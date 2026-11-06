package dev.signmirrors.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.GpuFormat;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * GPU side of one mirror at one resolution: a square render target holding the world as seen from
 * the player's eye reflected through the mirror, plus the matrices it was rendered with (the mirror
 * shader projects each pixel into it).
 *
 * Targets are pooled (keyed by size and formats) and handed back on release rather than destroyed,
 * so mirrors changing resolution or going in and out of range don't churn GPU allocations.
 */
public final class MirrorProbe {
    private record Key(int resolution, GpuFormat color, GpuFormat depth) {}

    private static final Map<Key, Deque<RenderTarget>> POOL = new HashMap<>();

    public final int resolution;
    private final Key key;
    private RenderTarget target;
    private boolean captured;
    private long capturedFrame;

    /** Projection x view of the capture, for positions relative to {@link #origin}. */
    public Matrix4f viewProj;
    /** The virtual camera position the capture was rendered from. */
    public Vec3 origin;
    /** The player's eye position the capture was made for. */
    public Vec3 eye;
    /** The player's look direction the capture was made for (the framed region follows it). */
    public Vec3 look;

    public MirrorProbe(int resolution, GpuFormat colorFormat, GpuFormat depthFormat) {
        this.resolution = resolution;
        this.key = new Key(resolution, colorFormat, depthFormat);
        RenderTarget t = POOL.computeIfAbsent(key, k -> new ArrayDeque<>()).pollFirst();
        this.target = t != null ? t
                : new TextureTarget("signmirrors probe " + resolution, resolution, resolution, colorFormat, depthFormat);
    }

    public boolean matches(GpuFormat colorFormat, GpuFormat depthFormat) {
        return key.color().equals(colorFormat) && key.depth().equals(depthFormat);
    }

    public RenderTarget target() {
        return target;
    }

    public void markCaptured(long frame) {
        captured = true;
        capturedFrame = frame;
    }

    public boolean isCaptured() {
        return captured && target != null && viewProj != null;
    }

    public long capturedFrame() {
        return capturedFrame;
    }

    public void release() {
        if (target != null) POOL.computeIfAbsent(key, k -> new ArrayDeque<>()).addFirst(target);
        target = null;
        captured = false;
    }

    // ---- A tiny never-rendered texture bound for mirrors with no reflection (reflectivity 0). ----
    private static RenderTarget placeholder;
    private static Key placeholderKey;

    static RenderTarget placeholder(GpuFormat colorFormat, GpuFormat depthFormat) {
        Key k = new Key(16, colorFormat, depthFormat);
        if (placeholder == null || !k.equals(placeholderKey)) {
            placeholder = new TextureTarget("signmirrors placeholder", 16, 16, colorFormat, depthFormat);
            placeholderKey = k;
        }
        return placeholder;
    }
}
