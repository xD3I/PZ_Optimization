# Built-in benchmark (2026-10-18, branch `builtin-benchmark`)

Goal (maintainer, 2026-10-18): a benchmark players start from the game itself, built from our own predefined runs
(the south spin, the 120 km/h drive, the Louisville horde, the south Rosewood drive, the horde shooting), using the
state of the art of in-game benchmarks, ending with a performance report for the player. Every technique below is
implemented and measured, none dropped.

## Architecture

- **One scenario per process.** `pzopt.Harness` (route driving, scene pins, `DrivePilot`, `Showcase`) reads its flags
  once at class init and keeps its state in statics; it was built for one route per launch. The benchmark keeps that:
  `pzopt.Benchmark` writes a plan (`Zomboid/pzopt/benchmark/plan.properties`), each process takes the next step's
  flags (`HarnessFlags` falls back to the plan when there is no harness flag file) and pinned settings (`Config` reads
  them like `pzopt.properties`), runs it, and `pzopt.Restart` starts the next process. Every pass starts from the same
  process state: no JIT, heap or cache history from the pass before (process isolation).
- **A world made for the benchmark.** Players have no bench save. The shipped Lua (`pzopt_benchmark.lua`) creates a
  fresh sandbox world per pass (`createWorld`, spawn through the game's own debug-scenario hook `selectedDebugScenario.startLoc`,
  read by `IsoWorld.init`) with pinned sandbox settings, deletes it after the pass and restores `latestSave.ini`, so the
  player's saves and Continue are untouched.
- **Data**: presented frames from `Overlay`'s frame log (interval, GPU time from timer queries, thread loads), the
  game-thread step (`FrameCap.lastStepNs`), the frame pipeline from `Pacing` (step start, render acquire, swap, GPU
  done), GC / heap from JMX, power from `pzopt.Power`, chunk counts from `Stats`. Recorded only between route start
  and route end (warm-up excluded).

## Scenarios

| id | from bench | what |
|---|---|---|
| `spin` | spin | south through Rosewood at max zoom, facing spinning 90 deg/s, 25 s: cutaways, vision, lighting, chunk streaming |
| `drive120` | drive-120 | 120 km/h path drive east on KY-60, 1,200 tiles: the heaviest chunk streaming and bake load |
| `louisville` | louisville | downtown Louisville, population max (~2,000 zombies), slow spin: zombie simulation, animation, characters draw |
| `drive120south` | drive-120-south | 120 km/h through Rosewood: KY-60, North Main St, south through town |
| `horde` | horde-shoot | Riverside pier, the player (autopilot) shooting a horde of 150 with an M16: combat, ragdolls, gunfire |

## Techniques (status in the table at the end)

1. Scripted, repeatable scenarios in a purpose-made world (above).
2. Pinned scene: hour, weather, zoom, population, sandbox, game RNG seeded per pass.
3. Warm-up excluded (settle before the route); a separate untimed warm-up pass optional (shader / disk caches).
4. Several passes per scenario; mean, standard deviation, coefficient of variation, 95 % confidence interval
   (Student t); a pass more than 3 MAD from the median is flagged an outlier.
5. Frame-time metrics: average fps as frames / time (not the mean of fps), median, 1 % and 0.1 % lows (both
   definitions: the average of the slowest 1 % / 0.1 % of frames, and the 99th / 99.9th percentile frame time), max,
   stutters (frames over 2x the rolling median and over 50 ms), jitter (mean absolute change between successive
   frames), share of time below 60 / 30 fps, time-weighted histogram, percentile curve.
6. Per-frame bottleneck attribution (PresentMon "GPU busy" style): game-thread step, render-thread busy, GPU busy,
   frame cap; each frame classified game-thread / render-thread / GPU / cap bound; shares per scenario.
7. Utilization over the window (process CPU, machine CPU, game / render thread, GPU busy) and the objective's finding
   "below the cap with hardware left over".
8. Latency estimate: game step start to GPU completion (Pacing), median and p99.
9. Memory / GC: collector pauses (count, total, max) during the route, heap peak, allocation rate.
10. Power and efficiency: average W, J per frame, fps per W (when the platform reports power).
11. Chunk streaming: chunks loaded per second.
12. Load times: world load per pass.
13. Validity checks: focus loss / pause, route completed, background CPU (machine minus process), throttling
    (CPU clock drop over the pass), cap-limited runs; an invalid pass is named and left out.
14. System fingerprint: CPU, cores, RAM, GPU renderer / vendor / GL version, OS, Java, resolution, refresh, game
    revision, pzopt build, the non-default settings; a hash of them = the comparability key.
15. Score: geometric mean over scenarios of average fps and 1 % low (like 3DMark's sub-scores), per scenario too.
16. History and comparison: every result kept; compared with the previous result of the same fingerprint, per
    scenario a Welch t-test on the pass means: better / worse / same.
17. Stock vs optimized A/B: the same scenarios with PZ Optimization switched off (`enabled=false`) in alternate
    processes, speedup with its confidence interval.
18. Counterbalanced order (ABBA) so drift (heat, background) cancels between A and B.
19. Cool-down between passes (fixed idle, optional).
20. The in-game report: score, per-scenario tables, frame-time graph, percentile curve, histogram, bottleneck bars,
    comparison, recommendations.
21. Exports: standalone HTML report (inline SVG charts), JSON, and per-pass frame CSV in PresentMon's column names
    (CapFrameX and other analysers read it).
22. Share code (compact text of the summary) and the local leaderboard of the player's own results.
23. Recommendations from the bottleneck shares (GPU bound: upscaler / zoom; game thread: population; ...).
24. Enhancement cost sweep: the Visuals features switched on and off live in one pass on a still camera, the cost of
    each on this machine.
25. Command-line / automation mode: `-Dpzopt.benchmark=<scenarios>` (or `pzopt.properties benchmarkAutoRun`) runs the
    suite unattended, writes the results and quits.
26. Low-overhead HUD during the benchmark: scenario, pass, progress; the performance overlay hidden (observer effect);
    its cost measured.
27. Timedemo mode: the route advances a fixed step per frame, so every pass renders the same camera path frame for
    frame; the result is frames / time.
28. Screenshot at a fixed point of each pass (after the timed window) for a visual record.
29. Spike map: per-second fps aligned on route time across passes, spikes that recur at the same place (content) vs
    random ones (system).
30. Frame pacing against the display: present interval vs the refresh period (judder share).

## Profiling program

Every technique that changes what a pass measures gets an A/B of its own, one technique at a time, on the desktop
(5120x2160, RTX 4090, 9800X3D), through `harness/benchmark-run.sh` (spec options: `timedemo`, `nohud`, `noseed`,
`capped`, `warmup`, `shots`, `cool<s>`):

| Technique | Experiment | Read |
|---|---|---|
| 2 seeding | `spin:5` vs `spin:5:standard:noseed` | pass-to-pass CV of fps and 1 % low |
| 26 HUD / hidden overlay | `spin:5` vs `spin:5:standard:nohud` | fps difference against the CI, CV |
| 27 timedemo | `spin:5:standard:timedemo` vs `spin:5` | CV, spike map (recurring vs random seconds) |
| 3 warm-up / process isolation | `spin:4:standard:warmup` | pass 1 vs passes 2-4 |
| 19 cool-down | `spin:4:standard:cool30` vs `spin:4` | drift over the passes |
| 17/18 stock A/B | `all:2:ab` | speed-up and its interval per scenario |
| 24 feature sweep | `sweep:2` | per-feature cost, repeatability between the two passes |
| observer cost of the phase profile (25 Hz stack sampler) | `spin:5` vs `spin:5` + `gameThreadProfileHz=0` | fps against the CI |

## Stopping a run (2026-10-19)

Two Workshop comments on 2026-10-18 within twenty minutes: "I am in an endless benchmark bootloop. Everytime I start the
game, it autostarts the benchmark. The benchmark doesn't fully load probably due to a mod conflict. [...] I uninstalled
and then reinstalled but still the same" and "how to stop benchmark in game bruh, estimated 1hr to benchmark". The plan
(`Zomboid/pzopt/benchmark/plan.properties`) is claimed at every start while its state is `running`; a pass process went
straight from the countdown overlay into its world, read no key, and a world that could not load (a mod's error at
world start) made every launch a pass: the crash guard skipped a step after two deaths and the next step died the same
way (two launches per step of the plan), every claim refreshed `updated_ms` so the 20-minute stale rule never fired, and
neither Uninstall nor BootRepair nor the installers look at `Zomboid/pzopt`, so a reinstall resumed the plan. Now:

- **Esc, a mouse click or B** during the pass's countdown at the main menu stops the whole run (`Benchmark` "cancel" ->
  `BenchPlan.abortRunning`: the plan aborted, the pass worlds deleted, `latestSave.ini` / the options files restored); the
  process is a pass (Harness flags and Config pins from the plan) so it quits and `Restart` relaunches a normal game. Every
  pass shows its countdown for at least `STOP_GRACE_S` (4) s with the hint line; unattended runs (`auto=1`) keep 0 s and
  ignore input.
- **Two deaths on one step abort the run**, not the step (`BenchPlan.MAX_ATTEMPTS`), with the failed pass on record
  (`BenchResults.writeFailed`) and the player's files back at once.
- **A claim no longer stamps the plan** (`BenchPlan.writeClaim`): a player relaunching a dying game does not keep the plan
  fresh, so a plan untouched for 20 minutes is stale as documented.
- **Another build aborts the plan** (`build=` key, the pzopt commit that planned it) instead of resuming it.
- **Uninstall aborts a running plan** (`Uninstall.start`, with the restores: no pzopt process follows an uninstall) and
  **BootRepair abandons one** (`BenchPlan.abandon`, plain file access before game code) when it removes or replaces the install.

A player stuck on an old build: delete `plan.properties`, the next start is a normal game. `tests/pzopt/BenchPlanTest`.

## Status (2026-10-18)

All 30 are implemented (classes: `src/CLAUDE.md` "Bench*"); measurements in `docs/findings-builtin-benchmark-2026-10-18.md`.

| # | Technique | Where | Measured |
|---|---|---|---|
| 1 | scripted scenarios, own world | BenchSuite, pzopt_benchmark.lua `startWorld` | chain: `bench-all1` |
| 2 | pinned scene + seeded RNG | BenchSuite.COMMON, BenchSeed | Louisville CV 3.7 % seeded vs 20 % unseeded (`fseed`/`fnoseed`) |
| 3 | warm-up excluded / warm-up pass | settle per scenario; config W | no cold-pass penalty with one process per pass (`fwarm`) |
| 4 | passes, CV, CI, MAD outliers | BenchStats.passes | BenchResultsTest |
| 5 | frame-time metrics | BenchStats.frames | BenchStatsTest |
| 6 | per-frame bottleneck | BenchRecorder (hand-off wait out) | v-sync misread fixed (findings) |
| 7 | utilization, "below cap with hardware left" | BenchRecorder sampler, BenchAdvice | |
| 8 | latency estimate | Pacing rows | |
| 9 | GC / heap | JMX notifications | |
| 10 | power, J/frame, fps/W | pzopt.Power | |
| 11 | chunk streaming | Stats.loadedChunks | |
| 12 | load times | boot_seconds, load_seconds | |
| 13 | validity checks + idle gate | BenchResults.validate, BenchIdle; cap_held | background / focus / cap-held caught (`fab`) |
| 14 | system fingerprint | BenchSystem | |
| 15 | score (geomean) | BenchResults.configScore | BenchResultsTest |
| 16 | history, Welch vs previous | BenchResults history / PREV / CMP | |
| 17 | stock A/B with interval | AB lines, bootstrap ratio CI | Louisville 3.04x [2.89-3.18]; stock limiter bug fixed, `gab` |
| 18 | ABBA order | BenchOrder | BenchOrderTest |
| 19 | cool-down | plan `cooldown` | CV 0.6 vs 1.8 % (`fcool`) |
| 20 | in-game report | PzoptBenchmarkReport | screenshots `report*.png` |
| 21 | HTML / JSON / PresentMon CSV | BenchHtml, writeFramesCsv | |
| 22 | share code + leaderboard + compare a code | shareCode / decodeShare, history.tsv | BenchResultsTest round trip |
| 23 | recommendations | BenchAdvice | |
| 24 | feature cost sweep | BenchSweep (pre-roll re-bake, A/A noise floor, baseline shift) | `fsweep`, `gsweep`; BenchSweepTest |
| 25 | unattended mode | `benchmarkAuto` / `-Dpzopt.benchmark=` | every profiling run |
| 26 | low-overhead HUD, overlay hidden | Lua HUD, Config pins | no measurable cost (`fnohud`) |
| 27 | timedemo | Harness `fixed_step`, calibrated by the warm-up pass | fixed 1/60 s wrong (`ftimedemo`); calibrated `gtimedemo` |
| 28 | screenshot per pass | `shots` option | |
| 29 | spike map | BenchStats.spikeMap, SPIKES | |
| 30 | pacing vs the display | pacing_smooth_pct, refresh_miss_pct | |
| + | frame rate stability (3DMark) | BenchResults.stabilityPct | BenchResultsTest |
| + | observer cost of the phase profiler | `gameThreadProfileHz=0` | none measurable (`fnoprof`) |
| + | HUD progress: route bar, run %, time left | `Harness.routeFraction`, `Benchmark.secondsLeft` (16 s a pass beside its route, 52 s a stock pass, from the 10-18 step end times) | `hudprog` captures: 3 / 37 / 73 % per pass |
| + | run history with setup: PZ Optimization settings off default, game settings (options.ini), mods, Java agents; Past results > Compare (baseline vs selected, only what differs) | `BenchSetup` (`runs/<run>/setup.txt`, taken in the menu process before any pin), `BenchResults.compareRuns`, `PzoptBenchmarkRunCompare`; HTML report lists the setup | BenchSetupTest; `ui:runs` screenshot (`hist-a` vs `hist-b`, sunShadows) |
