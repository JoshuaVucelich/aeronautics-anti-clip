package com.vucelich.sablesafelanding.sim;

/**
 * A hard cap on the time one kind of work may use in one game tick.
 *
 * <p>Work asks {@link #allows(long)} with its expected cost before it starts. A step whose
 * estimate does not fit in what is left is not started; it is deferred to a later tick. The
 * estimate comes from {@link CostEstimate}, which learns each step's real cost, so the cap is
 * checked before the work, not charged after it. The only overrun left is a step that runs
 * longer than its own estimate, which is one slice, not a whole build.
 * The clock is passed in so tests can drive it.
 */
public final class TickBudget {
    /** Smallest nonzero cap, in microseconds. A lower setting is raised to this. 0 turns deck work off. */
    public static final long MIN_CAP_MICROS = 500L;

    private long tick = Long.MIN_VALUE;
    private long spent;
    private long capNanos;
    private long exhaustedTicks;
    private long lastExhaustedTick = Long.MIN_VALUE;
    private long deferred;

    /** The cap actually used for a configured value: 0 stays 0 (off), anything else is at least {@link #MIN_CAP_MICROS}. */
    public static long effectiveCapMicros(long configured) {
        if (configured <= 0L) {
            return 0L;
        }
        return Math.max(MIN_CAP_MICROS, configured);
    }

    /** Starts the tick's allowance if this is a new tick. A cap of 0 or less means no budget. */
    public void begin(long gameTick, long capMicros) {
        if (gameTick != tick) {
            tick = gameTick;
            spent = 0;
            capNanos = effectiveCapMicros(capMicros) * 1_000L;
        }
    }

    /** True while some of the cap is left. Same as {@code allows(0)}. */
    public boolean allows() {
        return allows(0L);
    }

    /** True when a step expected to take {@code estimateNanos} fits in what is left of this tick. */
    public boolean allows(long estimateNanos) {
        boolean ok = capNanos > 0 && spent + Math.max(0L, estimateNanos) <= capNanos && spent < capNanos;
        if (!ok && lastExhaustedTick != tick) {
            lastExhaustedTick = tick;
            exhaustedTicks++;
        }
        return ok;
    }

    /**
     * Like {@link #allows(long)} but leaves a quarter of the cap for work that must not wait,
     * such as wheel rays, which then always have room in a tick where bulk work is running.
     */
    public boolean allowsBulk(long estimateNanos) {
        return allows(Math.max(0L, estimateNanos) + capNanos / 4);
    }

    public void charge(long nanos) {
        if (nanos > 0) {
            spent += nanos;
        }
    }

    /** Records a step that was put off to a later tick. */
    public void defer() {
        deferred++;
    }

    public long spentNanos() {
        return spent;
    }

    public long capNanos() {
        return capNanos;
    }

    /** Ticks in which the cap ran out. Shown by /anticlip status. */
    public long exhaustedTicks() {
        return exhaustedTicks;
    }

    /** Steps put off to a later tick because they did not fit. Shown by /anticlip status. */
    public long deferredSteps() {
        return deferred;
    }
}
