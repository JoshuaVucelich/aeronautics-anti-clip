package com.vucelich.sablesafelanding.sim;

/**
 * Speeds written back onto a body. Rescue always stores zero. A disassembled deck
 * clears only a non-finite or absurd speed, so a normal fall still falls.
 */
public final class VelocityClamp {
    /** Blocks per second. A Create elevator at the speeds this mod rides is far below this. */
    public static final double ABSURD = 80.0;

    private VelocityClamp() {
    }

    public static Vec3 afterRescue(Vec3 velocity) {
        return Vec3.ZERO;
    }

    public static Vec3 calm(Vec3 velocity) {
        if (velocity == null || !velocity.finite() || velocity.lengthSquared() > ABSURD * ABSURD) {
            return Vec3.ZERO;
        }
        return velocity;
    }
}
