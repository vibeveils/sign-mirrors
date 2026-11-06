package dev.signmirrors.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    /** Swapped to a cube face's target for the duration of each probe render. */
    @Mutable
    @Accessor("mainRenderTarget")
    void signmirrors$setMainRenderTarget(RenderTarget target);

    @Accessor("gameRenderState")
    GameRenderState signmirrors$gameRenderState();

    @Invoker("renderLevel")
    void signmirrors$renderLevel();
}
