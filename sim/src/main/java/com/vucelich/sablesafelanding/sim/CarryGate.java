package com.vucelich.sablesafelanding.sim;

/**
 * When a carried car is captured, kept, and released.
 * The collider feed never captures. Capture needs {@link #CONTACT_HOLD} ticks of real block
 * contact while carry is on. A teleport happens only after the deck has drifted past
 * {@link #DRIFT} blocks or {@link #ANGLE_DRIFT} radians, so a parked hull can sleep.
 */
public final class CarryGate {
    public static final int CONTACT_HOLD = 5;
    /** Ticks of confirmed absence before the lock is dropped. An inconclusive scan does not count. */
    public static final int NO_CONTACT_RELEASE = 8;
    public static final int IDLE_TICKS = 10;
    /**
     * Ticks of upward speed, above the deck, before a launch counts. One tick is the contact
     * solver returning a ship that was teleported onto the collider.
     */
    public static final int TAKEOFF_TICKS = 3;
    /** Blocks of deck drift before another teleport. */
    public static final double DRIFT = 0.2;
    /** Radians of bearing drift before another teleport. Eight degrees. */
    public static final double ANGLE_DRIFT = Math.toRadians(8.0);
    /** Anchor motion, in blocks, that still counts as a stopped deck. */
    public static final double IDLE_ANCHOR = 1.0e-3;
    /** Angle motion, in radians, that still counts as a stopped deck. About 0.05 degrees. */
    public static final double IDLE_ANGLE = Math.toRadians(0.05);
    /**
     * Gap, in blocks, that counts as boarding. A flyover of 0.6 blocks does not.
     * Matches the default rest epsilon.
     */
    public static final double CAPTURE_GAP = 0.2;
    /**
     * Gap allowed while already carried. It stays above {@link #DRIFT} so the pause between
     * teleports is still contact.
     */
    public static final double MAINTAIN_GAP = 0.45;

    public enum Contact {
        NONE, RESTING, INCONCLUSIVE
    }

    public enum Reason {
        NOCONTACT, DISASSEMBLED, IDLE, TAKEOFF, PILOT
    }

    private CarryGate() {
    }

    public static boolean capture(boolean carryEnabled, int contactStreak) {
        return carryEnabled && contactStreak >= CONTACT_HOLD;
    }

    /** Next streak. An inconclusive scan leaves the streak where it was. */
    public static int nextStreak(int streak, Contact contact) {
        return switch (contact) {
            case RESTING -> streak + 1;
            case NONE -> 0;
            case INCONCLUSIVE -> streak;
        };
    }

    public static int nextMisses(int misses, Contact contact) {
        return switch (contact) {
            case NONE -> misses + 1;
            case RESTING -> 0;
            case INCONCLUSIVE -> misses;
        };
    }

    /** Blocks above the deck before the lower takeoff bar applies. */
    public static final double TAKEOFF_CLEARANCE = 1.0;
    /**
     * Upward speed, in blocks per second, that can start a takeoff while the hull is still on
     * the blocks. A descending deck's contact readback sits near half a block per second, so
     * that nudge must not release the car. Thrust from a key other than the movement keys is
     * faster than this and counts before the hull is clear.
     */
    public static final double ON_DECK_SPEED = 2.0;

    /**
     * Consecutive ticks the ship is climbing faster than the deck.
     * While the hull is still on the blocks the bar is {@link #ON_DECK_SPEED}, so thrust counts
     * and the contact nudge does not. Once the hull is clear, {@link AntiClipStep#TAKEOFF_SPEED}
     * is enough. A tick that is not faster than the deck resets the streak.
     */
    public static int nextTakeoff(int streak, double upwardSpeed, double deckUpSpeed, boolean clearOfDeck) {
        double bar = clearOfDeck ? AntiClipStep.TAKEOFF_SPEED : ON_DECK_SPEED;
        if (upwardSpeed > bar && upwardSpeed > deckUpSpeed + bar) {
            return streak + 1;
        }
        return 0;
    }

    /**
     * Why the lock should drop, or null to keep it.
     * {@code takeoffTicks} comes from {@link #nextTakeoff}.
     */
    public static Reason release(
            boolean disassembled,
            boolean piloting,
            int takeoffTicks,
            int idleTicks,
            int missTicks
    ) {
        if (disassembled) {
            return Reason.DISASSEMBLED;
        }
        if (piloting) {
            return Reason.PILOT;
        }
        if (takeoffTicks >= TAKEOFF_TICKS) {
            return Reason.TAKEOFF;
        }
        if (idleTicks >= IDLE_TICKS) {
            return Reason.IDLE;
        }
        if (missTicks >= NO_CONTACT_RELEASE) {
            return Reason.NOCONTACT;
        }
        return null;
    }

    public static int nextIdle(int idleTicks, double anchorDelta, double angleDeltaRadians) {
        if (anchorDelta < IDLE_ANCHOR && Math.abs(angleDeltaRadians) < IDLE_ANGLE) {
            return idleTicks + 1;
        }
        return 0;
    }

    public static boolean teleport(double driftSquared, double angleSincePlaceRadians) {
        return driftSquared > DRIFT * DRIFT || Math.abs(angleSincePlaceRadians) > ANGLE_DRIFT;
    }

    public static String reasonName(Reason reason) {
        return switch (reason) {
            case NOCONTACT -> "nocontact";
            case DISASSEMBLED -> "disassembled";
            case IDLE -> "idle";
            case TAKEOFF -> "takeoff";
            case PILOT -> "pilot";
        };
    }
}
