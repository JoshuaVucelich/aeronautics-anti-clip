package com.vucelich.sablesafelanding;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.vucelich.sablesafelanding.sim.Aabb;
import com.vucelich.sablesafelanding.sim.AabbMerger;
import com.vucelich.sablesafelanding.sim.AntiClipConfig;
import com.vucelich.sablesafelanding.sim.AntiClipStep;
import com.vucelich.sablesafelanding.sim.CarryBroadphase;
import com.vucelich.sablesafelanding.sim.CarryGate;
import com.vucelich.sablesafelanding.sim.DeckContact;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds per-block support volumes for the ride. The anchor delta is the motion since the previous
 * game tick, kept for every substep of the current tick so the ship velocity stays at that rate.
 * A delta past the ride cap is dropped here.
 * <p>
 * A kinematic deck pushes a car out of itself and does not pull one down. Carry, and only carry,
 * starts a provisional lock on the first real block contact. The lock is not a capture, and the
 * car is not placed, until that contact has held for {@link CarryGate#CONTACT_HOLD} ticks. A miss
 * before then drops it. The collider feed never starts a lock and does not teleport.
 * After the confirm, the server tick places the car when the deck has drifted past a threshold.
 * The placement is a pose, so it is not applied again while the deck is still.
 * <p>
 * Upward speed relative to the deck starts the takeoff streak while the car is still on the blocks.
 * The car is not placed while that streak is running, so thrust from keys other than the movement
 * keys is not pulled back onto the deck. The lock is also dropped when contact is gone, the
 * contraption is removed or disassembled, the deck sits idle, or a player is steering. Steering
 * skips the placement, so pilot input is not written back.
 * <p>
 * Ship-hull parks an unpiloted hull at or under 0.05 blocks/s. Its notes say a Sable
 * center-of-mass teleport is copied onto that parked pose and is not a wake. Sable 2.0.3
 * {@code RapierPhysicsPipeline.teleport} writes the Rapier body, then the logical pose, and
 * returns without {@code wakeUp}. This listener calls {@code PhysicsPipeline.teleport} from
 * {@code ServerTickEvent.Post} only when the deck has drifted, and does not call
 * {@code resetVelocity}. A sleeping body keeps the pre-teleport pose, so the next read looks
 * like a launch; the drift correction wakes that one body first. Ticks that do not teleport
 * do not wake, so a parked deck can still sleep. The ship-hull mixin source was not in this
 * workspace; the Sable method order was read from that class, and the "teleport is not a wake"
 * rule from ship-hull 1.0.0-phase7.
 */
public final class CarryRuntime {
    private static final net.minecraft.world.phys.Vec3 ROTATION_OFFSET = new net.minecraft.world.phys.Vec3(0.5, 0.5, 0.5);
    private static final RateLimiter FAILURE_LOGS = new RateLimiter(5_000_000_000L);
    private static final Map<String, Sample> SAMPLES = new HashMap<>();
    private static final Map<String, DeckLock> LOCKS = new HashMap<>();
    private static final Map<String, CachedSupports> SUPPORT_CACHE = new HashMap<>();
    private static final Map<String, CachedSupports> BLOCK_CACHE = new HashMap<>();
    private static final Set<String> PLACEMENT_LOGGED = new HashSet<>();
    private static final Field JUMPING = jumpingField();

    /**
     * Deck-frame offset and boarding orientation, captured once contact has held.
     * The contraption is kept by reference: a piston outside the entity-ticking distance drops
     * out of the level lookup while its block entity is still moving it. The reference is
     * dropped when that entity is actually removed or the contraption has no blocks.
     * {@code capturedAngle} is Create's accumulated angle, not a wrapped one-tick sample.
     */
    private static final class DeckLock {
        private final AbstractContraptionEntity contraption;
        private final String contraptionId;
        private final ServerSubLevel ship;
        private final Vec3 local;
        private final Vec3 axis;
        private final double orientationX;
        private final double orientationY;
        private final double orientationZ;
        private final double orientationW;
        private final double capturedAngle;
        private double lastAnchorX;
        private double lastAnchorY;
        private double lastAnchorZ;
        private double lastAngle;
        private double lastPlacedAngle;
        private int idleTicks;
        private int missTicks;
        private int contactTicks;
        private int takeoffTicks;
        private boolean confirmed;
        /** Log body velocity against the pose delta once, on the tick after the first teleport. */
        private boolean pendingVelocityLog;
        private boolean velocityLogged;

        private DeckLock(
                AbstractContraptionEntity contraption,
                String contraptionId,
                ServerSubLevel ship,
                Vec3 local,
                Vec3 axis,
                double orientationX,
                double orientationY,
                double orientationZ,
                double orientationW,
                double capturedAngle,
                double anchorX,
                double anchorY,
                double anchorZ
        ) {
            this.contraption = contraption;
            this.contraptionId = contraptionId;
            this.ship = ship;
            this.local = local;
            this.axis = axis;
            this.orientationX = orientationX;
            this.orientationY = orientationY;
            this.orientationZ = orientationZ;
            this.orientationW = orientationW;
            this.capturedAngle = capturedAngle;
            this.lastAnchorX = anchorX;
            this.lastAnchorY = anchorY;
            this.lastAnchorZ = anchorZ;
            this.lastAngle = capturedAngle;
            this.lastPlacedAngle = capturedAngle;
            this.contactTicks = 1;
        }
    }

    /** Bearing angle at capture, and the yaw of the pose that was boarded. */
    public record YawSample(double capturedDeckDegrees, double boardedYawDegrees, boolean held) {
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

    private record CachedSupports(long time, List<SupportMotion> supports) {
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
            List<SupportMotion> supports = supportsFor(level);
            if (supports.isEmpty()) {
                return;
            }
            if (!(SubLevelContainer.getContainer(level) instanceof ServerSubLevelContainer container)) {
                return;
            }
            PhysicsPipeline pipeline = system.getPipeline();
            List<ServerSubLevel> ships = new ArrayList<>();
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

    /**
     * The contraption block entity has already applied this tick's motion. Capture, release,
     * and a drift-threshold placement all happen here, once per game tick.
     */
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!event.getServer().isSameThread()) {
            return;
        }
        try {
            AntiClipConfig config = SafeLandingConfig.snapshot();
            if (!config.carry()) {
                LOCKS.clear();
                return;
            }
            for (ServerLevel level : event.getServer().getAllLevels()) {
                tickCarry(level);
            }
        } catch (RuntimeException ex) {
            logFailure(ex);
        }
    }

    public static void clear() {
        SAMPLES.clear();
        LOCKS.clear();
        SUPPORT_CACHE.clear();
        BLOCK_CACHE.clear();
        PLACEMENT_LOGGED.clear();
    }

    public static void forgetDimension(String dimension) {
        String prefix = dimension + "\0";
        SAMPLES.keySet().removeIf(key -> key.startsWith(prefix));
        LOCKS.keySet().removeIf(key -> key.startsWith(prefix));
        BLOCK_CACHE.keySet().removeIf(key -> key.startsWith(prefix));
        SUPPORT_CACHE.remove(dimension);
    }

    public static boolean holding(UUID shipId) {
        DeckLock lock = lockFor(shipId);
        return lock != null && lock.confirmed;
    }

    public static YawSample yawSample(UUID shipId) {
        DeckLock lock = lockFor(shipId);
        if (lock == null || !lock.confirmed) {
            return new YawSample(0.0, 0.0, false);
        }
        double boarded = Math.toDegrees(new Rot(
                lock.orientationX, lock.orientationY, lock.orientationZ, lock.orientationW
        ).yaw());
        return new YawSample(Math.toDegrees(lock.capturedAngle), boarded, true);
    }

    private static DeckLock lockFor(UUID shipId) {
        if (shipId == null) {
            return null;
        }
        String suffix = "\0" + shipId;
        for (Map.Entry<String, DeckLock> entry : LOCKS.entrySet()) {
            if (entry.getKey().endsWith(suffix)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static void tickCarry(ServerLevel level) {
        if (!(SubLevelContainer.getContainer(level) instanceof ServerSubLevelContainer container)) {
            return;
        }
        String dimension = level.dimension().location().toString();
        String prefix = dimension + "\0";
        List<DeckBox> decks = deckBoxes(level, dimension);
        List<String> drop = new ArrayList<>();
        for (Map.Entry<String, DeckLock> entry : LOCKS.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            DeckLock lock = entry.getValue();
            String shipId = entry.getKey().substring(prefix.length());
            ServerSubLevel ship = lock.ship;
            if (ship == null || ship.isRemoved()) {
                drop.add(entry.getKey());
                continue;
            }
            List<SupportMotion> mine = blocksCached(level, dimension, lock.contraption);
            if (!lock.confirmed) {
                if (!holdUntilConfirmed(level, lock, ship, shipId, mine)) {
                    drop.add(entry.getKey());
                }
                continue;
            }
            CarryGate.Reason reason = releaseReason(level, lock, ship, mine);
            if (reason != null) {
                if (reason == CarryGate.Reason.TAKEOFF) {
                    SableSafeLanding.LOGGER.info(
                            "LIVE deck release ship {} reason=takeoff vy={}",
                            shipId,
                            fmt(bodyUpSpeed(level, ship))
                    );
                } else {
                    SableSafeLanding.LOGGER.info(
                            "LIVE deck release ship {} reason={}",
                            shipId,
                            CarryGate.reasonName(reason)
                    );
                }
                drop.add(entry.getKey());
                continue;
            }
            // A climb that has left the deck has to keep its velocity. Pulling it back is the glue.
            if (lock.takeoffTicks > 0) {
                continue;
            }
            if (!place(level, lock, ship, shipId)) {
                drop.add(entry.getKey());
            }
        }
        for (String key : drop) {
            LOCKS.remove(key);
        }
        for (ServerSubLevel ship : container.getAllSubLevels()) {
            if (ship.isRemoved() || piloting(level, ship)) {
                continue;
            }
            String key = prefix + ship.getUniqueId();
            if (LOCKS.containsKey(key)) {
                continue;
            }
            Aabb hull = shipBox(ship);
            if (hull == null) {
                continue;
            }
            Aabb query = hull.expand(CarryBroadphase.INFLATE);
            for (DeckBox deck : decks) {
                if (!query.intersects(deck.bounds)) {
                    continue;
                }
                // Block test is only this contraption. A ship that missed every box never gets here.
                BlockContact.Touch touch = BlockContact.touch(
                        ship,
                        blocksCached(level, dimension, deck.entity),
                        CarryGate.CAPTURE_GAP
                );
                if (touch.contact() != CarryGate.Contact.RESTING || touch.supportId() == null) {
                    continue;
                }
                // A stopped deck must not grab the car again. Idle release would otherwise be undone
                // on the next tick, and a drive off the elevator would be pulled back.
                Sample sample = SAMPLES.get(prefix + touch.supportId());
                boolean deckMoving = sample != null
                        && (sample.translation.length() >= CarryGate.IDLE_ANCHOR
                        || Math.abs(sample.angleRadians) >= CarryGate.IDLE_ANGLE);
                if (!deckMoving) {
                    continue;
                }
                // The first resting tick starts a provisional lock. It is not placed until contact
                // has held for the confirm. A miss before that drops it.
                capture(level, dimension, ship, key, touch.supportId());
                break;
            }
        }
    }

    private record DeckBox(AbstractContraptionEntity entity, Aabb bounds) {
    }

    /**
     * @return false when the provisional lock should be dropped
     */
    private static boolean holdUntilConfirmed(
            ServerLevel level,
            DeckLock lock,
            ServerSubLevel ship,
            String shipId,
            List<SupportMotion> supports
    ) {
        if (disassembled(lock.contraption)) {
            SableSafeLanding.LOGGER.info("LIVE deck release ship {} reason=disassembled", shipId);
            return false;
        }
        if (piloting(level, ship)) {
            SableSafeLanding.LOGGER.info("LIVE deck release ship {} reason=pilot", shipId);
            return false;
        }
        // Count a climb before any placement. Thrust during the confirm must not be snapped back
        // on the tick the lock becomes a capture.
        noteTakeoff(level, lock, ship, supports);
        if (lock.takeoffTicks >= CarryGate.TAKEOFF_TICKS) {
            SableSafeLanding.LOGGER.info(
                    "LIVE deck release ship {} reason=takeoff vy={}",
                    shipId,
                    fmt(bodyUpSpeed(level, ship))
            );
            return false;
        }
        CarryGate.Contact contact = maintain(ship, supports);
        if (contact == CarryGate.Contact.NONE) {
            // One resting tick can be a graze. After a second, allow the same gap as a confirmed ride.
            if (lock.contactTicks < 2) {
                return false;
            }
            lock.missTicks++;
            if (lock.missTicks >= CarryGate.NO_CONTACT_RELEASE) {
                SableSafeLanding.LOGGER.info("LIVE deck release ship {} reason=nocontact", shipId);
                return false;
            }
        }
        if (contact == CarryGate.Contact.RESTING) {
            lock.missTicks = 0;
            lock.contactTicks++;
            if (lock.contactTicks >= CarryGate.CONTACT_HOLD) {
                lock.confirmed = true;
                lock.idleTicks = 0;
                lock.missTicks = 0;
                net.minecraft.world.phys.Vec3 anchor = lock.contraption.getAnchorVec();
                lock.lastAnchorX = anchor.x;
                lock.lastAnchorY = anchor.y;
                lock.lastAnchorZ = anchor.z;
                lock.lastAngle = Math.toRadians(deckAngle(lock.contraption));
                SableSafeLanding.LOGGER.info(
                        "LIVE deck capture ship {} contraption {}",
                        shipId,
                        lock.contraptionId
                );
            }
        }
        if (!lock.confirmed || lock.takeoffTicks > 0) {
            return true;
        }
        return place(level, lock, ship, shipId);
    }

    /**
     * Advance the takeoff streak from this tick's upward speed relative to the deck.
     * {@code lastAnchorY} moves with the deck so the next tick is one tick of motion, not the
     * whole ride since capture.
     */
    private static void noteTakeoff(ServerLevel level, DeckLock lock, ServerSubLevel ship, List<SupportMotion> supports) {
        if (lock.pendingVelocityLog) {
            lock.pendingVelocityLog = false;
            lock.velocityLogged = true;
            Vector3dc poseDelta = ship.latestLinearVelocity;
            SableSafeLanding.LOGGER.info(
                    "LIVE deck velocity ship {} bodyVy={} poseVy={}",
                    ship.getUniqueId(),
                    fmt(bodyUpSpeed(level, ship)),
                    fmt(poseDelta == null ? 0.0 : poseDelta.y())
            );
        }
        net.minecraft.world.phys.Vec3 anchor = lock.contraption.getAnchorVec();
        double deckUp = (anchor.y - lock.lastAnchorY) * 20.0;
        lock.lastAnchorY = anchor.y;
        double upward = bodyUpSpeed(level, ship);
        boolean clear = clearance(ship, supports) > CarryGate.TAKEOFF_CLEARANCE;
        lock.takeoffTicks = CarryGate.nextTakeoff(lock.takeoffTicks, upward, deckUp, clear);
    }

    /**
     * Rapier body velocity, in blocks per second. Sable's {@code latestLinearVelocity} is
     * {@code (logicalPose - lastPose) * 20} from {@code SubLevelPhysicsSystem.updatePose}, so a
     * teleport inflates it when {@code lastPose} is still the pre-teleport pose.
     * {@code PhysicsPipeline.getLinearVelocity} reads the rigid body. Sable 2.0.3
     * {@code RapierPhysicsPipeline.teleport} writes the position and orientation and does not
     * change that velocity. The takeoff streak uses the body value. {@code updateLastPose}
     * still runs after a teleport so the pose-delta path stays aligned for the next physics step.
     */
    private static double bodyUpSpeed(ServerLevel level, ServerSubLevel ship) {
        PhysicsPipeline pipeline = pipeline(level);
        if (pipeline != null) {
            try {
                Vector3d velocity = pipeline.getLinearVelocity(ship, new Vector3d());
                if (velocity != null && Double.isFinite(velocity.y)) {
                    return velocity.y;
                }
            } catch (RuntimeException ex) {
                logFailure(ex);
            }
        }
        Vector3dc fallback = ship.latestLinearVelocity;
        return fallback == null || !Double.isFinite(fallback.y()) ? 0.0 : fallback.y();
    }

    /** Positive when the hull is above the deck. No horizontal overlap counts as clear. */
    private static double clearance(ServerSubLevel ship, List<SupportMotion> supports) {
        if (supports.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        BoundingBox3dc box = ship.boundingBox();
        Aabb body = new Aabb(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
        double best = Double.POSITIVE_INFINITY;
        boolean overlapped = false;
        for (SupportMotion support : supports) {
            if (!body.overlapsHorizontally(support.volume())) {
                continue;
            }
            overlapped = true;
            best = Math.min(best, box.minY() - support.volume().maxY());
        }
        return overlapped ? best : Double.POSITIVE_INFINITY;
    }

    private static CarryGate.Reason releaseReason(
            ServerLevel level,
            DeckLock lock,
            ServerSubLevel ship,
            List<SupportMotion> supports
    ) {
        if (disassembled(lock.contraption)) {
            return CarryGate.Reason.DISASSEMBLED;
        }
        if (piloting(level, ship)) {
            return CarryGate.Reason.PILOT;
        }
        net.minecraft.world.phys.Vec3 anchor = lock.contraption.getAnchorVec();
        double angle = Math.toRadians(deckAngle(lock.contraption));
        double anchorDelta = distance(anchor.x, anchor.y, anchor.z, lock.lastAnchorX, lock.lastAnchorY, lock.lastAnchorZ);
        lock.idleTicks = CarryGate.nextIdle(lock.idleTicks, anchorDelta, Math.abs(angle - lock.lastAngle));
        noteTakeoff(level, lock, ship, supports);
        lock.lastAnchorX = anchor.x;
        lock.lastAnchorY = anchor.y;
        lock.lastAnchorZ = anchor.z;
        lock.lastAngle = angle;
        CarryGate.Contact contact = maintain(ship, supports);
        lock.missTicks = CarryGate.nextMisses(lock.missTicks, contact);
        return CarryGate.release(false, false, lock.takeoffTicks, lock.idleTicks, lock.missTicks);
    }

    /**
     * @return false when the correction is too far and the lock should be dropped
     */
    private static boolean place(ServerLevel level, DeckLock lock, ServerSubLevel ship, String shipId) {
        // The block entity has already stepped the anchor. The kinematic collider is still at
        // the pre-physics sample, and a teleport to the live anchor puts the car inside it.
        // The solver then pops the car up and that one tick looks like a takeoff.
        Sample sample = SAMPLES.get(level.dimension().location() + "\0" + lock.contraptionId);
        net.minecraft.world.phys.Vec3 live = lock.contraption.getAnchorVec();
        double anchorX = live.x;
        double anchorY = live.y;
        double anchorZ = live.z;
        double angle = Math.toRadians(deckAngle(lock.contraption));
        if (sample != null && sample.time == level.getGameTime()) {
            anchorX = sample.x;
            anchorY = sample.y;
            anchorZ = sample.z;
            angle = Math.toRadians(sample.angle);
        }
        Vec3 pivot = new Vec3(anchorX + 0.5, anchorY + 0.5, anchorZ + 0.5);
        Vec3 target = DeckFollow.worldTarget(lock.local, pivot, lock.axis, angle);
        Vector3dc current = ship.logicalPose().position();
        Vec3 from = new Vec3(current.x(), current.y(), current.z());
        if (!DeckFollow.acceptable(from, target)) {
            SableSafeLanding.LOGGER.info(
                    "LIVE deck release ship {} reason=separated d=({},{},{})",
                    shipId,
                    fmt(target.x() - from.x()),
                    fmt(target.y() - from.y()),
                    fmt(target.z() - from.z())
            );
            return false;
        }
        double dx = target.x() - from.x();
        double dy = target.y() - from.y();
        double dz = target.z() - from.z();
        if (!CarryGate.teleport(dx * dx + dy * dy + dz * dz, angle - lock.lastPlacedAngle)) {
            return true;
        }
        Rot boarded = new Rot(lock.orientationX, lock.orientationY, lock.orientationZ, lock.orientationW);
        Rot turned = DeckFollow.orientation(boarded, lock.axis, lock.capturedAngle, angle);
        Quaterniond orientation = new Quaterniond(turned.x(), turned.y(), turned.z(), turned.w());
        Vector3d position = new Vector3d(target.x(), target.y(), target.z());
        PhysicsPipeline pipeline = pipeline(level);
        if (pipeline != null) {
            // A sleeping body keeps its old pose, so the next read looks like a launch upward.
            // Wake only for a drift correction. A parked deck never reaches this call.
            pipeline.wakeUp(ship);
            pipeline.teleport(ship, position, orientation);
        } else {
            ship.logicalPose().position().set(position);
            ship.logicalPose().orientation().set(orientation);
        }
        ship.updateLastPose();
        lock.lastPlacedAngle = angle;
        if (!lock.velocityLogged) {
            lock.pendingVelocityLog = true;
        }
        ship.updateBoundingBox();
        if (PLACEMENT_LOGGED.add(shipId)) {
            SableSafeLanding.LOGGER.info(
                    "LIVE deck {} y {} -> {} dy={} dx={} dz={} anchorY={} removed={}",
                    shipId,
                    fmt(from.y()),
                    fmt(target.y()),
                    fmt(dy),
                    fmt(dx),
                    fmt(dz),
                    fmt(anchorY),
                    lock.contraption.isRemoved()
            );
        }
        return true;
    }

    private static boolean capture(ServerLevel level, String dimension, ServerSubLevel ship, String key, String contraptionId) {
        AbstractContraptionEntity contraption = findContraption(level, contraptionId);
        if (disassembled(contraption)) {
            return false;
        }
        // The support sample for this tick is the pose the ship is still standing on.
        // The live anchor has already moved, and baking that into the offset leaves a gap.
        Sample sample = SAMPLES.get(dimension + "\0" + contraptionId);
        net.minecraft.world.phys.Vec3 live = contraption.getAnchorVec();
        double anchorX = live.x;
        double anchorY = live.y;
        double anchorZ = live.z;
        double angle = Math.toRadians(deckAngle(contraption));
        if (sample != null && sample.time == level.getGameTime()) {
            anchorX = sample.x;
            anchorY = sample.y;
            anchorZ = sample.z;
            angle = Math.toRadians(sample.angle);
        }
        Vec3 pivot = new Vec3(anchorX + 0.5, anchorY + 0.5, anchorZ + 0.5);
        Vec3 axis = rotationAxis(contraption);
        Vector3dc position = ship.logicalPose().position();
        Vec3 local = DeckFollow.localOffset(new Vec3(position.x(), position.y(), position.z()), pivot, axis, angle);
        if (!local.finite()) {
            return false;
        }
        Quaterniondc boarded = ship.logicalPose().orientation();
        LOCKS.put(key, new DeckLock(
                contraption,
                contraptionId,
                ship,
                local,
                axis,
                boarded.x(),
                boarded.y(),
                boarded.z(),
                boarded.w(),
                angle,
                anchorX,
                anchorY,
                anchorZ
        ));
        return true;
    }

    /**
     * Block bottoms decide a new contact. Once the hull is already on the deck, its box keeps
     * the lock through a yaw: the bottoms move around the pose while the box still sits on the planks.
     */
    private static CarryGate.Contact maintain(ServerSubLevel ship, List<SupportMotion> supports) {
        if (supports.isEmpty()) {
            return CarryGate.Contact.NONE;
        }
        BlockContact.Touch touch = BlockContact.touch(ship, supports, CarryGate.MAINTAIN_GAP);
        if (touch.contact() != CarryGate.Contact.NONE) {
            return touch.contact();
        }
        BoundingBox3dc box = ship.boundingBox();
        Aabb body = new Aabb(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
        for (SupportMotion support : supports) {
            if (DeckContact.restingBox(body, support.volume(), CarryGate.MAINTAIN_GAP)) {
                return CarryGate.Contact.RESTING;
            }
        }
        return CarryGate.Contact.NONE;
    }

    private static boolean disassembled(AbstractContraptionEntity entity) {
        if (entity == null || entity.isRemoved() || !entity.isAlive()) {
            return true;
        }
        Contraption contraption = entity.getContraption();
        return contraption == null || contraption.getBlocks().isEmpty();
    }

    private static boolean piloting(ServerLevel level, ServerSubLevel ship) {
        BoundingBox3dc box = ship.boundingBox();
        AABB aabb = new AABB(
                box.minX() - 1.0, box.minY() - 1.0, box.minZ() - 1.0,
                box.maxX() + 1.0, box.maxY() + 1.0, box.maxZ() + 1.0
        );
        if (!level.getEntitiesOfClass(Player.class, aabb, CarryRuntime::steering).isEmpty()) {
            return true;
        }
        for (Player player : level.players()) {
            if (aabb.contains(player.getX(), player.getY(), player.getZ()) && steering(player)) {
                return true;
            }
        }
        return false;
    }

    private static boolean steering(Player player) {
        return player.xxa != 0.0f || player.zza != 0.0f || player.yya != 0.0f || jumping(player);
    }

    private static boolean jumping(Player player) {
        if (JUMPING == null) {
            return false;
        }
        try {
            return JUMPING.getBoolean(player);
        } catch (IllegalAccessException ex) {
            return false;
        }
    }

    private static Field jumpingField() {
        try {
            Field field = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("jumping");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException ex) {
            return null;
        }
    }

    private static AbstractContraptionEntity findContraption(ServerLevel level, String id) {
        for (AbstractContraptionEntity entity : contraptions(level)) {
            if (entity.getUUID().toString().equals(id)) {
                return entity;
            }
        }
        for (DeckLock lock : LOCKS.values()) {
            if (id.equals(lock.contraptionId) && lock.contraption != null) {
                return lock.contraption;
            }
        }
        try {
            Entity direct = level.getEntity(UUID.fromString(id));
            if (direct instanceof AbstractContraptionEntity contraption) {
                return contraption;
            }
        } catch (IllegalArgumentException ex) {
            return null;
        }
        return null;
    }

    /**
     * Contraption bounds only. Block volumes are built later, and only for a box the ship meets.
     */
    private static List<DeckBox> deckBoxes(ServerLevel level, String dimension) {
        List<DeckBox> boxes = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int count = 0;
        for (AbstractContraptionEntity entity : contraptions(level)) {
            if (!ColliderFeedRuntime.isWorldSupport(entity)) {
                continue;
            }
            if (count++ >= ColliderFeedRuntime.MAX_CONTRAPTIONS) {
                break;
            }
            addDeck(boxes, seen, entity);
        }
        String prefix = dimension + "\0";
        for (Map.Entry<String, DeckLock> entry : LOCKS.entrySet()) {
            if (!entry.getKey().startsWith(prefix) || disassembled(entry.getValue().contraption)) {
                continue;
            }
            addDeck(boxes, seen, entry.getValue().contraption);
        }
        return boxes;
    }

    private static void addDeck(List<DeckBox> boxes, java.util.Set<String> seen, AbstractContraptionEntity entity) {
        if (entity == null) {
            return;
        }
        String id = entity.getUUID().toString();
        if (!seen.add(id)) {
            return;
        }
        Aabb bounds = broadphaseBounds(entity);
        if (bounds != null) {
            boxes.add(new DeckBox(entity, bounds));
        }
    }

    private static Aabb broadphaseBounds(AbstractContraptionEntity entity) {
        net.minecraft.world.phys.AABB box = entity.getBoundingBox();
        if (box == null || box.minX > box.maxX || box.minY > box.maxY || box.minZ > box.maxZ) {
            return null;
        }
        if (!Double.isFinite(box.minX) || !Double.isFinite(box.maxX)) {
            return null;
        }
        Aabb assembled = new Aabb(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        if (entity instanceof ControlledContraptionEntity controlled && controlled.getRotationAxis() != null) {
            return CarryBroadphase.sweepRotation(assembled, axis(controlled.getRotationAxis()));
        }
        return assembled;
    }

    private static Aabb shipBox(ServerSubLevel ship) {
        BoundingBox3dc box = ship.boundingBox();
        if (box == null) {
            return null;
        }
        double minX = box.minX();
        double minY = box.minY();
        double minZ = box.minZ();
        double maxX = box.maxX();
        double maxY = box.maxY();
        double maxZ = box.maxZ();
        if (!Double.isFinite(minX) || !Double.isFinite(maxX) || minX > maxX || minY > maxY || minZ > maxZ) {
            return null;
        }
        if (maxX - minX < 1.0e-4 && maxY - minY < 1.0e-4 && maxZ - minZ < 1.0e-4) {
            Vector3dc position = ship.logicalPose().position();
            return new Aabb(position.x() - 1.0, position.y() - 1.0, position.z() - 1.0,
                    position.x() + 1.0, position.y() + 1.0, position.z() + 1.0);
        }
        return new Aabb(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** This contraption's merged block volumes, built at most once per game tick. */
    private static List<SupportMotion> blocksCached(ServerLevel level, String dimension, AbstractContraptionEntity entity) {
        if (disassembled(entity)) {
            return List.of();
        }
        long time = level.getGameTime();
        String key = dimension + "\0" + entity.getUUID();
        CachedSupports cached = BLOCK_CACHE.get(key);
        if (cached != null && cached.time == time) {
            return cached.supports;
        }
        List<SupportMotion> built = blocks(dimension, entity, time);
        BLOCK_CACHE.put(key, new CachedSupports(time, built));
        return built;
    }

    private static List<SupportMotion> supportsFor(ServerLevel level) {
        String dimension = level.dimension().location().toString();
        long time = level.getGameTime();
        CachedSupports cached = SUPPORT_CACHE.get(dimension);
        if (cached != null && cached.time == time) {
            return cached.supports;
        }
        List<SupportMotion> supports = new ArrayList<>();
        int seen = 0;
        for (AbstractContraptionEntity entity : contraptions(level)) {
            if (!ColliderFeedRuntime.isWorldSupport(entity)) {
                continue;
            }
            if (seen++ >= ColliderFeedRuntime.MAX_CONTRAPTIONS) {
                break;
            }
            supports.addAll(blocks(dimension, entity, time));
        }
        SUPPORT_CACHE.put(dimension, new CachedSupports(time, supports));
        return supports;
    }

    private static List<AbstractContraptionEntity> contraptions(ServerLevel level) {
        List<AbstractContraptionEntity> tracked = ColliderFeedRuntime.tracked(level);
        if (!tracked.isEmpty()) {
            return tracked;
        }
        List<AbstractContraptionEntity> scanned = new ArrayList<>();
        int seen = 0;
        for (Entity candidate : level.getAllEntities()) {
            if (!(candidate instanceof AbstractContraptionEntity entity)) {
                continue;
            }
            if (seen++ >= ColliderFeedRuntime.MAX_CONTRAPTIONS) {
                break;
            }
            scanned.add(entity);
        }
        return scanned;
    }

    private static List<SupportMotion> forId(List<SupportMotion> supports, String id) {
        List<SupportMotion> mine = new ArrayList<>();
        for (SupportMotion support : supports) {
            if (support.id().equals(id)) {
                mine.add(support);
            }
        }
        return mine;
    }

    private static float deckAngle(AbstractContraptionEntity entity) {
        if (entity instanceof ControlledContraptionEntity controlled && controlled.getRotationAxis() != null) {
            return controlled.getAngle(1.0f);
        }
        return 0.0f;
    }

    private static Vec3 rotationAxis(AbstractContraptionEntity entity) {
        if (entity instanceof ControlledContraptionEntity controlled && controlled.getRotationAxis() != null) {
            return axis(controlled.getRotationAxis());
        }
        return new Vec3(0.0, 1.0, 0.0);
    }

    private static PhysicsPipeline pipeline(ServerLevel level) {
        SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
        return system == null ? null : system.getPipeline();
    }

    private static double distance(double x, double y, double z, double ox, double oy, double oz) {
        double dx = x - ox;
        double dy = y - oy;
        double dz = z - oz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /**
     * Block-entity ticks move the contraption and then the entity copies that pose into its
     * previous position before the next pre-physics event. The delta between two pre-physics
     * samples is that motion. The first sample of a game tick owns the anchor used for the
     * volumes, so later substeps and the post tick test contact against the pose the ship is
     * still standing on. Placement uses that same sample, which is where the kinematic collider is.
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
                    PlatformTrace.note(id, String.format(Locale.ROOT,
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
        List<Aabb> boxes = new ArrayList<>();
        for (BlockPos pos : contraption.getBlocks().keySet()) {
            Aabb local = new Aabb(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
            boxes.add(Aabb.enclosing(corners(entity, local, anchorNow, 1.0f)));
        }
        List<SupportMotion> supports = new ArrayList<>();
        for (Aabb world : AabbMerger.merge(boxes)) {
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
