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

## Open

- Thin dark contour lines on lawns come from another feature's post-process. They show with blades off and are drawn over
  the character (`gb-duskab-20261024-184431`). Not investigated.
- The Mac path has no trails and no cut (no compute), and its GPU timers read 0.
- Blades write no DLSS motion vectors.
- The feet push is modest: the walker tramples the grass at their feet anyway.
- After ground-common's `GroundState.GLSL_130` lands, the local `.replace("texture2D(", "texture(")` can go.

Test: `GrassBladesTest` (8 program links on NVIDIA + Mesa). Agent notes: `~/pzopt-wt/grass-blades/build/notes.txt`.
