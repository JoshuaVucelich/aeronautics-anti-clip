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

    public static boolean rests(Vec3 bottom, Aabb volume, double epsilon) {
        if (bottom == null || volume == null || !bottom.finite() || !volume.finite()) {
            return false;
        }
        if (bottom.x() < volume.minX() || bottom.x() >= volume.maxX()) {
            return false;
        }
        if (bottom.z() < volume.minZ() || bottom.z() >= volume.maxZ()) {
            return false;
        }
        double gap = bottom.y() - volume.maxY();
        return gap >= -epsilon && gap <= epsilon;
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
