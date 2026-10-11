package com.vucelich.sablesafelanding;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.vucelich.sablesafelanding.sim.Aabb;
import com.vucelich.sablesafelanding.sim.AntiClipConfig;
import com.vucelich.sablesafelanding.sim.AntiClipStep;
import com.vucelich.sablesafelanding.sim.BodyState;
import com.vucelich.sablesafelanding.sim.DeckFollow;
import com.vucelich.sablesafelanding.sim.RateLimiter;
import com.vucelich.sablesafelanding.sim.Rot;
import com.vucelich.sablesafelanding.sim.SupportMotion;
import com.vucelich.sablesafelanding.sim.Vec3;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds per-block support volumes for the ride. The anchor delta is the motion since the previous
 * game tick, kept for every substep of the current tick so the ship velocity stays at that rate.
 * A delta past the ride cap is dropped here.
 * <p>
 * A kinematic deck pushes a car out of itself and does not pull one down, and a velocity aimed
 * through that deck is cancelled. While the car is resting, its offset in the deck frame is
 * stored. After the contraption block entity has moved, the server tick places the car at that
 * offset on the deck's new pivot and angle. Feed and carry both use this placement. With both
 * off, nothing is stored and the car is left where the solver put it.
 */
public final class CarryRuntime {
    private static final net.minecraft.world.phys.Vec3 ROTATION_OFFSET = new net.minecraft.world.phys.Vec3(0.5, 0.5, 0.5);
    private static final RateLimiter FAILURE_LOGS = new RateLimiter(5_000_000_000L);
    private static final Map<String, Sample> SAMPLES = new HashMap<>();
    private static final Map<String, DeckLock> LOCKS = new HashMap<>();
    private static final Set<String> PLACEMENT_LOGGED = new HashSet<>();

    /**
     * Offset of the ship origin in the deck frame, captured while the ship was on the platform.
     * The capture angle is already applied, so a later absolute angle only adds the turn since then.
     */
    private record DeckLock(String contraptionId, Vec3 local, Vec3 axis) {
    }

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
            if (config.feedColliders() || config.carry()) {
                rememberDeck(level, ships, supports, config);
            }
        } catch (RuntimeException ex) {
            logFailure(ex);
        }
    }

    /**
     * The contraption block entity has already applied this tick's motion. Put a locked car on
     * the deck now, so the placement is still there when the trial reads the pose.
     */
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!event.getServer().isSameThread()) {
            return;
        }
        try {
            AntiClipConfig config = SafeLandingConfig.snapshot();
            if (!config.feedColliders() && !config.carry()) {
                LOCKS.clear();
                return;
            }
            if (LOCKS.isEmpty()) {
                return;
            }
            for (ServerLevel level : event.getServer().getAllLevels()) {
                placeLocked(level);
            }
        } catch (RuntimeException ex) {
            logFailure(ex);
        }
    }

    public static void clear() {
        SAMPLES.clear();
        LOCKS.clear();
        PLACEMENT_LOGGED.clear();
    }

    public static void forgetDimension(String dimension) {
        String prefix = dimension + "\0";
        SAMPLES.keySet().removeIf(key -> key.startsWith(prefix));
        LOCKS.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /**
     * Capture the deck-frame offset the first time a ship is resting on a moving contraption.
     * Later substeps keep that offset. Recapturing after the solver has held the ship still
     * would store the gap and the car would never catch the deck.
     */
    private static void rememberDeck(ServerLevel level, List<ServerSubLevel> ships, List<SupportMotion> supports, AntiClipConfig config) {
        String dimension = level.dimension().location().toString();
        for (ServerSubLevel ship : ships) {
            if (ship.isRemoved()) {
                continue;
            }
            String key = dimension + "\0" + ship.getUniqueId();
            BodyState body = bodyOf(ship);
            SupportMotion support = AntiClipStep.ridingSupport(body, supports, config);
            if (support == null) {
                continue;
            }
            // Keep the first offset. Recapturing after the car has sunk into the deck, or after a
            // lookup miss dropped the lock, stores the gap and the car never catches a piston.
            if (LOCKS.containsKey(key)) {
                continue;
            }
            boolean moving = support.translation().lengthSquared() > 1.0e-8 || Math.abs(support.angleRadians()) > 1.0e-9;
            if (!moving) {
                continue;
            }
            double angle = Math.toRadians(deckAngle(level, support.id()));
            Vec3 local = DeckFollow.localOffset(body.position(), support.pivot(), support.axis(), angle);
            if (!local.finite()) {
                continue;
            }
            LOCKS.put(key, new DeckLock(support.id(), local, support.axis()));
        }
    }

    private static void placeLocked(ServerLevel level) {
        if (!(SubLevelContainer.getContainer(level) instanceof ServerSubLevelContainer container)) {
            return;
        }
        PhysicsPipeline pipeline = SubLevelPhysicsSystem.require(level).getPipeline();
        String dimension = level.dimension().location().toString();
        String prefix = dimension + "\0";
        List<String> drop = new ArrayList<>();
        for (Map.Entry<String, DeckLock> entry : LOCKS.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            DeckLock lock = entry.getValue();
            String shipId = entry.getKey().substring(prefix.length());
            ServerSubLevel ship = findShip(container, shipId);
            AbstractContraptionEntity contraption = findContraption(level, lock.contraptionId);
            if (ship == null || contraption == null) {
                if (PLACEMENT_LOGGED.add(shipId + ":miss")) {
                    SableSafeLanding.LOGGER.info(
                            "LIVE deck miss ship={} foundShip={} contraption={}",
                            shipId,
                            ship != null,
                            lock.contraptionId
                    );
                }
                continue;
            }
            net.minecraft.world.phys.Vec3 anchor = contraption.getAnchorVec();
            Vec3 pivot = new Vec3(anchor.x + 0.5, anchor.y + 0.5, anchor.z + 0.5);
            double angle = Math.toRadians(deckAngle(contraption));
            Vec3 target = DeckFollow.worldTarget(lock.local, pivot, lock.axis, angle);
            Vector3dc current = ship.logicalPose().position();
            Vec3 from = new Vec3(current.x(), current.y(), current.z());
            if (!DeckFollow.acceptable(from, target)) {
                drop.add(entry.getKey());
                SableSafeLanding.LOGGER.info(
                        "LIVE deck release ship {} d=({},{},{})",
                        shipId,
                        fmt(target.x() - from.x()),
                        fmt(target.y() - from.y()),
                        fmt(target.z() - from.z())
                );
                continue;
            }
            double dx = target.x() - from.x();
            double dy = target.y() - from.y();
            double dz = target.z() - from.z();
            if (dx * dx + dy * dy + dz * dz < 1.0e-8) {
                continue;
            }
            Quaterniond orientation = new Quaterniond(ship.logicalPose().orientation());
            Vector3d position = new Vector3d(target.x(), target.y(), target.z());
            if (pipeline != null) {
                pipeline.wakeUp(ship);
                pipeline.teleport(ship, position, orientation);
                pipeline.resetVelocity(ship);
            } else {
                ship.logicalPose().position().set(position);
            }
            ship.latestLinearVelocity.zero();
            ship.latestAngularVelocity.zero();
            ship.updateBoundingBox();
            if (PLACEMENT_LOGGED.add(shipId) || (Math.abs(dy) > 1.0 && PLACEMENT_LOGGED.add(shipId + ":far"))) {
                SableSafeLanding.LOGGER.info(
                        "LIVE deck {} y {} -> {} dy={} dx={} dz={} anchorY={}",
                        shipId,
                        fmt(from.y()),
                        fmt(target.y()),
                        fmt(dy),
                        fmt(dx),
                        fmt(dz),
                        fmt(anchor.y)
                );
            }
        }
        drop.forEach(LOCKS::remove);
    }

    private static ServerSubLevel findShip(ServerSubLevelContainer container, String id) {
        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.getUniqueId().toString().equals(id) && !subLevel.isRemoved()) {
                return subLevel;
            }
        }
        return null;
    }

    private static AbstractContraptionEntity findContraption(ServerLevel level, String id) {
        try {
            Entity direct = level.getEntity(UUID.fromString(id));
            if (direct instanceof AbstractContraptionEntity contraption && contraption.isAlive()) {
                return contraption;
            }
        } catch (IllegalArgumentException ex) {
            return null;
        }
        for (Entity candidate : level.getAllEntities()) {
            if (candidate.getUUID().toString().equals(id) && candidate instanceof AbstractContraptionEntity contraption && contraption.isAlive()) {
                return contraption;
            }
        }
        return null;
    }

    private static float deckAngle(ServerLevel level, String id) {
        AbstractContraptionEntity contraption = findContraption(level, id);
        if (contraption != null) {
            return deckAngle(contraption);
        }
        return 0.0f;
    }

    private static float deckAngle(AbstractContraptionEntity entity) {
        if (entity instanceof ControlledContraptionEntity controlled && controlled.getRotationAxis() != null) {
            return controlled.getAngle(1.0f);
        }
        return 0.0f;
    }

    private static BodyState bodyOf(ServerSubLevel subLevel) {
        BoundingBox3dc box = subLevel.boundingBox();
        Vector3dc position = subLevel.logicalPose().position();
        return new BodyState(
                subLevel.getUniqueId().toString(),
                new Vec3(position.x(), position.y(), position.z()),
                Vec3.ZERO,
                new Aabb(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()),
                Rot.IDENTITY,
                false
        );
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
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
