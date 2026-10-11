package com.vucelich.sablesafelanding.sim;

/**
 * One Sable sub-level as the harness and the anti-clip step see it.
 * {@code position} is the logical pose origin. {@code bounds} is the world AABB.
 */
public record BodyState(String id, Vec3 position, Vec3 velocity, Aabb bounds, Rot orientation, boolean rescued) {
    public BodyState {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("body id required");
        }
    }

    public BodyState(String id, Vec3 position, Vec3 velocity, Aabb bounds, boolean rescued) {
        this(id, position, velocity, bounds, Rot.IDENTITY, rescued);
    }

    public BodyState moved(Vec3 delta, Vec3 newVelocity) {
        return new BodyState(id, position.add(delta), newVelocity, bounds.translate(delta), orientation, rescued);
    }

    public BodyState withRescued(boolean value) {
        return new BodyState(id, position, velocity, bounds, orientation, value);
    }

    public boolean finite() {
        return position.finite() && velocity.finite() && bounds.finite() && orientation.finite();
    }
}
