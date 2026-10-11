package com.vucelich.sablesafelanding.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeckFollowTest {
    private static final Vec3 UP = new Vec3(0.0, 1.0, 0.0);

    @Test
    void aDescendingAndAscendingDeckKeepTheCapturedOffset() {
        Vec3 pivot = new Vec3(4.0, 10.5, 8.0);
        Vec3 ship = new Vec3(4.2, 11.5, 8.1);
        Vec3 local = DeckFollow.localOffset(ship, pivot, UP, 0.0);

        Vec3 down = DeckFollow.worldTarget(local, new Vec3(4.0, 6.5, 8.0), UP, 0.0);
        assertEquals(4.2, down.x(), 1.0e-9);
        assertEquals(7.5, down.y(), 1.0e-9);
        assertEquals(8.1, down.z(), 1.0e-9);
        assertTrue(DeckFollow.acceptable(ship, down));

        Vec3 up = DeckFollow.worldTarget(local, new Vec3(4.0, 14.5, 8.0), UP, 0.0);
        assertEquals(15.5, up.y(), 1.0e-9);
        assertTrue(DeckFollow.acceptable(ship, up));
    }

    @Test
    void aBearingOrbitStaysOnTheCapturedRadius() {
        Vec3 pivot = new Vec3(0.5, 0.5, 0.5);
        Vec3 ship = new Vec3(2.5, 1.0, 0.5);
        double captured = Math.toRadians(20.0);
        Vec3 local = DeckFollow.localOffset(ship, pivot, UP, captured);

        double turned = Math.toRadians(30.0);
        Vec3 stepped = DeckFollow.worldTarget(local, pivot, UP, turned);
        Vec3 expected = ship.sub(pivot).rotate(UP, Math.toRadians(10.0)).add(pivot);
        assertEquals(expected.x(), stepped.x(), 1.0e-9);
        assertEquals(expected.y(), stepped.y(), 1.0e-9);
        assertEquals(expected.z(), stepped.z(), 1.0e-9);
        assertTrue(stepped.sub(ship).length() < 0.5);
        assertTrue(DeckFollow.acceptable(ship, stepped));

        Vec3 quarter = DeckFollow.worldTarget(local, pivot, UP, captured + Math.toRadians(90.0));
        assertEquals(ship.sub(pivot).length(), quarter.sub(pivot).length(), 1.0e-9);
        assertTrue(quarter.sub(ship).length() < 4.0);
        assertTrue(DeckFollow.acceptable(ship, quarter));
    }

    @Test
    void aFlingIsRefusedAndALongElevatorDropIsNot() {
        Vec3 ship = new Vec3(0.0, 11.0, 0.0);
        assertFalse(DeckFollow.acceptable(ship, new Vec3(0.0, 11.0, 15.87)));
        assertFalse(DeckFollow.acceptable(ship, new Vec3(0.0, 80.0, 0.0)));
        assertTrue(DeckFollow.acceptable(ship, new Vec3(0.0, 7.0, 0.0)));
        assertFalse(DeckFollow.acceptable(ship, new Vec3(Double.NaN, 7.0, 0.0)));
    }
}
