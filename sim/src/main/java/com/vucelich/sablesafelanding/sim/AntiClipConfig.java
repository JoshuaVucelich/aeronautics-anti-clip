package com.vucelich.sablesafelanding.sim;

/**
 * Shared defaults for the headless sim and the NeoForge config screen.
 * Distances are blocks. Speeds are blocks per second. Gravity is blocks per second squared.
 */
public record AntiClipConfig(
        boolean voidRescue,
        boolean feedColliders,
        boolean carry,
        boolean sweep,
        double approachMargin,
        double safeClearance,
        double maxPenetration,
        double restEpsilon,
        double stableFallSpeed,
        double gravity,
        int spatialCell,
        int maxPairChecks,
        boolean bedrockRescue,
        double bedrockMargin,
        int bedrockTicks
) {
    public AntiClipConfig {
        if (approachMargin < 0.0 || safeClearance < 0.0 || maxPenetration < 0.0 || restEpsilon < 0.0 || bedrockMargin < 0.0) {
            throw new IllegalArgumentException("distances must be non-negative");
        }
        if (spatialCell < 1) {
            throw new IllegalArgumentException("spatial cell must be positive");
        }
        if (maxPairChecks < 0) {
            throw new IllegalArgumentException("max pair checks must be non-negative");
        }
        if (bedrockTicks < 1) {
            throw new IllegalArgumentException("bedrock dwell must be at least one tick");
        }
    }

    public static AntiClipConfig defaults() {
        return new AntiClipConfig(
                true,
                true,
                false,
                false,
                1.0,
                8.0,
                0.05,
                0.2,
                4.0,
                -32.0,
                16,
                4096,
                true,
                32.0,
                40
        );
    }

    public AntiClipConfig withVoidRescue(boolean enabled) {
        return new AntiClipConfig(enabled, feedColliders, carry, sweep, approachMargin, safeClearance,
                maxPenetration, restEpsilon, stableFallSpeed, gravity, spatialCell, maxPairChecks,
                bedrockRescue, bedrockMargin, bedrockTicks);
    }

    public AntiClipConfig withFeedColliders(boolean enabled) {
        return new AntiClipConfig(voidRescue, enabled, carry, sweep, approachMargin, safeClearance,
                maxPenetration, restEpsilon, stableFallSpeed, gravity, spatialCell, maxPairChecks,
                bedrockRescue, bedrockMargin, bedrockTicks);
    }

    public AntiClipConfig withCarry(boolean enabled) {
        return new AntiClipConfig(voidRescue, feedColliders, enabled, sweep, approachMargin, safeClearance,
                maxPenetration, restEpsilon, stableFallSpeed, gravity, spatialCell, maxPairChecks,
                bedrockRescue, bedrockMargin, bedrockTicks);
    }

    public AntiClipConfig withSweep(boolean enabled) {
        return new AntiClipConfig(voidRescue, feedColliders, carry, enabled, approachMargin, safeClearance,
                maxPenetration, restEpsilon, stableFallSpeed, gravity, spatialCell, maxPairChecks,
                bedrockRescue, bedrockMargin, bedrockTicks);
    }

    public AntiClipConfig withBedrockRescue(boolean enabled) {
        return new AntiClipConfig(voidRescue, feedColliders, carry, sweep, approachMargin, safeClearance,
                maxPenetration, restEpsilon, stableFallSpeed, gravity, spatialCell, maxPairChecks,
                enabled, bedrockMargin, bedrockTicks);
    }

    public AntiClipConfig withBedrockMargin(double margin) {
        return new AntiClipConfig(voidRescue, feedColliders, carry, sweep, approachMargin, safeClearance,
                maxPenetration, restEpsilon, stableFallSpeed, gravity, spatialCell, maxPairChecks,
                bedrockRescue, margin, bedrockTicks);
    }

    public AntiClipConfig withBedrockTicks(int ticks) {
        return new AntiClipConfig(voidRescue, feedColliders, carry, sweep, approachMargin, safeClearance,
                maxPenetration, restEpsilon, stableFallSpeed, gravity, spatialCell, maxPairChecks,
                bedrockRescue, bedrockMargin, ticks);
    }
}
