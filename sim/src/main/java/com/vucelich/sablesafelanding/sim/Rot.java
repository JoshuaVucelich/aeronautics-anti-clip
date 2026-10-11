package com.vucelich.sablesafelanding.sim;

/**
 * Unit quaternion. Identity is no rotation. Components are x, y, z, w.
 */
public record Rot(double x, double y, double z, double w) {
    public static final Rot IDENTITY = new Rot(0.0, 0.0, 0.0, 1.0);

    public boolean finite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Double.isFinite(w);
    }
}
