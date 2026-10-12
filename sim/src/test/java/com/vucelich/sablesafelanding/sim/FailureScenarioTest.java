package com.vucelich.sablesafelanding.sim;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repros for the losses in docs/FAILURE_SCENARIOS.md.
 */
class FailureScenarioTest {
    private static final double DT = HeadlessLandingHarness.TICK_SECONDS;
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Test
    void chunkUnloadAndASpikeReleaseBeforeADisassembledDeckDoes() {
        assertEquals(
                CarryGate.Reason.UNLOADED,
                CarryGate.release(true, false, 0, 0, 0, true, false)
        );
        assertEquals(
                CarryGate.Reason.SPIKE,
                CarryGate.release(false, false, 0, 0, 0, false, true)
        );
        assertEquals(
                CarryGate.Reason.DISASSEMBLED,
                CarryGate.release(true, false, 0, 0, 0, false, false)
        );
        assertEquals("unloaded", CarryGate.reasonName(CarryGate.Reason.UNLOADED));
        assertEquals("spike", CarryGate.reasonName(CarryGate.Reason.SPIKE));
    }

    @Test
    void aReverseClearsIdleAndAStepPastTheCapIsRefused() {
        assertEquals(0, CarryGate.nextIdle(9, 0.5, 0.0));
        assertEquals(10, CarryGate.nextIdle(9, 0.0, 0.0));

        Vec3 pivot = new Vec3(0.5, 64.5, 0.5);
        Vec3 axis = new Vec3(0.0, 1.0, 0.0);
        Vec3 ship = new Vec3(1.5, 65.0, 0.5);
        Vec3 local = DeckFollow.localOffset(ship, pivot, axis, 0.0);
        Vec3 down = DeckFollow.worldTarget(local, pivot.add(new Vec3(0.0, -4.0, 0.0)), axis, 0.0);
        Vec3 back = DeckFollow.worldTarget(local, pivot, axis, 0.0);
        assertEquals(61.0, down.y(), 1.0e-9);
        assertEquals(ship.y(), back.y(), 1.0e-9);
        assertTrue(DeckFollow.acceptable(ship, down));

        assertTrue(CarryGate.safeStep(0.06, Math.toRadians(9.6)));
        assertTrue(CarryGate.safeStep(CarryGate.MAX_TICK_TRAVEL, CarryGate.MAX_TICK_ANGLE));
        assertFalse(CarryGate.safeStep(CarryGate.MAX_TICK_TRAVEL + 0.01, 0.0));
        assertFalse(CarryGate.safeStep(0.0, Math.toRadians(91.0)));
        assertFalse(CarryGate.safeStep(Double.NaN, 0.0));
    }

    @Test
    void aLagSpikeIsALongSubstepNotANormalSableStep() {
        assertFalse(CarryGate.lagSpike(1.0 / 20.0 / 2.0));
        assertFalse(CarryGate.lagSpike(CarryGate.LAG_STEP_SECONDS));
        assertTrue(CarryGate.lagSpike(CarryGate.LAG_STEP_SECONDS + 0.01));
        assertTrue(CarryGate.lagSpike(Double.NaN));
    }

    @Test
    void twoCarsOnOneDeckAreBothNearAndAThirdPastTheCapWaits() {
        Aabb deck = new Aabb(0.0, 10.0, 0.0, 6.0, 11.0, 3.0);
        Aabb first = new Aabb(0.2, 11.0, 0.2, 1.8, 12.0, 2.0);
        Aabb second = new Aabb(3.2, 11.0, 0.2, 4.8, 12.0, 2.0);
        Aabb away = new Aabb(40.0, 11.0, 0.0, 42.0, 12.0, 2.0);
        assertEquals(List.of("deck"), CarryBroadphase.near(first, List.of("deck"), List.of(deck)));
        assertEquals(List.of("deck"), CarryBroadphase.near(second, List.of("deck"), List.of(deck)));
        assertTrue(CarryBroadphase.near(away, List.of("deck"), List.of(deck)).isEmpty());

        assertTrue(CarryBudget.admit(2, 0));
        assertFalse(CarryBudget.admit(CarryBudget.MAX_LOCKS, 0));
        assertFalse(CarryBudget.admit(0, CarryBudget.MAX_SHIPS));
        assertEquals(0, CarryBudget.windowStart(2, 99, CarryBudget.MAX_SHIPS));
        assertEquals(5, CarryBudget.windowStart(40, 45, CarryBudget.MAX_SHIPS));
    }

    @Test
    void aCarOnAShipIsAPassengerAndASiblingIsNot() {
        Aabb ship = new Aabb(0.0, 10.0, 0.0, 8.0, 14.0, 4.0);
        Aabb car = new Aabb(2.0, 14.0, 1.0, 4.0, 15.2, 3.0);
        Aabb sibling = new Aabb(20.0, 14.0, 1.0, 22.0, 15.2, 3.0);
        Aabb below = new Aabb(2.0, 4.0, 1.0, 4.0, 6.0, 3.0);
        Aabb adjacent = new Aabb(8.0, 10.0, 0.0, 10.0, 14.0, 4.0);
        assertTrue(NestedCarry.passenger(ship, car));
        assertFalse(NestedCarry.passenger(ship, sibling));
        assertFalse(NestedCarry.passenger(ship, below));
        assertFalse(NestedCarry.passenger(ship, adjacent));

        Map<String, Aabb> others = new LinkedHashMap<>();
        others.put("ship", ship);
        others.put("car", car);
        others.put("sibling", sibling);
        others.put("locked", new Aabb(1.0, 14.0, 1.0, 2.0, 15.0, 2.0));
        List<String> passengers = NestedCarry.passengers(ship, others, "ship", Set.of("locked"));
        assertEquals(List.of("car"), passengers);

        Vec3 axis = new Vec3(0.0, 1.0, 0.0);
        Vec3 pivot = new Vec3(4.0, 12.0, 2.0);
        Vec3 child = new Vec3(6.0, 14.5, 2.0);
        Vec3 turnedChild = NestedCarry.place(child, pivot, pivot, axis, Math.PI / 2.0);
        assertEquals(4.0, turnedChild.x(), 1.0e-9);
        assertEquals(14.5, turnedChild.y(), 1.0e-9);
        assertEquals(0.0, turnedChild.z(), 1.0e-9);
        assertEquals(Math.PI / 2.0, Rot.IDENTITY.turned(axis, Math.PI / 2.0).yaw(), 1.0e-9);
    }

    @Test
    void aPassengerOffsetSurvivesAPhysicsPush() {
        Vec3 axis = new Vec3(0.0, 1.0, 0.0);
        Vec3 pivot = new Vec3(0.0, 10.0, 0.0);
        Vec3 child = new Vec3(2.0, 14.0, 1.0);
        Vec3 local = DeckFollow.localOffset(child, pivot, axis, 0.0);
        Vec3 drifted = new Vec3(2.0, 14.8, 1.0);
        Vec3 nextPivot = new Vec3(0.0, 9.8, 0.0);
        Vec3 incremental = NestedCarry.place(drifted, pivot, nextPivot, axis, 0.0);
        Vec3 corrected = DeckFollow.worldTarget(local, nextPivot, axis, 0.0);
        assertEquals(14.6, incremental.y(), 1.0e-9);
        assertEquals(13.8, corrected.y(), 1.0e-9);
        assertTrue(CarryGate.safeStep(drifted.sub(corrected).length(), 0.0));
    }

    @Test
    void aCarDrivenToANewDeckSpotIsRecapturedOnRelease() {
        PassengerBook book = new PassengerBook();
        Aabb hull = new Aabb(0.0, 10.0, 0.0, 16.0, 11.0, 6.0);
        Vec3 pivot = new Vec3(8.0, 10.5, 3.0);
        Vec3 boarded = new Vec3(2.0, 11.5, 2.0);
        assertEquals(boarded.x(), ride(book, "car", false, hull, boxAround(boarded), boarded, pivot, pivot).x(), 1.0e-9);

        Vec3 pushed = new Vec3(2.0, 11.9, 2.0);
        Vec3 corrected = ride(book, "car", false, hull, boxAround(pushed), pushed, pivot, pivot);
        assertEquals(11.5, corrected.y(), 1.0e-9);

        Vec3 driven = new Vec3(6.0, 11.5, 2.0);
        assertNull(ride(book, "car", true, hull, boxAround(driven), driven, pivot, pivot));
        assertNull(book.seat("car"));

        Vec3 nextPivot = new Vec3(8.0, 10.3, 3.0);
        Vec3 released = ride(book, "car", false, hull, boxAround(driven), driven, pivot, nextPivot);
        assertEquals(6.0, released.x(), 1.0e-9);
        assertEquals(11.3, released.y(), 1.0e-9);

        Vec3 slid = new Vec3(12.0, 11.5, 2.0);
        assertNull(ride(book, "car", false, hull, boxAround(slid), slid, nextPivot, nextPivot));
        assertNull(book.seat("car"));
        Vec3 laterPivot = new Vec3(8.0, 10.1, 3.0);
        Vec3 carried = ride(book, "car", false, hull, boxAround(slid), slid, nextPivot, laterPivot);
        assertEquals(12.0, carried.x(), 1.0e-9);
        assertEquals(11.3, carried.y(), 1.0e-9);
        assertEquals("ship", book.seat("car").parentId());
    }

    @Test
    void aCarDrivenOffTheHullIsNotSnappedBackOnRelease() {
        PassengerBook book = new PassengerBook();
        Aabb hull = new Aabb(0.0, 10.0, 0.0, 16.0, 11.0, 6.0);
        Vec3 pivot = new Vec3(8.0, 10.5, 3.0);
        Vec3 boarded = new Vec3(2.0, 11.5, 2.0);
        ride(book, "car", false, hull, boxAround(boarded), boarded, pivot, pivot);

        Vec3 gone = new Vec3(21.0, 11.5, 2.0);
        assertNull(ride(book, "car", true, hull, boxAround(gone), gone, pivot, pivot));
        assertNull(book.seat("car"));

        Vec3 nextPivot = new Vec3(8.0, 10.3, 3.0);
        assertNull(ride(book, "car", false, hull, boxAround(gone), gone, pivot, nextPivot));
        assertNull(book.seat("car"));
    }

    @Test
    void removedPassengersAndAForgottenDimensionDoNotHoldSeats() {
        PassengerBook book = new PassengerBook();
        Aabb hull = new Aabb(0.0, 10.0, 0.0, 24.0, 11.0, 6.0);
        Vec3 pivot = new Vec3(12.0, 10.5, 3.0);
        for (int i = 0; i < NestedCarry.MAX_PASSENGERS; i++) {
            Vec3 at = new Vec3(1.0 + i, 11.5, 2.0);
            assertNotNull(ride(book, "c" + i, false, hull, boxAround(at), at, pivot, pivot));
        }
        Vec3 ninth = new Vec3(1.0 + NestedCarry.MAX_PASSENGERS, 11.5, 2.0);
        assertNull(ride(book, "extra", false, hull, boxAround(ninth), ninth, pivot, pivot));
        assertEquals(NestedCarry.MAX_PASSENGERS, book.count("ship"));

        assertNull(book.apply(tick("c0", true, true, hull, boxAround(new Vec3(1.0, 11.5, 2.0)), new Vec3(1.0, 11.5, 2.0), pivot, pivot)));
        assertEquals(NestedCarry.MAX_PASSENGERS - 1, book.count("ship"));
        assertNotNull(ride(book, "extra", false, hull, boxAround(ninth), ninth, pivot, pivot));
        assertEquals(NestedCarry.MAX_PASSENGERS, book.count("ship"));

        book.retain(Set.of("extra"));
        assertEquals(1, book.count("ship"));
        assertNull(book.seat("c1"));

        Vec3 netherAt = new Vec3(3.0, 11.5, 2.0);
        book.apply(new PassengerBook.Tick(
                "nether-car", "nether-ship", NETHER, false, false, false,
                hull, boxAround(netherAt), netherAt, Rot.IDENTITY,
                pivot, pivot, new Vec3(0.0, 1.0, 0.0), 0.0, 0.0, 0.0
        ));
        book.forgetDimension(OVERWORLD);
        assertNull(book.seat("extra"));
        assertEquals(NETHER, book.seat("nether-car").dimension());
    }

    @Test
    void aBearingThatTurnsThroughSeveralRevolutionsDoesNotSpikeOnTheWrap() {
        double rawWrap = Math.toRadians(10.0 - 350.0);
        assertFalse(CarryGate.safeStep(0.0, rawWrap));
        double wrapped = wrapDegreesRadians(rawWrap);
        assertTrue(CarryGate.safeStep(0.0, wrapped));
        assertEquals(20.0, Math.toDegrees(wrapped), 0.05);

        Vec3 pivot = new Vec3(0.5, 64.5, 0.5);
        Vec3 axis = new Vec3(0.0, 1.0, 0.0);
        Vec3 ship = new Vec3(2.5, 65.0, 0.5);
        double radius = ship.sub(pivot).length();
        Vec3 local = DeckFollow.localOffset(ship, pivot, axis, 0.0);
        double placed = 0.0;
        Vec3 at = ship;
        for (int revolution = 0; revolution < 3; revolution++) {
            for (int deg = 0; deg < 360; deg += 10) {
                double next = Math.toRadians((deg + 10) % 360);
                double raw = next - placed;
                double step = wrapDegreesRadians(raw);
                if (deg == 350) {
                    assertFalse(CarryGate.safeStep(0.05, raw));
                }
                assertTrue(CarryGate.safeStep(0.05, step));
                assertEquals(10.0, Math.toDegrees(step), 0.05);
                at = DeckFollow.worldTarget(local, pivot, axis, next);
                assertEquals(radius, at.sub(pivot).length(), 1.0e-6);
                assertEquals(ship.y(), at.y(), 1.0e-9);
                placed = next;
            }
        }
        assertEquals(radius, at.sub(pivot).length(), 1.0e-6);
    }

    @Test
    void sideHitsAndNonDeckKindsDoNotCountAsAPlatform() {
        Aabb deck = new Aabb(0.0, 10.0, 0.0, 3.0, 11.0, 3.0);
        Vec3 side = new Vec3(3.4, 10.5, 1.5);
        assertFalse(DeckContact.rests(side, deck, CarryGate.CAPTURE_GAP));
        assertTrue(DeckContact.rests(new Vec3(1.5, 11.0, 1.5), deck, CarryGate.CAPTURE_GAP));
        assertNull(ContraptionKind.parse("train"));
        assertNull(ContraptionKind.parse("drill"));
        assertNull(ContraptionKind.parse("harvester"));
        assertNull(ContraptionKind.parse("plough"));
        assertEquals(ContraptionKind.GANTRY, ContraptionKind.parse("gantry"));
        assertEquals(ContraptionKind.ELEVATOR, ContraptionKind.parse("elevator"));
    }

    @Test
    void aWideHullIsSampledAcrossTheWholeBottomLayer() {
        assertEquals(1, RescueSite.columnStride(1, 256));
        assertEquals(1, RescueSite.columnStride(256, 256));
        assertEquals(2, RescueSite.columnStride(257, 256));
        assertEquals(2, RescueSite.columnStride(1024, 256));
        assertEquals(16, RescueSite.columnStride(256L * 256L, 256));
        assertEquals(1, RescueSite.columnStride(0, 256));

        assertEquals(1, RescueSite.columnStride(16, 16, 256));
        assertTrue(RescueSite.sampleCount(16, 16, RescueSite.columnStride(16, 16, 256)) <= 256);
        // The area stride of a 1000 by 1 floor is 2, which is 500 reads and drops the far half.
        assertEquals(2, RescueSite.columnStride(1000L, 256));
        int longStride = RescueSite.columnStride(1000, 1, 256);
        assertTrue(longStride > 2);
        assertTrue(RescueSite.sampleCount(1000, 1, longStride) <= 256);
        assertTrue(RescueSite.sampleCount(1000, 1000, RescueSite.columnStride(1000, 1000, 256)) <= 256);
    }

    @Test
    void aPortalIsDeferredAndAnotherDimensionIsNotARescueTarget() {
        assertTrue(RescueSite.defer(true));
        assertFalse(RescueSite.defer(false));

        SafePoseTracker tracker = new SafePoseTracker();
        RescueBounds world = RescueBounds.of(-64.0, -10_000.0, 100_000.0, AntiClipConfig.defaults());
        BodyState safe = new BodyState(
                "airship",
                new Vec3(4.0, 70.0, 0.0),
                Vec3.ZERO,
                new Aabb(3.2, 70.0, -1.2, 4.8, 71.4, 1.2),
                false
        );
        tracker.observe(OVERWORLD, 10, safe, world, AntiClipConfig.defaults(), false, true);
        assertNull(tracker.get(NETHER, "airship"));
        BodyState inNether = new BodyState(
                "airship",
                new Vec3(4.0, 70.0, 0.0),
                Vec3.ZERO,
                new Aabb(Double.NaN, 70.0, -1.2, 4.8, 71.4, 1.2),
                false
        );
        AntiClipStep step = new AntiClipStep();
        step.bind(NETHER, 12);
        step.tracker().put(OVERWORLD, "airship", tracker.get(OVERWORLD, "airship"));
        BodyState restored = step.step(List.of(inNether), List.of(), world, AntiClipConfig.defaults(), DT).get(0);
        assertFalse(restored.rescued());
    }

    @Test
    void aBuriedRescueTargetRisesToAirAndASolidColumnIsRefused() {
        boolean[] buried = new boolean[8];
        for (int i = 0; i < 3; i++) {
            buried[i] = true;
        }
        Vec3 rise = RescueSite.firstClear(RescueSite.offsets(), offset -> {
            int y = (int) Math.round(offset.y());
            return offset.x() == 0.0 && offset.z() == 0.0 && y < buried.length && buried[y];
        });
        assertEquals(0.0, rise.x(), 1.0e-9);
        assertEquals(3.0, rise.y(), 1.0e-9);

        Vec3 sideways = RescueSite.firstClear(RescueSite.offsets(), offset -> offset.x() == 0.0 && offset.z() == 0.0);
        assertEquals(1.0, sideways.x(), 1.0e-9);
        assertEquals(1.0, sideways.y(), 1.0e-9);

        assertNull(RescueSite.firstClear(RescueSite.offsets(), offset -> true));
    }

    @Test
    void rescueZeroesAnAbsurdVelocityAndAFallDoesNot() {
        AntiClipConfig config = AntiClipConfig.defaults();
        RescueBounds world = RescueBounds.of(-64.0, -10_000.0, 100_000.0, config);
        BodyState fallen = new BodyState(
                "car",
                new Vec3(12.0, -10_001.0, 0.0),
                new Vec3(0.0, -1.0e6, 0.0),
                new Aabb(11.2, -10_001.0, -1.2, 12.8, -9_999.6, 1.2),
                false
        );
        BodyState rescued = new AntiClipStep().step(List.of(fallen), List.of(), world, config, DT).get(0);
        assertTrue(rescued.rescued());
        assertEquals(0.0, rescued.velocity().length(), 1.0e-9);
        assertSame(Vec3.ZERO, VelocityClamp.afterRescue(fallen.velocity()));

        Vec3 falling = new Vec3(0.0, -12.0, 0.0);
        assertSame(falling, VelocityClamp.calm(falling));
        assertSame(Vec3.ZERO, VelocityClamp.calm(new Vec3(0.0, -1.0e6, 0.0)));
        assertSame(Vec3.ZERO, VelocityClamp.calm(new Vec3(Double.NaN, 0.0, 0.0)));
    }

    @Test
    void theMasterSwitchLeavesTheFeatureFlagsAlone() {
        AntiClipConfig config = AntiClipConfig.defaults();
        assertTrue(FailureGuards.engaged(config));
        AntiClipConfig killed = config.withEnabled(false);
        assertFalse(killed.enabled());
        assertTrue(killed.carry());
        assertTrue(killed.voidRescue());
        assertTrue(killed.feedColliders());
        assertFalse(FailureGuards.engaged(killed));
        assertFalse(FailureGuards.engaged(null));
    }

    @Test
    void aThrowingMixinCallbackDoesNotEscape() {
        assertFalse(FailureGuards.run(() -> {
            throw new IllegalStateException("missing target");
        }));
        assertTrue(FailureGuards.run(() -> {
        }));
        assertFalse(FailureGuards.run(null));
    }

    /** Float wrap matching {@code Mth.wrapDegrees}, which is what the bearing step uses. */
    private static double wrapDegreesRadians(double radians) {
        float degrees = (float) Math.toDegrees(radians);
        degrees %= 360.0f;
        if (degrees >= 180.0f) {
            degrees -= 360.0f;
        }
        if (degrees < -180.0f) {
            degrees += 360.0f;
        }
        return Math.toRadians(degrees);
    }

    private static Vec3 ride(
            PassengerBook book,
            String child,
            boolean piloting,
            Aabb hull,
            Aabb box,
            Vec3 position,
            Vec3 oldPivot,
            Vec3 pivot
    ) {
        PassengerBook.Placement placement = book.apply(tick(child, piloting, false, hull, box, position, oldPivot, pivot));
        return placement == null ? null : placement.position();
    }

    private static PassengerBook.Tick tick(
            String child,
            boolean piloting,
            boolean removed,
            Aabb hull,
            Aabb box,
            Vec3 position,
            Vec3 oldPivot,
            Vec3 pivot
    ) {
        return new PassengerBook.Tick(
                child,
                "ship",
                OVERWORLD,
                piloting,
                removed,
                false,
                hull,
                box,
                position,
                Rot.IDENTITY,
                oldPivot,
                pivot,
                new Vec3(0.0, 1.0, 0.0),
                0.0,
                0.0,
                0.0
        );
    }

    private static Aabb boxAround(Vec3 center) {
        return new Aabb(center.x() - 1.0, center.y() - 0.5, center.z() - 1.0, center.x() + 1.0, center.y() + 0.5, center.z() + 1.0);
    }
}
