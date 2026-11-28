# Binary greedy meshing (2026-11-28)

The maintainer asked to bring https://github.com/cgerikj/binary-greedy-meshing (v2: 64-bit column masks, face culling and
the greedy merge by bit shifts, 8-byte quads, vertex pulling) into the mod and profile it, on every place it can apply.
Worktree `~/pzopt-wt/greedy`. The game draws no voxel meshes, so the merge works on the one voxel structure the mod
has: the god rays / voxel lighting occupancy grid (`GodRays`: per square and level, floor / roof, the N and W edges as
wall / window / doorway, tree crowns). Three uses were built and measured, all off at first. **Shipped (master):
`godRaysVis=mesh`, the default since the fog / storm A/B below.** The torch mesh casters (`pplShadowCasters`,
`TorchMeshCasters`) stay on branch `greedy-meshing`, unmerged (verdict below).

| Key | Values (default first) | What |
|---|---|---|
| `godRaysVis` | `mesh` / `dda` / `mesh-dda` (default `dda` until the fog / storm A/B) | god rays sun visibility: the DDA through the grid, or a 4096² light-space depth map drawn from the merged quads (+ a crown opacity map), or the map first and the DDA where it says lit |
| `godRaysMeshRes` | 4096 | that depth map's size |
| `pplShadowCasters` | `texels` / `mesh` / `both` | torch shadow map casters: the chunk-texture texels, the merged wall / ceiling quads, or both |
| `pplShadowMeshPiecePct` | 100 | the most a cast quad spans before it is cut (the octahedral map is not linear) |

Dev: `devGodRaysVisAlternate=<ms>`, `devPplShadowCastersAlternate=<ms>` (in-run A/B cycles, timers keyed by method),
`devOccMeshNaive` (one quad per face), `devOccMeshStats`.

Classes: `pzopt.GreedyMesher` (the v2 port: `mesh()` for 62³ blocks, `mergePlane()` the 2D step),
`pzopt.OccMesh` (64x64-square x 16-level regions of the grid meshed on the game thread when a chunk rebuilds),
`pzopt.OccMeshMap` (the render-thread map pass, redrawn only when the quads, the sun or the window move),
`pzopt.TorchMeshCasters`; hooks in `GodRays` and `TorchShadowMap`. Tests: `GreedyMesherTest` (exact cover against brute
force, 3000 random planes, 6 blocks, 40 occupancy regions), `GreedyMeshShaderTest` (all programs link on NVIDIA and Mesa).

## The port

`tools/greedy/GreedyMesherBench.java` vs the original C++ (`tools/greedy/bgm_bench.cpp`, `-O3 -march=native`), same
quad counts on every block, median per 62³ block:

| Block | Java | C++ |
|---|---|---|
| terrain | 109 us | 75 us |
| random (1/3 solid) | 2,118 us | 1,742 us |
| checker | 2,051 us | 1,461 us |

## In game

Desktop (RTX 4090, 5120x2160), `--install opt` of the worktree build, empty options file. The first two runs
(`greedy-gr-day1`, `greedy-torch1`, Rosewood `route=S:450`) were pinned at the 165 Hz VSync (164 fps, GPU ~30 %): only their
GPU timers count. The still-scene runs are uncapped (`uncappedFps=true vrr=off --option vsync=false --no-mangohud`) and
GPU-bound, so a frame-time difference between phases is GPU time. Paired differences: each phase's mean frame against the
reference phases on either side of it, frames within 150 ms of a switch dropped.

**The mesher.** A region meshes in 34-62 us (the first one ~0.6 ms, JIT); 9-18x fewer quads than faces
(e.g. 91k quads for 919k faces over 300 regions); 4-6.6k quads live around the player. Moving at route speed it costs
the game thread 9-16 us a frame on average; standing still 0.

### God rays visibility

The visibility only feeds the haze volume: in clear air without haze (`greedy-gr-room1`) no volume compute ran at all.

`greedy-gr-room2`: `find=sunwindow` (furniture store, 12 sunlit windows), 17:00, `godRaysHazePct=250`, 444 fps,
GPU 89 %, p99 4.5 ms / p99.9 14.9 ms (the teleport's streaming), 1 %-low 222 fps.

| Volume recompute (GPU) | band | full |
|---|---|---|
| `dda` | 117 us | 266 us |
| `mesh` | 24 us | 51 us (+45 us when the map is redrawn) |
| `mesh-dda` | 108 us | 319 us |

Frame time: `mesh` -24 +- 3 us a frame against `dda`. On the moving route (`greedy-gr-day1`, sun sweeping so every
cell recomputes): `dda` ~32 us of GPU a frame, `mesh` ~24 (14 compute + 9 map redraws). `mesh-dda` saves nothing.

Picture: the shafts and the haze sit in the same places; `dda` -> `mesh` changes 0.7-1.4 % of the pixels by more than
8 levels of 255 (mean 1.4-2.0), against 0.5 % between two `mesh` frames 1 s apart. The map's resolution (4096² over the
window +-128 squares) and the crown opacity term are the approximations.

**Fog and storm** (`greedy-gr-fog`, `greedy-gr-storm`, the same room, 17:00, `--flag fog=heavy` / `--flag weather=storm
--flag cloud=0.8`, the master-based branch, uncapped, GPU 95-96 %, ~400 fps, 1 %-low 213-215 fps):

| | fog | storm |
|---|---|---|
| volume recompute, band (median) dda / mesh | 113 / 26 us | 44 / 26 us |
| volume recompute, full (median) dda / mesh | 148 / 43 us | 154 / 43 us |
| frame time `mesh` - `dda` (paired) | -29 +- 12 us | -117 +- 31 us |
| p99.9 dda / mesh phases | 11.1 / 7.6 ms | 7.0 / 4.5 ms |
| pixels > 8 levels, dda -> mesh / mesh control 1 s apart | 1.2-2.6 % / 0.3-0.6 % | 1.8-7.1 % / 2.2-9.0 % |

In fog the dda -> mesh difference is the walking characters (the x4 difference image shows nothing in the haze); in the
storm the rain changes more between two `mesh` frames than the method does. `mesh-dda` again costs more than `dda`.

**Default.** `godRaysVis=mesh` from the release after this pass. `OccMesh.wanted()` also needs the live `godRays` switch,
so with god rays off (the default) nothing is meshed (`greedy-gr-default-off`). Cost that stays: the mesher on the game
thread while the player moves (~9-16 us a frame, 0 standing), also in clear air where no volume compute runs.

### Torch shadow casters (branch `greedy-meshing`, not merged)

| Shadow map build (GPU) | lit room (`greedy-torch-still1`) | torch only (`greedy-torch-dark1`) |
|---|---|---|
| `texels` | 416 us | 376 us |
| `mesh` | 21.6 us | 21.0 us |
| `both` | 424 us | 386 us |

Frame time, `mesh` against `texels`: -204 +- 29 us (lit room, 450 fps) and -225 +- 7 us (torch only, 501 fps, GPU 97 %,
p99 2.9 ms, p99.9 4.5 ms, 1 %-low 350 fps). `both` is no cheaper than `texels`.

Picture: with the room lights on the difference is under the noise (the lamps drown the torch's shadows). Torch only,
`texels` -> `mesh` changes 1.2 % of the pixels by more than 8 levels (0.6 % by more than 24), against 0.01 % within one
method. What the mesh casters get wrong:

1. **The torch no longer shines through windows under a roof.** The grid is built for the sun: `godRaysRoofRule` makes a
   window or doorway whose outside square is under a roof / porch / floor above a wall (the sun cannot come in there). For a
   light inside the house those windows are open: the beam onto the room's wall and the lit band of grass outside the
   window went dark. A fix needs the aperture's own type next to the sun's; all 16 low bits of the grid are taken (the
   room hash fills the rest), so it would be a side table like `glassBands`.
2. **No furniture shadows.** The grid holds walls, floors and crowns only; the table's shadow is gone.
3. **Seams.** Dotted light lines along the wall corners where two merged quads meet.

## Verdict

- `godRaysVis=mesh`: 4-5x cheaper recomputes, -24 us a frame in a hazy room, -29 in fog, -117 in a storm, a close
  picture. Adopted as the default.
- `pplShadowCasters=mesh`: 18x cheaper build (-0.2 ms a frame at ~500 fps), but it loses furniture shadows and the light
  through roofed windows. Not usable as a replacement (kept on branch `greedy-meshing`); `both` costs what `texels` costs. A cheaper texel pass is the
  better lever.
- `godRaysVis=mesh-dda`: no gain; remove.

Runs: `harness/runs/greedy-{gr-day1,torch1,gr-room1,gr-room2,torch-still1,torch-dark1}-*`,
`greedy-gr-{fog,storm,default-on,default-off}-*`; side-by-side video `~/pzopt-wt/greedy-data/greedy-ab-sbs.mp4`; frames in
`~/pzopt-wt/greedy-data/frames*` (tone-mapped PNG crops at the method switches).
