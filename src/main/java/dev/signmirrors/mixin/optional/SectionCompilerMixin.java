package dev.signmirrors.mixin.optional;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.signmirrors.world.MirrorTracker;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Leaves the board of a mirror sign set to "hide sign" out of the chunk mesh (vanilla renderer).
 * Runs on chunk-meshing threads, so it only consults MirrorTracker's thread-safe hidden set.
 */
@Mixin(SectionCompiler.class)
abstract class SectionCompilerMixin {
    @WrapOperation(method = "compile", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V"))
    private void signmirrors$hideMirrorSigns(ModelBlockRenderer instance, BlockQuadOutput output, float x, float y, float z,
                                             BlockAndTintGetter level, BlockPos pos, BlockState state, BlockStateModel model,
                                             long seed, Operation<Void> original) {
        if (state.getBlock() instanceof SignBlock && MirrorTracker.INSTANCE.isSignHidden(pos.asLong())) return;
        original.call(instance, output, x, y, z, level, pos, state, model, seed);
    }
}
