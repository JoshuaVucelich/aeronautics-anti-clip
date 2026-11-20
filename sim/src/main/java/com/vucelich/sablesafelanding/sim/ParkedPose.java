package com.vucelich.sablesafelanding.sim;

/**
 * A cheap safe-pose record for a ship that is outside every rescue band. The full rescue step
 * only runs near the floor, ceiling, or bedrock line, so without this the tracker would only
 * hold poses from inside that band. A ship that sat on the surface far from any band and then
 * fell through bedrock would have no pose to go back to.
 *
 * <p>The record is one reused {@code double[SIZE]} per ship, written in place at most once every
 * {@code stride} ticks. No allocation happens after the first sight of a ship.
 */
public final class ParkedPose {
    public static final int SIZE = 15;
    private static final int TIME = 0;
    private static final int VALID = 14;

    private ParkedPose() {
    }

    public static double[] fresh() {
        double[] slot = new double[SIZE];
        slot[TIME] = Double.NEGATIVE_INFINITY;
        return slot;
    }

    /** True when the last write is at least {@code stride} ticks old (or the clock went back). */
    public static boolean due(double[] slot, long time, long stride) {
        double last = slot[TIME];
        return !(time >= last && time - last < stride);
    }

    /**
     * The same safe test the tracker uses, minus penetration (that needs deck shapes; the full
     * step checks it again inside the band): finite, not below min build height, and not falling
     * faster than {@code stableFallSpeed}.
     */
    public static boolean eligible(double x, double y, double z, double minY, double maxY, double vy,
                                   double minBuildHeight, double stableFallSpeed) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(minY) || !Double.isFinite(maxY)) {
            return false;
        }
        return vy > -stableFallSpeed && minY >= minBuildHeight;
    }

    public static void write(double[] slot, long time, double x, double y, double z,
                             double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                             double qx, double qy, double qz, double qw) {
        slot[TIME] = time;
        slot[1] = x;
        slot[2] = y;
        slot[3] = z;
        slot[4] = minX;
        slot[5] = minY;
        slot[6] = minZ;
        slot[7] = maxX;
        slot[8] = maxY;
        slot[9] = maxZ;
        slot[10] = qx;
        slot[11] = qy;
        slot[12] = qz;
        slot[13] = qw;
        slot[VALID] = 1.0;
    }

    /** The recorded pose when it is newer than {@code current}, else null. */
    public static PoseSnapshot newerThan(double[] slot, PoseSnapshot current) {
        if (slot == null || slot[VALID] != 1.0) {
            return null;
        }
        long at = (long) slot[TIME];
        if (current != null && current.time() >= at) {
            return null;
        }
        PoseSnapshot snapshot = new PoseSnapshot(
                new Vec3(slot[1], slot[2], slot[3]),
                new Aabb(slot[4], slot[5], slot[6], slot[7], slot[8], slot[9]),
                new Rot(slot[10], slot[11], slot[12], slot[13]),
                at
        );
        return snapshot.finite() ? snapshot : null;
    }
}
