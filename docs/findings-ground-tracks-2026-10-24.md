# Ground that remembers (2026-10-24)

Feature 3 of the ground / grass proposal (`~/pzopt-wt/ground-proposal.html`). Branch `ground-tracks` (agent ground-tracks,
coordinator session b8396cf2), merged into `ground-integ`. Off by default; keys `groundTracks*` (`groundTracksLook`,
`groundTracksPom`, `groundTracksGrass`, `groundTracksWater`). The state is ground-common's `GroundState` (a per-chunk GPU
field, stamped by `GroundMovers`, decayed, saved beside the save); the look is baked into the chunk textures.

- **Cars:** tyre ruts with tread prints in mud, sand and snow, with raised edges.
- **Feet and bodies:** footprints, body imprints and drag marks.
- **Trodden grass:** the game's tall-grass sprites lie flat and go to straw over tracks; before, they hid the tracks completely.
- **Water in ruts:** ruts fill with the game's own puddle water and its raindrop rings.
- **Recovery:** grass in ~20 game hours, mud keeps its ruts for days, snow refills while it snows.
- **Persistence:** saved and reloaded across sessions, keyed by the save (runs write only the bench copy's folder).
- **Light:** ruts are relit by the sun, and by the moon at night. When the key light moves, the chunks with tracks re-bake at
  low priority.

## Results

Desktop: RTX 4090, 5120x2160, power-saver profile.

| What | Result |
|---|---|
| Drawing the tracks into a chunk texture | 5.1 µs per draw against an 89.9 µs chunk bake (`gtr-cost3`) |
| Trodden grass per chunk bake | +44 → +14 µs (one texture read on untouched ground) → +4 median / +7 mean with the floors left on the stock programs (`gtr-cost6..8`, ground-common 1ce6b4cd) |
| Stamping, 200 idle zombies | 0.60 µs GroundState + 0.81 µs GroundMovers a frame, steady state (the 8.55 µs first read was the start-up mean; ground-common 5f155c7d) |
| 120 km/h drive at the 157 fps cap | first 1 %-low 146-150 vs 155 off: reloaded chunks re-baked their state. After ground-common 1e5f092f: 1 %-low 154, p99 6.5 ms, p99.9 12.7 ms = tracks off (`gtr-drive120-on4a`) |
| Real car | 1,251 stamps over 40 tiles, two soft ruts (`gtr-car1`) |
| Night, torch + pixelLight + relief | ruts read as grooves in the cone (`gtr-night1`) |

## Techniques, one by one

| Technique / key | Evidence | Verdict |
|---|---|---|
| Relief offset (POM) along the view ray, `groundTracksPom` | `gtr-snow2`: the rut walls get depth; without, a flat smear | **on** |
| Relight, self-shadow march, cavity AO | `gtr-var1`: relight is most of the look; colour alone is nearly invisible | **on** |
| Mud / snow material rules (cut, raised edges, slope settling) | `gtr-rig6`, `gtr-snow2`, `gtr-car1` | kept |
| Decay per material | `gtr-decay2`, `gtr-decay4` | kept |
| `groundTracksLook=patch` (in the floor tile shader) | +8-16 µs a frame under constant rig re-bakes, same as bake; cannot draw on snow | off |
| `groundTracksLook=composite` (redrawn every frame) | 116 µs GPU a frame (`gtr-cost4`) | off, dev only |
| `groundTracksWater` | baked water reads grey with no ripples; the puddle pass is clearly better (`gtr-wet1`, `gtr-wet2-puddles`) | **auto**: puddle pass when ripples are on, else baked |
| Moon as key light | `gtr-moon1/2`: vanilla night is black; with `darknessFloorPct=40` the ruts get a moonlit rim | kept, 0 cost |
| `groundTracksGrass` | `gtr-grass2`: 20,088 grass objects processed, wheel lanes show through tall grass (`gtr-grass1` void: sway moved every plant) | **on** |

Fixed on the way: the patch variant got all-zero light (no relight); the bake's GPU section now sits in the render stream; the
drive regression above. Discarded: `gtr-var2-low` (the desktop recorder caught another window; every capture since is in-game).

## Open

1. Stamping cost with a horde that walks is unmeasured (the bench crowd stood idle). Back with ground-tracks.
2. Relief's baked sun mode applies the sun relief a second time on top of the tracks'. Back with ground-tracks.
3. Off on the Mac: the ground state needs GL 4.4 compute.
4. Tracks made in dirt before forced snow show as snow ruts; real snowfall refills them.
5. Relief infers the rut shape from the darkened colour; feeding it the real height is deferred.
6. Composite and stamp-only modes (dev) never re-bake, so trodden grass goes stale there.
7. Multiplayer: the state is per client, stamped from the movement each client sees, not synced.

Test: `GroundTracksShaderTest`. Agent notes: `~/pzopt-wt/ground-tracks/build/notes.txt`.
