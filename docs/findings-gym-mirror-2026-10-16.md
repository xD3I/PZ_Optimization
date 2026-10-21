# The gym mirror (2026-10-16)

The maintainer's screenshot of save `Sandbox/2026-10-12_01-22-59` (player at 6284.3,5378.6,0): the gym's full-length wall
mirror (three prop panels `recreational_sports_01_77/78/79` on the west wall at 6283,5377..5379, reflecting through
`pzopt.Props`, class mirror) showed a flat pale-blue slab over two of its three panes, a torn copy of the weight rack beside
its reflection, a dotted band along the reflected wall's foot, and the reflected player lost their head over the glass's top
edge a step back from it. Rig: Jev walking and circling in front of each pane (below), zoom 0.5, 1:1 `devCapture`.

## Causes and fixes

| # | Seen | Cause | Fix (old way back with) |
|---|---|---|---|
| 1 | flat pale slab over panes 77 / 78 | `MirrorGeometry.square` skips only `IsMirror` tiles: each panel's rebuilt room held its neighbour panels as furniture right behind the glass (`dev room geometry of recreational_sports_01_78: ... recreational_sports_01_77@6282.1,5378.0`), nearest, so they won everywhere | prop mirrors (`Props` class mirror) are never part of a rebuilt room (`devMirrorsSkip` 1073741824) |
| 2 | a torn, streaky second rack beside the reflected rack | the march's thickness test took the real rack's pixels for rays that pass behind its thin upright; `withGeom` kept any marched hit nearer than the room geometry, which had the wall there and its own clean rack beside | a marched hit is kept only where it agrees with the geometry within the 0.25-square tolerance, both ways; the switch is a blend over the tolerance's upper half (a hard switch drew a jagged comb along the wall; a band out to 0.5 let the rack's pixels show faintly) (`devMirrorsSkip2` 1) |
| 3 | a dotted band of wall and trim along the reflected wall's foot (in the geometry itself: dev view 6) | a tile's overlays (baseboard trim, wallpaper) were drawn at its own distance, texel for texel: a depth fight in the geometry atlas | overlays a hair (0.02 squares) nearer (`devMirrorsSkip` 1073741824) |
| 4 | the reflected person's head cut by the glass's top edge from ~1.4 squares out | people are placed d / 6 levels up (`mirrorsViewDropPct` 50, tuned for the high-hung cabinets); the gym glass ends at 0.98 levels, a person is 0.85 | a full-length mirror (glass from within 0.15 of its floor, at least 0.8 tall) lowers its plane's drop so the head reaches the glass's top 2 squares out (0.065 here); planes keep one drop (a full-length and a hung mirror on one wall line are two planes) (`devMirrorsSkip2` 2) |

| 5 | the glass smeared and torn (stretched poster, jagged brown band) once the player left the gym (maintainer: "when the character leaves the room the reflection breaks") | with the player outside, the gym's walls stand full height: the frame shows their outer faces where the room's inside was, so the march's hits there are other walls' pixels; and on the east wall's outer face they lie within the tolerance of the geometry's far wall, so they won (dev view 5: blue over most of the glass) | `MirrorGeometry.roomClosed`: the player outside the room, or most of its camera-side walls not cut away, closes it; a closed pane takes the geometry's colour where it has a texel, keeping the march's distance code (the people are clipped by it) (`devMirrorsSkip2` 4) |
| 6 | (the same smear on pane 79, beside the room's NW corner) | `MirrorGeometry.square` skipped corner wall tiles entirely: the north wall that pane 79 looks along was missing from its room, only the poster on it was there | a corner adds its straight half across the plane (`CornerNorthWall` / `CornerWestWall`) (`devMirrorsSkip2` 8) |
| 7 | the smear lasted ~0.8 s after leaving | prop mirrors were re-marched on the prop age refresh only, not when their room changes (the 10-08 room check was for `prop == 0`) | prop mirrors with a room get the room check, and the closed state is part of the room signature: re-marched within 16 frames (`devMirrorsSkip2` 16) |

`devMirrorsSkip2` is new: `devMirrorsSkip`'s 31 bits are all taken (it reaches the static program as `uniform int skip2`).

Left: where the march (the real wall, with its grime and blood stains) gives way to the geometry (the wall's plain paint)
there is a soft edge on the reflected wall; the stains are not tile objects, so the geometry has no copy of them.

## Rig

`pzopt.MirrorWalk` now lists prop mirrors too (it walked past the gym before: 8 wall decorations of the building, not the gym
panels). The walk, per pane: arrive, face the mirror, turn round, one lap.

```
python3 harness/explore-director.py --log /tmp/<l>-dir.log --wait 1800 &
harness/queue.sh submit run --install opt ... -- --label <l> --mode bench --launcher direct --source-save Sandbox/2026-10-12_01-22-59 \
  --flag explore=mirror --flag director=jev --flag mirror_secs=16 --flag lights=on --flag zombies=off --flag route=S:1 \
  --flag speed=0.0005 --flag zoom=0.5 --route-seconds 26 --no-mangohud --prop mirrors=true --prop overlay=false \
  --prop devMirrorsLog=true --prop devMirrorsRectsEvery=24 --prop devCapture=0,26,8,100,crop=1700:300:1700:1400,ram
```

Dev views used: 5 (how each static ray ended: cyan = room geometry, blue = marched hit) and 6 (the geometry alone), both with
`devMirrorsViewToggleMs=1000`; `--prop devMirrorsSkip2=3 --prop devMirrorsSkip=1073741824` is the old behaviour in one run.
Runs `gym3` (before), `gym-ref` (`mirrors=false`), `gym-v5` / `gym-v6` (dev views), `gym-fix1`..`gym-fix7`, `gym-final-v5`.
`mirror-judge.py` (the 10-04 artifact classes) on `gym-v5` vs `gym-final-v5`: the largest flat-colour region of the glass
0.19 -> 0.15 (pane 78), 0.21 -> 0.16 (pane 79).

Leaving the room (fixes 5-7): `--flag mirror_pick=1,4,1` (new: these mirrors of the walking order, repeats allowed) walks the gym
pane, the next room's mirror and back. Runs `gymleave1` / `gymleave-ref` (`mirrors=false`), `gymleave-v5` / `-v6`, `gymcorner-v6`,
`gymcorner-n3`, `gymvid2-fixed` / `gymvid2-before` (`devMirrorsSkip2=28`). The panes switch to the geometry 0.94 s after the walk
leaves for the next room, the frame the walls go up (log `mirrors: dev pane ...: room closed to the camera`). Bathroom double-judge
with view 9 on all the time (`devMirrorsViewToggleMs=0`): one split image at the door with and without fix 5 (`bath-all9-new`,
`bath-all9-noclose`): the judge counting a person cut by the sink as two blobs, not a regression.
