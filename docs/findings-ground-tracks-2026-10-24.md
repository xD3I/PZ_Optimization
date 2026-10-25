# Ground that remembers (2026-10-24)

Feature 3 of the ground / grass proposal (`~/pzopt-wt/ground-proposal.html`). Branch `ground-tracks` (agent ground-tracks,
coordinator session b8396cf2), merged into `ground-integ`. Off by default; keys `groundTracks*` (`groundTracksLook`,
`groundTracksPom`, `groundTracksGrass`, `groundTracksWater`). The state is ground-common's `GroundState` (a per-chunk GPU
field, stamped by `GroundMovers`, decayed, saved beside the save); the look is baked into the chunk textures.

- **Cars:** tyre ruts with tread prints in mud, sand and snow, with raised edges.
- **Feet and bodies:** footprints, body imprints and drag marks.
- **Trodden grass:** the game's tall-grass sprites lie flat and go to straw over tracks; before, they hid the tracks completely.
- **Water in ruts:** ruts fill with the game's own puddle water and its raindrop rings.
- **Recovery:** grass in ~20 game hours, mud keeps its ruts for days, snow refills while it snows.
- **Persistence:** saved and reloaded across sessions, keyed by the save (runs write only the bench copy's folder).
- **Light:** ruts are relit by the sun, and by the moon at night. When the key light moves, the chunks with tracks re-bake at
  low priority.

## Results

Desktop: RTX 4090, 5120x2160, power-saver profile.

| What | Result |
|---|---|
| Drawing the tracks into a chunk texture | 5.1 µs per draw against an 89.9 µs chunk bake (`gtr-cost3`) |
| Trodden grass per chunk bake | +44 → +14 µs (one texture read on untouched ground) → +4 median / +7 mean with the floors left on the stock programs (`gtr-cost6..8`, ground-common 1ce6b4cd) |
| Stamping, 200 idle zombies | 0.60 µs GroundState + 0.81 µs GroundMovers a frame, steady state (the 8.55 µs first read was the start-up mean; ground-common 5f155c7d) |
| Stamping, walking hordes (steady state, on / off every 4 s in-run) | 700 walking zombies on a dirt field: 3.04 → **1.38 µs** a frame (`gtr-horde2` → `gtr-horde5`). Louisville horde (~2,400 zombies, ~60 fps): 25.3 → **10.3 µs** (`gtr-lou1` → `gtr-lou11`); whole-frame on − off −64 ± 61 µs, in the noise; Louisville runs checked (strongMarks 10-11k, black roofs) |
| 120 km/h drive at the 157 fps cap | first 1 %-low 146-150 vs 155 off: reloaded chunks re-baked their state. After ground-common 1e5f092f: 1 %-low 154, p99 6.5 ms, p99.9 12.7 ms = tracks off (`gtr-drive120-on4a`) |
| Real car | 1,251 stamps over 40 tiles, two soft ruts (`gtr-car1`) |
| Night, torch + pixelLight + relief | ruts read as grooves in the cone (`gtr-night1`) |

## Techniques, one by one

| Technique / key | Evidence | Verdict |
|---|---|---|
| Relief offset (POM) along the view ray, `groundTracksPom` | `gtr-snow2`: the rut walls get depth; without, a flat smear | **on** |
| Relight, self-shadow march, cavity AO | `gtr-var1`: relight is most of the look; colour alone is nearly invisible | **on** |
| Mud / snow material rules (cut, raised edges, slope settling) | `gtr-rig6`, `gtr-snow2`, `gtr-car1` | kept |
| Decay per material | `gtr-decay2`, `gtr-decay4` | kept |
| `groundTracksLook=patch` (in the floor tile shader) | +8-16 µs a frame under constant rig re-bakes, same as bake; cannot draw on snow | off |
| `groundTracksLook=composite` (redrawn every frame) | 116 µs GPU a frame (`gtr-cost4`) | off, dev only |
| `groundTracksWater` | baked water reads grey with no ripples; the puddle pass is clearly better (`gtr-wet1`, `gtr-wet2-puddles`) | **auto**: puddle pass when ripples are on, else baked |
| Moon as key light | `gtr-moon1/2`: vanilla night is black; with `darknessFloorPct=40` the ruts get a moonlit rim | kept, 0 cost |
| `groundTracksGrass` | `gtr-grass2`: 20,088 grass objects processed, wheel lanes show through tall grass (`gtr-grass1` void: sway moved every plant) | **on** |

Fixed on the way: the patch variant got all-zero light (no relight); the bake's GPU section now sits in the render stream; the
drive regression above. Discarded: `gtr-var2-low` (the desktop recorder caught another window; every capture since is in-game).

## Walking-horde pass (2026-10-24 night)

| Run | Change | GroundState | GroundMovers | Total µs a frame |
|---|---|---|---|---|
| gtr-lou1 | before | 18.10 | 7.16 | 25.26 |
| gtr-lou5 | longer surface cache, prints on pavement skipped | 15.28 | 5.15 | 20.43 |
| gtr-lou6 | + the characters' gather on a worker thread (`pzopt-ground-movers`; `devGroundMoversSync=true` puts it back) | 12.45 | 2.45 | 14.90 |
| gtr-lou8 | + game-side work at 30 Hz, cheaper chunk re-bake request (direct lookup, no grid walk) | 9.87 | 3.57 | 13.44 |
| gtr-lou9 | + the worker drops prints on pavement, chunk bakes refresh the surface cache | 9.55 | 2.78 | 12.33 |
| gtr-lou11 | + saved chunks copied on the IO thread, pending-chunk list | 7.85 | 2.41 | 10.26 |

Where the final Louisville 10.3 µs goes: prints to stamps 1.78, binning 1.64, re-bake bookkeeping 1.16, saved-chunk loads 0.78,
map recentring 0.61, decay 0.24, other 1.05; vehicles 0.73, characters 1.12. Every step is cache misses on a game thread
85-90 % busy; under 5 µs would need GroundState's game-side work on a worker (a rewrite of ground-common's shared part).
The worker spends ~14 µs a job, 0.4 jobs a frame. A horde caps chunk re-bakes at 12 a second (`groundTracksRebakesPerSec`).

Behaviour that came with it: characters' prints arrive 1-2 batches (33-66 ms) late; a parked car that drives off is noticed
within ~2 s, a new animal within ~10 s; the movers' event list carries characters at 30 Hz with a "natural ground" flag.
Relief double-apply fixed (04d5d6b7): with `reliefSunMode=bake` the tracks add no sun relief of their own (ruts read flatter;
the old look is the dev variant `doublerelief`). Two runs (gtr-horde4, gtr-crowd2) were submitted without `--mode bench`,
cancelled and re-run as gtr-horde5 / gtr-crowd3; gtr-crowd3 shows dense footprints and 0 worker failures.

## Tracks show at once (2026-10-25)

The maintainer asked for tracks that appear the frame they are pressed, as the default. Before, a print showed only at its
chunk's next re-bake: low priority, at least 300 ms later, at most 12 re-bakes a second under a horde. Key
`groundTracksRealtime` (default on, menu "Tracks: show at once"), branch `tracks-realtime` (8ae14f3a, agent ground-tracks),
merged into master. No override is touched. The bake stays the store; what changed since it is drawn on top each frame.

How it works:
- **Fresh marks.** `GroundState` marks every half square stamped since its chunk's last bake, plus the neighbour chunks
  within reach. A loaded neighbour gets its re-bake queued. A chunk's marks clear at its level-0 bake, in stream order.
- **Mirror.** Each level-0 bake copies the chunk's region of the state map, with one square of margin, on the GPU into a
  2560² mirror atlas. The mirror is exactly the state that bake used.
- **Per-frame pass.** After the composite, only marked chunks draw, over their marked half squares widened by the reach of
  relief and sun shadow. The shader reads the live map and the mirror and discards every pixel where they agree, so
  unchanged pixels stay the bake's. Where they differ, the baked pixel is scaled by the live track factor over the
  mirrored one.
- **Handover.** The re-bake draws the current state and clears the marks at that moment, so nothing pops.
- **Trodden grass.** The grass objects stay bake-only.

Candidates, measured on a 200-walker horde on dirt (desktop, uncapped ~870 fps, A/B within one run):

| # | Variant | GPU (`ground.rt`) | Look |
|---|---|---|---|
| 1 | Marked rectangle, widened by a square | 28 µs | pops at chunk borders |
| 2 | Per-square mask + reach | 17 µs | pops at chunk borders |
| 3 | Half-square mask + margin mirror + neighbour marks | 20 µs | no pops |
| 4 | 3 + discard where nothing changed (**adopted**) | 23 µs | no pops; equal to the bake where unchanged |

Cost of the adopted pass:

| Scene | Cost |
|---|---|
| 200-walker horde on dirt | +61 ± 21 µs GPU a frame |
| Lawn walk, 12 walkers | ~2 µs a frame |
| 120 km/h drive | 0 (paved road takes no tracks; only the mirror copies run) |
| Off-road 30 km/h | 0.8 µs a frame |
| Louisville horde | 20-26 µs GPU, 13-15 µs render thread; not in the game-thread samples |

Louisville frame tails. All nine runs launched in `mode=bench`, loaded the overrides, had clean settle frames and no bake
flood.
- **Uncapped pair:** p99 32.0 vs 31.7 ms, p99.9 43.7 vs 42.5 ms. The game thread is 96 % busy, the GPU ~41 %.
- **Two 60-cap pairs:** p99 ~4 ms worse with the pass on, so an A/B within one run (on / off every 2 s) separated cost from
  the horde's run-to-run spread:

| 60 cap, one run | p99 | p99.9 | frames > 25 ms |
|---|---|---|---|
| Realtime on | 32.5 ms | 36.1 ms | 38 of 1334 |
| Realtime off | 33.4 ms | 53.4 ms | 62 of 1258 |

The pairs' gap was spread, not cost: late-frame counts were equal (201 vs 206), and the slow frames are game-thread and
world-submit frames from the horde's arrival at the route's end, none of them in the pass.

Handover check: walkers stamp a dirt patch for 6 s, camera still, persisted tracks off (`gtr-rt-hand1*` / `hand0*`).
- With the pass on, prints update every frame; off, they update in 300 ms steps.
- After stamping stops, every loaded chunk's marks clear within ~0.1 s. The few small changes after that line up with
  ordinary object bakes and show with the pass off too.
- Fixed on the way: pops at chunk borders, margin marks that never cleared, and saved tracks of earlier runs changing at
  re-bakes in both modes.

Rigs: `devGroundCycle` variant `norealtime`; `devGroundTracksView=9` tints the pass's pixels (red changed since the bake).
Three runs (gtr-rt-v9b, hand1e, hand0e) were submitted with `speed=0.001` on a one-square route (≈1000 s). They were
cancelled and re-run with `speed=1`.

## Open

1. Louisville stamping 10.3 µs a frame, over the 5 µs target (the dirt-field horde is 1.4): next step GroundState's game-side
   work on a worker. The movers' worker reads the character lists off the game thread (a failed job retries; 200 failures put
   the gather back on the game thread).
2. With Relief's baked sun mode the ruts read flatter than the tracks' own relief.
3. Off on the Mac: the ground state needs GL 4.4 compute.
4. Tracks made in dirt before forced snow show as snow ruts; real snowfall refills them.
5. Relief infers the rut shape from the darkened colour; feeding it the real height is deferred.
6. Composite and stamp-only modes (dev) never re-bake, so trodden grass goes stale there.
7. Multiplayer: the state is per client, stamped from the movement each client sees, not synced.
8. Show at once: tracks loaded from disk show at their chunk's bake, not through the pass. A light or relief sun change
   between bakes shows at the next re-bake. Margin marks in an unloaded neighbour stay until that chunk bakes; they are never
   drawn. Zoomed texture-scale variants share one mirror tile a chunk.
9. Show at once costs 20-60 µs GPU in a horde on bare earth, over the ~10 µs aim set for the lawn walk and the drive. In
   Louisville it sits inside an idle GPU: the game thread is the limit there.

Test: `GroundTracksShaderTest`. Agent notes: `~/pzopt-wt/ground-tracks/build/notes.txt`.
