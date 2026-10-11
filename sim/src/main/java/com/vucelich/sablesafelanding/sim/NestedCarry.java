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
     * The child sits in or on the parent. A sibling a few blocks away does not.
     * A box that merely clips a corner from below the hull does not.
     */
    public static boolean passenger(Aabb parent, Aabb child) {
        if (parent == null || child == null || !parent.finite() || !child.finite()) {
            return false;
        }
        Aabb near = parent.expand(PAD);
        if (!near.intersects(child)) {
            return false;
        }
        if (child.minY() < parent.minY() - PAD) {
            return false;
        }
        if (child.minY() > parent.maxY() + PAD) {
            return false;
        }
        Vec3 center = child.center();
        return center.x() >= near.minX() && center.x() < near.maxX()
                && center.z() >= near.minZ() && center.z() < near.maxZ();
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
