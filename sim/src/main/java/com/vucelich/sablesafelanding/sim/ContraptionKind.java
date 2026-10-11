package com.vucelich.sablesafelanding.sim;

/**
 * Create contraptions whose colliders are fed into Sable. Anything else, including a
 * windmill that is not one of these assemblies, is left alone.
 */
public enum ContraptionKind {
    ELEVATOR,
    BEARING,
    PISTON,
    GANTRY;

    public static ContraptionKind parse(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case "elevator" -> ELEVATOR;
            case "bearing", "clockwork", "stabilized" -> BEARING;
            case "piston" -> PISTON;
            case "gantry" -> GANTRY;
            default -> null;
        };
    }
}
