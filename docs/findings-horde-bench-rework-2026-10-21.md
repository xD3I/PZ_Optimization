# Horde bench fought like a player (2026-10-21)

The maintainer: "rework the horde shooting bench, the character should move and react to zombies getting close the same
way a player would, shooting when the horde is far, circling the horde, reloading and moving away, use jev as a director,
the character should move without getting stuck, work in a loop until the benchmark passes without the character taking
any zombie damage, make the inventory of the character not be overcumbered". After run hk-fight1: the field had bushes
(move to open ground that also shows the visuals), and the bench must end at once when the player is hurt or dies, with a
hard timeout that also holds in the built-in benchmark.

Worktree `~/pzopt-wt/menu-impeccable` (branch `menu-impeccable`), uncommitted. Bytecode audit 9152 methods / 0 mismatches
on every build; build 17 installed (queue job 14243). No `src/overrides` touched.

## The bench now

- **Place**: the Riverside school's baseball field at noon (chosen with `arena_scan`): open grass, no bushes, fences or
  clutter inside the arena, the school, light poles and trees round it. Start / pier 6412,5452, arena 6424,5440 radius 24;
  150 fast zombies from the NE at gap 16. Zombies attack (no `no_attack`, no god mode).
- **Who decides**: Jev through `QUESTION_TACTICS` (`harness/showcase-director.py`); the built-in benchmark plays the
  autopilot with the same rules.
- **How the player fights** (`pzopt.HordeTactics`, `tactics=kite`): shoots while the horde is far; walks back while aiming
  as it comes on (`backpedal`, ~1.2 tiles/s while the nearest is inside shoot distance + 2); runs round it when it gets
  close (`kite`: steered every 50 ms over the 8 movement-key directions, each tested 3 tiles ahead with the pathfinder's body
  tests plus water / trees / bushes, scored by the clearance from every zombie along the way and 0.8 s on, a tangent to the
  horde's centre, the arena edge); adaptive distances (≤ 10 zombies within 10 tiles: kite under 4, shoot over 6); reloads
  only when empty or tops up at ≤ 1/3 with room, walking away (no Run: the game's reload / equip actions stop on a run)
  while a zombie is inside `reload_room`; draws the .44 when the rifle is empty and the horde is near; closes in on targets
  over 12 tiles (or on the ground over 5), from the nearest arena edge for a zombie outside it; no blind rounds past 12 tiles
  (`blind_tiles`); a 25° facing gate; the flinch (`reflex_tiles` 3.5: a zombie that close runs the kite anyway, counted);
  the instinct (a stale director "kite" with the nearest past shoot distance + 1.5 for 1 s becomes "shoot").
- **Kit**: travels light (`travelLight`: the save character's bags, 44 weight, stay home), boots when the character has
  none (`IsoPlayer.updateFootInjuries` scratches bare feet outdoors: the built-in benchmark's new-world character is
  barefoot), an ALICE pack, 20 magazines weighed for real and packed pack-first (4 at hand, `main_mags`), under 95 % of the
  capacity with the stowed rifle's weight for the sidearm swap allowed for. The benchmark character carries 12, the bench
  save's 18; both take all 20 magazines.
- **Measured every frame**: hits (entries into `PlayerHitReactionState`, new attackers in `attackedBy`), wounded body parts,
  health, attacks begun at the player, bumps; stuck episodes (a wanted move making under 0.5 tiles in 2 s); weight against
  capacity and the Heavy Load moodle. `pzopt-horde.out`, the `horde_*` lines of `pzopt-bench.out`, the built-in benchmark's
  step file; `harness/horde-judge.py <run> [--director log]`: pass / fail on overrides, unhurt, cleared, not_stuck, route,
  unburdened.
- **Kill switch** (`kill_switch`, default on): the first zombie hit or wound ends the pass at once, console
  `KILL SWITCH ... failed: player damaged` (or `player died`), `route_status=failed` with the reason, the game quits.
  **Hard timeout**: end_cap 200 s; a watchdog thread fires at end_cap + 20 (`hard_timeout`) even when no frames come (a dead
  player stops frames: why hk-fight1 never ended). Harness `endRouteFailed` / `hardStop`; the "player vanished" path quits
  instead of hanging. In the built-in benchmark a failed pass reads "the route did not complete (failed: player damaged)"
  and the planner moves on. Dev rigs `dev_horde_stand` / `-Dpzopt.devHordeStand`, `dev_watchdog_offthread`.
- `BenchSuite` horde entry: the new place, `tactics=kite`, `end=horde_dead`, end_cap 200, `seconds` 110 + 6 (clears
  measured 100-126 s, the benchmark's 120 s). Menu SCENE_INFO: the new place, the pier clip dropped until one of the field
  is made.

## Iterations

| run | change | result |
|---|---|---|
| hk-scan1 | arena scan, 5 points | the old bush test missed bushes |
| hk-fight1 | open field 6922,5375 | Heavy Load 3, bitten at 119 s and died; the run never ended (cancelled) |
| hk-scan2 | bush / clutter classes from the game's tests (`hasBush`, HedgeLow / HedgeHigh), 9 points | picked the school baseball field |
| hk-fight2 | new arena, travel light | 0 hits, but Heavy Load 4 (health to 75), 19 killed, cap |
| hk-fight3 | magazines packed by real weight | 0 hits, 110 killed, a reload loop wasted magazines |
| hk-fight4 | reload guard, no webbing, cap 240 | 145/150, out of ammo, Jev stuck on kite |
| hk-fight5 | pack, adaptive distances, instinct | 149/150, out of ammo (ejects counted as shots) |
| hk-fight6 | 4 magazines at hand + 16 in the pack | PASS, clear 169 s, 3.05 rounds / kill |
| hk-fight7 | close-in, 25° facing gate | PASS, clear 128 s |
| hk-kill1 | kill-switch test (player held still) | failed: player damaged at 13.7 s, quit by itself |
| hk-dead1 | kill switch off, player held still | failed: player died at 19.2 s, quit |
| hk-watch1 | watchdog forced (hard_timeout 15) | ended by the watchdog with no frame, failed: hard timeout, quit |
| hk-benchfail1 | benchmark `horde,spin:1`, player held still | horde recorded failed with its reason, spin ran next |
| hk-pass1 | BenchSuite horde entry, end_cap 200 | PASS, 144 s |
| hk-pass2 | same | FAIL: cap with 2 left after 156 s of kiting (the arena edge bends the run, the horde cuts across) |
| hk-bp1/2/3 | walk back while aiming | PASS x3: 111.8 / 107.2 / 105.6 s |
| hk-bench1 | built-in benchmark | FAIL: bare-foot scratch (the benchmark's new-world character has no shoes) |
| hk-bench2 | boots | PASS, 115 s, but only 9 of 20 magazines carried (capacity 12) |
| hk-bp4/5/6 + hk-bench3 | magazines pack-first | PASS x3; bench cleared but touched Heavy Load 1 at the sidearm draw |
| hk-bp7/8/9 | packing limit allows for the stowed rifle | PASS x3; hk-bp9 spent 36 s firing blind at a fence-thumper 27 tiles off |
| **hk-bp10/11/12** | close-in toward the arena edge, no blind rounds far off (build 17) | **PASS x3: 125.9 / 109.4 / 99.6 s** |
| **hk-bench4** | built-in benchmark `horde:1`, build 17 | **PASS: clear 118.2 s** |

## Passing runs

Run dirs under `harness/runs/`. Director runs: 0 hits, 0 wounds, 0 stuck, Heavy Load 0, 20/20 magazines, 0 Jev errors.

| run | clear | rounds / kill | closest (tiles) | fps | p99 | p99.9 | max | > 33 ms | CPU (busiest core) | GPU |
|---|---|---|---|---|---|---|---|---|---|---|
| hk-bp10-20261021-211436 | 125.9 s | 2.20 | 3.74 | 156.6 | 6.42 ms | 12.74 ms | 45.9 ms | 1 | 15.0 % (45.9 %) | 47.5 % |
| hk-bp11-20261021-211759 | 109.4 s | 1.91 | 2.71 | 156.7 | 6.39 ms | 12.19 ms | 46.3 ms | 1 | 15.4 % (43.2 %) | 47.8 % |
| hk-bp12-20261021-212104 | 99.6 s | 1.82 | 3.86 | 156.5 | 6.44 ms | 12.74 ms | 48.6 ms | 1 | 15.5 % (45.8 %) | 50.1 % |

The director runs sit at the desktop's ~157 fps VRR cap (165 Hz) with the hardware far from saturated (by the objective,
itself a finding). hk-bench4 (`bench-hk-bench4-20261021-212346`, the benchmark's uncapped mode, the player's options with
`panCameraWhileAiming=true`): 626.6 fps, p99 4.66 ms, p99.9 8.54 ms, max 66 ms, 1 %-low 154, jitter 0.23 ms; game thread
94.9 % (game-bound 76 % of frames), GPU busy 61.6 %, process CPU 19 %; 0 hits, 0 stuck, weight peak 10.22 of 12, 20/20
magazines.

## Memory

The first two attempts died with the whole desktop session (18:19, 18:31) when a whole-map lotpack scan used here to find
an open spot (`findpaved.py`: Pool(12) over all 4065 cells, an unbounded cell cache, a full-map distance transform) ran the
machine out of memory. The spot was then found with the game's own `arena_scan`; no whole-map script was run again.

## Open

- Clear time varies (93-172 s over the build-16/17 runs); a zombie stuck behind the field fence can stretch a pass. end_cap
  200 s and the watchdog catch the worst case.
- No menu clip of the new place yet (`bench-horde` showed the pier).
- One early `scripts/build.sh` ran outside the queue during the peer's sq-church2 run; later builds went through the queue.

## The maintainer's own take at the pier (2026-10-22 20:29)

Save `pzopt-horde-pier` (pzopt.HordeScene), build adef1841-dirty of 01:13. The recording is
`~/Videos/Project Zomboid/Video_2026-10-22_20-29-15.mp4` (441 s, 5K AV1 HDR). The console of that launch, with a link to
the video, is in `harness/runs/player-pier1-20261022-202915/`. The played save is kept at
`~/Zomboid/pzopt/saves/pzopt-horde-pier-take-20261022-2029`, and the save was reset from `pzopt-horde-pier-pristine` for
the next take. Frames were read at 1 fps (tone-mapped JPEGs, not kept).

- **Release**: Home at ~23 s of video, 150 zombies, the nearest 15.7 tiles out. They came as one dense blob, which reached
  the player at the pier's lawn in ~6 s.
- **Ground**: the player left the bench's arena (radius 22) at once and used the long open lanes: first SE down the
  waterfront promenade (road + lawn + boardwalk), then the park with the tents, then the donut shop's lot and the
  intersection. They came back to the pier twice (~100-130 s, ~240-290 s). The fight covered roughly 60 x 60 tiles.
- **How they shot**: walk away along a lane so the horde trails as a column, stop, turn, fire into the head of the column
  (dense, so rounds hit several), back off a few tiles, repeat. They let stragglers come to **1-2 tiles** while firing (41 s:
  three zombies inside 2 tiles, rifle up). The bot kites under 6 tiles and flinches at 3.5.
- **Reloads and lulls**: from ~240 s they stood on the pier (water on both sides, one way in) with the inventory open,
  hand-loading the .44 (24 -> 13 -> 2 rounds, `Load Rounds`), M16 Magazine x11-12 in the main inventory, and
  looted corpses. The inventory window stayed open while they fought on until ~345 s.
- **Length**: the horde was mostly down by ~350 s, with stragglers still being cleared at 441 s (game clock 12:00 -> 13:50).
  The bot clears in 100-126 s.
- **Damage**: no injury, bleeding or pain moodle at any of the sampled times (hungry, panic, nausea from the corpses, thirsty
  only).
- **Start state**: the scene's kit logs `weight 14.77 of capacity 18 (82 %), Heavy Load level 1` (the save character), so
  the player started with Heavy Load 1. The bench's own rule is no Heavy Load.
- **Seen on the way**: a large black patch over the water and the pier in the first seconds after load (gone by 10 s);
  grey see-through zombies at 31-41 s near the blob's edge (not checked yet against stock / the phasing fix); one
  `hdr light: build failed: NullPointerException ... this.lights is null` in the console.

## Under 80 s (2026-10-23, runs h80-p26..p43, director log + console under `~/pzopt-resume-2026-10-22/horde80/`)

Goal (maintainer, 2026-10-22): clear the pier horde in < 80 s, same judge (unhurt, cleared, not stuck, ready before
contact, unburdened), character Desensitized (no panic penalty). Stay in the pier arena (`arena=6450,5198,22`).

- **Running**: held Shift never counted (`Core.invalidBindingShiftCtrl` reads the raw key); `GameKeyboard.isKeyDownRaw`
  now also answers `Showcase.keyHeld` (`// pzopt:`, docs/override-edits.md): 4.3 tiles/s instead of 2.15.
- **Rules added on request**: M16 kit `Base.Laser;Base.RedDot` (`rifle_parts`; the RecoilPad does not mount on the M16),
  house alarms off (`no_alarms`: 662 BuildingDefs disarmed, ringing ones stopped), every live zombie inside the arena and
  15 tiles counts (`adopt_tiles`), non-horde zombies are targets.
- **Head-bone aim** (`head_bone`, default on): the reticle goes on `Bip01_Head` (`CombatManager.getBoneWorldPos`, z in
  levels). Before, a crawler took 12 body rounds at 0.1 each (p36, 61.8-74.3 s of a 77.5 s clear) because a floor target
  was aimed at its feet. Kills within 1 s of a round 66 -> 80 %, rounds a kill 1.40 -> 1.10-1.27.
- **M16 result**, flags `body_shot_ms=300 crowd_zombies=20 sidearm_draw=false kite_tiles=3 shoot_tiles=5.5 reflex_tiles=2.2
  backpedal=false reload_room=5 blind_tiles=10 close_in_left=20 close_in_tiles=9 floor_close_tiles=5 aim_settle=-1`:

  | run | clear_s | rounds/kill | reloads | judge |
  |---|---|---|---|---|
  | p36 (before head-bone aim) | 77.5 | 1.40 | 10 | PASS |
  | p39 | 54.2 | 1.21 | 6 | PASS |
  | p40 | 55.2 | 1.27 | 7 | PASS (health 99.9 = the save's own start value, 0 hits) |
  | p41 | 44.2 | 1.10 | 5 | PASS |

- **JS3T shotgun** (`gun=Base.JS3T_Shotgun rifle_parts=Base.RedDot;Base.AmmoStraps`, the only parts that mount on it):
  - The game racks after each shell (`ISRackFirearm`, queued at OnPlayerAttackFinished; stopOnAim / stopOnWalk false,
    stopOnRun true). The bot took it for a reload: aim down after every shell, the director asked for a reload, its
    walk-away ran and cancelled the rack, the gun stayed unchambered (p37: 4 shells in 74 s). Now a rack-only queue is
    not "reloading", the aim stays up and the trigger waits for the chambered round; a rack a run cancelled is queued
    again after 0.25 s (never while one is under way: p38 queued three in a row). A pump gun with its last shell
    chambered is no longer "empty".
  - Each shell adds RecoilDelay 28 x 0.25 + AimingTime 30 x 0.05 = 8.5 aiming delay and the delay does not drain while
    racking (`IsoGameCharacter.updateAimingDelay`): fired at once it sat at the 31.5 cap (p38: 39 of 47 shells with
    nothing under the reticle). `aim_settle=8` waits ~0.2 s after the rack. `fire_tiles=8`: no shell past 8 tiles (MaxRange
    12 with falloff; p38's 9-12 tile shells killed nothing).
  - p42 (settle 4): 0.62 shells a kill, 119 of 150, hit at 149.8 s. **p43** (settle 8, `crowd_zombies=12 reload_room=6`):
    **PASS, 185.4 s**, 0.67 shells a kill, 17 tube reloads. A shell costs ~0.85 s (rack + settle) and the tube loads one
    shell at a time, ~0.9 s a kill against ~0.3 s for the M16: the shotgun is not the faster gun for this bench.

### Built-in benchmark parity and the character's kit (2026-10-23)

- The built-in benchmark's `horde` scenario (`BenchSuite`) is now the pier bench with the p39 flags (it was the 10-21
  baseball field). `benchmark-run.sh horde:3` (in-game autopilot, no Jev director, a fresh character): 44.3 / 44.6 / 52.3 s,
  all 150, 0 hits: the same as the harness's director runs (44.2-55.2 s).
- `buffs` (default with tactics, `HordeTactics.buffs`): Marksman (+20 hit chance, faster aim-delay drain), Dextrous (a new
  aim starts at x0.8 delay), Eagle Eyed (sight range x1.2), All Thumbs / Short Sighted removed, Sprinting 10 (the run at the
  game's 1.0 cap: 4.3 -> 5.2 tiles/s), Nimble 10 (moving penalty halved), ammo strap + chest rig (magazine reload x1.15),
  the glasses taken off once Short Sighted is gone. The bench save's character was **Short Sighted** (max sight range cut to
  the gun's minimum without glasses, `HandWeapon.getMaxSightRange`). Moodle peaks are logged (`horde_moodles_max`): none
  came up in any buffed run (Desensitized + the pinned endurance / fatigue already cover them).
- Results: p44 43.3, p45 39.9, p46 36.4, p47 44.8, p48 40.3 s, all PASS, 0.87-1.07 rounds a kill, 5-7 reloads.
- **Blur**: the game's vision blur (`IsoGameCharacter.updateVisionEffects`) is 1 for glasses *without* Short Sighted as
  much as for Short Sighted without glasses, so p44-p46 (trait gone, glasses on) were blurred outside the 3-tile circle.
  p47 took the glasses off, but `Scene.keepWornItems` (the Louisville guard) wore them again (`wornRestored=1 blur=1.00`
  at the route end). Since p48 the buffs unpin them (`Scene.unpinWorn`) and recompute the vision effect: route end
  `eyes=none shortSighted=false blur=0.00 wornRestored=0`, a frame at 45 s sharp to the edges. Blur is screen-only (the
  bot aims by bone and ballistics), so the clear times before p48 stand.
- Built-in benchmark with the buffs (`bench-h80-parity2`, `horde:3`, autopilot, fresh character): 36.8 / 34.0 / 37.0 s, all
  150, 0 hits, 1.03-1.10 rounds a kill, 5-6 reloads, no moodle, route end `eyes=none blur=0.00` in every pass; the
  harness's director runs on the same build 36.4-44.8 s (p44-p48). Parity, the built-in side a little faster and tighter
  (no director round trip of ~0.2 s a decision).

## The ambulance and its siren back (2026-10-23)

The maintainer: "add back the ambulance with the siren on", "leave the siren sound on". `siren=1` (default; 1-3 are the game's
three siren sounds, 0 off) is set on the parked `Base.VanAmbulance` and re-applied every tick with its lights; the game's own
100-radius world sound of a siren (sandbox `sirenEffectsZombies`, on by default) stays in, so it draws the horde like in play.
The built-in "horde" scenario carries `ambulance=Base.VanAmbulance siren=1` too.

- h80-p49, the van 4 tiles behind the pier spot (6434,5184): **FAIL**, damaged at 29.6 s. The kite ran the player back into
  the van (the steering tests squares through `Nav`, not vehicles), the reload it had started was cut, and the horde pinned
  the empty-handed player against it (player 0.8 tiles/s, nearest 0.1).
- Fix: `parkAmbulance` takes the most-behind spot of a ring `ambulance_back` (6) to +3 tiles round the pier spot, never on
  the horde's side, whose 5x5 squares are dry ground (straight behind is the river and the deck: h80-p50 found nothing and
  ran without a van, PASS 46.1 s), and puts its 5x5 box into HordeTactics' no-go boxes (`addNogo`). The van stands at
  6431,5193, beside the pier spot on the west.
- h80-p51..p53 with the van and the siren sounding: **PASS** 48.7 / 44.6 / 45.6 s, 0 hits, 1.03-1.11 rounds a kill.
- Built-in benchmark `horde:3` (`bench-amb`): 34.4 / 36.5 / 39.7 s, 150 killed, 0 hits every pass.
