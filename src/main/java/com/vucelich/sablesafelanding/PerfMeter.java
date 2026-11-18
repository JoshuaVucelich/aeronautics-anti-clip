package com.vucelich.sablesafelanding;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Wall time this mod spends in its server hooks. Read by /anticlip status and the stress trial.
 * Each hook adds its own nanoseconds; {@link #takeTick()} returns and resets the running tick.
 */
public final class PerfMeter {
    private static final AtomicLong TICK = new AtomicLong();
    private static final AtomicLong WORST = new AtomicLong();
    private static final AtomicLong TOTAL = new AtomicLong();
    private static final AtomicLong TICKS = new AtomicLong();

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

    /** Closes one server tick. Returns the nanoseconds this mod used in it. */
    public static long takeTick() {
        long used = TICK.getAndSet(0);
        TOTAL.addAndGet(used);
        TICKS.incrementAndGet();
        WORST.accumulateAndGet(used, Math::max);
        return used;
    }

    public static long worstMicros() {
        return WORST.get() / 1_000L;
    }

    public static double meanMicros() {
        long ticks = TICKS.get();
        return ticks == 0 ? 0.0 : TOTAL.get() / 1_000.0 / ticks;
    }
}
