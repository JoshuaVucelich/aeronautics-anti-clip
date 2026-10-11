package com.vucelich.sablesafelanding.sim;

/**
 * The master switch, and a wrapper so a mixin callback cannot take down the server tick.
 */
public final class FailureGuards {
    private FailureGuards() {
    }

    public static boolean engaged(AntiClipConfig config) {
        return config != null && config.enabled();
    }

    /** @return false when the action threw. The exception is not rethrown. */
    public static boolean run(Runnable action) {
        if (action == null) {
            return false;
        }
        try {
            action.run();
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
