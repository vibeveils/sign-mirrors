package dev.signmirrors.core;

/**
 * The six cube-map faces, with the Minecraft camera yaw/pitch used to capture each one and the
 * resulting camera basis (forward, screen-right, screen-up). The shader's face tables in
 * {@code mirror.fsh} must match {@link #forward}, {@link #right} and {@link #up} exactly; the unit
 * test checks the basis against Minecraft's Camera.setRotation maths.
 *
 * Atlas layout (3 x 2 tiles): index = row * 3 + col, i.e.
 * <pre>
 *   row 1:  -Y  +Z  -Z
 *   row 0:  +X  -X  +Y
 * </pre>
 */
public enum CubeFace {
    POS_X(-90f, 0f, new V3(1, 0, 0), new V3(0, 0, 1), new V3(0, 1, 0)),
    NEG_X(90f, 0f, new V3(-1, 0, 0), new V3(0, 0, -1), new V3(0, 1, 0)),
    POS_Y(0f, -90f, new V3(0, 1, 0), new V3(-1, 0, 0), new V3(0, 0, -1)),
    NEG_Y(0f, 90f, new V3(0, -1, 0), new V3(-1, 0, 0), new V3(0, 0, 1)),
    POS_Z(0f, 0f, new V3(0, 0, 1), new V3(-1, 0, 0), new V3(0, 1, 0)),
    NEG_Z(180f, 0f, new V3(0, 0, -1), new V3(1, 0, 0), new V3(0, 1, 0));

    public final float yaw;
    public final float pitch;
    public final V3 forward;
    public final V3 right;
    public final V3 up;

    CubeFace(float yaw, float pitch, V3 forward, V3 right, V3 up) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.forward = forward;
        this.right = right;
        this.up = up;
    }

    public int atlasCol() { return ordinal() % 3; }
    public int atlasRow() { return ordinal() / 3; }

    /**
     * Whether this face can contain any direction from the probe that a front-facing reflection
     * will sample. The shader projects directions onto the mirror's front half-space, so any face
     * whose frustum touches that half-space is needed; at most five faces ever are.
     */
    public boolean neededFor(V3 mirrorNormal) {
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                V3 corner = forward.add(right.mul(sx)).add(up.mul(sy));
                if (corner.dot(mirrorNormal) > -1e-3) return true;
            }
        }
        return forward.dot(mirrorNormal) > -1e-3;
    }
}
