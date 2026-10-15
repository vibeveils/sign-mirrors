package dev.signmirrors.core;

/**
 * View-dependent maths for culling and level of detail. Pure Java so it can be unit tested; the
 * fragment shader does the same per-pixel lookup this does per sample.
 */
public final class MirrorView {
    private MirrorView() {}

    /** Samples per edge of the grid used to find which cube faces the reflection reads from. */
    public static final int FACE_SAMPLES = 7;

    /**
     * Which cube faces the mirror's reflection samples when seen from {@code cam}: the same
     * reflect + parallax-correct + fold as mirror.fsh, evaluated on a grid over the mirror.
     * Returns a bit mask indexed by {@link CubeFace#ordinal()}; 0 if the camera is behind the mirror.
     */
    public static int visibleFaceMask(MirrorGeometry g, ProxyBox box, V3 cam) {
        V3[] c = g.corners();
        V3 n = g.normal();
        V3 eu = c[1].sub(c[0]);
        V3 ev = c[3].sub(c[0]);
        int mask = 0;
        for (int i = 0; i < FACE_SAMPLES; i++) {
            for (int j = 0; j < FACE_SAMPLES; j++) {
                double u = (double) i / (FACE_SAMPLES - 1), v = (double) j / (FACE_SAMPLES - 1);
                V3 p = c[0].add(eu.mul(u)).add(ev.mul(v));
                V3 view = p.sub(cam);
                if (view.dot(n) >= 0) continue; // this point is seen from behind
                V3 dir = sampleDirection(g, box, p, view.normalize());
                mask |= 1 << faceOf(dir).ordinal();
            }
        }
        return mask;
    }

    /** Direction from the probe used to shade point {@code p} seen along {@code view} (unit). */
    public static V3 sampleDirection(MirrorGeometry g, ProxyBox box, V3 p, V3 view) {
        V3 n = g.normal();
        V3 r = view.sub(n.mul(2 * view.dot(n)));
        double t = exitDistance(box, p, r);
        V3 dir = p.add(r.mul(Math.max(t, 0))).sub(g.probe());
        double dn = dir.dot(n);
        if (dn < 0) dir = dir.sub(n.mul(dn));
        if (dir.dot(dir) < 1e-8) dir = r;
        return dir;
    }

    /** Distance along {@code r} from {@code p} (inside the box) to where the ray leaves it. */
    public static double exitDistance(ProxyBox box, V3 p, V3 r) {
        double t = Double.POSITIVE_INFINITY;
        for (int a = 0; a < 3; a++) {
            double ra = r.get(a);
            if (Math.abs(ra) < 1e-9) continue;
            double t1 = (box.max().get(a) - p.get(a)) / ra;
            double t2 = (box.min().get(a) - p.get(a)) / ra;
            t = Math.min(t, Math.max(t1, t2));
        }
        return Double.isInfinite(t) ? 0 : t;
    }

    /** Same face selection as mirror.fsh (ties resolved identically). */
    public static CubeFace faceOf(V3 d) {
        double ax = Math.abs(d.x()), ay = Math.abs(d.y()), az = Math.abs(d.z());
        if (ax >= ay && ax >= az) return d.x() > 0 ? CubeFace.POS_X : CubeFace.NEG_X;
        if (ay >= az) return d.y() > 0 ? CubeFace.POS_Y : CubeFace.NEG_Y;
        return d.z() > 0 ? CubeFace.POS_Z : CubeFace.NEG_Z;
    }

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

    /**
     * Probe face resolution for a mirror that covers {@code coverage} (0..1) of a screen
     * {@code screenHeight} pixels tall: roughly one face texel per screen pixel of mirror height,
     * scaled, rounded up to a power of two and clamped.
     */
    public static int chooseResolution(double coverage, int screenHeight, double scale, int min, int max) {
        return resolutionForPixels(Math.sqrt(Math.max(0, Math.min(1, coverage))) * screenHeight, scale, min, max);
    }

    /** Power-of-two texture size giving about {@code scale} texels per pixel across {@code pixels}, clamped. */
    public static int resolutionForPixels(double pixels, double scale, int min, int max) {
        double px = Math.max(0, pixels) * scale;
        int res = Integer.highestOneBit(Math.max(1, (int) Math.ceil(px)));
        if (res < px) res <<= 1;
        return Math.max(min, Math.min(max, res));
    }
}
