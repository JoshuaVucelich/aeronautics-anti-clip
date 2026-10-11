package com.vucelich.sablesafelanding.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarryTest {
    private static final double DT = HeadlessLandingHarness.TICK_SECONDS;
    private static final RescueBounds WORLD = RescueBounds.of(-64.0, -10_000.0, 100_000.0, AntiClipConfig.defaults());

    @Test
    void carOnADescendingElevatorTakesThePlatformStep() {
        AntiClipConfig config = AntiClipConfig.defaults().withCarry(true).withFeedColliders(false);
        BodyState car = carAt(0.0, 11.0, 0.0);
        SupportMotion down = support("elevator", new Aabb(-2.0, 10.0, -2.0, 2.0, 11.0, 2.0), new Vec3(0.0, -0.2, 0.0), 0.0);
        SupportMotion up = support("elevator", new Aabb(-2.0, 10.0, -2.0, 2.0, 11.0, 2.0), new Vec3(0.0, 0.2, 0.0), 0.0);

        BodyState descended = step(car, List.of(down), config);
        assertEquals(10.8, descended.bounds().minY(), 1.0e-9);
        assertEquals(-4.0, descended.velocity().y(), 1.0e-9);

        BodyState ascended = step(car, List.of(up), config);
        assertEquals(11.2, ascended.bounds().minY(), 1.0e-9);
        assertEquals(4.0, ascended.velocity().y(), 1.0e-9);
    }

    @Test
    void feedAloneCarriesASmallStepAndBothOffLeavesTheCar() {
        BodyState car = carAt(0.0, 11.0, 0.0);
        SupportMotion elevator = support("elevator", new Aabb(-2.0, 10.0, -2.0, 2.0, 11.0, 2.0), new Vec3(0.0, -0.2, 0.0), 0.0);

        BodyState fed = step(car, List.of(elevator), AntiClipConfig.defaults().withCarry(false).withFeedColliders(true));
        assertEquals(10.8, fed.bounds().minY(), 1.0e-9);

        BodyState left = step(car, List.of(elevator), AntiClipConfig.defaults().withCarry(false).withFeedColliders(false));
        assertEquals(11.0, left.bounds().minY(), 1.0e-9);
        assertEquals(0.0, left.velocity().y(), 1.0e-9);
    }

    @Test
    void aLargeStepIsRefusedSoTheCarIsNotFlung() {
        AntiClipConfig config = AntiClipConfig.defaults().withCarry(true);
        BodyState car = carAt(0.0, 11.0, 0.0);
        SupportMotion jump = support("elevator", new Aabb(-2.0, 10.0, -2.0, 2.0, 11.0, 2.0), new Vec3(0.0, 4.0, 0.0), 0.0);
        BodyState stayed = step(car, List.of(jump), config);
        assertEquals(11.0, stayed.bounds().minY(), 1.0e-9);
        assertEquals(11.0, stayed.position().y(), 1.0e-9);
        assertEquals(0.0, stayed.velocity().y(), 1.0e-9);
    }

    @Test
    void bearingAppliesTangentialMotionAndRefusesAQuarterTurn() {
        AntiClipConfig config = AntiClipConfig.defaults().withCarry(true).withFeedColliders(false);
        BodyState car = new BodyState(
                "car",
                new Vec3(2.0, 0.5, 0.0),
                Vec3.ZERO,
                new Aabb(1.5, 0.0, -0.5, 2.5, 1.0, 0.5),
                false
        );
        double angle = Math.toRadians(10.0);
        SupportMotion bearing = new SupportMotion(
                "bearing",
                new Aabb(1.0, -0.5, -1.0, 3.0, 0.0, 1.0),
                Vec3.ZERO,
                Vec3.ZERO,
                new Vec3(0.0, 1.0, 0.0),
                angle
        );

        BodyState carried = step(car, List.of(bearing), config);
        Vec3 expected = new Vec3(2.0, 0.5, 0.0).rotate(new Vec3(0.0, 1.0, 0.0), angle);
        assertEquals(expected.x(), carried.position().x(), 1.0e-9);
        assertEquals(expected.y(), carried.position().y(), 1.0e-9);
        assertEquals(expected.z(), carried.position().z(), 1.0e-9);
        assertEquals((expected.x() - 2.0) * 20.0, carried.velocity().x(), 1.0e-9);
        assertEquals(expected.z() * 20.0, carried.velocity().z(), 1.0e-9);
        assertTrue(carried.position().sub(car.position()).length() < AntiClipStep.MAX_RIDE_STEP);

        SupportMotion quarter = new SupportMotion(
                "bearing",
                new Aabb(1.0, -0.5, -1.0, 3.0, 0.0, 1.0),
                Vec3.ZERO,
                Vec3.ZERO,
                new Vec3(0.0, 1.0, 0.0),
                Math.PI / 2.0
        );
        BodyState stayed = step(car, List.of(quarter), config);
        assertEquals(2.0, stayed.position().x(), 1.0e-9);
        assertEquals(0.0, stayed.position().z(), 1.0e-9);
        assertEquals(0.0, stayed.velocity().z(), 1.0e-9);
    }

    @Test
    void pistonAndGantryCarryASmallHorizontalStep() {
        AntiClipConfig config = AntiClipConfig.defaults().withCarry(true).withFeedColliders(false);
        BodyState onPiston = carAt(0.0, 5.0, 0.0);
        BodyState onGantry = new BodyState("gantry-car", new Vec3(0.0, 8.0, 0.0), Vec3.ZERO, new Aabb(-0.8, 8.0, -1.2, 0.8, 9.4, 1.2), false);
        SupportMotion piston = support("piston", new Aabb(-2.0, 4.0, -2.0, 2.0, 5.0, 2.0), new Vec3(0.2, 0.0, 0.0), 0.0);
        SupportMotion gantry = support("gantry", new Aabb(-2.0, 7.0, -2.0, 2.0, 8.0, 2.0), new Vec3(0.0, 0.0, -0.15), 0.0);

        List<BodyState> carried = new AntiClipStep().step(List.of(onPiston, onGantry), List.of(piston, gantry), WORLD, config, DT);
        assertEquals(0.2, carried.get(0).position().x(), 1.0e-9);
        assertEquals(5.0, carried.get(0).bounds().minY(), 1.0e-9);
        assertEquals(4.0, carried.get(0).velocity().x(), 1.0e-9);
        assertEquals(-0.15, carried.get(1).position().z(), 1.0e-9);
        assertEquals(8.0, carried.get(1).bounds().minY(), 1.0e-9);

        SupportMotion flung = support("piston", new Aabb(-2.0, 4.0, -2.0, 2.0, 5.0, 2.0), new Vec3(3.0, 0.0, 0.0), 0.0);
        BodyState stayed = step(onPiston, List.of(flung), config);
        assertEquals(0.0, stayed.position().x(), 1.0e-9);
    }

    @Test
    void onlyOneContraptionDeltaIsApplied() {
        BodyState car = carAt(0.0, 11.0, 0.0);
        SupportMotion first = support("elevator", new Aabb(-2.0, 10.0, -2.0, 2.0, 11.0, 2.0), new Vec3(0.0, 0.2, 0.0), 0.0);
        SupportMotion second = support("other", new Aabb(-2.0, 10.0, -2.0, 2.0, 11.0, 2.0), new Vec3(0.0, 0.5, 0.0), 0.0);

        BodyState carried = step(car, List.of(first, second), AntiClipConfig.defaults().withCarry(true).withFeedColliders(false));
        assertEquals(11.2, carried.bounds().minY(), 1.0e-9);
    }

    @Test
    void aBodyThatIsNotRestingIsLeftBehind() {
        BodyState car = carAt(0.0, 12.5, 0.0);
        SupportMotion elevator = support("elevator", new Aabb(-2.0, 10.0, -2.0, 2.0, 11.0, 2.0), new Vec3(0.0, 0.2, 0.0), 0.0);
        BodyState stayed = step(car, List.of(elevator), AntiClipConfig.defaults().withCarry(true));
        assertEquals(12.5, stayed.bounds().minY(), 1.0e-9);
    }

    @Test
    void anUpwardVelocityIsNotPinnedToThePlatform() {
        AntiClipConfig config = AntiClipConfig.defaults().withCarry(true).withFeedColliders(false);
        BodyState lifting = carAt(0.0, 11.0, 0.0).moved(Vec3.ZERO, new Vec3(0.0, 2.0, 0.0));
        SupportMotion elevator = support("elevator", new Aabb(-2.0, 10.0, -2.0, 2.0, 11.0, 2.0), new Vec3(0.0, 0.05, 0.0), 0.0);
        BodyState free = step(lifting, List.of(elevator), config);
        assertEquals(11.0, free.bounds().minY(), 1.0e-9);
        assertEquals(2.0, free.velocity().y(), 1.0e-9);
    }

    private static BodyState step(BodyState body, List<SupportMotion> supports, AntiClipConfig config) {
        return new AntiClipStep().step(List.of(body), supports, WORLD, config, DT).get(0);
    }

    private static BodyState carAt(double x, double minY, double z) {
        return new BodyState(
                "car",
                new Vec3(x, minY, z),
                Vec3.ZERO,
                new Aabb(x - 0.8, minY, z - 1.2, x + 0.8, minY + 1.4, z + 1.2),
                false
        );
    }

    private static SupportMotion support(String id, Aabb volume, Vec3 translation, double angle) {
        return new SupportMotion(id, volume, translation, Vec3.ZERO, new Vec3(0.0, 1.0, 0.0), angle);
    }
}
