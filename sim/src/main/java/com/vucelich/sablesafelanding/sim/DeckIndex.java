package com.vucelich.sablesafelanding.sim;

import java.util.HashMap;
import java.util.Map;

/**
 * A contraption's block cells in its own local frame, bucketed by 16x16x16 section. Built once
 * per contraption shape, one block at a time, so the build can be spread over ticks. A region
 * query only reads the sections the region touches, so its cost follows the region, not the
 * contraption size.
 */
public final class DeckIndex {
    private static final int SHIFT = 4;

    private final Map<Long, long[]> sections = new HashMap<>();
    private Map<Long, Bucket> building = new HashMap<>();
    private int size;
    private boolean sealed;

    private static final class Bucket {
        private long[] cells = new long[16];
        private int count;
    }

    public void add(int x, int y, int z) {
        if (sealed) {
            throw new IllegalStateException("sealed");
        }
        Bucket bucket = building.computeIfAbsent(GridMerger.pack(x >> SHIFT, y >> SHIFT, z >> SHIFT), ignored -> new Bucket());
        if (bucket.count == bucket.cells.length) {
            bucket.cells = java.util.Arrays.copyOf(bucket.cells, bucket.cells.length * 2);
        }
        bucket.cells[bucket.count++] = GridMerger.pack(x, y, z);
        size++;
    }

    /** Ends the build. Queries are allowed only after this. */
    public DeckIndex seal() {
        for (Map.Entry<Long, Bucket> entry : building.entrySet()) {
            Bucket bucket = entry.getValue();
            sections.put(entry.getKey(), java.util.Arrays.copyOf(bucket.cells, bucket.count));
        }
        building = null;
        sealed = true;
        return this;
    }

    public boolean sealed() {
        return sealed;
    }

    public int size() {
        return size;
    }

    public int sectionCount() {
        return sections.size();
    }

    /** Result of a region query. {@code truncated} is true when {@code cap} stopped it. */
    public record Region(long[] cells, int count, boolean truncated) {
    }

    /**
     * Cells whose block lies inside the inclusive integer region, at most {@code cap} of them.
     */
    public Region query(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int cap) {
        if (!sealed) {
            throw new IllegalStateException("not sealed");
        }
        long[] out = new long[Math.min(cap, 64)];
        int count = 0;
        if (minX > maxX || minY > maxY || minZ > maxZ || cap <= 0) {
            return new Region(out, 0, false);
        }
        int sx0 = minX >> SHIFT;
        int sy0 = minY >> SHIFT;
        int sz0 = minZ >> SHIFT;
        int sx1 = maxX >> SHIFT;
        int sy1 = maxY >> SHIFT;
        int sz1 = maxZ >> SHIFT;
        long spanned = (long) (sx1 - sx0 + 1) * (sy1 - sy0 + 1) * (sz1 - sz0 + 1);
        if (spanned > sections.size()) {
            // A region wider than the contraption: walk the sections that exist instead.
            for (Map.Entry<Long, long[]> entry : sections.entrySet()) {
                long key = entry.getKey();
                int sx = GridMerger.unpackX(key);
                int sy = GridMerger.unpackY(key);
                int sz = GridMerger.unpackZ(key);
                if (sx < sx0 || sx > sx1 || sy < sy0 || sy > sy1 || sz < sz0 || sz > sz1) {
                    continue;
                }
                long[] cells = entry.getValue();
                for (long cell : cells) {
                    if (!inside(cell, minX, minY, minZ, maxX, maxY, maxZ)) {
                        continue;
                    }
                    if (count >= cap) {
                        return new Region(out, count, true);
                    }
                    if (count == out.length) {
                        out = java.util.Arrays.copyOf(out, Math.min(cap, out.length * 2));
                    }
                    out[count++] = cell;
                }
            }
            return new Region(out, count, false);
        }
        for (int sy = sy0; sy <= sy1; sy++) {
            for (int sz = sz0; sz <= sz1; sz++) {
                for (int sx = sx0; sx <= sx1; sx++) {
                    long[] cells = sections.get(GridMerger.pack(sx, sy, sz));
                    if (cells == null) {
                        continue;
                    }
                    for (long cell : cells) {
                        if (!inside(cell, minX, minY, minZ, maxX, maxY, maxZ)) {
                            continue;
                        }
                        if (count >= cap) {
                            return new Region(out, count, true);
                        }
                        if (count == out.length) {
                            out = java.util.Arrays.copyOf(out, Math.min(cap, out.length * 2));
                        }
                        out[count++] = cell;
                    }
                }
            }
        }
        return new Region(out, count, false);
    }

    private static boolean inside(long cell, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int x = GridMerger.unpackX(cell);
        int y = GridMerger.unpackY(cell);
        int z = GridMerger.unpackZ(cell);
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
}
