package com.vucelich.sablesafelanding;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.vucelich.sablesafelanding.sim.Aabb;
import com.vucelich.sablesafelanding.sim.AntiClipConfig;
import com.vucelich.sablesafelanding.sim.RateLimiter;
import com.vucelich.sablesafelanding.sim.ColliderFeedPlanner;
import com.vucelich.sablesafelanding.sim.FeedAction;
import com.vucelich.sablesafelanding.sim.FeedRequest;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Uploads world elevator, bearing, piston, and gantry colliders. Contraptions already inside a
 * sub-level stay on Sable's own kinematic path.
 */
public final class ColliderFeedRuntime {
    static final int MAX_CONTRAPTIONS = 256;

    private static final Map<String, ColliderFeedPlanner> PLANNERS = new HashMap<>();
    private static final Map<String, Map<String, FedContraptionCollider>> FED = new HashMap<>();
    private static final Set<String> HOOKED = new HashSet<>();
    private static final Set<String> CLAIMED = new HashSet<>();
    /** Claimed contraptions, including ones whose collider was never uploaded. */
    private static final Map<String, AbstractContraptionEntity> CLAIMS = new HashMap<>();
    private static final Set<String> KINEMATIC_LOGGED = new HashSet<>();
    /** Game tick each dimension was last planned. Later physics substeps in that tick skip it. */
    private static final Map<String, Long> SYNCED_AT = new HashMap<>();
    /** Shape hash per contraption, kept until its block count changes or it is rechecked. */
    private static final Map<Contraption, long[]> HASHES = new java.util.WeakHashMap<>();
    /** Contraptions whose collider could not be built, with the shape hash that failed. */
    private static final Map<String, Long> FAILED = new HashMap<>();
    /** Ticks between rehashes of an unchanged block count, so a swapped block still rebuilds. */
    private static final long REHASH_TICKS = 1_200;
    /** A settled dimension is fully re-planned at least this often. */
    private static final long REPLAN_TICKS = 20;
    /** Per dimension: the live set it was last planned for, when, and whether every slot was a keep. */
    private static final Map<String, long[]> PLANNED = new HashMap<>();
    private static final RateLimiter FAILURE_LOGS = new RateLimiter(5_000_000_000L);
    private static boolean deferredWarning;
    private static boolean hookRan;
    /** Decks the hook offered that are not taken over yet. Sable's collider stays until they are. */
    private static final Map<String, AbstractContraptionEntity> PENDING = new HashMap<>();
    /** Learned cost of one upload per contraption block. */
    private static final com.vucelich.sablesafelanding.sim.CostEstimate UPLOAD_COST =
            new com.vucelich.sablesafelanding.sim.CostEstimate(200.0, 5.0, 64);
    /** Game tick and dimension of the last upload, so at least one per tick always goes through. */
    private static final Map<String, Long> UPLOADED_AT = new HashMap<>();
    private static long deferredUploads;
    private static long uploads;

    private ColliderFeedRuntime() {
    }

    /**
     * The contraption mixin ran for this entity. Server thread only.
     */
    public static void noteHook(AbstractContraptionEntity entity) {
        hookRan = true;
    }

    /**
     * The tick hook saw a world deck this mod should own. It is taken over later, in the feed's
     * own tick and inside the budget, by {@link #apply}. Server thread only.
     */
    public static void offer(AbstractContraptionEntity entity) {
        if (onServerThread(entity)) {
            PENDING.put(entity.getUUID().toString(), entity);
        }
    }

    /** Collider uploads put off to a later tick because the budget was used up. */
    public static long deferredUploads() {
        return deferredUploads;
    }

    /** Collider uploads done since startup. */
    public static long uploads() {
        return uploads;
    }

    /**
     * The tick hook ran and this mod is taking the collider. Sable's kinematic is removed first.
     */
    public static void claim(AbstractContraptionEntity entity) {
        if (onServerThread(entity)) {
            String id = entity.getUUID().toString();
            CLAIMED.add(id);
            CLAIMS.put(id, entity);
        }
    }

    public static boolean hookSeen() {
        return hookRan || !HOOKED.isEmpty();
    }

    public static boolean isClaimed(String id) {
        return CLAIMED.contains(id);
    }

    /**
     * Contraptions this mod is already feeding. Empty when the feed is off, so the caller scans
     * the level once instead of every physics substep.
     */
    public static List<AbstractContraptionEntity> tracked(ServerLevel level) {
        Map<String, FedContraptionCollider> fed = FED.get(level.dimension().location().toString());
        if (fed == null || fed.isEmpty()) {
            return List.of();
        }
        List<AbstractContraptionEntity> entities = new ArrayList<>(fed.size());
        for (FedContraptionCollider collider : fed.values()) {
            if (collider.entity() != null) {
                entities.add(collider.entity());
            }
        }
        return entities;
    }

    /**
     * Sable's initializer already added this entity to the pipeline. Drop that kinematic before
     * this mod uploads its own collider. Server thread only.
     */
    public static void displaceSable(AbstractContraptionEntity entity) {
        if (!(entity.level() instanceof ServerLevel level) || !onServerThread(entity)) {
            return;
        }
        if (!(entity instanceof KinematicContraption kinematic)) {
            return;
        }
        try {
            SubLevelPhysicsSystem.require(level).getPipeline().remove(kinematic);
        } catch (RuntimeException ex) {
            logFailure(ex);
        }
    }

    public static void clear() {
        PLANNERS.clear();
        FED.clear();
        HOOKED.clear();
        CLAIMED.clear();
        CLAIMS.clear();
        KINEMATIC_LOGGED.clear();
        SYNCED_AT.clear();
        PLANNED.clear();
        HASHES.clear();
        FAILED.clear();
        PENDING.clear();
        FAR_CHECKED_AT.clear();
        UPLOADED_AT.clear();
    }

    public static void forgetDimension(String dimension) {
        PLANNERS.remove(dimension);
        SYNCED_AT.remove(dimension);
        PLANNED.remove(dimension);
        UPLOADED_AT.remove(dimension);
        PENDING.values().removeIf(entity -> entity == null || !(entity.level() instanceof ServerLevel server)
                || server.dimension().location().toString().equals(dimension));
        Map<String, FedContraptionCollider> fed = FED.remove(dimension);
        if (fed != null) {
            HOOKED.removeAll(fed.keySet());
        }
        restoreClaims(dimension);
    }

    private static boolean onServerThread(AbstractContraptionEntity entity) {
        return entity.level() instanceof ServerLevel server && server.getServer().isSameThread();
    }

    public static boolean shouldOwn(AbstractContraptionEntity entity) {
        try {
            AntiClipConfig config = SafeLandingConfig.snapshot();
            return config.enabled() && config.feedColliders() && isWorldSupport(entity);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public static boolean isWorldSupport(AbstractContraptionEntity entity) {
        try {
            if (!(entity.level() instanceof ServerLevel)) {
                return false;
            }
            if (ContraptionKinds.nameOf(entity.getContraption()) == null) {
                return false;
            }
            return Sable.HELPER.getContaining(entity) == null;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public static void onPrePhysics(ForgeSablePrePhysicsTickEvent event) {
        long start = System.nanoTime();
        try {
            prePhysics(event);
        } finally {
            PerfMeter.add(PerfMeter.Part.FEED, System.nanoTime() - start);
        }
    }

    private static void prePhysics(ForgeSablePrePhysicsTickEvent event) {
        SubLevelPhysicsSystem system = event.getPhysicsSystem();
        if (system == null || !(system.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!level.getServer().isSameThread()) {
            SableSafeLanding.LOGGER.warn("Ignoring contraption feed off the server thread");
            return;
        }
        try {
            AntiClipConfig config = SafeLandingConfig.snapshot();
            // A config edit can turn the master switch or the feed off without the command.
            if (!config.enabled() || !config.feedColliders()) {
                disarm(level);
                return;
            }
            String dimension = level.dimension().location().toString();
            Long synced = SYNCED_AT.get(dimension);
            if (synced != null && synced == level.getGameTime()) {
                // Planned this tick already. The kinematic samples are per game tick too.
                return;
            }
            SYNCED_AT.put(dimension, level.getGameTime());
            long[] planned = PLANNED.get(dimension);
            long now = level.getGameTime();
            long window = planned != null && planned[2] == 2L ? TAKEOVER_RECHECK : REPLAN_TICKS;
            if (planned != null && planned[2] >= 1L && planned[0] == liveKey(level)
                    && now >= planned[1] && now - planned[1] < window) {
                // Same contraptions as last tick and every one already fed: only the motion samples.
                sample(level);
                return;
            }
            sync(level, system.getPipeline());
        } catch (RuntimeException ex) {
            logFailure(ex);
        }
    }

    /**
     * Drops colliders this mod added in one level. Used by the master switch and by turning the
     * feed off. Every claim in the level is restored, including a contraption that was displaced
     * before its collider was uploaded.
     */
    public static void disarm(ServerLevel level) {
        try {
            String dimension = level.dimension().location().toString();
            SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
            PhysicsPipeline pipeline = system == null ? null : system.getPipeline();
            Map<String, FedContraptionCollider> fed = FED.remove(dimension);
            if (fed != null) {
                for (Map.Entry<String, FedContraptionCollider> entry : fed.entrySet()) {
                    HOOKED.remove(entry.getKey());
                    FedContraptionCollider collider = entry.getValue();
                    if (pipeline != null && collider != null) {
                        try {
                            pipeline.remove(collider);
                        } catch (RuntimeException ex) {
                            logFailure(ex);
                        }
                    }
                }
            }
            restoreClaims(dimension);
            PENDING.values().removeIf(entity -> entity == null || entity.level() == level);
        } catch (RuntimeException ex) {
            logFailure(ex);
        }
    }

    /** Sub-levels plus kinematic contraptions currently in the level pipeline. */
    public static int pipelineBodies(ServerLevel level) {
        try {
            SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
            PhysicsPipeline pipeline = system == null ? null : system.getPipeline();
            if (pipeline == null) {
                return 0;
            }
            int count = 0;
            boolean found = false;
            Class<?> type = pipeline.getClass();
            while (type != null) {
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    String name = field.getName();
                    if (!name.equals("activeSubLevels") && !name.equals("activeContraptions")) {
                        continue;
                    }
                    field.setAccessible(true);
                    count += mapSize(field.get(pipeline));
                    found = true;
                }
                type = type.getSuperclass();
            }
            return found ? count : 0;
        } catch (ReflectiveOperationException ex) {
            return 0;
        }
    }

    private static int mapSize(Object value) throws ReflectiveOperationException {
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        if (value == null) {
            return 0;
        }
        Object result = value.getClass().getMethod("size").invoke(value);
        return result instanceof Integer count ? count : 0;
    }

    /** Drops a disassembled or removed contraption so the claim map does not keep it alive. */
    private static void pruneClaims(String dimension) {
        List<String> drop = new ArrayList<>();
        for (Map.Entry<String, AbstractContraptionEntity> entry : CLAIMS.entrySet()) {
            if (gone(entry.getValue(), dimension)) {
                drop.add(entry.getKey());
            }
        }
        for (String id : drop) {
            CLAIMS.remove(id);
            CLAIMED.remove(id);
        }
        PENDING.entrySet().removeIf(entry -> gone(entry.getValue(), dimension));
    }

    private static boolean gone(AbstractContraptionEntity entity, String dimension) {
        if (entity == null) {
            return true;
        }
        try {
            if (!(entity.level() instanceof ServerLevel server)
                    || !server.dimension().location().toString().equals(dimension)) {
                return false;
            }
            if (entity.isRemoved() || !entity.isAlive()) {
                return true;
            }
            Contraption contraption = entity.getContraption();
            return contraption == null || contraption.getBlocks().isEmpty();
        } catch (RuntimeException ex) {
            return true;
        }
    }

    private static void restoreClaims(String dimension) {
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, AbstractContraptionEntity> entry : CLAIMS.entrySet()) {
            AbstractContraptionEntity entity = entry.getValue();
            if (entity != null && entity.level() instanceof ServerLevel server
                    && server.dimension().location().toString().equals(dimension)) {
                ids.add(entry.getKey());
            }
        }
        for (String id : ids) {
            AbstractContraptionEntity entity = CLAIMS.remove(id);
            CLAIMED.remove(id);
            HOOKED.remove(id);
            restoreSable(entity);
        }
    }

    /**
     * Sable 2.0.3 adds its kinematic from {@code sable$contraptionInitialize} only while
     * {@code sable$initialized} is false. Claiming removes that body. Clearing the flag lets the
     * next contraption tick add it again. This mod does not displace it while the master switch is off.
     */
    public static void restoreSable(AbstractContraptionEntity entity) {
        if (entity == null) {
            return;
        }
        try {
            Field field = initializedField(entity.getClass());
            if (field == null) {
                SableSafeLanding.LOGGER.warn("LIVE restore sable collider {} missing init flag", entity.getUUID());
                return;
            }
            field.setAccessible(true);
            field.setBoolean(entity, false);
            SableSafeLanding.LOGGER.info("LIVE restore sable collider {} initialized=false", entity.getUUID());
        } catch (ReflectiveOperationException ex) {
            logFailure(new IllegalStateException(ex));
        }
    }

    /** Null when the mixin field is absent. True after Sable's next initialize. */
    public static Boolean sableInitialized(AbstractContraptionEntity entity) {
        if (entity == null) {
            return null;
        }
        try {
            Field field = initializedField(entity.getClass());
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            return field.getBoolean(entity);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private static Field initializedField(Class<?> type) {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                return cursor.getDeclaredField("sable$initialized");
            } catch (NoSuchFieldException ex) {
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }

    /** Restores every claim, uploaded or not, so a killed switch cannot keep suppressing Sable. */
    public static void releaseClaims() {
        for (AbstractContraptionEntity entity : CLAIMS.values()) {
            restoreSable(entity);
        }
        CLAIMS.clear();
        CLAIMED.clear();
        PENDING.clear();
        FAR_CHECKED_AT.clear();
    }

    private static void sync(ServerLevel level, PhysicsPipeline pipeline) {
        AntiClipConfig config = SafeLandingConfig.snapshot();
        String dimension = level.dimension().location().toString();
        pruneClaims(dimension);
        ColliderFeedPlanner planner = PLANNERS.computeIfAbsent(dimension, ignored -> new ColliderFeedPlanner());
        Map<String, FedContraptionCollider> fed = FED.computeIfAbsent(dimension, ignored -> new HashMap<>());
        Map<String, AbstractContraptionEntity> entities = new HashMap<>();
        List<FeedRequest> requests = new ArrayList<>();
        int seen = 0;
        long now = level.getGameTime();
        for (AbstractContraptionEntity entity : LiveContraptions.in(level)) {
            if (entity == null || entity.isRemoved() || entity.level() != level) {
                continue;
            }
            if (seen++ >= MAX_CONTRAPTIONS) {
                break;
            }
            Contraption contraption = entity.getContraption();
            String kind = ContraptionKinds.nameOf(contraption);
            if (kind == null || contraption == null) {
                continue;
            }
            String id = entity.getUUID().toString();
            long hash = shapeHash(contraption, now);
            Long failed = FAILED.get(id);
            if (failed != null && failed == hash) {
                // This shape could not be built before. Do not rebuild it every tick.
                continue;
            }
            FAILED.remove(id);
            entities.put(id, entity);
            boolean inWorld = Sable.HELPER.getContaining(entity) == null;
            requests.add(new FeedRequest(id, kind, hash, inWorld, entity.isAlive()));
        }
        boolean settled = true;
        boolean farWaiting = false;
        for (FeedAction action : planner.plan(requests, config.feedColliders())) {
            long lazyBefore = lazyWaits;
            apply(pipeline, planner, fed, entities, action);
            // A deck left on Sable's collider because no ship is near does not keep the
            // dimension re-planning every tick. It is rechecked on the next full plan.
            if (lazyWaits != lazyBefore) {
                farWaiting = true;
            } else if (action.type() != FeedAction.Type.KEEP) {
                settled = false;
            }
        }
        for (FeedRequest request : requests) {
            if (request.inWorld() && !fed.containsKey(request.id()) && !FAR_CHECKED_AT.containsKey(request.id())) {
                // Waiting for its claim or its collider: keep planning every tick.
                settled = false;
            }
        }
        PLANNED.put(dimension, new long[]{liveKey(level), now, !settled ? 0L : farWaiting ? 2L : 1L});
        sample(level);
    }

    /** Identity of the live contraption set: its size and the entities in it. */
    private static long liveKey(ServerLevel level) {
        java.util.Set<AbstractContraptionEntity> live = LiveContraptions.in(level);
        long key = live.size();
        for (AbstractContraptionEntity entity : live) {
            key += 0x9E3779B97F4A7C15L * System.identityHashCode(entity);
        }
        return key;
    }

    /** Once per game tick: each fed collider records where its contraption is. */
    private static void sample(ServerLevel level) {
        Map<String, FedContraptionCollider> fed = FED.get(level.dimension().location().toString());
        if (fed == null || fed.isEmpty()) {
            return;
        }
        long time = level.getGameTime();
        for (Map.Entry<String, FedContraptionCollider> entry : fed.entrySet()) {
            FedContraptionCollider collider = entry.getValue();
            collider.noteSample(time);
            org.joml.Vector3d delta = collider.anchorDelta(new org.joml.Vector3d());
            if (delta.lengthSquared() > 1.0e-4 && KINEMATIC_LOGGED.add(entry.getKey())) {
                SableSafeLanding.LOGGER.info(
                        "LIVE kinematic {} dAnchor=({},{},{}) dAngle={}",
                        entry.getKey(),
                        String.format(java.util.Locale.ROOT, "%.3f", delta.x),
                        String.format(java.util.Locale.ROOT, "%.3f", delta.y),
                        String.format(java.util.Locale.ROOT, "%.3f", delta.z),
                        String.format(java.util.Locale.ROOT, "%.2f", collider.angleDeltaDegrees())
                );
            }
        }
    }

    private static void apply(
            PhysicsPipeline pipeline,
            ColliderFeedPlanner planner,
            Map<String, FedContraptionCollider> fed,
            Map<String, AbstractContraptionEntity> entities,
            FeedAction action
    ) {
        switch (action.type()) {
            case ADD, REBUILD -> {
                AbstractContraptionEntity entity = entities.get(action.id());
                if (!CLAIMED.contains(action.id())) {
                    AbstractContraptionEntity offered = PENDING.get(action.id());
                    if (offered == null || offered != entity || entity == null) {
                        planner.forget(action.id());
                        if (entity != null && entity.tickCount > 0) {
                            warnDeferred();
                        }
                        return;
                    }
                    if (entity.level() instanceof ServerLevel nearLevel && !shipNearForTakeover(nearLevel, entity)) {
                        // No ship anywhere near: Sable's own collider is enough until one comes.
                        planner.forget(action.id());
                        lazyWaits++;
                        return;
                    }
                    if (!(entity.level() instanceof ServerLevel serverLevel) || !uploadFits(serverLevel, entity)) {
                        // Over budget: Sable's own collider stays on this deck until a later tick.
                        planner.forget(action.id());
                        deferredUploads++;
                        return;
                    }
                    if (!Boolean.TRUE.equals(sableInitialized(entity))) {
                        // Sable has not added its own body yet; try again next tick.
                        planner.forget(action.id());
                        return;
                    }
                    PENDING.remove(action.id());
                    claim(entity);
                    displaceSable(entity);
                }
                if (action.type() == FeedAction.Type.REBUILD) {
                    remove(pipeline, fed, action.id());
                }
                long buildStart = System.nanoTime();
                FedContraptionCollider collider = entity == null ? null : FedContraptionCollider.tryCreate(entity);
                if (collider == null || pipeline == null) {
                    planner.forget(action.id());
                    if (entity != null && collider == null) {
                        FAILED.put(action.id(), action.shapeHash());
                    }
                    return;
                }
                try {
                    pipeline.add(collider);
                    fed.put(action.id(), collider);
                    long used = System.nanoTime() - buildStart;
                    int blocks = entity.getContraption().getBlocks().size();
                    UPLOAD_COST.observe(Math.max(1, blocks), used);
                    uploads++;
                    if (entity.level() instanceof ServerLevel serverLevel) {
                        CarryRuntime.budget(serverLevel).charge(used);
                        UPLOADED_AT.put(serverLevel.dimension().location().toString(), serverLevel.getGameTime());
                    }
                    SableSafeLanding.LOGGER.info("LIVE feed collider {} blocks={} ms={}", action.id(), blocks,
                            String.format(java.util.Locale.ROOT, "%.2f", used / 1_000_000.0));
                } catch (RuntimeException ex) {
                    planner.forget(action.id());
                    logFailure(ex);
                }
            }
            case REMOVE -> remove(pipeline, fed, action.id());
            case KEEP -> {
            }
        }
    }

    /**
     * The contraption's shape hash. Recomputed when its block count changes, and otherwise at
     * most every {@link #REHASH_TICKS}, instead of every physics substep.
     */
    private static long shapeHash(Contraption contraption, long now) {
        // A Create contraption's blocks are fixed while it is assembled, and a re-assembly is a new
        // Contraption object. Identity and block count find every change without reading a block,
        // so there is no per-block rehash and no once-a-minute recheck spike.
        return System.identityHashCode(contraption) * 0x9E3779B97F4A7C15L + contraption.getBlocks().size();
    }

    /** Blocks around a deck within which a ship makes the mod take the deck's collider over. */
    static final double TAKEOVER_REACH = 48.0;
    /** Seconds of a ship's own speed added to that reach. */
    static final double TAKEOVER_SECONDS = 2.0;
    /** Ticks between rechecks of a deck that had no ship near. */
    static final long TAKEOVER_RECHECK = 10;
    private static final Map<String, Long> FAR_CHECKED_AT = new HashMap<>();
    private static long lazyWaits;

    /** Decks left on Sable's own collider for a tick because no ship was near. */
    public static long lazyWaits() {
        return lazyWaits;
    }

    /**
     * With idleSleep on, a deck's collider is only taken over while a ship is within
     * {@link #TAKEOVER_REACH} blocks plus {@link #TAKEOVER_SECONDS} of its speed. A deck that
     * nobody is near keeps Sable's own collider, so loading or assembling far-away contraptions
     * does not pay for a second collider build. A far deck is rechecked every
     * {@link #TAKEOVER_RECHECK} ticks.
     */
    private static boolean shipNearForTakeover(ServerLevel level, AbstractContraptionEntity entity) {
        if (!SafeLandingConfig.idleSleep()) {
            return true;
        }
        String key = entity.getUUID().toString();
        long now = level.getGameTime();
        Long checked = FAR_CHECKED_AT.get(key);
        if (checked != null && now >= checked && now - checked < TAKEOVER_RECHECK) {
            return false;
        }
        Aabb bounds = CarryRuntime.broadphaseBounds(entity);
        if (DeckGate.anyShipWithin(level, bounds, TAKEOVER_REACH, TAKEOVER_SECONDS)) {
            FAR_CHECKED_AT.remove(key);
            return true;
        }
        if (FAR_CHECKED_AT.size() > 4096) {
            FAR_CHECKED_AT.clear();
        }
        FAR_CHECKED_AT.put(key, now);
        return false;
    }

    /**
     * One upload per dimension per tick always goes through, so a deck is never stuck on
     * Sable's collider. More in the same tick only when the learned cost fits the budget.
     */
    private static boolean uploadFits(ServerLevel level, AbstractContraptionEntity entity) {
        String dimension = level.dimension().location().toString();
        Long last = UPLOADED_AT.get(dimension);
        if (last == null || last != level.getGameTime()) {
            return true;
        }
        int blocks = entity.getContraption() == null ? 1 : entity.getContraption().getBlocks().size();
        com.vucelich.sablesafelanding.sim.TickBudget budget = CarryRuntime.budget(level);
        boolean fits = budget.allowsBulk(UPLOAD_COST.estimate(Math.max(1, blocks)));
        if (!fits) {
            budget.defer();
            UPLOAD_COST.relax();
        }
        return fits;
    }

    private static void remove(PhysicsPipeline pipeline, Map<String, FedContraptionCollider> fed, String id) {
        FedContraptionCollider collider = fed.remove(id);
        if (collider != null && pipeline != null) {
            pipeline.remove(collider);
        }
    }

    private static void warnDeferred() {
        if (deferredWarning) {
            return;
        }
        deferredWarning = true;
        SableSafeLanding.LOGGER.warn(
                "Sable contraption hook has not claimed this assembly; leaving it on Sable's collider path"
        );
    }

    private static void logFailure(RuntimeException ex) {
        if (!FAILURE_LOGS.tryAcquire(System.nanoTime())) {
            return;
        }
        SableSafeLanding.LOGGER.warn("Contraption collider feed failed: {}", ex.toString());
    }
}
