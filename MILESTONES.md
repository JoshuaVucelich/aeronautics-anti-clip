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

- Remember each sub-level's last safe pose.
- Restore it when the bounds drop below the dimension min build height, or come within the approach margin of Sable's remove bounds.
- If no safe pose exists, clamp back inside the world and zero velocity. Do not let Sable delete the body.
- Repro: `VoidRescueTest` (below min build height, and near `sub_level_remove_min`).

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
- Version `0.6.0-rc.1` jar. Still not published to Modrinth or CurseForge.
