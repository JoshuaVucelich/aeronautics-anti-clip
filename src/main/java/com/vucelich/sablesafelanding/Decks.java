package com.vucelich.sablesafelanding;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import dev.ryanhcode.sable.Sable;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The contraptions in a level that can carry a ship: world elevators, bearings, pistons, and
 * gantries, not ones inside a sub-level, and not trains, minecarts, or other contraptions.
 * Rebuilt only when a contraption joins or leaves a level, and at most every
 * {@link #REFRESH_TICKS} otherwise, so reading it is one map lookup. Works on both sides.
 */
public final class Decks {
    /** Rebuild at least this often, in case a contraption was still loading when it joined. */
    private static final long REFRESH_TICKS = 100;

    /** {@code loading}: some contraption had no blocks yet, so the list is rebuilt next tick. */
    private record Cache(int version, long builtAt, boolean loading, List<AbstractContraptionEntity> decks) {
    }

    private static final Map<Level, Cache> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private Decks() {
    }

    public static List<AbstractContraptionEntity> of(Level level) {
        if (level == null) {
            return List.of();
        }
        int version = LiveContraptions.version();
        long now = level.getGameTime();
        Cache cached = CACHE.get(level);
        if (cached != null && cached.version == version && now >= cached.builtAt && now - cached.builtAt < (cached.loading ? 1 : REFRESH_TICKS)) {
            return cached.decks;
        }
        List<AbstractContraptionEntity> decks = new ArrayList<>();
        boolean loading = false;
        for (AbstractContraptionEntity entity : LiveContraptions.in(level)) {
            if (decks.size() >= ColliderFeedRuntime.MAX_CONTRAPTIONS) {
                break;
            }
            if (entity == null || entity.isRemoved() || entity.level() != level) {
                continue;
            }
            Contraption contraption = entity.getContraption();
            if (contraption == null) {
                // Joined before its blocks were read. Look again next tick, not in 100.
                loading = true;
                continue;
            }
            if (ContraptionKinds.nameOf(contraption) == null) {
                continue;
            }
            try {
                if (Sable.HELPER.getContaining(entity) != null) {
                    continue;
                }
            } catch (RuntimeException ex) {
                continue;
            }
            decks.add(entity);
        }
        List<AbstractContraptionEntity> result = decks.isEmpty() ? List.of() : List.copyOf(decks);
        CACHE.put(level, new Cache(version, now, loading, result));
        return result;
    }

    public static void forget(Level level) {
        CACHE.remove(level);
    }

    public static void clear() {
        CACHE.clear();
    }
}
