package com.vucelich.sablesafelanding.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One anti-clip substep. M1 rescues bodies that have left the world and records safe poses.
 * Carry and sweep plug into the same step in later milestones.
 */
public final class AntiClipStep {
    private final SafePoseTracker tracker = new SafePoseTracker();
    private final Map<String, Set<String>> idsByDimension = new HashMap<>();
    private String dimension = "sim";
    private long time;

    public SafePoseTracker tracker() {
        return tracker;
    }

    public void clear() {
        idsByDimension.clear();
        tracker.retain(Set.of());
    }

    /**
     * Dimension and game time for the next {@link #step}. Poses are stored under this dimension.
     */
    public void bind(String dimension, long time) {
        this.dimension = dimension == null || dimension.isBlank() ? "sim" : dimension;
        this.time = time;
    }

    /**
     * Drops remembered poses for bodies that left this dimension since the last call.
     * Other dimensions are left alone.
     */
    public void retainDimension(String dimension, Set<String> liveIds) {
        Set<String> previous = idsByDimension.put(dimension, Set.copyOf(liveIds));
        if (previous == null) {
            return;
        }
        for (String id : previous) {
            if (!liveIds.contains(id)) {
                tracker.forget(dimension, id);
            }
        }
    }

    public List<BodyState> step(
            List<BodyState> bodies,
            List<SupportMotion> supports,
            RescueBounds world,
            AntiClipConfig config,
            double dt
    ) {
        if (dt < 0.0) {
            throw new IllegalArgumentException("dt must be non-negative");
        }
        List<BodyState> next = new ArrayList<>(bodies.size());
        for (BodyState body : bodies) {
            next.add(stepOne(body, supports, world, config));
        }
        return List.copyOf(next);
    }

    private BodyState stepOne(BodyState body, List<SupportMotion> supports, RescueBounds world, AntiClipConfig config) {
        boolean unsafe = !body.position().finite()
                || !body.velocity().finite()
                || !body.bounds().finite()
                || !body.orientation().finite()
                || world.needsRescue(body.bounds());
        if (unsafe) {
            if (!config.voidRescue()) {
                return body.withRescued(false);
            }
            return rescue(body, world);
        }
        boolean penetrating = penetrating(body, supports, config);
        boolean supported = resting(body, supports, config);
        tracker.observe(dimension, time, body, world, config, penetrating, supported);
        return body.withRescued(false);
    }

    private BodyState rescue(BodyState body, RescueBounds world) {
        boolean poseValid = body.position().finite() && body.bounds().finite() && body.orientation().finite();
        if (!poseValid) {
            PoseSnapshot saved = tracker.get(dimension, body.id());
            if (tracker.usable(saved, body, world, time)) {
                return new BodyState(body.id(), saved.position(), Vec3.ZERO, saved.bounds(), saved.orientation(), true);
            }
            return body.withRescued(false);
        }
        return clamp(body, world);
    }

    private BodyState clamp(BodyState body, RescueBounds world) {
        double dy = 0.0;
        double floor = world.floor();
        if (body.bounds().minY() < floor) {
            dy = floor - body.bounds().minY();
        } else if (body.bounds().maxY() > world.ceiling()) {
            dy = world.ceiling() - body.bounds().maxY();
        }
        Vec3 delta = new Vec3(0.0, dy, 0.0);
        return new BodyState(
                body.id(),
                body.position().add(delta),
                Vec3.ZERO,
                body.bounds().translate(delta),
                body.orientation(),
                true
        );
    }

    static boolean resting(BodyState body, List<SupportMotion> supports, AntiClipConfig config) {
        for (SupportMotion support : supports) {
            if (!support.volume().finite()) {
                continue;
            }
            double gap = body.bounds().minY() - support.volume().maxY();
            if (Math.abs(gap) <= config.restEpsilon() && body.bounds().overlapsHorizontally(support.volume())) {
                return true;
            }
        }
        return false;
    }

    static boolean penetrating(BodyState body, List<SupportMotion> supports, AntiClipConfig config) {
        for (SupportMotion support : supports) {
            if (!support.volume().finite() || !body.bounds().intersects(support.volume())) {
                continue;
            }
            double sunk = support.volume().maxY() - body.bounds().minY();
            if (sunk > config.maxPenetration()) {
                return true;
            }
        }
        return false;
    }
}
