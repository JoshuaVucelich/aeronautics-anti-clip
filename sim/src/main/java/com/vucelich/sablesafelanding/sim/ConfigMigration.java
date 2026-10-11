package com.vucelich.sablesafelanding.sim;

/**
 * NeoForge keeps values that are already written, so a config from before carry defaulted
 * on stays {@code carry=false} forever. {@link #CARRY_ON} is the version that turns carry on
 * once. A later edit that sets carry off is left alone.
 */
public final class ConfigMigration {
    /** Configs older than this are migrated to carry on. 0 means the key was not in the file. */
    public static final int CARRY_ON = 1;

    public record Result(int version, boolean carry, boolean changed) {
    }

    private ConfigMigration() {
    }

    public static Result migrate(int version, boolean carry) {
        if (version < CARRY_ON) {
            return new Result(CARRY_ON, true, version != CARRY_ON || !carry);
        }
        return new Result(version, carry, false);
    }
}
