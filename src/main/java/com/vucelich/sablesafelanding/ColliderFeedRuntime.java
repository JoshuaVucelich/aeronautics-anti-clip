package com.vucelich.sablesafelanding;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.vucelich.sablesafelanding.sim.AntiClipConfig;
import com.vucelich.sablesafelanding.sim.ColliderFeedPlanner;
import com.vucelich.sablesafelanding.sim.FeedAction;
import com.vucelich.sablesafelanding.sim.FeedRequest;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

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
    private static long lastFailureLog;
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
     * Sable's initializer was cancelled, so this mod may upload the collider.
     */
    public static void claim(AbstractContraptionEntity entity) {
        if (onServerThread(entity)) {
            CLAIMED.add(entity.getUUID().toString());
        }
    }

    public static boolean hookSeen() {
        return !HOOKED.isEmpty();
    }

    public static void clear() {
        PLANNERS.clear();
        FED.clear();
        HOOKED.clear();
        CLAIMED.clear();
    }

    private static boolean onServerThread(AbstractContraptionEntity entity) {
        return entity.level() instanceof ServerLevel server && server.getServer().isSameThread();
    }

    public static boolean shouldOwn(AbstractContraptionEntity entity) {
        try {
            if (!(entity.level() instanceof ServerLevel)) {
                return false;
            }
            if (!SafeLandingConfig.snapshot().feedColliders()) {
                return false;
            }
            Contraption contraption = entity.getContraption();
            if (ContraptionKinds.nameOf(contraption) == null) {
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
            sync(level, system.getPipeline());
        } catch (RuntimeException ex) {
            logFailure(ex);
        }
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
        long now = System.nanoTime();
        if (now - lastFailureLog < 5_000_000_000L) {
            return;
        }
        lastFailureLog = now;
        SableSafeLanding.LOGGER.warn("Contraption collider feed failed: {}", ex.toString());
    }
}
