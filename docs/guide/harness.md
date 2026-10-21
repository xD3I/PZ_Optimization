# Benchmark harness

[Back to the README](../../README.md) · [Linux](#linux) · [Run queue](#run-queue) · [Jev's verdicts](#verdicts-by-jev) ·
[Windows](#windows) · [Live dashboard](#live-dashboard) · [Repository layout](#repository-layout)

One hands-off game run: reset a bench save from a template, auto-continue into it, run a
scripted route, quit, and collect logs, sysmon samples and per-frame timings into
`harness/runs/<label>-<timestamp>/`. The bench save template is checked in as
`harness/bench-save/pzopt-bench-template.tar.zst`. Modes: `bench` (teleport route, fixed
tiles per second, `--flag turn=90` for the spin), `drive` (spawns a car, cruise control,
follows the road), `parity` (captures every chunk's recalc output for the byte-for-byte
comparison), `verify` (a copy of a real save, for visual checks, `--shot-at N` for screenshot
rigs). Scene presets: `--preset storm | storm-fog | night-torch | night-dark | louisville`.
Bench runs must pin `--flag zoom=max`: baselines are only comparable at the same zoom,
resolution and renderer. Measurement runs pass `--no-dashboard` (the PZDashboard mod's 2 s
collectors) and `--no-mangohud` (the overlay log is the frame source).

## Linux

`harness/run.sh` (MangoHud CSV, JFR and an AV1 HDR screen recording optional):

```sh
zstd -dc harness/bench-save/pzopt-bench-template.tar.zst | tar -C ~/Zomboid/Saves/Sandbox -xf -   # once

harness/run.sh --label spin-opt   --mode bench --flag route=S:450 --flag turn=90 --route-seconds 25 \
  --flag zoom=max --prop uncappedFps=true --no-mangohud --no-dashboard --launcher direct
harness/run.sh --label spin-stock --mode bench --flag route=S:450 --flag turn=90 --route-seconds 25 \
  --flag zoom=max --prop uncappedFps=true --no-mangohud --no-dashboard --launcher direct --prop enabled=false

python3 harness/analyze.py harness/runs/spin-*            # frame tail + utilization (overlay: line)
python3 harness/compare.py harness/runs/spin-stock-* harness/runs/spin-opt-*
python3 harness/loadtime.py harness/runs/<run>            # boot and load phases
python3 harness/gametree.py harness/runs/<jfr-run>        # game-thread call tree (--jfr --jfr-period 1)
python3 harness/flamegraph.py harness/runs/<run>          # the route as an SVG flame graph (pzopt-stacks.out)
harness/grafana/stack.sh up                               # Grafana + Postgres (podman) at http://127.0.0.1:3000, every run's metrics
```

`--record` captures the run (AV1 10-bit HDR); `harness/stitch-*.sh` build the side-by-side
and triple videos above; `--prop gpuSections=true` logs GPU time per frame section. Steam
launches need the launch options set to `<repo>/harness/steam-launch.sh %command%`;
`--launcher direct` starts the native game itself.

## Run queue

`harness/queue.sh`: the game folder, the display and the Steam client are one
shared resource per machine, so runs are submitted to a FIFO instead of launched by hand: one
worker per machine (`desktop`, and the laptops `flip`, `dell`, `mac` over a monitored ssh
connection, `harness/queue/machines.conf`) waits for a game, a `run.sh` or an encode started
outside the queue and for the desktop to be unlocked, installs the build the job asks for
(`--install opt|stock`), runs it, syncs a laptop's run folder back to
`harness/runs/<machine>-<label>-<ts>/` and analyses it here. Media jobs (encodes, stitches, GIF
renders) share the desktop FIFO, so an encode never overlaps a measurement. `watch` and `events`
report a laptop dropping off or a job ending; every job ends in a `result.txt` with the exit code,
route completion, resolution and OpenGL lines, `analyze.py`'s card, console errors and a verdict.

```sh
harness/queue.sh submit run --goal "spin: p99 under 8 ms with the GPU saturated" \
  --against harness/runs/spin-stock-* --wait -- --label spin-opt --mode bench --flag route=S:450 \
  --flag turn=90 --route-seconds 25 --flag zoom=max --prop uncappedFps=true --no-mangohud --no-dashboard --launcher direct
harness/queue.sh submit run --machine flip --install opt -- --label drive-flip --mode drive --flag zoom=max ...
harness/queue.sh submit media --wait --label sbs -- harness/stitch-sbs.sh ...      # encodes queue behind the runs
harness/queue.sh list | status | wait | result | log -f | cancel <id|label> | machines | events
```

## Verdicts by Jev

The arithmetic is code, the judgement is a TypeSafe (Jev) classifier, so a
script can gate on it. `harness/judge.py <run> --goal "..." --against <run|baseline.json>` builds
the run's frame-tail / utilization card and its deltas against each reference with `compare.py`'s
per-metric noise floors (a delta is real past twice the floor); Jev answers typed questions over
that card and the goal text — `verdict=achieved|partial|no_change|regressed|invalid`, `goal_met`,
`tail_regressed`, `setup_matches_goal`, `headroom_finding` (the objective's "below the cap and
nothing saturated") — into `<run>/judge.json`, exit 0 only for `achieved`. `harness/parity-judge.py
<A> <B>` does the same for visual parity between two recordings (transient px/frame, black
squares, luma pops, per screen cell with the HUD corners named): `parity=parity|hud_only|flicker|
black_tiles|lighting_pops|...` and whether a person should look. `harness/ui-drive.py` drives the
game's own menus the same way (screenshot → OCR → Jev picks the control). Jev never sees pixels or raw logs, only the numbers the
scripts computed. The key comes from `$TYPESAFE_API_KEY` or `~/.config/pzopt/typesafe.key`; without
one the queue still runs and the result carries the card without a verdict.

## Windows

`harness/run-win.ps1` runs the bench through Steam, samples CPU and GPU load
with `Get-Counter` and `nvidia-smi`; the frame-time tail comes from the overlay log and the
analysis scripts run on any Python 3. The game pauses on focus loss, so keep the window
focused.

```powershell
harness\run-win.ps1 -Label bench-opt   -Flag zoom=max -Prop instrument=true
harness\run-win.ps1 -Label bench-stock -Flag zoom=max -Prop instrument=true,enabled=false
python harness\analyze.py harness\runs\bench-opt-*
```

## Live dashboard

Every run is ingested into a Grafana + Postgres stack (`harness/grafana/`, `stack.sh up` locally) and published at
[pzo.diegov.dev](https://pzo.diegov.dev): each run frame by frame with CPU / GPU use, the profiler's flame graphs,
run-vs-run comparisons, the daily re-measure of every release, and the game being benchmarked right now.

## Repository layout

| Path | What |
|---|---|
| `src/pzopt/pzopt/` | The new code, the `pzopt` package (~280 classes): `Config` (every key), `Overrides` / `BuildInfo` (build guard), the streamer, bake, weather, zombie and lighting passes, the Visuals, `Overlay`, `FrameCap`, `UserOptions`, the boot caches, `Harness` / `Parity` |
| `src/overrides/` | The 139 shadowed game classes, edits marked `// pzopt:`; `scripts/bytecode-audit.py` checks every unedited method against the jar |
| `src/shims/` | From-scratch replacements (`TISLogoState`) |
| `src/lua/` | The PZ OPTIMIZATION screen, its settings and the frame-cap options Lua, installed under `media/lua/client/pzopt/` |
| `install.sh`, `install.ps1` | Standalone installers (Linux, Windows); attached to every release and shipped in the Workshop item |
| `scripts/` | `build.sh`, `pzopt.sh`, `release.sh` (release zip + GitHub release), `workshop.sh` (Workshop staging), `test.sh`, `accept.sh`, `regen-overrides.sh`, `decompile.sh`, `pz-env.sh` |
| `harness/` | `run.sh` (Linux) and `run-win.ps1` (Windows), `queue.sh` (the per-machine run queue, `queue/machines.conf`), analysis and stitch scripts, `flamegraph.py`, the Jev judges (`judge.py`, `parity-judge.py`, `ui-drive.py`, `typesafe_client.py`), `parity-gate.sh`, the `pzopt-harness` Lua mod, bench save template, `baseline/` captures |
| `config/` | MangoHud profiles, the tuned G1 launcher JSON |
| `tools/` | Standalone Java probes (JFR sample dump, GLFW swap probe, static audit) |
| `tests/` | JVM-only unit tests (`scripts/test.sh`) |
| `docs/` | `results.md` (every run since 2026-09-24, in order; the earlier results, findings and dashboard snapshot are in `docs/archive/2026-09-24/`), `override-edits.md` (every edit, in prose), `windows-test.md`, `workshop.md`, the plans |
| `docs/guide/` | The pages the README links: install, benchmarks, features, settings, how it works, limitations, this one |
| `docs/media/` | The comparison videos, posters, thumbnails and charts |
| `docs/workshop/` | The Workshop item's `workshop.txt`, description and page images |
