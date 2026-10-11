package com.vucelich.sablesafelanding.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarryTest {
    private static final double DT = HeadlessLandingHarness.TICK_SECONDS;
    private static final RescueBounds WORLD = RescueBounds.of(-64.0, -10_000.0, 100_000.0, AntiClipConfig.defaults());

    @Test
    void carOnAJumpingElevatorStaysOnThePlatform() {
        AntiClipConfig config = AntiClipConfig.defaults().withCarry(true);
        BodyState car = carAt(0.0, 11.0, 0.0);
        SupportMotion elevator = support("elevator", new Aabb(-2.0, 10.0, -2.0, 2.0, 11.0, 2.0), new Vec3(0.0, 4.0, 0.0), 0.0);

        BodyState carried = step(car, List.of(elevator), config);
        assertEquals(15.0, carried.bounds().minY(), 1.0e-9);
        assertEquals(15.0, carried.position().y(), 1.0e-9);
        assertTrue(carried.bounds().minY() >= 11.0 + 4.0 - 1.0e-9);

        BodyState leftBehind = step(car, List.of(elevator), config.withCarry(false));
        assertEquals(11.0, leftBehind.bounds().minY(), 1.0e-9);
    }

    @Test
    void bearingRotatesTheRestingBodyAroundThePivot() {
        AntiClipConfig config = AntiClipConfig.defaults().withCarry(true);
        BodyState car = new BodyState(
                "car",
                new Vec3(2.0, 0.5, 0.0),
                Vec3.ZERO,
                new Aabb(1.5, 0.0, -0.5, 2.5, 1.0, 0.5),
                false
        );
        SupportMotion bearing = new SupportMotion(
                "bearing",
                new Aabb(1.0, -0.5, -1.0, 3.0, 0.0, 1.0),
                Vec3.ZERO,
                Vec3.ZERO,
                new Vec3(0.0, 1.0, 0.0),
                Math.PI / 2.0
        );

        BodyState carried = step(car, List.of(bearing), config);
        assertEquals(0.0, carried.position().x(), 1.0e-9);
        assertEquals(0.5, carried.position().y(), 1.0e-9);
        assertEquals(-2.0, carried.position().z(), 1.0e-9);
        assertEquals(0.0, carried.bounds().center().x(), 1.0e-6);
        assertEquals(-2.0, carried.bounds().center().z(), 1.0e-6);
    }

    @Test
    void pistonAndGantryCarryHorizontally() {
        AntiClipConfig config = AntiClipConfig.defaults().withCarry(true);
        BodyState onPiston = carAt(0.0, 5.0, 0.0).moved(Vec3.ZERO, Vec3.ZERO);
        BodyState onGantry = new BodyState("gantry-car", new Vec3(0.0, 8.0, 0.0), Vec3.ZERO, new Aabb(-0.8, 8.0, -1.2, 0.8, 9.4, 1.2), false);
        SupportMotion piston = support("piston", new Aabb(-2.0, 4.0, -2.0, 2.0, 5.0, 2.0), new Vec3(3.0, 0.0, 0.0), 0.0);
        SupportMotion gantry = support("gantry", new Aabb(-2.0, 7.0, -2.0, 2.0, 8.0, 2.0), new Vec3(0.0, 0.0, -2.5), 0.0);

        List<BodyState> carried = new AntiClipStep().step(List.of(onPiston, onGantry), List.of(piston, gantry), WORLD, config, DT);
        assertEquals(3.0, carried.get(0).position().x(), 1.0e-9);
        assertEquals(5.0, carried.get(0).bounds().minY(), 1.0e-9);
        assertEquals(-2.5, carried.get(1).position().z(), 1.0e-9);
        assertEquals(8.0, carried.get(1).bounds().minY(), 1.0e-9);
    }

    @Test
    void onlyOneContraptionDeltaIsApplied() {
        BodyState car = carAt(0.0, 11.0, 0.0);
        SupportMotion first = support("elevator", new Aabb(-2.0, 10.0, -2.0, 2.0, 11.0, 2.0), new Vec3(0.0, 4.0, 0.0), 0.0);
        SupportMotion second = support("other", new Aabb(-2.0, 10.0, -2.0, 2.0, 11.0, 2.0), new Vec3(0.0, 10.0, 0.0), 0.0);

        BodyState carried = step(car, List.of(first, second), AntiClipConfig.defaults().withCarry(true));
        assertEquals(15.0, carried.bounds().minY(), 1.0e-9);
    }

    @Test
    void aBodyThatIsNotRestingIsLeftBehind() {
        BodyState car = carAt(0.0, 12.0, 0.0);
        SupportMotion elevator = support("elevator", new Aabb(-2.0, 10.0, -2.0, 2.0, 11.0, 2.0), new Vec3(0.0, 4.0, 0.0), 0.0);
        BodyState stayed = step(car, List.of(elevator), AntiClipConfig.defaults().withCarry(true));
        assertEquals(12.0, stayed.bounds().minY(), 1.0e-9);
    }

    @Test
    void anUpwardVelocityIsNotPinnedToThePlatform() {
        AntiClipConfig config = AntiClipConfig.defaults().withCarry(true);
        BodyState lifting = carAt(0.0, 11.0, 0.0).moved(Vec3.ZERO, new Vec3(0.0, 2.0, 0.0));
        SupportMotion elevator = support("elevator", new Aabb(-2.0, 10.0, -2.0, 2.0, 11.0, 2.0), new Vec3(0.0, 4.0, 0.0), 0.0);
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
