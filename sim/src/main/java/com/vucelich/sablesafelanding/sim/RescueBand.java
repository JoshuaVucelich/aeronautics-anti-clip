package com.vucelich.sablesafelanding.sim;

/**
 * Cheap test for void and bedrock rescue: a ship farther than {@link #LEAD} blocks (plus a
 * second of its own vertical speed) from every rescue line cannot need a rescue this tick, so
 * the full rescue step is skipped for it. A ship inside the band runs the full step every tick,
 * which also records its last safe pose before it can reach a line.
 */
public final class RescueBand {
    /** Blocks before a rescue line at which the full step starts running. */
    public static final double LEAD = 32.0;

    private RescueBand() {
    }

    /**
     * @param minY ship bottom
     * @param maxY ship top
     * @param verticalSpeed blocks per second, either sign
     * @param floor lowest safe bottom: Sable's remove floor plus margins
     * @param ceiling highest safe top
     * @param bedrockLine the bedrock rescue line, or NaN when bedrock rescue is off
     */
    public static boolean inBand(double minY, double maxY, double verticalSpeed,
                                 double floor, double ceiling, double bedrockLine) {
        if (!Double.isFinite(minY) || !Double.isFinite(maxY)) {
            return true;
        }
        double lead = LEAD + Math.min(512.0, Math.abs(Double.isFinite(verticalSpeed) ? verticalSpeed : 0.0));
        if (minY < floor + lead || maxY > ceiling - lead) {
            return true;
        }
        return Double.isFinite(bedrockLine) && minY < bedrockLine + lead;
    }
}
