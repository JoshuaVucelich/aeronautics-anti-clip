package com.vucelich.sablesafelanding;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import dev.ryanhcode.sable.api.physics.mass.MassTracker;
import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.physics.floating_block.FloatingClusterContainer;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
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
 * Rapier sets kinematic velocity from {@code position(partial) - position(partial - 1)}. Create copies
 * the previous anchor onto the current one at the start of the entity tick, after the block entity has
 * already moved, so that difference is zero and a descending platform never pulls. Samples taken once
 * per game tick restore the real per-tick motion. The angle used for that motion is capped so a wrapped
 * bearing reading cannot spin the collider through a full turn in one tick.
 */
public final class FedContraptionCollider implements KinematicContraption {
    private static final float MAX_DEGREES_PER_TICK = 25.0f;

    private final AbstractContraptionEntity entity;
    private final BoundingBox3i localBounds;
    private final MassTracker massTracker;
    private final Vector3d cachedPosition = new Vector3d();
    private final Map<BlockPos, BlockSubLevelLiftProvider.LiftProviderContext> liftProviders = new HashMap<>();
    private final FloatingClusterContainer floatingClusters = new FloatingClusterContainer();
    private final Vector3d fromAnchor = new Vector3d();
    private final Vector3d toAnchor = new Vector3d();
    private long sampledAt = Long.MIN_VALUE;
    private boolean sampled;
    private float fromAngle;
    private float toAngle;

    public AbstractContraptionEntity entity() {
        return entity;
    }

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

    /**
     * Record the anchor once per game tick. Later substeps in the same tick keep the delta so
     * every substep reports the same kinematic velocity.
     */
    public void noteSample(long gameTime) {
        if (sampled && gameTime == sampledAt) {
            return;
        }
        Vec3 anchor = entity.getAnchorVec();
        float angle = readAngle();
        if (!sampled) {
            fromAnchor.set(anchor.x, anchor.y, anchor.z);
            toAnchor.set(anchor.x, anchor.y, anchor.z);
            fromAngle = angle;
            toAngle = angle;
            sampled = true;
        } else {
            fromAnchor.set(toAnchor);
            toAnchor.set(anchor.x, anchor.y, anchor.z);
            fromAngle = toAngle;
            toAngle = angle;
        }
        sampledAt = gameTime;
    }

    /** Blocks the anchor moved since the previous game tick. Zero before the second sample. */
    public Vector3d anchorDelta(Vector3d dest) {
        return dest.set(toAnchor).sub(fromAnchor);
    }

    public float angleDeltaDegrees() {
        if (!sampled) {
            return 0.0f;
        }
        return Mth.clamp(Mth.wrapDegrees(toAngle - fromAngle), -MAX_DEGREES_PER_TICK, MAX_DEGREES_PER_TICK);
    }

    @Override
    public Vector3dc sable$getPosition(double partialTick) {
        Vec3 local = JOMLConversion.toMojang(massTracker.getCenterOfMass());
        Vec3 anchor = sampled
                ? new Vec3(
                        lerp(fromAnchor.x, toAnchor.x, partialTick),
                        lerp(fromAnchor.y, toAnchor.y, partialTick),
                        lerp(fromAnchor.z, toAnchor.z, partialTick))
                : entity.getPrevAnchorVec().lerp(entity.getAnchorVec(), partialTick);
        Vec3 rotationOffset = VecHelper.getCenterOf(BlockPos.ZERO);
        local = local.subtract(rotationOffset);
        local = rotate(local, partialTick);
        local = local.add(rotationOffset).add(anchor);
        return JOMLConversion.toJOML(local, cachedPosition);
    }

    @Override
    public Quaterniond sable$getOrientation(double partialTick) {
        Matrix3d matrix = new Matrix3d();
        Vector3d column = new Vector3d();
        for (int i = 0; i < 3; i++) {
            matrix.getColumn(i, column);
            Vec3 transformed = rotate(JOMLConversion.toMojang(column), partialTick);
            matrix.setColumn(i, transformed.x, transformed.y, transformed.z);
        }
        return matrix.getNormalizedRotation(new Quaterniond());
    }

    private Vec3 rotate(Vec3 local, double partialTick) {
        if (!(entity instanceof ControlledContraptionEntity controlled) || controlled.getRotationAxis() == null) {
            return local;
        }
        float delta = sampled ? angleDeltaDegrees() : 0.0f;
        float angle = (sampled ? fromAngle : readAngle()) + delta * (float) partialTick;
        return VecHelper.rotate(local, angle, controlled.getRotationAxis());
    }

    private float readAngle() {
        if (entity instanceof ControlledContraptionEntity controlled && controlled.getRotationAxis() != null) {
            return controlled.getAngle(1.0f);
        }
        return 0.0f;
    }

    private static double lerp(double from, double to, double partial) {
        return from + (to - from) * partial;
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
