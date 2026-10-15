package dev.signmirrors.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.signmirrors.render.MirrorCapture;
import net.minecraft.client.Camera;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {
    /** Captures need an exact FOV (90 for cube faces, mirror-sized for planar), regardless of settings or effects. */
    @WrapOperation(method = "update",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;calculateFov(F)F"))
    private float signmirrors$cubeFaceFov(Camera instance, float partialTicks, Operation<Float> original) {
        if (MirrorCapture.isCapturing()) return MirrorCapture.captureFovDegrees();
        return original.call(instance, partialTicks);
    }

    /** The mirror camera may sit inside water/lava/blocks (behind the wall); no submerged fog in reflections. */
    @Inject(method = "getFluidInCamera", at = @At("HEAD"), cancellable = true)
    private void signmirrors$noFluidFogInReflections(CallbackInfoReturnable<FogType> cir) {
        if (MirrorCapture.isCapturing()) cir.setReturnValue(FogType.NONE);
    }
}
