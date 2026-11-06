package dev.signmirrors.mixin.optional;

import dev.signmirrors.render.IrisCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While a mirror capture renders, Iris gets a separate pipeline (see IrisCompat). Pseudo with a
 * string target: no compile-time dependency on Iris, and skipped when Iris isn't installed.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.PipelineManager", remap = false)
abstract class IrisPipelineManagerMixin {
    @Inject(method = "preparePipeline", at = @At("HEAD"), cancellable = true, remap = false)
    private void signmirrors$capturePipeline(@Coerce Object dimension, CallbackInfoReturnable<Object> cir) {
        Object pipeline = IrisCompat.capturePipelineFor(this, dimension);
        if (pipeline != null) cir.setReturnValue(pipeline);
    }

    @Inject(method = "destroyPipeline", at = @At("HEAD"), remap = false)
    private void signmirrors$destroy(CallbackInfo ci) {
        IrisCompat.destroyCapturePipelines();
    }
}
