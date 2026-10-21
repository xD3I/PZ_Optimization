# God rays: light shafts in haze and dust (2026-09-27)

Asked by the maintainer: "implement god rays, implement all the state of the art cutting edge solutions; secondary objective:
virtually 0 performance impact; don't discard any ideas, implement them one by one and profile them". Worktree
`../PZ_Optimization-godrays`, branch `god-rays`, on master 12b275f. Runs on the desktop (RTX 4090, 5120x2160) through the
queue, labels `gr-*`. Class `pzopt.GodRays`; offline rig `harness/godrays/` (a dumped frame through the real GLSL on this GPU).

Keys: `godRays` (live, off by default), `godRaysStrengthPct`, `godRaysHazePct`, `godRaysDustPct`, `godRaysPatchPct`,
`godRaysMethod`, `godRaysApertures`, `godRaysLocal`, `godRaysLocalPct`, `godRaysMotes`, `godRaysHazeMode` (live);
`godRaysHazeComposite` (chunk), `godRaysHazeAddPct` (25), `godRaysShadePct` (100), `godRaysFogShade` (lowres),
`godRaysFogFuse`, `godRaysApCull`, `godRaysApDepthTest`, `godRaysUnbindDepth`, `godRaysGlCache` (on), `godRaysLateDraw`,
`godRaysCoarse` (1: off), `godRaysLowCompute`, `godRaysBufferDiv` (4); `godRaysCellPx` (16), `godRaysSlicesPerLevel` (8), `godRaysLevels` (4),
`godRaysChunkBudgetUs` (400), `godRaysUpdateColumns` (0); dev `devGodRaysAlternate` (+ `devGodRaysAlternateAll`: every pass on / off, the cost rig), `devGodRaysSkip` (bits: 1
buffer, 2 screen tap, 4 volume compute, 8 fog shade, 16 light volumes' depth read, 32 light volume draw, 64 plain blend),
`devGodRaysTouchDepth`, `devGodRaysTiming`, `devGodRaysView`
(1 in-scatter x8, 2 lit surfaces, 3 transmittance, 4 facing), `devGodRaysDumpAt`, `devGodRaysHourSpeed`. Rig flag
`find=sunwindow` (the player into the room whose windows let the key light in the most).

## What god rays are in this renderer

The camera is orthographic (30 deg down, from the south-east), the world is a grid of squares with walls on their N / W
edges, floors per level, roof tiles, and sprite trees. The light (sun, or the moon at night: `pzopt.Sky`) is directional.
So:

- every screen pixel's view ray is a fixed world line: along it `u = x - y` and `v = x + y - 6z` stay constant (the
  composite's own mapping, `Ssr.View`), so a volume indexed by (u, v, z) holds each view ray as one column. That is the
  froxel grid of Wronski / Hillaire made exact: world-anchored (cells of 2^n world units, toroidal), no reprojection
  error when the camera pans, only the columns that come into view are new;
- the directional light's epipolar lines are all parallel (the sun's screen direction): the epipolar and rectified-space
  methods (Engelhardt & Dachsbacher 2010, Baran et al. 2010, Chen et al. 2011) collapse to scans along straight lines;
- the occluders are known exactly: walls, windows (sill to head), doorways, floors and roofs per square and level, and
  the trees' crown proxies of `ChunkAo`. A 2D DDA through that grid is an exact shadow test, no shadow map needed.

## Pipeline (method `volume` + light volumes)

1. **Occupancy** (game thread, per chunk, 11 us a chunk, budget 400 us a frame, nearest first): one R32UI texel per
   square and level, 256 x 256 squares x 16 levels round the camera (toroidal): floor, N / W edge type (wall, window,
   open doorway, nothing; a curtained or barricaded window is a wall, a closed door too), room flag + a 16-bit room
   hash, roof tile, crown density. Rebuilt when a chunk's geometry bakes again (a door, a window) and every 30 s near.
2. **Visibility** (compute, a thread per cell): DDA toward the light from the cell: an edge crossed at the ray's height
   blocks unless it is a window between sill and head or a doorway; a floor or roof crossed going up blocks; crowns
   attenuate with a leaf noise (the gaps between the leaves). R8 volume, 324 x 139 x 32 at 5120x2160, zoom 1.
3. **Integration** (compute, a thread per column, from the top down, Hillaire's energy-conserving step): R the outdoor
   haze's in-scatter (height-falling, the weather's density), G its transmittance, B the light on an indoor cell, A the
   room dust's in-scatter from the cell up to where the column leaves the cell's room (reset at every room change: a
   pixel sees only its own room's dust, not a cut-away room in front of it or the hidden floor above).
4. **Light volumes** (the windows and open doorways the light comes through into a room, CPU prisms, rasterised): each
   aperture (glass between sill and head, or the door's opening) extruded down the light's way to the floor or the first
   wall, 12 triangles; its outer side's light from a CPU DDA. Into an RG16F target: R += +/- max(w - w_surface, 0) per
   face (entering +, leaving -: the sum is the view path inside the prism in front of the surface: Mitchell's light
   shafts / the shadow-volume path-length trick), G += +/- the surface's facing to the light for faces in front of the
   surface (z-pass counting: the surface is inside, the sunlit patch, with exact edges). Replaces the volume's room terms.
5. **The haze on the picture**, riding passes that run anyway (every separate pass cost ~10 us on this GPU whatever it
   did):
   - clear air, mist, rain without the game's fog: **the chunk composite** (stock `chunkShader.frag` or pixelLight's
     programs, patched after the cloud shadows and before the reflections' scatter) taps the volume at each fragment's
     own world position (its chunk texture's depth): `c' = sqrt(c^2 (1 - k miss) + a C F)`, miss = (1 - T) - F the air in
     front that the sun does not reach (k 2.2), a = 25 % of the lit air's light (`godRaysHazeAddPct`; the full veil
     looked like smog). No scene depth read, no pass: ~2 us. What the composite does not draw (characters, cars, water)
     has no haze.
   - the game's fog: **the fog buffer** itself (`fogShade`): a blended fragment pass over the fog's quarter-size buffers
     (near and far rows) multiplies each texel's transmittance by the shade at its own depth, `a' = 1 - k (1 - a)`
     through the blend unit; the fog composite's depth-aware upsampling carries it to the screen (characters included).
   - `godRaysHazeComposite=screen` (and the other methods, froxel lights): the quarter-size buffer + one bilinear tap in
     the stock `screen.frag` (patched before the colour grade), as first built.
6. **Light volumes and local lights** draw straight into the world picture (dual-source blend), culled on the CPU to
   the rooms the game shows (their building cut away, the player's level), depth-tested, uploaded only when rebuilt.

Phase: Henyey-Greenstein g 0.7 (35 %) over an isotropic floor, the angle between the light's travel and the way to the
camera, constant per frame. Light colour: the sun through the air mass (Kasten-Young, Rayleigh-ish per channel), the moon
bluish and dim. Haze: 0.0004 per square x (1 + 45 fog + 12 rain + 6 dawn mist), quantised to 5 % steps (the morning mist
changed every frame and re-integrated the volume), height scale 1.2 levels; dust in rooms 0.03 per square.

## Bugs found on the way

- GLSL `%` is undefined for negative operands; the volume's cell indices (u = x - y) are negative: every write landed in
  a wrong column (no light anywhere indoors). `imod` is floor-based now.
- `screen.frag` includes `util/math`, which declares `float max(float, float)`: a user function hides every built-in
  overload of its name, so `max(vec3, vec3)` does not compile there (the first run's patch failed silently to stock).
- The room's in-scatter showed as smoke over the street seen through the cutaway and from the hidden upper floor: the
  dust now stays within the pixel's own room (the room hash).
- The sunlit patches landed on the window wall itself (its inner face looked up half a cell toward the camera, inside the
  shaft): the patch is weighted by the surface's facing to the light (normal from the depth of the pixel's neighbours).
- The light volume target's scissored clear ran before the colour mask was reset: with the game's colour writes masked
  the clear did nothing and the uninitialised storage (old UI shapes) showed as translucent rounded rectangles.
- The route's teleport put the player back after `find=sunwindow`; the rig now moves the route's origin.
- **The game's `ShaderProgram` re-assigns every `sampler2D` of a program to units 0, 1, 2... in enumeration order after it
  compiles** (`onCompileSuccess`): `layout(binding = N)` does not survive it (the colour grade's LUT does only because it
  is a `sampler3D`). The composite read two random game textures as the god ray buffer and the light volumes: a
  garbage block, a band and a +53-level brightening over 94 % of the picture (gr-room7/8). The units are set with
  `glUniform1i` on every use now. Found with composite dev views 5-7 (what the composite samples) and an offline
  replay of the real patched `screen.frag` with the in-game buffers (right there, wrong in game).
- The sprite renderer leaves `glAlphaFunc(GREATER, 0)` on: a pass writing alpha 0 loses every fragment (the light
  volumes' first version). Disabled in our passes; `GLStateRenderThread.restore()` puts it back.
- The harness screenshots are unusable for this effect: `shot-game.png` re-renders the scene at 4096x1728 (our
  per-frame mapping does not match it), `shot-desktop.png` caught the queue's own desktop notification over the game.
  Pictures come from `devCapture` (the presented frames) + `harness/godrays/capab.py` (on / off frames of an
  alternating run).
- Light volumes: thin or twisted prisms (a window with a wall right behind it, corners stopping at different lengths)
  got some faces' signs wrong from a per-face centroid test: the +/- sums did not cancel (garbage blocks). Faces are now
  wound consistently and turned outward as a whole by the prism's signed volume.

## Light volumes: the windows and doorways (`godRaysApertures`)

The shafts people mean by god rays in this game are the ones through windows into rooms. Each window or open doorway the key
light comes through from outside into a room is a convex prism: its aperture (the glass between sill and head, a door's
opening) extruded down the light's way until the floor or the first wall stops it (a CPU walk of the occupancy); its outer
side's light from a CPU walk toward the light (another building, a tree, the roof overhang); the cloud shadows' transmittance
at the window every frame.

| version | how | cost (game, 5K, 12 windows lit) |
|---|---|---|
| 1. shadow-volume path length | every face into an RG16F target, +/- max(w - w_surface, 0) (entering / leaving), z-pass count for the patch, a composite | 26.6 us + composite |
| 2. half-size target | the same at half size | 15.4 + 16.4 (composite pass) |
| 3. analytic (Cyrus-Beck) | only the camera-facing faces, the pixel's view column clipped against the prism's 7 half-spaces in the fragment: the path and the inside test directly, nothing to cancel | 21.5 + 16.4 |
| 4. **direct** (default) | version 3 drawn straight into the world picture with dual-source blending: `beam (1 - dst) + dst sqrt(1 + gain lit)` (a screen blend for the dust, the exact display-space multiplier for the patch); no target, no clear, no composite | **20.5 us** (~6 offline) |

Frame level, uncapped, the same room: 658.9 fps with god rays, 660.5 without (MangoHud), p99 2.6 / 2.7 ms (gr-ab-on/off).
Dust motes (`godRaysMotes`): one speck per world (u, v) cell of 0.12 x 0.24 at a hashed height drifting slowly, lit where it
falls inside a beam in front of the surface (a hash per beam fragment).

## Local lights (`godRaysLocal`, `godRaysLocalMethod`)

Torches and headlights (as the game hands them to the lighting native: position, direction, cone, reach), lamps and fires
(active `IsoLightSource`s) light the dust of their room or the haze of the street (fog, rain) around them. Not in daylight.
The 8 nearest whose airlight can show (its peak, a ray grazing the light: `sigma I 2 pi`, over 1.2 %).

- `analytic` (default): Sun, Ramamoorthi, Narasimhan & Nayar 2005: a point light's single scattering along a ray has a closed
  form, `(atan((z2 - zc) / h) - atan((z1 - zc) / h)) / h`, less a constant `1 / R^2` so it fades to nothing at the reach; the
  pixel's view column clipped to the reach, the light's level and, for a torch or a headlight, its cone (a quadratic). One
  quad a light over its reach's (u, v) box, screen-blended into the world picture.
- `froxel`: Wronski 2014's injection: each light's in-scatter per volume cell (`1 / d^2 - 1 / R^2`, cone) x the way to the
  light being clear (a point-to-point walk of the occupancy: walls, windows, floors, so lamp light stays in its room and
  leaks out through the windows), accumulated down the view columns of the lights' footprints (this frame's and last
  frame's: the old one clears itself), tapped by the god ray buffer.

## Methods compared (the haze: `godRaysMethod`)

Morning mist (8:00, sun 10 deg up in the east, `--flag fog=0.3`, church lot), 5120x2160, zoom 1, the quarter-size buffer
(1280x540), GL timestamps in game, medians over ~1,500 frames (runs `gr-haze8-*`). The in-game GPU runs at low clocks at the
240 cap: the same passes measure ~2.5-3x faster offline (`harness/godrays/rig.py --time`).

| method | what | buffer pass | look |
|---|---|---|---|
| `volume` | the rectified froxel volume: each view ray is a column, visibility and integration computed once (per light step / pan / change), one trilinear tap a texel (Wronski 2014 / Hillaire 2015, exact for an ortho view: no reprojection) | **28.7 us** | reference |
| `march` | a jittered 8-sample march of the visibility volume per texel, interleaved gradient noise rotated by the golden ratio, the history reprojected by the world column (Toth & Umenhoffer 2009; Playdead's INSIDE 2016) | 65.5 us | the same after ~8 frames |
| `minmax` | `march` with Chen et al. 2011's 1D min-max blocks (8 slices) integrated analytically where V is constant and outdoors | 67.6 us | the same; in 32-slice columns few blocks are constant (a shadow edge or a room in most), the block reads cost what they save |
| `epipolar` | Engelhardt & Dachsbacher 2010: coarse marches every 8 texels along lines parallel to the light's screen direction (all epipolar lines are parallel here: 172 x 743 samples), 1D interpolation between samples at the texel's own height, a march of its own at depth discontinuities | 63.5 us | the same, a little softer along the lines |
| `blur` | Mitchell 2007 / Sousa 2008: a bright pass of the picture, three 8-tap passes toward the light (1, 8, 64 texels apart) | 91.1 us (incl. the volume's 28.7) | wrong for this view: the dark roofs smear into the haze, streaks follow brightness, not light |

The volume is the cheapest and exact; the others pay per frame what it pays per change. Kept as options for comparison.

## Cost (desktop, 5120x2160, zoom 1)

### How it is measured

Run-to-run noise on this machine (±15-30 us between two identical uncapped runs) is bigger than what is left to
measure, so every number below is a **within-run A/B**: `devGodRaysAlternate=1000 devGodRaysAlternateAll=true` switches
every god ray pass (game-thread work stays in both halves) on and off every second, and `harness/godrays/abframes.py`
compares the in-game overlay's frame times (`pzopt-overlay.out`, one row per frame with its epoch) of the two halves:
frames within 150 ms of a switch are dropped, and each on second is paired with the mean of the off seconds on either
side of it (the route moves; a scene change hits both halves of a pair alike). Reported: the mean of those paired
differences ± its standard error. Uncapped (`uncappedFps=true`, `uiRenderOffscreen=true`), GPU-bound (GPU ~92 %, game
thread ~47 %, render thread ~51 %), so the frame time is the GPU's. 30 s routes give ±1.5-3 us in fog and clear air;
night scenes vary more (lights come and go) and take 60 s routes (`route=S:2`).

Scenes (all `start=8147,11507`, zoom 1): **fog** 8:00, `fog=0.3`; **clear** 8:00, clear sky; **night** 23:00, `fog=0.15`,
torch and lamps on; **room** 17:00, `find=sunwindow` (12 sunlit windows; `--skip 15`: the teleport streams chunks first).

### What it cost and what it costs now

| step | fog | clear | night | room |
|---|---|---|---|---|
| start of the day (all passes, the per-pixel fog tap, the quarter buffer + screen tap) | 56.5 | 74 ± 1.2 | 29.5 ± 12 | ~36 |
| fog tap reads a one-byte shade volume instead of the RGBA16F one | 50.4 ± 1.4 | | | |
| light volumes depth-tested against the scene (faces behind a roof never shade) | 46 | | | |
| light volumes uploaded only when rebuilt, their own VAO (render thread 10.4 -> 4.9 us CPU) | | | | |
| fog shade applied to the fog buffer per texel (compute) instead of per screen pixel | 25.4 ± 3.6 | | | 12.2 ± 1.4 |
| ... as a blended fragment pass (no graphics / compute switch, no barriers) | **20.6 ± 2.0** | | | |
| light gathering: pooled candidates, near-lamp list (game thread 9.4 -> 2.0 us) | | | | |
| local lights: tight footprints (16-gon of the reach, a torch's cone hull) 3.3 -> 1.3 Mpx | | | **~7 ± 10** | |
| outdoor haze in the chunk composite (no buffer pass, no screen tap) | | 17.1 ± 2.1 | | |
| ... shade look (shadowed air darkens, 25 % of the lit air added) | | 17.7 ± 1.3 | | |
| scene depth unbound after our passes | 18.0 ± 2.1 | 14.4 ± 1.4 | | |
| **final build, 60 s routes** (`gr-final-*`) | **11.9 ± 3.5** (0.7 %) | **13.8 ± 1.6** (0.9 %) | **9.7 ± 6.2** (0.4 %) | **12.3 ± 1.0** (0.8 %) |

Noon, clear (no haze worth drawing, 9 light volumes on screen): 6.8 ± 0.9 us (0.5 %): the light volume pass's fixed cost
(its drawing is ~2 us). At the 240 fps cap these are 0.3 % of the frame's time; the game thread's share is 2-6 us a frame
(occupancy, prisms, lights), the render thread's 3-4 us.

Measured and not kept (the numbers are what they changed):

| idea | result |
|---|---|
| world FBO / viewport read back once instead of `glGet` every frame | 51.0 vs 50.9: no sync cost on this driver (kept: free, and other drivers may sync) |
| stochastic texture filtering (Pharr et al. 2024) of the fog tap: a 2D array, one slice per pixel by interleaved gradient noise | 51.5 vs 50.4: the tap was latency-bound, not filter-bound; superseded by the per-texel shade |
| NV coarse shading (`GL_NV_shading_rate_image`, 2x2) on the light volumes and local lights | light volumes 11.3 us GPU both ways; night within noise: neither pass is shading-bound (`godRaysCoarse`, default 1) |
| the light volumes without their depth read / without the depth test | 16.9 / 17.6 vs 19.4 (clear): within noise |
| the first depth read of a frame (a one-texel read in the off halves too) | ~5 us of the clear scene's cost (62 -> 57) |
| 1/8 buffer instead of 1/4 (screen path) | buffer pass 32 -> 16 us; superseded by the chunk composite |
| compute vs fragment quarter buffer (screen path) | 63.5 vs 62.1: same |
| light volumes and local lights drawn late (at the screen composite, a colour-only FBO over the world picture) | clear 17.7 -> 28.9, fog 20.6 -> 30.9: the extra FBO switch costs more (`godRaysLateDraw`, off) |
| the light volume pass with nothing drawn / with a plain blend | 16.1 / 16.4 vs 17.8: the drawing is ~2 us, the rest is the pass being there |
| the scene depth unbound from our sampler unit after the passes (**kept**, `godRaysUnbindDepth`) | clear 18.0 -> 14.4: left bound, the rest of the world's draws into that depth saw a feedback loop |


## Floating orbs of light (the maintainer's report, 2026-09-27)

"Two floating lights on top of my vehicle" in save `Sandbox/2026-09-27_08-41-12` (a clear night, their own options):
reproduced with a Jev-directed walk, `explore=lights` (`pzopt.LightWalk`, `harness/lights/light-director.py`: out of the car,
once round it, then from lit lamp to lamp, circling and watching each), god rays on / off every 2 s, and
`harness/lights/orbs.py` (added luma round each light between still on / off frame pairs). With god rays on every lamp had
a white-hot ball at the bulb with a bloom over its surroundings (the bus shelter beside the car: +184 luma, 17 orbs).

Two causes, both fixed:
- the local lights' closed-form airlight goes as 1 / h (h the view column's distance from the light): a singular hot point
  wherever a column passes through a bulb or a headlight. The light now has a core (`godRaysLocalCorePct`, 1.5 squares:
  h -> sqrt(h^2 + h0^2), the froxel variant likewise): a soft falloff, no point (+101, still a veil);
- lamps in rooms (the bus shelter's roof makes it one) lit the sunbeams' room dust (0.03 a square, 20x the air): a ball of
  lit air round every indoor lamp. Local lights use the air's density indoors too; a lamp in clear air does not glow, and
  on a clear night nothing is gathered at all (game thread 37 -> 0.1 us a frame in that save).
After: clear night 0 orbs (the one pair over the threshold is the character changing pose), fog 0.4 (glows drawn,
991 kpx a frame) 0 orbs, lamps +34 at most as a soft haze, nothing over the car.

Found on the way: in play mode (the camera's zoom eases all the time in a car) the volume was reallocated and recomputed
651 times in 45 s, one column at a time; its size now moves in steps of 16 cells with hysteresis (1 reallocation).

## Shafts out of a window onto the street, roofed windows, doors with glass (the maintainer's report, 2026-10-04)

"Windows that are under a roof should not cast god rays: in my latest save at 20:00 the east-facing window projects a god
ray onto the street. Doors with windows should cast god rays too, with the same rule." Save `Sandbox/2026-10-02_10-30-09`
(their session: 20:06 to ~20:45, the sun 16 deg up in the WNW, light rain setting in).

Reproduced at the save's own time (20:45, rain 0.18), god rays on / off every second in one run and
`harness/godrays/contrib.py` (mean on frame minus mean off frame, interleaved): a shaft left the house through its east
window and crossed the yard to the street (runs `grroof-own-*`). It was the north bedroom window's light volume: the sun
almost grazes that wall, so the beam runs east along it into the east wall. A light volume's length came from one walk
from the aperture's middle at mid height, and that walk (1) let the ray through the east window (a window is open between
sill and head for the light) and on outdoors, and (2) once the ray reached the floor kept testing the edges of the level
below, i.e. none, so it ran to its 24-square cap. Every corner then went as far as its own floor allowed: at a low sun
14 squares, through the far wall or window and onto the street. The same bug let the living room's shaft run 1.1 squares
through an inner wall (cap 6.8 -> 5.7 squares at 20:00).

![god ray contribution, the three keys off (top) and on (bottom), same build, 20:45 in the save](media/godrays-east-window-leak-off-vs-on.jpg)

Fixes (all default on, launch keys):
- `godRaysApClip`: the volume ends where the first of 6 rays through the aperture (its two ends and middle, at mid height and
  at the head; walls are full height, so a ray from the sill meets nothing the one above it does not) meets a wall
  at its height or leaves the building (a square without a room), measured from the aperture's middle; each far corner
  is placed on that plane (or its floor, if nearer). `GodRays.reach`.
- `godRaysRoofRule`: a window or doorway between a room and the outside whose outside square has a floor or a roof surface
  one level up (a porch roof, an awning, a balcony, an upper storey) is a wall for the light, in the occupancy itself, so
  neither a light volume nor the haze volume lets light through it. Roof pieces standing on the wall line one level up are
  not a cover: gable walls (`walls_exterior_roofs_*`), trims (`roofs_accents_*`), rooftop furniture (the first version
  counted them and closed 123 windows round the save, nearly all of them ordinary; now 33-51, porches and overhangs).
- `godRaysDoorGlass`: a closed door the game lets the player see through (the `doorTrans` tiles: a window in the door,
  glass and sliding glass doors; not curtained, not barricaded, not garage doors or fence gates) lets the light through
  its glass. The glass of each of the 42 vanilla see-through doors was measured from the art (`harness/godrays/door-glass.py`
  -> `pzopt.DoorGlass`: the leaf's translucent texels, alpha 60..230 away from the leaf's anti-aliased rim, as a band along
  the edge and up the level); a mod's door gets a door-window band. The roof rule applies to doors as to windows.

Checks (desktop, 5120x2160, zoom 1; `--prop devGodRaysApLog=N` logs every aperture the light volumes consider, the roof
rule's closures and what covers them; `harness/godrays/aplog.py` prints them):
- the leak box east of the house: 11.2 % of its pixels lit by god rays with the three keys off, 3.0-3.3 % with them on
  (the rest is the ordinary haze over the yard); the open-air haze elsewhere differs between runs by up to 3 levels with
  the keys either way (cloud shadows drifting), so only same-build key A/Bs count;
- the west wall's closed glass door now casts its shaft across the living room (glass 0.27..0.73 across, 0.40..0.73 up);
- the porch house next door: its east window under the porch roof (`roofs_30_09_33` one level up) and the porch doorway
  are closed by the rule.

## Soft shafts and dust glints (the maintainer's request, 2026-10-04)

"Diffuse the god rays so they don't look as blocky and have a softer contact, add dust glint too." The light volumes were
cut exactly by the window frame: a uniform slab with razor sides and a sunlit patch with hard edges where it landed.

- `godRaysSoftPct` (100, Enhancements tab, live): across each of the volume's four sides (the frame's edges carried down
  the light's way) the light ramps over a penumbra W either side of the hard edge, W = 0.04 + 0.07 D squares (D the
  distance from the window's plane), and the shaft fades by exp(-0.015 D). The in-scatter along a view column is the mean
  of that cover at 6 midpoints of the column's stretch through the soft hull; the sunlit patch takes the cover at the
  surface (and none on the window's own wall), so its edges blur like the shaft's. The soft hull is the four sides moved
  out by W (`pl_i + K pl0 + w0`, exact because W grows linearly with D); the drawn faces are grown by 1.5 W on the CPU
  so they cover it, never below the floor (the faces are depth-tested against the scene).
  Two wrong turns: growing the drawn prism and ramping from its faces dimmed the shafts' core by a third (the half-spaces
  are built from the aperture's edges and the light's direction, so they never followed the grown corners); a dev view
  (`devGodRaysSoftView`: red the mean cover along the column, green the soft hull's stretch) showed it.
- `godRaysGlintPct` (100, live): the dust motes tumble: each catches the light now and then (sin^24 of its own pace,
  1.1-3.7 rad/s) as a bright core with a soft halo for a fraction of a second, inside the beam's cover. The motes' cell
  hash was `fract(sin(dot(cell, k)) * 43758)` on cell indices of ~1e5, which repeats along rows of cells: the specks
  drifted as a dotted grid (in the released build too). Now pcg3d on the integer cell.

![hard (top) and soft (bottom) shafts, same build, 20:00 in the save](media/godrays-soft-hard-vs-soft.jpg)

Cost (desktop, 5120x2160, the maintainer's save at 20:00, GL timers `devGodRaysTiming`, ~2,000 frames each): the light
volume pass 12.3 -> 14.3 us median; the other god ray passes unchanged.

## Black world on AMD / Windows (2026-09-27)

Since this release the composite patches went in for everyone, god rays on or off, and AMD players under Windows saw
the whole world black. Fixed in `3f8f7dc` (patches only with god rays on at launch, the game's samplers back on their
stock units); causes, fix and triage: `findings-amd-black-world-2026-09-27.md`.

## Doors (2026-10-18)

Open doors, their leaves and swing, people in the shafts, the night spill and the chunk haze patch fix on the stock
composite: `findings-god-rays-doors-2026-10-18.md`.
