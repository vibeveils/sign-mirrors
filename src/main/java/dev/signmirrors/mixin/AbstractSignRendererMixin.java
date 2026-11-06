package dev.signmirrors.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.signmirrors.SignMirrorsClient;
import dev.signmirrors.access.MirrorRenderStateAccess;
import dev.signmirrors.render.FrameRenderer;
import dev.signmirrors.world.Mirror;
import dev.signmirrors.world.MirrorTracker;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirror signs draw their frame from the sign's block entity renderer (so it's lit, sorted and shown
 * in reflections like any other block entity), and skip the sign's text when the sign is hidden.
 * The sign board itself is part of the chunk mesh; see SectionCompilerMixin.
 */
@Mixin(AbstractSignRenderer.class)
public abstract class AbstractSignRendererMixin {
    @Inject(at = @At("TAIL"), method = "extractRenderState(Lnet/minecraft/world/level/block/entity/SignBlockEntity;Lnet/minecraft/client/renderer/blockentity/state/SignRenderState;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V")
    private void signmirrors$extract(SignBlockEntity sign, SignRenderState state, float partialTick, Vec3 cameraPos,
                                     ModelFeatureRenderer.CrumblingOverlay crumbling, CallbackInfo ci) {
        Mirror mirror = SignMirrorsClient.config.enabled ? MirrorTracker.INSTANCE.at(sign.getBlockPos()) : null;
        ((MirrorRenderStateAccess) state).signmirrors$setMirror(mirror);
    }

    @WrapMethod(method = "submit(Lnet/minecraft/client/renderer/blockentity/state/SignRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V")
    private void signmirrors$submit(SignRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                                    CameraRenderState camera, Operation<Void> original) {
        Mirror mirror = ((MirrorRenderStateAccess) state).signmirrors$mirror();
        if (mirror != null) FrameRenderer.submit(mirror, state, poseStack, collector);
        if (mirror != null && mirror.settings.hideSign) return;
        original.call(state, poseStack, collector, camera);
    }
}
