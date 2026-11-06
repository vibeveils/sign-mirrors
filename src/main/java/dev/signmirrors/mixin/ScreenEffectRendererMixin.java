package dev.signmirrors.mixin;

import dev.signmirrors.render.MirrorCapture;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No "inside a block" / fire / underwater overlays inside reflections. A planar mirror's camera sits
 * at your eye reflected behind the wall -- usually inside solid blocks -- so this overlay would
 * otherwise cover the whole reflection.
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
    @Inject(method = "submit", at = @At("HEAD"), cancellable = true, require = 0)
    private void signmirrors$noOverlaysInReflections(CallbackInfo ci) {
        if (MirrorCapture.isCapturing()) ci.cancel();
    }
}
