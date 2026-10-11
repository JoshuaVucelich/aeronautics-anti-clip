package com.vucelich.sablesafelanding.sim;

/**
 * Immutable block-space vector. Units are Minecraft blocks and blocks per second.
 */
public record Vec3(double x, double y, double z) {
    public static final Vec3 ZERO = new Vec3(0.0, 0.0, 0.0);

    public Vec3 add(Vec3 other) {
        return new Vec3(x + other.x, y + other.y, z + other.z);
    }

    public Vec3 sub(Vec3 other) {
        return new Vec3(x - other.x, y - other.y, z - other.z);
    }

    public Vec3 mul(double scale) {
        return new Vec3(x * scale, y * scale, z * scale);
    }

    public double dot(Vec3 other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    public double length() {
        return Math.sqrt(lengthSquared());
    }

    public boolean finite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    /**
     * Rotates this vector around a unit axis by {@code radians} (Rodrigues).
     */
    public Vec3 rotate(Vec3 axis, double radians) {
        double len = axis.length();
        if (len < 1.0e-12 || Math.abs(radians) < 1.0e-12) {
            return this;
        }
        Vec3 k = axis.mul(1.0 / len);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        Vec3 crossed = new Vec3(
                k.y * z - k.z * y,
                k.z * x - k.x * z,
                k.x * y - k.y * x
        );
        return mul(cos).add(crossed.mul(sin)).add(k.mul(dot(k) * (1.0 - cos)));
    }
}
