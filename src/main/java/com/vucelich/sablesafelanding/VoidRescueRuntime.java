package com.vucelich.sablesafelanding;

import com.vucelich.sablesafelanding.sim.Aabb;
import com.vucelich.sablesafelanding.sim.AntiClipConfig;
import com.vucelich.sablesafelanding.sim.AntiClipStep;
import com.vucelich.sablesafelanding.sim.BodyState;
import com.vucelich.sablesafelanding.sim.RescueBounds;
import com.vucelich.sablesafelanding.sim.Rot;
import com.vucelich.sablesafelanding.sim.Vec3;
import dev.ryanhcode.sable.SableConfig;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Restores a Sable sub-level before {@code ServerSubLevel.tick} can delete it for an extreme Y.
 */
public final class VoidRescueRuntime {
    private static final AntiClipStep STEP = new AntiClipStep();
    private static final long LOG_GAP_NANOS = 5_000_000_000L;
    private static long lastRescueLog;
    private static long lastFailureLog;
    private static boolean removalHookSeen;

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
            STEP.bind(dimension, subLevel.getLevel().getGameTime());
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
            if (!after.rescued()) {
                return false;
            }
            apply(subLevel, pipeline, after);
            subLevel.updateBoundingBox();
            logRescue(subLevel.getUniqueId());
            return true;
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

    private static void apply(ServerSubLevel subLevel, PhysicsPipeline pipeline, BodyState restored) {
        Vector3d position = new Vector3d(restored.position().x(), restored.position().y(), restored.position().z());
        Rot rot = restored.orientation();
        Quaterniond orientation = new Quaterniond(rot.x(), rot.y(), rot.z(), rot.w());
        if (pipeline != null) {
            try {
                pipeline.teleport(subLevel, position, orientation);
                pipeline.resetVelocity(subLevel);
            } catch (RuntimeException ex) {
                subLevel.logicalPose().position().set(position);
                subLevel.logicalPose().orientation().set(orientation);
            }
        } else {
            subLevel.logicalPose().position().set(position);
            subLevel.logicalPose().orientation().set(orientation);
        }
        subLevel.latestLinearVelocity.zero();
        subLevel.latestAngularVelocity.zero();
    }

    private static void logRescue(UUID id) {
        long now = System.nanoTime();
        if (now - lastRescueLog < LOG_GAP_NANOS) {
            return;
        }
        lastRescueLog = now;
        SableSafeLanding.LOGGER.info("Rescued sub-level {} from the void", id);
    }

    private static void logFailure(RuntimeException ex) {
        long now = System.nanoTime();
        if (now - lastFailureLog < LOG_GAP_NANOS) {
            return;
        }
        lastFailureLog = now;
        SableSafeLanding.LOGGER.warn("Void rescue failed: {}", ex.toString());
    }
}
