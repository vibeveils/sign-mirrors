package dev.signmirrors.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.GpuFormat;
import dev.signmirrors.SignMirrorsClient;
import dev.signmirrors.config.MirrorConfig;
import dev.signmirrors.core.CubeFace;
import dev.signmirrors.core.V3;
import dev.signmirrors.mixin.CameraAccessor;
import dev.signmirrors.mixin.GameRendererAccessor;
import dev.signmirrors.mixin.LevelExtractorAccessor;
import dev.signmirrors.world.Mirror;
import dev.signmirrors.world.MirrorTracker;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;

/**
 * The secondary camera. At the start of each frame's extraction it renders a few cube faces
 * (config.facesPerFrame) from mirror probe positions, each as a normal vanilla
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
    private static int mirrorCursor;
    private static ProbeEntity probeEntity;
    private static boolean levelExtracted;
    private static boolean warnedPose;
    private static boolean loggedProjection;

    private MirrorCapture() {}

    public static boolean isCapturing() { return capturing; }
    public static int resolution() { return captureResolution; }
    public static Mirror currentMirror() { return currentMirror; }

    /** Fabric LevelExtractionEvents.END_EXTRACTION: proves the level was actually extracted for a capture. */
    public static void onLevelExtracted() {
        if (capturing) levelExtracted = true;
    }

    /** Called at the head of GameRenderer.extract for the player's frame. */
    public static void runCaptures(GameRenderer gameRenderer) {
        MirrorConfig cfg = SignMirrorsClient.config;
        Minecraft mc = Minecraft.getInstance();
        if (!cfg.enabled || cfg.facesPerFrame <= 0 || mc.level == null || mc.player == null) return;

        Camera camera = gameRenderer.mainCamera();
        Vec3 look = Vec3.directionFromRotation(camera.xRot(), camera.yRot());
        List<Mirror> active = MirrorTracker.INSTANCE.active(camera.position(), look, cfg);
        if (active.isEmpty()) return;

        GameRendererAccessor accessor = (GameRendererAccessor) gameRenderer;
        RenderTarget mainTarget = gameRenderer.mainRenderTarget();
        GpuFormat colorFormat = mainTarget.getColorTexture().getFormat();
        GpuFormat depthFormat = mainTarget.getDepthTexture().getFormat();
        int res = cfg.probeResolution;

        for (Mirror m : active) {
            if (m.probe != null && !m.probe.matches(res, colorFormat, depthFormat)) {
                m.probe.release();
                m.probe = null;
            }
            if (m.probe == null && !m.geometry.neededFaces().isEmpty()) {
                m.probe = new MirrorProbe(res, colorFormat, depthFormat, m.geometry.neededFaces());
                m.capturedFaces = 0;
            }
        }

        if (probeEntity == null || probeEntity.level() != mc.level) probeEntity = new ProbeEntity(mc.level);

        DeltaTracker deltaTracker = mc.getDeltaTracker();
        Entity savedCameraEntity = mc.getCameraEntity();
        CameraType savedCameraType = mc.options.getCameraType();
        boolean savedSmartCull = mc.smartCull;
        CameraAccessor cameraAccess = (CameraAccessor) camera;
        Entity savedCameraOwnEntity = cameraAccess.signmirrors$entity();
        float savedEyeHeight = cameraAccess.signmirrors$eyeHeight();
        float savedEyeHeightOld = cameraAccess.signmirrors$eyeHeightOld();

        captureResolution = res;
        capturing = true;
        try {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            if (cfg.disableOcclusionCullingDuringCapture) mc.smartCull = false;
            mc.setCameraEntity(probeEntity);
            // The camera follows its own entity field, not Minecraft's camera entity, so attach it directly,
            // and drop the player's eye height (the marker's eye is at its position).
            cameraAccess.signmirrors$setEntity(probeEntity);
            cameraAccess.signmirrors$setEyeHeight(0f);
            cameraAccess.signmirrors$setEyeHeightOld(0f);

            for (int i = 0; i < cfg.facesPerFrame; i++) {
                Mirror mirror = active.get(mirrorCursor++ % active.size());
                if (mirror.box == null || mirror.probe == null) continue;
                List<CubeFace> faces = mirror.geometry.neededFaces();
                CubeFace face = faces.get(mirror.faceCursor++ % faces.size());
                // Never-captured faces first, so a new mirror fills in within a frame or three.
                for (CubeFace f : faces) {
                    if ((mirror.capturedFaces & (1 << f.ordinal())) == 0) { face = f; break; }
                }

                currentMirror = mirror;
                accessor.signmirrors$setMainRenderTarget(mirror.probe.face(face));
                poseCamera(camera, deltaTracker, mirror.geometry.probe(), face);

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
                SodiumCompat.beforeExtraLevelRender();
                accessor.signmirrors$renderLevel();

                mirror.capturedFaces |= 1 << face.ordinal();
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
            camera.update(deltaTracker);
        }
    }

    /**
     * Points the camera at the probe. Camera.update derives the pose from the camera entity but
     * also adds an eye-height that it eases toward over a few ticks, so measure where the camera
     * actually landed and correct the entity once.
     */
    private static void poseCamera(Camera camera, DeltaTracker deltaTracker, V3 target, CubeFace face) {
        Vec3 want = new Vec3(target.x(), target.y(), target.z());
        probeEntity.pose(want, face.yaw, face.pitch);
        camera.update(deltaTracker);
        Vec3 error = want.subtract(camera.position());
        if (error.lengthSqr() > 1e-8) {
            probeEntity.pose(want.add(error), face.yaw, face.pitch);
            camera.update(deltaTracker);
        }
        double off = camera.position().distanceTo(want);
        if (off > 0.05 && !warnedPose) {
            warnedPose = true;
            SignMirrorsClient.LOGGER.warn("Mirror camera landed {} blocks from its probe point (camera at {}, wanted {})",
                    String.format("%.2f", off), camera.position(), want);
        }
    }

    /** Applies the exact cube-face projection to the extracted camera state too (used for the mirror pass). */
    private static void squareProjection(CameraRenderState state) {
        state.projectionMatrix = cubeFaceProjection(new Matrix4f(state.projectionMatrix));
    }

    /**
     * Turns whatever perspective projection the game built into an exact 90 x 90 degree one:
     * both focal terms become 1 (tan 45 = 1) and any skew or off-centre terms are cleared, while the
     * depth terms are kept, so the backend's depth range, reverse-Z and handedness stay intact.
     */
    public static Matrix4f cubeFaceProjection(Matrix4f in) {
        if (!loggedProjection) {
            loggedProjection = true;
            // m11 = 1/tan(vfov/2), m00 = m11/aspect: tells us what FOV/aspect vanilla built for the probe.
            SignMirrorsClient.LOGGER.info("Mirror capture projection before correction: m00={} m11={} (exact 90x90 is 1, 1)",
                    in.m00(), in.m11());
        }
        Matrix4f p = new Matrix4f(in);
        p.m00(Math.copySign(1f, in.m00()));
        p.m11(Math.copySign(1f, in.m11()));
        p.m01(0f);
        p.m10(0f);
        p.m20(0f);
        p.m21(0f);
        return p;
    }
}
