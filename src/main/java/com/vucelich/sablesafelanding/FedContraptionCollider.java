package com.vucelich.sablesafelanding;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import dev.ryanhcode.sable.api.physics.mass.MassTracker;
import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.physics.floating_block.FloatingClusterContainer;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.HashMap;
import java.util.Map;

/**
 * Kinematic body uploaded instead of Sable's own adapter for a world elevator, bearing, piston, or gantry.
 * Pose math matches Sable's contraption mixin so the voxels sit on the lerped anchor.
 */
public final class FedContraptionCollider implements KinematicContraption {
    private final AbstractContraptionEntity entity;
    private final BoundingBox3i localBounds;
    private final MassTracker massTracker;
    private final Vector3d cachedPosition = new Vector3d();
    private final Map<BlockPos, BlockSubLevelLiftProvider.LiftProviderContext> liftProviders = new HashMap<>();
    private final FloatingClusterContainer floatingClusters = new FloatingClusterContainer();

    private FedContraptionCollider(AbstractContraptionEntity entity, BoundingBox3i localBounds, MassTracker massTracker) {
        this.entity = entity;
        this.localBounds = localBounds;
        this.massTracker = massTracker;
    }

    public static FedContraptionCollider tryCreate(AbstractContraptionEntity entity) {
        if (entity.getContraption() == null || entity.getContraption().getBlocks().isEmpty()) {
            return null;
        }
        BoundingBox3i bounds = null;
        for (BlockPos pos : entity.getContraption().getBlocks().keySet()) {
            if (bounds == null) {
                bounds = new BoundingBox3i(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
            } else {
                bounds.expandTo(pos.getX(), pos.getY(), pos.getZ());
            }
        }
        if (bounds == null) {
            return null;
        }
        MassTracker mass = MassTracker.build(entity.getContraption().getContraptionWorld(), bounds);
        if (mass.getCenterOfMass() == null) {
            return null;
        }
        return new FedContraptionCollider(entity, bounds, mass);
    }

    @Override
    public void sable$getLocalBounds(BoundingBox3i bounds) {
        bounds.set(localBounds);
    }

    @Override
    public BlockGetter sable$blockGetter() {
        return entity.getContraption().getContraptionWorld();
    }

    @Override
    public MassTracker sable$getMassTracker() {
        return massTracker;
    }

    @Override
    public Vector3dc sable$getPosition(double partialTick) {
        Vec3 local = JOMLConversion.toMojang(massTracker.getCenterOfMass());
        Vec3 anchor = entity.getPrevAnchorVec().lerp(entity.getAnchorVec(), partialTick);
        Vec3 rotationOffset = VecHelper.getCenterOf(BlockPos.ZERO);
        local = local.subtract(rotationOffset);
        local = entity.applyRotation(local, (float) partialTick);
        local = local.add(rotationOffset).add(anchor);
        return JOMLConversion.toJOML(local, cachedPosition);
    }

    @Override
    public Quaterniond sable$getOrientation(double partialTick) {
        Matrix3d matrix = new Matrix3d();
        Vector3d column = new Vector3d();
        for (int i = 0; i < 3; i++) {
            matrix.getColumn(i, column);
            Vec3 transformed = entity.applyRotation(JOMLConversion.toMojang(column), (float) partialTick);
            matrix.setColumn(i, transformed.x, transformed.y, transformed.z);
        }
        return matrix.getNormalizedRotation(new Quaterniond());
    }

    @Override
    public Map<BlockPos, BlockSubLevelLiftProvider.LiftProviderContext> sable$liftProviders() {
        return liftProviders;
    }

    @Override
    public FloatingClusterContainer sable$getFloatingClusterContainer() {
        return floatingClusters;
    }

    @Override
    public boolean sable$shouldCollide() {
        return true;
    }

    @Override
    public boolean sable$isValid() {
        return entity.isAlive() && !entity.isRemoved();
    }
}
