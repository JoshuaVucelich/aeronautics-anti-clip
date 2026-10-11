package com.vucelich.sablesafelanding;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.bearing.BearingBlock;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import dev.ryanhcode.sable.SableConfig;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Scripted dedicated-server trial. Enabled with {@code -Dsable_safe_landing.live=true}.
 * Logs lines prefixed with {@code LIVE} and then stops the server.
 */
public final class LiveTrial {
    private static final boolean ENABLED = Boolean.getBoolean("sable_safe_landing.live");
    private static final int MOVE_TICKS = 16;
    private static final double MOVE_STEP = 0.25;

    private enum Kind {
        ELEVATOR, BEARING, PISTON
    }

    private record Scene(String name, Kind kind, boolean feed, boolean carry) {
    }

    private static final Scene[] SCENES = {
            new Scene("elevator-off", Kind.ELEVATOR, false, false),
            new Scene("elevator-feed", Kind.ELEVATOR, true, false),
            new Scene("elevator-carry", Kind.ELEVATOR, true, true),
            new Scene("bearing-feed", Kind.BEARING, true, false),
            new Scene("bearing-carry", Kind.BEARING, true, true),
            new Scene("piston-feed", Kind.PISTON, true, false),
            new Scene("piston-carry", Kind.PISTON, true, true)
    };

    private enum Phase {
        WARMUP,
        VOID_OFF,
        VOID_ON,
        SCENE,
        DONE
    }

    private static Phase phase = Phase.WARMUP;
    private static int phaseTick;
    private static int sceneIndex;
    private static int surface = Integer.MIN_VALUE;
    private static ServerSubLevel ship;
    private static AbstractContraptionEntity contraption;
    private static BlockEntity controller;
    private static double startMinY;
    private static double startX;
    private static double startZ;
    private static double startAnchorY;
    private static boolean seated;
    private static int activatedAt = -1;

    private LiveTrial() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void onTick(ServerTickEvent.Post event) {
        if (!ENABLED || phase == Phase.DONE) {
            return;
        }
        MinecraftServer server = event.getServer();
        try {
            if (server.getTickCount() > 3600) {
                log("timeout tick {}", server.getTickCount());
                finish(server);
                return;
            }
            ServerLevel level = server.overworld();
            switch (phase) {
                case WARMUP -> warmup(level);
                case VOID_OFF -> voidCase(level, false);
                case VOID_ON -> voidCase(level, true);
                case SCENE -> scene(level);
                case DONE -> {
                }
            }
        } catch (RuntimeException ex) {
            SableSafeLanding.LOGGER.error("LIVE trial failed", ex);
            finish(server);
        }
    }

    private static void warmup(ServerLevel level) {
        if (surface == Integer.MIN_VALUE) {
            for (int cx = -1; cx <= 9; cx++) {
                for (int cz = -1; cz <= 2; cz++) {
                    level.setChunkForced(cx, cz, true);
                    level.getChunk(cx, cz);
                }
            }
            surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 8, 8);
            log("surface y {} minBuild {} removeMin {} removeMax {} thread {}",
                    surface,
                    level.getMinBuildHeight(),
                    SableConfig.SUB_LEVEL_REMOVE_MIN.getAsDouble(),
                    SableConfig.SUB_LEVEL_REMOVE_MAX.getAsDouble(),
                    Thread.currentThread().getName());
        }
        if (++phaseTick < 20 || surface <= level.getMinBuildHeight()) {
            return;
        }
        phase = Phase.VOID_OFF;
        phaseTick = 0;
        ship = null;
    }

    private static void voidCase(ServerLevel level, boolean rescue) {
        flags(rescue, false, false);
        int origin = rescue ? 16 : 8;
        if (phaseTick == 0) {
            discardShip();
            ship = assembleCar(level, new BlockPos(origin, surface + 2, 8), true);
            log("void-{} assembled removed={} {}", rescue ? "on" : "off", ship == null || ship.isRemoved(), describe(ship));
        } else if (phaseTick == 10) {
            push(level, ship, level.getMinBuildHeight() - 16.0);
            log("void-{} pushed below min build {}", rescue ? "on" : "off", describe(ship));
        } else if (phaseTick == 25) {
            log("void-{} after min build removed={} hook={} {}",
                    rescue ? "on" : "off", ship != null && ship.isRemoved(), VoidRescueRuntime.removalHookSeen(), describe(ship));
            if (ship != null && !ship.isRemoved()) {
                push(level, ship, SableConfig.SUB_LEVEL_REMOVE_MIN.getAsDouble() - 2.0);
                log("void-{} pushed below remove {}", rescue ? "on" : "off", describe(ship));
            }
        } else if (phaseTick == 40) {
            log("void-{} after remove removed={} {}", rescue ? "on" : "off", ship != null && ship.isRemoved(), describe(ship));
            discardShip();
            phase = rescue ? Phase.SCENE : Phase.VOID_ON;
            phaseTick = 0;
            sceneIndex = 0;
            return;
        }
        phaseTick++;
    }

    private static void scene(ServerLevel level) {
        if (sceneIndex >= SCENES.length) {
            log("done hook={} feedHook={}", VoidRescueRuntime.removalHookSeen(), ColliderFeedRuntime.hookSeen());
            finish(level.getServer());
            return;
        }
        Scene scene = SCENES[sceneIndex];
        flags(true, scene.feed, scene.carry);
        int origin = 32 + sceneIndex * 16;
        if (phaseTick == 0) {
            cleanup(level, origin);
            contraption = null;
            controller = null;
            seated = false;
            ship = null;
            activatedAt = -1;
            assembleScene(level, scene, origin);
        } else if (activatedAt < 0 && phaseTick >= 8) {
            float speed = controller instanceof KineticBlockEntity kinetic ? kinetic.getSpeed() : 0.0f;
            if (speed != 0.0f || phaseTick >= 40) {
                log("{} speed before assemble {}", scene.name, speed);
                activate(scene);
                activatedAt = phaseTick;
            }
        } else if (activatedAt >= 0 && phaseTick == activatedAt + 4) {
            if (contraption == null || !contraption.isAlive()) {
                contraption = attached(controller);
            }
            log("{} contraption present={} alive={} claimed={} anchor={}",
                    scene.name,
                    contraption != null,
                    contraption != null && contraption.isAlive(),
                    contraption != null && ColliderFeedRuntime.isClaimed(contraption.getUUID().toString()),
                    contraption == null ? "none" : contraption.getAnchorVec());
            if (contraption != null && contraption.isAlive()) {
                ship = assembleCar(level, carPos(scene.kind, origin), sceneIndex == 0);
                seat(level, scene);
                startMinY = ship == null ? Double.NaN : ship.boundingBox().minY();
                startX = ship == null ? Double.NaN : ship.logicalPose().position().x();
                startZ = ship == null ? Double.NaN : ship.logicalPose().position().z();
                startAnchorY = contraption.getAnchorVec().y;
                log("{} seated {}", scene.name, describe(ship));
            }
        } else if (activatedAt >= 0 && phaseTick > activatedAt + 4 && phaseTick <= activatedAt + 4 + MOVE_TICKS
                && contraption != null && contraption.isAlive()) {
            nudge(scene, phaseTick - (activatedAt + 4));
        } else if (activatedAt >= 0 && phaseTick == activatedAt + 5 + MOVE_TICKS) {
            report(scene);
            cleanup(level, origin);
            sceneIndex++;
            phaseTick = 0;
            return;
        }
        phaseTick++;
    }

    private static void assembleScene(ServerLevel level, Scene scene, int origin) {
        BlockPos base = new BlockPos(origin, surface, 8);
        switch (scene.kind) {
            case ELEVATOR -> controller = elevator(level, base);
            case BEARING -> controller = bearing(level, base);
            case PISTON -> controller = piston(level, base);
        }
        log("{} controller {}", scene.name, controller == null ? "missing" : controller.getClass().getSimpleName());
    }

    private static BlockPos carPos(Kind kind, int origin) {
        int y = switch (kind) {
            case ELEVATOR, BEARING, PISTON -> surface + 2;
        };
        return new BlockPos(origin - 4, y, 8);
    }

    private static void seat(ServerLevel level, Scene scene) {
        if (ship == null || ship.isRemoved() || contraption == null) {
            return;
        }
        double top = surface + 2.0;
        double gap = ship.boundingBox().minY() - top;
        if (Math.abs(gap) > 0.05 || Math.abs(ship.logicalPose().position().x() - contraption.getX()) > 1.5) {
            shiftShip(level, ship, contraption.getX() - ship.logicalPose().position().x(), top - ship.boundingBox().minY(), contraption.getZ() - ship.logicalPose().position().z());
            seated = true;
        }
        ship.latestLinearVelocity.zero();
    }

    private static void nudge(Scene scene, int step) {
        if (contraption == null) {
            return;
        }
        if (scene.kind == Kind.BEARING && controller instanceof MechanicalBearingBlockEntity bearing && contraption instanceof ControlledContraptionEntity controlled) {
            float angle = step * (90.0f / MOVE_TICKS);
            bearing.setAngle(angle);
            controlled.setAngle(angle);
            return;
        }
        double dy = scene.kind == Kind.PISTON ? MOVE_STEP : -MOVE_STEP;
        if (controller instanceof LinearActuatorBlockEntity actuator) {
            actuator.offset += (float) Math.abs(MOVE_STEP);
            actuator.running = true;
        }
        contraption.setPos(contraption.getX(), contraption.getY() + dy, contraption.getZ());
    }

    private static void report(Scene scene) {
        if (ship == null) {
            log("{} no ship", scene.name);
            return;
        }
        BoundingBox3dc box = ship.boundingBox();
        double anchorY = contraption == null ? Double.NaN : contraption.getAnchorVec().y;
        double dY = box.minY() - startMinY;
        double dAnchor = anchorY - startAnchorY;
        double dX = ship.logicalPose().position().x() - startX;
        double dZ = ship.logicalPose().position().z() - startZ;
        String outcome;
        if (ship.isRemoved()) {
            outcome = "removed";
        } else if (scene.kind == Kind.BEARING) {
            outcome = Math.hypot(dX, dZ) > 0.4 ? "orbited" : "stayed";
        } else if (Math.abs(dAnchor) < 0.2) {
            outcome = "platform-did-not-move";
        } else if (Math.abs(dY - dAnchor) < 0.75) {
            outcome = "followed";
        } else if (dY < dAnchor - 0.75) {
            outcome = "fell-through";
        } else {
            outcome = "separated";
        }
        log("{} outcome={} seated={} removed={} dY={} dAnchor={} dX={} dZ={} minY={} anchorY={}",
                scene.name, outcome, seated, ship.isRemoved(),
                fmt(dY), fmt(dAnchor), fmt(dX), fmt(dZ), fmt(box.minY()), fmt(anchorY));
    }

    private static void activate(Scene scene) {
        if (controller instanceof MechanicalBearingBlockEntity bearing) {
            bearing.assemble();
            if (bearing.getLastAssemblyException() != null) {
                log("{} exception {}", scene.name, bearing.getLastAssemblyException().component.getString());
            }
        } else if (controller != null) {
            try {
                invoke(controller, "assemble");
            } catch (ReflectiveOperationException ex) {
                log("{} assemble failed {}", scene.name, causeText(ex));
            }
        }
        contraption = attached(controller);
        log("{} immediate alive {} entity {}", scene.name, contraption != null && contraption.isAlive(), contraption);
    }

    private static BlockEntity elevator(ServerLevel level, BlockPos base) {
        BlockPos pulleyPos = base.offset(0, 8, 0);
        BlockPos deck = base.offset(0, 1, 0);
        place(level, pulleyPos, "create:elevator_pulley", Direction.NORTH);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                level.setBlockAndUpdate(deck.offset(x, 0, z), Blocks.OAK_PLANKS.defaultBlockState());
            }
        }
        place(level, deck.north(2), "create:redstone_contact", Direction.SOUTH);
        road(level, base.offset(-6, 1, 0), base.offset(-2, 1, 0));
        // Pulley shaft is the clockwise axis of its facing: north -> X.
        motor(level, pulleyPos.west(), Direction.EAST);
        return level.getBlockEntity(pulleyPos);
    }

    private static BlockEntity bearing(ServerLevel level, BlockPos base) {
        place(level, base, "create:mechanical_bearing", Direction.UP);
        BlockPos deck = base.above();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                level.setBlockAndUpdate(deck.offset(x, 0, z), Blocks.OAK_PLANKS.defaultBlockState());
            }
        }
        motor(level, base.below(), Direction.UP);
        return level.getBlockEntity(base);
    }

    private static BlockEntity piston(ServerLevel level, BlockPos base) {
        place(level, base, "create:sticky_mechanical_piston", Direction.UP);
        // A retracted piston counts poles on its back, not in front of the head.
        place(level, base.below(), "create:piston_extension_pole", Direction.UP);
        BlockPos deck = base.above();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                level.setBlockAndUpdate(deck.offset(x, 0, z), Blocks.OAK_PLANKS.defaultBlockState());
            }
        }
        // Facing up with axis_along_first false puts the shaft on Z.
        motor(level, base.north(), Direction.SOUTH);
        log("piston state {} pole {}", level.getBlockState(base), level.getBlockState(base.below()));
        return level.getBlockEntity(base);
    }

    private static void motor(ServerLevel level, BlockPos pos, Direction facing) {
        place(level, pos, "create:creative_motor", facing);
        if (level.getBlockEntity(pos) instanceof CreativeMotorBlockEntity motor && motor.generatedSpeed != null) {
            motor.generatedSpeed.setValue(32);
            motor.updateGeneratedRotation();
            log("motor at {} generates {}", pos, motor.getGeneratedSpeed());
        }
    }

    private static ServerSubLevel assembleCar(ServerLevel level, BlockPos origin, boolean tryAssembler) {
        List<BlockPos> blocks = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            BlockPos pos = origin.offset(x, 0, 0);
            level.setBlockAndUpdate(pos, Blocks.OAK_PLANKS.defaultBlockState());
            blocks.add(pos.immutable());
        }
        List<ServerSubLevel> before = ships(level);
        if (tryAssembler) {
            BlockPos assemblerPos = origin.offset(0, 0, 1);
            place(level, assemblerPos, "simulated:physics_assembler", Direction.NORTH);
            BlockEntity assembler = level.getBlockEntity(assemblerPos);
            if (assembler != null) {
                try {
                    invoke(assembler, "assembleOrDisassemble");
                } catch (ReflectiveOperationException ex) {
                    log("physics assembler call failed {}", ex.toString());
                }
            } else {
                log("physics assembler block missing");
            }
            ServerSubLevel created = newest(before, ships(level));
            if (created != null) {
                log("physics assembler produced {}", created.getUniqueId());
                return created;
            }
            level.setBlockAndUpdate(assemblerPos, Blocks.AIR.defaultBlockState());
            log("physics assembler produced no sub-level; using Sable assembly");
        }
        BlockPos min = blocks.get(0);
        BlockPos max = blocks.get(blocks.size() - 1);
        BoundingBox3i bounds = new BoundingBox3i(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
        return SubLevelAssemblyHelper.assembleBlocks(level, origin, blocks, bounds);
    }

    private static void push(ServerLevel level, ServerSubLevel subLevel, double targetMinY) {
        if (subLevel == null || subLevel.isRemoved()) {
            return;
        }
        double dy = targetMinY - subLevel.boundingBox().minY();
        shiftShip(level, subLevel, 0.0, dy, 0.0);
    }

    private static void shiftShip(ServerLevel level, ServerSubLevel subLevel, double dx, double dy, double dz) {
        Vector3d position = new Vector3d(subLevel.logicalPose().position());
        position.add(dx, dy, dz);
        Quaterniond orientation = new Quaterniond(subLevel.logicalPose().orientation());
        SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
        PhysicsPipeline pipeline = system == null ? null : system.getPipeline();
        if (pipeline != null) {
            pipeline.teleport(subLevel, position, orientation);
            pipeline.resetVelocity(subLevel);
        } else {
            subLevel.logicalPose().position().set(position);
        }
        subLevel.latestLinearVelocity.zero();
        subLevel.updateBoundingBox();
    }

    private static AbstractContraptionEntity attached(BlockEntity controller) {
        if (controller instanceof MechanicalBearingBlockEntity bearing) {
            return bearing.getMovedContraption();
        }
        if (controller instanceof LinearActuatorBlockEntity actuator) {
            return actuator.movedContraption;
        }
        return null;
    }

    private static void place(ServerLevel level, BlockPos pos, String id, Direction facing) {
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
        if (block == null || block == Blocks.AIR) {
            log("missing block {}", id);
            return;
        }
        BlockState state = withFacing(block.defaultBlockState(), facing);
        level.setBlockAndUpdate(pos, state);
    }

    private static BlockState withFacing(BlockState state, Direction facing) {
        if (state.hasProperty(BearingBlock.FACING) && BearingBlock.FACING.getPossibleValues().contains(facing)) {
            state = state.setValue(BearingBlock.FACING, facing);
        }
        if (state.hasProperty(BlockStateProperties.FACING) && BlockStateProperties.FACING.getPossibleValues().contains(facing)) {
            state = state.setValue(BlockStateProperties.FACING, facing);
        }
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) && facing.getAxis().isHorizontal()
                && BlockStateProperties.HORIZONTAL_FACING.getPossibleValues().contains(facing)) {
            state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        }
        for (Property<?> property : state.getProperties()) {
            if (property instanceof DirectionProperty directions && property.getName().equals("facing") && directions.getPossibleValues().contains(facing)) {
                state = state.setValue(directions, facing);
            }
            if (property instanceof BooleanProperty flag && property.getName().equals("axis_along_first")) {
                state = state.setValue(flag, false);
            }
        }
        return state;
    }

    private static String causeText(ReflectiveOperationException ex) {
        Throwable cause = ex instanceof InvocationTargetException target && target.getCause() != null ? target.getCause() : ex;
        if (cause instanceof AssemblyException assembly && assembly.component != null) {
            return assembly.component.getString();
        }
        return cause.toString();
    }

    private static void road(ServerLevel level, BlockPos from, BlockPos to) {
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            level.setBlockAndUpdate(pos.immutable(), Blocks.STONE.defaultBlockState());
        }
    }

    private static void flags(boolean rescue, boolean feed, boolean carry) {
        SafeLandingConfig.VOID_RESCUE.set(rescue);
        SafeLandingConfig.FEED_COLLIDERS.set(feed);
        SafeLandingConfig.CARRY.set(carry);
        SafeLandingConfig.SWEEP.set(false);
    }

    private static List<ServerSubLevel> ships(ServerLevel level) {
        if (!(SubLevelContainer.getContainer(level) instanceof ServerSubLevelContainer container)) {
            return List.of();
        }
        List<ServerSubLevel> live = new ArrayList<>();
        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (!subLevel.isRemoved()) {
                live.add(subLevel);
            }
        }
        return live;
    }

    private static ServerSubLevel newest(List<ServerSubLevel> before, List<ServerSubLevel> after) {
        for (ServerSubLevel candidate : after) {
            boolean known = false;
            for (ServerSubLevel prior : before) {
                if (prior.getUniqueId().equals(candidate.getUniqueId())) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                return candidate;
            }
        }
        return null;
    }

    private static void cleanup(ServerLevel level, int origin) {
        discardShip();
        if (controller instanceof MechanicalBearingBlockEntity bearing) {
            bearing.disassemble();
        } else if (controller instanceof LinearActuatorBlockEntity actuator) {
            actuator.disassemble();
        }
        if (contraption != null) {
            contraption.discard();
        }
        contraption = null;
        controller = null;
        BlockPos center = new BlockPos(origin, surface, 8);
        for (int y = 0; y <= 14; y++) {
            for (int x = -8; x <= 8; x++) {
                for (int z = -3; z <= 3; z++) {
                    level.setBlockAndUpdate(center.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static void discardShip() {
        if (ship != null && !ship.isRemoved()) {
            ship.markRemoved();
        }
        ship = null;
    }

    private static String describe(ServerSubLevel subLevel) {
        if (subLevel == null) {
            return "ship=null";
        }
        if (subLevel.isRemoved()) {
            return "ship=" + subLevel.getUniqueId() + " removed";
        }
        BoundingBox3dc box = subLevel.boundingBox();
        return "ship=" + subLevel.getUniqueId()
                + " minY=" + fmt(box.minY())
                + " maxY=" + fmt(box.maxY())
                + " pos=" + fmt(subLevel.logicalPose().position().x())
                + "," + fmt(subLevel.logicalPose().position().y())
                + "," + fmt(subLevel.logicalPose().position().z());
    }

    private static String fmt(double value) {
        return Double.isFinite(value) ? String.format(java.util.Locale.ROOT, "%.2f", value) : "NaN";
    }

    private static void invoke(BlockEntity entity, String name) throws ReflectiveOperationException {
        Class<?> type = entity.getClass();
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                method.invoke(entity);
                return;
            } catch (NoSuchMethodException ex) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static void log(String message, Object... args) {
        SableSafeLanding.LOGGER.info("LIVE " + message, args);
    }

    private static void finish(MinecraftServer server) {
        phase = Phase.DONE;
        log("stopping");
        server.halt(false);
    }
}
