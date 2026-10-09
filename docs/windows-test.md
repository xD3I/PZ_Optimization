# Testing the overrides on Windows

First Windows test of the class overrides. Nothing has to be compiled on Windows:
the zip built on Linux carries the finished class files, and the runtime guard
(`pzopt.Overrides`) checks both the game revision and the sha256 of every stock
class it shadows against the Windows jar. If anything differs it logs one line and
the game runs as stock, so the worst case of a mismatch is "no effect".

Current target since 2026-09-28: game revision `4a0e9546ec` (Build 42.21, the stable branch); the releases
below tagged `b42.20.4-*` were for revision `b0bbce05d5`. Release tags are `b<game version>-<yyyymmdd>-<hhmm>-<commit>`
(UTC creation time) since 2026-09-29, when every `win-<revision>-<commit>` release was renamed to that form so that
GitHub's list (by creation day, then by tag name; names that parse as versions, like `42.20.4-...`, rank first within
a day, hence the `b`) runs in release order; tags of releases deleted before then keep their old names below.

2026-09-20 release from `100f441` (flicker of objects inside buildings, doors, windows and corpses fixed; overlay fps colour) is 699 KB, 130 files plus the manifest (131 lines, unchanged); tag `win-b0bbce05d5-100f441`.
2026-09-20 night release from `cc99c05` (thunderstorm pass: puddle cache, rain tiles, VBORenderer batch, lighting re-bake spread, play mode; includes the 100f441 flicker fix and the overlay colours) is 740 KB, 145 files plus the manifest (146 lines); tag `win-b0bbce05d5-cc99c05`.
2026-09-21 release from `4dbe655` (RecalcPool: a failed chunk-recalc retry no longer leaves the publisher blocked, which stopped every later chunk from loading; found in a Windows user's console, StackOverflowError in the stock `isWallTo` recursion) is 741 KB, 145 files plus the manifest (146 lines, unchanged); tag `win-b0bbce05d5-4dbe655`.
2026-09-21 release from `75f9365` (issue #4: curtains draw in the same pass as the window they cover and baked curtains sit in front of the glass, key `curtainDepthNudgePct`; supersedes f93158f) is 743 KB, 145 files plus the manifest (146 lines, unchanged); tag `win-b0bbce05d5-75f9365`.
2026-09-21 release from `667b210` (issue #5: baked trees drawn by their own pass into every chunk texture the crown reaches, with a height-tilted depth, key `treeBakePass`; also the carport roof debounce `roofHideDebounceFrames`, the LightingJNI see-all override and new harness scene flags; supersedes 75f9365) is 782 KB, 151 files plus the manifest (152 lines); tag `win-b0bbce05d5-667b210`.
2026-09-21 release from `1cf5080` (the Windows 0.5 s micro stutter: the overlay's CPU-load sampling ran PDH queries on the game thread twice a second; sampling is now on a background thread and opt-in, key `overlaySampling`, default off, F9 shows a restart notice without it; supersedes 667b210) is 784 KB, 151 files plus the manifest (152 lines, unchanged); tag `b42.20.4-20260921-0613-1cf5080`.
2026-09-22 evening release from `3441a1c` (smooth zoom: `zoomRetain` keeps chunk textures across camera zoom changes and bakes what a zoom-out reveals a few per frame nearest the player, `zoomEaseMs` / `zoomEase` move the zoom along a 300 ms cubic Bézier; also the Louisville horde pass: strong re-bake budget, creation-first bakes, zombie bone math on worker threads) is 35.6 MB, 243 files plus the manifest (244 lines); tag `b42.20.4-20260922-0344-3441a1c`.
2026-09-22 release from `2980cf3` (the 3441a1c build's `zoomRetain` stall: chunk textures stopped appearing past a fixed radius and waiting never filled the rest — pending bits that no path cleared kept the zoom plan flooded and took its credits; now a credit nothing consumed is dropped, camera-motion returns bake at once, only a real zoom change or deferral keeps the flood on; supersedes 3441a1c) is 35.6 MB, 243 files plus the manifest (244 lines, unchanged); tag `b42.20.4-20260922-0908-2980cf3`.
2026-09-22 release from `1555855` (the day's passes: the upscaler — Options > Optimizations > Upscaling, FSR 1.0 / bicubic / DLSS, off by default —, the player line-of-sight pass, the zombie simulation on all cores, the characters draw pass, the world-sound hitch fix, every new key on the tab; no native libraries in the zip, so `upscaler=dlss` runs as fsr1 unless the shim from the repository is under natives/) is 36.3 MB, 344 files plus the manifest (345 lines); tag `b42.20.4-20260922-0932-1555855`.
2026-09-22 night release from `3c21099` (the performance overlay fits any screen size: `overlayFont=auto` picks the font by screen height, the frame graph, flame graph and tree give way on small and short screens, long lines end in "..."; video `docs/media/overlay-responsive-4-screen-sizes.jpg` poster) is 40.7 MB, 359 files plus the manifest (360 lines); tag `b42.20.4-20260922-1850-3c21099`.
2026-09-22 night release from `06242da` (Options > Optimizations > "Zoom motion curve" gains four sliders, x1 / y1 / x2 / y2 of the Bezier ease, with a live plot of the curve; plus Instant Continue from `03b4494`: Continue -> world 3.95 -> ~2.0 s, no click to start, black loading screen) is 40.7 MB, 367 files plus the manifest (368 lines); tag `b42.20.4-20260922-2026-06242da`.
2026-09-22 night release from `ef5ceb4` (fewer hitches on 4-core PCs: C2 JIT off during play on 4 cores or fewer (`jitMode=auto`), the overlay's game-thread sampler pauses only the game thread, zombie cell saves no longer block the game thread (`saveCellAsync`); Dell 120 km/h drive: frames over 100 ms 16.8 -> 8.0 per minute) is 40.7 MB, 373 files plus the manifest (374 lines); tag `b42.20.4-20260922-2048-ef5ceb4`.
2026-09-22 night release from `214eef7` (off-screen zombies no longer thump doors and windows in bursts of 8 / 16 hits with up to 16x the door damage; 240 fps rig: 29 strikes in one second before, a steady 1-3 per second after) is 40.7 MB, 374 files plus the manifest (375 lines); tag `b42.20.4-20260922-2104-214eef7`.
2026-09-22 night release from `da3cdea` (Render distance option: the chunk grid 7-15 chunks per side or vanilla, Rosewood uncapped spin 223.5 fps at the vanilla 19x19 vs 247.8 at 15x15; Dell hitching rounds 2 and 3) is 41.6 MB, 381 files
2026-09-23 release from `d36540a` (Continue: the world builds from the player outwards, and the ground around the player captured at the last exit is shown while loading, tiles popping in, instead of the loading screen; Mac bench save: filled in over 6.4 s of an 8.4 s load) is 41.7 MB, 384 files, sha256 `94a525365bd0d9e151eee34d3ee02dd00f8b2ac1f4d9b5557c286fc8de4d732b`, tag `b42.20.4-20260922-2230-d36540a`.
2026-09-23 release from `e5d025a` (the Continue view no longer shows chunks with an upper floor as black squares; Dell hitching round 4) is 41.7 MB, 385 files, sha256 `5ec895a87869288cfd7e9def719b01ea32f7a46bf44b96fa5820dd1fe7e2c1a0`, tag `b42.20.4-20260922-2258-e5d025a`.
2026-09-23 release from `102ccf9` (Render distance: an Auto entry that fills the screen at the widest zoom, 25 at 5120x2160, and fixed widths up to 31; tested on Linux and macOS) is 41.7 MB, 386 files

2026-09-22 night release from `71b2c6c` (the zombie game-thread pass: zombies 23 → 11 % of the game thread in the Louisville horde, eight default-on keys and three opt-in simulation-LOD keys; the `CanSee` self-recursion fix (StackOverflowError on the Lure action); the Optimizations tab's search box, collapsible sections and "Low-end hardware + FSR 1.0" button; Louisville preview clips per group) is 40.7 MB, 359 files plus the manifest (360 lines); tag `b42.20.4-20260922-1809-71b2c6c`.

2026-09-22 release from `db825a0` (the main menu's UPDATE PZ OPTIMIZATION item: `pzopt.Updater` checks the GitHub releases once per boot and, when a newer build for the game revision exists, downloads it and replaces the files of `pzopt-installed.txt` in place, then asks to quit; build-info now carries `commit=` / `built=`) is 35.4 MB, 223 files plus the manifest (224 lines); tag `b42.20.4-20260922-0114-db825a0`.
2026-09-22 release from `cd1a8de` (harness only: the bench player keeps their worn items during a run — the Louisville video's soft optimized side was the game's short-sighted blur after a zombie bump knocked the ghost player's glasses off, not a render change; no change to the shipped optimizations) is 35.4 MB, 219 files plus the manifest (220 lines); tag `b42.20.4-20260922-0005-cd1a8de`.
2026-09-22 release from `cb48693` (the Optimizations tab's preview panel: stock-vs-optimized clips and per-resource effect bars per setting; `pzopt.GifTextures`, five `PerformanceSettings` forwards, 28 GIFs under `media/ui/pzopt/compare/`, 34 MB) is 35.4 MB, 219 files plus the manifest (220 lines); tag `b42.20.4-20260921-2228-cb48693`.
2026-09-22 release from `0029f16` (installer only: `install.ps1` no longer throws `Cannot find drive` when `libraryfolders.vdf` lists a Steam library on a drive that is no longer connected, seen on a user's `G:`; workaround on older builds is `-Dir "<game folder>"`; the classes are identical to `8bbc11a`) is 893 KB, 188 files plus the manifest (189 lines); tag `b42.20.4-20260921-2216-0029f16`.

2026-09-22 release from `8bbc11a` (game-thread profiler in the F9 overlay: tree of phases / sub-phases / hot methods / waits, flame graph, frame graph with axes, one dropdown per overlay element under Options > Optimizations; profile logs per run) is 893 KB, 188 files plus the manifest (189 lines); tag `b42.20.4-20260921-2142-8bbc11a` (its predecessor `win-b0bbce05d5-dc46e1b` was deleted minutes after publishing: it carried a half-finished options-tab rework swept in from the shared checkout).

2026-09-21 release from `51a6f78` (multiplayer join fix: the `zombie.network.NetChecksum` override leaves the client-only `media/lua/*/pzopt/` files out of the Lua file check a joining client sends, so a community server no longer refuses with "File doesn't exist on the server: .../pzopt_keybinding.lua"; key `luaChecksumExempt`, default on; supersedes d434cde) is 869 KB, 183 files plus the manifest (184 lines); tag `b42.20.4-20260921-2010-51a6f78`.
2026-09-21 release from `d434cde` (blocky / lagging lights of a moving light source fixed: `pzopt.LightDirt` re-bakes strongly changed chunk levels at once, the lighting refresh queue is flushed before each lighting pass; keys `lightingStrongDelta`, `lightingGlobalDeltaPct`, `lightingFlush`; supersedes 31f27f4) is 858 KB, 177 files plus the manifest (178 lines); tag `b42.20.4-20260921-1744-d434cde`.
2026-09-21 release from `31f27f4` (installer fix only: `install.sh` / `install.ps1` pick the newest published release instead of GitHub's list order, which put dcc0ce9 ahead of c69c085; same 176 files plus the manifest (177 lines); supersedes c69c085) tag `b42.20.4-20260921-1500-31f27f4`.
2026-09-21 release from `c69c085` (macOS support: `install.sh` finds `Project Zomboid.app` and installs into `Contents/Java`; plus the storm parity pass from `ce1dc0c` that dcc0ce9 predates: `puddleVbo`, `puddleEarlyZ` with the `pzopt_puddles_*` shaders, `rainSplashesFast`, `treeAppend`; supersedes dcc0ce9) is 854 KB, 176 files plus the manifest (177 lines); tag `b42.20.4-20260921-1419-c69c085`.
2026-09-21 release from `dcc0ce9` (the Low-end hardware profile button in Options > Optimizations for 4-core machines, key `treeBakeMaxChunksPerSec` (trees baked into chunk textures only below that chunk rate; new class `pzopt.ChunkRate`), `workers` defaulting to 1 on 4 cores or fewer; supersedes 8889f72) is 853 KB, 161 files plus the manifest (162 lines); tag `b42.20.4-20260921-1330-dcc0ce9`.
2026-09-21 release from `8889f72` (the fog pass: heavy fog in one draw call into a quarter-size fog buffer, depth-aware composite, keys `fogPass` (experimental, default on), `fogScalePct`, `fogMaskFrames`; new overrides `ImprovedFog`, `ImprovedFogDrawer`, `MultiTextureFBO2`; supersedes 1cf5080) is 850 KB, 160 files plus the manifest (161 lines); tag `b42.20.4-20260921-1109-8889f72`.
2026-09-21 release from `f93158f` (IsoChunkMap mid-scroll guard: `getGridSquareDirect` rejects a chunk that is not where the index says, closing the stock race that made `isWallTo` overflow the stack on the streamer; supersedes 4dbe655) is 741 KB, 145 files plus the manifest (146 lines, unchanged); tag `win-b0bbce05d5-f93158f`.
2026-09-20 release from `b0d4fe6` (black chunk squares fixed, persistentVbo and translucentTilesInChunkTexture on by default) is 694 KB, 130 files plus the manifest
2026-09-20 release from `e398ce5` (master switch) is 607 KB, 114 files plus the manifest
2026-09-20 release from `aa9482b` is 606 KB, 114 files plus the manifest
2026-09-20 release from `2d34aa9` is 543 KB, 104 files plus the manifest
2026-09-23 release from `cbcd436` (fixes a hang while loading a save since the faster Continue: the chat-icon scan raced the early world entry; plus the Dell hitching rounds 2-5: chunk-map lookups without helper calls, allocation-free tile-surface scan, no glGet per frame in the weather drawer, learned render-chunk prewarm) is 43.7 MB, 385 files plus the manifest (386 lines); tag `b42.20.4-20260922-2327-cbcd436`.
2026-09-23 release from `76755df` (Continue: the boot backlog of file tasks drains in parallel, Mac Continue to world ready 8.2 -> 6.3 s; PNG Paeth rows ~40 % faster; the load steps touching the shared texture table run on the main thread, replacing the cbcd436 retry; the Continue view fills top-left to bottom-right) is 41.7 MB, 389 files, sha256 `52ec1b019dda8496132e5db08c7d349bff8e8d524a95a70dc3df4d25d87b56c8`, tag `b42.20.4-20260922-2347-76755df`.
2026-09-23 release from `53fe0e8` (palette PNGs and tile depth maps decode faster, the same bytes; Mac depth-map work 8.9 -> 7.7 s, Continue to world ready 6.4 -> 6.0 s) is 41.7 MB, 390 files, sha256 `58c68965e700a8499ce6ae8373e94b73c8c447f35d3b2007f1e79e4a69b1f974`, tag `b42.20.4-20260922-2359-53fe0e8`.
2026-09-23 release from `4826971` (fixes the windowed resolution at launch: a 1920x1080 window on a 1920x1080 screen opened as 1920x1061 on Windows since the 2026-09-22 window-at-final-size change) is 41.7 MB, 390 files, sha256 `92b353aa78a912c2596d850b1ca87dd03473e012674283e0c96b450a4b3f671b`, tag `b42.20.4-20260923-0022-4826971`.
2026-09-23 release from `2c0ca9d` (the performance overlay draws its panel as one texture per refresh instead of every letter and box as a sprite each frame, and builds its game-thread tree off the game thread: with every element on 12 -> 4 % of the frame rate on the flip laptop, 16 -> 2.3 % on the MacBook; new tab entries overlayTexture / overlayRefreshMs / overlayGraphHz; defaults overlayStats=tails, overlayFlame=off) is 43.7 MB, 392 manifest lines, sha256 `b315e95ed68716912ad1f4eb38ee5721d45445909ab2279d7366c4bec7cbde01`, tag `b42.20.4-20260923-0023-2c0ca9d`.
2026-09-23 release from `4ab3fe8` (a SHOW / HIDE PERFORMANCE OVERLAY item below Options in the main menu and the pause menu, mouse and controller, the same toggle as the F9 key binding) is 43.7 MB, 393 manifest lines, sha256 `61c1de300af64cba028ede75bba435df4d61c7bf9718f94640f272808b9bd55d`, tag `b42.20.4-20260923-0040-4ab3fe8`.
2026-09-23 release from `417778a` (fixes a load hang the previous releases could still hit: the chat-icon scan raced the World Streamer registering textures after the early world entry; the scan is retried on the main thread) is 43.7 MB, 392 files plus the manifest (393 lines); tag `b42.20.4-20260923-0055-417778a`.
2026-09-23 release from `5db6a37` (zombie hordes on the other cores: each zombie's animator, move speeds and animation clock run on worker threads and the bone math runs alongside the rest of the frame; fixes the game's single shared model lock that serialised that work; Louisville horde postupdate 15.8 -> 3.8 % of the game thread, +17 % fps; fixes an occasional ConcurrentModificationException while a save loads; new tab entries animatorParallel / animBatchAsync / animatorPipeline / guardedCallbacks / modelLockPerInstance / poolStatsBatched / lazyPose / headOnWorker) is 43.8 MB, 408 manifest lines, sha256 `73c6b585c47d03f1acc729ef56d80d9c0b00efc3238e7e4103ad193d5acaf2d1`, tag `b42.20.4-20260923-0056-5db6a37`.
2026-09-23 release from `a9a1558` (world entry: the Optimizations tab is built when first opened, the in-game options screen builds in ~28 ms instead of ~124 ms; geometry map zones skip far polygon edges, same result) is 41.8 MB, 411 files, sha256 `15e522661b752383b95ec20577249789f170f76f2108f5320bbe661958e02471`, tag `b42.20.4-20260923-0105-a9a1558`.
2026-09-23 release from `8cb8ccf` (the options screen is built when first opened: main menu build 654 -> 410 ms on the flip, in-game menu 28 -> 2 ms at each world entry, key bindings still loaded at once; the world-entry power check walks only existing levels) is 41.8 MB, 421 files, sha256 `0f4e9411c437f9656904b537698474bd5b1749a1e56b86b081c4b6bfea3560c2`, tag `b42.20.4-20260923-0143-8cb8ccf`.
2026-09-23 release from `8497d41` (the performance overlay's game-thread stack sampler takes 25 samples a second instead of 100: with every element on the overlay costs under 1 % of the frame rate, 2.5 % before on the MacBook) is 43.8 MB, 421 manifest lines, sha256 `9f8605ca4bf309b365f334fd2f549725eb2cd1aa0056326dde975450b54b1474`, tag `b42.20.4-20260923-0157-8497d41`.
2026-09-23 release from `be1f28a` (parked cars spawn again: the decompiled `IsoChunk.AddVehicles_OnZone` filled one row of stalls per parking zone and chunk and gave the High car-spawn rate a flat 2 %; also the fish-per-spot hash in double like the jar, and the build-time bytecode audit of the overrides) is 43.8 MB, 421 manifest lines, sha256 `c3a1490074ec03f55bccd6467cc752340da37c13abac8777f0e339ff09d37bc4`, tag `b42.20.4-20260923-0216-be1f28a`.
2026-09-23 release from `a4ed6f9` (the pause menu opens with its buttons again: Esc in game laid out the not-yet-built options screen and the Lua error aborted the menu's layout, broken since `8cb8ccf`) is 43.8 MB, 421 manifest lines, sha256 `a189a9d9a2134cae4e4356b58ddbff700713629b050e7b00980126cfdebd45ea`, tag `b42.20.4-20260923-0224-a4ed6f9`.
2026-09-23 release from `4bb5acb` (G1 garbage collector by default: the launcher JSON's ZGC is switched for the next launch, `gcMode=stock` or uninstalling undoes it; texture compression in the low-end presets; JIT policy by core count) is 43.8 MB, 421 files plus the manifest (422 lines); tag `b42.20.4-20260923-0259-4bb5acb`.
2026-09-23 release from `1718aa0` (borderless windowed: the window is created undecorated at the monitor origin, so the menu is centred and clicks land on the cursor again, issue #14; the pause menu runs at the Menu framerate cap) is 43.8 MB, 421 files plus the manifest (422 lines), sha256 `0b869a99ce41ea0f4278dd7009dabc1004e5a5a70cd75dba7bcfd71004c028e9`, tag `b42.20.4-20260923-0700-1718aa0`.
2026-09-23 release from `5c72380` (supersedes `1718aa0`: the undecorated borderless creation is Windows-only, Linux keeps the stock window sequence; the Workshop uploader raises its native confirm dialog) is 43.8 MB, 421 files plus the manifest (422 lines), sha256 `749ab97626a7c93b8e657096db9eb9e18502788cb866458ec6d34b851d9a834c`, tag `b42.20.4-20260923-0713-5c72380`.
2026-09-23 release from `d492a26` (supersedes `5c72380`: the Optimizations tab gets a Sort by dropdown, natural / alphabetical / effect on each resource, the preview follows the controller focus, and dependent settings are renamed so both orders agree) is 43.8 MB, 421 files plus the manifest (422 lines), sha256 `74385377b5b685ba72a780c36332283d76b3ae57c81a09ad5c9e77bb15d8e023`, tag `b42.20.4-20260923-0746-d492a26`.
2026-09-24 release from `0fed198` (supersedes `d492a26`: Options > Optimizations draws only the rows on screen, 11.4 -> 4.1 ms a frame on an M1 Pro and D-pad response 18.5 -> 6.5 ms, the same rows reachable by controller; harness-only in-game virtual pad for menu profiling; unit tests no longer read the player's saved options) is 43.8 MB, 422 files plus the manifest (423 lines), sha256 `65d665acf8b59a26de5acf9cde1800046dd17f54ee386ece8696208327b3930f`, tag `b42.20.4-20260923-2211-0fed198`.
2026-09-24 release from `d456427` (supersedes `0fed198`: with any upscaler the view cone no longer shows a second, shrunken cone and the aiming cursor reads its background from the right spot; new DLSS defaults for builds with the shim, preset E, DLSS at the render size with an FSR 1.0 RCAS sharpen, +25 % over no upscaler in a GPU-bound 4K scene; DLSS output size / finish in the Options tab; the release still carries no native libraries) is 43.9 MB, 437 files plus the manifest (438 lines), sha256 `f276073edd3e9f218252b7593760fd55ce35bde7218562000364ac0c70066c2b`, tag `b42.20.4-20260923-2236-d456427`.
2026-09-24 release from `d317261` (supersedes `d456427`: the performance overlay and game-thread profiler settings move to their own Options > Profiler tab with a Reset to defaults button; Render distance is its own section of the Optimizations tab) is 43.9 MB, 437 files plus the manifest (438 lines), sha256 `8845756e40756252a2136ef0d105e46741f1455f04f45c7265c30c1348135ea9`, tag `b42.20.4-20260923-2241-d317261`.
2026-09-24 release from `0672712` (supersedes `d317261`: the main menu's Update PZ Optimization item shows the installed version under it, and current -> new when an update is offered; Options > Optimizations gets an Install DLSS files button, Linux and Windows, that downloads the pzopt DLSS shim and NVIDIA's DLSS library into natives/ on request; the release itself still carries no native libraries) is 43.9 MB, 440 files plus the manifest (441 lines), sha256 `86c95efba18eb179823d0f2cbb47910aaf9f1092fb23e21307ea044917c6337c`, tag `b42.20.4-20260923-2322-0672712`.
2026-09-24 release from `13fc084` (supersedes `0672712`: input latency. The keyboard no longer runs a frame late and every frame reads the newest input, both on by default (60 fps cap with vsync: 29-47 -> 8.5-18 ms from input to screen); Options > Optimizations > Input latency adds an NVIDIA Reflex-style low-latency mode with a cap just below the refresh, Boost (NVIDIA: GPU clocks held up through NVML while a world is loaded), a late-latched in-game cursor and the right-mouse aim hold; still no native libraries) is 43.9 MB, 452 files plus the manifest (453 lines), sha256 `eb04eb3f4ee2866850336850c3b08de8fc9dec02e962f0c8b4e981c106f76a23`, tag `b42.20.4-20260924-0739-13fc084`.
2026-09-24 release from `18509be` (supersedes `13fc084`: variable refresh. On Linux the borderless window is a fullscreen window at the desktop mode, so KDE / GNOME / gamescope enable G-SYNC / FreeSync for it; while VRR is on the game caps itself inside the range (157 fps at 165 Hz) and holds each frame to an even delay (on-screen judder 3.10 -> 0.95 ms at a 100 fps cap); Options > Optimizations > Variable refresh rate, where Windows players set it to on; macOS Apple silicon can present through Metal for ProMotion timing, opt-in; still no native libraries) is 44.0 MB, 456 files plus the manifest (457 lines), sha256 `8f9a3f7b0310617cac09fba5a71f8023b39b17a7aed37c3a70e1c30dc953c893`, tag `b42.20.4-20260924-0748-18509be`.
2026-09-24 release from `8c2b119` (supersedes `18509be`: fixes the weather layer, fog, water and rain splashes flashing on and off, and translucent objects vanishing for a frame, when entering a building in the rain and zooming: an upper chunk level rebuilt alone appended duplicate squares to the per-frame lists, 84 puddle squares on a 64-square level overflowed the puddle renderer and aborted the rest of the world pass; still no native libraries) is 44.0 MB, 456 files plus the manifest (457 lines), sha256 `5dec97fa4dd78afdb36ace98677a31446be0a84089b77cb32c92a9051a1dddb4`, tag `b42.20.4-20260924-0758-8c2b119`.
2026-09-24 release from `b18d84f` (supersedes `8c2b119`: the performance overlay and the game-thread profiler keep working with the optimizations switched off, "Disable all (stock game)" / `enabled=false`, so the stock game can be profiled the same way; the Optimizations tab no longer changes the Profiler tab) is 44.0 MB, 463 files plus the manifest (464 lines), sha256 `4978e1aed3f25148e25d5dc77e56826e20217ced217850e221df37024f6d2a1e`, tag `b42.20.4-20260924-0920-b18d84f`.
2026-09-24 release from `4ecf3f3` (supersedes `b18d84f`: L3 + R3 on a controller toggles the performance overlay like F9, and the main / pause menu item reads "SHOW PERFORMANCE OVERLAY (F9 / L3 + R3)" with the key currently bound) is 44.0 MB, 463 files plus the manifest (464 lines), sha256 `eff3397fb895753a1aea20223880ad1a34bc2a87f442b8d537c107f4d3009864`, tag `b42.20.4-20260924-0921-4ecf3f3`.
2026-09-24 release from `eb305b6` (supersedes `4ecf3f3`: new "Zombie detail follows the frame cap" option, off by default, that lowers the number of 3D / animation-blending zombies while frames miss the cap; compatibility with the PZMulticore mod, alone or with ZBBetterFPS: its chunk loader no longer replaces ours and the zombie update is safe on its worker threads) is 44.0 MB, 468 files plus the manifest (469 lines), sha256 `0f92de0edb138d6be59d8543ff4e18f47bc3fcc4df62ea18dadb5d1be9b2be55`, tag `b42.20.4-20260924-1021-eb305b6`.
2026-09-24 release from `d01292e` (supersedes `eb305b6`: every setting on the Options > Profiler tab, the performance overlay and the game-thread profiler with their elements, colours and rates, applies as soon as Apply is pressed, no restart; the F9 notice and the tab say so) is 44.0 MB, 469 files plus the manifest (470 lines), sha256 `c1be00f567d1e44e8aae2b082ce6ab8c7eed30bd85efe7118ef07e9228de6b8c`, tag `b42.20.4-20260924-1055-d01292e`.
2026-09-24 release from `ecc868d` (supersedes `d01292e`: HDR output on Linux, Options > Optimizations > HDR output, off by default: an FP16 native-Wayland window with the desktop's HDR description, menus at the desktop white, lamp / torch / headlight / fire light, sun on water and lightning above it up to the panel peak; Windows and macOS HDR stay off until tested; fixes a crash on quit of native-Wayland windows) is 44.1 MB, 484 files plus the manifest (485 lines), sha256 `f8065003c23042ee7989eb5384c2d61ed682b6755afeba2eca37a5d2fedccedd`, tag `b42.20.4-20260924-1058-ecc868d`.
2026-09-24 release from `a183c76` (supersedes `ecc868d`: HDR output on macOS through an EDR Metal layer, tested on a MacBook Pro XDR panel; HDR turns itself on when the screen is HDR, new Options > Optimizations > "HDR output: automatic", on by default, SDR screens unchanged; Windows HDR stays off until tested) is 44.1 MB, 484 files plus the manifest (485 lines), sha256 `1d0e9e617b5ea2da638fa07304d835a4117c5f0661b15205874dc7c750dfa2b6`, tag `b42.20.4-20260924-1146-a183c76`.
2026-09-24 release from `4a59216` (supersedes `a183c76`: the Continue loading screen builds the world like the game does, whole chunks popping in bursts in no fixed order instead of a tile sweep; the last view kept at quit is the full frame, new Options > Optimizations > "Last view detail" = floors / buildings / world / full, default full; a chunk that could stay black after entering the world is fixed) is 44.1 MB, 484 files plus the manifest (485 lines), sha256 `640f077de6c5d30e4ca1ae266ff74977028f917424124d709d5341a449311fde`, tag `b42.20.4-20260924-1203-4a59216`.
2026-09-24 release from `50b2d94` (supersedes `4a59216`: crash fix, a zombie thrown into a ragdoll (shot, hit) could start its ragdoll physics on a frame worker and crash the game in its physics library; ragdolls now always run on the game thread; plus the harness-only showcase scene) is 44.2 MB, 486 files plus the manifest (487 lines), sha256 `59c4af03632235730ce33605d608e9d982b0a52e335b5699328e930d53667f0f`, tag `b42.20.4-20260924-1325-50b2d94`.
2026-09-24 release from `1d9fe0e` (supersedes `50b2d94`: power efficiency, new Options > Optimizations > CPU cores and power: on hybrid CPUs background work on the efficient cores and the game / render threads on the fast cores only when the frame cap needs them (`corePlacement`, macOS QoS classes; no effect on Windows yet), the frame limiter sleeps instead of spinning a core (`limiterSleep`, still off by default on Windows), AMD GPU clock governor on Linux (`gpuPstate`), Java compiler trap-limit flags in the launcher JSON (`jitSteady`, applies on Windows too; the uninstallers remove them), the vision-cone blur summed once per texel) is 44.2 MB, 494 files plus the manifest (495 lines), sha256 `ce1ff8aae2621fa01d005a92abc765ddab153c23b01f18c83afaa6bfaf893956`, tag `b42.20.4-20260924-1425-1d9fe0e`.
2026-09-24 release from `b973be9` (supersedes `1d9fe0e`: with a render distance wider than vanilla the grid follows the ground the camera looks at on upper floors, so the screen corners no longer go dark from the second or third floor up; new Options > Optimizations > Render distance > "Render distance follows the view upstairs", default on, single player; never nearer a grid edge than vanilla) is 44.2 MB, 494 files plus the manifest (495 lines), sha256 `688ff75d801a979b1e88c7db11fb3592cc8952ee2c11d005d749cb265d7e712b`, tag `b42.20.4-20260924-1527-b973be9`.
2026-09-24 release from `edb8077` (supersedes `b973be9`: ragdoll fix, a zombie hit or killed while its animation runs on a frame worker (`animatorParallel`) finishes that frame on the game thread, so its ragdoll starts in the same frame and never touches the physics or the shared collision scratch from a worker (player report: `ArrayIndexOutOfBoundsException` in `CollideWithObstacles`, ragdolls sliding, spinning or flying off); the performance overlay's power line (`overlayPower`, Profiler tab: CPU package, GPU, battery); the harness-only horde-shoot ragdoll bench) is 44.2 MB, 497 files plus the manifest (498 lines), sha256 `256663bffd428813db324212c8ba327b65ea969c910f14f6a9074e6faa6d1334`, tag `b42.20.4-20260924-1717-edb8077`.
2026-09-24 release from `b3904da` (supersedes `edb8077`: clear audio, on stereo speakers or headphones the game's fixed 5.1 mix no longer clips under gunfire (FMOD folds to stereo ahead of a -2 dBFS limiter on the master mix, `audioLimiter` / `audioLimiterStereoFold`), and the per-frame sound upkeep on the game thread is a fifth of stock (`emitterIdleSkip`, `soundTickHz`, `worldSoundCleanupFast`, `hearingHoist`); new Options > Optimizations > Sound) is 44.3 MB, 523 files plus the manifest (524 lines), sha256 `54ed92f66aad333d4348ed05d2e88ba8dc8081dba3785412be44f86808c2daef`, tag `b42.20.4-20260924-1745-b3904da`.
2026-09-24 release from `ba77a60` (supersedes `b3904da`: ambient occlusion baked into the chunk textures, off by default (`ambientOcclusion`, Options > Optimizations > Ambient occlusion, applies on the next launch; Windows and Linux, off on macOS's OpenGL 2.1): soft shading along wall bases, in corners and under furniture for 0 GPU time standing, ~4 us a frame walking and 1.3 % while streaming new chunks at 120 km/h, the capped frame-time tail unchanged) is 44.3 MB, 531 files plus the manifest (532 lines), sha256 `271c28351042290dcde9423264c06fb092aaf41df4aa9e18e5624ed3a0213dc6`, tag `b42.20.4-20260924-2016-ba77a60`.
2026-09-25 release from `25052d9` (supersedes `ba77a60`: HDR output, indoors by day lamps and windows no longer flare up (every light blooming) depending on which way the character faces: the light map's reference followed the view cone; by day it now stays off as designed, at night it fades instead of popping when the character turns) is 44.4 MB, 532 files plus the manifest (533 lines), sha256 `527005e44f9744f1294bf6d98423888f7ff3759061e5f813bb9ed1549d4daf2e`, tag `b42.20.4-20260924-2252-25052d9`.
2026-09-25 release from `f837467` (supersedes `25052d9`: the Options > Enhancements tab, upscaling, HDR output and ambient occlusion moved there from the Optimizations tab with before / after preview clips (14 new GIFs, the size jump), every setting on it applies on Apply without a restart except the two HDR output switches (they pick the window on Linux), ambient occlusion strength split into floors / walls / objects / vegetation (`aoStrengthFloorPct` / `WallPct` / `ObjectPct` / `VegetationPct`, the old `aoStrengthPct` is their fallback), and switching DLSS off mid-game no longer freezes the picture) is 58.7 MB, 545 files plus the manifest (546 lines), sha256 `9c937a14e3f2ddcb74e45f7adbbc16eb4dd8a6646167c2e9104baffc88ee117b`, tag `b42.20.4-20260925-0040-f837467`.
2026-09-25 release from `2565479` (supersedes `f837467`: Smooth Operator - Driving, driving through a town at 120 km/h no longer stutters: one prioritized per-frame bake budget for every chunk picture (`bakeScheduler`, `bakeBudgetAdaptive`), 3 mipmap levels per bake (`bakeMipLevels`), chunk textures made ahead of need (`renderChunkTopUp`), the sprite buffers fenced once per frame (`persistentVboFrameSync`), the corpse-flies re-bake loop fixed (`fliesToggleFix`), and present pacing on at a fixed refresh too (`presentPacing=auto`, ~1.7 ms more step-to-screen delay on average); Rosewood 120 km/h at the 240 cap: stock 153 fps / p99 36 ms -> 235 fps / 8.2 ms) is 58.9 MB, 581 files plus the manifest (582 lines), sha256 `cf033c17504f51b136e014e92da0f052631a337697f8e47a43d1c49a1beec6bc`, tag `b42.20.4-20260925-0103-2565479`.
2026-09-25 release from `e9f5209` (supersedes `2565479`: DLSS, rivers and lakes flow again: DLSS's frame blending had slowed the water's ripples to half speed; the water now comes from the frame just drawn (`dlssWaterCurrent`) with one frame of camera-following smoothing against DLSS's sub-pixel shimmer (`dlssWaterHistoryPct`, 60), both on the Enhancements tab and live; still river bank: water motion 0.183 / 0.649 per 1/15 s / 1 s vs stock 0.173 / 0.648, released DLSS 0.085 / 0.49; nothing changes without DLSS) is 58.9 MB, 581 files plus the manifest (582 lines), sha256 `636529c0e636913eeb34d88d5179d17c3780519e06db9412fb78354b50a4e872`, tag `b42.20.4-20260925-0131-e9f5209`.
2026-09-25 release from `f05e11d` (supersedes `e9f5209`: smoother town driving, second pass: a building rolling the trashed-house story on chunk load no longer re-scans the room for every wall square (`kidsRoomMemo`, new `RoomDef` / `RBTrashed` overrides; the 10-100 ms frames), newly streamed chunks are lit one per frame (`lightingNewChunkBudget`, loads and teleports unchanged), occlusion counts on worker threads (`occlusionCountParallel`); Rosewood 120 km/h: 237 fps, p99 7.1 ms, 1 % low 140) is 58.9 MB, 586 files plus the manifest (587 lines), sha256 `32057c2be4722ede2b55b29e8db3c2a4aa1fd4bb7c1d6d5f3717f47f8eb4256a`, tag `b42.20.4-20260925-0728-f05e11d`.
2026-09-25 release from `ed9afc8` (supersedes `f05e11d`, same code: the Workshop page's Smooth Operator - Driving card re-measured after the structural pass (desktop 155 -> 234 fps, 1 % low 29 -> 120; the flip laptop 92 -> 116 fps)) is 58.9 MB, 586 files plus the manifest (587 lines), sha256 `91668d636e61ce8f22e3535e05ccf6a0cd1c02953e26a5f5e459f6fbf7f3b685`, tag `b42.20.4-20260925-1258-ed9afc8`.
2026-09-25 release from `e1b28ab` (per-pixel lighting, Options > Enhancements, off by default) is 58.9 MB, 602 files plus the manifest (603 lines), sha256 `e86f19d8d9c36daa5caf7587916f93bfdbbee2deb3d61e6c3daa7228682a4301`, tag `b42.20.4-20260925-1436-e1b28ab`.
2026-09-25 release from `dc24455` (texture compression on the GPU instead of the driver's CPU compressor, `texCompress`: with the Texture compression option on, the menu no longer drops to 25-34 fps for 10-30 s after boot on Linux/Mesa) is 58.9 MB, 608 files plus the manifest (609 lines), sha256 `fc1b9f9496709918c0a9c3fee3a7af1dba8936cc8c699e5c14ea6b1189a00695`, tag `b42.20.4-20260925-1509-dc24455`.
2026-09-25 release from `8255778` (soft sun and contact shadows: Options > Enhancements > Sun shadows, off by default) is 59.0 MB, 612 files plus the manifest (613 lines), sha256 `a2d99bddc2ab87280155095b43f4efee643c11d31df7e34c12c3ec1d695f6374`, tag `b42.20.4-20260925-1841-8255778`.
2026-09-25 release from `778d733` (Per-pixel lighting: no one-frame flash of the whole picture when the render thread draws a frame twice, e.g. at a chunk crossing) is 59.0 MB, 612 files plus the manifest (613 lines), sha256 `a2a9825ef0c5ba263f56bd168419a1b86220cbcacfd9fc7a17b7b41ec277ee76`, tag `b42.20.4-20260925-1903-778d733`.
2026-09-25 release from `a41fd53` (ambient occlusion: a new chunk's AO is computed in its first bake, so grass no longer shows flat and pale and darkens later while driving fast, `aoArrivalInBake`) is 59.0 MB, 612 files plus the manifest (613 lines), sha256 `6f73aa8ad4dde96e0dbf8b0cb1887fb015e9083ff7b6c11bb05719ed3e1621bc`, tag `b42.20.4-20260925-1906-a41fd53`.
2026-09-25 release from `b70f234` (Per-pixel lighting: no dark grid lines along the chunk edges, no black tree crowns, no dark dots along wall tops or jagged edges where floors meet cut-away walls) is 59.0 MB, 612 files plus the manifest (613 lines), sha256 `c00bf6123f37856b45f438203bf88c9ab06d9943fc1d5cb699768dc1db573ecc`, tag `b42.20.4-20260925-2046-b70f234`.
2026-09-26 release from `e924835` (per-pixel lighting: walls no longer flicker while walking between floors with every lighting setting on, the torch light's surface normal now comes from the baked chunk texture, `pplTexelPos`; a floor change bakes the chunks round the player at once so the new floor appears whole, `bakeLevelChangeFrames`; `docs/findings-wall-flicker-2026-09-25.md`) is 59.1 MB, 637 files plus the manifest (638 lines), sha256 `41df32624ca1d4a39a535e7f779a4e4ef2466b26b9f7d58bca87d74e4f80c4ce`, tag `b42.20.4-20260926-0010-e924835`.
2026-09-26 release from `9bd1b73` (trees lit right with every lighting setting on: no black or flickering tree crowns, `pplAirFill` / `pplPackRing`; trees take sun shade on their lower crown and trunk and cast soft crown shadows, also the trees drawn per frame near the player, `sunShadowTrees` / `aoTreeCanopyPct`, new `FBORenderTrees` override; `docs/findings-tree-lighting-2026-09-25.md`) is 59.2 MB, 643 files plus the manifest (644 lines), sha256 `24eae764088c396da52b35269ace98682ae790242876896a8948988199bf382d`, tag `b42.20.4-20260926-0048-9bd1b73`.
2026-09-26 release from `c17a039` (fluid driving: vehicles, passengers and the driving camera drawn between the 100 Hz physics steps, the camera placed after the car moved, fractional look-ahead, screen-pixel camera when zoomed in: `vehicleSmooth`, `driveLookSmooth`, `cameraScreenPixels`; `docs/findings-car-jitter-2026-09-26.md`) is 59.2 MB, 657 files plus the manifest (658 lines), sha256 `4fc9739696d2883e82bde25a08e5fb2b91ac7b9e68224349d6ceb2bc5f16d27b`, tag `b42.20.4-20260926-0115-c17a039`.
2026-09-26 release from `3dd6fba` (Options tabs: the before / after preview clips are off by default, a "Before / after clips" tick box in each tab's header plays them, key `previewClips`, saved at once) is 59.2 MB, 657 files plus the manifest (658 lines), sha256 `d2c98ba843fb528e52945901c6abeafb8f3f02b130930b595d7efdd86e958077`, tag `b42.20.4-20260926-1002-3dd6fba`.
2026-09-26 release from `ea05422` (darkness floor, remembered places and colour grading on the Enhancements tab, off by default and applied at once: seen rooms and nights keep a faint moonlit light, `darknessFloorPct`; what is out of sight is drawn grey and dimmer instead of dark, `memoryTint`; time-of-day / weather colour grading fused with the stock screen shader into one LUT, cheaper than stock, `colorGrading`; new `pzopt.Darkness` / `Grade` / `GradeMath`; `docs/findings-darkness-grading-2026-09-26.md`) is 59.3 MB, 674 files plus the manifest (675 lines), sha256 `0ed67c1f46062d8b43ddbbaa117cfca8efa20026328a78f2264a1557d718f9ba`, tag `b42.20.4-20260926-1103-ea05422`.
2026-09-26 release from `604498a` (per-pixel lighting light leaks: the torch no longer lights a room behind a wall, `pplTorchCanSee`; torches, lamps and fires light only their own floor, `pplOwnLevelLights`; the torch shadow mask ignores the previous floor's shadows after a floor change, `pplShadowDepthTest`; `docs/findings-ppl-leaks-2026-09-26.md`) is 59.3 MB, 674 files plus the manifest (675 lines), sha256 `7a58c97680342a9854173182008f2ca331193ab3cbcfc056ee39189adfed89e0`, tag `b42.20.4-20260926-1337-604498a`.
2026-09-26 release from `ccc02be` (Options tabs: a "Release date (newest first)" choice in Sort by, one heading per release date with the settings new in this version on top, and the preview's "Released" line; dates generated by `scripts/option-dates.py` from the `win-*` tags into `pzopt_optimizations_dates.lua`) is 59.3 MB, 675 files plus the manifest (676 lines), sha256 `7f39f70c77b5811a4c6b87e3931a086b36eb019760d1686e3a12bd81d5020daa`, tag `b42.20.4-20260926-1355-ccc02be`.
2026-09-26 release from `e8cf412` (Fluid driving fix: crashes damage the car and the driver again, the vehicle smoothing no longer eats the physics engine's one-shot collision flag; harness `ram=true` crash test) is 59.3 MB, 676 files plus the manifest (677 lines), sha256 `d05a4329c130c95fe88561708867d2e96dabd66d6ca8e84c1fbc6fda08fd1df7`, tag `b42.20.4-20260926-1404-e8cf412`.
2026-09-26 release from `c7145ad` (Options > Optimizations: a "Texture compression: who compresses" combo for `texCompress`, auto / worker / driver, so CPU-only or stock driver compression no longer needs a hand-written options.ini line; menu check `harness/texcompress-tab-check.sh`) is 59.3 MB, 676 files plus the manifest (677 lines), sha256 `16c8366c9405b3890f04c8d71916ff04014baef70fc706c8ba5645e3882cda6b`, tag `b42.20.4-20260926-1512-c7145ad`.
2026-09-26 release from `f43331b` (the real sky, with sun shadows, Options > Enhancements, off by default: the sun and the moon placed for the game's date and hour, moon shadows by phase, cloud shadows drifting with the wind in the chunk composite and on water, long soft shadows for a low sun from a far-field height march; fixes east facades lit under a western sun and a light seam between storeys; the game's shaders untouched with sun shadows off; `docs/findings-sky-2026-09-26.md`): tag `b42.20.4-20260926-1559-f43331b`, 693 manifest entries.
2026-09-27 release from `b929049` (low-latency mode fix, `reflexSleep`, off by default: with it on and the 2026-09-25 sprite-buffer defaults the sleep ran away to 30 ms a frame, 20-45 fps in the menu and in game on a Workshop player's Windows PC, 44-47 at a 165 cap on the desktop; the GPU query is flushed and the sleep only takes the game thread's own idle, 165 cap 164.4 fps, uncapped = sleep off) is 59.4 MB, 692 files plus the manifest (693 lines), sha256 `c0f89dec5fc3444d9ea59ebf464682d871d2ba1c9f6025c0b215f15342f966f4`, tag `b42.20.4-20260927-0024-b929049`.
2026-09-27 release from `e12eb89` (flat roofs over a lit room, Spiffo's first, no longer flicker with DLSS and per-pixel lighting on: `pplTexelHeight`, `pplFloorSnap`, default on; `docs/findings-spiffo-roof-flicker-2026-09-27.md`), tag `b42.20.4-20260927-1130-e12eb89`, 693 manifest entries.
2026-09-27 release from `153e43e` (god rays, Options > Enhancements > God rays, off by default: sunbeams through sunlit windows and open doorways with lit patches and dust motes, shafts of light and shade in fog and mist, lamps / torches / headlights glowing softly in mist and rain; ~12 us a frame at 5K on an RTX 4090; needs OpenGL 4.3; `docs/findings-god-rays-2026-09-27.md`), tag `b42.20.4-20260927-1314-153e43e`, 707 manifest entries.
2026-09-27 release from `56b92b9` (installation from the game: Options > Optimizations > Uninstall PZ Optimization, the Workshop item's install helper window with a Copy button and a walkthrough, `install.ps1` / `install.sh` run piped (`irm | iex`, `curl | bash`), use the Workshop copy and wait for a running game; `harness/uninstall-e2e.sh` passed on the flip and the Mac), tag `b42.20.4-20260927-1305-56b92b9`, 695 manifest entries.
2026-09-27 release from `334ec7f` (pixel perfect shadows, with sun shadows, Options > Enhancements, off by default: trees cast one shadow of their own leaves and branches, people, zombies, animals and cars the shadow of their own model in the sun and in torch / headlight beams (per-object shadow maps, `pzopt.ShadowAtlas`); the player's torch casts shadows next to a car again; the stock blob fades under a real sun shadow; `docs/findings-detailed-shadows-2026-09-27.md`), tag `b42.20.4-20260927-2114-334ec7f`, 757 manifest entries, sha256 `2068e7944351b1f83b9ad4d281b4fdaba297069fbefdf16099b34fb6bf699d36`.
2026-09-28 release from `99320b9` (fixes the game ending on world entry on macOS with sun shadows on since the 334ec7f release: the shadow atlas asked the Mac's OpenGL 2.1 context for sampler objects and LWJGL aborts on a missing function; the shadow atlas, tree cards and capsule shadows now check the context first and switch themselves off; `docs/findings-detailed-shadows-2026-09-27.md`), tag `b42.20.4-20260927-2217-99320b9`, 757 manifest entries, sha256 `74c511641c32cabc099c362c6582f91ad2bb762de33380cf3a44a247f98d8df5`.
2026-09-28 release from `433353d` (the animation clip, texture-pack index and compressed-texture caches under `Zomboid/pzopt/` are keyed on the installed pzopt build: the first boot after an update moves the old caches aside, deletes them in the background and re-caches, `pzopt.CacheDir`; master switches at the top of the Enhancements and Profiler tabs, `enhancementsEnabled` / `profilerEnabled`), tag `b42.20.4-20260927-2313-433353d`, 758 manifest entries, sha256 `7a07c67b815b13c38d8b15e40c254987f5ec05ba2109f216328d4c4358661e73`.
2026-09-28 release from `fd3cf1e` (zombie updates on other cores, Options > Optimizations, off by default, get the rest of PR #35 by RealDoomSlaya: shooting into a horde with it on no longer crashes, the physics engine calls of a batched zombie run on the game thread (`physicsDefer`); the combined dispatch mode `entityUpdatePipeline`, off; races fixed in a square's surface data, the pathfinder's scratch objects and the statistics map; 1.4-4.1 ms less game-thread time a frame in a 2,000-zombie horde on the desktop, the flip and the Mac; `docs/findings-gt-offload-2026-09-27.md`), tag `b42.20.4-20260927-2324-fd3cf1e`, 761 manifest entries, sha256 `3dc9181cfbc4fbcce18e871c6de0ee8e7dd4ab46e0b54b8689690438d98d1af5`.
2026-09-28 release from `20d9a2d` (HDR output: the sun's glint on water and puddles from pixel-sized wave facets that flash only while they mirror the sun, Cox-Munk slopes for the wind, Fresnel, a twinkle per wave phase and a faint sheen, instead of one wide highlight that covered half a lake in white blobs at the panel's peak; flip report on the maintainer's lake save, 2.1 % -> 0.08 % of the frame above the UI white; `docs/findings-hdr-2026-09-24.md`), tag `b42.20.4-20260928-0023-20d9a2d`, 761 manifest entries, sha256 `6e5c067a4ef7f927936fa1d15ea2ebdbb6595afc65a3722c01317ba19287c9fe`.
2026-09-28 release from `2a47375` (per-pixel lighting: new Enhancements tab setting "torch glow at your feet", `pplTorchFeetGlow`, on by default and live; off removes the round patch of light under the character when holding a torch, a Workshop request; the beam is unchanged): tag `b42.20.4-20260928-0028-2a47375`, 761 manifest entries, sha256 `fd6c05f6e7fa81932f5d3da4297e0349cd7a4a5b9a154b172027e93432d990b7`.
2026-09-28 release from `a874079` (foliage sway: new Enhancements tab section "Foliage sway", `foliageSway` off by default and live, with strength and leading-edge taps; grass, bushes and trees move in the wind, characters push plants aside, DLSS gets the plants' own motion vectors; +19..21 us a frame at 5K on an RTX 4090 with default settings, 0 with it off): tag `b42.20.4-20260928-0041-a874079`, 770 manifest entries, sha256 `d9fad55306a1d921daa91e807b806abb0a81ce856ca8c1dc1fc207436d7cdfb0`.
2026-09-28 release from `4032bde` (foliage sway: no more whole white frames while walking past trees, seen on AMD / Mesa on the Riverside bridge: the tree bake left the game's shader cache naming the wrong program; the Enhancements master switch now turns Foliage sway off too; `docs/findings-foliage-sway-2026-09-27.md`): tag `b42.20.4-20260928-0906-4032bde`, 770 manifest entries, sha256 `365f9631ba33892bc50f775dfb07ab60a7c9ad9d1b56c17877534164aec7c608`.
2026-09-28 release from `af14ff5` (per-pixel lighting: no more flat pale-grey world without textures with per-pixel lighting, its torch shadows and HDR output all on: the torch-shadow variants of the chunk composite moved the game's DIFFUSE / DEPTH samplers to the driver's order behind the game's cache and drew the chunk depth as colour; Louisville bisect runs `bis*-*`, fix runs `fix-*`), tag `b42.20.4-20260928-1128-af14ff5`, 770 manifest entries, sha256 `060a6c1f7f88295a43e2c6008e538093216cc57ba2ad55c7a97a718cfef2c4f8`.
2026-09-28 release from `5ddff8e` (darkness floor: only drawn, no longer played; it raised the light zombie sight, stealth and the low-light to-hit penalty read, so zombies spotted the player in floored rooms; Louisville player deaths 5 of 9 -> 1 of 15 runs, runs `dfix*`), tag `b42.20.4-20260928-1224-5ddff8e`, 770 manifest entries, sha256 `c212f779728e8f217bc9cfd3e773f665305bd2a96d4a4043f05a874125d5b73f`.
2026-09-28 release from `e805cfd` (Build 42.21, game revision `4a0e9546ec`: every override ported to the stable 42.21; the installers now replace an existing install, so one run of the install line fixes a game that closes at start with the 42.20.4 build still in place), tag `b42.21-20260928-1331-e805cfd`, 771 manifest entries, sha256 `205c392e52ad12a3f8352f065c2d9e82528ad48a4e8b7efed96592b45f4b88b1`.
2026-09-28 release from `aa5b92d` (the AMD GPU clock governor `gpuPstate=auto` no longer switches clocks every few seconds on the Steam Deck: measured slowdown per clock level, the first 3 s of a world and the frames before a switch ignored, a real back-off), tag `b42.21-20260928-2113-aa5b92d`, 772 manifest entries, sha256 `b019da08f7dae4cb28b119d1ab97f84ab796187734fcc83458830b34c1c5a544`.
2026-09-29 release from `8d68485` (lit ceiling lights no longer show through the roof above them: the Fossoil gas-station canopy tubes flickered on its roof while zooming; Translucent light fixtures with a lit sprite stay per frame as in stock, `translucentLightsPerFrame`), tag `b42.21-20260928-2209-8d68485`, 772 manifest entries, sha256 `cc848add746dbd573f47be5b555c8153302ee9b574424c67f7e62aeb2e00f7a2`.
2026-09-29 release from `ea2b9de` (the game's own "Wind sprite effects" option no longer draws every plant per frame: grass, bushes and trees stay baked and bend through foliage sway, `windSpriteSway`; forest at max zoom on the flip 133 -> 369 fps, wind off 372; issue #41), tag `b42.21-20260928-2306-ea2b9de`, 772 manifest entries, sha256 `66822b5f0c766ac28f75bc4fc58f14b0cac3b06cfd15295a649c0898d3c7ea8b`.
2026-09-29 release from `85c61ef` (baked trees: no horizontal stripes across a crown where two trees share a diagonal row; their depths tied and the DEPTH16 rounding picked the front tree row by row, `TreeBake.rowStagger`), tag `b42.21-20260928-2339-85c61ef`, 772 manifest entries, sha256 `26aa474d6d90cb5c082bde0a8d2cb901165ae53cfd4d6fa14bc308b8f47bb26e`.
2026-09-29 release from `89ca721` (Real Blood, part two: new splats on grass and under bushes are drawn into the finished chunk pictures beneath the plants instead of re-baking the floor, `bloodAppendPlants`, on): tag `b42.21-20260929-0239-89ca721`, 792 manifest entries.
2026-09-29 release from `cb0630c` (issue #38: wall cabinets and store canopies get depth fitted to their sprites under per-pixel lighting, AO, sun shadows and reflections, `tileDepthFix` / `tileDepthCanopies`, on with those features): tag `b42.21-20260929-0558-cb0630c`, 793 manifest entries.
2026-09-29 release from `08a66cd` (wet blood: no more one-frame stale pictures, often right after a flashlight toggle; a blood splat texture without a GL id yet made the wet-blood update throw every 100 ms, which ended that frame's world render early), tag `b42.21-20260929-1711-08a66cd`, 794 manifest entries, sha256 `0c2bb31ab5709992a06e1afb31aee3d41f7752a8593b4e8c7a9899a3f1044f5a`.
2026-09-29 release from `b1569b7` (sun shadows: a character's shadow under a low morning or evening sun runs its full length; it was cut on a hard line across the torso past the shadow-map tile's far plane, four squares behind the character, `CapsuleShadow.atlasVis`), tag `b42.21-20260929-2151-b1569b7`, 794 manifest entries, sha256 `4c9b96124c8cb5d97fbebda77366be4c9f2131d7cd5167aecfc46d544e8b4c4e`.
2026-09-29 release from `bc0e9e3` (sun shadows: new Enhancements setting "Sun shadows: update rate", `sunShadowRate`: `frame`, the default, draws a character's, animal's or vehicle's true-shape shadow again every frame in the same frame as the model; `15` keeps the earlier 15-a-second pose shown a frame late), tag `b42.21-20260929-2225-bc0e9e3`, 794 manifest entries, sha256 `91e8da05f427858fae4fa64e2648b2e2ea9c92bb3dceca9242bd122de5421804`.
2026-09-30 release from `b6422a8` (per-pixel lighting, issue #44: no more black tiles over deep basements, the Rosewood secret military base down to level -17; the light lattice holds 32 levels of a chunk instead of 16, so the upper floors of the tallest Louisville towers also get their own light), tag `b42.21-20260930-0030-b6422a8`, 794 manifest entries, sha256 `81380b6b3c2803590e3fc1a7ea8fb990bead4ba3cc7157aea83b3cb7fc51a6e3`.
2026-09-30 release from `71efcf9` (Zero Lag UI: menus, windows, the inventory and the map redraw only what changed and a click or a key shows in the same frame, `uiRetained`, `uiRetainedChildren`, `uiTickStagger`, `uiLuaFast`; world map labels and visited texture, `mapStreetMemo`, `mapStreetCache`, `mapVisitedFast`; the first hot save after an item transfer warmed up at load, `hotsaveWarmup`; all on, Options > Optimizations > Menus, inventory and map (UI); `docs/findings-ui-snappy-2026-09-30.md`), tag `b42.21-20260930-0603-71efcf9`, 819 manifest entries, sha256 `e992f2d95b78af3ccc9a4504fac6be125961ae3519884d8af5bf16cd575655eb`.
2026-09-30 release from `702fdb5` (relief / "parallax textures": the fine relief painted into the art catches moving light, the sun and the moon baked into the chunk pictures, torches with Per-pixel lighting; `relief`, `reliefDepthPct`, `reliefSunPct`, `reliefTorchShadowSteps`, off by default, Enhancements tab; all else unchanged from 71efcf9), tag `b42.21-20260930-0608-702fdb5`, 828 manifest entries, sha256 `9020c20bee653cd6b613b4a0f12e36e1038e32ad9b6200bd4934aba23ea3b02c`.
2026-10-02 release from `f8b5e03` (sun shadows: the head of the player's and the zombies' shadows is no longer cut off flat under a low sun; `docs/findings-shadow-head-cut-2026-10-02.md`; all else unchanged from c70cda6), tag `b42.21-20261002-0734-f8b5e03e`, 909 manifest entries, sha256 `f9395d0a89dea4f03d4d19e82fe01a82510499f7db17a812f7f25d9cf5cc259d`.
2026-10-02 release from `7c4f5fa` (harness only: the benchmark flag `cloud=0..1`, a test storm with the sun partly through; nothing changes in play, all else unchanged from f8b5e03), tag `b42.21-20261002-0748-7c4f5fac`, 909 manifest entries, sha256 `790fc0e712df7198032ac11bc0e7e37fc7d372ea70371186a62805cfff733f6a`.
2026-10-02 release from `9da930b` (main menu: new PZ OPTIMIZATION MOD COMPATIBILITY CHECK item, every Java mod file of the launch with the methods of ours it patches and the settings switched off; its Max performance / Max compatibility choice (`modProfile`, default performance = `modCompat=report` + `uiRetainedMods=true`) with Restart game; UPDATE PZ OPTIMIZATION renamed PZ OPTIMIZATION UPDATE; all else unchanged from 5f72895), tag `b42.21-20261002-1923-9da930b9`, 912 manifest entries (one new file, `media/lua/client/pzopt/pzopt_mainscreen_compat.lua`), sha256 `5ca05c7172d5ae7c26401c06df7226645a08b8096f9bb996b120d77d26646ff6`.
2026-10-02 release from `8d59f07` (the flip's driving report: HDR no longer flickers the whole world one frame darker about once a second while driving, short light maps built during a chunk-map shift are dropped; tree crowns no longer show horizontal stripes with sun shadows / AO, `treeAppend` re-bakes instead while `ChunkAo` or relief keeps a per-texel term; dev `devAoDefines`, `devHdrFrameLog`; `docs/findings-flip-drive-2026-10-02.md`; all else unchanged from 9da930b), tag `b42.21-20261002-2111-8d59f07a`, 912 manifest entries (no new files), sha256 `26d47e74f367e470e2d84b41601e1678c9e2161b49911439331a06c8c69bae21`.
2026-10-03 release from `41839ad` (hotfix of be50962: the see-through trees of the cutaway show again at every zoom; be50962's `treeCutawayScissor` computed its box in offscreen pixels and hid them away from zoom 100 %, removed; the findings corrected: the stock cutaway mask is drawn every frame; all else unchanged from be50962), tag `b42.21-20261003-0212-41839add`, 915 manifest entries (no new files), sha256 `05b43bef6099f7da3405bb2874ed02da73dc56b1d08e6df0ebdf9d5ea4ec7e84`.
2026-10-03 release from `3bd4ae3` (dynamic resolution, off by default: Options > Enhancements > Dynamic resolution; the render size follows the GPU time so the frame-rate cap holds, 50-100 % per axis, resolved by the new temporal upscaler `taau`; also `upscaler=taau`, DLSS following a changing render size, opt-in supersampling; `docs/findings-dynamic-resolution-2026-10-03.md`), tag `b42.21-20261003-0310-3bd4ae38`, 922 manifest entries (7 new: `pzopt/DynRes*`, `pzopt/Taau*`), sha256 `4fe1d44bdb46fc565ca3b3a7aacf688aa1a050a2ef36b37e028a333126d0a67b`.
2026-10-03 release from `6d23257` (per-pixel lighting: wall tiles with a window are no longer a darker full-height column, they took half the light of the room behind the window by day; `pplWallEdge`, default on; `docs/findings-window-column-2026-10-03.md`; all else unchanged from 9b3b7d7 but the sun-shadow cache dev counters of 5668820), tag `b42.21-20261003-1530-6d23257f`, 928 manifest entries (no new files), sha256 `369edddcd52e237ee0bbcdcb228b12cec45ccea5296d2e374a1582e44e78c14d`.
2026-10-03 release from `3d88827` (new Enhancements option "Light from the torch itself", `torchSource`, off by default: carried lights shine from the item's lens instead of the holder's feet, `pzopt.TorchSource`, `docs/findings-torch-source-2026-10-03.md`; plus the run.sh Shift+F2 fix of 7e0991d; all else unchanged from 6d23257), tag `b42.21-20261003-2037-3d88827f`, 930 manifest entries (`pzopt/TorchSource.class` and its `Link` class new), sha256 `b567afa1d2f94ec32aecb949f38eec24d694ee6f495498acab6f2596d06dd8fe`.
2026-10-03 release from `9e9d987` (new Enhancements option "Occluded zombie outlines", `occludedZombieOutlines`, off by default, live: a zombie the character sees keeps a thin contour where scenery hides it; PR #48 by novakovicdavid reworked onto the world stencil, `pzopt.OccludedOutline`, +0.02..0.06 ms a frame with 60 zombies, `docs/findings-occluded-outlines-2026-10-03.md`; all else unchanged from 3d88827), tag `b42.21-20261003-2155-9e9d9878`, 933 manifest entries (`pzopt/OccludedOutline.class` and its two drawer classes new), sha256 `56ba80e360e74dcb02f62e229c79a5893d8b71dc090d94e8bef3e514d472757e`.
2026-10-03 release from `9b3b7d7` (people in cars: with Car glass on the driver and passengers show through the windows, the game's own model of each drawn into an impostor tile the glass reads (`carOccupant`, default impostor; `carOccupantOcclusion`, `carOccupantLightPct`; tiles reused and reprojected between pose-driven refreshes, per car and view, 4 cars a view, the rest a capsule proxy); the car glass no longer runs for the sun shadow pass's car draws; `docs/findings-car-occupant-2026-10-03.md`; all else unchanged from 3bd4ae3), tag `b42.21-20261003-1109-9b3b7d7b`, 928 manifest entries (6 new: `pzopt/CarOccupant*`), sha256 `ad628a8d8f285f47e30e601e942f3d26d8c6cd9f96da668d1265ce6be99f5dec`.
2026-10-03 release from `be50962` (driving through trees as fast as on 42.20: `treeRebakeLazy`, `treeCutawayReach` (`pzopt.CutawayMask`), `treeCutawayScissor`, 42.21's driving tree cutaway `driveTreeCutaway` off by default; `edgeTestFast` (`pzopt.EdgeFast`); dev `devTreePassCycle`, `devReachCheck`, `devStencilProbe`, `devXxlVehicleFade`; desktop drive 320 -> 510 fps, Mac 118 -> 175; `docs/findings-4221-drive-regression-2026-10-03.md`; all else unchanged from 3b4f986), tag `b42.21-20261003-0107-be50962b`, 915 manifest entries (+3, `pzopt/CutawayMask.class`, `pzopt/EdgeFast.class`, `pzopt/StencilProbe.class`), sha256 `be8e0b8bb253df8cc81e721caaddd285e8a2a09e555ab24a0cda54c5624f2a22`.
2026-10-03 release from `3b4f986` (car glass works on macOS: gated on `CoreGl.legacyMac()` instead of every Mac, and the vertex patch always writes `pzEnv`, which Apple's linker requires; MacBook Pro M1 Pro runs `cgmac-*`; `docs/findings-car-glass-2026-10-01.md` "macOS"; all else unchanged from 8d59f07), tag `b42.21-20261002-2338-3b4f9865`, 912 manifest entries (no new files), sha256 `1380f5b895b31dc07dbdba8d1a5c3891651b77ffa1fe4002eb5c6ffdd08daebe`.
2026-10-02 release from `5f72895` (car windows no longer flicker on AMD / Mesa with Car glass on: the glass pass draws with a polygon offset, `carGlassDepthOffset`; all else unchanged from 2716e56), tag `b42.21-20261002-1702-5f728959`, 911 manifest entries (no new files).
2026-10-02 release from `2716e56` (re-dress render errors: ModelManager.Reset waits for the slot's queued draw init, `pzopt.ModelInitOrder`; weather uniforms skip a missing vars array; `sunShareWallHeight`: fences shade a character only below their measured top; all else unchanged from 68e367f), tag `b42.21-20261002-1543-2716e568`, 911 manifest entries (+1, `pzopt/ModelInitOrder.class`).
2026-10-02 release from `68e367f` (foliage sway: walkers no longer tear a vertical seam through the grass and bushes they push; the push bend is continuous and zero on the walker's column, `devSwayPushOld` = the old kernel; `docs/findings-foliage-push-seam-2026-10-02.md`; all else unchanged from 9256aa2), tag `b42.21-20261002-1431-68e367fc`, 910 manifest entries (unchanged).
2026-10-02 release from `9256aa2` (sun shadows: a character's shadow stops at walls and fences instead of showing on the fence's far side and the grass behind it; `sunShadowWallCut`, on by default, live; `docs/findings-detailed-shadows-2026-09-27.md` "Shadows through fences"; all else unchanged from 7c4f5fa), tag `b42.21-20261002-1328-9256aa28`, 910 manifest entries (+1: the CapsuleShadow wall cache class), sha256 `60b8f38da79ac11aa94337159c076bbc40839a9b8c638f676ef59c7d2f3cfb77`.
2026-10-02 release from `c70cda6` (Glass car windows: windows reflect the sky, the surroundings and glints and show the cabin, raindrops, silvered side mirrors, the CarLuxury coupe's quarter windows; `carGlass` off by default, Enhancements tab, next launch; `docs/findings-car-glass-2026-10-01.md`), tag `b42.21-20261002-0036-c70cda62`, 909 manifest entries (+21: the 15 `pzopt_glass_*` vehicle shader copies and the CarGlass classes), sha256 `32384d25220c7698476ae5ed51f3087ace2a8b0bfd76e18ae0145147903d2d9f`.
2026-10-01 release from `439f079` (glass: you and zombies show through windows and glass tiles again; windowsInChunkTexture off by default, new setting glassTilesPerFrame on by default; release `b42.21-20261001-2334-439f079f`).
2026-10-01 release from `f564294` (Mac Unbound: macOS runs the game on OpenGL 4.1 instead of 2.1, setting "macOS: OpenGL 4.1" on by default, so every Enhancement runs on a Mac; Windows and Linux unchanged; release `b42.21-20261001-2236-f5642947`).
2026-10-01 release from `45fb43f` (Options > Profiler > Console log: all / warnings / errors / off, applies at once, quiets PZ Optimization's [pzopt] lines in console.txt and the debug-mode console so other mods' output stays readable; all else unchanged from 1baccb6), tag `b42.21-20261001-1341-45fb43f7`, 839 manifest entries (+1: `media/lua/shared/pzopt/pzopt_log.lua`), sha256 `f9785813a12c4318e64af6cccc54a982c208dc2f2143aef64d94de5a5b975b65`.
2026-10-01 release from `1baccb6` (torch fixes: with Sun shadows on, a car lit by the flashlight no longer shadows itself, black capsule-shaped patches on its rear half; with per-pixel lighting and torch shadows on, bushes inside the flashlight's shadow no longer sparkle with one-frame lit specks, the half-resolution shadow mask is upsampled depth-aware; all else unchanged from 4f09a4f), tag `b42.21-20261001-1322-1baccb69`, 838 manifest entries, sha256 `df32c9690673012a9f3a2bd28262c88e2d7a77e765cd852a06dab23d3f51db6f`.
2026-10-01 release from `4f09a4f` (mod compatibility: Java mods that patch a method PZ Optimization changed switch off only the settings in that method, Options > Optimizations > Mod compatibility; a mod's Lua reached from a helper thread runs on the game thread; the inventory shortcuts leave a mod's ISInventoryPage alone; UI a mod draws is redrawn at the game's rate; all else unchanged from c29e008), tag `b42.21-20261001-1038-4f09a4fa`, 838 manifest entries, sha256 `c87749438e05a43dc82b516af4d3ee979ddf94596c67913ae4f00149007a5a1d`.
2026-10-01 release from `c29e008` (zombies attack a car with the player inside again: the worker-thread animator no longer cuts their attack animation short; no half-invisible zombies with Sun Shadows on, issue #50: the shadow pass no longer composites a clothing texture with colour writes off; all else unchanged from 3420faa), tag `b42.21-20260930-2344-c29e008`, 830 manifest entries, sha256 `ec5c49c6ba728e0556b61c5cd65f8753c5acca8613a50d32834b193fe7332a17`.
2026-10-01 release from `3420faa` (Share settings: Export settings / Import settings... on the Optimizations, Enhancements and Profiler tabs, the non-default settings of all three as key=value text to the clipboard and Zomboid/pzopt/settings-export.ini, imported into the controls and saved on Apply; all else unchanged from 1feb80b), tag `b42.21-20260930-2334-3420faa`, 829 manifest entries, sha256 `8cf975566bf2a895b26c0afbd03a0e62d54a234a1d51446e21de905cb96ecf4b`.
2026-09-30 release from `1feb80b` (torch shadows of thin posts: a carport pole cast several parallel stripes instead of one shadow, the march now samples on a screen grid shared by every pixel, so the shadow is whole and its edge straight; all else unchanged from 0a32301), tag `b42.21-20260930-2200-1feb80b`, 829 manifest entries, sha256 `5f1b11b6e6a762fa6fc2950c023079043f434451c80f4cddcec00c222a2be872`.
2026-09-30 release from `0a32301` (sun shadows: buildings no longer shade their own walls and windows; the far-field pass counted outer walls, eaves and corner posts as blocks in front of the facade; all else unchanged from 1274f92), tag `b42.21-20260930-2129-0a32301`, 829 manifest entries, sha256 `246911e71be6cccae321f8a5a33c2687323735594a33ff7f4104f6ce95094c40`.
2026-09-30 release from `1274f92` (HDR output: no lamp-light flicker while zooming; the lamp-light map's screen projection in double precision; all else unchanged from 702fdb5), tag `b42.21-20260930-0811-1274f92`, 829 manifest entries, sha256 `2be4041bb508fc53f3c18ab18ef71e4b324ecc31710a0d36bbdac4f3db4de157`.
2026-09-29 release from `7595669` (Real Blood: floor blood drawn in one batch per chunk and new splats on bare floors painted into the finished chunk pictures, `bloodBake`, `bloodAppend`, `bloodRebakeCoalesceMs`, all on; new Enhancements section Wet blood, off by default): tag `b42.21-20260929-0211-7595669`, 788 manifest entries.
2026-09-29 release from `0b7f075` (per-pixel lighting: the goods on store shelves no longer flash black or shimmer while walking, the Riverside Fossoil counter; `pplSeenEdge`, `pplJiggle`, `pplDepthOpaqueOnly`, all on; includes the 675137c AO foliage change): tag `b42.21-20260929-0201-0b7f075`, 774 manifest entries.
2026-09-29 release from `675137c` (ambient occlusion on foliage, issue #40: bushes, hedges, grass and flowers no longer shade themselves leaf by leaf, `aoPlantLeafOcclusion`; trees and small plants get separate strengths, new `aoStrengthPlantPct`; every AO strength offers 25 %; defaults unchanged), tag `b42.21-20260929-0155-675137c`, 772 manifest entries, sha256 `938cdd6149dda261c16cd90f6ea0eb5297a52eccfc0706f069e3fafc0dc39a73`.
2026-09-27 release from `683e1be` (fixes the whole world drawing black, floor / walls / roofs, on AMD and Intel GPUs under Linux (Mesa) with god rays on: the haze's 3D texture sat on the default unit and Mesa's program validation rejected the chunk composite; maintainer's flip save), tag `b42.20.4-20260927-1335-683e1be`, 707 manifest entries.
2026-09-27 release from `3f8f7dc` (fixes the whole world drawing black, the player too, on AMD Radeon under Windows since the god rays release, god rays off: the chunk and world composites stay stock with god rays off, and a patched composite puts the game's own textures back on their stock units whatever order the driver lists the shader's samplers in (`Shaders.stockSamplerUnits`, god rays / HDR / per-pixel lighting); player console.txt + Workshop reports, not reproduced here), tag `b42.20.4-20260927-1535-3f8f7dc`, 707 manifest entries.
2026-09-27 release from `c316dbc` (game-thread offload: character render prep, per-pixel light packing, the scheduler classification, animal sight checks, zombie stats, the vision polygon, AO world masks and the translucent draw order on worker threads or cached; zombie updates on other cores (PR #35) included, off by default, calm zombies only; fixes the crash at quit after shooting zombies, `Ragdoll::deleteRigidBodies`: orphan ragdolls the game built for corpses, `ragdollCorpseGuard` + `ragdollQuitSweep`; `docs/findings-gt-offload-2026-09-27.md`), tag `b42.20.4-20260927-1855-c316dbc`, 744 manifest entries.
2026-10-04 release from `d641c36` (mirror and window reflections, Options > Enhancements > Mirrors and windows, off by default, applies at the next launch: the room, the street, and the player, zombies and cars mirrored as their real other side; `pzopt.Mirrors`, `docs/findings-mirrors-2026-10-03.md`), tag `b42.21-20261003-2238-d641c36e`, 946 manifest entries, sha256 `c30160e40c3063eccb9d3b3da47d1235917b9446105bda2bbe2ac6fb776d6949`.
2026-10-04 release from `6eac10a` (mirrors: the room behind a wall mirror's glass rebuilt from the game's tiles, furniture as its turned facing, the far wall in the mirror wall's paint, `mirrorsGeometry` / `pzopt.MirrorGeometry`; `docs/findings-mirrors-2026-10-03.md` "The room behind the glass"), tag `b42.21-20261004-1008-6eac10a5`, 953 manifest entries, sha256 `f884fcedda15d8cb16b16fc6c72f2b417b1dc50957579d81684935dd1fe7f393`.
2026-10-04 release from `18b55b4` (options tab: the sidebar works with a controller; every setting readable on a 1920 x 1080 window: the clips switch and the search hint no longer overlap the view switch, long names wrap, sidebar names fit; checked with `options_read=1` + `harness/options-readability.py`), tag `b42.21-20261004-1503-18b55b49`, 954 manifest entries, sha256 `86ea5a6d7dca117d0d58f0e4319e5622fcb8c99bfabf076ca530d2c5d4c65765`.
2026-10-04 release from `1a3b2d4` (pixelLight: no striped rectangle of dark bands on a torch-lit tree crown at night; a crown and its copy in a neighbouring chunk texture take their light from the same level; `docs/findings-crown-rectangle-2026-10-04.md`), tag `b42.21-20261004-1806-1a3b2d40`, 954 manifest entries, sha256 `2331e002e61d8175de522d45425c60049cc840e6cb33a487722ae8a8a02a4efc`.
2026-10-04 release from `c8b8a3c` (flashlight: no second, paler cone beside the beam with pixelLight: lamp 1 and player 1's torch shared lighting id 1 and the native lists one entry per id, so the torch was lit twice where the lamp reached; `lampIdsApart`, lamps take ids from 1048576; `docs/override-edits.md` LightingJNI sixth edit), tag `b42.21-20261004-2011-c8b8a3c5`, 954 manifest entries, sha256 `2528ec9bc1572caad31ba944656b8223224a8fd58af4f413f3fac83c71f3e6d7`.
2026-10-05 release from `ac1a2ca` (light and shadow fixes from a Discord report: the torch adds to a car's headlights, no dark wedge under a street lamp with a torch, no dot mesh on walls with per-pixel lighting, a weaker light outline round leaves with AO, headlight / torch character shadows without torn strands; docs/findings-light-shadow-2026-10-04.md), tag `b42.21-20261005-0030-ac1a2ca4`, 954 manifest entries.
2026-10-05 release from `ca3fefd` (manhole covers, drains and litter no longer flicker in stripes over rain puddles: those Translucent tiles lying on the ground are drawn every frame again, after the puddles, as in stock; new setting `floorDecalsPerFrame`), tag `b42.21-20261005-1432-ca3fefd6`, 955 manifest entries.
2026-10-05 release from `981b060` (Java memory: the heap sized by `gcHeap=auto`, 4 GB or 8 GB with 30 or more mods, at most half the RAM; mods' Lua `collectgarbage()` no longer forces a Full GC, `luaGcNoop`; new tab section "Java memory and garbage collector"; `docs/findings-gc-heap-2026-10-05.md`), tag `b42.21-20261005-1556-981b0602`, 956 manifest entries.

2026-10-05 release from `f97d8ed` (Options > Key Bindings: the overlay binding reads "Toggle Performance Overlay" instead of its raw translation key, Discord bug report; `PerformanceSettings.pzoptDefaultUiText`), tag `b42.21-20261005-1617-f97d8edc`, 956 manifest entries.
2026-10-05 release from `1359faf` (white flat roofs no longer flicker in the rain, Discord bug report: `puddleJiggleDepth`, the cached puddle depth follows the camera jiggle, and `swayFloorExact`, floors bake their exact depth under foliage sway; `docs/findings-roof-puddle-flicker-2026-10-05.md`), tag `b42.21-20261005-1745-1359fafe`, 956 manifest entries, sha256 `d18fc54420d467935f4d46f5c33ed3445f7d1f7cdd5e674cc51646cd4d0ab1a2`.
2026-10-05 release from `3850f0e` (the Java memory setting offers 12-64 GB heaps besides auto / the game's own / 4-8 GB; over half the RAM is lowered to half), tag `b42.21-20261005-1816-3850f0e1`, 956 manifest entries.
2026-10-06 release from `37ff46e` (fixes in two optional Louisville keys found on the flip / Mac test: `zombieSpawnSpread` waits at most `zombieSpawnMaxAgeMs` 1000, `lootDefer` re-checks a container before a late roll; defaults unchanged), tag `b42.21-20261005-2237-37ff46ec`, 970 manifest entries.
2026-10-06 release from `8807065` (a wall mirror by a room corner no longer blinks in and out as you walk near it: `mirrorsCutawayHoldMs` 750; also `postupdateParallel` and `zombieReuseSpread`, both off), tag `b42.21-20261006-0002-88070650`, 977 manifest entries.
2026-10-06 release from `815468f` (fix: Steam's `-cachedir=` now moves pzopt's options.ini, Export / Import and the mod-compat files with the game's user folder instead of leaving them in `C:\Users\<user>\Zomboid\pzopt`; the console says `[pzopt] user folder <dir>`), tag `b42.21-20261006-1152-815468f0`, 1059 manifest entries, sha256 `818457c9010a946ca45013ed0acd543a4ad857fa86691297c241f12437dc4a51`.
2026-10-06 release from `bc1f75d` (Louisville 120 plan A, off by default: the per-frame translucent tile pass recorded on the frame workers and spliced in stock order, `tileRecordParallel`; docs/findings-louisville-120-tile-record-2026-10-06.md), tag `b42.21-20261006-0131-bc1f75d7`, 1059 manifest entries, sha256 `e907eae4d34d85b2394b33a36fc7bf091fb7087452b8a45bf847b5fc928dbfe4`.
2026-10-07 release from `33b6b6b` (foliage sway: the edges of walls, door frames and windows with plants behind them no longer sway with the plants, `swayOccluderCheck`; Workshop report, house corner at 14325,4948; before / after videos `docs/media/foliage-sway-wall-edge-*.mp4`), tag `b42.21-20261007-1218-33b6b6b1`, 1064 manifest entries, sha256 `6470e015f9d869314e26568804b9e5cb05e3fad7615d1bb49133e8fdaded0996`.
2026-10-07 release from `3ca7520` (the Louisville checkpoint's military tent roofs no longer show an egg-crate pattern with Ambient Occlusion + Sun Shadows: `aoRoofSkip` now reaches the AO kernel's sun variant and the tents' WestRoof-typed roof tiles count as roofs, for the god rays too; Discord report; video `docs/media/tent-roofs-ao-before-vs-fix.mp4`), tag `b42.21-20261007-2058-3ca75206`, 1064 manifest entries, sha256 `c0ffecc90c35cf2e4e3a11965b10490dc061abfbea79e47740176fe3bee8083d`.
2026-10-07 release from `8259ca9` (low-end mode: the "Low-end hardware (4 cores or less)" preset now holds a locked 60 on a Core i5-6300HQ / GTX 960M with a 9-chunk render distance, vsync and the frame limit at 60; new preset "Low-end hardware + best look at 60" with sharp sprite filtering, colour grading, god rays, memory tint, mirrors and windows; docs/findings-low-end-mode-2026-10-07.md), tag `b42.21-20261007-2105-8259ca9a`, 1064 manifest entries, sha256 `d4e5584c305103df4c15eaf239d0a757049313787547a9a47e48675051e69363`.
2026-10-07 release from `dbee0c2` (sunrise / sunset shadows: no bright / dark flicker as the sun moves near the horizon, a sun step is applied to the whole screen at once and eased in, `sunStepSync` / `sunStepFadeMs`; long shadows reach their tips, cars and characters 40 squares, buildings 96, tree and bush crowns in the far field, fences at their sprite's height, `sunShadowReachFade`; docs/findings-low-sun-flicker-2026-10-07.md, video `docs/media/low-sun-shadows-before-after.mp4`), tag `b42.21-20261007-2117-dbee0c2f`, 1064 manifest entries, sha256 `4d50d22e70756be18fcdbfae749251c776a88e9cefa08219ce62f6977e98cef8`.
2026-10-07 release from `eceb5cf` (shadows on characters and cars, `entityShadows` with sun shadows: probe bricks through the god rays' occupancy grid, capsule shadows of other bodies and cars, self-shadow from the ShadowAtlas tiles, clouds per pixel, torch / headlight shadows on characters, capsule ambient occlusion; docs/findings-entity-shadows-2026-10-07.md, video `docs/media/entity-shadows-before-after.mp4` local; Workshop New! card 74 / 75), tag `b42.21-20261007-2219-eceb5cf2`, 1074 manifest entries, sha256 `c12edf2975cd10ccda3a7bdd20285493aa4128c4bb3c1842c69068e8d4b76205`.
2026-10-08 hotfix release from `65759d1` (AMD / Windows: no invisible player, zombies, cars or items; the model shaders stay stock with sun shadows off, bindless samplers as int handle halves; docs/findings-entity-shadows-2026-10-07.md "Invisible models on AMD / Windows"), tag `b42.21-20261008-0132-65759d1c`, 1074 manifest entries, sha256 `45d27b4aa3298ee54053d6c3f7fa95a89ea2462f7806ea180066a4273bb9b88c`.
2026-10-08 release from `3fe8723` (see-through trees no longer flash opaque for a frame with sun shadows on: entity shadows' probe compute moved after the cutaway mask; docs/findings-xxl-tree-flicker-2026-10-08.md), tag `b42.21-20261008-0415-3fe8723f`, 1075 manifest entries, sha256 `e5930af81bb8677b3815df9e09d1b2fd99c1efd4f96df3ad3f56624372bab049`.
2026-10-08 release from `aec3f93` (mirrors: only the people of the mirror's own room, plain glass in undiscovered rooms, no flicker while walking: re-drawn when the room changes; docs/findings-mirror-rooms-2026-10-08.md), tag `b42.21-20261008-2154-aec3f93a`, 1076 manifest entries, sha256 `d21490f635a444d7dad86ce8900bad2daeddf175a24605cc90ecb0b24277e79e`.
2026-10-09 release from `84f875e` (reflective props: glass doors, store fronts, counters and cases, fridges, the glass table, gym mirrors reflect; steel, ceramic and screens show the people passing; docs/findings-prop-reflections-2026-10-08.md), tag `b42.21-20261008-2236-84f875ec`, 1081 manifest entries, sha256 `9a87729b7f7929de2eea4b38cf3c1449e8a753cc179c4129889a35a26a930118`.
2026-10-08 release from `f7c4c4a` (cloud shadows: no light grid along the chunk borders, the kept term's share filled past the drawn edge for the composite's level-1 read; docs/override-edits.md "Cloud shadow grid lines"), tag `b42.21-20261008-1828-f7c4c4a0`, 1075 manifest entries, sha256 `e09d6636c7dfffa93c1e4cd025d42f76f111b0cdf32ef181b4026530a16d43be`.
2026-10-08 hotfix release from `5199e47` (AMD / Windows: no black screen at every sun step with sun shadows on; the sun-step fade's old term and the cloud term as int handle halves, `cloudHandleInts`; docs/findings-amd-step-fade-2026-10-08.md), tag `b42.21-20261008-1650-5199e474`, 1075 manifest entries, sha256 `9a543818f3a5402e64fb13e4e6a6df3c31a8590ac5e831a3c7a0098f6e72a8db`.
2026-10-07 release from `5f910d9` (uninstalling always works, `docs/findings-uninstall-2026-10-07.md`: the boot repair removes a build for another game revision or replaces it from the Workshop copy and finishes an in-game uninstall the helper left, then restarts the game; `Uninstall-PZ-Optimization.cmd` / `uninstall-pz-optimization.bash` and `pzopt/uninstall/install.{ps1,bash}` in the game folder; release assets `uninstall.ps1` / `uninstall.sh`; installer fallbacks and `-Force`; the Windows helper as a script file), tag `b42.21-20261006-2335-5f910d9f`, 1064 manifest entries, sha256 `87082b0054bf344d6912850e7ae3775da5b71fae55d32fb83a503b56e88412fe`. Windows test of the uninstall paths pending (the list in the findings).
2026-10-05 release from `11916bf` (Louisville horde pass: exact game-thread trims on by default, `profilerIdleFast`, `statsNoBox`, `stateMachineNoIter`, `worldgenPatternCache`; off by default: the frame-slack scheduler `slackWork` with `chunkHandoffSlackWork` / `lootDefer`, `animalLosSnapshot`, `zombieSpawnSpread`, `zombieModelAddBudgetUs`; docs/findings-louisville-120-2026-10-05.md), tag `b42.21-20261005-2157-11916bfa`, 970 manifest entries.
2026-10-05 release from `bc3db90` (a house's upper floor seen cut open from the yard: furniture against the hidden outside wall no longer black with per-pixel lighting, `pplCutEdge`; no dark bands across roofs with ambient occlusion, `aoRoofSkip`), tag `b42.21-20261005-2042-bc3db908`, 956 manifest entries, sha256 `828c8b8125b83bbb13a28102b3ce7d2204bcee06673c90a79e0e287ea8cf1a0b`.
2026-10-05 release from `c7324ec` (water reflections blinking out for single frames with Mirrors and windows on: mirrors' static pass reused the reflection keys' image unit 6 between the composite and the water; Ssr rebinds its images before every lookup; flip pool save 47 -> 0 blinking frames), tag `b42.21-20261004-2253-c7324eca`, 954 manifest entries, sha256 `f1849fe33f08c4a863a4578fa4eaf54c2a09a3f1cb70c39ee80c263e8229690a`.
2026-10-04 release from `8e1feef` (the PZ Optimization options tab missing for players who launch with `-debug`, issue #58: the game's Lua compiler in debug mode allows 200 declared locals per function and the single-tab rewrite had buildPage at 216 / relayout at 205, so the whole options file failed to load; split into smaller functions, `scripts/LuaDebugCompile.java` in build.sh), tag `b42.21-20261004-2150-8e1feef0`, 954 manifest entries, sha256 `27e32b6f217068e098cfab869bd79cc0022be3419e1d70d27b7e30310e72dc3a`.
2026-10-04 release from `e2749f2` (mirrors and windows: the red melee aim outline of a zombie no longer appears again beside each reflecting pane, outside the glass: the reflection pass re-rendered the character with its outline flag on; car-glass occupants get the same guard; harness rig `outline_zombies=N`), tag `b42.21-20261004-1402-e2749f29`, 954 manifest entries, sha256 `2ff093df637a56e5b5cdd00f78cc2636f8c2e133a0df90554903d87d011c0073`.
2026-10-04 release from `69763e8` (options: the Optimizations, Enhancements and Profiler tabs are one PZ Optimization tab: home page with presets, master switches and category tiles, a sidebar, subcategory tabs with an Overview, Visuals as cards, Simple / Advanced / Everything, Fix a problem, one search; `src/lua/client/pzopt/pzopt_optimizations_layout.lua`), tag `b42.21-20261004-1341-69763e8e`, 954 manifest entries, sha256 `8e5ade2975fdf4d460d7f0b07b5b3ef8d898e2efec5f1a48cdf02768efca8f0c`.
2026-10-04 release from `4795635` (mirrors: no glass-coloured speckle zoomed in, rays start 0.2 squares out; the room geometry reads tile depth maps front corner = 0, so the reflected player's legs are no longer hidden by the floor; people shown straight in front of where they stand and a little higher, `mirrorsViewLateralPct` 0 / `mirrorsViewDropPct` 50, so the medicine cabinet shows the head from the sink; `docs/findings-mirrors-2026-10-03.md`), tag `b42.21-20261004-1259-47956358`, 953 manifest entries, sha256 `6d03b8a69cb24f53af3bc047f251818ab36dbc8accabb20769e21fd08d0e9f92`.
2026-10-04 release from `e214134` (cloud shadows hold still while the sun moves and only drift with the wind: the projection direction is held while clouds are drawn; `docs/findings-sky-2026-09-26.md`), tag `b42.21-20261004-1149-e2141344`, 953 manifest entries, sha256 `e5d27d01c13181fe4f8b4feeae2657a7fe81af3d60e700f6fe4b819784ceeb7c`.
2026-10-04 release from `fc866c5` (mirrors in a real house: no grey bands of the outside wall over a wall mirror, no brick slab in a medicine cabinet; a floor the camera cannot see stands in at `mirrorsStandInPct` (50) over the glass; `docs/findings-mirrors-2026-10-03.md` "Walking round the mirrors of a real house"), tag `b42.21-20261004-0257-fc866c5e`, 950 manifest entries, sha256 `e7bd6938d6aa64137f545c2bd631c58fec41e3841a63e3a4f9a0014cdabefcea`.
2026-10-04 release from `7754cb6` (god rays: soft shaft edges and sunlit patches, dust glints, Options > Enhancements > God rays, `godRaysSoftPct` / `godRaysGlintPct`; no shaft through a house onto the street at a low sun, `godRaysApClip`; windows and doorways under a roof let no light in, `godRaysRoofRule`; closed doors with glass let light through it, `godRaysDoorGlass` / `pzopt.DoorGlass`; `docs/findings-god-rays-2026-09-27.md`), tag `b42.21-20261004-0116-7754cb65`, 948 manifest entries, sha256 `de947ea7018bcf22bc41441c91ef36934fd59c846736da7a5141bfb045c57f0a`.
2026-10-04 release from `0277de1` (mirrors fixes from a player save: the map's wall mirrors, `WallOverlay` tiles attached to their wall, now reflect; reflections no longer blink while zooming; a stale mirrored-model queue that switched mirrors off mid-session is dropped each frame), tag `b42.21-20261003-2336-0277de18`, 947 manifest entries, sha256 `77d094194bb987925acd01bd2925b63eaf38054842698a07f8c768e705ef1ca6`.
2026-10-04 release from `8d97af9` (the `torchSource` option of 3d88827 on top of 9e9d987's occluded outlines, plus pixelLight flicker dev rigs `devPplFrameLog` / `devVisBlinkTrace` and two experimental keys off by default, `pplEaseMs` / `pplTorchFade`; `docs/findings-torch-source-2026-10-03.md`), tag `b42.21-20261003-2207-8d97af98`, 935 manifest entries, sha256 `ac6e466ddd60df0ea1b24fa1aaafa549af8ddd674660b60ef9a738090253c2f4`.
2026-09-26 release from `84def73` (sprite filtering, Options > Enhancements > Sprite filtering, off by default: texel-aware anti-aliased point sampling at the 75 % zoom level, one explicit mip level with two adaptive taps when zoomed out, per-frame tile variants; `docs/findings-sprite-filter-2026-09-26.md`) is 59.4 MB, 686 files plus the manifest (687 lines), sha256 `35a5cd21f4a2bf79eeb3fadd458b1a027ae9e28a8b0ea15c6a92aba6f9670853`, tag `b42.20.4-20260926-1523-84def73`.
2026-09-26 release from `ad13b6d` (ambient occlusion: the vegetation strength also sets the shade tree crowns cast on the ground, which followed the floor strength before; player report) is 59.2 MB, 657 files plus the manifest (658 lines), sha256 `b0ffecac9c2f92fb112fa14cbbb2260ce5a92d4d6031786d86df0b0fea9ba354`.
2026-09-26 release from `6207264` (HDR exposure local to world lighting, PR #26 by novakovicdavid: switching off the room light at night no longer over-exposes the other rooms, the night key follows each square's own neighbourhood instead of the screen average, new `pzopt.HdrExposure`; `docs/findings-hdr-2026-09-24.md` "Local exposure reference") is 59.2 MB, 647 files plus the manifest (648 lines), sha256 `9f6bde2386049e0069b93df46c8ff74d22d28e59b710d79950848a359bdafe42`, tag `b42.20.4-20260926-0054-6207264`.
2026-09-26 release from `1e023a1` (sun shadows: a driver or passenger seated in a vehicle casts no body-shaped sun shadow through the car; the vehicle's own shadow is unchanged) is 59.1 MB, 636 files plus the manifest (637 lines), sha256 `3ecc48288ca47f5e36a72538417b337942c3289fb5227bc5772f6d11ed1a3d30`, tag `b42.20.4-20260926-0009-1e023a1`.
2026-09-26 release from `10bbe1a` (reflections on water and puddles, Options > Enhancements > Reflections, off by default, applies at the next launch; `docs/findings-reflections-2026-09-25.md`) is 59.1 MB, 636 files plus the manifest (637 lines), sha256 `00171078a441d92ee55a2aebc1bbdac6540b63b18707f7b97455043f44f94d72`, tag `b42.20.4-20260925-2349-10bbe1a`.
2026-09-26 release from `5e6645c` (near-instant updater: only the changed files are fetched, in the background once an update is offered, so Update now takes milliseconds; updates from the Steam Workshop copy on disk, issue #16; Restart game after an update) is 59.1 MB, 626 files plus the manifest (627 lines), sha256 `e88b4663cb0b133183976b80a5fcb31fd6ade69db9462ed0351cbdd0341fabfc`, tag `b42.20.4-20260925-2336-5e6645c`.
2026-09-25 release from `ab4b22b` (PR #20: with HDR and ambient occlusion both on, no one-frame overexposure of the whole screen when changing floors; HDR re-binds its light map before each use) is 59.0 MB, 612 files plus the manifest (613 lines), sha256 `39064f77986674cfba57abe16dacd14b33611c23eafe5c2c26162d1499ea7901`, tag `b42.20.4-20260925-2055-ab4b22b`.
2026-09-25 release from `e3a5fdc` (PR #19, issue #13: the fog pass and the ambient-occlusion passes restore the shader through `ShaderHelper`, so draws after the fog such as UI mods in `OnPostRender` no longer vanish or draw unshaded at some camera zooms) is 59.0 MB, 612 files plus the manifest (613 lines), sha256 `a426d8459a3a91dc8d8bd53728cc9c7edac34a594f326f9e20abf1b1df5f9e28`, tag `b42.20.4-20260925-1912-e3a5fdc`.
2026-09-25 release from `2d48ba9` (harness only: the `explore=circle` walk rig and the per-region flicker tools; nothing changes for players) is 59.0 MB, 608 files plus the manifest (609 lines), sha256 `d33f89be6357c92fb6826da1026db6d0a6f3c657c134aca235a44d50e8232516`, tag `b42.20.4-20260925-1806-2d48ba9`.
`pzopt-files.txt`; the 2026-09-19 test build from `a202778` had 97) for game
revision `b0bbce05d5` (Build 42.20.4).

## Before rebooting (on Linux)

Get the zip somewhere Windows can read. The Linux side is ZFS and XFS, which
Windows cannot mount, so use one of:

```sh
# a) the NTFS partition (sda2, 480 GB, unmounted right now)
sudo mount -t ntfs3 /dev/sda2 /mnt && cp build/pzopt-b0bbce05d5-classes.zip /mnt/ && sudo umount /mnt

# b) a GitHub release asset (download from a browser on Windows)
gh release create win-test-b0bbce05d5 build/pzopt-b0bbce05d5-classes.zip \
  --title "Windows test build (42.20.4 / b0bbce05d5)" --notes "class overrides for the Windows test, see docs/windows-test.md"
```

Also bring this file (or open it on GitHub).

## On Windows

All commands are PowerShell. `$PZ` is the game folder; adjust it if Steam is not
in the default place (Steam, right-click the game, Manage, Browse local files).

### 1. Check the game

- Steam, Properties, Betas: the game must be on **Build 42.21** (the default branch). Any other
  version makes the guard disable the overrides.
- Close the game.

```powershell
$PZ = "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid"
Get-Content "$PZ\ProjectZomboid64.json" | Select-String -Context 0,3 classpath
```

The classpath block must list `"."` **before** `"projectzomboid.jar"`. If it does
not, stop: loose class files would never load on this depot.

Confirm the folders the zip will create do not already exist (they should not on
a stock install; `media` exists and only gets one Lua file added under
`media\lua\client\pzopt`):

```powershell
Test-Path "$PZ\pzopt"; Test-Path "$PZ\zombie"; Test-Path "$PZ\org"; Test-Path "$PZ\se"
```

All four should print `False`.

### 2. Install

Unpack the zip straight into the game folder. It only adds files; it never
touches `projectzomboid.jar`.

```powershell
Expand-Archive -Path "$env:USERPROFILE\Downloads\pzopt-b0bbce05d5-classes.zip" -DestinationPath $PZ
Get-Content "$PZ\pzopt-files.txt" | Measure-Object -Line     # 1059 on the bc1f75d and 815468f releases, 977 on the 8807065 release, 970 on the 11916bf and 37ff46e releases, 956 on the 981b060, f97d8ed, 1359faf, 3850f0e and bc3db90 releases, 955 on the ca3fefd release, 954 on the ac1a2ca, c7324ec, 8e1feef, c8b8a3c, 1a3b2d4, 18b55b4, e2749f2 and 69763e8 releases, 953 on the 4795635, e214134 and 6eac10a releases, 950 on the fc866c5 release, 948 on the 7754cb6 release, 947 on the 0277de1 release, 946 on the d641c36 release, 935 on the 8d97af9 release, 911 on the 2716e56 and 5f72895 releases, 910 on the 68e367f and 9256aa2 releases, 909 on the c70cda6, f8b5e03 and 7c4f5fa releases, 888 on the 439f079 release, 887 on the f564294 release, 839 on the 45fb43f release, 838 on the 1baccb6 and 4f09a4f releases, 830 on the c29e008 release, 829 on the 3420faa, 1feb80b, 0a32301 and 1274f92 releases, 828 on the 702fdb5 release, 819 on the 71efcf9 release, 794 on the bc0e9e3, b1569b7 and 08a66cd releases, 793 on the cb0630c release, 792 on the 89ca721 release, 788 on the 7595669 release, 774 on the 0b7f075 release, 772 on the 675137c, 85c61ef, ea2b9de, 8d68485 and aa5b92d releases, 771 on the e805cfd release (Build 42.21), 770 on the a874079, 4032bde, af14ff5 and 5ddff8e releases, 761 on the fd3cf1e, 20d9a2d and 2a47375 releases, 758 on the 433353d release, 757 on the 334ec7f and 99320b9 releases, 744 on the c316dbc release, 707 on the 153e43e, 683e1be and 3f8f7dc releases, 695 on the 56b92b9 release, 693 on the f43331b, b929049 and e12eb89 releases, 687 on the 84def73 release, 677 on the e8cf412 and c7145ad releases, 676 on the ccc02be release, 675 on the 604498a and ea05422 releases, 658 on the 3dd6fba, ad13b6d and c17a039 releases, 648 on the 6207264 release, 644 on the 9bd1b73 release, 638 on the e924835 release, 637 on the 1e023a1 and 10bbe1a releases, 627 on the 5e6645c release, 613 on the ab4b22b, b70f234, e3a5fdc, a41fd53, 778d733 and 8255778 releases, 609 on the 2d48ba9 and dc24455 releases, 603 on the e1b28ab release, 533 on the 25052d9 release, 532 on the ba77a60 release, 524 on the b3904da release, 498 on the edb8077 release, 495 on the b973be9 and 1d9fe0e releases, 487 on the 50b2d94 release, 485 on the ecc868d release, 470 on the d01292e release, 469 on the eb305b6 release, 464 on the b18d84f and 4ecf3f3 releases, 421 on the 8cb8ccf, 8497d41, be1f28a and a4ed6f9 releases, 411 on the a9a1558 release, 393 on the 4ab3fe8 release, 392 on the 2c0ca9d release, 390 on the 53fe0e8 and 4826971 releases, 389 on the 76755df release, 386 on the 102ccf9 release, 385 on the e5d025a release, 384 on the d36540a release, 381 on the da3cdea release, 345 on the 1555855 release, 244 on the 2980cf3 and 3441a1c releases, 224 on the db825a0 release, 220 on the cd1a8de and cb48693 releases, 189 on the 0029f16 and 8bbc11a releases, 184 on the 51a6f78 release, 178 on the d434cde release, 177 on the 31f27f4 and c69c085 releases, 162 on the dcc0ce9 release, 161 on the 8889f72 release, 152 on the 1cf5080 and 667b210 releases, 146 on the 75f9365, f93158f, 4dbe655 and cc99c05 releases (131 on b0d4fe6, 115 on e398ce5, 105 on the 2d34aa9 release, 97 on the 2026-09-19 test build)
Get-Content "$PZ\pzopt\build-info.properties" | Select-String "^revision"
```

`Expand-Archive` refuses to overwrite existing files unless `-Force` is given.
Do not give it.

### 3. First launch

Launch from Steam normally. On the first boot the caches under
`%USERPROFILE%\Zomboid\pzopt\` (animation clips, texture-pack index, Lua
prototypes) are written, so it is a slower boot than the ones after it.

Then read the log:

```powershell
Select-String -Path "$env:USERPROFILE\Zomboid\console.txt" -Pattern "\[pzopt\]" | Select-Object -First 40
```

What to look for:

| Line | Meaning |
|---|---|
| `[pzopt] loaded override zombie.iso.IsoChunk (target revision b0bbce05d5, active)` | the guard passed; one such line per class |
| `game revision is X but overrides were built for b0bbce05d5; overrides disabled` | Windows depot is a different revision. Note X and stop; the test is over |
| `jar copy of ... differs from the one the overrides were built against` | same revision, different class bytes on the Windows jar. Note which class; the overrides are off |
| no `[pzopt]` line at all | the classes did not load. Re-check the classpath in step 1 and that `$PZ\pzopt\Overrides.class` exists |

If the guard disabled the overrides, everything below is moot but the game
should still run normally. That result is itself worth bringing back.

### 4. What to test

Do these in order and write down what happens at each step.

1. **Main menu.** Time from double-click to the menu, roughly. The TIS logo
   screens are skipped; the menu should appear directly.
2. **Options, Display.** The framerate combo has an **Uncapped** entry and
   300/330/400/430/500 fps entries, and a separate **Menu framerate** combo
   sits below it. Pick a value, apply, quit to desktop, relaunch: the value
   must survive. The setting lives in `%USERPROFILE%\Zomboid\pzopt\framecap.ini`.
3. **Continue a save.** Use a copy of a save, not the real one, if the save
   matters. Note the Continue-to-world time.
4. **Drive at max zoom** for a couple of minutes on a road with trees and
   buildings. Watch for:
   - black one-tile rectangles beside walls or black floor rectangles
   - trees that are missing, or that pop in late next to buildings
   - windows or glass doors that do not draw, or draw when they should be cut away
   - chunk edges that arrive noticeably late at speed
   - stutter on the 2 s beat (that would be a mod, PZDashboard, not this)
5. **Walk through buildings.** Cutaway walls around the player, doors and
   curtains opening and closing, windows breaking: the baked chunk textures must
   refresh when the state changes.
6. **Quit to menu and Continue again** without closing the game.
7. **Frame rate.** With the in-game limiter at Uncapped and vsync off, note the
   fps the game shows (or use the Steam overlay's fps counter). No MangoHud on
   Windows; a rough number is enough.

For a stock comparison on the same machine, press **Disable all (stock game)** in
Options > Optimizations and relaunch (**Enable all** brings the defaults back), or
create `$PZ\pzopt.properties` with the master switch off and relaunch; delete the
file to return to the defaults:

```properties
enabled=false
```

Every override then takes its stock path, the same fallback a build mismatch uses;
the per-key switches are ignored while it is off.

For per-frame numbers add `instrument=true` to that file (or to an otherwise
empty one): the game then writes `pzopt-frames.out` and `pzopt-chunks.out` in
`%USERPROFILE%\Zomboid`, which `harness/analyze.py` can read back on Linux.

#### Repeatable Windows worker / dual-CCD benchmark

`harness/run-win.ps1` now launches the installed bundled JVM with a **fresh private
profile under its unique result directory**, rather than replacing the player's
`pzopt-bench` save or harness mod. Existing saves, mods/default.txt, harness flags,
console/pzopt outputs, options and caches are never moved, removed or restored over.
The installed classes, properties and launcher JSON are read-only inputs; per-run
properties use `-Dpzopt.*`. Only the options seed (`options.ini`, `pzopt/options.ini`,
`pzopt/framecap.ini`) is copied; the private `options.ini` sets
`showSurvivalGuide=false` before launch. Stock Survival Guide opens on world load
with this option enabled and calls `setGameSpeed(0)`, so an unattended route would
wait for someone to dismiss the F1 help window. The player's setting stays intact.
The harness disables updater checks and launcher/AOT rewrites for this process,
redirects crash logs into the result directory, and
retains its entire owned profile for diagnosis. There is no cleanup of preexisting
directories. A timeout stops only the JVM this invocation launched.

Results default to `%LOCALAPPDATA%\pzopt-benchmark\runs`, not the repository:
the checkout can be a junction onto a nearly-full Steam volume. `-OutputRoot`
overrides this for both scripts; ensure that destination has room for a fresh
private save and caches on every run.

The game must be closed **for the supplied `-PZ` installation**. Steam itself and
games from other installations are not killed or attached to. When no game process
exists, the script starts `jre64/bin/java.exe` using that installation's launcher
classpath and Windows JVM settings, with Steam integration off and `-cachedir` set
to the private profile. A named install lock refuses concurrent benchmark runs.
This is a direct-launch benchmark, not a measurement of Steam launch time.

To benchmark new code before installation, pass `-Classes .\build\classes` to
`run-win.ps1` or `sweep-win.ps1`. The compiled classes are prepended to the
installed loose-class/jar classpath and fingerprinted with each run; neither
the installed override files nor the player's profile is changed. Use the same
`-Classes` setting for every run in a comparison.

Prerequisites: installed loose overrides and their file manifest, launcher
classpath beginning with `"."`, a user options seed whose native first-run terms
screen has already been completed, and Windows `tar` with zstd support. The fixture
is always `harness/bench-save/pzopt-bench-template.tar.zst`, extracted only into the
new private profile; user-owned templates and caches are never used. Preflight
rejects unsafe archive entries, unexpected fixture mod dependencies and a dictionary
containing non-vanilla mod IDs, naming the blocker instead of silently disabling a
required mod. PZDashboard is removed from the private save by default; explicit
`-Dashboard -DashboardMod <source>` copies only that mod into the private profile.

Run safe preparation before any measured launch:

```powershell
$PZ = 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid' # your Steam library's game folder
.\harness\run-win.ps1 -PZ $PZ -Label preflight -PrepareOnly
```

`-PrepareOnly` creates owned evidence/profile directories but never starts a game;
it is not a valid measured run. `-RouteSeconds` is the **expected** duration checked
by analysis, not a route-control flag: bench duration comes from `route`, `speed`
and optional `hold`. Coordinates and multi-leg routes remain intact in `-Flag`.
For a direct default-vs-6/8 comparison, use the same flags and fresh private
profiles for each run; alternate the order and repeat both configurations:

```powershell
$flags = @('start=12450,1280','population=max','settle=20',
           'route=S:150','speed=6','turn=90','zoom=max')
$baseline = .\harness\run-win.ps1 -PZ $PZ -Label defaults -RouteSeconds 25 `
  -Flag $flags -Prop @('instrument=true')
.\harness\analyze-win.ps1 $baseline.RunDirectory
$run = .\harness\run-win.ps1 -PZ $PZ -Label f6-d6-dual-ccd -RouteSeconds 25 `
  -Flag $flags -Prop @('frameThreads=6','charDrawThreads=6','fileThreads=8','corePlacement=dual-ccd')
.\harness\analyze-win.ps1 $run.RunDirectory
```

The repeatable sweep tests **one worker-count axis at a time**: frame workers with
draw fixed, then draw workers with frame fixed. Each round rotates/reverses the
configuration order and alternates adjacent `off`/`dual-ccd` placement pairs. Options
are frozen once, the installed build/fixture/mod/seed bytes are fingerprinted,
and changes during a sweep are refused. Every run starts with fresh private
optimization caches; these are consistently cold, not the player's warmed caches.

```powershell
.\harness\sweep-win.ps1 -PZ $PZ -Scenario louisville -Repeats 3 -BaselineFrameThreads 6 `
  -BaselineCharDrawThreads 6 -FrameThreads 2,4,6 -CharDrawThreads 2,4,6 -CorePlacement off,dual-ccd

# Seeded nearby crowd, natural population off, 1-tile motion + 24-second hold:
.\harness\sweep-win.ps1 -PZ $PZ -Scenario crowd -Crowd 40 -Repeats 3
# After choosing a measured frame count, isolate the draw axis at that count:
.\harness\sweep-win.ps1 -PZ $PZ -BaselineFrameThreads 6 -FrameThreads 6 `
  -CharDrawThreads 2,4,6,8,12,14 -Repeats 3
```

Keep the game foreground during the entire route; do not run other workloads.
`run.json` records CPU core/logical counts, requested settings, command/provenance
and lifecycle; `analysis.json` captures `Config.describe`, effective worker-pool
startup counts, actual placement summary, route/zoom/resolution, p99/p99.9, FPS,
zombie counts, sampled focus, waits and route CPU evidence. A requested count that
is clamped is invalid rather than mislabeled: with `dual-ccd` the frame and draw
pools are capped at the main CCD's physical cores minus two (6 on this 9950X3D,
4 on a 6+6 part), so larger counts are not valid points of a `dual-ccd` sweep.
The Java placement implementation detects the cache and physical-core topology;
the scripts never assume CPUs 0–7 are the main CCD. With `corePlacement=dual-ccd`
the main CCD is the larger L3 (the 96 MiB V-Cache CCD here), or CCD0 when both
L3 are equal (7950X/9950X, 9950X3D2). Game, render, frame, character-draw and the
other known critical threads share all of its cores; known background workers use
the other CCD. 6+6 and 8+8 parts work with SMT on or off. ZGC stop-the-world
workers stay wide; unknown/unlabeled native threads remain allowed on both CCDs.
The unpinned default on Windows is `corePlacement=auto`, `frameThreads=8`,
`charDrawThreads=14` and, on this 16-logical-CPU host, `fileThreads=8`. It is the
appropriate no-overrides comparison.

The same mode is selectable in **Options > Optimizations > CPU cores and power >
Which CPU cores run the game's threads (hybrid / dual-CCD)**; worker threads above
the main CCD's limit are clamped automatically. Apply and restart for it to take
effect. This does not change the Windows default mode or the CPU placement of
unproved native threads.

Louisville's scripted camera route is deterministic, but native zombie population
generation is not. The crowd scenario seeds placement selection, not every aspect
of zombie simulation. Both record route-start/end zombie counts; crowd also checks
the actual spawned count. Sweep comparison excludes count outliers versus the
reference configuration's median (default ±10%, `-ZombieTolerancePct`), differing
zoom/resolution/builds, incomplete routes, missing route markers/frames, lost focus,
missing wait/CPU evidence or crashes. There must be at least two usable repeats
for a configuration **and** the reference before a comparison is eligible.

`sweep.json` retains all individual valid/invalid runs and exclusion reasons;
`comparison.csv` gives medians, valid/invalid repeat counts and FPS/p99/p99.9 deltas
against the fixed baseline with the first placement mode (default `off`). No invalid
run contributes to an eligible comparison. Inspect individual tails and count
dispersion, not just FPS: a 25-second run can have very few p99.9-tail samples.
Frame and character-draw wait counters are cumulative since boot at route end,
**not route deltas**; slower runs must not depend on periodic console snapshots.
Route-only frame times, thread CPU and raw counter evidence remain available.
`analyze-win.ps1 <run-dir> ...` to regenerate the per-run JSON; it never falls back
to whole-game frames when route markers are missing. No worker-count winner or
9950X3D performance improvement is claimed without measured eligible repeats.

### 5. If the game crashes

Bring back `%USERPROFILE%\Zomboid\console.txt` and any `hs_err_pid*.log` from
`$PZ`. The line right before the crash in console.txt is usually the answer.

### 6. Uninstall

Options > PZ Optimization > Uninstall PZ Optimization... in the game, or without the game: double-click
`Uninstall-PZ-Optimization.cmd` in `$PZ` (every install since 2026-10-07), or
`irm https://github.com/xD3I/PZ_Optimization/releases/latest/download/uninstall.ps1 | iex`. Each removes exactly the
files of `pzopt-installed.txt` (without one: the files that are PZ Optimization's), the folders they leave and the
launcher edits. The jar was never modified, so no Steam file verification is needed (and it would not remove the
loose files). The test list for these paths and the boot repair is in `docs/findings-uninstall-2026-10-07.md`.

The caches in `%USERPROFILE%\Zomboid\pzopt\` can be deleted by hand; the game
never reads them without the overrides installed.

## Results 2026-09-19 (first Windows test)

Machine: the same box booted into Windows 11 IoT Enterprise LTSC 2024 (Ryzen 7 9800X3D, RTX 4090,
NVIDIA driver 616.92 / 32.0.16.1692, Azul Zulu 25 bundled JRE, ZGC). Game at
`C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid`, Build 42.20.4, jar sha256 and
size identical to the Linux depot's, classpath `"."` before the jar. Desktop 5120x2160, game
fullscreen at desktop resolution, vsync off, in-game cap 500 fps (`framecap.ini gameFps=500`),
launched through Steam. No MangoHud; utilization from `Get-Counter` + `nvidia-smi`.

Install: `Expand-Archive` of the zip, 97 manifest files, no pre-existing folders. Guard: every
override logged `active`, no revision or class-hash mismatch. Nothing had to be compiled.

### Boot

| Boot | Menu after process start | Notes |
|---|---|---|
| 1 (cold caches) | ~40 s | 1522 anim clips written, FMOD "Error initializing output device" (no audio device set up on this Windows install, not ours) |
| 2 (warm) | 21 s | anim clip cache 2161 hits / 0 misses |
| 3+ (warm, bench runs) | ~15 s | boot pump 5.9 s, anim sets preloaded 1.0–1.2 s |

Quitting from the main menu of boot 1 crashed on exit: `EXCEPTION_ACCESS_VIOLATION` in
`ZNetJNI64.dll` under `SteamWorkshop.n_GetInstalledItemFolders`, reached from
`RenderThread.shutdown → IsoPuddles.getInstance → Texture.getSharedTexture → ZomboidFileSystem.validatePrefix`
(the puddle renderer is constructed for the first time during shutdown and asks Steam for mod
folders after the Steam API is gone). Stock code path; a menu quit of a later boot did not
reproduce it. Dump kept as `harness/runs/win-boot-20260919/hs_err_pid3536-boot1-quit.log`.

Frame-cap combos: the maintainer confirmed Uncapped, 300–500 and the separate Menu framerate combo; a
500 fps choice survived a relaunch (`framecap.ini`, `[pzopt] frame cap: game 500 fps`).

### Bench route, optimized vs stock switches

`harness/run-win.ps1` (a PowerShell port of the run.sh steps a bench needs) on the committed
bench save, `--flag zoom=max` (max zoom on this install is **2.0**, not the 2.5 of the Linux
runs: options.ini `zoomLevels2x` tops out at 200), dashboard off, `instrument=true`. Stock =
every switch in the list above off. Analysis with `harness/analyze.py` (embeddable Python 3.12
under `%LOCALAPPDATA%\Programs\Python312-embed`).

| Run | fps mean | frame mean | p50 | p90 | p99 | p99.9 | max | >33 ms |
|---|---|---|---|---|---|---|---|---|
| `win-bench-stock-20260919-222950` | 172.5 | 5.8 ms | 4.8 | 10.1 | 19.1 | 28.4 | 46.8 | 8 |
| `win-bench-opt-20260919-222646` | 251.7 | 4.0 ms | 3.2 | 6.8 | 13.9 | 19.9 | 42.2 | 2 |

| Run | chunks | queue wait mean | queue wait p99 | recalc threads |
|---|---|---|---|---|
| stock | 4294 | 174 ms | 351 ms | World Streamer only |
| optimized | 4294 | 30 ms | 74 ms | 4 × pzopt-recalc |

Utilization over the route window (sysmon, ~62 samples each):

| Run | machine CPU | busiest core | game process | main thread | GPU load | GPU W |
|---|---|---|---|---|---|---|
| stock | 29 % | 66 % | 271 % of a core | 88 % of wall | 70 % (p90 99) | 139 |
| optimized | 28 % | 70 % | 274 % of a core | 93 % of wall | 60 % (p90 82) | 157 |

Finding against the objective: at 252 fps under a 500 cap neither the machine (28 % of 16
cores) nor the GPU (60 %) is saturated; the game thread is (`main` 93 % of wall). The
optimized build is game-thread-bound on Windows exactly as on Linux. Stock burns more GPU per
frame (the per-frame tree/translucent pass) for fewer frames.

Not directly comparable with the Linux native numbers (zoom 2.0 vs 2.5, 500 vs 240 cap,
Windows driver 616.92), but the direction and the p99 gain (19.1 → 13.9 ms) match the Linux
result (19.3 → 8.3 ms at zoom 2.5).

One stock run only: no noise floor yet. Two runs were discarded and are kept with an
`-INVALID-` suffix: the first optimized run lost window focus for 11 s right after the world
came up (options.ini `focusloss=true` pauses the game; `IsoChunk.update` stops, so the
sampler's first route frame was 12.6 s and the route started late), and the first stock run
got its properties on one comma-joined line (`powershell -File` does not split `-Prop a,b`;
run-win.ps1 now splits on commas itself).

Not yet done from the list above: the manual drive at max zoom, walking through buildings,
quit-to-menu-and-Continue, and the visible-fps reading with the in-game limiter at Uncapped.

### Zoom 2.5 (same evening, after the 250 % zoom level was added)

Same machine, cap and route; options.ini `zoomLevels2x` now starts at 250, so `zoom=max`
gives 2.5 and the zoom-out buffer is 12800x5400, the Linux configuration. Two stock runs
for the noise floor. Summaries in `harness/archive/2026-09-24/baseline/windows/z25/`.

| Run | fps mean | frame mean | p50 | p90 | p99 | p99.9 | max | >33 ms |
|---|---|---|---|---|---|---|---|---|
| `win-bench-z25-stock-20260919-224824` | 122.4 | 8.2 ms | 6.7 | 14.3 | 26.1 | 36.5 | 52.3 | 32 |
| `win-bench-z25-stock-20260919-225229` | 121.7 | 8.2 ms | 6.6 | 14.6 | 27.0 | 38.3 | 47.3 | 30 |
| `win-bench-z25-opt-20260919-224518` | 194.0 | 5.2 ms | 4.1 | 9.4 | 18.5 | 26.6 | 49.8 | 8 |

Noise floor between the two stock runs: 0.7 fps, 0.9 ms at p99, 1.8 ms at p99.9. The
optimized deltas (+72 fps, −7.6 ms p99, −10 ms p99.9, 30 → 8 frames over 33 ms) are far above
twice that. Both stock runs have the same cluster of 25–30 frames over 33 ms between 56 s and
90 s of the route (the W and N legs through the built-up area); the optimized run has eight
spread over the whole route. Chunk queue wait: stock 184 / 180 ms mean, optimized 31.5 ms.

| Run | machine CPU | busiest core | game process | main thread | GPU load | GPU W |
|---|---|---|---|---|---|---|
| stock 1 / 2 | 28 / 30 % | 74 / 72 % | 270 / 275 % of a core | 88 % of wall | 68 / 65 % (p90 99 / 100) | 127 / 120 |
| optimized | 26 % | 73 % | 270 % of a core | 93 % of wall | 52 % (p90 65) | 142 |

Same verdict as at zoom 2.0: the optimized build is bound by the game thread (93 % of wall)
with the GPU at half load and the machine at a quarter; stock spends more GPU per frame on the
per-frame tree/translucent pass and touches 100 % GPU in its p90 while producing 122 fps.

Against Linux native at zoom 2.5 and the 240 cap (route mean 6.2 → 4.4 ms, p99 19.3 → 8.3 ms):
Windows stock is slower (8.2 ms mean, 26.1 ms p99) and Windows optimized lands at 5.2 ms mean,
18.5 ms p99. The p99 gap to Linux (8.3 vs 18.5 ms) is the open Windows question; the driver
(616.92 vs the Linux 580 series), the 500 cap and the absence of MangoHud's frame pacing are
the candidates, and the 500-cap-vs-240-cap comparison is the first thing to run.

## What to bring back to Linux

- `console.txt` from the first optimized boot (the `[pzopt]` lines) and from a
  drive.
- The rough boot, load and fps numbers, optimized and stock.
- Screenshots of any artifact.
- `pzopt-frames.out` / `pzopt-chunks.out` if `instrument=true` was used.
- Whether the frame-cap combos and the menu cap worked.
