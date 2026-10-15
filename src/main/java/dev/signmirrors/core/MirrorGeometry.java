package dev.signmirrors.core;

import java.util.ArrayList;
import java.util.List;

/**
 * World-space geometry of one mirror: a quad (corners in counter-clockwise order as seen from the
 * reflective side), its normal, and the cube-map probe position.
 */
public record MirrorGeometry(V3[] corners, V3 normal, V3 center, V3 probe, List<CubeFace> neededFaces) {

    /** Wall signs: the mirror sits this far in front of the wall, just proud of the sign board (2/16). */
    public static final double WALL_OFFSET = 2.5 / 16.0;
    /** Standing/hanging signs: the mirror sits just in front of the board's front face (board is 2/16 thick). */
    public static final double STANDING_OFFSET = 1.5 / 16.0;

    public enum Mount { WALL, STANDING }

    /**
     * @param blockX/Y/Z  the sign's block position (minimum corner)
     * @param front       unit horizontal vector the sign's text faces (toward the reader)
     * @param mount       wall sign (mirror flush to the wall behind it) or free-standing
     * @param probeOffset how far in front of the mirror centre the cube-map probe sits
     */
    public static MirrorGeometry build(int blockX, int blockY, int blockZ, V3 front, Mount mount,
                                       MirrorSpec spec, double probeOffset) {
        V3 f = new V3(front.x(), 0, front.z()).normalize();
        V3 up = V3.UP;
        // The reader looks along -f; their right-hand side is (-f) x up.
        V3 right = f.mul(-1).cross(up).normalize();

        V3 blockCenter = new V3(blockX + 0.5, blockY + 0.5, blockZ + 0.5);
        V3 planeCenter = mount == Mount.WALL
                ? blockCenter.add(f.mul(-0.5 + WALL_OFFSET))
                : blockCenter.add(f.mul(STANDING_OFFSET));

        double w = spec.width(), h = spec.height();

        // Hinge point and the mirror's extent around it, in (right, up) units.
        double hingeR, x0, x1;
        double yawSign;
        switch (spec.hAlign()) {
            case RIGHT -> { hingeR = 0.5; x0 = -w; x1 = 0; yawSign = 1; }
            case LEFT -> { hingeR = -0.5; x0 = 0; x1 = w; yawSign = -1; }
            default -> { hingeR = 0; x0 = -w / 2; x1 = w / 2; yawSign = 1; }
        }
        double hingeY, y0, y1;
        double pitchSign;
        switch (spec.vAlign()) {
            case TOP -> { hingeY = blockY + 1.0; y0 = -h; y1 = 0; pitchSign = 1; }
            case MIDDLE -> { hingeY = blockY + 0.5; y0 = -h / 2; y1 = h / 2; pitchSign = 1; }
            default -> { hingeY = blockY; y0 = 0; y1 = h; pitchSign = -1; }
        }
        V3 hinge = new V3(planeCenter.x(), hingeY, planeCenter.z()).add(right.mul(hingeR));

        // Yaw: rotate the face toward the reader's right about the vertical axis through the hinge.
        double yaw = Math.toRadians(spec.yawDeg() * yawSign);
        V3 f1 = f.mul(Math.cos(yaw)).add(right.mul(Math.sin(yaw)));
        V3 r1 = right.mul(Math.cos(yaw)).sub(f.mul(Math.sin(yaw)));
        // Pitch: tilt the face upward about the (rotated) horizontal axis through the hinge.
        double pitch = Math.toRadians(spec.pitchDeg() * pitchSign);
        V3 n = f1.mul(Math.cos(pitch)).add(up.mul(Math.sin(pitch))).normalize();
        V3 u1 = up.mul(Math.cos(pitch)).sub(f1.mul(Math.sin(pitch))).normalize();
        V3 r = r1.normalize();

        V3[] corners = new V3[] {
                hinge.add(r.mul(x0)).add(u1.mul(y0)),
                hinge.add(r.mul(x1)).add(u1.mul(y0)),
                hinge.add(r.mul(x1)).add(u1.mul(y1)),
                hinge.add(r.mul(x0)).add(u1.mul(y1)),
        };
        V3 center = corners[0].add(corners[2]).mul(0.5);
        V3 probe = center.add(n.mul(probeOffset));

        List<CubeFace> faces = new ArrayList<>(5);
        for (CubeFace face : CubeFace.values()) if (face.neededFor(n)) faces.add(face);
        return new MirrorGeometry(corners, n, center, probe, List.copyOf(faces));
    }

    /** Horizontal facing for a 16-step sign rotation (0 = text faces south/+Z, steps of 22.5 degrees clockwise). */
    public static V3 frontFromRotation16(int rotation) {
        double deg = rotation * 22.5;
        double rad = Math.toRadians(deg);
        return new V3(-Math.sin(rad), 0, Math.cos(rad));
    }
}
