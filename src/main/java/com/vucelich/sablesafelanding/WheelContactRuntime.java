package com.vucelich.sablesafelanding;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.vucelich.sablesafelanding.sim.Aabb;
import com.vucelich.sablesafelanding.sim.RateLimiter;
import com.vucelich.sablesafelanding.sim.WheelContact;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.LevelPoseProviderExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lets an Offroad wheel mount's suspension ray land on a moving Create contraption.
 *
 * <p>The wheel mount finds the ground with {@code Level.clip}. Sable's clip covers world blocks
 * and sub-levels, but a contraption's blocks live inside the contraption, so a wheel over an
 * elevator deck casts straight through it. The body still rests on the deck because the collider
 * feed puts the deck in Rapier. Without ground under the wheels there is no spring force and no
 * drive force, so the car sinks onto its hull and cannot drive on or off.
 *
 * <p>This casts the same ray through nearby contraptions and returns the deck hit when it is
 * closer than what Sable found. The hit is in world space with a world face, so the wheel mount
 * reads it the same way it reads a world block.
 */
public final class WheelContactRuntime {
    private static final RateLimiter FAILURE_LOGS = new RateLimiter(5_000_000_000L);
    private static final AtomicLong DECK_HITS = new AtomicLong();
    /** Server only: the deck each wheeled ship last stood on, and the game tick it was seen. */
    private static final Map<UUID, WheelTouch> WHEEL_TOUCH = new ConcurrentHashMap<>();
    /** A touch this many ticks old still counts, so physics and the carry tick can be out of step by one. */
    private static final long TOUCH_TICKS = 2;

    private record WheelTouch(String contraptionId, long gameTime) {
    }

    private WheelContactRuntime() {
    }

    /** True when the config switch is on. A config that is not loaded yet counts as on. */
    public static boolean switchOn() {
        try {
            return SafeLandingConfig.WHEEL_CONTACT.getAsBoolean();
        } catch (RuntimeException ex) {
            return true;
        }
    }

    /** Wheel rays that landed on a contraption deck since startup. Shown by /anticlip status. */
    public static long deckHits() {
        return DECK_HITS.get();
    }

    /**
     * The contraption id a ship's wheels stood on within the last couple of ticks, or null.
     * The carry uses this as contact: a car on its wheels does not touch the deck with its hull.
     */
    public static String wheelSupport(UUID shipId, long gameTime) {
        WheelTouch touch = WHEEL_TOUCH.get(shipId);
        if (touch == null) {
            return null;
        }
        if (gameTime - touch.gameTime > TOUCH_TICKS || touch.gameTime > gameTime) {
            WHEEL_TOUCH.remove(shipId, touch);
            return null;
        }
        return touch.contraptionId;
    }

    /** Drops every wheel touch. Called on server stop. */
    public static void clear() {
        WHEEL_TOUCH.clear();
    }

    /**
     * Called from the wheel mount's ground ray with the result Sable already computed.
     * Returns {@code base} unless a contraption deck is strictly closer along the same ray.
     */
    public static BlockHitResult withDecks(Level level, ClipContext context, BlockHitResult base, Object wheelMount) {
        if (level == null || context == null) {
            return base;
        }
        Set<AbstractContraptionEntity> live = LiveContraptions.in(level);
        if (!WheelContact.scan(SafeLandingConfig.running(), switchOn(), live.size())) {
            return base;
        }
        try {
            BlockHitResult deck = cast(level, context, base, live, wheelMount);
            return deck == null ? base : deck;
        } catch (RuntimeException ex) {
            if (FAILURE_LOGS.tryAcquire(System.nanoTime())) {
                SableSafeLanding.LOGGER.warn("Wheel deck check failed; keeping the original ground ray: {}", ex.toString());
            }
            return base;
        }
    }

    private static BlockHitResult cast(
            Level level,
            ClipContext context,
            BlockHitResult base,
            Set<AbstractContraptionEntity> live,
            Object wheelMount
    ) {
        Vec3 from = toWorld(level, context.getFrom());
        Vec3 to = toWorld(level, context.getTo());
        Aabb ray = WheelContact.rayBounds(sim(from), sim(to));
        if (ray == null) {
            return null;
        }
        boolean baseHit = base != null && base.getType() != HitResult.Type.MISS;
        double baseDistance = baseHit ? toWorld(level, base.getLocation()).distanceTo(from) : Double.NaN;

        double bestDistance = Double.POSITIVE_INFINITY;
        Vec3 bestHit = null;
        Vec3 bestNormal = null;
        AbstractContraptionEntity bestEntity = null;
        for (AbstractContraptionEntity entity : live) {
            if (entity == null || entity.isRemoved() || entity.level() != level) {
                continue;
            }
            Contraption contraption = entity.getContraption();
            if (contraption == null || ContraptionKinds.nameOf(contraption) == null) {
                continue;
            }
            if (!WheelContact.near(ray, sim(entity.getBoundingBox()))) {
                continue;
            }
            BlockHitResult local = castLocal(entity, contraption, from, to);
            if (local == null || local.getType() == HitResult.Type.MISS) {
                continue;
            }
            Vec3 hit = entity.toGlobalVector(local.getLocation(), 1.0F);
            double distance = hit.distanceTo(from);
            if (distance < bestDistance) {
                Direction face = local.getDirection();
                bestDistance = distance;
                bestHit = hit;
                bestEntity = entity;
                bestNormal = entity.applyRotation(new Vec3(face.getStepX(), face.getStepY(), face.getStepZ()), 1.0F);
            }
        }
        if (bestHit == null || !WheelContact.preferDeck(baseHit, baseDistance, true, bestDistance)) {
            return null;
        }
        Direction face = Direction.getNearest(bestNormal.x, bestNormal.y, bestNormal.z);
        // The deck block's spot in the world. The wheel reads friction there; it is usually air,
        // which Sable rates at the default friction of 1.
        BlockPos pos = BlockPos.containing(bestHit.subtract(bestNormal.scale(0.01)));
        DECK_HITS.incrementAndGet();
        if (!level.isClientSide() && wheelMount instanceof net.minecraft.world.level.block.entity.BlockEntity mount) {
            // The ray is already in world space, so the ship is found from the wheel mount itself.
            SubLevel ship = Sable.HELPER.getContaining(mount);
            if (ship != null) {
                WHEEL_TOUCH.put(ship.getUniqueId(), new WheelTouch(bestEntity.getUUID().toString(), level.getGameTime()));
            }
        }
        return new BlockHitResult(bestHit, face, pos, false);
    }

    private static BlockHitResult castLocal(AbstractContraptionEntity entity, Contraption contraption, Vec3 from, Vec3 to) {
        Vec3 localFrom = entity.toLocalVector(from, 1.0F);
        Vec3 localTo = entity.toLocalVector(to, 1.0F);
        Map<BlockPos, StructureBlockInfo> blocks = contraption.getBlocks();
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        BlockGetter shapes = shapeWorld(contraption);
        return BlockGetter.traverseBlocks(localFrom, localTo, blocks, (map, pos) -> {
            StructureBlockInfo info = map.get(pos);
            if (info == null) {
                return null;
            }
            BlockState state = info.state();
            VoxelShape shape = state.getCollisionShape(shapes, pos);
            if (shape.isEmpty()) {
                return null;
            }
            return shape.clip(localFrom, localTo, pos.immutable());
        }, map -> null);
    }

    private static BlockGetter shapeWorld(Contraption contraption) {
        try {
            BlockGetter world = contraption.getContraptionWorld();
            return world == null ? EmptyBlockGetter.INSTANCE : world;
        } catch (RuntimeException ex) {
            return EmptyBlockGetter.INSTANCE;
        }
    }

    /** Same projection Sable's clip uses: a point inside a sub-level plot goes to world space. */
    static Vec3 toWorld(Level level, Vec3 point) {
        SubLevel subLevel = Sable.HELPER.getContaining(level, point);
        if (subLevel == null) {
            return point;
        }
        Pose3dc pose = subLevel.logicalPose();
        if (level instanceof LevelPoseProviderExtension provider) {
            Pose3dc provided = provider.sable$getPose(subLevel);
            if (provided != null) {
                pose = provided;
            }
        }
        return JOMLConversion.toMojang(pose.transformPosition(JOMLConversion.toJOML(point)));
    }

    private static com.vucelich.sablesafelanding.sim.Vec3 sim(Vec3 v) {
        return new com.vucelich.sablesafelanding.sim.Vec3(v.x, v.y, v.z);
    }

    private static Aabb sim(AABB box) {
        if (box == null || box.minX > box.maxX || box.minY > box.maxY || box.minZ > box.maxZ) {
            return null;
        }
        return new Aabb(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }
}
