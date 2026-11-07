package dev.signmirrors.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.signmirrors.SignMirrorsClient;
import dev.signmirrors.config.MirrorConfig;
import dev.signmirrors.core.V3;
import dev.signmirrors.world.Mirror;
import dev.signmirrors.world.MirrorTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Draws every ready mirror as a quad whose fragment shader projects each pixel (snapped to the
 * mirror's pixel grid when pixelated) into the mirror's reflected-eye render, then applies the
 * mirror's tint, reflectivity and opacity. Runs at the end of the main world pass (Fabric's
 * LevelRenderEvents.END_MAIN), depth-tested against the world, for the player's view and, for
 * mirror-in-mirror, inside captures. Opaque mirrors first, then see-through ones back to front.
 */
public final class MirrorDrawer {
    /**
     * std140 MirrorInfo, must match mirror.vsh / mirror.fsh:
     * mat4 ProjMat, mat4 ModelViewMat, mat4 PlanarViewProj, vec4 PlanarOrigin, vec4 Corner,
     * vec4 AxisU, vec4 AxisV, vec4 Tint, vec4 Params, mat4 InvProjMat, vec4 DepthTest.
     */
    private static final int UNIFORM_SIZE = 4 * 64 + 7 * 16;

    private static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
            .withUniform("MirrorInfo", UniformType.UNIFORM_BUFFER)
            .withUniform("Reflection", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("SceneDepth", UniformType.COMBINED_IMAGE_SAMPLER)
            .build();

    public static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(id("pipeline/mirror"))
            .withVertexShader(id("mirror"))
            .withFragmentShader(id("mirror"))
            .withBindGroupLayout(LAYOUT)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withCull(true)
            .withVertexBinding(0, DefaultVertexFormat.POSITION)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .build();

    /**
     * With a shader pack, mirrors are drawn over Iris's finished image. The depth test is then done
     * in the shader against a copy-free read of the scene depth, in view space, so it doesn't depend
     * on how Iris has remapped depth or compare functions (a hardware test there let mirrors cover the
     * hand, the player in third person and other entities in front of them).
     */
    public static final RenderPipeline SHADER_PACK_PIPELINE = RenderPipeline.builder()
            .withLocation(id("pipeline/mirror_shaderpack"))
            .withVertexShader(id("mirror"))
            .withFragmentShader(id("mirror"))
            .withBindGroupLayout(LAYOUT)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withCull(true)
            .withVertexBinding(0, DefaultVertexFormat.POSITION)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .build();

    public static final RenderPipeline SHADER_PACK_TRANSLUCENT_PIPELINE = RenderPipeline.builder()
            .withLocation(id("pipeline/mirror_shaderpack_translucent"))
            .withVertexShader(id("mirror"))
            .withFragmentShader(id("mirror"))
            .withBindGroupLayout(LAYOUT)
            .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT), GpuFormat.RGBA8_UNORM, 0xFFFFFFFF))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withCull(true)
            .withVertexBinding(0, DefaultVertexFormat.POSITION)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .build();

    /** Set if sampling the scene depth fails on this setup; falls back to the hardware depth test. */
    private static boolean shaderDepthBroken;

    /** Mirrors with opacity below 100%: alpha-blended over the world. */
    public static final RenderPipeline TRANSLUCENT_PIPELINE = RenderPipeline.builder()
            .withLocation(id("pipeline/mirror_translucent"))
            .withVertexShader(id("mirror"))
            .withFragmentShader(id("mirror"))
            .withBindGroupLayout(LAYOUT)
            .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT), GpuFormat.RGBA8_UNORM, 0xFFFFFFFF))
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withCull(true)
            .withVertexBinding(0, DefaultVertexFormat.POSITION)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .build();

    /** The projection actually used for the current level render (includes view bobbing). */
    private static Matrix4f frameProjection;
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static Matrix4f mainViewProjection;
    private static Vec3 mainCameraPosition;

    private MirrorDrawer() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(SignMirrorsClient.MOD_ID, path);
    }

    /** The projection recorded for the most recent level render (the capture's, during a capture). */
    static Matrix4f lastProjection() {
        return frameProjection == null ? null : new Matrix4f(frameProjection);
    }

    /** projection x view of the player's last rendered view (camera-relative), or null before the first frame. */
    static Matrix4f mainViewProjection() {
        return mainViewProjection;
    }

    /** Camera position of the player's last rendered view. */
    static Vec3 mainCameraPosition() {
        return mainCameraPosition;
    }

    private static boolean inFrustum(FrustumIntersection frustum, Mirror m, Vec3 cam) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (V3 c : m.geometry.corners()) {
            float x = (float) (c.x() - cam.x), y = (float) (c.y() - cam.y), z = (float) (c.z() - cam.z);
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
        }
        return frustum.testAab(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Recorded by GameRendererMixin from the projection buffer upload in GameRenderer.renderLevel. */
    public static void recordProjection(Matrix4f projection) {
        frameProjection = new Matrix4f(projection);
    }

    /** LevelRenderEvents.END_MAIN */
    /** End of the world pass (Fabric END_MAIN). With a shader pack this would be overwritten, so it waits (see below). */
    public static void drawInWorldPass(CameraRenderState cameraState) {
        if (IrisCompat.shaderPackActive()) return;
        draw(cameraState, false);
    }

    /** Right after a shader pack's final pass (Iris only), over the finished image. */
    public static void drawAfterShaderPack(CameraRenderState cameraState) {
        if (!IrisCompat.shaderPackActive()) return;
        IrisCompat.setBypass(true);
        try {
            draw(cameraState, true);
        } catch (RuntimeException e) {
            if (shaderDepthBroken) throw e;
            shaderDepthBroken = true;
            SignMirrorsClient.LOGGER.warn("Could not depth-test mirrors in the shader with this setup; using the hardware depth test", e);
        } finally {
            IrisCompat.setBypass(false);
        }
    }

    private static void draw(CameraRenderState cameraState, boolean shaderPackDepth) {
        MirrorConfig cfg = SignMirrorsClient.config;
        if (!cfg.enabled) return;
        boolean capturing = MirrorCapture.isCapturing();
        if (capturing && !cfg.recursiveMirrors) return;

        Vec3 cam = cameraState.pos;
        Mirror skip = capturing ? MirrorCapture.currentMirror() : null;
        Matrix4f projection = frameProjection != null ? frameProjection : new Matrix4f(cameraState.projectionMatrix);
        Matrix4f modelView = new Matrix4f(cameraState.viewRotationMatrix);
        Matrix4f viewProj = new Matrix4f(projection).mul(modelView);
        if (!capturing) {
            // Next frame's visibility/LOD pass works from this view.
            mainViewProjection = new Matrix4f(viewProj);
            mainCameraPosition = cam;
        }
        FrustumIntersection frustum = cfg.frustumCulling ? new FrustumIntersection(viewProj) : null;

        List<Mirror> toDraw = new ArrayList<>();
        for (Mirror m : MirrorTracker.INSTANCE.all()) {
            if (m == skip || !m.drawsGlass()) continue;
            // Inside a reflection, use the texture rendered for that reflection's viewpoint if there is one.
            if (m.reflects() && m.textureFor(skip) == null) continue; // nothing rendered yet
            if (!capturing && !m.visible) continue; // culled for the player's view (frustum/occlusion)
            V3 c = m.geometry.center(), n = m.geometry.normal();
            double dx = cam.x - c.x(), dy = cam.y - c.y(), dz = cam.z - c.z();
            if (dx * dx + dy * dy + dz * dz > cfg.maxMirrorDistance * cfg.maxMirrorDistance) continue;
            if (dx * n.x() + dy * n.y() + dz * n.z() <= 0) continue; // looking at the back
            if (frustum != null && !inFrustum(frustum, m, cam)) continue; // exact test for this view
            toDraw.add(m);
        }
        if (toDraw.isEmpty()) return;
        // Opaque first (any order), then see-through mirrors far to near so they blend correctly.
        toDraw.sort(Comparator.comparingInt((Mirror m) -> m.settings.opacity >= 100 ? 0 : 1)
                .thenComparingDouble(m -> m.settings.opacity >= 100 ? 0 : -distSq(cam, m.geometry.center())));

        // One vertex buffer holding a camera-relative quad per mirror.
        GpuBuffer vertices;
        int quadIndexCount;
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(toDraw.size() * 4 * 3 * Float.BYTES)) {
            BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION);
            for (Mirror m : toDraw) {
                for (V3 v : m.geometry.corners()) {
                    builder.addVertex((float) (v.x() - cam.x), (float) (v.y() - cam.y), (float) (v.z() - cam.z));
                }
            }
            try (MeshData mesh = builder.buildOrThrow()) {
                vertices = RenderSystem.getDevice().createBuffer(() -> "signmirrors quads",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, mesh.vertexBuffer());
                quadIndexCount = mesh.drawState().indexCount() / toDraw.size();
            }
        }
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = indices.getBuffer(quadIndexCount);
        // Iris renders the level with ordinary depth; match it so the depth test works.
        Matrix4f drawProjection = shaderPackDepth
                ? IrisCompat.undoReverseZ(projection, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne())
                : projection;
        boolean shaderDepth = shaderPackDepth && !shaderDepthBroken;
        DepthInfo depthInfo = new DepthInfo(shaderDepth, new Matrix4f(drawProjection).invert(),
                RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
        GpuSampler smooth = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        GpuSampler sharp = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

        List<GpuBuffer> uniformBuffers = new ArrayList<>(toDraw.size());
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuFormat colorFormat = target.getColorTexture().getFormat();
        GpuFormat depthFormat = target.getDepthTexture().getFormat();
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        // Shader-side depth test: the depth texture is read, so it mustn't also be attached.
        try (RenderPass pass = shaderDepth
                ? encoder.createRenderPass(() -> "signmirrors mirrors", target.getColorTextureView(), Optional.empty())
                : encoder.createRenderPass(() -> "signmirrors mirrors", target.getColorTextureView(), Optional.empty(),
                        target.getDepthTextureView(), OptionalDouble.empty())) {
            RenderPipeline bound = null;
            for (int i = 0; i < toDraw.size(); i++) {
                Mirror m = toDraw.get(i);
                boolean opaque = m.settings.opacity >= 100;
                RenderPipeline pipeline = shaderDepth
                        ? (opaque ? SHADER_PACK_PIPELINE : SHADER_PACK_TRANSLUCENT_PIPELINE)
                        : (opaque ? PIPELINE : TRANSLUCENT_PIPELINE);
                if (pipeline != bound) {
                    pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
                    pass.setVertexBuffer(0, vertices.slice());
                    pass.setIndexBuffer(indexBuffer, indices.type());
                    bound = pipeline;
                }
                MirrorProbe texture = m.reflects() ? m.textureFor(skip) : null;
                GpuBuffer uniforms = writeUniforms(m, texture, cam, drawProjection, modelView, cfg, depthInfo,
                        target.width, target.height);
                uniformBuffers.add(uniforms);
                pass.setUniform("MirrorInfo", uniforms.slice());
                RenderTarget reflection = texture != null ? texture.target() : MirrorProbe.placeholder(colorFormat, depthFormat);
                pass.setUniform("Reflection", reflection.getColorTextureView(),
                        m.settings.pixelsPerBlock > 0 ? sharp : smooth);
                // Without the shader-side test this sampler is unused; bind something that isn't attached.
                pass.setUniform("SceneDepth", shaderDepth ? target.getDepthTextureView()
                        : MirrorProbe.placeholder(colorFormat, depthFormat).getColorTextureView(), sharp);
                pass.drawIndexed(quadIndexCount, 1, 0, i * 4, 0);
            }
        } finally {
            // Closing GPU buffers is deferred until the GPU is done with them.
            vertices.close();
            for (GpuBuffer b : uniformBuffers) b.close();
        }
    }

    private static double distSq(Vec3 cam, V3 p) {
        double dx = cam.x - p.x(), dy = cam.y - p.y(), dz = cam.z - p.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /** How the shader-side depth test reads the scene depth. */
    private record DepthInfo(boolean enabled, Matrix4f inverseProjection, boolean zeroToOne) {}

    private static GpuBuffer writeUniforms(Mirror m, MirrorProbe texture, Vec3 cam, Matrix4f projection,
                                           Matrix4f modelView, MirrorConfig cfg, DepthInfo depth,
                                           int screenWidth, int screenHeight) {
        boolean reflecting = texture != null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = stack.calloc(UNIFORM_SIZE);
            var s = m.settings;
            var g = m.geometry;
            V3 c0 = g.corners()[0], u = g.right(), v = g.up();
            float tr = (s.tint >> 16 & 0xFF) / 255f, tg = (s.tint >> 8 & 0xFF) / 255f, tb = (s.tint & 0xFF) / 255f;
            float reflectivity = reflecting ? s.reflectivity / 100f : 0f;
            Std140Builder.intoBuffer(data)
                    .putMat4f(projection)
                    .putMat4f(modelView)
                    .putMat4f(reflecting ? texture.viewProj : IDENTITY)
                    .putVec4(reflecting ? (float) (texture.origin.x - cam.x) : 0f,
                             reflecting ? (float) (texture.origin.y - cam.y) : 0f,
                             reflecting ? (float) (texture.origin.z - cam.z) : 0f, 0f)
                    .putVec4((float) (c0.x() - cam.x), (float) (c0.y() - cam.y), (float) (c0.z() - cam.z), 0f)
                    .putVec4((float) u.x(), (float) u.y(), (float) u.z(), (float) g.width())
                    .putVec4((float) v.x(), (float) v.y(), (float) v.z(), (float) g.height())
                    .putVec4(tr, tg, tb, s.opacity / 100f)
                    .putVec4(reflecting ? 1f / texture.resolution : 0f, reflectivity,
                             cfg.flipFaceV ? 1f : 0f, s.pixelsPerBlock)
                    .putMat4f(depth.inverseProjection())
                    .putVec4(depth.enabled() ? 1f : 0f, depth.zeroToOne() ? 1f : 0f,
                             1f / Math.max(1, screenWidth), 1f / Math.max(1, screenHeight));
            // Std140Builder may use relative puts; hand the whole block over either way.
            data.position(0).limit(UNIFORM_SIZE);
            return RenderSystem.getDevice().createBuffer(() -> "signmirrors MirrorInfo",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, data);
        }
    }
}
