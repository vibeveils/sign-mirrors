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
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.signmirrors.SignMirrorsClient;
import dev.signmirrors.config.MirrorConfig;
import dev.signmirrors.core.CubeFace;
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
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Draws every ready mirror as a quad whose fragment shader reflects the view ray, intersects it
 * with the mirror's proxy box (parallax correction) and samples the cube face in the corrected
 * direction. Runs at the end of the main world pass (Fabric's LevelRenderEvents.END_MAIN), with
 * depth testing against the world — for the player's view and, for mirror-in-mirror, inside captures.
 */
public final class MirrorDrawer {
    /** Names must match mirror.fsh / mirror.vsh. */
    static final String[] FACE_SAMPLERS = {"FacePosX", "FaceNegX", "FacePosY", "FaceNegY", "FacePosZ", "FaceNegZ"};

    /** std140 MirrorInfo: mat4 ProjMat, mat4 ModelViewMat, then five vec4s. */
    private static final int UNIFORM_SIZE = 64 + 64 + 5 * 16;

    private static final BindGroupLayout LAYOUT = buildLayout();

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

    /** The projection actually used for the current level render (includes view bobbing). */
    private static Matrix4f frameProjection;
    private static Matrix4f mainViewProjection;
    private static Vec3 mainCameraPosition;

    private MirrorDrawer() {}

    private static BindGroupLayout buildLayout() {
        var b = BindGroupLayout.builder().withUniform("MirrorInfo", UniformType.UNIFORM_BUFFER);
        for (String name : FACE_SAMPLERS) b = b.withUniform(name, UniformType.COMBINED_IMAGE_SAMPLER);
        return b.build();
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(SignMirrorsClient.MOD_ID, path);
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
    public static void draw(CameraRenderState cameraState) {
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
            if (m == skip || !m.ready()) continue;
            if (!capturing && !m.visible) continue; // culled for the player's view (frustum/occlusion)
            V3 c = m.geometry.center(), n = m.geometry.normal();
            double dx = cam.x - c.x(), dy = cam.y - c.y(), dz = cam.z - c.z();
            if (dx * dx + dy * dy + dz * dz > cfg.maxMirrorDistance * cfg.maxMirrorDistance) continue;
            if (dx * n.x() + dy * n.y() + dz * n.z() <= 0) continue; // looking at the back
            if (frustum != null && !inFrustum(frustum, m, cam)) continue; // exact test for this view
            toDraw.add(m);
        }
        if (toDraw.isEmpty()) return;

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
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);

        List<GpuBuffer> uniformBuffers = new ArrayList<>(toDraw.size());
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "signmirrors mirrors",
                target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
            pass.setVertexBuffer(0, vertices.slice());
            pass.setIndexBuffer(indexBuffer, indices.type());

            for (int i = 0; i < toDraw.size(); i++) {
                Mirror m = toDraw.get(i);
                GpuBuffer uniforms = writeUniforms(m, cam, projection, modelView, cfg);
                uniformBuffers.add(uniforms);
                pass.setUniform("MirrorInfo", uniforms.slice());
                for (CubeFace face : CubeFace.values()) {
                    RenderTarget faceTarget = m.probe.face(face);
                    if (faceTarget == null) faceTarget = m.probe.anyFace();
                    pass.setUniform(FACE_SAMPLERS[face.ordinal()], faceTarget.getColorTextureView(), sampler);
                }
                pass.drawIndexed(quadIndexCount, 1, 0, i * 4, 0);
            }
        } finally {
            // Closing GPU buffers is deferred until the GPU is done with them.
            vertices.close();
            for (GpuBuffer b : uniformBuffers) b.close();
        }
    }

    private static GpuBuffer writeUniforms(Mirror m, Vec3 cam, Matrix4f projection, Matrix4f modelView, MirrorConfig cfg) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = stack.calloc(UNIFORM_SIZE);
            V3 p = m.geometry.probe(), n = m.geometry.normal();
            V3 bmin = m.box.min(), bmax = m.box.max();
            Std140Builder.intoBuffer(data)
                    .putMat4f(projection)
                    .putMat4f(modelView)
                    .putVec4((float) (p.x() - cam.x), (float) (p.y() - cam.y), (float) (p.z() - cam.z), 0f)
                    .putVec4((float) (bmin.x() - cam.x), (float) (bmin.y() - cam.y), (float) (bmin.z() - cam.z), 0f)
                    .putVec4((float) (bmax.x() - cam.x), (float) (bmax.y() - cam.y), (float) (bmax.z() - cam.z), 0f)
                    .putVec4((float) n.x(), (float) n.y(), (float) n.z(), 0f)
                    .putVec4(1f / m.probe.resolution, cfg.reflectivity, cfg.flipFaceV ? 1f : 0f, 0f);
            // Std140Builder may use relative puts; hand the whole block over either way.
            data.position(0).limit(UNIFORM_SIZE);
            return RenderSystem.getDevice().createBuffer(() -> "signmirrors MirrorInfo",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, data);
        }
    }
}
