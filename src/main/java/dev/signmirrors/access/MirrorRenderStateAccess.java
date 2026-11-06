package dev.signmirrors.access;

import dev.signmirrors.world.Mirror;

/** Added to SignRenderState by mixin: the mirror (if any) this sign is, captured at extraction. */
public interface MirrorRenderStateAccess {
    Mirror signmirrors$mirror();

    void signmirrors$setMirror(Mirror mirror);
}
