# Settings

[Back to the README](../../README.md) · [The PZ OPTIMIZATION screen](#the-pz-optimization-screen) ·
[Frame cap](#frame-cap-uncapped-300-to-500-fps-menu-framerate) · [Upscaling and DLSS](#upscaling-fsr-10-and-dlss) ·
[Performance overlay](#performance-overlay-f9--l3--r3) · [`pzopt.properties`](#pzoptproperties-and-the-key-table)

Nothing needs configuring: the defaults are what the [benchmarks](benchmarks.md) were measured with.
Three places change them, in order of precedence: `pzopt.properties` in the game folder
(or `-Dpzopt.<key>=` JVM properties) wins over the PZ OPTIMIZATION screen, which wins over the built-in
default. A key set to `false` or `0` restores stock behaviour for that item alone.

## The PZ OPTIMIZATION screen

Every setting lives in the **PZ OPTIMIZATION** item of the main menu (right below Options; the same item is in the
pause menu during a game). Until 2026-10-18 the settings were a tab of the game's Options screen; that tab is gone.
The screen has a sidebar on the left (a search box, then Optimizations, Enhancements, Profiler, Benchmark, Update and
the mod compatibility check) and the selected page on the right: a list, and the selected row's details beside it.

- **Home page**: the presets, Fix a problem, which settings the pages show, the before / after clips, Export / Import
  settings and the uninstall. **Stock game** turns every optimization off: after the next launch the game runs its
  original code everywhere, as if nothing were installed. **Recommended** puts every optimization back to the build's
  defaults. **Low-end hardware (4 cores or less)**, **Low-end hardware + best look at 60** and **Low-end hardware + FSR
  1.0 upscaling** are the sets measured on a 4-core Core i5-6300HQ with a GTX 960M
  ([`findings-low-end-mode-2026-10-07.md`](../findings-low-end-mode-2026-10-07.md)): render distance, no chunk worker
  pool, trees baked only while walking, lighting updates 10/s, the UI redrawn 30/s. Each preset asks before it changes
  anything.
- **Optimizations** (on by default): smoothness and pacing, driving, world drawing, lighting / cutaways / weather,
  zombies and hordes, boot and loading, menus / inventory / map, system / sound / mods. Applies on the next launch: the
  screen offers **Restart now** or **Later** (a reminder with its own Restart now button stays at the bottom).
- **Enhancements** (off by default): image quality (upscaling, dynamic resolution, sprite filtering, HDR output),
  lighting and shadows (per-pixel lighting, voxel lighting, sun / moon / cloud shadows, ambient occlusion, god rays),
  reflections and glass (water and puddles, mirrors and windows, car glass, wet blood), atmosphere and detail (darkness
  and grading, foliage sway, relief, zombie outlines). Most apply at once; HDR output, per-pixel and voxel lighting,
  reflections, car glass, mirrors and relief on the next launch.
- **Profiler**: the [performance overlay](#performance-overlay-f9--l3--r3) (its first row shows or hides it, like F9),
  its fps colours, the console log filter. Applies at once.
- **Fix a problem**: what players report (stutter while driving, low fps in a big horde, a long boot, slow storms, laggy
  input, tearing, slow menus), why it happens, and the settings that help, wherever they live.

Each group has a master switch at the top of its page and every category its own: off, its settings read as stock
(Optimizations: next launch; Enhancements and Profiler: at once) while your choices stay saved. **Simple / Advanced /
Everything** (home page) sets how many settings a page shows. The search box finds any setting by name, key, what it
does, a resource ("gpu", "load time") or a Java class that reads it (Ctrl+F; L3 on a controller opens the on-screen
keyboard); pick a result to open its page. Each change is saved at once to `Zomboid/pzopt/options.ini`; **DEFAULT** puts
a setting back to the build's default. A key pinned in `pzopt.properties` says so and cannot be changed there. **Reset ...
to defaults** at the end of every group and category page does that for all of its settings. **Export settings** copies
every non-default setting as `key=value` text to the clipboard (and `Zomboid/pzopt/settings-export.ini`); **Import
settings...** sets every setting from such a text (the ones it does not list go back to the default).

A setting's details say what it does, when it applies, its value now and at the next launch, its default, who pins it,
the release it came with and the Java classes that read it, and draw its **effect on your hardware**: one bar per
resource (**CPU game thread, CPU render thread, other cores, GPU, VRAM, RAM, disk / caches, boot and load time, chunk
arrival**) on an axis from *lowest* through *mid* (the stock game) to *max*: green bars grow left for less load, amber
right for more, blue for idle cores put to work. With the **before / after clips** on (SHOW CLIPS on any setting, or the
home page row; off by default, remembered as `previewClips`) the details also play the **stock game and the optimized
build side by side** on the same route, each run's live frame rate burned in. The details keep their layout from one
row to the next, so nothing jumps while you move through a list.

Everything works with a mouse, the keyboard (Escape closes) and a controller (D-pad or left stick to move, A to open
or change, B back, X and Y the row's actions, LB / RB the sections, LT / RT a page, L3 search, R3 the button help; the
hint bar at the bottom shows the buttons while a controller is in use).

To compare against stock on your own machine, pick the **Stock game** preset, or put `enabled=false` in
`pzopt.properties`; no reinstall is needed either way.

## Frame cap: Uncapped, 300 to 500 fps, menu framerate

Display & Performance keeps the stock layout and gains a real **Uncapped** entry, 300, 330,
400, 430 and 500 fps caps, and a separate **Menu framerate** combo (`pzopt.FrameCap`,
setting stored in `Zomboid/pzopt/framecap.ini`, so the choice survives the game's own
rewrite of `options.ini`). The stock Display option **UI rendering: offscreen** is worth
about 40 % uncapped on the desktop.

![Options > Display & Performance with the extended Lock Framerate combo and the Menu framerate combo](../media/options-display-tab.jpg)

## Upscaling: FSR 1.0 and DLSS

PZ OPTIMIZATION > Enhancements > Image quality > **Upscaling** renders the world at a fraction of the screen size and
scales it back up before the UI, the world text, the cursor and the game's own screen shader, which
stay at full resolution (keys `upscaler`, `upscalerQuality`, `upscalerScalePct`, `fsrSharpnessPct`,
`dlssPreset`, `upscalerObjectMv`; off by default, applied on the next launch).

**How it works.** The game draws the whole world (chunk textures, characters, vehicles, weather,
lighting) into an offscreen frame and then draws the interface over it. With an upscaler on, that
world frame is rendered into a smaller viewport of the same buffer, a fraction of the screen per
axis (`quality` 67 %, `balanced` 58 %, `performance` 50 % = a quarter of the pixels, `ultra` 33 %),
and resolved back to the screen size right before the game's own screen shader runs, so the UI,
the world text, the cursor, the map and the screen effects keep every pixel. Nothing changes on the
game thread: culling, chunk loading, mouse picking and the chunk-texture bakes are the same as
before. `fsr1` rebuilds the edges (EASU) and re-sharpens (RCAS) in ~0.1 ms at 4K on any GPU; `dlss`
draws the world with a sub-pixel jitter and feeds the camera's and each character's and vehicle's
motion to NVIDIA's network, which accumulates detail across frames (1-px power lines and car
lettering come back at 50 %); `bicubic` is the plain stretch the stock screen shader already does.

**Turning it on.** PZ OPTIMIZATION > Enhancements > Image quality > Upscaling: set **Upscaler** to `fsr1` (or `dlss` on an
RTX card once the shim is under `natives/`, see "Enabling DLSS" below), pick **Upscaler quality** (`quality` keeps most of the
detail, `performance` halves both axes), optionally an explicit percentage, the FSR sharpening
and the DLSS preset; each applies as soon as you change it.
`console.txt` confirms it with one line, `[pzopt] upscaler: fsr1 at 50 % (performance)`; a mode
that cannot run says why and continues as `fsr1` (`upscaler: dlss unavailable (...)`). The same
keys work as `-Dpzopt.upscaler=fsr1` or in `pzopt.properties`.

**When it helps.** Whenever the GPU is the limit: the overlay's (F9 / L3 + R3) verdict line reads `GPU bound`
(GPU busy ≥ 90 %) and the frame rate is under the cap. That is the usual state at 4K and 5K, on
laptops and integrated GPUs, in heavy fog and thunderstorms, and in downtown Louisville, where the
world pass is most of the GPU's frame; the gain is roughly the pixel ratio of that pass (this 4090
at 5120x2160: 509 → 619 fps at 50 %, and the spinning Rosewood walk 592 → 802). It does nothing
when the verdict says `game thread bound` (a horde, chunk streaming, the zombie simulation) or
`at the cap`, and DLSS's own cost is per output pixel (~2.5 ms a frame at 5120x2160 on a 4090),
so at 4K and above prefer `fsr1` or `dlssPreset=f`; DLSS pays off at 1440p and below, or whenever
its reconstructed detail matters more than the frame rate.

**Enabling DLSS (Linux, RTX).** The release zip and the Workshop item carry no native libraries,
so `upscaler=dlss` on a fresh install logs `upscaler: dlss unavailable (dlss: .../natives/libpzopt_ngx64.so
not found); using fsr1 at 50 %` and runs as FSR 1.0. DLSS needs two files under the game's `natives/`
folder (next to `libLighting64.so`): the shim `libpzopt_ngx64.so`, built from `src/native/pzopt_ngx.cpp`
against NVIDIA's DLSS SDK, and NVIDIA's DLSS library `libnvidia-ngx-dlss.so.<version>` from that SDK.

The easy way: **PZ OPTIMIZATION > Enhancements > Image quality > Upscaling > NVIDIA DLSS files**. The button checks what your machine has, downloads
the shim from this project's `dlss-linux-*` release and NVIDIA's DLSS library straight from NVIDIA's DLSS repository
(never re-hosted here), checks both against their pinned sha256 and puts them in `natives/`; then pick Upscaler: dlss
and restart the game. It needs an RTX card: on Linux x86-64 the proprietary NVIDIA driver and the Vulkan loader, on
Windows x86-64 the NVIDIA driver (the Windows shim is built with MSVC, `docs/dlss-windows-build.md`; until a
`dlss-windows-*` release exists the button reports that no release carries it); on macOS it says there is no DLSS. The files survive the
in-game updates; removing them by hand (`natives/libpzopt_ngx64.so`, `natives/libnvidia-ngx-dlss.so.*`,
`natives/pzopt-dlss-installed.txt`) is safe.

Or build both yourself, on top of any install method (Workshop, installer, zip); it takes a minute:

1. Requirements: an RTX card on the proprietary NVIDIA driver (it ships `libnvidia-ngx.so.1`: Arch
   `nvidia-utils`, elsewhere the `nvidia-driver-<version>` packages; check with
   `ls /usr/lib*/libnvidia-ngx.so.1 /usr/lib/x86_64-linux-gnu/libnvidia-ngx.so.1`), the game running on
   that driver's OpenGL (the default; not Mesa / Zink), `g++`, `git`, the Vulkan loader and headers.
   ```sh
   sudo pacman -S gcc git vulkan-headers vulkan-icd-loader     # Arch / CachyOS
   sudo apt install g++ git libvulkan-dev                       # Debian / Ubuntu
   sudo dnf install gcc-c++ git vulkan-headers vulkan-loader-devel   # Fedora
   ```
2. Get NVIDIA's DLSS SDK (public on GitHub, under NVIDIA's SDK license; ~600 MB) and the repository:
   ```sh
   git clone --depth 1 https://github.com/NVIDIA/DLSS ~/.local/share/nvidia-dlss-sdk
   git clone --depth 1 https://github.com/xD3I/PZ_Optimization.git
   ```
3. Build the shim and copy it, with the DLSS library, into the game's `natives/` folder
   (`PZ` below is the folder that holds `projectzomboid.jar`; on this machine
   `/games/steamapps/common/ProjectZomboid/projectzomboid`, usually
   `~/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid`):
   ```sh
   SDK=~/.local/share/nvidia-dlss-sdk
   PZ=~/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid
   cd PZ_Optimization
   g++ -O2 -shared -fPIC -std=c++17 -fvisibility=hidden -I$SDK/include -o "$PZ/natives/libpzopt_ngx64.so" \
       src/native/pzopt_ngx.cpp $SDK/lib/Linux_x86_64/libnvsdk_ngx.a -ldl -lpthread
   cp $SDK/lib/Linux_x86_64/rel/libnvidia-ngx-dlss.so.* "$PZ/natives/"
   ```
   (Deprecation warnings from the SDK headers are normal.) With a source build, `scripts/build.sh` does
   the same when the SDK checkout is at that path — `PZOPT_DLSS_SDK=<dir>` names another one,
   `PZOPT_DLSS=0` skips it — and `scripts/pzopt.sh install` copies both files in with the classes.
4. PZ OPTIMIZATION > Enhancements > Image quality > Upscaling: **Upscaler** = `dlss`, a quality (`performance` = 50 %, or
   `native` for DLAA anti-aliasing at full size), optionally a **DLSS model preset** ("Upscaler, NVIDIA DLSS: model preset") (`f` is the
   cheap one at 4K), restart the game. `console.txt` then has `[pzopt] upscaler: dlss at 50 % (performance)`
   followed a few frames later by `[pzopt] dlss: ready, 2560x1080 -> 5120x2160 (performance, preset default,
   ...)`. If it says `upscaler: dlss unavailable (...)` instead, the reason is in the parentheses: the
   shim or the library not under `natives/`, no NVIDIA Vulkan device, a driver without
   `GL_EXT_memory_object_fd` / `GL_EXT_semaphore_fd` (Mesa / Zink, or an old driver), or NGX refusing the
   card; the game keeps running with FSR 1.0 at the same scale.

The two hand-copied files are not in `pzopt-installed.txt`, so an uninstall leaves them behind: delete
`natives/libpzopt_ngx64.so` and `natives/libnvidia-ngx-dlss.so.*` by hand (the game does not touch them
without the overrides). NGX writes its own cache under `Zomboid/pzopt/ngx/`. Split screen falls back to
FSR 1.0 (one DLSS feature per screen). Windows: the same shim needs an MSVC build (`src/native/README.md`);
until someone builds and tests that DLL, `upscaler=dlss` on Windows runs as FSR 1.0.

| `upscaler` | What runs | Where |
|---|---|---|
| `fsr1` | AMD FidelityFX Super Resolution 1.0 (EASU + RCAS, MIT) as GLSL passes on the low-res frame | every GPU, Linux / Windows / macOS |
| `dlss` | NVIDIA DLSS Super Resolution: a Vulkan device runs NGX on the GPU the game's GL context uses, sharing the colour / depth / motion-vector images and the output through `GL_EXT_memory_object` + `GL_EXT_semaphore`; the world is drawn with a sub-pixel jitter (a float viewport offset), the camera's motion and each character's and vehicle's own motion (stencil ids) go in as motion vectors | RTX cards on the NVIDIA driver; Linux now, Windows once the shim DLL is built with MSVC (`src/native/README.md`). The release zip and the Workshop item ship no natives (2026-09-22: 58 MB of NVIDIA's library, Windows cannot use the .so, the Workshop bans the extension): build the shim yourself, see "Enabling DLSS" above; anything that cannot run continues as `fsr1` |
| `bicubic` | the stock screen shader's bicubic filter stretches the low-res frame | every GPU (the plain baseline) |
| `xess` | Intel XeSS: not written yet (Windows-only SDK); runs as `fsr1` | – |

`upscalerQuality`: quality 67 % per axis, balanced 58 %, performance 50 %, ultra 33 %, native 100 %
(dlss: DLAA). The 120 km/h drive at 5120x2160, uncapped, one build (runs `ups-*-p-3/4`, `ups-dlss-f-1`,
2026-09-22):

| Mode | fps, mean | Frame time p50 / p99 | Notes |
|---|---|---|---|
| off | 509 | 1.5 / 7.7 ms | the GPU is the wall (96-98 % busy) |
| bicubic 50 % | 676 | 1.1 / 6.2 ms | soft |
| fsr1 50 % | 619 | 1.2 / 6.4 ms | EASU + RCAS ≈ 0.14 ms a frame at 4K |
| dlss 50 %, transformer (default) | 232 | 3.7 / 12.2 ms | the DLSS 4 model costs ~2.5 ms a frame at 5120x2160 output on a 4090; the best image (1-px power lines and car lettering come back) |
| dlss 50 %, `dlssPreset=f` (convolutional) | 377 | 2.2 / 9.2 ms | a third of the cost at 1440p and below |

The visual-parity watch (recorded pairs against an `upscaler=off` twin, `harness/parity-judge.py` +
`colorshift.py`) passed all four: a steady softening at 50 % and nothing that flickers, ghosts or
shifts colour; the DLSS pair on the spinning bench route (zombies, spinning player) had transients at
0.98x the control. The GPU stays saturated at 50 %: the chunk bakes and the composite are not
screen-pixel work, so the gain is the world pass's share. Plan and seam: `docs/plan-upscalers.md`.

Since the second upscaler pass (2026-09-23) the DLSS defaults are `dlssPreset=e dlssOutputPct=67
dlssOutputFilter=rcas dlssDirectColor=true`: the network costs 0.64 ms (preset E) / 1.54 ms (K) at full 5120x2160
output on the 4090, so it only beats no upscaler when it writes less than the screen; reduced output plus RCAS was
+22 % over off in a static storm + fog scene with a sharper image than full-size K. On the 120 km/h drive DLSS still
costs frames; use `fsr1` there.

## Performance overlay (F9 / L3 + R3)

Press **F9** (key binding "Toggle performance overlay", after "Display FPS") or, on a
controller, **L3 + R3** (both sticks pressed together), or use the first row of PZ OPTIMIZATION > Profiler > Performance
overlay > Overlay. The overlay's sampling (**"Sample frame times and utilization"**; every Profiler setting applies at
once) is off by default; showing the overlay switches it on and saves that. It is the build's own profiler, drawn by
the game itself, so it reads the same on Windows and Linux,
in the menus, on the loading screen and in the world, with no MangoHud or RivaTuner.

![The performance overlay in the world: fps, frame-time tail, utilization, verdict and frame graph](../media/performance-overlay.png)

- **Frames**: fps, mean frame time and the active cap; p50 / p99 / p99.9 / max over the last
  5 s; 1 %-low fps, frame-to-frame jitter and the number of spikes above twice the median.
  The fps number is coloured against the cap (blue at the cap, green within 10 %, yellow
  within 50 %, red below; uncapped: blue above 300, green 150–300, yellow 100–150, red under
  100); the "Fps colour" page under Tools changes the thresholds and colours or turns it off.
- **Utilization**: GPU busy share (a GL timer query around the frame's draw commands),
  game-thread and render-thread load as a share of one core, the process's and the machine's
  share of all cores, the heap.
- **Power**: the watts the machine draws and the energy each frame costs (J/frame = watts / fps):
  CPU package, GPU board power, an AMD APU's socket, or the whole laptop while on battery, from
  the counters the system lets a user read (NVIDIA through the driver on Windows and Linux, AMD
  GPUs and APUs, and on Linux the CPU's RAPL counters, root-only on most distributions).
- **Game-thread tree**: *what* the game thread is doing, from its call stack sampled 25 times
  a second on a background thread (`pzopt.GameThreadProfile`; a sample stops the thread for tens
  of microseconds): the phases (update / render / lighting) with their share of the time, under
  each the biggest sub-phases and hot methods (chunk bakes, lighting JNI, player, zombies, the
  frame hand-off wait to the render thread), biggest first, a bar per row, waits in red.
- **Verdict**: "at the cap", "below cap: game thread / render thread / GPU bound", or "below
  cap, nothing saturated: waits or sync" — the case worth reporting. When it is the game thread
  the detailed verdict names the two biggest sub-phases from the tree.
- **Graph**: the last 240 frames as bars with ms ticks, the cap's budget as a line, the GPU time
  in blue.
- **Flame graph**: the last 5 s of the same stack samples, root (`GameWindow.frameStep`) at the
  bottom, callees above, width = share of the time, siblings biggest first from the left; update
  green, render blue, lighting amber, pzopt frames magenta.

Every element is a setting of PZ OPTIMIZATION > Profiler > Performance overlay, off or one of its sizes:
`overlayStats` (fps / tails / full), `overlayTree` (0 to 8 sub-phases per phase),
`overlayVerdict` (short / detailed), `overlayPower` (the power line: CPU / GPU / APU / battery watts and the
energy per frame, from the counters the system lets a user read), `overlayGraph` (240 / 480 / 960 frames), `overlayFlame`
(a 900 or 1400 px column beside the statistics, or below the frame graph) with
`overlayFlameDepth` rows, and `gameThreadProfileHz`. Stack sampling only runs while the tree,
the flame graph, the detailed verdict or the frame log wants it.

The panel fits the screen it is on. `overlayFont=auto` (the default) picks CodeSmall under 1000 px
of screen height, CodeMedium under 1800 and CodeLarge above; on a narrow screen the frame graph shows
fewer frames, the flame column keeps up to a third of the width (the left column's hints and legend are
cut to the rest; under 360 px it moves under the frame graph), and on a
short one the flame graph loses rows, then the frame graph flattens or goes, then the tree loses rows.
Lines that still do not fit end in "...".

"Show the overlay from boot" and "Log every presented frame" in the same group turn sampling
on too; the log is `Zomboid/pzopt-overlay.out`, one CSV row per presented frame in MangoHud's
column names plus `gpu_ms`, `game_load`, `render_load`, `epoch_ms`. Every harness run writes
it and `harness/analyze.py` reports it as `overlay:`. The game-thread profile is logged next to
it: `pzopt-gamethread.out` (per-second phase, sub-phase, hot-method and wait shares; `analyze.py`
prints the route's as `game thread:`) and `pzopt-stacks.out` (the folded stacks), from which
`harness/flamegraph.py <run>` renders the whole route as a self-contained SVG flame graph
(hover for the share, click to zoom, a search box; `--folded` writes the classic `a;b;c count`
format for other tools).

## `pzopt.properties` and the key table

Create `pzopt.properties` next to `projectzomboid.jar` with only the keys you change:

```properties
workers=2
hotsaveIntervalSec=60
fogPass=false
```

The table below is a selection of the main keys; the build has about 1,000. Full list with comments:
[`src/pzopt/pzopt/Config.java`](../../src/pzopt/pzopt/Config.java); the PZ OPTIMIZATION screen's search finds any of them by name.
`dev*` keys are diagnostics and are not listed.

| Key | Default | What it controls |
|---|---|---|
| **General** | | |
| `enabled` | `true` | master switch; `false` = every override on its stock path, the other keys ignored |
| `uncappedFps` | `auto` | `true` / `false` force the cap off / on for a run |
| `instrument` | `false` | per-chunk and per-frame timings for the harness |
| `gpuSections` | `false` | GPU microseconds per frame section in the log (measurement only) |
| `luaChecksumExempt` | `true` | the pzopt Lua files are left out of the multiplayer Lua file check (a server without them refused the join) |
| **Chunk streaming** | | |
| `parallel` / `workers` | `true` / `min(4, cores-1)` (`1` on 4 cores or fewer) | recalc chunks on a worker pool (`false` = stock single thread) and its width |
| `wake` | `true` | wake the streamer on enqueue instead of the 140 ms poll |
| `hotsaveIntervalSec` | `30` | minimum seconds between game-thread hot saves (`0` = stock) |
| `chunkHandoffDivisor` | `8` | freshly loaded chunks handed to the game thread per frame: at most 1 + queue/8 (`0` = stock, up to 4) |
| `loadWorkers` | `max(workers, cores/2)` | recalc pool width while a world loads |
| **Chunk textures** | | |
| `treesInChunkTexture` | `true` | static trees bake into the chunk texture |
| `treeBakePass` / `treeBakeDirect` | `true` / `true` | baked trees drawn by their own pass into every texture the crown reaches, with a height-tilted depth (issue #5); the plain sprite path instead of stock's broken tree batch |
| `treeAppend` | `true` | a newly loaded chunk's trees are drawn into the finished neighbour textures they reach instead of re-baking those textures (half the re-bakes while driving) |
| `windowsInChunkTexture` | `false` | windows and glass doors bake (off since 2026-10-01: baked glass hid the characters behind it, no measured gain) |
| `translucentTilesInChunkTexture` | `true` | `Translucent`-flagged tiles (fences, railings, decorations) bake |
| `translucentLightsPerFrame` | `true` | Translucent light fixtures with a lit sprite stay per frame as in stock (baked, the lit tubes of a gas-station canopy showed through its roof) |
| `curtainDepthNudgePct` | `5` | baked curtains drawn in front of the glass they cover, in % of a tile (issue #4) |
| `bakeBudget` | `8` | chunk textures baked per frame (`0` = unlimited) |
| `rebakeBudget` / `rebakeMaxFrames` | `4` / `3` | re-bakes of on-screen textures per frame; the previous image stays up to that many frames |
| `lightingRebakeMs` | `250` | a texture dirtied only by lighting drift is not re-baked more often than this (`0` = stock) |
| `lightingRebakeBudget` / `lightingRebakeMaxFrames` | `8` / `30` | lighting-only re-bakes started per frame and their longest hold (spreads a lightning flash over frames) |
| `lightingBudget` | `8` | chunk lighting refreshes per frame (`0` = stock) |
| `lightInfoOncePerFrame` / `lightInfoChunkGate` | `true` / `true` | the per-square light-info JNI call once per frame, and skipped for a chunk level the lighting engine reports clean |
| `occlusionSkipLightingOnly` | `true` | keep the occluded-squares grid when only lighting drift dirtied visible chunk levels |
| **Cutaways** | | |
| `cutawayFast` | `true` | replay the stored occluder mask on clean levels |
| `cutawayRadius` / `gridStackInterval` | `6` / `8` | cutaway wall visits only within 6 chunks of the camera; buildings-in-front scan at most every 8 frames while square and facing are unchanged (`0` = stock) |
| `cutawayInvalidateChanged` | `true` | re-bake a chunk after a cutaway visit only if one of its squares' flags changed |
| `cutawayVisitPrefilter` | `true` | a cutaway visit skips walls that cannot cut anything |
| `roofHideDebounceFrames` | `8` | an orphan structure's roof (carports) must be hidden or shown for this many frames before it flips |
| **Sprite buffers** | | |
| `persistentVbo` | `true` | persistently mapped sprite buffers (about 2.7x uncapped at max zoom) |
| `vboBatchKb` / `vboFastQuads` | `1024` / `true` | `VBORenderer` element buffer size (stock 4 KB flushed every 28 quads) and a direct quad writer |
| `textureBufferMb` | `50` | texture upload buffer size |
| **Weather** | | |
| `puddleCache` / `puddleCacheFrames` | `true` / `60` | packed puddle vertices reused per chunk level; backstop rebuild interval |
| `puddleVbo` | `true` | each chunk level's puddle batch in its own GPU buffer, re-sent only on a light change, a camera chunk edge or a re-bake; the camera jiggle as a matrix translation |
| `puddleEarlyZ` | `true` | generated puddle shaders with the depth from the vertex (no `gl_FragDepth` write): occluded wet ground is rejected before the shader runs |
| `rainSplashesFast` | `true` | splash starts by geometric skipping with a local generator instead of one game-RNG call per idle square per frame |
| `rainTiles` | `true` | rain and snow particles packed once per cell and drawn once per screen cell |
| `weatherMaskIdleSkip` | `true` | skip the per-frame weather-mask view scan when it cannot add a mask |
| `weatherFxScalePct` | `100` | weather mask and particle buffers at this share of the screen size (a wash at 50) |
| `fogPass` | `true` | heavy fog in one draw call into a scaled fog buffer, depth-aware composite (experimental) |
| `fogScalePct` / `fogMaskFrames` | `25` / `20` | fog buffer size per axis in % of the screen (100 = per-pixel depth); refresh period of the per-chunk fog masks (`0` = stock walk every frame) |
| `upscaler` | `off` | `bicubic` / `fsr1` / `dlss` / `xess`: the world renders at `upscalerQuality`'s fraction of the screen and is resolved back before the UI and the screen shader (dlss / xess that cannot run continue as fsr1) |
| `upscalerQuality` / `upscalerScalePct` | `quality` / `0` | quality 67 %, balanced 58 %, performance 50 %, ultra 33 %, native 100 % per axis; or an explicit percentage (10-100) |
| `fsrSharpnessPct` | `80` | FSR 1.0 RCAS sharpening, 100 = the sharpest |
| `dlssPreset` / `upscalerObjectMv` | `e` / `true` | the DLSS model (`e` / `f` the convolutional ones, `default` = NVIDIA's transformer presets; `dlssOutputPct` 67 writes less than the screen, RCAS sharpens the rest); characters and vehicles write their own motion vectors |
| `dlssJitter` / `dlssJitterSign` / `dlssMvSign` / `dlssDepthInverted` | `true` / `1` / `1` / `false` | dlss A/Bs: the sub-pixel jitter, its sign, the motion-vector sign, the depth convention |
| **Game thread** | | |
| `lightSwitchCheckFrames` | `15` | a light switch reuses its has-electricity answer this many frames (`0` = stock) |
| `soundZoneCache` | `true` | ambient zone parameters reuse their zone scan while the listener's square is unchanged |
| **Boot** | | |
| `fmodAsync` | `true` | FMOD init on a boot thread |
| `bootPump` / `bootFileThreads` / `earlyModels` | `true` / `max(4, cores-6)` / `true` | boot-time file pool pump |
| `luaPrecompile` | `true` | compile all Lua on a pool at boot |
| `preloadAnimSets` | `true` | parse animation sets at boot |
| `animClipCache` / `packIndex` | `true` / `true` | cache imported animation clips and texture-pack page offsets under `Zomboid/pzopt/` |
| `scriptParserFast` / `itemParamSwitch` | `true` / `true` | linear script parser; `Item.DoParam` switch dispatch |
| **World load** | | |
| `fileThreads` / `fileInflight` | `max(4, cores/2)` / `4x` | async file system width and queue depth |
| `parallelDepthMaps` | `true` | decode depth-map tilesets concurrently |
| `loaderCpuFixes` | `true` | algorithmic fixes on the loader thread |
| `shaderCache` | `true` | reuse model shaders instead of one render step per model (issue #1) |
| `mipmapArrays` | `true` | texture mipmaps on `byte[]` rows instead of per-byte direct-buffer loops |
| `noLoadFade` / `noIntroWait` | `true` / `true` | skip the loading screen's fade to black; new game: click-to-start as soon as the world is loaded |
| **Overlay** | | |
| `overlaySampling` | `false` | measure at all (frame ring, GL timer queries, a sampler thread) |
| `overlay` / `overlayLog` | `false` / `false` | show the overlay from boot; write `pzopt-overlay.out` (both imply sampling) |
| `overlayCorner` / `overlayFont` / `overlayKey` | `tl` / `auto` / `67` (F9) | placement, font and key binding; `overlayFps*` the colour thresholds |
| `overlayStats` / `overlayTree` / `overlayVerdict` / `overlayGraph` / `overlayFlame` | `tails` / `5` / `detailed` / `240` / `off` | each overlay element, `off` or its size ([Performance overlay](#performance-overlay-f9--l3--r3)) |
| `overlayPower` | `true` | the power line: watts per rail and J/frame; `pzopt-power.out` with the frame log. CPU watts need readable RAPL counters on Linux (root-only by default) |
| `overlayFlameDepth` / `gameThreadProfileHz` | `24` / `25` | flame-graph rows above `GameWindow.frameStep`; game-thread stack samples per second (10..1000) |
