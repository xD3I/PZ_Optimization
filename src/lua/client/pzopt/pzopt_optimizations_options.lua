-- pzopt: the "PZ Optimization" tab in the options screen, right after Display (one tab since 2026-10-04; before, three:
--  Optimizations, Enhancements, Profiler).
--  Every pzopt.Config key is a control here: booleans are tick boxes, numeric/string settings are combos whose first
--  entry is the build's default on this machine. The values live in Java: the overridden
--  PerformanceSettings forwards to pzopt.Config (what is in force since boot) and pzopt.UserOptions
--  (Zomboid/pzopt/options.ini, what the next launch will read). The Performance settings (SECTIONS) apply on the
--  next launch, so a change away from the boot value raises the stock "restart required" dialog; the
--  Tools (PROFILER_SECTIONS), most Visuals (ENHANCEMENT_SECTIONS, entry.live) and the live RawMouse sensitivity /
--  acceleration settings apply at once when saved (pzopt.Config.reloadLive, then pzopt.Overlay.reconfigure /
--  pzopt.Enhancements.apply; RawMouse resets its velocity estimate and fractional remainders).
--  A key set in the install dir's pzopt.properties or as -Dpzopt.<key> (harness runs) wins over the
--  file; its control shows that value, is disabled, and the tooltip says what pins it.
--  Each group has a master switch on the home page (keys `enabled`, `enhancementsEnabled`, `profilerEnabled`): off,
--  the group's settings are ignored and kept. `enabled` off = every override takes its stock path, the same as a build
--  mismatch. The presets (Recommended / Stock game / Low-end) and the Reset buttons only change the controls;
--  Apply / Accept saves them like any other option.
--  How the page is organised (home, categories, subcategories, Simple / Advanced / Everything, Fix a problem) comes
--  from pzopt_optimizations_layout.lua; the layout engine is near the end of this file (buildPage, relayout).
--  Right of the settings sits the preview panel (PzoptPreview, fixed while the list scrolls): for the
--  setting under the mouse it plays two clips side by side, the stock game and the optimized build on the
--  same route (animated GIFs under media/ui/pzopt/compare/, made by harness/menu-gifs.py, decoded by
--  pzopt.GifTextures), shows the setting's description and its value now / at the next launch, and draws
--  one bar per resource (game thread, render thread, other cores, GPU, VRAM, RAM, disk, load time, chunk
--  arrival) from the EFFECTS table below: left = less work / sooner, right = more. Which clip a setting
--  shows is its section's `clip`, overridden per key in KEY_CLIP.
-- Installed by scripts/pzopt.sh into <game dir>/media/lua/client/pzopt/ (loose game-dir Lua is
-- loaded like any other, no mod to enable).

local TAB = "PZ Optimization"
local ENHANCEMENTS_TAB = "Enhancements"
local PROFILER_TAB = "Profiler"
local RESTART_NOTE = "Takes effect on the next launch."
local LIVE_NOTE = "Applies as soon as you press Apply; no restart needed."

-- The master switch, drawn before the sections with the two buttons.
local MASTER = { key = "enabled", label = "Optimizations enabled (master switch)",
  tip = "Off = the game runs stock: every override takes its original code path and the settings below are ignored. On = the settings below apply. The Tools are not affected: the performance overlay works either way." }
-- The Enhancements and Profiler tabs' master switches (2026-09-28): off, Java reads every feature of the tab as off
-- (Config's GATED list) while the choices below stay saved for when it is on again. Both apply at once; the features
-- that pick their shaders or window at start-up (NEXT_LAUNCH_ONLY) follow on the next launch.
local ENHANCEMENTS_MASTER = { key = "enhancementsEnabled", label = "Enhancements enabled (master switch)", live = true,
  restartKeys = { "hdr", "hdrAuto", "pixelLight", "reflections", "carGlass", "mirrors" },
  tip = "Off = the picture is the stock game's: upscaling, sprite filtering, HDR output, ambient occlusion, sun shadows, reflections, car glass, the darkness floor, remembered places, colour grading, per-pixel lighting, god rays and foliage sway are all off, whatever the settings below say (they are kept for when you switch it on again). On = the settings below apply. HDR output, per-pixel lighting, reflections and car glass switch on the next launch." }
local PROFILER_MASTER = { key = "profilerEnabled", label = "Profiler enabled (master switch)", live = true,
  tip = "Off = no performance overlay, no measuring and no frame log: the overlay's samplers never start and the toggle key only says the profiler is off, whatever the settings below say (they are kept for when you switch it on again). On = the settings below apply." }

-- Colour names pzopt.Overlay.color knows (a RRGGBB hex typed into options.ini also works).
local FPS_COLOURS = { "blue", "green", "yellow", "red", "white", "cyan", "lime", "orange", "magenta", "purple" }

-- Keys, labels and tooltips. `choices` makes a combo (integer or string); `note[value]` annotates an entry;
-- `bezier` adds the curve sliders and plot under the combo (addBezierOption).
local SECTIONS = {
    {
        title = "Chunk textures: what bakes", clip = "drive",
        entries = {
            { key = "treesInChunkTexture", label = "Trees: bake into chunk textures",
              tip = "Static trees are drawn once into the chunk textures instead of every frame; only fading trees stay per-frame. Off = stock (every tree every frame)." },
            { key = "windSpriteSway", label = "Wind sprite effects: sway the baked plants",
              tip = "With the game's own \"Wind sprite effects\" display option on, grass, bushes and trees stay in the chunk textures and bend in the wind through Foliage sway, instead of the option drawing every plant one by one every frame (a forest at max zoom on a laptop: 133 fps with the stock option, 369 with this, 372 without wind). Nothing changes while the game option is off. Off = the stock option's per-frame drawing." },
            { key = "treeBakeMaxChunksPerSec", label = "Trees: bake only below this chunk rate (chunks/s)",
              choices = { "0", "12", "24", "48" }, note = { ["0"] = "always bake" },
              tip = "While chunks stream in faster than this (walking loads about 9 a second, driving at 60 km/h about 32, at 120 km/h about 72) new chunk textures are baked without their trees and the trees are drawn per frame instead: a texture that lives a second or two while driving costs more to bake its trees into than to draw them. Textures already baked keep their trees until they re-bake anyway." },
            { key = "treeBakeDirect", label = "Trees: bake through the plain sprite path",
              tip = "Baked trees go through the plain sprite path. The batched path drops the largest (jumbo) trees near buildings." },
            { key = "treeBakePass", label = "Trees: crowns across textures, depth by height",
              tip = "Baked trees are drawn by their own pass: into every chunk texture the crown reaches (a jumbo tree is up to 7 tiles wide) and with a depth that rises with the crown like walls do. Off = trees are clipped at their chunk texture's border and cut by upper-floor walls behind them (issue #5)." },
            { key = "treeAppend", label = "Trees: draw new ones into neighbour textures",
              tip = "A newly loaded chunk's trees that reach into an already baked neighbour texture are drawn on top of it instead of re-baking the whole texture; same picture, most of the re-bakes while driving." },
            { key = "treeRebakeLazy", label = "Trees: fewer re-bakes in the cutaway while driving",
              tip = "Since Build 42.21 every tree around your car turns see-through while you drive. A tree now re-bakes its chunk only when it leaves the baked picture; one coming back stays drawn every frame (as the stock game draws all trees) until its chunk re-bakes anyway. Same picture; Off = up to four re-bakes of the chunk and its neighbours per tree passed." },
            { key = "treeRebakeLingerMs", label = "Trees: longest per-frame stay after the cutaway (ms)",
              choices = { "0", "250", "1000", "3000" }, note = { ["0"] = "next frame" },
              tip = "How long a tree back from the driving cutaway may stay drawn every frame before its chunk re-bakes it in. 0 = at once: a tree drawn every frame costs more than the re-bake." },
            { key = "edgeTestFast", label = "Zombies: fast wall / window / door test when they push apart",
              tip = "Build 42.21 rewrote the test whether a wall, window or door lies between two squares round new edge objects, through layers of small function objects the Java compiler cannot inline. Zombies pushing each other apart ask it for every neighbour square every frame; the same test is now written out directly. Same result." },
            { key = "driveTreeCutaway", label = "Trees: see-through round your car while driving (Build 42.21)",
              tip = "Build 42.21 counts driving as aiming for the tree cutaway: every tree whose base lies in the cutaway square round you turns see-through. On = as the game. Off (default) = Build 42.20's rule (only trees south-east of you, and while aiming); the 120 km/h drive is about 15-20 % faster at max zoom." },
            { key = "treeCutawayReach", label = "Trees: only trees reaching the cutaway leave the bake",
              tip = "The game makes a tree see-through when its base lies in the cutaway's square around you (while driving: every such tree), but only an ellipse in that square is cut away. A tree whose picture stays clear of the ellipse looks the same either way, so it stays in the chunk texture (no re-bake, no per-frame draw); its fade keeps running so it fades as before when it gets there. 120 km/h drive about a fifth faster." },
            { key = "treeCutawayReachPx", label = "Trees: cutaway reach margin (pixels)",
              choices = { "0", "128", "256", "512" },
              tip = "How far ahead of the cutaway ellipse a tree already leaves the chunk texture, so its re-bake is done before the ellipse reaches it." },
            { key = "bloodBake", label = "Blood: how bakes draw the floor splats",
              choices = { "gpu", "cpu", "off" }, note = { ["gpu"] = "default", ["off"] = "stock" },
              tip = "Blood on the floor is baked into the chunk textures. Stock walks up to 18,000 splats of the chunk and its eight neighbours on every bake and sets the shader, depth test and blend for each splat it draws: at a thousand splats a chunk that doubled every chunk bake (the hitch when chunks arrive). gpu: each chunk keeps its splats sorted once, a bake draws them in one instanced draw per chunk with the same position, colour, age and light (pixel-identical to stock). cpu: the same list as sprites (any computer)." },
            { key = "bloodAppend", label = "Blood: draw new splats into finished textures",
              tip = "A new blood splat is drawn into the chunk textures it lands on instead of re-baking the chunk's floor (in a fight that was dozens of re-bakes a second), and into the neighbours' textures within a tile of a chunk edge, where stock cut it off until they re-baked. Hidden by the Blood Decals option: nothing at all (stock re-baked for it anyway). Where grass, a body or an item lies under it the floor re-bakes as stock." },
            { key = "bloodSettleSec", label = "Blood: exact re-bake after new splats (s)",
              choices = { "0", "10", "30", "60" }, note = { ["30"] = "default", ["0"] = "never" },
              tip = "Some seconds after the first splat drawn into a finished texture, that floor re-bakes once in stock's exact order (blood under anything flat on the floor that the depth test cannot tell from it)." },
            { key = "bloodFadeFix", label = "Blood: drop splats past a chunk's 1,000",
              tip = "Stock keeps drawing the splats pushed out at 1,000 per chunk: they were meant to fade out, but in chunk textures their fade only runs down one step per bake, so they stay for as long as the chunk is loaded. On: they go at the chunk's next bake. Off = stock." },
            { key = "bloodRebakeCoalesceMs", label = "Blood: gather re-bakes for new splats on grass (ms)",
              choices = { "0", "100", "250", "500" }, note = { ["250"] = "default", ["0"] = "stock" },
              tip = "A new splat on grass, a body or an item still re-bakes its floor (see above); it waits this long first, so the splats one hit throws over the next few frames share a single re-bake." },
            { key = "bloodAppendPlants", label = "Blood: new splats under grass without a re-bake",
              tip = "Where grass or bushes grow under a new splat, it is drawn into the finished texture and those plants are drawn into it once more over it, the way the game's bake draws them after the blood (same light, same wind sway), instead of re-baking the whole floor. Bodies, items and flattened grass still re-bake." },
            { key = "bloodAppendVegetation", label = "Blood: draw new splats over grass too",
              tip = "Also draw new splats into finished textures where grass, a body or an item lies under them (the blood then covers them; stock bakes them over the blood). Off = those floors re-bake." },
            { key = "fliesToggleFix", label = "Corpse flies: no re-bake when nothing moved",
              tip = "The corpse-flies update cleared and set the same square's flies every few frames while driving, and each toggle re-baked the chunk texture next to you at once; the clear is skipped when the square stays the same." },
            { key = "windowsInChunkTexture", label = "Bake windows into chunk textures",
              tip = "Windows and glass doors bake like walls instead of being drawn every frame. Off by default: baked glass hides zombies standing behind it (outside the window you look through), and it measured no frame-time gain." },
            { key = "translucentTilesInChunkTexture", label = "Bake translucent tiles",
              tip = "Fences, railings, wall decorations and overlays bake into the chunk textures instead of being drawn every frame (about 3,000 draws a frame at max zoom)." },
            { key = "glassTilesPerFrame", label = "Glass tiles stay per frame",
              tip = "Translucent tiles made of glass (display cases, glass-door fridges, escalator and mall balustrades, glass partitions) are drawn every frame as in the stock game, so you and zombies stay visible behind them. Baked, the glass hid whoever stood behind it. A few draws a frame where there is glass." },
            { key = "floorDecalsPerFrame", label = "Floor decals stay per frame",
              tip = "Translucent tiles that lie on the ground (manhole covers, drains) are drawn every frame as in the stock game, over the rain puddles. Baked, a manhole cover in a puddle flickered in stripes while you walked, most visibly zoomed out. A few draws a frame." },
            { key = "translucentLightsPerFrame", label = "Light fixtures stay per frame",
              tip = "Translucent light fixtures that have a lit sprite (ceiling lights, lamps) are drawn every frame as in the stock game, so a lit ceiling light never shows through the floor or roof above it (a gas-station canopy's tubes did). A few draws a frame." },
            { key = "curtainDepthNudgePct", label = "Curtain depth nudge (hundredths of a tile)",
              choices = { "0", "3", "5", "10" }, note = { ["0"] = "off" },
              tip = "Closed curtains draw this much nearer the camera than their tile geometry says, so a baked window never shows through them (north windows sit 0.017 tile in front of their curtain in the game's tile geometry)." },
        },
    },
    {
        title = "Chunk textures: bake budgets", clip = "nightdrive",
        entries = {
            { key = "bakeScheduler", label = "One prioritized bake budget per frame",
              tip = "Every chunk-level bake of a frame (new chunks, cutaways, strong light, redraws, lighting drift) shares one budget, granted by urgency and distance, instead of each kind having its own; a level waiting past its kind's longest wait goes first. Driving through Rosewood at 120 km/h: 214 -> ~230 fps, worst frames 37 -> 16 ms. Replaces the per-kind budgets below." },
            { key = "bakeFrameBudget", label = "One budget: bakes per frame",
              choices = { "2", "4", "6", "8", "12" },
              tip = "The most chunk-level bakes a frame (the highest the adaptive budget goes); object changes to a texture next to you and up to four new chunk levels always come on top." },
            { key = "bakeBudgetAdaptive", label = "One budget: follow the frame time",
              tip = "The bake budget halves after a frame that used more than 90 % of the frame-rate cap's time and grows by one after frames under 60 %, between 2 and the budget above." },
            { key = "occlusionGrantedOnly", label = "One budget: occlusion only for the levels baked",
              tip = "The pass that finds which chunk levels are hidden behind buildings looks only at the levels baked this frame, not every one still waiting." },
            { key = "bakeMipLevels", label = "Mipmap levels built after a bake",
              choices = { "0", "2", "3", "4" }, note = { ["0"] = "stock: all 11" },
              tip = "The smaller copies of a chunk texture made after each bake; the widest zoom uses only the first ones. 3 cuts a bake's GPU time by a fifth, same picture." },
            { key = "renderChunkTopUp", label = "Chunk textures made ahead of need",
              choices = { "0", "16", "32", "64" }, note = { ["0"] = "stock: made when first needed" },
              tip = "Keeps this many empty chunk textures ready for the current zoom, made two a frame, so a burst of new chunks never waits for texture creation." },
            { key = "bakeBudget", label = "Chunk textures baked per frame",
              choices = { "0", "2", "4", "8", "16", "32" }, note = { ["0"] = "unlimited, stock" },
              tip = "Chunk-level textures (re)baked in one frame; the rest wait for the next frame." },
            { key = "rebakeBudget", label = "Chunk texture re-bakes per frame",
              choices = { "0", "2", "4", "8", "16" }, note = { ["0"] = "unlimited, stock" },
              tip = "Textures dirtied only by lighting drift, a redraw or a cutaway change keep their previous image for a few frames past this many re-bakes." },
            { key = "rebakeMaxFrames", label = "Chunk texture re-bakes: longest hold (frames)",
              choices = { "1", "2", "3", "4", "6" },
              tip = "A held re-bake lands after at most this many frames." },
            { key = "lightingRebakeBudget", label = "Lighting-only re-bakes per frame",
              choices = { "2", "4", "8", "16", "32" },
              tip = "Textures dirtied only by a lighting change (daylight drift, a lightning flash) re-bake at most this many per frame." },
            { key = "lightingRebakeMaxFrames", label = "Lighting-only re-bakes: longest hold (frames)",
              choices = { "3", "10", "30", "60" },
              tip = "A lighting-only re-bake lands after at most this many frames; a lightning strike spreads over this window instead of one long frame." },
            { key = "lightingRebakeMs", label = "Lighting-only re-bakes: minimum ms between two",
              choices = { "0", "50", "100", "250", "500" }, note = { ["0"] = "stock" },
              tip = "A chunk texture dirtied only by a lighting change is not re-baked more often than this." },
            { key = "zoomRetain", label = "Zoom: keep chunk textures across changes",
              tip = "Chunk-level textures that leave the screen when the camera zooms in are kept (while the chunk would still be on screen at the widest zoom) instead of being freed, so zooming back out reuses them; the ones that return, and new ones a zoom-out reveals, are baked a few per frame nearest the player first, with the kept image shown meanwhile. Stock bakes every level a zoom-out reveals in the frame it appears: a fast wheel spin from 0.25 to 2.5 is a 80-375 ms frame." },
            { key = "zoomRebakeBudget", label = "Zoom: kept and new textures baked per frame",
              choices = { "4", "8", "12", "16", "24" },
              tip = "The most chunk-level textures a zoom change (kept ones coming back, new ones it reveals) may start per frame; the count drops after a long frame and grows back after short ones." },
            { key = "zoomPlaceholder", label = "Zoom: kept texture shown while the new one bakes",
              tip = "A chunk level whose texture at the new zoom scale is still queued draws its complete texture from the other scale meanwhile, so a zoom change never shows a hole; off, the level is blank until its own bake lands." },
            { key = "zoomEaseMs", label = "Zoom: motion time (ms)",
              choices = { "0", "150", "200", "300", "400", "600" }, note = { ["0"] = "stock step" },
              tip = "A mouse-wheel zoom change takes this long, moving along a smooth curve (quick start, gentle stop) whatever the frame rate. Stock moves a fixed amount per frame and stops abruptly: 8 frames, 16 ms at 500 fps, 130 ms at 60." },
            { key = "zoomEase", label = "Zoom: motion timing curve",
              choices = { "0.25,0.1,0.25,1.0", "0.42,0,0.58,1", "0,0,0.58,1", "0.42,0,1,1", "0.333,0.333,0.667,0.667" },
              note = { ["0.25,0.1,0.25,1.0"] = "ease", ["0.42,0,0.58,1"] = "ease-in-out", ["0,0,0.58,1"] = "ease-out", ["0.42,0,1,1"] = "ease-in", ["0.333,0.333,0.667,0.667"] = "linear" },
              bezier = true,
              tip = "The cubic Bezier control points (x1,y1,x2,y2) of the zoom motion, as in CSS transitions: pick a preset or drag the four sliders under it (the plot beside them shows the zoom's progress over the motion time). x is the share of the time, y the share of the zoom change; y stays within 0-1 so the zoom never overshoots its target." },
            { key = "zoomFrameMs", label = "Zoom: slow-frame limit for the bakes (ms)",
              choices = { "6", "8", "10", "14", "20" },
              tip = "A frame longer than this halves the zoom bakes per frame; frames under three quarters of it grow the count back." },
            { key = "lightingStrongDelta", label = "Strong light changes: amount that re-bakes at once (0-255)",
              choices = { "2", "4", "6", "12", "24", "255" }, note = { ["255"] = "hold everything" },
              tip = "A square whose light moved by this much since its texture was last baked (a torch or headlight beam sweeping in) re-bakes now, like stock; smaller drift keeps the lighting-only re-bake hold (\"Lighting-only re-bakes: minimum ms between two\")." },
            { key = "lightingStrongBudget", label = "Strong light changes: budget per frame",
              choices = { "0", "4", "8", "16", "32" }, note = { ["0"] = "no cap" },
              tip = "How many chunk textures with a strong light change re-bake in the same frame; the rest keep the lighting-only re-bake hold. A torch or headlight beam touches a few per frame; turning moves the out-of-sight fade over every exterior tile (downtown Louisville: 10.8 fps with no cap)." },
            { key = "lightingStrongFrameMs", label = "Strong light changes: ease on slow frames",
              choices = { "0", "12", "20", "33" }, note = { ["0"] = "fixed budget" },
              tip = "A game-thread frame longer than this many milliseconds halves the number of strong-light chunk re-bakes allowed next frame (it grows back on fast frames). Stops the slow-frame -> more re-bakes -> slower-frame loop of a big downtown horde." },
            { key = "lightingGlobalDeltaPct", label = "Global light move that keeps the spread (%)",
              choices = { "1", "2", "5", "10", "100" }, note = { ["100"] = "never" },
              tip = "A lightning flash or a fast dusk moves the whole scene's light at once; past this per-frame move the lighting re-bakes stay spread over frames instead of landing at once." },
            { key = "lightingBudget", label = "Lighting refreshes per frame (chunks)",
              choices = { "0", "2", "4", "8", "16", "32" }, note = { ["0"] = "unlimited, stock" },
              tip = "Chunks whose square light info is refreshed in one frame; the rest continue next frame." },
            { key = "lightingFlush", label = "Lighting refreshes: flush the queue before a lighting pass",
              tip = "Chunks the per-frame lighting refresh budget still holds are refreshed just before the next lighting pass rewrites their dirty bits; off, they keep stale light until it changes again." },
        },
    },
    {
        title = "Cutaways, lighting and weather (game thread)", clip = "spin",
        entries = {
            { key = "visBlurReduce", label = "Vision-cone edge blur summed once per texel",
              tip = "The soft edge of the vision cone is a 25-sample blur that stock runs for every pixel of the zoomed-out world image (at the widest zoom ~13 million pixels, 340 million texture reads a frame), although the sum only changes every few pixels. It is now summed once per blur texel and looked up per pixel: the same image, less GPU work." },
            { key = "cutawayFast", label = "Replay cutaway masks",
              tip = "Clean chunk levels replay their stored wall-cutaway occluder masks instead of re-testing every square." },
            { key = "cutawayRadius", label = "Cutaway radius (chunks)",
              choices = { "0", "1", "2", "3", "4", "6" }, note = { ["0"] = "all on screen, stock" },
              tip = "Cutaway wall visits only consider chunks within this many chunks of the camera." },
            { key = "gridStackInterval", label = "Frames between buildings-in-front scans",
              choices = { "0", "2", "4", "8" }, note = { ["0"] = "every frame, stock" },
              tip = "While the camera square and facing are unchanged, the buildings-in-front scan runs this often." },
            { key = "lightSwitchCheckFrames", label = "Frames between light-switch power checks",
              choices = { "0", "5", "15", "30", "60" }, note = { ["0"] = "every frame, stock" },
              tip = "Each light switch reuses its has-electricity answer for this many frames." },
            { key = "rainTiles", label = "Rain and snow as repeated tiles",
              tip = "The particle cell is packed once and drawn once per screen cell on the GPU instead of every copy being packed on both threads; same picture." },
            { key = "rainSplashesFast", label = "Rain splashes without the game RNG",
              tip = "Splash starts are drawn with a cheap local generator (one draw per splash instead of one game RNG call per idle square per frame); same chance, timing and sprites." },
            { key = "puddleCache", label = "Puddles: cache packed vertices per chunk",
              tip = "Rain puddles keep their packed vertices per chunk level and only refresh lighting, camera offset and depth each frame; 4.5 ms of a thunderstorm frame at max zoom." },
            { key = "puddleCacheFrames", label = "Puddles: cache rebuild interval (frames)",
              choices = { "1", "30", "60", "120" }, note = { ["1"] = "rebuild every frame (cache off)" },
              tip = "A cached puddle batch is rebuilt with the stock code after this many frames at the latest; bakes and cutaway changes rebuild it at once." },
            { key = "puddleEarlyZ", label = "Puddles: early depth test in the shader",
              tip = "The puddle shaders take their depth from the vertex instead of writing it per pixel, so wet ground hidden behind walls, roofs and objects is skipped before the expensive shader runs; same picture." },
            { key = "puddleVbo", label = "Puddles: keep batches on the GPU",
              tip = "Each chunk level's cached puddle vertices stay in their own GPU buffer and are re-sent only when a light changed, the camera crossed a chunk edge or the batch was rebuilt; the camera offset is a matrix translation. Nothing is copied per frame." },
            { key = "weatherMaskIdleSkip", label = "Skip the weather mask while nothing is drawn",
              tip = "Outdoors with no clouds, fog or rain the per-frame weather-mask view scan and mask draw are skipped; indoors only the player's building is scanned." },
            { key = "weatherFxScalePct", label = "Weather effects buffer size (% of screen)",
              choices = { "100", "75", "50", "33", "25" }, note = { ["100"] = "stock" },
              tip = "Clouds, fog, rain and the interior mask they are cut by are drawn into screen-sized buffers every frame; smaller buffers cost far less GPU and CPU and the soft content looks the same." },
            { key = "fogPass", label = "Fog drawn in one pass (experimental)",
              tip = "EXPERIMENTAL. Heavy fog is drawn in one batch into a smaller buffer that is depth-tested against the scene and blended over it once, instead of shading every pixel up to twelve times with one draw call per row: fog at 120 km/h went from 220 to ~340 fps (clear: 447) on the 5120x2160 desktop. Known issue: power lines can flicker slightly in fog while the camera moves; disabling this removes it (stock fog, stock cost)." },
            { key = "fogScalePct", label = "Fog pass: buffer size (% of screen)",
              choices = { "100", "75", "50", "33", "25" }, note = { ["100"] = "full resolution" },
              tip = "The fog buffer per axis as a percentage of the screen; 50 costs a quarter of the fog GPU work, 25 a sixteenth. Fog is soft, so the smaller buffers look the same, and edges where fog meets walls or wires are resolved against the real depth." },
            { key = "fogMaskFrames", label = "Fog pass: square masks refresh (frames)",
              choices = { "0", "10", "20", "60" }, note = { ["0"] = "read every square every frame" },
              tip = "The fog rows are built from per-chunk masks of the squares that take fog instead of reading every square each frame; a mask is refreshed this many frames after its last refresh, so a newly built room reaches the fog within that many frames." },
            { key = "roofHideDebounceFrames", label = "Carport roof hide/show settle time (frames)",
              choices = { "0", "4", "8", "15", "30" }, note = { ["0"] = "stock" },
              tip = "A carport or pergola roof is hidden or shown only after the decision has held for this many frames, so a player on its edge (or pushed by zombies) does not make the roof flicker every frame." },
            { key = "cutawayVisitPrefilter", label = "Skip cutaway walls that cannot cut",
              tip = "A cutaway visit only walks the squares of walls that occlude a cutaway room, belong to a collapsing building, or may hide a window being peeked through; the rest are skipped before their squares are looked up." },
            { key = "cutawayInvalidateChanged", label = "Re-bake cutaway chunks only when a cutaway changed",
              tip = "A cutaway visit re-flags every cut-away wall square; stock re-bakes every chunk holding one on every visit. Only chunks where a square's cutaway flag actually changed are re-baked." },
            { key = "occlusionSkipLightingOnly", label = "Keep the occlusion grid when only lighting changed",
              tip = "The occluded-squares grid and the per-level rendered-square counts are rebuilt only when a visible chunk level changed for a reason other than lighting drift." },
            { key = "lightInfoChunkGate", label = "Ask the lighting engine about a chunk level first",
              tip = "Before refreshing the 64 squares of a chunk level about to be re-baked, one chunk-level question to the lighting engine says whether any of them changed." },
            { key = "lightInfoOncePerFrame", label = "Ask the lighting engine once per square per frame",
              tip = "The per-square light-info JNI call is skipped when the same square was already refreshed this frame." },
            { key = "soundZoneCache", label = "Reuse ambient sound zone distances per square",
              tip = "The 80x80 zone scan behind each ambient zone parameter runs when the listener's square changes (at most every 30 frames), not every frame." },
            { key = "worldSoundFast", label = "Cheap world sounds (alarms, helicopter, gunshots)",
              tip = "A world sound is attached to the loaded chunks only instead of walking every chunk of its radius, and the fish-scaring square walk (a third of the radius squared, every frame while a house alarm rings) runs once per game minute per identical sound." },
            { key = "animBonesParallel", label = "Zombie animation bone math on other cores",
              tip = "After the object update loop the zombies' animation blending (bone, twist, model and skin matrices, 9 % of the game thread in a horde) runs on worker threads and is joined before rendering; off, it runs inline like stock." },
            { key = "frameThreads", label = "Worker threads for the zombie batches",
              choices = { "2", "4", "6", "8", "15" }, tip = "Threads of the per-frame zombie batches (bone math, transition evaluation, lighting reads; the game thread joins in). More than cores - 1 is clamped; with Windows dual-CCD placement also to the main CCD's physical cores - 2 (6 on an eight-core CCD, 4 on a six-core one)." },
            { key = "actionEvalParallel", label = "Zombie decision rules evaluated on other cores",
              tip = "Each zombie's action-state transition rules (dozens of variable tests per zombie per frame, 7-10 % of the game thread in a horde) are evaluated on worker threads after the object loop; the state changes, the animator and the model update then run on the game thread in the stock order. Rules that call Lua or a zombie mid-grapple stay on the game thread." },
            { key = "animatorParallel", label = "Zombie animators on other cores",
              tip = "After its decision rules, each zombie's animator (which animations play and how they blend), its turn and move speeds and its animation clock run on worker threads; the sounds, footsteps and state hooks those animations trigger are replayed on the game thread in the stock order. A zombie whose animator fires such an event this frame finishes on the game thread. 12 % of the game thread in a horde." },
            { key = "headOnWorker", label = "Zombie turning checks on other cores",
              tip = "The start of each zombie's animation step (which way it faces, its aim angle, whether it is turning, turning 90 degrees or turning around) runs in its decision-rule task on a worker thread instead of on the game thread; a zombie starting or re-aiming a 180-degree turn, whose event must go through the game thread first, is left to it." },
            { key = "lazyPose", label = "Look up animation keyframes only when used",
              tip = "Every animation track searched the keyframes of all 60 bones every frame; the search now happens when a bone is first read. A zombie drawn as a far sprite reads one or two bones, a modelled one reads them in the bone batch on the workers. Same poses." },
            { key = "animatorPipeline", label = "Overlap the zombie batches with the game thread",
              tip = "The workers run the decision rules and the animators alone while the game thread applies each zombie's result as soon as it is ready, instead of taking a share of the batch and then applying everything." },
            { key = "animBatchAsync", label = "Zombie bone math alongside the rest of the frame",
              tip = "The bone math batch starts after the zombie updates and runs on the workers while the game thread carries on with the rest of the frame's logic; it is joined where the game itself would wait for animation, right before drawing (anything touching a zombie's bones earlier waits for it first)." },
            { key = "guardedCallbacks", label = "Read more zombie rules on other cores",
              tip = "Eight zombie rules (has a target, should sprint, should attack, thump, lunge, attack a car, passenger exposed, eat a body) only change something in rare cases (a target that died, a door that is out of reach, a body next to it). They are now read on the worker threads, and the rare case falls back to the game thread before anything changes." },
            { key = "modelLockPerInstance", label = "One model lock per character",
              tip = "The engine's model update lock is a single text constant shared by every character, so the worker threads queued behind each other on it (each animator task ran 2.4x slower in parallel). Each model now has its own lock; stock only ever updates a model from one thread." },
            { key = "poolStatsBatched", label = "Batch the pool statistics of the workers",
              tip = "Every reuse of a pooled engine object bumps shared statistics counters; the worker threads now count in private tallies and add them once per batch. Same totals, no fight over one memory line." },
            { key = "ecsLookupFast", label = "Cheap state-machine lookups",
              tip = "Every state-machine, action-context and animation-variable access of a character went through a class walk, a map probe and a reflective cast; the answer is memoised and zombies keep their component in a field (5 % of the game thread in a horde). Same results." },
            { key = "actionConditionFast", label = "Typed decision-rule variables",
              tip = "A rule comparing a true/false or integer animation variable reads it directly; stock printed the value to text and parsed it back for every rule of every zombie every frame. Same outcomes." },
            { key = "skinTransformsPrecompute", label = "Bone worker: skin matrices of zombies",
              tip = "The worker that blends a zombie's bones also multiplies them into the skin matrices of the body and clothing models it wore last frame, so the render pass finds them ready (3 % of the game thread in a horde)." },
            { key = "skinPalettePrecompute", label = "Bone worker: skin palettes for the shader",
              tip = "The same worker also stores those matrices in the shader palette layout, so the draw data copies one block instead of sixteen numbers per bone." },
            { key = "shadowPrep", label = "Bone worker: zombie shadow ellipses",
              tip = "The shadow blob under a zombie (head and feet projected to the ground) is computed right after its bones on the worker instead of in the render pass; same numbers." },
            { key = "boneIndexCache", label = "Remember bone lookups by name",
              tip = "The head and feet bone indices the shadow asks for by name every frame are cached per skeleton." },
            { key = "lightingReadParallel", label = "Lighting reads on other cores",
              tip = "When a lighting pass lands, the per-square light reads of the queued chunk levels (two engine calls per visible square, 12 % of the game thread in a horde) run one chunk level per worker thread; the room-discovery hooks run on the game thread afterwards in the stock order." },
            { key = "zombieCullSortFast", label = "Cheap zombie relevance sort",
              tip = "The per-frame sort that decides which zombies get a full update computes each zombie's relevance score once and sorts primitive keys; stock recomputed both scores in every comparison of the sort. Same order." },
            { key = "vehicleCull", label = "Skip far vehicles in the zombie line-of-sight test",
              tip = "A zombie checking whether a car blocks its view of you tests only the cars near you whose bounding circle reaches the line (one list per frame); stock ran the exact box test on every loaded car (6 % of the game thread downtown)." },
            { key = "playerLosFast", label = "Remember spotted zombies in a set",
              tip = "The list of everything you have spotted since the last quiet moment is searched once per spotted object per frame; stock walked it end to end each time, so in a horde the cost grew with the square of the zombies in view (12 % of the game thread in Louisville). The search is one hash probe now, and the sneak modifier zombies ask for is computed once per frame." },
            { key = "zombieSpotFast", label = "Skip the spot roll for zombies that cannot see you",
              tip = "A zombie facing away from you or with you beyond its vision radius already has a zero chance to notice you; it skips the remaining modifiers, the car test and the dice roll (same outcome), instead of computing them all first." },
            { key = "charDrawPrep", label = "Zombie draw data built on other cores",
              tip = "The draw data of every zombie model on screen (its lights, one render record and matrix palette per body and clothing model, the depth and light setup) is built on worker threads before the game thread queues the draws in the stock order; stock built it one zombie at a time on the game thread (13 % of it in a Louisville horde). Same pixels." },
            { key = "charDrawThreads", label = "Zombie draw data threads",
              choices = { "4", "6", "8", "12", "14" }, tip = "Threads of the separate zombie draw-data pool; its work must finish before zombies are queued. The frame and draw pools are separate, so this does not cap the total number of runnable workers. More than cores - 2 is clamped; with Windows dual-CCD placement also to the main CCD's physical cores - 2." },
            { key = "zombieAtlasFast", label = "Flat draw call for far zombies",
              tip = "A zombie too far for a 3D model is drawn as a small pre-rendered sprite; its draw goes through a flat copy of the game's render chain (the same tests, the same sprite call) instead of five nested virtual calls per zombie. ~1,100 such zombies per frame in a Louisville horde. Same pixels." },
            { key = "actionSnapshotFilter", label = "Read only the animation variables that need it",
              tip = "Before a zombie's transitions are evaluated on a worker, the game thread reads the variables whose engine callback has a side effect. It used to resolve every variable of every transition to find out which those are; the answer only depends on the variable's name, so it is now decided once per state. Same values." },
            { key = "emitterParamSkip", label = "Skip sound parameters for silent characters",
              tip = "A character's sound parameters (the floor material under its feet, the room it is in) are recomputed only while it actually has a sound playing or about to start; stock recomputed all of them for every zombie every frame and wrote them nowhere. Same sounds." },
            { key = "separateFast", label = "Push-apart for zombies: cheap pass",
              tip = "The pass that pushes overlapping zombies apart skips the half of the engine's version that only ever applies to the player, and the grid answer 'is my square walled off from that neighbour' is computed once per square per frame instead of once per zombie. Same positions." },
            { key = "separateParallel", label = "Push-apart for zombies: on other cores",
              tip = "That push-apart is computed for the whole horde on worker threads before the update loop; the game thread applies each zombie's result at the same point in its update as before, so the collide events and window climbs keep their order. The neighbours' positions are read at the top of the frame instead of as the loop advances." },
            { key = "sleepCheckMemo", label = "Check 'everyone asleep' once per frame",
              tip = "The game asks whether all players are asleep on the way into every character update, and a zombie asks several times per update; the answer cannot change inside a frame, so it is computed once." },
            { key = "stateParamMemo", label = "Faster AI scratch lookups",
              tip = "The per-character scratch values the AI states keep were looked up through two maps and allocated a throwaway object on every read. Same values, one probe, no allocation." },
            { key = "actionGroupCache", label = "Keep the zombie action group",
              tip = "Every zombie asked the engine for its action group by name twice a frame, which copied the name into a new lower-case string and probed a map for a group loaded once at startup. It is held instead." },
            { key = "profilerThreadMemo", label = "Cheap profiler thread check",
              tip = "Every performance probe in the game asks twice whether it is on a profiled thread, and the engine answers by scanning a list of thread names. The answer per thread is remembered. Costs nothing when the profiler is off, which is always in normal play." },
            { key = "zombieSimLodTiles", label = "Distant zombies simulated less often (tiles)",
              choices = { "0", "8", "12", "15", "20", "25" },
              note = { ["0"] = "stock: the game's own 30 / 60 / 80 tile steps only" },
              tip = "The game already updates a zombie every 2nd, 4th or 8th frame once it is 30, 60 or 80 tiles from you. This adds one more step at a closer distance. It roughly halves what the horde costs the game thread, and distant zombies move in slightly coarser steps - it is a change to how the world is simulated, so it is off by default." },
            { key = "zombieSimLodSteps", label = "Distant zombies simulated less often: extra steps",
              choices = { "1", "2", "3" },
              note = { ["3"] = "not recommended: three steps made the Louisville test scene unstable" },
              tip = "Each further step applies at twice the distance of the previous one, like the game's own ladder. Two steps is the measured sweet spot." },
            { key = "zombieCheckSpread", label = "Spread the zombie thump probe (frames)",
              choices = { "0", "2", "3", "4", "6" },
              note = { ["0"] = "stock: every frame" },
              tip = "The grid test for 'is there a door or window in front of me to thump' runs on one frame in N per zombie, spread evenly by zombie. A thump starts at most N-1 frames later than it would." },
            { key = "zombieLodDynamic", label = "Zombie detail follows the frame cap",
              tip = "The game draws the 510 nearest zombies on screen as 3D models (the rest as flat sprites) and lets the 20 nearest blend their animations. With this on, both numbers follow your frame cap: while frames take longer than the cap allows they come down (the farthest zombies turn into sprites first), and they climb back towards the game's numbers while there is time to spare. Big hordes keep the cap at the cost of detail on far zombies; small crowds look exactly like the stock game. Off by default because it changes what you see." },
            { key = "zombieLodMin3d", label = "Zombie detail: fewest 3D zombies",
              choices = { "64", "128", "192", "256", "384" },
              tip = "The lowest number of zombies still drawn as 3D models when the detail comes down. The game's own number is 510." },
            { key = "zombieLodMinBlend", label = "Zombie detail: fewest blending zombies",
              choices = { "0", "4", "6", "10", "15" },
              tip = "The lowest number of zombies that still blend smoothly between animations when the detail comes down. The game's own number is 20." },
            { key = "zombieLodUncappedFps", label = "Zombie detail: target while uncapped (fps)",
              choices = { "0", "60", "90", "120", "144", "165", "240" },
              note = { ["0"] = "stock detail while uncapped" },
              tip = "With the frame rate uncapped there is no cap to follow; this is the frame rate the detail aims for instead." },
        },
    },
    {
        title = "Entity updates on the worker threads", clip = "horde",
        entries = {
            { key = "entityUpdateParallel", label = "Entity updates on other cores (experimental)",
              tip = "The update loop itself - the only part of the frame that grows with the whole moving-object population - runs the eligible entities on the worker threads instead of one after another on the game thread, with the stock four-step sequence per entity kept exactly. Players, animals, vehicles and physics objects stay on the game thread; Lua events fired from a worker are replayed on the game thread in the stock order; an entity that fails on a worker turns the batching off for the rest of the session. EXPERIMENTAL: it moves the simulation itself, so it is off by default." },
            { key = "entityUpdatePipeline", label = "Entity updates: overlap with the next batch",
              tip = "With entity updates on other cores, the workers finish one batch while the game thread already collects the next, instead of standing still between batches. The frame-timing multiplier each entity reads is captured per batch, so timings stay exactly right. Only active while the setting above is on." },
            { key = "emitterDefer", label = "Entity updates: sound ticks after the batch",
              tip = "With entity updates on other cores, a zombie's per-frame sound work (FMOD parameters, position, tick) queues instead of running on the worker, and the game thread runs the queue right after the batch in the stock order - same frame, before anything renders. The workers stop queuing on the sound locks, so the batch finishes sooner." },
            { key = "entityUpdateSafeStates", label = "Entity updates: calm zombies only",
              tip = "With entity updates on other cores, only zombies that are idle, walking or following a path, with no player within 12 squares, nothing physical going on (no bullet hit being tracked, no ragdoll, not falling, not burning, not grappled) run on the worker threads; everything else updates as in the stock game. Without it the worker threads crashed the game when zombies were shot. On by default." },
            { key = "ragdollCorpseGuard", label = "Ragdolls: none for corpses (game bug fix)",
              tip = "When a shot zombie's ragdoll settles and it turns into a corpse, the game builds a second, invisible ragdoll for the corpse in the same frame from the animation left over, and nothing ever owns it: it stays in the physics world, counts against the maximum number of ragdolls, and quitting while one is there crashes the game on the way out. With this on no ragdoll is made for a character that is already a corpse. On by default." },
            { key = "ragdollQuitSweep", label = "Ragdolls: clear leftovers before quitting (game bug fix)",
              tip = "Just before the physics world is destroyed on the way out of a game, any ragdoll still in it is removed first; the physics library destroys the world before its ragdolls, and a ragdoll left over then crashed the game at quit. A safety net behind the fix above. On by default." },
            { key = "physicsDefer", label = "Entity updates: physics calls after the batch",
              tip = "With entity updates on other cores, the two physics engine calls a zombie's update can make queue instead of running on the worker, and the game thread makes them right after the batch in the stock order - same frame, before anything renders. They are the bullet hitbox of a zombie you have a gun aimed at, and the contact test between a ragdolling zombie and a car. The physics engine is not safe to call from several threads at once, and a crash inside it cannot be caught and turned off the way a Java error can, so leave this on: without it, shooting into a horde with entity updates on other cores ends the game process. Only active while entity updates on other cores is on." },
            { key = "animalLosFast", label = "Animals: skip far-zombie sight checks",
              tip = "An animal's line-of-sight update walks every zombie in range even when it is too far to change anything; those calls are skipped with the same bookkeeping applied afterwards (bit-identical numbers), and players are never skipped. Matters on farms and near hordes." },
        },
    },
    {
        title = "More game-thread work on the worker threads", clip = "horde",
        entries = {
            { key = "renderPrepParallel", label = "Character shadows and reflections: prepared on other cores",
              tip = "Every character on screen asks the sun shadows how much sun reaches it (a walk through the buildings towards the sun) and the reflections whether water is near it. Both answers only read the world, so they are worked out on the worker threads while the game thread draws the ground; the characters then take them ready. Same picture." },
            { key = "pplPackParallel", label = "Per-pixel lighting: light map built on other cores",
              tip = "With per-pixel lighting, the light of every changed piece of the screen is packed on the worker threads, each piece into its own slot, instead of one after another on the game thread. Same light." },
            { key = "pplTorchNearChunk", label = "Per-pixel lighting: torch test per chunk",
              tip = "With per-pixel lighting, whether a torch reaches a square is first decided for the whole 8x8 chunk; only a chunk a torch can reach tests its squares. Same result." },
            { key = "schedulerClassifyParallel", label = "Update schedule computed on other cores",
              tip = "Every frame the game decides how often each zombie, animal and car is updated (distance, visibility, what it is doing); the worker threads compute it and the game thread files the results in the same order. Same schedule." },
            { key = "zombieStatsFold", label = "Zombie travel statistics once per frame",
              tip = "Every zombie added its walked distance to the statistics and re-checked the achievements, 2,000 times a frame in a horde. The same additions are summed in the same order and written once per frame. Same totals." },
            { key = "visPolyAsync", label = "Vision cone shape: computed on its own thread",
              tip = "While you walk, drive or turn, the shape of your vision cone (the walls and trees that cast its shadows) is recomputed every frame. It only depends on where you stand and look, so it is worked out on a thread of its own while the game thread draws the ground, and is ready when the cone is drawn. Same cone." },
            { key = "aoContextParallel", label = "Shadows and ambient occlusion: bake data on other cores",
              tip = "With ambient occlusion or sun shadows on, every chunk the game redraws also gathers which squares hold grass, trees, walls and roofs around it. Those reads are done for all the chunks of a frame at once on the worker threads, instead of one by one inside each redraw. Same shading." },
            { key = "translucentOrderCache", label = "See-through objects: drawing order kept",
              tip = "Objects drawn every frame (windows, doors, wind-blown plants, items) are put in drawing order level by level, every frame. That order only changes when the level changes, so it is kept and reused until then. Same order, checked against the recomputed one over a whole drive." },
            { key = "losLightPrefetch", label = "Player sight: light read ahead on other cores",
              tip = "The squares the player's sight check reads have their light refreshed on the worker threads first. Measured slower than reading them one by one (off)." },
        },
    },
    {
        title = "Sprite buffers", clip = "drive",
        entries = {
            { key = "persistentVbo", label = "Persistently mapped sprite buffers",
              tip = "Sprite ring buffers use persistently mapped buffer storage instead of an orphan and re-map per batch. About 2.7x the uncapped frame rate at max zoom." },
            { key = "persistentVboFrameSync", label = "Sprite buffers: one fence per frame",
              tip = "The persistent sprite buffers are fenced once per frame instead of per 64 KB batch, and a buffer last used 8 frames ago is reused without asking the driver (each check is a round trip to NVIDIA's driver thread)." },
            { key = "persistentVboSlots", label = "Sprite buffers: storage slots",
              choices = { "1", "2", "4" }, note = { ["1"] = "stock size" },
              tip = "Each persistent sprite buffer holds this many batches before it wraps, so a wrap rarely meets one the GPU still reads." },
            { key = "vboBatchKb", label = "Line/particle batch buffer (KB)",
              choices = { "4", "256", "1024" }, note = { ["4"] = "stock: rain flushes every 28 particles" },
              tip = "Rain and snow particles, debug lines and other VBORenderer quads are uploaded and drawn in batches of this size instead of 4 KB." },
            { key = "vboFastQuads", label = "Single-advance particle quads",
              tip = "VBORenderer writes a textured quad's four vertices in one go instead of four bookkeeping round trips; same bytes." },
        },
    },
    {
        title = "Input latency (keyboard, mouse, controller)", clip = "spin",
        entries = {
            { key = "keyboardFresh", label = "Keyboard read in the frame it was polled",
              tip = "The game read the keyboard one frame behind the mouse and the controller: it filled its key table from the previous poll and swapped the new one in afterwards. With this on it reads the new one. One frame less for every key (4 ms at 240 fps, 17 ms at 60). Applies on the next launch." },
            { key = "inputThread", label = "Windows: independent input thread and subframe events",
              tip = "Keeps the GLFW window/event pump on its owner thread and moves OpenGL rendering to a separate thread. Mouse actions are drained before simulation with their original order and coordinates, including press/release pairs between frames. High-rate mouse collection no longer runs in the render loop. Windows only; applies on the next launch." },
            { key = "mouseSensitivity", label = "Raw mouse sensitivity (×)",
              number = { min = 0.1, max = 4, twentieths = true }, live = true,
              tip = "Scales relative Windows Raw Input movement counts only; 1.0 preserves the original counts and OS acceleration is never applied. Enter 0.10–4.00 (rounded to 0.05 steps). This moves a cursor in pixels, not a camera through degrees, so cm/360 does not apply. Applies when you press Apply while Raw Input is active; curve changes reset its estimator and fractional remainders." },
            { key = "mouseAcceleration", label = "Raw mouse speed acceleration",
              live = true,
              tip = "Optional bounded acceleration of relative Windows Raw Input only. Off preserves the existing sensitivity-scaled counts exactly; it does not use Windows pointer acceleration. On estimates speed from packets collected over 8 ms, then applies the gain to the next batch; this is collect-time speed, not the device's hardware report rate. A gap over 50 ms resets the estimate and gain to 1×. Applies when you press Apply while Raw Input is active." },
            { key = "mouseAccelerationOnsetCps", label = "Acceleration onset (counts/s)",
              number = { min = 0, max = 8000 }, live = true,
              tip = "Enter an integer from 0 to 8,000. The collect-time relative-count speed where extra gain begins; below it gain is exactly 1×. The fixed 8 ms estimate is not a hardware-timestamped rate. Applies live when acceleration is on." },
            { key = "mouseAccelerationSlopePctPerKcps", label = "Acceleration slope (% gain per 1,000 counts/s)",
              number = { min = 0, max = 200 }, live = true,
              tip = "Enter an integer from 0 to 200. Extra gain percentage for each 1,000 counts/s above onset, before the cap. With the default 1,000 onset, rate 2,000 counts/s gives 1.5× at the default slope. Applies live when acceleration is on." },
            { key = "mouseAccelerationCapPct", label = "Acceleration cap (% of linear)",
              number = { min = 100, max = 300 }, live = true,
              tip = "Enter an integer from 100 to 300. Maximum total movement as a percentage of the sensitivity-scaled linear result; 200 caps the curve at 2×. Applies live when acceleration is on." },
            { key = "inputLatch", label = "Read the newest input when a frame starts",
              tip = "Without the independent input thread, asks the render thread for a fresh poll when the game frame starts. The independent input thread instead drains its already-collected events at this boundary without waiting for a render-thread poll. Applies on the next launch." },
            { key = "reflexSleep", label = "Low-latency mode (Reflex-style pacing)",
              tip = "What NVIDIA Reflex does, built with OpenGL (Reflex itself has no OpenGL version): each frame measures how long it waited in the queue to the screen (the hand-off to the render thread, a vsync swap that blocks, the driver's queue), and the next frame starts that much later so it arrives just in time, carrying newer input. With vsync on and an uncapped game, together with the cap below: 25-28 ms from input to screen became 5-10 ms. Does nothing when nothing queues (a frame cap below the refresh, vsync off). Applies on the next launch." },
            { key = "reflexBoost", label = "Low-latency boost: keep the GPU clocks up (NVIDIA)",
              tip = "At a frame cap the GPU is idle most of each frame, so the driver lowers its clocks and the frame it does draw takes longer (4 ms at 60 fps against 1.3 ms at 240 on an RTX 4090), which the input waits for. This holds the driver's \"prefer maximum performance\" setting while a world is loaded, what Reflex's On + Boost does. The driver drops it again when the game exits, even after a crash. Uses more power; NVIDIA only. Applies on the next launch." },
            { key = "reflexCapFps", label = "Low-latency mode: cap just below the refresh",
              choices = { "0", "-1" }, note = { ["0"] = "off", ["-1"] = "auto" },
              tip = "With vsync on, a game that runs a hair faster than the screen refills the queue the low-latency mode drained. Auto caps the frame rate at refresh - refresh x refresh / 3600 (157 at 165 Hz, 224 at 240 Hz), what Reflex does with vsync. On a fixed-refresh screen one frame in ~20 is shown twice. Applies on the next launch." },
            { key = "vblankLock", label = "Start each frame a measured time before the vblank",
              tip = "With vsync on, the render thread waits until the display's next refresh is a measured window away (GLX_NV_delay_before_swap) and only then lets the game read input and build the frame. Needs an X11 session with NVIDIA's GLX; under XWayland (Wayland desktops) the extension is missing and this stays off. Applies on the next launch." },
            { key = "gpuMaxFrames", label = "Frames allowed to queue for the GPU",
              choices = { "0", "1", "2" }, note = { ["0"] = "driver default" },
              tip = "After each frame the render thread waits until at most this many frames are waiting for the GPU (the driver's \"low latency mode\"). Helps a GPU-bound game; with vsync the low-latency mode above does more and should be used instead. Applies on the next launch." },
            { key = "frameStartGate", label = "Wait for room before reading input",
              tip = "When the render thread is still busy with the previous frame, the game waits for it before it reads input rather than after building the new frame on older input. Only matters when the render thread or the GPU is the limit. Applies on the next launch." },
            { key = "vsyncAdaptive", label = "Adaptive vsync (a late frame tears instead of waiting)",
              tip = "With vsync on, a frame that misses the refresh is shown at once with a tear line instead of waiting a whole refresh (swap interval -1, needs the driver's swap_control_tear). Applies on the next launch." },
            { key = "cursorLatch", label = "Draw the in-game cursor at the newest mouse position",
              tip = "With \"Lock cursor to window\" the game draws its own cursor where the mouse was when the frame started, so it trails the hand by the whole frame pipeline. With this on it is moved to the newest pointer position just before the frame is drawn. The OS cursor (lock off) has no such lag. Applies on the next launch." },
            { key = "aimHoldMs", label = "Right-mouse hold before aiming",
              choices = { "150", "100", "60", "0" }, note = { ["150"] = "stock" },
              tip = "The game starts aiming only after the right button has been held this long, so a quick right-click still opens the context menu. Shorter aims sooner; at 0 every right-click aims for a moment. Applies on the next launch." },
        },
    },
    {
        title = "Menus, inventory and map (UI)", clip = "spin",
        entries = {
            { key = "uiRetained", label = "UI: redraw only what changed, the moment you act",
              tip = "The game redrew every open window's Lua 60 times a second into the UI picture, although almost all of them drew exactly the same as the frame before, and showed a click or key only at the next of those redraws. With this on each window's drawing is kept and reused while nothing can have changed it (no click, key, wheel or mouse over it, no change to its data, the inventory not marked for refresh); anything you do is drawn in the same frame. Measured at 240 fps: the UI's share of the frame 4 -> 0.9 % with the HUD, 9.8 -> 1.4 % with the inventory open, 16.6 -> 2.2 % with the crafting window, 40 -> 4 % with the map." },
            { key = "uiRetainedChildren", label = "UI: reuse unchanged buttons and panels inside windows",
              tip = "The same for the buttons, lists and panels inside a window that is redrawn (the map's symbol buttons, the inventory's container buttons): only the part under the mouse or with new data is drawn again. Map 6.4 -> 4.1 % of the frame." },
            { key = "uiTickStagger", label = "UI: window updates spread over frames",
              tip = "Every window's Lua update ran in the same frame ten times a second (~0.5 ms with the inventory, ~1.3 ms with the crafting window open). Each window now keeps its own tenth of a second, spread over the frames: the same updates, no periodic spike." },
            { key = "uiLuaFast", label = "UI: inventory window shortcuts",
              tip = "A hidden inventory window no longer rebuilds its container buttons row ten times a second (it does when shown), and the item list skips a second pass over every item when nothing is being dragged. Same result on screen; not used when a mod replaced those functions." },
            { key = "hotsaveWarmup", label = "Transfers: first save warmed up while loading",
              tip = "Dropping or looting items makes the game save the map's building, room and zone records on the next save pass, on the game thread. The first time in a session that code ran cold: a 17 ms stutter a moment after the first transfer. It is now run three times into a scratch buffer on the loading screen (36 ms of load), so that save takes ~6 ms." },
            { key = "mapStreetMemo", label = "Map: street names laid out once per view",
              tip = "The world map measured every street, translated every street name for every letter and sorted the visible streets with a list search in every frame. Lengths and names are kept per view and language. Map UI 6.4 -> 3.8 ms a redraw." },
            { key = "mapStreetCache", label = "Map: street labels reused while the view stays",
              tip = "While the map is not moved or zoomed the whole street-label layout is reused. Map UI 3.8 -> 1.4 ms a redraw." },
            { key = "mapVisitedFast", label = "Map: explored-area texture built a row at a time",
              tip = "The map's dark / explored overlay was rebuilt pixel by pixel, re-computing the row width for every pixel; the first map open after loading rebuilds all 16 million. Built a row at a time, the same pixels: the first map open's stutter 97 -> 67 ms." },
            { key = "luaIndexCache", label = "Lua: class lookups remembered",
              tip = "A Lua method looked up through a class chain is remembered until any class changes. Exact; no measurable change on the UI scenes." },
            { key = "luaInternConstants", label = "Lua: shared text constants",
              tip = "Text constants of every Lua file are shared, so table lookups compare them by identity first. Exact; no measurable change on the UI scenes." },
            { key = "luaSkipEmpty", label = "Lua: empty UI handlers not called",
              tip = "A window's draw, update or mouse handler that is an empty Lua function is not called. Exact; no measurable change on the UI scenes." },
        },
    },
    {
        title = "Mod compatibility", clip = "spin",
        entries = {
            { key = "modProfile", label = "Mods: max performance or max compatibility",
              choices = { "performance", "compatibility" }, note = { ["performance"] = "max performance" },
              tip = "Sets the two settings below at once (also from the main menu's PZ OPTIMIZATION MOD COMPATIBILITY CHECK). Performance (default): Java mods are checked and listed but switch nothing off, and windows mods draw in are reused like the game's own. Compatibility: the settings a Java mod patches are switched off so the mod meets the code its author tested, and windows mods draw in are redrawn at the game's own rate. A value you pick for either setting below still wins. Applies on the next launch." },
            { key = "modCompat", label = "Java mods: switch off what they patch",
              choices = { "auto", "report", "off" }, note = { ["auto"] = "max compatibility", ["report"] = "max performance" },
              tip = "PZ Optimization ships whole game classes; a Java mod (a ZombieBuddy mod, or a -javaagent such as PZMulticore) patches methods of the same classes. At launch the mods' jars are read: where one patches a method we changed, the settings that live in that method are switched off, so the mod meets the code its author tested. Mods tested together with ours keep everything. Your own choice on this tab still wins. Report = only list them (Zomboid/pzopt/mod-compat.txt, the console and the main menu's mod compatibility check); off = no check. The default follows Mods: max performance (report) or max compatibility (auto). Applies on the next launch." },
            { key = "uiRetainedMods", label = "UI: reuse windows that mods draw in",
              tip = "Windows and buttons that carry a mod's drawing (a mod's own window, or a game window whose drawing a mod replaced or extended). On = reused like the game's own windows (faster, but a mod's display can lag by up to a second, because a mod may draw from state the reuse cannot see); off = redrawn at the game's own rate. The default follows Mods: on for max performance, off for max compatibility. Applies on the next launch." },
            { key = "luaWorkerGate", label = "Lua from helper threads runs on the game thread",
              tip = "With zombie updates spread over helper threads, a mod's Lua the update reaches (a zombie trampling a crop runs the farming mod code, a mod hooked into the zombie update) ran on the helper thread, beside the game's own Lua: an error \"Lua code called from the wrong thread\" and, at worst, a broken Lua stack. With this on such calls run on the game thread, in the game's order. Applies on the next launch." },
        },
    },
    {
        title = "Driving smoothness (the car's stutter and rubber banding)", clip = "drive",
        entries = {
            { key = "vehicleSmooth", label = "Vehicles drawn between physics steps",
              choices = { "interp", "extrap", "off" },
              note = { interp = "drawn between the last two physics steps (10 ms behind the simulation)", extrap = "the last step carried forward (no delay; overshoots for one step when a car stops dead)", off = "stock: the last whole 100 Hz step" },
              tip = "Car physics runs in fixed 10 ms steps. Stock draws the car, its passengers and the camera that follows it at the last whole step, so at 120 fps one frame in six shows no motion at all and at 240 fps the car moves on 5 frames of 12: the whole world judders under the car. Drawn at the matching point between two steps, the motion is even at any frame rate (world judder at 120 km/h, zoom 1: 6.5 -> 0.9 px). The simulation itself is untouched." },
            { key = "driveCameraLate", label = "Driving camera placed after the car moved",
              tip = "Stock places the camera inside the player's update; when the game updates the player before the car (it depends on the save), the camera sits one physics step behind on most frames and catches up on the others: the car jumps back and forth by ~20 pixels, 20 times a second (the rubber band). On: the camera is centred on the driver after every update of the frame. Included in the setting above whenever that is on." },
            { key = "driveLookSmooth", label = "Driving look-ahead in fractions of a pixel",
              tip = "The camera looks ahead of a moving car. Stock moves that look-ahead in whole pixels, paced by a millisecond clock, so the car creeps across the screen in uneven steps. On: fractional, paced by the nanosecond clock (the car moving backwards on screen: 4.2 -> 1.0 % of frames at zoom 1)." },
            { key = "cameraScreenPixels", label = "Zoomed in: camera in screen pixels",
              tip = "Zoomed in closer than 100 %, stock moves the world in whole pixels of the zoomed image: 2 screen pixels at 50 %. On: in whole screen pixels, so the scroll is twice as fine at 50 % and every tile still lands exactly on a pixel. No change at 100 % and out." },
            { key = "frameClockSmooth", label = "Game time on the display's refresh grid",
              tip = "Each frame simulates the time the previous one took; with vsync or a frame cap the frames reach the screen on a fixed grid, so a frame that started half a millisecond late moves the world by that much more than the screen shows. On: frames close to the grid step exactly one (or two...) refresh periods and the difference to the real clock is fed back slowly, so game time never drifts." },
            { key = "physicsStepHz", label = "Vehicle physics steps per second",
              choices = { "100", "120", "144", "200", "240" }, note = { ["100"] = "stock" },
              tip = "The fixed rate Bullet runs the vehicles at. Matching the display (120 at 120 Hz) makes one step per frame, but only at exactly that frame rate; the drawing between steps above does the same at any rate and keeps the physics stock. For comparison." },
            { key = "physicsStepMode", label = "Vehicle physics: steps per frame",
              choices = { "fixed", "frame" },
              note = { fixed = "stock: whole fixed steps, the rest carried to the next frame", frame = "each frame's time in equal steps (the step size follows the frame rate)" },
              tip = "Frame steps make every frame advance the cars by exactly its own time, at the price of physics that change a little with the frame rate. For comparison." },
        },
    },
    {
        title = "Variable refresh rate (G-SYNC, FreeSync, ProMotion)", clip = "spin",
        entries = {
            { key = "vrr", label = "Variable refresh rate",
              choices = { "auto", "on", "off" },
              note = { auto = "Linux: follows the display (the kernel's VRR_ENABLED); elsewhere off", on = "always act as if the display runs variable refresh", off = "stock frame pacing" },
              tip = "While the display runs variable refresh (G-SYNC, FreeSync, Adaptive-Sync), every frame is shown the moment it is ready, so how evenly frames arrive is exactly how smooth the game looks. With this on, the frame cap stays inside the display's range and each frame is held until a steady time after the game moment it shows. Linux reads the display state itself; on Windows and macOS choose On if your display runs variable refresh." },
            { key = "vrrCap", label = "Variable refresh: keep the frame rate inside the display's range",
              tip = "While variable refresh is on and your cap is Uncapped or above the display's maximum, the game caps itself a little below it (refresh - refresh^2/3600: 157 fps at 165 Hz, 224 at 240 Hz). Above the maximum a VRR display falls back to vsync (queued frames, more delay) or tears. Off = your own cap applies." },
            { key = "presentPacing", label = "Even frame delivery (present pacing)",
              choices = { "auto", "gpu", "cpu", "gpufinish", "off" },
              note = { auto = "gpu wherever the graphics driver has timestamps (default); off with the macOS Metal present", gpu = "hold each frame to a steady time after its game moment, measured at GPU completion", cpu = "the same, measured when the CPU finished the frame (less even: the GPU may still be drawing)", gpufinish = "wait for the GPU, then hold (evenest, but ~9 ms more delay)", off = "stock: each frame goes out the moment it is drawn" },
              tip = "The game samples its time at an even rate, but each frame takes a different time to update and draw, so on a variable refresh display the gaps between frames on screen wander around the game-time steps: judder. Measured at a 100 fps cap on a 165 Hz G-SYNC display, frames landed on average 3.1 ms off their game-time step (54 % of them more than 2 ms off); held to a steady delay after their game moment, 0.95 ms (13 %), for about 5 ms more input delay. On a fixed-refresh display it evens out when frames leave the game: driving through Rosewood at 240 Hz, frames off their slot 28 -> 11 %, frame-to-frame jitter 1.3 -> 0.4 ms, for ~1.7 ms more delay on average (~2.6 ms typical, less than one 240 Hz frame)." },
            { key = "borderlessFullscreen", label = "Borderless window counts as fullscreen (compositor VRR)",
              choices = { "auto", "true", "false" },
              note = { auto = "Linux", ["true"] = "every platform", ["false"] = "stock window" },
              tip = "KWin, Mutter and gamescope only switch variable refresh on for fullscreen windows; the stock borderless window is just a screen-sized window, so a G-SYNC / FreeSync display stayed at its fixed refresh (0 % of the time in VRR vs 100 % with this on, desktop KDE test). The window is held as a fullscreen window at the desktop's own resolution (no mode switch) and stays on screen when you switch away." },
            { key = "macGlCore", label = "macOS: OpenGL 4.1 (needed by the Enhancements)",
              tip = "On a Mac the game asks for Apple's old OpenGL 2.1, the only version Apple offers that still has the 1990s drawing calls the game uses here and there. Everything newer (OpenGL 4.1, GLSL 4.10) only comes without them, so every Enhancement (shadows, ambient occlusion, reflections, god rays, per-pixel light, HDR effects...) stayed off on macOS. With this on the game runs on OpenGL 4.1 and pzopt stands in for the old calls: its shaders are translated, the old alpha test, matrices and immediate-mode quads are emulated. Same picture as before; the Visuals then work on a Mac (screen-space reflections use the ray march there: OpenGL 4.1 has no image atomics). Applies on the next launch; if the Mac cannot give a 4.1 context the game starts on 2.1 as before." },
            { key = "macGlTimerQueries", label = "macOS (OpenGL 4.1): GPU timer queries",
              tip = "The performance overlay's GPU load and present pacing's GPU timing ask the graphics driver to time each frame. On a Mac's OpenGL 4.1 (Apple's OpenGL runs on top of Metal) that cost a third of the frame rate (106 -> 68 fps on the test route, MacBook Pro M1 Pro), so it is off: the overlay shows no GPU load and present pacing measures on the CPU, as on OpenGL 2.1. On for a measurement that needs the GPU time. Applies on the next launch." },
            { key = "macPresent", label = "macOS: present through Metal (ProMotion timing)",
              choices = { "off", "on" },
              note = { on = "Apple silicon; needs fullscreen or borderless for the finer steps" },
              tip = "OpenGL frames on a Mac are shown on the 120 Hz grid, so a cap like 90 fps alternates 8 ms and 17 ms frames. Presented through Metal, a ProMotion or Adaptive-Sync display shows frames at any multiple of 4.17 ms in fullscreen: the frame cap is snapped to what the panel shows exactly (120, 80, 60, 48, 40 fps) and every frame is held on screen for exactly that long." },
            { key = "limiterSleep", label = "Frame limiter sleeps instead of spinning",
              tip = "While the game waits for its next frame under a frame cap, the stock limiter keeps one core busy spinning. This sleeps until 0.2 ms before the frame (1.5 ms on Windows, where a sleep wakes on the 1 ms timer tick) and spins only that: same frame timing, about half a core less CPU at a 120 fps cap. On by default except on Windows." },
        },
    },
    {
        title = "CPU cores and power (hybrid and dual-CCD CPUs)", clip = "spin",
        entries = {
            { key = "corePlacement", label = "Which CPU cores run the game's threads (hybrid / dual-CCD)",
              choices = { "auto", "efficient", "performance", "dual-ccd", "off" },
              note = { auto = "hybrid CPUs: move game/render to fast cores only when needed", efficient = "all threads on efficient cores", performance = "game/render on fast cores, background on efficient cores", ["dual-ccd"] = "Windows dual-CCD: game, render and workers on the main CCD, background on the other", off = "stock: the operating system decides" },
              tip = "On Linux hybrid CPUs (Zen 5 + Zen 5c or Intel P + E), Auto keeps background work on efficient cores and moves game/render to fast cores when they would miss the frame cap; macOS uses QoS classes. Windows Auto, Efficient and Performance leave placement to the OS. Windows Dual-CCD works on any dual-CCD Ryzen (6+6 or 8+8 cores, SMT on or off, with or without V-Cache): the main CCD is the one with the larger L3 (V-Cache), or CCD0 when both are equal. The game, render, lighting, input and frame/draw worker threads share the whole main CCD; known background work runs on the other one. Frame and draw worker threads are capped at the main CCD's physical cores - 2. Unknown/native and stop-the-world GC threads stay on both CCDs. Unsupported topology disables placement. Applies on the next launch." },
            { key = "gpuPstate", label = "AMD GPU clock level while playing (Linux)",
              choices = { "auto", "off", "standard", "min_sclk" },
              note = { auto = "the lowest fixed clock whose frame time fits the cap, automatic clocks otherwise", off = "stock: the driver's automatic clocks", standard = "a fixed ~1 GHz", min_sclk = "the lowest shader clock (~640 MHz)" },
              tip = "At a frame cap the Radeon driver runs the GPU at its top clock (2.7-2.9 GHz on a Radeon 890M, the highest voltage) for a few milliseconds and then idles it. Most of the game's GPU work waits on memory, not on the clock: at a fixed 640 MHz the 890M still held 120 fps on the bench walk. Auto measures the GPU time of every frame and holds the lowest clock level that keeps the cap, stepping up the moment it does not. The level applies to the whole GPU while the game runs and is released when it exits (a crash included). Applies on the next launch." },
            { key = "jitSteady", label = "Java compiler: no speculative recompiles",
              tip = "Java's optimizing compiler leaves out the branches a method never took while it was being profiled; when the game later takes one (a new street, a different zombie state) the compiled code is thrown away and compiled again. A 100 s walk through the world did that ~2,000 times and kept a compiler thread busy the whole walk (16,600 compilations). With this on the compiler keeps every branch: the compiler's CPU time halves, the game runs as fast. Written into the game's launcher file for the next launch; the uninstallers take it out again." },
            { key = "lightingSyncPark", label = "Lighting thread sleeps between updates",
              tip = "The lighting thread updates 15 times a second (the Lighting FPS setting) and waited for its next update with LWJGL's timer, which sleeps in 1 ms steps and then spins the last millisecond. This sleeps straight to the next update. Applies on the next launch." },
        },
    },
    {
        title = "Java memory and garbage collector", clip = "horde",
        entries = {
            { key = "gcHeap", label = "Java memory for the game (heap size)",
              choices = { "auto", "game", "4096", "6144", "8192", "12288", "16384", "24576", "32768", "40960", "49152", "57344", "65536" },
              note = { auto = "4 GB, 8 GB with 30 mods or more (default)", game = "the game's own: 3 GB", ["4096"] = "4 GB", ["6144"] = "6 GB", ["8192"] = "8 GB", ["12288"] = "12 GB", ["16384"] = "16 GB", ["24576"] = "24 GB", ["32768"] = "32 GB: Java's references double in size from here", ["40960"] = "40 GB", ["49152"] = "48 GB", ["57344"] = "56 GB", ["65536"] = "64 GB" },
              tip = "Auto (the default) gives the game 4 GB, or 8 GB when 30 or more mods are enabled (in the main menu or in the save you played last), never more than half your RAM. The most memory the game's Java objects may use. The game ships with 3 GB. In a big horde (Louisville, ~2,000 zombies) 3 GB ran 95 % full, so the collector worked all the time and was one step from a full stop-the-world collection; at 2 GB that stop came and froze the game for 205 ms. 4 GB and more gave it room (the heap then settles at 3.6-3.9 GB); beyond 4 GB nothing changed. Driving and walking used about 2-2.5 GB, and on the desktop the frame rate was the same from 3 GB up; a bigger heap is a margin against that freeze in bigger hordes and long sessions. Mods need more: with 132 popular Workshop mods the game held 3.7-4.1 GB right after loading, 4 GB filled up and froze it for 150-280 ms at a time (up to 1.1 s in a row) in 3 of 5 runs, the game's own 3 GB in every run; 8 GB never did. With a big mod list pick 6 or 8 GB. The larger sizes are for very large mod lists: the most any test needed was 4.7 GB (132 mods, 8 GB heap). A size over half your RAM is lowered to half (64 GB needs a 128 GB machine). From 32 GB up Java stores every object reference in 8 bytes instead of 4, so a 32 GB heap holds less than a 31 GB one would and every collection walks more memory: go past 24 GB only if you need far more than that. Never more than half of your RAM is used, whatever you pick. Written into the game's launcher file for the next launch; the uninstallers put the game's own value back." },
            { key = "luaGcNoop", label = "Ignore mods' requests for a full memory clean-up",
              tip = "Some mods call Lua's collectgarbage() to \"free memory\". In this game that is a full stop-the-world collection of all of Java's memory: every call froze the game for 120-140 ms in a vanilla game (more with a bigger heap); Java's own concurrent mode was worse, 270-320 ms, because the game thread waits for the whole cycle. Java collects the same memory on its own anyway, so with this on (the default) such calls return at once; asking how much memory Lua uses still works." },
            { key = "gcHeapFixed", label = "Reserve the whole heap from the start",
              tip = "The heap starts at its full size instead of growing as the game needs it (Java's -Xms equal to the heap size), so it is never resized while you play. The memory is taken from the system at once. Applies on the next launch." },
            { key = "gcPreTouch", label = "Touch the heap's memory at boot",
              tip = "Java writes every page of the heap at boot (-XX:+AlwaysPreTouch) so the system never has to hand out a page while the game runs. Uses the memory at once and adds a moment to the boot; best together with \"Reserve the whole heap from the start\". Applies on the next launch." },
            { key = "gcMode", label = "Garbage collector",
              choices = { "g1", "auto", "stock" },
              note = { g1 = "G1 (default)", auto = "G1 on 4 cores or fewer, the game's own above", stock = "the game's own: ZGC" },
              tip = "The game ships with ZGC, which collects alongside the game on its own threads. In a horde its 3 GB heap ran 95 % full and ZGC fell behind: the game thread had to wait for it and the horde ran 66 fps against 72-90 with G1; uncapped on the spinning route ZGC was 3 % slower too. G1 pauses the game for a few milliseconds a few times a minute instead. Applies on the next launch." },
            { key = "gcPauseMs", label = "G1 pause target",
              choices = { "0", "25", "50", "100" }, note = { ["0"] = "G1's own (200 ms)" },
              tip = "The longest pause G1 aims for. Its pauses in the game are already 10-20 ms against its own 200 ms target; a 25 ms target made no measurable difference to the frame times. Applies on the next launch." },
        },
    },
    {
        title = "Sound", clip = "horde",
        entries = {
            { key = "audioLimiter", label = "Limiter on the game's final mix",
              tip = "The game mixes in 5.1 at 32 kHz and has no limiter: a gun fired next to you over alarms and a horde went over full scale and the sound card clipped it (about 6,000 clipped samples in 25 s in the test scene). FMOD's own limiter now sits at the very end of the mix; below its ceiling it changes nothing. Applies on the next launch." },
            { key = "audioLimiterCeilingDb", label = "Limiter ceiling",
              choices = { "-1", "-2", "-3", "-6" }, note = { ["-2"] = "default" },
              tip = "The highest level the mix may reach, in dB below full scale. -2 leaves room for the conversion to your sound card's rate (the game mixes at 32 kHz). Applies on the next launch." },
            { key = "audioLimiterStereoFold", label = "Fold to stereo before the limiter on stereo devices",
              tip = "On headphones or stereo speakers the system mixer folds the game's six channels into two by adding them up, after the game, where nothing could stop the sum from clipping. With this on the game does that fold itself, just before the limiter, so the limiter sees what you hear. 5.1 and 7.1 devices keep their surround. Applies on the next launch." },
            { key = "soundTickHz", label = "Sound upkeep rate",
              choices = { "0", "30", "60", "120" }, note = { ["0"] = "every frame, stock", ["60"] = "default" },
              tip = "How often per second the ambient-object sounds, the wall emitters, the room and weather sound parameters and the sound parameters of zombies making noise are refreshed. The sound engine applies them every 20 ms, so above ~60 fps stock recomputed them several times for every change you could hear. Below this rate nothing changes." },
            { key = "emitterIdleSkip", label = "Skip silent zombies' sound emitters",
              tip = "A zombie that is not making any sound skips its three sound emitters entirely; a flag set when it starts a sound brings it back. Stock checked every zombie's emitters every frame (about 2 % of the game thread in a horde of 2,000). Same sounds." },
            { key = "worldSoundCleanupFast", label = "Cheap cleanup of expired noises",
              tip = "Every noise zombies can hear (alarms, shots, thumps) is listed in each loaded chunk it reaches and cleared out of those lists when it expires. Stock swept every chunk's list every frame and shifted the list for each expired entry; now the sweep only runs after a frame in which a noise expired, and clears a list in one pass. Same lists, same order." },
            { key = "hearingHoist", label = "Zombie hearing: fewer repeated lookups",
              tip = "When a zombie looks for the loudest noise around it, its own hearing and the square it stands on were looked up again for every noise in the list; they are now looked up once per search. Same answer." },
        },
    },
    {
        title = "Multiplayer", clip = "drive",
        entries = {
            { key = "luaChecksumExempt", label = "Leave the pzopt Lua files out of the server file check",
              tip = "When joining a server the game lists every Lua file under media/lua to the server; the pzopt files (this tab and its search index, the frame cap combo, the key binding, the update item) only exist on clients and a server without them refused the join with \"File doesn't exist on the server\". They are skipped like the game skips SandboxVars.lua. Applies on the next launch." },
        },
    },
    {
        title = "Updates", clip = "load",
        entries = {
            { key = "updateCheck", label = "Offer new releases in the main menu",
              tip = "Once per boot the main menu asks the GitHub releases (one request to api.github.com) whether a newer build for this game revision exists. The \"PZ OPTIMIZATION UPDATE\" item between Credits and Exit is greyed out while the build is current and enabled when a newer one exists: it downloads the zip, replaces the installed files and asks to quit so the next launch loads them. Nothing is downloaded without that click. Applies on the next launch." },
            { key = "updatePrefetch", label = "Get an offered update ready in the background",
              tip = "When the check finds a newer build, the files that differ from the installed ones are fetched right away (a release apart is usually a few files, tens of KB, read out of the release zip with range requests; the whole zip is never downloaded without the click), so Update now only writes them: a few milliseconds instead of downloading and unpacking 59 MB. Needs the first setting. Applies on the next launch." },
            { key = "updateFromWorkshop", label = "Update from the Steam Workshop copy",
              tip = "Subscribers already have every release: Steam downloads the Workshop item (the same files as the GitHub release zip) into the Steam library. The update check looks there first, before GitHub, and offers that copy when it is newer than the installed build: Update now then copies it from the disk, no download. GitHub is still asked afterwards and only replaces the offer with a newer release, so the check also works where GitHub cannot be reached. Needs the setting above. Applies on the next launch." },
        },
    },
    {
        title = "Render distance", clip = "grid",
        entries = {
            { key = "chunkGridWidth", label = "Render distance (chunk grid width)",
              choices = { "0", "auto", "7", "9", "11", "13", "15", "19", "21", "23", "25", "27", "31" },
              note = { ["0"] = "vanilla", ["auto"] = "fill the screen at the widest zoom", ["7"] = "56 tiles", ["9"] = "72 tiles",
                       ["11"] = "88 tiles", ["13"] = "104 tiles", ["15"] = "120 tiles", ["19"] = "152 tiles, vanilla at 1080p and above",
                       ["21"] = "168 tiles, fills 4K", ["23"] = "184 tiles", ["25"] = "200 tiles, fills 5120x2160", ["27"] = "216 tiles",
                       ["31"] = "248 tiles" },
              tip = "How many chunks (8 tiles each) per side are loaded, simulated, lit and drawn around you. Vanilla picks it from the screen size but stops at 19 (152 tiles), sized for 1080p: on a 4K or ultrawide screen the world ends before the screen corners at the widest zooms. Auto picks the smallest grid that fills your screen at the widest zoom, never less than vanilla (21 at 3840x2160, 25 at 5120x2160, vanilla at 1080p). Smaller than vanilla = less world to update every frame, which helps CPU-limited setups (heavy mod lists, NPC mods), with the world ending nearer the screen edge. Larger = more CPU, RAM and VRAM and longer loads; zombies, vehicles and sounds are simulated further out. Only the grid size changes; zombie AI, streaming and culling are untouched." },
            { key = "chunkGridFollowView", label = "Render distance follows the view upstairs",
              tip = "The camera looks at you from above, so on an upper floor the ground in the middle of the screen is 3 tiles north and 3 tiles west of you per level. With a render distance wider than vanilla the grid then moves with that ground point, so the screen corners stay filled upstairs as on the ground floor (25 at 5120x2160 up to level 8). You always stay at least as far from every grid edge as in vanilla; a grid no wider than vanilla never moves. Off = the grid stays centred on you and the top corners go dark from the second or third floor at the widest zoom. Single player only. Applies on the next launch." },
        },
    },
    {
        title = "Chunk streaming", clip = "drive",
        entries = {
            { key = "parallel", label = "Chunk loading in parallel",
              tip = "Chunk recalculation runs on a worker pool. Off = the stock single-threaded pass." },
            { key = "workers", label = "Chunk worker threads",
              choices = { "1", "2", "3", "4", "6", "8" },
              tip = "Width of the recalc pool; never more than cores - 1." },
            { key = "loadWorkers", label = "Chunk worker threads while a world loads",
              choices = { "1", "2", "4", "6", "8", "12" },
              tip = "The initial 361-chunk recalc uses this many threads, then the pool shrinks back." },
            { key = "wake", label = "Wake the streamer on demand",
              tip = "The streamer thread wakes when a chunk is queued instead of polling every 140 ms." },
            { key = "chunkHandoffDivisor", label = "Chunk hand-off budget (queue divisor)",
              choices = { "0", "4", "8", "16" }, note = { ["0"] = "stock: up to 4 chunks a frame" },
              tip = "At most 1 + queued/divisor freshly loaded chunks are handed to the game thread per frame, so a chunk row arriving at once is spread over a few frames instead of one long one." },
            { key = "hotsaveStaged", label = "Staged hot save",
              tip = "The periodic hot save serialises the meta grid and other systems one part per streamer update instead of all in one frame." },
            { key = "hotsaveIntervalSec", label = "Seconds between hot saves",
              choices = { "0", "5", "15", "30", "60", "120" }, note = { ["0"] = "every drain, stock" },
              tip = "Minimum seconds between the game-thread saves of the meta grid, game time, world map and entities that follow every drained chunk-save queue." },
        },
    },
    {
        title = "Boot: threads and caches", clip = "load",
        entries = {
            { key = "fmodAsync", label = "Start audio on a boot thread",
              tip = "FMOD and its banks (~1.6 s) initialise on a thread during boot." },
            { key = "preloadAnimSets", label = "Parse animation sets on a boot thread",
              tip = "The player and zombie animation-set XML trees parse off the loader thread (1.1 s)." },
            { key = "bootPump", label = "Decode textures during boot",
              tip = "A thread pumps the async file system during boot so texture pages and animations decode before the main menu." },
            { key = "bootFileThreads", label = "File threads during boot",
              choices = { "2", "4", "6", "8", "10", "12" },
              tip = "File pool width while the boot pump runs; shrinks to the in-game width at the load." },
            { key = "earlyModels", label = "Register models early",
              tip = "Models and the animation queue register right after the scripts load, giving the boot pump ~2 s more." },
            { key = "luaPrecompile", label = "Precompile Lua on a pool",
              tip = "Every Lua file compiles on a thread pool during boot; the game takes the prototypes from that cache." },
            { key = "animClipCache", label = "Cache animation clips",
              tip = "Imported animation clips are written under Zomboid/pzopt/anims and read from there on later boots." },
            { key = "packIndex", label = "Index texture packs",
              tip = "Texture packs keep their page offsets under Zomboid/pzopt/packs so the reader seeks instead of scanning 526 MB at boot." },
        },
    },
    {
        title = "Boot: parsers", clip = "load",
        entries = {
            { key = "scriptParserFast", label = "Linear script parser",
              tip = "Script comments strip in one pass and tokens parse without re-substringing; identical output (the stock passes cost 1.8 s at boot)." },
            { key = "itemParamSwitch", label = "Item parameter switch",
              tip = "Item script fields dispatch through a switch instead of a chain of 361 string compares per parameter (0.9 s of boot)." },
        },
    },
    {
        title = "World load: file system and decoding", clip = "load",
        entries = {
            { key = "fileThreads", label = "File threads in game",
              choices = { "1", "2", "4", "6", "8", "12" },
              tip = "Worker threads of the game's async file system (texture decode, model and animation import, depth maps). Stock: 2 on up to 4 cores, else 4." },
            { key = "fileInflight", label = "File tasks in flight",
              choices = { "8", "16", "32", "64" }, note = { ["16"] = "stock" },
              tip = "File tasks handed to the file threads at once." },
            { key = "textureBufferMb", label = "Decoded texture buffer (MB)",
              choices = { "50", "100", "256" }, note = { ["50"] = "stock" },
              tip = "Decoded texture bytes that may wait for the render thread before the decoders pause. Large values pile uploads onto one frame." },
            { key = "parallelDepthMaps", label = "Decode depth maps in parallel",
              tip = "The 218 depth-map tilesets decode concurrently instead of one at a time under one lock." },
            { key = "loaderCpuFixes", label = "Loader thread algorithmic fixes",
              tip = "Lot headers, vehicle zones and room ids resolve once per cell instead of repeatedly; identical results." },
            { key = "shaderCache", label = "Reuse model shaders",
              tip = "A model takes a shader an earlier model already created instead of waiting one loading-screen frame for the render thread." },
            { key = "mipmapArrays", label = "Row-based texture mipmaps",
              tip = "Texture mipmaps and alpha premultiply build row by row on byte arrays; same pixels as stock." },
            { key = "texCompress", label = "Texture compression: who compresses",
              choices = { "auto", "worker", "driver" },
              note = { auto = "the GPU where it runs compute shaders (OpenGL 4.3), otherwise the file threads", worker = "the file threads on the CPU, never the GPU", ["driver"] = "stock: the graphics driver, inside each upload on the render thread" },
              tip = "Only matters with the game's own \"Texture compression\" option on (the low-end preset and the Steam Deck defaults). Stock hands every texture to the graphics driver uncompressed and lets it compress; Mesa does that on the CPU inside the upload, on the render thread (about 26 s a boot on a Radeon 890M: the main menu at 25-34 fps for 10-16 s). Auto: the file threads build the levels and the GPU encodes them (render thread ~0.1 s a boot). Worker: the file threads also encode them, the GPU does no compression work. Applies on the next launch." },
            { key = "tileDefPreload", label = "Build tile definitions during boot",
              tip = "The ~60k tile sprites are built on a thread while the main menu loads; Continue only binds their textures instead of parsing the tile files again." },
            { key = "skipIdChecks", label = "Skip room-id consistency checks",
              tip = "A log-only check over every building and room of the map (six walks per load) runs in debug mode only." },
            { key = "voronoiFast", label = "Fast zombie-density noise",
              tip = "The zombie voronoi noise of every map cell is computed per sector instead of per sample; identical values, half the map loading time." },
            { key = "earlyTilePacks", label = "Load tile textures earlier in boot",
              tip = "The tile texture packs and depth maps start decoding before the boot Lua load instead of after it, so the world load does not wait for them." },
            { key = "aotCache", label = "Warm-start cache (Java AOT)",
              tip = "The game records a Java AOT cache on one launch and starts from it afterwards: warm code for the menu and every world load. Changes the launcher config (backed up once); off puts it back." },
        },
    },
    {
        title = "World load: loading screen", clip = "load",
        entries = {
            { key = "noLoadFade", label = "Skip the loading-screen fade",
              tip = "The loading screen does not fade to black (350 ms) before the world's own fade-in." },
            { key = "noIntroWait", label = "Click-to-start as soon as a new game is loaded",
              tip = "A new game shows click-to-start when loading is done instead of after the 33 s intro text." },
            { key = "noClickToStart", label = "Enter the world without click-to-start",
              tip = "The loading screen goes straight into the world when loading is done instead of waiting for a click or A." },
            { key = "noLoadingScreen", label = "No loading screen",
              tip = "Single player: no fade from black into the world, and with \"Show the last view while loading\" no loading screen either." },
            { key = "resumeShot", label = "Show the last view while loading",
              tip = "Quitting keeps the view around you (see Last view detail); Continue shows it while loading, its chunks appearing the way the world loads, and the world builds over it. Saves without it show the normal loading screen." },
            { key = "resumeShotDetail", label = "Last view detail",
              choices = { "floors", "buildings", "world", "full" },
              tip = "What the last view keeps when you quit: floors = the ground only; buildings = floors, walls, doors and furniture; world = everything that does not move, trees included; full = the frame as you left it, zombies and cars too. Taken at the next quit." },
            { key = "centerFirstLoad", label = "Load the world from the centre outwards",
              tip = "The chunks around you load and light first and the world appears as soon as they are ready; the rest streams in around you." },
        },
    },
}

-- The Enhancements tab (2026-09-25): what changes the picture rather than the frame time: upscaling, HDR output and
-- ambient occlusion, on a page of their own; same controls, preview and search as the Optimizations tab. They are part
-- of the overrides, so they need the Optimizations tab's master switch on. `profiles` marks the section whose keys the
-- Optimizations tab's profile buttons set (the low-end + FSR set picks the upscaler; HDR and AO stay as they are).
local ENHANCEMENT_SECTIONS = {
    {
        title = "Upscaling (render the world smaller, resolve to the screen)", clip = "upscale", profiles = true,
        entries = {
            { key = "upscaler", label = "Upscaler",
              choices = { "off", "bicubic", "fsr1", "taau", "dlss", "xess" },
              note = { off = "stock: the world renders at the screen size", bicubic = "the stock screen filter, any GPU", fsr1 = "AMD FidelityFX Super Resolution 1.0, any GPU", taau = "temporal upsampling: gathers detail over frames like DLSS, any GPU", dlss = "NVIDIA DLSS Super Resolution (RTX; needs the shim built from the repository under natives/, not in the release); else runs as fsr1", xess = "Intel XeSS: not available yet, runs as fsr1" },
              tip = "The world is rendered at a fraction of the screen size (see \"Upscaler quality\") and scaled back up before the UI, text and the stock screen shader, which stay at full resolution. GPU-bound scenes (fog, storms, big towns, 4K, laptops) gain roughly the pixel ratio. fsr1 is a sharp spatial upscaler that works on every GPU; dlss accumulates detail over frames on an RTX card; bicubic is the plain stretch." },
            { key = "upscalerQuality", label = "Upscaler quality (render size)",
              choices = { "quality", "balanced", "performance", "ultra", "native" },
              note = { quality = "67 % per axis (44 % of the pixels)", balanced = "58 %", performance = "50 % (a quarter of the pixels)", ultra = "33 %", native = "100 % (dlss: DLAA anti-aliasing only)" },
              tip = "The render size per axis. Quality keeps most of the detail; performance halves the axes for a quarter of the world-pass GPU work." },
            { key = "upscalerScalePct", label = "Upscaler render scale of your own (%)",
              choices = { "0", "40", "50", "60", "67", "75", "85" }, note = { ["0"] = "use the quality preset" },
              tip = "A render scale of your own in percent per axis instead of the quality preset (10-100)." },
            { key = "fsrSharpnessPct", label = "Upscaler, AMD FSR 1.0: sharpening (%)",
              choices = { "0", "40", "60", "80", "100" }, note = { ["0"] = "none", ["100"] = "the sharpest" },
              tip = "The contrast-adaptive sharpening (RCAS) after the FSR 1.0 upsample; 80 is AMD's usual default." },
            { key = "dlssPreset", label = "Upscaler, NVIDIA DLSS: model preset",
              choices = { "e", "default", "f", "k", "j", "m", "l" },
              note = { default = "NVIDIA's choice: the transformer models K / M / L", f = "older convolutional model: ~1.5 ms cheaper a frame at 4K, a little softer", e = "recommended: the older convolutional model, half the cost of K and no trails in this game", k = "transformer, best quality", j = "transformer, less ghosting, more flicker", m = "transformer, the performance-mode default", l = "transformer, the ultra-performance default" },
              tip = "Which DLSS network runs. The transformer models (DLSS 4) reconstruct the most detail but cost ~2.5 ms a frame at 5120x2160 on an RTX 4090 (231 fps on the 120 km/h drive vs 377 with preset F and 619 with FSR 1.0); at 1440p and below the cost is a third or less." },
            { key = "dlssOutputPct", label = "Upscaler, NVIDIA DLSS: output size (%)",
              choices = { "67", "75", "0" },
              note = { ["0"] = "the screen size: full DLSS super resolution", ["75"] = "DLSS writes 75 % of the screen, the finish below does the rest", ["67"] = "recommended: DLSS anti-aliases at the render size (quality), the finish does the upscale" },
              tip = "DLSS costs time per pixel it writes, not per pixel it reads. Writing less than the screen and letting a cheap filter finish the upscale is what makes DLSS faster than no upscaler on a 4K screen: on an RTX 4090 at 5120x2160 in a heavy storm with fog, DLSS at quality with preset E gains +22 % at 67 % with the sharpen finish (and a sharper image than full-size DLSS with the default model), +17 % at 75 %, +10 % at the full size. At DLAA (native) the output is always the screen size." },
            { key = "dlssOutputFilter", label = "Upscaler, NVIDIA DLSS: output finish",
              choices = { "rcas", "bicubic", "fsr1" },
              note = { rcas = "recommended: FSR 1.0's sharpen at the DLSS size, then the bicubic", bicubic = "the stock screen shader's bicubic, free but soft", fsr1 = "FSR 1.0 EASU + RCAS: the sharpest, ~0.2 ms a frame at 4K" },
              tip = "How a DLSS output smaller than the screen (the size above) is brought to the screen." },
            { key = "dlssSharpen", label = "Upscaler, NVIDIA DLSS: sharpening",
              tip = "Asks DLSS for its mild extra sharpening pass on top of the super resolution; off is the plain reconstruction." },
            { key = "upscalerObjectMv", label = "Upscaler, temporal: character and vehicle motion vectors",
              tip = "The temporal upscalers get each character's and vehicle's own motion on top of the camera's, so moving zombies and cars do not ghost or smear. Off = camera motion only (an A/B)." },
            { key = "dlssWaterCurrent", label = "Upscaler, NVIDIA DLSS: water from the current frame",
              tip = "DLSS blends each frame with the previous ones; the water's ripples move in place with no motion vectors, so that blend slowed them to half speed. On: the water pixels show the frame just drawn, so rivers and lakes flow like the stock game. Off = DLSS's own image everywhere (an A/B)." },
            { key = "dlssWaterHistoryPct", label = "Upscaler, NVIDIA DLSS: water smoothing (%)",
              choices = { "0", "25", "50", "60", "75" }, note = { ["0"] = "the current frame alone", ["60"] = "recommended" },
              tip = "With the water from the current frame, how much of the previous frame's water is mixed in. DLSS shifts each frame by a fraction of a pixel; one frame of history evens that out so the ripples do not shimmer, without slowing them." },
        },
    },
    {
        title = "Dynamic resolution (hold the frame rate cap)", clip = "upscale",
        entries = {
            { key = "dynRes", label = "Dynamic resolution",
              tip = "The world's render size follows the GPU's time per frame so it fits your frame rate cap: when a scene gets heavy (fog, storms, big towns, a laptop) the world renders smaller for as long as it needs to and grows back when the GPU has room again. The UI and text stay sharp. Only lowers the resolution when the picture's pixels are what costs the time (chunk loading spikes are left alone: a smaller picture would not help them). Uses the upscaler above; with the upscaler off it uses the choice below. Uncapped: stays at the highest size." },
            { key = "dynResUpscaler", label = "Dynamic resolution: upscaler when the upscaler is off",
              choices = { "taau", "fsr1", "bicubic" },
              note = { taau = "temporal: the steadiest picture while the size changes", fsr1 = "AMD FSR 1.0: sharp, a size change can show", bicubic = "the stock filter" },
              tip = "How the smaller world picture is brought to the screen when \"Upscaler\" is off. The temporal upscaler builds the picture over several frames at the screen size, so a change of the render size is hard to see." },
            { key = "dynResTargetPct", label = "Dynamic resolution: GPU budget (% of the frame time)",
              choices = { "80", "85", "90", "95" }, note = { ["90"] = "default" },
              tip = "How much of each frame's time (1 / your cap) the GPU may use; the rest is headroom for the frame-to-frame noise. Lower holds the cap more firmly at a lower resolution." },
            { key = "dynResMinPct", label = "Dynamic resolution: lowest render size (%)",
              choices = { "33", "40", "50", "60", "67", "75" }, note = { ["50"] = "default (a quarter of the pixels)" },
              tip = "The smallest render size per axis it may go down to." },
            { key = "dynResMaxPct", label = "Dynamic resolution: highest render size (%)",
              choices = { "100", "90", "85", "75" }, note = { ["100"] = "native" },
              tip = "The largest render size per axis." },
            { key = "dynResFps", label = "Dynamic resolution: frame rate to hold",
              choices = { "0", "60", "90", "120", "144", "165", "240" }, note = { ["0"] = "your frame rate cap" },
              tip = "The frame rate the GPU time is held to; 0 = the cap in force (the variable-refresh cap when VRR is on)." },
            { key = "dynResController", label = "Dynamic resolution: controller",
              choices = { "model", "pi", "step" },
              note = { model = "recommended: learns what the pixels cost and solves for the size", pi = "integral control on the GPU time", step = "the classic engine rule: drop at once, creep back up" },
              tip = "How the render size is chosen. The model separates the part of the GPU time that follows the pixel count from the part that does not, ignores lone spikes, and keeps a headroom sized to the measured noise." },
        },
    },
    {
        title = "Sprite filtering (sharp world art when zoomed in or out)", clip = "upscale",
        entries = {
            { key = "spriteFilter", label = "Sprite filtering",
              choices = { "stock", "sharp", "nearest" },
              note = { stock = "the game's own (soft at 75 % zoom and when zoomed out)", sharp = "texel-aware: crisp edges without shimmer", nearest = "hard pixels" },
              tip = "How the world's art is scaled to your zoom. The game draws it sharp at 100 % and 50 %, soft at 75 % (a linear blur) and when zoomed out (a blend of the full picture and a blurred half-size copy: the blurry look while driving). Sharp: zoomed in, every texel of the art stays a hard square and only the one screen pixel an edge crosses is blended by how much of it the texel covers, so edges are crisp at any zoom and do not crawl while the camera glides; zoomed out, four samples per pixel from a sharper copy of the art keep fences, window frames and road lines sharp without shimmer. At 100 % the picture is the game's own. Nearest: hard pixels at every zoom-in step (75 % included), no blending. Costs next to nothing (the world picture is sampled by a variant of the same program, picked once a frame for the zoom). Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default); with per-pixel lighting on it applies on the next launch." },
            { key = "spriteFilterMin", label = "Sprite filtering: zoomed out",
              choices = { "rgssa2", "rgssa", "floor", "rgss4", "trilinear" },
              note = { rgssa2 = "adaptive, two samples (default)", rgssa = "adaptive, four samples", floor = "one sample, sharpest", rgss4 = "four samples, steadiest, softer", trilinear = "the game's own blend" },
              tip = "How the sharp filter shrinks the art when you zoom out. The game blends a half-size and a quarter-size copy of the art (soft); the sharp filter reads the one copy that fits the zoom. Adaptive spreads two (or four) samples as the zoom approaches the next copy, where fine lines would otherwise shimmer; one sample is the sharpest and cheapest with a little grain; four fixed samples is the steadiest and softest. The default costs less than the game's own blend." },
            { key = "spriteFilterSharpnessPct", label = "Sprite filtering: edge sharpness zoomed in (%)",
              choices = { "100", "150", "200", "300" }, note = { ["100"] = "exact one-pixel edges (default)" },
              tip = "100 blends each texel edge over exactly one screen pixel, the smoothest motion. Higher narrows the blend: harder edges, a little more crawl while the camera glides." },
            { key = "spriteFilterSprites", label = "Sprite filtering: also the tiles drawn per frame",
              tip = "The tiles the game draws every frame instead of into the chunk pictures (open doors, items, things near the cut-away walls) get the same filtering, so they match the rest of the world." },
        },
    },
    {
        title = "HDR output (Linux with HDR on under Wayland, macOS)", clip = "hdr",
        entries = {
            { key = "hdrAuto", label = "HDR output: automatic",
              tip = "On by default: when the game starts on an HDR screen it is shown as HDR by itself (Linux: a Wayland desktop with HDR switched on; macOS: a display with HDR headroom, e.g. a MacBook Pro's XDR screen), on an SDR screen nothing changes. Untick it to keep the SDR picture on an HDR screen. Applies on the next launch." },
            { key = "hdr", label = "HDR output: always on",
              tip = "Turns HDR on even when the automatic check finds no HDR screen. The game is shown as HDR: the menus and the HUD stay at your desktop's white, lamps, torches, headlights, fires and lightning go above it up to your screen's peak. Needs an HDR screen with HDR switched on in the desktop settings; on Linux the game then runs as a native Wayland window. On a Mac the lights and lightning go above the screen's white (the other HDR settings below are Linux only for now); an XDR display (MacBook Pro 14/16, Pro Display XDR) has the most room above it. Without an HDR screen, or on Windows for now, nothing changes. Applies on the next launch." },
            { key = "hdrLightPct", label = "HDR: how bright light sources make things (%)",
              choices = { "0", "50", "100", "150", "200" }, note = { ["0"] = "off: the SDR picture in an HDR container", ["100"] = "recommended (default)" },
              tip = "What makes it HDR: every square the game lights with a lamp, a torch, a headlight or a fire is shown brighter in proportion to how much light falls on it, up to the screen's peak, with its colours intact; the dark stays dark." },
            { key = "hdrBloomPct", label = "HDR: glow around bright light (%)",
              choices = { "0", "30", "50", "100" }, note = { ["0"] = "none", ["30"] = "default" },
              tip = "The light the HDR highlights add also spills a little into the dark around them, the way a bright lamp glows in the eye. Costs about 0.2 ms a frame at 4K. Linux only for now." },
            { key = "hdrSunPct", label = "HDR: sunlight (%)",
              choices = { "0", "30", "60", "100" }, note = { ["0"] = "daylight as in the SDR game", ["60"] = "default" },
              tip = "On a clear day the sunlit outdoors is shown brighter than the SDR picture by this much, less with the sun low or behind clouds; shade and interiors stay as they are. Linux only for now." },
            { key = "hdrGlintPct", label = "HDR: sparkle on water and puddles (%)",
              choices = { "0", "50", "100", "200" }, note = { ["0"] = "off (the water and puddle shaders stay stock)", ["100"] = "default" },
              tip = "The sun glitters on rivers, lakes and puddles above the desktop's white, the sky reflects in them, and at night lamps, torches and headlights glint on the water. Linux only for now." },
            { key = "hdrPaperPct", label = "HDR: world brightness (% of the desktop's white)",
              choices = { "60", "70", "80", "90", "100" }, note = { ["100"] = "the same brightness as the SDR game" },
              tip = "The brightness of the world's ordinary (unlit by lamps) surfaces relative to your desktop's white. Lower leaves more room above it for the highlights: they look brighter by contrast. Linux only for now." },
            { key = "hdrItmPct", label = "HDR: bright pixels at night become highlights (%)",
              choices = { "0", "25", "50", "100" }, note = { ["0"] = "off" },
              tip = "At night, pixels that are already near white (lit windows, bright signs) are also pushed above the desktop's white. Off in daylight. Linux only for now." },
            { key = "hdrSaturationPct", label = "HDR: extra colour (%)",
              choices = { "0", "5", "10", "20" }, note = { ["0"] = "the game's colours" },
              tip = "A little more colour saturation, using the wider colour range of HDR screens. Linux only for now." },
            { key = "hdrPeakNits", label = "HDR: brightest highlight (nits)",
              choices = { "0", "400", "600", "800", "1000", "1500" }, note = { ["0"] = "your screen's peak (what the desktop reports)" },
              tip = "Caps the brightest highlight below what the screen can do, if the peaks are too much. Linux only (macOS gives no nits)." },
            { key = "hdrUiNits", label = "HDR: menu and HUD white (nits)",
              choices = { "0", "100", "200", "300", "400" }, note = { ["0"] = "your desktop's white (what the desktop reports)" },
              tip = "The brightness of the menus and the HUD. By default the desktop's own white, so switching windows does not change brightness. Linux only (on a Mac the screen brightness sets it)." },
        },
    },
    {
        title = "Ambient occlusion (soft shading in corners, along wall bases and under furniture)", clip = "ao",
        entries = {
            { key = "ambientOcclusion", label = "Ambient occlusion",
              tip = "Soft shading where surfaces meet: floors darken a little along the base of walls, in room corners, under and around furniture, fences, stairs and bushes, so buildings and objects sit on the ground instead of floating on it. Computed from the game's own depth when a chunk's picture is drawn and baked into it, so it costs nothing on frames that draw no new chunk picture and nothing while the camera moves. Characters and vehicles keep their own shadows. Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default; on OpenGL 2.1 it stays off). Changing an ambient occlusion setting redraws every chunk picture over the next few frames." },
            { key = "aoStrengthFloorPct", label = "Ambient occlusion: strength on floors (%)",
              choices = { "0", "25", "50", "75", "100", "150" }, note = { ["0"] = "no shading on floors", ["100"] = "default" },
              tip = "How dark the shading gets on floors and flat ground where it is strongest: along the base of walls, in room corners, under furniture and around what stands on them. 0 leaves floors unshaded. The three strengths replace the single strength of earlier versions, whose value they take until you set them." },
            { key = "aoStrengthWallPct", label = "Ambient occlusion: strength on walls (%)",
              choices = { "0", "25", "50", "75", "100", "150" }, note = { ["0"] = "no shading on walls", ["100"] = "default" },
              tip = "How dark the shading gets on walls: where they meet the floor and each other, and behind furniture standing against them. 0 leaves walls unshaded." },
            { key = "aoStrengthObjectPct", label = "Ambient occlusion: strength on objects (%)",
              choices = { "0", "25", "50", "75", "100", "150" }, note = { ["0"] = "no shading on objects", ["100"] = "default" },
              tip = "How dark the shading gets on everything that is neither floor nor wall nor tree nor plant: furniture, counters, fences, stairs, crates. 0 leaves them unshaded." },
            { key = "aoStrengthVegetationPct", label = "Ambient occlusion: strength on trees (%)",
              choices = { "0", "25", "50", "75", "100", "150" }, note = { ["0"] = "no shading on trees", ["100"] = "default" },
              tip = "How dark the shading gets on trees and the soft shade their crowns cast on the ground straight below them. 0 leaves trees bright and removes the dark patch under them. Bushes, grass and flowers have their own strength below." },
            { key = "aoStrengthPlantPct", label = "Ambient occlusion: strength on bushes, grass and flowers (%)",
              choices = { "0", "25", "50", "75", "100", "150" }, note = { ["0"] = "no shading on bushes, grass and flowers", ["100"] = "default" },
              tip = "How dark the shading gets on bushes, hedges, tall grass and flowers where walls, fences, furniture and the ground close them in (their own leaves do not shade each other). 0 leaves them bright; the contact shading where a bush meets the floor follows the floor strength. Until you set it, it takes the value of the trees' strength (one strength for all vegetation in earlier versions)." },
            { key = "aoRadiusPct", label = "Ambient occlusion: reach (% of a tile)",
              choices = { "40", "60", "80", "100" }, note = { ["60"] = "default" },
              tip = "How far from a wall or an object the shading reaches, in tiles. Longer reach is softer and wider." },
            { key = "aoScalePct", label = "Ambient occlusion: detail (% of the chunk picture)",
              choices = { "25", "50", "100" }, note = { ["50"] = "default" },
              tip = "The resolution the shading is computed at. Lower costs less when a chunk's picture is redrawn with new objects, and is softer." },
        },
    },
    {
        title = "Sun, moon and cloud shadows (soft shadows of walls, trees, fences and furniture that follow the real sky)", clip = "ao",
        entries = {
            { key = "sunShadows", label = "Sun shadows",
              tip = "By day, walls, trees, fences, cars parked in the world's pictures and furniture outdoors cast soft shadows of the sun onto the ground and onto each other: sharp where they touch the ground, softer further away, like real sunlight. The sun stands where it really does over Kentucky for the game's date and hour (high and short-shadowed in summer, low and long in winter, rising in the north-east in June and the south-east in December), so the shadows turn and lengthen through the day; rain and fog thin them out and they fade away at dusk. Indoors stays as it is (rooms have a roof). Computed with the ambient occlusion when a chunk's picture is drawn and baked into it, so a still or moving camera costs nothing; when the sun has moved a little (every few in-game minutes) the pictures on screen are updated a few per frame. Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default)." },
            { key = "sunShadowStrengthPct", label = "Sun shadows: strength (%)",
              choices = { "25", "35", "45", "60", "75" }, note = { ["45"] = "default" },
              tip = "How much of the daylight a full shadow takes away on a clear day. Clouds, rain and fog lower it further." },
            { key = "sunShadowCharacters", label = "Sun shadows: characters",
              tip = "Characters cast the shadow of their body onto the ground, walls and furniture around them, drawn every frame, and a character standing in a building's or a tree's shadow is shaded too. Only characters drawn with a model (the ones near enough to animate); the far ones drawn as flat pictures cast a simple upright shape." },
            { key = "sunShadowMeshes", label = "Sun shadows: the true shape of characters, animals and vehicles",
              tip = "Each character, animal and vehicle is drawn once more, as the sun sees it, into a small shadow picture of its own, and its shadow takes that shape: arms, legs, hair, clothes, backpacks and weapons; an animal's legs, head and tail; a car's body and wheels. How often the pose is renewed: \"Sun shadows: update rate\". Off: a simpler shape of rounded pieces." },
            { key = "sunShadowRate", label = "Sun shadows: update rate",
              choices = { "frame", "15" },
              note = { frame = "recommended (default): every frame, the shadow moves with the character", ["15"] = "15 times a second, one frame behind: the earlier behaviour, a little lighter" },
              tip = "How often the true-shape shadow of a character, animal or vehicle takes its new pose. Every frame: the shadow is drawn in the same frame as the character, so arms and legs move in step with it. 15 times a second: each pose is kept for several frames and shown a frame late, which reads as a slightly choppy shadow next to a smoothly animated character; the shadow still follows the character's position every frame. With 40 characters on screen every frame cost about 0.09 ms a frame on an RTX 4090 against 0.03 ms for 15 a second; in big crowds at most 32 are renewed a frame (your own every frame), the rest in turns." },
            { key = "sunShadowAnimals", label = "Sun shadows: animals",
              tip = "Farm and wild animals cast sun shadows too." },
            { key = "sunShadowVehicles", label = "Sun shadows: vehicles",
              tip = "Cars and trucks outdoors cast a sun shadow of their body." },
            { key = "sunShadowTreeCards", label = "Sun shadows: the true shape of trees",
              tip = "A tree casts the shadow of its own picture, trunk, branches and leaves, in one piece from its foot to its crown, turned towards the sun so it never thins to a line. Off: a soft oval for the crown." },
            { key = "sunShadowStockFadePct", label = "Sun shadows: the game's round shadow under characters (fade, %)",
              choices = { "0", "50", "85", "100" }, note = { ["85"] = "default" },
              tip = "Where a character or a vehicle casts a real sun shadow, the game's own round shadow under its feet fades by this much (it was a second shadow beside the sun's). Overcast, at night and indoors it stays." },
            { key = "sunShadowTorches", label = "Shadows from torches and headlights",
              tip = "At night (and in dark places), characters caught in a torch beam or in headlights cast long soft shadows away from the light. The one holding the torch does not shadow their own beam." },
            { key = "sunShadowSoftnessPct", label = "Sun shadows: softness (%)",
              choices = { "10", "25", "50", "100", "200" }, note = { ["25"] = "default" },
              tip = "How quickly a shadow's edge blurs with the distance from what casts it (the size of the sun's disk). 25 is close to real sunlight: a tree's shadow keeps its branches and leaves, a character's its arms and legs; 100 and more blur every shadow into a soft shape; 10 is as sharp as the real sun." },
            { key = "moonShadows", label = "Moon shadows",
              tip = "At night the moon casts the same soft shadows when it is up: where it really is for the game's date and hour, as strong as its phase allows (a full moon high in a clear sky casts clear shadows, a thin crescent barely any, a new moon none), once the sky is dark. The game's own night brightness already follows the moon's phase." },
            { key = "moonShadowPct", label = "Moon shadows: strength (% of the sun's)",
              choices = { "50", "75", "100", "125" }, note = { ["100"] = "default" },
              tip = "How dark a full moon's shadows are compared with the sun's." },
            { key = "cloudShadows", label = "Cloud shadows",
              tip = "The shadows of the clouds drift over the sunlit (or moonlit) ground, walls and roofs with the wind: as many as the weather's cloud cover, changing shape as they go, lying where the sun ray through each cloud meets the ground. Under a cloud the sunlit outdoors takes exactly the shade of a building's shadow, and the shadows the sun casts fade there; characters and cars walking into one are shaded too. Only where there is a cloud: a clear sky costs nothing, and a cloudy one a few texture reads per pixel of the world picture. Full overcast has no cloud gaps and so no shadows. Windows and Linux. Applies on the next launch when sun shadows or cloud shadows were off when the game started (the game's own shaders stay untouched then)." },
            { key = "cloudOpacityPct", label = "Cloud shadows: darkness (%)",
              choices = { "50", "70", "85", "100" }, note = { ["85"] = "default" },
              tip = "How much of the sun a thick cloud holds back (100: all of it, so the shade under it is as dark as a building's shadow)." },
            { key = "cloudSpeedPct", label = "Cloud shadows: drift speed (%)",
              choices = { "0", "50", "100", "200" }, note = { ["100"] = "default", ["0"] = "still" },
              tip = "How fast the cloud shadows travel, % of the wind at the clouds' height (they follow the game's clock: they stop when it is paused and race with fast-forward)." },
            { key = "cloudScalePct", label = "Cloud shadows: cloud size (%)",
              choices = { "50", "100", "200" }, note = { ["100"] = "default" },
              tip = "The size of the clouds (100: fair-weather cumulus, their shadows a hundred to two hundred tiles across)." },
        },
    },
    {
        title = "Reflections (the scene mirrored in rivers, lakes and puddles)", clip = "hdr",
        entries = {
            { key = "reflections", label = "Reflections",
              tip = "Buildings, fences, trees, lamp posts, cars and characters are mirrored in rivers, lakes and puddles, rippled by the waves and the rain, sharp where they meet the water and softer further out, stronger at grazing angles of the waves. Every surface picture the game already draws writes itself where its mirror image lands, so the water only looks up one value: nothing extra is drawn where there is no water or puddle on screen. Characters' reflections are one frame behind. Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default; there the water marches up its pixel column for the reflection, OpenGL 4.1 has no image atomics for the pixel-projected method). Turning it on applies on the next launch (the game's water and chunk shaders are only patched when it starts with reflections on; off, they stay exactly the game's own)." },
            { key = "reflectionStrengthPct", label = "Reflections: strength (%)",
              choices = { "25", "45", "70", "100" }, note = { ["45"] = "default" },
              tip = "How strongly the water mirrors the scene. Real water seen from the game's camera angle reflects little (a few percent, more on the side of a wave); higher is more of a mirror." },
            { key = "reflectionPuddles", label = "Reflections: in puddles",
              tip = "Puddles mirror the scene too once the rain has made them big enough (the rain's rings blur them)." },
        },
    },
    {
        title = "Car glass (windows that reflect the world and show the cabin)", clip = "hdr",
        entries = {
            { key = "carGlass", label = "Car glass",
              tip = "Car windows become glass instead of the stock opaque blue: they mirror the sky (the game's own, with its clouds and sunsets, turned to the real sun), the buildings, trees and road around the car (each car's small reflection probe, marched through the scene before the cars are drawn), and glint where they mirror the sun, the moon, the street lamps and headlights, more at grazing angles (Fresnel); and through them you see the cabin: seats, headrests, the dashboard, the driver and passengers, and the street behind the car where you look out through the far window. Cracks, blood and broken-out windows stay as the game draws them; rain beads on the glass. About a hundredth of a millisecond for a street of parked cars on a fast GPU; nothing when no car is on screen. Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default). Turning it on applies on the next launch (the game's vehicle shaders are only patched when it starts with car glass on)." },
            { key = "carGlassReflectPct", label = "Car glass: reflection strength (%)",
              choices = { "60", "100", "150", "200" }, note = { ["100"] = "default" },
              tip = "How strongly the windows mirror (100: real glass, four percent head-on, more at grazing angles; higher reads more like a mirror)." },
            { key = "carGlassInteriorPct", label = "Car glass: cabin light (%)",
              choices = { "50", "100", "150", "200" }, note = { ["100"] = "default" },
              tip = "How bright the inside of the car is behind the glass." },
            { key = "carGlassSunPct", label = "Car glass: sun and moon glint (%)",
              choices = { "0", "50", "100", "200" }, note = { ["100"] = "default", ["0"] = "none" },
              tip = "The sharp highlight where a window mirrors the sun (or the moon at night)." },
            { key = "carGlassRainPct", label = "Car glass: raindrops (%)",
              choices = { "0", "50", "100" }, note = { ["100"] = "default", ["0"] = "none" },
              tip = "Drops beading on the windows of cars standing out in the rain, each a tiny lens that catches the sky and the glints." },
            { key = "carOccupant", label = "Car glass: the people inside",
              choices = { "impostor", "proxy", "box", "off" }, note = { ["impostor"] = "default", ["off"] = "empty cars" },
              tip = "Who sits in a car shows through its glass. The game never draws a seated character (cars look empty whoever drives them), though it animates them. impostor: the game's own model of the driver and passengers, hands on the wheel, in their clothes, drawn into a small picture of their own before the cars and looked up by the glass wherever it shows the cabin; redrawn when they move (turning the wheel up to 60 times a second, still a few times a second), between that the glass moves the last picture with the car: about a thousandth of a millisecond a frame parked, a few thousandths driving on a fast GPU. proxy: a simple body (head, torso, arms, legs) in their skin, hair and clothing colours, worked out by the glass itself (nothing extra drawn; cruder). box: the first release's dark torso shape. Applies on the next launch." },
            { key = "carOccupantOcclusion", label = "Car glass: seats hide the people inside",
              tip = "Off (default): the people inside show wherever the glass shows the cabin, over the seats in front of them (a cutaway: from the game's high camera angle the roof and the seats would otherwise hide most of a driver). On: the seats, headrests and floor hide them where they stand between them and the glass. Applies on the next launch." },
            { key = "carOccupantLightPct", label = "Car glass: light on the people inside (%)",
              choices = { "60", "80", "100", "120" }, note = { ["80"] = "default" },
              tip = "How bright the people inside the car are, against how the game lights them outside (the roof shades them). Applies on the next launch." },
        },
    },
    {
        title = "Occluded zombie outlines (the hidden parts of the zombies you see)",
        entries = {
            { key = "occludedZombieOutlines", label = "Outline the hidden parts of zombies you see",
              tip = "A zombie you can see that walks behind a wall, a tree, a fence or a car keeps a thin contour where the scenery hides it, so you know where it went. Only zombies your character sees right now (the same sight that draws them): no unseen or remembered zombies, nothing through the dark (the contour fades with the light on the zombie). The parts hidden behind other characters are not outlined. Next to nothing on a fast GPU: the zombies' own draws mark what they cover, then one pass draws the contour where they are hidden. Applies at once. Off while DLSS / TAAU object motion is on (it uses the same buffer bits)." },
            { key = "occludedOutlineIgnorePlants", label = "Occluded outlines: ignore grass and bushes",
              tip = "A zombie standing in grass or bushes, with nothing solid in front of it, does not get its legs outlined by the plants; a wall, a fence, a tree or a car further in front still outlines it." },
            { key = "occludedOutlineWidth", label = "Occluded outlines: width (render pixels)",
              choices = { "1", "2", "3", "4" }, note = { ["1"] = "default" },
              tip = "The contour's width in pixels of the world picture (an upscaler draws them larger). Only the zombie's own outline is drawn, never a line along the edge of what hides it." },
            { key = "occludedOutlineColour", label = "Occluded outlines: colour", colour = true,
              tip = "Pick the contour colour, then press Apply. The aiming and interaction outlines keep theirs." },
            { key = "occludedOutlineOpacityPct", label = "Occluded outlines: opacity (%)",
              choices = { "25", "50", "70", "100" }, note = { ["70"] = "default" },
              tip = "How opaque the contour is in daylight; dimmer in the dark and while the zombie fades in or out of sight." },
        },
    },
    {
        title = "Mirrors and windows (real reflections)", clip = "hdr",
        entries = {
            { key = "mirrors", label = "Mirror and window reflections",
              tip = "Wall mirrors, mirrored medicine cabinets and dressers, and window panes reflect what stands in front of them: the floor and the room, the street, and you, the zombies and the cars as their real other side (the game's own models drawn once more through the mirror's plane, lit as they are lit), so a mirror shows your face, not your back. From the game's high camera a wall mirror shows the floor and whoever stands within a couple of squares of it; a window upstairs shows the street below. Someone inside a room seen through a window keeps the reflection over them, as glass does; a closed curtain stops it. The room part of each pane's reflection is worked out once and kept while nothing changes (the camera's pan does not change it), panes hidden under a roof or behind a building are skipped, and the people in it are redrawn at most 120 times a second: a few hundredths of a millisecond a frame for a street of windows on a fast GPU, nothing when no mirror or window is on screen. Applies on the next launch (mirror tiles are drawn on their own instead of into the chunk pictures)." },
            { key = "mirrorsWindows", label = "Mirrors: windows reflect too",
              tip = "On: window panes reflect as well (subtly, as glass does). Off: only wall mirrors. Applies on the next launch." },
            { key = "mirrorsWindowPct", label = "Mirrors: window reflection strength (%)",
              choices = { "15", "30", "50", "70" }, note = { ["30"] = "default" },
              tip = "How much of a window pane is its reflection. Real glass reflects a few percent head-on, more where the room behind it is dark; higher reads more like a shop window by day. Applies on the next launch." },
            { key = "mirrorsGeometry", label = "Mirrors: the room behind the glass",
              tip = "On: a wall mirror's room is rebuilt behind the glass from the game's own tiles, so the mirror shows what the camera can never see: the far side of the bathtub or the bed in front of it (the furniture's other facing, as the game draws it when you turn it), the floor behind it, and the wall across the room. Off: the reflection is made of what the camera sees, and where that is hidden (behind a table, a bathtub) the floor seen last stands in. Built once when a mirror comes on screen and kept; costs nothing while it stands. Applies on the next launch." },
            { key = "mirrorsCutawayHoldMs", label = "Mirrors: steady walls by a room corner (ms)",
              choices = { "0", "750", "1500" }, note = { ["750"] = "default", ["0"] = "stock" },
              tip = "The game cuts the walls round you away so you stay in sight; standing or walking by a room's corner it flips a wall between cut and shown every few tenths of a second, and a mirror on that wall blinked in and out with it. A wall with a mirror changes at most once in this time (it still goes at once the first time) and comes back only after it has not been needed for twice this, the game's own cutaway lock that its newer renderer skips. 0: the game's flipping. Applies on the next launch." },
            { key = "mirrorsModels", label = "Mirrors: people and cars in the reflection",
              tip = "On: characters and vehicles in front of a mirror or window are drawn once more through its plane, so the mirror shows their faces and the side of the car facing it. Off: the reflection is made of what the camera sees (cheaper; you see their backs). Applies on the next launch." },
            { key = "mirrorsViewLateralPct", label = "Mirrors: where you appear along the glass (%)",
              choices = { "0", "50", "100" }, note = { ["0"] = "default" },
              tip = "0: you appear straight in front of where you stand, as you see yourself in a mirror. 100: the game camera's true reflection, which puts someone a square out from the mirror a square to its side (off a small medicine cabinet). Windows always use the true reflection. Applies on the next launch." },
            { key = "mirrorsViewDropPct", label = "Mirrors: how high you appear (%)",
              choices = { "0", "25", "50", "75", "100" }, note = { ["50"] = "default" },
              tip = "How much higher someone farther from the mirror appears in it, as a share of the game camera's true reflection (100: a third of a floor per square out, a head at the sink above the medicine cabinet). 0: at their own height, eye level, below the game's high-hung cabinets. 50: your head in the cabinet from the sink, as if looking slightly down into the mirror. Applies on the next launch." },
        },
    },
    {
        title = "Wet blood (fresh blood reflects and catches the light)", clip = "hdr",
        entries = {
            { key = "bloodWet", label = "Wet blood",
              tip = "Fresh blood on the floor is a film of liquid until it dries: the rims of the pools catch the sun and the lamps, the sky and (with Reflections on) the zombies and walls standing in them are mirrored at the film's own angle, and with HDR output the highlights go above white. The film's shape comes from the splat itself (flat pools, bright edges) and it dries from the edges in. Only the wet splats on screen are drawn, once a frame, like the puddles; walls, tables and bodies in front of the floor hide it. Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default)." },
            { key = "bloodWetMinutes", label = "Wet blood: stays wet (game minutes)",
              choices = { "30", "60", "120", "240" }, note = { ["120"] = "default" },
              tip = "How long a splat stays wet, in game time." },
            { key = "bloodReflectPct", label = "Wet blood: reflection strength (%)",
              choices = { "50", "100", "150", "200" }, note = { ["100"] = "default" },
              tip = "How much of the sky and the scene the film mirrors (100: a liquid film's own amount, a few percent of the sky, more of what stands in the pool)." },
            { key = "bloodSheenPct", label = "Wet blood: sheen (%)",
              choices = { "0", "25", "45", "80" }, note = { ["45"] = "default" },
              tip = "How bright the sun and lamp highlights on the pools' rims are." },
            { key = "bloodGlintPct", label = "Wet blood: HDR glints (%)",
              choices = { "0", "50", "100", "200" }, note = { ["100"] = "default" },
              tip = "With HDR output: how bright the highlights above white are." },
        },
    },
    {
        title = "God rays (light shafts through windows, doorways, trees and fog)", clip = "hdr",
        entries = {
            { key = "godRays", label = "God rays",
              tip = "Sunlight (and moonlight) falls through windows and open doorways into rooms as shafts of light in the dust, with sunlit patches on the floor, tables and walls where it lands, cut exactly by the window frames; outdoors, in fog, rain and morning mist, the shadows of buildings and trees stretch through the haze; torches, headlights and lamps glow in the dust and fog around them. Walls, roofs, upper floors, curtains and barricades block the light; tree crowns let it through their gaps. Nothing is drawn where no light comes in: a clear day costs only the rooms with sunlit windows on screen (one draw), the haze pass runs only in fog, rain or mist. Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default)." },
            { key = "godRaysStrengthPct", label = "God rays: brightness (%)",
              choices = { "50", "75", "100", "150", "200" }, note = { ["100"] = "default" },
              tip = "How bright the shafts of light and the sunlit patches are." },
            { key = "godRaysDustPct", label = "God rays: dust in rooms (%)",
              choices = { "0", "50", "100", "200" }, note = { ["100"] = "default", ["0"] = "patches only" },
              tip = "How dusty the air in rooms is: the more dust, the brighter the shafts through the windows (0: only the sunlit patches)." },
            { key = "godRaysPatchPct", label = "God rays: sunlit patches (%)",
              choices = { "0", "50", "100", "150" }, note = { ["100"] = "default" },
              tip = "How bright the sun lands on the floor, furniture and walls where a shaft reaches them (0: the shafts only)." },
            { key = "godRaysSoftPct", label = "God rays: soft edges (%)",
              choices = { "0", "50", "100", "200" }, note = { ["100"] = "default", ["0"] = "sharp" },
              tip = "How soft the edges of the shafts and of their sunlit patches are: the light spreads a little the farther it gets from the window, so the shafts blur out instead of ending in hard lines (0: cut exactly by the window frame)." },
            { key = "godRaysGlintPct", label = "God rays: dust glints (%)",
              choices = { "0", "50", "100", "200" }, note = { ["100"] = "default", ["0"] = "off" },
              tip = "Specks of dust drifting in the shafts now and then catch the sun and glint." },
            { key = "godRaysHazePct", label = "God rays: haze outdoors (%)",
              choices = { "0", "50", "100", "200" }, note = { ["100"] = "default", ["0"] = "off" },
              tip = "How hazy the open air is: fog, rain and the mist of the hours after sunrise add to it, and the shadows of buildings and trees stretch through it as rays. A clear midday has almost none (and costs nothing)." },
            { key = "godRaysLocal", label = "God rays: torches, headlights and lamps",
              tip = "Torch beams, headlights, street lamps and fires glow in the dust of rooms and in fog and rain, their cones shaped by the light's reach and direction." },
            { key = "godRaysLocalPct", label = "God rays: torch and lamp glow (%)",
              choices = { "50", "100", "200" }, note = { ["100"] = "default" },
              tip = "How strong the glow around torches, headlights and lamps is." },
        },
    },
    {
        title = "Foliage sway (grass, bushes and trees in the wind)", clip = "hdr",
        entries = {
            { key = "foliageSway", label = "Foliage sway",
              tip = "Grass, bushes and trees bend and sway in the wind: they lean with it, gusts roll across fields and tree crowns, each plant swings at its own pace (grass quick, trees slow) and leaves flutter. The plants stay in the game's cached chunk pictures; the pass that puts those pictures on screen every frame moves each plant's pixels by the wind, so nothing extra is drawn. The game's own \"Wind sprite effects\" option (off by default) does this by drawing every plant every frame instead. Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default)." },
            { key = "foliageSwayPct", label = "Foliage sway: strength (%)",
              choices = { "50", "100", "150", "200" }, note = { ["100"] = "default" },
              tip = "How far the plants bend in the wind." },
            { key = "foliageSwayTaps", label = "Foliage sway: quality",
              choices = { "1", "2", "3", "4" }, note = { ["1"] = "default", ["2"] = "plant edges move too" },
              tip = "How the moving plants cover what is behind them: 1 moves each plant's pixels inside its own outline only (the cheapest); 2 to 4 also let the plant's edge move out over the ground behind it, looking 1 to 3 steps upwind." },
        },
    },
    {
        title = "Darkness, remembered places and colour grading", clip = "darkness",
        entries = {
            { key = "darknessFloorPct", label = "Darkness floor (% of full light)",
              choices = { "0", "10", "15", "20", "30", "40" }, note = { ["0"] = "off (the game's own darkness)", ["20"] = "a dim moonlit floor" },
              tip = "No place you have already seen gets darker than this: pitch-black rooms and nights keep a faint, cool light, so you can make out the floor, walls and furniture instead of a black void. Lamps, fires, torches and daylight are unchanged; places you have never seen stay black. Basements stay pitch black unless you tick the next box. Applied to the game's own light values, so it costs nothing per frame; the chunk pictures on screen are redrawn once when you change it." },
            { key = "darknessFloorBasements", label = "Darkness floor: also in basements",
              tip = "Basements and everything below ground get the darkness floor too. Off: below ground stays as dark as the game makes it." },
            { key = "memoryTint", label = "Remembered places",
              tip = "What you cannot see right now (behind walls, behind you) is drawn like a memory, desaturated, dimmer and cooler, with a soft edge, instead of darkened; rooms of a building you walked out of keep a dim remembered light and their furniture instead of going black. Zombies and other characters out of sight stay hidden as always. Replaces the view cone's own darkening in the same pass, so it costs nothing extra. Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default)." },
            { key = "memoryTintPct", label = "Remembered places: strength (%)",
              choices = { "40", "55", "70", "85", "100" }, note = { ["70"] = "default" },
              tip = "How strongly what you cannot see is desaturated and dimmed. 100 turns it fully grey." },
            { key = "memoryLightPct", label = "Remembered places: light of remembered rooms (% of full light)",
              choices = { "5", "10", "15", "20" }, note = { ["10"] = "default" },
              tip = "How bright rooms you have seen stay once they are out of sight (the game fades them to black)." },
            { key = "colorGrading", label = "Colour grading",
              tip = "The picture is colour graded by time of day and weather, like a film: nights shift towards the desaturated blue-green your eyes see in the dark (the Purkinje effect) while lamps and fires keep their colour, dawn is pink and cool in the shadows, the hour before dusk golden, overcast and rain greyer and cooler, storms dark and cold, fog soft and flat. Clear daylight is unchanged. The game's own screen filter and the grade become one colour lookup per pixel, which makes the screen pass cheaper than the game's own (its invisible film grain is left out); the lookup table is rebuilt in the background when the weather or the hour moves it. Custom looks: .cube files named base, night, dawn, dusk, overcast, rain, storm, fog or snow in Zomboid/pzopt/luts. Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default)." },
            { key = "colorGradingPct", label = "Colour grading: strength (%)",
              choices = { "25", "50", "75", "100", "130" }, note = { ["100"] = "default" },
              tip = "How strong the grade is. 0 would be the game's own colours." },
            { key = "colorGradingNightPct", label = "Colour grading: night vision shift (%)",
              choices = { "0", "50", "100", "150" }, note = { ["100"] = "default" },
              tip = "How much dark places at night lose their colour and turn blue-green, like human night vision. 0 keeps the night's colours." },
        },
    },
    {
        title = "Per-pixel lighting (smooth light, torch and headlight beams drawn per pixel)", clip = "torch",
        entries = {
            { key = "pixelLight", label = "Per-pixel lighting",
              tip = "The world's light is drawn per pixel instead of being painted into the chunk pictures square by square. The game's own lighting still decides how much light every square gets and what walls hide; the picture follows it smoothly between squares instead of in blocky steps, and a torch or headlight beam is drawn from its own cone, so it has straight edges and follows your aim every frame. A light change no longer redraws chunk pictures, which saves work when torches, headlights or lightning move the light. Nights look a little darker than stock: stock spreads every lit square's light half a square into its neighbours, even through walls. Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default). Applies on the next launch." },
            { key = "pplAnalytic", label = "Per-pixel lighting: torch, headlight, lamp and fire shapes",
              tip = "Torches and headlights drawn from their cone, lamps and fires from their round falloff, pixel by pixel. Off: the game's per-square light only, smoothed between squares." },
            { key = "pplPointLights", label = "Per-pixel lighting: lamp and fire shapes",
              tip = "Lamps and fires drawn from their round falloff, pixel by pixel. Off: their light is the game's per-square light, smoothed between squares, and the pictures they reach are drawn with the cheaper light-free program (the GPU cost of per-pixel lighting then only applies near torches and headlights)." },
            { key = "pplNormals", label = "Per-pixel lighting: surfaces facing the light",
              tip = "Walls and objects turned towards a torch, a headlight or a lamp catch more of its light, those turned away less (the direction of every surface is read from the game's depth picture). Floors are unchanged." },
            { key = "pplWrapPct", label = "Per-pixel lighting: how far light wraps around surfaces (%)",
              choices = { "0", "20", "35", "50", "80" }, note = { ["35"] = "default" },
              tip = "Higher softens the effect of the surface direction: the sprites already carry painted shading, so a strong effect can shade them twice." },
            { key = "pplSmooth", label = "Per-pixel lighting: smooth between tiles",
              tip = "The light changes smoothly from one tile to the next (no faint diamond pattern where its slope changes). Off: straight blends between tile centres." },
            { key = "pplWetSpecular", label = "Per-pixel lighting: wet glints in the rain",
              tip = "While the rain wets the ground outdoors (and while it dries), torches, headlights and lamps glint on it." },
            { key = "pplSpecPct", label = "Per-pixel lighting: wet glint strength (%)",
              choices = { "30", "60", "100" }, note = { ["60"] = "default" },
              tip = "How bright the glints on wet ground are." },
            { key = "pplShadows", label = "Per-pixel lighting: torch shadows (experimental)",
              tip = "Fence posts, furniture and walls cast shadows into your torch's beam (computed from the picture's depth at half resolution, one frame late). Experimental." },
            { key = "pplTorchFeetGlow", label = "Per-pixel lighting: torch glow at your feet",
              tip = "A torch in your hands also lights a small circle round your feet, behind and beside you. Off: only the beam in front of you is lit. Applies at once." },
        },
    },
    {
        title = "Light from the torch itself (flashlights, lanterns and weapon lights shine from the item you carry)", clip = "torch",
        entries = {
            { key = "torchSource", label = "Light from the torch itself",
              tip = "A flashlight, lantern, lighter or weapon light shines from the item in your hand, on your webbing or under your gun barrel, at its height, instead of from the middle of your feet: the beam starts at the lens and moves with your arm, a lantern held at your side lights that side, and with Per-pixel lighting the beam, its shadows, the glow in fog and the shadows other people cast from it all start there too. The game's own square-by-square lighting starts from the same spot (never through a wall) and holds still while you stand still. Costs a few microseconds a frame. Applies at once." },
            { key = "torchSourceAim", label = "Light from the torch itself: beam direction",
              choices = { "look", "item" }, note = { look = "default: where you look", item = "where the item points (follows your hand)" },
              tip = "Where the beam points. Where you look (the game's own) keeps the beam steady on your aim; where the item points makes it follow the flashlight in your hand as you walk and turn, and a flashlight tilted down lights the ground nearer (with Per-pixel lighting)." },
            { key = "torchSourceSelfShadow", label = "Light from the torch itself: your body casts a shadow",
              tip = "With Per-pixel lighting: your own body shades the light you carry, so a lantern in one hand leaves the other side of you in a soft shadow, and a light at your hip does not light what is behind you. A torch held out in front is never tested. Applies on the next launch." },
        },
    },
    {
        title = "Relief (parallax textures: bricks, stones, planks and shingles catch the light)", clip = "torch",
        entries = {
            { key = "relief", label = "Relief (parallax textures)",
              tip = "The fine relief the art paints (mortar between bricks and stones, gaps between planks and floor tiles, roof shingles, cobbles) catches the light that moves: a low sun or the moon rakes it and the grooves fall into shade, a torch, headlight or lamp sweeping along a wall brings the stones out. The height is read once from each chunk picture after it is drawn (two bytes per pixel of video memory); the sun and the moon are baked into the picture with it and redone a few pictures a frame when they move a step, so they cost nothing per frame; a torch, headlight or lamp reads one byte where it shines. Floors and walls only: furniture keeps its painted shading. Sun and moon: needs Sun shadows. Torches, headlights and lamps: needs Per-pixel lighting. Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default). Applies on the next launch." },
            { key = "reliefDepthPct", label = "Relief: depth (%)",
              choices = { "50", "100", "150", "200" }, note = { ["150"] = "default" },
              tip = "How deep the grooves between bricks, planks and tiles are." },
            { key = "reliefSunPct", label = "Relief: in sunlight and moonlight (%)",
              choices = { "0", "50", "100", "150" }, note = { ["100"] = "default", ["0"] = "torches and lamps only" },
              tip = "How strongly the sun and the moon bring the relief out (the part of their light that reaches the surface directly: none in shade)." },
            { key = "reliefTorchShadowSteps", label = "Relief: grooves in torch shadow",
              choices = { "0", "2", "4" }, note = { ["0"] = "default (off)", ["4"] = "costs ~0.12 ms at 5K" },
              tip = "The grooves also fall into the shadow of their own edges in torch, headlight and lamp light (each lit pixel follows the light a few texels across the relief). The sun and the moon always have it (baked)." },
        },
    },
}

-- The Enhancements tab's keys apply as soon as Apply is pressed (Java: Config's live reload, pzopt.Enhancements), except
-- the two HDR output switches: on Linux they pick the window the game is started with.
local NEXT_LAUNCH_ONLY = { hdr = true, hdrAuto = true, carOccupant = true, carOccupantOcclusion = true, carOccupantLightPct = true,
    -- per-pixel lighting: read once at start-up (the chunk composite shader is patched when the game loads it)
    pixelLight = true, pplAnalytic = true, pplPointLights = true, pplNormals = true, pplWrapPct = true, pplSmooth = true,
    pplWetSpecular = true, pplSpecPct = true, pplShadows = true, torchSourceSelfShadow = true,
    -- reflections: the water, puddle and chunk composite shaders are patched when the game loads them (only then);
    -- strength and puddles apply at once
    reflections = true,
    -- car glass: the vehicle shaders are patched when the game loads them (only then); its strengths apply at once
    carGlass = true,
    -- mirrors: mirror tiles leave the chunk pictures at start-up; the settings are read once
    mirrors = true, mirrorsWindows = true, mirrorsWindowPct = true, mirrorsModels = true, mirrorsGeometry = true, mirrorsViewLateralPct = true, mirrorsViewDropPct = true,
    -- relief: compiled into the chunk composite programs when the game loads them
    relief = true, reliefDepthPct = true, reliefSunPct = true, reliefTorchShadowSteps = true }
for _, section in ipairs(ENHANCEMENT_SECTIONS) do
    for _, entry in ipairs(section.entries) do
        entry.live = not NEXT_LAUNCH_ONLY[entry.key]
    end
end

-- The Profiler tab (2026-09-24): the performance overlay and the game-thread profiler it draws, on a page of their
-- own; same controls, preview and search as the Optimizations tab.
local PROFILER_SECTIONS = {
    {
        title = "Performance overlay (F9 or the \"Toggle performance overlay\" key binding; L3 + R3 on a controller)", clip = "spin",
        entries = {
            { key = "overlaySampling", label = "Sample frame times and utilization (needed for F9 / L3 + R3)",
              tip = "Records every presented frame, times the GPU with GL timer queries and samples the CPU load twice a second on a background thread. Off by default: without it F9 (or L3 + R3 on a controller) only shows a notice. \"Show the overlay from boot\" and \"Log every presented frame\" turn it on too." },
            { key = "overlay", label = "Show the overlay from boot",
              tip = "Frame rate, frame-time tail (p99, p99.9, max, 1%-low, jitter, spikes), GPU busy share, game and render thread load, and a frame-time graph. The key (F9) or L3 + R3 on a controller toggles it any time." },
            { key = "overlayLog", label = "Log every presented frame",
              tip = "Writes Zomboid/pzopt-overlay.out, one CSV row per frame in MangoHud's column names, for harness/analyze.py. Harness runs log regardless." },
            { key = "overlayStats", label = "Frame statistics",
              choices = { "off", "fps", "tails", "full" },
              note = { fps = "the fps line", tails = "+ p99 / p99.9 / max, 1%-low, jitter, spikes", full = "+ GPU, thread, process and machine load, heap" },
              tip = "The lines at the top of the overlay." },
            { key = "overlayPower", label = "Power draw",
              tip = "A line with the watts the machine draws while you play and the energy each frame costs (J/frame = watts / fps): CPU package, GPU board power, an AMD APU's socket, or the whole machine while a laptop runs on battery. Read from the counters the system exposes: NVIDIA GPUs through the driver (Windows and Linux), AMD GPUs and APUs, and on Linux the CPU's RAPL counters, which only root may read on most distributions (the line then says CPU n/a). While the frame log is on it also writes Zomboid/pzopt-power.out twice a second." },
            { key = "overlayTree", label = "Game-thread tree",
              choices = { "off", "0", "3", "5", "8" },
              note = { ["0"] = "phases only", ["3"] = "3 sub-phases per phase", ["5"] = "5 sub-phases per phase", ["8"] = "8 sub-phases per phase" },
              tip = "What the game thread is doing, from its call stack sampled on a background thread: the phases (update / render / lighting) with their share of the time, under each the biggest sub-phases (chunk bakes, zombies, UI draw, frame hand-off...) with a bar, the wait share in red and the hottest methods. Also logged per second to Zomboid/pzopt-gamethread.out for harness/analyze.py." },
            { key = "overlayVerdict", label = "Verdict line",
              choices = { "off", "short", "detailed" },
              note = { short = "\"at the cap\" / \"GPU bound\" / \"nothing saturated\"", detailed = "+ the two biggest game-thread sub-phases when it is the game thread" },
              tip = "What is holding the frame rate below the cap." },
            { key = "overlayGraph", label = "Frame-time graph",
              choices = { "off", "240", "480", "960" },
              note = { ["240"] = "last 240 frames (480 px)", ["480"] = "last 480 frames (960 px)", ["960"] = "last 960 frames (1920 px)" },
              tip = "A bar per presented frame (green under 1.1x the cap budget, amber under 2x, red above; GPU time in blue) with ms ticks and the budget line." },
            { key = "overlayGraphHz", label = "Frame-time graph redraws per second",
              choices = { "0", "15", "30", "60" },
              note = { ["0"] = "every frame (one sprite per bar)", ["15"] = "15 times a second", ["30"] = "30 times a second", ["60"] = "60 times a second" },
              tip = "With \"Draw the overlay as one texture\" on, the frame-time bars are redrawn into that texture this often instead of drawn as ~480 sprites every frame. 0 draws them every frame." },
            { key = "overlayFlame", label = "Game-thread flame graph",
              choices = { "off", "right", "right-wide", "below" },
              note = { right = "column beside the statistics, 900 px", ["right-wide"] = "column beside the statistics, 1400 px", below = "under the frame graph, panel width" },
              tip = "The last 5 s of stack samples as a flame graph: root (GameWindow.frameStep) at the bottom, callees above, width = share of the time, biggest first from the left; update green, render blue, lighting amber, pzopt frames magenta. Off by default: with \"Draw the overlay as one texture\" off it is the heaviest element (one sprite per box and label every frame). harness/flamegraph.py draws a whole run as an SVG." },
            { key = "overlayFlameDepth", label = "Game-thread flame graph rows",
              choices = { "12", "16", "24", "32", "48" },
              tip = "How many call levels above GameWindow.frameStep the flame graph shows." },
            { key = "gameThreadProfileHz", label = "Game-thread stack samples per second",
              choices = { "10", "25", "50", "100", "200", "500" },
              tip = "Higher resolves short phases sooner; each sample briefly stops the game thread (tens of microseconds). 25 (the default) gives 125 samples over the overlay's 5 s window; each sample costs the game thread about 0.15 ms on a MacBook, so 100 a second takes 1.5 % of its time. Sampling only runs while the overlay is shown or its log is on, and only when the tree, the flame graph, the detailed verdict or the log needs it." },
            { key = "overlayTexture", label = "Draw the overlay as one texture",
              tip = "The overlay's text, tree and flame graph only change four times a second, so they are drawn into an offscreen texture then and each frame shows that texture plus the live frame-time bars. Off draws every letter and box as its own sprite every frame (thousands of quads), which costs frame rate on slower PCs." },
            { key = "overlayRefreshMs", label = "Overlay refresh interval (ms)",
              choices = { "100", "250", "500", "1000" },
              tip = "How often the overlay's numbers, game-thread tree and verdict are recomputed and its texture redrawn. Longer is cheaper; the frame-time graph has its own rate." },
            { key = "overlayCorner", label = "Overlay corner",
              choices = { "tl", "tr", "bl", "br" },
              tip = "Where the overlay sits: top-left, top-right, bottom-left, bottom-right." },
            { key = "overlayFont", label = "Overlay font",
              choices = { "auto", "CodeMedium", "CodeSmall", "CodeLarge", "Small", "Medium", "Large" },
              tip = "The UI font the overlay text uses. auto follows the screen height: CodeSmall under 1000 px, CodeMedium under 1800, CodeLarge above. Whatever the font, the panel fits the screen: the frame graph shows fewer frames, the flame graph keeps up to a third of the width (hints and legend are cut to the rest) or moves under the frame graph and long lines are cut when it would not." },
        },
    },
    {
        title = "Performance overlay: fps colour", clip = "spin",
        entries = {
            { key = "overlayFpsColor", label = "Colour the fps number",
              tip = "The fps number takes one of four colours by how close it is to the target; off = white like the rest of the line." },
            { key = "overlayFpsFollowCap", label = "Colour thresholds follow the framerate cap",
              tip = "On: with a framerate cap the thresholds are percentages of it (the three \"% of the cap\" values). Off, or uncapped: the three fixed fps thresholds apply." },
            { key = "overlayFpsCapBluePct", label = "Threshold, capped: blue, at the cap (% of the cap)",
              choices = { "100", "99", "98", "95", "90" },
              tip = "At or above this share of the cap counts as at the cap. The limiter rarely lands exactly on it, so 100 is stricter than it looks." },
            { key = "overlayFpsCapGreenPct", label = "Threshold, capped: green, at or above (% of the cap)",
              choices = { "95", "90", "85", "80", "75" },
              tip = "Green from this share of the cap up to the blue threshold." },
            { key = "overlayFpsCapYellowPct", label = "Threshold, capped: yellow, at or above (% of the cap)",
              choices = { "75", "66", "50", "33", "25" },
              tip = "Yellow from this share of the cap up to the green threshold; red below it." },
            { key = "overlayFpsBlueAbove", label = "Threshold, uncapped: blue, above (fps)",
              choices = { "500", "400", "300", "240", "200", "165", "144", "120", "60" },
              tip = "Uncapped, or with follow-cap off: blue above this many fps." },
            { key = "overlayFpsGreenAbove", label = "Threshold, uncapped: green, at or above (fps)",
              choices = { "300", "240", "200", "150", "120", "100", "60", "45" },
              tip = "Uncapped, or with follow-cap off: green from this many fps up to the blue threshold." },
            { key = "overlayFpsYellowAbove", label = "Threshold, uncapped: yellow, at or above (fps)",
              choices = { "200", "150", "120", "100", "75", "60", "45", "30" },
              tip = "Uncapped, or with follow-cap off: yellow from this many fps up to the green threshold; red below it." },
            { key = "overlayFpsColorBlue", label = "Tier colour 1: \"at the cap\"",
              choices = FPS_COLOURS,
              tip = "Named colour, or a RRGGBB hex value typed into Zomboid/pzopt/options.ini." },
            { key = "overlayFpsColorGreen", label = "Tier colour 2: \"near the cap\"",
              choices = FPS_COLOURS,
              tip = "Named colour, or a RRGGBB hex value typed into Zomboid/pzopt/options.ini." },
            { key = "overlayFpsColorYellow", label = "Tier colour 3: \"well below\"",
              choices = FPS_COLOURS,
              tip = "Named colour, or a RRGGBB hex value typed into Zomboid/pzopt/options.ini." },
            { key = "overlayFpsColorRed", label = "Tier colour 4: \"far below\"",
              choices = FPS_COLOURS,
              tip = "Named colour, or a RRGGBB hex value typed into Zomboid/pzopt/options.ini." },
        },
    },
    {
        title = "Console log", clip = "spin",
        entries = {
            { key = "consoleLog", label = "PZ Optimization lines in the console",
              choices = { "all", "warnings", "errors", "off" },
              note = { all = "everything (startup, settings, periodic statistics)", warnings = "only warnings and errors", errors = "only errors", off = "nothing" },
              tip = "Which [pzopt] lines go to console.txt and the debug-mode console. Turn it down to read another mod's output without PZ Optimization's startup notes and statistics in between. Applies at once; the game's own lines are not affected. When reporting a problem with PZ Optimization, set it back to all first." },
        },
    },
}
-- Every Profiler-tab key applies while the game runs (Java: Config.isLive): no restart dialog, "now" in the preview.
for _, section in ipairs(PROFILER_SECTIONS) do
    for _, entry in ipairs(section.entries) do
        entry.live = true
    end
end

-- The "Sort by" combo shows the sections in this source order ("natural"), alphabetically (sections by title, settings
-- by label) or by their effect on one resource. Settings that only make sense next to another one (a setting and its
-- sub-settings, the fps colour tiers) carry labels that sort into the same order both ways, and a tip names another
-- setting by its label rather than saying "above" / "below".
local function alphaLess(a, b)
    return string.lower(a) < string.lower(b)
end

local function perf()
    return getPerformance()
end

-- The tab label and section title of a key (nil when the tab has no row for it), for the main menu's mod compatibility
-- check (pzopt_mainscreen_compat.lua).
function PzoptOptionLabel(key)
    for _, list in ipairs({ SECTIONS, ENHANCEMENT_SECTIONS, PROFILER_SECTIONS }) do
        for _, section in ipairs(list) do
            for _, entry in ipairs(section.entries or {}) do
                if entry.key == key and type(entry.label) == "string" then
                    return entry.label, section.title
                end
            end
        end
    end
    return nil
end

-- The value the next launch will read: pinned > saved > default.
-- "" or why mod compatibility switched the key off at this launch (pzopt.ModCompat: a Java mod patches its method)
local function compatReason(key)
    return perf():getPzoptModCompatReason(key)
end

-- Mod compatibility decides the key: it switched it off and the player has no saved choice. The control shows the value in
-- force and Apply stores nothing until the player changes it (then the choice is saved even when it is the default).
local function compatDecides(key)
    return compatReason(key) ~= "" and perf():getPzoptOptionSaved(key) == ""
end

local function nextValue(entry)
    local p = perf()
    if p:getPzoptOptionPinnedBy(entry.key) ~= "" then
        return p:getPzoptOption(entry.key)
    end
    local saved = p:getPzoptOptionSaved(entry.key)
    if saved ~= "" then
        return saved
    end
    if compatDecides(entry.key) then
        return p:getPzoptOption(entry.key)
    end
    return p:getPzoptOptionDefault(entry.key)
end

local function store(entry, value)
    if value == perf():getPzoptOptionDefault(entry.key) then
        perf():setPzoptOption(entry.key, "")
    else
        perf():setPzoptOption(entry.key, value)
    end
end

local function noteFor(entry)
    return entry.live and LIVE_NOTE or RESTART_NOTE
end

-- After a save: a live key is already in force (UserOptions.set re-read it), any other asks for a restart.
local function afterStore(option, entry, value)
    if not entry.live then
        option:restartRequired(perf():getPzoptOption(entry.key), value)
    end
end

-- A tab master switch's start-up features (entry.restartKeys: they pick the window or patch shaders when the game
-- starts) as the next launch will read them, so switching the master asks for a restart only when one of them moves.
local function startupSignature(master)
    local p = perf()
    local off = nextValue(master) == "false"
    local t = {}
    for _, key in ipairs(master.restartKeys) do
        local v = nextValue({ key = key })
        if off and p:getPzoptOptionPinnedBy(key) == "" then v = "false" end
        table.insert(t, key .. "=" .. v)
    end
    return table.concat(t, ",")
end

local function tooltipFor(entry, pinnedBy)
    local t = entry.tip .. " " .. noteFor(entry) .. " Key: " .. entry.key .. "."
    if pinnedBy ~= "" then
        t = t .. " Pinned by " .. pinnedBy .. " for this install; the menu cannot change it."
    end
    local reason = compatReason(entry.key)
    if reason ~= "" then
        t = t .. " Off at this launch for mod compatibility: " .. reason .. ". Choosing a value here overrides that."
    end
    return t
end

-- The Java classes that read a key: PzoptOptionClasses from pzopt_optimizations_classes.lua, generated by
-- scripts/option-classes.py at build time (looked up late: the client files load in name order, after this one
-- is parsed but before the options screen is built).
local function optionClasses(key)
    local t = PzoptOptionClasses
    return (t and t[key]) or {}
end

-- The date of the first release holding a key ("YYYY-MM-DD"): PzoptOptionDates from pzopt_optimizations_dates.lua,
-- generated by scripts/option-dates.py at build time from the release tags (looked up late, like the classes).
-- nil = the key is new in this version.
local function optionDate(key)
    local t = PzoptOptionDates
    return t and t[key]
end

-- ---------------------------------------------------------------------------------------------------
-- Preview panel: the two clips, the description and the effect bars of the setting under the mouse.

-- Clips under media/ui/pzopt/compare/<clip>-stock.gif / -opt.gif (harness/menu-gifs.py, harness/menu-gifs.json
-- names the runs). A section's `clip` is the default for its keys; KEY_CLIP picks another for one key.
local CLIP_TITLES = {
    drive = "120 km/h highway drive, clear day, max zoom",
    spin = "Rosewood, camera spinning through town, max zoom",
    fog = "120 km/h drive in heavy fog, max zoom",
    storm = "120 km/h drive in a thunderstorm, max zoom",
    horde = "Downtown Louisville, zombie population maxed",
    torch = "Night, hand torch, turning in place",
    nightdrive = "Night drive with headlights",
    load = "Launch to the main menu, then Continue to the world (real time)",
    -- the performance overlay's elements: the spinning route with the overlay off / with every default element on
    -- (overlayFont=Large, each clip a crop of the same capture; the overlay shows its own numbers, no burned counter)
    overlay = "The whole overlay with every element on: statistics, game-thread tree, verdict, frame graph, flame graph",
    ovstats = "The statistics lines: fps, frame time, p99 / p99.9 / max, 1 %-low, jitter, spikes, GPU and thread loads",
    ovtree = "The game-thread tree: phases, their sub-phases and hot methods, biggest first, waits in red",
    ovverdict = "The verdict line: what holds the frame rate below the cap",
    ovgraph = "The frame-time graph: one bar per presented frame, GPU time in blue, the budget line and ms ticks",
    ovflame = "The flame graph: the last 5 s of game-thread stacks, root at the bottom, biggest first from the left",
    -- the Louisville horde, one group of keys at a time (overlay + profiler on both sides)
    zombies = "Downtown Louisville horde: stock vs stock + only the zombie simulation settings (all cores, lookups, push-apart)",
    player = "Downtown Louisville horde: stock vs stock + only the player line-of-sight settings",
    zgt = "Downtown Louisville horde: every optimization on, without vs with the zombie game-thread settings (on their own over stock they gain nothing: the stock frame waits on other work)",
    grid = "Rosewood, camera spinning, uncapped, every optimization on: the vanilla chunk grid (19x19) vs 15x15",
    -- the Enhancements tab: every optimization on in both runs, only the enhancement differs (2026-09-25, runs enh-*)
    upscale = "Rosewood, camera spinning, uncapped, max zoom, RTX 4090 at 5120x2160: the world at the screen size vs rendered at 67 % and upscaled with FSR 1.0",
    fsrzoom = "A furnished Rosewood house at noon, zoom 1, still camera, a 1:1 pixel crop around the player: FSR 1.0 at quality (67 %, sharpening 80) against the native picture",
    dlss = "Rosewood, camera spinning, uncapped, max zoom, RTX 4090 at 5120x2160: the world at the screen size vs DLSS at the defaults (preset E, 67 % output, RCAS finish)",
    dlsszoom = "A furnished Rosewood house at noon, zoom 1, still camera, a 1:1 pixel crop around the player: DLSS at the defaults against the native picture",
    hdr = "Night, fires beside a police car with its light bar: the HDR side is tone-mapped to fit this SDR preview, so its lights look brighter, not as bright as on an HDR screen",
    hdrday = "River shore at 15:00, clear sky, a crop of the pier and the water: the sun glitter on the water, tone-mapped to fit this SDR preview",
    ao = "Rosewood houses at noon, walking south at zoom 1: a crop around the player, ambient occlusion off vs on",
    -- darkness floor, remembered places, colour grading (2026-09-26, runs cap-*: the game's own frames, the player turning in place)
    darkness = "Rosewood house at 01:00, the player turning in place: darkness floor 20 %, remembered places and colour grading together",
    memory = "Rosewood house at 13:00, the player turning in place: what is out of sight is drawn grey, dimmer and cooler",
    grade = "Rosewood house in the rain at 14:00, the player turning in place: colour grading (cooler, greyer, softer in rain)",
}
-- Clips whose stock side is a shared GIF (one stock run for several group clips): <STOCK_FILE[clip]>-stock.gif.
local STOCK_FILE = { zombies = "lou", player = "lou", upscale = "native", dlss = "native", fsrzoom = "nativezoom", dlsszoom = "nativezoom" }
-- The captions over the two clips; the overlay clips are "off" / "on" rather than stock / optimized.
local CLIP_SIDES = {
    default = { "STOCK GAME", "OPTIMIZED (every optimization on)" },
    overlay = { "OVERLAY OFF", "OVERLAY ON (F9 / L3 + R3)" },
    alone = { "STOCK GAME", "STOCK + THESE SETTINGS ONLY" },
    without = { "EVERYTHING ON EXCEPT THESE", "EVERYTHING ON" },
    grid = { "VANILLA GRID (19x19)", "15x15 GRID" },
    upscale = { "NO UPSCALER (NATIVE)", "FSR 1.0, QUALITY" },
    fsrzoom = { "NATIVE, 1:1 PIXELS", "FSR 1.0 QUALITY, 1:1 PIXELS" },
    dlss = { "NO UPSCALER (NATIVE)", "NVIDIA DLSS (DEFAULTS)" },
    dlsszoom = { "NATIVE, 1:1 PIXELS", "DLSS, 1:1 PIXELS" },
    hdr = { "SDR (HDR OFF)", "HDR ON" },
    hdrday = { "SDR (HDR OFF)", "HDR ON" },
    ao = { "AMBIENT OCCLUSION OFF", "AMBIENT OCCLUSION ON" },
    darkness = { "STOCK DARKNESS", "FLOOR + REMEMBERED + GRADING" },
    memory = { "REMEMBERED PLACES OFF", "REMEMBERED PLACES ON" },
    grade = { "COLOUR GRADING OFF", "COLOUR GRADING ON" },
}
-- The line under the clips: what is the same in both, and what the burned-in number is (clips without one say so).
local CLIP_NOTES = {
    fsrzoom = ". Same save, spot and machine; the picture is the point, no counter.",
    dlsszoom = ". Same save, spot and machine; the picture is the point, no counter.",
    hdr = ". Same save, spot and machine; no counter.",
    hdrday = ". Same save, spot and machine; no counter.",
    ao = ". Same save, route and machine; no counter.",
    darkness = ". Same save, spot and hour, frames lined up; no counter.",
    memory = ". Same save, spot and hour, frames lined up; no counter.",
    grade = ". Same save, spot and hour, frames lined up; no counter.",
}
local function clipSides(clip)
    if CLIP_SIDES[clip] then return CLIP_SIDES[clip] end
    if string.sub(clip, 1, 2) == "ov" then return CLIP_SIDES.overlay end
    if STOCK_FILE[clip] then return CLIP_SIDES.alone end
    if clip == "zgt" then return CLIP_SIDES.without end
    return CLIP_SIDES.default
end
local KEY_CLIP = {
    memoryTint = "memory", memoryTintPct = "memory", memoryLightPct = "memory", colorGrading = "grade", colorGradingPct = "grade",
    rainTiles = "storm", puddleCache = "storm", rainSplashesFast = "storm", puddleEarlyZ = "storm", puddleVbo = "storm",
    puddleCacheFrames = "storm", weatherMaskIdleSkip = "storm", weatherFxScalePct = "storm", vboBatchKb = "storm",
    vboFastQuads = "storm", lightingRebakeBudget = "storm", lightingRebakeMaxFrames = "storm",
    fogPass = "fog", fogScalePct = "fog", fogMaskFrames = "fog",
    fsrSharpnessPct = "fsrzoom", dlssWaterCurrent = "dlss", dlssWaterHistoryPct = "dlss", dlssPreset = "dlss", dlssOutputPct = "dlss", dlssOutputFilter = "dlsszoom", dlssSharpen = "dlsszoom",
    hdrSunPct = "hdrday", hdrGlintPct = "hdrday",
    pixelLight = "torch", pplAnalytic = "torch", pplNormals = "torch", pplWrapPct = "torch", pplShadows = "torch", pplTorchFeetGlow = "torch", torchSource = "torch", torchSourceAim = "torch", torchSourceSelfShadow = "torch", pplSmooth = "torch", pplPointLights = "torch", pplWetSpecular = "storm", pplSpecPct = "storm",
    lightingStrongDelta = "torch", lightingStrongBudget = "horde", lightingStrongFrameMs = "horde", lightingFlush = "torch", lightingBudget = "torch",
    audioLimiter = "horde", audioLimiterCeilingDb = "horde", audioLimiterStereoFold = "horde", soundTickHz = "horde", emitterIdleSkip = "horde", worldSoundCleanupFast = "horde", hearingHoist = "horde",
    lightSwitchCheckFrames = "horde", soundZoneCache = "horde", worldSoundFast = "horde", gridStackInterval = "horde",
    playerLosFast = "player", zombieSpotFast = "player", charDrawPrep = "horde", zombieAtlasFast = "horde", charDrawThreads = "horde",
    actionSnapshotFilter = "zgt", emitterParamSkip = "zgt", separateFast = "zgt", separateParallel = "zgt", sleepCheckMemo = "zgt",
    renderPrepParallel = "horde", pplPackParallel = "torch", pplTorchNearChunk = "torch", schedulerClassifyParallel = "zgt", zombieStatsFold = "zgt", losLightPrefetch = "player", entityUpdateSafeStates = "horde", ragdollCorpseGuard = "horde", ragdollQuitSweep = "horde", visPolyAsync = "player", aoContextParallel = "horde", translucentOrderCache = "drive",
    stateParamMemo = "zgt", actionGroupCache = "zgt", profilerThreadMemo = "zgt", zombieSimLodTiles = "zgt", zombieSimLodSteps = "zgt",
    zombieCheckSpread = "zgt", chunkGridWidth = "grid", chunkGridFollowView = "grid",
    zombieLodDynamic = "horde", zombieLodMin3d = "horde", zombieLodMinBlend = "horde", zombieLodUncappedFps = "horde",
    animBonesParallel = "zombies", vehicleCull = "zombies", frameThreads = "zombies", actionEvalParallel = "zombies", ecsLookupFast = "zombies",
    actionConditionFast = "zombies", skinTransformsPrecompute = "zombies", skinPalettePrecompute = "zombies", shadowPrep = "zombies",
    boneIndexCache = "zombies", lightingReadParallel = "zombies", zombieCullSortFast = "zombies",
    animatorParallel = "zombies", headOnWorker = "zombies", lazyPose = "zombies", animatorPipeline = "zombies", animBatchAsync = "zombies", guardedCallbacks = "zombies",
    modelLockPerInstance = "zombies", poolStatsBatched = "zombies",
    bakeBudget = "drive", rebakeBudget = "drive", rebakeMaxFrames = "drive", treeBakeMaxChunksPerSec = "drive", driveTreeCutaway = "drive", edgeTestFast = "horde", treeRebakeLazy = "drive", treeRebakeLingerMs = "drive", treeCutawayReach = "drive", treeCutawayReachPx = "drive",
    bakeScheduler = "drive", bakeFrameBudget = "drive", bakeBudgetAdaptive = "drive", occlusionGrantedOnly = "drive", bakeMipLevels = "drive",
    renderChunkTopUp = "drive", fliesToggleFix = "drive",
    translucentLightsPerFrame = "spin", glassTilesPerFrame = "spin", floorDecalsPerFrame = "spin", curtainDepthNudgePct = "spin", treeBakePass = "spin", windSpriteSway = "drive", treeBakeDirect = "spin", roofHideDebounceFrames = "spin",
    overlaySampling = "overlay", overlay = "overlay", overlayLog = "overlay", overlayCorner = "overlay", overlayFont = "overlay", overlayTexture = "overlay", overlayRefreshMs = "overlay", overlayGraphHz = "ovgraph",
    gameThreadProfileHz = "ovtree", overlayStats = "ovstats", overlayPower = "ovstats", overlayTree = "ovtree", overlayVerdict = "ovverdict",
    overlayGraph = "ovgraph", overlayFlame = "ovflame", overlayFlameDepth = "ovflame",
    overlayFpsColor = "ovstats", overlayFpsFollowCap = "ovstats", overlayFpsCapBluePct = "ovstats", overlayFpsCapGreenPct = "ovstats",
    overlayFpsCapYellowPct = "ovstats", overlayFpsBlueAbove = "ovstats", overlayFpsGreenAbove = "ovstats", overlayFpsYellowAbove = "ovstats",
    overlayFpsColorBlue = "ovstats", overlayFpsColorGreen = "ovstats", overlayFpsColorYellow = "ovstats", overlayFpsColorRed = "ovstats",
}

-- One bar per resource; `moreIsWork` colours the right side blue instead of amber: putting idle cores to work is
-- the point, not a cost.
local AXES = {
    { id = "cpu", label = "CPU: game thread",
      tip = "The thread that simulates the world and prepares every frame; it is what limits the frame rate most of the time." },
    { id = "render", label = "CPU: render thread",
      tip = "The thread that feeds the GPU." },
    { id = "cores", label = "CPU: other cores", moreIsWork = true,
      tip = "Worker threads: chunk loading, boot and file decoding, sampling." },
    { id = "gpu", label = "GPU" },
    { id = "vram", label = "VRAM" },
    { id = "ram", label = "RAM" },
    { id = "disk", label = "Disk / caches" },
    { id = "load", label = "Boot and load time" },
    { id = "chunks", label = "Chunk arrival" },
}
-- The x axis: the load on that part with the setting, the stock game being "mid". -3 .. 3 -> the word beside the
-- bar; the same words are the axis ticks (-1 shares "low": its bar is a third of the way, the word cannot be finer).
local LEVELS = { [-3] = "lowest", [-2] = "low", [-1] = "low", [0] = "mid", [1] = "high", [2] = "ultra", [3] = "max" }
local AXIS_TICKS = { -3, -2, 0, 1, 2, 3 }
local AXIS_TITLE = "load on that part with this setting  (stock game = mid)"

-- How each key changes the load on the parts above against the stock game, -3 .. 3 (0 / absent = no measurable
-- change): -1 a few percent, -2 clearly measurable, -3 the big wins (docs/archive/2026-09-24/results.md, docs/archive/2026-09-24/findings-*.md). A combo's
-- bars describe moving it away from stock in the direction the tab offers.
local EFFECTS = {
    corePlacement = { cores = -1 },
    gpuPstate = { gpu = -1 },
    jitSteady = { cores = -2 },
    gcHeap = { cpu = -1, ram = 1 },
    gcHeapFixed = { ram = 1 },
    luaGcNoop = { cpu = -1 },
    gcPreTouch = { ram = 1 },
    gcMode = { cpu = -1 },
    gcPauseMs = {},
    lightingSyncPark = { cores = -1 },
    visBlurReduce = { gpu = -1 },
    enabled = { cpu = -3, render = -3, gpu = -1, cores = 2, ram = 1, disk = 1, load = -3, chunks = -2 },
    -- chunk textures
    treesInChunkTexture = { cpu = -3, render = -2, gpu = -1 },
    windSpriteSway = { cpu = -3, render = -3, gpu = 1 },
    treeBakeMaxChunksPerSec = { cpu = -1 },
    treeBakeDirect = {},
    treeBakePass = { cpu = 1 },
    treeAppend = { cpu = -2, gpu = -1 },
    driveTreeCutaway = { cpu = 1, gpu = 2 },
    edgeTestFast = { cpu = -1 },
    treeRebakeLazy = { cpu = -1, gpu = -2 },
    treeRebakeLingerMs = {},
    treeCutawayReach = { cpu = -1, gpu = -2 },
    treeCutawayReachPx = {},
    bloodBake = { cpu = -2, render = -2, gpu = -1 },
    bloodAppend = { cpu = -2, gpu = -1 },
    bloodSettleSec = { cpu = 1 },
    bloodFadeFix = { cpu = -1, gpu = -1 },
    bloodAppendVegetation = { cpu = -1 },
    bloodAppendPlants = { cpu = -2, gpu = -1 },
    bloodRebakeCoalesceMs = { cpu = -1, gpu = -1 },
    windowsInChunkTexture = { cpu = -2, render = -1, gpu = -1 },
    translucentTilesInChunkTexture = { cpu = -3, render = -2, gpu = -1 },
    translucentLightsPerFrame = {},
    glassTilesPerFrame = {},
    floorDecalsPerFrame = {},
    curtainDepthNudgePct = {},
    bakeBudget = { cpu = -2 },
    bakeScheduler = { cpu = -1, render = -1, gpu = -1 },
    bakeFrameBudget = { cpu = -1 },
    bakeBudgetAdaptive = { cpu = -1, gpu = -1 },
    occlusionGrantedOnly = { cpu = -1 },
    bakeMipLevels = { gpu = -1 },
    renderChunkTopUp = { render = -1, vram = 1 },
    fliesToggleFix = { cpu = -1, gpu = -1 },
    rebakeBudget = { cpu = -1 },
    rebakeMaxFrames = {},
    lightingRebakeBudget = { cpu = -2, gpu = -1 },
    lightingRebakeMaxFrames = { cpu = -1 },
    lightingRebakeMs = { cpu = -1 },
    zoomRetain = { cpu = -2, gpu = -1, vram = 1 },
    zoomRebakeBudget = { cpu = -1 },
    zoomPlaceholder = { vram = 1 },
    zoomFrameMs = {},
    zoomEaseMs = {},
    zoomEase = {},
    lightingStrongDelta = { cpu = 1 },
    lightingStrongBudget = { cpu = -1, gpu = -2 },
    lightingStrongFrameMs = { gpu = -1 },
    entityUpdateParallel = { cpu = -2, cores = 1 },
    renderPrepParallel = { cpu = -1, cores = 1 },
    pplPackParallel = { cpu = -1, cores = 1 },
    pplTorchNearChunk = { cpu = -1 },
    schedulerClassifyParallel = { cpu = -1 },
    zombieStatsFold = { cpu = -1 },
    visPolyAsync = { cpu = -1, cores = 1 },
    aoContextParallel = { cpu = -1, cores = 1 },
    translucentOrderCache = { cpu = -1 },
    entityUpdatePipeline = { cores = 1 },
    emitterDefer = {},
    physicsDefer = {},
    animalLosFast = { cpu = -1 },
    animBonesParallel = { cpu = -1, cores = 1 },
    animBonesThreads = { cores = 1 },
    frameThreads = { cores = 1 },
    actionEvalParallel = { cpu = -2, cores = 1 },
    animatorParallel = { cpu = -2, cores = 1 },
    headOnWorker = { cpu = -1, cores = 1 },
    lazyPose = { cpu = -1 },
    animatorPipeline = { cpu = -1, cores = 1 },
    animBatchAsync = { cpu = -1, cores = 1 },
    guardedCallbacks = { cpu = -1 },
    modelLockPerInstance = { cores = 1 },
    poolStatsBatched = { cores = 1 },
    ecsLookupFast = { cpu = -1 },
    actionConditionFast = { cpu = -1 },
    skinTransformsPrecompute = { cpu = -1, cores = 1 },
    skinPalettePrecompute = { cpu = -1 },
    shadowPrep = { cpu = -1 },
    boneIndexCache = { cpu = -1 },
    lightingReadParallel = { cpu = -2, cores = 1 },
    zombieCullSortFast = { cpu = -1 },
    vehicleCull = { cpu = -1 },
    playerLosFast = { cpu = -2 },
    zombieSpotFast = { cpu = -1 },
    charDrawPrep = { cpu = -2, cores = 1 },
    zombieAtlasFast = { cpu = -1 },
    actionSnapshotFilter = { cpu = -2 },
    emitterParamSkip = { cpu = -1 },
    emitterIdleSkip = { cpu = -1 },
    worldSoundCleanupFast = { cpu = -1 },
    hearingHoist = { cpu = -1 },
    soundTickHz = { cpu = -1 },
    audioLimiter = {},
    audioLimiterCeilingDb = {},
    audioLimiterStereoFold = {},
    separateFast = { cpu = -1 },
    separateParallel = { cpu = -2, cores = 1 },
    sleepCheckMemo = { cpu = -1 },
    stateParamMemo = { cpu = -1, ram = -1 },
    actionGroupCache = { cpu = -1, ram = -1 },
    profilerThreadMemo = { cpu = -1 },
    zombieSimLodTiles = { cpu = -3 },
    zombieSimLodSteps = { cpu = -1 },
    zombieCheckSpread = { cpu = -1 },
    zombieLodDynamic = { cpu = -2, gpu = -2 },
    zombieLodMin3d = {},
    zombieLodMinBlend = {},
    zombieLodUncappedFps = {},
    charDrawThreads = { cores = 1 },
    lightingGlobalDeltaPct = { cpu = -1 },
    lightingFlush = { cpu = 1 },
    lightingBudget = { cpu = -2 },
    -- cutaways, lighting, weather
    cutawayFast = { cpu = -2 },
    cutawayRadius = { cpu = -2 },
    gridStackInterval = { cpu = -1 },
    lightSwitchCheckFrames = { cpu = -1 },
    rainTiles = { cpu = -3, render = -3, gpu = -1 },
    puddleCache = { cpu = -3, ram = 1 },
    rainSplashesFast = { cpu = -1 },
    puddleEarlyZ = { gpu = -2 },
    puddleVbo = { render = -3, gpu = -1, vram = 1 },
    puddleCacheFrames = { cpu = -1 },
    weatherMaskIdleSkip = { cpu = -1, gpu = -1 },
    weatherFxScalePct = { gpu = -2, render = -1, vram = -1 },
    fogPass = { gpu = -3, render = -2, cpu = -1, vram = 1 },
    ambientOcclusion = { gpu = 1, vram = 1 },
    sunShadows = { gpu = 1, vram = 1 },
    sunShadowRate = { render = 1, gpu = 1 },
    cloudShadows = { gpu = 1 },
    reflections = { gpu = 1, vram = 1 },
    carGlass = { gpu = 1 },
    mirrors = { gpu = 1, vram = 1, render = 1 },
    mirrorsWindows = { gpu = 1 },
    mirrorsWindowPct = {},
    mirrorsViewLateralPct = {},
    mirrorsViewDropPct = {},
    mirrorsModels = { gpu = 1, render = 1 },
    mirrorsGeometry = { vram = 1 },
    carGlassReflectPct = {},
    carGlassInteriorPct = {},
    carGlassSunPct = {},
    carGlassRainPct = {},
    carOccupant = { render = 1 },
    carOccupantOcclusion = {},
    carOccupantLightPct = {},
    occludedZombieOutlines = {},
    occludedOutlineIgnorePlants = {},
    occludedOutlineWidth = {},
    occludedOutlineColour = {},
    occludedOutlineOpacityPct = {},
    bloodWet = { gpu = 1, cpu = 1 },
    bloodWetMinutes = { gpu = 1 },
    bloodReflectPct = {},
    bloodSheenPct = {},
    bloodGlintPct = {},
    godRays = { gpu = 1, vram = 1 },
    godRaysLocal = { gpu = 1 },
    foliageSway = { gpu = 1, vram = 1 },
    relief = { gpu = 1, vram = 2 },
    reliefTorchShadowSteps = { gpu = 2 },
    darknessFloorPct = {},
    memoryTint = {},
    colorGrading = { gpu = -1 },
    spriteFilter = { gpu = 1 },
    spriteFilterMin = { gpu = 1 },
    pixelLight = { cpu = -1, gpu = 1, vram = 1 },
    pplPointLights = { gpu = 1 },
    pplShadows = { gpu = 2 },
    pplTorchFeetGlow = {},
    torchSource = {},
    torchSourceAim = {},
    torchSourceSelfShadow = {},
    aoScalePct = { gpu = 1, vram = 1 },
    vrr = { gpu = -1, cpu = -1 },
    vrrCap = { gpu = -1, cpu = -1 },
    presentPacing = { render = 1 },
    vehicleSmooth = {},
    driveLookSmooth = {},
    cameraScreenPixels = {},
    frameClockSmooth = {},
    driveCameraLate = {},
    physicsStepHz = { cpu = 1 },
    physicsStepMode = { cpu = 1 },
    borderlessFullscreen = { gpu = 0 },
    macPresent = { gpu = 1, render = 1 },
    limiterSleep = { cpu = -1 },
    upscaler = { gpu = -3, render = -1, vram = 1 },
    upscalerQuality = { gpu = -2 },
    upscalerScalePct = { gpu = -2 },
    fsrSharpnessPct = { gpu = 0 },
    dynRes = { gpu = -2, render = 0 },
    dynResUpscaler = { gpu = 0 },
    dynResTargetPct = { gpu = -1 },
    dynResMinPct = { gpu = -1 },
    dynResMaxPct = { gpu = -1 },
    dynResFps = { gpu = -1 },
    dynResController = { gpu = 0 },
    upscalerObjectMv = { gpu = 1, render = 1 },
    dlssWaterCurrent = { gpu = 1 },
    dlssWaterHistoryPct = { gpu = 0 },
    hdr = { gpu = 1, render = 1, vram = 1 },
    hdrAuto = { gpu = 1, render = 1, vram = 1 },
    hdrLightPct = { cpu = 1 },
    hdrBloomPct = { gpu = 1 },
    hdrGlintPct = { gpu = 1 },
    dlssPreset = { gpu = -1 },
    dlssSharpen = { gpu = 0 },
    dlssOutputPct = { gpu = -2 },
    dlssOutputFilter = { gpu = 1 },
    fogScalePct = { gpu = -2, vram = -1 },
    fogMaskFrames = { cpu = -1 },
    roofHideDebounceFrames = {},
    cutawayVisitPrefilter = { cpu = -1 },
    cutawayInvalidateChanged = { cpu = -2, gpu = -1 },
    occlusionSkipLightingOnly = { cpu = -1 },
    lightInfoChunkGate = { cpu = -1 },
    lightInfoOncePerFrame = { cpu = -1 },
    soundZoneCache = { cpu = -1 },
    worldSoundFast = { cpu = -1 },
    -- sprite buffers
    uiRetained = { cpu = -2 },
    uiRetainedChildren = { cpu = -1 },
    uiTickStagger = {},
    uiLuaFast = { cpu = -1 },
    modProfile = { cpu = -1 },
    modCompat = {},
    uiRetainedMods = { cpu = -1 },
    luaWorkerGate = {},
    hotsaveWarmup = { cpu = -1, load = 1 },
    mapStreetMemo = { cpu = -1 },
    mapStreetCache = { cpu = -1 },
    mapVisitedFast = { cpu = -1 },
    luaIndexCache = {},
    luaInternConstants = {},
    luaSkipEmpty = {},
    persistentVbo = { render = -3, gpu = -1 },
    persistentVboFrameSync = { render = -2 },
    persistentVboSlots = { render = -1, vram = 1 },
    vboBatchKb = { render = -2, vram = 1 },
    vboFastQuads = { render = -1 },
    -- multiplayer
    luaChecksumExempt = {},
    -- updates
    updateCheck = {},
    updateFromWorkshop = {},
    updatePrefetch = { disk = 1 },
    -- overlay
    overlaySampling = { cpu = 1, cores = 1 },
    overlay = { cpu = 1 },
    overlayLog = { disk = 1 },
    overlayTree = { cpu = 1, cores = 1 },
    overlayVerdict = {},
    overlayPower = {},
    overlayGraph = { cpu = 1 },
    overlayTexture = { cpu = -1, render = -2, gpu = -1 },
    overlayRefreshMs = { cpu = -1 },
    overlayGraphHz = { cpu = 1, render = 1 },
    overlayFlame = { cpu = 1, cores = 1 },
    gameThreadProfileHz = { cores = 1, cpu = 1 },
    consoleLog = {},
    -- chunk streaming
    parallel = { cores = 3, ram = 1, chunks = -3 },
    workers = { cores = 2, chunks = -1 },
    loadWorkers = { cores = 2, load = -1 },
    wake = { chunks = -2 },
    chunkHandoffDivisor = { cpu = -1, chunks = 1 },
    chunkGridWidth = { cpu = -2, gpu = -1, ram = -1, vram = -1, load = -1 }, -- the bars describe the smaller grids
    chunkGridFollowView = { chunks = -1 }, -- the upstairs screen corners are filled; same chunk count
    hotsaveStaged = { cpu = -1 },
    hotsaveIntervalSec = { cpu = -2, disk = -2 },
    -- boot
    fmodAsync = { load = -2, cores = 1 },
    preloadAnimSets = { load = -2, cores = 1 },
    bootPump = { load = -2, cores = 2, ram = 1 },
    bootFileThreads = { load = -1, cores = 2 },
    earlyModels = { load = -1 },
    luaPrecompile = { load = -2, cores = 2 },
    animClipCache = { load = -2, disk = 2 },
    packIndex = { load = -2, disk = 1 },
    scriptParserFast = { load = -2, ram = -1 },
    itemParamSwitch = { load = -1 },
    -- world load
    fileThreads = { load = -1, cores = 2 },
    fileInflight = { load = -1, ram = 1 },
    textureBufferMb = { load = -1, ram = 2 },
    parallelDepthMaps = { load = -1, cores = 1 },
    loaderCpuFixes = { load = -1, cpu = -1 },
    shaderCache = { load = -3, render = -1 },
    mipmapArrays = { cores = -1 },
    texCompress = { render = -3, load = -1, cores = 1, gpu = 1 },
    noLoadFade = { load = -1 },
    noIntroWait = { load = -2 },
    noClickToStart = { load = -2 },
    noLoadingScreen = { load = -1 },
    resumeShot = { load = -1, disk = 1 },
    centerFirstLoad = { load = -2, chunks = -1 },
    tileDefPreload = { load = -2, cores = 1 },
    skipIdChecks = { load = -1 },
    voronoiFast = { load = -2, cores = -1 },
    earlyTilePacks = { load = -1 },
    aotCache = { load = -2, disk = 1 },
}

-- SDR values of the media style (docs/media-style.md): stock amber, optimized green, a third series blue
local C_STOCK = { r = 0.79, g = 0.35, b = 0.17 }
local C_OPT = { r = 0.27, g = 0.87, b = 0.49 }
local C_BLUE = { r = 0.23, g = 0.56, b = 0.88 }
local C_TEXT = { r = 0.94, g = 0.94, b = 0.96 }
local C_GREY = { r = 0.66, g = 0.66, b = 0.70 }
local C_DIM = { r = 0.40, g = 0.40, b = 0.45 }
local CLIP_W, CLIP_H = 512, 216

local function clipPath(clip, side)
    if side == "stock" and STOCK_FILE[clip] then clip = STOCK_FILE[clip] end
    return "media/ui/pzopt/compare/" .. clip .. "-" .. side .. ".gif"
end

-- Whether the preview plays the before / after clips (key previewClips, off by default): the tick box in every
-- tab's header flips it for all three tabs and saves it to options.ini at once. `gen` tells the previews to lay
-- out again. A build without the key keeps the choice for the session only.
local CLIPS = { on = nil, gen = 0 }

local function clipsOn()
    if CLIPS.on == nil then
        CLIPS.on = false
        pcall(function()
            local p = perf()
            if p:isPzoptOptionKnown("previewClips") then
                local saved = p:getPzoptOptionSaved("previewClips")
                CLIPS.on = (saved ~= "" and saved or p:getPzoptOptionDefault("previewClips")) == "true"
            end
        end)
    end
    return CLIPS.on
end

local function setClipsOn(on)
    if on == clipsOn() then return end
    CLIPS.on = on
    CLIPS.gen = CLIPS.gen + 1
    pcall(function()
        local p = perf()
        if p:isPzoptOptionKnown("previewClips") then
            -- "" = the default (off) on the next boot
            p:setPzoptOption("previewClips", on and "true" or "")
        end
        if not on then p:releasePzoptGifs() end
    end)
end

PzoptPreview = ISPanel:derive("PzoptPreview")

function PzoptPreview:new(x, y, w, h, panel, rows)
    local o = ISPanel.new(self, x, y, w, h)
    o.panel = panel   -- the scrolling options panel the rows live in
    o.rows = rows     -- { entry, option, clip, y, h } in the panel's content space
    o.row = nil
    o.backgroundColor = { r = 0.04, g = 0.04, b = 0.06, a = 1 } -- opaque: the list's long labels pass under it
    o.borderColor = { r = 0.31, g = 0.31, b = 0.35, a = 1 }
    o.pad = 12
    o.fontS = UIFont.Small
    o.fontM = UIFont.Medium
    o.hS = getTextManager():getFontHeight(UIFont.Small)
    o.hM = getTextManager():getFontHeight(UIFont.Medium)
    -- every controller-focusable element of a row (the curve sliders are several lines) -> the row
    o.byControl = {}
    for _, row in ipairs(rows) do
        for _, line in ipairs(row.option.pzoptJoyLines or { { row.option.control } }) do
            for _, el in ipairs(line) do o.byControl[el] = row end
        end
    end
    o:layoutSlots()
    return o
end

-- Every part of the panel has a fixed place, so nothing moves when the mouse crosses to another row: the
-- panel is the whole free area of the page, the text column is centred in it (capped so lines stay readable),
-- the description slot is as tall as the longest description wrapped at that width, the clips take the height
-- that is left (up to 3x their 512 px source), the bars and the legend sit under the description.
function PzoptPreview:layoutSlots()
    local pad, gap = self.pad, self.pad
    local cw = math.min(self.width - 2 * pad, 2 * CLIP_W * 3 + gap)
    self.colX = math.floor((self.width - cw) / 2)
    self.colW = cw
    local lines = 1
    local function count(tip)
        local n = 0
        for _ in string.gmatch(getTextManager():WrapText(self.fontS, tip, cw), "[^\n]+") do n = n + 1 end
        if n > lines then lines = n end
    end
    for _, row in ipairs(self.rows) do count(row.entry.tip) end
    self.descLines = lines
    self.clips = clipsOn()
    self.clipsGen = CLIPS.gen
    local fixed = pad + self.hM + 2 + self.hS + 2 + self.hS + 8 -- title, values, Java classes
        + lines * self.hS + 8                              -- description
        + self.hM + 4 + #AXES * (self.hS + 6)              -- bars
        + 6 + self.hS + 2 + self.hS                        -- x axis ticks and title
        + 4 + 2 * self.hS + pad                            -- legend
    local iw, ih = 0, 0
    if self.clips then
        fixed = fixed + self.hS + 2 + 4 + self.hS + 8      -- clip captions, clip title line
        ih = math.max(60, self.height - fixed)
        iw = math.floor((cw - gap) / 2)
        if math.floor(iw * CLIP_H / CLIP_W) < ih then
            ih = math.floor(iw * CLIP_H / CLIP_W)
        else
            iw = math.floor(ih * CLIP_W / CLIP_H)
        end
    end
    self.clipW, self.clipH = iw, ih
    -- height the clips did not need (a wide page) makes the bar rows taller, up to twice the font height
    local spare = math.max(0, self.height - fixed - ih)
    self.barRowH = math.min(2 * self.hS + 8, self.hS + 6 + math.floor(spare / #AXES))
    self.slotW, self.slotH = self.width, self.height
end

-- the wheel over the preview scrolls the options list, like anywhere else on the page
function PzoptPreview:onMouseWheel(del)
    return false
end

function PzoptPreview:select(row)
    self.row = row
end

-- The row under the mouse: the list's mouse position is in its content space (scroll included), like row.y.
-- With a controller (the page holds the joypad focus) the row of the focused line instead, wherever the mouse
-- is; a section heading or the search row keeps the last setting shown.
function PzoptPreview:pick()
    local panel = self.panel
    if panel.joyfocus then
        local line = panel.joypadButtonsY and panel.joypadButtonsY[panel.joypadIndexY or 0]
        for _, el in ipairs(line or {}) do
            local row = self.byControl[el]
            if row then
                self:select(row)
                return
            end
        end
        return
    end
    if not panel:isMouseOver() or self:isMouseOver() then return end
    local mx, my = panel:getMouseX(), panel:getMouseY()
    if mx >= self.x or mx < (self.minX or 0) then return end
    for _, row in ipairs(self.rows) do
        if not row.hidden and my >= row.y and my < row.y + row.h then
            self:select(row)
            return
        end
    end
end

function PzoptPreview:text(str, x, y, col, font, alpha)
    self:drawText(str, x, y, col.r, col.g, col.b, alpha or 1, font or self.fontS)
end

-- One clip box: caption, the current frame (or why there is none), a hairline frame.
function PzoptPreview:drawClip(x, y, w, h, caption, path, now, col)
    self:text(caption, x, y, col, self.fontS)
    y = y + self.hS + 2
    self:drawRect(x, y, w, h, 1, 0.02, 0.02, 0.03)
    local ok, tex = pcall(function() return perf():getPzoptGifFrame(path, now) end)
    if ok and tex then
        self:drawTextureScaledAspect(tex, x, y, w, h, 1, 1, 1, 1)
    else
        local state = ok and perf():getPzoptGifState(path) or "error"
        local msg = "no clip for this setting yet"
        if state == "loading" then msg = "loading..." elseif state == "error" then msg = "clip could not be decoded" end
        self:drawTextCentre(msg, x + w / 2, y + h / 2 - self.hS / 2, C_DIM.r, C_DIM.g, C_DIM.b, 1, self.fontS)
    end
    self:drawRectBorder(x, y, w, h, 1, 0.31, 0.31, 0.35)
    return y + h
end

function PzoptPreview:drawWrapped(str, x, y, w, col, font)
    local wrapped = getTextManager():WrapText(font or self.fontS, str, w)
    local h = font == self.fontM and self.hM or self.hS
    for line in string.gmatch(wrapped, "[^\n]+") do
        self:text(line, x, y, col, font)
        y = y + h
    end
    return y
end

-- The effect bars: a track per axis with the zero line in the middle; the bar grows left for less work / sooner
-- (green) and right for more (amber, or blue where more means idle cores put to work), a word says the same.
function PzoptPreview:drawBars(x, y, w, fx)
    local labW = math.min(170, math.floor(w * 0.3))
    local wordW = 110
    local barX = x + labW + 10
    local barW = w - labW - 10 - wordW - 8
    local rowH = self.barRowH or (self.hS + 6)
    local textY = math.floor((rowH - self.hS) / 2)
    local mid = barX + math.floor(barW / 2)
    local half = math.floor(barW / 2) - 2
    for _, axis in ipairs(AXES) do
        local v = fx[axis.id] or 0
        if v > 3 then v = 3 elseif v < -3 then v = -3 end
        self:drawTextRight(axis.label, x + labW, y + textY, C_GREY.r, C_GREY.g, C_GREY.b, 1, self.fontS)
        self:drawRect(barX, y + 3, barW, rowH - 6, 1, 0.11, 0.11, 0.13)
        local word, wcol = LEVELS[0], C_DIM
        if v ~= 0 then
            local len = math.floor(half * math.abs(v) / 3)
            local col = v < 0 and C_OPT or (axis.moreIsWork and C_BLUE or C_STOCK)
            local bx = v < 0 and (mid - len) or (mid + 1)
            self:drawRect(bx, y + 3, len, rowH - 6, 1, col.r, col.g, col.b)
            word = LEVELS[v]
            wcol = col
        end
        -- faint ticks through the track at every level, the zero line stronger
        for _, t in ipairs(AXIS_TICKS) do
            local tx = mid + math.floor(half * t / 3)
            self:drawRect(tx, y + 3, 1, rowH - 6, 1, 0.22, 0.22, 0.26)
        end
        self:drawRect(mid, y + 1, 1, rowH - 2, 1, 0.55, 0.55, 0.60)
        self:text(word, barX + barW + 8, y + textY, wcol, self.fontS)
        y = y + rowH
    end
    -- the x axis: a rule, a tick and a word per level, the title under them
    self:drawRect(barX, y, barW, 1, 1, 0.55, 0.55, 0.60)
    for _, t in ipairs(AXIS_TICKS) do
        local tx = mid + math.floor(half * t / 3)
        self:drawRect(tx, y, 1, 5, 1, 0.55, 0.55, 0.60)
        local label = LEVELS[t]
        local lw = getTextManager():MeasureStringX(self.fontS, label)
        local lx = tx - math.floor(lw / 2)
        if lx < barX then lx = barX elseif lx + lw > barX + barW then lx = barX + barW - lw end
        local col = t < 0 and C_OPT or (t > 0 and C_STOCK or C_GREY)
        self:text(label, lx, y + 6, col, self.fontS)
    end
    y = y + 6 + self.hS + 2
    self:drawTextCentre(AXIS_TITLE, mid, y, C_DIM.r, C_DIM.g, C_DIM.b, 1, self.fontS)
    return y + self.hS
end

function PzoptPreview:prerender()
    ISPanel.prerender(self)
    -- window resized, or the clips switched on / off
    if self.width ~= self.slotW or self.height ~= self.slotH or self.clipsGen ~= CLIPS.gen then self:layoutSlots() end
    self:pick()
    local row = self.row
    local pad = self.pad
    local x, y, w = self.colX, pad, self.colW
    if not row then
        self:text("Point at a setting to see what it does.", x, y, C_GREY, self.fontM)
        return
    end
    local entry = row.entry
    local p = perf()
    -- title and values: one line each, cut with "..." rather than wrapped
    self:text(getTextManager():WrapText(self.fontM, entry.label, w, 1, "..."), x, y, C_TEXT, self.fontM)
    y = y + self.hM + 2
    local pinnedBy = p:getPzoptOptionPinnedBy(entry.key)
    local values = entry.live
        and ("Key " .. entry.key .. "   now: " .. p:getPzoptOption(entry.key) .. "   after Apply: " .. row.option:pzoptCurrent())
        or ("Key " .. entry.key .. "   since this boot: " .. p:getPzoptOption(entry.key) .. "   next launch: " .. row.option:pzoptCurrent())
    if pinnedBy ~= "" then values = values .. "   (pinned by " .. pinnedBy .. ")" end
    local reason = compatReason(entry.key)
    if reason ~= "" then values = values .. "   (off for mod compatibility: " .. reason .. ")" end
    self:text(getTextManager():WrapText(self.fontS, values, w, 1, "..."), x, y, C_GREY)
    y = y + self.hS + 2
    local classes = optionClasses(entry.key)
    local java = #classes > 0 and ("Java: " .. table.concat(classes, ", ")) or "Java: read by pzopt.Config only"
    java = (optionDate(entry.key) and ("Released " .. optionDate(entry.key)) or "New in this version") .. "   " .. java
    self:text(getTextManager():WrapText(self.fontS, java, w, 1, "..."), x, y, C_DIM)
    y = y + self.hS + 8
    -- the two clips, centred in the column (with the header's "Before / after clips" ticked)
    if self.clips then
        local iw, ih, gap = self.clipW, self.clipH, pad
        local cx = x + math.floor((w - (2 * iw + gap)) / 2)
        local now = getTimestampMs()
        local sides = clipSides(row.clip)
        self:drawClip(cx, y, iw, ih, sides[1], clipPath(row.clip, "stock"), now, C_STOCK)
        y = self:drawClip(cx + iw + gap, y, iw, ih, sides[2], clipPath(row.clip, "opt"), now, C_OPT) + 4
        local same = sides == CLIP_SIDES.overlay and ". Same save, route and machine, a crop of the top-left corner at the Large overlay font."
            or CLIP_NOTES[row.clip] or ". Same save, route and machine; the number is that run's live frame rate."
        self:text(getTextManager():WrapText(self.fontS, (CLIP_TITLES[row.clip] or row.clip) .. same, w, 1, "..."), x, y, C_DIM)
        y = y + self.hS + 8
    end
    -- what it does, in a slot tall enough for the longest description
    self:drawWrapped(entry.tip, x, y, w, C_TEXT)
    y = y + self.descLines * self.hS + 8
    -- the bars
    self:text("Effect on your hardware", x, y, C_TEXT, self.fontM)
    y = y + self.hM + 4
    y = self:drawBars(x, y, w, EFFECTS[entry.key] or {})
    self:drawWrapped("Against the stock game, from the measurements in docs/archive/2026-09-24/results.md: green = less load (or a shorter "
        .. "load, chunks sooner), amber = more, blue = idle cores put to work. " .. noteFor(entry), x, y + 4, w, C_DIM)
end

-- ---------------------------------------------------------------------------------------------------
-- Search: BM25 over every setting's label, key, description, combo notes, section, the resources its EFFECTS bars
-- move (the AXES names) and the Java classes that read its key, with fuzzy term matching (prefix, substring, one
-- typo from 4 letters, two from 7). Each typed word must match (camelCase and dotted names count as one word:
-- "FogPass" matches the class or both "fog" and "pass"); a word may also match a term that is a longer form of it.

local STOPWORDS = {}
for w in string.gmatch("a an and are as at be by for from in into is it its of on or so than that the then this to with", "%a+") do
    STOPWORDS[w] = true
end
local BM25_K1, BM25_B = 1.2, 0.75
-- field weights (term-frequency multipliers, BM25F-style)
local W_LABEL, W_KEY, W_CLASS, W_RESOURCE, W_SECTION, W_TIP = 3, 3, 2, 2, 1, 1

-- The parts of one identifier-ish word: "treesInChunkTexture" -> trees chunk texture, "FBORenderCell" -> fbo render
-- cell, "pzopt.FogPass" -> pzopt fog pass.
local function parts(word)
    local spaced = string.gsub(word, "(%l)(%u)", "%1 %2")
    spaced = string.gsub(spaced, "(%u)(%u%l)", "%1 %2")
    local out = {}
    for w in string.gmatch(string.lower(spaced), "%w+") do
        if not STOPWORDS[w] then table.insert(out, w) end
    end
    return out
end

-- Adds the terms of a text to tf with a weight: every part, and the whole word (dots dropped) when it had several.
local function addTerms(tf, text, weight)
    local n = 0
    for word in string.gmatch(tostring(text or ""), "[%w%.]+") do
        local ps = parts(word)
        for _, t in ipairs(ps) do
            tf[t] = (tf[t] or 0) + weight
            n = n + weight
        end
        local whole = string.lower((string.gsub((string.gsub(word, "^pzopt%.", "")), "%.", "")))
        if #ps > 1 and whole ~= "" then
            tf[whole] = (tf[whole] or 0) + weight
            n = n + weight
        end
    end
    return n
end

-- Levenshtein distance, giving up (limit + 1) once every path is past the limit.
local function editDistance(a, b, limit)
    local la, lb = #a, #b
    if math.abs(la - lb) > limit then return limit + 1 end
    local prev = {}
    for j = 0, lb do prev[j] = j end
    for i = 1, la do
        local cur = { [0] = i }
        local best = i
        local ca = string.byte(a, i)
        for j = 1, lb do
            local v = math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + ((ca == string.byte(b, j)) and 0 or 1))
            cur[j] = v
            if v < best then best = v end
        end
        if best > limit then return limit + 1 end
        prev = cur
    end
    return prev[lb]
end

-- The index over the tab's rows: one document per setting.
local function buildIndex(rows, sectionOf)
    local index = { docs = {}, df = {}, vocab = {}, expand = {}, avgdl = 1 }
    local total = 0
    for _, row in ipairs(rows) do
        local entry, tf, len = row.entry, {}, 0
        len = len + addTerms(tf, entry.label, W_LABEL)
        len = len + addTerms(tf, entry.key, W_KEY)
        len = len + addTerms(tf, entry.tip, W_TIP)
        if entry.note then
            for _, v in pairs(entry.note) do len = len + addTerms(tf, v, W_TIP) end
        end
        len = len + addTerms(tf, sectionOf[row] and sectionOf[row].title, W_SECTION)
        local fx = EFFECTS[entry.key] or {}
        for _, axis in ipairs(AXES) do
            if fx[axis.id] and fx[axis.id] ~= 0 then
                len = len + addTerms(tf, axis.id .. " " .. axis.label, W_RESOURCE)
            end
        end
        for _, name in ipairs(optionClasses(entry.key)) do
            len = len + addTerms(tf, name, W_CLASS)
        end
        table.insert(index.docs, { row = row, tf = tf, len = len })
        total = total + len
        for t in pairs(tf) do
            if not index.df[t] then table.insert(index.vocab, t) end
            index.df[t] = (index.df[t] or 0) + 1
        end
    end
    local n = #index.docs
    if n > 0 then index.avgdl = total / n end
    index.idf = {}
    for t, df in pairs(index.df) do
        index.idf[t] = math.log(1 + (n - df + 0.5) / (df + 0.5))
    end
    return index
end

-- The vocabulary terms a query word stands for, with a weight: exact 1, a longer form 0.8, the word inside a term
-- 0.5, one typo 0.6, two typos 0.35.
local function expand(index, q)
    local hit = index.expand[q]
    if hit then return hit end
    hit = {}
    local lq = #q
    for _, t in ipairs(index.vocab) do
        local w
        if t == q then
            w = 1
        elseif lq >= 2 and string.sub(t, 1, lq) == q then
            w = 0.8
        elseif #t >= 4 and lq > #t and lq - #t <= 2 and string.sub(q, 1, #t) == t then
            w = 0.7 -- "chunks" -> "chunk"
        elseif lq >= 3 and string.find(t, q, 1, true) then
            w = 0.5
        elseif lq >= 4 and (string.byte(t, 1) == string.byte(q, 1) or string.byte(t, 2) == string.byte(q, 2)) then
            -- a typo rarely hits both of the first two letters; the check keeps the scan cheap under Kahlua
            local d = editDistance(q, t, lq >= 7 and 2 or 1)
            if d == 1 then w = 0.6 elseif d == 2 and lq >= 7 then w = 0.35 end
        end
        if w then table.insert(hit, { t = t, w = w }) end
    end
    index.expand[q] = hit
    return hit
end

local function termScore(index, doc, t)
    local tf = doc.tf[t]
    if not tf then return 0 end
    local norm = 1 - BM25_B + BM25_B * doc.len / index.avgdl
    return index.idf[t] * tf * (BM25_K1 + 1) / (tf + BM25_K1 * norm)
end

-- Best weighted BM25 score of one query term in one document (0 = no match).
local function wordScore(index, doc, q)
    local best = 0
    for _, e in ipairs(expand(index, q)) do
        local s = doc.tf[e.t] and e.w * termScore(index, doc, e.t) or 0
        if s > best then best = s end
    end
    return best
end

-- Scores every row for a query: { [row] = score } of the rows every query word matched (all words must match;
-- if no row has them all, the rows matching any word), and how many matched.
local function search(index, query)
    local units = {}
    for word in string.gmatch(query, "[%w%.]+") do
        local ps = parts(word)
        local whole = string.lower((string.gsub((string.gsub(word, "^pzopt%.", "")), "%.", "")))
        if #ps > 0 or (whole ~= "" and not STOPWORDS[whole]) then
            table.insert(units, { whole = whole, parts = ps })
        end
    end
    if #units == 0 then return nil, 0 end
    local all, any = {}, {}
    local nAll, nAny = 0, 0
    for _, doc in ipairs(index.docs) do
        local total, matched = 0, 0
        for _, u in ipairs(units) do
            local s = wordScore(index, doc, u.whole)
            if #u.parts > 1 then
                local sum = 0
                for _, p in ipairs(u.parts) do
                    local ps = wordScore(index, doc, p)
                    if ps == 0 then sum = 0 break end
                    sum = sum + ps
                end
                if sum > s then s = sum end
            end
            if s > 0 then matched = matched + 1 end
            total = total + s
        end
        if matched == #units then all[doc.row] = total; nAll = nAll + 1 end
        if matched > 0 then any[doc.row] = total; nAny = nAny + 1 end
    end
    local hits, n = all, nAll
    if nAll == 0 then hits, n = any, nAny end
    -- drop the weak tail fuzzy matching drags in
    local top = 0
    for _, s in pairs(hits) do if s > top then top = s end end
    for row, s in pairs(hits) do
        if s < 0.15 * top then hits[row] = nil; n = n - 1 end
    end
    return hits, n
end

local function comboLabels(entry, default, saved)
    local labels = { "Default (" .. default .. ((entry.note and entry.note[default]) and (", " .. entry.note[default]) or "") .. ")" }
    local values = {}
    local seen = {}
    for _, v in ipairs(entry.choices) do
        local text = v
        if entry.note and entry.note[v] then
            text = v .. " (" .. entry.note[v] .. ")"
        end
        table.insert(labels, text)
        table.insert(values, v)
        seen[v] = true
    end
    -- a value typed into options.ini by hand that is not in the list stays selectable
    if saved ~= "" and not seen[saved] then
        table.insert(labels, saved)
        table.insert(values, saved)
    end
    return labels, values
end

local function addBoolOption(self, entry, splitpoint, y, BUTTON_HGT)
    local p = perf()
    local pinnedBy = p:getPzoptOptionPinnedBy(entry.key)
    local box = self:addYesNo(splitpoint, y, BUTTON_HGT, BUTTON_HGT, entry.label)
    box.tooltip = tooltipFor(entry, pinnedBy)
    if pinnedBy ~= "" then
        box.enable = false
    end
    local option = GameOption:new("pzopt." .. entry.key, box)
    function option.toUI(self)
        self.pzoptShown = nextValue(entry)
        self.control:setSelected(1, self.pzoptShown == "true")
    end
    function option.apply(self)
        if pinnedBy ~= "" then return end
        local value = tostring(self.control:isSelected(1))
        if compatDecides(entry.key) then
            if value == self.pzoptShown then return end -- untouched: mod compatibility keeps deciding
            perf():setPzoptOption(entry.key, value) -- the player's choice beats mod compatibility, the default too
            afterStore(self, entry, value)
            return
        end
        local before = entry.restartKeys and startupSignature(entry)
        store(entry, value)
        afterStore(self, entry, value)
        if before then
            self:restartRequired(before, startupSignature(entry))
        end
    end
    -- the "Enable all" button puts the control back to the build's default (mod compatibility's value where it decides)
    function option.pzoptReset(self)
        if pinnedBy ~= "" then return end
        if compatDecides(entry.key) then
            self.control:setSelected(1, self.pzoptShown == "true")
            return
        end
        self.control:setSelected(1, perf():getPzoptOptionDefault(entry.key) == "true")
    end
    -- a profile button sets an explicit value (nil = the build's default)
    function option.pzoptSet(self, value)
        if pinnedBy ~= "" then return end
        if value == nil then return self:pzoptReset() end
        self.control:setSelected(1, value == "true")
    end
    -- what the control says right now (the preview panel's "next launch" value)
    function option.pzoptCurrent(self)
        return tostring(self.control:isSelected(1))
    end
    option.pzoptKey = entry.key
    option.pzoptBool = true
    self.gameOptions:add(option)
    return option
end

-- Colour controls store RGB hex; opacity remains a separate setting.
local function colourHex(value)
    return string.upper((value or ""):match("^%s*#?(%x%x%x%x%x%x)%s*$") or "FFC740")
end

local function colourRGB(value)
    local hex = colourHex(value)
    return {
        r = tonumber(hex:sub(1, 2), 16) / 255,
        g = tonumber(hex:sub(3, 4), 16) / 255,
        b = tonumber(hex:sub(5, 6), 16) / 255,
        a = 1,
    }
end

local function addColourOption(self, entry, splitpoint, y)
    local p = perf()
    local pinnedBy = p:getPzoptOptionPinnedBy(entry.key)
    local option
    local function setValue(value)
        local button = option.control
        button.pzoptValue = value == "" and "" or colourHex(value)
        button.backgroundColor = colourRGB(value ~= "" and value or p:getPzoptOptionDefault(entry.key))
        button.backgroundColorMouseOver = button.backgroundColor
    end
    local function openPicker(screen, button)
        if pinnedBy ~= "" then
            return
        end
        require("ISUI/ISSliderPanel")
        require("ISUI/ISColorPickerHSB")
        if screen.pzoptColourPicker then
            screen.pzoptColourPicker:removeSelf()
        end
        local rgb = button.backgroundColor
        local picker = ISColorPickerHSB:new(0, 0, ColorInfo.new(rgb.r, rgb.g, rgb.b, 1))
        picker:initialise()
        picker.resetFocusTo = button.parent
        picker:setPickedFunc(function(_, colour)
            local function channel(value)
                return math.floor(math.max(0, math.min(1, value)) * 255 + 0.5)
            end
            setValue(string.format("%02X%02X%02X", channel(colour.r), channel(colour.g), channel(colour.b)))
            option:invokeOnChangeEvent()
            if picker.parent then
                picker:removeSelf()
            end
        end)
        local removeSelf = picker.removeSelf
        picker.removeSelf = function(o)
            screen.pzoptColourPicker = nil
            removeSelf(o)
        end
        -- The popup belongs to the options screen, not the scrolling settings panel.
        local prerender = picker.prerender
        picker.prerender = function(o)
            if not button:getIsVisible() or not button.parent:getIsVisible() then
                o:removeSelf()
                return
            end
            prerender(o)
        end
        screen:addChild(picker)
        local x = button:getAbsoluteX() - screen:getAbsoluteX()
        local top = button:getAbsoluteY() - screen:getAbsoluteY()
        local py = top + button:getHeight() + 1
        if py + picker:getHeight() > screen:getHeight() then
            py = top - picker:getHeight() - 1
        end
        picker:setX(math.max(0, math.min(x, screen:getWidth() - picker:getWidth())))
        picker:setY(math.max(0, py))
        picker:setCapture(true)
        picker:setVisible(true)
        picker:bringToTop()
        screen.pzoptColourPicker = picker
        local joypad = JoypadState.getMainMenuJoypad()
        if joypad then
            joypad.focus = picker
        end
    end
    local button = self:addColorButton(splitpoint, y, entry.label, colourRGB(""), openPicker)
    button.tooltip = tooltipFor(entry, pinnedBy)
    button:setEnable(pinnedBy == "")
    option = GameOption:new("pzopt." .. entry.key, button)
    function option.toUI()
        setValue(pinnedBy ~= "" and p:getPzoptOption(entry.key) or p:getPzoptOptionSaved(entry.key))
    end
    function option.apply(o)
        if pinnedBy ~= "" then
            return
        end
        local value = o.control.pzoptValue
        p:setPzoptOption(entry.key, value)
        afterStore(o, entry, value ~= "" and value or p:getPzoptOptionDefault(entry.key))
    end
    function option.pzoptReset()
        if pinnedBy == "" then
            setValue("")
        end
    end
    function option.pzoptSet(_, value)
        if pinnedBy == "" then
            setValue(value or "")
        end
    end
    function option.pzoptCurrent(o)
        return o.control.pzoptValue ~= "" and ("#" .. o.control.pzoptValue)
            or ("#" .. colourHex(p:getPzoptOptionDefault(entry.key)) .. " (default)")
    end
    option.pzoptKey = entry.key
    self.gameOptions:add(option)
    return option
end

local function addIntOption(self, entry, splitpoint, y, comboWidth)
    local p = perf()
    local pinnedBy = p:getPzoptOptionPinnedBy(entry.key)
    local labels, values = comboLabels(entry, p:getPzoptOptionDefault(entry.key), p:getPzoptOptionSaved(entry.key))
    local combo = self:addCombo(splitpoint, y, comboWidth, 20, entry.label, labels, 1)
    combo:setToolTipMap({ defaultTooltip = tooltipFor(entry, pinnedBy) })
    if pinnedBy ~= "" then
        combo.disabled = true
    end
    local function indexOf(value)
        for i, v in ipairs(values) do
            if v == value then return i + 1 end
        end
        return nil
    end
    local option = GameOption:new("pzopt." .. entry.key, combo)
    function option.toUI(self)
        local pp = perf()
        local box = self.control
        if pinnedBy ~= "" then
            box.selected = indexOf(pp:getPzoptOption(entry.key)) or 1
        else
            local saved = pp:getPzoptOptionSaved(entry.key)
            box.selected = (saved ~= "" and indexOf(saved)) or 1
        end
    end
    function option.apply(self)
        if pinnedBy ~= "" then return end
        local box = self.control
        local value = box.selected > 1 and values[box.selected - 1] or ""
        perf():setPzoptOption(entry.key, value)
        local effective = value ~= "" and value or perf():getPzoptOptionDefault(entry.key)
        afterStore(self, entry, effective)
    end
    function option.pzoptReset(self)
        if pinnedBy ~= "" then return end
        self.control.selected = 1 -- "Default (...)"
    end
    function option.pzoptSet(self, value)
        if pinnedBy ~= "" then return end
        local index = value ~= nil and indexOf(value) or nil
        if index == nil then
            -- a profile value outside the combo's list becomes selectable, like a hand-typed one
            if value ~= nil and value ~= perf():getPzoptOptionDefault(entry.key) then
                table.insert(labels, value)
                table.insert(values, value)
                self.control:addOption(value)
                index = #values + 1
            else
                index = 1
            end
        end
        self.control.selected = index
    end
    function option.pzoptCurrent(self)
        local box = self.control
        if box.selected > 1 and values[box.selected - 1] then
            return values[box.selected - 1]
        end
        return perf():getPzoptOptionDefault(entry.key) .. " (default)"
    end
    option.pzoptKey = entry.key
    self.gameOptions:add(option)
    return option
end

-- Text fields use the same value/pinning/profile contract as combos. Normalize to the effective
-- sensitivity step before saving, so the menu reflects what Config actually applies.
local function numericValue(entry, text)
    local raw = string.gsub(string.match(text or "", "^%s*(.-)%s*$"), ",", ".")
    if not string.match(raw, "^%d+%.?%d*$") then return nil end
    local value = tonumber(raw)
    local spec = entry.number
    if not value or value < spec.min or value > spec.max then return nil end
    if spec.twentieths then
        return string.format("%.2f", math.floor(value * 20 + 0.5) / 20)
    end
    if value ~= math.floor(value) then return nil end
    return tostring(value)
end

local function addNumericOption(self, entry, splitpoint, y, width, height)
    local p = perf()
    local pinnedBy = p:getPzoptOptionPinnedBy(entry.key)
    local label = ISLabel:new(splitpoint, y + self.addY, height, entry.label, 1, 1, 1, 1, UIFont.Small)
    label:initialise()
    self.mainPanel:addChild(label)
    local box = ISTextEntryBox:new(nextValue(entry), splitpoint + 20, y + self.addY, width, height)
    box:initialise()
    box:instantiate()
    box.tooltip = tooltipFor(entry, pinnedBy) .. " Empty input restores the default; invalid input keeps the previous value on Apply."
    if pinnedBy ~= "" then box:setEditable(false) end
    self.mainPanel:addChild(box)
    self.mainPanel:insertNewLineOfButtons(box)
    self.addY = self.addY + height + MainOptions.style.borderSpacing

    local option = GameOption:new("pzopt." .. entry.key, box)
    function option.toUI(self)
        self.pzoptShown = nextValue(entry)
        self.control:setText(self.pzoptShown)
    end
    function option.apply(self)
        if pinnedBy ~= "" then return end
        local text = self.control:getText()
        local default = perf():getPzoptOptionDefault(entry.key)
        local value = text:match("^%s*$") and default or numericValue(entry, text)
        if not value then
            self.control:setText(nextValue(entry))
            return
        end
        self.control:setText(value)
        if compatDecides(entry.key) then
            if value == numericValue(entry, self.pzoptShown) then return end
            perf():setPzoptOption(entry.key, value)
        elseif tonumber(value) == tonumber(default) then
            perf():setPzoptOption(entry.key, "")
        else
            perf():setPzoptOption(entry.key, value)
        end
        afterStore(self, entry, value)
    end
    function option.pzoptReset(self)
        if pinnedBy == "" then self.control:setText(perf():getPzoptOptionDefault(entry.key)) end
    end
    function option.pzoptSet(self, value)
        if pinnedBy == "" then self.control:setText(value or perf():getPzoptOptionDefault(entry.key)) end
    end
    function option.pzoptCurrent(self)
        local text = self.control:getText()
        local value = text:match("^%s*$") and perf():getPzoptOptionDefault(entry.key) or numericValue(entry, text)
        return value or nextValue(entry)
    end
    option.pzoptKey = entry.key
    self.gameOptions:add(option)
    return option
end

-- The zoom curve (`bezier` entries, key zoomEase; 2026-09-22): the preset combo, then one slider per control-point
-- coordinate (x1, y1, x2, y2 as in CSS cubic-bezier(), each 0..1 so the zoom never overshoots its target) with a
-- plot of the curve in the label column beside them. A slider move selects the preset it matches, else "custom";
-- picking a preset moves the sliders. The option's value is always what the sliders say.
local BEZIER_AXES = { "Point 1 time (x1)", "Point 1 zoom (y1)", "Point 2 time (x2)", "Point 2 zoom (y2)" }

local function parseBezier(spec)
    local v = {}
    for part in string.gmatch(spec or "", "[^,; ]+") do
        table.insert(v, tonumber(part))
    end
    if #v ~= 4 then return nil end
    for i = 1, 4 do
        if v[i] == nil then return nil end
    end
    return v
end

local function sameBezier(a, b)
    if not a or not b then return false end
    for i = 1, 4 do
        if math.abs(a[i] - b[i]) > 0.005 then return false end
    end
    return true
end

local function formatBezier(v)
    local parts = {}
    for i = 1, 4 do parts[i] = string.format("%.2f", v[i]) end
    return table.concat(parts, ",")
end

-- The curve with its two handles, sampled like pzopt.ZoomEase: x(t) and y(t) share the parameter t.
PzoptBezierPlot = ISPanel:derive("PzoptBezierPlot")

function PzoptBezierPlot:render()
    local w, h = self.width, self.height
    self:drawRect(0, 0, w, h, 1, 0.06, 0.06, 0.07)
    self:drawLine(nil, 0, h, w, 0, 1, 1, 0.22, 0.22, 0.26) -- linear, for reference (drawLine2 draws nothing in B42 menus)
    local v = {}
    for i = 1, 4 do v[i] = self.sliders[i]:getCurrentValue() end
    local function px(x) return x * (w - 1) end
    local function py(y) return (1 - y) * (h - 1) end
    self:drawLine(nil, px(0), py(0), px(v[1]), py(v[2]), 1, 1, C_GREY.r, C_GREY.g, C_GREY.b)
    self:drawLine(nil, px(1), py(1), px(v[3]), py(v[4]), 1, 1, C_GREY.r, C_GREY.g, C_GREY.b)
    local function b(t, a, c)
        local u = 1 - t
        return 3 * u * u * t * a + 3 * u * t * t * c + t * t * t
    end
    local lx, ly = px(0), py(0)
    for s = 1, 32 do
        local t = s / 32
        local x, y = px(b(t, v[1], v[3])), py(b(t, v[2], v[4]))
        self:drawLine(nil, lx, ly, x, y, 2, 1, C_OPT.r, C_OPT.g, C_OPT.b)
        lx, ly = x, y
    end
    self:drawRect(px(v[1]) - 2, py(v[2]) - 2, 5, 5, 1, C_TEXT.r, C_TEXT.g, C_TEXT.b)
    self:drawRect(px(v[3]) - 2, py(v[4]) - 2, 5, 5, 1, C_TEXT.r, C_TEXT.g, C_TEXT.b)
    self:drawRectBorder(0, 0, w, h, 1, 0.31, 0.31, 0.35)
end

local function addBezierOption(self, entry, splitpoint, y, comboWidth, BUTTON_HGT)
    local p = perf()
    local pinnedBy = p:getPzoptOptionPinnedBy(entry.key)
    local default = p:getPzoptOptionDefault(entry.key)
    local labels, values = comboLabels(entry, default, "")
    table.insert(labels, "custom (the sliders below)")
    local customIndex = #labels
    local combo = self:addCombo(splitpoint, y, comboWidth, 20, entry.label, labels, 1)
    combo:setToolTipMap({ defaultTooltip = tooltipFor(entry, pinnedBy) })
    if pinnedBy ~= "" then
        combo.disabled = true
    end
    local spacing = MainOptions.style.borderSpacing
    local top = y + self.addY
    local valueW = getTextManager():MeasureStringX(UIFont.Small, "0.00") + 8
    local labelW = 0
    local sliders = {}
    for i, name in ipairs(BEZIER_AXES) do
        local rowY = y + self.addY
        local label = ISLabel:new(splitpoint, rowY, BUTTON_HGT, name, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
        label:initialise()
        self.mainPanel:addChild(label)
        labelW = math.max(labelW, getTextManager():MeasureStringX(UIFont.Small, name))
        local value = ISLabel:new(splitpoint + 20, rowY, BUTTON_HGT, "", 1, 1, 1, 1, UIFont.Small, true)
        value:initialise()
        self.mainPanel:addChild(value)
        local slider = ISSliderPanel:new(splitpoint + 20 + valueW, rowY, comboWidth - valueW, BUTTON_HGT)
        slider:initialise()
        slider:setValues(0, 1, 0.01, 0.1)
        slider.doToolTip = false -- its own tooltip is the radio's "increase step size"
        slider.valueLabel = value
        self.mainPanel:addChild(slider)
        self.mainPanel:insertNewLineOfButtons(slider)
        self.addY = self.addY + BUTTON_HGT + spacing
        sliders[i] = slider
    end
    local size = y + self.addY - spacing - top
    local plot = PzoptBezierPlot:new(math.max(16, splitpoint - labelW - 12 - size), top, size, size)
    plot:initialise()
    plot.sliders = sliders
    self.mainPanel:addChild(plot)

    local function current()
        local v = {}
        for i = 1, 4 do v[i] = sliders[i]:getCurrentValue() end
        return v
    end
    -- the sliders to a value; setCurrentValue ignores a disabled slider, so a pinned value is written directly
    local function setSliders(spec)
        local v = parseBezier(spec) or parseBezier(default)
        for i = 1, 4 do
            local s = sliders[i]
            s.currentValue = math.max(0, math.min(1, v[i]))
            s.valueLabel:setName(string.format("%.2f", s.currentValue))
            s.disabled = pinnedBy ~= ""
        end
    end
    -- the combo entry the sliders match: the default, a preset, or "custom"
    local function syncCombo()
        local v = current()
        if combo.selected == 1 and sameBezier(v, parseBezier(default)) then return end
        for i, value in ipairs(values) do
            if sameBezier(v, parseBezier(value)) then
                combo.selected = i + 1
                return
            end
        end
        combo.selected = customIndex
    end

    local option = GameOption:new("pzopt." .. entry.key, combo)
    for _, slider in ipairs(sliders) do
        slider.target = option
        slider.onValueChange = function(opt, value, s)
            s.valueLabel:setName(string.format("%.2f", value))
            syncCombo()
            opt:invokeOnChangeEvent()
        end
    end
    function option.onChange(self, box)
        if box.selected == 1 then
            setSliders(default)
        elseif values[box.selected - 1] then
            setSliders(values[box.selected - 1])
        end
    end
    function option.toUI(self)
        local pp = perf()
        local saved = pp:getPzoptOptionSaved(entry.key)
        if pinnedBy ~= "" then
            setSliders(pp:getPzoptOption(entry.key))
            syncCombo()
        elseif saved == "" then
            self.control.selected = 1
            setSliders(default)
        else
            setSliders(saved)
            self.control.selected = 0
            syncCombo()
        end
    end
    function option.apply(self)
        if pinnedBy ~= "" then return end
        local value = ""
        if self.control.selected ~= 1 then
            local v = current()
            value = sameBezier(v, parseBezier(default)) and "" or formatBezier(v)
        end
        perf():setPzoptOption(entry.key, value)
        local effective = value ~= "" and value or default
        afterStore(self, entry, effective)
    end
    function option.pzoptReset(self)
        if pinnedBy ~= "" then return end
        self.control.selected = 1
        setSliders(default)
    end
    function option.pzoptSet(self, value)
        if pinnedBy ~= "" then return end
        if value == nil then return self:pzoptReset() end
        setSliders(value)
        self.control.selected = 0
        syncCombo()
    end
    function option.pzoptCurrent(self)
        if self.control.selected == 1 then
            return default .. " (default)"
        end
        return formatBezier(current())
    end
    option.pzoptKey = entry.key
    option.pzoptJoyLines = { { combo }, { sliders[1] }, { sliders[2] }, { sliders[3] }, { sliders[4] } }
    self.gameOptions:add(option)
    return option
end

-- "Enable all": master on, every other control back to the build's default. "Disable all (stock)":
-- master off, the other controls untouched (they are ignored while the master is off). Neither writes
-- anything: the controls are marked changed and Apply / Accept saves them through the options above.
local function setAll(self, enable)
    local master = self.pzoptMaster
    if master and master.control.enable then
        master.control:setSelected(1, enable)
        master:invokeOnChangeEvent()
    end
    if enable then
        for _, option in ipairs(self.pzoptOptions) do
            option:pzoptReset()
            option:invokeOnChangeEvent()
        end
    end
end

-- Profiles: one button sets a named group of controls (the rest go back to the build's default),
-- master on; Apply / Accept saves them like the other buttons. Values are the option strings.
-- The low-end set (2026-09-21, docs/archive/2026-09-24/results.md): no chunk worker pool (its threads took the game thread's
-- core on four cores), trees baked into chunk textures only while walking, and on the Display page lighting
-- updates 10/s and the UI redrawn 30/s. The stock Display-page combos go by GameOption name -> combo index
-- (MainOptions.lua lists): lightingFPS {5, 10, 15, 20, 25, 30, 45, 60}, UIRenderFPS {120, 60, 30, 25, 20, 15, 10}.
local LOW_END_VALUES = { workers = "1", loadWorkers = "2", treeBakeMaxChunksPerSec = "24" }
-- Plus texture compression (2026-09-23, same laptop: with uncompressed textures the 4 GB card was full, 4034 MiB, the
-- driver spilled into system RAM and the machine swapped 34k pages in a 40 s walk; compressed, 2372 MiB and 1.2k swap-ins,
-- 51 -> 54 fps, frames over 50 ms 34 -> 22 a minute, worst frame 292 -> 120 ms). Tick boxes go by name -> true / false.
local LOW_END_STOCK = { lightingFPS = 2, UIRenderFPS = 3, texcompress = true }
local function withValues(base, extra)
    local t = {}
    for k, v in pairs(base) do t[k] = v end
    for k, v in pairs(extra) do t[k] = v end
    return t
end
local PROFILES = {
    {
        button = "Low-end hardware (4 cores or less)",
        tip = "Turns the master switch on and picks the settings measured on a 4-core CPU with an old GPU "
           .. "(Core i5-6300HQ / GTX 960M, 2026-09-21): no chunk worker pool (its threads took the game thread's core), "
           .. "trees baked only while walking (while driving a chunk texture lives seconds, and baking its trees cost "
           .. "more than drawing them per frame), and on the Display page lighting updates 10/s and the UI redrawn 30 "
           .. "times a second (the lighting thread and the Lua UI were the next biggest users of the four cores). "
           .. "Everything else goes back to the build's default. 120 km/h drive 44 -> 68 fps, walking 49 -> 81 "
           .. "(p99 80 -> 40 ms / 69 -> 30 ms). It also turns on texture compression (Display page), which kept a 4 GB "
           .. "graphics card from filling up and the machine from swapping (worst frame 292 -> 120 ms, 2026-09-23). The G1 "
           .. "collector these numbers need is now the default (gcMode). See docs/archive/2026-09-24/results.md.",
        values = LOW_END_VALUES,
        stock = LOW_END_STOCK,
    },
    {
        button = "Low-end hardware + FSR 1.0 upscaling",
        tip = "The Low-end hardware set above, plus the world rendered at 67 % of the screen per axis (44 % of the "
           .. "pixels) and scaled back up with AMD FidelityFX Super Resolution 1.0, which runs on any GPU; the "
           .. "interface, text and cursor stay at full resolution. For a machine whose GPU is the wall as well as "
           .. "its CPU: measured on the same Core i5-6300HQ / GTX 960M at 1920x1080 (2026-09-22, docs/archive/2026-09-24/results.md) "
           .. "the GPU-bound scenes gain the most. Everything else goes back to the build's default; G1 "
           .. "collector these numbers need is now the default (gcMode).",
        values = withValues(LOW_END_VALUES, { upscaler = "fsr1", upscalerQuality = "quality" }),
        stock = LOW_END_STOCK,
    },
}

-- Builds a lazily built settings page now (set below, after buildSettingsPage).
local ensurePageBuilt
-- The three pages (set below, after the buttons they name).
local PAGES

local function applyProfile(self, profile)
    local master = self.pzoptMaster
    if master and master.control.enable then
        master.control:setSelected(1, true)
        master:invokeOnChangeEvent()
    end
    for _, option in ipairs(self.pzoptOptions) do
        option:pzoptSet(profile.values[option.pzoptKey])
        option:invokeOnChangeEvent()
    end
    -- the upscaler keys live on the Enhancements tab: build it if it was never shown, then set them the same way
    -- (a profile that picks an upscaler also turns that tab's master switch on)
    ensurePageBuilt(self, ENHANCEMENTS_TAB)
    local enhancements = self.pzoptEnhancementMaster
    if profile.values.upscaler and enhancements and enhancements.control.enable then
        enhancements.control:setSelected(1, true)
        enhancements:invokeOnChangeEvent()
    end
    for _, option in ipairs(self.pzoptEnhancementOptions or {}) do
        if option.pzoptProfile then
            option:pzoptSet(profile.values[option.pzoptKey])
            option:invokeOnChangeEvent()
        end
    end
    for name, index in pairs(profile.stock or {}) do
        local option = self.gameOptions:get(name)
        local box = option and option.control
        if box and type(index) == "boolean" and box.setSelected then
            box:setSelected(1, index)
            option:invokeOnChangeEvent()
        elseif name == "texcompress" and not option then
            -- the game hides the tick box where the GPU cannot compress textures: nothing to set
        elseif box and box.options and box.options[index] then
            box.selected = index
            option:invokeOnChangeEvent()
        else
            print("[pzopt] options tab: profile could not set stock option " .. name)
        end
    end
end

-- "Uninstall PZ Optimization": pzopt.Uninstall puts the launcher settings back now and starts a helper that deletes the
-- installed files once the game has quit (the classes cannot go while it runs), then the game quits the stock way.
-- Main menu only: in a world the quit would skip the save.
local UNINSTALL_TITLE = "Uninstall PZ Optimization..."
local UNINSTALL_TIP = "Closes the game, then removes every file the installer or the updater put into the game folder and puts "
    .. "back the launcher settings it changed: the next launch is the stock game. projectzomboid.jar was never modified. "
    .. "Your settings under Zomboid/pzopt/ stay for a reinstall. Do this before you unsubscribe from the Workshop item."
-- ISModalDialog draws plain text: lines broken by hand ("\n"), no rich-text tags
local UNINSTALL_CONFIRM = "Uninstall PZ Optimization?\n\n"
    .. "The game closes now. Once it has, every file the installer\n"
    .. "or the updater put into the game folder is removed and the\n"
    .. "launcher settings it changed are put back: the next launch\n"
    .. "is the stock game. Your settings in Zomboid/pzopt/ stay.\n\n"
    .. "Afterwards you can unsubscribe from the Workshop item."

local function showUninstallResult(text)
    local modal = ISModalDialog:new(getCore():getScreenWidth() / 2 - 200, getCore():getScreenHeight() / 2 - 60, 400, 120,
        text, false, nil, nil)
    modal:initialise()
    modal:setCapture(true)
    modal:setAlwaysOnTop(true)
    modal:addToUIManager()
end

local function onUninstallConfirm(target, button)
    if button.internal ~= "YES" then return end
    local ok, started = pcall(function() return perf():pzoptUninstall() end)
    if ok and started then
        MainScreen.instance:quitToDesktop()
        return
    end
    local msg = ""
    pcall(function() msg = perf():getPzoptUninstallMessage() end)
    showUninstallResult("Nothing was removed:\n" .. tostring(msg):gsub(": ", ":\n"))
end

-- The home page's uninstall button (made by buildPage): the confirmation dialog, disabled in a world or when
-- pzopt.Uninstall says it cannot run.
local function setupUninstallButton(self, b)
    b.onclick = function()
        if MainScreen.instance and MainScreen.instance.inGame then return end
        local w, h = 420, 200
        local modal = ISModalDialog:new(getCore():getScreenWidth() / 2 - w / 2, getCore():getScreenHeight() / 2 - h / 2, w, h,
            UNINSTALL_CONFIRM, true, self, onUninstallConfirm)
        modal:initialise()
        modal:setCapture(true)
        modal:setAlwaysOnTop(true)
        modal:addToUIManager()
        self.pzoptUninstallModal = modal
        local joypadData = JoypadState.getMainMenuJoypad()
        if joypadData then
            modal.prevFocus = joypadData.focus
            joypadData.focus = modal
            updateJoypadFocus(joypadData)
        end
    end
    self.pzoptUninstallButton = b
    local why = ""
    pcall(function() why = perf():getPzoptUninstallUnavailable() end)
    if MainScreen.instance and MainScreen.instance.inGame then
        why = "go back to the main menu first (quitting from a world would skip the save)"
    end
    if why ~= "" then
        b:setEnable(false)
        b.tooltip = UNINSTALL_TIP .. " Not available now: " .. why .. "."
    else
        b.tooltip = UNINSTALL_TIP
    end
end

-- devUninstallDrive (dev rig, Config key; harness/uninstall-e2e.sh): once the main menu is up, the real controls in
-- order: Options, the Optimizations tab, Uninstall PZ Optimization..., Yes. Each step is logged ("[pzopt-e2e] ...") and
-- held for a few seconds so the script can take a screenshot; Yes starts pzopt.Uninstall and quits the game.
local uninstallDrive = { step = 0, at = 0 }
local function uninstallDriveTick()
    local d = uninstallDrive
    if d.step < 0 then return end
    if d.step == 0 then
        local ok, v = pcall(function() return getPerformance():getPzoptOption("devUninstallDrive") end)
        if not ok or v ~= "true" then d.step = -1; return end
        d.step, d.at = 1, getTimestampMs() + 4000
        return
    end
    if getTimestampMs() < d.at then return end
    local ms = MainScreen.instance
    if not ms or ms.inGame then return end
    local mo = ms.mainOptions
    if d.step == 1 then
        if not ms.optionsOption then return end
        MainScreen.onMenuItemMouseDownMainMenu(ms.optionsOption, 0, 0)
        d.step, d.at = 2, getTimestampMs() + 1500
    elseif d.step == 2 then
        if not mo or not mo.tabs then return end
        mo.tabs:activateView(TAB)
        PzoptOptionsNavigate(mo, "home")
        d.step, d.at = 3, getTimestampMs() + 1500
    elseif d.step == 3 then
        local b = mo and mo.pzoptUninstallButton
        if not b then
            print("[pzopt-e2e] uninstall drive: no Uninstall button on the " .. TAB .. " tab")
            d.step = -1
            return
        end
        print("[pzopt-e2e] tab shown: button \"" .. tostring(b.title) .. "\" enabled=" .. tostring(b.enable) .. " visible="
            .. tostring(b:isReallyVisible()) .. " tooltip=" .. tostring(b.tooltip))
        getCore():TakeFullScreenshot("pzopt-e2e-A-tab.png")
        d.step, d.at = 4, getTimestampMs() + 3000
    elseif d.step == 4 then
        mo.pzoptUninstallButton.onclick()
        d.step, d.at = 5, getTimestampMs() + 1500
    elseif d.step == 5 then
        local m = mo.pzoptUninstallModal
        if not m then
            print("[pzopt-e2e] uninstall drive: the button opened no dialog")
            d.step = -1
            return
        end
        print("[pzopt-e2e] dialog shown: " .. string.gsub(tostring(m.text), "\n", " | "))
        getCore():TakeFullScreenshot("pzopt-e2e-A-dialog.png")
        d.step, d.at = 6, getTimestampMs() + 3000
    elseif d.step == 6 then
        local m = mo.pzoptUninstallModal
        d.step = -1
        print("[pzopt-e2e] pressing Yes at " .. getTimestampMs())
        m:onClick(m.yes)
    end
end
Events.OnFETick.Add(function() pcall(uninstallDriveTick) end)

-- Export / import (2026-10-01), on each of the three tabs, covering all three: the settings as `key=value` lines, only
-- those that differ from the build's default (an absent key is the default, as in options.ini), under one comment line
-- per tab. Export takes the controls as they are now (changes not applied yet included; a setting pinned by
-- pzopt.properties or -D gives the player's own saved choice), copies the text to the clipboard and writes it to
-- Zomboid/pzopt/settings-export.ini. Import sets the controls of all three tabs from pasted text (the box starts with the
-- clipboard, else that file): the listed settings to their value, every other one back to the default, pinned ones
-- untouched; Apply / Accept saves them like the profile buttons. options.ini itself pastes as well.
local EXPORT_TITLE = "Export settings"
local IMPORT_TITLE = "Import settings..."
local EXPORT_TIP = "Copies the settings of the Optimizations, Enhancements and Profiler tabs to the clipboard as text "
    .. "(the ones that differ from the build's defaults, as the controls show them now, changes not applied yet included) "
    .. "and saves the same text to Zomboid/pzopt/settings-export.ini. Import settings... reads it back, here or on another PC."
local IMPORT_TIP = "Sets the controls of the Optimizations, Enhancements and Profiler tabs from exported text: paste it into "
    .. "the box (it starts with the clipboard, or with Zomboid/pzopt/settings-export.ini when the clipboard holds no "
    .. "settings). Settings the text does not list go back to the build's defaults. Nothing is saved until you press Apply "
    .. "or Accept."

-- The open dialog is kept in self.pzoptTransferModal (the harness's options_io rig closes it).
local function showMessage(self, text)
    local w = 480
    local shown = getTextManager():WrapText(UIFont.Small, text, w - 40)
    local modal = ISModalDialog:new(getCore():getScreenWidth() / 2 - w / 2, getCore():getScreenHeight() / 2 - 80, w, 160,
        shown, false, nil, nil)
    modal:initialise()
    modal:setCapture(true)
    modal:setAlwaysOnTop(true)
    modal:addToUIManager()
    self.pzoptTransferModal = modal
    local joypadData = JoypadState.getMainMenuJoypad()
    if joypadData then
        modal.prevFocus = joypadData.focus
        joypadData.focus = modal
        updateJoypadFocus(joypadData)
    end
end

-- A page's controls, its master switch first; every page is built first (the import sets them all).
local function pageOptions(self, page)
    ensurePageBuilt(self, page.tab)
    local t = {}
    if self[page.masterField] then table.insert(t, self[page.masterField]) end
    for _, option in ipairs(self[page.options] or {}) do table.insert(t, option) end
    return t
end

-- What the export writes for a control: nil = the build's default (left out).
local function exportValue(option)
    local p = perf()
    local key = option.pzoptKey
    if p:getPzoptOptionPinnedBy(key) ~= "" then
        local saved = p:getPzoptOptionSaved(key)
        return saved ~= "" and saved or nil
    end
    local v = option:pzoptCurrent()
    if string.sub(v, -10) == " (default)" or v == p:getPzoptOptionDefault(key) then return nil end
    -- mod compatibility's value for this launch is not a setting of the player's (left out unless they changed it)
    if option.pzoptShown ~= nil and v == option.pzoptShown and compatDecides(key) then return nil end
    return v
end

-- `key=value` per line or between ';', spaces trimmed; '#' / '!' comments and [headings] skipped; a -Dpzopt. or pzopt.
-- prefix is dropped (a pzopt.properties line pastes too). The last value of a key wins. Returns values, keys in order.
local function parseSettings(text)
    local values, keys = {}, {}
    for raw in string.gmatch(text or "", "[^\r\n;]+") do
        local line = string.match(raw, "^%s*(.-)%s*$")
        local first = string.sub(line, 1, 1)
        if line ~= "" and first ~= "#" and first ~= "!" and first ~= "[" then
            local k, v = string.match(line, "^([%w_%.%-]+)%s*[=:]%s*(.-)$")
            if k then
                k = string.gsub(k, "^%-D", "")
                k = string.gsub(k, "^pzopt%.", "")
                if values[k] == nil then table.insert(keys, k) end
                values[k] = v
            end
        end
    end
    return values, keys
end

local function knownCount(text)
    local _, keys = parseSettings(text)
    local n = 0
    for _, k in ipairs(keys) do
        if perf():isPzoptOptionKnown(k) then n = n + 1 end
    end
    return n
end

local function exportSettings(self)
    local lines, count = {}, 0
    for _, page in ipairs(PAGES) do
        local rows = {}
        for _, option in ipairs(pageOptions(self, page)) do
            local v = exportValue(option)
            if v then table.insert(rows, option.pzoptKey .. "=" .. v) end
        end
        table.sort(rows)
        table.insert(lines, "# " .. page.tab .. (#rows == 0 and ": all at the defaults" or ""))
        for _, row in ipairs(rows) do table.insert(lines, row) end
        count = count + #rows
    end
    local p = perf()
    local text = p:getPzoptSettingsExportText(table.concat(lines, "\n") .. "\n")
    Clipboard.setClipboard(text)
    local path = p:pzoptSettingsExportWrite(text)
    local what = count == 0 and "Every setting is at the build's default: the text says so."
        or (count .. (count == 1 and " setting differs" or " settings differ") .. " from the build's defaults.")
    showMessage(self, "Settings copied to the clipboard. " .. what .. "\n\n"
        .. (path ~= "" and ("Also saved to " .. path .. ".") or "The file Zomboid/pzopt/settings-export.ini could not be written (see console.txt)."))
end

-- Up to `max` names, then "and N more".
local function listSome(names, max)
    local shown = {}
    for i = 1, math.min(#names, max) do table.insert(shown, names[i]) end
    local s = table.concat(shown, ", ")
    if #names > max then s = s .. " and " .. (#names - max) .. " more" end
    return s
end

local function importSettings(self, text)
    local values, keys = parseSettings(text)
    local p = perf()
    local onTabs, set, reset, pinned, invalid = {}, 0, 0, {}, {}
    for _, page in ipairs(PAGES) do
        for _, option in ipairs(pageOptions(self, page)) do
            local key = option.pzoptKey
            onTabs[key] = true
            local v = values[key]
            if p:getPzoptOptionPinnedBy(key) ~= "" then
                if v then table.insert(pinned, key) end
            else
                if v and option.pzoptBool and v ~= "true" and v ~= "false" then
                    table.insert(invalid, key .. "=" .. v)
                    v = nil
                end
                local before = exportValue(option)
                option:pzoptSet(v)
                option:invokeOnChangeEvent()
                if v then
                    set = set + 1
                elseif before then
                    reset = reset + 1
                end
            end
        end
    end
    local ignored = {}
    for _, k in ipairs(keys) do
        if not onTabs[k] then table.insert(ignored, k) end
    end
    local msg = set .. (set == 1 and " setting" or " settings") .. " imported"
        .. (reset > 0 and (", " .. reset .. " more back to the build's default") or "") .. ". Press Apply or Accept to save them."
    if #pinned > 0 then
        msg = msg .. "\n\nPinned by pzopt.properties or -D here, left as they are: " .. listSome(pinned, 6) .. "."
    end
    if #invalid > 0 then
        msg = msg .. "\n\nNot a true / false value, set to the default: " .. listSome(invalid, 6) .. "."
    end
    if #ignored > 0 then
        msg = msg .. "\n\nNot settings of this version, ignored: " .. listSome(ignored, 6) .. "."
    end
    showMessage(self, msg)
end

local function onImportOk(self, button)
    if button.internal ~= "OK" then return end
    local text = button.parent.entry:getText()
    if knownCount(text) == 0 then
        showMessage(self, "No settings found in the text: nothing was changed. Paste the text Export settings copied "
            .. "(key=value lines, e.g. treesInChunkTexture=false).")
        return
    end
    importSettings(self, text)
end

local function openImportDialog(self)
    local text = Clipboard.getClipboard() or ""
    if knownCount(text) == 0 then
        text = perf():getPzoptSettingsExportFile()
        if knownCount(text) == 0 then text = "" end
    end
    -- ISTextBox puts the box at half the starting height, below the title bar
    local w, h = math.min(720, getCore():getScreenWidth() - 40), 2 * (MainOptions.style.buttonHeight + 20)
    local modal = ISTextBox:new(getCore():getScreenWidth() / 2 - w / 2, getCore():getScreenHeight() / 2 - 200, w, h,
        "Paste exported settings (settings not listed go back to the defaults):", text, self, onImportOk)
    modal:setMultipleLine(true)
    modal:setNumberOfLines(12)
    modal:setMaxLines(100000)
    modal:initialise()
    modal:setCapture(true)
    modal:setAlwaysOnTop(true)
    modal:addToUIManager()
    self.pzoptTransferModal = modal
    local joypadData = JoypadState.getMainMenuJoypad()
    if joypadData then
        modal.prevFocus = joypadData.focus
        joypadData.focus = modal
        updateJoypadFocus(joypadData)
    end
end

-- "Install DLSS files": the natives DLSS needs that a release does not carry (pzopt.UpscalerDeps, Linux x86-64 with
-- an RTX card). The button's title follows the Java side's state.
local DEPS_TITLES = {
    missing = "Install DLSS files", error = "Retry the DLSS files download", checking = "Checking the DLSS files...",
    idle = "Checking the DLSS files...", downloading = "Downloading the DLSS files 100 %", installing = "Installing the DLSS files...",
    done = "DLSS files installed: restart the game", installed = "DLSS files installed", unsupported = "DLSS files: not available here",
}
local DEPS_TIP = "Downloads the two native files NVIDIA DLSS needs into the game's natives folder (the pzopt shim and NVIDIA's "
    .. "DLSS library; releases do not carry them) and checks each one's checksum. Linux or Windows with an NVIDIA RTX card; "
    .. "FSR 1.0 needs no files. Then pick \"Upscaler\": dlss and restart the game."

local function setupUpscalerDepsButton(self, b)
    b.onclick = function()
        local s = perf():getPzoptUpscalerDepsState()
        if s == "missing" or s == "error" then perf():pzoptUpscalerDepsInstall() end
    end
    b:setEnable(false)
    pcall(function() perf():pzoptUpscalerDepsCheck() end)
    local stockUpdate = b.update
    b.update = function(o)
        stockUpdate(o)
        local ok, s = pcall(function() return perf():getPzoptUpscalerDepsState() end)
        if not ok then return end
        local title = DEPS_TITLES[s] or DEPS_TITLES.checking
        if s == "downloading" then
            title = "Downloading the DLSS files " .. tostring(perf():getPzoptUpscalerDepsProgress()) .. " %"
        end
        if o.title ~= title then
            o:setTitle(title)
            o:setWidthToTitle()
        end
        local enable = s == "missing" or s == "error"
        if o.enable ~= enable then o:setEnable(enable) end
        local msg = perf():getPzoptUpscalerDepsMessage()
        o.tooltip = msg ~= "" and (DEPS_TIP .. " Now: " .. msg .. ".") or DEPS_TIP
    end
end

-- A group's settings back to the build's defaults, its master switch on (the Visuals and Tools "Reset" buttons, a
-- category's "Reset ... to defaults"). Only the controls change; Apply / Accept saves them.
local function resetOptions(options, master)
    if master then
        master:pzoptReset()
        master:invokeOnChangeEvent()
    end
    for _, option in ipairs(options) do
        option:pzoptReset()
        option:invokeOnChangeEvent()
    end
end

-- The three groups of settings, as the tabs they were until 2026-10-04: their sections, master switch, the MainOptions
-- fields holding their controls (the profile buttons, export / import and the harness read them) and the note on what
-- Apply does. `tab` names them in the exported text.
PAGES = {
    { tab = "Optimizations", sections = SECTIONS, master = MASTER, masterField = "pzoptMaster", masterClip = "drive",
      options = "pzoptOptions",
      footer = "Performance settings take effect on the next launch; Raw mouse settings apply as soon as you press Apply. File: Zomboid/pzopt/options.ini" },
    { tab = ENHANCEMENTS_TAB, sections = ENHANCEMENT_SECTIONS, master = ENHANCEMENTS_MASTER, masterField = "pzoptEnhancementMaster",
      masterClip = "upscale", options = "pzoptEnhancementOptions",
      footer = "Visuals apply as soon as you press Apply; HDR output, per-pixel lighting, reflections, car glass, mirrors and relief on the next launch. File: Zomboid/pzopt/options.ini" },
    { tab = PROFILER_TAB, sections = PROFILER_SECTIONS, master = PROFILER_MASTER, masterField = "pzoptProfilerMaster",
      masterClip = "overlay", options = "pzoptProfilerOptions",
      footer = "Tools apply as soon as you press Apply, no restart needed. File: Zomboid/pzopt/options.ini" },
}

-- ---------------------------------------------------------------------------------------------------
-- The tab (2026-10-04): one "PZ Optimization" tab instead of Optimizations / Enhancements / Profiler.
--  Home: presets, the three groups (Performance, Visuals, Tools) with their master switches and one tile per category,
--  "Fix a problem", export / import / uninstall. A category: the sidebar (fixed, every category), its subcategory tabs
--  (Overview first; the Visuals categories show each subcategory as a card), its settings. "Fix a problem": what players
--  report, why, and the settings that help. Typing in the search box lists the best matches of every category.
--  Simple / Advanced / Everything filters what a category shows (tiers in pzopt_optimizations_layout.lua).
--  Every setting is built once as a row: its control, its label to the right, and under the label a line with its
--  tags and the first sentence of its description. relayout places the items of the page shown (rows, tiles, headings,
--  buttons), hides the rest and rebuilds the controller rows in display order; the preview panel stays on the right.

local GAP = 24
-- the page shown, kept for the session
local NAV = { kind = "home", cat = nil, sub = 0, level = "simple", problem = 1, back = "home" }
local VIEWS = { { id = "simple", title = "Simple" }, { id = "advanced", title = "Advanced" }, { id = "everything", title = "Everything" } }
local LEVEL = { simple = 1, advanced = 2, everything = 3 }
local VIEW_TIPS = {
    simple = "The settings worth knowing about: the features and the main switches.",
    advanced = "Also the settings that change how something is done; the defaults are what was measured best.",
    everything = "Also the tuning numbers (budgets, thread counts, intervals). Change them to experiment.",
}
local C_HELP = { r = 0.85, g = 0.55, b = 0.95 }
local C_AMBER = { r = 0.98, g = 0.72, b = 0.30 }
local C_EXPERT = { r = 0.95, g = 0.45, b = 0.40 }
local C_ADV = { r = 0.45, g = 0.68, b = 0.98 }

local function rgb(t) return { r = t[1], g = t[2], b = t[3] } end
local function fontH(font) return getTextManager():getFontHeight(font) end
local function textW(font, s) return getTextManager():MeasureStringX(font, s) end

local function clipText(font, s, w)
    if textW(font, s) <= w then return s end
    while #s > 1 and textW(font, s .. "...") > w do s = string.sub(s, 1, #s - 1) end
    return s .. "..."
end

-- greedy word wrap measured with MeasureStringX (the game's WrapText sometimes left a line wider than `w`, so a wrapped
-- setting name still came out cut with "..." at 1920 x 1080)
local function wrapLines(font, s, w)
    local out, line = {}, ""
    for word in string.gmatch(s or "", "%S+") do
        local t = line == "" and word or (line .. " " .. word)
        if line ~= "" and textW(font, t) > w then
            table.insert(out, line)
            line = word
        else
            line = t
        end
    end
    if line ~= "" then table.insert(out, line) end
    return out
end

local function firstSentence(s)
    s = s or ""
    local i = string.find(s, "%. ")
    if i then return string.sub(s, 1, i) end
    return s
end

-- the part of a section title in brackets: "Ambient occlusion (soft shading ...)" -> "soft shading ..."
local function bracketed(title)
    return title and string.match(title, "%((.-)%)%s*$")
end

local TIER
local function tierOf(key)
    if not TIER then
        local L = PzoptSettingsLayout or {}
        TIER = { simple = {}, expert = {}, patterns = L.expertPatterns or {} }
        for _, k in ipairs(L.simple or {}) do TIER.simple[k] = true end
        for _, k in ipairs(L.expertKeys or {}) do TIER.expert[k] = true end
    end
    if TIER.simple[key] then return 1 end
    if TIER.expert[key] then return 3 end
    for _, pattern in ipairs(TIER.patterns) do
        if string.find(key, pattern) then return 3 end
    end
    return 2
end

-- the control says something other than the build's default
local function rowChanged(row)
    local ok, v = pcall(function() return row.option:pzoptCurrent() end)
    if not ok or not v then return false end
    if string.sub(v, -10) == " (default)" then return false end
    return v ~= perf():getPzoptOptionDefault(row.entry.key)
end

local function groupOff(group)
    local m = group.master
    return m ~= nil and m.option:pzoptCurrent() == "false"
end

-- A category's status for its tile, recomputed twice a second: "Recommended" / "N changed", on Visuals "N on" / "Off".
local function catStatus(cat)
    local now = getTimestampMs()
    if cat.statusAt and now - cat.statusAt < 500 then return cat.status, cat.statusC end
    local changed, on = 0, 0
    for _, row in ipairs(cat.rows) do
        if rowChanged(row) then
            changed = changed + 1
            if row.option.pzoptBool and row.option:pzoptCurrent() == "true" then on = on + 1 end
        end
    end
    local status, c
    if groupOff(cat.group) then
        status, c = "Switched off", C_DIM
    elseif cat.group.cards then
        if on > 0 then status, c = on .. " on", C_AMBER
        elseif changed > 0 then status, c = changed .. " changed", C_AMBER
        else status, c = "Off", C_DIM end
    elseif changed > 0 then
        status, c = changed .. " changed", C_AMBER
    else
        status, c = "Recommended", C_OPT
    end
    cat.status, cat.statusC, cat.statusAt = status, c, now
    return status, c
end

local function drawPill(o, x, y, text, c, filled)
    local w, h = textW(UIFont.Small, text) + 12, fontH(UIFont.Small) + 2
    o:drawRect(x, y, w, h, filled and 0.9 or 0.18, c.r, c.g, c.b)
    o:drawRectBorder(x, y, w, h, 0.55, c.r, c.g, c.b)
    if filled then
        o:drawText(text, x + 6, y + 1, 0.05, 0.05, 0.05, 1, UIFont.Small)
    else
        o:drawText(text, x + 6, y + 1, c.r, c.g, c.b, 1, UIFont.Small)
    end
    return w
end

-- three squares filled to `n` (0..3), after a label; returns the x after them
local function drawDots(o, x, y, label, n, c)
    o:drawText(label, x, y, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
    local dx = x + textW(UIFont.Small, label) + 6
    local s = fontH(UIFont.Small) - 6
    for i = 1, 3 do
        if i <= n then
            o:drawRect(dx + (i - 1) * (s + 3), y + 3, s, s, 0.9, c.r, c.g, c.b)
        else
            o:drawRect(dx + (i - 1) * (s + 3), y + 3, s, s, 0.12, 1, 1, 1)
        end
    end
    return dx + 3 * (s + 3) + 12
end

-- The line under a setting's label: its tags (Advanced / Expert, changed, next launch, pinned, mod compatibility),
-- the first sentence of its description, and on the search and problem pages where it lives.
PzoptRowInfo = ISPanel:derive("PzoptRowInfo")

function PzoptRowInfo:new(x, y, w, h, row)
    local o = ISPanel.new(self, x, y, w, h)
    o.background = false
    o.row = row
    return o
end

function PzoptRowInfo:onMouseWheel(del) return false end
function PzoptRowInfo:prerender() end

function PzoptRowInfo:render()
    local row = self.row
    local p = perf()
    local lineH = fontH(UIFont.Small) + 2
    local y = 0
    -- the rest of a name that did not fit on its label's line
    for _, l in ipairs(row.labelMore or {}) do
        self:drawText(l, 0, y + 1, C_TEXT.r, C_TEXT.g, C_TEXT.b, 1, UIFont.Small)
        y = y + lineH
    end
    local x = 0
    if row.tier == 2 then x = x + drawPill(self, x, y, "ADVANCED", C_ADV) + 6 end
    if row.tier == 3 then x = x + drawPill(self, x, y, "EXPERT", C_EXPERT) + 6 end
    if rowChanged(row) then x = x + drawPill(self, x, y, "CHANGED", C_AMBER) + 6 end
    if not row.entry.live then x = x + drawPill(self, x, y, "next launch", C_GREY) + 6 end
    if p:getPzoptOptionPinnedBy(row.entry.key) ~= "" then x = x + drawPill(self, x, y, "pinned", C_GREY) + 6 end
    if compatReason(row.entry.key) ~= "" then x = x + drawPill(self, x, y, "off for a mod", C_STOCK) + 6 end
    local right = self.width
    if (NAV.kind == "search" or NAV.kind == "problems") and row.cat then
        -- where it lives, cut to leave the tags and a few words of the description their room
        local where = clipText(UIFont.Small, row.cat.title .. "  >  " .. row.sub.title, math.max(40, right - x - 120))
        local c = row.cat.group.c
        local ww = textW(UIFont.Small, where)
        self:drawText(where, right - ww, y + 1, c.r, c.g, c.b, 1, UIFont.Small)
        right = right - ww - 16
    end
    if right - x > 30 then
        self:drawText(clipText(UIFont.Small, firstSentence(row.entry.tip), right - x), x, y + 1,
            C_GREY.r * 0.8, C_GREY.g * 0.8, C_GREY.b * 0.8, 1, UIFont.Small)
    end
end

-- A panel that only draws (headings, notes): `draw(o)` paints it.
local function drawPanel(draw)
    local o = ISPanel:new(0, 0, 10, 10)
    o:initialise()
    o.background = false
    o.prerender = function() end
    o.render = draw
    o.onMouseWheel = function() return false end
    return o
end

-- A button that draws itself (tiles, tabs, headings): `draw(o, hot)`.
local function drawButton(target, onclick, draw)
    local b = ISButton:new(0, 0, 10, 10, "", target, onclick)
    b:initialise()
    b.prerender = function() end
    b.render = function(o) draw(o, o:isMouseOver() or o.joypadFocused) end
    return b
end

-- ---------------------------------------------------------------------------------------------------
-- Placement

local function placeRow(row, y, dx)
    dx = dx or 0
    for _, e in ipairs(row.elems) do
        e.el:setX(e.x0 + dx)
        e.el:setY(y + e.dy)
        e.el:setVisible(true)
    end
    row.hidden = false
    row.y = y
end

local function hideItem(item)
    for _, e in ipairs(item.elems) do e.el:setVisible(false) end
    item.hidden = true
end

-- Off-screen elements draw nothing. The UI renders every child of a scrolled panel each frame and lets the stencil drop
-- what is outside (the old Optimizations tab cost ~8 ms a frame on an M1 Pro against ~0.5 ms for the rows on screen,
-- 2026-09-23). An element more than CULL_MARGIN px outside the scrolled band gets no-op prerender / render instead of
-- being hidden: hidden ones would drop out of the controller rows and ensureVisible could no longer scroll to them.
local CULL_MARGIN = 50
local NOOP = function() end

local function cullElement(el, off)
    if off == (el.pzoptCulled == true) then return end
    if off then
        el.pzoptCulled = true
        el.pzoptOwnPrerender, el.pzoptOwnRender = rawget(el, "prerender"), rawget(el, "render")
        el.prerender, el.render = NOOP, NOOP
    else
        el.pzoptCulled = nil
        el.prerender, el.render = el.pzoptOwnPrerender, el.pzoptOwnRender
        el.pzoptOwnPrerender, el.pzoptOwnRender = nil, nil
    end
end

local function cullRows(S)
    local panel = S.panel
    local top = -panel:getYScroll()
    local bottom = top + panel:getHeight()
    if S.cullTop == top and S.cullBottom == bottom and S.cullGen == S.layoutGen then return end
    S.cullTop, S.cullBottom, S.cullGen = top, bottom, S.layoutGen
    for _, item in ipairs(S.items) do
        if not item.hidden then
            for _, e in ipairs(item.elems) do
                local el = e.el
                local y = el:getY()
                cullElement(el, y + el:getHeight() < top - CULL_MARGIN or y > bottom + CULL_MARGIN)
            end
        end
    end
end

local relayout

local function crumbFor(S)
    if NAV.kind == "home" then return "Home" end
    if NAV.kind == "problems" then return "Help  >  Fix a problem" end
    if NAV.kind == "search" then return "Search" end
    local cat = S.tree.catById[NAV.cat]
    if not cat then return "" end
    local sub = cat.subs[NAV.sub]
    return cat.group.title .. "  >  " .. cat.title .. "  >  " .. (sub and sub.title or "Overview")
end

-- Shows a page: kind "home" | "cat" (cat id, sub index, 0 = Overview) | "problems". Leaves a search. The controller
-- focus goes to the page's first control, or stays on `keep` (the sidebar entry that opened the page).
local function navigate(S, kind, cat, sub, keep)
    NAV.kind = kind
    if cat then NAV.cat = cat end
    NAV.sub = sub or 0
    if S.entry and S.entry:getText() ~= "" then
        S.entry:setText("")
        S.typed, S.lastText, S.hits = "", "", nil
    end
    S.panel:setYScroll(0)
    relayout(S)
    if S.panel.joyfocus then
        local target = (keep and keep:isReallyVisible()) and keep or S.firstJoy
        if target then S.panel:setJoypadFocus(target, JoypadState.getMainMenuJoypad()) end
    end
end

local function setLevel(S, level)
    NAV.level = level
    relayout(S)
end

local function runSearch(S, text)
    local hits, n = nil, 0
    if text and string.match(text, "%w") then
        hits, n = search(S.index, text)
    end
    S.hits, S.hitCount, S.query = hits, n, text
    if hits then
        if NAV.kind ~= "search" then NAV.back = NAV.kind end
        NAV.kind = "search"
    elseif NAV.kind == "search" then
        NAV.kind = NAV.back or "home"
    end
    S.panel:setYScroll(0)
    relayout(S)
end

-- The sidebar: home, every category under its group, "Fix a problem". Fixed while the page scrolls. Every entry is a
-- button, for the mouse and the controller: Left from the page reaches the entry at that height, Up / Down move along
-- the sidebar, A opens its page with the focus kept on the entry (so A, Down, A... browses the categories), Right goes
-- back into the page (buildPage wraps the page's joypad handlers for that).
PzoptSidebar = ISPanel:derive("PzoptSidebar")

function PzoptSidebar:new(x, y, w, h, S)
    local o = ISPanel.new(self, x, y, w, h)
    o.S = S
    o.backgroundColor = { r = 0.04, g = 0.04, b = 0.06, a = 1 }
    o.borderColor = { r = 0.31, g = 0.31, b = 0.35, a = 1 }
    o.headings = {}
    o.buttons = {}
    return o
end

function PzoptSidebar:onMouseWheel(del) return false end
function PzoptSidebar:onMouseDown(x, y) return true end

-- the entries as buttons (children of the sidebar), the group headings drawn by render
function PzoptSidebar:build(target)
    local S = self.S
    local hS = fontH(UIFont.Small)
    local rowH = hS + 10
    local pad = 12
    local y = pad
    local function item(label, count, selected, c, action)
        local b = ISButton:new(4, y, self.width - 8, rowH, "", target, function(_, button) action(button) end)
        b:initialise()
        b.pzoptFixed = true -- not part of the scrolled page (ensureVisible leaves the scroll alone)
        b.pzoptLabel = label
        b.pzoptFits = textW(UIFont.Small, label) <= (self.width - 8) - 2 * pad - (count and (textW(UIFont.Small, count) + pad) or 0)
        b.prerender = function() end
        b.render = function(o)
            local sel = selected()
            local hot = o:isMouseOver() or o.joypadFocused
            if sel then
                o:drawRect(0, 0, o.width, o.height, 0.18, c.r, c.g, c.b)
                o:drawRect(0, 0, 4, o.height, 1, c.r, c.g, c.b)
            end
            if hot then o:drawRect(0, 0, o.width, o.height, 0.08, 1, 1, 1) end
            if o.joypadFocused then o:drawRectBorder(0, 0, o.width, o.height, 0.9, c.r, c.g, c.b) end
            local countW = count and (textW(UIFont.Small, count) + pad) or 0
            local col = (sel or hot) and C_TEXT or { r = 0.8, g = 0.8, b = 0.82 }
            o:drawText(clipText(UIFont.Small, label, o.width - 2 * pad - countW), pad + 2, 5, col.r, col.g, col.b, 1, UIFont.Small)
            if count then o:drawTextRight(count, o.width - pad + 4, 5, C_DIM.r, C_DIM.g, C_DIM.b, 1, UIFont.Small) end
        end
        self:addChild(b)
        table.insert(self.buttons, b)
        y = y + rowH
    end
    item("<  Home", nil, function() return NAV.kind == "home" end, C_TEXT, function() navigate(S, "home") end)
    y = y + 6
    for _, g in ipairs(S.tree.groups) do
        table.insert(self.headings, { y = y, g = g })
        y = y + hS + 4
        for _, cat in ipairs(g.cats) do
            local id = cat.id
            item(cat.title, tostring(#cat.rows), function() return NAV.kind == "cat" and NAV.cat == id end, g.c,
                function(b) navigate(S, "cat", id, 0, b) end)
        end
        y = y + 8
    end
    table.insert(self.headings, { y = y, help = true })
    y = y + hS + 4
    item("Fix a problem", nil, function() return NAV.kind == "problems" end, C_HELP, function(b) navigate(S, "problems", nil, nil, b) end)
end

function PzoptSidebar:render()
    local pad = 12
    for _, h in ipairs(self.headings) do
        if h.help then
            self:drawText("HELP", pad, h.y, C_HELP.r, C_HELP.g, C_HELP.b, 1, UIFont.Small)
        else
            local g = h.g
            self:drawText(string.upper(g.title), pad, h.y, g.c.r, g.c.g, g.c.b, 1, UIFont.Small)
            if groupOff(g) then
                self:drawTextRight("off", self.width - pad, h.y, C_STOCK.r, C_STOCK.g, C_STOCK.b, 1, UIFont.Small)
            end
        end
    end
end

-- ---------------------------------------------------------------------------------------------------
-- The tree: groups -> categories -> subcategories -> rows, from PzoptSettingsLayout and the rows built.

local function buildTree(rows, masterRows)
    local L = PzoptSettingsLayout
    local tree = { groups = {}, catById = {}, problems = {} }
    local claimed = {}
    local function add(sub, row)
        claimed[row] = true
        row.cat, row.sub, row.group = sub.cat, sub, sub.cat.group
        table.insert(sub.rows, row)
        table.insert(sub.cat.rows, row)
    end
    local byKey = {}
    for _, row in ipairs(rows) do byKey[row.entry.key] = row end
    for _, g in ipairs(L.groups) do
        local group = { id = g.id, title = g.title, page = g.page, c = rgb(g.colour), note = g.note, cards = g.cards,
                        cats = {}, master = masterRows[g.page], count = 0 }
        table.insert(tree.groups, group)
        for _, c in ipairs(g.cats) do
            local cat = { id = c.id, title = c.title, blurb = c.blurb, group = group, subs = {}, rows = {} }
            table.insert(group.cats, cat)
            tree.catById[cat.id] = cat
            for _, s in ipairs(c.subs) do
                local sub = { title = s.title, cat = cat, rows = {}, dlss = s.dlssButton }
                table.insert(cat.subs, sub)
                if s.sections then
                    for _, row in ipairs(rows) do
                        if not claimed[row] and row.page == g.page then
                            for _, prefix in ipairs(s.sections) do
                                if string.sub(row.section.title, 1, #prefix) == prefix then
                                    add(sub, row)
                                    sub.blurb = sub.blurb or bracketed(row.section.title)
                                    break
                                end
                            end
                        end
                    end
                else
                    for _, key in ipairs(s.keys or {}) do
                        local row = byKey[key]
                        if row and not claimed[row] then add(sub, row) end
                    end
                end
            end
        end
    end
    -- a key no subcategory lists: the "More" subcategory of the category its section maps to
    for _, row in ipairs(rows) do
        if not claimed[row] then
            local cat
            for _, h in ipairs(L.home or {}) do
                if string.sub(row.section.title, 1, #h[1]) == h[1] then cat = tree.catById[h[2]]; break end
            end
            if not cat then
                for _, g in ipairs(tree.groups) do
                    if g.page == row.page then cat = g.cats[#g.cats] end
                end
            end
            if not cat.more then
                cat.more = { title = "More", cat = cat, rows = {} }
                table.insert(cat.subs, cat.more)
            end
            add(cat.more, row)
        end
    end
    for _, g in ipairs(tree.groups) do
        for _, cat in ipairs(g.cats) do
            g.count = g.count + #cat.rows
            local subs = {}
            for _, s in ipairs(cat.subs) do table.insert(subs, s.title) end
            cat.subList = table.concat(subs, ", ")
        end
    end
    for _, pr in ipairs(L.problems or {}) do
        local problem = { title = pr.title, cause = pr.cause, why = pr.why, rows = {} }
        for _, key in ipairs(pr.keys or {}) do
            if byKey[key] then table.insert(problem.rows, byKey[key]) end
        end
        table.insert(tree.problems, problem)
    end
    return tree
end

local function visibleRows(list, level)
    local out = {}
    local limit = LEVEL[level] or 1
    for _, row in ipairs(list) do
        if row.tier <= limit then table.insert(out, row) end
    end
    return out
end

-- ---------------------------------------------------------------------------------------------------
-- relayout: the page in NAV

local layoutPage = {} -- the page kinds' content (below relayout)

relayout = function(S)
    local G, panel = S.G, S.panel
    local hS, hM, hL, BH, SP = G.hS, G.hM, G.hL, G.BH, G.SP
    local shown, joy = {}, {}
    local firstContentLine
    local function line(...)
        local l = {}
        for i = 1, select("#", ...) do
            local el = select(i, ...)
            if el then table.insert(l, el) end
        end
        if #l > 0 then table.insert(joy, l) end
    end
    local function rowAt(row, y, dx)
        placeRow(row, y, dx)
        shown[row] = true
        for _, l in ipairs(row.option and (row.option.pzoptJoyLines or { { row.option.control } }) or row.joy or {}) do
            table.insert(joy, l)
        end
        return y + row.step
    end
    local function at(item, x, y, w, h)
        local el = item.elems[1].el
        el:setX(x)
        el:setY(y)
        if w then el:setWidth(w) end
        if h then el:setHeight(h) end
        el:setVisible(true)
        item.hidden = false
        shown[item] = true
        return el
    end
    local kind = NAV.kind
    if kind == "cat" and not S.tree.catById[NAV.cat] then kind = "home"; NAV.kind = "home" end
    local home = kind == "home"
    local x0 = home and G.homeX or G.contentX
    local x1 = home and G.homeR or G.contentR
    local w = x1 - x0
    S.crumb = crumbFor(S)

    -- header: Home, the title and where we are; under them the search box, the view switch, the status and the clips
    -- switch. On a narrow window (1920 x 1080) the clips switch moves to the end of the title line and the status goes
    -- when they do not fit on the search line.
    local clipsW = S.clips:getWidth() + 8 + S.clipsLabel:getWidth()
    local viewsW = S.viewLabel.elems[1].el:getWidth() + 10
    for _, v in ipairs(S.viewButtons) do viewsW = viewsW + v.elems[1].el:getWidth() + 4 end
    local status = ""
    if kind == "search" then
        status = S.hitCount > 0 and (S.hitCount .. " of " .. #S.searchRows .. " settings match") or "Nothing matches"
    elseif kind == "home" then
        status = #S.searchRows .. " settings"
    end
    S.status:setName(status)
    local statusW = status ~= "" and (S.status:getWidth() + 16) or 0
    local clipsOnTitle = G.searchW + 24 + viewsW + statusW + 24 + clipsW > w
    local y = G.m
    local tx = x0
    local homeEl
    if not home then
        homeEl = at(S.homeButton, x0, y + math.floor((hL + 4 - BH) / 2))
        tx = x0 + homeEl:getWidth() + 16
    end
    local titleR = clipsOnTitle and (x1 - clipsW - 24) or x1
    at(S.title, tx, y, math.max(20, titleR - tx), hL + 4)
    if clipsOnTitle then
        local cy = y + math.floor((hL + 4 - BH) / 2)
        at(S.clipsLabelItem, x1 - S.clipsLabel:getWidth(), cy)
        at(S.clipsItem, x1 - clipsW, cy)
        line(homeEl, S.clips)
    else
        line(homeEl)
    end
    y = y + hL + 4 + SP * 2
    if not clipsOnTitle then
        at(S.clipsLabelItem, x1 - S.clipsLabel:getWidth(), y)
        at(S.clipsItem, x1 - clipsW, y)
    end
    at(S.searchItem, x0, y)
    local vx = x0 + G.searchW + 24
    at(S.viewLabel, vx, y)
    vx = vx + S.viewLabel.elems[1].el:getWidth() + 10
    local views = {}
    for _, v in ipairs(S.viewButtons) do
        local el = at(v, vx, y)
        vx = vx + el:getWidth() + 4
        table.insert(views, el)
    end
    local lineR = clipsOnTitle and x1 or (x1 - clipsW - 24)
    if statusW > 0 and vx + statusW <= lineR then at(S.statusItem, vx + 16, y) end
    line(S.entry, views[1], views[2], views[3], (not clipsOnTitle) and S.clips or nil)
    y = y + BH + SP * 2
    local rules = 0
    local function rule(yy)
        rules = rules + 1
        at(rules == 1 and S.rule or S.rule2, x0, yy, w, 1)
    end
    rule(y)
    y = y + SP * 2 + 4
    local contentLine = #joy + 1

    local page = layoutPage[kind]
    if page then
        y = page({ S = S, G = G, x0 = x0, x1 = x1, w = w, joy = joy, line = line, rowAt = rowAt, at = at, rule = rule }, y)
    end

    -- the sidebar and the preview: on every page but home; the preview shows a setting of the page shown
    S.sidebar:setVisible(not home)
    S.preview:setVisible(not home)
    if not home and (not S.preview.row or not shown[S.preview.row]) then
        for _, row in ipairs(S.keyRows) do
            if shown[row] and not row.isMaster then
                S.preview:select(row)
                break
            end
        end
    end
    for _, item in ipairs(S.items) do
        if not shown[item] and not item.hidden then hideItem(item) end
        if not shown[item] then item.hidden = true end
    end
    panel:setScrollHeight(y + 20)
    local maxScroll = math.max(0, y + 20 - panel:getHeight())
    if -panel:getYScroll() > maxScroll then panel:setYScroll(-maxScroll) end
    -- the sidebar's entries after the page's rows (reached with Left; Up / Down stay on their side, see buildPage)
    if not home then
        for _, b in ipairs(S.sidebar.buttons) do line(b) end
    end
    -- controller navigation: the rows in display order
    -- (the stock spatial navigation searches allJoypadButtons, which only insertNewLineOfButtons fills: same elements)
    for i = #panel.joypadButtonsY, 1, -1 do table.remove(panel.joypadButtonsY, i) end
    panel.allJoypadButtons = {}
    for _, l in ipairs(joy) do
        table.insert(panel.joypadButtonsY, l)
        for _, el in ipairs(l) do table.insert(panel.allJoypadButtons, el) end
    end
    panel.joypadButtons = panel.joypadButtonsY[#panel.joypadButtonsY]
    if (panel.joypadIndexY or 1) > #panel.joypadButtonsY then
        panel.joypadIndexY = #panel.joypadButtonsY
        panel.joypadIndex = 1
    end
    local first = joy[contentLine] or joy[1]
    S.firstJoy = first and first[1]
    S.layoutGen = (S.layoutGen or 0) + 1 -- cullRows looks again
end

-- The content of each page kind below the header, one function each: the game's Lua compiler fails a function that
-- declares more than 200 locals in a -debug game (Core.debug records each local's line in a 200-slot array indexed by
-- every local declared so far), and relayout with all four pages inline declared 201 (issue #58, 2026-10-04;
-- scripts/LuaDebugCompile.java checks it in build.sh). C = the page geometry and relayout's placing helpers; each
-- returns the y under its content.
function layoutPage.home(C, y)
    local S, G, x0, x1, w, joy = C.S, C.G, C.x0, C.x1, C.w, C.joy
    local at, line, rowAt, rule = C.at, C.line, C.rowAt, C.rule
    local hS, hM, BH, SP = G.hS, G.hM, G.BH, G.SP
    -- presets (wrapping to a second row on a narrow window)
    at(S.presetLabel, x0, y)
    local px0 = x0 + S.presetLabel.elems[1].el:getWidth() + 16
    local px = px0
    local pl = {}
    for _, b in ipairs(S.presetButtons) do
        local bw = b.elems[1].el:getWidth()
        if px + bw > x1 and px > px0 then
            line(unpack(pl))
            pl = {}
            px = px0
            y = y + BH + SP
        end
        local el = at(b, px, y)
        px = px + bw + 8
        table.insert(pl, el)
    end
    line(unpack(pl))
    y = y + BH + SP * 3
    local cols = w >= 1800 and 4 or (w >= 1150 and 3 or 2)
    local tileW = math.floor((w - (cols - 1) * 12) / cols)
    local tileH = hM + 3 * hS + 34
    for _, g in ipairs(S.tree.groups) do
        at(g.heading, x0, y, w, hM + 6)
        y = y + hM + 6 + SP
        if g.master then
            rowAt(g.master, y, x0 - G.contentX)
            if g.reset then
                local el = at(g.reset, 0, y)
                el:setX(x1 - el:getWidth())
                table.insert(joy[#joy], el)
            end
            y = y + g.master.step + SP
        end
        local col = 0
        local tl = {}
        for _, cat in ipairs(g.cats) do
            table.insert(tl, at(cat.tile, x0 + col * (tileW + 12), y, tileW, tileH))
            col = col + 1
            if col == cols then
                col = 0
                y = y + tileH + 12
                line(unpack(tl))
                tl = {}
            end
        end
        if col ~= 0 then
            y = y + tileH + 12
            line(unpack(tl))
        end
        y = y + SP * 3
    end
    at(S.helpHeading, x0, y, w, hM + 6)
    y = y + hM + 6 + SP
    line(at(S.problemTile, x0, y, tileW, tileH))
    y = y + tileH + SP * 4
    rule(y)
    y = y + SP * 2
    local bx = x0
    local bl = {}
    for _, b in ipairs(S.toolButtons) do
        local el = at(b, bx, y)
        bx = bx + el:getWidth() + 8
        table.insert(bl, el)
    end
    line(unpack(bl))
    y = y + BH + SP
    local noteH = #wrapLines(UIFont.Small, S.homeNoteText, w) * hS + 4
    at(S.homeNote, x0, y, w, noteH)
    y = y + noteH
    return y
end

function layoutPage.cat(C, y)
    local S, x0, x1, w = C.S, C.x0, C.x1, C.w
    local at, line, rowAt, rule = C.at, C.line, C.rowAt, C.rule
    local hS, hM, hL, BH, SP = C.G.hS, C.G.hM, C.G.hL, C.G.BH, C.G.SP
    local cat = S.tree.catById[NAV.cat]
    local g = cat.group
    local headH = hL + hS + 10 + (groupOff(g) and (hS + 6) or 0)
    S.catHead.elems[1].el.cat = cat
    at(S.catHead, x0, y, w, headH)
    y = y + headH + SP
    -- the subcategory tabs (wrapping when they do not fit)
    local tabX, tl = x0, {}
    for i, t in ipairs(cat.tabs) do
        local el = t.elems[1].el
        if tabX + el:getWidth() > x1 and tabX > x0 then
            line(unpack(tl))
            tl = {}
            tabX = x0
            y = y + hM + 14
        end
        at(t, tabX, y)
        tabX = tabX + el:getWidth() + 4
        table.insert(tl, el)
        if i == 1 then tabX = tabX + 8 end
    end
    line(unpack(tl))
    y = y + hM + 14
    at(S.tabRule, x0, y - 2, w, 1)
    y = y + SP * 3
    if NAV.sub == 0 then
        for _, sub in ipairs(cat.subs) do
            local rows = visibleRows(sub.rows, NAV.level)
            local hidden = #sub.rows - #rows
            if g.cards then
                -- a card: title, what it is, cost; its settings inside; "All N settings" opens its tab
                local cardY = y
                local headH2 = hM + hS + 22
                local card = at(sub.card, x0, cardY, w, 10)
                local ob = at(sub.open, 0, cardY + 10)
                ob:setX(x1 - 12 - ob:getWidth())
                line(ob)
                local ry = cardY + headH2
                if sub.dlssRow then ry = rowAt(sub.dlssRow, ry, 16) end
                for _, row in ipairs(rows) do ry = rowAt(row, ry, 16) end
                card:setHeight(ry - cardY + 8)
                y = ry + 8 + SP * 3
            else
                local hb = at(sub.head, x0, y, w, hM + 8)
                sub.headHidden = hidden
                line(hb)
                y = y + hM + 8 + SP
                for _, row in ipairs(rows) do y = rowAt(row, y) end
                y = y + SP * 3
            end
        end
    else
        local sub = cat.subs[NAV.sub]
        if sub then
            if sub.dlssRow then y = rowAt(sub.dlssRow, y) end
            local rows = visibleRows(sub.rows, NAV.level)
            for _, row in ipairs(rows) do y = rowAt(row, y) end
            local hidden = #sub.rows - #rows
            if hidden > 0 then
                y = y + SP * 2
                local next = NAV.level == "simple" and "Advanced" or "Everything"
                local el = at(S.moreButton, x0, y)
                el:setTitle("Show " .. hidden .. " more " .. (hidden == 1 and "setting" or "settings") .. " (" .. next .. " view)")
                el:setWidthToTitle()
                line(el)
                y = y + BH + SP
            end
        end
    end
    y = y + SP * 2
    rule(y)
    y = y + SP * 2
    local rb = at(S.catReset, x0, y)
    rb:setTitle("Reset " .. cat.title .. " to defaults")
    rb:setWidthToTitle()
    line(rb)
    S.catNote.elems[1].el.text = PAGES[g.page].footer
    at(S.catNote, x0 + rb:getWidth() + 16, y + math.floor((BH - hS) / 2), x1 - x0 - rb:getWidth() - 16, hS + 2)
    y = y + BH + SP
    return y
end

function layoutPage.problems(C, y)
    local S, x0, w = C.S, C.x0, C.w
    local at, line, rowAt = C.at, C.line, C.rowAt
    local hS, hM, hL, SP = C.G.hS, C.G.hM, C.G.hL, C.G.SP
    at(S.probHead, x0, y, w, hL + hS + 10)
    y = y + hL + hS + 10 + SP * 2
    local cols = w >= 1300 and 3 or 2
    local tileW = math.floor((w - (cols - 1) * 10) / cols)
    local tileH = hM + 2 * hS + 26
    local col, tl = 0, {}
    for _, pr in ipairs(S.tree.problems) do
        table.insert(tl, at(pr.tile, x0 + col * (tileW + 10), y, tileW, tileH))
        col = col + 1
        if col == cols then
            col = 0
            y = y + tileH + 10
            line(unpack(tl))
            tl = {}
        end
    end
    if col ~= 0 then
        y = y + tileH + 10
        line(unpack(tl))
    end
    y = y + SP * 3
    local pr = S.tree.problems[NAV.problem] or S.tree.problems[1]
    if pr then
        local lines = wrapLines(UIFont.Small, pr.why, w - 56)
        local whyH = hM + 16 + #lines * hS + 12
        S.probWhy.elems[1].el.problem, S.probWhy.elems[1].el.lines = pr, lines
        at(S.probWhy, x0, y, w, whyH)
        y = y + whyH + SP * 2
        for _, row in ipairs(pr.rows) do y = rowAt(row, y) end
    end
    return y
end

function layoutPage.search(C, y)
    local S, x0, w = C.S, C.x0, C.w
    local at, rowAt = C.at, C.rowAt
    local hS, hM, SP = C.G.hS, C.G.hM, C.G.SP
    S.searchHead.elems[1].el.text = (S.hitCount or 0) > 0
        and ((S.hitCount == 1 and "1 setting matches" or (S.hitCount .. " settings match")) .. " \"" .. (S.query or "") .. "\", best first")
        or ("Nothing matches \"" .. (S.query or "") .. "\"")
    at(S.searchHead, x0, y, w, hM + 6)
    y = y + hM + 6 + SP * 2
    local hits = S.hits or {}
    -- the categories by their best match, the matches inside by score
    local cats = {}
    for _, g in ipairs(S.tree.groups) do
        for _, cat in ipairs(g.cats) do
            local list, best = {}, 0
            for _, row in ipairs(cat.rows) do
                local s = hits[row]
                if s then
                    table.insert(list, row)
                    if s > best then best = s end
                end
            end
            if #list > 0 then
                table.sort(list, function(a, b)
                    if hits[a] ~= hits[b] then return hits[a] > hits[b] end
                    return a.index < b.index
                end)
                table.insert(cats, { cat = cat, rows = list, best = best })
            end
        end
    end
    table.sort(cats, function(a, b) return a.best > b.best end)
    for _, c in ipairs(cats) do
        at(c.cat.resultHead, x0, y, w, hS + 8)
        y = y + hS + 8 + SP
        for _, row in ipairs(c.rows) do y = rowAt(row, y) end
        y = y + SP * 2
    end
    return y
end

-- ---------------------------------------------------------------------------------------------------
-- Building the page (once, the first time the tab is shown)

-- Parts of buildPage, one function each: the game's Lua compiler fails a function that declares more than 200 locals
-- in a -debug game, and buildPage in one piece declared 216 (issue #58, 2026-10-04; scripts/LuaDebugCompile.java checks
-- it in build.sh). B = the page being built and buildPage's element helpers.
local pageBuild = {}

function pageBuild.header(B)
    local self, S, G, single, buttonItem, label = B.self, B.S, B.G, B.single, B.buttonItem, B.label
    local BH, hS, hM, hL = G.BH, G.hS, G.hM, G.hL
    S.homeButton = buttonItem("<  Home", "Back to the start page: presets, every category, export / import.",
        function() navigate(S, "home") end)
    S.title = single(drawPanel(function(o)
        o:drawText("PZ OPTIMIZATION", 0, 0, 1, 1, 1, 1, UIFont.Large)
        local x = textW(UIFont.Large, "PZ OPTIMIZATION") + 24
        -- where we are; left out when only a stub of it would fit (1920 x 1080: the sidebar and the heading say it too)
        local crumb = clipText(UIFont.Medium, S.crumb or "", math.max(20, o.width - x))
        if #crumb >= 12 or crumb == (S.crumb or "") then
            o:drawText(crumb, x, math.floor((hL - hM) / 2) + 2, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Medium)
        end
    end))
    -- the search box takes what the view switch leaves on the search line (the text box cannot resize later)
    local viewsW = textW(UIFont.Small, "View") + 10
    for _, v in ipairs(VIEWS) do viewsW = viewsW + textW(UIFont.Small, v.title) + 28 + 4 end
    G.searchW = math.max(140, math.min(560, G.contentR - G.contentX - viewsW - 24))
    local entry = ISTextEntryBox:new("", 0, 0, G.searchW, BH)
    entry:initialise()
    entry:instantiate()
    entry:setClearButton(true)
    entry.tooltip = "Type words from a setting's name, description or key, a resource (gpu, vram, game thread, "
        .. "load time...) or a Java class that reads it (FBORenderCell, IsoChunk, pzopt.FogPass...). Typos and "
        .. "partial words are fine; the best matches of every category come first."
    S.typed, S.lastText, S.typedAt = "", "", 0
    -- polled each frame (the clear button and pasting do not all go through onTextChange) and searched once the
    -- text has been still for 120 ms, so typing a word runs one search, not one per letter
    entry.prerender = function(o)
        ISTextEntryBox.prerender(o)
        local text, now = o:getText(), getTimestampMs()
        if text ~= S.typed then
            S.typed, S.typedAt = text, now
        elseif text ~= S.lastText and now - S.typedAt >= 120 then
            S.lastText = text
            runSearch(S, text)
        end
    end
    -- what to type, while the box is empty and not focused
    entry.render = function(o)
        ISTextEntryBox.render(o)
        if o:getText() == "" and not o:isFocused() then
            -- inside the box: the long hint, else the short one, cut to fit (it ran under the view switch at 1920 x 1080)
            local room = o.width - 16
            local hint = "Search all " .. #S.searchRows .. " settings: name, what it does, gpu, load time..."
            if textW(UIFont.Small, hint) > room then hint = "Search " .. #S.searchRows .. " settings..." end
            o:drawText(clipText(UIFont.Small, hint, room), 8, math.floor((o.height - hS) / 2), C_DIM.r, C_DIM.g, C_DIM.b, 1, UIFont.Small)
        end
    end
    S.entry = entry
    S.searchItem = single(entry)
    local _, viewLabelItem = label("View", C_GREY)
    S.viewLabel = viewLabelItem
    S.viewButtons = {}
    for _, v in ipairs(VIEWS) do
        local id = v.id
        local b = drawButton(self, function() setLevel(S, id) end, function(o, hot)
            local sel = NAV.level == id
            if sel then
                o:drawRect(0, 0, o.width, o.height, 0.85, 1, 1, 1)
            else
                o:drawRect(0, 0, o.width, o.height, hot and 0.16 or 0.06, 1, 1, 1)
            end
            o:drawRectBorder(0, 0, o.width, o.height, 0.3, 1, 1, 1)
            local c = sel and 0.05 or (hot and 1 or 0.7)
            o:drawTextCentre(v.title, o.width / 2, math.floor((o.height - hS) / 2), c, c, c, 1, UIFont.Small)
        end)
        b:setWidth(textW(UIFont.Small, v.title) + 28)
        b:setHeight(BH)
        b.pzoptLabel = v.title
        b.tooltip = VIEW_TIPS[id] .. " Search and Fix a problem always show every match."
        table.insert(S.viewButtons, single(b))
    end
    local status = ISLabel:new(0, 0, BH, "", C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small, true)
    status:initialise()
    S.status, S.statusItem = status, single(status)
    -- "Before / after clips": the preview's GIFs, off by default; saved at once
    local clipsLabel = ISLabel:new(0, 0, BH, "Before / after clips", 1, 1, 1, 1, UIFont.Small, true)
    clipsLabel:initialise()
    S.clipsLabel = clipsLabel
    S.clipsLabelItem = single(clipsLabel)
    local clips = ISTickBox:new(0, 0, BH, BH, "", S, function(target, index, selected)
        setClipsOn(selected == true)
    end)
    clips.choicesColor = { r = 1, g = 1, b = 1, a = 1 }
    clips:initialise()
    clips:addOption("")
    clips:setSelected(1, clipsOn())
    clips.tooltip = "Plays a short clip of the stock game and one with the setting on, side by side, above the "
        .. "description of the setting under the mouse. Off saves the memory the clips take (up to ~100 MB of video "
        .. "memory while this screen is open). Applies at once and is remembered."
    S.clips = clips
    S.clipsItem = single(clips)
    S.rule = single(drawPanel(function(o) o:drawRect(0, 0, o.width, 1, 1, 0.35, 0.35, 0.38) end))
    S.rule2 = single(drawPanel(function(o) o:drawRect(0, 0, o.width, 1, 1, 0.35, 0.35, 0.38) end))
    S.rule.background, S.rule2.background = true, true -- (drawn under other items: the harness's overlap audit skips them)
end

function pageBuild.home(B)
    local self, S, p, single, button, label = B.self, B.S, B.p, B.single, B.button, B.label
    local hS, hM = B.G.hS, B.G.hM
    -- home: presets, group headings, tiles, help, tools
    local _, presetLabelItem = label("Presets", C_GREY)
    S.presetLabel = presetLabelItem
    S.presetButtons = {}
    local function preset(title, tip, fn)
        local _, item = button(title, tip, fn)
        table.insert(S.presetButtons, item)
        return item
    end
    local pinnedMaster = p:getPzoptOptionPinnedBy(MASTER.key) ~= ""
    local recommended = preset("Recommended (every optimization on)",
        "Turns the Performance master switch on and puts every Performance setting back to the build's default on this machine. "
        .. "Visuals and Tools are not changed. " .. RESTART_NOTE, function() setAll(self, true) end)
    local stockGame = preset("Stock game (every optimization off)",
        "Turns the Performance master switch off: the game runs its original code everywhere, as if the overrides were not "
        .. "installed. The settings are kept for when you turn it on again; Tools and the performance overlay are not affected. "
        .. RESTART_NOTE, function() setAll(self, false) end)
    for _, profile in ipairs(PROFILES) do
        local pr = profile
        preset(profile.button, profile.tip .. " " .. RESTART_NOTE, function() applyProfile(self, pr) end)
    end
    if pinnedMaster then
        for _, item in ipairs(S.presetButtons) do
            local b = item.elems[1].el
            b:setEnable(false)
            b.tooltip = "Pinned by " .. p:getPzoptOptionPinnedBy(MASTER.key) .. " for this install."
        end
    end
    S.helpHeading = single(drawPanel(function(o)
        o:drawText("HELP", 0, 0, C_HELP.r, C_HELP.g, C_HELP.b, 1, UIFont.Medium)
        local x = textW(UIFont.Medium, "HELP") + 16
        o:drawText(clipText(UIFont.Small, "Not sure what to change? Start from what you notice.", math.max(20, o.width - x)), x,
            math.floor((hM - hS) / 2), C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
    end))
    S.problemTile = single(drawButton(self, function() navigate(S, "problems") end, function(o, hot)
        o:drawRect(0, 0, o.width, o.height, 1, 0.115, 0.10, 0.13)
        o:drawRectBorder(0, 0, o.width, o.height, hot and 0.9 or 0.15, hot and C_HELP.r or 1, hot and C_HELP.g or 1, hot and C_HELP.b or 1)
        o:drawRect(0, 0, 5, o.height, 1, C_HELP.r, C_HELP.g, C_HELP.b)
        o:drawText("Fix a problem", 16, 8, 1, 1, 1, 1, UIFont.Medium)
        local yy = 10 + hM
        for i, l in ipairs(wrapLines(UIFont.Small, "Stutter while driving, low fps in a horde, slow loading, laggy input... "
            .. "Pick what you notice: why it happens, and the settings that help.", o.width - 32)) do
            if i > 2 then break end
            o:drawText(l, 16, yy, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
            yy = yy + hS
        end
    end))
    S.toolButtons = {}
    local _, exportItem = button(EXPORT_TITLE, EXPORT_TIP, function() exportSettings(self) end)
    local _, importItem = button(IMPORT_TITLE, IMPORT_TIP, function() openImportDialog(self) end)
    local uninstall, uninstallItem = button(UNINSTALL_TITLE, nil, nil)
    setupUninstallButton(self, uninstall)
    S.toolButtons = { exportItem, importItem, uninstallItem }
    S.homeNoteText = "Apply or Accept saves your changes to Zomboid/pzopt/options.ini. Performance settings take effect on the next "
        .. "launch; Visuals and Tools apply at once (a few Visuals on the next launch). Settings pinned by the game folder's "
        .. "pzopt.properties or -Dpzopt.<key> cannot be changed here."
    S.homeNote = single(drawPanel(function(o)
        local yy = 0
        for _, l in ipairs(wrapLines(UIFont.Small, S.homeNoteText, o.width)) do
            o:drawText(l, 0, yy, C_DIM.r, C_DIM.g, C_DIM.b, 1, UIFont.Small)
            yy = yy + hS
        end
    end))
end

-- group headings, tiles, Visuals cards and subcategory tabs / headings (before the rows: they draw beneath them)
function pageBuild.decorations(B)
    local self, S, single, buttonItem = B.self, B.S, B.single, B.buttonItem
    local hS, hM, tree = B.G.hS, B.G.hM, S.tree
    for _, g in ipairs(tree.groups) do
        local group = g
        g.heading = single(drawPanel(function(o)
            o:drawText(string.upper(group.title), 0, 0, group.c.r, group.c.g, group.c.b, 1, UIFont.Medium)
            local x = textW(UIFont.Medium, string.upper(group.title)) + 16
            o:drawText(clipText(UIFont.Small, group.count .. " settings.  " .. group.note, math.max(20, o.width - x)), x,
                math.floor((hM - hS) / 2), C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
        end))
        if g.page ~= 1 then
            local page = PAGES[g.page]
            g.reset = buttonItem("Reset " .. g.title .. " to defaults", "Puts every " .. g.title .. " setting back to the "
                .. "build's default, the master switch on. Only the controls change; Apply or Accept saves them.", function()
                resetOptions(self[page.options] or {}, self[page.masterField])
            end)
        end
        for _, cat in ipairs(g.cats) do
            local c = cat
            cat.tile = single(drawButton(self, function() navigate(S, "cat", c.id, 0) end, function(o, hot)
                local col = c.group.c
                o:drawRect(0, 0, o.width, o.height, 1, 0.115, 0.115, 0.125)
                if hot then
                    o:drawRectBorder(0, 0, o.width, o.height, 0.9, col.r, col.g, col.b)
                else
                    o:drawRectBorder(0, 0, o.width, o.height, 0.15, 1, 1, 1)
                end
                o:drawRect(0, 0, 5, o.height, 1, col.r, col.g, col.b)
                o:drawText(c.title, 16, 8, 1, 1, 1, 1, UIFont.Medium)
                local yy = 10 + hM
                for i, l in ipairs(wrapLines(UIFont.Small, c.blurb, o.width - 32)) do
                    if i > 2 then break end
                    o:drawText(l, 16, yy, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
                    yy = yy + hS
                end
                local st, sc = catStatus(c)
                local pw = textW(UIFont.Small, st) + 12
                local fy = o.height - hS - 10
                drawPill(o, o.width - 12 - pw, fy - 1, st, sc)
                o:drawText(clipText(UIFont.Small, #c.rows .. " settings:  " .. c.subList, o.width - 44 - pw), 16, fy,
                    C_DIM.r, C_DIM.g, C_DIM.b, 1, UIFont.Small)
            end))
            -- the subcategory tabs, Overview first
            cat.tabs = {}
            local function tabButton(index, title, list)
                local bw = textW(UIFont.Medium, title) + (list and (textW(UIFont.Small, "000") + 28) or 0) + 24
                local b = drawButton(self, function() navigate(S, "cat", c.id, index) end, function(o, hot)
                    local col = c.group.c
                    local sel = NAV.sub == index
                    if sel then
                        o:drawRect(0, 0, o.width, o.height, 0.14, col.r, col.g, col.b)
                        o:drawRect(0, o.height - 3, o.width, 3, 1, col.r, col.g, col.b)
                    elseif hot then
                        o:drawRect(0, 0, o.width, o.height, 0.07, 1, 1, 1)
                    end
                    local t = (sel or hot) and 1 or 0.65
                    o:drawText(title, 12, math.floor((o.height - hM) / 2), t, t, t, 1, UIFont.Medium)
                    if list then
                        local n = tostring(#visibleRows(list, NAV.level))
                        local nx = 12 + textW(UIFont.Medium, title) + 8
                        local nw = textW(UIFont.Small, n) + 12
                        o:drawRect(nx, math.floor((o.height - hS - 2) / 2), nw, hS + 2, sel and 0.4 or 0.1, sel and col.r or 1, sel and col.g or 1, sel and col.b or 1)
                        o:drawTextCentre(n, nx + nw / 2, math.floor((o.height - hS) / 2), t, t, t, 1, UIFont.Small)
                    end
                end)
                b:setWidth(bw)
                b:setHeight(hM + 12)
                table.insert(cat.tabs, single(b))
            end
            tabButton(0, "Overview", nil)
            for i, sub in ipairs(cat.subs) do
                local s, index = sub, i
                tabButton(i, sub.title, sub.rows)
                if g.cards then
                    s.card = single(drawPanel(function(o)
                        local col = c.group.c
                        o:drawRect(0, 0, o.width, o.height, 1, 0.10, 0.10, 0.11)
                        o:drawRectBorder(0, 0, o.width, o.height, 0.18, 1, 1, 1)
                        o:drawRect(0, 0, 4, o.height, 1, col.r, col.g, col.b)
                        o:drawText(s.title, 16, 8, 1, 1, 1, 1, UIFont.Medium)
                        local fx = (s.rows[1] and EFFECTS[s.rows[1].entry.key]) or {}
                        local dx = 16 + textW(UIFont.Medium, s.title) + 24
                        dx = drawDots(o, dx, 8 + math.floor((hM - hS) / 2), "GPU", math.max(0, math.min(3, (fx.gpu or 0) + 1)), C_AMBER)
                        drawDots(o, dx, 8 + math.floor((hM - hS) / 2), "VRAM", math.max(0, math.min(3, fx.vram or 0)), C_AMBER)
                        local text = s.blurb or (s.rows[1] and firstSentence(s.rows[1].entry.tip)) or ""
                        o:drawText(clipText(UIFont.Small, text, o.width - 32), 16, 10 + hM, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
                    end))
                    s.card.background = true
                    s.open = buttonItem("All " .. #s.rows .. " settings  >", "Opens the " .. s.title .. " tab.",
                        function() navigate(S, "cat", c.id, index) end)
                else
                    s.head = single(drawButton(self, function() navigate(S, "cat", c.id, index) end, function(o, hot)
                        local col = c.group.c
                        o:drawRect(0, 0, o.width, 1, 1, 0.3, 0.3, 0.33)
                        o:drawText(s.title, 0, 6, col.r, col.g, col.b, 1, UIFont.Medium)
                        local hidden = s.headHidden or 0
                        local more = hidden > 0 and (hidden .. " more in its tab  >") or "Open its tab  >"
                        local t = hot and 1 or 0.6
                        o:drawText(more, textW(UIFont.Medium, s.title) + 16, 6 + math.floor((hM - hS) / 2), t, t, t, 1, UIFont.Small)
                    end))
                end
            end
            cat.resultHead = single(drawPanel(function(o)
                local col = c.group.c
                o:drawText(string.upper(c.group.title) .. "  >  " .. c.title, 0, 2, col.r, col.g, col.b, 1, UIFont.Small)
            end))
        end
    end
    for i, pr in ipairs(tree.problems) do
        local problem, index = pr, i
        pr.tile = single(drawButton(self, function() NAV.problem = index; relayout(S) end, function(o, hot)
            local sel = NAV.problem == index
            if sel then
                o:drawRect(0, 0, o.width, o.height, 1, 0.20, 0.14, 0.24)
            else
                o:drawRect(0, 0, o.width, o.height, 1, 0.115, 0.115, 0.125)
            end
            if sel or hot then
                o:drawRectBorder(0, 0, o.width, o.height, 0.9, C_HELP.r, C_HELP.g, C_HELP.b)
            else
                o:drawRectBorder(0, 0, o.width, o.height, 0.15, 1, 1, 1)
            end
            o:drawText(problem.title, 12, 6, 1, 1, 1, 1, UIFont.Medium)
            o:drawText(clipText(UIFont.Small, problem.cause, o.width - 24), 12, 8 + hM, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
            local on = 0
            for _, row in ipairs(problem.rows) do
                local v = row.option:pzoptCurrent()
                if v ~= "false" and v ~= "off" and v ~= "0" and v ~= "off (default)" and v ~= "false (default)" then on = on + 1 end
            end
            local c = on == #problem.rows and C_OPT or C_AMBER
            o:drawText(#problem.rows .. " settings, " .. on .. " on", 12, o.height - hS - 8, c.r, c.g, c.b, 1, UIFont.Small)
        end))
    end
end

local function buildPage(self)
    local t0 = getTimestampMs()
    local savedPanel, savedAddY = self.mainPanel, self.addY
    local firstOption = #self.gameOptions.options + 1
    local wasChanged = self.gameOptions.changed
    local style = MainOptions.style
    local BH, SP = style.buttonHeight, style.borderSpacing
    local hS, hM, hL = fontH(UIFont.Small), fontH(UIFont.Medium), fontH(UIFont.Large)
    local comboWidth = 45 * (getCore():getOptionFontSizeReal() + 1) + 60
    local panel = self.pzoptPanel
    self.mainPanel = panel
    self.addY = 0
    local p = perf()
    local W, H = panel:getWidth(), panel:getHeight()
    local G = { m = 16, sbar = 13, hS = hS, hM = hM, hL = hL, BH = BH, SP = SP }
    -- the sidebar as wide as its longest category name needs (with its count), within 200..330 px; the preview a
    -- quarter of the width, at least 300 px (a 1920 x 1080 window is 1344 px wide: 1/4 is 336)
    local longest = 0
    for _, g in ipairs(PzoptSettingsLayout.groups) do
        for _, c in ipairs(g.cats) do longest = math.max(longest, textW(UIFont.Small, c.title)) end
    end
    G.sideW = math.max(200, math.min(330, math.max(math.floor(W * 0.14), longest + textW(UIFont.Small, "000") + 48)))
    G.prevW = math.max(300, math.min(900, math.floor(W * 0.25)))
    G.prevX = W - G.m - G.sbar - G.prevW
    G.contentX = G.m + G.sideW + GAP
    G.contentR = G.prevX - GAP
    G.homeX, G.homeR = G.m, W - G.m - G.sbar
    G.ctrlW = math.min(comboWidth, math.floor((G.contentR - G.contentX) * 0.4))
    G.labelX = G.contentX + G.ctrlW + 12
    local split = G.contentX - 20 -- the stock helpers put the control at splitpoint + 20
    local S = { panel = panel, G = G, items = {}, keyRows = {}, searchRows = {}, self = self, hitCount = 0 }
    self.pzoptSearch = S

    -- every element added while `sink` is set belongs to the item being built
    local sink
    panel.addChild = function(o, child)
        if sink then table.insert(sink, child) end
        return ISPanelJoypad.addChild(o, child)
    end
    local function capture(fn)
        local top = self.addY
        sink = {}
        local result = fn()
        local item = { elems = {}, step = self.addY - top }
        for _, el in ipairs(sink) do table.insert(item.elems, { el = el, dy = el:getY() - top, x0 = el:getX() }) end
        sink = nil
        table.insert(S.items, item)
        return item, result, top
    end
    -- one element, placed by relayout at its own x
    local function single(el)
        panel:addChild(el)
        local item = { elems = { { el = el, dy = 0, x0 = el:getX() } }, step = el:getHeight() }
        table.insert(S.items, item)
        return item
    end
    local function button(title, tip, onclick, h)
        local b = ISButton:new(0, 0, 100, h or BH, title, self, onclick)
        b:initialise()
        b:setWidthToTitle()
        b.tooltip = tip
        return b, single(b)
    end
    local function buttonItem(title, tip, onclick)
        local _, item = button(title, tip, onclick)
        return item
    end
    local function label(text, col, font)
        col = col or C_TEXT
        local l = ISLabel:new(0, 0, BH, text, col.r, col.g, col.b, 1, font or UIFont.Small, true)
        l:initialise()
        return l, single(l)
    end

    local B = { self = self, S = S, G = G, p = p, single = single, button = button, buttonItem = buttonItem, label = label }
    pageBuild.header(B)

    pageBuild.home(B)

    -- category pages: heading, footer
    S.catHead = single(drawPanel(function(o)
        local cat = o.cat
        if not cat then return end
        local c = cat.group.c
        o:drawText(cat.title, 0, 0, 1, 1, 1, 1, UIFont.Large)
        o:drawText(clipText(UIFont.Small, cat.blurb, o.width), 0, hL + 4, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
        local count = #cat.rows .. " settings in " .. #cat.subs .. " subcategories"
        if textW(UIFont.Large, cat.title) + 24 + textW(UIFont.Small, count) <= o.width then
            o:drawTextRight(count, o.width, math.floor((hL - hS) / 2), c.r, c.g, c.b, 1, UIFont.Small)
        end
        if groupOff(cat.group) then
            o:drawText(clipText(UIFont.Small, "Switched off: the " .. cat.group.title .. " master switch on the home page is off, so these "
                .. "settings are ignored.", o.width), 0, hL + hS + 8, C_STOCK.r, C_STOCK.g, C_STOCK.b, 1, UIFont.Small)
        end
    end))
    S.tabRule = single(drawPanel(function(o) o:drawRect(0, 0, o.width, 1, 1, 0.3, 0.3, 0.33) end))
    S.tabRule.background = true
    S.moreButton = buttonItem("Show more", "Switches the view so this subcategory shows the rest of its settings.", function()
        setLevel(S, NAV.level == "simple" and "advanced" or "everything")
    end)
    S.catReset = buttonItem("Reset to defaults", "Puts every setting of this category back to the build's default on this "
        .. "machine. Only the controls change; Apply or Accept saves them.", function()
        local cat = S.tree.catById[NAV.cat]
        if not cat then return end
        local list = {}
        for _, row in ipairs(cat.rows) do table.insert(list, row.option) end
        resetOptions(list)
    end)
    S.catNote = single(drawPanel(function(o)
        o:drawText(clipText(UIFont.Small, o.text or "", o.width), 0, 0, C_DIM.r, C_DIM.g, C_DIM.b, 1, UIFont.Small)
    end))

    -- problems
    S.probHead = single(drawPanel(function(o)
        o:drawText("Fix a problem", 0, 0, 1, 1, 1, 1, UIFont.Large)
        o:drawText(clipText(UIFont.Small, "Pick what you notice: why it happens, and the settings that help, wherever they live.", o.width),
            0, hL + 4, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
    end))
    S.probWhy = single(drawPanel(function(o)
        local pr = o.problem
        if not pr then return end
        o:drawRect(0, 0, o.width, o.height, 1, 0.115, 0.10, 0.13)
        o:drawRectBorder(0, 0, o.width, o.height, 0.6, C_HELP.r, C_HELP.g, C_HELP.b)
        o:drawText(pr.title, 16, 8, 1, 1, 1, 1, UIFont.Medium)
        local yy = 12 + hM
        for _, l in ipairs(o.lines or {}) do
            o:drawText(l, 16, yy, 0.85, 0.85, 0.87, 1, UIFont.Small)
            yy = yy + hS
        end
    end))
    -- search
    S.searchHead = single(drawPanel(function(o)
        o:drawText(clipText(UIFont.Medium, o.text or "", o.width), 0, 0, 1, 1, 1, 1, UIFont.Medium)
    end))

    -- the settings: master switches first, then every section's entries (in tab order)
    local rows = {}
    local masterRows = {}
    local optionLists = { {}, {}, {} }
    local function makeRow(entry, pageIndex, section, isMaster)
        local item, option, top = capture(function()
            if entry.bezier then return addBezierOption(self, entry, split, 0, G.ctrlW, BH) end
            if entry.colour then return addColourOption(self, entry, split, 0) end
            if entry.number then return addNumericOption(self, entry, split, 0, G.ctrlW, BH) end
            if entry.choices then return addIntOption(self, entry, split, 0, G.ctrlW) end
            return addBoolOption(self, entry, split, 0, BH)
        end)
        -- the labels the stock helpers right-align left of the control go to its right, left-aligned; a setting's name
        -- too long for the column (a 1920 x 1080 window) wraps: its first line stays the label, the rest goes on the
        -- line(s) under it, above the tags (PzoptRowInfo)
        local maxLab = 0
        local more = {}
        local titled = false
        for _, e in ipairs(item.elems) do
            local el = e.el
            if el.Type == "ISLabel" and el:getX() < G.contentX - 1 then
                local font = el.font or UIFont.Small
                local room = G.contentR - G.labelX
                local name = el.name or ""
                if not titled and textW(font, name) > room then
                    local lines = wrapLines(font, name, room)
                    name = lines[1]
                    for i = 2, math.min(#lines, 3) do table.insert(more, lines[i]) end
                    if #lines > 3 then more[2] = clipText(font, more[2] .. " " .. table.concat(lines, " ", 4), room) end
                end
                titled = true
                name = clipText(font, name, room)
                el.left, el.originalX, el.name = true, G.labelX, name
                el:setWidth(textW(font, name))
                el:setX(G.labelX)
                e.x0 = G.labelX
                maxLab = math.max(maxLab, el:getWidth())
            elseif el.Type == "ISTickBox" then
                el:setX(G.contentX + G.ctrlW - el:getWidth())
                e.x0 = el:getX()
            end
        end
        for _, e in ipairs(item.elems) do
            if e.el.Type ~= "ISLabel" and e.el:getX() < G.contentX - 1 then -- the curve plot, smaller when the column is narrow
                local px = G.labelX + maxLab + 16
                local size = math.max(40, math.min(e.el:getWidth(), G.contentR - px))
                e.el:setWidth(size)
                e.el:setHeight(size)
                e.el:setX(px)
                e.x0 = px
            end
        end
        local row = item
        row.entry, row.option, row.page, row.section = entry, option, pageIndex, section
        row.key = entry.key
        row.tier = isMaster and 1 or tierOf(entry.key)
        row.clip = isMaster and PAGES[pageIndex].masterClip or (KEY_CLIP[entry.key] or section.clip or "drive")
        row.controlDy = 0
        -- the line under the label (after the rest of a wrapped name)
        row.labelMore = more
        local infoH = (hS + 2) * (1 + #more)
        local info = PzoptRowInfo:new(G.labelX, self.addY - SP + 1, G.contentR - G.labelX, infoH, row)
        info:initialise()
        panel:addChild(info)
        table.insert(row.elems, { el = info, dy = info:getY() - top, x0 = G.labelX })
        self.addY = self.addY + infoH + SP
        row.step = self.addY - top
        row.h = row.step
        option.pzoptProfile = section and section.profiles
        if not isMaster then table.insert(optionLists[pageIndex], option) end
        table.insert(S.keyRows, row)
        row.index = #S.keyRows
        return row
    end
    for pi, page in ipairs(PAGES) do
        if page.master and p:isPzoptOptionKnown(page.master.key) then
            local row = makeRow(page.master, pi, nil, true)
            row.isMaster = true
            masterRows[pi] = row
            self[page.masterField] = row.option
        end
    end
    -- the Visuals cards are built before the rows inside them, so the rows draw over them
    for pi, page in ipairs(PAGES) do
        for _, section in ipairs(page.sections) do
            for _, entry in ipairs(section.entries) do
                if p:isPzoptOptionKnown(entry.key) then
                    table.insert(rows, { entry = entry, page = pi, section = section })
                else
                    print("[pzopt] options tab: unknown key " .. entry.key .. ", skipped")
                end
            end
        end
    end
    -- the tree needs the rows' keys and sections only; build it on stand-ins, then make the cards, then the real rows
    local stand = {}
    for _, r in ipairs(rows) do table.insert(stand, { entry = r.entry, page = r.page, section = r.section, stand = r }) end
    local tree = buildTree(stand, masterRows)
    S.tree = tree
    pageBuild.decorations(B)
    -- the real rows, in the tree's order (stand-ins swapped for them)
    local real = {}
    for _, st in ipairs(stand) do
        local row = makeRow(st.entry, st.page, st.section, false)
        row.cat, row.sub, row.group = st.cat, st.sub, st.group
        real[st] = row
        table.insert(S.searchRows, row)
    end
    for _, g in ipairs(tree.groups) do
        for _, cat in ipairs(g.cats) do
            for i, r in ipairs(cat.rows) do cat.rows[i] = real[r] end
            for _, sub in ipairs(cat.subs) do
                for i, r in ipairs(sub.rows) do sub.rows[i] = real[r] end
                -- the Upscaling subcategory starts with the "Install DLSS files" button
                if sub.dlss then
                    local b, item = button(DEPS_TITLES.checking, DEPS_TIP, nil)
                    setupUpscalerDepsButton(self, b)
                    item.elems[1].x0 = G.contentX
                    item.step = BH + SP
                    item.joy = { { b } }
                    sub.dlssRow = item
                end
            end
        end
    end
    for _, pr in ipairs(tree.problems) do
        for i, r in ipairs(pr.rows) do pr.rows[i] = real[r] end
    end
    for pi, page in ipairs(PAGES) do self[page.options] = optionLists[pi] end
    panel.addChild = nil -- back to the class method

    -- search over every setting (the masters are on the home page)
    local sectionOf = {}
    for _, row in ipairs(S.searchRows) do
        sectionOf[row] = { title = row.section.title .. " " .. row.cat.title .. " " .. row.sub.title }
    end
    S.index = buildIndex(S.searchRows, sectionOf)

    -- the fixed parts: the sidebar on the left, the preview on the right (both added last: they draw on top)
    local sidebar = PzoptSidebar:new(G.m, G.m, G.sideW, H - 2 * G.m, S)
    sidebar:initialise()
    sidebar:instantiate()
    sidebar:setScrollWithParent(false)
    sidebar:setAnchorTop(true)
    sidebar:setAnchorBottom(true)
    panel:addChild(sidebar)
    S.sidebar = sidebar
    sidebar:build(self)
    -- The controller between the page and the sidebar: Left / Right cross (the stock spatial search finds the entry or the
    -- control at that height), Up / Down stay on their side (at the ends of a list the stock fallback steps to the next
    -- row, which would jump between the two), Right from the sidebar into an empty stretch of the page goes to the page's
    -- first control. A focused sidebar entry never scrolls the page.
    panel.ensureVisible = function(o)
        local child = o:getJoypadFocus()
        if child and child.pzoptFixed then return end
        return ISPanelJoypad.ensureVisible(o)
    end
    for _, dir in ipairs({ "onJoypadDirUp", "onJoypadDirDown" }) do
        local stock = ISPanelJoypad[dir]
        panel[dir] = function(o, joypadData)
            local before = o:getJoypadFocus()
            stock(o, joypadData)
            local after = o:getJoypadFocus()
            if before and after and before ~= after and (before.pzoptFixed == true) ~= (after.pzoptFixed == true) then
                o:setJoypadFocus(before, joypadData)
                o:ensureVisible()
            end
        end
    end
    local stockRight = ISPanelJoypad.onJoypadDirRight
    panel.onJoypadDirRight = function(o, joypadData)
        local before = o:getJoypadFocus()
        stockRight(o, joypadData)
        if before and before.pzoptFixed and o:getJoypadFocus() == before and S.firstJoy then
            o:setJoypadFocus(S.firstJoy, joypadData)
            o:ensureVisible()
        end
    end
    local preview = PzoptPreview:new(G.prevX, G.m, G.prevW, H - 2 * G.m, panel, S.keyRows)
    preview:initialise()
    preview:instantiate()
    preview:setScrollWithParent(false)
    preview:setAnchorTop(true)
    preview:setAnchorBottom(true)
    preview.minX = G.contentX
    panel:addChild(preview)
    if S.keyRows[1] then preview:select(S.keyRows[1]) end
    self.pzoptPreview = preview
    S.preview = preview

    -- the page's prerender runs before its children draw: cull for this frame's scroll first
    local pagePrerender = panel.prerender
    panel.prerender = function(o, ...)
        cullRows(S)
        return pagePrerender(o, ...)
    end
    relayout(S)
    -- the screen's toUI ran before this tab existed: show the saved values and remember them as the current ones
    for i = firstOption, #self.gameOptions.options do
        local option = self.gameOptions.options[i]
        option:toUI()
        option:storeCurrentValue()
    end
    self.gameOptions.changed = wasChanged
    self.mainPanel, self.addY = savedPanel, savedAddY
    local tiers = { 0, 0, 0 }
    for _, row in ipairs(S.searchRows) do tiers[row.tier] = tiers[row.tier] + 1 end
    PzoptLogInfo("[pzopt] options tab " .. TAB .. ": " .. #S.keyRows .. " settings (simple " .. tiers[1] .. ", advanced "
        .. tiers[2] .. ", expert " .. tiers[3] .. "), " .. #S.items .. " items, layout "
        .. G.sideW .. " / " .. (G.contentR - G.contentX) .. " / " .. G.prevW .. " px, built in " .. (getTimestampMs() - t0) .. " ms")
end

-- The tab is added with the others (after Display) but built the first time it is shown: the in-game menu builds the
-- whole options screen while the world is entered.
function MainOptions:pzoptAddOptimizationsPanel()
    self.pzoptBuilt = false
    self:addPage(TAB)
    self.pzoptPanel = self.mainPanel
end

ensurePageBuilt = function(self, tab)
    if self.pzoptPanel and not self.pzoptBuilt then
        self.pzoptBuilt = true
        local ok, err = pcall(buildPage, self)
        if not ok then
            print("[pzopt] options tab " .. TAB .. ": build failed: " .. tostring(err))
        end
    end
end

-- For the harness and the e2e rigs: the tab's name, and showing a page ("home", "cat" + category id + subcategory
-- index, "problems") at a view level ("simple", "advanced", "everything").
PzoptOptionsTab = TAB
function PzoptOptionsNavigate(mo, kind, cat, sub, level)
    if not mo then return false end
    ensurePageBuilt(mo, TAB)
    local S = mo.pzoptSearch
    if not S then return false end
    if level and LEVEL[level] then NAV.level = level end
    if kind == "problems" and tonumber(cat) then
        NAV.problem = tonumber(cat)
        cat = nil
    end
    navigate(S, kind or "home", cat, tonumber(sub) or 0)
    return true
end

local function install()
    if not MainOptions or MainOptions.pzoptOptimizationsTab then return end
    local ok, has = pcall(function() return getPerformance():hasPzoptOptions() end)
    if not ok or not has then
        print("[pzopt] options tab: PerformanceSettings override not loaded or overrides disabled, tab not added")
        return
    end
    MainOptions.pzoptOptimizationsTab = true
    -- The tabs go right after Display (Optimizations, Enhancements, then Profiler): create() adds the pages in order, so hook
    -- the Display page.
    -- the whole options screen's build time, for the load trace (the in-game menu builds it while the world is entered)
    local stockCreate = MainOptions.create
    -- The full build: stock create (timed for the load trace), then the Optimizations tab's lazy hook.
    local function fullCreate(self, ...)
        self.pzoptCreatePending = false
        self.pzoptCreated = true
        local t0 = getTimestampMs()
        local r = stockCreate(self, ...)
        PzoptLogInfo("[pzopt] options screen: MainOptions:create took " .. (getTimestampMs() - t0) .. " ms")
        -- build our tab when it is first shown (pzoptAddOptimizationsPanel added it empty)
        local tabs = self.tabs
        if tabs and self.pzoptPanel then
            local stockOnActivate = tabs.onActivateView
            tabs.onActivateView = function(target, tabPanel)
                if target and target.pzoptPanel and tabPanel:getActiveView() == target.pzoptPanel then
                    ensurePageBuilt(target, TAB)
                end
                if stockOnActivate then
                    return stockOnActivate(target, tabPanel)
                end
            end
        end
        return r
    end
    -- lazyOptionsScreen: the main menu and the in-game menu build the whole options screen while they are built
    -- (boot, every world entry, exit to menu), ~130 ms of vanilla panels on the flip on top of our tab. The hidden
    -- screen now only does the one part of create() the game needs without it, the key bindings (loadKeys, and the
    -- keysB42.ini rewrite stock does after a key-file version change), and builds the rest the first time it is
    -- used: toUI (called before the screen is shown) or setVisible(true). Only while our wrapper is still the
    -- installed MainOptions.create: a mod that wrapped create after us runs the stock build as before.
    local lazy = true
    pcall(function() lazy = getPerformance():getPzoptOption("lazyOptionsScreen") ~= "false" end)
    local ourCreate
    ourCreate = function(self, ...)
        if lazy and MainOptions.create == ourCreate and not self.pzoptCreated and not self:getIsVisible() then
            local reload = MainOptions.loadKeys()
            if reload then
                -- Stock writes MainOptions.keyText, which only its addKeybindingPanel fills (one entry per
                -- MainOptions.keys row, plus mod binds it skips here). Unbuilt, keyText is empty at boot and stale in
                -- game, and writing it wiped every binding from keysB42.ini (2026-10-06). The same entries, made
                -- from the rows loadKeys just read, through stock's writeKey:
                local fileOutput = getFileWriter("keysB42.ini", true, false)
                fileOutput:write("VERSION=" .. tostring(MainOptions.KEYS_VERSION) .. "\r\n")
                for _, v in ipairs(MainOptions.keys) do
                    if luautils.stringStarts(v.value, "[") then
                        MainOptions.writeKey({ value = v.value }, fileOutput)
                    else
                        local name = v.value
                        MainOptions.writeKey({ txt = { getName = function() return name end },
                            keyCode = tonumber(v.key) or 0, altCode = tonumber(v.altCode) or 0,
                            shift = v.shift, ctrl = v.ctrl, alt = v.alt }, fileOutput)
                    end
                end
                fileOutput:close()
            end
            self.pzoptCreatePending = true
            PzoptLogInfo("[pzopt] options screen: build deferred until it is opened (key bindings loaded)")
            return
        end
        return fullCreate(self, ...)
    end
    MainOptions.create = ourCreate
    local function ensureCreated(self)
        if self.pzoptCreatePending then
            fullCreate(self)
        end
    end
    local stockToUI = MainOptions.toUI
    function MainOptions:toUI(...)
        ensureCreated(self)
        return stockToUI(self, ...)
    end
    local stockOnResolutionChange = MainOptions.onResolutionChange
    function MainOptions:onResolutionChange(...)
        if self.pzoptCreatePending then
            return -- nothing built yet; the build later uses the size in force then
        end
        return stockOnResolutionChange(self, ...)
    end
    -- The parent's doLayout reaches the hidden screen too (ISUIElement.setVisible(true) lays out every child, e.g.
    -- MainScreen on Esc in game): stock centerKeybindings reads keyButtonWidth, which only the build sets, and its
    -- error aborted the pause menu's layout (pause menu without its buttons). The build lays itself out.
    local stockDoLayout = MainOptions.doLayout
    function MainOptions:doLayout(...)
        if self.pzoptCreatePending then
            return
        end
        return stockDoLayout(self, ...)
    end
    local stockAddDisplayPanel = MainOptions.addDisplayPanel
    function MainOptions:addDisplayPanel()
        stockAddDisplayPanel(self)
        local okPanel, err = pcall(MainOptions.pzoptAddOptimizationsPanel, self)
        if not okPanel then
            print("[pzopt] options tab: failed: " .. tostring(err))
        end
    end
    -- Closing the options screen (Back / Accept hide it) frees the preview clips' textures; the next
    -- visit decodes them again.
    local stockSetVisible = MainOptions.setVisible
    function MainOptions:setVisible(bVisible, ...)
        if bVisible then
            local wasPending = self.pzoptCreatePending
            ensureCreated(self)
            if wasPending then
                stockToUI(self) -- the values the screen shows, as toUI would have set them
            end
        end
        stockSetVisible(self, bVisible, ...)
        if not bVisible then
            if self.pzoptColourPicker then self.pzoptColourPicker:removeSelf() end
            pcall(function() getPerformance():releasePzoptGifs() end)
        end
    end
end

install()
Events.OnGameBoot.Add(install)
