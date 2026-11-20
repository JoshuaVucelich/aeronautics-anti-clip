package com.vucelich.sablesafelanding;

import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Wall time this mod spends in its server hooks. Each hook adds its own nanoseconds; the tick is
 * closed by {@link #onServerTick}, which is registered after every other server hook of this mod.
 * Read by /anticlip status and the stress trial. Closing a tick is a few counter updates.
 */
public final class PerfMeter {
    /** Ticks in the recent window shown by /anticlip status (one minute). */
    private static final int WINDOW = 1_200;

    private static final AtomicLong TICK = new AtomicLong();
    private static final AtomicLong WORST = new AtomicLong();
    private static final AtomicLong TOTAL = new AtomicLong();
    private static final AtomicLong TICKS = new AtomicLong();
    private static final AtomicLong BUSY_TICKS = new AtomicLong();
    private static final long[] RECENT = new long[WINDOW];
    private static int recentIndex;
    private static int recentCount;
    private static volatile long lastTick;

    /** Which hook the time was spent in, for the stress trial breakdown. */
    public enum Part { RESCUE, FEED, CARRY_PHYSICS, CARRY_TICK, WHEEL }

    private static final AtomicLong[] PARTS = new AtomicLong[Part.values().length];

    static {
        for (int i = 0; i < PARTS.length; i++) {
            PARTS[i] = new AtomicLong();
        }
    }

    private PerfMeter() {
    }

    public static void add(Part part, long nanos) {
        if (nanos > 0) {
            TICK.addAndGet(nanos);
            PARTS[part.ordinal()].addAndGet(nanos);
        }
    }

    /** Total nanoseconds per part since startup. */
    public static long[] parts() {
        long[] out = new long[PARTS.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = PARTS[i].get();
        }
        return out;
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        takeTick();
    }

    /** Closes one server tick. Returns the nanoseconds this mod used in it. */
    public static synchronized long takeTick() {
        long used = TICK.getAndSet(0);
        TOTAL.addAndGet(used);
        TICKS.incrementAndGet();
        WORST.accumulateAndGet(used, Math::max);
        if (used > 20_000L) {
            BUSY_TICKS.incrementAndGet();
        }
        RECENT[recentIndex] = used;
        recentIndex = (recentIndex + 1) % WINDOW;
        recentCount = Math.min(WINDOW, recentCount + 1);
        lastTick = used;
        return used;
    }

    /** This mod's time in the last closed server tick. */
    public static long lastTickNanos() {
        return lastTick;
    }

    public static long worstMicros() {
        return WORST.get() / 1_000L;
    }

    public static double meanMicros() {
        long ticks = TICKS.get();
        return ticks == 0 ? 0.0 : TOTAL.get() / 1_000.0 / ticks;
    }

    public static long ticks() {
        return TICKS.get();
    }

    /** Ticks in which this mod used more than 20 microseconds. */
    public static long busyTicks() {
        return BUSY_TICKS.get();
    }

    /** Mean and worst over the last minute of ticks, in microseconds. */
    public static synchronized double[] recentMicros() {
        if (recentCount == 0) {
            return new double[]{0.0, 0.0};
        }
        long total = 0;
        long worst = 0;
        for (int i = 0; i < recentCount; i++) {
            total += RECENT[i];
            worst = Math.max(worst, RECENT[i]);
        }
        return new double[]{total / 1_000.0 / recentCount, worst / 1_000.0};
    }

    public static synchronized void reset() {
        TICK.set(0);
        WORST.set(0);
        TOTAL.set(0);
        TICKS.set(0);
        BUSY_TICKS.set(0);
        recentIndex = 0;
        recentCount = 0;
    }
}
