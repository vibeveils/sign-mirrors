package dev.signmirrors.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    /** Swapped to the capture target for the duration of each probe render. */
    @Mutable
    @Accessor("mainRenderTarget")
    void signmirrors$setMainRenderTarget(RenderTarget target);
}
