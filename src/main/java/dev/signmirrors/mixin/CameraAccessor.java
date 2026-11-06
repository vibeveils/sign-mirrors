package dev.signmirrors.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Since 26.1 the camera keeps its own entity (set via setEntity) and eases its eye height toward
 * that entity's over a few ticks; Camera.update follows those, not Minecraft.getCameraEntity().
 * Names as used by Freecam's 26.x CameraMixin.
 */
@Mixin(Camera.class)
public interface CameraAccessor {
    @Invoker("setEntity")
    void signmirrors$setEntity(Entity entity);

    @Accessor("entity")
    Entity signmirrors$entity();

    @Accessor("eyeHeight")
    float signmirrors$eyeHeight();

    @Accessor("eyeHeight")
    void signmirrors$setEyeHeight(float eyeHeight);

    @Accessor("eyeHeightOld")
    float signmirrors$eyeHeightOld();

    @Accessor("eyeHeightOld")
    void signmirrors$setEyeHeightOld(float eyeHeightOld);
}
