package com.vucelich.sablesafelanding.sim;

/**
 * A hard cap on the time one kind of work may use in one game tick. Work checks
 * {@link #allows} before each step and skips the rest of the tick once the cap is used up.
 * The clock is passed in so tests can drive it.
 */
public final class TickBudget {
    private long tick = Long.MIN_VALUE;
    private long spent;
    private long capNanos;
    private long exhaustedTicks;
    private long lastExhaustedTick = Long.MIN_VALUE;

    /** Starts the tick's allowance if this is a new tick. A cap of 0 or less means no budget. */
    public void begin(long gameTick, long capMicros) {
        if (gameTick != tick) {
            tick = gameTick;
            spent = 0;
            capNanos = Math.max(0L, capMicros) * 1_000L;
        }
    }

    public boolean allows() {
        boolean ok = spent < capNanos;
        if (!ok && lastExhaustedTick != tick) {
            lastExhaustedTick = tick;
            exhaustedTicks++;
        }
        return ok;
    }

    public void charge(long nanos) {
        if (nanos > 0) {
            spent += nanos;
        }
    }

    public long spentNanos() {
        return spent;
    }

    /** Ticks in which the cap ran out. Shown by /anticlip status. */
    public long exhaustedTicks() {
        return exhaustedTicks;
    }
}
