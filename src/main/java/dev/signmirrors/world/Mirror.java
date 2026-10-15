package dev.signmirrors.world;

import dev.signmirrors.core.MirrorGeometry;
import dev.signmirrors.core.MirrorSpec;
import dev.signmirrors.core.ProxyBox;
import dev.signmirrors.render.MirrorProbe;
import net.minecraft.core.BlockPos;

/** A live mirror: geometry from its sign, its GPU probe(s), and this frame's visibility/LOD state. */
public final class Mirror {
    public final BlockPos pos;
    public final String key;
    public final MirrorSpec spec;
    public final MirrorGeometry geometry;

    public ProxyBox box;
    public int boxAgeTicks;

    /** The probe drawn on screen. Null until the mirror has first been visible. */
    public MirrorProbe probe;
    /**
     * A probe at a new resolution being filled in. It replaces {@link #probe} once it holds every face
     * the reflection currently shows, so a resolution change never flashes grey.
     */
    public MirrorProbe pendingProbe;

    // ---- Per-frame visibility / level-of-detail state (MirrorVisibility) ----
    /** Passed frustum and occlusion culling this frame (including the grace period). */
    public boolean visible;
    /** Within distance, in front of the mirror and inside the view frustum (occlusion not considered). */
    public boolean inFrustum;
    /** Last frame number in which any occlusion ray reached the mirror. */
    public long lastSeenFrame = Long.MIN_VALUE / 2;
    /** Frame of the next occlusion raycast. */
    public long nextOcclusionCheck;
    /** Approximate fraction of the screen the mirror covers (0..1). */
    public double coverage;
    /** Update priority: bigger, nearer and more central mirrors refresh more often. */
    public double weight;
    /** Cube faces the reflection samples from the current viewpoint (bit per CubeFace). */
    public int visibleFaceMask;
    /** Resolution the probe is moving to, and how many frames the LOD has asked for it. */
    public int targetResolution;
    public int targetStableFrames;

    Mirror(BlockPos pos, String key, MirrorSpec spec, MirrorGeometry geometry) {
        this.pos = pos;
        this.key = key;
        this.spec = spec;
        this.geometry = geometry;
    }

    /** True once there is something to draw. */
    public boolean ready() {
        return probe != null && box != null && probe.capturedMask() != 0;
    }

    /** The probe captures should go into. */
    public MirrorProbe captureTarget() {
        return pendingProbe != null ? pendingProbe : probe;
    }

    public void releaseProbe() {
        if (probe != null) probe.release();
        if (pendingProbe != null) pendingProbe.release();
        probe = null;
        pendingProbe = null;
    }
}
