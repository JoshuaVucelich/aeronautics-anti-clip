package com.vucelich.sablesafelanding;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.vucelich.sablesafelanding.sim.DeckIndex;
import com.vucelich.sablesafelanding.sim.TickBudget;
import net.minecraft.core.BlockPos;

import java.util.ConcurrentModificationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Per-contraption index of block cells in the contraption's own frame, server thread only.
 * Built once per contraption shape, a slice per tick inside the tick budget, so a contraption
 * with a hundred thousand blocks never costs more than the budget in one tick. It is only built
 * for a contraption a ship is actually on or next to. Until it is ready the carry treats that
 * deck as unknown and leaves the ship to Sable's own collider, which is what happens with the
 * mod off.
 */
public final class DeckShapes {
    /** Blocks added between budget checks. */
    private static final int SLICE = 256;

    private static final Map<UUID, Build> BUILDS = new ConcurrentHashMap<>();
    /** Learned cost of adding one block to an index. */
    private static final com.vucelich.sablesafelanding.sim.CostEstimate SLICE_COST =
            new com.vucelich.sablesafelanding.sim.CostEstimate(60.0, 2.0, 64);
    private static long deferredBuilds;

    /** Index builds that stopped for the tick because the next slice would not fit, so they go on next tick. */
    public static long deferredBuilds() {
        return deferredBuilds;
    }

    private DeckShapes() {
    }

    private static final class Build {
        private final Contraption contraption;
        private final int expected;
        private DeckIndex index = new DeckIndex();
        private Iterator<BlockPos> cursor;
        private DeckIndex ready;
        private long spentNanos;
        private int ticks;

        private Build(Contraption contraption, Map<BlockPos, ?> blocks) {
            this.contraption = contraption;
            this.expected = blocks.size();
            this.cursor = blocks.keySet().iterator();
        }
    }

    /**
     * The ready index for this contraption, or null while it is still being built or the
     * budget is used up. Each call adds at most the budget's remaining time.
     */
    public static DeckIndex index(AbstractContraptionEntity entity, TickBudget budget) {
        Contraption contraption = entity.getContraption();
        if (contraption == null) {
            return null;
        }
        Map<BlockPos, ?> blocks = contraption.getBlocks();
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        UUID id = entity.getUUID();
        Build build = BUILDS.get(id);
        if (build == null || build.contraption != contraption
                || (build.ready != null && build.ready.size() != blocks.size())) {
            build = new Build(contraption, blocks);
            BUILDS.put(id, build);
        }
        if (build.ready != null) {
            return build.ready;
        }
        long start = System.nanoTime();
        long began = start;
        build.ticks++;
        try {
            while (budget.allowsBulk(SLICE_COST.estimate(Math.min(SLICE, Math.max(1, build.expected - build.index.size()))))) {
                int added = 0;
                while (added < SLICE && build.cursor.hasNext()) {
                    BlockPos pos = build.cursor.next();
                    build.index.add(pos.getX(), pos.getY(), pos.getZ());
                    added++;
                }
                long now = System.nanoTime();
                budget.charge(now - start);
                SLICE_COST.observe(added, now - start);
                start = now;
                if (!build.cursor.hasNext()) {
                    if (build.index.size() != build.expected || blocks.size() != build.expected) {
                        BUILDS.remove(id);
                        return null;
                    }
                    build.ready = build.index.seal();
                    build.index = null;
                    build.cursor = null;
                    build.spentNanos += System.nanoTime() - began;
                    SableSafeLanding.LOGGER.info("LIVE deck index {} blocks={} sections={} ticks={} ms={}",
                            id, build.ready.size(), build.ready.sectionCount(), build.ticks,
                            String.format(java.util.Locale.ROOT, "%.2f", build.spentNanos / 1_000_000.0));
                    return build.ready;
                }
            }
            build.spentNanos += System.nanoTime() - began;
            deferredBuilds++;
            budget.defer();
            SLICE_COST.relax();
            return null;
        } catch (ConcurrentModificationException ex) {
            // The block map changed during a slice. Start over next tick.
            BUILDS.remove(id);
            budget.charge(System.nanoTime() - start);
            return null;
        }
    }

    /** True when the index for this contraption is ready. Does no work. */
    public static boolean ready(AbstractContraptionEntity entity) {
        Build build = BUILDS.get(entity.getUUID());
        return build != null && build.ready != null && build.contraption == entity.getContraption();
    }

    public static void forget(UUID id) {
        BUILDS.remove(id);
    }

    public static int count() {
        return BUILDS.size();
    }

    public static void clear() {
        BUILDS.clear();
    }
}
