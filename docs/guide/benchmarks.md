# Benchmarks

[Back to the README](../../README.md) · [Desktop](#desktop-the-daily-re-measure) ·
[Louisville on four devices](#louisville-horde-on-four-devices) · [Handheld and MacBook](#handheld-and-macbook) ·
[Windows](#windows) · [Old 4-core laptop](#old-4-core-laptop-a-locked-60) · [Boot and load](#boot-and-load) ·
[Other performance mods](#against-the-other-performance-mods) · [Feature passes](#feature-passes)

Every number here comes from the hands-off [benchmark harness](harness.md): a scripted route on a copied bench save,
the game's own frame log (`pzopt-overlay.out`, every presented frame) for the frame-time tail, and a sampler for CPU and
GPU load over the same window. **fps** = presented frames per second over the whole route, **p99** / **p99.9** = the
frame time 99 % / 99.9 % of frames stay under, **1 % low** = the fps of the slowest 1 % of frames, **GPU** = GPU busy,
**game thread** = the game thread's share of one core. Every run is on the
[live dashboard](https://pzo.diegov.dev) frame by frame; [`docs/results.md`](../results.md) lists them in order.

**Stock** is the game as it ships (`enabled=false`, which runs every override's original code), with its frame cap at
240 fps, which it never reaches on these routes. **Optimized** is a release at its defaults, uncapped, with the
Enhancements off unless a row says otherwise.

## Desktop: the daily re-measure

Each day's release is re-run on the same five routes ([the dashboard's "fps by day" chart](https://pzo.diegov.dev)).
Ryzen 7 9800X3D, RTX 4090, 32 GB, CachyOS, NVIDIA OpenGL, 5120x2160, Build 42.21, max zoom (the shootout at zoom 1).
Optimized: release `a0cc7a4a` (2026-10-15); stock: Build 42.21 on 2026-10-02 (the shootout 2026-10-04, Louisville
2026-10-15). Means of two runs a side.

| Route | Side | fps | p99 / p99.9 | 1 % low | GPU / game thread / render thread |
|---|---|---|---|---|---|
| 120 km/h highway drive (1,200 tiles east) | stock | 107 | 21.5 / 24.7 ms | 51 | 92 / 58 / 75 % |
| | optimized | **663** (6.2x) | 3.3 / 6.3 ms | 301 | 97 / 70 / 57 % |
| The same drive in a thunderstorm with heavy fog | stock | 53 | 58.8 / 80.4 ms | 13 | 80 / 90 / 74 % |
| | optimized | **504** (9.5x) | 4.4 / 8.0 ms | 219 | 96 / 85 / 51 % |
| Rosewood walk with the view spinning 90°/s, thunderstorm + heavy fog | stock | 31 | 95.6 / 146.2 ms | 9 | 53 / 94 / 58 % |
| | optimized | **293** (9.3x) | 9.0 / 17.2 ms | 105 | 82 / 95 / 47 % |
| Horde shootout on the Riverside pier (Jev fires on a horde of 150, 60 s) | stock | 144 | 15.3 / 23.6 ms | 62 | 55 / 99 / 24 % |
| | optimized | **333** (2.3x) | 6.4 / 9.1 ms | 155 | 92 / 83 / 19 % |
| Downtown Louisville, population max (~2,000 zombies), spinning walk | stock | 44 | 55.1 / 77.0 ms | 15 | 31 / 97 / 28 % |
| | optimized | **133** (3.0x) | 16.9 / 26.2 ms | 56 | 40 / 96 / 30 % |
| | every optimization, experimental ones too | **222** (5.0x) | 11.4 / 15.9 ms | 87 | 58 / 94 / 39 % |

Stock is bound by its game thread on the horde routes (97-99 % of a core with the GPU half idle) and by the GPU on the
drives at 5K. Optimized, the drives and the shootout use the GPU to the full (92-97 %); the spinning walk and Louisville
are still game-thread bound (95-96 %), Louisville with the GPU 40 % busy: the zombie simulation is the remaining wall
there, which the experimental keys (`entityUpdateParallel`, zombie LOD, `postupdateParallel`, `tileRecordParallel` ...,
[Settings](settings.md)) move further.

Runs: `daily-stock4221-1/2`, `dstorm-stock4221-1/2`, `dspin-stock4221-1/2` (2026-10-02 20:40-20:51),
`dhorde-stock4221-5/6` (2026-10-04 17:43-17:47), `lourace-stock1-20261015-215851`; `daily-1015-*`, `dstorm-1015-*`,
`dspin-1015-*`, `dhorde-1015-*`, `dlou-1015-*` (2026-10-16 00:50-01:22), `lourace-opt1-20261015-220030`. The stock
Louisville and shootout runs of 2026-10-02 were measured beside an editor using ~1.8 cores and are excluded.

## Louisville horde on four devices

The same Louisville route on every test machine, uncapped, Enhancements off, stock against every optimization on (the
experimental ones included), 2026-10-15; means of two runs a side (desktop: one race each). The p99 here is the frame at
99 % of the route's sorted frame list (`harness/slowframes/goal.py`).

| Device | Stock | Optimized | Faster |
|---|---|---|---|
| Desktop, 5120x2160 (Ryzen 7 9800X3D, RTX 4090) | 44 fps · p99 67 ms | **222 fps** · p99 11.5 ms | 5.0x |
| Ayaneo Flip, 1080p (Ryzen AI 9 HX 370, Radeon 890M) | 32 fps · p99 70 ms | **82 fps** · p99 22 ms | 2.6x |
| MacBook Pro 14 (M1 Pro, 1512x982) | 29 fps · p99 117 ms | **56 fps** · p99 40 ms | 1.9x |
| Dell laptop, 1080p (Core i5-6300HQ, GTX 960M) | 9 fps · p99 282 ms | **19 fps** · p99 106 ms | 2.1x |

![Louisville horde on four devices](../workshop/images/88-new-louisville-devices.png)

## Handheld and MacBook

The daily routes on the laptops, Build 42.21, uncapped, max zoom, release `5f72895` (2026-10-02) against stock (the
same day); means of two runs a side.

**Ayaneo Flip** (Ryzen AI 9 HX 370, Radeon 890M, 1920x1080, Linux, Mesa):

| Route | Stock | Optimized | Faster | GPU / game thread, stock → optimized |
|---|---|---|---|---|
| 120 km/h drive | 164 fps · p99 18.6 ms | **311 fps** · p99 7.3 ms | 1.9x | 56 → 88 / 97 → 84 % |
| 120 km/h drive, thunderstorm + heavy fog | 66 fps · p99 35.3 ms | **193 fps** · p99 10.9 ms | 2.9x | 53 → 85 / 98 → 94 % |
| Spinning walk, thunderstorm + heavy fog | 47 fps · p99 50.2 ms | **115 fps** · p99 19.8 ms | 2.5x | 51 → 66 / 97 → 97 % |
| Louisville horde | 31 fps · p99 63.0 ms | **53 fps** · p99 34.2 ms | 1.7x | 24 → 36 / 98 → 98 % |

On battery-sized power the Flip also does more per joule: the thunderstorm preset at a 120 fps cap goes 34 → 78 fps at
618 → 315 mJ a frame (runs `flip-flipw120-storm-stock` / `-eff`, 2026-10-06, defaults + efficient cores).

**MacBook Pro 14** (M1 Pro, 1920x1200, macOS 27; stock on Apple's OpenGL 2.1, optimized on OpenGL 4.1 core):

| Route | Stock | Optimized | Faster | GPU / game thread, stock → optimized |
|---|---|---|---|---|
| 120 km/h drive | 117 fps · p99 18.3 ms | **141 fps** · p99 17.7 ms | 1.2x | 71 → 76 / 69 → 65 % |
| 120 km/h drive, thunderstorm + heavy fog | 66 fps · p99 53.8 ms | **113 fps** · p99 20.5 ms | 1.7x | 69 → 78 / 90 → 67 % |
| Spinning walk, thunderstorm + heavy fog | 48 fps · p99 65.1 ms | **113 fps** · p99 22.6 ms | 2.4x | 67 → 77 / 93 → 84 % |
| Louisville horde | 29 fps · p99 97.2 ms | **40 fps** · p99 63.9 ms | 1.4x | 39 → 41 / 95 → 92 % |

The Mac drive is neither at a cap nor saturated (GPU 76 %, game thread 65 %): a finding, not a result. Since the
2026-10-16 release the Mac's worker threads run at the "user-initiated" quality of service (`workerQosMac`), which
measured 35 → 55 fps in a loaded scene. With OpenGL 4.1 every Enhancement runs on the Mac too: a walk round a pond with
lighting, shadows, ambient occlusion, reflections and god rays on runs at 115 fps against stock's 113 without them
(runs `mac-pondmac-*`, 2026-10-06).

## Windows

Windows 11 on the desktop (RTX 4090, 5120x2160, NVIDIA 617.42), uncapped, zoom 2.5, Steam launch, one run per cell
(2026-10-11, [`docs/results.md`](../results.md); these runs are not on the dashboard). Stock: the game's own launcher
(ZGC, 3 GB heap). Optimized: release `fd0b693` on G1 with a 4 GB heap, what its launcher setting writes.

| Route | Stock | Optimized | Faster | Optimized: GPU / game thread |
|---|---|---|---|---|
| Spinning walk south (25 s) | 71.6 fps · p99 38.3 ms | **190 fps** · p99 17.2 ms | 2.7x | 84 / 77 % |
| Louisville horde | 33.2 fps · p99 82.8 ms | **44.1 fps** · p99 72.3 ms | 1.3x | 40 / 88 % |
| 120 km/h drive east | 126.7 fps · p99 16.9 ms | **450 fps** · p99 4.8 ms | 3.6x | 90 / 62 % |
| 120 km/h drive south through Rosewood | 115.9 fps · p99 53.6 ms | **341 fps** · p99 13.4 ms | 2.9x | 87 / 71 % |

A rerun after a restart was within 1-5 % of every cell. With every Enhancement on, the spinning walk ran at 93 fps with
nothing saturated; the cause was the frame workers' 20 µs waits, which Windows rounds up to 1 ms. The fix (`shortWait`,
released since) gives 201.5 fps on the walk with the defaults and 113 fps (+21 %) with every Enhancement. The Windows GL
driver still costs more per call than Linux's (the defaults' Rosewood drive 2.86 ms a frame against 1.67 ms on Linux,
render thread 93-95 % busy), so the same build is slower on Windows on the same machine.

## Old 4-core laptop: a locked 60

Dell laptop (Core i5-6300HQ 4 cores / 4 threads, GTX 960M, 8 GB, 1920x1080 at 60 Hz, Linux), vsync on, 60 fps cap,
the home page's **Low-end hardware** preset (2026-10-07,
[`findings-low-end-mode-2026-10-07.md`](../findings-low-end-mode-2026-10-07.md)). "Repeats" = the share of display
refreshes that showed the previous frame again (0 % = a locked 60).

| Scene | Stock | Low-end hardware | + best look at 60 (5 Visuals) |
|---|---|---|---|
| Town walk, 150 s | 29.9 % repeats (42 fps, p99 72 ms) | **1.3 %** (59.2 fps, p99 26 ms) | 2.1 % |
| Night, torch | | 1.5-1.8 % | 1.8 % |
| Thunderstorm | | 3.9 % | 4.1 % |
| 60 km/h drive, full path | 36.8 % (38 fps, p99 73 ms) | **6.3-6.8 %** (56 fps, p99 32 ms) | 6.7-8.3 % |
| 120 km/h drive | | 5.6 % | 6.7 % |

Machine load in the locked walk: CPU 59 %, GPU 34 %, game thread 55 % of a core. Render distance 9 (`chunkGridWidth`) is
the one lever on four cores: the native lighting thread, every chunk bake and every arriving chunk scale with the grid's
area. Louisville (2,000 zombies) is out of reach for a 4-core i5 (9 → 19 fps above). With dynamic resolution held to
60 fps the 120 km/h highway drive goes 29 → 42 fps, p99 93 → 50 ms (runs `dell-delllow-*`, 2026-10-06).

## Boot and load

Desktop (Crucial T705 NVMe), launch to the world on screen with Continue pressed as soon as the menu answers, caches
warm, means of two runs per side without capture (runs `boot-stock-20261006-205652` / `boot-opt-20261006-205745`):

| Phase | Stock | Optimized |
|---|---|---|
| Launch to main menu | 7.4 s | 5.3 s |
| Continue to world ready | 10.3 s | 2.5 s |
| Launch to walking | 17.6 s | **7.8 s** |

The first boot after installing is slower: the animation-clip and texture-pack caches under `Zomboid/pzopt/` are written
then and read on every boot after. Chunk latency (ask to ready) on the 100 s drive went from 166 ms to 9 ms at the
median and from 1,024 ms to ~500 ms at p99 with the streamer changes alone (2026-09-19).

![Fast boot and load](../workshop/images/65-feature-fast-boot-load-split.avif)

## Against the other performance mods

The most-subscribed Build 42 performance mods, each installed the way its page says and run alone on the stock game, on
three routes: the 120 km/h highway drive, the same drive in a thunderstorm, and the Louisville horde (~2,000 zombies),
uncapped (2026-10-06/07, Build 42.21, the desktop: Ryzen 7 9800X3D, RTX 4090, 5120x2160, Linux; runs `mx-*`, rig in
`harness/mods-table-card.py`). All sides of a route ran back to back; stock is the mean of its runs at both ends of the
drive and the horde blocks. Stock and the other mods keep the game's own launcher (ZGC, 3 GB heap); PZ_Optimization runs
on G1, as its install sets it for players.

![Workshop performance mods vs PZ_Optimization](../workshop/images/70-vs-other-mods-table.png)

| Mod | What it is | Drive fps / p99 ms | Storm | Louisville horde |
|---|---|---|---|---|
| Stock game | | 115 / 17.0 | 63 / 61.6 | 24 / 110 |
| [Project Zomboid Optimiser](https://steamcommunity.com/sharedfiles/filedetails/?id=3787481250) + its [PZO-Launcher](https://github.com/prop11/PZO-Launcher) V0.9.9.4 | Lua toggles; agent jar, native lib, launcher JSON (G1, 8 GB) | 114 / 16.8 | 68 / 64.8 | 29 / 84.8 |
| [Tempo](https://steamcommunity.com/sharedfiles/filedetails/?id=3736629791) | Lua sampler and menu memo | 116 / 16.6 | 63 / 60.5 | 24 / 112 |
| [Multi-Cpu Enhance](https://steamcommunity.com/sharedfiles/filedetails/?id=3459875383) | launcher JSON: ParallelGC, 8 GB heap | 118 / 16.6, **291 ms stall** | 67 / 64.4, **332 ms stall** | 29 / 97.9, **390 ms stall** |
| [Every Texture Optimized](https://steamcommunity.com/sharedfiles/filedetails/?id=3119788162) | re-encoded textures | 117 / 16.6 | 63 / 63.1 | 24 / 104 |
| [Lugli – Optimizations](https://steamcommunity.com/sharedfiles/filedetails/?id=3790863696) | ZombieBuddy patches | 117 / 16.9 | 62 / 62.6 | 23 / 109 |
| [Zed's Better FPS](https://steamcommunity.com/sharedfiles/filedetails/?id=3622986450) | ZombieBuddy patches | does not load (below) | | |
| [Zed's Better FPS KA](https://steamcommunity.com/sharedfiles/filedetails/?id=3793137588) | ZombieBuddy patches, partly ported from this repository (below) | 113 / 17.1 | 73 / 18.5 | 29 / 89.0 |
| [Let Me Drive!](https://steamcommunity.com/sharedfiles/filedetails/?id=3805307651) | Lua event gate, GC-call block | 116 / 16.7 | 60 / 66.9 | 23 / 108 |
| **PZ_Optimization** | class overrides | **523 / 5.2** | **447 / 6.3** | **86 / 43.9** |

On the drive every mod is within 3 % of stock. In the horde, PZ Optimiser and Multi-Cpu Enhance gain ~20 % by changing
the launcher's garbage collector and heap: the horde's live data nearly fills the game's 3 GB (stock with PZO's G1 / 8 GB
switch alone matches PZO on the spin route too: 125 vs 123 fps, runs `mx-*-stockg1`); Multi-Cpu's ParallelGC also puts a
300–390 ms stop-the-world pause inside every route. Zed's Better FPS KA is the only one with a gain of its own: +15 % in
the storm with a far shorter tail (p99 18.5 vs 61.6 ms) and +19 % in the horde. Run-to-run noise: two stock runs of the
drive differ by 2 %, of the horde by under 1 %.

Zed's Better FPS does not load on Build 42.21 today: ZombieBuddy (2.3.4 on the Workshop, the latest release) skips it,
because it now asks for ZombieBuddy 2.4.0–2.99.99, which is not released (ZombieBuddy's master is 3.0.0-beta1). Pointing
its mod.info at its other jar loads that one, which then stops the game with a `NoSuchMethodError` (it is built for
ZombieBuddy 3).

Zed's Better FPS KA ports code from this repository: five of its classes are our `pzopt` classes decompiled and renamed
(`ScriptText`, `ZombieNoise`, `PngFilters`, `AnimClipCache`, `RainTiles`; measured as identifier-blind token similarity
against our sources, 0.43–0.81, where unrelated classes of similar size score at most 0.39). Its Workshop page credits
PZ Optimization for some of its improvements; its code does not.

The Workshop page shows the same table and a stock | Zed's KA | ours clip of the drive in a thunderstorm with heavy fog
(57.7 / 66.5 / 386.1 fps, one back-to-back block, stock at both ends).

## Feature passes

The measurements behind single features, each dated: the build has moved on since, so the absolute numbers are those
of their day (the tables above are current).

### The showcase video (2026-09-21)

**[Project Zomboid Build 42: stock vs all optimizations](https://www.youtube.com/watch?v=GCjCYbTE9AQ)**
(YouTube, 2 min, with sound). Same PC, same save, same routes: stock with every optimization
off and the frame cap removed on the left, the same build with the overrides on and uncapped
on the right. Six scenes in order: boot and load, the 120 km/h highway drive, the Options >
Optimizations tab (now PZ OPTIMIZATION > Optimizations in the main menu), the spinning Rosewood route, heavy fog, a thunderstorm. The in-game
overlay shows the live frame time; the big number is the 1 s average from its log, the
results card at the end the whole-route numbers below.

![The optimized build at the end of the showcase: 500 fps uncapped with the performance overlay, animated](../workshop/images/00-showcase-thumbnail.gif)

*The last beat of the showcase as the Workshop preview (12 fps GIF): the optimized build at ~500 fps uncapped, the in-game overlay along the bottom.*

| Scene | Stock | Optimized | Change |
|---|---|---|---|
| Boot, launch to main menu | 7.3 s | 4.8 s | -34 % |
| Load, Continue to world ready | 7.5 s | 3.4 s | -55 % |
| 120 km/h highway drive, fps / p99 | 156 fps / 16.5 ms | 512 fps / 5.8 ms | +228 % / -65 % |
| Rosewood, spinning view, fps / p99 | 140 fps / 27.9 ms | 447 fps / 8.4 ms | +220 % / -70 % |
| 120 km/h drive, heavy fog, fps / p99 | 111 fps / 18.9 ms | 256 fps / 8.4 ms | +131 % / -56 % |
| 120 km/h drive, thunderstorm, fps / p99 | 70 fps / 67 ms | 392 fps / 9.9 ms | +460 % / -85 % |

Boot and load are the mean of four launches each; fps = presented frames per second over
the whole route, p99 = the frame time 99 % of frames stay under. Ryzen 7 9800X3D, RTX 4090,
32 GB DDR5-8000, CachyOS, native Linux build, NVIDIA OpenGL, 5120x2160 at 240 Hz, max zoom,
no frame cap on either side. Recorded 2026-09-21 with that day's release.

### Weather: fog & lightning at 120 km/h (2026-09-21)

The 1,200-tile drive in a thunderstorm with a lightning strike every 6 s, and the same drive in
a thunderstorm inside heavy fog (`--preset storm-fog`, six strikes). Heavy fog was the slowest
weather by far: stock draws it as one screen-wide rectangle per tile row and level, so every
pixel is shaded up to twelve times, one draw call per row; the fog pass (`fogPass`, on by
default) draws every row in one call into a quarter-size fog buffer and blends it over the
scene once. The storm side has the puddle cache in GPU buffers, the early-depth puddle
shaders, the rain tiles, the lightning re-bake spread and the tree copies
([Weather](how-it-works.md#3-weather-puddles-rain-lightning-fog)). A thunderstorm with lightning now runs at
85 % of the clear-weather frame rate on this machine (392 vs 454–465 fps); before this pass it
was 232 vs 420. Runs `sbs2-storm120-stock-1` / `sbs2-storm120-opt-1` and `sbs-stormfog-stock` /
`sbs-stormfog-opt` (2026-09-21); videos (AV1 HDR)
`docs/media/drive-120kmh-storm-stock-vs-optimized-2026-09-21.mp4` and
`drive-120kmh-storm-fog-stock-vs-optimized-1080.mp4`; findings in
[`docs/archive/2026-09-24/findings-storm-parity-2026-09-21.md`](../archive/2026-09-24/findings-storm-parity-2026-09-21.md) and
[`docs/archive/2026-09-24/findings-fog-2026-09-21.md`](../archive/2026-09-24/findings-fog-2026-09-21.md).

![120 km/h through a thunderstorm, stock vs optimized](../media/drive-120kmh-storm-stock-vs-optimized-2026-09-21.jpg)

| Metric | Lightning: stock | Lightning: optimized | Fog + lightning: stock | Fog + lightning: optimized |
|---|---|---|---|---|
| fps, mean | 70 | 392 | 60 | 191 |
| Frame time, mean / p50 | 14.2 / 13.3 ms | 2.6 / 2.0 ms | 16.6 / 15.4 ms | 5.2 / 4.1 ms |
| Frame time, p90 / p99 / p99.9 | 16.6 / 67 / 84 ms | 4.6 / 9.9 / 14.4 ms | 19.9 / 71.7 / 92.9 ms | 9.1 / 16.5 / 22.3 ms |
| Frames over 33 ms | 46 | 0 | 49 | 0 |
| GPU / game thread / render thread busy | 85 / 85 / 83 % | 98 / 68 / 45 % | | |

Stock is capped at 300 by the in-game limiter, moot at 60–70 fps; optimized is uncapped. Heavy
fog alone on the same route in clear weather: stock fog 220 fps, the fog pass 333–392 fps, no
fog 447 fps (the fog's own GPU time 1.52 → 0.35 ms a frame).

### Rosewood spin, uncapped (2026-09-20)

A 25 s teleport route south through Rosewood at max zoom with the player's facing spinning at
90°/s, so the view cone, lighting cone and wall cutaways change every frame while 55 chunks a
second stream in; the heaviest bench route. Three runs: stock, the build before the
uncapped pass of 2026-09-20 and the build with everything (runs `u400show-stock-2`,
`u400show-prev-1`, `u400show-all-1`).

![Stock vs optimized vs all optimizations, uncapped, on the Rosewood route](../media/rosewood-spin-uncapped-stock-vs-optimized-vs-all-hdr.jpg)

| Metric | Stock | Optimized (before the pass) | All optimizations |
|---|---|---|---|
| fps, mean | 114 | 391 | 492 |
| Frame time, mean / p50 | 8.8 / 6.2 ms | 2.6 / 1.9 ms | 2.0 / 1.6 ms |
| Frame time, p90 / p99 / p99.9 | 17.8 / 31.4 / 53.1 ms | 4.4 / 10.2 / 20.0 ms | 3.3 / 7.7 / 15.8 ms |
| Frames below 240 fps | 71 % | 9 % | 4 % |

From about 450 fps the GPU is saturated (the chunk-texture composite and the bakes) with
the game thread at ~89 %, so both sides of the machine are used to the full at 5120x2160.
The frames still above 2.5 ms are chunk streaming on the game thread (loot roll, bakes and
cutaway data of freshly loaded chunks); what locking 400 needs is in the [Roadmap](limitations.md#roadmap).
Two things learned on the way: the stock Display option **UI rendering: offscreen** is worth
about 40 % uncapped on this machine, and a `mangohud %command%` in the Steam launch options
costs about 30 % uncapped.

### Input latency: NVIDIA Reflex-style low latency (2026-09-24)

![Input -> screen, stock vs this build](../media/input-latency-reflex.png)

Measured end to end: a virtual keyboard, mouse and Xbox pad (uinput) press, the game's own stage stamps, and the flip
that put the frame on screen (X Present). Stock reads the keyboard one frame late (`GameKeyboard` used the previous
poll), and every frame used input polled right after the previous frame was shown, however long the frame limiter then
idled. Two fixes are on by default: **fresh keyboard** (`keyboardFresh`) and **input latch** (`inputLatch`: the game asks
the render thread for a fresh poll the moment its frame starts). Three are options in PZ OPTIMIZATION > Optimizations > Smoothness and pacing > Input
latency:

- **Low-latency mode** (`reflexSleep` + `reflexCapFps=-1`): what NVIDIA Reflex does. Each frame measures how long it
  queued on the way to the screen (the hand-off to the render thread, a vsync swap that blocks, the driver's queue from
  GL timestamps) and the next one starts that much later; with vsync the frame rate is capped just below the refresh
  (refresh - refresh²/3600, 157 fps at 165 Hz) so the game can never refill the queue. Vsync on, uncapped:
  25-28 ms -> 5-10 ms input -> screen.
- **Boost** (`reflexBoost`, NVIDIA): at a frame cap the GPU idles and clocks down, so the frame it draws takes longer
  (3.9 ms per frame at 60 fps vs 1.3 ms at 240 on an RTX 4090). This holds the driver's "prefer maximum performance"
  through NVML while a world is loaded, as Reflex's On + Boost does; the driver drops it when the game exits. ~28 W more
  at a 60 fps cap.
- **Cursor latch**, **aim hold** and the other measured options (`cursorLatch`, `aimHoldMs`, `gpuMaxFrames`,
  `vsyncAdaptive`, `vblankLock`) are described in the PZ OPTIMIZATION screen.

NVIDIA Reflex itself is an SDK for Direct3D and Vulkan; there is none for OpenGL, which the game uses, so these are
Reflex's methods rebuilt with OpenGL and NVML. Every run and the techniques that did not help (the driver's
`__GL_MaxFramesAllowed`, adaptive vsync, a vblank-locked start under XWayland):
[docs/archive/2026-09-24/findings-input-latency-2026-09-24.md](../archive/2026-09-24/findings-input-latency-2026-09-24.md).

### Variable refresh: G-SYNC, FreeSync, ProMotion (2026-09-24)

![Variable refresh, without vs with this build](../media/vrr.png)

A variable refresh display shows each frame the moment it is ready, so it only helps when the compositor turns it on and
the frames arrive evenly. Measured with the flip times of every frame (X Present), the kernel's `VRR_ENABLED` state and
the game's own per-frame stamps (`harness/pacing.py`):

- **Borderless gets VRR** (`borderlessFullscreen`, Linux, on by default): KDE, GNOME and gamescope enable VRR only for a
  window in the fullscreen state, and the stock borderless window is a screen-sized plain window, so it never got VRR
  (0 % of the run). It is now a fullscreen window at the desktop's own mode: no mode switch, it stays up when focus
  moves away, and the options still say borderless. VRR on for the whole run under XWayland and native Wayland.
- **Cap inside the range** (`vrr=auto`, `vrrCap`): while the kernel reports VRR on, an uncapped or too-high cap becomes
  refresh - refresh²/3600 (157 fps at 165 Hz), so frames never fall back to vsync at the ceiling: 21 -> 13 ms from the
  game's step to the screen.
- **Even delivery** (`presentPacing=auto`): the game steps at an even rate, but each frame takes a different time to
  update and draw, and with VRR that difference is on screen. Each swap is held to a high percentile of the recent
  step -> GPU-done time (GL timestamp queries, never blocking): on-screen judder 3.10 -> 0.95 ms at a 100 fps cap.
- **macOS ProMotion** (`macPresent`, Apple silicon, off by default): OpenGL frames are shown on the 120 Hz grid, so a
  90 fps cap alternates 8 and 17 ms. Presenting through Metal (`presentDrawable:afterMinimumDuration:` from a native
  fullscreen Space, the game's frame upscaled into a native-size drawable) shows any multiple of 4.17 ms: 94 % of
  steady frames exactly on their slot at 60 fps, 84 % at 80 (M1 Pro). It adds ~10-15 ms of latency, hence opt-in.

Windows (borderless already covers the screen there) and AMD FreeSync are not measured yet; on Windows set Variable
refresh rate = on in PZ OPTIMIZATION > Optimizations > Smoothness and pacing. Every run: [docs/archive/2026-09-24/findings-vrr-2026-09-24.md](../archive/2026-09-24/findings-vrr-2026-09-24.md).

### Clear audio: no clipping under gunfire (2026-09-24)

![Clear audio, stock vs this build](../media/clear-audio.png)

Measured on the game's own audio stream (PipeWire, recorded alone) in the loudest scene we could build: the downtown
Louisville horde (~2,000 zombies) in a thunderstorm with a house alarm, a car alarm, the helicopter and a pistol fired
twice a second; `harness/audio-judge.py` looks for cutoffs, gaps, dropouts, clicks and clipping and Jev gives the verdict.

- **No more crackle on stereo** (`audioLimiter`, `audioLimiterStereoFold`, on by default): the game mixes 5.1 surround at
  32 kHz on every device (fixed inside FMOD's init in the game's native library). On stereo speakers or headphones the
  operating system adds the six channels into two after the game, and under gunfire that sum clipped: 6,161 samples at
  full scale in 25 s with the pistol, 19,113 with an assault rifle at 8 shots a second, although FMOD's own output never
  passed 0.75. The game now folds to stereo itself, ahead of FMOD's limiter at the head of the master mix (ceiling
  -2 dBFS, 50 ms release): 0 clipped samples, true peak -0.3 dBTP. A 5.1 / 7.1 device keeps its surround and gets the
  limiter per channel.
- **Sound upkeep costs a fifth** (`emitterIdleSkip`, `soundTickHz`, `worldSoundCleanupFast`, `hearingHoist`): stock
  checks every zombie's three sound emitters every frame, refreshes the listener's ambience parameters every frame
  (FMOD applies them every 20 ms), sweeps every chunk's expired noises one `remove` at a time and re-reads a zombie's
  hearing per candidate sound. Silent zombies now skip their emitters, the ambience runs at 60 Hz, the sweep only runs
  when a noise expired, and hearing is read once: sound code on the game thread 1.35 ms -> 0.24 ms a frame. FMOD's own
  threads stay at 0.1 of a core.

All of them are in PZ OPTIMIZATION > Optimizations > System, sound and mods > Sound. Every run: [docs/findings-sound-2026-09-24.md](../findings-sound-2026-09-24.md).

### Ambient occlusion at almost no cost (2026-09-24)

![Ambient occlusion off and on, Rosewood house](../workshop/images/27-ambient-occlusion.gif)

Soft shading where surfaces meet: floors darken along the base of walls, in room corners, under and around furniture,
stairs, fences, bushes and trees. The game's depth buffer is a linear, world-space height field (orthographic 2:1
projection), so every depth texel is an exact 3D position and the AO is computed in true 3D without a normal buffer:
ground-truth-style horizon AO with visibility bitmasks (32 sectors per slice, a thickness per sample, so thin posts
occlude as little as they cover and no halos form at depth jumps). It runs when a chunk's picture is baked, reading the
eight neighbour chunk textures for occluders across the edge, and is multiplied into that picture; lighting-only
re-bakes reuse the kept AO. A frame that bakes nothing pays nothing, and so does a moving camera.

- **Cost** (desktop, RTX 4090, 5120x2160): 0 standing or panning, 4.1 us a frame walking through Rosewood (0.27 % at
  650 fps), 1.3 % of the GPU's time in the 120 km/h drive and the storm route, which bake new chunk pictures every frame.
- **Frame-time tail**: under a cap the computes follow the last frame's slack, so the capped 240 fps drive keeps its
  tail: 220 fps, p99 14.2-14.8 ms with AO on against 218 fps, 14.2-14.7 ms off. The trade-off: when the game thread
  misses the cap (a fast drive at max zoom), new chunks get their AO a little late.

Off by default because it changes the picture: PZ OPTIMIZATION > Enhancements > Lighting and shadows > Ambient occlusion (strength on floors, walls, objects and vegetation, reach and detail
there too), applies on the next launch. Windows and Linux; macOS too since the OpenGL 4.1 core context (2026-10-01). Every
run: [docs/findings-ambient-occlusion-2026-09-24.md](../findings-ambient-occlusion-2026-09-24.md).

### Smooth Operator: driving through town (2026-09-24/25)

![Stock vs this release on the Rosewood 120 km/h drive, with both runs' frame times](../workshop/images/30-smooth-operator-driving.gif)

Driving through a town at 120 km/h, fully zoomed out, the game bakes new chunk pictures every few frames: a row of
chunks arriving at the edge of the screen, buildings entering and leaving the cut-away set, neighbour seams, lighting.
Each kind of bake had its own budget, so the kinds that arrive together stacked into bursts of 10-120 bakes in one frame.
One per-frame bake budget now covers all of them (`bakeScheduler`): urgent levels first (a changed object, a cut-away,
a never-drawn chunk), the rest by how long they have waited and how near they are, the budget following the last frame's
cost (2-8 bakes). A bake builds 3 mipmap levels instead of 11 (`bakeMipLevels`, the widest zoom samples no further),
chunk textures are made ahead of need (`renderChunkTopUp`), and the persistent sprite buffers are fenced once per frame
(`persistentVboFrameSync`). Present pacing (`presentPacing=auto`, now on at a fixed refresh too) holds each frame until
a steady time after the game moment it shows, so frames leave the game evenly.

| Rosewood, 120 km/h, 240 fps cap | stock | this release |
|---|---|---|
| Desktop (RTX 4090, 5120x2160, HDR desktop): frame rate | 155 fps | 234 fps |
| Desktop: 1 % low | 29 fps | 120 fps |
| Desktop: p99 / p99.9 frame time | 35.1 / 50.5 ms | 8.4 / 15.4 ms |
| Desktop: frames off their 240 Hz slot | 42.1 % | 13.0 % |
| Desktop: frame-to-frame jitter | 2.4 ms | 0.5 ms |
| Desktop: game thread busy (share of a core) | 98 % | 45 % |
| Laptop (the flip, balanced profile): frame rate | 92 fps | 116 fps |
| Laptop: 1 % low / p99 | 26 fps / 38.5 ms | 66 fps / 15.3 ms |

The cost: present pacing adds ~1.7 ms from the game step to the screen on average (~2.6 ms on a typical frame, less
than one 240 Hz frame; frames already late go out at once). PZ OPTIMIZATION > Optimizations > Smoothness and pacing > Even frame
delivery, `off` for stock timing. A second pass removed the chunk-load stalls (a trashed house's room scan, new chunks lit one per frame, occlusion
counts on worker threads). The route is still not locked at 240. Every run, including the techniques measured and not
adopted: [docs/findings-town-drive-2026-09-24.md](../findings-town-drive-2026-09-24.md).
