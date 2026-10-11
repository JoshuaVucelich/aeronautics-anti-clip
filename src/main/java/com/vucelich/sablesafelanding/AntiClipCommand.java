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
                        .then(Commands.literal("status").executes(context -> status(context.getSource())))
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
                "Aeronautics Anti-Clip %s enabled=%s carry=%s feed=%s sweep=%s voidRescue=%s bedrockRescue=%s locks=%d held=%d removalHook=%s",
                SafeLandingConfig.VERSION,
                config.enabled(),
                config.carry(),
                config.feedColliders(),
                config.sweep(),
                config.voidRescue(),
                config.bedrockRescue(),
                CarryRuntime.lockCount(),
                CarryRuntime.heldCount(),
                VoidRescueRuntime.removalHookSeen()
        );
        source.sendSuccess(() -> Component.literal(text), false);
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
