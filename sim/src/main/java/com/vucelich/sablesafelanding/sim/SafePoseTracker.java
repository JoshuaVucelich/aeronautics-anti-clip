package com.vucelich.sablesafelanding.sim;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Remembers one safe pose per dimension and body id. Unsafe, penetrating, or fast-falling poses
 * are ignored. A remembered pose is only reused when it is recent and near the current position.
 */
public final class SafePoseTracker {
    /** Ticks. Thirty seconds at 20 ticks per second. */
    public static final long MAX_AGE = 600;
    /** Blocks. A pose from another part of the world is not a rescue target. */
    public static final double MAX_DISTANCE = 32.0;

    private final Map<String, PoseSnapshot> poses = new LinkedHashMap<>();

    public static String key(String dimension, String id) {
        return dimension + "\u0000" + id;
    }

    public void observe(
            String dimension,
            long time,
            BodyState body,
            RescueBounds world,
            AntiClipConfig config,
            boolean penetrating,
            boolean supported
    ) {
        if (!body.position().finite() || !body.velocity().finite() || !body.bounds().finite() || !body.orientation().finite()) {
            return;
        }
        if (penetrating || !world.comfortablyInside(body.bounds())) {
            return;
        }
        boolean stable = supported || body.velocity().y() > -config.stableFallSpeed();
        if (!stable) {
            return;
        }
        poses.put(key(dimension, body.id()), new PoseSnapshot(body.position(), body.bounds(), body.orientation(), time));
    }

    public PoseSnapshot get(String dimension, String id) {
        return poses.get(key(dimension, id));
    }

    /**
     * A saved pose may replace an invalid one only in the same dimension, recently, and nearby.
     */
    public boolean usable(PoseSnapshot saved, BodyState body, RescueBounds world, long time) {
        if (saved == null || !saved.finite() || world.needsRescue(saved.bounds())) {
            return false;
        }
        if (saved.time() > time || time - saved.time() > MAX_AGE) {
            return false;
        }
        if (!body.position().finite()) {
            return false;
        }
        return body.position().sub(saved.position()).lengthSquared() <= MAX_DISTANCE * MAX_DISTANCE;
    }

    public void put(String dimension, String id, PoseSnapshot snapshot) {
        if (snapshot != null && snapshot.finite()) {
            poses.put(key(dimension, id), snapshot);
        }
    }

    public void forget(String dimension, String id) {
        poses.remove(key(dimension, id));
    }

    public void forgetDimension(String dimension) {
        String prefix = dimension + "\u0000";
        poses.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public int size() {
        return poses.size();
    }

    public void retain(Set<String> liveIds) {
        poses.keySet().retainAll(liveIds);
    }
}
