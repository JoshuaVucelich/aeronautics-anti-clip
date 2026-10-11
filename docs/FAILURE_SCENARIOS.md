# Failure scenarios

Aeronautics vehicles are Sable sub-levels. The loss that started this mod is a car that fell through a moving Create elevator, then through bedrock. A second loss is an airship deleted after its grabbers froze. This note walks through how those happen and what `0.6.0-rc.26` does about each one.

The master switch is the config key `enabled` (default true). `/anticlip off` and `/anticlip on` flip it and save the file. `/anticlip status` prints the switch, the feature flags, how many carry locks are held, and whether the Sable removal hook has run. Off means no rescue, no collider feed, no carry, and no sweep. Disarm also clears `sable$initialized` on each claimed contraption, so Sable's next tick adds its own kinematic collider again. Mixin injects use `require = 0`, the mixin config is not required, and each inject is wrapped so a missing target or a thrown callback logs and returns. Those warnings are limited to one every five seconds. The server tick is not crashed by a Sable or Create mismatch.

## Car falling through an elevator

A kinematic deck pushes a dynamic body up and does not pull it down. Carry is the downward coupling: after five ticks of real block contact it teleports the car onto the stored deck offset. That teleport is skipped when it would do more harm than a fall.

- The correction for one tick is refused past 4 blocks or 90 degrees (`CarryGate.safeStep`). The lock releases with `reason=spike` and the car is left where it is. The angle in that check is the wrapped step, `wrapDegrees` of the degree delta, so a bearing that passes through 0 degrees once per revolution is a few degrees and not a release. Tested elevator motion is about 0.06 blocks/tick, and the scripted bearing is 9.6 degrees/tick, both inside the cap. A sample that jumps farther is a bad tick, not a ride.
- A physics substep whose timestep is above 0.25 seconds does not write a ride velocity. Sable's normal substep is `1/20/substeps` (0.025 at the default of 2). Rescue still runs on that substep. The pose placement on the server tick is unchanged, so a long wall-clock tick does not multiply the deck step.
- A stall (anchor and angle idle) still releases after 10 ticks, so a stopped elevator does not glue the car. Motion, including a reverse, clears that streak. The stored offset is applied at the deck's current pose, so a reverse that stays inside the step cap follows. It does not keep the old direction.
- Contact is a bottom face on a block top. A side hit from a drill, harvester, saw, or train does not capture. Trains, drills, harvesters, and ploughs are not contraption kinds this mod feeds or carries. A car standing on a gantry platform still is.
- Two cars on one deck are two locks. New captures examine at most 32 ships a tick, rotating through the list, and stop once 32 locks are held. Ships already locked are still maintained.

## Airship deleted because grabbers froze

Grabbers on an airship are contraptions inside that sub-level. This mod does not claim those. `isWorldSupport` is false when Sable reports a containing sub-level, so the feed never replaces their collider.

The freeze we can cause is a parent teleport that leaves a child behind. A car sitting on a ship that is itself on an elevator is a second sub-level. Carry locks the ship to the elevator. The car is not on the elevator's blocks, so it is not locked. Teleporting only the ship pulls the deck out from under the car and leaves the grabber holding the old pose. After a parent placement, sub-levels whose center sits inside the parent's footprint, and that do not have their own lock, are moved by the same pivot step, including the bearing rotation. A ship that only touches the hull from the side is not moved. At most 8 passengers. They are not given a lock, and their velocity is not cleared.

`RapierPhysicsPipeline.teleport` writes the body and the logical pose and does not wake or change linear velocity. A sleeping body then restores the pre-teleport pose on the next physics read, which is the fall through the deck. Carry calls `wakeUp` only on a drift correction, then `updateLastPose`, and does not call `resetVelocity`. Ticks that do not teleport do not wake, so an idle hull can still sleep. Ship-hull 1.0.0-phase7 copies a Sable center-of-mass teleport onto a parked pose and does not count that teleport as a wake. The sources for that mixin were not in this workspace. The order this mod relies on is: Sable's removal inject (priority 1000), our removal inject (default priority, `require = 0`), physics substeps, contraption entity tick (Sable at priority 1000, ours at 1100 after `Map.entrySet` ordinal 0), then `ServerTickEvent.Post` where the carry teleport runs. Nothing in this mod runs after that teleport except `updateLastPose` and the passenger translation.

A grabber that still expands the ship bounds out to `sub_level_remove_min/max` is the void-rescue path, not a carry path. Rescue refuses a ship when any block of its bounds is a nether portal, end portal, or end gateway. It refuses a target that is buried, or that contains liquid, when no clear cell is found within 16 blocks above or a short sideways step. The clear-cell query is world blocks and liquids. The ship's own plot is not an entity, so it does not occupy the cell it is being moved into.

## Chunk unload, restart, logout

Carry locks live in memory. They are not written to disk. Saving a lock and reapplying it after a restart would teleport a car onto an elevator that has since moved.

- The ship or the contraption's chunk unloading drops the lock with `reason=unloaded` and does not teleport. A removed entity in an unloaded chunk is this case, not a mid-air disassemble, so velocity is left alone.
- A ship whose level is no longer the level being ticked (a portal crossing) is dropped the same way. The last safe pose is keyed by dimension, so an overworld pose is not a nether rescue target.
- Server stop clears locks, fed colliders, and the in-memory safe-pose tracker. The saved entity position is whatever the last teleport wrote. On the next boot the car is not pulled toward a deck it is no longer touching.
- A player logging out does not by itself drop a lock. The car is not the player. If the logout then unloads the chunk, the unload rule drops it. Steering input disappears with the player, so a piloted lock releases on the next tick because the pilot is gone, and a car with no pilot keeps riding while the chunk stays loaded.

## Disassembly, absurd speed, rescue site

A deck that disassembles while the chunk is still loaded releases with `reason=disassembled` and does not place again. If the body speed is above 80 blocks/s or is not finite, it is zeroed. A normal fall is slower than that and is left to gravity, including a car released in mid-air.

Void and bedrock rescue already write a zero velocity. The rescue body is then shifted to the first clear cell in a fixed list: straight up by up to 16 blocks, then one block east, west, north, or south at a few heights. If every cell is solid, the rescue is not applied. The ship stays put rather than being embedded. The bedrock dwell starts again. A ship sitting in a portal is not rescued on that call.

## What is intentionally unchanged

The collider feed still does not capture. Takeoff still reads Rapier body velocity, not the pose delta. Bearing placement is still the pre-physics sample, half a degree at a time. `errSeat` of about one bearing step is still the pass metric.
