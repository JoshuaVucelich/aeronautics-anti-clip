package com.vucelich.anticlipbench;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.bearing.BearingBlock;
import com.simibubi.create.content.contraptions.elevator.ElevatorColumn;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
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
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scripted dedicated-server A/B. MSPT is the wall time from the start of the server tick
 * to the end of it, which is the work and not the 50 ms sleep. A ride window snapshots
 * the deck and the car, then asks the elevator to move, so the measured ticks include the trip.
 */
public final class BenchTrial {
    private static final int PARKED_TICKS = 400;
    /** A 4-block descent at this motor speed finishes in about 40 ticks. The window also covers the stop. */
    private static final int RIDE_TICKS = 80;

    private enum Phase {
        WARMUP, PARKED_BUILD, PARKED, ELEVATOR, SHIP_BUILD, SHIP, DONE
    }

    private static Phase phase = Phase.WARMUP;
    private static int phaseTick;
    private static int surface = Integer.MIN_VALUE;
    private static long tickStart;
    private static boolean measuring;
    private static Pose3dc earlyCar;
    private static Pose3dc earlyShip;
    private static final Map<String, Window> windows = new LinkedHashMap<>();
    private static Window window;

    private static BlockEntity controller;
    private static AbstractContraptionEntity contraption;
    private static ServerSubLevel car;
    private static ServerSubLevel ship;
    private static double startCarMinY;
    private static double startShipMinY;
    private static double startAnchorY;
    private static int origin;

    private BenchTrial() {
    }

    public static void onPre(ServerTickEvent.Pre event) {
        tickStart = System.nanoTime();
    }

    /** Pose tracking would have sent. Carry teleports later on this same event. */
    public static void onPostEarly(ServerTickEvent.Post event) {
        if (car != null && !car.isRemoved()) {
            earlyCar = new Pose3d(car.logicalPose());
        } else {
            earlyCar = null;
        }
        if (ship != null && !ship.isRemoved()) {
            earlyShip = new Pose3d(ship.logicalPose());
        } else {
            earlyShip = null;
        }
    }

    public static void onPostLate(ServerTickEvent.Post event) {
        if (phase == Phase.DONE) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (measuring && window != null) {
            window.mspt.add((System.nanoTime() - tickStart) / 1_000_000.0);
            note(car, earlyCar, "car");
            note(ship, earlyShip, "ship");
        }
        try {
            if (server.getTickCount() > 6000) {
                log("timeout tick {}", server.getTickCount());
                finish(server);
                return;
            }
            step(server.overworld());
        } catch (RuntimeException ex) {
            AnticlipBench.LOGGER.error("BENCH failed", ex);
            finish(server);
        }
    }

    private static void note(ServerSubLevel body, Pose3dc networked, String name) {
        if (body == null || body.isRemoved() || networked == null || window == null) {
            return;
        }
        window.bodies.computeIfAbsent(name, ignored -> new ClientModel()).observe(networked, body.logicalPose());
    }

    private static void step(ServerLevel level) {
        switch (phase) {
            case WARMUP -> warmup(level);
            case PARKED_BUILD -> buildRide(level, false);
            case PARKED -> measure(level, "parked", PARKED_TICKS, Phase.ELEVATOR, false);
            case ELEVATOR -> measure(level, "elevator", RIDE_TICKS, Phase.SHIP_BUILD, true);
            case SHIP_BUILD -> buildRide(level, true);
            case SHIP -> measure(level, "ship", RIDE_TICKS, Phase.DONE, true);
            case DONE -> {
            }
        }
    }

    private static void warmup(ServerLevel level) {
        if (surface == Integer.MIN_VALUE) {
            for (int cx = -1; cx <= 6; cx++) {
                for (int cz = -1; cz <= 2; cz++) {
                    level.setChunkForced(cx, cz, true);
                    level.getChunk(cx, cz);
                }
            }
            surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 8, 8);
            log("surface y {} mods {}", surface, mods());
        }
        if (++phaseTick >= 40 && surface > level.getMinBuildHeight()) {
            enter(Phase.PARKED_BUILD);
        }
    }

    private static void buildRide(ServerLevel level, boolean withShip) {
        if (phaseTick == 0) {
            origin = withShip ? 48 : 8;
            clear(level, origin);
            controller = elevator(level, new BlockPos(origin, surface, 8));
            log("{} controller {}", phase, controller == null ? "missing" : controller.getClass().getSimpleName());
        } else if (phaseTick == 20) {
            float speed = controller instanceof KineticBlockEntity kinetic ? kinetic.getSpeed() : 0.0f;
            log("{} speed before assemble {}", phase, speed);
            activate();
        } else if (phaseTick == 28) {
            contraption = attached(controller);
            log("{} contraption alive {} anchor {}", phase, contraption != null && contraption.isAlive(),
                    contraption == null ? "none" : contraption.getAnchorVec());
            if (withShip) {
                ship = assembleHull(level, new BlockPos(origin - 8, surface + 2, 8), 5);
                seat(level, ship, deckTop(), deckX(), deckZ());
            }
            car = assembleHull(level, new BlockPos(origin - 8, surface + 2, 12), 3);
            if (withShip && ship != null && !ship.isRemoved()) {
                seat(level, car, ship.boundingBox().maxY(), center(ship, true), center(ship, false));
            } else {
                seat(level, car, deckTop(), deckX(), deckZ());
            }
            startCarMinY = minY(car);
            startShipMinY = minY(ship);
            startAnchorY = contraption == null ? Double.NaN : contraption.getAnchorVec().y;
            log("{} seated car {} ship {}", phase, describe(car), describe(ship));
            enter(withShip ? Phase.SHIP : Phase.PARKED);
            return;
        }
        phaseTick++;
    }

    private static void measure(ServerLevel level, String name, int ticks, Phase next, boolean ride) {
        if (phaseTick == 0) {
            startCarMinY = minY(car);
            startShipMinY = minY(ship);
            startAnchorY = contraption == null ? Double.NaN : contraption.getAnchorVec().y;
            window = new Window(name);
            windows.put(name, window);
            measuring = true;
            log("{} start anchor {} car {} ship {}", name, fmt(startAnchorY), describe(car), describe(ship));
            if (ride) {
                callElevator();
            }
        }
        if (phaseTick >= ticks) {
            measuring = false;
            double anchor = contraption == null ? Double.NaN : contraption.getAnchorVec().y;
            window.outcome = outcome(car, startCarMinY, anchor - startAnchorY);
            window.shipOutcome = ship == null ? "" : outcome(ship, startShipMinY, anchor - startAnchorY);
            window.anchorDelta = anchor - startAnchorY;
            window.carDelta = minY(car) - startCarMinY;
            window.shipDelta = ship == null ? Double.NaN : minY(ship) - startShipMinY;
            log("{} done outcome {} ship {} dAnchor {} dCar {} dShip {}",
                    name, window.outcome, window.shipOutcome, fmt(window.anchorDelta), fmt(window.carDelta), fmt(window.shipDelta));
            window = null;
            if (next == Phase.DONE) {
                finish(level.getServer());
                return;
            }
            if (next == Phase.SHIP_BUILD) {
                clear(level, origin);
            }
            enter(next);
            return;
        }
        phaseTick++;
    }

    private static String outcome(ServerSubLevel body, double startMin, double anchorDelta) {
        if (body == null) {
            return "no-ship";
        }
        if (body.isRemoved()) {
            return "removed";
        }
        double delta = minY(body) - startMin;
        if (!Double.isFinite(anchorDelta) || Math.abs(anchorDelta) < 0.5) {
            return "platform-did-not-move";
        }
        if (Math.abs(delta - anchorDelta) <= Math.max(0.5, Math.abs(anchorDelta) * 0.25)) {
            return "followed";
        }
        if (delta < anchorDelta - 0.5) {
            return "fell-through";
        }
        return "separated";
    }

    private static void enter(Phase next) {
        phase = next;
        phaseTick = 0;
    }

    private static double deckTop() {
        return surface + 2.0;
    }

    private static double deckX() {
        return contraption == null ? origin + 0.5 : contraption.getAnchorVec().x + 0.5;
    }

    private static double deckZ() {
        return contraption == null ? 8.5 : contraption.getAnchorVec().z + 0.5;
    }

    private static double center(ServerSubLevel body, boolean x) {
        BoundingBox3dc box = body.boundingBox();
        return x ? (box.minX() + box.maxX()) * 0.5 : (box.minZ() + box.maxZ()) * 0.5;
    }

    private static void seat(ServerLevel level, ServerSubLevel body, double top, double x, double z) {
        if (body == null || body.isRemoved()) {
            return;
        }
        body.updateBoundingBox();
        BoundingBox3dc box = body.boundingBox();
        double min = box.minY();
        if (min == 0.0 && box.maxY() == 0.0) {
            min = body.logicalPose().position().y() - 0.5;
        }
        shift(level, body, x - center(body, true), top - min, z - center(body, false));
    }

    private static void shift(ServerLevel level, ServerSubLevel body, double dx, double dy, double dz) {
        Vector3d position = new Vector3d(body.logicalPose().position()).add(dx, dy, dz);
        Quaterniond orientation = new Quaterniond(body.logicalPose().orientation());
        SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
        PhysicsPipeline pipeline = system == null ? null : system.getPipeline();
        if (pipeline != null) {
            pipeline.teleport(body, position, orientation);
            pipeline.resetVelocity(body);
        } else {
            body.logicalPose().position().set(position);
        }
        body.latestLinearVelocity.zero();
        body.updateBoundingBox();
    }

    private static BlockEntity elevator(ServerLevel level, BlockPos base) {
        for (int y = -6; y <= 0; y++) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos dug = base.offset(x, y, z);
                    if (dug.getY() > level.getMinBuildHeight()) {
                        level.setBlockAndUpdate(dug, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        BlockPos pulleyPos = base.offset(0, 8, 0);
        BlockPos deck = base.offset(0, 1, 0);
        place(level, pulleyPos, "create:elevator_pulley", Direction.NORTH);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) {
                    continue;
                }
                level.setBlockAndUpdate(deck.offset(x, 0, z), Blocks.OAK_PLANKS.defaultBlockState());
            }
        }
        place(level, deck, "create:redstone_contact", Direction.SOUTH);
        motor(level, pulleyPos.west(), Direction.EAST, 32);
        return level.getBlockEntity(pulleyPos);
    }

    private static void activate() {
        if (controller == null) {
            return;
        }
        try {
            invoke(controller, "assemble");
        } catch (ReflectiveOperationException ex) {
            log("assemble failed {}", cause(ex));
        }
        contraption = attached(controller);
    }

    private static void callElevator() {
        if (!(controller instanceof ElevatorPulleyBlockEntity pulley) || pulley.movedContraption == null) {
            log("elevator controller missing");
            return;
        }
        if (!(pulley.movedContraption.getContraption() instanceof ElevatorContraption elevator)) {
            log("not an elevator contraption");
            return;
        }
        ElevatorColumn column = ElevatorColumn.get(pulley.getLevel(), elevator.getGlobalColumn());
        if (column == null || !column.isTargetAvailable()) {
            log("elevator column missing");
            return;
        }
        int current = column.getTargetedYLevel();
        int next = current - 4;
        if (elevator.isTargetUnreachable(next)) {
            log("elevator target {} unreachable from {}", next, current);
            return;
        }
        column.target(next);
        log("elevator target {} -> {} speed {}", current, next, fmt(pulley.getMovementSpeed()));
    }

    private static ServerSubLevel assembleHull(ServerLevel level, BlockPos origin, int length) {
        List<BlockPos> blocks = new ArrayList<>();
        for (int x = 0; x < length; x++) {
            BlockPos pos = origin.offset(x, 0, 0);
            level.setBlockAndUpdate(pos, Blocks.OAK_PLANKS.defaultBlockState());
            blocks.add(pos.immutable());
        }
        List<ServerSubLevel> before = ships(level);
        BlockPos min = blocks.get(0);
        BlockPos max = blocks.get(blocks.size() - 1);
        BoundingBox3i bounds = new BoundingBox3i(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
        ServerSubLevel created = SubLevelAssemblyHelper.assembleBlocks(level, origin, blocks, bounds);
        if (created != null) {
            return created;
        }
        return newest(before, ships(level));
    }

    private static void clear(ServerLevel level, int at) {
        if (car != null && !car.isRemoved()) {
            car.markRemoved();
        }
        if (ship != null && !ship.isRemoved()) {
            ship.markRemoved();
        }
        car = null;
        ship = null;
        if (controller instanceof LinearActuatorBlockEntity actuator) {
            actuator.disassemble();
        }
        if (contraption != null) {
            contraption.discard();
        }
        contraption = null;
        controller = null;
        BlockPos center = new BlockPos(at, surface, 8);
        for (int y = 0; y <= 14; y++) {
            for (int x = -12; x <= 8; x++) {
                for (int z = -4; z <= 8; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    if (pos.getY() > level.getMinBuildHeight()) {
                        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    private static AbstractContraptionEntity attached(BlockEntity entity) {
        if (entity instanceof LinearActuatorBlockEntity actuator) {
            return actuator.movedContraption;
        }
        return null;
    }

    private static void motor(ServerLevel level, BlockPos pos, Direction facing, int speed) {
        place(level, pos, "create:creative_motor", facing);
        if (level.getBlockEntity(pos) instanceof CreativeMotorBlockEntity motor && motor.generatedSpeed != null) {
            motor.generatedSpeed.setValue(speed);
            motor.updateGeneratedRotation();
        }
    }

    private static void place(ServerLevel level, BlockPos pos, String id, Direction facing) {
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
        if (block == null || block == Blocks.AIR) {
            log("missing block {}", id);
            return;
        }
        level.setBlockAndUpdate(pos, withFacing(block.defaultBlockState(), facing));
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
            if (property instanceof DirectionProperty directions && property.getName().equals("facing")
                    && directions.getPossibleValues().contains(facing)) {
                state = state.setValue(directions, facing);
            }
            if (property instanceof BooleanProperty flag && property.getName().equals("axis_along_first")) {
                state = state.setValue(flag, false);
            }
        }
        return state;
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

    private static String cause(ReflectiveOperationException ex) {
        Throwable error = ex instanceof InvocationTargetException target && target.getCause() != null ? target.getCause() : ex;
        if (error instanceof AssemblyException assembly && assembly.component != null) {
            return assembly.component.getString();
        }
        return error.toString();
    }

    private static double minY(ServerSubLevel body) {
        if (body == null || body.isRemoved()) {
            return Double.NaN;
        }
        return body.boundingBox().minY();
    }

    private static String describe(ServerSubLevel body) {
        if (body == null) {
            return "null";
        }
        if (body.isRemoved()) {
            return "removed";
        }
        BoundingBox3dc box = body.boundingBox();
        Vector3dc pose = body.logicalPose().position();
        return body.getUniqueId()
                + " box=[" + fmt(box.minX()) + "," + fmt(box.minY()) + "," + fmt(box.minZ())
                + " -> " + fmt(box.maxX()) + "," + fmt(box.maxY()) + "," + fmt(box.maxZ()) + "]"
                + " pose=(" + fmt(pose.x()) + "," + fmt(pose.y()) + "," + fmt(pose.z()) + ")";
    }

    private static String mods() {
        return "neoforge=" + version("neoforge")
                + " create=" + version("create")
                + " sable=" + version("sable")
                + " aeronautics=" + version("aeronautics")
                + " anticlip=" + version("sable_safe_landing")
                + " sodium=" + ModList.get().isLoaded("sodium");
    }

    private static String version(String id) {
        return ModList.get().getModContainerById(id)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("absent");
    }

    private static void finish(MinecraftServer server) {
        if (phase == Phase.DONE && windows.containsKey("wrote")) {
            return;
        }
        phase = Phase.DONE;
        measuring = false;
        write(server);
        log("stopping");
        server.halt(false);
    }

    private static void write(MinecraftServer server) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"anticlip\": \"").append(esc(version("sable_safe_landing"))).append("\",\n");
        json.append("  \"neoforge\": \"").append(esc(version("neoforge"))).append("\",\n");
        json.append("  \"create\": \"").append(esc(version("create"))).append("\",\n");
        json.append("  \"sable\": \"").append(esc(version("sable"))).append("\",\n");
        json.append("  \"aeronautics\": \"").append(esc(version("aeronautics"))).append("\",\n");
        json.append("  \"sodium\": ").append(ModList.get().isLoaded("sodium")).append(",\n");
        json.append("  \"scenes\": [\n");
        int index = 0;
        for (Window scene : windows.values()) {
            if (index++ > 0) {
                json.append(",\n");
            }
            json.append(scene.toJson());
        }
        json.append("\n  ]\n}\n");
        String path = System.getProperty("anticlip.bench.out", "bench-out.json");
        try {
            Files.writeString(Path.of(path), json.toString(), StandardCharsets.UTF_8);
            log("wrote {}", path);
        } catch (java.io.IOException ex) {
            AnticlipBench.LOGGER.error("BENCH could not write {}", path, ex);
        }
        server.getPlayerList();
    }

    private static String esc(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String fmt(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.3f", value) : "NaN";
    }

    private static void log(String message, Object... args) {
        AnticlipBench.LOGGER.info("BENCH " + message, args);
    }

    private static final class Window {
        private final String name;
        private final List<Double> mspt = new ArrayList<>();
        private final Map<String, ClientModel> bodies = new LinkedHashMap<>();
        private String outcome = "";
        private String shipOutcome = "";
        private double anchorDelta = Double.NaN;
        private double carDelta = Double.NaN;
        private double shipDelta = Double.NaN;

        private Window(String name) {
            this.name = name;
        }

        private String toJson() {
            StringBuilder out = new StringBuilder();
            out.append("    {\n");
            out.append("      \"name\": \"").append(name).append("\",\n");
            out.append("      \"ticks\": ").append(mspt.size()).append(",\n");
            out.append("      \"mspt\": ").append(stats(mspt)).append(",\n");
            out.append("      \"outcome\": \"").append(outcome).append("\",\n");
            out.append("      \"shipOutcome\": \"").append(shipOutcome).append("\",\n");
            out.append("      \"anchorDelta\": ").append(num(anchorDelta)).append(",\n");
            out.append("      \"carDelta\": ").append(num(carDelta)).append(",\n");
            out.append("      \"shipDelta\": ").append(num(shipDelta)).append(",\n");
            out.append("      \"bodies\": {\n");
            int index = 0;
            for (Map.Entry<String, ClientModel> entry : bodies.entrySet()) {
                if (index++ > 0) {
                    out.append(",\n");
                }
                ClientModel model = entry.getValue();
                out.append("        \"").append(entry.getKey()).append("\": {\n");
                out.append("          \"desync\": ").append(stats(model.desync())).append(",\n");
                out.append("          \"clientJitterRms\": ").append(num(model.clientJitterRms())).append(",\n");
                out.append("          \"serverJitterRms\": ").append(num(model.serverJitterRms())).append(",\n");
                out.append("          \"errorJitterRms\": ").append(num(model.errorJitterRms())).append(",\n");
                out.append("          \"movePackets\": ").append(model.movePackets()).append(",\n");
                out.append("          \"stopPackets\": ").append(model.stopPackets()).append(",\n");
                out.append("          \"rubberBand\": ").append(rubber(model)).append("\n");
                out.append("        }");
            }
            out.append("\n      }\n    }");
            return out.toString();
        }

        private static boolean rubber(ClientModel model) {
            Stats desync = Stats.of(model.desync());
            // A stuck client holds still while the server moves, then snaps. Sable's falling-ship
            // baseline stays near a block or two of interpolation lag and keeps sending MOVE.
            return model.movePackets() < Math.max(1, model.desync().size()) * 0.25
                    && desync.p99 > 4.0
                    && model.serverJitterRms() > 1.0e-4
                    && model.clientJitterRms() < model.serverJitterRms() * 0.05;
        }
    }

    private static String stats(List<Double> values) {
        Stats stats = Stats.of(values);
        return "{\"mean\": " + num(stats.mean)
                + ", \"p99\": " + num(stats.p99)
                + ", \"stddev\": " + num(stats.stddev)
                + ", \"max\": " + num(stats.max)
                + ", \"n\": " + values.size() + "}";
    }

    private static String num(double value) {
        if (!Double.isFinite(value)) {
            return "null";
        }
        return String.format(Locale.ROOT, "%.6f", value);
    }

    /** Sample standard deviation. p99 is the nearest-rank value, {@code ceil(0.99 n) - 1}. */
    private record Stats(double mean, double p99, double stddev, double max) {
        private static Stats of(List<Double> values) {
            if (values.isEmpty()) {
                return new Stats(Double.NaN, Double.NaN, Double.NaN, Double.NaN);
            }
            double sum = 0.0;
            double max = Double.NEGATIVE_INFINITY;
            for (double value : values) {
                sum += value;
                if (value > max) {
                    max = value;
                }
            }
            double mean = sum / values.size();
            double square = 0.0;
            for (double value : values) {
                double delta = value - mean;
                square += delta * delta;
            }
            double stddev = values.size() < 2 ? 0.0 : Math.sqrt(square / (values.size() - 1));
            List<Double> sorted = new ArrayList<>(values);
            sorted.sort(Double::compare);
            int index = (int) Math.ceil(0.99 * sorted.size()) - 1;
            if (index < 0) {
                index = 0;
            }
            if (index >= sorted.size()) {
                index = sorted.size() - 1;
            }
            return new Stats(mean, sorted.get(index), stddev, max);
        }
    }
}
