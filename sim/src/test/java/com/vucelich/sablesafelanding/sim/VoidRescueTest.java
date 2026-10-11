package com.vucelich.sablesafelanding.sim;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoidRescueTest {
    private static final double DT = HeadlessLandingHarness.TICK_SECONDS;
    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void droppedCarIsShiftedJustInsideTheRemoveBound() {
        AntiClipConfig config = AntiClipConfig.defaults();
        RescueBounds world = RescueBounds.of(-64.0, -10_000.0, 100_000.0, config);
        BodyState fallen = car("car", 12.0, -10_001.0, new Vec3(1.0, -40.0, 0.0));

        BodyState rescued = new AntiClipStep().step(List.of(fallen), List.of(), world, config, DT).get(0);

        assertTrue(rescued.rescued());
        assertEquals(world.floor(), rescued.bounds().minY(), 1.0e-9);
        assertEquals(12.0, rescued.position().x(), 1.0e-9);
        assertEquals(0.0, rescued.velocity().y());
        assertFalse(world.sableWouldRemove(rescued.bounds()));
        assertFalse(world.needsRescue(rescued.bounds()));
    }

    @Test
    void rescueStartsAtTheRemoveBoundNotAtBuildHeight() {
        AntiClipConfig config = AntiClipConfig.defaults();
        RescueBounds world = RescueBounds.of(-64.0, -10_000.0, 100_000.0, config);
        assertEquals(1.0, config.approachMargin());
        assertEquals(-9_999.0, world.floor());
        assertTrue(world.needsRescue(car("car", 0.0, -9_999.5, Vec3.ZERO).bounds()));
        assertFalse(world.needsRescue(car("car", 0.0, -9_999.0, Vec3.ZERO).bounds()));
        assertFalse(world.sableWouldRemove(car("car", 0.0, -9_999.5, Vec3.ZERO).bounds()));
        assertTrue(world.sableWouldRemove(car("car", 0.0, -10_000.1, Vec3.ZERO).bounds()));
        assertFalse(world.needsRescue(car("car", 0.0, -80.0, Vec3.ZERO).bounds()));

        AntiClipStep step = new AntiClipStep();
        step.bind(OVERWORLD, 10);
        step.step(List.of(car("car", 40.0, 70.0, Vec3.ZERO)), List.of(), world, config, DT);
        BodyState rescued = step.step(List.of(car("car", 40.0, -10_001.0, new Vec3(0.0, -30.0, 0.0))), List.of(), world, config, DT).get(0);

        assertTrue(rescued.rescued());
        assertEquals(world.floor(), rescued.bounds().minY(), 1.0e-9);
        assertEquals(40.0, rescued.position().x(), 1.0e-9);
        assertEquals(0.0, rescued.velocity().y());
    }

    @Test
    void invalidPoseFailsOpenInsteadOfInventingAnOriginBox() {
        AntiClipConfig config = AntiClipConfig.defaults();
        RescueBounds world = RescueBounds.of(-64.0, -10_000.0, 100_000.0, config);
        BodyState broken = new BodyState(
                "car",
                new Vec3(Double.NaN, 10.0, 0.0),
                Vec3.ZERO,
                new Aabb(-0.8, 10.0, -1.2, 0.8, 11.4, 1.2),
                false
        );

        BodyState result = new AntiClipStep().step(List.of(broken), List.of(), world, config, DT).get(0);

        assertFalse(result.rescued());
        assertTrue(Double.isNaN(result.position().x()));
        assertEquals(10.0, result.bounds().minY());
    }

    @Test
    void recentNearbyLastSafeIsUsedOnlyWhenThePoseIsInvalid() {
        AntiClipConfig config = AntiClipConfig.defaults();
        RescueBounds world = RescueBounds.of(-64.0, -10_000.0, 100_000.0, config);
        AntiClipStep step = new AntiClipStep();
        step.bind(OVERWORLD, 100);
        BodyState safe = car("car", 4.0, 70.0, Vec3.ZERO);
        step.step(List.of(safe), List.of(), world, config, DT);

        BodyState broken = new BodyState(
                "car",
                new Vec3(4.0, 70.0, 0.0),
                Vec3.ZERO,
                new Aabb(Double.NaN, 70.0, -1.2, 0.8, 71.4, 1.2),
                false
        );
        step.bind(OVERWORLD, 120);
        BodyState restored = step.step(List.of(broken), List.of(), world, config, DT).get(0);
        assertTrue(restored.rescued());
        assertEquals(70.0, restored.bounds().minY(), 1.0e-9);
        assertEquals(4.0, restored.position().x(), 1.0e-9);

        step.bind(OVERWORLD, 100 + SafePoseTracker.MAX_AGE + 1);
        BodyState stale = step.step(List.of(broken), List.of(), world, config, DT).get(0);
        assertFalse(stale.rescued());

        step.bind("minecraft:the_nether", 120);
        BodyState otherDimension = step.step(List.of(broken), List.of(), world, config, DT).get(0);
        assertFalse(otherDimension.rescued());

        step.bind(OVERWORLD, 120);
        BodyState far = new BodyState(
                "car",
                new Vec3(4.0 + SafePoseTracker.MAX_DISTANCE + 5.0, 70.0, 0.0),
                Vec3.ZERO,
                new Aabb(Double.NaN, 70.0, -1.2, 0.8, 71.4, 1.2),
                false
        );
        BodyState distant = step.step(List.of(far), List.of(), world, config, DT).get(0);
        assertFalse(distant.rescued());
    }

    @Test
    void bodyJustAboveBedrockIsNotRescued() {
        AntiClipConfig config = AntiClipConfig.defaults();
        RescueBounds world = RescueBounds.of(-64.0, -10_000.0, 100_000.0, config);
        BodyState standing = car("car", 0.0, -63.0, Vec3.ZERO);

        BodyState result = new AntiClipStep().step(List.of(standing), List.of(), world, config, DT).get(0);

        assertFalse(result.rescued());
        assertEquals(-63.0, result.bounds().minY());
    }

    @Test
    void rescueDisabledLeavesTheBodyEligibleForDeletion() {
        AntiClipConfig config = AntiClipConfig.defaults().withVoidRescue(false);
        RescueBounds world = RescueBounds.of(-64.0, -10_000.0, 100_000.0, config);
        BodyState doomed = car("car", 0.0, -10001.0, new Vec3(0.0, -50.0, 0.0));

        BodyState result = new AntiClipStep().step(List.of(doomed), List.of(), world, config, DT).get(0);

        assertFalse(result.rescued());
        assertEquals(-10001.0, result.bounds().minY());
        assertTrue(world.sableWouldRemove(result.bounds()));
    }

    @Test
    void fastFallIsNotRememberedUnlessTheBodyIsResting() {
        AntiClipConfig config = AntiClipConfig.defaults();
        RescueBounds world = RescueBounds.of(-64.0, -10_000.0, 100_000.0, config);
        AntiClipStep falling = new AntiClipStep();
        falling.bind(OVERWORLD, 1);
        falling.step(List.of(car("car", 0.0, 70.0, new Vec3(0.0, -10.0, 0.0))), List.of(), world, config, DT);
        BodyState clamped = falling.step(List.of(car("car", 3.0, -10_001.0, new Vec3(0.0, -40.0, 0.0))), List.of(), world, config, DT).get(0);
        assertEquals(world.floor(), clamped.bounds().minY(), 1.0e-9);
        assertEquals(3.0, clamped.position().x(), 1.0e-9);

        SupportMotion platform = new SupportMotion(
                "elevator",
                new Aabb(-2.0, 69.0, -2.0, 2.0, 70.0, 2.0),
                Vec3.ZERO,
                Vec3.ZERO,
                new Vec3(0.0, 1.0, 0.0),
                0.0
        );
        AntiClipStep supported = new AntiClipStep();
        supported.bind(OVERWORLD, 1);
        supported.step(List.of(car("car", 0.0, 70.0, new Vec3(0.0, -10.0, 0.0))), List.of(platform), world, config, DT);
        assertEquals(1, supported.tracker().size());
    }

    @Test
    void inPlaceCorrectionKeepsOrientationAndForgetsRemovedBodies() {
        AntiClipConfig config = AntiClipConfig.defaults();
        RescueBounds world = RescueBounds.of(-64.0, -10_000.0, 100_000.0, config);
        Rot yaw = new Rot(0.0, 0.70710678118, 0.0, 0.70710678118);
        BodyState fallen = new BodyState("car", new Vec3(8.0, -10_001.0, 0.0), Vec3.ZERO, boxAt(8.0, -10_001.0), yaw, false);
        AntiClipStep step = new AntiClipStep();
        step.bind(OVERWORLD, 5);
        BodyState restored = step.step(List.of(fallen), List.of(), world, config, DT).get(0);
        assertEquals(yaw, restored.orientation());
        assertEquals(8.0, restored.position().x(), 1.0e-9);

        step.step(List.of(car("car", 0.0, 70.0, Vec3.ZERO)), List.of(), world, config, DT);
        step.retainDimension(OVERWORLD, Set.of("car"));
        assertEquals(1, step.tracker().size());
        step.retainDimension(OVERWORLD, Set.of());
        assertEquals(0, step.tracker().size());
    }

    @Test
    void bodyAboveTheRemoveCeilingIsBroughtBackInside() {
        AntiClipConfig config = AntiClipConfig.defaults();
        RescueBounds world = RescueBounds.of(-64.0, -10_000.0, 100_000.0, config);
        BodyState high = car("car", 2.0, 99_999.0, new Vec3(0.0, 20.0, 0.0));
        assertTrue(world.needsRescue(high.bounds()));

        BodyState rescued = new AntiClipStep().step(List.of(high), List.of(), world, config, DT).get(0);
        assertTrue(rescued.rescued());
        assertEquals(world.ceiling(), rescued.bounds().maxY(), 1.0e-6);
        assertEquals(2.0, rescued.position().x(), 1.0e-9);
        assertFalse(world.sableWouldRemove(rescued.bounds()));
    }

    private static BodyState car(String id, double x, double minY, Vec3 velocity) {
        return new BodyState(id, new Vec3(x, minY, 0.0), velocity, boxAt(x, minY), false);
    }

    private static Aabb boxAt(double x, double minY) {
        return new Aabb(x - 0.8, minY, -1.2, x + 0.8, minY + 1.4, 1.2);
    }
}
