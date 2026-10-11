package com.vucelich.sablesafelanding.sim;

/**
 * Places a resting ship on a contraption by a stored offset in the deck frame.
 * The offset is captured once, while the ship is on the deck. Later ticks rebuild the
 * world position from the deck's current pivot and absolute angle. A kinematic floor
 * does not pull a car downward, so this pose is the coupling for both directions.
 * A correction that is far in the horizontal plane is refused so a bad angle cannot fling the ship.
 */
public final class DeckFollow {
    /** Horizontal distance, in blocks, past which the ship has left the platform. */
    public static final double MAX_HORIZONTAL = 8.0;
    /** Vertical distance, in blocks, past which a correction is not a ride. */
    public static final double MAX_VERTICAL = 64.0;

    private DeckFollow() {
    }

    /** Ship position in the deck frame at {@code angleRadians}. */
    public static Vec3 localOffset(Vec3 ship, Vec3 pivot, Vec3 axis, double angleRadians) {
        return ship.sub(pivot).rotate(axis, -angleRadians);
    }

    /** World position of a stored deck-frame offset. */
    public static Vec3 worldTarget(Vec3 local, Vec3 pivot, Vec3 axis, double angleRadians) {
        return local.rotate(axis, angleRadians).add(pivot);
    }

    /**
     * A ride correction. Vertical travel can be the whole elevator move, because the solver
     * may have held the ship on a floor the deck already left. A large horizontal jump is a fling.
     */
    public static boolean acceptable(Vec3 from, Vec3 target) {
        if (from == null || target == null || !from.finite() || !target.finite()) {
            return false;
        }
        double dx = from.x() - target.x();
        double dy = from.y() - target.y();
        double dz = from.z() - target.z();
        return dx * dx + dz * dz <= MAX_HORIZONTAL * MAX_HORIZONTAL && Math.abs(dy) <= MAX_VERTICAL;
    }
}
