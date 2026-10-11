package com.vucelich.sablesafelanding.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A car on a ship on an elevator is its own sub-level. The ship is the lock.
 * The car is not on the elevator's blocks, so it is not locked. Moving the ship
 * without the car leaves the grabber holding the old pose.
 */
public final class NestedCarry {
    /** Blocks around the parent hull that still count as "on the ship". */
    public static final double PAD = 1.0;
    public static final int MAX_PASSENGERS = 8;

    private NestedCarry() {
    }

    /**
     * The child sits in or on the parent. The center has to be inside the parent's
     * footprint, so a ship touching the hull from the side is not dragged along.
     * A box below the hull is not a passenger either.
     */
    public static boolean passenger(Aabb parent, Aabb child) {
        if (parent == null || child == null || !parent.finite() || !child.finite()) {
            return false;
        }
        if (child.minY() < parent.minY() - PAD) {
            return false;
        }
        if (child.minY() > parent.maxY() + PAD) {
            return false;
        }
        Vec3 center = child.center();
        return center.x() >= parent.minX() && center.x() < parent.maxX()
                && center.z() >= parent.minZ() && center.z() < parent.maxZ();
    }

    /** Child position after the parent's pivot motion. The bearing step rotates around the old pivot. */
    public static Vec3 place(Vec3 child, Vec3 oldPivot, Vec3 newPivot, Vec3 axis, double angleStep) {
        if (child == null || oldPivot == null || newPivot == null || axis == null) {
            return child;
        }
        return child.sub(oldPivot).rotate(axis, angleStep).add(newPivot);
    }

    public static List<String> passengers(Aabb parent, Map<String, Aabb> others, String parentId, Set<String> locked) {
        List<String> ids = new ArrayList<>();
        if (parent == null || others == null) {
            return ids;
        }
        for (Map.Entry<String, Aabb> entry : others.entrySet()) {
            if (entry.getKey().equals(parentId) || (locked != null && locked.contains(entry.getKey()))) {
                continue;
            }
            if (!passenger(parent, entry.getValue())) {
                continue;
            }
            ids.add(entry.getKey());
            if (ids.size() >= MAX_PASSENGERS) {
                break;
            }
        }
        return ids;
    }
}
