package dev.signmirrors.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    private static void near(double e, double a) { assertEquals(e, a, 1e-6); }

    private static void near(V3 e, V3 a) {
        near(e.x(), a.x());
        near(e.y(), a.y());
        near(e.z(), a.z());
    }

    /** Roughly Minecraft's font: most characters 6 px wide, a few narrow. */
    private static int fontWidth(String s) {
        int w = 0;
        for (char c : s.toCharArray()) w += "il!.,:;'|".indexOf(c) >= 0 ? 2 : c == 'I' || c == 't' ? 4 : 6;
        return w;
    }

    private static MirrorSettings everything() {
        MirrorSettings s = new MirrorSettings();
        s.width = 15.9375; s.height = 0.0625;
        s.hAlign = MirrorSpec.HAlign.RIGHT; s.vAlign = MirrorSpec.VAlign.TOP;
        s.yaw = -89.5; s.pitch = 45; s.roll = 179.5;
        s.offsetX = -15.9375; s.offsetY = 3.25; s.offsetZ = 16;
        s.frame = MirrorSettings.Frame.BLACK_CONCRETE; s.frameWidth = 7.5;
        s.pixelsPerBlock = 256;
        s.tint = 0x12AB34; s.opacity = 37; s.reflectivity = 0;
        s.hideSign = true;
        return s;
    }

    @Test
    void defaultsEncodeTiny() {
        String p = new MirrorSettings().encode();
        assertTrue(p.length() <= 4, p);
        assertEquals(new MirrorSettings(), MirrorSettings.decode(p).orElseThrow());
    }

    @Test
    void roundTripsEveryField() {
        MirrorSettings s = everything();
        assertEquals(s, MirrorSettings.decode(s.encode()).orElseThrow());
        for (MirrorSpec.HAlign h : MirrorSpec.HAlign.values()) {
            for (MirrorSpec.VAlign v : MirrorSpec.VAlign.values()) {
                MirrorSettings a = new MirrorSettings();
                a.hAlign = h; a.vAlign = v;
                assertEquals(a, MirrorSettings.decode(a.encode()).orElseThrow());
            }
        }
    }

    @Test
    void valuesAreQuantisedAndClamped() {
        MirrorSettings s = new MirrorSettings();
        s.width = 1.03; s.yaw = 10.3; s.offsetX = 99; s.opacity = 140;
        MirrorSettings d = MirrorSettings.decode(s.encode()).orElseThrow();
        near(1.0, d.width);
        near(10.5, d.yaw);
        near(MirrorSettings.MAX_OFFSET, d.offsetX);
        assertEquals(100, d.opacity);
    }

    @Test
    void badPayloadsAreRejected() {
        assertTrue(MirrorSettings.decode("").isEmpty());
        assertTrue(MirrorSettings.decode("!!!").isEmpty());
        String full = everything().encode();
        assertTrue(MirrorSettings.decode(full.substring(0, full.length() / 2)).isEmpty(), "truncated");
        assertTrue(MirrorSettings.decode("Ag").isEmpty(), "unknown version");
    }

    @Test
    void linesFitOnASignAndParseBack() {
        for (MirrorSettings s : List.of(new MirrorSettings(), everything())) {
            String[] lines = s.toLines(90, SettingsTest::fontWidth);
            assertNotNull(lines, "should fit on a standard sign");
            assertEquals("[Mirror]", lines[0]);
            assertEquals('~', lines[1].charAt(0));
            for (String l : lines) assertTrue(fontWidth(l) <= 90, l);
            MirrorSettings parsed = MirrorSettings.parse(lines[0], lines[1], lines[2], lines[3]).orElseThrow();
            assertEquals(s, parsed);
            assertFalse(parsed.legacy);
        }
        assertNull(everything().toLines(20, SettingsTest::fontWidth), "too narrow to hold the payload");
    }

    @Test
    void damagedPayloadStillMakesAMirror() {
        MirrorSettings s = MirrorSettings.parse("[Mirror]", "~zz", "", "").orElseThrow();
        assertEquals(new MirrorSettings(), s);
        assertTrue(MirrorSettings.parse("Hello", "~AQA", "", "").isEmpty());
    }

    @Test
    void legacySignsStillParse() {
        MirrorSettings s = MirrorSettings.parse("[mirror]", "2,1r", "15,10", "anything").orElseThrow();
        assertTrue(s.legacy);
        near(2, s.width);
        assertEquals(MirrorSpec.HAlign.RIGHT, s.hAlign);
        near(15, s.yaw);
        near(-10, s.pitch); // bottom hinge: "away from the wall" is downward-facing in the new convention
        assertEquals(100, s.opacity);
        assertEquals(0xFFFFFF, s.tint);
        assertEquals(MirrorSettings.Frame.NONE, s.frame);
    }

    @Test
    void legacyLeftHingeFlipsYawToKeepTheSameShape() {
        MirrorSettings s = MirrorSettings.fromLegacy(MirrorSpec.parse("[Mirror]", "2,1l", "30").orElseThrow());
        near(-30, s.yaw);
        MirrorGeometry g = MirrorGeometry.build(0, 64, 0, new V3(0, 0, -1), MirrorGeometry.Mount.WALL, s);
        double planeZ = 1 - MirrorGeometry.WALL_OFFSET;
        for (V3 c : g.corners()) assertTrue(c.z() <= planeZ + 1e-9, "swung into the wall");
        near(planeZ - 2 * Math.sin(Math.toRadians(30)), g.corners()[1].z()); // free (right) edge comes out
    }

    @Test
    void offsetMovesTheMirrorInSignAxes() {
        MirrorSettings s = new MirrorSettings();
        MirrorGeometry base = MirrorGeometry.build(0, 64, 0, new V3(0, 0, -1), MirrorGeometry.Mount.WALL, s);
        s.offsetX = 1; s.offsetY = 0.5; s.offsetZ = 0.25;
        MirrorGeometry moved = MirrorGeometry.build(0, 64, 0, new V3(0, 0, -1), MirrorGeometry.Mount.WALL, s);
        // Reader faces south (+Z): right is west (-X), out of the sign is north (-Z).
        near(base.center().add(new V3(-1, 0.5, -0.25)), moved.center());
        near(base.normal(), moved.normal());
    }

    @Test
    void rollTurnsAboutTheAnchorKeepingTheNormal() {
        MirrorSettings s = new MirrorSettings();
        s.width = 2; s.height = 1; s.hAlign = MirrorSpec.HAlign.LEFT; s.roll = 90;
        MirrorGeometry g = MirrorGeometry.build(0, 64, 0, new V3(0, 0, -1), MirrorGeometry.Mount.WALL, s);
        near(new V3(0, 0, -1), g.normal());
        // Anchor is the bottom-left corner; counter-clockwise by 90 the bottom edge points up.
        near(new V3(0, 1, 0), g.right());
        near(2, g.corners()[1].sub(g.corners()[0]).length());
        V3 faceNormal = g.corners()[1].sub(g.corners()[0]).cross(g.corners()[2].sub(g.corners()[0])).normalize();
        near(g.normal(), faceNormal);
    }

    @Test
    void positiveYawFacesTheReadersRight() {
        MirrorSettings s = new MirrorSettings();
        s.yaw = 30;
        MirrorGeometry g = MirrorGeometry.build(0, 64, 0, new V3(0, 0, -1), MirrorGeometry.Mount.WALL, s);
        // Reader's right is -X.
        assertTrue(g.normal().x() < -0.4);
        s.yaw = 0; s.pitch = 30;
        g = MirrorGeometry.build(0, 64, 0, new V3(0, 0, -1), MirrorGeometry.Mount.WALL, s);
        assertTrue(g.normal().y() > 0.4);
    }

    @Test
    void frameSurroundsTheGlassInBlockSizedPieces() {
        MirrorSettings s = new MirrorSettings();
        s.width = 2.5; s.height = 1; s.roll = 20; s.yaw = 10;
        MirrorGeometry g = MirrorGeometry.build(3, 64, 7, new V3(1, 0, 0), MirrorGeometry.Mount.WALL, s);
        double t = 2 / 16.0;
        List<MirrorGeometry.Box> boxes = g.frameBoxes(t);
        double area = 0;
        for (MirrorGeometry.Box b : boxes) {
            assertTrue(b.ex().length() <= 1 + 1e-9 && b.ey().length() <= 1 + 1e-9, "pieces at most a block");
            near(1, b.ex().normalize().cross(b.ey().normalize()).dot(b.ez().normalize())); // right-handed
            near(b.ez().length(), MirrorGeometry.FRAME_FRONT + MirrorGeometry.FRAME_BACK);
            area += b.ex().length() * b.ey().length();
        }
        // Border area = outer rectangle - glass.
        near((2.5 + 2 * t) * (1 + 2 * t) - 2.5, area);
        assertTrue(g.frameBoxes(0).isEmpty());
    }
}
