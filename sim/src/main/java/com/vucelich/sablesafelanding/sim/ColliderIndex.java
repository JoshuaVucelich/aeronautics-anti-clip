package com.vucelich.sablesafelanding.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spatial hash for contraption supports. A box that covers too many cells is kept in a short
 * overflow list so a huge collider cannot explode the grid.
 */
public final class ColliderIndex {
    private static final int MAX_SPAN = 6;

    private final int cellSize;
    private final Map<Long, List<SupportMotion>> cells = new HashMap<>();
    private final List<SupportMotion> wide = new ArrayList<>();
    private int pairChecks;

    public ColliderIndex(List<SupportMotion> supports, int cellSize) {
        if (cellSize < 1) {
            throw new IllegalArgumentException("cell size must be positive");
        }
        this.cellSize = cellSize;
        for (SupportMotion support : supports) {
            if (!support.volume().finite()) {
                continue;
            }
            insert(support);
        }
    }

    public int pairChecks() {
        return pairChecks;
    }

    public List<SupportMotion> nearby(Aabb bounds, int maxPairChecks) {
        if (!bounds.finite() || maxPairChecks > 0 && pairChecks >= maxPairChecks) {
            return List.of();
        }
        Set<SupportMotion> found = new LinkedHashSet<>(wide);
        int minX = cell(bounds.minX());
        int maxX = cell(bounds.maxX());
        int minY = cell(bounds.minY());
        int maxY = cell(bounds.maxY());
        int minZ = cell(bounds.minZ());
        int maxZ = cell(bounds.maxZ());
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    List<SupportMotion> bucket = cells.get(key(x, y, z));
                    if (bucket != null) {
                        found.addAll(bucket);
                    }
                }
            }
        }
        if (maxPairChecks > 0) {
            int room = maxPairChecks - pairChecks;
            if (found.size() > room) {
                List<SupportMotion> trimmed = new ArrayList<>(room);
                int kept = 0;
                for (SupportMotion support : found) {
                    if (kept >= room) {
                        break;
                    }
                    trimmed.add(support);
                    kept++;
                }
                pairChecks += trimmed.size();
                return List.copyOf(trimmed);
            }
        }
        pairChecks += found.size();
        return List.copyOf(found);
    }

    private void insert(SupportMotion support) {
        Aabb box = support.volume();
        int minX = cell(box.minX());
        int maxX = cell(box.maxX());
        int minY = cell(box.minY());
        int maxY = cell(box.maxY());
        int minZ = cell(box.minZ());
        int maxZ = cell(box.maxZ());
        if (maxX - minX > MAX_SPAN || maxY - minY > MAX_SPAN || maxZ - minZ > MAX_SPAN) {
            wide.add(support);
            return;
        }
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cells.computeIfAbsent(key(x, y, z), ignored -> new ArrayList<>()).add(support);
                }
            }
        }
    }

    private int cell(double value) {
        return (int) Math.floor(value / cellSize);
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x1fffffL) | (((long) y & 0x1fffffL) << 21) | (((long) z & 0x1fffffL) << 42);
    }
}
