package com.vucelich.sablesafelanding;

import com.vucelich.sablesafelanding.sim.SupportMotion;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Vector3d;

import java.util.List;

/**
 * True when a non-air block of the sub-level overlaps a contraption block volume.
 * Sable 2.0.3 does not expose Rapier contact manifolds, so this is the contact check.
 */
public final class BlockContact {
    static final int MAX_BLOCKS = 128;

    private BlockContact() {
    }

    public static boolean overlaps(ServerSubLevel subLevel, List<SupportMotion> supports) {
        if (supports.isEmpty() || subLevel.getPlot() == null) {
            return false;
        }
        int seen = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (PlotChunkHolder holder : subLevel.getPlot().getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            BoundingBox3ic local = holder.getBoundingBox();
            if (chunk == null || local == null || local.volume() <= 0) {
                continue;
            }
            ChunkPos pos = chunk.getPos();
            for (int y = local.minY(); y <= local.maxY() && seen < MAX_BLOCKS; y++) {
                for (int x = local.minX(); x <= local.maxX() && seen < MAX_BLOCKS; x++) {
                    for (int z = local.minZ(); z <= local.maxZ() && seen < MAX_BLOCKS; z++) {
                        cursor.set(pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z);
                        BlockState state = chunk.getBlockState(cursor);
                        if (state.isAir()) {
                            continue;
                        }
                        seen++;
                        Vector3d world = subLevel.logicalPose().transformPosition(
                                new Vector3d(cursor.getX() + 0.5, cursor.getY() + 0.5, cursor.getZ() + 0.5)
                        );
                        for (SupportMotion support : supports) {
                            if (support.volume().contains(world.x, world.y, world.z)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }
}
