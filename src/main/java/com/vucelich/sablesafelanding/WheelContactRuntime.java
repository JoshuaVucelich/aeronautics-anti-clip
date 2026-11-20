package com.vucelich.sablesafelanding;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.vucelich.sablesafelanding.sim.Aabb;
import com.vucelich.sablesafelanding.sim.RateLimiter;
import com.vucelich.sablesafelanding.sim.TickBudget;
import com.vucelich.sablesafelanding.sim.WheelContact;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.LevelPoseProviderExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lets an Offroad wheel mount's suspension ray land on a moving Create contraption.
 *
 * <p>The wheel mount finds the ground with {@code Level.clip}. Sable's clip covers world blocks
 * and sub-levels, but a contraption's blocks live inside the contraption, so a wheel over an
 * elevator deck casts straight through it. The body still rests on the deck because the collider
 * feed puts the deck in Rapier. Without ground under the wheels there is no spring force and no
 * drive force, so the car sinks onto its hull and cannot drive on or off.
 *
 * <p>This casts the same ray through nearby contraptions and returns the deck hit when it is
 * closer than what Sable found. The hit is in world space with a world face, so the wheel mount
 * reads it the same way it reads a world block.
 *
 * <p>Cost: once per level per tick the contraption bounds are cached (at most
 * {@link ColliderFeedRuntime#MAX_CONTRAPTIONS}). Once per ship per tick that list is cut to the
 * contraptions that can reach the ship's wheel rays. A ray from a ship with none of those does
 * three map reads and returns. The wheel mount's ship is cached, so a ray does no Sable lookup.
 * A ray that does reach a deck walks only the few block cells along the ray, never the whole
 * contraption, and all deck rays in a level share the per-tick {@code tickBudgetMicros} cap.
 * Past the cap a ray keeps Offroad's own result for the rest of that tick.
 */
public final class WheelContactRuntime {
    private static final RateLimiter FAILURE_LOGS = new RateLimiter(5_000_000_000L);
    /** Server wheel rays that stood on a deck: within suspension reach and up-facing. */
    private static final AtomicLong DECK_HITS = new AtomicLong();
    /** Server only: the deck each wheeled ship last stood on, and the game tick it was seen. */
    private static final Map<UUID, WheelTouch> WHEEL_TOUCH = new ConcurrentHashMap<>();
    /** A touch this many ticks old still counts, so physics and the carry tick can be out of step by one. */
    private static final long TOUCH_TICKS = 2;
    /** A ship's candidate list not used for this many ticks is dropped. */
    private static final long SHIP_IDLE_TICKS = 20;
    /** Used when the tire radius cannot be read. The standard Offroad tire is 0.97. */
    private static final double FALLBACK_RADIUS = 1.0;
    private static final ResourceLocation TIRE_COMPONENT = ResourceLocation.parse("offroad:tire");

    private static final Map<Level, LevelBounds> BOUNDS = new ConcurrentHashMap<>();
    private static final Map<SubLevel, ShipCandidates> SHIPS = new ConcurrentHashMap<>();
    private static final Map<Object, SubLevel> MOUNT_SHIP = Collections.synchronizedMap(new WeakHashMap<>());
    /** Per-level cap on deck ray time. Each level is only used from its own thread. */
    private static final Map<Level, TickBudget> BUDGETS = Collections.synchronizedMap(new WeakHashMap<>());
    /** A ground ray longer than this is not a suspension ray; it is left as Offroad cast it. */
    private static final double MAX_RAY = 32.0;
    private static final AtomicLong BUDGET_SKIPS = new AtomicLong();
    private static final AtomicLong SERVER_DECK_CALLS = new AtomicLong();
    private static final AtomicLong SERVER_DECK_NANOS = new AtomicLong();
    /** Learned cost of one wheel ray against one nearby deck. Shared by both sides; each side only reads its own effect. */
    private static final com.vucelich.sablesafelanding.sim.CostEstimate RAY_COST =
            new com.vucelich.sablesafelanding.sim.CostEstimate(20_000.0, 500.0);
    private static final com.vucelich.sablesafelanding.sim.CostEstimate CLIENT_RAY_COST =
            new com.vucelich.sablesafelanding.sim.CostEstimate(20_000.0, 500.0);

    private static volatile Method heldItem;
    private static volatile Method radiusAccessor;
    private static volatile DataComponentType<?> tireType;
    private static volatile boolean reflectionFailed;

    private record WheelTouch(String contraptionId, long gameTime) {
    }

    private record Candidate(AbstractContraptionEntity entity, Contraption contraption, Aabb reach) {
    }

    private record LevelBounds(long gameTime, List<Candidate> all, long serverTick, int version) {
    }

    /**
     * {@code far} means no deck was within the reach plus {@link #FAR_SLACK}. On the server such
     * an answer is reused while no deck has moved, the contraption set is the same, and the ship
     * has not moved {@link #FAR_SLACK} blocks, for at most {@link #FAR_TICKS} ticks.
     */
    private record ShipCandidates(long gameTime, List<Candidate> near, boolean far, long serverTick, int version,
                                  double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    }

    /** Blocks a ship may move before a cached "no deck near" answer is checked again. */
    static final double FAR_SLACK = 1.0;
    /** Ticks a cached "no deck near" answer or a still deck list is trusted at most. */
    static final long FAR_TICKS = 100;

    private WheelContactRuntime() {
    }

    /** True when the config switch is on. A config that is not loaded yet counts as on. */
    public static boolean switchOn() {
        try {
            return SafeLandingConfig.WHEEL_CONTACT.getAsBoolean();
        } catch (RuntimeException ex) {
            return true;
        }
    }

    /**
     * Server wheel rays that stood on a contraption deck since startup: within the wheel's
     * suspension reach and on an up-facing face. Shown by /anticlip status.
     */
    public static long deckHits() {
        return DECK_HITS.get();
    }

    /**
     * The contraption id a ship's wheels stood on within the last couple of ticks, or null.
     * The carry uses this as contact: a car on its wheels does not touch the deck with its hull.
     */
    public static String wheelSupport(UUID shipId, long gameTime) {
        WheelTouch touch = WHEEL_TOUCH.get(shipId);
        if (touch == null) {
            return null;
        }
        if (gameTime - touch.gameTime > TOUCH_TICKS || touch.gameTime > gameTime) {
            WHEEL_TOUCH.remove(shipId, touch);
            return null;
        }
        return touch.contraptionId;
    }

    /** Wheel deck rays left to Offroad because the tick budget ran out. Shown by /anticlip status. */
    /** Ground rays that went through the deck test on the server, and their total time. */
    public static long serverDeckCalls() {
        return SERVER_DECK_CALLS.get();
    }

    public static long serverDeckNanos() {
        return SERVER_DECK_NANOS.get();
    }

    public static long budgetSkips() {
        return BUDGET_SKIPS.get();
    }

    /** Drops every cache and wheel touch. Called on server stop. */
    public static void clear() {
        RAY_MEMO.clear();
        WHEEL_TOUCH.clear();
        BOUNDS.clear();
        SHIPS.clear();
        MOUNT_SHIP.clear();
    }

    /** Drops the caches for one level. Called on level unload. */
    public static void forget(Level level) {
        if (level == null) {
            return;
        }
        BOUNDS.remove(level);
        BUDGETS.remove(level);
        SHIPS.keySet().removeIf(ship -> ship == null || ship.getLevel() == level);
    }

    /** Number of wheel touches held. Shown by /anticlip status so a leak would be visible. */
    public static int touchCount() {
        return WHEEL_TOUCH.size();
    }

    /**
     * Called from the wheel mount's ground ray with the result Sable already computed.
     * Returns {@code base} unless a contraption deck is strictly closer along the same ray.
     */
    public static BlockHitResult withDecks(Level level, ClipContext context, BlockHitResult base, Object wheelMount) {
        if (level == null || context == null) {
            return base;
        }
        if (!level.isClientSide()) {
            return serverRay(level, context, base, wheelMount);
        }
        long start = System.nanoTime();
        try {
            if (!SafeLandingConfig.clientWheelContact() || !SafeLandingConfig.running() || !switchOn()) {
                return base;
            }
            return deckRay(level, context, base, wheelMount, null);
        } finally {
            ClientPerf.add(level.getGameTime(), System.nanoTime() - start);
        }
    }

    private static final boolean WHEEL_DEBUG = Boolean.getBoolean("sable_safe_landing.gateDebug");

    private static BlockHitResult serverRay(Level level, ClipContext context, BlockHitResult base, Object wheelMount) {
        if (!SafeLandingConfig.running() || !switchOn()) {
            return base;
        }
        if (!(level instanceof net.minecraft.server.level.ServerLevel server)) {
            return base;
        }
        // No ship near any deck in this level: Offroad's own ray, after one cached check.
        if (Decks.of(level).isEmpty() || !DeckGate.near(server)) {
            if (WHEEL_DEBUG && level.getGameTime() % 20 == 0) {
                SableSafeLanding.LOGGER.info("GATE wheel skip decks={} near={} live={} t={}", Decks.of(level).size(),
                        DeckGate.near(server), LiveContraptions.in(level).size(), level.getGameTime());
            }
            return base;
        }
        long start = System.nanoTime();
        try {
            SubLevel ship = shipOf(wheelMount);
            if (ship != null && candidatesFor(level, ship, Decks.of(level)).isEmpty()) {
                // This ship is not near any deck: Offroad's own ray, from the per-ship cache.
                return base;
            }
            boolean memoOk = ship != null && SafeLandingConfig.idleSleep();
            long tick = DeckGate.tickOf(server);
            if (memoOk) {
                BlockHitResult remembered = recall(server, context, base, wheelMount, ship, tick);
                if (remembered != null) {
                    return remembered;
                }
            }
            long skipsBefore = BUDGET_SKIPS.get();
            lastTouchDeck = null;
            BlockHitResult result = deckRay(level, context, base, wheelMount, CarryRuntime.budget(server));
            if (memoOk && BUDGET_SKIPS.get() == skipsBefore) {
                remember(server, context, base, result, wheelMount, ship, tick, lastTouchDeck);
            }
            return result;
        } finally {
            long used = System.nanoTime() - start;
            PerfMeter.add(PerfMeter.Part.WHEEL, used);
            SERVER_DECK_CALLS.incrementAndGet();
            SERVER_DECK_NANOS.addAndGet(used);
        }
    }

    /**
     * One wheel ray's last answer on the server. While no deck in the level has moved since it
     * was made, no contraption joined or left, the ship has not moved, and Offroad's own ray
     * still gives the same hit, the deck answer cannot have changed, so it is reused instead of
     * cast again. This is what a car parked on a stopped deck costs: a few field compares per ray.
     */
    private static final class RayMemo {
        double fx, fy, fz, tx, ty, tz;
        double px, py, pz, qx, qy, qz, qw;
        double bx, by, bz;
        boolean baseMiss;
        long tick;
        int version;
        BlockHitResult result;
        String touchDeck;
    }

    /** Ticks a remembered ray answer is trusted at most, even when nothing moved. */
    static final long MEMO_TICKS = 100;
    private static final double MEMO_POS = 1.0e-3;
    private static final double MEMO_ROT = 1.0e-5;
    private static final Map<Object, RayMemo[]> RAY_MEMO = Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicLong MEMO_HITS = new AtomicLong();
    /** Set by {@link #noteTouch} during one server cast: the deck the wheel stood on. */
    private static String lastTouchDeck;

    /** Server wheel rays answered from memory because nothing had moved. */
    public static long memoHits() {
        return MEMO_HITS.get();
    }

    private static final java.util.concurrent.atomic.AtomicLongArray MEMO_MISS = new java.util.concurrent.atomic.AtomicLongArray(7);

    /** Memo misses by reason: none stored, contraption set changed, a deck moved, too old, Offroad's hit changed, ship moved, no stored ray matched. */
    public static String memoMisses() {
        return "[none=" + MEMO_MISS.get(0) + " set=" + MEMO_MISS.get(1) + " deckMoved=" + MEMO_MISS.get(2) + " age=" + MEMO_MISS.get(3)
                + " base=" + MEMO_MISS.get(4) + " pose=" + MEMO_MISS.get(5) + " noRay=" + MEMO_MISS.get(6) + "]";
    }

    private static boolean sameRay(RayMemo memo, ClipContext context) {
        Vec3 from = context.getFrom();
        Vec3 to = context.getTo();
        return memo.fx == from.x && memo.fy == from.y && memo.fz == from.z
                && memo.tx == to.x && memo.ty == to.y && memo.tz == to.z;
    }

    private static boolean sameBase(RayMemo memo, BlockHitResult base) {
        boolean miss = base == null || base.getType() == HitResult.Type.MISS;
        if (miss || memo.baseMiss) {
            return miss == memo.baseMiss;
        }
        Vec3 at = base.getLocation();
        return Math.abs(at.x - memo.bx) <= MEMO_POS && Math.abs(at.y - memo.by) <= MEMO_POS && Math.abs(at.z - memo.bz) <= MEMO_POS;
    }

    private static BlockHitResult recall(net.minecraft.server.level.ServerLevel level, ClipContext context, BlockHitResult base,
                                         Object wheelMount, SubLevel ship, long tick) {
        RayMemo[] memos = RAY_MEMO.get(wheelMount);
        if (memos == null) {
            MEMO_MISS.incrementAndGet(0);
            return null;
        }
        long lastMove = DeckGate.lastDeckMove(level);
        int version = LiveContraptions.version();
        Pose3dc pose = poseOf(level, ship);
        for (RayMemo memo : memos) {
            if (memo == null || !sameRay(memo, context)) {
                continue;
            }
            if (memo.version != version) {
                MEMO_MISS.incrementAndGet(1);
                return null;
            }
            if (lastMove >= memo.tick) {
                MEMO_MISS.incrementAndGet(2);
                return null;
            }
            if (tick < memo.tick || tick - memo.tick > MEMO_TICKS) {
                MEMO_MISS.incrementAndGet(3);
                return null;
            }
            if (!sameBase(memo, base)) {
                MEMO_MISS.incrementAndGet(4);
                return null;
            }
            org.joml.Vector3dc p = pose.position();
            org.joml.Quaterniondc q = pose.orientation();
            if (Math.abs(p.x() - memo.px) > MEMO_POS || Math.abs(p.y() - memo.py) > MEMO_POS || Math.abs(p.z() - memo.pz) > MEMO_POS
                    || Math.abs(q.x() - memo.qx) > MEMO_ROT || Math.abs(q.y() - memo.qy) > MEMO_ROT
                    || Math.abs(q.z() - memo.qz) > MEMO_ROT || Math.abs(q.w() - memo.qw) > MEMO_ROT) {
                MEMO_MISS.incrementAndGet(5);
                return null;
            }
            MEMO_HITS.incrementAndGet();
            if (memo.touchDeck != null) {
                DECK_HITS.incrementAndGet();
                WHEEL_TOUCH.put(ship.getUniqueId(), new WheelTouch(memo.touchDeck, level.getGameTime()));
            }
            return memo.result == null ? base : memo.result;
        }
        MEMO_MISS.incrementAndGet(6);
        return null;
    }

    private static void remember(net.minecraft.server.level.ServerLevel level, ClipContext context, BlockHitResult base,
                                 BlockHitResult result, Object wheelMount, SubLevel ship, long tick, String touchDeck) {
        RayMemo[] memos = RAY_MEMO.computeIfAbsent(wheelMount, ignored -> new RayMemo[4]);
        RayMemo slot = null;
        int oldest = 0;
        for (int i = 0; i < memos.length; i++) {
            if (memos[i] == null) {
                memos[i] = new RayMemo();
                slot = memos[i];
                break;
            }
            if (sameRay(memos[i], context)) {
                slot = memos[i];
                break;
            }
            if (memos[i].tick < memos[oldest].tick) {
                oldest = i;
            }
        }
        if (slot == null) {
            slot = memos[oldest];
        }
        Vec3 from = context.getFrom();
        Vec3 to = context.getTo();
        slot.fx = from.x;
        slot.fy = from.y;
        slot.fz = from.z;
        slot.tx = to.x;
        slot.ty = to.y;
        slot.tz = to.z;
        Pose3dc pose = poseOf(level, ship);
        slot.px = pose.position().x();
        slot.py = pose.position().y();
        slot.pz = pose.position().z();
        slot.qx = pose.orientation().x();
        slot.qy = pose.orientation().y();
        slot.qz = pose.orientation().z();
        slot.qw = pose.orientation().w();
        slot.baseMiss = base == null || base.getType() == HitResult.Type.MISS;
        if (!slot.baseMiss) {
            slot.bx = base.getLocation().x;
            slot.by = base.getLocation().y;
            slot.bz = base.getLocation().z;
        }
        slot.tick = tick;
        slot.version = LiveContraptions.version();
        slot.result = result == base ? null : result;
        slot.touchDeck = touchDeck;
    }

    /**
     * The deck cast for one wheel ray. The server shares the dimension's deck budget with the
     * carry; the client has its own. A ray starts only when its learned cost fits what is left.
     */
    private static BlockHitResult deckRay(Level level, ClipContext context, BlockHitResult base, Object wheelMount, TickBudget shared) {
        List<AbstractContraptionEntity> decks = Decks.of(level);
        if (decks.isEmpty()) {
            return base;
        }
        try {
            SubLevel ship = shipOf(wheelMount);
            if (ship == null) {
                return base;
            }
            List<Candidate> near = candidatesFor(level, ship, decks);
            if (near.isEmpty()) {
                // No deck near this ship: Offroad's own ray, nothing added.
                return base;
            }
            TickBudget budget = shared;
            if (budget == null) {
                budget = BUDGETS.computeIfAbsent(level, ignored -> new TickBudget());
                budget.begin(level.getGameTime(), SafeLandingConfig.tickBudgetMicros());
            }
            com.vucelich.sablesafelanding.sim.CostEstimate rayCost = level.isClientSide() ? CLIENT_RAY_COST : RAY_COST;
            if (!budget.allows(rayCost.estimate(near.size()))) {
                BUDGET_SKIPS.incrementAndGet();
                budget.defer();
                rayCost.relax();
                if (level.isClientSide()) {
                    ClientPerf.budgetSkip();
                }
                return base;
            }
            long start = System.nanoTime();
            BlockHitResult deck = null;
            try {
                deck = cast(level, context, base, ship, near, wheelMount);
                return deck == null ? base : deck;
            } finally {
                long used = System.nanoTime() - start;
                budget.charge(used);
                rayCost.observe(near.size(), used);
                if (level.isClientSide()) {
                    ClientPerf.ray(deck != null);
                }
            }
        } catch (RuntimeException ex) {
            if (FAILURE_LOGS.tryAcquire(System.nanoTime())) {
                SableSafeLanding.LOGGER.warn("Wheel deck check failed; keeping the original ground ray: {}", ex.toString());
            }
            return base;
        }
    }

    /** The wheel mount's ship. Cached per mount; a removed ship is looked up again. */
    private static SubLevel shipOf(Object wheelMount) {
        if (!(wheelMount instanceof BlockEntity mount)) {
            return null;
        }
        SubLevel ship = MOUNT_SHIP.get(mount);
        if (ship != null && !ship.isRemoved()) {
            return ship;
        }
        ship = Sable.HELPER.getContaining(mount);
        if (ship != null) {
            MOUNT_SHIP.put(mount, ship);
        } else {
            MOUNT_SHIP.remove(mount);
        }
        return ship;
    }

    /** Contraptions that can reach this ship's wheel rays this tick. Built once per ship per tick. */
    private static List<Candidate> candidatesFor(Level level, SubLevel ship, List<AbstractContraptionEntity> live) {
        long now = level.getGameTime();
        ShipCandidates cached = SHIPS.get(ship);
        if (cached != null && cached.gameTime == now) {
            return cached.near;
        }
        BoundingBox3dc box = ship.boundingBox();
        net.minecraft.server.level.ServerLevel server = level instanceof net.minecraft.server.level.ServerLevel sl
                && SafeLandingConfig.idleSleep() ? sl : null;
        long serverTick = server == null ? 0 : DeckGate.tickOf(server);
        int version = LiveContraptions.version();
        if (cached != null && cached.far && server != null && box != null && cached.version == version
                && serverTick >= cached.serverTick && serverTick - cached.serverTick <= FAR_TICKS
                && DeckGate.lastDeckMove(server) < cached.serverTick
                && Math.abs(box.minX() - cached.minX) < FAR_SLACK && Math.abs(box.minY() - cached.minY) < FAR_SLACK
                && Math.abs(box.minZ() - cached.minZ) < FAR_SLACK && Math.abs(box.maxX() - cached.maxX) < FAR_SLACK
                && Math.abs(box.maxY() - cached.maxY) < FAR_SLACK && Math.abs(box.maxZ() - cached.maxZ) < FAR_SLACK) {
            return List.of();
        }
        List<Candidate> all = boundsFor(level, live, now);
        List<Candidate> near = List.of();
        boolean far = false;
        Aabb reach = box == null ? null
                : WheelContact.shipReach(new Aabb(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
        if (reach != null) {
            Aabb wide = reach.expand(FAR_SLACK);
            far = true;
            List<Candidate> found = null;
            for (Candidate candidate : all) {
                if (wide.intersects(candidate.reach)) {
                    far = false;
                    if (reach.intersects(candidate.reach)) {
                        if (found == null) {
                            found = new ArrayList<>(2);
                        }
                        found.add(candidate);
                    }
                }
            }
            near = found == null ? List.of() : found;
        }
        SHIPS.put(ship, box == null
                ? new ShipCandidates(now, near, false, serverTick, version, 0, 0, 0, 0, 0, 0)
                : new ShipCandidates(now, near, far, serverTick, version,
                box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
        return near;
    }

    /** Every live contraption's reach in this level, built once per tick and capped. */
    private static List<Candidate> boundsFor(Level level, List<AbstractContraptionEntity> live, long now) {
        LevelBounds cached = BOUNDS.get(level);
        if (cached != null && cached.gameTime == now) {
            return cached.all;
        }
        net.minecraft.server.level.ServerLevel server = level instanceof net.minecraft.server.level.ServerLevel sl
                && SafeLandingConfig.idleSleep() ? sl : null;
        long serverTick = server == null ? 0 : DeckGate.tickOf(server);
        int version = LiveContraptions.version();
        if (cached != null && server != null && cached.version == version && serverTick >= cached.serverTick
                && serverTick - cached.serverTick <= FAR_TICKS && DeckGate.lastDeckMove(server) < cached.serverTick) {
            // No deck moved and no contraption joined or left: the bounds are the same.
            BOUNDS.put(level, new LevelBounds(now, cached.all, cached.serverTick, cached.version));
            prune(level, now);
            return cached.all;
        }
        List<Candidate> all = new ArrayList<>();
        for (AbstractContraptionEntity entity : live) {
            if (all.size() >= ColliderFeedRuntime.MAX_CONTRAPTIONS) {
                break;
            }
            if (entity == null || entity.isRemoved() || entity.level() != level) {
                continue;
            }
            Contraption contraption = entity.getContraption();
            if (contraption == null || ContraptionKinds.nameOf(contraption) == null) {
                continue;
            }
            Aabb reach = WheelContact.reach(sim(entity.getBoundingBox()));
            if (reach != null) {
                all.add(new Candidate(entity, contraption, reach));
            }
        }
        BOUNDS.put(level, new LevelBounds(now, all, serverTick, version));
        prune(level, now);
        return all;
    }

    /** Runs once per level per tick: drops idle ship lists and expired wheel touches. */
    private static void prune(Level level, long now) {
        SHIPS.entrySet().removeIf(entry -> {
            SubLevel ship = entry.getKey();
            if (ship == null || ship.isRemoved()) {
                return true;
            }
            return ship.getLevel() == level && now - entry.getValue().gameTime > SHIP_IDLE_TICKS;
        });
        if (!level.isClientSide()) {
            WHEEL_TOUCH.values().removeIf(touch -> now - touch.gameTime > TOUCH_TICKS || touch.gameTime > now);
        }
    }

    private static BlockHitResult cast(
            Level level,
            ClipContext context,
            BlockHitResult base,
            SubLevel ship,
            List<Candidate> near,
            Object wheelMount
    ) {
        Pose3dc pose = poseOf(level, ship);
        // Offroad casts from the wheel in the ship's plot. Sable's clip moves it to the world the same way.
        Vec3 from = transform(pose, context.getFrom());
        Vec3 to = transform(pose, context.getTo());
        if (from.distanceToSqr(to) > MAX_RAY * MAX_RAY) {
            return null;
        }
        Aabb ray = WheelContact.rayBounds(sim(from), sim(to));
        if (ray == null) {
            return null;
        }
        double bestDistance = Double.POSITIVE_INFINITY;
        Vec3 bestHit = null;
        Vec3 bestNormal = null;
        AbstractContraptionEntity bestEntity = null;
        for (Candidate candidate : near) {
            AbstractContraptionEntity entity = candidate.entity;
            if (entity.isRemoved() || !ray.intersects(candidate.reach)) {
                continue;
            }
            BlockHitResult local = castLocal(entity, candidate.contraption, from, to);
            if (local == null || local.getType() == HitResult.Type.MISS) {
                continue;
            }
            Vec3 hit = entity.toGlobalVector(local.getLocation(), 1.0F);
            double distance = hit.distanceTo(from);
            if (distance < bestDistance) {
                Direction face = local.getDirection();
                bestDistance = distance;
                bestHit = hit;
                bestEntity = entity;
                bestNormal = entity.applyRotation(new Vec3(face.getStepX(), face.getStepY(), face.getStepZ()), 1.0F);
            }
        }
        if (bestHit == null) {
            return null;
        }
        boolean baseHit = base != null && base.getType() != HitResult.Type.MISS;
        double baseDistance = baseHit ? toWorld(level, base.getLocation()).distanceTo(from) : Double.NaN;
        if (!WheelContact.preferDeck(baseHit, baseDistance, true, bestDistance)) {
            return null;
        }
        Direction face = Direction.getNearest(bestNormal.x, bestNormal.y, bestNormal.z);
        // The deck block's spot in the world. The wheel reads friction there; it is usually air,
        // which Sable rates at the default friction of 1.
        BlockPos pos = BlockPos.containing(bestHit.subtract(bestNormal.scale(0.01)));
        if (!level.isClientSide()) {
            noteTouch(level, ship, pose, bestEntity, bestDistance, bestNormal, wheelMount);
        }
        return new BlockHitResult(bestHit, face, pos, false);
    }

    /**
     * Records the deck as the ship's support only when Offroad would treat the wheel as standing
     * on it: within rest + tire radius + 0.25 of the wheel center, on a face pointing up in the
     * ship's frame. A ship hovering above a deck is not carry contact.
     */
    private static void noteTouch(
            Level level,
            SubLevel ship,
            Pose3dc pose,
            AbstractContraptionEntity deck,
            double distance,
            Vec3 worldNormal,
            Object wheelMount
    ) {
        Vector3d up = pose.transformNormalInverse(new Vector3d(worldNormal.x, worldNormal.y, worldNormal.z));
        double length = up.length();
        double upDot = length > 1.0e-9 ? up.y / length : 0.0;
        double radius = tireRadius(wheelMount);
        if (!WheelContact.standing(distance, radius, upDot)) {
            return;
        }
        DECK_HITS.incrementAndGet();
        String deckId = deck.getUUID().toString();
        lastTouchDeck = deckId;
        WHEEL_TOUCH.put(ship.getUniqueId(), new WheelTouch(deckId, level.getGameTime()));
    }

    /** The held tire's radius, read the way Offroad does. No tire means the wheel does nothing. */
    private static double tireRadius(Object wheelMount) {
        if (reflectionFailed) {
            return FALLBACK_RADIUS;
        }
        try {
            Method held = heldItem;
            if (held == null) {
                held = wheelMount.getClass().getMethod("getHeldItem");
                heldItem = held;
            }
            DataComponentType<?> type = tireType;
            if (type == null) {
                type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(TIRE_COMPONENT);
                if (type == null) {
                    throw new IllegalStateException("no offroad:tire component");
                }
                tireType = type;
            }
            Object stack = held.invoke(wheelMount);
            if (!(stack instanceof ItemStack item)) {
                return FALLBACK_RADIUS;
            }
            Object tire = item.get(type);
            if (tire == null) {
                return FALLBACK_RADIUS;
            }
            Method radius = radiusAccessor;
            if (radius == null || radius.getDeclaringClass() != tire.getClass()) {
                radius = tire.getClass().getMethod("radius");
                radiusAccessor = radius;
            }
            Object value = radius.invoke(tire);
            return value instanceof Number number ? number.doubleValue() : FALLBACK_RADIUS;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            reflectionFailed = true;
            SableSafeLanding.LOGGER.warn("Could not read the Offroad tire radius; using {}: {}", FALLBACK_RADIUS, ex.toString());
            return FALLBACK_RADIUS;
        }
    }

    private static BlockHitResult castLocal(AbstractContraptionEntity entity, Contraption contraption, Vec3 from, Vec3 to) {
        Vec3 localFrom = entity.toLocalVector(from, 1.0F);
        Vec3 localTo = entity.toLocalVector(to, 1.0F);
        Map<BlockPos, StructureBlockInfo> blocks = contraption.getBlocks();
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        BlockGetter shapes = shapeWorld(contraption);
        return BlockGetter.traverseBlocks(localFrom, localTo, blocks, (map, pos) -> {
            StructureBlockInfo info = map.get(pos);
            if (info == null) {
                return null;
            }
            BlockState state = info.state();
            VoxelShape shape = state.getCollisionShape(shapes, pos);
            if (shape.isEmpty()) {
                return null;
            }
            return shape.clip(localFrom, localTo, pos.immutable());
        }, map -> null);
    }

    private static BlockGetter shapeWorld(Contraption contraption) {
        try {
            BlockGetter world = contraption.getContraptionWorld();
            return world == null ? EmptyBlockGetter.INSTANCE : world;
        } catch (RuntimeException ex) {
            return EmptyBlockGetter.INSTANCE;
        }
    }

    /** Same projection Sable's clip uses: a point inside a sub-level plot goes to world space. */
    static Vec3 toWorld(Level level, Vec3 point) {
        SubLevel subLevel = Sable.HELPER.getContaining(level, point);
        return subLevel == null ? point : transform(poseOf(level, subLevel), point);
    }

    /** The pose Sable's clip uses for this sub-level: the level's provided pose when it has one. */
    private static Pose3dc poseOf(Level level, SubLevel subLevel) {
        Pose3dc pose = subLevel.logicalPose();
        if (level instanceof LevelPoseProviderExtension provider) {
            Pose3dc provided = provider.sable$getPose(subLevel);
            if (provided != null) {
                pose = provided;
            }
        }
        return pose;
    }

    private static Vec3 transform(Pose3dc pose, Vec3 point) {
        return JOMLConversion.toMojang(pose.transformPosition(JOMLConversion.toJOML(point)));
    }

    private static com.vucelich.sablesafelanding.sim.Vec3 sim(Vec3 v) {
        return new com.vucelich.sablesafelanding.sim.Vec3(v.x, v.y, v.z);
    }

    private static Aabb sim(AABB box) {
        if (box == null || box.minX > box.maxX || box.minY > box.maxY || box.minZ > box.maxZ) {
            return null;
        }
        return new Aabb(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }
}
