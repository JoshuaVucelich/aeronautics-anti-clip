package com.vucelich.sablesafelanding;

import java.util.HashMap;
import java.util.Map;

/**
 * One line per contraption for the live trial. The first non-zero sample is the one that shows
 * whether the platform motion was kept or dropped.
 */
public final class PlatformTrace {
    private static final Map<String, String> LINES = new HashMap<>();

    private PlatformTrace() {
    }

    public static void note(String id, String line) {
        LINES.putIfAbsent(id, line);
    }

    public static String get(String id) {
        if (id == null) {
            return "none";
        }
        return LINES.getOrDefault(id, "none");
    }

    public static void clear() {
        LINES.clear();
    }
}
