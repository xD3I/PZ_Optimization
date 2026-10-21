# Results

Fresh start on 2026-09-24. Every earlier result (the old `results.md`, the findings, the baselines and the last
`benchmark-progress.html` snapshot) is in `docs/archive/2026-09-24/` and `harness/archive/2026-09-24/`; the run
directories behind them are in `harness/archive/2026-09-24/runs/` (local only).

The metrics of every run from here on are in Grafana (`harness/grafana/stack.sh up`, http://127.0.0.1:3000):
per-frame times, presented frames and GPU time, utilization, game-thread phases, chunk latency, GC, flips and
Jev's verdict. This file keeps the prose: what was run, why, and what it showed. Each entry names its runs, so
the dashboards "PZ run" / "PZ compare" show the numbers behind it, and states the frame tail (p99 / p99.9 /
spikes / jitter) and the utilization over the route window, as the objective requires.

## 2026-09-24 afternoon: sound engine pass (horde + thunderstorm + house and car alarms + gunfire + helicopter)

Scene `--preset louisville --flag weather=storm --flag house_alarm=12 --flag car_alarm=8 --flag gunshots=2|8
[--flag gunshot_weapon=Base.AssaultRifle] --flag helicopter=true --flag route=S:25 --flag speed=1 --route-seconds 25
--record --record-audio game --asprof event=cpu,interval=5ms,threads --prop uncappedFps=true`, desktop, 5120x2160,
NVIDIA GL, zoom 2.5, direct launch; stock = the same build with `--prop enabled=false`. Details and the audio verdicts:
`findings-sound-2026-09-24.md`.

| Run | fps | p50 / p99 / p99.9 ms | > 33 ms | jitter | game / render thread | GPU | sound code (game thread) | clipped samples | Jev audio |
|---|---|---|---|---|---|---|---|---|---|
| `snd-i4-stock-20260924-155836` (pistol) | 32.7 | 25.5 / 119.2 / 126.4 | 131 | 8.4 ms | 93 % / 52 % | 53 % | 1.3 ms / frame | 6,161 | distortion |
| `snd-i4-opt-20260924-155716` (pistol) | 115.6 | 7.7 / 28.6 / 47.8 | 16 | 2.4 ms | 97 % / 39 % | 51 % | 0.24 ms / frame | 0 | clean 0.96 |
| `snd-i6-stress-stock2-20260924-161256` (rifle, 8 shots/s) | 30.3 | 27.2 / 123.6 / 137.3 | 174 | 9.4 ms | 93 % / 51 % | 53 % | 1.38 ms / frame | 19,113 | distortion |
| `snd-i6-stress-opt-20260924-160738` (rifle, 8 shots/s) | 118.1 | 7.7 / 25.5 / 65.5 | 17 | 2.3 ms | 96 % / 40 % | 53 % | 0.28 ms / frame | 0 | clean 0.96 |

Both builds stay game-thread bound (93-97 % of a core) with the GPU half busy: below the cap with the GPU left
over, the horde's simulation is the wall, not sound. Sound code was 5.3 % of the optimized game thread before the pass
(`snd-i1-off-20260924-150826`) and is 2.7-2.9 % after (`emitterIdleSkip`: `snd-i1-on`; `soundTickHz`: `snd-i5-tick0`
vs `snd-i5-tick60`). FMOD's own threads: 0.10-0.12 of a core in every run.

## 2026-09-24 evening: render distance upstairs (`chunkGridFollowView`)

The maintainer's report: with a render distance above vanilla the screen corners are filled at the widest zoom on the
ground, but "on a higher z-level it reverts to the vanilla distance". Cause: the camera centres on the player with the
height in it, so the ground under the screen centre is 3 tiles north and 3 west of the player per level, while the
grid stays centred on the player (details: `override-edits.md`, IsoChunkMap, edit of 2026-09-24). Rig: bench mode,
`--flag start=12450,1280 --flag upstairs=4 --flag upstairs_roof=true --flag zoom=max --flag weather=clear --flag
zombies=off --shot-at 8 --prop chunkGridWidth=auto`, desktop 5120x2160, NVIDIA GL, zoom 2.5, direct launch; the
`harness: grid coverage:` console line (new) gives, for each screen corner, how many tiles its level-0 ground lies
outside the grid.

| Run | chunkGridFollowView | grid centre chunk | TL / TR / BL / BR outside (tiles) | black px in the TL wedge |
|---|---|---|---|---|
| `gridz-cov4-follow-false-20260924-171020` | false | 1556,170 | 7.1 / 7.1 / 0 / 0 | 9 % |
| `gridz-cov4-follow-true-20260924-171112` | true | 1554,168 | 0 / 0 / 0 / 0 | 1 % |

Player on a flat roof at 12450,1361, level 4, grid 25 (stock 19). The wedge is mostly covered by the sidebar and a
roof drawn up from inside the grid, so the picture shows little at level 4; the numbers grow by 3 tiles per level.
The top-right corner is dark in both shots from the view cone, not the grid. No frame-time reading: screenshot runs
(the save's leftover 60 fps cap). Discarded: `gridz-lv6-*` (no level-6 room near the start, player stayed indoors on
the ground floor), `gridz-roof4-*` (no coverage line yet).
Release check on origin/master 2456dbb + the change (`gridz-rel-cov4-20260924-172608`, follow view on by default):
centre chunk 1554,168, corners 0 / 0 / 0 / 0, no exceptions.

## 2026-10-03: the 42.20 -> 42.21 drive regression (branch `perf-4221-regression`)

Runs `r4221-*` (desktop, 5120x2160, uncapped, empty tab file, `instrument=true`). Daily drive E:1200 at kmh=193, max zoom:
tree fixes off (the 42.21 port as released) 326 / 320 fps, p99 9.2-9.3 ms; on (`treeRebakeLazy`, `treeCutawayReach`,
and the since-removed `treeCutawayScissor`) 428-438 fps, p99 6.3-6.4 ms, bakes 31.5k -> 21.6k; `driveTreeCutaway=false` (42.20's tree rule)
507 / 514 fps, the 42.20.4 daily level (471-503). Storm spin on foot unchanged (271.6 vs 271.8). Proofs: `devReachCheck`
0 inside-cutaway samples from trees the reach rule keeps baked (47,720 / 35,502 probes at margin 0 / 256; control 786),
`devEdgeFastCheck` 0 of 23.9 M. Details: `docs/findings-4221-drive-regression-2026-10-03.md`.
Mac (M1 Pro, same drive, `mac-r4221-*`): released 10-02 build 118 fps, branch with fixes off 96-119, on 137-152 (p99 ~22 ->
17 ms), 42.20 rule 175; reach proof 0 violations (31,012 probes, control 444).
Hotfix (scissor removed, the cutaway draws again at every zoom; `r4221-noscis-*`, `r4221-hf-*`, `r4221-ab-*`): optimized path
2,219 trees with inside-cutaway samples (control), 0 lost by the reach rule (109,103 probes). Daily drive interleaved: passes
skipped 490 / 534 fps, drawn 504 / 498 (~2 %, inside the spread); `driveTreeCutaway=true` 405 fps.

## 2026-10-06: stock vs every optimization, Louisville horde and the Riverside horde shootout (release bc1f75d)

The last comparison after the Louisville 120 pass, on the desktop (9800X3D, RTX 4090, 5120x2160), build `bc1f75d`.
Stock = `--prop enabled=false --option frameRate=240 --option uncappedFPS=false` (stock has no uncapped mode; it reached
the cap in about 10 % of frames at most). Every optimization = the shipped defaults plus every off-by-default
performance key: `entityUpdateParallel zombieSimLodTiles=10 zombieSimLodSteps=4 zombieLodDynamic zombieLodMin3d=32
slackWork lootDefer zombieSpawnSpread zombieModelAddBudgetUs=500 animalLosSnapshot postupdateParallel zombieReuseSpread
tileRecordParallel`, uncapped. Both sides `tieredZombieUpdates=true` (the player default) and an empty options file.
Louisville = the daily `dlou` scene (population max, spinning walk, 25 s route); Riverside = the `horde-shoot` /
`dhorde` scene (Jev shoots a horde of 150, 60 s). No crash in any run.

| scene | run | fps | p99 | p99.9 | 1%-low | GPU | game thread |
|---|---|---|---|---|---|---|---|
| Louisville | stock (fin-lou-stock1 / 2) | 36.2 / 35.0 | 67.8 / 74.1 ms | 102 / 121 ms | 12 / 11 | 36 / 35 % | 97 / 96 % |
| Louisville | every optimization (fin-lou-all1 / 2) | 155.3 / 153.6 | 19.2 / 18.5 ms | 30.0 / 31.0 ms | 50 / 52 | 55 / 53 % | 91 / 91 % |
| Riverside shootout | stock (fin-horde-stock1 / 2) | 137.5 / 130.1 | 15.4 / 19.0 ms | 27.6 / 29.0 ms | 62 / 51 | 49 / 50 % | 98 / 98 % |
| Riverside shootout | every optimization (fin-horde-all1 / 2 / 3) | 308.3 / 243.8 / 275.1 | 6.5 / 9.3 / 7.5 ms | 11.5 / 13.5 / 11.9 ms | 148 / 104 / 129 | 93 / 84 / 86 % | 86 / 88 / 87 % |

Louisville: about 36 -> 154 fps (4.3x), p99 71 -> 19 ms. Stock is game-thread bound with the GPU a third busy; with
every optimization the game thread is still the wall (91 %) at half the GPU. Riverside: about 134 -> 292 fps (2.2x,
runs 1 and 3), p99 17 -> 7 ms; optimized it is GPU-bound (86-93 %). Run 2 (244 fps) had the machine's CPU at 51 %
against 38-40 % in the others with the game's own CPU unchanged (something else was running), so it was redone (run 3);
the director plays each run differently, so the shootout varies more than the walk.

## 2026-10-09 00:00 — frame-time spikes, short drive and spin (`docs/findings-frame-spikes-2026-10-09.md`)

Desktop, 5120x2160 at **165 Hz with VRR** (no longer 240 Hz), NVIDIA GL, direct launcher, `--option frameRate=240`, zoom 2.5.
Drive = the first 487 tiles of `drive-120-south` (25 s), spin = the spin route with `--route-seconds 15`. "tab" = the
desktop's own options file (nearly every Enhancement on, HDR on, `vrrCap=false`); "def" = an empty options file (VRR cap 157).

| run | settings | fps | p99 | p99.9 | max | frames > 20 ms | GPU | game / render thread |
|---|---|---|---|---|---|---|---|---|
| spk-drive-20261008-235836 | tab, asprof + schedmon | 152 | 21.1 ms | 26.8 ms | 144 ms | 53 | 74 % | 74 / 92 % |
| spk-spin-20261008-235941 | tab, asprof + schedmon | 123 | 21.9 ms | 30.1 ms | 428 ms | 49 | 63 % | 85 / 87 % |
| spk-spin-tab-20261009-000606 | tab | 130 | 20.8 ms | 30.8 ms | 36.5 ms | 35 | 85 % | 86 / 88 % |
| spk-spin-nohdr-20261009-000708 | tab, `hdr=false hdrAuto=false` | 110 | 24.7 ms | 40.9 ms | 158 ms | 105 | 62 % | 94 / 55 % |
| spk-spin-def-20261009-000810 | defaults | 153 (cap 157) | 11.7 ms | 20.4 ms | 57.6 ms | 5 | 46 % | 68 / 39 % |

The 144 / 428 ms frames are shaders compiled on the render thread mid-play (a foliage-sway twin of a pixelLight variant; the
patched `vehicle` program for a burnt-car story's first car). The 20-35 ms frames are bake bursts with both threads already
near full every frame with the Enhancements set; with the defaults the same bursts fit.

Highway, same setup (`drive-120` path cut to 600 tiles, 24 s; findings §3):

| run | settings | fps | p99 | p99.9 | max | frames > 20 ms | GPU | game / render thread |
|---|---|---|---|---|---|---|---|---|
| spk-hwy-20261009-005503 | tab, asprof + schedmon | 185 | 14.9 ms | 23.4 ms | 32.1 ms | 9 | 81 % | 72 / 91 % |
| spk-hwy-def-20261009-005609 | defaults | 157 (cap 157) | 7.7 ms | 13.4 ms | 64.1 ms | 2 | 40 % | 36 / 29 % |

The max of both is `CutawayMask.read` (the cutaway mask PNG decoded on the game thread the first time a drive's cutaway
stencil exists, ~1.5 s into the route).

Uncapped (`uncappedFps=true vrr=off`, no profiler), tab file vs defaults, runs `unc-*` 2026-10-09 01:15-01:23 (findings §4):

| route | tab fps / p99 / p99.9 / >20 ms | defaults fps / p99 / p99.9 / >20 ms |
|---|---|---|
| highway 600 tiles | 202 / 14.4 / 21.2 / 10 | 569 / 4.8 / 8.8 / 2 |
| Rosewood 487 tiles | 147 / 23.1 / 31.6 / 112 | 457 / 11.2 / 17.2 / 6 |
| spin 15 s | 121 / 21.5 / 29.7 / 45 | 349 / 9.6 / 16.1 / 5 |

## 2026-10-11 00:40-01:05 — Windows, uncapped: optimizations vs + enhancements (release fd0b693, desktop Windows boot)

Desktop's Windows 11 side (RTX 4090, 5120x2160, NVIDIA 617.42), `harness/run-win.ps1` (Steam launch, in-game overlay log +
sysmon), one run per cell, zoom 2.5 in every run, `uncappedFps=true`, `uiRenderOffscreen=true`, the player's tab file pinned
off (`upscaler=off overlay=false hdr=false hdrAuto=false dynRes=false`). **Heap: the launcher's stock 3 GB ZGC** — the release's
GC / heap writers cannot rewrite the JSON on Windows (fixed in fef2c5b, not released), so not comparable to Linux runs (4 GB).
Configs: **opt** = the defaults; **enh** = + every Enhancements feature but the resolution / output ones (`sunShadows pixelLight
relief foliageSway godRays ambientOcclusion reflections mirrors carGlass torchSource ripples bloodWet colorGrading memoryTint
occludedZombieOutlines` on, `spriteFilter=sharp darknessFloorPct=20`); **enh2** = enh without remembered places
(`memoryTint`) and zombie outlines (`occludedZombieOutlines`). Runs `win-unc-<scene>-<config>-20261011-*`.

| scene | config | fps | p50 | p99 | p99.9 | > 33 ms | 1%-low | GPU | game / render thread |
|---|---|---|---|---|---|---|---|---|---|
| spin south (`route=S:450 turn=90`, 25 s) | opt | 179 | 4.5 ms | 18.5 ms | 32.5 ms | 3 | 54 | 77 % | 83 / 84 % |
| | enh | 68 | 13.3 ms | 38.1 ms | 61.0 ms | 37 | 26 | 59 % | 82 / 67 % |
| | enh2 | 77 | 11.9 ms | 33.8 ms | 42.0 ms | 20 | 30 | 59 % | 84 / 67 % |
| Louisville horde (preset flags, 25 s) | opt | 36.6 | 24.0 ms | 83.2 ms | 148.9 ms | 153 | 12 | 38 % | 89 / 37 % |
| | enh | 30.7 | 28.4 ms | 84.0 ms | 139.0 ms | 253 | 12 | 39 % | 87 / 42 % |
| | enh2 | 29.5 | 29.1 ms | 98.7 ms | 321.5 ms | 253 | 10 | 41 % | 83 / 41 % |
| 120 km/h east (`drive-120` path) | opt | 427 | 2.2 ms | 5.4 ms | 12.6 ms | 0 | 184 | 89 % | 63 / 95 % |
| | enh | 207 | 4.1 ms | 14.7 ms | 20.4 ms | 0 | 68 | 89 % | 71 / 90 % |
| | enh2 | 201 | 4.3 ms | 15.0 ms | 22.8 ms | 1 | 67 | 89 % | 73 / 90 % |
| 120 km/h Rosewood south (`drive-120-south` path) | opt | 356 | 2.0 ms | 14.3 ms | 21.0 ms | 1 | 70 | 84 % | 74 / 90 % |
| | enh | 163 | 3.9 ms | 34.9 ms | 45.1 ms | 78 | 29 | 78 % | 75 / 81 % |
| | enh2 | 167 | 3.8 ms | 31.2 ms | 39.4 ms | 34 | 32 | 78 % | 76 / 81 % |

- Louisville: every run checked (CLAUDE.md rule): settle-window frame (`devCapture=8,1,1,50`, no `--record` on Windows) clean, no
  pale patches / speckle / chunk holes; `strongMarks` 7,701 (opt) and 0 (enh / enh2: pixelLight bakes unlit), not a flood. None
  discarded. Both enhancement runs ran out of the 3 GB heap in the exit save **after** the route (`OutOfMemoryError` after
  `route done`; the harness forced the exit); enh2 had 4,632 zombies loaded at route start, its 721 ms max frame is likely the full heap.
- With enhancements nothing is saturated on the spin (GPU 59 %, game thread 82-84 % of a core, render thread 67 %) at 68-77 fps: the
  objective's "below 240 fps and hardware not saturated" finding. The drives stay GPU-bound (78-89 %). The Windows enhancement
  cost (spin 5.6 -> 13.0-14.7 ms a frame, east drive 2.3 -> 4.8-5.0 ms) is far above the 2026-10-08 Linux sweep's ~0.5 ms total
  (`docs/findings-enhancement-costs-2026-10-08.md`): not yet explained (Windows driver, heap, the drives' streaming).
- Remembered places + zombie outlines off (enh2) is +13 % on the spin and tighter tails on the Rosewood drive, inside one-run noise
  on the highway and Louisville.
- For context, Linux uncapped defaults 2026-10-09 (runs `unc-*`, other routes' lengths): highway 569, Rosewood 457, spin 349 fps.

## 2026-10-11 01:41-02:05 — the same Windows set on G1 + 4 GB heap (launcher JSON set by hand)

Same build, routes and configs as the section above; the launcher JSON edited by hand to what the fixed GcChoice writes
(`-Xmx4096m` + `-Dpzopt.heap=3072m,none,0`, G1 + `-Dpzopt.gc=g1` in the `windows` / `10.0.17134` section; each run dir's
`ProjectZomboid64.json` copy checked), original kept at `C:\pz\ProjectZomboid64.json.before-4g`. Runs `win-g1-<scene>-<config>-20261011-*`,
one each. Every route complete (drives 1198.9 / 1200 and 851.6 / 852.7 tiles), zoom 2.5, **no OutOfMemoryError** (the 3 GB runs had two),
Louisville settle frames clean and `strongMarks` 7,886 / 0 / 0: none discarded.

| scene | config | fps (3 GB ZGC run) | p50 | p99 | p99.9 | > 33 ms | 1%-low | GPU | game / render thread |
|---|---|---|---|---|---|---|---|---|---|
| spin south | opt | **190** (179) | 4.4 ms | 17.2 ms | 34.8 ms | 7 | 58 | 84 % | 77 / 88 % |
| | enh | **91** (68) | 9.6 ms | 28.6 ms | 35.4 ms | 8 | 35 | 71 % | 83 / 77 % |
| | enh2 | **93** (77) | 9.3 ms | 29.7 ms | 36.0 ms | 9 | 34 | 72 % | 80 / 80 % |
| Louisville horde | opt | **44.1** (36.6) | 19.9 ms | 72.3 ms | 132.6 ms | 98 | 14 | 40 % | 88 / 43 % |
| | enh | **35.2** (30.7) | 25.0 ms | 85.3 ms | 134.4 ms | 160 | 12 | 45 % | 83 / 49 % |
| | enh2 | **35.0** (29.5) | 25.3 ms | 88.5 ms | 132.5 ms | 146 | 11 | 44 % | 83 / 48 % |
| 120 km/h east | opt | **450** (427) | 2.0 ms | 4.8 ms | 11.0 ms | 0 | 209 | 90 % | 62 / 94 % |
| | enh | **212** (207) | 4.1 ms | 14.1 ms | 21.6 ms | 2 | 71 | 91 % | 67 / 92 % |
| | enh2 | **216** (201) | 4.1 ms | 13.1 ms | 23.7 ms | 0 | 76 | 91 % | 69 / 92 % |
| 120 km/h Rosewood south | opt | **341** (356) | 2.1 ms | 13.4 ms | 21.2 ms | 1 | 75 | 87 % | 71 / 93 % |
| | enh | **177** (163) | 3.8 ms | 29.0 ms | 39.2 ms | 21 | 35 | 83 % | 73 / 86 % |
| | enh2 | **176** (167) | 3.9 ms | 27.8 ms | 35.8 ms | 14 | 36 | 84 % | 73 / 87 % |

- G1 + 4 GB is faster or equal in 11 of 12 cells: Louisville +17-20 %, the spin +6 % (opt) / +20-34 % (enh), the east drive
  +1-8 %; the Rosewood drive opt -4 % is inside one-run noise. Tails tighter almost everywhere (Louisville p99.9 139-322 -> 133-134 ms,
  spin enh p99 38 -> 29 ms), and no out-of-memory exits. Windows players get this once the JSON fix (fef2c5b) ships.
- Remembered places + zombie outlines off (enh2) is within one-run noise of enh on every scene with the bigger heap; the +13 %
  spin gain in the 3 GB set was heap pressure.
- Still: with every enhancement on, the spin runs at 91-93 fps with nothing saturated (GPU 71-72 %, game thread 80-83 %, render
  thread 77-80 %); the drives stay GPU-bound (83-91 %). The Windows enhancement cost (spin 5.3 -> 10.7 ms a frame) remains well
  above the Linux sweep's.

## 2026-10-11 02:19-05:23 — Windows after a restart: stock baselines, the Linux comparison, and the short-wait fix

Same desktop Windows boot, G1 + 4 GB launcher (by hand) unless noted, uncapped, zoom 2.5, `run-win.ps1` (Steam launch).

**Rerun after the restart** (`win-g1r-*`, 02:19-02:46): within 1-5 % of the 01:41 set in every cell (spin 195 / 93 / 96 fps,
Louisville 44.1 / 35.9 / 37.2, east drive 445 / 216 / 219, Rosewood south 351 / 185 / 186 for opt / enh / enh2); no OOM;
Louisville settle frames clean, `strongMarks` 7,729 / 0 / 0.

**Stock** (`win-stock3-*`, 03:16-03:24; `enabled=false`, the game's own launcher JSON: ZGC + `-Xmx3072m`, cap 244 because
stock `Core.loadOptions` turns a saved `uncappedFPS=true` into a 60 lock: `win-stock-*` / `win-stock2-*` ran at 60 and are void;
`framecap.ini`'s `restore=60,false,0` line taken out for the runs and put back):

| scene | stock fps | p99 | opt (G1 4 GB) | x |
|---|---|---|---|---|
| spin south | 71.6 | 38.3 ms | 195 | 2.7 |
| Louisville | 33.2 | 82.8 ms | 44.1 | 1.33 |
| 120 km/h east | 126.7 | 16.9 ms | 445 | 3.5 |
| 120 km/h Rosewood south | 115.9 | 53.6 ms | 351 | 3.0 |

**Against Linux.** Still camera of `docs/findings-enhancement-costs-2026-10-08.md` (bench square, turning 180°/s, `win-still-*`):
all off 1.80 ms (544 / 550 fps; Linux 1.14, 754), all on 2.65 ms (372 / 379; Linux 1.68, 543): the set costs +0.85 ms here vs
+0.54; the live invert sweep (`win-still-invert`) has no single key over 0.12 ms (noise 0.08 median), launch-only keys from
all-on: pixelLight + relief 0.13 ms, mirrors 0.12, car glass 0.08, reflections 0. Per Enhancement on the full Rosewood drive (the
method of `docs/findings-frame-spikes-2026-10-09.md` §10, `win-feat-<f>-a1/a2`, 2 runs each, +ms on the defaults' 2.89 ms;
Linux 1.67): **pixelLight +0.96 (Linux +0.28)**, sun shadows +0.68 (0.59), HDR +0.48 (0.57), sway +0.28 (0.14), AO +0.17 (0.09),
car glass +0.15 (0.23), god rays +0.14 (<= 0.04), mirrors +0.12 (0.07), reflections +0.08 (0.06), the rest <= 0.03.

**Cause of the Windows excess: the joins' 20 µs parks.** With pixelLight the GPU fell 87 -> 77 % and the render thread 93 -> 83 %
(nothing saturated); the game thread had `PixelLight.beforeComposite` at 20 %, half of it waiting in `FrameBatch.run` (JFR /
the overlay profiler, `win-jfr-*`). FrameBatch's join (and CharDraw's, LuaGate's, VisPolyAsync's, AnimParallel's) waits with
`LockSupport.parkNanos(5..20 us)` after its spin, and HotSpot's Windows park rounds any timeout under 1 ms up to 1 ms. New
`pzopt.ShortWait` (`shortWait` auto = yield on Windows, park elsewhere). A/B on the same build (`win-sw-*`, 2 runs each):

| Rosewood drive | shortWait=park (released) | yield | |
|---|---|---|---|
| defaults | 342.6 fps (2.92 ms), p99 13.8 | 352.9 (2.83 ms), p99 12.3 | -0.09 ms |
| pixelLight | 258.5 (3.87 ms), p99 18.3, GPU 78 % | 289.9 (3.45 ms), p99 15.5, GPU 87 % | -0.42 ms |

With the fix (`win-feat-<f>-b1/b2`, 2 runs each, defaults 2.86 ms): god rays -0.01, mirrors +0.06, AO +0.08 (Linux 0.09),
sway +0.26 (0.14), pixelLight +0.51 (0.28), sun shadows +0.66 (0.59), **every Enhancement +2.22 ms (Linux +2.09)**. Spin and
Louisville with the fix (`win-g1sw-*`, one each): spin 201.5 (195) / with Enhancements **112.9 (93, +21 %)**, Louisville 42.9
(44.1) / 39.0 (35.9). What is left above Linux: pixelLight +0.23 ms (its doubled bakes on a render thread already at 93-95 %)
and sway +0.12 (program switches); the bigger gap is the base itself, 2.86 vs 1.67 ms with the render thread at 93-95 % in the
defaults (Linux ~1.3 ms of render thread a frame): the Windows GL driver's per-call cost, not an Enhancement.


## UI latency pass (2026-10-14, worktree `ui-latency`, `docs/findings-ui-latency-2026-10-14.md`)

Desktop, bench save standing still, 240 cap (vsync / VRR off, 165 Hz panel), `ui_script=latency` with a 200-item stack,
empty options file. Ground truth `devUiTruth` (stale = a visible UI element differs from rendering it fresh that frame).

| scene | stale frames: stock 60 Hz / previous / new / new without display cap | UI % of frame: stock / previous / new |
|---|---|---|
| hover over inventory rows | 23 / 25 / 8.7 / 0.0 | 10.0 / 7.6 / 11.5 |
| hover over a 200-item stack | 28 / 29 / 10.3 / 0.0 | 9.1 / 6.1 / 10.0 |
| scrolling the 200-item stack | 56 / 58 / 22 / 0.0 | 7.6 / 8.2 / 15.1 |
| dragging an item | 62 / 62 / 26 / 0.1 | 9.0 / 6.4 / 16.6 |
| dragging a window | 61 / 62 / 24 / 0.0 | 8.7 / 6.8 / 9.8 |
| inventory items changing | 8.7 / 43 / 2.7 / 0.4 | 8.1 / 3.0 / 3.9 |
| skills panel, XP arriving | 1.0 / 61 / 0.3 / 0.7 | 7.2 / 1.2 / 4.4 |
| 200-item stack, renames | 0.2 / 19 / 0.0 / 0.1 | 7.3 / 1.2 / 1.9 |
| HUD only | 0.1 / 0.1 / 0.4 / 0.0 | 2.7 / 1.2 / 2.3 |

240 fps held in every scene; uncapped ~-1 % fps. Runs: `uil13-cost-new`, `uil13-cost-prev`, `uil6-cost-stock`,
`uil12-truth-new`, `uil12-truth-nocap`, `uil7-truth-prev`, `uil11-unc-prev`, `uil11-unc-new` (harness/runs of the worktree).

## 2026-10-18 16:20-16:25 — KI5 LAV-300 flicker report, single-player repro (`docs/findings-ki5-vehicle-flicker-2026-10-18.md`)

Desktop, direct launch, `--mode bench --mod damnlib --mod 84gageV300`, LAV-300 APC + vanilla Van parked by the car rig at
8158,11514, 14 s hold, recorded. `ki5-lav-repro2` (tab file: car glass and sun shadows off): steady, LAV / Van frame-to-frame
luma diff 0.30 / 0.30. `ki5-lav-allenh` (rain, every Visuals key on): steady, 0.69 / 0.43 (rain streaks), LAV mean luma flat
within +-0.2 over 6 s. No flicker reproduced.
