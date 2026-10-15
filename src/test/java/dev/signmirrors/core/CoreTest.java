package dev.signmirrors.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoreTest {

    private static void near(double expected, double actual) {
        assertEquals(expected, actual, 1e-6);
    }

    private static void near(V3 e, V3 a) {
        near(e.x(), a.x());
        near(e.y(), a.y());
        near(e.z(), a.z());
    }

    // ---------- parsing ----------

    @Test
    void notAMirror() {
        assertTrue(MirrorSpec.parse("Hello", "2,1r", "").isEmpty());
        assertTrue(MirrorSpec.parse("", "", "").isEmpty());
    }

    @Test
    void defaults() {
        MirrorSpec s = MirrorSpec.parse(" [MIRROR] ", "", "").orElseThrow();
        assertEquals(MirrorSpec.DEFAULT, s);
    }

    @Test
    void sizeAndAlign() {
        MirrorSpec s = MirrorSpec.parse("[Mirror]", "2,1r", "").orElseThrow();
        near(2, s.width());
        near(1, s.height());
        assertEquals(MirrorSpec.HAlign.RIGHT, s.hAlign());
        assertEquals(MirrorSpec.VAlign.BOTTOM, s.vAlign());

        s = MirrorSpec.parse("[mirror]", "3 x 2 lt", "").orElseThrow();
        near(3, s.width());
        near(2, s.height());
        assertEquals(MirrorSpec.HAlign.LEFT, s.hAlign());
        assertEquals(MirrorSpec.VAlign.TOP, s.vAlign());

        s = MirrorSpec.parse("[mirror]", "1.5,40", "").orElseThrow();
        near(1.5, s.width());
        near(MirrorSpec.MAX_SIZE, s.height());
    }

    @Test
    void angles() {
        near(15, MirrorSpec.parse("[Mirror]", "1,1", "15").orElseThrow().yawDeg());
        near(45, MirrorSpec.parse("[Mirror]", "1,1", "45°").orElseThrow().yawDeg());
        MirrorSpec s = MirrorSpec.parse("[Mirror]", "1,1", "10, -5").orElseThrow();
        near(10, s.yawDeg());
        near(-5, s.pitchDeg());
        s = MirrorSpec.parse("[Mirror]", "1,1", "p15").orElseThrow();
        near(0, s.yawDeg());
        near(15, s.pitchDeg());
        s = MirrorSpec.parse("[Mirror]", "1,1", "y-30 p10").orElseThrow();
        near(-30, s.yawDeg());
        near(10, s.pitchDeg());
        near(89, MirrorSpec.parse("[Mirror]", "1,1", "120").orElseThrow().yawDeg());
    }

    // ---------- geometry ----------

    @Test
    void wallMirrorRightAligned() {
        // Wall sign at (0,64,0) whose text faces north (-Z): it hangs on the wall at z = 1.
        MirrorSpec spec = MirrorSpec.parse("[Mirror]", "2,1r", "").orElseThrow();
        MirrorGeometry g = MirrorGeometry.build(0, 64, 0, new V3(0, 0, -1), MirrorGeometry.Mount.WALL, spec, 0.5);
        near(new V3(0, 0, -1), g.normal());
        double planeZ = 1 - MirrorGeometry.WALL_OFFSET;
        // Reader stands north looking south (+Z): their right is west (-X), so the right edge is x = 0
        // and the mirror extends east to x = 2.
        near(new V3(2, 64, planeZ), g.corners()[0]); // bottom-left as seen by the reader
        near(new V3(0, 64, planeZ), g.corners()[1]); // bottom-right
        near(new V3(0, 65, planeZ), g.corners()[2]);
        near(new V3(2, 65, planeZ), g.corners()[3]);
        assertEquals(5, g.neededFaces().size());
        assertFalse(g.neededFaces().contains(CubeFace.POS_Z));
    }

    @Test
    void windingIsCounterClockwiseFromFront() {
        MirrorGeometry g = MirrorGeometry.build(5, 70, -3, new V3(1, 0, 0), MirrorGeometry.Mount.WALL,
                MirrorSpec.parse("[Mirror]", "3,2", "30,20").orElseThrow(), 0.5);
        V3[] c = g.corners();
        V3 faceNormal = c[1].sub(c[0]).cross(c[2].sub(c[0])).normalize();
        near(g.normal(), faceNormal);
    }

    @Test
    void hingedYawSwingsFreeEdgeOutward() {
        for (String align : new String[] {"r", "l"}) {
            MirrorSpec spec = MirrorSpec.parse("[Mirror]", "2,1" + align, "45").orElseThrow();
            MirrorGeometry g = MirrorGeometry.build(0, 64, 0, new V3(0, 0, -1), MirrorGeometry.Mount.WALL, spec, 0.5);
            double planeZ = 1 - MirrorGeometry.WALL_OFFSET;
            for (V3 c : g.corners()) assertTrue(c.z() <= planeZ + 1e-9, "corner went into the wall for " + align);
            // The free edge (bottom-left for r, bottom-right for l) must be 2*sin45 in front of the wall.
            V3 free = align.equals("r") ? g.corners()[0] : g.corners()[1];
            near(planeZ - 2 * Math.sin(Math.toRadians(45)), free.z());
        }
    }

    @Test
    void bottomHingePitchLeansTopOutward() {
        MirrorSpec spec = MirrorSpec.parse("[Mirror]", "1,2", "0,10").orElseThrow();
        MirrorGeometry g = MirrorGeometry.build(0, 64, 0, new V3(0, 0, -1), MirrorGeometry.Mount.WALL, spec, 0.5);
        double planeZ = 1 - MirrorGeometry.WALL_OFFSET;
        assertTrue(g.corners()[3].z() < planeZ - 0.3, "top edge should lean away from the wall");
        assertTrue(g.normal().y() < 0, "a bottom-hinged mirror leaning out looks slightly down");
    }

    @Test
    void standingRotation() {
        near(new V3(0, 0, 1), MirrorGeometry.frontFromRotation16(0));
        near(new V3(-1, 0, 0), MirrorGeometry.frontFromRotation16(4));
        near(new V3(0, 0, -1), MirrorGeometry.frontFromRotation16(8));
        near(new V3(1, 0, 0), MirrorGeometry.frontFromRotation16(12));
    }

    @Test
    void proxyBoxContainsMirror() {
        MirrorGeometry g = MirrorGeometry.build(0, 64, 0, new V3(0, 0, -1), MirrorGeometry.Mount.WALL,
                MirrorSpec.parse("[Mirror]", "4,3", "").orElseThrow(), 0.5);
        ProxyBox box = ProxyBox.build(g, 24, (a, b) -> 1.0); // tiny room
        for (V3 c : g.corners()) {
            for (int axis = 0; axis < 3; axis++) {
                assertTrue(c.get(axis) >= box.min().get(axis) && c.get(axis) <= box.max().get(axis));
            }
        }
    }

    // ---------- cube faces vs Minecraft's camera convention ----------

    /**
     * Re-implements Camera.setRotation: rotation = rotationYXZ(PI - yaw, -pitch, 0),
     * forwards = (0,0,-1), up = (0,1,0), left = (-1,0,0), each rotated by it.
     */
    private static V3[] mcCameraBasis(float yawDeg, float pitchDeg) {
        double ay = Math.PI - Math.toRadians(yawDeg);
        double ax = -Math.toRadians(pitchDeg);
        V3[] in = {new V3(0, 0, -1), new V3(0, 1, 0), new V3(-1, 0, 0)};
        V3[] out = new V3[3];
        for (int i = 0; i < 3; i++) {
            V3 v = in[i];
            // X rotation first (rotationYXZ applies Z, then X, then Y to the vector)
            double y1 = v.y() * Math.cos(ax) - v.z() * Math.sin(ax);
            double z1 = v.y() * Math.sin(ax) + v.z() * Math.cos(ax);
            double x2 = v.x() * Math.cos(ay) + z1 * Math.sin(ay);
            double z2 = -v.x() * Math.sin(ay) + z1 * Math.cos(ay);
            out[i] = new V3(x2, y1, z2);
        }
        return new V3[] {out[0], out[2].mul(-1), out[1]}; // forward, right, up
    }

    @Test
    void cubeFaceBasisMatchesMinecraftCamera() {
        for (CubeFace face : CubeFace.values()) {
            V3[] b = mcCameraBasis(face.yaw, face.pitch);
            near(face.forward, b[0]);
            near(face.right, b[1]);
            near(face.up, b[2]);
            // right x up = backward for a right-handed view space looking down -Z
            near(face.forward.mul(-1), face.right.cross(face.up));
        }
    }
}
