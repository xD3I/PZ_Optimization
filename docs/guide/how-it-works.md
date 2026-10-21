# How it works

[Back to the README](../../README.md) · [Chunk streaming](#1-chunk-streaming) · [Renderer](#2-renderer) ·
[Weather](#3-weather-puddles-rain-lightning-fog) · [Boot](#4-boot-launch-to-main-menu) ·
[Load](#5-load-continue-to-world-ready) · [Visual fixes](#6-visual-fixes-found-on-the-way) ·
[Later passes](#7-later-passes) · [Measured and not adopted](#measured-and-not-adopted) ·
[The install without touching the jar](#how-the-install-works-without-touching-the-jar)

Sections 1-6 are the first passes (2026-09-18 to 09-21) in detail; section 7 lists what came after, each with the
findings document that has its runs.

The game runs one main thread that simulates and renders, one streamer thread that loads
chunks, a save worker and an async file system. Profiling (JFR) at max zoom while driving
showed the main thread spending 80 % of an ordinary frame inside `IsoCell.render`, a third
of all samples in the "translucent" pass that redraws every tree, window and fence each
frame, the streamer idle 90 % of the time yet delivering chunks 150 ms late because of a
polling sleep, and the loading path as a chain of single-threaded parsers. Each group below
attacks one of those; every item names its key.

## 1. Chunk streaming

**Wake the streamer on enqueue** (`wake`). The stock streamer thread checks its queue every
140 ms, so a chunk waited about 150 ms before any work started. The override signals the
thread the moment a job is added: chunk latency 166 → 20 ms at the median on its own.

**Parallel grid recalc** (`parallel`, `workers`). The expensive part of loading a chunk is
recalculating every square against its 3x3x3 neighbourhood; stock does it on the one
streamer thread, and a mutable static (`IsoChunk.chunkGetter`) makes two chunks at once
unsafe. The override gives each job its own getter, runs the pass on a small pool and
publishes results in queue order, so the game thread sees chunks exactly as stock would. The
output is byte-identical: a parity gate compares 131,133 squares in 1,653 chunks at 1, 2, 4
and 15 workers. A worker failure is retried on the streamer thread (a retry that left the
publisher blocked, found in a Windows user's console, was fixed on 2026-09-21 together with
the stock `isWallTo` stack overflow it exposed).

**Hot-save throttle** (`hotsaveIntervalSec`). Whenever the chunk save queue drains, stock
serialises the whole meta grid, game time, world map and entities on the game thread; while
driving that is a 2 to 5 ms stall twice a second. Now at most every 30 s.

**Chunk hand-off** (`chunkHandoffDivisor`). Freshly loaded chunks reach the game thread at
most 1 + queue/8 per frame instead of up to four, which spreads the loot roll and erosion of
a new chunk row over frames.

## 2. Renderer

The game caches walls and floors of each chunk level in a texture and redraws only what
changed. Trees, windows and "translucent" tiles were excluded from that cache and drawn every
frame, thousands of draws at max zoom.

**Trees, windows and translucent tiles bake into the chunk texture**
(`treesInChunkTexture`, `windowsInChunkTexture`, `translucentTilesInChunkTexture`; windows are drawn per frame again
since 2026-10-01: baked glass hid the characters behind it, for no measured gain). A tree
is drawn per frame only while it fades around the player, sways or carries an effect;
16,476 tile definitions carry the translucent flag (damaged fences, railings, crops, wall
decorations). Per-frame draws fall from about 3,000 to about 100. Baked trees are drawn by
their own pass (`treeBakePass`) into every chunk texture their crown reaches, with a depth
that rises with the crown, so JUMBO trees are neither clipped to one chunk nor cut by
upper-floor walls behind them; stock's own chunk-texture tree batch is broken (dark crown
behind the house) and stays unused. When a newly loaded chunk's trees reach into a
neighbour's finished texture, they are drawn on top of it (`treeAppend`) instead of re-baking
the whole texture: while driving, 60 % of all bakes were these neighbour re-bakes.

**Bake and re-bake budgets** (`bakeBudget`, `rebakeBudget`, `rebakeMaxFrames`,
`lightingBudget`, `lightingRebakeMs`). When a new chunk row comes into view stock bakes
dozens of textures in one frame; now at most 8 first bakes and 4 re-bakes per frame, the
previous image shown for at most 3 frames, lighting-only re-bakes held 250 ms, and the
square-lighting refresh limited the same way without ever dropping a dirty flag. Slow frames
were 26 % bakes; p99 13.8 → 8.3 ms on the drive.

**Cutaways** (`cutawayFast`, `cutawayRadius`, `gridStackInterval`,
`cutawayInvalidateChanged`, `cutawayVisitPrefilter`). Clean chunk levels replay their stored,
exact occluder bitmask instead of re-testing every square; wall visits stay within 6 chunks
of the camera; the buildings-in-front scan runs at most every 8 frames while square and
facing are unchanged; a visit re-bakes only chunks where a square's cutaway flag actually
changed (stock re-baked every chunk holding a cut-away wall on every visit, so textures
re-baked every frame while moving through a town); and the visit walks only walls that can
cut (140k of 154k wall walks skipped on the Rosewood route).

**Light info** (`lightInfoOncePerFrame`, `lightInfoChunkGate`,
`occlusionSkipLightingOnly`). The per-square light-info JNI call is made once per square per
frame, a chunk level about to re-bake asks the lighting engine one chunk-level question
before its 64 square questions (the single largest uncapped step, 466 → 501 fps), and the
occluded-squares grid is kept when the only dirty levels are dirty for lighting drift.

**Persistently mapped sprite buffers** (`persistentVbo`, `vboBatchKb`, `vboFastQuads`).
Stock orphans and re-maps a 64 KB vertex buffer per sprite batch; the override allocates
immutable storage once and fences per buffer: render thread busy 63 → 35 %, about 2.7x the
uncapped frame rate at max zoom (184 → 508 fps on the spin). `VBORenderer`, the batcher the
weather and other quad paths use, flushed every 28 quads at its stock 4 KB; it is 1 MB now
with a direct quad writer.

**Game-thread trims** (`weatherMaskIdleSkip`, `lightSwitchCheckFrames`, `soundZoneCache`,
plus a single-lookup Kahlua table read and the occluder masks stored on the chunk). The
weather-mask view scan is skipped when it can add nothing and limited to the player's
building when it can; a light switch reuses its electricity answer for 15 frames; the seven
ambient sound zone parameters reuse their 80x80 zone scan while the listener's square is
unchanged. Together with the budgets and cutaway keys: 199 → 229 fps at the 240 cap on the
spin, 273 → 501 fps uncapped.

## 3. Weather: puddles, rain, lightning, fog

**Puddle cache** (`puddleCache`, `puddleVbo`). Stock re-packs every wet square's puddle
vertices every frame (4.5 ms a frame in a storm) and the render thread streams them through
a 64 KB ring buffer in seven map / draw cycles per level, which is what starved the GPU
(1.3 ms of "puddle" GPU time a frame at 5120x2160). The packed vertices are kept per chunk
level in their own GPU buffer, re-sent only when a square's light changed, the camera crossed
a chunk edge or the level was re-baked; the camera's sub-pixel jiggle is a matrix translation.
Nothing is copied per frame on either thread. Storm drive 232 → 325 fps on its own.

**Early-depth puddle shaders** (`puddleEarlyZ`). The stock puddle shaders write
`gl_FragDepth`, which switches the GPU's early depth test off, so every wet-ground pixel
hidden behind a wall, roof or object still ran the ~200-op HQ shader. The build generates
copies of the game's puddle shaders (`media/shaders/pzopt_puddles_*`) that take the depth
from the vertex instead; same colour math, same picture. 357 → 390 fps.

**Rain splashes** (`rainSplashesFast`). Stock asks the game's random generator once per
idle square of every on-screen chunk level every frame to start a splash; the starts are now
drawn by geometric skipping with a local generator, one draw per splash, same chance and
timing. 2.6 % of a laptop storm frame.

**Rain tiles** (`rainTiles`). The rain particle path walked ~100k quads a frame at
5120x2160 on the game thread, twice; a particle cell is now packed once and drawn once per
screen cell. Storm spin 111 → 188 fps on the desktop, 84 → 106 on a laptop.

**Lightning re-bake spread** (`lightingRebakeBudget`, `lightingRebakeMaxFrames`). Every
lightning strike flash-dirtied all chunk textures and stock re-baked them in one frame: five
50–90 ms stalls per strike, seen as the rain "vanishing" every 6 s. Lighting-only re-bakes
now start 8 per frame and hold at most 30 frames (storm drive p99.9 57 → 12.5 ms, a faint
chunk checkerboard for ~90 ms while a flash ramps). With the puddle and tree changes above the
flashes are ~0.1 ms of the mean frame and ~1 ms of the p99 (storm without lightning 419 fps,
with 392).

**Heavy fog in one pass** (`fogPass`, `fogScalePct`, `fogMaskFrames`, experimental).
Stock `ImprovedFog` draws heavy fog as one screen-wide rectangle per tile row per level, each
96 texture pixels tall while rows are 16 apart: every pixel shaded up to twelve times with
seven noise fetches, a `gl_FragDepth` write (no early depth rejection) and one blend, ~190
draw calls a frame, while the game thread walks every on-screen square per level only to feed
the row iterator. The pass draws all rectangles in one call into a fog buffer of 25 % of the
viewport per axis, reads the scene depth in place (the offscreen buffer's depth is a texture
now, `MultiTextureFBO2`), reduces it per block to its nearest and farthest value with a fog
value for each, and composites every screen pixel from the four nearest fog texels
interpolated by its own depth, so a wire over distant ground keeps the rows stock draws over
it. Per-chunk masks of the squares that take fog (refreshed every 20 frames, staggered) and
a segment cache replace the square walk. Falls back to the stock drawer if the driver refuses
the depth texture or a shader. Known issue: a slight power-line flicker in fog while the
camera moves; `fogPass=false` is stock fog at stock cost.

## 4. Boot: launch to main menu

**FMOD on a boot thread** (`fmodAsync`): the sound system and 12 bank files (1.6 s)
initialise on a thread started at the top of init and joined right before the first sound
script needs them; the sound managers are built at the join, since built earlier their FMOD
parameters silently never register (issue #3). **Boot-time file-pool pump** (`bootPump`,
`bootFileThreads`, `earlyModels`): the async file system only advanced once per rendered
frame, and frames start at the menu, so for 4 s of boot the pool sat idle; a thread pumps it
every 3 ms and model creation moves right after the scripts load. **Lua precompile**
(`luaPrecompile`): every Lua file compiles on a pool during boot and `LuaCompiler` takes the
prototype from that cache, keyed by name and contents. **Animation clip cache**
(`animClipCache`): importing 2,209 `.X` files through jassimp costs 14 to 17 thread-seconds
per boot; the clips are written under `Zomboid/pzopt/anims/` and read from there (2.9
thread-seconds). **Texture pack index** (`packIndex`): version-0 packs were scanned byte by
byte (526 MB) at every boot to find page boundaries; the offsets are kept in
`Zomboid/pzopt/packs/*.idx`. **Linear script parser** (`scriptParserFast`):
`ScriptParser.stripComments` was quadratic (1.56 s), now a single pass with the same output
(unit-tested). **Item parameter switch** (`itemParamSwitch`): `Item.DoParam` tested each
parameter against a chain of 361 `equalsIgnoreCase` calls (0.9 s), now a `switch`. **Logo
screens skipped**: `TISLogoState` is a from-scratch replacement that goes straight to the
main menu.

## 5. Load: Continue to world ready

**File pool sized to the machine** (`fileThreads`, `fileInflight`): stock decodes every
texture, model and depth map on 4 threads with 16 tasks in flight and the loader thread waits
3.5 s for them; now half the cores and 4 tasks per thread. **Depth maps decode concurrently**
(`parallelDepthMaps`): stock ran all 218 tileset loads one at a time under a single lock.
**Loader-thread fixes** (`loaderCpuFixes`), same results as stock: `checkVehiclesZones` was
called 11 times per load over 9,690 zones with an O(n²) duplicate check; `MapCollisionData`
and `IsoMetaCell.getChunk` looked up the lot header per chunk instead of per cell;
`checkBuildingAndRoomIDs` was O(rooms²) per cell. **Animation sets preloaded at boot**
(`preloadAnimSets`): 1.1 s of JAXB moves to a boot thread. **Model shader cache**
(`shaderCache`): `Model.CreateShader` waited one loading-screen step per model on the render
thread; on a laptop whose step is 220 ms the 73 animal models cost 16.5 s (issue #1).
**Mipmaps on byte arrays** (`mipmapArrays`): row-wise `byte[]` mip building, byte-identical
to stock, written for issue #2 (whose crash turned out to be that machine's hardware).
**Wider recalc pool while loading** (`loadWorkers`), **no fade to black** (`noLoadFade`,
350 ms of sleeps) and **no intro wait** (`noIntroWait`: a new game shows click-to-start as
soon as the world is loaded instead of after the 33 s intro; the lines still play behind it).

## 6. Visual fixes found on the way

Each came from a bisect on the bench route or a user report and has a repro rig in the
harness (`docs/archive/2026-09-24/results.md`, `docs/override-edits.md`):

- **Black chunk squares** (2026-09-20): the light-info chunk gate left squares that had never
  been lit out of the bake and the whole 8x8 level came out black; the gate now refreshes any
  square without light info (`harness/blacktiles.py`).
- **Objects inside buildings, doors, windows and corpses blinking out** for 1–3 frames
  (2026-09-20): stock `FBORenderLevels.invalidate()` empties the per-frame square lists and
  every held re-bake drew the previous texture with them empty; the lists are kept across
  invalidations now (`harness/flicker.py`: stock 3.8 vs broken 26 transient px/frame).
- **Curtains** (issue #4, 2026-09-21): curtains draw in the same pass as the window they
  cover and baked curtains sit in front of the glass (`curtainDepthNudgePct`).
- **JUMBO trees clipped or cut by walls** (issue #5, 2026-09-21): the tree pass above.
- **Carport roof toggling every frame** (2026-09-21): an orphan structure's hide/show is
  debounced 8 frames (`roofHideDebounceFrames`); the driver of the per-frame toggle with
  zombies around is still open.
- **Windows 0.5 s micro stutter** (2026-09-21): the overlay's JMX CPU-load sampling on the
  game thread; now opt-in and on a daemon thread.

## 7. Later passes

Each has its own findings document with every run, the techniques that did not help and the rigs:

- **Zombies on every core** (2026-09-22, [`plan-zombie-multithread.md`](../plan-zombie-multithread.md)): bone math,
  action-state evaluation, skinning palettes, shadows and the lighting reads of the zombie update run on a frame worker
  pool (`frameThreads`); the component lookups behind every state query are memoised. Louisville 46 → 58 fps that day.
- **Player line of sight** (2026-09-22): `IsoPlayer.updateLOS` walked a stack of every remembered object once per
  spotted object per frame; an identity set beside it (`playerLosFast`, `zombieSpotFast`) took the player from 16 % of
  the game thread to 3.7 % in a horde.
- **Characters draw** (2026-09-22, [`findings-characters-draw-2026-09-22.md`](../archive/2026-09-24/findings-characters-draw-2026-09-22.md)):
  the per-zombie model setup on a worker pool (`charDrawPrep`) and a flat draw for atlas zombies (`zombieAtlasFast`):
  13 → under 5 % of the game thread.
- **World sound** (2026-09-22): a ringing house alarm walked squares quadratic in its radius every frame;
  `worldSoundFast` clamps and memoises the walk (4.4 ms → 0.007 ms a call).
- **Camera zoom** (2026-09-22): textures kept while a chunk is inside the widest zoom's rect and the bakes a zoom reveals
  planned over frames (`zoomRetain`), the zoom eased in time (`zoomEase`): a full wheel spin 375 → 25 ms worst frame.
- **Town drive** (2026-09-24, [`findings-town-drive-2026-09-24.md`](../findings-town-drive-2026-09-24.md)): one
  prioritised per-frame bake budget (`bakeScheduler`), 3 mip levels a bake, chunk textures made ahead, even present
  pacing.
- **Car smoothness** (2026-09-26, [`findings-car-jitter-2026-09-26.md`](../findings-car-jitter-2026-09-26.md)): the
  frame draws vehicles and the camera between the last two physics steps (`vehicleSmooth=interp`): world judder
  5.1 → 0.7 px.
- **Texture compression on the GPU** (2026-09-25, [`findings-texcompress-2026-09-25.md`](../findings-texcompress-2026-09-25.md)):
  with `textureCompression` on, Mesa compressed every texture on the render thread for ~26 s a boot; the GPU now builds
  the mips and encodes BC3 (`texCompress=auto`).
- **Build 42.21 trees while driving** (2026-10-03, [`findings-4221-drive-regression-2026-10-03.md`](../findings-4221-drive-regression-2026-10-03.md)):
  42.21 made every tree near the car see-through, re-baking its chunk up to four times; `treeRebakeLazy`,
  `treeCutawayReach` and `driveTreeCutaway=false` brought the drive back to the 42.20 level.
- **Louisville 120** (2026-10-05/06, [`findings-louisville-120-2026-10-05.md`](../findings-louisville-120-2026-10-05.md) and
  the three after it): entity updates, zombie post-update and the translucent tile pass recorded on the frame workers,
  zombie simulation level of detail by distance; most are experimental (off by default) and give the 222 fps in the
  [benchmarks](benchmarks.md#louisville-horde-on-four-devices).
- **Garbage collector and heap** (2026-10-05, [`findings-gc-heap-2026-10-05.md`](../findings-gc-heap-2026-10-05.md)):
  the launcher's 3 GB runs 95 % full in a horde; `gcHeap=auto` writes 4 GB (8 GB from 30 mods) on G1.
- **Windows short waits** (2026-10-11, [`docs/results.md`](../results.md)): the frame workers' 20 µs joins became 1 ms
  on Windows; `shortWait` yields there.
- **Low-end mode** (2026-10-07, [`findings-low-end-mode-2026-10-07.md`](../findings-low-end-mode-2026-10-07.md)):
  render distance as the one lever on four cores, and the presets built from it.

The Visuals (enhancements) each have a findings document too: ambient occlusion, per-pixel lighting, HDR, sun / moon /
cloud shadows, god rays, reflections, mirrors, car glass, puddle ripples and more ([`docs/`](..), `findings-*.md`).

## Measured and not adopted

GraalVM 25 (7–9 %
behind Zulu/C2 uncapped); Mesa Zink instead of NVIDIA GL (blocks 1.8 ms per frame in swap);
a 256 MB texture upload buffer (a 5 s frame); native Wayland (a wash at the 240 cap);
weather FX buffers at half size (`weatherFxScalePct`, the pass costs draw calls, not pixels);
`lightingRebakeMs=1000` and `bakeBudget=3|4` (no change); a hot save split over nine streamer
updates (`hotsaveStaged`, off: the meta-grid files could disagree); `uiRenderFPS=60` (within
noise); Java 27 and generational Shenandoah (issue #11, 2026-10-15: same fps, Shenandoah no RAM saving,
[`findings-java27-shenandoah-2026-10-15.md`](../findings-java27-shenandoah-2026-10-15.md)). The collector itself was
adopted: since 2026-09-23 the install switches the launcher to G1 (`gcMode`), since 2026-10-05 with a 4 GB heap (`gcHeap`), after the stock
ZGC stalled four-core machines for seconds and its 3 GB heap ran 95 % full in a horde.

---

## How the install works without touching the jar

The game's launcher config `ProjectZomboid64.json` ships with
`"classpath": [".", "projectzomboid.jar"]`. The install directory comes before the jar, so a
loose `.class` file there **shadows** the same class inside the jar. The macOS bundle has no
JSON; its `JavaAppLauncher` builds `-Djava.class.path=<Contents/Java>/` and appends the jars
after it, which is the same order. The overrides are copied
in as loose files and removed by deleting them; the jar's checksum never changes. This is the
"manual class replacement" method described on the
[PZ wiki's Java page](https://pzwiki.net/wiki/Java).

Shadowed classes: 139 game classes (names below without the leading `zombie.`) plus 1 written from scratch (`zombie.gameStates.TISLogoState`). Every one is listed with what was changed in it in [`docs/override-edits.md`](../override-edits.md).

<details><summary>The 139 classes</summary>

| Area | Classes |
|---|---|
| Chunks, streaming and the world (`zombie.iso`) (23) | `iso.ChunkSaveWorker`, `iso.FishSchoolManager`, `iso.IsoCamera`, `iso.IsoChunk`, `iso.IsoChunkMap`, `iso.IsoGridSquare`, `iso.IsoMetaCell`, `iso.IsoMetaGrid`, `iso.IsoMovingObject`, `iso.IsoObject`, `iso.IsoPuddles`, `iso.IsoWorld`, `iso.LightingJNI`, `iso.PlayerCamera`, `iso.PuddlesShader`, `iso.RoomDef`, `iso.WaterShader`, `iso.WorldStreamer`, `iso.objects.IsoLightSwitch`, `iso.sprite.IsoCursor`, `iso.sprite.IsoSprite`, `iso.worldgen.WorldGenUtils`, `iso.zones.Zone` |
| Chunk textures (`zombie.iso.fboRenderChunk`) (4) | `iso.fboRenderChunk.FBORenderCell`, `iso.fboRenderChunk.FBORenderCutaways`, `iso.fboRenderChunk.FBORenderShadows`, `iso.fboRenderChunk.FBORenderTrees` |
| Weather (`zombie.iso.weather`) (6) | `iso.weather.WeatherShader`, `iso.weather.fog.ImprovedFog`, `iso.weather.fog.ImprovedFogDrawer`, `iso.weather.fx.ParticleRectangle`, `iso.weather.fx.WeatherFxMask`, `iso.weather.fx.WeatherParticleDrawer` |
| Rendering, textures and shaders (`zombie.core`) (19) | `core.PerformanceSettings`, `core.SpriteRenderer`, `core.VBO.GLVertexBufferObject`, `core.opengl.IOpenGLState`, `core.opengl.RenderThread`, `core.opengl.ShaderUniformSetter`, `core.opengl.ShaderUnit`, `core.opengl.VBORenderer`, `core.physics.WorldSimulation`, `core.properties.PropertyContainer`, `core.sprite.SpriteRendererStates`, `core.textures.ImageData`, `core.textures.MultiTextureFBO2`, `core.textures.PNGDecoder`, `core.textures.TextureCombiner`, `core.textures.TextureDraw`, `core.textures.TextureFBO`, `core.textures.TextureID`, `core.textures.TextureIDAssetManager` |
| Animation and models (`zombie.core.skinnedmodel`) (13) | `core.skinnedmodel.DeadBodyAtlas`, `core.skinnedmodel.ModelManager`, `core.skinnedmodel.advancedanimation.AnimatedModel`, `core.skinnedmodel.advancedanimation.AnimationSet`, `core.skinnedmodel.advancedanimation.AnimationVariableHandlePool`, `core.skinnedmodel.animation.AnimationMultiTrack`, `core.skinnedmodel.animation.AnimationPlayer`, `core.skinnedmodel.animation.AnimationTrack`, `core.skinnedmodel.model.AnimationAssetManager`, `core.skinnedmodel.model.IsoObjectModelDrawer`, `core.skinnedmodel.model.Model`, `core.skinnedmodel.model.ModelInstance`, `core.skinnedmodel.model.ModelSlotRenderData` |
| Characters, zombies, AI and pathfinding (18) | `ai.State`, `ai.StateMachine`, `ai.ZombieGroupManager`, `ai.states.WalkTowardState`, `characters.AttachedItems.AttachedItems`, `characters.IsoGameCharacter`, `characters.IsoPlayer`, `characters.IsoZombie`, `characters.Stats`, `characters.SurvivorDesc`, `characters.action.ActionContext`, `characters.action.conditions.CharacterVariableCondition`, `characters.animals.IsoAnimal`, `characters.ecs.ECSComponent`, `characters.ecs.ECSEntity`, `pathfind.PathFindBehavior2`, `pathfind.PolygonalMap2`, `popman.ZombiePopulationManager` |
| Everything else (56) | `fmod.fmod.FMODSoundEmitter`, `org.lwjglx.input.Mouse`, `org.lwjglx.opengl.Display`, `se.krka.kahlua.integration.LuaCaller`, `se.krka.kahlua.integration.expose.caller.MethodCaller`, `se.krka.kahlua.j2se.KahluaTableImpl`, `se.krka.kahlua.luaj.compiler.LuaCompiler`, `AmbientStreamManager`, `FileGuidTable`, `FliesSound`, `GameProfiler`, `GameTime`, `GameWindow`, `IndieGL`, `LoadGridsquarePerformanceWorkaround`, `Lua.Event`, `Lua.LuaEventManager`, `MapCollisionData`, `MovingObjectUpdateScheduler`, `MovingObjectUpdateSchedulerUpdateBucket`, `VirtualZombieManager`, `WorldSoundManager`, `audio.FMODAmbientWalls`, `audio.ObjectAmbientEmitters`, `audio.parameters.ParameterShoeType`, `audio.parameters.ParameterZone`, `buildingRooms.BuildingRoomsEditor`, `fileSystem.FileSystemImpl`, `fileSystem.TexturePackDevice`, `gameStates.GameLoadingState`, `gameStates.MainScreenState`, `input.ControllerStateCache`, `input.GameKeyboard`, `input.KeyboardState`, `input.KeyboardStateCache`, `input.Mouse`, `input.MouseState`, `input.MouseStateCache`, `network.NetChecksum`, `network.statistics.data.PerformanceStatistic`, `randomizedWorld.randomizedBuilding.RBTrashed`, `savefile.SavefileThumbnail`, `scripting.ScriptParser`, `scripting.objects.Item`, `statistics.StatisticsManager`, `tileDepth.TileDepthTexture`, `tileDepth.TileDepthTextures`, `ui.UIElement`, `ui.UIManager`, `util.FrameDelay`, `viewCone.ChunkRenderShader`, `vispoly.VisibilityPolygon2`, `worldMap.WorldMapVisited`, `worldMap.streets.StreetRenderData`, `worldMap.streets.WorldMapStreet`, `worldMap.streets.WorldMapStreets` |

</details>

The edited sources live under `src/overrides/` with every change marked `// pzopt:` and
described in prose in [`docs/override-edits.md`](../override-edits.md); the new helper code
is the `pzopt` package under `src/pzopt/`, the PZ OPTIMIZATION screen and frame-cap Lua under `src/lua/`.

Safety rails:

- **Build guard.** The classes record the game revision they were compiled against and the
  sha256 of every stock class they shadow, and disable themselves, with one log line, when
  the installed game differs. The installers refuse a mismatched zip.
- **Signature check.** The build fails if any non-private member of a shadowed class is
  missing or changed, so other game classes always link.
- **Kill switches.** Every optimization is a key, and `enabled=false` is the stock game.
- **Game-thread-only code stays there.** Pathfinding registration is never reached from a
  worker; dev builds assert it.
- **Save format and network payloads are untouched.**
- **All files the mod writes** (caches, settings, traces) stay under `Zomboid/pzopt/`. The
  game directory only receives the files listed in `pzopt-files.txt` / `pzopt-installed.txt`.
