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

The jar is `build/libs/sable_safe_landing-<version>.jar`. The release candidate is version `0.7.0-rc.1`. Drop it in the `mods` folder next to Create, Sable, and Create Aeronautics. Bump `mod_version` in `gradle.properties` before a new jar so the hash changes.

`/anticlip status` prints the master switch, the feature flags, the carry locks, and whether the Sable removal hook has run. `/anticlip off` and `/anticlip on` (permission level 2) flip `enabled` and save it. Off disables rescue, the collider feed, carry, and sweep. Failure cases and the guards for them are in `docs/FAILURE_SCENARIOS.md`.

A dedicated server started with `-Dsable_safe_landing.live=true` runs a scripted trial: a sub-level below min build height, a sub-level at Sable's remove bound, and a vehicle on an elevator, a bearing, and a piston. It logs lines prefixed with `LIVE` and then stops.

## Carry

Carry is on by default. NeoForge keeps a value that is already in the file, so a config written when carry defaulted off stays off. `configVersion` 0 (a missing key loads as 0) is migrated to carry on and stored as 1. Turning carry off after that is kept. The collider feed uploads the deck and does not capture or teleport the car.

A ship is skipped unless its bounding box, inflated by 0.2 blocks, meets a contraption's bounds. Only that contraption's blocks are tested. There is no per-tick scan of every ship against every contraption block. A lock starts only after a ship block's bottom has stayed on the deck for 5 ticks, within 0.2 blocks. The car is not placed until that confirm finishes. A pass 0.6 blocks above the deck does not count. The lock ends when any of these happen: 8 ticks with no contact, the contraption is removed or disassembled, the deck is idle for 10 ticks, upward speed relative to the deck for 3 ticks, or a player is steering. That speed is the Rapier body velocity from `PhysicsPipeline.getLinearVelocity`. Sable's `latestLinearVelocity` is the pose delta times 20, and a teleport inflates it when the previous pose is stale. The body velocity is not changed by `RapierPhysicsPipeline.teleport`. While the hull is still on the blocks, the climb has to be faster than the contact readback (2 blocks per second above the deck) so a descending elevator does not look like thrust. The car is not pulled back while the streak is running. An inconclusive scan of a large ship (the 128-block cap) does not count as lost contact.

The server tick places the car only after the confirm, and only once the deck has drifted more than 0.2 blocks or the bearing has turned more than half a degree. The placement is `PhysicsPipeline.teleport`. It wakes that body for the correction and does not call `resetVelocity` or zero the ship's velocity. `updateLastPose` runs after the teleport so the next pose delta starts at the new pose. Ticks that do not teleport do not wake, so a parked hull can sleep and a takeoff can climb off.

## Bearing on a client

Sable 2.0.3 already interpolates sub-level snapshots. `ClientSubLevel` slerps from one networked pose to the next, and the server sends a snapshot when the logical pose changes. Carry does not add a client mixin. While a bearing turns, the server writes the carried pose at every half degree so those snapshots follow the deck instead of stepping eight degrees. The written pose is the pre-physics sample, the same pose as the kinematic collider. Putting the car on the live angle embeds it in that collider, so the ship stays about one tick behind the contraption (`errSeat` of one bearing step).

The angle stored at capture is Create's accumulated bearing angle. Later ticks rotate the captured offset and the boarding orientation by the difference around the bearing axis. Steering releases the lock first, so that rotation is not applied over the pilot's turn.

Create 6.0.10 `AbstractContraptionEntity.tick` calls `Map.entrySet` once, on `collidingEntities`. Sable 2.0.3 injects that call. This mod's hook uses the same invoke with ordinal 0 and mixin priority 1100, so it runs after Sable adds its kinematic. Sable's collider is suppressed only after this mod has claimed the contraption.

Sable 2.0.3 `RapierPhysicsPipeline.teleport` writes the Rapier body, copies that pose onto the sub-level, and returns without waking the body. Ship-hull 1.0.0-phase7 treats a Sable center-of-mass teleport as a copy onto a parked pose and not as a wake. This mod's teleport is that call, from `ServerTickEvent.Post`, and nothing after it wakes the body. The ship-hull mixin sources were not in this workspace; the Sable order was read from `RapierPhysicsPipeline`, and the wake rule from the ship-hull README.

A carried correction longer than 4 blocks or 90 degrees in one tick releases the lock instead of teleporting. A physics substep longer than 0.25 seconds does not write a ride velocity. A car sitting on a carried ship is moved with that ship. Chunk unload drops the lock and does not teleport. Rescue is skipped inside a portal and when the target cell is buried and no air spot is found within 16 blocks. Mixin targets are optional, and a thrown hook is logged once and swallowed.

This project is not published to Modrinth or CurseForge.
