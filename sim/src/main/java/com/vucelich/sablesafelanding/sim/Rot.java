package com.vucelich.sablesafelanding.sim;

/**
 * Unit quaternion. Identity is no rotation. Components are x, y, z, w.
 */
public record Rot(double x, double y, double z, double w) {
    public static final Rot IDENTITY = new Rot(0.0, 0.0, 0.0, 1.0);

    public boolean finite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Double.isFinite(w);
    }

    public Rot turned(Vec3 axis, double radians) {
        double len = axis.length();
        if (len < 1.0e-12 || Math.abs(radians) < 1.0e-12) {
            return this;
        }
        Vec3 k = axis.mul(1.0 / len);
        double half = radians * 0.5;
        double s = Math.sin(half);
        return new Rot(k.x() * s, k.y() * s, k.z() * s, Math.cos(half)).mul(this);
    }

    public Rot mul(Rot other) {
        return new Rot(
                w * other.x + x * other.w + y * other.z - z * other.y,
                w * other.y - x * other.z + y * other.w + z * other.x,
                w * other.z + x * other.y - y * other.x + z * other.w,
                w * other.w - x * other.x - y * other.y - z * other.z
        );
    }
}
