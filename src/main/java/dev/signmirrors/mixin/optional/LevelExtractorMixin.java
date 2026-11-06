package dev.signmirrors.mixin.optional;

import dev.signmirrors.render.MirrorCapture;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cosmetic: no block-selection outline inside reflections. Non-required config. */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @Inject(method = "extractBlockOutline", at = @At("HEAD"), cancellable = true, require = 0)
    private void signmirrors$noOutlineInReflections(CallbackInfo ci) {
        if (MirrorCapture.isCapturing()) ci.cancel();
    }
}
