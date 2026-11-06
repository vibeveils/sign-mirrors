package dev.signmirrors.core;

/** View-dependent maths for culling and level of detail. Pure Java so it can be unit tested. */
public final class MirrorView {
    private MirrorView() {}

    /**
     * Distance along the ray (origin + t * dir, dir need not be unit) at which it hits the quad,
     * or -1 if it misses. Quad corners are in order around the edge (either winding); both sides count.
     */
    public static double rayQuad(V3 origin, V3 dir, V3[] quad) {
        double t = rayTriangle(origin, dir, quad[0], quad[1], quad[2]);
        if (t >= 0) return t;
        return rayTriangle(origin, dir, quad[0], quad[2], quad[3]);
    }

    /** Möller–Trumbore, two-sided. Returns t or -1. */
    static double rayTriangle(V3 o, V3 d, V3 a, V3 b, V3 c) {
        V3 e1 = b.sub(a), e2 = c.sub(a);
        V3 p = d.cross(e2);
        double det = e1.dot(p);
        if (Math.abs(det) < 1e-12) return -1;
        double inv = 1.0 / det;
        V3 s = o.sub(a);
        double u = s.dot(p) * inv;
        if (u < 0 || u > 1) return -1;
        V3 q = s.cross(e1);
        double v = d.dot(q) * inv;
        if (v < 0 || u + v > 1) return -1;
        double t = e2.dot(q) * inv;
        return t >= 0 ? t : -1;
    }

    /** Area of a simple polygon given as x0,y0,x1,y1,... (shoelace). */
    public static double polygonArea(double[] xy) {
        double sum = 0;
        int n = xy.length / 2;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            sum += xy[2 * i] * xy[2 * j + 1] - xy[2 * j] * xy[2 * i + 1];
        }
        return Math.abs(sum) * 0.5;
    }

    /** Power-of-two texture size giving about {@code scale} texels per pixel across {@code pixels}, clamped. */
    public static int resolutionForPixels(double pixels, double scale, int min, int max) {
        double px = Math.max(0, pixels) * scale;
        int res = Integer.highestOneBit(Math.max(1, (int) Math.ceil(px)));
        if (res < px) res <<= 1;
        return Math.max(min, Math.min(max, res));
    }
}
