package dev.signmirrors.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.signmirrors.SignMirrorsClient;
import dev.signmirrors.config.MirrorConfig;
import dev.signmirrors.core.V3;
import dev.signmirrors.world.Mirror;
import dev.signmirrors.world.MirrorTracker;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Draws every ready mirror as a quad whose fragment shader reflects the view ray, intersects it
 * with the mirror's proxy box (parallax correction) and samples the cube-map atlas in the corrected
 * direction. Runs right after the world render (before the hand), with depth testing against the
 * world, both for the main view and — for mirror-in-mirror — inside captures.
 */
public final class MirrorDrawer {
    public static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
            .withLocation(id("pipeline/mirror"))
            .withVertexShader(id("core/mirror"))
            .withFragmentShader(id("core/mirror"))
            .withSampler("Sampler0")
            .withUniform("MirrorInfo", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS)
            .withCull(true)
            .withDepthWrite(true)
            .build();

    private MirrorDrawer() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(SignMirrorsClient.MOD_ID, path);
    }

    /** Called right after LevelRenderer.renderLevel inside GameRenderer.renderLevel. */
    public static void drawAll() {
        MirrorConfig cfg = SignMirrorsClient.config;
        if (!cfg.enabled) return;
        boolean capturing = MirrorCapture.isCapturing();
        if (capturing && !cfg.recursiveMirrors) return;

        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.getPosition();
        Mirror skip = capturing ? MirrorCapture.currentMirror() : null;

        List<Mirror> toDraw = new ArrayList<>();
        for (Mirror m : MirrorTracker.INSTANCE.all()) {
            if (m == skip || !m.ready()) continue;
            V3 c = m.geometry.center();
            double dx = cam.x - c.x(), dy = cam.y - c.y(), dz = cam.z - c.z();
            if (dx * dx + dy * dy + dz * dz > cfg.maxMirrorDistance * cfg.maxMirrorDistance) continue;
            // Back faces are culled anyway; skipping them here saves the uniform upload.
            if (dx * m.geometry.normal().x() + dy * m.geometry.normal().y() + dz * m.geometry.normal().z() <= 0) continue;
            toDraw.add(m);
        }
        if (toDraw.isEmpty()) return;

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

        // Per-mirror uniforms, all relative to the camera so the shader works in small numbers.
        for (Mirror m : toDraw) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                V3 p = m.geometry.probe(), n = m.geometry.normal();
                V3 bmin = m.box.min(), bmax = m.box.max();
                ByteBuffer data = Std140Builder.onStack(stack, MirrorProbe.UNIFORM_SIZE)
                        .putVec4((float) (p.x() - cam.x), (float) (p.y() - cam.y), (float) (p.z() - cam.z), 0f)
                        .putVec4((float) (bmin.x() - cam.x), (float) (bmin.y() - cam.y), (float) (bmin.z() - cam.z), 0f)
                        .putVec4((float) (bmax.x() - cam.x), (float) (bmax.y() - cam.y), (float) (bmax.z() - cam.z), 0f)
                        .putVec4((float) n.x(), (float) n.y(), (float) n.z(), 0f)
                        .putVec4(1f / m.probe.resolution, cfg.reflectivity, cfg.flipAtlasV ? 1f : 0f, 0f)
                        .get();
                encoder.writeToBuffer(m.probe.uniforms.slice(), data);
            }
        }

        // One vertex buffer with a quad per mirror, camera-relative.
        GpuBuffer vertices;
        int quadIndexCount;
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(toDraw.size() * 4 * DefaultVertexFormat.POSITION.getVertexSize())) {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            for (Mirror m : toDraw) {
                for (V3 v : m.geometry.corners()) {
                    builder.addVertex((float) (v.x() - cam.x), (float) (v.y() - cam.y), (float) (v.z() - cam.z));
                }
            }
            try (MeshData mesh = builder.buildOrThrow()) {
                vertices = DefaultVertexFormat.POSITION.uploadImmediateVertexBuffer(mesh.vertexBuffer());
                quadIndexCount = mesh.drawState().indexCount() / toDraw.size();
            }
        }
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer indexBuffer = indices.getBuffer(quadIndexCount);

        // Positions are camera-relative, so the model-view matrix is just the camera rotation.
        Matrix4f modelView = new Matrix4f().rotation(camera.rotation().conjugate(new Quaternionf()));
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
                .writeTransform(modelView, new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f());

        RenderTarget target = mc.getMainRenderTarget();
        try (RenderPass pass = encoder.createRenderPass(() -> "signmirrors mirrors",
                target.getColorTextureView(), OptionalInt.empty(),
                target.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, vertices);
            pass.setIndexBuffer(indexBuffer, indices.type());
            for (int i = 0; i < toDraw.size(); i++) {
                Mirror m = toDraw.get(i);
                pass.setUniform("MirrorInfo", m.probe.uniforms.slice());
                pass.bindTexture("Sampler0", m.probe.atlasView,
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.drawIndexed(i * 4, 0, quadIndexCount, 1);
            }
        }
    }
}
