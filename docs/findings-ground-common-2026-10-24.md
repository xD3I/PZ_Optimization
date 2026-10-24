# Ground and grass, the shared part (2026-10-24)

The base of the four ground / grass features (`~/pzopt-wt/ground-proposal.html`). Branch `ground-common` (agent
ground-common, coordinator session b8396cf2), merged into `ground-integ`. Override edits: `docs/override-edits.md` "Ground and
grass, shared part". API summary: `~/pzopt-wt/ground-common/build/ground-common-api.txt`. With every ground key off the cost
is 0 by construction: each hook is one static boolean read.

| Part | What |
|---|---|
| `pzopt.Ground` | key mask (`K_HEX`, `K_MACRO`, `K_HEIGHT`, `K_SCATTER`, `K_GLINTS`, `K_TRACKS`, `K_BLADES`), `section()`, `onFrame` |
| `pzopt.GroundHash` | world-anchored hashes, bit-identical in Java and GLSL |
| `pzopt.GroundTiles` | floor classes from the game's sprites (natural materials, tone, `F_ROAD` / `F_WALK` / `paved()`), a per-chunk class map (RG8: class + tone) in GLSL (`pzgClassAt`, `pzgToneAt`, `pzgPavedAt`) |
| `pzopt.GroundBake` / `FloorPatch` | the natural-floor program variant in the chunk bake: patches add a texel function (`defines()`, `overlayObjects()` for level-0 grass objects, `floorsToo`), seam strips, `FloorPass` phases (incl. after snow) |
| `pzopt.GroundState` v2 | a GPU-authoritative per-chunk field (wear, depth, wet, crush), rules GLSL pluggable, stamps batched at 30 Hz, decay, persistence beside the save (read-back by a compute pack into one buffer, IO thread region files) |
| `pzopt.GroundMovers` | one gather of characters, vehicles and animals for every feature (`NEAR` list, event list) |
| `GroundChunk` slots | per-chunk caches on `IsoChunk`, reuse-safe |
| Rigs | `devGroundAlternate` / `devGroundAlternateKeys` / `devGroundCycle` (`harness/ground/ab.py`), offline shader rig `harness/ground/rig.py`, `harness/ground/bakecost.py`, `pzopt.Scene` `snow=` / `ground_map=`, dev views `devGroundView` |

## Cost work

Desktop: RTX 4090, 5120x2160, power-saver profile, uncapped, no MangoHud.

| What | Before | After | Runs |
|---|---|---|---|
| Natural-ground chunk bake, variant with a patch that returns stock | 137 µs (stock 62.5) | 65.5 (stock 61.4) | gt-cost16 → gc-int-final-20261024-210743 |
| Same, tiling's default look (hpb+b+m) | 145 | 89.3 (79.9 after tiling's own pass) | same; gt-bc14 |
| Class-map upload in every level-0 bake (~20 µs a bake, into a texture earlier bakes sampled) | 78.8 (no-op probe) | 64.5: a game-thread copy, only changed texels, persistent PBO ring; 152 of 1,097 bakes uploaded (5132c6dd) | gc-noop, gc-noop2 |
| Dynamically indexed `const int` tables in patches (NVIDIA puts them in local memory, filled every fragment) | most of tiling's overhead | GroundBake rewrites every patch's tables into functions over packed constants (90fca545) | gc-int-static |
| State read-back per flush (render thread) | 1,270-1,989 µs (`glGetTextureSubImage` per chunk) | 19-38 µs: a compute pack + one buffer copy (`devGroundReadback=pack`, 30b8ba4c); `glReadPixels` 1,677 | gc-rb-pack-20261024-202234, gc-statev2-rb |
| Stamping, 200 idle zombies | 8.55 µs (tracks' first read: start-up loads + a 256-slot scan every frame) | 0.60 GroundState + 0.81 GroundMovers; the scan runs only while a slot waits (5f155c7d) | gc-crowd-split-20261024-212351 |
| Stamps unbatched vs 30 Hz | GPU 7.41 µs a frame | 0.18 | gc-statev2-cost2-20261024-184808 |
| 120 km/h drive with tracks: reloaded chunks re-baked their state | 1 %-low 146-150 vs 155 | no state re-bake before a chunk's first bake, loaded state at low priority (1e5f092f): = off | gtr-drive120-on4a |
| Walking horde (tracks' pass, 811b66ec, in ground-common's files) | Louisville 25.3 µs | 10.3 µs; worker gather, 30 Hz game-side work | `findings-ground-tracks-2026-10-24.md` |

Tests: `GroundCommonTest` (72 programs on the NVIDIA driver and Mesa), `tools/ground/gsrun.c` (the state compute on driver +
Mesa), byte-identical read-back paths. Agent notes: `~/pzopt-wt/ground-common/build/notes.txt`.

## Open

1. The pack read-back has one 1.9 ms GPU outlier; its usual GPU time matches the get path.
2. GroundState's game-side work under the Louisville horde (~7.9 µs) would need a worker to fall under 5 µs.
3. GroundState needs GL 4.4 compute: off on the Mac (tracks off there; tiling, cover and blades run).
