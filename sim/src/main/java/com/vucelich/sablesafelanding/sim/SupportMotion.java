package com.vucelich.sablesafelanding.sim;

/**
 * One contraption volume for this physics substep.
 * {@code translation} is the fractional motion applied during the substep.
 * Bearing motion rotates around {@code pivot} by {@code angleRadians} about {@code axis}.
 */
public record SupportMotion(
        String id,
        Aabb volume,
        Vec3 translation,
        Vec3 pivot,
        Vec3 axis,
        double angleRadians
) {
    public SupportMotion {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("support id required");
        }
    }
}
