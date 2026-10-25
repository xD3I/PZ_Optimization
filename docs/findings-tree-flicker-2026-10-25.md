# Tree crown flicker (2026-10-25)

The maintainer's video (`~/Videos/Project Zomboid/Video_2026-10-25_02-15-59.mp4`: night, torch, walking on the park lawn at
8184,11444 with their options) shows the big pink dogwood south-east of the lawn (XXL tree `e_dogwoodJUMBOXXL_1_0` at
8196,11455) in the game's tree cutaway. Its crown is see-through round the player. For 1–3 frames at a time the whole crown
was drawn opaque, then turned see-through again. The video has 44 such pops between 15.0 and 23.2 s, every 85–270 ms (median
~133 ms), while the player walks next to the tree at the wider zoom. Branch `tree-flicker` (agent tree-flicker, coordinator
session b8396cf2), merged into master as 047b65c0.

## What Jev saw

`harness/trees/tree-pop.py` (Jev) counts the crown pop events and says whether a run pops, against a control run. On the
video it counts 68 pop events in 33 s (49 large; 22 lasted 2 frames, 45 lasted 3); Jev: pops_present yes 0.94, verdict pops
(0.85). The video has no control. Other video tools: `vid-blocks.py` (16x16 block means of the PQ luma, run as a queue cmd job),
`vid-blink.py` (1–3-frame flashes) and `vid-strip.py` (1:1 frame strips).

## Reproduction and bisection

Rig, run through the queue with `--install ~/pzopt-wt/tree-flicker` and the maintainer's options file as
`-Dpzopt.userOptionsFile`: `--mode bench --flag start=8184,11444 --flag explore=walk --flag "walk=8184,11444;8193,11444"
--flag zombies=off --flag time_of_day=1.15 --flag weather=clear --flag lights=on --flag torch=on --flag zoom=1 --option
frameRate=165 --prop devCapture=6,20,1000,100,gray,crop=1960:980:1400:800,ram`.

| Run | Setup | Pops / 10 s |
|---|---|---|
| tf-b-maint | maintainer's options, 165 cap (156 fps) | 27.0 |
| tf-b-maint60 | maintainer's options, 60 fps | 44.5 |
| tf-b-defaults | empty options file (build defaults) | 0 |
| tf-h-nopass | maintainer + `treeBakePass=false` | 27.5 |
| tf-h-perframe | maintainer + `treesInChunkTexture=false` | 27.0 |
| tf-f-G | ground keys only | 31.0 |
| tf-f-B2ng | blades, sway, mirrors, outlines only | 38.0 |
| tf-e-B1 | reflections, relief, ripples, filter, glints, blood, car glass only | 0 |

The tree bake has nothing to do with it: the pops stay when trees are drawn per frame. The two groups that pop on their own
have one thing in common, `pzopt.GroundState`, the GPU map of where the player has stepped. The ground tracks use it, and so
do the blades' and Sway's feet push.

## Cause

`devStencilProbe` was extended to log the cutaway's stencil bit along a row and a column through the cutaway centre, at four
points in the frame (`a0-beforeMask`, `a-afterMask`, `b-zloop`, `c-trees`), plus the GL state (`<run>/pzopt-stencil.out`).

- **tf-i-stencil:** 57 frames of a 20 s capture had no cutaway mask right after `IsoCell.drawStencilMask`. All 56 pop
  events fell within 3–9 ms of one of those frames.
- **tf-i-stencil2:** on all 44 frames that lost the mask, GL program 0 was bound before the mask was drawn. On normal frames
  it is program 1, the game's default sprite program. 46 more frames had program 0 and still kept the mask: the
  fixed-function draw writes the stencil only some of the time.

`GroundState.renderFrame` is queued at the top of the cell render, before the mask, and runs whenever the player stamps new
ground cells (about every 200 ms while walking). It ended its compute with `glUseProgram(0)` +
`ShaderHelper.forgetCurrentlyBound()`. The sprite renderer's batches bind no program, so the mask quad drew fixed-function and
often wrote no stencil. FBORenderTrees' pass for the part outside the mask (`NOTEQUAL 128`) then drew the see-through crown at
full opacity for that frame.

This is the same family as EntityShadow's early compute on 2026-10-08 (runs `xxl-bis-*`), which was moved rather than fixed.

## Fix

- **`pzopt.DefaultProgram.restore()`:** `forgetCurrentlyBound` + `ShaderHelper.glUseProgramObjectARB(0)`, which binds the
  game's default program and keeps ShaderHelper's cache in step. It now ends the frame-start passes that run before the mask:
  GroundState's compute and its read-back pack, RainRipples, RipplesGpu and Wakes. EntityShadow's two computes use it too.
- **Texture bind cache:** GroundState's chunk upload unbinds `GL_TEXTURE_2D`, so it now resets `Texture.lastTextureID = -1`
  (the sprite renderer's bind cache).
- **Guard in `FBORenderCell.renderInternal`:** the default program is queued right before `IsoCell.drawStencilMask`, so the
  mask draws with it whatever raw-GL pass ran before. Key `stencilMaskProgram`, default on, an A/B key with no menu entry.

| Run | Setup | Pops / 10 s |
|---|---|---|
| tf-j-fix | fixed build, maintainer's options | 0 (3117 frames) |
| tf-j-fix-off | fixed build, `stencilMaskProgram=false` (source fix alone) | 0 |
| tf-j-fix-probe | fixed build with the probe | 0; mask lost on 0 of 3006 frames, program 1 before the mask on every frame |

- **Jev, before:** tf-b-maint against tf-b-defaults: pops_present yes 0.99, verdict pops (1.00).
- **Jev, after:** `tree-pop.py --test tf-j-fix --before tf-b-maint --control tf-b-defaults`: pops_present no (0.02),
  verdict clean (1.00), fixed_vs_before yes 0.98.

## Cost

Fixed build, guard on against guard off, on the same walk, `--no-mangohud`, no capture:

| Run | fps | p99 / p99.9 | GPU | Game / render thread |
|---|---|---|---|---|
| tf-k-unc-on (uncapped) | 558.0 | 3.4 / 4.9 ms | 95 % (sysmon 99 %) | 54 / 57 % |
| tf-k-unc-off | 553.8 | 3.3 / 5.0 ms | 95 % | 56 / 59 % |
| tf-k-cap-on (165 cap) | 156.9 | 6.6 / 8.1 ms | 29 % | 25 / 36 % |
| tf-k-cap-off | 156.9 | 6.6 / 7.9 ms | 29 % | 25 / 36 % |

Both differences are inside the run spread. Uncapped, the GPU is saturated, so that run is GPU-bound. Capped, it is
neither CPU- nor GPU-bound: it is held by the cap. The source fix is one `glUseProgram` on the frames that ran one of those
computes, which bound program 0 there before; it is not measurable.

## Open

- About 20 more raw-GL passes still end with `glUseProgram(0)` + forget: GodRays alone has 14 such sites, plus VoxelLight,
  PixelLight, Mirrors and others. The guard covers the mask, but a plain sprite drawn after one of them in the frame would
  still draw fixed-function. Switching them to `DefaultProgram.restore()` is untested.
- `stencilMaskProgram` has no menu entry (A/B key only).

Agent notes (gitignored): `~/pzopt-wt/tree-flicker/build/notes.txt`; before / after picture `build/tf-before-after.png` there.
