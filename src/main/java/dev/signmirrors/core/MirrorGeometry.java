package dev.signmirrors.core;

import java.util.ArrayList;
import java.util.List;

/**
 * World-space shape of a mirror.
 *
 * @param corners bottom-left, bottom-right, top-right, top-left as seen by the reader; counter-clockwise
 *                seen from the front, so (c1-c0) x (c2-c0) points along {@code normal}
 * @param normal  unit normal of the reflective face (toward the reader)
 * @param right   unit vector along the bottom edge (c0 to c1)
 * @param up      unit vector along the left edge (c0 to c3)
 * @param width   length of the bottom edge, in blocks
 * @param height  length of the left edge, in blocks
 */
public record MirrorGeometry(V3[] corners, V3 normal, V3 center, V3 right, V3 up, double width, double height) {
    /** Wall signs: the glass sits this far out from the wall (just proud of the sign board, which it covers). */
    public static final double WALL_OFFSET = 2.5 / 16.0;
    /** Standing and ceiling-hanging signs: this far in front of the block's centre (just proud of the board). */
    public static final double STANDING_OFFSET = 1.5 / 16.0;
    /** Frames stand this far proud of the glass, and reach this far behind it. */
    public static final double FRAME_FRONT = 1 / 16.0, FRAME_BACK = 1 / 16.0;

    public enum Mount { WALL, STANDING }

    /**
     * Builds the mirror for a sign at block (x, y, z) whose text faces {@code front}.
     * <ol>
     *   <li>The anchor is the point the alignment names, at the sign: horizontally the sign block's
     *       left edge / centre / right edge (as seen by the reader), vertically its bottom / middle /
     *       top; on the plane the original format used (against the wall, or through the block).</li>
     *   <li>The offset moves the anchor right / up / out of the sign, in the sign's own (unrotated) axes.</li>
     *   <li>The mirror is laid out from the anchor and rotated about it: yaw turns the reflective face
     *       toward the reader's right, then pitch tilts it up, then roll turns it counter-clockwise
     *       as seen by the reader.</li>
     * </ol>
     */
    public static MirrorGeometry build(int blockX, int blockY, int blockZ, V3 front, Mount mount, MirrorSettings s) {
        V3 f = new V3(front.x(), 0, front.z()).normalize();
        V3 up = V3.UP;
        // The reader faces -f; their right is (-f) x up.
        V3 right = f.mul(-1).cross(up).normalize();

        V3 blockCenter = new V3(blockX + 0.5, blockY + 0.5, blockZ + 0.5);
        V3 planeCenter = mount == Mount.WALL
                ? blockCenter.add(f.mul(-0.5 + WALL_OFFSET))
                : blockCenter.add(f.mul(STANDING_OFFSET));

        double w = s.width, h = s.height;
        double hingeR, x0, x1;
        switch (s.hAlign) {
            case RIGHT -> { hingeR = 0.5; x0 = -w; x1 = 0; }
            case LEFT -> { hingeR = -0.5; x0 = 0; x1 = w; }
            default -> { hingeR = 0; x0 = -w / 2; x1 = w / 2; }
        }
        double hingeY, y0, y1;
        switch (s.vAlign) {
            case TOP -> { hingeY = blockY + 1; y0 = -h; y1 = 0; }
            case MIDDLE -> { hingeY = blockY + 0.5; y0 = -h / 2; y1 = h / 2; }
            default -> { hingeY = blockY; y0 = 0; y1 = h; }
        }
        V3 anchor = new V3(planeCenter.x(), hingeY, planeCenter.z()).add(right.mul(hingeR))
                .add(right.mul(s.offsetX)).add(up.mul(s.offsetY)).add(f.mul(s.offsetZ));

        // Yaw about the vertical through the anchor: the face (f) turns toward the reader's right.
        double yaw = Math.toRadians(s.yaw);
        V3 f1 = f.mul(Math.cos(yaw)).add(right.mul(Math.sin(yaw)));
        V3 r1 = right.mul(Math.cos(yaw)).sub(f.mul(Math.sin(yaw)));
        // Pitch about the (yawed) horizontal: the face tilts up.
        double pitch = Math.toRadians(s.pitch);
        V3 n = f1.mul(Math.cos(pitch)).add(up.mul(Math.sin(pitch))).normalize();
        V3 u1 = up.mul(Math.cos(pitch)).sub(f1.mul(Math.sin(pitch))).normalize();
        // Roll about the normal: counter-clockwise as seen from the front.
        double roll = Math.toRadians(s.roll);
        V3 r2 = r1.mul(Math.cos(roll)).add(u1.mul(Math.sin(roll))).normalize();
        V3 u2 = u1.mul(Math.cos(roll)).sub(r1.mul(Math.sin(roll))).normalize();

        V3[] corners = {
                anchor.add(r2.mul(x0)).add(u2.mul(y0)),
                anchor.add(r2.mul(x1)).add(u2.mul(y0)),
                anchor.add(r2.mul(x1)).add(u2.mul(y1)),
                anchor.add(r2.mul(x0)).add(u2.mul(y1)),
        };
        V3 center = corners[0].add(corners[2]).mul(0.5);
        return new MirrorGeometry(corners, n, center, r2, u2, w, h);
    }

    /** Standing sign ROTATION_16 value to the direction its front text faces. */
    public static V3 frontFromRotation16(int rotation) {
        double rad = Math.toRadians(rotation * 22.5);
        return new V3(-Math.sin(rad), 0, Math.cos(rad));
    }

    /**
     * A box with one corner at {@code origin} and edges {@code ex, ey, ez}, where ex x ey points
     * along ez (right-handed), so faces can be wound consistently.
     */
    public record Box(V3 origin, V3 ex, V3 ey, V3 ez) {}

    /**
     * The frame around the glass as boxes no longer than one block, so a block texture can be
     * stretched over each piece without distortion: a border {@code borderBlocks} wide on every side,
     * from {@link #FRAME_BACK} behind the glass to {@link #FRAME_FRONT} in front of it.
     */
    public List<Box> frameBoxes(double borderBlocks) {
        List<Box> out = new ArrayList<>();
        if (borderBlocks <= 0) return out;
        double t = borderBlocks;
        V3 back = corners[0].sub(normal.mul(FRAME_BACK));
        V3 depth = normal.mul(FRAME_FRONT + FRAME_BACK);
        // Bottom and top bars run the full width including the corners; the sides fill in between.
        bar(out, back.add(right.mul(-t)).add(up.mul(-t)), right, width + 2 * t, up.mul(t), depth);
        bar(out, back.add(right.mul(-t)).add(up.mul(height)), right, width + 2 * t, up.mul(t), depth);
        bar(out, back.add(right.mul(-t)), up, height, right.mul(t), depth, true);
        bar(out, back.add(right.mul(width)), up, height, right.mul(t), depth, true);
        return out;
    }

    private static void bar(List<Box> out, V3 start, V3 along, double length, V3 across, V3 depth) {
        bar(out, start, along, length, across, depth, false);
    }

    /** Splits a bar into pieces of at most one block. {@code alongIsUp}: along x across must still point along depth. */
    private static void bar(List<Box> out, V3 start, V3 along, double length, V3 across, V3 depth, boolean alongIsUp) {
        int pieces = Math.max(1, (int) Math.ceil(length - 1e-9));
        double step = length / pieces;
        for (int i = 0; i < pieces; i++) {
            V3 o = start.add(along.mul(i * step));
            V3 e = along.mul(step);
            // For vertical bars (along = up, across = right), up x right = -normal, so swap to stay right-handed.
            out.add(alongIsUp ? new Box(o, across, e, depth) : new Box(o, e, across, depth));
        }
    }
}
