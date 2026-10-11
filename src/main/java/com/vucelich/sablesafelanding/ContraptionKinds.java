package com.vucelich.sablesafelanding;

import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import com.simibubi.create.content.contraptions.bearing.ClockworkContraption;
import com.simibubi.create.content.contraptions.bearing.StabilizedContraption;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import com.simibubi.create.content.contraptions.gantry.GantryContraption;
import com.simibubi.create.content.contraptions.piston.PistonContraption;
import net.minecraft.world.level.block.Block;

/**
 * Names consumed by {@link com.vucelich.sablesafelanding.sim.ContraptionKind#parse}.
 */
public final class ContraptionKinds {
    private ContraptionKinds() {
    }

    public static String nameOf(Contraption contraption) {
        if (contraption instanceof ElevatorContraption) {
            return "elevator";
        }
        if (contraption instanceof ClockworkContraption) {
            return "clockwork";
        }
        if (contraption instanceof StabilizedContraption) {
            return "stabilized";
        }
        if (contraption instanceof BearingContraption) {
            return "bearing";
        }
        if (contraption instanceof PistonContraption) {
            return "piston";
        }
        if (contraption instanceof GantryContraption) {
            return "gantry";
        }
        return null;
    }

    public static long shapeHash(Contraption contraption) {
        long hash = 1L;
        for (var entry : contraption.getBlocks().entrySet()) {
            hash = hash * 31L + entry.getKey().asLong();
            hash = hash * 31L + Block.getId(entry.getValue().state());
        }
        return hash;
    }
}
