package dev.signmirrors.world;

import dev.signmirrors.core.MirrorGeometry;
import dev.signmirrors.core.MirrorSettings;
import dev.signmirrors.core.V3;
import dev.signmirrors.render.MirrorCapture;
import dev.signmirrors.render.MirrorProbe;
import net.minecraft.core.BlockPos;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** A live mirror: settings and geometry from its sign, its GPU probe, and this frame's visibility/LOD state. */
public final class Mirror {
    public final BlockPos pos;
    /** The sign text (and block state) this was built from; a change rebuilds the shape but keeps the probe. */
    public String key;
    public MirrorSettings settings;
    public MirrorGeometry geometry;
    /** Frame pieces in world space (empty without a frame). */
    public List<MirrorGeometry.Box> frameBoxes;
    /** The sign's wood type ("oak", "crimson", ...), for the sign-wood frame. */
    public String woodType;
    /** The block the sign hangs on or stands on, for the block frame. */
    public BlockPos supportPos;

    /** The probe drawn on screen. Null until the mirror has first been visible. */
    public MirrorProbe probe;
    /** A probe at a new resolution being filled in; replaces {@link #probe} once it has been rendered. */
    public MirrorProbe pendingProbe;
    /**
     * This mirror as seen inside another mirror's reflection, keyed by that other mirror: rendered
     * from the other mirror's virtual eye just before the other mirror's own reflection is, so
     * mirrors in mirrors stay live. Freed when unused (MirrorCapture.expireNested).
     */
    public final Map<Mirror, MirrorProbe> nested = new IdentityHashMap<>();

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
    /** The part of the mirror inside the player's view (slightly widened), in world space. */
    public V3[] visibleRegion;
    /** Largest on-screen extent of that region, in pixels. */
    public double screenExtentPx;
    /** Resolution the probe is moving to, and how many frames the LOD has asked for it. */
    public int targetResolution;
    public int targetStableFrames;

    Mirror(BlockPos pos) {
        this.pos = pos;
    }

    void update(String key, MirrorSettings settings, MirrorGeometry geometry, String woodType, BlockPos supportPos) {
        this.supportPos = supportPos;
        this.key = key;
        this.settings = settings;
        this.geometry = geometry;
        this.woodType = woodType;
        this.frameBoxes = settings.frame == MirrorSettings.Frame.NONE ? List.of()
                : geometry.frameBoxes(settings.frameWidth / 16.0);
        this.visibleRegion = null;
    }

    /** Whether anything of the glass is drawn at all. */
    public boolean drawsGlass() {
        return settings.opacity > 0;
    }

    /** Whether the glass shows a reflection (and so needs captures). */
    public boolean reflects() {
        return settings.opacity > 0 && settings.reflectivity > 0 && MirrorCapture.reflectionsAvailable();
    }

    /** True once there is something to draw. */
    public boolean ready() {
        if (!drawsGlass()) return false;
        return !reflects() || (probe != null && probe.isCaptured());
    }

    /**
     * The texture to draw this mirror with when it's seen in {@code viewer}'s reflection (null for
     * the player's own view): its nested texture for that viewer if it has one, else its own.
     */
    public MirrorProbe textureFor(Mirror viewer) {
        if (viewer != null) {
            MirrorProbe p = nested.get(viewer);
            if (p != null && p.isCaptured()) return p;
        }
        return probe != null && probe.isCaptured() ? probe : null;
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
        for (MirrorProbe p : nested.values()) p.release();
        nested.clear();
    }
}
