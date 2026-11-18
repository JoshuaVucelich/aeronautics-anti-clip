package com.vucelich.sablesafelanding.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WheelContactTest {
    @Test
    void noContraptionOrSwitchOffMeansNoExtraCast() {
        assertFalse(WheelContact.scan(true, true, 0));
        assertFalse(WheelContact.scan(true, false, 3));
        assertFalse(WheelContact.scan(false, true, 3));
        assertTrue(WheelContact.scan(true, true, 1));
    }

    @Test
    void aWheelAboveTheDeckIsNearAndOneBesideTheShaftIsNot() {
        Aabb deck = new Aabb(0.0, 64.0, 0.0, 5.0, 65.0, 5.0);
        Aabb overDeck = WheelContact.rayBounds(new Vec3(2.5, 66.0, 2.5), new Vec3(2.5, 61.0, 2.5));
        Aabb farAway = WheelContact.rayBounds(new Vec3(30.5, 66.0, 30.5), new Vec3(30.5, 61.0, 30.5));
        assertTrue(WheelContact.near(overDeck, deck));
        assertFalse(WheelContact.near(farAway, deck));
    }

    @Test
    void aWheelRollingOntoTheDeckEdgeCountsAsNear() {
        Aabb deck = new Aabb(0.0, 64.0, 0.0, 5.0, 65.0, 5.0);
        Aabb edge = WheelContact.rayBounds(new Vec3(-0.2, 65.6, 2.5), new Vec3(-0.2, 60.6, 2.5));
        assertTrue(WheelContact.near(edge, deck));
    }

    @Test
    void aTurnedDeckCornerStaysInsideTheReach() {
        Aabb deck = new Aabb(0.0, 64.0, 0.0, 6.0, 65.0, 2.0);
        Aabb reach = WheelContact.reach(deck);
        Vec3 corner = new Vec3(6.0, 64.5, 2.0);
        Vec3 center = deck.center();
        Vec3 turned = center.add(corner.sub(center).rotate(new Vec3(0.0, 1.0, 0.0), Math.toRadians(90.0)));
        assertTrue(reach.contains(turned.x(), turned.y(), turned.z()));
        assertFalse(deck.contains(turned.x(), turned.y(), turned.z()));
    }

    @Test
    void theDeckWinsOnlyWhenItIsCloserOrTheGroundCastMissed() {
        assertTrue(WheelContact.preferDeck(false, Double.NaN, true, 1.4));
        assertTrue(WheelContact.preferDeck(true, 4.6, true, 1.4));
        assertFalse(WheelContact.preferDeck(true, 1.0, true, 1.4));
        assertFalse(WheelContact.preferDeck(true, 1.4, true, 1.4));
        assertFalse(WheelContact.preferDeck(true, 4.6, false, 1.4));
        assertFalse(WheelContact.preferDeck(false, 0.0, true, Double.NaN));
    }

    @Test
    void badInputFailsOpen() {
        assertNull(WheelContact.rayBounds(new Vec3(Double.NaN, 0.0, 0.0), Vec3.ZERO));
        assertNull(WheelContact.reach(null));
        assertFalse(WheelContact.near(null, new Aabb(0, 0, 0, 1, 1, 1)));
    }
}
