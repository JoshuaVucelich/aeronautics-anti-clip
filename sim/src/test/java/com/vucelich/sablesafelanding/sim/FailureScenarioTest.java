package com.vucelich.sablesafelanding.sim;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(NestedCarry.passenger(ship, car));
        assertFalse(NestedCarry.passenger(ship, sibling));
        assertFalse(NestedCarry.passenger(ship, below));

        Map<String, Aabb> others = new LinkedHashMap<>();
        others.put("ship", ship);
        others.put("car", car);
        others.put("sibling", sibling);
        others.put("locked", new Aabb(1.0, 14.0, 1.0, 2.0, 15.0, 2.0));
        List<String> passengers = NestedCarry.passengers(ship, others, "ship", Set.of("locked"));
        assertEquals(List.of("car"), passengers);
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
}
