package com.vucelich.sablesafelanding.sim;

/**
 * Caps the carry scan. Ships that are already locked are maintained separately.
 * New captures look at {@link #MAX_SHIPS} unlocked ships per tick, rotating through the list,
 * and they stop once {@link #MAX_LOCKS} locks are held.
 */
public final class CarryBudget {
    public static final int MAX_SHIPS = 32;
    public static final int MAX_LOCKS = 32;

    private CarryBudget() {
    }

    public static boolean admit(int heldLocks, int examinedUnlocked) {
        return heldLocks < MAX_LOCKS && examinedUnlocked < MAX_SHIPS;
    }

    /** First index to examine. A short list always starts at 0 so two cars on one deck are both seen. */
    public static int windowStart(int count, int cursor, int max) {
        if (count <= 0 || max <= 0 || count <= max) {
            return 0;
        }
        return Math.floorMod(cursor, count);
    }
}
