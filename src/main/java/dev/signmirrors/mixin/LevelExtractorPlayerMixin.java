package dev.signmirrors.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.signmirrors.render.MirrorCapture;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla only draws the local player when the camera's entity is that player. While a mirror
 * camera renders, answer that particular check with the player so you appear in the reflection
 * (same injection point as Tweakeroo's 26.3 free camera).
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorPlayerMixin {
    @WrapOperation(method = "extractVisibleEntities",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;entity()Lnet/minecraft/world/entity/Entity;", ordinal = 3))
    private Entity signmirrors$showPlayerInMirror(Camera instance, Operation<Entity> original) {
        if (MirrorCapture.isCapturing() && Minecraft.getInstance().player != null) return Minecraft.getInstance().player;
        return original.call(instance);
    }
}
