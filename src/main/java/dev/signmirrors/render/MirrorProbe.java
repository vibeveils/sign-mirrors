package dev.signmirrors.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.GpuFormat;
import dev.signmirrors.core.CubeFace;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GPU side of one mirror at one resolution: one square render target per cube face it needs (at
 * most five). Each capture renders the world straight into the face's target, and the mirror shader
 * samples the face textures directly. Also tracks which faces have been captured and when.
 *
 * Targets are pooled (keyed by size and formats) and handed back on release rather than destroyed,
 * so mirrors changing resolution or going in and out of range don't churn GPU allocations.
 */
public final class MirrorProbe {
    private record Key(int resolution, GpuFormat color, GpuFormat depth) {}

    private static final Map<Key, Deque<RenderTarget>> POOL = new HashMap<>();

    public final int resolution;
    private final Key key;
    private final RenderTarget[] faces = new RenderTarget[CubeFace.values().length];
    private final long[] capturedFrame = new long[CubeFace.values().length];
    private int capturedMask;

    public MirrorProbe(int resolution, GpuFormat colorFormat, GpuFormat depthFormat, List<CubeFace> neededFaces) {
        this.resolution = resolution;
        this.key = new Key(resolution, colorFormat, depthFormat);
        Deque<RenderTarget> pool = POOL.computeIfAbsent(key, k -> new ArrayDeque<>());
        for (CubeFace face : neededFaces) {
            RenderTarget target = pool.pollFirst();
            if (target == null) {
                target = new TextureTarget("signmirrors probe " + resolution, resolution, resolution, colorFormat, depthFormat);
            }
            faces[face.ordinal()] = target;
        }
    }

    public boolean matches(int resolution, GpuFormat colorFormat, GpuFormat depthFormat) {
        return key.equals(new Key(resolution, colorFormat, depthFormat));
    }

    public boolean sameFormats(MirrorProbe other) {
        return key.color().equals(other.key.color()) && key.depth().equals(other.key.depth());
    }

    /** Null for faces this mirror never needs. */
    public RenderTarget face(CubeFace face) {
        return faces[face.ordinal()];
    }

    /** Any allocated face; bound in place of unneeded faces (the shader never samples those). */
    public RenderTarget anyFace() {
        for (RenderTarget t : faces) if (t != null) return t;
        throw new IllegalStateException("mirror probe has no faces");
    }

    public void markCaptured(CubeFace face, long frame) {
        capturedMask |= 1 << face.ordinal();
        capturedFrame[face.ordinal()] = frame;
    }

    public boolean isCaptured(CubeFace face) {
        return (capturedMask & (1 << face.ordinal())) != 0;
    }

    public long capturedFrame(CubeFace face) {
        return capturedFrame[face.ordinal()];
    }

    public int capturedMask() {
        return capturedMask;
    }

    public void release() {
        Deque<RenderTarget> pool = POOL.computeIfAbsent(key, k -> new ArrayDeque<>());
        for (int i = 0; i < faces.length; i++) {
            if (faces[i] != null) pool.addFirst(faces[i]);
            faces[i] = null;
        }
        capturedMask = 0;
    }
}
