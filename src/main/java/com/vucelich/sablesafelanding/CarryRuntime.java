package com.vucelich.sablesafelanding;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.vucelich.sablesafelanding.sim.Aabb;
import com.vucelich.sablesafelanding.sim.AntiClipConfig;
import com.vucelich.sablesafelanding.sim.AntiClipStep;
import com.vucelich.sablesafelanding.sim.RateLimiter;
import com.vucelich.sablesafelanding.sim.SupportMotion;
import com.vucelich.sablesafelanding.sim.Vec3;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds per-block support volumes for the ride. The anchor delta is the motion since the previous
 * game tick, kept for every substep of the current tick so the ship velocity stays at that rate.
 * A delta past the ride cap is dropped here. A kinematic platform cannot pull, so the ride sets
 * the ship's velocity instead of teleporting it by the angle.
 */
public final class CarryRuntime {
    private static final net.minecraft.world.phys.Vec3 ROTATION_OFFSET = new net.minecraft.world.phys.Vec3(0.5, 0.5, 0.5);
    private static final RateLimiter FAILURE_LOGS = new RateLimiter(5_000_000_000L);
    private static final Map<String, Sample> SAMPLES = new HashMap<>();

    private record Sample(
            long time,
            double x,
            double y,
            double z,
            float angle,
            Vec3 translation,
            double angleRadians,
            Vec3 axis
    ) {
    }

    private CarryRuntime() {
    }

    public static void onPrePhysics(ForgeSablePrePhysicsTickEvent event) {
        SubLevelPhysicsSystem system = event.getPhysicsSystem();
        if (system == null || !(system.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!level.getServer().isSameThread()) {
            SableSafeLanding.LOGGER.warn("Ignoring contraption carry off the server thread");
            return;
        }
        try {
            AntiClipConfig config = SafeLandingConfig.snapshot();
            if (!config.carry() && !config.sweep() && !config.feedColliders()) {
                return;
            }
            List<SupportMotion> supports = new ArrayList<>();
            int seen = 0;
            for (Entity candidate : level.getAllEntities()) {
                if (!(candidate instanceof AbstractContraptionEntity entity) || !ColliderFeedRuntime.isWorldSupport(entity)) {
                    continue;
                }
                if (seen++ >= ColliderFeedRuntime.MAX_CONTRAPTIONS) {
                    break;
                }
                supports.addAll(blocks(level.dimension().location().toString(), entity, level.getGameTime()));
            }
            if (supports.isEmpty()) {
                return;
            }
            if (!(SubLevelContainer.getContainer(level) instanceof ServerSubLevelContainer container)) {
                return;
            }
            PhysicsPipeline pipeline = system.getPipeline();
            java.util.List<ServerSubLevel> ships = new java.util.ArrayList<>();
            for (ServerSubLevel subLevel : container.getAllSubLevels()) {
                if (!subLevel.isRemoved()) {
                    ships.add(subLevel);
                }
            }
            VoidRescueRuntime.advanceAll(ships, pipeline, supports, event.getTimeStep());
        } catch (RuntimeException ex) {
            logFailure(ex);
        }
    }

    public static void clear() {
        SAMPLES.clear();
    }

    public static void forgetDimension(String dimension) {
        String prefix = dimension + "\0";
        SAMPLES.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /**
     * Block-entity ticks move the contraption and then the entity copies that pose into its
     * previous position before the next pre-physics event. The delta between two pre-physics
     * samples is that motion. The first sample stores the pose and applies no delta. Later
     * substeps of the same tick reuse the delta so the velocity is not cleared after one substep.
     */
    private static List<SupportMotion> blocks(String dimension, AbstractContraptionEntity entity, long gameTime) {
        Contraption contraption = entity.getContraption();
        if (contraption == null || contraption.getBlocks().isEmpty()) {
            return List.of();
        }
        net.minecraft.world.phys.Vec3 anchor = entity.getAnchorVec();
        float angleNow = entity instanceof ControlledContraptionEntity controlled ? controlled.getAngle(1.0f) : 0.0f;
        String id = entity.getUUID().toString();
        String key = dimension + "\0" + id;
        Sample previous = SAMPLES.get(key);
        Sample sample;
        if (previous != null && previous.time == gameTime) {
            sample = previous;
        } else {
            Vec3 translation = Vec3.ZERO;
            double radians = 0.0;
            Vec3 axis = new Vec3(0.0, 1.0, 0.0);
            if (previous != null) {
                Vec3 raw = new Vec3(anchor.x - previous.x, anchor.y - previous.y, anchor.z - previous.z);
                if (raw.length() <= AntiClipStep.MAX_RIDE_STEP) {
                    translation = raw;
                }
                if (entity instanceof ControlledContraptionEntity controlled && controlled.getRotationAxis() != null) {
                    axis = axis(controlled.getRotationAxis());
                    float degrees = Mth.wrapDegrees(angleNow - previous.angle);
                    if (Math.abs(degrees) <= AntiClipStep.MAX_RIDE_DEGREES) {
                        radians = Math.toRadians(degrees);
                    }
                }
                if (raw.length() > 1.0e-4 || Math.abs(Mth.wrapDegrees(angleNow - previous.angle)) > 0.05f) {
                    PlatformTrace.note(id, String.format(java.util.Locale.ROOT,
                            "shift=(%.3f,%.3f,%.3f) applied=(%.3f,%.3f,%.3f) angleDeg=%.2f",
                            raw.x(), raw.y(), raw.z(),
                            translation.x(), translation.y(), translation.z(),
                            Mth.wrapDegrees(angleNow - previous.angle)));
                }
            }
            sample = new Sample(gameTime, anchor.x, anchor.y, anchor.z, angleNow, translation, radians, axis);
            SAMPLES.put(key, sample);
        }
        Vec3 pivot = new Vec3(sample.x + 0.5, sample.y + 0.5, sample.z + 0.5);
        net.minecraft.world.phys.Vec3 anchorNow = new net.minecraft.world.phys.Vec3(sample.x, sample.y, sample.z);
        List<SupportMotion> supports = new ArrayList<>();
        for (BlockPos pos : contraption.getBlocks().keySet()) {
            Aabb local = new Aabb(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
            Aabb world = Aabb.enclosing(corners(entity, local, anchorNow, 1.0f));
            supports.add(new SupportMotion(id, world, sample.translation, pivot, sample.axis, sample.angleRadians));
        }
        return supports;
    }

    private static List<Vec3> corners(AbstractContraptionEntity entity, Aabb local, net.minecraft.world.phys.Vec3 anchor, float partial) {
        double[] xs = {local.minX(), local.maxX()};
        double[] ys = {local.minY(), local.maxY()};
        double[] zs = {local.minZ(), local.maxZ()};
        List<Vec3> points = new ArrayList<>(8);
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    net.minecraft.world.phys.Vec3 world = toGlobal(entity, new net.minecraft.world.phys.Vec3(x, y, z), anchor, partial);
                    points.add(new Vec3(world.x, world.y, world.z));
                }
            }
        }
        return points;
    }

    private static net.minecraft.world.phys.Vec3 toGlobal(
            AbstractContraptionEntity entity,
            net.minecraft.world.phys.Vec3 local,
            net.minecraft.world.phys.Vec3 anchor,
            float partial
    ) {
        net.minecraft.world.phys.Vec3 rotated = entity.applyRotation(local.subtract(ROTATION_OFFSET), partial);
        return rotated.add(ROTATION_OFFSET).add(anchor);
    }

    private static Vec3 axis(Direction.Axis direction) {
        return switch (direction) {
            case X -> new Vec3(1.0, 0.0, 0.0);
            case Y -> new Vec3(0.0, 1.0, 0.0);
            case Z -> new Vec3(0.0, 0.0, 1.0);
        };
    }

    private static void logFailure(RuntimeException ex) {
        if (!FAILURE_LOGS.tryAcquire(System.nanoTime())) {
            return;
        }
        SableSafeLanding.LOGGER.warn("Contraption carry failed: {}", ex.toString());
    }
}
