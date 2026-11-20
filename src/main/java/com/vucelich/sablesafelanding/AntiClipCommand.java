package com.vucelich.sablesafelanding;

import com.mojang.brigadier.Command;
import com.vucelich.sablesafelanding.sim.AntiClipConfig;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /anticlip status} reports the master switch and the live counts.
 * {@code /anticlip off} and {@code /anticlip on} require permission level 2 and save the config.
 */
public final class AntiClipCommand {
    private AntiClipCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("anticlip")
                        .then(Commands.literal("status").executes(context -> status(context.getSource()))
                                .then(Commands.literal("reset")
                                        .requires(source -> source.hasPermission(2))
                                        .executes(context -> {
                                            PerfMeter.reset();
                                            context.getSource().sendSuccess(() -> Component.literal("Anti-Clip server timers reset"), false);
                                            return Command.SINGLE_SUCCESS;
                                        })))
                        .then(Commands.literal("off")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> setEnabled(context.getSource(), false)))
                        .then(Commands.literal("on")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> setEnabled(context.getSource(), true)))
        );
    }

    private static int status(net.minecraft.commands.CommandSourceStack source) {
        AntiClipConfig config = SafeLandingConfig.snapshot();
        String text = String.format(
                java.util.Locale.ROOT,
                "Aeronautics Anti-Clip %s enabled=%s carry=%s feed=%s sweep=%s voidRescue=%s bedrockRescue=%s wheelContact=%s wheelDeckHits=%d wheelTouches=%d locks=%d held=%d removalHook=%s tickBudgetMicros=%d modMeanUs=%.1f modWorstUs=%d deckIndexes=%d budgetOutTicks=%d wheelBudgetSkips=%d",
                SafeLandingConfig.VERSION,
                config.enabled(),
                config.carry(),
                config.feedColliders(),
                config.sweep(),
                config.voidRescue(),
                config.bedrockRescue(),
                WheelContactRuntime.switchOn(),
                WheelContactRuntime.deckHits(),
                WheelContactRuntime.touchCount(),
                CarryRuntime.lockCount(),
                CarryRuntime.heldCount(),
                VoidRescueRuntime.removalHookSeen(),
                SafeLandingConfig.tickBudgetMicros(),
                PerfMeter.meanMicros(),
                PerfMeter.worstMicros(),
                DeckShapes.count(),
                CarryRuntime.budgetExhaustedTicks(),
                WheelContactRuntime.budgetSkips()
        );
        source.sendSuccess(() -> Component.literal(text), false);
        double[] recent = PerfMeter.recentMicros();
        long[] rescue = VoidRescueRuntime.counts();
        String timing = String.format(
                java.util.Locale.ROOT,
                "Server time: mean %.2f us/tick, worst %d us over %d ticks; last minute mean %.2f us, worst %.0f us; ticks over 20 us %d; deck work ran in %d of %d level-ticks (idleSleep=%s); budget-out ticks %d, steps deferred %d, index builds deferred %d, collider uploads %d (deferred %d, waiting for a ship near %d), wheel rays from memory %d; void rescue full steps %d, skipped far from the void %d, parked safe-pose snapshots %d",
                PerfMeter.meanMicros(),
                PerfMeter.worstMicros(),
                PerfMeter.ticks(),
                recent[0],
                recent[1],
                PerfMeter.busyTicks(),
                DeckGate.engagedTicks(),
                DeckGate.checkedTicks(),
                SafeLandingConfig.idleSleep(),
                CarryRuntime.budgetExhaustedTicks(),
                CarryRuntime.budgetDeferred(),
                DeckShapes.deferredBuilds(),
                ColliderFeedRuntime.uploads(),
                ColliderFeedRuntime.deferredUploads(),
                ColliderFeedRuntime.lazyWaits(),
                WheelContactRuntime.memoHits(),
                rescue[1],
                rescue[0],
                VoidRescueRuntime.parkedSnaps()
        );
        source.sendSuccess(() -> Component.literal(timing), false);
        SableSafeLanding.LOGGER.info("{} | {}", text, timing);
        return Command.SINGLE_SUCCESS;
    }

    private static int setEnabled(net.minecraft.commands.CommandSourceStack source, boolean on) {
        SafeLandingConfig.ENABLED.set(on);
        SafeLandingConfig.ENABLED.save();
        if (!on) {
            CarryRuntime.clear();
            ColliderFeedRuntime.releaseClaims();
            for (ServerLevel level : source.getServer().getAllLevels()) {
                ColliderFeedRuntime.disarm(level);
            }
        }
        source.sendSuccess(() -> Component.literal(on ? "Anti-clip is on" : "Anti-clip is off"), true);
        SableSafeLanding.LOGGER.info("Anti-clip {}", on ? "on" : "off");
        return Command.SINGLE_SUCCESS;
    }
}
