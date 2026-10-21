# Voxel lighting, 2026-10-18

Asked by the maintainer: "your objective is to implement voxel lighting, your secondary objective is to make it cost virtually
zero in terms of performance, implement all state of the art cutting edge techniques found in other games, don't discard any
idea, implement them one by one and profile them". Worktree `~/pzopt-wt/voxel`, branch `voxel-lighting` from origin/master
a3729ea3. Class `pzopt.VoxelLight`, on top of `pzopt.PixelLight` (needs `pixelLight`) and `pzopt.GodRays`' occupancy. Runs on
the desktop (RTX 4090, 5120x2160) through the queue, labels `vxl-*`. Rig: the Rosewood church lot at 01:00 with every light on
(`start=8168,11502 time_of_day=1 lights=on zombies=off zoom=1`), the same spot as the offline mock-up of the morning.

Keys: `voxelLight` (off by default, next launch), `voxelLightApply` (cache | pixel), `voxelLightSquares` (256),
`voxelLightRes` (2 texels a square), `voxelLightLevelRes` (2 a level), `voxelLightBricksPerFrame` (256), `voxelLightCombine`
(max), `voxelLightAmbientAdd`, `voxelLightSky`, `voxelLightSkyDecayPct`, `voxelLightSkyFloorPct`, `voxelLightBounce`,
`voxelLightBouncePct`, `voxelLightBounceDecayPct`, `voxelLightPropIters`, `voxelLightGlassPct`, `voxelLightFencePct`,
`voxelLightCrownPct`, `voxelLightWrapPct`, `voxelLightNormal`, `voxelLightStrengthPct`, `voxelLightGatherFrames`,
`voxelLightCacheLayers` / `CacheW` / `CacheH`, `voxelLightRelightsPerFrame`; dev `devVoxelLightView`, `devVoxelLightViewCycle`
+ `devVoxelLightViewList`, `devVoxelLightTiming`, `devVoxelLightAlternate`, `devVoxelLightFull`, `devVoxelLightDumpAt`.
`devPplAlternate` now takes any number of masks (`start,period,m0,m1,...`, `harness/voxel/ctimen.py`); voxel cost bits:
65536 the voxel term off, 131072 no facing, 262144 no wall clamp, 524288 one layer, 1048576 / 2097152 cost probes, 4194304
the per-pixel path instead of the cache.

## Result (final defaults, desktop RTX 4090, 5120x2160)

| scene | frame time, voxel on vs off (within-run alternation) | tails | voxel compute |
|---|---|---|---|
| still night scene with a torch, uncapped (vxl-static-ab) | **-12.7 +- 1.8 us** (881 vs 870 fps): cheaper than pixelLight alone | p99 2.20 / 2.17 ms | ~1 us a frame |
| same, after the Jev fixes (16-bit cache, validity-weighted read, room spill; vxl-static-ab50) | **+2.7 +- 5.3 us** paired (median -1.2, gpu -3.2) | p99 2.12 / 2.12 ms | |
| 120 km/h night drive through Rosewood at max zoom, uncapped (vxl-drive4) | **+30.8 us (+1.7 %)** (paired median -42, noise) | p99 8.43 / 8.46 ms | 3.7 us a frame mean |
| zoom cycle 0.5 <-> max at the 60 fps cap (vxl-zoom1) | +2.9 us paired median | p99 17.95 / 17.92 ms | relights 1 us median, p90 3 us |

Game thread: 1-4 us a frame (lights read every 10 frames, the bricks' bookkeeping). VRAM: ~60 MB volume + 144 MB surface cache (RGBA16 since the Jev check)
(+32 MB with `voxelLightDirect=maps`). Open: the drive's GPU-busy column shows +39..60 us with only ~4 us of voxel compute and a
cheaper composite; not attributed yet. Not run on the flip (Radeon 890M) or the Mac (no OpenGL 4.3: voxel lighting stays off there).

Defaults: `voxelLight` off (Enhancements tab, per-pixel lighting section, next launch), `voxelLightApply=cache`,
`voxelLightDirect=dda`, `voxelLightSkyMethod=flood`, `voxelLightBounceMethod=flood`, `voxelLightTorchShadows=true`,
`voxelLightSkip=true`, `voxelLightPending=native`.

## Techniques, one by one (what each bought)

| technique (where it comes from) | here | measured |
|---|---|---|
| voxel occupancy + exact 3D DDA shadows (Teardown) | GodRays' occupancy, edges as planes with sill / head heights, fences, crowns | the reference |
| clustered light lists | per chunk bucket, per brick list | — |
| sparse bricks, dirty tracking, time-sliced budget (incremental GI updates) | 4 x 4 x 1 bricks, nearest first, 256 a frame | still scene: 0 traces |
| hierarchical occupancy / empty-space skipping (Teardown's mip bitmasks) | 4 x 4 coarse mask | brute-force direct 556 -> 517 us (-7 %) |
| per-light visibility maps traced in voxels (shadow maps) | 64 x 64 octahedral, PCF 2 x 2 | brute-force direct 517 -> 122 us; 4.8 % of lit texels differ > 0.05 (option) |
| flood-fill propagation (Minecraft shader packs, LPV-lite) | sky + bounce, max of neighbours | inject 32 us + ~10 us an iteration a full window, regional |
| six-flux directional propagation (LPV / discrete ordinates) | bounce against the surface normal in the cache | +120-160 us a changing frame, barely visible (option) |
| cone-traced sky (VXGI-style, ray form) | 17 rays a cell | 1.9-2.2 ms a full-window refresh (option) |
| radiance cascades (Path of Exile 2) | 2 cascades: 0-3 squares a cell, 3-14 from 2 x 2 probes | the cones' result for 0.93 ms (option) |
| surface cache (Lumen) | a light layer a chunk texture, relit by compute | composite +243 -> +16 -> -7 us |
| lightmap dilation, median surface sample | layer texels never empty, never the room through a window | the chunk-edge dashes and window dots gone |
| regional updates | up to 4 boxes of what changed | drive voxel compute 50 -> 14 -> 3.7 us |
| dynamic-light voxel shadows | torch visibility maps, traced when the torch moves | standing / turning: 0 |
| temporal hysteresis (DDGI) | not needed: the game's lights switch instantly; the volume changes only when they do | — |

## What the native light is (measured on the night dump)

The native's per-square light is `max(ambient x darkMulti, max over lamps of (1 - d / r) colour)` per channel (d in squares
between square centres, r the lamp's radius capped at 20, colour = the lamp's x 2 clamped): predicted 0.5 -> native 0.44 on
the red channel over 10k squares; room lights flat 0.9 / 0.8 / 0.7 in their room's rectangle; darkMulti is the indoor
ambient factor (0.5 / 0.67 / 0.75 / 1), not vision (the vision cone is its own screen pass). The first version summed the
lamps with a screen blend and added the ambient: overlapping sodium lamps saturated green and blue, the lot turned
yellow-green. Defaults are now the native's combination (`voxelLightCombine=max`, the brighter of ambient and lamps).

## Pipeline

1. **Occupancy** (GodRays, kept in its occupancy-only mode when only voxel lighting wants it): per square and level the
   floor, the N / W edge (wall / window / doorway / open), room hash, roof, crown density; new voxel-only bits: a see-through
   fence (`WallNTrans` / `WallWTrans` / `TallHoppable*`, bits 14 / 15: `voxelLightFencePct` of the light through) and a low fence
   (`Hoppable*` on an open edge, bits 7 / 13: stops only the bottom third). A rebuilt chunk whose words changed dirties the
   bricks of its squares and of the lights in reach of it (the 30 s refresh of identical chunks dirties nothing).
2. **Lights** (game thread, every 10 frames and on a window move): the lamp list (active as the native has it, the native's
   colour and radius, the head 0.8 levels up outdoors, 0.6 indoors) and the room lights; a light whose signature changed
   (switched, colour, power, came, went) dirties the bricks in its reach box, old and new.
3. **Bricks** (4 x 4 squares x 1 level, 8 x 8 x 2 texels): the dirty ones nearest the camera first, 256 a frame (a brick no
   light reaches counts 1/16); a brick the window scrolls onto is cleared (validity 0) at once and traced later; nothing is
   traced before the occupancy scan is complete.
4. **Direct** (compute, a workgroup a brick): per texel the brick's lights (a per-chunk bucket list, CPU), each through an
   exact 3D DDA of the occupancy to the lamp's head: a wall stops it, a window lets `voxelLightGlassPct` through between sill
   and head, a doorway up to its head, a floor or roof crossed stops it, crowns attenuate (not the receiver's own crown within
   2 squares nor the lamp's last 0.75 square: the lit leaf and the lamp in its own tree). Out: RGBA16F rgb + validity, RGBA8
   the light's dominant direction + how directional (room lights add to the sum, not the direction).
5. **Flood** (compute): inject (the open sky: a column with nothing above, crowns thinning it; the traced light x albedo; the
   per-square blocked-edge mask) and iterations `C' = max(S, decay x max over open neighbours C)` (sky 0.72, bounce 0.6, a
   window's pane and a see-through fence let part through), 4 a frame while unsettled.
6. **Resolve** (compute): A + B + C into two RGBA8 2D arrays (T1: lamps + bounce, a = validity / sky packed as 0 or 0.5 +
   sky / 2; T2: octahedral direction, directionality, validity), the flood upsampled per texel without crossing a blocked edge.
7. **Surface cache** (compute, `voxelLightApply=cache`): a light layer per chunk texture (192 x 128 RGBA8, a 2D array of 512
   layers, 48 MB), relit when it is new, its chunk baked again, or the volume settled after a change (32 a frame, new ones
   first): per layer texel the surface point from the chunk texture's own depth through the texture's world mapping (from the
   composite's own placement: the chunk's screen point, half the width left, the levels and the jumbo margin above; x + y + 2z
   from the draw's chunk depth), its normal from the depth two texels to each side, then T1 / T2 as the per-pixel path reads
   them (wall clamp, both layers of the level, facing). Out rgb / 1.25 (headroom for a facing light), a = validity + sky.
8. **Composite**: per chunk draw a `vxlLayer` uniform; one bilinear fetch at the texture's own uv: `max(ambient x sky, light)`
   blended with the native's light by validity. A texture without a lit layer yet takes the per-pixel path.

## Cost log (desktop, 5120x2160, uncapped, in-run alternation of the composite's cost masks)

| step | voxel term in the chunk composite | notes |
|---|---|---|
| v1 per pixel: 3D RGBA16F A + RGBA8 B + RGBA16F C, 3 R32UI occupancy fetches, texel normal | +243 us (474 -> 704 / 487 -> 730) | facing 88, wall clamp 31, flood fetch 11, the rest ~113 |
| v1 + screen-derivative normal | +174 us | facing 19 |
| v2 resolve to RGBA8 2D arrays, R8UI edge mask | +121 us | facing 8, clamp 19, second layer 6 |
| v2 + floors read one layer, clamp only near an edge | +131 us (noise) | fetch count is not the cost |
| probes: same program, no fetch / one fetch | +1 / +23 us | registers are not the cost: per-fragment work x the composite's overdraw |
| **v3 surface cache** | **+16..20 us** | the per-pixel path in the same run: +133 us |

| v3 + light-free variant early-out (a fully valid layer texel is the whole light: no lattice, edge path or wall gradient read) | **-7 us** (476.8 vs 484.0, median -6.6) | voxel lighting makes the composite slightly cheaper than pixelLight alone |

Moving (the 120 km/h night drive south through Rosewood at max zoom, uncapped, `devVoxelLightAlternate=2000`,
`harness/voxel/abframes.py`):

| build | frame on / off | p99 on / off | GPU busy | voxel compute (mean) |
|---|---|---|---|---|
| whole-window flood / resolve, global relight | +91 us (+4.7 %) | 10.44 / 9.76 ms | +89 us | 50 us |
| regions (up to 4 boxes of what changed + 16 squares; flood, inject, resolve only there; layers stale only under a region) | +64 us (+3.3 %) | 9.65 / 9.75 | +91 | 14 |
| 1536 layers of 128 x 96 (no evictions at max zoom), pending layers show the native light (no per-pixel fallback) | **+39 us (+2.0 %)** | **8.89 / 8.89** | +73 | 13 |

Radeonsi registers (desktop iGPU, `tools/voxel/vxlcheck --draw` with AMD_DEBUG=ps,stats): light-free variant 32 -> 40 VGPRs
with screen normals (56 with texel normals), full variant 64-72.

Compute (devVoxelLightTiming): a still scene 1 us a frame (binds); the first fill of the window: direct 185 us a frame (256
lit bricks), flood 107 us (4 iterations of 524k cells), inject 31 us, resolve 61-123 us, for ~30 frames; the cache's relight
116 us for 32 layers (~3.6 us a layer) on the frames after a change. Game thread 4.3 us a frame.

## Surface cache details

- **Dilation**: a layer texel whose block shows no surface took nothing (0) and the composite's bilinear tap mixed it into its
  neighbours: dark dashes along every chunk texture's edge, a wrong (zero) sky. Such a texel takes the nearest surface of the
  next blocks now (the lightmap dilation of baked games).
- **The block's surface**: the median depth of nine samples, not the first covered one (a window block took the room seen
  through the opening: dark dots round every window).
- **Leaves**: a baked tree's depth puts its crown inside the crown's own volume and its normals are noise: crown texels that are
  not a flat floor keep the native's light (trees look as the game lights them; the ground under them is voxel-lit). The
  floor test uses the raw normal (the snap makes any texel at a level's height a floor: a dark line across crowns).
- **Facing only on planar texels** (both neighbours on each axis on the texel's plane): bushes, bars and sprite edges take the
  light flat, as the native gives objects (they were dark blocks).
- Relights: ~3.6 us a layer (192 x 128), ~1.8 (128 x 96); new layers first, then re-baked chunks, then stale ones; a vision
  re-bake (turning) relights its chunk's layers (~35-45 us a frame while turning, an open item).

## Torch shadows (`voxelLightTorchShadows`)

Per carried torch (up to two), a visibility map 32 x 32 squares round the lens, 4 texels a square, two heights in its level
(0.05 and 0.55 of the level), traced through the occupancy with the same DDA (walls, windows, fences, floors, crowns), again
only when the lens moved or the occupancy changed (standing still and turning: never). The composite multiplies the torch's
term by one bilinear tap at the point a hair along the normal (a lit wall face would otherwise blend the texel behind it).

## Empty-space skipping (`voxelLightSkip`)

A coarse occupancy (4 x 4 squares a level, R8UI 64 x 64 x 16, rebuilt by a compute pass when the occupancy changed): a block
with no edge, fence or crown is crossed in one DDA step (its exit edge, the next block's, still tested; a level crossed
inside it is not skipped).

## Direct light: per-texel DDA or per-lamp visibility maps (`voxelLightDirect=dda|maps`)

`maps`: a 64 x 64 octahedral visibility map per lamp (a 2D array of 1024 RGBA16F layers, 32 MB), marched once from the lamp's
head outwards (the first full stop's distance, the first partial one's, what the partial ones let through), again only when the
lamp or the occupancy in its reach changed; texels read it with 2 x 2 percentage-closer filtering instead of marching. On the
brute-force reference (`devVoxelLightFull`: 8192 bricks traced every frame) the direct pass is **122 us vs 517 us** (DDA with
empty-space skipping; 556 without), 365 maps traced once. 4.8 % of lit texels differ by more than 0.05 (shadow edges: the
map's ~3 degrees a texel far from the lamp), mean difference 0.008. The default stays the exact DDA: a still scene traces
nothing either way; `maps` is the option where fills dominate (teleports, a slow GPU).

## The sky term: flood, cones or radiance cascades (`voxelLightSkyMethod`)

| method | what | cost of a full-window inject (fresh region) | picture |
|---|---|---|---|
| flood (default) | open columns (nothing above), flooded cell to cell through openings, decay 0.72 a square | 32 us | rooms lit from their windows and doorways; diamond-shaped falloff |
| cones (VXGI-style) | 17 rays a cell (zenith, 8 azimuths at 20 and 50 degrees up, cosine-weighted, 14 squares) through the occupancy | 1885-2230 us | real sky occlusion: courtyards between buildings and alleys darker, window light from where the window faces |
| cascades (radiance cascades) | cascade 0: each cell's first 3 squares in the 17 directions; cascade 1: probes every 2 x 2 squares a level, 3-14 squares; merged per direction | 930 us (cascade 529 + inject 400) | the cones' result: mean difference 0.002-0.007 a level, 95th percentile <= 0.04 |

Only fresh regions (where the occupancy or the traced light changed) inject, so a still scene pays none of it; while driving
the regions are strips at the window's edge. Cones and cascades stay options (2 ms / 1 ms a full refresh).

## Wet glints of voxel-lit lamps

A lamp in the volume left pixelLight's per-pixel loop, and with it its wet-ground glint. In the rain such lamps go back into
the loop as kind 3: their diffuse light stays the volume's (shadowed), the loop adds only the glint (like a vehicle light's).
Checked in a rainy night run (vxl-rain1): the glints under the lamps match the native's.

## Harness: weather flags at night

`Scene.assertWeather` pinned GLOBAL_LIGHT_INTENSITY / AMBIENT / DAYLIGHT_STRENGTH to daylight values for every `weather=`, so
`time_of_day=1 weather=rain` was a rainy noon. With a forced hour the light floats now follow the climate's own value for that
hour, times the storm's dimming; without `time_of_day` the old daylight pin stays (the weather= baselines are unchanged). The
maintainer asked for it mid-pass ("change the rig to allow weather flags").

## Jev visual check (2026-10-18 evening)

Four runs, voxel lighting alternating with the native every 2-3 s, judged by `harness/voxel/voxel-judge.py` (numbers from
the captures, Jev classifies them): night lot, the same in rain, noon (`weather=clear time_of_day=12`), and a Jev-directed
torch walk (`explore=circle director=jev`). Builds 43 -> 50:

| run | build 43 | build 50 |
|---|---|---|
| night: dark specks / Mpx, crushed blacks, Jev | 108.9, 1.5 %, lights_correct 0.27, artifact colour | 65.2, 0.4 %, lights_correct 0.15, artifact colour / specks |
| rain | 94.3, 0.7 %, specks | 72.4, 0.2 %, specks |
| noon | 54.1, 0.15 %, lights_correct 0.32, specks (0.68) | **8.9**, 0.09 %, **lights_correct 0.79, artifact none (0.79), look_needed 0.29** |
| walk: transient px / frame on vs off | 431 vs 286 (half res) | ~10.2k vs ~8.0k (full res, +25 %), no pop past the first phase |

What the crops showed and what fixed it:

- **Dark rims round every bush and crown, the top of a small tree black** (build 43). Leaf / crown blocks of the surface
  cache store "use the native" (alpha 0, rgb 0) and the composite read the cache with one bilinear tap: next to such a block
  the rgb was pulled to black while the alpha handed only part of the pixel to the native. Now the cache is RGBA16, a valid
  texel's alpha is 0.99 + 0.01 sky, and a filtered alpha under 0.99 (a native texel in the footprint) makes the composite
  weigh the four texels by their validity (four texelFetch, only there). pixelLight's PPL_BASE early-out takes the fast path
  only above 0.99 too.
- **The dumpster nook behind the restaurant black** (the native lights it white and orange). Two causes, found with
  `devVoxelLightProbe` (lights, rooms, occupancy and the native's own light per square) and a dump (`devVoxelLightDumpAt`):
  (1) the native's room lights shine 5 squares out of their room through windows and doorways (`IsoRoomLight.shineOut`,
  (1 - d / 5)^2 x 2): the voxel direct pass now does it, traced at mid-level height from the point to the nearest of the
  room's squares and two either way along its side (a window lights the strip along its wall), without a direction (a
  direction into the room bent the summed direction of every light there into the wall: the lamp-lit brick facade went dark
  with white dots round its windows, build 48); (2) a free-standing fence panel ("fencing_" walls, or a wall flag from a
  non-wall object, no room on either side; `GodRays.solidFence`, low + see-through bits on a wall edge) was a full-height
  wall: it now stops light up to 0.65 of a level only.
- Non-planar texels (plants, sprite edges) take the light at their own point again, not the floor under it.

Left as they are (checked, not defects): a bush or a small tree's top in the shade of a big tree's crown from a street lamp
(the native never shades one tree by another; Jev counts these as specks, the night / rain count stays ~65-72 / Mpx); the
right dumpster's front in the shadow of the panel standing next to the lamp; the torch cone's square-stepped edge while
walking, which is the same with voxel lighting off (`vxl-walk50-novxl`) and with `voxelLightTorchShadows=false`
(`vxl-walk50-nots`): the native torch visibility per square, not this pass.

New dev keys: `devVoxelLightProbe=x0,y0,x1,y1,z[;...]` (every 600 frames: lamps, room lights, per square room / occupancy
word / the native's light, wall sprites), `devVoxelLightTorchLog` (every torch retrace with its position, height, level and
the shadowed share of its map; reads the map back). `tools/voxel/vxlrun.c` writes its whole output with `VXLRUN_OUT=`.
Screen <-> world at zoom 1 on the 5K desktop (capture at 50 %): fit the lamp bulbs (`x - y = (px - ox) / 32`,
`x + y = (py - oy) / 16`), not a guessed scale.

## Night drives and the Riverside dock (2026-10-18 late, builds 50 -> 53)

Maintainer's priority runs, all at 1 AM, clear, `lights=on` (grid power on, switches on), voxel lighting alternating with the
native every 3 s, devCapture 15 fps: the 120 km/h KY-60 drive (`drive-120`), the 120 km/h Rosewood drive (`drive-120-south`)
and a Jev-directed walk on the Riverside pier (6436,5186). Judged by `voxel-judge.py --mode moving`, a flash scan and the new
`harness/voxel/car-flicker.py` (the car's box luma per frame against the road beside it, blinks split by voxel phase, Jev).

- **Headlights gone with voxel lighting on** (every on frame of the KY-60 drive). pixelLight leaves a vehicle light (kind 2)
  to the native's field and draws only its wet glint; voxel lighting replaces that field. Now the light's own model
  (pplTorch's focused cone, facing) is added where the voxel light took the field's place (`vxlVeh`, x vxlW, without the
  torch visibility V), and a chunk whose only lights are headlights no longer takes the light-free variant (build 51).
- **One frame of full daylight** on the Rosewood drive (frame 65, a voxel-on phase). The outdoor ambient (`PixelLight.ambientOutdoor`,
  the mode of the seen squares' native light) can be won for a frame by the headlight beam's saturated squares; the voxel
  term paints every outdoor pixel with it. The estimate now takes a jump of more than 8 / 255 only after three frames in a
  row (`ambientCommit`); small changes pass at once (build 52). Re-run (vxl-night-rosewood53): no flash in 572 frames, Jev
  artifact none.
- **The car flickers** (maintainer saw it on the 120 km/h drive): confirmed (Jev yes 0.83-0.91), not voxel lighting: 30-32
  blinks / 100 captured frames with voxel on or off, `charDrawPrep=false` 30, `vehicleSmooth=off` 31, **stock
  (`enabled=false`) 38**. The car model takes the per-square light where it stands; at 120 km/h under lamps every ~8 tiles it
  steps between lit and dark ~4 times a second, 2-3x the steps of the road beside it (which pixelLight interpolates). Vanilla
  behaviour; a smoothing of the vehicle's light would be a separate change. (A colour tracker tried on the way locked onto
  the road's yellow line: the camera holds the car at one screen spot while cruising, stock too, so a fixed box is right.)
  **Re-checked at every presented frame** (maintainer: "record another 120km run to confirm the flicker"; runs
  `vxl-night-drive120-rec53` and `car-flick-stock-rec`, `--record` + `devCapture=12,16,240,100,crop=...,ram`, ~150 fps, the car
  1:1): the 15 fps blink counts above were aliasing. The real pattern is a sawtooth: the car model brightens over ~8 frames
  to fully lit and drops to dark in one frame, ~10 times a second (median 15-16 frames apart), while the road beside it
  stays steady (0.2-0.3 luma a frame). Same in both voxel phases (snaps 8.2 / 8.6 per 100 frames, 30 dark drops per 3 s phase
  each) and the same in stock (56 dark drops / 800 frames, median 15 apart; car luma p10/p90 4/85 vs 4/76-79). Jev: flickers
  yes 0.88 / 0.90; cause vanilla 0.86 (voxel 0.07, other pzopt 0.05). `car-flicker.py` now also counts snaps (one-frame jumps
  > 40 %) per second. Likely mechanism (not yet proven): the vehicle model takes its point lights from its square's result
  lights (`ModelManager.getSquareLighting`), its own headlight torches among them, positioned as of the native's last pass.
  **Uninstalled control** (maintainer: "do a control run with the mod uninstalled"; run `car-flick-uninstalled`, `--install
  stock`, 0 `[pzopt]` console lines): the drive redone in the harness Lua with the game's own calls (`--flag stock_drive=1`:
  1 AM, power on, the save's car with headlights, the stock cruise control at 120 km/h; `--inputlag
  harness/inputlag/stock-click.txt` presses click-to-start), recorded at 60 fps: the same sawtooth, a ramp over ~5 frames and
  a drop to dark every ~6 frames (~10 a second), car luma p10/p90 3/42 while the road beside it steps 2 luma a frame. The
  maintainer confirmed the flicker by eye. Vanilla.
- **Checkerboard on the donut shop roof** (dock walk): the tall z=1 street lamps (r 40-60) light the flat roof; the native lights
  only the roof squares the player has seen (0.2-0.57) and `pplUnseenAmbient` draws the never-seen ones with the flat ambient,
  per square. Stock has the same boundary at low contrast; the brighter voxel light makes its steps visible. Not changed:
  lighting never-seen squares would light rooms the player has not discovered; a smoothed boundary costs fetches.

## Bugs found on the way

- The traced volume read black in the composite at the start: bricks were traced before the occupancy and the light list
  existed (valid, empty) and traced again later. Tracing waits for the occupancy scan now.
- Iron fences are walls in the occupancy: the lower floor of a facade behind one stayed dark while the upper floor (rays over
  the fence's level) was lit: a dark band at the floor line. The see-through fence bits.
- Window frames took the dark room's light: the wall clamp used the pixel's own square, a pane sits a hair inside the wall.
  The square in front of the surface (after the normal offset) now.
- NVIDIA's GLSL refuses `local_size_x = 4 * R` (a non-constant expression for it): a `BT` define.
- `flat` is a reserved word in GLSL (an interpolation qualifier): a variable named so failed every compute program at launch
  (voxel lighting off for the session) while the helper's check was piped through `tail` and hid the failure. The run helper
  now greps the console for "did not compile" / voxel light failures.
- Adding the cone uniforms to the inject pass dropped the line binding its output image: the inject wrote into whatever was on
  image unit 0 (the direct pass's lamp volume) and the sky stayed 0 (black outdoors at noon) for builds 35-37. Found with an
  offline harness (`tools/voxel/vxlrun.c`: a compute program on the real driver against synthetic or dumped textures,
  `occfile=` / `occ4file=` load a `devVoxelLightDumpAt` dump) that gave the right sky from the same shader and data.
- The surface cache's texture mapping first came from the StartShader draw's quad: a pooled TextureDraw's leftover vertices
  (pieces at random places, a strip with vertex 2 under vertex 0). The mapping is the composite's own placement computed on
  the game thread now; the StartShader draw carries only the depth texture and the chunk depth.

## Rigs

`harness/voxel/capab.py` (on / off pair of a devVoxelLightAlternate run), `views.py` (the dev views of a view-cycle run in a
grid), `ctimen.py` (the composite's GPU time per cost mask), `tools/voxel/vxlcheck.c` (compute and fragment programs through
the real driver headless; `--draw` makes radeonsi compile and print its registers), `tests/pzopt/VoxelLightShaderTest.java`
(every program on NVIDIA and Mesa), `devVoxelLightDumpAt` (the volumes read back as float32 with the mapping).
