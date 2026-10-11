package com.vucelich.sablesafelanding;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.vucelich.sablesafelanding.sim.Aabb;
import com.vucelich.sablesafelanding.sim.AabbMerger;
import com.vucelich.sablesafelanding.sim.AntiClipConfig;
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
 * Parents a sub-level that is resting on a world contraption to that contraption's substep motion.
 * The anchor is lerped the way Sable lerps it, so the carried pose matches the kinematic body.
 */
public final class CarryRuntime {
    private static final net.minecraft.world.phys.Vec3 ROTATION_OFFSET = new net.minecraft.world.phys.Vec3(0.5, 0.5, 0.5);
    private static final RateLimiter FAILURE_LOGS = new RateLimiter(5_000_000_000L);
    private static final Map<String, Sample> SAMPLES = new HashMap<>();

    private record Sample(double x, double y, double z, float angle) {
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
            if (!config.carry() && !config.sweep()) {
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
                supports.addAll(blocks(level.dimension().location().toString(), entity));
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
     * previous position before the next pre-physics event. Sampling here is what still sees
     * the motion. The first sample stores the pose and applies no delta.
     */
    private static List<SupportMotion> blocks(String dimension, AbstractContraptionEntity entity) {
        Contraption contraption = entity.getContraption();
        if (contraption == null || contraption.getBlocks().isEmpty()) {
            return List.of();
        }
        net.minecraft.world.phys.Vec3 anchor1 = entity.getAnchorVec();
        float angle1 = entity instanceof ControlledContraptionEntity controlled ? controlled.getAngle(1.0f) : 0.0f;
        String id = entity.getUUID().toString();
        String key = dimension + "\0" + id;
        Sample last = SAMPLES.put(key, new Sample(anchor1.x, anchor1.y, anchor1.z, angle1));
        net.minecraft.world.phys.Vec3 anchor0 = last == null
                ? anchor1
                : new net.minecraft.world.phys.Vec3(last.x, last.y, last.z);
        net.minecraft.world.phys.Vec3 shift = anchor1.subtract(anchor0);
        Vec3 translation = new Vec3(shift.x, shift.y, shift.z);
        Vec3 pivot = new Vec3(anchor0.x + 0.5, anchor0.y + 0.5, anchor0.z + 0.5);
        Vec3 axis = new Vec3(0.0, 1.0, 0.0);
        double angle = 0.0;
        if (entity instanceof ControlledContraptionEntity controlled && controlled.getRotationAxis() != null && last != null) {
            axis = axis(controlled.getRotationAxis());
            angle = Math.toRadians(Mth.wrapDegrees(angle1 - last.angle));
        }
        List<Aabb> boxes = new ArrayList<>();
        for (BlockPos pos : contraption.getBlocks().keySet()) {
            Aabb local = new Aabb(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
            boxes.add(Aabb.enclosing(corners(entity, local, anchor0, 0.0f)));
        }
        List<SupportMotion> supports = new ArrayList<>();
        for (Aabb world : AabbMerger.merge(boxes)) {
            supports.add(new SupportMotion(id, world, translation, pivot, axis, angle));
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
