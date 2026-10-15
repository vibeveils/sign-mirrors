package dev.signmirrors.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets a capture run the level extraction itself if GameRenderer.extract skipped it. */
@Mixin(LevelExtractor.class)
public interface LevelExtractorAccessor {
    @Invoker("extract")
    void signmirrors$extract(DeltaTracker deltaTracker, Camera camera, float partialTick);
}
