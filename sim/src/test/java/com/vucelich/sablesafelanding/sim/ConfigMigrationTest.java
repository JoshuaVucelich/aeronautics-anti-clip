package com.vucelich.sablesafelanding.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigMigrationTest {
    @Test
    void anOldFileTurnsCarryOnOnceAndALaterChoiceStays() {
        ConfigMigration.Result legacy = ConfigMigration.migrate(0, false);
        assertEquals(ConfigMigration.CARRY_ON, legacy.version());
        assertTrue(legacy.carry());
        assertTrue(legacy.changed());

        ConfigMigration.Result chosen = ConfigMigration.migrate(ConfigMigration.CARRY_ON, false);
        assertFalse(chosen.carry());
        assertFalse(chosen.changed());

        ConfigMigration.Result current = ConfigMigration.migrate(ConfigMigration.CARRY_ON, true);
        assertTrue(current.carry());
        assertFalse(current.changed());

        // A fresh file is born at version 0 with carry already on. It is stamped once so a
        // later carry=false is kept, and carry itself is not flipped.
        ConfigMigration.Result fresh = ConfigMigration.migrate(0, true);
        assertEquals(ConfigMigration.CARRY_ON, fresh.version());
        assertTrue(fresh.carry());
        assertTrue(fresh.changed());
    }
}
