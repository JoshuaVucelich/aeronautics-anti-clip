# Aeronautics Anti-Clip milestones

Mod id `sable_safe_landing`. NeoForge 1.21.1. Targets Create 6.0.10, Sable 2.0.x (tested against 2.0.3), and Create Aeronautics 1.3.x.

Aeronautics vehicles are Sable sub-levels. Sable deletes a sub-level whose bounds cross `sub_level_remove_min` / `sub_level_remove_max` (defaults -10000 / 100000). Moving Create contraptions (elevator, bearing, piston, gantry) are not reliable Rapier floors: a car driven onto an elevator can tunnel, fall through bedrock, and be deleted.

This repo is not published to Modrinth or CurseForge.

Each milestone bumps `mod_version` so the built jar hash changes. Headless repros live in `:sim:test` and do not start Minecraft or Rapier.

## M0 — Project, config, headless harness

- NeoForge mod skeleton, common config, and a Minecraft-free sim module.
- `HeadlessLandingHarness` integrates gravity with protection off, so a dropped body falls.
- Test: `HarnessBaselineTest`.

## M1 — Void rescue

- Remember each sub-level's last safe pose, keyed by dimension and ship id.
- Shift Y in place only when the pose is valid and the bounds are at or just inside Sable's `sub_level_remove` check. Min build height is not a trigger.
- An invalid pose with no recent, nearby last safe pose is left for Sable. It is not rebuilt at the origin.
- Repro: `VoidRescueTest` (in-place clamp at the remove bound, fail-open NaN, stale or distant last safe pose ignored).

## M2 — Contraption colliders in Sable/Rapier

- Feed elevator, bearing (including clockwork and stabilized), piston, and gantry colliders into Sable's kinematic pipeline.
- World contraptions are owned by this mod. Contraptions already inside a sub-level stay on Sable's own path.
- Repro: `ColliderFeedTest` (elevator is added, a windmill-only or unknown kind is ignored, a block change rebuilds, removal unfeeds).

## M3 — Carry / parent

- A sub-level resting on a moving contraption takes that contraption's translation, or its bearing rotation about the pivot.
- Repro: `CarryTest` — a car on an elevator that jumps upward stays on the platform; bearing, piston, and gantry cases move with the support instead of falling through.

## M4 — Swept penetration limit

- Sweep the sub-level's displacement against contraption volumes and stop it so remaining overlap is at most `maxPenetration`.
- Repro: `SweepTest` — a fast body does not tunnel through a thin platform; discrete overlap alone would miss.

## M5 — Hardening and release candidate

- Spatial hash, merged support boxes, NaN rejection, per-feature kill switches, and a rate-limited failure path.
- Repro: `HardeningTest`.
- A fed collider samples the contraption anchor once per tick so Rapier's kinematic velocity is the real platform motion. That contact pushes a car up and does not pull it down, and a velocity aimed through the deck is cancelled. While the car is resting, its offset in the deck frame is stored. After the contraption moves, the server tick places the car on the new pivot and angle. A horizontal correction past 8 blocks is refused, so a bearing cannot fling the car. Steps larger than 0.75 blocks or 25 degrees are ignored when sampling the ride.
- A separate bedrock rescue restores a sub-level that has stayed below min build height minus 32 blocks for 40 ticks to its last safe pose in the same dimension, with velocity zeroed. It does not use the 32-block distance gate, and it does not fire at the remove bounds.
- Version `0.6.0-rc.12` jar. Still not published to Modrinth or CurseForge.
