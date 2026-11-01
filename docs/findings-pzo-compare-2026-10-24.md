# PZO V1.1.2.2 (prop11) against stock and ours, and its code against ours (2026-10-24)

prop11's "Project Zomboid Optimiser" = Workshop item 3787481250 (Lua mod `MPOptimizer` 1.4.3, updated 2026-10-21 17:08 UTC)
plus PZO-Launcher (github.com/prop11/PZO-Launcher: `PZOptimEngine.jar`, a Java agent that patches game bytecode at load,
and `libpzo_native64.so`). Tested release: **V1.1.2.2**, published 2026-10-23 20:33 UTC, commit `aa93fa8` (= main HEAD,
jar `Implementation-Version: 1.1.2.2`). Earlier rounds: `findings-pzo-launcher-2026-10-02.md` (v0.9.9.4 / 0.9.9.5) and the
2026-10-06 table on the Workshop page (`68-` / `70-vs-other-mods-table.png`).

Why this round: on 2026-10-23 prop11 wrote on Steam that our mod "stole my parallelised multi-threading i spent months
working on", and the same day PZO-Launcher committed zombie threading of its own (`6f2bc0c` 05:35 UTC, `9a2aff7` 06:07,
`13fe21e` 11:02), all three in V1.1.2.2.

Desktop (5120x2160, RTX 4090, 16 cores, 30 GB, CachyOS), game 42.21 revision `4a0e9546ec`, ours = the installed build
(`941468b5-dirty`, installed 2026-10-23 00:18 UTC). **One run per row**: run-to-run spread was not measured, single
Louisville runs have varied about ±10 % before.

## PZO switches itself off next to us

V1.1.2.2 carries `com.pzoptimizer.IncompatibleModGuard` (added in `70ddf86`, 2026-10-22 13:57 UTC, "detect
PZ_Optimization"). `isPzOptimizationInstalled()` looks in the game folder and the folder of `projectzomboid.jar` for
`pzopt-installed.txt`, `pzopt/BootRepair.class`, a `pzopt/` directory with classes or `Uninstall-PZ-Optimization.cmd`, and
then for `pzopt/BootRepair.class` as a system resource. On a hit it logs "PZO is DISABLED for this launch: no bytecode
patches, engine governors, Lua bridge or Java mod loading will run", writes `{"optimized":false,"disabled":
"incompatible_mod",...}` and shows a dialog telling the player to uninstall PZ_Optimization.

Our harness runs every side with our classes installed (stock = `--prop enabled=false`), so the 2026-10-06 method would
now measure stock with PZO's launcher JSON and its Lua mod, not PZO. Three ways round it:

1. PZO's own benchmark on a truly stock game dir (our install moved aside for the job).
2. A rig on our harness that runs a truly stock game dir (no class of ours), for stock / stock G1 / PZO; ours on the normal
   harness, same time window.
3. The guard patched out of a local copy of the jar, PZO on top of our classes with `enabled=false` (not the released
   artifact).

Every game-dir change (our install moved aside, PZO's files, `ProjectZomboid64.json`, `~/Zomboid/Lua`, options.ini) was
made and undone inside its queued job, with a sha256 check of the restored state written to the run dir. All jobs passed
it; one early job overwrote four inert PZO status files left in the game dir from 2026-10-02 (`pzo_status.json` /
`pzo_telemetry.json` under `projectzomboid/Lua/` and `projectzomboid/Zomboid/Lua/`), later jobs back them up.

## The PZO side is the installer's setup

Checked on the 2(b) Louisville PZO run (`sr-louisville-pzo-20261024-013154`):

- **Launcher JSON:** the rig runs the JSON-editing Python block of the released `pzo_optimizer.sh` (byte-identical to the
  release script), on the stock 42.21 JSON, with the 8 GB tier the script picks for 30 GB of RAM. The launched JSON equals
  the script's output (main class `com/pzoptimizer/PZOEntrypoint`, classpath `.` / `PZOptimEngine.jar` /
  `projectzomboid.jar`, `-agentlib:pzo_native64`, `-Xmx8192m`, G1 with IHOP 45 / reserve 15, AlwaysPreTouch, its JIT and
  NUMA flags, in the same order) except `-Dzomboid.steam=0` for its `=1` (runs go without Steam; PZO reads the property only
  to detect a beta branch in `PZOConfig`) and our harness's `-Xlog:gc` line. The game's own launch log lists the same main
  class and flags.
- **Files:** the jar and `.so` where the installer puts them (game dir, `linux64/`, `natives/`, parent dir).
- **Runtime:** `pzo_status.json` = `optimized:true, ram_gb:8, g1gc:true, pretouch:true, native:true, version 1.1.2.2`; no
  "DISABLED"; the native library loaded (`Kernel QoS Active ... AVX2: ENABLED`); 53 "patched" lines in `pzo_engine.log`,
  among them `AnimationPlayer: zombie bone math queued for the frame workers`, `zombie postupdate: action-transition
  variables read on worker threads`, `[ActionParallel] Variable callbacks audited: 91 of 125 are pure reads`,
  `[FrameWorkers] 8 frame worker threads started`.
- **Workshop mod:** MPOptimizer 1.4.3 (the 10-21 Workshop copy, copied in for the run since Steam is off) loaded and set
  its default switches through PZO's bridge (`JVM_ZeroStutterGC`, `JVM_HordeHibernation`, `JVM_PathfindBudget`, ...).
- No ERROR lines over stock's.

## 1. PZO's own benchmark: its 10-23 threading makes it slower

`BenchmarkHarness` (settings in `~/Zomboid/Lua/pzo-bench.txt`, launch arg `-pzo_bench=`): continues the latest save, presses
click-to-start, 15 s warm-up (god mode, invisible, frame rate uncapped, time and weather forced), per zoom a 4 s settle, a
route on which the player is teleported a fraction of a tile per frame while each frame interval is recorded, two
screenshots; `results.json` with avg fps, p99 and 1 %-low (= mean of the slowest 1 %). Run here on a Louisville save made by
our preset (2,261 zombies at route start, population multiplier 4.0) with `zooms=1.0,2.0 route=line route_dir=S
route_tiles=150 route_speed=6 time=12 weather=clear`, the installer's JVM flags (G1, 8 GB). Not our Louisville bench: no
facing spin, zoom 1 / 2 instead of the widest, PZO's own frame clock.

| PZO V1.1.2.2 row (run) | zoom 1.0: fps / p99 / 1 %-low | zoom 2.0: fps / p99 / 1 %-low | CPU / busiest core / GPU (zoom 1.0) |
|---|---|---|---|
| full (`pzo3-full-20261024-005649`) | 43.5 / 45.7 ms / 19.2 | 36.3 / 48.9 ms / 10.9 | 32 % / 63 % / 31 % |
| threading off (`pzo3-threadoff-20261024-005954`) | **45.2 / 43.9 ms / 18.7** | **41.5 / 45.2 ms / 13.9** | 31 % / 69 % / 35 % |
| `stock=1`, every PZO feature off (`pzo3-stock-20261024-010217`) | 38.3 / 51.8 ms / 15.8 | 25.2 / 63.8 ms / 11.8 | 18 % / 81 % / 26 % |

- Threading off = `bones=0 skins=0 palette=0 simcap=0` plus a Lua mod in that save calling the bridge's
  `setJvmOption(..., false)` for `JVM_ActionReadParallel` (the action pre-read has no bench switch), `JVM_BoneBatch`,
  `JVM_SkinPrecompute`, `JVM_PaletteBulkCopy`; the log confirms all four and every threading counter in `results.json` is 0.
  With threading on the counters show it working (2,357,690 actions deferred, 298,648 bone-batch zombies, 953,752 skins
  precomputed).
- With the threading off PZO is 1.7 fps (zoom 1) and 5.2 fps (zoom 2) faster, p99 better at both. Both PZO rows beat its
  `stock=1` row (same JVM), so the gain over it comes from PZO's other patches (inferred; no per-feature A/B).
- Nothing saturated: CPU ≤ 32 %, GPU ≤ 37 %.
- For scale, `6f2bc0c`'s message reports "Louisville horde bench, 200-tile route at 16 tiles/s: 427/322 fps vs stock
  235/199 (zoom 1/2)". Their save and settings are not in the repository; the horde here is 2,261 zombies.

## 2. Same rig, same window: stock, stock G1 8 GB, PZO, ours

Rig (`~/pzopt-wt/pzo-cmp-stockrig/harness/pzo-compare/`, uncommitted): for the stock / G1 / PZO rows the job moves our
install and PZ_Controller (it overrides `AimingReticle`) aside, puts back the stock 42.21 JSON (ZGC, 3 GB; for G1 the
same with `-XX:+UseG1GC -Xmx8192m`, for PZO the installer's edit); our harness Lua presses Continue, a uinput mouse passes
click-to-start, a new Lua file (`pzopt_harness_stockrig.lua`, flag `stockrig=1`) drives the bench: god mode, invisible,
the storm pinned every tick with lightning every 6 s, the drive in a fresh RaceCar12 on cruise control at 120 km/h with no
steering (39.1 s), the Louisville walk S:150 at 6 tiles/s with the facing turning 90°/s at the widest zoom. Frame times from
MangoHud, utilisation from sysmon. Ours ran on the normal Java harness (path pilot with braking, 41.8 s; `instrument=true`
and our profiling on, which costs it something). Stock / G1 / PZO at frame cap 244 (none came near it), ours uncapped. The
current `--preset louisville` has no `see_all` (dropped 2026-09-23), so these Louisville numbers do not compare with runs
before that date. All 12 runs 2026-10-24 01:12-01:35, none discarded (Louisville settle frames clean; ours strongMarks 5714).

| bench | row (run) | fps | p50 | p99 | p99.9 | max | 1 %-low | CPU / busiest core | GPU | frames > 33 ms |
|---|---|---|---|---|---|---|---|---|---|---|
| drive120 | stock ZGC (`sr-drive120-stock-20261024-011226`) | 115.5 | 7.0 | 17.4 | 20.9 | 37.3 | 57.6 | 20 / 66 % | 93 % | 1 |
| | stock G1 8 GB (`sr-drive120-stockg1-20261024-011746`) | 119.9 | 6.0 | 17.2 | 19.9 | 32.5 | 58.0 | 17 / 71 % | 93 % | 0 |
| | PZO (`sr-drive120-pzo-20261024-011527`) | 114.9 | 6.3 | 18.6 | 20.0 | 20.4 | 53.7 | 17 / 64 % | 93 % | 0 |
| | ours (`sr-drive120-ours-20261024-011930`) | **638.3** | 1.4 | 3.1 | 7.3 | 85.4 | 320.4 | 16 / 52 % | 99 % | 1 |
| storm120 | stock ZGC (`sr-storm120-stock-20261024-012056`) | 70.0 | 13.9 | 69.7 | 87.4 | 109.5 | 14.3 | 20 / 76 % | 79 % | 31 |
| | stock G1 8 GB (`sr-storm120-stockg1-20261024-012243`) | 71.9 | 13.6 | 60.9 | 81.5 | 85.9 | 16.4 | 21 / 72 % | 80 % | 31 |
| | PZO (`sr-storm120-pzo-20261024-012426`) | 108.8 | 7.8 | 18.1 | 51.8 | 66.4 | 55.3 | 19 / 69 % | 90 % | 10 |
| | ours (`sr-storm120-ours-20261024-012615`) | **581.6** | 1.5 | 3.8 | 7.1 | 77.8 | 260.8 | 18 / 60 % | 97 % | 1 |
| louisville | stock ZGC (`sr-louisville-stock-20261024-012757`) | 26.0 | 32.3 | 132.8 | 165.9 | 178.9 | 7.5 | 20 / 79 % | 36 % | 308 |
| | stock G1 8 GB (`sr-louisville-stockg1-20261024-012957`) | 35.1 | 23.4 | 101.3 | 125.6 | 176.2 | 9.9 | 20 / 85 % | 40 % | 216 |
| | PZO (`sr-louisville-pzo-20261024-013154`) | 42.4 | 19.8 | 58.9 | 198.2 | 216.8 | 17.0 | 23 / 69 % | 36 % | 163 |
| | ours (`sr-louisville-ours-20261024-013341`) | **136.8** | 6.4 | 17.7 | 31.0 | 81.6 | 56.5 | 32 / 58 % | 42 % | 3 |

(ms columns: p50 to max. Run dirs under `~/pzopt-wt/pzo-cmp-stockrig/harness/runs/`.)

- **drive120:** PZO = stock (114.9 vs 115.5 fps, p99 17-19 ms in all three); ours 5.6x PZO.
- **storm120** (one noisy sample a row, no recording): PZO +55 % over stock with p99 18 vs 70 ms, G1 alone +2 fps, so the
  gain is its weather patches (`WeatherTileBatcher`: weather tiles drawn once per screen cell; `ShaderEarlyDepth`: puddle
  depth from the vertex stage; puddle scan on the ground floor only). Picture parity not checked. Ours 5.3x PZO.
- **louisville:** stock 26.0 → G1 35.1 → PZO 42.4 fps: 55 % of PZO's gain over stock is the G1 / 8 GB swap, PZO adds 21 %
  over the G1 control, with the worst p99.9 / max of the four rows. Ours 3.2x PZO, 3 frames over 33 ms against 163. Nothing
  is saturated in any row (GPU 36-42 %, busiest core 58-85 %) at well under 240 fps: a finding for every row, ours included.
- Console ERROR lines: 18-20 in every row, the same set.

## 3. Guard patched out, PZO on top of our classes

`isPzOptimizationInstalled()` rewritten to `iconst_0; ireturn` (JDK `java.lang.classfile`, `harness/pzo-compare/PatchGuard.java`
in `~/pzopt-wt/pzo-cmp-noguard`); nothing else in the jar changed (297 other entries byte-identical; jar sha256 `099e4185…` →
`3cb25e15…`). Louisville preset on the Java harness, our classes with `--prop enabled=false`, installer JVM flags, PZO row
with the Workshop mod. **"PZO V1.1.2.2 with IncompatibleModGuard disabled locally, not the released artifact."**

| row (run) | fps | p50 | p99 | p99.9 | 1 %-low | game thread | GPU |
|---|---|---|---|---|---|---|---|
| stock, G1 8 GB (`pzo1-lou-stock-20261024-010721`) | 31.8 | 26.9 | 104.1 | 219.0 | 10 | 98 % | 28 % |
| PZO noguard + mod (`pzo1-lou-noguard-20261024-010854`) | 32.7 | 27.0 | 71.4 | 124.8 | 14 | 98 % | 27 % |
| stock, shipped ZGC 3 GB (`pzo1-lou-stockzgc-20261024-011359`) | 24.7 | 35.6 | 119.1 | 131.1 | 8 | 99 % | 25 % |

PZO within noise of the same-JVM stock row; the game thread stays at 98 %. Its bone batching threw a second into the route
(`[BoneBatch] bone work failed on a worker ... IllegalStateException: no per-thread copy for scratch type pzopt.Tl`: its
`PerThreadScratch` cannot copy the scratch object our `AnimationPlayer` override holds) and switched itself off. It patched
27 of our override classes and skipped 5 patches whose target code it did not find (the `IsoGameCharacter` postupdate split,
`KahluaTableImpl.rawget`, Lua `Event.trigger`, the `VBORenderer` batch size, `FBORenderCell` foliage LOD). This row says how
PZO behaves on our install, not on vanilla; 1 and 2 are the measurements.

## Verdict

- PZO V1.1.2.2 against stock with the same JVM: nothing on the drive, about +20 % on the Louisville horde (2 and 1), a
  storm gain from its weather patches (one sample, picture unchecked). Its 10-23 multithreading does not add to that: PZO is
  faster with it off (1), and on our install it fails and switches off (3).
- Most of what a player gains from PZO's installer on 42.21 is still the launcher's ZGC 3 GB → G1 8 GB swap (2, 3: +29-35 %
  on the horde), as on 2026-10-06.
- Ours: 3.2x PZO on the horde, 5.3-5.6x on the drives, with p99 3-18 ms against 18-59 ms.

## Code similarities

Earlier audits: `findings-pzo-launcher-2026-10-02.md` "Similarities with this project" (fog shaders 111 of 115 lines ours,
the "PZAC" clip-cache magic, the pack index, PNG Paeth filter, copied javadoc text; 29 classes named after our features in
four commits on 09-30 starting 2 h after our `71efcf9` release) and a re-audit of `cfae2ea` (v1.0.2.1, 2026-10-22 07:26 UTC)
on 2026-10-23. Their history was rewritten between the two (the 10-02 hashes are gone, same content under new hashes).

Method: token 20-grams over PZO's `src/` against our committed `src/` (Java + GLSL, 602 files at `941468b5`); grams that
also occur in the vanilla corpus (all of `decompiled/` incl. the 42.20 tree, the game's shaders) and type casts are dropped
from both sides first, so two patches of the same game method do not count (`pzopt-dispute-evidence/tools/simil3.py 20`).
"As written" = identical token text; "names normalised" = identifiers replaced, structure only. The normalised share has a
floor from ordinary Java on game APIs: PZO's whole tree sits at 5.4-6.6 %, so only figures well above that say anything.

### What changed in `70ddf86` (2026-10-22 13:57 UTC)

One commit ("remove broken and unused governors, fix gameplay patches, detect PZ_Optimization") did three things:

- deleted the 12 classes the earlier audits found carrying our code: `FogQuarterBufferGovernor` (← `FogPass`),
  `FastTextureDecompressor` (← `PngFilters`), `UIFramePacingGovernor` (← `UiTicks`), `ScriptFastParser` (← `ScriptText`),
  `PZOUniformCache` (← `UniformCache`), `MipMapOptimizer` (← `MipMaps`), `ZoneEdgeGovernor` (← `ZoneGeom`),
  `CameraDriveSmoother` (← `DriveCamera`), `AudioCadenceGovernor` (← `SoundTick`), `AnimationClipCache` (← `AnimClipCache`,
  "PZAC"), `TexturePackIndexGovernor` (← `PackIndex`), `PlayerLosOptimizer` (← `PlayerLos`);
- removed the README section "Technical Comparison: PZO vs. PZ_Optimization", their only credit to us ("introduced some great
  ideas (especially quarter-resolution fog rendering)");
- added `IncompatibleModGuard`.

Whole-tree overlap before and after:

| PZO tree | tokens | as written, shared with ours | names normalised | longest shared runs |
|---|---|---|---|---|
| `cfae2ea` (v1.0.2.1, 10-22 07:26 UTC) | 174,972 | 1.49 % | 6.56 % | `FogQuarterBufferGovernor` ↔ `FogPass` 249 / 174 / 149 / 115 tokens, `FastTextureDecompressor` ↔ `PngFilters` 198 |
| `aa93fa8` (V1.1.2.2, tested) | 195,365 | 0.56 % | 5.44 % | `WorldUpscaleRenderer` ↔ `Upscaler` 71 / 68 / 59 / 55, `WeatherTileBatcher` ↔ `FogPass` 71 |

The `WorldUpscaleRenderer` runs are AMD's FSR 1.0 EASU math (`ffx_fsr1.h`) that both ports transcribe: not evidence of
copying.

### Still in V1.1.2.2, per class

| PZO class (first commit, UTC) | ours (first commit, UTC) | as written | normalised | note |
|---|---|---|---|---|
| `StreamerWake` (`ac52409`, 09-30 08:03) | `pzopt.StreamerWake` (`dc6961de`, 09-15 13:30) | 7.9 % | 14.1 % | same class name; shared runs: the `LockSupport.parkNanos(...)` wait with its interrupt check and `signal()`, the class header |
| `CpuTopologyGovernor` (`ec4e9bc`, 10-21 08:04) | `CpuTopology` (`a5fdbb6c`, 10-15 21:18), `CorePlacement` (`97fabb86`, 09-24 14:20) | 4.0 % | 27.8 % | the sysfs walk (`listFiles(... "cpu\\d+")` into a `Set<Long>`), the hybrid test on `/sys/devices/cpu_atom/cpus` |
| `ShortWait` (`26cdf1c`, 10-17 06:06) | `ShortWait` (`a4cd3038`, 10-11 03:24) | 2.0 % | 19.2 % | same name, 42 lines |
| `GcSlackGovernor` (`26cdf1c`, 10-17 06:06) | `GcSlack` (`fe0ac936`, 10-14 06:24) | 0.0 % | 1.1 % | same idea and name, own code |
| `WeatherTileBatcher` (`6f2bc0c`, 10-23 05:35) | `RainTiles` (`cc99c05e`, 09-20 15:21) | 5.2 % | — | "weather tiles drawn once per screen cell" = `rainTiles`; shared runs: our `FogPass` vertex-attribute set-up (71 tokens), `RainTiles`' projection / depth set-up (38) and vertex packing (34) |
| `ShaderEarlyDepth` (`6f2bc0c`, 10-23 05:35) | `puddleEarlyZ` (`ce1dc0cd`, 09-21 13:37) | 0.0 % | 3.7 % | same idea (puddle depth written from the vertex stage so the early depth test stays on), own code |
| `ChunkBakeBudget` (`6f2bc0c`, 10-23 05:35) | `bakeBudget` (09-19), `BakeScheduler` (`d84a7e04`, 09-25 01:03) | 0.0 % | 5.4 % | own code |
| `SpottedListGovernor` (`9297fca`, 10-22 17:58) | `PlayerLos` / `playerLosFast` (`ba89d84b`, 09-22 09:32) | 0.0 % | 4.9 % | same idea (a hash set mirroring `lastSpotted` so `updateLOS` stops scanning the Stack), own code; replaces the deleted `PlayerLosOptimizer` |

The 10-13..10-15 classes named after ours that the 10-23 re-audit listed (`AnimalLosSkip`, `BodyAtlasPrewarm`,
`HandoffRecalc`, `LookupMemo`, `PropIds`, `RagdollGate`, `WorkerRand`, `HotSave`) are no longer in the tree.

### The 10-23 multithreading

`ActionParallel` + `CallbackPurity` (`6f2bc0c`, 05:35 UTC), `BoneBatch` + `FrameWorkers` + `PerThreadScratch` (`9a2aff7`,
06:07), `GameThreadQueue` (`13fe21e`, 11:02): 1,255 lines, first committed 1-2 hours before prop11's Steam comment
(07:21 UTC) that we stole his multi-threading. Ours: `AnimBatch` (`6513d385`, pushed 2026-09-22 01:38 UTC), `ActionEval` +
`FrameBatch` (`ba89d84b`, 09-22 09:32 UTC), `AnimParallel` (`5db6a370`, pushed 09-23 00:56 UTC); `docs/plan-zombie-multithread.md`
(09-22).

- **Text:** 0.19 % as written (17 grams: an array-growth idiom, an `isBeingGrappled() || isGrappling() ||
  getReanimatedPlayer() != null` guard, an interrupt idiom); 7.43 % names-normalised against the tree's 5.44 % floor.
  Not copied text.
- **Design:** the same as ours. Each zombie's action-transition variables read on worker threads between the postupdate loop
  and the animator, only callbacks proven pure evaluated there (PZO: "91 of 125 are pure reads"; ours: `ActionEval.PURE_CALLBACKS`
  plus a game-thread snapshot of the rest), the result applied in order per zombie; the bone math queued from
  `AnimationPlayer` to frame workers after the `MovingObjectUpdateScheduler.postupdate` loop and joined before rendering
  (ours: `AnimBatch` from the same two classes), per-thread scratch, a skin precompute, a palette bulk copy (ours:
  `skinTransformsPrecompute`, `skinPalettePrecompute`), 8 workers.
- **Commit message:** `6f2bc0c` lists our 09-22 set item for item: the state-machine / action-context component memo
  (`getStateMachine` / `getActionContext`; ours `ecsLookupFast`), the ECS class cache, `isVehicleBetween` against vehicles
  snapshotted once per frame (ours `vehicleCull` / `zombieSpotFast`), the action pre-reads, and a "Louisville horde bench"
  (our preset's name).
- PZO's earlier multicore engine, the `MultiCoreHordeGovernor` family (`37be962`, 2026-09-07) is older than
  ours but, per the 10-02 audit, computes into snapshot arrays only its telemetry reads (no work taken off the game thread);
  0.15 % shared text.

In short: the copied files the 10-02 / 10-23 audits named were deleted on 10-22, the same commit that dropped the credit and
added the guard; what remains of ours in V1.1.2.2 as text is small (`StreamerWake`, the CPU-topology sysfs code,
`WeatherTileBatcher`'s GL set-up), while a run of features followed ours by days to weeks under our names or the same idea:
`ShortWait` 6 days after ours, `GcSlack` 3, `CpuTopology` 6, `SpottedList` 30, the zombie threading 30-31, `rainTiles` 33,
`puddleEarlyZ` 32.

Side-by-side images of the pairs (PZO's file at its first commit or at V1.1.2.2 on the left, ours as it stood before PZO's
file was first committed on the right; trailing `//` comments ignored; yellow = a line identical to one in the other file and
absent from the game's own classes and shaders, teal = near-identical (paired one to one, token similarity ≥ 0.75 with `this.`
dropped, the differing tokens marked: `BASE` / `base`, `slot` / `i`, `predictPaeth` / `predict`), grey = identical but also present
in the game's code): `~/Documents/pzopt-dispute-evidence/pzo/code-compare/` (`fog-vertex`, `fog-fragment`, `fog-rects`,
`png-paeth`, `anim-clip-cache`, `streamer-wake`, `cpu-topology`, `weather-tiles`, stacked with the eight comment pages below in
`all-pairs.png`; the 29
near-identical pairs as text in `near-identical-lines.txt`; made by `tools/code-sbs.py`).

### Comments

The line counts above compare code only: trailing `//` comments are stripped and a javadoc line counts only when it is
identical, so reworded prose is never counted. Comparing PZO's current tree for comments misses most of it anyway.
`80626f1` (2026-10-21 05:17 UTC, re-applied as `aff5d0b` at 08:05 UTC, which is also on the `Unstable` branch), "chore: rewrite comments to the natural comments
policy", rewrote the comments of 142 files (+511 / -1,327 lines; its message says the bytecode is unchanged). `70ddf86`
(10-22) then deleted the copied classes. So the comparison runs over every comment of every `src/**/*.java` and `README.md`
version in PZO's history: 3,005 distinct sentences. They are matched against our committed comments and Markdown, 40,270
sentences, in two ways:

- a verbatim run of 7+ words;
- a paraphrase, an idf-weighted overlap of 4+ content words that includes a rare one.

This gave 92 candidates. 43 pairs were picked by hand where both sides describe the same thing with the same specifics:
the same stock methods, the same numbers, the same order of facts. Generic overlaps were left out (a Workshop path, the
game's own exception text, "1 % low").

- 7 of the 43 share a verbatim run of 7–9 words:
  - "every tile sprite drawn with the tile-depth shader" (`PZOUniformCache`, 09-30);
  - "weather particles, model atlases, shadows, trees, the vision polygon" (`RenderBatchPatches`);
  - "the GPU's early depth test off, so every wet ... pixel hidden behind a wall, roof or object still ..." (`ShaderEarlyDepth`);
  - "asks every light switch for power every frame (grid power, ... 3x3x2 neighbourhood)";
  - "run task(s) 0..count-1 on the workers and ... thread ... the first failure / exception ... null" (`FrameWorkers` vs `FrameBatch`);
  - "looks the id up by name in TilePropertyAliasMap's HashMap on every call (... 0.8 % ...)";
  - "Vanilla updateLOS walks the whole cell object list for every animal every frame".
- The other 36 are paraphrases carrying our specifics. Examples:
  - "526 MB ... byte by byte (`TexturePackPage.readIntByte`) ... 0.5-0.6 s";
  - "2,200 .X ... jassimp ... 15-20 thread-seconds" vs our "2,209 .X ... jassimp ... ~17 thread-seconds";
  - "510 3D models and 20 blended";
  - "11 levels for 1024x1024";
  - "levels 1-3 ... LOD <= 1.33";
  - "about every 0.45 s ... `MainThread.invokeOnMainThread` ... 2-5 ms";
  - "~15 times within a single chunk hand-off";
  - "128 generic body combinations (female/male x idle/walktoward x 8 directions x 4 frames)";
  - "identity-hash mirror beside the tracking stack" vs our "identity set beside the stack";
  - "Windows ... rounds any park below 1 ms up to a full millisecond ... `Thread.yield()` (SwitchToThread)".

In all 43 our wording is older by commit date: 1 to 33 days before PZO's first commit holding it. The closest are `ShortWait`,
`BodyAtlasPrewarm` and `AnimalLos` (10-11 / 10-15 / 10-14 vs 10-17). Our commit dates are not our push dates; see
`../analysis/pushes.txt` in the evidence folder. Of the 43 PZO sentences, 25 are gone from V1.1.2.2 (rewritten on 10-21 or
deleted with their class) and 18 are still there; most of the 18 are in the 10-23 threading commits.

Side by side, PZO's comment on the left and ours on the right:

- the shared verbatim run is highlighted yellow and the shared content words teal;
- the rest of each comment is grey;
- each side shows the first commit that holds the text.

The images are in `~/Documents/pzopt-dispute-evidence/pzo/code-compare/comments/`: `comments-01..08.png`, stacked in
`comments-all.png`. The pairs as text are in `shared-comments.txt` and all 92 candidates in `candidates.txt`; both were made
by `tools/comment-sbs.py`, whose `--pick` and `--extra` arguments record the hand selection.

## Rigs and files (uncommitted worktrees)

- `~/pzopt-wt/pzo-cmp-ownbench/harness/pzo-compare/`: `ownbench.sh <full|threadoff|stock> <label> <save-dir>` (cmd job:
  stash our install, installer JSON, launch with PZO's bench, restore + check), `switchmod/` (the threading-off Lua mod),
  `ownbench-table.py`. Louisville bench save: `harness/runs/pzo3-lou-save` (prep run `pzo3-lou-prep-20261024-005334`).
- `~/pzopt-wt/pzo-cmp-stockrig/harness/pzo-compare/`: `stockrig-job.sh <row> <bench> <label>`, `stockrig-wrap.sh`,
  `stockrig-helper.py` (click-to-start, file copies, watchdog), `stockrig-submit.sh`, `stockrig-table.py`; Lua
  `harness/mod/pzopt-harness/42/media/lua/client/pzopt_harness_stockrig.lua`.
- `~/pzopt-wt/pzo-cmp-noguard/harness/pzo-compare/`: `PatchGuard.java`, `PZOptimEngine-noguard.jar`, `noguard-wrap.sh`,
  `submit-noguard.sh`.
- PZO release assets: `~/src/pzo-work/rel-1122/`; PZO source history: `~/Documents/pzopt-dispute-evidence/pzo/PZO-Launcher`
  (watched, one bundle per change).
