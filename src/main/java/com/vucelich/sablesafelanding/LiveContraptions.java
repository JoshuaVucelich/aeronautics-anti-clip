package com.vucelich.sablesafelanding;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Contraption entities loaded in each level, on the server and on the client. The wheel ground
 * check reads this instead of scanning entities, so a level with no contraption costs one map
 * read per wheel ray. Bounds for the rest are cached once per tick by {@link WheelContactRuntime}. Join and leave events keep it current.
 */
public final class LiveContraptions {
    private static final Map<Level, Set<AbstractContraptionEntity>> LIVE = new ConcurrentHashMap<>();
    /** Bumped on every join and leave, so a cached deck list knows when to rebuild. */
    private static final java.util.concurrent.atomic.AtomicInteger VERSION = new java.util.concurrent.atomic.AtomicInteger();

    private LiveContraptions() {
    }

    public static Set<AbstractContraptionEntity> in(Level level) {
        if (level == null) {
            return Collections.emptySet();
        }
        Set<AbstractContraptionEntity> live = LIVE.get(level);
        return live == null ? Collections.emptySet() : live;
    }

    /** Changes whenever any level's contraption set changes. */
    public static int version() {
        return VERSION.get();
    }

    public static int count(Level level) {
        return in(level).size();
    }

    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof AbstractContraptionEntity entity && event.getLevel() != null) {
            LIVE.computeIfAbsent(event.getLevel(), ignored -> ConcurrentHashMap.newKeySet()).add(entity);
            VERSION.incrementAndGet();
        }
    }

    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof AbstractContraptionEntity entity) || event.getLevel() == null) {
            return;
        }
        Set<AbstractContraptionEntity> live = LIVE.get(event.getLevel());
        if (live != null) {
            live.remove(entity);
        }
        VERSION.incrementAndGet();
        if (!event.getLevel().isClientSide()) {
            DeckShapes.forget(entity.getUUID());
        }
    }

    public static void onUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level) {
            LIVE.remove(level);
            VERSION.incrementAndGet();
            Decks.forget(level);
            WheelContactRuntime.forget(level);
        }
    }

    public static void clear() {
        LIVE.clear();
        VERSION.incrementAndGet();
    }
}
