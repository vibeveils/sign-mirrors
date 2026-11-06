package dev.signmirrors.mixin.optional;

import com.llamalad7.mixinextras.sugar.Local;
import dev.signmirrors.render.MirrorDrawer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * With an Iris shader pack, Iris runs its composite and final passes at this same point (just
 * before LevelRenderer.render pops its matrix). The higher priority makes this callback land after
 * Iris's, so mirrors are drawn over the finished image instead of being overwritten by it.
 * Without a shader pack this does nothing (mirrors are drawn in the world pass as usual).
 */
@Mixin(value = LevelRenderer.class, priority = 1100)
abstract class LevelRendererIrisMixin {
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;"))
    private void signmirrors$drawAfterShaderPack(CallbackInfo ci, @Local(argsOnly = true) CameraRenderState cameraState) {
        MirrorDrawer.drawAfterShaderPack(cameraState);
    }
}
