# Aeronautics Anti-Clip

NeoForge 1.21.1 mod (`sable_safe_landing`) that keeps Create Aeronautics vehicles from clipping through moving Create contraptions.

Aeronautics vehicles are Sable sub-levels. A car driven onto a Create elevator can tunnel through the platform, fall through bedrock, and get deleted when it crosses Sable's `sub_level_remove` bounds. This mod remembers a safe pose, feeds elevator, bearing, piston, and gantry colliders into Sable's Rapier scene, carries a resting sub-level with the contraption, and sweeps fast motion so it cannot tunnel.

## Targets

- Minecraft 1.21.1, NeoForge 21.1.228 or newer
- Create 6.0.10
- Sable 2.0.x (developed against 2.0.3)
- Create Aeronautics 1.3.x (optional at load time; vehicles are ordinary Sable sub-levels)

## Run the headless repros

The sim module does not start Minecraft or link Rapier.

```bash
./gradlew :sim:test
```

## Build the mod jar

```bash
./gradlew build
```

The jar is `build/libs/sable_safe_landing-<version>.jar`. Drop it in the `mods` folder next to Create, Sable, and Create Aeronautics. Bump `mod_version` in `gradle.properties` before a new jar so the hash changes.

This project is not published to Modrinth or CurseForge.

## Download

Not on Modrinth yet.

## License

[MIT](LICENSE). Copyright (c) 2026 Joshua Vucelich.
