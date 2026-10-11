package com.vucelich.sablesafelanding;

import com.vucelich.sablesafelanding.sim.Aabb;
import com.vucelich.sablesafelanding.sim.AntiClipConfig;
import com.vucelich.sablesafelanding.sim.AntiClipStep;
import com.vucelich.sablesafelanding.sim.BodyState;
import com.vucelich.sablesafelanding.sim.RateLimiter;
import com.vucelich.sablesafelanding.sim.PoseSnapshot;
import com.vucelich.sablesafelanding.sim.RescueBounds;
import com.vucelich.sablesafelanding.sim.Rot;
import com.vucelich.sablesafelanding.sim.SupportMotion;
import com.vucelich.sablesafelanding.sim.Vec3;
import dev.ryanhcode.sable.SableConfig;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Restores a Sable sub-level before {@code ServerSubLevel.tick} can delete it for an extreme Y.
 */
public final class VoidRescueRuntime {
    private static final AntiClipStep STEP = new AntiClipStep();
    private static final RateLimiter RESCUE_LOGS = new RateLimiter(5_000_000_000L);
    private static final RateLimiter BEDROCK_LOGS = new RateLimiter(5_000_000_000L);
    private static final RateLimiter FAILURE_LOGS = new RateLimiter(5_000_000_000L);
    /**
     * Where a resting ship started this game tick, and the capped platform step for the whole tick.
     * Post-physics places the ship at {@code start + step * partial}. Rapier is not given that
     * velocity: a kinematic floor cancels a downward component, and integrating it as well as the
     * placement would move the ship twice on the way up or around a bearing.
     */
    private static final Map<String, PlatformFollow> FOLLOWS = new HashMap<>();

    private record PlatformFollow(long time, Vec3 start, Vec3 step) {
    }
    private static boolean removalHookSeen;
    private static boolean loggedThread;
    private static int ticksWithShipsBeforeHook;

    /**
     * Set from the removal mixin once Sable's bounding-box invoke was matched.
     */
    public static void noteRemovalHook() {
        removalHookSeen = true;
    }

    public static boolean removalHookSeen() {
        return removalHookSeen;
    }

    public static void clear() {
        STEP.clear();
        FOLLOWS.clear();
    }

    public static void forgetDimension(String dimension) {
        STEP.forgetDimension(dimension);
        String prefix = dimension + "\u0000";
        FOLLOWS.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private VoidRescueRuntime() {
    }

    public static void onPrePhysics(ForgeSablePrePhysicsTickEvent event) {
        SubLevelPhysicsSystem system = event.getPhysicsSystem();
        if (system == null || system.getLevel() == null) {
            return;
        }
        if (!system.getLevel().getServer().isSameThread()) {
            SableSafeLanding.LOGGER.warn("Ignoring pre-physics rescue off the server thread");
            return;
        }
        if (!loggedThread) {
            loggedThread = true;
            SableSafeLanding.LOGGER.info("ForgeSablePrePhysicsTickEvent thread {}", Thread.currentThread().getName());
        }
        SubLevelContainer container = SubLevelContainer.getContainer(system.getLevel());
        if (!(container instanceof ServerSubLevelContainer server)) {
            return;
        }
        PhysicsPipeline pipeline = system.getPipeline();
        Set<String> live = new HashSet<>();
        for (ServerSubLevel subLevel : server.getAllSubLevels()) {
            if (subLevel.isRemoved()) {
                continue;
            }
            live.add(subLevel.getUniqueId().toString());
            consider(subLevel, pipeline);
        }
        if (!removalHookSeen && !live.isEmpty() && ++ticksWithShipsBeforeHook == 40) {
            SableSafeLanding.LOGGER.warn(
                    "Sable removal hook has not run; void rescue will not see the post-bounds check. Leaving deletion to Sable when the hook is absent"
            );
        }
        try {
            STEP.retainDimension(system.getLevel().dimension().location().toString(), live);
        } catch (RuntimeException ex) {
            logFailure(ex);
        }
    }

    /**
     * @return true when the sub-level was moved back to a safe pose and its bounds were refreshed
     */
    public static boolean consider(ServerSubLevel subLevel, PhysicsPipeline pipeline) {
        try {
            if (subLevel.isRemoved()) {
                return false;
            }
            if (subLevel.getLevel() instanceof ServerLevel server && !server.getServer().isSameThread()) {
                return false;
            }
            AntiClipConfig config = SafeLandingConfig.snapshot();
            String dimension = subLevel.getLevel().dimension().location().toString();
            long time = subLevel.getLevel().getGameTime();
            String id = subLevel.getUniqueId().toString();
            STEP.bind(dimension, time);
            SafePoseSavedData saved = subLevel.getLevel() instanceof ServerLevel serverLevel ? SafePoseSavedData.get(serverLevel) : null;
            if (saved != null && STEP.tracker().get(dimension, id) == null) {
                PoseSnapshot stored = saved.recall(id);
                if (stored != null) {
                    STEP.tracker().put(dimension, id, stored);
                }
            }
            BoundingBox3dc box = subLevel.boundingBox();
            Aabb bounds = new Aabb(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
            RescueBounds world = RescueBounds.of(
                    subLevel.getLevel().getMinBuildHeight(),
                    SableConfig.SUB_LEVEL_REMOVE_MIN.getAsDouble(),
                    SableConfig.SUB_LEVEL_REMOVE_MAX.getAsDouble(),
                    config
            );
            BodyState before = snapshot(subLevel, bounds);
            BodyState after = STEP.step(List.of(before), List.of(), world, config, 1.0 / 20.0).get(0);
            if (saved != null) {
                PoseSnapshot remembered = STEP.tracker().get(dimension, id);
                if (remembered != null && remembered.time() == time) {
                    saved.remember(id, remembered);
                }
            }
            if (!after.rescued()) {
                return false;
            }
            apply(subLevel, pipeline, after, true);
            subLevel.updateBoundingBox();
            logRescue(subLevel.getUniqueId(), before, after);
            return true;
        } catch (RuntimeException ex) {
            logFailure(ex);
            return false;
        }
    }

    /**
     * Rescue, carry, and sweep for one sub-level. Pose is written when the step moved it
     * or took away the velocity that would have tunneled.
     */
    public static void advance(ServerSubLevel subLevel, PhysicsPipeline pipeline, java.util.List<SupportMotion> supports, double dt) {
        advanceAll(java.util.List.of(subLevel), pipeline, supports, dt);
    }

    /**
     * One spatial index for every ship in this substep. Callers must not step ships one at a time.
     */
    public static void advanceAll(java.util.List<ServerSubLevel> subLevels, PhysicsPipeline pipeline, java.util.List<SupportMotion> supports, double dt) {
        try {
            if (subLevels.isEmpty()) {
                return;
            }
            ServerSubLevel first = subLevels.get(0);
            if (!(first.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
                return;
            }
            AntiClipConfig config = SafeLandingConfig.snapshot();
            String dimension = level.dimension().location().toString();
            long time = level.getGameTime();
            STEP.bind(dimension, time);
            SafePoseSavedData saved = SafePoseSavedData.get(level);
            RescueBounds world = RescueBounds.of(
                    level.getMinBuildHeight(),
                    SableConfig.SUB_LEVEL_REMOVE_MIN.getAsDouble(),
                    SableConfig.SUB_LEVEL_REMOVE_MAX.getAsDouble(),
                    config
            );
            java.util.List<ServerSubLevel> live = new java.util.ArrayList<>();
            java.util.List<BodyState> before = new java.util.ArrayList<>();
            for (ServerSubLevel subLevel : subLevels) {
                if (subLevel.isRemoved()) {
                    continue;
                }
                String id = subLevel.getUniqueId().toString();
                if (STEP.tracker().get(dimension, id) == null) {
                    PoseSnapshot stored = saved.recall(id);
                    if (stored != null) {
                        STEP.tracker().put(dimension, id, stored);
                    }
                }
                BoundingBox3dc box = subLevel.boundingBox();
                Aabb bounds = new Aabb(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
                live.add(subLevel);
                before.add(snapshot(subLevel, bounds).withSolidContact(solidContact(subLevel, supports, config)));
            }
            if (live.isEmpty()) {
                return;
            }
            java.util.List<BodyState> after = STEP.step(before, supports, world, config, dt, false);
            for (int i = 0; i < live.size(); i++) {
                finish(live.get(i), pipeline, before.get(i), after.get(i), saved, time);
            }
        } catch (RuntimeException ex) {
            logFailure(ex);
        }
    }

    private static void finish(ServerSubLevel subLevel, PhysicsPipeline pipeline, BodyState before, BodyState after, SafePoseSavedData saved, long time) {
        PoseSnapshot remembered = STEP.tracker().get(subLevel.getLevel().dimension().location().toString(), subLevel.getUniqueId().toString());
        if (remembered != null && remembered.time() == time) {
            saved.remember(subLevel.getUniqueId().toString(), remembered);
        }
        boolean moved = after.position().sub(before.position()).lengthSquared() > 1.0e-12
                || !after.orientation().equals(before.orientation());
        boolean slowed = after.velocity().sub(before.velocity()).lengthSquared() > 1.0e-12;
        if (!after.rescued() && !moved && !slowed) {
            return;
        }
        apply(subLevel, pipeline, after, after.rescued());
        if (!after.rescued()) {
            Vector3d desired = new Vector3d(after.velocity().x(), after.velocity().y(), after.velocity().z());
            if (slowed) {
                String key = subLevel.getLevel().dimension().location().toString() + "\u0000" + subLevel.getUniqueId();
                PlatformFollow existing = FOLLOWS.get(key);
                if (existing == null || existing.time != time) {
                    FOLLOWS.put(key, new PlatformFollow(
                            time,
                            before.position(),
                            after.velocity().mul(1.0 / 20.0)
                    ));
                }
                PlatformTrace.note("ride:" + subLevel.getUniqueId(),
                        String.format(java.util.Locale.ROOT, "vel=(%.2f,%.2f,%.2f) shipMinY=%.2f",
                                desired.x, desired.y, desired.z, before.bounds().minY()));
            }
            subLevel.latestLinearVelocity.set(desired);
        }
        subLevel.updateBoundingBox();
        if (after.rescued()) {
            logRescue(subLevel.getUniqueId(), before, after);
        }
    }

    /**
     * After Rapier has moved kinematic colliders and solved contacts, put a resting ship on the
     * platform pose for this substep. Doing it earlier lets the floor push a descending car back up.
     */
    public static void onPostPhysics(ForgeSablePostPhysicsTickEvent event) {
        SubLevelPhysicsSystem system = event.getPhysicsSystem();
        if (system == null || !(system.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!level.getServer().isSameThread() || FOLLOWS.isEmpty()) {
            return;
        }
        try {
            catchUp(level, system.getPipeline(), system.getPartialPhysicsTick());
        } catch (RuntimeException ex) {
            logFailure(ex);
        }
    }

    private static void catchUp(ServerLevel level, PhysicsPipeline pipeline, double partial) {
        if (!(SubLevelContainer.getContainer(level) instanceof ServerSubLevelContainer container)) {
            return;
        }
        String dimension = level.dimension().location().toString();
        long time = level.getGameTime();
        double fraction = Math.max(0.0, Math.min(1.0, partial));
        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) {
                continue;
            }
            String key = dimension + "\u0000" + subLevel.getUniqueId();
            PlatformFollow follow = FOLLOWS.get(key);
            if (follow == null || follow.time != time || follow.step.lengthSquared() < 1.0e-18) {
                continue;
            }
            Vec3 target = follow.start.add(follow.step.mul(fraction));
            Vector3dc current = subLevel.logicalPose().position();
            double dx = target.x() - current.x();
            double dy = target.y() - current.y();
            double dz = target.z() - current.z();
            if (dx * dx + dy * dy + dz * dz > 1.0e-12) {
                Quaterniond orientation = new Quaterniond(subLevel.logicalPose().orientation());
                Vector3d position = new Vector3d(target.x(), target.y(), target.z());
                if (pipeline != null) {
                    try {
                        pipeline.teleport(subLevel, position, orientation);
                        pipeline.resetVelocity(subLevel);
                    } catch (RuntimeException ex) {
                        subLevel.logicalPose().position().set(position);
                        logFailure(ex);
                    }
                } else {
                    subLevel.logicalPose().position().set(position);
                }
                subLevel.updateBoundingBox();
            } else if (pipeline != null) {
                try {
                    pipeline.resetVelocity(subLevel);
                } catch (RuntimeException ex) {
                    logFailure(ex);
                }
            }
            subLevel.latestLinearVelocity.set(follow.step.x() * 20.0, follow.step.y() * 20.0, follow.step.z() * 20.0);
        }
    }

    private static boolean solidContact(ServerSubLevel subLevel, List<SupportMotion> supports, AntiClipConfig config) {
        if (!config.sweep() || supports.isEmpty()) {
            return false;
        }
        try {
            return BlockContact.overlaps(subLevel, supports);
        } catch (RuntimeException ex) {
            logFailure(ex);
            return false;
        }
    }

    private static BodyState snapshot(ServerSubLevel subLevel, Aabb bounds) {
        Vector3dc position = subLevel.logicalPose().position();
        Quaterniondc orientation = subLevel.logicalPose().orientation();
        Vector3d velocity = subLevel.latestLinearVelocity;
        return new BodyState(
                subLevel.getUniqueId().toString(),
                new Vec3(position.x(), position.y(), position.z()),
                new Vec3(velocity.x, velocity.y, velocity.z),
                bounds,
                new Rot(orientation.x(), orientation.y(), orientation.z(), orientation.w()),
                false
        );
    }

    private static void apply(ServerSubLevel subLevel, PhysicsPipeline pipeline, BodyState restored, boolean zeroVelocity) {
        Vector3d position = new Vector3d(restored.position().x(), restored.position().y(), restored.position().z());
        Rot rot = restored.orientation();
        Quaterniond orientation = new Quaterniond(rot.x(), rot.y(), rot.z(), rot.w());
        if (pipeline != null) {
            try {
                pipeline.teleport(subLevel, position, orientation);
                if (zeroVelocity) {
                    pipeline.resetVelocity(subLevel);
                }
            } catch (RuntimeException ex) {
                subLevel.logicalPose().position().set(position);
                subLevel.logicalPose().orientation().set(orientation);
            }
        } else {
            subLevel.logicalPose().position().set(position);
            subLevel.logicalPose().orientation().set(orientation);
        }
        if (zeroVelocity) {
            subLevel.latestLinearVelocity.zero();
            subLevel.latestAngularVelocity.zero();
        }
    }

    private static void logRescue(UUID id, BodyState before, BodyState after) {
        boolean bedrock = before.bounds().finite() && after.position().y() - before.position().y() > 1.0
                && before.bounds().minY() > -1_000.0;
        long now = System.nanoTime();
        if (bedrock) {
            if (!BEDROCK_LOGS.tryAcquire(now)) {
                return;
            }
            SableSafeLanding.LOGGER.info(
                    "Rescued sub-level {} from below bedrock to y {} vel=0",
                    id,
                    String.format(java.util.Locale.ROOT, "%.2f", after.bounds().minY())
            );
            return;
        }
        if (!RESCUE_LOGS.tryAcquire(now)) {
            return;
        }
        SableSafeLanding.LOGGER.info("Rescued sub-level {} from the void", id);
    }

    private static void logFailure(RuntimeException ex) {
        if (!FAILURE_LOGS.tryAcquire(System.nanoTime())) {
            return;
        }
        SableSafeLanding.LOGGER.warn("Void rescue failed: {}", ex.toString());
    }
}
