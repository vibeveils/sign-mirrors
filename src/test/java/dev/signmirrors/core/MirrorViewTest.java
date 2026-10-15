package dev.signmirrors.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MirrorViewTest {

    /** 2x2 wall mirror on the z=1 wall facing north (-Z), in a 9x5x9 room. */
    private static MirrorGeometry wallMirror() {
        return MirrorGeometry.build(0, 64, 0, new V3(0, 0, -1), MirrorGeometry.Mount.WALL,
                MirrorSpec.parse("[Mirror]", "2,2c", "").orElseThrow(), 0.5);
    }

    private static ProxyBox room(MirrorGeometry g) {
        return ProxyBox.build(g, 32, (from, to) -> {
            // walls 4 blocks away in every direction except the mirror's own wall
            V3 d = to.sub(from).normalize();
            return d.z() > 0.5 ? (1 - MirrorGeometry.WALL_OFFSET) - from.z() + 0.0 : 4.0;
        });
    }

    @Test
    void lookingStraightAtMirrorSeesTheFaceBehindYou() {
        MirrorGeometry g = wallMirror();
        ProxyBox box = room(g);
        V3 c = g.center();
        V3 cam = c.add(new V3(0, 0, -3)); // 3 blocks in front, level with the centre
        int mask = MirrorView.visibleFaceMask(g, box, cam);
        // Reflection looks back out of the mirror, i.e. toward -Z: the NEG_Z face must be used.
        assertTrue((mask & (1 << CubeFace.NEG_Z.ordinal())) != 0);
        // The face pointing into the wall is never used.
        assertEquals(0, mask & (1 << CubeFace.POS_Z.ordinal()));
    }

    @Test
    void grazingViewUsesSideFaces() {
        MirrorGeometry g = wallMirror();
        ProxyBox box = room(g);
        V3 cam = g.center().add(new V3(3.5, 0, -0.6)); // far off to +X, close to the wall
        int mask = MirrorView.visibleFaceMask(g, box, cam);
        // Reflected rays head off toward -X.
        assertTrue((mask & (1 << CubeFace.NEG_X.ordinal())) != 0);
    }

    @Test
    void behindMirrorSeesNothing() {
        MirrorGeometry g = wallMirror();
        assertEquals(0, MirrorView.visibleFaceMask(g, room(g), g.center().add(new V3(0, 0, 3))));
    }

    @Test
    void faceSelectionMatchesShaderTieBreaks() {
        assertEquals(CubeFace.POS_X, MirrorView.faceOf(new V3(1, 1, 1)));
        assertEquals(CubeFace.NEG_Y, MirrorView.faceOf(new V3(0.2, -1, 1)));
        assertEquals(CubeFace.NEG_Z, MirrorView.faceOf(new V3(0.1, 0.2, -3)));
    }

    @Test
    void rayQuadHitsAndMisses() {
        V3[] quad = {new V3(0, 0, 5), new V3(1, 0, 5), new V3(1, 1, 5), new V3(0, 1, 5)};
        assertEquals(5, MirrorView.rayQuad(new V3(0.5, 0.5, 0), new V3(0, 0, 1), quad), 1e-9);
        assertEquals(-1, MirrorView.rayQuad(new V3(2, 0.5, 0), new V3(0, 0, 1), quad), 1e-9);
        assertEquals(-1, MirrorView.rayQuad(new V3(0.5, 0.5, 0), new V3(0, 0, -1), quad), 1e-9);
        // Back side counts too (mirrors are opaque both ways for occlusion).
        assertEquals(5, MirrorView.rayQuad(new V3(0.5, 0.5, 10), new V3(0, 0, -1), quad), 1e-9);
    }

    @Test
    void polygonAreaOfFullScreen() {
        assertEquals(4, MirrorView.polygonArea(new double[] {-1, -1, 1, -1, 1, 1, -1, 1}), 1e-12);
    }

    @Test
    void resolutionScalesWithCoverageAndClamps() {
        assertEquals(64, MirrorView.chooseResolution(0.0001, 1080, 1.0, 64, 512));
        assertEquals(512, MirrorView.chooseResolution(1.0, 1080, 1.0, 64, 512));
        // A mirror covering 1/16 of the screen is ~270 px tall -> 512 (next power of two).
        assertEquals(512, MirrorView.chooseResolution(1.0 / 16, 1080, 1.0, 64, 1024));
        assertEquals(256, MirrorView.chooseResolution(1.0 / 16, 1080, 0.5, 64, 1024));
        int r = MirrorView.chooseResolution(0.3, 1440, 1.0, 32, 2048);
        assertEquals(Integer.bitCount(r), 1, "power of two");
    }
}
