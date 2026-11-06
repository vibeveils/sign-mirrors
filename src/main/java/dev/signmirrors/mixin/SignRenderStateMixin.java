package dev.signmirrors.mixin;

import dev.signmirrors.access.MirrorRenderStateAccess;
import dev.signmirrors.world.Mirror;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SignRenderState.class)
public class SignRenderStateMixin implements MirrorRenderStateAccess {
    @Unique
    private Mirror signmirrors$mirror;

    @Override
    public Mirror signmirrors$mirror() {
        return signmirrors$mirror;
    }

    @Override
    public void signmirrors$setMirror(Mirror mirror) {
        signmirrors$mirror = mirror;
    }
}
