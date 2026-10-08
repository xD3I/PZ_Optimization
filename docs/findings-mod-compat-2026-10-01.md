# Mod compatibility: Java mod scan, Lua origin checks, Lua off the workers (2026-10-01)

Worktree `~/pzopt-wt/modcompat`, branch `mod-compat` (on d01445e). The maintainer asked for three of four compatibility
ideas: (2) detect conflicts at boot and switch off only the affected features, for Java and Lua mods; (3) keep a mod's
Lua off our worker threads; and a test matrix of real mods run with ours (idea 4).

## What was built

| Part | Key | What it does |
|---|---|---|
| `pzopt.ModCompat` + `scripts/override-methods.py` | `modCompat` auto / report / off | At `Config` init: reads the `-javaagent` jars and the jars of the mods `Zomboid/mods/default.txt` enables. Finds ZombieBuddy `@Patch(className, methodName)` and, in bytecode-rewriting classes, string constants naming a class we ship plus its methods. A patch of a method we edited switches off the boolean keys that method reads (build-time map, 606 edited methods, 303 with switches), below the player's options.ini, unless the mod is listed as tested (`KNOWN`, `Zomboid/pzopt/mod-compat.ini`). Report: `mod compat:` console lines, `Zomboid/pzopt/mod-compat.txt`, the Optimizations tab (reason per setting). |
| `pzopt.LuaOrigin` | none | Where a Lua function was loaded from (`prototype.filename`): the game's file, ours, `mod:<id>`. |
| `pzopt_ui_fast.lua` | `uiLuaFast` (dev A/B `devUiFastNoOrigin`) | Replaces the inventory functions only when they come from the game's own file. A mod that replaces the vanilla file at its path loads in the vanilla slot, before our file, and was taken for vanilla. |
| `pzopt.UiRetained` | `uiRetainedMods` (default off) | An element whose render path (`render*` / `prerender*` / `postrender*`, own table or class chain) is a mod's renders at the stock rate, and so does a window whose last fresh render drew one. |
| `pzopt.LuaGate` + LuaCaller override | `luaWorkerGate` (default on) | A Java -> Lua call made on a frame worker runs on the game thread: a void call in the update batch joins its Lua replay, others wait while the game thread runs them at its next task boundary / join (500 ms timeout = the old behaviour, counted). |
| `run.sh --mod` | none | Workshop mods are found by mod.info id and copied into Zomboid/mods for the run (a direct launch has no Steam). |

Unit tests: `ModCompatTest` (annotation and transformer detection, plain classes ignored, policy), `LuaGateTest` (worker
calls run on the batch's thread, results and exceptions returned). Bytecode audit: 0 mismatches with the new override.

## Test matrix (desktop, `--install opt`, Rosewood spin bench, 12-20 s routes)

| Run | Mods | Result |
|---|---|---|
| mc-base | none | `no Java mod patches a class we ship`, scan 24 ms; the error lines every run has (room metaIDs, fonts) are the baseline below. |
| mc-zbb | ZombieBuddy + Ultimate ZBetterFPS 1.4.9 | Scan lists 14 edited methods ZBBetterFPS patches and ZombieBuddy's own 7 hooks: known compatible, nothing switched, 163 ms. No new error. **But ZombieBuddy 2.3.3 loads no Java mod on 42.21** (`[ZBBetterFPS] can't find ZBBetterFPS class`): its `ZomboidFileSystem.loadMods` hook never fires. |
| mc-zb-stock | same, **stock install** (0 of our classes) | Same failure: ZombieBuddy's Java-mod loading is broken on 42.21 regardless of ours (run cancelled once the menu lines were in; the stock install has no auto-quit). |
| mc-lugli | ZombieBuddy + Lugli Optimizations (unknown) | Patches `LuaEventManager.triggerEvent`, `UIManager.updateUIElements`, `IsoChunkMap.calculateZExtentsForChunkMap` -> switched off `chunkMapFast`, `uiTickStagger` and the `entityUpdate*` / `emitterDefer` / `physicsDefer` family (the settings line confirms). Its jar does not load either (ZombieBuddy, above), so this checks the switching, not the two running together. |
| mc-pzmc | PZMulticore agent | Transformer found: of our edits only `IsoChunkMap.CalcChunkWidth` (no switch); class names of IsoChunk, IsoChunkMap, IsoMovingObject, PathFindBehavior2, WorldStreamer. Its 42.20 build on 42.21: one `Bucket sixteenthSimulation failed: ArrayIndexOutOfBounds 16384` inside its own dispatcher and `realZombieCount` missing; nothing of ours in the trace. |
| mc-luaui / -old | fixture + Inventory Tetris + TwisTonFire + Item Condition, `devUiRetainedCheck` | Old behaviour (`uiRetainedMods=true`): **18 stale replays of the mod-drawn HUD** (shown up to 140 ms late). New: 0. The first rule (any mod function in the class chain) left the whole UI at the stock rate (Tetris adds a helper to ISUIElement): 546 replay checks. |
| mc-luaui3 | same, rule narrowed to the render path | Only the elements a mod draws stay at the stock rate (Tetris' inventory page, drag renderer, side panel; Item Condition's hotbar; the fixture HUD); **11,783 replay checks, 0 stale of mod elements** (1 of the vanilla clock, the retained UI's known 1 s bound). `uiLuaFast` leaves both inventory functions to the mods. |
| mc-fixonly / -old | fixture alone (replaces ISInventoryPage.lua at the vanilla path, wraps `update` with a counter) | New: `page_updates` grows (350, 436, 514), `update` is the mod's. Old test (`devUiFastNoOrigin=true`): **`page_updates=0`, `update` is ours**: the mod's change was silently replaced. Vanilla UI replays normally in both (~23k). |
| mc-crop / -nogate | none, `entityUpdateParallel=true`, zombies at the crop field (start=6080,5338) | Gate on: `lua gate: pzopt-frame-1 reached Lua .../Farming/SFarmingSystem.lua; it runs on the game thread`, 7 calls replayed, 0 timeouts, **0 wrong-thread errors**. Gate off: **13 "Lua code called from the wrong thread"** from five workers (the stack corruption that turned the batching off on 09-28 is chance-driven and did not happen in this run). |
| mc-npc / mc-npc2 | Bandits + Project A-Life, `entityUpdateParallel=true` | 132,129 / 143,357 worker-fired Lua events (their `OnZombieUpdate` handlers) captured and replayed on the game thread, 0 wrong-thread errors, no gated direct calls. mc-npc had a Bandits script load error from the harness linking the Workshop folder (ZomboidFileSystem builds `mods/home/...` paths through a symlink); run.sh now copies, and mc-npc2 has no error beyond the base run. |

Workshop folder on Windows and macOS (fixed after the matrix): the scan looked for the Workshop downloads three folders above the game dir, which fits only the Linux depot (`.../steamapps/common/ProjectZomboid/projectzomboid`); on Windows (`...\steamapps\common\ProjectZomboid`) that is the library root and on macOS (`.../Project Zomboid.app/Contents/Java`) the game's install folder, so a Windows boot logged `no Java mod patches a class we ship` while the Workshop mod Viewpoint loaded (32 patched methods of classes we ship, 9 of them edited, by the offline scan of its jar). It now walks up to the nearest `steamapps` folder, which holds the game's Workshop content on every platform; the matrix did not see the bug because `run.sh --mod` copies the mods into `Zomboid/mods`, which the scan reads first.

Scan time with the Workshop found (Windows, 330 enabled mods, game running, warm): 2.65-2.72 s (3.6 s at a real boot), 88 % of it walking each enabled mod's whole tree for jars; now 159-166 ms, 358-373 ms when a jar is new or changed. A mod's jars are its `javaJarFile=` (resolved as ZombieBuddy does) plus a walk that enters only `java/` inside `media`, the mod folders are read on 8 threads, and each jar's hits are kept in `Zomboid/pzopt/mod-compat-cache.properties` (path, size and time; a new build or edited-method map empties it). All 472 mod folders give the same 6 jars as before.

## Open

- ZombieBuddy 2.3.3 cannot load Java mods on 42.21 (stock too). Until it can, ZombieBuddy mods are covered by the scan and
  the switching only; the run-together checks of ZBBetterFPS (2026-09-24, 42.20) stand as the last evidence.
- PZMulticore's 42.20 build needs a 42.21 rebuild on its side (`realZombieCount`, its bucket array).
- The scan reads `default.txt` (the boot's mod list). A save's own mod list adds Lua mods at load; ZombieBuddy loads Java
  at boot, so the Java side is complete; Lua-side checks run in game anyway.
- `uiRetainedMods=false` costs the retained UI's gain on mod-drawn elements only; elements a mod merely extends with
  helpers keep it.
