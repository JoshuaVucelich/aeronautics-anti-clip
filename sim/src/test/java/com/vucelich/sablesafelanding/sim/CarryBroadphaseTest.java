package com.vucelich.sablesafelanding.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarryBroadphaseTest {
    @Test
    void aDistantShipIsSkippedAndOnlyTheOverlappingDeckIsKept() {
        Aabb deck = new Aabb(0.0, 10.0, 0.0, 3.0, 11.0, 3.0);
        Aabb other = new Aabb(40.0, 10.0, 40.0, 43.0, 11.0, 43.0);
        Aabb parked = new Aabb(1.0, 11.0, 1.0, 2.0, 12.0, 2.0);
        Aabb gap = new Aabb(1.0, 11.1, 1.0, 2.0, 12.1, 2.0);
        Aabb flyover = new Aabb(1.0, 11.5, 1.0, 2.0, 12.5, 2.0);
        Aabb far = new Aabb(80.0, 11.0, 80.0, 81.0, 12.0, 81.0);
        List<String> ids = List.of("deck", "other");
        List<Aabb> bounds = List.of(deck, other);

        assertEquals(List.of("deck"), CarryBroadphase.near(parked, ids, bounds));
        assertEquals(List.of("deck"), CarryBroadphase.near(gap, ids, bounds));
        assertTrue(CarryBroadphase.near(flyover, ids, bounds).isEmpty());
        assertTrue(CarryBroadphase.near(far, ids, bounds).isEmpty());
    }

    @Test
    void aYawedDeckStillCoversTheCornerTheUnrotatedBoxMisses() {
        Aabb assembled = new Aabb(0.0, 10.0, 0.0, 3.0, 11.0, 3.0);
        Aabb swept = CarryBroadphase.sweepRotation(assembled, new Vec3(0.0, 1.0, 0.0));
        Aabb corner = new Aabb(-0.55, 11.0, 1.0, -0.35, 12.0, 2.0);
        assertTrue(CarryBroadphase.near(corner, List.of("bearing"), List.of(assembled)).isEmpty());
        assertEquals(List.of("bearing"), CarryBroadphase.near(corner, List.of("bearing"), List.of(swept)));
    }
}
