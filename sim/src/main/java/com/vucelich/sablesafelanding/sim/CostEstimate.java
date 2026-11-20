package com.vucelich.sablesafelanding.sim;

/**
 * Learns the cost of one unit of a kind of work (a block added to an index, a cell merged, a
 * wheel ray cast) from what it really took, so a {@link TickBudget} can be asked before the work
 * runs. A new sample moves the estimate a quarter of the way; a step that ran slower than
 * expected raises it at once, so the next check is cautious. Work that was turned away calls
 * {@link #relax()}, which halves any excess over the starting guess, so one slow outlier (a cold
 * JIT, a GC pause) cannot lock that work out for good: it is retried within a few ticks.
 */
public final class CostEstimate {
    private double nanosPerUnit;
    private final double floorNanos;
    private final double initialNanos;
    private final long minUnits;

    public CostEstimate(double initialNanosPerUnit, double floorNanosPerUnit) {
        this(initialNanosPerUnit, floorNanosPerUnit, 1L);
    }

    /**
     * @param minUnits a sample with fewer units is counted as this many, so the fixed cost of a
     *                 call on a tiny piece of work is not read as a huge cost per unit
     */
    public CostEstimate(double initialNanosPerUnit, double floorNanosPerUnit, long minUnits) {
        this.minUnits = Math.max(1L, minUnits);
        this.nanosPerUnit = Math.max(initialNanosPerUnit, floorNanosPerUnit);
        this.floorNanos = floorNanosPerUnit;
        this.initialNanos = this.nanosPerUnit;
    }

    /** Called when work was deferred on this estimate: halves the excess over the first guess. */
    public void relax() {
        if (nanosPerUnit > initialNanos) {
            nanosPerUnit = Math.max(initialNanos, initialNanos + (nanosPerUnit - initialNanos) * 0.5);
        }
    }

    public long estimate(long units) {
        if (units <= 0) {
            return 0L;
        }
        return (long) Math.ceil(nanosPerUnit * units);
    }

    public void observe(long units, long nanos) {
        if (units <= 0 || nanos <= 0) {
            return;
        }
        double sample = (double) nanos / Math.max(units, minUnits);
        if (sample > nanosPerUnit) {
            // Rise at once, but at most fourfold per sample, so one pause cannot shut the work out.
            nanosPerUnit = Math.min(sample, nanosPerUnit * 4.0);
        } else {
            nanosPerUnit += (sample - nanosPerUnit) * 0.25;
        }
        if (nanosPerUnit < floorNanos) {
            nanosPerUnit = floorNanos;
        }
    }

    public double nanosPerUnit() {
        return nanosPerUnit;
    }
}
