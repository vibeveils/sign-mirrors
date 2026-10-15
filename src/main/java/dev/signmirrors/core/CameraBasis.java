package dev.signmirrors.core;

/**
 * Minecraft's camera basis for a yaw/pitch, as Camera.setRotation builds it:
 * rotation = rotationYXZ(PI - yaw, -pitch, 0); forward = (0,0,-1), up = (0,1,0), left = (-1,0,0),
 * each rotated by it. Returns {forward, right, up}.
 */
public final class CameraBasis {
    private CameraBasis() {}

    public static V3[] of(float yawDeg, float pitchDeg) {
        double ay = Math.PI - Math.toRadians(yawDeg);
        double ax = -Math.toRadians(pitchDeg);
        V3[] in = {new V3(0, 0, -1), new V3(0, 1, 0), new V3(-1, 0, 0)};
        V3[] out = new V3[3];
        for (int i = 0; i < 3; i++) {
            V3 v = in[i];
            double y1 = v.y() * Math.cos(ax) - v.z() * Math.sin(ax);
            double z1 = v.y() * Math.sin(ax) + v.z() * Math.cos(ax);
            double x2 = v.x() * Math.cos(ay) + z1 * Math.sin(ay);
            double z2 = -v.x() * Math.sin(ay) + z1 * Math.cos(ay);
            out[i] = new V3(x2, y1, z2);
        }
        return new V3[] {out[0], out[2].mul(-1), out[1]};
    }
}
