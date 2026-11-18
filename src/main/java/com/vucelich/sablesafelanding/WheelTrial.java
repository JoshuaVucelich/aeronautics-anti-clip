package com.vucelich.sablesafelanding;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorColumn;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Scripted dedicated-server trial for wheels on a contraption deck. Enabled with
 * {@code -Dsable_safe_landing.wheels=true}. Builds a car with four Offroad wheel mounts and
 * tires, sets it over a Create elevator deck (and once over plain ground), lets physics settle,
 * and logs how far the hull sits above what is under it. Lines start with {@code WHEELS}.
 */
public final class WheelTrial {
    private static final boolean ENABLED = Boolean.getBoolean("sable_safe_landing.wheels");

    private record Case(String name, boolean deck, boolean wheelContact, int ride, int drive, int startX, int turn,
                        boolean heavy, int rpm) {
    }

    private static final Case[] CASES = {
            new Case("ground-on", false, true, 0, 0, 0, 0, false, 32),
            new Case("deck-on", true, true, 0, 0, 0, 0, false, 32),
            new Case("deck-off", true, false, 0, 0, 0, 0, false, 32),
            new Case("ride-down-on", true, true, -1, 0, 0, 0, false, 32),
            new Case("ride-up-on", true, true, 1, 0, 0, 0, false, 32),
            new Case("drive-off-deck-on", true, true, 0, 8, 0, 0, false, 32),
            new Case("drive-off-deck-off", true, false, 0, 8, 0, 0, false, 32),
            new Case("drive-onto-deck-on", true, true, 0, 8, -9, 0, false, 32),
            new Case("drive-onto-deck-off", true, false, 0, 8, -9, 0, false, 32),
            new Case("turntable-on", true, true, 0, 0, 0, 4, false, 32),
            new Case("turntable-off", true, false, 0, 0, 0, 4, false, 32),
            new Case("drive-while-moving-on", true, true, -1, 8, 0, 0, false, 32),
            new Case("drive-while-moving-off", true, false, -1, 8, 0, 0, false, 32),
            new Case("turntable-offcenter-on", true, true, 0, 0, 2, 4, false, 32),
            new Case("turntable-offcenter-off", true, false, 0, 0, 2, 4, false, 32),
            // A big, heavy machine on large tires, riding an 11x11 elevator 8 blocks at several pulley speeds.
            new Case("heavy-up-32", true, true, 2, 0, 0, 0, true, 32),
            new Case("heavy-down-32", true, true, -2, 0, 0, 0, true, 32),
            new Case("heavy-up-128", true, true, 2, 0, 0, 0, true, 128),
            new Case("heavy-down-128", true, true, -2, 0, 0, 0, true, 128),
            new Case("heavy-up-256", true, true, 2, 0, 0, 0, true, 256),
            new Case("heavy-down-256", true, true, -2, 0, 0, 0, true, 256),
            new Case("heavy-up-256-off", true, false, 2, 0, 0, 0, true, 256),
            // The real server's elevator stepped 0.025 blocks a tick, which is 16 rpm here.
            new Case("heavy-up-16", true, true, 2, 0, 0, 0, true, 16),
            new Case("heavy-down-16", true, true, -2, 0, 0, 0, true, 16),
            // The real server's sequence: while carried, the ship is flung sideways at 113 blocks
            // per second (spike), then skids across a wide deck at 50 blocks per second for six
            // ticks before friction takes over. rc.6 and earlier captured it mid-skid and released
            // it as separated. The deck is 27x27 so the skid stays on it.
            new Case("heavy-fling-16", true, true, 2, 0, 10, 0, true, 16)
    };
    private static final boolean HEAVY_ONLY = Boolean.getBoolean("sable_safe_landing.heavyOnly");
    /** Comma-separated case names to run, or empty for all. */
    private static final String ONLY = System.getProperty("sable_safe_landing.cases", "");
    private static final Case[] RUN = java.util.Arrays.stream(CASES)
            .filter(c -> !HEAVY_ONLY || c.heavy())
            .filter(c -> ONLY.isEmpty() || java.util.Arrays.asList(ONLY.split(",")).contains(c.name()))
            .toArray(Case[]::new);
    private static final int HEAVY_DECK = 5;
    private static final int HEAVY_RIDE = 200;
    /** Ticks for an 8 block ride at 16 rpm (0.025 blocks a tick), with room to settle. */
    private static final int SLOW_RIDE = 400;
    private static final int FLING_DECK = 13;
    private static final int FLING_AT = 60;
    private static final double FLING_SPEED = 113.0;
    private static final double SKID_SPEED = 50.0;
    private static final int SKID_TICKS = 6;

    private static final int SPACING = 40;
    private static final int DECK = 3;
    private static final int SETTLE = 60;
    private static final int RIDE = 80;
    private static final int DRIVE = 100;

    private static int index;
    private static int tick;
    private static int surface = Integer.MIN_VALUE;
    private static BlockEntity controller;
    private static AbstractContraptionEntity contraption;
    private static ServerSubLevel ship;
    private static long hitsAtStart;
    private static double startX;
    private static double minGapOnDeck;
    private static double startYaw;
    private static double startDeck;
    private static boolean done;
    private static double settledGap;
    private static double rideMinGap;
    private static double rideMaxGap;
    private static double rideMaxLag;
    private static int rideHeldTicks;
    private static double rideDeckStart;
    private static double rideShipStart;

    private WheelTrial() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void onTick(ServerTickEvent.Post event) {
        if (!ENABLED || done) {
            return;
        }
        MinecraftServer server = event.getServer();
        try {
            if (server.getTickCount() > 12000) {
                log("timeout tick {}", server.getTickCount());
                finish(server);
                return;
            }
            ServerLevel level = server.overworld();
            if (surface == Integer.MIN_VALUE) {
                SafeLandingConfig.ENABLED.set(true);
                SafeLandingConfig.FEED_COLLIDERS.set(true);
                SafeLandingConfig.CARRY.set(true);
                int lastChunk = (24 + RUN.length * SPACING + 16) >> 4;
                for (int cx = -1; cx <= lastChunk; cx++) {
                    for (int cz = -1; cz <= 2; cz++) {
                        level.setChunkForced(cx, cz, true);
                        level.getChunk(cx, cz);
                    }
                }
                surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 8, 8);
                log("surface y {}", surface);
                tick = -20;
            }
            if (tick < 0) {
                tick++;
                return;
            }
            if (index >= RUN.length) {
                log("done deckHits={}", WheelContactRuntime.deckHits());
                finish(server);
                return;
            }
            step(level, RUN[index]);
        } catch (RuntimeException ex) {
            SableSafeLanding.LOGGER.error("WHEELS trial failed", ex);
            finish(server);
        }
    }

    private static void step(ServerLevel level, Case c) {
        int origin = 24 + index * SPACING;
        BlockPos base = new BlockPos(origin, surface, 8);
        if (tick == 0) {
            SafeLandingConfig.WHEEL_CONTACT.set(c.wheelContact);
            clear(level, base);
            build(level, base, c);
        } else if (c.deck && tick == 12) {
            float speed = controller instanceof KineticBlockEntity kinetic ? kinetic.getSpeed() : 0.0f;
            try {
                LiveTrial.invoke(controller, "assemble");
            } catch (ReflectiveOperationException ex) {
                log("{} assemble failed {}", c.name, LiveTrial.causeText(ex));
            }
            contraption = LiveTrial.attached(controller);
            log("{} pulley speed {} contraption alive={} blocks={}", c.name, speed, contraption != null && contraption.isAlive(),
                    contraption == null || contraption.getContraption() == null ? 0 : contraption.getContraption().getBlocks().size());
        } else if (tick == 20) {
            if (c.deck && (contraption == null || !contraption.isAlive())) {
                log("{} no contraption; skipping", c.name);
                next(level, base);
                return;
            }
            ship = c.heavy
                    ? assembleHeavy(level, base.offset(c.startX, 4, 0))
                    : assembleWheelCar(level, base.offset(c.startX, 3, 0), c.drive);
            hitsAtStart = WheelContactRuntime.deckHits();
            startX = ship == null ? Double.NaN : ship.logicalPose().position().x();
            startYaw = ship == null ? Double.NaN : LiveTrial.yawDegrees(ship);
            startDeck = LiveTrial.deckDegrees(contraption);
            minGapOnDeck = Double.POSITIVE_INFINITY;
            log("{} car {} supportTop {}", c.name, describe(ship), LiveTrial.fmt(supportTop(base, c)));
        } else if (tick > 20 && ship != null) {
            int t = tick - 20;
            wake(level);
            double gap = gap(base, c);
            double relX = ship.isRemoved() ? Double.NaN : ship.logicalPose().position().x() - base.getX();
            // Only count the gap once the car has dropped onto its wheels, and only over the deck.
            if (t >= 10 && Math.abs(relX) <= DECK - 0.5 && Double.isFinite(gap)) {
                minGapOnDeck = Math.min(minGapOnDeck, gap);
            }
            if (c.turn != 0 && t % 10 == 0) {
                log("{} t={} yaw={} deck={} held={}", c.name, t, LiveTrial.fmt(LiveTrial.yawDegrees(ship)),
                        LiveTrial.fmt(LiveTrial.deckDegrees(contraption)), LiveTrial.held(ship));
            }
            if (c.drive != 0 ? t % 10 == 0 : t % 20 == 0) {
                log("{} t={} relX={} gap={} hits={} {} {}", c.name, t, LiveTrial.fmt(relX), LiveTrial.fmt(gap),
                        WheelContactRuntime.deckHits() - hitsAtStart, describe(ship), physics(level));
            }
            if (c.drive != 0 && c.ride != 0 && t == 1) {
                // The motors are already turning. Start the elevator under the moving car.
                callElevator(c);
            }
            if (c.drive != 0 && c.ride != 0 && t % 10 == 0) {
                log("{} t={} held={} anchorY={}", c.name, t, LiveTrial.held(ship),
                        contraption == null ? "none" : LiveTrial.fmt(contraption.getAnchorVec().y));
            }
            if (c.drive != 0 && ship.isRemoved()) {
                log("{} RESULT wheelContact={} ship removed at t={}", c.name, c.wheelContact, t);
                next(level, base);
                return;
            }
            if (c.drive != 0 && t == DRIVE) {
                log("{} RESULT wheelContact={} movedX={} endRelX={} endGap={} minGapOverDeck={} hits={} held={} anchorY={} {}", c.name, c.wheelContact,
                        LiveTrial.fmt(ship.logicalPose().position().x() - startX), LiveTrial.fmt(relX), LiveTrial.fmt(gap),
                        LiveTrial.fmt(minGapOnDeck), WheelContactRuntime.deckHits() - hitsAtStart, LiveTrial.held(ship),
                        contraption == null ? "none" : LiveTrial.fmt(contraption.getAnchorVec().y), describe(ship));
                next(level, base);
                return;
            }
            if (c.turn != 0 && t == RIDE) {
                double dYaw = LiveTrial.yawDegrees(ship) - startYaw;
                double dDeck = LiveTrial.deckDegrees(contraption) - startDeck;
                double relZ = ship.logicalPose().position().z() - base.getZ() - 0.5;
                log("{} RESULT wheelContact={} dYaw={} dDeck={} offAxis={} gap={} held={} hits={}", c.name, c.wheelContact,
                        LiveTrial.fmt(net.minecraft.util.Mth.wrapDegrees(dYaw)), LiveTrial.fmt(net.minecraft.util.Mth.wrapDegrees(dDeck)),
                        LiveTrial.fmt(Math.hypot(relX - 0.5, relZ)), LiveTrial.fmt(gap), LiveTrial.held(ship),
                        WheelContactRuntime.deckHits() - hitsAtStart);
                next(level, base);
                return;
            }
            if (c.drive == 0 && c.turn == 0 && t == SETTLE) {
                log("{} RESULT wheelContact={} settledGap={} hits={} {}", c.name, c.wheelContact, LiveTrial.fmt(gap),
                        WheelContactRuntime.deckHits() - hitsAtStart, physics(level));
                settledGap = gap;
                rideMinGap = Double.POSITIVE_INFINITY;
                rideMaxGap = Double.NEGATIVE_INFINITY;
                rideMaxLag = 0.0;
                rideHeldTicks = 0;
                rideDeckStart = contraption == null ? Double.NaN : contraption.getAnchorVec().y;
                rideShipStart = ship.isRemoved() ? Double.NaN : ship.logicalPose().position().y();
                if (c.ride == 0) {
                    next(level, base);
                    return;
                }
                callElevator(c);
            }
            if (c.ride != 0 && t > SETTLE && contraption != null) {
                if (fling(c)) {
                    flingStep(level, c, t - SETTLE);
                }
                trackRide(level, c, t, gap);
            }
            if (c.ride != 0 && t == SETTLE + rideTicks(c)) {
                double deckMoved = contraption == null ? Double.NaN : contraption.getAnchorVec().y - rideDeckStart;
                double shipMoved = ship.isRemoved() ? Double.NaN : ship.logicalPose().position().y() - rideShipStart;
                double endRelX = ship.isRemoved() ? Double.NaN : ship.logicalPose().position().x() - base.getX();
                log("{} RESULT after ride rpm={} relX={} gap={} settledGap={} minGap={} maxGap={} maxLag={} deckMoved={} shipMoved={} heldTicks={} hits={} anchorY={} {}",
                        c.name, c.rpm, LiveTrial.fmt(endRelX), LiveTrial.fmt(gap), LiveTrial.fmt(settledGap), LiveTrial.fmt(rideMinGap), LiveTrial.fmt(rideMaxGap),
                        LiveTrial.fmt(rideMaxLag), LiveTrial.fmt(deckMoved), LiveTrial.fmt(shipMoved), rideHeldTicks,
                        WheelContactRuntime.deckHits() - hitsAtStart,
                        contraption == null ? "none" : LiveTrial.fmt(contraption.getAnchorVec().y), describe(ship));
                next(level, base);
                return;
            }
        }
        tick++;
    }

    /**
     * Stone roads at deck height on both sides along X. For deck cases, a glued 7x7 elevator
     * deck in a shaft between them; otherwise the whole strip is stone.
     */
    private static void build(ServerLevel level, BlockPos base, Case c) {
        if (c.turn != 0) {
            // A 7x7 glued turntable on a bearing at ground level, turned slowly by a motor below.
            LiveTrial.place(level, base, "create:mechanical_bearing", Direction.UP);
            for (int x = -DECK; x <= DECK; x++) {
                for (int z = -DECK; z <= DECK; z++) {
                    level.setBlockAndUpdate(base.offset(x, 1, z), Blocks.OAK_PLANKS.defaultBlockState());
                }
            }
            BlockPos lo = base.offset(-DECK, 1, -DECK);
            int size = DECK * 2 + 1;
            level.addFreshEntity(new com.simibubi.create.content.contraptions.glue.SuperGlueEntity(level,
                    new net.minecraft.world.phys.AABB(lo.getX(), lo.getY(), lo.getZ(), lo.getX() + size, lo.getY() + 1, lo.getZ() + size)));
            LiveTrial.motor(level, base.below(), Direction.UP, c.turn);
            controller = level.getBlockEntity(base);
            return;
        }
        int half = deckHalf(c);
        int roadZ = Math.max(5, half + 1);
        int dig = c.heavy ? -10 : -6;
        for (int x = -14; x <= 14; x++) {
            for (int z = -roadZ; z <= roadZ; z++) {
                boolean deckSpot = Math.abs(x) <= half && Math.abs(z) <= half;
                if (deckSpot && c.deck) {
                    for (int y = dig; y <= 0; y++) {
                        BlockPos dug = base.offset(x, y, z);
                        if (dug.getY() > level.getMinBuildHeight()) {
                            level.setBlockAndUpdate(dug, Blocks.AIR.defaultBlockState());
                        }
                    }
                } else {
                    level.setBlockAndUpdate(base.offset(x, 1, z), Blocks.STONE.defaultBlockState());
                }
            }
        }
        if (!c.deck) {
            return;
        }
        controller = c.heavy ? elevatorAt(level, base, c.rpm) : LiveTrial.elevator(level, base);
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                if (x == 0 && z == 0) {
                    continue;
                }
                level.setBlockAndUpdate(base.offset(x, 1, z), Blocks.OAK_PLANKS.defaultBlockState());
            }
        }
        LiveTrial.place(level, base.offset(0, 1, 0), "create:redstone_contact", Direction.SOUTH);
        // Glue the deck together the way a player would, so the whole deck moves.
        BlockPos lo = base.offset(-half, 1, -half);
        int size = half * 2 + 1;
        level.addFreshEntity(new com.simibubi.create.content.contraptions.glue.SuperGlueEntity(level,
                new net.minecraft.world.phys.AABB(lo.getX(), lo.getY(), lo.getZ(), lo.getX() + size, lo.getY() + 1, lo.getZ() + size)));
    }

    private static void next(ServerLevel level, BlockPos base) {
        clear(level, base);
        index++;
        tick = 0;
    }

    /** Top face of what the wheels stand on. Roads and the idle deck share one height. */
    private static double supportTop(BlockPos base, Case c) {
        if (c.ride != 0 && contraption != null && contraption.isAlive()) {
            return contraption.getBoundingBox().maxY;
        }
        return base.getY() + 2.0;
    }

    private static double gap(BlockPos base, Case c) {
        if (ship == null || ship.isRemoved()) {
            return Double.NaN;
        }
        ship.updateBoundingBox();
        return ship.boundingBox().minY() - supportTop(base, c);
    }

    private static void callElevator(Case c) {
        if (!(controller instanceof ElevatorPulleyBlockEntity pulley) || pulley.movedContraption == null
                || !(pulley.movedContraption.getContraption() instanceof ElevatorContraption elevator)) {
            log("{} elevator missing", c.name);
            return;
        }
        ElevatorColumn column = ElevatorColumn.get(pulley.getLevel(), elevator.getGlobalColumn());
        if (column == null || !column.isTargetAvailable()) {
            log("{} elevator column missing", c.name);
            return;
        }
        int current = column.getTargetedYLevel();
        int target = current + c.ride * 4;
        if (elevator.isTargetUnreachable(target)) {
            log("{} target {} unreachable from {}", c.name, target, current);
            return;
        }
        column.target(target);
        log("{} elevator target {} -> {}", c.name, current, target);
    }

    private static int rideTicks(Case c) {
        return c.heavy ? (c.rpm <= 16 ? SLOW_RIDE : HEAVY_RIDE) : RIDE;
    }

    private static int deckHalf(Case c) {
        return fling(c) ? FLING_DECK : c.heavy ? HEAVY_DECK : DECK;
    }

    private static boolean fling(Case c) {
        return c.name.contains("fling");
    }

    /**
     * Scripted fling: one tick at {@link #FLING_SPEED} sideways, then {@link #SKID_TICKS} ticks
     * held at {@link #SKID_SPEED}, the way the real ship crossed its deck. Vertical speed is kept.
     */
    private static void flingStep(ServerLevel level, Case c, int r) {
        double speed;
        if (r == FLING_AT) {
            speed = FLING_SPEED;
        } else if (r > FLING_AT && r <= FLING_AT + SKID_TICKS) {
            speed = SKID_SPEED;
        } else {
            return;
        }
        SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
        if (system == null || ship == null || ship.isRemoved()) {
            return;
        }
        org.joml.Vector3d body = system.getPipeline().getLinearVelocity(ship, new org.joml.Vector3d());
        double vy = body == null || !Double.isFinite(body.y) ? 0.0 : body.y;
        system.getPipeline().wakeUp(ship);
        system.getPipeline().resetVelocity(ship);
        system.getPipeline().addLinearAndAngularVelocity(ship, new org.joml.Vector3d(-speed, vy, 0.0), new org.joml.Vector3d());
        log("{} fling r={} vx={} relX={} held={}", c.name, r, LiveTrial.fmt(-speed),
                LiveTrial.fmt(ship.logicalPose().position().x() - (24 + index * SPACING)), LiveTrial.held(ship));
    }

    /** Per-tick ride samples: the gap to the deck and how far the ship trails the deck's travel. */
    private static void trackRide(ServerLevel level, Case c, int t, double gap) {
        if (ship.isRemoved() || !Double.isFinite(gap)) {
            return;
        }
        rideMinGap = Math.min(rideMinGap, gap);
        rideMaxGap = Math.max(rideMaxGap, gap);
        double lag = Math.abs(gap - settledGap);
        rideMaxLag = Math.max(rideMaxLag, lag);
        boolean held = LiveTrial.held(ship);
        if (held) {
            rideHeldTicks++;
        }
        if (t % 5 == 0) {
            log("{} ride t={} gap={} deckY={} shipY={} held={} {}", c.name, t, LiveTrial.fmt(gap),
                    LiveTrial.fmt(contraption.getAnchorVec().y), LiveTrial.fmt(ship.logicalPose().position().y()), held, physics(level));
        }
    }

    /**
     * A big, heavy machine: a 5x7 plank floor with three layers of iron blocks on top (about
     * 135 blocks), and four wheel mounts with large tires at the corners (z -3 facing north,
     * z +3 facing south), so the wheels sit at z -4 and z +4.
     */
    static ServerSubLevel assembleHeavy(ServerLevel level, BlockPos center) {
        List<BlockPos> blocks = new ArrayList<>();
        Item tire = BuiltInRegistries.ITEM.get(ResourceLocation.parse("offroad:large_tire"));
        for (int x = -2; x <= 2; x++) {
            for (int z = -3; z <= 3; z++) {
                BlockPos pos = center.offset(x, 0, z);
                boolean corner = Math.abs(x) == 2 && Math.abs(z) == 3;
                if (corner) {
                    LiveTrial.place(level, pos, "offroad:wheel_mount", z < 0 ? Direction.NORTH : Direction.SOUTH);
                    BlockEntity mount = level.getBlockEntity(pos);
                    if (mount != null && tire != null && tire != Items.AIR) {
                        CompoundTag tag = mount.saveWithoutMetadata(level.registryAccess());
                        tag.put("CurrentStack", new ItemStack(tire).save(level.registryAccess()));
                        mount.loadWithComponents(tag, level.registryAccess());
                        mount.setChanged();
                    }
                } else {
                    level.setBlockAndUpdate(pos, Blocks.OAK_PLANKS.defaultBlockState());
                }
                blocks.add(pos.immutable());
                for (int y = 1; y <= 3; y++) {
                    BlockPos up = pos.above(y);
                    level.setBlockAndUpdate(up, Blocks.IRON_BLOCK.defaultBlockState());
                    blocks.add(up.immutable());
                }
            }
        }
        BoundingBox3i bounds = new BoundingBox3i(center.getX() - 2, center.getY(), center.getZ() - 3,
                center.getX() + 2, center.getY() + 3, center.getZ() + 3);
        return SubLevelAssemblyHelper.assembleBlocks(level, center, blocks, bounds);
    }

    /** Same pulley layout as the live trial, with the pulley motor at {@code rpm}. */
    static BlockEntity elevatorAt(ServerLevel level, BlockPos base, int rpm) {
        BlockPos pulleyPos = base.offset(0, 20, 0);
        LiveTrial.place(level, pulleyPos, "create:elevator_pulley", Direction.NORTH);
        LiveTrial.motor(level, pulleyPos.west(), Direction.EAST, rpm);
        return level.getBlockEntity(pulleyPos);
    }

    /**
     * Five rows along Z, three blocks along X. Wheel mounts at the four corners (z -2 facing
     * north, z +2 facing south) put the wheels at z -3 and z +3. With {@code drive} set, creative
     * motors behind each mount turn the wheels; the south motors spin the other way so both
     * sides push the same direction.
     */
    private static ServerSubLevel assembleWheelCar(ServerLevel level, BlockPos center, int drive) {
        List<BlockPos> blocks = new ArrayList<>();
        for (int z = -2; z <= 2; z++) {
            BlockPos pos = center.offset(0, 0, z);
            level.setBlockAndUpdate(pos, Blocks.OAK_PLANKS.defaultBlockState());
            blocks.add(pos.immutable());
        }
        for (int x : new int[]{-1, 1}) {
            BlockPos mid = center.offset(x, 0, 0);
            level.setBlockAndUpdate(mid, Blocks.OAK_PLANKS.defaultBlockState());
            blocks.add(mid.immutable());
            for (int side : new int[]{-1, 1}) {
                BlockPos behind = center.offset(x, 0, side);
                Direction out = side < 0 ? Direction.NORTH : Direction.SOUTH;
                if (drive != 0) {
                    LiveTrial.motor(level, behind, out, side < 0 ? drive : -drive);
                } else {
                    level.setBlockAndUpdate(behind, Blocks.OAK_PLANKS.defaultBlockState());
                }
                blocks.add(behind.immutable());
            }
        }
        Item tire = BuiltInRegistries.ITEM.get(ResourceLocation.parse("offroad:tire"));
        if (tire == null || tire == Items.AIR) {
            log("offroad:tire missing");
        }
        for (int x : new int[]{-1, 1}) {
            for (int z : new int[]{-2, 2}) {
                BlockPos pos = center.offset(x, 0, z);
                LiveTrial.place(level, pos, "offroad:wheel_mount", z < 0 ? Direction.NORTH : Direction.SOUTH);
                BlockEntity mount = level.getBlockEntity(pos);
                if (mount != null && tire != null && tire != Items.AIR) {
                    CompoundTag tag = mount.saveWithoutMetadata(level.registryAccess());
                    tag.put("CurrentStack", new ItemStack(tire).save(level.registryAccess()));
                    mount.loadWithComponents(tag, level.registryAccess());
                    mount.setChanged();
                }
                blocks.add(pos.immutable());
            }
        }
        BoundingBox3i bounds = new BoundingBox3i(center.getX() - 1, center.getY(), center.getZ() - 2,
                center.getX() + 1, center.getY(), center.getZ() + 2);
        return SubLevelAssemblyHelper.assembleBlocks(level, center, blocks, bounds);
    }

    private static String held(BlockEntity mount) {
        if (mount == null) {
            return "none";
        }
        try {
            java.lang.reflect.Method method = mount.getClass().getMethod("getHeldItem");
            return String.valueOf(method.invoke(mount));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return "unknown " + ex;
        }
    }

    private static String physics(ServerLevel level) {
        SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
        if (system == null || ship == null || ship.isRemoved()) {
            return "physics=none";
        }
        org.joml.Vector3d v = system.getPipeline().getLinearVelocity(ship, new org.joml.Vector3d());
        boolean listed = false;
        if (dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level) instanceof dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container) {
            listed = container.getAllSubLevels().contains(ship);
        }
        StringBuilder wheels = new StringBuilder();
        try {
            for (Object actor : ship.getPlot().getBlockEntityActors()) {
                if (actor.getClass().getSimpleName().equals("WheelMountBlockEntity")) {
                    java.lang.reflect.Field ext = actor.getClass().getDeclaredField("extension");
                    ext.setAccessible(true);
                    wheels.append(LiveTrial.fmt(ext.getDouble(actor))).append(' ');
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            wheels.append("err ").append(ex);
        }
        return "ext=[" + wheels.toString().trim() + "] vel=" + LiveTrial.fmt(v.x) + "," + LiveTrial.fmt(v.y) + "," + LiveTrial.fmt(v.z)
                + " paused=" + system.getPaused() + " listed=" + listed;
    }

    private static void wake(ServerLevel level) {
        SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
        if (system != null && ship != null && !ship.isRemoved()) {
            system.getPipeline().wakeUp(ship);
        }
    }

    private static void clear(ServerLevel level, BlockPos base) {
        if (ship != null && !ship.isRemoved()) {
            ship.markRemoved();
        }
        ship = null;
        if (controller instanceof LinearActuatorBlockEntity actuator) {
            actuator.disassemble();
        } else if (controller instanceof com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity bearing) {
            bearing.disassemble();
        }
        if (contraption != null) {
            contraption.discard();
        }
        contraption = null;
        controller = null;
        for (int y = 1; y <= 24; y++) {
            for (int x = -16; x <= 16; x++) {
                for (int z = -14; z <= 14; z++) {
                    level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static String describe(ServerSubLevel subLevel) {
        if (subLevel == null) {
            return "ship=none";
        }
        if (subLevel.isRemoved()) {
            return "ship=removed";
        }
        BoundingBox3dc box = subLevel.boundingBox();
        return "minY=" + LiveTrial.fmt(box.minY()) + " maxY=" + LiveTrial.fmt(box.maxY())
                + " pos=" + LiveTrial.fmt(subLevel.logicalPose().position().x())
                + "," + LiveTrial.fmt(subLevel.logicalPose().position().y())
                + "," + LiveTrial.fmt(subLevel.logicalPose().position().z());
    }

    private static void log(String message, Object... args) {
        SableSafeLanding.LOGGER.info("WHEELS " + message, args);
    }

    private static void finish(MinecraftServer server) {
        done = true;
        try {
            SafeLandingConfig.WHEEL_CONTACT.set(true);
            SafeLandingConfig.ENABLED.set(true);
        } catch (RuntimeException ex) {
            SableSafeLanding.LOGGER.warn("WHEELS could not restore the switches", ex);
        }
        log("stopping");
        server.halt(false);
    }
}
