package com.vucelich.sablesafelanding.sim;

/**
 * Decisions for the wheel ground check against moving contraptions. Offroad's wheel mount
 * casts its suspension rays with {@code Level.clip}, which only sees world blocks and Sable
 * sub-levels. A Create contraption's blocks live in the contraption, so a wheel over an
 * elevator deck sees through it. These rules decide when the extra contraption cast runs and
 * which hit the wheel keeps.
 */
public final class WheelContact {
    /** Blocks added around a wheel ray before it is tested against a contraption's reach. */
    public static final double RAY_PAD = 0.5;
    /** Offroad's suspension rest length. */
    public static final double SUSPENSION_REST = 0.65;
    /** Offroad treats a wheel as lifted past rest + tire radius + this margin. */
    public static final double LIFT_MARGIN = 0.25;
    /** Offroad's ground ray length from the wheel center. */
    public static final double RAY_LENGTH = 5.0;
    /**
     * How far past a ship's box its wheel rays can reach. A wheel center sits one block outside
     * its mount, the side rays one more block along the wheel, and a ray runs 5 blocks along the
     * ship's down axis, which a tilted ship turns. Padding every side covers any tilt.
     */
    public static final double SHIP_REACH = RAY_LENGTH + 2.0 + RAY_PAD;
    /** Offroad ignores a hit whose face is less than 60 degrees from the wheel's up. */
    public static final double MIN_UP_DOT = 0.5;

    private WheelContact() {
    }

    /**
     * True only when the feature is on and the wheel's level has at least one live contraption.
     * With no contraption the wheel ray is left alone and costs one map read.
     */
    public static boolean scan(boolean enabled, boolean wheelContact, int liveContraptions) {
        return enabled && wheelContact && liveContraptions > 0;
    }

    /** Box around one wheel ray, padded so a ray that grazes a deck edge still counts. */
    public static Aabb rayBounds(Vec3 from, Vec3 to) {
        if (from == null || to == null || !from.finite() || !to.finite()) {
            return null;
        }
        return new Aabb(
                Math.min(from.x(), to.x()) - RAY_PAD,
                Math.min(from.y(), to.y()) - RAY_PAD,
                Math.min(from.z(), to.z()) - RAY_PAD,
                Math.max(from.x(), to.x()) + RAY_PAD,
                Math.max(from.y(), to.y()) + RAY_PAD,
                Math.max(from.z(), to.z()) + RAY_PAD
        );
    }

    /**
     * Box that still holds the contraption after any turn about its center. Create's entity box
     * can be the unrotated assembly bounds, so a turned bearing deck could stick out of it.
     */
    public static Aabb reach(Aabb contraption) {
        if (contraption == null || !contraption.finite()) {
            return null;
        }
        Vec3 c = contraption.center();
        double hx = (contraption.maxX() - contraption.minX()) * 0.5;
        double hy = (contraption.maxY() - contraption.minY()) * 0.5;
        double hz = (contraption.maxZ() - contraption.minZ()) * 0.5;
        double r = Math.sqrt(hx * hx + hy * hy + hz * hz);
        return new Aabb(c.x() - r, c.y() - r, c.z() - r, c.x() + r, c.y() + r, c.z() + r);
    }

    /** True when the wheel ray's box meets the contraption's reach. */
    public static boolean near(Aabb ray, Aabb contraption) {
        if (ray == null) {
            return false;
        }
        Aabb reach = reach(contraption);
        return reach != null && ray.intersects(reach);
    }

    /**
     * Box around a ship that holds every one of its wheel rays this tick. A contraption whose
     * reach misses it cannot be under any of the ship's wheels, so its rays skip the deck test.
     */
    public static Aabb shipReach(Aabb shipBox) {
        if (shipBox == null || !shipBox.finite()) {
            return null;
        }
        return shipBox.expand(SHIP_REACH);
    }

    /** True when the contraption's reach can meet one of the ship's wheel rays. */
    public static boolean shipNear(Aabb shipReach, Aabb contraption) {
        return near(shipReach, contraption);
    }

    /**
     * True when Offroad would treat the wheel as standing on this hit: within the suspension
     * reach (rest + tire radius + margin, measured from the wheel center) and on a face that
     * points up in the ship's frame. Only such a hit counts as the ship resting on the deck.
     */
    public static boolean standing(double distance, double tireRadius, double upDot) {
        if (!Double.isFinite(distance) || !Double.isFinite(tireRadius) || !Double.isFinite(upDot)) {
            return false;
        }
        if (distance <= 1.0e-5 || tireRadius < 0.0) {
            return false;
        }
        return distance <= SUSPENSION_REST + tireRadius + LIFT_MARGIN && upDot >= MIN_UP_DOT;
    }

    /**
     * Keep the contraption hit when the original cast missed or the deck is strictly closer.
     * A tie keeps the original so a wheel on solid ground never changes.
     */
    public static boolean preferDeck(boolean baseHit, double baseDistance, boolean deckHit, double deckDistance) {
        if (!deckHit || !Double.isFinite(deckDistance) || deckDistance < 0.0) {
            return false;
        }
        if (!baseHit || !Double.isFinite(baseDistance)) {
            return true;
        }
        return deckDistance < baseDistance;
    }
}
