package dev.signmirrors.mixin;

import dev.signmirrors.render.MirrorCameraAccess;
import dev.signmirrors.render.MirrorCapture;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin implements MirrorCameraAccess {
    @Shadow private boolean detached;

    @Shadow protected abstract void setPosition(Vec3 pos);

    @Shadow protected abstract void setRotation(float yRot, float xRot);

    /** While capturing, whatever vanilla sets up gets overridden with the probe pose. */
    @Inject(method = "setup", at = @At("TAIL"))
    private void signmirrors$probePose(CallbackInfo ci) {
        if (MirrorCapture.isCapturing() && MirrorCapture.currentMirror() != null) {
            signmirrors$setPose(MirrorCapture.probePosition(), MirrorCapture.probeYaw(), MirrorCapture.probePitch(), true);
        }
    }

    @Override
    public void signmirrors$setPose(Vec3 position, float yaw, float pitch, boolean detached) {
        this.setRotation(yaw, pitch);
        this.setPosition(position);
        // Detached so the player model is rendered: you can see yourself in the mirror.
        this.detached = detached;
    }
}
