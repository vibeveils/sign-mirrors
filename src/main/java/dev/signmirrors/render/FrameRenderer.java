package dev.signmirrors.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.signmirrors.core.MirrorGeometry;
import dev.signmirrors.core.MirrorSettings;
import dev.signmirrors.core.V3;
import dev.signmirrors.world.Mirror;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.resources.Identifier;

import java.util.List;

/** Mirror frames: boxes textured with a block texture, submitted from the sign's block entity renderer. */
public final class FrameRenderer {
    private static final Identifier BLOCK_ATLAS = Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png");

    private FrameRenderer() {}

    public static void submit(Mirror mirror, SignRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
        List<MirrorGeometry.Box> boxes = mirror.frameBoxes;
        if (boxes == null || boxes.isEmpty()) return;
        TextureAtlasSprite sprite = sprite(mirror);
        if (sprite == null) return;
        int light = state.lightCoords;
        // The pose is at the sign block's corner; frame boxes are in world space.
        double ox = mirror.pos.getX(), oy = mirror.pos.getY(), oz = mirror.pos.getZ();
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(sprite.atlasLocation()), (pose, consumer) -> {
            VertexConsumer vc = sprite.wrap(consumer);
            for (MirrorGeometry.Box b : boxes) box(pose, vc, b, ox, oy, oz, light);
        });
    }

    private static TextureAtlasSprite sprite(Mirror mirror) {
        MirrorSettings.Frame frame = mirror.settings.frame;
        String texture = frame == MirrorSettings.Frame.SIGN_WOOD ? "block/" + mirror.woodType + "_planks" : frame.texture;
        if (texture == null) return null;
        try {
            return Minecraft.getInstance().getAtlasManager().get(
                    new SpriteId(BLOCK_ATLAS, Identifier.fromNamespaceAndPath("minecraft", texture)));
        } catch (RuntimeException e) {
            return null; // modded wood without planks of that name, etc.
        }
    }

    /** Six faces, wound counter-clockwise seen from outside (ex x ey points along ez). */
    private static void box(PoseStack.Pose pose, VertexConsumer vc, MirrorGeometry.Box b,
                            double ox, double oy, double oz, int light) {
        V3 o = b.origin(), x = b.ex(), y = b.ey(), z = b.ez();
        V3 nx = x.normalize(), ny = y.normalize(), nz = z.normalize();
        face(pose, vc, o.add(z), x, y, nz, ox, oy, oz, light);          // front
        face(pose, vc, o, y, x, nz.mul(-1), ox, oy, oz, light);         // back
        face(pose, vc, o.add(x), y, z, nx, ox, oy, oz, light);          // +x side
        face(pose, vc, o, z, y, nx.mul(-1), ox, oy, oz, light);         // -x side
        face(pose, vc, o.add(y), z, x, ny, ox, oy, oz, light);          // +y side
        face(pose, vc, o, x, z, ny.mul(-1), ox, oy, oz, light);         // -y side
    }

    private static void face(PoseStack.Pose pose, VertexConsumer vc, V3 p, V3 e1, V3 e2, V3 n,
                             double ox, double oy, double oz, int light) {
        // UVs in blocks (pieces are at most one block), so the texture keeps its scale.
        float u1 = (float) Math.min(1, e1.length()), v1 = (float) Math.min(1, e2.length());
        vertex(pose, vc, p, 0, v1, n, ox, oy, oz, light);
        vertex(pose, vc, p.add(e1), u1, v1, n, ox, oy, oz, light);
        vertex(pose, vc, p.add(e1).add(e2), u1, 0, n, ox, oy, oz, light);
        vertex(pose, vc, p.add(e2), 0, 0, n, ox, oy, oz, light);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vc, V3 p, float u, float v, V3 n,
                               double ox, double oy, double oz, int light) {
        vc.addVertex(pose.pose(), (float) (p.x() - ox), (float) (p.y() - oy), (float) (p.z() - oz))
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, (float) n.x(), (float) n.y(), (float) n.z());
    }
}
