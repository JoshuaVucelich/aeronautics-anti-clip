package com.vucelich.sablesafelanding.sim;

/**
 * World limits that decide when a sub-level is rescued and when a pose is safe to remember.
 * {@code removeMin} and {@code removeMax} are Sable's {@code sub_level_remove_min/max}.
 * Rescue starts at those bounds, or {@code approachMargin} blocks before them. It does not
 * fire at min build height.
 */
public record RescueBounds(
        double minBuildHeight,
        double removeMin,
        double removeMax,
        double approachMargin,
        double safeClearance
) {
    public RescueBounds {
        if (removeMin >= removeMax) {
            throw new IllegalArgumentException("remove bounds are inverted");
        }
        if (approachMargin < 0.0 || safeClearance < 0.0) {
            throw new IllegalArgumentException("margins must be non-negative");
        }
    }

    public static RescueBounds of(double minBuildHeight, double removeMin, double removeMax, AntiClipConfig config) {
        return new RescueBounds(minBuildHeight, removeMin, removeMax, config.approachMargin(), config.safeClearance());
    }

    public double floor() {
        return removeMin + approachMargin;
    }

    public double ceiling() {
        return removeMax - approachMargin;
    }

    public boolean sableWouldRemove(Aabb bounds) {
        return bounds.minY() < removeMin || bounds.maxY() > removeMax;
    }

    public boolean needsRescue(Aabb bounds) {
        if (!bounds.finite()) {
            return true;
        }
        return bounds.minY() < removeMin + approachMargin
                || bounds.maxY() > removeMax - approachMargin;
    }

    /**
     * True when the box sits {@code safeClearance} blocks inside the rescue band.
     */
    public boolean comfortablyInside(Aabb bounds) {
        if (!bounds.finite()) {
            return false;
        }
        return bounds.minY() >= floor() + safeClearance && bounds.maxY() <= ceiling() - safeClearance;
    }
}
