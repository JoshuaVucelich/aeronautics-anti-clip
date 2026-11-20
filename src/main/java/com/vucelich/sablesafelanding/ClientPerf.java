package com.vucelich.sablesafelanding;

/**
 * Wall time this mod spends on the client, read by {@code /anticlipc status}. The client runs
 * only two hooks: the wheel ground ray of an Aeronautics wheel mount (once per wheel per client
 * tick, never per frame) and an early return in the contraption tick. Each call adds its time
 * here; a client tick is closed lazily the first time a hook runs in the next one, so no client
 * tick listener is needed. Client thread only, so plain fields.
 */
public final class ClientPerf {
    private static long currentTick = Long.MIN_VALUE;
    private static long tickNanos;
    private static long totalNanos;
    private static long worstNanos;
    private static long ticks;
    private static long calls;
    private static long rays;
    private static long deckRays;
    private static long budgetSkips;
    private static long firstTick = Long.MIN_VALUE;
    private static long lastSeenTick = Long.MIN_VALUE;

    private ClientPerf() {
    }

    /** Adds {@code nanos} spent in a hook during client game tick {@code gameTick}. */
    public static void add(long gameTick, long nanos) {
        roll(gameTick);
        tickNanos += Math.max(0L, nanos);
        calls++;
    }

    public static void ray(boolean onDeck) {
        rays++;
        if (onDeck) {
            deckRays++;
        }
    }

    public static void budgetSkip() {
        budgetSkips++;
    }

    private static void roll(long gameTick) {
        if (firstTick == Long.MIN_VALUE) {
            firstTick = gameTick;
        }
        lastSeenTick = Math.max(lastSeenTick, gameTick);
        if (gameTick != currentTick) {
            if (currentTick != Long.MIN_VALUE) {
                totalNanos += tickNanos;
                worstNanos = Math.max(worstNanos, tickNanos);
                ticks++;
            }
            currentTick = gameTick;
            tickNanos = 0;
        }
    }

    /**
     * Mean is over every client tick since the first hook ran, including ticks where no hook
     * ran at all, so it is the real per-tick cost.
     */
    public static String status() {
        long span = firstTick == Long.MIN_VALUE ? 0 : Math.max(1, lastSeenTick - firstTick + 1);
        long total = totalNanos + tickNanos;
        double mean = span == 0 ? 0.0 : total / 1_000.0 / span;
        double meanActive = ticks + 1 == 0 ? 0.0 : total / 1_000.0 / (ticks + 1);
        return String.format(java.util.Locale.ROOT,
                "Anti-Clip client %s: per client tick mean %.2f us over %d ticks (%.2f us in the %d ticks a hook ran), worst %.1f us; hook calls %d, wheel rays %d (on a deck %d), budget skips %d, wheel drawing %s",
                SafeLandingConfig.VERSION, mean, span, firstTick == Long.MIN_VALUE ? 0.0 : meanActive,
                firstTick == Long.MIN_VALUE ? 0 : ticks + 1, Math.max(worstNanos, tickNanos) / 1_000.0,
                calls, rays, deckRays, budgetSkips, SafeLandingConfig.clientWheelContact() ? "on" : "off");
    }

    public static void reset() {
        currentTick = Long.MIN_VALUE;
        tickNanos = 0;
        totalNanos = 0;
        worstNanos = 0;
        ticks = 0;
        calls = 0;
        rays = 0;
        deckRays = 0;
        budgetSkips = 0;
        firstTick = Long.MIN_VALUE;
        lastSeenTick = Long.MIN_VALUE;
    }
}
