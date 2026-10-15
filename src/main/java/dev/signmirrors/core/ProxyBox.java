package dev.signmirrors.core;

import java.util.function.BiFunction;

/**
 * Axis-aligned proxy volume used for parallax correction. Built by casting rays from the probe
 * along the six world axes and stopping at the first solid block, so a mirror in a room gets a
 * box roughly the shape of the room. Always contains the mirror quad.
 */
public record ProxyBox(V3 min, V3 max) {

    /**
     * @param raycast (from, to) -> distance to first hit, or the full length if nothing was hit
     */
    public static ProxyBox build(MirrorGeometry g, double maxDistance, BiFunction<V3, V3, Double> raycast) {
        V3 p = g.probe();
        double[] neg = new double[3], pos = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            V3 dir = axis == 0 ? new V3(1, 0, 0) : axis == 1 ? new V3(0, 1, 0) : new V3(0, 0, 1);
            pos[axis] = Math.max(0.25, raycast.apply(p, p.add(dir.mul(maxDistance))));
            neg[axis] = Math.max(0.25, raycast.apply(p, p.sub(dir.mul(maxDistance))));
        }
        V3 min = new V3(p.x() - neg[0], p.y() - neg[1], p.z() - neg[2]);
        V3 max = new V3(p.x() + pos[0], p.y() + pos[1], p.z() + pos[2]);
        for (V3 c : g.corners()) {
            min = min.min(c.sub(new V3(0.01, 0.01, 0.01)));
            max = max.max(c.add(new V3(0.01, 0.01, 0.01)));
        }
        return new ProxyBox(min, max);
    }
}
