package dev.signmirrors.mixin;

import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 26.3 has no public getter for a sign's text; the front side lives in the private field {@code frontText}. */
@Mixin(SignBlockEntity.class)
public interface SignBlockEntityAccessor {
    @Accessor("frontText")
    SignText signmirrors$frontText();
}
