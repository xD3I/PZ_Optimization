# Menu preview clips, one per visual setting (2026-10-20)

The maintainer asked: "record new clips of every single enhancement, and optimizations that change the game visually, use
jev to judge if the effect is clearly visible, think of the appropriate zoom levels". Session pz-optimization-b1, worktree
`~/pzopt-wt/menu-impeccable` (its uncommitted menu redesign; nothing committed).

## Method

- Every pair is two queue runs on this tree's build (`--install opt`, `--launcher direct`), the same save (the bench save
  copy), spot, hour, weather and route; only the setting differs. Both sides read an empty options file
  (`-Dpzopt.userOptionsFile=/home/diegov/pzopt-wt/menu-clips-empty.ini`: Config defaults, every Enhancement off) and pin
  `upscaler=off hdr=false hdrAuto=false overlay=false`, `hide_ui=true`; the setting goes on as `--prop`. Helpers:
  `/home/diegov/pzopt-wt/menu-clips-sub.sh` (one run, waits) and `menu-clips-pair.sh` (an off / on pair, labels
  `mc-<name>-off` / `mc-<name>-on`).
- Footage: the game's own frames (`pzopt.FrameCapture`, `devCapture=<start>,<secs>,24,<scale>[,crop=...],ram`), 1:1 screen
  pixels in a crop (`scale` 100 + `crop=`) for pixel- and room-scale effects, or the whole screen GPU-scaled to 20 %
  (1024x432) for world-scale ones. `harness/menu-gifs.py` kind `capture` builds each GIF frame from route time `after` +
  j/24 s, the off frame matched to the on frame within 0.35 s, so both sides show the same moment. No `--record`: 71 GB
  were free on /home and the captures are the frame-aligned source (SDR, 8-bit RGBA, `<run>/capture/frames.rgba`).
- Zoom: pixel-level effects (sprite filtering, relief, AO, wet blood, car glass) at zoom 0.5-1 and a crop shown at 1:1 or
  1.5:1 in the 512x216 clip; room-scale (per-pixel light, torch source, mirrors, props) zoom 0.5-1 with a 2-3x crop;
  world-scale (cloud shadows, sun shadows, reflections, tree cutaway) zoom 1 to max with a 3-5x crop or the whole frame.
- Jev: `harness/clip-judge.py <off run> <on run> --crop ... --after ... --feature "..." --expect ...` rebuilds the clip's
  frames at 512 px wide exactly as menu-gifs.py does and measures differing-pixel shares (> 16 / 32 / 64 of 255), mean luma
  difference and both lumas, 8x8 SSIM, where the difference sits (3 x 3 grid), each side's motion (frame-to-frame change),
  detail (Laplacian) and the alignment of the two sides (gradient correlation); Jev answers `visible` (p) and `kind`
  (clear / local / motion / subtle / none / misaligned). `visible=yes` needs p >= 0.6 and a kind other than subtle / none /
  misaligned. Every verdict was also checked by looking at a frame pair (`--png`). The verdict JSON sits in the on run's
  dir as `clip-judge.json`.

## Inventory and results

Clip shown before this pass in brackets ("wrong" = a generic or unrelated clip).

| Feature (keys) | Clip before | New clip | Scene, hour, weather | Zoom / crop | Runs (off / on) | Jev | Re-shoots / notes |
|---|---|---|---|---|---|---|---|
| Per-pixel lighting (`pixelLight`, `ppl*`) | torch (the blocky-lights fix) | `ppl` | night lot 8162,11502, 23:00, clear, lamps + hand torch, turning | 1, capture crop 1536x648 at 1:1, whole | mc-ppl2-off / -on | yes 0.81 clear | 1: mc-ppl-off (8147,11521) thrown out before its on run: a dark interior under a hover outline |
| Torch source (`torchSource*`) | torch | `torchsource` | the same lot, 01:00, hand torch + hurricane lantern, pixelLight on in both | 0.5, 1024x432 at 1:1 | mc-tsrc2-off / -on | yes 0.97 clear | 1: mc-tsrc at zoom 0.75 no 0.53 |
| Relief (`relief*`) | torch | `relief` | brick facade 8138,11531, 11:00, clear, ppl + sun shadows on in both | 1, crop 636x268 of the 1:1 capture | mc-relief-off / -on | yes 0.96 clear | - |
| Ambient occlusion (`ambientOcclusion`, `ao*`) | ao (enh-ao) | `ao` (replaces it) | Rosewood living room 8147,11507, noon | 1, crop 768x324 at 1:1 | mc-ao-off / -on | yes 0.94 clear | old clip yes 0.95 too; replaced so it matches the other clips' rig |
| Sun shadows (`sunShadow*`, section default) | ao (wrong) | `sunshadows` | house 8147,11507, 16:30, clear, cloud cover 0 | 1, 1536x648 at 1:1 | mc-sun-off / -on | yes 0.92 clear | - |
| Shadows on characters (`entityShadow*`) | ao (wrong) | none: shows `sunshadows` (entity shadows on by default there) | 16:30 walk; 17:30 turning by a wall; one alternating run with 33 pinned zombies at the church at 17:48 | 0.5 / 0.75 | mc-entsh, mc-entsh2, mc-entsh3-alt | no 0.69 / no 0.74 subtle / thrown out | **not visible at 512x216** after 2 re-shoots: the player stood wholly in the sun, then wholly in the shade; mc-entsh3-alt spawned no zombie (`pin_zombies` with `zombies=off`: `inWorld=false`). A pinned crowd across a shadow edge (`docs/findings-entity-shadows-2026-10-07.md`, es-vid2) is the scene that shows it |
| Cloud shadows (`cloud*`) | ao (wrong) | `clouds` | 8154,11514, 16:00, cloud cover 0.5, `cloudScalePct=50 cloudSpeedPct=800`, sun shadows on in both | max, whole screen at 20 % (1024x432) | mc-clouds-off / -on | yes 0.79 motion | - |
| Moon shadows (`moon*`) | ao (wrong) | `moon` | 8154,11514, 23:00, full moon (`devSkyDate=1993-07-03`), sun shadows on in both | 1, 1536x648 at 1:1 | mc-moon-off / -on | yes 0.85 clear | - |
| Reflections (`reflection*`) | hdr (wrong) | `reflections` | Riverside pier `find=shore` 6404,5188, 15:00 | 1, crop 768x324 of the 1:1 capture | mc-refl-off / -on | yes 0.73 clear | the whole 1536x648 crop: no 0.59 (the river is a corner of it); recut, no re-shoot |
| Puddle ripples (`ripples*`) | hdr (wrong) | `ripples` | rain walk at 8006,11206, noon | 0.5, 1024x432 at 1:1 | mc-ripples-off / -on | yes 0.94 motion | - |
| Car wakes (`ripplesWake`, `ripplesVehicles`) | hdr (wrong) | `wakes` | drive at 30 km/h through puddles, noon, clear | 0.75, 1536x648 at 1:1 | mc-wakes2-off / -on | yes 0.88 clear | 1: mc-wakes (rain drive) thrown out: misaligned even with a 1.5 s match window, no trail visible in the rain |
| Car glass (`carGlass*`) | hdr (wrong) | `carglass` | three parked cars (`car_rig`), 10:30, clear; ONE run, `devCarGlassAlternate=4000` | 0.5, crop 1792x756 of the 1:1 capture | mc-glass2-alt (phase 2 on / 1 off) | yes 0.75 clear | 1: mc-glass pair thrown out: Jev yes 0.96, but the two runs parked cars of different colours in other places (a misleading clip) |
| The people inside a car (`carOccupant*`) | hdr (wrong) | `occupant` | drive at 60 km/h, 11:00, car glass on in both | 0.5, crop 1200x506 | mc-occ-off / -on | yes 0.92 clear | match window 1.0 s (a drive) |
| Occluded zombie outlines (`occludedOutline*`) | drive (wrong, no section clip) | `outlines` | 200 zombies at the church lot 8170,11500, noon | 1, whole screen at 30 %, crop 576x243 | mc-outl-off / -on | yes 0.87 clear | the yard zombies stand in other places in the two runs; the outlined ones on the roof edge match |
| Mirrors and windows (`mirrors*`) | hdr (wrong) | `mirrors` | `find=mirror` from 8147,11507, noon | 0.5, 1280x540 at 1:1 | mc-mirror2-off / -on | yes 0.94 clear | 1: mc-mirror (25 % capture) yes 0.63, marginal |
| Reflective props (`mirrorsProps`, `mirrorsPropGlassPct`) | hdr (wrong) | `props` | a row of nine placed props (`place_tile`: shop glass case, low table, TV, counter, sports item, fridge, glass door, bathroom fixture) at 7998-8006,11202, walking east | 0.5, crop 944x398 of the 1:1 capture | mc-props2-off / -on | yes 0.90 clear | 1: mc-props (25 % capture) no 0.55; mc-props2 whole 1536x648 no 0.59, recut |
| Wet blood (`blood*`) | hdr (wrong) | `blood` | living room 8147,11507, 15:00, 800 splats in a 4-square radius, turning, reflections on in both | 0.5, 768x324 at 1:1 | mc-blood3-off / -on | yes 0.85 clear | 2: mc-blood (noon, in the bench car) no 0.70 subtle, mc-blood2 (18:30 on the road) no 0.81 subtle |
| Foliage sway (`foliageSway*`, `windSpriteSway`) | hdr / drive (wrong) | `sway` | yard at 8147,11507, noon, `wind=1` | 1, 1536x648 at 1:1 | mc-sway-off / -on | yes 0.90 motion | - |
| Darkness floor (`darknessFloor*`) | darkness (all three together) | `darkfloor` | house 8147,11507, 01:00 | 1, 1536x648 at 1:1 | mc-dark-off / -on (0 % / 20 %) | yes 0.96 clear | - |
| Remembered places (`memory*`) | memory (colour grading was on in both sides too) | `memory` (replaced) | house, 13:00, memoryTint alone | 1, crop 936x395 | mc-memory-off / -on | yes 0.83 clear | old clip no 0.56; whole crop no (p 0.48); recut |
| Colour grading (`colorGrading*`) | grade (sides ~40 px apart, alignment 0.18) | `grade` (replaced) | house, 20:30, clear | 1, 1536x648 at 1:1 | mc-grade2-off / -on | yes 0.81 clear | 1: mc-grade (storm 14:00) no, subtle (p 0.25-0.36; the storm look is a small desaturation) |
| Tree cutaway while driving (`driveTreeCutaway`) | drive | `treecut` (left = the setting on, the game's 42.21 rule) | drive at 60 km/h, noon | 1, whole screen at 30 % | mc-treecut-off (`=true`) / -on (`=false`) | yes 0.96 clear | match window 1.0 s |
| Sprite filtering (`spriteFilter*`) | upscale (wrong: FSR) | none: shows `visuals` | house, noon | 0.75 / 1.5 / 2.5, 1:1 crops | mc-sf, mc-sf2, mc-sf3 | no 0.82 / no 0.80 / no 0.59, all subtle | **not visible at 512x216** after 2 re-shoots (detail 19.5 -> 29.1 at 1.5, yet a 512 px clip shows no difference one can name) |
| Zoom ease (`zoomEase`, `zoomEaseMs`) | nightdrive (section) | none: keeps `nightdrive` | house, wheel zoom cycle every 1 s | 1, whole screen at 20 % | mc-zoom (60 fps cap), mc-zoom2 (uncapped) | no (p 0.49) / no (p 0.45) | **not visible at 512x216**: a 24 fps GIF cannot show the difference between a 300 ms glide and the stock step |
| Zombie detail (`zombieLodFixed3d`) | horde | none: keeps `horde` | 200-zombie crowd, zoom 1.5 | 1.5 | mc-zlod-off / -on (0 / 30) | Jev yes 0.94, overruled | **not visible**: the difference Jev measured is the crowd standing elsewhere in the two runs; the 2D sprites are drawn to look like the 3D models |

Kept clips, judged in GIF mode (`clip-judge.py <stock.gif> <opt.gif>`), not re-shot: `hdr` yes 0.94, `hdrday` yes 0.95,
`darkness` yes 0.87 (the three darkness settings together, kept for the section), `voxel` yes 0.93, `godrays` yes 0.82,
`godraysdoor` yes 0.81, `godraysspill` yes 0.95, `fsrzoom` yes 0.92, `dlsszoom` yes 0.93, `torch` yes 0.66 local (the
blocky-lights fix, kept for the lighting budget keys). Upscaling and dynamic resolution keep `upscale` / `fsrzoom` / `dlss`;
fog, rain and the chunk grid keep `fog` / `storm` / `grid`. The Visuals master switch shows `visuals` (it showed `upscale`).
Driving smoothness, VRR and the frame pacing keys change motion a 24 fps GIF cannot carry; they keep their sections' clips.

Thrown out: mc-ppl-off (scene), mc-wakes pair (misaligned), mc-glass pair (different cars), mc-entsh3-alt (no zombies
spawned), mc-zlod (the measured difference was crowd placement). No Louisville run was made. Every run's console has its
`[pzopt]` lines (the overrides loaded). All runs stay under `harness/runs/mc-*`; `harness/menu-gifs.json` names the ones
each shipped GIF comes from.

## The shipped GIFs

`harness/menu-gifs.py ppl torchsource relief sunshadows clouds moon reflections ripples wakes carglass occupant outlines mirrors
props blood sway ao memory grade darkfloor treecut` (queue media jobs 14087-14089 on this tree), 512 px wide, 24 fps 3.6 s;
reflections, ripples, wakes, occupant and treecut at 15 fps with the denoise chain (rain, water and a moving world
re-colour every pixel: 2.3-3.8 MB at 24 fps), ripples and treecut 3 s. 18 new pairs = 23.7 MB, the largest 1.41 MB
(mirrors-opt, blood-opt); ao / memory / grade replaced (3.15 -> 1.75 MB); net +22.3 MB under `src/media/ui/pzopt/compare/`.
Jev on the encoded GIFs themselves (`clip-judge.py <clip>-stock.gif <clip>-opt.gif`): all 21 `visible=yes`, confidence
0.74 (wakes) to 0.97 (sun shadows), median 0.93.

Wiring (`src/lua/client/pzopt/pzopt_optimizations_options.lua`): each Visuals section's `clip` is its own clip (sprite
filtering -> `visuals`, occluded outlines gained one), KEY_CLIP gives the sub-features theirs (cloud / moon shadows, car wakes,
the people inside, reflective props, darkness floor, `driveTreeCutaway` -> `treecut`, `windSpriteSway` -> `sway`), the Visuals
master switch shows `visuals`; CLIP_TITLES / CLIP_SIDES / CLIP_NOTES for every new clip. Ships with the next `scripts/build.sh`.
