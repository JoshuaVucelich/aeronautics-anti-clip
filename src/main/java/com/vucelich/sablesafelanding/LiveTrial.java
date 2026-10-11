package com.vucelich.sablesafelanding;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.bearing.BearingBlock;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorColumn;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
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
import com.mojang.authlib.GameProfile;
import com.vucelich.sablesafelanding.sim.Rot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Scripted dedicated-server trial. Enabled with {@code -Dsable_safe_landing.live=true}.
 * Logs lines prefixed with {@code LIVE} and then stops the server.
 */
public final class LiveTrial {
    private static final boolean ENABLED = Boolean.getBoolean("sable_safe_landing.live");

    private enum Kind {
        ELEVATOR, BEARING, PISTON
    }

    private enum Tail {
        NONE, DRIVE, DROP, FLY, YAW, PILOT, DISARM
    }

    private record Scene(String name, Kind kind, boolean feed, boolean carry, int direction, int moveTicks, Tail tail, double seatLift) {
    }

    private static final Scene[] SCENES = {
            new Scene("elevator-off", Kind.ELEVATOR, false, false, -1, 48, Tail.NONE, 0.0),
            new Scene("elevator-down", Kind.ELEVATOR, true, false, -1, 48, Tail.NONE, 0.0),
            new Scene("elevator-up", Kind.ELEVATOR, true, false, 1, 48, Tail.NONE, 0.0),
            new Scene("elevator-carry", Kind.ELEVATOR, true, true, -1, 48, Tail.NONE, 0.0),
            new Scene("bearing-feed", Kind.BEARING, true, false, 0, 24, Tail.NONE, 0.0),
            new Scene("bearing-carry", Kind.BEARING, true, true, 0, 24, Tail.YAW, 0.0),
            new Scene("bearing-yaw", Kind.BEARING, true, true, 0, 24, Tail.YAW, 0.0),
            new Scene("piston-feed", Kind.PISTON, true, false, 1, 64, Tail.NONE, 0.0),
            new Scene("elevator-stop", Kind.ELEVATOR, true, true, -1, 48, Tail.DRIVE, 0.0),
            new Scene("elevator-drop", Kind.ELEVATOR, true, true, -1, 16, Tail.DROP, 0.0),
            new Scene("flyover", Kind.ELEVATOR, true, true, -1, 32, Tail.FLY, 0.7),
            new Scene("pilot-takeoff", Kind.ELEVATOR, true, true, 1, 36, Tail.PILOT, 0.0),
            new Scene("disarm-collide", Kind.ELEVATOR, true, false, 1, 48, Tail.DISARM, 0.0)
    };

    private static final GameProfile PILOT_PROFILE = new GameProfile(
            UUID.fromString("6f1c0a1e-7b2d-4c3a-9e10-a1b2c3d4e5f6"),
            "DeckPilot"
    );

    private enum Phase {
        WARMUP,
        VOID_OFF,
        VOID_ON,
        BEDROCK,
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
    private static boolean bedrockNoted;
    private static int activatedAt = -1;
    private static double startDeckDeg;
    private static double startYawDeg;
    private static double markerX = Double.NaN;
    private static double markerY = Double.NaN;
    private static FakePlayer pilot;

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
                case BEDROCK -> bedrockCase(level);
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
            SafeLandingConfig.ENABLED.set(true);
            for (int cx = -1; cx <= 16; cx++) {
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
            phase = rescue ? Phase.BEDROCK : Phase.VOID_ON;
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
            markerX = Double.NaN;
            markerY = Double.NaN;
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
                startDeckDeg = deckDegrees(contraption);
                startYawDeg = ship == null ? Double.NaN : yawDegrees(ship);
                log("{} seated {}", scene.name, describe(ship));
            }
        } else if (activatedAt >= 0 && phaseTick > activatedAt + 4 && contraption != null) {
            int step = phaseTick - (activatedAt + 4);
            int reportAt = activatedAt + 5 + scene.moveTicks();
            if (step <= scene.moveTicks() && contraption.isAlive()) {
                nudge(scene, step);
            }
            if (scene.tail == Tail.FLY && ship != null && !ship.isRemoved()) {
                holdFlyover(level, scene);
            }
            if (scene.tail == Tail.PILOT && step >= 8) {
                steerPilot(level);
            }
            if (scene.tail == Tail.DROP && phaseTick == reportAt && contraption.isAlive()) {
                disassembleController();
                log("{} disassembled alive={}", scene.name, contraption.isAlive());
            }
            if (scene.tail == Tail.DRIVE && phaseTick == reportAt + 20 && ship != null && !ship.isRemoved()) {
                markerX = ship.logicalPose().position().x();
                shiftShip(level, ship, 3.0, 0.0, 0.0);
                log("{} shoved x={} held={}", scene.name, fmt(ship.logicalPose().position().x()), held(ship));
            }
            if (scene.tail == Tail.DISARM && step == 10) {
                disarmFeed(level, scene);
            }
            if (scene.tail == Tail.DISARM && step == 16 && contraption != null) {
                log("{} sable back tracked={} sableInitialized={} claimed={} pipeline={}",
                        scene.name,
                        ColliderFeedRuntime.tracked(level).size(),
                        ColliderFeedRuntime.sableInitialized(contraption),
                        ColliderFeedRuntime.isClaimed(contraption.getUUID().toString()),
                        ColliderFeedRuntime.pipelineBodies(level));
            }
            if (scene.tail == Tail.PILOT && phaseTick >= reportAt && ship != null && !ship.isRemoved()) {
                if (phaseTick == reportAt) {
                    markerY = ship.logicalPose().position().y();
                }
                // Gravity erases one shove before the report. Keep climbing, and carry must not pull it back.
                shiftShip(level, ship, 0.0, 0.4, 0.0);
                if (phaseTick == reportAt) {
                    log("{} shoved y={} held={}", scene.name, fmt(ship.logicalPose().position().y()), held(ship));
                }
            }
        }
        if (activatedAt >= 0 && phaseTick == doneTick(scene)) {
            report(scene);
            cleanup(level, origin);
            sceneIndex++;
            phaseTick = 0;
            return;
        }
        phaseTick++;
    }

    private static int doneTick(Scene scene) {
        if (activatedAt < 0) {
            return Integer.MAX_VALUE;
        }
        int reportAt = activatedAt + 5 + scene.moveTicks();
        return switch (scene.tail) {
            case DRIVE -> reportAt + 32;
            case DROP -> reportAt + 8;
            case PILOT -> reportAt + 10;
            default -> reportAt;
        };
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
        ship.updateBoundingBox();
        double top = surface + 2.0 + scene.seatLift;
        BoundingBox3dc box = ship.boundingBox();
        double minY = box.minY();
        if (minY == 0.0 && box.maxY() == 0.0) {
            minY = ship.logicalPose().position().y() - 0.5;
        }
        // The elevator contraption is the contact block. Put the hull on that block, not beside it.
        // A bearing rotates around its own anchor, so the car has to sit off that axis.
        double deckX = contraption.getAnchorVec().x + 0.5 + (scene.kind == Kind.BEARING ? 1.0 : 0.0);
        double deckZ = contraption.getAnchorVec().z + 0.5;
        double centerX = (box.minX() + box.maxX()) * 0.5;
        double centerZ = (box.minZ() + box.maxZ()) * 0.5;
        double gap = minY - top;
        if (Math.abs(gap) > 0.05 || Math.abs(centerX - deckX) > 0.2 || Math.abs(centerZ - deckZ) > 0.2) {
            shiftShip(level, ship, deckX - centerX, top - minY, deckZ - centerZ);
            seated = true;
        }
        ship.latestLinearVelocity.zero();
    }

    /** Holds a flyover above the deck so gravity cannot turn it into contact. */
    private static void holdFlyover(ServerLevel level, Scene scene) {
        if (ship == null || ship.isRemoved() || contraption == null) {
            return;
        }
        double top = surface + 2.0 + scene.seatLift;
        double minY = ship.boundingBox().minY();
        shiftShip(
                level,
                ship,
                contraption.getX() - ship.logicalPose().position().x(),
                top - minY,
                contraption.getZ() - ship.logicalPose().position().z()
        );
        ship.latestLinearVelocity.zero();
    }

    private static void steerPilot(ServerLevel level) {
        if (ship == null || ship.isRemoved()) {
            return;
        }
        if (pilot == null) {
            pilot = FakePlayerFactory.get(level, PILOT_PROFILE);
        }
        org.joml.Vector3dc pos = ship.logicalPose().position();
        pilot.setPos(pos.x(), pos.y() + 0.1, pos.z());
        pilot.xxa = 0.0f;
        pilot.yya = 0.0f;
        pilot.zza = 1.0f;
        if (level.getEntity(pilot.getUUID()) != null) {
            return;
        }
        try {
            boolean added = level.addFreshEntity(pilot);
            log("pilot added={} pos={}", added, pos);
        } catch (RuntimeException ex) {
            log("pilot add failed {}", ex.toString());
        }
    }

    private static void disassembleController() {
        if (controller instanceof LinearActuatorBlockEntity actuator) {
            actuator.disassemble();
        } else if (controller instanceof MechanicalBearingBlockEntity bearing) {
            bearing.disassemble();
        }
    }

    private static void nudge(Scene scene, int step) {
        if (contraption == null || !contraption.isAlive()) {
            return;
        }
        if (scene.kind == Kind.BEARING) {
            // The motor already integrates the angle. Overwriting it wraps the sample and flings the car.
            return;
        }
        if (scene.kind == Kind.ELEVATOR && step == 1) {
            // No floor target means movement speed is zero, and the arrived snap writes a raw setPos back.
            callElevator(scene);
        }
        if (step == 1 || step % 16 == 0) {
            traceActuator(scene);
        }
    }

    /**
     * Ask the elevator column for a floor a few blocks off the current one. Increasing the
     * target Y raises the car; decreasing it lowers the car. Create integrates the offset.
     */
    private static void callElevator(Scene scene) {
        if (!(controller instanceof ElevatorPulleyBlockEntity pulley) || pulley.movedContraption == null) {
            log("{} elevator controller missing", scene.name);
            return;
        }
        if (!(pulley.movedContraption.getContraption() instanceof ElevatorContraption contraption)) {
            log("{} not an elevator contraption", scene.name);
            return;
        }
        ElevatorColumn column = ElevatorColumn.get(pulley.getLevel(), contraption.getGlobalColumn());
        if (column == null || !column.isTargetAvailable()) {
            log("{} elevator column missing", scene.name);
            return;
        }
        int current = column.getTargetedYLevel();
        int next = current + scene.direction * 4;
        if (contraption.isTargetUnreachable(next)) {
            log("{} elevator target {} unreachable from {}", scene.name, next, current);
            return;
        }
        column.target(next);
        log("{} elevator target {} -> {} offset {} speed {}",
                scene.name, current, next, fmt(pulley.offset), fmt(pulley.getMovementSpeed()));
    }

    private static void traceActuator(Scene scene) {
        if (!(controller instanceof LinearActuatorBlockEntity actuator)) {
            return;
        }
        boolean stalled = actuator.movedContraption != null && actuator.movedContraption.isStalled();
        log("{} actuator offset={} speed={} stalled={} anchorY={}",
                scene.name,
                fmt(actuator.offset),
                fmt(actuator.getMovementSpeed()),
                stalled,
                contraption == null ? "none" : fmt(contraption.getAnchorVec().y));
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
            if (dY < -1.0) {
                outcome = "fell";
            } else {
                outcome = Math.hypot(dX, dZ) > 0.4 ? "orbited" : "stayed";
            }
        } else if (Math.abs(dAnchor) < 1.0) {
            outcome = "platform-did-not-move";
        } else if (Math.abs(dY - dAnchor) <= Math.max(0.5, Math.abs(dAnchor) * 0.25)) {
            outcome = "followed";
        } else if (dY < dAnchor - 0.5) {
            outcome = "fell-through";
        } else {
            outcome = "separated";
        }
        String platform = contraption == null ? "none" : PlatformTrace.get(contraption.getUUID().toString());
        String ride = PlatformTrace.get("ride:" + ship.getUniqueId());
        org.joml.Vector3d velocity = ship.latestLinearVelocity;
        boolean held = held(ship);
        log("{} outcome={} seated={} removed={} claimed={} held={} dY={} dAnchor={} dX={} dZ={} minY={} posY={} anchorY={} velY={} platform=[{}] ride=[{}]",
                scene.name, outcome, seated, ship.isRemoved(),
                contraption != null && ColliderFeedRuntime.isClaimed(contraption.getUUID().toString()),
                held,
                fmt(dY), fmt(dAnchor), fmt(dX), fmt(dZ), fmt(box.minY()), fmt(ship.logicalPose().position().y()),
                fmt(anchorY), fmt(velocity.y),
                platform, ride);
        if (scene.kind == Kind.BEARING) {
            double yawNow = yawDegrees(ship);
            double deckNow = deckDegrees(contraption);
            CarryRuntime.YawSample sample = CarryRuntime.yawSample(ship.getUniqueId());
            double errSeat = Mth.wrapDegrees((float) ((yawNow - startYawDeg) - (deckNow - startDeckDeg)));
            double errLock = sample.held()
                    ? Mth.wrapDegrees((float) ((yawNow - sample.boardedYawDegrees()) - (deckNow - sample.capturedDeckDegrees())))
                    : Double.NaN;
            log("{} bearing pass errSeat={}", scene.name, fmt(errSeat));
            log("{} yaw dYaw={} dDeck={} errSeat={} errLock={} held={}",
                    scene.name,
                    fmt(yawNow - startYawDeg),
                    fmt(deckNow - startDeckDeg),
                    fmt(errSeat),
                    fmt(errLock),
                    sample.held());
        }
        if (scene.tail == Tail.DRIVE && Double.isFinite(markerX)) {
            log("{} drive dX={} held={}", scene.name, fmt(ship.logicalPose().position().x() - markerX), held);
        }
        if (scene.tail == Tail.PILOT && Double.isFinite(markerY)) {
            log("{} takeoff dY={} held={}", scene.name, fmt(ship.logicalPose().position().y() - markerY), held);
        }
        if (scene.tail == Tail.FLY) {
            log("{} flyover held={}", scene.name, held);
        }
        if (scene.tail == Tail.DROP) {
            log("{} drop held={} contraptionAlive={}", scene.name, held, contraption != null && contraption.isAlive());
        }
        if (scene.tail == Tail.DISARM && contraption != null && contraption.level() instanceof ServerLevel serverLevel) {
            log("{} after off tracked={} sableInitialized={} claimed={}",
                    scene.name,
                    ColliderFeedRuntime.tracked(serverLevel).size(),
                    ColliderFeedRuntime.sableInitialized(contraption),
                    ColliderFeedRuntime.isClaimed(contraption.getUUID().toString()));
        }
    }

    /** Turns the master switch off after the feed has claimed the elevator, so Sable's collider is what pushes. */
    private static void disarmFeed(ServerLevel level, Scene scene) {
        int before = ColliderFeedRuntime.pipelineBodies(level);
        try {
            level.getServer().getCommands().performPrefixedCommand(
                    level.getServer().createCommandSourceStack(),
                    "anticlip off"
            );
        } catch (RuntimeException ex) {
            log("{} anticlip off failed {}", scene.name, ex.toString());
        }
        int after = ColliderFeedRuntime.pipelineBodies(level);
        log("{} pipeline before={} after={}", scene.name, before, after);
        log("{} disarmed tracked={} sableInitialized={} claimed={}",
                scene.name,
                ColliderFeedRuntime.tracked(level).size(),
                contraption == null ? "none" : ColliderFeedRuntime.sableInitialized(contraption),
                contraption != null && ColliderFeedRuntime.isClaimed(contraption.getUUID().toString()));
    }

    private static boolean held(ServerSubLevel subLevel) {
        return subLevel != null && CarryRuntime.holding(subLevel.getUniqueId());
    }

    private static double yawDegrees(ServerSubLevel subLevel) {
        Quaterniondc orientation = subLevel.logicalPose().orientation();
        return Math.toDegrees(new Rot(orientation.x(), orientation.y(), orientation.z(), orientation.w()).yaw());
    }

    private static double deckDegrees(AbstractContraptionEntity entity) {
        if (entity instanceof ControlledContraptionEntity controlled && controlled.getRotationAxis() != null) {
            return controlled.getAngle(1.0f);
        }
        return 0.0;
    }

    /**
     * A ship held below min build height minus the configured margin is restored to the last
     * safe pose. Sable does not delete this case.
     */
    private static void bedrockCase(ServerLevel level) {
        flags(true, false, false);
        SafeLandingConfig.BEDROCK_RESCUE.set(true);
        int dwell = SafeLandingConfig.BEDROCK_TICKS.getAsInt();
        double line = level.getMinBuildHeight() - SafeLandingConfig.BEDROCK_MARGIN.getAsDouble() - 8.0;
        if (phaseTick == 0) {
            discardShip();
            bedrockNoted = false;
            ship = assembleCar(level, new BlockPos(24, surface + 2, 8), false);
            log("bedrock assembled {}", describe(ship));
        } else if (phaseTick == 8) {
            push(level, ship, line);
            log("bedrock pushed line={} {}", fmt(line), describe(ship));
        } else if (phaseTick == 20) {
            log("bedrock early {}", describe(ship));
        } else if (!bedrockNoted && phaseTick > 8 && ship != null && !ship.isRemoved()
                && ship.boundingBox().minY() > level.getMinBuildHeight() - 1.0) {
            bedrockNoted = true;
            org.joml.Vector3d velocity = ship.latestLinearVelocity;
            log("bedrock restored vel=({},{},{}) {}",
                    fmt(velocity.x), fmt(velocity.y), fmt(velocity.z), describe(ship));
        } else if (phaseTick == 8 + dwell + 8) {
            org.joml.Vector3d velocity = ship == null ? new org.joml.Vector3d() : ship.latestLinearVelocity;
            log("bedrock after dwell removed={} vel=({},{},{}) {}",
                    ship != null && ship.isRemoved(),
                    fmt(velocity.x), fmt(velocity.y), fmt(velocity.z),
                    describe(ship));
            discardShip();
            phase = Phase.SCENE;
            phaseTick = 0;
            sceneIndex = 0;
            return;
        }
        phaseTick++;
    }

    private static void activate(Scene scene) {
        contraption = attached(controller);
        if (contraption != null && contraption.isAlive()) {
            log("{} already assembled {}", scene.name, contraption);
            return;
        }
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
        // The deck has to be able to drop several blocks without hitting stone.
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
        // The pulley searches the first solid under itself, so the contact has to be that block.
        place(level, deck, "create:redstone_contact", Direction.SOUTH);
        log("elevator contact {}", level.getBlockState(deck));
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
        // A retracted piston counts poles on its back. One pole stops the head within a block.
        for (int i = 1; i <= 6; i++) {
            BlockPos pole = base.below(i);
            if (pole.getY() <= level.getMinBuildHeight()) {
                break;
            }
            place(level, pole, "create:piston_extension_pole", Direction.UP);
        }
        BlockPos deck = base.above();
        for (int y = 2; y <= 8; y++) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                level.setBlockAndUpdate(deck.offset(x, 0, z), Blocks.OAK_PLANKS.defaultBlockState());
            }
        }
        // Facing up with axis_along_first false puts the shaft on Z.
        // Positive generated speed retracts an upward piston, which is already retracted.
        motor(level, base.north(), Direction.SOUTH, -32);
        log("piston state {} pole {}", level.getBlockState(base), level.getBlockState(base.below()));
        return level.getBlockEntity(base);
    }

    private static void motor(ServerLevel level, BlockPos pos, Direction facing) {
        motor(level, pos, facing, 32);
    }

    private static void motor(ServerLevel level, BlockPos pos, Direction facing, int speed) {
        place(level, pos, "create:creative_motor", facing);
        if (level.getBlockEntity(pos) instanceof CreativeMotorBlockEntity motor && motor.generatedSpeed != null) {
            motor.generatedSpeed.setValue(speed);
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
            ServerSubLevel created = tryPhysicsAssembler(level, blocks.get(1), before);
            if (created != null) {
                return created;
            }
            log("physics assembler produced no sub-level; using Sable assembly");
        }
        BlockPos min = blocks.get(0);
        BlockPos max = blocks.get(blocks.size() - 1);
        BoundingBox3i bounds = new BoundingBox3i(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
        return SubLevelAssemblyHelper.assembleBlocks(level, origin, blocks, bounds);
    }

    private static ServerSubLevel tryPhysicsAssembler(ServerLevel level, BlockPos plank, List<ServerSubLevel> before) {
        BlockPos assemblerPos = plank.north();
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("simulated:physics_assembler"));
        if (block == null || block == Blocks.AIR) {
            log("physics assembler block is not registered");
            return null;
        }
        BlockState state = block.defaultBlockState();
        if (state.hasProperty(FaceAttachedHorizontalDirectionalBlock.FACE)) {
            state = state.setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.WALL);
        }
        if (state.hasProperty(FaceAttachedHorizontalDirectionalBlock.FACING)) {
            state = state.setValue(FaceAttachedHorizontalDirectionalBlock.FACING, Direction.NORTH);
        }
        level.setBlockAndUpdate(assemblerPos, state);
        BlockEntity assembler = level.getBlockEntity(assemblerPos);
        if (assembler == null) {
            log("physics assembler block entity missing, state {}", level.getBlockState(assemblerPos));
            return null;
        }
        try {
            invoke(assembler, "assembleOrDisassemble");
        } catch (ReflectiveOperationException ex) {
            log("physics assembler call failed {}", causeText(ex));
        }
        Object failure = readAssemblyException(assembler);
        if (failure != null) {
            log("physics assembler lastException {}", failure instanceof AssemblyException assembly && assembly.component != null
                    ? assembly.component.getString() : failure);
        }
        ServerSubLevel created = newest(before, ships(level));
        if (created != null) {
            log("physics assembler produced {}", created.getUniqueId());
            return created;
        }
        try {
            Class<?> helper = Class.forName("dev.simulated_team.simulated.util.SimAssemblyHelper");
            Method assemble = helper.getMethod(
                    "assembleFromSingleBlock",
                    net.minecraft.world.level.Level.class,
                    BlockPos.class,
                    BlockPos.class,
                    boolean.class,
                    boolean.class
            );
            Object result = assemble.invoke(null, level, assemblerPos, plank, Boolean.TRUE, Boolean.TRUE);
            log("physics assembler direct result {}", result);
        } catch (ReflectiveOperationException ex) {
            log("physics assembler direct call failed {}", causeText(ex));
        }
        created = newest(before, ships(level));
        if (created != null) {
            log("physics assembler direct produced {}", created.getUniqueId());
            return created;
        }
        level.setBlockAndUpdate(assemblerPos, Blocks.AIR.defaultBlockState());
        return null;
    }

    private static Object readAssemblyException(BlockEntity assembler) {
        try {
            Method method = assembler.getClass().getMethod("getLastAssemblyException");
            return method.invoke(assembler);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
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
        if (pilot != null) {
            pilot.xxa = 0.0f;
            pilot.yya = 0.0f;
            pilot.zza = 0.0f;
            pilot.setPos(0.0, -64.0, 0.0);
        }
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
        try {
            SafeLandingConfig.ENABLED.set(true);
            SafeLandingConfig.ENABLED.save();
        } catch (RuntimeException ex) {
            SableSafeLanding.LOGGER.warn("LIVE could not restore the master switch", ex);
        }
        log("stopping");
        server.halt(false);
    }
}
