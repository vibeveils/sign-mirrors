package dev.signmirrors.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MirrorViewTest {

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
    void resolutionForPixelsRoundsUpAndClamps() {
        assertEquals(64, MirrorView.resolutionForPixels(3, 1.0, 64, 512));
        assertEquals(512, MirrorView.resolutionForPixels(270, 1.0, 64, 1024));
        assertEquals(256, MirrorView.resolutionForPixels(270, 0.5, 64, 1024));
        assertEquals(1024, MirrorView.resolutionForPixels(5000, 1.0, 64, 1024));
        assertEquals(1, Integer.bitCount(MirrorView.resolutionForPixels(777, 1.0, 32, 2048)), "power of two");
    }
}
