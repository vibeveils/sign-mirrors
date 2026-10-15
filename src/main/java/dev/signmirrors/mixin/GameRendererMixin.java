package dev.signmirrors.mixin;

import dev.signmirrors.render.MirrorCapture;
import dev.signmirrors.render.MirrorDrawer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    /** Secondary camera: render the due cube faces before the player's own world render. */
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void signmirrors$captureProbes(CallbackInfo ci) {
        if (!MirrorCapture.isCapturing()) MirrorCapture.runCaptures((GameRenderer) (Object) this);
    }

    /** Draw mirrors after the world (so they depth-test against it) but before the hand. */
    @Inject(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel",
                    shift = At.Shift.AFTER))
    private void signmirrors$drawMirrors(CallbackInfo ci) {
        MirrorDrawer.drawAll();
    }

    @Inject(method = "getFov", at = @At("HEAD"), cancellable = true)
    private void signmirrors$squareFov(CallbackInfoReturnable<Float> cir) {
        if (MirrorCapture.isCapturing()) cir.setReturnValue(90.0f);
    }

    @Inject(method = {"bobView", "bobHurt"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void signmirrors$noBobbing(CallbackInfo ci) {
        if (MirrorCapture.isCapturing()) ci.cancel();
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true, require = 0)
    private void signmirrors$noHand(CallbackInfo ci) {
        if (MirrorCapture.isCapturing()) ci.cancel();
    }

    @Inject(method = "shouldRenderBlockOutline", at = @At("HEAD"), cancellable = true, require = 0)
    private void signmirrors$noOutline(CallbackInfoReturnable<Boolean> cir) {
        if (MirrorCapture.isCapturing()) cir.setReturnValue(false);
    }
}
