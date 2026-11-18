package com.vucelich.sablesafelanding;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.vucelich.sablesafelanding.sim.Aabb;
import com.vucelich.sablesafelanding.sim.AntiClipConfig;
import com.vucelich.sablesafelanding.sim.AntiClipStep;
import com.vucelich.sablesafelanding.sim.CarryBroadphase;
import com.vucelich.sablesafelanding.sim.CarryBudget;
import com.vucelich.sablesafelanding.sim.CarryGate;
import com.vucelich.sablesafelanding.sim.DeckIndex;
import com.vucelich.sablesafelanding.sim.GridMerger;
import com.vucelich.sablesafelanding.sim.TickBudget;
import com.vucelich.sablesafelanding.sim.PassengerBook;
import com.vucelich.sablesafelanding.sim.VelocityClamp;
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
import net.minecraft.world.level.ChunkPos;
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
    /** Deck-frame offsets for cars sitting on a carried ship. */
    private static final PassengerBook SEATS = new PassengerBook();
    private static final Map<String, CachedSupports> SUPPORT_CACHE = new HashMap<>();
    private static final Map<String, CachedSupports> BLOCK_CACHE = new HashMap<>();
    /** Game tick a deck last moved, per dimension and contraption. */
    private static final Map<String, Long> MOVED_AT = new HashMap<>();
    /** The last deck shape built for a contraption, reused when the tick budget runs out. */
    private static final Map<String, List<SupportMotion>> LAST_SHAPES = new HashMap<>();
    private static final Map<String, TickBudget> BUDGETS = new HashMap<>();
    private static final Map<String, CachedReaches> REACHES = new HashMap<>();
    private static final Set<String> TRUNCATION_LOGGED = new HashSet<>();
    /** A deck that moved within this many ticks still counts as moving for the deck shapes. */
    private static final long ACTIVE_TICKS = 40;
    /** Most deck blocks turned into shapes for one contraption in one tick. */
    private static final int MAX_REGION_BLOCKS = 16_384;
    /** Most ships looked at for the deck reach test in one tick. */
    private static final int MAX_REACH_SHIPS = 256;
    private static final Set<String> PLACEMENT_LOGGED = new HashSet<>();
    private static final RateLimiter LAG_LOGS = new RateLimiter(5_000_000_000L);
    private static final Field JUMPING = jumpingField();
    /** Rotates which unlocked ships are examined so a long list is not stuck on the first 32. */
    private static int captureCursor;

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
        private Vec3 local;
        private final Vec3 axis;
        private double orientationX;
        private double orientationY;
        private double orientationZ;
        private double orientationW;
        private double capturedAngle;
        private double lastAnchorX;
        private double lastAnchorY;
        private double lastAnchorZ;
        private double lastAngle;
        private double lastPlacedAngle;
        private int idleTicks;
        private int missTicks;
        private int contactTicks;
        /** The last placement gave the ship a ride velocity, so a stop has to clear it once. */
        private boolean riding;
        /** The skid line is logged once per lock. */
        private boolean skidLogged;
        private int takeoffTicks;
        private boolean confirmed;
        /** Log body velocity against the pose delta once, on the tick after the first teleport. */
        private boolean pendingVelocityLog;
        private boolean velocityLogged;
        private double placedPivotX;
        private double placedPivotY;
        private double placedPivotZ;

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
            this.placedPivotX = anchorX + 0.5;
            this.placedPivotY = anchorY + 0.5;
            this.placedPivotZ = anchorZ + 0.5;
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

    private record CachedReaches(long time, List<Aabb> boxes) {
    }

    private CarryRuntime() {
    }

    public static void onPrePhysics(ForgeSablePrePhysicsTickEvent event) {
        long start = System.nanoTime();
        try {
            prePhysics(event);
        } finally {
            PerfMeter.add(PerfMeter.Part.CARRY_PHYSICS, System.nanoTime() - start);
        }
    }

    private static void prePhysics(ForgeSablePrePhysicsTickEvent event) {
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
            if (!config.enabled()) {
                LOCKS.clear();
                SEATS.clear();
                return;
            }
            if (!config.carry() && !config.sweep() && !config.feedColliders()) {
                return;
            }
            boolean lag = CarryGate.lagSpike(event.getTimeStep());
            List<SupportMotion> supports = lag ? List.of() : supportsFor(level);
            if (lag) {
                logLag(event.getTimeStep());
            }
            if (supports.isEmpty() && (!lag || (!config.voidRescue() && !config.bedrockRescue()))) {
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
        long start = System.nanoTime();
        try {
            serverTick(event);
        } finally {
            PerfMeter.add(PerfMeter.Part.CARRY_TICK, System.nanoTime() - start);
        }
    }

    private static void serverTick(ServerTickEvent.Post event) {
        if (!event.getServer().isSameThread()) {
            return;
        }
        try {
            AntiClipConfig config = SafeLandingConfig.snapshot();
            if (!config.enabled() || !config.carry()) {
                LOCKS.clear();
                SEATS.clear();
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
        SEATS.clear();
        SUPPORT_CACHE.clear();
        BLOCK_CACHE.clear();
        MOVED_AT.clear();
        LAST_SHAPES.clear();
        BUDGETS.clear();
        REACHES.clear();
        TRUNCATION_LOGGED.clear();
        DeckShapes.clear();
        PLACEMENT_LOGGED.clear();
        WheelContactRuntime.clear();
        captureCursor = 0;
    }

    public static void forgetDimension(String dimension) {
        String prefix = dimension + "\0";
        SAMPLES.keySet().removeIf(key -> key.startsWith(prefix));
        LOCKS.keySet().removeIf(key -> key.startsWith(prefix));
        BLOCK_CACHE.keySet().removeIf(key -> key.startsWith(prefix));
        MOVED_AT.keySet().removeIf(key -> key.startsWith(prefix));
        LAST_SHAPES.keySet().removeIf(key -> key.startsWith(prefix));
        SUPPORT_CACHE.remove(dimension);
        BUDGETS.remove(dimension);
        REACHES.remove(dimension);
        SEATS.forgetDimension(dimension);
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
        if (!SEATS.isEmpty()) {
            prunePassengers(dimension, container);
        }
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
            if (ship == null || ship.isRemoved() || ship.getLevel() != level || !chunkLoaded(level, ship)) {
                SableSafeLanding.LOGGER.info("LIVE deck release ship {} reason=unloaded", shipId);
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
        if (decks.isEmpty()) {
            // No contraption deck in this level: nothing can be captured.
            return;
        }
        List<ServerSubLevel> unlocked = new ArrayList<>();
        for (ServerSubLevel ship : container.getAllSubLevels()) {
            if (ship.isRemoved() || ship.getLevel() != level) {
                continue;
            }
            // Only a ship on or next to a deck can be captured. The bounds test is cheap; the
            // pilot check and everything after it run only for those ships.
            if (!nearAnyDeck(level, ship, decks)) {
                continue;
            }
            if (piloting(level, ship)) {
                continue;
            }
            String key = prefix + ship.getUniqueId();
            if (LOCKS.containsKey(key)) {
                continue;
            }
            unlocked.add(ship);
        }
        int held = 0;
        for (String key : LOCKS.keySet()) {
            if (key.startsWith(prefix)) {
                held++;
            }
        }
        int start = CarryBudget.windowStart(unlocked.size(), captureCursor, CarryBudget.MAX_SHIPS);
        int examined = 0;
        for (int i = 0; unlocked.size() > 0 && examined < CarryBudget.MAX_SHIPS; i++) {
            if (!CarryBudget.admit(held, examined)) {
                break;
            }
            if (i >= unlocked.size()) {
                break;
            }
            ServerSubLevel ship = unlocked.get((start + i) % unlocked.size());
            examined++;
            Aabb hull = shipBox(ship);
            if (hull == null) {
                continue;
            }
            Aabb query = hull.expand(CarryBroadphase.INFLATE);
            // Wheels on a deck count as resting contact. The hull rides above it on the suspension,
            // so it can be farther from the deck than the broadphase and the block test allow.
            String wheelDeck = WheelContactRuntime.wheelSupport(ship.getUniqueId(), level.getGameTime());
            for (DeckBox deck : decks) {
                boolean onWheels = wheelDeck != null && wheelDeck.equals(deck.entity.getUUID().toString());
                if (!onWheels && !query.intersects(deck.bounds)) {
                    continue;
                }
                // A stopped deck must not grab the car again. Idle release would otherwise be undone
                // on the next tick, and a drive off the elevator would be pulled back. Checked before
                // the block test so a ship parked on a stopped deck costs no block work.
                Sample deckSample = sampleOf(dimension, deck.entity, level.getGameTime());
                boolean deckMoving = deckSample != null
                        && (deckSample.translation.length() >= CarryGate.IDLE_ANCHOR
                        || Math.abs(deckSample.angleRadians) >= CarryGate.IDLE_ANGLE);
                if (!deckMoving) {
                    continue;
                }
                // Block test is only this contraption. A ship that missed every box never gets here.
                BlockContact.Touch touch = onWheels
                        ? new BlockContact.Touch(CarryGate.Contact.RESTING, wheelDeck)
                        : BlockContact.touch(
                                ship,
                                blocksCached(level, dimension, deck.entity),
                                CarryGate.CAPTURE_GAP
                        );
                if (touch.contact() != CarryGate.Contact.RESTING || touch.supportId() == null) {
                    continue;
                }
                // The first resting tick starts a provisional lock. It is not placed until contact
                // has held for the confirm. A miss before that drops it.
                String key = prefix + ship.getUniqueId();
                if (capture(level, dimension, ship, key, touch.supportId())) {
                    held++;
                }
                break;
            }
        }
        if (unlocked.size() > CarryBudget.MAX_SHIPS) {
            captureCursor = start + examined;
        }
    }

    private record DeckBox(AbstractContraptionEntity entity, Aabb bounds) {
    }

    /** The capture's own broadphase, or wheels standing on one of these decks. */
    private static boolean nearAnyDeck(ServerLevel level, ServerSubLevel ship, List<DeckBox> decks) {
        String wheelDeck = WheelContactRuntime.wheelSupport(ship.getUniqueId(), level.getGameTime());
        Aabb hull = shipBox(ship);
        Aabb query = hull == null ? null : hull.expand(CarryBroadphase.INFLATE);
        for (DeckBox deck : decks) {
            if (query != null && query.intersects(deck.bounds)) {
                return true;
            }
            if (wheelDeck != null && wheelDeck.equals(deck.entity.getUUID().toString())) {
                return true;
            }
        }
        return false;
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
        if (ship.getLevel() != level || !chunkLoaded(level, ship) || contraptionUnloaded(level, lock.contraption)) {
            SableSafeLanding.LOGGER.info("LIVE deck release ship {} reason=unloaded", shipId);
            return false;
        }
        if (disassembled(lock.contraption)) {
            calmIfAbsurd(level, ship);
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
        CarryGate.Contact contact = maintain(level, ship, supports, lock.contraptionId);
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
            // Only on a sliding deck: the confirm has already given the ship the deck's speed, so
            // anything faster across it is a push. A turntable is not checked, because a ship parked
            // on it is still while the planks under it move.
            Vec3 body = bodyVelocity(level, ship);
            Vec3 ride = slidingVelocity(level, lock);
            if (body != null && ride != null && !CarryGate.settled(body, ride)) {
                // Crossing or skidding over the deck, not riding it. Start the confirm again.
                if (!lock.skidLogged) {
                    lock.skidLogged = true;
                    SableSafeLanding.LOGGER.info(
                            "LIVE deck skid ship {} speed={}",
                            shipId,
                            fmt(Math.sqrt(body.x() * body.x() + body.z() * body.z()))
                    );
                }
                lock.contactTicks = 1;
            }
            if (lock.contactTicks >= CarryGate.CONTACT_HOLD && rebase(level, lock, ship)) {
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
            // While the contact is being confirmed, already move with a sliding deck so a heavy
            // ship does not sink onto it. Nothing is teleported until the confirm.
            if (contact == CarryGate.Contact.RESTING && lock.takeoffTicks == 0) {
                matchDeckSpeed(level, lock, ship);
            }
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
        boolean clear = !onWheels(level, ship, lock.contraptionId)
                && clearance(ship, supports) > CarryGate.TAKEOFF_CLEARANCE;
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
        if (ship.getLevel() != level || !chunkLoaded(level, ship) || contraptionUnloaded(level, lock.contraption)) {
            return CarryGate.Reason.UNLOADED;
        }
        if (disassembled(lock.contraption)) {
            calmIfAbsurd(level, ship);
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
        CarryGate.Contact contact = maintain(level, ship, supports, lock.contraptionId);
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
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // Same wrap as the ride sample and FedContraptionCollider. A 360-degree bearing wrap is one step.
        double angleStep = Math.toRadians(Mth.wrapDegrees(Math.toDegrees(angle - lock.lastPlacedAngle)));
        // An elevator, piston, or gantry this tick: place every tick and match the deck's speed.
        Vec3 ride = sample != null && sample.time == level.getGameTime()
                ? CarryGate.rideVelocity(sample.translation, sample.angleRadians)
                : null;
        // The deck just stopped: place once more and clear the ride velocity so the ship does not coast past.
        boolean stopped = ride == null && lock.riding;
        if (ride == null && !stopped && !CarryGate.teleport(dx * dx + dy * dy + dz * dz, angleStep)) {
            return true;
        }
        if (!CarryGate.safeStep(distance, angleStep)) {
            calmIfAbsurd(level, ship);
            SableSafeLanding.LOGGER.info(
                    "LIVE deck release ship {} reason=spike d={} angle={}",
                    shipId,
                    fmt(distance),
                    fmt(Math.toDegrees(angleStep))
            );
            return false;
        }
        Vec3 oldPivot = new Vec3(lock.placedPivotX, lock.placedPivotY, lock.placedPivotZ);
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
            if (ride != null) {
                setRideVelocity(pipeline, ship, ride);
            } else if (stopped) {
                setRideVelocity(pipeline, ship, com.vucelich.sablesafelanding.sim.Vec3.ZERO);
            }
            lock.riding = ride != null;
        } else {
            ship.logicalPose().position().set(position);
            ship.logicalPose().orientation().set(orientation);
        }
        ship.updateLastPose();
        shiftPassengers(level, ship, lock, oldPivot, pivot, angle, angleStep);
        lock.lastPlacedAngle = angle;
        lock.placedPivotX = pivot.x();
        lock.placedPivotY = pivot.y();
        lock.placedPivotZ = pivot.z();
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

    /** Gives a ship the deck's velocity for the coming physics step, when the deck is sliding. */
    private static void matchDeckSpeed(ServerLevel level, DeckLock lock, ServerSubLevel ship) {
        Sample sample = SAMPLES.get(level.dimension().location() + "\0" + lock.contraptionId);
        if (sample == null || sample.time != level.getGameTime()) {
            return;
        }
        Vec3 ride = CarryGate.rideVelocity(sample.translation, sample.angleRadians);
        PhysicsPipeline pipeline = pipeline(level);
        if (ride == null || pipeline == null) {
            return;
        }
        // The whole velocity, sideways too. A ship that touches a moving deck with nobody steering
        // stops on it instead of rolling off; a steering player drops the lock before this runs.
        pipeline.wakeUp(ship);
        setRideVelocity(pipeline, ship, ride);
        lock.riding = true;
    }

    /** This tick's deck velocity in blocks per second, or null when the deck is not sliding. */
    private static Vec3 slidingVelocity(ServerLevel level, DeckLock lock) {
        Sample sample = SAMPLES.get(level.dimension().location() + "\0" + lock.contraptionId);
        if (sample == null || sample.time != level.getGameTime()) {
            return null;
        }
        return CarryGate.rideVelocity(sample.translation, sample.angleRadians);
    }

    /** Rapier body velocity, or null when it cannot be read. */
    private static Vec3 bodyVelocity(ServerLevel level, ServerSubLevel ship) {
        PhysicsPipeline pipeline = pipeline(level);
        if (pipeline == null) {
            return null;
        }
        try {
            Vector3d read = pipeline.getLinearVelocity(ship, new Vector3d());
            if (read == null || !Double.isFinite(read.x) || !Double.isFinite(read.y) || !Double.isFinite(read.z)) {
                return null;
            }
            return new Vec3(read.x, read.y, read.z);
        } catch (RuntimeException ex) {
            logFailure(ex);
            return null;
        }
    }

    /**
     * Store the deck-frame offset again on the tick the contact is confirmed. The provisional
     * offset is from the first touch, and a ship that moved over the deck during the confirm
     * was pulled back to that spot, or released as separated when it was more than 8 blocks away.
     * Only the horizontal part is refreshed. The height stays the first touch's resting height,
     * because a ship on a falling deck trails it during the confirm and that gap is not a seat.
     * A bearing keeps the first-touch offset: the planks turned under a still ship during the
     * confirm, and the first touch is the seat it is carried from.
     */
    private static boolean rebase(ServerLevel level, DeckLock lock, ServerSubLevel ship) {
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
        Vector3dc position = ship.logicalPose().position();
        if (lock.contraption instanceof ControlledContraptionEntity controlled && controlled.getRotationAxis() != null) {
            return true;
        }
        Vec3 fresh = DeckFollow.localOffset(new Vec3(position.x(), position.y(), position.z()), pivot, lock.axis, angle);
        if (!fresh.finite()) {
            return false;
        }
        Vec3 local = new Vec3(fresh.x(), lock.local.y(), fresh.z());
        Quaterniondc boarded = ship.logicalPose().orientation();
        lock.local = local;
        lock.orientationX = boarded.x();
        lock.orientationY = boarded.y();
        lock.orientationZ = boarded.z();
        lock.orientationW = boarded.w();
        lock.capturedAngle = angle;
        lock.lastPlacedAngle = angle;
        lock.placedPivotX = pivot.x();
        lock.placedPivotY = pivot.y();
        lock.placedPivotZ = pivot.z();
        return true;
    }

    /**
     * Rapier body velocity, not only Sable's pose-delta field. Without it the body keeps its own
     * speed between placements, the suspension and gravity slow a heavy ship, and it trails the
     * deck. Angular velocity is cleared too; the orientation is the placed one.
     */
    private static void setRideVelocity(PhysicsPipeline pipeline, ServerSubLevel ship, Vec3 ride) {
        try {
            pipeline.resetVelocity(ship);
            pipeline.addLinearAndAngularVelocity(ship, new Vector3d(ride.x(), ride.y(), ride.z()), new Vector3d());
            ship.latestLinearVelocity.set(ride.x(), ride.y(), ride.z());
        } catch (RuntimeException ex) {
            logFailure(ex);
        }
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

    /** True when this ship's wheel rays landed on this contraption in the last couple of ticks. */
    private static boolean onWheels(ServerLevel level, ServerSubLevel ship, String contraptionId) {
        return contraptionId != null
                && contraptionId.equals(WheelContactRuntime.wheelSupport(ship.getUniqueId(), level.getGameTime()));
    }

    /**
     * Block bottoms decide a new contact. Once the hull is already on the deck, its box keeps
     * the lock through a yaw: the bottoms move around the pose while the box still sits on the planks.
     */
    private static CarryGate.Contact maintain(
            ServerLevel level,
            ServerSubLevel ship,
            List<SupportMotion> supports,
            String contraptionId
    ) {
        // A car standing on its wheels: the wheel rays landed on this deck, so the car is on it.
        if (onWheels(level, ship, contraptionId)) {
            return CarryGate.Contact.RESTING;
        }
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
        List<SupportMotion> built = blocks(level, dimension, entity, time);
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
            // The motion sample is a few reads and is kept for every deck, so a deck that a ship
            // reaches later already knows how fast it is moving.
            sampleOf(dimension, entity, time);
            if (!active(level, dimension, entity, time)) {
                // A ship is resting on a stopped deck: build the deck index now, inside the budget,
                // so the carry is ready the tick the deck starts to move. Built once per shape.
                if (!DeckShapes.ready(entity) && !nearReaches(level, entity).isEmpty()) {
                    DeckShapes.index(entity, budget(level));
                }
                continue;
            }
            supports.addAll(blocksCached(level, dimension, entity));
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
        for (AbstractContraptionEntity entity : LiveContraptions.in(level)) {
            if (entity == null || entity.isRemoved() || entity.level() != level) {
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
    private static List<SupportMotion> blocks(ServerLevel level, String dimension, AbstractContraptionEntity entity, long gameTime) {
        Contraption contraption = entity.getContraption();
        if (contraption == null || contraption.getBlocks().isEmpty()) {
            return List.of();
        }
        String id = entity.getUUID().toString();
        String key = dimension + "\0" + id;
        Sample sample = sampleOf(dimension, entity, gameTime);
        if (sample == null) {
            return List.of();
        }
        // Only the part of the deck a ship can reach this tick is turned into shapes. A deck with
        // no ship on or next to it has none and costs nothing more.
        List<Aabb> reaches = nearReaches(level, entity);
        if (reaches.isEmpty()) {
            LAST_SHAPES.remove(key);
            return List.of();
        }
        TickBudget budget = budget(level);
        List<SupportMotion> last = LAST_SHAPES.getOrDefault(key, List.of());
        if (!budget.allows()) {
            return last;
        }
        DeckIndex index = DeckShapes.index(entity, budget);
        if (index == null || !budget.allows()) {
            return last;
        }
        long start = System.nanoTime();
        Vec3 pivot = new Vec3(sample.x + 0.5, sample.y + 0.5, sample.z + 0.5);
        net.minecraft.world.phys.Vec3 anchorNow = new net.minecraft.world.phys.Vec3(sample.x, sample.y, sample.z);
        long[] cells = new long[256];
        int count = 0;
        boolean truncated = false;
        Set<Long> seen = reaches.size() > 1 ? new HashSet<>() : null;
        for (Aabb reach : reaches) {
            int[] region = localRegion(entity, reach, anchorNow);
            DeckIndex.Region found = index.query(region[0], region[1], region[2], region[3], region[4], region[5],
                    MAX_REGION_BLOCKS - count);
            truncated |= found.truncated();
            for (int i = 0; i < found.count(); i++) {
                long cell = found.cells()[i];
                if (seen != null && !seen.add(cell)) {
                    continue;
                }
                if (count == cells.length) {
                    cells = java.util.Arrays.copyOf(cells, cells.length * 2);
                }
                cells[count++] = cell;
            }
            if (count >= MAX_REGION_BLOCKS) {
                truncated = true;
                break;
            }
        }
        if (truncated && TRUNCATION_LOGGED.add(key)) {
            SableSafeLanding.LOGGER.info("LIVE deck shape {} capped at {} blocks near ships", id, MAX_REGION_BLOCKS);
        }
        // A deck turned off a quarter turn only joins blocks along its rotation axis, so each
        // rotated box encloses the same space the per-block boxes did.
        Direction.Axis locked = turnedAxis(entity);
        List<Aabb> local = locked == null
                ? GridMerger.merge(cells, count)
                : GridMerger.merge(cells, count, locked == Direction.Axis.X, locked == Direction.Axis.Y, locked == Direction.Axis.Z);
        List<SupportMotion> supports = new ArrayList<>(local.size());
        for (Aabb box : local) {
            Aabb world = Aabb.enclosing(corners(entity, box, anchorNow, 1.0f));
            supports.add(new SupportMotion(id, world, sample.translation, pivot, sample.axis, sample.angleRadians));
        }
        budget.charge(System.nanoTime() - start);
        LAST_SHAPES.put(key, supports);
        return supports;
    }

    /**
     * The deck's motion since the previous game tick, sampled once per tick. A few field reads,
     * no block work.
     */
    private static Sample sampleOf(String dimension, AbstractContraptionEntity entity, long gameTime) {
        if (entity == null) {
            return null;
        }
        net.minecraft.world.phys.Vec3 anchor = entity.getAnchorVec();
        float angleNow = entity instanceof ControlledContraptionEntity controlled ? controlled.getAngle(1.0f) : 0.0f;
        String id = entity.getUUID().toString();
        String key = dimension + "\0" + id;
        Sample previous = SAMPLES.get(key);
        if (previous != null && previous.time == gameTime) {
            return previous;
        }
        Vec3 translation = Vec3.ZERO;
        double radians = 0.0;
        Vec3 axis = new Vec3(0.0, 1.0, 0.0);
        if (previous != null && previous.time < gameTime) {
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
        if (translation.length() >= CarryGate.IDLE_ANCHOR || Math.abs(radians) >= CarryGate.IDLE_ANGLE) {
            MOVED_AT.put(key, gameTime);
        }
        Sample sample = new Sample(gameTime, anchor.x, anchor.y, anchor.z, angleNow, translation, radians, axis);
        SAMPLES.put(key, sample);
        return sample;
    }

    /**
     * True only while a ship is on or next to this deck and the deck has moved within
     * {@link #ACTIVE_TICKS} or holds a ship. A bounds test, never a block scan.
     */
    private static boolean active(ServerLevel level, String dimension, AbstractContraptionEntity entity, long time) {
        if (nearReaches(level, entity).isEmpty()) {
            return false;
        }
        String id = entity.getUUID().toString();
        Long moved = MOVED_AT.get(dimension + "\0" + id);
        if (moved != null && time - moved <= ACTIVE_TICKS) {
            return true;
        }
        String prefix = dimension + "\0";
        for (Map.Entry<String, DeckLock> entry : LOCKS.entrySet()) {
            if (entry.getKey().startsWith(prefix) && id.equals(entry.getValue().contraptionId)) {
                return true;
            }
        }
        return false;
    }

    /** Reaches of ships whose box touches this deck's bounds. */
    private static List<Aabb> nearReaches(ServerLevel level, AbstractContraptionEntity entity) {
        Aabb bounds = broadphaseBounds(entity);
        if (bounds == null) {
            return List.of();
        }
        List<Aabb> near = null;
        for (Aabb reach : shipReaches(level)) {
            if (reach.intersects(bounds)) {
                if (near == null) {
                    near = new ArrayList<>(2);
                }
                near.add(reach);
            }
        }
        return near == null ? List.of() : near;
    }

    /**
     * Every ship's box grown by the broadphase margin, three blocks for wheels and suspension,
     * and up to two ticks of its own speed. Built once per tick from Sable's bounds.
     */
    private static List<Aabb> shipReaches(ServerLevel level) {
        String dimension = level.dimension().location().toString();
        long time = level.getGameTime();
        CachedReaches cached = REACHES.get(dimension);
        if (cached != null && cached.time == time) {
            return cached.boxes;
        }
        List<Aabb> boxes = new ArrayList<>();
        if (SubLevelContainer.getContainer(level) instanceof ServerSubLevelContainer container) {
            for (ServerSubLevel ship : container.getAllSubLevels()) {
                if (boxes.size() >= MAX_REACH_SHIPS) {
                    break;
                }
                if (ship.isRemoved() || ship.getLevel() != level) {
                    continue;
                }
                Aabb hull = shipBox(ship);
                if (hull == null) {
                    continue;
                }
                Vector3d velocity = ship.latestLinearVelocity;
                double speed = velocity == null || !Double.isFinite(velocity.length()) ? 0.0 : velocity.length();
                boxes.add(hull.expand(CarryBroadphase.INFLATE + 3.0 + Math.min(16.0, speed * 0.1)));
            }
        }
        REACHES.put(dimension, new CachedReaches(time, boxes));
        return boxes;
    }

    private static TickBudget budget(ServerLevel level) {
        TickBudget budget = BUDGETS.computeIfAbsent(level.dimension().location().toString(), ignored -> new TickBudget());
        // Keyed by the server tick, so the physics hook and the end-of-tick hook share one allowance.
        budget.begin(level.getServer().getTickCount(), SafeLandingConfig.tickBudgetMicros());
        return budget;
    }

    /** Deck shape time charged to this dimension's budget in its current tick. */
    public static long budgetSpentNanos(String dimension) {
        TickBudget budget = BUDGETS.get(dimension);
        return budget == null ? 0L : budget.spentNanos();
    }

    /** Ticks in which deck shape work ran out of budget, all dimensions. */
    public static long budgetExhaustedTicks() {
        long total = 0;
        for (TickBudget budget : BUDGETS.values()) {
            total += budget.exhaustedTicks();
        }
        return total;
    }

    /** The world box mapped into the contraption's block grid, one block wider each way. */
    private static int[] localRegion(AbstractContraptionEntity entity, Aabb world, net.minecraft.world.phys.Vec3 anchor) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        double[] xs = {world.minX(), world.maxX()};
        double[] ys = {world.minY(), world.maxY()};
        double[] zs = {world.minZ(), world.maxZ()};
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    net.minecraft.world.phys.Vec3 local = entity.reverseRotation(
                            new net.minecraft.world.phys.Vec3(x, y, z).subtract(anchor).subtract(ROTATION_OFFSET), 1.0f
                    ).add(ROTATION_OFFSET);
                    minX = Math.min(minX, local.x);
                    minY = Math.min(minY, local.y);
                    minZ = Math.min(minZ, local.z);
                    maxX = Math.max(maxX, local.x);
                    maxY = Math.max(maxY, local.y);
                    maxZ = Math.max(maxZ, local.z);
                }
            }
        }
        return new int[]{
                Mth.floor(minX) - 1, Mth.floor(minY) - 1, Mth.floor(minZ) - 1,
                Mth.floor(maxX) + 1, Mth.floor(maxY) + 1, Mth.floor(maxZ) + 1
        };
    }

    /** The rotation axis of a deck that is turned off a quarter turn, or null. */
    private static Direction.Axis turnedAxis(AbstractContraptionEntity entity) {
        if (!(entity instanceof ControlledContraptionEntity controlled) || controlled.getRotationAxis() == null) {
            return null;
        }
        double angle = controlled.getAngle(1.0f);
        double quarter = Math.abs(angle / 90.0 - Math.rint(angle / 90.0));
        return quarter < 1.0e-4 ? null : controlled.getRotationAxis();
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

    public static int lockCount() {
        return LOCKS.size();
    }

    public static int heldCount() {
        int held = 0;
        for (DeckLock lock : LOCKS.values()) {
            if (lock.confirmed) {
                held++;
            }
        }
        return held;
    }

    /** Drops locks whose ship or deck lives in this chunk. Does not teleport. */
    public static void dropChunk(ServerLevel level, ChunkPos pos) {
        if (pos == null) {
            return;
        }
        String prefix = level.dimension().location().toString() + "\0";
        List<String> drop = new ArrayList<>();
        for (Map.Entry<String, DeckLock> entry : LOCKS.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            DeckLock lock = entry.getValue();
            if (shipInChunk(lock.ship, pos) || entityInChunk(lock.contraption, pos)) {
                SableSafeLanding.LOGGER.info(
                        "LIVE deck release ship {} reason=unloaded",
                        entry.getKey().substring(prefix.length())
                );
                drop.add(entry.getKey());
            }
        }
        for (String key : drop) {
            LOCKS.remove(key);
        }
    }

    private static boolean shipInChunk(ServerSubLevel ship, ChunkPos pos) {
        if (ship == null || ship.logicalPose() == null || ship.logicalPose().position() == null) {
            return false;
        }
        Vector3dc position = ship.logicalPose().position();
        int x = net.minecraft.util.Mth.floor(position.x()) >> 4;
        int z = net.minecraft.util.Mth.floor(position.z()) >> 4;
        return x == pos.x && z == pos.z;
    }

    private static boolean entityInChunk(AbstractContraptionEntity entity, ChunkPos pos) {
        return entity != null && entity.chunkPosition().equals(pos);
    }

    private static boolean chunkLoaded(ServerLevel level, ServerSubLevel ship) {
        Vector3dc position = ship.logicalPose().position();
        if (position == null || !Double.isFinite(position.x()) || !Double.isFinite(position.y()) || !Double.isFinite(position.z())) {
            return false;
        }
        return level.isLoaded(BlockPos.containing(position.x(), position.y(), position.z()));
    }

    private static boolean contraptionUnloaded(ServerLevel level, AbstractContraptionEntity entity) {
        if (entity == null) {
            return false;
        }
        if (entity.level() != level) {
            return true;
        }
        return !level.isLoaded(entity.blockPosition());
    }

    private static void calmIfAbsurd(ServerLevel level, ServerSubLevel ship) {
        Vector3d body = new Vector3d();
        PhysicsPipeline pipeline = pipeline(level);
        if (pipeline != null) {
            try {
                Vector3d read = pipeline.getLinearVelocity(ship, body);
                if (read != null) {
                    body = read;
                }
            } catch (RuntimeException ex) {
                logFailure(ex);
            }
        }
        Vector3dc pose = ship.latestLinearVelocity;
        Vec3 bodyVec = new Vec3(body.x, body.y, body.z);
        Vec3 poseVec = pose == null ? Vec3.ZERO : new Vec3(pose.x(), pose.y(), pose.z());
        if (VelocityClamp.calm(bodyVec) == bodyVec && VelocityClamp.calm(poseVec) == poseVec) {
            return;
        }
        if (pipeline != null) {
            try {
                pipeline.resetVelocity(ship);
            } catch (RuntimeException ex) {
                logFailure(ex);
            }
        }
        ship.latestLinearVelocity.zero();
        ship.latestAngularVelocity.zero();
        double speed = Math.max(bodyVec.finite() ? bodyVec.length() : 0.0, poseVec.finite() ? poseVec.length() : 0.0);
        SableSafeLanding.LOGGER.info("LIVE deck calm ship {} speed={}", ship.getUniqueId(), fmt(speed));
    }

    /**
     * Moves a car that is sitting on a carried ship by the parent's pivot.
     * The offset is captured only while the child is still on the hull. A piloted tick,
     * a car that leaves the hull, a child with its own lock or in another level, and a
     * correction past {@code acceptable} or {@code safeStep} drop that seat, so the next
     * on-hull tick stores where the car is now.
     */
    private static void shiftPassengers(
            ServerLevel level,
            ServerSubLevel parent,
            DeckLock lock,
            Vec3 oldPivot,
            Vec3 pivot,
            double angle,
            double angleStep
    ) {
        if (!(SubLevelContainer.getContainer(level) instanceof ServerSubLevelContainer container)) {
            return;
        }
        if (!oldPivot.finite() || !pivot.finite() || lock.axis == null) {
            return;
        }
        Aabb hull = shipBox(parent);
        String parentId = parent.getUniqueId().toString();
        String dimension = level.dimension().location().toString();
        Set<String> locked = new HashSet<>();
        for (DeckLock held : LOCKS.values()) {
            if (held.ship != null) {
                locked.add(held.ship.getUniqueId().toString());
            }
        }
        for (ServerSubLevel child : container.getAllSubLevels()) {
            if (child == parent) {
                continue;
            }
            String childId = child.getUniqueId().toString();
            if (child.isRemoved() || piloting(level, child)) {
                SEATS.remove(childId);
                continue;
            }
            if (child.getLevel() != level || locked.contains(childId)) {
                SEATS.remove(childId);
                continue;
            }
            Vector3dc position = child.logicalPose().position();
            Quaterniondc orientation = child.logicalPose().orientation();
            PassengerBook.Placement placement = SEATS.apply(new PassengerBook.Tick(
                    childId,
                    parentId,
                    dimension,
                    false,
                    false,
                    false,
                    hull,
                    shipBox(child),
                    new Vec3(position.x(), position.y(), position.z()),
                    new Rot(orientation.x(), orientation.y(), orientation.z(), orientation.w()),
                    oldPivot,
                    pivot,
                    lock.axis,
                    lock.lastPlacedAngle,
                    angle,
                    angleStep
            ));
            if (placement == null) {
                continue;
            }
            shiftBody(level, child, placement.position(), placement.orientation());
        }
    }

    /**
     * Drops offsets whose parent is no longer carried, and this dimension's seats
     * whose child is gone. Other dimensions are left for their own tick.
     */
    private static void prunePassengers(String dimension, ServerSubLevelContainer container) {
        Set<String> parents = new HashSet<>();
        for (DeckLock lock : LOCKS.values()) {
            if (lock.ship != null && !lock.ship.isRemoved()) {
                parents.add(lock.ship.getUniqueId().toString());
            }
        }
        SEATS.forgetParents(parents);
        Set<String> live = new HashSet<>();
        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (!subLevel.isRemoved()) {
                live.add(subLevel.getUniqueId().toString());
            }
        }
        SEATS.retainDimension(dimension, live);
    }

    private static void shiftBody(ServerLevel level, ServerSubLevel child, Vec3 target, Rot turned) {
        Quaterniond copy = new Quaterniond(turned.x(), turned.y(), turned.z(), turned.w());
        Vector3d position = new Vector3d(target.x(), target.y(), target.z());
        PhysicsPipeline pipeline = pipeline(level);
        if (pipeline != null) {
            pipeline.wakeUp(child);
            pipeline.teleport(child, position, copy);
        } else {
            child.logicalPose().position().set(position);
            child.logicalPose().orientation().set(copy);
        }
        child.updateLastPose();
        child.updateBoundingBox();
    }

    private static void logLag(double timeStep) {
        if (!LAG_LOGS.tryAcquire(System.nanoTime())) {
            return;
        }
        SableSafeLanding.LOGGER.info("Skipping ride velocity for a {}s physics step", fmt(timeStep));
    }

    private static void logFailure(RuntimeException ex) {
        if (!FAILURE_LOGS.tryAcquire(System.nanoTime())) {
            return;
        }
        SableSafeLanding.LOGGER.warn("Contraption carry failed: {}", ex.toString());
    }
}
