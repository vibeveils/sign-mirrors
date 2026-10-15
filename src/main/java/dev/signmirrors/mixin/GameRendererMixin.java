package dev.signmirrors.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.signmirrors.render.MirrorCapture;
import dev.signmirrors.render.MirrorDrawer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    /** Secondary camera: render the due cube faces before the player's own extraction. */
    @Inject(method = "extract", at = @At("HEAD"))
    private void signmirrors$captureProbes(CallbackInfo ci) {
        if (!MirrorCapture.isCapturing()) MirrorCapture.runCaptures((GameRenderer) (Object) this);
    }

    /**
     * Every level render uploads its projection here. During a mirror capture, force an exact
     * 90 x 90 degree projection (whatever FOV/aspect the game computed), and remember the projection
     * actually used so mirrors drawn in this view line up exactly.
     */
    @WrapOperation(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"),
            require = 1)
    private GpuBufferSlice signmirrors$recordProjection(ProjectionMatrixBuffer instance, Matrix4f projection,
                                                       Operation<GpuBufferSlice> original) {
        Matrix4f used = MirrorCapture.isCapturing() ? MirrorCapture.cubeFaceProjection(projection) : projection;
        MirrorDrawer.recordProjection(used);
        return original.call(instance, used);
    }

    @WrapOperation(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V"),
            require = 0)
    private void signmirrors$noBobbing(GameRenderer instance, CameraRenderState cameraState, PoseStack poseStack,
                                       Operation<Void> original) {
        if (!MirrorCapture.isCapturing()) original.call(instance, cameraState, poseStack);
    }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true, require = 0)
    private void signmirrors$noHurtTilt(CallbackInfo ci) {
        if (MirrorCapture.isCapturing()) ci.cancel();
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true, require = 0)
    private void signmirrors$noHand(CallbackInfo ci) {
        if (MirrorCapture.isCapturing()) ci.cancel();
    }
}
