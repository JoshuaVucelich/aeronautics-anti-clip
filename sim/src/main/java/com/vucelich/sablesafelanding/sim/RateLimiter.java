package com.vucelich.sablesafelanding.sim;

/**
 * Allows one event per gap. Used so a falling sub-level does not log on every substep.
 */
public final class RateLimiter {
    private final long gapNanos;
    private long last = Long.MIN_VALUE;

    public RateLimiter(long gapNanos) {
        if (gapNanos < 0) {
            throw new IllegalArgumentException("gap must be non-negative");
        }
        this.gapNanos = gapNanos;
    }

    public boolean tryAcquire(long now) {
        if (last != Long.MIN_VALUE && now - last < gapNanos) {
            return false;
        }
        last = now;
        return true;
    }
}
