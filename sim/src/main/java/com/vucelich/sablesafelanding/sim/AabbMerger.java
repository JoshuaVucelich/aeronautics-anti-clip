package com.vucelich.sablesafelanding.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Greedily joins boxes that share a face and match on the other two axes.
 */
public final class AabbMerger {
    private static final double EPS = 1.0e-6;

    private AabbMerger() {
    }

    public static List<Aabb> merge(List<Aabb> boxes) {
        List<Aabb> open = new ArrayList<>();
        for (Aabb box : boxes) {
            if (box.finite()) {
                open.add(box);
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < open.size() && !changed; i++) {
                for (int j = i + 1; j < open.size(); j++) {
                    if (!canMerge(open.get(i), open.get(j))) {
                        continue;
                    }
                    open.set(i, union(open.get(i), open.get(j)));
                    open.remove(j);
                    changed = true;
                    break;
                }
            }
        }
        return List.copyOf(open);
    }

    private static boolean canMerge(Aabb a, Aabb b) {
        boolean sameX = close(a.minX(), b.minX()) && close(a.maxX(), b.maxX());
        boolean sameY = close(a.minY(), b.minY()) && close(a.maxY(), b.maxY());
        boolean sameZ = close(a.minZ(), b.minZ()) && close(a.maxZ(), b.maxZ());
        return sameX && sameY && overlaps(a.minZ(), a.maxZ(), b.minZ(), b.maxZ())
                || sameX && sameZ && overlaps(a.minY(), a.maxY(), b.minY(), b.maxY())
                || sameY && sameZ && overlaps(a.minX(), a.maxX(), b.minX(), b.maxX());
    }

    private static boolean overlaps(double minA, double maxA, double minB, double maxB) {
        return maxA >= minB - EPS && maxB >= minA - EPS;
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) <= EPS;
    }

    private static Aabb union(Aabb a, Aabb b) {
        return new Aabb(
                Math.min(a.minX(), b.minX()),
                Math.min(a.minY(), b.minY()),
                Math.min(a.minZ(), b.minZ()),
                Math.max(a.maxX(), b.maxX()),
                Math.max(a.maxY(), b.maxY()),
                Math.max(a.maxZ(), b.maxZ())
        );
    }
}
