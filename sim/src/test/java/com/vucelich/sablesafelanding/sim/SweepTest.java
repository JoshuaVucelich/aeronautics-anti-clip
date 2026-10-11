package com.vucelich.sablesafelanding.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SweepTest {
    private static final double DT = HeadlessLandingHarness.TICK_SECONDS;
    private static final RescueBounds WORLD = RescueBounds.of(-64.0, -10_000.0, 100_000.0, AntiClipConfig.defaults());

    @Test
    void fastBodyDoesNotTunnelAThinPlatform() {
        AntiClipConfig config = AntiClipConfig.defaults().withSweep(true);
        Aabb platform = new Aabb(-4.0, 10.0, -4.0, 4.0, 10.2, 4.0);
        BodyState fast = new BodyState(
                "car",
                new Vec3(0.0, 30.0, 0.0),
                new Vec3(0.0, -40.0 / DT, 0.0),
                new Aabb(-0.8, 30.0, -1.2, 0.8, 31.4, 1.2),
                false
        );
        BodyState tunneled = fast.moved(fast.velocity().mul(DT), fast.velocity());
        assertFalse(tunneled.bounds().intersects(platform), "a discrete overlap test misses the slab");
        assertTrue(tunneled.bounds().maxY() < platform.minY());

        SupportMotion support = new SupportMotion("slab", platform, Vec3.ZERO, Vec3.ZERO, new Vec3(0.0, 1.0, 0.0), 0.0);
        BodyState stopped = new AntiClipStep().step(List.of(fast.withSolidContact(true)), List.of(support), WORLD, config, DT).get(0);

        double sunk = platform.maxY() - stopped.bounds().minY();
        assertTrue(stopped.bounds().minY() > platform.minY(), "the car must stay above the underside of the slab");
        assertTrue(sunk <= config.maxPenetration() + 1.0e-6, "remaining penetration was " + sunk);
        assertTrue(stopped.velocity().y() >= 0.0);
    }

    @Test
    void existingPenetrationIsPushedOutToTheLimit() {
        AntiClipConfig config = AntiClipConfig.defaults().withSweep(true);
        Aabb platform = new Aabb(-4.0, 9.0, -4.0, 4.0, 10.2, 4.0);
        BodyState sunk = new BodyState(
                "car",
                new Vec3(0.0, 10.0, 0.0),
                Vec3.ZERO,
                new Aabb(-0.8, 10.0, -1.2, 0.8, 11.4, 1.2),
                false
        );
        assertTrue(platform.maxY() - sunk.bounds().minY() > config.maxPenetration());

        SupportMotion support = new SupportMotion("slab", platform, Vec3.ZERO, Vec3.ZERO, new Vec3(0.0, 1.0, 0.0), 0.0);
        BodyState separated = new AntiClipStep().step(List.of(sunk.withSolidContact(true)), List.of(support), WORLD, config, DT).get(0);
        double overlap = platform.maxY() - separated.bounds().minY();
        assertTrue(overlap <= config.maxPenetration() + 1.0e-6, "overlap was " + overlap);
        assertTrue(overlap >= -1.0e-6);
    }

    @Test
    void sweepDisabledLeavesTheTunnelUnchecked() {
        AntiClipConfig config = AntiClipConfig.defaults().withSweep(false);
        Aabb platform = new Aabb(-4.0, 10.0, -4.0, 4.0, 10.2, 4.0);
        BodyState fast = new BodyState(
                "car",
                new Vec3(0.0, 30.0, 0.0),
                new Vec3(0.0, -40.0 / DT, 0.0),
                new Aabb(-0.8, 30.0, -1.2, 0.8, 31.4, 1.2),
                false
        );
        BodyState result = new AntiClipStep().step(List.of(fast), List.of(
                new SupportMotion("slab", platform, Vec3.ZERO, Vec3.ZERO, new Vec3(0.0, 1.0, 0.0), 0.0)
        ), WORLD, config, DT).get(0);
        assertTrue(result.bounds().minY() == 30.0);
    }

    @Test
    void boundingBoxOverlapWithoutBlockContactIsLeftAlone() {
        AntiClipConfig config = AntiClipConfig.defaults().withSweep(true);
        Aabb platform = new Aabb(-4.0, 9.0, -4.0, 4.0, 10.2, 4.0);
        BodyState sunk = new BodyState(
                "car",
                new Vec3(0.0, 10.0, 0.0),
                new Vec3(0.0, -4.0, 0.0),
                new Aabb(-0.8, 10.0, -1.2, 0.8, 11.4, 1.2),
                false
        );
        BodyState result = new AntiClipStep().step(List.of(sunk), List.of(
                new SupportMotion("slab", platform, Vec3.ZERO, Vec3.ZERO, new Vec3(0.0, 1.0, 0.0), 0.0)
        ), WORLD, config, DT).get(0);
        assertEquals(10.0, result.bounds().minY(), 1.0e-9);
        assertEquals(-4.0, result.velocity().y(), 1.0e-9);
    }
}
