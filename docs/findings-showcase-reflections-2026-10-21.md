# Reflections showcase walk and the feature sweep at the church (2026-10-21)

The maintainer: "prepare a run that showcases the reflections and visual features by walking around a house with a gym
room and next to a water source, disable zombies in this run, and the visual effects scene should be outside the rosewood
church near the main entrance on the east with the character walking in a square and rotating on each corner".

Worktree `~/pzopt-wt/menu-impeccable` (branch `menu-impeccable`), uncommitted. Bytecode audit 9152 methods / 0 mismatches,
`scripts/test.sh` OK (BenchSweepTest included); installed for the maintainer by queue job reinstall-showcase2 (19:34).

## The places (from the map files)

Found with the game's `.lotheader` / `.lotpack` files (RoomDefs, BuildingDefs, tiles; the parsers of
`harness/props/findtile.py` / `harness/roofs/census.py`). Map lookups are per place only (see "Memory" below).

- **Gym house, Riverside**: of the 9,546 buildings, 91 rooms are named `gym`; seven buildings hold a gym together with a
  bedroom, kitchen or living room. Only one of them has the game's gym mirror (`recreational_sports_01_77..79`) and water
  within 40 squares: the house at 6281-6300 x 5377-5391 (rooms laundry, gym, living room, kitchen, closet, hall, bathroom,
  bedroom, kids' bedroom, garage storage). Gym 6283-6286 x 5377-5379, the full-length prop mirror on its west wall
  (x 6283, y 5377-5379, facing east), a window in its north wall (6285,5377). The Ohio is 13 squares north (water at
  y <= 5364, the railway on the bank at 5365-5366, an open yard and a stone patio between it and the house); a pool in the
  neighbour's fenced yard at 6269-6273 x 5373-5382, reached through the yard gate at 6276,5368. Front doors 6290-6291,5377
  (north wall), the gym's door on the west side of 6287,5378; windows in the north wall at 6282, 6285, 6288 and 6295. It is
  the house of the maintainer's gym-mirror save (`docs/findings-gym-mirror-2026-10-16.md`).
  - **The river cannot reflect anything here**: the water reflections land below an object on screen (`pzopt.Ssr`, the iso
    mirror runs down the screen column), and the river lies north of everything on this bank, so the bank's objects
    mirror onto land. The pool next door does reflect; the walk has a pool stop. River reflections need a house on a
    north bank or a pier.
- **Rosewood church** (the big one with the steeple, 8119-8154 x 11505-11524): the main hall 8131-8150 x 11511-11519,
  its main doors (`location_community_church_small_01_16/17/24/28`) on the east front at x 8150, y 11517-11519, a stone porch
  8150-8152 x 11512-11519 (steps and railings at its north end), then open pavement 8153-8156 and the street's kerb at 8157.
  The square walk: corners 8153.5,11515.5 / 8155.5,11515.5 / 8155.5,11517.5 / 8153.5,11517.5, on the pavement in front of the
  doors, nothing on any of those squares. Frames show the player outside the double doors, the lamps either side.

## What the scenes do

- **Reflections walk** (`explore=tour tour=riverside-gym`, `pzopt.TourWalk`; Jev directs in harness runs, the built-in
  benchmark plays the same order on an autopilot), zombies off, 16 stops: the river bank (look round), the bank west, the
  gate's two sides, the pool's north edge facing the water and a look round, back through the gate, the gym window from
  outside, the living room, the gym in front of the mirror (look round), a second gym spot, the living-room window from
  inside, outside by the kitchen window, the east bank (look round). Benchmark scene `reflections` (100 + 6 s).
- **Visuals feature costs** (`explore=square`, `pzopt.SquareWalk` + `BenchSweep`): the player walks the square and turns
  360° + 90° at every corner, one lap per 10 s measured window (SETTLE 1 s + MEASURE 9 s), while each live Visuals feature
  is switched on / off as before. Benchmark scene `sweep` (234 + 6 s; 11 of the 14 features switch live on this build,
  pixelLight, relief and mirrors apply at the next launch).
- Queue benches (`harness/queue/benches.json`): `reflections-walk` (needs `explore-director.py` started first) and
  `church-square`. `src/lua/client/pzopt/pzopt_walk.lua` is the harness walk Lua copied so the built-in benchmark walks
  without the harness mod.

## Iterations

| run | change | result |
|---|---|---|
| sq-church1 (job 14185) | first square walk: 6 s laps, 2-square side | invalid: ran while another session's whole-map scan swamped the machine (CPU 100 %, 35 fps, p99 210 ms); 15 of 20 sides still walking when the next was due (a side ~1.45 s, a quarter lap 1.5 s: 0.02 s left to turn) |
| build-showcase2 | `BenchSweep` window 6 → 10 s (SETTLE 1 + MEASURE 9) | build OK, audit 0 mismatches |
| sq-church2 | church square, 10 s laps, 5 laps, max zoom, Visuals on by `--prop`, empty options file, `--record` | clean: 20/20 corners reached ~0.98 s before the next side, 0 late, 0 collisions; frames show the corner turns. 60.0 fps (options.ini frameRate=60), p99 17.2, p99.9 19.0, max 22.3 ms; GPU 28-34 %, CPU 13 % |
| refl-gym1 | first gym-house tour, Jev director | 9/9 stops, 0 collisions; mirror and puddles reflect. Bug: the look-rounds never turned (`Nav.face` ignores < 1°, 360°/4 s at 156 fps is 0.6° a frame); the river shows nothing (geometry, above) |
| build-showcase3 | `TourWalk` keeps its own heading for its turns; pool stop | build OK |
| refl-gym2 | tour with the pool stop | the pathfinder vaulted the fence corner at 6278,5368: 1 collision, 1 climb, Jev "broken" |
| build-showcase4 | pool legs through the gate via three viewless stops (railway 6277.5,5366.5; gate sides 6276.5,5367.5 / 6276.5,5369.5) | build OK |
| refl-gym3 | final tour | clean: 16/16 stops, 0 collisions / climbs / stuck, navjudge exit 0, Jev "clean", look-rounds turn. 156.9 fps (VRR cap 157), p99 6.5, p99.9 8.2, max 39.5 ms (one frame > 33 ms); GPU 39-49 %, CPU 11 %, game thread 27 % of a core |
| bench-refl-sweep1 (job 14198) | built-in benchmark `harness/benchmark-run.sh reflections,sweep:1`, default settings | both scenes in the report, SWEEP table (11 features, SWEEPNOISE 0.003 ms frame / 0.007 ms GPU). Reflections: 16/16 stops, 0 collisions, walk 99.6 s; 477 fps, 1%-low 218, 0.1%-low 118, p99 3.96 ms, GPU-bound 83 %. Sweep: 23 laps, 92 corners, 0 late, min slack 0.62 s; 570 fps, 1%-low 185, p99 3.89 ms, GPU-bound 77 % |
| build-showcase5 / test-showcase1 / reinstall-showcase1/2 | scenario seconds = measured times; SCENE_INFO text | audit 0 mismatches, tests OK, installed |

Every run logged `[pzopt]` console lines (546-878). Runs: `harness/runs/refl-gym3-20261021-191819/`,
`sq-church2-20261021-190014/`, `bench-refl-sweep1-20261021-192154/results/report.txt`. Checked frames (tone-mapped from the
AV1 HDR recordings) in `~/pzopt-resume-2026-10-21/refl/`: `gym1-mirror-crop.jpg` (the gym mirror shows the player from the
front and the treadmill), `gym3-pool-crop.jpg` (the player upside down in the pool, rippled), `gym1-terrace-crop.jpg` (wet
terrace puddles reflect the chairs, table and railing), `gym3-indoor.jpg`, `church2-crops.jpg` (a corner turn).

## Memory

The first two attempts at this work died with the whole desktop session when a parallel whole-map lotpack scan ran the
machine out of memory (18:19 and 18:31). This pass looked up one region only (6260-6305 x 5355-5392, cells 24,20 / 24,21)
with a cell cache capped at 9 cells, every map / video script under `systemd-run --user --scope -p MemoryMax=4G
-p MemorySwapMax=0`, video frames pulled one at a time with ffmpeg.

## Open

- Sweep length: 234 s (was 120) because the full turn at each corner needs ~1 s; `square_spin=0` (only the 90° corner turn)
  with 6 s laps would be ~142 s. Which reading of "rotating on each corner" is wanted: maintainer.
- The reflections walk counts in the benchmark score (only the sweep is excluded); whether it should be excluded too.
- `~/Zomboid/options.ini` has frameRate=60: harness runs without `--option frameRate=240` cap at 60 (sq-church2 did).
- Seen in frames, not from this work: an orange outline on a cabinet near the player indoors (likely the game's hover
  highlight), speckled dots in the tree-crown shadows at the church at max zoom, unexplored rooms black (stock).
