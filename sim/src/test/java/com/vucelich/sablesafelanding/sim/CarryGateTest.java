package com.vucelich.sablesafelanding.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarryGateTest {
    private static final Vec3 UP = new Vec3(0.0, 1.0, 0.0);

    @Test
    void feedAloneNeverCapturesAndContactMustHold() {
        assertFalse(CarryGate.capture(false, CarryGate.CONTACT_HOLD));
        assertFalse(CarryGate.capture(true, CarryGate.CONTACT_HOLD - 1));
        assertTrue(CarryGate.capture(true, CarryGate.CONTACT_HOLD));

        int streak = 0;
        for (int i = 0; i < 4; i++) {
            streak = CarryGate.nextStreak(streak, CarryGate.Contact.RESTING);
        }
        assertEquals(4, streak);
        streak = CarryGate.nextStreak(streak, CarryGate.Contact.INCONCLUSIVE);
        assertEquals(4, streak);
        assertEquals(0, CarryGate.nextStreak(streak, CarryGate.Contact.NONE));
    }

    @Test
    void eachReleaseHasItsOwnReason() {
        assertEquals(CarryGate.Reason.DISASSEMBLED, CarryGate.release(true, true, 4.0, 0.0, 20, 20));
        assertEquals(CarryGate.Reason.PILOT, CarryGate.release(false, true, 4.0, 0.0, 20, 20));
        assertEquals(CarryGate.Reason.TAKEOFF, CarryGate.release(false, false, 1.0, 0.0, 20, 20));
        assertNull(CarryGate.release(false, false, 0.6, 0.4, 0, 0));
        assertEquals(CarryGate.Reason.IDLE, CarryGate.release(false, false, 0.0, 0.0, CarryGate.IDLE_TICKS, 0));
        assertEquals(CarryGate.Reason.NOCONTACT, CarryGate.release(false, false, 0.0, 0.0, 0, CarryGate.NO_CONTACT_RELEASE));
        assertNull(CarryGate.release(false, false, 0.0, 0.0, CarryGate.IDLE_TICKS - 1, CarryGate.NO_CONTACT_RELEASE - 1));
        assertEquals("idle", CarryGate.reasonName(CarryGate.Reason.IDLE));
        assertEquals("nocontact", CarryGate.reasonName(CarryGate.Reason.NOCONTACT));
        assertEquals("disassembled", CarryGate.reasonName(CarryGate.Reason.DISASSEMBLED));
        assertEquals("takeoff", CarryGate.reasonName(CarryGate.Reason.TAKEOFF));
        assertEquals("pilot", CarryGate.reasonName(CarryGate.Reason.PILOT));
    }

    @Test
    void idleAndTeleportWaitForRealDrift() {
        assertEquals(1, CarryGate.nextIdle(0, 0.0, 0.0));
        assertEquals(0, CarryGate.nextIdle(4, CarryGate.IDLE_ANCHOR, 0.0));
        assertEquals(0, CarryGate.nextIdle(4, 0.0, CarryGate.IDLE_ANGLE));

        assertFalse(CarryGate.teleport(CarryGate.DRIFT * CarryGate.DRIFT, 0.0));
        assertTrue(CarryGate.teleport(CarryGate.DRIFT * CarryGate.DRIFT + 1.0e-6, 0.0));
        assertFalse(CarryGate.teleport(0.0, CarryGate.ANGLE_DRIFT));
        assertTrue(CarryGate.teleport(0.0, CarryGate.ANGLE_DRIFT + 1.0e-6));
    }

    @Test
    void aBearingTurnsPositionAndYawByTheCapturedDelta() {
        Vec3 pivot = new Vec3(0.5, 0.5, 0.5);
        Vec3 ship = new Vec3(2.5, 1.0, 0.5);
        double captured = Math.toRadians(20.0);
        double current = captured + Math.toRadians(90.0);
        Vec3 local = DeckFollow.localOffset(ship, pivot, UP, captured);
        Vec3 placed = DeckFollow.worldTarget(local, pivot, UP, current);
        Vec3 expected = ship.sub(pivot).rotate(UP, Math.toRadians(90.0)).add(pivot);
        assertEquals(expected.x(), placed.x(), 1.0e-9);
        assertEquals(expected.z(), placed.z(), 1.0e-9);

        Rot boarded = Rot.IDENTITY.turned(UP, Math.toRadians(15.0));
        Rot turned = DeckFollow.orientation(boarded, UP, captured, current);
        Rot expect = boarded.turned(UP, current - captured);
        assertEquals(expect.x(), turned.x(), 1.0e-9);
        assertEquals(expect.y(), turned.y(), 1.0e-9);
        assertEquals(expect.z(), turned.z(), 1.0e-9);
        assertEquals(expect.w(), turned.w(), 1.0e-9);
        assertEquals(Math.toRadians(105.0), turned.yaw(), 1.0e-9);
    }

    @Test
    void blockBottomsRestAndACappedMissDoesNot() {
        Aabb deck = new Aabb(0.0, 10.0, 0.0, 3.0, 11.0, 3.0);
        Vec3 parked = new Vec3(1.5, 11.0, 1.5);
        Vec3 flyover = new Vec3(1.5, 11.7, 1.5);
        Vec3 between = new Vec3(1.5, 11.3, 1.5);
        assertTrue(DeckContact.rests(parked, deck, CarryGate.CAPTURE_GAP));
        assertFalse(DeckContact.rests(flyover, deck, CarryGate.CAPTURE_GAP));
        assertFalse(DeckContact.rests(between, deck, CarryGate.CAPTURE_GAP));
        assertTrue(DeckContact.rests(between, deck, CarryGate.MAINTAIN_GAP));
        assertFalse(DeckContact.rests(flyover, deck, CarryGate.MAINTAIN_GAP));
        assertEquals(CarryGate.Contact.RESTING, DeckContact.judge(List.of(parked), List.of(deck), CarryGate.CAPTURE_GAP, true));
        assertEquals(
                CarryGate.Contact.INCONCLUSIVE,
                DeckContact.judge(List.of(flyover), List.of(deck), CarryGate.CAPTURE_GAP, true)
        );
        assertEquals(
                CarryGate.Contact.NONE,
                DeckContact.judge(List.of(flyover), List.of(deck), CarryGate.CAPTURE_GAP, false)
        );
        assertEquals(3, CarryGate.nextMisses(3, CarryGate.Contact.INCONCLUSIVE));
        assertEquals(0, CarryGate.nextMisses(3, CarryGate.Contact.RESTING));
        assertEquals(3, CarryGate.nextStreak(3, CarryGate.Contact.INCONCLUSIVE));
    }
}
