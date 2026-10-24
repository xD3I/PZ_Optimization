# Ground cover by season, plus glints (2026-10-24)

Feature 2 of the ground / grass proposal (`~/pzopt-wt/ground-proposal.html`). Branch `ground-cover` (agent ground-cover,
coordinator session b8396cf2), merged into `ground-integ`. Off by default, in "Ground and grass": `groundScatter` (+
`groundScatterDensityPct`, `groundCoverPlacement`, `groundCoverAssets`, `groundCoverAsync`, `groundCoverFrost`,
`groundCoverWind`) and the glints (`glints`, `glintModel`, `glintPass`, `glintHdrPct`). Classes `pzopt.GroundCover`,
`pzopt.Glints` / `GlintGlsl`, `pzopt.WindLeaves`. Rig `harness/ground/cover/glint_rig.py`.

- **Ground cover:** a Horizon Zero Dawn-style rule graph on world-anchored hashes, built on a worker and baked into the chunk
  textures through ground-common's hook. Leaves under crowns, in each tree's colour, banked along curbs and in gutters;
  flowers in spring; litter by roads; frost in shade that melts in the sun. Tracks crush it; puddles and blood draw over it;
  nothing on built floors or indoors.
- **Glints:** sun, moon, torch, street-lamp and headlight sparkle on frost, snow and wet asphalt, drawn as tiny points over
  the floor and under the puddles, with an HDR copy past 8-bit white.

## Results

Desktop: RTX 4090, 5120x2160, power-saver profile, uncapped, empty options file. Job ids are queue jobs.

| What | Result |
|---|---|
| Ground cover, frame | 0: −3.7 ± 6.4 µs frame, −5.6 ± 3.4 µs GPU (gcv-cost-scatter-static 14600) |
| Ground cover, bake | +7 µs GPU per chunk-level bake (16.9 vs 9.7; gcv-cost-sections 14571) |
| Ground cover, build | game thread 17-24 µs gather + 37-56 µs submit per chunk; worker 93-210 µs per build; 0 synchronous builds |
| Streaming at 37 squares/s | 2,508 builds, 0 dropped, 0 stale draws; 80 pop-ins averaging 29 ms (max 193), 79 of them at world load (gcv-popin-fast 14655) |
| Wind-blown leaves (off by default) | +5.0 ± 4.1 µs frame, +7.3 µs GPU, ~2 µs of it the draw (14616; the `GL_DEPTH_CLAMP` version was +13.9) |
| Glints, nothing that can glint | 0.0 µs GPU; the game-thread check returns before gathering lights (14653) |
| Glints, frosty dawn | +4.2 µs frame, +4.9 µs GPU (14642; was 9.7 / 12.2) |
| Glints, worst case: 5,225 forced frost squares in full sun | +12.3 µs GPU with the default particle pass (14643) |
| Glints, worst real case: wet town road at night, zoom 2.5, two cars' lights, street lamps | game thread 4.0 µs (56.6 on the game thread, same build; was 579), worker ~57 µs on its own thread, GPU-bound frame +10-20 µs (gcv-cost-wetnight9 14727, -10 14732 order swapped) |

## Techniques, one by one

| Technique | Key / variant | Cost | Verdict |
|---|---|---|---|
| Rule graph scatter | `groundScatter` | 0 frame, +7 µs a bake | **kept** |
| Blue noise vs jittered grid | `groundCoverPlacement` | same | **blue noise default**; jitter pairs and gaps, kept as a choice |
| Game sprites vs drawn shapes | `groundCoverAssets` mixed / procedural / sprites | same | **mixed default**; drawn shapes lose the curb piles, sprites alone repeat a few heavy shapes |
| Worker build, staggered season re-bakes | `groundCoverAsync` | above | **kept** |
| Frost in shade | `groundCoverFrost` | in the bake | **kept**: pale rime at 7:36, gone by 9:00 in sun (14589/14590) |
| Deliot & Belcour 2023, full | `glintModel=db23` | +445 µs | off: cost |
| Same, single grid (exact under the iso camera) | `db23iso` | +99 µs | **kept** as the per-pixel model |
| JAMSI 2025 | `jamsi` | +248 µs | off: cost |
| Ghost of Yōtei-style sparkle | `sparkle` | +39 µs | dead end: shimmers on pan (rig flicker 0.78-1.0 vs ~0.06) |
| Wang / Deng / Holzschuch 2020, explicit | `explicit` | +87 µs | reference only: stablest, costly |
| Per-pixel pass | `glintPass=diamonds` | +101 µs GPU | a choice |
| Flakes as points | `glintPass=particles` | +12.3 µs GPU | **default**; 100 px differ from diamonds at dawn |
| Compute list of lit flakes | `compact` | +18.7 µs GPU, GL 4.3 | kept; falls back to particles |
| Fused into the composite | rig | +16 µs with no frost on screen | not shipped: breaks "0 when absent" |
| Sky term (Kneiphof 2025) | derivation | | not built: a sky-lit flake is ≤ ~7 % over the frost around it (ice Fresnel at 60°) |
| HDR highlights | `glintHdrPct` | runs with the normal pass (14618) | kept; the HDR look itself is unchecked (PNG) |

Temporal stability across zoom 0.5-3 (offline rig): particles flicker 0.050; db23 / db23iso ≤ ~0.08 up to zoom 2.

Fixes, in order:
- A ring of 3 data textures removed an implicit GPU sync worth up to 260 µs.
- `GL_DEPTH_CLAMP` on point draws cost ~50 µs; the depth is clamped in the shader instead.
- The game-thread queue went 191 → 48 µs (worst case) by keeping each square's static data per mask.
- Fixed per-frame costs cut: only the texels in use uploaded, sun / moon at 10 Hz, the lamp list at 4 Hz.
- Masks rebuild only when their inputs change: 166 → 3 µs.
- Light results cached per square and per chunk level, unchanged chunk levels copied whole: 180 → 57 µs.
- Street lamps never glinted: a lamp's light sits one level up in open air and the level filter dropped it (gcv-dbg-lamps
  14639; gcv-look-wet-lamp 14641 now shows the streak toward the camera).
- Headlights: the darkness gate stops glints on squares the game shows dark (14665).

## Glint worker pass (2026-10-24 evening)

| Run (job) | Change | Game thread a frame | Worker a frame |
|---|---|---|---|
| gcv-cost-wetnight5 (14703) | `glintAsync`: the per-frame build on its own thread, joined before the frame goes to the render thread | 6.4 (56.6 inline) | 53.3 |
| gcv-cost-wetnight6 (14705) | torches and lamps gathered on the worker (the sun stays) | 6.9 (3.0 waits) | 78.9 |
| gcv-cost-wetnight7 (14709) | out-of-date masks rebuild at most 12 a frame (a 5 % wet step rebuilt ~400 in one frame: a 4-5 ms hitch, inline too) | 4.4 | 81.7 |
| gcv-cost-wetnight8 (14712) | light keys snapped to 1/4 square instead of 1/64 | 4.3 | 61.9 |
| gcv-cost-wetnight9 / 10 (14727 / 14732) | final; order of the variants swapped (a +30 µs frame gap moved with the order: the route) | 4.0 / 4.4 | 56.6 / 57.7 |

Per frame: ~199 chunk levels walked, 2.7 relit, ~194 copied whole. A per-square relight under a moving light was not built:
1.4 % of levels relight a frame.

Mac (M1 Pro, GL 4.1 core): every program links, leaves on lawns, sidewalks and the lot with pale frost, wind leaves drawn,
glints on 3,334 frames (gcv-mac-look3 14725); sun glints from 9:30 (11° up; at 8:30 the sun was below the horizon). Cost at
the 60 fps vsync: frame −6.8 ± 6.4 µs, game thread 14.1 µs, worker 48.7 µs, render thread ~18 µs glints / ~1 µs wind (Apple's
timer queries read 0) (gcv-mac-cost2 14741).

Motion (`devCapture` 1:1 60 fps, `harness/ground/cover/motion.py`: consecutive frames aligned, then diffed):

| Runs | Scene | On | Off |
|---|---|---|---|
| gcv-motion-on / off (14721 / 14723) | dawn bench walk | 210 px changing over 49 frames (134 the player's idle); of 314 bright px, 254 steady, 12 blinking | 122 px |
| gcv-pan-on / off (14730 / 14731) | 10 km/h pan past frosty lawns | jumps 1,166 ppm, bright 9.89 | 1,198 / 9.07 |
| gcv-panforce-on / off (14734 / 14735) | same, every outdoor square frosted | 1,222 / 9.60 | 1,255 / 9.05 |
| gcv-panforce-x20 (14739) | glints 20× brighter to count them | 46 points a frame, 97.0 % still there next frame (worst 10 %: 94.6) | |

## Open

1. The glint worker costs ~57 µs a frame off the game thread (49 on the Mac): the walk over ~200 chunk levels and the copy of
   ~2,800 squares' records. The legacy GL 2.1 Mac path switches glints off.
2. Twinkle under moving headlights at night not captured.
3. Sun glints are sparse: the camera is fixed and Kentucky's sun never reaches the mirror direction; only steep facets glint.
4. Glints use at most 8 lights (torches and headlights first, then lamps, then taillights); a crowded scene can drop lamps.
5. The wet-lamp look used forced wetting, which wets the grass too; in play only roads get the wet mask.
6. Short pop-ins at world load (29 ms average).

Test: `GroundCoverTest` (24 programs on NVIDIA and Mesa). Agent notes: `~/pzopt-wt/ground-cover/build/notes.txt`.
