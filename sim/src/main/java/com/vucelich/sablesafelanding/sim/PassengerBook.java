package com.vucelich.sablesafelanding.sim;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Deck-frame offsets for cars sitting on a carried ship.
 * A seat is stored only after the child is still on the hull. A piloted tick, a car
 * that leaves the hull, and a correction that fails {@link DeckFollow#acceptable} or
 * {@link CarryGate#safeStep} drop it, so the next on-hull tick captures where the car
 * is now instead of snapping back to the old spot, and a step past the cap does not
 * block every later tick. A prune names one dimension, so a Nether tick does not drop
 * an Overworld seat.
 */
public final class PassengerBook {
    private final Map<String, Seat> seats = new LinkedHashMap<>();

    public void clear() {
        seats.clear();
    }

    public void remove(String childId) {
        if (childId != null) {
            seats.remove(childId);
        }
    }

    public Seat seat(String childId) {
        return seats.get(childId);
    }

    public int count(String parentId) {
        int riding = 0;
        for (Seat seat : seats.values()) {
            if (parentId.equals(seat.parentId)) {
                riding++;
            }
        }
        return riding;
    }

    /** Drops offsets whose parent ship is no longer carried. */
    public void forgetParents(Set<String> livingParents) {
        if (livingParents == null) {
            seats.clear();
            return;
        }
        seats.entrySet().removeIf(entry -> !livingParents.contains(entry.getValue().parentId));
    }

    /** Drops offsets for sub-levels that are gone, so they no longer count toward the cap. */
    public void retain(Set<String> liveChildIds) {
        if (liveChildIds == null) {
            seats.clear();
            return;
        }
        seats.keySet().retainAll(liveChildIds);
    }

    /**
     * Drops seats in {@code dimension} whose child is not in {@code liveChildIds}.
     * Seats stored for any other dimension stay, so a Nether tick cannot clear the Overworld.
     */
    public void retainDimension(String dimension, Set<String> liveChildIds) {
        if (dimension == null) {
            return;
        }
        seats.entrySet().removeIf(entry -> dimension.equals(entry.getValue().dimension)
                && (liveChildIds == null || !liveChildIds.contains(entry.getKey())));
    }

    public void forgetDimension(String dimension) {
        if (dimension == null) {
            return;
        }
        seats.entrySet().removeIf(entry -> dimension.equals(entry.getValue().dimension));
    }

    /**
     * One child during one parent placement.
     * Removes the seat on a piloted tick, a removed child, a child with its own lock,
     * a car that has left the hull, and a rejected correction.
     * Captures a seat only after the hull check, and only when one is not already held.
     *
     * @return the pose to teleport to, or null when this child is not moved
     */
    public Placement apply(Tick tick) {
        if (tick == null || tick.childId == null) {
            return null;
        }
        if (tick.piloting || tick.removed) {
            remove(tick.childId);
            return null;
        }
        if (tick.ownLock) {
            remove(tick.childId);
            return null;
        }
        Seat existing = seats.get(tick.childId);
        if (existing != null && !existing.parentId.equals(tick.parentId)) {
            return null;
        }
        boolean onHull = NestedCarry.passenger(tick.hull, tick.childBox);
        Seat seat = existing;
        if (seat == null) {
            if (!onHull || count(tick.parentId) >= NestedCarry.MAX_PASSENGERS) {
                return null;
            }
            if (tick.childPos == null || tick.orientation == null || tick.oldPivot == null || tick.axis == null) {
                return null;
            }
            seat = new Seat(
                    tick.parentId,
                    tick.dimension,
                    DeckFollow.localOffset(tick.childPos, tick.oldPivot, tick.axis, tick.oldAngle),
                    tick.orientation,
                    tick.oldAngle
            );
            if (!seat.local.finite() || !seat.boarded.finite()) {
                return null;
            }
        } else if (!onHull) {
            remove(tick.childId);
            return null;
        }
        if (tick.pivot == null || tick.axis == null || tick.childPos == null) {
            remove(tick.childId);
            return null;
        }
        Vec3 target = DeckFollow.worldTarget(seat.local, tick.pivot, tick.axis, tick.angle);
        double distance = tick.childPos.sub(target).length();
        if (!target.finite()
                || !DeckFollow.acceptable(tick.childPos, target)
                || !CarryGate.safeStep(distance, tick.angleStep)) {
            remove(tick.childId);
            return null;
        }
        if (existing == null) {
            seats.put(tick.childId, seat);
        }
        Rot turned = DeckFollow.orientation(seat.boarded, tick.axis, seat.capturedAngle, tick.angle);
        return new Placement(target, turned);
    }

    public record Seat(String parentId, String dimension, Vec3 local, Rot boarded, double capturedAngle) {
    }

    public record Placement(Vec3 position, Rot orientation) {
    }

    public static final class Tick {
        final String childId;
        final String parentId;
        final String dimension;
        final boolean piloting;
        final boolean removed;
        final boolean ownLock;
        final Aabb hull;
        final Aabb childBox;
        final Vec3 childPos;
        final Rot orientation;
        final Vec3 oldPivot;
        final Vec3 pivot;
        final Vec3 axis;
        final double oldAngle;
        final double angle;
        final double angleStep;

        public Tick(
                String childId,
                String parentId,
                String dimension,
                boolean piloting,
                boolean removed,
                boolean ownLock,
                Aabb hull,
                Aabb childBox,
                Vec3 childPos,
                Rot orientation,
                Vec3 oldPivot,
                Vec3 pivot,
                Vec3 axis,
                double oldAngle,
                double angle,
                double angleStep
        ) {
            this.childId = childId;
            this.parentId = parentId;
            this.dimension = dimension;
            this.piloting = piloting;
            this.removed = removed;
            this.ownLock = ownLock;
            this.hull = hull;
            this.childBox = childBox;
            this.childPos = childPos;
            this.orientation = orientation;
            this.oldPivot = oldPivot;
            this.pivot = pivot;
            this.axis = axis;
            this.oldAngle = oldAngle;
            this.angle = angle;
            this.angleStep = angleStep;
        }
    }
}
