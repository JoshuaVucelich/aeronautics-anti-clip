package com.vucelich.sablesafelanding;

import com.vucelich.sablesafelanding.sim.Aabb;
import com.vucelich.sablesafelanding.sim.AntiClipConfig;
import com.vucelich.sablesafelanding.sim.AntiClipStep;
import com.vucelich.sablesafelanding.sim.BodyState;
import com.vucelich.sablesafelanding.sim.RateLimiter;
import com.vucelich.sablesafelanding.sim.PoseSnapshot;
import com.vucelich.sablesafelanding.sim.RescueBounds;
import com.vucelich.sablesafelanding.sim.RescueSite;
import com.vucelich.sablesafelanding.sim.Rot;
import com.vucelich.sablesafelanding.sim.SupportMotion;
import com.vucelich.sablesafelanding.sim.Vec3;
import dev.ryanhcode.sable.SableConfig;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

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
    private static final RateLimiter PORTAL_LOGS = new RateLimiter(5_000_000_000L);
    private static final RateLimiter BURIED_LOGS = new RateLimiter(5_000_000_000L);
    private static final RateLimiter CLAMP_LOGS = new RateLimiter(1_000_000_000L);
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
    }

    public static void forgetDimension(String dimension) {
        STEP.forgetDimension(dimension);
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
        if (!SafeLandingConfig.running()) {
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
            if (!(subLevel.getLevel() instanceof ServerLevel server) || !server.getServer().isSameThread()) {
                return false;
            }
            if (!SafeLandingConfig.running()) {
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
            if (RescueSite.defer(inPortal(server, before.bounds()))) {
                logPortal(subLevel.getUniqueId());
                return false;
            }
            after = acceptRescue(server, after);
            if (after == null) {
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
            if (!SafeLandingConfig.running()) {
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
        if (after.rescued() && subLevel.getLevel() instanceof ServerLevel server) {
            if (RescueSite.defer(inPortal(server, before.bounds()))) {
                logPortal(subLevel.getUniqueId());
                return;
            }
            after = acceptRescue(server, after);
            if (after == null) {
                return;
            }
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

    /**
     * @return the pose to apply, or null when every nearby cell is solid
     */
    private static BodyState acceptRescue(ServerLevel level, BodyState restored) {
        boolean worldBlocked = worldBlocked(level, restored.bounds());
        boolean liquid = liquid(level, restored.bounds());
        Vec3 shift = RescueSite.firstClear(
                RescueSite.offsets(),
                offset -> occupied(level, restored.bounds().translate(offset))
        );
        logClamp(restored.id(), shift, worldBlocked, liquid);
        if (shift == null) {
            logBuried(restored.id());
            return null;
        }
        if (shift.lengthSquared() == 0.0) {
            return restored;
        }
        return new BodyState(
                restored.id(),
                restored.position().add(shift),
                Vec3.ZERO,
                restored.bounds().translate(shift),
                restored.orientation(),
                true,
                restored.solidContact()
        );
    }

    /** Bottom-layer columns read when a rescue is about to fire. A hull is not scanned every tick. */
    private static final int PORTAL_SCAN_CAP = 256;

    /**
     * Portal blocks on the bottom layer of the ship. Unloaded columns are skipped so the check
     * does not force a chunk load. The walk stops at {@link #PORTAL_SCAN_CAP}.
     */
    private static boolean inPortal(ServerLevel level, Aabb box) {
        if (box == null || !box.finite()) {
            return false;
        }
        int minX = (int) Math.floor(box.minX());
        int y = (int) Math.floor(box.minY());
        int minZ = (int) Math.floor(box.minZ());
        int maxX = (int) Math.floor(box.maxX());
        int maxZ = (int) Math.floor(box.maxZ());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int checked = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (checked++ >= PORTAL_SCAN_CAP) {
                    return false;
                }
                pos.set(x, y, z);
                if (!level.isLoaded(pos)) {
                    continue;
                }
                BlockState state = level.getBlockState(pos);
                if (state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean occupied(ServerLevel level, Aabb box) {
        try {
            if (box == null || !box.finite()) {
                return true;
            }
            AABB aabb = new AABB(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
            // World blocks and liquids only. The ship's own plot is not an entity and is not in this query.
            return level.containsAnyLiquid(aabb) || !level.noCollision(null, aabb);
        } catch (RuntimeException ex) {
            logFailure(ex);
            return true;
        }
    }

    /** World blocks at the clamped cell. Sub-level blocks are not part of this query. */
    private static boolean worldBlocked(ServerLevel level, Aabb box) {
        try {
            if (box == null || !box.finite()) {
                return true;
            }
            AABB aabb = new AABB(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
            return !level.noCollision(null, aabb);
        } catch (RuntimeException ex) {
            logFailure(ex);
            return true;
        }
    }

    private static boolean liquid(ServerLevel level, Aabb box) {
        try {
            if (box == null || !box.finite()) {
                return true;
            }
            AABB aabb = new AABB(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
            return level.containsAnyLiquid(aabb);
        } catch (RuntimeException ex) {
            logFailure(ex);
            return true;
        }
    }

    private static void logClamp(String id, Vec3 shift, boolean worldBlocked, boolean liquid) {
        if (!CLAMP_LOGS.tryAcquire(System.nanoTime())) {
            return;
        }
        String shiftText = shift == null
                ? "none"
                : String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", shift.x(), shift.y(), shift.z());
        SableSafeLanding.LOGGER.info(
                "LIVE rescue clamp ship {} shift={} worldBlocked={} liquid={}",
                id,
                shiftText,
                worldBlocked,
                liquid
        );
    }

    private static void logPortal(UUID id) {
        if (!PORTAL_LOGS.tryAcquire(System.nanoTime())) {
            return;
        }
        SableSafeLanding.LOGGER.info("Deferred rescue of sub-level {} inside a portal", id);
    }

    private static void logBuried(String id) {
        if (!BURIED_LOGS.tryAcquire(System.nanoTime())) {
            return;
        }
        SableSafeLanding.LOGGER.info("Skipped rescue of sub-level {} because the target is buried", id);
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
