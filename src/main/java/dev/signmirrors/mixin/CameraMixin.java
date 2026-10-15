package dev.signmirrors.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.signmirrors.render.MirrorCapture;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Camera.class)
public abstract class CameraMixin {
    /** Cube faces need exactly 90 degrees, regardless of the FOV setting, sprinting or effects. */
    @WrapOperation(method = "update",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;calculateFov(F)F"))
    private float signmirrors$cubeFaceFov(Camera instance, float partialTicks, Operation<Float> original) {
        if (MirrorCapture.isCapturing()) return 90.0f;
        return original.call(instance, partialTicks);
    }
}
