package dev.signmirrors.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;

/**
 * GPU side of one mirror: a 3x2 atlas holding the six cube-map faces (see CubeFace for layout)
 * and a small uniform buffer with the per-mirror parameters for the mirror shader.
 */
public final class MirrorProbe implements AutoCloseable {
    /** std140 block MirrorInfo: five vec4s. */
    public static final int UNIFORM_SIZE = 5 * 16;

    public final int resolution;
    public final GpuTexture atlas;
    public final GpuTextureView atlasView;
    public final GpuBuffer uniforms;

    public MirrorProbe(int resolution) {
        this.resolution = resolution;
        GpuDevice device = RenderSystem.getDevice();
        this.atlas = device.createTexture(() -> "signmirrors probe atlas",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT,
                TextureFormat.RGBA8, resolution * 3, resolution * 2, 1, 1);
        this.atlasView = device.createTextureView(atlas);
        this.uniforms = device.createBuffer(() -> "signmirrors MirrorInfo",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UNIFORM_SIZE);
        // Neutral grey until the first faces are captured.
        device.createCommandEncoder().clearColorTexture(atlas, 0xFF6E7378);
    }

    @Override
    public void close() {
        atlasView.close();
        atlas.close();
        uniforms.close();
    }
}
