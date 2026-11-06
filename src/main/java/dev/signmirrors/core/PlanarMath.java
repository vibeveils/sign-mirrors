package dev.signmirrors.core;

/**
 * Maths for exact planar reflections. Pure Java so it can be unit tested; matrices are 16 doubles in
 * column-major order with element (column c, row r) at index {@code c * 4 + r} — the same layout as
 * JOML's {@code mCR} fields, so render code converts with {@code Matrix4f.get(float[])}.
 *
 * A planar mirror seen from eye E shows the real world as seen from E reflected through the mirror
 * plane (E'), looking through the mirror's outline. So: render from E' aimed at the mirror, clip
 * away everything on the far side of the mirror plane (the wall and the room behind it) with an
 * oblique near plane, and look each mirror pixel up by projecting it into that render.
 */
public final class PlanarMath {
    private PlanarMath() {}

    /** Maximum field of view for the virtual camera; beyond this the mirror edges are clamped. */
    public static final double MAX_FOV_DEGREES = 160;

    /** Point reflected through the plane with unit normal {@code n} passing through {@code onPlane}. */
    public static V3 reflectPoint(V3 p, V3 onPlane, V3 n) {
        return p.sub(n.mul(2 * p.sub(onPlane).dot(n)));
    }

    /** Camera pose (Minecraft yaw/pitch in degrees) and square field of view covering the mirror. */
    public record Aim(float yaw, float pitch, double fovDegrees) {}

    /** Aims a camera at {@code eye} toward the mirror's centre with just enough FOV to see all corners. */
    public static Aim aim(V3 eye, MirrorGeometry g) {
        return aim(eye, g.corners());
    }

    /**
     * Aims a camera at {@code eye} toward the middle of {@code points} with just enough square FOV to
     * see all of them. Used with the on-screen part of the mirror, so the render's texels go where
     * they're seen.
     */
    public static Aim aim(V3 eye, V3[] points) {
        V3 sum = V3.ZERO;
        for (V3 p : points) sum = sum.add(p.sub(eye).normalize());
        V3 dir = sum.normalize();
        double pitch = Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, dir.y()))));
        pitch = Math.max(-89.0, Math.min(89.0, pitch));
        double yaw = Math.toDegrees(Math.atan2(-dir.x(), dir.z()));
        V3[] basis = CameraBasis.of((float) yaw, (float) pitch);
        V3 f = basis[0], r = basis[1], u = basis[2];
        double maxTan = 0;
        for (V3 c : points) {
            V3 v = c.sub(eye);
            double z = v.dot(f);
            if (z <= 1e-3) return new Aim((float) yaw, (float) pitch, MAX_FOV_DEGREES);
            maxTan = Math.max(maxTan, Math.max(Math.abs(v.dot(r)), Math.abs(v.dot(u))) / z);
        }
        double fov = Math.toDegrees(2 * Math.atan(maxTan)) * 1.04 + 1.0; // a little slack for filtering
        return new Aim((float) yaw, (float) pitch, Math.max(10, Math.min(MAX_FOV_DEGREES, fov)));
    }

    /**
     * The part of a mirror quad inside a view frustum, as a convex polygon in world space (empty if
     * none of it is in view). {@code viewProj} is projection x view for camera-relative positions;
     * {@code margin} widens the frustum (0.1 = 10%) so the region still covers the view after a
     * little movement or turning.
     */
    public static V3[] visibleRegion(V3[] quad, double[] viewProj, V3 cam, double margin) {
        java.util.List<V3> poly = new java.util.ArrayList<>(java.util.List.of(quad));
        double k = 1 + margin;
        // Planes as (a, b, c, d) acting on clip coords: a*x + b*y + c*z + d*w >= 0
        double[][] planes = {
                {-1, 0, 0, k}, {1, 0, 0, k}, {0, -1, 0, k}, {0, 1, 0, k}, // |x|, |y| <= k*w
                {0, 0, 0, 1},                                             // in front of the eye
        };
        for (double[] pl : planes) {
            double bias = pl == planes[4] ? -1e-3 : 0; // w >= small epsilon
            java.util.List<V3> out = new java.util.ArrayList<>();
            int n = poly.size();
            for (int i = 0; i < n; i++) {
                V3 a = poly.get(i), b = poly.get((i + 1) % n);
                double da = side(pl, viewProj, a, cam) + bias, db = side(pl, viewProj, b, cam) + bias;
                if (da >= 0) out.add(a);
                if ((da >= 0) != (db >= 0)) {
                    double t = da / (da - db);
                    out.add(a.add(b.sub(a).mul(t)));
                }
            }
            poly = out;
            if (poly.isEmpty()) break;
        }
        return poly.toArray(new V3[0]);
    }

    private static double side(double[] plane, double[] viewProj, V3 p, V3 cam) {
        double[] c = mul(viewProj, new double[] {p.x() - cam.x(), p.y() - cam.y(), p.z() - cam.z(), 1});
        return plane[0] * c[0] + plane[1] * c[1] + plane[2] * c[2] + plane[3] * c[3];
    }

    /**
     * Largest on-screen extent, in pixels, of a world-space polygon (already inside the view).
     */
    public static double screenExtentPixels(V3[] poly, double[] viewProj, V3 cam, int width, int height) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (V3 p : poly) {
            double[] c = mul(viewProj, new double[] {p.x() - cam.x(), p.y() - cam.y(), p.z() - cam.z(), 1});
            if (c[3] <= 1e-6) continue;
            double x = Math.max(-1, Math.min(1, c[0] / c[3])), y = Math.max(-1, Math.min(1, c[1] / c[3]));
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        if (minX > maxX) return 0;
        return Math.max((maxX - minX) * 0.5 * width, (maxY - minY) * 0.5 * height);
    }

    /**
     * True if the projection maps the near plane to NDC depth 1 (reverse-Z, depth range [0, 1]) rather
     * than standard OpenGL (near at -1, far at 1). Detected from where depth reaches 1 along the view axis.
     */
    public static boolean isReverseZ(double[] p) {
        double m22 = p[2 * 4 + 2], m23 = p[2 * 4 + 3], m32 = p[3 * 4 + 2], m33 = p[3 * 4 + 3];
        double denom = m22 - m23;
        if (Math.abs(denom) < 1e-12) return false;
        double d = (m32 - m33) / denom; // view distance where ndc z == 1
        return d > 0 && d < 1.0;
    }

    /**
     * Replaces the projection's depth row so its near plane is {@code planeView} (a plane in view
     * space, a·x + b·y + c·z + d, keeping the side where it's >= 0), with the far plane moved just far
     * enough to keep the original frustum's far corners. Works for standard OpenGL depth and
     * reverse-Z [0, 1]. X, Y and W rows are untouched, so screen positions don't change.
     *
     * @return the new matrix, or null if no part of the view lies on the kept side of the plane
     */
    public static double[] obliqueClip(double[] proj, double[] planeView) {
        boolean reverse = isReverseZ(proj);
        double[] inv = invert(proj);
        if (inv == null) return null;
        double[] w = row(proj, 3);
        double farNdc = reverse ? 0 : 1;

        double lambda = Double.POSITIVE_INFINITY;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                double[] q = mul(inv, new double[] {sx, sy, farNdc, 1});
                if (Math.abs(q[3]) < 1e-12) continue;
                for (int i = 0; i < 4; i++) q[i] /= q[3];
                double cq = dot(planeView, q);
                if (cq <= 1e-9) continue;
                double wq = dot(w, q);
                lambda = Math.min(lambda, (reverse ? wq : 2 * wq) / cq);
            }
        }
        if (Double.isInfinite(lambda) || lambda <= 0) return null;

        double[] out = proj.clone();
        for (int c = 0; c < 4; c++) {
            double z = reverse
                    ? w[c] - lambda * planeView[c]   // near: w - z = λC·v >= 0, far: z >= 0
                    : lambda * planeView[c] - w[c];  // near: z + w = λC·v >= 0, far: z <= w
            out[c * 4 + 2] = z;
        }
        return out;
    }

    /** Normalised device depth of a view-space point (x, y, z, 1). */
    public static double ndcDepth(double[] p, double[] v) {
        double[] clip = mul(p, v);
        return clip[2] / clip[3];
    }

    // ---- small column-major 4x4 helpers ----

    static double[] row(double[] m, int r) {
        return new double[] {m[r], m[4 + r], m[8 + r], m[12 + r]};
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
    }

    public static double[] mul(double[] m, double[] v) {
        double[] o = new double[4];
        for (int r = 0; r < 4; r++) o[r] = m[r] * v[0] + m[4 + r] * v[1] + m[8 + r] * v[2] + m[12 + r] * v[3];
        return o;
    }

    /** General 4x4 inverse (cofactors); null if singular. */
    static double[] invert(double[] m) {
        double[] inv = new double[16];
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
        double det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
        if (Math.abs(det) < 1e-18) return null;
        for (int i = 0; i < 16; i++) inv[i] /= det;
        return inv;
    }
}
