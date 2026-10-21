# Zombie "phasing" (2026-10-18)

Workshop reports since the 10-16 release (Lirexio, Sigmagoon, Siowar, 飞翔的小企鹅, Crashdown, C.Delete, p6kocka; players'
workaround: "New zombies start as sprites" off) and the maintainer: zombies flash as naked grey-white mannequins for a split
second on a melee hit, a kill, a corpse drag, a hat or helmet falling off, and for 1-2 s when a horde first comes into view.

## Cause

- The figure is `IsoZombie.renderTextureInsteadOfModel`: DeadBodyAtlas's generic male / female body in the idle or walk
  pose, unclothed. Stock draws it only past the 510 nearest zombies, and black; `zombieFirstSightSprite` (on by default since
  round 11, 55c78883) drew it lit (`zombieLodSpriteLight`) and opaque while a zombie waited for its clothing composite.
- `ModelManager.Reset(chr)` (a hit, a helmet / hat falling, a death, an outfit change) gives a clothed zombie a fresh
  `ModelInstance` whose texture is the plain body texture and an empty ready list. `FirstSight.pending` judged "never had a
  composite" by exactly that test, so a zombie drawn clothed a frame earlier was held as the stand-in and faded back in over
  300 ms. A dragged corpse is a new zombie (`IsoDeadBody.reanimateZombieForGrapple`) and counted the same. Stock draws nothing
  for that frame and makes the composite in it: no gap.
- First sight of a horde: past the frame's grants (3 a frame at ~0.7-0.9 ms of render thread each) every zombie stayed the
  stand-in until its turn, 1-2 s for 150-300 zombies. A zombie whose model `zombieModelAddBudgetUs` put off was lit too.
- The player who fixed it by turning off the "other cores" options turned off `charDrawPrep`, without which
  `FirstSight.grant` never runs.

## Fix (worktree `phasing-fix`)

- `FirstSight.pending` stamps every zombie drawn with its clothing composite (`IsoZombie.pzoptClothedNs`); one stamped in
  the last 2 s never waits (stock order). A grapple-only reanimated corpse never waits. `anyPending` checks every zombie.
- Without `zombieLodDynamic` (the default) a zombie waiting at first sight draws nothing (stock while not ready), no sprite.
  New key `zombieFirstSightMaxWaitMs` (250): grants per frame rise to the even rate that finishes every waiting zombie by its
  deadline (iteration 1's hard 100 ms bound released up to 140 composites at once: 82-91 ms frames).
- `newSprite` no longer lights a zombie put off its model (`pzoptAddWait` removed): the stock unlit sprite again.
- With `zombieLodDynamic` on, the LOD's own sprite stays as designed (still the unclothed body).

## Evidence

- Rig `--flag melee=3` (`pzopt.MeleeRig`, 2026-10-18): three still zombies pinned east of the player, the area's own zombies
  removed, the player walks into reach (0.85 tiles) and swings through `IsoPlayer.AttemptAttack` every 1.1 s; logs every hit /
  kill / spawn with the zombie's screen place and every frame run in which the build drew the stand-in. Judge
  `harness/phasing-events.py` (per event, the zombie's box: peak share of the stand-in's warm-grey pixels minus a second
  later; Jev compares test and controls). The frame-wide `harness/phasing-judge.py` caught the first-sight horde (25.8 s,
  `zr-horde-def`) but not the translucent melee flashes.
- Release `pz-v7-rel`: 19 stand-in episodes (18 after a spawn, ~350 ms each, 1 after a hit), 12 events flagged in the
  recording (max share 0.132). Fix `pz-v7-fix`: 0 flagged (max 0.052). Stock `pz-v7-stock`: 0 flagged. Jev: phasing 0.92.
  By eye: `pz-walk-rel` 15.30 s, the zombie being killed is a translucent naked figure; 16.80 s a respawn stands as a
  mannequin. Clip `phasing-repro/repro/phasing/phasing-release-vs-fix.mp4`.
- Horde first sight: release `zr-horde-def` 0.77 s burst; fix `pf1-gun`, `pf2-gun` 0 events, clothed horde at 25-27 s.
- Frame time: M16 horde at 120 cap fix 119.8 fps / p99.9 11.8 ms / max 45.3 vs release 119.2 / 29.1 / 60.2. Louisville at
  120: fix 114.4-116.4 fps, p99 16.3-16.7 vs release 111.0-113.4, 16.7 (first fix run 111.0 / 21.1 with 19 game-thread-bound
  frames in its first route second, not repeated).

## Open

- A zombie at first sight can be invisible for up to 250 ms (nearest first, during stock's fade-in). Fallback:
  `zombieFirstSightSprite=false` by default (tails at parity on the 4090, `pf-lou-nofs`).
- Not tested on AMD / Mesa (the flip): the "heads only" report (diannea / agnin, fixed by `hordeBatch` off) is still open.
