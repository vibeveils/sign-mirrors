package dev.signmirrors.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.GpuFormat;
import dev.signmirrors.SignMirrorsClient;
import dev.signmirrors.config.MirrorConfig;
import dev.signmirrors.core.CubeFace;
import dev.signmirrors.core.PlanarMath;
import dev.signmirrors.core.V3;
import dev.signmirrors.mixin.CameraAccessor;
import dev.signmirrors.mixin.GameRendererAccessor;
import dev.signmirrors.mixin.LevelExtractorAccessor;
import dev.signmirrors.world.Mirror;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The secondary camera. At the start of each frame's extraction it runs the visibility/LOD pass
 * ({@link MirrorVisibility}), picks the most valuable cube faces to refresh (config.facesPerFrame,
 * by priority) and renders each one from its mirror's probe position as a normal vanilla
 * extract + renderLevel pass, with:
 * <ul>
 *   <li>the camera entity switched to an invisible probe entity (so the player model is drawn),</li>
 *   <li>the game's main render target swapped for the face's own square target,</li>
 *   <li>a 90 degree, square projection, and no view bobbing, hand or block outline (see mixins).</li>
 * </ul>
 * Everything is restored before the player's own extraction runs, so the main frame is untouched.
 */
public final class MirrorCapture {
    private static boolean capturing;
    private static Mirror currentMirror;
    private static int captureResolution = 256;
    private static long frame;
    /** Extra faces allowed in one frame for never-captured, visible faces. */
    private static final int MAX_BURST = 5;
    /** How much less often faces the reflection can't currently show are refreshed. */
    private static final double HIDDEN_FACE_FACTOR = 0.05;
    private static ProbeEntity probeEntity;
    private static boolean levelExtracted;
    private static boolean warnedPose;
    private static boolean loggedProjection;

    // Per-capture projection state, read by the projection hook and the FOV hook.
    private static double captureFovDegrees = 90;
    /** Planar: world-space mirror plane to clip against (normal, point on plane), or null for cube faces. */
    private static V3 clipNormal;
    private static V3 clipPoint;
    private static Vec3 captureCameraPos;
    private static Matrix4f captureViewRotation;
    /** Planar mode re-renders much sooner once the player's eye has moved. */
    private static final double EYE_MOVED_BOOST = 25.0;
    /** Cosine of the look-direction change (about 1.5 degrees) that counts as moved. */
    private static final double LOOK_CHANGED_COS = Math.cos(Math.toRadians(1.5));

    private MirrorCapture() {}

    public static boolean isCapturing() { return capturing; }
    public static int resolution() { return captureResolution; }
    public static Mirror currentMirror() { return currentMirror; }
    /** Keeps Sodium's terrain projection in step with the capture projection (no-op without Sodium). */
    public static void syncSodiumProjection(GameRenderer gameRenderer, Matrix4f projection) {
        SodiumCompat.setProjection(gameRenderer, projection);
    }

    /** Field of view (degrees) the current capture needs; read by CameraMixin. */
    public static float captureFovDegrees() { return (float) captureFovDegrees; }

    /** Fabric LevelExtractionEvents.END_EXTRACTION: proves the level was actually extracted for a capture. */
    public static void onLevelExtracted() {
        if (capturing) levelExtracted = true;
    }

    /** One render this frame: a cube face, or (planar probes) the mirror's single reflected view. */
    private record Job(Mirror mirror, MirrorProbe probe, CubeFace face, double score) {}

    /** Called at the head of GameRenderer.extract for the player's frame. */
    public static void runCaptures(GameRenderer gameRenderer) {
        MirrorConfig cfg = SignMirrorsClient.config;
        Minecraft mc = Minecraft.getInstance();
        frame++;
        if (!cfg.enabled || mc.level == null || mc.player == null) return;

        List<Mirror> visible = MirrorVisibility.update(mc, frame, cfg);
        if (visible.isEmpty() || cfg.facesPerFrame <= 0) return;
        List<Mirror> active = visible.size() > cfg.maxActiveMirrors
                ? visible.subList(0, cfg.maxActiveMirrors) : visible;

        RenderTarget mainTarget = gameRenderer.mainRenderTarget();
        GpuFormat colorFormat = mainTarget.getColorTexture().getFormat();
        GpuFormat depthFormat = mainTarget.getDepthTexture().getFormat();
        boolean planar = cfg.technique == MirrorConfig.Technique.PLANAR;
        for (Mirror m : active) prepareProbes(m, colorFormat, depthFormat, planar);

        Camera camera = gameRenderer.mainCamera();
        // GameRenderer.update has already placed the camera for this frame: this is where the player sees from.
        Vec3 eye = camera.position();
        Vec3 look = Vec3.directionFromRotation(camera.xRot(), camera.yRot());

        List<Job> jobs = pickJobs(active, cfg, eye, look);
        if (jobs.isEmpty()) return;

        GameRendererAccessor accessor = (GameRendererAccessor) gameRenderer;
        if (probeEntity == null || probeEntity.level() != mc.level) probeEntity = new ProbeEntity(mc.level);

        DeltaTracker deltaTracker = mc.getDeltaTracker();
        Entity savedCameraEntity = mc.getCameraEntity();
        CameraType savedCameraType = mc.options.getCameraType();
        boolean savedSmartCull = mc.smartCull;
        CameraAccessor cameraAccess = (CameraAccessor) camera;
        Entity savedCameraOwnEntity = cameraAccess.signmirrors$entity();
        float savedEyeHeight = cameraAccess.signmirrors$eyeHeight();
        float savedEyeHeightOld = cameraAccess.signmirrors$eyeHeightOld();

        capturing = true;
        try {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            // Planar cameras sit behind the wall, inside blocks, where chunk occlusion culling hides everything.
            if (cfg.disableOcclusionCullingDuringCapture || planar) mc.smartCull = false;
            mc.setCameraEntity(probeEntity);
            // The camera follows its own entity field, not Minecraft's camera entity, so attach it directly,
            // and drop the player's eye height (the marker's eye is at its position).
            cameraAccess.signmirrors$setEntity(probeEntity);
            cameraAccess.signmirrors$setEyeHeight(0f);
            cameraAccess.signmirrors$setEyeHeightOld(0f);

            for (Job job : jobs) {
                Mirror mirror = job.mirror();
                MirrorProbe probe = job.probe();
                CubeFace face = job.face();
                currentMirror = mirror;
                captureResolution = probe.resolution;
                accessor.signmirrors$setMainRenderTarget(probe.face(face));

                if (probe.planar) {
                    // Render the real world from the eye reflected through the mirror plane, aimed at the
                    // mirror, clipping everything on the far side of the plane (the wall and beyond).
                    V3 n = mirror.geometry.normal();
                    V3 eyeV = new V3(eye.x, eye.y, eye.z);
                    V3 virtualEye = PlanarMath.reflectPoint(eyeV, mirror.geometry.center(), n);
                    // Frame only the part of the mirror that's on screen (not the whole mirror), so the
                    // render's texels go where they're seen and the FOV stays sane up close.
                    V3[] region = mirror.visibleRegion != null && mirror.visibleRegion.length >= 3
                            ? mirror.visibleRegion : mirror.geometry.corners();
                    PlanarMath.Aim aim = PlanarMath.aim(virtualEye, region);
                    captureFovDegrees = aim.fovDegrees();
                    clipNormal = n;
                    clipPoint = mirror.geometry.center().sub(n.mul(0.01)); // keep things touching the glass
                    poseCamera(camera, deltaTracker, virtualEye, aim.yaw(), aim.pitch());
                } else {
                    captureFovDegrees = 90;
                    clipNormal = null;
                    clipPoint = null;
                    poseCamera(camera, deltaTracker, mirror.geometry.probe(), face.yaw, face.pitch);
                }

                levelExtracted = false;
                gameRenderer.extract(deltaTracker, false);
                if (!levelExtracted) {
                    // GameRenderer.extract can skip the level (it did on 26.3 with advanceGameTime=false);
                    // run the level extraction for the probe camera directly.
                    ((LevelExtractorAccessor) mc.levelExtractor).signmirrors$extract(deltaTracker, camera,
                            deltaTracker.getGameTimeDeltaPartialTick(false));
                }
                if (!levelExtracted) {
                    // Rendering now would draw stale state (and breaks Sodium), so stop safely instead.
                    SignMirrorsClient.LOGGER.error("Level extraction did not run for the mirror camera; disabling mirrors");
                    cfg.enabled = false;
                    break;
                }

                CameraRenderState cameraState = accessor.signmirrors$gameRenderState().levelRenderState.cameraRenderState;
                camera.extractRenderState(cameraState, deltaTracker);
                squareProjection(cameraState);
                captureCameraPos = cameraState.pos;
                captureViewRotation = new Matrix4f(cameraState.viewRotationMatrix);
                SodiumCompat.beforeExtraLevelRender();
                accessor.signmirrors$renderLevel();

                if (probe.planar) {
                    Matrix4f proj = MirrorDrawer.lastProjection();
                    if (proj == null) proj = new Matrix4f(cameraState.projectionMatrix);
                    probe.planarViewProj = new Matrix4f(proj).mul(captureViewRotation);
                    probe.planarOrigin = captureCameraPos;
                    probe.planarEye = eye;
                    probe.planarLook = look;
                }
                probe.markCaptured(face, frame);
            }
        } catch (Throwable t) {
            SignMirrorsClient.LOGGER.error("Mirror capture failed; disabling mirrors for this session", t);
            cfg.enabled = false;
        } finally {
            accessor.signmirrors$setMainRenderTarget(mainTarget);
            mc.setCameraEntity(savedCameraEntity);
            cameraAccess.signmirrors$setEntity(savedCameraOwnEntity);
            cameraAccess.signmirrors$setEyeHeight(savedEyeHeight);
            cameraAccess.signmirrors$setEyeHeightOld(savedEyeHeightOld);
            mc.options.setCameraType(savedCameraType);
            mc.smartCull = savedSmartCull;
            capturing = false;
            currentMirror = null;
            clipNormal = null;
            clipPoint = null;
            captureFovDegrees = 90;
            camera.update(deltaTracker);
        }

        for (Mirror m : active) promotePendingProbe(m);
    }

    /** Creates the first probe, or starts a probe at a new resolution when the LOD has settled on one. */
    private static void prepareProbes(Mirror m, GpuFormat colorFormat, GpuFormat depthFormat, boolean planar) {
        if (!planar && m.geometry.neededFaces().isEmpty()) return;
        int res = m.targetResolution > 0 ? m.targetResolution : SignMirrorsClient.config.minResolution;
        if (m.probe != null && (m.probe.planar != planar
                || !m.probe.matches(m.probe.resolution, colorFormat, depthFormat))) {
            // Technique or formats changed: start over.
            m.releaseProbe();
        }
        if (m.probe == null) {
            m.probe = newProbe(m, res, colorFormat, depthFormat, planar);
            return;
        }
        int current = m.pendingProbe != null ? m.pendingProbe.resolution : m.probe.resolution;
        if (MirrorVisibility.shouldResize(m, current)) {
            if (m.pendingProbe != null) m.pendingProbe.release();
            m.pendingProbe = m.targetResolution == m.probe.resolution
                    ? null // went back to the size already on screen
                    : newProbe(m, m.targetResolution, colorFormat, depthFormat, planar);
        }
    }

    private static MirrorProbe newProbe(Mirror m, int res, GpuFormat color, GpuFormat depth, boolean planar) {
        return planar ? MirrorProbe.planar(res, color, depth)
                : new MirrorProbe(res, color, depth, m.geometry.neededFaces());
    }

    /** Once the new-resolution probe holds every face the reflection shows, swap it in. */
    private static void promotePendingProbe(Mirror m) {
        MirrorProbe pending = m.pendingProbe;
        if (pending == null) return;
        if (pending.planar) {
            if (pending.capturedMask() != 0) {
                m.probe.release();
                m.probe = pending;
                m.pendingProbe = null;
            }
            return;
        }
        int needed = m.visibleFaceMask != 0 ? m.visibleFaceMask : m.probe.capturedMask();
        int neededAllocated = 0;
        for (CubeFace f : m.geometry.neededFaces()) neededAllocated |= 1 << f.ordinal();
        needed &= neededAllocated;
        if ((pending.capturedMask() & needed) == needed && pending.capturedMask() != 0) {
            m.probe.release();
            m.probe = pending;
            m.pendingProbe = null;
        }
    }

    /**
     * Chooses which faces to re-render. Each candidate face scores
     * {@code mirror weight x frames since last capture}, discounted heavily when the reflection
     * can't currently show that face. Visible faces that have never been captured jump the queue,
     * and up to {@link #MAX_BURST} of them may exceed the per-frame budget so new mirrors (and
     * resolution changes) fill in immediately rather than showing grey.
     */
    private static List<Job> pickJobs(List<Mirror> active, MirrorConfig cfg, Vec3 eye, Vec3 look) {
        List<Job> candidates = new ArrayList<>();
        for (Mirror m : active) {
            MirrorProbe target = m.captureTarget();
            if (target == null) continue;
            if (target.planar) {
                // One view per mirror; it depends only on where the eye is, so moving makes it urgent.
                CubeFace slot = MirrorProbe.PLANAR_SLOT;
                double score;
                if (!target.isCaptured(slot)) {
                    score = 1e12 * (1 + m.weight);
                } else {
                    long age = Math.max(1, frame - target.capturedFrame(slot));
                    // The view depends on the eye position, and the framed region on the look direction.
                    boolean moved = target.planarEye == null || target.planarEye.distanceToSqr(eye) > 1e-5
                            || target.planarLook == null || target.planarLook.dot(look) < LOOK_CHANGED_COS;
                    score = m.weight * age * (moved ? EYE_MOVED_BOOST : 1.0);
                }
                candidates.add(new Job(m, target, slot, score));
                continue;
            }
            if (m.box == null) continue;
            for (CubeFace face : m.geometry.neededFaces()) {
                boolean shown = (m.visibleFaceMask & (1 << face.ordinal())) != 0;
                if (!shown && !cfg.refreshHiddenFaces && target.isCaptured(face)) continue;
                double score;
                if (!target.isCaptured(face)) {
                    score = (shown ? 1e12 : 1e9) * (1 + m.weight);
                } else {
                    long age = Math.max(1, frame - target.capturedFrame(face));
                    score = m.weight * age * (shown ? 1.0 : HIDDEN_FACE_FACTOR);
                }
                candidates.add(new Job(m, target, face, score));
            }
        }
        candidates.sort(Comparator.comparingDouble(Job::score).reversed());

        List<Job> jobs = new ArrayList<>();
        int burst = 0;
        for (Job job : candidates) {
            boolean urgent = job.score() >= 1e12;
            if (jobs.size() - burst < cfg.facesPerFrame) {
                jobs.add(job);
            } else if (urgent && burst < MAX_BURST) {
                jobs.add(job);
                burst++;
            } else {
                break;
            }
        }
        return jobs;
    }

    /**
     * Points the camera at the probe. Camera.update derives the pose from the camera entity but
     * also adds an eye-height that it eases toward over a few ticks, so measure where the camera
     * actually landed and correct the entity once.
     */
    private static void poseCamera(Camera camera, DeltaTracker deltaTracker, V3 target, float yaw, float pitch) {
        Vec3 want = new Vec3(target.x(), target.y(), target.z());
        probeEntity.pose(want, yaw, pitch);
        camera.update(deltaTracker);
        Vec3 error = want.subtract(camera.position());
        if (error.lengthSqr() > 1e-8) {
            probeEntity.pose(want.add(error), yaw, pitch);
            camera.update(deltaTracker);
        }
        double off = camera.position().distanceTo(want);
        if (off > 0.05 && !warnedPose) {
            warnedPose = true;
            SignMirrorsClient.LOGGER.warn("Mirror camera landed {} blocks from its probe point (camera at {}, wanted {})",
                    String.format("%.2f", off), camera.position(), want);
        }
    }

    /** Applies the capture's field of view to the extracted camera state too (used for drawing mirrors in it). */
    private static void squareProjection(CameraRenderState state) {
        state.projectionMatrix = fovProjection(new Matrix4f(state.projectionMatrix));
    }

    /**
     * The projection for the current capture, applied where every level render uploads its
     * projection: an exact square field of view (90 degrees for cube faces, just enough to cover the
     * mirror for planar views) and, for planar views, an oblique near plane on the mirror so the wall
     * and everything behind it is clipped away.
     */
    public static Matrix4f captureProjection(Matrix4f in) {
        Matrix4f p = fovProjection(in);
        if (clipNormal == null || captureCameraPos == null || captureViewRotation == null) return p;
        // Mirror plane in camera-relative world space, then rotated into view space.
        Vec3 cam = captureCameraPos;
        double w = clipNormal.x() * (cam.x - clipPoint.x()) + clipNormal.y() * (cam.y - clipPoint.y())
                + clipNormal.z() * (cam.z - clipPoint.z());
        Vector3f nView = captureViewRotation.transformDirection(
                new Vector3f((float) clipNormal.x(), (float) clipNormal.y(), (float) clipNormal.z()));
        float[] m = new float[16];
        p.get(m);
        double[] md = new double[16];
        for (int i = 0; i < 16; i++) md[i] = m[i];
        double[] clipped = PlanarMath.obliqueClip(md, new double[] {nView.x, nView.y, nView.z, w});
        if (clipped == null) return p; // mirror plane not in view; nothing sensible to clip
        for (int i = 0; i < 16; i++) m[i] = (float) clipped[i];
        return new Matrix4f().set(m);
    }

    /**
     * Turns whatever perspective projection the game built into an exact square one with the
     * capture's field of view: both focal terms become 1/tan(fov/2) and any skew or off-centre terms
     * are cleared, while the depth terms are kept, so depth range, reverse-Z and handedness stay intact.
     */
    private static Matrix4f fovProjection(Matrix4f in) {
        if (!loggedProjection) {
            loggedProjection = true;
            SignMirrorsClient.LOGGER.info("Mirror capture projection before correction: m00={} m11={} (exact 90x90 is 1, 1)",
                    in.m00(), in.m11());
        }
        float focal = (float) (1.0 / Math.tan(Math.toRadians(captureFovDegrees) / 2));
        Matrix4f p = new Matrix4f(in);
        p.m00(Math.copySign(focal, in.m00()));
        p.m11(Math.copySign(focal, in.m11()));
        p.m01(0f);
        p.m10(0f);
        p.m20(0f);
        p.m21(0f);
        return p;
    }
}
