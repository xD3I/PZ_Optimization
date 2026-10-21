---
name: jev-walk
description: Walk the player through a building with Jev directing (explore=mirror director=jev) on the collision-free game walk (pzopt.Nav), set up test rooms (mirrors in the corners, placed tiles), capture the walk and judge it (navjudge.py, walkframes.py). Use for any visual test that needs the character moving around a place (mirrors, lighting, cutaway, flicker), or when a walk rig collides, gets stuck or vaults fences.
---

# Jev walks the player (pzopt.Nav + MirrorWalk)

Since 2026-10-08 the walk never uses movement keys or teleports: `pzopt.Nav` issues the game's own "walk to"
(`ISWalkToTimedActionF`, harness Lua `harness/mod/pzopt-harness/42/media/lua/client/pzopt_harness_walk.lua`), so the
pathfinder routes round furniture footprints, takes stairs and opens doors. On top of that Nav:

- opens and unlocks a closed door one square ahead (the game opens it at 0.5 squares, its collision feeler's reach);
- replaces a route that vaults a fence or climbs a window by a square-grid detour (radius 120);
- faces with the instant turn (`Nav.face`): the turn-in-place animation steps into walls;
- accepts a station / lap point only where the body fits 0.15 squares round it (`Nav.canStandClear`);
- times a leg out after 15 s + 1.5 s per planned square, retries a failed or stuck leg once;
- logs `harness: nav: leg N '<what>' arrived in S s, walked W / straight D squares, planned P (xR) ...`, every collision
  (the game's own per-frame flags, the squares' objects, the player's state) and stuck episode, and
  `harness: nav summary: legs .. arrived .. collisions .. stuck .. doors .. detours .. climbs ..` at the end.

Jev only decides (face the mirror, turn around, circle, move a station, next mirror, done); `harness/explore-director.py`
asks TypeSafe every 0.3 s from the state file the game writes.

## 1. A run

One director per run, started **before** the job (it waits for a state file newer than itself and exits when it goes stale):

```bash
python3 harness/explore-director.py --log /tmp/<label>-dir.log --wait 1800 &      # --machine flip|mac for a laptop run
harness/queue.sh submit run --install opt --name <session> --intent "..." --progress "..." --resource visual-parity -- \
  --label <label> --mode bench --flag start=8092,11540 \
  --flag explore=mirror --flag director=jev --flag nav_selftest=true \
  --flag mirror_corners=8092,11548,2 --flag mirror_laps=3 --flag lights=on \
  --flag zombies=off --flag route=S:1 --flag speed=0.0005 --flag zoom=0.75 --flag time_of_day=14 --flag weather=clear \
  --route-seconds 200 --prop mirrors=true --prop mirrorsWindows=false --prop hdr=false --prop hdrAuto=false --prop overlay=false \
  --prop devMirrorsLog=true --prop devMirrorsRectsEvery=24 --prop devCapture=0,125,6,100,crop=1660:530:1800:1100,ram
```

(`--install opt` ships this checkout's build: build first with `PZOPT_DLSS=0 scripts/build.sh`, never piped.)

| flag | effect |
|---|---|
| `explore=mirror director=jev` | the walk: every mirror of the building (or of the `mirror_corners` room), stations in front of each |
| `mirror_cols` / `mirror_rows` | station grid along the wall / out from it (default `-0.5,0.5` / `1.0,1.8`); a grid blocked by furniture is moved out / sideways |
| `mirror_only=N`, `mirror_secs=S` | walk only the N-th mirror; cap the walk (default 10 + 25 s a mirror) |
| `mirror_pick=a,b,...` | these mirrors of the walking order in this order, repeats allowed: `1,4,1` leaves a mirror's room for the next one and comes back (2026-10-16) |
| `mirror_laps=N` | the mirrors N times, every other pass reversed (back and forth) |
| `mirror_corners=x,y,z` / `auto[:minW,minH]` | a mirror in each corner of that room (auto: the most free one-rect room within 80 squares whose corner spots can be stood on); NW / NE north wall, SW west wall, SE east wall (seen from the back, listed only); `mirror_corner_sprites=a,b,c,d` |
| `mirror_corners=pair` | a corner room with another room behind its south wall: the walk starts in that room (the mirrors' room unseen), goes in and comes back between passes; judge with `harness/mirrors/room-judge.py` |
| `mirror_corners_teleport=false` | the player walks to the room from the start instead of starting in its middle (a long, multi-floor walk) |
| `place_tile=sprite@x,y,z/...`, `clear_wall=x,y,z/...` | extra tiles (another mirror upstairs) / remove wall decorations first |
| `nav_selftest=true` | before the walk the keys push the player into the nearest wall: the collision counter's control (not counted) |
| `lights=on` | grid power and every switch within 50 squares on: unlit interiors make reflections unjudgeable |

Known clean scenes (2026-10-08): the Rosewood church multi-level walk (`start=8147,11507
place_tile=walls_decoration_01_5@8141,11505,1/walls_decoration_01_5@8124,11511,0`), the derelict room 70 squares away
(`mirror_corners=auto mirror_corners_teleport=false`, a fence detour), a house bedroom two floors up
(`start=8090,11531 mirror_corners=8089,11535,2 mirror_corners_teleport=false`), the lit 7x7 room above
(`mirror_corners=8092,11548,2`).

## 2. Judge the walk

```bash
python3 harness/navjudge.py <run> --director /tmp/<label>-dir.log [--json <run>/navjudge.json] [--no-jev]
```

Exit 0 = clean: done by itself, no collision (after the self-test push, which must be `control_push_detected: true`),
no stuck, failed or lost leg, no fence / window vault, no ERROR / exception line between route start and the walk's end,
no director error. Efficiency = walked over the pathfinder's planned route (median ~1.00). Jev answers per criterion and
clean / nearly / broken. `skipped_no_room` (a lap or a mirror without room in front) is by design.

## 3. Look at the walk

- `python3 harness/mirrors/walkframes.py <run> [--sheet]`: a PNG per walk event (arrived / faced / back to / circled)
  into `<run>/walkframes/`, cut from the `devCapture` by the events' `epoch_ms`.
- `devCapture` `crop=x:y:w:h` is **1:1 screen pixels, top-left origin** (the scale field is ignored with a crop). Size the
  crop from the `mirrors: dev rects (viewport WxH): [mirror x,y wxh @sx,sy,sz]` lines of a first run; RAM is
  w x h x 4 bytes a frame.
- A/B: the same walk with the feature off (`--prop mirrors=false`) gives the reference for every event (the walk is
  deterministic: same stations, same timings within a frame or two).
- `harness/mirrors/kinds.py`, `mirror-judge.py`, `corner-judge.py`: reflection-specific judges (harness/CLAUDE.md).

## Pitfalls

- **The game keeps only the last ~5 MB of its log** (console.txt and `~/Zomboid/Logs/*DebugLog*`): a per-frame dev log
  (`devMirrorsRectsEvery=1` at 240 fps) pushed the walk's first minute out. Log every 24 frames.
- `harness: route starts in 5.0s` contains "route start": match `harness: route start (` for the route's real start.
- The director needs the TypeSafe key on this machine; for a laptop run pass `--machine <m>` (it bridges over ssh).
- `mirror_corners=auto` may pick a room in another building; the walk then lists that room's building, not the start's.
- A furnished room can leave a corner mirror without a spot to stand on: it is logged `no station that can be reached,
  skipped`, never walked into.
