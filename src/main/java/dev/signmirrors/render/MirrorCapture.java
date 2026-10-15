package dev.signmirrors.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.signmirrors.SignMirrorsClient;
import dev.signmirrors.config.MirrorConfig;
import dev.signmirrors.core.CubeFace;
import dev.signmirrors.core.V3;
import dev.signmirrors.mixin.MinecraftAccessor;
import dev.signmirrors.world.Mirror;
import dev.signmirrors.world.MirrorTracker;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The secondary camera. Before each main world render it re-runs the vanilla world render a few
 * times (config.facesPerFrame) from a mirror's probe position, one cube face per pass, into a small
 * square target, then copies the result into that mirror's atlas.
 *
 * While {@link #isCapturing()} is true, mixins make vanilla cooperate: the main camera is posed at
 * the probe (detached, so the player is visible), FOV is 90 degrees, the window reports a square
 * size, view bobbing, the hand and the block outline are suppressed, and the chunk occlusion graph
 * is left as computed from the player.
 */
public final class MirrorCapture {
    private static boolean capturing;
    private static Mirror currentMirror;
    private static CubeFace currentFace;
    private static int captureResolution = 256;
    private static boolean frustumReapplyPending;
    private static int mirrorCursor;

    private static TextureTarget target;

    private MirrorCapture() {}

    public static boolean isCapturing() { return capturing; }
    public static int resolution() { return captureResolution; }
    public static Mirror currentMirror() { return currentMirror; }

    /** Pose for the camera mixin while capturing. */
    public static Vec3 probePosition() {
        V3 p = currentMirror.geometry.probe();
        return new Vec3(p.x(), p.y(), p.z());
    }
    public static float probeYaw() { return currentFace.yaw; }
    public static float probePitch() { return currentFace.pitch; }

    /** Used by the occlusion-graph mixin: re-cull sections for the probe frustum, and once more for the player afterwards. */
    public static boolean forceFrustumUpdate() {
        if (capturing) return true;
        if (frustumReapplyPending) {
            frustumReapplyPending = false;
            return true;
        }
        return false;
    }

    /** Called at the head of the main GameRenderer.renderLevel. */
    public static void runCaptures(GameRenderer gameRenderer) {
        MirrorConfig cfg = SignMirrorsClient.config;
        Minecraft mc = Minecraft.getInstance();
        if (!cfg.enabled || cfg.facesPerFrame <= 0 || mc.level == null || mc.player == null) return;

        Camera camera = gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        Vec3 look = Vec3.directionFromRotation(camera.getXRot(), camera.getYRot());
        List<Mirror> active = MirrorTracker.INSTANCE.active(camPos, look, cfg);
        if (active.isEmpty()) return;

        int res = cfg.probeResolution;
        ensureTarget(res);
        for (Mirror m : active) {
            if (m.probe != null && m.probe.resolution != res) m.probe.close();
            if (m.probe == null || m.probe.resolution != res) {
                m.probe = new MirrorProbe(res);
                m.capturedFaces = 0;
            }
        }

        MirrorCameraAccess cameraAccess = (MirrorCameraAccess) camera;
        Vec3 savedPos = camera.getPosition();
        float savedYaw = camera.getYRot(), savedPitch = camera.getXRot();
        boolean savedDetached = camera.isDetached();

        RenderTarget mainTarget = mc.getMainRenderTarget();
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        captureResolution = res;
        capturing = true;
        try {
            ((MinecraftAccessor) mc).signmirrors$setMainRenderTarget(target);
            for (int i = 0; i < cfg.facesPerFrame; i++) {
                Mirror mirror = active.get(mirrorCursor++ % active.size());
                if (mirror.box == null || mirror.geometry.neededFaces().isEmpty()) continue;
                List<CubeFace> faces = mirror.geometry.neededFaces();
                CubeFace face = faces.get(mirror.faceCursor++ % faces.size());
                // Never-captured faces first, so a new mirror fills in within a frame or three.
                for (CubeFace f : faces) {
                    if ((mirror.capturedFaces & (1 << f.ordinal())) == 0) { face = f; break; }
                }

                currentMirror = mirror;
                currentFace = face;
                encoder.clearColorAndDepthTextures(target.getColorTexture(), 0, target.getDepthTexture(), 1.0);
                // In case Camera.setup is not called inside renderLevel on this version, pose it up front too.
                cameraAccess.signmirrors$setPose(probePosition(), face.yaw, face.pitch, true);

                gameRenderer.renderLevel(mc.getDeltaTracker());

                encoder.copyTextureToTexture(target.getColorTexture(), mirror.probe.atlas, 0,
                        face.atlasCol() * res, face.atlasRow() * res, 0, 0, res, res);
                mirror.capturedFaces |= 1 << face.ordinal();
            }
        } catch (Throwable t) {
            SignMirrorsClient.LOGGER.error("Mirror capture failed; disabling mirrors for this session", t);
            cfg.enabled = false;
        } finally {
            ((MinecraftAccessor) mc).signmirrors$setMainRenderTarget(mainTarget);
            capturing = false;
            currentMirror = null;
            currentFace = null;
            frustumReapplyPending = true;
            cameraAccess.signmirrors$setPose(savedPos, savedYaw, savedPitch, savedDetached);
        }
    }

    private static void ensureTarget(int res) {
        if (target != null && target.width == res && target.height == res) return;
        if (target != null) target.destroyBuffers();
        target = new TextureTarget("signmirrors capture", res, res, true);
    }
}
