# Limitations and roadmap

[Back to the README](../../README.md) · [Known limitations](#known-limitations) · [Roadmap](#roadmap)

## Known limitations

- **Version pin.** One exact game build. Every Build 42 patch needs a new build of these
  classes; until then they disable themselves.
- **Other Java mods.** Only one mod can replace a given class: anything that also ships one of the
  [shadowed classes](how-it-works.md#how-the-install-works-without-touching-the-jar) conflicts, and whichever file is
  found first wins silently. Mods built on ZombieBuddy or Leaf patch methods and can coexist when they do not patch the
  same methods: since 2026-10-01 the build scans the launch's Java mods and switches off its own setting for every edited
  method a mod patches (`modCompat=auto`, [`findings-mod-compat-2026-10-01.md`](../findings-mod-compat-2026-10-01.md);
  the main menu's Mod compatibility check lists them). ZombieBuddy loads no Java mod on 42.21 at all, stock game too.
  The Lua-only performance mods [measured](benchmarks.md#against-the-other-performance-mods) are harmless but add
  nothing.
- **Multiplayer is lightly tested.** The files are client side: there is nothing to install on a
  server. Joining a community server used to fail with `File doesn't exist on the server:
  media/lua/shared/pzopt/pzopt_keybinding.lua` (the client lists every `media/lua` file to the
  server); since 2026-09-21 the `NetChecksum` override leaves the `pzopt/` Lua files out of that
  list (`luaChecksumExempt`), as the game does for `SandboxVars.lua`; verified against a stock
  dedicated server (join succeeds; `luaChecksumExempt=false` reproduces the refusal). Several shadowed classes
  (`IsoChunk`, `WorldStreamer`, `ChunkSaveWorker`, `IsoMetaGrid`) also run on a server, and
  hosting with the overrides installed has not been exercised. Do not install on a dedicated server.
- **Fog pass (experimental).** With `fogPass` on (the default) power lines can flicker
  slightly in heavy fog while the camera moves; frame captures do not show it, the maintainer
  does at 240 Hz. Untick "Fog drawn in one pass (experimental)" in PZ OPTIMIZATION > Optimizations > Lighting, cutaways and weather > Fog for stock fog.
- **Carport roofs.** The per-frame roof toggle of a detached carport with zombies around is
  debounced, not understood; `--prop devCutawayLog=true` logs every decision.
- **Four-core machines.** The recalc pool, the native lighting thread and the collector compete with the game thread
  for four cores; use one of the home page's **Low-end hardware** presets (render distance 9, one recalc worker), which
  hold a locked 60 walking on a 2015 i5 laptop ([benchmarks](benchmarks.md#old-4-core-laptop-a-locked-60)). A
  2,000-zombie horde stays out of reach there.
- **Security.** Build 42.20.4 removed Lua `loadstring` and restricted the file types Lua may
  write. This mod does not widen either: the `LuaCompiler` override only caches compiled
  prototypes of the same source text, and all writes stay under `Zomboid/pzopt/`.
- **Platforms.** Measured on Windows 11 with NVIDIA GL, on the native Linux depot with
  NVIDIA GL under XWayland (Mesa Zink and native Wayland too), on AMD (Radeon 890M, Mesa) and on macOS on an M1 Pro
  (OpenGL 4.1 core over Metal, [benchmarks](benchmarks.md#handheld-and-macbook)); AMD on Windows has had player reports
  fixed but is not measured here, and Proton is prepared but not measured. A long free-play soak (interiors, zombies
  behind fences, curtain and door state changes) is still open.
- **Steam performance monitor.** Caps the optimized game at ~160 fps by pinning the GL
  thread; keep it off (the classic overlay is fine).
- **Development install contents.** The build also carries the harness classes
  (`pzopt.Harness`, `pzopt.AutoStart`, `pzopt.Parity`, `pzopt.Stats`, `pzopt.ScriptDump`).
  They are inert unless the harness launches the game.

---

## Roadmap

Where the frame went at the last full survey (2026-09-21): on NVIDIA GL at max zoom and uncapped the desktop is GPU-bound
from about 450 fps (the composite of ~290 visible chunk-level textures, ~0.6 ms, and the
bakes, 0.3 to 0.5 ms) with the game thread at ~89 %; everywhere else (lower zoom, a smaller
screen, a slower card, Windows, the handheld) the single **game thread** is the limit, and
its remaining cost is broad: chunk texture bakes ~20 %, the world update ~23 % (character
update and animation the largest part, and everything in the Louisville horde), the Lua UI
~13 %, chunk streaming hand-off. No single hot spot is left worth a class override; what
moves the needle now is structural. Chunk-streamer latency is done (4 to 5 ms median).
Dates are not promised. Since then the zombie simulation moved onto the frame workers (Louisville 44 → 222 fps with
every optimization, [benchmarks](benchmarks.md)); what is left in a horde is the update itself on the game thread.

Open plans, each with its measured gate: [`plan-drive-game-thread.md`](../plan-drive-game-thread.md) (late frames while
driving through town), [`plan-zombie-multithread.md`](../plan-zombie-multithread.md) (the rest of the zombie
simulation), [`plan-louisville-120-structural.md`](../plan-louisville-120-structural.md) (a locked 120 in Louisville),
[`plan-graphics-enhancements.md`](../plan-graphics-enhancements.md), [`plan-game-load.md`](../plan-game-load.md),
[`plan-vulkan-renderer.md`](../plan-vulkan-renderer.md), [`plan-resource-use.md`](../plan-resource-use.md). The two
long-range items:

1. **Vulkan renderer, measured gate first** (`docs/plan-vulkan-renderer.md`): the inventory
   is done (LWJGL 3.4.1 without the `vulkan` module, ~1,800 direct GL call sites in 119
   files). Phase 0 is a native-frame profile of the render thread; the port only starts if
   driver plus swap is at least 20 % of the frame on NVIDIA GL (30 % on Zink), otherwise the
   effort goes to GL-level batching behind the same backend seam.
2. **Rust interop, standalone experiment**: whole passes (translucent list build, occluder
   scan, view-cone polygon, texture decode) through the Foreign Function & Memory API of the
   bundled Java 25, one at a time, each microbenchmarked against the JIT; a pass that does
   not beat Java by a clear margin is written up and dropped.

Not on the roadmap: lower render resolution, multiplayer, moving `IsoCell.render` to another
thread wholesale (GL context ownership), more chunk-streamer work.
