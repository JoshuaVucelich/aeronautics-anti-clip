package com.vucelich.anticlipbench;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Measurement mod for the pack A/B. It does nothing unless {@code -Danticlip.bench=true}.
 * It is not inside the release jar.
 */
@Mod(AnticlipBench.MOD_ID)
public final class AnticlipBench {
    public static final String MOD_ID = "anticlip_bench";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AnticlipBench(IEventBus modBus) {
        if (!Boolean.getBoolean("anticlip.bench")) {
            LOGGER.info("Anti-Clip bench is installed and idle");
            return;
        }
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, BenchTrial::onPre);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, BenchTrial::onPostEarly);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, BenchTrial::onPostLate);
        LOGGER.info("Anti-Clip bench armed");
    }
}
