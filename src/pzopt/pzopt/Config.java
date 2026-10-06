package pzopt;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * Runtime settings for the overrides, read once from pzopt.properties in the
 * game install directory (next to projectzomboid.jar, so scripts/pzopt.sh
 * status can show it) with -Dpzopt.<key> system properties overriding. Below both sits the
 * player's Zomboid/pzopt/options.ini, written by the Options > Optimizations tab
 * (pzopt.UserOptions); every key is exposed there (booleans as tick boxes, numeric/string settings as combos) and
 * takes effect on the next launch except Profiler, Enhancements and RawMouse sensitivity/acceleration settings, which apply
 * at once ({@link #reloadLive}).
 *
 * Keys:
 *   enabled     true/false   master switch: false makes every override take its stock path, exactly as a build
 *                            mismatch does (Overrides.enabled() is false); the other keys are then ignored. The
 *                            "Disable all (stock)" / "Enable all" buttons of the Optimizations tab set it (default true).
 *                            The overlay and its profiler (the Profiler tab's keys) keep working with it off
 *                            (Overlay needs only Overrides.buildMatches(), 2026-09-24)
 *   enhancementsEnabled true/false  the Enhancements tab's master switch (2026-09-28): false reads every enhancement's
 *                            own switch (upscaler, spriteFilter, hdr, hdrAuto, ambientOcclusion, sunShadows, reflections,
 *                            darknessFloorPct, memoryTint, colorGrading, pixelLight, godRays, foliageSway, relief) as off unless
 *                            -D / pzopt.properties pins it; the tab's choices stay saved. Live, except what hdr / hdrAuto /
 *                            pixelLight / reflections pick at start-up (default true)
 *   profilerEnabled true/false  the Profiler tab's master switch: false reads overlaySampling, overlay and overlayLog as
 *                            false (no overlay, samplers or frame log; harness runs still measure). Live (default true)
 *   consoleLog  all|warnings|errors|off  which [pzopt] lines reach console.txt / the debug console (pzopt.Log): all, only
 *                            warnings and errors, only errors, or none, so other mods' output stays readable (2026-10-01,
 *                            a player's request). Not tied to profilerEnabled. Live; harness runs pin it to all (default all)
 *   parallel    true/false   kill switch: false forces the stock single-threaded pass (default true)
 *   workers     int          recalc pool width; clamped to [1, availableProcessors - 1] (default: min(4, cores - 1), 1 on
 *                            4 cores or fewer: there the three workers took the game thread's core, Dell i5-6300HQ 2026-09-21)
 *   instrument  true/false   record per-chunk timings and frame times to pzopt-*.out (default false)
 *   wake        true/false   wake the streamer thread on enqueue instead of the stock 140 ms polls (default true)
 *   chunkGridWidth int|auto  the player's chunk grid (loaded / simulated / rendered chunks around the player) is this many
 *                            chunks wide, 8 tiles each, instead of the stock size from the screen resolution (19 at 1080p
 *                            and above, 13 at 720p); odd, clamped to 5..41. Smaller = less world simulated, lit and baked
 *                            around the player (CPU-bound setups, heavy mod lists) but the world ends nearer the screen
 *                            edge at wide zoom; larger = more of everything. "auto" = wide enough to fill the screen at the
 *                            widest zoom, never below stock (25 at 5120x2160, 21 at 3840x2160, stock 19 at 1080p;
 *                            pzopt.ChunkGrid). 0 = stock (default 0; IsoChunkMap.CalcChunkWidth)
 *   chunkGridFollowView true/false  with a chunkGridWidth wider than stock, the grid centre follows the ground the camera
 *                            looks at on an upper floor (3 tiles north and west per level), so the screen corners stay
 *                            filled upstairs as on the ground floor; the player stays at least as deep inside the grid as
 *                            in a stock one. Single player only (default true; pzopt.ChunkGrid.heightShiftTiles)
 *   dev         true/false   development assertions, e.g. game-thread-only code reached from a worker (default false)
 *   luaChecksumExempt true/false  the pzopt Lua files (media/lua/{shared,client}/pzopt/) are left out of the multiplayer
 *                            Lua checksum a client sends to the server, like SandboxVars.lua is in stock: a server without
 *                            them otherwise refuses the join with "File doesn't exist on the server" (default true; not
 *                            tied to `enabled`, the files are on disk either way)
 *   updateCheck true/false   the main menu asks the GitHub releases once per boot whether a newer build for this game
 *                            revision exists and offers an "PZ Optimization update" menu item that downloads and
 *                            installs it (pzopt.Updater; default true; never in harness runs)
 *   updatePrefetch true/false  once a release is offered, the files that differ from the installed ones are fetched in
 *                            the background (range requests of the changed zip entries; default true), so Update now
 *                            only writes them; updatePrefetchMaxKb (16384) caps that background fetch
 *   updateFromWorkshop true/false  the update check first looks at the Steam Workshop item's copy on this disk (the
 *                            same files as the release zip) and offers it without a download when it is newer than
 *                            this build; GitHub is still asked after it (issue #16; default true; needs updateCheck)
 *   devUpdateOffer true/false  dev: offer the newest release for this revision whatever this build is, to see the
 *                            menu item, the dialog and the install (default false)
 *   devUpdateDrive true/false  dev rig: the menu opens the update dialog and presses Update now, then Restart game; the
 *                            restarted process logs the time since the press and quits (harness/mac-update-e2e.sh;
 *                            also runs the check in a harness run; default false)
 *   translucentCache true/false  reuse prepared translucent render lists (default false)
 *   hotsaveIntervalSec int   (default 30) minimum seconds between the "hot saves" of the ancillary systems (meta grid, game time,
 *                            world map, entities) that ChunkSaveWorker runs on the game thread whenever its chunk
 *                            save queue drains; 0 = stock (every drain, i.e. every chunk row while moving)
 *   persistentVbo   true/false  sprite ring buffers use persistently mapped buffer storage instead of an orphaning
 *                            glBufferData + glMapBufferRange per 64 KB batch (default true)
 *   treesInChunkTexture true/false  static trees bake into the chunk textures; only translucent/fading trees are
 *   treeBakeMaxChunksPerSec int  above this many chunk hand-offs per second (pzopt.ChunkRate: walking ~9, 60 km/h ~32,
 *                            120 km/h ~72) trees are not baked into new chunk textures but drawn per frame, as with
 *                            treesInChunkTexture=false; textures already baked keep their trees until they re-bake.
 *                            A texture that lives a second or two while driving costs more to bake its trees into
 *                            (own texture plus neighbour copies) than drawing them per frame for that long: Dell
 *                            i5-6300HQ 2026-09-21, 120 km/h 28.6 -> 42.7 fps; walking is the other way round
 *                            (default 0 = always bake; the low-end profile sets 24)
 *   treeBakeDirect true/false  bake trees through IsoTree.render without a FBORenderTrees batch (the batch drops JUMBO trees)
 *   treeBakePass true/false   baked trees are drawn by the pzopt tree pass (pzopt.TreeBake): into every chunk-level texture
 *                            the sprite overlaps, last in the texture, with a depth that gets nearer with height like the
 *                            wall depth textures (issue #5: crowns clipped at the texture border, strips cut by upper
 *                            walls); false = the MinusFloor loop of the sprite path (default true)
 *   windSpriteSway true/false with the game's own "Wind sprite effects" display option on, plants stay baked in the chunk
 *                            textures and foliage sway (pzopt.Sway) moves them, instead of the option's per-frame draw of
 *                            every tree, bush and grass tuft; the option reads off inside the world render only
 *                            (pzopt.Sway.windHandoffBegin), menus and options.ini keep the player's choice. Not on macOS
 *                            (no sway there). Live (default true, issue #41)
 *   devRedrawFrame N          dev: force a full redraw of on-screen chunk levels N frames after the first render
 *   devCutawayLog true/false  dev: log every change of the buildings-to-collapse list and every orphan-structure
 *                            (carport / pergola roof) hide-show flip with the frame number (first 400 lines)
 *                            drawn every frame (default true; false = stock: every tree every frame)
 *   windowsInChunkTexture true/false  windows and glass doors bake like walls instead of being drawn every frame
 *                            (default false since 2026-10-01: the baked glass hides characters behind it)
 *   translucentTilesInChunkTexture true/false  tiles flagged Translucent in tileGeometry.txt (fences, railings, wall
 *                            decorations, overlays, crops: 16k definitions) bake instead of being drawn every frame
 *                            (default true)
 *   glassTilesPerFrame true/false  with translucentTilesInChunkTexture, Translucent tiles whose definition names glass
 *                            (display cases, glass-door fridges, balustrades, partitions: pzopt.GlassTiles) stay per frame
 *                            as in stock, so characters behind them show through the glass (default true; 2026-10-01)
 *   floorDecalsPerFrame true/false  with translucentTilesInChunkTexture, Translucent tiles that lie on the floor (manhole
 *                            covers, drains: the sprite fits the floor diamond, pzopt.FloorDecals) stay per frame as in
 *                            stock, drawn over the puddles; baked, a cover z-fought with the puddle over it (default true;
 *                            2026-10-05)
 *   translucentLightsPerFrame true/false  with translucentTilesInChunkTexture, Translucent light fixtures that have a lit
 *                            sprite (HasLightOnSprite: ceiling lights, lamps, lamp posts) stay per frame as in stock
 *                            (default true). Baked, the lit sprite is drawn by the animated-attachments pass pulled
 *                            towards the camera to win over its own baked lamp; a ceiling fixture's top lies in the
 *                            plane of the floor above, so the Fossoil canopy's lit tubes showed through its roof and
 *                            flickered as the camera zoomed (2026-09-28)
 *   curtainDepthNudgePct int     a curtain hanging in front of a window on its own square draws this many hundredths of
 *                            a tile nearer the camera than its tile geometry says (default 5; 0 = off). The north
 *                            window glass sits 0.017 tile in front of the north curtain in tileGeometry.txt; stock
 *                            draws both per frame in object order with depth writes off, so it never notices, but
 *                            once either bakes the depth test lets the glass through the closed curtain (issue #4)
 *   bakeBudget      int          chunk-level textures (re)baked per frame, the rest deferred to the next frame (default 8; 0 = stock, unlimited)
 *   lightingBudget  int          chunks whose square light info is refreshed per frame, the rest continue next frame (default 8; 0 = stock)
 *   lightingRebakeMs int         a chunk texture dirtied only by a lighting change is not re-baked more often than this (0 = stock)
 *   rebakeBudget    int          re-bakes per frame of on-screen chunk textures dirtied only by lighting, redraw or cutaways; past it the previous image stays for up to rebakeMaxFrames frames (default 4; 0 = every re-bake lands the same frame)
 *   rebakeMaxFrames int          longest hold for such a re-bake (default 3)
 *   lightingRebakeBudget int     lighting-only re-bakes (flag 32 alone: daylight drift, a lightning flash) started per frame
 *                            (default 8) ...
 *   lightingRebakeMaxFrames int  ... and how long one may stay stale (default 30). A lightning strike dirties every
 *                            on-screen texture; with the 3-frame cap of rebakeMaxFrames they all landed in one
 *                            50-90 ms frame, five times per strike (flash on, off, and every 250 ms of the ramp)
 *   lightingStrongDelta int      a square whose light moved by this much (0-255, largest channel, summed since its level was last
 *                            baked) marks the level strong: it skips lightingRebakeMs and the spread and re-bakes at once, like
 *                            stock (default 6; a moving torch changes squares by tens a frame, sky drift by 1 a tick)
 *   vehicleCull     true/false   a zombie's "is a vehicle between me and the player" test skips vehicles whose bounding circle
 *                            misses the segment before the exact box test (default true; 6 % of the game thread in downtown)
 *   animBonesParallel true/false the zombies' animation bone math (keyframe blend, twist bones, model and skin matrices,
 *                            9 % of the game thread on the Louisville horde) runs on worker threads after the object
 *                            postupdate loop instead of inline (default true; pzopt.AnimBatch)
 *   frameThreads    int          worker threads of the per-frame zombie batches (bone math, transition evaluation), clamped
 *                            to cores - 1 (default 8, or animBonesThreads when set; the game thread joins in)
 *   actionEvalParallel true/false the zombies' action-context transitions are evaluated on the frame workers after the
 *                            postupdate loop, applied on the game thread in order (default true; pzopt.ActionEval)
 *   devActionEvalCheck true/false dev: re-evaluate on the game thread at apply time and count disagreements
 *   devActionEvalUnitMultiplier true/false dev: the pre-fix deferred postupdate at perObjectMultiplier 1 (off-screen thump bursts; ThumpRig A/B)
 *   zombieCullSortFast true/false the per-frame zombie relevance sort computes each score once (default true; same order)
 *   lightingReadParallel true/false the lighting queue's pre-pass drain reads its chunk levels on the frame workers
 *                            (default true; pzopt.LightingBatch)
 *   devLightingReadCheck true/false dev: re-read a sample of squares after a parallel batch and count mismatches
 *   skinTransformsPrecompute true/false the bone worker also computes the skin-transform sets of the models drawn last
 *                            frame, so the render phase finds them ready (default true; needs animBonesParallel)
 *   skinPalettePrecompute true/false the worker stores each skin-transform set as the shader palette too; the draw data
 *                            copies it in one bulk put instead of sixteen per matrix (default true)
 *   shadowPrep      true/false   a zombie's shadow ellipse computed on the bone worker instead of in renderShadow (default true)
 *   boneIndexCache  true/false   bone-name -> index answers cached per skinning data on the animation player (default true)
 *   ecsLookupFast   true/false   the entity-component lookups behind getStateMachine / getActionContext / getVariable are a
 *                            memoised class walk, a lean map probe and a cached field on IsoZombie (default true)
 *   actionConditionFast true/false action-context transition conditions read boolean / int animation variables through
 *                            their typed getters instead of print-and-parse (default true; same outcomes)
 *   charDrawPrep    true/false   the render phase's characters draw: the on-screen zombies' model draw data (model lights,
 *                            the render data of every sub-model, the depth / lights / matrix palette init) is built on the
 *                            game's slot-init executor right after the players are drawn, joined before the sequential
 *                            pass enqueues it in the stock order (pzopt.CharDraw; default true)
 *   zombieAtlasFast true/false   a culled zombie's atlas-sprite draw through a flat copy of its render chain instead of
 *                            the virtual IsoZombie.render -> IsoGameCharacter.render chain (same tests, same writes,
 *                            same sprite; default true)
 *   charDrawThreads int          threads of the pre-pass pool (default 14, clamped to cores - 2)
 *   devSimChecksum  true/false   dev: per-frame hash of every zombie's state after postupdate in Zomboid/pzopt-sim.out
 *   lightingStrongBudget int     how many strong levels re-bake at once in one frame (default 8); the rest are held like
 *                            drift (they still re-bake within lightingRebakeMs / the spread). A beam touches a few levels a
 *                            frame; turning moves the out-of-sight fade across every exterior square, ~every level. 0 = no cap
 *   lightingStrongFrameMs int    a game-thread frame step longer than this halves the strong re-bake budget for the next
 *                            frame (down to 1; grows back by one per frame under 3/4 of it): a slow frame moves the
 *                            out-of-sight fade further, marks more levels strong and bakes more, which is the loop that
 *                            held downtown Louisville at twice the GPU time per frame (default 0 = fixed budget: at 20 it held the strong
 *                            levels of a scene that is steadily slow, 30-45 fps downtown, and the held squares are
 *                            re-marked every pass — stale light instead of a broken loop; keep it for A/Bs)
 *   lightingGlobalDeltaPct int   a per-frame move of the player's global light (colour mods, ambient, night, sky level) past this
 *                            percentage is a global event (lightning flash, fast-forwarded dusk): strong levels keep the spread
 *                            for lightingRebakeMaxFrames frames (default 2; a torch or the vision cone never moves it)
 *   lightingFlush   true/false   chunks the lightingBudget queue still holds are refreshed just before the next lighting pass
 *                            rewrites their dirty bits (default true; false loses them, chunk-sized stale light at 120 km/h)
 *   lightSwitchCheckFrames int   frames a light switch reuses its "has electricity around" answer (default 15; 0 = every frame, stock)
 *   cutawayFast     true/false   replay stored occluder masks for clean chunk levels instead of re-testing every square (default true)
 *   cutawayRadius   int          cutaway wall visits only consider chunks within this many chunks of the camera (0 = all on screen)
 *   gridStackInterval int        frames between buildings-in-front scans while the camera square and facing are unchanged (0 = every frame)
 *   roofHideDebounceFrames int   a carport / pergola roof (orphan structure) is hidden or shown only after the decision has held
 *                            for this many consecutive frames (default 8; 0 = stock, the roof can flip every frame)
 *   weatherMaskIdleSkip true/false skip the per-frame weather-mask tile scan and mask FBO draw while the player is outdoors and no cloud/fog/rain layer is active (default true)
 *
 * Game load (docs/plan-game-load.md):
 *   fileThreads     int          worker threads of the game's async file system (texture decode, model and animation
 *                            import, depth maps); stock is 2 on <= 4 cores, else 4 (default: max(4, cores / 2): with cores - 2
 *                            the game's own 8 meta-grid loader threads ran 2.5x slower and the load was 0.65 s longer, load-s3 vs load-s3f8)
 *   fileInflight    int          file tasks handed to those threads at once (stock 16; default 4 * fileThreads)
 *   fileInflightLoad int         the same from boot until the world is entered (default 128, at least fileInflight): the
 *                            game takes finished tasks once a frame, so 16 in flight drained the boot backlog at 16 a
 *                            frame and the Continue waited on it (Mac: assetLock2 wait 3.4 -> 2.0 s); in play the file
 *                            system's own priority order matters again (the pool's queue is first come, first served)
 *   pngPaethFast    true/false   PNG decode: the Paeth row filter of 4-byte pixels (every texture-pack page) runs as one
 *                            interleaved loop with the neighbours in locals (pzopt.PngFilters), byte-identical, the filter
 *                            40 % faster (it was 69 % of a page decode); palette images (every tile depth map) also copy
 *                            to RGBA with one table lookup a pixel and a bulk put a line, 3.2x faster (default true)
 *   depthMapFast    true/false   tile depth maps: each tile's pixels read with one bulk get per row instead of two
 *                            bounds-checked gets per pixel (TileDepthTexture override, pzopt.PngFilters.depthTile), the
 *                            same values (default true)
 *   zoneEdgePrefilter true/false map zones on Continue: a geometry zone's chunk tests skip the polygon edges whose bounding
 *                            box is more than a tile from the chunk side before the stock arithmetic (pzopt.ZoneGeom, Zone
 *                            override), the same answers; they were 20 % of the map-zones step (default true)
 *   lazyOptionsScreen true/false the options screen (main menu, in-game menu) is built when first opened; while hidden only
 *                            its key bindings are loaded (pzopt_optimizations_options.lua) (default true)
 *   previewClips    true/false   the options tabs' preview panel plays the before / after clips of the setting under the
 *                            mouse; the "Before / after clips" tick box in each tab's header, saved at once (default false)
 *   electricityLevelRange true/false AmbientStreamManager.checkHaveElectricity (world entry, power changes) walks only the
 *                            levels some loaded chunk has instead of all 64, same squares in the same order (default true)
 *   fileThreadsWait int          file pool width while the loader thread only waits for the file tasks (assetLock2, the
 *                            main thread idle too); back to fileThreads afterwards (default: cores)
 *   textureBufferMb int          decoded-texture bytes that may wait for the render thread before the decoders pause
 *                            (stock 50; default 50: 256 MB let ~200 MB of uploads pile up on the render thread and
 *                            gave a 5 s frame a few seconds into the world, run load-s1-155507)
 *   parallelDepthMaps true/false  the 218 depth-map tilesets decode concurrently instead of one at a time under
 *                            one lock (default true)
 *   loaderCpuFixes  true/false   algorithmic fixes on the loader thread with identical results: MapCollisionData.init
 *                            resolves lot headers once per cell, IsoMetaGrid.checkVehiclesZones dedupes with a hash
 *                            set, IsoMetaCell.getChunk memoizes the lot header per cell, BuildingRoomsEditor
 *                            .checkBuildingAndRoomIDs indexes rooms once per cell (default true)
 *   scriptParserFast true/false  ScriptParser.stripComments in one linear pass and parseTokens without re-substringing
 *                            (identical output, tests/pzopt/ScriptTextTest; the stock passes cost 1.8 s at boot) (default true)
 *   earlyModels     true/false   ModelManager.create (models + the animation queue) runs right after the scripts load
 *                            instead of after the Lua load, giving the boot pump ~2 s more to import animations (default true)
 *   luaPrecompile   true/false   every Lua file (game, mods, map objects.lua) compiles on a pool during boot; the
 *                            game's LuaCompiler.loadis takes the prototype from that cache (default true)
 *   preloadAnimSets true/false   the player/zombie animation-set XML trees parse on a boot thread (1.1 s of the
 *                            loader thread otherwise) (default true)
 *   tileDefPreload  true/false   the tile definitions (~100k sprites and their properties, 0.6 s of the loader thread)
 *                            are built on a boot thread into a private sprite manager right after the tile packs
 *                            register; the first world load binds their textures and moves them in instead of parsing
 *                            the .tiles files again. Used once per boot, only when the mod list and language are
 *                            unchanged (pzopt.TileDefPreload) (default true)
 *   skipIdChecks    true/false   BuildingRoomsEditor.checkBuildingAndRoomIDs, a walk over every building and room of the
 *                            map that only logs ids that disagree with their position (never changes anything), runs in
 *                            debug mode only; stock runs it six times per world load, 0.37 s (default true)
 *   voronoiFast     true/false   the zombie-density voronoi noise of every map cell (IsoMetaGrid's loader threads, 1.2 s of
 *                            every world load) generates each sector's points once per cell and keeps the two smallest
 *                            distances instead of re-seeding and sorting boxed doubles per sample; identical values
 *                            (pzopt.ZombieNoise, tests/pzopt/ZombieNoiseTest) (default true)
 *   earlyTilePacks  true/false   the tile texture packs register and the 218 tile depth-map loads are queued right after the
 *                            UI packs and the script load instead of after the boot Lua load, so their decode (12-14 +
 *                            ~4 thread-s) runs during boot; with a warm load the loader used to wait 0.4-0.6 s for the
 *                            depth maps at the end of the load (default true)
 *   aotCache        true/false   JDK AOT cache (classes + method profiles of a whole session) so launches start warm: the
 *                            overrides run from pzopt/aot/pzopt.jar and the launcher JSON records the cache on one launch
 *                            and uses it from the next (pzopt.AotCache; the JSON is backed up once as
 *                            ProjectZomboid64.json.pzopt-backup). Continue -> world 2.64 -> 2.02 s, launch -> menu -0.6 s
 *                            on 2026-09-22. false puts the launcher back to the loose classes on the next boot (default true, the
 *                            maintainer's decision of 2026-09-22; harness runs leave it inert unless devAotCacheHarness)
 *   animClipCache   true/false   imported animation clips are written to <cache>/pzopt/anims/ after a stock import and
 *                            read from there on later boots instead of parsing the .X files with jassimp (default true)
 *   packIndex       true/false   version-0 texture packs keep their page end offsets in <cache>/pzopt/packs/*.idx so the
 *                            reader seeks instead of scanning 526 MB byte by byte at boot (default true)
 *   itemParamSwitch true/false   Item.DoParam dispatches through a switch on the lower-cased key instead of a chain of
 *                            361 equalsIgnoreCase tests per parameter (0.9 s of boot) (default true)
 *   dumpItems       true/false   after the scripts load, write every item script's fields to Zomboid/pzopt-items.out
 *                            (reflection) so two runs can be diffed (default false)
 *   bootPump        true/false   a thread pumps the async file system every 3 ms during GameWindow.init, so the queued
 *                            texture pages and animations decode during boot instead of after the main menu appears
 *                            (default true)
 *   bootFileThreads int          file pool width while the boot pump runs (default cores - 6: with cores - 2 the 16 cores
 *                            saturated and the main thread's Lua load ran 1.6x slower); shrinks to fileThreads at the load
 *   noLoadFade      true/false   GameLoadingState.exit does not fade the loading screen to black (350 ms of sleeps) before
 *                            the world's own 2 s fade-in, and MainScreenState.exit does not fade the main menu to black
 *                            (250 ms of renders and 33 ms sleeps) after Continue (default true)
 *   noClickToStart  true/false   the loading screen goes into the world the moment loading is done instead of waiting for
 *                            "click to start" / A (new games still honour noIntroWait) (default true)
 *   noLoadingScreen true/false   single player: no fade from black into the world (the fader loop skipped), and with a cached
 *                            view (resumeShot) no loading screen either; the world then appears from the player outwards
 *                            (pzopt.NoLoadingScreen; errors, conversions and multiplayer keep the stock screen) (default true)
 *   centerFirstEntryRadius int   A/B: chunks around the player handed to the chunk map before the first world frame
 *                            (0..3, default 3; 1 was inside the noise on the flip)
 *   centerFirstLoad true/false   single player: the initial chunk map loads nearest-first and the loader enters the world
 *                            once the 7 x 7 chunks around the player are loaded; the rest streams in during play like
 *                            chunks do while walking (pzopt.CenterFirstLoad) (default true)
 *   resumeShot      true/false   the exit save also keeps the view around the player (as resumeShotDetail keeps it, at their
 *                            zoom, pzopt-resume.jpg + .properties with the chunk grid's screen geometry); Continue shows the
 *                            7 x 7 chunks around the player from it at full brightness, whole chunks popping in in random
 *                            bursts like the live world's own build-up, paced to the save's last load time,
 *                            until the live world builds over it; a save without it gets the stock loading screen
 *                            (pzopt.ResumeShot) (default true)
 *   resumeShotDetail floors|buildings|world|full   what the exit shot keeps: floors = ground-level floors only;
 *                            buildings = every level's floors, walls, doors, furniture, items (no trees, translucent
 *                            tiles); world = everything static, trees included; full = the frame as seen, characters,
 *                            vehicles and corpses too (default full, the maintainer's choice 2026-09-24)
 *   fmodAsync       true/false   FMODManager.init (system + 12 banks, ~1.6 s) runs on a thread from the top of
 *                            GameWindow.mainThreadInit and is joined before the scripts load; the sound managers
 *                            (whose FMOD global parameters need the banks) are built at the join (default true)
 *   loadWorkers     int          recalc pool width while a world is loading (GameLoadingState.loader alive): the 361
 *                            chunks of the initial chunk map recalc on this many threads, then the pool shrinks back
 *                            to `workers` (default max(workers, cores / 2); clamped like workers. More is slower while the
 *                            recalc code is not C2-compiled yet: the interpreter / C1 profile counters are shared, so 15
 *                            workers made the first chunks 26 -> 48-65 ms each and the recalc CPU 4.5 -> 6.6-11 s,
 *                            load-b1-w15 / load-b2 2026-09-22; cores - 2 tripled it on 2026-09-19, load-s3)
 *   shaderCache     true/false   Model.CreateShader takes a shader an earlier model already created from pzopt.ModelShaders
 *                            instead of posting to the render thread and waiting one render step per model; the 73
 *                            animal models of AnimalDefinitions were 16.5 s of the load on a laptop whose loading-screen
 *                            render step is ~220 ms (GitHub issue #1) (default true)
 *   puddleCache     true/false   FBORenderCell.renderPuddles keeps the packed puddle vertices of each chunk level on the IsoChunk
 *                            and only patches the vertex lights, the camera jiggle and the depth per frame instead of
 *                            re-filtering, re-lighting and re-packing every wet square (pzopt.PuddleCache); 4.5 ms of a
 *                            13 ms thunderstorm frame at max zoom on 5120x2160 (default true)
 *   puddleCacheFrames  N      a cached puddle batch is rebuilt with the stock code after N frames at the latest,
 *                            staggered per chunk; bakes and cutaway changes rebuild it at once (default 60)
 *   puddleEarlyZ    true/false   the puddle shaders (media/shaders/pzopt_puddles_hq|mq|lq) take their depth from the vertex
 *                            instead of writing gl_FragDepth, so the GPU's early depth test drops every wet-ground pixel
 *                            hidden behind a wall, roof or object before the ~200-op puddle shader runs; same colour
 *                            math, same depth values (default true)
 *   rainSplashesFast true/false  rain splash starts are drawn by geometric skipping over the idle squares with a local
 *                            xorshift generator (one draw per splash) instead of one Rand.NextBool through the game's
 *                            CellularAutomatonRNG per idle square of every on-screen chunk level per frame; same
 *                            per-square start probability, timing, sprites and positions (pzopt.RainSplashes)
 *                            (default true)
 *   bloodBake       gpu/cpu/off  floor blood in the chunk bakes: gpu = instanced from a per-chunk cache, cpu = the cache as
 *                            sprites, off = stock (pzopt.BloodDecals; default gpu)
 *   bloodAppend     true/false   a new blood splat is drawn into the finished textures instead of re-baking (default true)
 *   bloodSettleSec  int          seconds after an append before one exact re-bake of the level (default 30, 0 = never)
 *   bloodFadeFix    true/false   drop splats pushed out at the per-chunk cap instead of drawing them forever (default false)
 *   treeRebakeLazy  true/false   42.21 counts driving as aiming, so every tree in the cutaway around the car turns
 *                            see-through; a tree re-bakes its chunk texture (and the neighbours holding its copy) only
 *                            when it leaves the bake, not again at its fade start / cutaway exit mid-fade, and a tree
 *                            back from the cutaway stays per frame until its level re-bakes anyway or treeRebakeLingerMs
 *                            passes (default true; 120 km/h drive 408 -> see docs/override-edits.md)
 *   treeRebakeLingerMs int       treeRebakeLazy's longest per-frame stay of a tree back from the cutaway (default 0 = next check)
 *   treeAppend      true/false   tree pass: when a chunk exports trees into a neighbour's texture for the first time
 *                            (a newly loaded chunk next to baked ones while driving), the quads are drawn on top of
 *                            that finished texture (same draw, same depth test, mipmaps regenerated) instead of
 *                            re-baking it; a texture that is dirty, off screen or not composited this frame is
 *                            re-baked as before, and every later change still re-bakes (default true)
 *   puddleVbo       true/false   with puddleCache: every cached puddle batch lives in its own GL buffer on the render
 *                            thread, re-uploaded only when a square's light changed, the camera crossed a chunk edge
 *                            or the batch was rebuilt; the camera jiggle is a translation of the ModelViewProjection.
 *                            Nothing is copied or patched per frame on either thread (pzopt.PuddleVbo); 12 % of a
 *                            thunderstorm frame on the laptop, ~7 ring-buffer uploads per frame on the render thread
 *                            (default true)
 *   rainTiles       true/false   ParticleRectangle renders its particles once at the origin and lists the screen cells; the
 *                            render thread uploads that template once and draws it once per cell with a translated
 *                            ModelViewProjection (pzopt.RainTiles) instead of ~90k per-cell quads through VBORenderer
 *                            (13x7 cells of 1024 rain particles at 5120x2160; 1.7 ms game thread, 6 ms render thread)
 *                            (default true)
 *   fogPass         true/false   heavy fog (ImprovedFog) drawn as one batch into a fog buffer of fogScalePct % of the viewport,
 *                            depth-tested against the scene depth read in place (the offscreen buffer's depth becomes a
 *                            texture), composited once with a depth-aware blend; the rectangle depth comes from the
 *                            vertex (early depth rejection), the noise is sampled with mipmaps, and the game thread
 *                            skips the per-square walk that only fed the row iterator (pzopt.FogPass,
 *                            docs/archive/2026-09-24/findings-fog-2026-09-21.md). Stock shades every pixel up to twelve times with a
 *                            gl_FragDepth write and one draw call per row segment: 447 -> 220 fps on the 5120x2160
 *                            120 km/h uncapped route, ~340 with the pass at 25 %. EXPERIMENTAL (2026-09-21): the
 *                            maintainer still sees a slight flicker on power lines in fog while the camera moves that
 *                            the frame captures do not reproduce; false = stock fog, no flicker (default true)
 *   fogDepthCopy    true/false   keep the offscreen buffer's depth a renderbuffer and copy it for the fog pass instead of
 *                            reading it in place (measurement, or a driver that refuses the texture; slower) (default false)
 *   fogScalePct     25..100      the fog buffer size per axis as % of the viewport (100 = full resolution; 50 = a quarter
 *                            of the fog fragment and blend work; below 100 the depth is reduced per block to its nearest
 *                            value and the composite is depth-aware, so thin objects keep their fog; 25 reads the same as
 *                            stock at 1:1 on 5120x2160 and 1920x1080 captures) (default 25)
 *   fogMaskFrames   N            the fog row walk reads per-chunk masks of the squares that take fog (exterior, not in a
 *                            room) instead of touching every square object (~10k per level per frame at max zoom on
 *                            5120x2160); a chunk's masks are refreshed every N frames, staggered per chunk, and the
 *                            row segments found are replayed while the visible diamond is unchanged and fewer than N
 *                            frames old, so a new room or wall reaches the fog within N frames; 0 = the stock
 *                            per-square walk every frame (default 20)
 *   vboBatchKb      4..1536      VBORenderer element buffer in KB (4 = stock). The rain FX add ~100k particle quads a frame
 *                            at 5120x2160 through VBORenderer.addQuad, and the 4 KB stock buffer flushes (glBufferData + draw)
 *                            every 28 quads; the render thread spent 73 % of a thunderstorm frame there (default 1024)
 *   vboFastQuads    true/false   VBORenderer.addQuad writes the four vertices of a textured quad with one position advance
 *                            and no per-vertex isFull/currentRun/position() round trips; same bytes, same indices (default true)
 *   mipmapArrays    true/false   ImageData builds texture mipmaps and premultiplies alpha row by row on byte[] copies
 *                            (pzopt.MipMaps) instead of the stock per-byte absolute reads/writes of the malloc'd
 *                            direct buffers; same pixels (tests/pzopt/MipMapsTest), same speed. Written for GitHub
 *                            issue #2, whose crash turned out to be the laptop (truncated stack address on a plain
 *                            spill reload), so this is a simplification, not the fix (default true)
 */
public final class Config {
   /** Every key read at init: key -> {effective value, default}, in declaration order (for the options tab). */
   private static final java.util.LinkedHashMap<String, String[]> REGISTRY = new java.util.LinkedHashMap<>();
   /** The keys that apply while the game runs (registered by {@link #loadLive}): the Profiler tab's. */
   private static final java.util.HashSet<String> LIVE = new java.util.HashSet<>();
   private static boolean loadingLive;
   /**
    * The Enhancements and Profiler tabs' master switches (2026-09-28): key -> {master, value while the master is off}.
    * Off, these keys read as their off value unless -D or pzopt.properties pins them (harness runs keep their props);
    * the player's own choices stay in options.ini for when the master is on again. Declared before the first read.
    */
   private static final java.util.Map<String, String[]> GATED = gated();
   private static final Properties props = load();
   /** The player's Options > Optimizations choices (Zomboid/pzopt/options.ini), below props and -D. */
   private static final Properties userProps = UserOptions.load();
   /**
    * Mods: max performance or max compatibility (2026-10-02, the main menu's mod compatibility check). It is the default
    * of modCompat (report / auto) and uiRetainedMods (on / off); a value the player set for either key still wins.
    */
   private static final boolean MOD_PROFILE_COMPAT = "compatibility".equalsIgnoreCase(String.valueOf(upper("modProfile")).trim());
   private static final String MOD_COMPAT_DEFAULT = MOD_PROFILE_COMPAT ? "auto" : "report";
   /** pzopt.ModCompat: keys a Java mod's patches of our edited methods switch off, below the player's options.ini. */
   private static final Properties compatProps = ModCompat.scan(upper("modCompat") != null ? upper("modCompat") : MOD_COMPAT_DEFAULT);
   /** Master switch, read by {@link Overrides#enabled()}; false = stock behaviour everywhere. */
   public static final boolean ENABLED = bool("enabled", true);
   public static final boolean PARALLEL = bool("parallel", true);
   public static final int WORKERS = clampWorkers(integer("workers", defaultWorkers(Runtime.getRuntime().availableProcessors())));
   public static final boolean INSTRUMENT = bool("instrument", false);
   public static final boolean INPUT_LOG = bool("inputLog", INSTRUMENT); // every key / mouse / pad change the game thread sees -> pzopt-input.out (pzopt.InputRecorder); on in harness runs
   public static final boolean WAKE = bool("wake", true);
   public static final String CHUNK_GRID_SETTING = string("chunkGridWidth", "0"); // chunks per side of the player's chunk grid, or "auto"; 0 = the stock screen-size value (IsoChunkMap.CalcChunkWidth, pzopt.ChunkGrid)
   public static final boolean CHUNK_GRID_AUTO = "auto".equalsIgnoreCase(CHUNK_GRID_SETTING);
   public static final int CHUNK_GRID_WIDTH = CHUNK_GRID_AUTO ? 0 : Math.max(0, parseInt("chunkGridWidth", CHUNK_GRID_SETTING, 0));
   public static final boolean CHUNK_GRID_FOLLOW_VIEW = bool("chunkGridFollowView", true); // a grid wider than stock moves 3 tiles north + west per level the player stands on, the ground the camera looks at (IsoChunkMap.ProcessChunkPos, pzopt.ChunkGrid.heightShiftTiles)
   public static final boolean DEV = bool("dev", false);
   public static final boolean LUA_CHECKSUM_EXEMPT = bool("luaChecksumExempt", true); // NetChecksum skips media/lua/*/pzopt/ files: they only exist on clients
   public static final boolean UPDATE_CHECK = bool("updateCheck", true); // main menu: check the GitHub releases for a newer build and offer the update item (pzopt.Updater)
   public static final boolean UPDATE_FROM_WORKSHOP = bool("updateFromWorkshop", true); // update check: offer the Steam Workshop copy already on disk before asking GitHub (pzopt.Updater.findWorkshopCopy)
   public static final boolean UPDATE_PREFETCH = bool("updatePrefetch", true); // once a release is offered, fetch the files that differ from the installed ones in the background (a range request per changed span; the whole zip never before the click)
   public static final int UPDATE_PREFETCH_MAX_KB = integer("updatePrefetchMaxKb", 16384); // the background fetch stops at this many compressed KB of changed entries (a release apart is ~60 KB)
   public static final boolean DEV_UPDATE_DRIVE = bool("devUpdateDrive", false); // dev rig: the menu opens the update dialog, presses Update now and Restart game; the restarted process logs its timing and quits (pzopt.Updater.drive)
   public static final boolean DEV_UNINSTALL_DRIVE = bool("devUninstallDrive", false); // dev rig: the main menu opens Options > Optimizations, presses Uninstall PZ Optimization and Yes (harness/uninstall-e2e.sh)
   public static final boolean DEV_UPDATE_OFFER = bool("devUpdateOffer", false); // dev: offer the newest release for this revision whatever this build is (menu item / dialog / install checks)
   public static final boolean TRANSLUCENT_CACHE = bool("translucentCache", false);
   public static final int HOTSAVE_INTERVAL_SEC = integer("hotsaveIntervalSec", 30);
   public static final boolean HOTSAVE_STAGED = bool("hotsaveStaged", false);
   public static final boolean PERSISTENT_VBO = bool("persistentVbo", true);
   public static final boolean PERSISTENT_VBO_FRAME_FENCE = bool("persistentVboFrameFence", false); // diagnostic: also wait for the fence of the frame the buffer was drawn in (did not affect the black squares, 2026-09-20)
   public static final int PERSISTENT_VBO_FRAME_LAG = integer("persistentVboFrameLag", 0); // diagnostic: wait for the fence of the unmap frame + N
   public static final int PERSISTENT_VBO_DELAY_US = integer("persistentVboDelayUs", 0); // diagnostic: CPU-only delay per persistent map (timing vs GPU race)
   /**
    * FliesSound re-runs its corpse-flies update for the 3x3 chunks around the player whenever any chunk loads (every few
    * frames while driving); for a chunk whose flies square did not move it cleared and set the same square's flag, and
    * each toggle dirtied the chunk level (object remove + add): an immediate re-bake next to the player every few frames.
    * The clear is skipped when the square stays the same; the flag ends the same, nothing reads it in between.
    */
   public static final boolean FLIES_TOGGLE_FIX = bool("fliesToggleFix", true);
   public static final boolean COMPOSITE_SHADER_RUN = bool("compositeShaderRun", false); // chunk-level textures are composited with one chunk-shader run, not a start / end per chunk (FBORenderCell.pzoptCompositeChunks)
   public static final boolean UNIFORM_CACHE = bool("uniformCache", false); // consecutive shader starts of one program skip uniforms that hold their value already (pzopt.UniformCache)
   public static final boolean UPSCALE_NO_GLGET = bool("upscaleNoGlGet", false); // the upscaler resolve takes the bound framebuffer / viewport from the game's records instead of asking the driver (pzopt.Upscaler.savedState)
   public static final boolean DEV_DLSS_STATE_LOG = bool("devDlssStateLog", false); // dev: log the GL framebuffer / viewport the DLSS resolve finds, next to the game's own records
   public static final boolean DEV_INVALIDATE_STACKS = bool("devInvalidateStacks", false); // dev: log the stacks behind object add / remove chunk invalidations (pzopt.DevStacks)
   /** Mipmap levels built after a chunk-level bake (GL_TEXTURE_MAX_LEVEL; pzopt.BakeMips); 0 = stock (the whole chain). */
   public static final int BAKE_MIP_LEVELS = Math.max(0, integer("bakeMipLevels", 3));
   /**
    * A chunk level the occlusion pass hides (no rendered square: behind a building) keeps its texture and its dirt instead
    * of clearing the dirt and freeing the texture; back in view it shows at once when nothing changed, else it re-bakes
    * through the normal path. Stock re-made it from scratch every time it came back, outside every bake budget (driving
    * past buildings in Rosewood: ~800 re-creations per 30 s). Textures of hidden levels stay allocated meanwhile.
    */
   public static final boolean OCCLUSION_RETAIN = bool("occlusionRetain", false);
   /** One per-frame budget for every chunk-level bake, granted by class and distance (pzopt.BakeScheduler); replaces the per-kind budgets. */
   public static final boolean BAKE_SCHEDULER = bool("bakeScheduler", true);
   /**
    * The rendered-squares count of every on-screen chunk level, recounted whenever the occlusion grid changes (every few
    * frames while driving, all levels in that frame), is counted on the FrameBatch workers right after the grid is built
    * instead of level by level on the game thread (FBORenderCell.pzoptPrecountRenderedSquares).
    */
   public static final boolean OCCLUSION_COUNT_PARALLEL = bool("occlusionCountParallel", true);
   /** LightingJNI.update lights at most this many never-lit chunks a pass, nearest first; the rest wait a pass (0 = all, stock). */
   public static final int LIGHTING_NEW_CHUNK_BUDGET = Math.max(0, integer("lightingNewChunkBudget", 1));
   /** lightingNewChunkBudget: with more never-lit chunks waiting than this (a world load, a teleport) every one is lit at once, as stock. */
   public static final int LIGHTING_NEW_CHUNK_BACKLOG = Math.max(1, integer("lightingNewChunkBacklog", 8));
   /** RoomDef.isKidsRoom answers kept per room through one trashed-house pass on chunk load (pzopt.KidsRoom; exact: the pass changes no kids-room tile). */
   public static final boolean KIDS_ROOM_MEMO = bool("kidsRoomMemo", true);
   public static final boolean DEV_KIDS_ROOM_CHECK = bool("devKidsRoomCheck", false); // dev: rescan every memo hit and count disagreements
   public static final boolean DEV_OCCLUSION_COUNT_CHECK = bool("devOcclusionCountCheck", false); // dev: renderOneLevel also counts on the game thread and counts disagreements with the worker count
   /** bakeScheduler: bakes granted a frame (object / tree changes to an existing texture always go on top). */
   public static final int BAKE_FRAME_BUDGET = Math.max(1, integer("bakeFrameBudget", 8));
   /** bakeScheduler: the budget follows the last frame's cost (pzopt.BakeScheduler.budget) between bakeFrameBudgetMin and bakeFrameBudget. */
   public static final boolean BAKE_BUDGET_ADAPTIVE = bool("bakeBudgetAdaptive", true);
   public static final int BAKE_FRAME_BUDGET_MIN = Math.max(1, integer("bakeFrameBudgetMin", 2));
   public static final int BAKE_BUDGET_HIGH_PCT = Math.max(10, Math.min(200, integer("bakeBudgetHighPct", 90))); // bakeBudgetAdaptive: halve above this share of the interval
   public static final int BAKE_BUDGET_LOW_PCT = Math.max(5, Math.min(150, integer("bakeBudgetLowPct", 60))); // bakeBudgetAdaptive: grow below it
   /**
    * bakeScheduler: the normal tier grants only the least rate that meets every waiting level's longest wait (earliest
    * deadline first with a demand bound: the most over d of ceil(levels due within d frames / d)), under the adaptive budget,
    * so a backlog drains evenly instead of in budget-sized bursts after fast frames.
    */
   /** bakeScheduler: a step that used more than this share (%) of its interval before the bakes are planned grants no normal-tier bake (0 = off). */
   public static final int BAKE_DEADLINE_PCT = Math.max(0, integer("bakeDeadlinePct", 0));
   public static final int BAKE_TIME_GUARD_PCT = Math.max(0, integer("bakeTimeGuardPct", 0)); // bakeScheduler at a frame cap: once the step used this share of its interval, arrival / strong / redraw / light bakes wait for the next frame (0 = off; measured 2026-10-05 at 55: 118 -> 88 fps on Louisville, the deferred levels cost more than the bakes)
   public static final boolean BAKE_SMOOTH = bool("bakeSmooth", false);
   public static final int BAKE_SMOOTH_MIN = Math.max(0, integer("bakeSmoothMin", 1)); // bakeSmooth: normal-tier bakes granted a frame at least (when any wait)
   /** bakeScheduler: a chunk's seam re-bake after a neighbour loads is urgent only for a south / east neighbour (the squares SeamFix2 reads). */
   public static final boolean SEAM_DIRECTIONS = bool("seamDirections", false);
   /** bakeScheduler: the occlusion rebuild looks only at the levels granted this frame, not every waiting one. */
   public static final boolean OCCLUSION_GRANTED_ONLY = bool("occlusionGrantedOnly", true);
   /** bakeScheduler: never-textured levels granted a frame on top of the budget (0 = they queue with the rest). */
   public static final int BAKE_ARRIVAL_QUOTA = Math.max(0, integer("bakeArrivalQuota", 4));
   /** bakeScheduler: the most bakes a frame when levels past their longest wait are pending. */
   public static final int BAKE_FRAME_BUDGET_HARD = Math.max(1, integer("bakeFrameBudgetHard", 12));
   /** bakeScheduler: longest wait (frames) per class before a level jumps the queue. */
   public static final int BAKE_MAX_WAIT_CUTAWAY = Math.max(0, integer("bakeMaxWaitCutaway", 3));
   public static final int BAKE_LEVEL_CHANGE_RADIUS = integer("bakeLevelChangeRadius", 1); // bakeLevelChangeFrames: only chunks at most this many chunks from the camera character bake at once (-1: all on screen; 1, the 3x3 around the player: as calm as all on the stairs walk, first arrival on a floor 80 ms instead of 170 ms on the flip)
   public static final int BAKE_LEVEL_CHANGE_FRAMES = Math.max(0, integer("bakeLevelChangeFrames", 3)); // bakeScheduler: for this many frames after the camera's level changes (stairs), every cutaway and never-textured level is baked at once, like stock: the new floor appears whole instead of chunk by chunk over ~10 frames (0 = spread as any other cutaway)
   public static final int BAKE_MAX_WAIT_STRONG = Math.max(0, integer("bakeMaxWaitStrong", 6));
   public static final int BAKE_MAX_WAIT_ARRIVAL = Math.max(0, integer("bakeMaxWaitArrival", 8));
   public static final int BAKE_MAX_WAIT_REDRAW = Math.max(0, integer("bakeMaxWaitRedraw", 48));
   public static final int BAKE_MAX_WAIT_LIGHT = Math.max(0, integer("bakeMaxWaitLight", 120));
   /** A chunk's seam re-bake after a neighbour loads is queued and released a few a frame (pzopt.SeamSpread) instead of all at once. */
   public static final boolean SEAM_SPREAD = bool("seamSpread", false);
   /** seamSpread: queued chunks released per frame. */
   public static final int SEAM_REBAKE_BUDGET = Math.max(1, integer("seamRebakeBudget", 2));
   /** seamSpread: no release (except overdue ones) in a frame after one that baked this many levels. */
   public static final int SEAM_HEAVY_BAKES = Math.max(1, integer("seamHeavyBakes", 6));
   /** seamSpread: the longest a queued seam re-bake waits (frames). */
   public static final int SEAM_MAX_FRAMES = Math.max(1, integer("seamMaxFrames", 60));
   /** Persistent sprite buffers are fenced per frame instead of per 64 KB batch; a map waits only for a frame not yet known done (GLVertexBufferObject). */
   public static final boolean PERSISTENT_VBO_FRAME_SYNC = bool("persistentVboFrameSync", true);
   /** persistentVboFrameSync: a sprite buffer last drawn this many frames ago is reused without asking the driver (the swap chain caps the frames in flight at 2-3). */
   public static final int PERSISTENT_VBO_TRUST_FRAMES = Math.max(4, integer("persistentVboTrustFrames", 8));
   public static final int PERSISTENT_VBO_SLOTS = integer("persistentVboSlots", 4); // storage slots per sprite buffer object (reuse distance x K)
   public static final boolean PERSISTENT_VBO_COHERENT = bool("persistentVboCoherent", true); // false: MAP_FLUSH_EXPLICIT + glFlushMappedBufferRange at unmap
   public static final boolean PERSISTENT_VBO_FINISH = bool("persistentVboFinish", false); // diagnostic: glFinish before every persistent map (GPU read race check)
   public static final boolean TREES_IN_CHUNK_TEXTURE = bool("treesInChunkTexture", true);
   public static final int[] DEV_TREE_PASS_CYCLE = intList(string("devTreePassCycle", "")); // dev: stencil-tree pass masks cycled every devTreePassAlternate ms (bit 0 outside the cutaway, 1 inside faded, 2 inside outline); GPU section translucent.m<mask>
   public static final int DEV_TREE_PASS_ALTERNATE = integer("devTreePassAlternate", 500);
   public static final boolean DRIVE_TREE_CUTAWAY = bool("driveTreeCutaway", false); // 42.21: in a vehicle counts as aiming in FBORenderCell.isTranslucentTree (every tree in the cutaway square turns see-through); false (default, maintainer 2026-10-03) = 42.20's rule
   public static final boolean DEV_XXL_VEHICLE_FADE = bool("devXxlVehicleFade", true); // dev A/B: false = no XXL tree fade while driving (pzopt.XxlTreeFade)
   public static final boolean DEV_XXL_TREE_LOG = bool("devXxlTreeLog", false); // dev: every 10 s, how many XXL trees 42.21's cutaway rules made see-through (pzopt.XxlTreeFade)
   // default false since 2026-10-01: baked glass writes the window's depth into the chunk texture, so a zombie
   // standing outside a window was hidden behind its own pane (Workshop reports "zombies not visible at windows",
   // also glass doors); stock draws windows per frame with depth writes off. It measured no gain (runs windows-1)
   public static final boolean WINDOWS_IN_CHUNK_TEXTURE = bool("windowsInChunkTexture", false);
   public static final boolean TRANSLUCENT_TILES_IN_CHUNK_TEXTURE = bool("translucentTilesInChunkTexture", true);
   public static final boolean GLASS_TILES_PER_FRAME = bool("glassTilesPerFrame", true); // Translucent glass tiles stay per frame: baked, their pane hid the characters behind it (2026-10-01)
   public static final boolean FLOOR_DECALS_PER_FRAME = bool("floorDecalsPerFrame", true); // Translucent tiles lying on the floor stay per frame: baked, a manhole cover z-fought with the puddle drawn over it (2026-10-05)
   public static final boolean TRANSLUCENT_LIGHTS_PER_FRAME = bool("translucentLightsPerFrame", true); // translucent light fixtures with a lit sprite stay per frame (gas canopy lights through the roof, 2026-09-28)
   public static final float CURTAIN_DEPTH_NUDGE = Math.max(0, integer("curtainDepthNudgePct", 5)) / 100.0F; // tiles; issue #4
   public static final int BAKE_BUDGET = integer("bakeBudget", 8);
   public static final int LIGHTING_BUDGET = integer("lightingBudget", 8);
   public static final int LIGHTING_REBAKE_MS = integer("lightingRebakeMs", 250);
   public static final int REBAKE_BUDGET = integer("rebakeBudget", 4);
   public static final int REBAKE_MAX_FRAMES = Math.max(1, integer("rebakeMaxFrames", 3));
   public static final int LIGHTING_REBAKE_BUDGET = Math.max(1, integer("lightingRebakeBudget", 8)); // lighting-only (flag 32) re-bakes started per frame once rebakeBudget applies
   public static final int LIGHTING_REBAKE_MAX_FRAMES = Math.max(1, integer("lightingRebakeMaxFrames", 30)); // longest hold of a lighting-only re-bake
   // zoom smoothness (2026-09-22): chunk-level textures that leave the screen when the camera zooms in are kept while
   // the chunk stays inside the screen rectangle of the widest zoom (high-res ones: of the widest zoom below 0.75)
   // instead of being freed, so zooming back out re-uses them; a kept texture returning to the screen is re-baked under
   // its own per-frame budget and shown as it was meanwhile; a level whose texture at the new scale (the 0.75 crossing)
   // is still to be baked shows its other-scale texture instead of nothing
   public static final boolean ZOOM_RETAIN = bool("zoomRetain", true);
   public static final int ZOOM_REBAKE_BUDGET = Math.max(1, integer("zoomRebakeBudget", 12)); // most chunk-level bakes a zoom change may start per frame (returned textures and, during a flood, new ones); the plan adapts below it
   public static final float ZOOM_FRAME_MS = Math.max(1.0F, integer("zoomFrameMs", 10)); // game-thread frame length above which the per-frame count halves (grows back under 3/4 of it)
   public static final boolean ZOOM_PLACEHOLDER = bool("zoomPlaceholder", true); // other-scale texture while the new scale bakes
   // zoom motion (2026-09-22, pzopt.ZoomEase): a manual zoom change takes zoomEaseMs of wall-clock time along a cubic Bézier
   // (stock: 0.03 per frame and a snap, 8 frames whatever the frame rate); 0 = the stock step
   public static final int ZOOM_EASE_MS = Math.max(0, integer("zoomEaseMs", 300));
   public static final String ZOOM_EASE = string("zoomEase", "0.25,0.1,0.25,1.0"); // Bézier control points x1,y1,x2,y2 (CSS "ease")
   public static final int LIGHTING_STRONG_DELTA = Math.max(1, integer("lightingStrongDelta", 6)); // a square's light moved by this much (0-255, summed since the level's last bake): the level re-bakes now instead of being held (pzopt.LightDirt)
   public static final int LIGHTING_STRONG_BUDGET = Math.max(0, integer("lightingStrongBudget", 8)); // strong levels that re-bake at once per frame; the rest take the holds (0 = unlimited, the 2026-09-21 behaviour: turning marks ~every exterior level strong, 10.8 fps in downtown Louisville)
   public static final float LIGHTING_STRONG_FRAME_MS = Math.max(0, integer("lightingStrongFrameMs", 0)); // game-thread frame step above which the strong re-bake budget halves for the next frame (grows back under 3/4 of it); 0 = fixed budget. Breaks the slow-frame -> more strong marks -> more bakes -> slower frame loop of downtown Louisville (FBORenderCell.pzoptStrongBudget)
   public static final float LIGHTING_GLOBAL_DELTA = Math.max(0, integer("lightingGlobalDeltaPct", 2)) / 100.0F; // a per-frame move of the global light (colour, ambient, night, sky) past this is a flash or dusk: the spread applies for lightingRebakeMaxFrames
   public static final boolean LIGHTING_FLUSH = bool("lightingFlush", true); // drain the lightingBudget queue before a lighting pass rewrites the dirty bits (false = the 2026-09-21 morning behaviour, for A/Bs)
   public static final int LIGHT_SWITCH_CHECK_FRAMES = integer("lightSwitchCheckFrames", 15);
   public static final int DEV_REDRAW_FRAME = integer("devRedrawFrame", 0);
   public static final boolean DEV_WORLD_SOUND_TIMING = bool("devWorldSoundTiming", false); // dev: per-section nanoTime totals of WorldSoundManager.addSound (pzoptTiming())
   public static final boolean DEV_PROFILE_LOG_OFF = bool("devProfileLogOff", false); // dev: harness runs sample the game-thread stack only while the overlay is shown (baseline for the overlay cost)
   public static final boolean DEV_CUTAWAY_LOG = bool("devCutawayLog", false); // dev: roof hide/show decisions per frame in the log
   public static final boolean GPU_SECTIONS = bool("gpuSections", false); // measurement only: GPU time per frame section in the log
   public static final boolean DEV_WEATHER_FX_OFF = bool("devWeatherFxOff", false); // measurement only: skip the weather FX pass
   public static final boolean TREE_BAKE_DIRECT = bool("treeBakeDirect", true);
   public static final int TREE_BAKE_MAX_CHUNKS_PER_SEC = integer("treeBakeMaxChunksPerSec", 0); // 0 = always bake (pzopt.ChunkRate)
   public static final boolean TREE_BAKE_PASS = bool("treeBakePass", true); // issue #5: pzopt.TreeBake draws the baked trees
   public static final boolean CUTAWAY_FAST = bool("cutawayFast", true);
   public static final int CUTAWAY_RADIUS = integer("cutawayRadius", 6);
   public static final int GRID_STACK_INTERVAL = integer("gridStackInterval", 8);
   public static final boolean WEATHER_MASK_IDLE_SKIP = bool("weatherMaskIdleSkip", true);
   public static final boolean SLACK_WORK = bool("slackWork", false); // at a frame cap, deferred game-thread jobs (far loot rolls, far zombie spawns, far chunk hand-offs) run in the step's slack before the limiter's park, each while its learned cost fits the time left (pzopt.SlackWork)
   public static final int SLACK_MARGIN_US = Math.max(0, integer("slackMarginUs", 700)); // slackWork: time kept free before the next step
   public static final int SLACK_MAX_WAIT_FRAMES = Math.max(1, integer("slackMaxWaitFrames", 600)); // slackWork: a job that waited this long runs whatever its cost (one a frame)
   public static final boolean ZOMBIE_SPAWN_SPREAD = bool("zombieSpawnSpread", false); // the zombies the native population turns real in one frame are created from a queue under zombieSpawnBudgetUs a frame (ZombiePopulationManager.updateMain): a chunk row next to a horde made dozens of IsoZombies in one frame. They appear a few frames later (an intended edit)
   public static final int ZOMBIE_SPAWN_BUDGET_US = Math.max(0, integer("zombieSpawnBudgetUs", 600));
   public static final int ZOMBIE_SPAWN_DRAIN_FRAMES = Math.max(1, integer("zombieSpawnDrainFrames", 240)); // zombieSpawnSpread: the budget grows so any backlog is created within this many frames (up to zombieSpawnMaxUs a frame)
   public static final int ZOMBIE_SPAWN_MAX_US = Math.max(0, integer("zombieSpawnMaxUs", 1500));
   public static final int ZOMBIE_SPAWN_LOAD_MS = Math.max(0, integer("zombieSpawnLoadMs", 3000)); // zombieSpawnSpread: for this long after the first population update (the load's mass spawn) every queued zombie is created at once, as stock
   public static final int ZOMBIE_SPAWN_MAX_AGE_MS = Math.max(0, integer("zombieSpawnMaxAgeMs", 1000)); // zombieSpawnSpread: a queued zombie waits at most this long (wall time), so a slow machine's small per-frame budget never thins the horde (the flip / Mac test of 2026-10-06 had half the zombies at route start)
   public static final int ZOMBIE_SPAWN_NEAR = Math.max(0, integer("zombieSpawnNear", 25)); // zombieSpawnSpread: a zombie this close (squares) to a player is created at once, as stock
   public static final int ZOMBIE_SPAWN_MIN = Math.max(1, integer("zombieSpawnMin", 2));
   public static final boolean LOOT_DEFER = bool("lootDefer", false); // time-sliced loot: a chunk arriving lootDeferDistance squares or more from every player queues its unexplored containers and they roll under lootDeferBudgetUs a frame (pzopt.LootDefer; single player). A downtown chunk's loot roll was up to 32 ms of one frame. Changes the order of random draws (an intended edit)
   public static final int LOOT_DEFER_DISTANCE = Math.max(0, integer("lootDeferDistance", 20));
   public static final int LOOT_DEFER_BUDGET_US = Math.max(0, integer("lootDeferBudgetUs", 400));
   public static final int CHUNK_HANDOFF_DIVISOR = integer("chunkHandoffDivisor", 8);
   public static final boolean CHUNK_HANDOFF_SLACK_WORK = bool("chunkHandoffSlackWork", true); // slackWork: at a cap, queued chunks join the world in the step's slack, one at a time while their learned cost fits (IsoChunkMap.pzoptHandOffChunk)
   public static final int CHUNK_HANDOFF_SLACK_BACKLOG = Math.max(1, integer("chunkHandoffSlackBacklog", 24)); // chunkHandoffSlackWork: with this many queued (a world load, a teleport) the in-frame hand-off runs as before
   public static final boolean CHUNK_HANDOFF_SLACK = bool("chunkHandoffSlack", false); // pzopt.ChunkHandoff: after a heavy frame the chunk hand-off waits for one with headroom
   public static final int CHUNK_HANDOFF_SLACK_PCT = integer("chunkHandoffSlackPct", 60); // chunkHandoffSlack: a game step above this share of the cap interval is heavy
   public static final int CHUNK_HANDOFF_SLACK_QUEUE = integer("chunkHandoffSlackQueue", 8); // chunkHandoffSlack: never wait with this many chunks queued
   public static final int CHUNK_HANDOFF_MAX_WAIT = integer("chunkHandoffMaxWait", 6); // chunkHandoffSlack: frames at most
   public static final int WEATHER_FX_SCALE_PCT = integer("weatherFxScalePct", 100);
   public static final boolean CUTAWAY_VISIT_PREFILTER = bool("cutawayVisitPrefilter", true);
   public static final int ROOF_HIDE_DEBOUNCE_FRAMES = integer("roofHideDebounceFrames", 8);
   public static final boolean CUTAWAY_INVALIDATE_CHANGED = bool("cutawayInvalidateChanged", true);
   public static final boolean SOUND_ZONE_CACHE = bool("soundZoneCache", true);
   public static final boolean VEHICLE_CULL = bool("vehicleCull", true); // IsoZombie.isVehicleBetween: bounding-circle test per vehicle before the exact box test, over the per-frame list of vehicles near the player (pzopt.VehicleCull)
   public static final boolean PLAYER_LOS_FAST = bool("playerLosFast", true); // IsoPlayer.updateLOS: the remembered-spotted membership is an identity set beside the stack (stock walked the stack per spotted object per frame), loop invariants hoisted, the sneak spot modifier memoised per frame (pzopt.PlayerLos)
   public static final boolean ZOMBIE_SPOT_FAST = bool("zombieSpotFast", true); // IsoZombie.spottedNew: a zombie whose spot chance is already zero (facing away, beyond its vision radius) skips the modifiers, the vehicle test and the roll, keeping the same outcome
   public static final boolean PLAYER_LOS_NATIVE = bool("playerLosNative", false); // experiment: the LOS arithmetic (distances, close count, branch per object) in C++ over arrays Java packs, one FFM call per player per frame (pzopt.NativeLos, natives/libpzopt_los64.so built with PZOPT_NATIVE=1); off = the Java loop
   public static final String PLAYER_LOS_NATIVE_LIB = string("playerLosNativeLib", ""); // experiment: absolute path of libpzopt_los64.so when it is not under the game dir's natives/ (build/native/ of the checkout survives other sessions' builds; build/classes does not)
   public static final boolean ANIM_BONES_PARALLEL = bool("animBonesParallel", true); // the zombies' animation bone math (blend, twist, model and skin matrices) runs on worker threads after the postupdate loop (pzopt.AnimBatch)
   public static final int ANIM_BONES_THREADS = Math.max(1, integer("animBonesThreads", 8)); // (2026-09-22: the old name of frameThreads, read as its default)
   public static final int FRAME_THREADS = Math.max(1, integer("frameThreads", ANIM_BONES_THREADS)); // worker threads of the per-frame zombie batches (bone math, action-context transitions; pzopt.FrameBatch; the game thread joins in); clamped to cores - 1
   public static final boolean ANIM_BATCH_ASYNC = bool("animBatchAsync", true); // the zombies' bone batch (animBonesParallel) runs on the frame workers while the game thread carries on with the frame's logic; joined at IsoWorld.FinishAnimation (the game's own animation join point, before the render phase) or by the next frame batch (pzopt.AnimBatch, pzopt.FrameBatch)
   public static final boolean ANIMATOR_PARALLEL = bool("animatorParallel", true); // the zombies' animator, move deltas and model update (track tick) run on the frame workers after the transition evaluation, anim events captured and dispatched on the game thread in order; a zombie whose animator fires an event, or whose anim states read a side-effecting variable callback, finishes on the game thread (pzopt.AnimParallel; IsoGameCharacter, IsoZombie, AnimationTrack, AnimationMultiTrack overrides)
   public static final boolean ANIMATOR_PIPELINE = bool("animatorPipeline", true); // animatorParallel: the workers alone run the animator batch while the game thread dispatches each zombie's events in order as soon as its task is done (instead of taking tasks itself)
   public static final int FRAME_SPIN_US = Math.max(0, integer("frameSpinUs", 0)); // how long a frame worker spins for the next batch before parking (pzopt.FrameBatch); 0 = park at once
   public static final boolean GUARDED_CALLBACKS = bool("guardedCallbacks", true); // bHasTarget / shouldSprint / bPassengerExposed are read on the workers like pure callbacks; their rare side effect throws there and falls back to the game thread (pzopt.ActionEval.GUARDED_CALLBACKS)
   public static final boolean POOL_STATS_BATCHED = bool("poolStatsBatched", true); // the frame workers tally the game's pool statistics counters (shared AtomicDouble CAS loops) per thread and publish them once per batch (pzopt.PoolStats; PerformanceStatistic override)
   public static final boolean MODEL_LOCK_PER_INSTANCE = bool("modelLockPerInstance", true); // ModelInstance.lock is a new object per instance instead of stock's shared interned string (its only user, ModelSlot.Update, then no longer serialises the frame workers; ModelInstance override)
   public static final boolean HEAD_ON_WORKER = bool("headOnWorker", false); // a queued zombie's postUpdateAnimating head (forward direction, aim angle, turning flags) runs in its transition-evaluation task on a frame worker; a turn-around that would fire Turn180Started / TargetChanged goes back to the game thread (IsoGameCharacter override, pzopt.ActionEval)
   public static final boolean LAZY_POSE = bool("lazyPose", true); // an animation track finds a bone's keyframe span when the bone is first read after a tick instead of all 60 spans every tick (same spans; AnimationTrack override)
   public static final boolean DEV_ANIM_ASYNC_TRACE = bool("devAnimAsyncTrace", false); // log the stack of the first 20 early joins of the async bone batch (a game-thread touch of an in-flight player)
   public static final boolean ACTION_EVAL_PARALLEL = bool("actionEvalParallel", true); // the zombies' action-context transition evaluation runs on the frame workers between the postupdate loop and the animator updates (pzopt.ActionEval; IsoGameCharacter + ActionContext + MovingObjectUpdateScheduler overrides); a state with a Lua condition, a grappled / grappling / reanimated zombie and multiplayer take the stock path
   public static final boolean ACTION_SNAPSHOT_FILTER = bool("actionSnapshotFilter", true); // actionEvalParallel: the game-thread snapshot before a batch only reads the operands whose variable name is a callback with side effects (the name is fixed by the XML, the callback set by the character constructor), instead of resolving every operand of every transition of every batched zombie every frame
   public static final boolean AUDIO_LIMITER = bool("audioLimiter", true); // pzopt.AudioLimiter: FMOD's limiter DSP at the head of the master channel group, so the mix never clips (stock went over full scale with a pistol fired next to the listener over alarms and a horde)
   public static final float AUDIO_LIMITER_CEILING_DB = Math.min(0, Math.max(-12, integer("audioLimiterCeilingDb", -2))); // the limiter's ceiling in dBFS (-12..0; -2 leaves room for the 32 -> 48 kHz resample and codec overshoot)
   public static final boolean AUDIO_LIMITER_STEREO_FOLD = bool("audioLimiterStereoFold", true); // on a stereo device the limiter takes stereo input: FMOD folds its 5.1 mix to stereo ahead of the limiter instead of the OS mixer summing six channels after it (the clipping of snd-i3c-fmt)
   public static final int SOUND_TICK_HZ = integer("soundTickHz", 60); // pzopt.SoundTick: the ambient-object slots, wall emitters, listener ambience parameters and busy zombies' parameters at most this often (FMOD Studio applies them every 20 ms); 0 = every frame like stock
   public static final boolean WORLD_SOUND_CLEANUP_FAST = bool("worldSoundCleanupFast", true); // WorldSoundManager.update: the per-chunk sweep of expired world sounds runs only after a frame in which some sound expired (none can be dead in a chunk list otherwise), and IsoChunk.updateSounds compacts its list in one order-preserving pass instead of ArrayList.remove per dead entry
   public static final boolean DEV_WORLD_SOUND_CLEANUP_CHECK = bool("devWorldSoundCleanupCheck", false); // rig: on a frame worldSoundCleanupFast skips the chunk sweep, scan anyway and count dead sounds found (WorldSoundManager.pzoptSkipDeadFound, must stay 0; SoundProbe summary)
   public static final boolean DEV_AMBIENT_SLOT_LOG = bool("devAmbientSlotLog", false); // rig: ObjectAmbientEmitters logs why every stopped ambient-object slot lost its place (SoundProbe writes the lines into pzopt-sound.out)
   public static final boolean HEARING_HOIST = bool("hearingHoist", true); // WorldSoundManager.getBiggestSoundZomb: the zombie's hearing multiplier and its own square are computed once per call instead of once per candidate sound (same values: pure reads with the same arguments)
   public static final boolean EMITTER_IDLE_SKIP = bool("emitterIdleSkip", true); // IsoGameCharacter.updateEmitter: a zombie whose three FMOD emitters hold no sound returns at once on a flag the FMODSoundEmitter override raises when a sound is queued (stock asked all three emitters' three lists every frame and ticked them empty every 30 ms: ~2 % of the game thread in a horde); a busy zombie takes the stock path until its emitters are empty again
   public static final boolean EMITTER_PARAM_SKIP = bool("emitterParamSkip", true); // IsoGameCharacter.updateEmitter: the character's FMOD parameters (footstep material walk, zone lookup, ...) are recomputed only while its emitter has a running event instance or one about to start; a silent character's values go nowhere and are refreshed before its next sound starts
   public static final boolean SEPARATE_FAST = bool("separateFast", true); // IsoZombie.separate: the separation pass specialised for a zombie (stock's player bump / charged-spear half is unreachable there) with the "blocked to that neighbour" grid answers cached per square for the frame (pzopt.SeparateMask) instead of recomputed per character
   public static final boolean SLEEP_CHECK_MEMO = bool("sleepCheckMemo", true); // IsoPlayer.allPlayersAsleep memoised per frame: GameTime.getMultiplier asks it on the way into every character update, so the player array was walked tens of thousands of times a frame
   public static final boolean STATE_PARAM_MEMO = bool("stateParamMemo", true); // IsoZombie.getStateMachineParams keeps the last (state class, map) pair: an AI state reads several State.Param values of the same class in a row, each through the component map and an IdentityHashMap probe
   public static final int ZOMBIE_SIM_LOD_TILES = integer("zombieSimLodTiles", 0); // experiment: one extra simulation-level step (stock's own MovingObjectUpdateScheduler LOD, which already steps at 30 / 60 / 80 tiles) for a zombie further than this many tiles from the nearest player; 0 = stock
   public static final boolean SEPARATE_PARALLEL = bool("separateParallel", true); // the zombies' separation pass computed on the frame workers before the update loop, applied by the game thread in loop order (pzopt.SeparateBatch; needs separateFast); the neighbours' positions are then read at the top of the frame instead of as the loop advances
   public static final boolean ENTITY_UPDATE_PARALLEL = bool("entityUpdateParallel", false); // the simulation bucket's own update loop on the frame workers (pzopt.UpdateBatch): a bucket's entities run setCurrentSimulationLevel / preupdate / frameStep / update on the workers instead of one after another on the game thread. Per bucket, never across two of them: GameTime.perObjectMultiplier is the bucket's frame mod for the whole loop and 1 after it. Default off: unlike the postupdate batches this one moves the simulation itself, so the entities' writes land in a different order within the frame
   public static final boolean ENTITY_UPDATE_SAFE_STATES = bool("entityUpdateSafeStates", true); // with entityUpdateParallel: only zombies in a calm state (idle, walk toward, path find) with no ballistics target, ragdoll, fall, fire or grapple and no player within 12 squares run on the workers (IsoZombie.pzoptBatchSafe); false = PR #35's filter (every zombie but grapple / reanimated)
   public static final boolean RAGDOLL_QUIT_SWEEP = bool("ragdollQuitSweep", true); // WorldSimulation.destroy: ragdolls still in the Bullet world at quit are removed before the world is destroyed (libPZBullet deletes the world before its ragdolls: SIGSEGV in Ragdoll::deleteRigidBodies); pzopt.RagdollLedger logs each one
   public static final boolean RAGDOLL_CORPSE_GUARD = bool("ragdollCorpseGuard", true); // AnimationPlayer: no ragdoll controller for a character that has already turned into its corpse; stock builds one from the leftover ragdoll track in the frame the zombie dies (ZombieOnGroundState.enter -> die, then the model update), owned by nobody: an orphan Bullet body until the zombie is reused, counted against the active-ragdoll cap, and the quit crash when one is still there
   public static final boolean DEV_RAGDOLL_LEDGER = bool("devRagdollLedger", false); // pzopt.RagdollLedger evidence: stacks of ragdolls made for characters on no square, where they left it, the ragdoll state at each death (deaths(track/ctl/sim)=), a per-frame off-square check
   public static final boolean ENTITY_UPDATE_LUA_REPLAY = bool("entityUpdateLuaReplay", true); // a worker mid-batch reaching LuaEventManager.triggerEvent captures the event and its arguments instead of dropping them (the first wired run counted 4.38M drops in 26 s — the per-zombie update event, so any mod hooking it was silently dead with the batch on); after the join the game thread replays the captures in queue order — stock's serial event order — still inside the bucket window, so handlers run under the bucket's perObjectMultiplier exactly like stock's inline dispatch. Off = the bare drop guard and its counter, kept for pricing the replay's game-thread cost
   public static final boolean ENTITY_UPDATE_PIPELINE = bool("entityUpdatePipeline", false); // with entityUpdateParallel: a bucket batch is dispatched WITHOUT joining, the game thread collects the next bucket (and runs its inline players/vehicles/animals) while the workers run, and the join lands right before the next dispatch (the scheduler override joins after the last bucket). Each batch carries its dispatch-time perObjectMultiplier (pom()), so tasks and the Lua replay see their own bucket's time scale while the game thread is already writing the next one. Off = the synchronous dispatch-then-join shape
   public static final boolean EMITTER_DEFER = bool("emitterDefer", true); // with entityUpdateParallel: a batched zombie's updateEmitter (FMOD parameters, position, tick) is queued on its task's slot instead of running on the worker, and joinPending runs the queued ticks on the game thread in queue order under the flight's multiplier. Shortens the flight (the ticks serialized on the emitter monitors, against each other and the inline player's combat) and takes the workers off updateEmitter's static tempVectorBonePos scratch. Off = the ticks run on the workers under the emitter locks, as before
   public static final boolean PHYSICS_DEFER = bool("physicsDefer", true); // with entityUpdateParallel: the two Bullet calls a batched zombie's update can make are queued on its task's slot instead of running on the worker, and joinPending makes them on the game thread in queue order under the flight's multiplier. IsoGameCharacter.updateBallisticsTarget (the hitbox of a character a gun is aimed at: add / axis / position / skeleton / remove, five natives) and IsoZombie's RagdollController.vehicleCollision (the ragdoll-versus-car contact test: setRagdollBodyDynamics / resetRagdollBodyDynamics, plus BaseVehicle.testTouchingVehicle on a vehicle the game thread may be updating). Bullet is not thread-safe, a native crash is not catchable by the batch's failure latch, and BallisticsTarget hands the native a public static float[] its own initialize() can replace mid-fill — so this is a crash fix, not a speed one: shooting zombies with the batch on killed the process in both live runs. Off = the calls run on the workers, which is the crash
   public static final int DEV_PIPELINE_ALTERNATE = integer("devPipelineAlternate", 0); // seconds per window, 0 = off: the bucket seam alternates entityUpdatePipeline on/off every N seconds inside ONE run and logs each flip with its epoch, so the frame log splits into paired on/off distributions over the identical zombie population (the repo's devPplAlternate pattern; cross-run comparisons are confounded because a faster build loads more zombies by route start)
   public static final boolean ACTION_GROUP_CACHE = bool("actionGroupCache", true); // IsoZombie holds the "zombie" and "zombie-crawler" ActionGroups instead of asking ActionGroup for them by name (a lower-cased copy of the string and a HashMap probe) twice per zombie per frame
   public static final boolean PROFILER_THREAD_MEMO = bool("profilerThreadMemo", true); // GameProfiler.isValidThread memoised per thread: every performance probe in the game calls it twice and the valid-thread list is an ArrayList of names scanned with String.equals
   public static final boolean PROFILER_IDLE_FAST = bool("profilerIdleFast", true); // GameProfiler probes while nothing records: the two valid threads known by identity, our frame workers never valid, isRunning false until some thread has run the profiler; every zombie update / postupdate asked two ThreadLocals per probe (2.2 % of the game thread on the Louisville horde)
   public static final boolean WORLDGEN_PATTERN_CACHE = bool("worldgenPatternCache", true); // WorldGenUtils.canPlace keeps each placement glob's compiled regex instead of compiling it on every String.matches (~5 % of the allocation, world streamer)
   public static final boolean STATS_NO_BOX = bool("statsNoBox", true); // Stats.get without getOrDefault's boxed default: a new Float on every stat read (10 % of the sampled allocation on the Louisville horde)
   public static final boolean STATE_MACHINE_NO_ITER = bool("stateMachineNoIter", true); // StateMachine.getMinimumSimulationLevel walks its substates by index instead of an iterator (10 % of the sampled allocation with tiered zombie updates)
   public static final boolean POSTUPDATE_PARALLEL = bool("postupdateParallel", false); // Louisville 120 plan B (2026-10-06): the zombies' postupdate movement (impulse, vehicle resolution, DoCollide, the destination square) computed on the frame workers against a frozen world and committed on the game thread in stock order (pzopt.PostupdateBatch)
   public static final boolean DEV_POSTUPDATE_CHECK = bool("devPostupdateCheck", false); // dev: postupdateParallel, a sample of the split movements recomputed by the stock body and compared field by field (pzopt.PostupdateBatch, counts in the postupdate stats line)
   public static final boolean ZOMBIE_REUSE_SPREAD = bool("zombieReuseSpread", false); // Louisville 120 item 3 (2026-10-06): the zombies leaving the world are reset for reuse oldest first under zombieReuseBudgetUs a frame instead of all in their frame (a chunk row leaving the grid downtown: ~10 ms of resets in one frame; pzopt.ReuseSpread). Intended difference: the reset's random draws come later
   public static final int ZOMBIE_REUSE_BUDGET_US = Math.max(0, integer("zombieReuseBudgetUs", 300)); // zombieReuseSpread: resets a frame stop after this much time (at least backlog / zombieReuseDrainFrames)
   public static final int ZOMBIE_REUSE_DRAIN_FRAMES = Math.max(1, integer("zombieReuseDrainFrames", 60)); // zombieReuseSpread: the queue empties within this many frames whatever the budget
   public static final int ZOMBIE_SIM_LOD_STEPS = Math.max(1, integer("zombieSimLodSteps", 1)); // how many extra simulation-level steps zombieSimLodTiles may take, each at twice the distance of the previous one
   public static final int ZOMBIE_MODEL_ADD_BUDGET_US = Math.max(0, integer("zombieModelAddBudgetUs", 0)); // IsoWorld.sceneCullZombies: zombies getting a 3D model this frame (ModelManager.Add, model + clothing) stop after this much time; the rest stay flat sprites a frame longer (0 = stock, all at once; 500 measured on Louisville, off by default until the maintainer decides)
   public static final boolean ZOMBIE_LOD_DYNAMIC = bool("zombieLodDynamic", false); // how many zombies get a 3D model (stock 510) and blend their animations (stock 20) follows the frame cap: lowered while frames miss it, raised back while there is headroom (pzopt.ZombieLod)
   public static final int ZOMBIE_LOD_MIN_3D = Math.max(0, integer("zombieLodMin3d", 128)); // zombieLodDynamic's floor for zombies drawn as 3D models (the rest are flat sprites)
   public static final int ZOMBIE_LOD_MIN_BLEND = Math.max(0, integer("zombieLodMinBlend", 6)); // zombieLodDynamic's floor for zombies that blend their animations
   public static final int ZOMBIE_LOD_UNCAPPED_FPS = Math.max(0, integer("zombieLodUncappedFps", 0)); // zombieLodDynamic's target while uncapped; 0 = stock detail when uncapped
   public static final int ZOMBIE_CHECK_SPREAD = integer("zombieCheckSpread", 0); // experiment: a zombie's thump probe (the "is something thumpable in front of me" grid test) runs on one frame in N, spread by zombie id; 0 or 1 = stock, every frame. It must not cover the visible-to-player test: that one also decides whether the zombie is drawn
   public static final boolean ZOMBIE_CULL_SORT_FAST = bool("zombieCullSortFast", true); // IsoWorld.sceneCullZombies: one relevance score per zombie and a primitive key sort instead of a comparator sort that recomputes both scores per comparison; same order
   public static final boolean LIGHTING_READ_PARALLEL = bool("lightingReadParallel", true); // the pre-pass drain of the lighting queue reads its chunk levels on the frame workers, one task per level (pzopt.LightingBatch; FBORenderCell.pzoptFlushPendingLighting, LightingJNI override); the room-seen / meta hooks are applied by the game thread after the join
   public static final boolean DEV_LIGHTING_READ_CHECK = bool("devLightingReadCheck", false); // dev: after a parallel lighting batch re-read one square in sixteen on the game thread and count squares whose stored fields differ from the native's answer
   public static final boolean DEV_ACTION_EVAL_CHECK = bool("devActionEvalCheck", false); // dev: evaluate every batched transition set again on the game thread at apply time and count / log disagreements with the worker's result
   public static final boolean DEV_ACTION_EVAL_UNIT_MULTIPLIER = bool("devActionEvalUnitMultiplier", false); // dev: reproduce the bug fixed on 2026-09-22 (deferred postupdate ran at perObjectMultiplier 1, a reduced-simulation zombie counted each thump up to 16 times)
   public static final boolean SKIN_TRANSFORMS_PRECOMPUTE = bool("skinTransformsPrecompute", true); // the worker that updated a zombie's bones also multiplies them into the skin-transform sets its models used last frame, so the render phase finds them computed (AnimationPlayer.pzoptPrecomputeSkinTransforms; only with animBonesParallel)
   public static final boolean SKIN_PALETTE_PRECOMPUTE = bool("skinPalettePrecompute", true); // the worker also stores each precomputed skin-transform set as the shader palette buffer, so initMatrixPalette is one bulk copy (AnimatedModel override; needs skinTransformsPrecompute)
   public static final boolean SHADOW_PREP = bool("shadowPrep", true); // the worker that updated a zombie's bones also computes its shadow ellipse (pzopt.ShadowPrep); IsoZombie.calculateShadowParams serves it until the next update
   public static final boolean BONE_INDEX_CACHE = bool("boneIndexCache", true); // AnimationPlayer.getSkinningBoneIndex keeps the last few name -> index answers per skinning data (the shadow of every drawn character asked for three names per frame through two HashMap probes each)
   public static final boolean ECS_LOOKUP_FAST = bool("ecsLookupFast", true); // ECSComponent.getECSClass memoised per class, ECSEntity.tryGetECSComponent without the null checks and the reflective cast; IsoZombie keeps its StateMachineComponent in a field so getStateMachine / getActionContext / getCurrentState / isCurrentState / getVariable are field reads (stock: a class walk + a HashMap probe per call, ~5 % of the game thread on the Louisville horde)
   public static final boolean ACTION_CONDITION_FAST = bool("actionConditionFast", true); // CharacterVariableCondition: a boolean or int animation variable is compared from its typed getter instead of being printed to a string and parsed back per transition per frame (same result; floats and strings keep the stock path)
   public static final boolean CHAR_DRAW_PREP = bool("charDrawPrep", true); // the characters draw of the render phase: the on-screen zombies' draw data (ModelInstance.updateLights, ModelSlotRenderData.initModel + init, the camera record) built on the pass's own threads before the sequential enqueue pass (pzopt.CharDraw; FBORenderCell.renderMovingObjects, TextureDraw + ModelInstance overrides; the loop also skips the shadow call that returns without drawing for culled atlas zombies); players, animals, vehicles, fake-dead and hand-model zombies keep the stock path
   public static final boolean ZOMBIE_ATLAS_FAST = bool("zombieAtlasFast", true); // a culled zombie drawn as an atlas sprite (no active model, ~1,100 of the 1,600 on-screen objects of the Louisville horde) is drawn through a flat copy of its render chain (IsoZombie.pzoptRenderAtlas: the same tests, writes and sprite call as IsoZombie.render -> IsoGameCharacter.render, without the virtual chain); anything else falls back to the stock chain
   // game-thread offload pass (2026-09-27, docs/findings-gt-offload-2026-09-27.md)
   public static final boolean RENDER_PREP_PARALLEL = bool("renderPrepParallel", true); // each on-screen character's sun visibility (capsule shadows) and water proximity (reflections) computed on the frame workers while the game thread composites, read where the character is drawn (pzopt.RenderPrep)
   public static final boolean PPL_PACK_PARALLEL = bool("pplPackParallel", true); // pixelLight: the lattice levels of a frame packed on the frame workers, each into its own block (pzopt.PixelLight)
   public static final boolean PPL_TORCH_NEAR_CHUNK = bool("pplTorchNearChunk", true); // pixelLight: the torch reach tested once per chunk level, the per-square test only in a chunk a torch reaches
   public static final boolean SCHEDULER_CLASSIFY_PARALLEL = bool("schedulerClassifyParallel", true); // the update scheduler's per-object simulation level computed on the frame workers, the buckets filled by the game thread in list order (MovingObjectUpdateScheduler.startFrame)
   public static final boolean ANIMAL_LOS_FAST = bool("animalLosFast", true); // IsoAnimal.updateLOS: a zombie whose spotted() call can only clear spottedChr and tick lastAlerted (an animal that does not flee zombies, or a zombie farther than 10 squares at the stock distance) is counted instead of called, the ticks applied in one exact step before the next call that can act (the same fold as PR #35's far-zombie skip)
   public static final boolean ANIMAL_LOS_SNAPSHOT = bool("animalLosSnapshot", false); // IsoAnimal.updateLOS (with animalLosFast) scans one per-frame snapshot of the zombies and players (pzopt.AnimalLosSnapshot) instead of walking the cell's whole object set per animal: 10 % of the game thread on the Louisville horde. Zombie positions are the frame's first animal update's (an intended edit)
   public static final boolean WEATHER_PARTICLES_PARALLEL = bool("weatherParticlesParallel", false); // the weather particle cells built on the frame workers
   public static final boolean BAKE_PREP_PARALLEL = bool("bakePrepParallel", false); // chunk-level bake preparation (render lists, occlusion) on the frame workers (pzopt.BakePrep)
   public static final boolean ZOMBIE_STATS_FOLD = bool("zombieStatsFold", true); // the zombies' distance statistics (walked / ran / crawled) summed in the zombies' order through the update loop and written back once after it, the achievement check once per statistic instead of once per zombie (pzopt.ZombieStats; bitwise the same totals)
   public static final boolean AO_CONTEXT_PARALLEL = bool("aoContextParallel", true); // ambient occlusion / sun shadows: the bake's world masks (vegetation, trees, exterior, walls, far column heights) of every bake of a frame computed at once on the frame workers in ChunkAo.flush instead of one by one inside each bake
   public static final boolean TRANSLUCENT_ORDER_CACHE = bool("translucentOrderCache", true); // the per-frame translucent pass keeps each level's merged, sorted square order and reuses it while the level's three square lists are unchanged (pzopt.TranslucentOrder)
   public static final boolean DEV_TL_ORDER_CHECK = bool("devTlOrderCheck", false); // dev: translucentOrderCache, every reused order compared with the stock merge and sort (gt_offload=)
   public static final boolean TILE_RECORD_PARALLEL = bool("tileRecordParallel", false); // the per-frame translucent tile pass recorded per chunk on the frame workers (pzopt.TileRecord / DrawRecorder; SpriteRendererStates + IOpenGLState overrides route a recording thread's draws and GL state to its own list) and spliced into the frame's draw list in stock order; objects a worker may not draw are drawn by the game thread at their place in the stream
   public static final boolean TILE_RECORD_ASYNC = bool("tileRecordAsync", true); // tileRecordParallel: every level's units handed to the workers in one batch after the chunk composite, recorded while the game thread draws the players, moving objects, water and attachments; the first translucent pass joins (false: one blocking batch per level's pass)
   public static final boolean TILE_RECORD_WALLS = bool("tileRecordWalls", true); // tileRecordParallel: walls, door frames and windows (IsoGridSquare's wall lighting, cutaway shaders) recorded on the workers too (false: only plain tiles and light switches; the wall path stays on the game thread)
   public static final boolean DEV_TILE_RECORD_SERIAL = bool("devTileRecordSerial", false); // dev: tileRecordParallel's phase A1, every chunk recorded through its recorder on the game thread (the splice machinery and its overhead without workers)
   public static final boolean DEV_TILE_RECORD_DEFER_ALL = bool("devTileRecordDeferAll", false); // dev: tileRecordParallel, every object deferred to the game thread (exercises the splice's defer path)
   public static final int DEV_DRAW_LIST_CHECK = integer("devDrawListCheck", 0); // dev: tileRecordParallel, every Nth frame the stock translucent pass runs first and is rolled back, then the recorded one, compared entry by entry (tile record line of the periodic log)
   public static final boolean VIS_POLY_ASYNC = bool("visPolyAsync", true); // the vision cone's shadow polygon computed on its own thread from the top of the tile render, taken in VisibilityPolygon2.renderMain (pzopt.VisPolyAsync)
   public static final boolean LOS_LIGHT_PREFETCH = bool("losLightPrefetch", false); // the lazy per-square lighting refresh of the squares the player's line-of-sight pass reads, done ahead on the frame workers (one task per chunk level, room / meta hooks deferred; pzopt.LosPrefetch)
   public static final boolean DEV_SCHED_CHECK = bool("devSchedCheck", false); // dev: schedulerClassifyParallel, every worker classification compared with the game thread's own (mismatches in gt_offload=)
   public static final boolean DEV_OUTLINE_TIMING = bool("devOutlineTiming", false); // dev: occluded outlines, every 10 s the hidden mesh / atlas quad counts, the end pass's render-thread time and the frame time with it on / off
   public static final int DEV_OUTLINE_VIEW = integer("devOutlineView", 0); // dev: occluded outlines, 1 = paint the stencil codes instead of the contour (hidden red, seen zombie green, other characters blue)
   public static final int DEV_OUTLINE_ALTERNATE = integer("devOutlineAlternate", 0); // dev: ms; occluded outlines switch off and on every period (a within-run A/B, read with devOutlineTiming)
   public static final int DEV_GT_ALTERNATE = integer("devGtAlternate", 0); // dev: ms; the keys in devGtAlternateKeys switch off and on every period (a within-run A/B: harness/gtab.py)
   public static final String DEV_TORCH_SOURCE_CYCLE = string("devTorchSourceCycle", ""); // dev: torchSource variants taking turns in one run (off,on,hold0,item,stale,noclamp), a per-variant report line (pzopt.TorchSource)
   public static final int DEV_TORCH_SOURCE_PERIOD = integer("devTorchSourcePeriod", 2000); // dev: ms per devTorchSourceCycle variant
   public static final boolean DEV_TORCH_SOURCE_VIEW = bool("devTorchSourceView", false);
   public static final boolean DEV_PPL_FRAME_LOG = bool("devPplFrameLog", false); // dev: pixelLight's per-frame state as handed over and as drawn -> pzopt-pplframes.out (PixelLight.FrameLog)
   public static final boolean DEV_VIS_BLINK_TRACE = bool("devVisBlinkTrace", false); // dev: squares whose native visibility bits change and change back within 3 frames, a log line per frame with any (pzopt.VisBlink)
   public static final int DEV_TORCH_SOURCE_PUSH = integer("devTorchSourcePush", 0); // dev: the native's lens pushed this many hundredths of a square along the look (the wall clamp rig)
   public static final boolean DEV_TORCH_SOURCE_CHECK = bool("devTorchSourceCheck", false); // dev: every fast lens solve checked against the reference path (the game's own helpers); counts in the torch source stats line
   public static final boolean TORCH_SOURCE_SELF_SHADOW = bool("torchSourceSelfShadow", false); // torchSource + pixelLight: the carrier's body shades their own carried light (a lantern at the side leaves the other side dark); compiled into the chunk composite at start-up
   public static final int TORCH_SOURCE_BODY_PCT = integer("torchSourceBodyPct", 22); // torchSourceSelfShadow: the body's radius, hundredths of a square
   public static final boolean TORCH_SOURCE_FAST = bool("torchSourceFast", true); // torchSource: the item chain's fixed part cached per model instance, one sin / cos for the world mapping (false: the game's helpers every frame) // dev: torchSource draws the lens (yellow), the beam (orange) and the native's position (cyan)
   public static final String DEV_GT_ALTERNATE_KEYS = string("devGtAlternateKeys", ""); // dev: comma list of the offload keys the alternation switches (or "all")
   public static final boolean DEV_GT_ABBA = bool("devGtAbba", true); // devGtAlternate: on, off, off, on periods instead of on, off (cancels a linear scene trend)
   public static final int CHAR_DRAW_THREADS = Math.max(1, integer("charDrawThreads", 14)); // threads of the characters draw pre-pass pool (pzopt.CharDraw; clamped to cores - 2): the ~480 zombies' draw data must finish inside the chunk bakes, eight threads left the game thread waiting 0.2 ms a frame, twelve 0.13
   public static final boolean DEV_SIM_CHECKSUM = bool("devSimChecksum", false); // dev: one line per frame in Zomboid/pzopt-sim.out hashing every zombie's position, target, action state and animation state after postupdate (pzopt.SimChecksum, harness/simdiff.py)
   public static final String THREAD_NICE = string("threadNice", ""); // pzopt.ThreadNice rules "<comm prefix>=<nice|idle|batch>,...": lower the CPU priority of JIT / GC / background threads (empty = off)
   public static final boolean PROFILE_HANDSHAKE = bool("profileHandshake", true); // GameThreadProfile samples with Thread.getStackTrace (handshake with the game thread only) instead of ThreadMXBean.getThreadInfo (a global ThreadDump safepoint per sample in Java 25)
   /** devUiDiff=Type,Type: uiProfile logs what changed between consecutive renders of those top-level UI elements (D lines in pzopt-ui.out). */
   public static final String DEV_UI_DIFF = "," + string("devUiDiff", "") + ",";
   /** pzopt.UiRetained: record each top-level UI element's draw commands and replay them while nothing can have changed it. */
   public static final boolean UI_RETAINED = bool("uiRetained", true);
   /** pzopt.MapStreets: world-map street labels reuse each street's UI length per view and its translated name, and sort without indexOf. */
   /** KahluaTableImpl: a class table remembers what its metatable walk finds per key until any class table changes. */
   public static final boolean LUA_INDEX_CACHE = bool("luaIndexCache", true);
   /** LuaCompiler: string constants of every compiled Lua chunk are interned (table lookups hit on identity). */
   public static final boolean LUA_INTERN_CONSTANTS = bool("luaInternConstants", true);
   /** UIElement: prerender / render / update calls to an empty Lua function (a single RETURN) are skipped. */
   public static final boolean LUA_SKIP_EMPTY = bool("luaSkipEmpty", true);
   /** media/lua/client/pzopt/pzopt_ui_fast.lua: vanilla-identical fast paths for the inventory windows (only while their functions are vanilla). */
   public static final boolean UI_LUA_FAST = bool("uiLuaFast", true);
   /** pzopt.UiTicks: every top-level UI element's 100 ms Lua update on its own phase instead of all in one frame. */
   public static final boolean UI_TICK_STAGGER = bool("uiTickStagger", true);
   /**
    * Java mod compatibility (pzopt.ModCompat, 2026-10-01): auto = a Java mod that patches a method we edited switches
    * off the features that method holds (unless the mod is on ModCompat.KNOWN); report = only log it
    * (Zomboid/pzopt/mod-compat.txt); off = no scan. Default by modProfile: report (performance), auto (compatibility).
    */
   public static final String MOD_PROFILE = string("modProfile", "performance");
   public static final String MOD_COMPAT = string("modCompat", MOD_COMPAT_DEFAULT);
   /**
    * uiRetained: UI elements a mod draws (a render / prerender / class function from a mod file) replay too; off = stock
    * rate for them. Default by modProfile: on (performance), off (compatibility).
    */
   public static final boolean UI_RETAINED_MODS = bool("uiRetainedMods", !MOD_PROFILE_COMPAT);
   /** dev: pzopt_ui_fast.lua skips its "loaded from the game's own file" test (the behaviour before 2026-10-01), for the fixture A/B. */
   public static final boolean DEV_UI_FAST_NO_ORIGIN = bool("devUiFastNoOrigin", false);
   /**
    * Lua called on a frame worker (a mod's Lua reached from a batched zombie's update, pzopt.LuaGate): the call runs on
    * the game thread (a void call in the update batch joins its Lua replay; others wait for the game thread at its next
    * task or join) instead of on the worker, where Kahlua's one stack broke ("Lua code called from the wrong thread").
    */
   public static final boolean LUA_WORKER_GATE = bool("luaWorkerGate", true);
   public static final boolean MAP_STREET_MEMO = bool("mapStreetMemo", true);
   /** pzopt.MapStreets: the world map reuses the whole street-label layout while its view, options, style and streets are unchanged. */
   public static final boolean MAP_STREET_CACHE = bool("mapStreetCache", true);
   /** WorldMapVisited: the map's visited-area texture rebuilt a row at a time (the same bytes; stock re-derived the row span per texel). */
   public static final boolean MAP_VISITED_FAST = bool("mapVisitedFast", true);
   /** devMapVisitedCheck: the stock loop re-checks every texel mapVisitedFast wrote. */
   public static final boolean DEV_MAP_VISITED_CHECK = bool("devMapVisitedCheck", false);
   /** devMapStreetCheck: lay the street labels out on every reuse too and log when the kept layout differs. */
   public static final boolean DEV_MAP_STREET_CHECK = bool("devMapStreetCheck", false);
   /** uiRetained: fresh renders from mouse movement alone, per element and second (clicks, wheel and keys are immediate). */
   public static final int UI_HOVER_HZ = integer("uiHoverHz", 60);
   /** uiRetained: longest time between fresh renders of an element whose output stayed identical. */
   public static final int UI_RETAINED_MAX_MS = integer("uiRetainedMaxMs", 1000);
   /** uiRetained: elements whose fresh render costs less than this (us) keep the stock rate. */
   public static final int UI_RETAINED_CHEAP_US = integer("uiRetainedCheapUs", 10);
   /** uiRetained: child elements (buttons, list panels ...) are recorded and replayed too. */
   public static final boolean UI_RETAINED_CHILDREN = bool("uiRetainedChildren", true);
   /** uiRetained: child elements whose fresh render costs less than this (us) are always rendered fresh. */
   public static final int UI_RETAINED_CHILD_CHEAP_US = integer("uiRetainedChildCheapUs", 2);
   /** uiRetained: identical fresh renders in a row before an element backs off (animations keep their stock rate). */
   public static final int UI_RETAINED_STREAK = integer("uiRetainedStreak", 4);
   /** uiRetained: an element backs off only when its output has not changed for this long (slow fades keep the stock rate). */
   public static final int UI_RETAINED_STATIC_MS = integer("uiRetainedStaticMs", 250);
   /** uiRetained: after a click, key or wheel every element keeps the stock rate this long (its consequences arrive). */
   public static final int UI_RETAINED_SETTLE_MS = integer("uiRetainedSettleMs", 300);
   /** devUiRetainedCheck: render replays fresh anyway and count the ones that would have been stale. */
   public static final boolean DEV_UI_RETAINED_CHECK = bool("devUiRetainedCheck", false);
   /** devHotsaveTiming: IsoMetaGrid.save logs its serialize / write time per file (the transfer hitch). */
   public static final boolean DEV_HOTSAVE_TIMING = bool("devHotsaveTiming", false);
   /** pzopt.HotsaveWarmup: the loader serialises the map metadata three times into a scratch buffer, so the first hot save runs JIT-compiled code. */
   public static final boolean HOTSAVE_WARMUP = bool("hotsaveWarmup", true);
   public static final boolean UI_PROFILE = bool("uiProfile", INSTRUMENT); // pzopt.UiProfile: exact UI update / render timing per frame and per top-level element (Zomboid/pzopt-ui.out); on in instrumented runs
   public static final boolean LUA_PROFILE = bool("luaProfile", INSTRUMENT); // pzopt.LuaProfile: the game-thread sampler also names the Lua function it caught (Zomboid/pzopt-lua.out); on in instrumented runs
   public static final String GC_MODE = string("gcMode", "g1"); // pzopt.GcChoice: the next launch's collector. g1 (default, the maintainer's decision 2026-09-23) | auto (G1 on gcG1Cores cores or fewer) | stock (the launcher's own ZGC; undoes our switch)
   public static final int GC_G1_CORES = integer("gcG1Cores", 4);
   /**
    * pzopt.GcChoice: the next launch's C2 compiles without speculative traps (-XX:PerMethodTrapLimit=0
    * -XX:PerBytecodeTrapLimit=0, marker -Dpzopt.jit=steady): every branch is compiled instead of pruning the ones the
    * profile never saw. A walk through the world deoptimized ~2,000 compiled methods per 100 s (unstable_if: a branch
    * never taken while profiling, then taken in a new street) and recompiled 16,600, a compiler thread busy for the whole
    * walk; without the traps the JIT used 0.43 cores instead of 0.83, same game-thread time (the flip, 2026-09-24).
    */
   public static final boolean JIT_STEADY = bool("jitSteady", true);
   public static final int GC_PAUSE_MS = integer("gcPauseMs", 0); // -XX:MaxGCPauseMillis added with the G1 switch (0 = G1's own 200 ms target)
   public static final String GC_HEAP = string("gcHeap", "auto").toLowerCase(java.util.Locale.ROOT); // pzopt.GcChoice: the next launch's -Xmx: auto (default: gcHeapAutoMb, or gcHeapAutoModsMb with gcHeapAutoMods mods or more) | game (the launcher's own, 3072 MB on Steam) | a size in MB; never more than half the RAM
   public static final int GC_HEAP_AUTO_MB = integer("gcHeapAutoMb", 4096); // gcHeap=auto without a big mod list (vanilla horde: ~2.8 GB live, the launcher's 3 GB 95 % full)
   public static final int GC_HEAP_AUTO_MODS = integer("gcHeapAutoMods", 30); // gcHeap=auto: this many enabled mods (heap peak ~+20 MB a mod: 66 mods filled 4 GB to 3.7 GB on the drive, a horde adds ~0.4 GB) (main menu or the last save, the larger) or more take gcHeapAutoModsMb
   public static final int GC_HEAP_AUTO_MODS_MB = integer("gcHeapAutoModsMb", 8192); // gcHeap=auto with a big mod list (132 mods: ~4 GB live, 4 GB heaps froze in Full GCs)
   public static final boolean LUA_GC_NOOP = bool("luaGcNoop", true); // src/lua/shared/pzopt/pzopt_lua_gc.lua: Lua collectgarbage("collect"/"step") returns at once instead of System.gc() (a ~280 ms Full GC per call)
   public static final boolean GC_HEAP_FIXED = bool("gcHeapFixed", false); // pzopt.GcChoice: -Xms = -Xmx (the heap is never resized)
   public static final boolean GC_PRE_TOUCH = bool("gcPreTouch", false); // pzopt.GcChoice: -XX:+AlwaysPreTouch (heap pages committed at boot)
   public static final String JIT_MODE = string("jitMode", "auto"); // pzopt.JitGovernor: tiered (stock) | c1play (C2 excluded from world start on) | c2idle (C2 threads at SCHED_IDLE from world start, Linux; no measured gain) | auto (default: c1play on jitC1Cores cores or fewer, stock above)
   public static final int JIT_C1_CORES = integer("jitC1Cores", 4);
   public static final boolean LIGHTING_VISION_PARALLEL = bool("lightingVisionParallel", false); // pzopt.VisionBatch (off: on the CPU-bound 4-core Dell the workers got no core and the game thread did the batch itself, no fps change): the vision tests of every dirty chunk level on the FrameBatch workers before LightingJNI.updateChunk
   public static final boolean DEV_VISION_CHECK = bool("devVisionCheck", false); // dev: recompute each precomputed vision value on the game thread and count mismatches (log line)
   public static final int RENDER_CHUNK_PREWARM = integer("renderChunkPrewarm", -1); // render chunks (texture + depth + FBO) created when the world loads, so first bakes in play find the pool filled (0 = off, -1 = auto: the most render chunks in use at once in the previous session + 5 %, kept in Zomboid/pzopt/renderchunk-pool.txt; 96 on the first run; a Dell walk used 114)
   public static final int RENDER_CHUNK_TOP_UP = integer("renderChunkTopUp", 32); // keep this many free render chunks for the current zoom's texture size, made a few a frame ahead of need (0 = off)
   public static final int RENDER_CHUNK_TOP_UP_PER_FRAME = Math.max(1, integer("renderChunkTopUpPerFrame", 2)); // renderChunkTopUp: new render chunks per frame at most
   /**
    * Render-thread GL calls that return a value wait for NVIDIA's driver thread to drain its queue (the render thread's
    * late frames on the Rosewood drive: ~40 % of their samples). glNoSync answers them without the driver: texture names
    * from a pool filled in batches while the render thread waits anyway (pzopt.GlNames), the corpse atlas's program from
    * ShaderHelper's record (pzopt.GlState), the tree append's target from the bound render chunk, no framebuffer status
    * check for render-chunk FBOs after the first complete one (TextureFBO).
    */
   public static final boolean GL_NO_SYNC = bool("glNoSync", false);
   public static final int GL_NAME_POOL = Math.max(8, integer("glNamePool", 256)); // glNoSync: texture names kept ready
   public static final boolean WEATHER_NO_GLGET = bool("weatherNoGlGet", true); // WeatherParticleDrawer takes the current shader program from ShaderHelper's record instead of glGetInteger (a driver sync per call)
   public static final boolean DEV_GL_STATE_CHECK = bool("devGlStateCheck", false); // dev: also query the driver and count disagreements with the recorded program
   public static final boolean PROPERTY_SURFACE_NOALLOC = bool("propertySurfaceNoAlloc", true); // PropertyContainer.initSurface walks its entries without allocating a capturing lambda per call (C1 code, jitMode)
   public static final boolean CHUNK_MAP_FAST = bool("chunkMapFast", true); // IsoChunkMap.getGridSquareDirect without helper calls, calculateZExtentsForChunkMap over the grid width instead of length x length
   public static final boolean SAVE_CELL_ASYNC = bool("saveCellAsync", true); // no effect since 42.21 (its requestSaveCell only queues the cell key, the MapCollisionData thread saves throttled); on 42.20 ZombiePopulationManager.requestSaveCell snapshots without saveLock (held by the MapCollisionData thread through each native cell write) and keeps one pending write per cell (a drive unloads dozens of chunks of the same cell)
   public static final boolean WORLD_SOUND_FAST = bool("worldSoundFast", true); // addSound walks only the loaded chunk grid; FishSchoolManager.addSoundNoise skips a repeat of an identical call in the same game minute (the house alarm adds its sound every frame)
   public static final boolean KEYBOARD_FRESH = bool("keyboardFresh", true); // GameKeyboard reads the keyboard poll it swaps in (stock: the previous one, a frame behind mouse and pad)
   public static final boolean INPUT_THREAD = bool("inputThread", System.getProperty("os.name", "").startsWith("Windows")); // Windows: GLFW window/event owner independent of rendering, subframe drain before simulation
   public static final boolean INPUT_LATCH = bool("inputLatch", true); // without inputThread, request a fresh render-thread event/input poll before the game input swap (pzopt.InputLatch)
   public static final int INPUT_LATCH_WAIT_US = integer("inputLatchWaitUs", 1500); // longest the game thread waits for that poll
   public static final boolean FRAME_START_GATE = bool("frameStartGate", false); // the game thread waits for pipeline room before it reads input, not after building the frame
   public static final int GPU_MAX_FRAMES = integer("gpuMaxFrames", 0); // frames the render thread lets queue behind the GPU (GL fence after the swap), 0 = the driver's own limit
   public static final boolean CURSOR_LATCH = bool("cursorLatch", false); // the game-drawn cursor (lock cursor to window) moved to the newest pointer position right before the frame is drawn
   public static final int AIM_HOLD_MS = integer("aimHoldMs", 150); // right-mouse hold before the player aims (stock 150: shorter taps open the context menu)
   public static final boolean VBLANK_LOCK = bool("vblankLock", false); // with vsync on: the game starts each frame a measured time before the display's vblank (GLX_NV_delay_before_swap)
   public static final int VBLANK_LOCK_MARGIN_US = integer("vblankLockMarginUs", 1000); // margin over the p95 of go -> swap
   public static final boolean VSYNC_ADAPTIVE = bool("vsyncAdaptive", false); // with vsync on: swap interval -1 (GLX/WGL_EXT_swap_control_tear), a late frame tears instead of waiting for the next refresh
   public static final boolean REFLEX_BOOST = bool("reflexBoost", false); // NVIDIA: hold "prefer maximum performance" (NVML PowerMizer, released when the game exits) while a world is loaded
   public static final boolean REFLEX_SLEEP = bool("reflexSleep", false); // Reflex-style just-in-time sleep before the input sample (pzopt.LowLatency)
   public static final int REFLEX_QUEUE_US = integer("reflexQueueUs", 500); // the frame queue reflexSleep aims for
   public static final int REFLEX_CAP_FPS = integer("reflexCapFps", 0); // with reflexSleep: frame starts no faster than this (-1 = auto with vsync: refresh - refresh^2/3600, 0 = off)
   public static final boolean OCCLUSION_SKIP_LIGHTING_ONLY = bool("occlusionSkipLightingOnly", true);
   public static final boolean LIGHT_INFO_CHUNK_GATE = bool("lightInfoChunkGate", true);
   public static final boolean LIGHT_INFO_ONCE_PER_FRAME = bool("lightInfoOncePerFrame", true);
   public static final int FILE_THREADS = Math.max(1, integer("fileThreads", Math.max(4, Runtime.getRuntime().availableProcessors() / 2)));
   public static final int FILE_INFLIGHT = Math.max(1, integer("fileInflight", 4 * FILE_THREADS));
   public static final boolean PNG_PAETH_FAST = bool("pngPaethFast", true);
   public static final boolean DEPTH_MAP_FAST = bool("depthMapFast", true);
   public static final String TILE_DEPTH_FIX = string("tileDepthFix", "auto").trim().toLowerCase(java.util.Locale.ROOT); // issue #38: fitted depth for the tiles that share a generic box (pzopt.TileDepthFix); auto = while pixelLight / AO / sun shadows / reflections read the depth, on, off
   public static final boolean TILE_DEPTH_CANOPIES = bool("tileDepthCanopies", true); // tileDepthFix for the store canopies too: fitted sloped slabs, depth only on the sprite's own texels (2026-09-29: canopies closer to the features-off look, no frame cost, ~+200 ms once at load)
   public static final boolean TILE_DEPTH_CEILING = bool("tileDepthCeiling", false); // measured and off (2026-09-29): every depth texture's top rows clamped under the ceiling at upload (boot); no visible change on the canopies
   public static final boolean ZONE_EDGE_PREFILTER = bool("zoneEdgePrefilter", true);
   public static final boolean ELECTRICITY_LEVEL_RANGE = bool("electricityLevelRange", true);
   public static final boolean LAZY_OPTIONS_SCREEN = bool("lazyOptionsScreen", true); // the options screen built when first opened, key bindings at once (Lua)
   public static final boolean PREVIEW_CLIPS = bool("previewClips", false); // the options tabs' before / after GIFs in the preview panel; the tick box in each tab's header saves it at once (Lua)
   public static final boolean LUA_EVENT_PROFILE = bool("luaEventProfile", false); // measurement: time every Lua event handler (pzopt.LuaEventProfile)
   public static final boolean DEV_ELECTRICITY_CHECK = bool("devElectricityCheck", false); // dev: count the stock 64-level walk in the same call (instrumented runs)
   public static final int FILE_INFLIGHT_LOAD = Math.max(FILE_INFLIGHT, integer("fileInflightLoad", 128));
   public static final int FILE_THREADS_WAIT = Math.max(1, integer("fileThreadsWait", Runtime.getRuntime().availableProcessors()));
   public static final int TEXTURE_BUFFER_MB = Math.max(1, integer("textureBufferMb", 50));
   public static final boolean PARALLEL_DEPTH_MAPS = bool("parallelDepthMaps", true);
   public static final boolean LOADER_CPU_FIXES = bool("loaderCpuFixes", true);
   public static final boolean SCRIPT_PARSER_FAST = bool("scriptParserFast", true);
   public static final boolean FMOD_ASYNC = bool("fmodAsync", true);
   public static final boolean NO_LOAD_FADE = bool("noLoadFade", true);
   /** new game: show click-to-start as soon as loading is done instead of after the 33 s intro text. */
   public static final boolean NO_INTRO_WAIT = bool("noIntroWait", true);
   /** the loading screen enters the world as soon as it is loaded instead of waiting for "click to start". */
   public static final boolean NO_CLICK_TO_START = bool("noClickToStart", true);
   /** single player: a black screen instead of the loading screen, then the world with no fade from black. */
   public static final boolean NO_LOADING_SCREEN = bool("noLoadingScreen", true);
   /** single player: the initial chunks load nearest-first and the world is entered once the 7 x 7 around the player are in. */
   public static final boolean CENTER_FIRST_LOAD = bool("centerFirstLoad", true);
   public static final int CENTER_FIRST_ENTRY_RADIUS = integer("centerFirstEntryRadius", 3); // chunks around the player handed over before the first world frame (0..3)
   /** exit saves keep the view around the player; Continue shows it with chunks popping in (else the stock loading screen). */
   public static final boolean RESUME_SHOT = bool("resumeShot", true);
   /** how much of the world the exit shot keeps: floors | buildings | world | full (pzopt.ResumeShot.beginCapture) */
   public static final String RESUME_SHOT_DETAIL = string("resumeShotDetail", "full").trim().toLowerCase(java.util.Locale.ROOT);
   public static final boolean BOOT_PUMP = bool("bootPump", true);
   public static final boolean EARLY_MODELS = bool("earlyModels", true);
   public static final boolean LUA_PRECOMPILE = bool("luaPrecompile", true);
   public static final boolean PRELOAD_ANIM_SETS = bool("preloadAnimSets", true);
   public static final boolean TILE_DEF_PRELOAD = bool("tileDefPreload", true);
   public static final boolean SKIP_ID_CHECKS = bool("skipIdChecks", true);
   public static final boolean VORONOI_FAST = bool("voronoiFast", true);
   public static final boolean EARLY_TILE_PACKS = bool("earlyTilePacks", true);
   public static final boolean AOT_CACHE = bool("aotCache", true);
   /** dev: let a harness run drive the aotCache cycle (normally inert there: run.sh owns the launcher JSON). */
   public static final boolean DEV_AOT_CACHE_HARNESS = bool("devAotCacheHarness", false);
   public static final boolean ANIM_CLIP_CACHE = bool("animClipCache", true);
   /** auto = honour options.ini (frameRate / uncappedFPS); true / false force the frame cap off / on for a run. */
   public static final String UNCAPPED_FPS = string("uncappedFps", "auto");
   /** Run key: > 0 locks game and menus at exactly this fps for this launch, nothing persisted (A/B runs; the uncappedFps restore dance can leave another cap). */
   public static final int FRAME_CAP_FPS = integer("frameCapFps", 0);
   /** Variable refresh (pzopt.Pacing, Display): per-frame step / swap timestamps to Zomboid/pzopt-pacing.out (harness/pacing.py). */
   public static final boolean PACING_LOG = bool("pacingLog", INSTRUMENT);
   /** Variable refresh (pzopt.Vrr): auto = act while the kernel reports VRR on (Linux), on = always, off = never. */
   public static final String VRR = string("vrr", "auto");
   /** While VRR is active, keep the frame cap inside its range (refresh - refresh^2/3600) when the player's cap is higher or uncapped. */
   public static final boolean VRR_CAP = bool("vrrCap", true);
   /** > 0: the VRR cap in fps instead of the formula. */
   public static final int VRR_CAP_FPS = integer("vrrCapFps", 0);
   /** macOS on Apple silicon: present through Metal (pzopt.MacPresent) for ProMotion / Adaptive-Sync timing; on | off (auto = on). */
   public static final String MAC_PRESENT = string("macPresent", "off");
   /** macOS: an OpenGL 4.1 core context instead of Apple's legacy 2.1 one, with the fixed-function shim (pzopt.CoreGl); next launch. */
   public static final boolean MAC_GL_CORE = bool("macGlCore", true);
   /** Rig: macGlCore's alpha test as a uniform-driven discard in every fragment shader (the core profile has no GL_ALPHA_TEST); false = no alpha test at all (wrong picture). */
   public static final boolean CORE_ALPHA_INJECT = bool("devCoreAlphaInject", true);
   /** Rig: macGlCore logs GL errors per frame and every unemulated legacy call (first stack trace each). */
   public static final boolean DEV_CORE_GL_TRACE = bool("devCoreGlTrace", false);
   /**
    * macGlCore: GL timer queries (the overlay's GPU load, present pacing's GPU timestamps). Off by default: on Apple's GL over
    * Metal a query per frame cost a third of the frame rate (spin route 106 -> 68 fps, 2026-10-01); the legacy context has none.
    */
   public static final boolean MAC_GL_TIMER_QUERIES = bool("macGlTimerQueries", false);
   /** macPresent: glFinish instead of glFlush before Metal reads the frame (only if the flush hand-off ever shows torn frames). */
   public static final boolean MAC_PRESENT_FINISH = bool("macPresentFinish", false);
   /** macPresent: CAMetalLayer drawables in flight (2 = one shown + one queued; 3 queues ~3 frames of latency at the panel's rate). */
   public static final int MAC_PRESENT_DRAWABLES = integer("macPresentDrawables", 2);
   /** macPresent: shift the game's frame starts so frames are ready just before their panel slot (latency; pzopt.MacPresent.steer). */
   public static final boolean MAC_PRESENT_PHASE = bool("macPresentPhase", true);
   /** macPresent + the borderless option: a native macOS fullscreen Space instead of a screen-sized borderless window. */
   public static final boolean MAC_NATIVE_FULLSCREEN = bool("macNativeFullscreen", true);
   /** Rig: every 1000th bridged frame (5 times), compare the IOSurface rows with the GL back buffer and log whether the picture reaches Metal upright. */
   public static final boolean DEV_MAC_PRESENT_CHECK = bool("devMacPresentCheck", false);
   /**
    * Hold each swap until step start + a high percentile of the recent step-to-ready times, so present gaps equal game-time
    * steps (capped only). off | cpu | gpu | gpufinish | auto (auto = gpu wherever GL timestamp queries exist, cpu only while VRR is active, off under the macOS Metal bridge); see pzopt.Pacing.
    */
   public static final String PRESENT_PACING = string("presentPacing", "auto");
   public static final int PRESENT_PACING_PCT = Math.max(50, Math.min(100, integer("presentPacingPct", 90)));
   public static final int PRESENT_PACING_MARGIN_US = Math.max(0, integer("presentPacingMarginUs", 200));
   /**
    * A borderless window covering the monitor is created as a GLFW monitor window at the desktop's own mode (no mode switch,
    * no auto-iconify), so the compositor sees a fullscreen window: KWin / Mutter / gamescope only switch variable refresh on
    * for fullscreen windows. auto = Linux (X11 / XWayland / Wayland), true = every platform, false = stock window.
    */
   public static final String BORDERLESS_FULLSCREEN = string("borderlessFullscreen", "auto");
   /**
    * The frame limiter parks the game thread until limiterSpinUs before the next step instead of spinning it through the
    * whole wait (a core at full clock for nothing: at a 120 fps cap half of the game thread's time on the flip). Default on
    * except on Windows, where a park wakes on the 1 ms timer tick.
    */
   public static final String TEX_COMPRESS = string("texCompress", "auto"); // textureCompression without the driver's CPU compressor (Mesa: the menu at 15-30 fps for up to 30 s after boot): auto = gpu where GL 4.3 compute runs, else worker; worker = file-pool workers encode BC3 (pzopt.TexCompress); gpu = workers build the levels, a compute shader encodes; driver = stock GL_COMPRESSED_RGBA
   public static final boolean TEX_COMPRESS_GPU = bool("texCompressGpu", true); // worker mode: textures made outside the asset pipeline encoded by the GPU (GL 4.3) instead of the driver
   public static final boolean TEX_COMPRESS_CACHE = bool("texCompressCache", false); // BC3 of every compressed pack page kept in ~/Zomboid/pzopt/texcache (deflated, ~330 MB): later boots skip PNG decode, mips and encode (pzopt.TexCache); the first boot encodes on the CPU to fill it
   public static final boolean TEX_COMPRESS_MEASURE_DECODER = bool("texCompressMeasureDecoder", true); // the BC3 encoders fit against this GPU's own decode palette, measured once at the first compressed texture (pzopt.TexBcPalette; GPUs round the interpolated entries their own way)
   public static final boolean TEX_COMPRESS_EARLY_FREE = bool("texCompressEarlyFree", true); // texCompress gpu: a raw-staged image's pixels freed on the worker right after the copy (the decoded-bytes budget the decoders wait on drops at once)
   public static final boolean TEX_COMPRESS_GPU_MIPS = bool("texCompressGpuMips", true); // texCompress gpu: workers stage only the raw level 0, the GPU builds ImageData's mip chain (bit-exact) and premultiplies; a pack page skips its stock initMipMaps
   public static final int TEX_COMPRESS_STAGING_MB = integer("texCompressStagingMb", 128); // texCompress gpu: persistently mapped buffer the workers write the levels into (0 = the render thread copies them); 64 left a tenth of a boot's textures waiting for room on the flip, 128 almost none
   public static final int TEX_COMPRESS_STAGING_WAIT_MS = integer("texCompressStagingWaitMs", 50); // a worker waits this long for staging room before it builds the levels on the CPU
   public static final int TEX_COMPRESS_HQ_THRESHOLD = integer("texCompressHqThreshold", 16); // CPU encoder: squared RGB error a pixel above which a block gets the HQ fit (0 = every block)
   public static final boolean TEX_COMPRESS_HQ = bool("texCompressHq", true); // BC3 fit: principal axis + least squares (true) or the inset bounding box (false, ~5x cheaper, ~2 dB worse)
   public static final boolean DEV_TEX_COMP_TIMING = bool("devTexCompTiming", false); // console line every 2 s while textures load: worker / render-thread / driver counts and times
   public static final boolean LIMITER_SLEEP = bool("limiterSleep", !System.getProperty("os.name", "").startsWith("Win"));
   /** limiterSleep: how long before the step the park ends; the stock loop spins the rest (the game thread's timer slack is 1 ns on Linux). */
   public static final int LIMITER_SPIN_US = Math.max(0, integer("limiterSpinUs", System.getProperty("os.name", "").startsWith("Win") ? 1500 : 200));
   /**
    * Hybrid CPUs (Zen 5 + Zen 5c, Intel P + E, Apple P + E): which cores the game's threads run on (pzopt.CorePlacement).
    * off (stock: the OS decides) | auto (background threads on the efficient cores; the game and render threads there too
    * while they keep the frame cap, moved to the fast cores when they fall short) | efficient (everything on the efficient
    * cores) | performance (game and render threads on the fast cores, everything else on the efficient ones).
    * Windows: existing modes remain stock; opt-in dual-ccd needs a dual-CCD CPU (two disjoint shared L3 groups, any sizes, 6+6
    * or 8+8 cores, SMT on or off). The primary CCD (the larger L3, else CCD0) runs game/render and synchronous
    * frame/draw/lighting/input/visibility work together; the other CCD runs known background workers. frameThreads and
    * charDrawThreads are capped at the primary CCD's physical cores minus two. STW GC and unknown/ambiguous OS
    * descriptions retain access to both CCDs. Requires one unrestricted processor group. No process pinning. Unnamed
    * native GL threads stay wide; unsupported topology or native API failure disables placement.
    */
   public static final String CORE_PLACEMENT = string("corePlacement", "auto");
   /**
    * The machine's logical CPUs, read when Config loads (boot, before corePlacement narrows any thread's affinity: Java's
    * availableProcessors() follows the calling thread's mask, so a pool sized later from the game thread got 8 of 24).
    */
   public static final int CPUS = Runtime.getRuntime().availableProcessors();
   /**
    * AMD GPUs on Linux: the GPU clock level while a world is on screen (pzopt.GpuPstate): off | auto (the lowest fixed
    * level whose GPU time per frame fits the frame cap, automatic clocks otherwise) | standard | min_sclk | min_mclk | peak.
    */
   public static final String GPU_PSTATE = string("gpuPstate", "auto");
   /** gpuPstate=auto: a level holds while the frame's GPU time p90 stays under this share of the frame interval. */
   public static final int GPU_PSTATE_FIT_PCT = Math.max(30, Math.min(100, integer("gpuPstateFitPct", 95)));
   /** The vision cone's edge blur sums its 25 taps once per vision texel instead of once per world pixel (pzopt.VisBlur; same result). */
   public static final boolean VIS_BLUR_REDUCE = bool("visBlurReduce", true);
   /** The lighting thread parks to its next update instead of LWJGL's sleep + yield-spin (pzopt.LightingSync). */
   public static final boolean LIGHTING_SYNC_PARK = bool("lightingSyncPark", true);
   /**
    * corePlacement: the CPUs background threads may use instead of every efficient core (a list like "4-7,16-19"; empty =
    * the efficient class). Linux only; rejected rather than misinterpreted by Windows dual-ccd placement.
    */
   public static final int CORE_ISOLATE = Math.max(0, integer("coreIsolate", 0)); // corePlacement on a CPU whose cores are alike: reserve this many physical cores (best boost rank first) for the game and render threads, their SMT siblings idle, every other thread on the rest (0 = off)
   public static final String CORE_BACKGROUND_CPUS = string("coreBackgroundCpus", "");
   /** Linux only: CPUs the game / render / GL threads use on the fast class (a list; empty = the fast class). Rejected by Windows dual-ccd. */
   public static final String CORE_CRITICAL_CPUS = string("coreCriticalCpus", "");
   /** corePlacement=auto: the game step's p90 (share of the frame interval) above which the game and render threads move to the fast cores. */
   public static final int CORE_PROMOTE_PCT = Math.max(30, Math.min(100, integer("corePromotePct", 85)));
   /** corePlacement=auto: back to the efficient cores when the work, scaled by the measured fast/slow speed ratio, fits this share of the interval. */
   public static final int CORE_DEMOTE_PCT = Math.max(20, Math.min(95, integer("coreDemotePct", 70)));
   /** corePlacement=auto: the least time (ms) the game and render threads stay on the fast cores before the governor may move them back. */
   public static final int CORE_HOLD_MS = Math.max(250, integer("coreHoldMs", 3000));
   public static final boolean PACK_INDEX = bool("packIndex", true);
   public static final boolean ITEM_PARAM_SWITCH = bool("itemParamSwitch", true);
   public static final boolean DUMP_ITEMS = bool("dumpItems", false);
   public static final int BOOT_FILE_THREADS = Math.max(1, integer("bootFileThreads", Math.max(4, Runtime.getRuntime().availableProcessors() - 6)));
   public static final int LOAD_WORKERS = clampWorkers(integer("loadWorkers", Math.max(WORKERS, Runtime.getRuntime().availableProcessors() / 2)));
   public static final boolean SHADER_CACHE = bool("shaderCache", true);
   public static final boolean MIPMAP_ARRAYS = bool("mipmapArrays", true);
   public static final boolean PUDDLE_CACHE = bool("puddleCache", true); // FBORenderCell.renderPuddles reuses packed puddle vertices per chunk level (pzopt.PuddleCache)
   public static final int PUDDLE_CACHE_FRAMES = integer("puddleCacheFrames", 60); // backstop rebuild interval of a cached puddle batch, staggered per chunk
   public static final boolean PUDDLE_JIGGLE_DEPTH = bool("puddleJiggleDepth", true); // a cached puddle batch's depth follows the camera jiggle as stock's packing does (it kept its build frame's: up to 1.7e-4 off against the puddle's 1e-4 lift, puddles flickered through flat roofs, 2026-10-05)
   public static final boolean PUDDLE_EARLY_Z = bool("puddleEarlyZ", true); // puddle shaders take their depth from the vertex, no gl_FragDepth write: early depth test rejects occluded wet ground (media/shaders/pzopt_puddles_*)
   public static final boolean RAIN_SPLASHES_FAST = bool("rainSplashesFast", true); // splash starts by geometric skipping with a local generator instead of Rand.NextBool per idle square per frame (pzopt.RainSplashes)
   // --- render-resolution upscaling (docs/plan-upscalers.md, pzopt.RenderScale / pzopt.Upscaler) ---
   public static volatile String UPSCALER; // off | bicubic | fsr1 | dlss | xess: the world pass renders at upscalerQuality's fraction of the screen and is resolved to the screen by this upscaler; the UI, text and the stock screen shader stay native
   public static volatile String UPSCALER_QUALITY; // quality 67 % | balanced 59 % | performance 50 % | ultra 33 % | native 100 % (dlss: DLAA) of the screen size per axis
   public static volatile int UPSCALER_SCALE_PCT; // explicit render scale in percent (10..100) instead of upscalerQuality's preset; 0 = use the preset
   public static volatile int FSR_SHARPNESS_PCT; // RCAS sharpening after EASU as a percentage: 100 = the sharpest (0 stops of attenuation), 0 = 2 stops (the mildest); AMD ships 80-100 in its sample
   public static volatile boolean DLSS_SHARPEN; // dlss: the NGX sharpening flag (a mild extra sharpen; off = plain super resolution)
   public static volatile boolean UPSCALER_OBJECT_MV; // dlss / xess: characters and vehicles write their own motion vectors (a rect masked by the depth band) on top of the camera motion; false = camera motion only
   // --- dynamic resolution (docs/findings-dynamic-resolution-2026-10-03.md, pzopt.DynRes) ---
   public static volatile boolean DYN_RES; // the world's render scale follows the GPU time per frame so it fits the frame cap's interval; between dynResMinPct and dynResMaxPct, resolved by upscaler (dynResUpscaler when upscaler=off)
   public static volatile int DYN_RES_TARGET_PCT; // the share of the frame interval the GPU may fill (the controller's target; the rest is headroom for noise)
   public static volatile int DYN_RES_MIN_PCT; // the lowest render scale per axis
   public static volatile int DYN_RES_MAX_PCT; // the highest render scale per axis (100 = native; above: supersampling with fsr1 / bicubic when the GPU has room at the cap, up to what the offscreen texture holds)
   public static volatile int DYN_RES_FPS; // the frame rate the GPU time is held to; 0 = the frame cap in force (uncapped: the scale stays at dynResMaxPct)
   public static volatile String DYN_RES_CONTROLLER; // model (a filtered cost model: GPU ms = fixed + per-pixel x pixels, the scale solved from it) | pi (integral control on log GPU time) | step (stock-engine style: drop at once when over, creep up when under)
   public static volatile String DYN_RES_UPSCALER; // the resolve dynamic resolution uses when upscaler=off: taau (temporal, the steadiest picture while the size changes: 2026-10-03 seamlessness runs) | fsr1 | bicubic
   public static volatile int DYN_RES_STEP_PX; // render widths are multiples of this many pixels (fewer distinct sizes, steadier image); 1 = any width
   public static volatile int DYN_RES_UP_PER_MILLE; // the most the scale rises per frame, in thousandths
   public static volatile int DYN_RES_DOWN_PER_MILLE; // the most the scale falls per frame, in thousandths
   public static volatile int DYN_RES_DEADBAND_PCT; // a new target closer than this to the scale in use (and not over budget) is ignored
   public static volatile boolean DYN_RES_NATIVE_BYPASS; // at a frame scale of 100 % fsr1 / bicubic skip the resolve (the stock composite, no RCAS)
   public static volatile boolean DYN_RES_SHARPEN_RAMP; // fsr1 under dynRes: RCAS fades out towards native size (none at 100 %, the configured strength from 85 % down), so crossing the native bypass shows no jump in sharpness
   public static volatile boolean DYN_RES_BAKE_FEEDFORWARD; // model controller: the GPU cost of chunk bakes is a term of the model, and a frame that will bake the levels left waiting renders smaller for that frame only
   public static volatile boolean DYN_RES_STARVE_GATE;
   public static volatile boolean DYN_RES_BAKE_TERM; // model controller: each frame's chunk-bake count is a term of the cost model (a bake burst is explained, not taken for a heavier scene); the scale itself is solved for a frame without bakes
   public static volatile boolean DYN_RES_PROBE; // a second pinned at the lowest scale renders 8 frames 30 % larger, so the model can tell whether the pixels are what costs the time (persistent excitation)
   public static volatile boolean DYN_RES_CPU_AWARE;
   public static volatile int DYN_RES_CPU_TARGET_PCT; // when the game thread sets the pace: the share of its interval the GPU may fill (no noise headroom on top) // the GPU's budget is the longer of the cap's interval and the game thread's own time per frame (median of 16): a CPU-bound scene keeps its resolution
   public static volatile boolean DYN_RES_AXES_X; // dynResAxes=x: only the width changes (the frame's pixel fraction spent on the width, the height stays native; not with DLSS)
   public static volatile int DYN_RES_UP_DELAY_FRAMES; // a higher wish must hold this many frames before the scale rises (time hysteresis)
   public static volatile int DYN_RES_SIGMA_PCT; // model controller: the headroom kept under the interval, in hundredths of the robust spread of the GPU time
   public static volatile int DYN_RES_DRIFT_PER_MILLE; // model controller: how fast the cost terms may drift, in thousandths of the frame per sample
   // --- taau: the GLSL temporal upscaler (pzopt.Taau), upscaler=taau or dynResUpscaler=taau ---
   public static volatile int TAAU_MAX_FRAMES; // the most frames of history an output pixel accumulates (1 / the least weight of a new sample)
   public static volatile int TAAU_CLIP_PCT; // history clipped to the 3x3 input neighbourhood's mean +- this many hundredths of a standard deviation (YCoCg)
   public static volatile int TAAU_SAMPLE_SIGMA_PCT; // a new sample's weight falls off with its distance to the output pixel's centre with this sigma, in hundredths of an output pixel (smaller = sharper, slower to converge)
   public static volatile boolean TAAU_JITTER; // the sub-pixel jitter of the world pass (off: no new detail is gathered, A/B only)
   public static volatile boolean TAAU_JITTER_ADAPTIVE;
   public static volatile boolean TAAU_WARM_BYPASS;
   public static volatile int TAAU_WARM_EVERY; // the warm copy runs on every n-th native frame (it cost 0.24-0.36 ms a frame on the flip's 890M at 1080p every frame) // dynRes + taau at the native size (the bypass skips the resolve): the frame is copied into the history (one cheap pass) so a drop below 100 % starts from it instead of a fresh history // the jitter grows as the render scale falls: none at the screen size (crisp pixel art), full from 75 % down
   public static final int DEV_TAAU_VIEW = integer("devTaauView", 0); // dev: 1 the history weight, 2 the new sample's weight, 3 the clip distance // frames where the GPU kept up with the render thread (its time is the CPU's, not pixels) do not lower the scale
   public static final boolean DYN_RES_LOG = bool("dynResLog", INSTRUMENT); // one row per frame to Zomboid/pzopt-dynres.out (harness/dynres.py)
   public static final String DEV_DYN_RES_FORCE = string("devDynResForce", ""); // dev rig: the scale follows "lowPct,highPct,periodSec[,square|sine|ramp]" open-loop (no controller, no rate limit), so how visible a scale change is can be measured on a still scene
   public static final String DEV_DYN_RES_LOAD = string("devDynResLoad", ""); // dev rig: extra per-pixel GPU work in the world pass, "lowIters,highIters,periodSec[,square|sine|ramp]", so the controller's step response is measurable
   public static final boolean DEV_DLSS_GAPS = bool("devDlssGaps", false); // dev: GL timestamps at the DLSS hand-over and after the wait, matched with the evaluation's Vulkan start / end (the GL <-> Vulkan switch cost) in the dlss stats line
   public static final boolean DEV_UPSCALER_STOCK_VIS_BLUR = bool("devUpscalerStockVisBlur", false); // dev A/B: the view-cone blur keeps the stock unscaled displaySize under an upscaler (the 2026-09-23 "second view cone" bug)
   public static final boolean DEV_UPSCALER_LOG = bool("devUpscalerLog", false); // dev: log every upscaler state change, the shared-image import and the first evaluations
   public static volatile String DLSS_PRESET; // dlss: the render preset for every quality level: default (the driver's: transformer K / M / L), e or f (the older convolutional models, half the cost), j, k, l, m. e since 2026-09-23: at 5120x2160 K costs 1.5 ms a frame, more than the render scale saves
   public static volatile int DLSS_OUTPUT_PCT; // dlss: DLSS writes this percentage of the screen size (never below the render size; its cost follows the output pixels) and dlssOutputFilter finishes the upscale; 0 = the screen size. 67 since 2026-09-23: the configuration that beats no upscaler (+22 % in a GPU-bound 4K scene) with a sharper image than full-size DLSS K
   public static volatile String DLSS_OUTPUT_FILTER; // dlss with dlssOutputPct: bicubic = the stock screen shader samples the smaller output directly (no extra pass), fsr1 = EASU + RCAS first, rcas = RCAS alone at the DLSS output size, then the bicubic
   public static final boolean DLSS_FLUSH_AFTER_WAIT = bool("dlssFlushAfterWait", false); // dlss A/B: glFlush right after GL's wait on the DLSS-done semaphore (2026-09-23: no effect, the ~0.2 ms after an evaluation is not unflushed GL work)
   public static final boolean DLSS_FLUSH_AFTER_COMPOSITE = bool("dlssFlushAfterComposite", false); // dlss: also glFlush after the composite quad (A/B)
   public static final boolean DLSS_WAIT_OUTPUT_ONLY = bool("dlssWaitOutputOnly", false); // dlss: GL's wait names only the output image (A/B of the layout hand-back)
   public static final boolean DLSS_DIRECT_COLOR = bool("dlssDirectColor", true); // dlss: the world pass draws straight into the DLSS colour image (its colour attachment swapped for the frame) instead of a copy at the resolve (2026-09-23: same image, +3 %)
   public static volatile boolean DLSS_WATER_CURRENT; // dlss: the water pixels of the DLSS output are this frame's colour (the water shader tags them in the stencil; 2026-09-25: DLSS's history blend halved the ripples' motion, they have no motion vectors)
   public static volatile int DLSS_WATER_HISTORY_PCT; // dlssWaterCurrent: the share of the previous frame's water (camera-reprojected, clamped to the current neighbourhood) mixed into the current one, which averages out DLSS's sub-pixel jitter on the ripples; 0 = the current frame alone (2026-09-25, still river: frame-to-frame change 0 -> 0.257, 50 -> 0.197, 60 -> 0.183, 75 -> 0.152-0.168, stock 0.173; change over 1 s 0.649 at 60 like stock's 0.648, 75 reads 0.60-0.65; no smear walking the shore)
   public static final String DEV_DLSS_WATER_FILTER = string("devDlssWaterFilter", "catmull").trim().toLowerCase(java.util.Locale.ROOT); // dev A/B of dlssWaterCurrent: how the jittered frame is read back, catmull (default) / bilinear / raw (the texel under the pixel, no jitter compensation) / mask (the copied region in red) / none (the pass runs, DLSS output only)
   public static final boolean DLSS_PIPELINE = bool("dlssPipeline", false); // dlss: two image sets, the composite shows the previous frame's evaluation so GL never waits for the evaluation it just submitted (+1 frame of world latency)
   public static final boolean DLSS_AUTO_EXPOSURE = bool("dlssAutoExposure", false); // dlss: the NGX AutoExposure flag (a luminance reduction per frame; the input is LDR at exposure 1, so off by default since 2026-09-23)
   public static final boolean DLSS_DEPTH_INVERTED = bool("dlssDepthInverted", false); // dlss: the scene depth's larger values are nearer (the NGX DepthInverted flag)
   public static final boolean DLSS_JITTER = bool("dlssJitter", true); // dlss: draw the world with the Halton sub-pixel jitter DLSS accumulates from (false = no jitter, an A/B)
   public static final float DLSS_JITTER_SIGN = integer("dlssJitterSign", 1) < 0 ? -1.0F : 1.0F; // dlss: the sign the viewport offset is reported to NGX with (1 or -1, an A/B of the convention)
   public static final float DLSS_MV_SIGN = integer("dlssMvSign", 1) < 0 ? -1.0F : 1.0F; // dlss: the sign of the motion vectors (1 = current to previous position, NGX's convention; -1 the other way)
   public static final boolean DEV_STENCIL_PROBE = bool("devStencilProbe", false); // dev: the stencil at the cutaway centre at named points of the frame, logged every 2 s (pzopt.StencilProbe; stalls)
   public static final boolean DEV_REACH_CHECK = bool("devReachCheck", false); // dev: stock tree cutaway, plus occlusion queries round the inside passes of every see-through tree treeCutawayReach would keep baked (reachViolations must stay 0)
   public static final boolean EDGE_TEST_FAST = bool("edgeTestFast", true); // IsoMovingObject.separate asks pzopt.EdgeFast instead of 42.21's lambda-based IsoGridSquare.isBlockedTo (same result)
   public static final boolean DEV_EDGE_FAST_CHECK = bool("devEdgeFastCheck", false); // dev: every edgeTestFast call also asks the game's isBlockedTo and counts differences (console every 10 s)
   public static final boolean TREE_CUTAWAY_REACH = bool("treeCutawayReach", true); // a see-through tree whose sprite stays clear of the cutaway's marked ellipse stays in the bake (FBORenderCell, pzopt.CutawayMask)
   public static final int TREE_CUTAWAY_REACH_PX = integer("treeCutawayReachPx", 256); // treeCutawayReach: a tree leaves the bake this many pixels (tileScale 2) before its sprite box reaches the ellipse
   public static final boolean TREE_REBAKE_LAZY = bool("treeRebakeLazy", true); // 42.21 cutaway while driving: re-bake only when a tree leaves / rejoins the bake, rejoining deferred (FBORenderCell.checkTreeTranslucency)
   public static final int TREE_REBAKE_LINGER_MS = integer("treeRebakeLingerMs", 0); // treeRebakeLazy: longest a tree back from the cutaway stays per frame before its level re-bakes (0 = at the next check; a 3 s stay measured slower, per-frame trees cost more than the re-bake)
   public static final boolean TREE_APPEND = bool("treeAppend", true); // a new chunk's trees are drawn into the finished neighbour textures they reach instead of re-baking those textures (tree pass, issue #5)
   // floor blood decals (pzopt.BloodDecals, 2026-09-29, docs/findings-blood-decals-2026-09-29.md)
   public static final String BLOOD_BAKE = string("bloodBake", "gpu").trim().toLowerCase(java.util.Locale.ROOT); // how a chunk bake draws the floor splats: gpu = one instanced draw per chunk level from a cached per-chunk splat list (stock placement, colour, density, light per square); cpu = the same cache as sprites without the per-splat state (the exact stock picture, any GL); off = stock's walk of nine chunks' queues per bake
   public static final boolean BLOOD_APPEND = bool("bloodAppend", true); // a new splat is drawn into the finished chunk textures it lands in (its chunk's and the neighbours' within a tile of the edge, which stock never re-baked) instead of re-baking its chunk level; hidden by the density option: nothing at all
   public static final int BLOOD_SETTLE_SEC = integer("bloodSettleSec", 30); // with bloodAppend: a level that got appended splats re-bakes once, this many seconds after its first append (stock's exact order: blood under flat floor objects); 0 = never
   public static final boolean BLOOD_FADE_FIX = bool("bloodFadeFix", false); // splats pushed out at the 1,000-per-chunk cap are dropped at the next bake (stock keeps drawing them: their fade counter only runs down per bake, so in chunk textures they never fade)
   public static final int BLOOD_APPEND_BIAS_PCT = integer("bloodAppendBiasPct", 2); // bloodAppend: the floor plane is moved this % of one square's depth toward the camera for the depth test (a wall's foot within that of the floor gets the blood over it: 1 % = 1/800 of a level)
   public static final int BLOOD_REBAKE_COALESCE_MS = integer("bloodRebakeCoalesceMs", 250); // bloodAppend: a splat that must re-bake its level (grass, a body or an item under it) waits this long, so the splats a hit throws over the next frames share one re-bake; 0 = at once (stock)
   public static final boolean BLOOD_APPEND_PLANTS = bool("bloodAppendPlants", true); // bloodAppend: where grass, bushes or floor-attached plants (alone on their squares) lie under a new splat, append it and draw those plants into the texture again over it (stock bakes them after the blood), through the bake's own object path with the depth test; off = those floors re-bake
   public static final boolean BLOOD_APPEND_VEGETATION = bool("bloodAppendVegetation", false); // bloodAppend: also append where vegetation, a body or an item lies under the splat (stock bakes those over the blood; appended, the blood covers them); off = those splats re-bake their level as stock
   public static final int DEV_BLOOD_WET_VIEW = integer("devBloodWetView", 0); // dev: the wet blood layer shows 1 its coverage (magenta), 2 the reflection found (orange + the colour), 3 the sun term, 4 the film normal
   public static final boolean DEV_BLOOD_APPEND_TINT = bool("devBloodAppendTint", false); // dev: appended splats draw solid green (where the appends land and what hides them)
   public static final boolean PUDDLE_VBO = bool("puddleVbo", true); // cached puddle batches kept in per-chunk-level GL buffers, jiggle as a matrix translation (pzopt.PuddleVbo)
   public static final boolean RAIN_TILES = bool("rainTiles", true); // weather particles rendered once as a template and drawn once per screen cell (pzopt.RainTiles)
   public static final int VBO_BATCH_KB = integer("vboBatchKb", 1024); // VBORenderer element buffer (4 = stock): rain particles flush every 28 quads at 4 KB
   public static final boolean VBO_FAST_QUADS = bool("vboFastQuads", true); // VBORenderer.addQuad writes the four vertices with one position advance
   public static final boolean FOG_PASS = bool("fogPass", true); // ImprovedFog as one batch into a scaled, depth-copied fog buffer (pzopt.FogPass)
   public static final int FOG_SCALE_PCT = integer("fogScalePct", 25); // fog buffer size per axis, % of the viewport
   public static volatile boolean AO; // ambient occlusion on the static world: floors, walls, furniture (pzopt.AmbientOcclusion)
   public static final String AO_MODE = string("aoMode", "chunk").toLowerCase(java.util.Locale.ROOT); // chunk: baked into the chunk textures (pzopt.ChunkAo, no per-frame cost); screen: a per-frame pass on the scene (pzopt.AmbientOcclusion)
   public static final boolean AO_SKIP_SLOW_FRAMES = bool("aoSkipSlowFrames", true); // chunk AO: under a cap, computes per frame follow the slack the last frame left (after a frame that missed the cap: aoSlowFrameComputes)
   public static final int AO_SLOW_FRAME_COMPUTES = integer("aoSlowFrameComputes", 0); // chunk AO: deferred computes after a frame that missed the cap (0 = one every 8 such frames)
   public static final int AO_BAKE_BUDGET = integer("aoBakeBudget", 4); // chunk AO: textures whose AO is computed inside their bake per frame at most (new ones, changed objects); cheaper than a deferred one
   public static final boolean AO_ARRIVAL_IN_BAKE = bool("aoArrivalInBake", true); // chunk AO: a texture's first AO is computed inside its bake whatever the frame's slack or aoBakeBudget (the bake scheduler bounds those bakes); false = it queues like the rest (driving fast, grass showed its AO shading late)
   public static final int AO_COMPUTE_BUDGET = integer("aoComputeBudget", 4); // chunk AO: deferred computes per frame at most (under a cap: fewer, by the last frame's slack) (over the bake budget, neighbour refreshes); the rest wait
   public static final boolean AO_CHUNK_FLIP = bool("aoChunkFlip", true); // chunk AO: the bake writes texture rows top-down (FlipY); false = bottom-up
   public static final boolean AO_REUSE = bool("aoReuse", true); // the AO buffer is kept while the camera, zoom and chunk textures stay the same (no AO work on such frames)
   public static volatile int AO_SCALE_PCT; // AO buffer size per axis, % of the viewport (25..100)
   public static final boolean AO_EDGE_SHADE = bool("aoEdgeShade", true); // chunk AO: a texel beside a deeper surface (0.05+ squares behind, 2-4 texels out) takes the darker AO of the two (a leaf's soft edge before a shaded wall showed a light outline round every leaf, Discord 2026-10-04)
   public static final boolean AO_EDGE_AWARE = bool("aoEdgeAware", false); // measured and off (2026-09-29): the AO multiplied in with a depth-aware read of its half-resolution term; no visible change on the canopies (max zoom shows the mips), small elsewhere
   public static volatile int AO_RADIUS_PCT; // how far occluders reach, % of a square
   public static final int AO_STRENGTH_PCT = integer("aoStrengthPct", 100); // the one darkening strength before 2026-09-25; now only the fallback of the three per-surface strengths below while they are unset
   // darkening strength per receiving surface, % (100 = the computed occlusion), picked in the AO kernel from the normal it snaps
   // to the ground or a wall plane: floors (and flat ground), walls, trees (aoStrengthVegetationPct: neither, a tree's card or
   // crown squares, and the shade crowns cast below them) and plants (neither, on a square with a bush, grass or flowers; both
   // chunk mode only), everything else (furniture, fences, stairs, roofs' edges)
   public static volatile int AO_STRENGTH_FLOOR_PCT;
   public static volatile int AO_STRENGTH_WALL_PCT;
   public static volatile int AO_STRENGTH_OBJECT_PCT;
   public static volatile int AO_STRENGTH_VEGETATION_PCT;
   public static volatile int AO_STRENGTH_PLANT_PCT; // bushes, grass, flowers (issue #40, 2026-09-29); unset: the vegetation strength (one key for all plants before)
   public static final boolean AO_PLANT_LEAF_OCCLUSION = bool("aoPlantLeafOcclusion", false); // chunk AO: a plant's leaves shade the leaves around them (every leaf texel's horizon saw its neighbours: dark speckled bushes, issue #40); false = plants take AO from the ground, walls and objects only
   public static final boolean AO_ROOF_SKIP = bool("aoRoofSkip", true); // chunk AO: no ambient occlusion on roof tiles: a roof sprite's depth is a staircase snapped to the floor / wall planes; the horizon kernel shaded every riser and aoEdgeShade spread it onto the row above, dark horizontal bands across a roof (the sun term skips roofs for the same reason; flip house report 2026-10-05)
   public static final boolean DEV_AO_ROOF_VIEW = bool("devAoRoofView", false); // dev (with devAoView=1): the texels aoRoofSkip takes for roof tiles dark
   public static final int AO_THICKNESS_PCT = integer("aoThicknessPct", 60); // how deep a surface is assumed to be behind what the depth shows, % of a square
   // soft sun shadows (pzopt.SunShadow), computed by the chunk AO kernel into the same kept term: live keys (Enhancements tab)
   public static volatile boolean SUN_SHADOWS; // walls, trees, fences, furniture cast soft shadows of the sun onto the outdoor world (the time of day moves them)
   public static volatile int SUN_SHADOW_STRENGTH_PCT; // how much of the outdoor daylight a full shadow takes away, % (on a clear day; clouds and rain thin it)
   public static volatile int SUN_SHADOW_SOFTNESS_PCT; // penumbra: the sun's angular radius, % of 3 degrees (the real sun is ~9 %)
   public static volatile boolean SUN_SHADOW_CHARACTERS; // sunShadows: characters outdoors cast the shadow of their body (ten bone capsules) onto the scene, per frame (pzopt.CapsuleShadow)
   public static volatile boolean SUN_SHADOW_VEHICLES; // sunShadows: vehicles outdoors cast a sun shadow (two body capsules and the cabin) onto the scene, per frame
   public static volatile boolean SUN_SHADOW_TORCHES; // sunShadows: characters and vehicles also cast soft shadows from the torches and headlights in reach, at any hour (pzopt.CapsuleShadow)
   public static final int SUN_SHADOW_TORCH_PCT = integer("sunShadowTorchPct", 85); // how much of a torch's light a character's shadow takes away in the dark, %
   public static final boolean SUN_SHADOW_ATLAS = bool("sunShadowAtlas", true); // sunShadows, characters: zombies drawn as atlas sprites (no model) cast one upright capsule's shadow
   public static final boolean SUN_SHADOW_SILHOUETTE = bool("sunShadowSilhouette", true); // sunShadows, characters / animals / vehicles: the shadow takes the caster's drawn shape (limbs, hair, weapons, bags, an animal's legs, a car's body): after the moving objects are drawn, each caster's sun ray is marched through the scene depth inside its bounding capsule (pzopt.CapsuleShadow); where the penumbra grows wider than a limb the capsule model takes over; off: the capsules alone, drawn before the characters (2026-09-25)
   public static final boolean SUN_SHADOW_PASS_LATE = bool("sunShadowPassLate", true); // sunShadowSilhouette: the caster pass right before the god rays and the fog (its scene depth read beside theirs; translucent objects receive too); off: right after the moving objects
   public static final int SUN_SHADOW_SILHOUETTE_STEPS = integer("sunShadowSilhouetteSteps", 24); // sunShadowSilhouette: depth samples per pixel along the ray's stretch inside the caster's bounding capsule (at most)
   public static volatile boolean SUN_SHARE_WALL_HEIGHT; // sunShadows, characters / animals / vehicles: whether a wall or fence between a caster and the sun shades it (and so takes its sun shadow away, SunShadow's march) goes by the edge's height measured from the sprite (as sunShadowWallCut): the ray passes over a low fence; off: any wall / fence edge it crosses at any height shades the caster (a hoppable fence up to ~5 squares off under an evening sun put the shadow out on part of every lap round a spot) (live)
   public static volatile boolean SUN_SHADOW_WALL_CUT; // sunShadows, characters / animals / vehicles: the shadow ends at the walls and fences standing between the ground behind them and the sun (their own shadow is there already; it went through fences): the opaque wall / fence edges along each caster's shadow (pzopt.CapsuleShadow, cached per square and sun step, height from the sprite), up to 4 a caster, tested per pixel; off: drawn on whatever lies behind (live)
   public static volatile boolean SUN_SHADOW_MESHES; // sunShadows: characters and animals cast the shadow of their own model: right after its draw the model is drawn again from the sun into its tile of a depth atlas (pzopt.ShadowAtlas, 128 x 128 a caster), the shadow pass reads it with a percentage-closer soft shadow (every limb, hair, clothes, bags, weapons, an animal's legs and tail); off: capsules (+ the depth shell of sunShadowSilhouette) (live)
   public static final boolean SUN_SHADOW_MESH_VEHICLES = bool("sunShadowMeshVehicles", true); // sunShadowMeshes: vehicles too (their body, wheels, doors, bars as drawn); off: their three capsules
   public static final int SUN_SHADOW_MESH_BURST = integer("sunShadowMeshBurst", 6); // sunShadowMeshes, sunShadowRate N a second: redraws come in bursts of at least this many (the atlas flush's fixed cost shared), N a second on average
   public static final int SUN_SHADOW_MESH_BUDGET = integer("sunShadowMeshBudget", 12); // sunShadowMeshes, sunShadowRate N a second: at most this many casters drawn again from the sun a frame (new ones always); the rest keep an earlier pose at their position now
   public static final int SUN_SHADOW_MESH_FRAME_BUDGET = integer("sunShadowMeshFrameBudget", 32); // sunShadowMeshes, sunShadowRate=frame: at most this many casters drawn again from the sun a frame (new ones and players always); past it the casters take turns (a drawn one waits casters / budget frames)
   public static final boolean SUN_SHADOW_MESH_SAME_FRAME = bool("sunShadowMeshSameFrame", true); // sunShadowMeshes, sunShadowRate=frame: the atlas drawn right before the caster pass, so the shadow shows the pose the model shows this frame; off: at the screen composite, read by the next frame's pass (one frame behind the model)
   public static final boolean SUN_SHADOW_LAMP_MESHES = bool("sunShadowLampMeshes", true); // sunShadowMeshes + sunShadowTorches: a character's shadow from a torch or a headlight is its own model too, drawn from the lamp (a perspective view into its atlas tile, the two strongest lamps a character); off: capsules
   public static final int SUN_SHADOW_LAMP_MESH_NEAR_PCT = integer("sunShadowLampMeshNearPct", 100); // sunShadowLampMeshes: the lamp view's shadow is used as is within this many hundredths of a square of the caster
   public static final int SUN_SHADOW_LAMP_MESH_FAR_PCT = integer("sunShadowLampMeshFarPct", 300); // sunShadowLampMeshes: and has faded into the capsules' soft shadow by this distance (a low headlight stretched the legs' shadow into thin torn strands, Discord 2026-10-04); 0 = the lamp view everywhere
   public static final int SUN_SHADOW_LAMP_SPREAD_PCT = integer("sunShadowLampSpreadPct", 100); // sunShadowTorches: a torch / headlight shadow's quad widens with its cone up to this % of the caster's width near it; the rest fades out at the quad's sides (flip, 8 zombies in a torch beam: 100 ~260 us, 200 ~420-700, 400 ~500-830: the quads are long)
   public static final int SUN_SHADOW_LAMP_BUDGET = integer("sunShadowLampBudget", 8); // sunShadowLampMeshes: at most this many lamp views drawn again a frame (new ones and a lamp that moved round its caster first); the rest keep an earlier pose at their position now
   public static final boolean SUN_SHADOW_SHELL_LIMBS = bool("sunShadowShellLimbs", true); // sunShadowSilhouette: a person's limbs and an animal's legs come from the depth shell alone (the capsules keep the torso and head, an animal's trunk): 2 capsule tests a pixel instead of 10; off: all ten capsules as well
   public static volatile String SUN_SHADOW_RATE; // sunShadowMeshes: how often a caster's shadow pose is drawn again: frame = every frame, in the same frame as the model (2026-09-30, recommended: 15 a second read as choppy next to the character; desktop 40 casters +91 us a frame against +27); a number = that many times a second, one frame behind the model (15: the 2026-09-27 behaviour) (live)
   public static volatile int SUN_SHADOW_RATE_HZ; // sunShadowRate as a rate: 0 every frame, else times a second
   public static volatile int SUN_SHADOW_STOCK_FADE_PCT; // sunShadows: how much of the stock blob shadow under a character or vehicle fades where it casts a real sun shadow (x its sun share; overcast, at night and indoors the stock blob stays), % (live)
   public static volatile boolean SUN_SHADOW_ANIMALS; // sunShadows: animals cast sun shadows too (their bounding capsule from every bone of their skeleton; with sunShadowSilhouette their drawn shape) (live)
   public static final boolean SUN_SHADOW_MARCH = bool("sunShadowMarch", false); // sunShadows, characters: per pixel, a receiver the static world hides from the sun takes no second shadow (8 depth taps); off: per caster (its own sun share)
   public static final int SUN_SHADOW_CHARACTER_REACH = integer("sunShadowCharacterReach", 12); // how far a character's shadow may reach along the ground, squares (a low sun; the last 30 % fades)
   public static final int SUN_SHADOW_CHARACTER_LOD_PCT = integer("sunShadowCharacterLodPct", 150); // past this far from a character's feet (% of a square) its shadow is the bounding capsule's alone (the penumbra is wider than a limb there); 10000 = always the ten body capsules
   public static final int SUN_SHADOW_CHARACTER_PCT = integer("sunShadowCharacterPct", 100); // how dark a character's sun shadow is, % of the sun shadows' strength
   public static final int SUN_SHADOW_LENGTH_PCT = integer("sunShadowLengthPct", 800); // how far a shadow may reach from its caster, % of a square (within the neighbour chunks' textures: at most 8 squares)
   public static final int SUN_SHADOW_THICKNESS_PCT = integer("sunShadowThicknessPct", 100); // how deep a caster is assumed to be behind what the depth shows, % of a square
   public static final boolean SUN_SHADOW_TREES = bool("sunShadowTrees", true); // sunShadows: trees take part as crown proxies (an ellipsoid per tree from its sprite, pzopt.ChunkAo): a crown shades what the sun ray crosses it to (the ground, walls, its own trunk and lower crown, its far side); the flat tree card no longer casts (a line or nothing, depending on the sun) nor takes striped shade; off: trees neither cast nor take sun shadows (before 2026-09-25)
   public static final int AO_TREE_CANOPY_PCT = integer("aoTreeCanopyPct", 25); // chunk AO: the sky the crowns hide, optical depth % per square of crown straight above (times the vegetation AO strength, wherever it lands): a tree's trunk and lower crown, the ground under a tree; 0 = none (before 2026-09-25)
   public static final int SUN_SHADOW_CANOPY_PCT = integer("sunShadowCanopyPct", 35); // sunShadowTrees: foliage optical depth of a crown for the sun, % per square of crown the ray crosses (a big crown is ~6 squares through)
   public static final boolean SUN_SHADOW_BARE_FAR = bool("sunShadowBareFar", true); // sunShadows: a texture with only floor in its 3 x 3 chunks still takes the sun term when a wall, solid object, upper floor, roof or tree stands within the far field's reach (4 chunks): a low sun's long shadow across open ground no longer stops on a bare chunk's edge; off: bare by the 3 x 3 chunks alone (before 2026-09-27)
   public static volatile boolean SUN_SHADOW_TREE_CARDS; // sunShadowTrees: a tree casts the shadow of its own sprite (trunk, branches and leaves as drawn, one continuous shadow from the foot) on a card turned to face the sun (pzopt.TreeSilhouette), softened by distance through the silhouette's mipmaps; the crown proxy stays for the tree's own shading and the sky under it; off: the crown proxies cast (2026-09-25) (live)
   public static final int SUN_SHADOW_TREE_OPACITY_PCT = integer("sunShadowTreeOpacityPct", 90); // sunShadowTreeCards: how much sun a covered texel of a tree's silhouette holds back (leaves let some through), %
   public static final int SUN_SHADOW_TREE_REACH = integer("sunShadowTreeReach", 3); // sunShadowTreeCards: trees this many chunks around a texture whose shadow reaches it take part (a low sun's long tree shadows)
   public static final int SUN_SHADOW_STEPS = integer("sunShadowSteps", 24); // samples along each texel's march towards the sun
   public static final int SUN_AZIMUTH_DEG = integer("sunAzimuthDeg", 0); // turns the sun's path (east at 6 h, south at noon, west at 18 h) clockwise seen from above
   public static final int SUN_MAX_ELEVATION_DEG = integer("sunMaxElevationDeg", 55); // the sun's height at noon (Kentucky in autumn: ~50)
   public static final int SUN_MIN_ELEVATION_DEG = integer("sunMinElevationDeg", 2); // below this the shadows fade out (12 before the far-field march: they would have been cut at the neighbour chunks)
   public static final boolean SUN_SHADOW_FAR = bool("sunShadowFar", true); // sunShadows: past the near march (the neighbour chunks' depth), a coarse march over the squares' column heights out to sunShadowFarSquares: a low sun's long shadows
   public static final int SUN_SHADOW_FAR_SQUARES = integer("sunShadowFarSquares", 32); // how far the far-field march reaches, squares (at most ChunkAo.FAR_MARGIN; the last quarter fades) // below this the shadows fade out (they would reach past the neighbour chunks)
   public static final int SUN_COMPUTE_BUDGET = integer("sunComputeBudget", 1); // textures recomputed per frame because the sun moved a step (on screen first; none after a frame that missed the cap)
   public static final int SUN_STEP_DEG10 = integer("sunStepDeg10", 15); // tenths of a degree the sun moves before the shadows are computed again (the textures on screen first, a few a frame)
   public static final float DEV_SUN_HOUR = Float.parseFloat(string("devSunHour", "-1")); // dev: the sun stands at this hour whatever the game time (-1: the game's clock)
   public static final boolean DEV_SHADOW_ATLAS_DROP = bool("devShadowAtlasDrop", false); // dev: the sun draw keeps Core.DoPushIsoStuff's -0.48 model offset (the bones say the feet stand at the character's position without it)
   public static final boolean DEV_SHADOW_GL_GET = bool("devShadowGlGet", false); // dev: the caster pass reads the bound framebuffer back every frame (a glGet) instead of the TextureFBO.lastID cache, and logs where they differ
   public static final int DEV_SIL_COST = integer("devSilCost", 0); // dev: the caster pass (sunShadowSilhouette) with every fragment discarded at once (1) or no fragments at all (2): its fixed cost; 8 = the torch / headlight quads tinted (blue outside the lamp's light, red by its share)
   public static final int DEV_SHADOW_ATLAS_DUMP = integer("devShadowAtlasDump", 0); // dev: the Nth frame of the characters' sun shadow atlas into Zomboid/pzopt-shadow-atlas.pgm
   public static final int DEV_SUN_ALTERNATE = integer("devSunAlternate", 0); // dev: ms; the capsule shadow pass switches on and off every period (harness/contact/alt.py splits the frames' GPU time by it)
   public static final float DEV_SUN_HOUR_SPEED = Float.parseFloat(string("devSunHourSpeed", "0")); // dev: with devSunHour, the sun moves on at this many hours per real second (a sweep to watch the recompute waves)
   public static final int DEV_DETAIL_TOGGLE_PERIOD = integer("devDetailTogglePeriod", 0); // dev: ms; the detailed shadows (sunShadowMeshes, sunShadowTreeCards, the stock blob's fade, softness 25) and the 09-26 release's (capsules, crown ovals, the blob, softness 100) take turns every period, logged with the epoch (the Workshop card's clip)
   public static final int DEV_SHADOW_TIP_TOGGLE_PERIOD = integer("devShadowTipTogglePeriod", 0); // dev: ms; the characters' sun shadow quads end at the old place (the bounding capsule's axis end: the head's shadow cut flat under a low sun) and at the fixed one (its top) every other period, logged with the epoch (the 2026-10-02 before / after clip)
   public static final int DEV_CASTER_TRACE = integer("devCasterTrace", 0); // dev: every Nth frame, the local player's sun caster facts (position, facing, sun share, what the shade march hit, its atlas tile) into the console (the 2026-10-02 lost-shadow report)
   public static final int DEV_SUN_TOGGLE_PERIOD = integer("devSunTogglePeriod", 0); // dev: ms; sun shadows flip on / off every period (each switch bakes the chunk pictures again), logged with the epoch (the Workshop card's clip)
   // The real sky (pzopt.Sky, pzopt.CloudShadow; docs/findings-sky-2026-09-26.md), 2026-09-26
   public static final String SKY_PATH = string("skyPath", "astro").trim().toLowerCase(java.util.Locale.ROOT); // astro = the sun and moon where they really stand for the game's date, hour and latitude; arc = the fixed path of before (east 6 h, south noon at sunMaxElevationDeg, west 18 h), no moon
   public static final int SKY_LATITUDE_DEG = integer("skyLatitudeDeg", 0); // 0 = the season's latitude (38 N, Knox County); else this latitude
   public static final int SUN_STEP_MODE_ANGLE = integer("sunStepAngle", 1); // 1 = a new shadow step when the light has turned sunStepDeg10 from the last step's direction (any path); 0 = steps of the arc's hour angle (arc only)
   public static final boolean SUN_STALE_OFFSCREEN = bool("sunStaleOffscreen", false); // sun-step recomputes of textures off screen: false = only when they come on screen (a 1.5 deg step is invisible for the frames it takes); true = drained 1 a frame like before
   public static volatile boolean MOON_SHADOWS; // sunShadows: the moon casts the same shadows at night, as strong as its phase and height and the darkness of the sky allow
   public static volatile int MOON_SHADOW_PCT; // a full moon high in a dark sky: % of sunShadowStrengthPct
   public static volatile boolean CLOUD_SHADOWS; // sunShadows: soft shadows of the clouds drift over the sunlit (or moonlit) outdoors with the wind (the chunk composite; the shaders are patched at launch)
   public static volatile int CLOUD_OPACITY_PCT; // how much of the sun a cloud's thick core holds back, %
   public static volatile int CLOUD_SPEED_PCT; // how fast the cloud shadows drift, % of the wind at the clouds' height
   public static volatile int CLOUD_SCALE_PCT; // cloud size, % of the default (~1 km cumulus)
   public static final int CLOUD_HEIGHT = integer("cloudHeight", 400); // squares above the ground: the cloud shadows sit where the sun ray through the cloud lands (the direction is held while clouds are drawn: they move with the wind, not the sun)
   public static final boolean CLOUD_REPLACE_STOCK = bool("cloudReplaceStock", false); // cloudShadows: the stock screen-space cloud overlay is not drawn while cloud shadows are on
   public static final int CLOUD_FIELD_SIZE = integer("cloudFieldSize", 256); // texels per side of the cloud field (tiled; both layers)
   public static final float DEV_CLOUD_COVER = Float.parseFloat(string("devCloudCover", "-1")); // dev: cloud cover 0..1 for the cloud shadows whatever the weather (-1: the climate's)
   public static final int DEV_CLOUD_VIEW = integer("devCloudView", 0); // dev: 1 = the composite shows the cloud factor on every pixel (white = no change, 0.85 grey = a texture without share / culled), 2 = the direct-sun share alone
   public static final boolean DEV_CLOUD_TIMING = bool("devCloudTiming", false); // dev: GL timer queries around the chunk composite, logged every 5 s (composite us / frame with and without clouds)
   public static final boolean CLOUD_BINDLESS = bool("cloudBindless", true); // cloudShadows: the composite reads each chunk texture's kept term through a bindless handle (GL_ARB_bindless_texture; a uniform per draw) instead of a texture bind per draw (+30-50 us at 5K on NVIDIA: every bind between draws is a GPU state change); off or unsupported: binds
   public static final boolean CLOUD_TERM_MIPS = bool("cloudTermMips", true); // cloudShadows: the composite reads the direct-sun share from levels 1-2 of the kept term (built once per compute) instead of its full size (texture traffic); off: level 0
   public static final int CLOUD_TERM_LOD = integer("cloudTermLod", 1); // cloudTermMips: the level of the kept term the composite reads (explicit, no derivatives)
   public static final boolean CLOUD_CULL = bool("cloudCull", true); // cloudShadows: chunk textures no cloud reaches this frame skip the cloud work in the composite (a coarse max map of the field, per texture on the game thread)
   public static final int DEV_CLOUD_SKIP = integer("devCloudSkip", 0); // dev (cost split): 1 = no kept-term tap (a constant share), 2 = no detail tap (no erosion), 4 = the kept term bound once a frame (wrong picture: the cost of per-draw binds); bits
   public static final int DEV_CLOUD_ALTERNATE = integer("devCloudAlternate", 0); // dev: ms; cloud shadows switch on and off every period (the frames' GPU time split by it)
   // god rays (pzopt.GodRays, docs/findings-god-rays-2026-09-27.md): light shafts in the haze and the dust of rooms, sun and moon
   public static volatile boolean FOLIAGE_SWAY; // grass, bushes and trees sway in the wind while they stay baked in the chunk textures (the chunk composite looks each texel up through the wind's displacement; pzopt.Sway)
   public static volatile int FOLIAGE_SWAY_PCT; // foliage sway: how far the plants bend, %
   public static volatile int FOLIAGE_SWAY_TAPS; // foliage sway: 1 = texels move inside their plant's outline only, 2..4 = + 1..3 upwind probes so a plant's leading edge moves over what is behind it
   // issue #41: with the game's own "Wind sprite effects" option on, plants stay baked and foliage sway moves them (the stock
   // option draws every tree, bush and grass tuft per frame: a forest at max zoom 244 -> 154 fps on the flip); pzopt.Sway.windHandoffBegin
   public static volatile boolean WIND_SPRITE_SWAY;
   public static volatile int DEV_SWAY_VIEW; // dev: foliage sway views (composite)
   public static final int DEV_SWAY_ALTERNATE = integer("devSwayAlternate", 0); // dev: ms; the composite's sway switches on and off every period (frame-time A/B within one run; the bakes keep writing the attributes)
   public static final boolean DEV_SWAY_ALTERNATE_ALL = bool("devSwayAlternateAll", false); // dev: the alternation's off half also bakes without sway attributes (the bake side's cost within one run)
   public static final int DEV_SWAY_GAIN_PCT = integer("devSwayGainPct", 100); // dev: exaggerates the sway for inspection
   public static final boolean DEV_SWAY_FLIP_Y = bool("devSwayFlipY", false); // dev: the bake's fragment rows counted from the FBO's top instead of its bottom
   public static final String DEV_SWAY_DUMP_DIR = string("devSwayDumpDir", "").trim(); // dev: write each sway twin's sources there (tools/ShaderRegs.java compares the programs the driver builds)
   public static final int DEV_SWAY_SKIP = integer("devSwaySkip", 0); // dev: composite cost bisection (compile time), bits: 4 no gust noise, 8 one fixed-point step inside plants, 32 DLSS motion written as zero (no motion math), 64 the sway function returns at once (a twin / variant that binds and fetches like the stock one)
   public static final boolean DEV_SWAY_VARIANT_STOCK = bool("devSwayVariantStock", false); // dev: the sway variant program is a plain copy of the game's composite and gets no uniforms or textures (what switching programs costs)
   public static final boolean SWAY_DEPTH_CHECK = bool("swayDepthCheck", true); // foliage sway: a texel's sway attribute counts only while its depth is still the plant's (something drawn over it later by a program that does not write the attribute)
   public static final boolean SWAY_FLOOR_EXACT = bool("swayFloorExact", true); // foliage sway: floors (flat roofs too) bake with sway off, their depth exactly as stock writes it; the rigid flag (lowest DEPTH16 bit cleared) moved half their texels a step nearer and puddles flickered on corrugated flat roofs (2026-10-05, Discord)
   public static final boolean SWAY_BINDLESS = bool("swayBindless", false); // foliage sway: the composite variant reads its attribute and mask textures through bindless handles (ARB_bindless_texture; launch)
   public static final String SWAY_GUST = string("swayGust", "sines").trim().toLowerCase(java.util.Locale.ROOT); // foliage sway: the gust field, sines (two travelling waves, no fetch) | texture (32 x 32 periodic value noise)
   public static final int SWAY_AUX_BUDGET_MB = integer("swayAuxBudgetMb", 160); // foliage sway: VRAM for the plants' attribute textures above which the ones not shown for 3 s are freed (their chunk re-bakes when it is shown again)
   public static final boolean SWAY_MV = bool("swayMotionVectors", true); // foliage sway: with DLSS, the swaying pixels get their own motion vectors (without them DLSS's history damped the sway ~7x)
   public static final boolean SWAY_MV_FOLD = bool("swayMvFold", true); // foliage sway: its DLSS motion added (and zeroed) in DLSS's own depth + motion pass instead of a full-screen pass of its own
   public static final boolean SWAY_TWIN_REMAP = bool("swayTwinRemap", true); // foliage sway: pixelLight / the sprite filter bind the sway twin of their composite directly (one program start a draw, not two)
   public static final boolean SWAY_MV_IMAGE = bool("swayMvImage", true); // foliage sway: the plant fragments store their DLSS motion in an image (only they write; no second render target in the composite)
   public static final boolean SWAY_LIGHT_UNDISPLACED = bool("swayLightUndisplaced", false); // foliage sway: in pixelLight's composite only the colour and depth come from the moved texel, its light taps keep the pixel's own (no wait on the sway lookup)
   public static final boolean SWAY_TWIN_ALL = bool("swayTwinAll", false); // foliage sway: every chunk draw goes through the twin while sway is on (textures without plants return at a uniform test): no program switch between plant and plain textures
   public static final boolean SWAY_PREFETCH = bool("swayPrefetch", false); // foliage sway: the composite fetches colour and depth at the pixel's own texel with the plant flag; only a moved pixel fetches again (no dependent round trip for every pixel)
   public static final boolean SWAY_AUX_EAGER = bool("swayAuxEager", false); // foliage sway: the attribute texel fetched beside the depth for every pixel of a plant texture (one dependent round trip less for plant pixels, more bandwidth)
   public static final boolean SWAY_MV_NO_BLEND = bool("swayMvNoBlend", true); // foliage sway: the motion attachment is written, never blended (the composite's glEnable(GL_BLEND) covers every draw buffer)
   public static final boolean SWAY_PUSH = bool("swayPush", true); // foliage sway: characters and cars bend the grass and bushes they move through (replaces stock's rustle: its per-frame draw and two re-bakes per bush)
   public static final float DEV_SWAY_PUSH_ORBIT = (float)integer("devSwayPushOrbit", 0); // dev: squares; a pusher circles the camera's character at that radius
   public static final boolean DEV_SWAY_PUSH_OLD = bool("devSwayPushOld", false); // dev: the push kernel before 2026-10-02 (bend sign flips on the pusher's column: a vertical seam above every walker), for same-build A/Bs
   public static final int SWAY_ITERATIONS = integer("swayIterations", 1); // foliage sway: fixed-point steps of the inverse lookup inside a plant (2: the texel's own attribute re-read at the first guess; +1 dependent fetch)
   public static final boolean SWAY_MASK = bool("swayMask", true); // foliage sway: a byte per 16 x 16 texels says whether a plant can reach it (the composite skips the rest with one cached tap)
   public static final boolean DEV_SWAY_VARIANT_ALL = bool("devSwayVariantAll", false); // dev: every chunk composite draw uses the sway variant while sway is on (no program alternation; textures without plants skip the lookup by a uniform)
   public static final boolean DEV_SWAY_NO_PATCH = bool("devSwayNoPatch", false); // dev: no shader patched (the off cost's reference)
   public static final int DEV_SWAY_WIND = integer("devSwayWind", -1); // dev: pins the wind the sway sees (0..100), -1 = the climate's
   public static volatile boolean GOD_RAYS; // light shafts: the sun (or moon) through windows, doors, tree crowns and between buildings, lit in the haze and the dust of rooms (world composite)
   public static volatile int GOD_RAYS_STRENGTH_PCT; // how bright the shafts are, %
   public static volatile int GOD_RAYS_HAZE_PCT; // how hazy the open air is, % of the weather's own (fog, rain, morning mist add to it)
   public static volatile int GOD_RAYS_DUST_PCT; // how dusty rooms are (the shafts through windows), %
   public static volatile int GOD_RAYS_PATCH_PCT; // sunlit patches on floors and walls inside (the light the shaft lands with), %
   public static volatile String GOD_RAYS_METHOD;
   public static volatile boolean GOD_RAYS_APERTURES;
   public static volatile boolean GOD_RAYS_AP_DIRECT;
   public static volatile boolean GOD_RAYS_MOTES;
   public static volatile int GOD_RAYS_SOFT_PCT; // god rays: how soft the shafts' edges and their sunlit patches are (the penumbra widens with the distance from the window), %; 0 = cut exactly by the frame
   public static volatile int GOD_RAYS_GLINT_PCT; // god rays: dust motes glinting in the shafts, %; 0 = none
   public static volatile String GOD_RAYS_LOCAL_METHOD; // god rays, local lights: analytic (the closed-form airlight, one quad a light) | froxel (injected into the volume with walls' shadows)
   public static volatile String GOD_RAYS_HAZE_MODE; // god rays outdoors: add (the haze's light on top) | shade (the game's fog darkened where the air is in shadow) | auto (shade in the game's fog, add in mist) // god rays: dust motes glint in the shafts through windows
   public static volatile boolean GOD_RAYS_LOCAL; // god rays: torches, headlights and lamps light the dust and the fog around them (their airlight)
   public static volatile int GOD_RAYS_LOCAL_PCT; // how bright the local lights' beams are, %
   public static final boolean GOD_RAYS_LOCAL_AT_NIGHT = true; // (the local lights need no sun) // god rays: the light volumes drawn straight into the world picture (one draw, dual-source blending) instead of a target + composite // god rays: the shafts through windows and doorways and their sunlit patches as light volumes (exact edges) instead of the volume's cells // volume (the view columns integrated once per sun step / change, one tap a pixel) | march (per-pixel march of the visibility volume, blue noise + temporal) | epipolar | minmax | blur (screen-space directional blur of the lit surfaces)
   public static final int GOD_RAYS_CELL_PX = integer("godRaysCellPx", 16); // god rays: screen pixels per volume column (each view ray is a column of the rectified volume)
   public static final int GOD_RAYS_SLICES = integer("godRaysSlicesPerLevel", 8); // god rays: volume slices per level of height
   public static final int GOD_RAYS_LEVELS = integer("godRaysLevels", 4); // god rays: levels of height the haze volume covers (above the lowest visible level)
   public static final int GOD_RAYS_CHUNK_BUDGET_US = integer("godRaysChunkBudgetUs", 400); // god rays: game-thread time a frame for building chunk occupancy (walls, windows, doors, roofs, crowns)
   public static final boolean GOD_RAYS_GL_CACHE = bool("godRaysGlCache", true); // god rays: the world FBO and viewport read back only when the game binds another FBO (no glGet a frame)
   public static final int GOD_RAYS_COARSE = integer("godRaysCoarse", 1); // god rays: the light volumes and local lights shaded once per NxN pixels (NV_shading_rate_image; 1 = every pixel, the default: 2x2 measured the same, the passes are not shading-bound)
   public static final String GOD_RAYS_HAZE_COMPOSITE = string("godRaysHazeComposite", "chunk"); // god rays: chunk = the outdoor haze added by the chunk composite from each fragment's own depth (no buffer, no screen tap; patched at launch), screen = the quarter buffer + the screen composite's tap
   public static final int GOD_RAYS_HAZE_ADD_PCT = integer("godRaysHazeAddPct", 25); // god rays, the haze in the chunk composite: how much of the lit air's light is added (100: the full veil)
   public static final int GOD_RAYS_SHADE_PCT = integer("godRaysShadePct", 100); // god rays, the haze in the chunk composite: the shade of the air in shadow (100: k 2.2, as in the fog)
   public static String GOD_RAYS_FOG_SHADE = string("godRaysFogShade", "lowres"); // god rays in the game's fog: lowres = the shade applied to the fog buffer per texel (no per-pixel fetch), pixel = a tap per screen pixel in the fog composite
   public static final int GOD_RAYS_LOCAL_CORE_PCT = integer("godRaysLocalCorePct", 150); // god rays, local lights: the light's core radius in hundredths of a square (a point light glowed as a white-hot orb at the bulb)
   public static final boolean GOD_RAYS_UNBIND_DEPTH = bool("godRaysUnbindDepth", true); // god rays: the scene depth unbound from our sampler unit after our passes (left bound, the rest of the world's draws saw a feedback loop)
   public static final boolean GOD_RAYS_LATE_DRAW = bool("godRaysLateDraw", false); // god rays: the light volumes and local lights drawn over the finished world at the screen composite (measured: +10 us, the extra FBO switch; off)
   public static final boolean GOD_RAYS_ROOF_RULE = bool("godRaysRoofRule", true); // god rays: a window or doorway whose outside square has a roof, a porch roof or a floor right above it lets no light in (no shaft, no sunlit patch)
   public static final boolean GOD_RAYS_DOOR_GLASS = bool("godRaysDoorGlass", true); // god rays: closed doors with glass (a window in the door, sliding glass doors) let the light through their glass, unless curtained or barricaded
   public static final boolean GOD_RAYS_AP_CLIP = bool("godRaysApClip", true); // god rays: a light volume ends at the first wall or the edge of the building at every height of its aperture (one wall test at the aperture's middle let the upper part of a long evening shaft through the far wall onto the street)
   public static final boolean GOD_RAYS_AP_CULL = bool("godRaysApCull", true); // god rays: light volumes only in rooms the game shows (its building cut away, the player's level)
   public static final boolean GOD_RAYS_AP_DEPTH_TEST = bool("godRaysApDepthTest", true); // god rays: the light volumes' faces depth-tested against the scene (behind a roof or wall: never shaded)
   public static final boolean GOD_RAYS_FOG_FUSE = bool("godRaysFogFuse", true); // god rays: in the game's fog the haze's shade rides the fog pass's composite (the world composite's tap is skipped)
   public static final boolean GOD_RAYS_LOW_COMPUTE = bool("godRaysLowCompute", true); // god rays: the god ray buffer by a compute dispatch (no framebuffer switch) instead of a fragment pass
   public static final int GOD_RAYS_BUFFER_DIV = integer("godRaysBufferDiv", 4); // god rays: the god ray buffer the composite reads is 1/N of the view per axis (the haze and, with light volumes off, the rooms' light)
   public static final int GOD_RAYS_MARCH_SAMPLES = integer("godRaysMarchSamples", 8); // god rays, methods march / minmax / epipolar: samples a view column a frame
   public static final boolean GOD_RAYS_TEMPORAL = bool("godRaysTemporal", true); // god rays, methods march / epipolar: accumulate the jittered marches over frames (reprojected by the world column)
   public static final int GOD_RAYS_TEMPORAL_PCT = integer("godRaysTemporalPct", 12); // the new frame's weight in the accumulation, %
   public static final int GOD_RAYS_STEP_FRAMES = integer("godRaysStepFrames", 8); // god rays: a light step recomputes the volume in bands over this many frames (1 = at once); pans and changes always at once
   public static final int DEV_GOD_RAYS_ALTERNATE = integer("devGodRaysAlternate", 0); // dev: ms; god rays switch on and off every period (the screen pass's GPU time split by it)
   public static final boolean DEV_GOD_RAYS_TOUCH_DEPTH = bool("devGodRaysTouchDepth", false); // dev (cost attribution): the off phase of devGodRaysAlternateAll still reads one scene depth texel
   public static final int DEV_GOD_RAYS_SKIP = integer("devGodRaysSkip", 0); // dev (cost attribution): 1 no god ray buffer pass (the last one stays), 2 no composite tap, 4 no volume compute, 8 no fog shade, 16 light volumes read no depth, 32 light volume pass draws nothing, 64 light volumes blend plainly
   public static final boolean DEV_GOD_RAYS_ALTERNATE_ALL = bool("devGodRaysAlternateAll", false); // dev: the alternation's off half skips every god ray pass (frame-time A/B within one run: harness/godrays/abframes.py)
   public static final boolean DEV_GOD_RAYS_TIMING = bool("devGodRaysTiming", false); // dev: GL timestamps around the volume updates and the screen pass (on / off), medians logged every 5 s
   public static final boolean DEV_GOD_RAYS_SOFT_VIEW = bool("devGodRaysSoftView", false); // dev: the light volumes show their soft cover (red: the mean along the view column, green: the hull's stretch)
   public static final int DEV_GOD_RAYS_AP_LOG = integer("devGodRaysApLog", 0); // dev: log every window / doorway the light volumes consider (side, light, cover) for the first N prism builds
   public static final int DEV_GOD_RAYS_VIEW = integer("devGodRaysView", 0); // dev: 1 the inscatter alone (x8), 2 the sun visibility at the surface, 3 the transmittance, 4 the room flag
   public static final String DEV_GOD_RAYS_DUMP_AT = string("devGodRaysDumpAt", ""); // dev: seconds after the world is up (comma list): colour, depth, occupancy and the frame state to ~/Zomboid/pzopt-godrays/ for harness/godrays/rig.py
   public static final float DEV_GOD_RAYS_HOUR_SPEED = Float.parseFloat(string("devGodRaysHourSpeed", "0")); // dev: game hours a real second the key light sweeps (with devSunHour; the worst case of the volume updates)
   public static final String DEV_SKY_DATE = string("devSkyDate", ""); // dev: YYYY-MM-DD for the sky whatever the game's date (a full-moon night)
   public static final String DEV_FAR_DUMP = string("devFarDump", "").trim(); // dev: "x,y,r": once, the far field's column tops (quarter levels, base 36) of the squares within r of x, y into the console
   public static final int DEV_SUN_VIEW = integer("devSunView", 0); // dev: 1 = the kept term is the sun term alone (with devAoView=1: the shadows as a grey image), 2 = the exterior mask, 3 = the attached (facing) term, 4 = which branch the sun term took (indoors 0, roof 0.2, clamped wall 0.4, grid wall 0.6, snapped plane 0.8, unsnapped 1, outdoor ones x (0.5 + 0.5 facing)), 5-7 = height / x / y offset, 8 = the far field (sunShadowFar) alone)
   public static final boolean PIXEL_LIGHT = bool("pixelLight", false); // per-pixel lighting (pzopt.PixelLight): the chunk textures bake unlit and the light is composed per pixel from the lattice of the native's corner colours; a light change never re-bakes a texture
   public static final String PPL_MODE = string("pplMode", "composite").toLowerCase(java.util.Locale.ROOT); // pixelLight: composite = the chunk composite shader lights each pixel (no extra pass); pass = a full-screen pass after it
   public static final boolean PPL_DISCARD_CLEAR = bool("pplDiscardClear", true); // pixelLight composite: empty chunk-texture texels (no colour, cleared depth) are discarded instead of blended (same picture)
   public static final boolean PPL_ANALYTIC = bool("pplAnalytic", true); // pixelLight: torches, headlights, lamps and fires drawn per pixel from their own shape (the native keeps deciding where they reach); off: the native per-square light only
   public static final boolean PPL_NORMALS = bool("pplNormals", true); // pixelLight: each dynamic light's share shaded by the surface's facing (normals from the depth; floors unchanged)
   public static final int PPL_WRAP_PCT = integer("pplWrapPct", 35); // pixelLight normals: wrap lighting, % (how far past 90 degrees a face still catches the light; softens the painted sprites' double shading)
   public static final boolean PPL_TORCH_VEHICLE_MIX = bool("pplTorchVehicleMix", true); // pixelLight: where a vehicle light outshines the torch on a square the native never added the torch, so nothing of it is taken out of the base (else the torch lit nothing in a car's beam, Discord 2026-10-04); where the torch outshines it, the vehicle light is put back
   public static final boolean PPL_CLIP_BASE = bool("pplClipBase", true); // pixelLight: a square the torch saturates keeps the light it had without the torch (remembered per square, at least light - torch) instead of the night ambient estimate (a street lamp's light vanished round the player: Discord 2026-10-04)
   public static final boolean PPL_SHADOWS = bool("pplShadows", false); // pixelLight: the player's torch casts per-pixel shadows (a half-resolution mask marched in the scene depth after the composite, blurred, read by the next frame's composite reprojected)
   public static final int PPL_EASE_MS = integer("pplEaseMs", 0); // pixelLight (experimental, off: not yet verified in game): a chunk level's light change eases in over this many ms (the native hands vision and light changes over in batches: a lit / dark layout snapped in one frame, floor tiles flashing while turning, 2026-10-03); 0: at once. Global light events (flashes, dusk steps) stay instant; try 120
   public static final boolean PPL_TORCH_FADE = bool("pplTorchFade", false); // pixelLight (experimental, off: no measurable change in the tile-pop rig): a torch cross-fades in over the native's own fade where a square comes into view or into the beam (light / torch 0.5 -> 0.9) instead of switching on at 0.9: floor tiles flashed at the vision cone's edge while turning with a lantern (2026-10-03)
   public static final boolean PPL_SHADOW_DEPTH_TEST = bool("pplShadowDepthTest", true); // pixelLight shadows: a mask texel whose surface is not the pixel's (the previous frame showed another floor: the first frame after a floor change drew the old floor's torch shadows as streaks on the new floor's walls, 2026-09-26) is ignored
   public static final float PPL_SHADOW_SQUARES = integer("pplShadowSquares", 4); // pixelLight shadows: how far towards the light the march looks, squares
   public static final int PPL_SHADOW_MAX_STEPS = integer("pplShadowMaxSteps", 64); // pixelLight shadows: most march steps a pixel takes (the samples sit on a 1/8-square screen grid shared by every pixel, doubled past this count; the old fixed 16 cut pole shadows into stripes, 2026-09-30)
   public static final boolean PPL_TORCH_CAN_SEE = bool("pplTorchCanSee", true); // pixelLight: a handheld torch stays off every square the player cannot see (the native lists the torch there but a wall keeps it out; false: only where the square's own light is below the torch's, so a room lit by its own lamp took the torch through the wall, 2026-09-26)
   public static final boolean LAMP_IDS_APART = bool("lampIdsApart", true); // lamps and lit objects take their lighting ids from 1048576 up instead of 1 (LightingJNI): the native keeps one entry per id in a square's light list, so lamp 1 shared player 1's torch id and replaced it on every square it reached; pixelLight then took no torch out there and drew its own on top (a second, paler cone beside the beam, 2026-10-04). Handheld torches stay 1..4095, vehicle lights 4096 + vehicle x 10 + light, room lights 100000 + n
   public static final boolean PPL_OWN_LEVEL_LIGHTS = bool("pplOwnLevelLights", true); // pixelLight: torches, lamps, fires and vehicle lights light only their own level (false: one level up and down as well: a torch's glow on the ground below the player's floor, a lamp's colour on the walls of the floor above, 2026-09-26)
   public static final boolean PPL_TEXEL_POS = bool("pplTexelPos", true); // pixelLight: the torch's facing term takes its normal from the chunk-texture texel the pixel shows and its neighbours (false: screen derivatives, which moved with the camera's sub-pixel offset: the torch light blinked on walls while walking, 2026-09-25)
   public static final boolean PPL_TEXEL_HEIGHT = bool("pplTexelHeight", true); // pixelLight: a composited pixel's height (the level it takes its light from) comes from the chunk-texture texel it shows, its centre and its own depth (false: the pixel centre with the nearest texel's depth, which moved with the upscaler's sub-pixel jitter: flat roofs' tile-edge rows flipped to the level below every frame with DLSS, Spiffo's roof, 2026-09-27)
   public static final boolean PPL_FLOOR_SNAP = bool("pplFloorSnap", true); // pplTexelHeight: a flat floor within the level tolerance of a level is on it (its texels 2 rows above and below at the same height), so the tolerance's brighter-of-two-levels rule for wall tops never lights a roof with the room under it (Spiffo's roof, 2026-09-27)
   public static final boolean PPL_DEPTH_OPAQUE_ONLY = bool("pplDepthOpaqueOnly", true); // pixelLight: baked tiles write their depth only where the sprite is not fully transparent (stock's tileWithDepth writes it in the sprite's gaps too: a shelf's goods overlay stamped its box depth where the shelf shows through, and those texels took the light of a square up to one away, across the wall in the unlit garage: the Fossoil shelf goods shimmered dark, 2026-09-29)
   public static final boolean PPL_SEEN_EDGE = bool("pplSeenEdge", true); // pixelLight: a point just past a square's west / north edge across a wall, in a square the player has never seen (blacked out), takes the light of the seen square before the edge (an object flush against a wall has its depth box's face on the square's edge; the Fossoil shelf goods took the unlit garage's light behind the wall in a dark speckle that moved every frame, 2026-09-29)
   public static final boolean PPL_CUT_EDGE = bool("pplCutEdge", true); // pixelLight: pplSeenEdge also where the wall on that edge is cut away (stock's cutaway): what shows on the edge is then the room's furniture flush against the hidden wall, lit by the room, not by the square outside (a cut-open upper floor at dusk: the bathtub's front face took the dark roof square's light outside the house, a black slab, 2026-10-05)
   public static final boolean PPL_WALL_EDGE = bool("pplWallEdge", true); // pixelLight: a surface above the floor on the wall side of a square whose west / north edge has a wall (window, door frame, wall) never takes the light of the square behind that wall (the native shares its light across a window by day: every window tile's wall face took half the room's darker light, a full-height grey column, 2026-10-03); floors keep the blend
   public static final boolean PPL_JIGGLE = bool("pplJiggle", true); // pixelLight: the point a pixel is lit at takes the camera's jiggle fix into account (the game moves every chunk texture by fixJigglyModels on screen and in depth; without it the point lay up to a tenth of a square off, and a surface at a square's edge took its light from the square beside it every other frame while walking: the goods on the Fossoil shelves against the unlit garage wall went black and back, 2026-09-29)
   public static final int PPL_NORMAL_SPAN = Math.max(1, Math.min(4, integer("pplNormalSpan", 2))); // pplTexelPos: the texel normal's neighbours this many texels away (1: DEPTH16 rounding flipped the snapped plane texel by texel)
   public static final int PPL_NORMAL_SPAN_WIDE = Math.max(0, Math.min(8, integer("pplNormalSpanWide", 4))); // pplTexelPos: a texel normal that did not snap to a floor / wall plane tries neighbours this many texels away and keeps that one when it snaps (DEPTH16 rounding left a dark dot lattice on walls, Discord 2026-10-04); 0 = off
   public static final boolean PPL_SMOOTH = bool("pplSmooth", true); // pixelLight: smoothstep weights between square centres (C1 light, no Mach bands; still one texture fetch)
   public static final String DEV_PPL_COST_AT = string("devPplCostAt", ""); // dev: t:mask,... seconds after the world is up: parts of the per-pixel shader off (1 lights, 2 edge path, 4 normals, 8 all but the position, 16 all, 32 the texture uploads, 64 the light via its uniform, 128 all the frame's GL work, 256 the stock chunk program, 512 the light-free variant everywhere, 1024 no pass in pass mode, 8192 two identical light-free programs alternating per draw), the composite timer names the mask
   public static final String DEV_PPL_ALTERNATE = string("devPplAlternate", ""); // dev: start,periodSec,maskA,maskB: from start the cost mask flips between A and B every period (256 = the unpatched stock chunk program on the same unlit bakes), the composite timer sums each mask apart: same-scene, drift-cancelling A/B
   public static final boolean DEV_PPL_TINT = bool("devPplTint", false); // dev: every chunk program tints its pixels (light-free green, torch red, lamps blue, torch + lamps / full yellow): which chunk textures took which program
   public static final boolean PPL_WET_SPECULAR = bool("pplWetSpecular", true); // pixelLight: wet outdoor ground and walls glint in torch, headlight and lamp light while the rain wets them (and while they dry)
   public static final boolean PPL_POINT_LIGHTS = bool("pplPointLights", true); // per-pixel lighting: lamps and fires shaped per pixel (off: their light is the native's field, interpolated; the chunks they reach keep the light-free program)
   public static final int PPL_SPEC_PCT = integer("pplSpecPct", 60); // pixelLight: strength of the wet glints, %
   public static final boolean PPL_AIR_FILL = bool("pplAirFill", true); // pixelLight: a lattice level without a square at a column (air above the ground, beside a taller building, above the chunk's top) takes the top corners of the column's highest square below, kept up to the tallest neighbour's top; off: black (0) there, and levels above the chunk's top + 1 keep whatever the slot held before (tree crowns black or flickering)
   public static final boolean PPL_PACK_RING = bool("pplPackRing", true); // pixelLight: the lattice also holds the loaded chunks around the on-screen ones (their squares are reached by tree copies in on-screen textures and by the light's bilinear across the border); off: only the on-screen chunks, the rest reads whatever the slot held (a tree at the screen's edge black)
   public static final boolean PPL_VARIANTS = bool("pplVariants", true); // pixelLight: chunk textures no torch, headlight or lamp reaches are composited by a light-free variant of the shader (half the registers: twice the occupancy on RDNA iGPUs)
   public static final boolean RELIEF = bool("relief", false); // relief / "parallax textures" (pzopt.Relief): the art's fine relief (mortar, plank gaps, cobbles) as a height field from the chunk texture's colour, so moving light rakes it (with pixelLight: the torch, lamps, fires, headlights)
   public static final int RELIEF_DEPTH_PCT = Math.max(0, Math.min(400, integer("reliefDepthPct", 150))); // relief: how deep the art's grooves are, %
   public static final String RELIEF_HEIGHT = string("reliefHeight", "mix").trim().toLowerCase(java.util.Locale.ROOT); // relief: the height estimate from the colour: lum (brighter is higher), groove (off the local mean colour is a groove), mix
   public static final int RELIEF_SHADOW_STEPS = Math.max(0, Math.min(12, integer("reliefShadowSteps", 4))); // relief: self-shadowing, texels marched towards the light (0: none)
   public static final int RELIEF_SHADOW_PCT = Math.max(0, Math.min(100, integer("reliefShadowPct", 70))); // relief: how dark the art's own grooves fall in its shadow, %
   public static final int RELIEF_SNAP_PCT = Math.max(50, Math.min(99, integer("reliefSnapPct", 80))); // relief: a texel whose depth normal leans this close to a floor or wall plane (cosine, %) gets that plane's relief
   public static final int RELIEF_GMAX_PCT = Math.max(1, Math.min(1000, integer("reliefGmaxPct", 20))); // relief: the height slope (green per texel, %) at which the soft limit halves it (edges between sprites are not cliffs)
   public static final int RELIEF_SUN_PCT = Math.max(0, Math.min(200, integer("reliefSunPct", 100))); // relief: how much of the direct sun / moonlight the relief relights, % (0: the sun path off; needs sunShadows and cloudShadows, which give the texel's direct-sun share)
   public static final boolean RELIEF_AUX = bool("reliefAux", true); // relief: each chunk texture's relief encoded once after it bakes into a byte per texel (pzopt.ReliefAux), read once per composited fragment (false: derived from the colour and depth in the composite, with the self-shadow ray)
   public static final int RELIEF_AUX_BUDGET = Math.max(1, integer("reliefAuxBudget", 8)); // relief codes: chunk textures encoded a frame at most (on screen)
   public static final int RELIEF_AUX_BUDGET_MB = Math.max(16, integer("reliefAuxBudgetMb", 512)); // relief codes: VRAM above which the codes of textures not drawn for a second are freed (a code + the baked factor = 2 bytes a chunk-texture texel: ~2 MB a texture, ~120 textures at zoom 1 on a 5K screen)
   public static final String RELIEF_SUN_MODE = string("reliefSunMode", "bake").trim().toLowerCase(java.util.Locale.ROOT); // relief under the sun / moon: bake = into the chunk textures at bake time and on the key light's steps (no per-frame cost; needs ambient occlusion's chunk kernel: sunShadows), composite = per fragment in the composite (+86 us at 5K)
   public static final int RELIEF_STEP_BUDGET = Math.max(1, integer("reliefStepBudget", 4)); // relief baked: chunk textures on screen re-lit a frame when the key light steps
   public static final int RELIEF_TORCH_SHADOW_STEPS = Math.max(0, Math.min(8, integer("reliefTorchShadowSteps", 0))); // relief codes: the torch's / lamps' self-shadow ray on the codes, texels (0: none; the baked sun always has its own)
   public static final float RELIEF_MAX_ZOOM = Float.parseFloat(string("reliefMaxZoom", "1.75")); // relief: zoomed out this far or more no relief is baked or read (its detail is under half a pixel), and no codes held for it
   public static final boolean RELIEF_HORIZON = bool("reliefHorizon", false); // relief codes: the torch's / lamps' self-shadow from per-texel horizons (horizon mapping: tan of the relief's horizon along +u, -u, +v, -v, RGBA4 = 2 more bytes a texel, one fetch)
   public static final int RELIEF_HORIZON_STEPS = Math.max(1, Math.min(16, integer("reliefHorizonSteps", 6))); // relief horizons: texels searched along each direction
   public static final boolean RELIEF_OBJECTS = bool("reliefObjects", false); // relief: also on furniture and other objects (their sprites' painted shading reads as relief; off: floors and walls only)
   public static final int DEV_RELIEF_ALTERNATE = integer("devReliefAlternate", 0); // dev: relief on / off every N ms in one run (the same programs; GPU section "composite" split .rlon / .rloff with gpuSections=true)
   public static final int DEV_RELIEF_SKIP = integer("devReliefSkip", 0); // dev: sun relief cost probes, bits: 1 return after the direct-sun share fetch, 2 after the code fetch, 4 no share fetch (0.4)
   public static final boolean DEV_RELIEF_TIMING = bool("devReliefTiming", false); // dev: GPU time of the baked relief's passes (encode, factor, apply, keep) in the log every 300 bakes
   public static final boolean DEV_RELIEF_TRACE = bool("devReliefTrace", false); // dev: log the relief's re-bake requests with what the texture held at its last bake
   public static final int DEV_RELIEF_VIEW = integer("devReliefView", 0); // dev: 1 = every surface lit by a low light from the west (relief over flat), 2 = the relief normal, 3 = that factor alone (grey), 4 = the self-shadow, 5 = the height (pixelLight's composite; needs relief=true)
   public static final int DEV_PPL_VIEW = integer("devPplView", 0); // dev: 1 = the light alone, 2 = the unlit surfaces, 3 = the owner squares as a checkerboard, 4 = the reconstructed normals, 13 = the reconstructed height (red below 0, green z / 6, blue its fraction), 14 = its offset from the nearest level (grey: 0.5 on it, +-0.039 levels), 15 = the level read (red the wall path, green the level, blue lifted by the tolerance), 16 = the lit point unshaded (red fract x, green fract y, blue (x mod 4) * 4 + y mod 4 over 16)
   public static final int DEV_PPL_PROBE = integer("devPplProbe", 0); // dev: per frame, the squares within this many of the player on its level: how many of their native corners / light / vision bits and packed lattice values changed and how many came back to the value of two frames before (A-B-A), one log line (pzopt.PixelLight), the stairs wall flicker rig
   public static final String DEV_SQUARE_TRACE = string("devSquareTrace", ""); // dev: x,y,z[;x,y,z...]: per frame, each listed square's objects with their render layer (baked / per-frame translucent), alphas, whether the chunk baked, the light (pzopt.SquareTrace; the Fossoil shelf blink, 2026-09-29)
   public static final int DEV_SQUARE_TRACE_AT = integer("devSquareTraceAt", 0); // devSquareTrace: seconds after the first world frame before logging
   public static final int DEV_SQUARE_TRACE_FRAMES = integer("devSquareTraceFrames", 600); // devSquareTrace: frames logged
   public static final boolean DEV_PPL_TRACE = bool("devPplTrace", false); // dev: one log line per frame of pixelLight's lattice packs and camera mapping inputs (pzopt.PixelLight), for lining frames up with a devCapture sequence
   public static final boolean DEV_PPL_TIMING = bool("devPplTiming", false); // dev: GPU time of the pixel-light pass and the lattice uploads in the log every 1000 frames
   public static final String DEV_PPL_TOGGLE_AT = string("devPplToggleAt", ""); // dev: seconds after the world is up at which pixelLight flips on / off (every texture re-baked), for A/Bs of one scene in one run
   public static final String DEV_CAPTURE = string("devCapture", ""); // dev: start,seconds,fps,scalePct: the presented frames read back to ~/Zomboid/pzopt-capture/ (pzopt.FrameCapture), a recorder-free video rig
   public static final String DEV_PPL_DUMP_AT = string("devPplDumpAt", ""); // dev: seconds after the world is up at which the per-square lighting, the light sources and the scene depth + colour go to ~/Zomboid/pzopt-ppl/ (pzopt.PixelLight)
   public static final boolean DEV_SSR_TIMING = bool("devSsrTiming", false); // dev: GPU time of the water pass in the log every 600 frames, apart for reflections on / off (devSsrAlternate)
   public static final int DEV_SSR_ALTERNATE = integer("devSsrAlternate", 0); // dev: the reflections flip on / off every N ms (same-run A/B of their cost)
   public static final boolean DEV_SSR_BARRIER_OFF = bool("devSsrBarrierOff", false); // dev: devSsrAlternate's off frames still issue the texture barrier
   public static final int DEV_SSR_SKIP = integer("devSsrSkip", 0); // dev: cost probes, bits: 1 no moving-object scatter, 2 no water resolve (strength 0), 4 no barriers before the water, 8 no composite scatter
   public static final int DEV_SSR_VIEW = integer("devSsrView", 0); // dev: 1 = water and puddles show the reflection term alone
   public static final boolean DEV_SSR_NO_PATCH = bool("devSsrNoPatch", false); // dev: the shaders stay stock (cost of the patched programs with the reflections off)
   public static final boolean DEV_SSR_TRACE = bool("devSsrTrace", false); // dev: one log line per frame of the reflection scatter (map uploads with their water bits, chunk textures near water, composite draws with the scatter on, water draws, render-thread replays) with epoch ms, to line up with a devCapture
   public static final String DEV_SSR_DUMP_AT = string("devSsrDumpAt", ""); // dev: seconds after the world is up at which the world colour + depth before and after the puddles and the water go to ~/Zomboid/pzopt-ssr/ (pzopt.Ssr)
   public static final boolean DEV_HDR_FRAME_LOG = bool("devHdrFrameLog", false); // dev: one line per HDR world composite into ~/Zomboid/pzopt-hdrframe.out (epoch ms, the bloom / light / aux / glint state and the sun keys the composite reads; the flip one-frame-dark hunt, 2026-10-02)
   public static final String DEV_AO_DEFINES = string("devAoDefines", ""); // dev: preprocessor names (comma separated) defined in the chunk AO / sun kernel, e.g. TREE_NO_CLASS, TREE_NO_SKIP, TREE_NO_SUNPATH, TREE_NO_SKY (in-game ablation of the tree terms)
   public static final int DEV_AO_DUMP_TREE = integer("devAoDumpTree", 0); // dev: the first N chunk AO computes with a tree in reach (sunShadowTrees) or in the chunk are dumped like devAoDumpFrame's into ~/Zomboid/pzopt-chunkao/<k>/ (plus raw4: AO, depth, sun, and the source depths + uniforms for harness/trees/tree_rig.py)
   public static final int DEV_AO_DUMP_FRAME = integer("devAoDumpFrame", 0); // dev: on this AO frame the scene depth + colour go to ~/Zomboid/pzopt-ao-*.bin
   public static final boolean DEV_AO_NO_MIPS = bool("devAoNoMips", false); // dev: a deferred chunk AO does not rebuild the texture's mipmaps (cost probe)
   public static final boolean DEV_AO_TIMING = bool("devAoTiming", false); // dev: GPU time of each AO stage in the log every 1000 frames
   public static final int DEV_AO_VARIANT = integer("devAoVariant", 0); // dev: cost probes, 1 = the AO stage reads one depth texel, 2 = none
   public static final int DEV_AO_VIEW = integer("devAoView", 0); // dev: 1 = the scene shows the AO term alone, 2 = the reconstructed normals
   public static final boolean HDR = bool("hdr", false); // HDR output (pzopt.Hdr): FP16 window on Wayland tagged with the output's HDR image description, world highlights expanded, UI at the desktop's white
   public static final boolean HDR_AUTO = bool("hdrAuto", true); // HDR output whenever the screen is HDR (Linux: a Wayland output in HDR mode; macOS: an EDR display), even with hdr=false; hdr=true forces it
   public static final String HDR_ENCODE = string("hdrEncode", "auto").toLowerCase(java.util.Locale.ROOT); // on: ext_linear description + encode pass at the swap (standard, exact roll-off); off: no description, the compositor's own SDR decode shows the FP16 values above 1.0 (KWin; ~0.3 ms a frame cheaper at 4K); auto: off on KDE Plasma, on elsewhere
   public static volatile boolean SSR; // screen-space reflections of the scene in the water and the puddles (pzopt.Ssr)
   public static volatile int SSR_STRENGTH_PCT; // how strongly the water mirrors the scene, % (a deep river at the camera's angle reflects ~6 % physically)
   public static volatile boolean OCCLUDED_OUTLINES; // occluded zombie outlines (pzopt.OccludedOutline, PR #48): the parts of a seen zombie that scenery hides get a contour (live)
   public static volatile boolean OCCLUDED_OUTLINE_IGNORE_PLANTS; // occluded outlines: grass and bushes in front of a zombie (nothing solid) do not outline its legs (live)
   public static volatile int OCCLUDED_OUTLINE_WIDTH; // occluded outlines: contour width in render pixels, 1..4 (live)
   public static volatile int OCCLUDED_OUTLINE_OPACITY; // occluded outlines: opacity in daylight, % (live)
   public static volatile String OCCLUDED_OUTLINE_COLOUR; // occluded outlines: RGB hex (live)
   public static volatile boolean BLOOD_WET; // wet blood (pzopt.BloodWet): fresh floor splats reflect the scene (with reflections on) and catch the sun and the lamps (a GGX sheen; with HDR output, glints above white) until they dry (live)
   public static volatile int BLOOD_WET_MINUTES; // wet blood: game minutes a splat stays wet (drying from its edges in) (live)
   public static volatile int BLOOD_REFLECT_PCT; // wet blood: strength of the reflection in the film, % of the water's Fresnel (live)
   public static volatile int BLOOD_SHEEN_PCT; // wet blood: strength of the SDR sun / lamp sheen, % (live)
   public static volatile int BLOOD_GLINT_PCT; // wet blood: strength of the HDR glints, % (live)
   public static volatile boolean SSR_PUDDLES; // reflections in the puddles too (once they are big enough to reflect)
   public static final String SSR_MODE = string("ssrMode", "ppr"); // reflections: ppr = pixel-projected (the chunk composite writes each surface into the pixel it mirrors to; the water reads one texel), march = a ray march up the column in the water shader
   public static final int SSR_REACH_PCT = integer("ssrReachPct", 150); // reflections: the reflection fades out between half and all of this height above the water, % of a level
   public static final int SSR_STRIDE = integer("ssrStride", 8); // reflections: px between the march's depth taps up the column
   public static final int SSR_STEPS = integer("ssrSteps", 48); // reflections: taps before the ray gives up (reach = stride x steps px)
   public static final int SSR_REFINE = integer("ssrRefine", 3); // reflections: binary refinements between the last miss and the hit
   public static final int SSR_THICKNESS_PCT = integer("ssrThicknessPct", 150); // reflections: how far behind a surface the ray may pass and still count it as hit, % of a square
   public static final int SSR_DISTORT_PCT = integer("ssrDistortPct", 100); // reflections: how far the waves displace the reflected image
   public static final boolean MIRRORS = bool("mirrors", false); // wall mirrors and windows reflect the room / street in front of them, characters and cars as their real mirrored models (pzopt.Mirrors; next launch)
   public static final boolean MIRRORS_WINDOWS = bool("mirrorsWindows", true); // mirrors: windows reflect too (false: wall mirrors only)
   public static final int MIRRORS_WINDOW_PCT = integer("mirrorsWindowPct", 30); // mirrors: how much of a window pane is its reflection, %
   public static final int MIRRORS_MIRROR_PCT = integer("mirrorsMirrorPct", 90); // mirrors: how much of a mirror's glass is its reflection, % (silvered glass: ~90)
   public static final boolean MIRRORS_MODELS = bool("mirrorsModels", true); // mirrors: characters and vehicles in front of a reflector drawn once more through the mirror plane (their real other side); false = only what the camera sees
   public static final String MIRRORS_STATIC = string("mirrorsStatic", "pass"); // mirrors: the room / street in the reflection. pass = marched once before the characters draw (the static world alone), late = marched by the composite in the finished frame (the characters' camera side in it), off = models only
   public static final int MIRRORS_STEPS = integer("mirrorsSteps", 32); // mirrors: most depth taps along a reflected ray (then 4 refinements)
   public static final int MIRRORS_STEP_PX = integer("mirrorsStepPx", 10); // mirrors: px between the taps (fewer taps on short rays)
   public static final int MIRRORS_STAND_IN_PCT = integer("mirrorsStandInPct", 50); // mirrors: how strongly a stand-in shows, %: the floor guessed where a ray's landing is hidden from the camera (behind a table, a bathtub) is drawn at this share of a real hit's strength, the glass's own look under the rest
   public static final int MIRRORS_CUTAWAY_HOLD_MS = Math.max(0, integer("mirrorsCutawayHoldMs", 750)); // mirrors: a wall carrying a mirror changes its cutaway at most once per this many ms and comes back only after it has not been wanted cut for twice that (stock's own 750 ms cutaway lock, which the fboRenderChunk path skips; the first cut applies at once): at a room corner the cut flag flips every 0.1-0.7 s while the player walks and the wall with its reflection popped in and out (flip save, upstairs bathroom, 2026-10-06); 0 = stock
   public static final boolean MIRRORS_DRAWN_CUT = bool("mirrorsDrawnCut", true); // mirrors: a map wall mirror reflects by the cutaway its wall was last drawn (baked) with, not the live flag the bake follows frames later (the reflection popped off / on ahead of the wall at a corner, flip 2026-10-05); false = the live flag
   public static final boolean MIRRORS_GEOMETRY = bool("mirrorsGeometry", true); // mirrors: a wall mirror's room is rebuilt behind the glass from the game's own tiles (the floor, the furniture drawn with the sprite of its turned facing, so the side the camera never sees, the far wall in the mirror wall's paint), each texel at its depth-map distance; where the frame cannot show what a ray meets (the bathtub's far side, the floor behind it) this stands in instead of a guess
   public static final int MIRRORS_REACH = integer("mirrorsReach", 8); // mirrors: the longest reflected ray, squares
   public static final int MIRRORS_THICKNESS_PCT = integer("mirrorsThicknessPct", 80); // mirrors: how far behind a surface the ray may pass and still hit it, % of a unit of iso depth (x + y + 2z); 80 matched an analytic ray cast best (harness/mirrors/march_sim.py: 1.5 -> 22 % of the pixels wrong, 0.8 -> 5.5 %)
   public static final int MIRRORS_STATIC_REUSE = integer("mirrorsStaticReuse", 30); // mirrors: a pane's static reflection (kept in its own atlas tile: the ortho camera's reflected rays do not move with a pan) is marched again every this many frames, staggered; at once when the pane is new or more of it came on screen (0: every pane every frame)
   public static final boolean MIRRORS_WINDOW_HALF_RES = bool("mirrorsWindowHalfRes", true); // mirrors: a window's static reflection marched for one pixel in each 2x2 block (a quarter of the rays; the pane's tint and the weak reflection hide it), mirrors at every pixel
   public static final int MIRRORS_MODEL_REUSE = integer("mirrorsModelReuse", 3); // mirrors: while the camera and every mirrored model stand still (position, facing, plane), the model layer is kept up to this many frames (the idle animation at a half / third of the rate); 0 or 1: drawn every frame
   public static final int MIRRORS_REFRESH_BUDGET = integer("mirrorsRefreshBudget", 8); // mirrors: most panes re-marched for age in one frame (every 4th frame; new panes and ones coming on screen do not count)
   public static final int MIRRORS_MODEL_RANGE = integer("mirrorsModelRange", 14); // mirrors: characters / vehicles farther than this from the camera's centre (squares) are not mirrored
   public static final int MIRRORS_STATIC_EVERY = integer("mirrorsStaticEvery", 4); // mirrors: the static pass runs at most every this many frames (its fixed cost, the frame made readable, ~20 us at 5K, is paid once for every pane due): a pane new on screen waits as long for its reflection
   public static final boolean MIRRORS_VISIBILITY = bool("mirrorsVisibility", true); // mirrors: the composite counts each pane's visible pixels (read back a few frames later, never waited for); a pane hidden under a roof or behind a building is neither marched nor mirrors anyone
   public static final int MIRRORS_VIEW_LATERAL_PCT = integer("mirrorsViewLateralPct", 0); // mirrors: where a person d squares in front of a mirror shows along it, % of the game camera's true reflection (100: d squares to the side, off a small mirror; 0: straight in front of where they stand, as one sees oneself)
   public static final int MIRRORS_VIEW_DROP_PCT = integer("mirrorsViewDropPct", 50); // mirrors: how much higher a person d squares in front of a mirror shows, % of the camera's true reflection (100: d / 3 levels higher; 0: at their own height, eye level, below the game's high-hung medicine cabinets; 50: the head in a medicine cabinet from the sink)
   public static final int MIRRORS_MODEL_HZ = integer("mirrorsModelHz", 120); // mirrors: the mirrored models are redrawn at most this many times a second (0: every frame); at 240 fps every other frame
   public static final boolean MIRRORS_COMPOSITE_ONCE = bool("mirrorsCompositeOnce", true); // mirrors: one composite after every level's translucent objects instead of one per level (a level's pass costs ~5-9 us fixed; a higher level's translucent object over a lower pane would get the reflection drawn over it)
   public static final boolean MIRRORS_STATIC_FBO = bool("mirrorsStaticFbo", false); // mirrors: the static pass renders the panes' tiles into the atlas framebuffer (tile space) instead of storing into the atlas images (the GL 4.1 path, macOS core, always; elsewhere an A/B)
   public static final int MIRRORS_MAX_MODELS = integer("mirrorsMaxModels", 24); // mirrors: most mirrored model draws a frame (nearest first)
   public static final int DEV_MIRRORS_ALTERNATE = integer("devMirrorsAlternate", 0); // dev: mirrors on / off every N ms in one run (GPU sections mirrors.* tagged .mon / .moff)
   public static final int DEV_MIRRORS_VIEW = integer("devMirrorsView", 0); // dev: 1 the reflection alone at full strength, 2 the glass mask (red) and the static hit (green), 3 the hit distance, 4 the model layer alone, 5 how each static ray ended (green floor shortcut, blue marched hit, magenta a marched hit under the pane's own floor, yellow hidden floor landing (the floor seen last stands in), orange the same with the landing pixel, red reach fallback, cyan the room geometry), 6 the room geometry alone (its colour where it has a texel)
   public static final int DEV_MIRRORS_RECTS_EVERY = integer("devMirrorsRectsEvery", 120); // dev (devMirrorsLog): the composite's reflector rects (window px, with each one's square and epoch_ms) logged 3 frames in every N (1: every frame)
   public static final int DEV_MIRRORS_VIEW_TOGGLE_MS = integer("devMirrorsViewToggleMs", 0); // dev: devMirrorsView only every other N ms (the two --shot-at captures 2 s apart: N = 2000 shows the view in one and the picture in the other)
   public static final String DEV_MIRRORS_CYCLE = string("devMirrorsCycle", ""); // dev: with devMirrorsAlternate, one entry per period in turn: "off" (mirrors off) or a devMirrorsSkip mask, e.g. off,0,1,2,4 (GPU sections tagged .c<index>)
   public static final int DEV_MIRRORS_SKIP = integer("devMirrorsSkip", 0); // dev: bits 1 no static pass, 2 no model pass, 4 no composite, 8 the composite reads nothing (constant colour), 16 no floor-first shortcut, 32 the static pass stores a constant (no march), 64 the static pass without its texture barrier, 32768 the old no-hit stand-in (the ray end one level under the floor, before 2026-10-04), 65536 a hidden floor landing takes the landing pixel instead of the last floor seen, 131072 no room geometry (mirrorsGeometry off for that moment), 262144 the march starts at the glass (before 2026-10-04 afternoon: the first taps hit the mirror itself, glass-coloured speckle zoomed in), 524288 the room geometry reads its tiles' depth maps the old, wrong way round (far floor halves too near: the reflected player's legs cut), 1048576 people in mirrors placed by the camera's true reflection (mirrorsViewLateralPct / mirrorsViewDropPct 100), 2097152 mirrorsViewDropPct 100 alone
   public static final boolean DEV_MIRRORS_LOG = bool("devMirrorsLog", false); // dev: the reflectors and planes of every 300th frame logged
   public static final boolean CAR_GLASS = bool("carGlass", false); // car windows reflect the sky, the sun, the lamps and the ground around them and show the cabin behind them (pzopt.CarGlass; the vehicle shaders are patched at launch)
   public static final boolean CAR_GLASS_SNAP = bool("carGlassSnap", true) && "blit".equals(string("carGlassSnapMode", "live")); // car glass: each car's screen rect of the scene copied before the vehicles draw (the ground it reflects, the scene behind it through the far window)
   public static final String CAR_GLASS_SNAP_MODE = string("carGlassSnapMode", "live"); // car glass: live = no copy, the world read as drawn (one barrier a frame with probes, else before each car); pass = each car drawn body first (glass discarded), then a texture barrier and the glass reading the world as drawn (no copies); blit = each car's rect copied before the vehicles draw; live = no copy, a texture barrier before each car and the world read as drawn so far; off = neither (sky, glints, cabin only)
   public static final int CAR_GLASS_SNAP_SCALE_PCT = integer("carGlassSnapScalePct", 100); // car glass: the snapshot copied at most at this % of the screen resolution
   public static final int CAR_GLASS_SNAP_ATLAS = integer("carGlassSnapAtlas", 2048); // car glass: the snapshot atlas' size (px a side, RGBA8)
   public static final int CAR_GLASS_SNAP_MARGIN_PCT = integer("carGlassSnapMarginPct", 40); // car glass: how far beyond the car the snapshot reaches, % of a square
   public static final boolean CAR_GLASS_SKY_TEXTURE = bool("carGlassSkyTexture", true); // car glass: the game's own sky texture (clouds, sunset) in the reflection; false = its gradient alone
   public static volatile int CAR_GLASS_STRENGTH_PCT; // car glass: 0 = the stock window look, 100 = the glass (live)
   public static final int CAR_GLASS_F0_PCT = integer("carGlassF0Pct", 4); // car glass: reflectance head-on, % (4 = glass, n 1.52)
   public static volatile int CAR_GLASS_REFLECT_PCT; // car glass: the Fresnel reflection scaled, % (live)
   public static final int CAR_GLASS_TRANSMIT_PCT = integer("carGlassTransmitPct", 85); // car glass: light through one pane, %
   public static final int CAR_GLASS_TINT_PCT = integer("carGlassTintPct", 100); // car glass: the green-grey tint of the pane, %
   public static final boolean CAR_GLASS_CABIN = bool("carGlassCabin", true); // car glass: the cabin ray-cast behind the glass (seats, occupants, dashboard; false = a flat dark interior)
   public static volatile int CAR_GLASS_INTERIOR_PCT; // car glass: the cabin's light, % (live)
   public static volatile int CAR_GLASS_RAIN_PCT; // car glass: raindrops on the windows of a car outdoors in rain (their curved faces reflect the sky and the glints), % of the rain's intensity (0 = none) (live)
   public static final String CAR_GLASS_SSR_MODE = string("carGlassSsrMode", "probe"); // car glass: probe = a reflection probe per car (octahedral, its directions marched through the scene depth before the vehicles draw), the glass reads it; pixel = every glass pixel marches its own ray
   public static final int CAR_GLASS_PROBE_HEIGHT_PCT = integer("carGlassProbeHeightPct", 80); // car glass: the probe's centre above the floor, % of the car's height
   public static final boolean CAR_GLASS_LIVE_READS = bool("carGlassLiveReads", false); // car glass with probes: the glass also reads the world framebuffer as drawn (the scene behind the car through the far window, the ground it mirrors per pixel); false = the probe gives those too
   public static final boolean CAR_GLASS_COMPACT = bool("carGlassCompact", true); // car glass: the glass program's fragment unit is a compact one (false: the stock vehicle shader with the glass appended, A/B)
   public static final int CAR_GLASS_MIN_PX = integer("carGlassMinPx", 40); // car glass: a car shorter than this on screen (display px) keeps the stock windows (no glass draw, no probe)
   public static final String CAR_OCCUPANT = string("carOccupant", "impostor"); // car glass: who sits in a car shows through its glass. impostor = the game's own model of each occupant, drawn into an offscreen tile before the cars and composited by the glass with the cabin (pzopt.CarOccupant); proxy = an analytic body in the cabin ray-cast (capsules in the occupant's skin, hair and clothing colours); box = the first release's dark torso box and head ball; stock = the game's unused showPassenger path (the model among the moving objects, under the opaque glass); off = no one
   public static final int CAR_OCCUPANT_SCALE_PCT = integer("carOccupantScalePct", 100); // car glass, carOccupant=impostor: the tile's texel density, % of the world view's
   public static final int DEV_CAR_OCCUPANT_Y_PCT = integer("devCarOccupantYPct", 0); // dev: the occupant's depth anchor (its seat origin in the chassis frame) moved up, % of a square
   public static final int DEV_CAR_OCCUPANT_TURN_DEG = integer("devCarOccupantTurnDeg", 0); // dev: the seated model turned about the vertical in the chassis frame, degrees
   public static final boolean DEV_CAR_OCCUPANT_MIRROR = bool("devCarOccupantMirror", false); // dev: the seated model mirrored across the chassis frame's x
   public static final boolean DEV_CAR_OCCUPANT_FBO_CACHE = bool("devCarOccupantFboCache", false); // dev: the occupant pass puts back the framebuffer from ShadowAtlas's cache instead of TextureFBO.lastID (the version whose cars blinked out)
   public static final boolean DEV_CAR_OCCUPANT_FBO_CHECK = bool("devCarOccupantFboCheck", false); // dev: the occupant pass also asks the bound framebuffer (a glGet) and logs where it differs from the one it puts back
   public static final int DEV_CAR_OCCUPANT_DUMP = integer("devCarOccupantDump", 0); // dev: the N-th occupant tile read back to ~/Zomboid/pzopt-occupant-tile.png (pzopt.CarOccupant)
   public static final boolean CAR_OCCUPANT_OCCLUSION = bool("carOccupantOcclusion", false); // car glass, carOccupant=impostor: the cabin's seats, headrests and floor hide the occupant where they stand between it and the glass (physical: from the iso camera most of a driver then sits behind the seats and the roof); false = the occupant over the cabin wherever the glass shows it (a cutaway)
   public static final int CAR_OCCUPANT_HZ = integer("carOccupantHz", 60); // car glass, carOccupant=impostor: an occupant's tile is drawn again at most this many times a second while the pose moves (at once when the car turns, the zoom or the people change); between, the glass maps its chassis points into the last one
   public static final int CAR_OCCUPANT_MIN_HZ = integer("carOccupantMinHz", 4); // car glass, carOccupant=impostor: a still occupant's tile is drawn again at least this many times a second (the light on them)
   public static final int CAR_OCCUPANT_POSE_EPS_PCT = integer("carOccupantPoseEpsPct", 1); // car glass, carOccupant=impostor: a bone moving more than this (% of a model unit) since the tile was drawn makes the next one due (at most carOccupantHz a second)
   public static final int CAR_OCCUPANT_TURN_DEG = integer("carOccupantTurnDeg", 6); // car glass, carOccupant=impostor: a car that turned (or tilted) this many degrees since its occupants' tile was drawn gets a new one at once (the reprojection's parallax)
   public static final int CAR_OCCUPANT_LIGHT_PCT = integer("carOccupantLightPct", 80); // car glass: the occupants' light inside the cabin, % of what the game lights them with outside (the roof's shade on top, darker low in the cabin)
   public static final boolean CAR_GLASS_EXTRA = bool("carGlassExtra", true); // car glass: glass the artist painted outside the window zones (the CarLuxury coupe's rear quarter windows) and the side mirrors (window-coloured blobs in the door zones) count as glass, found per skin from its diffuse
   public static final boolean CAR_GLASS_VERTEX_ENV = bool("carGlassVertexEnv", false); // car glass: the reflection lookups and Fresnel per vertex (flat panes under the ortho camera: exact), per pixel only under raindrops; measured a loss on the 4090 (the glass draws are too small to hide the vertex fetches), off
   public static final boolean CAR_GLASS_WINDOW_TRIS = bool("carGlassWindowTris", true); // car glass: the glass pass draws only the mesh's window triangles (found once per model from its mask texture); false = the whole mesh, the rest discarded
   static volatile boolean carGlassWindowTrisFailed;
   public static final boolean CAR_GLASS_PROBE_PARALLAX = bool("carGlassProbeParallax", true); // car glass: probe lookups corrected for the texel's offset from the probe centre (the hit distance in the probe's alpha; one more fetch)
   public static final int CAR_GLASS_PROBE_EVERY = integer("carGlassProbeEvery", 8); // car glass: the probes are marched again every N frames (all at once; a moving car's every other frame)
   public static final int CAR_GLASS_PROBE_MOVING_EVERY = integer("carGlassProbeMovingEvery", 4); // car glass: a moving car's probe is marched every N frames
   public static final boolean CAR_GLASS_DEPTH_OFFSET = bool("carGlassDepthOffset", true); // car glass: the glass pass drawn with a polygon offset towards the camera (Mesa compiles the glass copy's depth a rounding step off the stock draw's: the LEQUAL test then failed per pixel, the windows flickered; false = the plain LEQUAL pass, A/B)
   public static final boolean CAR_GLASS_FRAME_BARRIER = bool("carGlassFrameBarrier", false); // car glass (dev): a texture barrier before the vehicles (the live world's coherence for the glass's reads)
   public static final int CAR_GLASS_SSR_STEPS = integer("carGlassSsrSteps", 12); // car glass: screen-space reflection march steps through the scene depth (0 = the sky and the ground plane only)
   public static final int CAR_GLASS_SSR_REACH_PCT = integer("carGlassSsrReachPct", 1000); // car glass: how far the reflected ray is marched, % of a square
   public static final int CAR_GLASS_SSR_THICKNESS_PCT = integer("carGlassSsrThicknessPct", 150); // car glass: how far behind a surface the ray may pass and still hit it (x + y + 2z units), %
   public static volatile int CAR_GLASS_SUN_PCT; // car glass: the sun / moon glint, % (live)
   public static final int CAR_GLASS_SUN_ROUGH_PCT = integer("carGlassSunRoughPct", 4); // car glass: the sun glint's GGX roughness, % (the disk's size on glass)
   public static final int CAR_GLASS_LAMP_ROUGH_PCT = integer("carGlassLampRoughPct", 12); // car glass: the lamps' glint roughness, %
   public static final float CAR_GLASS_SEAT_FWD = integer("carGlassSeatFwd", 1) < 0 ? -1F : 1F; // car glass (dev): the chassis axis the seats face, +1 = +z
   public static final int CAR_GLASS_SEAT_HIP_PCT = integer("carGlassSeatHipPct", 15); // car glass (dev): the seat's hip point above the script's inside position, % of a square
   public static final int CAR_GLASS_CABIN_Y_PCT = integer("carGlassCabinYPct", 0); // car glass (dev): script chassis positions -> render frame, vertical offset, % of a square
   public static final int CAR_GLASS_GROUND_OFFSET_PCT = integer("carGlassGroundOffsetPct", 0); // car glass (dev): the reflected ground plane moved up, % of a square
   public static final int DEV_CAR_GLASS_VIEW = integer("devCarGlassView", 0); // dev: car glass shows 1 normal, 2 reflection, 3 cabin, 4 chassis position, 5 seats, 6 Fresnel x4, 7 snapshot, 8 glints, 9 see-through / drop / probe, 10 every car texel by its glass class (window red, extra glass green, mirror cyan, unmarked blue, other zones grey), 11 the diffuse with the glass tinted
   public static final int DEV_CAR_GLASS_VIEW_CYCLE = integer("devCarGlassViewCycle", 0); // dev: the car glass dev view steps 0..9 every N ms (logged with its epoch)
   public static final String DEV_CAR_GLASS_VIEW_LIST = string("devCarGlassViewList", ""); // dev: with devCarGlassViewCycle, the dev views it steps through (comma-separated) instead of 0..9
   public static final int DEV_CAR_GLASS_SKIP = integer("devCarGlassSkip", 0); // dev: car glass cost probes, bits: 1 no probe pass, 2 the probe framebuffer switch without the draw, 4 the probe draw without world reads, 8 the glass without live world reads, 16 no cabin ray-cast, 32 no probe lookups, 64 no sky texture, 128 no lamp glints, 256 the glass draw alone (constant colour), 512 the environment per pixel (not per vertex), 1024 no occupant impostor pass, 2048 the occupant pass drawn but not read by the glass, 4096 the occupant tile at 50 % density, 8192 the occupant tile drawn every frame (no reuse), 16384 the occupant pass without its model draws (bind + clear)
   public static final String DEV_CAR_GLASS_CYCLE = string("devCarGlassCycle", ""); // dev: with devCarGlassAlternate, one entry per period: off (glass off) or devCarGlassSkip bits; GPU sections tagged .cg<entry>
   public static final int DEV_CAR_GLASS_ALTERNATE = integer("devCarGlassAlternate", 0); // dev: car glass on / off every N ms in one run (GPU sections "moving" / "carGlassSnap" split .cgon / .cgoff with gpuSections=true)
   public static volatile int HDR_UI_NITS; // UI / SDR white on the panel in cd/m², 0 = the desktop's reference white
   public static volatile int HDR_PAPER_PCT; // world paper white, % of the UI white (lower = more room for highlights)
   public static volatile int HDR_PEAK_NITS; // brightest highlight in cd/m², 0 = the panel's peak
   public static volatile int HDR_ITM_PCT; // highlight expansion strength (0 = the SDR picture in an HDR container)
   public static volatile int HDR_BLOOM_PCT; // bloom from the expanded highlights, % strength (0 = off)
   public static final int DEV_HDR_TRACE_MS = integer("devHdrTraceMs", 0); // dev: every N ms a console line with the player's facing, the world's average light, the night key and the light-map stats
   public static volatile int HDR_LIGHT_PCT; // light-map gain: lit squares (lamps, torches, fire) brightened by their light over the ambient, % strength
   public static volatile int HDR_GLINT_PCT; // sun glints and sky reflections on water and puddles, lamp glints at night, % strength (0 = off)
   public static volatile int HDR_SUN_PCT; // sunlit outdoors on a clear day brighter than the SDR picture by this %, scaled by sun height and clouds (0 = daylight as SDR)
   public static volatile int HDR_SATURATION_PCT; // extra world chroma, %
   public static final boolean HDR_UNTESTED_PLATFORMS = bool("hdrUntestedPlatforms", false); // dev: allow the untested Windows (scRGB) HDR path; HDR is Linux / macOS without it
   public static final String HDR_TUNE = string("hdrTune", "");
   public static final boolean HDR_WIN_FLIP = bool("hdrWinFlip", true); // Windows HDR: write the interop texture upside down (D3D rows run top-down); false if a driver maps it the other way
   public static final String HDR_DUMP_AT = string("hdrDumpAt", ""); // dev: seconds after the world is up at which frames are dumped (with the tune file's [sweep] sets), e.g. "20,35" // dev: tuning file re-read once a second (key=value lines, [sweep] sets for pzopt-hdr.req dumps)
   public static final String VEHICLE_SMOOTH = string("vehicleSmooth", "interp"); // off | interp | extrap: vehicles, their passengers and the driving camera drawn between / past the 100 Hz physics steps (pzopt.VehicleSmooth)
   public static final boolean DRIVE_CAMERA_LATE = bool("driveCameraLate", false); // the driving camera centred on the driver after the frame's vehicle update (stock: inside the player's update, before the car moved: the car jumps a physics step back and forth on screen)
   public static final boolean DRIVE_LOOK_SMOOTH = bool("driveLookSmooth", true); // the driving look-ahead kept fractional and paced by nanoTime (stock: whole pixels, currentTimeMillis steps) (pzopt.DriveCamera)
   public static final boolean FRAME_CLOCK_SMOOTH = bool("frameClockSmooth", false); // each frame simulates a whole number of display periods (median frame time) instead of the measured previous frame; the wall-clock difference fed back slowly (pzopt.FrameClock)
   public static final boolean CAMERA_SCREEN_PIXELS = bool("cameraScreenPixels", true); // zoomed in (zoom < 1): the camera offset snapped to whole screen pixels, not whole offscreen pixels (2 screen px at zoom 0.5) (pzopt.DriveCamera.snap)
   public static final boolean VSYNC_LOCK = bool("vsyncLock", false); // vsync on + a cap that divides the refresh: swap interval refresh/cap and a limiter 3 % faster, so vsync paces every frame (stock: the CPU limiter drifts against the vblank: 3+1 refresh pairs) (pzopt.VsyncLock)
   public static final int PHYSICS_STEP_HZ = integer("physicsStepHz", 100); // vehicle physics fixed step rate (stock 100 Hz); with physicsStepMode=frame the smallest rate a frame is split at
   public static final String PHYSICS_STEP_MODE = string("physicsStepMode", "fixed"); // fixed (stock: whole steps, remainder carried) | frame (each frame's time in equal steps, nothing carried)
   public static final boolean DEV_DRIVE_JITTER = bool("devDriveJitter", false); // measurement: per-frame vehicle / camera / physics-step log pzopt-drivejitter.out (pzopt.DriveJitter, harness/drivejitter.py)
   // Candidate B (2026-09-26, pzopt.Darkness / pzopt.Grade): darkness floor, remembered places, colour grading. The
   // tab keys are live (loadLive); these are the fixed / dev ones.
   public static final boolean COLOR_GRADING_DITHER = bool("colorGradingDither", false); // triangular dither of one 8-bit step after the LUT; off: the world image is already 8-bit before the grade and the looks barely stretch the darks, and the hash cost ~12 us / frame at 5120x2160
   public static final boolean COLOR_GRADING_FUSED = bool("colorGradingFused", true); // the plain world path of screen.frag as one 65³ table (stock tail + grade): cheaper than the stock shader; false = stock shader + the grade after it
   public static final String DARKNESS_FLOOR_TINT = string("darknessFloorTint", "0.92,0.98,1.12"); // colour of the darkness floor's lift (normalised to luminance 1)
   public static final int DEV_DARK_ALTERNATE = integer("devDarkAlternate", 0); // dev: every N frames the render-side part (grade + remembered-places pass) flips on / off; GPU sections screen.on/off, vispoly.on/off
   public static final boolean DEV_DARK_STATS = bool("devDarkStats", false); // dev: a darkness / grading stats line every 10 s
   public static final int DEV_SPRITE_FILTER_ALTERNATE = integer("devSpriteFilterAlternate", 0); // dev: every N frames the sprite filter flips on / off; GPU sections composite.on / composite.off
   public static final boolean DEV_SPRITE_FILTER_SHOT_AB = bool("devSpriteFilterShotAb", false); // dev: with --shot-at, stock until 3 s into the hold: shot-game.png stock, shot2-game.png with spriteFilter
   public static final String DEV_SPRITE_FILTER_CYCLE = string("devSpriteFilterCycle", ""); // dev: with devSpriteFilterAlternate, cycle these entries (stock, nearest, rgss4, rgss4+skip, rgss2, bias, trilinear); GPU section composite.<entry>
   public static final String DEV_SPRITE_FILTER_SHOT_MODES = string("devSpriteFilterShotModes", ""); // dev: with --shot-at, these cycle entries one after the other through the hold, four slots clear of its screenshots (logged; pair with devCapture, harness/spritefilter/modes.py)
   public static final boolean DEV_SPRITE_FILTER_STATS = bool("devSpriteFilterStats", false); // dev: a sprite-filter line every 10 s (mode, frames per zoom regime, variants)
   public static final boolean DEV_GRADE_TRACE = bool("devGradeTrace", false); // dev: a console line per LUT bake (look + weights)
   public static final int DEV_GRADE_ABLATE = integer("devGradeAblate", 0); // dev: cost ablation of the grade shader (0 full, 1 no shaper, 2 no LUT fetch, 3 wrapper only)
   public static final int DEV_GRADE_REBAKE_MS = integer("devGradeRebakeMs", 0); // dev: force a LUT bake + upload this often (upload cost rig)
   public static final String GRADE_TUNE = string("gradeTune", ""); // dev: a looks file re-read once a second (pzopt.Grade.Tune)
   public static final int DEV_MEMORY_DESAT_PCT = integer("devMemoryDesatPct", 90); // remembered places: desaturation of what is out of sight
   public static final int DEV_MEMORY_DIM_PCT = integer("devMemoryDimPct", 78); // remembered places: brightness kept
   public static final int DEV_MEMORY_SOFT_PCT = integer("devMemorySoftPct", 100); // remembered places: edge softness (0 = the stock vision edge)
   public static final int MEMORY_FADE_MS = integer("memoryFadeMs", 0); // remembered places: what goes out of sight turns grey over this long (0 = at once, no pre-pass; 250 costs ~13 us / frame at 5120x2160, run dk-cost4)
   public static final int MEMORY_FADE_SCALE = integer("memoryFadeScale", 4); // remembered places: the fade pre-pass runs at 1/N of the vision texture per axis (1: +22 us / frame at 5120x2160)
   public static final int DEV_MEMORY_RING_TEXELS = integer("devMemoryRingTexels", 6); // remembered places: how far inside the shadow the soft edge reaches, in vision texels
   public static final boolean DEV_FOG_NO_DRAW = bool("devFogNoDraw", false); // measurement: the fog pass does everything but the rectangle draw call
   public static final boolean DEV_FOG_FLAT = bool("devFogFlat", false);
   public static final int DEV_FOG_DEPTH_VIEW = integer("devFogDepthView", 0); // measurement: the composite shows 1 = the scene depth, 2 = the fog texel depth, 3 = the fog buffer alpha (R/G = depth * 255 integer / fraction)
   public static final boolean FOG_DEPTH_COPY = bool("fogDepthCopy", false); // keep the offscreen depth a renderbuffer and copy it for the fog pass (measurement / driver fallback) // measurement: the rectangles with a flat fragment shader (no noise fetches)
   public static final int FOG_MASK_FRAMES = integer("fogMaskFrames", 20); // a chunk's fog masks (which squares take fog) are refreshed this often; 0 = read every square every frame
   // Live RawMouse settings: changing any of them resets its collect-time velocity and signed fractional motion.
   public static volatile int MOUSE_SENSITIVITY_TWENTIETHS; // option key: mouseSensitivity; 2..80 units of 0.05, default 20 = 1.0
   public static volatile boolean MOUSE_ACCELERATION; // option key: mouseAcceleration; default off
   public static volatile int MOUSE_ACCELERATION_ONSET_CPS; // option key: mouseAccelerationOnsetCps; counts per collect-time second
   public static volatile int MOUSE_ACCELERATION_SLOPE_PCT_PER_KCPS; // option key: mouseAccelerationSlopePctPerKcps; gain percent per 1,000 counts/s
   public static volatile int MOUSE_ACCELERATION_CAP_PCT; // option key: mouseAccelerationCapPct; max gain percent of linear
   public static volatile int DARKNESS_FLOOR_PCT; // luminance floor of seen squares, % of full light (0 = off)
   public static volatile boolean DARKNESS_FLOOR_BASEMENTS; // the floor also below ground
   public static volatile boolean MEMORY_TINT; // remembered places: out of sight desaturated / dimmed, remembered rooms kept
   public static volatile int MEMORY_TINT_PCT; // strength of the remembered look
   public static volatile int MEMORY_LIGHT_PCT; // light of remembered rooms the game fades to black, % of full light
   public static volatile boolean COLOR_GRADING; // time-of-day / weather LUT
   public static volatile int COLOR_GRADING_PCT; // strength of the grade
   public static volatile int COLOR_GRADING_NIGHT_PCT; // strength of the night-vision (Purkinje) shift within it
   public static volatile boolean TORCH_SOURCE; // torchSource: a carried light shines from the drawn item's lens, not the holder's feet (pzopt.TorchSource)
   public static volatile String TORCH_SOURCE_AIM; // torchSource: the beam's direction, look (stock: where the player looks) or item (the item's own axis)
   public static volatile int TORCH_SOURCE_HOLD; // torchSource: hundredths of a square the lens may sway before the native's position follows while the holder stands still
   public static volatile boolean TORCH_SOURCE_FRESH; // torchSource: the per-pixel consumers re-solve the lens from the pose the frame draws (not the previous frame's)
   public static volatile boolean TORCH_SOURCE_WALL_CLAMP;
   public static volatile boolean TORCH_SOURCE_VEHICLES;
   public static volatile boolean TORCH_SOURCE_PITCH; // torchSource + torchSourceAim=item: pixelLight's beam reach follows the item's tilt (a torch pointed down lights the ground nearer) // torchSource: headlights / tail lights drawn per pixel from where the car is shown this frame (vehicleSmooth), not the last physics step // torchSource: the native's position stays on the holder's side of its square's walls, closed doors and windows
   public static volatile boolean PPL_TORCH_FEET_GLOW; // pixelLight: a handheld torch also lights a small disc round the holder's feet (off: the beam alone; the Workshop report of 2026-09-28 wanted the circle gone)
   // Candidate A (2026-09-26, pzopt.SpriteFilter): how the chunk composite samples the baked world.
   public static volatile String SPRITE_FILTER; // stock | sharp (texel-aware: anti-aliased point sampling zoomed in, supersampled mips zoomed out) | nearest (point sampling at every magnified zoom)
   public static volatile String SPRITE_FILTER_MIN; // sharp, zoomed out: the taps read one mip level, floor(log2(texels a pixel)): rgssa2 (two diagonal taps spread by how far the pixel exceeds that level's texel; default) | rgssa (four rotated-grid taps spread the same way) | floor (one bilinear tap) | rgss4 (the full grid) | rgss2 | bias (one trilinear tap half a level sharp) | trilinear (stock)
   public static volatile int SPRITE_FILTER_SHARPNESS_PCT; // sharp, zoomed in: 100 = a texel edge blended over exactly one screen pixel (box coverage); 200 = half a pixel
   public static volatile boolean SPRITE_FILTER_SPRITES; // sharp: the tiles drawn per frame (outside the chunk textures) get the same filtering
   public static volatile String SPRITE_FILTER_KERNEL; // sharp, zoomed in: box (linear ramp over the edge pixel: exact coverage) | smooth (smoothstep ramp)
   public static volatile boolean SPRITE_FILTER_MIP_TRIM; // sharp: chunk textures build only the mip levels the sharper minification reads (1 instead of 3 at the widest zoom), less GPU per bake
   public static volatile int SPRITE_FILTER_LOD_BIAS_PCT; // sharp, zoomed out: the taps read level floor(log2(texels a pixel) - this / 100); 0 = level 1 from 2x (cheapest), 50 = level 0 up to 2.83x (sharper, more bandwidth)
   public static volatile boolean SPRITE_FILTER_INTEGER_AA; // sharp: the edge blend also at whole-multiple zooms (50 %, 25 %), where point sampling is already exact (smoother sub-pixel glides, a little softer)
   public static volatile boolean SPRITE_FILTER_SHARP_MIPS; // sharp, zoomed out 2x and more: mip level 1 of each baked chunk texture is a Lanczos-2 downsample instead of the 2x2 box (pzopt.SpriteMips)
   public static volatile boolean SPRITE_FILTER_LINEAR_LIGHT; // sharp, zoomed out: the taps (and the sharp mip level) averaged in linear light (gamma 2), so thin bright lines keep their brightness; stock filters the encoded values
   public static volatile boolean SPRITE_FILTER_SKIP_EMPTY; // sharp, zoomed out: one coarse probe skips the taps where the texture is empty
   public static volatile boolean ENHANCEMENTS_ENABLED; // Enhancements tab master switch: false = every enhancement off (GATED), the tab's choices kept
   public static volatile boolean PROFILER_ENABLED; // Profiler tab master switch: false = no overlay, sampling or frame log outside harness runs (GATED)
   public static volatile boolean OVERLAY_SAMPLING; // measure at all (ring, GL timer queries, sampler thread); off by default since 2026-09-21
   public static volatile boolean OVERLAY;
   public static volatile boolean OVERLAY_LOG;
   public static volatile String CONSOLE_LOG; // all | warnings | errors | off: which [pzopt] lines pzopt.Log writes to the console
   // The overlay's elements, each a dropdown on the Profiler options tab: "off" or the element's own options.
   public static volatile String OVERLAY_STATS; // off | fps (the fps line) | tails (+ p99 / jitter lines) | full (+ utilization); tails by default since 2026-09-23 (overlay cost pass)
   public static volatile String OVERLAY_TREE; // the game-thread tree: off | 0 (phases only) | 3 | 5 | 8 sub-phases per phase
   public static volatile String OVERLAY_VERDICT; // off | short ("GPU bound") | detailed (+ the two biggest game-thread sub-phases)
   public static volatile String OVERLAY_GRAPH; // the frame-time graph: off | 240 | 480 | 960 frames (2 px each)
   public static volatile boolean OVERLAY_POWER; // the power line: CPU / GPU / SoC / battery watts and J per frame (pzopt.Power), 2026-09-24
   public static volatile String OVERLAY_FLAME; // the game-thread flame graph: off | right (900 px column) | right-wide (1400) | below (under the frame graph); off by default since 2026-09-23 (the heaviest element)
   public static volatile boolean OVERLAY_TEXTURE; // draw the panel into a texture at each 4 Hz refresh, one quad per frame (Overlay.renderToTexture); false = every glyph as a sprite every frame
   public static volatile int OVERLAY_REFRESH_MS; // how often the overlay's numbers, tree and texture are refreshed
   public static volatile int OVERLAY_GRAPH_HZ; // frame-graph redraws per second into the overlay texture; 0 = drawn live every frame (default: 30 Hz measured no cheaper on the Mac)
   public static volatile int OVERLAY_FLAME_DEPTH; // rows of the flame graph (frames from GameWindow.frameStep up)
   public static volatile int GAME_THREAD_PROFILE_HZ; // game-thread stack samples per second (10..1000); sampling runs when the tree, the flame graph, the detailed verdict or the frame log wants it 25 since 2026-09-23: each capture pauses the game thread ~150 us on the Mac, 1.5 % of wall at 100 Hz
   public static volatile String OVERLAY_FONT; // auto = CodeSmall / CodeMedium / CodeLarge by screen height (Overlay.font), or a UIFont name
   public static volatile String OVERLAY_CORNER;
   public static volatile boolean OVERLAY_FPS_COLOR; // colour the fps number (see Overlay.fpsColor)
   public static volatile boolean OVERLAY_FPS_FOLLOW_CAP; // thresholds are % of the cap when one is set; else the fixed fps ones
   public static volatile int OVERLAY_FPS_CAP_BLUE_PCT; // "at the cap": at or above this % of it
   public static volatile int OVERLAY_FPS_CAP_GREEN_PCT;
   public static volatile int OVERLAY_FPS_CAP_YELLOW_PCT; // below: red
   public static volatile int OVERLAY_FPS_BLUE_ABOVE; // uncapped / follow-cap off: fixed fps thresholds
   public static volatile int OVERLAY_FPS_GREEN_ABOVE;
   public static volatile int OVERLAY_FPS_YELLOW_ABOVE; // below: red
   public static volatile String OVERLAY_FPS_COLOR_BLUE; // a name Overlay.color knows or RRGGBB hex
   public static volatile String OVERLAY_FPS_COLOR_GREEN;
   public static volatile String OVERLAY_FPS_COLOR_YELLOW;
   public static volatile String OVERLAY_FPS_COLOR_RED;

   static {
      loadLive();
   }

   private static void loadLive() {
      loadingLive = true;
      MOUSE_SENSITIVITY_TWENTIETHS = mouseSensitivityUnits();
      MOUSE_ACCELERATION = bool("mouseAcceleration", false);
      MOUSE_ACCELERATION_ONSET_CPS = Math.max(0, Math.min(8000, integer("mouseAccelerationOnsetCps", 1000)));
      MOUSE_ACCELERATION_SLOPE_PCT_PER_KCPS = Math.max(0, Math.min(200, integer("mouseAccelerationSlopePctPerKcps", 50)));
      MOUSE_ACCELERATION_CAP_PCT = Math.max(100, Math.min(300, integer("mouseAccelerationCapPct", 200)));
      ENHANCEMENTS_ENABLED = bool("enhancementsEnabled", true);
      PROFILER_ENABLED = bool("profilerEnabled", true);
      // The Enhancements tab's keys (2026-09-25): upscaling, the HDR sliders (not hdr / hdrAuto: on Linux they pick the
      // window the game starts with), ambient occlusion; pzopt.Enhancements applies a change (UserOptions.set)
      UPSCALER = string("upscaler", "off").trim().toLowerCase(java.util.Locale.ROOT);
      UPSCALER_QUALITY = string("upscalerQuality", "quality").trim().toLowerCase(java.util.Locale.ROOT);
      UPSCALER_SCALE_PCT = integer("upscalerScalePct", 0);
      FSR_SHARPNESS_PCT = Math.max(0, Math.min(100, integer("fsrSharpnessPct", 80)));
      DLSS_SHARPEN = bool("dlssSharpen", false);
      UPSCALER_OBJECT_MV = bool("upscalerObjectMv", true);
      DYN_RES = bool("dynRes", false);
      DYN_RES_TARGET_PCT = Math.max(30, Math.min(100, integer("dynResTargetPct", 90)));
      DYN_RES_MIN_PCT = Math.max(25, Math.min(100, integer("dynResMinPct", 50)));
      DYN_RES_MAX_PCT = Math.max(DYN_RES_MIN_PCT, Math.min(200, integer("dynResMaxPct", 100))); // above 100: supersampling (fsr1 / bicubic), capped to the offscreen texture
      DYN_RES_FPS = Math.max(0, integer("dynResFps", 0));
      DYN_RES_CONTROLLER = string("dynResController", "model").trim().toLowerCase(java.util.Locale.ROOT);
      DYN_RES_UPSCALER = string("dynResUpscaler", "taau").trim().toLowerCase(java.util.Locale.ROOT);
      DYN_RES_STEP_PX = Math.max(1, Math.min(64, integer("dynResStepPx", 8)));
      DYN_RES_UP_PER_MILLE = Math.max(1, Math.min(500, integer("dynResUpPerMille", 5)));
      DYN_RES_DOWN_PER_MILLE = Math.max(1, Math.min(500, integer("dynResDownPerMille", 60)));
      DYN_RES_DEADBAND_PCT = Math.max(0, Math.min(20, integer("dynResDeadbandPct", 2)));
      DYN_RES_NATIVE_BYPASS = bool("dynResNativeBypass", true);
      DYN_RES_STARVE_GATE = bool("dynResStarveGate", true);
      DYN_RES_BAKE_TERM = bool("dynResBakeTerm", true);
      DYN_RES_PROBE = bool("dynResProbe", true);
      DYN_RES_CPU_AWARE = bool("dynResCpuAware", true);
      DYN_RES_CPU_TARGET_PCT = Math.max(50, Math.min(100, integer("dynResCpuTargetPct", 95)));
      DYN_RES_AXES_X = "x".equalsIgnoreCase(string("dynResAxes", "both").trim());
      DYN_RES_UP_DELAY_FRAMES = Math.max(0, Math.min(240, integer("dynResUpDelayFrames", 8)));
      DYN_RES_SIGMA_PCT = Math.max(0, Math.min(400, integer("dynResSigmaPct", 100)));
      DYN_RES_DRIFT_PER_MILLE = Math.max(1, Math.min(100, integer("dynResDriftPerMille", 5)));
      TAAU_MAX_FRAMES = Math.max(1, Math.min(64, integer("taauMaxFrames", 16)));
      TAAU_CLIP_PCT = Math.max(25, Math.min(400, integer("taauClipPct", 125)));
      TAAU_SAMPLE_SIGMA_PCT = Math.max(15, Math.min(200, integer("taauSampleSigmaPct", 35)));
      TAAU_JITTER = bool("taauJitter", true);
      TAAU_JITTER_ADAPTIVE = bool("taauJitterAdaptive", true);
      TAAU_WARM_BYPASS = bool("taauWarmBypass", true);
      TAAU_WARM_EVERY = Math.max(1, Math.min(16, integer("taauWarmEvery", 4)));
      DYN_RES_BAKE_FEEDFORWARD = bool("dynResBakeFeedforward", false);
      DYN_RES_SHARPEN_RAMP = bool("dynResSharpenRamp", true);
      DLSS_WATER_CURRENT = bool("dlssWaterCurrent", true);
      DLSS_WATER_HISTORY_PCT = Math.max(0, Math.min(90, integer("dlssWaterHistoryPct", 60)));
      DLSS_PRESET = string("dlssPreset", "e").trim().toLowerCase(java.util.Locale.ROOT);
      DLSS_OUTPUT_PCT = integer("dlssOutputPct", 67);
      DLSS_OUTPUT_FILTER = string("dlssOutputFilter", "rcas").trim().toLowerCase(java.util.Locale.ROOT);
      AO = bool("ambientOcclusion", false);
      AO_SCALE_PCT = integer("aoScalePct", 50);
      AO_RADIUS_PCT = integer("aoRadiusPct", 60);
      AO_STRENGTH_FLOOR_PCT = aoStrength("aoStrengthFloorPct");
      AO_STRENGTH_WALL_PCT = aoStrength("aoStrengthWallPct");
      AO_STRENGTH_OBJECT_PCT = aoStrength("aoStrengthObjectPct");
      AO_STRENGTH_VEGETATION_PCT = aoStrength("aoStrengthVegetationPct");
      String plant = raw("aoStrengthPlantPct");
      AO_STRENGTH_PLANT_PCT = register("aoStrengthPlantPct", plant == null ? AO_STRENGTH_VEGETATION_PCT : parseInt("aoStrengthPlantPct", plant, AO_STRENGTH_VEGETATION_PCT), 100);
      SSR = bool("reflections", false);
      CAR_GLASS_STRENGTH_PCT = Math.max(0, Math.min(400, integer("carGlassStrengthPct", 100)));
      CAR_GLASS_RAIN_PCT = Math.max(0, Math.min(400, integer("carGlassRainPct", 100)));
      CAR_GLASS_SUN_PCT = Math.max(0, Math.min(400, integer("carGlassSunPct", 100)));
      CAR_GLASS_INTERIOR_PCT = Math.max(0, Math.min(400, integer("carGlassInteriorPct", 100)));
      CAR_GLASS_REFLECT_PCT = Math.max(0, Math.min(400, integer("carGlassReflectPct", 100)));
      SSR_STRENGTH_PCT = Math.max(0, Math.min(100, integer("reflectionStrengthPct", 45)));
      SSR_PUDDLES = bool("reflectionPuddles", true);
      OCCLUDED_OUTLINES = bool("occludedZombieOutlines", false);
      OCCLUDED_OUTLINE_IGNORE_PLANTS = bool("occludedOutlineIgnorePlants", true);
      OCCLUDED_OUTLINE_WIDTH = Math.max(1, Math.min(4, integer("occludedOutlineWidth", 1)));
      OCCLUDED_OUTLINE_OPACITY = Math.max(0, Math.min(100, integer("occludedOutlineOpacityPct", 70)));
      OCCLUDED_OUTLINE_COLOUR = string("occludedOutlineColour", "FFC740");
      BLOOD_WET = bool("bloodWet", false);
      BLOOD_WET_MINUTES = Math.max(1, integer("bloodWetMinutes", 120));
      BLOOD_REFLECT_PCT = Math.max(0, Math.min(200, integer("bloodReflectPct", 100)));
      BLOOD_SHEEN_PCT = Math.max(0, Math.min(200, integer("bloodSheenPct", 45)));
      BLOOD_GLINT_PCT = Math.max(0, Math.min(300, integer("bloodGlintPct", 100)));
      SUN_SHADOWS = bool("sunShadows", false);
      SUN_SHADOW_STRENGTH_PCT = integer("sunShadowStrengthPct", 45);
      SUN_SHADOW_SOFTNESS_PCT = integer("sunShadowSoftnessPct", 25); // 25 since 2026-09-27 (detailed shadows: a tree's leaves and a body's limbs stay readable; 100 blurred every shadow past a square from its caster into a blob)
      SUN_SHADOW_CHARACTERS = bool("sunShadowCharacters", true);
      SUN_SHADOW_VEHICLES = bool("sunShadowVehicles", true);
      SUN_SHADOW_TORCHES = bool("sunShadowTorches", true);
      SUN_SHADOW_TREE_CARDS = bool("sunShadowTreeCards", true);
      SUN_SHADOW_MESHES = bool("sunShadowMeshes", true);
      SUN_SHADOW_WALL_CUT = bool("sunShadowWallCut", true);
      SUN_SHARE_WALL_HEIGHT = bool("sunShareWallHeight", true);
      SUN_SHADOW_ANIMALS = bool("sunShadowAnimals", true);
      SUN_SHADOW_STOCK_FADE_PCT = Math.max(0, Math.min(100, integer("sunShadowStockFadePct", 85)));
      SUN_SHADOW_RATE = string("sunShadowRate", "frame").trim().toLowerCase(java.util.Locale.ROOT);
      int rateHz = 0;
      if (!SUN_SHADOW_RATE.equals("frame")) {
         try {
            rateHz = Math.max(1, Math.min(240, Integer.parseInt(SUN_SHADOW_RATE)));
         } catch (NumberFormatException e) {
            rateHz = 0; // unknown: every frame
         }
      }
      SUN_SHADOW_RATE_HZ = rateHz;
      MOON_SHADOWS = bool("moonShadows", true);
      MOON_SHADOW_PCT = integer("moonShadowPct", 100);
      CLOUD_SHADOWS = bool("cloudShadows", true);
      CLOUD_OPACITY_PCT = Math.max(0, Math.min(100, integer("cloudOpacityPct", 85)));
      CLOUD_SPEED_PCT = integer("cloudSpeedPct", 100);
      CLOUD_SCALE_PCT = Math.max(25, Math.min(400, integer("cloudScalePct", 100)));
      FOLIAGE_SWAY = bool("foliageSway", false);
      FOLIAGE_SWAY_PCT = Math.max(0, Math.min(400, integer("foliageSwayPct", 100)));
      FOLIAGE_SWAY_TAPS = Math.max(1, Math.min(4, integer("foliageSwayTaps", 1)));
      WIND_SPRITE_SWAY = bool("windSpriteSway", true);
      DEV_SWAY_VIEW = integer("devSwayView", 0);
      GOD_RAYS = bool("godRays", false);
      GOD_RAYS_STRENGTH_PCT = Math.max(0, Math.min(400, integer("godRaysStrengthPct", 100)));
      GOD_RAYS_HAZE_PCT = Math.max(0, Math.min(400, integer("godRaysHazePct", 100)));
      GOD_RAYS_DUST_PCT = Math.max(0, Math.min(400, integer("godRaysDustPct", 100)));
      GOD_RAYS_PATCH_PCT = Math.max(0, Math.min(200, integer("godRaysPatchPct", 100)));
      GOD_RAYS_METHOD = string("godRaysMethod", "volume").trim().toLowerCase(java.util.Locale.ROOT);
      GOD_RAYS_APERTURES = bool("godRaysApertures", true);
      GOD_RAYS_AP_DIRECT = bool("godRaysApDirect", true);
      GOD_RAYS_LOCAL = bool("godRaysLocal", true);
      GOD_RAYS_MOTES = bool("godRaysMotes", true);
      GOD_RAYS_SOFT_PCT = Math.max(0, Math.min(300, integer("godRaysSoftPct", 100)));
      GOD_RAYS_GLINT_PCT = Math.max(0, Math.min(300, integer("godRaysGlintPct", 100)));
      GOD_RAYS_LOCAL_METHOD = string("godRaysLocalMethod", "analytic").trim().toLowerCase(java.util.Locale.ROOT);
      GOD_RAYS_HAZE_MODE = string("godRaysHazeMode", "auto").trim().toLowerCase(java.util.Locale.ROOT);
      GOD_RAYS_LOCAL_PCT = Math.max(0, Math.min(400, integer("godRaysLocalPct", 100)));
      HDR_UI_NITS = integer("hdrUiNits", 0);
      HDR_PAPER_PCT = integer("hdrPaperPct", 100);
      HDR_PEAK_NITS = integer("hdrPeakNits", 0);
      HDR_ITM_PCT = integer("hdrItmPct", 50);
      HDR_BLOOM_PCT = integer("hdrBloomPct", 30);
      HDR_LIGHT_PCT = integer("hdrLightPct", 100);
      HDR_GLINT_PCT = integer("hdrGlintPct", 100);
      HDR_SUN_PCT = integer("hdrSunPct", 60);
      HDR_SATURATION_PCT = integer("hdrSaturationPct", 0);
      DARKNESS_FLOOR_PCT = integer("darknessFloorPct", 0);
      DARKNESS_FLOOR_BASEMENTS = bool("darknessFloorBasements", false);
      MEMORY_TINT = bool("memoryTint", false);
      MEMORY_TINT_PCT = integer("memoryTintPct", 70);
      MEMORY_LIGHT_PCT = integer("memoryLightPct", 10);
      COLOR_GRADING = bool("colorGrading", false);
      COLOR_GRADING_PCT = integer("colorGradingPct", 100);
      COLOR_GRADING_NIGHT_PCT = integer("colorGradingNightPct", 100);
      PPL_TORCH_FEET_GLOW = bool("pplTorchFeetGlow", true);
      TORCH_SOURCE = bool("torchSource", false);
      TORCH_SOURCE_AIM = string("torchSourceAim", "look").trim().toLowerCase(java.util.Locale.ROOT);
      TORCH_SOURCE_HOLD = Math.max(0, integer("torchSourceHold", 15));
      TORCH_SOURCE_FRESH = bool("torchSourceFresh", true);
      TORCH_SOURCE_WALL_CLAMP = bool("torchSourceWallClamp", true);
      TORCH_SOURCE_VEHICLES = bool("torchSourceVehicles", true);
      TORCH_SOURCE_PITCH = bool("torchSourcePitch", true);
      SPRITE_FILTER = string("spriteFilter", "stock").trim().toLowerCase(java.util.Locale.ROOT);
      SPRITE_FILTER_MIN = string("spriteFilterMin", "rgssa2").trim().toLowerCase(java.util.Locale.ROOT);
      SPRITE_FILTER_SHARPNESS_PCT = integer("spriteFilterSharpnessPct", 100);
      SPRITE_FILTER_SKIP_EMPTY = bool("spriteFilterSkipEmpty", true);
      SPRITE_FILTER_LOD_BIAS_PCT = Math.max(0, Math.min(100, integer("spriteFilterLodBiasPct", 0)));
      SPRITE_FILTER_SPRITES = bool("spriteFilterSprites", true);
      SPRITE_FILTER_KERNEL = string("spriteFilterKernel", "box").trim().toLowerCase(java.util.Locale.ROOT);
      SPRITE_FILTER_MIP_TRIM = bool("spriteFilterMipTrim", true);
      SPRITE_FILTER_INTEGER_AA = bool("spriteFilterIntegerAa", false);
      SPRITE_FILTER_SHARP_MIPS = bool("spriteFilterSharpMips", false);
      SPRITE_FILTER_LINEAR_LIGHT = bool("spriteFilterLinearLight", false);
      OVERLAY_SAMPLING = bool("overlaySampling", false);
      OVERLAY = bool("overlay", false);
      OVERLAY_LOG = bool("overlayLog", false);
      CONSOLE_LOG = string("consoleLog", "all");
      Log.setLevel(CONSOLE_LOG);
      OVERLAY_STATS = string("overlayStats", "tails");
      OVERLAY_TREE = string("overlayTree", "5");
      OVERLAY_VERDICT = string("overlayVerdict", "detailed");
      OVERLAY_GRAPH = string("overlayGraph", "240");
      OVERLAY_FLAME = string("overlayFlame", "off");
      OVERLAY_POWER = bool("overlayPower", true);
      OVERLAY_TEXTURE = bool("overlayTexture", true);
      OVERLAY_REFRESH_MS = integer("overlayRefreshMs", 250);
      OVERLAY_GRAPH_HZ = integer("overlayGraphHz", 0);
      OVERLAY_FLAME_DEPTH = integer("overlayFlameDepth", 24);
      GAME_THREAD_PROFILE_HZ = integer("gameThreadProfileHz", 25);
      OVERLAY_FONT = string("overlayFont", "auto");
      OVERLAY_CORNER = string("overlayCorner", "tl");
      OVERLAY_FPS_COLOR = bool("overlayFpsColor", true);
      OVERLAY_FPS_FOLLOW_CAP = bool("overlayFpsFollowCap", true);
      OVERLAY_FPS_CAP_BLUE_PCT = integer("overlayFpsCapBluePct", 98);
      OVERLAY_FPS_CAP_GREEN_PCT = integer("overlayFpsCapGreenPct", 90);
      OVERLAY_FPS_CAP_YELLOW_PCT = integer("overlayFpsCapYellowPct", 50);
      OVERLAY_FPS_BLUE_ABOVE = integer("overlayFpsBlueAbove", 300);
      OVERLAY_FPS_GREEN_ABOVE = integer("overlayFpsGreenAbove", 150);
      OVERLAY_FPS_YELLOW_ABOVE = integer("overlayFpsYellowAbove", 100);
      OVERLAY_FPS_COLOR_BLUE = string("overlayFpsColorBlue", "blue");
      OVERLAY_FPS_COLOR_GREEN = string("overlayFpsColorGreen", "green");
      OVERLAY_FPS_COLOR_YELLOW = string("overlayFpsColorYellow", "yellow");
      OVERLAY_FPS_COLOR_RED = string("overlayFpsColorRed", "red");
      loadingLive = false;
   }

   public static final int OVERLAY_KEY = integer("overlayKey", 67); // LWJGL 2 code, 67 = F9; used when the Lua binding is absent

   private Config() {
   }

   private static Properties load() {
      Properties p = new Properties();
      File f = new File("pzopt.properties"); // cwd is the install dir when launched by ProjectZomboid64.exe
      if (f.isFile()) {
         try (InputStream in = new FileInputStream(f)) {
            p.load(in);
         } catch (Exception e) {
            Log.warn("could not read " + f.getAbsolutePath() + ": " + e);
         }
      }
      return p;
   }

   /** -Dpzopt.<key>, then the install dir's pzopt.properties, then the player's options.ini. */
   private static String raw(String key) {
      String v = System.getProperty("pzopt." + key);
      if (v == null) {
         v = props.getProperty(key);
      }
      if (v != null) {
         return v;
      }
      String[] gate = GATED.get(key);
      if (gate != null && !masterOn(gate[0])) {
         return gate[1];
      }
      v = userProps.getProperty(key);
      return v != null ? v : compatProps.getProperty(key);
   }

   /** -D, pzopt.properties, then options.ini: a key read before the mod-compat layer exists (modCompat itself). */
   private static String upper(String key) {
      String v = System.getProperty("pzopt." + key);
      if (v == null) {
         v = props.getProperty(key);
      }
      return v != null ? v : userProps.getProperty(key);
   }

   /** A tab master switch (enhancementsEnabled / profilerEnabled), read through the usual order; on unless "false". */
   private static boolean masterOn(String master) {
      String v = raw(master);
      return v == null || !v.trim().equalsIgnoreCase("false");
   }

   private static java.util.Map<String, String[]> gated() {
      java.util.Map<String, String[]> m = new java.util.HashMap<>();
      String[][] groups = {
         // the switch of each feature on the Enhancements tab; the rest of each section only tunes it
         {"enhancementsEnabled", "upscaler", "off", "dynRes", "false", "spriteFilter", "stock", "hdr", "false", "hdrAuto", "false",
            "ambientOcclusion", "false", "sunShadows", "false", "reflections", "false", "bloodWet", "false", "occludedZombieOutlines", "false", "darknessFloorPct", "0",
            "memoryTint", "false", "colorGrading", "false", "pixelLight", "false", "godRays", "false", "foliageSway", "false", "relief", "false", "carGlass", "false",
            "torchSource", "false", "mirrors", "false"},
         // everything that makes the overlay measure or show (Overlay.configure; harness runs still measure)
         {"profilerEnabled", "overlaySampling", "false", "overlay", "false", "overlayLog", "false"},
      };
      for (String[] g : groups) {
         for (int i = 1; i < g.length; i += 2) {
            m.put(g[i], new String[] {g[0], g[i + 1]});
         }
      }
      return m;
   }

   /** The keys a tab master switch turns off, or null when {@code key} is not one. */
   static java.util.List<String> gatedBy(String key) {
      java.util.List<String> keys = null;
      for (java.util.Map.Entry<String, String[]> e : GATED.entrySet()) {
         if (e.getValue()[0].equals(key)) {
            if (keys == null) {
               keys = new java.util.ArrayList<>();
            }
            keys.add(e.getKey());
         }
      }
      return keys;
   }

   private static <T> T register(String key, T effective, T def) {
      REGISTRY.put(key, new String[] {String.valueOf(effective), String.valueOf(def)});
      if (loadingLive) {
         LIVE.add(key);
      }
      return effective;
   }

   private static boolean bool(String key, boolean def) {
      String v = raw(key);
      return register(key, v == null ? def : Boolean.parseBoolean(v.trim()), def);
   }

   private static String string(String key, String def) {
      String v = raw(key);
      return register(key, v == null ? def : v.trim(), def);
   }

   private static int parseInt(String key, String v, int def) {
      try {
         return Integer.parseInt(v.trim());
      } catch (NumberFormatException e) {
         Log.warn("bad integer for " + key + ": " + v + "; using " + def);
         return def;
      }
   }

   /** A per-surface AO strength: its own key, else the old single {@code aoStrengthPct} (options files from before 2026-09-25). */
   private static int aoStrength(String key) {
      String v = raw(key);
      return register(key, v == null ? AO_STRENGTH_PCT : parseInt(key, v, AO_STRENGTH_PCT), 100);
   }

   private static int[] intList(String csv) {
      if (csv == null || csv.isBlank()) {
         return new int[0];
      }
      String[] parts = csv.split(",");
      int[] out = new int[parts.length];
      for (int i = 0; i < parts.length; i++) {
         out[i] = Integer.parseInt(parts[i].trim());
      }
      return out;
   }

   private static int integer(String key, int def) {
      String v = raw(key);
      if (v == null) {
         return register(key, def, def);
      }
      try {
         return register(key, Integer.parseInt(v.trim()), def);
      } catch (NumberFormatException e) {
         Log.warn("bad integer for " + key + ": " + v + "; using " + def);
         return register(key, def, def);
      }
   }
   /** mouseSensitivity: finite scale in 0.10..4.00, rounded to the nearest 0.05 for signed integer accumulation. */
   private static int mouseSensitivityUnits() {
      String value = raw("mouseSensitivity");
      int units = 20;
      if (value != null) {
         try {
            double parsed = Double.parseDouble(value.trim());
            if (!Double.isFinite(parsed)) throw new NumberFormatException("non-finite");
            units = (int)Math.round(Math.max(0.1, Math.min(4.0, parsed)) * 20.0);
         } catch (NumberFormatException e) {
            Log.warn("bad number for mouseSensitivity: " + value + "; using 1.0");
         }
      }
      double effective = units / 20.0;
      register("mouseSensitivity", Double.toString(effective), "1.0");
      return units;
   }

   // --- options tab (Options > Optimizations; see UserOptions) --------------------------------

   /** Is this one of the keys read at init? */
   public static boolean knows(String key) {
      return key != null && REGISTRY.containsKey(key);
   }

   /** Does a change of this key apply while the game runs ({@link #reloadLive}) rather than on the next launch? */
   public static boolean isLive(String key) {
      return key != null && LIVE.contains(key);
   }

   /**
    * The player changed {@code key} (UserOptions.set, game thread): when it is a live key, re-read every live key
    * through the usual -D > pzopt.properties > options.ini order and return true; the caller then lets the classes
    * that derive state from them know. A key pinned by -D or pzopt.properties keeps its pinned value.
    */
   static synchronized boolean reloadLive(String key) {
      if (!isLive(key)) {
         return false;
      }
      loadLive();
      return true;
   }

   /** The value in force now (since boot for every key but the live ones; before the clamps some keys apply), or null for an unknown key. */
   public static String value(String key) {
      String[] r = key == null ? null : REGISTRY.get(key);
      return r == null ? null : r[0];
   }

   /** The build's default on this machine, or null for an unknown key. */
   public static String defaultValue(String key) {
      String[] r = key == null ? null : REGISTRY.get(key);
      return r == null ? null : r[1];
   }

   /**
    * What pins the key above the player's options.ini: "-Dpzopt.<key>" or "pzopt.properties",
    * or null when the menu choice is what counts.
    */
   public static String pinnedBy(String key) {
      if (key == null) {
         return null;
      }
      if (System.getProperty("pzopt." + key) != null) {
         return "-Dpzopt." + key;
      }
      return props.getProperty(key) != null ? "pzopt.properties" : null;
   }

   /** min(4, cores - 1); 1 on 4 cores or fewer, where a pool only competes with the game, lighting and render threads. */
   static int defaultWorkers(int cores) {
      return cores <= 4 ? 1 : Math.min(4, cores - 1);
   }

   /** At least 1, and never the full processor count: the render thread keeps one core. */
   static int clampWorkers(int requested) {
      int max = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
      return Math.max(1, Math.min(requested, max));
   }

   /** Effective pool width: 1 when parallelism is switched off or the build guard tripped. */
   public static int effectiveWorkers() {
      return PARALLEL && Overrides.enabled() ? WORKERS : 1;
   }

   /** Wake-on-enqueue is also off when the build guard tripped, so a mismatched build is fully stock. */
   public static boolean effectiveWake() {
      return WAKE && Overrides.enabled();
   }

   public static String describe() {
      return "parallel=" + PARALLEL + " workers=" + WORKERS + " (effective " + effectiveWorkers() + ", cores "
            + Runtime.getRuntime().availableProcessors() + ") wake=" + WAKE + " (effective " + effectiveWake() + ") chunkGridWidth=" + CHUNK_GRID_SETTING + " instrument=" + INSTRUMENT + " dev=" + DEV + " luaChecksumExempt=" + LUA_CHECKSUM_EXEMPT
            + " translucentCache=" + TRANSLUCENT_CACHE + " hotsaveIntervalSec=" + HOTSAVE_INTERVAL_SEC + " persistentVbo=" + PERSISTENT_VBO + " persistentVboSlots=" + PERSISTENT_VBO_SLOTS + " persistentVboFrameSync=" + PERSISTENT_VBO_FRAME_SYNC + " persistentVboTrustFrames=" + PERSISTENT_VBO_TRUST_FRAMES + " seamSpread=" + SEAM_SPREAD + " fliesToggleFix=" + FLIES_TOGGLE_FIX + " bakeMipLevels=" + BAKE_MIP_LEVELS + " occlusionRetain=" + OCCLUSION_RETAIN + " bakeScheduler=" + BAKE_SCHEDULER + " bakeFrameBudget=" + BAKE_FRAME_BUDGET + " bakeArrivalQuota=" + BAKE_ARRIVAL_QUOTA + " seamDirections=" + SEAM_DIRECTIONS + " chunkHandoffSlack=" + CHUNK_HANDOFF_SLACK + " occlusionGrantedOnly=" + OCCLUSION_GRANTED_ONLY + " occlusionCountParallel=" + OCCLUSION_COUNT_PARALLEL + " kidsRoomMemo=" + KIDS_ROOM_MEMO + " lightingNewChunkBudget=" + LIGHTING_NEW_CHUNK_BUDGET + " bakeBudgetAdaptive=" + BAKE_BUDGET_ADAPTIVE + " bakeFrameBudgetMin=" + BAKE_FRAME_BUDGET_MIN + " bakeBudgetPct=" + BAKE_BUDGET_LOW_PCT + "/" + BAKE_BUDGET_HIGH_PCT + " bakeFrameBudgetHard=" + BAKE_FRAME_BUDGET_HARD + " bakeSmooth=" + BAKE_SMOOTH + "/" + BAKE_SMOOTH_MIN + " bakeDeadlinePct=" + BAKE_DEADLINE_PCT + " bakeMaxWait=" + BAKE_MAX_WAIT_CUTAWAY + "/" + BAKE_MAX_WAIT_ARRIVAL + "/" + BAKE_MAX_WAIT_STRONG + "/" + BAKE_MAX_WAIT_REDRAW + "/" + BAKE_MAX_WAIT_LIGHT + " seamRebakeBudget=" + SEAM_REBAKE_BUDGET + " seamHeavyBakes=" + SEAM_HEAVY_BAKES + " seamMaxFrames=" + SEAM_MAX_FRAMES + " treesInChunkTexture=" + TREES_IN_CHUNK_TEXTURE + " windowsInChunkTexture=" + WINDOWS_IN_CHUNK_TEXTURE + " translucentTilesInChunkTexture=" + TRANSLUCENT_TILES_IN_CHUNK_TEXTURE + " glassTilesPerFrame=" + GLASS_TILES_PER_FRAME + " floorDecalsPerFrame=" + FLOOR_DECALS_PER_FRAME + " treeBakePass=" + TREE_BAKE_PASS + " curtainDepthNudgePct=" + Math.round(CURTAIN_DEPTH_NUDGE * 100.0F) + " bakeBudget=" + BAKE_BUDGET + " lightingBudget=" + LIGHTING_BUDGET + " lightingRebakeMs=" + LIGHTING_REBAKE_MS + " rebakeBudget=" + REBAKE_BUDGET + " rebakeMaxFrames=" + REBAKE_MAX_FRAMES + " lightingRebakeBudget=" + LIGHTING_REBAKE_BUDGET + " lightingRebakeMaxFrames=" + LIGHTING_REBAKE_MAX_FRAMES + " zoomRetain=" + ZOOM_RETAIN + " zoomRebakeBudget=" + ZOOM_REBAKE_BUDGET + " zoomFrameMs=" + Math.round(ZOOM_FRAME_MS) + " zoomPlaceholder=" + ZOOM_PLACEHOLDER + " zoomEaseMs=" + ZOOM_EASE_MS + " zoomEase=" + ZOOM_EASE + " lightingStrongDelta=" + LIGHTING_STRONG_DELTA + " lightingStrongBudget=" + LIGHTING_STRONG_BUDGET + " lightingStrongFrameMs=" + Math.round(LIGHTING_STRONG_FRAME_MS) + " lightingGlobalDeltaPct=" + Math.round(LIGHTING_GLOBAL_DELTA * 100.0F) + " lightingFlush=" + LIGHTING_FLUSH + " lightSwitchCheckFrames=" + LIGHT_SWITCH_CHECK_FRAMES + " cutawayFast=" + CUTAWAY_FAST + " cutawayRadius=" + CUTAWAY_RADIUS + " gridStackInterval=" + GRID_STACK_INTERVAL + " roofHideDebounceFrames=" + ROOF_HIDE_DEBOUNCE_FRAMES + " weatherMaskIdleSkip=" + WEATHER_MASK_IDLE_SKIP + " worldSoundFast=" + WORLD_SOUND_FAST + " keyboardFresh=" + KEYBOARD_FRESH + " inputLatch=" + INPUT_LATCH + " inputLatchWaitUs=" + INPUT_LATCH_WAIT_US + " frameStartGate=" + FRAME_START_GATE + " gpuMaxFrames=" + GPU_MAX_FRAMES + " reflexSleep=" + REFLEX_SLEEP + " reflexBoost=" + REFLEX_BOOST + " reflexQueueUs=" + REFLEX_QUEUE_US + " reflexCapFps=" + REFLEX_CAP_FPS + " vsyncAdaptive=" + VSYNC_ADAPTIVE + " vblankLock=" + VBLANK_LOCK + " vblankLockMarginUs=" + VBLANK_LOCK_MARGIN_US + " aimHoldMs=" + AIM_HOLD_MS + " cursorLatch=" + CURSOR_LATCH + " saveCellAsync=" + SAVE_CELL_ASYNC + " chunkMapFast=" + CHUNK_MAP_FAST + " propertySurfaceNoAlloc=" + PROPERTY_SURFACE_NOALLOC + " weatherNoGlGet=" + WEATHER_NO_GLGET + " glNoSync=" + GL_NO_SYNC + "/" + GL_NAME_POOL + " renderChunkPrewarm=" + RENDER_CHUNK_PREWARM + " renderChunkTopUp=" + RENDER_CHUNK_TOP_UP + " renderChunkTopUpPerFrame=" + RENDER_CHUNK_TOP_UP_PER_FRAME + " lightingVisionParallel=" + LIGHTING_VISION_PARALLEL + " threadNice=" + THREAD_NICE + " profileHandshake=" + PROFILE_HANDSHAKE + " luaProfile=" + LUA_PROFILE + " uiProfile=" + UI_PROFILE + " uiRetained=" + UI_RETAINED + " uiRetainedChildren=" + UI_RETAINED_CHILDREN + " luaIndexCache=" + LUA_INDEX_CACHE + " luaInternConstants=" + LUA_INTERN_CONSTANTS + " luaSkipEmpty=" + LUA_SKIP_EMPTY + " uiLuaFast=" + UI_LUA_FAST + " uiTickStagger=" + UI_TICK_STAGGER + " mapStreetMemo=" + MAP_STREET_MEMO + " mapStreetCache=" + MAP_STREET_CACHE + " uiHoverHz=" + UI_HOVER_HZ + " uiRetainedMaxMs=" + UI_RETAINED_MAX_MS + " uiRetainedCheapUs=" + UI_RETAINED_CHEAP_US + " gcMode=" + GC_MODE + " gcPauseMs=" + GC_PAUSE_MS + " gcHeap=" + GC_HEAP + " gcHeapFixed=" + GC_HEAP_FIXED + " gcPreTouch=" + GC_PRE_TOUCH + " jitMode=" + JIT_MODE + " jitC1Cores=" + JIT_C1_CORES + " vehicleCull=" + VEHICLE_CULL + " playerLosFast=" + PLAYER_LOS_FAST + " zombieSpotFast=" + ZOMBIE_SPOT_FAST + " playerLosNative=" + PLAYER_LOS_NATIVE + " animBonesParallel=" + ANIM_BONES_PARALLEL + " animBatchAsync=" + ANIM_BATCH_ASYNC + " animatorParallel=" + ANIMATOR_PARALLEL + " animatorPipeline=" + ANIMATOR_PIPELINE + " frameSpinUs=" + FRAME_SPIN_US + " frameThreads=" + FRAME_THREADS + " actionEvalParallel=" + ACTION_EVAL_PARALLEL + " actionSnapshotFilter=" + ACTION_SNAPSHOT_FILTER + " emitterParamSkip=" + EMITTER_PARAM_SKIP + " emitterIdleSkip=" + EMITTER_IDLE_SKIP + " worldSoundCleanupFast=" + WORLD_SOUND_CLEANUP_FAST + " hearingHoist=" + HEARING_HOIST + " soundTickHz=" + SOUND_TICK_HZ + " audioLimiter=" + AUDIO_LIMITER + " audioLimiterCeilingDb=" + Math.round(AUDIO_LIMITER_CEILING_DB) + " separateFast=" + SEPARATE_FAST + " separateParallel=" + SEPARATE_PARALLEL + " entityUpdateParallel=" + ENTITY_UPDATE_PARALLEL + " entityUpdateLuaReplay=" + ENTITY_UPDATE_LUA_REPLAY + " animalLosFast=" + ANIMAL_LOS_FAST + " entityUpdatePipeline=" + ENTITY_UPDATE_PIPELINE + " emitterDefer=" + EMITTER_DEFER + " physicsDefer=" + PHYSICS_DEFER + " actionGroupCache=" + ACTION_GROUP_CACHE + " profilerThreadMemo=" + PROFILER_THREAD_MEMO + " sleepCheckMemo=" + SLEEP_CHECK_MEMO + " stateParamMemo=" + STATE_PARAM_MEMO + " zombieSimLodTiles=" + ZOMBIE_SIM_LOD_TILES + " zombieSimLodSteps=" + ZOMBIE_SIM_LOD_STEPS + " zombieCheckSpread=" + ZOMBIE_CHECK_SPREAD + " lightingReadParallel=" + LIGHTING_READ_PARALLEL + " zombieCullSortFast=" + ZOMBIE_CULL_SORT_FAST + " skinTransformsPrecompute=" + SKIN_TRANSFORMS_PRECOMPUTE + " skinPalettePrecompute=" + SKIN_PALETTE_PRECOMPUTE + " shadowPrep=" + SHADOW_PREP + " boneIndexCache=" + BONE_INDEX_CACHE + " ecsLookupFast=" + ECS_LOOKUP_FAST + " actionConditionFast=" + ACTION_CONDITION_FAST + " charDrawPrep=" + CHAR_DRAW_PREP + " zombieAtlasFast=" + ZOMBIE_ATLAS_FAST + " charDrawThreads=" + CHAR_DRAW_THREADS + " vehicleSmooth=" + VEHICLE_SMOOTH + " driveCameraLate=" + DRIVE_CAMERA_LATE + " driveLookSmooth=" + DRIVE_LOOK_SMOOTH + " cameraScreenPixels=" + CAMERA_SCREEN_PIXELS + " frameClockSmooth=" + FRAME_CLOCK_SMOOTH + " vsyncLock=" + VSYNC_LOCK + " physicsStepHz=" + PHYSICS_STEP_HZ + " physicsStepMode=" + PHYSICS_STEP_MODE
            + " fileThreads=" + FILE_THREADS + " fileInflight=" + FILE_INFLIGHT + " textureBufferMb=" + TEXTURE_BUFFER_MB + " parallelDepthMaps=" + PARALLEL_DEPTH_MAPS + " loaderCpuFixes=" + LOADER_CPU_FIXES + " loadWorkers=" + LOAD_WORKERS + " scriptParserFast=" + SCRIPT_PARSER_FAST + " fmodAsync=" + FMOD_ASYNC + " noLoadFade=" + NO_LOAD_FADE + " noIntroWait=" + NO_INTRO_WAIT + " noClickToStart=" + NO_CLICK_TO_START + " noLoadingScreen=" + NO_LOADING_SCREEN + " centerFirstLoad=" + CENTER_FIRST_LOAD + " resumeShot=" + RESUME_SHOT + " resumeShotDetail=" + RESUME_SHOT_DETAIL + " bootPump=" + BOOT_PUMP + " earlyModels=" + EARLY_MODELS + " luaPrecompile=" + LUA_PRECOMPILE + " preloadAnimSets=" + PRELOAD_ANIM_SETS + " tileDefPreload=" + TILE_DEF_PRELOAD + " skipIdChecks=" + SKIP_ID_CHECKS + " voronoiFast=" + VORONOI_FAST + " earlyTilePacks=" + EARLY_TILE_PACKS + " aotCache=" + AOT_CACHE + " animClipCache=" + ANIM_CLIP_CACHE + " packIndex=" + PACK_INDEX + " itemParamSwitch=" + ITEM_PARAM_SWITCH + " bootFileThreads=" + BOOT_FILE_THREADS + " shaderCache=" + SHADER_CACHE + " mipmapArrays=" + MIPMAP_ARRAYS + " puddleCache=" + PUDDLE_CACHE + " puddleCacheFrames=" + PUDDLE_CACHE_FRAMES + " puddleVbo=" + PUDDLE_VBO + " treeAppend=" + TREE_APPEND + " treeRebakeLazy=" + TREE_REBAKE_LAZY + " treeRebakeLingerMs=" + TREE_REBAKE_LINGER_MS + " puddleEarlyZ=" + PUDDLE_EARLY_Z + " rainSplashesFast=" + RAIN_SPLASHES_FAST + " rainTiles=" + RAIN_TILES + " vboBatchKb=" + VBO_BATCH_KB + " vboFastQuads=" + VBO_FAST_QUADS + " fogPass=" + FOG_PASS + " fogScalePct=" + FOG_SCALE_PCT + " fogMaskFrames=" + FOG_MASK_FRAMES
            + " uniformCache=" + UNIFORM_CACHE + " windSpriteSway=" + WIND_SPRITE_SWAY + " compositeShaderRun=" + COMPOSITE_SHADER_RUN + " ambientOcclusion=" + AO + " aoMode=" + AO_MODE + " aoScalePct=" + AO_SCALE_PCT + " aoRadiusPct=" + AO_RADIUS_PCT + " aoStrengthFloorPct=" + AO_STRENGTH_FLOOR_PCT + " aoStrengthWallPct=" + AO_STRENGTH_WALL_PCT + " aoStrengthObjectPct=" + AO_STRENGTH_OBJECT_PCT + " aoStrengthVegetationPct=" + AO_STRENGTH_VEGETATION_PCT + " aoStrengthPlantPct=" + AO_STRENGTH_PLANT_PCT + " aoPlantLeafOcclusion=" + AO_PLANT_LEAF_OCCLUSION + " aoThicknessPct=" + AO_THICKNESS_PCT + " sunShadows=" + SUN_SHADOWS + " sunShadowStrengthPct=" + SUN_SHADOW_STRENGTH_PCT + " skyPath=" + SKY_PATH + " moonShadows=" + MOON_SHADOWS + " moonShadowPct=" + MOON_SHADOW_PCT + " cloudShadows=" + CLOUD_SHADOWS + " cloudOpacityPct=" + CLOUD_OPACITY_PCT + " cloudSpeedPct=" + CLOUD_SPEED_PCT + " cloudScalePct=" + CLOUD_SCALE_PCT + " godRays=" + GOD_RAYS + " godRaysMethod=" + GOD_RAYS_METHOD + " godRaysStrengthPct=" + GOD_RAYS_STRENGTH_PCT + " godRaysHazePct=" + GOD_RAYS_HAZE_PCT + " godRaysDustPct=" + GOD_RAYS_DUST_PCT + " sunShadowSoftnessPct=" + SUN_SHADOW_SOFTNESS_PCT
            + " upscaler=" + UPSCALER + " upscaleNoGlGet=" + UPSCALE_NO_GLGET + " upscalerQuality=" + UPSCALER_QUALITY + " upscalerScalePct=" + UPSCALER_SCALE_PCT + " fsrSharpnessPct=" + FSR_SHARPNESS_PCT + " dlssSharpen=" + DLSS_SHARPEN + " upscalerObjectMv=" + UPSCALER_OBJECT_MV + " dynRes=" + DYN_RES + " dynResController=" + DYN_RES_CONTROLLER + " dynResTargetPct=" + DYN_RES_TARGET_PCT + " dynResMinPct=" + DYN_RES_MIN_PCT + " dynResMaxPct=" + DYN_RES_MAX_PCT + " dynResFps=" + DYN_RES_FPS + " dlssPreset=" + DLSS_PRESET + " dlssAutoExposure=" + DLSS_AUTO_EXPOSURE + " dlssPipeline=" + DLSS_PIPELINE + " dlssOutputPct=" + DLSS_OUTPUT_PCT + " dlssOutputFilter=" + DLSS_OUTPUT_FILTER + " dlssFlushAfterWait=" + DLSS_FLUSH_AFTER_WAIT + " dlssFlushAfterComposite=" + DLSS_FLUSH_AFTER_COMPOSITE + " dlssWaitOutputOnly=" + DLSS_WAIT_OUTPUT_ONLY + " dlssDirectColor=" + DLSS_DIRECT_COLOR + " dlssWaterCurrent=" + DLSS_WATER_CURRENT + " dlssWaterHistoryPct=" + DLSS_WATER_HISTORY_PCT;
   }
}
