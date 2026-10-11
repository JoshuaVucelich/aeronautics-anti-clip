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
    private static final Set<String> KINEMATIC_LOGGED = new HashSet<>();
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
            CLAIMED.add(entity.getUUID().toString());
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
        KINEMATIC_LOGGED.clear();
    }

    public static void forgetDimension(String dimension) {
        PLANNERS.remove(dimension);
        Map<String, FedContraptionCollider> fed = FED.remove(dimension);
        if (fed != null) {
            HOOKED.removeAll(fed.keySet());
            CLAIMED.removeAll(fed.keySet());
        }
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
        SubLevelPhysicsSystem system = event.getPhysicsSystem();
        if (system == null || !(system.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!level.getServer().isSameThread()) {
            SableSafeLanding.LOGGER.warn("Ignoring contraption feed off the server thread");
            return;
        }
        try {
            if (!SafeLandingConfig.snapshot().enabled()) {
                disarm(level);
                return;
            }
            sync(level, system.getPipeline());
        } catch (RuntimeException ex) {
            logFailure(ex);
        }
    }

    /**
     * Drops colliders this mod added in one level. Used by the master switch.
     * Claims for those ids are released so Sable's own collider is no longer suppressed.
     */
    public static void disarm(ServerLevel level) {
        try {
            String dimension = level.dimension().location().toString();
            SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
            PhysicsPipeline pipeline = system == null ? null : system.getPipeline();
            Map<String, FedContraptionCollider> fed = FED.remove(dimension);
            if (fed == null) {
                return;
            }
            for (Map.Entry<String, FedContraptionCollider> entry : fed.entrySet()) {
                CLAIMED.remove(entry.getKey());
                HOOKED.remove(entry.getKey());
                FedContraptionCollider collider = entry.getValue();
                if (pipeline != null && collider != null) {
                    try {
                        pipeline.remove(collider);
                    } catch (RuntimeException ex) {
                        logFailure(ex);
                    }
                }
                if (collider != null) {
                    restoreSable(collider.entity());
                }
            }
        } catch (RuntimeException ex) {
            logFailure(ex);
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

    /** Forgets every claim so a killed switch cannot keep suppressing Sable. */
    public static void releaseClaims() {
        CLAIMED.clear();
    }

    private static void sync(ServerLevel level, PhysicsPipeline pipeline) {
        AntiClipConfig config = SafeLandingConfig.snapshot();
        String dimension = level.dimension().location().toString();
        ColliderFeedPlanner planner = PLANNERS.computeIfAbsent(dimension, ignored -> new ColliderFeedPlanner());
        Map<String, FedContraptionCollider> fed = FED.computeIfAbsent(dimension, ignored -> new HashMap<>());
        Map<String, AbstractContraptionEntity> entities = new HashMap<>();
        List<FeedRequest> requests = new ArrayList<>();
        int seen = 0;
        for (Entity candidate : level.getAllEntities()) {
            if (!(candidate instanceof AbstractContraptionEntity entity)) {
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
            entities.put(id, entity);
            boolean inWorld = Sable.HELPER.getContaining(entity) == null;
            requests.add(new FeedRequest(id, kind, ContraptionKinds.shapeHash(contraption), inWorld, entity.isAlive()));
        }
        for (FeedAction action : planner.plan(requests, config.feedColliders())) {
            apply(pipeline, planner, fed, entities, action);
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
                FedContraptionCollider collider = entity == null ? null : FedContraptionCollider.tryCreate(entity);
                if (collider == null || pipeline == null) {
                    planner.forget(action.id());
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
