package com.vucelich.sablesafelanding;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

@Mod(SableSafeLanding.MOD_ID)
public final class SableSafeLanding {
    public static final String MOD_ID = "sable_safe_landing";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SableSafeLanding(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, SafeLandingConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(VoidRescueRuntime::onPrePhysics);
        NeoForge.EVENT_BUS.addListener(ColliderFeedRuntime::onPrePhysics);
        NeoForge.EVENT_BUS.addListener(CarryRuntime::onPrePhysics);
        NeoForge.EVENT_BUS.addListener(SableSafeLanding::onUnload);
        NeoForge.EVENT_BUS.addListener(SableSafeLanding::onStop);
        if (LiveTrial.enabled()) {
            NeoForge.EVENT_BUS.addListener(LiveTrial::onTick);
        }
        LOGGER.info("Aeronautics Anti-Clip {} loaded", SafeLandingConfig.VERSION);
    }

    private static void onUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            return;
        }
        String dimension = level.dimension().location().toString();
        ColliderFeedRuntime.forgetDimension(dimension);
        VoidRescueRuntime.forgetDimension(dimension);
        CarryRuntime.forgetDimension(dimension);
    }

    private static void onStop(ServerStoppingEvent event) {
        ColliderFeedRuntime.clear();
        VoidRescueRuntime.clear();
        CarryRuntime.clear();
        PlatformTrace.clear();
    }
}
