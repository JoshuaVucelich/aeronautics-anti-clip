package com.vucelich.sablesafelanding;

import com.vucelich.sablesafelanding.sim.AntiClipConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class SafeLandingConfig {
    public static final String VERSION = "0.6.0-rc.22";

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue VOID_RESCUE;
    public static final ModConfigSpec.BooleanValue FEED_COLLIDERS;
    public static final ModConfigSpec.BooleanValue CARRY;
    public static final ModConfigSpec.BooleanValue SWEEP;
    public static final ModConfigSpec.DoubleValue APPROACH_MARGIN;
    public static final ModConfigSpec.DoubleValue SAFE_CLEARANCE;
    public static final ModConfigSpec.DoubleValue MAX_PENETRATION;
    public static final ModConfigSpec.DoubleValue REST_EPSILON;
    public static final ModConfigSpec.DoubleValue STABLE_FALL_SPEED;
    public static final ModConfigSpec.BooleanValue BEDROCK_RESCUE;
    public static final ModConfigSpec.DoubleValue BEDROCK_MARGIN;
    public static final ModConfigSpec.IntValue BEDROCK_TICKS;

    static {
        AntiClipConfig defaults = AntiClipConfig.defaults();
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("Aeronautics Anti-Clip. This mod is not published to Modrinth or CurseForge.");

        VOID_RESCUE = builder
                .comment("Shift a sub-level's Y back inside Sable's sub_level_remove bounds before Sable deletes it.")
                .define("voidRescue", defaults.voidRescue());
        FEED_COLLIDERS = builder
                .comment("Feed moving elevator, bearing, piston, and gantry colliders into Sable/Rapier.")
                .define("feedColliders", defaults.feedColliders());
        CARRY = builder
                .comment("On by default. Teleport a sub-level with a contraption after 5 ticks of real block contact. Placement does not start before that confirm. The collider feed does not capture. The lock releases after contact is gone, the contraption is removed, the deck sits idle, upward speed relative to the deck, or a player is steering. A climb faster than the deck is not pulled back. Placement runs only after the deck drifts, and it does not clear velocity.")
                .define("carry", defaults.carry());
        SWEEP = builder
                .comment("Swept correction against contraption blocks. Off by default. When on, it moves a ship only if a real ship block overlaps a contraption block.")
                .define("sweep", defaults.sweep());
        APPROACH_MARGIN = builder
                .comment("Blocks before Sable's sub_level_remove_min/max at which a rescue starts. One block matches the deletion check.")
                .defineInRange("approachMargin", defaults.approachMargin(), 0.0, 100_000.0);
        SAFE_CLEARANCE = builder
                .comment("Extra blocks inside the approach margin required before a pose is remembered as safe.")
                .defineInRange("safeClearance", defaults.safeClearance(), 0.0, 10_000.0);
        MAX_PENETRATION = builder
                .comment("Deepest overlap, in blocks, left after the swept correction.")
                .defineInRange("maxPenetration", defaults.maxPenetration(), 0.0, 2.0);
        REST_EPSILON = builder
                .comment("How close, in blocks, a sub-level bottom may be to a support and still count as resting.")
                .defineInRange("restEpsilon", defaults.restEpsilon(), 0.0, 4.0);
        STABLE_FALL_SPEED = builder
                .comment("Downward speed, in blocks per second, above which a pose is not stored as safe.")
                .defineInRange("stableFallSpeed", defaults.stableFallSpeed(), 0.0, 10_000.0);
        BEDROCK_RESCUE = builder
                .comment("After a sub-level stays below min build height minus bedrockMargin for bedrockTicks, put it back at its last safe pose in the same dimension and zero its velocity. This is separate from the sub_level_remove clamp. Sable does not delete a ship that is merely below bedrock.")
                .define("bedrockRescue", defaults.bedrockRescue());
        BEDROCK_MARGIN = builder
                .comment("Blocks below min build height the sub-level must reach before the bedrock dwell starts.")
                .defineInRange("bedrockMargin", defaults.bedrockMargin(), 0.0, 10_000.0);
        BEDROCK_TICKS = builder
                .comment("Consecutive ticks the sub-level must stay below that line before it is restored.")
                .defineInRange("bedrockTicks", defaults.bedrockTicks(), 1, 20_000);

        SPEC = builder.build();
    }

    private SafeLandingConfig() {
    }

    public static AntiClipConfig snapshot() {
        AntiClipConfig defaults = AntiClipConfig.defaults();
        return new AntiClipConfig(
                VOID_RESCUE.getAsBoolean(),
                FEED_COLLIDERS.getAsBoolean(),
                CARRY.getAsBoolean(),
                SWEEP.getAsBoolean(),
                APPROACH_MARGIN.getAsDouble(),
                SAFE_CLEARANCE.getAsDouble(),
                MAX_PENETRATION.getAsDouble(),
                REST_EPSILON.getAsDouble(),
                STABLE_FALL_SPEED.getAsDouble(),
                defaults.gravity(),
                defaults.spatialCell(),
                defaults.maxPairChecks(),
                BEDROCK_RESCUE.getAsBoolean(),
                BEDROCK_MARGIN.getAsDouble(),
                BEDROCK_TICKS.getAsInt()
        );
    }
}
