package com.vucelich.sablesafelanding;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
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

    private ColliderFeedRuntime() {
    }

    /**
     * The contraption mixin ran for this entity. Server thread only.
     */
    public static void noteHook(AbstractContraptionEntity entity) {
        if (onServerThread(entity)) {
            HOOKED.add(entity.getUUID().toString());
        }
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
        return !HOOKED.isEmpty();
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
    }

    public static void forgetDimension(String dimension) {
        PLANNERS.remove(dimension);
        SYNCED_AT.remove(dimension);
        PLANNED.remove(dimension);
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
            if (planned != null && planned[2] == 1L && planned[0] == liveKey(level)
                    && now >= planned[1] && now - planned[1] < REPLAN_TICKS) {
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
        for (FeedAction action : planner.plan(requests, config.feedColliders())) {
            if (action.type() != FeedAction.Type.KEEP) {
                settled = false;
            }
            apply(pipeline, planner, fed, entities, action);
        }
        for (FeedRequest request : requests) {
            if (request.inWorld() && !fed.containsKey(request.id())) {
                // Waiting for its claim or its collider: keep planning every tick.
                settled = false;
            }
        }
        PLANNED.put(dimension, new long[]{liveKey(level), now, settled ? 1L : 0L});
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
                    planner.forget(action.id());
                    if (entity != null && entity.tickCount > 0) {
                        warnDeferred();
                    }
                    return;
                }
                if (action.type() == FeedAction.Type.REBUILD) {
                    remove(pipeline, fed, action.id());
                }
                long buildStart = System.nanoTime();
                FedContraptionCollider collider = entity == null ? null : FedContraptionCollider.tryCreate(entity);
                if (collider != null) {
                    SableSafeLanding.LOGGER.info("LIVE feed collider {} blocks={} ms={}", action.id(),
                            entity.getContraption().getBlocks().size(),
                            String.format(java.util.Locale.ROOT, "%.2f", (System.nanoTime() - buildStart) / 1_000_000.0));
                }
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
        int size = contraption.getBlocks().size();
        long[] cached = HASHES.get(contraption);
        if (cached != null && cached[0] == size && now - cached[2] < REHASH_TICKS && now >= cached[2]) {
            return cached[1];
        }
        long hash = ContraptionKinds.shapeHash(contraption);
        HASHES.put(contraption, new long[]{size, hash, now});
        return hash;
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
