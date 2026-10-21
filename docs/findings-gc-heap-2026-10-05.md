# Java heap size and collector settings (2026-10-05)

New Optimizations-tab section "Java memory and garbage collector" (`pzopt.GcChoice`, applies at the next launch):
`gcHeap` (the launch's `-Xmx`: `auto`, the default since the afternoon = 4096 MB, or 8192 MB with `gcHeapAutoMods` (30) or
more enabled mods; `game` = the launcher's own, 3072 MB on the Steam depots; or a size in MB; never more than half the RAM
in 512 MB steps), `gcHeapFixed` (`-Xms` = `-Xmx`), `gcPreTouch` (`-XX:+AlwaysPreTouch`), plus the existing
`gcMode` and `gcPauseMs`, which were not on the tab before. `luaGcNoop` (default on) is the same section's Lua-side setting (below).

## How it is applied

`GcChoice.step()` (boot, AotCache's thread) rewrites `ProjectZomboid64.json` for the next launch: the effective (last)
`-Xmx` replaced in place, `-Xms` set or added, `-XX:+AlwaysPreTouch` added unless the player has it, and the marker
`-Dpzopt.heap=<old -Xmx>,<old -Xms>,<pre-touch added 0|1>` (`none` for an absent flag). Every undo reads the marker:
`GcChoice.undo` (in-game uninstall), `reset_gc` in `scripts/pzopt.sh` and `install.sh`, `Reset-Gc` in `install.ps1`
(PowerShell not run here: no pwsh on the desktop). Since 2026-10-19 (Workshop: players' own `-Xmx12g` reset to 3072m at
every launch, `docs/override-edits.md` "Java heap: the player's own -Xmx wins") the marker also records what was written
and the gcHeap it was for (`...,<-Xmx written>,<-Xms written>,<gcHeap>`): auto only raises the heap (an `-Xmx` at or above
its size stays), a flag changed by hand after our write is the player's and no launch or undo writes over it until gcHeap
is changed in the menu, and an undo restores the old value only where ours still stands. Harness runs never get the player's JSON edits; `run.sh` applies the
same rule to every optimized run (`-Xmx4096m`, `-Xmx8192m` with 30 `--mod` ids or more; `--prop gcHeap=game|<MB>`,
`gcHeapFixed=true`, `gcPreTouch=true`; not in a stock run, nor when the run passes its own `--vmarg -Xmx`), so harness runs
measure what players get. The mod count `auto` reads: `mod = ` lines of `Zomboid/mods/default.txt` and of the last save's
`mods.txt` (`latestSave.ini`), the larger, the harness's and pzopt's own left out; a changed mod list takes effect at the
launch after the first one with it.
Checks: `tests/pzopt/GcChoiceTest` (round trip, idempotence, a player's own pre-touch kept, the RAM clamp),
`tests/pzopt/UninstallTest`, `harness/gcchoice-check.sh` (a real launch with the three keys writes `-Xmx6144m -Xms6144m
-XX:+AlwaysPreTouch` + marker; `reset_gc` gives `-Xmx3072m` back): all pass.

## Benchmark

Desktop (9800X3D, 16 threads, 30 GB, RTX 4090, 5120x2160), optimized build, defaults (`-Dpzopt.userOptionsFile` =
an empty file), the heap / collector set per run with `--gc` and `--vmarg`. Table: `harness/gc-sweep.py gch-drive-
gch-spin- gch-lou-` (reps averaged; GC from every gc.log segment inside the route window). The drive ran at the VRR cap
(165 Hz display, `vrrCap` 157 fps), so there it shows GC only as tail spikes; the uncapped spin shows its cost in fps.

**120 km/h drive** (`--bench drive-120`, 2 runs each): every configuration 156.6-156.8 fps, p99 7.7-8.1 ms,
p99.9 9.1-11.7 ms, max 55-72 ms. G1 pauses in the 42 s route: 2-6, 13-38 ms in total, longest 11-17 ms; the max frames
are not GC. Heap in use peaked at 2.0-2.6 GB (G1) whatever the limit. No configuration is measurably better.

**Spinning route uncapped** (`--bench spin-uncapped`, 2 runs each):

| config | fps | p99 ms | G1 pauses in route (n / ms / max) | heap used / committed MB |
|---|---|---|---|---|
| G1 2 GB | 385.7 | 8.75 | 13 / 86 / 12.8 | 1998 / 2048 |
| G1 3 GB (launcher's own) | 400.8 | 8.28 | 6 / 65 / 16.9 | 2352 / 2929 |
| G1 4 GB | 397.2 | 8.43 | 6 / 57 / 16.4 | 2347 / 2902 |
| G1 4 GB fixed + pre-touch | 404.9 | 8.09 | 4 / 42 / 22.4 | 3264 / 4096 |
| G1 8 GB | 405.5 | 8.00 | 6 / 64 / 20.8 | 2859 / 3328 |
| ZGC 3 GB (the game's stock collector) | 387.5 | 8.46 | concurrent | 2885 / - |

2 GB costs ~4 % and ZGC ~3 %; 3 GB and up are within the run spread (~±2 %).

**Louisville horde** (`--bench louisville --record`, 3 runs each, 2 GB and ZGC 1; every run's settle frames and
`bake_counters` checked, none in the `see_all` flood state, none discarded). The horde's fps swings 53-90 between runs
of one configuration, so fps does not separate them; the collector log does:

| config | G1 pauses in route (n / ms / max) | heap used / committed MB | notes |
|---|---|---|---|
| G1 2 GB | 23 / 378 / **220** | 2047 / 2048 | a Full GC (G1 Compaction Pause, 205 ms) |
| G1 3 GB | 7 / 84 / 27 | 2835 / 3072 | heap 92-95 % full |
| G1 4 GB | 8 / 90 / 23 | 2710 / 3609 | |
| G1 6 GB | 6 / 69 / 26 | 2900 / 3938 | (2 runs) |
| G1 8 GB | 8 / 96 / 24 | 2850 / 3856 | |
| G1 4 GB fixed + pre-touch | 2 / 67 / 38 | 3364 / 4096 | fewer, longer young pauses (bigger young generation) |
| ZGC 3 GB | stalls | 3072 / - | 92-95 % full, back-to-back "High Usage" cycles, the game thread stalled 12.8 ms; 66.5 fps vs 72-90 on G1 |

## Reading

- The game's live set in a ~2,000-zombie horde is ~2.7-2.9 GB. The launcher's 3 GB holds it with ~5 % to spare:
  no Full GC in a 25 s route, but one step from it (2 GB shows what that step is: a 205 ms freeze). 4 GB and more give
  G1 room; above 4 GB nothing improves, G1 commits ~3.6-3.9 GB and stops growing.
- Driving and walking use 2-2.5 GB; the heap size changes nothing there.
- GC is 0.3-0.4 % of the wall time on every G1 configuration of 3 GB or more; it is not a frame-rate lever on this
  machine. The value of a bigger heap is the margin against the Full GC cliff (bigger hordes, mods, longer sessions).
- Fixed + pre-touch: fewer collections in the horde but its young pauses are the longest (38 ms); committed memory is
  the full 4 GB from boot. Neither better nor worse in the frame tail. `gcPauseMs=25` changed nothing (as on 2026-09-23).
- ZGC with the stock 3 GB falls behind in the horde (allocation stalls, -10-20 % fps); `gcMode=g1` stays right.

Default since the afternoon (maintainer's call): `gcHeap=auto` (below).

## With 132 Workshop mods: 4 GB vs 8 GB (2026-10-05 afternoon)

Mod set: the most-subscribed Build 42 Workshop items (7 pages of "Build 42", most subscribers), maps, spawn changers, NPC
mods, other optimizers, B41-only / abandoned items and translations left out, fetched with `steamcmd +login anonymous`
into `~/.cache/pzopt-workshop` (5.5 GB; `run.sh` searches it after Steam's library), mod list from
`harness/compat/workshop-modlist.py` (B42 folder, requirements first, declared incompatibilities out). Removed after
smoke runs: Common Sense (its `CS_recipes.txt` asks for fluid `Alcohol`, which 42.21 does not have: a script load error,
and B42 refuses to load a world with one, the "check console.txt" dialog) and Spongie's Character Customisation (opens
a modal Face / Details window at world start that holds the route). 132 mods: ~40 vehicle packs (KI5 and others),
clothing, hair, tiles, UI, crafting, traits, weapons. Boot to world ready ~22 s (10-11 without mods).

| run (G1, 132 mods) | n | fps | p99 ms | p99.9 ms | G1 pauses in route (n / ms / max) | Full GCs | heap used / committed MB |
|---|---|---|---|---|---|---|---|
| horde, 3 GB (the game's own) | 2 | 57.5 | 83.0 | 150 | 16 / 169 / 21 | every run: 140-150 ms in the load, 266 ms at the route's end (the quit save) | 3071 / 3072 |
| horde, 4 GB | 3 | 59.6 | 74.3 | 171 | 6.3 / 134 / 42 | 1 run in 3: 3 back to back at the end of the load (645 ms) + 1 in game (157 ms) | 4095 / 4096 |
| horde, 8 GB | 3 | 57.8 | 87.4 | 161 | 4.3 / 147 / 61 | none | 6196 / 7296 |
| drive 120 km/h, 4 GB | 2 | 155.8 | 8.9 | 29.3 | 4 / 24 / 11 | both runs at the end of the load: 5 in 2.5 s (1.1 s) and 1 (272 ms) | 4096 / 4096 |
| drive 120 km/h, 8 GB | 2 | 155.3 | 9.0 | 30.7 | 2.5 / 27 / 24 | none | 4467 / 5360 |

(Without mods the horde used ~2.8 GB and the drive 2.0-2.6 GB.) Inside the measured route 4 and 8 GB are the same
(fps, p99, p99.9 within the scene's run-to-run spread; the drive at the 157 fps VRR cap). The difference is the Full GC:
with this mod set the live heap at the end of the load is ~3.7-4.1 GB, so 4 GB fills and G1 stops the game for
150-280 ms per Full GC, 3-5 in a row, in 3 of 5 runs (mostly while the world appears, once in the horde); 8 GB never
did one in 5 runs. G1 grows into the room it has (8 GB: 4.2-6.2 GB used, 5.4-7.3 GB committed), so 8 GB costs ~1.5-3 GB
more RAM. Reading: 4 GB is enough for a vanilla game; a big mod list wants 6-8 GB.

One 8 GB horde run (r3) left the world mid-route: `Clothing.addRandomHole` threw a NullPointerException for a modded
clothing item without an item visual while `RBTrashed.trashHouse` rolled a new chunk's loot (stock code; our RBTrashed
edit only wraps the pass in the kidsRoomMemo begin / end). Discarded and re-run (r4). Item Condition and Barricaded World
throw Lua errors every frame / chunk on 42.21 (hundreds per run); they stayed in, as in a real mod list.

## `gcHeap=auto` and the other JVM parameters (2026-10-05 evening)

The rule: 4 GB, 8 GB from 30 enabled mods, at most half the RAM. Heap peak against mods on the 120 km/h drive at 4 GB
(runs `gchj-drv66-*`, `gchj-drv-base-*`, `gchm-drive-4g-*`): 66 mods 3.7 GB with no Full GC (2 runs), 132 mods full (4.1 GB)
with Full GCs in 2 of 4 runs; vanilla 2.0-2.6 GB, and the horde adds ~0.4 GB to the drive: ~20 MB a mod, so a horde game
fills 4 GB from ~30-40 mods. End to end (`harness/gcchoice-check.sh`, job `gchj-e2e`): `gc: running ... gcHeap=auto
(0 mods) -> 4096 MB`, the flags written with their marker, the uninstaller's `reset_gc` restoring `-Xmx3072m`.

Other JVM parameters, each against its baseline in the same batch (runs `gchj-*`; the 132-mod 4 GB drive is the Full GC
stress case, the uncapped spin the throughput case):

| parameter | uncapped spin, no mods (2 runs) | 132 mods, 4 GB drive (2 runs): Full GCs, heap | verdict |
|---|---|---|---|
| baseline (G1, auto heap) | 406.1 fps, p99 7.73, heap 2376 / 2878 MB | none in these 2 (2 of 4 with the earlier pair), 3439 / 4014 | |
| `-XX:+UseCompactObjectHeaders` (JDK 25) | 400.8 fps, p99 7.70, heap 2186 / 2580 (-8 %) | 1 run (244 ms), full | no fps gain, the heap saving does not stop the Full GCs; the AOT cache would have to be re-recorded. Not adopted |
| `-XX:+UseStringDeduplication` | - | both runs (2 + 2, 515 ms a run) | worse. Not adopted |
| `-XX:-G1UseAdaptiveIHOP -XX:InitiatingHeapOccupancyPercent=35 -XX:G1ReservePercent=15` | - | both runs (4 at load + 1 in game, 548 ms a run) | worse. Not adopted |
| `-XX:+ExplicitGCInvokesConcurrent` | with the collectgarbage fixture: 269-319 ms blocked per call (stock 118-138 ms) | - | worse: the calling game thread waits for the whole concurrent cycle. Not adopted |
| `-XX:+DisableExplicitGC` | not run | - | would also disable the JDK's own System.gc() when direct buffers run out (the game's textures are direct buffers): an out-of-memory risk. Not adopted |
| G1 region size, `-XX:MetaspaceSize` | - | - | not run: 2-6 humongous allocations and 2-3 metadata-threshold collections (all at boot) per run, nothing to win |

The explicit-GC fix is Lua-side instead: `luaGcNoop` (default on, `src/lua/shared/pzopt/pzopt_lua_gc.lua`) makes Lua's
`collectgarbage()` / `"collect"` / `"step"` return at once ("count" and the other options still reach the game's function),
installed from the game's own shared Lua before any mod's file runs. None of the 132 mods calls it, but "memory cleaner"
mods do, and Kahlua maps it to `System.gc()`. Fixture `harness/compat/make-gc-fixture.sh` (`--mod pzopt-gc-fixture`, a call
every 3 s): stock 118-158 ms a call (runs `gchj-gcfix-base`, `gchj-gcfix-noop` = the build before the Lua file shipped),
`luaGcNoop` 0 ms and no `System.gc` in gc.log, worst frame 139 -> 58 ms (run `gchj-gcfix-noop2`).

## Harness fixes found on the way

- `analyze.py`'s `gc:` line read only `gc.log`; a JVM started after the game (at exit) rolls the log, so the run's
  events were in `gc.log.2` and the line said 0 events. It now reads every segment (the route window keeps the
  game's own) and recognises JDK 25's `Using G1` line (it printed `gc (None)`).
- Every run of the sweep has `crashed=1` from that exit-time JVM (`hs_err`: `ArchiveHeapWriter::compute_ptrmap`
  guarantee, 1.4 s after it started): after the route, the numbers are unaffected; not investigated.
- Harness runs inherit the collector of whatever the game dir's JSON holds (ZGC on 10-04, G1 today): pass `--gc` for
  any run whose numbers depend on the collector.
