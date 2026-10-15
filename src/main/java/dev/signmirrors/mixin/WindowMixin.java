package dev.signmirrors.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.signmirrors.render.MirrorCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** During a capture the "window" is the square probe target, so every aspect-ratio calculation is 1:1. */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Inject(method = {"getWidth", "getHeight"}, at = @At("HEAD"), cancellable = true)
    private void signmirrors$squareSize(CallbackInfoReturnable<Integer> cir) {
        if (MirrorCapture.isCapturing()) cir.setReturnValue(MirrorCapture.resolution());
    }
}
