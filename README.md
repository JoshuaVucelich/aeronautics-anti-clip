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

The jar is `build/libs/sable_safe_landing-<version>.jar`. The release candidate is version `0.7.0-rc.9`. Drop it in the `mods` folder next to Create, Sable, and Create Aeronautics. Bump `mod_version` in `gradle.properties` before a new jar so the hash changes.

`/anticlip status` prints the master switch, the feature flags, the carry locks, and whether the Sable removal hook has run. `/anticlip off` and `/anticlip on` (permission level 2) flip `enabled` and save it. Off disables rescue, the collider feed, carry, and sweep. Failure cases and the guards for them are in `docs/FAILURE_SCENARIOS.md`.

A dedicated server started with `-Dsable_safe_landing.live=true` runs a scripted trial: a sub-level below min build height, a sub-level at Sable's remove bound, and a vehicle on an elevator, a bearing, and a piston. It logs lines prefixed with `LIVE` and then stops.

A dedicated server started with `-Dsable_safe_landing.stress=true` runs the stress trial: a large glued elevator contraption (`-Dsable_safe_landing.stress.half` and `.layers` set its size), a heavy wheeled ship parked away from it, and a fake player on the deck. It measures server tick time and this mod's own time with nothing on the elevator (stopped, then moving), then drops a second heavy ship on the deck and rides it down. `-Dsable_safe_landing.stress.modOff=true` runs the same script with the master switch off. Lines start with `STRESS`.

A dedicated server started with `-Dsable_safe_landing.wheels=true` runs the wheel trial: a four-wheel car (Offroad wheel mounts and tires) on the ground, parked on an elevator deck, riding the elevator up and down, driving off the deck, driving onto it, driving while the elevator moves, and parked on a turning bearing deck at its center and off-center. Each case runs with `wheelContact` on and off. It logs lines prefixed with `WHEELS` and then stops.

## Wheels on a contraption deck

Offroad's wheel mount (bundled with Create Aeronautics 1.3.x) finds the ground with three `Level.clip` rays from each wheel. Sable's clip sees world blocks and sub-levels. A Create elevator, bearing, piston, or gantry deck is a contraption entity, and its blocks are not in the world, so the rays pass through it. The collider feed holds the body up, but the wheels get no spring and no drive force. They hang at full extension through the deck and the car cannot drive on or off.

With `wheelContact` on (the default), the same ray is also cast through the blocks of nearby contraptions. When a deck is strictly closer than what Sable found, the wheel gets that hit, in world space, with the face turned by the deck rotation. Friction is the default 1. A failure keeps the original ray. The mixin is optional, so without Offroad it does nothing. The rays run on the server (suspension and drive) and on the client (where the wheels are drawn), so the jar belongs on both.

Cost: a level with no contraption pays one map read per wheel ray. With contraptions loaded, their bounds are cached once per level per tick (at most 256). Each ship is checked once per tick against that cache, using its box padded by 7.5 blocks, the farthest its wheel rays can reach. A ray from a ship with no contraption that close does three map reads (live contraptions, the mount's ship, that ship's cached list) and returns. The wheel mount's ship is cached, so a ray does no Sable lookup. Only rays from a ship near a contraption test that contraption's blocks.

Wheels on a deck also count as resting contact for carry. A car on its suspension rides about a block above the deck, which is past the hull gap carry uses, and the wheel force does not move the car with a turning deck. Only a hit Offroad would stand on counts: within the suspension reach (0.65 rest + tire radius + 0.25 from the wheel center) and on a face pointing up in the car's frame. A ship hovering higher than that is not grabbed, and takeoff release works once the wheels leave that reach. A touch expires after 2 ticks and expired touches are pruned every tick. A bearing deck captures a car parked on its wheels and turns it.

`wheelContact` is a common config value and is not synced. The server's value decides driving and carry. A client reads its own file, which only changes where its wheels are drawn.

`/anticlip status` prints `wheelContact`, `wheelDeckHits` (server wheel rays that stood on a deck since startup, so client rays and hits past the suspension reach are not counted), and `wheelTouches` (ships whose wheels touch a deck right now).

## Performance

Nothing in this mod draws anything. There are no render mixins, render events, or client tick listeners. On a client the mod does two things: the wheel ground ray (once per wheel per client tick, never per frame) and an early return in the contraption tick hook.

`idleSleep` (default on). Each server tick the mod reads each world deck's anchor once. Until a deck moves with a ship on or next to it, or holds a ship, the carry, deck shapes, and sweep do nothing, and ships are not scanned. Whether any ship is near any deck (used to get a deck's index ready before it moves, and by the server wheel rays) is rechecked every 10 ticks. Off restores the rc.8 behavior of running the deck checks every tick. It never changes how a riding ship is carried. With it on, also:

- Void rescue runs its full step only for a ship within 32 blocks (plus its fall speed) of the void floor, the build ceiling, or the bedrock line. Every other ship gets a cheap safe-pose record instead: every 10 ticks its position, box, and rotation are copied into a reused array (no allocation) when it is not falling fast and is above min build height. When the ship enters the band, that record seeds the rescue tracker if it is newer. This is what lets a car parked on the surface and then pushed through bedrock go back to the surface.
- A server wheel ray from a car parked on a deck that has not moved reuses its last result instead of casting through the deck again. The memo is per wheel mount and per exact ray, and is dropped when any deck moves, the contraption set changes, the car moves (more than 0.001 blocks or turns), Offroad's own hit changes, or after 100 ticks.
- Server wheel rays from a ship with no deck within its wheel reach plus 1 block go straight to Offroad's ray. That answer, and the level's list of deck bounds, are reused while no deck has moved and no contraption joined or left, for at most 100 ticks; the ship's answer also expires once it moves 1 block.
- A deck's collider is taken over from Sable only while a ship is within 48 blocks of it (plus 2 seconds of the ship's speed). A deck nobody is near keeps Sable's own collider, so loading or assembling far-away contraptions does not pay for a second collider build. Far decks are rechecked every 10 ticks.

`clientWheelContact` (default on, client only). This is tick logic, not render code. On the client, Offroad's `WheelMountBlockEntity.tick()` casts the wheel ground ray and stores the wheel's extension; Offroad's renderer later draws that stored value. With this on, the client's ray also sees contraption decks, so a wheel on a deck is drawn standing on the deck instead of hanging through it. Nothing else on the client changes: no driving, no physics, no carry. Away from every deck the check costs about 15 to 50 ns per wheel ray. Off leaves Offroad's own client ray untouched, and the wheels on a deck are drawn at full extension through it. The server decides driving either way.

`tickBudgetMicros` (default 2000, so 2 ms of a 50 ms tick, range 0 to 50000) caps background deck work per dimension per server tick. It is checked before each step runs, against a learned cost, with a quarter of the cap kept back for wheel rays. Capped: deck index slices, deck shapes, capture block tests, wheel rays against decks, and collider uploads past the first one in a tick. A step that does not fit waits for the next tick, so a big build is spread over ticks. Values 1 to 499 are raised to 500. 0 turns deck work off.

It does not cap active carry. Placing a ship that is already riding (the teleport, wake, and velocity write each tick) always runs, and so does the first collider upload per dimension per tick. In the stress trial a heavy ride peaked at 6 to 9 ms of the mod's time in single ticks while the ride mean stayed under 1 ms, and the first upload of a 23,121-block deck took 6.7 to 10.8 ms on the Linux test box (up to about 0.45 us per block), once per assembly. Those peaks are real and are not held to 2 ms.

`/anticlip status` prints a second line, `Server time`: mean and worst microseconds per tick, the last minute's mean and worst, ticks over 20 us, how many level-ticks did deck work, budget-out ticks, deferred steps, deferred index builds, collider uploads (deferred, and left on Sable's collider because no ship was near), wheel rays answered from the memo, void rescue full steps versus skipped, and parked safe-pose records written. `/anticlip status reset` (op level 2) clears those counters. On a client, `/anticlipc status` prints the client's own mean and worst time per client tick, hook calls, wheel rays, and rays on a deck; `/anticlipc reset` clears them.

## Carry

Carry is on by default. NeoForge keeps a value that is already in the file, so a config written when carry defaulted off stays off. `configVersion` 0 (a missing key loads as 0) is migrated to carry on and stored as 1. Turning carry off after that is kept. The collider feed uploads the deck and does not capture or teleport the car.

A ship is skipped unless its bounding box, inflated by 0.2 blocks, meets a contraption's bounds. Only that contraption's blocks are tested. There is no per-tick scan of every ship against every contraption block. A lock starts only after a ship block's bottom has stayed on the deck for 5 ticks, within 0.2 blocks. The car is not placed until that confirm finishes. The horizontal deck-frame offset is stored on the tick the confirm finishes, not at the first touch, so a ship that moved over the deck during the confirm is not pulled back or released as separated. The height stays the first touch's resting height, and a bearing keeps the whole first-touch offset. On a sliding deck, a ship moving across it faster than 2 blocks per second relative to the deck starts the confirm again, so a ship that was flung or is still being pushed is not captured until it has stopped on the deck. Turntables are not checked, since a parked ship is still while the planks under it move. A pass 0.6 blocks above the deck does not count. The lock ends when any of these happen: 8 ticks with no contact, the contraption is removed or disassembled, the deck is idle for 10 ticks, upward speed relative to the deck for 3 ticks, or a player is steering. That speed is the Rapier body velocity from `PhysicsPipeline.getLinearVelocity`. Sable's `latestLinearVelocity` is the pose delta times 20, and a teleport inflates it when the previous pose is stale. The body velocity is not changed by `RapierPhysicsPipeline.teleport`. While the hull is still on the blocks, the climb has to be faster than the contact readback (2 blocks per second above the deck) so a descending elevator does not look like thrust. The car is not pulled back while the streak is running. An inconclusive scan of a large ship (the 128-block cap) does not count as lost contact.

The server tick places the car only after the confirm. On a deck that is sliding (moving, not turning, and inside the 0.75 block step), the car is placed on its deck offset every tick and its Rapier body velocity is set to the deck's velocity, so a heavy ship moves at exactly the elevator's speed instead of trailing it on its suspension. The same velocity is given during the capture confirm. When the deck stops, the car is placed once more and that velocity is cleared. On a still or turning deck the car is placed only once the deck has drifted more than 0.2 blocks or the bearing has turned more than half a degree, and that placement does not change the ship's velocity. The placement is `PhysicsPipeline.teleport`, and it wakes the body. `updateLastPose` runs after the teleport so the next pose delta starts at the new pose. Ticks that do not teleport do not wake, so a parked hull can sleep and a takeoff can climb off.

## Bearing on a client

Sable 2.0.3 already interpolates sub-level snapshots. `ClientSubLevel` slerps from one networked pose to the next, and the server sends a snapshot when the logical pose changes. Carry does not add a client mixin. While a bearing turns, the server writes the carried pose at every half degree so those snapshots follow the deck instead of stepping eight degrees. The written pose is the pre-physics sample, the same pose as the kinematic collider. Putting the car on the live angle embeds it in that collider, so the ship stays about one tick behind the contraption (`errSeat` of one bearing step).

The angle stored at capture is Create's accumulated bearing angle. Later ticks rotate the captured offset and the boarding orientation by the difference around the bearing axis. Steering releases the lock first, so that rotation is not applied over the pilot's turn.

Create 6.0.10 `AbstractContraptionEntity.tick` calls `Map.entrySet` once, on `collidingEntities`. Sable 2.0.3 injects that call. This mod's hook uses the same invoke with ordinal 0 and mixin priority 1100, so it runs after Sable adds its kinematic. Sable's collider is suppressed only after this mod has claimed the contraption.

Sable 2.0.3 `RapierPhysicsPipeline.teleport` writes the Rapier body, copies that pose onto the sub-level, and returns without waking the body. Ship-hull 1.0.0-phase7 treats a Sable center-of-mass teleport as a copy onto a parked pose and not as a wake. This mod's teleport is that call, from `ServerTickEvent.Post`, and nothing after it wakes the body. The ship-hull mixin sources were not in this workspace; the Sable order was read from `RapierPhysicsPipeline`, and the wake rule from the ship-hull README.

A carried correction longer than 4 blocks or 90 degrees in one tick releases the lock instead of teleporting. A physics substep longer than 0.25 seconds does not write a ride velocity. A car sitting on a carried ship is moved with that ship. Chunk unload drops the lock and does not teleport. Rescue is skipped inside a portal and when the target cell is buried and no air spot is found within 16 blocks. Mixin targets are optional, and a thrown hook is logged once and swallowed.

This project is not published to Modrinth or CurseForge.
