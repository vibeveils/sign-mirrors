package dev.signmirrors.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlanarMathTest {

    /** JOML Matrix4f.setPerspective(fovy, aspect, zNear, zFar, zZeroToOne), column-major. */
    private static double[] perspective(double fovyDeg, double aspect, double zNear, double zFar, boolean zZeroToOne) {
        double h = Math.tan(Math.toRadians(fovyDeg) * 0.5);
        double[] m = new double[16];
        m[0] = 1.0 / (h * aspect);
        m[5] = 1.0 / h;
        m[10] = (zZeroToOne ? zFar : zFar + zNear) / (zNear - zFar);
        m[14] = (zZeroToOne ? zFar : zFar + zFar) * zNear / (zNear - zFar);
        m[11] = -1.0;
        return m;
    }

    /** Standard OpenGL projection, near 0.05, far 512. */
    private static double[] gl() {
        return perspective(90, 1, 0.05, 512, false);
    }

    /** 26.3 with clip control: reverse-Z, depth [0, 1] (near and far passed swapped). */
    private static double[] reverse() {
        return perspective(90, 1, 512, 0.05, true);
    }

    @Test
    void detectsDepthConvention() {
        assertFalse(PlanarMath.isReverseZ(gl()));
        assertTrue(PlanarMath.isReverseZ(reverse()));
    }

    @Test
    void reflectsEyeThroughPlane() {
        V3 e = PlanarMath.reflectPoint(new V3(1, 2, -3), new V3(0, 0, 1), new V3(0, 0, -1));
        assertEquals(1, e.x(), 1e-12);
        assertEquals(2, e.y(), 1e-12);
        assertEquals(5, e.z(), 1e-12);
    }

    private static void checkClip(double[] proj, double[] plane, boolean reverse) {
        double[] clipped = PlanarMath.obliqueClip(proj, plane);
        assertNotNull(clipped);
        // Screen-space rows untouched.
        for (int c = 0; c < 4; c++) {
            assertEquals(proj[c * 4], clipped[c * 4], 1e-12);
            assertEquals(proj[c * 4 + 1], clipped[c * 4 + 1], 1e-12);
            assertEquals(proj[c * 4 + 3], clipped[c * 4 + 3], 1e-12);
        }
        double nearNdc = reverse ? 1 : -1, farNdc = reverse ? 0 : 1;
        // Points along a few view rays: kept side inside the depth range, removed side outside it,
        // and depth monotonic along each ray.
        double[][] dirs = {{0, 0, -1}, {0.6, 0.3, -1}, {-0.9, -0.9, -1}, {0.95, -0.2, -1}};
        for (double[] d : dirs) {
            double prev = Double.NaN;
            for (double t = 0.1; t < 400; t *= 1.3) {
                double[] v = {d[0] * t, d[1] * t, d[2] * t, 1};
                double side = PlanarMath.dot(plane, v);
                double z = PlanarMath.ndcDepth(clipped, v);
                if (side > 1e-6) {
                    assertTrue(Math.min(nearNdc, farNdc) - 1e-6 <= z && z <= Math.max(nearNdc, farNdc) + 1e-6,
                            "kept point outside depth range: t=" + t + " z=" + z);
                } else if (side < -1e-6) {
                    assertTrue(reverse ? z > 1 : z < -1, "point behind the mirror plane not clipped: z=" + z);
                }
                if (!Double.isNaN(prev)) {
                    if (reverse) assertTrue(z <= prev + 1e-9, "depth must decrease with distance (reverse-Z)");
                    else assertTrue(z >= prev - 1e-9, "depth must increase with distance");
                }
                prev = z;
            }
        }
    }

    @Test
    void obliqueClipFacingPlaneBothConventions() {
        // Keep z <= -3 (mirror 3 blocks ahead, facing the camera from the far side): -z - 3 >= 0
        double[] plane = {0, 0, -1, -3};
        checkClip(gl(), plane, false);
        checkClip(reverse(), plane, true);
    }

    @Test
    void obliqueClipTiltedPlaneBothConventions() {
        double nx = 0.4, nz = -1;
        double len = Math.sqrt(nx * nx + nz * nz);
        double[] plane = {nx / len, 0.1, nz / len, -2.5};
        checkClip(gl(), plane, false);
        checkClip(reverse(), plane, true);
    }

    @Test
    void planeBehindCameraIsRejected() {
        // Keep only z >= 2 (behind the camera): nothing visible on that side.
        assertNull(PlanarMath.obliqueClip(gl(), new double[] {0, 0, 1, -2}));
        assertNull(PlanarMath.obliqueClip(reverse(), new double[] {0, 0, 1, -2}));
    }

    @Test
    void aimCoversAllCorners() {
        MirrorGeometry g = MirrorGeometry.build(0, 64, 0, new V3(0, 0, -1), MirrorGeometry.Mount.WALL,
                MirrorSpec.parse("[Mirror]", "3,2", "20").orElseThrow(), 0.5);
        V3 eye = new V3(4, 65.6, -5);
        V3 virtualEye = PlanarMath.reflectPoint(eye, g.center(), g.normal());
        PlanarMath.Aim aim = PlanarMath.aim(virtualEye, g);
        V3[] b = CameraBasis.of(aim.yaw(), aim.pitch());
        double halfTan = Math.tan(Math.toRadians(aim.fovDegrees()) / 2);
        for (V3 c : g.corners()) {
            V3 v = c.sub(virtualEye);
            double z = v.dot(b[0]);
            assertTrue(z > 0, "corner in front of the virtual camera");
            assertTrue(Math.abs(v.dot(b[1])) / z <= halfTan + 1e-9);
            assertTrue(Math.abs(v.dot(b[2])) / z <= halfTan + 1e-9);
        }
        // The virtual eye is on the far side of the mirror plane.
        assertTrue(virtualEye.sub(g.center()).dot(g.normal()) < 0);
    }
}
