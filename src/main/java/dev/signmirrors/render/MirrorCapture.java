package dev.signmirrors.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.GpuFormat;
import dev.signmirrors.SignMirrorsClient;
import dev.signmirrors.config.MirrorConfig;
import dev.signmirrors.core.CubeFace;
import dev.signmirrors.core.V3;
import dev.signmirrors.mixin.GameRendererAccessor;
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

    private MirrorCapture() {}

    public static boolean isCapturing() { return capturing; }
    public static int resolution() { return captureResolution; }
    public static Mirror currentMirror() { return currentMirror; }

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

        captureResolution = res;
        capturing = true;
        try {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            if (cfg.disableOcclusionCullingDuringCapture) mc.smartCull = false;
            mc.setCameraEntity(probeEntity);

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

                gameRenderer.extract(deltaTracker, false);
                squareProjection(accessor.signmirrors$gameRenderState().levelRenderState.cameraRenderState);
                accessor.signmirrors$renderLevel();

                mirror.capturedFaces |= 1 << face.ordinal();
            }
        } catch (Throwable t) {
            SignMirrorsClient.LOGGER.error("Mirror capture failed; disabling mirrors for this session", t);
            cfg.enabled = false;
        } finally {
            accessor.signmirrors$setMainRenderTarget(mainTarget);
            mc.setCameraEntity(savedCameraEntity);
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
    }

    /** Forces a 1:1 aspect ratio while keeping the backend's depth/handedness conventions intact. */
    private static void squareProjection(CameraRenderState state) {
        Matrix4f p = new Matrix4f(state.projectionMatrix);
        p.m00(Math.copySign(Math.abs(p.m11()), p.m00()));
        state.projectionMatrix = p;
    }
}
