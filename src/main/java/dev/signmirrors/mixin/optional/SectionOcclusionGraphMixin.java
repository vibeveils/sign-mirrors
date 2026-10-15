package dev.signmirrors.mixin.optional;

import dev.signmirrors.SignMirrorsClient;
import dev.signmirrors.render.MirrorCapture;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Optional (separate, non-required mixin config). Without it the occlusion graph would be rebuilt
 * from each probe and then again from the player every frame, which costs CPU and can make chunks
 * flicker. Instead the graph from the player's position is reused and only frustum culling is
 * redone for each probe view, then once more for the player.
 */
@Mixin(SectionOcclusionGraph.class)
public abstract class SectionOcclusionGraphMixin {
    @Inject(method = "update", at = @At("HEAD"), cancellable = true, require = 0)
    private void signmirrors$keepPlayerGraph(CallbackInfo ci) {
        if (MirrorCapture.isCapturing() && SignMirrorsClient.config.freezeOcclusionDuringCapture) ci.cancel();
    }

    @Inject(method = "consumeFrustumUpdate", at = @At("HEAD"), cancellable = true, require = 0)
    private void signmirrors$recull(CallbackInfoReturnable<Boolean> cir) {
        if (MirrorCapture.forceFrustumUpdate()) cir.setReturnValue(true);
    }
}
