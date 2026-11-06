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
        MirrorGeometry g = MirrorGeometry.build(0, 64, 0, new V3(0, 0, -1), MirrorGeometry.Mount.WALL, MirrorSettings.fromLegacy(spec));
        near(new V3(0, 0, -1), g.normal());
        double planeZ = 1 - MirrorGeometry.WALL_OFFSET;
        // Reader stands north looking south (+Z): their right is west (-X), so the right edge is x = 0
        // and the mirror extends east to x = 2.
        near(new V3(2, 64, planeZ), g.corners()[0]); // bottom-left as seen by the reader
        near(new V3(0, 64, planeZ), g.corners()[1]); // bottom-right
        near(new V3(0, 65, planeZ), g.corners()[2]);
        near(new V3(2, 65, planeZ), g.corners()[3]);
    }

    @Test
    void windingIsCounterClockwiseFromFront() {
        MirrorGeometry g = MirrorGeometry.build(5, 70, -3, new V3(1, 0, 0), MirrorGeometry.Mount.WALL,
                MirrorSettings.fromLegacy(MirrorSpec.parse("[Mirror]", "3,2", "30,20").orElseThrow()));
        V3[] c = g.corners();
        V3 faceNormal = c[1].sub(c[0]).cross(c[2].sub(c[0])).normalize();
        near(g.normal(), faceNormal);
    }

    @Test
    void hingedYawSwingsFreeEdgeOutward() {
        for (String align : new String[] {"r", "l"}) {
            MirrorSpec spec = MirrorSpec.parse("[Mirror]", "2,1" + align, "45").orElseThrow();
            MirrorGeometry g = MirrorGeometry.build(0, 64, 0, new V3(0, 0, -1), MirrorGeometry.Mount.WALL, MirrorSettings.fromLegacy(spec));
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
        MirrorGeometry g = MirrorGeometry.build(0, 64, 0, new V3(0, 0, -1), MirrorGeometry.Mount.WALL, MirrorSettings.fromLegacy(spec));
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

    // ---------- camera convention ----------

    @Test
    void cameraBasisMatchesMinecraftDirections() {
        // Minecraft: yaw 0 looks south (+Z), yaw 90 west (-X); pitch 90 looks straight down.
        near(new V3(0, 0, 1), CameraBasis.of(0, 0)[0]);
        near(new V3(-1, 0, 0), CameraBasis.of(0, 0)[1]); // facing south, your right is west
        near(new V3(0, 1, 0), CameraBasis.of(0, 0)[2]);
        near(new V3(-1, 0, 0), CameraBasis.of(90, 0)[0]);
        near(new V3(0, -1, 0), CameraBasis.of(0, 90)[0]);
        for (float[] p : new float[][] {{37, 21}, {-123, -45}}) {
            V3[] b = CameraBasis.of(p[0], p[1]);
            near(b[0].mul(-1), b[1].cross(b[2])); // right x up = backward
        }
    }
}
