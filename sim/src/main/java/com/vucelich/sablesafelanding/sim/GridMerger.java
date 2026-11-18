package com.vucelich.sablesafelanding.sim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Joins unit block cells into boxes by greedy meshing. The boxes cover exactly the given cells,
 * nothing more, and each cell is in one box. Cost is close to linear in the cell count, so a
 * contraption with tens of thousands of blocks is merged in milliseconds.
 *
 * <p>{@link AabbMerger} restarts its pair search after every join and grows with the cube of
 * the box count: about 50 seconds for 6,854 blocks. That ran every tick for every loaded world
 * contraption and stalled the server. This replaces it on the live path.
 *
 * <p>An axis can be locked so cells only join along the others. A rotating deck locks the two
 * axes across its rotation axis, so a rotated box encloses the same space the per-block boxes
 * did.
 */
public final class GridMerger {
    private GridMerger() {
    }

    /** Packs a cell. Each coordinate must fit in 21 signed bits (plus or minus a million). */
    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    public static int unpackX(long packed) {
        return signed((int) (packed >>> 42) & 0x1FFFFF);
    }

    public static int unpackY(long packed) {
        return signed((int) (packed >>> 21) & 0x1FFFFF);
    }

    public static int unpackZ(long packed) {
        return signed((int) packed & 0x1FFFFF);
    }

    private static int signed(int value) {
        return (value << 11) >> 11;
    }

    /** All axes may join. */
    public static List<Aabb> merge(long[] cells, int count) {
        return merge(cells, count, true, true, true);
    }

    public static List<Aabb> merge(long[] cells, int count, boolean joinX, boolean joinY, boolean joinZ) {
        if (count <= 0) {
            return List.of();
        }
        long[] sorted = Arrays.copyOf(cells, count);
        // Order by y, then z, then x, so the first unvisited cell is a box's low corner.
        long[] keys = new long[count];
        for (int i = 0; i < count; i++) {
            keys[i] = orderKey(sorted[i]);
        }
        sortByKeys(sorted, keys);
        Set<Long> present = new HashSet<>(count * 2);
        for (int i = 0; i < count; i++) {
            present.add(sorted[i]);
        }
        Set<Long> used = new HashSet<>(count * 2);
        List<Aabb> boxes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long cell = sorted[i];
            if (used.contains(cell)) {
                continue;
            }
            int x0 = unpackX(cell);
            int y0 = unpackY(cell);
            int z0 = unpackZ(cell);
            int lx = 1;
            while (joinX && free(present, used, x0 + lx, y0, z0)) {
                lx++;
            }
            int lz = 1;
            while (joinZ && rowFree(present, used, x0, lx, y0, z0 + lz)) {
                lz++;
            }
            int ly = 1;
            while (joinY && layerFree(present, used, x0, lx, y0 + ly, z0, lz)) {
                ly++;
            }
            for (int y = y0; y < y0 + ly; y++) {
                for (int z = z0; z < z0 + lz; z++) {
                    for (int x = x0; x < x0 + lx; x++) {
                        used.add(pack(x, y, z));
                    }
                }
            }
            boxes.add(new Aabb(x0, y0, z0, x0 + lx, y0 + ly, z0 + lz));
        }
        return boxes;
    }

    private static long orderKey(long cell) {
        long y = unpackY(cell) + 0x100000L;
        long z = unpackZ(cell) + 0x100000L;
        long x = unpackX(cell) + 0x100000L;
        return (y << 42) | (z << 21) | x;
    }

    private static void sortByKeys(long[] cells, long[] keys) {
        long[][] pairs = new long[cells.length][];
        for (int i = 0; i < cells.length; i++) {
            pairs[i] = new long[]{keys[i], cells[i]};
        }
        Arrays.sort(pairs, (a, b) -> Long.compare(a[0], b[0]));
        for (int i = 0; i < cells.length; i++) {
            cells[i] = pairs[i][1];
        }
    }

    private static boolean free(Set<Long> present, Set<Long> used, int x, int y, int z) {
        long cell = pack(x, y, z);
        return present.contains(cell) && !used.contains(cell);
    }

    private static boolean rowFree(Set<Long> present, Set<Long> used, int x0, int lx, int y, int z) {
        for (int x = x0; x < x0 + lx; x++) {
            if (!free(present, used, x, y, z)) {
                return false;
            }
        }
        return true;
    }

    private static boolean layerFree(Set<Long> present, Set<Long> used, int x0, int lx, int y, int z0, int lz) {
        for (int z = z0; z < z0 + lz; z++) {
            if (!rowFree(present, used, x0, lx, y, z)) {
                return false;
            }
        }
        return true;
    }
}
