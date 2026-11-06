package dev.signmirrors.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.GpuFormat;
import dev.signmirrors.SignMirrorsClient;
import dev.signmirrors.world.MirrorTracker;
import dev.signmirrors.core.MirrorView;
import dev.signmirrors.config.MirrorConfig;
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
 * ({@link MirrorVisibility}), picks the most valuable mirrors to refresh (config.rendersPerFrame,
 * by priority) and renders each one from the player's eye reflected through the mirror as a normal
 * vanilla extract + renderLevel pass, with:
 * <ul>
 *   <li>the camera entity switched to an invisible probe entity (so the player model is drawn),</li>
 *   <li>the game's main render target swapped for the mirror's own square target,</li>
 *   <li>a square projection framing just the on-screen part of the mirror, with an oblique near
 *       plane on the glass, and no view bobbing, hand or block outline (see mixins).</li>
 * </ul>
 * Everything is restored before the player's own extraction runs, so the main frame is untouched.
 */
public final class MirrorCapture {
    private static boolean capturing;
    private static Mirror currentMirror;
    private static int captureResolution = 256;
    private static long frame;
    /** Extra renders allowed in one frame for mirrors that have nothing to show yet. */
    private static final int MAX_BURST = 5;
    private static ProbeEntity probeEntity;
    private static boolean levelExtracted;
    private static boolean warnedPose;
    private static boolean loggedProjection;

    // Per-capture projection state, read by the projection hook and the FOV hook.
    private static double captureFovDegrees = 90;
    /** World-space mirror plane to clip against (normal, point on plane), or null outside captures. */
    private static V3 clipNormal;
    private static V3 clipPoint;
    private static Vec3 captureCameraPos;
    private static Matrix4f captureViewRotation;
    /** Re-render much sooner once the player's eye has moved. */
    private static final double EYE_MOVED_BOOST = 25.0;
    /** Cosine of the look-direction change (about 1.5 degrees) that counts as moved. */
    private static final double LOOK_CHANGED_COS = Math.cos(Math.toRadians(1.5));

    private MirrorCapture() {}

    public static boolean isCapturing() { return capturing; }

    /** With a shader pack, whether this frame's state allows reflections (see IrisCompat). */
    private static boolean shaderPack, reflectionsAvailable = true;

    public static boolean shaderPackActive() { return shaderPack; }

    /** False while a shader pack is on and reflections can't be rendered with it: mirrors show their tint. */
    public static boolean reflectionsAvailable() { return reflectionsAvailable; }
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

    /** One render this frame: a mirror's reflected view. */
    private record Job(Mirror mirror, MirrorProbe probe, double score) {}

    /** Called at the head of GameRenderer.extract for the player's frame. */
    public static void runCaptures(GameRenderer gameRenderer) {
        MirrorConfig cfg = SignMirrorsClient.config;
        Minecraft mc = Minecraft.getInstance();
        frame++;
        shaderPack = IrisCompat.shaderPackActive();
        reflectionsAvailable = !shaderPack || IrisCompat.canCapture();
        if (!cfg.enabled || mc.level == null || mc.player == null) return;

        List<Mirror> visible = MirrorVisibility.update(mc, frame, cfg);
        if (visible.isEmpty() || budget(cfg) <= 0 || !reflectionsAvailable) return;
        List<Mirror> active = visible.size() > cfg.maxActiveMirrors
                ? visible.subList(0, cfg.maxActiveMirrors) : visible;

        RenderTarget mainTarget = gameRenderer.mainRenderTarget();
        GpuFormat colorFormat = mainTarget.getColorTexture().getFormat();
        GpuFormat depthFormat = mainTarget.getDepthTexture().getFormat();
        for (Mirror m : active) prepareProbes(m, colorFormat, depthFormat);

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

        Context ctx = new Context(mc, gameRenderer, accessor, camera, deltaTracker, cfg);
        capturing = true;
        try {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            // The reflected camera sits behind the wall, inside blocks, where chunk occlusion culling hides everything.
            mc.smartCull = false;
            mc.setCameraEntity(probeEntity);
            // The camera follows its own entity field, not Minecraft's camera entity, so attach it directly,
            // and drop the player's eye height (the marker's eye is at its position).
            cameraAccess.signmirrors$setEntity(probeEntity);
            cameraAccess.signmirrors$setEyeHeight(0f);
            cameraAccess.signmirrors$setEyeHeightOld(0f);

            for (Job job : jobs) {
                Mirror mirror = job.mirror();
                V3 eyeV = new V3(eye.x, eye.y, eye.z);
                // Mirrors seen in this reflection are re-rendered first, from this reflection's own
                // viewpoint, so they're live (not frozen) inside it.
                if (cfg.recursiveMirrors && cfg.nestedMirrors > 0) {
                    V3 virtualEye = PlanarMath.reflectPoint(eyeV, mirror.geometry.center(), mirror.geometry.normal());
                    for (Mirror inner : nestedCandidates(mirror, virtualEye, cfg)) {
                        MirrorProbe nested = nestedProbe(inner, mirror, job.probe().resolution, colorFormat, depthFormat);
                        renderView(ctx, inner, nested, virtualEye, inner.geometry.corners(), eye, look);
                    }
                }
                V3[] region = mirror.visibleRegion != null && mirror.visibleRegion.length >= 3
                        ? mirror.visibleRegion : mirror.geometry.corners();
                renderView(ctx, mirror, job.probe(), eyeV, region, eye, look);
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
        if (frame % 30 == 0) expireNested();
    }

    /** What one capture needs from runCaptures. */
    private record Context(Minecraft mc, GameRenderer gameRenderer, GameRendererAccessor accessor, Camera camera,
                           DeltaTracker deltaTracker, MirrorConfig cfg) {}

    /**
     * Renders the world into {@code probe} as {@code mirror} reflects it for a viewer at
     * {@code viewerEye}: from that eye mirrored through the glass, framing {@code region}.
     * {@code playerEye} / {@code playerLook} are recorded to tell later when it's out of date.
     */
    private static void renderView(Context ctx, Mirror mirror, MirrorProbe probe, V3 viewerEye, V3[] region,
                                   Vec3 playerEye, Vec3 playerLook) {
        Minecraft mc = ctx.mc();
        GameRenderer gameRenderer = ctx.gameRenderer();
        GameRendererAccessor accessor = ctx.accessor();
        Camera camera = ctx.camera();
        DeltaTracker deltaTracker = ctx.deltaTracker();
        MirrorConfig cfg = ctx.cfg();
        Vec3 eye = playerEye;
        Vec3 look = playerLook;
        if (!cfg.enabled) return;
        currentMirror = mirror;
        captureResolution = probe.resolution;
        accessor.signmirrors$setMainRenderTarget(probe.target());

        // Render the real world from the eye reflected through the mirror plane, aimed at the
        // mirror, clipping everything on the far side of the plane (the wall and beyond).
        V3 n = mirror.geometry.normal();
        V3 virtualEye = PlanarMath.reflectPoint(viewerEye, mirror.geometry.center(), n);
        // Frame only the part of the mirror that's on screen (not the whole mirror), so the
        // render's texels go where they're seen and the FOV stays sane up close.
        PlanarMath.Aim aim = PlanarMath.aim(virtualEye, region);
        captureFovDegrees = aim.fovDegrees();
        clipNormal = n;
        clipPoint = mirror.geometry.center().sub(n.mul(0.01)); // keep things touching the glass
        poseCamera(camera, deltaTracker, virtualEye, aim.yaw(), aim.pitch());

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
            return;
        }

        CameraRenderState cameraState = accessor.signmirrors$gameRenderState().levelRenderState.cameraRenderState;
        camera.extractRenderState(cameraState, deltaTracker);
        squareProjection(cameraState);
        captureCameraPos = cameraState.pos;
        captureViewRotation = new Matrix4f(cameraState.viewRotationMatrix);
        SodiumCompat.beforeExtraLevelRender();
        if (shaderPack) IrisCompat.beforeCapture();
        try {
            accessor.signmirrors$renderLevel();
        } finally {
            if (shaderPack) IrisCompat.afterCapture();
        }

        Matrix4f proj = MirrorDrawer.lastProjection();
        if (proj == null) proj = new Matrix4f(cameraState.projectionMatrix);
        probe.viewProj = new Matrix4f(proj).mul(captureViewRotation);
        probe.origin = captureCameraPos;
        probe.eye = eye;
        probe.look = look;
        probe.markCaptured(frame);
    }

    /** Nested textures unused for this many frames are freed. */
    private static final long NESTED_EXPIRY_FRAMES = 120;

    /**
     * Mirrors visible inside {@code outer}'s reflection, as seen from {@code virtualEye} (the viewer's
     * eye mirrored through {@code outer}), biggest first, at most config.nestedMirrors. A mirror
     * counts if it's in front of {@code outer}, faces the virtual eye, is in range, and the line from
     * the virtual eye to its centre or a corner passes through {@code outer}'s glass.
     */
    private static List<Mirror> nestedCandidates(Mirror outer, V3 virtualEye, MirrorConfig cfg) {
        record Scored(Mirror m, double score) {}
        List<Scored> found = new ArrayList<>();
        V3 outerN = outer.geometry.normal(), outerC = outer.geometry.center();
        for (Mirror inner : MirrorTracker.INSTANCE.all()) {
            if (inner == outer || !inner.reflects()) continue;
            V3 c = inner.geometry.center();
            V3 toEye = virtualEye.sub(c);
            double dist = toEye.length();
            if (dist > cfg.maxMirrorDistance || dist < 1e-3) continue;
            if (toEye.dot(inner.geometry.normal()) <= 0) continue; // seen from behind
            boolean inFront = false;
            for (V3 p : inner.geometry.corners()) if (p.sub(outerC).dot(outerN) > 0.01) inFront = true;
            if (!inFront) continue; // behind the outer mirror's plane: clipped from its reflection
            boolean throughGlass = false;
            V3[] points = {c, inner.geometry.corners()[0], inner.geometry.corners()[1],
                    inner.geometry.corners()[2], inner.geometry.corners()[3]};
            for (V3 p : points) {
                double t = MirrorView.rayQuad(virtualEye, p.sub(virtualEye), outer.geometry.corners());
                if (t > 0 && t < 1) { throughGlass = true; break; }
            }
            if (!throughGlass) continue;
            double area = inner.geometry.width() * inner.geometry.height();
            double cos = Math.abs(toEye.normalize().dot(inner.geometry.normal()));
            found.add(new Scored(inner, area * cos / (dist * dist)));
        }
        found.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Mirror> out = new ArrayList<>();
        for (int i = 0; i < found.size() && i < cfg.nestedMirrors; i++) out.add(found.get(i).m());
        return out;
    }

    /** {@code inner}'s texture for being seen inside {@code outer}'s reflection, (re)made at the right size. */
    private static MirrorProbe nestedProbe(Mirror inner, Mirror outer, int outerResolution,
                                           GpuFormat colorFormat, GpuFormat depthFormat) {
        MirrorConfig cfg = SignMirrorsClient.config;
        int res = shaderPack ? cfg.irisResolution
                : Math.max(32, Math.min(cfg.maxResolution, outerResolution / 2));
        MirrorProbe probe = inner.nested.get(outer);
        if (probe != null && (probe.resolution != res || !probe.matches(colorFormat, depthFormat))) {
            probe.release();
            probe = null;
        }
        if (probe == null) {
            probe = new MirrorProbe(res, colorFormat, depthFormat);
            inner.nested.put(outer, probe);
        }
        return probe;
    }

    /** Frees nested textures that haven't been rendered lately or whose outer mirror is gone. */
    private static void expireNested() {
        var live = MirrorTracker.INSTANCE.all();
        for (Mirror m : live) {
            if (m.nested.isEmpty()) continue;
            m.nested.entrySet().removeIf(e -> {
                boolean stale = !live.contains(e.getKey()) || e.getKey().probe == null
                        || frame - e.getValue().capturedFrame() > NESTED_EXPIRY_FRAMES;
                if (stale) e.getValue().release();
                return stale;
            });
        }
    }

    /** Reflection renders allowed per frame: each is a full shader-pack render when one is on. */
    private static int budget(MirrorConfig cfg) {
        return shaderPack ? cfg.irisRendersPerFrame : cfg.rendersPerFrame;
    }

    /** Creates the first probe, or starts a probe at a new resolution when the LOD has settled on one. */
    private static void prepareProbes(Mirror m, GpuFormat colorFormat, GpuFormat depthFormat) {
        if (!m.reflects()) return;
        int res = m.targetResolution > 0 ? m.targetResolution : SignMirrorsClient.config.minResolution;
        if (m.probe != null && !m.probe.matches(colorFormat, depthFormat)) m.releaseProbe(); // formats changed
        if (shaderPack && m.probe != null && m.probe.resolution != SignMirrorsClient.config.irisResolution) {
            m.releaseProbe(); // shader packs: one shared size, switch at once
        }
        if (m.probe == null) {
            m.probe = new MirrorProbe(res, colorFormat, depthFormat);
            return;
        }
        int current = m.pendingProbe != null ? m.pendingProbe.resolution : m.probe.resolution;
        if (MirrorVisibility.shouldResize(m, current)) {
            if (m.pendingProbe != null) m.pendingProbe.release();
            m.pendingProbe = m.targetResolution == m.probe.resolution
                    ? null // went back to the size already on screen
                    : new MirrorProbe(m.targetResolution, colorFormat, depthFormat);
        }
    }

    /** Once the new-resolution probe has been rendered, swap it in (so a resize never flashes). */
    private static void promotePendingProbe(Mirror m) {
        MirrorProbe pending = m.pendingProbe;
        if (pending == null || !pending.isCaptured()) return;
        m.probe.release();
        m.probe = pending;
        m.pendingProbe = null;
    }

    /**
     * Chooses which mirrors to re-render: each scores {@code weight x frames since its last render},
     * much more once the player's eye or view has moved (the reflection depends on both). Mirrors
     * with nothing to show yet jump the queue, and up to {@link #MAX_BURST} of them may exceed the
     * per-frame budget so new mirrors (and resolution changes) fill in immediately.
     */
    private static List<Job> pickJobs(List<Mirror> active, MirrorConfig cfg, Vec3 eye, Vec3 look) {
        List<Job> candidates = new ArrayList<>();
        for (Mirror m : active) {
            MirrorProbe target = m.captureTarget();
            if (target == null || !m.reflects()) continue;
            double score;
            if (!target.isCaptured()) {
                score = 1e12 * (1 + m.weight);
            } else {
                long age = Math.max(1, frame - target.capturedFrame());
                boolean moved = target.eye == null || target.eye.distanceToSqr(eye) > 1e-5
                        || target.look == null || target.look.dot(look) < LOOK_CHANGED_COS;
                score = m.weight * age * (moved ? EYE_MOVED_BOOST : 1.0);
            }
            candidates.add(new Job(m, target, score));
        }
        candidates.sort(Comparator.comparingDouble(Job::score).reversed());

        List<Job> jobs = new ArrayList<>();
        int burst = 0;
        for (Job job : candidates) {
            boolean urgent = job.score() >= 1e12;
            if (jobs.size() - burst < budget(cfg)) {
                jobs.add(job);
            } else if (urgent && burst < (shaderPack ? 1 : MAX_BURST)) {
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
     * projection: an exact square field of view just wide enough to cover the mirror, and an oblique
     * near plane on the mirror so the wall and everything behind it is clipped away.
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
