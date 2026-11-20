# Aeronautics Anti-Clip milestones

Mod id `sable_safe_landing`. NeoForge 1.21.1. Targets Create 6.0.10, Sable 2.0.x (tested against 2.0.3), and Create Aeronautics 1.3.x.

Aeronautics vehicles are Sable sub-levels. Sable deletes a sub-level whose bounds cross `sub_level_remove_min` / `sub_level_remove_max` (defaults -10000 / 100000). Moving Create contraptions (elevator, bearing, piston, gantry) are not reliable Rapier floors: a car driven onto an elevator can tunnel, fall through bedrock, and be deleted.

This repo is not published to Modrinth or CurseForge.

Each milestone bumps `mod_version` so the built jar hash changes. Headless repros live in `:sim:test` and do not start Minecraft or Rapier.

## M0: Project, config, headless harness

- NeoForge mod skeleton, common config, and a Minecraft-free sim module.
- `HeadlessLandingHarness` integrates gravity with protection off, so a dropped body falls.
- Test: `HarnessBaselineTest`.

## M1: Void rescue

- Remember each sub-level's last safe pose, keyed by dimension and ship id.
- Shift Y in place only when the pose is valid and the bounds are at or just inside Sable's `sub_level_remove` check. Min build height is not a trigger.
- An invalid pose with no recent, nearby last safe pose is left for Sable. It is not rebuilt at the origin.
- Repro: `VoidRescueTest` (in-place clamp at the remove bound, fail-open NaN, stale or distant last safe pose ignored).

## M2: Contraption colliders in Sable/Rapier

- Feed elevator, bearing (including clockwork and stabilized), piston, and gantry colliders into Sable's kinematic pipeline.
- World contraptions are owned by this mod. Contraptions already inside a sub-level stay on Sable's own path.
- Repro: `ColliderFeedTest` (elevator is added, a windmill-only or unknown kind is ignored, a block change rebuilds, removal unfeeds).

## M3: Carry / parent

- A sub-level resting on a moving contraption takes that contraption's translation, or its bearing rotation about the pivot.
- Repro: `CarryTest`: a car on an elevator that jumps upward stays on the platform; bearing, piston, and gantry cases move with the support instead of falling through.

## M4: Swept penetration limit

- Sweep the sub-level's displacement against contraption volumes and stop it so remaining overlap is at most `maxPenetration`.
- Repro: `SweepTest`: a fast body does not tunnel through a thin platform; discrete overlap alone would miss.

## M5: Hardening and release candidate

- Spatial hash, merged support boxes, NaN rejection, per-feature kill switches, and a rate-limited failure path.
- Repro: `HardeningTest`.
- A fed collider samples the contraption anchor once per tick so Rapier's kinematic velocity is the real platform motion. That contact pushes a car up and does not pull it down, and a velocity aimed through the deck is cancelled. Steps larger than 0.75 blocks or 25 degrees are ignored when sampling the ride. The feed does not capture or teleport.
- Carry is on by default. A config written before that, `configVersion` 0, is migrated to carry on once. A ship is block-tested only when its box, inflated by 0.2, meets that contraption's bounds. Carry stores the deck-frame offset only after real block contact holds for 5 ticks, and it does not place the car before that confirm. The server tick teleports the car when the deck drifts, rotating position and yaw by the bearing angle since capture. Bearing steps of half a degree are written so Sable's client snapshot interpolation can follow; there is no client mixin, and the pose stays on the pre-physics sample. Takeoff reads Rapier body velocity, which a teleport does not change. Upward speed relative to the deck starts the takeoff streak on the blocks, and the car is not pulled back while that streak is running. The lock releases on lost contact, disassembly, an idle deck, that takeoff, or a steering player. A horizontal correction past 8 blocks is refused. The bearing pass metric is `errSeat`.
- A separate bedrock rescue restores a sub-level that has stayed below min build height minus 32 blocks for 40 ticks to its last safe pose in the same dimension, with velocity zeroed. It does not use the 32-block distance gate, and it does not fire at the remove bounds.
- Version `0.6.0-rc.24` jar. Still not published to Modrinth or CurseForge.

## M6: Failure scenarios

- `docs/FAILURE_SCENARIOS.md` records the elevator fall, a frozen-grabber deletion, and the surrounding cases.
- Carry releases on chunk unload without teleporting, refuses a one-tick step past 4 blocks or 90 degrees, and skips the ride velocity when a physics substep is longer than 0.25 seconds. A car on a carried ship is translated with that ship. New captures examine at most 32 ships a tick and stop at 32 locks.
- Rescue is not applied inside a portal, is not taken from another dimension, and walks a buried target up to clear air or skips it. Rescue velocity is zero. A disassembled deck clears only a non-finite or absurd speed.
- `enabled` is the master switch. `/anticlip status`, `/anticlip off`, and `/anticlip on` read and save it. Mixin configs are optional (`require = 0`) and hook bodies swallow a thrown callback. Those warnings are rate-limited.
- A bearing step is the wrapped angle, so a continuous turn through 360 degrees stays inside the spike cap. Passengers rotate with that step. A ship whose center is outside the hull is not a passenger.
- `/anticlip off`, and turning the feed off in the config, clears `sable$initialized` for every claim, including one that was never uploaded. The sync pass drops a disassembled or removed contraption from that map. Rescue refuses a cell that contains liquid. A portal check runs only when a rescue is about to fire, on the bottom layer, with stride `ceil(sqrt(area / 256))`, skipping unloaded columns, and stops at 256 reads. The clamp query is world blocks only.
- Repro: `FailureScenarioTest`.
- Version `0.6.0-rc.28` jar. Still not published to Modrinth or CurseForge.

## Release candidate 0.7.0-rc.1

- A long hull's portal scan stays inside 256 reads and still reaches the far end. A fresh config stamps `configVersion` without logging that carry was turned on.
- A car on a carried ship keeps the deck-frame offset from the first tick it was on the hull. Replaying only the latest pivot step let the physics read walk it off the footprint.
- Headless A/B on the pack (NeoForge 21.1.233, Create 6.0.10, Sable 2.0.3, Aeronautics 1.3.0 bundled, no Sodium) is in `bench/results/comparison.md`. MSPT is server-tick wall time. Desync is Sable 2.0.3's client interpolator (delay 1.5 ticks, dead reckoning clamped to 1 tick) against the pose after the server tick.
- Version `0.7.0-rc.1` jar. Still not published to Modrinth or CurseForge.

## Release candidate 0.7.0-rc.2

- A piloted passenger drops its seat. The next tick stores a new deck offset only while the car is still on the hull, so releasing on a new spot keeps that spot and releasing off the hull does not snap the car back. A correction past the step cap drops the seat too, and the following tick can carry from where the car actually is.
- Seats for removed passengers are dropped and do not hold one of the 8 slots. `forgetDimension` drops that dimension's seats.
- Version `0.7.0-rc.2` jar. Still not published to Modrinth or CurseForge.

## Release candidate 0.7.0-rc.3

- A level tick prunes seats only in that dimension. A Nether or End tick no longer clears every Overworld seat.
- A car that leaves the hull drops its seat. A child that takes its own lock, or whose level is no longer the one being ticked, drops its seat too.
- Version `0.7.0-rc.3` jar. Still not published to Modrinth or CurseForge.

## Release candidate 0.7.0-rc.4

- Offroad wheel rays land on Create contraption decks. Before this, a car's body rested on an elevator deck while its wheels sank through it, so it could not drive on or off. Config `wheelContact`, default on.
- Wheels on a deck count as carry contact, so a car parked on its wheels on a bearing deck turns with it.
- `/anticlip status` prints `wheelContact` and `wheelDeckHits`.
- Repro: `WheelContactTest` (ray bounds, nearby check, closer hit wins). Live trial: `-Dsable_safe_landing.wheels=true`.
- Version `0.7.0-rc.4` jar. Still not published to Modrinth or CurseForge.

## Release candidate 0.7.0-rc.5

- A wheel touch counts as carry contact only within the suspension reach (rest + tire radius + 0.25) and on an up-facing face, as Offroad itself decides. A wheeled ship hovering above a moving deck is no longer grabbed, and takeoff release is not delayed.
- Contraption bounds are cached once per level per tick (capped at 256), and each ship is checked once per tick. A wheel ray from a ship far from every contraption does three map reads. The mount's ship is cached, so no Sable lookup runs per ray.
- Expired wheel touches and idle ship entries are pruned every tick and on level unload.
- `wheelDeckHits` counts only accepted server hits. `/anticlip status` adds `wheelTouches`.
- `wheelContact` is documented as not synced: the server's value decides driving and carry, the client's only changes where wheels are drawn.
- Live trial adds driving while the elevator moves and an off-center turntable.
- Version `0.7.0-rc.5` jar. Still not published to Modrinth or CurseForge.

## Release candidate 0.7.0-rc.6

- A heavy wheeled ship on a moving elevator now moves at the deck's speed. While the deck is sliding, carry places the ship on its deck offset every tick and sets the Rapier body velocity to the deck velocity, during the capture confirm too. Before, only the pose-delta field was written, so the body trailed the deck, sank onto it, and a rebound looked like a takeoff and released the lock.
- When the deck stops, the ship is placed once more and the ride velocity is cleared, so it does not coast past the top.
- Bearings are unchanged: a turning deck is not sliding.
- Wheel trial adds a heavy ship (about 140 blocks, large tires, 11x11 deck) riding up and down at 32, 128, and 256 rpm, plus 256 rpm with `wheelContact` off.
- Version `0.7.0-rc.6` jar. Still not published to Modrinth or CurseForge.

## Release candidate 0.7.0-rc.7

- The horizontal deck-frame offset is stored when the capture confirm finishes, not at the first touch. The height stays the first touch's resting height, and a bearing keeps the whole first-touch offset. A ship that moved over the deck during the confirm was pulled back to the first touch, or released as `separated` when that was more than 8 blocks away. That is the release the real server logged after the fling.
- On a sliding deck, a ship moving more than 2 blocks per second across it, relative to the deck, restarts the confirm and logs `LIVE deck skid` once. A flung or pushed ship is captured only after it has stopped on the deck. Turntables are not checked.
- Wheel trial adds `heavy-up-16` and `heavy-down-16` (the real server's elevator speed) and `heavy-fling-16`, which scripts the real fling (113 blocks per second) and skid (50 blocks per second for 6 ticks) on a 27x27 deck. `-Dsable_safe_landing.cases=a,b` runs only the named cases.
- Version `0.7.0-rc.7` jar. Still not published to Modrinth or CurseForge.

## Release candidate 0.7.0-rc.8

- Fixes the server stall on Huge Josh Land. With rc.5 and rc.7 the main thread froze for about 70 seconds at a time while the elevator contraption was loaded, even with nothing on it. Cause: every physics tick, carry rebuilt support boxes for every block of every loaded world contraption and joined them with `AabbMerger`, which restarts its pair search after every join. That cost grows with the cube of the block count: 2,229 blocks took 1.8 s per tick in the stress trial, and 6,854 blocks took about 50 s in a bench. The code dates from 0.6.0-rc.2 and was first run on the server today. The wheel ray code was not the cause.
- Deck work now runs only while a ship is on or next to a contraption deck. A bounds test against Sable's ship boxes decides that, never a block scan. The deck shapes are built only while that deck has moved in the last 40 ticks or holds a ship. A deck with nothing on it costs a motion sample and one box test per tick.
- Deck shapes come from a per-contraption index of block cells, built once per contraption shape a slice at a time, and only for the part of the deck within reach of a ship. Blocks are joined with `GridMerger`, a greedy mesh that is close to linear: a 5,985-block layout that took `AabbMerger` 37 s takes about 5 ms, and 45,913 blocks take about 33 ms. On the live path only the reachable part of a deck is merged. A deck turned off a quarter turn only joins blocks along its axis, so the rotated boxes cover the same space as before.
- New config `tickBudgetMicros`, default 2000 (2 ms), range 0 to 50000. It is a hard cap per dimension per server tick on deck index building and deck shapes, and separately on wheel rays against decks. Past the cap the carry reuses the last deck shape and wheel rays keep Offroad's own result for the rest of the tick. 0 turns deck work off.
- The collider feed plans once per game tick instead of every physics substep, reads the live contraption list instead of every entity, caches each contraption's shape hash, skips re-planning while nothing changed (at most 20 ticks), and does not retry a collider that failed to build until its shape changes.
- Carry capture checks that the deck is moving before any block test, and only looks at ships whose box touches a deck. The ship block test stops after 32,768 cells.
- `/anticlip status` adds `tickBudgetMicros`, the mod's mean and worst time per tick, the deck index count, and how often each budget ran out. The log notes `LIVE feed collider` and `LIVE deck index` with block counts and build time.
- Stress trial: `-Dsable_safe_landing.stress=true` (`stress.half`, `stress.layers`, `stress.rpm`, `stress.modOff`). A large glued elevator, a heavy wheeled ship parked away from it, and a fake player on the deck; then a second heavy ship rides the elevator. Logs tick time and the mod's own time per phase.
- Version `0.7.0-rc.8` jar. Still not published to Modrinth or CurseForge.

## Release candidate 0.7.0-rc.9

- Performance is the release gate. Joshua reported rc.8 made the game lag. The rc.8 server log (Nov 18) shows four "Can't keep up" warnings of 2.1 to 4.1 s, each within seconds of a join or leave. Logs from Nov 17 and Nov 18, before Anti-Clip was installed, show the same pattern at join (3.3 s, 3.8 s, up to 17 s). The only Anti-Clip work logged in that session was one 55 ms collider build for the 2,302-block elevator, which keeps the same id across sessions and was never rebuilt per trip. The stalls are the server's own join cost, not this mod. What rc.8 did cost every tick even with nothing riding: void rescue on every ship every substep, a motion sample with string keys for every contraption, the live-key scan, wheel rays scanning every contraption's bounds, and a client contraption tick hook. None of it ran per frame and there is no render code. This release removes or gates all of it.
- New `idleSleep` (default on). A per-level deck gate reads each world deck's anchor once per tick. Deck work runs only while a deck that moved in the last 40 ticks has a ship within reach, or a ship is held. Whether any ship is near any deck is rechecked every 10 ticks, so a deck's index is ready before it moves. A second check at the end of the tick catches a deck that started moving after the first check, so a ride starts on the deck's first moving tick. Off runs the checks every tick like rc.8.
- New `clientWheelContact` (default on, client only). The client ground ray does nothing while the car is away from decks. The contraption tick hook returns at once on the client.
- `tickBudgetMicros` is now a true hard cap. Every capped step is checked before it runs against a learned cost per unit, with a quarter of the cap kept for wheel rays: index slices, deck shapes, capture block tests, wheel rays, and collider uploads after the first in a tick. Merge and wheel rays share one budget. A learned cost rises at most fourfold per sample, small samples are counted as at least 64 units, and deferred work halves any excess, so a cold JIT or GC pause cannot lock a step out. Values 1 to 499 are raised to 500.
- Void rescue runs its full step only for a ship within 32 blocks plus its fall speed of the void floor or build ceiling. Everything else is a height read.
- Deck list cached per level and rebuilt when a contraption joins or leaves, or next tick while one is still loading. The collider takeover reuses Sable's own mass tracker and bounds instead of building them a second time, keeps Sable's collider until the takeover, and paces uploads. The shape hash no longer walks every block.
- `/anticlip status` adds a `Server time` line (mean and worst per tick, last minute, busy ticks, gate counts, budget-outs, deferred steps and builds, uploads, rescue steps versus skipped). `/anticlip status reset` (op 2). Client command `/anticlipc status` and `/anticlipc reset`.
- Stress trial adds `stress.count`, `stress.smallHalf`, `stress.ships`, `stress.reload`, and `stress.churn` (small decks disassemble, reassemble, and take a trip every 60 ticks). Wheel trial logs a `WHEELS PERF` line with the mod's tick time, deck ray calls and mean cost, and the idle client wheel check per call.
- Results, Linux box and Joshua's Mac mini (M4, Zulu 21 arm64, headless dedicated server in a scratch folder): 93 unit tests pass. Wheel trial 37 of 37 results match rc.8 on both machines (two gap readings differ by 0.01), 0 takeoff releases, `heavy-fling-16` spike, skid, and recapture as in rc.8. Live trial 13 of 13 outcomes match rc.8.
- Stress, 23,121-block elevator, 64 small decks, 12 parked ships, heavy rider, mean server tick in ms with the mod on and off. Box: idle still 4.25 and 4.16, idle moving 3.30 and 3.40, ride 3.72 and 6.74, churn 4.07 and 7.53, reload 3.48 and 3.30. Mac: idle still 4.15 and 3.31, idle moving 3.37 and 3.58, ride 2.87 and 5.18, churn 4.20 and 7.31, reload 3.43 and 3.35. The mod's own time with nothing riding: 103 us per tick on the box and 135 us on the Mac (rc.8 under QA's same load: 249 us). The ride is held with 0.31 max lag on both.
- Version `0.7.0-rc.9` jar. Still not published to Modrinth or CurseForge.
