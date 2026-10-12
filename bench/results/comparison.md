# Pack benchmark, 0.7.0-rc.1

Headless dedicated server, flat world, no players. NeoForge 21.1.233, Create 6.0.10, Sable 2.0.3, Aeronautics 1.3.0 bundled, no Sodium. The bench mod is not in the release jar.

MSPT is server-tick wall time from `ServerTickEvent.Pre` at highest priority to `ServerTickEvent.Post` at lowest, so it is the work and not the 50 ms sleep. p99 is nearest-rank `ceil(0.99 n) - 1`. Stddev is the sample standard deviation.

Desync is a same-tick model of Sable 2.0.3 `SubLevelSnapshotInterpolator` and `ClientSableInterpolationState`: interpolation delay 1.5 ticks, dead reckoning clamped to one tick and only while the server has not sent STOP. The snapshot is the logical pose at post-tick highest priority, which is after tracking would have sent and before carry teleports. The server pose is the logical pose at post-tick lowest priority, after carry. Jitter is the RMS of the second finite difference of position, in blocks/tick². Rubber-band is the ship-hull failure shape: move packets under 25% of the samples, desync p99 over 4 blocks, the server still moving, and the client jitter under 5% of the server jitter.

A parked window is 400 ticks with the elevator assembled and no floor target. A ride window snapshots the deck and the vehicle, then targets four blocks down, and records the next 80 ticks. That trip finishes in about 40 ticks. The surface heightmap on this flat world was y=-60, and the elevator still had room to drop four blocks.

## Without the mod

| Scene | Ticks | MSPT mean | p99 | stddev | Outcome | Anchor | Car | Ship |
| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: |
| Parked | 400 | 1.574 | 5.482 | 0.914 | platform did not move | 0.000 | -0.000 | |
| Elevator | 80 | 1.319 | 2.881 | 0.532 | separated | -4.000 | -0.002 | |
| Ship | 80 | 1.358 | 4.664 | 0.760 | separated / separated | -4.000 | -0.005 | -0.003 |

The elevator left the car in the air. The ship and the car on it did the same. Nothing rubber-banded, because those bodies barely moved: desync p99 was under 0.001 blocks, and each body sent a handful of MOVE packets from pose noise.

## With 0.7.0-rc.1

| Scene | Ticks | MSPT mean | p99 | stddev | Outcome | Anchor | Car | Ship |
| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: |
| Parked | 400 | 1.582 | 3.086 | 0.611 | platform did not move | 0.000 | -0.000 | |
| Elevator | 80 | 1.526 | 4.137 | 0.618 | followed | -4.000 | -4.003 | |
| Ship | 80 | 1.963 | 4.039 | 0.526 | followed / followed | -4.000 | -4.005 | -4.003 |

The car on the elevator stayed on the deck. The ship on the elevator stayed on the deck, and the car on that ship stayed on the ship.

| Scene | Body | Desync mean | p99 | stddev | Client jitter | Server jitter | MOVE | STOP | Rubber-band |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Parked | car | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | 1 | 1 | no |
| Elevator | car | 0.139 | 0.947 | 0.221 | 0.089 | 0.124 | 46 | 4 | no |
| Ship | ship | 0.120 | 0.944 | 0.214 | 0.091 | 0.085 | 45 | 4 | no |
| Ship | car | 0.154 | 1.549 | 0.304 | 0.132 | 0.225 | 48 | 7 | no |

MOVE packets kept coming while the decks were moving, and the client jitter stayed on the same order as the server jitter. That is interpolation across carry's drift steps, not a client stuck at the origin. The nested car's p99 of 1.55 blocks is the largest gap in the window.

Parked MSPT matches the baseline. The elevator ride costs about 0.21 ms of mean MSPT. The ship ride, with two sub-levels and passenger placement, costs about 0.61 ms. Both are far under a 50 ms tick.

Raw rows are `baseline.json` and `withmod.json` in this directory.
