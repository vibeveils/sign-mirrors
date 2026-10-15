package dev.signmirrors.mixin;

import dev.signmirrors.render.MirrorCapture;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No clouds inside reflections. The cloud renderer keeps its mesh in a per-frame ring buffer and
 * assumes one level render per frame; a mirror capture (a second render from another position)
 * makes it rebuild into the slot the main render then needs, which renderpearl rejects
 * ("Cannot wait on a fence for the current submit"). Same hook Iris uses to override clouds.
 */
@Mixin(Options.class)
public abstract class OptionsMixin {
    @Inject(method = "getCloudStatus", at = @At("HEAD"), cancellable = true)
    private void signmirrors$noCloudsInCaptures(CallbackInfoReturnable<CloudStatus> cir) {
        if (MirrorCapture.isCapturing()) cir.setReturnValue(CloudStatus.OFF);
    }
}
