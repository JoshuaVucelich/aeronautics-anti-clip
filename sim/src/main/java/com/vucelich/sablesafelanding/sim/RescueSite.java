package com.vucelich.sablesafelanding.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Where a rescued ship is allowed to appear. A portal is deferred. A buried last-safe
 * pose is walked to the first clear cell, or the rescue is skipped.
 */
public final class RescueSite {
    public static final int MAX_RISE = 16;

    private RescueSite() {
    }

    /** A ship inside a portal is not teleported on this call. */
    public static boolean defer(boolean insidePortal) {
        return insidePortal;
    }

    /**
     * Columns between samples on a bottom layer of {@code columns} cells, capped near {@code cap} reads.
     * {@code ceil(sqrt(columns / cap))}, and at least 1 so a tiny hull is read cell by cell.
     */
    public static int columnStride(long columns, int cap) {
        if (columns < 1 || cap < 1) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(Math.sqrt(columns / (double) cap)));
    }

    /**
     * Whole-block offsets tried in order. Straight up first, then one block sideways
     * at a few heights, for a last-safe cell that is now solid.
     */
    public static List<Vec3> offsets() {
        List<Vec3> offsets = new ArrayList<>();
        for (int y = 0; y <= MAX_RISE; y++) {
            offsets.add(new Vec3(0.0, y, 0.0));
        }
        for (int y = 1; y <= 4; y++) {
            offsets.add(new Vec3(1.0, y, 0.0));
            offsets.add(new Vec3(-1.0, y, 0.0));
            offsets.add(new Vec3(0.0, y, 1.0));
            offsets.add(new Vec3(0.0, y, -1.0));
        }
        return offsets;
    }

    /** The first offset whose cell is clear, or null when every candidate is blocked. */
    public static Vec3 firstClear(List<Vec3> offsets, Predicate<Vec3> blocked) {
        if (offsets == null || blocked == null) {
            return null;
        }
        for (Vec3 offset : offsets) {
            if (offset != null && offset.finite() && !blocked.test(offset)) {
                return offset;
            }
        }
        return null;
    }
}
