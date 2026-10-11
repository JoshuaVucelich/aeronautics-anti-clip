package com.vucelich.sablesafelanding;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(SableSafeLanding.MOD_ID)
public final class SableSafeLanding {
    public static final String MOD_ID = "sable_safe_landing";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SableSafeLanding(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, SafeLandingConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(VoidRescueRuntime::onPrePhysics);
        LOGGER.info("Aeronautics Anti-Clip {} loaded", SafeLandingConfig.VERSION);
    }
}
