package com.vucelich.sablesafelanding;

import com.mojang.brigadier.Command;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * {@code /anticlipc status} and {@code /anticlipc reset}: this mod's own time on the client.
 * Registered only on a physical client. Commands only, nothing that draws.
 */
public final class ClientCommands {
    private ClientCommands() {
    }

    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("anticlipc")
                .then(Commands.literal("status").executes(context -> {
                    String text = ClientPerf.status();
                    context.getSource().sendSuccess(() -> Component.literal(text), false);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("reset").executes(context -> {
                    ClientPerf.reset();
                    context.getSource().sendSuccess(() -> Component.literal("Anti-Clip client timers reset"), false);
                    return Command.SINGLE_SUCCESS;
                })));
    }
}
