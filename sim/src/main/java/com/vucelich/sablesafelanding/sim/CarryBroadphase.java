package com.vucelich.sablesafelanding.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Cheap overlap test before any ship block is read. A ship is skipped unless its box,
 * inflated by {@link #INFLATE}, meets a contraption's bounds. Only those contraptions
 * are then tested block by block.
 */
public final class CarryBroadphase {
    /** Blocks added on each side of the ship box. Matches the capture gap. */
    public static final double INFLATE = 0.2;

    private CarryBroadphase() {
    }

    public static List<String> near(Aabb ship, List<String> contraptionIds, List<Aabb> contraptionBounds) {
        if (ship == null || !ship.finite() || contraptionIds == null || contraptionBounds == null) {
            return List.of();
        }
        Aabb query = ship.expand(INFLATE);
        List<String> hits = new ArrayList<>();
        int count = Math.min(contraptionIds.size(), contraptionBounds.size());
        for (int i = 0; i < count; i++) {
            Aabb bounds = contraptionBounds.get(i);
            if (bounds != null && bounds.finite() && query.intersects(bounds)) {
                hits.add(contraptionIds.get(i));
            }
        }
        return hits;
    }

    /**
     * Bounds that still contain {@code box} after any turn about {@code axis}.
     * Create's contraption box is the unrotated assembly bounds, so a yawed deck
     * would otherwise miss a car sitting on a corner.
     */
    public static Aabb sweepRotation(Aabb box, Vec3 axis) {
        if (box == null || axis == null || !box.finite() || !axis.finite()) {
            return box;
        }
        double cx = (box.minX() + box.maxX()) * 0.5;
        double cy = (box.minY() + box.maxY()) * 0.5;
        double cz = (box.minZ() + box.maxZ()) * 0.5;
        double hx = (box.maxX() - box.minX()) * 0.5;
        double hy = (box.maxY() - box.minY()) * 0.5;
        double hz = (box.maxZ() - box.minZ()) * 0.5;
        double ax = Math.abs(axis.x());
        double ay = Math.abs(axis.y());
        double az = Math.abs(axis.z());
        if (ay >= ax && ay >= az) {
            double radius = Math.hypot(hx, hz);
            return new Aabb(cx - radius, box.minY(), cz - radius, cx + radius, box.maxY(), cz + radius);
        }
        if (ax >= az) {
            double radius = Math.hypot(hy, hz);
            return new Aabb(box.minX(), cy - radius, cz - radius, box.maxX(), cy + radius, cz + radius);
        }
        double radius = Math.hypot(hx, hy);
        return new Aabb(cx - radius, cy - radius, box.minZ(), cx + radius, cy + radius, box.maxZ());
    }
}
