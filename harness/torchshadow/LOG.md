# Torch shadows loop (2026-10-25, worktree torch-shadows, branch torch-shadows from c371fb95)

Goal: `pplShadows` production quality (geometry-faithful, stable, cheap), every candidate technique behind
`pplShadowMode` / `pplShadowFilter`, profiled and judged on fixed captures, then a default.

Runs: desktop queue session `787c0a58-torch` (`torch-shadows`), builds as snapshots `build/tree-b<n>`
(`--install <snap>`), helper `ts.sh` in the session scratchpad. The flip was disconnected from 10-24 23:30.

## Baseline: what the march does (pplShadowMode=march)

`PixelLight.SHADOW_FRAG`: after the chunk composite, a half-resolution pass marches each pixel's ray towards the torch
through the *scene depth* over `pplShadowSquares` (4) squares, samples on a 1/8-square screen grid, a thickness window
[0.00016, 0.0035] depth (0.07..1.5 squares behind the front surface counts as solid), 3x3 depth-aware blur, read by
the NEXT frame's composite reprojected. Limits by construction:
- one frame late (the torch's previous position; a turn smears);
- the thickness window: a post is taken as 1.5 squares deep along the view, so its shadow is the shape of a slab, not
  of the post, and anything thinner than the window behind a front surface is "solid";
- only what the scene depth shows: occluders hidden behind another chunk's sprites, cut away, or off screen cast nothing;
- half resolution + blur: soft, blocky edges at max zoom; 4-square reach.

## Technique table

Desktop = RTX 4090 at 5120x2160, carport scene at zoom 0.25 (most of the screen in the beam: the worst case for any per-pixel
read); costs are GPU us a frame from the in-run timers (composite incl. the map build, minus the same run's off variant).
Mac = M-series, macOS GL 4.1 core through pzopt.CoreGl, 1920x1200, frame time p50 (no GPU timers there). The flip
(Radeon 890M, Mesa) stayed disconnected the whole loop: no flip numbers; every program links and validates under Mesa
(llvmpipe) in TorchShadowMapShaderTest.

| # | technique | desktop cost | Mac | visual verdict | runs / frames |
|---|-----------|--------------|-----|----------------|---------------|
| 1 | march (old default): half-res screen-space march, previous frame | mask 94-101 us + read | - | slabs (thickness window), no off-screen / hidden casters, one frame late | ts1-pole-march, ts9-pole-hiz `mont-hiz.png` |
| 2 | map, VS cells (b4) | build 860-1350 us | - | faithful, floor dashes until b6 | ts4-pole-map, ts4-pole-alt |
| 2a | map, GS cells (b6) | build 476-552 us | links, runs; +0.17 ms p50 | same as compute (0.13 % px differ) | ts6-pole-alt, ts7-pole-alt, tsmac13-ab |
| 2b | map, compute cells + indirect draw (b7) | build 183-222 us | n/a (GL 4.1) | same | ts7-pole-alt, ts10/ts11 |
| 2c | map cache (kept while torch / casters / bakes stay) | 0 build when standing; native torch cadence while walking (44 builds / 1800 frames) | 49 builds / 3000 frames | identical | ts7-yard-alt, ts11-walk |
| 2d | off-screen / culled chunks as casters | ~0 (0 extra casters on the bench spots) | - | shadows from chunks outside the screen | b8 |
| 2e | per-corner auto thickness (posts as deep as wide) | +~400 us build vs per cell on GS, ~0 on compute | - | wedge widens physically from a post | ts6-pole-alt |
| 2f | smooth silhouette (marching-squares edge cells) | +4 us build | same path (GS) | removes the cell staircase teeth: straight edges | ts11-pole-smooth `teeth.png` |
| 2g | 2x finer cells (SM_FINE) | +235 us build | - | halves teeth (superseded by 2f) | ts10-pole-filters |
| 2h | corner-min (dither theory) | ~0 | - | no change: dropped | ts10-pole-filters |
| 3 | filter hard | ~pcf | - | aliased | ts4-pole-alt, ts7-filters |
| 3a | filter pcf (bilinear 2x2 gather) | read ~40-55 us (cached map: +5-55 over off) | - | crisp, clean with 2f | ts7-filters, ts13-default-yard |
| 3b | filter pcss (9+9 gathers) | read +176-215 us | - | contact hardening | ts7-filters, ts10-pole-filters |
| 3c | filter soft (PCSS 4+4, rotated grid) | read +85-140 us over off (+80 over pcf) | +0.17 ms p50 incl. map | contact hardening like pcss, no visible difference | ts10-yard-face, ts13-default-yard |
| 3d | EVSM (moments, blur, 1 tap) | build +170 us, read = pcf | - | edges like pcf, no bleeding visible: no gain | ts9-pole-evsm `mont.png` |
| 3e | MSM (4 moments) | not run (same build as EVSM, heavier read) | - | links on driver + Mesa | - |
| 4 | map-ism (splat of the screen's depth, next frame) | composite 470 us | - | speckle / holes, one frame late | ts2-pole-ism |
| 5 | Hi-Z march (min-depth pyramid traversal) | mask 584-615 us (6x march) | - | same slabs as march, twice the reach | ts9-pole-hiz |
| 6 | sss (Bend-style, full res) | mask 440 us | - | same answer as march | ts9-pole-sss |
| 7 | voxel torch visibility (voxelLight) | ~33 us a retrace | - | posts / rails cast nothing (per-square occupancy) | ts9-pole-voxel |
| 8 | parametrizations dp / cube / planar | not run in game (link + validate only) | - | - | - |

Validation of the default (map + compute/GS cells + cache + off-screen + smooth + soft): zoom 0.25 -> 2.25 under FSR1 and
TAAU (ts9-map-zoomfsr `zoomcrops.png`), indoors with stairs and walls (ts9-indoor `mont2.png`), walking (ts11-walk), the
placed objects (ts13-default-yard `mont.png`, tsmac13-ab `mont.png` on the Mac).


## Notes per technique

### 1. march (baseline, pplShadowMode=march)
See above. Cost and look from run `ts1-pole-march`.

### 2. map: omnidirectional torch shadow map from the chunk textures' depth (pplShadowMode=map)
`pzopt.TorchShadowMap`. Game thread (`collect`, from `PixelLight.beforeComposite`): the chunk textures of this frame's
composite whose squares are within `min(reach + 1, pplShadowMapSquares)` of the torch and whose levels overlap the
torch's level and the one above, with each texture's texel -> world mapping (the composite's own placement: A = x - y,
B = x + y - 6z from the texture uv, C = x + y + 2z from the depth) and the texel rectangle that can hold casters.
Render thread (`build`, in `Gl.render` before the composite, i.e. after this frame's bakes): a DEPTH32F N x N
octahedral map ("down-centred": the lower hemisphere fills the inner diamond, no seam on the floor), one instance
per `pplShadowMapCell` x cell texels, the cell's four corner depths -> world points -> map; a corner that is empty or
farther than 0.35 square-diagonals behind the cell's front corner is snapped to the front (the cell's edge stays on the
object, no curtain to the floor behind). Cells across the upper hemisphere's folds are dropped. The composite reads the
map in the same frame (`pplMaskN(P, n)` with a normal offset of `pplShadowBiasPct` map texels): no lag, casters off
screen and hidden behind other chunks' sprites count, the light's own position is exact.
Filters (`pplShadowFilter`, in-run via the dev cost bits SM_HARD / SM_PCF / SM_PCSS): hard (1 texel), pcf (one gather,
bilinear 2x2 compare), pcss (blocker search 3x3 gathers inside the lens's cone, penumbra = lens (d - db) / db, 3x3
bilinear taps that wide: contact-hardening), evsm / msm (below).

### 2b. caster thickness (pplShadowThickPct, pplShadowThickAuto)
The chunk textures hold only front surfaces: a round post lit from the side is a sheet seen edge-on and casts a sliver
50-85 % as wide as it should. Each cell is drawn as a box from its front surface back along the view (x + y + 2z falls
8 a level: 1.633 per square) by its thickness: auto = the narrower of the surface's runs across and up the screen
through the cell (texels until the depth jumps or the texture is empty, 8 steps of a cell each side), times an elliptical
section factor sqrt(1 - p^2) for the cell's place p across the run (a round post is deepest on its axis), capped at
pplShadowThickPct (50) hundredths of a square. A post is as deep as it is wide, a rail as deep as it is tall, walls and
floors get the cap (their back is never lit by the torch in front of them). One draw of a 14-vertex cube strip per cell
(the first 4 vertices are the sheet, so thickness 0 draws 4). In-run A/B: SM_SHEET (sheet), SM_THICK_CONST (the cap for
every cell).

### 3. map-ism: imperfect shadow map splatted from the scene depth (pplShadowMode=map-ism)
After the composite, every second window pixel of the scene depth as a point from the torch into the same octahedral
map (point size from the pixel's footprint and distance), read by the NEXT frame's composite with that frame's torch
(shifted to the new origin square). The ISM idea (Ritschel et al. 2008) with the screen's depth as the point cloud:
cheap to build but one frame late, only what the screen shows, holes where points are sparse.

### 4. evsm / msm: filtered moment maps (pplShadowFilter=evsm | msm)
The map's cells also write moments into an RGBA32F target (EVSM: e^(40 d), e^(80 d), -e^(-5 d), e^(-10 d); MSM: d, d^2,
d^3, d^4), a separable 5-tap Gaussian, and the composite reads ONE bilinear tap: EVSM = the lesser Chebyshev bound of both
warps with light-bleeding reduction (0.25), MSM = Hamburger 4-moment reconstruction (moment bias 3e-5). The composite is
overdraw-heavy, so a one-tap read can be cheaper than PCSS's 18 gathers even with the blur passes.

## Results

Scene for every first pass: the carport poles (save `Sandbox/2026-09-30_00-33-03`, 02:00, torch on, zoom 0.25, `--mode bench
--route-seconds 16 --flag route=W:0.1 --flag speed=0.1 --flag turn=30 --flag hold=14`, `devCapture=7,12,2,100,crop=1000:200:3100:1700`,
empty user options + `pixelLight=true pplShadows=true devPplTiming=true gpuSections=true`). Desktop RTX 4090 at 5120x2160.
NOTE: the first submits (jobs 15057-15096) lacked `--mode bench` / `--route-seconds` and ran as unbounded verify runs;
the coordinator stopped 15088 and cancelled the rest. Every run since is bounded (ts.sh refuses a run without both).

- `ts1-pole-march` (job 15117, b1, run `ts1-pole-march-20261025-215346`): march mask pass 94 us/frame median, composite
  662 us. Look: pole shadows as wide slabs from the base, the torch's cone edge, bushes shadowed.
- `ts2-pole-map` (job 15145, b2, run `ts2-pole-map-20261025-230129`): works (map builds, pole casts a shadow line from its
  base) but 2.28 M cells a frame (2-texel cells on zoomed-in high-res chunk textures, 10 casters): composite+map 4.6 ms.
  Acne: dotted pattern on the poles' lit faces (box side faces of a curved surface's neighbouring cells) and a grazing-angle
  floor region shadowed (constant 0.004 bias at ~15 deg grazing; the floor itself in the map). Fixed in b3/b4: own-floor
  cells skipped, indexed boxes, adaptive cells (24 a square), box gap behind the surface, slope-scaled normal offset + bias.
- `ts2-pole-alt` (job 15147, b2): in-run variants, frames named by mask (`harness/torchshadow/frames.py`, montage
  `alt-montage1.png`). Sheet (no thickness) has no dotted acne on the poles, auto / constant thickness have it (box steps):
  confirms the b4 box gap. The composite timer's per-mask line needs 1500 frames a mask (none in 18 s at ~25 fps): 300 in b4.
  turn=10 swept the torch: variants are not the same view; next alternation runs use turn=0.
- `ts2-pole-ism` (job 15148, b2): composite 470 us (splat not timed: own timer in b4). Look: the pole casts the same thin 1:1
  line as the map, but the floor and table top are speckled with point-splat holes / acne (moire), as expected of ISM from
  every second screen pixel. Same frame comparison: `ts2-pole-ism-.../ism-vs-march.png` (march: the near pole's shadow is a
  slab-wide dark region over the rug; map and ISM: a thin line, the rug lit).
- `ts4-pole-map` (job 15183, b4, run `ts4-pole-map-20261026-000103`): adaptive cells cut 2.28 M to 570 k cells a build; map
  build 860-1350 us GPU (`torch shadow map build gpu:`), composite (its timer brackets the build too) 1730 us. Still far too
  dear. Look: the near pole now casts a wedge that widens away from the torch (the torch is ~0.8 square from it: a 0.15
  square post makes a ~0.5 square shadow 2 squares on, physically right; b2's "thin line" was the edge-on sheet). Faults:
  short dark dashes along the pavement's seams, and the map dump (`capture/torchmap-9s.pgm`) shows a diamond ring of floor
  cells ~6 squares out at the floor's height and up to 0.15 below it: floor cells were not skipped where a corner had been
  pulled onto the front from an empty / behind texel (its height then comes from another texel's depth, off by up to 0.05-0.15).
- `ts4-pole-alt` (job 15184, b4, masks off / sheet / box / const / hard / pcss at 1 s each): composite incl. build m65536 (off)
  420 us, sheet+pcf 1155, box pcf 1810, const 1740, hard 1745, pcss 1800. Montage `mont1.png`: the floor dashes in every map
  variant, sheet's wedge narrower, box / const alike, pcss softens the far edge.
- b6 (job 15198): the floor test counts only a cell's own corners (`FLOOR_EPS` 0.05 above the torch's floor, one-sided);
  a pulled corner sits on the plane of the cell's three own corners when it has them (`PULL`); geometry-shader cells
  (one point a cell, the box emitted as six strips only for casting cells, `pplShadowMapGs`); thickness per cell corner
  (`pplShadowThickCorner`: neighbouring cells share their back points); the map filter compiled into the composite
  (`PPL_SM_FILTER`, dev cost bits pick a program variant: the uber-shader with every filter's loops cost registers).
- `ts6-pole-alt` (job 15203, b6, masks 0 / SM_VS_CELLS / SM_CELL_THICK / SM_PCSS / SM_OFF): map build (own timer, per mask)
  GS + corner thickness 945 us, GS + cell thickness 552, VS cells (b4 path) 1088, GS + corner + pcss 969 (same build).
  Composite minus build: about 490 us with the map read vs 510 off: with the filter compiled in the read is ~free (b4's
  uber-shader read cost ~450 us). The floor dashes are gone in every variant (montage `mont1.png`). The GS only halves
  the per-cell build; per-corner runs double it (4 corners x 4 runs x 8 fetches, each corner recomputed by 4 cells).
  The pole shadow's teeth follow the pole sprite's own dithered edge texels (visible on the pole itself): faithful.
- b7 (job 15212): compute cells (GL 4.3, `pplShadowMapCompute`): 8 x 8 cell tiles, the 9 x 9 corners' depth and thickness
  once in shared memory, each casting (cell, view) appended to a record list (4 x vec4: the front points from the torch,
  thickness), one `glDrawElementsIndirect` of the 36-index box per record (VS reads the list); the floor and empty cells cost
  one thread. Dev bits SM_GS_CELLS / SM_VS_CELLS pick the older paths. `pplShadowMapCache`: the map is kept (no build) while
  the torch, the caster list and the dev bits stay and none of those chunks baked (`PixelLight.bakeBegin` ->
  `TorchShadowMap.chunkBaked`), checked on the game thread with a collect serial so a kept map is always the previous one.
- `ts7-pole-alt` (job 15213, b7, cache off, masks 0 / SM_GS_CELLS / SM_VS_CELLS / SM_OFF): map build compute 183-195 us,
  GS 476, VS 1063 (the compute path is 5x cheaper than b4's). Composite incl. build: compute 667 vs off 421, i.e. the whole
  map shadow (build + read) ~246 us a frame against the march's ~334 us (mask 94 + its read in the composite) with the
  standing torch rebuilt every frame. The three paths give the same picture (0.13 % of pixels differ, along edges).
- `ts7-yard-alt` (job 15214, b7, cache on, standing): composite m0 834 vs off 809 us (+25 us: the kept map is read only);
  pcss +150 us. The placed objects were not in the torch's cone (facing W): next yard run with `--flag face=0`.
- `ts7-filters` (job 15232, b7, cache on, masks 0 pcf / SM_HARD / SM_PCSS / SM_OFF, 4 x 300 frames): composite off 731,
  pcf 786-793 (+55), hard 805 (+70, within the run spread of pcf), pcss 925 (+195) us. PCF is the read to ship; PCSS's
  contact hardening costs ~140 us more at 5K for a softer far edge.
- Mac (jobs 15218, 15227, 15233, M-series GL 4.1 core through pzopt.CoreGl, b7): GL 4.1 has no compute; the GS cells link
  and run (`geometry-shader cells` in the init line), no GL errors. Frame time per mask equal (uncapped 304 fps, p50
  1.70 / 1.66 / 1.72 ms for map / off / VS cells: the map costs nothing measurable there). The Mac's bench save is a car
  on a road (no carport scene there), so its captures show the torch on the road only: visual parity not checked on Mac.
- b8 (job 15234, build + 128 shader programs ok on the driver and Mesa): `pplShadowMapOffscreen`: the chunks round the torch
  within reach that the composite did not draw this frame (off screen, or culled) add their textures of the torch's level
  and the one above as casters (their FBO for the current zoom, when one is kept); the log line counts them.

### 5. hiz: the march over a min-depth pyramid (pplShadowMode=hiz, b9)
Hi-Z tracing as SSR does it (Uludag, GPU Pro 5; McGuire & Mara 2014): a R32F pyramid of the scene depth's nearest value
(level k = 2^(k+1) window px, 7 levels, one reduction pass a level), the half-resolution mask pass walks each pixel's ray
in window px, climbs a level while the deepest point of the ray inside a cell is not behind the cell's nearest surface
(nothing there can occlude), drops a level otherwise, and at level 0 tests the window pixels it crosses with the march's
thickness window. Every pixel tests whole pixels (no sample phase), reach `pplShadowHizSquares` (8). In-run A/B in march
mode by dev bit SM_HIZ (67108864; SM_MARCH 134217728 the other way in hiz mode); the mask pass timer now keeps per-mask
sums (`pixel light shadow mask gpu alt:`).
- `ts9-pole-hiz` (job 15239, b9): mask pass march 94-101 us, Hi-Z 584-615 us (6x). Frames (`mont-hiz.png`): the same
  slab-shaped shadows as the march (the thickness window is unchanged), reaching twice as far. Rejected: the long reach
  is not worth 0.5 ms at 5K, and it keeps every screen-space limit (one frame late, slabs, only what the screen shows).
  The divergent stackless loop at half resolution is the cost; the map gives the reach for free.

## Validation of the map path (b9)
- `ts9-map-zoomfsr` (job 15241): map (compute, cache, off-screen casters, pcf), zoom 0.25 -> 2.25 in eight wheel steps,
  torch turning 12 deg/s, `live_set` upscaler=fsr1 at 13 s and taau at 19 s. Crops of a fixed world size per zoom
  (`zoomcrops.png`): the pole wedges keep their shape at every zoom and under both upscalers, no acne, dashes, blockiness or
  swimming; the shadow is drawn at the composite's full resolution before the upscale (the upscalers see a finished
  picture). Composite incl. map 450-920 us depending on zoom (more screen in the beam when zoomed in). The map is kept
  through the turn (omnidirectional: only the torch's position, the casters and bakes rebuild it): fewer than 600 builds in
  4800 frames (the stats line only counted builds; b10 counts every frame). NOTE: `live_set` writes the key into the
  `userOptionsFile` (empty-options.ini held `upscaler=taau` afterwards); it was emptied and made read-only before any later job.

## Filters, continued
- `ts9-pole-evsm` (job 15244, b9, pplShadowFilter=evsm, cache off, masks 0 evsm / SM_PCF / SM_PCSS / SM_OFF): map build with
  moments (RGBA32F target + separable blur) 355-380 us against ~190 for depth only; composite incl. build evsm 1013-1033,
  pcf 1040-1047 (the same build), pcss 1132, off 605 us. EVSM's one-tap read costs the same as PCF's gather; its blur is
  5 taps in map space, so the edges look like PCF's (montage `mont.png`: evsm / pcf / pcss / off nearly alike, no visible
  light bleeding at 0.25 cut). Verdict: no gain over PCF for +170 us of build; rejected as default.
- Close-up (`teeth.png`, 1:1 at half the 5K capture): PCF edges far from the caster show teeth ~10 px (one caster cell, 1/24
  square, times the caster-to-receiver magnification, 3-4x here). PCSS hides them with the penumbra (which widens exactly
  with that magnification) for +140 us. b10 adds `soft` (PCSS with 4 + 4 taps on a fixed rotated grid, a lit pixel stops
  after 4 gathers) and the dev bit SM_FINE (48 cells a square) to measure both answers in one run.

### 6. sss: Bend Studio-style screen-space shadows (pplShadowMode=sss)
- `ts9-pole-sss` (job 15245, b9): full-resolution wavefront march, mask pass 440 us (march 95), composite 625 us. Frames
  (`mont.png`, sss vs the march of ts9-pole-hiz at the same spot): the same screen-depth answer as the march: the near
  pole's long shadow across the carport floor (the map's wedge) is missing in both, the counter / AC unit cast a solid
  black slab (the thickness window), one frame late. Rejected: 4.6x the march's cost for the same limits.

### 7. voxel: voxel lighting's traced torch visibility (voxelLight + voxelLightTorchShadows, pplShadows off)
- `ts9-pole-voxel` (job 15246, b9, torch turning 15 deg/s): torch visibility retrace median 33 us (101 retraces), the
  frame's voxel work ~1-4 us mean, composite 755 us (the voxel terms included). Frames (`mont.png`): the carport poles and
  rails cast nothing (the occupancy is per square / brick: a 0.15-square post is not in it), only walls and the roof's
  coarse blocks shade. Cheap and stable, but not geometry-faithful at all for the objects the request names; it stays the
  voxel lighting's own coarse occlusion. Rejected for torch shadows.
- `ts9-indoor` (job 15249, b9, Riverside police station, zombies off, 01:00, lights off, zoom 0.5, torch turning 20 deg/s,
  map vs off in one run): composite map 585-602 vs off 564-578 us (+25 us: the turning torch keeps its map, the stairs and
  rails are cached casters). Crops (`mont2.png`): the staircase's steps and balusters throw a stepped shadow onto the wall
  behind them and the rail posts small ones on the floor; no light through walls, no shadow on the floor from the cut-away
  upper level, nothing left after the torch turns away.
- `ts10-pole-filters` (job 15257, b10, cache off, 6 variants x 1 s): composite incl. build / build alone: pcf + corner-min
  864 / 221, pcf point corners 903 / 207, soft 1001 / 218, pcss 1032 / 213, pcf with 48 cells a square 1111 / 455, off 605 us.
  Reads (composite - build - off): pcf ~40, soft ~180, pcss ~215 us (at zoom 0.25 most of the 5K screen is in the beam: the
  read runs for every composite fragment there, overdraw included). Close-up `teeth.png`: corner-min changes nothing (the
  teeth are not the sprite's dithering), finer cells halve them (+235 us build), soft and pcss remove them. So the teeth are
  the cell staircase of a slanted silhouette in the chunk texture: b11 cuts edge cells as marching squares does
  (`pplShadowSilhouette=smooth`, dev bit SM_SMOOTH_FLIP) instead of drawing them whole; corner-min dropped.
- `ts10-yard-face` (job 15258, b10, placed wooden post, lamp pillar, chair, table, fence row in the beam with face=0, cache
  on, pcf / soft / pcf point corners / off): `mont.png`. Each object's shadow has its own shape: the lamp pillar's post
  and the A-frame struts of its foot, the chair's stacked planks as stripes, the table top and legs, the fence boards; the
  post a thin line. pcf edges are crisp, soft hardens at the contact and widens away from it (the lamp pillar's shadow tip).
  Composite: pcf +40-55 us over off, soft +110-135 (cache on: builds only when the dev mask changes, 87-94 builds in 3000
  frames). CPU on the render thread ~52 us a build (ts10-pole-filters, every frame built; the yard's 450 us a build is the
  programs' first link amortised over few builds).
- `ts11-pole-smooth` (job 15263, b11, cache off): composite incl. build / build: pcf + smooth 968 / 222, pcf whole cells
  980 / 218, soft + smooth 1041 / 213, soft whole cells 1030 / 213, off 706 / - us. Close-up `teeth.png`: with the edge
  cells cut as marching squares does, the diagonal shadow edge that was a sawtooth is a straight line under plain pcf, and
  the pole's foot shadow on the grass is clean; the cut costs ~4 us of build. Adopted (`pplShadowSilhouette=smooth`, all
  three cell paths).
- `ts11-walk` (job 15264, b11, soft, cache on, zoom 0.5, walking W 6 squares at 0.4/s, map vs off every 2 s): composite map
  650-740 vs off 593-711 us (+50-70). 44 builds in 1800 frames: the native hands the torch's position over at its own
  lighting cadence (not every frame; `torchSource` off), so the map rebuilds only when the light itself moves and stays in
  step with the lit pool; with `torchSource` on (the lens position every frame) it would rebuild every moving frame (~220 us
  GPU + ~52 us render-thread CPU at zoom 0.25).
- Mac `tsmac11-yard` (job 15267, b11, on foot via exit_car + start=8004,11210, placed post / lamp pillar / chair / table,
  soft, masks 0 / SM_OFF / SM_SMOOTH_FLIP; the Mac's run-mac.sh has no --source-save, job 15265 failed on it): GS cells,
  init line ok, frame time p50 4.02 / 3.85 / 4.01 ms (uncapped). But no `torch shadow map: N builds` line in 7000 frames
  and the beam's brightness shows no step at the mask flips: the map was never built there (no frame with the player's
  torch as the shadow light, or no collect). b13 adds `torch shadow frames=` / `map collects` to the ppl stats line to see
  which.

## Defaults (b13)
`pplShadowMode=map`, `pplShadowFilter=soft`, `pplShadowSilhouette=smooth`, `pplShadowMapCompute` / `Gs` / `Cache` /
`Offscreen` on. `pplShadows` itself stays off by default (as pixelLight is); the menu entry lost "(experimental)" and got a
"torch shadow edges" chooser (soft / pcf, next launch).
- `ts13-default-yard` (job 15272, b13, only pixelLight + pplShadows set, masks default / SM_GS_CELLS / SM_PCF / off):
  init line `compute cells, thickness per corner, filter soft`; composite default 884-900, GS cells 872, pcf 806-818, off
  798-803 us (cache on, standing). Frames `mont.png`: lamp pillar with its strut feet, chair planks, table, fence boards,
  clean edges, GS = compute.
- Mac `tsmac13-ab` (job 15274, b13 defaults, GS cells, map vs off): frame time p50 3.96 vs 3.79 ms, p99 11.4 vs 11.8; the
  table and pillar shadows are there (`mont.png`). `tsmac13-yard` (15273): `torch shadow frames=2670 (map collects 2670)`,
  78 builds. The b11 Mac run's missing builds were not reproduced. Render-thread CPU a build is ~1.5 ms there (GS through
  the CoreGl shim) once the first link is amortised: fine at the native torch cadence, worth watching with torchSource.

## Flip (Radeon 890M, Mesa 26.2.5 radeonsi, 1920x1080), merged master 7efdce2e, 2026-10-26
- `tsflip-yard` (job 15287, bench save on foot at 8004,11210, empty user options, torch held, masks 0 soft / SM_PCF / SM_GS_CELLS /
  SM_OFF at 1 s each): composite incl. map soft 866-951, sharp 841-923, GS cells 868-931, off 822-893 us (7 windows): soft
  +30-60 us, sharp +15-40, GS cells about as compute. 102 builds in 10,800 frames (the kept map otherwise); render-thread CPU
  ~250-335 us a build after the first (the run averages 1.7-1.9 ms with the first build's program links in it). 360 fps mean
  uncapped, p99 5.4 ms. The objects stood beside the beam in this run (face=0 there faces south-east), so no look check.
- `tsflip-yard3` (job 15294, objects on the beam's axis): the lamp pillar casts a shadow as wide as its post, straight away from
  the torch down the screen onto the grass (`flip-tsflip-yard3-*/diff.png`: soft vs off, the difference in red); no acne, holes
  or light bleeding; soft, sharp and GS cells give the same picture as on the desktop.
