package com.vucelich.sablesafelanding.sim;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HardeningTest {
    private static final double DT = HeadlessLandingHarness.TICK_SECONDS;
    private static final RescueBounds WORLD = RescueBounds.of(-64.0, -10_000.0, 100_000.0, AntiClipConfig.defaults());

    @Test
    void aRowOfUnitCubesMergesIntoOneBox() {
        List<Aabb> cubes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            cubes.add(new Aabb(0.0, 0.0, i, 1.0, 1.0, i + 1.0));
        }
        List<Aabb> merged = AabbMerger.merge(cubes);
        assertEquals(1, merged.size());
        assertEquals(0.0, merged.get(0).minZ());
        assertEquals(5.0, merged.get(0).maxZ());
        assertEquals(1.0, merged.get(0).maxX());
    }

    @Test
    void spatialHashTestsFarFewerPairsThanTheFullProduct() {
        List<SupportMotion> supports = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            double x = i * 64.0;
            supports.add(new SupportMotion("s" + i, new Aabb(x, 0.0, 0.0, x + 1.0, 1.0, 1.0), Vec3.ZERO, Vec3.ZERO, new Vec3(0.0, 1.0, 0.0), 0.0));
        }
        ColliderIndex index = new ColliderIndex(supports, 16);
        for (int i = 0; i < 50; i++) {
            double x = i * 64.0;
            List<SupportMotion> nearby = index.nearby(new Aabb(x, 1.0, 0.0, x + 1.0, 2.0, 1.0), 0);
            assertEquals(1, nearby.size());
        }
        assertTrue(index.pairChecks() < 400, "pair checks were " + index.pairChecks());
        assertTrue(index.pairChecks() < 50 * 200);
    }

    @Test
    void pairBudgetStopsTheSearch() {
        List<SupportMotion> supports = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            supports.add(new SupportMotion("s" + i, new Aabb(i, 0.0, 0.0, i + 0.5, 1.0, 1.0), Vec3.ZERO, Vec3.ZERO, new Vec3(0.0, 1.0, 0.0), 0.0));
        }
        ColliderIndex index = new ColliderIndex(supports, 16);
        List<SupportMotion> nearby = index.nearby(new Aabb(0.0, 0.0, 0.0, 10.0, 1.0, 1.0), 3);
        assertEquals(3, nearby.size());
        assertEquals(3, index.pairChecks());
    }

    @Test
    void nanPoseFailsOpenAndIsNotRemembered() {
        BodyState broken = new BodyState(
                "car",
                new Vec3(Double.NaN, 10.0, 0.0),
                Vec3.ZERO,
                new Aabb(-0.8, 10.0, -1.2, 0.8, 11.4, 1.2),
                false
        );
        AntiClipStep step = new AntiClipStep();
        BodyState rescued = step.step(List.of(broken), List.of(), WORLD, AntiClipConfig.defaults(), DT).get(0);
        assertFalse(rescued.rescued());
        assertTrue(Double.isNaN(rescued.position().x()));
        assertEquals(0, step.tracker().size());
    }

    @Test
    void eachFeatureSwitchDisablesOnlyThatFeature() {
        AntiClipConfig config = AntiClipConfig.defaults();
        assertFalse(config.withVoidRescue(false).voidRescue());
        assertFalse(config.withFeedColliders(false).feedColliders());
        assertFalse(config.withCarry(false).carry());
        assertFalse(config.withSweep(false).sweep());
        assertTrue(config.withSweep(false).carry());
        assertTrue(config.withCarry(true).carry());
        assertEquals(4096, config.maxPairChecks());

        BodyState car = new BodyState("car", new Vec3(0.0, 11.0, 0.0), Vec3.ZERO, new Aabb(-0.8, 11.0, -1.2, 0.8, 12.4, 1.2), false);
        SupportMotion elevator = new SupportMotion("elevator", new Aabb(-2.0, 10.0, -2.0, 2.0, 11.0, 2.0), new Vec3(0.0, 4.0, 0.0), Vec3.ZERO, new Vec3(0.0, 1.0, 0.0), 0.0);
        BodyState stayed = new AntiClipStep().step(List.of(car), List.of(elevator), WORLD, config.withCarry(false), DT).get(0);
        assertEquals(11.0, stayed.bounds().minY(), 1.0e-9);
    }

    @Test
    void logLimiterDropsBursts() {
        RateLimiter limiter = new RateLimiter(1_000_000_000L);
        assertTrue(limiter.tryAcquire(0));
        assertFalse(limiter.tryAcquire(10));
        assertTrue(limiter.tryAcquire(1_000_000_000L));
    }
}
