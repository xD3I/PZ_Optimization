# Grass with blades (2026-10-24)

Feature 4 of the ground / grass proposal (`~/pzopt-wt/ground-proposal.html`, published as the "Ground and Grass Proposals"
artifact). Branch `grass-blades` (agent grass-blades, coordinator session b8396cf2), merged into `ground-integ`. Key
`grassBlades`, off by default, needs `foliageSway=true` (the grass mask comes from Sway's bake attachment).

Near the player at close zoom, the grass painted into the chunk textures grows real blades, generated on the GPU from Sway's
grass texels. Each blade takes its colour from the composited painting at its root, so lighting, AO, sun / cloud shadows,
pixelLight and HDR carry over and the art style stays the game's. Further out, and when zoomed out, it is the baked painting
with sway, at 0 µs.

## Cost at the defaults

Defaults on top of `foliageSway=true`: compute path, 4 segments, clumps, waves, tufts, translucency, trails, cut, wear, feet
push, `grassBladeGenMs` 4. Measured on the desktop: RTX 4090, 5120x2160, power-saver profile, ~500 grass squares while
walking a lawn.

| What | Cost |
|---|---|
| GPU at ~1000 fps (blades regenerated every 4 ms) | 18 µs |
| GPU at ≤ 240 fps (regenerated every frame) | 29 µs |
| Game thread | 3.5-5 µs a frame |
| Off or zoomed out | 0 µs (the hook returns before any work or GL call) |
| Floor | the hardware draw, ~14 µs (raster-bound: still 19.5 µs with rasterisation skipped) |

Field walk, no frame-tail regression: `gb-walkon-20261024-190215` (40 s, 20 zombies, 1 s in-run on/off) against
`gb-walkoff-20261024-190243`. Mean cost +21.5 µs a frame, GPU +29 µs.

| Half | p99 | p99.9 | frames > 8 ms |
|---|---|---|---|
| Blades on | 2.34 ms | 3.42 ms | 3 |
| Blades off | 2.35 ms | 5.01 ms | 10 |

The only long frames are at load and exit.

## Techniques, one by one

"Rig" is the offline replay of a dumped lawn (`tools/GrassRig.java`, `build/lawn` t12, 505 squares; full draw 25.6 µs
median before `grassBladeGenMs`).

| # | Technique (key / knob) | Cost | Evidence | Verdict |
|---|---|---|---|---|
| 1 | GPU generation: compute + indirect Bezier strips (Ghost of Tsushima, GDC 2021) | GPU 25.5 µs, game thread 6.2 µs (428 squares) | `gb-cost1-20261024-181824` | kept |
| 1a | Per-chunk grass cache (GroundChunk slot) | game thread 87.8 → 3.2 µs | same | kept |
| 2 | Voronoi clumps | 22.5 µs with clumps off | rig | kept |
| 3 | Sway's gust bit-identical; Tsushima waves + sheen (`grassBladeWavesPct`) | in the timer noise | rig: travelling glints, mean colour shift 0.07/255 | kept |
| 4 | Root colour from the composite (AO, shadows, pixelLight, HDR carry over) | 0 extra | `gb-dusk-20261024-184031`, `gb-night-20261024-190111` | kept |
| 4a | Translucency at low sun (`grassBladeTranslucencyPct`) | ~1 µs | rig, dusk | kept |
| 5 | Wear from GroundState, Sway push, lasting trails, melee cut buffer (Ghost of Yōtei) | ~1 µs | rig | kept |
| 5a | Tufts (`grassBladeTufts`): tall blades on `e_newgrass` | in the noise | rig, dusk | kept |
| 6 | Depth test against the composite + feet push (`grassBladeFeetPct`) | vertex shader only | `gb-feet-20261024-184828`, `gb-feet2-20261024-185420` | kept |
| 7 | LOD by shrink + hash thinning (stochastic dither); no flattening of the baked grass needed | n/a | alpha-to-coverage unavailable (no MSAA) | kept |
| 8a | Vertex LOD (`grassBladeLodPx`) | 22.5-23.6 µs | rig | off: little gain, worse look |
| 8b | Regenerate every N frames (`grassBladeGenEvery`) | GPU −10 µs | `gb-cost3-20261024-185824` | off: 4-frame colour lag under a moving torch at 60 fps |
| 8b' | Regenerate by wall time (`grassBladeGenMs` 4) | GPU 29.0 → 18.1 µs, paired frame −5.4 ± 3.2 µs | `gb-genms-20261024-191422` | **kept, default** |
| 8c | Instanced draw vs one strip | equal (25.6 µs) | rig | strip kept |
| 8d | NV mesh shader | 27.6 µs, identical image | rig | off (raster-bound) |
| 8e | Compute software raster | 95.2 µs | rig | dead end: the resolve over 3.1 Mpx costs 3× the draw |
| 8f | Half-res layer + upsample (`grassBladeLayerDiv`) | 43.0 µs | rig | dead end: blobs, the composite alone ~14 µs |
| 8g | Per-chunk impostor / cached layer (`grassBladeLayerEvery`) | 25.6-27.6 µs | rig | dead end: the composite still runs every frame |
| 8h | Tile early-out by class | 3.2 µs game thread | chunk cache | kept (a GPU class-map version would lose the tone and tuft bits) |
| 8i | Persistent visibility feedback (`grassBladeFeedback`) | 28.7 µs, skips 2 % of squares | rig | off |
| 9 | Mac GL 4.1: vertex-only path (root pass + one strip) | desktop rig 28.7 µs, image bit-identical to compute | `gb-mac2/3/4-20261024-19*` (jobs 14567/14569/14573) | kept as the Mac path |

The first Mac run (`gb-mac-20261024-191837`) failed to compile: a `texture2D` token in `GroundState.GLSL` made CoreGlsl add
bias-argument wrappers, which Apple refuses in a vertex shader (fixed in 63b306dd, guarded by the test). On the M1 Pro the
blades-on halves were faster in all three runs (paired −0.47 to −0.95 ms). The off half does no work, so this is the Apple
driver, not a saving; the cost there is not measurable. Earlier vertex-only attempts: a screen copy (25-140 µs) and
instanced points (28 µs for the root pass alone).

## Torch shadows of the blades (2026-10-25)

With `pplShadows` on, pixelLight's torch shadow mask is marched in the scene depth after the composite, and the blades have
already written their depth by then, so every blade near the player cast its own shadow into the beam. The maintainer asked
for an option, off by default: `grassBladeTorchShadows` (live, menu "Grass blades: cast torch shadows"). Off,
`PixelLight.beforeGrassBlades` (called from `GrassBlades.afterComposite` once the blades will draw) queues the shadow pass
ahead of the blades and `afterComposite` skips its own. The march then sees the ground under them. Blades still take the
shadows of fences, walls and furniture through their root colour. Pass mode (`pplMode=pass`) keeps the old order. Not yet
measured or captured in a run; the order change adds no work.

## Blade flicker while walking (2026-10-25)

The maintainer reported the blades flickering while walking, worst at night under the torch. Their video
(`~/Videos/Project Zomboid/Video_2026-10-25_02-15-59.mp4`, build eca12709-dirty with `grassBladeTorchShadows=false`, zoom
0.5) was decoded as a 1:1 luma crop round the player. AV1 smooths single-pixel blinks, so the one-frame flash count reads ~0
there. The temporal second-difference energy is concentrated on the torch-lit lawn: 1.06 lit against 0.13 dark, uniform over
the beam's interior. Teleport walks did not trigger it; the real walk (`explore=walk`, 8189..8196,11449) with the
maintainer's options file, night 1.15, torch on, does. Branch `blade-flicker`, merged into master.

**Metric** (`harness/ground/blade-flicker.py --alternation`, `devGrassAlternate=1000`: blades A/B every second on one walk,
1:1 `devCapture` crop round the player): the robust flash rate counts one-frame blinks over 6/255 that survive every ±1 px
shift, per 1000 torch-lit pixels. "Paired" compares each A second with the B seconds either side (± SE). A B half that only
switches a feature off reads ~0.7 high by itself, so −0.7 means no change.

**Cause.** The game draws the ground shifted each frame by the camera's sub-pixel correction (`fixJigglyModels`, a fraction
in [0, 1)). The blades' screen mapping had no such term, so while walking they slid up to a pixel against the ground and
their own root colour. Shifting them by the fraction (the first fix) removed only a third. A sweep of the shift's size
(`jiggleScale` 0 / 0.5 / 1.5 / 2 / 3: 12.0 / 9.5 / 7.2 / 7.1 / 11.4) showed the fraction was not the ground's motion. A
per-frame log beside the capture (`gb165-jlog`, job 14916, 681 frame pairs) settled it:

- **Zoom 0.5:** FBORenderChunk samples the chunk texture with mag filter NEAREST at every zoom but 0.75. The ground does not
  move by the fraction: it jumps a whole pixel when the fraction passes 0.5, `ceil(fix − 0.5)`. That matched 98.8 % (x) and
  99.1 % (y) of the frame pairs; the plain fraction matched 43 % and 77 %.
- **Zoom 0.75** (`gb165-jlog075`, linear filter): the ground does move by the fraction (fit 1.06 x, 1.04 y, residual sd
  0.13 px).

**Fix.**

- **`grassBladeJiggle`** (default on): the blades move by whole pixels where the chunk textures are point sampled
  (`GrassBlades.groundPointSampled`), by the fraction elsewhere. The depth keeps the full fraction. Two floats a frame.
- **`grassBladeAntialias`** (default on): the blades' edges blended. It does nothing at 0.5 or 1 and cuts the blinks at 0.75
  (paired −2.9 ± 0.3), where the ground really moves by fractions. ~3 µs GPU.
- Dev knobs kept for later A/Bs: `jiggleScale`, `jiggleSnap`, `aaPx`, `rootFilter`, `wind`, `partialTest`.

| Run (job) | Zoom | B half | Robust A / B | Paired A − B |
|---|---|---|---|---|
| gb165-snapold (14919) | 0.5 | old build | 2.14 / 12.51 | −10.63 ± 1.05 |
| gb165-snap (14917) | 0.5 | first fix (fraction) | 1.88 / 8.56 | −6.54 ± 0.62 |
| gb165-snapoff (14918) | 0.5 | blades off | 1.89 / 1.56 | +0.36 ± 0.46 (no excess) |
| 14921 | 1 | blades off | 2.52 / 2.44 | −0.08 ± 0.39 |
| 14920 | 0.75 | blades off | 7.13 / 5.06 | +1.83 ± 0.45 (residual) |
| 14924 | 0.75 | old build | 7.23 / 8.66 | −2.03 ± 0.35 |

With the old jiggle mapping, removing the wind and every other blade motion (`gb165-frozen`), the depth test, the
partial-square test, the visibility feedback or the root colour changed little to nothing. Neither does
`grassBladeTorchShadows`: with every light path off (god rays, HDR, voxel light, torch source) the blades flickered the same.

**Jev** (`blade-flicker.py --alt`):

- Zoom 0.5 pairs: verdict fixed (0.66; improved 0.33).
- All pairs, zoom 1 and 0.75 included: improved (0.52; fixed 0.37).
- Odds that A flickers clearly more than blades off: 0.13 at zoom 0.5, 0.05 at 1, 0.20 at 0.75.

**Cost** (fixed against the old build in one run, zoom 0.5, torch walk; `gbcost2-vsync` 14928 / `gbcost2-novsync` 14929):

| Run | Blade pass GPU | Frame | GPU / game / render thread |
|---|---|---|---|
| 165 fps cap | 39.6 vs 36.5 µs (+3.1) | +0.4 ± 0.2 µs | 34 / 21 / 24 % (cap-bound) |
| Uncapped, 526 fps | 31.5 vs 28.1 µs (+3.4) | median +10.6 µs | 94 % (GPU-bound) / 42 / 81 % |

The game thread shows no cost (−2 %, noise). The +3 µs is the edge blending.

## Open

- Thin dark contour lines on lawns come from another feature's post-process. They show with blades off and are drawn over
  the character (`gb-duskab-20261024-184431`). Not investigated.
- Zoom 0.75 keeps ~+1.8 robust blinks per 1000 lit pixels over blades off (thin blades under real sub-pixel motion); a
  wider edge filter (`aaPx` 2) did nothing.
- pixelLight's `pplJiggle` and every `Ssr.View` user (CarGlass, CloudShadow, GodRays, Mirrors, Ssr) still use the
  continuous jiggle fraction: at point-sampled zooms their reconstructed points are up to half a pixel off the ground they
  shade. Not checked for visible effects.
- The Mac path has no trails and no cut (no compute), and its GPU timers read 0.
- Blades write no DLSS motion vectors.
- The feet push is modest: the walker tramples the grass at their feet anyway.
- After ground-common's `GroundState.GLSL_130` lands, the local `.replace("texture2D(", "texture(")` can go.

Test: `GrassBladesTest` (8 program links on NVIDIA + Mesa). Agent notes: `~/pzopt-wt/grass-blades/build/notes.txt`.
