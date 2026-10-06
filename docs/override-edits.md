# Edits to the overridden game classes

`src/overrides/` is **not in the repository**: it holds decompiled game code.
Regenerate it with `scripts/regen-overrides.sh` (Vineflower output of
`zombie.iso.IsoChunk` and `zombie.iso.WorldStreamer` from the installed jar,
revision `4a0e9546ec`, Build 42.21, since 2026-09-28; before that `b0bbce05d5`, Build 42.20.4), then re-apply
the edits below by hand (the last section, "Port to Build 42.21", lists what the port changed). Every edit is
marked `// pzopt:` in the working copy. One decompiler fix is needed first:
the `switch` expression on `carSpawnRate` in `IsoChunk.addVehicles` lacks a
`default -> chance;` arm.

## zombie.iso.IsoChunk

1. **Load marker.** A `static {}` block at the top of the class calling
   `pzopt.Overrides.onClassLoaded("zombie.iso.IsoChunk")`.
2. **Split of `loadInWorldStreamerThread()`.** The method becomes
   `recalcLoop1(); recalcPooled();`. `recalcLoop1()` is the original first
   loop (per level/square: create missing squares, `ensureNotNull3x3`,
   `RecalcProperties`). `recalcPooled()` is everything after it — the rain/roof
   column pass, the `RecalcAllWithNeighbours(true, getter)` pass, and the
   `propertiesDirty = true` pass — with one difference: instead of binding the
   static `chunkGetter` (`assert chunkGetter.chunk == null; chunkGetter.chunk =
   this; … chunkGetter.chunk = null;`) it allocates a local
   `IsoChunk.ChunkGetter`, sets its `chunk` to `this`, and passes that. Both new
   methods are `public`. At the top of `recalcPooled()`, a dev-only injected
   failure: if `pzopt.Guard.DEV` and `pzopt.RecalcPool.failChunk` equals
   `"wx,wy"` of this chunk, clear `failChunk` and throw
   `IllegalStateException`.
3. **Frame hook.** First statement of `update()`:
   `pzopt.Stats.frameTick(IsoCamera.frameState.frameCount);`.
4. **Dev-build guards** at the two pathfinding-registration gates: a
   `pzopt.Guard.assertGameThread(...)` call immediately before the
   `if (… Thread.currentThread() == GameWindow.gameThread || … GameServer.mainThread)`
   test in the level-change path, and immediately before the
   `MapCollisionData.instance.addChunkToWorld(this)` block in
   `doLoadGridsquare()`.
5. **Decompiler fixes in `AddVehicles_OnZone` (2026-09-23, Workshop reports "cars barely
   spawn").** Two Vineflower mis-renders, present since the override was first committed,
   restored to the jar's bytecode (not optimizations): the stall-row loop was a `while`
   ending in an unconditional `break`, so only the first row of stalls of each parking /
   driveway zone in a chunk ever spawned (the "type not found" path advanced a row and broke
   out too); it is a labelled `for (y = yOffset; …; y += stallLen)` again, and the not-found
   path is `continue rows`. The `carSpawnRate` switch rendered stock's `chance *= 2` (High)
   as `case 5 -> 2`, a flat 2 % per stall; it is `chance * 2` again. Checked by compiling the
   override and comparing the method's `javap -c` with the jar's (same `imul` / `Rand.Next`
   counts, outer back-edge restored).

Imports added: `pzopt.Guard`, `pzopt.RecalcPool`.

## zombie.iso.WorldStreamer

1. **Load marker + settings line.** A `static {}` block calling
   `pzopt.Overrides.onClassLoaded("zombie.iso.WorldStreamer")` and
   `pzopt.Log.info("settings: " + pzopt.Config.describe())`.
2. **Streamer wake-up.** In `create()`, the worker lambda calls
   `pzopt.StreamerWake.register()` before its loop. In `addJob(...)`, after
   `this.jobQueue.add(chunk)`: `pzopt.StreamerWake.signal()` (preceded by
   `pzopt.Stats.onEnqueued(chunk)`). In `threadLoop()`, the two
   `Thread.sleep(140L)` calls (the one taken when `jobList` is empty, and the
   one at the end of the loop when a player exists) become
   `pzopt.StreamerWake.idle(140L)`. The `Thread.sleep(20L)` and
   `Thread.sleep(0L)` are unchanged.
3. **Retries.** In `threadLoop()`, `pzopt.RecalcPool.runRetries();` just
   before the loop that drains `jobQueue` into `jobList`.
4. **Pool hand-off in `DoChunkAlways(chunk, fromServer)`.** Around the body:
   `pzopt.Stats.Timing timing = pzopt.Stats.begin(chunk);` before
   `chunk.LoadChunk(...)`, and `timing.loadEndNs = System.nanoTime();` after
   `VehiclesDB2.instance.loadChunk(chunk)`. In the non-Convert/non-SoftReset
   branch: if `pzopt.RecalcPool.active()` and the current thread is
   `this.worldStreamer` and `chunk.refs` is non-empty, call
   `chunk.recalcLoop1()` then `pzopt.RecalcPool.submit(chunk, timing)` inside
   the existing `try/catch (Exception) { ExceptionLogger.logException(ex); }`,
   and `return` on success (the pool publishes to `IsoChunk.loadGridSquare`);
   if loop 1 threw, fall through. Otherwise the stock path, with
   `timing.recalcStartNs`/`thread`/`recalcEndNs` recorded around
   `chunk.loadInWorldStreamerThread()` and `pzopt.Parity.capture(chunk)` after
   it. After the stock `IsoChunk.loadGridSquare.add(chunk)`:
   `timing.publishNs = System.nanoTime(); pzopt.Stats.done(chunk, timing);`.
5. **`isBusy()`** additionally returns true when
   `pzopt.RecalcPool.inFlight() > 0`.
6. **`stop()`**: after the streamer thread has ended ("stop 3" debug line) and
   before `this.worldStreamer = null`: `pzopt.RecalcPool.drain();
   pzopt.Stats.flush();`.

## zombie.iso.ChunkSaveWorker (added 2026-09-18)

Regenerated with `scripts/regen-overrides.sh` (now lists this class); the
pristine Vineflower output compiles as is.

1. **Load marker.** A `static {}` block calling
   `pzopt.Overrides.onClassLoaded("zombie.iso.ChunkSaveWorker")`, plus two
   static fields: the nanosecond stamp of the last ancillary hot save and a
   counter of skipped drains.
2. **Hot-save throttle.** In `Update(IsoChunk)`, the branch that calls
   `HotsaveAncilliarySystems()` when the save queue has just drained now runs
   it only if `pzopt.Config.HOTSAVE_INTERVAL_SEC` is 0 (stock), the overrides
   are disabled, or at least that many seconds have passed since the previous
   hot save; otherwise the drain is counted and skipped (logged with the count
   when the next hot save runs). Background: the hot save serialises the whole
   meta grid (`IsoMetaGrid.saveToBufferMap`, building/room counts of every
   meta cell) on the game thread via `MainThread.invokeOnMainThread`, and
   while moving the queue drains about every 0.45 s (JFR, `attr-jfr-1`), so it
   was a 2–5 ms game-thread stall twice a second.

## zombie.core.VBO.GLVertexBufferObject (added 2026-09-18)

Regenerated the same way (no inner classes). All edits are guarded by
`pzopt.Config.PERSISTENT_VBO && pzopt.Overrides.enabled()` and by the
`GL_ARB_buffer_storage` / OpenGL 3.2 capabilities; otherwise every method is
stock.

1. **Load marker** in a `static {}` block.
2. **Persistent mapping for fixed-size buffers.** New private state: a
   `persistent` flag, a per-buffer fence handle, a static list of buffers
   unmapped since the last fence, and dev counters (waits, wait time, stalls).
   The no-argument `map()` (used by the sprite `RingBuffer`, the water
   `SharedVertexBufferObjects` and the world-map VBOs, all constructed with a
   size) takes a new path: the first call allocates immutable storage with
   `glBufferStorage(MAP_WRITE | MAP_PERSISTENT | MAP_COHERENT)` and maps it
   once with the same flags; later calls only reset the buffer position. Before
   handing the buffer out it (a) creates one `glFenceSync` per buffer unmapped
   since the last map — those buffers' draw calls were issued in between — and
   (b) waits on this buffer's own fence with `glClientWaitSync(FLUSH, 1 s)`, so
   the CPU never overwrites a batch the GPU is still reading (the stock ring
   has 128 buffers, which is the depth of the pipeline before a wait happens).
   `unmap()` on a persistent buffer only records it in the pending list;
   `clear()` returns early (no `glBufferData` on immutable storage);
   `doDestroy()` unmaps for real and deletes the fence.
   Stock behaviour was `glBufferData` (orphan) + `glMapBufferRange(WRITE |
   INVALIDATE_RANGE | UNSYNCHRONIZED)` per 64 KB batch, ~25 % of render-thread
   samples at max zoom (`attr-jfr-z25`). Default off since 2026-09-19
   (evening): with the in-game 240 fps limiter back, two 120 km/h drives with
   it on and off had the same frame profile (mean 4.2 ms, p99 7.2 / 7.3 ms), and
   one stationary run with it on drew a whole building lot floor opaque black
   for a minute (`artfix-opt120-2`), which its fence logic is the only edit
   able to cause. Re-enable with `--prop persistentVbo=true` for uncapped runs.
   2026-09-20 afternoon: the black chunk squares seen with it on were the light-info
   chunk gate (see the FBORenderCell entry of that day), not the fence logic: a
   `glFinish` before every map (`persistentVboFinish`) still showed them, and the
   fix in FBORenderCell removes them with the fences untouched.
3. **Diagnostics and variants added during that bisect (2026-09-20), all off by
   default and marked `// pzopt:`:** `persistentVboFrameFence` (one fence per frame
   from `RenderThread` after `SpriteRenderer.postRender`, `pzoptFrameEnd()`, kept in
   an 8-entry ring; a slot is rewritten only after the frame it was drawn in is
   done; `persistentVboFrameLag=N` also waits for the N following frames);
   `persistentVboSlots=K` (K immutable storage buffers per `GLVertexBufferObject`,
   rotated and re-bound on every `map()`, so the 128-buffer ring reuses a slot
   after 128*K batches; slot 0 keeps the original buffer id);
   `persistentVboCoherent=false` (MAP_FLUSH_EXPLICIT plus `glFlushMappedBufferRange`
   at `unmap()` instead of MAP_COHERENT); `persistentVboDelayUs` (CPU-only park per
   map); `persistentVboFinish` (`glFinish` per map). With `instrument=true` a
   "persistent VBO:" line every 5 s counts maps, maps per frame, per-batch and
   per-frame fence waits and stalls.

## zombie.iso.fboRenderChunk.FBORenderCell (added 2026-09-18)

Regenerated with Vineflower; three decompiler fixes were needed before it
compiled: two dropped `boolean` declarations in `renderTilesInternal`
(the `runChecks` / `recalculateGridStacks` result variables), and explicit
comparator types for the three `timSort.doSort` lambdas (world inventory
objects, chunks by lighting counter, translucent squares). Every fix is marked
`// pzopt: decompiler fix`.

1. **Load marker** in a `static {}` block before the private constructor.
2. **Trees in the chunk texture.** `isTreeRenderedEveryFrame(IsoObject)`
   returns `false` when `pzopt.Config.TREES_IN_CHUNK_TEXTURE` is set and the
   overrides are enabled (stock: `object instanceof IsoTree`). With that, a
   tree is classified `Translucent` (drawn every frame) only while it is in
   the player stencil (`isTranslucentTree`), fading, wind-animated or carrying
   render effects; every other tree bakes into its chunk-level texture, and
   the existing `checkTreeTranslucency` pass invalidates the level (flag 4096)
   when a tree changes state. Two additions (2026-09-19, evening): a tree whose
   texture is not ready yet (`Asset.isReady`) stays per frame and
   `checkTreeTranslucency` re-dirties its level when the texture arrives; and
   `renderMinusFloor(IsoObject)` bakes a tree with `FBORenderTrees.current`
   temporarily null (`treeBakeDirect`, default on), so `IsoTree.render` takes
   the plain sprite path. The batch path (`FBORenderTrees` in chunk-texture
   mode) dropped most JUMBO trees around town buildings (they never appeared,
   even after a forced redraw), while the plain path draws every tree.
   Per-frame JUMBO trees cost 8.1 ms mean on the max-zoom route versus 4.2 ms
   baked, so this is the difference between the edit paying off and not. Measured on the max-zoom teleport route
   (`trees-1` vs `pvbo-1`): frame mean 6.2 → 5.2 ms, p99 18.7 → 17.4 ms, GPU
   busy 84 → 61 %.
3. **Windows and glass doors in the chunk texture.** In
   `isObjectRenderLayer_Translucent`, the `object instanceof IsoWindow` and
   the `doorTrans` door clauses are skipped when
   `pzopt.Config.WINDOWS_IN_CHUNK_TEXTURE` is set (they still go per-frame
   through the fading / obscuring-player clause that follows).
   Default off since 2026-10-01 (Workshop discussion "zombies not visible at windows", RCwuhui's comment of
   2026-09-30 "the window looks like it's become opaque"; glass doors and showers too): a baked window goes
   through `tileWithDepth.frag`, which writes the sprite's depth for every texel the depth texture covers, glass
   included, while stock draws windows in `renderOneChunk_Translucent` with `glDepthMask(false)`. A zombie outside
   the pane (farther from the camera than the glass) then failed the depth test against the composited chunk
   texture and was not drawn where the window covers it. It measured no gain when adopted (`windows-1` vs
   `hotsave-1`: 5.0 / 5.0 ms mean). Rig: harness `--flag find=winzombie` (runs `wz3-win-on` / `wz3-win-off`: a
   zombie behind the right-hand room's window shows through the glass only with the key off).
   The maintainer's bus shelter (Riverside 6209-6213,5290-5292, 2026-10-01 screenshot): its panes are windows
   (`walls_commercial_01_96` / `_97`, `IsoWindow`, glass alpha 0.6-0.8 under opaque brown frame strips
   `walls_detailing_01_36` / `_45`). Runs `gs2-on` / `gs2-off` / `gs2-stock` (player at 6207,5288 behind it, three
   pinned zombies, `--flag pin_zombies=`): with the key on the zombie behind the left pane came out washed out and
   cut; off it matches stock. The player's body behind the shelter is hidden in stock too: it stands behind the
   opaque top strip, only the head shows above it.
4. **`Translucent`-flagged tiles in the chunk texture.** A private static
   helper `pzoptPerFrameTranslucentTile(IsoSprite)` returns
   `depthFlags & 2 != 0` unless `pzopt.Config.TRANSLUCENT_TILES_IN_CHUNK_TEXTURE`
   is set; it replaces the three literal `depthFlags & 2` tests (the early
   `return true` in `isObjectRenderLayer_Translucent`, the early `return
   false` in `isObjectRenderLayer_MinusFloor`, and the `TranslucentSE` /
   `MinusFloorSE` choice in `calculateObjectRenderLayer`). Default off since
   2026-09-19 (evening): the flag is the tileset property `Translucent`
   (road decals, dirt patches, puddles), and baked into the opaque chunk
   texture those tiles come out as opaque black one-tile rectangles on the
   floor (the maintainer's screenshot, walking, not only at speed). The per-frame
   pass draws about 20 of them per frame; not worth it.
   Since 2026-09-28 the helper also keeps a Translucent light fixture with a lit
   sprite (`IsoFlagType.HasLightOnSprite`) per frame while
   `pzopt.Config.TRANSLUCENT_LIGHTS_PER_FRAME` (`translucentLightsPerFrame`,
   default on) is set. Baked, such a fixture's "on" sprite (`<tileset>_on_<n>`)
   is drawn by the per-frame animated-attachments pass, where
   `IsoSprite.startTileDepthShader` pulls it 5e-5 towards the camera so it wins
   over its own baked lamp; the top of a ceiling fixture lies in the plane of the
   floor above, so the pull put the lit tubes of the Fossoil canopy
   (`lighting_indoor_03_19..21` on level 1, roof `location_shop_fossoil_01_38/39`
   on level 2, Riverside 6056-6063,5302-5308) on top of the roof as dashes that
   moved with every zoom step (maintainer's save `Sandbox/2026-09-28_22-02-56`).
   Per frame the fixture and its lit sprite go through the translucent pass with
   no pull, as in stock. A depth nudge of the baked lamp changed nothing (the
   baked lamp never showed; `_20` / `_21` have no tile geometry either), and a
   bake that left every Translucent tile out still showed the dashes: the lit
   sprite was the whole problem. Runs `canopy-*` (2026-09-28): white pixels on
   the roof 70-270 per frame, jumping with the zoom, before; 5-45 and steady per
   zoom level after, like `translucentTilesInChunkTexture=false` and stock. Rig:
   harness `--flag find=translucent [--flag find_box=x0,y0,x1,y1]` lists the
   objects (sprite, class, depth flags, attached / overlay sprites, the floor
   above).
   Since 2026-10-01 the helper also keeps Translucent glass tiles per frame while
   `pzopt.Config.GLASS_TILES_PER_FRAME` (`glassTilesPerFrame`, default on) is set:
   `pzopt.GlassTiles` says a sprite is glass when its definition names a glass
   material (`MaterialType` / `Material` / `Material2` / `Material3` containing
   "Glass": Glass, Glass_Light, Glass_Solid), a glass `GroupName` or has a
   `GlassRemovedOffset` (182 of the 15.8k Translucent definitions of 42.21: shop
   and restaurant display cases, glass-door fridges, escalator and mall
   balustrades, glass doors, shower screens), cached per sprite. Baked, such a
   pane writes its depth like any baked tile (`tileWithDepth.frag` keeps every
   texel the depth texture covers; the alpha test only drops alpha 0), so a
   character behind it fails the depth test against the composited chunk texture;
   stock draws the pane per frame after the characters with depth writes off and
   it blends over them. A depth-only second bake pass for the opaque texels was
   tried and dropped: the panes are 0.6-0.8 alpha, so the character came out on
   top of the glass instead of behind it, and every Translucent bake drew twice.
   Rig: harness `--flag find=glasszombie` (nearest such tile, three zombies pinned
   behind it in the same room). Confirmed against stock with `harness/glass-judge.py`
   (2026-10-02, runs gw-* vs gv-*-stock / -stock2, default options): the zombie regions
   behind the glass were 2.5-7.3x the stock-to-stock difference before, 0.67-0.98x after,
   in both scenes (shelter windows, placed `location_shop_mall_01_24` balustrade); Jev:
   balustrade fixed 0.88, shelter fixed 0.51 vs not_fixed 0.43 on the same numbers.
   Since 2026-10-05 the helper (now taking the object) also keeps Translucent tiles
   that lie on the floor of a puddle square per frame while
   `pzopt.Config.FLOOR_DECALS_PER_FRAME` (`floorDecalsPerFrame`, default on) is set:
   `pzopt.FloorDecals` says a sprite lies on the floor when its texture's opaque
   rectangle fits the floor diamond at the bottom of the tile (half the tile's width
   high, plus a sixteenth of the width for a rim; cached per sprite: manhole covers,
   drains, litter, broken glass), and the square draws puddles (a floor, puddle
   geometry that renders: the same test the bake walk caches the puddle squares
   with). Stock draws such a tile in the translucent pass after the puddles, so it
   covers the water. Baked, it sat in the chunk texture under the puddle pass,
   which is depth-tested against it: the cover's object depth (no tile geometry,
   `UseObjectDepthTexture`) lies within the puddle's 1e-4 lift over the floor, and
   the two traded places in horizontal bands that moved with the camera
   (maintainer's report "manholes flicker zoomed out all the way, in the rain
   puddles", `street_decoration_01_15`). Bisected with `harness/manhole-flicker.py`
   (Jev walks circles at max zoom, puddles pinned full): stock clean, puddle keys,
   enhancements, fogPass, tileDepthFix, zoomRetain all innocent,
   `translucentTilesInChunkTexture=false` alone clean. Before: bands in 436 of 520
   frames, 31 % of the cover changing per frame; after: 0 band frames, 1.2 %
   (stock 0.3 %); Jev fixed 0.95. In daylight rain (`weather=rain`): bands in 107
   of 520 frames before, 0 after, 0 in stock; the cover adds 8.5 % flips over the
   road beside it before, 0.6 % after (stock -2.5 %; our rain streaks flicker the
   road itself ~2 points more than stock's); Jev fixed 0.83.
   Video: `docs/media/manhole-puddle-flicker-before-vs-fix.mp4` (`harness/stitch-manhole.sh`). Cost: the per-frame translucent pass draws ~200
   more tiles a frame on the wet spin route (420-480 vs 200-280; nothing baked
   would be ~3,000); uncapped spin 352 vs 377 fps over four noisy pairs.
5. **Dev counters** (only with `instrument=true`): `renderTranslucent(IsoObject)`
   and `renderTranslucent(IsoGridSquare)` count what the per-frame pass draws
   by kind (window, door, tree, Translucent-flagged tile with a per-tileset
   tally, animating, fading, other, squares); `renderInternal()` logs the
   per-frame averages every 1800 frames as "translucent pass per frame".
6. **Bake budget** (`pzopt.Config.BAKE_BUDGET`, 0 = stock). Two new fields
   (bakes started this frame, the set of textures deferred this frame; both
   reset at the top of `performRenderTiles`) and a block at the top of the
   render decision in `renderOneLevel`, before `beginRenderChunkLevel`: when a
   level is dirty and, at its texture's lowest level, the frame has already
   started `BAKE_BUDGET` bakes, the texture is deferred (its upper level follows
   the decision through the set). Since 2026-09-19 (evening) only a never-baked
   level (`DIRTY_CREATE` still set) can be deferred: a re-bake of a texture that
   is already on screen (obscuring set, trees, cutaways, lighting, object
   changes) always lands in the same frame, because drawing the stale texture
   for a frame while the per-frame translucent list already reflects the new
   state showed windows and glass doors flickering as the car passed buildings. A deferred level whose texture was baked
   before (`DIRTY_CREATE`, 512, no longer set) takes the existing "clean"
   path — the manager's current chunk is pointed at its texture,
   `endRenderChunkLevel(..., false)` queues it for drawing and the cached
   translucent lists are re-registered; a never-baked level returns without
   drawing. Deferred levels are retried next frame in chunk order.
7. **Lighting budget** (`pzopt.Config.LIGHTING_BUDGET`, 0 = stock). With a
   budget `updateChunkLighting` calls a new private
   `pzoptUpdateChunkLightingBudgeted`: in the frame the lighting counter
   changes it still asks `LightingJNI.getChunkDirty` for every on-screen chunk
   level (sorted by chunk lighting counter as in stock) and records the dirty
   levels as a bitmask per chunk in a `LinkedHashMap<IsoChunk, Long>`; then, on
   that frame and the following ones, it refreshes the square light info
   (`cacheLightInfo` over the level's renderable squares) of at most that many
   chunks per frame, oldest entry first, dropping entries whose chunk left the
   on-screen list. The first version (2026-09-18) simply returned after the
   budget and re-entered the loop next frame; the JNI rewrites its dirty bits
   on its next pass, so the chunks it had not reached lost their update and
   stayed with stale light info (unlit tiles and tree silhouettes behind the
   car at 120 km/h, darker chunk-sized patches on grass). The stock branch
   (budget 0) is unchanged, including the debug-only `Lighting.SplitUpdate`.
8. **Occluder-mask replay** (`pzopt.Config.CUTAWAY_FAST`). In
   `calculateOccludingSquares(int)`, a chunk level that is not dirty and whose
   mask was computed before (tracked in a bounded `HashSet<ChunkLevelData>`)
   has its stored `occludingSquares[playerIndex]` bitmask replayed into the
   occluded grid (bit → square x/y, level z, same window test and `max`
   update as the stock per-square loop); dirty or never-computed levels run
   the stock `ChunkLevelData.calculateOccludingSquares`. The replayed mask is
   pzopt's own (`pzoptExactOccluderMask`, computed after the stock call with
   the stock test but without the on-screen clip). Since 2026-09-20 the mask
   lives on the `IsoChunk` override (a `long[64]` indexed by level + 32 plus a
   bit per stored level, cleared in `resetForStore`) instead of a map keyed by
   `ChunkLevelData`: the per-frame map lookup for every on-screen level was
   1.8 % of the game thread. The stock `occludingSquares` mask is built with an `int`
   shift (`1 << x + y * 8`): bits 32-63 wrap and bit 31 sign-extends when cast
   to long, which stock never notices because it only compares the mask with
   its previous value. Replaying it marked whole rows of tiles beside house
   walls as occluding, drawn as black one-tile rectangles (2026-09-19). Every input of that
   test (cutaway flags, vision matrix, square existence) dirties the level
   when it changes, so a clean level's mask is current.
9. **Cutaway visit radius** (`pzopt.Config.CUTAWAY_RADIUS`, chunks). The
   `doCutawayVisitSquares(playerIndex, chunks)` call in `renderTilesInternal`
   receives, instead of every on-screen chunk, the on-screen chunks whose
   chunk coordinates are within the radius of the camera character's chunk
   (a reused list; the stock list when the radius is 0).
10. **Grid-stack interval** (`pzopt.Config.GRID_STACK_INTERVAL`, frames). In
    `recalculateGridStacks`, `CalculatePointsOfInterest`,
    `CalculateBuildingsToCollapse` and `checkHiddenBuildingLevels` are skipped
    while the camera character's square and facing are the same as at the
    last scan and fewer than that many frames have passed (never skipped when
    `player.dirtyRecalcGridStack` is set); `recalculateAnyGridStacks` still
    runs every frame.
11. **Tree translucency check** (2026-09-20). In `checkTreeTranslucency` the
    `HashSet.remove` of the trees-awaiting-texture set is only attempted
    when the set is not empty (it almost always is: 1.9 % of the game thread
    was that remove per tree per frame), and the aim-key state is read once
    per chunk level instead of once per tree: `isTranslucentTree(IsoObject)`
    now delegates to a private overload that takes the aim flag.
12. **Re-bake budget** (`pzopt.Config.REBAKE_BUDGET`, default 4;
    `REBAKE_MAX_FRAMES`, default 3; 2026-09-20). Inside the bake-budget block
    of `renderOneLevel`: a texture that was baked before and whose dirty flags
    are only lighting (32), redraw (1024) and/or cutaways (2048) is deferred
    (previous image drawn through the existing stale-texture path) once that
    many such re-bakes have started this frame, for at most
    `REBAKE_MAX_FRAMES` frames per texture (an identity map from texture to
    the frame it was first held, bounded at 4096). Object, item, tree and
    obscuring changes are never held, for the flicker reason in item 6. On
    the 25 s Rosewood teleport route with the facing spinning, bakes were
    2.4 to 4.3 per frame and 87 % of them re-bakes; this took the p99 from
    15.6 to 11.2 ms (`docs/archive/2026-09-24/results.md`, 2026-09-20). Counters
    `budgeted rebakes` / `held` join the instrument line.
13. **Defaults changed 2026-09-20**: `lightingRebakeMs` 0 → 250,
    `cutawayRadius` 0 → 6, `gridStackInterval` 0 → 8 (measured on the same
    route: +8 fps for the two cutaway keys, +7 fps for the lighting hold;
    recordings side by side with the keys off show the same frames).

## zombie.GameWindow

The update check starts in `mainThreadInit` right after the `server` property is read
(2026-09-26, `if (!GameServer.server && pzopt.Overrides.buildMatches()) pzopt.Updater.check();`),
so its answer is there when the menu appears (the menu's own call is then a no-op).

Two edits. In the boot sequence (`init`, between `Translator.loadFiles()` and
`LuaManager.init()`): the call to `doEpilepsyWarningText()` is wrapped in
`if (!pzopt.Overrides.enabled())`, so the photosensitivity warning frame is
not drawn at start-up while the build guard is active. The method itself is
unchanged.

In `mainThreadStep` (edited 2026-09-19, reverted to stock later that day): the
stock limiter is back. `frameStep()` runs once the `accumulator` reaches
`1 s / PerformanceSettings.getLockFPS()` (the `frameRate=` value from
options.ini) unless `isFramerateUncapped()`, exactly as in stock. Earlier that
day the condition had `|| pzopt.Overrides.enabled()` appended, which made every
main-loop iteration a frame whenever the build guard was active; that removed
the player's choice, so it was undone.

In `mainThreadStep` (second edit, 2026-09-19): the two reads of the cap,
`isFramerateUncapped()` and `getLockFPS()`, go through `pzopt.FrameCap.uncappedNow()`
and `lockNow()`. Those return the in-game values while a world is up or loading
(`isIngameState()` or the current state is `GameLoadingState`) and the separate
menu cap otherwise; with the build guard off or the menu cap left at "same as
in-game" they are the stock values, so the loop shape is unchanged. After each
`frameStep()` (both branches) one call to `pzopt.FrameCap.onFrame(now)` counts frames
per phase and prints one console line per menu/game transition
("frame cap: menu phase 3.2 s, 144 frames, 45.0 fps (cap 45 fps)"), which is
how a hands-off run verifies the menu cap.

In `InitDisplay` (added 2026-09-19): one call to `pzopt.FrameCap.afterLoadOptions()`
right after `Core.loadOptions()` (both branches), before the sprite renderer is
created. Stock ships an "Uncapped" entry for the frame-rate combo in
`MainOptions.lua` but it is dead twice over: nothing ever sets the
`SystemDisabler` flag that gates the entry, and `Core.loadOptions` resets a saved
`uncappedFPS=true` to a 60 fps lock. `FrameCap` sets that flag so the combo shows
"Uncapped", then re-reads the `frameRate=` / `uncappedFPS=` lines from
options.ini and re-applies them to `PerformanceSettings`, so what the player
picks in Display options survives a restart. It also loads the menu cap from
`Zomboid/pzopt/framecap.ini`. Config key `uncappedFps`: `auto` (default) honours
options.ini, `true` / `false` force the in-game cap off / on for one run
(`harness/run.sh --prop uncappedFps=true`). No-op when the build guard is off.
Nothing else consults the cap for timing (`GameTime` uses measured deltas);
Lua's `getAverageFPS` clamps the displayed number to the in-game lock value
only when capped. The harness metric "frames below 240 fps cap"
(`harness/analyze.py` `FPS_TARGET`) keeps its meaning as the share of frames
slower than 4.17 ms.

The class is otherwise verbatim Vineflower output (revision
`b0bbce05d5`), which recompiles without fixes. Together with the committed
`src/shims/zombie/gameStates/TISLogoState.java` (logo screens skipped), this
is what gets a run from launch to the main menu with no splash screens.

Boot edits (added 2026-09-19, evening, `docs/plan-instant-load.md`), each
behind a `Config` key and `pzopt.Overrides.enabled()`:

- `mainThreadInit`: `FMODManager.instance.init()` is handed to
  `pzopt.BootAsync.startFmod` (a thread) when `fmodAsync` is on; the
  construction of `SoundManager.instance`, `AmbientStreamManager.instance` and
  `BaseSoundBank.instance` (each still choosing the Dummy variant on
  `Core.soundDisabled`), `VoiceManager.instance.loadConfig()` and the four
  `SoundManager.instance.set*Volume` calls are wrapped together in one
  `pzopt.BootAsync.afterFmod(...)` block, which runs at once when the init is
  synchronous and otherwise at the join. Why: the FMOD system create and the
  twelve bank files are 1.4 s of native work that nothing needs before the
  sound scripts, and the VCAs the volume setters read live in the banks. The
  managers must wait too (issue #3, 2026-09-20): `SoundManager` and
  `AmbientStreamManager` build their `FMODGlobalParameter` fields (MusicState,
  MusicIntensity, TimeOfDay, ...) in field initialisers, and each constructor
  resolves its parameter description from the banks loaded so far. Built
  while the banks were still loading they kept a null description, never
  registered with `FMODManager`, and their values never reached FMOD: the
  menu music never stopped and the in-game music and ambience were dead. This
  was true on Linux as well; nobody had listened to a run. `joinFmod` now logs
  whether `MusicState` is registered.
- `initShared`: `pzopt.BootAsync.joinFmod()` right before
  `ScriptManager.instance.Load()` (whose last step,
  `GameSounds.ScriptsLoaded`, is the first FMOD consumer). After
  `SpriteModelManager.getInstance().init()`, when `earlyModels` is on:
  `ModelManager.instance.create()` and `pzopt.BootAsync.startAnimSets()`;
  `enter()` later finds the manager created and skips (its own guard). Why:
  `create` only needs the scripts and the file system, and registering the
  3,990 animation imports 2 s earlier lets the boot pump finish them during
  the Lua load.
- `init` (first statement): `pzopt.BootPump.start()`; `mainThreadStart` calls
  `pzopt.BootPump.stop()` after `enter()`. Why: the file pool is only pumped
  by `GameWindow.logic` (per frame), so during init its threads idled.
- `init`, after `ZomboidFileSystem.instance.loadModPackFiles()`:
  `pzopt.LuaPrecompiler.start()` (mods are known, so the file list is right).
- `enter`: `pzopt.BootAsync.startAnimSets()` after `ModelManager.instance.create()`
  (a no-op when `initShared` already started it).

## zombie.fileSystem.FileSystemImpl (added 2026-09-19, game load)

Vineflower output needs one fix: in `updateAsyncTransactions` the decompiler
typed the reused local as `boolean priority` (`= (boolean)1`, later
`= (boolean)(16 - inProgress.size())`); the first assignment is dropped and the
second becomes `int canAdd`. Edits (`// pzopt:`):

1. **Load marker** in a `static {}` block; a new `private final int maxInFlight`.
2. **Pool size.** The constructor's `numThreads` (stock: 2 on ≤ 4 cores, else 4)
   becomes `pzopt.Config.FILE_THREADS` and `maxInFlight` becomes
   `pzopt.Config.FILE_INFLIGHT` when the overrides are enabled (stock 4 / 16
   otherwise); one log line reports both.
3. **In-flight cap.** The two literal `16`s in `updateAsyncTransactions` (how
   many in-progress items are checked per frame, and how many pending tasks may
   be submitted at once) read `maxInFlight`.

## zombie.tileDepth.TileDepthTextures (added 2026-09-19, game load)

Pristine Vineflower output compiles. Edits:

1. **Load marker** in a `static {}` block.
2. `tilesets` becomes a `ConcurrentHashMap` (same private field, same uses) and
   a `claimedTilesets` concurrent key set is added.
3. **Concurrent load tasks.** `LoadTask.call`, when
   `pzopt.Config.PARALLEL_DEPTH_MAPS` is set and the overrides are enabled,
   skips the stock `synchronized (this.textures)` block: if the tileset is not
   in the map and this task is the first to claim its name it calls
   `createTileset(tilesetName, true)` directly. Everything `createTileset` does
   is per tileset (cached row count, its own `PNGDecoder`, its own tiles, GPU
   uploads queued on the render thread), so the 218 tasks decode concurrently
   instead of one at a time. Stock path otherwise.

## zombie.core.textures.TextureIDAssetManager (added 2026-09-19, game load)

Pristine Vineflower output compiles. Edits: load marker, and `waitFileTask`'s
literal `52428800L` (50 MB of decoded textures waiting for the render thread
before the decoders sleep in 20 ms steps) becomes `WAIT_BYTES` =
`pzopt.Config.TEXTURE_BUFFER_MB` MB when the overrides are enabled.

## zombie.MapCollisionData (added 2026-09-19, game load)

Pristine Vineflower output compiles. Edits, all under
`pzopt.Config.LOADER_CPU_FIXES && pzopt.Overrides.enabled()`:

1. **Load marker** in a `static {}` block.
2. **Lot header once per cell.** A private static
   `pzoptZombieIntensity(lotHeader, chunkX, chunkY, cache, cached)` is a copy
   of `LotHeader.getZombieIntensityForChunk` whose
   `mapFiles.getLotHeader(cellX, cellY)` result is cached per map-files index
   in two arrays allocated per cell in `init`; the 32×32 chunk loop calls it
   instead of the static. Same loop bounds, same `bgHasCell300` test, same
   returned byte; only the per-chunk thread-local/`String.format`/`HashMap`
   lookups go.

## zombie.iso.IsoMetaGrid (added 2026-09-19, game load)

Pristine Vineflower output compiles (`MetaGridLoaderThread` comes along as an
inner class). Edits:

1. **Load marker** via `pzopt.Overrides.onClassLoadedQuiet` in a `static {}`
   block: `IsoMetaGrid` is constructed inside `IsoWorld`'s own static
   initializer, and `DebugLog` reads `IsoWorld.instance` (still null) for
   the frame number of every line, so logging there kills the game at boot
   (`ExceptionInInitializerError` in `IsoWorld.<clinit>`, nothing in
   console.txt). The marker is printed by the next override that loads.
2. **`checkVehiclesZones` dedupe.** Under the same guard the O(n²) scan is
   replaced by one pass with a `HashSet<Long>` keyed on
   `(getX(), getY(), w, h)`: a zone whose key was seen is removed, so the
   first zone of each key survives exactly as in stock (stock removes the
   later index). The stock debug string is only built when
   `DebugType.Vehicle.isEnabled()`; one `[pzopt]` line reports the counts.

## Windows owner-thread input fork (revision `4a0e9546ec`)

- `MainScreenState` establishes the window owner before configuration is read. `RenderThread`
  hands the GL context to `pzopt-render`; `InputThread` keeps the original thread as the Win32/GLFW owner.
  `Display`, LWJGL mouse/keyboard, clipboard, controllers, cursor clipping and ImGui platform operations
  marshal native window work to that owner. The active path bypasses the legacy mouse queue/pollers.
- Mods may synchronously call `Display.processMessages()` from a render-context callback (ZombieBuddy's
  Java-mod approval screen does this). While the input fork is active, the call executes on the GLFW owner
  through `InputThread.invoke()` and returns only after the pump completes. Calling GLFW on the render
  thread instead throws and aborts mod loading; bypassing the pump would leave approval input stale.
- `RawMouse` registers foreground-only mouse usage 1:2 and waits through `MsgWaitForMultipleObjectsEx`.
  It drains `GetRawInputBuffer` before GLFW dispatch. A subclassed `WM_INPUT` handler reads the already-removed
  message with `GetRawInputData`, drains the remaining buffer, and calls `DefWindowProc` for native cleanup.
  Native storage is preallocated; x64 `RAWINPUT` blocks are bounds-checked and walked at 8-byte alignment.
  Per-pump work is bounded so keyboard/window events remain serviceable under continuous mouse input.
  GLFW main/platform-window mouse callbacks are gated off, not replayed alongside raw events.
  Legacy native messages remain enabled for activation/title bars; this does not register background input.
- Raw relative counts drive one shared absolute cursor without Windows pointer acceleration. `mouseSensitivity`
  applies exact signed fractional movement at 1/20-count resolution when acceleration is disabled. Optional
  `mouseAcceleration` uses collection-time 8 ms windows of Euclidean relative raw counts; each completed window
  sets the gain for subsequent batches. After the onset in counts/s, the gain rises by the configured percentage
  per 1,000 counts/s up to the configured percentage cap, then multiplies sensitivity. A >50 ms collector stall,
  lost foreground, absolute position, or setting change resets the estimate and fractional remainder. This is
  not a physical hardware-timestamp speed curve. Absolute primary/virtual-desktop packets and signed wheel
  transitions are unaffected. Button locations use the cursor at their packet, not the batch endpoint.
  Native pointer synchronization happens after the batch; client origins and scale are sampled once, not per packet.
  Focus regain reconciles physical releases missed while inactive without generating a press.
  Raw Input reports physical buttons: packets from a real device (non-null `hDevice`) swap left/right when
  `SM_SWAPBUTTON` is set, as window messages do; device-less packets (touchpads, injected input) arrive logical.
  The focus reconciliation picks `VK_LBUTTON`/`VK_RBUTTON` the same way, since `GetAsyncKeyState` is physical too.
  Raw timestamps are collection times: `RAWMOUSE` does not provide hardware timestamps.
- ImGui's single-window coordinates are client-relative; desktop origins are added only for multi-viewport mode.
  The game viewport moves only by its title bar, not by clicks inside its rendered UI.
  `pzopt.imguiIniFile` lets the isolated harness use its own layout rather than the installed game's file.
- `InputLatch` freezes `SubframeBuffer` before input swaps. `SubframeInput`, game `Mouse`, `UIManager`
  and `UIElement` replay ordered actions at their raw-derived coordinates, then restore final cursor state.
  Focus/overflow resets cancel captures and held gestures without an activating synthetic release.
  Wheel records keep their raw fraction, but UI scroll/zoom, `OnMouseWheel` and `getWheelState` see whole notches:
  1/120 units accumulate across records and frames (reset on direction reversal or a cancelled frame), so a
  high-resolution wheel scrolls at the stock per-notch rate rather than one step per partial packet.
  `CursorLatch` uses the published position rather than querying GLFW from the renderer.
- `IsoPlayer` submits eligible buffered attack presses through native `AttemptAttack`; only an actual
  `isAttackStarted` transition commits `SubframeCombat`'s snapshot. `BallisticsController` and scoped
  `CombatManager` entry points preserve that aim through collision/damage evaluation. No stock attack
  retry at final cursor C follows a handled buffered press.
  Reticle projection is captured independently of weapon-model readiness. At emission, rotate the native muzzle pose
  to the accepted aim, retaining its origin offset, elevation and animation error rather than freezing an idle pose.
- Regenerate `CombatManager` and `BallisticsController` with their nested classes, not just the outer
  `.class`. The shipped `CombatManager` decompile needs three default `yield`s after their conditional
  branches in `processTargetedHit`, plus a distinct `lowerArm` local in the body-part switch.
- `scripts/build.sh` and `scripts/test.sh` use javac response files; Windows paths/classpath separators
  are converted explicitly under Git Bash. `SubframeBufferTest` covers publication, short clicks,
  frozen prefixes, overflow/focus cancellation, held-button suppression and collector hold timestamps.
  `RawMousePacketsTest` covers raw binary layout, signed deltas/wheels, record order/alignment, malformed bounds,
  fractional signed motion and bounded acceleration/collector-window transitions. The real Windows UI/combat
  smoke entry point is `harness/input-thread-win.ps1`; see README.
  It now requires a nonempty native buffered read and verifies relative raw movement through the real game cursor.
  Its text scenario waits for the native text-change callback before a later mouse scenario takes focus.
  Foreground acquisition temporarily attaches the injector to the foreground input queue and always detaches;
  the HWND guard remains, and physical input is never blocked.
  Combat checks submit a second press at C in the same stalled batch: only B may receive one native attack.
  The combat fixture uses free anchor squares and centers characters with `setForceX/Y`: B42's
  `teleportTo(float, float, int)` floors coordinates and otherwise makes melee range depend on random spawn offsets.
  `CombinedDispatchTest` waits for a worker's actual progress before joining instead of assuming a 3 ms timeslice;
  the scheduler implementation is unchanged.

## org.lwjglx.opengl.Display and org.lwjglx.input.Mouse (added 2026-09-19)

These two are The Indie Stone's LWJGL 2 compatibility shim over GLFW 3.4 (the
window and mouse the whole game talks to), not `zombie.*` code. Their overrides were
originally added for native Wayland on a scaled desktop; the Windows input fork above also uses them.
The game selects the Wayland GLFW platform only when the JVM property `zomboid.wayland=1` is set
(otherwise the shim forces X11). On Wayland GLFW hands the window size out in
screen coordinates while the framebuffer is scaled (`GLFW_SCALE_FRAMEBUFFER`
is on by default), so on a 5120x2160 panel at KDE's 125 % scale the stock shim
told the game the display was 4096x1728 and the game drew that viewport into
the bottom-left of a 5120x2160 buffer, leaving black bands on top and right.

Edits in `Display` (all marked `// pzopt:`):

- `getWidth()` / `getHeight()` return the framebuffer size whenever it is known
  (the stock code returned the screen-coordinate size). On X11 and XWayland the
  two are identical, so nothing changes there.
- two new helpers, `getFramebufferScaleX()` / `getFramebufferScaleY()`, give
  framebuffer pixels per screen coordinate (1.0 when GLFW does not scale).
- the cursor-position callback multiplies GLFW's screen coordinates by those
  scales before handing them to `Mouse.addMoveEvent`, so clicks land where the
  cursor is drawn.
- the lock-cursor-to-window clamp in `updateMouseCursor` clamps to the
  screen-coordinate size (GLFW's space), not the framebuffer size.

Edit in `Mouse`: `setCursorPosition` divides the game's framebuffer pixels by
the same scales before calling `glfwSetCursorPos`.

Third edit in `Display` (added 2026-09-19, evening): **MangoHud on native
Wayland.** `swapBuffers()` first calls a private `pzoptHudSwap()`. On the first
call it checks that the overrides are enabled, the GLFW platform is Wayland,
`MANGOHUD=1` is set and `/proc/self/maps` shows `libMangoHud_opengl.so` already
loaded; if so it resolves that library's exported `eglSwapBuffers` with the JDK
foreign-function API (`SymbolLookup.libraryLookup` + `Linker.downcallHandle`,
signature `int (void*, void*)`). Every swap then calls it with
`GLFWNativeEGL.glfwGetEGLDisplay()` / `glfwGetEGLSurface(window)` (cached per
window handle) and returns; MangoHud draws the HUD and forwards to the real
`eglSwapBuffers`. Any failure (no handle, `EGL_FALSE`, exception) logs one
warning and falls back to `glfwSwapBuffers` for the rest of the process. Why:
GLFW resolves EGL entry points with `dlsym` on its private `libEGL` handle, so
the `LD_PRELOAD` hook never sees the swap on Wayland (on X11 the Steam overlay's
own `dlsym` hook chains to MangoHud, which is why it works there);
MangoHud's `dlsym` shim library deadlocks the game's JNI launcher. Verified
with `tools/GlfwSwapProbe.java` (the same LWJGL build, "hud" mode) and the
`wl-gl-mh-*` runs.

Both classes are otherwise verbatim Vineflower output (revision `b0bbce05d5`)
and recompile without fixes; `Display`'s inner classes `$Window` and
`$Callbacks` come along as loose classes. Verified 2026-09-19 with
`harness/run.sh ... --env JAVA_TOOL_OPTIONS=-Dzomboid.wayland=1`: the console
logs "Display mode changed to 5120x2160", the recording is full-screen and the
route numbers match the XWayland runs.

Fourth edit in `Display` (added 2026-09-22, midday): **the window is created at
its final size.** The stock shim creates the GLFW window at the shim's 640x480
placeholder, makes the context current, and `Core.setDisplayModeInternal` then
resizes it (`glfwSetWindowMonitor`) to the options' resolution and fullscreen
state; the window manager applies that resize asynchronously, after the shim has
already re-bound the context. On the Dell (GTX 960M rendering through NVIDIA
PRIME render offload over XWayland, first boot into KWin 6.7.5 / Xwayland
24.1.13) the GL drawable kept the 640x480 geometry the context was first made
current with: the fullscreen 1920x1080 window showed the frame's bottom-left
640x480 and black elsewhere, with the stock shim as well (`dell-lo2-probe-*`
runs, the game's own `--shot-at` capture of the default framebuffer lit only
that corner). The previous day the same box was fine because KWin had grown the
fresh window to 1920x1022 before the switch ("Display mode changed to
1920x1022" in every 09-21 Dell console), i.e. the bug was masked, not absent.

- `create()`: when `Core.width` / `Core.height` are known (or the fullscreen
  option is set) the window is created at `Core.width x Core.height` — the
  desktop size for a borderless window — and, for fullscreen, directly on the
  primary monitor with `GLFW_REFRESH_RATE` = the desktop's rate so no video mode
  switch happens; `gameWindowMode` is set to the matching `DisplayMode` (the
  4-argument constructor for fullscreen, read back from the window in case GLFW
  chose another mode) so `Core.setDisplayModeInternal`'s first check
  (`getWidth() == width && getHeight() == height && isFullscreen() == fullscreen`)
  returns before any switch. The "closest width=" search and the "Display mode
  changed to 640x480" line therefore no longer appear at boot.
- `setDisplayModeAndFullscreenInternal()`: after `glfwSetWindowMonitor` (a
  resolution change from the options screen, the fallback path) a new
  `pzoptAwaitWindowSize()` polls `glfwGetFramebufferSize` (a synchronous
  `XGetWindowAttributes`) until the window reaches the requested size, or its
  size has stopped changing for 200 ms, or 1 s has passed, records the size in
  `displayFramebufferWidth/Height` and `latestWidth/Height`, and the context is
  then unbound and re-bound (`glfwMakeContextCurrent(0)` + the window) so a
  driver that latches the drawable geometry on MakeCurrent sees the final size.

Not gated on `Overrides.enabled()` (like the HiDPI edit: a display-correctness
fix with no game-internal dependency). Verified 2026-09-22 on the Dell with the
`--shot-at` capture (`dell-lo2-fix-probe-*`: the whole 1920x1080 frame) and on
the desktop's windowed 5120x2160 runs.

Follow-up (2026-09-23, user report "opens with 1920x1061 instead of 1920x1080"):
on Windows a new decorated window is clamped to the screen's maximum track size, so
a windowed 1920x1080 window on a 1920x1080 screen is created with a 1920x1061
client area. `gameWindowMode` already said 1920x1080, so `Core.setDisplayModeInternal`
saw a mismatch and called `Display.setDisplayMode(1920x1080)`, but
`setDisplayModeAndFullscreenInternal` found `gameWindowMode` unchanged and did
nothing. The stock resize from 640x480 (a `SetWindowPos`, not clamped) never ran,
and every launch stayed at 1061. `create()` now reads the window size back for
windowed windows too and stores it in `gameWindowMode`, so the stock switch
issues that resize whenever the window came out smaller than asked.

Follow-up (2026-09-23, issue #14 and Workshop reports "borderless windowed: the
menu is a little off centre and clicks land above the cursor", Windows 11, build
4bb5acb): the borderless window was created *decorated* at the desktop size and
only lost its decoration in Core's switch. `glfwSetWindowAttrib(DECORATED, 0)`
keeps the client rect, so the result depended on Windows: clamped to the max
track size (1920x1058 under Wine, the 1061 report) the switch resized it to
0,0; not clamped (a max track size larger than the monitor, e.g. more than one
monitor) the client stays at the caption offset (8,31) with its bottom rows
off screen, and `setDisplayMode` finds the size unchanged and never moves it
(read from the call sequence; Wine always clamps, so only the clamped branch was
replayed).
Replayed with GLFW 3.4 under Wine (a C copy of the call sequence, stock / 4bb5acb
/ new): stock ends as a 1920x1080 `WS_POPUP` at 0,0 after two transitions.
Now `create()` sets `GLFW_DECORATED` 0 and `isBorderlessWindow` for a
borderless window on Win32, so it is created undecorated at the monitor origin,
which is stock's final state; Core's switch finds it done. Linux keeps the
decorated creation: nobody reported the offset there, and with the window mapped
undecorated at the screen size (desktop, KWin) the Workshop uploader's native
confirm dialog was not on top and the upload of 1718aa0 did not go through. `setDisplayModeAndFullscreenInternal`
also re-places a borderless window that is not where `calcWindowPos` puts it
(`pzoptBorderlessMisplaced`, not on Wayland: no window position there), which
covers switching windowed -> borderless at the desktop size in the options
screen (a stock bug as well). The HiDPI cursor scale (framebuffer / window size)
is applied only on Cocoa and Wayland, the platforms where the two sizes differ;
on Win32 and X11 both are the client area, so a ratio other than 1 could only be
two sizes recorded at different moments.

## zombie.scripting.ScriptParser (added 2026-09-19, evening, boot)

`stripComments` first tries `pzopt.ScriptText.stripComments` (one forward pass
with a nesting depth) when `scriptParserFast` is on and falls back to the
stock backward `StringBuilder.replace` loop when that returns null
(unbalanced comment markers). `parseTokens` returns
`pzopt.ScriptText.parseTokens`, the same split with an index instead of a
new substring per block, including the stock quirks (searches start one
character in; a brace-less remainder is a token of its own). Why: the stock
stripper is quadratic in the number of comments and cost 1.5 s on
`tileGeometry.txt` alone. `tests/pzopt/ScriptTextTest` compares both
functions against the jar's class on every `.txt` under `media/` (1,501
files identical). Otherwise verbatim Vineflower output (revision `b0bbce05d5`).

## zombie.fileSystem.FileSystemImpl (second edit, 2026-09-19, evening)

`updateAsyncTransactions` now takes a `ReentrantLock` around its whole body
(the body moved to a private method), and a `pzoptExecutor()` accessor
exposes the pool. The constructor sizes the pool to
`max(fileThreads, bootFileThreads)` when `bootPump` is on. Why: the boot pump
thread (`pzopt.BootPump`) pumps concurrently with the main thread's own calls
during font loading, and `pending`/`inProgress` are plain lists.

## zombie.iso.IsoMetaCell (added 2026-09-19, evening, game load)

`getChunk(int)` resolves the zombie intensity through
`pzopt.LotHeaders.zombieIntensity` with a per-cell memo (`pzoptLotHeaderCache`,
rebuilt if the cell's `info` changes) when `loaderCpuFixes` is on. Same
loop and tests as `LotHeader.getZombieIntensityForChunk`, but
`MapFiles.getLotHeader` (a `String.format` plus two hash lookups) runs once
per map layer per cell instead of once per chunk. Why: 0.8 s of the loader
thread in `IsoMetaGrid.load` → `loadZone` → `addZone` → `getChunk`.

## zombie.buildingRooms.BuildingRoomsEditor (added 2026-09-19, evening, game load)

`checkBuildingAndRoomIDs(IsoMetaCell)` builds an `IdentityHashMap` from
`roomList` (walked backwards so the first occurrence wins, as `indexOf`
does) and uses it for the two `roomList.indexOf(roomDef)` lookups when
`loaderCpuFixes` is on. Same checks and messages. Why: O(rooms²) per cell,
and `Basements.beforeLoadMetaGrid` calls it three times per load (0.85 s).

## zombie.gameStates.GameLoadingState (added 2026-09-19, evening, game load)

`exit`: `screenFader.startFadeToBlack()` is skipped when `noLoadFade` is on,
so the `while (isFading)` loop with its 33 ms sleeps ends at once (the world's
own 2 s fade-in through `UIManager.FadeOut` is untouched). `enter`, first
statements: `pzopt.BootPump.onLoadStart(executor)` shrinks the file pool to
`fileThreads`, `pzopt.BootAsync.joinAnimSets()` waits for the boot preload of
the animation sets, and the Lua precompile statistics are logged. Why: F
dropped from 0.41 to 0.05 s; the other hooks are the load-side ends of the
boot threads.

`render` (2026-09-20): the click-to-start gate for a new game (`newGame &&
time < 33`) is also satisfied when `noIntroWait` is on, so the prompt shows,
and `showedClickToSkip` lets `update` accept the click, as soon as `done &&
playerCreated`. The three intro lines keep their timers and still fade behind
the prompt. Why: stock makes a new save wait the full 33 s intro ("This is how
you died") even when the world finished loading in 4 s.

## se.krka.kahlua.luaj.compiler.LuaCompiler (added 2026-09-19, evening)

`loadis(Reader, String, KahluaTable)` (the overload `LuaManager.RunLuaInternal`
uses) reads the whole chunk into a string when `luaPrecompile` is on, asks
`pzopt.LuaPrecompiler.lookup(name, content)` for a prototype compiled during
boot, and returns `new LuaClosure(prototype, env)` on a hit; on a miss it
compiles the same characters through the stock path (a `StringReader`). The
other overloads are untouched. Why: Kahlua compiles ~1.7 s of Lua serially
across boot and load; the boot pool does it in parallel. The precompiler
stamps `Prototype.file`/`filename` with what the stock compile would have
written (`FuncState.currentFile`/`currentfullFile`).

## zombie.core.skinnedmodel.advancedanimation.AnimationSet (added 2026-09-19, evening)

`GetAnimationSet` and `Reset` keep their signatures and now run their bodies
inside `synchronized (setMap)`. Why: `pzopt.BootAsync.startAnimSets` parses
the player and zombie sets on a boot thread while the game may ask for them
(`IsoPlayer`/`IsoZombie` constructors, main-menu previews), and the map is a
plain `HashMap`.

## zombie.core.skinnedmodel.model.AnimationAssetManager (added 2026-09-19, evening)

`startLoading` creates a `pzopt.CachedAnimationTask` (a `FileTask_LoadAnimation`
subclass) instead of the stock task when `animClipCache` is on, and remembers
it per asset. `loadCallback` has a new first branch for the task's
`CachedClips` result (sets `anim.animationClips`, `onLoadingSucceeded`,
`ModelManager.animationAssetLoaded`, exactly what the `ProcessedAiScene`
branch does after `onLoadedX`), and the `ProcessedAiScene` branch ends with
`pzoptWriteCache(anim)`, which hands the freshly imported clips to
`pzopt.AnimClipCache.writeAsync`. Why: 2,209 jassimp imports (14–17
thread-seconds) per boot for data that is a map of keyframes; the cache
(one file per source, keyed by path, size, mtime and skinning mesh) replaces
them with 2.9 thread-seconds of reading.

## zombie.fileSystem.FileSystemImpl (third edit, 2026-09-19, evening)

`runAsync(FileTask)` wraps the task's `call()` in a timing lambda that reports
to `pzopt.FileTaskStats` (count and summed run time per task class; logged
when the boot pump stops and when the loading screen starts). Why: to size
the asset work per class (animations 14 s, texture pages 12 s of which most
is the upload-budget sleep, meshes 0.3 s).

## zombie.fileSystem.TexturePackDevice (added 2026-09-19, evening, boot)

`initMetaData` opens a `pzopt.PackIndex` for version-0 packs when `packIndex`
is on, and `readPage` looks the page's PNG end offset up in it: on a hit the
stream skips to the end instead of the stock loop that reads the PNG bytes
one at a time through the synchronized `PositionInputStream` looking for the
end marker; on a miss the stock loop runs and the end offset is recorded, and
the index is saved after the last page. Why: 0.5–0.6 s of boot scanning
526 MB of packs byte by byte (`TexturePackPage.readIntByte`). The index is
keyed by the pack file's size and mtime and lives in `~/Zomboid/pzopt/packs/`.
Nothing else changes; `PositionInputStream` already counts skips.

## zombie.scripting.objects.Item (added 2026-09-19, evening, boot)

`DoParam(String, String)` starts with a guard: when `itemParamSwitch` is on it
calls the new private `pzoptDoParam` and returns. That method is the stock
method with its 367-branch `else if (param.trim().equalsIgnoreCase("..."))`
chain rewritten as a `switch` on `param.trim().toLowerCase(Locale.ROOT)`;
every case block is the stock branch body verbatim, the attribute check that
precedes the chain stays first, the chain's tail (the negated
`GameEntityScript` test with the unknown-parameter handling) is the `default`
block, and the one key that appears twice in the chain (`SwingAnim`) keeps
its first branch, as in stock. The rewrite was generated mechanically from
the Vineflower output (the script is not kept; re-run the transformation if
the class changes). Why: 0.9 s of boot in a linear chain of up to 367
case-insensitive comparisons per item parameter. Both callers trim the key
before calling, so the comparison semantics are the same. Verified with the
`dumpItems` field dump (`pzopt.ScriptDump`) of every item script: identical
with the switch on and off.

## zombie.core.PerformanceSettings (added 2026-09-19, frame limiter)

Three public instance methods added, nothing else touched: `getMenuFramerateIndex`,
`setMenuFramerateIndex(int)` and `getMenuFramerateChoices`, each a one-line
forward to `pzopt.FrameCap`. The class is exposed to Lua (`getPerformance()`),
so the added methods are what the "Menu framerate" combo calls; the combo itself
is `src/lua/client/pzopt/pzopt_framecap_options.lua`, installed loose into the
game dir's `media/lua/client/pzopt/` by `scripts/pzopt.sh` (build.sh copies
`src/lua/` under `build/classes/media/lua/`). The Lua wraps `MainOptions:addCombo`
and, right after the stock "Framerate" combo is added, adds a second one whose
entries are "Same as in-game", "Uncapped" and the fps table; index 1 / 2 /
3.. is the convention `FrameCap` stores in `Zomboid/pzopt/framecap.ini`
(`menuFramerateIndex=`), written the moment the option is applied because
`Core.saveOptions` only writes keys it knows.

Extra caps (added 2026-09-19, later): both combos list 500, 430, 400, 330 and
300 fps above the stock 244 (`FrameCap.FPS_TABLE` and the Lua's copy of it must
agree; the framecap.ini index for the stock entries shifted by five). The stock
"Framerate" combo is built with the extended list from the same `addCombo`
wrapper, and the stock `'framerate'` GameOption, which hard-codes the stock
indices in `toUI`/`apply`, is caught on `gameOptions:add` and given
replacements that look the value up in the combo and apply it through
`PerformanceSettings.setFramerateUncapped` / `setFramerate` instead of
`Core.setFramerate` (which only knows indices 1..14). `Core.saveOptions` writes
`frameRate=` from `getLockFPS()` so the value persists, but `Core.loadOptions`
feeds it through an `IntegerConfigOption` clamped to 24..244 that rejects
anything higher and leaves the lock at the option's 60 default; `FrameCap.applySaved`
therefore also re-applies a saved capped value above 244 (it already re-read
the raw lines for the uncapped case). No Core edit.

Correction the same night: that re-read never worked, because the live
`Core.loadOptions` ends with `saveOptions()`, so by the time `afterLoadOptions`
ran the file already held the clamped 24..244 value and `uncappedFPS=false`
(a saved `uncappedFPS=true` becomes `frameRate=60`, which is how forced
`--prop uncappedFps=true` runs left the player's options.ini at 60 fps on
2026-09-19). `Core.saveOptions` also refuses a lock above 244 (the fake
`IntegerConfigOption` rejects it), so the file can never hold the new caps.
Now: `InitDisplay` calls `pzopt.FrameCap.beforeLoadOptions()` right before
`Core.loadOptions()`, which snapshots the raw `frameRate=` / `uncappedFPS=`
lines; `afterLoadOptions` re-applies them and any cap above 244 from
`framecap.ini` (`gameFps=`). The extended combo applies through the new
`PerformanceSettings.setGameFramerate(fps)` (0 = uncapped), which persists
the above-244 value. A forced `uncappedFps=true|false` run writes a
`restore=lock,uncapped,gameFps` line to framecap.ini and the next boot
re-applies that instead of whatever the forced run saved on quit, so harness
runs no longer change the player's frame-rate choice. Verified with two short
boots: forced run logs "game uncapped", next auto boot logs "game 300 fps". Menu means every state that is not
in-game or loading: logo, main menu, options, character creation.

Optimizations tab (added 2026-09-20): six more public instance methods, again
one-line forwards and nothing else touched: `hasPzoptOptions` (true when the
build guard is on; since the master switch of 2026-09-20 evening it returns
`pzopt.Overrides.buildMatches()`, so the tab is offered when the build matches
even if the player switched every optimization off, otherwise nothing could
switch them back on), `isPzoptOptionKnown(key)`, `getPzoptOption(key)` (the value
in force since boot, from `pzopt.Config.value`), `getPzoptOptionDefault(key)`,
`getPzoptOptionSaved(key)` (the player's saved value or ""),
`getPzoptOptionPinnedBy(key)` ("" or `pzopt.properties` / `-Dpzopt.<key>`) and
`setPzoptOption(key, value)` ("" removes the key). They serve
`src/lua/client/pzopt/pzopt_optimizations_options.lua`, which wraps
`MainOptions:addDisplayPanel` and adds an "Optimizations" page right after
Display: every `Config` key that is an optimization (not `instrument`, `dev`,
`devRedrawFrame`, `dumpItems`, `translucentCache`, `uncappedFps`, the last is
the Display combo) as a tick box (booleans) or a combo whose first entry is
"Default (value on this machine)" (integers), grouped as rendering, chunk
streaming, boot and load, with a tooltip per control. The choices go to
`Zomboid/pzopt/options.ini` through `pzopt.UserOptions` the moment Apply is
pressed, and `Config` reads that file at class init below `-Dpzopt.<key>` and the
install dir's `pzopt.properties` (harness runs write that file per run, so a run
never depends on a menu choice; a key set there shows disabled in the tab with
the pinning source in its tooltip). Choosing "Default" removes the key instead of
writing the default's value, because defaults differ per machine (worker
counts). Everything applies on the next launch: the GameOption's `apply`
compares the boot value with the new one through the stock
`GameOption:restartRequired`, so the stock "restart required" dialog appears
exactly when a change matters. Verified by a verify run (`opttab-smoke`,
`--prop bakeBudget=8`): the console logs "options tab: 35 controls, 1 pinned",
no Lua errors; the click path was not exercised hands-off.

Master switch (added 2026-09-20 evening): one more forward, `isPzoptEnabled`
(`pzopt.Overrides.enabled()`, the value since boot). The switch itself is
`Config.enabled` (default true), folded into `Overrides.ENABLED` next to the
build check: `enabled=false` makes `Overrides.enabled()` false, which is the
same stock fallback every override already takes on a build mismatch, so the
other keys are ignored and no override needs a change. `Overrides.buildMatches()`
exposes the build check alone. The tab shows the switch as a tick box above the
sections, with a "since this boot: on / OFF" note in its heading and two
buttons: "Enable all (recommended defaults)" ticks the switch and puts every
other control back to "Default", "Disable all (stock game)" unticks it and
leaves the other controls alone. Both only change the controls and mark the
options changed; Apply / Accept saves them through the same `apply` handlers,
so the restart dialog and `options.ini` behave as for any single change. A
pinned `enabled` (pzopt.properties / `-Dpzopt.enabled`, e.g. a harness
`--prop enabled=false` stock run) disables both buttons.

Preview clips (added 2026-09-21 night): five more forwards, one line each,
for the tab's preview panel: `getPzoptGifFrame(path, nowMs)` (the frame of an
animated GIF under the game dir, as a `Texture`, or null while it decodes / if
the file is missing), `getPzoptGifState(path)` ("loading" / "ready" / "missing" /
"error"), `getPzoptGifWidth` / `getPzoptGifHeight(path)` and `releasePzoptGifs()`.
They call `pzopt.GifTextures`, which decodes the GIF with ImageIO on a daemon
thread (compositing the frame deltas per their disposal rule, thinning to 96
frames and scaling to 512 px wide), and makes one game `Texture` per frame on the
game thread the way a Steam avatar is made (`ImageData` from RGBA rows ->
`TextureID` -> `Texture`, uncompressed, at most four per call). The tab (Lua)
draws, right of the control list, the stock and the optimized clip of the same
route side by side for the setting under the mouse, the setting's description,
its value since boot and at the next launch, and the effect bars (game thread,
render thread, other cores, GPU, VRAM, RAM, disk, load time, chunk arrival, from
a table in the Lua); the clips are `media/ui/pzopt/compare/<clip>-{stock,opt}.gif`
(`harness/menu-gifs.py`). The two clips in use are the only textures held (96 x
512x256 RGBA each at most); `MainOptions:setVisible(false)` releases them.
Since 2026-09-26 the clips are off by default (`previewClips`, Config key read by
the Lua only): a "Before / after clips" tick box under "Sort by" in every tab's
header saves it at once through `setPzoptOption` (no Apply), switches all three
tabs, and releases the decoded clips when unticked; with it off the preview drops
the clip slot and never asks for a GIF.
Verified in game on 2026-09-21 (queue job `menu-check4`: hover, scroll, wheel
over the preview, clips playing, panel sized to its content).

Main-menu update item (added 2026-09-22): eleven more one-line forwards to
`pzopt.Updater` for `media/lua/client/pzopt/pzopt_mainscreen_update.lua`:
`pzoptUpdateCheck()` (starts the release check once per boot; a no-op with
`updateCheck=false` or in a harness run), `getPzoptUpdateState()` ("idle",
"checking", "up-to-date", "available", "downloading", "installing", "installed",
"error"), `getPzoptUpdateTag` / `Notes` / `Published` / `PageUrl` /
`InstalledCommit` / `Message` / `Progress`, `canPzoptUpdateInstall()` (a
`pzopt-installed.txt` or `pzopt-files.txt` exists to replace) and
`pzoptUpdateInstall()`. The check lists the GitHub releases on a daemon thread
and picks the newest one (publish date) that carries
`pzopt-<revision>-classes.zip` for the running game; it is an update when the
tag's commit (`b<version>-<yyyymmdd>-<hhmm>-<commit>`, before 2026-09-29 `win-<revision>-<commit>`) differs from build-info's `commit=` and
its publish date is after build-info's `built=` (both stamped by `build.sh` since
this change, so a from-source build newer than the last release stays quiet).
The install downloads the zip next to the game folder, checks the zip's
revision, unpacks it into `pzopt-update.tmp/`, moves every file over the
installed one, deletes what the previous manifest listed and the zip no longer
has, and rewrites `pzopt-installed.txt` in the installers' format. The Lua adds
an `ISLabel` styled like the stock items (`UIFont.Large`, the hover fade of
`MainScreen.prerenderBottomPanelLabel`, the menu sounds) between Credits and
Exit (Exit and the panel move down one row); it is always there like the stock
items, greyed out and inert (no fade, no click) while the check runs, when the
build is current or when the check failed, enabled once a newer build is
offered; its text follows the state ("UPDATING... 43 %", "RESTART TO FINISH THE
UPDATE"), and
the dialog (`PzoptUpdateDialog`) shows the installed and offered builds, the
release notes, a progress bar and Update now / Later, then Quit game / Later:
classes the JVM already loaded stay the old ones until a restart. Never in the
pause menu; no joypad entry (the stock list is hard-coded).

Steam Workshop source (issue #16, 2026-09-26, `updateFromWorkshop`, default on):
one more forward, `getPzoptUpdateSource()` ("workshop", "github" or ""). Before
the GitHub request the check looks for the Workshop item in the library that
holds the game (`<steamapps>/workshop/content/108600/3805285544/mods/
PZ_Optimization/<version>/pzopt-classes/`, the unpacked release zip that
`workshop.sh --tag` stages); a copy counts when its build-info names this
revision and a commit and every file of its `pzopt-files.txt` exists (Steam
mid-update skips it). It is offered at once when newer than this build by the
same rule, with its `built=` standing in for the publish date (no downgrade
after a GitHub install). The GitHub answer then replaces the offer only with a
later release; when GitHub is unreachable the item goes grey ("up-to-date")
instead of "error". Installing the copy stages its files and runs the zip
path's swap (`swapFolder` → `swapStaged`); Steam's folder is not touched. The
dialog names the source and the Workshop change-notes page.

Near-instant updater (2026-09-26, `docs/findings-updater-2026-09-26.md`): two more forwards,
`pzoptRestartGame()` (`pzopt.Restart.relaunch()`: a helper process starts the game again once
this one has quit; the dialog's "Quit game" is now "Restart game", which calls it and then the
stock `quitToDesktop`) and `getPzoptUpdateDrive()` (the `devUpdateDrive` rig's mode for the
Lua: "", "drive", "restarted:<ms>"). The install now goes through `pzopt.UpdateDelta`: the
changed zip entries only (range requests, prefetched in the background once offered), written
beside their targets and renamed over them; unchanged files are not rewritten.

Settings export / import (2026-10-01): three forwards to `pzopt.UserOptions` for the "Export settings" /
"Import settings..." buttons on the Optimizations, Enhancements and Profiler tabs: `getPzoptSettingsExportText(body)`
(the export's comment header, date and build, above the Lua's `key=value` lines), `pzoptSettingsExportWrite(text)`
(writes `settings-export.ini` beside options.ini; its path, or "" when the write failed) and
`getPzoptSettingsExportFile()` (that file's text for the import dialog, "" when absent). The clipboard is the stock
`Clipboard` the Lua already reaches; the controls are set by the Lua like the profile buttons, saved on Apply.

In-game uninstall (2026-09-27): three forwards to `pzopt.Uninstall` for the Optimizations tab's
"Uninstall PZ Optimization..." button: `getPzoptUninstallUnavailable()` ("" or why the button is
off: a harness run, or no `pzopt-installed.txt` / `pzopt-files.txt` in the game folder),
`pzoptUninstall()` (undoes AotCache's and GcChoice's launcher edits, writes the file list, starts
a helper that deletes the files once this process has ended; the Lua then calls the stock
`quitToDesktop`) and `getPzoptUninstallMessage()`. The button is off in a world (the quit would
skip the save).

Performance overlay item (added 2026-09-23): three forwards to `pzopt.Overlay`
for `media/lua/client/pzopt/pzopt_mainscreen_overlay.lua`: `togglePzoptOverlay()`
(`Overlay.toggle()`, the same path as the key binding, which now calls it too:
show / hide, or the "sampling is off" notice while `overlaySampling` is off),
`isPzoptOverlayVisible()` and `isPzoptOverlaySampling()`. The Lua adds an
`ISLabel` styled like the stock items right below Options in both the main menu
and the pause menu (every item under Options moves down one row; the
multiplayer pause menu's per-frame re-layout in `MainScreen:render` is redone
after the stock one), "SHOW / HIDE PERFORMANCE OVERLAY" following the overlay's
state. Controller: the label gets a row after Options in `joypadButtonsY`
(after the stock `onGainJoypadFocus` rebuild and every frame while the menu has
the focus) and A on it toggles. Not added with `enabled=false`.

## zombie.core.skinnedmodel.model.Model (added 2026-09-19, night, game load; GitHub issue #1)

`CreateShader(name)`: the stock method always posts a lambda to the render
thread and waits for it, even when `ShaderManager` already holds the shader.
Every `Model` constructor calls it, and the render thread only drains that
queue once per render step, so each model built off the render thread costs
one loading-screen frame. On the desktop that is ~1 ms; on the laptop
the laptop of issue #1 the loading-screen step is ~220 ms and the 73 animal
models `AnimalDefinitions.loadAnimalDefinitions` builds (all `animalEffect`)
were 16.5 s of the 31 s load. Now, when `shaderCache` is on, the method first
asks `pzopt.ModelShaders` for a shader an earlier model already created for
the same name and static flag and takes it without the round trip; only the
first model per shader still posts to the render thread, and that call's wall
time is recorded. `ModelShaders.summary()` ("model shaders: N cached, M
render-thread round trips (x s waited), K served from the cache") is logged
when the boot pump stops, when the load starts and at the harness's "world
ready", so the trace shows the stall on any machine. The cache is safe
because `ShaderManager` never removes shaders and a shader reloaded by the
debug file watcher recompiles in place. Config key `shaderCache` (default
true). The class-load marker goes in a static initializer like the others.

## zombie.core.textures.ImageData (added 2026-09-20, texture load; GitHub issue #2)

A file-pool worker (`pool-1-thread-18`, `FileTask_LoadPackImage.call` →
`initMipMaps` → `generateMipMaps`) crashed the JVM on the laptop
the issue #2 laptop with a SIGSEGV inside the C2-compiled
`scaleMipLevelMaxAlpha`. Nothing at the Java level can produce it: the
`ImageData` is local to the task, its `MipMapLevel` buffers are freshly
malloc'd and only ever read through bounds-checked `ByteBuffer.get(int)` /
`put(int, byte)`, and no other thread sees the object until `call` returns.
Reading the `hs_err` (copied from the laptop over SSH) settles it: the
faulting instruction is a plain reload of a spill slot from the thread's own
stack, `mov r14d, [rsp+0x88]`, with no address-size prefix; the reported
fault address is exactly that stack address truncated to 32 bits, and the
stack page was mapped (the crash log dumps it a few lines later). Eight
seconds after the JVM died, `systemd-coredump` (compressing the core, in
libzstd) segfaulted on the same laptop with a 32-bit-truncated address too,
and fifteen minutes earlier the same laptop's previous game process had
aborted inside the C2 register allocator (`PhaseChaitin::Simplify`, a
`SIGABRT` coredump with an empty `hs_err_pid3802.log`), after which the
machine was rebooted. Three faults in three unrelated code bases within
fifteen minutes, two with truncated addresses, is the machine (CPU, memory
or its `7.2.4-1-cachyos-custom` clang-built kernel, while the packaged
7.2.6 kernels are installed but not booted), not the game or the overrides.
A 5-minute, 18-thread hammer of the stock mipmap loops on the laptop's own
Zulu 25 JRE (`tools`-style reflection driver, see the issue) did not
reproduce it.

The override still exists because it was written before the crash log was
readable and is harmless: when `mipmapArrays` is on and the
`worldMipmapColors` debug option is off, `scaleMipLevelMaxAlpha`,
`scaleMipLevelAverage` and `performPreMultipliedAlpha(MipMapLevel)` return
early into `pzopt.MipMaps`, which reads each parent row pair with one bulk
`get`, builds the sub row in a thread-local `byte[]` with plain array
indexing, and writes it with one bulk `put`; the compiled loop has no
per-byte direct-buffer access left. The sub buffer is still rewound first,
as in stock. Output is byte-identical (`tests/pzopt/MipMapsTest` drives the
jar's own private methods by reflection over 40 size/alpha variants) and
the speed is the same (2048² → level 1: stock 14.3 ms, ours 13.5 ms,
warmed). Config key `mipmapArrays` (default true); off, or with the debug
colours on, the stock loops run untouched. Class-load marker in a static
initializer. It is not a fix for the laptop: if the crash recurs there, boot
the packaged kernel and run a memory test before touching the code.

## zombie.iso.weather.fx.WeatherFxMask (added 2026-09-20, game thread)

Regenerated with Vineflower; one decompiler fix (the "Calc Bounds" profile
area local shared its name with the `bRender` boolean captured by the two
rasterize lambdas; renamed, marked `// pzopt: decompiler fix`).

1. **Load marker** in a `static {}` block.
2. **Idle skip** (`pzopt.Config.WEATHER_MASK_IDLE_SKIP`, default true). At the
   top of the masked branch of `renderFxMask`, a private `pzoptMaskIdle`
   returns when nothing of the pass could reach the screen: the player is
   exterior (no interior tint layer), no cloud layer, no fog layer at fog
   quality 2, no precipitation layer at the precipitation option 1, and no
   debug mask view. Then neither `scanForTiles` nor `drawFxMask` runs; the
   mask state is left as it was, so the next active frame continues it.
3. **Scan gate.** `scanForTiles` returns at once when the scan could not add a
   mask: the player mask needs no update or has nothing to draw, or the
   player is in no building and no fog-mask region (`isInPlayerBuilding` can
   then never be true). Stock rasterizes the whole view every frame in that
   state (tens of thousands of squares at max zoom, `isInteriorLocation` per
   exterior tile: 1.5 to 3.7 % of the game thread, 70 % of the pass in slow
   frames).
4. **Building-only scan.** When the player stands in a building and not in
   a fog-mask region, a private `pzoptScanBuildingOnly` visits the squares of
   the building's `BuildingDef` bounds plus one tile of margin (a square
   outside the building only consults its N, W and NW neighbours) that pass
   the class's own `isOnScreen`, calling `addMaskLocation` for each, instead
   of rasterizing the view; `scanForTiles` then returns. The mask contents
   are the same squares stock would have found. Same Config key. The whole
   pass went from 2.6 to 0.5 % of the game thread outdoors and from 6.6 to
   2.4 % on the spinning route through Rosewood.

## zombie.iso.objects.IsoLightSwitch (added 2026-09-20, game thread)

1. **Load marker** in a `static {}` block.
2. **Electricity check cache** (`pzopt.Config.LIGHT_SWITCH_CHECK_FRAMES`,
   default 15; 0 = stock). `hasElectricityAround()` keeps its last answer and
   the frame it was computed (`IsoWorld.getFrameNo`) in two new fields and
   returns it while fewer than that many frames have passed; the stock body
   moved to a private `pzoptHasElectricityAroundNow`. `LightingJNI.checkLights`
   asks every light source's first switch for power every frame (grid power,
   generators, the 3x3x2 neighbourhood): 2.3 % of the game thread on the
   Rosewood route, 0.3 % after. A power change shows on a lamp up to 15
   frames late.

## se.krka.kahlua.j2se.KahluaTableImpl (added 2026-09-20, Lua VM)

1. **Load marker** in a `static {}` block.
2. **Single lookup in `rawget(Object)`.** Stock does `containsKey` and then
   `get` on the delegate map (two hash lookups per table read; the Lua UI and
   `OnTick` handlers do millions per second). Now one `get`; a null result
   means the key is absent, which is exactly the stock `containsKey` test
   because `rawset` removes the key on a nil value and never stores null.
   The metatable fallback is unchanged. The reload-replace and data-breakpoint
   code before it is untouched.

## zombie.core.opengl.RenderThread (added 2026-09-20, performance overlay)

Three one-line hooks in `lockStepRenderStep`, all into `pzopt.Overlay`, plus the load
marker `static {}` block. Inside the `spriteRendererPostRender` probe,
`pzopt.Overlay.gpuBegin()` precedes `SpriteRenderer.instance.postRender()` and
`pzopt.Overlay.gpuEnd()` follows it: a `GL_TIME_ELAPSED` query around the frame's
draw-command replay, which is the frame's GPU work (the swap is outside it). After the
`displayUpdate` probe closes, before the stock `FPSGraph.addRender` call,
`pzopt.Overlay.onSwap()` records the presented-frame time, the same instant MangoHud
logs from. With the build guard off every hook is a static boolean test.

Harness virtual pad (2026-09-24): in `renderLoop` the stock `GameWindow.GameInput.poll()` is
`pzopt.VirtualPad.poll()`, which is that same call unless the harness flag file names a pad script (`--flag
pad=<script>`). Then a fake lwjglx `Controller` (no GLFW device) sits in `Controllers` slot 15 and its buttons / hat
are written into the polling `GamepadState` under the `ControllerStateCache` lock right after the stock poll, so the
game's own input path (`Input`, `JoypadManager`, the Lua `JoypadControllerData`, the menus) runs as for a real pad.
Menu profiling on machines without uinput (the Mac); players never set the flag.

## org.lwjglx.opengl.Display (second edit, 2026-09-20, performance overlay)

First statement of `imguiEndFrame()`: `pzopt.Overlay.draw();`. `Core.EndFrameUI` calls
this method on the game thread after the UI FBO has been composited onto the screen and
immediately before `IndieGL.glDoEndFrame()` / `RenderThread.Ready()` hand the frame to the
render thread, so sprites queued here are the last thing on top of every state (menus,
loading screen, world). A draw at the end of `GameWindow.renderInternal` was tried first
and never showed: the hand-off has already happened by then. The overlay draws through
`TextManager` and `SpriteRenderer`, so it needs no Lua and no external HUD. See
`pzopt.Overlay` for what it shows and the `overlay*` Config keys. The other two callers of
`imguiEndFrame` (exception paths in `GameWindow.logic`, ImGui only) just draw one more
overlay frame.

## zombie.iso.fboRenderChunk.FBORenderCutaways (added 2026-09-20, game thread, 400 fps pass)

Loose copy with the load marker `static {}` block and one edit in
`doCutawayVisitSquares`, behind `pzopt.Config.CUTAWAY_INVALIDATE_CHANGED` (and only when
`PerformanceSettings.fboRenderChunk` is on). Stock clears the cutaway flag of every square in
last visit's result sets, re-adds the flags from this visit's sets, and invalidates (flag
2048, "cutaway changed") the chunk level of every square touched in either step, so every chunk
holding a cut-away wall re-bakes its texture on every visit; while moving through a town the
visit runs almost every frame. With `fboRenderChunk` the texture only reads the square's
*target* cutaway flag (`getPlayerCutawayFlag` returns it directly, there is no fade), so the
texture is unchanged when the flags end the visit as they began. The edit remembers each
touched square's target flag before the clear (an identity map, reused), lets the stock flag
updates run unchanged, then invalidates only the chunks where a square's flag differs (with the
stock off-screen seen-rooms mask reset for those). The edge-wall neighbour invalidation loop
after it iterates the same reduced set, which is exact: it only reads flags of that chunk.
Counters `pzoptCutawayVisits`, `pzoptCutawayChunksInvalidated`, `pzoptCutawayChangedSquares`.

## zombie.audio.parameters.ParameterZone (added 2026-09-20, game thread, 400 fps pass)

Loose copy with the load marker and a memo in `calculateCurrentValue`, behind
`pzopt.Config.SOUND_ZONE_CACHE`. Seven of these parameters (forest, deep forest, farm, nav,
town, trailer park, vegetation) each scan the meta grid's zones in an 80x80 window around the
listener every frame (`IsoMetaChunk.getZonesIntersecting`, with an `ArrayList.contains` per
zone) and take distances from `fastfloor(x)`, `fastfloor(y)`. The value is therefore a function
of the listener's integer square; it is reused while that square is unchanged, for at most 30
frames. The stock body is unchanged, moved into `pzoptCalculate`.

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-20 evening, light info once per frame)

`cacheLightInfo` (one JNI call per square) is now routed through `pzoptCacheLightInfo` at the
three per-frame sites: `prepareChunkForUpdating` (texture-dirty chunk levels),
`pzoptCacheChunkLevelLightInfo` and `updateChunkLevelLighting` (lighting-dirty levels). A chunk
level that is both texture-dirty and lighting-dirty in one frame asked twice per square. The
helper keeps a frame-number stamp per player, level and square on the `IsoChunk`
(`pzoptLightInfoFrame`, lazily allocated) and skips a square already refreshed this frame,
behind `pzopt.Config.LIGHT_INFO_ONCE_PER_FRAME`. Lighting results only change in
`LightingThread.update`, which runs before the render. Counter `pzoptLightInfoSkipped`.

## zombie.iso.IsoChunk (edit of 2026-09-20 evening)

One field: `pzoptLightInfoFrame`, the stamp rows used by the FBORenderCell edit above.

## zombie.iso.ChunkSaveWorker (second edit, 2026-09-20 evening, staged hot save)

(A ten-stage variant splitting `map_meta.bin` in two was tried and dropped the same evening.)

`HotsaveAncilliarySystems` behind `pzopt.Config.HOTSAVE_STAGED`: instead of serialising the
meta grid (map_meta, zones, animal zones, meta cells), the animal population, game time, the
world map, visited map and the entity manager in one `invokeOnMainThread` block (one 55 ms
frame per hot save on the bench route), the first call starts a staged save and every following
`Update` call from the streamer runs one part on the game thread (nine stages), accumulating
into the same `SaveBufferMap`; after the last stage the players are saved and the buffers are
written to disk exactly as stock does. The stock path is kept for the flag off.

## zombie.iso.weather.fx.WeatherFxMask (second edit, 2026-09-20 evening, FX buffer scale)

Behind `pzopt.Config.WEATHER_FX_SCALE_PCT` (100 = stock and the default: 50 % measured as a wash, u400-it3-fx50-2; single player only):
`checkFbos` creates the mask and particle FBO textures at that fraction of the screen size;
`drawFxMask` and `drawFxLayered` queue a `glViewport` to the texture size right after their
`glDoStartFrameFx` (the projection stays in world units, `glDoEndFrameFx` pops the viewport
attribute); the mask composite (`rendershader2` texel rectangle) and the final particle composite
(texture coordinates) sample the scaled texels. Clouds, fog, rain and the interior mask are soft
content; at 5120x2160 the full-size pass was ~11 % of the uncapped frame (CPU and GPU). Also a
measurement-only `Config.DEV_WEATHER_FX_OFF` that returns from `renderFxMask` at once.

## zombie.iso.IsoChunkMap (added 2026-09-20 evening, chunk hand-off budget)

Loose copy with the load marker and one edit in `updateInternal`, behind
`pzopt.Config.CHUNK_HANDOFF_DIVISOR` (8; 0 = stock): the number of freshly loaded chunks handed
to the game thread this frame (`doLoadGridsquare`: loot roll, erosion, recalc, pathfind, 1 to
5 ms each) is capped at 1 + queue / divisor on top of the stock 1 + 3 * queue / gridWidth, so a
chunk row arriving at once is spread over a few frames instead of one 10 to 25 ms frame.

Edit of 2026-09-21 (mid-scroll guard): `getGridSquareDirect` returns null when the chunk found at
the indexed slot is not the chunk that slot should hold (`c.wx != getWorldXMin() + chunkX` or the
same for y). `LoadLeft/Right/Up/Down` move `worldX`/`worldY` (the origin every caller subtracts)
before `SwapChunkBuffers` publishes the shifted grid, so a lookup from the streamer or a recalc
worker inside that window indexes the old grid with the new origin and gets a square one chunk
off. Stock `IsoGridSquare.isWallTo(other, depth)` then asks for the orthogonal intermediate,
receives that same off-by-a-chunk (still diagonal) square and recurses on it until the stack
overflows (the `depth > 100` branch is an empty debug hook). Seen in a Windows user's console,
chunk 1071,1434: the worker's pass and the streamer retry both overflowed. A square that is not
where the index says reads as not loaded, exactly what the map edge returns; two int compares on
fields already in cache.

Edit of 2026-09-22 (`chunkGridWidth`, a user's suggestion): `CalcChunkWidth` keeps the stock choice
(the debug 5x5..13x13 options first, else 13 * 1.5 * min(1, screen / 1080p), odd, at most 19) and
then, when `pzopt.Config.CHUNK_GRID_WIDTH` is above 0 and the overrides are enabled, replaces the
width with that value made odd and clamped to 5..15 (the maintainer's cap) before `chunkWidthInTiles` is derived. Every
consumer reads the two statics (the chunk map arrays, `IsoCell` square grid, lighting / pathfind /
Bullet natives via their init and per-frame calls, the multiplayer connect range, which is a byte),
and `CalcChunkWidth` runs in `GameLoadingState` before the cell is built, so the width is fixed for
the session. Default 0 = stock. Options tab combo "Render distance (chunk grid width)": 7..15, so
at 1080p and above it only ever shrinks the stock 19 (at 720p, 15 is above the stock 13).

Second edit of 2026-09-22 (`chunkGridWidth=auto`, cap raised to 41): the stock width stops at 19 whatever the
screen, and the grid's diamond covers a W x H screen only while its width reaches W + 2H pixels at 128 / zoom
pixels per tile, so at 5120x2160 the widest zoom (2.5) needs 23 chunks and the stock 19 leaves dark screen
corners from zoom 2.25 outward (the maintainer's report). The width is now `pzopt.ChunkGrid.width(stock, auto,
fixed, screen W, screen H, Core.getMaxZoom())`: "auto" = the smallest odd width covering the screen at the widest
zoom plus one chunk (the player sits anywhere in the centre chunk), never below the stock width (25 at 5120x2160,
21 at 3840x2160, the stock 19 at 1080p); a number = that width made odd; both clamped to 5..41 (an 8K screen at
zoom 2.5 needs 41). One `[pzopt] chunk grid:` console line gives the width and its inputs. Tests:
`tests/pzopt/ChunkGridTest`. The key is read as a string now (`Config.CHUNK_GRID_SETTING`).

Edit of 2026-09-24 (`chunkGridFollowView`, default on; the maintainer's report "on a higher z-level it reverts to the
vanilla distance"): the camera centres on the player's screen position with the height in it (`PlayerCamera`, a level
is 96 px at 1x tiles, a tile step 16 px), so on level z the ground under the screen centre lies 3z tiles north and 3z
tiles west of the player, while `ProcessChunkPos` centres the grid on the player's x / y. A grid sized for level 0
(`auto`: about one chunk to spare) then shows unloaded ground in both top screen corners from about level 2 at the
widest zoom; the south-east quarter of the grid is off the bottom of the screen. `CalcChunkWidth` now remembers the
stock width (`pzopt.ChunkGrid.stock`, only when the setting replaced it), and `ProcessChunkPos` subtracts
`pzopt.ChunkGrid.heightShiftTiles(z, width, stock)` from the target x and y, after the stock driving look-ahead (the
same kind of centre offset): 3 tiles per level, rounded, none at or below level 0, capped at the extra half-width over
stock (3 chunks for 25 over 19, i.e. levels up to 8) so the player is never nearer a grid edge than in a stock grid.
Grids no wider than stock, `chunkGridWidth=0`, `enabled=false` and multiplayer (the server loads a player-centred area:
`ServerMap`, `LoadedAreas` use `onlineChunkGridWidth` around the player) keep the stock centring. Climbing stairs
moves the grid one row / column when the shifted point crosses a chunk border, like walking.

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-20 evening, occlusion grid on lighting-only frames)

`renderTilesInternal` decides whether to rebuild the occluded-squares grid through
`pzoptHasDirtyChunkTexturesForOcclusion` instead of `hasAnyDirtyChunkTextures`: behind
`pzopt.Config.OCCLUSION_SKIP_LIGHTING_ONLY`, a frame whose only dirty on-screen chunk levels are
dirty for lighting (flag 32) keeps the previous grid and does not set `occlusionChanged` (so
the per-level rendered-squares counts are not recomputed either), exactly as a frame with no
dirty level behaves in stock. Lighting drift changes no square, vision matrix or cutaway flag,
which are the only inputs of the occluder test. Counter `pzoptOcclusionRebuildsSkipped`.

## GPU section timing (2026-09-20 evening, `pzopt.GpuSections`, measurement only)

Behind `pzopt.Config.GPU_SECTIONS` (off by default): `begin`/`end` calls on the game thread
queue a generic draw command whose render step writes a `GL_TIMESTAMP` query, so the GPU time of
a named span of the sprite stream is known a few frames later; the periodic FBORenderCell log
line prints the average GPU microseconds per frame per section. Sites: `FBORenderCell`
(`chunks` = the per-chunk draw/bake loop, `bake` = one chunk-level texture bake,
`translucentFloor`, `translucent`, `items`, `moving`, `water`) and `WeatherFxMask.renderFxMask`
(`fx`, the whole weather pass; the stock body moved to `pzoptRenderFxMask`). With the flag off
every site is a static boolean test.

## zombie.iso.fboRenderChunk.FBORenderCutaways (second edit, 2026-09-20 evening, visit prefilter)

Behind `pzopt.Config.CUTAWAY_VISIT_PREFILTER`. `cutawayVisit` walks every cutaway wall of the
on-screen chunks at the player's level and, per wall square, does a grid-square lookup, two hash
set operations, a level-data lookup and `IsCutawaySquare`; on the spinning route that is ~10 %
of the frames above 3 ms. `IsCutawaySquare` can only be true when the wall occludes one of the
player's cutaway rooms, or (with no cutaway rooms) is part of a building in
`buildingsToCollapse`, or the player is peeking through a window (a per-square garage-door
test, kept as is). Those are wall-level facts, so `pzoptWallCanCut` tests them once per wall and
walls that fail are skipped before their squares are touched. The visited sets are then no
longer complete; their only reader is the point-of-interest loop in `doCutawayVisitSquares`,
where stock's first visit marks every wall square visited so later points of interest never
add a square: with the prefilter the loop simply stops after the first visit, which is the same
result. The wall's own `ChunkLevelData` is used when the square lies in the wall's chunk instead
of a hash lookup per square. Counters `pzoptWallsVisited`, `pzoptWallsSkipped`.

## zombie.iso.fboRenderChunk.FBORenderCutaways (fourth edit, 2026-09-21, carport roof debounce)

Behind `pzopt.Config.ROOF_HIDE_DEBOUNCE_FRAMES` (`roofHideDebounceFrames`, default 8; 0 = stock;
Options > Optimizations "Carport roof hide/show settle time"). In `checkOrphanStructures`, per
chunk level with orphan structures (a carport / pergola roof: a building whose only room is
`emptyoutside`), the answer of `OrphanStructures.shouldCutaway()` is compared with the current
`PlayerInRange` state; when they differ, a per-player counter on the `OrphanStructures` object
(`pzoptPendingFrames[4]`, reset by `calculate` and `resetForStore`) counts consecutive frames of
the new answer and the level is skipped (`continue`, current state kept, nothing invalidated)
until the counter reaches the setting. The first answer after `Unset` is applied at once, as in
stock. Stock applies every change immediately and invalidates the level (2048) each time, so a
decision that changes every frame (the maintainer's 2026-09-21 video: player on the SE edge of a
detached carport, zombies around, the roof toggling every frame until the game was paused)
re-bakes four chunk levels per frame and the roof flickers. Counter `roof flips held` on the
instrument line.

## zombie.iso.fboRenderChunk.FBORenderCutaways (third edit, 2026-09-21, dev log of roof hide/show decisions)

Diagnostic only, behind `pzopt.Config.DEV_CUTAWAY_LOG` (`devCutawayLog`, default false): a
private static `pzoptDevCutawayLog(String)` prints at most 400 `cutaway dev f<frame>: ...` lines.
It is called (a) at the end of `CalculateBuildingsToCollapse` when the buildings-to-collapse list
changed (old and new size, the new defs' bounds, `cell.occludedByOrphanStructureFlag`, the number
of points of interest), (b) in `checkOrphanStructures` on each `PlayerInRange` flip (`orphan
HIDE` / `orphan SHOW`, chunk and level), and (c) in `shouldRenderBuildingSquare` when the
adjacent-chunk counter forces an `OrphanStructures.calculate`. No decision is changed. Added for
the carport-roof per-frame flicker report (user video, 2026-09-21).

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-20 evening, light info chunk gate)

`prepareChunkForUpdating` refreshes the light info of every square of every level of a chunk
about to be re-baked; each refresh is a JNI "is this square dirty" question (and the lighting
data fetch when it is). Behind `pzopt.Config.LIGHT_INFO_CHUNK_GATE` the level first asks
`LightingJNI.getChunkDirty` (the same chunk-level question stock's own lighting refresh,
`updateChunkLevelLighting`, uses as its gate) and skips the 64 square refreshes when the level
has no dirty square; the `squareFlags` bookkeeping of the loop is unchanged. Counter
`pzoptLightInfoLevelsGated`.

**Black chunk squares fixed (2026-09-20 afternoon).** With the gate, a square whose light info had
never been cached (a freshly streamed chunk whose lighting pass had already run and consumed the
JNI dirty bit before the level's first bake) kept `lightInfo == null`; the loop's
`getLightInfo(playerIndex) != null` test then left it out of `squareFlags` and the whole 8x8
level baked black. Stock never hits it because it refreshes every square unconditionally. The
gated branch now refreshes a square whose light info is null regardless of the chunk-level answer
(`pzoptRefresh || sq.getLightInfo(playerIndex) == null`). This was the "black squares on the left
of the screen" that had been attributed to `persistentVbo`: the persistent mapping only changed the
render/lighting thread timing enough to expose it (bisect runs `bs-*`, screenshot rig
`run.sh --shot-at`, metric `harness/blacktiles.py`; 0 black 32 px tiles after the fix in
`bs-gatefix-*`, 225-303 before).

**Staged hot save off by default (2026-09-20 night):** `hotsaveStaged` defaults to false. The
parts are serialised a few frames apart while chunks keep loading, so `map_meta.bin` and the
`metacell_*.bin` files could disagree on room metaIDs; the "invalid room metaID" load errors seen
that night turned out to be pre-existing in the bench save (present in every run), but the
consistency risk stands and the gain was one 55 ms frame per 30 s, so it stays opt-in.

## zombie.core.opengl.VBORenderer (added 2026-09-20 evening, thunderstorm pass)

Two edits in the immediate-mode line/quad renderer that every VBORenderer user shares (weather
particles, model atlases, shadows, trees, the vision polygon, debug lines). Both behind Config
keys; with `enabled=false` the stock values apply.

**Batch buffer size** (`vboBatchKb`, default 1024, 4 = stock). The element buffer, its index
buffer and the two GL buffer objects are created at the configured size instead of 4 KB / 1 KB,
and `setFormat` derives the element count from that size. Stock flushed (a `glBufferData` and a
draw) every 113 vertices, i.e. every 28 textured quads; the rain FX at 5120x2160 add ~100k
particle quads a frame (104 tiles of a 512x512 cell of 1024 particles), which was 73 % of the
render thread's busy time in a thunderstorm (`storm-jfr` JFR, `gametree.py --thread main`). The
size is capped at 1.5 MB so every vertex index still fits the 16-bit index buffer.

**Single-advance quad** (`vboFastQuads`, default true). The 4-vertex branch of the textured
`addQuad` (the one that is not lines and not triangles) writes the four vertices, the four
indices and one buffer-position advance in a private helper instead of four `addElement` calls
that each re-check `isFull`, look up the current run and read the buffer position. Same bytes at
the same format offsets (vertex, colour, uv1; other slots left as `addElement` leaves them),
same flush condition, same vertex count bookkeeping.

Result on the storm route (uncapped, direct launcher): render thread submission
(`buildStateDrawBuffer`) 8.7 -> 6.1 ms; 108 -> 131 fps once the game thread stopped being the
wall (runs `storm-rec-vbostock` / `storm-rec-cur`).

## zombie.iso.IsoPuddles (added 2026-09-20 evening, thunderstorm pass)

No behaviour change in the existing methods. Four public `pzopt*` methods expose the pieces of
`render(grid, z)` separately for `pzopt.PuddleCache`: the guard chain (`pzoptCanRender(z)`:
debug option, shader enabled, shaders in use, puddle quality, level clamp, wet-ground /
puddle-size non-zero), the packing loop without the draw (`pzoptPack`, the identical
shouldRender / updateLighting / addSquare sequence), the draw (`pzoptDraw`), and
`pzoptAppend(packed, count, z)` which grows the per-state RenderData like `addSquare` does and
copies pre-packed squares in, keeping the per-level counters. `pzoptNumSquares` / `pzoptData`
read the current main-state RenderData.

## zombie.iso.IsoChunk (third edit, 2026-09-20 evening, puddle cache slot)

One public field `pzoptPuddles`, a `pzopt.PuddleCache.Slot` holding the packed puddle batches
of this chunk per player and level; lazily created by the cache, dropped with the chunk.

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-20 evening, puddle cache)

`renderPuddles(playerIndex)`: after the stock guards (puddles enabled, no snow, level clamp for
the medium/low quality) and before the per-level loop, when `Config.puddleCache` is on the
method hands the on-screen chunk list and `maxZ` to `pzopt.PuddleCache.render` and returns; the
stock loop is untouched below it. In the bake path, right after `clearCachedSquares(level)` is
called for a chunk level, `pzopt.PuddleCache.invalidate(chunk, level)` marks that level's
batches for a rebuild (the puddle square list is refilled by the same bake). The periodic
`[pzopt] FBORenderCell` log line gets the cache counters (built / reused / rebuilt by bake,
cutaway change, expiry).

What the cache does (`pzopt.PuddleCache`, committed): stock re-filters, re-lights and re-packs
every wet square of every on-screen chunk level every frame (`FBORenderCell.puddles` 4.5 ms of a
13 ms thunderstorm frame at max zoom). Of the 32 floats per square only the four vertex lights,
the camera's sub-pixel jiggle on x/y and the depth change between frames, and the depth depends
on the camera's chunk only (`IsoDepthHelper.getSquareDepthData` floors the camera position to a
chunk), shifting by one constant for every square when the camera crosses a chunk edge. So a
chunk level is packed once with the stock code, the block is kept on the chunk, and later frames
copy it into RenderData and patch those three slots (lights from `getVertLight`, jiggle delta,
depth delta from two `getChunkDepthData` calls). Rebuilt when the bake clears the square list,
when the level's cutaway `squareFlags` visibility bits change, or after `puddleCacheFrames`
frames (default 60, staggered per chunk). The per-square `IsOnScreen` cull is not applied to
cached batches (the GPU clips the squares outside the viewport; same picture). Result: puddles
4.5 -> 0.96 ms, storm route 70 -> 109 fps with the profiler on (`storm-vbo` -> `storm-puddle`).

Since 2026-10-05 (`puddleJiggleDepth`, default on; Discord "Flickering textures on white roofs when
it's raining", the West Point GigaMart and car wash): the depth slot also follows the camera jiggle.
Stock packs each vertex's depth at the square corner moved by `fixJigglyModelsSquareX/Y` (up to
~0.06 of a square at the widest zoom), exactly as the chunk composite moves its `chunkDepth` by the
same jiggle; a cached batch kept its build frame's jiggle in the depth while x/y took the current
one, up to 1.7e-4 off against the 1e-4 the puddle sits in front of the floor. On a flat roof
(`roofs_04_*` drawn as the floor, the corrugation in its depth texture) the puddle and the roof traded
places in white dashes along the ridges, a chunk at a time. The depth falls by `CHUNK_DEPTH / 16` per
square along x and along y, linear across chunk edges (the chunk term and `calculateDepth`'s wrap
cancel), so `PuddleCache.jiggleDepth` patches it exactly: the CPU path adds the delta to the copied
depth; `puddleVbo` packs the depth at zero jiggle and the generated `pzopt_puddles_common.vert` adds the
frame's as the uniform `pzoptDepthShift` (build.sh; reset to 0 after the draw, the CPU paths share the
program); with `puddleEarlyZ=false` (stock's programs, no uniform) a moved jiggle re-uploads the batch.
Roof dashes (`harness/roof-dashes.py`): 1,741 -> 263 px a frame; the rest was `swayFloorExact` (below).

## zombie.iso.weather.fx.ParticleRectangle (added 2026-09-20 night, rain tiles)

`render()`: after the stock cell arithmetic and `StartShader`, when `Config.rainTiles` is on and
the debug bounds are off, the method renders every particle with `renderAlpha > 0` once at the
origin into the rectangle's drawer between `pzoptBeginTile` / `pzoptEndTile`, adds one origin per
screen cell (the same `-1..cellsW` x `-1..cellsH` grid the stock loops walk) to that tile, and
returns; the stock per-cell loop with its per-particle `isOnScreen` cull is below it, untouched.
Every subclass `render(offsetx, offsety)` (rain, snow, cloud, fog) adds its quad at the offset
plus the particle's own position, so a cell's picture is the origin picture translated by the
cell origin.

## zombie.iso.weather.fx.WeatherParticleDrawer (added 2026-09-20 night, rain tiles)

Keeps a list of tiles for the frame (cleared in `startFrame`): a tile records, per texture
index, the range of particle-list indices added between `pzoptBeginTile` and `pzoptEndTile`, and
its origins. `render()` (render thread) first hands the tiles, the particle buffer, the texture
list and the per-texture index lists to `pzopt.RainTiles.Gl.draw` (one buffer object and staging
buffer per drawer), then runs the stock VBORenderer loop only over the particles no tile covers
(a fully tiled texture list is skipped). `pzopt.RainTiles.Gl.draw`: uses VBORenderer's own
`vboRenderer_PositionColorUV` shader (new accessor `VBORenderer.pzoptShaderPositionColorUv`),
sets its ModelViewProjection from the current matrix stacks like `VertexBufferObject` does,
packs the template quads once in the same 36-byte position/colour/uv layout, uploads them with
`glBufferData` (stream), binds the texture, and issues one `glDrawArrays(GL_QUADS)` per origin
with the ModelViewProjection uniform translated by the origin; then restores the uniform,
unbinds the buffer, re-enables the attribute arrays 0..4 and the depth test as
`VBORenderer.flush` leaves them, and sets the sprite ring buffer's restore flags. Depth test
off and `userDepth` 0 as VBORenderer's default run. The per-particle on-screen cull of the stock
loop becomes GPU clipping. Counters (tiles, template quads, draws) in the periodic
`[pzopt] FBORenderCell` line.

## zombie.core.opengl.VBORenderer (second edit, 2026-09-20 night)

Public accessor `pzoptShaderPositionColorUv()` returning the lazily created
`vboRenderer_PositionColorUV` shader the class already uses for that format.
## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-20 evening, per-frame lists survive a held re-bake)

The maintainer reported objects inside buildings, doors, windows and corpses flickering
(appear / disappear) in normal play. Reproduced on the end square of the `S:450` route with the
player spinning (`run.sh ... --flag hold=10 --flag turn=90 --flag zoom=1 --record`, runs
`flick-*`; metric `harness/flicker.py`): items on the desks, the table beside the player, the
doors and the wall objects blink out for 1-3 frames; stock shows nothing but the spinning player.

Cause: stock `FBORenderLevels.NLevels.invalidate()` does not only set the dirty bits. Outside
`performRenderTiles` (`FBORenderLevels.clearCachedSquares == true`, set at the top of
`renderInternal` and after the tile pass) it also empties the level's per-frame square lists
(items on tables, obscuring furniture, cutaway window frames, corpses, flies, animated
attachments, puddles, translucent floor), because in stock the bake that follows in the same
frame rebuilds them. Every pzopt hold that draws the previous texture instead of re-baking
(`lightingRebakeMs`, `rebakeBudget`) therefore drew a texture whose per-frame objects were
neither in the texture nor in the lists. `lightingRebakeMs=0` alone took the metric from 26 to
7.8 transient px/frame (stock 3.8); `rebakeBudget=0`, `bakeBudget=0`, `lightingBudget=0`, the
cutaway keys and the texture-content keys changed nothing on their own.

Edit: the two `FBORenderLevels.clearCachedSquares = true` assignments in `renderInternal`
become `= !pzoptKeepPerFrameLists()`, a private static helper that is true when the overrides
are enabled and any of `LIGHTING_REBAKE_MS`, `REBAKE_BUDGET`, `BAKE_BUDGET` is set. The lists
then only change at a bake (`clearCachedSquares(level)` at its start rebuilds them), so they
always describe the texture that is on screen, held or fresh. Stock's own flow is unchanged
(every bake rebuilds them anyway). The re-bake budget no longer holds cutaway (2048) re-bakes:
their per-frame draws re-test the live cutaway flags (`isTableTopObjectSquareCutaway`, the
window-frame flags), so a stale texture could show an object neither baked nor per frame; the
held set is now `32 | 1024` only. Result: 0.1 transient px/frame at object scale (`--scale
1280`; broken build 3.4, stock 0.0); uncapped spinning route 488.7 fps mean, p99 7.2 ms
(`flickfix-u-1`, reference `jvm-zulu-g1-1` 508.7 / 7.3) with ~15 % more bakes per period.

## zombie.iso.IsoChunk (fourth edit, 2026-09-20 evening, per-frame lists cleared on reuse)

`resetForStore` calls a new private `pzoptClearPerFrameLists()`: `clearCachedSquares(z)` for every
level of every player's `FBORenderLevels`. Stock relied on the load-time invalidation to empty
those lists; with the FBORenderCell edit above that invalidation keeps them, so a chunk object
going back to the pool drops them here instead (the corpse and flies lists are iterated for every
on-screen level, baked or not).

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-20 night, lighting-only re-bake spread)

In the re-bake budget block (`REBAKE_BUDGET`), a texture whose only dirty reason is lighting
(flag 32 alone: daylight drift, the ramp of a lightning flash) now uses its own per-frame start
budget `lightingRebakeBudget` (default 8) and its own longest hold `lightingRebakeMaxFrames`
(default 30) instead of `rebakeBudget` / `rebakeMaxFrames` (4 / 3), which stay for the redraw
reason (1024: light switches must answer within a few frames). Why: a lightning strike dirties
every on-screen chunk texture; with the 3-frame cap they all landed in one frame, five times per
strike (flash on, flash off, then every `lightingRebakeMs` of the fade), a 50-90 ms stall each
that the maintainer saw as the rain freezing and jumping every ~6 s (`docs/archive/2026-09-24/findings-scene-presets-2026-09-20.md`
§6). With the spread the same bakes land over ~25 frames: storm drive p99.9 57 -> 12.5 ms, max
88 -> 19 ms, no frame over 33 ms, at the price of a faint chunk checkerboard for ~90 ms while a
flash ramps (chunks baked at different points of the ramp). `lightingRebakeMs=100` was tried
with budgets 8 and 16 and is worse on the tail (chunks become eligible about as often as the
cap, so the cap keeps dumping the backlog).

## zombie.iso.LightingJNI (2026-09-20 night, harness see-all view)

`updatePlayer` passes `pzopt.Scene.seeAll()` to the native `playerSet` where stock passes a
constant false (B41 passed the player's dead state there: a dead player's visibility pass marks
every square seen and visible, the spectator view). The flag comes from the harness flag file
(`see_all=true`, read once at world-ready by `Scene.apply`; false in normal play, so the class
behaves as stock outside a run). Why: the Louisville preset walks through downtown blocks whose
tall buildings stop the vision cone, and the never-seen squares behind them draw black, so the
recordings were mostly black. With the flag every square is lit and drawn on both sides of an
A/B (more visible tiles and characters than a normal view; compare only same-flag runs). No
Config key: it is a scene flag, not an optimization. The class also gains the usual
`pzopt.Overrides.onClassLoaded` static initializer.

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-21, curtains in front of baked windows; GitHub issue #4)

Two pieces. (a) In `calculateObjectRenderLayer`, after the Vegetation test and before the
MinusFloor one, the new private `pzoptCurtainLayer(IsoObject)` decides the layer of a curtain
that hangs in front of the window or door on its own square: it returns the layer already
computed for that attached object in this pass (MinusFloor = bakes, Translucent = per frame),
so the curtain is drawn in the same pass as its window whatever the reason the window landed
there (the `windowsInChunkTexture` key, or a baked window sent per frame while it fades or
obscures the player). The attached object comes first in the square's object list
(`IsoCurtain.getObjectAttachedTo` searches backwards from the curtain's index), and
`calculateObjectRenderInfo` walks the list in order, so its layer is final. Null (stock rules)
when both bake keys are off, for `curtainS` / `curtainE` (which hang on the far side of the
next square's wall and are meant to be seen through the glass), for sheet-door curtains (3D
models at the door's `CurtainOffset`), and when nothing is attached. (b) At the end of
`renderMinusFloor_NotDoorOrWall(IsoObject)`, before the final `object.render(...)`, a curtain
drawn by a bake (`!renderTranslucentOnly`) for which `pzoptCurtainDepthNudge(IsoCurtain)`
returns a positive distance goes through `pzoptRenderCurtainNudged(...)`: its world position is
moved that many tiles into the room (south for `curtainN`, east for `curtainW`, i.e. toward the
camera, a smaller depth in `IsoDepthHelper.calculateDepth`) and `offsetX` / `offsetY` are moved
back by `IsoUtils.XToScreen` / `YToScreen` of the same delta for the duration of the call, so
the pixels land where they always did and only the depth the tile depth shader writes changes.
The nudge is `pzopt.Config.CURTAIN_DEPTH_NUDGE` (`curtainDepthNudgePct`, default 5 = 0.05
tile; 0 = off) and applies under the same conditions as (a).

Why: a Windows user reported windows drawn over closed curtains with the default settings,
correct only with both bake keys off. Stock never depth-tests a curtain against its window:
both are per-frame translucent objects (`IsoWindow`, and the curtain tiles are
`Translucent = true`), drawn in object order with `glDepthMask(false)`, so the curtain (after
the window on its square) simply paints over the glass. With both baked the chunk texture's
depth buffer decides (`DepthTestAll`, `GL_LEQUAL`, depth writes on): the north window glass and
the north curtain use the same wall depth texture (`setupWallDepth`; the few tiles with
geometry boxes have the glass 0.017 tile in front), and the glass came out on top. With one
baked and the other per frame the per-frame sprite is tested against the composited chunk
texture, and the two sample the 8-bit wall depth texture at slightly different sub-pixel
positions: the glass z-fights through the curtain as a dither (the reporter's
"semi-transparent" curtain). Hence (a) removes every cross-pass comparison and (b) settles the
one that remains, inside a single bake where both sprites map to the same texels and a nudge
of about 3 depth-texture steps (one step is about 0.016 tile) is an exact margin. Verified on
the bench save with the `find=curtains` / `close_curtains=true` harness dev flags at
8147,11507 (Rosewood living room, `fixtures_windows_01_9` + `fixtures_windows_curtains_01_50`):
runs `i4-repro` (nudge 0, no (a): glass over the curtain), `i4-fix` ((b) alone, defaults:
curtain covers the glass, same pixels), `i4-wpf` / `i4-tpf` ((b) alone with one key off:
dithered glass), `i4-fix2` / `i4-wpf2` / `i4-tpf2` ((a) + (b): curtain covers the glass in all
three settings).

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-21, tree pass; GitHub issue #5)

Trees baked into the chunk-level textures (`treesInChunkTexture`) get their own draw pass
(`pzopt.Config.TREE_BAKE_PASS`, `treeBakePass`, default true; the drawer is `pzopt.TreeBake`,
described in `src/CLAUDE.md`). Edits, all marked `// pzopt: issue #5`:

1. **`renderMinusFloor(IsoChunk, IsoGridSquare, PZArrayList)`** skips `IsoTree` objects while the
   pass is active (`pzoptTreePassActive()`: the two keys, the overrides enabled and the
   wind-sprite-effects option off, under which stock never bakes a tree anyway).
2. **`renderOneLevel`**, on the bake path just before `endRenderChunkLevel(c, level, zoom, true)`,
   calls `pzoptBakeTrees(c, playerIndex, zoom)` when `level` is the texture's top level, i.e. after
   every level of the texture has been drawn.
3. **`pzoptBakeTrees`** walks the 5x5 chunk neighbourhood of `c` (the chunk itself and the
   neighbours whose lighting has been done). For every square at a level of this texture that
   carries a tree which would bake (`pzoptTreeBakes`: not highlighted, animating, wind- or
   hit-affected, fading, awaiting its texture or translucent under the player; for the chunk's own
   trees also the `MinusFloor` render layer this bake computed), that the cutaway data lets render,
   that is not occluded and not a force-render square, the sprite's full rectangle in this texture's
   space is computed (`pzopt.TreeBake.spriteRect`, from the square, the chunk corner, IsoTree's
   offset rule for the JUMBO sizes and the texture's untrimmed size). The chunk's own trees are
   drawn when the rectangle touches the texture; a neighbour's tree only when the part of it inside
   this texture is not entirely inside the tree's own chunk texture (`needsCopy`), so a tree that
   fits its own texture is never duplicated. The colour is the square's light info (a neighbour's
   square gets `cacheLightInfo()` first, as the stock bake does for the north and west squares it
   draws); `unlit` sprites draw white. The depth is the one the sprite path would write for the
   square's south corner (`getSquareDepthData` minus this chunk's `getChunkDepthData`), and a
   neighbour's tree with a negative result (a nearer chunk, below this texture's depth range) is
   skipped because its own and nearer textures hold it. Each texture of the tree (main sprite and the
   attached foliage overlays) is handed to the drawer with its trimmed rectangle and the depth at
   its top and bottom rows (`depthAtRow`: the base at the ground row, one level's depth nearer per
   level of height). The chunk's own baked trees also get `renderFlag = false`, what the stock bake
   records for `checkTreeTranslucency`.
4. **Export fingerprints.** While walking its own trees, the pass sums an identity hash of every
   tree that needs a copy in each of the 24 neighbours' textures (`IsoChunk.pzoptTreeExportFp`, one
   int per slot). A slot whose value changed since the chunk's last bake re-bakes that neighbour's
   texture (`invalidateLevel(minLevel, DIRTY_TREES)`) if it has one, so a tree chopped, grown, gone
   per frame or back on this chunk never leaves a stale copy elsewhere.
5. **`checkTreeTranslucency`** calls `pzoptInvalidateTreeCopies(tree)` where it already invalidates
   the tree's render square (state change, texture arrived): every neighbour texture that needs a
   copy of the tree re-bakes in the same frame as the tree's own chunk.
6. The periodic counters line gets `tree bake: passes= trees= copies= quads= neighbours re-baked=`.

Why: a chunk-level texture covers its chunk's footprint plus two levels and the JUMBO_L
allowance above it (`extraHeightForJumboTrees`), but a tree sprite is anchored on one square and
is up to seven tiles wide and sixteen tile heights tall. Baked through the plain sprite path
into its own chunk's texture (the previous `treeBakeDirect` path) a JUMBO tree is clipped at the
texture border: the maintainer's report of crowns cut by straight edges and of a chunk-sized
black rectangle (a tree drawn black, as stock draws trees on never-seen squares in heavy fog,
clipped the same way). The plain path also writes one flat depth for the whole sprite while the
walls it overlaps write per-pixel depths that get nearer with height (`zDepthBlendZ` to the
front corner one level up), so an upper-storey wall behind a tree cut a vertical strip out of
its crown. Stock's own chunk-texture tree batch (`FBORenderTrees` with `renderThreadCurrent`
set) draws the crown dark and behind the house (run `trees-ab-batch`), which is presumably why
stock never bakes trees. The pass draws the quads through VBORenderer's position/colour/uv/depth
format (`vboRenderer_PositionColorUVDepth`, the fragment shader writes the interpolated depth),
under `GL_LEQUAL` with depth writes and the alpha test on, last in the texture, so content in
front already in the depth buffer occludes the tree and content behind is painted over, like
the stock per-frame tree billboard against the composited textures. Verified on the issue's
capture (`--source-save Apocalypse/2026-09-20_22-30-24 --flag start=11023,6720 --flag fog=heavy`
`--shot-at 1`): run `trees-fix1` differs from the per-frame reference `trees-ab-off` in 0.08 % of
the pixels (crown outlines), the previous bake `trees-shot` in 3.43 %; on the Rosewood capture
(`route=S:450 zoom=1 --shot-at 12`, runs `trees-town-on` / `-off` / `-plain`) the pass matches the
plain path's brightness and the per-frame reference drawn without `vboFastQuads`. Spinning
route (`gt` route, 25 s): 280 fps with the pass, 281.5 with the plain path, bakes +9 % (the
neighbour re-bakes), flicker rig 0.3 px/frame at scale 2560 (stock 3.8).

## zombie.iso.IsoChunk (fifth edit, 2026-09-21, tree export fingerprints)

A public `int[] pzoptTreeExportFp` (25 slots, allocated by `FBORenderCell.pzoptBakeTrees` on the
chunk's first tree pass) and its reset to null in `resetForStore()`, so a reused chunk object
starts without another chunk's fingerprints.

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-21 afternoon, trees per frame while chunks churn; `treeBakeMaxChunksPerSec`)

A per-frame mode bit `pzoptTreesPerFrameNow`, set once per frame by `pzoptUpdateTreeMode()` at the
top of `renderTilesInternal` (frame number stamp `pzoptTreeModeFrame`): true when
`Config.TREE_BAKE_MAX_CHUNKS_PER_SEC` is above 0 and `pzopt.ChunkRate.perSecond()` (chunk hand-offs
to the game thread, exponential average over half-second windows) is above it.
`isTreeRenderedEveryFrame` treats every tree as per-frame while the bit is set (the stock answer),
and `pzoptTreePassActive()` — now an instance method — is false, so a chunk level baked in that
frame gets no trees and no tree pass, and the per-frame path draws them. Counter
`pzoptTreesPerFrameFrames` and the current rate on the instrument line.

Why: on a 4-core i5-6300HQ (Dell, `docs/archive/2026-09-24/results.md` 2026-09-21 low-end section) baking trees
while driving at 120 km/h was a net loss — a chunk texture lives a second or two, and baking its
trees (own texture plus the neighbour copies of the tree pass, plus the tree pass's 5x5-chunk scan)
cost more game-thread time than drawing them per frame for that long: `workers=1` 19.5 fps, with
`treeBakePass=false` 28.6, with `treesInChunkTexture=false` 42.7 (stock 29.1). Walking through
Rosewood at max zoom the bake amortises over hundreds of frames and the default set is 47.3 fps
against stock's 31.5. So the choice follows the chunk rate instead of a fixed key. No re-bake burst
on a mode flip: a tree's layer is stored in its `ObjectRenderInfo` at bake time and read per frame,
so textures baked with trees keep drawing them from the texture until they re-bake for their own
reasons, and the tree export fingerprints (`pzoptTreeExportFp`) re-bake a neighbour whose copy of a
tree went stale. During the seconds a neighbour still holds a copy of a tree now drawn per frame,
that tree is drawn twice in the overlap (slightly denser alpha edges), the same transient the
fingerprint re-bake already covers. Default 0 (always bake, the desktop behaviour); the Options
tab's "Low-end hardware" profile sets 24.

## zombie.iso.IsoChunk (sixth edit, 2026-09-21 afternoon)

One line at the top of `loadInMainThread()`: `pzopt.ChunkRate.loaded()`, the hand-off counter the
edit above reads.

## zombie.iso.weather.fog.ImprovedFog (added 2026-09-21, fog pass)

`endRender()`: when `Config.fogPass` is on (`pzopt.FogPass.enabled()`), the remaining rows of the
layer are walked by a private `pzoptRenderAllRows` instead of `renderRowsBehind(null)`. It is the
same loop over the same iterator, `lastRow`, `lastIterPos` and open-rectangle statics, producing
the same rectangles, but the square lookup is hoisted per chunk: the chunk is fetched from the
chunk map once per eight squares (`getChunkForGridSquare`, accepted only when loaded and when its
`wx`/`wy` are the expected ones, the mid-scroll guard of the IsoChunkMap override) and the square
read straight from the chunk's level array. A missing or unloaded chunk, or a level the chunk has
no squares for, counts as fog exactly as the stock null square does. Stock resolved the chunk map
for every one of the ~10k squares per level per frame at max zoom.

With `Config.fogMaskFrames` > 0 (default 20) a second private walk, `pzoptWalkMasks`, replays
the RectangleIterator's geometry itself (rows of alternating lengths ceil(rowlen/2) and +1, row
starts stepping (0,0) (0,1) (1,1) (1,2) ..., the last position of the last row never delivered,
as the stock `next()` returns false before it is used; `startRender` records the `rows` /
`rowlen` it passed to the iterator) and reads the fog test of up to eight squares of a row from
one short of the chunk's per-diagonal masks (`pzopt.FogPass.ChunkFog` on `IsoChunk.pzoptFog`:
bit lx of `diag[level][lx + ly]` = the square takes fog, i.e. no square, or exterior and not in a
room; levels 0 and 1 only, the ones ImprovedFog draws). A chunk's masks are recomputed when older
than `fogMaskFrames` frames, the first computation stamped up to `fogMaskFrames - 1` frames in the
past by a hash of the chunk position so the refreshes spread over the frames; a new room or wall
therefore reaches the fog within `fogMaskFrames` frames (stock: the next frame). The walk touches
one chunk object per up to eight squares and no square objects, and runs of up to eight all-fog
or no-fog squares advance in one step: 215 → 49 µs per level per frame at 1920x1080 max zoom on
the laptop, with the same 92.7 segments per level. The segments found (start and end square per
segment, world coordinates) are kept per level with the diamond they were found in (minX, minY,
maxX, maxY) and the frame; while the diamond is the same and fewer than `fogMaskFrames` frames
have passed, the next frames replay them through `renderFogSegment` (which recomputes the screen
rectangle and depths from this frame's camera) instead of walking: on the desktop at max zoom
(22k squares per level) the walk runs about one frame in four while driving, 59 → 28 µs per level
per frame averaged. Both walks record their time, segment count and square count for the
`fog pass:` counters. Everything else in the
class (`startRender`, `renderRowsBehind`, the segment maths and depths, `startFrame`) is
untouched.

## zombie.iso.weather.fog.ImprovedFogDrawer (added 2026-09-21, fog pass)

`render()` (render thread): when the fog pass is on, the drawer copies its 28 uniform floats
into an array and hands them, its rectangle buffer and the noise texture to its
`pzopt.FogPass.Gl` instance (one per drawer, like the drawers themselves per player and
sprite-renderer state); when that returns true the stock body is skipped, otherwise the buffer
is rewound and the stock body runs (the fall-back once the driver refused the depth copy or a
shader did not compile). `pzopt.FogPass.Gl.render`, per frame:

1. Reads the viewport and the bound draw framebuffer. Keeps a fog buffer (RGBA8 colour texture
   plus a depth texture) at `fogScalePct` % of the viewport per axis (clamped to 25..100) and,
   below 100 %, a full-size depth texture for a copy of the scene depth. The depth textures use
   the scene attachment's own internal format, read from the framebuffer, so the depth blit is
   format-compatible whether the scene depth is a renderbuffer, a texture or the window's.
2. Gets the scene depth. When the scene framebuffer's depth attachment is a texture (the
   `MultiTextureFBO2` edit below makes the offscreen buffer's one a texture) it is read in place:
   at 100 % that texture is attached to the fog buffer as its depth (the rectangles never write
   depth), below 100 % a reduction pass writes each fog texel the *nearest* (smallest) scene depth
   of the block of screen pixels it stands for (up to 4x4, `texelFetch` loop, `gl_FragDepth`, depth
   func ALWAYS, colour writes off). When the attachment is a renderbuffer (some other FBO, or the
   swap failed) the depth is first copied with a nearest `glBlitFramebuffer` (the first eight
   frames after a (re)creation check `glGetError`): at 100 % straight into the fog buffer, below
   100 % into a full-size depth texture the reduction reads. A nearest-sampled *scaled* blit picked
   one arbitrary pixel per block, so around a one-pixel power line the fog was decided by the
   ground behind it in most blocks and the wire came out dotted; with the block's nearest depth
   every block that holds a thin near object keeps that object's depth.
3. Clears the fog colour (the clear colour is saved and put back), packs every rectangle as one
   quad in a 36-byte layout (position; the corner's position in the rectangle, the stock side-fade
   width as a fraction of the rectangle width, the row noise offset; the rectangle depth and the
   layer alpha) through a ring of three stream buffers and draws them all with one `glDrawArrays`
   under its own programs compiled from strings: the vertex shader takes the depth from the
   attribute into `gl_Position.z` (no `gl_FragDepth`, so early depth rejection works) and the
   fragment shader is the stock `fog.frag` maths with the per-rectangle uniforms replaced by the
   interpolated attributes and `gl_FragCoord` mapped from fog-buffer to viewport pixels. Depth
   test GL_LESS with the depth mask off, `glBlendFuncSeparate(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE,
   ONE_MINUS_SRC_ALPHA)` so the buffer accumulates premultiplied colour and correct coverage
   (identical to sequential "over" because every rectangle covering a pixel has the same colour),
   scissor and stencil off. The noise texture (`media/textures/weather/fognew/fog_noise.png`,
   loaded by the game without mipmaps) is sampled through a sampler object with trilinear
   filtering after a one-time `glGenerateMipmap` on the game's texture object (the levels survive
   the game's per-bind filter juggling; the sampler never changes the texture's own parameters; if
   the driver refuses the mipmaps the sampler is not used): in a scaled buffer the seven fetches
   per fragment are texels apart, so without mipmaps every fetch missed the texture cache and the
   draw was memory-bound (1.2 ms at 25 % on a Radeon 890M, the same at 50 %) and the noise aliased.
4. Restores the scene framebuffer and viewport and draws the fog buffer over the scene once as a
   clip-space quad with `(ONE, ONE_MINUS_SRC_ALPHA)`. At 100 % that is a texel copy. Below 100 %
   every screen pixel reads the four nearest fog texels and their depths; when those depths
   straddle an edge (spread > 0.0003) it also reads its own scene depth and weighs the texels by
   bilinear distance (floored at 0.05 so a neighbour can still win) divided by the distance between
   the texel's depth and its own: the fog texel that was decided at this pixel's surface dominates,
   so a wire keeps the fog decided at its depth and the ground next to it its own, instead of a
   bilinear smear of the two. Away from edges it is a plain bilinear blend.
5. Puts back the texture units, the buffer binding, the attribute arrays 0..4, the depth test,
   mask and blend function, runs `GLStateRenderThread.restore()` as in the stock body, and sets
   the sprite ring buffer's restore flags.

Counters (frames, rectangles, fall-back, the game-thread walk split) in the periodic `[pzopt]
FBORenderCell` line; the buffer sizes and the noise mipmap result are logged when created. With
`gpuSections=true` the sub-sections `fog.blit` (copy + reduction), `fog.rects` and
`fog.composite` are timed from the render thread (`GpuSections.markNow`). Measurement switches
`devFogNoDraw` (everything but the rectangle draw) and `devFogFlat` (a flat fragment shader).

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-21, fog pass)

`renderFog`: with the fog pass on, the per-level loop over every on-screen chunk, its squares and
their objects (which only called `ImprovedFog.renderRowsBehind(square)` on the first floor object
of each square) is replaced by `startRender` / `endRender` per level: the FBO renderer draws all
rectangles from the drawer at `endFrame` in any case, so the painter's-order interleaving the walk
provided did nothing. Both paths are bracketed by the GPU section `fog`, and the fog pass counters
are appended to the periodic log line.

## zombie.iso.IsoChunk (sixth edit, 2026-09-21, fog masks)

A public `pzopt.FogPass.ChunkFog pzoptFog` slot (the per-diagonal masks of the squares of levels
0 and 1 that take fog, see the ImprovedFog entry) and its reset to null in `resetForStore()`.

## zombie.core.textures.MultiTextureFBO2 (added 2026-09-21, fog pass)

`createTexture` (the real branch): after the stock `new TextureFBO(tex)` it calls
`pzopt.FogPass.sceneDepthAsTexture(fbo, tex)`, which on the render context (when `fogPass` is on)
replaces the FBO's DEPTH24_STENCIL8 depth+stencil renderbuffer with a DEPTH24_STENCIL8 texture of
the texture's hardware size on the `GL_DEPTH_STENCIL_ATTACHMENT`, checks completeness, and either
deletes the renderbuffer (the later `TextureFBO.destroy` deletes the name again, which GL
ignores) or, on any failure, re-attaches it and keeps the stock state. Rendering into a depth
texture is the same as into a renderbuffer (same format, same stencil bits); the point is that the
fog pass can sample the scene depth where it is instead of copying the whole depth buffer every
frame (44 MB at 5120x2160). Textures of FBOs that no longer exist (zoom-level or resolution
changes recreate the offscreen buffer) are deleted at the next call. The FBO's private id is read
by reflection. Logged as `offscreen buffer WxH (fbo N) depth+stencil is texture T`.

## zombie.iso.IsoPuddles (second edit, 2026-09-21, storm parity pass)

Two additions, no behaviour change with the keys off. `pzoptTruncate(numSquares, z)` drops the
squares packed after index `numSquares` from the main-state RenderData (count and per-level
counter): `pzopt.PuddleCache.renderVbo` packs a batch with the stock `pzoptPack` and then uses
the RenderData only as scratch space. `applyPuddlesQuality()` builds the `PuddlesShader` from
`pzoptShaderName("puddles_lq|mq|hq")`, which with `Config.puddleEarlyZ` (default on) returns the
`pzopt_` copy shipped under `media/shaders/` (`src/shaders/`): same `#include`s and colour
math, but the vertex shader sets `gl_Position.z` from the depth attribute and the fragment
shader no longer writes `gl_FragDepth`, so the GPU's early depth test drops the wet-ground
pixels hidden behind walls, roofs and objects before the ~200-op puddle shader runs. The files are
generated by `scripts/build.sh` from the installed game's puddle shaders (the game's `#include "x"`
pulls `x.h` for the prototypes and compiles `x.glsl` as a second shader unit of the program, so the
variant needs its own copies of both): the entry files with the include renamed, the `.h` stubs
copied, the vertex unit with one added line after `vDepth = aFragDepth;` and the fragment unit
with its three `gl_FragDepth = vDepth;` lines removed. Two things learned the hard way: an
assignment to `gl_FragDepth` anywhere in a fragment program, even in a function that is never
called, makes the depth shader-written (undefined where not executed, and no early test), and the
depth attribute is relative to the camera's chunk and negative for nearer chunks, which the stock
write clamps per fragment but the vertex path would clip, so the draws (`PuddleVbo.Gl.draw` and
`renderSome`) enable `GL_DEPTH_CLAMP` around them: same "interpolate, then clamp to the range".
`applyPuddlesQuality` logs the live program and whether it compiled (`[pzopt] puddles: shader`).
Depth values are otherwise the same (orthographic projection, w = 1, window depth = the attribute).
Storm drive 120 km/h on the desktop: puddles GPU section 0.29 -> 0.10 ms, 357 -> 390 fps.

## zombie.iso.LightingJNI (second edit, 2026-09-21, puddle light hook)

In `JNILighting.updateFBORenderChunk`, right after the eight cached vertex lights are refreshed
from the native result, when any of the lower four (the puddle corner colours) changed the
square's chunk level is reported to `pzopt.PuddleCache.lightsChanged(chunk, z)`, which marks the
level's cached puddle batches for a light patch and re-upload (`Config.puddleVbo`). Stock's own
`invalidateLevel(z, 32)` calls are unchanged.

## zombie.iso.IsoChunk (seventh edit, 2026-09-21, puddle batches on reuse)

`resetForStore()` calls `pzopt.PuddleCache.chunkReused(this)`: every cached puddle batch of the
slot is marked invalid, so a chunk object reused for another position rebuilds (and re-uploads)
its batches instead of trusting the list-size check alone. The GL buffers stay with the slot and
are refilled.

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-21, storm parity pass)

`renderRainSplashes(playerIndex, z)`: with `Config.rainSplashesFast` (default on) each on-screen
chunk level goes through `pzopt.RainSplashes.update` / `render` instead of
`IsoChunkLevel.updateRainSplashes` / `renderRainSplashes`. Same fields, same advance, same flags
and the same `IsoGridSquare.renderRainSplash` call; only the per-idle-square `Rand.NextBool(n)`
(one call through the CellularAutomatonRNG per idle square of every on-screen level per frame,
2.6 % of a laptop thunderstorm frame) is replaced by geometric skipping with a local xorshift
generator: one draw per splash start gives the number of idle squares to skip, which is the same
Bernoulli(1/n)-per-square process. Two GPU sections were added for the storm profile:
`puddles` (both puddle draws) and `splashes`. The periodic log line gets the splash counters.

What `pzopt.PuddleVbo` does (`Config.puddleVbo`, default on, on top of `puddleCache`): stock, and
the cache alone, copy every on-screen puddle batch into IsoPuddles' RenderData each frame and
patch the vertex lights, jiggle and depth of every vertex (`PuddleCache.reuse`, 11.7 % of a laptop
thunderstorm frame), and the render thread streams that block through the 64 KB ring buffer in
~7 map / draw cycles (`IsoPuddles.renderSome`, 8 % of its frame). Now a batch is packed once with
the stock code, its jiggle normalised to zero, and uploaded to its own GL buffer (`Batch.vbo`,
render thread); later frames only re-upload it when a square's light changed (the LightingJNI
hook above), the camera crossed a chunk edge (one depth constant for every vertex) or it was
rebuilt. The game thread lists the on-screen batches (~90 items) into a `PuddleVbo.Frame` per
level, handed to the render thread through `SpriteRenderer.drawGeneric` with an immutable
snapshot of every batch due for upload; the render thread repeats `ModelManager.RenderPuddles`'
state (projection, `PuddlesShader.updatePuddlesParams`, blend, depth) and draws one indexed range
per batch from its buffer with the frame's jiggle folded into the ModelViewProjection uniform.
Same vertex bytes, same shader, same order.

A reduced-resolution puddle layer (the puddle shader once per screen pixel, composited depth-aware
like the fog pass) was built and measured on the way and dropped: the puddle draw's viewport is the
5120x2160 screen, not the zoom-out buffer, so "100 / zoom" was only 2048x864, and the reduction and
composite passes cost more than the early-Z draw saves (376 vs 389 fps, run `lz-d-storm-scale-1`).

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-21, tree copies appended; storm parity pass)

`Config.treeAppend` (default on), marked `// pzopt: treeAppend`. In `pzoptBakeTrees`' export
fingerprint loop a slot that goes from 0 to a value (the chunk exports trees into that
neighbour's texture for the first time: the usual case is a newly loaded chunk next to already
baked ones while driving) no longer invalidates the neighbour's texture. `pzoptQueueTreeAppend`
lists the chunk's trees that reach into that texture, placed in *its* space (`pzoptTreeRect`
with the neighbour's chunk coordinates and its texture's `xoff` / `yoff` as
`beginRenderChunkLevel` computes them, `needsCopy` against its rectangle, the depth relative to
its chunk, the same light and the same texture quads as the pass), into a pooled
`TreeBake.Drawer` that remembers the target texture. Before `FBORenderChunkManager.endFrame`
composites the textures, `pzoptFlushTreeAppends` draws each queued drawer into its texture
inside the bake's own begin / end sequence (`glDoStartFrameFlipY`, `FBORenderChunkStart` without
a clear, the generic draw, `FBORenderChunkEnd`, `glDoStartFrame`): the render thread binds the
texture with its depth attachment, the quads go through the pass's depth test (`GL_LEQUAL`,
depth writes, alpha test) on top of the finished bake, and `endRenderThread` regenerates the
mipmaps. The drawer refuses to draw when the bound framebuffer's colour attachment is not the
expected texture (`expectTexture`). A neighbour texture that is dirty, off screen, not the level
group's current texture or not among the frame's composited textures at flush time is
invalidated as before, and every later fingerprint change (a tree chopped, grown, gone per frame
or back) still re-bakes. Same picture: the pass draws trees last in a bake anyway, so drawing
them last into the finished texture is the same sequence of draws. Counters: `appends= (queued=
fell back= refused=)` in the tree bake line. Why: on the 120 km/h desktop drive 60 % of all bakes
(`trees=5217` of 8571 per 1800 frames, 2.9 a frame at ~300 µs of GPU each) were these neighbour
re-bakes, in clear weather and storms alike.

## zombie.iso.LightingJNI (third edit, 2026-09-21 evening, strong light changes)

The "blocky lights" report (maintainer video, 2026-09-21: a hand torch swept in a garage at
night showed the beam as a patchwork of tile-stepped, stale pieces): the held lighting-only
re-bakes of FBORenderCell (`lightingRebakeMs`, `lightingRebakeBudget` / `lightingRebakeMaxFrames`)
were tuned for sky drift and lightning flashes, but a moving light source dirties the few chunk
levels in its beam every frame and each held level kept showing the beam at a past angle, one
angle per chunk. Stock re-bakes them every frame.

`JNILighting.updateFBORenderChunk`: in both branches that call `invalidateLevel(z, 32)` the
square now reports the size of its change to the new `pzoptLightChanged(...)`: the light-info
channels' differences added together, a quarter of the dark-multiplier move (in 1/1000), a
light-level change counted as 255, and the largest channel difference of the eight vertex lights.
The square sums those deltas since its level was last baked (`pzoptLightAcc`, reset when
`IsoChunk.pzoptLightBakeFrame[level]` is newer than the square's last accumulation) and past
`Config.lightingStrongDelta` (6) marks the level strong for the frame through
`pzopt.LightDirt.markStrong`.

`LightingJNI.update`: before `stateEndFrame` for player 0 the global light handed to the engine
(rmod, gmod, bmod, ambient, night, sky level) goes to `pzopt.LightDirt.globalLight`; a move past
`Config.lightingGlobalDeltaPct` (2 %) in one frame is a flash or a fast-forwarded dusk and keeps
the re-bake spread on for `lightingRebakeMaxFrames + 2` frames. (A count of strong levels a frame
was tried first and rejected: turning moves the vision cone across the whole screen, so a sweep
marked as many levels as a flash.)

## zombie.iso.IsoChunk (eighth edit, 2026-09-21 evening, strong light stamps)

Two per-level frame arrays (index z + 32): `pzoptLightStrongFrame`, the frame a square of the
level accumulated a strong light change, and `pzoptLightBakeFrame`, the frame the level's
texture was last baked. `resetForStore()` calls `pzopt.LightDirt.chunkReused(this)`, which
fills both with -1.

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-21 evening, strong light changes)

In the bake-decision block of `renderChunkLevel` (the `lightingRebakeMs` hold and the
`rebakeBudget` spread): a level whose lighting-only dirt is strong
(`pzopt.LightDirt.rebakeNow(chunk, level, frameNo)`: marked strong since its last bake and no
global light event in progress) skips the `lightingRebakeMs` hold and gets an unlimited frame
budget in the spread, so it re-bakes this frame like stock; it still counts as a started re-bake
for the weak ones behind it. Where the texture is (re)baked, `pzopt.LightDirt.baked` stamps the
level's bake frame. Counters `strong now=`, `strong marks=`, `global light events=` on the
periodic log line. Verified on the night-torch spinning bench (`bl-torch-*` runs, zoom 1,
recorded): hard-jump pixels per frame between consecutive recording frames stock 7.7 k, holds
off 7.9 k, this fix 7.9 k, before 9.9 k (p90 16.7 / 17.5 / 17.9 / 22.3 k).

**Strong budget (2026-09-22 night).** The strong path had no cap, and turning marks far more
than a beam does: the out-of-sight fade (`darkMulti`, a quarter of its 1/1000 move in the
accumulator) flips on every exterior square the vision cone crosses, so a spinning player marked
about every on-screen level strong every frame. Cheap on the Rosewood spin at 400 fps (`strong
now=` ~2 a frame), but on the Louisville horde preset (`--preset louisville`, downtown, eight
levels a chunk, `see_all`) it was 100+ tall-chunk bakes a frame: 10.8 fps, GPU 97 %, chunk
bakes 30 % of the game thread, and the never-baked levels behind them starved black (the
"black squares" seen during run `lou-base`; `lightingStrongDelta=100000` gave 26.9 fps, GPU
35 %, bakes 4 %). New `pzoptStrongNow(chunk, level)` grants the strong path to at most
`Config.lightingStrongBudget` (8) levels a frame (`pzoptStrongThisFrame`, reset in
`renderInternal` with the other per-frame budgets); a strong level past the budget takes the
ordinary `lightingRebakeMs` hold and the spread, i.e. it still re-bakes within 250 ms. A torch or
headlight beam touches a handful of levels a frame, so the blocky-lights fix above keeps its
whole budget; only sweeps that mark dozens are spread. Counter `strong past budget=` on the log
line; `lightingStrongBudget=0` restores the uncapped behaviour for A/Bs.

**Creation first (2026-09-22 night, second pass).** With the strong budget in place the black squares
still showed under any extra load: the never-baked budget (`bakeBudget`, 8) was counted against
`pzoptBakesThisFrame`, which every bake incremented, so eight re-bakes of any kind (strong, lighting
spread, redraw, object changes, all allowed before it) used the budget up and the never-baked levels
of the chunks late in the draw order were deferred again and again, black on screen while re-bakes
kept flowing every frame. Now the budget counts creations alone (`pzoptCreatesThisFrame`), and while a
creation was deferred in the previous frame (`pzoptCreatesDeferredLastFrame`) the optional re-bakes
wait: no strong grant, every lighting-only level is held regardless of `lightingRebakeMs`, and the
spread budget is zero (its `lightingRebakeMaxFrames` / `rebakeMaxFrames` caps still apply, so nothing
stays stale for long). Object, tree, cutaway and obscuring re-bakes are never held, as before. Counter
`creations deferred=` on the log line.

## zombie.iso.LightingJNI + FBORenderCell (2026-09-21 evening, lighting-budget flush)

The second half of the "blocky lights" report, the 120 km/h night drive: with `lightingBudget`
(8 chunks a frame) the chunks past the budget are refreshed on a later frame, and if the next
lighting pass lands first it rewrites every per-square dirty bit, so their squares read "not
dirty" and keep the light of the previous pass (chunk-sized dark patches inside the headlight
beam; `--prop lightingBudget=0` was clean). Reading a non-dirty square anyway is no cure: the
engine answers with the previous pass (tried as a forced read, it produced a checkerboard).

`LightingJNI.update`: before `stateBeginUpdate` for each player it calls
`FBORenderCell.pzoptFlushPendingLighting(playerIndex)`, which refreshes every chunk level still
in the budget's pending queue (on-screen chunks only, the player index pinned in
`IsoCamera.frameState` for `cacheLightInfo`) and clears the queue. The budget therefore spreads
the refreshes over the frames between two passes (2-4 at 60 fps with the 15-30 Hz lighting
thread) and whatever is left lands in the frame the pass arrives, never lost. Counter
`flushed=` on the log line. Verified on the SportsCar night drive (`bl-drive-*` runs, race cars
have no headlight beam by script).

## zombie.network.NetChecksum (added 2026-09-21, multiplayer Lua checksum)

A user joining a community server was refused with `File doesn't exist on the server:
media/lua/shared/pzopt/pzopt_keybinding.lua`. When a client connects, `LuaManager.LoadDirBase`
feeds every file under `media/lua/shared` and `media/lua/client` (game and mods) into
`NetChecksum.Checksummer.addFile`: an MD5 over all of them that the server compares with its
own, plus groups of per-file checksums the server walks to name the odd file when the totals
differ. Stock leaves `SandboxVars.lua` out of the list. The three pzopt Lua files (the
Optimizations tab, the frame-cap combos, the F9 key binding) are installed into the client's
`media/lua/{shared,client}/pzopt/` and exist on no server.

One edit, at the top of `Checksummer.addFile`: with `Config.luaChecksumExempt` (default true)
a path that `pzopt.LuaChecksum.exempt` recognises (`media/lua/<sub>/pzopt/...`, either slash
style, any case) returns before the file is read, so it enters neither the total nor a group.
Everything else is byte-identical stock. The exemption is deliberately not tied to `enabled`:
the files are on disk either way, and a server that has the overrides installed skips them
too, so both sides agree whichever of them carries the files. Unit test `LuaChecksumTest`.

Verified 2026-09-21 on the MacBook against a stock dedicated server (an APFS clone of the game's
`Contents/Java` with every pzopt file removed, `zombie.network.GameServer -nosteam -no-worldgen`
in its own `-cachedir`, `DoLuaChecksum=true`), the client with the overrides installed, started
with `+connect 127.0.0.1:16261 -nosteam`, its own `-Ddeployment.user.cachedir`, a throw-away
`media/lua/client/pzopt/pzopt_devjoin.lua` that presses Connect / spawn / character Next on
`OnFETick`, and a `mode=verify` flag file so `AutoStart` presses click-to-start. Default:
`client: DoLuaChecksum start` → `end`, `OnGameStart` in the world. `luaChecksumExempt=false`:
`force-disconnect checksum-File doesn't exist on the server: media/lua/shared/pzopt/pzopt_keybinding.lua`,
the reported message. A killed client leaves "User is already connected" on the server;
`kickuser` on its console clears it.

## zombie.WorldSoundManager (added 2026-09-22, world-sound hitch)

Loose copy with one edit in the full `addSound`, behind `pzopt.Config.WORLD_SOUND_FAST`. Stock
attaches a new sound to every chunk in the square of side `2 * radius * hearingMultiplier` around it
by asking the cell for each of those world chunk coordinates, so a 600-tile house alarm (or a 500-tile
helicopter pass, a 600-tile meta gunshot) asks for 22,500 chunks at normal hearing and 202,500 at
pinpoint hearing, of which only the loaded grid (a few hundred) can answer. The edit intersects the
radius rectangle with the union of the active players' loaded chunk ranges before the walk; the cell
returns null outside that union, so the chunks that receive the sound are exactly stock's. The rest of
the method (the sound object, the manager list, the population manager call, the network send) is
untouched. Why it matters: `zombie.iso.Alarm.update` adds its 600-radius sound every frame for the
~49 s an alarm rings, so every per-call cost here is a per-frame cost during an alarm.

## zombie.iso.FishSchoolManager (added 2026-09-22, world-sound hitch)

Loose copy with a memo in `addSoundNoise`, behind `pzopt.Config.WORLD_SOUND_FAST`. Every world
sound (from `WorldSound.init`, single player and server) scares the fish: the stock method walks
every square of the disc of radius `soundRadius / 6` around the sound (40,000 squares for the house
alarm, with a square root, the procedural fish-point roll, the no-fish-zone list and a boxed
hash-map probe per square) and writes "disabled until now + 180 game minutes" for each fish or chum
point in it. A repeat of the same call in the same game minute rewrites the same keys with the same
value, i.e. does nothing, and the house alarm makes exactly that call every frame. The edit keeps
the last eight (centre, radius, game minute) calls and returns at once for a repeat; the memo is
dropped whenever the noise or chum maps are cleared, purged of expired entries, replaced by the
server's copy, or a chum point is added, so a call that could write something new always runs.
Saved and transmitted data are unchanged (the maps themselves are never touched by the memo).
Same key, same day, the walk itself: stock tests every square of the box against all fourteen no-fish
rectangles, takes a square root for the disc test and probes the chum map. The override's walk selects
the rectangles that meet the box once per call (none, almost always), compares the squared float distance
with the squared radius (the same float arithmetic as the stock helper; the square root is monotonic, so
the test is identical), probes the chum map only when chum points exist, reads the game clock once, and
handles a missing `Fishing.NoFishZones` table as stock does (every square a no-fish zone). Same keys, same
values: `tests/pzopt/FishNoiseWalkTest.java` compares it with the stock loop over 420 walks. The
helicopter's moving 500-radius sound goes 0.27 → 0.08 ms per call.

Decompiler fix (2026-09-23, found by `scripts/bytecode-audit.py` on its first run): `procedureRandomFloat`, the
per-point fish abundance hash, divides `t % 2^30` by `5.36870912E8` in double in the jar; Vineflower rendered
it as a float divide (`/ 5.368709E8F`), so the fish count of some points differed slightly from the game's
(and between a client with the mod and a server without it). Restored to the jar's
`(float)(((double)(t % 1073741824L) / 5.36870912E8 + 2.0) / 4.0)`, marked on the line.

## zombie.core.skinnedmodel.animation.AnimationPlayer (added 2026-09-22 night, zombie bone math on the other cores)

Louisville horde profile (`lou-budget`, game thread 98 % busy): the zombies' postupdate is 17 % of the
game thread and 9 % of that is `updateModelSlot`, i.e. `AnimationPlayer.Update` blending every live
track's keyframes into the bone transforms, the body-angle steps, the twist bones, the model-space and
skin matrices. That math only touches the player's own arrays, the read-only clips, the thread-safe
pools (`Pool`, `ObjectPool`, `HelperFunctions`' locked matrix stack) and a set of static scratch
objects, so it can run on any thread once the scratch is per thread.

Edits: the seven static scratch holders (`L_applyTwistBone`, `L_getBoneModelTransform`,
`L_getTrackTransform`, `L_getUnweightedBoneTransform`, `L_getUnweightedModelTransform`,
`L_updateBoneAnimationTransform`, the deferred-movement bone-index array) hold instance fields now,
one instance per thread through a `ThreadLocal`, and every use goes through it; the static `tempo`
vector is a `ThreadLocal` too. In `updateInternal`, after the multi-track tick (which fires the
animation events and stays on the game thread) and the non-visual / shared-skeleton branches, the
standard-animation branch first offers the player to `pzopt.AnimBatch.submit`; when accepted the
method returns and the batch calls the new `pzoptRunDeferred(deltaT)` (the standard animation plus
`postUpdateRagdoll`) later. `pzoptBatchable()` says no for a child player (copies its parent's
bones), a ragdoll or a recording player. Key `animBonesParallel` (true); `animBonesThreads` (8)
worker threads, the game thread joins in. Nothing changes in the order of a single player's work,
only where the second half runs.

`isBoneReparented(boneIdx)` is a plain loop over the reparented-bone list instead of
`PZArrayUtil.contains` with a pooled `Lambda.predicate`: stock allocated and released one pooled
predicate per bone per character per frame (~30k a frame on the horde), and every pool alloc /
release bumps shared atomic statistics counters, which the batch's worker threads all contended on
(5 % of the game thread waiting inside `PooledObject.release` in run `lou-rec-fix`). Same answer,
no allocation, inline or batched.

## zombie.MovingObjectUpdateScheduler (added 2026-09-22 night, zombie bone math on the other cores)

`postupdate()`: `pzopt.AnimBatch.begin()` before the bucket loop and `flush()` after it (in a
`finally`), client side only. The batch runs after the loop rather than overlapping it because
`IsoGameCharacter.updateAnimPlayer` (the model-less path, most of a horde) flips
`PerformanceSettings.interpolateAnims` around each call and the keyframe sampling reads that flag; the
join is before anything reads a bone (attachments, the render data). Zombies being grappled or
grappling, or that reanimated a dead player, are refused by the batch (the other side reads their
bones in the same loop) and run inline. Counters on the periodic FBORenderCell log line:
`anim batch: frames= batched= inline= max= work ms= wait ms=`; a failure inside a deferred update is
logged once and turns the batch off for the rest of the session.

## zombie.characters.IsoZombie (added 2026-09-22 night, vehicle cull for the line-of-sight test)

`isVehicleBetween`: for every loaded vehicle stock transforms the zombie-to-target segment into the
vehicle's local space (two matrix multiplies, three pooled vectors) and runs the exact box test — per
zombie that could see the player, per frame. Downtown Louisville has hundreds of parked cars, so
`BaseVehicle.getIntersectPoint` was 6 % of the game thread. Now `pzopt.VehicleCull.mayIntersect`
runs first: the vehicle's bounding circle (half the horizontal diagonal of its script extents plus the
centre-of-mass offset plus a 1-tile margin (getX/getY follow the physics origin a tick behind)) against the segment's nearest point; a miss skips the
exact test, a hit runs it unchanged. Key `vehicleCull` (true); a vehicle without a script always runs
the exact test.

## zombie.iso.fboRenderChunk.FBORenderCell + IsoChunk + MultiTextureFBO2 (edit of 2026-09-22, chunk textures across zoom changes)

Stock frees a chunk level's textures the frame the level leaves the screen (`checkNewlyOnScreenChunks`,
`renderOneLevel`: `freeFBOsForLevel`) and creates them again, dirty, when it returns. Zooming in
shrinks the screen, so it frees most of what was visible; zooming back out bakes every level that
reappears in the frame it appears, and the bake budget never caught those: `DIRTY_CREATE` is only set
by `createFBOForLevel`, inside `beginRenderChunkLevel`, after the deferral decision, so a level with
no texture yet always baked at once. On the south route at 5120x2160 a 0.25 → 2.5 wheel spin was 320-410
bakes in one frame, 77-109 ms, then a 375 ms frame (the render thread allocating the textures); one notch
out at wide zoom 45-51 ms frames (stock runs `zs-out-jump9`, `zs-out-wheel`; rig `--flag zoom_cycle=`
`zoom_span=` `zoom_jump=`, `harness/zoomsteps.py`).

Now (`pzopt.ZoomRetain`, keys `zoomRetain` true, `zoomRebakeBudget` 12, `zoomFrameMs` 10, `zoomPlaceholder`):

- Off-screen levels go through `ZoomRetain.releaseOffScreen`: the textures stay while the chunk lies
  inside the screen rectangle the widest zoom would show (centred on the camera character like
  `PlayerCamera.center`, one chunk of margin; the high-res texture, a debug option, inside the widest
  zoom below 0.75), else they are freed as before. That is the set stock holds at the widest zoom, so
  the texture memory stays within stock's own maximum. Chunk unload still frees everything.
- A level coming back on screen that was seen before (`prevMinZ` set) gets a bit in
  `IsoChunk.pzoptZoomReturned[player]` (cleared in `removeFromWorld`); stock's `invalidateLevel(1024)`
  stays. In `renderOneLevel` such a level, and every first-sight level while a zoom flood lasts (the zoom
  changed this frame or zoom work was deferred last frame), bakes only when the frame's plan allows it;
  otherwise it is held with its kept texture on screen (the stale path), its other-scale texture, or
  nothing, and returns. Object / item / obscuring dirt still bakes at once; lighting, redraw, tree and
  cutaway dirt is held.
- The plan (`pzoptZoomPlan`, once per player per frame before the chunk loop): the pending levels of
  the loaded grid sorted by their chunk's distance to the camera character, the first N allowed
  (`IsoChunk.pzoptZoomAllowed`), so the picture fills from the player outwards and the cutaway-relevant
  chunks land first. N follows the last game-thread frame: over `zoomFrameMs` it halves (a fresh
  chunk texture costs the render thread ~1 ms of GL allocation on top of the bake, which shows up as a
  hand-off wait), under 3/4 of it grows by two, within [4, `zoomRebakeBudget`]. Credits the plan did
  not give to a pending level go to first-sight flood levels in chunk order; in the frame the zoom
  changes nothing new starts at all (the returned set is only known after that frame's on-screen scan).
- `MultiTextureFBO2.pzoptWidestZoomBelow(limit)`: the widest selectable zoom under a limit (the high-res
  rectangle).
- Two faults of the first build (`3441a1c`, found by the parity watch on the Louisville walk the same
  night): a level re-entering the screen by camera motion carried its kept texture into the ordinary re-bake
  hold (`rebakeBudget`, up to 3 frames of the stale texture, read as roof flicker), and a returned level that
  was then occlusion-culled or freed kept its pending bit, which the plan took for a running zoom flood, so
  every first-sight chunk level was budgeted for the rest of the session (black chunk levels downtown). Now a
  camera-motion return is allowed in the frame it appears (the texture is redrawn before it is shown, as
  stock's fresh one was), the bits clear on the occlusion and off-screen paths, and only an actual zoom
  change or a real deferral keeps the flood on.
- A Workshop player reported the first build's stall as it looks in play (2026-09-22): chunk textures stop
  appearing past a fixed radius and waiting never fills the rest; `zoomRetain` off cures it. Two guards on top
  of the previous fix, in the second build: the plan now honours the on-screen scan's "allowed at once" marks
  outside a flood without charging them to the budget (`checkNewlyOnScreenChunks` runs before `pzoptZoomPlan`,
  which used to zero every allowed bit, so the camera-motion returns of the previous fix still competed
  nearest-first inside the 4-12 budget; after a slow frame a pan over seen ground drew stale textures again),
  and `pzoptZoomSettle`, after the chunk loop, drops every allowed bit still pending: the level never reached
  the gate (chunk skipped while its lighting is not done, level index not visited after a `minLevel` change,
  clean level with a texture), so a bit no path clears can take at most one credit and the plan can never
  starve again. Counter `dropped=` in the periodic `zoom kept=` log line; 0 on the bench route.

Results (240 cap, south route, `zoomsteps.py --window 1.0`): 0.25 ↔ 2.5 instant jumps, worst frame per
jump 375 / 86 / 59 / 52 ms (stock) → see `docs/archive/2026-09-24/results.md` for the adopted build's numbers.

## zombie.characters.IsoPlayer (added 2026-09-22, player line-of-sight pass)

Loose copy with three edits behind `pzopt.Config.PLAYER_LOS_FAST` (`playerLosFast`, true), plus the
class-loaded marker. Profile: on the Louisville preset (2,433 zombies loaded, spectator view) the
player was 16 % of the game thread and `updateLOS` 15 of it, 12 in its own body.

- `updateLOS`: stock keeps every object the player spotted since the last quiet frame in the
  `lastSpotted` Stack and asks it `contains` once per object spotted this frame, plus once more per
  zombie within a few tiles: a linear walk of a synchronized Vector. The list only empties on a frame
  that spots no zombie at all, so in a horde it holds every zombie ever seen and the walk is spotted ×
  remembered identity compares a frame. The override keeps an identity set beside the stack
  (`pzopt.PlayerLos`): `sync` at the top of the pass rebuilds the set whenever the stack object or its
  size is not what the set last mirrored (a mod adding through `getLastSpotted` or replacing it through
  `setLastSpotted` is picked up), the two `contains` become one probe each, the end-of-pass add goes
  through the set (add unless remembered, then push), and the periodic clear empties both. The stack
  keeps exactly the stock contents and order. The end-of-pass loop also reads each spotted object once
  instead of three synchronized `get`s.
- `getSneakSpotMod` (new override of the `IsoGameCharacter` method): every zombie that could see the
  player asks for it in `spottedNew`, and stock walks the perk list for the sneak level each time; the
  answer cannot change between two zombies of one frame, so it is memoised per `IsoWorld` frame number
  (a level gained mid-frame shows to the next frame's zombies).

## zombie.characters.IsoZombie (second edit, 2026-09-22, spot roll early-out and nearby vehicles)

- `spottedNew`, behind `pzopt.Config.ZOMBIE_SPOT_FAST` (`zombieSpotFast`, true): the vision-radius
  update moves above the look-vector / facing block (neither depends on the other), and a chance that is
  already zero (the player beyond this zombie's vision radius, or in the dark) skips the look-vector
  trig, since the facing block only scales the chance. After the facing block a zero chance (not
  forced) skips every remaining modifier (movement, sneak, traits, shelter, the vehicle test, worn
  items, the pow and the roll): each one multiplies or divides the chance, so the roll could not
  succeed, and the code goes straight to the one early exit a zero chance still takes (a nearer current
  target) and to the failed-roll bookkeeping, which is unchanged: the could-be-seen flag needs a chance
  above 20, and the sneak / lightfoot XP rolls never read the chance. The only difference to stock is
  one fewer `Rand.Next(10000)` draw from the shared generator per skipped zombie.
- `isVehicleBetween`, under the existing `vehicleCull`: instead of walking every loaded vehicle, the
  walk covers `pzopt.VehicleCull.near(...)`: the per-frame list of the vehicles whose bounding circle
  reaches the disc around the target (the player) of radius max(view distance, this zombie's distance)
  — every zombie asking in a frame asks about the same player position, and `spottedNew` only asks
  within the view distance, so the list is built once per player per frame (rebuilt on a new frame, a
  moved target or a larger reach). A vehicle without a script is always a candidate. `nearBuilds()`
  counts the builds.

## zombie.iso.LightingJNI (2026-09-22, dead end: clean squares asked once per frame — removed)

Tried and removed the same night: a per-square stamp in `JNILighting.updateFBORenderChunk` so a square
the native called "not dirty" was not asked `getSquareDirty` again in the same frame under the same
`updateCounter`. A dev rig re-asked on every skip: 0 misses in 1,500 frames (the stamp was sound), but
only 5,636 skips against 19.1 M dirty answers and 173 k clean ones — the native reports nearly every
visible square dirty on nearly every frame, so the whole on-screen set is re-fetched each frame
(`getSquareDirty` 4 % + `getSquareLighting` 3 % of the game thread on the Louisville preset). That is
the shape of the "lighting jni" cost; a per-frame memo cannot touch it. Also learnt: `getChunkDirty`
is only legal between `stateBeginUpdate` / `stateEndUpdate` (it throws `missing stateEndUpdate?`
elsewhere), so a chunk-level gate cannot live in the square accessors either (run `lou-los4`: the
exception on every frame left the zombies invisible and a meaningless 91 fps).

## zombie.core.skinnedmodel.animation.AnimationPlayer (third edit, 2026-09-22, shadow ellipse and palette on the worker)

- `updateInternal` clears a "shadow valid" flag at its start; `pzoptRunDeferred` ends (after the skin transforms)
  with `pzoptPrecomputeShadow` behind `pzopt.Config.SHADOW_PREP` (`shadowPrep`, true): the head and both feet bone
  indices (through the cached `getSkinningBoneIndex`), then `pzopt.ShadowPrep.compute` — stock's
  `IsoGameCharacter.calculateShadowParams(player, 1, false, sp)` arithmetic step for step with thread-local scratch
  (the stock static `L_renderShadow` holder cannot be shared by workers), packed into a long on the player and the
  flag set. `pzoptShadowParams()` returns it while the flag holds (0 otherwise). Out-of-range bone indices leave the
  flag clear (stock path). Counters `shadow computed= served= fallback=` on the `anim batch:` log line.
- `SkinTransformData` carries a `FloatBuffer` palette and a valid flag (`skinPalettePrecompute`, true, needs
  `skinTransformsPrecompute`): `pzoptPrecomputeSkinTransforms` stores each set it computed into the set's own buffer
  (`Matrix4f.store` per bone, the shader's column order, flipped); `getSkinTransforms` clears the flag whenever it
  recomputes a dirty set; `pzoptSkinPalette(skinnedTo)` (game thread) returns the buffer, rewound, when the set is
  clean and its palette valid, else null.

## zombie.core.skinnedmodel.advancedanimation.AnimatedModel (added 2026-09-22, palette hand-off)

Inner class `AnimatedModelInstanceRenderData.initMatrixPalette`: with `skinPalettePrecompute` the draw data asks the
player for the precomputed palette of the model's skinning data and, when it gets one, sizes its own buffer to it and
copies it in a single bulk `put` (then flips and marks the palette valid, as stock); otherwise the stock loop
(`getSkinTransforms`, sixteen puts per bone) runs. Stock's `init()` on the render thread and the shader upload read
the draw data's buffer as before.

## zombie.characters.IsoZombie (fourth edit, 2026-09-22, shadow from the worker, thread-local facing test)

- `calculateShadowParams(ShadowParams)` override (`shadowPrep`): when the animation player holds a pair from its last
  deferred update and is ready, `sp.set(0.45, fm, bm)` from it (`served++`); else the inherited computation
  (`fallback++`). Sits in the tail block with the cached-component accessors.
- `isFacingTarget` (the `isFacingTarget` animation variable, read by transition conditions that now evaluate on the
  frame workers): the two vectors are a thread-local pair instead of the inherited static `tempo` / `tempo2`; same
  arithmetic.

## zombie.characters.IsoGameCharacter (added 2026-09-22, postUpdateAnimating split for the parallel transition evaluation)

`postUpdateAnimating` (private) is split at the point after `setTurningAround`: everything from
`getActionContext().update()` to the end (network AI post-update, the animator update, the three
`ActiveAnim*` event clears, `applyDeltas`, `updateAnimPlayer` or `updateModelSlot`, `updateLightInfo`, the
animation recorder, the finishing event) moved verbatim into a new public `pzoptPostUpdateAnimatingRest()`. The
original method computes the forward direction, the vertical aim angle and the three turning flags as before, then
asks `pzopt.ActionEval.submit(this)`: true (an eligible zombie inside a batch) returns at once, the rest runs from
`ActionEval.flush()` in loop order after the parallel evaluation; false calls `pzoptPostUpdateAnimatingRest()`
directly, i.e. the stock sequence. Note for the regen: CFR renders the model-less / model branch of this method
wrongly (as two sequential blocks); the bytecode and Vineflower have `if (!hasActiveModel()) updateAnimPlayer else
updateModelSlot`.

Decompiler fix (2026-09-22): Vineflower drops the `(IsoObject)` cast in `CanSee(IsoMovingObject obj)`, so its
body `return this.CanSee(obj);` called itself and threw a StackOverflowError on the first call. It is used by
`IsoAnimal`, `DeviceData` and Lua. The cast is back in place. A scan of every override for a method whose whole
body calls a same-named method with the same arguments found no other case; check for this after a regen.

## zombie.characters.action.ActionContext (added 2026-09-22, evaluate on a worker, apply on the game thread)

`actionEvalParallel` (`pzopt.ActionEval`). `updateInternal` is stock's set / evaluate / transfer, with a first check:
when this context was evaluated by the current batch (a generation stamp) and the batch is being applied, only the
transfer runs. New methods: `pzoptEvaluate()` (worker: set + evaluate into the "next" container, stamp the
generation), `pzoptOffThreadSafe()` (the root state and every sub-state have only `CharacterVariableCondition`,
`EventOccurred` and `EventNotOccurred` conditions — anything else, i.e. `LuaCall`, keeps the zombie inline; cached
per `ActionState` in an identity map, game thread only; reads `ActionTransition.conditions`, package-private, which
is why the check lives here). With `devActionEvalCheck=true` the apply step first evaluates again on the game thread
into a scratch container and counts a mismatch when the two containers differ (`ActionEval.checks / mismatches`,
first 20 logged with the state names): the determinism rig of this phase.

## zombie.MovingObjectUpdateScheduler (third edit, 2026-09-22, two-phase postupdate)

`postupdate()` begins both batches (`ActionEval.begin()` after `AnimBatch.begin()`), runs the bucket loop, then in
the finally `ActionEval.flush()` (the parallel evaluation, then every queued zombie's
`pzoptPostUpdateAnimatingRest()` in order, which is where their bone math gets queued) and last `AnimBatch.flush()`.
Log line `action eval:` next to `anim batch:` (FBORenderCell).

Fix of 2026-09-22 night (off-screen thump bursts, a player report): every bucket sets
`GameTime.perObjectMultiplier` to its frame mod while its zombies update / postupdate and resets it to 1 afterwards,
so the deferred `pzoptPostUpdateAnimatingRest()` calls in `flush()` ran at 1. An off-screen zombie runs at its
state's minimum simulation level (SIXTEENTH: one update every 16 frames): `ThumpState.execute` counted the thump
events between the track time and the track time + 16 frames of animation, while the deferred animation advanced
the track by one frame, so the next update counted the same strikes again, up to 16 times (bursts of 8 / 16 thumps
and 16x door damage). `flush()` now sets the multiplier to each zombie's `getCurrentSimulationLevel().getFrameMod()`
around its deferred call and restores the previous value in a finally, as stock's in-loop call saw it. Dev key
`devActionEvalUnitMultiplier=true` restores the bug for A/Bs; rig `pzopt.ThumpRig` (harness flag `thump=N`).

## pzopt.FrameBatch (2026-09-22)

The worker pool both batches share (`frameThreads`, default 8 = the old `animBonesThreads`, clamped to cores - 1):
`run(count, runner)` executes indices 0..count-1 on the daemon workers `pzopt-frame-N` plus the calling thread and
returns when all finished, with the first exception a task threw. `AnimBatch` lost its own pool and threads.

## zombie.core.textures.MultiTextureFBO2 (edit of 2026-09-22, zoom motion as a cubic Bézier)

`update()`: stock moves the zoom towards the target by a fixed 0.03 per frame (0.004 × 1.5 × 5 for a manual
change; auto-zoom without the ×5) and snaps onto it, so a wheel notch takes 8 frames whatever the frame rate
(16 ms at 500 fps, 130 ms at 60), at constant speed with an abrupt stop. With `zoomEaseMs` > 0 (300) a
manual change (`autoZoom` off for the player) takes `pzoptEase`: the first frame that sees a new target
(any entry point: `doZoomScroll`, `setTargetZoom`, `setZoomAndTargetZoom`) records the current zoom and the
time; every frame after that sets `zoom = from + (target − from) × curve(elapsed / zoomEaseMs)` with
`pzopt.ZoomEase`, a CSS-style cubic Bézier through (0,0) and (1,1) with control points from `zoomEase`
(x1,y1,x2,y2; default 0.25,0.1,0.25,1.0 = CSS "ease"; the solver is Newton steps on x(t) with a bisection
fallback, `tests/pzopt/ZoomEaseTest`). A new target during the motion restarts the curve from the current
zoom, so nothing jumps; `dirtyRecalcGridStackTime` is set while it moves as stock does. `zoomEaseMs=0` is
the stock step. Auto-zoom keeps the stock step (it retargets every frame with a distance term, a restarted
curve would never leave its slow start).

## zombie.characters.IsoPlayer (second edit, 2026-09-22, experiment: the LOS pass in C++)

Asked for after the player pass: the same logic rewritten in the lighting engine's language. Key
`pzopt.Config.PLAYER_LOS_NATIVE` (`playerLosNative`, **false**, experiment). `pzoptUpdateLosNative` is
the stock loop split in three: Java packs every object that passes the cheap filters (position, the
could-see / can-see bits read from its square's `JNILighting`), one call into
`natives/libpzopt_los64.so` (`src/native/pzopt_los.cpp`, `pzopt_los_pass`: the distance with
`IsoUtils.DistanceTo`'s float semantics, the "close" count and the branch per object) through the FFM
linker with `Linker.Option.critical(true)` (heap arrays read in place, no JNI copy; `pzopt.NativeLos`),
then Java applies the side effects in stock order (alpha targets, spot tests, stats, the spotted list).
The library is only built with `PZOPT_NATIVE=1 scripts/build.sh` and never ships; with the key on and
the library missing the Java loop runs.

Result: no difference. Micro-benchmark over 2,433 objects on the same JIT: Java 3.6 µs a pass, C++
through one FFM call 3.7 µs (C2 emits the same `sqrtss`; a whole pass is ~0.01 % of a 30 ms frame);
per-object native calls 102 µs a frame (42 ns a crossing), thirty times worse. In game, an isolated
pair from a worktree of HEAD + this pass only (`lou-losn4-on` / `-off`, 05:12 / 05:16, library
confirmed loaded): player 3.86 % vs 3.52 % of the game thread, 1.01 vs 0.99 ms a frame in absolute
terms, `NativeLos.run` one sample in 2,600, the loop's own self time 0.19 vs 0.27 % (two samples);
the 38 vs 35 fps gap is the route's run-to-run noise (two Java-only runs the same hour were 53 and 60). What is left of the player's cost is reads of Java object state (visibility bits,
zombie state, rooms) and the spot tests' side effects, none of which a native pass can take over
without Java packing it first — the packing loop is the loop. Everything worth moving out of Java was
the algorithm (the `lastSpotted` walk), not the language.

## zombie.iso.LightingJNI (fifth edit, 2026-09-22, square reads on the frame workers)

`lightingReadParallel` (`pzopt.LightingBatch`). In `JNILighting`: the static `lightInts` scratch the native fills
became a thread-local array (`pzoptLightInts`, both `update` paths take a local from it); the room-seen block at the
end of `updateFBORenderChunk` (`checkRoomSeen`, then `Meta.dealWithSquareSeen` for a square seen for the first
time) asks `LightingBatch.current()` first: on a worker the square is recorded in the task's effects list (only
when the hooks would do something: first time seen, or its room unexplored) and the game thread runs the hooks after
the batch; on the game thread the block runs as stock. New `pzoptRecheck()` (dev): asks `getSquareLighting` again
and compares with the stored visibility bits (bit 2 excluded: the facing rule may force it), light colour, dark
multipliers, light level and vertex lights. Everything else the read does — the square's fields, the level's
`invalidateLevel`, `LightDirt.markStrong`, `PuddleCache.lightsChanged`, `FBORenderCutaways.squareChanged` — is per
square, per chunk level or an idempotent write, and the natives are pure reads (disassembly of libLighting64.so:
`getSquareDirty` is an index computation and a byte load; `getSquareLighting` reads the lighting arrays and copies
into the Java array), so per-level tasks do not race.

## zombie.iso.LightingJNI (sixth edit, 2026-10-04, lamp ids apart from the torches)

`lampIdsApart` (default on). `checkLights` gives a new `IsoLightSource` the id `1048576 + IsoLightSource.nextId++`
instead of `nextId++`. The native keeps one entry per id in a square's light list (`getSquareLighting`): stock numbers
lamps 1, 2, 3, ... and the local players' handheld torches `playerIndex * 4 + i + 1` (remote players' `(onlineId + 1)
* 4 + i + 1`), so lamp 1 and player 1's first torch shared id 1 and on every square both reached only one of them was
listed (four dumps of the 2026-10-04 report save: ~700 squares with lamp 1, ~150 with the torch, never both). pixelLight
takes the listed torch out of a square's light and draws its own; where lamp 1 had replaced the entry the native's
torch stayed in the base and the per-pixel torch went on top: half the cone lit twice, read by the player as a second
cone (rotating torch rig: base >= 0.9 on 61 cone squares -> 7). Stock's model lighting (`ModelInstance` frame lights,
matched by id) mixed the two lights up the same way. The new range is clear of the torches (1..4095, the
`rl.id < 4096` test in PixelLight), the vehicle lights (`4096 + vehicleId * 10 + light`) and the room lights
(`100000 + n`); torch ids are unchanged (stock's own-torch skip in `ModelInstance` needs them). Lamp ids are runtime
only (not saved, not sent). Rig: `--flag torch=on --flag face=90 --prop devPplDumpAt=10,14`, `--prop lampIdsApart=false`
for the old ids.

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-22, the pre-pass lighting drain on the frame workers)

`pzoptFlushPendingLighting` hands over to `pzoptFlushPendingLightingParallel` when `lightingReadParallel` is on: the
on-screen (chunk, level) pairs of the pending queue are collected into two arrays while the game thread creates
what a level's read would create lazily (`getRenderLevels(player)`, `getCutawayDataForLevel(z)`, the
once-per-frame stamp row through the new `pzoptTouchLightInfoRow`); one level alone reads inline, otherwise
`FrameBatch.run` executes `pzoptCacheChunkLevelLightInfo` per level on the workers and this thread with the task's
effects sink installed (`LightingBatch.withEffects`), then `LightingBatch.applyAll` runs the deferred room / meta
hooks in level order. With `devLightingReadCheck` one square in sixteen of every level is re-read on the game
thread through `JNILighting.pzoptRecheck` and mismatches counted. The `lightingBudget` path of the render phase
(`updateChunkLighting`) stays serial. Log line `lighting batch:` next to `action eval:`; the harness summary line
`zombie_batches=` in `pzopt-bench.out` carries all batch counters at route end.

## zombie.core.textures.TextureDraw (added 2026-09-22, characters draw pre-pass)

`drawModel(TextureDraw, ModelSlot)`: after the type and slot id are set, the method asks `pzopt.CharDraw.take(slot)`
for draw data the frame workers already built and initialised for this slot (`charDrawPrep`); when it gets some,
that object becomes the sprite's drawer, the future stays null (nothing left for the render thread to wait on) and
the method returns. Otherwise the stock path runs unchanged: allocate, `initModel`, and `init` on the game's own
eight-thread executor (the `Threading.ModelSlotInit` debug option, on by default) with the future the render thread
waits on. The class also carries the load marker.

## zombie.core.skinnedmodel.model.ModelInstance (added 2026-09-22, characters draw pre-pass)

- `updateLights()`: returns at once when `pzopt.CharDraw.lightsDone(this)` says the pre-pass already ran it for the
  current frame and player (a public int stamp field on the instance, set right after that call). Otherwise stock:
  allocate the per-player data, run its update. Without the pre-pass the stamp never matches. The pre-pass runs it
  on its worker, after `CharDraw.start` has performed, on the game thread, the lazy per-square refresh the method's
  reads sit behind (`square.lighting[p].lightInfo()` for the character's square and the one above it on stairs —
  `JNILighting.update` with its JNI reads and dirty-tracking hooks; stock's `renderShadow` refreshes the same
  square just before the stock call); after that every read in the method is a plain field read.
- Inner class `PlayerData.updateLights`: the two static `ColorInfo` scratch objects the ambient interpolation wrote
  through are a thread-local pair now, and the two `IsoGridSquare.interpolateLight` calls (which write through the
  square class's static `Color` scratch) go to the new `pzoptInterpolateLight`: the same four `getVertLight` reads,
  the same `Color.abgrToColor` conversions and the same three `interp` lerps into the given `ColorInfo`, with seven
  thread-local `Color` objects. The arithmetic, the order of the reads and the smoothing steps are stock. The
  static fields stay declared and unused.

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-22, characters draw pre-pass)

- `renderInternal`, right after the player index is read: with `charDrawPrep` on, `pzopt.CharDraw.walk(objectList,
  this)` hands the walk of the cell's object set (stock iteration order; the on-screen objects, and the zombies that
  pass the model tests independent of this frame's cutaway / lighting passes) to one task of the pass's pool.
- `renderTilesInternal`, after `checkBlackedOutRooms` and before `performRenderTiles` (the cutaway checks, the
  lighting refresh and the blacked-out passes are done, i.e. everything the draw data reads): `pzopt.CharDraw.start`
  takes the walk's result, applies the square light-info / cutaway visibility test on the game thread, runs
  `checkUpdateModelTextures` and the lazy lighting refresh of the zombie's square (and the one above it on stairs)
  for the zombies kept, and hands them to the pass's own pool (`charDrawThreads`) chunk by chunk — `updateLights`
  (plain reads after that refresh), `initModel`, `init`, the camera record; the chunk bakes, the composite, the players, the corpse shadows, world items and
  puddles that follow overlap that work.
- `renderMovingObjects`: `pzopt.CharDraw.join(...)` waits for the tail of those tasks (or runs the whole pre-pass when
  `start` did not) and returns the on-screen list; the loop then runs the stock `renderMovingObject` over that list
  only, and `CharDraw.finish()` releases anything prepared and not drawn. A null (the pass failed this frame) or
  the key off = the stock loop over the whole set.
- New `pzoptShouldRenderSquare(square)`: the private `shouldRenderSquare` for the pre-pass's predicate.
- `renderMovingObject`: the `renderShadow` call is skipped for a scene-culled character without an active model that
  is not a fake-dead zombie (`pzoptShadowIsNoOp`): since the zombie session's reorder that call returns before
  drawing anything for exactly that case, and every earlier test in it returns too, so nothing changes; ~1,100 of
  the 1,600 on-screen objects of the horde are such zombies.
- The periodic log line gets `char draw:` (frames, on-screen objects per frame out of the walked set, batched per
  frame, max, taken, leftovers, walk ms on the worker, walk waits / ms and start ms on the game thread, join waits / ms).

## zombie.characters.action.ActionContext + conditions.CharacterVariableCondition (second edit, 2026-09-22, the callback snapshot)

The first parallel build ran every transition condition on the workers and tripped on the variables whose callback
is not a read: `blunge` runs `PolygonalMap2.lineClearCollide` (whose `LineClearCollideMain` keeps a plain
`ArrayDeque` point pool — corrupted from two threads, then `NoSuchElementException` / `NullPointerException` in
every later line test on the game thread, wrong lunge / attack answers and, in the parity session's run, the
god-mode ghost dying), `bHasTarget` clears the target, `bthump` drops the thump target, `beatbodytarget` scans the
corpses nearby, `turndirection` uses the inherited static vectors. So, behind the same `actionEvalParallel`:

- `CharacterVariableCondition` exposes its two lookups (`pzoptLhsLookup` / `pzoptRhsLookup`) and a static
  `pzoptSnapshotValue(lookup, owner)`: null when the owner's slot for it is a stored value or a callback whose key
  is in `pzopt.ActionEval.PURE_CALLBACKS` (the audited pure reads: field getters, the facing test, `canRagdoll`,
  the animation angles...), else the typed value read now (a `NULL` marker for null). The classification is
  cached on the lookup (`pzoptPure`). `CharacterVariableLookup.pzoptGetValue` first consults
  `ActionEval.currentSnapshot()` (a thread-local `IdentityHashMap<lookup, value>`) and returns the snapshot value
  when present.
- `ActionContext` keeps one snapshot map; `pzoptSnapshot()` (game thread, called by `ActionEval.submit`, i.e. at the
  point in the zombie's postupdate where stock would have evaluated) reads every non-pure variable of the current
  state's and sub-states' conditions into it — their side effects happen there, in order, as in stock; a callback
  stock would have short-circuited past runs once more than stock. The per-state cache (`pzoptStateLookups`)
  holds the lookup array of a safe state or an UNSAFE marker. `pzoptEvaluate()` installs the map for the worker
  evaluation, and the `devActionEvalCheck` re-evaluation installs it too.

## zombie.characters.IsoGameCharacter (second edit, 2026-09-22, shadow params only where drawn; ragdoll test order)

Both pure reorders, suggested by the characters-draw session from the Louisville profile:
- `renderShadow`: stock computed `calculateShadowParams` (three bone projections, three nearest-point tests) before
  the branch that returns for a scene-culled character, i.e. for the ~1,100 culled zombies of a 1,600-object horde
  frame whose shadow is never drawn (2.5 % of the game thread). The culled return is tested first (same condition:
  no model path, not a fake-dead zombie, scene-culled) and the params are computed after it. Same numbers on every
  drawn shadow.
- `render`: `getRagdollController() != null && canRagdoll()` instead of the reverse (`canRagdoll` walks the
  state per zombie per frame; both are pure reads).

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-22, adaptive strong re-bake budget)

`pzoptStrongNow` takes its per-frame budget from `pzoptStrongBudget()`: with `pzopt.Config.LIGHTING_STRONG_FRAME_MS`
(`lightingStrongFrameMs`, default 0 = the fixed `lightingStrongBudget` since 06:45; 20 in the runs of 06:00-06:40) the budget follows the previous game-thread
frame step (`FrameCap.lastStepNs`, the limiter's wait excluded) — over the threshold it halves (down to 1), under
three quarters of it grows by one, up to `lightingStrongBudget`. Why: the out-of-sight fade (`darkMulti`) moves per
unit of game time, so a slow frame moves every exterior square further, marks more levels strong, bakes more chunk
textures (GPU work) and slows the next frame; on the Louisville preset about a third of the 2026-09-22 runs sat in
that loop at 27-30 fps with the game's own GPU time doubled (30 vs 13-14 ms per frame, GPU 82 %, the parity
session's per-process log showed no other GPU client). Held strong levels still re-bake within `lightingRebakeMs`
or the spread. Counter `strongBudgetCuts=` in the harness summary's `bake_counters=` line (the bake / re-bake
counters since boot, new in `pzopt-bench.out`, via `pzoptBakeCounters()`). Defaulted off after the parity session's
reading of those counters: on a scene that is steadily slow (30-45 fps downtown) every frame exceeds 20 ms, the budget
sits at 1, the held strong squares are re-marked every pass (strong marks 25x in the slow recorded runs) and the
screen shows stale light — the loop it was meant to break turned out to be the zoom session's stale pending bits
(`build-zoom-leak-fix`), not the strong re-bakes. Kept as an A/B key; a spike-relative rule (cut only on a frame well
above the recent average) would be the version worth trying.

## zombie.iso.IsoWorld (added 2026-09-22, the zombie relevance sort)

`sceneCullZombies` sorts the zombies with a model by their relevance to the players before handing out the model /
animation tiers; stock's comparator recomputes both zombies' scores on every comparison (n log n × 2 score walks,
1.4 % of the game thread in a Louisville horde). Behind `pzopt.Config.ZOMBIE_CULL_SORT_FAST` (`zombieCullSortFast`,
true) `pzoptSortZombiesByScore` computes each score once, packs it with the element index into a long
(`pzopt.SortKeys.descending`: the negated sortable float bits in the high word, the index in the low word, so a signed long sort gives score descending, index ascending on ties; -0 folded into +0) and sorts the keys as
primitives, then permutes the list's backing array through a scratch array — the exact order of stock's stable
sort under its comparator (`tests/pzopt/SortKeysTest`). The rest of the method is stock.

## Upscaling pass (added 2026-09-22; `upscaler`, `upscalerQuality`, `upscalerScalePct`, `fsrSharpnessPct`, dlss keys; docs/plan-upscalers.md)

With `upscaler` other than `off` the world pass renders at `upscalerQuality`'s fraction of the screen and
`pzopt.Upscaler` resolves it to the screen size before the stock screen shader; the UI, world text, cursor and
that shader stay at full resolution. `pzopt.RenderScale` holds the scale and the render-thread state. With the
key off every hook below is a no-op.

### zombie.core.textures.TextureDraw (edit of 2026-09-22, upscaling)

`run()`: after the stock `DoStartFrameStuff` (case `glDoStartFrame`) and `DoStartFrameNoZoom`
(`glDoStartFrameNoZoom`) `RenderScale.afterStartFrame(player)` runs; when the frame was started with a player
index and the world framebuffer is bound it replaces the viewport and scissor with the scaled player rectangle
(with the current sub-pixel jitter as a float viewport offset) and marks the render thread "in the world pass".
After `DoEndFrameStuff` (`glDoEndFrame`) the mark is cleared. Case `glViewport` goes through
`RenderScale.requestedViewport`: inside the scaled world pass a rectangle equal to a player's screen rectangle or
the whole screen (IsoWorld's view-cone restore) is scaled the same way; any other rectangle (the FX mask, the cone
texture) is set as requested.

### zombie.iso.IsoCamera (added 2026-09-22, upscaling)

`getScreenLeft/Top/Width/Height` and `getOffscreenLeft/Top`: on the render thread, while the world framebuffer
is bound inside a scaled world pass (`RenderScale.scaledView()`), they return the scaled rectangle. That covers
the viewport restores and uniforms of `ModelOutlines` and `VisibilityPolygon2` (`screenSize`, `displayOrigin`)
and the water / puddle viewport origin without touching those classes. The game thread always gets the stock
values (culling, chunk work, UI, mouse).

### zombie.iso.WaterShader and zombie.iso.PuddlesShader (added 2026-09-22, upscaling)

`startRenderThread`: the `WViewport` size (stock `camera.offscreenWidth / camera.zoom`, the screen size) goes
through `RenderScale.viewPx`, i.e. it is scaled inside the scaled world pass, since the shaders map
`gl_FragCoord` through it for the sky reflection and the noise.

### zombie.core.skinnedmodel.ModelManager (added 2026-09-22, upscaling)

`RenderParticles`: the two `glViewport(0, 0, offscreenWidth, offscreenHeight)` of the fire / smoke pass take
their size through `RenderScale.viewPx` (stock's numbers scaled like the rest of the pass; the stock behaviour
at other zooms is unchanged).

`RenderWater` (2026-09-25, `dlssWaterCurrent`): right before `IsoWater.waterGeometry` the draw calls
`pzopt.ObjectMotion.beginWaterStencil()`, which under DLSS, in player 0's world pass only, writes stencil id 127
(`ObjectMotion.WATER_ID`, the low seven bits; object ids now stop at 126) where the water's fragments pass the depth
test. The method's own `glPushAttrib` / `glPopAttrib` puts the stencil state back. `pzopt.Dlss` turns the id into an
R8 mask and composites DLSS's output with the frame's own colour on the water into a texture of its own (DLSS's output
image stays untouched: writing into it changed DLSS's later frames far from the water): the ripples are animated in
place without motion vectors, and DLSS's history blend had halved their motion (`harness/watermotion.py`, runs
`waterflow-*`: stock 0.173, dlss 0.085, upscaler off 0.165, fsr1 0.184 levels per 1/15 s; with the fix the change over
1 s is back to stock's; the sub-pixel jitter of the current frame, visible as shimmer, is averaged out by a one-frame
camera-reprojected water history clamped to the current neighbourhood, `dlssWaterHistoryPct` 60: 0.183 frame to frame,
0.649 over 1 s, stock 0.173 / 0.648). Nothing is written without DLSS.

### zombie.iso.weather.fog.ImprovedFog (edit of 2026-09-22, upscaling)

`startFrame` values: `screenWidth/Height` and `cameraOffscreenLeft/Top` (the `screenInfo.xy` / `cameraInfo.xy`
the stock fog shader and `FogPass` map `gl_FragCoord` through) go through `RenderScale.scaledPx`, scaled
whenever the pass is active (every world frame renders scaled; the game thread computes them).

### zombie.core.textures.MultiTextureFBO2 (edit of 2026-09-22, upscaling)

`render()`: before the per-player quads `pzopt.Upscaler.queueResolve()` queues the render-thread resolve
(`GenericDrawer`) of the frame; with the pass active the quad draws the resolved texture (`fsr1`, `dlss`:
`Upscaler.output()`, a `Texture` over the GL texture, same screen coordinates on both sides) or, for `bicubic`
and before the first resolve, the offscreen texture's scaled region (`RenderScale.scaledRect`) stretched to the
screen rectangle, so the stock screen shader's bicubic filter is the upscaler.

### zombie.iso.weather.WeatherShader (added 2026-09-22, upscaling)

`startMainThread`: `texd.col2/col3` (the `TextureSize` uniform, the bicubic texel size) take the resolved
texture's size from `Upscaler.compositeTextureSize()` when the quad draws it; stock's offscreen texture size
otherwise.

### zombie.vispoly.VisibilityPolygon2 (new override, 2026-09-23, the "second view cone")

The view-cone shadow is drawn in two steps: the shadow polygons into a half-size blur buffer (colour + depth) with
the world projection, then a screen quad whose shader (`visibilityBlur`) takes the shadow's alpha from the blur
buffer through `screenSize`, and the shadow's depth (written as the fragment depth, tested less-or-equal against
the scene) through `displayOrigin` / `displaySize`. The IsoCamera hook already scaled `screenSize` and
`displayOrigin` in the scaled world pass, but `displaySize` is the offscreen buffer's size, read from the buffer
itself, so under an upscaler the depth came from a copy of the shadow shrunk by the render scale towards the
bottom-left corner. The visible shadow was the true cone intersected with that shrunken copy: wedges of the cone
missing and a second cone apex off the player, most visible zoomed out (report of 2026-09-23).
`Drawer.renderToScreen`: `displaySize` goes through `RenderScale.visBlurPx`, scaled like the two other uniforms
inside the scaled world pass (`devUpscalerStockVisBlur=true` keeps the stock value, the A/B of the bug).
`Drawer.renderNew`: after its closing integer viewport restore, `RenderScale.afterModelDraw` puts the jittered
float viewport back (DLSS), as after a model draw. Screenshots: runs `vcone2-*` (fsr1 with the stock value vs the
fix vs no upscaler; the stock value's diff against no upscaler shows the straight-edged cone wedges, the fix's diff
only cloud-shadow noise). With the upscaler off both hooks are no-ops.

### zombie.iso.sprite.IsoCursor (new override, 2026-09-23, upscaling)

The aiming cursor (`IsoCursorShader`, shader `isocursor`) colours itself with the inverse of the world under it:
`accept` maps the cursor's screen rectangle into the offscreen world texture as `x / width, y / height` of that
texture. Under an upscaler the world image fills only the scaled rectangle of the texture (every mode keeps it
there; fsr1 / dlss draw a separate resolved texture), so the cursor read the world from a point the render scale
away and took the wrong colour. `startMainThread` now takes the background from `Upscaler.cursorBackground`: the
resolved output when there is one (fsr1 / dlss: the image on screen, and with `dlssDirectColor` the only current
one), else the offscreen texture; `accept` divides its two sizes by `Upscaler.cursorBackgroundScale` (the output's
share of the screen, or the render scale for the offscreen texture; 1 with the upscaler off, i.e. stock).
`IsoReticle` has the same mapping but its shader never samples the world texture.

## zombie.characters.IsoZombie (fifth edit, 2026-09-22, the flat draw of the horde's zombies)

New `pzoptRenderFlat(x, y, z, col)` (`zombieAtlasFast`), called by `FBORenderCell.renderMovingObject` in place of
`render` for an object of exactly this class: for the two cases that make up a horde — a culled zombie (no active
model, the atlas sprite) and a zombie whose model draw data the pre-pass built (`CharDraw.isPrepared`) — it
performs the observable steps of `IsoZombie.render` and `IsoGameCharacter.render` in their order: drop the
corpse-atlas texture, the alpha rule when the camera is not on the player, the doRender / alpha / seat / invisible /
alpha-zero returns, the depth-mask state, the static light scratch from the square, the default facing, the
last-rendered statics, `checkUpdateModelTextures`, the sprite scale, then either `renderTextureInsteadOfModel` or
the body of `IsoSprite.renderActiveModel` (the object / debug-option test, the profiler area, the lights call the
frame stamp answers, the camera record — the one the pre-pass built with the draw data, `CharDraw.takeCamera`, else
its own — and the model enqueue that takes the prepared data), then the item updaters
and the ragdoll / ballistics debug renders — and returns true; otherwise (fire sprites, a non-parts sprite, the
non-fbo path, a debug mode, a fake-dead zombie, a model without prepared data) it returns false having done nothing
and the stock virtual chain runs. Sits after `renderTextureInsteadOfModel`.

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-22, the flat draw and the prepared zombies' tests)

`renderMovingObject` is split: the three leading tests (not a player, a square, on screen) stay in it and the rest
moves to the new `pzoptRenderOnScreenObject(object, playerIndex)`, which the pre-pass's loop calls directly (its
list was built from the same three tests in this frame; nothing changes them during the render). In that rest: a
zombie whose draw data the pre-pass built (`CharDraw.isPrepared`) skips the square light-info and cutaway-visibility
tests (the pre-pass applied both on the game thread this frame); the square is read once into a local; before
`render`, an object of class `IsoZombie` is offered to `pzoptRenderFlat`; true = drawn, the method returns; false =
the stock `render` call as before.

## Zombie game-thread pass (2026-09-22 afternoon; `actionSnapshotFilter`, `emitterParamSkip`, `separateFast`, `sleepCheckMemo`, `stateParamMemo`)

Goal of the pass: the `zombies` sub-phase of the game-thread profile (`IsoZombie.update` + `IsoZombie.postupdate`)
under 5 % on the Louisville horde; it was 21 % on `zbu2-lou-ours` (12.1 update + 11.2 postupdate), with the leaf
profile showing where: the callback snapshot 7.6 %, the separation pass 3.2 %, the FMOD parameters 1.6 %, the
sleep check 0.7 %, the state-param maps 0.9 %.

### zombie.characters.action.ActionContext + conditions.CharacterVariableCondition (third edit, `actionSnapshotFilter`)

The snapshot the game thread takes before a batch resolved *every* operand of every transition of the current state
and its sub-states, per zombie, per frame — a grid of string-keyed handle lookups through the component map and the
state container — only to discover that almost all of them are stored slots or audited pure callbacks that need no
snapshot at all. The decision does not depend on the zombie: a condition's variable *name* is fixed by the action
XML, and which names are callbacks is fixed by the character constructor. So `CharacterVariableCondition` gained
`pzoptNeedsSnapshot(lookup)`, a name-only test (a sub-variable source — another character — stays conservative), and
`ActionContext` applies it once, when it caches a state's operand array: the per-frame walk now visits only the
handful of operands whose callback has side effects. `pzopt.ActionEval.initCallbackKeys` reads the callback key set
off the first batched zombie's variable registry and logs its size; until it has, the filter answers "snapshot",
so the behaviour is the old one. Rig: with `devActionEvalCheck` the context also resolves every dropped operand and
counts the ones that did resolve to an impure callback (`filterMisses` on the action-eval log line; must stay 0).

### zombie.characters.IsoGameCharacter (third edit, `emitterParamSkip`)

`updateEmitter` recomputed the character's whole FMOD parameter list every frame — the footstep material walks its
square's objects and parses a property string, the zone parameter looks up the room — although a parameter value
only ever reaches FMOD through the event instances of that character's own emitter. With no instance running and
none queued, the values were written to nothing. The list is now updated only when the emitter is not clear or has
a sound about to start; that gate is evaluated before `emitter.tick()` starts anything, which is the only place the
cached value is read (`FMODLocalParameter.startEventInstance`), so a starting sound still gets a fresh value.

### zombie.characters.IsoZombie (sixth edit, `separateFast`) and pzopt.SeparateMask

`IsoMovingObject.separate()` re-derives per neighbouring object whether the asker is a player; for a zombie it never
is, which makes the charged-spear branch and the whole bump block (with its traits, moodles, RNG roll and
`wasBumped` event) unreachable. `IsoZombie` now overrides `separate()` with the zombie-only path — the same solid /
pushable gates, square and object walks, early return on a non-character non-vehicle object, contact, push vector
(down to `Vector2.normalize()`'s "already unit" shortcut), camera-distance gate and `collideWith` — and asks
`pzopt.SeparateMask` instead of the grid whether its square is blocked to a neighbour. That answer depends only on
the two squares' geometry (and, for a diagonal, the two between them), never on who asks, so it is cached per square
for the frame in a direct-mapped identity table, computed lazily per neighbour: hundreds of zombies standing on a
few hundred squares used to recompute the same wall / window / door / stair recursion three to eight times a frame.
A door that opens mid-frame is seen on the next one. `pzopt.FrameTick` (bumped once per frame from the scheduler's
`update()`) is the stamp.

### zombie.characters.IsoPlayer (third edit, `sleepCheckMemo`)

`GameTime.getMultiplier()` asks `IsoPlayer.allPlayersAsleep()` on the way into every character update, and a zombie
asks for the multiplier several times per update, so the player array was walked tens of thousands of times a frame
for an answer that cannot change inside a frame. Memoised on `FrameTick`.

### zombie.characters.IsoZombie (`stateParamMemo`)

Every `State.Param` read (an AI state's per-character scratch) goes through `getStateMachineParams(state class)`:
the entity-component map, then an `IdentityHashMap` keyed by the class, then the param's own probe — and a state's
`execute()` reads several params of the same class in a row. The zombie keeps the last (class, map) pair beside the
`ecsLookupFast` component field; the component is created once per zombie and its per-class map object is only ever
cleared, never replaced, so the memo returns the identical map.

### zombie.MovingObjectUpdateScheduler (fourth edit)

`update()` bumps `pzopt.FrameTick`, the frame stamp of the simulation memos above.

### Second pass on the snapshot (2026-09-22, same keys)

Measured after the first: the snapshot was still 4.5 % of the game thread, in three parts. (a) The audit only
knew 95 of the character's 157 callback variables, so 62 names still counted as "may have side effects"; a second
audit of their getters added 29 (field reads and derived reads of the hand / worn items, the action queue, the fall
table; the zombie's network-moving test, small-vehicle test, distance to target, canSeeTarget and
shouldGetUpFromCrawl). The ones left out are listed in `pzopt.ActionEval` beside the set with the reason —
`battack` / `bhastarget` / `shouldsprint` clear the target through the getter, `bthump` drops the thump target,
`beatbodytarget` rescans the corpses, `blunge` runs a pathfind line test through a shared pool, `turndirection`
uses the class's static vectors, and six more are simply not audited yet. (b) Every operand was resolved twice per
read — once to classify the slot, once inside the value read — and (c) the value read consulted the worker's
thread-local snapshot map even on the game thread, where it is never set. `CharacterVariableLookup.pzoptResolve`
now keeps the operand's pooled `AnimationVariableHandle` (allocated from the same pool the reference would have
used) and asks the owner directly when there is no sub-variable source, which also drops the blank-name scan the
reference does on every call; the snapshot reads the resolved slot straight.

### zombie.MovingObjectUpdateScheduler (fifth edit, `zombieSimLodTiles`, experiment, default 0)

Stock already drops a visible object's simulation level one step at 30, 60 and 80 tiles from the nearest player
(the bucket it lands in then updates every 2nd / 4th / 8th frame). The key adds one more step at a chosen distance,
for zombies only, as an A/B of "simulate fewer of the horde per frame".

### zombie.characters.IsoZombie (seventh edit) + pzopt.SeparateBatch (`separateParallel`, default off)

The separation pass is split into a compute that only reads the world and an apply that does every write. The
compute walks the nine squares and records the total displacement, the one contact, the wasSeparated flag and the
`collideWith` partners in order (at most twelve; a thirteenth is counted as a spill and dropped). The apply runs on
the game thread at the point in the zombie's own update where stock would have done the writes, so the Lua collide
events, the window climbs and the thumps keep their order. With the key on, the scheduler collects the zombies
whose bucket matches this frame while it fills the simulation levels in `startFrame` and runs their computes on the
frame workers at the top of `update()`; a zombie the batch did not reach computes inline exactly as before. The one
behavioural difference: a neighbour's position is read at the top of the frame rather than as the loop advances.

### Third pass (2026-09-22, `actionGroupCache`, `profilerThreadMemo`, `stateParamMemo` in `zombie.ai.State`, snapshot dedupe)

Measured after the second pass, all from the leaf profile of the `zombies` sub-phase:

- `zombie.ai.State` (new override). `State.Param.get` — the per-character scratch every AI state reads several times
  per frame — resolved its value with `computeIfAbsent(this, param -> defaultSupplier.get())`. The supplier is a
  parameter, so the lambda captures and allocates on every read. A get, and a put only when the default was actually
  produced, is exactly what `computeIfAbsent` does with an `IdentityHashMap` (it stores nothing for a null result and
  treats a mapping to null as absent). Vineflower's output needed two casts it had dropped. Marker is the quiet one:
  the AI states initialise before the logger.
- `zombie.GameProfiler` (new override). Every performance probe in the game — `IsoZombie.update` and `postupdate`
  have one each — calls `isValidThread()` on the way in and on the way out, and the valid-thread list is an
  `ArrayList<String>` scanned with `String.equals`. The list is filled once in the static initialiser and a thread
  keeps its name, so the answer is memoised on the per-thread instance the profiler already holds. 0.7 % of the game
  thread, paid whether or not the profiler is recording (`profilerThreadMemo`).
- `IsoZombie` holds the `zombie` and `zombie-crawler` `ActionGroup`s (`actionGroupCache`): `updateInternal` asked for
  them by name twice per zombie per frame and the lookup lower-cases the name into a new String before probing a map
  for a group loaded once at startup.
- `ActionContext` dedupes the snapshot: a state that tests the same variable in several transitions used to resolve
  and call it once per transition. The duplicate map is built once per state, from the operands' names.
- The snapshot resolves straight to the character's own slot array when the action-state container holds no state
  variables at all (checked once per snapshot instead of walked per operand — stock walks the current state and every
  sub-state before falling back to the character).

### zombie.MovingObjectUpdateScheduler (`zombieSimLodSteps`) and IsoZombie / IsoGameCharacter (`zombieCheckSpread`)

`zombieSimLodTiles` may now take more than one extra step, each at twice the distance of the previous one, like
stock's own 30 / 60 / 80 ladder (`zombieSimLodSteps`, default 1). `zombieCheckSpread` (default 0 = stock) runs a
zombie's thump probe — the grid test for something thumpable in front of it — on one frame in N, spread by zombie id.

**Dead end, recorded:** the same spread was first applied to `IsoGameCharacter.updateSeenVisibility` as well. That
method writes `isVisibleToPlayer[]`, which decides whether the zombie is drawn at all and feeds the scheduler's own
LOD, so spreading it stopped most of the horde from rendering and produced a large fake frame-rate win (138 and
152 fps where the same scene runs at 64). It is called every frame again, with a comment saying why.

## Instant Continue pass (2026-09-22 evening; `tileDefPreload`, `skipIdChecks`, `voronoiFast`, `noLoadFade`, `noClickToStart`)

Goal: Continue → world ready from ~4 s to under 2 s on the bench save. Profiled with `harness/phaseprof.py` on
`load-now-jfr`; numbers per step are in `docs/plan-instant-load.md` ("Addendum 2026-09-22").

### zombie.iso.IsoWorld (tile definitions, `tileDefPreload`)

Stock disposes every tile sprite at the start of each world load and parses the seven `.tiles` files plus the mods'
again (property strings, alias table, ~61k sprites; 0.6 s of the loader thread). A new public method builds the same
thing into a caller-supplied private `IsoSpriteManager` from a boot thread (`pzopt.TileDefPreload`, started from
`GameWindow.enter` right after the tile packs register): same files, same order, same property passes, the mod files
resolved as `ZomboidFileSystem.loadModTileDefs` does. While it runs, a new instance field names that manager: sprite
creation in `LoadTileDefinitions` and `registerFakeJumboTree` goes through a texture-less copy of
`IsoSpriteManager.AddSprite` (the texture table is a plain HashMap the main thread fills during boot), the door lookup
in `setOpenDoorProperties` reads that manager instead of the global one, and `LoadTileDefinitionsPropertyStrings`
skips its loading-screen frame pump. In `init`, the whole stock tile-definition block (after stock's own `Dispose`)
is skipped when `TileDefPreload.install` succeeds: it binds each sprite's texture by name exactly as `AddSprite`
does, moves the sprites into the global manager and returns the tile image list. Used once per boot, only with the
same mod list and language, never in debug or multiplayer; otherwise the stock block runs. Also: load-trace step
markers around the map-zone section (logged only with the trace installed).

### zombie.GameWindow (fourth edit)

`enter` starts the tile-definition preload after the tile texture packs are registered. `exit` ends with
`pzopt.AotCache.onGameExit`: a JVM started with `-XX:AOTCacheOutput` exits through `System.exit`, because HotSpot
writes the AOT cache only on an orderly JVM exit and the native launcher ends the process without one.

### zombie.buildingRooms.BuildingRoomsEditor (second edit, `skipIdChecks`)

`checkBuildingAndRoomIDs()` walks every building and room of every lot-header cell and only logs ids that disagree with
their position. Stock runs it six times per load (0.37 s even with the identity index); it now runs in debug mode only.

### zombie.iso.IsoMetaGrid (loader thread, `voronoiFast`)

The meta-grid loader threads multiply each cell's zombie intensity by the zombie-density voronoi layers
(`ZombieVoronoi.evaluateCellCutoff`), which re-seeds a Random for nine sectors, allocates a point object per sector and
sorts the boxed squared distances through a stream for each of a cell's 1024 samples: 89 % of the eight loader
threads, 1.2 s of wall time the loader waited for. The call goes to `pzopt.ZombieNoise.cellCutoff`, which generates
each sector's points once per cell with the same seeding and draws and keeps the smallest and second smallest of the
same double expressions (the only elements stock reads from the sorted list). `tests/pzopt/ZombieNoiseTest` compares
it bit for bit with the game's own method over 1,080 cells, every selection type and several seeds and scales. Any
reflection failure falls back to the stock call.

### zombie.gameStates.MainScreenState (new override, `noLoadFade`)

`exit` faded the main menu to black over 250 ms (render, sleep 33 ms, repeat) before the load could begin. With
`noLoadFade` the fade starts at full black: one black frame, then the stock cleanup (video, music). Vineflower's output
recompiles unchanged. This is the launcher's main class, so its marker is the quiet one.

### zombie.gameStates.GameLoadingState (`noClickToStart`)

`update` returned to the world only after the "click to start" prompt had been drawn and a click or A was seen. With
`noClickToStart` it continues at the same point a click would (loading done, streamer idle, animations loaded, player
created; a new game still waits for its intro unless `noIntroWait`), without the click sound.

### zombie.GameWindow (fifth edit, `earlyTilePacks`, `aotCache`)

The tile-pack block of `enter()` is a method now; with `earlyTilePacks` `initShared` calls it right after the tile
geometry / depth assignment managers initialise (after the UI packs and the script load, before the boot Lua load),
queues the 218 depth-map loads there (`TileDepthTextureManager.init`, skipped in `enter()` then) and starts the
tile-definition preload. `enter()` then only re-runs the pack lookup. Same packs, flags and order. `enter()` also
starts `pzopt.AotCache`, which decides the next launch's launcher form on a daemon thread.

### zombie.gameStates.MainScreenState (second edit)

The `noLoadFade` skip draws three black frames, not one: a frame push waits while the render thread is behind, so
the menu frames queued before the skip are drawn before the menu destroys its background video texture. With one
frame, a queued menu frame was sometimes drawn after the destroy and showed the missing-texture checkerboard for one
frame (reported by the maintainer; reproduced in the recording of run `flash-all`, gone in `flash-fix`).

### zombie.gameStates.GameLoadingState (`noLoadingScreen`) and zombie.GameWindow (sixth edit)

At the maintainer's request (2026-09-22) single player shows a plain black frame while the world loads: `render` draws
a black quad and returns before the loading screen (text, quick tips, progress dots) unless an error, a world-version
dialog, a map download or a save conversion needs the stock screen. `GameWindow.logic` calls
`pzopt.NoLoadingScreen.afterStateUpdate` after the state machine's update; on the frame the state machine enters
`IngameState` it zeroes the UI fade (`fadeInTime`, `fadeAlpha`) that `IngameState.enter` started, so the world appears
without the fade from black. Multiplayer keeps the stock screen.

### zombie.MapCollisionData (third edit, `loaderCpuFixes`)

`init` passed every cell of the 500 x 500 world grid to the native side with the path looked up as
`infoFileNames.get("chunkdata_" + cx + "_" + cy + ".bin")`, one string built and hashed per cell and map folder. The
chunkdata entries of each map folder are indexed by cell once (only keys in exactly that form); the same path, or
null, reaches the same native call for every cell in the same order.

## Dell hitching pass (2026-09-22 night; `saveCellAsync`, `jitMode`, `threadNice`, `profileHandshake`)

Profiled on the 4-core Dell (i5-6300HQ / GTX 960M) during the 120 km/h low-end drive with a per-thread scheduler
monitor (`run.sh --schedmon`) and async-profiler (`run.sh --asprof`). Frames over 100 ms were C2 compile bursts (the
compiler threads took ~1.6 of the 4 cores in those frames and the game thread got a CPU 38 % of the time) and the
game-thread sampler's own global safepoints (below).

### zombie.popman.ZombiePopulationManager (new override, `saveCellAsync`)

Every chunk that leaves the world asks for its whole 256x256 cell to be saved again. The request took `saveLock`, which
the MapCollisionData thread holds through each native cell write, so the game thread waited for file writes while
driving. With `saveCellAsync` the request builds its zombie snapshot without the lock (the snapshot reads only game-
thread state; the native population calls stay under the lock on the writer) and keeps one pending write per cell: a
newer snapshot replaces the queued one, the writer takes the newest. A drive asks ~9 times per written cell
(1,406 requests, 160 writes). The console counts both (`saveCell:` lines).

### zombie.gameStates.GameLoadingState (`jitMode`)

`exit()` first calls `pzopt.JitGovernor`, which with `jitMode=c1play` (or `auto` on `jitC1Cores` cores or fewer, the
default) adds a compiler directive excluding every method from C2 through the DiagnosticCommand MBean. HotSpot then
compiles newly hot methods with C1 at tier 1; code C2 made during loading stays. Loading keeps C2 (load time
unchanged, unlike `-XX:TieredStopAtLevel=1`, which cost the Dell 16 s of world load).

Long-session check (2026-09-23, Dell, 10-minute walk through new ground, two runs each): tiered 69.2 / 69.4 fps,
frames over 100 ms 1.9 / 1.8 per minute; `jitMode=auto` 64.3 / 63.8 fps, 0.8 / 0.6 per minute. The mean-fps cost stays
because HotSpot marks a method it declined for C2 under an exclude directive as not C2-compilable for good, so hot code
reached during play keeps C1 code after warm-up; the gain in the worst hitches stays too. Running the C2 threads at
SCHED_IDLE instead (tiered code, compile only on idle CPU) behaved like tiered (68.3 fps, 1.5 per minute): the Dell is
~85 % busy, not saturated, so the idle thread still finds time. On a 12-core laptop pinned to 6 / 8 cores excluding C2
also removed the frames over 100 ms (4.5-6 -> 0 per minute at 6 cores, 3 -> 0 at 8) but cost 20-35 % of mean fps at 6
cores (118-120 vs 149-195 fps tiered, two runs each), so the threshold stays at 4 cores (`jitC1Cores`). `jitMode=c2idle`
(C2 threads at SCHED_IDLE, via `ThreadNice.addRule`) is kept as an option: one 6-core run looked like a big win (215 fps,
nothing over 100 ms) but its repeat with identical settings measured 152 fps, and on 12 / 16 cores it equalled tiered.

### zombie.iso.WorldStreamer (`threadNice`)

The static block that logs the settings also starts `pzopt.ThreadNice` (off unless `threadNice` has rules): nice
values, SCHED_IDLE / SCHED_BATCH or a CPU affinity for this process's threads by name, applied through `setpriority`
/ `sched_setscheduler` / `sched_setaffinity` over the FFM API. No rule set beat the default on the Dell (pinning the
game thread alone on a core, a core for the render thread, niced or idle C2 threads all lost).

### pzopt.GameThreadProfile (`profileHandshake`, not a game class)

The overlay's game-thread sampler called `ThreadMXBean.getThreadInfo(id, depth)` 100 times a second. Java 25 serves
that with a global ThreadDump safepoint, not a handshake: on the Dell it stopped every Java thread for 10 % of the wall
time, single stops up to 175 ms, in every instrumented run and whenever a player shows the overlay. It now samples with
`Thread.getStackTrace()` on the game thread, a handshake with that thread only (`-Xlog:safepoint` shows no ThreadDump
safepoints any more); `profileHandshake=false` restores the old call.

### zombie.iso.IsoChunkMap (`chunkMapFast`)

`calculateZExtentsForChunkMap`, run whenever a chunk arrives, looped over the chunk array's length squared (169 x 169
`getChunk` calls on the 13-wide grid, all but 169 out of range and null); it now loops over the grid width, the same
result. `getGridSquareDirect`, the lookup behind every `IsoCell.getGridSquare`, does its range checks, chunk index
(shift / mask instead of divide / modulo on the already range-checked coordinates), mid-scroll guard and level index
inline instead of through five helper calls: with C2 excluded during play on few cores (`jitMode`) C1 code pays for
each call. Dell drive: game-thread "chunk map" 15 -> 11 %, 36.4 -> 39.5 fps (two runs each).

### zombie.iso.LightingJNI (`lightingVisionParallel`, default off)

Before the chunk loop of a lighting pass, `pzopt.VisionBatch` can compute the ten neighbour vision tests of every
square of every dirty chunk level on the FrameBatch workers; `updateChunk` then reads the bits instead of calling
`testVisionAdjacent`. Exact (`devVisionCheck`: 225,153 values, 0 mismatches), but off by default: on the CPU-bound
4-core Dell the workers got no core, the game thread ran most of the batch itself and fps did not change.

### pzopt.Harness road following (not a game class)

Where the street is wider than the 15-tile scan the controller held course, keeping the heading error of the last
curve; at the Dell's ~30 fps the car drifted off the route line through the wide stretch after the Rosewood start,
overcorrected at 100 km/h and stopped in a yard (three drive timeouts). It now steers back to the route line there.
Since 2026-09-23 it aims at the lateral position 0.6 s ahead (the lateral velocity taken from the heading) rather than
the current one: on position alone a start yaw of a few degrees went uncorrected until the car was tiles off the line,
and past the +-3 tile clamp the target stopped moving, which removed the damping; on the desktop half of the evening's
E:1200 drives swung +10 / -8 tiles and hit the north side at x~8120 about 8 s in (route_complete could still read 1).

### zombie.core.properties.PropertyContainer (new override, `propertySurfaceNoAlloc`)

`initSurface` (surface height, table flags, sloped-surface data, re-derived after every property recalculation) walked
its entries through `forEachEntry` with a capturing lambda. C2 removed that allocation; with C2 excluded during play on
few cores (`jitMode`) C1 allocated one per call, and chunk loading's `CalculateCollide -> getSlopedSurfaceDirection` made
it 30 % of all allocation on the Dell drive (~240 MB in 40 s, mostly on the World Streamer). The same loop now runs over
the map's own arrays in `forEachEntry`'s order, the former lambda body is a method. With `GameThreadProfile.flame` no
longer re-splitting its folded stacks on every overlay refresh, route allocation 0.80 -> 0.50 GB, G1 pauses in the
route 5-8 -> 4-5, frames over 50 ms 48-57 -> 38 per minute (two runs each).

## World entry: centre-outward load and the resume shot (2026-09-22 night; `centerFirstLoad`, `resumeShot`, `noLoadingScreen`)

The maintainer asked for no loading screen, a world that builds itself from the centre outwards, and the illusion of an
instant load (the ground around the player, captured at exit, while loading). Measured with new load-trace markers ("world visible": the
player's chunk is lit, so drawn; "world complete": every loaded chunk lit) because the harness's "world ready" fires
inside `IngameState.enter`, ~0.65 s before the first world frame (stock runs included).

- `zombie.iso.WorldStreamer` (comparator): while no player exists (the load) the job order uses the load's centre
  (`pzopt.CenterFirstLoad.center`), so the initial chunks load nearest first; stock served them in no particular order.
- `zombie.iso.IsoWorld` (initial chunk wait): the streamer loop also ends once every valid chunk within 3 of the centre
  is loaded (`CenterFirstLoad.nearLoaded`); the rest keeps streaming. `GameLoadingState.update` then no longer waits for
  the streamer either. The 2 ms poll (instead of 100 ms) is under `loaderCpuFixes`.
- `zombie.iso.IsoChunkMap.processAllLoadGridSquare` (called only by `IngameState.enter`): after an early entry only the
  chunks within 3 of the centre are handed over there; the others go back on the queue in the same order and
  `update()` hands them over a few per frame as it does while walking (0.44 s of main-thread boundary recalc before the
  first world frame otherwise).
- `zombie.iso.LightingJNI.update`: chunks are handed to the lighting engine nearest the chunk map's centre first (same
  chunks, same calls, `CenterFirstLoad.gridOrder`); a chunk is only drawn once lit (`lightingNeverDone`), and the row
  order made the world appear in diagonal bands.
- `zombie.gameStates.GameLoadingState.exit`: under `noLoadingScreen` the fader loop is skipped; the loading screen's
  fade from black (started in `enter`) is only advanced by the stock `render`, so the loop played all of it here
  (0.36 s of renders and sleeps before the world).
- `zombie.savefile.SavefileThumbnail.create`, exit saves only (`ResumeShot.exiting`: `Core.exiting`, `GameWindow.exit`
  setting `ResumeShot.exitSave`, or `PlayerDB.canSavePlayers` already false, which both quit paths clear right before
  their last save; autosaves never capture): after the stock thumbnail, `pzoptCaptureFloor` invalidates every chunk
  level, sets `ResumeShot.floorOnly`, turns occlusion off, renders the world once at the player's zoom and composites
  it, and a render-thread drawer reads the back buffer; floorOnly is then cleared and the chunks invalidated again. A
  daemon thread scales the image to 1920 px wide and writes `pzopt-resume.jpg` plus `pzopt-resume.properties` (the
  screen position of the player's chunk corner and one chunk step in world x / y) into the save folder.
- `zombie.iso.fboRenderChunk.FBORenderCell` under `ResumeShot.floorOnly`: only ground-level floors are baked (no floors
  above level 0, though every level still runs: a chunk's levels share one texture, finished and queued for the screen at
  its top level, so stopping at level 0 left every multi-storey chunk black; `renderMinusFloor` for objects, the tree pass, both translucent passes, characters, players,
  corpses, items and moving objects skipped) and the bake budget is 0 so every level bakes in that one frame.
  `pzoptSetOcclusion` flips the package-private occlusion switch; `pzoptFrameBakeCounters` logs the capture frame.
- `zombie.gameStates.GameLoadingState`: `enter` starts decoding the save's shot; `render` draws a black frame plus
  `ResumeShot.draw` when the save has a shot (no error screens pending), else the stock loading screen. The shot shows
  the 7 x 7 chunks around the player at full brightness, the rest black: whole chunks popping in (no fade), each in one
  of 9 bursts drawn at random, the bursts spread by random gaps, over 85 % of this save's last loading time (`pzopt-resume-load.txt`, written at world
  entry, averaged with the previous value; 3 s before the first measured Continue), so the square is whole just
  before the world appears. `org.lwjglx.opengl.Display.imguiEndFrame` keeps drawing it over the world (tiles the load
  outran pop in within 0.3 s), its opacity falling as the chunk map lights up (full up to 20 % lit, gone at 80 % or
  after 3 s); the texture is freed a few frames later.
- `zombie.GameWindow.exit` sets `ResumeShot.exitSave` before its save.
- `resumeShotDetail` (2026-09-24, the maintainer: "different level of details in the screenshots"): what the exit capture
  keeps. `ResumeShot.beginCapture` sets flags FBORenderCell reads during the capture frame instead of the one `floorOnly`:
  `floors` (as before: ground-level floors, occlusion off), `buildings` (every level's floors, walls, doors,
  furniture, items; `renderMinusFloor` skips `IsoTree`, `pzoptBakeTrees` and both translucent passes are skipped),
  `world` (everything static, trees and translucent tiles included), `full` (players, characters, vehicles, corpses and
  their shadows too; the default since the maintainer picked it from the four-level video, 2026-09-24). The bake's "Minus Floor Chars" block draws the static objects (walls, doors, furniture, lamp
  posts, items; the characters are the moving-objects pass), so only `floors` skips it; mapping it to "no characters"
  first cut every wall from the buildings / world shots. Only `floors` turns occlusion off (the other levels draw the
  buildings, so the frame keeps its own cutaways); the bake budget is 0 in every capture frame (`ResumeShot.capturing`).
- The fill-in copies the live world's own build-up (2026-09-24, the maintainer's request: "record a video of how the
  game loads the world and use that as a base for the fake loading screen"). Run `worldload-rec2` (`--prop
  resumeShot=false --prop overlay=false --record`, 240 cap) through `harness/revealmap.py <run> --geom <zoom-1 geometry>
  --fit` (the time each pixel turns on and stays on after world entry, the chunk grid fitted to the time map): the ~50
  chunks on screen arrived whole (each chunk one reveal time), 267-533 ms after world entry, in 9 bursts of 1-14 chunks
  17-67 ms apart, in no spatial order (neighbours left L / T shaped black holes; the outer chunks even came slightly
  first). The shot's first version (a per-tile top-left to bottom-right sweep with 35 % jitter) looked like nothing the
  game does.
- `pzopt.NoLoadingScreen` also runs the lighting thread at 240 fps (the player's `lightFPS`, 15 by default, otherwise)
  from world entry until the world is complete or 3 s, by writing the field, so options never save the boosted value.

### zombie.iso.weather.fx.WeatherParticleDrawer (`weatherNoGlGet`)

`render` (once a frame, rain or not) asked the driver for the current shader program with
`glGetInteger(GL_CURRENT_PROGRAM)` to restore it afterwards. A glGet makes the NVIDIA driver wait for its command
queue: on the Dell (PRIME offload) that was 3.3 s of the render thread's wall time in a 40 s walk, and the game thread
then waited at the frame hand-off. `pzopt.GlState` takes the program from `ShaderHelper`'s own record of what it bound
(`currentlyBound`), falling back to the query when that is unknown. Exact on the walk (`devGlStateCheck`: 1,791 calls,
0 disagreements with the driver, 0 fallbacks). Walk bench, two runs each: frames over 50 ms 33-40 -> 25-27 per minute,
p99 53-58 -> 47-50 ms, p99.9 298-342 -> 231-244 ms.

### zombie.iso.fboRenderChunk.FBORenderCell + zombie.gameStates.GameLoadingState (`renderChunkPrewarm`)

A chunk level's first bake creates its render chunk (colour and depth textures and an FBO). On the Dell the driver's
`glGenTextures` / `glCheckFramebufferStatus` stall the render thread for tens of ms each, and the pool of recycled
render chunks (keyed by texture height) only fills as the view first fills, so the first minute of play paid it while
walking and turning (render-thread wall profile of the walk's long frames: 13 % in `glGenTextures`). `GameLoadingState.exit`
now fills the pool from the loading screen: with `renderChunkPrewarm=-1` (default) as many render chunks as were in
use at once in the previous session plus 5 % (the peak is sampled every 10 s and kept in `Zomboid/pzopt/renderchunk-pool.txt`;
96 when there is no history, clamped 32-400). A fixed size sized from the chunk grid (252) over-provisioned the Dell's
4 GB of VRAM and doubled its swap-ins; the learned size settles at the machine's own demand (Dell walk: 114). The
texture width does not depend on the level count and the scale only on a debug option, so the height key is exact.
Dell walk: render chunks created during play 114 -> 4-9, p99.9 241 -> 148-197 ms; load time unchanged (the prewarm
takes ~1.3 s of the loading screen).

### zombie.gameStates.GameLoadingState (loader steps on the main thread)

Since `centerFirstLoad` (d36540a) the main thread loads chunks, and lazily registers their textures in
`Texture.s_sharedTextureTable` (an unsynchronised HashMap), while the loader thread is still running; its
`ChatUtility.InitAllowedChatIcons` iterates that table and threw a `ConcurrentModificationException` in two of three
Dell loads, hanging the game on the error, and its five `getSharedTexture` calls for the shadow and cutaway textures write
to the same table. When the world was entered early, the loader thread now hands both steps to the main thread
(`pzoptOnMain`: queued, run at the top of `update()`, the loader waits for them), so every access to the table stays on
one thread; otherwise they run in place as in stock. This replaces the first fix (cbcd436), which retried the scan up to
50 x 5 ms.

## Continue asset wait: file tasks in flight, a full-width pool, a faster Paeth filter (2026-09-23; `fileInflightLoad`, `fileThreadsWait`, `pngPaethFast`)

On the Mac the Continue spent 3.4 s in `GameLoadingState`'s second asset wait (assetLock2: animations loaded and no file
task left). A new instrumented-run log line (`assetLock2 wait:`, every 250 ms, from the file system's queued / running
tasks by class) showed the boot backlog still draining: 1,144 cached-animation tasks, 216 tile-depth loads and a few
hundred images, at exactly 16 tasks a frame, because the file system hands the pool 16 tasks at a time and collects
them once a frame.

- `zombie.fileSystem.FileSystemImpl`: the in-flight limit is no longer final. From boot until the world is entered it is
  `fileInflightLoad` (128); `GameLoadingState.exit` sets it back to `fileInflight` for play, where the file system's own
  priority order matters again (the pool's queue is first come, first served), and `enter` raises it for a second load.
  `pzoptWorkSummary` gives the queued / running tasks by class for the log line above.
- `zombie.gameStates.GameLoadingState`: while the loader thread waits on assetLock2 (the main thread only renders the
  loading frame) the file pool takes every core (`fileThreadsWait`, `pzopt.BootPump.onAssetWait`), back to `fileThreads`
  afterwards; the file-task summary is logged after the wait.
- `zombie.core.textures.TextureIDAssetManager.waitFileTask`: the time decoders sleep on the decoded-bytes throttle is
  counted in the file-task summary as `waitFileTask(sleep)` (5.5 s of 33 s of pack-page task time on the Mac).
- `zombie.core.textures.PNGDecoder` (new override): the Paeth un-filter of 4-byte pixels goes to `pzopt.PngFilters.paeth4`,
  one interleaved loop over the four channels with the left and upper-left neighbours in locals; the output is
  byte-identical (`PngFiltersTest`). The stock loop was 69 % of a texture-pack page decode in a JFR profile of a
  1024 x 1014 page; the new one takes 8.4 ms instead of 14 ms per page on the desktop CPU, on the Mac the pack-page task
  time falls 31.0 -> 29.1 s (the rest is inflate, the sleep above and the copy).

Mac Continue -> world ready: 8.16 s (mac-dwait) -> 6.25-6.64 s with the first two (mac-dwait2, mac-waitstat) and 6.29 s
with the filter (mac-paeth-on; 6.46 s with `pngPaethFast=false`). The assetLock2 wait is the boot backlog, so it is
this long only when Continue is pressed as soon as the menu shows, as the harness does.

The hand-off alone did not end it: a Dell load on 2c0ca9d stopped on the same exception on the main thread. The other
writer is the World Streamer thread, which after the early entry is still loading the rest of the grid and registers
item and object textures (`getSharedTexture`) while it deserializes their objects; any full iteration of the table races
it. The chat-icon scan therefore runs on the main thread and is retried (up to 50 x 5 ms) on a
`ConcurrentModificationException`, logging `chat icons: scan retried N time(s)` when it had to. Item icons first
registered by chunks loaded after the scan are missing from the chat-icon list (cosmetic).

### Depth maps and palette images (`pngPaethFast`, `depthMapFast`, 2026-09-23)

The 218 tile depth maps (mostly 8-bit palette PNGs, 5.8 MB on disk, 685 megapixels decoded) were 8.9 s of file-pool
time per boot on the Mac. Offline on the largest one (1024 x 7680): half the decode was the palette-to-RGBA copy writing
four single bytes per pixel, and after the decode `TileDepthTexture.load` read every tile pixel with two bounds-checked
buffer gets.

- `zombie.core.textures.PNGDecoder.copyPALtoRGBA` (under `pngPaethFast`): the palette and its alpha entries become one
  256-entry table of 4-byte pixels on first use, and a scan line is written with one lookup a pixel and one bulk put
  (`pzopt.PngFilters.paletteToRgba`), leaving the buffer position and byte order as the per-byte copy did; 19.5 -> 6.0 ms
  on that map, byte-identical (`PngFiltersTest`).
- `zombie.tileDepth.TileDepthTexture.load` (new override, `depthMapFast`): the tile's rows are read with one bulk get
  each and converted in a plain array loop (`pzopt.PngFilters.depthTile`), the same values and the same empty test;
  11.6 -> 6.1 ms for that map's 240 tiles. Decompiler fix in the same class (2026-09-23, `scripts/bytecode-audit.py`):
  in `recalculateShadowDepth` the floor-polygon rasterize callback read `floorPolygon`, which in Vineflower's source
  resolves to the method's local of that name (captured, typed `Geometry`); the jar reads the static field. Same
  object today, so no visible change; the callback now reads `TileDepthTexture.floorPolygon` like the jar.

Mac (one run each, same build): depth-map task time 8.9 -> 7.7 s, image decode 4.1 -> 2.8 s, Continue -> world ready
6.37 -> 5.96 s (mac-depth-off / mac-depth-on).

## Zombie postupdate pass (2026-09-23 night; `animatorParallel`, `animBatchAsync`, `animatorPipeline`, `guardedCallbacks`, `modelLockPerInstance`, `poolStatsBatched`, `headOnWorker`)

Goal: `MovingObjectUpdateScheduler.postupdate` under 4 % of the game thread on the Louisville horde. Numbers in
`docs/archive/2026-09-24/results.md` ("Zombie postupdate pass"). The pass moves the per-zombie finish of the postupdate loop to the frame
workers (`pzopt.AnimParallel`) and fixes the shared state that kept them serial.

### zombie.characters.IsoGameCharacter (`animatorParallel`, `headOnWorker`)

`pzoptPostUpdateAnimatingRest` is split into its three stock steps: the state change (`pzoptRestContext`), the animator
and everything after it (`pzoptRestAnimatorAndAfter` / `pzoptRestAfterAnimator`), in stock order when called in sequence.
New worker task `pzoptRestWorker`: the animator, the move deltas and the model update (the track tick; model-less zombies
the non-visual update) with the time step computed on the game thread. The anim events of that update are captured in an
`AnimCapture` instead of dispatched (`OnAnimEvent` checks the capture; the old body is `pzoptDispatchAnimEvent`); a zombie
whose animator step fired any event stops there and finishes on the game thread after its events, so everything after
the animator sees the handlers' effects as in stock. `pzoptRestFinish` (game thread, queue order) dispatches the captured
events around the context's event clear in stock's order, then the finishing report. `applyDeltas` uses a per-thread copy
of stock's shared move-delta scratch on a worker, seeded with the shared one's twist delta: stock never resets that field,
so it holds the first value of the session and the copy must start from it (a zombie is only armed once it is set).
The model-less update flips `PerformanceSettings.interpolateAnims` per thread (`AnimParallel.setNoInterpolate`) instead of
the static. `headOnWorker` (default off): the head of `postUpdateAnimating` (forward direction, aim angle, the turning
flags) runs first in the zombie's transition-evaluation task; a turn-around that would fire Turn180Started /
TargetChanged is left to the game thread with a stock evaluation. Off by default because every run with it on tipped the
Louisville preset into its re-bake flood (3 of 3, 0 of 3 with it off on the same build) while the evaluation check stayed
exact; the head only touches the zombie's own fields, so the effect looks like timing of the native see_all NaN race, not
a data race, but it is not understood.

### zombie.characters.IsoZombie (`guardedCallbacks`, `animatorParallel`)

The variable callbacks with side effects carry a guard (`AnimParallel.impureGuard` / `conditionalGuard`) that throws on a
worker before the side effect. For eight of them the side effect is a rare branch, and only that branch is guarded:
bHasTarget and shouldSprint (a target that became a reanimated corpse), battack (a target on the floor, the vehicle walks
of the crawler / fake-dead branches), bthump (dropping a far or timed-out thump target), blunge (ghost target, the
staircase reset, the pathfind line test within 3.5 tiles), battackvehicle and bPassengerExposed (only with the target in
a vehicle), beatbodytarget (the eat-target update is a no-op without a body). These are read on the workers like pure
callbacks; the transition evaluation that hits a guard is left unstamped (the game thread evaluates it stock-wise in the
apply loop, `guardedFallbacks=`), the animator task that hits one finishes on the game thread (`impure=`). That finish
does not run the animator step again, so a guard hit inside `AdvancedAnimator.update` loses the rest of that step. It
was not rare: a zombie attacking a car with the player inside reads battackvehicle / bPassengerExposed with the target
in a vehicle every frame, and its attack animation never reached ThumpFrame (Workshop report 2026-10-01, "zombies crowd
my car instead of attacking": `impure=52213` in a 35 s siege run, car condition 1200 -> 1200 against stock's 878).
Since then `AnimParallel.eligible` leaves a zombie whose target sits in a vehicle on the game thread (`vehicleTarget=`),
and a zombie whose animator hit any guard stays there for 60 frames (`impureHeld=`). Rig: harness flag `siege=N`
(`pzopt.CarSiege`).

### zombie.characters.action.ActionContext

`pzoptEvaluate` publishes a per-generation "evaluation done" stamp (volatile) for the pipelined apply loop, and treats a
guard exception as "not evaluated here".

### zombie.core.skinnedmodel.animation.AnimationTrack (new override)

The deferred-motion keyframe scratch (`L_updateDeferredValues`) is per thread, the track id counter atomic, and the
keyframe sampling reads the interpolation switch through `AnimParallel.interpolateAnims` (a worker's model-less update
samples without interpolation, like stock's flip of the static).

### zombie.core.skinnedmodel.animation.AnimationMultiTrack (new override)

The static temp list of `removeTracks` is per thread. A track removed while a worker captures anim events is released
after the events are dispatched (an event can name the track its own update just finished).

### zombie.core.skinnedmodel.animation.AnimationPlayer (`animBatchAsync`)

Every public accessor or mutator of the bone state first joins the asynchronous bone batch when the player's bones are in
flight and the caller is the game thread (`pzoptInFlight`; counter `guardJoins`, 0 in every run).

### zombie.core.skinnedmodel.model.ModelInstance (`modelLockPerInstance`)

Stock's `lock` is a string literal, i.e. one interned object shared by every model instance, and `ModelSlot.Update` (its
only user) holds it across the track tick. Harmless on one thread; with the animators on eight workers it serialised them
(each animator task 2.4x slower than on one thread). It is a new object per instance now.

### zombie.network.statistics.data.PerformanceStatistic (new override, `poolStatsBatched`)

The pool statistics counters every pooled alloc / release bumps are `AtomicDouble` compare-and-set loops shared by all
threads; on a frame worker the counts go to per-thread tallies (`pzopt.PoolStats`) published once per batch.

### zombie.iso.IsoWorld, zombie.MovingObjectUpdateScheduler (`animBatchAsync`)

The bone batch (`pzopt.AnimBatch`) starts on the workers at the end of the postupdate loop without a join; `FinishAnimation`
(the game's own join point of its threadAnimation debug option, before the render phase) or the next frame batch joins
it. `IsoWorld.init` also holds `pzopt.SpriteWindow` from the sprite manager's dispose to the missing-tile sprite (below).

### zombie.tileDepth.TileDepthTextures, zombie.fileSystem.FileSystemImpl (sprite-map race)

With earlyTilePacks the tile depth-map loads finish during the world load; the last one walks the global sprite map
(`TileDepthTextureManager.initSprites`, `TileDepthTextureAssignmentManager.initSprites`) on the main thread while the
loader refills it, and about a third of the Louisville loads logged a ConcurrentModificationException. A finish during
the loader's sprite window waits for the next file-system pump; a walk that still meets an on-demand sprite insert
(early world entry) is repeated there (both walks are idempotent). Log line `spriteWindow:`.

### pzopt.Updater (not a game class)

The main-menu update check is skipped whenever the harness flag file asks for a run (`Harness.REQUESTED`); it used to
test `Harness.active()`, which is still false at the menu, so every run polled GitHub and logged the 403 of its rate limit.

### Map zones on Continue (`zoneEdgePrefilter`, 2026-09-23)

The map-zones step of a Continue (0.72 s on the flip) was mostly Java called from the Lua `doMapZones`: registering each
zone into every chunk its bounds touch (`IsoMetaCell.addZone`, 42 % of the step's loader-thread samples) and, for
polygon and polyline zones, testing each chunk's four sides against every edge (24 %; JFR run `flip-zonesjfr`).
`zombie.iso.zones.Zone` (new override): the edge loops of `lineSegmentIntersects` and `polylineOutlineSegmentIntersects`
go to `pzopt.ZoneGeom`, which skips an edge whose bounding box is more than a tile from the side's before the stock
per-edge arithmetic; the float error of that arithmetic at map coordinates is far below a tile, so the answers are the
stock ones (`ZoneGeomTest`: 960,000 side tests identical). OnLoadMapZones 297 -> 270 ms on the flip (flip-zones-*).

### Options screen built without the Optimizations tab (Lua, 2026-09-23)

The in-game menu builds the whole options screen while the world is entered (and at boot and exit); the Optimizations
tab was 101 of its 124 ms on the flip (`options screen: MainOptions:create took` / `options tab: ... built in` log
lines). `pzopt_optimizations_options.lua` now adds the tab's page empty and builds its controls the first time the tab is
activated (the tab panel's `onActivateView`, which mouse, joypad and `activateView` all reach), then loads the saved
values into them and keeps the screen's changed flag as it was. World entry: the screen builds in 28 ms. Harness rig:
`--flag options_check=S` activates the tab S seconds into the world and logs the build (flip-lazytab: 152 controls,
changed=false, no second build).

### zombie.AmbientStreamManager.checkHaveElectricity (`electricityLevelRange`, 2026-09-23)

Called on world entry and when the power state changes, it asked the cell for every tile of the chunk map at all 64
levels (-32..31), about 1.5 million lookups, almost all of them empty (JFR `flip-zonesjfr`: ~9 % of the world-entry
window). A square outside a chunk's minLevel..maxLevel is always null, so the level loop now runs only between the
lowest and highest level of any chunk in any player's chunk map (the cell's lookup reads them all), in the stock order.
Instrumented runs log `checkHaveElectricity: levels a..b, N objects, T ms`; with `devElectricityCheck=true` the same call
also counts what the stock 64-level walk visits (flip-elec-check: 8,274 objects both ways, 9 ms vs 23 ms stock).

A/B key `centerFirstEntryRadius` (default 3, `pzopt.CenterFirstLoad.nearCenter`): the chunks handed to the chunk map
before the first world frame. Radius 1 on the flip: world ready -> first frame 634 vs 672 ms, world visible 4.68 vs
4.80 s (flip-entryr1 / flip-entryr3), inside the run-to-run noise, so the default stays.

### Options screen built when first opened (Lua, `lazyOptionsScreen`, 2026-09-23)

The main menu (boot), the in-game menu (every world entry) and the menu after an exit each build the whole options
screen through `MainOptions:create()` while it stays hidden. The Lua event profile (`luaEventProfile`) put the main menu
build (`LoadMainScreenPanel`) at 654 ms on the flip, the options screen being most of it. `pzopt_optimizations_options.lua`
now replaces `MainOptions.create`: while the screen is hidden it runs only the part the game needs without the screen,
the key bindings (`MainOptions.loadKeys`, which registers them with the core, and the `keysB42.ini` rewrite stock does
after a key-file version change), and builds the rest the first time the screen is used: `toUI` (MainScreen calls it
before showing the screen) or `setVisible(true)`. A resolution change before that is skipped (the build uses the size in
force then), and so is `doLayout`: `ISUIElement.setVisible(true)` lays out every child, so Esc in game (MainScreen
shown) reached stock `centerKeybindings` on the unbuilt screen, which failed on `keyButtonWidth` (nil until the build)
and aborted the pause menu's layout, leaving it without its buttons (shipped in 8cb8ccf..be1f28a, fixed in 3e0324c;
the `options_check` rig opens through `toUI` and never took that path). The deferral only happens while our wrapper is still the installed `MainOptions.create`; a mod that wrapped
it later gets the stock build. Flip (flip-lazymenu-*): main menu build 654 -> 410 ms, in-game menu 28.5 -> 2.3 ms, the
same key bindings with the screen deferred, and opening it builds the 119 stock options (28 ms) then the Optimizations
tab on activation. `--prop lazyOptionsScreen=false` restores the eager build.

Fixed 2026-10-06: the deferred path's `keysB42.ini` rewrite wiped every key binding. Stock writes the file from
`MainOptions.keyText`, which only `addKeybindingPanel` fills, so on the unbuilt screen the list was empty (at boot) or
left from another screen (in game), and the file kept only its `VERSION=2` line: the next load had every binding at its
default. `loadKeys` asks for the rewrite on the first launch with a new `options.ini` (`updateSneakButton`) and while Toggle
Health Panel and Vehicle Horn share a key. The deferred path now builds the same entries from `MainOptions.keys` (the rows
`loadKeys` just read; `addKeybindingPanel` makes one `keyText` entry per row, and the mod bindings it adds are the ones
stock skips) and writes them through stock's `MainOptions.writeKey`. Rig `harness/.../pzopt_harness_keybind.lua` with
`--option updateSneakButton=true` and `Map=key:66` seeded: before, 1 line left and Map back on M (`keywipe-before`); after,
96 lines / 84 bindings and Map on F8 (`keywipe-after`), byte-identical to the file stock's eager build writes
(`keywipe-stockref`, `--prop lazyOptionsScreen=false`).

Dropped (2026-09-23): building the main menu's other screens (server settings, sandbox options, character creation,
multiplayer, credits, spawn select) on first use. A `lua_wrap` profile had put them at ~600 ms, but that rig's per-call
overhead inflated them; without it the whole main menu builds in 378 ms eager vs 348 ms lazy on the flip
(flip-lazyscr-*), character creation still built during the menu (a trigger method is called there), and the screens
share first-time UI costs, so deferring one moves them to the next. Not worth depending on six vanilla screens' call
patterns. The `menu_check` harness rig from that test stays.


### pzopt.GcChoice (`gcMode`, `gcPauseMs`; not a game class, launcher JSON)

The game's launcher JSON starts the JVM with ZGC. Measured on the walk bench (2026-09-23, same build, two runs each):
4-core Dell G1 ~+10 % fps and half the frames over 100 ms; 12-core flip 145-147 -> 162 fps, p99.9 45-56 -> 36-42 ms;
M1 Pro Mac (at its 60 fps cap) p99.9 102-103 -> 87-91 ms; 16-core desktop at the 240 cap the same. G1 never lost, so
by the maintainer's decision `gcMode=g1` is the default: on boot, on AotCache's thread (the other launcher-JSON writer,
so the two never overlap), `-XX:+UseZGC` becomes `-XX:+UseG1GC` in the top-level and per-platform vmArgs for the next
launch, with the marker `-Dpzopt.gc=g1` (`-Dpzopt.gc=g1,pause` when `gcPauseMs` added a `-XX:MaxGCPauseMillis`). The
pause targets 25 / 50 ms measured inside the noise on all four machines, so the default adds none. `gcMode=stock`
(or `auto` above `gcG1Cores`) and the uninstallers (`reset_gc` in install.sh / scripts/pzopt.sh, `Reset-Gc` in
install.ps1) undo it by the marker. Harness runs keep choosing their own collector (`run.sh --gc`). The macOS app keeps
its collector in the signed bundle's Info.plist and is not changed. Checks: tests/pzopt/GcChoiceTest.java,
harness/gcchoice-check.sh (a real launch switches a ZGC JSON, reset_gc restores it).

Heap keys (2026-10-05, `docs/findings-gc-heap-2026-10-05.md`): `gcHeap` replaces the effective `-Xmx` (`auto`, the
default: 4096 MB, 8192 with `gcHeapAutoMods` (30) or more mods enabled in `mods/default.txt` or the last save's `mods.txt`;
`game` = the launcher's own; a size in MB; clamped to half the RAM), `gcHeapFixed` sets `-Xms` to it, `gcPreTouch` adds `-XX:+AlwaysPreTouch`, with
the marker `-Dpzopt.heap=<old -Xmx>,<old -Xms>,<pre-touch added 0|1>` that every undo reads. The default `auto` writes the heap on every optimized install;
on the tab with `gcMode` / `gcPauseMs` and `luaGcNoop` (Lua `collectgarbage()` returns at once instead of a Full GC; Lua only,
`src/lua/shared/pzopt/pzopt_lua_gc.lua`) in "Java memory and garbage collector". `run.sh` applies the same heap to every optimized run.

### Optimizations tab: off-screen rows draw nothing (Lua, 2026-09-24)

The UI draws every child of a scrolled panel each frame and lets the stencil drop what is outside it, so the
Optimizations page (~100 rows, ~340 controls: 95 tick boxes, 67 combo boxes, 174 labels a frame) cost ~8 ms a frame
on an M1 Pro against ~0.3 ms for the Display tab, and the D-pad took 17-24 ms to answer there (controller menu
profile, Mac runs `mac-pad-luaprof` / `mac-pad-cull`). `pzopt_optimizations_options.lua` now culls in the page's
prerender: a control more than 50 px outside the scrolled band gets no-op `prerender` / `render` (its own instance
functions are kept and put back when it scrolls in), recomputed only when the scroll band or the layout (search, fold,
sort) changed. The controls stay visible: `ISPanelJoypad` walks visible children only, so hiding them would take the
rows out of the controller navigation and `ensureVisible` could no longer scroll to them. Page frame 11.4 -> 4.1 ms,
D-pad response 18.5 -> 6.5 ms (p50); the D-pad visits the same rows in the same order before and after.

### Input latency (2026-09-24): zombie.input.* (new overrides), GameWindow, RenderThread, Display

Profiled with `run.sh --inputlag` (uinput keyboard + mouse + Xbox 360 pad driven by harness/inputlag-drive.py, every
stage stamped by the harness-only `pzopt.InputLag`, lined up by harness/inputlag.py). In stock, a press waits for the
render thread's next event pump (it pumps and polls only right after its buffer swap), the polled state is then frozen
until the game thread swaps it in at the start of its next frame, and the keyboard is a further frame late.

- **zombie.input.GameKeyboard** (new override, `keyboardFresh`): `update()` filled its key-down table from the using
  state and swapped in the new poll only at its end, so every key read (movement, hotkeys) saw the previous poll while
  the mouse, the pad and the keyboard's own text-event queue were already on the new one. With the key the swap moves to
  the top of the method; the per-key edge / Lua event logic is unchanged. 240 fps cap: key -> game 9.4 -> 4.6 ms.
  Also `pzoptRepoll()` for the latch below.
- **zombie.input.KeyboardStateCache, MouseStateCache, ControllerStateCache, KeyboardState, MouseState** (new overrides,
  `inputLatch`): each cache gets `pzoptRepoll()`, a poll that also runs when the polling state was already polled this
  frame. The key / button re-poll keeps a key or button that went down since the using state down (a tap between two
  polls is never lost), the mouse wheel adds up. `poll()` / `swap()` of the three caches carry a decompiler fix:
  Vineflower turned the jar's early returns inside the lock into if-blocks (one `monitorexit` fewer); restored.
- **zombie.input.Mouse** (new override, `aimHoldMs`): `isRightDelay()` compares the right-button hold with the key's
  seconds instead of the fixed 0.15 s (the hold that tells a context-menu right-click from aiming; stock value default).
- **GameWindow.logic**: `pzopt.InputLatch.beforeInputSwap()` before the Mouse / GameKeyboard / GameInput swaps (Reflex
  sleep, `frameStartGate`, then the latch request: the game thread wakes the render thread, which is idle waiting for
  the next frame, on the sprite-state monitor and waits up to `inputLatchWaitUs` for a fresh `glfwPollEvents` + re-poll;
  240 fps cap: mouse / pad -> game 5.2-5.7 -> 1.9-2.3 ms, 0.02-0.03 ms a frame of wait). Also the harness probe hooks
  (`InputLag.afterGameInput`, and in `frameStep` before `renderInternal` `InputLag.beforeRender`).
- **RenderThread**: `waitForRenderStateCallback` and the render loop after its input polls serve the latch
  (`InputLatch.serve`); `lockStepRenderStep` and `Ready` carry `pzopt.LowLatency` (GPU timestamp queries, the
  GPU-free prediction of `reflexSleep`, the frame counts) and the probe hooks.
- **Display.update / processMessages / setVSyncEnabled**: after the swap `LowLatency.afterSwap()` (`gpuMaxFrames`: fence
  the frame, wait until at most N frames are queued behind the GPU); the probe's event stage after `glfwPollEvents`;
  vsync on uses swap interval -1 with `vsyncAdaptive` when the driver has swap_control_tear.

NVIDIA Reflex has no OpenGL SDK; `pzopt.LowLatency` implements its parts with GL: frames-in-flight fences
(`gpuMaxFrames`, the driver's low-latency mode) and the just-in-time sleep before the input sample (`reflexSleep`).

Later the same day: `pzopt.LowLatency` gained `reflexCapFps` (frame starts no faster than refresh - refresh^2/3600
with vsync, what Reflex does: without it the vsync queue is bistable and refills) and `vblankLock` (RenderThread
`lockStepRenderStep` waits in `glXDelayBeforeSwapNV` before taking the next frame and lets the game start it; needs a
GLX server with GLX_NV_delay_before_swap, which XWayland lacks). `Display` gained `isVSyncEnabledPzopt()`;
**org.lwjglx.input.Mouse** gained `pzoptLatestX/Y()` (the newest pointer position, clipped like `poll`) and
**zombie.input.Mouse.renderCursorTexture** records its sprite through `pzopt.CursorLatch` (`cursorLatch`): with "Lock
cursor to window" the game draws its own cursor at the frame's mouse position, and the render thread moves that one
sprite to the newest pointer position right before it replays the frame (RenderThread `lockStepRenderStep`, before
`postRender`).

## Variable refresh: G-SYNC / FreeSync / ProMotion (2026-09-24; `borderlessFullscreen`, `vrr`, `vrrCap`, `presentPacing`, `limiterSleep`, `macPresent`)

Measurements and the reasoning behind every key: `docs/archive/2026-09-24/findings-vrr-2026-09-24.md`.

### org.lwjglx.opengl.Display (`borderlessFullscreen`, `macPresent`)

- KWin, Mutter and gamescope switch variable refresh on only for a window in the fullscreen state, and the stock
  borderless window is a screen-sized undecorated window, so it never got VRR. When the borderless window covers the
  monitor (`pzoptBorderlessFullscreenApplies`: overrides on, window size = the desktop mode, `borderlessFullscreen`
  auto on Linux / true everywhere), `createWindow` and `setDisplayModeAndFullscreenInternal` make it a GLFW monitor
  window at the desktop's own mode (`GLFW_REFRESH_RATE` = the monitor's rate, so no mode switch), with auto-iconify
  off so it stays up when focus moves away. The mode switch also attaches / detaches the monitor when only the
  borderless state changed (`hasMonitor != wantMonitor`). `isFullscreen()` stays false for that window
  (`pzoptBorderlessFs`), so options.ini keeps `fullScreen=false` and Core's switch sees the borderless state.
- `create`: starts `pzopt.Vrr` (DRM `VRR_ENABLED` poller).
- `update` (the swap): `pzopt.MacPresent.present` first; when the Metal bridge presented the frame, glfwSwapBuffers
  is skipped.
- `setBorderlessWindow`: on macOS with the bridge (`macNativeFullscreen`), borderless asks `MacPresent` to enter a
  native fullscreen Space on the next frame instead of removing the decoration.

### zombie.GameWindow (frame limiter: `presentPacing`, `macPresent`, `limiterSleep`)

- Each limiter step stamps its start and interval (`pzopt.Pacing.stepStart`, the game time the frame shows).
- `macPresentPhase`: before a step, `MacPresent.takeStepShiftNs` may ask to start it later (the frame was early for its
  panel slot: the limiter waits and the wait is not counted as frame time) or earlier (the accumulator is advanced).
- `limiterSleep`: the limiter parks until ~1 ms before the step instead of spinning the whole wait (same pacing, one
  core less busy); off by default.

### zombie.core.opengl.RenderThread (`presentPacing`, `pacingLog`)

`Pacing.onPush` pairs each pushed frame with its step start, `onAcquire` marks when the render thread takes it,
`beforeSwap` holds the swap until step start + a high percentile of the recent step-to-ready lag (`presentPacing`
cpu / gpu / gpufinish; auto = gpu while VRR is active) and stamps the swap call, `afterSwap` stamps its return and
writes the `pzopt-pacing.out` row. No change to what is drawn.

### pzopt.Vrr, pzopt.Pacing, pzopt.MacPresent, pzopt.FrameCap (not game classes)

- `Vrr`: libdrm through java.lang.foreign on a daemon thread (2 Hz), reads the CRTC `VRR_ENABLED` property of the
  output the window is on (no DRM master needed). `vrr=auto` acts while it is 1, `on` always, `off` never. The overlay
  line shows the state.
- `FrameCap`: while VRR is active and the player's cap is uncapped or above the range, the cap is refresh -
  refresh^2/3600 (`vrrCap`, 157 at 165 Hz; `vrrCapFps` overrides); a forced `uncappedFps=true` run stays uncapped.
  With the Mac bridge the cap snaps to a rate the panel shows exactly (4.17 ms steps in fullscreen / borderless /
  a native fullscreen Space, the refresh grid in a window). `frameCapFps` is a run key that locks game and menus.
- `Pacing`: GPU completion per frame from a GL_TIMESTAMP query read back a few frames later (never blocks), mapped
  onto System.nanoTime; the hold is capped at one cap interval per frame (throughput guard).
- `MacPresent` (Apple silicon, `macPresent`, off by default): the GL back buffer is blitted upside down (GL row 0 is the
  bottom, Metal's the top) into one of three IOSurface-backed textures, upscaled with `MPSImageBilinearScale` into a
  native-size CAMetalLayer drawable (a scale-1 layer is resampled by the window server and loses the exact timing) and
  presented with `afterMinimumDuration` = the cap interval; `presentedTime` goes back into `pzopt-pacing.out`. Any
  failure removes the layer and falls back to glfwSwapBuffers. Rig `devMacPresentCheck`: compares IOSurface rows
  with the GL back buffer and logs `mac present check: frame N upright|UPSIDE DOWN|MISMATCH`.

## zombie.iso.fboRenderChunk.FBORenderCell (edit of 2026-09-24, an upper level's rebuild replaces its own list entries)

Found while chasing the Workshop / issue #13 reports of the weather layer and the fog flashing when entering a
building and zooming (run `uiz-storm240`: storm, a 3 tiles/s walk through the Rosewood houses south of the bench
save, a zoom step every second): seven consecutive frames threw `ArrayIndexOutOfBoundsException` from
`pzopt.PuddleVbo.add` (a batch of 84 puddle squares on one chunk level, 64 at most) out of `renderInternal`, right
after a zoom step. The exception skips the rest of `performRenderTiles` for the frame: water, translucent floors,
rain splashes, translucent objects and the fog. It is in one run of the 1,148 on the desktop.

Cause: stock keeps the per-frame square lists per group of two levels (`FBORenderLevels.NLevels`) and clears them
only when the group's lower level is rebuilt (`clearCachedSquares(level)` under `level == getMinLevel(level)`).
With the lists kept across invalidations (`pzoptKeepPerFrameLists`, the 2026-09-20 entry above), an upper level
rebuilt without its lower level appended its squares to the entries it already had. Stock never meets this: its
invalidate() empties the lists before every rebuild.

Edit: after the stock lower-level clear, an upper level (`else if (pzoptKeepPerFrameLists())`) first drops its own
entries (`square.getZ() == level`) from the group's eleven lists (`pzoptDropLevelSquares`) and invalidates its
puddle batch, so its rebuild replaces them and the lower level's stay. The count goes into
`pzoptBakeCounters()` as `dupSquaresDropped`. `pzopt.PuddleVbo.add` also clamps a batch to 64 squares, as its draw
always did, so a long list can no longer throw out of the world pass.

## Compatibility with the PZMulticore agent (2026-09-24): zombie.iso.WorldStreamer, zombie.pathfind.PolygonalMap2 (new override)

PZMulticore (github.com/RealDoomSlaya/PZMulticore, a `-javaagent` that ASM-patches game classes at load; tested
branch `fix/42.20.4-strip-lighting`, v2.9.1-dev) did two things that broke next to our overrides on the Louisville
preset (runs `pzmc-lou-*`):

- Its `WorldStreamerPatcher` rewrites the one direct `DoChunk(chunk, null)` call in `WorldStreamer.threadLoop` into
  its own loader, which runs vanilla `IsoChunk.LoadChunk` on several threads at once. That path reads through the
  static `sliceBufferLoad` / `crcLoad` guarded by `SanityCheck.beginLoad`, so all but one concurrent load throw;
  the loader then drops the chunk and nothing requests it again. With `centerFirstLoad` the player's own chunk was
  among the dropped: `getCurrentSquare()` stayed null, the harness never reached world-ready and the world stayed
  empty (thread dumps: the World Streamer idle with an empty job list). It also bypassed our streamer (`parallel`,
  `RecalcPool`, `Stats`).
- Its parallel entity update runs zombies' `update()` on worker threads, and zombie code calls
  `PolygonalMap2.lineClearCollide` / `getCollidepoint` / `canStandAt`, which share one `LineClearCollideMain`
  (`lccMain`: the `pts` list, the vehicle rects, the `PointPool`). The agent synchronizes only `PointPool`, so the
  list was corrupted: worker buckets failed with "Cannot read field x because pt is null" / an
  `IndexOutOfBoundsException`, the agent's main-thread retry then hit `ECSEntity`'s "Double-update call", and its
  dispatcher failed every frame after that.

Edits:
- `WorldStreamer.threadLoop` calls `pzoptDoChunk(chunk)`, a private helper that calls `DoChunk(chunk, null)`. The
  agent's patcher requires exactly one direct `DoChunk` call in `threadLoop`, finds none, logs "Vanilla behavior
  preserved" and leaves chunk loading to our streamer. Same behaviour without the agent.
- `PolygonalMap2` (new override, Vineflower output, marker `onClassLoadedQuiet`): the bodies of
  `lineClearCollide(..., int flags)`, `getCollidepoint` and `canStandAt(..., BaseVehicle, int)` run under
  `synchronized (this.lccMain)`. Uncontended on the stock single-threaded path (one lock acquire per call); the
  debug-render use in `render()` is left as is (game thread only, after the agent's workers joined).
- Decompiler fix in the new `PolygonalMap2` override (bytecode audit): `cleanPath` resets `dxOld` / `dyOld` with the
  jar's chained store `dxOld = dyOld = -123` (one `bipush -123; dup`); Vineflower rendered two assignments.
- Two pzopt per-frame caches that zombie `update()` reaches assumed the game thread; with the agent's workers they
  raced (run `pzmc-fix-all3`: a `ConcurrentModificationException` and "vehicle is null" in `BaseVehicle.getScript()`
  in two worker buckets). `pzopt.VehicleCull.near` (`zombieSpotFast`) keeps the game thread's list and gives any
  other thread its own (`ThreadLocal`), same rebuild rules (`VehicleCullTest.nearPerThread`);
  `pzopt.SeparateMask.blocked` (`separateFast`) answers `isBlockedTo` directly off the game thread (its
  direct-mapped table has no locking; a torn slot would hand one square another's answer).
- `WorldSoundManager` (run `pzmc-f3-ours-mc`: "Cannot read field source because sound is null" in a worker bucket):
  stock `addSound` already adds under `synchronized (soundList)` (the global and the chunk lists; `IsoChunk` removes
  under it too), but the readers the zombie update calls did not take it. `getSoundZomb`, `getSoundAnimal`,
  `getBiggestSoundZomb` and `getStressFromSounds` now scan under the same lock, and `getBiggestSoundZomb` returns the
  stock shared `resultBiggestSound` on the game thread and a per-thread one elsewhere (`pzoptResultBiggestSound`).
- Seen once in eight runs, not addressed: `VehicleSoundOwner.hasAlarm` NPE (a vehicle without a script) in
  `VehiclesDB2.unloadChunk` on the streamer thread right after the harness teleport (run `pzmc-f3-ours-zb`); not on
  an agent worker and not in our code.

## zombie.iso.IsoWorld.sceneCullZombies: zombie detail follows the frame cap (`zombieLodDynamic`, 2026-09-24)

Stock gives the 510 nearest on-screen zombies a 3D model (the rest are scene-culled to the flat atlas sprite) and the
first `PerformanceSettings.numberZombiesBlended` (20) of them animation blending, whatever the frame rate. With
`zombieLodDynamic` (default off, maintainer's request) both counts follow the frame cap through `pzopt.ZombieLod`:
one level 0..1 maps onto `zombieLodMin3d .. 510` models and `zombieLodMinBlend .. numberZombiesBlended` blended;
every frame's game-thread step (`FrameCap.lastStepNs`, the limiter's wait excluded) is kept, and every 250 ms the
level drops (8-24 %, more the further over; then no climbing for 2 s) when the window's median is above 97 % of the
cap's budget (`FrameCap.lockNow()`), climbs 3 % when its 90th percentile is below 85 %, and holds otherwise (a first
version on an 8-frame average swung between full detail and the floor every few seconds on Louisville). Uncapped the target is
`zombieLodUncappedFps` (0 = stock detail). A console line every 5 s: `zombie lod: level .., 3d .., blended ..`.

Edit: stock's unused local `tcountMax` becomes the dynamic model cap (`tcount < 510 && tcount < tcountMax`) and the
blend test reads a local that is `numberZombiesBlended` unless the key lowers it. Both `510` literals stay, so
ZBBetterFPS' cull-cap transformer (it rewrites exactly two `SIPUSH 510` sites and disables itself otherwise) still
applies, and its lower counts win. `tests/pzopt/ZombieLodTest`.

## Sound engine pass (2026-09-24; `emitterIdleSkip`, `soundTickHz`, `audioLimiter`, `audioLimiterCeilingDb`, `audioLimiterStereoFold`)

Scene: `--preset louisville --flag weather=storm --flag house_alarm=12 --flag car_alarm=8 --flag gunshots=2|8
--flag helicopter=true` (a horde of ~1,800-2,300 zombies, a thunderstorm, a house and a car alarm, gunfire, the
helicopter), recorded with the game's own audio stream (`--record-audio game`) and judged by `harness/audio-judge.py`
(Jev). Findings and numbers: `docs/findings-sound-2026-09-24.md`.

### fmod.fmod.FMODSoundEmitter (new override, `emitterIdleSkip`)

Vineflower output of the jar class, unchanged except for the quiet class-loaded marker and one call at the two
places a sound enters the emitter's start list (the event path and the file path of `addSound`): when the emitter's
parent is a character, the character's new `pzoptSoundBusy` flag is raised. Nothing else can put a sound into an
emitter: `instances` is only filled from the start list and `stopped` only from `instances`.

### zombie.characters.IsoGameCharacter (fourth edit, `emitterIdleSkip`, `soundTickHz`)

`updateEmitter` asked each zombie's three FMOD emitters (vocals, footsteps, extra) — nine list checks — every frame,
and every 30 ms ticked the three empty emitters to move their position (2.8 % of the game thread on the horde, the
leaf `FMODSoundEmitter.isEmpty`). A zombie that has run the stock path once with a tick (primed: its emitters carry a
position, which a file sound queued on an unpositioned emitter would read to pick 2D) and whose flag is down now
returns at once; a busy zombie takes the stock path, and after its tick the flag is set to whether any of the three
emitters still holds something. Only zombies with a `CharacterSoundEmitter` are ever primed; players and animals keep
the stock path. The parameter refresh of a busy zombie additionally waits for a `pzopt.SoundTick` frame unless a
sound is about to start (which always gets fresh values, as the start reads them). Measured 2.81 % -> 0.66 % for the
emitter path, sound code 5.26 % -> 2.82 % of the game thread (runs `snd-i1-off` / `snd-i1-on`).

### zombie.audio.ObjectAmbientEmitters, zombie.audio.FMODAmbientWalls (new overrides, `soundTickHz`)

Vineflower output of the jar classes with the class-loaded marker; `update()` returns on a frame that is not a
`pzopt.SoundTick` frame. Both rebuild their slot assignment from scratch on every call (nearest emitters, sort, start
/ stop), so skipping calls only delays a start or stop to the next tick. FMOD Studio applies what they set on its own
update every 20 ms; the default 60 Hz tick is faster than that, and under 60 fps every frame is a tick.
`ObjectAmbientEmitters.stopNotPlaying` also reports each slot it stops to `pzopt.SoundProbe.ambientStop` when the
measurement key `devAmbientSlotLog` is on (off by default; the rain-gap hunt's `# ambient stop` lines).

### zombie.AmbientStreamManager (edit, `soundTickHz`)

The block that refreshes the listener's ambience parameters (weather, zones, walls, inside, room type, ...) runs on
`pzopt.SoundTick` frames; the power supply update before it and the alarms, world emitters and world ambiance after
it stay per frame (the alarm's world sound is gameplay).

### zombie.GameWindow (edit, `audioLimiter`)

The block that runs once FMOD is up (`BootAsync.afterFmod`) first calls `pzopt.AudioLimiter.install()`: FMOD's
limiter DSP is created through the core C API (FFM into `natives/libfmod.so`) and added at the head of the master
channel group, checked by the name FMOD reports. Stock mixes 5.1 at 32 kHz on every device
(`libfmodintegration64`'s `FMOD_System_Init` hardcodes `SetSoftwareFormat(32000, FMOD_SPEAKERMODE_5POINT1)`); on a
stereo device the OS mixer adds the six channels up into two, after FMOD, and that sum clipped: FMOD's own output
stayed under 0.75 per channel while the recording held 6,000-19,000 samples at full scale in 25 s. So on a stereo
device (`FMOD_System_GetDriverInfo`) the limiter takes stereo input (`FMOD_DSP_SetChannelFormat`,
`audioLimiterStereoFold`): FMOD folds 5.1 to stereo ahead of the limiter and only the front pair reaches the OS
mixer; a 5.1 / 7.1 device keeps the per-channel limiter and its surround. Ceiling `audioLimiterCeilingDb` (-2 dBFS:
-1 left single samples over after the 32 -> 48 kHz resample), 50 ms release, linked channels, no make-up gain.
Stock clipped / optimized: 6,161 / 0 samples (pistol), 19,113 / 0 (assault rifle at 8 shots/s).

### zombie.WorldSoundManager + zombie.iso.IsoChunk (sound pass, second round: `worldSoundCleanupFast`, `hearingHoist`)

`WorldSoundManager.update` swept every loaded chunk's list of world sounds each frame and removed the expired ones
with one `ArrayList.remove` shift each (0.35 % of the game thread with alarms, thumps and gunfire). A world sound is
born with life 16 (`WorldSound.init`; only the copy constructor can give another value) and every sound in the global
list loses one per update, so (1) no chunk list can hold a dead sound after a frame in which none reached 0: the sweep
is skipped then (`pzoptDeadPending`); and (2) while every sound was born with 16, each chunk list, filled in creation
order by `addSound`, holds its dead sounds as a prefix: `IsoChunk.updateSounds` trims the leading dead entries and
stops at the first live one. The first sound added with another life (`pzoptUniformLife`, set in `addSound`) turns the
trim off for the session; the fallback is a one-pass order-preserving compaction. Lists, order and timing are stock's:
the sweep still happens before the dead sounds are released to the pool. Rig `devWorldSoundCleanupCheck`: on skipped
frames every chunk list is scanned for dead entries, and after a trim the rest of the list is (run
`snd-l2-ws-prefix-20260924-164104`: 5,527 sweeps, 16 skipped, 0 dead found either way). Measured 0.35 -> 0.17 %.

`getBiggestSoundZomb` (a zombie looking for the loudest sound around it) evaluated the zombie's hearing multiplier
(sandbox hearing x worn items x weather: pure reads) and looked up the zombie's own square once per candidate sound; both
are now taken once per call, the square at the first sound that needs it. Same float products, same squares.
Measured 0.33 -> 0.10 % (runs `snd-l2-ws-off` / `snd-l2-ws-on`).

### HDR output (`hdr` and the `hdr*` keys, 2026-09-24; pzopt.Hdr, HdrWayland, HdrLight, HdrFlash, HdrMac)

All hooks are no-ops unless `hdr=true`; findings and numbers in `docs/findings-hdr-2026-09-24.md`.

- **zombie.core.opengl.ShaderUnit** (new override): the unit's processed source passes through `pzopt.Hdr.patchShader`
  right before it is handed to the driver. For the world composite (`screen.frag`) the stock `main` is renamed and a new
  `main` calls it, then expands the finished SDR pixel to HDR (light-map gain, night ITM, lightning, bloom), still
  gamma-encoded relative to the UI white. The patched source is test-compiled first; if the driver rejects it the stock
  source is used and a warning is logged (the first try went black because the game's util/math.h hides the builtin
  `max(vec3, float)`). Every other shader is untouched; on macOS nothing is patched.
- **org.lwjglx.opengl.Display**: `init` selects the Wayland platform when HDR is asked for on a Wayland session (the
  colour-management protocol does not exist for X11 windows); `create` asks GLFW for a 16-bit float default framebuffer
  after the default hints and, once the context is current, lets `Hdr.windowCreated` check the back buffer is float and
  attach the HDR image description to the window's wl_surface; `swapBuffers` first runs `Hdr.beforeSwap` (the encode
  pass: SDR-encoded extended frame -> extended linear with the output's own luminances) and, on macOS, presents through
  `HdrMac` (an EDR Metal layer) instead of glfwSwapBuffers. On macOS (and Windows) `create` asks for 8 alpha bits
  instead: Core's `PixelFormat(32, 0, 24, 8, 0)` has none, and the alpha-carried world gain needs them (2026-09-24,
  first Mac run). HdrMac also copies the SDR frame into GL_FRONT each frame, because Core's screenshot reads GL_FRONT and
  nothing is swapped any more.
- **zombie.core.textures.MultiTextureFBO2.render**: before the composite quads, the world passes are queued (average
  luminance mip chain, bloom chain; the light map is built on a worker and queued from here); after them, on 8-bit back
  buffers (macOS), the alpha-only pass that carries the world gain in the back buffer's alpha.
- **zombie.iso.weather.WeatherShader.startRenderThread**: after the stock uniforms, `Hdr.worldUniforms` sets the
  expansion's uniforms on the bound composite program (glProgramUniform from another pass never reached it).

### Ragdolls stay on the game thread; harness showcase input hooks (2026-09-24)

- **zombie.core.skinnedmodel.animation.AnimationPlayer** (fix): with `animatorParallel` a zombie's animator and track tick
  run on a frame worker. A zombie shot (or otherwise knocked into a ragdoll) during the batch started its ragdoll track on
  the worker, and `updateRagdoll` then created and stepped its `RagdollController` there. Both call the game's Bullet
  library, which is not thread-safe: `btDiscreteDynamicsWorld::calculateSimulationIslands` SIGSEGV on the game thread in
  2 of 6 runs of a burning horde being shot (stock 0 of 2). `pzoptBatchable()` now also refuses any animation player with
  a ragdoll track (from the next frame the game thread runs it), and a worker that meets a ragdoll track without a
  controller skips the ragdoll step for that one frame. `releaseRagdollController` / `initRagdollController` /
  `updateRagdollInternal` call `pzopt.AnimParallel.noteRagdoll`, which logs (first 5, with the stack) any of them off the
  game thread; 0 in 4 runs after the fix.
- **zombie.characters.IsoGameCharacter** (`pzoptRestWorker`, 2026-09-24, player report: `ArrayIndexOutOfBoundsException` in
  `CollideWithObstacles.getIntersection` from `AnimParallel`, ragdolls sliding / spinning / flying): the worker task now
  stops right after the animator when the zombie's multitrack holds a ragdoll track, and the zombie finishes on the game
  thread in queue order through the serial path (`AnimCapture.ragdoll`, counter `serialRagdoll=`). The fix above left the
  model update of that frame on the worker with the ragdoll step skipped (the ragdoll started one frame late); the
  reported stack is from a release before it, where `initRagdollController` → `onRagdollSimulationStarted` →
  `slideAwayFromWalls` → `PolygonalMap2.resolveCollision` ran on eight workers sharing one collision scratch. Checked with
  the `horde-shoot` bench (`harness/ragdoll-judge.py`, `pzopt.RagdollWatch`): at matched fps the optimized ragdolls are
  stock's (0 abnormal episodes either side), 0 ragdoll calls off the game thread.
- **zombie.input.Mouse** (harness only): `getXA/getYA/getX/getY` return `pzopt.Showcase.aimXA/aimYA` while the showcase
  aims, and `update()` reports the right button held and the left one from `Showcase.fireDown` while `holdButtons`, so the
  game's own aim / attack / recoil / fire-mode path runs as for a player holding the mouse. Off (one volatile read) outside
  `showcase=horde`.
- **zombie.input.GameKeyboard.isKeyDown(int)** (harness only): reports the key codes `pzopt.Showcase` holds (the movement
  keys and Run of the director's `run_to_pier`).

## E-core pass: hybrid-CPU placement, AMD GPU clock, limiter sleep, vision-blur split (2026-09-24; `corePlacement`, `gpuPstate`, `limiterSleep`, `lightingSyncPark`, `visBlurReduce`, `jitSteady`)

Goal (maintainer): on the flip (Ryzen AI 9 HX 370: 4 Zen 5 + 8 Zen 5c, Radeon 890M) a 120 fps capped run in the
balanced power profile as close to 12 W as the game can get. Findings in `docs/findings-ecores-2026-09-24.md`.

### zombie.GameWindow (frame limiter)

- `limiterSleep` now calls `pzopt.Pacing.limiterWait(step)`: one park until `limiterSpinUs` (200 us; 1500 on Windows)
  before the step, the stock loop spins the rest. The old path (`waitUntil(step - 1 ms)`) parked to 2 ms before the step
  and spun 1 ms inside the helper plus 1 ms in the stock loop, a quarter of a core at 120 fps. The limiter thread's timer
  slack is 1 ns on Linux (prctl, first wait). `limiterSleep` is on by default except on Windows (a park wakes on the 1 ms
  tick there).

### zombie.core.opengl.RenderThread

- `pzopt.GpuPstate.gpuBegin()` / `gpuEnd()` next to the overlay's timer hooks around `SpriteRenderer.postRender()`: two
  GL_TIMESTAMP queries per frame (no nesting limit, unlike the overlay's GL_TIME_ELAPSED), read a few frames late, feed the
  `gpuPstate` governor on the render thread. No change to what is drawn.

### org.lwjglx.opengl.Display

- `sync(fps)`, called only by the lighting thread, goes to `pzopt.LightingSync.sync` (`lightingSyncPark`, default on):
  a park straight to the next update instead of LWJGL's 1 ms sleeps + yield-spin of the last millisecond. The first call
  also marks the thread as background work (macOS QoS).

### zombie.vispoly.VisibilityPolygon2 (`visBlurReduce`)

- `renderToScreen`: before the blur shader starts, `pzopt.VisBlur.reduce(blurTex)` runs a pass over the half-resolution
  vision texture that writes, per texel, the stock shader's 25-tap alpha sum (the loop copied verbatim by
  scripts/build.sh into `pzopt_visBlurReduce.frag`) into an R32F texture; the screen pass then uses
  `pzopt_visibilityBlur` (the stock shader with the loop replaced by one texelFetch of that sum, sampler `reduced` on unit
  2). The stock sum depends only on the integer vision texel a fragment maps to, so the result is the same; at the widest
  zoom the stock pass read 25 texels for each of ~13 M world pixels. Pitfall: `TextureFBO` allocates its colour
  texture as RGBA8 whatever the Texture asked for, which clamped the sum to 1 and removed the shadow (first build);
  the pass re-specifies the texture as R32F after creating the FBO. One player only; any failure (shaders missing or not
  compiled) keeps the stock pass for the session.

### zombie.iso.WorldStreamer

- The streamer thread calls `pzopt.CorePlacement.background()` first (macOS: utility QoS; no-op elsewhere).

### zombie.iso.IsoWorld, zombie.iso.fboRenderChunk.FBORenderCell (GPU sections only)

- `gpuSections` timestamps added around the body/item atlases (`atlas`), the cell render (`cell`), `performRenderTiles`
  (`tiles`), the on-screen chunk loop (`chunks`), players + corpse / mannequin shadows (`players`), animated attachments +
  flies + highlight (`attach`), the per-level loop (`zloop`), its character shadows (`shadows`) and the vision cone
  (`vispoly`). Measurement only (`gpuSections=true`).

### pzopt classes (not game classes)

- `CorePlacement` (`corePlacement=auto|efficient|performance|off`, Linux affinity by thread name via FFM
  sched_setaffinity; macOS QoS classes for threads we own; `coreBackgroundCpus`, `corePromotePct`, `coreDemotePct`,
  `coreHoldMs`). Hooked from `FrameCap.stepDone`.
- `GpuPstate` (`gpuPstate=auto|off|standard|min_sclk|min_mclk|peak`, `gpuPstateFitPct`): `AMDGPU_CTX_OP_SET_STABLE_PSTATE`
  on a context of its own on the render node (no root; released by the kernel when the context closes).
  2026-09-28 (Steam Deck player log, 135 switches in 400 s): the step-down estimate was the flip's clock guess
  (min_sclk 1.28x standard) while the Deck's min_sclk was 4.9x, the lower rung's failed measurement was cleared on the
  next check, the back-off reset whenever the upper rung had headroom, and the first window after a switch still held
  the old level's frames (min_sclk's 22.5 ms pushed standard up to automatic too). Now `GpuPstate.Governor` measures
  each rung's slowdown on its first window after a step down and uses it for the next step-down estimate, judges only
  frames submitted after the switch, and resets the back-off only after a level held a minute; a slowdown is only
  learned from an upper window of at least 1 ms and clamped to 1-8x, and a world is judged from 3 s in (the flip's first
  run stepped down on the 0.26 ms loading frames and learned 148x from the 38.7 ms storm behind them); `GpuPstateTest`
  simulates the Deck (one min_sclk try, then standard) and the flip (down to min_sclk and stays).
- `GcChoice` also writes `jitSteady` (`-XX:PerMethodTrapLimit=0 -XX:PerBytecodeTrapLimit=0`, marker
  `-Dpzopt.jit=steady`) into the launcher JSON; scripts/pzopt.sh, install.sh and install.ps1 remove them with the G1 switch.

## Ambient occlusion (`ambientOcclusion`, `aoMode`, `ao*`, 2026-09-24; pzopt.ChunkAo, pzopt.AmbientOcclusion)

New visual feature, off by default: soft occlusion where surfaces meet (wall bases, room corners, furniture, stairs,
fences, bushes). The FBO renderer's depth is linear in world space (`IsoDepthHelper`: `C - (x + y + 2z) *
SQUARE_DEPTH / 2`) under an orthographic 2:1 projection, so a depth texel gives an exact view-space position (a
square is `32 sqrt(2) tileScale / zoom` screen pixels across, a unit of depth is 424.27 squares along the view). The
kernel is ground-truth-style horizon AO with 32-sector visibility bitmasks (Therrien et al. 2023: each sample covers
the sectors between its front and an assumed back `aoThicknessPct` behind it, so thin posts occlude as little as they
cover), in trig-free form (sector index from `sin(angle - n)` by dot products), with normals snapped to the three
planes tiles are made of (ground, east-facing wall, south-facing wall), a 0.04-square height bias and pit filling for
the one-row depth steps tile edges have, a 4x4 Bayer rotation of two slices and a 4x4 depth-aware box over it.

### zombie.iso.fboRenderChunk.FBORenderCell

- At the end of a chunk-level bake (top level, after the tree pass, before `endRenderChunkLevel(..., true)`):
  `pzopt.ChunkAo.bakeEnd(renderChunk, c, playerIndex, zoom, geometryDirty)`, while the texture's framebuffer is still
  bound. A new texture, or a bake whose dirty flags change the depth (all but lighting, blood and redraw), computes its
  AO there (a new texture always, `aoArrivalInBake`; a changed one up to `aoBakeBudget` a frame) and multiplies it in; a lighting-only re-bake multiplies the kept R8 AO in.
  The kernel reads the texture's depth and its eight neighbours' of the same level pair and zoom (each at its composite
  offset and chunk depth offset). The stock mipmap build at the bake's end then carries the AO into every level.
- Before the composite, after the tree appends: `pzopt.ChunkAo.flush(playerIndex)` runs at most `aoComputeBudget` (4)
  deferred computes (over the bake budget, or neighbour refreshes) and applies `new / old` onto the texture (blend
  `DST_COLOR, SRC_COLOR` = 2 src dst, so it darkens and lightens) with the mip levels the current zoom / render scale
  samples (`glGenerateMipmap` costs ~65 us per 1024 texture on NVIDIA); zooming out past those re-bakes the textures
  (lighting only). A texture's first compute queues a refresh of the neighbours computed without it, when something
  but floor stands on its border squares facing them. The first multiply after a compute runs under an occlusion
  query; a texture with no occlusion skips its later multiplies.
  Under a frame cap the computes per frame follow the last frame's slack (`FrameCap.lastStepNs` against the cap, ~60 us
  a compute), with none in bakes after a frame that missed the cap (`aoSkipSlowFrames`): fixed budgets added ~1.4 ms at
  p99 on the capped 120 km/h drive, the gated version is at parity. A chunk whose texture's levels hold nothing but floor
  (no attached sprites either: grass and bushes hang off the floor object) and whose neighbours' facing borders are bare
  is skipped.
- `aoMode=screen` (the first version, kept for comparison): right after `FBORenderChunkManager.endFrame()`,
  `pzopt.AmbientOcclusion.queue(playerIndex)` runs the same kernel on the scene depth every frame (when the static scene
  changed) and multiplies the scene. It cost 90-140 us a frame on the desktop, which is why the chunk mode exists.
- `changed()` markers on the bake and the tree appends feed the screen mode's reuse; the periodic stats line prints
  both modes' counters.

### pzopt.FogPass.sceneDepthAsTexture

The offscreen depth becomes a texture also when `ambientOcclusion` with `aoMode=screen` is on (the screen mode reads
it in place like the fog pass).

## Town drive pass: one bake budget, cheaper bakes, fewer driver syncs (2026-09-24/25; docs/findings-town-drive-2026-09-24.md)

### zombie.iso.fboRenderChunk.FBORenderCell

- `bakeScheduler` (pzopt.BakeScheduler): `renderTilesInternal` makes the frame's bake grants right before
  `prepareChunksForUpdating` (`pzoptSchedulePlan`): every dirty on-screen chunk level is offered once with a class (an
  existing texture whose objects / items / trees / obscuring changed = must; never-textured = arrival; cutaway; strong
  lighting (pzopt.LightDirt); redraw; lighting drift, which is still held `lightingRebakeMs` since its last bake) and its
  chunk's distance to the camera character. A level last found fully occluded is not offered while the occlusion stays as
  it was (only the stored count is read: the plan runs before this frame's occlusion pass, and writing a count from last
  frame's grid left levels at 0 visible squares for good). In `renderOneLevel` the scheduler's grant replaces the per-kind
  budget decision for the lower level of a texture (the upper level follows it as before); a level not granted takes the
  existing deferral path (previous texture, or nothing for a never-textured one). `prepareChunksForUpdating` skips a level
  that holds no grant but has a texture (its square flags from the last preparation match what is on screen); a
  never-textured level is always prepared, since the occlusion count reads those flags. `pzoptHasDirtyChunkTexturesForOcclusion`
  looks only at granted levels (`occlusionGrantedOnly`). The scheduler is off during a zoom flood and the resume-shot capture.
- `bakeMipLevels`: the first bake of each render chunk queues pzopt.BakeMips (GL_TEXTURE_MAX_LEVEL on its colour texture).
- `renderChunkTopUp`: `checkNewlyOnScreenChunks` keeps that many free render chunks of the current texture size in the
  pool, a few a frame; their GL objects are made on the render thread through pzopt.GlTask (a `TextureFBO` built on the
  game thread waits for the render thread).
- `seamSpread` / `seamDirections` (both off): `checkSeamChunks` queues a baked neighbour's seam re-bake (pzopt.SeamSpread),
  or marks it with the pzopt dirty bit `BakeScheduler.DIRTY_SEAM_LOW` when the chunk that loaded is not south or east of
  it (the only squares SeamFix2 reads); the scheduler bakes that bit with lighting drift.
- `occlusionRetain` (off): a fully occluded level keeps its texture and dirt instead of clearing and freeing them.
- `compositeShaderRun` (off): `pzoptCompositeChunks` replaces `FBORenderChunkManager.endFrame`'s non-combined path with
  the same per-chunk composite (`pzoptCompositeOne` = `FBORenderChunk.renderInWorldMainThread`) and one `EndShader` after
  the last chunk instead of one per chunk.
- Instrumented runs: `pzopt.BakeLog` (per-frame bake census and one row per bake, `pzopt-bakes.out`), GPU sub-sections
  `bake.trees` and `bake.end` (`gpuSections`).
- The periodic counters line carries the `glNoSync` counters (`pzopt.GlNames.summary`).

### zombie.FliesSound (new override, `fliesToggleFix`, default on)

- `ChunkLevelData.update`: with an emitter already playing, the old square's `setHasFlies(false)` is skipped when the flies
  square stays the same (it is set back to true a few lines later, nothing reads it between). Each toggle dirtied the chunk
  level (object remove + add, never held), and the update runs for the 3x3 chunks around the player whenever any chunk
  loads: an immediate re-bake next to the player every few frames while driving.
- Decompiler fix: `ChunkData`'s constructor creates `ChunkLevelData` unqualified (Vineflower's `FliesSound.this.new` added
  a `requireNonNull` the jar does not have).

### zombie.iso.IsoChunk

- `checkAdjacentChunks` records on each neighbour which side the loaded chunk is on (`pzoptSeamDirs`, `seamDirections`);
  reset with the other per-chunk stamps on reuse. `invalidateRenderChunkLevel` can log the stacks of object add / remove
  invalidations (`devInvalidateStacks`, pzopt.DevStacks). Chunk reuse also clears pzopt.BakeScheduler's waits.

### zombie.iso.IsoChunkMap

- The chunk hand-off is timed in instrumented runs and may wait for a frame with headroom (`chunkHandoffSlack`, off;
  pzopt.ChunkHandoff).

### zombie.core.VBO.GLVertexBufferObject (`persistentVboFrameSync`, `persistentVboTrustFrames`)

- With `persistentVboFrameSync` the persistent sprite buffers are fenced per frame (`pzoptFrameEnd`) instead of per 64 KB
  batch: `pzoptMapPersistent` no longer fences the previous batch, and a slot last drawn in an earlier frame waits for that
  frame's fence only when that frame is not yet known done and was drawn fewer than `persistentVboTrustFrames` frames ago
  (the swap chain caps the frames in flight at 2-3); a slot drawn earlier in the same frame waits for a fence set now.

### zombie.core.textures.TextureDraw

- `run`: `uniformCache` (off) resets pzopt.UniformCache on every command other than a shader start; a start of the same
  program as the previous command skips `TileDepthShader.startRenderThread`'s sampler setup (only its MVP update runs) and
  sends the uniform chain through `ShaderUniformSetter.pzoptInvokeAllCached`. Instrumented runs count shader starts per
  program inside and outside chunk bakes (pzopt.DrawStats).

### zombie.core.opengl.ShaderUniformSetter (new override, `uniformCache`)

- `pzoptInvokeAllCached`: the chain of a shader start, skipping every 1f / 1i uniform whose value the program already holds
  (pzopt.UniformCache); other shapes are sent and forget their cached location.

### zombie.core.textures.TextureID (new override, `glNoSync`)

- `generateHwId`: the texture name comes from `pzopt.GlNames.texture()` (a render-thread pool refilled after the swap;
  any other thread, or `glNoSync` off, calls `glGenTextures` as before).
- Decompiler fix in `getData`: `glBindTexture(3553, Texture.lastTextureID = 0)` as the jar (Vineflower wrote the
  assignment and a second constant).

### zombie.core.textures.TextureFBO (new override, `glNoSync`)

- `initInternal`: the framebuffer name from `pzopt.GlNames.framebuffer` (GL 3.0 framebuffers on the pool's thread only);
  `glCheckFramebufferStatus` is skipped for an attachment shape (colour size, depth texture size or renderbuffer, stencil)
  that already came out complete; a first one is checked and recorded after the stock error handling.
- Decompiler fixes (`reset`, `initInternal`): the jar binds the assigned value (`lastID = 0`, `Texture.lastTextureID = 0`).

### zombie.core.skinnedmodel.DeadBodyAtlas (new override, `glNoSync`)

- `toBodyAtlas`: the program to restore is `pzopt.GlState.currentProgramNoSync()` (ShaderHelper's record, as
  `weatherNoGlGet` does for the weather particles) instead of `glGetInteger(GL_CURRENT_PROGRAM)`, a driver round trip per
  corpse drawn into the atlas (19 % of the render thread's late-frame samples on the drive at upscaler=off).
- Decompiler fixes in `toBodyAtlas`: the two `glBindTexture(3553, Texture.lastTextureID = 0)` as the jar.

### zombie.core.opengl.RenderThread

- After the swap: `pzopt.GlNames.refill()` (`glNoSync`), one batched `glGenTextures` / `glGenFramebuffers` when a pool
  is under half, while the render thread waits for the next frame anyway.

### zombie.iso.RoomDef (new override, `kidsRoomMemo`, default on)

- `isKidsRoom`: while a trashed-house pass is open (`pzopt.KidsRoom`, game thread) the answer is kept per room; the stock
  body moved to `pzoptIsKidsRoom`, its 19 tile names from a static set (`KidsRoom.TILES`) instead of a list built per call.
  `devKidsRoomCheck` rescans every memo hit (689 checked, 0 mismatches).

### zombie.randomizedWorld.randomizedBuilding.RBTrashed (new override, `kidsRoomMemo`)

- `randomizeBuilding`: `trashHouse(def)` runs between `KidsRoom.begin()` and `end()` (the pass is timed with the memo
  off too). `trashHouse` destroys doors, smashes windows, moves container items and adds graffiti overlays, none of them a
  kids-room tile, so a room's answer holds through the pass.

### zombie.iso.LightingJNI (`lightingNewChunkBudget`, default 1)

- `update`: at most `lightingNewChunkBudget` never-lit chunks (`lightingNeverDone`) go through `updateChunk` a pass, in the
  centre-first order; the others keep `lightCheck` for the next pass. Applies only in steady state: more than
  `lightingNewChunkBacklog` (8) never-lit chunks waiting (a load, a teleport) switches it off until a pass finds none,
  so a load lights every chunk at once as stock (time to a fully lit world 3.41 / 3.03 vs 3.35 / 3.04 s).

### zombie.iso.fboRenderChunk.FBORenderCell (structural pass)

- `occlusionCountParallel` (default on): right after the occlusion grid is built, `pzoptPrecountRenderedSquares` counts
  every on-screen level's rendered squares on the FrameBatch workers (`pzoptCountRendered`, `pzoptOccluded`: the stock
  `FBORenderOcclusion.isOccluded` test on locals, since the stock one writes `testValue`), and `renderOneLevel` skips its
  own count that frame. `devOcclusionCountCheck` recounts on the game thread (1,214,010 checked, 0 mismatches).

### pzopt classes (not game classes)

- `GlNames` (`glNoSync`): the name pools and the complete-shape set; `TreeBake`'s append check reads the render
  chunk the render thread bound (`FBORenderChunkManager.renderThreadCurrent`, whose FBO keeps one colour texture for life)
  instead of `glGetFramebufferAttachmentParameteri` (0 refusals in 402 recorded runs with the query); `devGlStateCheck`
  still asks the driver and counts disagreements.
- `BakeScheduler` `bakeSmooth`: the normal tier grants the demand bound (the most over d of ceil(levels due within d
  frames / d)) in deadline order, under the adaptive budget.
- `BakeScheduler`, `BakeMips`, `BakeLog`, `SeamSpread`, `ChunkHandoff`, `GlTask`, `DrawStats`, `UniformCache`, `DevStacks`.
- `GpuSections` logs every section pair with its render-thread issue time (`pzopt-gpusections.out`, instrumented runs).
- `Pacing.lastSubmitNs` (the render thread's acquire-to-swap time before any hold) feeds `bakeBudgetAdaptive`.
- `Upscaler.savedState` / `boundFramebuffer` (`upscaleNoGlGet`, off): the resolve takes the bound framebuffer from
  `TextureFBO.lastID` and the viewport as the screen instead of asking the driver (0 disagreements with `devGlStateCheck`).

## Per-pixel lighting (`pixelLight`, `ppl*`, 2026-09-25; pzopt.PixelLight, pzopt.FrameCapture)

New visual feature, off by default (`docs/findings-per-pixel-lighting-2026-09-25.md`). The chunk textures bake unlit and
the light is composed per pixel by the chunk composite shader: the scene depth after the composite is linear in
x + y + 2z, so a pixel's screen position and depth give its exact world position. Per square the native's own light
(`lightInfo`, the sample the corners are the max of) is interpolated between square centres across neighbours that share
their corner colours (the native breaks them at walls); handheld torches are taken out of it and drawn from an analytic
cone fitted to the native's per-square torch entries; vehicle lights and point lights keep the native value at the
centres and add their own shape between them. A light change uploads 2.5 KB of lattice per chunk level instead of
re-baking it.

### zombie.iso.LightingJNI (JNILighting)

- A per-square "white" state: while its chunk's texture bakes, `lightverts` returns white and the `lightInfo` object
  (which `IsoGridSquare.getLightInfo` hands out by reference) holds (1, 1, 1), the real values kept aside; a lazy refresh
  in the middle of a bake reads and writes the real ones and re-whitens.
- With `pixelLight`, a square whose light was re-read marks its chunk level for the lattice (`PixelLight.lightChanged`)
  instead of invalidating the level's texture; a change of the visibility bits still invalidates it (they decide object
  alphas in the bake).
- Accessors for PixelLight: the corners as lit, the visibility bits, the flat light, a one-line dump (dev), and the
  torch list as sent to the native (`pzoptTorches`).

### zombie.iso.fboRenderChunk.FBORenderCell

- When `beginRenderChunkLevel` starts a bake, `PixelLight.bakeBegin` whitens the chunk's squares; after every
  `endRenderChunkLevel` (and once before the composite) `PixelLight.bakeEnd` restores them once the texture stops caching.
- The tree pass and the tree appends bake trees white (like the `unlit` sprite flag) when `pixelLight` is on.
- `PixelLight.beforeComposite` (lattice packing, the frame's camera and light table) right before
  `FBORenderChunkManager.endFrame()`, `PixelLight.afterComposite` (the pass mode, the dev dumps) right after it.

### zombie.iso.IsoChunk

- `pzoptPplDirty`, one byte per level: the lattice block of that level needs packing (written by the lighting-read
  workers, one level per task).
- `pzoptPplFlags`, one byte per level, written by the pack: 1 every square's light is saturated, 2 every square hides the
  torch from the player. A chunk texture whose levels (and the eight chunks around) carry a flag leaves the lamps / torch
  out of its light list, which usually leaves it on the light-free program.

### zombie.viewCone.ChunkRenderShader (new override)

- `startRenderThread` calls `PixelLight.chunkDraw(program)` after setting `DEPTH` and `chunkDepth`: it switches the draw
  to the program variant compiled for the lights that reach that chunk texture (none: the light-free `PPL_BASE` one;
  variants are compiled from the one placeholder `pzopt_chunkBase` with `PPL_NO_POINT` / `PPL_NO_TORCH` / `PPL_NO_WET` /
  `PPL_NO_MASK`), sets the light uniforms at a program's first draw of the frame and the draw's light list (`pplSel`).
  Constructor: the quiet load marker.

### zombie.core.opengl.ShaderUnit

- The shader source goes through `PixelLight.patchShader` after `Hdr.patchShader`: `chunkShader.frag` is replaced by a
  GLSL 4.20 version (same uniforms and output, plus the light; test-compiled, stock on failure). Its lattice samplers
  have fixed bindings on units 9-12 (units 4-8 are the fog's and the HDR passes'): the game validates programs with every
  sampler on unit 0, and two sampler types on one unit fail validation on Mesa. The game then renumbers every
  `sampler2D` after the link, so the shadow mask's unit is set again by `glUniform1i`. In pass mode (`pplMode=pass`)
  the composite stays stock.

### org.lwjglx.opengl.Display

- `FrameCapture.beforeSwap()` before the HDR encode (dev rig `devCapture`, off unless set).

### pzopt.FogPass.sceneDepthAsTexture

The offscreen depth becomes a texture also with `pixelLight` (the pass mode reads it in place).

### pzopt.Harness

- `face=deg`: the facing the `turn` starts from (held there with `turn=0`).

## Texture compression without the driver (2026-09-25, `texCompress`, default `auto`)

With `textureCompression=true` (the Steam Deck defaults and the low-end preset turn it on) stock creates every texture as
`GL_COMPRESSED_RGBA` and the driver compresses each mip level inside `glTexImage2D` on the render thread. Mesa does that
on the CPU (41 ns a pixel on the flip's Radeon 890M; Mesa and NVIDIA both pick DXT5): the flip's main menu ran at 15-30
fps for 10-30 s after boot with the render thread 99 % in `glTexImage2D`. BC3 is now encoded by pzopt itself:
`pzopt.TexBcGpu` (a GL 4.3 compute shader; `auto` and `gpu`) or `pzopt.TexBc` on the file-pool worker (`worker`, and
`auto` without compute: macOS GL 4.1). Fit: stb_dxt-style principal axis + least squares with optimal single-colour
tables, alpha least squares + the 6-level mode; sampled quality above both drivers' own compressors
(`tools/TexCompProbe.java`, `harness/texdiff.py`). In `auto` the worker writes the raw level 0 straight into a
persistently mapped staging buffer (`texCompressStagingMb`), the GPU builds ImageData's mip chain from it (bit-exact:
`TexCompProbe -Dprobe.mipcheck`), premultiplies as it encodes, and the blocks reach the texture through a pixel-unpack
buffer; the render thread issues a few GL calls a texture.

### zombie.core.textures.TextureIDAssetManager

- `startLoading`: both texture file tasks (pack page, loose image) keep their callback in a local and are wrapped by
  `pzopt.TexCompress.wrap` (with the pack and page names) when the texture will be created compressed (asset flag 4, not
  a depth texture). The wrapper runs on the file-pool worker: in `worker` mode the stock task, then the levels the render
  thread would upload (`ImageData.getMipMapData`, which builds the mips and premultiplies, work stock does lazily on the
  render thread for loose images) encoded to BC3; in `auto` / `gpu` a pack page is loaded with the stock task's own calls
  minus its `initMipMaps` and only level 0 is staged (premultiplied on the GPU when stock would have: a mipmapped upload,
  or a pack whose flags mipmap). The flag rule is `generateHwId`'s (asset flags, or the compression option without
  asset params).

### zombie.core.textures.ImageData

- Transient fields for the worker's BC3 levels, the staging range and its flags; `dispose` frees both first (a texture
  that never reached `generateHwId`, or the original of a `limitMaxSize` downscale).

### zombie.core.textures.TextureID

- `generateHwId`: first releases staging ranges whose GPU fence has signalled; where stock uploads `GL_COMPRESSED_RGBA`
  levels, `pzopt.TexCompress.upload` creates them with `glCompressedTexImage2D` from the worker's blocks, from the staging
  buffer through `pzopt.TexBcGpu`, or by copying the levels to the GPU encoder; without S3TC, or with
  `texCompress=driver`, the stock path runs. The memory counter adds the RGBA size as stock does.
## Soft sun shadows (`sunShadows`, `sunShadow*`, 2026-09-25; pzopt.SunShadow, pzopt.CapsuleShadow, the sun term in pzopt.ChunkAo)

Write-up: `docs/findings-contact-shadows-2026-09-25.md`. Off by default (a change of the picture).

### zombie.iso.fboRenderChunk.FBORenderCell
- After the chunk composite (`PixelLight.afterComposite`), `CapsuleShadow.queue(playerIndex)` queues this frame's
  capsule shadow pass (a GenericDrawer placed before every character draw).
- `renderPlayer` and `pzoptRenderOnScreenObject`: right before a character's stock `renderShadow`, `CapsuleShadow.add`
  puts its bone capsules into the pass (not for a character seated in a vehicle, whose stock shadow is skipped too:
  the vehicle's capsules shade the car, 2026-09-26); before a vehicle's, `CapsuleShadow.addVehicle`; a zombie drawn as an atlas
  sprite (whose stock shadow call charDrawPrep skips) gets `CapsuleShadow.addAtlas` (one upright capsule). Nothing else
  changes.

### zombie.core.skinnedmodel.animation.AnimationPlayer
- `pzoptPrecomputeShadow` (the bone worker, shadowPrep): when `CapsuleShadow.wanted()`, the fifteen capsule end points
  (`ShadowPrep.capsulePoints`, stock's boneToWorld arithmetic) into `pzoptCapsules`, read by the game thread through
  `pzoptCapsules()` (joins the batch like `pzoptShadowParams`); `updateInternal` invalidates them with the ellipse pair.
  Two public fields cache the bone indices per skinning data.

### zombie.core.skinnedmodel.model.ModelInstance (PlayerData.updateLights)
- After the target ambient is taken from the square, it is multiplied by `SunShadow.characterFactor(character)`: 1 in
  the sun, indoors, at night; 1 - strength for a character in the static world's sun shadow (a cached grid march from
  its chest towards the sun). The game's own easing of the ambient smooths the change.

## Reflections on water and puddles (`reflections`, `reflectionStrengthPct`, `reflectionPuddles`, 2026-09-25; pzopt.Ssr)

Write-up: `docs/findings-reflections-2026-09-25.md`. Off by default (a change of the picture).

### zombie.iso.fboRenderChunk.FBORenderCell
- Right before the chunk composite (next to `PixelLight.beforeComposite`), `Ssr.beforeComposite(playerIndex,
  onScreenChunks)`: the water / puddle square map of the on-screen chunks and the frame's scatter setup (a GenericDrawer).
  Right after the composite, `Ssr.afterComposite()` (dev timing only).
- Around the puddles' and the water's draw: `Ssr.devDump` (dev frame dump, `devSsrDumpAt`), `Ssr.beforeWater` (this frame's
  camera for the water shader) and `Ssr.afterWater` (the moving objects' scatter for the next frame, the world textures off
  their units).
- `renderPlayer` and `pzoptRenderOnScreenObject`: `Ssr.addMoving` puts a character, animal or vehicle standing near water
  or a puddle into the frame's moving-object scatter. Nothing else changes.

### zombie.core.opengl.ShaderUnit
- The source handed to `glShaderSource` passes through `Ssr.patchShader` after the HDR and pixel-light patches: the water
  shaders get the reflection lookup at the end of `mainImage`, the puddles' common unit gets it where the reflective colour
  is taken, the chunk composite programs (stock and pixelLight's) get the scatter after their own `main`. Every patch is
  test-compiled; a unit that does not compile stays as it came.

### zombie.iso.WaterShader, zombie.iso.PuddlesShader
- `updateWaterParams` / `updatePuddlesParams`: `Ssr.surfaceUniforms()` / `Ssr.puddleUniforms(z)` after the HDR glint
  uniforms (the world colour + depth on units 13 / 14, the camera mapping, the hash on image unit 6, the tile map on 5).

### zombie.viewCone.ChunkRenderShader
- `startRenderThread`: `Ssr.chunkDraw(texd)` after `PixelLight.chunkDraw`: the scatter's uniforms once per program per
  frame, then only its on / off switch per chunk texture (on for textures with water within reflection reach).

### pzopt.FogPass.sceneDepthAsTexture
- The world framebuffer's depth becomes a texture also when `reflections` is on (the water reads it in place).

### pzopt.HdrGlint
- `glintOnlyNow()`: the water / puddle draw of the glint-only pass skips the reflection lookups.

## Stairs wall flicker (2026-09-25, `pplTexelPos`, `bakeLevelChangeFrames`; docs/findings-wall-flicker-2026-09-25.md)

### zombie.iso.fboRenderChunk.FBORenderCell
- `pzoptSchedulePlan` hands the camera character's level to `BakeScheduler.cameraLevel` before the offers: for
  `bakeLevelChangeFrames` (3) frames after it changes, the scheduler grants every cutaway and never-textured level at once
  (as stock bakes them), so the floor the player arrives on replaces the old one in one frame instead of chunk by chunk.
## Trees lit with every lighting feature on (`sunShadowTrees`, `aoTreeCanopyPct`, 2026-09-26; pzopt.TreeShade, crown proxies in pzopt.ChunkAo)

See `docs/findings-tree-lighting-2026-09-25.md`.

### zombie.iso.fboRenderChunk.FBORenderTrees (new override)
- `renderTexture`: when `TreeShade.active()` (sun shadows with `sunShadowTrees`, or AO with `aoTreeCanopyPct`) and the
  tree is drawn per frame (not into a chunk texture), the tree's quad is drawn as `TreeShade.STRIPS` horizontal strips
  whose vertices carry the tree colour times `TreeShade.shade` at their point of the card: the same crown proxy as the
  chunk kernel's baked trees (an ellipsoid on the card from the sprite's size; the path out of it towards the sun and
  straight up), so a tree near the player (swaying in the wind, fading, translucent: never baked) keeps the shaded trunk
  and lower crown its baked neighbours have. Everything else about the quad (corners, wind distortion, uv, depth, stencil
  passes) is unchanged; with both keys off, or drawing into a chunk texture, the stock single quad is drawn.
## Driving smoothness (2026-09-26: `vehicleSmooth`, `driveCameraLate`, `driveLookSmooth`, `cameraScreenPixels`, `frameClockSmooth`, `physicsStepHz`, `physicsStepMode`; pzopt.VehicleSmooth, pzopt.DriveCamera, pzopt.FrameClock)

Write-up: `docs/findings-car-jitter-2026-09-26.md` (the maintainer's "micro rubber banding" of the car while driving).
Rig: `devDriveJitter` (pzopt.DriveJitter, `harness/drivejitter.py`, `harness/drivejitter-capture.py`).

### zombie.core.physics.WorldSimulation (new override)
- `updatePhysic`: before the frame's last Bullet step, `VehicleSmooth.beforeLastStep` reads every vehicle's physics state
  (position, rotation, wheels) from Bullet; after the steps, `VehicleSmooth.afterSteps` reads it again and keeps the
  carried remainder as the render fraction. The simulation itself is unchanged.
- `updateInternal` (fix, 2026-09-26, Workshop report "no more damage to cars in a crash regardless of speed"): the
  native `Bullet.getVehiclePhysics` reports each vehicle's collide flag and clears it in the same call (a one-shot latch,
  `movb $0x0,0x5(vehicle)` in libPZBullet64 right after the flag is written to the array). The two reads above ran before
  the stock read of the frame, so the stock read always got 0, `BaseVehicle.jniIsCollide` stayed false and
  `BaseVehicle.crash` never ran: no damage to the car or the driver, no crash sound, no damaged objects, whatever the
  speed. `VehicleSmooth.read` now keeps the id of every vehicle whose flag it took and the stock read ORs it back in
  (`VehicleSmooth.collide`), then drops what is left (`VehicleSmooth.collideDone`, vehicles the read did not report).
  Rig: `--flag ram=true` on a path drive (Harness: no avoidance, no stop; telemetry `crashes= cond= hp=`).
- With `physicsStepHz` other than 100 or `physicsStepMode=frame` the method hands over to `updatePhysicPzopt`, the same
  loop with another fixed step, or with the frame's time split into equal steps no longer than one fixed step and
  nothing carried over; the network clock advances by the stepped time. Single player only. Measured, not shipped on.

### zombie.GameWindow
- `frameStep`: right after the FPS tracker set the frame's simulation step, `FrameClock.afterFpsTracking` may replace it
  with a whole number of display periods (`frameClockSmooth`). Around `renderInternal`, `VehicleSmooth.beforeRender`
  puts moving vehicles, their seated characters and the driving camera where this frame should show them and
  `VehicleSmooth.afterRender` puts every value back; `DriveJitter.beforeRender` logs the frame (rig).

### zombie.iso.PlayerCamera (new override)
- `update`, pan camera while driving: the look-ahead's frame time comes from `System.nanoTime` and its target and value
  stay fractional when `driveLookSmooth` is on (`DriveCamera.panMult`, `DriveCamera.px`; stock truncates to whole pixels
  and paces with whole milliseconds).
- `getOffX/Y`, `getTOffX/Y`, `getLastOffX/Y`: `DriveCamera.snap` (stock's whole offscreen pixels, or with
  `cameraScreenPixels` at zoom below 1 whole screen pixels). `XToIso` / `YToIso` keep a fractional screen point when
  either key is on (the model camera goes through them with the camera offset; truncating it there put the car model up
  to one offscreen pixel off the world).

### zombie.core.opengl.RenderThread, org.lwjglx.opengl.Display
- `Ready`, `lockStepRenderStep`, `update`: `DriveJitter.pushed / acquired / swapped` (rig only: which game frame each
  swap showed, `pzopt-driveswap.out`).


## Darkness floor, remembered places, colour grading (`darknessFloorPct`, `memoryTint*`, `colorGrading*`, 2026-09-26; pzopt.Darkness, pzopt.Grade, pzopt.GradeMath)

Candidate B of `docs/plan-graphics-enhancements.md`; see `docs/findings-darkness-grading-2026-09-26.md`. All off by
default, all live from the Enhancements tab.

### zombie.iso.LightingJNI
- `JNILighting` keeps the native's values of a square (`pzoptDarkRaw`: eight corner colours, the flat light, the fade
  multiplier, then the derived copies and a settings + visibility key) while a square-level feature is on.
  `updateFBORenderChunk` calls `pzoptDarkApply(settings, true)` right after it reads the eight corners: for a seen square
  (above ground unless `darknessFloorBasements`) the corners and the flat light get the darkness floor (a soft maximum of
  the luminance in a cool tint, `GradeMath.floorLight`), remembered squares (fade multiplier below 1) the higher
  `memoryLightPct` floor, and the fade multiplier `darkMulti` is held at 0.5 at least (objects of other rooms keep
  alpha 1 instead of fading out with the black room). Unseen squares are never touched. The stock change test that
  follows compares the derived values, so no re-bake is added; when the native repeats a square exactly (most re-reads)
  the derived values are copied back without arithmetic (`Darkness.repeated`); a per-thread colour cache
  (`Darkness.Scratch`) serves the floor of a colour seen before. `pzoptDarkRestore` gives the native values back when
  the features go off; `reset` drops the kept values (pooled squares). With `devDarkStats` the apply is timed
  (`Darkness.applyNs`).

### zombie.vispoly.VisibilityPolygon2
- `renderToScreen`: before the stock blur shader starts, `pzopt.Darkness.memoryPass(...)` may draw the vision pass
  itself (`memoryTint` on): the same full-screen quad, the same alpha from the 25 vision taps (or the visBlurReduce
  sums) and the same `gl_FragDepth`, but the colour is the world pixel read after a texture barrier, desaturated,
  dimmed and cooled, blended by that alpha x `memoryTintPct` (softened over the whole kernel); fragments with no shadow
  are discarded. When it returns false (off this frame, no texture barrier, a failure) the stock pass runs unchanged.
  The player index is passed for the optional fade pre-pass (`memoryFadeMs`), which reprojects its history by that
  player's camera offset.

### zombie.core.opengl.ShaderUnit
- The shader source goes through `pzopt.Grade.patchShader` before `pzopt.Hdr.patchShader`: `screen.frag` gets its
  `main` renamed and a new one (ARB_shading_language_420pack for the two `sampler3D` bindings 13 and 14; test-compiled,
  stock on failure): grading off = the stock main; on, on the plain world path (no drunk / blur / search mode / goggles)
  the bicubic sample, the stock `desaturate(DesaturationVal)` and one fetch of the fused table (the rest of the stock
  world path and the grade; the stock 3D noise and its film grain of at most 0.0015 are not computed); otherwise the
  stock main followed by the grade-only table (goggles ungraded). The HDR expansion sees the graded picture.

### zombie.iso.weather.WeatherShader
- `startRenderThread` calls `pzopt.Grade.worldUniforms(this.getID())` after the HDR uniforms: uploads a new LUT when the
  worker baked one, binds it on unit 13, sets `pzGradeP` (on / dither / shaper scale). Off: one uniform.

### zombie.iso.IsoWorld
- `render` calls `pzopt.Darkness.frame()` first (game thread, once per frame): pending live settings (every loaded
  square re-derived, every chunk texture re-baked), the grading weights from the climate, and a generic draw that
  carries the frame's render-side switches in stream order.

### zombie.core.textures.MultiTextureFBO2, zombie.iso.fboRenderChunk.FBORenderCell
- The GPU section names `screen` and `vispoly` go through `pzopt.Darkness.section`: unchanged normally, suffixed
  `.on` / `.off` with `devDarkAlternate` so the grade and the remembered-places pass are timed against the stock paths
  in one run.

## Sprite filtering (`spriteFilter`, `spriteFilterMin`, `spriteFilterSharpnessPct`, `spriteFilterKernel`, `spriteFilterSprites`, `spriteFilterMipTrim`, `spriteFilterSkipEmpty`, 2026-09-26; pzopt.SpriteFilter)

Candidate A of `docs/plan-graphics-enhancements.md`: how the chunk composite (and the tiles drawn per frame) sample the
baked world art. Off (`stock`) by default; every edit is a no-op then.

### zombie.core.opengl.ShaderUnit
- The shader source goes through `pzopt.SpriteFilter.patchShader` last (after SSR's patch): it keeps copies of the stock
  `chunkShader`, `tileWithDepth` and `opaqueWithDepth` sources and returns them, with their one `DIFFUSE` fetch replaced
  by `pzsfFetch` and a regime's `#define`s, for the placeholder programs `pzopt_sfChunk`, `pzopt_sfTile` and
  `pzopt_sfOpaque`; with `spriteFilter=sharp` at launch, pixel light's composite programs get the self-selecting fetch.
  The game's own programs are returned unchanged.

### zombie.core.textures.TextureDraw
- `run`, `StartShader`: the program id goes through `pzopt.SpriteFilter.remap` first and the rest of the case (the
  draw census, `glUseProgramObjectARB`, the uniform cache, the `ShaderMap` lookup and `startRenderThread`) uses the
  result: the chunk composite program becomes this frame's zoom variant, `tileWithDepth` / `opaqueWithDepth` their
  per-frame variants while the world framebuffer is bound outside a chunk bake; every other id is returned as is.
  `remap` also closes the composite's filter window (below).

### zombie.viewCone.ChunkRenderShader
- `startRenderThread` ends with `pzopt.SpriteFilter.afterChunkStart()`: after the depth texture is bound, the next
  texture bind (the chunk quad's colour texture) takes the composite's magnification filter.

### zombie.core.textures.TextureID
- `assignFilteringFlags`: the magnification filter goes through `pzopt.SpriteFilter.magFilter` (the texture's own
  unless a chunk texture is bound inside the composite's window: GL_LINEAR for the texel-aware variants, GL_NEAREST for
  `spriteFilter=nearest`). The same two glTexParameteri calls the game makes on every bind; no extra GL call.

### zombie.iso.fboRenderChunk.FBORenderCell
- `pzopt.SpriteFilter.beforeComposite(playerIndex)` right before the composite's GPU section: the zoom over the render
  scale picks this frame's variant (none at 1:1), queued to the render thread in stream order. The section name goes
  through `pzopt.SpriteFilter.section` (`composite.on` / `.off` with `devSpriteFilterAlternate`).
- `pzopt.BakeMips` (not an override) asks `pzopt.SpriteFilter.mipLevelsNeeded` how many mip levels a bake builds
  (`spriteFilterMipTrim`).

### zombie.core.opengl.RenderThread
- After the swap (next to `GlNames.refill`), `pzopt.SpriteFilter.afterSwap()`: when the configured sprite-filter settings
  change (boot, Apply), the variant programs they need are compiled there, outside the world frame (a program is ~0.8 s
  on first use: a hitch the first time the player zooms otherwise). No-op when the filter is off or nothing changed.
## The real sky: sun, moon and cloud shadows (2026-09-26)

Write-up: `docs/findings-sky-2026-09-26.md`. Classes: `pzopt.Sky` (sun and moon ephemeris), `pzopt.SunShadow` (the key
light: the sun, or the moon at night), `pzopt.CloudShadow` (the cloud field, the composite and water patches), the
direct-sun share and the wall fixes in `pzopt.ChunkAo`'s kernel.

### zombie.core.opengl.ShaderUnit
- The patch chain gets `CloudShadow.patchShader` between pixelLight's and the reflections': the chunk composite
  (`chunkShader.frag`, pixelLight's `pzopt_chunkBase` / `pzopt_chunkStock`) multiplies each pixel under a cloud by
  `1 - q (1 - T)`; the water shaders darken under a cloud. Only when `cloudShadows` is on at launch.

### zombie.viewCone.ChunkRenderShader
- `startRenderThread`: `CloudShadow.chunkDraw(texd)` after the reflections' uniforms: once per program per frame the
  cloud uniforms, per draw the chunk texture's kept term (a bindless handle, or a bind) found by its depth texture.

### zombie.iso.fboRenderChunk.FBORenderCell
- Before the chunk composite `CloudShadow.beforeComposite` (the drift, the camera, the per-texture cull list); after it
  `CloudShadow.afterComposite` (dev timing only).

### zombie.iso.WaterShader
- `updateWaterParams`: `CloudShadow.waterUniforms()` (the cloud field on unit 16 and the camera mapping on the bound
  water program).

### zombie.iso.weather.fx.WeatherFxMask
- The stock screen-space cloud layer is skipped (and does not keep the weather mask awake) while
  `CloudShadow.replaceStock` (`cloudReplaceStock`, off by default).

## God rays (2026-09-27)

Write-up: `docs/findings-god-rays-2026-09-27.md`. Class `pzopt.GodRays` (occupancy, the rectified froxel volume, light
volumes, local lights, the haze's taps); `pzopt.FogPass` carries the fog shade.

### zombie.core.opengl.ShaderUnit
- The patch chain gets `GodRays.patchShader` innermost (the stock `screen.frag`: its bicubic fetch of the world picture is
  wrapped, one tap of the quarter god ray buffer, before the colour grade's patch) and `GodRays.patchChunk` between the
  cloud shadows' patch and the reflections': the chunk composite (`chunkShader.frag`, pixelLight's programs) adds the
  outdoor haze from each fragment's own depth. The chunk patch only with `godRaysHazeComposite=chunk` (the default).

### zombie.viewCone.ChunkRenderShader
- `startRenderThread`: `GodRays.chunkDraw()` after the cloud shadows' uniforms: the haze uniforms once per program per
  frame (off: the shader returns after one uniform test).

### zombie.iso.fboRenderChunk.FBORenderCell
- At a level's bake, when its objects were added, removed or changed and `godRays` is on: `GodRays.chunkChanged(chunk)`
  (the occupancy of that chunk is rebuilt: a door opened, a window broken).
- Before the chunk composite `GodRays.beforeComposite` (this frame's camera for the haze in the composite); before
  `renderFog` `GodRays.queue` (this frame's light, volume updates, light volumes and local lights; the scene depth is
  complete there).

### zombie.iso.weather.WeatherShader
- `startRenderThread`: `GodRays.worldUniforms(program)`: the quarter buffer and its mapping on the world composite (or its
  switch off).

### zombie.core.textures.MultiTextureFBO2
- Around the screen composite `GodRays.screenBegin` / `screenEnd`: dev timing (`devGodRaysTiming`), and with
  `godRaysLateDraw` (off) the light volumes and local lights drawn there over the finished world.

## zombie.MovingObjectUpdateSchedulerUpdateBucket: the bucket's update loop on the workers (`entityUpdateParallel`, new override)

The three batches that already ride the scheduler all sit *around* the simulation: `SeparateBatch` computes the
separations before the update loop, `ActionEval` and `AnimBatch` take the transition evaluation and the bone math out of
the postupdate loop. The loop in the middle — the entities' own `update()` — was still one after another on the game
thread, and it is the only part of the frame that scales with the whole moving-object population rather than with the
zombies alone: animals, vehicles and the players go through the same bucket.

The bucket is a new override (it is a top-level class, so shadowing `MovingObjectUpdateScheduler` never covered it). In
`update(int)` the loop now walks the bucket's sub-list in stock's order and, with the key on, hands each eligible entity
to `pzopt.UpdateBatch` instead of updating it inline; after the loop the batch runs the same four calls per entity —
`setCurrentSimulationLevel`, `preupdate`, `frameStep`, `update`, in that order — on the `FrameBatch` workers. Four calls,
not one: stock does all four per entity and losing any of them is silent, so the batch reproduces the sequence rather
than just the `update()`.

Two cases stay on the game thread, collected inline exactly where stock had them: an `IsoDeadBody`, which goes into the
cell's shared remove set, and the reused-zombie debug branch. Anything the batch did not take runs inline as before.

The batch is per bucket and never spans two of them. `GameTime.perObjectMultiplier` is set to the bucket's frame mod at
the top of `update(int)` and back to 1 at the bottom; it is one field on the `GameTime` singleton, so it is only constant
— and the entities' timing only correct — while a single bucket's entities are in flight. Running the batch inside that
window is what makes the multiplier the right one for every entity in it; batching two buckets at once would let each
publish its own multiplier and every entity would read whichever landed last.

An entity that throws on a worker is reported once and turns the batching off for the rest of the session, so the bucket
walks the stock loop from the next frame on; a failed entity is not retried, because the entities before it in the batch
have already updated this frame and `ECSEntity` refuses a second update in the same frame.

Default off. Unlike the postupdate batches, this one moves the simulation itself: the entities' writes still all happen,
but their order within the frame is no longer the bucket's list order, so it is opt-in until the checksum rig has run
over a route. `tests/pzopt/UpdateBatchTest` drives the batch with real `IsoMovingObject`s and pins the four-call
sequence, once per entity, across threads, plus the failure latch.

## The ItemVisuals scratch buffers a worker reaches, one per thread (`entityUpdateParallel`)

`entityUpdateParallel` runs a simulation bucket's entities through their four update calls on the `FrameBatch` workers,
so every static scratch object those calls reach stops being scratch and becomes shared mutable state. A live
4,220-zombie batch proved it at frame 53: a `NullPointerException` out of the ShoeType sound parameter, on a worker,
because the buffer it was walking by index had been cleared and refilled shorter by another worker part way through.
The whole jar holds seven classes with a static `ItemVisuals` field; this pass fixes the two that a batched entity's
`update()` can actually reach, and the reasoning for the other five is below so the next reader does not have to redo
it.

The shape of the bug is the same everywhere: `IsoGameCharacter.getItemVisuals(buffer)` **clears** the buffer and refills
it from the caller's worn items, and the caller then reads it back by index. One buffer for every character is fine
while one thread walks the entity list; two workers turn it into a torn read, and because the list only shrinks
silently the symptom is either a null element (the crash above) or blood, dirt, holes and patches applied to the wrong
character's clothing with no error at all.

### zombie.characters.IsoGameCharacter

A new `pzoptTempItemVisuals`, one `ItemVisuals` per thread, replaces the shared `tempItemVisuals` every character
used, and each of the fourteen methods that filled that field takes its own thread's buffer into a local of the same
name at the point where stock did the fill, so the rest of every method body is unchanged:
`playWeaponHitArmourSound`, `addBasicPatch`, `addHole`, `addDirt`, `addLotsOfDirt`, `addBlood`, `bodyPartHasTag`,
`getBodyPartClothingDefense`, `addHoleFromZombieAttacks`, `updateWornItemsVisionModifier`,
`updateWornItemsHearingModifier`, `hasDirtyClothing`, `hasBloodyClothing` and `updateDisguisedState`. This is the class
that matters: it is the base of every entity the bucket updates, and the path into it is plain single-player code — a
zombie's `update()` runs `updateInternal`, that runs the state machine, the eat-body state splatters blood on the zombie
itself, and `addBlood` hands the shared buffer on to the clothing-blood helper inside a loop of up to twenty-eight
splats, the widest window of any of the fourteen. `getBodyPartClothingDefense` is reached from the same `update()` by a
second, independent route (the falling / landing / fell-on-knees chain).

The jar's field itself stays declared, with its name, type and `protected static final` access, because
`scripts/build.sh` requires every non-private member of a shadowed class to survive so anything compiled against the
shipped class still links; nothing reads it any more. Since the locals shadow it, a method that missed its local would
compile and quietly go back to sharing, so the test below checks the built class files: no method of either class may
touch that field, the initializer that creates it aside.

Every one of the fourteen is pure per-call scratch — filled, read inside the one call, nothing carried between calls —
so one buffer per thread is exactly what the game thread already had: a single buffer, reused. There is therefore no
behaviour to protect and the change is unconditional rather than gated on the key. A guard would have had to be
repeated at all fourteen sites, would have kept the shared buffer in live use on one branch of each of them, and would
have given the bytecode check above nothing to assert: more surface for no gain.

### zombie.characters.IsoZombie

`helmetFallFromVisuals` was the only user of that field outside `IsoGameCharacter` anywhere in the jar, so it reads its
own thread's buffer now. Its own behaviour is unchanged (it still removes a fallen entry from the buffer and copies the
rest into the zombie's visuals); it is reached from combat rather than from `update()`, and it follows only because the
field it read has moved.

### zombie.audio.parameters.ParameterShoeType (new override)

The class that produced the crash. It keeps its own static `ItemVisuals`, filled by `getShoeType` from the character's
worn items and then walked by index looking for the SHOES body location; that walk re-reads the size each iteration, so
a refill by another worker between the size check and the element read hands back a null and the parameter update dies.
Same treatment, same reasoning: per-thread buffer, fetched into a local in `getShoeType`, unconditional. The path is the
sound upkeep every zombie does on every update — `updateInternal`, `updateEmitter`, the FMOD parameter list, this
parameter's `calculateCurrentValue` — which is why it was the one that showed up first, and within a minute of the first
parallel run.

### Checked and deliberately left alone

`zombie.characters.ClothingWetness` (a static `ItemVisuals` plus a static covered-parts list) and
`zombie.characters.BodyDamage.Thermoregulator` (two static `ItemVisuals` plus a static covered-parts list) carry the
same hazard in principle but cannot be reached from a worker as the batch stands: a `ClothingWetness` is only ever
constructed by `IsoPlayer`, a `Thermoregulator` only when the body damage's owner is an `IsoPlayer`, and the body damage
object itself is only created for players and animals — so the whole `BodyDamage.Update` subtree is dead for anything
else, and `UpdateBatch.batchableType` keeps players and animals on the game thread anyway. Shadowing two more game
classes for a path nothing can take would add two permanent decompile-and-audit liabilities against the top requirement
of this repo, and the thermoregulator is dense float physics — the worst candidate there is for a hand-fixed decompile.
Worth noting for whoever lets animals into the batch: that is the moment these two become live, and one of the
thermoregulator's two buffers is **not** per-call scratch. It is a cache of the previous call's visuals, compared
against the fresh list to decide whether to rebuild the per-node clothing lists, so a thread-local there is not
behaviour-identical — it turns cache hits into misses. Harmless in effect (a miss only redoes a deterministic rebuild)
and close to academic, since one static cache shared by every character already misses nearly always in a world with
more than one of them, but it has to be a deliberate decision rather than a mechanical one.

`zombie.PersistentOutfits`'s buffer is used only by the fallen-hat removal, which the outfit-dressing path reaches: from
multiplayer packet handling, from zombie spawning, and from the render-side random-outfit dressing. The one route from
`update()` is behind `Core.debug` and goes through the model manager's dressing, which is far more than a buffer's worth
of not-worker-safe work; the key is off in multiplayer in any case.
`zombie.network.packets.ZombieHelmetFallingPacket` is multiplayer only, and `UpdateBatch.enabled()` is false there.
`zombie.characters.BodyDamage.Thermoregulator_tryouts` is dead code — nothing in the jar references it but its own
nested classes.

Not in this pass, and a bigger job: `IsoGameCharacter` holds a dozen more static scratch objects of other types
(`tempo`, `tempo2`, `tempo3`, `tempVector2`, `tempVector2_1`, `tempVector2_2`, `tempVector3f00`, `tempVector3f01`,
`tempVectorBonePos`, `inf`, `movingStatic`, the bandages singleton). Those are the same class of hazard for
`entityUpdateParallel` and want the same audit before the key is turned on by default.

`tests/pzopt/ItemVisualsScratchTest` drives both fixed paths for real — `hasDirtyClothing` on a real
`IsoGameCharacter`, and the ShoeType parameter's `calculateCurrentValue` — from eight threads held on a barrier inside
`getItemVisuals` so every thread has filled its buffer before any of them reads it back, and asserts each thread had a
buffer of its own still holding what it wrote, and that two calls on one thread reuse that thread's buffer instead of
allocating per call. Two pins come with it, both read straight out of class files: that the jar still declares both
fields as `static ItemVisuals`, so a change at The Indie Stone's end fails the build instead of quietly making the
override pointless, and that the built overrides no longer reach the retained shared field. Against the stock classes
the concurrency check reports "1 of 8 were distinct" for each path.

## The worker guards ported from PZMulticore (`entityUpdateParallel`)

The live 4,220-zombie runs of the entity batch showed the next two races past the ItemVisuals scratch pass: an
`IllegalStateException: Forward Direction cannot be zero length vector` out of `WalkTowardState.execute` on a
`pzopt-frame-` worker, and an `ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 2` at frame 10 that
arrived with no stack at all, because the batch logged only the throwable's `toString()`. This pass ports PZMulticore's
proven guard set for exactly these races (its ASM patchers ForwardDirection, LuaEventManager, AttachedItems and
PathFindBehavior2, months in live use) into pzopt's idiom — source edits in the overrides, logic in `pzopt.UpdateBatch`
— with one deliberate difference throughout: where PZMulticore patched unconditionally, every pzopt guard keys on
`UpdateBatch.onWorkerNow()`, so the game-thread path stays byte-identical to vanilla with the key off *and* on.

`onWorkerNow()` is the one predicate all of them share: true only while the entity batch is actually in flight (a
volatile set and cleared around the batch's `FrameBatch.run` inside `UpdateBatch.run`) AND the current thread is a
`FrameBatch.Worker` (an instanceof check — the worker class is public, so no name-prefix matching). Both conditions
carry weight. The in-flight flag keeps the scheduler's other batches, which share the same workers but never call Lua
or the guarded paths by design (AnimBatch, ActionEval, LightingBatch, SeparateBatch), entirely unaffected; FrameBatch
runs one batch at a time, so while the flag is up the only tasks on the workers are this batch's entities. The thread
check keeps the game thread — which works the batch alongside the workers — on vanilla behaviour for every entity it
updates itself. `UpdateBatch.run` also logs the FIRST failure's full stack trace now (one-shot; repeats keep the
one-line summary), so the next unknown race arrives with a call site instead of a bare `toString()`.

`tests/pzopt/WorkerNowTest` pins the predicate from all three sides (game thread, entity-batch worker, plain
FrameBatch worker) plus the first-failure stack trace and the one-line repeat.

### zombie.characters.IsoGameCharacter (existing override)

- `setForwardDirection(float, float)`: vanilla writes the direction, normalizes, sets the iso direction and THEN
  throws `IllegalStateException` when the length is zero. On a worker mid-batch the zero length is a torn position
  read (two threads read/write positions during the parallel update and a walk delta collapses), not a programming
  error, so the method now returns silently there — the character keeps its previous direction and the next frame
  recomputes. Vanilla's mutation order is untouched (PZMulticore's patch replaced the ATHROW with a POP+RETURN, i.e.
  kept the same writes); the game thread still throws, key on or off, which is where pzopt deliberately narrows
  PZMulticore's unconditional patch. Key: `entityUpdateParallel`. `tests/pzopt/ForwardDirectionGuardTest` pins both
  sides on real characters driven through a real batch.

### zombie.Lua.LuaEventManager (new override)

- every `triggerEvent` overload (nine of them): at entry, if `UpdateBatch.onWorkerNow()`, count via
  `UpdateBatch.onLuaSuppressed()` and return. Vanilla's main-thread path writes the shared static argument slots
  `a1..a8`/`a1index..a8index`; its off-thread path takes the `EventMap` monitor and queues into a shared pool — a
  worker mid-batch must enter neither, and a mod's event handler running against a half-updated entity is wrong even
  where it would not crash. `onLuaSuppressed()` is an AtomicLong with a one-shot stack dump on the first occurrence
  (so the log says which event from where), `getLuaSuppressedCount()` reads it and `UpdateBatch.describe()` folds it
  into the console summary line. The game thread dispatches exactly as vanilla, so nothing a player does with the key
  off changes. KahluaThread (`pcall`) is deliberately NOT guarded in this pass: `LuaEventManager.triggerEvent` is the
  single funnel for event dispatch out of entity code, and the pcall-level belt goes in only if evidence shows a path
  that bypasses it. Key: `entityUpdateParallel`. `tests/pzopt/LuaEventGuardTest` exercises worker suppression for all
  nine overloads and game-thread dispatch through a real batch, and pins in bytecode that the override carries
  exactly the jar's overload set with the guard as each one's first call; runtime dispatch into a real Lua state is
  not exercised (no Kahlua environment in a bare JVM).

### zombie.characters.AttachedItems.AttachedItems (new override)

- all thirteen public methods are `synchronized` — the patcher's exact set: constructors and the two private
  `indexOf` helpers skipped (the privates only run from the synchronized publics, so they already hold the lock).
  The class is a plain `ArrayList<AttachedItem>` behind get/setItem/remove/forEach; every read re-checks `size()`
  against a list another thread may be shrinking, and PZMulticore traced the resulting `IndexOutOfBoundsException`
  out of `ArrayList` to exactly this class under its parallel updates. This is also the most plausible culprit for
  our own frame-10 `Index -1 out of bounds for length 2`. One lock per character's instance, so contention needs two
  threads on the SAME character, which the bucket scheduler prevents — on the game thread the cost is an
  uncontended lock. This edit is intentionally not keyed on `onWorkerNow()`: ACC_SYNCHRONIZED on an uncontended
  monitor does not change what any method computes, and a conditional lock cannot be expressed with the flag while
  the unconditional one is exactly what months of PZMulticore live use ran. Key (reached only via):
  `entityUpdateParallel`. `tests/pzopt/AttachedItemsSyncTest` shows the stock class tearing within two seconds
  (ArrayList's IOOBE out of `forEach` against `copyFrom`/`clear`) and the override clean, and pins ACC_SYNCHRONIZED
  on exactly the jar's public method set in the built class.

### zombie.pathfind.PathFindBehavior2 (new override)

Superseded on 2026-09-27 by "The pathfinding race was the class's own static scratch" at the end of this file:
both layers below are retired, the list copy is gone and the counter named here no longer exists. Kept for the
record of what was tried and why it did not hold.

- `update()`, both layers of PZMulticore's patcher at source level. Layer 1: `this.path.nodes` is read once at
  entry into a local; on a batch task the local is a frozen `clone()` of the list, and every read in the
  method body goes through the local — PZ's async pathfinding writes the live list from its own thread while the
  method iterates. Outside a batch the local IS the live list (no clone), so serial behaviour is bit-identical;
  the writes (`path.clear()`/`addNode` in the vehicle-target branch, `setPath2`, `closestPointOnPath`) stay against
  the live path exactly as the patcher left them. Layer 2: the body is wrapped in a catch of
  `IndexOutOfBoundsException | IllegalStateException`; a batch task counts it (`UpdateBatch.onPathfindRaceSkipped()`,
  AtomicLong, folded into `describe()`) and returns `BehaviorResult.Working` so the character retries next frame,
  outside a batch it rethrows — vanilla parity where PZMulticore again caught unconditionally. Layer 2 stays necessary
  behind layer 1 because the position race (layer 1 fixes only the list race) can still surface as a zero-length
  vector in a callee, and `pathIndex` is derived from the live path but indexes the snapshot. Key:
  `entityUpdateParallel`. Both layers originally guarded on `onWorkerNow()` (workers only), keeping the game-thread
  participant on vanilla's live-list read; the live runs disagreed — every PathFindState escape of lou-replay-clean
  (4) and lou-fwd-scratch (1) bottomed out in `FrameBatch.run`, the game thread working the batch, racing the same
  writers a worker does (the pathfind thread's delivery, a group leader's member `pathToLocation` on another
  worker). The predicate is now `UpdateBatch.onBatchTaskNow()` (the batch in flight AND this thread inside one of
  its entity tasks — the condition `deferMovingSquare` used from the start), so vanilla behaviour outside a batch
  is untouched, key on or off. Also in this file, marked `pzopt: decompiler fix`: CFR's `(Object)` casts into
  `ObjectPool.release` and `set(Param<T>, T)`, and two locals whose declarations CFR dropped in
  `checkDoorHoppableWindow` — all verified against the jar by the bytecode audit (0 mismatches over the class's
  unedited methods). `tests/pzopt/PathfindRaceGuardTest` pins in bytecode that `update()` reads `Path.nodes` at most
  once (the jar's copy reads it many times), and drives the real `update()` on real characters through a real batch:
  every probe — worker or game-thread participant — gets Working plus the counter, and outside a batch the throw
  still escapes exactly like vanilla. The clone-under-race
  semantics themselves (a list mutated mid-iteration surviving because the iteration holds a frozen copy) are not
  separately exercised at runtime — a bare JVM has no async pathfinder to race against; the bytecode pin plus the
  serial-aliasing argument above are the evidence.

## zombie.iso.IsoMovingObject: the position snapshot and the tile-update deferral (`entityUpdateParallel`, new override)

The first live A/B of `entityUpdateParallel` (Louisville, ~4,000 zombies, 25 s, 11.9 → 22.0 fps) left a measured
residue: 27 caught StateMachine exceptions against 0 in the baseline, 20 of them the zero-length ForwardDirection
throw on the GAME thread — a worker read another entity's `x`/`y`/`z` while a second worker was writing them, and
the torn value surfaced a frame later, past every worker-side guard. This override is PZMulticore's v1.4 answer
(its `IsoMovingObjectPatcher`) rebuilt as a pzopt override; it exists to take that residue to zero.

- Four new public fields, all marked `pzopt: entityUpdateParallel`: `pzoptSnapshotIndex` + `pzoptSnapshotFrame`
  (the entity's slot in `UpdateBatch`'s frozen position arrays, valid only while the frame stamp matches the
  batch), and `pzoptDeferredSquare` + `pzoptDeferredSquareFrame` (the latched `setMovingSquare` argument, below).
  Deliberately no initializers: a fresh entity's stamp of 0 never matches a batch (the counter starts at 1), so
  every constructor stays byte-identical to stock.
- `getX()`, `getY()`, `getZ()`: one guard line at entry. While a batch is in flight (`UpdateBatch.frozen(this)`:
  volatile read, false costs nothing with the key off), an entity whose stamp matches the batch answers with the
  position frozen on the game thread just before dispatch — unless the caller is the task updating that very
  entity (`ThreadLocal` in UpdateBatch), which must see its own writes live. Only the queued entities are
  snapshotted: players, animals and grappled zombies ran inline before `run()` and cannot move mid-window. The
  happens-before is the array-and-stamp population on the game thread before the volatile `inFlight` write,
  volatile-read first in `frozen()`. Staleness is the stamp — no clear pass after the join.
- `setMovingSquare()`: one guard line at entry. The stock body mutates the target square's shared
  `MovingObjects` ArrayList — two workers landing entities on one square is a plain list race. A call made from
  inside a batched entity's update (any thread; the game thread works the batch too) is latched on the entity
  (last call wins) and the game thread replays it through the real method right after the join, also after a
  failed batch. This goes one step past the PZMulticore reference, which skips the call outright: the only other
  writer for a zombie is `postupdate()`'s `setMovingSquare(this.current)` on the game thread, so a pure skip
  merely delays the square hand-off by half a frame there — but the replay makes the per-frame end state exactly
  stock's serial outcome, latched callees do not even need to be in the queue, and nothing is left to chance.
- `removeFromSquare()`, marked `pzopt: decompiler fix`: the jar chains the two null stores
  (`this.current = this.last = null`, one `aconst_null` + `dup_x1`); CFR split them into two statements. Verified
  by the bytecode audit — 0 mismatches over the class's unedited methods.

Key: `entityUpdateParallel` (the layer is part of the feature, not separately switchable — turning it off alone
would reintroduce the torn reads the feature cannot ship with). The UpdateBatch side: snapshot arrays grown
never-shrunk, `CURRENT` set/cleared around the four calls per task, the deferred-square replay drained through a
`ConcurrentLinkedQueue` with a stamp check so a racing double-add applies once, and `describe()` now counts
`movingSquareDeferred`; the harness summary's `zombie_batches=` line carries `UpdateBatch.describe()` so every
run reports batch counts, Lua suppressions, pathfind skips and deferrals. `tests/pzopt/PositionSnapshotTest`
pins frozen cross-entity reads (latch-ordered, no scheduling luck), live self-reads, live-again after the join,
staleness across batches and never-batched entities; `tests/pzopt/MovingSquareDeferralTest` pins stock behavior
outside a window, the untouched shared list mid-window, the replay's end state, last-call-wins, and a batched
update writing a non-queued entity's square.

## zombie.Lua.LuaEventManager: worker events captured and replayed instead of dropped (`entityUpdateLuaReplay`)

The first run summary with `UpdateBatch.describe()` wired in put a number on the drop guard's cost:
`luaSuppressed=4,383,274` in a 26 s Louisville route — the per-zombie update event
(`IsoZombie.updateInternal` → `triggerEvent`), deleted for every batched zombie every frame. In stock those
handlers RUN, so part of the measured speedup was skipped work, and any mod hooking per-zombie events was
silently dead while the batch was on. This applies the repo's own AnimParallel pattern (anim events captured on
the workers, dispatched on the game thread in order) to the update batch.

- Each `triggerEvent` overload's worker guard now routes to `UpdateBatch.captureLuaEvent(event, params…)`
  instead of the drop counter (one line per overload, marked `pzopt: entityUpdateLuaReplay`). The varargs
  array only allocates on the already-guarded worker path; the game-thread fast path is unchanged.
- `UpdateBatch` keeps one pooled capture list per task; the runner points a ThreadLocal at the task's list
  around the four update calls. After the join — after the deferred setMovingSquare replay, still inside the
  bucket window — the game thread walks the tasks in queue order (stock's serial event order) and fans every
  record back through the real `triggerEvent` overloads, so handlers run under the bucket's
  `perObjectMultiplier` exactly as stock's inline dispatch did. Replay also runs after a failed batch: events
  fired before the throw had fired in stock's semantics too.
- Key `entityUpdateLuaReplay`, default on. Off = the previous count-and-drop guard, kept so the replay's
  game-thread cost can be priced in an A/B. `describe()` carries `luaCaptured=`/`luaReplayed=` beside the
  drop counter.

`tests/pzopt/LuaEventReplayTest` pins the runtime counter flow (captured on the worker, zero mid-flight
replays, replayed after the join, nothing dropped while the key is on) and, in bytecode, that the replay fans
out through all nine `triggerEvent` overloads and every overload's guard routes to the capture funnel.
`tests/pzopt/LuaEventGuardTest` keeps the guard pins and re-runs itself in a subprocess with the key off for
the drop-mode runtime half (the bare JVM's null Lua state cannot take a replayed dispatch of the seven
overloads that do not null-check `env`).

## zombie.ai.ZombieGroupManager: the group list under one lock (`entityUpdateParallel`, new override)

Run `lou-replay-on`, frame 928: a worker died with `NullPointerException: "idealSizeFactor" because "group" is
null` in `findNearestGroup`, latching the batch off for the rest of the session. Root cause from the pinned
jar: `groups` is a plain ArrayList and every batched zombie's `updateInternal` calls `update()` here —
`findNearestGroup` iterates the list AND removes empties (`groups.remove(i--)`), `update()` adds groups,
removes members and reads other groups' leaders, `preupdate()` sweeps, and the leader/member branches use the
manager's shared `tempVec2`/`tempVec3` scratch. Concurrent iterate/add/remove on one ArrayList tears a slot;
the joins are also gated on a global tick, so the race is bursty (one frame in thirty) and intermittent.

Every group-touching section now runs under `synchronized (this.groups)`, the same idiom as the `lccMain` and
`soundList` locks this repo already ships for PZMulticore's workers: the membership remove at `update()`'s
entry, everything past the tick gate (join, leader spread, member follow — which also covers the tempVec
scratch), the whole of `findNearestGroup` (reentrant under `update()`'s lock, locked itself for external
callers), `preupdate()`'s sweep and `Reset()`. Uncontended on the stock path — the frame workers only exist
while a batch is in flight — and the tick gate keeps the join block off 29 frames in 30.

Key: `entityUpdateParallel`. A runtime hammer is not possible in a bare JVM (`findNearestGroup`'s first reads
touch `SandboxOptions.instance`, whose initializer runs Lua through the game filesystem), so like the
PathFindBehavior2 clone the evidence is structural plus the live run: `tests/pzopt/ZombieGroupGuardTest` pins
in bytecode that the jar's methods hold no monitor (the lock is ours, and a TIS-added lock would be noticed)
and that exactly `update`/`findNearestGroup`/`preupdate`/`Reset` in the override each hold one, with the
method set otherwise identical to the jar's.

## zombie.characters.IsoGameCharacter: setForwardDirectionFromIsoDirection off the static scratch (`entityUpdateParallel`)

The residue of every batched Louisville run — 37 caught exceptions over 3,064 zombies (1.21% per zombie),
unchanged by the position-snapshot AND the Lua-replay layers — was `IllegalStateException: Forward Direction
cannot be zero length vector` out of WalkTowardState (27), ThumpState (5) and ClimbOverFenceState (1).
ClimbOverFenceState throws it from `setDir(IsoDirections.N)`, a constant, which rules the states' own math
out; all 33 stacks route through `setForwardDirectionFromIsoDirection`. Its jar body is four instructions:
write the character's direction into the STATIC `tempVector2_2` with `getVectorFromDirection`, read it back
into `setForwardDirection`. One scratch Vector2 shared by every character on every thread — and
`getVectorFromDirection` zeroes the vector before its switch assigns the direction, so a batched zombie
reading between another worker's zeroing and its assignment sees an exact (0,0) and throws. Between throws
the same race silently hands a walker another zombie's direction for a frame.

The method now takes its vector from `pzopt.UpdateBatch.dirScratch()`, a per-thread Vector2
(`ThreadLocal.withInitial`, the `VehicleCull.near` idiom). Identical output single-threaded; the other
`tempVector2_2` users (`processHitDamage`, `renderlast`, `isObjectBehind`, `isBehind`) keep the static and
are the static-scratch audit's follow-up. `tests/pzopt/ForwardDirectionScratchTest` pins that the jar's
method still uses the static (a TIS rework would be noticed), that the override's method touches no
`tempVector2_2` and routes through `dirScratch()`, and that the scratch is per-thread; a runtime hammer
needs a constructible IsoGameCharacter, which a bare JVM does not have.

## zombie.characters.animals.IsoAnimal: updateLOS skips the no-effect far-zombie calls (`animalLosFast`, new override)

Vanilla `updateLOS` walks the WHOLE cell object list once per animal per frame and calls
`BaseAnimalBehavior.spotted(zombie, false, dist)` for every zombie in it — animals x objects, the quadratic
behind updateLOS's 4% of the game thread in the maintainer's farm profile (on the Louisville horde it is
0.45%: almost no animals near the route, ~4,000 objects each). Read from the pinned jar: for a zombie
farther than 10 tiles (square non-null) the call's whole observable effect is `parent.spottedChr = null`
plus one `lastAlerted` subtract-then-clamp — the stress/flee/alert branches all need `dist <= 10` (wild
flee needs 3, flee 6), `spotted` cannot become true for a zombie past 10, and no `Rand` is drawn, so the
shared RNG stream is identical either way.

With `animalLosFast` (default on) the loop skips a zombie's call when its squared distance exceeds 101 —
the 1.0 margin over 10² guarantees float-sqrt rounding at the boundary can never make vanilla's
`dist <= 10` true for a skipped zombie — counting each skip (null-square objects are not counted: vanilla
gives them no call either). The bookkeeping is replayed exactly where vanilla would have applied it:
before the next executed `spotted()` call (which re-nulls `spottedChr` itself at entry),
`AnimalLos.decay` applies the same N sequential subtract-then-clamp steps N vanilla calls would have — a
loop, not one multiply, because float subtraction is not associative and the clamp can hit zero mid-run —
and after the loop the remaining skips also null `spottedChr`. Player calls are never skipped (their
acceptance/wild-spotting logic reaches past 10 tiles and draws from `Rand`).

Also in this file, marked `pzopt: decompiler fix`: three `CreateItem`/`Translator` results CFR typed as
`Object` (casts restored), and `fertilize`'s two branched `getData().maleGenome` stores that CFR folded
into one ternary assignment (the jar's if/else shape restored) — all verified by the bytecode audit
(0 mismatches over the class's unedited methods). `tests/pzopt/AnimalLosTest` pins `decay` bit-exact
against the vanilla step sequence and, in bytecode, that the jar's `updateLOS` has no pzopt call while the
override's still calls `spotted` and routes the skip accounting through `AnimalLos.decay`; a runtime drive
needs a constructible IsoAnimal with a populated cell, which a bare JVM does not have (the constructor
chain pulls AnimalDefinitions through the script engine).

## entityUpdateParallel: vehicles and physics objects stay inline (batchableType)

Found by the first hour-long live session (2026-09-26), not by any bench: the Louisville route is on foot,
so no active vehicle ever updated mid-batch until real play did it. `UpdateBatch.batchableType` excluded
players and animals but admitted `BaseVehicle` — an active vehicle's `update()` reaches Lua part scripts
(`updateParts` → `VehicleParts.callLuaVoid` → `KahluaThread.pcall`), the wrong-thread guard errored 26
times on `pzopt-frame-*` workers, and the 27th corrupted the Kahlua VM stack ("Index -4 out of bounds for
length 1000"), latching batching off for the session. `batchableType` now also excludes `BaseVehicle`
(Lua + pzBullet) and `IsoPhysicsObject` (native physics, never audited); both update inline on the game
thread exactly as with the key off. The same session was otherwise the branch's strongest evidence: ~17,900
frames of real play with zero caught state exceptions before the vehicle moment. `UpdateBatchTest` pins the
four exclusions and the zombie/probe admissions.

## entityUpdatePipeline: a bucket's batch flies while the next one collects (GameTime + FrameDelay new overrides, bucket/scheduler edits)

The five simulation buckets each dispatched their batch and JOINED before returning: five sequential
barriers per frame, the game thread idle at every one. With `entityUpdatePipeline` (default on, under
`entityUpdateParallel`) the bucket's update() first lands the PREVIOUS bucket's flight (`joinPending`),
then sends its own collection up without waiting (`dispatchAsync`) — so the next bucket's collection walk,
its dead-body/reused-zombie branches and its inline entities (players, vehicles, animals, grappled
zombies) all run while the previous batch's workers are still busy. The scheduler override joins once more
after the last bucket, so postupdate and the render never see an airborne batch; FrameBatch's
one-batch-at-a-time rule holds throughout (dispatch always joins first).

Two consistency pieces make the overlap safe:

- **perObjectMultiplier virtualization.** The global field belongs to whichever bucket the game thread is
  IN — the airborne batch's tasks would read the wrong bucket's frame mod. Jar-wide the field funnels
  through five read sites (the three GameTime getters, `FrameDelay.update`,
  `IsoZombie.allowsInvisibleAnimationSkips`); each now reads `pzopt.UpdateBatch.pom(gameTime)` — the
  dispatch-time capture while this thread runs one of the batch's tasks (a no-boxing float[1]
  ThreadLocal), the live field otherwise, so off-batch behaviour is bit-identical. The Lua replay at the
  join runs under the same holder: handlers see their own bucket's time scale even though the game
  thread's global already belongs to the next bucket. `PerObjectMultiplierTest` pins all five sites in
  bytecode (jar raw, override routed) and drives the capture semantics through a real racing batch.
- **Inline entities stamp into the in-flight snapshot.** Serially, inline entities finished before the
  dispatch, so workers always read their settled positions; overlapped, they move mid-flight. After an
  inline entity's four calls the bucket loop stamps its post-update position into the airborne batch's
  snapshot (`stampInline`, append past the queued block, `INLINE_SLACK` reserved so the arrays never grow
  mid-flight; past the slack the entity simply stays live). The stamp's plain writes mean a torn read is
  just one more live read.

The queue is double-buffered (the flight owns its array; the next collection gets the spare), captures
replay in queue order at the landing exactly as before, and a worker failure latches at the join as
before. With the key off, `run()` is dispatch-then-join — the old synchronous shape, and what the JVM
tests drive. `PipelineTest` pins the wiring (bucket dispatches async + joins the previous, scheduler holds
the final join) and the discriminating runtime: with every task finished on the workers, nothing has
replayed until `joinPending`. Also in the new GameTime override, marked `pzopt: decompiler fix`: two
`Translator` results CFR typed as `Object`, and `daysInMonth`'s compound array assignment CFR expanded
(one extra index constant) — audit 0 mismatches over the class's unedited methods.

### zombie.characters.IsoGameCharacter: the tempo/tempo2 statics off the worker path (`entityUpdateParallel`)

Run lou-pipe-off caught the dirScratch disease's sibling: ZombieEatBodyState threw the zero-length
ForwardDirection exception through `faceThisObject`, whose jar body fills the STATIC `tempo` Vector2 and
hands it to `DirectionFromVector` — two zombies facing anything concurrently share that one vector (the
vehicle branch even calls `setForwardDirection` unguarded). The worker-reachable users — `faceThisObject`,
`faceThisObjectAlt`, `facePosition`, `doDeferredMovement` (tempo) and `getMovementSpeed` (tempo2) — now
take `pzopt.UpdateBatch.tempoScratch()` / `tempo2Scratch()` (per-thread, a method-local shadowing the
static where the body uses the bare name, so the diff is one inserted line each). Per-thread scratch is
behaviour-identical to the static for a single thread by definition, so the game-thread paths are
untouched; the debug/render/death-path users (`renderDebugData`, `Throw`, `doDeathSplatterAndSounds`,
`isObjectBehind`/`isBehind`…) keep the statics and stay byte-identical to the jar.
ForwardDirectionScratchTest pins the five methods (jar reads the statics, override routes through the
scratch getters) beside its tempVector2_2 pins.

## zombie.ai.states.WalkTowardState: the singleton's scratch per thread (`entityUpdateParallel`, new override)

The scratch family's last member, caught by run lou-pipe-final with both static scratches already
converted: one WalkTowardState zero-length throw remained. The State instance is a SINGLETON — its
`temp` Vector2 and `worldPos` Vector3f fields are shared by every walking zombie on every thread, and
execute()'s whole direction computation (write targetX/targetY, subtract the position, offset, normalize,
setDir, setForwardDirection) runs on `temp` across that window. `execute` and `calculateTargetLocation`
now take per-thread vectors (`UpdateBatch.walkScratch()` / `walkScratch3()`) as method-locals shadowing
the fields — one inserted line each, the bodies otherwise identical, per-thread scratch being
behaviour-identical to the singleton field for a single thread. The fields themselves stay (enter/exit
and the jar's shape untouched). ForwardDirectionScratchTest pins both methods beside the
tempVector2_2/tempo pins.

### fmod.fmod.FMODSoundEmitter: the emitter under its own lock (`entityUpdateParallel`)

Run lou-pipe-clean2, the pipeline's first casualty: with bucket k airborne while bucket k+1 collects, the
inline player fought zombies of the airborne bucket — its combat wrote into a zombie's emitter (playSound →
the sound lists and the slot BitSet) while that zombie's worker task ticked the same emitter.
`BitSet.clear(-1)` latched batching off at f:15 (the guard worked), but the race had already corrupted the
emitter's sound list, and at f:366 the SERIAL path ticked the same emitter into
`FMOD_Studio_GetPlaybackState(NULL)` — a native-argument NPE on the game thread, fatal (PZ saved and
exited). This is also the branch's oldest ghost: the pre-guard wired run died with the identical
"Index -1 out of bounds for length 2" at frame 10. All 23 list-touching entry points of the emitter — tick,
the playSound family, stop/volume/3D/parameter, the isPlaying readers, the private stopSound overload —
now hold the emitter's own monitor (`synchronized`, the ZombieGroupManager idiom): uncontended on the
serial path, and every combination (worker vs inline game thread, worker vs worker) serializes.
`tests/pzopt/EmitterLockTest` pins ACC_SYNCHRONIZED on exactly that surface and its absence in the jar; a
runtime hammer needs the native FMOD system.

### fmod.fmod.FMODSoundEmitter: the NULL event handle refused (`entityUpdateParallel`)

Runs lou-pipe-clean2/3, both dying ~25-40 frames after route start at the ~4,200-zombie vocal storm, with
the emitter lock already held and no worker-side signature: a stock bug our throughput exposes. The
creation site guards FMOD's negative error codes (`eventInstance < 0`) but STORES the NULL handle (0) a
saturated Studio system returns; the 0 survives its first tick (`isStarting` skips the state read) and
kills the game on its second — `FMOD_Studio_GetPlaybackState(NULL)`, a native-argument NPE on whichever
thread ticks it, fatal on the game thread. The guard is now `<= 0` (refusing the NULL exactly as stock
refuses every other creation failure) plus a belt in `EventSound.tick`: a zeroed handle reports the sound
finished instead of reaching the native. Load-dependent, not thread-dependent — vanilla can hit this under
any heavy enough sound load.

### The pipeline's measurement (`devPipelineAlternate`, and why the cross-run numbers lied)

Cross-run Louisville comparisons are confounded twice: population=max spawns vary per run, AND a faster
build loads MORE zombies by route start (the count is taken at route start), so an improvement penalizes
itself. `devPipelineAlternate=N` is the repo's devPplAlternate pattern applied to the batch: the bucket
seam flips entityUpdatePipeline on/off every N seconds inside one run (each flip logged with its epoch),
so the frame log splits into paired windows over the identical population. First reading
(lou-pipe-alt, 45 s route, 4 s windows, 3 route windows per mode, 2,944 zombies): a wash — mean 38.8 ms
off vs 39.7 ms on, p50/p90 slightly for on, p99/max for off. The overlap window as first built holds
little (collection is microseconds and the join helps), so the win waits on real work moving into it
(deferred ticks, replay distribution); the key ships ON by the maintainer-side decision of 2026-09-26,
with this measurement as the honest record and the rig as the permanent instrument.

## zombie.statistics.StatisticsManager: the statistics map locked for the batch (`entityUpdateParallel`, new override, 2026-09-27)

The first live modded session (Windows, 322 mods) latched batching off at frame 1: a worker's zombie update
ran `IsoZombie.updateMovementStatistics` → `StatisticsManager.incrementStatistic`, whose `computeIfAbsent`
raced another worker's on the singleton's plain `HashMap` — `ConcurrentModificationException`, one report,
serial for the rest of the session (correct, the latch working as designed, but unbatched: the whole session
measured the fallback path). The bench saves never see this because the statistics path only ticks with a
consumer installed; the live save runs a daily-statistics mod.

Every map-touching method of the manager (`incrementStatistic`, `setStatistic`, `getStatistic`,
`getStatistics`, `getAllStatisticsDebug`, `load`, `save`) is now `synchronized` — the ZombieGroupManager /
FMODSoundEmitter idiom, uncontended on the serial path. The `Statistic` increment itself is a
read-modify-write and rides the same lock, so concurrent increments stop losing updates too. The lambdas and
`getInstance` stay lock-free (they run under the caller's monitor or touch no shared state). `getStatistics`
returns the live map (stock behaviour, kept); its callers iterate on the game thread, which the lock does not
cover while a pipeline flight is airborne — no live sighting, watched for.

`tests/pzopt/StatisticsLockTest` pins it structurally like EmitterLockTest: the jar's methods carry no
ACC_SYNCHRONIZED, the override's seven all do, no strays, method set otherwise identical. No runtime hammer:
`incrementStatistic` calls into `AchievementManager`, whose initialisation needs platform state a bare JVM
does not have.

## The batched zombies' emitter ticks land at the join (`emitterDefer`, 2026-09-27; IsoGameCharacter + UpdateBatch)

The pipeline's overlap window held nothing (the lou-pipe-alt wash above): the flight's dear part is the
zombies' FMOD work serializing on the emitter monitors (the FMODSoundEmitter locks) — worker against worker,
and against the inline player's combat writes. `updateEmitter` now defers on a batch task: the character
queues itself on its task's slot (the Lua capture's per-task shape, so queue order = stock's serial order)
and returns; `joinPending` runs the queued ticks on the game thread right before the Lua replay, under the
flight's captured multiplier, in the same frame. The idle-skip early-out still runs on the worker (an idle
zombie never queues); the inline path never defers (the slot is only set on batch tasks, and only when the
key was on at dispatch); a failed batch still drains what deferred before the throw. A side effect worth its
own line: the prone-zombie branch of `updateEmitter` writes `CombatManager`'s STATIC `tempVectorBonePos` —
the scratch-family disease again — and moving the whole tick to the game thread retires that hazard instead
of converting it.

Key `emitterDefer`, default on (inert without `entityUpdateParallel`). Counters `emitterDeferred` /
`emitterDrained` on the batch status line. `tests/pzopt/EmitterDeferTest` pins the jar's updateEmitter
pzopt-free and still on the static bone scratch (a TIS rework re-reads the override), the override routed
through `deferEmitter`, the join's drain calling the real `updateEmitter`, and the off-task refusal. The
win claim waits on the alternation rig (the measurement rule above); until that run lands this ships as
a correctness + contention change, not a numbers claim.


Measurements around the deferral (2026-09-27, Windows desktop, 9950X/4090, chunkGridWidth=25, max
population, 90 s S:300 route at max zoom, the maintainer's own options file):

- The branch's headline pair, back to back on the same route (runs lou-oursoff / lou-ourson, only
  entityUpdateParallel + animalLosFast flipped, everything else identical): OFF = 12,434 zombies,
  101.7 ms mean (9.8 fps), p50 86.0, p99 282; ON = 12,139 zombies, 57.7 ms mean (17.3 fps), p50 41.7,
  p99 206. The median frame halved at twelve thousand zombies; the counts differ 2.4 % in OFF's favour,
  so the true gap is at most a hair smaller. Zero exceptions on either side.
- The pipeline alternation runs (lou-defer-alt, lou-defer-alt2) are NOT clean pipeline evidence: the
  worst windows were GPU bake bursts (230 ms gpu_ms frames landing on whichever window parity the wall
  clock chose) and the batch counters exposed the real defect — `async helped` ≈ 100 % of batched tasks,
  i.e. at the join the workers had claimed essentially nothing: the overlap window as built is
  structurally EMPTY. The join sits at the next bucket's seam, the dominant batch is followed by
  trivial collections, and the game thread arrives at the join before the workers wake from the gate
  monitor. The pipeline is currently sync-with-extra-steps; the fix (join at first dependency, workers
  pre-woken at dispatch) is the next pass, and until it lands no pipeline number is claimed either way.

## The game-thread offload pass (2026-09-27, branch `gt-offload`; findings in `docs/findings-gt-offload-2026-09-27.md`)

Every key below reads its Config value unless `devGtAlternate=N` with `devGtAlternateKeys=...` alternates it on and off every
N ms inside one run (`pzopt.GtAb`, `harness/gtab.py`): the off half takes the old path, so both halves share the scene.

### zombie.iso.fboRenderChunk.FBORenderCell (`renderPrepParallel`, `pplPackParallel`, dev section timers)

- `performRenderTiles`, after the god-ray camera call and before the chunk composite: `pzopt.RenderPrep.start` hands the
  characters draw pre-pass's on-screen list to the frame workers, which compute each object's sun share for the capsule
  shadows and its water / puddle proximity for the reflections while the game thread composites and draws the players,
  corpses, items and puddles.
- `renderMovingObjects`: joins that batch before the per-object loop, tells `RenderPrep` the index each object is drawn
  at (so `CapsuleShadow.add / addAtlas / addVehicle` and `Ssr.addMoving` take the precomputed answer when the object at
  that index is theirs), and clears it after the loop; the loop without the pre-pass list only joins and clears.
- `performRenderTiles` / `renderMovingObjects` / `PixelLight.beforeComposite` call sites wrapped in `GtAb` section timers
  (inert unless an alternation runs).

### zombie.MovingObjectUpdateScheduler (`schedulerClassifyParallel`, `zombieStatsFold`, dev section timers)

- `startFrame`: the stock body moved to a private method so the public one can time it; with the key on, the loop over
  the cell's objects is `pzopt.SchedulerClassify.run`: the game thread fixes a missing square first (the loop's one
  write; the classification never reads another object's square) and snapshots the set's order, the frame workers
  compute each object's simulation level through the stock method, the game thread fills the buckets and the separation
  batch in the snapshot's order. New public helpers: the worker entry (returns null for a character whose animation
  player the body-model check would replace, which the game thread then classifies), the stock classification, and the
  loop's bucket / separation step. `devSchedCheck=true` compares every worker result with the game thread's own.
- `update`: body moved to a private method (timed); `pzopt.ZombieStats.begin / end` around the bucket loop (after the
  entity batch's final join).

### zombie.characters.IsoZombie (`zombieStatsFold`, `entityUpdateSafeStates`)

- `updateMovementStatistics`: while the scheduler's loop runs, the travel distance goes to `pzopt.ZombieStats`, which
  adds it to a per-statistic float in the zombies' order (started from the statistic's value) and writes it back once
  after the loop, checking the achievement once per statistic: bitwise the same totals, the same unlocks, one map
  lookup instead of ~2,000. A zombie updated on a frame worker (entityUpdateParallel) adds to its thread's partial sum,
  added after the game thread's.
- `update`: a dev counter of zombie updates per frame for `pzopt-gtab.out`.
- New `pzoptBatchSafe`: the calm-state whitelist `UpdateBatch.batchable` uses when `entityUpdateSafeStates` is on (idle /
  walk toward / path find; no ballistics target, ragdoll, fall, fire, grapple or reanimated player; no animation player
  about to be replaced; no player within 12 squares). PR #35's crash under fire (Bullet's ballistics target released on a
  worker) was a zombie outside this set.

### zombie.characters.IsoGameCharacter (`schedulerClassifyParallel`, `entityUpdateSafeStates`)

- Two read-only helpers: whether `getAnimationPlayer()` would replace the animation player (the body model changed), and
  whether the animation player has a ragdoll (read from the field, never through the replacing getter).

### zombie.characters.animals.IsoAnimal (new override, `animalLosFast`)

- Vineflower decompile, compiles unchanged (bytecode audit clean). `updateLOS`: a zombie whose `BaseAnimalBehavior.spotted`
  call can only clear `spottedChr` and tick `lastAlerted` down to zero (the animal does not flee zombies, or the zombie is
  farther than 10 squares at the stock float distance: every branch that acts on a zombie needs 10, 6 or 3 squares, and
  no random roll is reached) is counted instead of called; the counted ticks are applied, in the same float steps, before
  the next `spotted` call that can act and at the end of the walk, so they keep their order against the calls that set
  `lastAlerted`. No vanilla animal definition sets `fleeZombies`, so the distance branch is the one that applies. PR #35
  reached the same fold (its squared-distance margin, `pzopt.AnimalLos`); this version uses the stock distance itself.
  `updateLOS` call site timed (dev).

### zombie.characters.IsoPlayer (`losLightPrefetch`, off; dev section timer)

- `updateLOS`: before the walk, `pzopt.LosPrefetch` refreshes the stale lighting of the moving objects' squares on the frame
  workers (one task per chunk level through `LightingBatch`, room / meta hooks deferred). Measured slower than the walk's
  own lazy reads (~130 ns a square), so the key stays off. `updateLOS` call site timed (dev).

### zombie.iso.LightingJNI (`losLightPrefetch`)

- `JNILighting`: two helpers, whether the lazy refresh would read the native now, and the lazy refresh itself for a
  prefetch task.

### zombie.iso.IsoChunk (`losLightPrefetch`)

- Two fields: the prefetch frame and the per-level task index of that frame.

### zombie.iso.IsoWorld (dev section timer)

- The scheduler's `postupdate` call timed.

### zombie.core.properties.PropertyContainer (`entityUpdateParallel`)

- `initSurface`: the lazy surface / slope parse is read from frame workers too (`updateFalling -> hasSlopedSurface` on a
  batched zombie). Stock set the "parsed" bit before filling the fields, so a second thread could read half-parsed values;
  now the fill runs under the container's monitor into the same fields and the bit is set last behind a release fence,
  readers take an acquire fence after seeing it. Serially the same values in the same fields. Superseded on 2026-09-28 by
  PR #35's own fix (below, `SurfaceInitTest`): the walk accumulates into a per-thread scratch and the volatile flag byte is
  published last in one store; the monitor and the fences were dropped in the merge.

### fmod.fmod.FMODSoundEmitter, zombie.characters.AttachedItems.AttachedItems, zombie.statistics.StatisticsManager (PR #35's locks)

- PR #35 made the list-touching methods `synchronized`, which changes their signatures and fails build.sh's stock-member
  check; each is now the stock signature with the same monitor taken as a block around the body (`tests/pzopt/*LockTest`,
  `AttachedItemsSyncTest` check for the monitor in the body).

### zombie.vispoly.VisibilityPolygon2 (`visPolyAsync`)

- `renderMain`: takes the drawer `pzopt.VisPolyAsync` computed on its own thread since the top of the tile render when it is
  this frame's drawer, else computes it as stock. Two helpers: this frame's drawer when the polygon may be computed ahead (one
  player, the game's vision-polygon switch and new visibility on), and the worker's entry (the stock calculation). The Drawer
  gets a flag under which the chunk walk reads a chunk's render levels without creating them (a chunk without them was never
  on screen, which the stock test would have answered the same way after creating them).

### zombie.iso.IsoChunk (`visPolyAsync`)

- An accessor for the per-player render levels that does not create them.

### zombie.iso.LightingJNI (`entityUpdateParallel`, the lighting race PR #35 left open)

- `JNILighting.update`: on an entity-update batch task (`pzopt.LightingDefer.current()` non-null) the fboRenderChunk refresh runs
  under the square lighting object's monitor. `updateFBORenderChunk`: on such a task every side effect of a refresh (the level
  invalidation, LightDirt's bookkeeping, the puddle batch's light change, the cutaway check reset, the first-refresh
  invalidation, the room / meta "square seen" hooks) goes to the task's list instead of running on the worker, and the worker
  never creates the chunk's render levels; the game thread runs the lists at the batch's join in task (= stock) order. Off a
  batch task nothing changes. Helpers for the deferred invalidation and LightDirt call.

### zombie.iso.fboRenderChunk.FBORenderCell (`visPolyAsync`, `aoContextParallel`)

- The cell render starts `pzopt.VisPolyAsync` right before `performRenderTiles`; `performRenderTiles` calls
  `pzopt.ChunkAo.tilesBegin` first (the bakes of the frame then defer their AO / sun-shadow world masks to the batch
  `ChunkAo.flush` runs after them).

### zombie.iso.fboRenderChunk.FBORenderCell + zombie.iso.IsoChunk (`translucentOrderCache`)

- `renderOneLevel_Translucent`: the merge of the level's three cached square lists (items, cutaway window frames, translucent
  objects; a `contains` per insertion) and the world-order sort run only when `pzopt.TranslucentOrder` has no kept order whose
  inputs (the three lists' squares in order and their coordinates) equal this frame's; the kept order is otherwise copied into
  the same scratch list, and a freshly sorted one is stored. The render loop after it is stock. `IsoChunk` holds the kept orders
  per player and level and drops them when the chunk object is reused. `devTlOrderCheck=true` recomputes the stock merge and
  sort on every reuse: 369,052 reuses, 0 mismatches on the flip's 120 km/h drive.

### zombie.core.skinnedmodel.animation.AnimationPlayer + zombie.characters.IsoGameCharacter (`ragdollCorpseGuard`, 2026-09-27)

- `initRagdollController` makes no controller for a character that has already turned into its corpse (IsoGameCharacter gets
  an accessor: its dead body is set; it is cleared when a zombie is reused). Stock rebuilds one in the frame a zombie dies: the
  settled ragdoll's controller was released, the action state moves to onground, `ZombieOnGroundState.enter` calls `die()` (the
  zombie becomes its IsoDeadBody and leaves the world), and the same `postUpdateAnimating`'s model update still finds the
  ragdoll track. That controller belongs to nobody: an extra Bullet ragdoll until the zombie is reused, counted against the
  active-ragdoll cap, and the SIGSEGV in `Ragdoll::deleteRigidBodies` when a game is quit while one is still there. Measured
  with `devRagdollLedger=true` on the horde-shoot bench: stock 15 of 40 controllers were such, ours 47 of 109 (more kills a
  second). Off with `enabled=false` like every hook.
- `initRagdollController` / `releaseRagdollController` report each controller to `pzopt.RagdollLedger` (bookkeeping for the
  sweep below).

### zombie.core.physics.WorldSimulation (`ragdollQuitSweep`, 2026-09-27)

- `destroy()` first lets `pzopt.RagdollLedger` remove every ragdoll still in the Bullet world (and log it). libPZBullet's
  world destructor deletes the dynamics world before the ragdolls left in its id map, whose destructors then remove their
  bodies from the deleted world through a null vtable slot (the quit crash above).

### zombie.iso.IsoMovingObject (`devRagdollLedger`, 2026-09-27)

- `removeFromSquare` reports the object to `pzopt.RagdollLedger` first (a dev key: the stack where a dead character left its
  square and its ragdoll state at that moment; nothing without the key).

## Detailed shadows (2026-09-27)

Write-up: `docs/findings-detailed-shadows-2026-09-27.md`. Classes: `pzopt.TreeSilhouette` (the trees' silhouettes),
`pzopt.ChunkAo` (the kernel's tree cards), `pzopt.CapsuleShadow` (the silhouette pass of characters, animals and vehicles).

### zombie.iso.fboRenderChunk.FBORenderCell
- Right after `renderMovingObjects`: `CapsuleShadow.afterMoving` (with `sunShadowSilhouette` the frame's caster pass is
  queued there instead of right after the chunk composite: it reads the casters' own depth).
- After each `renderShadow` call of the player, a moving character and a vehicle: `CapsuleShadow.stockShadowDone` (the
  stock blob's fade set by the caster's `add` applies to that one shadow only).

- Right before `GodRays.queue` (the scene depth complete): `CapsuleShadow.beforeFog` (with `sunShadowPassLate`, the
  default, the caster pass is drawn there, its scene depth read beside the god rays' and the fog's).

### zombie.core.textures.TextureDraw
- Fields `pzoptShadowTile` / `pzoptShadowX,Y,Z,Half`: a DrawModel's atlas tile for its sun draw (`sunShadowMeshes`).
- `drawModel` (game thread): `CapsuleShadow.tileFor(modelSlot, texd)` fills them (-1: no sun draw this frame).
- The DrawModel case (render thread), after the model's own draw: `ShadowAtlas.renderCaster(...)` queues the sun draw
  (drawn at the frame's `ShadowAtlas.flush`).
- Fields `pzoptLampN` / `pzoptLampDraw` (`sunShadowLampMeshes`): the caster's lamp views to draw this frame (tile, centre,
  half size, lamp position each; filled by `tileFor`); after the sun draw `ShadowAtlas.renderLamps(this)` queues them (a
  perspective view of the model from the torch or headlight into its own atlas tile).

### zombie.characters.IsoGameCharacter
- Fields `pzoptShadowStamp`, `pzoptShadowTile`, `pzoptShadowX,Y,Z,Half`: the character's atlas tile and a scheduled
  sun draw (`CapsuleShadow.assignTile` / `tileFor`).

### zombie.core.textures.MultiTextureFBO2
- `render`, first: `CapsuleShadow.queueAtlasFlush()`: the frame's sun draws after the world pass, before the screen
  composite (a render target switch in the middle of the world cost the GPU more than the draws).

### zombie.iso.fboRenderChunk.FBORenderShadows (new override)
- The texture overload of `addShadow` (every stock blob and vehicle shadow goes through it) multiplies its alpha by
  `CapsuleShadow.stockShadowScale()`: 1, except between a caster's `add` and its `stockShadowDone` when it casts a real sun
  shadow (then `1 - sunShadowStockFadePct x its sun share`: the blob under the feet was a second shadow beside the sun's).

## The combined dispatch: the pipeline's window made real (`entityUpdatePipeline`, 2026-09-27; UpdateBatch + FrameBatch, bucket and scheduler overrides)

The measurement above named the defect: `async helped` was about 100 % of `batched`, so at the join the
workers had claimed essentially nothing and the game thread ran the whole batch itself. The pipeline as
built joined at the *next* bucket's seam, the dominant batch is the fully simulated bucket's, and the
buckets after it are trivial collections, so the game thread reached the join before the workers woke from
the gate monitor. The overlap window was not small, it was structurally empty, and the feature was
synchronous with extra bookkeeping.

What the frame does now on a pipeline frame. The scheduler latches the frame's shape once, at its entry,
and that latch is the only place the wall clock is read: with the alternation rig a window flip now lands on
a frame boundary instead of leaving one frame's early buckets queued while its late buckets execute. Every
bucket then only collects. A batchable entity is queued together with its own simulation level and its own
per-object multiplier, both taken at queue time, because one flight now spans every bucket of the frame and
the single dispatch-time scalar the old shape captured is no longer meaningful. An entity that is not
batchable -- the player, animals, vehicles, physics objects -- goes into a second queue instead of running
in the loop. After the last bucket the scheduler dispatches all of the frame's batchables as one
workers-only flight, and then runs that second queue on the game thread while the flight is airborne, each
entity under the multiplier its own bucket had and in the order the walk found it. That game-thread work is
the runway: it is the only reason a worker can have claimed anything by the time the join runs. The join
stays at the scheduler tail, ahead of the postupdate loop and the zombie vocal walk, which is the frame's
first reader of every zombie.

The whole tail sits in a `finally`, and the join sits in a `finally` inside it. Two failures drove that. A
bucket that throws must not cost the frame the work already queued -- in the old shape the entities of the
buckets before the throw had already run, and silently skipping the player's update for a frame is not an
acceptable translation of that. And an inline entity that throws -- a player update running Lua is the most
likely thrower in the method -- must not leave a flight airborne, because the vocal walk immediately after
reads every zombie. The exception still propagates; only the landing is made unconditional.

The landing itself became per task. The deferred emitter ticks and the replayed Lua events are still drained
on the game thread in queue order, but each entity's are now drained under that entity's own multiplier
rather than the flight's, which on this shape would be meaningless. The snapshot window is sized from the
counted inline queue plus a small margin instead of the old fixed guess, since the walk has finished before
the dispatch and the count is exact.

A nested batch is the one reentrancy the shape invites: an inline entity's update can reach code that itself
uses the frame pool, and the pool runs one batch at a time. Both pool entry points now land the entity
flight first, through the full landing rather than a bare join, so the deferred tile updates, the drains, the
replay and the failure latch are not skipped. It has to sit ahead of the pool's own in-progress check, which
cannot stand in for it: that flag is false for the whole combined flight and false again during the inline
phase, which is exactly the window the guard exists for. It deliberately does not fire from a worker, nor
from the game thread while it is working a batch task, because the join there would wait for a count that
includes the caller's own unfinished task -- that reentrancy is the pool's pre-existing hazard and the guard
leaves it exactly as it was instead of converting it into a deadlock.

Two findings fell out of building it. The pool's very first batch of a session ran almost entirely on the
calling thread, because every worker's first act is the core-placement call and the first worker to arrive
paid that class's initialization -- seventeen milliseconds, measured -- while the others queued on the
class-init lock. The pool now forces that initialization on the calling thread when it starts the workers;
in the game it is free, since the frame limiter has made the same call since boot. And the runway metric had
to be attributed rather than read: the pool is shared with the animation, lighting, separation and character
draw batches, several of which join later in the same frame, so the pool's last-join value belongs to
whichever batch joined last. The entity path now accumulates its own total at its own join.

Key `entityUpdatePipeline`, unchanged in name and still inert without `entityUpdateParallel`. The old
per-bucket asynchronous dispatch has no caller left; the method stays one release, as the synchronous
shape still reaches it, and the synchronous per-bucket path is byte-for-byte the rig's control arm. New
counters on the batch status line: `combinedFrames`, `inlineQueued`, `nestedJoins`, and `preClaimed` --
the tasks the workers had claimed when the join started, summed per flight. `preClaimed` is the acceptance
number for this change, and `batched` minus `preClaimed` is this path's own async-helped total, which is
the quantity whose near-equality with `batched` was the defect; the pool's global `async helped` line is
shared with the other batches and must not be used for it.

`tests/pzopt/CombinedDispatchTest` pins the machinery at runtime on real moving objects: per-entity
multiplier and level across two queued groups in one flight, the inline queue's order and per-group
multiplier with the global restored to one afterwards, the latch's own semantics including that it drops a
queue left over from a frame that never dispatched, the nested guard landing the flight fully and counting
it, a throwing task still latching the batching off with the frame still landed, and -- the assertion the
whole change exists for -- that after a dispatch followed by three milliseconds of game-thread work the
workers had claimed tasks before the join, which the old shape never achieved. `tests/pzopt/PipelineTest`
pins the new seam in bytecode: the bucket defers inline entities and no longer dispatches, the synchronous
per-bucket path survives, and the scheduler latches once and then dispatches, runs the inline phase and
joins.

One exposure is worth stating plainly rather than burying. The player now updates concurrently with every
zombie of the frame, where the per-bucket shape overlapped it only with the previous bucket's. The position
snapshot covers the coordinate reads; any other read from the player into a zombie is newly concurrent over
a wider set. The feature is opt-in and off by default, and the alternation rig is how that gets watched.

No performance number is claimed here. The change is a shape change with a metric attached, and until an
alternation run reports a non-zero `preClaimed` with its zombie counts and its processor-bound windows
separated from its graphics-bound ones, this entry stands as a correctness and structure change only.


## Bullet stays off the workers (`physicsDefer`, 2026-09-27; IsoGameCharacter + IsoZombie + UpdateBatch)

With `entityUpdateParallel` on, shooting zombies killed the game. Not an exception, not a latched-off
batch: the process died, twice out of two runs, and it was the maintainer firing a gun on a normal route
that found it. The crash file names the native frame at the top — the overlapping-pair cache of the
physics library removing a pair — and under it, on a batch worker thread, the Java frames that led there:
a character's ballistics target being taken out of the physics world from inside the character's own
update, which the batch was running on that worker. The benchmark routes never fire a weapon, which is
the whole reason this survived into a pull request: the feature's own measurements cannot reach the bug.

The hitbox path is not exotic. Whenever a gun is aimed, the combat code gives every character in the
aim cone a ballistics target, and from then on each of those characters pushes that target into the
physics engine once per frame from its update: into the world on the first frame, then its axis, then its
position, then its whole skeleton, and out of the world again when the target expires. That is five
native entry points, all of them reached from the one call at the top of a character's internal update.
With the batch on, a dozen workers make those calls while the game thread makes its own for the player,
the vehicles and the bullets. The engine was never written for that, and the failure mode is a segfault
rather than an exception, so our failure latch — which turns batching off for the session when an entity
throws — cannot see it, let alone recover from it. A crash that the safety net structurally cannot catch
is not a tuning question.

Locking our side of the call would not have been enough either, which is the second half of why this had
to be a deferral. The skeleton push does not hand the engine a per-target buffer: it fills one public
static float array shared by every character in the game and passes that. The target's own initialisation
replaces that array with a longer one when a taller skeleton turns up, mid-fill as far as any other
thread is concerned, and the game thread writes the same array when the combat code first registers a
target. Two workers filling it at once, or one worker filling it while it is being replaced, is memory
corruption regardless of what the native does with threads. Deferring is what fixes that, because the
game-thread side of it only ever runs on the game thread.

So on a batch task the character's hitbox update queues instead of running, and the game thread makes the
call at the join, in queue order, in the same frame. The queue entry is taken only when the character
actually has a target — the check was already the first thing the method did, and keeping the deferral
behind it is what stops twelve thousand zombies from queueing an entry a frame for a target almost none
of them have. Reading that field without a lock is safe here for a plain reason: a reference read cannot
tear, so a worker sees either nothing (and the target, created this frame by the shot, updates one frame
later) or a valid target (and the game thread re-reads the field at the drain anyway, so a target
released in between is simply not updated).

One behaviour difference, worth stating plainly rather than burying: stock makes this call at the top of
the character's update, before the character moves; the join makes it after. The hitbox therefore sits at
the zombie's post-move position instead of its pre-move one. That is a fraction of a tile, and it is
arguably the more correct of the two — you are shooting at where the zombie is, not where it was — but it
is a real change and anyone chasing a hit-registration report should know it is there. Nothing else
moves: the animator writes the pose during postupdate, and the join happens before postupdate, so the
deferred call reads exactly the pose the inline call would have read.

The second escape is rarer and was not what crashed, but it is the same hazard and shipping a fix for one
of them would have been dishonest. A zombie in ragdoll asks its ragdoll controller to test whether it is
touching a vehicle, and that test both writes ragdoll body dynamics into the engine — through another
static parameter array — and walks a vehicle's collision geometry, a vehicle the game thread may be
updating at that moment, since vehicles are one of the types the batch keeps inline. It needs a
ragdolling zombie in contact with a car to fire at all, which is why nobody had seen it. It defers the
same way, with one difference: the call takes the vehicle as well as the zombie, and the zombie's update
clears its vehicle field a few lines further down, so the vehicle reference travels with the queue entry
instead of being read again at the drain. The controller, by contrast, is re-read on the game thread: if
the rest of the frame released the ragdoll there is nothing left to tell the engine, and the drain simply
skips that entry. At the join the vehicles have already finished updating, so this call is if anything
better placed than it was.

Both queues sit on one per-task slot under one key, drained at the join in the order they have inside a
single zombie's update — the vehicle contact test first, since it runs above the point where the zombie's
update reaches its character update, and the hitbox second — and both before the Lua replay, so no handler
of a captured event can observe a hitbox that is still where the zombie stood last frame. The slot is set
only on a batch task and only when the key was on at dispatch, so the game thread and the inline path
never defer and the drain runs the real work with the slot unset. A batch that failed still drains what
was queued before the throw, exactly as the emitter drain does.

Everything else we could find stays where it was. All five hitbox natives and both ragdoll-dynamics
natives are now unreachable from a worker; every other physics call in the game belongs to the vehicles,
the chunk collision meshes, the world simulation step or the rest of the ragdoll pipeline, and those run
on the game thread — the ragdoll simulation in particular runs from postupdate, after the join. One
theoretical hole is left open deliberately: a character with an aimed firearm raised also updates its own
ballistics controller from its update, and that reaches the engine too, but a vanilla zombie never holds
an aimed firearm and never aims, and zombies are the only characters the batch takes. A mod that arms
zombies would reopen it, and that is the line to re-read first if one ever reports a crash here.

Key `physicsDefer`, default on and inert without `entityUpdateParallel`; it is a crash fix, so it is not
opt-in. Counters `ballisticsDeferred` / `ballisticsDrained` and `ragdollDeferred` / `ragdollDrained` on
the batch status line, beside the emitter pair. `tests/pzopt/PhysicsDeferTest` pins both escapes in the
game jar (a rework by the developers fails the build instead of silently un-deferring the fix), the jar's
hitbox update still native and still fed from the shared static buffer, both overrides routed through the
new helpers, the join draining both ahead of the Lua replay, each drain calling the real work, the
off-task refusals, and the key's default. No performance number is claimed: work moved from the workers
to the game thread, which is a cost, and the reason to pay it is that the alternative is a dead process.

## The surface properties published once, a torn alias index skipped (`entityUpdateParallel`, 2026-09-27; PropertyContainer + UpdateBatch)

A Louisville horde route with a radius-150 sound pulling the horde in threw an index-out-of-bounds about
three seconds into the route: index -1 into a list of 235, out of the tile-property alias lookup at the top
of the surface walk, reached from a worker's falling update through the height-above-floor test and the
square's sloped-surface question. Nothing crashed — the batch's failure latch caught it, reported it once
and turned batching off — but that is the expensive part: the rest of the session ran the stock serial
loop, so a run that was there to measure the parallel path measured the fallback instead. Both defects
behind it are stock's own lazy-init shape, not something the batch introduced; the batch only supplied the
second thread that makes them reachable, and the same second thread has always existed in the shape of
chunk streaming.

The first defect is that the done flag was published before the work. The lazy init is guarded by one bit
of the surface flag byte, and stock set that byte at the top of the block — before the entry walk fills the
surface height, the stack-replace offset, the item height and the three sloped-surface values. A second
thread entering the guard while the first is mid-walk therefore returns immediately and reads fields that
are still at the values the block reset them to a few instructions earlier: a square that has a sloped
surface answers that it has none, and one that is a table answers that it is not. That is a silent
wrong-value bug rather than the crash, and it had no report of its own; it was found reading the code the
crash pointed at.

The second defect is that the walk takes a torn view of the backing map. A property container is a
primitive short-to-short hash map whose occupancy, keys and values live in three parallel arrays, and the
walk reads a state byte, then the key, then the value, one array at a time. The library writes the map's
no-entry key into the key array *before* the state byte stops saying occupied, on a removal and on a clear
alike, and a property container's no-entry key and no-entry value are both -1. So a walker holding a state
byte that still says occupied can read a key of -1 and hand it to the alias list, which is exactly the
index and exactly the list the crash carried. The mutator is stock's own square property recalculation,
which clears the container and refills it from the square's objects, and which chunk streaming calls from
its own threads as well as the game thread calling it for every door, window, fire, corpse and vehicle
impact. A rehash is the other way to tear the view, but a freshly allocated key array is zero-filled, so a
key read across a rehash comes out as a valid index — silently wrong, never -1. Our no-allocation walk and
the stock lambda walk it replaced are equally exposed: the library's own entry walk hoists the same three
arrays into locals in the same order and reads the same three slots per entry, so this is not a window our
edit opened or widened.

The fix for the first defect is to compute into a scratch accumulator and publish once. The walk fills a
per-thread scratch object with the six values and the flag bits, and the block then assigns the six fields
and writes the flag byte last, with the valid bit and the accumulated bits in one store. Two threads may
both do the work, which is harmless because it is idempotent and they derive the same values from the same
entries, but no thread can observe the flag over unfilled fields. The flag bits used to be OR-ed into the
field from inside the per-entry handler, a read-modify-write that loses a bit when two walks overlap;
accumulated and stored once they cannot. Nothing in the single-threaded case changes: the final values are
the same, and the only visible difference is that a throw inside the walk now leaves the flag clear so the
next call retries, where stock left it set over half-filled fields.

Publishing last only orders the writes for the thread doing them; a reader of a plain field can still be
handed a stale value, so the flag byte is volatile. That buys the ordering as a real happens-before for the
price of a compiler barrier on the read path, which is free on x86 and a load-acquire on Apple silicon, and
one fence per publish and per invalidation. It is viable here because the field is private and used only
inside the class, which also means no method's bytecode changes — a field's volatility is not part of any
instruction — so the parity audit still compares every unedited method of the class against the jar's, and
the structural signature check never saw the field at all.

The fix for the second defect is to refuse an out-of-range alias index instead of throwing on it. The
per-entry handler now skips an entry whose property index falls outside the alias list, and likewise one
whose value index falls outside that property's value list, since the value array tears the same way. A
torn read is a transient condition — the entry contributes nothing this time and the next call re-derives
the value off an untorn view — and turning it into an exception is what cost a whole session's
measurements. Skipping silently would be worse than either, so each skip counts on the batch status line
beside the pathfinding-race count, under its own name.

`tests/pzopt/SurfaceInitTest` pins all of it. Two pins are bytecode: the jar's init writes the flag third
of seven and ours writes it last and exactly once, and the jar's field is plain while ours is volatile, so
a rework by the developers fails the build rather than quietly un-fixing this; a third asserts the
per-entry handler writes none of the container's own fields. The torn-entry cases build the exact array
state a removal and a clear leave behind — an occupied state byte over a -1 key, over a key past the end of
the alias list, and over a -1 value — and assert the walk skips it, counts it once and still derives every
other property correctly. The publication case is one-sided by construction: with the publish-last shape in
place a reader can only ever see the flag together with the values it was published with, so the test cannot
fail for a timing reason, while against the old shape three readers against one invalidator catch the flag
over the reset defaults immediately. No performance number is claimed; the change adds a scratch reset and
a fence to a path that runs once per container per invalidation, and the reason to pay it is that the
alternative is a session that silently stops measuring what it was launched to measure.

## The pathfinding race was the class's own static scratch (`entityUpdateParallel`, 2026-09-27; PathFindBehavior2 + UpdateBatch)

This supersedes the PathFindBehavior2 entry further up, whose two layers are both retired here. That entry said
PZ's asynchronous pathfinding writes a character's path list from its own thread while a batch task reads it, and
answered with a defensive copy of the list taken at the top of the update plus a catch that turned an index or
state exception on a batch task into a report of still working. A route log then showed between seventy-seven and
just under a thousand of those catches, which is what sent the reading back for another look. The reading was
wrong, and both answers were wrong with it.

The writer, precisely. Both pathfinders run their search on their own thread and both write only the request's own
path object, never a character's. The single piece of code that copies a finished search into a character's path is
the behaviour's success callback, and its only two callers in the whole jar are the main-thread update of the Java
map and the main-thread update of the native pathfinder. Both of those run on the game thread, from the game
state's world-and-managers step, which reaches them only after the world update has returned. The entity flight
opens and lands inside that world update, because the scheduler joins it in a finally before returning, so a
delivery cannot overlap a batch task at all. On top of that the behaviour's path field is private, so nothing
outside the class can reach the list even in principle, and every method of the class that mutates it is invoked
on the behaviour of the entity being updated, never on another entity's. So the copy was guarding a writer that
does not exist inside the window, and it cost one list copy per batched zombie per frame to do it.

What does race is four static mutable scratch objects the class keeps for itself: two two-dimensional vectors, a
three-dimensional vector, and one point-on-path record. Every thread running the update, the two move helpers, the
crawling-transition check and the path-to-character setup writes and reads those same four objects. The sharpest
is the point record. The update asks the static helper for the closest point on this character's path, handing it
the shared record, and reads the path index back out of it on the very next statement. A second task landing
between those two statements hands this character an index derived from its own, differently sized path. If that
index is past the end, the node lookup a few lines later throws, and that is the entire population of catches the
counter was reporting. If it happens to be in range, nothing throws and the character walks the wrong segment of
its own path in silence. That silent case is the one that mattered, and neither the copy nor the catch touched it.
The vectors tear the same way and in both directions: the deferred movement is read into the shared vector and
consumed as a move roughly thirty lines later, so a zombie could be stepped by another zombie's movement vector,
and the zero-length check in front of the forward-direction write guards a different read of the same shared
object, which is how a constant direction could still arrive as a zero-length vector.

The shape chosen is neither of the two the review suggested, because neither applies. Deferring the delivery to
the join would move a write that is already outside the flight; taking a copy of the path at dispatch would freeze
a list nothing concurrent writes. The fix is instead the idiom this repo already applies three times over to the
same disease, in the character class's direction and facing scratch and in the walk state's singleton scratch:
each of the four objects becomes a per-thread object on the batch helper, and every method a batch task can reach
takes its scratch from there. On one thread the behaviour is exactly what it was, because each use writes and then
reads inside a single method on a single thread, and that includes the residual state the class relies on, since
the closest-point helper resets the index but not the distance and the advance helper accumulates into it. Stock
carried that residue over from whichever call ran last anywhere; a thread's own object carries it over from that
thread's own calls, which is the repair rather than a change. Across threads there is no shared write left. In
each converted method the local deliberately takes the same name as the static field it replaces, so the body is
otherwise untouched, and the test pins in bytecode that no read of any of the four statics survives in any of
those methods, which is what turns a missed use into a build failure instead of a silent hole.

Three places were deliberately left on the statics. The debug render method is game-thread only and gated behind a
debug option, and once no batch task touches the statics nothing else writes them, so it keeps them and stays
byte-identical to the jar. The vehicle-adjacent path setup has no caller anywhere in the jar and is reached only
from Lua, which also runs on the game thread, for the same reason. The pooled debug-point allocator in the update
is only reached with the debug flag on and only for a player, and players never run on a worker.

The copy of the list is gone, so the index and the nodes come from one list again, as they do in stock. The catch
stays, but it no longer swallows anything: a batch task's throw is counted under its own name on the batch status
line, the first one logs its whole stack with a message saying plainly that the analysis above is wrong if this
ever fires, and the exception is rethrown on every thread. That makes behaviour identical to vanilla on and off a
batch, which matters because the one thing that can still legitimately reach the catch is vanilla's own shape: an
empty path with the finder reporting a result found indexes past the end in stock too, and must keep throwing
there. The counter's meaning is therefore inverted. It used to count damage absorbed; it now counts the analysis
failing, and a route log reading zero is the evidence that the race is closed.

`tests/pzopt/PathfindRaceGuardTest` replaces its own earlier contents. Four groups of pins. The jar pins record
the facts the fix rests on so a game update that moves any of them fails the build: that the success callback is
invoked from exactly the two main-thread update methods and from nowhere else, that neither pathfind thread's loop
invokes it, that the world update runs before the managers step inside the game state's frame and that the cell's
object pass is what drives the entity scheduler, that the path field is private, and that the four scratch fields
are still static and still read by each of the five reachable methods. The override pins say those five methods
read none of the four and route through the per-thread holders, that the render method still uses the statics on
purpose, and that no list copy is left. The holders themselves are checked for per-thread identity and stability.
The derived-index case is the runtime evidence and is one-sided by construction: four threads walk four paths of
different lengths and read the index straight back out, which is the two statements the update runs back to back,
and through a per-thread holder a thread can only ever read the index it just wrote, so the assertion cannot fail
for a timing reason. Beside it the identical hammer runs through one shared record, and that control is asserted
to see violations, so the invariant cannot pass vacuously; it finds tens of thousands of them in a few seconds.
The catch case drives the real update on real characters through a real batch and asserts the throw now escapes on
every thread, worker and game-thread participant alike, counted exactly once each. What is not exercised at
runtime is the end-to-end update on a multi-node path, because the closest-point walk needs a real cell to ask for
grid squares and a bare JVM has none; the derived-index hammer covers the exact statements the defect lived in
instead, and the bytecode pins cover the rest of the method. No performance number is claimed. The change removes
a per-entity list copy and adds one thread-local read per scratch object per call, and the reason to make it is
that the alternative leaves an unknown number of zombies a frame walking somebody else's path with no signal at
all.

### Foliage sway (`foliageSway`, 2026-09-27; `pzopt.Sway`, `docs/findings-foliage-sway-2026-09-27.md`)

Nothing below changes a pixel or a timing while `foliageSway` is off (the default): the hooks return at once, the tile
programs' extra outputs go to no draw buffer, the game's chunk composite is never patched.

- `zombie.core.opengl.ShaderUnit` (compile): the source passes through `pzopt.Sway.patchShader` last and
  `pzopt.Sway.recordSource` records the chunk composites' final sources with their program. The bake's tile programs
  (tileWithDepth, opaqueWithDepth, seamFix2, CutawayAttached) get a second output (the plant attribute: weight, class, phase,
  four depth bits) and write their depth through `pzSwDepth`, which with sway on sets the lowest DEPTH16 bit on plant texels
  and clears it on everything else (one depth step, 1.5e-5); with the uniform at its default (0) it returns the depth
  unchanged. `chunkShader.vert` / `.frag` are recorded for the sway variant (`pzopt_swChunk`, placeholder files under
  `src/media/shaders/`), never changed. Test-compiled; the source stays stock on failure.
- `zombie.core.opengl.ShaderUniformSetter`: `pzoptNext()`, the chain's next link (the sway uniform is appended to a sprite's
  chain).
- `zombie.core.textures.TextureDraw`: both `StartShader` builders let `pzopt.Sway.startShader` append the plant's sway
  uniforms to a patched tile program's chain while a plant bakes (a rigid draw resets them once after a plant used the
  program) and tag the draw (`c`); `run()`: `StartShader` asks `pzopt.Sway.remap` for the program (the game's chunk composite
  becomes the sway variant for a chunk texture that holds plants: its DEPTH is already on the draw) and tells
  `pzopt.Sway.onProgram` which program is bound (both draw buffers while a patched tile program bakes into a texture with a
  plant attribute texture; the DLSS motion attachment's colour mask during the composite); model / generic / water / particle /
  terrain / ImGui ops first set one draw buffer again (`beforeOp`: they bind programs of their own, whose `gl_FragColor`
  broadcast into the attribute); `FBORenderChunkStart` / `FBORenderChunkEnd` open and close the texture's attributes
  (`chunkStart` clears them with a clearing bake, `chunkEnd` uploads the bake's tile mask carried on the end draw, frees the
  attributes of a bake that drew no plant and sets one draw buffer while the chunk's FBO is still bound); the
  `FBORenderChunkEnd` builder puts the bake's tile marks (`takeMask`) on the draw.
- `zombie.core.textures.TextureFBO.destroy`: `pzopt.Sway.fboDestroyed` frees the attribute texture attached to that FBO.
- `zombie.viewCone.ChunkRenderShader.startRenderThread`: `pzopt.Sway.chunkDraw(this, texd)`: the wind uniforms (once per
  program per frame) and the texture's attribute / mask textures and mapping (per draw); for pixelLight's or the sprite
  filter's composite, its sway twin is bound for a texture that holds plants.
- `zombie.iso.fboRenderChunk.FBORenderCell`: `renderMinusFloor(IsoObject)` brackets `object.render` with `Sway.begin` /
  `Sway.end` (a baked plant: `moveWithWind` or `isBush` sprites); `pzoptAddTreeTexture` adds each baked tree quad's sway
  (its rows as fractions of the tree's height, amplitude, phase; one more parameter, the tree's square); the frame calls
  `Sway.beforeComposite` (the wind) and `Sway.afterComposite` (the motion attachment's draw buffers) around the composite and
  names the `composite` / `chunks` GPU sections by `devSwayAlternate`'s half.
  Since 2026-10-05 (`swayFloorExact`, default on): `renderFloor(IsoObject)`'s stock body moved into `pzoptRenderFloor`; the
  new method wraps it in `Sway.floorBegin` / `floorEnd`, and while a floor draws the patched bake programs get sway "off"
  (`pzSwObj.w` 0: the depth written exactly as stock writes it, a zero-weight attribute) instead of "rigid" (`w` 2: the depth
  rounded to DEPTH16 with its lowest bit cleared, the composite's free "this texel sways" flag). The rigid flag moved half a
  floor's texels one DEPTH16 step nearer; on corrugated flat roofs (`roofs_04_*`, drawn as the floor, depth right at the
  puddle's 1e-4 lift) the puddle lost to the ridges in dashes that changed with the camera (Discord "white roofs flicker in the
  rain"). An odd floor texel now reads as "sways" to the composite and finds weight 0 (lands on itself): spin-uncapped
  composite 151-173 us either way, 118-119 fps. With `puddleJiggleDepth` (above): roof dashes 1,741 -> 29 px a frame in
  clear weather (stock 33), 2,803 -> 33 in rain (stock 49); Jev fixed 0.92 / 0.71. Video
  `docs/media/roof-puddle-flicker-before-vs-fix.mp4` (`harness/stitch-roof-puddles.sh`).
- `zombie.iso.fboRenderChunk.FBORenderTrees.addTree`: a tree the game draws per frame (faded near the player) without an
  effect of its own gets the wind at its top as stock's corner offsets (`Sway.shearTop`, the composite's wind on the CPU).
- `pzopt.TreeBake` (ours): the drawer carries each quad's sway and, while sway is on, draws the baked trees with
  `Sway.drawTrees` (the same colour and depth as the VBO path, plus the attribute and the depth flag).
- `pzopt.PixelLight.chunkDraw` (ours): a sway twin of its chosen variant counts as that variant (no switch back).
- `pzopt.Dlss` (ours): after the object motion, one additive pass adds the sway's per-pixel motion (`Sway.motionTexture`).

## The darkness floor is only seen, never played (`darknessFloorPct`, 2026-09-28; LightingJNI + IsoWorld)

With the darkness floor on, the Louisville player died seconds into the route (5 of 9 runs with the floor, 0 of 28
without; runs `bis-dark-*`, `fix-all*`). `pzoptDarkApply` floored the square's flat light (`lightInfo`) in place, and that
value is what gameplay reads: zombie sight (`IsoZombie.spottedNew` / `spottedOld` / `updateVisionRadius`),
`IsoGameCharacter.TestIfSeen`, `IsoPlayer.checkCanSeeClient` / `tooDarkToRead` and the low-light to-hit penalty
(`CombatManager.getWeatherPenalty` through `IsoGridSquare.getLightLevel`). So zombies saw the player in rooms that should
be dark. The corner lights and the fade multiplier are only read by the renderer, so they stay floored.

- `zombie.iso.LightingJNI.JNILighting`: `pzoptNative` keeps the native's flat light as read (set before the floor, fresh
  reads and the first settings change), `pzoptFloored` says the floor changed it (cleared when the features go off);
  `lightInfo()` still runs the lazy refresh, then hands `pzoptNative` to every caller but `pzopt.Darkness.drawThread`.
- `zombie.iso.IsoWorld.render`: `Darkness.drawThread` is the game thread for the world pass (`renderInternal`: the cell
  render, the chunk bakes, `cacheLightInfo`, which keeps the floored object by reference for the objects' colours) and
  null otherwise, also on a throw. Worker threads (the characters draw pre-pass, the zombie batches) always get the native
  value; the character models are lit from the corner lights (`getVertLight`), which keep the floor.

## Port to Build 42.21 (2026-09-28, revision `4a0e9546ec`)

42.21 went to the stable branch; the jar is byte-identical to the 42.21 unstable decompiled on 2026-09-25
(`/games/pz-42.21/`), so `decompiled/` is that CFR tree. Method: Vineflower of the 100 overridden classes from the
42.20.4 jar (`pzsrv-stock/`, the base) and from 42.21, then `git merge-file` of our sources onto the 42.21 output.
Of the 113 classes, 68 are unchanged between the two jars and kept as they were; 40 changed. The 13 classes of the
game-thread offload pass (PR #35) are CFR output, not Vineflower, so their merge base and target are CFR of the two jars.
The bytecode audit then compares every unedited method against the 42.21 jar (6,217 methods, 0 mismatches after the
one decompiler fix below); the edited methods 42.21 touched were checked line by line against the 42.21 changes.

- **zombie.popman.ZombiePopulationManager**: 42.21 rewrote cell saving. `requestSaveCell` only queues the cell key
  and `processPendingSaveCells` (MapCollisionData thread) saves due cells throttled per cell through the native,
  under `saveLock`; there is no zombie snapshot on the game thread any more. Our `saveCellAsync` edit (snapshot
  without the lock, one pending write per cell) is gone: the class is 42.21's with the load marker, and the key has
  no effect. Decompiler fix again in `removeChunkFromWorld`: Vineflower renders the stationary branch first as an
  if / else; the jar tests the moving case first with a `continue` inside the `try` (two `finally` copies), so the
  moving branch comes first and both end in `continue` (`// pzopt: decompiler fix`).
- **zombie.iso.WorldStreamer** `DoChunkAlways`: 42.21's `IsoChunk.LoadChunk` returns whether the chunk loaded and the
  stock method does nothing more when it did not; our timing / recalc-pool body now sits inside that guard.
- **zombie.iso.fboRenderChunk.FBORenderCell**: 42.21 counts a player in a vehicle as aiming in `isTranslucentTree`
  (trees in the cutaway stencil turn see-through while driving); the two places that hoist the aim flag for
  `pzoptIsTranslucentTree` (the tree-translucency pass, `pzoptBakeTrees`) do the same. 42.21's `IsoTree.render` also
  fades XXL trees while driving, inside or near rooms; a tree baked into the chunk textures never reaches
  `IsoTree.render`, so with `treesInChunkTexture` an XXL tree those rules fade is translucent (per-frame, faded by
  IsoTree) in `pzoptIsTranslucentTree` (`pzopt.XxlTreeFade`, 42.21's arithmetic). While driving only XXL trees within
  12 squares are faded (42.21: all of them): fading every XXL tree moved them all to per-frame drawing, 120 km/h drive
  mean 4.4 -> 10.2 ms, p99 11.4 -> 29 ms (2026-09-25 backport runs `bp4221-trees-drive` / `-drive12`).
  `--prop devXxlTreeLog=true` logs how many checks came back see-through every 10 s.
- **zombie.gameStates.GameLoadingState** `render`: 42.21 removed `mapDownloadFailed`; the resume-shot condition drops it.
- **zombie.core.textures.ImageData**: 42.21 loads jpg / png files through `NativeImage`; the conflict was our cosmetic
  `Format` import, 42.21's code kept. `pzopt.GifTextures` / `pzopt.ResumeShot` used `ImageUtils.getNextPowerOfTwoHW`
  (removed): `max(2, PZMath.smallestEncompassingPowerOfTwo(n))`, the same values.
- **zombie.GameWindow** `initShared`: 42.21 dropped `CustomizationManager.load()`; our item-dump hook stays.
- **zombie.iso.IsoWorld** tile-definition loading: 42.21's log category (`DebugType.General`) under our preload guard.
- IsoChunk, FBORenderCell imports and IsoLightSwitch's new constant: both sides kept.
- **zombie.characters.IsoGameCharacter** `faceThisObject`: 42.21 hands a character target to `faceThisObjectAlt` and skips
  objects with no index; `entityUpdateParallel`'s per-thread scratch vector stays.
- **zombie.characters.animals.IsoAnimal** `updateInternal`: 42.21 moved the stress / lure / LOS block into a new branch;
  the `devGtAlternate` timer around `updateLOS` moved with it.
- **zombie.pathfind.PathFindBehavior2**: 42.21 split `update()` into `update()` -> `update(float speedMul)`; the per-thread
  scratch locals and the assertion `try` of `entityUpdateParallel` open `update(float)` now.
- **zombie.Lua.LuaEventManager**: 42.21's two new events (`LogLevelPerk`, `OnTileObjectAdded`) added by hand (our copy is
  formatted differently from a fresh decompile, so a text merge could not place them).
- `pzopt.ActionEval.PURE_CALLBACKS`: 42.21's new `nearWallCrouching` variable (a field getter); `hitDir` is an enum
  getter now, still a field read.


## The game's wind option handed to foliage sway (`windSpriteSway`, 2026-09-29, issue #41)

The stock "Wind sprite effects" display option (`doWindSpriteEffects`, off by default) takes every tree and every
wind-moved sprite out of the chunk textures and draws each one per frame with its top corners sheared. With our tree bake
that undid one of the biggest wins: in a forest at max zoom on the flip, 244 (cap) -> 154 fps with the option (stock:
151 -> 106; runs `w41z-*`). The per-frame path itself was not dearer than stock (3.6 vs 5.1 ms of translucent work for the
same objects). With the handoff, uncapped on the flip: wind off 372 fps, wind on 369 (the stock per-frame path: 133;
runs `w41h-*`).

### zombie.iso.fboRenderChunk.FBORenderCell

- `renderInternal` is a wrapper now: `pzopt.Sway.windHandoffBegin()` (with `windSpriteSway` and the option on, the option
  reads off: `Core.setOptionDoWindSpriteEffects(false)`), then stock's body (moved unchanged into `pzoptRenderInternal`),
  and in a `finally` `windHandoffEnd` sets the player's value back. Everything that reads the option inside the world
  render takes its option-off path: the render layer (`isObjectRenderLayer_*`), `checkTreeTranslucency`, the tree pass,
  `IsoObject.getObjectRenderEffectsToApply` (no wind shear on a baked plant), `ObjectRenderEffects` on a rustling plant,
  `FBORenderObjectHighlight.isRenderedEveryFrame`. The options screen, `Core.saveOptions` (never called during the
  render), Lua and the wind effects' own update see the player's value. `pzopt.Sway.wanted()` is `foliageSway || handoff`,
  so sway moves the plants that stay baked. Sway is not supported on macOS (GL 2.1): there the option keeps the stock
  per-frame path.
- Dead end, not kept: a per-frame identity cache for `ShaderUniformSetter`'s uniform-by-name lookups (four per sprite in
  `IsoSprite.startTileDepthShader`, ~4.5 % of the game thread in the sampler with the stock per-frame path). Same run,
  cache on / off: 132.6 / 133.0 fps; the samples moved to `ShaderUniformSetter.uniform1f` (safepoint bias). What remains of
  the per-frame path is a draw per sprite and `IsoTree.countObscuredSeenSquaresOriginal` asking the lighting native per
  square (~7 %), both in classes we do not override.

## Baked trees of one iso row never tie in depth (`treeBakePass`, 2026-09-29)

Found during issue #41: at the edge of the out-of-sight area a yellow tree's crown showed horizontal black stripes (flip,
deep forest `start=9300,12900`, max zoom; runs `stripe-*`). Stock and `treesInChunkTexture=false` draw it whole;
`treeBakePass=false` hides most of it. The tree pass's depth only knows x + y and the height, so a tree beside it on
the same iso row (out of sight, baked black, invisible on the black ground) had the identical depth ramp, and the
DEPTH16 rounding picked the winner row by row under GL_LEQUAL. Stock staggers its per-frame billboards
(`DepthStagger`, per frame, by draw order); a baked crown copied into several textures needs the same answer in each.

### zombie.iso.fboRenderChunk.FBORenderCell

- `pzoptBakeTrees` and the `treeAppend` path add `pzopt.TreeBake.rowStagger(x, y)` to the tree's base depth after the
  "below this texture's range" check: `(3 - floorMod((x - y) >> 1, 7)) * 1e-4`, distinct for trees up to three
  columns apart, at most 3e-4 (a fifth of a row's 1.44e-3). Of two trees on one row the one further right is in front
  (stock showed the same at that spot; the choice is arbitrary for equally far trees). The issue #5 spot
  (`start=11023,6720`) is unchanged against `treesInChunkTexture=false`.

## The shelf blink: where pixelLight takes a texel's light from (`pplSeenEdge`, `pplJiggle`, `pplDepthOpaqueOnly`, 2026-09-29; PixelLight + a dev hook in FBORenderCell)

The maintainer's save (Riverside Fossoil, night, pixelLight on): the goods on the shelves behind the store counter blinked
black and shimmered while the player walked in circles. No override edit; all in `pzopt.PixelLight` (details and numbers:
`docs/findings-shelf-blink-2026-09-29.md`):

- `pplSeenEdge`: a lit point within 0.03 past a square's west / north edge, across a wall, in a never-seen square takes the
  seen square before the edge (the goods' depth box put their face on the square edge, the plane of the unlit garage's wall);
  the pack puts the square's "seen" bit in the connectivity texture's alpha next to "outdoors".
- `pplJiggle`: `Gl.mapping()` includes the camera jiggle fix the game applies to every chunk texture (screen and depth).
- `pplDepthOpaqueOnly`: `patchShader` makes `tileWithDepth.frag` write no depth where the sprite is fully transparent.
- Counter crop 400.7 -> 3.0 one-frame flip px/frame (pixelLight off: 1.1); Jev walk through the store 825 -> 7 blink
  frames, 0 inside the store.
- `zombie.iso.fboRenderChunk.FBORenderCell`: two dev hooks for `devSquareTrace` (`pzopt.SquareTrace`): `noteBake(c)` beside
  `PixelLight.bakeBegin` and `frame(playerIndex)` after `FBORenderItems.update()`; both return at once with the key empty.

## Floor blood without its bake cost; wet blood (2026-09-29; IsoChunk, FBORenderCell; `pzopt.BloodDecals`, `pzopt.BloodWet`)

Findings and numbers: `docs/findings-blood-decals-2026-09-29.md`.

- **zombie.iso.IsoChunk**: two fields, `pzoptBlood` (the chunk's `BloodDecals.Cache`) and `pzoptBloodVersion` (bumped in
  `addBloodSplat` after the splat joins the queue and in the load loop that fills the queue from the save).
  `addBloodSplat`'s `invalidateRenderChunkLevel(sq.z, 1L)` first asks `BloodDecals.added(this, b)` (`bloodAppend`): true =
  the splat is queued to be drawn into the finished textures this frame, or the density option hides it (nothing to
  re-bake); false = stock's re-bake (append off, grass / a body / an item under the splat, a level outside -32..31). The
  reset that clears the queue calls `BloodDecals.reset(this)` (the cache's atlas row back to the pool, its appends and
  settle entry dropped).
- **zombie.iso.fboRenderChunk.FBORenderCell**: `renderOneLevel_Blood(chunk, zza, minX, minY, maxX, maxY)`, right after
  stock's option checks, hands the call to `BloodDecals.bake` (`bloodBake` gpu / cpu) and returns when it drew; the new
  `pzoptBloodRules` field is the per-square half of the stock loop (cutaway flag, blacked-out building, the average of the
  four vertex lights, computed with the same float operations) that the cache's draws take per square instead of per
  splat. After `pzoptFlushTreeAppends` (before the composite) `BloodDecals.flush` draws the frame's appends into their
  textures inside a bake's begin / end, as treeAppend does. Wet blood: `BloodWet.collect` before `Ssr.beforeComposite`
  (the wet level-0 squares join the reflection map), `BloodWet.queueMain` after the puddles (GPU section `bloodWet`),
  `BloodWet.queueGlint` in the HDR glint-only pass after the puddles'.
- **FBORenderCell** (bloodAppendPlants, same day): `pzoptRedrawPlants`, handed to `BloodDecals.flush` as a `PlantRedraw`,
  draws the plants (isBush / canBeRemoved / attachedFloor MinusFloor objects, no trees) of the given squares of a chunk
  level through `renderMinusFloor` with the chunk manager pointed at the texture (renderChunk, caching, xoff / yoff as
  beginRenderChunkLevel sets them), the squares' light cached, pixelLight's bake whitening, depth test LEQUAL; BloodDecals
  binds a scratch layer around it, so the plants land there, not in the texture.
- `pzopt.Ssr.beforeComposite` (not an override): a chunk's wet-blood squares are OR-ed into its puddle bits and its slot
  fingerprint, and `f.puddles` is set while any are on screen, so the composite and the characters scatter into them.
- Picture: `bloodBake=gpu` and `cpu` measured pixel-identical to stock (runs `bp-stock` / `bp-cpu` / `bp2-gpu`: 14-99
  pixels > 8 of 11 M, all animation). `bloodAppend` draws the same splats in the same place; it differs from stock only
  where stock would never have drawn them (neighbour textures, which stock left cut at the chunk edge until another
  re-bake) and in draw order under things the depth test can tell from the floor (a wall's foot: 2 % of a square's depth);
  vegetation, bodies and items under a splat fall back to the re-bake.

## Blood decal probe (2026-09-29; FBORenderCell, dev rig only)

`pzopt.BloodProbe` (harness flags `blood_fill` / `blood_rate` / `blood_probe`, off otherwise) times three places of
**zombie.iso.fboRenderChunk.FBORenderCell**: `renderOneChunk` takes `nanoTime` around each `renderOneLevel`,
`renderOneLevel` hands the level's dirty flags to the probe where the bake census reads them, and the nine
`renderOneLevel_Blood` calls of a bake are timed as one section. Nothing is drawn differently; with the rig off each hook
is one static-final check. Findings: `docs/findings-blood-decals-2026-09-29.md`.

## Fitted depth for the floating wall cabinets (`tileDepthFix`, 2026-09-29, issue #38)

The game's tile depth assignments give 88 "Floating" wall cabinets (71 of `fixtures_counters_01`, 8 of the Sunstar motel's
`location_hospitality_sunstarmotel_02`, 8 of `location_trailer_02`) the depth texture of `fixtures_counters_01_16`: a box
over the whole square from 1.8 to the ceiling. Stock only sorts with it. Per-pixel lighting, ambient occlusion, sun shadows
and reflections read the chunk textures' depth as the surface, so the cabinets' doors lay half on the box's top and half on
its front a full square out from the wall (a light seam through every door, AO darkening round a box that is not there).
`pzopt.TileDepthFix` keeps one texture per distinct fitted shape (17 for the 88 tiles): the boxes
`harness/tiledepth/fit-boxes.py` fitted to each sprite's opaque pixels (against the wall behind the doors, ~0.55 squares
deep, corner pieces as an L of two), drawn with the game's own box depth, on exactly the texels the stock box covers (the
stock depth where the boxes miss, ≤ 3 % of a sprite's opaque pixels), so nothing is drawn or discarded differently. No texel
is nearer than a plane 0.04 units under the ceiling (the stock upload clamps to just under it): pixel light takes a texel
within 0.006 levels under a level for that level and then the brighter of the two, so tops on the ceiling plane, and the top
rows of the fronts, took the light of the roof or the room upstairs (a 0.1 drop smudged the tops with AO: the wall behind
rose above them). With `tileDepthFix=auto` (default) the fitted textures are swapped into the sprites only while one of those
four features is on; `on` / `off` force it. The 17 textures build in ~130 ms, once, at the load or at the live switch (there
the swap waits for the render thread's upload: a re-bake before it read a blank depth texture and the cabinets vanished from
those chunk textures). Rig: `--flag find=cabinets` (the player in front of the nearest cabinet), the Rosewood medical room
`--flag start=8086,11526` (a row of cabinets with corner pieces), runs `cab-*` 2026-09-29; `tests/pzopt/TileDepthFixTest`.

`tileDepthCanopies` (default on, same auto rule): 32 straight store canopies, which borrow flat boxes up to the ceiling and are
Translucent (the stock shader writes the box's depth on their transparent texels: the air under the canopy), get sloped slabs
fitted by `harness/tiledepth/fit-canopies.py`, and every texture of a tile that is not OpaquePixelsOnly leaves out the texels
its sprite's alpha mask leaves empty. `tileDepthCeiling` (measured, off): the general alternative, below. Comparison in
`docs/findings-cabinet-depth-2026-09-29.md`.

### zombie.tileDepth.TileDepthTexture

- `updateGPUTexture` (`tileDepthCeiling`, off by default): with the key and a depth-reading feature on at boot, every non-roof
  texture's texels in the rows the ceiling crosses are clamped under a plane 0.04 units below it (stock's own clamp, deeper).
  Measured: no visible change on the canopies; kept off.
- New method `pzoptFilled`: clears the empty flag of a texture filled in memory, which stock only clears when a PNG loads or
  when the render thread uploads, so a fitted texture is used from the frame it is swapped in.

### zombie.tileDepth.TileDepthTextures

- `LoadTask.done`: after the manager's finish (whose last call runs both stock assignment walks) `TileDepthFix.reapply()`
  puts the fitted textures back on the sprites those walks reset. `pzopt.SpriteWindow`'s re-walk does the same.

### zombie.iso.IsoWorld

- `loadedTileDefinitions`: `TileDepthFix.reapply()` after the depth manager's own walk over the new sprites and the geometry
  manager's sprite properties (the OpaquePixelsOnly flag decides whether a tile's texture takes the sprite mask).

## The UI snappiness pass (2026-09-30; UIManager, UIElement, GameWindow, GameKeyboard, Lua Event, KahluaTableImpl, LuaCompiler, PerformanceSettings, WorldMapStreet, WorldMapStreets, StreetRenderData)

Keys `uiProfile` (on in instrumented runs), `uiRetained`, `uiRetainedChildren`, `uiHoverHz`, `uiRetainedMaxMs`,
`uiRetainedCheapUs`, `uiRetainedChildCheapUs`, `uiRetainedStreak`, `uiRetainedStaticMs`, `uiRetainedSettleMs`,
`uiTickStagger`, `luaIndexCache`, `luaInternConstants`, `luaSkipEmpty`, `mapStreetMemo`, `mapStreetCache`, dev keys
`devUiDiff`, `devUiRetainedCheck`, `devMapStreetCheck`. Findings: `docs/findings-ui-snappy-2026-09-30.md`.

### zombie.ui.UIManager (new override)

- `render`: `pzopt.UiProfile.renderBegin` after the stencil level is reset and `renderEnd` at the end of a UI frame.
  In the element loop's non-profiler branch, each top-level element is first offered to `pzopt.UiRetained.replay`
  (which copies its recorded draw commands into the UI render state when nothing can have changed it); otherwise it is
  rendered as stock between `UiRetained.freshBegin` / `freshEnd` (which records its commands) and
  `UiProfile.elementBegin` / `elementEnd`; a replay is reported to `UiProfile.elementReplayed`. After the loop
  `UiRetained.loopEnd` (every cached GL state marked dirty) before the tooltip and the paused text.
- `update`: `UiProfile.updateMark(0..6)` at the boundaries of its sections (list upkeep, mouse buttons, click and
  wheel, mouse move, world pick and OnMouseMove, element updates, tooltip).
- `updateUIElements`: with `uiTickStagger`, each top-level element's update runs with `doTick` and
  `uiUpdateIntervalMS` set from its own 100 ms tick (`pzopt.UiTicks`), both restored after the loop; each element's
  update time goes to `UiProfile.elementUpdated`.

### zombie.ui.UIElement (new override)

- `render`: a child element (one with a parent), after the stock visibility and clipping tests, is offered to
  `UiRetained.replayChild` and returns when its recorded commands were copied in; otherwise its render is recorded
  (`childBegin` before its prerender, `freshEnd` after the debug outline). The prerender and render Lua calls are
  skipped when the function is an empty Lua function (`luaSkipEmpty`, `pzopt.LuaFast.skipEmpty`) and timed by
  element type (`UiProfile.callBegin` / `callEnd`).
- `update`: the Lua update call the same (empty skip, timing); `onMouseMoveOutside`: the Lua call skipped for an
  empty function.
- Decompiler fixes (the audit's mismatches): `onConsumeMouseWheel` returns `onMouseWheel`'s Boolean as is,
  `getUIName` concatenates through `String.valueOf`, `isKeyConsumed` unboxes per branch, as in the jar.

### zombie.GameWindow

- `UiProfile.updateBegin` before and `updateEnd` after `UIManager.update`; `UiRetained.decideFrame` just before the
  render phase (after the whole update, so input and Lua ticks of the frame count): it sets
  `Core.uiRenderAccumulator` so the UI renders this frame (the stock rate, or at once after input / a change) or keeps
  the previous UI texture.

### zombie.input.GameKeyboard

- `update`: a key whose state changed bumps `UiRetained.keyEvents` (every UI element renders fresh).

### zombie.Lua.Event

- A flag set in the constructor for the events after which UI elements show something else (OnContainerUpdate,
  OnClothingUpdated, OnEquipPrimary / Secondary, AddXP, LevelPerk, OnPlayerGetDamage,
  OnRefreshInventoryWindowContainers); `trigger` of such an event calls `UiRetained.uiEvent`.

### se.krka.kahlua.j2se.KahluaTableImpl

- `luaIndexCache`: a table used as some table's metatable is marked; `rawget` on a miss of the table's own map asks
  its metatable's `pzoptChainGet`, which returns the stock recursive `rawget` result cached per key until a static
  version changes; the version is bumped by `rawset` / `wipe` on a marked table, `setMetatable` and `setRewriteTable`.
- `pzoptWrites`: bumped when `rawset` changes a value (the previous value returned by the map's put / remove differs)
  and on `wipe`; `UiRetained` renders an element fresh when its table's count changed since it was recorded.

### se.krka.kahlua.luaj.compiler.LuaCompiler

- `luaInternConstants`: the prototype of a freshly compiled chunk and of a precompile-cache hit goes through
  `pzopt.LuaFast.intern` (string constants interned, recursively).

### zombie.core.PerformanceSettings

- Harness UI rig methods for Lua: `pzoptMicros`, `pzoptUiMark`, `pzoptUiInput`, `pzoptUiMouse`, `pzoptUiMouseOff`.

### zombie.worldMap.streets.WorldMapStreet (new override)

- `mapStreetMemo`: `getLength(ui)` keeps the UI-space length per (view stamp of `pzopt.MapStreets`, map UI, points
  object, point count, edit count); `getTranslatedText` keeps the translation per (untranslated text, language). An
  edit counter is bumped by `init` and every point edit method. Decompiler fix: `countUpsideDownCharacters`
  multiplies by the jar's double constant (the float 180/PI widened) instead of dividing by a float PI.

### zombie.worldMap.streets.WorldMapStreets (new override)

- `mapStreetMemo`: the visible streets are sorted by `pzopt.MapStreets.sortByIndex` (each street's first index in the
  street list from one pass; the same comparator values and stable sort as stock's indexOf comparator).

### zombie.worldMap.streets.StreetRenderData (new override)

- `mapStreetCache`: in `init`, the non-editor branch reuses the last street-label layout of this map UI (the
  characters list copied from a kept copy into pooled CharLayouts) when the view stamp, the combined streets object,
  `MapStreets.labelKey` (every renderer option's value, the map style's layers, the language) are unchanged and no
  street edit or dirty rebuild happened this frame; otherwise the stock layout runs and a copy is kept.
  `devMapStreetCheck` lays out anyway on a reuse and logs a difference.

### zombie.iso.IsoMetaGrid, zombie.iso.ChunkSaveWorker, zombie.gameStates.GameLoadingState (the transfer hitch)

- `devHotsaveTiming` (dev, off): `IsoMetaGrid.save()` and its per-file `save(String, Consumer)` log serialise / write time
  per file; `saveToBufferMap` logs its four parts; `ChunkSaveWorker.HotsaveAncilliarySystems`'s game-thread lambda logs
  each system's time.
- `hotsaveWarmup`: `GameLoadingState`'s loader calls `pzopt.HotsaveWarmup.run()` right after `IsoWorld.instance.init()`
  (map_meta's grid part, zones and animal zones serialised three times into a private buffer, so the first hot save of
  the session runs compiled code).

### zombie.worldMap.WorldMapVisited (new override)

- `mapVisitedFast`: `updateTextureData` builds each changed texture row in a byte array (the packed flags read with the
  row span computed once) and puts it in one call; the same bytes as the stock per-texel loop, which stays for the off
  case. `devMapVisitedCheck` re-runs the stock computation over the region and logs differing texels.
- Decompiler fix: `setBounds` zeroes the four bounds with one chained assignment, as the jar does.
### Relief / parallax textures (`relief`, 2026-09-30; `pzopt.Relief`, `pzopt.ReliefAux`, `docs/findings-relief-2026-09-30.md`)

Nothing below changes a pixel or a timing while `relief` is off (the default): the hooks return at once and no shader is
patched.

- `zombie.iso.fboRenderChunk.FBORenderCell.renderOneLevel` (the bake's end, top level, before the `bake.end` section and
  `endRenderChunkLevel`): `pzopt.ReliefAux.baked(renderChunk)` queues the texture's relief code and, with the baked sun relief,
  its relight (the factor multiplied into the colour before the stock mipmap build).
- `FBORenderCell` before the composite (after `ChunkAo.flush`): `pzopt.ReliefAux.flush(playerIndex)`: the light steps of the
  baked sun relief for the textures on screen (a few a frame), re-bake requests for textures shown without relief, the
  composite-mode encodes; the composite's GPU section name goes through `pzopt.Relief.section` (`devReliefAlternate`).
- `zombie.viewCone.ChunkRenderShader.startRenderThread`: `pzopt.Relief.chunkDraw(texd)` after the cloud shadows: this texture's
  relief code on unit 13 (keyed by `texd.tex1`, the depth: the op is the StartShader, its `tex` is not the chunk's colour) and,
  in composite mode, the key light.
- `zombie.core.opengl.ShaderUnit` (compile): `pzopt.Relief.patchComposite` right after the cloud shadows' patch
  (`reliefSunMode=composite` only: a post-main relighting the direct-sun share per fragment, +86 us at 5K, not the default).
  pixelLight's chunk programs get the relief code read in their texel normal through `PixelLight`'s own sources (defines from
  `Relief.defines()`), not through a patch.
- `pzopt.CloudShadow.chunkDraw`: the per-texture direct-sun share uniforms are also set while no cloud shades
  (`Relief.wantsSunShare`, composite mode only; clear sky left them at 0).

## Mod compatibility (2026-10-01; LuaCaller, PerformanceSettings, `pzopt.ModCompat`, `pzopt.LuaOrigin`, `pzopt.LuaGate`)

Keys `modCompat` (auto / report / off, default auto), `uiRetainedMods` (default off), `luaWorkerGate` (default on).
Findings and the test matrix: `docs/findings-mod-compat-2026-10-01.md`.

### se.krka.kahlua.integration.LuaCaller (new override)

- Every public call entry (`pcall`, `pcallvoid`, `pcallBoolean`, `protectedCall*`): on a frame worker
  (`pzopt.LuaGate.onWorker`, with `luaWorkerGate` on) the call is handed to `pzopt.LuaGate` instead of pushing onto the
  shared Kahlua stack. The arguments the call takes are copied into locals the hand-off can capture. A void call made
  inside the entity update batch joins that task's Lua replay (`UpdateBatch.captureLuaCall`, run on the game thread
  after the join in stock's order); any other call waits on the worker while the game thread runs it at its next
  FrameBatch task boundary or in a join wait, and its result or exception is returned to the worker. Off a worker
  nothing changes. Before: a zombie trampling a crop on a worker ran the farming system's Lua there ("Lua code called
  from the wrong thread", then a corrupted stack).

### zombie.core.PerformanceSettings

- Methods for Lua: `getPzoptLuaOrigin` (where a Lua function was loaded from: the game's file, ours, a mod's;
  `pzopt.LuaOrigin`), `getPzoptModCompatReason` and `getPzoptModCompatSummary` (`pzopt.ModCompat`, shown on the
  Optimizations tab), `getPzoptModCompatDetails` (2026-10-02: the launch's scan per jar file for the main menu's
  "PZ OPTIMIZATION MOD COMPATIBILITY CHECK" dialog, `pzopt_mainscreen_compat.lua`).

### pzopt classes and Lua (no game class changed for these)

- `pzopt.ModCompat` runs at `Config`'s class init: it reads the `-javaagent` jars of the JVM's arguments and every jar
  of the mods `Zomboid/mods/default.txt` enables (Zomboid/mods, the game's mods folder, the Workshop downloads), finds
  ZombieBuddy `@Patch(className, methodName)` annotations and, in classes that rewrite bytecode, string constants naming
  a class we ship and its methods. A hit on a method we edited switches off the boolean keys that method reads
  (`scripts/override-methods.py` writes the map at build time, `pzopt/override-methods.properties`), below the
  player's options.ini, unless the mod is listed as tested (`ModCompat.KNOWN`, `Zomboid/pzopt/mod-compat.ini`) or
  `modCompat=report`. Report in console.txt (`mod compat:` lines) and `Zomboid/pzopt/mod-compat.txt`.
- `pzopt_ui_fast.lua` replaces `ISInventoryPage.update` / `setVisible` / `ISInventoryPane.renderdetails` only when the
  function is still the one it saw and comes from the game's own file: a mod that replaces the vanilla file at the
  same path loads in the vanilla slot, before our file, and was taken for vanilla.
- `pzopt.UiRetained`: an element that carries a mod's Lua function (its own table or a class up its `__index` chain)
  renders fresh at the stock rate, and so does an element whose last fresh render drew such an element
  (`uiRetainedMods=false`).
### macOS OpenGL 4.1 core profile (`macGlCore`, 2026-10-01; `pzopt.CoreGl`, `pzopt.CoreGlsl`, `docs/findings-mac-gl41-2026-10-01.md`)

Nothing below changes anything off macOS or with `macGlCore=false`: `CoreGl.windowHints` and `capabilities` return at once,
`CoreGl.active` stays false.

- `org.lwjglx.opengl.Display.create`: `pzopt.CoreGl.windowHints()` after HDR's hints (GLFW: 4.1, core profile, forward
  compatible); if `glfwCreateWindow` returns no window, `CoreGl.retryWithoutCore()` puts the stock hints back and the window is
  created again (Apple's legacy 2.1 context, as stock). `capabilities = CoreGl.capabilities(GL.createCapabilities())`: on a core
  context LWJGL's table is rebuilt with the shim's provider (Java upcalls for the removed fixed-function calls, aliases for the
  EXT / ARB names, a no-op for entry points the driver lacks) and made current. The ImGui backend gets `#version 150` instead
  of `#version 120` on a core context (debug builds only). `swapBuffers` calls `CoreGl.frame()` (10 s counters; GL errors per
  frame with `devCoreGlTrace`).
- `zombie.core.opengl.VBORenderer.renderRun`: a `GL_QUADS` run (mode 7) on the core context is drawn by
  `CoreGl.drawQuads` (a shared quad -> triangle index buffer, `glDrawElementsBaseVertex` at the run's first vertex; the run's
  own indices are sequential by construction) and the renderer's element buffer is bound again; the core profile has no
  `GL_QUADS`, so these runs (corpse / item atlas blits, debug quads) drew nothing.
### Car glass (`carGlass`, 2026-10-01; `pzopt.CarGlass`, `docs/findings-car-glass-2026-10-01.md`)

Nothing below changes a pixel or a timing while `carGlass` is off (the default): the hooks return at once, no shader is
patched, the `pzopt_glass_*` copies build.sh installs are never loaded. The game's own vehicle programs are never patched,
on or off: the glass is a program of its own (inlining it into the stock vehicle shader cut every car fragment's speed to a
quarter, measured).

- `zombie.core.skinnedmodel.model.Model.DrawVehicle`: the vehicle-shader branch keeps its `VehicleModelInstance` in
  `pzoptVmi`; after the stock `mesh.Draw` and `effect.End()`, `pzopt.CarGlass.glassFor(effect, slotData, instData, vmi)` returns
  the glass program for a car body on this frame's list (null for sub-models, cars off screen or too small, glass off) and
  the new `pzoptDrawGlass` draws the body again with it: the transform / palette and target depth (the compact glass
  program reads nothing else of the stock uniforms; with `carGlassCompact=false` the stock draw's setters are repeated),
  `CarGlass.draw` (one upload of the car's glass data, the frame's once), depth func LEQUAL (the stock draw wrote the same
  depth), and only the mesh's glass triangles (`CarGlass.glassTriangles`: an index buffer per mesh and skin glass map,
  read back asynchronously) through `VertexBufferObject.BeginDraw` / `glDrawElements` / `FinishDraw`, else the full mesh.
- `zombie.iso.fboRenderChunk.FBORenderCell` (before `renderMovingObjects`): `pzopt.CarGlass.beforeMoving(playerIndex)` (the
  frame's sky, sun and cars on screen; queues the probe pass that marches the cars' reflection probes, GPU section
  `carGlassProbe`); the moving objects' GPU section name goes through `pzopt.CarGlass.section` (`devCarGlassAlternate`:
  `.cgon` / `.cgoff`).
- `zombie.core.opengl.ShaderUnit` (compile): `pzopt.CarGlass.patchShader` innermost in the chain; it touches only the
  `pzopt_glass_*` units (vertex: the chassis-frame position and normal as varyings; fragment: the compact glass program).

Window flicker on the flip (2026-10-02, maintainer's save Sandbox/2026-09-26_03-37-09, Radeon 890M / Mesa 26.2): the glass pass
(`Model.pzoptDrawGlass`) redraws the window triangles with GL_LEQUAL against the depth the stock body draw wrote, but the
glass program is a different program (the patched `pzopt_glass_*` vertex copy computes `transform * position` again for
`pzGP`) and nothing declares `gl_Position` invariant, so Mesa's depth came out a rounding step off: per pixel and per frame
the glass lost the test and the stock window's striped sky map showed through (halftone dots, staircases, whole panes
toggling as the camera moved). `Model.pzoptDrawGlass` now draws the glass with `glPolygonOffset(-1, -4)` (key
`carGlassDepthOffset`, default on; false = the old pass). Same build, same walk, 1:1 capture of three parked cars: window
pixels changing per frame 3.38 % (p90 9.6 %) with the offset off, 0.09 % (p90 0.27 %) with it on (runs `cgfix-off` /
`cgfix-on`; `cgflick-*` = the repro and the per-term cycle, which ruled out probe, sky texture and cabin).

### Model re-dress order and the fence height of the sun share (2026-10-02; `pzopt.ModelInitOrder`, `pzopt.SunShadow`)

A player report ("8 errors", the character's shadow lost while walking). The errors were render-thread exceptions:
`ModelManager.Reset` (a character re-dressed: clothes, hair, a timed action) puts a new `ModelInstance` into `slot.model`
on the game thread, while the draw init TextureDraw queued for that slot in the previous frame (the stock slot-init pool)
may not have run yet; that init reads `slot.model` when it runs, found the new instance with no per-player lights
(`playerData is null`), and the render thread dropped the rest of the frame (the sun shadow pass with it), after which the
replayed state threw `texd.vars is null` in the weather composite for a few dozen frames.

- `zombie.core.textures.TextureDraw.drawModel` / `DrawQueued`: the queued init's future goes to the slot
  (`pzopt.ModelInitOrder.queued`). Dev only: an init that throws logs the model's state (`CharDraw.devInitFailed`) and is
  thrown again; with `devCasterTrace` inits over 2 ms from queued are logged with their queue / lock / run time.
- `zombie.core.skinnedmodel.ModelManager.Reset`: `ModelInitOrder.beforeReset(slot)` waits for that future (at most 50 ms,
  then on as stock) before the slot's model is replaced. New field `ModelSlot.pzoptInit`.
- `zombie.iso.weather.WeatherShader.startRenderThread`: the uniforms from `texd.vars[6..23]` are set only when `vars` is
  there (stock already guards `vars[0..5]`); a missing array keeps the last frame's values.
- `pzopt.SunShadow.march` (no game class): with `sunShareWallHeight` (default on, live) a wall / fence edge the ray to the
  sun crosses shades the caster only when the ray passes below the edge's top measured from the sprite
  (`CapsuleShadow.edgeTop`, the sunShadowWallCut measure; its cache is concurrent now, the march runs on the frame
  workers); before, any collide / wall edge at any height took the whole sun share, so a hoppable fence up to ~5 squares
  off under an evening sun put the shadows of the characters and animals beside it out. Off: the old test.
- Dev: `devCasterTrace=N` logs the local player's caster facts every Nth frame and, every frame, the player's sun shadow
  area on screen (its instance alone in a `GL_SAMPLES_PASSED` query, read back later, with the epoch); harness
  `--flag explore=walk --flag "walk=x1,y1;x2,y2"` walks the player between points on foot (`walk=probe` logs a map of
  the squares round the player).

## The flip's driving report (2026-10-02): whole-scene flicker (HDR) and striped crowns (treeAppend under AO / sun shadows)

The maintainer's flip (Radeon 890M, Mesa; their save `Sandbox/2026-09-26_03-37-09` and options file: HDR, FSR1, AO, sun
shadows, pixelLight, relief, ...) while driving through Riverside at max zoom: "heavy flickering of the whole scene" and
"artifacts in the trees on the right side of the screen" (horizontal bands across crowns). Rigs and numbers:
`docs/findings-flip-drive-2026-10-02.md`.

### pzopt.HdrLight: a light map read during a chunk-map shift is dropped

The worker builds the HDR light map from the cell's chunks on its own thread. When the car crosses a chunk line the game
thread re-centres the chunk map and `cell.getChunk` returns null for most of the grid for a moment; a map built then read
22-1,500 of its 2,401 squares, the missing ones carry no sun exposure (aux red = 0), and the one composite that used it
lost the HDR sun gain (`hdrSunPct`) everywhere: the world one frame ~12 % darker, ~0.3 times a second (32 frames on the
94 s town drive, 0 with `hdr=false` or `hdrSunPct=0`). A build that reads under 90 % of the last kept one's share is
dropped (the held map stays, re-projected); a lower share that persists for three builds (a world edge) is kept. Squares
a kept map did not read take the mean sun exposure of those it did, not 0. Town drive 32 -> 0 dark frames, frame times
unchanged. `hdr: light map ... N short maps dropped` in the periodic line.

### pzopt.HdrExposure: the floor comes from the player's z

The sample that selects the light map's floor took it from `player.getCurrentSquare()`, null for a frame now and then
while driving (10 in 23 s); the sample was then UNAVAILABLE and the composite dropped the aux map for that frame. The
floor is now `floor(player.getZ())`, as `HdrLight.queue` builds the map. (Not the main cause of the dark frames.)

### zombie.iso.fboRenderChunk.FBORenderCell: no treeAppend while a pass keeps a per-texel term of the texture

`treeAppend` draws a newly arrived chunk's trees into finished neighbour textures. With the chunk AO / sun-shadow term
(`pzopt.ChunkAo`) on, a texture's later deferred recompute applies new / old term to its colour, assuming every texel
holds the old term; an appended crown never got it and came out divided by the term of the ground under it: bright
horizontal bands, several pixels wide, across the crown (in-game ablations: gone with `treeAppend=false`,
`sunShadowTrees=false`, `sunShadowTreeCards=false` or the kernel's crown sun path off; the kernel's tree classification
was not it). The append is now refused (`pzopt.TreeBake.appendAllowed()`: not with `ChunkAo.enabled()` or relief, whose
code is per texel too) and the texture re-bakes as with `treeAppend` off. Forest spot still, crown band energy 8.4 ->
5.0 (control `sunShadowTrees=false` 5.4, Jev fixed 0.89); flip town drive 86.0 -> 86.2 fps, p99.9 35.4 -> 32.5 ms.

- Dev: `devAoDefines=A,B` defines names in the chunk AO / sun kernel (`TREE_NO_CLASS`, `TREE_NO_SKIP`, `TREE_NO_SUNPATH`,
  `TREE_NO_SKY`, and the views `TREE_DEBUG_OWN` / `TREE_DEBUG_OWNID` / `TREE_DEBUG_D0` that replace the raw sun term);
  `devHdrFrameLog=true` writes one line per HDR composite (`pzopt-hdrframe.out`: epoch ms, bloom / aux / light map state,
  the map's uploads and the last upload's sun mean and squares read).

## The 42.21 drive regression: the tree cutaway while driving (2026-10-03; `docs/findings-4221-drive-regression-2026-10-03.md`)

42.21 counts a player in a vehicle as aiming in `isTranslucentTree`, so every tree whose base lies in the cutaway square
round the car turns see-through; with baked trees each one re-baked its chunk texture and the neighbours holding its copy
up to four times and was drawn per frame in three passes (120 km/h drive 500 -> 305 fps on the desktop).

### zombie.iso.fboRenderChunk.FBORenderCell

- `checkTreeTranslucency` (`treeRebakeLazy`): the tree's fade / see-through flags are updated as before, but its level
  is re-dirtied only when the tree leaves the bake (out of it = see-through or fading) or comes back; coming back is
  recorded per level with a deadline (`treeRebakeLingerMs`, default 0) and re-dirtied by `checkChunksWithTrees` when it
  runs out, unless the level baked meanwhile (the bake clears it). A tree whose see-through flag and fade end in the same
  check re-dirties at once.
- `treeCutawayReach`: `isTranslucentTree` split into `pzoptXxlCutaway` (42.21's XXL fade) and `pzoptTreeRule` (the stock
  test). A tree needs per-frame drawing when the XXL fade applies, or when it is see-through / fading and a generous box
  of its sprites comes within `treeCutawayReachPx` of the cutaway mask's marked box (`pzopt.CutawayMask`); the bake's
  layer decisions (`isObjectRenderLayer_MinusFloor` and its translucent counterpart: the tree case and the fade case)
  and the tree pass (`pzoptTreeBakes`) use that need instead of the stock test while the key is on; a weak per-tree set
  remembers the need at the last check or bake and the transitions re-dirty as above. A tree kept in the bake gets
  `IsoTree.render`'s fade step (fboRenderChunk branch, same constants) from `checkTreeTranslucency`, also for a dirty level
  (`pzoptReachDirtyTick` sets its see-through flag first), and only while its render layer is a baked one.
- `driveTreeCutaway` (default false since 2026-10-03, maintainer's decision; true = 42.21): the vehicle term of the aim flag in `isTranslucentTree`, `checkTreeTranslucency`
  and the tree pass; false = 42.20's rule.
- Dev rigs: `devTreePassCycle` names the `translucent` GPU section by pass mask; `devReachCheck`; `devStencilProbe` round
  `drawStencilMask` and before the per-level loop; bake counters for the tree transitions.

### zombie.iso.fboRenderChunk.FBORenderTrees

- `renderTree`: `devTreePassCycle` can skip each of the three passes; `devReachCheck` wraps the passes of a tree the reach rule would
  have kept baked in `GL_SAMPLES_PASSED` queries (polled without waiting at the next batch).
- `addTree` / `render`: per-frame tree counters; the probe flag.

- Hotfix (2026-10-03): be50962 also scissored the inside passes to the mask's box (`treeCutawayScissor`); the box was in
  offscreen pixels while the world framebuffer is screen-sized, so at zoom != 1 it hid the cutaway. Removed.

### zombie.iso.IsoMovingObject

- `separate`: with `edgeTestFast` the neighbour test asks `pzopt.EdgeFast.isBlockedTo(current, sq)`, 42.21's
  `IsoGridSquare.isBlockedTo` written out without its predicate objects (same result: `devEdgeFastCheck`, 23.9 M calls,
  0 different). `pzopt.SeparateMask.blocked` (the batched separation) asks it too.

### pzopt classes (no game class changed for these)

- `pzopt.EdgeFast`: `edgeTestFast` / `devEdgeFastCheck`.
- `pzopt.CutawayMask`: the marked box of each cutaway mask texture (PNG read once, alpha > 25/255, one-texel guard),
  placed on the frame's `IsoCell.StencilArea`s.
- `pzopt.StencilProbe`: `devStencilProbe`'s reads.
- `pzopt.XxlTreeFade`: `devXxlVehicleFade` (dev) turns 42.21's XXL fade while driving off.

## Dynamic resolution (`dynRes`, 2026-10-03; `pzopt.DynRes`, `pzopt.Taau`, `docs/findings-dynamic-resolution-2026-10-03.md`)

All off by default (`dynRes=false`, `upscaler=off`); with them off every line below is a no-op or the stock call.

### zombie.GameWindow
`renderInternal`: right after `SpriteRenderer.NewFrame`, `DynRes.beginFrame()` picks the frame's render scale (the
controller's wish under its rate limits and hysteresis) and files it under the frame's `SpriteRenderState`.

### zombie.core.opengl.RenderThread
`lockStepRenderStep`: `DynRes.gpuBegin/gpuEnd` around `SpriteRenderer.postRender` (beside the GpuPstate pair): the
render thread latches the acquired frame's scale and brackets the replay with two GL_TIMESTAMP queries (read a few
frames later, no stall). `Ready`: `DynRes.pushed` after `pushFrameDown` records the game thread's own time for the frame
(step start to hand-off) for `dynResCpuAware`.

### zombie.core.textures.MultiTextureFBO2
`render`: `DynRes.queueDevLoad()` before the upscaler's resolve: the `devDynResLoad` rig's synthetic per-pixel GPU work
in the world image (nothing without the dev key).

### zombie.iso.WaterShader, zombie.iso.PuddlesShader, zombie.core.skinnedmodel.ModelManager, zombie.vispoly.VisibilityPolygon2, zombie.iso.weather.fog.ImprovedFog, zombie.iso.sprite.IsoCursor
The vertical sizes / origins the upscaler scales (`WViewport.w`, the particles' viewport height, the view-cone blur's
`displaySize.y`, fog `screenInfo.y` / camera top, the aiming cursor's background height) take `RenderScale`'s vertical
factor (`viewPxY`, `scaledPxY`, `visBlurPxY`, `cursorBackgroundScaleY`), which differs from the horizontal one only
with `dynResAxes=x` (horizontal-only scaling).

## The people inside a car (2026-10-03; `pzopt.CarOccupant`, `pzopt.CarGlass`; write-up docs/findings-car-occupant-2026-10-03.md)

### zombie.core.textures.TextureDraw

`DrawModel` in `render()`: first `pzopt.CarOccupant.markDrawModel()` (dev timing of an occupant's slot-init wait, nothing
outside an occupant pass); after the slot's init future is waited for, `pzopt.CarOccupant.capture(this)`: while an occupant pass
is open (its Begin drawer queued by `CarOccupant.queue` ahead of the moving objects), a seated character's model is drawn into its
car's impostor tile with the pass's camera (the world view, projection zoomed onto the cabin, the seat placed in the glass's
chassis frame) and the world draw is skipped (`break`). Every other DrawModel goes on as before.

### zombie.characters.IsoGameCharacter

`render(...)`: the stock early return for a character seated in a vehicle without the seat script's `showPassenger` also
passes when `pzopt.CarOccupant.showStock(this)` (`carOccupant=stock`, a dev A/B of the stock path: the model among the moving
objects, covered by the opaque glass). Default: unchanged.

### pzopt.CarGlass (not an override; for the record)

The glass program and the chassis-frame capture run only for the car's world draw (`ModelCamera.instance ==
VehicleModelCamera.instance`): the sun shadow pass draws the same car from the sun into its atlas, and with sun shadows on the
glass was drawn there too (wasted) and the occupant was placed from the sun camera's frame.

## Window tiles as a dark column (`pplWallEdge`, 2026-10-03; `pzopt.PixelLight`, no override; docs/findings-window-column-2026-10-03.md)

- pixelLight blended a wall pixel with the square behind its wall wherever the native shares the corner colours across it:
  windows by day, door frames. Every window tile's wall face took half the room's light (a full-height grey column).
- `pplWallEdge` (default on): the pack sets bits 0 / 1 of the connection texture's g byte for a square whose west / north
  edge holds a wall (`ChunkAo.edgeW` / `edgeN`, now package-private) and is connected across it (such a square is not simple);
  the shader drops that connection and the diagonal for points above the floor on the wall's side. Floors unchanged.
  Rig: `--source-save Sandbox/2026-10-02_10-30-09 --shot-at 2` + `harness/wall-column.py` (Jev).


## Occluded zombie outlines (`occludedZombieOutlines`, 2026-10-03; PR #48 by novakovicdavid, reworked; `pzopt.OccludedOutline`)

The parts of a zombie the player sees that scenery hides get a thin contour. The PR's version replayed every eligible
zombie into its own targets and patched every world material shader to record an opaque-depth image; the rework rides
the world framebuffer's stencil (bits 0x7F; 0x80 stays the game's player-mask bit) during the draws the game already
makes. Design and costs: `docs/plan-occluded-zombie-outlines.md`.

- **TextureDraw:** field `pzoptOutline` (0 none, 1 outline, 2 outline with the plant slack, 3 seen but nothing in
  front can hide it), set in `drawModel` from
  `OccludedOutline.eligible` (the player's current sight, on the game thread). The DrawModel command brackets
  `drawer.render` with `OccludedOutline.beginModel` / `endModel`: a character's draw gets its stencil codes, a
  vehicle's draw clears the "visible" bit where it lands.
- **Model.DrawSolid:** `this.mesh.Draw(effect)` runs only when `OccludedOutline.drawMesh(mesh, effect)` returns false
  (no character codes set). Otherwise drawMesh does the stock draw's own steps (`VertexBufferObject.BeginInstancedDraw`,
  `PushDrawCall`, `FinishInstancedDraw`) with the stencil code written where the mesh passes the depth test, and in
  between the elements once more with the depth test inverted, colour and depth writes off (the hidden part).
  DrawChar's `GLStateRenderThread.restore()` after each mesh puts the state back.
- **IsoZombie:** `renderTextureInsteadOfModel` passes the eligibility with its atlas draw
  (`BodyTexture.pzoptRenderWithOutline`); fields `pzoptOutlineSquare` / `pzoptOutlineNs` / `pzoptOutlineClass`
  cache the occluder test (`OccludedOutline.classify`) per square.
- **DeadBodyAtlas:** `BodyTexture.pzoptRenderWithOutline` queues the stock depth drawer with the eligibility;
  `BodyTextureDepthDrawer.pzoptOutline` (cleared in `init`) makes `render` call `OccludedOutline.atlas` before the
  quad flushes (its stencil code; the quad is kept for the hidden-part pass).
- **FBORenderCell.performRenderTiles:** `OccludedOutline.begin` after the chunk composite, `OccludedOutline.finish`
  before the fog (the atlas zombies' hidden quads and the contour pass).

## Light from the torch itself (`torchSource`, 2026-10-03; `pzopt.TorchSource`; docs/findings-torch-source-2026-10-03.md)

### zombie.characters.IsoGameCharacter (inner class TorchInfo)

- New public fields, all `pzopt`-prefixed: `pzoptSrc` / `pzoptLx` / `pzoptLy` / `pzoptLz` (the carried light's lens for the
  per-pixel consumers), `pzoptFrame`, `pzoptHolder` / `pzoptItem` / `pzoptPart` (what the last `set` was called for),
  `pzoptOut` (the render-time solve, reused by the same frame's native update), `pzoptHoldValid` / `pzoptNx` / `pzoptNy` /
  `pzoptHx` / `pzoptHy` / `pzoptHz` / `pzoptHax` / `pzoptHay` (the native's held position and what it was held for).
- `set(IsoPlayer, InventoryItem)`: after the stock body, `pzopt.TorchSource.onSet(this, p, item)`. With `torchSource` off it
  only records the holder and clears `pzoptSrc` (stock values untouched). On: `x` / `y` become the lens of the drawn item (the
  hand prop / attachment / weapon light part placed as `AnimatedModel.transformToParent` places it, mapped to the world as
  `Model.vectorToWorldCoords` maps bones; the mesh box's support point along the beam), kept on the holder's side of its
  square's walls, closed doors and windows (`torchSourceWallClamp`) and held while the holder stands still and the lens
  sways less than `torchSourceHold` hundredths of a square; `angleX` / `angleY` follow the item's axis with
  `torchSourceAim=item`. `z` stays the holder's (the native's level). No model drawn (invisible holder, a mesh still
  loading): stock values.
- `set(VehiclePart)`: clears `pzoptSrc`, records the part (re-placed at render time where vehicleSmooth draws the car,
  `torchSourceVehicles`; render-time readers only, the native keeps the step's values).

### zombie.iso.fboRenderChunk.FBORenderCell

Ahead of `pzopt.PixelLight.beforeComposite`: `pzopt.TorchSource.renderFrame()` (with `torchSource` on): the frame's lens
re-solve for every per-pixel consumer and the dev markers, whichever consumer is on (without pixelLight nothing else asked).

### zombie.iso.LightingJNI (JNILighting.updateFBORenderChunk), 2026-10-03

Dev rig `devVisBlinkTrace`: when a refresh changes the square's visibility bits, `pzopt.VisBlink.change(square, was, now)`
(off: one static final test). The tile-flicker investigation of `docs/findings-torch-source-2026-10-03.md`.
## Mirror and window reflections (`mirrors`, 2026-10-03; `pzopt.Mirrors`; write-up docs/findings-mirrors-2026-10-03.md)

Off by default (Enhancements tab "Mirrors and windows", next launch). With `mirrors=false` every hook below is a static
test that returns at once.

### zombie.iso.fboRenderChunk.FBORenderCell
- `performRenderTiles`: `Mirrors.beginFrame` before the chunks (last frame's panes under this frame's camera, the atlas
  decisions, the visibility read-back), `Mirrors.afterComposite` after the static-world passes and before the players (the
  static march), `Mirrors.afterMoving` after the moving objects (the mirrored model draws into the layer),
  `Mirrors.afterTranslucent` after each level's translucent objects and `Mirrors.afterLevels` after the level loop (the
  composite: per level, or once with `mirrorsCompositeOnce`).
- `renderTranslucent(IsoObject)`: the stock body moved into `pzoptRenderTranslucent`; the new method wraps it in
  `Mirrors.beginCapture` / `endCapture` so a window's or mirror tile's quad is captured while it draws.
- `pzoptPerFrameTranslucentTile`: a mirror tile (`IsMirror`, Facing S / E, with a glass mask) is drawn per frame
  (`Mirrors.perFrame`), out of the chunk textures, so its quad is captured and its glass composited over.
- Wall mirrors the map placed (2026-10-04, player save: a tall living-room mirror never reflected). 18 of the 24 masked
  mirror tiles (`walls_decoration_01_*`) are `WallOverlay` tiles: `CellLoader` adds them to their wall's
  `attachedAnimSprite` list instead of making an object, so they bake with the wall and the object test above never saw
  them (the 10-03 rigs placed their mirror with `place_tile`, a separate object). The level preparation now lists a
  square whose object carries a mirror overlay (`Mirrors.attachedMirror`) among the animated-attachment squares, and the
  per-frame animated-attachments pass (`renderAnimatedAttachments(IsoGridSquare)`) calls the new
  `pzoptCaptureAttachedMirror`: the overlay drawn once more with the bake's own sprite call at alpha 0.001 (Texture.render
  skips exactly 0; the byte colour is 0, so no pixel changes) between `Mirrors.beginCaptureAttached` / `endCapture`, which
  take its quad with the overlay's own alpha; skipped while the wall's side is cut away (`getPlayerCutawayFlag` bit 1 N /
  2 W). The baked mirror stays in the chunk texture and the reflection is composited over it.

### zombie.core.textures.TextureDraw
- New field `pzoptMirrorPlanes` (the frame's pane planes a model shows in, bits).
- `drawModel`: `Mirrors.planesFor` fills it (characters / vehicles within `mirrorsModelRange` of a visible pane they reach).
- `Create` (the central one): while a pane draws (`Mirrors.capturingNow`) the quad and texture go to `Mirrors.captured`.
- `DrawModel` render: after the model's world draw (and the shadow atlas draws), `Mirrors.renderModel` queues the same
  slot for the frame's mirrored draws.

### pzopt.ShadowAtlas (not an override)
- `Tracked` is package-private now (the mirrored model flush saves / restores the tracked GL state the same way).

## Light and shadow fixes from the Discord report (2026-10-04; `pzopt.PixelLight`, `pzopt.ChunkAo`, `pzopt.CapsuleShadow`; docs/findings-light-shadow-2026-10-04.md)

### zombie.iso.LightingJNI (inner class JNILighting), seventh edit
- New public field `pzoptBaseMem` (int, -1): the square's light without a handheld torch on it, packed rgb, written by
  `PixelLight` while it packs the lattice (`pplClipBase`). Where the torch saturates the native's light, the base under it
  is that remembered light (at least light - torch) instead of the night ambient estimate: a street lamp's light no longer
  vanishes round the player. No method changed.

### pzopt.PixelLight (not an override)
- `pplTorchVehicleMix`: the native adds the brightest of the torch and vehicle lights to a square (max, measured per
  square); where a headlight is the brighter, nothing of the torch is taken out of the base, where the torch is, the
  headlight is put back; the torch-hidden test compares against the brightest of the two.
- `pplNormalSpanWide` (4): a texel normal that did not snap to a plane tries neighbours 4 texels away and keeps that
  normal when it snaps to a wall plane of a square with a wall on that edge (packed in bits 2 / 3 of the conn texture's g),
  within 0.2 of the edge, close to the short normal: the DEPTH16 rounding's dark dot lattice on walls is gone.

### pzopt.ChunkAo (not an override)
- `aoEdgeShade`: when the AO is multiplied into a texture, a texel beside a deeper surface (a step of 0.05+ squares, not
  its own slope) takes the darker AO of the two: a leaf's soft edge before a shaded wall no longer glows.

### pzopt.CapsuleShadow (not an override)
- `sunShadowLampMeshNearPct` / `FarPct` (100 / 300): a character's lamp-view shadow (torch / headlight) fades into the
  capsules' soft shadow from 1 to 3 squares from the caster; a low headlight's leg shadows no longer run as thin torn strands.

## Key binding label: the overlay binding's text (2026-10-05, Discord bug report; PerformanceSettings)

### zombie.core.PerformanceSettings
- New method for Lua, `pzoptDefaultUiText(key, text)`: puts `text` into the translator's `UI` table (`Translator.BY_NAME`)
  under a `UI_` key when no translation has it. `pzopt_keybinding.lua` calls it for
  `UI_optionscreen_binding_Toggle performance overlay` ("Toggle Performance Overlay"); before, Options > Key Bindings
  showed that raw key, because the translator reads only fixed `Translate/<lang>/UI.json` files from the game dir and
  enabled mods. Every `Translator.loadFiles()` that empties the table (boot, language change, leaving a game) is
  followed by a Lua reload, which adds the text again; a translation shipped by a mod or language pack wins.

## A cut-open upper floor: furniture against the hidden outside wall, AO bands on roofs (`pplCutEdge`, `aoRoofSkip`, 2026-10-05; FBORenderCutaways, `pzopt.PixelLight`, `pzopt.ChunkAo`)

Report (flip, save `Sandbox/2026-09-26_03-37-09`, Riverside house at 6765,5405): with the player in the yard the house's
upper floor shows cut open. That part is stock: the level-1 squares outside the rooms (ceiling, exterior wall, roof, no
room) are orphan structures `shouldRenderBuildingSquare` stops drawing near the player (no cutaway flags, every alpha 1).
Ours, two artifacts:
- **The bathtub's front face black.** It lies on the south edge of its square (reconstructed y = 5408.02); pixelLight's
  owner square, nudged towards the viewer, was the hidden roof square outside the house (dusk light 0.28 against the lit
  bathroom's 0.8-1.0); in play the exterior wall covers that face. FBORenderCutaways gets `pzoptSquareHidden` (new
  method: `!shouldRenderBuildingSquare` without its lazy orphan recalculation, read-only, so the lattice pack workers can
  call it). `pplCutEdge` (default on): the lattice marks a square stock does not draw (bit 16 of the conn texel's g), and
  pplSeenEdge's path hands a point within 0.1 of its west / north edge to the square before it. A first attempt keyed on
  the cutaway flags (PCF_NORTH / PCF_WEST) did nothing here: none are set.
- **Dark horizontal bands across the roof** (Enhancements: AO). A roof sprite's depth is a staircase snapped to the floor /
  wall planes; the horizon kernel shaded every riser and `aoEdgeShade` (2026-10-04) spread that 2-4 texels onto the row
  above. `aoRoofSkip` (default on): no horizon on a texel whose own column has a roof tile on the texel's own level
  (`roofLevels`, a new 2 x 256-bit uniform `roofLv` for every compute, night ones too), treads and risers alike, real
  floors (a floor-like texel at a whole level) excluded. The 3 x 3 neighbourhood of `exteriorKind` took the cut-open
  bathroom beside a hidden roof square for roof, so the test is the own column only. The gable soffit's dark wedge under
  the trim goes as well (closer to stock). Dev view `devAoRoofView=true` with `devAoView=1`: the texels taken dark.
Rig: `--source-save Sandbox/2026-09-26_03-37-09 --flag zombies=off --flag route=S:1 --flag speed=0.1 --flag zoom=1
--shot-at 3`, crops of `shot-desktop.png`; `--prop devPplDumpAt=8` (the square dump now lists object alphas and `pcf=`).
Left: the porch gutter's top reads darker than stock with the player's settings (pixelLight, aoEdgeShade and the HDR /
grading tone each add to it; defaults match stock).

## Louisville 120 fps pass (2026-10-05; branch `lou120`, docs/findings-louisville-120-2026-10-05.md)

Defaults: `profilerIdleFast`, `statsNoBox`, `stateMachineNoIter`, `worldgenPatternCache` on (same results as stock);
`animalLosSnapshot`, `lootDefer`, `zombieSpawnSpread`, `slackWork`, `zombieModelAddBudgetUs` off (intended differences,
the maintainer's decision). With every key off the edited methods run the stock bodies.

### zombie.GameProfiler, second edit
- `profilerIdleFast` (default on): `isValidThread` answers from two remembered thread identities (filled when the
  per-thread memo first says yes) and returns false for `pzopt.FrameBatch.Worker` threads without a ThreadLocal lookup;
  `isRunning` returns false without one until some thread's `isRunning` has been true (set only in `startFrame` /
  `endFrame` from `gameProfilerEnabled`, which now also raise a static flag). Same answers; every zombie update and
  postupdate probe skipped two ThreadLocal lookups (2.2 % of the game thread on the Louisville horde).

### zombie.characters.Stats (new override)
- `statsNoBox`: `get` reads the map with a plain `get` and returns the stat's default only for an absent key, instead of
  `getOrDefault` with a freshly boxed default (10 % of the sampled allocation). A key mapped to null still throws on the
  unboxing, as stock.

### zombie.ai.StateMachine (new override)
- `stateMachineNoIter`: `getMinimumSimulationLevel` walks the substates by index instead of an iterator (10 % of the
  sampled allocation once tiered updates classify every object every frame).
- Decompiler fix: the `stateAnimEvent` lambda's parameter types written out (Vineflower lost them; javap
  `lambda$stateAnimEvent$0`).

### zombie.characters.animals.IsoAnimal, second edit
- `animalLosSnapshot` (with `animalLosFast`): `updateLOS` scans `pzopt.AnimalLosSnapshot`, built once per scheduler frame
  from the cell's object set in its own order (zombies passing the grapple / square filters with their position, non-animal
  players, animals), instead of walking the whole set per animal; zombies fold into ticks or a near `spotted` call as
  before, players take the stock body with live reads, the animal's own entry adds it to its spotted list. Intended
  difference: the zombies' positions and filters are those of the frame's first animal update. 0.80 -> 0.25 ms a frame.

### zombie.MovingObjectUpdateScheduler, second edit
- `zombieSimLodTiles` now honours the A/B rig (`GtAb.SIM_LOD`); with `instrument` the number of zombies per simulation
  level is logged every 600 frames (`sim levels`).

### zombie.LoadGridsquarePerformanceWorkaround (new override)
- `lootDefer`: in `checkObject`, an unexplored container of a chunk arriving `lootDeferDistance` (20) squares or more from
  every player is queued (`pzopt.LootDefer`) instead of rolled; the queue rolls it later with the same calls (fill,
  explored, overlay sprite), in the frame's slack at a cap (`slackWork`) or under `lootDeferBudgetUs` a frame uncapped.
  Intended difference: the order of the game's random draws. Single player only.

### zombie.iso.IsoChunkMap, second edit
- The hand-off loop body of `updateInternal` moved verbatim into `pzoptHandOffChunk(chunk)` (true when the chunk joined).
- `chunkHandoffSlackWork`: at a cap with fewer than `chunkHandoffSlackBacklog` (24) chunks queued, no chunk is handed off
  inside the frame; a `SlackWork` producer takes them off the queue one at a time and hands them off in the step's slack
  when the learned cost per square times the chunk's squares fits (then `calculateZExtentsForChunkMap` on each player's
  map). A larger backlog (world load, teleport) or no cap takes the stock path; a chunk the producer staged goes first.
- `LootDefer.drain` once a frame; section timer `chunkMapUpdate` for the A/B rig.

### zombie.popman.ZombiePopulationManager, second edit
- `zombieSpawnSpread`: the zombies the native population turns real are decoded and filtered as stock, then those farther
  than `zombieSpawnNear` (25) squares from every player are queued and created oldest first under `zombieSpawnBudgetUs`
  (600) a frame, the budget growing with the backlog (drain within `zombieSpawnDrainFrames`, at most `zombieSpawnMaxUs`);
  for `zombieSpawnLoadMs` (3000) after the session's first population update everything goes at once, as stock (the
  load's mass spawn). Intended difference: a far zombie appears a few frames later. (A slack-time variant crashed once in
  `createZombieOutsideWorld` with a zero direction vector and was dropped.) Section timer `popmanUpdate`.

### zombie.iso.IsoWorld, second edit
- `zombieModelAddBudgetUs` (500): in `sceneCullZombies`, zombies getting a 3D model this frame (`ModelManager.Add`: the
  model and every clothing model) stop after that much time; the rest stay flat sprites for the frame (their model slot goes
  to the next zombie in score order). Section timers `sceneCull`, `atlases`, `cellRender`.

### zombie.iso.worldgen.WorldGenUtils (new override)
- `worldgenPatternCache`: `canPlace` keeps each placement glob's compiled `Pattern` (same rewrite as stock) instead of
  compiling it in every `String.matches` (~5 % of the allocation, on the world streamer).

### zombie.iso.fboRenderChunk.FBORenderCell, dev counter
- The instrument-only translucent census (`pzoptCountTranslucent`) runs one frame in 16 (it was 1.7 % of a harness run's
  game thread and 4 % of its allocation); counts are scaled by 16.

### zombie.GameWindow, zombie.iso.LightingJNI (section timers only)
- `devGtAlternate` section timers around `logic`, `IsoWorld.FinishAnimation`, `renderInternal` and `LightingJNI.update`
  (the stock body moved into `pzoptUpdateBody`).

### Fixes after the flip / Mac test (2026-10-06)
- `ZombiePopulationManager`, `zombieSpawnSpread`: a queued zombie waits at most `zombieSpawnMaxAgeMs` (1000) of wall time,
  whatever the per-frame budget: on the flip and the Mac (25-35 ms frames) the budget alone let a third to a half of the
  horde still wait at the route start.
- `pzopt.LootDefer`: a deferred roll re-checks the hand-off's own preconditions (the object still has a sprite with a
  name); a container whose object changed meanwhile threw in `ItemPickerJava` (caught by SlackWork's job isolation).

### pzopt (not overrides)
- `SlackWork`: deferred game-thread jobs run in the step's slack at the start of `Pacing.limiterWait` while each job's
  learned cost (running mean, a share of a decaying peak) fits the time left less `slackMarginUs`; an overdue job
  (`slackMaxWaitFrames`) runs in a light frame (half the interval free), any frame at four times the wait; a job's
  exception is logged and skipped. Producers: `LootDefer`, the chunk hand-off.
- `GtAb`: ABBA periods (`devGtAbba`, default on; plain alternation biased an A/A placebo by 0.6 ms) and nine more sections.
- `CorePlacement`: `coreIsolate=N` reserves N physical cores for the game / render threads on non-hybrid SMT CPUs (off: no
  measured gain on the 9800X3D).
- `BakeScheduler`: `bakeTimeGuardPct` (default 0: at 55 the deferred levels cost more than the bakes, 118 -> 88 fps).

## Louisville 120 plan B: zombie postupdate movement on the workers (2026-10-06; branch `lou120-postupdate`, docs/findings-louisville-120-postupdate-2026-10-06.md)

Key `postupdateParallel` (default off), dev rig `devPostupdateCheck`. With the key off the edited methods run the stock
bodies (every new branch tests `pzopt.PostupdateBatch.computing()`, false outside a movement task).

### zombie.MovingObjectUpdateScheduler, third edit
- `postupdate`: before the bucket loop, `pzopt.PostupdateBatch.prepass` runs `IsoMovingObject.postupdate` of this frame's
  eligible zombies (not reused, alive, on a square, not in a vehicle, not grappled / grappling, no reanimated player, no
  animation-player swap or ragdoll pending, not climbing a fence / window / wall) on the frame workers; after the loop
  `finish` puts back a computed zombie the loop never reached. Section timers `pu_loop`, `pu_move`, `pu_flush` and counts
  `pu_zombies`, `pu_moved`, `pu_collided` for the A/B rig.

### zombie.characters.IsoGameCharacter, census and commit
- `postUpdateInternal`: a zombie whose movement was computed this frame (`PostupdateBatch.take`) only commits the latched
  `setMovingSquare` at its place in the loop; otherwise stock (`super.postupdate()`), or, while an A/B alternation runs, the
  same call through `pzoptCensusMove` (one in eight timed, counts of square changes and collisions).
- `pzoptMovingPostupdate` (the stock movement body for the batch), `pzoptAnimPlayerSettled` (the eligibility test that keeps
  `isRagdollSimulationActive` a pure read on a worker).

### zombie.iso.IsoMovingObject, movement task points
- `pzoptMoveSave` / `pzoptMoveRestore` / `pzoptMoveDiff`: the fields `postupdate` writes (position, next, last, impulse,
  squares, collision flags and object, `altCollide`, `firstUpdate`, `collideType`, attack bookkeeping).
- In a task, every point where stock touches shared state throws the batch's stackless bail (`PostupdateBatch.hazard`): the
  task restores the fields and the zombie runs stock inline at its place. Points: the virtualisation at the loaded area's
  edge, the fence climb / thump-target checks after a `DoCollide` collision (both branches), tree noises / rustle in
  `getGlobalMovementMod` (both squares), `collideWith` (the special-object hook and its Lua event).
- `setMovingSquare` in a task latches the square (`pzoptMoveSq`) for the commit.
- The vehicle resolution in a task: `PostupdateBatch.vehicleFree` returns the next position unchanged when no vehicle's
  polygon (the corners `VehiclePoly.init` takes, plus 0.75) meets the move's bounds grown by a square, which is what
  `CollideWithObstacles.resolveCollision` returns with no obstacle; a hazard otherwise.
- The static `tempo` scratch (impulse clamp, `getFeelerTile`) is per thread in a task.

### zombie.characters.IsoZombie, third edit
- `collideWith`: the bail point above (zombies override the hook).

Rig: `--prop devPostupdateCheck=true` re-runs one computed zombie in seven through the stock body from the saved fields at
its place in the loop and compares every saved field and the moving square (the zombie keeps the stock result).

## Louisville 120 item 3: the burst frames (2026-10-06; docs/findings-louisville-120-bursts-2026-10-06.md)

### zombie.VirtualZombieManager (new override)
- `zombieReuseSpread` (default off): `update` queues the zombies removed this frame (`pzopt.ReuseSpread`) instead of
  resetting them all (`resetForReuse`) at once, and resets the oldest under `zombieReuseBudgetUs` (300) a frame, at least
  backlog / `zombieReuseDrainFrames` (60). A queued zombie's vocal sound stops when it is queued (the reset's first step);
  `isReused` also answers true for a queued zombie (stock has it in the pool by the end of its frame); `reuseZombie` tests
  the pool set itself; `createZombieOutsideWorld` resets a queued zombie first when the pool is empty; `Reset` gives the
  queued zombies the frame list's clean-up. Intended difference: the reset's random draws (the speed roll) come later in
  the game's random sequence. Single player only.
- `update`'s stock body moved verbatim into `pzoptUpdateBody` under the section timer `vzmUpdate`.

### zombie.iso.IsoChunkMap, third edit
- `ProcessChunkPos`'s stock body moved verbatim into `pzoptProcessChunkPosBody` under the section timer `chunkPos`; the four
  grid shifts (`LoadUp` / `Down` / `Left` / `Right`) mark their phases for `pzopt.ChunkShiftTimer` (dev, only while an A/B
  alternation runs: a console line for a shift over 1 ms).

### zombie.iso.IsoChunk, dev timers
- `removeFromWorld` times its phases (collision / animals, the zombie population's removal, save request / pathfinding, the
  square loop, vehicles / render frees) while an A/B alternation runs and logs a removal over 0.2 ms with the chunk's levels,
  squares, objects and movers.

### zombie.popman.ZombiePopulationManager, third edit
- `removeChunkFromWorld`: the native `n_loadChunk` call timed into the `nativeUnload` column while an alternation runs.

### pzopt.BakeScheduler (census)
- While an alternation runs, the grants of each frame by tier (must / level-change burst, arrival quota, overdue, normal)
  and the levels offered go to the `bake_t0..3` / `bake_offered` columns.

## Mirrors by a room corner: the wall's cutaway lock (2026-10-06, maintainer report on the flip; docs/findings-mirror-corner-2026-10-06.md)

The game cuts a room's walls away round the player. At a room corner the visitor's cut of a wall flips every 0.1-0.7 s
while the player walks (its points of interest are the squares the player can see, which change with every turn; the
same with every pzopt cutaway key off, so stock behaviour). Stock's `IsoGridSquare.setPlayerCutawayFlag` keeps a 750 ms
lock, but with `fboRenderChunk` `getPlayerCutawayFlag` returns the target flag and the lock is never read. On a wall
carrying a mirror the reflection made every flip a visible pop (the upstairs bathroom mirror of the flip save).

### zombie.iso.fboRenderChunk.FBORenderCutaways
- `doCutawayVisitSquares(int, ArrayList)`: after the exterior-wall pass, before the change detection, calls
  `pzoptHoldMirrorWalls` (`mirrorsCutawayHoldMs`, default 750, 0 = stock). For every square whose walls carry a mirror
  (`Mirrors.mirrorWallBits`: a map wall-mirror overlay or a mirror tile; both walls of a corner square) it keeps, per
  cut bit, what the visit wanted and what is applied: a change applies at most once per hold (the first cut at once),
  and the wall comes back only after visits have not wanted it for twice the hold. The applied state goes into the
  visitor's result sets (this frame's cuts and the next visit's squares to clear), so the stock flag loops and the
  change detection run on it unchanged. First player only (split screen: the others stock).
- New `pzoptHoldTick(long)`: the visit runs only when something changed, so a change that became due without one is
  applied here (flag set / cleared, its chunk level invalidated with 2048). A forced visit was tried first: it gave
  another verdict than the last visit and the wall came back for 0.1 s.

### zombie.iso.fboRenderChunk.FBORenderCell
- After the cutaway visit: `FBORenderCutaways.pzoptHoldTick` every frame for the first player.
- `renderMinusFloor_DoorOrWall`: `Mirrors.wallDrawn(object, cutawaySelf)` records the cut a wall carrying a mirror
  overlay was drawn (baked) with; `pzoptCaptureAttachedMirror` captures by that cut (`mirrorsDrawnCut`, default on)
  instead of the live flag, so the reflection follows the wall on screen even when a re-bake lags the flag.
- Dev (`devMirrorsLog`): `renderOneLevel_AnimatedAttachments` / `renderAnimatedAttachments` / `pzoptCaptureAttachedMirror`
  report why a wall's mirror overlay was or was not captured (`Mirrors.devAttached`; the console line `mirrors: dev
  attached ... A -> B (frame N screen X,Y, epoch_ms=T)` on every change).
## Louisville 120 plan A: the translucent tile pass recorded on the workers (2026-10-06; branch `lou120-tilerecord`, docs/findings-louisville-120-tile-record-2026-10-06.md)

Keys: `tileRecordParallel` (default off), `tileRecordAsync` (on), `tileRecordWalls` (on); dev `devTileRecordSerial`,
`devTileRecordDeferAll`, `devDrawListCheck`. The common rule of every edit below: on a thread that records a tile draw
unit (`pzopt.DrawRecorder.recording` and the thread bound to a recorder) the code uses that thread's own copy of a
piece of state stock keeps in a static or a shared singleton; on any other thread (the game thread outside a recording,
the render thread) it uses the stock static, so with the key off nothing changes.

### zombie.core.sprite.SpriteRendererStates (new override)
- `getPopulatingActiveState` hands a recording thread its recorder's own render state, so every SpriteRenderer call of
  the unit appends to the unit's list.

### zombie.core.opengl.IOpenGLState (new override)
- Each state gets a slot number at construction. `set` on a recording thread goes to the recorder's own cache
  (`pzopt.DrawRecorder.glSet`); the first set of a state in a segment is recorded as a conditional set, issued at splice
  time against the game thread's cache (stock's dedupe). New public bridges for the recorder and the check rig: a fresh
  value, issue an entry without touching the cache, whether stock would issue, adopt a value, snapshot / restore.

### zombie.IndieGL (new override)
- The six `temp*` value scratch objects and the shader stack come from the recorder on a recording thread; the shader
  stack entry pool is taken under a lock there.

### zombie.iso.IsoObject (new override)
- Every method using the `stCol` / `stCol2` scratch reads the thread's copy (a local of the same name at the method top).
- `prepareToRender` leaves `lastRendered` / `lastRenderedRendered` / `lowLightingQualityHack` alone on a recording thread
  (nothing reads them).
- The depth / seam / cutaway modifiers and the wall shapers come from `pzopt.RenderScratch` (per thread, so the identity
  tests along the way hold); the render-mode flags (`renderTranslucentOnly`, `renderAnimatedAttachments`, the highlight /
  outline / caching modes, `renderWindowFrameOutline`) are read through FBORenderCell's per-thread getters.
- New: `pzoptSpritesWarm` (main / overlay / attached / light-on sprites drawn once on a non-recording thread, no flipped
  or self-fading shared instance) and `pzoptHasChildrenOrSplats`.
- Decompiler fixes: `getFasciaAttachedSquare` (a variable name Vineflower reused), `Thump` (the jar reads a static
  constant through the instance: its null check), `renderClockHands` (the jar re-boxes a Float).

### zombie.iso.sprite.IsoSprite (new override)
- `info`, the `l_renderCurrentAnim` vectors, the chained modifier (`AND_THEN`) and `seamFix2` (null on a recording
  thread) are per thread; the depth / seam modifiers come from `pzopt.RenderScratch`; the next-draw depth goes through
  TextureDraw's per-thread setters.
- `prepareToRenderSprite`: on a recording thread the alpha `IsoSpriteInstance.renderprep` would leave is computed without
  writing the instance (a tile's instance is its sprite's shared def: two threads swapped alphas).
- `render`: a sprite drawn on a non-recording thread is marked warm (`pzoptWarm`); `pzoptRoofKnown` reports whether its
  lazy roof init ran.

### zombie.iso.IsoGridSquare (new override)
- The wall scratch stock keeps in statics (`colu` .. `colr2`, `circleStencil`, `wallCutawayN/W`, `lightInfoTemp`,
  `defColorInfo`, the interpolation colours) moved into a context object: the game thread's (wrapping the original
  objects) or a recording thread's own; `pzoptSetCircleStencil` for FBORenderCell. Modifiers / shapers from
  `pzopt.RenderScratch`, `lowestCutawayObjectN/W` and `renderWindowFrameOutline` through FBORenderCell's per-thread
  getters, the next-draw depth through TextureDraw's setters, the four cutaway mask textures resolved once on the game
  thread.

### zombie.core.textures.TextureDraw, edits
- `nextZ` / `nextChunkDepth`: per-thread setters / getter; `Create` takes the depth through `pzoptTakeDepth` (a
  recording thread's recorder records where a segment takes the depth it inherited, so the splice carries stock's
  leftover from a skipped draw exactly). `pzoptCopyFrom`: the splice copies a recorded entry into the frame's slot.

### zombie.core.opengl.ShaderUniformSetter, edits
- `alloc` on a recording thread takes from its recorder's pool (topped up from the shared one on the game thread before
  each pass); `pzoptSameChain` / `pzoptDescribe` for the check rig.

### zombie.iso.fboRenderChunk.FBORenderCell, edits
- `renderTranslucentObjects`: the chunk loop through `pzopt.TileRecord.translucentPass` when active (stock pass timer for
  the A/B otherwise); `performRenderTiles` starts the asynchronous recording of every level after the chunk composite and
  ends it after the level loop.
- `renderTranslucent(IsoObject)` defers an object a recording thread may not draw; the tree flush before a non-tree
  object and a square's items become splice events; `renderWindowFrameOutline`, `lowestCutawayObjectN/W`, the sort
  scratch (`tempSquares`, `timSort`) and the colour copy in `renderAnimatedAttachments` are per thread; reads of the
  render-mode flags go through per-thread getters; the dev census skips recording threads.

### zombie.iso.weather.fx.WeatherFxMask, LightingJNI, pzopt
- `isRenderingMask` is false on a recording thread. `JNILighting.update` takes the square's lock while units record (the
  game thread may refresh a square a worker refreshes). `LightingDefer.applyOne` runs one unit's lazy-lighting effects at
  its splice. `FrameBatch.Worker.drawRecorder`. New classes `pzopt.DrawRecorder`, `pzopt.TileRecord`,
  `pzopt.RenderScratch`.

### zombie.gameStates.MainScreenState (third edit, `-cachedir=`) and `pzopt.UserOptions`

A player reported (2026-10-06) that with Steam's `-cachedir=E:/Zomboid` the game used `E:\Zomboid` while pzopt's tab
settings, the export and the mod-compat files stayed in `C:\Users\<user>\Zomboid\pzopt\`. `Config` reads `options.ini`
in this class's static initializer (the marker initializes `Overrides`, whose master switch reads `Config.ENABLED`),
before `main` has parsed `-cachedir=`, and `UserOptions.zomboidDir()` only copied the default rule. It now takes the last
`-cachedir=` from the process's own command line (`ProcessHandle` arguments on Linux / macOS; on Windows kernel32
`GetCommandLineW` through FFM, split by the C runtime's quoting rules), else the default rule. `main` calls
`UserOptions.checkCacheDir` right after the console redirect: one `user folder <dir>` line, or a warning when the game's
folder and pzopt's differ (a launcher whose arguments the process does not show). `ModCompat` and `GcChoice` read the
mod lists through the same folder. Unit test `CacheDirArgTest`; harness `run-mac.sh --game-arg`.
