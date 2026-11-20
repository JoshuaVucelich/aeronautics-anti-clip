package com.vucelich.sablesafelanding;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorColumn;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dedicated-server stress trial. Enabled with {@code -Dsable_safe_landing.stress=true}.
 * A large glued elevator contraption ({@code stress.half} and {@code stress.layers} set its size),
 * a heavy wheeled ship parked on the ground away from it, and a fake player standing on the deck.
 * Phases: idle-still (nothing on the elevator, elevator stopped), idle-moving (nothing on it,
 * elevator moving), settle (a second heavy ship dropped on the stopped deck), ride (the elevator
 * carries it down). Each phase logs server tick time and this mod's own time. Lines start with
 * {@code STRESS}. {@code stress.modOff=true} runs the same script with the master switch off.
 */
public final class StressTrial {
    private static final boolean ENABLED = Boolean.getBoolean("sable_safe_landing.stress");
    private static final int HALF = Integer.getInteger("sable_safe_landing.stress.half", 40);
    private static final int LAYERS = Integer.getInteger("sable_safe_landing.stress.layers", 6);
    private static final int RPM = Integer.getInteger("sable_safe_landing.stress.rpm", 64);
    private static final boolean MOD_OFF = Boolean.getBoolean("sable_safe_landing.stress.modOff");
    private static final int COUNT = Integer.getInteger("sable_safe_landing.stress.count", 0);
    private static final int SMALL_HALF = Integer.getInteger("sable_safe_landing.stress.smallHalf", 8);
    private static final int SHIPS = Integer.getInteger("sable_safe_landing.stress.ships", 0);
    private static final int CHURN = Integer.getInteger("sable_safe_landing.stress.churn", 0);
    private static final int SPACING = 24;
    private static final boolean RELOAD = Boolean.getBoolean("sable_safe_landing.stress.reload");
    private static final List<BlockEntity> SMALL = new ArrayList<>();
    private static final long WALL_LIMIT_NANOS = Long.getLong("sable_safe_landing.stress.wallSeconds", 1500L) * 1_000_000_000L;

    private static final int IDLE_STILL = 40;
    private static final int IDLE_MOVING = 240;
    private static final int DROP = 440;
    private static final int RIDE = 500;
    private static final int END = 800;

    private static int tick = -20;
    private static int surface = Integer.MIN_VALUE;
    private static boolean done;
    private static long tickStart;
    private static long wallStart;
    private static BlockPos base;
    private static BlockEntity controller;
    private static AbstractContraptionEntity contraption;
    private static ServerSubLevel parked;
    private static ServerSubLevel rider;
    private static FakePlayer player;
    private static String phase = "setup";
    private static final List<Long> TICK_NANOS = new ArrayList<>();
    private static final List<Long> MOD_NANOS = new ArrayList<>();
    private static double settledGap = Double.NaN;
    private static double rideMinGap = Double.POSITIVE_INFINITY;
    private static double rideMaxGap = Double.NEGATIVE_INFINITY;
    private static double rideMaxLag;
    private static int heldTicks;
    private static int drops;
    private static boolean wasHeld;
    private static double anchorAtPhase = Double.NaN;

    private StressTrial() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void onTickStart(ServerTickEvent.Pre event) {
        if (ENABLED && !done) {
            tickStart = System.nanoTime();
        }
    }

    public static void onTick(ServerTickEvent.Post event) {
        if (!ENABLED || done) {
            return;
        }
        long used = PerfMeter.lastTickNanos();
        long tickNanos = System.nanoTime() - tickStart;
        MinecraftServer server = event.getServer();
        if (wallStart == 0L) {
            wallStart = System.nanoTime();
        }
        try {
            if (!phase.equals("setup")) {
                TICK_NANOS.add(tickNanos);
                MOD_NANOS.add(used);
            }
            long[] parts = PerfMeter.parts();
            if (used > 2_000_000L && !phase.equals("setup")) {
                StringBuilder split = new StringBuilder();
                for (PerfMeter.Part part : PerfMeter.Part.values()) {
                    split.append(' ').append(part.name()).append('=')
                            .append((parts[part.ordinal()] - partsLastTick[part.ordinal()]) / 1_000L);
                }
                log("mod busy tick {} phase={} us={} tickMs={} budgetUs={}{}", tick, phase, used / 1_000L, tickNanos / 1_000_000L,
                        CarryRuntime.budgetSpentNanos("minecraft:overworld") / 1_000L, split);
            }
            partsLastTick = parts;
            if (tickNanos > 50_000_000L && !phase.equals("setup")) {
                log("slow tick {} phase={} ms={}", tick, phase, tickNanos / 1_000_000L);
            }
            if (System.nanoTime() - wallStart > WALL_LIMIT_NANOS) {
                log("wall limit at tick {} phase={}", tick, phase);
                endPhase();
                finish(server);
                return;
            }
            step(server.overworld());
        } catch (RuntimeException ex) {
            SableSafeLanding.LOGGER.error("STRESS trial failed", ex);
            finish(server);
        }
        tick++;
    }

    private static void step(ServerLevel level) {
        if (surface == Integer.MIN_VALUE) {
            SafeLandingConfig.ENABLED.set(!MOD_OFF);
            SafeLandingConfig.FEED_COLLIDERS.set(true);
            SafeLandingConfig.CARRY.set(true);
            SafeLandingConfig.WHEEL_CONTACT.set(true);
            int reach = (HALF + 90) >> 4;
            int far = COUNT > 0 || SHIPS > 0 ? (HALF + 100 + 8 * SPACING + 16) >> 4 : reach;
            int zr = COUNT > 0 || SHIPS > 0 ? Math.max((HALF >> 4) + 2, 7) : (HALF >> 4) + 2;
            for (int cx = -reach; cx <= far; cx++) {
                for (int cz = -zr; cz <= zr; cz++) {
                    level.setChunkForced(cx, cz, true);
                    level.getChunk(cx, cz);
                }
            }
            surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0);
            base = new BlockPos(0, surface + 30, 0);
            log("surface y {} modOff={} half={} layers={} rpm={} count={} ships={} churn={} reload={}",
                    surface, MOD_OFF, HALF, LAYERS, RPM, COUNT, SHIPS, CHURN, RELOAD);
            if (RELOAD) {
                startPhase("reload");
            }
            return;
        }
        if (tick < 0) {
            return;
        }
        if (RELOAD) {
            if (tick == 0) {
                log("reload contraptions live={} decks={}", LiveContraptions.in(level).size(), Decks.of(level).size());
            }
            if (tick == 1300) {
                endPhase();
                log("RESULT reload live={} uploads={} deferredUploads={}", LiveContraptions.in(level).size(),
                        ColliderFeedRuntime.uploads(), ColliderFeedRuntime.deferredUploads());
                log("RESULT mod overall meanUs={} worstUs={}", String.format(java.util.Locale.ROOT, "%.1f", PerfMeter.meanMicros()), PerfMeter.worstMicros());
                finish(level.getServer());
            }
            return;
        }
        if (tick == 0) {
            build(level);
            buildSmall(level);
        } else if (tick == 12) {
            startPhase("assembly");
            try {
                LiveTrial.invoke(controller, "assemble");
            } catch (ReflectiveOperationException ex) {
                log("assemble failed {}", LiveTrial.causeText(ex));
            }
            contraption = LiveTrial.attached(controller);
            long smallBlocks = assembleSmall();
            log("small contraptions={} blocks={} live={}", SMALL.size(), smallBlocks, LiveContraptions.in(level).size());
            int blocks = contraption == null || contraption.getContraption() == null ? 0 : contraption.getContraption().getBlocks().size();
            log("contraption alive={} blocks={}", contraption != null && contraption.isAlive(), blocks);
        } else if (tick == 20) {
            BlockPos pad = new BlockPos(HALF + 60, surface, 0);
            for (int x = -6; x <= 6; x++) {
                for (int z = -6; z <= 6; z++) {
                    level.setBlockAndUpdate(pad.offset(x, 1, z), Blocks.STONE.defaultBlockState());
                }
            }
            parked = WheelTrial.assembleHeavy(level, pad.offset(0, 4, 0));
            for (int k = 0; k < SHIPS; k++) {
                BlockPos p2 = new BlockPos(HALF + 60, surface, -90 + k * 12);
                for (int x = -4; x <= 4; x++) {
                    for (int z = -5; z <= 5; z++) {
                        level.setBlockAndUpdate(p2.offset(x, 1, z), Blocks.STONE.defaultBlockState());
                    }
                }
                WheelTrial.assembleHeavy(level, p2.offset(0, 4, 0));
            }
            log("extra parked ships {}", SHIPS);
            player = FakePlayerFactory.getMinecraft(level);
            player.setPos(base.getX() + HALF - 1.5, base.getY() + LAYERS + 1.0, base.getZ() + 0.5);
            log("parked ship {} fake player on deck at {}", parked == null ? "none" : parked.getUniqueId(), player.position());
        }
        if (tick == IDLE_STILL) {
            endPhase();
            startPhase("idle-still");
        } else if (tick == IDLE_MOVING) {
            endPhase();
            startPhase("idle-moving");
            callElevator(3);
            callSmall(3);
        } else if (tick == DROP) {
            endPhase();
            startPhase("settle");
            // The deck moved during idle-moving, so drop the rider two blocks above where its top is now.
            int top = contraption == null ? base.getY() + LAYERS + 1 : (int) Math.ceil(contraption.getBoundingBox().maxY);
            rider = WheelTrial.assembleHeavy(level, new BlockPos(base.getX(), top + 2, base.getZ()));
            log("rider {}", rider == null ? "none" : rider.getUniqueId());
        } else if (tick == RIDE) {
            settledGap = gap();
            log("settle gap {}", LiveTrial.fmt(settledGap));
            endPhase();
            startPhase("ride");
            callElevator(-2);
        } else if (tick > RIDE && tick < END) {
            trackRide();
        } else if (tick == END) {
            endPhase();
            if (CHURN > 0) {
                startPhase("churn");
            }
        } else if (CHURN > 0 && tick > END && tick < END + CHURN * 60) {
            // Every 60 ticks each small deck disassembles, reassembles a tick later, and is sent
            // on a trip, the way an elevator that lands at every floor would.
            int at = (tick - END) % 60;
            if (at == 1) {
                for (BlockEntity be : SMALL) {
                    try {
                        LiveTrial.invoke(be, "disassemble");
                    } catch (ReflectiveOperationException ex) {
                        log("small disassemble failed {}", LiveTrial.causeText(ex));
                    }
                }
            } else if (at == 3) {
                assembleSmall();
            } else if (at == 6) {
                callSmall(((tick - END) / 60) % 2 == 0 ? -1 : 1);
            }
        } else if (tick == END + Math.max(0, CHURN) * 60 || (CHURN <= 0 && tick == END + 1)) {
            if (CHURN > 0) {
                endPhase();
                log("RESULT churn cycles={} uploads={} deferredUploads={} indexDeferred={}", CHURN,
                        ColliderFeedRuntime.uploads(), ColliderFeedRuntime.deferredUploads(), DeckShapes.deferredBuilds());
            }
            log("RESULT ride settledGap={} minGap={} maxGap={} maxLag={} heldTicks={} heldDrops={} deckMoved={} riderRemoved={}",
                    LiveTrial.fmt(settledGap), LiveTrial.fmt(rideMinGap), LiveTrial.fmt(rideMaxGap), LiveTrial.fmt(rideMaxLag),
                    heldTicks, drops, LiveTrial.fmt(deckY() - anchorAtRide), rider == null || rider.isRemoved());
            log("RESULT mod overall meanUs={} worstUs={}", String.format(java.util.Locale.ROOT, "%.1f", PerfMeter.meanMicros()), PerfMeter.worstMicros());
            finish(level.getServer());
            return;
        }
        if (rider != null || parked != null) {
            SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
            if (system != null) {
                if (rider != null && !rider.isRemoved()) {
                    system.getPipeline().wakeUp(rider);
                }
            }
        }
    }

    private static double anchorAtRide = Double.NaN;

    private static void build(ServerLevel level) {
        // A solid plank deck on top, with stone columns of uneven length hanging under it, so the
        // contraption is irregular the way a real machine is, not one plate.
        int placed = 0;
        for (int x = -HALF; x <= HALF; x++) {
            for (int z = -HALF; z <= HALF; z++) {
                level.setBlock(base.offset(x, LAYERS, z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
                placed++;
                int hash = (x * 73856093) ^ (z * 19349663);
                hash ^= hash >>> 13;
                int hang = Math.floorMod(hash, LAYERS);
                for (int y = LAYERS - hang; y < LAYERS; y++) {
                    level.setBlock(base.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
                    placed++;
                }
            }
        }
        LiveTrial.place(level, base.offset(0, LAYERS, 0), "create:redstone_contact", Direction.SOUTH);
        BlockPos lo = base.offset(-HALF, 1, -HALF);
        int size = HALF * 2 + 1;
        level.addFreshEntity(new com.simibubi.create.content.contraptions.glue.SuperGlueEntity(level,
                new net.minecraft.world.phys.AABB(lo.getX(), lo.getY(), lo.getZ(), lo.getX() + size, lo.getY() + LAYERS, lo.getZ() + size)));
        controller = WheelTrial.elevatorAt(level, base.offset(0, LAYERS, 0), RPM);
        log("built {} blocks", placed);
    }

    private static void buildSmall(ServerLevel level) {
        long placed = 0;
        for (int i = 0; i < COUNT; i++) {
            BlockPos b = new BlockPos(HALF + 100 + (i % 8) * SPACING, base.getY(), -84 + (i / 8) * SPACING);
            for (int x = -SMALL_HALF; x <= SMALL_HALF; x++) {
                for (int z = -SMALL_HALF; z <= SMALL_HALF; z++) {
                    level.setBlock(b.offset(x, LAYERS, z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
                    placed++;
                    int hash = (x * 73856093) ^ (z * 19349663);
                    hash ^= hash >>> 13;
                    int hang = Math.floorMod(hash, LAYERS);
                    for (int y = LAYERS - hang; y < LAYERS; y++) {
                        level.setBlock(b.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
                        placed++;
                    }
                }
            }
            LiveTrial.place(level, b.offset(0, LAYERS, 0), "create:redstone_contact", Direction.SOUTH);
            BlockPos lo = b.offset(-SMALL_HALF, 1, -SMALL_HALF);
            int size = SMALL_HALF * 2 + 1;
            level.addFreshEntity(new com.simibubi.create.content.contraptions.glue.SuperGlueEntity(level,
                    new net.minecraft.world.phys.AABB(lo.getX(), lo.getY(), lo.getZ(), lo.getX() + size, lo.getY() + LAYERS, lo.getZ() + size)));
            SMALL.add(WheelTrial.elevatorAt(level, b.offset(0, LAYERS, 0), RPM));
        }
        log("built small {} blocks in {}", placed, COUNT);
    }

    private static long assembleSmall() {
        long blocks = 0;
        for (BlockEntity be : SMALL) {
            try {
                LiveTrial.invoke(be, "assemble");
            } catch (ReflectiveOperationException ex) {
                log("small assemble failed {}", LiveTrial.causeText(ex));
            }
            AbstractContraptionEntity e = LiveTrial.attached(be);
            if (e != null && e.getContraption() != null) {
                blocks += e.getContraption().getBlocks().size();
            }
        }
        return blocks;
    }

    private static void callSmall(int steps) {
        BlockEntity main = controller;
        double anchor = anchorAtRide;
        for (BlockEntity be : SMALL) {
            controller = be;
            callElevator(steps);
        }
        controller = main;
        anchorAtRide = anchor;
    }

    private static void callElevator(int steps) {
        if (!(controller instanceof ElevatorPulleyBlockEntity pulley) || pulley.movedContraption == null
                || !(pulley.movedContraption.getContraption() instanceof ElevatorContraption elevator)) {
            log("elevator missing");
            return;
        }
        ElevatorColumn column = ElevatorColumn.get(pulley.getLevel(), elevator.getGlobalColumn());
        if (column == null || !column.isTargetAvailable()) {
            log("elevator column missing");
            return;
        }
        int current = column.getTargetedYLevel();
        int target = current + steps * 4;
        if (elevator.isTargetUnreachable(target)) {
            log("target {} unreachable from {}", target, current);
            return;
        }
        column.target(target);
        anchorAtRide = deckY();
        log("elevator target {} -> {}", current, target);
    }

    private static double deckY() {
        return contraption == null ? Double.NaN : contraption.getAnchorVec().y;
    }

    private static double gap() {
        if (rider == null || rider.isRemoved() || contraption == null) {
            return Double.NaN;
        }
        rider.updateBoundingBox();
        return rider.boundingBox().minY() - contraption.getBoundingBox().maxY;
    }

    private static void trackRide() {
        double gap = gap();
        if (!Double.isFinite(gap)) {
            return;
        }
        rideMinGap = Math.min(rideMinGap, gap);
        rideMaxGap = Math.max(rideMaxGap, gap);
        if (Double.isFinite(settledGap)) {
            rideMaxLag = Math.max(rideMaxLag, Math.abs(gap - settledGap));
        }
        boolean held = LiveTrial.held(rider);
        if (held) {
            heldTicks++;
        } else if (wasHeld) {
            drops++;
        }
        wasHeld = held;
        if (tick % 20 == 0) {
            log("ride t={} gap={} deckY={} held={}", tick - RIDE, LiveTrial.fmt(gap), LiveTrial.fmt(deckY()), held);
        }
    }

    private static long[] partsLastTick = new long[PerfMeter.Part.values().length];
    private static long[] partsAtPhase = new long[PerfMeter.Part.values().length];

    private static void startPhase(String name) {
        phase = name;
        partsAtPhase = PerfMeter.parts();
        TICK_NANOS.clear();
        MOD_NANOS.clear();
        anchorAtPhase = deckY();
    }

    private static void endPhase() {
        if (TICK_NANOS.isEmpty()) {
            return;
        }
        long[] ticks = TICK_NANOS.stream().mapToLong(Long::longValue).toArray();
        long[] mod = MOD_NANOS.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(ticks);
        Arrays.sort(mod);
        log("RESULT phase={} ticks={} tickMs mean={} p50={} p99={} max={} modUs mean={} p99={} max={} deckMoved={}",
                phase, ticks.length, ms(mean(ticks)), ms(ticks[ticks.length / 2]), ms(ticks[Math.min(ticks.length - 1, ticks.length * 99 / 100)]),
                ms(ticks[ticks.length - 1]), us(mean(mod)), us(mod[Math.min(mod.length - 1, mod.length * 99 / 100)]), us(mod[mod.length - 1]),
                LiveTrial.fmt(deckY() - anchorAtPhase));
        long[] parts = PerfMeter.parts();
        StringBuilder split = new StringBuilder();
        for (PerfMeter.Part part : PerfMeter.Part.values()) {
            double perTick = (parts[part.ordinal()] - partsAtPhase[part.ordinal()]) / 1_000.0 / ticks.length;
            split.append(' ').append(part.name()).append('=').append(String.format(java.util.Locale.ROOT, "%.1f", perTick));
        }
        log("RESULT phase={} modUsPerTickByPart{}", phase, split);
    }

    private static double mean(long[] values) {
        double sum = 0.0;
        for (long value : values) {
            sum += value;
        }
        return values.length == 0 ? 0.0 : sum / values.length;
    }

    private static String ms(double nanos) {
        return String.format(java.util.Locale.ROOT, "%.2f", nanos / 1_000_000.0);
    }

    private static String us(double nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1_000.0);
    }

    private static void log(String message, Object... args) {
        SableSafeLanding.LOGGER.info("STRESS " + message, args);
    }

    private static void finish(MinecraftServer server) {
        done = true;
        log("stopping");
        server.halt(false);
    }
}
