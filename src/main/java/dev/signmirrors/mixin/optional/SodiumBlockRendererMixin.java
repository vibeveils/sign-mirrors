package dev.signmirrors.mixin.optional;

import dev.signmirrors.world.MirrorTracker;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium builds chunk meshes itself; this does the same as SectionCompilerMixin there. Pseudo with a
 * string target, so there is no compile-time dependency on Sodium and it is skipped without it.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer")
abstract class SodiumBlockRendererMixin {
    @Inject(method = "renderModel", at = @At("HEAD"), cancellable = true, remap = false)
    private void signmirrors$hideMirrorSigns(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin,
                                             CallbackInfo ci) {
        if (state.getBlock() instanceof SignBlock && MirrorTracker.INSTANCE.isSignHidden(pos.asLong())) ci.cancel();
    }
}
