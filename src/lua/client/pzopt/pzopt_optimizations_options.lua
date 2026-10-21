-- pzopt: every pzopt setting the player can change, as data, and what the main menu's PZ OPTIMIZATION screen does with
-- them (pzopt_mainscreen_menu.lua is the screen; since 2026-10-18 it is their only home: the game's Options screen has no
-- PZ Optimization tab any more, only this file's lazy build of that screen, install() at the end).
--  SECTIONS (Performance, next launch), ENHANCEMENT_SECTIONS (Visuals, live except NEXT_LAUNCH_ONLY) and PROFILER_SECTIONS
--  (Tools, live): one entry per pzopt.Config key with its label, description (tip), choices / notes, its clip. The values
--  live in Java: the overridden PerformanceSettings forwards to pzopt.Config (what is in force since boot) and
--  pzopt.UserOptions (Zomboid/pzopt/options.ini, what the next launch reads; a live key applies when it is saved:
--  pzopt.Config.reloadLive, then pzopt.Overlay.reconfigure / pzopt.Enhancements.apply). A key set in the install dir's
--  pzopt.properties or as -Dpzopt.<key> (harness runs) wins over the file and cannot be changed from the screen.
--  Masters: each group has one (`enabled`, `enhancementsEnabled`, `profilerEnabled`) and each category one (CATS, the
--  layout's `master`): off, Java reads the settings they gate at the stock game's value; the choices stay saved.
--  The impact chart (AXES / EFFECTS, PzoptPreview:drawBars), the before / after clips (CLIP_*, KEY_CLIP, PzoptPreview:
--  drawClip, GIFs under media/ui/pzopt/compare/ decoded by pzopt.GifTextures), the search (BM25 over labels, keys,
--  descriptions, resources and the Java classes), the presets (PROFILES), export / import, reset, uninstall and the DLSS
--  files are reached through PzoptSettings (the API near the end).
--  How the settings are organised (groups, categories, subcategories, Simple / Advanced / Everything, Fix a problem):
--  pzopt_optimizations_layout.lua.
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
  restartKeys = { "hdr", "hdrAuto", "pixelLight", "voxelLight", "reflections", "carGlass", "mirrors" },
  tip = "Off = the picture is the stock game's: upscaling, sprite filtering, HDR output, ambient occlusion, sun shadows, reflections, car glass, the darkness floor, remembered places, colour grading, per-pixel lighting, god rays and foliage sway are all off, whatever the settings below say (they are kept for when you switch it on again). On = the settings below apply. HDR output, per-pixel lighting, reflections and car glass switch on the next launch." }
local PROFILER_MASTER = { key = "profilerEnabled", label = "Profiler enabled (master switch)", live = true,
  tip = "Off = no performance overlay, no measuring and no frame log: the overlay's samplers never start and the toggle key only says the profiler is off, whatever the settings below say (they are kept for when you switch it on again). On = the settings below apply." }

-- The category master switches (2026-10-16): one per category of pzopt_optimizations_layout.lua (its `master` key), at the
-- top of the category's page. Off, Java reads the category's settings at the stock game's value (Config's GATED list)
-- while the choices stay saved, like the group switches. Visuals ones apply at once except the start-up features
-- (restart keys, named in `later`); Performance ones on the next launch. The Tools group's one category uses its group's.
-- One table (CATS.*) rather than top-level locals: a -debug game's Lua compiler fails a chunk past 200 locals (issue #58,
-- scripts/LuaDebugCompile.java).
local CATS = {}
CATS.restart = {
    catImageEnabled = { keys = { "hdr", "hdrAuto" }, later = "HDR output switches" },
    catLightEnabled = { keys = { "pixelLight", "voxelLight" }, later = "per-pixel and voxel lighting switch" },
    catReflEnabled = { keys = { "reflections", "carGlass", "mirrors" }, later = "reflections, car glass and mirrors switch" },
}

-- the category switch that gates a start-up key, or nil
function CATS.masterOfKey(key)
    for master, r in pairs(CATS.restart) do
        for _, k in ipairs(r.keys) do
            if k == key then return master end
        end
    end
    return nil
end

-- the switch as a settings entry: label, live, its start-up keys, its description
function CATS.entry(cat, live)
    local entry = { key = cat.master, label = cat.title .. " enabled (category switch)", live = live }
    if live then
        local r = CATS.restart[cat.master]
        entry.restartKeys = r and r.keys
        entry.tip = "Off = everything in " .. cat.title .. " is off, as in the stock game, whatever the settings below say "
            .. "(they are kept for when you switch it on again). On = the settings below apply, as long as the Enhancements "
            .. "master switch is on too. Applies at once" .. (r and ("; " .. r.later .. " on the next launch.") or ".")
    else
        entry.tip = "Off = every optimization in " .. cat.title .. " takes the stock game's code path from the next launch, "
            .. "whatever the settings below say (they are kept for when you switch it on again). On = the settings below apply. "
            .. "Worker thread counts other categories share, crash guards, mod compatibility and updates stay as set."
    end
    return entry
end

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
            { key = "bakeBudgetGpu", label = "One budget: count the GPU's time too",
              tip = "The adaptive budget above judged a frame by the time the game and render threads took. With this it also counts how long the GPU kept drawing after the frame was handed over (the frame queue's GPU timestamps), so a frame whose GPU ran long, such as a horde coming into view, also halves the next frames' bakes. Only changes when chunk pictures bake, never what they show. On by default since 2026-10-15." },
            { key = "occlusionGrantedOnly", label = "One budget: occlusion only for the levels baked",
              tip = "The pass that finds which chunk levels are hidden behind buildings looks only at the levels baked this frame, not every one still waiting." },
            { key = "bakeNoSync", label = "Bake passes never wait for the graphics driver",
              tip = "The ambient-occlusion and foliage-sway passes that run inside a chunk bake keep their own record of the GL state and take object names from a ready pool, instead of asking the driver (each question made the render thread wait for the driver to finish every bake queued before it). Louisville horde with every Enhancement on: single baked levels of 10-35 ms on the render thread gone, worst frame's bake time 25 -> 9 ms. Same picture." },
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
              choices = { "0", "9", "12", "20", "33" }, note = { ["0"] = "fixed budget", ["9"] = "default" },
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
              choices = { "2", "4", "8", "11", "15" }, tip = "Threads of the per-frame zombie batches (bone math, transition evaluation, lighting reads; the game thread joins in); more than cores - 1 is clamped. Default 8, or on a CPU with fast and efficient cores and more than nine cores one per core besides the game thread's, at most 11 (a Ryzen AI 9 HX 370 handheld: 11 ran a horde frame about 5 % faster than 8)." },
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
            { key = "lookupMemo", label = "Remember clothing and animation name lookups",
              tip = "Dressing a new zombie looks up dozens of clothing and animation names in tables that ignore upper and lower case, so each lookup compared strings letter by letter all the way down the table. The exact names already asked are remembered, so the same name again is one hash probe. Same answers: 3.1 million lookups compared on the Louisville horde, none different. Hordes appearing cost less game-thread time." },
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
            { key = "separateMemo", label = "Zombie crowding: each answer once per frame",
              tip = "When zombies push each other apart, every zombie asks of each neighbour whether it counts as solid (its state, sitting, ragdoll) and whether a wall stands between their squares. While that pass runs nothing in the world changes, so each neighbour's answer is now worked out once per frame and each worker thread remembers the wall answers it already found. Same answers, about a third of the pass." },
            { key = "nearVehicleGrid", label = "Find nearby cars from one map per frame",
              tip = "Every zombie asks each update whether a car is within four squares; stock looked up the 16 chunks around it and their car lists one by one. One table of which chunks hold a car, built once per frame, answers \"none\" for most of them; when a car may be near, the stock search runs. Same answers (2 % of the game thread in a horde)." },
            { key = "playerLosFast", label = "Remember spotted zombies in a set",
              tip = "The list of everything you have spotted since the last quiet moment is searched once per spotted object per frame; stock walked it end to end each time, so in a horde the cost grew with the square of the zombies in view (12 % of the game thread in Louisville). The search is one hash probe now, and the sneak modifier zombies ask for is computed once per frame." },
            { key = "playerLosParallel", label = "Player sight: each zombie's checks on other cores",
              tip = "Every frame the game checks every zombie, animal and car against your sight: how far it is, whether its square is in view, whether it is climbing through a window. Those reads are made on the worker threads, and the game thread then applies the results one by one in the usual order (fade, spot roll, counters). A zombie nobody can see is finished on the worker. Same result, checked object by object. On by default since 2026-10-15." },
            { key = "zombieSpotFast", label = "Skip the spot roll for zombies that cannot see you",
              tip = "A zombie facing away from you or with you beyond its vision radius already has a zero chance to notice you; it skips the remaining modifiers, the car test and the dice roll (same outcome), instead of computing them all first." },
            { key = "charDrawPrep", label = "Zombie draw data built on other cores",
              tip = "The draw data of every zombie model on screen (its lights, one render record and matrix palette per body and clothing model, the depth and light setup) is built on worker threads before the game thread queues the draws in the stock order; stock built it one zombie at a time on the game thread (13 % of it in a Louisville horde). Same pixels." },
            { key = "hordeBatch", label = "Draw the 3D zombies in batches",
              choices = { "off", "exact", "defer" },
              note = { ["off"] = "stock: every zombie its own little scene", ["exact"] = "same pixels", ["defer"] = "fastest; zombies drawn after the other moving objects" },
              tip = "The game draws every 3D zombie on its own: a full GL state save and restore, a shader switch, ~25 settings and a 60-matrix skeleton upload for each of its ~4 models. Batched, the render thread works out the same values, puts them in buffers the GPU reads, and draws a run of zombies with one shader and one draw call per model. In a 450-zombie horde the zombies' GPU time drops from ~6.5 ms to ~1.2 ms a frame. 'exact' keeps the stock drawing order (same pixels, checked pixel by pixel); 'defer' draws all of a frame's 3D zombies at the end of the moving objects, as if the game had listed them last (a semi-transparent edge where a zombie overlaps a flat sprite can blend the other way round). Needs OpenGL 4.3; elsewhere the stock path stays." },
            { key = "hordeBatchUpload", label = "Zombie batches: how the data reaches the GPU",
              choices = { "mapped", "subdata" },
              tip = "mapped: buffers the GPU reads straight from (OpenGL 4.4), written once. subdata: a copy per batch through the driver." },
            { key = "hordeBatchVao", label = "Zombie batches: one vertex setup per mesh layout",
              choices = { "mesh", "format" }, note = { mesh = "one per mesh, made at its first draw", format = "one per layout (default)" },
              tip = "Batched zombies drew each body and clothing mesh through its own vertex setup, made the first time that mesh was drawn; making one made the render thread wait about 1 ms for the graphics driver, about 250 times on the Louisville route, mostly in the frames a horde comes into view. 'format' keeps one setup per vertex layout (two on that route) and points it at each mesh's buffers per draw. Same pixels (checked pixel by pixel). Needs OpenGL 4.3, like the batches." },
            { key = "atlasBatch", label = "Batch the far zombies' sprites",
              tip = "Zombies too far for a 3D model are small pre-rendered sprites, about 1,100 a frame in a Louisville horde, each drawn with its own state change and draw call. They are now queued and drawn in runs, in the stock order and with each sprite's own state, from a vertex buffer the GPU reads directly. Same pixels (checked pixel by pixel). Needs OpenGL 4.4; elsewhere the stock draws stay." },
            { key = "hordeBatchOrder", label = "Zombie batches: models and sprites in two groups",
              choices = { "stock", "window" }, note = { stock = "a batch ends at the next sprite", window = "two groups where nothing overlaps (default)" },
              tip = "In a horde the 3D zombies and the far zombies' sprites alternate in the drawing order, and every switch ended a batch: ~245 batches a frame. With 'window', the models and sprites between two other objects are drawn as two groups; one may move ahead of the other only where their screen rectangles do not overlap, so every pixel is drawn exactly as before (checked pixel by pixel). About 50 batches a frame; the horde's GPU time in the densest seconds of the Louisville route went from ~7 to ~4 ms a frame. Works with the sprite batching above." },
            { key = "hordeBatchUnits", label = "Zombie batches: textures bound 16 at a time",
              choices = { "0", "16" }, note = { ["0"] = "one texture bind before every draw", ["16"] = "default" },
              tip = "Each batched zombie model bound its texture before its draw. Now a batch binds up to 16 of its textures at once and each draw picks its own, so the render thread binds about a sixteenth as often and the GPU stalls less between draws. Same pixels (checked pixel by pixel). Needs OpenGL 4.3, like the batches." },
            { key = "renderPrewarm", label = "Prepare the horde's drawing while the world loads",
              tip = "The first zombie drawn in 3D made the render thread set up the zombie batches' shaders and buffers, the far zombies' sprite buffer and the first clothing pictures (a new framebuffer and about 11 textures): on the Louisville route the frame the horde came into view took 27 ms and the first clothing picture 12-17 ms. These are now made once at the first frame of the world, about 0.2 s during loading. The same objects, made earlier: nothing drawn changes." },
            { key = "bodyAtlasPrewarm", label = "Prepare the far zombies' body sprites while you play",
              tip = "Far zombies are drawn as flat sprites made from 128 generic body pictures, which the game drew in the frames a horde first came into view. They are now drawn two a frame from a second after the world appears, so the horde's first sight finds them ready. Same pictures, made earlier." },
            { key = "renderPrewarmOutlines", label = "Prepare the outline passes while the world loads",
              tip = "The first outlined character or object (a highlighted zombie, an item under the mouse) made the render thread set up the outline passes: three screen-sized framebuffers and their shaders, a 10-11 ms frame on the Louisville route. With the prepare option above they are made at the first frame of the world instead. Same pixels; the three framebuffers take their video memory from the start." },
            { key = "hordePaletteMismatch", label = "Zombie batches: palette compare in one step",
              tip = "Zombies whose bone palettes are identical share one upload; the compare read the two palettes eight bytes at a time through a call that walked the stack each time until the JIT compiled it (about 0.6 ms of render thread a frame when a horde first comes into view). Now one memory compare of both ranges. Same answer (checked over 228,114 compares)." },
            { key = "charDrawThreads", label = "Zombie draw data threads",
              choices = { "4", "8", "12", "14" }, tip = "Threads of the zombie draw-data pool; the data has to be ready before the zombies are queued, so on a 16-core machine 14 keep the game thread from waiting. More than cores - 2 is clamped." },
            { key = "zombieAtlasFast", label = "Flat draw call for far zombies",
              tip = "A zombie too far for a 3D model is drawn as a small pre-rendered sprite; its draw goes through a flat copy of the game's render chain (the same tests, the same sprite call) instead of five nested virtual calls per zombie. ~1,100 such zombies per frame in a Louisville horde. Same pixels." },
            { key = "renderLastFast", label = "Skip the empty per-character inventory walk",
              tip = "After drawing, every character looked through its whole inventory each frame for items that draw something then; none of the game's items do. ~2,500 zombie inventories a frame on the Louisville horde, 0.05 ms of the frame. Off when Java mods are loaded. Same picture." },
            { key = "actionSnapshotFilter", label = "Read only the animation variables that need it",
              tip = "Before a zombie's transitions are evaluated on a worker, the game thread reads the variables whose engine callback has a side effect. It used to resolve every variable of every transition to find out which those are; the answer only depends on the variable's name, so it is now decided once per state. Same values." },
            { key = "emitterParamSkip", label = "Skip sound parameters for silent characters",
              tip = "A character's sound parameters (the floor material under its feet, the room it is in) are recomputed only while it actually has a sound playing or about to start; stock recomputed all of them for every zombie every frame and wrote them nowhere. Same sounds." },
            { key = "separateFast", label = "Push-apart for zombies: cheap pass",
              tip = "The pass that pushes overlapping zombies apart skips the half of the engine's version that only ever applies to the player, and the grid answer 'is my square walled off from that neighbour' is computed once per square per frame instead of once per zombie. Same positions." },
            { key = "separateParallel", label = "Push-apart for zombies: on other cores",
              tip = "That push-apart is computed for the whole horde on worker threads before the update loop; the game thread applies each zombie's result at the same point in its update as before, so the collide events and window climbs keep their order. The neighbours' positions are read at the top of the frame instead of as the loop advances." },
            { key = "ragdollGate", label = "Skip the ragdoll question when nothing is a ragdoll",
              tip = "The engine asks 'is this character a ragdoll?' about 60,000 times a frame in a big horde (every zombie's push-apart asks it of each neighbour), and each answer looked up the character's body model first. While no character is ragdolling the answer is no without looking at anyone; a character with no ragdoll in its animation player says no from that player. Same answers." },
            { key = "bodyModelCache", label = "Remember each character's body model",
              tip = "Every request for a character's animation player first worked out its body model (male, female, skeleton) from its descriptor and compared it with the player's. The answer is kept on the character and dropped exactly when one of its inputs changes (descriptor, gender, skeleton, the body models, the player itself). Same players." },
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
            { key = "zombieLodFixed3d", label = "Zombie detail: a fixed number of 3D zombies",
              choices = { "0", "64", "128", "192", "256" },
              note = { ["0"] = "follow the frame cap (the setting above)" },
              tip = "Instead of following the frame cap, always draw this many of the nearest zombies as 3D models and the rest as flat sprites." },
            { key = "zombieLodHysteresis", label = "Zombie detail: places a 3D zombie keeps its model",
              choices = { "0", "8", "16", "32" },
              note = { ["0"] = "stock: the nearest win every frame" },
              tip = "A zombie already drawn in 3D keeps its model while it ranks up to this many places past the 3D count; a zombie that is a flat sprite needs a place inside the count. The edge of the 3D group then does not flip between model and sprite while zombies walk or the view turns (it can hold this many more models)." },
            { key = "zombieLodSpriteLight", label = "Zombie detail: flat zombies in the light",
              choices = { "0", "40", "60", "80" },
              note = { ["0"] = "stock: black silhouettes" },
              tip = "The game draws its flat zombie sprites as black silhouettes. With this they take this share of their square's light, closer to the lit 3D zombies beside them. Without \"Zombie detail follows the frame cap\" (or a fixed number) only new zombies waiting for their 3D model are lit (\"new zombies start as sprites\"); the far sprites stay as the game draws them." },
            { key = "zombieLodFadeMs", label = "Zombie detail: crossfade (ms)",
              choices = { "0", "150", "300", "500" },
              note = { ["0"] = "stock: an instant switch" },
              tip = "A zombie switching between its 3D model and the flat sprite fades from one to the other over this long instead of popping. During the fade both are drawn." },
            { key = "zombieFirstSightSprite", label = "Zombie detail: new zombies start as sprites",
              tip = "When a horde comes into view, a few hundred zombies take their 3D model within a second and each first makes its clothing picture on the render thread, up to about 90 in one frame (10 ms). With this on, those pictures are spread over the next frames, nearest zombies first, within the budget below; a zombie still waiting is not drawn yet (as the game does while a picture is not ready) and waits at most a quarter of a second (more pictures a frame when needed). A zombie that was already drawn with its clothes (hit, hat falling off, killed, dragged) never waits. With \"Zombie detail follows the frame cap\" on, a waiting zombie is that option's flat sprite instead and its model fades in over it. On by default since 2026-10-15 (it only changes what you see for a moment: a new zombie in a horde appears a few frames later)." },
            { key = "zombieCompositeBudgetUs", label = "Zombie detail: clothing pictures per frame (us)",
              choices = { "250", "500", "1000", "2000" }, note = { ["1000"] = "default" },
              tip = "The render-thread time a frame may spend on new zombies' first clothing pictures when new zombies start as sprites (at least one picture a frame)." },
            { key = "zombieCompositeMaxPerFrame", label = "Zombie detail: clothing pictures per frame (count)",
              choices = { "0", "1", "2", "3", "5", "8" }, note = { ["0"] = "only the time budget above", ["3"] = "default" },
              tip = "When new zombies start as sprites, at most this many first clothing pictures a frame. Each one also costs about 0.2 ms of GPU time, which the render-thread budget above does not see." },
        },
    },
    {
        title = "Entity updates on the worker threads", clip = "horde",
        entries = {
            { key = "entityUpdateParallel", label = "Zombie updates on other cores",
              tip = "The update loop itself - the only part of the frame that grows with the whole moving-object population - runs the calm zombies on the worker threads instead of one after another on the game thread, with the stock four-step sequence per zombie kept. Players, animals, vehicles and busy zombies stay on the game thread; Lua events fired from a worker are replayed on the game thread in the stock order; a zombie that fails on a worker turns the batching off for the rest of the session. The batched zombies see each other where they stood at the start of the batch (like the separation pass), and their random rolls come in a different order. In a 2,700-zombie horde: 67 -> 95 fps; the horde behaves the same in the brawl check (2026-10-13). On by default." },
            { key = "entityUpdatePipeline", label = "Entity updates: overlap with the game thread",
              tip = "With zombie updates on other cores, the workers run the frame's zombies while the game thread updates the animals, vehicles and busy zombies, instead of the game thread waiting for the batch. The frame-timing multiplier each zombie reads is captured per bucket, so timings stay right. Only active while the setting above is on." },
            { key = "entityUpdatePlayerFirst", label = "Entity updates: players and cars before the batch",
              tip = "With the overlap above, your character and the vehicles update before the zombie batch starts instead of beside it. Your line of sight tells the zombies it sees that they saw you, and a moving car marks the zombies it touches; doing that while those zombies were updating on the workers lost part of it (in the brawl check the horde closed in noticeably slower), and the car's polygons are brought up to date before the workers read them. No measurable cost; on by default." },
            { key = "entityUpdateMoreStates", label = "Entity updates: sitting zombies too",
              tip = "With zombie updates on other cores, zombies sitting against a wall or resting in their default state also update on the worker threads; their state does nothing of its own in single player, so their update is the same one the idle zombies already run there. In downtown Louisville that is about 300 zombies a frame." },
            { key = "postupdateParallel", label = "Zombie movement on other cores",
              tip = "After the updates, each calm zombie's move for the frame (its push, the walls and fences it slides along, the stairs, the square it lands on) is computed on the worker threads; the game thread only moves it to its new square, in the stock order. A zombie near a car, against a fence or touching a special object moves on the game thread as before. Checked against the stock movement: 0 differences in 692,000 moves." },
            { key = "postupdateNoopSkip", label = "Zombie movement: skip square moves that change nothing",
              tip = "A zombie that ends its move on the square it was already listed on no longer re-checks that square's list in the movement loop. Same lists." },
            { key = "postupdateEligibleOnWorker", label = "Zombie movement: eligibility checked on other cores",
              tip = "Which zombies may compute their move on the worker threads is decided in each zombie's own task instead of in a pass over every zombie on the game thread. Same zombies." },
            { key = "postupdateVehicleOnWorker", label = "Zombie movement near cars on other cores",
              tip = "A zombie whose move comes within reach of a car resolves the car on its worker thread (the game's own collision, under its lock) instead of moving entirely on the game thread. Same moves." },
            { key = "postupdateFuse", label = "Zombie animation decisions under the movement loop",
              tip = "A zombie with no target starts its animation-state decision on the worker threads while the game thread is still moving the others; when nothing changes it also runs its animation there. The game thread then finishes every zombie in the stock order. Same states and animations." },
            { key = "postupdateClean", label = "Zombie movement loop skips finished zombies",
              tip = "A calm zombie with no target whose whole movement step needs nothing of the game thread finishes it on its worker, and the game thread's loop passes over it without reading it. Same results." },
            { key = "separateEdgeCache", label = "Zombie crowding: wall answers shared between cores",
              tip = "When zombies push each other apart, whether a wall stands between two squares is worked out once per frame and kept on the square for every worker thread; before, each worker asked again. Same answers." },
            { key = "luaSilentSkip", label = "No per-zombie Lua event when nothing listens",
              tip = "Each zombie update raised a Lua event that the worker threads recorded and the game thread replayed; while no Lua function listens to it (none in the base game) it is not recorded. A mod that listens gets it as before." },
            { key = "randPerWorker", label = "Zombie dice rolls: one random stream per core",
              tip = "The zombie work on the worker threads drew every random number (spotting, lunges, groans) from the game's one generator behind a lock, so the cores queued at it. Each worker thread now draws from its own generator of the same kind: the same odds for every roll, but not the same sequence of numbers as the stock game." },
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
            { key = "schedulerFillParallel", label = "Update schedule: the whole sort on other cores",
              tip = "With the update schedule computed on other cores, the rest of that step moves too: the worker threads also make the per-object checks the game thread still made, read each zombie's current behaviour from a remembered reference instead of a table lookup, and the game thread files the results in the usual order from what the workers read, without going back to each zombie. Same schedule, checked list by list. On by default since 2026-10-15." },
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
            { key = "zombieSpawnSpread", label = "Zombie creation queue: new zombies spread over frames",
              tip = "When a chunk next to a horde loads, the game turns dozens of zombies real in one frame, each one dressed and given its animations: a frame of several milliseconds. With this on they are created from a queue within a small time a frame (the frame's spare time under a frame cap), so they appear a few frames later; a zombie within 25 squares of you is still created at once, the first 3 seconds of a load create everything at once as the game does, and none waits longer than the longest wait below, so the horde is never thinned. Changes only when zombies appear, not which or where. On by default since 2026-10-15." },
            { key = "zombieModelAddBudgetUs", label = "Horde first sight: 3D models given per frame (us)",
              choices = { "0", "250", "500", "1000" }, note = { ["0"] = "stock: every zombie at once", ["500"] = "default" },
              tip = "A zombie that gets its 3D model builds the model and every piece of its clothing in that frame; when a crowd comes into view or the view turns, many do it at once (a 4 ms frame). With a budget, the zombies past it stay their flat sprite a frame longer (lit, with \"new zombies start as sprites\") and get their model in the next frames, nearest first. On by default since 2026-10-15 (500)." },
            { key = "burstGovernor", label = "Horde first sight: lighter work in heavy frames",
              tip = "When a horde first comes into view, dozens of frames in a row run late. A frame that started late for its slot or followed a frame that used nearly all of its time now creates fewer queued zombies and gives fewer zombies their 3D model; that work moves to the lighter frames right after. Changes only when zombies appear, not which or where. Acts at a frame cap, on the zombie creation queue and on the 3D model budget (both on by default since 2026-10-15)." },
            { key = "burstLateUs", label = "Horde first sight: a frame this late is heavy (us)",
              choices = { "500", "1000", "2000", "4000" }, note = { ["1000"] = "default" },
              tip = "A frame that starts more than this many microseconds after its slot under the frame cap counts as heavy." },
            { key = "burstWorkPct", label = "Horde first sight: a frame after this much work is heavy (%)",
              choices = { "80", "90", "95", "100" }, note = { ["95"] = "default" },
              tip = "A frame whose previous frame worked for more than this share of the cap's frame time counts as heavy." },
            { key = "zombieSpawnBurstUs", label = "Horde first sight: zombie creation in a heavy frame (us)",
              choices = { "0", "150", "300", "600" }, note = { ["150"] = "default" },
              tip = "The time a heavy frame may spend creating queued zombies." },
            { key = "zombieModelAddBurstUs", label = "Horde first sight: 3D models given in a heavy frame (us)",
              choices = { "0", "150", "300", "500" }, note = { ["150"] = "default" },
              tip = "The time a heavy frame may spend giving zombies their 3D model." },
            { key = "zombieModelAddHeavyEvery", label = "Horde first sight: 3D models only every Nth heavy frame",
              choices = { "0", "2", "4", "8" }, note = { ["0"] = "every heavy frame", ["4"] = "default" },
              tip = "In a run of heavy frames only every Nth one gives zombies their 3D model; in the others they stay flat sprites a frame longer." },
            { key = "zombieSpawnSlackBudgetUs", label = "Zombie creation queue: use the frame's spare time (us kept free)",
              choices = { "0", "1000", "1500", "2500" }, note = { ["0"] = "a fixed time a frame", ["1500"] = "default" },
              tip = "At a frame cap, queued zombies are created in the time the last frame left unused before the cap, less this margin, instead of a fixed time a frame. Only with the zombie creation queue on." },
            { key = "zombieSpawnHeavyMin", label = "Zombie creation queue: zombies a heavy frame creates at least",
              choices = { "0", "1", "2" }, note = { ["0"] = "default: none unless they waited too long", ["1"] = "the earlier rule" },
              tip = "A heavy frame creates at least this many queued zombies. Only with the zombie creation queue on." },
            { key = "zombieSpawnMaxAgeMs", label = "Zombie creation queue: longest wait (ms)",
              choices = { "1000", "2000", "3000", "5000" }, note = { ["3000"] = "default" },
              tip = "A queued zombie waits at most this long, so a slow machine's small per-frame time never thins the horde. Only with the zombie creation queue on." },
            { key = "zombieSpawnAgedCapUs", label = "Zombie creation queue: time a frame for zombies that waited too long (us)",
              choices = { "0", "300", "600", "1500" }, note = { ["0"] = "all at once", ["600"] = "default" },
              tip = "The queued zombies past the longest wait are created within this much time a frame instead of all in one frame (all at once after three times the longest wait). Only with the zombie creation queue on." },
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
            { key = "inputLatch", label = "Read the newest input when a frame starts",
              tip = "The game polled input once, right after the previous frame was shown, and used that until its next frame started (the frame limiter's idle time later). With this on, the game asks for a fresh poll the moment its frame starts: mouse and controller reach the game in ~2 ms instead of ~5.5 at 240 fps, and up to a frame sooner at low frame caps. Applies on the next launch." },
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
            { key = "uiVerify", label = "UI: changes shown the frame they happen",
              tip = "A window kept from an earlier frame is now checked every frame against exactly what its drawing read the last time (the Lua fields it used, the item names, stats and conditions it asked the game for, the mouse position) and drawn again in the frame any of them changes, instead of after a timer of up to a second. Measured at 240 fps with the ground-truth rig (every window drawn every frame for comparison): items renamed or worn in an open inventory late on 43 % of frames before, 0.4 % now; skill XP arriving 61 % -> 0.7 %; mouse over inventory rows and a 200-item stack 25-29 % -> 0 %." },
            { key = "uiAnimate", label = "UI: movement drawn every frame",
              tip = "A window whose picture is changing (smooth scrolling, an item being dragged, a fade) is drawn on every frame while it moves instead of 60 times a second. Wheel scrolling through a 200-item stack showed an out-of-date list on 58 % of frames before, 0 % now. Uses only the time the frame has left before the frame cap (see the next setting); never draws less often than before." },
            { key = "uiSlack", label = "UI: extra redraws only in the frame's spare time",
              tip = "The redraws for mouse movement and animation are done only when they fit in the time the frame has left before the frame cap (measured each frame), so the frame rate does not drop for them; a window that has waited the stock 1/60 s is drawn anyway (never later than before). Uncapped, a fifth of the frame." },
            { key = "uiMoveReplay", label = "UI: dragged windows moved, not redrawn",
              tip = "While you drag a window that draws the same thing wherever it is, its kept picture is moved to the new place every frame (what a desktop compositor does with a window) instead of running its Lua again; it is still redrawn 60 times a second, so a change during the drag shows at the old rate at the latest." },
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
              tip = "Sets the two settings below at once (also from the main menu: PZ OPTIMIZATION > MOD COMPATIBILITY CHECK). Performance (default): Java mods are checked and listed but switch nothing off, and windows mods draw in are reused like the game's own. Compatibility: the settings a Java mod patches are switched off so the mod meets the code its author tested, and windows mods draw in are redrawn at the game's own rate. A value you pick for either setting below still wins. Applies on the next launch." },
            { key = "modCompat", label = "Java mods: switch off what they patch",
              choices = { "auto", "report", "off" }, note = { ["auto"] = "max compatibility", ["report"] = "max performance" },
              tip = "PZ Optimization ships whole game classes; a Java mod (a ZombieBuddy mod, or a -javaagent such as PZMulticore) patches methods of the same classes. At launch the mods' jars are read: where one patches a method we changed, the settings that live in that method are switched off, so the mod meets the code its author tested. Mods tested together with ours keep everything. Your own choice on this tab still wins. Report = only list them (Zomboid/pzopt/mod-compat.txt, the console and the main menu's PZ OPTIMIZATION > MOD COMPATIBILITY CHECK); off = no check. The default follows Mods: max performance (report) or max compatibility (auto). Applies on the next launch." },
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
            { key = "frameQueue", label = "Frame queue: every frame on the cap's beat (adds input delay)",
              tip = "With a frame cap, every frame is held back until a fixed time after the moment of game time it shows, long enough that almost every frame is ready by then (the 99th percentile of the last 2 seconds, GPU time included). A frame whose update or drawing takes longer than usual then still reaches the screen on the beat instead of late, and the frames around it absorb it. The price is input delay: what you do reaches the screen about 3-7 ms later at a 120 fps cap (driving at 120 km/h: 10.9 -> 14.2 ms on average from the frame's start to the screen), more in a big horde. Driving at 120 km/h with a 120 fps cap, frames within 9 ms rose from 92 % to 97.5 %, and to 99.5 % with the GPU's time counted (below). Only while you play a world with a frame cap: never uncapped, in the menus, the pause menu or the loading screen, with vsync on a fixed-refresh display (vsync already sets the beat), on macOS, or with the just-in-time input sleep. Replaces \"Even frame delivery\" while it acts. Off = present pacing as before." },
            { key = "frameQueueGpu", label = "Frame queue: count the GPU's drawing time",
              tip = "The frame queue's holding time also covers how long the GPU still draws after the game has handed a frame over (measured with GPU timestamps a few frames later). Without it a frame whose GPU work runs long is swapped on the beat but shown late. Louisville horde at a 120 cap: frames within 9 ms 96.6 -> 98.7 %, for 3-5 ms more input delay." },
            { key = "frameQueueMinPct", label = "Frame queue: shortest holding time (% of a frame)",
              choices = { "60", "100", "160", "200", "260" }, note = { ["60"] = "follow the recent frame times (the earlier default)", ["260"] = "default: fixed, as long as the longest" },
              tip = "The frame queue holds each frame for a time between this and the longest holding time below, in percent of the cap's frame time, following the slowest recent frames. At 260 with the longest at 260 (the default since 2026-10-15) the holding time is fixed at 2.6 frames, 21.7 ms at a 120 fps cap: it never has to grow after a slow frame, which is when the frames after one came late. Louisville horde at a 120 cap: about 0.4 points more frames within 9 ms than the adaptive holding time, for about 2.5 ms more input delay on average. Lower = less input delay, more late frames. Applies at frame caps from the setting \"fixed holding time from (fps)\" up; below it the shortest holding time for low caps is used." },
            { key = "frameQueueMaxPct", label = "Frame queue: longest holding time (% of a frame)",
              choices = { "160", "200", "260", "300" }, note = { ["260"] = "default" },
              tip = "The longest the frame queue holds a frame, in percent of the cap's frame time. Above about 2.5 frames the game thread starts to wait for a free frame slot." },
            { key = "frameQueueFixedMinFps", label = "Frame queue: fixed holding time from (fps)",
              choices = { "0", "60", "100", "144" }, note = { ["0"] = "at every frame cap", ["100"] = "default" },
              tip = "The shortest holding time above (2.6 frames by default, a fixed holding time) applies only at frame caps of this many fps or more. 2.6 frames are 21.7 ms at a 120 fps cap but 43 ms at 60 and 87 ms at 30, a delay you feel; below this cap the queue follows the slowest recent frames instead, from the shortest holding time for low caps below (10 ms at 60 fps, 20 ms at 30), and starts there again after a load, a menu or a cap change, settling within a few seconds (the low-cap entries below)." },
            { key = "frameQueueLowCapMinPct", label = "Frame queue: shortest holding time at low caps (% of a frame)",
              choices = { "0", "60", "100", "160", "260" }, note = { ["60"] = "default: follow the recent frame times" },
              tip = "Below the cap set in \"fixed holding time from (fps)\", the frame queue holds each frame for at least this share of the cap's frame time and otherwise as long as the slowest recent frames need, up to the longest holding time. Lower = less input delay, more late frames." },
            { key = "frameQueueLowCapCalmMs", label = "Frame queue at low caps: start from the shortest holding time (ms of steady frames)",
              choices = { "0", "500", "1000", "2000" }, note = { ["0"] = "learn from the first frame (round 11: the loading bursts set it to the longest)", ["1000"] = "default" },
              tip = "Below the cap set in \"fixed holding time from (fps)\": after a world loads, a menu or the pause menu closes or the cap changes, the frame queue starts at its shortest holding time and learns from the frames only once they have been ready within one frame of their beat for this long (at most 10 s). Without it the slow frames of a world's first seconds set the holding time to its longest, 43 ms at a 60 fps cap and 87 ms at 30, and it took 20 s and over a minute to come down: that much more input delay." },
            { key = "frameQueueLowCapDecayPct", label = "Frame queue at low caps: how fast the holding time falls (% of a frame per second)",
              choices = { "0", "12", "25", "50" }, note = { ["0"] = "1 ms a second, as at high caps", ["25"] = "default" },
              tip = "Below the cap set in \"fixed holding time from (fps)\", after a burst of slow frames the holding time comes down by at most this share of the cap's frame time a second: 25 % is 4.2 ms a second at 60 fps and 8.3 ms at 30 (the fixed 1 ms a second of high caps took over a minute at 30). Lower = steadier after a burst, more input delay." },
            { key = "frameQueueLowCapMarginPct", label = "Frame queue at low caps: safety margin (% of a frame)",
              choices = { "0", "10", "20", "30" }, note = { ["20"] = "default" },
              tip = "Below the cap set in \"fixed holding time from (fps)\", the holding time is the slowest recent frames' time plus this share of the cap's frame time (20 %: 3.3 ms at 60 fps, 6.7 ms at 30). With the holding time coming down within seconds, the margin is what keeps a frame that is a little slower than the recent ones on the beat: replayed on a Louisville horde route, 20 % had no more late frames than the old slow-falling holding time, which stayed at 50-87 ms at a 30 fps cap. Lower = less input delay, more late frames." },
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
        title = "CPU cores and power (laptops and handhelds)", clip = "spin",
        entries = {
            { key = "corePlacement", label = "Which cores the game's threads run on (hybrid CPUs)",
              choices = { "auto", "efficient", "performance", "off" },
              note = { auto = "background work on the efficient cores, the game and render threads there too while they keep the frame cap", efficient = "every thread on the efficient cores", performance = "game and render threads on the fast cores, the rest on the efficient ones", off = "stock: the operating system decides" },
              tip = "CPUs such as the Ryzen AI 300 series (Zen 5 + Zen 5c), Intel's P + E core chips and Apple silicon have fast cores that cost several times the power of their efficient ones for the same work: one busy thread drew 9.2 W on a Zen 5 core and 2.2 W on a Zen 5c core of an Ayaneo Flip. Linux put the game's lighting, compiler, audio and loader threads on the fast cores as often as not. Auto keeps all of that on the efficient cores and moves the game and render threads to the fast cores only while they would otherwise miss the frame cap. Linux sets the cores directly; macOS gets the matching quality-of-service classes. Applies on the next launch." },
            { key = "workerQosMac", label = "Priority of the game's helper threads (Mac)",
              choices = { "user-initiated", "utility", "user-interactive" },
              note = { ["user-initiated"] = "work the player waits for: keeps its cores when other programs are busy", utility = "background work: the efficient cores first, gives way to other programs", ["user-interactive"] = "the game and render threads' own class" },
              tip = "The helper threads that update and draw the zombies with the game thread every frame. macOS ran them at the background 'utility' class: with a virtual machine, a build or Spotlight busy beside the game they lost their cores, and the game thread waited for them every frame (Louisville horde on a MacBook Pro M1 Pro with four busy cores beside the game: 35 fps). At user-initiated they keep up (55 fps, the same as with nothing else running). No effect on Windows or Linux. Applies on the next launch." },
            { key = "gpuPstate", label = "AMD GPU clock level while playing (Linux)",
              choices = { "auto", "off", "standard", "min_sclk" },
              note = { auto = "the lowest fixed clock whose frame time fits the cap, automatic clocks otherwise", off = "stock: the driver's automatic clocks", standard = "a fixed ~1 GHz", min_sclk = "the lowest shader clock (~640 MHz)" },
              tip = "At a frame cap the Radeon driver runs the GPU at its top clock (2.7-2.9 GHz on a Radeon 890M, the highest voltage) for a few milliseconds and then idles it. Most of the game's GPU work waits on memory, not on the clock: at a fixed 640 MHz the 890M still held 120 fps on the bench walk. Auto measures the GPU time of every frame and holds the lowest clock level that keeps the cap, stepping up the moment it does not. The level applies to the whole GPU while the game runs and is released when it exits (a crash included). Applies on the next launch." },
            { key = "jitSteady", label = "Java compiler: no speculative recompiles",
              tip = "Java's optimizing compiler leaves out the branches a method never took while it was being profiled; when the game later takes one (a new street, a different zombie state) the compiled code is thrown away and compiled again. A 100 s walk through the world did that ~2,000 times and kept a compiler thread busy the whole walk (16,600 compilations). With this on the compiler keeps every branch: the compiler's CPU time halves, the game runs as fast. Since 2026-10-15 it also stops the compiler from betting on the types a method was seen with (-XX:-UseTypeSpeculation): about 45 such bets failed on the Louisville route, one of them in the player's sight check at the horde's first sight. Written into the game's launcher file for the next launch; the uninstallers take it out again." },
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
              choices = { "0", "5", "10", "25", "50", "100" }, note = { ["0"] = "auto: 5 ms with the tuned settings below, else G1's own 200 ms" },
              tip = "The longest pause G1 aims for. Auto (0, the default) is 5 ms together with \"G1 tuned for steady frames\" and G1's own 200 ms target without it. With 200 ms the pauses in a horde were 50-75 ms long; with 5 ms and the tuned settings they are typically 3-5 ms. Applies on the next launch." },
            { key = "gcG1Tuned", label = "G1 tuned for steady frames",
              tip = "With G1 (the default collector), the next launch also gets the settings measured on the Louisville horde: a small young generation collected often in short pauses, objects that survive moved straight to the old generation instead of being copied back and forth, the old generation cleaned up in many small steps, and a larger space for compiled code so it no longer starts extra collection cycles. Pauses went from 7-12 ms to typically 3-5 ms at the same heap size. Written into the game's launcher file only on the Java version it was tested with, and left out when you set any of these G1 options yourself; the uninstallers take them out again. Applies on the next launch." },
            { key = "gcSlack", label = "Garbage collection in the frame cap's spare time",
              tip = "With a frame cap, the collection of short-lived objects (a pause of a few milliseconds) that would land in the middle of a frame is started a few frames early, at the end of a frame that has time to spare before the cap, so it no longer makes a frame late. It fills the rest of the young generation with throwaway memory to start the collection at that moment; the game itself is untouched. On the Louisville horde at 120 fps a scheduled collection cost the frames 1-3 ms instead of 10-14. Needs G1 (the default); without a cap nothing changes." },
            { key = "gcSlackQueue", label = "Spare-time garbage collection under the frame queue",
              choices = { "slack", "off", "swap", "hold" },
              note = { slack = "default: in the spare time, only while the held frame's swap is far enough away", off = "the spare-time rule as without the queue", swap = "from its own thread right after a held swap", hold = "from its own thread inside a long hold" },
              tip = "Where the collection above runs while the frame queue holds frames. In the game thread's spare time it could overlap the end of a hold and make that frame late (8 of 28 collections in a Louisville run); slack waits for a frame whose swap leaves room for the collection: 35 of 35 collections without a late frame." },
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
              tip = "Once per boot the main menu asks the GitHub releases (one request to api.github.com) whether a newer build for this game revision exists. The main menu's PZ OPTIMIZATION item then says \"Update available\" under it, and so does its screen's UPDATE entry: its dialog downloads the zip, replaces the installed files and asks to restart so the next launch loads them. Nothing is downloaded without that click. Applies on the next launch." },
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
            { key = "handoffRecalcFast", label = "Chunk joining: border recalc without repeats",
              tip = "When a loaded chunk joins the world, its border squares are recalculated against the neighbours (collision, pathfinding, sight). The game recomputed each square's tile properties about fifteen times and its walkable-neighbour links dozens of times in that pass; now once each, with the same result. About half the cost of that pass." },
            { key = "propIdCache", label = "Tile property lookups by id",
              tip = "Questions like 'does this tile carry a fuel amount' looked the property up by name in a table every time; the answer's id is now kept per property type. Same answers, less work for chunk joining and drawing." },
            { key = "slackWork", label = "Deferred work in the frame cap's spare time",
              tip = "With a frame cap, the time left in a frame before the limiter waits is used for queued game-thread jobs: freshly loaded chunks joining the world, far loot rolls and far zombie spawns run one at a time while each one's learned cost still fits, instead of all at once in the frame they arrive. Without a cap nothing changes." },
            { key = "chunkHandoffCost", label = "Chunk joining: cost estimate",
              choices = { "chunk", "square" }, note = { ["chunk"] = "default", ["square"] = "until 2026-10-15" },
              tip = "How the spare-time scheduler guesses whether the next chunk's joining fits the time left in a frame. chunk: the running mean of whole joinings. square: a learned cost per square that one heavy chunk made pessimistic for a long time, so chunks waited and then joined several at once in one frame." },
            { key = "chunkHandoffSlackMaxWait", label = "Chunk joining: longest wait for spare time (frames)",
              choices = { "0", "6", "30" }, note = { ["0"] = "600 frames, until 2026-10-15", ["6"] = "default" },
              tip = "A loaded chunk that does not fit a frame's spare time joins after this many frames anyway, in a light frame, so a queue never builds behind one heavy chunk. A world load or a teleport keeps the stock count." },
            { key = "chunkHandoffForceAnyFrame", label = "Chunk joining: an overdue chunk joins in the next spare time",
              tip = "A chunk past its longest wait joins in the next frame's spare time, light frame or not (still one such chunk a frame)." },
            { key = "chunkHandoffFrameBudgetUs", label = "Chunk joining: time a frame may spend on a backlog (us)",
              choices = { "0", "1000", "1500", "3000" }, note = { ["0"] = "stock: the whole count", ["1500"] = "default" },
              tip = "When chunks queue up while you move, a frame joins them only until this much time is used (at least one); the rest join in the next frames' spare time. Each such frame took over 4 ms before. A world load or a teleport keeps the stock count." },
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
            { key = "shaderWarmup", label = "Build shaders ahead of play",
              tip = "Shaders that were built the first time something needed them, in the middle of play, are built ahead: every model shader the game's scripts name during the loading screen (the first car of a session stopped the game for up to half a second), and the Visuals' own programs (foliage sway, god rays, wet blood) as the world appears. Matters most on the first launch after an update, when the graphics driver has none of them cached. Applies on the next launch." },
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
            { key = "aotNoIndy", label = "Warm-start cache: save it without lambda linking (makes it work)",
              tip = "With the game's Java (Zulu 25.0.1) the warm-start cache was never written: Java crashed while saving it at the end of the recording launch (an hs_err file in the game folder after every second quit), so the next launch found no cache and recorded again, forever. Saved without Java's pre-linked lambda calls (-XX:-AOTInvokeDynamicLinking) the cache is written and keeps the loaded classes and all method profiles. Needs the warm-start cache on. Written into the game's launcher file for the next launch; the uninstallers take it out again." },
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
            { key = "entityShadows", label = "Shadows on characters and vehicles",
              tip = "The player, zombies, animals and cars take the shadows of buildings, walls, roofs and trees where they stand, part by part: a zombie half behind a wall has its legs in the shade and its head in the sun, a car under a porch roof is dark under the roof only. With Sun shadows turned on during a game, part by part from the next launch (one shade for a whole character until then). Off: one shade for a whole character, none for cars." },
            { key = "entityShadowFormPct", label = "Shadows on characters: sunlit side (%)",
              choices = { "0", "25", "50", "75", "100" }, note = { ["50"] = "default" },
              tip = "How much the side of a body facing the sun is brighter and the far side darker. The overall brightness stays the same. 0: flat, as the game draws them." },
            { key = "entityShadowCasters", label = "Shadows on characters: from other characters and cars",
              tip = "Characters and cars also shade each other: a zombie standing in a car's shadow, the player in a crowd's. Cheap with few characters; in a dense crowd at a shadow's edge about 0.02 ms a frame on an RTX 4090." },
            { key = "entityShadowSelf", label = "Shadows on characters: their own shadow",
              tip = "A character's or a car's own body shades itself: an arm on the chest, a hat brim on the face, the cabin on the hood. For the 16 nearest characters, when zoomed in enough to see it." },
            { key = "entityShadowImpostors", label = "Shadows on characters: far zombies",
              tip = "Zombies drawn as flat pictures (far away, big hordes) take the shade of where they stand too, one shade for the whole body." },
            { key = "entityShadowTorches", label = "Shadows on characters: torches and headlights",
              tip = "At night (and in dark places), a character standing in another character's or a car's shadow from a torch or headlights is shaded too, as the ground there: a zombie behind another in your torch beam stays dark. With \"Shadows from torches and headlights\" this matches the shadows on the ground. Costs nothing measurable." },
            { key = "entityShadowAoPct", label = "Shadows on characters: from the bodies next to them (%)",
              choices = { "0", "30", "60", "100" }, note = { ["60"] = "default", ["0"] = "off" },
              tip = "Characters standing close together, or against a car, hide part of the surrounding light from each other: the sides that face each other are a little darker, so a packed crowd has depth instead of every body lit the same. About 0.01 ms a frame in a dense crowd on an RTX 4090." },
            { key = "entityShadowClouds", label = "Shadows on characters: clouds",
              tip = "With cloud shadows on, a cloud's soft shadow crosses characters and cars as it crosses the ground: a car half under a cloud's edge is half shaded, and a character's head can be in the sun while their feet are not (the shadow falls along the sun). Off: one cloud value for each character or car. Costs nothing measurable." },
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
        title = "Puddle ripples (footsteps, raindrops and car wakes)", clip = "hdr",
        entries = {
            { key = "ripples", label = "Puddle ripples",
              tip = "Anyone walking or running through a puddle, zombies, animals and you, rings it at every step, and car wheels leave trails of rings behind them. The rings are real water: each one a train of waves that spreads and fades (the longer waves run ahead), they cross and add up, and they bend whatever the puddle mirrors (the sky, and with Reflections on the zombies and the buildings). Only puddles big enough to show do it, outdoors, only where someone steps. The rain rings puddles and wet ground with real drop rings instead of the game's grid of half rings (cheaper than the game's own), and cars leave a continuous wake. Nearly free: nothing at all when it is dry or nobody walks in a puddle. Windows, Linux and macOS. Turning it on applies on the next launch, the rest at once." },
            { key = "ripplesStrengthPct", label = "Puddle ripples: strength (%)",
              choices = { "50", "100", "150", "200" }, note = { ["100"] = "default" },
              tip = "How much the rings tilt the puddle's surface (how strongly they catch the light and bend the reflection)." },
            { key = "ripplesVehicles", label = "Puddle ripples: from car wheels",
              tip = "Moving cars ring the puddles under their wheels too." },
            { key = "ripplesPerSec", label = "Puddle ripples: rings a second at most",
              choices = { "80", "160", "320", "640" }, note = { ["160"] = "default" },
              tip = "A horde walking through flooded streets would start hundreds of rings a second; past this many, each footstep makes a ring by chance (your own always do), so a crowd still churns the water but costs no more than a few walkers. Higher: busier water in hordes, a little more work." },
            { key = "ripplesMode", label = "Puddle ripples: method",
              choices = { "alu", "tiled", "world", "sim", "field", "loop" }, note = { ["alu"] = "default" },
              tip = "How the rings are drawn; all but sim look the same. alu: each part of the screen looks only at the rings that reach it, the wave shape computed (the cheapest, most on weak GPUs). tiled: the same, the wave shape read from a table (the exact simulation's). world: the rings sorted once for their whole life instead of every frame (least work for the game, more for the GPU). sim: the water is simulated on the GPU, so the rings also bounce off the puddle's edges (needs OpenGL 4.3). field: the rings drawn once into a texture the puddles read (needs OpenGL 4.3). loop: every puddle pixel looks at every ring (slow, for comparison)." },
            { key = "ripplesRain", label = "Puddle ripples: raindrops",
              choices = { "flipbook", "field", "sim", "cells", "texture", "stock", "none" }, note = { ["flipbook"] = "default" },
              tip = "The rings the rain makes on puddles and wet ground. The game's own (stock) draw a grid of half rings that pulse in place; these are real drop rings at random spots, each a small train of waves that spreads and fades, filtered so they never shimmer when zoomed out. cells: worked out per pixel (no textures). texture: drops read from a small texture (Lagarde's technique from Remember Me). flipbook (default): a precomputed loop of the whole rain field (two texture reads): cheaper than the game's own rings. field: drawn once a frame into a small texture the puddles read (needs OpenGL 4.3). sim: the water simulated on the GPU, rings crossing each other for real (needs OpenGL 4.3). none: no rings at all." },
            { key = "ripplesRainPct", label = "Puddle ripples: raindrop strength (%)",
              choices = { "50", "100", "150", "200" }, note = { ["100"] = "default" },
              tip = "How strongly the raindrops' rings catch the light." },
            { key = "ripplesWake", label = "Puddle ripples: car wakes",
              choices = { "trail", "kelvin", "particles", "sim", "rings" }, note = { ["trail"] = "default" },
              tip = "What a car leaves in the puddles it drives through. trail: two tyre grooves that fill back in and a wave running out of each, a continuous wake however fast you drive. kelvin: the V-shaped wake of a boat. particles: each tyre sheds little waves to both sides as it moves (wave particles). sim: the wheels push the simulated water (the footsteps then run in that simulation too; needs OpenGL 4.3). rings: a ring at the rear wheels every few squares (the first version). trail, kelvin and particles need OpenGL 4.3 (else rings)." },
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
              tip = "On: window panes reflect as well (subtly, as glass does). Off: no window reflects (wall mirrors and glass props keep theirs). Applies on the next launch." },
            { key = "mirrorsWallMirrors", label = "Mirrors: wall mirrors reflect too",
              tip = "On: wall mirrors, mirrored medicine cabinets and dressers, and the gym's wall mirrors reflect the room in front of them and the people in it. Off: they look as in the game (a plain mirror texture), and only the windows and glass props reflect: for window reflections without the mirrors. Applies on the next launch." },
            { key = "mirrorsWindowPct", label = "Mirrors: window reflection strength (%)",
              choices = { "15", "30", "50", "70" }, note = { ["30"] = "default" },
              tip = "How much of a window pane is its reflection. Real glass reflects a few percent head-on, more where the room behind it is dark; higher reads more like a shop window by day. Applies on the next launch." },
            { key = "mirrorsProps", label = "Mirrors: glass, screens and steel reflect too",
              tip = "On: the things in a room that are not mirrors or windows reflect as well: glass doors, shop fronts and railings, shower screens, display counters and cases, glass-door fridges, the glass table, switched-off televisions and monitors, the gym's wall mirrors, steel counters, sinks and appliances, toilets. Each surface reflects the way it faces: a glass top shows what stands behind it, a door or a pane what stands in front, faintly as glass does; steel, ceramic and dark screens keep their own look and show the people and cars passing in front as a soft sheen. Their shape comes from the game's own depth maps, so nothing is guessed per object. Reflections fade where you cannot see (out of your sight the prop is dark, so is its reflection). Costs about a tenth of a millisecond on a handheld with a room full of them, a few hundredths on a fast GPU; worked out once per prop and kept while nothing changes. Applies on the next launch (with Mirror and window reflections on)." },
            { key = "mirrorsPropGlassPct", label = "Mirrors: glass prop reflection strength (%)",
              choices = { "15", "25", "40", "60" }, note = { ["25"] = "default" },
              tip = "How much of a glass door, counter or case is its reflection. Real glass reflects a few percent head-on and more at a grazing angle (a glass top more than an upright pane: the game's camera sees tops at 60 degrees). Applies on the next launch." },
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
        title = "God rays (light shafts through windows, doorways, trees and fog)", clip = "godrays",
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
            { key = "godRaysDoorLeaf", label = "God rays: open doors block the light",
              tip = "An open door stands in its doorway's light: the shaft and its sunlit patch on the floor lose the part the door shades, and a glass door lets its share through." },
            { key = "godRaysDoorSwingMs", label = "God rays: door swing (ms)",
              choices = { "0", "200", "350", "600" }, note = { ["350"] = "default", ["0"] = "at once" },
              tip = "When a door opens the light sweeps in through the widening gap over this long, and it is cut off the same way as the door shuts (0: the light comes and goes the moment the door does)." },
            { key = "godRaysCharShadows", label = "God rays: people's shadows in the shafts",
              tip = "The player, other people and zombies standing in a shaft of light cast their shadow into the lit dust and onto the sunlit patch on the floor." },
            { key = "godRaysDoorDustPct", label = "God rays: dust stirred by doors (%)",
              choices = { "0", "50", "100", "200" }, note = { ["100"] = "default", ["0"] = "off" },
              tip = "A door opening stirs up the dust in its shaft: thicker swirls drift in with the draft and settle over a few seconds." },
            { key = "godRaysSpill", label = "God rays: lamplight spilling out at night",
              tip = "At night a lit room's light spills out through its open doors and windows into the fog and the rain, shaped by the opening." },
            { key = "godRaysSpillPct", label = "God rays: spill brightness (%)",
              choices = { "50", "100", "150", "200" }, note = { ["100"] = "default" },
              tip = "How bright the light spilling out of lit rooms is." },
            { key = "godRaysBouncePct", label = "God rays: light bounced off the sunlit floor (%)",
              choices = { "0", "50", "100", "200" }, note = { ["0"] = "off" },
              tip = "The sunlit patch a shaft lays on the floor bounces a little of its light onto the walls and furniture around it. Subtle next to the game's own room light, so off by default." },
            { key = "godRaysRoofExact", label = "God rays: sun under porch roofs",
              tip = "Off (the default): a window or doorway under a porch roof or a floor above lets no light in. On: the sun comes in under the roof's edge as its height allows, so a low morning or evening sun reaches in under a porch and a high midday one does not. Applies on the next launch." },
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
            { key = "voxelLight", label = "Voxel lighting (needs per-pixel lighting)",
              tip = "Street lamps, house lamps and room lights are traced through the world's walls, windows, doorways, fences, floors and tree crowns: they cast real shadows (a fence, a stair, a building's corner), light reaches a room through its windows, walls facing a lamp catch more of it, and the sky lights rooms from their windows and doorways instead of a flat indoor darkening. Each chunk picture keeps its own light layer, worked out again only when a light or the buildings change, so a still scene costs nothing extra. Needs a graphics card with OpenGL 4.3 (not macOS). Applies on the next launch." },
            { key = "voxelLightTorchShadows", label = "Voxel lighting: torch shadows",
              tip = "A torch you carry casts shadows of walls, fences, floors and crowns too (traced again only when the torch moves)." },
            { key = "voxelLightSkyMethod", label = "Voxel lighting: daylight indoors",
              choices = { "flood", "cascades", "cones" }, note = { ["flood"] = "default" },
              tip = "How the sky's light reaches into rooms. Flood: spread from the open sky through windows and doorways (cheapest). Cascades: rays towards the sky from every square, shared between neighbours (the sky through a window comes from where it faces; ~1 ms when buildings change). Cones: the same with every ray traced (~2 ms when buildings change)." },
            { key = "voxelLightBounce", label = "Voxel lighting: bounced light",
              tip = "Lit floors and walls light their surroundings a little: a lamp pool fills the area round it, a lit window spills warm light outside." },
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
local NEXT_LAUNCH_ONLY = { hdr = true, hdrAuto = true, godRaysRoofExact = true, carOccupant = true, carOccupantOcclusion = true, carOccupantLightPct = true,
    -- per-pixel lighting: read once at start-up (the chunk composite shader is patched when the game loads it)
    pixelLight = true, pplAnalytic = true, pplPointLights = true, pplNormals = true, pplWrapPct = true, pplSmooth = true,
    pplWetSpecular = true, pplSpecPct = true, pplShadows = true, torchSourceSelfShadow = true,
    -- voxel lighting: its shader code is compiled into the per-pixel lighting programs at start-up
    voxelLight = true, voxelLightTorchShadows = true, voxelLightSkyMethod = true, voxelLightBounce = true,
    -- reflections: the water, puddle and chunk composite shaders are patched when the game loads them (only then);
    -- strength and puddles apply at once
    reflections = true,
    -- car glass: the vehicle shaders are patched when the game loads them (only then); its strengths apply at once
    carGlass = true,
    -- mirrors: mirror tiles leave the chunk pictures at start-up; the settings are read once
    mirrors = true, mirrorsWindows = true, mirrorsWallMirrors = true, mirrorsWindowPct = true, mirrorsModels = true, mirrorsGeometry = true, mirrorsViewLateralPct = true, mirrorsViewDropPct = true,
    mirrorsProps = true, mirrorsPropGlassPct = true,
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

local function noteFor(entry)
    return entry.live and LIVE_NOTE or RESTART_NOTE
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
    -- voxel lighting (2026-10-19, the game's own frames: stock vs every default + per-pixel and voxel lighting)
    voxel = "A parking lot at 01:00, street lamps and room lights on, the camera panning east: stock vs voxel lighting (lamps and room lights traced through walls, windows and fences)",
    -- god rays through doors (2026-10-18, runs grmenu-*: the game's own frames at 1:1, the door toggled every 2 s)
    godrays = "The Riverside fire station at 15:00, zoom 1: the sun comes in through the garage doors as they open and close",
    godraysdoor = "A shop door at 17:00, zoom 1, the player just inside: the door settings off vs on (the light follows the door's swing, the open door blocks its share, the player's shadow in the shaft, dust stirred up)",
    godraysspill = "A Rosewood house at 23:00 in dark fog, its lamps on, zoom 1: the rooms' lamplight spills out of the windows and the opening door into the fog",
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
    voxel = { "STOCK GAME", "VOXEL LIGHTING ON" },
    godrays = { "GOD RAYS OFF", "GOD RAYS ON" },
    godraysdoor = { "DOOR SETTINGS OFF", "DOOR SETTINGS ON" },
    godraysspill = { "SPILL OFF", "SPILL ON" },
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
    voxel = ". Same save, route and hour, frames lined up; no counter.",
    godrays = ". Same save, spot and hour, frames lined up; no counter.",
    godraysdoor = ". Same save, spot and hour, frames lined up; no counter.",
    godraysspill = ". Same save, spot and hour, frames lined up; no counter.",
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
    godRaysDoorLeaf = "godraysdoor", godRaysDoorSwingMs = "godraysdoor", godRaysCharShadows = "godraysdoor", godRaysDoorDustPct = "godraysdoor",
    godRaysSpill = "godraysspill", godRaysSpillPct = "godraysspill",
    pixelLight = "torch", voxelLight = "voxel", voxelLightTorchShadows = "voxel", voxelLightSkyMethod = "voxel", voxelLightBounce = "voxel", pplAnalytic = "torch", pplNormals = "torch", pplWrapPct = "torch", pplShadows = "torch", pplTorchFeetGlow = "torch", torchSource = "torch", torchSourceAim = "torch", torchSourceSelfShadow = "torch", pplSmooth = "torch", pplPointLights = "torch", pplWetSpecular = "storm", pplSpecPct = "storm",
    lightingStrongDelta = "torch", lightingStrongBudget = "horde", lightingStrongFrameMs = "horde", lightingFlush = "torch", lightingBudget = "torch",
    audioLimiter = "horde", audioLimiterCeilingDb = "horde", audioLimiterStereoFold = "horde", soundTickHz = "horde", emitterIdleSkip = "horde", worldSoundCleanupFast = "horde", hearingHoist = "horde",
    lightSwitchCheckFrames = "horde", soundZoneCache = "horde", worldSoundFast = "horde", gridStackInterval = "horde",
    playerLosFast = "player", zombieSpotFast = "player", charDrawPrep = "horde", hordeBatch = "horde", hordeBatchUpload = "horde", hordeBatchVao = "horde", atlasBatch = "horde", hordeBatchOrder = "horde", hordeBatchUnits = "horde", renderPrewarm = "horde", renderPrewarmOutlines = "horde", hordePaletteMismatch = "horde", zombieAtlasFast = "horde", renderLastFast = "horde", charDrawThreads = "horde",
    actionSnapshotFilter = "zgt", emitterParamSkip = "zgt", separateFast = "zgt", ragdollGate = "zgt", bodyModelCache = "zgt", entityUpdateParallel = "zgt", entityUpdatePipeline = "zgt", entityUpdatePlayerFirst = "zgt", entityUpdateMoreStates = "zgt", postupdateParallel = "zgt", separateParallel = "zgt", sleepCheckMemo = "zgt",
    renderPrepParallel = "horde", pplPackParallel = "torch", pplTorchNearChunk = "torch", schedulerClassifyParallel = "zgt", schedulerFillParallel = "zgt", playerLosParallel = "player", zombieStatsFold = "zgt", losLightPrefetch = "player", entityUpdateSafeStates = "horde", ragdollCorpseGuard = "horde", ragdollQuitSweep = "horde", visPolyAsync = "player", aoContextParallel = "horde", translucentOrderCache = "drive",
    stateParamMemo = "zgt", actionGroupCache = "zgt", profilerThreadMemo = "zgt", zombieSimLodTiles = "zgt", zombieSimLodSteps = "zgt",
    zombieCheckSpread = "zgt", chunkGridWidth = "grid", chunkGridFollowView = "grid",
    zombieLodDynamic = "horde", zombieLodMin3d = "horde", zombieLodMinBlend = "horde", zombieLodUncappedFps = "horde", zombieLodHysteresis = "horde", zombieLodFadeMs = "horde", zombieLodFixed3d = "horde", zombieLodSpriteLight = "horde", zombieFirstSightSprite = "horde", zombieCompositeBudgetUs = "horde",
    animBonesParallel = "zombies", vehicleCull = "zombies", nearVehicleGrid = "zombies", separateMemo = "zombies", frameThreads = "zombies", actionEvalParallel = "zombies", ecsLookupFast = "zombies",
    actionConditionFast = "zombies", skinTransformsPrecompute = "zombies", skinPalettePrecompute = "zombies", shadowPrep = "zombies",
    boneIndexCache = "zombies", lightingReadParallel = "zombies", zombieCullSortFast = "zombies",
    animatorParallel = "zombies", headOnWorker = "zombies", postupdateNoopSkip = "zombies", postupdateEligibleOnWorker = "zombies", postupdateVehicleOnWorker = "zombies", postupdateFuse = "zombies", postupdateClean = "zombies", separateEdgeCache = "zombies", luaSilentSkip = "zombies", lazyPose = "zombies", animatorPipeline = "zombies", animBatchAsync = "zombies", guardedCallbacks = "zombies",
    randPerWorker = "zombies",
    modelLockPerInstance = "zombies", poolStatsBatched = "zombies",
    bakeBudget = "drive", rebakeBudget = "drive", rebakeMaxFrames = "drive", treeBakeMaxChunksPerSec = "drive", driveTreeCutaway = "drive", edgeTestFast = "horde", treeRebakeLazy = "drive", treeRebakeLingerMs = "drive", treeCutawayReach = "drive", treeCutawayReachPx = "drive",
    bakeScheduler = "drive", bakeFrameBudget = "drive", bakeBudgetAdaptive = "drive", occlusionGrantedOnly = "drive", bakeMipLevels = "drive", bakeNoSync = "drive",
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
    workerQosMac = { cpu = -2, cores = 1 },
    gpuPstate = { gpu = -1 },
    jitSteady = { cores = -2 },
    gcHeap = { cpu = -1, ram = 1 },
    gcHeapFixed = { ram = 1 },
    luaGcNoop = { cpu = -1 },
    gcPreTouch = { ram = 1 },
    gcMode = { cpu = -1 },
    gcPauseMs = {},
    gcG1Tuned = { cpu = -1 },
    gcSlack = { cpu = -1 },
    gcSlackQueue = {},
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
    bakeNoSync = { render = -2 },
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
    schedulerFillParallel = { cpu = -1, cores = 1 },
    playerLosParallel = { cpu = -1, cores = 1 },
    zombieStatsFold = { cpu = -1 },
    visPolyAsync = { cpu = -1, cores = 1 },
    aoContextParallel = { cpu = -1, cores = 1 },
    translucentOrderCache = { cpu = -1 },
    entityUpdatePipeline = { cores = 1 },
    entityUpdatePlayerFirst = { cpu = 1 },
    entityUpdateMoreStates = { cpu = -1 },
    postupdateParallel = { cpu = -1, cores = 1 },
    emitterDefer = {},
    physicsDefer = {},
    animalLosFast = { cpu = -1 },
    animBonesParallel = { cpu = -1, cores = 1 },
    animBonesThreads = { cores = 1 },
    frameThreads = { cores = 1 },
    actionEvalParallel = { cpu = -2, cores = 1 },
    animatorParallel = { cpu = -2, cores = 1 },
    headOnWorker = { cpu = -1, cores = 1 },
    postupdateNoopSkip = { cpu = -1 }, postupdateEligibleOnWorker = { cpu = -1, cores = 1 }, postupdateVehicleOnWorker = { cpu = -1, cores = 1 },
    postupdateFuse = { cpu = -2, cores = 1 }, postupdateClean = { cpu = -2, cores = 1 }, separateEdgeCache = { cpu = -1 }, luaSilentSkip = { cpu = -1 },
    randPerWorker = { cores = -1 },
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
    nearVehicleGrid = { cpu = -1 },
    separateMemo = { cpu = -1 },
    playerLosFast = { cpu = -2 },
    zombieSpotFast = { cpu = -1 },
    charDrawPrep = { cpu = -2, cores = 1 },
    hordeBatch = { render = -2, gpu = -3 },
    hordeBatchUpload = {},
    hordeBatchVao = { render = -1 },
    atlasBatch = { render = -1, gpu = -1 },
    hordeBatchOrder = { render = -1, gpu = -2 },
    hordeBatchUnits = { render = -1, gpu = -1 },
    renderPrewarm = { render = -1, load = 1 },
    renderPrewarmOutlines = { render = -1, vram = 1 },
    hordePaletteMismatch = { render = -1 },
    zombieAtlasFast = { cpu = -1 },
    renderLastFast = { cpu = -1 },
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
    ragdollGate = { cpu = -1 },
    bodyModelCache = { cpu = -1 },
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
    zombieLodHysteresis = {},
    zombieLodFixed3d = { cpu = -2, gpu = -2 },
    zombieLodSpriteLight = {},
    zombieLodFadeMs = { gpu = 1 },
    zombieFirstSightSprite = { render = -1 },
    zombieCompositeBudgetUs = {},
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
    mirrorsWallMirrors = { gpu = 1 },
    mirrorsWindowPct = {},
    mirrorsProps = { gpu = 1, render = 1 },
    mirrorsPropGlassPct = {},
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
    ripples = { gpu = 1 },
    ripplesStrengthPct = {},
    ripplesVehicles = {},
    ripplesPerSec = {},
    ripplesMode = {},
    ripplesRain = { gpu = 1 },
    ripplesRainPct = {},
    ripplesWake = { gpu = 1 },
    bloodWet = { gpu = 1, cpu = 1 },
    bloodWetMinutes = { gpu = 1 },
    bloodReflectPct = {},
    bloodSheenPct = {},
    bloodGlintPct = {},
    godRays = { gpu = 1, vram = 1 },
    godRaysLocal = { gpu = 1 },
    godRaysCharShadows = { gpu = 1 },
    godRaysSpill = { gpu = 1 },
    godRaysBouncePct = { gpu = 1 },
    foliageSway = { gpu = 1, vram = 1 },
    relief = { gpu = 1, vram = 2 },
    reliefTorchShadowSteps = { gpu = 2 },
    darknessFloorPct = {},
    memoryTint = {},
    colorGrading = { gpu = -1 },
    spriteFilter = { gpu = 1 },
    spriteFilterMin = { gpu = 1 },
    pixelLight = { cpu = -1, gpu = 1, vram = 1 },
    voxelLight = { gpu = 0, vram = 1 },
    voxelLightSkyMethod = { gpu = 1 },
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
    frameQueue = { render = 1 },
    frameQueueGpu = { render = 1 },
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
    uiVerify = { cpu = 1 },
    uiAnimate = { cpu = 1 },
    uiSlack = { cpu = -1 },
    uiMoveReplay = { cpu = -1 },
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
    slackWork = { cpu = -1, chunks = 1 },
    handoffRecalcFast = { cpu = -1, chunks = -1 },
    propIdCache = { cpu = -1 },
    chunkHandoffCost = { cpu = -1, chunks = -1 },
    chunkHandoffSlackMaxWait = { cpu = -1, chunks = -1 },
    chunkHandoffForceAnyFrame = { chunks = -1 },
    chunkHandoffFrameBudgetUs = { cpu = -1, chunks = 1 },
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
    shaderWarmup = { render = -1 },
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
local C_STOCK_TEXT = { r = 1, g = 0.62, b = 0.40 } -- the amber as text on the dark panels (the bar's own amber read poorly)
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
    -- every controller-focusable element of a built row (the curve sliders are several lines) -> the row (addRow)
    o.byControl = {}
    return o
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
        -- only the resources the setting moves (2026-10-18, the maintainer): a mid / stock row is left out, the others keep
        -- their order
        if v ~= 0 then
        self:drawTextRight(axis.label, x + labW, y + textY, C_GREY.r, C_GREY.g, C_GREY.b, 1, self.fontS)
        self:drawRect(barX, y + 3, barW, rowH - 6, 1, 0.11, 0.11, 0.13)
        local word, wcol = LEVELS[0], C_GREY
        if v ~= 0 then
            local len = math.floor(half * math.abs(v) / 3)
            local col = v < 0 and C_OPT or (axis.moreIsWork and C_BLUE or C_STOCK)
            local bx = v < 0 and (mid - len) or (mid + 1)
            self:drawRect(bx, y + 3, len, rowH - 6, 1, col.r, col.g, col.b)
            word = LEVELS[v]
            wcol = col == C_STOCK and C_STOCK_TEXT or col
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
    end
    -- the x axis: a rule, a tick and a word per level, the title under them. On a narrow chart (the main menu's detail pane
    -- at 1280 x 720) the words would run into each other: the ends and mid first, then the others where they fit
    self:drawRect(barX, y, barW, 1, 1, 0.55, 0.55, 0.60)
    local placed = {}
    for _, t in ipairs({ -3, 3, 0, -2, 1, 2 }) do
        local tx = mid + math.floor(half * t / 3)
        self:drawRect(tx, y, 1, 5, 1, 0.55, 0.55, 0.60)
        local label = LEVELS[t]
        local lw = getTextManager():MeasureStringX(self.fontS, label)
        local lx = tx - math.floor(lw / 2)
        if lx < barX then lx = barX elseif lx + lw > barX + barW then lx = barX + barW - lw end
        local free = true
        for _, r in ipairs(placed) do
            if lx < r[2] + 6 and lx + lw + 6 > r[1] then free = false end
        end
        if free then
            table.insert(placed, { lx, lx + lw })
            local col = t < 0 and C_OPT or (t > 0 and C_STOCK_TEXT or C_GREY)
            self:text(label, lx, y + 6, col, self.fontS)
        end
    end
    y = y + 6 + self.hS + 2
    self:drawTextCentre(AXIS_TITLE, mid, y, C_GREY.r, C_GREY.g, C_GREY.b, 1, self.fontS)
    return y + self.hS
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

-- A word's index terms, kept for the session: the descriptions repeat most of their words, and splitting each one again
-- with Kahlua's patterns was most of the index's 236 ms (2026-10-10).
local WORD_TERMS = {}
local function wordTerms(word)
    local m = WORD_TERMS[word]
    if not m then
        local ps = parts(word)
        local whole = string.lower((string.gsub((string.gsub(word, "^pzopt%.", "")), "%.", "")))
        m = { parts = ps, whole = (#ps > 1 and whole ~= "") and whole or nil }
        WORD_TERMS[word] = m
    end
    return m
end

-- Adds the terms of a text to tf with a weight: every part, and the whole word (dots dropped) when it had several.
local function addTerms(tf, text, weight)
    local n = 0
    for word in string.gmatch(tostring(text or ""), "[%w%.]+") do
        local m = wordTerms(word)
        for _, t in ipairs(m.parts) do
            tf[t] = (tf[t] or 0) + weight
            n = n + weight
        end
        if m.whole then
            tf[m.whole] = (tf[m.whole] or 0) + weight
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

-- The index over the tab's rows: one document per setting, added one per indexStep by the tab's idle builder in the
-- frames after the tab opened; a search that comes sooner finishes it first (indexReady).
local function indexNew(rows)
    return { rows = rows, next = 1, total = 0, docs = {}, df = {}, vocab = {}, expand = {}, avgdl = 1, idf = {} }
end

-- Adds the next row; true once every row is in (and the averages are set).
local function indexStep(index)
    local row = index.rows[index.next]
    if row then
        index.next = index.next + 1
        local entry, tf, len = row.entry, {}, 0
        len = len + addTerms(tf, entry.label, W_LABEL)
        len = len + addTerms(tf, entry.key, W_KEY)
        len = len + addTerms(tf, entry.tip, W_TIP)
        if entry.note then
            for _, v in pairs(entry.note) do len = len + addTerms(tf, v, W_TIP) end
        end
        len = len + addTerms(tf, row.section.title .. " " .. row.cat.title .. " " .. row.sub.title, W_SECTION)
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
        index.total = index.total + len
        for t in pairs(tf) do
            if not index.df[t] then table.insert(index.vocab, t) end
            index.df[t] = (index.df[t] or 0) + 1
        end
        return false
    end
    if not index.ready then
        local n = #index.docs
        if n > 0 then index.avgdl = index.total / n end
        for t, df in pairs(index.df) do
            index.idf[t] = math.log(1 + (n - df + 0.5) / (df + 0.5))
        end
        index.ready = true
    end
    return true
end

local function indexReady(index)
    while not indexStep(index) do end
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

-- Profiles: one button sets a named group of controls (the rest go back to the build's default),
-- master on; Apply / Accept saves them like the other buttons. Values are the option strings.
-- The low-end set (2026-09-21, docs/archive/2026-09-24/results.md): no chunk worker pool (its threads took the game thread's
-- core on four cores), trees baked into chunk textures only while walking, and on the Display page lighting
-- updates 10/s and the UI redrawn 30/s. The stock Display-page combos go by GameOption name -> combo index
-- (MainOptions.lua lists): lightingFPS {5, 10, 15, 20, 25, 30, 45, 60}, UIRenderFPS {120, 60, 30, 25, 20, 15, 10}.
-- The locked-60 pass (2026-10-07, same laptop, docs/findings-low-end-mode-2026-10-07.md) added a 9-chunk render distance:
-- the native lighting thread, the chunk bakes and every chunk hand-off scale with the grid's area, and they were what
-- broke 60 on four cores (a 9-chunk grid is what vanilla itself picks for a ~960x540 window and still covers a 1080p
-- screen at the widest zoom).
local LOW_END_VALUES = { workers = "1", loadWorkers = "2", treeBakeMaxChunksPerSec = "24", chunkGridWidth = "9" }
-- Plus texture compression (2026-09-23, same laptop: with uncompressed textures the 4 GB card was full, 4034 MiB, the
-- driver spilled into system RAM and the machine swapped 34k pages in a 40 s walk; compressed, 2372 MiB and 1.2k swap-ins,
-- 51 -> 54 fps, frames over 50 ms 34 -> 22 a minute, worst frame 292 -> 120 ms). Tick boxes go by name -> true / false.
-- And vsync with the frame limit at 60 (2026-10-07): a lock is one new frame every refresh of a 60 Hz panel; combos whose
-- entries move (the frame limit has an "Uncapped" entry only on some builds, and pzopt adds caps) go by label in stockLabels.
local LOW_END_STOCK = { lightingFPS = 2, UIRenderFPS = 3, texcompress = true, vsync = true }
local LOW_END_LABELS = { framerate = "60" }
-- The Visuals the 4-core laptop keeps at a locked 60 (2026-10-07, one walk and one 60 km/h drive per setting on top of the
-- low-end set, then the set together): a profile with an enhancements table sets every Visuals control (the unlisted ones
-- back to the build's default, off) and turns that page's master switch on.
-- Each was within the run-to-run noise alone, and the five together stay there (repeated refreshes: town walk 2.1 % vs
-- 1.3 % without, night 1.8 vs 1.5-1.8, storm 4.1 vs 3.9, 60 km/h drive 6.7 / 8.3 vs 6.3 / 6.8). Left out: water and puddle
-- reflections (alone free, but with the others the drive's render thread went 27 -> 34 % and the repeats 6.5 -> 8.5 %),
-- ambient occlusion (the drive's chunk bakes took the GPU 45 -> 79 %, repeats 14.4 %), sun shadows, per-pixel light and
-- zombie outlines (walking 4-5.6 %), foliage sway, relief and wet blood (~+2 points on the drive each).
local LOW_END_LOOK = { colorGrading = "true", godRays = "true", memoryTint = "true", mirrors = "true", spriteFilter = "sharp" }
local function withValues(base, extra)
    local t = {}
    for k, v in pairs(base) do t[k] = v end
    for k, v in pairs(extra) do t[k] = v end
    return t
end
local PROFILES = {
    {
        button = "Low-end hardware (4 cores or less)",
        tip = "A locked 60 fps on a 4-core CPU with an old GPU (Core i5-6300HQ / GTX 960M, 1920x1080, 2026-10-07): turns the "
           .. "master switch on, a render distance of 9 chunks (what the game itself picks for a small window: the native "
           .. "lighting thread, the chunk bakes and every arriving chunk's setup grow with it, and they were what broke 60 on "
           .. "four cores), no chunk worker pool (its threads took the game thread's core), trees baked only while walking, and "
           .. "on the Display page vsync on with the frame limit at 60, lighting updates 10/s, the UI redrawn 30 times a second "
           .. "and texture compression (keeps a 4 GB card from spilling into system memory). Everything else goes back to the "
           .. "build's default. Walking through town 1.3 % of the screen's refreshes repeat a frame (stock 29.9 %), a 60 km/h "
           .. "drive 6.3-6.8 % (stock 36.8 %). Zombies, cars and sounds are simulated 36 tiles around you instead of 76. "
           .. "See docs/findings-low-end-mode-2026-10-07.md.",
        values = LOW_END_VALUES,
        stock = LOW_END_STOCK,
        stockLabels = LOW_END_LABELS,
    },
    {
        button = "Low-end hardware + best look at 60",
        tip = "The Low-end hardware set above plus the Visuals that cost nothing on that laptop, measured one by one walking "
           .. "and driving at the locked 60, then together: sharp sprite filtering, colour grading, god rays, remembered places "
           .. "tinted, and mirrors and windows that reflect (town walk: 2.1 % of the screen's refreshes repeat a frame, 1.3 % "
           .. "without them). Every other Visuals setting goes back to off: ambient occlusion doubled the missed frames while "
           .. "driving (it is baked into every arriving chunk), water reflections cost the render thread while driving, sun "
           .. "shadows, per-pixel light and zombie outlines cost the lock while walking. Mirrors apply on the next launch. "
           .. "See docs/findings-low-end-mode-2026-10-07.md.",
        values = LOW_END_VALUES,
        enhancements = LOW_END_LOOK,
        stock = LOW_END_STOCK,
        stockLabels = LOW_END_LABELS,
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
        stockLabels = LOW_END_LABELS,
    },
}

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

-- ---------------------------------------------------------------------------------------------------
-- PzoptSettings (2026-10-18): what the main menu's PZ OPTIMIZATION screen reaches here. No UI: a value goes through
-- PerformanceSettings and is saved at once (pzopt.UserOptions; a live key is in force at once), so the presets, reset and
-- import change the settings themselves, not controls waiting for an Apply.
PzoptSettings = {}
local API = PzoptSettings
API.MASTERS = { MASTER, ENHANCEMENTS_MASTER, PROFILER_MASTER } -- by page: 1 Optimizations, 2 Enhancements, 3 Profiler
API.MASTER_CLIPS = { enabled = "drive", enhancementsEnabled = "upscale", profilerEnabled = "overlay" }
API.GROUP_NAMES = { "Optimizations", ENHANCEMENTS_TAB, PROFILER_TAB }
API.CATS = CATS
API.AXES = AXES
API.CLIP_W, API.CLIP_H = CLIP_W, CLIP_H
API.RESTART_NOTE, API.LIVE_NOTE = RESTART_NOTE, LIVE_NOTE
API.EXPORT_TITLE, API.EXPORT_TIP, API.IMPORT_TITLE = EXPORT_TITLE, EXPORT_TIP, IMPORT_TITLE
API.UNINSTALL_TITLE, API.UNINSTALL_TIP, API.UNINSTALL_CONFIRM = UNINSTALL_TITLE, UNINSTALL_TIP, UNINSTALL_CONFIRM
API.DEPS_TIP = DEPS_TIP
API.classes = optionClasses
API.date = optionDate
API.clipsOn = clipsOn
API.setClipsOn = setClipsOn
function API.clipsGen() return CLIPS.gen end
function API.effects(key) return EFFECTS[key] or {} end
-- how many of the chart's rows a setting moves (the chart draws only those; none = no chart)
function API.effectRows(key)
    local fx, n = EFFECTS[key] or {}, 0
    for _, axis in ipairs(AXES) do
        if (fx[axis.id] or 0) ~= 0 then n = n + 1 end
    end
    return n
end
function API.compatReason(key) return compatReason(key) end

-- the clip a setting shows: its key's own, else its section's, else the highway drive
function API.clipOf(key, section)
    return API.MASTER_CLIPS[key] or KEY_CLIP[key] or (section and section.clip) or "drive"
end

-- the two GIFs of a clip, their captions and the line under them
function API.clipInfo(clip)
    local sides = clipSides(clip)
    local same = sides == CLIP_SIDES.overlay and ". Same save, route and machine, a crop of the top-left corner at the Large overlay font."
        or CLIP_NOTES[clip] or ". Same save, route and machine; the number is that run's live frame rate."
    return { stock = clipPath(clip, "stock"), opt = clipPath(clip, "opt"), sides = sides, title = (CLIP_TITLES[clip] or clip) .. same }
end

-- Simple (1) / Advanced (2) / Everything (3): the layout's `simple` list, its expert keys and patterns, the rest Advanced
local TIER
function API.tier(key)
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

-- the colour row (occludedOutlineColour) and the zoom curve (zoomEase)
API.colourHex, API.colourRGB = colourHex, colourRGB
API.bezier = { axes = BEZIER_AXES, parse = parseBezier, same = sameBezier, format = formatBezier }

-- Search: rows are { entry, section = { title }, cat = { title }, sub = { title } }; the index fills one row per step (the
-- screen's idle frames), search() finishes it first
function API.searchIndex(rows) return indexNew(rows) end
function API.searchStep(index) return indexStep(index) end
function API.search(index, query) return search(indexReady(index), query) end

-- ---- writing settings

local function sectionsOf(page)
    return ({ SECTIONS, ENHANCEMENT_SECTIONS, PROFILER_SECTIONS })[page] or {}
end

-- every key a page lists, its sections in order
function API.pageKeys(page)
    local t = {}
    for _, sec in ipairs(sectionsOf(page)) do
        for _, e in ipairs(sec.entries or {}) do
            if e.key then table.insert(t, e.key) end
        end
    end
    return t
end

-- the category switches of a page (the layout's `master` fields this build knows)
function API.catMasters(page)
    local t = {}
    for _, g in ipairs((PzoptSettingsLayout or {}).groups or {}) do
        if g.page == page then
            for _, c in ipairs(g.cats or {}) do
                if c.master and perf():isPzoptOptionKnown(c.master) then table.insert(t, c.master) end
            end
        end
    end
    return t
end

-- Saves one value ("" / nil = the build's default; a choice equal to the default is kept as a choice while mod
-- compatibility decides the key, else the mod's value would come back). Pinned keys are left. True when it changed.
function API.set(key, value)
    local p = perf()
    if not p:isPzoptOptionKnown(key) or p:getPzoptOptionPinnedBy(key) ~= "" then return false end
    value = value or ""
    if value ~= "" and value == p:getPzoptOptionDefault(key) and not compatDecides(key) then value = "" end
    local before = p:getPzoptOptionSaved(key)
    if before == value then return false end
    p:setPzoptOption(key, value)
    return true
end

-- Back to the build's defaults (mod compatibility's value where it decides); returns how many changed.
function API.resetKeys(keys)
    local n = 0
    for _, k in ipairs(keys) do
        if API.set(k, "") then n = n + 1 end
    end
    return n
end

-- The stock Display options a preset sets (PROFILES' `stock` / `stockLabels`, the combo indices of MainOptions.lua),
-- applied directly and saved to options.ini.
local STOCK_SET = {
    lightingFPS = function(i) local t = { 5, 10, 15, 20, 25, 30, 45, 60 } if t[i] then getPerformance():setLightingFPS(t[i]) return "lighting " .. t[i] .. "/s" end end,
    UIRenderFPS = function(i) local t = { 120, 60, 30, 25, 20, 15, 10 } if t[i] then getCore():setOptionUIRenderFPS(t[i]) return "UI " .. t[i] .. " fps" end end,
    texcompress = function(on)
        if not getCore():allowOptionTextureCompression() then return nil end
        getCore():setOptionTextureCompression(on)
        return "texture compression " .. (on and "on" or "off") .. " (next launch)"
    end,
    vsync = function(on) getCore():setOptionVSync(on) return "vsync " .. (on and "on" or "off") end,
    framerate = function(label) local fps = tonumber(label) if fps then getPerformance():setGameFramerate(fps) return "frame limit " .. fps end end,
}

local function applyStock(profile)
    local done = {}
    for name, v in pairs(profile.stock or {}) do
        local f = STOCK_SET[name]
        local ok, what = pcall(function() return f and f(v) end)
        if ok and what then table.insert(done, what)
        elseif not ok then print("[pzopt] settings: preset could not set stock option " .. name .. ": " .. tostring(what)) end
    end
    for name, label in pairs(profile.stockLabels or {}) do
        local f = STOCK_SET[name]
        local ok, what = pcall(function() return f and f(label) end)
        if ok and what then table.insert(done, what) end
    end
    if #done > 0 then pcall(function() getCore():saveOptions() end) end
    table.sort(done)
    return done
end

-- The presets of the home page: Recommended, Stock game, then PROFILES. apply() saves and returns a summary line.
local RECOMMENDED_TIP = "Turns the Optimizations master switch and every category switch of it on and puts every Optimizations "
    .. "setting back to the build's default on this machine. Enhancements and Profiler are not changed. " .. RESTART_NOTE
local STOCK_TIP = "Turns the Optimizations master switch off: the game runs its original code everywhere, as if the overrides were not "
    .. "installed. The settings are kept for when you turn it on again; the Profiler and the performance overlay are not affected. "
    .. RESTART_NOTE
function API.presets()
    local list = {
        { title = "Recommended (every optimization on)", tip = RECOMMENDED_TIP, apply = function()
            local n = (API.set("enabled", "") and 1 or 0) + API.resetKeys(API.catMasters(1)) + API.resetKeys(API.pageKeys(1))
            return n .. (n == 1 and " setting" or " settings") .. " changed: every optimization at the build's default."
        end },
        { title = "Stock game (every optimization off)", tip = STOCK_TIP, apply = function()
            local changed = API.set("enabled", "false")
            return changed and "Optimizations switched off: the next launch is the stock game's code." or "Optimizations were already off."
        end },
    }
    for _, profile in ipairs(PROFILES) do
        local pr = profile
        table.insert(list, { title = pr.button, tip = pr.tip .. " " .. RESTART_NOTE, apply = function()
            local n = (API.set("enabled", "") and 1 or 0) + API.resetKeys(API.catMasters(1))
            for _, k in ipairs(API.pageKeys(1)) do
                if API.set(k, pr.values[k]) then n = n + 1 end
            end
            -- the Enhancements: a preset that picks an upscaler or brings a Visuals set turns that master on; the upscaling
            -- section follows the preset's values, a Visuals set decides every other Enhancements setting too
            if pr.values.upscaler or pr.enhancements then
                if API.set("enhancementsEnabled", "") then n = n + 1 end
                n = n + API.resetKeys(API.catMasters(2))
            end
            for _, sec in ipairs(ENHANCEMENT_SECTIONS) do
                for _, e in ipairs(sec.entries or {}) do
                    if sec.profiles then
                        if API.set(e.key, pr.values[e.key]) then n = n + 1 end
                    elseif pr.enhancements then
                        if API.set(e.key, pr.enhancements[e.key]) then n = n + 1 end
                    end
                end
            end
            local stock = applyStock(pr)
            return n .. (n == 1 and " setting" or " settings") .. " changed"
                .. (#stock > 0 and ("; Display options: " .. table.concat(stock, ", ")) or "") .. "."
        end })
    end
    return list
end

-- ---- export / import

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
API.parseSettings = parseSettings

function API.knownCount(text)
    local _, keys = parseSettings(text)
    local n = 0
    for _, k in ipairs(keys) do
        if perf():isPzoptOptionKnown(k) then n = n + 1 end
    end
    return n
end

-- Up to `max` names, then "and N more".
local function listSome(names, max)
    local shown = {}
    for i = 1, math.min(#names, max) do table.insert(shown, names[i]) end
    local s = table.concat(shown, ", ")
    if #names > max then s = s .. " and " .. (#names - max) .. " more" end
    return s
end

-- the keys of a group page in the export's order: its master, its category switches, its settings
local function exportKeys(page)
    local t = { API.MASTERS[page].key }
    for _, k in ipairs(API.catMasters(page)) do table.insert(t, k) end
    for _, k in ipairs(API.pageKeys(page)) do table.insert(t, k) end
    return t
end

-- what the export writes for a key: the player's saved choice, nil when it is the build's default
local function exportValue(key)
    local p = perf()
    local saved = p:getPzoptOptionSaved(key)
    if saved == "" or saved == p:getPzoptOptionDefault(key) then return nil end
    return saved
end

-- Export settings: the saved choices that differ from the build's defaults, a heading per group, to the clipboard and
-- Zomboid/pzopt/settings-export.ini. Returns the message to show.
function API.exportSettings()
    local lines, count, seen = {}, 0, {}
    for page = 1, 3 do
        local rows = {}
        for _, key in ipairs(exportKeys(page)) do
            if not seen[key] then
                seen[key] = true
                local v = exportValue(key)
                if v then table.insert(rows, key .. "=" .. v) end
            end
        end
        table.sort(rows)
        table.insert(lines, "# " .. API.GROUP_NAMES[page] .. (#rows == 0 and ": all at the defaults" or ""))
        for _, row in ipairs(rows) do table.insert(lines, row) end
        count = count + #rows
    end
    local p = perf()
    local text = p:getPzoptSettingsExportText(table.concat(lines, "\n") .. "\n")
    Clipboard.setClipboard(text)
    local path = p:pzoptSettingsExportWrite(text)
    local what = count == 0 and "Every setting is at the build's default: the text says so."
        or (count .. (count == 1 and " setting differs" or " settings differ") .. " from the build's defaults.")
    return "Settings copied to the clipboard. " .. what .. " "
        .. (path ~= "" and ("Also saved to " .. path .. ".") or "The file Zomboid/pzopt/settings-export.ini could not be written (see console.txt).")
end

-- settings text: a known key, or an export of nothing but defaults (its "# Optimizations" heading and no key lines)
local function isSettingsText(text)
    return API.knownCount(text) > 0 or string.find(text or "", "# Optimizations", 1, true) ~= nil
end

-- the text an import starts from: the clipboard when it holds settings, else the last export's file
function API.importSource()
    local text = Clipboard.getClipboard() or ""
    if not isSettingsText(text) then
        text = perf():getPzoptSettingsExportFile()
        if not isSettingsText(text) then text = "" end
    end
    return text
end

-- Import settings: the listed settings to their value, every other one back to the build's default, pinned ones left.
-- Saved at once. Returns the message to show.
function API.importSettings(text)
    if not isSettingsText(text) then
        return "No settings found in the text: nothing was changed. Paste the text Export settings copied (key=value lines, "
            .. "e.g. treesInChunkTexture=false).", false
    end
    local values, keys = parseSettings(text)
    local p = perf()
    local listed, set, reset, pinned, invalid, seen = {}, 0, 0, {}, {}, {}
    for page = 1, 3 do
        for _, key in ipairs(exportKeys(page)) do
            if not seen[key] then
                seen[key] = true
                listed[key] = true
                local v = values[key]
                if p:getPzoptOptionPinnedBy(key) ~= "" then
                    if v then table.insert(pinned, key) end
                else
                    local def = p:getPzoptOptionDefault(key)
                    if v and (def == "true" or def == "false") and v ~= "true" and v ~= "false" then
                        table.insert(invalid, key .. "=" .. v)
                        v = nil
                    end
                    local before = exportValue(key)
                    if API.set(key, v or "") or v then
                        if v then set = set + 1 elseif before then reset = reset + 1 end
                    end
                end
            end
        end
    end
    local ignored = {}
    for _, k in ipairs(keys) do
        if not listed[k] then table.insert(ignored, k) end
    end
    local msg = set .. (set == 1 and " setting" or " settings") .. " imported"
        .. (reset > 0 and (", " .. reset .. " more back to the build's default") or "") .. ". Saved; the Optimizations apply on the next launch."
    if #pinned > 0 then msg = msg .. " Pinned by pzopt.properties or -D here, left as they are: " .. listSome(pinned, 6) .. "." end
    if #invalid > 0 then msg = msg .. " Not a true / false value, set to the default: " .. listSome(invalid, 6) .. "." end
    if #ignored > 0 then msg = msg .. " Not settings of this version, ignored: " .. listSome(ignored, 6) .. "." end
    return msg, true
end

-- ---- uninstall, the DLSS files

-- why the uninstall cannot run now ("" = it can): in a world the quit would skip the save
function API.uninstallUnavailable()
    if MainScreen.instance and MainScreen.instance.inGame then
        return "go back to the main menu first (quitting from a world would skip the save)"
    end
    local why = ""
    pcall(function() why = perf():getPzoptUninstallUnavailable() end)
    return why
end

-- starts pzopt.Uninstall (the caller quits the game when it started); false + why when it did not
function API.uninstall()
    local ok, started = pcall(function() return perf():pzoptUninstall() end)
    if ok and started then return true, "" end
    local msg = ""
    pcall(function() msg = perf():getPzoptUninstallMessage() end)
    return false, "Nothing was removed: " .. tostring(msg)
end

-- the DLSS files' state: its button title, whether pressing it installs, the tip with the Java side's message
function API.deps()
    local ok, s = pcall(function() return perf():getPzoptUpscalerDepsState() end)
    if not ok then return { title = DEPS_TITLES.unsupported, enabled = false, tip = DEPS_TIP } end
    local title = DEPS_TITLES[s] or DEPS_TITLES.checking
    if s == "downloading" then title = "Downloading the DLSS files " .. tostring(perf():getPzoptUpscalerDepsProgress()) .. " %" end
    local msg = ""
    pcall(function() msg = perf():getPzoptUpscalerDepsMessage() end)
    return { state = s, title = title, enabled = s == "missing" or s == "error", tip = msg ~= "" and (DEPS_TIP .. " Now: " .. msg .. ".") or DEPS_TIP }
end
function API.depsCheck() pcall(function() perf():pzoptUpscalerDepsCheck() end) end
function API.depsInstall()
    local s = API.deps().state
    if s == "missing" or s == "error" then pcall(function() perf():pzoptUpscalerDepsInstall() end) end
end

-- A setting key's label and its section's title (nil when no settings page shows it; a master switch has no section),
-- for the benchmark's run comparison and the main menu's mod compatibility check (pzopt_mainscreen_compat.lua).
local labelByKey, sectionByKey
function PzoptOptionLabel(key)
    if not labelByKey then
        labelByKey, sectionByKey = {}, {}
        for _, m in ipairs({ MASTER, ENHANCEMENTS_MASTER, PROFILER_MASTER }) do labelByKey[m.key] = m.label end
        for _, list in ipairs({ SECTIONS, ENHANCEMENT_SECTIONS, PROFILER_SECTIONS }) do
            for _, sec in ipairs(list) do
                for _, e in ipairs(sec.entries or {}) do
                    if e.key and type(e.label) == "string" and not labelByKey[e.key] then
                        labelByKey[e.key], sectionByKey[e.key] = e.label, sec.title
                    end
                end
            end
        end
    end
    return labelByKey[key], sectionByKey[key]
end

-- The tab's sections, page by page (1 Performance, 2 Visuals, 3 Tools), for the main menu's PZ Optimization screen
-- (pzopt_mainscreen_menu.lua: its settings pages list each subcategory's keys with their labels and descriptions).
function PzoptOptionSections()
    return { SECTIONS, ENHANCEMENT_SECTIONS, PROFILER_SECTIONS }
end

local function install()
    if not MainOptions or MainOptions.pzoptOptionsHooks then return end
    local ok, has = pcall(function() return getPerformance():hasPzoptOptions() end)
    if not ok or not has then
        print("[pzopt] options screen: PerformanceSettings override not loaded or overrides disabled, lazy build not installed")
        return
    end
    MainOptions.pzoptOptionsHooks = true
    -- The game's Options screen has no PZ Optimization tab since 2026-10-18 (the settings live in the main menu's PZ
    -- OPTIMIZATION screen, pzopt_mainscreen_menu.lua); what stays here is its lazy build.
    -- the whole options screen's build time, for the load trace (the in-game menu builds it while the world is entered)
    local stockCreate = MainOptions.create
    -- The full build: stock create, timed for the load trace.
    local function fullCreate(self, ...)
        self.pzoptCreatePending = false
        self.pzoptCreated = true
        local t0 = getTimestampMs()
        local r = stockCreate(self, ...)
        PzoptLogInfo("[pzopt] options screen: MainOptions:create took " .. (getTimestampMs() - t0) .. " ms")
        return r
    end
    -- lazyOptionsScreen: the main menu and the in-game menu build the whole options screen while they are built
    -- (boot, every world entry, exit to menu), ~130 ms of vanilla panels on the flip. The hidden
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
    -- Shown: the deferred build first, then the values it shows.
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
    end
end

install()
Events.OnGameBoot.Add(install)
