# Ground without the grid (2026-10-24)

Feature 1 of the ground / grass proposal (`~/pzopt-wt/ground-proposal.html`). Branch `ground-tiling` (agent ground-tiling,
coordinator session b8396cf2), merged into `ground-integ`. Keys `groundHexTiling`, `groundHeightBlend`, `groundMacroPct`, off by
default. The work runs only in the chunk-texture bake, through ground-common's natural-floor program variant. No override
class is touched.

Natural floors (`blends_natural_01/02`, grass, dirt, sand, clay, gravel) are sampled with a stochastic tiling of the game's own
art, so the square lattice and the per-square banding disappear. Material boundaries become organic lines with grass growing
over dirt, and a world-scale brightness / dryness drift follows season and temperature.

## Results

Measured on the desktop: RTX 4090, 5120x2160, power-saver profile.

| What | Result |
|---|---|
| Frame cost, every key on, uncapped static field | +0.9 ± 0.4 µs frame, +0.1 ± 0.6 µs GPU at 852 fps (`gt-cost12-20261024-193120`): 0 in the noise |
| Bake cost of a natural-ground chunk texture | ~145 µs GPU median vs 62.5 stock. Tiling's own shader ≈ +8 µs; ~75 µs is ground-common's floor variant, which costs the same with a no-op patch. **Open, handed to ground-common.** |
| Re-bakes on the 120 km/h drive | 13,262 on vs 13,096 off: the difference is the one-time re-bake of ~230 textures when the patch switches on. Both held 156.7 fps (the cap). |
| Drive tail | p99.9 12.7 vs 12.6 ms, no frame > 33 ms; p99 7.0 vs 6.6 ms (one run each: `gt-drive-on-17-20261024-200009`, `gt-drive-off-17-20261024-200135`) |
| Repeat score, grass (lower is better; texture floor 0.11) | stock 0.222 → hpb 0.176 |
| Zoom 0.5 and 3 with `spriteFilter=sharp` | no seams, lines or chunk-edge breaks (`gt-zoom0.5-18`, `gt-zoom3-18`) |
| pixelLight, AO, relief, sway, puddles, forced snow | no artefacts (`gt-inter20`, `gt-snow21`); pixelLight and relief read the baked colour, so they follow the patch |
| Mac GL 4.1 core | the shaders link and bake with 0 failures (`mac-gt-mac19-20261024-201759`); the screenshot never arrived, so the look there is unseen |

## Techniques, one by one

Variants are switched with `devGroundTilingMode` / `devGroundCycle` tokens.

| Technique | Evidence | Verdict |
|---|---|---|
| hpb: histogram-preserving blend (Heitz & Neyret 2018 with Mikkelsen's practical weights), cell 150 % | grass repeat 0.222 → 0.176; dirt's per-square banding gone; stock's square lattice gone at zoom 3 (`gt-grass-11-20261024-192605`, `gt-dirt-11-20261024-192435`) | **kept, default mode**, contrast kept |
| Mikkelsen hex-tiling (`hex`), 2-sample stochastic (`two`), texture bombing (`bomb`), Voronoi (`voronoi`), `lap` | repeat 0.174-0.185, all better than stock; bomb close to hpb but softer | dev modes, off |
| Wang-tile choice among the game's variants | worse than stock offline | off |
| Offline unwrap of the floor diamond to a square | exact only at 4× storage (16,384 texels a square) | off; iso layout kept |
| Rotated height probe (hex) | within noise, one more fetch | off |
| Height blend v1 (per-piece geometric strips / halves) | only wobbled the staircase | replaced |
| Height blend v2 (`groundHeightBlend`): class-map field, bilinear material indicator over the four square centres + rank bias (grass > dirt > clay > gravel > sand) + per-material world fbm + the art's height; tone channel from ground-common (913c906e) | organic dirt / grass and sand / dirt lines through the corners, grass over the dirt edge, tone patches blend too (`gt-blend10-20261024-191548`, `gt-cost15-20261024-194940`) | **kept**, off by default |
| Macro drift (`groundMacroPct`): world-scale brightness and season / temperature dryness | subtle at 0.12 brightness, raised to 0.18 at 100 % | **kept** |
| Sprite lookup grid (one fetch) | variant bake 125 → 105 µs (`gt-cost13/14`) | kept |
| One program for every piece | no cost change (`gt-cost15` vs `gt-cost16`) | kept (fewer programs) |
| Sprite table in a texture | frees 160 uniform vec4 for GL 4.1 | kept |
| TexTile (CVPR 2024) as the metric | no released code or weights | cheap proxies used instead: grid energy, repeat, contrast, sharpness, plus visual checks |

Two bugs fixed on the way:
- **Bright lines along every grass edge.** The game's texture pages are premultiplied and floors bake with
  `glBlendFuncSeparate(ONE, ONE_MINUS_SRC_ALPHA, ...)`; the patch returned straight colour, which brightened every edge piece.
  It now returns colour × alpha, and the rule is in ground-common's patch API docs.
- **No visible test spot.** Real natural ground near the bench spots is under overlays and trees, so the new rig
  `ground_field=` (harness/CLAUDE.md) lays bare zones.

## Open

1. The floor variant's bake overhead (~75 µs a bake over stock) is ground-common's; the leading, unconfirmed suspect is the
   class-map upload stalling inside the bake.
2. The Mac look needs one picture.
3. The `ground_field` rig leaves trees standing on squares flagged solid.
4. Default values for the menu: hpb at cell 150 % suggested.

Tests: `GroundTileArtTest`, `GroundTilingShaderTest` (12 links on the NVIDIA driver and Mesa). Agent notes:
`~/pzopt-wt/ground-tiling/build/notes.txt`.
