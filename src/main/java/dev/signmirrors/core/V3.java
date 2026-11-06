package dev.signmirrors.core;

/** Tiny immutable double vector so the core maths has no Minecraft dependency (and is unit-testable). */
public record V3(double x, double y, double z) {
    public static final V3 ZERO = new V3(0, 0, 0);
    public static final V3 UP = new V3(0, 1, 0);

    public V3 add(V3 o) { return new V3(x + o.x, y + o.y, z + o.z); }
    public V3 sub(V3 o) { return new V3(x - o.x, y - o.y, z - o.z); }
    public V3 mul(double s) { return new V3(x * s, y * s, z * s); }
    public double dot(V3 o) { return x * o.x + y * o.y + z * o.z; }
    public V3 cross(V3 o) { return new V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x); }
    public double length() { return Math.sqrt(dot(this)); }
    public V3 normalize() { double l = length(); return l < 1e-12 ? this : mul(1.0 / l); }
    public V3 min(V3 o) { return new V3(Math.min(x, o.x), Math.min(y, o.y), Math.min(z, o.z)); }
    public V3 max(V3 o) { return new V3(Math.max(x, o.x), Math.max(y, o.y), Math.max(z, o.z)); }
    public double get(int axis) { return axis == 0 ? x : axis == 1 ? y : z; }
}
