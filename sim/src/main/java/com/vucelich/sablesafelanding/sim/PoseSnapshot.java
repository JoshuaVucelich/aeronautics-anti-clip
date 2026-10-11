package com.vucelich.sablesafelanding.sim;

/**
 * Last pose that was inside the world, clear of the remove band, and not tunneling.
 */
public record PoseSnapshot(Vec3 position, Aabb bounds, Rot orientation, long time) {
    public boolean finite() {
        return position.finite() && bounds.finite() && orientation.finite();
    }
}
