package com.vucelich.sablesafelanding.sim;

import java.util.List;

/**
 * A ship block rests on a deck when its bottom face is over that deck and the gap to the
 * deck top is within epsilon. Block centers sit half a block above the deck, so a center
 * test never sees a car that is parked on the planks.
 */
public final class DeckContact {
    private DeckContact() {
    }

    /**
     * {@code bottom} is the center of a block's bottom face. The block covers half a block
     * around that point, so a center just outside the deck still counts when the block overlaps it.
     */
    public static boolean rests(Vec3 bottom, Aabb volume, double epsilon) {
        if (bottom == null || volume == null || !bottom.finite() || !volume.finite()) {
            return false;
        }
        if (!overlaps(bottom.x() - 0.5, bottom.x() + 0.5, volume.minX(), volume.maxX())) {
            return false;
        }
        if (!overlaps(bottom.z() - 0.5, bottom.z() + 0.5, volume.minZ(), volume.maxZ())) {
            return false;
        }
        double gap = bottom.y() - volume.maxY();
        return gap >= -epsilon && gap <= epsilon;
    }

    /** The ship's world box is sitting on the deck. Used to keep a lock through a yaw. */
    public static boolean restingBox(Aabb body, Aabb volume, double epsilon) {
        if (body == null || volume == null || !body.finite() || !volume.finite()) {
            return false;
        }
        if (!body.overlapsHorizontally(volume)) {
            return false;
        }
        double gap = body.minY() - volume.maxY();
        return gap >= -epsilon && gap <= epsilon;
    }

    private static boolean overlaps(double minA, double maxA, double minB, double maxB) {
        return minA < maxB && maxA > minB;
    }

    /**
     * {@code bottoms} are lowest first and already cut at the block cap.
     * {@code hitCap} means more blocks existed than were examined, so a miss is inconclusive
     * and must not release a large ship or start a capture.
     */
    public static CarryGate.Contact judge(List<Vec3> bottoms, List<Aabb> volumes, double epsilon, boolean hitCap) {
        if (bottoms != null && volumes != null) {
            for (Vec3 bottom : bottoms) {
                for (Aabb volume : volumes) {
                    if (rests(bottom, volume, epsilon)) {
                        return CarryGate.Contact.RESTING;
                    }
                }
            }
        }
        return hitCap ? CarryGate.Contact.INCONCLUSIVE : CarryGate.Contact.NONE;
    }
}
