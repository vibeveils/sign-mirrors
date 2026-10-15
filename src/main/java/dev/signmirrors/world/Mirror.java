package dev.signmirrors.world;

import dev.signmirrors.core.MirrorGeometry;
import dev.signmirrors.core.MirrorSpec;
import dev.signmirrors.core.ProxyBox;
import dev.signmirrors.render.MirrorProbe;
import net.minecraft.core.BlockPos;

/** A live mirror: geometry from its sign, plus (once it has been near the player) a GPU probe. */
public final class Mirror {
    public final BlockPos pos;
    public final String key;
    public final MirrorSpec spec;
    public final MirrorGeometry geometry;

    public ProxyBox box;
    public int boxAgeTicks;
    /** Lazily created when the mirror becomes active; freed when it is removed or far away. */
    public MirrorProbe probe;
    public int faceCursor;
    /** Bit set of cube faces captured at least once; the mirror is drawn once any face is ready. */
    public int capturedFaces;

    Mirror(BlockPos pos, String key, MirrorSpec spec, MirrorGeometry geometry) {
        this.pos = pos;
        this.key = key;
        this.spec = spec;
        this.geometry = geometry;
    }

    public boolean ready() {
        return probe != null && box != null && capturedFaces != 0;
    }

    void releaseProbe() {
        if (probe != null) {
            probe.close();
            probe = null;
        }
        capturedFaces = 0;
    }
}
