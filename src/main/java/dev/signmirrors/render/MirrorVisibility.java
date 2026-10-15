package dev.signmirrors.render;

import dev.signmirrors.config.MirrorConfig;
import dev.signmirrors.core.MirrorView;
import dev.signmirrors.core.PlanarMath;
import dev.signmirrors.core.V3;
import dev.signmirrors.world.Mirror;
import dev.signmirrors.world.MirrorTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Once per frame, before captures: decides which mirrors are worth rendering and at what fidelity,
 * from the player's last view (matrices recorded by {@link MirrorDrawer}).
 * <ul>
 *   <li><b>Frustum culling</b> — the mirror's bounds against the view frustum.</li>
 *   <li><b>Occlusion culling</b> — rays from the camera to points on the mirror; it is hidden only if
 *       every ray is blocked by a solid block or by another mirror. Re-checked every few frames, with
 *       a grace period so mirrors don't pop.</li>
 *   <li><b>Level of detail</b> — screen coverage picks the probe resolution; coverage and how central
 *       the mirror is set its update priority; the reflection's actual sample directions say which
 *       cube faces are worth refreshing at all.</li>
 * </ul>
 */
final class MirrorVisibility {
    /** Occlusion rays target these points on the mirror, in (u, v) over the quad. */
    private static final double[][] SAMPLES = {
            {0.5, 0.5},
            {0.12, 0.12}, {0.88, 0.12}, {0.88, 0.88}, {0.12, 0.88},
            {0.5, 0.08}, {0.92, 0.5}, {0.5, 0.92}, {0.08, 0.5},
    };
    /** Frames the LOD must keep asking for a higher / lower resolution before the probe changes. */
    private static final int UPGRADE_FRAMES = 4;
    private static final int DOWNGRADE_FRAMES = 40;
    /** How much wider than the screen the framed region is, to cover movement until the next render. */
    private static final double REGION_MARGIN = 0.15;

    private MirrorVisibility() {}

    /** Updates every mirror's visibility and LOD; returns the visible ones, most important first. */
    static List<Mirror> update(Minecraft mc, long frame, MirrorConfig cfg) {
        Collection<Mirror> all = MirrorTracker.INSTANCE.all();
        Matrix4f viewProj = MirrorDrawer.mainViewProjection();
        Vec3 cam = MirrorDrawer.mainCameraPosition();
        if (cam == null) cam = mc.gameRenderer.mainCamera().position();
        V3 camV = new V3(cam.x, cam.y, cam.z);
        Vec3 lookVec = Vec3.directionFromRotation(mc.gameRenderer.mainCamera().xRot(), mc.gameRenderer.mainCamera().yRot());
        V3 look = new V3(lookVec.x, lookVec.y, lookVec.z);
        FrustumIntersection frustum = viewProj != null && cfg.frustumCulling ? new FrustumIntersection(viewProj) : null;
        int screenHeight = Math.max(1, mc.getWindow().getHeight());
        int screenWidth = Math.max(1, mc.getWindow().getWidth());
        double[] viewProjD = null;
        if (viewProj != null) {
            float[] f = new float[16];
            viewProj.get(f);
            viewProjD = new double[16];
            for (int i = 0; i < 16; i++) viewProjD[i] = f[i];
        }
        boolean planar = cfg.technique == MirrorConfig.Technique.PLANAR;

        // Pass 1: distance, facing and frustum.
        for (Mirror m : all) {
            m.inFrustum = false;
            V3 toCam = camV.sub(m.geometry.center());
            if (toCam.length() > cfg.maxMirrorDistance) continue;
            if (toCam.dot(m.geometry.normal()) <= 0) continue; // seen from behind: nothing to reflect
            if (frustum != null && !inFrustum(frustum, m, camV)) continue;
            m.inFrustum = true;
        }

        // Pass 2: occlusion, coverage, priority, faces, resolution.
        List<Mirror> visible = new ArrayList<>();
        for (Mirror m : all) {
            if (!m.inFrustum) {
                m.visible = false;
                continue;
            }
            if (!cfg.occlusionCulling) {
                m.lastSeenFrame = frame;
            } else if (frame >= m.nextOcclusionCheck) {
                if (anySampleVisible(mc, m, camV, all)) m.lastSeenFrame = frame;
                // Stagger checks so mirrors don't all raycast on the same frame.
                m.nextOcclusionCheck = frame + cfg.occlusionCheckInterval + (Math.floorMod(m.pos.hashCode(), cfg.occlusionCheckInterval));
            }
            m.visible = frame - m.lastSeenFrame <= Math.max(cfg.visibilityGraceFrames, cfg.occlusionCheckInterval * 2L);
            if (!m.visible) continue;

            m.coverage = viewProj != null ? coverage(viewProj, m, camV) : fallbackCoverage(m, camV);
            V3 toMirror = m.geometry.center().sub(camV).normalize();
            double centrality = Math.max(0, look.dot(toMirror));
            m.weight = (0.02 + m.coverage) * (0.35 + 0.65 * centrality * centrality);
            m.visibleFaceMask = m.box != null ? MirrorView.visibleFaceMask(m.geometry, m.box, camV) : 0;

            // The on-screen part of the mirror: planar captures frame just this, and its size on screen
            // sets the resolution, so close-ups get roughly one reflection texel per screen pixel.
            if (viewProjD != null) {
                V3[] region = PlanarMath.visibleRegion(m.geometry.corners(), viewProjD, camV, REGION_MARGIN);
                m.visibleRegion = region.length >= 3 ? region : m.geometry.corners();
                m.screenExtentPx = PlanarMath.screenExtentPixels(m.visibleRegion, viewProjD, camV, screenWidth, screenHeight);
            } else {
                m.visibleRegion = m.geometry.corners();
                m.screenExtentPx = Math.sqrt(m.coverage) * screenHeight;
            }

            int target = planar
                    ? MirrorView.resolutionForPixels(m.screenExtentPx, cfg.resolutionScale, cfg.minResolution, cfg.maxResolution)
                    : MirrorView.chooseResolution(m.coverage, screenHeight, cfg.resolutionScale,
                            cfg.minResolution, cfg.maxResolution);
            int current = m.pendingProbe != null ? m.pendingProbe.resolution
                    : m.probe != null ? m.probe.resolution : 0;
            if (target == m.targetResolution) {
                m.targetStableFrames++;
            } else {
                m.targetResolution = target;
                m.targetStableFrames = 0;
            }
            if (current == 0) m.targetStableFrames = Integer.MAX_VALUE / 2; // first probe: no waiting
            visible.add(m);
        }
        visible.sort(Comparator.comparingDouble((Mirror m) -> m.weight).reversed());
        return visible;
    }

    /** Whether the LOD has settled on a resolution different from the probe's for long enough. */
    static boolean shouldResize(Mirror m, int currentResolution) {
        if (m.targetResolution == currentResolution) return false;
        int needed = m.targetResolution > currentResolution ? UPGRADE_FRAMES : DOWNGRADE_FRAMES;
        return m.targetStableFrames >= needed;
    }

    private static boolean inFrustum(FrustumIntersection frustum, Mirror m, V3 cam) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (V3 c : m.geometry.corners()) {
            float x = (float) (c.x() - cam.x()), y = (float) (c.y() - cam.y()), z = (float) (c.z() - cam.z());
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
        }
        // The matrices are a frame old; a small margin stops mirrors popping at the screen edge while turning.
        float margin = 0.5f;
        return frustum.testAab(minX - margin, minY - margin, minZ - margin, maxX + margin, maxY + margin, maxZ + margin);
    }

    private static boolean anySampleVisible(Minecraft mc, Mirror m, V3 cam, Collection<Mirror> all) {
        V3[] c = m.geometry.corners();
        V3 eu = c[1].sub(c[0]), ev = c[3].sub(c[0]);
        V3 lift = m.geometry.normal().mul(0.05); // just in front of the glass, so the wall behind doesn't count
        for (double[] uv : SAMPLES) {
            V3 target = c[0].add(eu.mul(uv[0])).add(ev.mul(uv[1])).add(lift);
            V3 seg = target.sub(cam);
            double len = seg.length();
            if (len < 0.25) return true; // right up against it
            if (MirrorTracker.raycast(mc, cam, target) < len - 0.05) continue; // a block is in the way
            boolean blockedByMirror = false;
            for (Mirror other : all) {
                if (other == m || !other.inFrustum) continue;
                double t = MirrorView.rayQuad(cam, seg, other.geometry.corners());
                if (t >= 0 && t < 0.999) { blockedByMirror = true; break; }
            }
            if (!blockedByMirror) return true;
        }
        return false;
    }

    /** Fraction of the screen covered by the mirror's projected quad (clamped to the screen). */
    private static double coverage(Matrix4f viewProj, Mirror m, V3 cam) {
        double[] xy = new double[8];
        V3[] corners = m.geometry.corners();
        for (int i = 0; i < 4; i++) {
            V3 c = corners[i];
            Vector4f clip = viewProj.transform(new Vector4f(
                    (float) (c.x() - cam.x()), (float) (c.y() - cam.y()), (float) (c.z() - cam.z()), 1f));
            if (clip.w <= 0.05f) return 1.0; // a corner is at or behind the eye: the mirror fills the view
            xy[2 * i] = Math.max(-1, Math.min(1, clip.x / clip.w));
            xy[2 * i + 1] = Math.max(-1, Math.min(1, clip.y / clip.w));
        }
        return Math.min(1.0, MirrorView.polygonArea(xy) / 4.0);
    }

    /** Before the first frame has recorded matrices: solid angle estimate assuming a ~70 degree view. */
    private static double fallbackCoverage(Mirror m, V3 cam) {
        V3[] c = m.geometry.corners();
        double area = c[1].sub(c[0]).length() * c[3].sub(c[0]).length();
        V3 to = m.geometry.center().sub(cam);
        double d2 = Math.max(0.25, to.dot(to));
        double cos = Math.abs(to.normalize().dot(m.geometry.normal()));
        return Math.min(1.0, area * cos / d2 / 2.0);
    }
}
