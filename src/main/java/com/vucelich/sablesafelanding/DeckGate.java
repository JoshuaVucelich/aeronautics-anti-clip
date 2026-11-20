package com.vucelich.sablesafelanding;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.vucelich.sablesafelanding.sim.Aabb;
import com.vucelich.sablesafelanding.sim.CarryBroadphase;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides, once per server tick and per level, whether the deck work has to run at all.
 *
 * <p>The carry, the deck shapes, and the sweep run only while the level is <em>engaged</em>: a
 * ship is held on a deck, or a deck that moved in the last {@link #ACTIVE_TICKS} ticks has a
 * ship on or next to it. Everything else in the level costs one anchor read per deck per tick.
 * Ships are looked at only while a deck is moving. Whether any ship is near any deck at all
 * (used to get a deck's index ready before it moves, and by the wheel ground rays) is
 * rechecked every {@link #NEAR_STRIDE} ticks with a margin of that many ticks of the ship's speed.
 *
 * <p>With {@code idleSleep} off every tick counts as engaged, which is the rc.8 behavior.
 */
public final class DeckGate {
    /** A deck that moved within this many ticks still counts as moving. Same as the carry's. */
    static final long ACTIVE_TICKS = 40;
    /** Ticks between rechecks of "is any ship near any deck" while nothing moves. */
    static final long NEAR_STRIDE = 10;
    /** Most ships looked at in one check. */
    private static final int MAX_SHIPS = 512;

    private static final class State {
        private long tick = Long.MIN_VALUE;
        private boolean engaged;
        private boolean near;
        private long nearAt = Long.MIN_VALUE;
        private List<ServerSubLevel> nearShips = List.of();
        private final Map<AbstractContraptionEntity, double[]> last = new IdentityHashMap<>();
        private int version = Integer.MIN_VALUE;
        private boolean wasEngaged;
    }

    private static final Map<String, State> STATES = new java.util.HashMap<>();
    private static final boolean DEBUG = Boolean.getBoolean("sable_safe_landing.gateDebug");
    private static long engagedTicks;
    private static long checkedTicks;

    private DeckGate() {
    }

    /** Deck work has to run this tick in this level. */
    public static boolean engaged(ServerLevel level) {
        return update(level).engaged;
    }

    /**
     * Same as {@link #engaged}, for the end of the tick. The first check of a tick can run before
     * the decks' own tick has moved them, so a deck that started moving later in the same tick
     * would only be seen one tick late. This rereads each deck's anchor (one read per deck) and,
     * if any moved since the first check, decides again, so a ride starts on the deck's first
     * moving tick as it did before the gate.
     */
    public static boolean engagedAtEnd(ServerLevel level) {
        State state = update(level);
        if (state.engaged || !SafeLandingConfig.idleSleep()) {
            return state.engaged;
        }
        for (AbstractContraptionEntity deck : Decks.of(level)) {
            double[] last = state.last.get(deck);
            if (last == null || deck.isRemoved() || state.tick - (long) last[4] <= ACTIVE_TICKS) {
                // Unknown, gone, or already counted as moving by the first check.
                continue;
            }
            Vec3 anchor = deck.getAnchorVec();
            double dx = anchor.x - last[0];
            double dy = anchor.y - last[1];
            double dz = anchor.z - last[2];
            float angle = deck instanceof ControlledContraptionEntity controlled && controlled.getRotationAxis() != null
                    ? controlled.getAngle(1.0f) : 0.0f;
            if (dx * dx + dy * dy + dz * dz > 1.0e-8 || Math.abs(angle - last[3]) > 1.0e-3) {
                state.tick = Long.MIN_VALUE;
                checkedTicks--;
                return update(level).engaged;
            }
        }
        return false;
    }

    /** Some ship is on or next to some deck, moving or not. Refreshed every {@link #NEAR_STRIDE} ticks. */
    public static boolean near(ServerLevel level) {
        return update(level).near;
    }

    /** Ships whose reach overlaps a moving or holding deck. Empty when not engaged. */
    public static List<ServerSubLevel> nearShips(ServerLevel level) {
        return update(level).nearShips;
    }

    public static long engagedTicks() {
        return engagedTicks;
    }

    public static long checkedTicks() {
        return checkedTicks;
    }

    public static void clear() {
        STATES.clear();
    }

    public static void forgetDimension(String dimension) {
        STATES.remove(dimension);
    }

    private static State update(ServerLevel level) {
        String dimension = level.dimension().location().toString();
        State state = STATES.computeIfAbsent(dimension, ignored -> new State());
        long tick = level.getServer().getTickCount();
        if (state.tick == tick) {
            return state;
        }
        state.tick = tick;
        checkedTicks++;
        if (!SafeLandingConfig.idleSleep()) {
            state.engaged = true;
            state.near = true;
            state.nearShips = allShips(level);
            engagedTicks++;
            return state;
        }
        List<AbstractContraptionEntity> decks = Decks.of(level);
        int version = LiveContraptions.version();
        if (version != state.version) {
            state.version = version;
            state.last.keySet().retainAll(Set.copyOf(decks));
        }
        Set<String> holding = CarryRuntime.heldDeckIds(dimension);
        List<Aabb> hot = null;
        for (AbstractContraptionEntity deck : decks) {
            if (deck.isRemoved()) {
                continue;
            }
            Vec3 anchor = deck.getAnchorVec();
            float angle = deck instanceof ControlledContraptionEntity controlled && controlled.getRotationAxis() != null
                    ? controlled.getAngle(1.0f) : 0.0f;
            double[] last = state.last.get(deck);
            if (last == null) {
                state.last.put(deck, new double[]{anchor.x, anchor.y, anchor.z, angle, Long.MIN_VALUE / 2});
                continue;
            }
            double prevX = last[0];
            double prevY = last[1];
            double prevZ = last[2];
            float prevAngle = (float) last[3];
            double dx = anchor.x - last[0];
            double dy = anchor.y - last[1];
            double dz = anchor.z - last[2];
            if (dx * dx + dy * dy + dz * dz > 1.0e-8 || Math.abs(angle - last[3]) > 1.0e-3) {
                last[4] = tick;
            }
            last[0] = anchor.x;
            last[1] = anchor.y;
            last[2] = anchor.z;
            last[3] = angle;
            boolean moving = tick - (long) last[4] <= ACTIVE_TICKS;
            boolean held = !holding.isEmpty() && holding.contains(deck.getUUID().toString());
            if (moving && !state.engaged) {
                // The carry keeps its own per-tick deck samples only while engaged. Give it the
                // previous tick's pose now, so a ship resting on a deck that just started moving
                // is caught on this tick, not one or two ticks later.
                CarryRuntime.seedSample(dimension, deck, prevX, prevY, prevZ, prevAngle, level.getGameTime() - 1);
            }
            if (moving || held) {
                Aabb bounds = CarryRuntime.broadphaseBounds(deck);
                if (bounds != null) {
                    if (hot == null) {
                        hot = new ArrayList<>(2);
                    }
                    hot.add(bounds);
                }
            }
        }
        if (hot == null && holding.isEmpty()) {
            state.engaged = false;
            state.nearShips = List.of();
        } else {
            state.nearShips = hot == null ? List.of() : shipsNear(level, hot, 0.0);
            state.engaged = !holding.isEmpty() || !state.nearShips.isEmpty();
        }
        if (DEBUG && (state.engaged != state.wasEngaged || tick % 40 == 0)) {
            SableSafeLanding.LOGGER.info("GATE {} tick={} decks={} hot={} near={} ships={} engaged={}", dimension, tick,
                    decks.size(), hot == null ? 0 : hot.size(), state.near, state.nearShips.size(), state.engaged);
        }
        state.wasEngaged = state.engaged;
        if (state.engaged) {
            engagedTicks++;
            state.near = true;
            state.nearAt = tick;
        } else if (decks.isEmpty()) {
            state.near = false;
        } else if (state.nearAt == Long.MIN_VALUE || tick < state.nearAt || tick - state.nearAt >= NEAR_STRIDE) {
            List<Aabb> all = new ArrayList<>(decks.size());
            for (AbstractContraptionEntity deck : decks) {
                Aabb bounds = deck.isRemoved() ? null : CarryRuntime.broadphaseBounds(deck);
                if (bounds != null) {
                    all.add(bounds);
                }
            }
            state.near = !shipsNear(level, all, NEAR_STRIDE / 20.0).isEmpty();
            state.nearAt = tick;
        }
        return state;
    }

    /**
     * Ships whose box, grown by the capture margin, three blocks for wheels, and
     * {@code extraSeconds} plus two ticks of their own speed, overlaps one of {@code boxes}.
     */
    private static List<ServerSubLevel> shipsNear(ServerLevel level, List<Aabb> boxes, double extraSeconds) {
        if (boxes.isEmpty() || !(SubLevelContainer.getContainer(level) instanceof ServerSubLevelContainer container)) {
            return List.of();
        }
        List<ServerSubLevel> found = null;
        int seen = 0;
        for (ServerSubLevel ship : container.getAllSubLevels()) {
            if (seen++ >= MAX_SHIPS) {
                break;
            }
            if (ship.isRemoved() || ship.getLevel() != level) {
                continue;
            }
            Aabb hull = CarryRuntime.shipBox(ship);
            if (hull == null) {
                continue;
            }
            Vector3d velocity = ship.latestLinearVelocity;
            double speed = velocity == null || !Double.isFinite(velocity.length()) ? 0.0 : velocity.length();
            Aabb reach = hull.expand(CarryBroadphase.INFLATE + 3.0 + Math.min(32.0, speed * (0.1 + extraSeconds)));
            for (Aabb box : boxes) {
                if (reach.intersects(box)) {
                    if (found == null) {
                        found = new ArrayList<>(2);
                    }
                    found.add(ship);
                    break;
                }
            }
        }
        return found == null ? List.of() : found;
    }

    private static List<ServerSubLevel> allShips(ServerLevel level) {
        if (!(SubLevelContainer.getContainer(level) instanceof ServerSubLevelContainer container)) {
            return List.of();
        }
        List<ServerSubLevel> ships = new ArrayList<>();
        for (ServerSubLevel ship : container.getAllSubLevels()) {
            if (!ship.isRemoved() && ship.getLevel() == level) {
                ships.add(ship);
            }
        }
        return ships;
    }
}
