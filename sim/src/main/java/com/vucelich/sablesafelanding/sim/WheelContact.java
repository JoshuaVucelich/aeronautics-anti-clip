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
