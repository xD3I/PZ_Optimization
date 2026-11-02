# KodeMannn's PZ-Mod-Performance-Suite v2.21.0: what it does, its "1-click" fixes measured, vs ours (2026-10-13)

https://github.com/KodeMannn/PZ-Mod-Performance-Suite (MIT, "coded with Google Gemini"): one ~4,200-line PowerShell
script (`Scan-PZModPerformance.ps1`, a `.bat` polyglot of the same for Windows, a `.sh` that downloads `pwsh` on Linux).
It is not a mod and changes no game code. Three parts:

1. **Static heuristics** over every enabled mod's Lua / scripts / textures: per-mod "spike" predictions (idle ms per
   frame, burst ms, "permanent hooks", "VRAM thrash" for big tile packs).
2. **Log telemetry**: parses `console.txt` for exceptions (and names a mod for each), Project Viewpoint's
   `[Viewpoint] fps | gpu ms` lines, "slow frame on the main thread" lines, ZGC / G1 lines and our
   `[pzopt] frame cap: game N fps` line.
3. **Interactive fixes**: `FixGC` (launcher JSON: ZGC -> G1, adds `-Dpzopt.gc=g1` (our marker), `-XX:MaxGCPauseMillis=5`,
   `-Xmx16g` on >= 28 GB RAM, 8g on >= 14 GB, else 4g; `.bak` + revert), `OptGraphics` (options.ini: `frameRate=120`,
   `modelTextureMipmaps=true`, `lightFPS=60`, `maxActiveRagdolls=10`, `textureCompression=true`), `CapFPS`, a savegame
   `mods.txt` sanitizer, a Discord / markdown report.

Rig: `~/src/kms-1013/` (`submit.sh <side> <bench>`, `table.py <prefix>`, `m132-ids.txt`); the clone, a portable pwsh 7.4.6
and the sandboxed scans (bwrap, read-only `/`, fake `$HOME`) in the session scratchpad.

## The scanner, run on this machine

- Read-only scans: real `~/Zomboid` ~2 s, the 132-mod Workshop set (10-05 GC sweep, fake profile) ~6 s; nothing written
  outside its report.
- **132 mods**: predicts +2.14 ms/frame idle, up to +10.21 ms in an active burst, 14 permanent hooks; tile packs
  (Simon MDs, Melos) "100-250 ms VRAM thrash", 40 vehicle mods ~10-25 ms each; Kill Count +0.6 ms idle, SOTO +0.5 ms.
  Measured below: the whole set costs the drive +0.3 ms a frame on stock and +0.08 ms on ours, Louisville +7 ms on ours.
- **Console attribution is wrong**: of the 365 exceptions it counted in a 132-mod console (98 missing vehicle templates,
  264 translation format errors) it blamed NeatLockpicking and ProjectViewpointNearVegetation, which are not in the set.
- Reads our `frame cap` line correctly ("Configured Frame Cap 244 (Active: 300)").
- Flags `lightFPS=10` as a HIGH "dissonance" and advises `lightFPS=60` (the stock default is 15, range 5-60): measured
  below, that advice costs frame rate.

### Its GC fix on Linux never applies

- Native Linux depot (`ProjectZomboid/projectzomboid/ProjectZomboid64.json`, our install): "ProjectZomboid64.json not
  found". Its Linux candidate paths do not include a non-default Steam library (`/games`) either.
- JSON at the install root: writes the `.bak`, then fails with "property 'vmArgs' cannot be found"; JSON unchanged.
- Windows layout (fake tree): works. G1 + `-Dpzopt.gc=g1` + `MaxGCPauseMillis=5` in both the `6.1` and `10.0.17134`
  sections, `-Xmx16g` at the top level. `Get-PZJvmStatus` reads every OS section regardless of platform, so its status
  line can describe a section the running OS does not use.

### It writes our marker, which our GcChoice then treats as ours

`-Dpzopt.gc=g1` is `pzopt.GcChoice.MARKER`. Checked with `GcChoice.toStock` / `toG1` on its Windows output (a small
interop class against `build/classes`):

- Our uninstall or `gcMode=stock` (`toStock`) turns **both** Windows sections to ZGC, including the Win 7 `6.1` section
  that is G1 in the stock JSON, and leaves its `-XX:MaxGCPauseMillis=5` behind (we only remove a pause flag we added,
  `MARKER_PAUSE`).
- With ours installed (`gcMode=g1`): G1 + its 5 ms target + our marker; our `gcHeap` replaces its 16g.

## Measured (desktop, 5120x2160, RTX 4090, direct launcher, vsync off)

Sides: `stock` (`--prop enabled=false`, ZGC 3 GB, `frameRate=244`), `kgc` (stock + its GC fix as it lands on Windows:
G1, `MaxGCPauseMillis=5`, `-Xmx16g`), `klight` (kgc + `lightFPS=60` only), `kall` (kgc + its graphics settings minus its
120 fps cap), `opt` (ours, defaults, uncapped), `optk` (ours + kall's settings). Every side an empty pzopt options file,
HDR / upscaler / vrrCap off. Frame tail from the in-game overlay over the route window; utilization from sysmon.

The first drive / spin batch (`kms-drive120-*`, `kms-spin-*`) left vsync on and sat at the 165 Hz desktop's refresh;
it is replaced by `kmsv-*` below. The Louisville runs never reach 165, so the `kms-louisville-*` batch stands.

| bench | side | fps | mean / p99 / p99.9 / max ms | > 33 ms | 1 % low | jitter ms | CPU / GPU % | game thread | lighting thread (of a core) | GC in the route |
|---|---|---|---|---|---|---|---|---|---|---|
| drive 120 | stock | 201.1 | 5.0 / 12.9 / 20.5 / 34.5 | 1 | 77 | 1.0 | 22 / 43 | 81 % | 15 % | ZGC 3 GB |
| drive 120 | kgc | 204.1 | 4.9 / 11.8 / 21.0 / 28.6 | 0 | 85 | 1.0 | 21 / 43 | 77 % | 14 % | G1: 9 pauses, max 7.1 ms |
| drive 120 | kall | 197.8 | 5.1 / 11.5 / 18.1 / 28.4 | 0 | 87 | 1.0 | 23 / 43 | 79 % | 44 % | G1: 10 pauses, max 8.0 ms |
| drive 120 | opt | **614.5** | 1.6 / 3.4 / 7.1 / 13.1 | 0 | 290 | 0.7 | 17 / 99 | 62 % | 13 % | ZGC 4 GB |
| drive 120 | optk | 601.6 | 1.7 / 3.5 / 7.1 / 16.5 | 0 | 288 | 0.7 | 20 / 99 | 61 % | 42 % | G1: 12 pauses, max 8.4 ms |
| spin | stock | 110.4 | 9.1 / 34.1 / 50.6 / 69.6 | 30 | 29 | 4.1 | 25 / 40 | 98 % | 20 % | ZGC 3 GB |
| spin | kgc | 131.7 | 7.6 / 28.8 / 41.6 / 57.9 | 15 | 35 | 3.2 | 27 / 42 | 96 % | 20 % | G1: 10 pauses, max 11.8 ms |
| spin | klight | 117.7 | 8.5 / 29.7 / 44.1 / 61.0 | 18 | 34 | 3.9 | 29 / 41 | 97 % | 52 % | G1: 12 pauses, max 12.0 ms |
| spin | kall | 97.8 | 10.2 / 36.1 / 49.4 / 68.2 | 41 | 28 | 4.8 | 28 / 38 | 98 % | 51 % | G1: 12 pauses, max 9.4 ms |
| spin | opt | **411.8** | 2.4 / 8.6 / 13.5 / 23.0 | 0 | 117 | 0.8 | 27 / 90 | 90 % | 18 % | ZGC 4 GB |
| spin | optk | 413.9 | 2.4 / 7.1 / 12.0 / 31.9 | 0 | 142 | 0.8 | 27 / 92 | 88 % | 50 % | G1: 11 pauses, max 9.1 ms |
| Louisville | stock | 23.8 | 42.0 / 116.7 / 157.6 / 180.6 | 338 | 9 | 15.1 | 29 / 30 | 98 % | 25 % | ZGC 3 GB |
| Louisville | kgc | 26.0 | 38.4 / 124.0 / 230.8 / 262.9 | 304 | 8 | 16.4 | 37 / 31 | 97 % | 27 % | G1: 11 pauses, max 14.2 ms |
| Louisville | kall | 26.2 | 38.1 / 98.1 / 165.7 / 219.7 | 353 | 10 | 11.9 | 31 / 32 | 97 % | 49 % | G1: 11 pauses, max 16.0 ms |
| Louisville | opt | **113.8** | 8.8 / 36.0 / 127.8 / 187.9 | 33 | 28 | 2.8 | 30 / 39 | 93 % | 23 % | ZGC 4 GB |
| Louisville | optk | 113.6 | 8.8 / 31.5 / 89.0 / 157.0 | 27 | 32 | 2.5 | 33 / 39 | 92 % | 58 % | G1: 11 pauses, max 10.1 ms |

Runs: `kmsv-{drive120,spin}-<side>-20261013-0613..0628`, `kms-louisville-<side>-20261013-0451..0458`. World ready: stock
sides 28-31 s, ours 8-9 s (`textureCompression=true` changed neither on NVIDIA). Jev: `achieved` for every run except
stock and kgc on the drive (`invalid`, confidence 0.16: ~200 fps with neither CPU nor GPU saturated, the headroom finding);
all five drives ended on the same square at the same second (`harness: drive t=` telemetry). Stock sides never saturate
anything: the game thread is the wall (77-98 %) while the machine sits at 21-37 % CPU / 30-43 % GPU. Ours is GPU-bound on
the drive (99 %) and the spin (90 %).

### 132 Workshop mods (the 10-05 GC sweep's set)

| bench | side | fps | mean / p99 / p99.9 / max ms | > 33 ms | CPU / GPU % | game thread | console ERROR lines | world ready s |
|---|---|---|---|---|---|---|---|---|
| drive 120 | stock | 187.4 | 5.3 / 15.4 / 23.2 / 37.1 | 2 | 22 / 42 | 88 % | 739 | 88 |
| drive 120 | opt | 584.9 | 1.7 / 4.9 / 7.3 / 17.5 | 0 | 18 / 95 | 78 % | 742 | 17 |
| Louisville | stock | OOM (below) | | | | | | 89 |
| Louisville | kgc | 24.2 | 41.3 / 158.4 / 329.8 / 369.0 | 313 | 23 / 28 | 96 % | 260 | 90 |
| Louisville | opt | 63.4 | 15.8 / 85.0 / 216.4 / 259.4 | 68 | 48 / 33 | 92 % | 4842 | 20 |

Runs `kmsv132-drive120-{stock,opt}-20261013-0629/0632`, `kms132-louisville-{stock,opt,kgc}-20261013-0500/0610/0638` (kgc settle frames checked: clean; the stuck job's
overrun notifications sit over the right of its recording). The ERROR counts are mod Lua (Item Condition's hotbar fails
once per drawn frame, so it scales with fps; Barricaded World's `loadGridsquare` also fails on stock), not ours.
What the mods cost, same side without -> with them: drive stock +0.3 ms mean (201 -> 187 fps, p99 12.9 -> 15.4), ours
+0.08 ms (615 -> 585, p99 3.4 -> 4.9); Louisville ours +7.0 ms mean (114 -> 63 fps). The scanner's +2.14 ms/frame idle is
7-25x the drive's cost; its +10.2 ms "active burst" is in the range of the horde's +7 ms, but nothing in its per-mod list
pointed at what costs there (no frame of the drive came near its 100-250 ms "VRAM thrash" for the tile packs: max 17.5 /
37.1 ms).

### Louisville runs checked

All six recorded Louisville runs (stock, kgc, kall, opt, optk, kms132 opt) pass the see_all check: settle-window frames
(world ready + 2 s, midway, route start - 1 s) brightened show uniform black, no pale patches or speckle; ours ran 113 fps,
far from the flooded 20-26. `pzopt-bench.out` now carries only `bakes=` in `bake_counters`, so the strongMarks check was
not available. optk alone shows one extra lit corner (wall + fence under a lamp) top left in the settle window: clean
geometry, not a flood.

### 132 mods: stock's 3 GB heap dies

`kms132-louisville-stock`: world ready 89 s after launch (30 s without mods); during the settle the game thread spent
93 % of wall time stopped (profile samples mean 325 ms, max 2.8 s), then `OutOfMemoryError: Java heap space` in Barricaded
World's `PlaceWindowsBaricades.lua` and "GameThread exited", with the process alive (the queue job hung an hour, cancelled;
the run has no numbers). Ours with the same mods (`gcHeap=auto` -> 8 GB at >= 30 mods): world ready 20 s, 63.4 fps.
This is the case its `-Xmx16g` is for: `kms132-louisville-kgc` (16 GB G1) survives, world ready 90 s, 24.2 fps, p99
158 ms, p99.9 330 ms (ours 63.4 fps, p99 85 ms). Its 10 G1 pauses max 17.6 ms, so the time goes elsewhere: the mods' Lua
on a stock game thread (96 %).

## Reading

- **Its GC fix is real on stock, small, and lands only on Windows.** The gain is stock ZGC's 3 GB heap (the 10-05 sweep:
  ZGC stalls at 3 GB) more than G1 itself: spin +19 % (110 -> 132 fps, p99 34 -> 29 ms), drive +1.5 %, Louisville +9 %
  with a worse tail (p99.9 158 -> 231 ms). The 5 ms pause target is never met (G1 pauses 7-16 ms) and does not move the
  tails. On ours it does nothing (spin 412 vs 414, drive 615 vs 602, Louisville 113.8 vs 113.6): ours already ships a
  bigger heap (`gcHeap=auto`) and the game thread is not where the GC hurts it. With 132 mods it is the difference between
  stock running and stock dying (Louisville 132 mods: stock OOM, kgc 24 fps); `gcHeap=auto` gives ours 8 GB there.
- **Its graphics optimizer costs frame rate.** `lightFPS=60` (stock 15) quadruples the native lighting thread's rate: the
  lighting thread goes 14-20 % -> 42-58 % of a core on every bench, and on the spin it takes back two thirds of the GC gain
  (kgc 132 -> klight 118 fps). The rest of the set (`modelTextureMipmaps`, `textureCompression`, `maxActiveRagdolls=10`)
  takes the other third: kall 98 fps, **below stock's 110**. On the drive kall is -1.6 % vs stock, Louisville +10 % vs stock
  (the GC part) with the p99 better and the max worse. On ours the set is neutral on fps (GPU-bound) and burns ~0.4 of a
  core more (process 240 -> 276 % drive, 358 -> 405 % spin). Its own scan flags the user's `lightFPS` below 60 as a HIGH
  problem, so its advice points the wrong way.
- **Ours vs theirs**: stock + all of its fixes vs ours on defaults: drive 198 vs 615 fps (3.1x, p99 11.5 vs 3.4 ms), spin
  98 vs 412 (4.2x, p99 36 vs 8.6 ms), Louisville 26 vs 114 (4.3x, p99 98 vs 36 ms, frames > 33 ms 353 vs 33), world ready
  30 vs 8-9 s; with 132 mods 88 vs 17 s. Ours saturates the GPU (90-99 %) where the frame rate is not pegged; stock + its
  fixes leaves 60 % of the GPU and 70-80 % of the CPU idle behind one busy game thread.
- **The scanner's predictions do not match measurements.** Its per-mod ms numbers are heuristics over file contents, not
  measurements: the 132-mod set costs a tenth of its idle prediction on the drive, and its exception attribution names
  mods that are not installed. Useful parts: it lists the console's exceptions, has a sanitizer for a save's
  `mods.txt`, and has a revert path for both of its edits.

### Interaction with ours, to fix on our side (proposed, not done)

- `GcChoice.toStock` should only undo a `-Dpzopt.gc=g1` it wrote itself (its recorded state), or at least leave a
  section that was G1 before (Win 7 `6.1`) as G1 and drop a foreign `-XX:MaxGCPauseMillis` it did not add; today a player
  who ran the suite and then our uninstaller gets ZGC on Win 7 and a stray 5 ms target.
- Its Linux candidate paths miss `projectzomboid/ProjectZomboid64.json` (the native depot) and non-default libraries,
  so a Linux player of both gets only its options.ini changes.
