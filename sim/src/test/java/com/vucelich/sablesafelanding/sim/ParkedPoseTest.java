package com.vucelich.sablesafelanding.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParkedPoseTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final long STRIDE = 10;

    private static void park(double[] slot, long time, double y) {
        ParkedPose.write(slot, time, 25.5, y, 8.5, 25.0, y - 0.5, 8.0, 26.0, y + 0.5, 9.0, 0, 0, 0, 1);
    }

    @Test
    void writesAtMostOncePerStride() {
        double[] slot = ParkedPose.fresh();
        assertTrue(ParkedPose.due(slot, 0, STRIDE));
        park(slot, 100, -39.5);
        for (long t = 100; t < 110; t++) {
            assertFalse(ParkedPose.due(slot, t, STRIDE), "tick " + t);
        }
        assertTrue(ParkedPose.due(slot, 110, STRIDE));
        assertTrue(ParkedPose.due(slot, 50, STRIDE), "a clock that went back must not freeze the slot");
    }

    @Test
    void onlyStableFinitePosesAboveBuildHeightAreEligible() {
        double fall = AntiClipConfig.defaults().stableFallSpeed();
        assertTrue(ParkedPose.eligible(0, -39.5, 0, -40, -39, -0.3, -64, fall));
        assertFalse(ParkedPose.eligible(0, -39.5, 0, -40, -39, -fall - 0.1, -64, fall), "falling fast");
        assertFalse(ParkedPose.eligible(0, -80, 0, -80.5, -79.5, 0, -64, fall), "below min build");
        assertFalse(ParkedPose.eligible(Double.NaN, -39.5, 0, -40, -39, 0, -64, fall), "not finite");
        assertFalse(ParkedPose.eligible(0, -39.5, 0, -40, Double.POSITIVE_INFINITY, 0, -64, fall), "not finite box");
    }

    @Test
    void onlyANewerPoseReplacesTheTrackers() {
        double[] slot = ParkedPose.fresh();
        assertNull(ParkedPose.newerThan(slot, null), "never written");
        park(slot, 200, -39.5);
        PoseSnapshot older = new PoseSnapshot(new Vec3(0, 0, 0), new Aabb(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5), Rot.IDENTITY, 150);
        PoseSnapshot newer = new PoseSnapshot(new Vec3(0, 0, 0), new Aabb(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5), Rot.IDENTITY, 250);
        assertNotNull(ParkedPose.newerThan(slot, null));
        assertEquals(200, ParkedPose.newerThan(slot, older).time());
        assertNull(ParkedPose.newerThan(slot, newer));
    }

    /**
     * The rc.9 failure: a car parked on the surface outside every rescue band, then pushed through
     * bedrock. With only in-band poses the tracker is empty and bedrock rescue cannot restore it.
     * The parked record seeds the tracker, and the bedrock path accepts it.
     */
    @Test
    void parkedPoseSeedsTheTrackerForBedrockRescue() {
        AntiClipConfig config = AntiClipConfig.defaults();
        RescueBounds world = RescueBounds.of(-64.0, -10_000.0, 100_000.0, config);
        SafePoseTracker tracker = new SafePoseTracker();
        double[] slot = ParkedPose.fresh();
        assertTrue(ParkedPose.eligible(25.5, -39.5, 8.5, -40, -39, 0, world.minBuildHeight(), config.stableFallSpeed()));
        park(slot, 1_000, -39.5);

        long rescueAt = 1_040;
        assertNull(tracker.get(OVERWORLD, "car"));
        PoseSnapshot seeded = ParkedPose.newerThan(slot, tracker.get(OVERWORLD, "car"));
        tracker.put(OVERWORLD, "car", seeded);

        PoseSnapshot saved = tracker.get(OVERWORLD, "car");
        assertSame(seeded, saved);
        assertTrue(tracker.usableRecent(saved, world, rescueAt));
        assertEquals(-40.0, saved.bounds().minY(), 1.0e-9);
        assertFalse(tracker.usableRecent(saved, world, 1_000 + SafePoseTracker.MAX_AGE + 1), "a stale record is not used");
    }
}
