package com.vucelich.sablesafelanding.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One anti-clip substep. Rescue runs first, then a resting body is carried by one contraption.
 */
public final class AntiClipStep {
    private final SafePoseTracker tracker = new SafePoseTracker();
    private final Map<String, Set<String>> idsByDimension = new HashMap<>();
    /** Game time when a body first sat below the bedrock line, keyed by dimension and id. */
    private final Map<String, Long> bedrockSince = new HashMap<>();
    private String dimension = "sim";
    private long time;

    public SafePoseTracker tracker() {
        return tracker;
    }

    public void clear() {
        idsByDimension.clear();
        bedrockSince.clear();
        tracker.retain(Set.of());
    }

    public void forgetDimension(String dimension) {
        idsByDimension.remove(dimension);
        tracker.forgetDimension(dimension);
        String prefix = dimension + "\u0000";
        bedrockSince.keySet().removeIf(key -> key.startsWith(prefix));
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
        return step(bodies, supports, world, config, dt, true);
    }

    /**
     * @param integrateRide when false, a ride only writes the platform velocity. The physics
     *                      step integrates it. Integrating here as well would move the ship twice.
     */
    public List<BodyState> step(
            List<BodyState> bodies,
            List<SupportMotion> supports,
            RescueBounds world,
            AntiClipConfig config,
            double dt,
            boolean integrateRide
    ) {
        if (dt < 0.0) {
            throw new IllegalArgumentException("dt must be non-negative");
        }
        ColliderIndex index = new ColliderIndex(supports, config.spatialCell());
        List<BodyState> next = new ArrayList<>(bodies.size());
        for (BodyState body : bodies) {
            List<SupportMotion> local = index.nearby(queryBounds(body, config, dt), config.maxPairChecks());
            next.add(stepOne(body, local, world, config, dt, integrateRide));
        }
        return List.copyOf(next);
    }

    private static Aabb queryBounds(BodyState body, AntiClipConfig config, double dt) {
        Aabb bounds = body.bounds();
        if (!bounds.finite()) {
            return bounds;
        }
        if (config.sweep() && body.velocity().finite()) {
            Aabb end = bounds.translate(body.velocity().mul(dt));
            bounds = new Aabb(
                    Math.min(bounds.minX(), end.minX()),
                    Math.min(bounds.minY(), end.minY()),
                    Math.min(bounds.minZ(), end.minZ()),
                    Math.max(bounds.maxX(), end.maxX()),
                    Math.max(bounds.maxY(), end.maxY()),
                    Math.max(bounds.maxZ(), end.maxZ())
            );
        }
        return bounds.expand(Math.max(config.restEpsilon(), RIDE_WINDOW));
    }

    private BodyState stepOne(BodyState body, List<SupportMotion> supports, RescueBounds world, AntiClipConfig config, double dt, boolean integrateRide) {
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
        BodyState bedrock = bedrock(body, world, config);
        if (bedrock != null) {
            return bedrock;
        }
        if (config.carry() || config.feedColliders()) {
            body = ride(body, supports, config, integrateRide);
        }
        if (config.sweep() && body.solidContact()) {
            body = sweep(body, supports, config, dt);
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
                return new BodyState(body.id(), saved.position(), VelocityClamp.afterRescue(body.velocity()), saved.bounds(), saved.orientation(), true);
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
                VelocityClamp.afterRescue(body.velocity()),
                body.bounds().translate(delta),
                body.orientation(),
                true
        );
    }

    /**
     * A sub-level that has stayed below {@code minBuildHeight - bedrockMargin} for
     * {@code bedrockTicks} game ticks is put back at the last safe pose in this dimension.
     * Distance is not a gate: the fall itself is what made the pose far. No saved pose means
     * the body is left where it is.
     *
     * @return the restored body, or null when this path does not apply
     */
    private BodyState bedrock(BodyState body, RescueBounds world, AntiClipConfig config) {
        String key = dimension + "\u0000" + body.id();
        double line = world.minBuildHeight() - config.bedrockMargin();
        if (!config.bedrockRescue() || !body.bounds().finite() || body.bounds().minY() >= line) {
            bedrockSince.remove(key);
            return null;
        }
        bedrockSince.putIfAbsent(key, time);
        long elapsed = time - bedrockSince.get(key);
        if (elapsed < config.bedrockTicks()) {
            return null;
        }
        PoseSnapshot saved = tracker.get(dimension, body.id());
        if (!tracker.usableRecent(saved, world, time)) {
            return null;
        }
        bedrockSince.remove(key);
        return new BodyState(body.id(), saved.position(), VelocityClamp.afterRescue(body.velocity()), saved.bounds(), saved.orientation(), true);
    }

    /** Upward speed, in blocks per second, that means the ship is leaving the platform. */
    public static final double TAKEOFF_SPEED = 0.5;
    /** Blocks of platform motion in one tick. Anything larger is a bad sample and is ignored. */
    public static final double MAX_RIDE_STEP = 0.75;
    /** Degrees of platform rotation in one tick. A larger turn is not applied. */
    public static final double MAX_RIDE_DEGREES = 25.0;
    /**
     * How far a ship may be from a block top, above or below, and still count as on that block.
     * Volumes are taken at the platform's new pose, so an elevator that just rose leaves the
     * ship a fraction of a block underneath.
     */
    static final double RIDE_WINDOW = 1.0;

    /**
     * Match a resting ship to the platform's per-tick motion. Translation and the tangential
     * step of a small rotation become a velocity of {@code step * 20}. A step past
     * {@link #MAX_RIDE_STEP} is refused so a bad angle cannot fling the ship.
     */
    /** The support {@link #ride} would use, or null when the ship is not on a platform. */
    public static SupportMotion ridingSupport(BodyState body, List<SupportMotion> supports, AntiClipConfig config) {
        for (SupportMotion support : supports) {
            if (!support.volume().finite() || !support.translation().finite() || !support.pivot().finite() || !support.axis().finite()) {
                continue;
            }
            if (ridingOn(body, support, config)) {
                return support;
            }
        }
        return null;
    }

    public static BodyState ride(BodyState body, List<SupportMotion> supports, AntiClipConfig config, boolean integrate) {
        for (SupportMotion support : supports) {
            if (!support.volume().finite() || !support.translation().finite() || !support.pivot().finite() || !support.axis().finite()) {
                continue;
            }
            if (!ridingOn(body, support, config)) {
                continue;
            }
            double maxAngle = Math.toRadians(MAX_RIDE_DEGREES);
            boolean spin = Math.abs(support.angleRadians()) <= maxAngle && Math.abs(support.angleRadians()) > 1.0e-9;
            Vec3 tangential = spin ? around(body.position(), support).sub(body.position()) : Vec3.ZERO;
            Vec3 linear = support.translation().length() <= MAX_RIDE_STEP ? support.translation() : Vec3.ZERO;
            if (tangential.length() > MAX_RIDE_STEP) {
                tangential = Vec3.ZERO;
                spin = false;
            }
            Vec3 step = linear.add(tangential);
            if (step.lengthSquared() < 1.0e-18) {
                return body;
            }
            Vec3 velocity = step.mul(20.0);
            if (body.velocity().finite()
                    && body.velocity().y() > TAKEOFF_SPEED
                    && body.velocity().y() > velocity.y() + TAKEOFF_SPEED) {
                return body;
            }
            if (!integrate) {
                return new BodyState(body.id(), body.position(), velocity, body.bounds(), body.orientation(), false);
            }
            Rot orientation = spin ? body.orientation().turned(support.axis(), support.angleRadians()) : body.orientation();
            return new BodyState(
                    body.id(),
                    body.position().add(step),
                    velocity,
                    body.bounds().translate(step),
                    orientation,
                    false
            );
        }
        return body;
    }

    /** Kept for callers that still name the coupling carry. It is the capped ride. */
    public static BodyState carry(BodyState body, List<SupportMotion> supports, AntiClipConfig config) {
        return ride(body, supports, config, true);
    }

    private static Vec3 around(Vec3 point, SupportMotion support) {
        return point.sub(support.pivot()).rotate(support.axis(), support.angleRadians()).add(support.pivot());
    }

    private static List<Vec3> corners(Aabb box) {
        return List.of(
                new Vec3(box.minX(), box.minY(), box.minZ()),
                new Vec3(box.minX(), box.minY(), box.maxZ()),
                new Vec3(box.minX(), box.maxY(), box.minZ()),
                new Vec3(box.minX(), box.maxY(), box.maxZ()),
                new Vec3(box.maxX(), box.minY(), box.minZ()),
                new Vec3(box.maxX(), box.minY(), box.maxZ()),
                new Vec3(box.maxX(), box.maxY(), box.minZ()),
                new Vec3(box.maxX(), box.maxY(), box.maxZ())
        );
    }

    private static boolean restingOn(BodyState body, SupportMotion support, AntiClipConfig config) {
        double gap = body.bounds().minY() - support.volume().maxY();
        return Math.abs(gap) <= config.restEpsilon() && body.bounds().overlapsHorizontally(support.volume());
    }

    /**
     * Resting for the ride uses each block's top. The gap may be a block either way: a descending
     * platform opens a gap above the ship, and an ascending one is sampled after it has already
     * moved up through the ship.
     */
    private static boolean ridingOn(BodyState body, SupportMotion support, AntiClipConfig config) {
        double gap = body.bounds().minY() - support.volume().maxY();
        return gap >= -RIDE_WINDOW
                && gap <= RIDE_WINDOW
                && body.bounds().overlapsHorizontally(support.volume());
    }

    static boolean resting(BodyState body, List<SupportMotion> supports, AntiClipConfig config) {
        for (SupportMotion support : supports) {
            if (support.volume().finite() && restingOn(body, support, config)) {
                return true;
            }
        }
        return false;
    }

    private record SweepHit(double time, Vec3 normal) {
    }

    private static BodyState sweep(BodyState body, List<SupportMotion> supports, AntiClipConfig config, double dt) {
        BodyState separated = separate(body, supports, config);
        Vec3 delta = separated.velocity().mul(dt);
        if (delta.lengthSquared() < 1.0e-18) {
            return separated;
        }
        double bestTime = 1.0;
        Vec3 normal = null;
        for (SupportMotion support : supports) {
            Aabb solid = inset(support.volume(), config.maxPenetration());
            if (solid == null) {
                continue;
            }
            SweepHit hit = slab(separated.bounds(), delta, solid);
            if (hit != null && hit.time < bestTime) {
                bestTime = hit.time;
                normal = hit.normal;
            }
        }
        if (normal == null || bestTime >= 1.0) {
            return separated;
        }
        Vec3 velocity = separated.velocity();
        if (normal.y() > 0.5 && velocity.y() < 0.0) {
            velocity = new Vec3(velocity.x(), 0.0, velocity.z());
        } else if (normal.y() < -0.5 && velocity.y() > 0.0) {
            velocity = new Vec3(velocity.x(), 0.0, velocity.z());
        } else if (normal.x() > 0.5 && velocity.x() < 0.0 || normal.x() < -0.5 && velocity.x() > 0.0) {
            velocity = new Vec3(0.0, velocity.y(), velocity.z());
        } else if (normal.z() > 0.5 && velocity.z() < 0.0 || normal.z() < -0.5 && velocity.z() > 0.0) {
            velocity = new Vec3(velocity.x(), velocity.y(), 0.0);
        }
        return separated.moved(delta.mul(Math.max(0.0, bestTime)), velocity);
    }

    private static BodyState separate(BodyState body, List<SupportMotion> supports, AntiClipConfig config) {
        BodyState current = body;
        for (SupportMotion support : supports) {
            if (!support.volume().finite() || !current.bounds().intersects(support.volume())) {
                continue;
            }
            Aabb solid = support.volume();
            Aabb box = current.bounds();
            double overlapX = Math.min(box.maxX(), solid.maxX()) - Math.max(box.minX(), solid.minX());
            double overlapY = Math.min(box.maxY(), solid.maxY()) - Math.max(box.minY(), solid.minY());
            double overlapZ = Math.min(box.maxZ(), solid.maxZ()) - Math.max(box.minZ(), solid.minZ());
            double pushX = overlapX - config.maxPenetration();
            double pushY = overlapY - config.maxPenetration();
            double pushZ = overlapZ - config.maxPenetration();
            if (pushX <= 0.0 && pushY <= 0.0 && pushZ <= 0.0) {
                continue;
            }
            Vec3 delta;
            Vec3 velocity = current.velocity();
            if (pushY > 0.0 && pushY <= pushX && pushY <= pushZ || pushX <= 0.0 && pushZ <= 0.0) {
                double sign = box.center().y() >= solid.center().y() ? 1.0 : -1.0;
                delta = new Vec3(0.0, sign * pushY, 0.0);
                if (sign > 0.0 && velocity.y() < 0.0 || sign < 0.0 && velocity.y() > 0.0) {
                    velocity = new Vec3(velocity.x(), 0.0, velocity.z());
                }
            } else if (pushX > 0.0 && pushX <= pushZ) {
                double sign = box.center().x() >= solid.center().x() ? 1.0 : -1.0;
                delta = new Vec3(sign * pushX, 0.0, 0.0);
                velocity = new Vec3(0.0, velocity.y(), velocity.z());
            } else {
                double sign = box.center().z() >= solid.center().z() ? 1.0 : -1.0;
                delta = new Vec3(0.0, 0.0, sign * pushZ);
                velocity = new Vec3(velocity.x(), velocity.y(), 0.0);
            }
            current = current.moved(delta, velocity);
        }
        return current;
    }

    private static Aabb inset(Aabb box, double amount) {
        if (amount <= 0.0) {
            return box;
        }
        double x = box.maxX() - box.minX();
        double y = box.maxY() - box.minY();
        double z = box.maxZ() - box.minZ();
        double limit = Math.min(x, Math.min(y, z)) * 0.5;
        if (limit <= 1.0e-9) {
            return null;
        }
        double inset = Math.min(amount, limit - 1.0e-9);
        return new Aabb(box.minX() + inset, box.minY() + inset, box.minZ() + inset,
                box.maxX() - inset, box.maxY() - inset, box.maxZ() - inset);
    }

    private static SweepHit slab(Aabb body, Vec3 delta, Aabb solid) {
        double sizeX = body.maxX() - body.minX();
        double sizeY = body.maxY() - body.minY();
        double sizeZ = body.maxZ() - body.minZ();
        double[] start = {body.minX(), body.minY(), body.minZ()};
        double[] move = {delta.x(), delta.y(), delta.z()};
        double[] boxMin = {solid.minX() - sizeX, solid.minY() - sizeY, solid.minZ() - sizeZ};
        double[] boxMax = {solid.maxX(), solid.maxY(), solid.maxZ()};
        double enter = 0.0;
        double exit = 1.0;
        Vec3 normal = Vec3.ZERO;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(move[axis]) < 1.0e-12) {
                if (start[axis] < boxMin[axis] || start[axis] > boxMax[axis]) {
                    return null;
                }
                continue;
            }
            double t1 = (boxMin[axis] - start[axis]) / move[axis];
            double t2 = (boxMax[axis] - start[axis]) / move[axis];
            double near = Math.min(t1, t2);
            double far = Math.max(t1, t2);
            if (near > enter) {
                enter = near;
                double sign = move[axis] < 0.0 ? 1.0 : -1.0;
                normal = axis == 0 ? new Vec3(sign, 0.0, 0.0) : axis == 1 ? new Vec3(0.0, sign, 0.0) : new Vec3(0.0, 0.0, sign);
            }
            exit = Math.min(exit, far);
            if (enter > exit) {
                return null;
            }
        }
        if (enter < 0.0 || enter > 1.0) {
            return null;
        }
        return new SweepHit(enter, normal);
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
