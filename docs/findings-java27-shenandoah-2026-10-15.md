# Java 27 and generational Shenandoah (issue #11), 2026-10-15

Issue #11 suggests swapping the game's bundled Zulu 25 JRE (`jre64`) for Zulu 27 and running generational Shenandoah
(`-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational`). Commenters there report lower RAM use with 500+ mods, and one
player went from 20-40 to 50-60 fps with a posted JSON (6 GB fixed heap, Shenandoah, compact headers, DisableExplicitGC).

## Rig

- JRE: Zulu 27.0.0 (`zulu27.28.101-ca-jre27.0.0-linux_x64`, sha256 checked against Azul's API) in `~/.cache/pzopt-jre/`.
  `harness/jre-wrapper.sh` is a `GAME_WRAPPER` that runs the game on another JRE without touching the shared `jre64`:
  `GAME_WRAPPER=$PWD/harness/jre-wrapper.sh PZOPT_JRE=<jre dir> harness/run.sh --launcher direct ...` (pzexe finds libjvm
  through the first `java` on PATH). Each run's console shows `java.version=27` / `25.0.1`, and every run had the overrides
  loaded (357 `[pzopt]` lines).
- 2 x 2 matrix (Zulu 25 / 27 x G1 / generational Shenandoah), queue `cmd` jobs, runs `j27-<scene>-<cfg>-r<n>`, all optimized
  (`--install opt`, build 2026-10-15 17:00), empty options file, uncapped (`uncappedFps=true vrr=off vsync=false`),
  `--schedmon 0.25` for the process RSS, heap from run.sh's `gcHeap=auto` (4 GB) unless noted:
  - `spin`: spin-uncapped bench (Rosewood S:450, turn 90, max zoom, 25 s);
  - `lou`: `--preset louisville --flag settle=5 --record` (all 8 checked: strongMarks 5.5-5.7k, settle frames clean);
  - `mods`: 120 km/h KY-60 drive with the 132 Workshop mods of `findings-gc-heap-2026-10-05.md` and `-Xmx4096m` (the
    Full-GC stress case there); Zulu 25 Shenandoah left out. All six drives valid (`drive_check.py`).
- 2 runs each (spin Zulu 27 G1: the smoke run + 1). Table script kept with the runs' session; numbers below are the means.

## Results

| scene | config | fps | p99 ms | p99.9 ms | max ms | 1%-low | GC pauses in route (n / ms / max) | heap used / committed MB | process RSS GB | process cores | GPU % |
|---|---|---|---|---|---|---|---|---|---|---|---|
| spin | Zulu 25 G1 | 437 | 6.5 | 12.7 | 22.9 | 153 | 7 / 59 / 17 | 2404 / 2924 | 5.69 | 3.9 | 92 |
| spin | Zulu 25 Shenandoah | 378 | 9.0 | 16.7 | 41.3 | 112 | 17 / 1.0 / 0.2 | 3623 / 4096 | 6.40 | 4.7 | 81 |
| spin | Zulu 27 G1 | 437 | 6.5 | 12.2 | 25.0 | 153 | 5.5 / 47 / 17 | 1983 / 2288 | 5.25 | 3.9 | 94 |
| spin | Zulu 27 Shenandoah | 420 | 7.0 | 11.6 | 26.4 | 142 | 3 / 0.4 / 0.2 | 2911 / 3300 | 6.24 | 4.0 | 90 |
| lou | Zulu 25 G1 | 80.0 | 47.8 | 135 | 186 | 20.9 | 9.5 / 107 / 26 | 2613 / 3463 | 6.09 | 3.6 | 36 |
| lou | Zulu 25 Shenandoah | 64.3 | 61.0 | 164 | 226 | 16.4 | 15 / 0.9 / 0.2 | 3588 / 4096 | 6.94 | 3.9 | 32 |
| lou | Zulu 27 G1 | 84.0 | 46.1 | 125 | 169 | 21.7 | 7.5 / 97 / 27 | 2279 / 2511 | 5.42 | 3.7 | 36 |
| lou | Zulu 27 Shenandoah | 86.6 | 48.0 | 125 | 180 | 20.9 | 3.5 / 0.3 / 0.1 | 3177 / 3776 | 6.68 | 3.7 | 37 |
| mods | Zulu 25 G1 | 601 | 5.4 | 7.8 | 19.0 | 186 | 4 / 39 / 15 | 3638 / 4095 | 7.58 | 2.8 | 91 |
| mods | Zulu 27 G1 | 596 | 5.3 | 8.1 | 17.5 | 189 | 3 / 32 / 15 | 3143 / 3493 | 6.74 | 2.9 | 91 |
| mods | Zulu 27 Shenandoah | 586 | 5.6 | 8.1 | 22.4 | 179 | 4.5 / 0.4 / 0.2 | 3436 / 3780 | 7.29 | 2.9 | 91 |

Run-to-run spread: spin 424-449 fps for one config, Louisville 77-92 (and 53-90 across days), mods 583-604.

## Reading

- **Java 27 with G1 gives the same frame rate and frame times as Java 25.** Spin 437 vs 437, mods 596 vs 601, Louisville
  84 vs 80, all inside the spread.
- **Java 27 lowers RAM by 8-11 %.** Process RSS -0.44 GB (spin), -0.67 (Louisville), -0.84 (132 mods). The committed heap
  is 15-27 % lower. This is compact object headers, on by default in 27 (`UseCompactObjectHeaders=true` in
  `-XX:+PrintFlagsFinal`). On Zulu 25 the same flag gave -8 % heap and no fps change (`findings-gc-heap-2026-10-05.md`).
  This matches the comment in the thread that the RAM gain comes from compact headers.
- **Generational Shenandoah does not lower RAM. It raises it.** At the same 4 GB maximum heap it commits 0.3-1.6 GB more
  than G1 and adds 0.55-1.3 GB of RSS, because the concurrent collector keeps headroom.
- **Shenandoah on Zulu 25 is slower.** Spin is -13 % (p99 6.5 -> 9.0 ms, 1 % low 153 -> 112, +0.8 cores of GC threads,
  game-thread bound so the GPU drops to 81 %). Louisville is -20 % (64 vs 80 fps, both runs). This matches the
  2026-10-07 low-end finding (Shenandoah costs CPU) and the 10-06 Louisville single run.
- **Shenandoah on Zulu 27 is about even with G1.** Spin is -4 %, mods -2 %, Louisville +3 % (inside the spread). It
  removes G1's 15-30 ms pauses (Louisville: 97 ms of pauses per 25 s, now 0.3 ms), but p99 / p99.9 / max do not
  improve. The frame tail in these scenes comes from game-thread bursts, not GC.
- **No Full GC in any of the 22 runs**, including the 132 mods at 4 GB. On 10-05 that case had Full GCs in 2 of 4 runs
  while the world appeared, so it is borderline. The "20-40 -> 50-60 fps with 500+ mods" report fits a 3 GB heap
  thrashing with Full GCs: the posted JSON's `-Xmx6g` fixes that on any JRE. Our `gcHeap=auto` already gives 8 GB from
  30 enabled mods.
- Not adopted from the posted JSON (measured on 10-05): `DisableExplicitGC` (risk of running out of direct-buffer
  memory) and `UseStringDeduplication` (worse). Shenandoah also turns on `ExplicitGCInvokesConcurrent`, which blocked
  Lua's `collectgarbage()` for 270-320 ms; `luaGcNoop` (default on) makes that call do nothing.

## What follows

- **Players:** Zulu 27 is safe with PZ Optimization on Linux; every override loads. It saves ~0.4-0.8 GB of RAM and
  gives no fps gain. Keep G1 (our `gcMode` default). Shenandoah buys no smoothness and costs RAM, and on the bundled
  Zulu 25 it costs 13-20 % fps.
- **Caveats for a JRE swap:**
  - Steam's verify / update puts `jre64` back.
  - Release ZombieBuddy does not run on 27 (issue text).
  - Java 27 is a short-term release, superseded by 28 in March 2027.
  - A JDK-25 AOT cache (the f9-burst AOT work) does not carry over.
  - Not tested on Windows or macOS.
- **For us:** the only lever is the heap saving. Compact headers on Zulu 25 would give most of it without a JRE swap,
  but it was rejected on 10-05 (no fps gain, the AOT cache would need re-recording). It is worth reconsidering only as
  an opt-in for players short on RAM.
