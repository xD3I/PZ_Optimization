# The Mac under outside CPU load: frame workers at user-initiated QoS (2026-10-16)

Worktree `~/pzopt-wt/f11-mac` (566b8184 + this change). MacBook Pro 14 M1 Pro (6P + 2E), normal power, Louisville horde
uncapped (run-mac.sh with the preset spelled out), Enhancements off, round-8 opt-in set (`submit-mac.sh` of f10-mac).

## The problem (f10-mac, 2026-10-15)

With a Colima VM building an x86 image beside the game (~3.7 cores outside the game) the optimized build fell from
55.5 to 38.5-43.5 fps while stock lost nothing (28.9-29.1 either way). The game process used *less* CPU under load
(3.9 vs 4.6 cores) and the game thread was only 86-89 % busy: it waited at the per-frame batch joins for frame workers
that ran at `QOS_CLASS_UTILITY` (`CorePlacement.background()`) and lost their cores to the default-QoS VM threads.

## Rig

Outside load: four `yes` processes on the Mac (default QoS, like a VM's threads) for exactly the queue job's run
(`burn.sh <job dir> 4 <max s>`: waits for the job's `running`, starts the burners over ssh with a deadline the Mac
enforces itself, kills them when the job ends). The machine is at 100 % CPU in the route window.

## Keys

- `workerQosMac` = user-initiated (default) | utility (the old class) | user-interactive | default: the QoS class of the
  frame-critical pools whose batches the game thread joins every frame (pzopt.FrameBatch workers, CharDraw, VisPolyAsync;
  `CorePlacement.frameCritical()`, re-applied before each batch so it can alternate within a run: ZombAb `workerQosMac`,
  the off half is utility). The streamer, recalc pool and lighting thread stay utility. No effect off macOS. The
  summary's `core_placement=` line on macOS now says `qos_ok=` / `qos_failed=` (1,078 / 0 in the A/B run: the class is
  really applied).
- `joinSpinPark` (off): `PlayerLosBatch.await` parks in 10 us slices after 200 spins instead of spinning until the worker
  that holds the chunk is done. Measured off.

## Results

In-run A/B under load (job 12987 `mac-f11-ab-load1`, `gtabsec.py`): workerQosMac user-initiated vs utility, 300 ms
periods: wall 20.5 vs 34.7 ms a frame, paired -15.4 +- 3.6 ms (19 periods); game-thread CPU -7.3 ms a frame
(postupdate -3.8, tile rendering -3.6, scheduler update -2.6, characters draw -1.6: the joins). joinSpinPark (group 2,
470 ms): +2.3 +- 4.1 ms, noise.

Whole runs, 4 cores of load, ABBA (jobs 12989-12992):

| run | workerQosMac | fps | p99 | p99.9 | max | >33 ms | game process |
|---|---|---|---|---|---|---|---|
| mac-f11-load-2 | utility | 35.4 | 73.2 | 159.4 | 207.2 | 174 | 3.2 cores |
| mac-f11-load-3 | user-initiated | **55.9** | **37.4** | 45.4 | 47.9 | 27 | 4.8 |
| mac-f11-load-4 | user-initiated | **54.1** | **36.9** | 51.0 | 57.3 | 30 | 4.4 |
| mac-f11-load-5 | utility | 34.3 | 77.9 | 101.7 | 108.3 | 196 | 3.2 |
| mac-f11-load-default | user-initiated (default, rebuilt tree) | 48.4 | 38.9 | 49.0 | 50.5 | 42 | 4.5 |
| mac-f11-load-default2 | user-initiated (default) | 53.3 | 37.0 | 49.6 | 57.6 | 35 | |

The two default-path runs (jobs 12997 / 12998) ran while Spotlight was indexing on the Mac (mds_stores up to 123 %,
three mdworkers) on top of the burners; QoS applied (`qos_ok=26 qos_failed=0`), bake counters healthy in every run
(strongMarks 5.1-6.4k, no see_all flood).

Quiet Mac (no outside load, load average ~2; jobs 12993-12995):

| run | workerQosMac | fps | p99 | p99.9 | max | >33 ms |
|---|---|---|---|---|---|---|
| mac-f11-quiet-1 | user-initiated | 59.7 | 33.0 | 50.3 | 64.3 | 14 |
| mac-f11-quiet-2 | user-initiated | 59.2 | 32.8 | 44.7 | 48.3 | 14 |
| f10 round 10 (2026-10-15) | utility | 55.2-55.8 | 39.9-40.8 | 52.1-54.0 | | 43-44 |

Quiet in-run A/B (job 12993): -0.92 +- 0.81 ms a frame (paired, 18 periods), game-thread CPU -1.17 ms. With
user-initiated workers the loaded Mac runs at the quiet level of the old build, and the quiet Mac gains ~7 %.

## Not changed

- Linux / Windows: `frameCritical()` returns at once (`MAC` is a static final); no desktop or flip run was needed.
- Other unbounded spins found by the audit: `Ripples.awaitBinned` (render thread on the Binner thread; ripples are an
  Enhancement, off in these runs) and `BakeRecord.laneWait` (prewake lanes spin up to 5 ms on the game thread's publish).
  Neither showed up here; candidates if a loaded run with Enhancements on regresses.
