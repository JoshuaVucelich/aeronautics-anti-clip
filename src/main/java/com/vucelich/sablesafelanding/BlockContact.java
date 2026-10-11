package com.vucelich.sablesafelanding;

import com.vucelich.sablesafelanding.sim.CarryGate;
import com.vucelich.sablesafelanding.sim.DeckContact;
import com.vucelich.sablesafelanding.sim.SupportMotion;
import com.vucelich.sablesafelanding.sim.Vec3;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Vector3d;

import java.util.ArrayList;
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

    /**
     * Resting contact for the carry lock. Bottoms are scanned from the lowest block upward so a
     * hull floor is inside the {@link #MAX_BLOCKS} cap. Hitting the cap without a contact is
     * inconclusive: the caller must not treat that as "no contact".
     */
    public record Touch(CarryGate.Contact contact, String supportId) {
    }

    public static Touch touch(ServerSubLevel subLevel, List<SupportMotion> supports, double epsilon) {
        if (supports.isEmpty() || subLevel.getPlot() == null) {
            return new Touch(CarryGate.Contact.NONE, null);
        }
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        List<PlotChunkHolder> holders = new ArrayList<>();
        for (PlotChunkHolder holder : subLevel.getPlot().getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            BoundingBox3ic local = holder.getBoundingBox();
            if (chunk == null || local == null || local.volume() <= 0) {
                continue;
            }
            holders.add(holder);
            minY = Math.min(minY, local.minY());
            maxY = Math.max(maxY, local.maxY());
        }
        if (holders.isEmpty()) {
            return new Touch(CarryGate.Contact.NONE, null);
        }
        int seen = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = minY; y <= maxY; y++) {
            for (PlotChunkHolder holder : holders) {
                BoundingBox3ic local = holder.getBoundingBox();
                if (local == null || y < local.minY() || y > local.maxY()) {
                    continue;
                }
                LevelChunk chunk = holder.getChunk();
                if (chunk == null) {
                    continue;
                }
                ChunkPos pos = chunk.getPos();
                for (int x = local.minX(); x <= local.maxX(); x++) {
                    for (int z = local.minZ(); z <= local.maxZ(); z++) {
                        cursor.set(pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z);
                        if (chunk.getBlockState(cursor).isAir()) {
                            continue;
                        }
                        seen++;
                        Vector3d world = subLevel.logicalPose().transformPosition(
                                new Vector3d(cursor.getX() + 0.5, cursor.getY(), cursor.getZ() + 0.5)
                        );
                        Vec3 bottom = new Vec3(world.x, world.y, world.z);
                        for (SupportMotion support : supports) {
                            if (DeckContact.rests(bottom, support.volume(), epsilon)) {
                                return new Touch(CarryGate.Contact.RESTING, support.id());
                            }
                        }
                        if (seen >= MAX_BLOCKS) {
                            return new Touch(CarryGate.Contact.INCONCLUSIVE, null);
                        }
                    }
                }
            }
        }
        return new Touch(CarryGate.Contact.NONE, null);
    }
}
