package com.vucelich.sablesafelanding.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessBaselineTest {
    @Test
    void configDefaultsMatchTheShipTargets() {
        AntiClipConfig config = AntiClipConfig.defaults();
        assertTrue(config.voidRescue());
        assertTrue(config.feedColliders());
        assertFalse(config.carry());
        assertFalse(config.sweep());
        assertTrue(config.bedrockRescue());
        assertEquals(32.0, config.bedrockMargin());
        assertEquals(40, config.bedrockTicks());
        assertEquals(1.0, config.approachMargin());
        assertEquals(0.05, config.maxPenetration());
        assertEquals(-32.0, config.gravity());
    }

    @Test
    void droppedBodyFallsWhenProtectionIsOff() {
        HeadlessLandingHarness.DropReport report = HeadlessLandingHarness.dropWithoutProtection(
                AntiClipConfig.defaults(), 1.0);
        assertEquals(20, report.ticks());
        assertFalse(report.minecraftLoaded(), "the harness must run without Minecraft");
        assertFalse(report.end().rescued());
        assertTrue(report.end().bounds().minY() < report.start().bounds().minY() - 10.0,
                "one second of gravity should drop the body well clear of y=80");
        assertTrue(report.end().velocity().y() < -20.0);
    }
}
