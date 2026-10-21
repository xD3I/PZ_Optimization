# The built-in benchmark (2026-10-18)

Plan and technique list: `docs/plan-builtin-benchmark.md`. Desktop (9800X3D, RTX 4090, 5120x2160 @ 165 Hz, v-sync on in
options.ini, KWin VRR off), the build of branch `builtin-benchmark`, an empty pzopt options file (defaults) unless named.
Runs: `harness/runs/bench-*` of the worktree, made with `harness/benchmark-run.sh`, tabulated with
`harness/benchmark-compare.py`.

## The chain works

- Smoke (`spin:1`, run `bench-smoke1`): menu -> plan -> restart -> fresh world at 8004,11206 -> settle 6 s -> 25 s timed
  -> report, 63 s and 2 processes in all; world load 3.3 s, boot 6.7 s.
- Every scenario once (`all:1`, `bench-all1`): 285 s, 6 processes, every route `complete`. Uncapped: spin 468 fps (harness
  spin on the same desktop ~470-500), 120 km/h drive 681, Rosewood town drive 603, horde shooting 335 (the autopilot runs
  to the pier and fires: 117 -> 112 zombies left in the window), Louisville 45 (see below).
- The UI: the report screen after a player-style run (`spin:1:standard:report`), the setup dialog and the past results,
  captured with spectacle (`harness/benchmark-run.sh` spec option `report`, `ui:panel`, `ui:history`). First captures at
  5K showed a dialog of fixed 1180 px, overlapping columns and empty line charts (`ISUIElement:drawLine2` draws a
  degenerate quad, invisible for near-horizontal segments); now sized to the screen, columns laid out by measured
  width, lines through `UIElement.DrawLine`.

## Bottleneck attribution needs the hand-off wait taken out

With v-sync on (the desktop's options) the smoke pass ran at the 165 Hz refresh and the first classifier said "game
thread bound 82 %": `FrameCap.lastStepNs` includes the game thread's wait at the frame hand-off (the render thread blocked
in the v-synced swap), 46 % of the game thread's samples. Now: game work = step minus the hand-off wait
(`LowLatency.pushed`), render work = acquire to the swap call, present = swap call to return (`Pacing.beforeSwap`); a
frame is GPU / game / render bound when that stage filled 85 % of its interval, display bound when it waited 40 % of it
in the present or sat at the limiter's cap, else "mixed". Uncapped passes also switch v-sync off in memory
(options.ini is restored by the next process).

## Louisville: the same route is a different scene in a fresh world

| world | zombies at route start | fps (uncapped) |
|---|---|---|
| harness bench save, preset (population 4x pushed at world-ready, teleport) | 2,265 (end 2,641) | 113 at a 120 cap |
| fresh world created at 4x, spawn in Louisville | 4,216 | 45 |
| fresh world, spawn in Rosewood, 4x pushed, teleport (= the preset) | 6,707 | 34 |
| fresh world, default population | 201 | 519 |
| fresh world, 2.5x pushed, teleport | 2,600-2,645 | 99 (56 with another program on 6 cores) |

The bench save's Louisville cells got their population when that save was created, at its own multiplier; the preset's 4x
only acts on what spawns after. A fresh world populates downtown from scratch at whatever multiplier is in force, so the
benchmark uses 2.5x to land on the bench save's crowd. Zombie counts are part of every pass result now
(`zombies_start`, `zombies_end`, `vehicles_end`).

## Background load invalidates a pass

The 56 fps Louisville pass had 36 % of the machine's CPU used by other programs (machine load minus the game's, from the
overlay's sampler), a Postgres import of the Grafana follower among them; the next pass with 4.6 % ran 99 fps. A pass
with 25 % or more is now invalid (named in the report, left out of the means), 10-25 % is a warning.

## Profiling program 1 (runs `bench-f*`, 02:31-03:44, idle-gated build)

One technique at a time against the plain run (`spin:5`, 5 passes of the Rosewood spin, uncapped, defaults). fps per pass
in plan order; CV = pass-to-pass coefficient of variation; ±95 = Student-t half-width of the mean.

| Technique | Run | fps per pass | mean ±95 | CV | 1 % low (avg) | Reading |
|---|---|---|---|---|---|---|
| reference | `fspin` | 483.2 482.5 484.8 480.9 502.2 | 486.7 ±10.9 | 1.8 % | 86.8 | |
| 26 HUD off | `fnohud` | 482.8 500.1 477.9 489.9 499.4 | 490.0 ±12.2 | 2.0 % | 86.4 | +0.7 %, inside the interval: the HUD (one text line, 2 Hz query) costs nothing measurable; kept on |
| observer: 25 Hz phase profile off | `fnoprof` | 489.1 472.5 492.8 483.7 480.9 | 483.8 ±9.7 | 1.6 % | 86.4 | no measurable cost; kept on (it gives the report's game-thread phases) |
| 3 warm-up pass | `fwarm` | 490.4 489.4 473.6 475.6 | 482.2 ±14.1 | 1.8 % | 84.1 | first measured pass vs the rest +2.3 %; without a warm-up the first pass is -0.9 / -1.8 / +1.4 / -0.2 % off the rest (fspin, fnohud, fnoprof, fcool): one process per pass already starts every pass alike, no cold-pass penalty to remove. Kept optional (and as the timedemo's calibration, below) |
| 19 cool-down 30 s | `fcool` | 485.8 482.5 487.8 489.6 | 486.4 ±4.8 | 0.6 % | 85.6 | same mean, a third of the spread (n = 4 vs 5, variance ratio 9: suggestive, not proven); costs 30 s a pass. Kept optional |
| 2 RNG seeding, Louisville | `fseed` | (invalid) 106.6 111.2 103.2 | 107.0 ±10.0 | 3.7 % | 15.8 | zombies at the start 2,600, CV 1.0 % |
| 2 unseeded, Louisville | `fnoseed` | 97.6 149.7 112.2 104.8 | 116.1 ±36.9 | 20.0 % | 33.4 | zombies CV 2.7 %; the 149.7 pass had a 1 % low of 87.7 against ~15 in every other pass: the horde was elsewhere, another scene. Seeding stays on |
| 27 timedemo, fixed 1/60 s step | `ftimedemo` | 241.8 230.7 239.9 241.5 246.5 | 240.1 ±7.2 | 2.4 % | 43.1 | **wrong as built**: at ~480 fps the 25 s route played in 6.2 s, 176 chunks/s streamed against ~63 in real time; a different load (game thread 94 %), and no steadier. Fixed: calibrated step (below) |
| 17/18 stock A/B, ABBA | `fab` | see below | | | | **the stock passes sat at the game's own 60 fps limiter**; fixed |
| 24 feature sweep | `fsweep` | | | | | costs inside the noise and a baseline step; fixed (below) |

The idle gate held: the longest wait before a pass was 85 s, most 3-10 s, and no pass of the program was invalidated by
background load. Two A/B passes were invalidated for focus loss (33 % and 13 % of the pass: the desktop was in use), the
validity check working as meant. Wall time: 250 s for 5 spin passes (6 processes: 50 s a pass with boot, world creation,
6 s settle, 25 s route); the 20-pass A/B 1,665 s.

### The stock passes were held at 60 fps

`enabled=false` turns off pzopt's frame cap, so the stock passes ran under the game's limiter from options.ini
(frameRate 60): drive120 59.9, horde 59.8, spin 58.9 fps, and the report said "11.5x faster". Only Louisville (stock 34.5
fps, under the limiter) was a real comparison: **3.04x [2.89-3.18], Welch p = 0.023, 1 % low 1.78x**. Fixes: an uncapped
run also sets `PerformanceSettings.setFramerateUncapped(true)` in memory (the stock loop reads it each frame; options.ini
is restored by the next process anyway); every pass at ≥ 95 % of its cap or with ≥ 60 % of its frames display-held gets
`cap_held=1` and a warning; the AB line carries which side was held and the report shows the speed-up as a bound
(≥ when the optimized side was held, ≤ when the stock side was).

### Timedemo, calibrated

The classic timedemo (Quake, Unreal: a fixed step per frame) assumes the render cost is independent of how fast the
camera moves. In this game it is not: chunk streaming and bakes scale with distance per wall second. Now, with timedemo
on, every scenario it applies to (the walked bench routes: spin, Louisville) gets a real-time warm-up pass, and the timed
passes step the route at that pass's frame rate (`BenchPlan.calibratedStep`, recorded as `timedemo_step`): the same camera
path frame for frame in every pass, at the real speed. An A/B runs on the wall clock (the stock pass runs at a fraction of
the step). Re-measured in `gtimedemo`.

### Feature sweep: noise floor and a re-baked baseline

The first sweep (2 passes) gave costs of -0.08 to +0.06 ms at a 1.1 ms frame, the same size both ways. Two causes, read
from its frame CSVs:

- **A baseline step.** The base windows (player's settings, nothing switched) were 1.176, 1.176, 1.224 ms, then
  1.076-1.092 ms for the rest of the pass, in both passes. The drop came with the ambient-occlusion switch, which re-bakes
  every loaded chunk texture (`chunk ao: settings applied, 361 chunks bake again`; tileDepthFix is back at stock depth
  after it, as at boot). So **the same still views draw ~0.1 ms (8 %) faster after a full re-bake than with the
  textures baked while the world streamed in**: a lead for the renderer (some per-frame work left behind by streaming-time
  bakes: per-frame tiles, neighbour appends), not followed here. For the benchmark: the sweep now forces a full re-bake at
  route start and leaves a 4 s pre-roll before its first window (`BenchSweep.PREROLL_S`), and each feature reports
  whether the baseline after it matches the one before ("back to baseline" / "moved").
- **No noise floor.** The sweep now estimates its own noise as an A/A control: each interior base window against its two
  neighbours, the same estimator as a feature's cost with nothing switched, robust (1.4826 x median absolute value) so
  one step does not widen it. Offline on the first sweep: 0.04 ms a pass with the step in. Costs inside twice the floor
  show as "≈0, within noise". `BenchSweepTest`: a 0.2 ms cost recovered to 0.01 ms, a free feature ~0, a 0.1 ms step
  named on its feature, the floor unaffected by it.
- The report's "No pass of this run is a valid measurement" on a sweep-only run was wrong (the sweep is unscored); fixed.
- The HUD now says what the still camera is measuring (`re-baking the view`, `your settings (baseline)`, `God rays on`),
  and a timedemo pass names its step: from outside, a sweep looked like a spin whose character stopped moving, and the
  old timedemo like a spin played at 4x.

## Re-measured after the fixes (runs `bench-g*`, 03:53-04:36)

### Stock vs optimized, both uncapped (`gab`, `all:2:ab`, ABBA, 20 passes, 1,717 s)

| Scenario | optimized fps | stock fps | speed-up [95 % bootstrap] | Welch p | 1 % low speed-up |
|---|---|---|---|---|---|
| Rosewood spin | 481.2 (CV 0.1 %) | 142.3 | 3.38x [3.32-3.44] | 0.004 | 3.31x |
| 120 km/h highway drive | 686.2 | 214.9 | 3.19x [3.14-3.24] | <0.001 | 4.45x |
| Louisville horde (2,590 / 2,622 zombies) | 102.7 | 34.8 | 2.95x [2.85-3.06] | <0.001 | 1.97x |
| Rosewood town drive | 599.2 | 183.0 | 3.27x [3.25-3.30] | 0.001 | 3.81x |
| Horde shooting | 342.6 | 210.6 | 1.63x [1.57-1.68] | 0.021 | 1.81x |

Score (geometric mean of 10·sqrt(fps · 1 % low)): optimized 1636 ±131, stock 576 ±66. No side held at a cap; the
Louisville factor agrees with the first A/B (3.04x), the one scenario the limiter had not touched. Background load stayed
under 5 % except the stock town drive's 21 % (a warning, under the 25 % invalidation line).

### Calibrated timedemo (`gtimedemo`, `spin,louisville:4:standard:timedemo`)

| Scenario | real time (ref.) | calibrated timedemo |
|---|---|---|
| spin | 486.7, CV 1.8 % (`fspin`) | 470.4, CV 1.2 %, route at the real speed (step = warm-up fps) |
| Louisville | 107.0, CV 3.7 % (`fseed`) | 98.3, CV 8.8 % |

Steadier where the camera is the load (the spin), worse where the simulation is: the game's simulation runs on the wall
clock (zombies move by real seconds), so frame-locking the camera alone makes the horde's relation to the camera depend
on the frame rate. The classic timedemo replays recorded *game state*; this game has no deterministic replay, so the
option stays off by default, and its description says it helps camera-bound scenarios only.

### Feature sweep with pre-roll and noise floor (`gsweep`, 2 passes)

Noise floor 0.004 ms (was 0.04 with the baseline step), every baseline shift under 0.01 ms. Costs at 1.15 ms frames
(~860 fps, Rosewood, noon, clear, max zoom, RTX 4090 at 5120x2160), GPU cost beside:

| Feature | cost ms / frame | GPU ms | earlier finding |
|---|---|---|---|
| Sun shadows | +0.051 | +0.055 | contact-shadows findings: GPU-side, tails at parity |
| Foliage sway | +0.048 | +0.051 | |
| Upscaling (FSR 1.0, 67 %) | +0.030 | +0.028 | on a 4090 in a light scene the FSR passes cost more than the 67 % render saves |
| God rays, AO, darkness floor, ripples, reflections | ≈0 (±0.004) | ≈0 | AO "0 standing" (AO findings); ripples / reflections need water or rain, the scene is dry noon |
| Remembered places | -0.016 | -0.038 | "no dearer than the stock pass" |
| Sprite filtering | -0.028 | -0.026 | composite "at or under stock" (sprite-filter findings) |
| Colour grading | -0.032 | -0.038 | screen pass 100 -> 72 us at 5K (darkness-grading findings): -0.028 ms |

The sweep reproduces, in one 90 s pass, the costs earlier dedicated passes measured.

## UI checks (build of 04:41)

- `bench-uicompare-*` (`harness/benchmark-run.sh ui:compare`, rig `devBenchmarkUi=compare`): the A/B report with another
  run's share code applied: "Their PC" line under ours, "Their fps" column (fps / 1 % low) for the scenarios the code
  holds, "-" for the rest.
- `bench-uireport-20261018-044506` (`spin:2:standard:report`): the new frame rate stability line (96.8 %, orange under
  97 %), the background-load warning of pass 1 (10 %), score 1919 ±413 with two passes.

## Open

- The sweep covers the features that switch live (11 of 14 on this build); pixelLight, relief and mirrors apply at the
  next launch and would need a process per state (as the A/B does), not measured here.
- The streaming-time bakes costing ~8 % more to draw than a re-bake of the same view (feature sweep, above) is a
  renderer lead, not followed.

## Flip and Mac checks (2026-10-18 afternoon)

Runs through the queue: `run.sh` / `run-mac.sh --benchmark <spec> --label bench-x [--prop k=v]` hand the job to
`harness/benchmark-run.sh` (the laptops' queue runs only the runner). The Mac needs `harness/benchmark-run.sh` copied by
hand for now: the queue syncs only `run-mac.sh`, `harness/mod/` and `macpower.py` there.

| Machine | Run | Spin fps | Town drive fps | Notes |
|---|---|---|---|---|
| flip | flip-b (defaults) | 117.3 | 152.4 | game thread 95 / 73 % of frames |
| flip | flip-c (sunShadows) | 87.0 | 131.0 | Compare screen: -25.8 % spin, setting listed by name |
| Mac | mac-a / mac-b / mac-c | 141 / 89 / 146 | 172 / 122 / - | every pass marked invalid, see below |

Found and fixed:

- **The player's game options were left changed.** A pass holds v-sync off and the frame rate uncapped in memory, and the
  game saves options.ini from many places (zoom, player events, its own exit). The restore ran at the next process's start;
  an unattended chain ends with a pass process, and `benchmark-run.sh` deleted the plan (with the backup) before the next
  launch. The desktop's v-sync stayed off from the first run (01:09) on, the Mac lost v-sync and its 120 fps limit. Fix: a
  pass process restores the player's options files in a shutdown hook (`BenchPlan.restoreAtExit`), the stale-plan path
  restores them too, `benchmark-run.sh` restores options.ini from an uncleaned plan before deleting it. Both machines' files
  were put back (desktop vsync=true; Mac vsync=true, frameRate=120) and mac-c confirmed they survive a run.
- **Technique 28 (screenshot per pass) never wrote a file.** `Core.TakeFullScreenshot` waits on the game thread for the
  render thread; at a route end it ran at the process exit (black) or not at all. `BenchShot` reads the back buffer on the
  render thread before the swap; a pass's shot goes straight into its run folder (`shot-NN.png`). Verified on the flip.
- Compare screen: invalid passes show "-" (not 0.0), long run labels on their own line, "none in either run" for mods.

Open:

- **Mac focus.** Every process the benchmark restarts opens its window unfocused (macOS 27 cooperative activation:
  `glfwFocusWindow`, five tries in `BenchFocus`, is refused), so its passes are invalid although the frame rate matches a
  focused pass (mac-c 146 vs mac-a 141 fps, spin). Options: relaunch through LaunchServices (`open -n`) on macOS, or count an
  unfocused but visible window on macOS as a warning instead of invalid. Not decided.
- **Background CPU on the Mac** counts WindowServer / kernel_task / coreaudiod work done for the game (~7-8 % of the 8 cores
  in mac-b's top samples) as "other programs"; the rest was XProtect scanning the fresh install and mediaanalysisd.
