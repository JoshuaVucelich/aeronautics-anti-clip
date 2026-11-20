package com.vucelich.sablesafelanding;

import com.vucelich.sablesafelanding.sim.AntiClipConfig;
import com.vucelich.sablesafelanding.sim.ConfigMigration;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class SafeLandingConfig {
    public static final String VERSION = "0.7.0-rc.10";

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
    public static final ModConfigSpec.IntValue CONFIG_VERSION;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue WHEEL_CONTACT;
    public static final ModConfigSpec.IntValue TICK_BUDGET_MICROS;
    public static final ModConfigSpec.BooleanValue IDLE_SLEEP;
    public static final ModConfigSpec.BooleanValue CLIENT_WHEEL_CONTACT;
    /** Default for {@link #TICK_BUDGET_MICROS}: 2 ms, 4% of a 50 ms tick. */
    public static final int DEFAULT_TICK_BUDGET_MICROS = 2_000;

    static {
        AntiClipConfig defaults = AntiClipConfig.defaults();
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("Aeronautics Anti-Clip. This mod is not published to Modrinth or CurseForge.");

        ENABLED = builder
                .comment("Master switch. Off disables void rescue, bedrock rescue, the collider feed, carry, and sweep. /anticlip off and /anticlip on flip this and save it. /anticlip status prints the flags.")
                .define("enabled", defaults.enabled());
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
        WHEEL_CONTACT = builder
                .comment("On by default. Lets Aeronautics wheel mounts find the ground on a moving elevator, bearing, piston, or gantry deck, so a car can drive on and off. Off leaves the wheel ground ray exactly as Offroad casts it. Needed on the server for driving and on the client for where the wheels are drawn. Not synced: the server value decides driving and carry, the client value only changes where its wheels are drawn. A wheel far from every contraption deck gets Offroad's ray unchanged, after two map reads.")
                .define("wheelContact", true);
        TICK_BUDGET_MICROS = builder
                .comment("Performance. Cap on background deck work, in microseconds per game tick per dimension: building a contraption's deck index, the carry's deck shapes, capture block tests, wheel rays against decks, and collider uploads after the first one in a tick. It does not cap placing a ship that is already riding, or the first collider upload in a tick. Each step is checked against what is left before it starts, using the step's measured cost, so a step that would not fit waits for the next tick instead of running over. The carry reuses the last deck shape meanwhile and wheel rays use Offroad's own ray. Default 2000 (2 ms). 0 turns deck work off. Any value from 1 to 499 is raised to 500, the smallest cap that keeps a heavy ship riding at the deck's speed; below about 1000 a big deck can lag a ship a little on its first trip.")
                .defineInRange("tickBudgetMicros", DEFAULT_TICK_BUDGET_MICROS, 0, 50_000);
        IDLE_SLEEP = builder
                .comment("Performance, on by default. While no ship is on or next to a moving elevator, bearing, piston, or gantry deck, the carry, deck shapes, and sweep do no work at all, and ships are not scanned. Void rescue runs its full step only near the void floor, build ceiling, or bedrock line, and elsewhere copies each ship's pose every 10 ticks as its safe pose. A car parked on a still deck reuses its last wheel ray result. A deck's collider is taken over from Sable only while a ship is within 48 blocks. Off runs the deck checks every tick like 0.7.0-rc.8 did. This never changes how a ship that is actually riding is carried.")
                .define("idleSleep", true);
        CLIENT_WHEEL_CONTACT = builder
                .comment("Client only, on by default. Tick logic, not render code: the client wheel ground ray also sees contraption decks, so the wheel extension Offroad later draws stands on a deck instead of sunk through it. Costs a few map reads per wheel ray while the car is not near a deck. Off leaves the drawn wheel exactly as Offroad places it; driving is decided by the server either way.")
                .define("clientWheelContact", true);
        CONFIG_VERSION = builder
                .comment("Internal. 0 means this file was written before carry defaulted on. Loading migrates that file to carry=true and stores 1. A later edit that turns carry off is kept.")
                .defineInRange("configVersion", 0, 0, 1_000);

        SPEC = builder.build();
    }

    /**
     * NeoForge does not replace a value that is already in the file, so an older config keeps
     * {@code carry=false}. A missing {@code configVersion} loads as 0 and is migrated once.
     */
    public static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        if (!(event instanceof ModConfigEvent.Loading) && !(event instanceof ModConfigEvent.Reloading)) {
            return;
        }
        int previous = CONFIG_VERSION.getAsInt();
        boolean carryWasOn = CARRY.getAsBoolean();
        ConfigMigration.Result result = ConfigMigration.migrate(previous, carryWasOn);
        if (!result.changed()) {
            return;
        }
        CARRY.set(result.carry());
        CONFIG_VERSION.set(result.version());
        CONFIG_VERSION.save();
        if (carryWasOn) {
            SableSafeLanding.LOGGER.info(
                    "Stamped configVersion {} to {} (carry was already on)",
                    previous,
                    result.version()
            );
            return;
        }
        SableSafeLanding.LOGGER.info("Migrated configVersion {} to {} and set carry=true", previous, result.version());
    }

    /**
     * False only when the loaded config says so. A config that is not ready yet stays on,
     * and a thrown read does not escape into a mixin.
     */
    public static boolean running() {
        try {
            return ENABLED.getAsBoolean();
        } catch (RuntimeException ex) {
            return true;
        }
    }

    private SafeLandingConfig() {
    }

    /** The per-tick deck work cap in microseconds. A config that is not loaded yet uses the default. */
    public static int tickBudgetMicros() {
        try {
            return TICK_BUDGET_MICROS.getAsInt();
        } catch (RuntimeException ex) {
            return DEFAULT_TICK_BUDGET_MICROS;
        }
    }

    /** True unless the idle gate is turned off. A config that is not loaded yet counts as on. */
    public static boolean idleSleep() {
        try {
            return IDLE_SLEEP.getAsBoolean();
        } catch (RuntimeException ex) {
            return true;
        }
    }

    /** Client wheel drawing on decks. A config that is not loaded yet counts as on. */
    public static boolean clientWheelContact() {
        try {
            return CLIENT_WHEEL_CONTACT.getAsBoolean();
        } catch (RuntimeException ex) {
            return true;
        }
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
                BEDROCK_TICKS.getAsInt(),
                ENABLED.getAsBoolean()
        );
    }
}
