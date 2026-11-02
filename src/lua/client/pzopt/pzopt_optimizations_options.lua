-- pzopt: every pzopt setting the player can change, as data, and what the main menu's PZ OPTIMIZATION screen does with
-- them (pzopt_mainscreen_menu.lua is the screen; since 2026-10-18 it is their only home: the game's Options screen has no
-- PZ Optimization tab any more, only this file's lazy build of that screen, install() at the end).
--  SECTIONS (Performance, next launch), ENHANCEMENT_SECTIONS (Visuals, live except NEXT_LAUNCH_ONLY) and PROFILER_SECTIONS
--  (Tools, live): one entry per pzopt.Config key with its label, description (tip: what it does and what you see), the
--  reason for each resource its EFFECTS row moves (why: axis id -> one or two sentences, under the description in the
--  detail; tests/lua/effect-why.lua checks they match), choices / notes, its clip. The values
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
  tip = "Off = the game runs stock: every override takes its original code path and the settings below are ignored. On = the settings below apply. The Tools are not affected: the performance overlay works either way.",
  why = {
      cpu = "The world is baked into chunk textures instead of drawn tile by tile, and the lighting, cutaway, rain and zombie work is trimmed or cached: on the desktop's spinning route 336 to 472 fps uncapped.",
      render = "Rain, puddles and sprites are packed once and sent in large batches, so the render thread issues far fewer draw calls (in a stock thunderstorm the rain alone is 73 % of the render thread).",
      cores = "The zombies' bone math, action checks, lighting reads, character draw preparation and the boot's loading run on worker threads, beside the game thread.",
      gpu = "Fog is drawn into a quarter-size buffer in one pass, puddles skip hidden pixels and chunk textures re-bake less often.",
      ram = "Chunk textures' puddle vertices, the lighting and sprite caches and the worker threads' scratch memory stay in the Java heap.",
      disk = "Boot caches (precompiled Lua, animation clips, texture pack indexes) are written under Zomboid/pzopt the first time.",
      load = "The boot loads sound, animations and scripts on parallel threads and reads the caches: launch to menu 7.4 to 5.0 s, Continue to the world 6.5 to 4.0 s on the desktop.",
      chunks = "New chunks are read, decoded and baked on workers and under a per-frame budget, so they appear sooner without a hitch while you move.",
  } }
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
        title = "Chunk textures: what bakes",
        entries = {
            { key = "treesInChunkTexture", label = "Trees: bake into chunk textures",
              tip = "Static trees are drawn once into the chunk textures instead of every frame; only trees that fade around you, sway in the wind or carry an effect stay per frame. Off = stock (every tree every frame).",
              why = {
                  cpu = "Every tree went through the per-frame translucent pass, 35 % of the game thread at max zoom; baked, it is no longer walked and prepared each frame (frame mean 6.2 to 5.2 ms on the desktop's max-zoom route).",
                  render = "Far fewer sprites are handed to the GL each frame: render thread 35 to 32 % busy on the same route (the persistent sprite buffer had already taken most of its load).",
                  gpu = "One chunk composite replaces thousands of overlapping tree sprites drawn every frame: GPU busy 84 to 61 % on the desktop's max-zoom route.",
                  chunks = "Each new chunk texture also bakes its trees, so an arrival costs more; on a 4-core laptop at 120 km/h that was more than drawing them (see Trees: bake only below this chunk rate).",
              } },
            { key = "windSpriteSway", label = "Wind sprite effects: sway the baked plants",
              tip = "With the game's own Wind sprite effects display option on, grass, bushes and trees stay in the chunk textures and bend in the wind through Foliage sway, instead of the option drawing every plant one by one every frame. In a forest at max zoom on a laptop the wind then costs almost nothing (133 fps with the stock option, 369 with this, 372 without wind). Nothing changes while the game option is off; on macOS the option keeps its stock drawing. Off = the stock option's per-frame drawing.",
              why = {
                  cpu = "The stock option takes every plant out of the bake and the game thread prepares each one per frame, sheared, with a lighting query per tree; kept baked, that walk is gone.",
                  render = "The option's path is one draw call and its uniforms per plant sprite every frame; with the plants baked the render thread issues the chunk composite instead.",
                  gpu = "On the desktop the stock option costs about 370 us of GPU a frame at zoom 1; the sway shader that bends the baked plants costs about 20 us.",
              } },
            { key = "treeBakeMaxChunksPerSec", label = "Trees: bake only below this chunk rate (chunks/s)",
              choices = { "0", "12", "24", "48" }, note = { ["0"] = "always bake" },
              tip = "While chunks stream in faster than this (walking loads about 9 a second, driving at 60 km/h about 32, at 120 km/h about 72), new chunk textures are baked without their trees and the trees are drawn every frame instead: a texture that lives only a second or two while driving is not worth baking trees into. Textures already baked keep their trees until they re-bake anyway. 0 (default) = always bake; the Low-end hardware preset uses 24.",
              why = {
                  cpu = "Baking a short-lived texture's trees, their copies in the neighbours and the 5x5-chunk scan cost the game thread more than drawing them: a 4-core laptop at 120 km/h ran 42.7 fps with trees per frame, 19.5 baked.",
                  gpu = "Above the rate the trees are drawn every frame, as in the stock game, so the GPU draws them again instead of reading them from the chunk texture.",
                  chunks = "Above the rate a new chunk texture bakes without trees and without the tree pass, so each arrival costs less while you drive fast.",
              } },
            { key = "treeBakeDirect", label = "Trees: bake through the plain sprite path",
              tip = "Baked trees go through the plain sprite path; the game's batched path dropped the largest (jumbo) trees near buildings. Used only while Trees: crowns across textures, depth by height is off." },
            { key = "treeBakePass", label = "Trees: crowns across textures, depth by height",
              tip = "Baked trees are drawn by their own pass: into every chunk texture the crown reaches (a jumbo tree is up to 7 tiles wide) and with a depth that rises with the crown like walls do. Off = trees are clipped at their chunk texture's border and cut by upper-floor walls behind them (issue #5).",
              why = {
                  cpu = "Each bake scans the 5x5 chunks around it for crowns that reach in and draws copies of them, and a changed tree re-bakes the neighbours holding its copy (bakes +9 % on the spinning route).",
                  gpu = "The copies and the extra neighbour re-bakes are GPU work in the bakes (about 0.2-0.3 ms per bake on the desktop); the frame rate stayed within noise (280 vs 281.5 fps).",
              } },
            { key = "treeAppend", label = "Trees: draw new ones into neighbour textures",
              tip = "A newly loaded chunk's trees that reach into an already baked neighbour texture are drawn on top of it instead of re-baking the whole texture. Same picture.",
              why = {
                  cpu = "On the 120 km/h desktop drive 60 % of all bakes were these neighbour re-bakes, each a full bake prepared on the game thread; now only the new trees' quads are listed.",
                  gpu = "Each neighbour re-bake was about 300 us of GPU, 2.9 a frame on the 120 km/h desktop drive; drawing only the new trees into the finished texture removes about half of them.",
                  chunks = "A chunk arriving next to baked ones no longer re-bakes their textures, so each arrival while driving costs less.",
              } },
            { key = "treeRebakeLazy", label = "Trees: fewer re-bakes in the cutaway while driving",
              tip = "Since Build 42.21 every tree around your car turns see-through while you drive. A tree now re-bakes its chunk only when it leaves the baked picture or comes back, not at every step of its fade; one coming back is drawn every frame (as the stock game draws all trees) until the next check re-bakes its chunk once for all such trees. Same picture. Off = up to four re-bakes of the chunk and its neighbours per tree passed.",
              why = {
                  cpu = "Fewer re-bakes means fewer bake preparations on the game thread: neighbour re-bakes 20k to 7k on the desktop's 120 km/h drive with Trees: only trees reaching the cutaway.",
                  gpu = "Each tree passed re-dirtied its chunk and about 7 neighbour textures up to four times; with the reach rule, bakes fell 31k to 21k on the desktop's drive (320 to 430 fps, GPU-bound).",
              } },
            { key = "treeRebakeLingerMs", label = "Trees: longest per-frame stay after the cutaway (ms)",
              choices = { "0", "250", "1000", "3000" }, note = { ["0"] = "next frame" },
              tip = "How long a tree back from the driving cutaway may stay drawn every frame before its chunk re-bakes it in. 0 (default) = at the next check, which gathers a chunk's returning trees into one re-bake; a longer stay measured slower." },
            { key = "edgeTestFast", label = "Zombies: fast wall / window / door test when they push apart",
              tip = "Build 42.21 rewrote the test whether a wall, window or door lies between two squares round new edge objects, through layers of small function objects the Java compiler cannot inline. Zombies pushing each other apart ask it for every neighbour square every frame; for those calls the same test is now written out directly. Same result (24 million calls checked in a horde, none different).",
              why = {
                  cpu = "The written-out test skips the predicate calls: 154 instead of 181 ns a call, under 1 % of the game thread in the Louisville horde.",
              } },
            { key = "driveTreeCutaway", label = "Trees: see-through round your car while driving (Build 42.21)",
              tip = "Build 42.21 counts driving as aiming for the tree cutaway: every tree whose base lies in the cutaway square round you turns see-through. On = as the game. Off (default) = Build 42.20's rule (only trees south-east of you, and while aiming); the 120 km/h drive is about 15-20 % faster at max zoom.",
              why = {
                  cpu = "Trees leaving and rejoining the bake add re-bakes prepared on the game thread: 17.4k to 21.6k bakes on the desktop's 120 km/h drive, with every tree fix on.",
                  gpu = "Every see-through tree is drawn each frame in three passes and its chunk re-bakes: the rule added 0.37 ms of per-frame trees and 0.18 ms of bakes a frame on the desktop's drive (514 to ~430 fps).",
              } },
            { key = "treeCutawayReach", label = "Trees: only trees reaching the cutaway leave the bake",
              tip = "The game makes a tree see-through when its base lies in the cutaway's square around you (while driving: every such tree), but only an ellipse in that square is cut away. A tree whose picture stays clear of the ellipse looks the same either way, so it stays in the chunk texture; its fade keeps running so it fades as before when it gets there. The 120 km/h drive is about a fifth faster.",
              why = {
                  cpu = "A kept tree skips the re-bakes of its chunk and its neighbours; only its fade is stepped each frame, a few sums per tree.",
                  gpu = "Most see-through trees never reach a marked pixel yet left the bake and were drawn every frame in three passes; kept baked they cost neither (with the lazy re-bakes 320 to 430 fps on the desktop's drive).",
              } },
            { key = "treeCutawayReachPx", label = "Trees: cutaway reach margin (pixels)",
              choices = { "0", "128", "256", "512" },
              tip = "How far ahead of the cutaway ellipse a tree already leaves the chunk texture, so its re-bake is done before the ellipse reaches it. 0, 128 and 256 measured the same." },
            { key = "bloodBake", label = "Blood: how bakes draw the floor splats",
              choices = { "gpu", "cpu", "off" }, note = { ["gpu"] = "default", ["off"] = "stock" },
              tip = "Blood on the floor is baked into the chunk textures. Stock walks up to 18,000 splats of the chunk and its eight neighbours on every bake and sets the shader, depth test and blend for each splat it draws. gpu (default): each chunk keeps its splats sorted once and a bake draws them in one instanced draw per chunk, with the same position, colour, age and light (pixel-identical to stock; on macOS it runs as cpu). cpu: the same list drawn as sprites, on any computer. off = stock.",
              why = {
                  cpu = "A bake no longer walks nine chunks' splat queues: blood per bake 122-131 to 35 us, its game-thread time 21 to 8.7 ms a second in a fight on the desktop (cpu mode: 66 us, 13.6 ms).",
                  render = "The per-splat shader, depth-test and blend changes are gone: the blood's render-thread time 74-79 to 17 us a frame in the same fight (cpu mode: 43 us).",
                  gpu = "One instanced draw per chunk replaces a state change and a quad per splat: bake GPU time 76 to 68 us a frame in that fight.",
                  chunks = "At 1,000 splats a chunk blood doubled the cost of every chunk bake, so arrivals hitched longer; the worst second's blood time fell 33 to 6.4 ms.",
              } },
            { key = "bloodAppend", label = "Blood: draw new splats into finished textures",
              tip = "A new blood splat is drawn into the chunk textures it lands on instead of re-baking the chunk's floor, and into the neighbours' textures within a tile of a chunk edge, where stock cut it off until they re-baked. Hidden by the Blood Decals option: nothing happens at all (stock re-baked for it anyway). Where grass, a body or an item lies under it, or ambient occlusion or sun shadows touch that texture, the floor re-bakes as stock.",
              why = {
                  cpu = "With 20 new splats a second, blood-only re-bakes fell 465 to 30 in 15 s on the desktop, and the blood's game-thread time 8.7 to 2.1 ms a second.",
                  gpu = "A splat quad drawn into a finished texture replaces a full floor re-bake: bake GPU time 68 to 53 us a frame in the same fight.",
              } },
            { key = "bloodSettleSec", label = "Blood: exact re-bake after new splats (s)",
              choices = { "0", "10", "30", "60" }, note = { ["30"] = "default", ["0"] = "never" },
              tip = "Some seconds after the first splat drawn into a finished texture, that floor re-bakes once in stock's exact order (blood under anything flat on the floor that the depth test cannot tell from it). 0 = never.",
              why = {
                  cpu = "One extra re-bake per floor that took appended blood, this long after its first splat; shorter shows the exact order sooner, 0 skips the re-bake.",
              } },
            { key = "bloodFadeFix", label = "Blood: drop splats past a chunk's 1,000",
              tip = "Stock keeps drawing the splats pushed out at 1,000 per chunk: they were meant to fade out, but in chunk textures their fade only runs down one step per bake, so they stay for as long as the chunk is loaded. On: they go at the chunk's next bake. Off = stock.",
              why = {
                  cpu = "The pushed-out list only grows (6,800 splats in a test fight) and every bake of the chunk and its neighbours walks it; dropping them keeps the walk short.",
                  gpu = "Those old splats are no longer drawn into each re-bake of the floor.",
              } },
            { key = "bloodRebakeCoalesceMs", label = "Blood: gather re-bakes for new splats on grass (ms)",
              choices = { "0", "100", "250", "500" }, note = { ["250"] = "default", ["0"] = "stock" },
              tip = "A new splat on grass, a body or an item still re-bakes its floor (see above); it waits this long first, so the splats one hit throws over the next few frames share a single re-bake. 0 = at once, as stock.",
              why = {
                  cpu = "The splats of one hit share a re-bake: 413 to 199 re-bakes in a 15 s fight on the desktop. Longer gathers more but shows the new blood later.",
                  gpu = "Every re-bake saved is a floor texture the GPU does not redraw (about 0.2 ms of GPU a bake on the desktop).",
              } },
            { key = "bloodAppendPlants", label = "Blood: new splats under grass without a re-bake",
              tip = "Where grass or bushes grow under a new splat, the splat is drawn into the finished texture under those plants, the way the game's bake draws them after the blood (same light, same wind sway), instead of re-baking the whole floor. Bodies, items and flattened grass still re-bake.",
              why = {
                  cpu = "By a road most splats land near grass: blood-only re-bakes fell 441 to 38 in a 15 s fight on the desktop, 146 of 383 splats appended under plants.",
                  gpu = "The plants are drawn once more into a small layer instead of the whole floor re-baking; that pass is below the profiler's resolution.",
              } },
            { key = "bloodAppendVegetation", label = "Blood: draw new splats over grass too",
              tip = "Also draw new splats into finished textures where grass, a body or an item lies under them (the blood then covers them; stock bakes them over the blood). Off (default) = those floors re-bake.",
              why = {
                  cpu = "Those splats no longer re-bake their floor at all; the remaining re-bakes of a fight go too.",
              } },
            { key = "fliesToggleFix", label = "Corpse flies: no re-bake when nothing moved",
              tip = "The corpse-flies update cleared and set the same square's flies every few frames while driving, and each toggle re-baked the chunk texture next to you at once; the clear is now skipped when the square stays the same. Same result.",
              why = {
                  cpu = "Each toggle forced an immediate re-bake next to the car, prepared on the game thread: 430+ of them on the 120 km/h Rosewood drive, now none.",
                  gpu = "Those forced re-bakes were about 0.2 ms of GPU each and never waited for the bake budget.",
              } },
            { key = "windowsInChunkTexture", label = "Bake windows into chunk textures",
              tip = "Windows and glass doors bake like walls instead of being drawn every frame. Off by default: baked glass hides zombies standing behind it (outside the window you look through), and it gave no measurable frame-time gain.",
              why = {
                  cpu = "The 30-130 windows and doors a frame leave the per-frame pass; on the desktop's max-zoom route that stayed within the run-to-run noise.",
              } },
            { key = "translucentTilesInChunkTexture", label = "Bake translucent tiles",
              tip = "Fences, railings, wall decorations and overlays (16,476 tile kinds carry the game's Translucent flag) bake into the chunk textures instead of being drawn every frame; the ones fading near you stay per frame.",
              why = {
                  cpu = "The per-frame pass drew 1,500-3,600 such tiles a frame at max zoom, now 50-120: frame mean 5.0 to 4.5 ms, p99 16.4 to 13.8 ms on the desktop's max-zoom route.",
                  render = "Thousands fewer sprites a frame to submit: render thread 31 to 19 % busy on the same route.",
                  gpu = "The tiles come with the chunk composite instead of one quad each: GPU busy 62 to 59 %.",
              } },
            { key = "glassTilesPerFrame", label = "Glass tiles stay per frame",
              tip = "Translucent tiles made of glass (display cases, glass-door fridges, escalator and mall balustrades, glass partitions) are drawn every frame as in the stock game, so you and zombies stay visible behind them. Baked, the glass hid whoever stood behind it." },
            { key = "floorDecalsPerFrame", label = "Floor decals stay per frame",
              tip = "Translucent tiles that lie on the ground (manhole covers, drains) are drawn every frame as in the stock game, over the rain puddles. Baked, a manhole cover in a puddle flickered in stripes while you walked, most visibly zoomed out." },
            { key = "translucentGridsPerFrame", label = "Multi-tile furniture stays per frame",
              tip = "Translucent furniture made of several tiles (picnic tables, large metal shelves, animal-hide rugs) is drawn every frame as in the stock game. Baked, the ground showed through it in wedges along the seams between its tiles." },
            { key = "translucentLightsPerFrame", label = "Light fixtures stay per frame",
              tip = "Translucent light fixtures that have a lit sprite (ceiling lights, lamps) are drawn every frame as in the stock game, so a lit ceiling light never shows through the floor or roof above it (a gas-station canopy's tubes did)." },
            { key = "curtainDepthNudgePct", label = "Curtain depth nudge (hundredths of a tile)",
              choices = { "0", "3", "5", "10" }, note = { ["0"] = "off" },
              tip = "Closed curtains draw this much nearer the camera than their tile geometry says, so a baked window never shows through them (north windows sit 0.017 tile in front of their curtain in the game's tile geometry)." },
        },
    },
    {
        title = "Chunk textures: bake budgets",
        entries = {
            { key = "bakeScheduler", label = "One prioritized bake budget per frame",
              tip = "Every chunk-level bake of a frame (new chunks, cutaways, strong light, redraws, lighting drift) shares one budget, granted by urgency and distance, instead of each kind having its own; a level waiting past its kind's longest wait goes first, and changes to objects next to you always bake at once. Driving through Rosewood at 120 km/h the worst frames drop from 37 to 16 ms. Replaces the per-kind budgets below: they apply only while this is off and during a zoom's bake flood.",
              why = {
                  cpu = "Arrivals, cutaways and lighting no longer stack in one frame: a heavy frame used to prepare 12-120 level bakes on the game thread.",
                  render = "Each bake is about 0.03 ms of render-thread submit; spread over frames, the render thread's heavy frames are no longer piled with dozens of them.",
                  gpu = "The bakes were the GPU's burst (3.5 ms mean in heavy frames, about 0.2 ms a bake); spread out, GPU busy fell 61 to 47 % on the Rosewood drive with the rest of the set.",
              } },
            { key = "bakeFrameBudget", label = "One budget: bakes per frame",
              choices = { "2", "4", "6", "8", "12" },
              tip = "The most chunk-level bakes a frame (the highest the adaptive budget goes); changes to objects in a texture next to you and up to four new chunk levels always come on top.",
              why = {
                  cpu = "Caps how much bake work lands on one frame. Lower keeps frames shorter but leaves levels waiting longer; on the Rosewood drive a ceiling of 4 was no better than 8.",
              } },
            { key = "bakeBudgetAdaptive", label = "One budget: follow the frame time",
              tip = "The bake budget halves after a frame that used more than 90 % of the frame-rate cap's time and grows by one after frames under 60 %, between 2 and the budget above.",
              why = {
                  cpu = "A long frame is followed by fewer bake preparations, so slow frames do not stack one on the next.",
                  gpu = "Bakes are mostly GPU work (about 0.2 ms each on the desktop); after a slow frame half as many are queued, so the GPU's bursts stay smaller.",
              } },
            { key = "bakeBudgetGpu", label = "One budget: count the GPU's time too",
              tip = "The adaptive budget above judges a frame by the time the game and render threads took. With this it also counts how long the GPU kept drawing after the frame was handed over (the frame queue's GPU timestamps), so a frame whose GPU ran long, such as a horde coming into view, also halves the next frames' bakes. Only changes when chunk pictures bake, never what they show. On by default." },
            { key = "occlusionGrantedOnly", label = "One budget: occlusion only for the levels baked",
              tip = "The pass that finds which chunk levels are hidden behind buildings looks only at the levels baked this frame, not every one still waiting.",
              why = {
                  cpu = "The hidden-level pass skips the levels still waiting: on the Rosewood 120 km/h drive p99.9 17.0 to 15.9 ms and the worst frame 45 to 27 ms.",
              } },
            { key = "bakeNoSync", label = "Bake passes never wait for the graphics driver",
              tip = "The ambient-occlusion and foliage-sway passes that run inside a chunk bake keep their own record of the GL state and take object names from a ready pool, instead of asking the graphics driver. Matters only with those Enhancements on. Same picture.",
              why = {
                  render = "Each question to NVIDIA's threaded driver made the render thread wait for every bake queued before it: in the Louisville horde with every Enhancement on, the bake time per frame p99 8.3 to 1.9 ms, worst 25 to 9 ms.",
              } },
            { key = "bakeMipLevels", label = "Mipmap levels built after a bake",
              choices = { "0", "2", "3", "4" }, note = { ["0"] = "stock: all 11" },
              tip = "The smaller copies of a chunk texture made after each bake; the widest zoom only samples the first ones, so 3 gives the same picture. 0 = stock, all 11.",
              why = {
                  gpu = "Building 3 levels instead of 11 after every bake: 0.062 to 0.020 ms, a whole bake 0.20 to 0.16 ms of GPU on the desktop. More levels cost more for nothing visible.",
              } },
            { key = "renderChunkTopUp", label = "Chunk textures made ahead of need",
              choices = { "0", "16", "32", "64" }, note = { ["0"] = "stock: made when first needed" },
              tip = "Keeps this many empty chunk textures ready for the current zoom, made two a frame, so a burst of new chunks never waits for texture creation. 0 = stock: made when first needed.",
              why = {
                  render = "A fresh chunk texture costs the render thread about 1 ms of GL allocation; made ahead, a burst of arrivals no longer pays it in one frame (210 of 210 on the Rosewood drive).",
                  vram = "The spare textures sit in GPU memory ready for use, 32 by default at the current zoom's texture size.",
              } },
            { key = "bakeBudget", label = "Chunk textures baked per frame",
              choices = { "0", "2", "4", "8", "16", "32" }, note = { ["0"] = "unlimited, stock" },
              tip = "Never-baked chunk-level textures made in one frame; the rest wait for the next frame. Used only while One prioritized bake budget per frame is off, and during a zoom's bake flood. 0 = unlimited, stock.",
              why = {
                  cpu = "A new chunk row at max zoom baked dozens of textures in one frame (26 % of slow frames); 8 a frame, with the lighting budget, took p99 13.8 to 8.3 ms on the desktop. Lower spreads more, new chunks fill in later.",
              } },
            { key = "rebakeBudget", label = "Chunk texture re-bakes per frame",
              choices = { "0", "2", "4", "8", "16" }, note = { ["0"] = "unlimited, stock" },
              tip = "Textures dirtied only by lighting drift or a redraw keep their previous image for a few frames past this many re-bakes a frame (object, tree and cutaway changes always re-bake). Used only while One prioritized bake budget per frame is off. 0 = unlimited, stock.",
              why = {
                  cpu = "Re-bakes past the budget wait a few frames instead of piling onto one; lower holds more stale images, higher allows longer frames.",
              } },
            { key = "rebakeMaxFrames", label = "Chunk texture re-bakes: longest hold (frames)",
              choices = { "1", "2", "3", "4", "6" },
              tip = "A held re-bake (a redraw) lands after at most this many frames. Used only while One prioritized bake budget per frame is off." },
            { key = "lightingRebakeBudget", label = "Lighting-only re-bakes per frame",
              choices = { "2", "4", "8", "16", "32" },
              tip = "Textures dirtied only by a lighting change (daylight drift, a lightning flash) re-bake at most this many per frame. Used only while One prioritized bake budget per frame is off.",
              why = {
                  cpu = "A lightning strike dirtied every texture on screen at once: five 50-90 ms frames per strike. Spread at 8 a frame, the storm drive's p99.9 fell 57 to 12.5 ms.",
                  gpu = "The flash's re-bakes reach the GPU a few per frame instead of all in one frame.",
              } },
            { key = "lightingRebakeMaxFrames", label = "Lighting-only re-bakes: longest hold (frames)",
              choices = { "3", "10", "30", "60" },
              tip = "A lighting-only re-bake lands after at most this many frames; a lightning strike or a fast dusk spreads over this window instead of one long frame.",
              why = {
                  cpu = "A longer window spreads a flash's re-bakes thinner, for shorter frames, but leaves light stale longer; 30 frames is an eighth of a second at 240 fps.",
              } },
            { key = "lightingRebakeMs", label = "Lighting-only re-bakes: minimum ms between two",
              choices = { "0", "50", "100", "250", "500" }, note = { ["0"] = "stock" },
              tip = "A chunk texture dirtied only by a small lighting change (daylight drift) is not re-baked more often than this. 0 = stock.",
              why = {
                  cpu = "Daylight drift moves every square a step at a time, which dirtied every texture on screen; holding them 250 ms bakes far fewer. Longer saves more but the light catches up later (1,000 measured no better).",
              } },
            { key = "zoomRetain", label = "Zoom: keep chunk textures across changes",
              tip = "Chunk-level textures that leave the screen when the camera zooms in are kept (while the chunk would still be on screen at the widest zoom) instead of being freed, so zooming back out reuses them; the ones that return, and new ones a zoom-out reveals, are baked a few per frame nearest you first, with the kept image shown meanwhile. A fast wheel spin from 0.25 to 2.5 no longer freezes the game for one long frame (worst frame 375 to 25 ms).",
              why = {
                  cpu = "Stock bakes every level a zoom-out reveals in the frame it appears, hundreds for a full spin; here they are spread 4-12 a frame over the next 20-40 frames.",
                  gpu = "Kept textures that come back unchanged need no bake, and the rest reach the GPU a few per frame instead of all at once.",
                  vram = "Textures off screen stay in GPU memory while their chunk is inside the widest zoom's view: at most what stock itself holds at the widest zoom.",
              } },
            { key = "zoomRebakeBudget", label = "Zoom: kept and new textures baked per frame",
              choices = { "4", "8", "12", "16", "24" },
              tip = "The most chunk-level textures a zoom change (kept ones coming back, new ones it reveals) may start per frame; the count drops after a long frame and grows back after short ones.",
              why = {
                  cpu = "Caps the zoom's bakes in one frame; lower spreads the fill over more frames (old images or gaps for longer), higher fills faster with longer frames.",
              } },
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
              tip = "A square whose light moved by this much since its texture was last baked (a torch or headlight beam sweeping in) re-bakes now, like stock; smaller drift keeps the lighting-only re-bake hold. Held, a moving beam showed as a patchwork of past angles, one per chunk. 255 = hold everything.",
              why = {
                  cpu = "Strong changes skip the hold, so a moving torch or headlight re-bakes the few levels in its beam each frame; a lower threshold marks more of them.",
                  gpu = "Those immediate re-bakes are GPU work (about 0.2 ms each); in a horde, turning can mark nearly every exterior level strong, which the budget below caps.",
              } },
            { key = "lightingStrongBudget", label = "Strong light changes: budget per frame",
              choices = { "0", "4", "8", "16", "32" }, note = { ["0"] = "no cap" },
              tip = "How many chunk textures with a strong light change re-bake in the same frame; the rest keep the lighting-only re-bake hold. A torch or headlight beam touches a few per frame; turning moves the out-of-sight fade over every exterior tile. Used only while One prioritized bake budget per frame is off. 0 = no cap.",
              why = {
                  cpu = "Fewer bake preparations a frame on the game thread while you turn among tall buildings.",
                  gpu = "Without a cap, turning in downtown Louisville baked 100+ tall chunk textures a frame: 10.8 fps with the GPU 97 % busy; a cap of 8 ended that.",
              } },
            { key = "lightingStrongFrameMs", label = "Strong light changes: ease on slow frames",
              choices = { "0", "9", "12", "20", "33" }, note = { ["0"] = "fixed budget", ["9"] = "default" },
              tip = "A game-thread frame longer than this many milliseconds halves the number of strong-light re-bakes allowed next frame (it grows back on fast frames). Stops the slow frame, more re-bakes, slower frame loop of a big downtown horde. Used only while One prioritized bake budget per frame is off. 0 = fixed budget.",
              why = {
                  gpu = "In a slow frame the out-of-sight fade moves further and marks more levels strong; halving their bakes keeps that GPU work from growing frame after frame.",
              } },
            { key = "lightingGlobalDeltaPct", label = "Global light move that keeps the spread (%)",
              choices = { "1", "2", "5", "10", "100" }, note = { ["100"] = "never" },
              tip = "A lightning flash or a fast dusk moves the whole scene's light at once; past this per-frame move the lighting re-bakes stay spread over frames instead of landing at once.",
              why = {
                  cpu = "A flash would otherwise count as a strong change on every exterior square and re-bake everything in one frame; lower spreads more such events, higher lets more land at once.",
              } },
            { key = "lightingBudget", label = "Lighting refreshes per frame (chunks)",
              choices = { "0", "2", "4", "8", "16", "32" }, note = { ["0"] = "unlimited, stock" },
              tip = "Chunks whose square light info is refreshed in one frame; the rest continue next frame, and nothing still queued is lost when the next lighting pass arrives. 0 = unlimited, stock.",
              why = {
                  cpu = "Refreshing the light info of a new chunk row was 12 % of the slow frames' game thread; 8 a frame, with the bake budget, took p99 13.8 to 8.3 ms on the desktop's max-zoom route.",
              } },
            { key = "lightingFlush", label = "Lighting refreshes: flush the queue before a lighting pass",
              tip = "Chunks the per-frame lighting refresh budget still holds are refreshed just before the next lighting pass rewrites their dirty bits; off, they keep stale light until it changes again (chunk-sized dark patches in a headlight beam).",
              why = {
                  cpu = "The chunks still queued are refreshed in the frame the lighting pass lands, so that frame carries the rest of the budget's work.",
              } },
        },
    },
    {
        title = "Cutaways, lighting and weather (game thread)",
        entries = {
            { key = "visBlurReduce", label = "Vision-cone edge blur summed once per texel",
              tip = "The soft edge of the vision cone is a 25-sample blur that stock runs for every pixel of the zoomed-out world image, although the sum only changes every few pixels. It is now summed once per blur texel and looked up per pixel: the same image.",
              why = {
                  gpu = "The 25 texture reads per pixel (about 340 million a frame at the widest zoom) become one: about 0.13 ms less GPU time a frame on a laptop. The pass's full-screen blend itself stays.",
              } },
            { key = "cutawayFast", label = "Replay cutaway masks",
              tip = "When walls are cut away around you, the game tests every square of every chunk level on screen to find the squares that hide others. A chunk level that has not changed now replays the mask it stored last time. Same picture.",
              why = {
                  cpu = "The per-square occluder test only runs for chunk levels that changed; an unchanged level copies its stored mask, a few bit operations instead of 64 square tests a level each frame.",
              } },
            { key = "cutawayRadius", label = "Cutaway radius (chunks)",
              choices = { "0", "1", "2", "3", "4", "6" }, note = { ["0"] = "all on screen, stock" },
              tip = "The wall cutaway only visits the chunks within this many chunks of the camera instead of every chunk on screen. 0 = stock (every chunk on screen).",
              why = {
                  cpu = "Fewer chunks to walk in each cutaway visit, which runs almost every frame while you move through a town: with the scan interval below, about 8 fps more at about 200 fps on the desktop's Rosewood route. A smaller radius walks less.",
              } },
            { key = "gridStackInterval", label = "Frames between buildings-in-front scans",
              choices = { "0", "2", "4", "8" }, note = { ["0"] = "every frame, stock" },
              tip = "While the camera's square and facing stay the same, the scan of which buildings stand in front of you (and which of their floors to hide) runs only this often; a new square, a turn or the game's own request rescans at once. 0 = every frame, stock.",
              why = {
                  cpu = "While you stand in one square facing one way, the points-of-interest, collapsing-building and hidden-floor scans run one frame in 8 (the default) instead of every frame. A higher number skips more.",
              } },
            { key = "lightSwitchCheckFrames", label = "Frames between light-switch power checks",
              choices = { "0", "5", "15", "30", "60" }, note = { ["0"] = "every frame, stock" },
              tip = "Each light switch reuses its has-electricity answer for this many frames, so a power change (the grid going off, a generator) reaches a lamp up to that many frames late. 0 = every frame, stock.",
              why = {
                  cpu = "The lighting asks every light source's switch for power every frame (grid, generators, the 3x3x2 squares around it): 2.3 % of the game thread on the Rosewood route, 0.3 % with the default 15.",
              } },
            { key = "rainTiles", label = "Rain and snow as repeated tiles",
              tip = "Rain and snow repeat one particle cell across the screen. The cell is now prepared once and the graphics card draws it at every screen position, instead of every copy being prepared on both CPU threads. Same picture.",
              why = {
                  cpu = "The game thread walked the 1,024-particle cell once per screen cell (13x7 cells at 5K, max zoom), now once: the weather draw went from 1.76 to 0.10 ms a frame in a desktop thunderstorm (111 to 188 fps).",
                  render = "The render thread uploads the cell once and issues one draw per screen cell instead of sending about 100,000 rain quads: its submission went from 5.5 to 2.9 ms a frame in the same storm.",
              } },
            { key = "rainSplashesFast", label = "Rain splashes without the game RNG",
              tip = "Rain splashes start from a cheap local random generator: one draw per splash instead of one roll of the game's random generator per idle square per frame. Same chance, timing and sprites.",
              why = {
                  cpu = "Skipping straight to the next square that splashes replaces a random roll for every idle square on screen each frame: about 2.6 % of the frame on a laptop in a storm.",
              } },
            { key = "puddleCache", label = "Puddles: cache packed vertices per chunk",
              tip = "Rain puddles keep their prepared vertices per chunk level and only refresh the light, the camera offset and the depth each frame, instead of filtering, lighting and packing every wet square again. Same picture.",
              why = {
                  cpu = "Stock re-packed every wet square of every chunk level on screen each frame: 4.5 ms of a 13 ms thunderstorm frame at max zoom on the desktop, now 0.96 ms (70 to 109 fps with the profiler on).",
                  ram = "The packed puddle vertices (32 numbers per wet square) are kept on each loaded chunk.",
              } },
            { key = "puddleCacheFrames", label = "Puddles: cache rebuild interval (frames)",
              choices = { "1", "30", "60", "120" }, note = { ["1"] = "rebuild every frame (cache off)" },
              tip = "A cached puddle batch is rebuilt with the stock code after this many frames at the latest; bakes and cutaway changes rebuild it at once. 1 = rebuild every frame (the cache does nothing).",
              why = {
                  cpu = "At 60 about 4 batches a frame are rebuilt as a backstop on a storm route; a lower number rebuilds more often (1 = every batch every frame, the stock cost), a higher one less.",
              } },
            { key = "puddleEarlyZ", label = "Puddles: early depth test in the shader",
              tip = "The puddle shaders take their depth from the vertex instead of writing it per pixel, so wet ground hidden behind walls, roofs and objects is skipped before the puddle shader runs. Same picture.",
              why = {
                  gpu = "Without a per-pixel depth write the graphics card rejects hidden puddle pixels before the ~200-step shader: puddles 0.29 to 0.10 ms a frame on the desktop's 120 km/h storm drive (357 to 390 fps).",
              } },
            { key = "puddleVbo", label = "Puddles: keep batches on the GPU",
              tip = "Each chunk level's cached puddles stay in their own buffer on the graphics card and are sent again only when a light changed, the camera crossed a chunk edge or the batch was rebuilt; nothing is copied per frame. Same picture. Works on top of the puddle cache above.",
              why = {
                  render = "The render thread no longer streams every wet square through the sprite buffer each frame: 93 to 38 % busy on the desktop's 120 km/h storm drive (232 to 325 fps).",
                  gpu = "The graphics card had been starving on the streamed puddle data; drawn from its own buffers with one draw per chunk level, puddles went from 1.3 to 0.29 ms a frame on the same drive.",
                  vram = "One vertex buffer per chunk level with puddles, kept while the chunk is loaded.",
              } },
            { key = "weatherMaskIdleSkip", label = "Skip the weather mask while nothing is drawn",
              tip = "Outdoors with no clouds, fog or rain drawn, the weather mask (which keeps rain and fog out of buildings) is neither scanned nor drawn; indoors only the player's building is scanned instead of the whole view. Same picture.",
              why = {
                  cpu = "Stock rasterised the whole view every frame, testing each outdoor square for an interior: the weather pass went from 2.6 to 0.5 % of the game thread outdoors and from 6.6 to 2.4 % on the spinning Rosewood route.",
                  gpu = "With nothing to cut, the mask's draw into its screen-sized buffer is skipped as well.",
              } },
            { key = "weatherFxScalePct", label = "Weather effects buffer size (% of screen)",
              choices = { "100", "75", "50", "33", "25" }, note = { ["100"] = "stock" },
              tip = "Clouds, fog, rain and the interior mask they are cut by are drawn into screen-sized buffers every frame; this draws them into smaller ones. The content is soft, so it looks the same. 100 = stock. Single player only.",
              why = {
                  gpu = "Fewer pixels to fill in the weather buffers. At 5K the full-size pass was about 11 % of the uncapped frame, but 50 % measured no faster on the desktop's spinning route, so the gain is small.",
                  vram = "The mask and particle buffers shrink with the square of the percentage: 50 % is a quarter of the memory.",
              } },
            { key = "fogPass", label = "Fog drawn in one pass (experimental)",
              tip = "EXPERIMENTAL. Heavy fog is drawn in one batch into a smaller buffer, tested against the scene's depth and blended over it once, instead of up to twelve layers per pixel. Same look; on the 5K desktop the 120 km/h drive in heavy fog runs at about 340 instead of 220 fps (447 in clear weather). Known issue: power lines can flicker slightly in fog while the camera moves; disabling this removes it (stock fog).",
              why = {
                  cpu = "The game thread no longer walks every square on screen per level to place the fog rows; it reads per-chunk fog masks instead: about 0.4 to 0.1 ms a frame on a laptop.",
                  render = "One draw call for every fog row (their values sent as vertex data) instead of about 190 draw calls a frame, each with three uniform updates.",
                  gpu = "Stock shades each pixel with up to 12 screen-wide rows (7 noise reads each, depth written per pixel, so nothing is rejected early): 1.5 ms a frame at 5K. The pass costs about 0.3 ms.",
                  vram = "The reduced-size fog buffer and its depth textures (a quarter of the screen per axis by default).",
              } },
            { key = "fogScalePct", label = "Fog pass: buffer size (% of screen)",
              choices = { "100", "75", "50", "33", "25" }, note = { ["100"] = "full resolution" },
              tip = "The fog pass's buffer size per axis as a percentage of the screen. Fog is soft, so the smaller buffers look the same, and edges where fog meets walls or wires are resolved against the real depth. 100 = full resolution.",
              why = {
                  gpu = "Fog work scales with the buffer's area (50 a quarter, 25 a sixteenth): on the 5K desktop about 0.8 ms a frame at 100, 0.39 ms at 50, 0.18 ms at 25.",
                  vram = "The fog buffers shrink with the area too: a sixteenth of the screen at 25.",
              } },
            { key = "fogMaskFrames", label = "Fog pass: square masks refresh (frames)",
              choices = { "0", "10", "20", "60" }, note = { ["0"] = "read every square every frame" },
              tip = "The fog rows are built from per-chunk masks of the squares that take fog instead of reading every square each frame; a mask is refreshed this many frames after its last refresh, so a newly built room reaches the fog within that many frames. 0 = read every square every frame.",
              why = {
                  cpu = "Reading the masks instead of the squares: the fog row walk went from 215 to about 35 us per level a frame on a laptop. A higher number refreshes the masks less often; 0 walks every square.",
              } },
            { key = "roofHideDebounceFrames", label = "Carport roof hide/show settle time (frames)",
              choices = { "0", "4", "8", "15", "30" }, note = { ["0"] = "stock" },
              tip = "A carport or pergola roof is hidden or shown only after the decision has held for this many frames, so a player on its edge (or pushed by zombies) does not make the roof flicker every frame." },
            { key = "cutawayVisitPrefilter", label = "Skip cutaway walls that cannot cut",
              tip = "A cutaway visit only walks the squares of walls that occlude a cut-away room, belong to a collapsing building or may hide a window being peeked through; the others are skipped before their squares are looked up. Same result.",
              why = {
                  cpu = "Most walls on screen cannot cut, so their square lookups are skipped: 454 to 466 fps on the desktop's uncapped spinning Rosewood route.",
              } },
            { key = "cutawayInvalidateChanged", label = "Re-bake cutaway chunks only when a cutaway changed",
              tip = "Each cutaway visit re-flags every cut-away wall square, and stock re-bakes every chunk holding one on every visit, which runs almost every frame while you move through a town. Only the chunks where a square's cutaway flag actually changed are re-baked. Same picture.",
              why = {
                  cpu = "Far fewer chunk textures are re-baked while you move: with the sound-zone and light-info changes of the same step, 374 to 442 fps on the desktop's uncapped spinning Rosewood route.",
                  gpu = "Every re-bake skipped is a chunk-level texture the graphics card does not redraw.",
              } },
            { key = "occlusionSkipLightingOnly", label = "Keep the occlusion grid when only lighting changed",
              tip = "The grid of squares hidden behind walls and the per-level counts of drawn squares are rebuilt only when a visible chunk level changed for a reason other than lighting drift. Lighting changes none of their inputs, so the result is the same.",
              why = {
                  cpu = "Daylight drift dirties chunk levels nearly every frame, and stock rebuilt the whole occlusion grid each time: with the chunk hand-off budget of the same step, 442 to 454 fps uncapped on the desktop.",
              } },
            { key = "lightInfoChunkGate", label = "Ask the lighting engine about a chunk level first",
              tip = "Before refreshing the 64 squares of a chunk level about to be re-baked, one question to the lighting engine says whether any of them changed; a square that was never read is always read. Same picture.",
              why = {
                  cpu = "One call instead of 64 per unchanged chunk level, each a call into the native lighting library: 466 to 500 fps on the desktop's uncapped spinning Rosewood route.",
              } },
            { key = "lightInfoOncePerFrame", label = "Ask the lighting engine once per square per frame",
              tip = "The per-square light-info call into the lighting engine is skipped when the same square was already refreshed this frame (a chunk level that needs both a re-bake and a lighting refresh asked twice). Same light.",
              why = {
                  cpu = "Each square is read from the native lighting library at most once a frame; part of the 374 to 442 fps step on the desktop's uncapped spinning Rosewood route (with two other changes).",
              } },
            { key = "soundZoneCache", label = "Reuse ambient sound zone distances per square",
              tip = "The ambient sounds (forest, farm, town and others) measure your distance to the map's zones in an 80x80 area. The answer is now reused while you stay on one square, for at most 30 frames, instead of scanned every frame. Same sound.",
              why = {
                  cpu = "Seven ambient parameters each scanned the zones of an 80x80 area every frame; part of the 374 to 442 fps step on the desktop's uncapped Rosewood route (with two other changes).",
              } },
            { key = "worldSoundFast", label = "Cheap world sounds (alarms, helicopter, gunshots)",
              tip = "Loud world sounds (a house alarm, a helicopter, gunshots) are attached to the loaded chunks only instead of every chunk in their radius, and the fish-scaring square walk runs once per game minute per identical sound instead of every frame while a house alarm rings. The same chunks hear the sound as in stock.",
              why = {
                  cpu = "A ringing house alarm adds its 600-tile sound every frame: 0.4 ms a frame in stock, 0.007 ms with this (242 to 290 fps in the desktop's alarm storm). At 2000 tiles stock took 4.4 ms a frame.",
              } },
            { key = "animBonesParallel", label = "Zombie animation bone math on other cores",
              tip = "After the object update loop the zombies' animation blending (bone, twist, model and skin matrices) runs on worker threads and is joined before rendering; off, it runs inline like stock.",
              why = {
                  cpu = "The bone math was 9 % of the game thread in the Louisville horde; the game thread now does only its share of the batch.",
                  cores = "The blending of every zombie's bones is split across the frame worker threads (8 by default).",
              } },
            { key = "frameThreads", label = "Worker threads for the zombie batches",
              choices = { "2", "4", "8", "11", "15" }, tip = "Threads of the per-frame zombie batches (bone math, decision rules, lighting reads; the game thread joins in). More than cores - 1 is clamped. Default 8, or on a CPU with fast and efficient cores and more than nine cores one per core besides the game thread's, at most 11.",
                why = {
                    cores = "More threads split each batch finer: on a Ryzen AI 9 HX 370 handheld 11 ran a horde frame about 5 % faster than 8; on the 16-core desktop 12 was no faster than 8.",
                } },
            { key = "actionEvalParallel", label = "Zombie decision rules evaluated on other cores",
              tip = "Each zombie's action-state transition rules (dozens of variable tests per zombie per frame) are evaluated on worker threads after the object loop; the state changes, the animator and the model update then run on the game thread in the stock order. Rules that call Lua, and zombies mid-grapple, stay on the game thread.",
              why = {
                  cpu = "The rule tests were 7-10 % of the game thread in the Louisville horde; it now only applies the results.",
                  cores = "The evaluations run on the frame worker threads, a share each.",
              } },
            { key = "animatorParallel", label = "Zombie animators on other cores",
              tip = "After its decision rules, each zombie's animator (which animations play and how they blend), its turn and move speeds and its animation clock run on worker threads; the sounds, footsteps and state hooks those animations trigger are replayed on the game thread in the stock order. A zombie whose animator fires such an event this frame finishes on the game thread.",
              why = {
                  cpu = "The animators were 12 % of the game thread in a horde. With the bone batch and pipeline settings the zombies' postupdate fell from about 3.0 to 0.6 ms a frame in the Louisville horde (90 to 105 fps).",
                  cores = "Each zombie's animator is a task on the frame workers; about 15 of ~800 zombies a frame fall back to the game thread.",
              } },
            { key = "headOnWorker", label = "Zombie turning checks on other cores",
              tip = "The start of each zombie's animation step (which way it faces, its aim angle, whether it is turning, turning 90 degrees or turning around) runs in its decision-rule task on a worker thread instead of on the game thread; a zombie starting or re-aiming a 180-degree turn, whose event must go through the game thread first, is left to it.",
              why = {
                  cpu = "That step leaves the game thread's postupdate loop: about 0.1 ms a frame less in the Louisville horde.",
                  cores = "It runs inside each zombie's decision-rule task on the frame workers.",
              } },
            { key = "lazyPose", label = "Look up animation keyframes only when used",
              tip = "Every animation track searched the keyframes of all 60 bones every frame; the search now happens when a bone is first read. A zombie drawn as a far sprite reads one or two bones, a modelled one reads them in the bone batch on the workers. Same poses.",
              why = {
                  cpu = "Most of the 60 keyframe searches per track per frame are never made: a far-sprite zombie reads one or two bones, and a modelled zombie's searches happen in the bone batch on the workers.",
              } },
            { key = "animatorPipeline", label = "Overlap the zombie batches with the game thread",
              tip = "The workers run the decision rules and the animators alone while the game thread applies each zombie's result as soon as it is ready, instead of taking a share of the batch and then applying everything.",
              why = {
                  cpu = "The game thread no longer waits for the whole batch before applying: it starts with the first finished zombie and runs an unstarted task itself while it waits.",
                  cores = "The decision rules and the animators run on the workers alone, overlapping the game thread's apply loop.",
              } },
            { key = "animBatchAsync", label = "Zombie bone math alongside the rest of the frame",
              tip = "The bone math batch starts after the zombie updates and runs on the workers while the game thread carries on with the rest of the frame's logic; it is joined where the game itself would wait for animation, right before drawing (anything touching a zombie's bones earlier waits for it first).",
              why = {
                  cpu = "The game thread stops waiting for the bone batch at the end of the loop: on its own this took the zombies' postupdate from 18 to 10 % of the game thread in the Louisville horde.",
                  cores = "The bone batch keeps running on the workers while the game thread goes on, until the game's own animation join point.",
              } },
            { key = "guardedCallbacks", label = "Read more zombie rules on other cores",
              tip = "Eight zombie rules (has a target, should sprint, should attack, thump, lunge, attack a car, passenger exposed, eat a body) only change something in rare cases (a target that died, a door that is out of reach, a body next to it). They are now read on the worker threads, and the rare case falls back to the game thread before anything changes.",
              why = {
                  cpu = "The game thread no longer reads these eight values for every zombie before a batch, and every zombie animation state can now be evaluated on the workers.",
                  cores = "The eight rules are read in the zombies' tasks on the frame workers; only the rare case comes back to the game thread.",
              } },
            { key = "modelLockPerInstance", label = "One model lock per character",
              tip = "The engine's model update lock was a single text constant shared by every character, so the worker threads queued behind each other on it. Each model now has its own lock; stock only ever updates a model from one thread.",
              why = {
                  cpu = "The game thread, which applies the animator results and takes part in the batch, waits less for it.",
                  cores = "The animator tasks on the workers run side by side instead of one at a time: each had run 2.4x slower in parallel, and the workers' animator time went from 5.3 to 2.7 ms a frame.",
              } },
            { key = "poolStatsBatched", label = "Batch the pool statistics of the workers",
              tip = "Every reuse of a pooled engine object bumps shared statistics counters; the worker threads now count in private tallies and add them once per batch. Same totals, no fight over one memory line.",
              why = {
                  cores = "The workers stop retrying the same shared counter update against each other (a compare-and-set loop per pooled object), so the batches scale across the cores.",
              } },
            { key = "ecsLookupFast", label = "Cheap state-machine lookups",
              tip = "Every state-machine, action-context and animation-variable access of a character went through a class walk, a map probe and a reflective cast; the answer is now remembered and zombies keep their component in a field. Same results.",
              why = {
                  cpu = "These lookups run many times per zombie per frame: 5 % of the game thread in the Louisville horde before.",
              } },
            { key = "lookupMemo", label = "Remember clothing and animation name lookups",
              tip = "Dressing a new zombie looks up dozens of clothing and animation names in tables that ignore upper and lower case, so each lookup compared strings letter by letter all the way down the table. The exact names already asked are remembered, so the same name again is one hash probe. Same answers: 3.1 million lookups compared on the Louisville horde, none different.",
              why = {
                  cpu = "A burst of new zombies or new 3D models spent 8-20 ms a second of game thread in these lookups; a remembered name is one hash probe.",
              } },
            { key = "actionConditionFast", label = "Typed decision-rule variables",
              tip = "A rule comparing a true/false or integer animation variable reads it directly; stock printed the value to text and parsed it back for every rule of every zombie every frame. Same outcomes.",
              why = {
                  cpu = "No text formatting and parsing per rule test, across the thousands of rule tests a horde makes each frame.",
              } },
            { key = "skinTransformsPrecompute", label = "Bone worker: skin matrices of zombies",
              tip = "The worker that blends a zombie's bones also multiplies them into the skin matrices of the body and clothing models it wore last frame, so the render pass finds them ready.",
              why = {
                  cpu = "The render pass on the game thread no longer multiplies the skin matrices: 3 % of the game thread in a horde.",
                  cores = "The multiplications run in each zombie's bone task on the frame workers.",
              } },
            { key = "skinPalettePrecompute", label = "Bone worker: skin palettes for the shader",
              tip = "The same worker also stores those matrices in the layout the shader reads, so the draw data copies one block instead of sixteen numbers per bone.",
              why = {
                  cpu = "Building each model's draw data copies the bone palette in one bulk copy instead of storing 16 numbers per bone.",
                  cores = "Storing the palette is added to each zombie's bone task on the frame workers.",
              } },
            { key = "shadowPrep", label = "Bone worker: zombie shadow ellipses",
              tip = "The shadow blob under a zombie (head and feet projected to the ground) is computed right after its bones on the worker instead of in the render pass; same numbers.",
              why = {
                  cpu = "Three bone-to-world transforms and three ground projections per zombie leave the game thread's render pass.",
                  cores = "They run in the zombie's bone task on the frame workers.",
              } },
            { key = "boneIndexCache", label = "Remember bone lookups by name",
              tip = "The head and feet bone indices the shadow asks for by name every frame are cached per skeleton.",
              why = {
                  cpu = "A search of the skeleton's bone names per bone, per drawn character, per frame becomes a cache hit.",
              } },
            { key = "lightingReadParallel", label = "Lighting reads on other cores",
              tip = "When a lighting pass lands, the per-square light reads of the queued chunk levels (two engine calls per visible square) run one chunk level per worker thread; the room-discovery hooks then run on the game thread in the stock order. Same light.",
              why = {
                  cpu = "These reads were 12 % of the game thread in the Louisville horde; it now takes one share and runs the room hooks.",
                  cores = "Each queued chunk level is read on a frame worker; the native lighting reads are pure reads, so the levels do not interfere.",
              } },
            { key = "zombieCullSortFast", label = "Cheap zombie relevance sort",
              tip = "The per-frame sort that decides which zombies get a full update computes each zombie's relevance score once and sorts primitive keys; stock recomputed both scores in every comparison of the sort. Same order.",
              why = {
                  cpu = "Each zombie's score is computed once instead of twice per comparison of the sort: the sort was 1.4 % of the game thread in a Louisville horde.",
              } },
            { key = "vehicleCull", label = "Skip far vehicles in the zombie line-of-sight test",
              tip = "A zombie checking whether a car blocks its view of you tests only the cars near you whose bounding circle reaches the line (one list per frame); stock ran the exact box test on every loaded car. Same answers.",
              why = {
                  cpu = "Downtown Louisville has hundreds of parked cars; the exact test (two matrix multiplies per car per zombie) was 6 % of the game thread there, 0.7 % with this.",
              } },
            { key = "separateMemo", label = "Zombie crowding: each answer once per frame",
              tip = "When zombies push each other apart, every zombie asks of each neighbour whether it counts as solid (its state, sitting, ragdoll) and whether a wall stands between their squares. While that pass runs nothing in the world changes, so with this each neighbour's answer is worked out once per frame and each worker thread remembers the wall answers it already found. Same answers. Off by default: the Louisville horde ran no faster with it." },
            { key = "nearVehicleGrid", label = "Find nearby cars from one map per frame",
              tip = "Every zombie asks each update whether a car is within four squares; stock looked up the 16 chunks around it and their car lists one by one. One table of which chunks hold a car, built once per frame, answers \"none\" for most of them; when a car may be near, the stock search runs. Same answers.",
              why = {
                  cpu = "Downtown most of those 16 chunk lookups found an empty car list: 2.4 % of the game thread in the Louisville horde, now one rectangle query on the frame's table.",
              } },
            { key = "playerLosFast", label = "Remember spotted zombies in a set",
              tip = "The list of everything you have spotted since the last quiet moment is searched once per spotted object per frame; stock walked it end to end each time, so in a horde the cost grew with the square of the zombies in view. The search is one hash probe now, and the sneak modifier zombies ask for is computed once per frame. Same list, same order.",
              why = {
                  cpu = "The player's sight pass was 16 % of the game thread in the Louisville horde, 12 of it this search; with the spot early-out below it fell to 3.7 %.",
              } },
            { key = "playerLosParallel", label = "Player sight: each zombie's checks on other cores",
              tip = "Every frame the game checks every zombie, animal and car against your sight: how far it is, whether its square is in view, whether it is climbing through a window. Those reads are made on the worker threads, and the game thread then applies the results one by one in the usual order (fade, spot roll, counters). A zombie nobody can see is finished on the worker. Same result, checked object by object. Single player only.",
              why = {
                  cpu = "The player's sight pass went from about 1.1 to 0.4 ms a frame in the Louisville horde on a laptop (74-80 to 82-84 fps over whole runs).",
                  cores = "The reads run in chunks of 64 objects on the frame workers: about 2.4 ms more CPU time a frame across the workers on the desktop.",
              } },
            { key = "zombieSpotFast", label = "Skip the spot roll for zombies that cannot see you",
              tip = "A zombie facing away from you or with you beyond its vision radius already has a zero chance to notice you; it now skips the remaining modifiers, the car test and the dice roll instead of computing them all first. Same outcome; the game's random sequence shifts by the one roll each such zombie skips.",
              why = {
                  cpu = "Most of a horde cannot see you, so their spot test stops at the first zero; with the spotted-list change above the player's sight pass went from 16 to 3.7 % of the game thread in Louisville.",
              } },
            { key = "charDrawPrep", label = "Zombie draw data built on other cores",
              tip = "The draw data of every zombie model on screen (its lights, one render record and matrix palette per body and clothing model, the depth and light setup) is built on worker threads before the game thread queues the draws in the stock order; stock built it one zombie at a time on the game thread. Same pixels.",
              why = {
                  cpu = "The characters draw went from 13 % of the game thread to under 5 % in the Louisville horde; the game thread keeps the visibility test and the per-square light refresh.",
                  cores = "About 480 zombies' draw data a frame are built on the pass's own thread pool (14 threads on a 16-core machine), overlapping the chunk bakes.",
              } },
            { key = "hordeBatch", label = "Draw the 3D zombies in batches",
              choices = { "off", "exact", "defer" },
              note = { ["off"] = "stock: every zombie its own little scene", ["exact"] = "same pixels", ["defer"] = "fastest; zombies drawn after the other moving objects" },
              tip = "The game draws every 3D zombie on its own: a full GL state save and restore, a shader switch, ~25 settings and a 60-matrix skeleton upload for each of its ~4 models. Batched, the render thread works out the same values, puts them in buffers the graphics card reads, and draws a run of zombies with one shader and one draw call per model. 'exact' keeps the stock drawing order (same pixels, checked pixel by pixel); 'defer' draws all of a frame's 3D zombies at the end of the moving objects, as if the game had listed them last (a semi-transparent edge where a zombie overlaps a flat sprite can blend the other way round). Needs OpenGL 4.3; elsewhere the stock path stays.",
              why = {
                  render = "A run of zombies needs one shader and buffered per-draw records instead of a state save and restore, ~25 uniform writes and a palette upload per model.",
                  gpu = "Most of the stock cost was per-draw state and uniform traffic, not pixels: in a 450-zombie horde the zombies' GPU time drops from about 6.5 to 1.2 ms a frame.",
              } },
            { key = "hordeBatchUpload", label = "Zombie batches: how the data reaches the GPU",
              choices = { "mapped", "subdata" },
              tip = "mapped: buffers the GPU reads straight from (OpenGL 4.4), written once. subdata: a copy per batch through the driver." },
            { key = "hordeBatchVao", label = "Zombie batches: one vertex setup per mesh layout",
              choices = { "mesh", "format" }, note = { mesh = "one per mesh, made at its first draw", format = "one per layout (default)" },
              tip = "Batched zombies drew each body and clothing mesh through its own vertex setup, made the first time that mesh was drawn, which made the render thread wait for the graphics driver, about 250 times on the Louisville route, mostly in the frames a horde comes into view. 'format' keeps one setup per vertex layout (two on that route) and points it at each mesh's buffers per draw. Same pixels (checked pixel by pixel). Needs OpenGL 4.3, like the batches.",
              why = {
                  render = "No new vertex setup per mesh: each had made the render thread wait about 1 ms for the driver, the 5-26 ms render-thread spikes when the horde came into view.",
              } },
            { key = "atlasBatch", label = "Batch the far zombies' sprites",
              tip = "Zombies too far for a 3D model are small pre-rendered sprites, about 1,100 a frame in a Louisville horde, each drawn with its own state change and draw call. They are now queued and drawn in runs, in the stock order and with each sprite's own state, from a vertex buffer the graphics card reads directly. Same pixels (checked pixel by pixel). Needs OpenGL 4.4; elsewhere the stock draws stay.",
              why = {
                  render = "One persistent vertex buffer and a run of draws instead of a full sprite-batch setup per far zombie.",
                  gpu = "Each far zombie was its own short sprite batch, about 1.3 ms of GPU a frame in the horde seconds; drawn in runs, far fewer state changes separate them.",
              } },
            { key = "hordeBatchOrder", label = "Zombie batches: models and sprites in two groups",
              choices = { "stock", "window" }, note = { stock = "a batch ends at the next sprite", window = "two groups where nothing overlaps (default)" },
              tip = "In a horde the 3D zombies and the far zombies' sprites alternate in the drawing order, and every switch ended a batch. With 'window', the models and sprites between two other objects are drawn as two groups; one may move ahead of the other only where their screen rectangles do not overlap, so every pixel is drawn exactly as before (checked pixel by pixel). Works with the sprite batching above.",
              why = {
                  render = "About a fifth as many batch flushes for the render thread to set up.",
                  gpu = "About 50 batches a frame instead of ~245, each with its own state push and pop: the horde's GPU time in the densest seconds of the Louisville route went from about 7 to 4 ms a frame.",
              } },
            { key = "hordeBatchUnits", label = "Zombie batches: textures bound 16 at a time",
              choices = { "0", "16" }, note = { ["0"] = "one texture bind before every draw", ["16"] = "default" },
              tip = "Each batched zombie model bound its texture before its draw. Now a batch binds up to 16 of its textures at once and each draw picks its own. Same pixels (checked pixel by pixel). Needs OpenGL 4.3, like the batches.",
              why = {
                  render = "About a sixteenth as many texture binds to issue.",
                  gpu = "Fewer texture switches stall the graphics card between draws: the bind before every draw had cost about 0.9 ms of GPU a frame in the Louisville horde seconds.",
              } },
            { key = "renderPrewarm", label = "Prepare the horde's drawing while the world loads",
              tip = "The first zombie drawn in 3D made the render thread set up the zombie batches' shaders and buffers, the far zombies' sprite buffer and the first clothing pictures (a new framebuffer and about 11 textures), so the moment a horde came into view stuttered. These are now made once at the first frame of the world, during loading. The same objects, made earlier: nothing drawn changes.",
              why = {
                  render = "The horde's first sight no longer makes them: on the Louisville route that frame had taken 27 ms and the first clothing picture 12-17 ms (now 2.1-3.3 ms).",
                  load = "About 0.2 s more at the end of loading, while the world appears.",
              } },
            { key = "bodyAtlasPrewarm", label = "Prepare the far zombies' body sprites while you play",
              tip = "Far zombies are drawn as flat sprites made from 128 generic body pictures, which the game drew in the frames a horde first came into view. They are now drawn two a frame from a second after the world appears, so the horde's first sight finds them ready. Same pictures, made earlier." },
            { key = "renderPrewarmOutlines", label = "Prepare the outline passes while the world loads",
              tip = "The first outlined character or object (a highlighted zombie, an item under the mouse) made the render thread set up the outline passes: three screen-sized framebuffers and their shaders. With the prepare option above they are made at the first frame of the world instead. Same pixels.",
              why = {
                  render = "No 10-11 ms render-thread frame at the first outline (measured on the Louisville route).",
                  vram = "The three screen-sized outline framebuffers take their video memory from the start of the session instead of from the first outline.",
              } },
            { key = "hordePaletteMismatch", label = "Zombie batches: palette compare in one step",
              tip = "Zombies whose bone palettes are identical share one upload; the compare read the two palettes eight bytes at a time through a call that is slow until the Java compiler has optimised it. Now one memory compare of both ranges. Same answer (checked over 228,114 compares).",
              why = {
                  render = "The slow compare cost about 0.6 ms of render thread a frame when a horde first came into view.",
              } },
            { key = "charDrawThreads", label = "Zombie draw data threads",
              choices = { "4", "8", "12", "14" }, tip = "Threads of the zombie draw-data pool; the data has to be ready before the zombies are queued. More than cores - 2 is clamped.",
                why = {
                    cores = "More threads finish the ~480 zombies' draw data sooner, so the game thread does not wait for it; on a 16-core machine 14 keep it from waiting.",
                } },
            { key = "zombieAtlasFast", label = "Flat draw call for far zombies",
              tip = "A zombie too far for a 3D model is drawn as a small pre-rendered sprite; its draw now goes through a flat copy of the game's render chain (the same tests, the same sprite call) instead of five nested virtual calls per zombie. Same pixels.",
              why = {
                  cpu = "About 1,100 such zombies a frame in a Louisville horde, each now one flat call; part of the characters draw falling from 13 to under 5 % of the game thread.",
              } },
            { key = "renderLastFast", label = "Skip the empty per-character inventory walk",
              tip = "After drawing, every character looked through its whole inventory each frame for items that draw something then; none of the game's items do. Off when Java mods are loaded. Same picture.",
              why = {
                  cpu = "About 2,500 zombie inventories a frame on the Louisville horde are no longer walked: 0.05 ms of the frame.",
              } },
            { key = "actionSnapshotFilter", label = "Read only the animation variables that need it",
              tip = "Before a zombie's transitions are evaluated on a worker, the game thread reads the variables whose engine callback has a side effect. It used to resolve every variable of every transition to find out which those are; the answer only depends on the variable's name, so it is now decided once per state. Same values.",
              why = {
                  cpu = "The per-frame snapshot was 7.6 % of the game thread in the Louisville horde; it now visits only the handful of variables with side effects.",
              } },
            { key = "emitterParamSkip", label = "Skip sound parameters for silent characters",
              tip = "A character's sound parameters (the floor material under its feet, the room it is in) are recomputed only while it actually has a sound playing or about to start; stock recomputed all of them for every zombie every frame and wrote them nowhere. Same sounds.",
              why = {
                  cpu = "The footstep material walk and the room lookup per zombie per frame were 1.6 % of the game thread in the Louisville horde.",
              } },
            { key = "separateFast", label = "Push-apart for zombies: cheap pass",
              tip = "The pass that pushes overlapping zombies apart skips the half of the engine's version that only ever applies to the player, and the grid answer 'is my square walled off from that neighbour' is computed once per square per frame instead of once per zombie. Same positions.",
              why = {
                  cpu = "The push-apart pass was 3.2 % of the game thread in the Louisville horde; zombies on a few hundred squares recomputed the same wall answers three to eight times a frame.",
              } },
            { key = "separateParallel", label = "Push-apart for zombies: on other cores",
              tip = "That push-apart is computed for the whole horde on worker threads before the update loop; the game thread applies each zombie's result at the same point in its update as before, so the collide events and window climbs keep their order. The neighbours' positions are read at the top of the frame instead of as the loop advances.",
              why = {
                  cpu = "With it the zombies' share of the game thread went from 16 to 12 % in the Louisville horde.",
                  cores = "The push-apart computes run on the frame workers at the top of the update.",
              } },
            { key = "ragdollGate", label = "Skip the ragdoll question when nothing is a ragdoll",
              tip = "The engine asks 'is this character a ragdoll?' about 60,000 times a frame in a big horde (every zombie's push-apart asks it of each neighbour), and each answer looked up the character's body model first. While no character is ragdolling the answer is no without looking at anyone; a character with no ragdoll in its animation player says no from that player. Same answers.",
              why = {
                  cpu = "About 60,000 questions a frame each looked up the body model: 1.2-2.2 % of the game thread in the Louisville horde, now close to 0; with the body model cache below, about 3 % more fps.",
              } },
            { key = "bodyModelCache", label = "Remember each character's body model",
              tip = "Every request for a character's animation player first worked out its body model (male, female, skeleton) from its descriptor and compared it with the player's. The answer is kept on the character and dropped exactly when one of its inputs changes (descriptor, gender, skeleton, the body models, the player itself). Same players.",
              why = {
                  cpu = "Getting a character's animation player went from 1.6 to 0.2 % of the game thread in the Louisville horde, and working out its body model from 0.7 to 0 %.",
              } },
            { key = "sleepCheckMemo", label = "Check 'everyone asleep' once per frame",
              tip = "The game asks whether all players are asleep on the way into every character update, and a zombie asks several times per update; the answer cannot change inside a frame, so it is computed once.",
              why = {
                  cpu = "The player list was walked tens of thousands of times a frame for this answer: 0.7 % of the game thread in the Louisville horde.",
              } },
            { key = "stateParamMemo", label = "Faster AI scratch lookups",
              tip = "The per-character scratch values the AI states keep were looked up through two maps and allocated a throwaway object on every read. Same values, one probe, no allocation.",
              why = {
                  cpu = "Each read went through the component map, a class-keyed map and a new capturing lambda; now one probe. The state-param maps were 0.9 % of the game thread in the Louisville horde.",
              } },
            { key = "actionGroupCache", label = "Keep the zombie action group",
              tip = "Every zombie asked the engine for its action group by name twice a frame, which copied the name into a new lower-case string and probed a map for a group loaded once at startup. It is held instead.",
              why = {
                  cpu = "Two lower-cased copies of the group name and two map probes per zombie per frame are gone.",
              } },
            { key = "profilerThreadMemo", label = "Cheap profiler thread check",
              tip = "Every performance probe in the game asks twice whether it is on a profiled thread, and the engine answers by scanning a list of thread names. The answer per thread is remembered. The game asks this whether or not its profiler is recording, so it helps in normal play.",
              why = {
                  cpu = "Two scans of a list of names per probe, and the zombie update and postupdate each have a probe: 0.7 % of the game thread in the Louisville horde.",
              } },
            { key = "zombieSimLodTiles", label = "Distant zombies simulated less often (tiles)",
              choices = { "0", "8", "12", "15", "20", "25" },
              note = { ["0"] = "stock: the game's own 30 / 60 / 80 tile steps only" },
              tip = "The game already updates a zombie every 2nd, 4th or 8th frame once it is 30, 60 or 80 tiles from you. This adds one more step at a closer distance, so distant zombies move in slightly coarser steps. It changes how the world is simulated, so it is off by default. 0 = stock.",
              why = {
                  cpu = "Fewer zombie updates a frame: with 8 tiles, two steps and the thump spread the zombies' game-thread time went from 1.9 to 0.85 ms a frame in the Louisville horde. A closer distance saves more.",
              } },
            { key = "zombieSimLodSteps", label = "Distant zombies simulated less often: extra steps",
              choices = { "1", "2", "3" },
              note = { ["3"] = "not recommended: three steps made the Louisville test scene unstable" },
              tip = "Each further step applies at twice the distance of the previous one, like the game's own ladder. Two steps is the measured sweet spot.",
              why = {
                  cpu = "Each further step halves the update rate of the zombies past it once more; three steps made the Louisville test scene unstable.",
              } },
            { key = "zombieCheckSpread", label = "Spread the zombie thump probe (frames)",
              choices = { "0", "2", "3", "4", "6" },
              note = { ["0"] = "stock: every frame" },
              tip = "The grid test for 'is there a door or window in front of me to thump' runs on one frame in N per zombie, spread evenly by zombie. A thump starts at most N-1 frames later than it would.",
              why = {
                  cpu = "Each zombie's door-and-window grid test runs on one frame in N instead of every frame; part of the set that took the zombies from 1.9 to 0.85 ms a frame in the Louisville horde.",
              } },
            { key = "zombieLodDynamic", label = "Zombie detail follows the frame cap",
              tip = "The game draws the 510 nearest zombies on screen as 3D models (the rest as flat sprites) and lets the 20 nearest blend their animations. With this on, both numbers follow your frame cap: while frames take longer than the cap allows they come down (the farthest zombies turn into sprites first), and they climb back towards the game's numbers while there is time to spare. Big hordes keep the cap at the cost of detail on far zombies; small crowds look exactly like the stock game. Off by default because it changes what you see.",
              why = {
                  cpu = "Fewer zombies get model draw data and animation blending each frame.",
                  render = "Fewer model draws for the render thread to issue.",
                  gpu = "Fewer 3D models to draw: in the Louisville horde with a floor of 32 models, the GPU-bound seconds (6 ms of 3D zombie draws) went and uncapped play rose from 141 to 172 fps.",
              } },
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
              tip = "Instead of following the frame cap, always draw this many of the nearest zombies as 3D models and the rest as flat sprites. 0 = follow the frame cap (the setting above).",
              why = {
                  cpu = "Fewer zombies get model draw data and animation blending each frame.",
                  render = "Fewer model draws for the render thread to issue.",
                  gpu = "Only this many 3D models are drawn instead of the game's 510; the rest are flat sprites, which cost far less to draw.",
              } },
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
              tip = "A zombie switching between its 3D model and the flat sprite fades from one to the other over this long instead of popping. During the fade both are drawn.",
              why = {
                  gpu = "While a zombie crossfades, both its 3D model and its sprite are drawn.",
              } },
            { key = "zombieFirstSightSprite", label = "Zombie detail: new zombies start as sprites",
              tip = "When a horde comes into view, a few hundred zombies take their 3D model within a second and each first makes its clothing picture on the render thread, up to about 90 in one frame. With this on, those pictures are spread over the next frames, nearest zombies first, within the budget below; a zombie still waiting is not drawn yet (as the game does while a picture is not ready) and waits at most a quarter of a second (more pictures a frame when needed). A zombie that was already drawn with its clothes (hit, hat falling off, killed, dragged) never waits. With \"Zombie detail follows the frame cap\" on, a waiting zombie is that option's flat sprite instead and its model fades in over it. It only changes what you see for a moment: a new zombie in a horde appears a few frames later.",
              why = {
                  render = "At the horde's first sight on the Louisville route the clothing pictures a frame drop from 29-34 (13-17 ms of render thread) to 9-10 (about 3 ms).",
              } },
            { key = "zombieCompositeBudgetUs", label = "Zombie detail: clothing pictures per frame (us)",
              choices = { "250", "500", "1000", "2000" }, note = { ["1000"] = "default" },
              tip = "The render-thread time a frame may spend on new zombies' first clothing pictures when new zombies start as sprites (at least one picture a frame)." },
            { key = "zombieCompositeMaxPerFrame", label = "Zombie detail: clothing pictures per frame (count)",
              choices = { "0", "1", "2", "3", "5", "8" }, note = { ["0"] = "only the time budget above", ["3"] = "default" },
              tip = "When new zombies start as sprites, at most this many first clothing pictures a frame. Each one also costs about 0.2 ms of GPU time, which the render-thread budget above does not see." },
        },
    },
    {
        title = "Entity updates on the worker threads",
        entries = {
            { key = "entityUpdateParallel", label = "Zombie updates on other cores",
              tip = "Calm zombies run their update on the worker threads instead of one after another on the game thread, with the stock four-step sequence per zombie kept. Players, animals, vehicles and busy zombies stay on the game thread; Lua events fired from a worker are replayed on the game thread in the stock order; a zombie that fails on a worker turns the batching off for the rest of the session. The batched zombies see each other where they stood at the start of the batch (like the separation pass), and their random rolls come in a different order. In a 2,700-zombie horde: 67 to 95 fps; the horde behaves the same in the brawl check (2026-10-13). On by default.",
              why = {
                  cpu = "The update loop is the only part of the frame that grows with the whole zombie population; the calm zombies leave it: -0.9 ms a frame in a steady horde on the desktop, -2 to -4 ms on the Louisville horde.",
                  cores = "The calm zombies' updates run on the eight frame workers: about 2.3 ms more process CPU a frame in a steady horde, spread over cores that were idle.",
              } },
            { key = "entityUpdatePipeline", label = "Entity updates: overlap with the game thread",
              tip = "With zombie updates on other cores, the workers run the frame's zombies while the game thread updates the animals, vehicles and busy zombies, instead of the game thread waiting for the batch. The frame-timing multiplier each zombie reads is captured per bucket, so timings stay right. Only active while the setting above is on.",
              why = {
                  cores = "The zombie batch goes to the workers before the game thread updates the animals, vehicles and busy zombies, so the workers run while the game thread works instead of waiting for it to reach the batch.",
              } },
            { key = "entityUpdatePlayerFirst", label = "Entity updates: players and cars before the batch",
              tip = "With the overlap above, your character and the vehicles update before the zombie batch starts instead of beside it. Your line of sight tells the zombies it sees that they saw you, and a moving car marks the zombies it touches; doing that while those zombies were updating on the workers lost part of it (in the brawl check the horde closed in noticeably slower), and the car's polygons are brought up to date before the workers read them. On by default." },
            { key = "entityUpdateMoreStates", label = "Entity updates: sitting zombies too",
              tip = "With zombie updates on other cores, zombies sitting against a wall or resting in their default state also update on the worker threads; their state does nothing of its own in single player, so their update is the same one the idle zombies already run there. In downtown Louisville that is about 300 zombies a frame.",
              why = {
                  cpu = "About 300 more zombies a frame leave the game thread's inline phase in downtown Louisville; most of that time is now spent waiting at the batch's join, so the update section gains only ~0.05 ms.",
                  cores = "The sitting and default-state zombies run the idle zombies' generic update on the frame workers.",
              } },
            { key = "postupdateParallel", label = "Zombie movement on other cores",
              tip = "After the updates, each calm zombie's move for the frame (its push, the walls and fences it slides along, the stairs, the square it lands on) is computed on the worker threads; the game thread only moves it to its new square, in the stock order. A zombie near a car, against a fence or touching a special object moves on the game thread as before. Checked against the stock movement: 0 differences in 692,000 moves.",
              why = {
                  cpu = "Each calm zombie's move (push, wall and fence slides, stairs, landing square) is no longer computed in the game thread's movement loop: the postupdate section 2.44 to 2.08 ms a frame in the horde.",
                  cores = "The moves are computed in one task per zombie on the frame workers.",
              } },
            { key = "postupdateNoopSkip", label = "Zombie movement: skip square moves that change nothing",
              tip = "A zombie that ends its move on the square it was already listed on no longer re-checks that square's list in the movement loop. Same lists.",
              why = {
                  cpu = "One list search per zombie per frame is skipped for zombies that stayed on their square: the movement loop about 0.06 ms a frame shorter in the Louisville horde.",
              } },
            { key = "postupdateEligibleOnWorker", label = "Zombie movement: eligibility checked on other cores",
              tip = "Which zombies may compute their move on the worker threads is decided in each zombie's own task instead of in a pass over every zombie on the game thread. Same zombies.",
              why = {
                  cpu = "The game thread no longer scans every zombie before the batch (its first touch of each zombie's animation player, a cache miss each): about -0.08 ms a frame in the Louisville horde.",
                  cores = "The eligibility test runs at the start of each zombie's own movement task on a worker.",
              } },
            { key = "postupdateVehicleOnWorker", label = "Zombie movement near cars on other cores",
              tip = "A zombie whose move comes within reach of a car resolves the car on its worker thread (the game's own collision, under its lock) instead of moving entirely on the game thread. Same moves.",
              why = {
                  cpu = "The 32-54 zombies a frame that come near Louisville's parked cars no longer fall back to the game thread's stock movement.",
                  cores = "Their car collision (the game's own resolveCollision, under its lock) runs in their movement task on the workers.",
              } },
            { key = "postupdateFuse", label = "Zombie animation decisions under the movement loop",
              tip = "A zombie with no target starts its animation-state decision on the worker threads while the game thread is still moving the others; when nothing changes it also runs its animation there. The game thread then finishes every zombie in the stock order. Same states and animations.",
              why = {
                  cpu = "The game thread no longer waits after its loop for these zombies' decisions and animators: the postupdate 1.97 to 1.60 ms a frame in the Louisville horde.",
                  cores = "A target-less zombie's animation-state decision, and when nothing changes its animator, run on the workers while the movement loop is still going.",
              } },
            { key = "postupdateClean", label = "Zombie movement loop skips finished zombies",
              tip = "A calm zombie with no target whose whole movement step needs nothing of the game thread finishes it on its worker, and the game thread's loop passes over it without reading it. Same results.",
              why = {
                  cpu = "The movement loop passes over a clean zombie without reading it: the loop 0.76 to 0.32 ms a frame in the Louisville horde.",
                  cores = "The rest of a clean zombie's postupdate (attack variables, balance, sight flag) is finished in its movement task on a worker.",
              } },
            { key = "separateEdgeCache", label = "Zombie crowding: wall answers shared between cores",
              tip = "When zombies push each other apart, whether a wall stands between two squares is worked out once per frame and kept on the square for every worker thread; before, each worker asked again. Same answers.",
              why = {
                  cpu = "The separation batch, which the game thread shares and waits for, finishes sooner: the wall test was 39 % of its time.",
                  cores = "Each wall answer is worked out once a frame and kept on the square for all workers; before, every worker asked the grid again.",
              } },
            { key = "luaSilentSkip", label = "No per-zombie Lua event when nothing listens",
              tip = "Each zombie update raised a Lua event that the worker threads recorded and the game thread replayed; while no Lua function listens to it (none in the base game) it is not recorded. A mod that listens gets it as before.",
              why = {
                  cpu = "No empty event is recorded on the workers and replayed on the game thread for every batched zombie: the replay about 0.04 ms a frame shorter in the Louisville horde.",
              } },
            { key = "luaZombieLod", label = "Mod scripts on zombies: every frame only near you",
              tip = "Some mods run a Lua function for every zombie in every frame (the OnZombieUpdate event): with 20 such functions and a big horde that was most of the frame (650-mod list, Louisville horde: 65 ms a frame, 5.6 fps). With this on, the zombies near you (12 squares) still run every mod function every frame; the others on screen run them 10 times a second and those off screen 4 times, spread so every frame carries the same share. A function that runs less often is told the time that passed since its last call (GameTime's multiplier), so mod timers keep their pace, and a function that just changed a zombie keeps running every frame on it for a second. Mods known to need every frame are left alone. On by default since 2026-10-25, with the time per frame below. Applies on the next launch.",
              why = {
                  cpu = "Only the zombies near you run every mod function every frame: with the 26 mods that listen, in the Louisville horde (2,400 zombies) the mod functions took 84 ms a frame in stock and 6.5 ms with the 2 ms budget below.",
              } },
            { key = "luaZombieBudgetUs", label = "Mod scripts on zombies: time per frame (us)",
              choices = { "0", "1000", "2000", "4000" }, note = { ["0"] = "fixed rates", ["2000"] = "default" },
              tip = "The time per frame the mod functions of the zombies not near you may take. Above it, their interval grows (up to the longest gap below) until they fit; below it, it shrinks back to the rates above. Keeps the frame time steady however big the horde, and the rate high when there are few zombies. 0 = the fixed rates only. Applies on the next launch.",
              why = {
                  cpu = "The zombie mod functions' time per frame stays near this however big the horde: Louisville horde with 26 such mods, game-thread frame 106 to 20 ms (p99 151 to 97 ms) at 2 ms.",
              } },
            { key = "luaZombieMaxGapMs", label = "Mod scripts on zombies: longest gap (ms)",
              choices = { "250", "500", "1000", "2000" }, note = { ["1000"] = "default" },
              tip = "The time per frame never stretches a zombie's mod functions further apart than this. Applies on the next launch.",
              why = {
                  cpu = "A longer gap lets the budget stretch further in a big horde, so less time per frame; a shorter one keeps mod functions closer to every frame.",
              } },
            { key = "luaZombieHz", label = "Mod scripts on zombies: fixed rate (without the near / far tiers)",
              choices = { "0", "5", "10", "20" }, note = { ["0"] = "every frame, stock" },
              tip = "Without \"every frame only near you\": every zombie's mod functions run at most this many times a second, spread over the frames. 0 = every frame, stock. Applies on the next launch.",
              why = {
                  cpu = "Each zombie's mod functions run at most this often a second instead of every frame; it only helps while the frame rate is above the rate.",
              } },
            { key = "luaZombieLodNear", label = "Mod scripts on zombies: near distance (squares)",
              choices = { "6", "12", "20" },
              tip = "The distance to you under which a zombie's mod functions run every frame. Applies on the next launch.",
              why = {
                  cpu = "A larger near distance runs more zombies' mod functions every frame.",
              } },
            { key = "luaZombieLodHz1", label = "Mod scripts on zombies: on-screen rate",
              choices = { "5", "10", "20", "30" },
              tip = "How often a second the mod functions of a zombie on screen but not near you run. Applies on the next launch.",
              why = {
                  cpu = "A higher rate runs the on-screen zombies' mod functions more often.",
              } },
            { key = "luaZombieLodHz2", label = "Mod scripts on zombies: off-screen rate",
              choices = { "1", "2", "4", "10" },
              tip = "How often a second the mod functions of a zombie off screen run. Applies on the next launch.",
              why = {
                  cpu = "A higher rate runs the off-screen zombies' mod functions more often.",
              } },
            { key = "luaZombieWriters", label = "Mod scripts on zombies: functions that change zombies",
              choices = { "watch", "learn", "full", "stagger" }, note = { ["watch"] = "every frame after a write it made", ["learn"] = "every frame after a change", ["full"] = "always every frame", ["stagger"] = "at the rate" },
              tip = "What happens to a mod function that changes zombies. watch = every mod function runs at the rate, but every frame on a zombie it changed in its last call (it called a setter of that zombie: setTarget, setUseless, setHealth ... or changed its mod data), for a second; learn = the same, only for the functions whose code names such a setter; full = those functions always every frame; stagger = at the rate like the others. Applies on the next launch." },
            { key = "luaZombieTimeComp", label = "Mod scripts on zombies: tell skipped time",
              tip = "A mod function that runs less often than every frame reads the time since its last call from GameTime's multiplier and time delta, so a mod counting time (bleeding, burning, timers) keeps its pace. Without it, such a mod runs slower in proportion. Applies on the next launch." },
            { key = "luaZombieCaptureDue", label = "Mod scripts on zombies: no record when nothing is due",
              tip = "A zombie updated on another core whose mod functions are not due in this frame is not recorded for the game thread's replay. Applies on the next launch.",
              why = {
                  cpu = "A zombie updated on another core is not recorded and replayed on the game thread when none of its mod functions is due.",
              } },
            { key = "luaZombieCheapUs", label = "Mod scripts on zombies: cheap functions every frame (us)",
              choices = { "0", "2", "5" }, note = { ["0"] = "off" },
              tip = "A mod function that takes less than this per call on average keeps running every frame (deferring it saves little). 0 = off. Applies on the next launch." },
            { key = "luaZombieCostShare", label = "Mod scripts on zombies: costly functions less often than cheap ones",
              tip = "With the time per frame above: a mod function that costs more per call runs less often than a cheap one (its interval grows with the square root of its cost, 1 to 8 times the cheapest one's, never past the longest gap), and the time this frees runs the cheap ones more often. For the same time per frame the mod functions run, on average, closer to every frame. Applies on the next launch." },
            { key = "luaZombieDirtySkip", label = "Mod scripts on zombies: skip unchanged zombies (risky)",
              tip = "A zombie whose position, state, target, health and mod data did not change since its mod functions last ran is skipped when they come due. A mod that reacts to something else (the time, the player, the weather) misses those calls: off by default. Applies on the next launch.",
              why = {
                  cpu = "Mod functions are not run on a zombie whose watched state did not change since their last call.",
              } },
            { key = "luaZombieBatchApi", label = "Mod scripts on zombies: batch event for mod authors",
              tip = "Adds Events.OnZombieUpdateBatch(zombies, elapsed): once a frame, the list of zombies whose turn it is (all of them without a rate) and for each the seconds since its last batch call. A mod written for it runs one Lua call a frame instead of one per zombie. Applies on the next launch." },
            { key = "luaCollideDedupe", label = "Mod scripts: one collision event per pair a frame",
              tip = "The game fires OnCharacterCollide / OnObjectCollide for every collision test that touches, and a pair pushing into each other is reported several times a frame. With this on, each (object, other) pair fires once a frame; both directions stay separate events. Applies on the next launch." },
            { key = "randPerWorker", label = "Zombie dice rolls: one random stream per core",
              tip = "The zombie work on the worker threads drew every random number (spotting, lunges, groans) from the game's one generator behind a lock. Each worker thread now draws from its own generator of the same kind: the same odds for every roll, but not the same sequence of numbers as the stock game.",
              why = {
                  cpu = "The batch finishes sooner, so the game thread waits less at its join: 0.59 to 0.46 ms a frame.",
                  cores = "The workers no longer queue at the one locked generator (about 7 % of the batch's samples): the batch's worker CPU 4.95 to 3.81 ms a frame in the Louisville horde.",
              } },
            { key = "emitterDefer", label = "Entity updates: sound ticks after the batch",
              tip = "With entity updates on other cores, a zombie's per-frame sound work (FMOD parameters, position, tick) is queued instead of run on the worker, and the game thread runs the queue right after the batch in the stock order - same frame, before anything renders. The workers no longer wait for each other at the sound locks." },
            { key = "entityUpdateSafeStates", label = "Entity updates: calm zombies only",
              tip = "With entity updates on other cores, only zombies that are idle, walking or following a path, with no player within 12 squares, nothing physical going on (no bullet hit being tracked, no ragdoll, not falling, not burning, not grappled) run on the worker threads; everything else updates as in the stock game. Without it the worker threads crashed the game when zombies were shot. On by default." },
            { key = "ragdollCorpseGuard", label = "Ragdolls: none for corpses (game bug fix)",
              tip = "When a shot zombie's ragdoll settles and it turns into a corpse, the game builds a second, invisible ragdoll for the corpse in the same frame from the animation left over, and nothing ever owns it: it stays in the physics world, counts against the maximum number of ragdolls, and quitting while one is there crashes the game on the way out. With this on no ragdoll is made for a character that is already a corpse. On by default." },
            { key = "ragdollQuitSweep", label = "Ragdolls: clear leftovers before quitting (game bug fix)",
              tip = "Just before the physics world is destroyed on the way out of a game, any ragdoll still in it is removed first; the physics library destroys the world before its ragdolls, and a ragdoll left over then crashed the game at quit. A safety net behind the fix above. On by default." },
            { key = "physicsDefer", label = "Entity updates: physics calls after the batch",
              tip = "With entity updates on other cores, the two physics engine calls a zombie's update can make queue instead of running on the worker, and the game thread makes them right after the batch in the stock order - same frame, before anything renders. They are the bullet hitbox of a zombie you have a gun aimed at, and the contact test between a ragdolling zombie and a car. The physics engine is not safe to call from several threads at once, and a crash inside it cannot be caught and turned off the way a Java error can, so leave this on: without it, shooting into a horde with entity updates on other cores ends the game process. Only active while entity updates on other cores is on." },
            { key = "animalLosFast", label = "Animals: skip far-zombie sight checks",
              tip = "An animal's line-of-sight update walks every zombie in range even when it is too far to change anything; those calls are skipped with the same bookkeeping applied afterwards (bit-identical numbers), and players are never skipped. Matters on farms and near hordes.",
              why = {
                  cpu = "An animal's sight call for each zombie beyond 10 squares is folded into its exact bookkeeping: with 60 cows near a horde the animals' sight 2.62 to 1.74 ms a frame on the desktop.",
              } },
        },
    },
    {
        title = "More game-thread work on the worker threads",
        entries = {
            { key = "renderPrepParallel", label = "Character shadows and reflections: prepared on other cores",
              tip = "Every character on screen asks the sun shadows how much sun reaches it (a walk through the buildings towards the sun) and the reflections whether water is near it. Both answers only read the world, so they are worked out on the worker threads while the game thread draws the ground; the characters then take them ready. Same picture.",
              why = {
                  cpu = "Two world walks per on-screen character leave the game thread's character draw: 0.57 to 0.22 ms a frame in Louisville on the desktop.",
                  cores = "Each character's sun share and water proximity are worked out on the frame workers while the game thread draws the ground.",
              } },
            { key = "pplPackParallel", label = "Per-pixel lighting: light map built on other cores",
              tip = "With per-pixel lighting, the light of every changed piece of the screen is packed on the worker threads, each piece into its own slot, instead of one after another on the game thread. Same light.",
              why = {
                  cpu = "The light-map packing leaves the game thread: with the torch test per chunk, its preparation 0.56 to 0.17 ms a frame in Louisville on the desktop, 1.04 to 0.35 ms on the laptop's drive.",
                  cores = "Each changed piece of the light map is packed in its own task on the frame workers.",
              } },
            { key = "pplTorchNearChunk", label = "Per-pixel lighting: torch test per chunk",
              tip = "With per-pixel lighting, whether a torch reaches a square is first decided for the whole 8x8 chunk; only a chunk a torch can reach tests its squares. Same result.",
              why = {
                  cpu = "One reach test per 8x8 chunk replaces 64 square tests where no torch reaches; measured together with the light map on other cores (above).",
              } },
            { key = "schedulerClassifyParallel", label = "Update schedule computed on other cores",
              tip = "Every frame the game decides how often each zombie, animal and car is updated (distance, visibility, what it is doing); the worker threads compute it and the game thread files the results in the same order. Same schedule.",
              why = {
                  cpu = "The per-object decisions leave the game thread: the scheduler's start 0.28 to 0.17 ms a frame in a steady horde on the desktop, -0.34 ms on a Mac.",
                  cores = "Each object's update rate (its simulation level) is computed on the frame workers; the game thread only files the results.",
              } },
            { key = "schedulerFillParallel", label = "Update schedule: the whole sort on other cores",
              tip = "With the update schedule computed on other cores, the rest of that step moves too: the worker threads also make the per-object checks the game thread still made, read each zombie's current behaviour from a remembered reference instead of a table lookup, and the game thread files the results in the usual order from what the workers read, without going back to each zombie. Same schedule, checked list by list. On by default since 2026-10-15.",
              why = {
                  cpu = "The game thread fills the schedule from arrays the workers wrote, without touching each object (a cache miss each before): the scheduler's start 0.43 to 0.32 ms a frame in the Louisville horde.",
                  cores = "The square test and every per-object read the fill needs are made in the workers' classification tasks.",
              } },
            { key = "zombieStatsFold", label = "Zombie travel statistics once per frame",
              tip = "Every zombie added its walked distance to the statistics and re-checked the achievements, 2,000 times a frame in a horde. The same additions are summed in the same order and written once per frame. Same totals.",
              why = {
                  cpu = "About 2,000 statistics-map additions and achievement checks a frame in a horde become three: roughly -0.4 ms a frame in a steady horde on the desktop.",
              } },
            { key = "visPolyAsync", label = "Vision cone shape: computed on its own thread",
              tip = "While you walk, drive or turn, the shape of your vision cone (the walls and trees that cast its shadows) is recomputed every frame. It only depends on where you stand and look, so it is worked out on a thread of its own while the game thread draws the ground, and is ready when the cone is drawn. Same cone.",
              why = {
                  cpu = "The cone's shape is no longer built on the game thread; with the shading data on other cores, the laptop's tile drawing -0.41 ms a frame on a 120 km/h drive.",
                  cores = "The shape is built on a thread of its own from the start of the tile drawing, while the game thread draws the ground.",
              } },
            { key = "aoContextParallel", label = "Shadows and ambient occlusion: bake data on other cores",
              tip = "With ambient occlusion or sun shadows on, every chunk the game redraws also gathers which squares hold grass, trees, walls and roofs around it. Those reads are done for all the chunks of a frame at once on the worker threads, instead of one by one inside each redraw. Same shading.",
              why = {
                  cpu = "A chunk redraw no longer gathers its neighbourhood's grass, tree, wall and roof masks itself; with the vision cone on its own thread, the laptop's tile drawing -0.41 ms a frame on a drive.",
                  cores = "The masks of all the chunks a frame redraws are gathered in one batch on the frame workers.",
              } },
            { key = "translucentOrderCache", label = "See-through objects: drawing order kept",
              tip = "Objects drawn every frame (windows, doors, wind-blown plants, items) are put in drawing order level by level, every frame. That order only changes when the level changes, so it is kept and reused until then. Same order, checked against the recomputed one over a whole drive.",
              why = {
                  cpu = "The level-by-level merge and sort runs only when a level changes: the laptop's tile drawing -0.30 ms a frame on a 120 km/h drive.",
              } },
            { key = "losLightPrefetch", label = "Player sight: light read ahead on other cores",
              tip = "The squares the player's sight check reads have their light refreshed on the worker threads first. Off by default: measured slower than reading them one by one.",
              why = {
                  cpu = "Handing the squares out and waiting for the batch costs more than the sight check's own reads (~130 ns a square): the check +0.12 to 0.14 ms a frame.",
                  cores = "The light refresh of the sight squares runs on the frame workers ahead of the check.",
              } },
            { key = "zombieSpawnSpread", label = "Zombie creation queue: new zombies spread over frames",
              tip = "When a chunk next to a horde loads, the game turns dozens of zombies real in one frame, each one dressed and given its animations. With this on they are created from a queue within a small time a frame (the frame's spare time under a frame cap), so they appear a few frames later; a zombie within 25 squares of you is still created at once, the first 3 seconds of a load create everything at once as the game does, and none waits longer than the longest wait below, so the horde is never thinned. Changes only when zombies appear, not which or where. On by default since 2026-10-15.",
              why = {
                  cpu = "Dressing and animating dozens of new zombies no longer lands in one frame of several milliseconds; the same work is spread over the next frames within a small budget a frame.",
              } },
            { key = "zombieModelAddBudgetUs", label = "Horde first sight: 3D models given per frame (us)",
              choices = { "0", "250", "500", "1000" }, note = { ["0"] = "stock: every zombie at once", ["500"] = "default" },
              tip = "A zombie that gets its 3D model builds the model and every piece of its clothing in that frame; when a crowd comes into view or the view turns, many do it at once. With a budget, the zombies past it stay their flat sprite a frame longer (lit, with \"new zombies start as sprites\") and get their model in the next frames, nearest first. On by default since 2026-10-15 (500).",
              why = {
                  cpu = "Model and clothing builds past the budget move to the next frames instead of piling into one 4 ms frame: in the Louisville horde frames over 12 ms halved (4.7 to 2.4 %). Lower spreads more, higher gives models sooner.",
              } },
            { key = "burstGovernor", label = "Horde first sight: lighter work in heavy frames",
              tip = "When a horde first comes into view, dozens of frames in a row run late. A frame that started late for its slot or followed a frame that used nearly all of its time now creates fewer queued zombies and gives fewer zombies their 3D model; that work moves to the lighter frames right after. Changes only when zombies appear, not which or where. Acts at a frame cap, on the zombie creation queue and on the 3D model budget (both on by default since 2026-10-15).",
              why = {
                  cpu = "In a run of late frames the zombie creation and model building get the smaller heavy-frame budgets below, so that work lands in the lighter frames after them instead of on the late ones.",
              } },
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
        title = "Sprite buffers",
        entries = {
            { key = "persistentVbo", label = "Persistently mapped sprite buffers",
              tip = "Sprite ring buffers use persistently mapped buffer storage instead of an orphan and re-map per batch. Matters most uncapped and zoomed out, where the render thread is the limit.",
              why = {
                  render = "Stock orphaned and re-mapped a GL buffer for every 64 KB of sprites, ~25 % of the render thread at max zoom: its busy time 63 to 35 % on the max-zoom route, about 2.7x the uncapped frame rate there.",
              } },
            { key = "persistentVboFrameSync", label = "Sprite buffers: one fence per frame",
              tip = "The persistent sprite buffers are fenced once per frame instead of per 64 KB batch, and a buffer last used 8 frames ago is reused without asking the driver.",
              why = {
                  render = "Each fence check is a round trip to NVIDIA's driver thread (~1 ms under load), even for a long-signalled fence; one fence a frame removes nearly all of them.",
              } },
            { key = "persistentVboSlots", label = "Sprite buffers: storage slots",
              choices = { "1", "2", "4" }, note = { ["1"] = "stock size" },
              tip = "Each persistent sprite buffer holds this many batches before it wraps, so a wrap rarely meets one the GPU still reads.",
              why = {
                  render = "With more slots a wrap of the 128-buffer ring rarely meets a batch the GPU still reads, so the render thread waits on a fence less often (within the run noise on the town drive).",
                  vram = "Each sprite buffer keeps this many storage slots instead of one: at 4, four times the ring's buffer memory.",
              } },
            { key = "vboBatchKb", label = "Line/particle batch buffer (KB)",
              choices = { "4", "256", "1024" }, note = { ["4"] = "stock: rain flushes every 28 particles" },
              tip = "Rain and snow particles, debug lines and other VBORenderer quads are uploaded and drawn in batches of this size instead of 4 KB.",
              why = {
                  render = "Stock uploaded and drew every 28 quads, 73 % of the render thread in a thunderstorm at 5K; with 1024 KB and single-advance quads the storm's submission 8.7 to 6.1 ms a frame.",
                  vram = "The batch's vertex and index buffers are created at this size (up to 1.5 MB) instead of 4 KB.",
              } },
            { key = "vboFastQuads", label = "Single-advance particle quads",
              tip = "VBORenderer writes a textured quad's four vertices in one go instead of four bookkeeping round trips; same bytes.",
              why = {
                  render = "Four vertices, four indices and one position advance are written in one step instead of four checked calls; part of the storm's 8.7 to 6.1 ms submission with the larger batch.",
              } },
        },
    },
    {
        title = "Input latency (keyboard, mouse, controller)",
        entries = {
            { key = "keyboardFresh", label = "Keyboard read in the frame it was polled",
              tip = "The game read the keyboard one frame behind the mouse and the controller: it filled its key table from the previous poll and swapped the new one in afterwards. With this on it reads the new one. One frame less for every key (4 ms at 240 fps, 17 ms at 60). Applies on the next launch." },
            { key = "inputLatch", label = "Read the newest input when a frame starts",
              tip = "The game polled input once, right after the previous frame was shown, and used that until its next frame started (the frame limiter's idle time later). With this on, the game asks for a fresh poll the moment its frame starts: mouse and controller reach the game in ~2 ms instead of ~5.5 at 240 fps, and up to a frame sooner at low frame caps. Applies on the next launch." },
            { key = "reflexSleep", label = "Low-latency mode (Reflex-style pacing)",
              tip = "What NVIDIA Reflex does, built with OpenGL (Reflex itself has no OpenGL version): each frame measures how long it waited in the queue to the screen (the hand-off to the render thread, a vsync swap that blocks, the driver's queue), and the next frame starts that much later so it arrives just in time, carrying newer input. With vsync on and an uncapped game, together with the cap below: 25-28 ms from input to screen became 5-10 ms. Does nothing when nothing queues (a frame cap below the refresh, vsync off). Applies on the next launch." },
            { key = "reflexBoost", label = "Low-latency boost: keep the GPU clocks up (NVIDIA)",
              tip = "At a frame cap the GPU is idle most of each frame, so the driver lowers its clocks and the frame it does draw takes longer, which the input waits for. This holds the driver's \"prefer maximum performance\" setting while a world is loaded, what Reflex's On + Boost does. The driver drops it again when the game exits, even after a crash. Uses more power; NVIDIA only. Applies on the next launch.",
              why = {
                  gpu = "With the clocks held up each frame's GPU work finishes sooner: 3.9 to 1.3 ms at a 60 fps cap on an RTX 4090, for about 28 W more power.",
              } },
            { key = "reflexCapFps", label = "Low-latency mode: cap just below the refresh",
              choices = { "0", "-1" }, note = { ["0"] = "off", ["-1"] = "auto" },
              tip = "With vsync on, a game that runs a hair faster than the screen refills the queue the low-latency mode drained. Auto caps the frame rate at refresh - refresh x refresh / 3600 (157 at 165 Hz, 224 at 240 Hz), what Reflex does with vsync. On a fixed-refresh screen one frame in ~20 is shown twice. Applies on the next launch.",
              why = {
                  cpu = "With vsync the game makes a few percent fewer frames than the refresh (157 instead of 165 at 165 Hz), so the game thread prepares fewer frames a second.",
                  gpu = "The GPU draws those few percent fewer frames too.",
              } },
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
        title = "Menus, inventory and map (UI)",
        entries = {
            { key = "uiRetained", label = "UI: redraw only what changed, the moment you act",
              tip = "The game redrew every open window's Lua 60 times a second into the UI picture, although almost all of them drew exactly the same as the frame before, and showed a click or key only at the next of those redraws. With this on each window's drawing is kept and reused while nothing can have changed it (no click, key, wheel or mouse over it, no change to its data, the inventory not marked for refresh); anything you do is drawn in the same frame.",
              why = {
                  cpu = "Kept windows are replayed instead of running their Lua: at 240 fps the UI's share of the frame 4 to 0.9 % with the HUD, 9.8 to 1.4 % with the inventory open, 16.6 to 2.2 % with crafting, 40 to 4 % with the map.",
              } },
            { key = "uiRetainedChildren", label = "UI: reuse unchanged buttons and panels inside windows",
              tip = "The same for the buttons, lists and panels inside a window that is redrawn (the map's symbol buttons, the inventory's container buttons): only the part under the mouse or with new data is drawn again.",
              why = {
                  cpu = "Unchanged buttons and panels are replayed instead of redrawn: the map's share of the frame 6.4 to 4.1 % (its symbol buttons).",
              } },
            { key = "uiVerify", label = "UI: changes shown the frame they happen",
              tip = "A window kept from an earlier frame is now checked every frame against exactly what its drawing read the last time (the Lua fields it used, the item names, stats and conditions it asked the game for, the mouse position) and drawn again in the frame any of them changes, instead of after a timer of up to a second. Measured at 240 fps with the ground-truth rig (every window drawn every frame for comparison): items renamed or worn in an open inventory late on 43 % of frames before, 0.4 % now; skill XP arriving 61 % to 0.7 %; mouse over inventory rows and a 200-item stack 25-29 % to 0 %.",
              why = {
                  cpu = "Each kept window's read set (Lua fields, game getters, the mouse) is re-checked every frame and changed windows drawn at once: e.g. the inventory with a 200-item stack 1.8 to 2.3 % of the frame at 240 fps.",
              } },
            { key = "uiAnimate", label = "UI: movement drawn every frame",
              tip = "A window whose picture is changing (smooth scrolling, an item being dragged, a fade) is drawn on every frame while it moves instead of 60 times a second. Wheel scrolling through a 200-item stack showed an out-of-date list on 58 % of frames before, 0 % now. Uses only the time the frame has left before the frame cap (see the next setting); never draws less often than before.",
              why = {
                  cpu = "A moving window runs its Lua every frame while it moves: scrolling a 200-item stack 8 to 15 % of the frame at 240 fps, taken from the frame's spare time.",
              } },
            { key = "uiSlack", label = "UI: extra redraws only in the frame's spare time",
              tip = "The redraws for mouse movement and animation are done only when they fit in the time the frame has left before the frame cap (measured each frame), so the frame rate does not drop for them; a window that has waited the stock 1/60 s is drawn anyway (never later than before). Uncapped they get a fifth of the frame.",
              why = {
                  cpu = "Optional redraws are granted only from the frame's spare time (uncapped a fifth of it), so they never push the game thread past the cap: 240 fps held in every UI scene measured.",
              } },
            { key = "uiMoveReplay", label = "UI: dragged windows moved, not redrawn",
              tip = "While you drag a window that draws the same thing wherever it is, its kept picture is moved to the new place every frame (what a desktop compositor does with a window) instead of running its Lua again; it is still redrawn 60 times a second, so a change during the drag shows at the old rate at the latest.",
              why = {
                  cpu = "A dragged window's kept picture is shifted to its new place instead of its Lua running every frame of the drag.",
              } },
            { key = "uiTickStagger", label = "UI: window updates spread over frames",
              tip = "Every window's Lua update ran in the same frame ten times a second. Each window now keeps its own tenth of a second, spread over the frames: the same updates, no periodic spike.",
              why = {
                  cpu = "The updates no longer land in one frame: the worst UI-update frame 711 to 435 us with the inventory, 1.34 to 1.14 ms with crafting; the average is the same.",
              } },
            { key = "uiLuaFast", label = "UI: inventory window shortcuts",
              tip = "A hidden inventory window no longer rebuilds its container buttons row ten times a second (it does when shown), and the item list skips a second pass over every item when nothing is being dragged. Same result on screen; not used when a mod replaced those functions.",
              why = {
                  cpu = "A hidden inventory skips its 70-100 us button re-arrange ten times a second and the item list one pass: the HUD's element updates ~30 to ~20 us a frame.",
              } },
            { key = "hotsaveWarmup", label = "Transfers: first save warmed up while loading",
              tip = "Dropping or looting items makes the game save the map's building, room and zone records on the next save pass, on the game thread. The first time in a session that code ran cold: a stutter a moment after the first transfer. It is now run three times into a scratch buffer on the loading screen, so that first save runs warm.",
              why = {
                  cpu = "The first save pass after a transfer runs warm code: its map records 16.5 to 5.8 ms, the one-off stutter gone.",
                  load = "The three warm-up passes add about 36 ms to the loading screen.",
              } },
            { key = "mapStreetMemo", label = "Map: street names laid out once per view",
              tip = "The world map measured every street, translated every street name for every letter and sorted the visible streets with a list search in every frame. Lengths and names are now kept per view and language.",
              why = {
                  cpu = "Street lengths and translated names are worked out once per view and language, and the sort uses one index pass: the map's UI 6.4 to 3.8 ms a redraw.",
              } },
            { key = "mapStreetCache", label = "Map: street labels reused while the view stays",
              tip = "While the map is not moved or zoomed the whole street-label layout is reused.",
              why = {
                  cpu = "The label layout is not redone while the view, streets, options, style and language stay the same: the map's UI 3.8 to 1.4 ms a redraw.",
              } },
            { key = "mapVisitedFast", label = "Map: explored-area texture built a row at a time",
              tip = "The map's dark / explored overlay was rebuilt pixel by pixel, re-computing the row width for every pixel; the first map open after loading rebuilds all 16 million. It is now built a row at a time, the same pixels.",
              why = {
                  cpu = "Each texture row is built in an array and copied at once instead of four checked writes per pixel: the first map open's long frame 97 to 67 ms.",
              } },
            { key = "luaJit", label = "Lua: hot functions compiled to Java code",
              tip = "Lua functions the game and mods call often are compiled to Java bytecode on a background thread, so the Java JIT runs them like Java code. The same results, errors, stack traces and mod profiler entries as the interpreter; functions it cannot compile keep running interpreted.",
              why = {
                  cpu = "Lua registers stay in Java locals and numbers unboxed instead of every instruction writing the shared Lua stack: the Lua VM benchmark's kernels 4.4 times faster (geomean, 513 to 117 us), a mod LoadGridsquare handler 5.4 times, an OnTick handler 7.7 times.",
              } },
            { key = "luaJavaFast", label = "Lua: game functions called without reflection",
              tip = "A Java method Lua calls often (getPlayer, square:getObjects, ...) gets a small generated caller: the same argument checks, conversions and error reports as the stock path, without Java reflection.",
              why = {
                  cpu = "No reflective invoke and no pooled argument objects per call: with the compiler on, the benchmark's LoadGridsquare kernel 899 to 351 us.",
              } },
            { key = "luaFastTable", label = "Lua: compact tables",
              tip = "Lua tables are stored in a compact map with the same iteration order, which the compiled Lua code reads directly.",
              why = {
                  cpu = "Compiled field reads find a key's slot by one identity compare and array-like tables are read by index: the Lua VM benchmark 140 to 117 us (geomean) with the compiler on.",
              } },
            { key = "luaPatternFast", label = "Lua: faster text patterns",
              tip = "string.find, match, gmatch and gsub run the game's own pattern matcher rewritten on plain positions: the same steps and the same answers and errors. A pattern with a back-reference (%1) keeps the stock matcher.",
              why = {
                  cpu = "No position object per matching step: the string benchmark of the patterns mods run every frame (a per-mod gsub, a list gmatch, item-type matches) 9.5 to 3.2 us.",
              } },
            { key = "luaStringFast", label = "Lua: text patterns without the shared pool",
              tip = "The stock pattern matcher takes its position objects from a plain allocation instead of a locked pool shared by all text calls. Applies to the patterns the faster matcher leaves to it (or all of them with it off).",
              why = {
                  cpu = "No locked pool per position object: the same benchmark on the stock matcher 9.5 to 4.4 us.",
              } },
            { key = "luaIndexCache", label = "Lua: class lookups remembered",
              tip = "A Lua method looked up through a class chain is remembered until any class changes. Exact; no measurable change on the UI scenes." },
            { key = "luaInternConstants", label = "Lua: shared text constants",
              tip = "Text constants of every Lua file are shared, so table lookups compare them by identity first. Exact; no measurable change on the UI scenes." },
            { key = "luaSkipEmpty", label = "Lua: empty UI handlers not called",
              tip = "A window's draw, update or mouse handler that is an empty Lua function is not called. Exact; no measurable change on the UI scenes." },
        },
    },
    {
        title = "Mod compatibility",
        entries = {
            { key = "modProfile", label = "Mods: max performance or max compatibility",
              choices = { "performance", "compatibility" }, note = { ["performance"] = "max performance" },
              tip = "Sets the two settings below at once (also from the main menu: PZ OPTIMIZATION / MOD COMPATIBILITY CHECK). Performance (default): Java mods are checked and listed but switch nothing off, and windows mods draw in are reused like the game's own. Compatibility: the settings a Java mod patches are switched off so the mod meets the code its author tested, and windows mods draw in are redrawn at the game's own rate. A value you pick for either setting below still wins. Applies on the next launch.",
              why = {
                  cpu = "Performance keeps every optimisation in the methods Java mods patch and reuses the windows mods draw in; Compatibility switches those off, so the game thread does the stock work there.",
              } },
            { key = "modCompat", label = "Java mods: switch off what they patch",
              choices = { "auto", "report", "off" }, note = { ["auto"] = "max compatibility", ["report"] = "max performance" },
              tip = "PZ Optimization ships whole game classes; a Java mod (a ZombieBuddy mod, or a -javaagent such as PZMulticore) patches methods of the same classes. At launch the mods' jars are read: where one patches a method we changed, the settings that live in that method are switched off, so the mod meets the code its author tested. Mods tested together with ours keep everything. Your own choice on this tab still wins. Report = only list them (Zomboid/pzopt/mod-compat.txt, the console and the main menu's PZ OPTIMIZATION / MOD COMPATIBILITY CHECK); off = no check. The default follows Mods: max performance (report) or max compatibility (auto). Applies on the next launch.",
              why = {
                  load = "The mods' jars are read at launch to find the methods they patch: 24 ms with no Java mod, 163 ms with ZombieBuddy and Ultimate ZBetterFPS. Off skips the scan.",
              } },
            { key = "uiRetainedMods", label = "UI: reuse windows that mods draw in",
              tip = "Windows and buttons that carry a mod's drawing (a mod's own window, or a game window whose drawing a mod replaced or extended). On = reused like the game's own windows (a mod's display can lag by up to a second, because a mod may draw from state the reuse cannot see); off = redrawn at the game's own rate. The default follows Mods: on for max performance, off for max compatibility. Applies on the next launch.",
              why = {
                  cpu = "Mod-drawn windows are replayed like the game's own instead of running their Lua 60 times a second.",
              } },
            { key = "luaWorkerGate", label = "Lua from helper threads runs on the game thread",
              tip = "With zombie updates spread over helper threads, a mod's Lua the update reaches (a zombie trampling a crop runs the farming mod code, a mod hooked into the zombie update) ran on the helper thread, beside the game's own Lua: an error \"Lua code called from the wrong thread\" and, at worst, a broken Lua stack. With this on such calls run on the game thread, in the game's order. Applies on the next launch." },
        },
    },
    {
        title = "Mod Lua events (OnTick, OnPlayerUpdate)",
        entries = {
            { key = "luaEventDispatchFast", label = "Mod events: faster handler loop",
              tip = "The game walks an event's whole handler list after every handler to see whether it is still listed (with 600 mod OnTick handlers that is 360,000 compares a frame) and builds the profiler's label for each call even with the profiler off. This loop asks the slot it just called first. Exactly the same handlers in the same order, whatever a handler adds or removes. Applies on the next launch.",
              why = {
                  cpu = "With ~800 mod handler calls a frame the loop's own cost fell from 0.17 to 0.10 ms a frame (650-mod list).",
              } },
            { key = "luaEventSkipEmpty", label = "Mod events: empty handlers not called",
              tip = "An event handler that is an empty Lua function is not called. Exact. Applies on the next launch." },
            { key = "luaTickHz", label = "Mod OnTick / OnPlayerUpdate rate",
              choices = { "0", "30", "60", "120" }, note = { ["0"] = "every frame, stock", ["60"] = "the rate most mods are written for" },
              tip = "Mods' OnTick, OnPlayerUpdate and OnTickEvenPaused handlers run at most this many times a second instead of every frame, spread over the frames so no frame takes them all; below this frame rate nothing changes. The game's own handlers and those that read the keyboard, mouse or pad, place windows, read the camera or move a character stay every frame. A handler that was skipped reads the time of every frame it missed (getMultiplier, getTimeDelta, ...), so timers, fuel and needs advance as before. Never on a server, not while everyone sleeps. Applies on the next launch.",
              why = {
                  cpu = "At ~300 fps (a 55-mod list, 60 a second = a call every 5 frames) the mods' tick handlers fell from 0.28 to 0.16 ms a frame and the frame time 3 to 8 %; on a 650-mod list with the cap at 4 frames a call, mod OnTick fell from 30.6 to 8.2 ms a frame.",
              } },
            { key = "luaTickAdaptive", label = "Mod OnTick / OnPlayerUpdate when the game is slow",
              choices = { "off", "budget", "aimd" }, note = { off = "stock", budget = "a time budget from the frame's headroom", aimd = "the budget halves when late, grows when on time" },
              tip = "When the game thread is the limit and the frame rate is under the cap, mods' heavy per-frame handlers are called less often (never less than every 4 frames or every quarter second), the heaviest first, each handler getting a fair share. The same exceptions and time catch-up as the rate above. Applies on the next launch.",
              why = {
                  cpu = "Only while the game thread is the limit: the mods' heaviest per-frame handlers run every second to fourth frame.",
              } },
            { key = "luaTickInputWake", label = "Mod key and mouse polls follow the rate too",
              tip = "A mod handler that only polls the keyboard, mouse or pad (and does nothing else that needs every frame) normally stays every frame under the rate above. With this on it follows the rate, and also runs in every frame where a key, a mouse button or the wheel changed, so it still sees every key press and release in the frame it happens; while a controller is in use it stays every frame. Applies on the next launch.",
              why = {
                  cpu = "The mods' key and mouse pollers run at the rate above instead of every frame, plus the frames where input changed.",
              } },
            { key = "luaErrorThrottle", label = "Mod errors repeated every frame",
              choices = { "0", "3", "10" }, note = { ["0"] = "every time, stock", ["3"] = "the first three, then counted" },
              tip = "A mod handler that fails every frame prints the same Java and Lua stack trace every frame. With 3 or 10, the same error from the same handler is printed that many times, then counted (a line every 100 repeats). The error count, the error popup and the debug error list still see every error. Applies on the next launch." },
        },
    },
    {
        title = "Driving smoothness (the car's stutter and rubber banding)",
        entries = {
            { key = "vehicleSmooth", label = "Vehicles drawn between physics steps",
              choices = { "interp", "extrap", "off" },
              note = { interp = "drawn between the last two physics steps (10 ms behind the simulation)", extrap = "the last step carried forward (no delay; overshoots for one step when a car stops dead)", off = "stock: the last whole 100 Hz step" },
              tip = "Car physics runs in fixed 10 ms steps. Stock draws the car, its passengers and the camera that follows it at the last whole step, so at 120 fps one frame in six shows no motion at all and at 240 fps the car moves on 5 frames of 12: the whole world judders under the car. Drawn at the matching point between two steps, the motion is even at any frame rate (world judder at 120 km/h, zoom 1: 6.5 to 0.9 px). The simulation itself is untouched." },
            { key = "driveCameraLate", label = "Driving camera placed after the car moved",
              tip = "Stock places the camera inside the player's update; when the game updates the player before the car (it depends on the save), the camera sits one physics step behind on most frames and catches up on the others: the car jumps back and forth by ~20 pixels, 20 times a second (the rubber band). On: the camera is centred on the driver after every update of the frame. Included in the setting above whenever that is on." },
            { key = "driveLookSmooth", label = "Driving look-ahead in fractions of a pixel",
              tip = "The camera looks ahead of a moving car. Stock moves that look-ahead in whole pixels, paced by a millisecond clock, so the car creeps across the screen in uneven steps. On: fractional, paced by the nanosecond clock (the car moving backwards on screen: 4.2 to 1.0 % of frames at zoom 1)." },
            { key = "cameraScreenPixels", label = "Zoomed in: camera in screen pixels",
              tip = "Zoomed in closer than 100 %, stock moves the world in whole pixels of the zoomed image: 2 screen pixels at 50 %. On: in whole screen pixels, so the scroll is twice as fine at 50 % and every tile still lands exactly on a pixel. No change at 100 % and out." },
            { key = "frameClockSmooth", label = "Game time on the display's refresh grid",
              tip = "Each frame simulates the time the previous one took; with vsync or a frame cap the frames reach the screen on a fixed grid, so a frame that started half a millisecond late moves the world by that much more than the screen shows. On: frames close to the grid step exactly one (or two...) refresh periods and the difference to the real clock is fed back slowly, so game time never drifts." },
            { key = "physicsStepHz", label = "Vehicle physics steps per second",
              choices = { "100", "120", "144", "200", "240" }, note = { ["100"] = "stock" },
              tip = "The fixed rate Bullet runs the vehicles at. Matching the display (120 at 120 Hz) makes one step per frame, but only at exactly that frame rate; the drawing between steps above does the same at any rate and keeps the physics stock. For comparison.",
              why = {
                  cpu = "Bullet runs more vehicle steps a second on the game thread (120-240 instead of 100), each a full physics step.",
              } },
            { key = "physicsStepMode", label = "Vehicle physics: steps per frame",
              choices = { "fixed", "frame" },
              note = { fixed = "stock: whole fixed steps, the rest carried to the next frame", frame = "each frame's time in equal steps (the step size follows the frame rate)" },
              tip = "Frame steps make every frame advance the cars by exactly its own time, at the price of physics that change a little with the frame rate. For comparison.",
              why = {
                  cpu = "Above 100 fps every frame runs its own physics step, so the game thread runs more steps a second than stock's fixed 100.",
              } },
        },
    },
    {
        title = "Variable refresh rate (G-SYNC, FreeSync, ProMotion)",
        entries = {
            { key = "vrr", label = "Variable refresh rate",
              choices = { "auto", "on", "off" },
              note = { auto = "Linux: follows the display (the kernel's VRR_ENABLED); elsewhere off", on = "always act as if the display runs variable refresh", off = "stock frame pacing" },
              tip = "While the display runs variable refresh (G-SYNC, FreeSync, Adaptive-Sync), every frame is shown the moment it is ready, so how evenly frames arrive is exactly how smooth the game looks. With this on, the frame cap stays inside the display's range and each frame is held until a steady time after the game moment it shows. Linux reads the display state itself; on Windows and macOS choose On if your display runs variable refresh.",
              why = {
                  cpu = "With your cap Uncapped or above the display's maximum, the game caps itself just inside the range (157 fps at 165 Hz), so the game thread prepares fewer frames a second.",
                  gpu = "The GPU draws only the frames the display can show inside its range instead of every frame it can.",
              } },
            { key = "vrrCap", label = "Variable refresh: keep the frame rate inside the display's range",
              tip = "While variable refresh is on and your cap is Uncapped or above the display's maximum, the game caps itself a little below it (refresh - refresh^2/3600: 157 fps at 165 Hz, 224 at 240 Hz). Above the maximum a VRR display falls back to vsync (queued frames, more delay) or tears. Off = your own cap applies.",
              why = {
                  cpu = "Uncapped, the game thread prepares only the frames the display can show (157 a second at 165 Hz) instead of as many as it can.",
                  gpu = "The GPU draws those frames only, not the ones above the display's range.",
              } },
            { key = "presentPacing", label = "Even frame delivery (present pacing)",
              choices = { "auto", "gpu", "cpu", "gpufinish", "off" },
              note = { auto = "gpu wherever the graphics driver has timestamps (default); off with the macOS Metal present", gpu = "hold each frame to a steady time after its game moment, measured at GPU completion", cpu = "the same, measured when the CPU finished the frame (less even: the GPU may still be drawing)", gpufinish = "wait for the GPU, then hold (evenest, but ~9 ms more delay)", off = "stock: each frame goes out the moment it is drawn" },
              tip = "The game samples its time at an even rate, but each frame takes a different time to update and draw, so on a variable refresh display the gaps between frames on screen wander around the game-time steps: judder. With this on each frame is held until a steady time after its game moment. At a 100 fps cap on a 165 Hz G-SYNC display frames landed on average 3.1 ms off their game-time step (54 % of them more than 2 ms off); held, 0.95 ms (13 %), for about 5 ms more input delay. On a fixed-refresh display it evens out when frames leave the game: driving through Rosewood at 240 Hz, frames off their slot 28 to 11 %, frame-to-frame jitter 1.3 to 0.4 ms, for ~1.7 ms more delay on average (~2.6 ms typical, less than one 240 Hz frame).",
              why = {
                  render = "The render thread waits out each frame's hold before the swap (parked, the last part spun) and reads back a GPU timestamp query per frame, a few frames later.",
              } },
            { key = "frameQueue", label = "Frame queue: every frame on the cap's beat (adds input delay)",
              tip = "With a frame cap, every frame is held back until a fixed time after the moment of game time it shows, long enough that almost every frame is ready by then (the 99th percentile of the last 2 seconds, GPU time included). A frame whose update or drawing takes longer than usual then still reaches the screen on the beat instead of late, and the frames around it absorb it. The price is input delay: what you do reaches the screen about 3-7 ms later at a 120 fps cap, more in a big horde. Driving at 120 km/h with a 120 fps cap, frames within 9 ms rose from 92 % to 97.5 %, and to 99.5 % with the GPU's time counted (below). Only while you play a world with a frame cap: never uncapped, in the menus, the pause menu or the loading screen, with vsync on a fixed-refresh display (vsync already sets the beat), on macOS, or with the just-in-time input sleep. Replaces \"Even frame delivery\" while it acts. Off = present pacing as before.",
              why = {
                  render = "The render thread holds every capped frame's swap until its slot plus the holding time: parked, then spinning the last millisecond.",
              } },
            { key = "frameQueueGpu", label = "Frame queue: count the GPU's drawing time",
              tip = "The frame queue's holding time also covers how long the GPU still draws after the game has handed a frame over (measured with GPU timestamps a few frames later). Without it a frame whose GPU work runs long is swapped on the beat but shown late. Louisville horde at a 120 cap: frames within 9 ms 96.6 to 98.7 %, for 3-5 ms more input delay.",
              why = {
                  render = "A GPU timestamp query per frame, read back a few frames later, puts the GPU's drawing time into the holding time.",
              } },
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
              tip = "The performance overlay's GPU load and present pacing's GPU timing ask the graphics driver to time each frame. On a Mac's OpenGL 4.1 (Apple's OpenGL runs on top of Metal) that cost a third of the frame rate (106 to 68 fps on the test route, MacBook Pro M1 Pro), so it is off: the overlay shows no GPU load and present pacing measures on the CPU, as on OpenGL 2.1. On for a measurement that needs the GPU time. Applies on the next launch." },
            { key = "macPresent", label = "macOS: present through Metal (ProMotion timing)",
              choices = { "off", "on" },
              note = { on = "Apple silicon; needs fullscreen or borderless for the finer steps" },
              tip = "OpenGL frames on a Mac are shown on the 120 Hz grid, so a cap like 90 fps alternates 8 ms and 17 ms frames. Presented through Metal, a ProMotion or Adaptive-Sync display shows frames at any multiple of 4.17 ms in fullscreen: the frame cap is snapped to what the panel shows exactly (120, 80, 60, 48, 40 fps) and every frame is held on screen for exactly that long. It adds input delay: a frame is shown one panel step after the one before it.",
              why = {
                  render = "Each frame is handed from OpenGL to Metal by Java calls into the Objective-C runtime, and the render thread waits for a free Metal drawable.",
                  gpu = "An extra copy of every frame into a shared IOSurface, then a GPU upscale (MPS bilinear) from the game's resolution to the panel's native size.",
                  vram = "Three IOSurface textures at the game's resolution plus Metal drawables at the panel's native size (3024x1898 on the 14-inch MacBook Pro).",
              } },
            { key = "limiterSleep", label = "Frame limiter sleeps instead of spinning",
              tip = "While the game waits for its next frame under a frame cap, the stock limiter keeps one core busy spinning. This sleeps until 0.2 ms before the frame (1.5 ms on Windows, where a sleep wakes on the 1 ms timer tick) and spins only that: same frame timing. On by default except on Windows.",
              why = {
                  cpu = "The game thread sleeps through its wait instead of spinning: about half a core less at a 120 fps cap (VRR test: the game's CPU 406 to 340 % of a core, busiest core 46 to 36 %).",
              } },
        },
    },
    {
        title = "CPU cores and power (laptops and handhelds)",
        entries = {
            { key = "corePlacement", label = "Which cores the game's threads run on (hybrid CPUs)",
              choices = { "auto", "efficient", "performance", "off" },
              note = { auto = "background work on the efficient cores, the game and render threads there too while they keep the frame cap", efficient = "every thread on the efficient cores", performance = "game and render threads on the fast cores, the rest on the efficient ones", off = "stock: the operating system decides" },
              tip = "On CPUs with fast and efficient cores (the Ryzen AI 300 series' Zen 5 + Zen 5c, Intel's P + E core chips, Apple silicon), which cores the game's threads run on. Linux put the game's lighting, compiler, audio and loader threads on the fast cores as often as not. Auto (the default) keeps all of that on the efficient cores and moves the game and render threads to the fast cores only while they would otherwise miss the frame cap. Efficient puts every thread on the efficient cores; Performance keeps the game and render threads on the fast cores and the rest on the efficient ones. Off = stock: the operating system decides. Linux sets the cores directly; macOS gets the matching quality-of-service classes. Applies on the next launch.",
              why = {
                  cores = "The background threads run on the efficient cores, which cost a fraction of the power for the same work (one busy thread: 9.2 W on a Zen 5 core, 2.2 W on a Zen 5c). Ayaneo Flip at a 120 fps cap: 37 to about 30 W, same frame rate.",
              } },
            { key = "workerQosMac", label = "Priority of the game's helper threads (Mac)",
              choices = { "user-initiated", "utility", "user-interactive" },
              note = { ["user-initiated"] = "work the player waits for: keeps its cores when other programs are busy", utility = "background work: the efficient cores first, gives way to other programs", ["user-interactive"] = "the game and render threads' own class" },
              tip = "The macOS priority class of the helper threads that update and draw the zombies with the game thread every frame. At the old background class (utility) they lost their cores whenever a virtual machine, a build or Spotlight was busy beside the game, and the game slowed down while stock did not. User-initiated (the default) lets them keep their cores. No effect on Windows or Linux. Applies on the next launch.",
              why = {
                  cpu = "The game thread no longer waits at its per-frame joins for helpers that lost their cores: Louisville horde on an M1 Pro with four busy cores beside the game, 20.5 ms a frame instead of 34.7 (55 fps instead of 35).",
                  cores = "The helper threads hold their cores against other programs' work: under that load the game used 4.4-4.8 cores instead of 3.2. Quiet, the horde also ran a little faster (59.5 fps instead of 55.5).",
              } },
            { key = "gpuPstate", label = "AMD GPU clock level while playing (Linux)",
              choices = { "auto", "off", "standard", "min_sclk" },
              note = { auto = "the lowest fixed clock whose frame time fits the cap, automatic clocks otherwise", off = "stock: the driver's automatic clocks", standard = "a fixed ~1 GHz", min_sclk = "the lowest shader clock (~640 MHz)" },
              tip = "AMD Radeon on Linux only. At a frame cap the Radeon driver runs the GPU at its top clock and highest voltage for a few milliseconds a frame and then idles it. Auto (the default) measures the GPU time of every frame and holds the lowest fixed clock level that keeps the cap, stepping up the moment it does not. Standard holds about 1 GHz, min_sclk the lowest shader clock (about 640 MHz). The level applies to the whole GPU while the game runs and is released when it exits (a crash included). Off = stock: the driver's automatic clocks. Applies on the next launch.",
              why = {
                  gpu = "Most of the game's GPU work waits on memory, not on the clock: a Radeon 890M held its 120 fps cap at 640 MHz instead of bursts at 2.7-2.9 GHz, 1.2 W less at 1 GHz. Each frame's GPU work takes a little longer (3.4 to 4.2 ms at 1 GHz).",
              } },
            { key = "jitSteady", label = "Java compiler: no speculative recompiles",
              tip = "Java's optimising compiler leaves out the branches a method never took while it was being profiled; when the game later takes one (a new street, a different zombie state) the compiled code is thrown away and compiled again. With this on the compiler keeps every branch, and since 2026-10-15 it no longer bets on the types a method was seen with either (-XX:-UseTypeSpeculation: about 45 such bets failed on the Louisville route, one of them in the player's sight check at the horde's first sight). The game runs as fast. Written into the game's launcher file for the next launch; the uninstallers take it out again.",
              why = {
                  cores = "The compiler threads stop recompiling thrown-away code: a 100 s walk threw it away ~2,000 times and compiled 16,600 times; the compiler fell from 0.83 to 0.43 cores on the Ayaneo Flip, 1.4-2.1 W less.",
              } },
            { key = "lightingSyncPark", label = "Lighting thread sleeps between updates",
              tip = "The lighting thread updates 15 times a second (the Lighting FPS setting). Between updates it now sleeps straight to the next one instead of using LWJGL's timer. Applies on the next launch.",
              why = {
                  cores = "LWJGL's timer slept in 1 ms steps and then spun the last millisecond of every wait on a core; the lighting thread now stays asleep until its next update.",
              } },
        },
    },
    {
        title = "Java memory and garbage collector",
        entries = {
            { key = "gcHeap", label = "Java memory for the game (heap size)",
              choices = { "auto", "game", "4096", "6144", "8192", "12288", "16384", "24576", "32768", "40960", "49152", "57344", "65536" },
              note = { auto = "4 GB, 8 GB with 30 mods or more (default)", game = "the game's own: 3 GB", ["4096"] = "4 GB", ["6144"] = "6 GB", ["8192"] = "8 GB", ["12288"] = "12 GB", ["16384"] = "16 GB", ["24576"] = "24 GB", ["32768"] = "32 GB: Java's references double in size from here", ["40960"] = "40 GB", ["49152"] = "48 GB", ["57344"] = "56 GB", ["65536"] = "64 GB" },
              tip = "The most memory the game's Java objects may use. Auto (the default) gives the game 4 GB, or 8 GB when 30 or more mods are enabled (in the main menu or in the save you played last), never more than half your RAM; the game ships with 3 GB. When the heap is full Java stops the game to clean it up, and a big horde or a long mod list fills the game's 3 GB. With a big mod list pick 6 or 8 GB; the larger sizes are for very large mod lists (the most any test needed was 4.7 GB, with 132 mods). A size over half your RAM is lowered to half (64 GB needs a 128 GB machine). From 32 GB up Java stores every object reference in 8 bytes instead of 4, so a 32 GB heap holds less than a 31 GB one would and every collection walks more memory: go past 24 GB only if you need far more than that. Auto never lowers a larger heap you set yourself in ProjectZomboid64.json, and a size you edit there by hand stays yours until you pick another one here. Written into the game's launcher file for the next launch; the uninstallers put the game's own value back where the mod's value still stands.",
              why = {
                  cpu = "With room to spare Java never has to stop the game for a full clean-up: 3 GB ran 95 % full in the Louisville horde (2 GB froze it for 205 ms), and with 132 mods 4 GB froze it for 150-280 ms at a time where 8 GB never did.",
                  ram = "The game may take 4 GB instead of 3 (8 GB with 30+ mods), never more than half your RAM. It grows into what it gets: the horde settles at 3.6-3.9 GB, 132 mods used 4.2-6.2 GB of an 8 GB heap.",
              } },
            { key = "luaGcNoop", label = "Ignore mods' requests for a full memory clean-up",
              tip = "Some mods call Lua's collectgarbage() to \"free memory\". In this game that is a full stop-the-world collection of all of Java's memory, which Java does on its own anyway when it needs to. With this on (the default) such calls return at once; asking how much memory Lua uses still works. Off = stock.",
              why = {
                  cpu = "Each such call froze the game thread for 120-140 ms in a vanilla game (more with a bigger heap); Java's concurrent mode was worse, 270-320 ms, because the game thread waits for the whole cycle.",
              } },
            { key = "gcHeapFixed", label = "Reserve the whole heap from the start",
              tip = "The heap starts at its full size instead of growing as the game needs it (Java's -Xms equal to the heap size), so it is never resized while you play. Off by default. Applies on the next launch.",
              why = {
                  ram = "The whole heap is taken from the system at boot: 4,096 MB from the start, where a growing 4 GB heap held 2.9-3.6 GB on the spinning route and in the horde.",
              } },
            { key = "gcPreTouch", label = "Touch the heap's memory at boot",
              tip = "Java writes every page of the heap at boot (-XX:+AlwaysPreTouch) so the system never has to hand out a page while the game runs. Best together with \"Reserve the whole heap from the start\". Off by default. Applies on the next launch.",
              why = {
                  ram = "Every page of the heap is backed by real memory from boot, not only once the game first uses it.",
                  load = "Writing every page of the heap takes a moment at boot, longer with a bigger heap (not measured on its own).",
              } },
            { key = "gcMode", label = "Garbage collector",
              choices = { "g1", "auto", "stock" },
              note = { g1 = "G1 (default)", auto = "G1 on 4 cores or fewer, the game's own above", stock = "the game's own: ZGC" },
              tip = "The game ships with ZGC, which collects alongside the game on its own threads. G1 (the default) pauses the game for a few milliseconds a few times a minute instead. Auto: G1 on 4 cores or fewer, the game's own above. Applies on the next launch.",
              why = {
                  cpu = "In a horde ZGC's 3 GB ran 95 % full and fell behind: the game thread stalled up to 12.8 ms waiting for it, 66 fps against 72-90 with G1; uncapped on the spinning route ZGC was 3 % slower too.",
                  cores = "ZGC's collector threads run alongside the game all the time; on a 4-core laptop they took cycles straight from the game thread (with G1, no allocation stalls there).",
              } },
            { key = "gcPauseMs", label = "G1 pause target",
              choices = { "0", "5", "10", "25", "50", "100" }, note = { ["0"] = "auto: 5 ms with the tuned settings below, else G1's own 200 ms" },
              tip = "The longest pause G1 aims for. Auto (0, the default) is 5 ms together with \"G1 tuned for steady frames\" and G1's own 200 ms target without it. With 200 ms the pauses in a horde were 50-75 ms long; with 5 ms and the tuned settings they are typically 3-5 ms. Applies on the next launch." },
            { key = "gcG1Tuned", label = "G1 tuned for steady frames",
              tip = "With G1 (the default collector), the next launch also gets the settings measured on the Louisville horde: a small young generation collected often in short pauses, objects that survive moved straight to the old generation instead of being copied back and forth, the old generation cleaned up in many small steps, and a larger space for compiled code so it no longer starts extra collection cycles. Written into the game's launcher file only on the Java version it was tested with, and left out when you set any of these G1 options yourself; the uninstallers take them out again. Applies on the next launch.",
              why = {
                  cpu = "The game thread's collection pauses went from 7-12 ms to typically 3-5 ms at the same heap size.",
              } },
            { key = "gcSlack", label = "Garbage collection in the frame cap's spare time",
              tip = "With a frame cap, the collection of short-lived objects that would land in the middle of a frame is started a few frames early, at the end of a frame that has time to spare before the cap, so it no longer makes a frame late. It fills the rest of the young generation with throwaway memory to start the collection at that moment; the game itself is untouched. Needs G1 (the default); without a cap nothing changes.",
              why = {
                  cpu = "The few-millisecond pause moves into time the frame would spend waiting for the cap anyway: on the Louisville horde at 120 fps a scheduled collection cost the frames 1-3 ms instead of 10-14.",
              } },
            { key = "gcSlackQueue", label = "Spare-time garbage collection under the frame queue",
              choices = { "slack", "off", "swap", "hold" },
              note = { slack = "default: in the spare time, only while the held frame's swap is far enough away", off = "the spare-time rule as without the queue", swap = "from its own thread right after a held swap", hold = "from its own thread inside a long hold" },
              tip = "Where the collection above runs while the frame queue holds frames. In the game thread's spare time it could overlap the end of a hold and make that frame late (8 of 28 collections in a Louisville run); slack waits for a frame whose swap leaves room for the collection: 35 of 35 collections without a late frame." },
        },
    },
    {
        title = "Sound",
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
              tip = "How often per second the ambient-object sounds, the wall emitters, the room and weather sound parameters and the sound parameters of zombies making noise are refreshed. The sound engine applies them every 20 ms, so above ~60 fps stock recomputed them several times for every change you could hear. Below this rate nothing changes. 0 = every frame, stock.",
              why = {
                  cpu = "Above the rate, frames skip a refresh the sound engine would not have applied. Together with \"Skip silent zombies' sound emitters\" the game thread's sound work fell from 5.3 % to 2.8 % in a horde with a storm and alarms.",
              } },
            { key = "emitterIdleSkip", label = "Skip silent zombies' sound emitters",
              tip = "A zombie that is not making any sound skips its three sound emitters entirely; a flag set when it starts a sound brings it back. Same sounds.",
              why = {
                  cpu = "Stock checked every zombie's three emitters every frame: 2.8 % of the game thread in a horde of 2,000, 0.7 % with the skip.",
              } },
            { key = "worldSoundCleanupFast", label = "Cheap cleanup of expired noises",
              tip = "Every noise zombies can hear (alarms, shots, thumps) is listed in each loaded chunk it reaches and cleared out of those lists when it expires. The sweep now only runs after a frame in which a noise expired, and clears a list in one pass. Same lists, same order.",
              why = {
                  cpu = "Stock swept every loaded chunk's list each frame and shifted the list once per expired entry: 0.35 % of the game thread with alarms, thumps and gunfire, now 0.17 %.",
              } },
            { key = "hearingHoist", label = "Zombie hearing: fewer repeated lookups",
              tip = "When a zombie looks for the loudest noise around it, its own hearing and the square it stands on are now looked up once per search instead of once for every noise in the list. Same answer.",
              why = {
                  cpu = "The repeated lookups were 0.33 % of the game thread in the sound test scene (horde, storm, alarms, gunfire), now 0.10 %.",
              } },
        },
    },
    {
        title = "Multiplayer",
        entries = {
            { key = "luaChecksumExempt", label = "Leave the pzopt Lua files out of the server file check",
              tip = "When joining a server the game lists every Lua file under media/lua to the server; the pzopt files (this tab and its search index, the frame cap combo, the key binding, the update item) only exist on clients and a server without them refused the join with \"File doesn't exist on the server\". They are skipped like the game skips SandboxVars.lua. Applies on the next launch." },
        },
    },
    {
        title = "Updates",
        entries = {
            { key = "updateCheck", label = "Offer new releases in the main menu",
              tip = "Once per boot the main menu asks the GitHub releases (one request to api.github.com) whether a newer build for this game revision exists. The main menu's PZ OPTIMIZATION item then says \"Update available\" under it, and so does its screen's UPDATE entry: its dialog downloads the zip, replaces the installed files and asks to restart so the next launch loads them. Nothing is downloaded without that click. Applies on the next launch." },
            { key = "updatePrefetch", label = "Get an offered update ready in the background",
              tip = "When the check finds a newer build, the files that differ from the installed ones are fetched right away, so Update now only writes them and takes a few milliseconds instead of downloading and unpacking the whole 59 MB release. The whole zip is never downloaded without the click. Needs the first setting. Applies on the next launch." },
            { key = "updateFromWorkshop", label = "Update from the Steam Workshop copy",
              tip = "Subscribers already have every release: Steam downloads the Workshop item (the same files as the GitHub release zip) into the Steam library. The update check looks there first, before GitHub, and offers that copy when it is newer than the installed build: Update now then copies it from the disk, no download. GitHub is still asked afterwards and only replaces the offer with a newer release, so the check also works where GitHub cannot be reached. Needs the setting above. Applies on the next launch." },
        },
    },
    {
        title = "Render distance",
        entries = {
            { key = "chunkGridWidth", label = "Render distance (chunk grid width)",
              choices = { "0", "auto", "7", "9", "11", "13", "15", "19", "21", "23", "25", "27", "31" },
              note = { ["0"] = "vanilla", ["auto"] = "fill the screen at the widest zoom", ["7"] = "56 tiles", ["9"] = "72 tiles",
                       ["11"] = "88 tiles", ["13"] = "104 tiles", ["15"] = "120 tiles", ["19"] = "152 tiles, vanilla at 1080p and above",
                       ["21"] = "168 tiles, fills 4K", ["23"] = "184 tiles", ["25"] = "200 tiles, fills 5120x2160", ["27"] = "216 tiles",
                       ["31"] = "248 tiles" },
              tip = "How many chunks (8 tiles each) per side are loaded, simulated, lit and drawn around you. Vanilla picks it from the screen size but stops at 19 (152 tiles), sized for 1080p: on a 4K or ultrawide screen the world ends before the screen corners at the widest zooms. Auto picks the smallest grid that fills your screen at the widest zoom, never less than vanilla (21 at 3840x2160, 25 at 5120x2160, vanilla at 1080p). Smaller than vanilla helps CPU-limited setups (4 cores, heavy mod lists, NPC mods), with the world ending nearer the screen edge. Larger simulates zombies, vehicles and sounds further out and moves every resource below the other way. Only the grid size changes; zombie AI, streaming and culling are untouched.",
              why = {
                  cpu = "Chunk bakes, arriving chunks and the world's update scale with the grid's area; on 4-core machines it is the one lever (the low-end preset uses 9).",
                  cores = "The native lighting thread lights every loaded chunk: at 13 it used 44 % of a core instead of 70 %.",
                  gpu = "Fewer chunk levels to bake into textures and draw.",
                  vram = "Each loaded chunk level keeps its baked texture in GPU memory; fewer chunks, fewer textures.",
                  ram = "Fewer chunks, squares and objects held in memory.",
                  load = "Fewer chunks to load and light before the world appears (vanilla's 19 x 19 grid is 361).",
                  chunks = "A row of chunks you cross is shorter, so each arrival is less work: at 9 most of the +11 ms that late drive frames spent on arriving chunks (bakes, square loading, new-chunk lighting) went.",
              } },
            { key = "chunkGridFollowView", label = "Render distance follows the view upstairs",
              tip = "The camera looks at you from above, so on an upper floor the ground in the middle of the screen is 3 tiles north and 3 tiles west of you per level. With a render distance wider than vanilla the grid then moves with that ground point, so the screen corners stay filled upstairs as on the ground floor (25 at 5120x2160 up to level 8). You always stay at least as far from every grid edge as in vanilla; a grid no wider than vanilla never moves. Off = the grid stays centred on you and the top corners go dark from the second or third floor at the widest zoom. Single player only. Applies on the next launch.",
              why = {
                  chunks = "Upstairs the grid loads the chunks under the ground the camera shows, so the ones at the top screen corners are there instead of missing (at level 4 the corners lay 7 tiles outside the grid before).",
              } },
        },
    },
    {
        title = "Chunk streaming",
        entries = {
            { key = "parallel", label = "Chunk loading in parallel",
              tip = "Chunk recalculation (collision, pathfinding and sight for every square of a freshly loaded chunk) runs on a pool of worker threads instead of the one streamer thread. Chunks are handed to the game in the same order with the same result. Off = the stock single-threaded pass.",
              why = {
                  cores = "The recalculation, about 71 % of the streamer's work, runs on up to 4 workers, at about 0.4 ms more CPU per chunk (the workers share the caches).",
                  ram = "Each worker keeps its own square getter, and finished chunks wait in memory until it is their turn to be handed over.",
                  chunks = "With the streamer's wake-up, the median chunk is ready in 9.3 ms instead of 20 and p90 in 84 ms instead of 184 on the car-speed route (stock: 166 / 310 ms).",
              } },
            { key = "workers", label = "Chunk worker threads",
              choices = { "1", "2", "3", "4", "6", "8" },
              tip = "How many worker threads recalculate chunks while you play. Default 4 (1 on machines with 4 cores or fewer), never more than cores - 1.",
              why = {
                  cores = "Each worker is a thread recalculating chunks. On 4 cores or fewer they took time from the game thread (a 4-core laptop's drive ran at half the frame rate), hence 1 there.",
                  chunks = "A wider pool finishes a row of chunks sooner: p90 chunk latency 103 ms with 2 workers, 84 with 4; 8 bought nothing.",
              } },
            { key = "loadWorkers", label = "Chunk worker threads while a world loads",
              choices = { "1", "2", "4", "6", "8", "12" },
              tip = "How many threads recalculate the first chunks around you while a world loads (361 at the vanilla render distance); afterwards the pool shrinks back to \"Chunk worker threads\". Default: half your cores.",
              why = {
                  cores = "The initial chunk recalculation spreads over more threads only during the load (about 5.4 thread-seconds on 8 workers on the desktop).",
                  load = "The first chunks are ready sooner. More than half the cores bought nothing: with 15 workers each chunk took longer, as the threads contend until Java has compiled the code.",
              } },
            { key = "wake", label = "Wake the streamer on demand",
              tip = "The streamer thread starts on a chunk the moment one is asked for instead of checking its queue every 140 ms.",
              why = {
                  chunks = "A chunk no longer waits for the next 140 ms check: median chunk latency 166 to 20 ms on its own on the car-speed route, 8x.",
              } },
            { key = "chunkHandoffDivisor", label = "Chunk hand-off budget (queue divisor)",
              choices = { "0", "4", "8", "16" }, note = { ["0"] = "stock: up to 4 chunks a frame" },
              tip = "How many freshly loaded chunks join the world per frame: at most 1 + queued/divisor instead of stock's up to 4, so a chunk row arriving at once is spread over a few frames instead of one long one. 0 = stock.",
              why = {
                  cpu = "Each joining rolls the chunk's loot and erosion on the game thread; fewer per frame means no frame carries a whole row of them.",
                  chunks = "Chunks of a big row wait a few frames longer to appear. A higher divisor spreads them further and makes them wait longer.",
              } },
            { key = "handoffRecalcFast", label = "Chunk joining: border recalc without repeats",
              tip = "When a loaded chunk joins the world, its border squares are recalculated against the neighbours (collision, pathfinding, sight). The game recomputed each square's tile properties about fifteen times and its walkable-neighbour links dozens of times in that pass; now once each, with the same result.",
              why = {
                  cpu = "The border pass is about half of a chunk's joining time on the game thread: 207-299 us a chunk instead of 416-459 us in the Louisville horde.",
                  chunks = "Cheaper joinings fit a frame's spare time sooner, so waiting chunks join earlier.",
              } },
            { key = "propIdCache", label = "Tile property lookups by id",
              tip = "Questions like 'does this tile carry a fuel amount' looked the property up by name in a table every time; the answer's id is now kept per property type. Same answers.",
              why = {
                  cpu = "The name lookups were 0.8 % of the game thread in the Louisville horde and 10 % of a chunk's joining, and run while tiles are drawn too.",
              } },
            { key = "slackWork", label = "Deferred work in the frame cap's spare time",
              tip = "With a frame cap, the time left in a frame before the limiter waits is used for queued game-thread jobs: freshly loaded chunks joining the world, far loot rolls and far zombie spawns run one at a time while each one's learned cost still fits, instead of all at once in the frame they arrive. Without a cap nothing changes.",
              why = {
                  cpu = "Joinings of 1-50 ms each downtown and far loot rolls move into time the frame would wait anyway: the chunk map's p99 a frame fell from 2.8 to 0.2 ms in the Louisville horde.",
                  chunks = "A chunk may wait a few frames for spare time before it appears (at most \"Chunk joining: longest wait\", 6 frames by default).",
              } },
            { key = "chunkHandoffCost", label = "Chunk joining: cost estimate",
              choices = { "chunk", "square" }, note = { ["chunk"] = "default", ["square"] = "until 2026-10-15" },
              tip = "How the spare-time scheduler guesses whether the next chunk's joining fits the time left in a frame. chunk (the default): the running mean of whole joinings. square (until 2026-10-15): a learned cost per square, which one heavy chunk kept pessimistic for a long time.",
              why = {
                  cpu = "With the per-square guess chunks waited, the queue grew, and a few frames a run then joined the backlog at once, each over 4 ms; the whole-chunk mean keeps them flowing into spare time.",
                  chunks = "Chunks no longer wait behind one heavy chunk's estimate (up to 600 frames before).",
              } },
            { key = "chunkHandoffSlackMaxWait", label = "Chunk joining: longest wait for spare time (frames)",
              choices = { "0", "6", "30" }, note = { ["0"] = "600 frames, until 2026-10-15", ["6"] = "default" },
              tip = "A loaded chunk that does not fit a frame's spare time joins after this many frames anyway, in a light frame, so a queue never builds behind one heavy chunk. A world load or a teleport keeps the stock count. 0 = 600 frames, as until 2026-10-15.",
              why = {
                  cpu = "No backlog builds up, so no frame later has to join many waiting chunks at once (such frames took over 4 ms).",
                  chunks = "A chunk joins within 6 frames instead of waiting up to 600 for spare time. Lower joins sooner, more often in a busy frame.",
              } },
            { key = "chunkHandoffForceAnyFrame", label = "Chunk joining: an overdue chunk joins in the next spare time",
              tip = "A chunk past its longest wait joins in the next frame's spare time, light frame or not (still one such chunk a frame).",
              why = {
                  chunks = "An overdue chunk no longer waits on for a light frame, which in a busy scene may not come for a while.",
              } },
            { key = "chunkHandoffFrameBudgetUs", label = "Chunk joining: time a frame may spend on a backlog (us)",
              choices = { "0", "1000", "1500", "3000" }, note = { ["0"] = "stock: the whole count", ["1500"] = "default" },
              tip = "When chunks queue up while you move, a frame joins them only until this much time is used (at least one); the rest join in the next frames' spare time. A world load or a teleport keeps the stock count. 0 = stock: the whole count.",
              why = {
                  cpu = "A backlog no longer lands in one frame: such frames took over 4 ms of the game thread, now about 1.5 ms. Lower spreads it further, higher clears the queue sooner.",
                  chunks = "The rest of a backlog joins over the next frames instead of all in one.",
              } },
            { key = "hotsaveStaged", label = "Staged hot save",
              tip = "The periodic hot save writes the meta grid and the other systems one part per streamer update instead of all in one frame. Off by default: the parts of one save are then written at different moments, so the meta-grid files could disagree with each other.",
              why = {
                  cpu = "The save's serialisation, a 2-5 ms game-thread stall while driving, is split over nine streamer updates, so no single frame carries all of it.",
              } },
            { key = "hotsaveIntervalSec", label = "Seconds between hot saves",
              choices = { "0", "5", "15", "30", "60", "120" }, note = { ["0"] = "every drain, stock" },
              tip = "Minimum seconds between the saves of the meta grid, game time, world map and entities that follow every drained chunk-save queue. Default 30; 0 = stock, after every drain.",
              why = {
                  cpu = "Stock saved after every drained chunk-save queue, a 2-5 ms game-thread stall about twice a second while driving; at 30 s it is rare. Higher saves less often.",
                  disk = "Those files are rewritten every 30 s at most instead of about twice a second while driving.",
              } },
        },
    },
    {
        title = "Mod Lua while chunks arrive",
        entries = {
            { key = "luaLoadStream", label = "Chunk joining in time slices",
              tip = "A chunk joining the world runs every mod's LoadGridsquare handler on each of its squares, then the loot and the LoadChunk handlers. Stock does a chunk's whole joining in one frame; this runs it square by square over the next frames, in the stock order, a little each frame and more while chunks queue up. The chunk is not drawn until all of it has run. Off = stock.",
              why = {
                  cpu = "No frame carries a whole chunk's mod handlers: on the 54-mod list's spinning route frames over twice the median fell from 118-147 to 40-43 (those by LoadGridsquare / LoadChunk 46-70 to 5-6), p99 17.3-20.6 to 13.1-14.8 ms.",
                  chunks = "Chunks are ready to draw sooner, as none waits behind the stock pace: queued to drawable p99 0.9 s to 0.3 s on the spin, 0.3 to 0.1 s on the drive.",
              } },
            { key = "luaLoadBudgetUs", label = "Chunk joining in time slices: least time a frame (us)",
              choices = { "300", "1000", "2000" }, note = { ["1000"] = "default" },
              tip = "The least a frame spends on a joining chunk's slices; while chunks queue up it spends more, to keep up with them. Lower keeps each frame lighter and readies chunks later; 2000 cost tails on a light mod list.",
              why = {} },
            { key = "luaLoadNearestFirst", label = "Chunk joining in time slices: nearest first",
              tip = "The next chunk to join is the queued one nearest the point you reach in 0.75 s instead of the oldest (a chunk that waited over a second still goes first). Off by default: it changes the order mods see the chunks in (the order check found mods keeping lists and flags across chunks), for no measured gain on the routes tried.",
              why = {
                  chunks = "The chunks ahead of you join before the ones behind or far to the side.",
              } },
            { key = "luaLoadChunkSplit", label = "Chunk joining in time slices: LoadChunk handlers one at a time",
              tip = "A chunk's LoadChunk handlers (mods that look at the whole chunk at once) run one per slice instead of all in the slice that ends the chunk, still before it is drawn.",
              why = {
                  cpu = "A chunk's last slice no longer carries every LoadChunk handler at once.",
              } },
            { key = "luaLoadSlack", label = "Chunk joining in time slices: in the frame cap's spare time",
              tip = "With a frame cap, slices also run in the time the frame would wait anyway, while their learned cost fits.",
              why = {
                  cpu = "Joining work moves into time the frame waits anyway: with it off on the drive frames over twice the median went 26 to 56.",
              } },
            { key = "luaLoadPrefilter", label = "Mods' square handlers: skip the proven no-ops",
              tip = "A mod's LoadGridsquare handler is left out on a square when it already ran on an identical square (the same level, room, objects, sprites, mod data) without changing anything, while reading nothing but those and its own Lua values, and none of what it read has changed since. Same result by construction; one skip in 32 still runs and is checked.",
              why = {
                  cpu = "Handlers that do nothing on most squares cost nothing there: LoadGridsquare time 21 % lower on the 54-mod drive, 47 % on a 650-mod list (29.0 to 15.3 ms a frame).",
              } },
            { key = "luaLoadSigProject", label = "Mods' square handlers: compare only what a handler reads",
              tip = "With the skipping, two squares count as identical for a handler when the parts it ever read are equal (its signature leaves out the rest), so more squares match.",
              why = {
                  cpu = "Fewer signatures to learn and hash per handler, so more skips and less bookkeeping.",
              } },
            { key = "luaLoadBatch", label = "Mods' square handlers: handler by handler",
              tip = "A chunk's LoadGridsquare handlers run one handler over all its squares, then the next, instead of every handler on each square in turn. It changes what mods see: the order check found Fence Sheets reading squares that stock had not loaded yet when it ran (213,000 calls on the 54-mod spin) and Total Weight Rebalance and Fence Sheets touching the same squares in the other order. Off by default, no measured gain.",
              why = {} },
            { key = "luaLoadNoopSkip", label = "Mods' square handlers: skip learned no-ops (risky)",
              tip = "Like the proven skipping, but also for handlers that read more than the square's signature (coordinates, the cell, other squares), after four calls that did nothing. A handler that acts on one particular square would then be skipped there: about 1 call in 22,000 on a 650-mod list. Off by default.",
              why = {
                  cpu = "More handlers skipped than the proven skipping can.",
              } },
            { key = "mapObjectsFast", label = "Map object callbacks: only where one can run",
              tip = "The game's sprite callbacks (OnNewWithSprite / OnLoadWithSprite) are looked up for every object of every joining square; this asks first whether any object of the square has a registered sprite. Same result.",
              why = {} },
        },
    },
    {
        title = "Boot: threads and caches",
        entries = {
            { key = "fmodAsync", label = "Start audio on a boot thread",
              tip = "The sound system and its 12 sound banks start on a thread of their own during boot, alongside the rest of the start-up, instead of first thing on the main thread.",
              why = {
                  cores = "FMOD's start-up (about 1.6 s) runs on a boot thread; the main thread waited 0 s for it.",
                  load = "Launch to main menu 6.57 to 5.20 s on the desktop.",
              } },
            { key = "preloadAnimSets", label = "Parse animation sets on a boot thread",
              tip = "The player and zombie animation sets (XML) are read on a boot thread while the menu loads, instead of on the loader thread after you press Continue.",
              why = {
                  cores = "The 1.1 s parse runs on a boot thread.",
                  load = "Creating the player in the world load fell from 0.35 to 0.05 s: Continue to world 5.11 to 4.54 s on the desktop.",
              } },
            { key = "bootPump", label = "Decode textures during boot",
              tip = "A thread drives the game's file loading during boot, so texture pages and animations decode before the main menu instead of only once the menu starts drawing frames.",
              why = {
                  cores = "The file threads decode during boot instead of sitting idle for its first 4 s.",
                  ram = "Decoded textures and animations are in memory from boot on, earlier than in stock.",
                  load = "Less is left to decode at Continue: the world load's wait for its files fell from 0.85 to 0.36 s, and to none with \"Register models early\".",
              } },
            { key = "bootFileThreads", label = "File threads during boot",
              choices = { "2", "4", "6", "8", "10", "12" },
              tip = "How many file threads decode while the boot pump runs; the pool shrinks to \"File threads in game\" at the world load. Default: cores - 6, at least 4.",
              why = {
                  cores = "More threads decode textures and animations at once during boot (10 by default on 16 threads).",
                  load = "Boot decoding finishes sooner. Too many slow the boot's Lua: with 16 threads all busy the boot took 7.55 s instead of about 6.",
              } },
            { key = "earlyModels", label = "Register models early",
              tip = "Models and the animation queue register right after the scripts load instead of later in boot, so the boot pump has about 2 s more for the 2,209 animations.",
              why = {
                  load = "The animations are imported by the main menu, so the world load no longer waits for them (its file wait 0.36 to 0.01 s).",
              } },
            { key = "luaPrecompile", label = "Precompile Lua on a pool",
              tip = "Every Lua file is compiled on a thread pool during boot; the game takes the compiled code from that cache instead of compiling each file when it first runs it (at boot, and the server Lua and map zones at Continue).",
              why = {
                  cores = "1,419 Lua files compile on up to 14 threads during boot.",
                  load = "Boot 6.04 to 5.58 s on the desktop; at Continue the server Lua (0.9 s of compiling) and the map zones come from the cache.",
              } },
            { key = "animClipCache", label = "Cache animation clips",
              tip = "Imported animation clips are written under Zomboid/pzopt/anims and read from there on later boots. The first boot after an update or a cache wipe imports them as stock does and fills the cache.",
              why = {
                  cores = "Importing the 2,209 .X animation files cost the file threads 14-17 thread-seconds a boot; reading the cache costs 2.9.",
                  disk = "About 89 MB of clips (2,161 files) under Zomboid/pzopt/anims, written on the first boot.",
                  load = "Continue to world 4.89 to 4.02 s on the desktop once the cache is warm.",
              } },
            { key = "packIndex", label = "Index texture packs",
              tip = "Texture packs keep their page offsets under Zomboid/pzopt/packs, so the reader jumps to each page instead of scanning the packs at every boot.",
              why = {
                  disk = "Seven small index files under Zomboid/pzopt/packs, written on the first boot.",
                  load = "The byte-by-byte scan of 526 MB of packs is gone from every later boot (the pack headers were 0.64 s of the boot's main thread).",
              } },
        },
    },
    {
        title = "Boot: parsers",
        entries = {
            { key = "scriptParserFast", label = "Linear script parser",
              tip = "Script comments are stripped in one pass and tokens parse without copying the rest of the file each time; identical output.",
              why = {
                  ram = "Stock rewrote the multi-MB script text once per comment and copied the rest of it once per token; those throwaway copies are gone.",
                  load = "The stock passes cost 1.8 s of boot (1.56 s of it stripping comments): boot 7.35 to 6.57 s on the desktop.",
              } },
            { key = "itemParamSwitch", label = "Item parameter switch",
              tip = "Item script fields dispatch through a switch instead of a chain of 361 text comparisons per parameter.",
              why = {
                  load = "The comparison chain was about 0.9 s of the boot's main thread.",
              } },
        },
    },
    {
        title = "World load: file system and decoding",
        entries = {
            { key = "fileThreads", label = "File threads in game",
              choices = { "1", "2", "4", "6", "8", "12" },
              tip = "Worker threads of the game's file loading (texture decoding, model and animation import, depth maps), in game and during the world load. Stock: 2 on up to 4 cores, else 4; default here: half your cores, at least 4.",
              why = {
                  cores = "Texture, model and depth-map decoding spread over more threads (8 on the desktop's 16).",
                  load = "The world load no longer waits for decoded files (3.5 s in stock). Many more threads slowed the game's own map loaders (1.5 to 4.0 s beside 14 file threads), hence half the cores.",
              } },
            { key = "fileInflight", label = "File tasks in flight",
              choices = { "8", "16", "32", "64" }, note = { ["16"] = "stock" },
              tip = "How many file tasks are handed to the file threads at once. Default: 4 per file thread; stock 16.",
              why = {
                  ram = "More files can be read and decoded at the same time, each holding its buffers.",
                  load = "The file threads always have a next task waiting, so none sits idle during the load.",
              } },
            { key = "textureBufferMb", label = "Decoded texture buffer (MB)",
              choices = { "50", "100", "256" }, note = { ["50"] = "stock" },
              tip = "Decoded texture bytes that may wait for the render thread before the decoders pause. 50 = stock and the default.",
              why = {
                  render = "Larger values pile uploads onto one frame: with 256 MB one frame a few seconds into the world took 5.05 s.",
                  ram = "Up to this many MB of decoded textures wait in memory for their upload (256 instead of 50).",
                  load = "The decoders pause less often while the loading screen is up.",
              } },
            { key = "parallelDepthMaps", label = "Decode depth maps in parallel",
              tip = "The 218 depth-map tilesets (how deep each pixel of a tile lies, for drawing order) decode at the same time instead of one at a time under one lock.",
              why = {
                  cores = "The decodes spread over the file threads instead of queuing behind one lock.",
                  load = "The depth maps are ready earlier in the world load.",
              } },
            { key = "loaderCpuFixes", label = "Loader thread algorithmic fixes",
              tip = "Lot headers, vehicle zones and room ids resolve once per cell instead of repeatedly while a world loads; identical results.",
              why = {
                  cores = "The loader thread stops repeating work: vehicle zones checked once (11 checks over 9,690 zones were 0.6 s), lot headers looked up per cell instead of per chunk, rooms indexed instead of searched.",
                  load = "Collision data 0.52 to 0.13 s and the map-zone phase 1.52 to 1.29 s of the world load on the desktop.",
              } },
            { key = "shaderCache", label = "Reuse model shaders",
              tip = "A model takes a shader an earlier model already created instead of asking the render thread for its own and waiting a loading-screen frame for it.",
              why = {
                  render = "The render thread builds each shader once instead of once per model.",
                  load = "On a laptop whose loading-screen frame took 220 ms, 73 animal models waited 16.5 s of a 31 s load; now only the first model per shader waits.",
              } },
            { key = "shaderWarmup", label = "Build shaders ahead of play",
              tip = "Shaders that were built the first time something needed them, in the middle of play, are built ahead: every model shader the game's scripts name during the loading screen, and the Visuals' own programs (foliage sway, god rays, wet blood) as the world appears. Matters most on the first launch after an update, when the graphics driver has none of them cached. Applies on the next launch.",
              why = {
                  render = "The builds happen in the loading screen instead of mid-play, where the first burnt car's vehicle program took the render thread 428 ms and a foliage-sway variant 144 ms.",
              } },
            { key = "mipmapArrays", label = "Row-based texture mipmaps",
              tip = "Texture mipmaps and alpha premultiply are built row by row on byte arrays; same pixels as stock.",
              why = {
                  cores = "The file threads build mip levels with bulk row copies instead of byte-by-byte buffer access, a few percent faster (a 2048 x 2048 texture's first level 13.5 ms instead of 14.3).",
              } },
            { key = "texCompress", label = "Texture compression: who compresses",
              choices = { "auto", "worker", "driver" },
              note = { auto = "the GPU where it runs compute shaders (OpenGL 4.3), otherwise the file threads", worker = "the file threads on the CPU, never the GPU", ["driver"] = "stock: the graphics driver, inside each upload on the render thread" },
              tip = "Only matters with the game's own \"Texture compression\" option on (the low-end preset and the Steam Deck defaults). Stock hands every texture to the graphics driver uncompressed and lets it compress; Mesa (Linux AMD and Intel) does that on the CPU inside each upload, so after boot the main menu stutters for several seconds. Auto: the file threads prepare each texture and the GPU builds its mip levels and compresses it where OpenGL 4.3 compute runs, else as Worker. Worker: the file threads also compress them, the GPU does no compression work. Driver = stock. Applies on the next launch.",
              why = {
                  render = "The render thread no longer compresses inside each upload: about 26 s a boot on a Radeon 890M in stock, about 0.1 s with Auto.",
                  cores = "The file threads copy each texture into a 128 MB staging buffer (Auto), or compress it on the CPU (Worker: about 46 s of file-thread time a boot on the Ayaneo Flip).",
                  gpu = "With Auto, compute shaders build each texture's mip levels and its BC3 compression on the GPU while the textures load.",
                  load = "The main menu runs at 60 fps from 4 s after boot instead of 25-34 fps for 10-16 s.",
              } },
            { key = "tileDefPreload", label = "Build tile definitions during boot",
              tip = "The ~60k tile sprites are built on a thread while the main menu loads; Continue only binds their textures instead of parsing the tile files again.",
              why = {
                  cores = "Parsing the tile-definition files and building ~61k sprites runs on a boot thread.",
                  load = "The tile-definition step of the world load fell from 0.76 to about 0.2 s.",
              } },
            { key = "skipIdChecks", label = "Skip room-id consistency checks",
              tip = "A log-only check over every building and room of the map (six walks per load) runs in debug mode only; the game never uses its result.",
              why = {
                  load = "The six walks over every building and room cost about 0.37 s of CPU per world load.",
              } },
            { key = "voronoiFast", label = "Fast zombie-density noise",
              tip = "The zombie-density noise of every map cell is computed per sector instead of per sample; identical values.",
              why = {
                  cores = "The noise was 89 % of the eight map-loader threads' work.",
                  load = "The map loaders finish in 0.7 s instead of 1.5 s, so the world load no longer waits for them.",
              } },
            { key = "earlyTilePacks", label = "Load tile textures earlier in boot",
              tip = "The tile texture packs and depth maps start decoding before the boot Lua load instead of after it, so the world load does not wait for them.",
              why = {
                  load = "Removes the 0.2-0.6 s wait for tile textures at the end of a warm world load.",
              } },
            { key = "aotCache", label = "Warm-start cache (Java AOT)",
              tip = "The game records a Java warm-start cache (AOT: the classes it loads and how its methods ran) on one launch and starts from it afterwards, so the menu and every world load begin with warm code. Changes the launcher config (backed up once); off puts it back.",
              why = {
                  disk = "A cache of about 127 MB is written at the end of the recording launch.",
                  load = "Continue to world 2.64 to 2.02 s and launch to main menu about 0.6 s shorter on the desktop.",
              } },
            { key = "aotNoIndy", label = "Warm-start cache: save it without lambda linking (makes it work)",
              tip = "With the game's Java (Zulu 25.0.1) the warm-start cache was never written: Java crashed while saving it at the end of the recording launch (an hs_err file in the game folder after every second quit), so the next launch found no cache and recorded again, forever. Saved without Java's pre-linked lambda calls (-XX:-AOTInvokeDynamicLinking) the cache is written and keeps the loaded classes and all method profiles. Needs the warm-start cache on. Written into the game's launcher file for the next launch; the uninstallers take it out again." },
        },
    },
    {
        title = "World load: loading screen",
        entries = {
            { key = "noLoadFade", label = "Skip the loading-screen fade",
              tip = "The main menu and the loading screen no longer fade to black before the world's own fade-in.",
              why = {
                  load = "The loading screen's 350 ms fade and the main menu's 250 ms fade are skipped: leaving the menu takes 0.15 s instead of 0.51.",
              } },
            { key = "noIntroWait", label = "Click-to-start as soon as a new game is loaded",
              tip = "A new game shows click-to-start as soon as it is loaded instead of after the 33 s intro text; the lines still play behind it.",
              why = {
                  load = "Up to 33 s less before you can start a new game.",
              } },
            { key = "noClickToStart", label = "Enter the world without click-to-start",
              tip = "The loading screen goes straight into the world when loading is done instead of waiting for a click or A.",
              why = {
                  load = "No wait for your click once the world is ready: you are in as soon as it is loaded.",
              } },
            { key = "noLoadingScreen", label = "No loading screen",
              tip = "Single player: a plain black frame while the world loads and no fade from black into the world; with \"Show the last view while loading\" that view instead of the loading screen. The stock screen still appears for errors, a world-version dialog, a map download or a save conversion.",
              why = {
                  load = "The fade from black played in full before the world appeared (0.36 s of renders and sleeps); now it is skipped.",
              } },
            { key = "resumeShot", label = "Show the last view while loading",
              tip = "Quitting keeps the view around you (see Last view detail); Continue shows it while loading, its chunks appearing the way the world loads, and the world builds over it. Saves without it show the normal loading screen.",
              why = {
                  disk = "Each quit writes a 1920-pixel-wide JPEG and its position file into the save folder.",
                  load = "The load takes as long, but you see the place you left from its first frame instead of a loading screen.",
              } },
            { key = "resumeShotDetail", label = "Last view detail",
              choices = { "floors", "buildings", "world", "full" },
              tip = "What the last view keeps when you quit: floors = the ground only; buildings = floors, walls, doors and furniture; world = everything that does not move, trees included; full = the frame as you left it, zombies and cars too. Taken at the next quit." },
            { key = "centerFirstLoad", label = "Load the world from the centre outwards",
              tip = "The chunks around you load and light first and the world appears as soon as they are ready; the rest streams in around you.",
              why = {
                  load = "You enter once the chunks within 3 of you are loaded and lit instead of all of them: the world shows 3.02 s after Continue instead of 3.51 on the desktop.",
                  chunks = "The first chunks load and light nearest you first instead of in no particular order (stock lit them in rows, so the world appeared in diagonal bands).",
              } },
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
              tip = "The world is rendered at a fraction of the screen size (see \"Upscaler quality\") and scaled back up before the UI, text and the stock screen shader, which stay at full resolution. GPU-bound scenes (fog, storms, big towns, 4K, laptops) gain roughly the pixel ratio; scenes held back by the CPU gain little. fsr1 is a sharp spatial upscaler that works on every GPU; taau gathers detail over frames on any GPU; dlss accumulates detail over frames on an RTX card; bicubic is the plain stretch. Off = stock: the world at the screen size.",
              why = {
                  gpu = "The world's per-pixel work (fog, rain, water, lights) follows the render size: in a static storm with the stock fog at 5K, FSR 1.0 at quality ran 54 % faster than off, bicubic 73 %. On a fast drive the gain is small: chunk bakes do not shrink.",
                  vram = "The smaller world picture and the upscaler's own images sit beside the screen-sized one; taau and DLSS also keep a history picture at the output size.",
              } },
            { key = "upscalerQuality", label = "Upscaler quality (render size)",
              choices = { "quality", "balanced", "performance", "ultra", "native" },
              note = { quality = "67 % per axis (44 % of the pixels)", balanced = "58 %", performance = "50 % (a quarter of the pixels)", ultra = "33 %", native = "100 % (dlss: DLAA anti-aliasing only)" },
              tip = "The render size per axis: quality 67 %, balanced 58 %, performance 50 %, ultra 33 %, native 100 % (with dlss: its anti-aliasing only). The smaller, the softer the picture.",
              why = {
                  gpu = "Fewer world pixels to shade: performance draws a quarter of them, quality 44 %. DLSS's own cost follows its output size instead, so with dlss the levels move the frame rate by about 10 % only.",
              } },
            { key = "upscalerScalePct", label = "Upscaler render scale of your own (%)",
              choices = { "0", "40", "50", "60", "67", "75", "85" }, note = { ["0"] = "use the quality preset" },
              tip = "A render scale of your own in percent per axis instead of the quality preset (10-100).",
              why = {
                  gpu = "A lower percentage shades fewer world pixels (they fall with its square: 50 is a quarter) for a softer picture; 0 follows the quality preset.",
              } },
            { key = "fsrSharpnessPct", label = "Upscaler, AMD FSR 1.0: sharpening (%)",
              choices = { "0", "40", "60", "80", "100" }, note = { ["0"] = "none", ["100"] = "the sharpest" },
              tip = "The contrast-adaptive sharpening (RCAS) after the FSR 1.0 upsample; 80 is AMD's usual default." },
            { key = "dlssPreset", label = "Upscaler, NVIDIA DLSS: model preset",
              choices = { "e", "default", "f", "k", "j", "m", "l" },
              note = { default = "NVIDIA's choice: the transformer models K / M / L", f = "older convolutional model: ~1.5 ms cheaper a frame at 4K, a little softer", e = "recommended: the older convolutional model, half the cost of K and no trails in this game", k = "transformer, best quality", j = "transformer, less ghosting, more flicker", m = "transformer, the performance-mode default", l = "transformer, the ultra-performance default" },
              tip = "Which DLSS network runs. The transformer models (DLSS 4: k, j, m, l and NVIDIA's default) reconstruct the most detail; the older convolutional models e and f are a little softer, and e leaves no trails in this game (recommended). m and l are too heavy for a 4K screen.",
              why = {
                  gpu = "The network runs on the GPU every frame: at 5120x2160 on an RTX 4090 preset E takes 0.64-0.74 ms, the transformer K 1.54 ms (the 120 km/h drive at 327 fps with E, 249 with K). At 1440p and below a third or less.",
              } },
            { key = "dlssOutputPct", label = "Upscaler, NVIDIA DLSS: output size (%)",
              choices = { "67", "75", "0" },
              note = { ["0"] = "the screen size: full DLSS super resolution", ["75"] = "DLSS writes 75 % of the screen, the finish below does the rest", ["67"] = "recommended: DLSS anti-aliases at the render size (quality), the finish does the upscale" },
              tip = "The size DLSS writes. Writing less than the screen and letting a cheap filter (below) finish the upscale is what makes DLSS faster than no upscaler on a 4K screen, and with the sharpen finish the picture is sharper than full-size DLSS with the default model. At DLAA (native) the output is always the screen size.",
              why = {
                  gpu = "DLSS costs time per pixel it writes, not per pixel it reads: in a heavy storm with fog at 5K on an RTX 4090, DLSS E at quality gained 22 % over no upscaler with a 67 % output, 17 % at 75 %, 10 % at the full size.",
              } },
            { key = "dlssOutputFilter", label = "Upscaler, NVIDIA DLSS: output finish",
              choices = { "rcas", "bicubic", "fsr1" },
              note = { rcas = "recommended: FSR 1.0's sharpen at the DLSS size, then the bicubic", bicubic = "the stock screen shader's bicubic, free but soft", fsr1 = "FSR 1.0 EASU + RCAS: the sharpest, ~0.2 ms a frame at 4K" },
              tip = "How a DLSS output smaller than the screen (the size above) is brought to the screen.",
              why = {
                  gpu = "rcas is one small sharpen pass at the DLSS output's size (the stock-fog scene ~3 % slower than the plain bicubic finish); fsr1 adds a full-screen EASU pass, about 0.2 ms a frame at 4K; bicubic is free.",
              } },
            { key = "dlssSharpen", label = "Upscaler, NVIDIA DLSS: sharpening",
              tip = "Asks DLSS for its mild extra sharpening pass on top of the super resolution; off is the plain reconstruction." },
            { key = "upscalerObjectMv", label = "Upscaler, temporal: character and vehicle motion vectors",
              tip = "The temporal upscalers get each character's and vehicle's own motion on top of the camera's, so moving zombies and cars do not ghost or smear. Off = camera motion only (an A/B)." },
            { key = "dlssWaterCurrent", label = "Upscaler, NVIDIA DLSS: water from the current frame",
              tip = "DLSS blends each frame with the previous ones; the water's ripples move in place with no motion vectors, so that blend slowed them to half speed. On: the water pixels show the frame just drawn, so rivers and lakes flow like the stock game. Off = DLSS's own image everywhere (an A/B).",
              why = {
                  gpu = "One more pass each frame mixes the water pixels of the frame just drawn into DLSS's picture, masked to the water (rivers, lakes) on screen.",
                  vram = "Two textures at the output size, made the first time water is on screen, hold the mixed picture and the last frame's water.",
              } },
            { key = "dlssWaterHistoryPct", label = "Upscaler, NVIDIA DLSS: water smoothing (%)",
              choices = { "0", "25", "50", "60", "75" }, note = { ["0"] = "the current frame alone", ["60"] = "recommended" },
              tip = "With the water from the current frame, how much of the previous frame's water is mixed in. DLSS shifts each frame by a fraction of a pixel; one frame of history evens that out so the ripples do not shimmer, without slowing them." },
        },
    },
    {
        title = "Dynamic resolution (hold the frame rate cap)", clip = "upscale",
        entries = {
            { key = "dynRes", label = "Dynamic resolution",
              tip = "The world's render size follows the GPU's time per frame so it fits your frame rate cap: when a scene gets heavy (fog, storms, big towns, a laptop) the world renders smaller for as long as it needs to and grows back when the GPU has room again. The UI and text stay sharp. Only lowers the resolution when the picture's pixels are what costs the time (chunk loading spikes are left alone: a smaller picture would not help them). Uses the upscaler above; with the upscaler off it uses the choice below. Uncapped: stays at the highest size.",
              why = {
                  gpu = "In a GPU-bound scene the world renders smaller until the GPU fits the cap: on the desktop under a heavy per-pixel load late frames fell from 28 % to 3-8 % at about 60 % size.",
                  vram = "With the upscaler off the temporal upscaler (taau) keeps a 16-bit history picture at the screen size.",
              } },
            { key = "dynResUpscaler", label = "Dynamic resolution: upscaler when the upscaler is off",
              choices = { "taau", "fsr1", "bicubic" },
              note = { taau = "temporal: the steadiest picture while the size changes", fsr1 = "AMD FSR 1.0: sharp, a size change can show", bicubic = "the stock filter" },
              tip = "How the smaller world picture is brought to the screen when \"Upscaler\" is off. The temporal upscaler builds the picture over several frames at the screen size, so a change of the render size is hard to see." },
            { key = "dynResTargetPct", label = "Dynamic resolution: GPU budget (% of the frame time)",
              choices = { "80", "85", "90", "95" }, note = { ["90"] = "default" },
              tip = "How much of each frame's time (1 / your cap) the GPU may use; the rest is headroom for the frame-to-frame noise. Lower holds the cap more firmly at a lower resolution.",
              why = {
                  gpu = "A lower share leaves the GPU more idle time each frame by rendering the world smaller; a higher one keeps more resolution and misses the cap more often.",
              } },
            { key = "dynResMinPct", label = "Dynamic resolution: lowest render size (%)",
              choices = { "33", "40", "50", "60", "67", "75" }, note = { ["50"] = "default (a quarter of the pixels)" },
              tip = "The smallest render size per axis it may go down to.",
              why = {
                  gpu = "A lower floor lets a heavy scene shed more pixels (50 % is a quarter of them); a higher one keeps the picture sharper and holds the cap less firmly.",
              } },
            { key = "dynResMaxPct", label = "Dynamic resolution: highest render size (%)",
              choices = { "100", "90", "85", "75" }, note = { ["100"] = "native" },
              tip = "The largest render size per axis.",
              why = {
                  gpu = "Below 100 the world never renders at full size, so the GPU shades fewer pixels even in light scenes, for a softer picture all the time.",
              } },
            { key = "dynResFps", label = "Dynamic resolution: frame rate to hold",
              choices = { "0", "60", "90", "120", "144", "165", "240" }, note = { ["0"] = "your frame rate cap" },
              tip = "The frame rate the GPU time is held to; 0 = the cap in force (the variable-refresh cap when VRR is on).",
              why = {
                  gpu = "A rate above your cap renders the world smaller and leaves the GPU idle time; a lower one lets the world use more of each frame at a larger size.",
              } },
            { key = "dynResController", label = "Dynamic resolution: controller",
              choices = { "model", "pi", "step" },
              note = { model = "recommended: learns what the pixels cost and solves for the size", pi = "integral control on the GPU time", step = "the classic engine rule: drop at once, creep back up" },
              tip = "How the render size is chosen. The model separates the part of the GPU time that follows the pixel count from the part that does not, ignores lone spikes, and keeps a headroom sized to the measured noise." },
        },
    },
    {
        title = "Sprite filtering (sharp world art when zoomed in or out)", clip = "visuals",
        entries = {
            { key = "spriteFilter", label = "Sprite filtering",
              choices = { "stock", "sharp", "nearest" },
              note = { stock = "the game's own (soft at 75 % zoom and when zoomed out)", sharp = "texel-aware: crisp edges without shimmer", nearest = "hard pixels" },
              tip = "How the world's art is scaled to your zoom. The game draws it sharp at 100 % and 50 %, soft at 75 % (a linear blur) and when zoomed out (a blend of the full picture and a blurred half-size copy: the blurry look while driving). Sharp: zoomed in, every texel of the art stays a hard square and only the one screen pixel an edge crosses is blended by how much of it the texel covers, so edges are crisp at any zoom and do not crawl while the camera glides; zoomed out, a few samples per pixel from a sharper copy of the art keep fences, window frames and road lines sharp without shimmer. At 100 % the picture is the game's own. Nearest: hard pixels at every zoom-in step (75 % included), no blending. Off (stock) = the game's own filtering. Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default); with per-pixel lighting on it applies on the next launch.",
              why = {
                  gpu = "The world picture is sampled by a variant of the same program, picked once a frame for the zoom: nothing measurable zoomed in, cheaper zoomed out; up to ~0.07 ms a frame on the desktop's town drive at 5K.",
              } },
            { key = "spriteFilterMin", label = "Sprite filtering: zoomed out",
              choices = { "rgssa2", "rgssa", "floor", "rgss4", "trilinear" },
              note = { rgssa2 = "adaptive, two samples (default)", rgssa = "adaptive, four samples", floor = "one sample, sharpest", rgss4 = "four samples, steadiest, softer", trilinear = "the game's own blend" },
              tip = "How the sharp filter shrinks the art when you zoom out. The game blends a half-size and a quarter-size copy of the art (soft); the sharp filter reads the one copy that fits the zoom. Adaptive spreads two (or four) samples as the zoom approaches the next copy, where fine lines would otherwise shimmer; one sample is the sharpest with a little grain; four fixed samples is the steadiest and softest.",
              why = {
                  gpu = "One copy of the art read a few times costs less than the game's blend of two: with the default two adaptive samples the 5K composite is 59 us cheaper at zoom 1.75 and 147 us at 2.5; four adaptive samples cost about what the game's blend does.",
              } },
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
              tip = "On by default: when the game starts on an HDR screen it is shown as HDR by itself (Linux: a Wayland desktop with HDR switched on; macOS: a display with HDR headroom, e.g. a MacBook Pro's XDR screen), on an SDR screen nothing changes. Untick it to keep the SDR picture on an HDR screen. Applies on the next launch.",
              why = {
                  render = "On Linux HDR runs in a native Wayland window, where NVIDIA's GL driver has no worker thread of its own: its work lands on the render thread (38 to 90 % of a core on the desktop's town drive).",
                  cores = "The map of which squares lamps, torches and fires light is built on its own worker thread (about 0.2 ms a frame), not on the game thread.",
                  gpu = "On an HDR screen the world is drawn into a 16-bit float picture and encoded for it at the end of each frame: about 0.25 ms a frame at 5K (none on KDE, which takes the picture as it is). Nothing on an SDR screen.",
                  vram = "The 16-bit float frame buffers at the screen's size.",
              } },
            { key = "hdr", label = "HDR output: always on",
              tip = "Turns HDR on even when the automatic check finds no HDR screen. The game is shown as HDR: the menus and the HUD stay at your desktop's white, lamps, torches, headlights, fires and lightning go above it up to your screen's peak. Needs an HDR screen with HDR switched on in the desktop settings; on Linux the game then runs as a native Wayland window. On a Mac the lights and lightning go above the screen's white (the other HDR settings below are Linux only for now); an XDR display (MacBook Pro 14/16, Pro Display XDR) has the most room above it. Without an HDR screen, or on Windows for now, nothing changes. Applies on the next launch.",
              why = {
                  render = "On Linux HDR runs in a native Wayland window, where NVIDIA's GL driver has no worker thread of its own: its work lands on the render thread (38 to 90 % of a core on the desktop's town drive).",
                  cores = "The map of which squares lamps, torches and fires light is built on its own worker thread (about 0.2 ms a frame), not on the game thread.",
                  gpu = "The world is drawn into a 16-bit float picture and encoded for the HDR screen at the end of each frame: about 0.25 ms a frame at 5K (none on KDE, which takes the picture as it is).",
                  vram = "The 16-bit float frame buffers at the screen's size.",
              } },
            { key = "hdrLightPct", label = "HDR: how bright light sources make things (%)",
              choices = { "0", "50", "100", "150", "200" }, note = { ["0"] = "off: the SDR picture in an HDR container", ["100"] = "recommended (default)" },
              tip = "What makes it HDR: every square the game lights with a lamp, a torch, a headlight or a fire is shown brighter in proportion to how much light falls on it, up to the screen's peak, with its colours intact; the dark stays dark." },
            { key = "hdrBloomPct", label = "HDR: glow around bright light (%)",
              choices = { "0", "30", "50", "100" }, note = { ["0"] = "none", ["30"] = "default" },
              tip = "The light the HDR highlights add also spills a little into the dark around them, the way a bright lamp glows in the eye. Linux only for now.",
              why = {
                  gpu = "The bright parts are blurred in smaller copies and added back each frame (skipped at 0): 0.065 ms at 4K on its own, ~0.1 ms of the HDR set on the desktop's town drive at 5K.",
              } },
            { key = "hdrSunPct", label = "HDR: sunlight (%)",
              choices = { "0", "30", "60", "100" }, note = { ["0"] = "daylight as in the SDR game", ["60"] = "default" },
              tip = "On a clear day the sunlit outdoors is shown brighter than the SDR picture by this much, less with the sun low or behind clouds; shade and interiors stay as they are. Linux only for now." },
            { key = "hdrGlintPct", label = "HDR: sparkle on water and puddles (%)",
              choices = { "0", "50", "100", "200" }, note = { ["0"] = "off (the water and puddle shaders stay stock)", ["100"] = "default" },
              tip = "The sun glitters on rivers, lakes and puddles above the desktop's white, the sky reflects in them, and at night lamps, torches and headlights glint on the water. Linux only for now.",
              why = {
                  gpu = "Water and puddles are drawn once more into a glint target and car bodies write their speculars beside their colour (skipped at 0): ~0.07 and ~0.1 ms a frame on the desktop's town drive at 5K.",
              } },
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
              tip = "Soft shading where surfaces meet: floors darken a little along the base of walls, in room corners, under and around furniture, fences, stairs and bushes, so buildings and objects sit on the ground instead of floating on it. Computed from the game's own depth when a chunk's picture is drawn and baked into it. Characters and vehicles keep their own shadows. Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default; on OpenGL 2.1 it stays off). Changing an ambient occlusion setting redraws every chunk picture over the next few frames.",
              why = {
                  gpu = "Computed once when a chunk's picture is drawn (~25-35 us a picture) and kept, so a still or moving camera pays nothing more: ~1.3 % of the GPU's time in the worst streaming, ~0.1 ms a frame on a town drive.",
                  vram = "Each chunk picture keeps its shading as a one-byte image beside it, at the AO resolution (half its size per axis by default).",
              } },
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
              tip = "The resolution the shading is computed and kept at, per axis of a chunk picture. Lower is softer.",
              why = {
                  vram = "Every chunk picture's shading is kept at this size: 100 holds four times what 50 does, 25 a quarter. The GPU time hardly changes, it follows the number of pictures, not their texels.",
              } },
        },
    },
    {
        title = "Sun, moon and cloud shadows (soft shadows of walls, trees, fences and furniture that follow the real sky)", clip = "sunshadows",
        entries = {
            { key = "sunShadows", label = "Sun shadows",
              tip = "By day, walls, trees, fences, cars parked in the world's pictures and furniture outdoors cast soft shadows of the sun onto the ground and onto each other: sharp where they touch the ground, softer further away, like real sunlight. The sun stands where it really does over Kentucky for the game's date and hour (high and short-shadowed in summer, low and long in winter, rising in the north-east in June and the south-east in December), so the shadows turn and lengthen through the day; rain and fog thin them out and they fade away at dusk. Indoors stays as it is (rooms have a roof). Characters, animals and cars cast and take shadows too (the settings below). Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default).",
              why = {
                  render = "Every character, animal and car is drawn once more from the sun, and the far-field heights are uploaded for each baked picture: about 25 us a model on a laptop's render thread.",
                  gpu = "The static shadows are baked into the chunk pictures with the AO (a picture a frame re-shaded when the sun moves); characters and cars are shaded every frame. The dearest Visual: +0.7 ms GPU on the desktop's town drive at 5K.",
                  vram = "A 2048 x 2048 depth atlas for the casters' shapes, the far-field height map and the sun term kept beside each chunk picture's shading.",
              } },
            { key = "sunShadowStrengthPct", label = "Sun shadows: strength (%)",
              choices = { "25", "35", "45", "60", "75" }, note = { ["45"] = "default" },
              tip = "How much of the daylight a full shadow takes away on a clear day. Clouds, rain and fog lower it further." },
            { key = "sunShadowCharacters", label = "Sun shadows: characters",
              tip = "Characters cast the shadow of their body onto the ground, walls and furniture around them, drawn every frame, and a character standing in a building's or a tree's shadow is shaded too. Only characters drawn with a model (the ones near enough to animate); the far ones drawn as flat pictures cast a simple upright shape.",
              why = {
                  gpu = "One instanced pass after the world shades the ground round each character: ~10 us of GPU for 70 characters on the desktop, 125-200 us for 16 on a laptop's integrated GPU.",
              } },
            { key = "sunShadowMeshes", label = "Sun shadows: the true shape of characters, animals and vehicles",
              tip = "Each character, animal and vehicle is drawn once more, as the sun sees it, into a small shadow picture of its own, and its shadow takes that shape: arms, legs, hair, clothes, backpacks and weapons; an animal's legs, head and tail; a car's body and wheels. How often the pose is renewed: \"Sun shadows: update rate\". Off: a simpler shape of rounded pieces.",
              why = {
                  render = "Every caster is drawn a second time by the render thread, at most 32 a frame: about 25 us a model on a laptop.",
                  gpu = "Each caster's pose is drawn from the sun into its own tile and the shadow pass filters it softly: ~50 us a frame for 40 zombies, 8 animals and a car on the desktop, against ~30 for the rounded shapes.",
                  vram = "The 2048 x 2048 32-bit depth atlas the casters' 128 x 128 tiles live in (16 MB).",
              } },
            { key = "sunShadowRate", label = "Sun shadows: update rate",
              choices = { "frame", "15" },
              note = { frame = "recommended (default): every frame, the shadow moves with the character", ["15"] = "15 times a second, one frame behind: the earlier behaviour, a little lighter" },
              tip = "How often the true-shape shadow of a character, animal or vehicle takes its new pose. Every frame: the shadow is drawn in the same frame as the character, so arms and legs move in step with it; in big crowds at most 32 are renewed a frame (your own every frame), the rest in turns. 15 times a second: each pose is kept for several frames and shown a frame late, which reads as a slightly choppy shadow next to a smoothly animated character; the shadow still follows the character's position every frame.",
              why = {
                  render = "Each renewed pose is one more model draw on the render thread (about 25 us on a laptop), up to 32 a frame instead of 12 in bursts.",
                  gpu = "Every pose is drawn again each frame: with 40 characters on screen about 0.09 ms a frame on an RTX 4090, against 0.03 ms at 15 a second.",
              } },
            { key = "sunShadowAnimals", label = "Sun shadows: animals",
              tip = "Farm and wild animals cast sun shadows too." },
            { key = "entityShadows", label = "Shadows on characters and vehicles",
              tip = "The player, zombies, animals and cars take the shadows of buildings, walls, roofs and trees where they stand, part by part: a zombie half behind a wall has its legs in the shade and its head in the sun, a car under a porch roof is dark under the roof only. With Sun shadows turned on during a game, part by part from the next launch (one shade for a whole character until then). Off: one shade for a whole character, none for cars.",
              why = {
                  render = "Two more uniform uploads per model draw: about 15 us a frame of render thread for that crowd.",
                  cores = "The frame's shadow bricks and casters are gathered on a worker while the world draws: 63-85 us of it in a Louisville horde, 2.4 us left on the game thread.",
                  gpu = "The character and car shaders look up the shade where each part stands: about 27 us of GPU for 40 zombies and 3 cars across a shadow's edge on the desktop, ~0 on a drive.",
              } },
            { key = "entityShadowFormPct", label = "Shadows on characters: sunlit side (%)",
              choices = { "0", "25", "50", "75", "100" }, note = { ["50"] = "default" },
              tip = "How much the side of a body facing the sun is brighter and the far side darker. The overall brightness stays the same. 0: flat, as the game draws them." },
            { key = "entityShadowCasters", label = "Shadows on characters: from other characters and cars",
              tip = "Characters and cars also shade each other: a zombie standing in a car's shadow, the player in a crowd's.",
              why = {
                  gpu = "The nearest capsules that can reach each body are tested in its shader: about 0.02 ms a frame on an RTX 4090 in a dense crowd at a shadow's edge, little with few characters.",
              } },
            { key = "entityShadowSelf", label = "Shadows on characters: their own shadow",
              tip = "A character's or a car's own body shades itself: an arm on the chest, a hat brim on the face, the cabin on the hood. For the 16 nearest characters, when zoomed in enough to see it." },
            { key = "entityShadowImpostors", label = "Shadows on characters: far zombies",
              tip = "Zombies drawn as flat pictures (far away, big hordes) take the shade of where they stand too, one shade for the whole body." },
            { key = "entityShadowTorches", label = "Shadows on characters: torches and headlights",
              tip = "At night (and in dark places), a character standing in another character's or a car's shadow from a torch or headlights is shaded too, as the ground there: a zombie behind another in your torch beam stays dark. With \"Shadows from torches and headlights\" this matches the shadows on the ground." },
            { key = "entityShadowAoPct", label = "Shadows on characters: from the bodies next to them (%)",
              choices = { "0", "30", "60", "100" }, note = { ["60"] = "default", ["0"] = "off" },
              tip = "Characters standing close together, or against a car, hide part of the surrounding light from each other: the sides that face each other are a little darker, so a packed crowd has depth instead of every body lit the same.",
              why = {
                  render = "A second uniform array per character draw: about 8 us a frame of render thread for 40 zombies standing close together (about 0.01 ms of frame time).",
                  cores = "The worker that gathers the shadows also picks up to 3 neighbours' capsules for each character: ~22 us of its time in that crowd.",
              } },
            { key = "entityShadowClouds", label = "Shadows on characters: clouds",
              tip = "With cloud shadows on, a cloud's soft shadow crosses characters and cars as it crosses the ground: a car half under a cloud's edge is half shaded, and a character's head can be in the sun while their feet are not (the shadow falls along the sun). Off: one cloud value for each character or car." },
            { key = "sunShadowVehicles", label = "Sun shadows: vehicles",
              tip = "Cars and trucks outdoors cast a sun shadow of their body." },
            { key = "sunShadowTreeCards", label = "Sun shadows: the true shape of trees",
              tip = "A tree casts the shadow of its own picture, trunk, branches and leaves, in one piece from its foot to its crown, turned towards the sun so it never thins to a line. Off: a soft oval for the crown." },
            { key = "sunShadowStockFadePct", label = "Sun shadows: the game's round shadow under characters (fade, %)",
              choices = { "0", "50", "85", "100" }, note = { ["85"] = "default" },
              tip = "Where a character or a vehicle casts a real sun shadow, the game's own round shadow under its feet fades by this much (it was a second shadow beside the sun's). Overcast, at night and indoors it stays." },
            { key = "sunShadowTorches", label = "Shadows from torches and headlights",
              tip = "At night (and in dark places), characters caught in a torch beam or in headlights cast long soft shadows away from the light. The one holding the torch does not shadow their own beam." },
            { key = "sunShadowStaticLights", label = "Shadows from street lamps, lamps and fires",
              tip = "At night (and in dark places), characters, animals and cars standing in the light of a street lamp, a lamp, a wall light or a fire cast soft shadows away from it: short and dark right under the lamp, long and faint at the edge of its light. Each one takes the shadows of the two lights that light it most. A light only shades who it really reaches, as the game lights it: the same floor, a clear line from the light (walls and closed doors block it, windows do not), a building's own lights only inside it.",
              why = {
                  gpu = "One more shadow area per lit character, at most two lights each: the same cost as a torch's shadow for each.",
                  cpu = "A line test from each light to the squares the characters stand on, kept for about a second: at most 48 a frame.",
              } },
            { key = "sunShadowRoomLights", label = "Shadows from room ceiling lights",
              tip = "With the shadows from lamps on: a room lit by its ceiling light (the switch on and the power on) gives the people, animals and cars in it soft shadows from the light above, short and dark round the feet, long and faint towards the walls; a long hallway has its light in two or three places. A room's light only shades who stands in that room, and the shadow stops at its walls.",
              why = {
                  gpu = "One more shadow area per character in a lit room, the same as a lamp's: at most two lights each.",
                  cpu = "The lit rooms near the camera are listed every frame, and a line test from the light to each character in the room, kept for about a second.",
              } },
            { key = "sunShadowSoftnessPct", label = "Sun shadows: softness (%)",
              choices = { "10", "25", "50", "100", "200" }, note = { ["25"] = "default" },
              tip = "How quickly a shadow's edge blurs with the distance from what casts it (the size of the sun's disk). 25 is close to real sunlight: a tree's shadow keeps its branches and leaves, a character's its arms and legs; 100 and more blur every shadow into a soft shape; 10 is as sharp as the real sun." },
            { key = "moonShadows", label = "Moon shadows",
              tip = "At night the moon casts the same soft shadows when it is up: where it really is for the game's date and hour, as strong as its phase allows (a full moon high in a clear sky casts clear shadows, a thin crescent barely any, a new moon none), once the sky is dark. The game's own night brightness already follows the moon's phase." },
            { key = "moonShadowPct", label = "Moon shadows: strength (% of the sun's)",
              choices = { "50", "75", "100", "125" }, note = { ["100"] = "default" },
              tip = "How dark a full moon's shadows are compared with the sun's." },
            { key = "cloudShadows", label = "Cloud shadows",
              tip = "The shadows of the clouds drift over the sunlit (or moonlit) ground, walls and roofs with the wind: as many as the weather's cloud cover, changing shape as they go, lying where the sun ray through each cloud meets the ground. Under a cloud the sunlit outdoors takes exactly the shade of a building's shadow, and the shadows the sun casts fade there; characters and cars walking into one are shaded too. Full overcast has no cloud gaps and so no shadows. Windows and Linux. Applies on the next launch when sun shadows or cloud shadows were off when the game started (the game's own shaders stay untouched then).",
              why = {
                  gpu = "World pixels under a cloud read the drifting cloud field and the picture's kept sun share (a clear sky reads nothing): within the noise of the 5K composite alone, ~0.07 ms in the full set on the desktop's town drive.",
              } },
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
        title = "Reflections (the scene mirrored in rivers, lakes and puddles)", clip = "reflections",
        entries = {
            { key = "reflections", label = "Reflections",
              tip = "Buildings, fences, trees, lamp posts, cars and characters are mirrored in rivers, lakes and puddles, rippled by the waves and the rain, sharp where they meet the water and softer further out, stronger at grazing angles of the waves. Characters' reflections are one frame behind. Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default; there the water finds its reflection by a slower search up its column). Turning it on applies on the next launch (the game's water and chunk shaders are only patched when it starts with reflections on; off, they stay exactly the game's own).",
              why = {
                  render = "The render thread issues the patched chunk and water programs and the characters' mirror pass: its load went 38 to 67 % on the desktop's town drive.",
                  gpu = "Every chunk picture writes itself where its mirror image lands as it is drawn, and the water reads one value: nothing with no water on screen, +11-15 us at a river, +27-30 us in rain with puddles at 5K.",
                  vram = "Two 32-bit images at the world picture's size hold the mirrored pixels (alternating frames), plus a small tile map.",
              } },
            { key = "reflectionStrengthPct", label = "Reflections: strength (%)",
              choices = { "25", "45", "70", "100" }, note = { ["45"] = "default" },
              tip = "How strongly the water mirrors the scene. Real water seen from the game's camera angle reflects little (a few percent, more on the side of a wave); higher is more of a mirror." },
            { key = "reflectionPuddles", label = "Reflections: in puddles",
              tip = "Puddles mirror the scene too once the rain has made them big enough (the rain's rings blur them)." },
            { key = "reflectionsFlow", label = "Reflections: follow the water",
              tip = "The reflection moves with the water instead of sitting on it like a still picture: the waves that carry the river's current and the wind carry the mirrored image too, on the water's own clock. Calm water is nearly a mirror; a fast river, wind and rain break it into streaks stretched along the current. Puddles ripple with the wind and the rain (they have no current). It reuses the waves the water already computes: no measurable cost. Applies at once." },
            { key = "reflectionsFlowPct", label = "Reflections: waves (%)",
              choices = { "0", "50", "100", "200" }, note = { ["100"] = "default", ["0"] = "a mirror" },
              tip = "How much the current, the wind and the rain bend and break the reflection (0: a still mirror whatever the weather; 200: twice the default)." },
        },
    },
    {
        title = "Puddle ripples (footsteps, raindrops and car wakes)", clip = "ripples",
        entries = {
            { key = "ripples", label = "Puddle ripples",
              tip = "Anyone walking or running through a puddle, zombies, animals and you, rings it at every step, and car wheels leave trails of rings behind them. The rings are real water: each one a train of waves that spreads and fades (the longer waves run ahead), they cross and add up, and they bend whatever the puddle mirrors (the sky, and with Reflections on the zombies and the buildings). Only puddles big enough to show do it, outdoors, only where someone steps. The rain rings puddles and wet ground with real drop rings instead of the game's grid of half rings, and cars leave a continuous wake. Windows, Linux and macOS. Turning it on applies on the next launch, the rest at once.",
              why = {
                  cores = "The rings are sorted into screen tiles on a worker thread each frame (~8 us for ~190 rings), not on the game thread.",
                  gpu = "The puddle shader reads only the rings sorted into its part of the screen: about 9 us a frame at 5K with 40 walkers in puddles everywhere (19 us on a laptop's iGPU), nothing when it is dry.",
              } },
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
              tip = "The rings the rain makes on puddles and wet ground. The game's own (stock) draw a grid of half rings that pulse in place; these are real drop rings at random spots, each a small train of waves that spreads and fades, filtered so they never shimmer when zoomed out. cells: worked out per pixel (no textures). texture: drops read from a small texture (Lagarde's technique from Remember Me). flipbook (default): a precomputed loop of the whole rain field. field: drawn once a frame into a small texture the puddles read (needs OpenGL 4.3). sim: the water simulated on the GPU, rings crossing each other for real (needs OpenGL 4.3). none: no rings at all.",
              why = {
                  gpu = "The default flipbook is two texture reads a pixel instead of the stock rings' maths: the 5K puddle pass 19-33 us cheaper than stock on an RTX 4090 (cells costs ~0.2 ms more than stock).",
              } },
            { key = "ripplesRainPct", label = "Puddle ripples: raindrop strength (%)",
              choices = { "50", "100", "150", "200" }, note = { ["100"] = "default" },
              tip = "How strongly the raindrops' rings catch the light." },
            { key = "ripplesWake", label = "Puddle ripples: car wakes",
              choices = { "trail", "kelvin", "particles", "sim", "rings" }, note = { ["trail"] = "default" },
              tip = "What a car leaves in the puddles it drives through. trail: two tyre grooves that fill back in and a wave running out of each, a continuous wake however fast you drive. kelvin: the V-shaped wake of a boat. particles: each tyre sheds little waves to both sides as it moves (wave particles). sim: the wheels push the simulated water (the footsteps then run in that simulation too; needs OpenGL 4.3). rings: a ring at the rear wheels every few squares (the first version). trail, kelvin and particles need OpenGL 4.3 (else rings).",
              why = {
                  gpu = "The tyre trail is stamped into a world map by a small compute pass the puddles read: about 13 us of GPU at 5K while a car drives through water, nothing otherwise.",
              } },
        },
    },
    {
        title = "Car glass (windows that reflect the world and show the cabin)", clip = "carglass",
        entries = {
            { key = "carGlass", label = "Car glass",
              tip = "Car windows become glass instead of the stock opaque blue: they mirror the sky (the game's own, with its clouds and sunsets, turned to the real sun), the buildings, trees and road around the car, and glint where they mirror the sun, the moon, the street lamps and headlights, more at grazing angles (Fresnel); and through them you see the cabin: seats, headrests, the dashboard, the driver and passengers, and the street behind the car where you look out through the far window. Cracks, blood and broken-out windows stay as the game draws them; rain beads on the glass. Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default). Turning it on applies on the next launch (the game's vehicle shaders are only patched when it starts with car glass on).",
              why = {
                  render = "Each car's glass is one more draw with its own settings: about 3 us of render thread a car (~0.1 ms for a ring of 33 cars).",
                  gpu = "A small reflection probe per car is marched through the scene before the cars, then a separate glass program redraws only the window triangles: +10 us with a few cars in view, +54 us for 33 parked cars; nothing with no car on screen.",
              } },
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
              tip = "Who sits in a car shows through its glass. The game never draws a seated character (cars look empty whoever drives them), though it animates them. impostor: the game's own model of the driver and passengers, hands on the wheel, in their clothes, drawn into a small picture of their own before the cars and looked up by the glass wherever it shows the cabin; redrawn when they move (turning the wheel up to 60 times a second, still a few times a second), between that the glass moves the last picture with the car. proxy: a simple body (head, torso, arms, legs) in their skin, hair and clothing colours, worked out by the glass itself (nothing extra drawn; cruder). box: the first release's dark torso shape. Applies on the next launch.",
              why = {
                  render = "Each redraw of an occupant's picture is a model draw on the render thread (~30 us warm, 150-200 us cold); drawn only when they move, that is about 3 us a frame parked.",
              } },
            { key = "carOccupantOcclusion", label = "Car glass: seats hide the people inside",
              tip = "Off (default): the people inside show wherever the glass shows the cabin, over the seats in front of them (a cutaway: from the game's high camera angle the roof and the seats would otherwise hide most of a driver). On: the seats, headrests and floor hide them where they stand between them and the glass. Applies on the next launch." },
            { key = "carOccupantLightPct", label = "Car glass: light on the people inside (%)",
              choices = { "60", "80", "100", "120" }, note = { ["80"] = "default" },
              tip = "How bright the people inside the car are, against how the game lights them outside (the roof shades them). Applies on the next launch." },
        },
    },
    {
        title = "Occluded zombie outlines (the hidden parts of the zombies you see)", clip = "outlines",
        entries = {
            { key = "occludedZombieOutlines", label = "Outline the hidden parts of zombies you see",
              tip = "A zombie you can see that walks behind a wall, a tree, a fence or a car keeps a thin contour where the scenery hides it, so you know where it went. Only zombies your character sees right now (the same sight that draws them): no unseen or remembered zombies, nothing through the dark (the contour fades with the light on the zombie). The parts hidden behind other characters are not outlined. Applies at once. Off while DLSS / TAAU object motion is on (it uses the same buffer bits).",
              why = {
                  render = "Zombies with something in front of them are drawn once more into the mark: +0.02-0.06 ms a frame in a crowd of 60, +0.08 ms in a 300-zombie horde.",
                  gpu = "The zombies' own draws mark what they cover, then one pass draws the contour where they are hidden: ~12 us at 5K, skipped on frames where no zombie can be hidden.",
              } },
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
        title = "Mirrors and windows (real reflections)", clip = "mirrors",
        entries = {
            { key = "mirrors", label = "Mirror and window reflections",
              tip = "Wall mirrors, mirrored medicine cabinets and dressers, and window panes reflect what stands in front of them: the floor and the room, the street, and you, the zombies and the cars as their real other side (the game's own models drawn once more through the mirror's plane, lit as they are lit), so a mirror shows your face, not your back. From the game's high camera a wall mirror shows the floor and whoever stands within a couple of squares of it; a window upstairs shows the street below. Someone inside a room seen through a window keeps the reflection over them, as glass does; a closed curtain stops it. The room part of each pane's reflection is worked out once and kept while nothing changes; panes hidden under a roof or behind a building are skipped. Applies on the next launch (mirror tiles are drawn on their own instead of into the chunk pictures).",
              why = {
                  render = "The render thread issues each pane's march into its atlas tile (9-17 us a call, at most every 4th frame), one composite a frame (4-8 us) and the mirrored models' flush (~20 us plus ~3 us a model).",
                  gpu = "Panes are marched when new or every 30 frames and composited once a frame: +13..34 us a frame with ~30 windows on screen on the desktop at 5K (about 1 % of a 240 fps frame), nothing without one on screen.",
                  vram = "The panes' atlases (2048 x 2048 reflection colour, depth and glass masks) stay allocated while the game runs.",
              } },
            { key = "mirrorsWindows", label = "Mirrors: windows reflect too",
              tip = "On: window panes reflect as well (subtly, as glass does). Off: no window reflects (wall mirrors and glass props keep theirs). Applies on the next launch.",
              why = {
                  gpu = "In a street the windows are most of the panes: each adds its tile to the march and its pixels to the composite (29 windows on screen: +13..34 us a frame on the desktop at 5K).",
              } },
            { key = "mirrorsWallMirrors", label = "Mirrors: wall mirrors reflect too",
              tip = "On: wall mirrors, mirrored medicine cabinets and dressers, and the gym's wall mirrors reflect the room in front of them and the people in it. Off: they look as in the game (a plain mirror texture), and only the windows and glass props reflect: for window reflections without the mirrors. Applies on the next launch.",
              why = {
                  gpu = "Each wall mirror on screen is drawn every frame instead of in the chunk picture, marched into its tile with its rebuilt room and composited with the other panes; off they bake as in the game.",
              } },
            { key = "mirrorsWindowPct", label = "Mirrors: window reflection strength (%)",
              choices = { "15", "30", "50", "70" }, note = { ["30"] = "default" },
              tip = "How much of a window pane is its reflection. Real glass reflects a few percent head-on, more where the room behind it is dark; higher reads more like a shop window by day. Applies on the next launch." },
            { key = "mirrorsProps", label = "Mirrors: glass, screens and steel reflect too",
              tip = "On: the things in a room that are not mirrors or windows reflect as well: glass doors, shop fronts and railings, shower screens, display counters and cases, glass-door fridges, the glass table, switched-off televisions and monitors, the gym's wall mirrors, steel counters, sinks and appliances, toilets. Each surface reflects the way it faces: a glass top shows what stands behind it, a door or a pane what stands in front, faintly as glass does; steel, ceramic and dark screens keep their own look and show the people and cars passing in front as a soft sheen. Their shape comes from the game's own depth maps, so nothing is guessed per object. Reflections fade where you cannot see (out of your sight the prop is dark, so is its reflection). Applies on the next launch (with Mirror and window reflections on).",
              why = {
                  gpu = "Props join the mirrors' composite and model passes, their reflection worked out once a prop and kept: +107..129 us a frame in prop-dense rooms and shops on a laptop (Radeon 890M, 1080p), 0.7 % of a 60 fps frame.",
              } },
            { key = "mirrorsPropGlassPct", label = "Mirrors: glass prop reflection strength (%)",
              choices = { "15", "25", "40", "60" }, note = { ["25"] = "default" },
              tip = "How much of a glass door, counter or case is its reflection. Real glass reflects a few percent head-on and more at a grazing angle (a glass top more than an upright pane: the game's camera sees tops at 60 degrees). Applies on the next launch." },
            { key = "mirrorsGeometry", label = "Mirrors: the room behind the glass",
              tip = "On: a wall mirror's room is rebuilt behind the glass from the game's own tiles, so the mirror shows what the camera can never see: the far side of the bathtub or the bed in front of it (the furniture's other facing, as the game draws it when you turn it), the floor behind it, and the wall across the room. Off: the reflection is made of what the camera sees, and where that is hidden (behind a table, a bathtub) the floor seen last stands in. Applies on the next launch.",
              why = {
                  vram = "The rebuilt rooms are drawn into their own geometry atlas (colour and depth, ~32 MB), only for a pane due for its march, and kept while the room stands.",
              } },
            { key = "mirrorsCutawayHoldMs", label = "Mirrors: steady walls by a room corner (ms)",
              choices = { "0", "750", "1500" }, note = { ["750"] = "default", ["0"] = "stock" },
              tip = "The game cuts the walls round you away so you stay in sight; standing or walking by a room's corner it flips a wall between cut and shown every few tenths of a second, and a mirror on that wall blinked in and out with it. A wall with a mirror changes at most once in this time (it still goes at once the first time) and comes back only after it has not been needed for twice this, the game's own cutaway lock that its newer renderer skips. 0: the game's flipping. Applies on the next launch." },
            { key = "mirrorsModels", label = "Mirrors: people and cars in the reflection",
              tip = "On: characters and vehicles in front of a mirror or window are drawn once more through its plane, so the mirror shows their faces and the side of the car facing it. Off: the reflection is made of what the camera sees (you see their backs). Applies on the next launch.",
              why = {
                  render = "Every mirrored character or car is submitted again through the mirror's plane: a flush of ~20 us plus ~3 us a model on the frames the layer is redrawn (at most 120 a second).",
                  gpu = "The models are drawn a second time into the reflection layer, only those at panes you can see: with 24 zombies at a shop front ~11 us a frame on the desktop at 5K.",
              } },
            { key = "mirrorsViewLateralPct", label = "Mirrors: where you appear along the glass (%)",
              choices = { "0", "50", "100" }, note = { ["0"] = "default" },
              tip = "0: you appear straight in front of where you stand, as you see yourself in a mirror. 100: the game camera's true reflection, which puts someone a square out from the mirror a square to its side (off a small medicine cabinet). Windows always use the true reflection. Applies on the next launch." },
            { key = "mirrorsViewDropPct", label = "Mirrors: how high you appear (%)",
              choices = { "0", "25", "50", "75", "100" }, note = { ["50"] = "default" },
              tip = "How much higher someone farther from the mirror appears in it, as a share of the game camera's true reflection (100: a third of a floor per square out, a head at the sink above the medicine cabinet). 0: at their own height, eye level, below the game's high-hung cabinets. 50: your head in the cabinet from the sink, as if looking slightly down into the mirror. Applies on the next launch." },
        },
    },
    {
        title = "Wet blood (fresh blood reflects and catches the light)", clip = "blood",
        entries = {
            { key = "bloodWet", label = "Wet blood",
              tip = "Fresh blood on the floor is a film of liquid until it dries: the rims of the pools catch the sun and the lamps, the sky and (with Reflections on) the zombies and walls standing in them are mirrored at the film's own angle, and with HDR output the highlights go above white. The film's shape comes from the splat itself (flat pools, bright edges) and it dries from the edges in. Walls, tables and bodies in front of the floor hide it. Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default).",
              why = {
                  cpu = "The game thread collects the wet splats on screen, rebuilt only when chunks, visible squares or the camera's chunk change, or every 100 ms: +0.6 % of its time in a fight, 2.3 % with every chunk on screen wet.",
                  gpu = "The wet film is drawn over the floor once a frame, like the puddles: 8.5-9 us in a realistic fight (up to 285 splats), 12-13 us with 4,096 wet splats on screen, on the desktop at 5K.",
              } },
            { key = "bloodWetMinutes", label = "Wet blood: stays wet (game minutes)",
              choices = { "30", "60", "120", "240" }, note = { ["120"] = "default" },
              tip = "How long a splat stays wet, in game time.",
              why = {
                  gpu = "Longer keeps more splats wet at once, so more films are drawn each frame; shorter dries them sooner (from ~9 us in a fight to ~13 us with every chunk on screen wet, desktop at 5K).",
              } },
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
              tip = "Sunlight (and moonlight) falls through windows and open doorways into rooms as shafts of light in the dust, with sunlit patches on the floor, tables and walls where it lands, cut exactly by the window frames; outdoors, in fog, rain and morning mist, the shadows of buildings and trees stretch through the haze; torches, headlights and lamps glow in the dust and fog around them. Walls, roofs, upper floors, curtains and barricades block the light; tree crowns let it through their gaps. Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default).",
              why = {
                  cpu = "The game thread records the walls, windows, doorways, roofs and crowns of each chunk (11 us a chunk, at most 400 us a frame while chunks arrive), then 2-6 us a frame for the window prisms and the lights.",
                  gpu = "A visibility volume is traced towards the sun, the sunlit windows draw their light volumes and the haze is tapped inside the chunk composite: 10-14 us a frame on the desktop at 5K, ~7 us on a clear noon.",
                  vram = "The occupancy grid (256 x 256 squares x 16 levels round the camera) and the visibility and haze volumes at the screen's size.",
              } },
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
              tip = "Torch beams, headlights, street lamps and fires glow in the dust of rooms and in fog and rain, their cones shaped by the light's reach and direction.",
              why = {
                  gpu = "Up to 8 nearby lights each draw one screen-blended quad over their reach with the closed-form glow; only at night, in rooms or in fog and rain: ~10 us a frame for all god rays at night on the desktop at 5K.",
              } },
            { key = "godRaysLocalPct", label = "God rays: torch and lamp glow (%)",
              choices = { "50", "100", "200" }, note = { ["100"] = "default" },
              tip = "How strong the glow around torches, headlights and lamps is." },
            { key = "godRaysDoorLeaf", label = "God rays: open doors block the light",
              tip = "An open door stands in its doorway's light: the shaft and its sunlit patch on the floor lose the part the door shades, and a glass door lets its share through." },
            { key = "godRaysDoorSwingMs", label = "God rays: door swing (ms)",
              choices = { "0", "200", "350", "600" }, note = { ["350"] = "default", ["0"] = "at once" },
              tip = "When a door opens the light sweeps in through the widening gap over this long, and it is cut off the same way as the door shuts (0: the light comes and goes the moment the door does)." },
            { key = "godRaysCharShadows", label = "God rays: people's shadows in the shafts",
              tip = "The player, other people and zombies standing in a shaft of light cast their shadow into the lit dust and onto the sunlit patch on the floor.",
              why = {
                  gpu = "People near a lit window or doorway become two capsules each that the light volume tests: +2 us with you in a doorway's shaft, +4 us with 6 people at windows, on the desktop at 5K.",
              } },
            { key = "godRaysDoorDustPct", label = "God rays: dust stirred by doors (%)",
              choices = { "0", "50", "100", "200" }, note = { ["100"] = "default", ["0"] = "off" },
              tip = "A door opening stirs up the dust in its shaft: thicker swirls drift in with the draft and settle over a few seconds." },
            { key = "godRaysSpill", label = "God rays: lamplight spilling out at night",
              tip = "At night a lit room's light spills out through its open doors and windows into the fog and the rain, shaped by the opening.",
              why = {
                  gpu = "Each lit room's ceiling light by an open door or window draws its glow clipped to the opening, only in fog or rain: the night light pass 21.5 to 27.6 us on the desktop at 5K.",
              } },
            { key = "godRaysSpillPct", label = "God rays: spill brightness (%)",
              choices = { "50", "100", "150", "200" }, note = { ["100"] = "default" },
              tip = "How bright the light spilling out of lit rooms is." },
            { key = "godRaysBouncePct", label = "God rays: light bounced off the sunlit floor (%)",
              choices = { "0", "50", "100", "200" }, note = { ["0"] = "off" },
              tip = "The sunlit patch a shaft lays on the floor bounces a little of its light onto the walls and furniture around it. Subtle next to the game's own room light, so off by default.",
              why = {
                  gpu = "Each sunlit patch adds one box that lights the surfaces round it as a disk light: +17 us a frame at the 60 fps capture cap.",
              } },
            { key = "godRaysRoofExact", label = "God rays: sun under porch roofs",
              tip = "Off (the default): a window or doorway under a porch roof or a floor above lets no light in. On: the sun comes in under the roof's edge as its height allows, so a low morning or evening sun reaches in under a porch and a high midday one does not. Applies on the next launch." },
        },
    },
    {
        title = "Foliage sway (grass, bushes and trees in the wind)", clip = "sway",
        entries = {
            { key = "foliageSway", label = "Foliage sway",
              tip = "Grass, bushes and trees bend and sway in the wind: they lean with it, gusts roll across fields and tree crowns, each plant swings at its own pace (grass quick, trees slow) and leaves flutter. The plants stay in the game's cached chunk pictures; their pixels are moved by the wind as the pictures go on screen. The game's own \"Wind sprite effects\" option (off by default) does this by drawing every plant every frame instead. Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default).",
              why = {
                  gpu = "The chunk composite moves each plant pixel by the wind as it draws: +19..21 us a frame on the desktop at 5K (1.7 % uncapped), where the game's own wind option costs +348 us.",
                  vram = "Each chunk picture keeps a plant attribute texture: 104 MB at zoom 1, 272 MB zoomed out; those not drawn for 3 s are freed while streaming (142-180 MB).",
              } },
            { key = "foliageSwayPct", label = "Foliage sway: strength (%)",
              choices = { "50", "100", "150", "200" }, note = { ["100"] = "default" },
              tip = "How far the plants bend in the wind." },
            { key = "foliageSwayTaps", label = "Foliage sway: quality",
              choices = { "1", "2", "3", "4" }, note = { ["1"] = "default", ["2"] = "plant edges move too" },
              tip = "How the moving plants cover what is behind them: 1 moves each plant's pixels inside its own outline only; 2 to 4 also let the plant's edge move out over the ground behind it, looking 1 to 3 steps upwind.",
              why = {
                  gpu = "Each step upwind is one more lookup per plant pixel in the composite: taps 1 (the default) +13..17 us, taps 3 +46 us at 5K on the desktop.",
              } },
        },
    },
    {
        title = "Ground and grass: tiling (ground without the grid)",
        entries = {
            { key = "groundHexTiling", label = "Ground without the tile grid",
              tip = "Lawns, fields, dirt and sand lose their visible tile repeat: each natural square takes its painted ground from the art at its own place in the world (hex-tiling, Mikkelsen 2022) instead of repeating the same few painted variants, so a big field no longer reads as a diamond quilt when you zoom out. Crop rows, paving and anything players build stay as they are. Baked into the cached chunk pictures: nothing extra per frame." },
            { key = "groundMacroPct", label = "Ground: large-scale colour drift (%)",
              choices = { "0", "50", "100", "150" }, note = { ["0"] = "off", ["100"] = "default strength when on" },
              tip = "Grass and dirt drift slowly in colour and dryness over tens of squares, as real ground does, instead of one even tone. Baked into the chunk pictures." },
            { key = "groundHeightBlend", label = "Ground: grass grows over the dirt edge",
              tip = "Where grass meets dirt or sand, the taller material wins texel by texel (height blending), so grass blades grow over the edge and sand fills the cracks, instead of the even alpha fade the game paints. Baked into the chunk pictures." },
        },
    },
    {
        title = "Ground and grass: cover by season, glints",
        entries = {
            { key = "groundScatter", label = "Ground cover by season",
              tip = "The ground collects what the season leaves: leaves under the trees and in the gutters in autumn, flowers in spring, litter by the roads, frost in the shade on a cold morning. Placed by rules from the world itself (trees, roads, the month, the wind), always the same leaf in the same place. Baked into the chunk pictures; a season step re-bakes them slowly in the background." },
            { key = "groundScatterDensityPct", label = "Ground cover: amount (%)",
              choices = { "50", "100", "150", "200" }, note = { ["100"] = "default" },
              tip = "How much ground cover there is." },
            { key = "glints", label = "Glints on frost, snow and wet asphalt",
              tip = "Frost, snow and wet roads sparkle: tiny flakes catch the sun, a torch, headlights or a street lamp, and the sparkle moves with the light (Deliot and Belcour 2023). Only where a frost, snow or wet surface and a light meet.",
              why = {
                  gpu = "Only the flakes that catch a light are drawn, as tiny points: ~0.005 ms a frame on a frosty morning on the desktop at 5K, ~0.012 ms with every outdoor square frosty in full sun, nothing at all without frost, snow or a wet road in a light.",
              } },
            { key = "glintPass", label = "Glints: how they are drawn",
              choices = { "particles", "compact", "diamonds" },
              note = { particles = "the glinting flakes alone, as tiny points (default)", compact = "the same flakes, listed on the GPU first (needs OpenGL 4.3)", diamonds = "every frosty square in a light, pixel by pixel" },
              tip = "Particles: each frosty, snowy or wet square holds a counted number of tiny ice facets; only the ones that mirror the light into your eye are drawn, as points that stay put on the ground while the camera moves. Compact: the same flakes, sorted out by a compute pass first. Diamonds: the light is worked out for every pixel of the square (the Deliot and Belcour model), the most detailed and the most expensive.",
              why = {
                  gpu = "Measured on the desktop at 5K with every outdoor square frosty in full sun: particles ~0.012 ms of GPU a frame, compact ~0.019 ms, diamonds ~0.10 ms. Nothing at all without frost, snow or a wet road in a light.",
              } },
            { key = "glintAsync", label = "Glints: worked out beside the game",
              tip = "Which frosty, snowy or wet squares catch a light is worked out on a thread of its own while the game draws the rest of the frame, instead of on the game's main thread.",
              why = {
                  cpu = "Wet town road at night with two cars' lights and the street lamps, zoom 2.5, on the desktop: ~0.004 ms of the game thread a frame on, ~0.06-0.07 ms off.",
                  cores = "The work moves to a thread of its own: ~0.06 ms a frame there in the same scene, nothing without frost, snow or a wet road in a light.",
              } },
            { key = "glintPct", label = "Glints: brightness (%)",
              choices = { "50", "100", "150", "200" }, note = { ["100"] = "default" },
              tip = "How bright the glints are." },
            { key = "glintHdrPct", label = "Glints: HDR highlights (%)",
              choices = { "0", "50", "100", "200" }, note = { ["0"] = "off", ["100"] = "default" },
              tip = "With HDR output on, glints shine past the white of a normal picture, as the sun's glitter on the water does. Off: they stop at white." },
            { key = "groundCoverFrost", label = "Ground cover: morning frost",
              tip = "After a cold, clear night the grass and the dirt turn white with rime in the shade, and it melts where the sun has been shining. Prints and ruts show dark through it." },
            { key = "groundCoverWind", label = "Ground cover: leaves in the wind",
              tip = "In autumn wind a few leaves lift off the ground and tumble downwind, hopping and spinning, and settle again. More wind and more fallen leaves, more of them; none when it is calm, wet or out of season.",
              why = {
                  gpu = "At most 32 small leaves in one draw: ~0.007 ms of GPU a frame on the desktop while they fly, nothing otherwise.",
              } },
            { key = "groundCoverPlacement", label = "Ground cover: placement",
              choices = { "bluenoise", "jitter" },
              note = { bluenoise = "evenly spread, no clumps (default)", jitter = "a jittered grid" },
              tip = "How leaves, petals and litter are spread inside a square: blue noise keeps every piece apart from the next at any amount, a jittered grid lets pieces bunch up." },
            { key = "groundCoverAssets", label = "Ground cover: art",
              choices = { "mixed", "procedural", "sprites" },
              note = { mixed = "drawn shapes and the game's leaf and litter decals (default)", procedural = "drawn shapes only", sprites = "the game's decals only" },
              tip = "Which art the ground cover uses: shapes drawn by the shader (leaves, petals, paper), the game's own leaf-clump and litter decals, or both." },
        },
    },
    {
        title = "Ground and grass: tracks that stay",
        entries = {
            { key = "groundTracks", label = "Tracks that stay",
              tip = "Cars leave tyre ruts in mud and snow, people leave footprints, a horde wears a path across a lawn. Grass flattens and recovers over a couple of game days, mud keeps its ruts, snow refills while it snows. The ground's relief lights them like the rest of the art.",
              why = {
                  cpu = "Stamping a car and a few hundred walkers into the ground map: a few microseconds a frame on the game thread.",
                  gpu = "The wheels and feet are pressed into the ground map on the graphics card (a microsecond or two a frame while something moves), the tracks are drawn into the ground when its chunk is redrawn, about 5 us a time; with Tracks: show at once the fresh ones also each frame until then.",
                  vram = "The ground map round the player: 16 MB.",
              } },
            { key = "groundTracksLook", label = "Tracks: how they are drawn",
              choices = { "bake", "patch", "composite" }, note = { bake = "default" },
              tip = "bake: drawn into the ground's textures when a chunk is redrawn, over the snow too (with Tracks: show at once, the fresh ones also each frame until then). patch: inside the ground tiles' own drawing (no extra draw, but snow covers the tracks). composite: drawn over the picture every frame (fresh tracks show at once, costs a little every frame where tracks are on screen)." },
            { key = "groundTracksRealtime", label = "Tracks: show at once",
              tip = "A print or a rut shows the frame it is pressed: what changed since its chunk was last redrawn is drawn over the picture every frame until the redraw takes it over (Tracks: how they are drawn = bake). Off: tracks show when their chunk is next redrawn, a moment later (seconds under a horde). Trodden grass plants follow at the redraw either way.",
              why = {
                  gpu = "Only the squares stamped since their chunk's last redraw are drawn again each frame: about 2 us a frame with a few people on a lawn, 15-25 us under a horde of 200 on bare earth, nothing on roads and once the chunks are redrawn.",
              } },
            { key = "groundTracksWater", label = "Tracks: water in the ruts",
              choices = { "auto", "bake", "puddles", "off" }, note = { auto = "default" },
              tip = "After rain, water stands in mud and snow ruts. auto: puddles when Puddle ripples is on, else bake. bake: drawn with the tracks (still water). puddles: the game's puddles fill the ruts, with their ripples, raindrop rings and reflections (needs Puddle ripples on). off: no water in the ruts." },
            { key = "groundTracksPom", label = "Tracks: depth",
              tip = "The near wall of a rut hides part of its floor, as seen from the camera (relief offset sampling): ruts and prints read as cut into the ground instead of painted on it." },
            { key = "groundTracksAo", label = "Tracks: shade in the ruts",
              tip = "The floor of a rut sees less sky and is a little darker." },
            { key = "groundTracksShadowSteps", label = "Tracks: shadows inside the ruts",
              choices = { "0", "4", "8", "16" }, note = { ["8"] = "default", ["0"] = "off" },
              tip = "A rut's wall shades its floor when the sun is low: steps of the shadow test." },
            { key = "groundTracksStrengthPct", label = "Tracks: how deep",
              choices = { "50", "100", "150", "200" }, note = { ["100"] = "default" },
              tip = "How deep wheels and feet press into the ground." },
            { key = "groundTracksVehicles", label = "Tracks: from cars", tip = "Cars leave tyre tracks, deeper and smeared where a wheel slips." },
            { key = "groundTracksFeet", label = "Tracks: footprints", tip = "Players, zombies and animals leave footprints; a horde wears a path." },
            { key = "groundTracksBodies", label = "Tracks: bodies", tip = "Bodies on the ground and dragged bodies leave their imprint." },
            { key = "groundTracksGrass", label = "Tracks: trodden grass", tip = "The tall grass and plants over a path, a rut or a print lie flat and go to straw, then stand up again as the ground recovers." },
            { key = "groundTracksRebakesPerSec", label = "Tracks: redraws a second under a crowd", choices = { "0", "6", "12", "24" }, note = { ["12"] = "default", ["0"] = "no limit" },
              tip = "A horde over many chunks redraws them at most this often a second in all: their tracks show a little later instead of costing frame time." },
            { key = "groundTracksPersist", label = "Tracks: keep them when you leave",
              tip = "Tracks are kept per save in Zomboid/pzopt/ground/ (never inside the save itself) and are still there when you come back. Off: they are forgotten once you leave the area.",
              why = {
                  disk = "A small file per 16 x 16 chunks you left tracks in, written in the background every 30 seconds and when you move on.",
              } },
            { key = "groundTracksDays", label = "Tracks: days to fade",
              choices = { "1", "2", "4", "7", "14" }, note = { ["2"] = "default" },
              tip = "Game days trodden grass takes to stand up again. Each ground keeps its own pace around it: sand blows smooth sooner, mud keeps its ruts about five times longer (rain softens them within a day), packed snow lasts until it thaws or new snow fills it." },
        },
    },
    {
        title = "Ground and grass: blades near you",
        entries = {
            { key = "grassBlades", label = "Grass with blades",
              tip = "Near you, at zoom 1 or closer, the grass becomes real blades in clumps: wind waves roll across them, a low sun shines through, and they stay trampled where people walked. Further out the painted grass with foliage sway stays. Each blade takes its colour from the painted grass under it.",
              why = {
                  gpu = "Tens of thousands of blades near the player: about 50 to 150 us a frame at 5K on the desktop, only while grass is in reach.",
                  vram = "The blades' buffers and the wind and trample maps: a few MB.",
              } },
            { key = "grassBladeRadius", label = "Grass blades: reach (squares)",
              choices = { "6", "9", "12", "16", "24" }, note = { ["12"] = "default" },
              tip = "How far round you the grass has blades.",
              why = {
                  gpu = "The blade count grows with the square of the reach.",
              } },
            { key = "grassBladeDensityPct", label = "Grass blades: density (%)",
              choices = { "50", "75", "100", "150" }, note = { ["100"] = "default" },
              tip = "How many blades.",
              why = {
                  gpu = "The blade pass costs in proportion to the blades drawn.",
              } },
            { key = "grassBladeTorchShadows", label = "Grass blades: cast torch shadows",
              tip = "With \"Per-pixel lighting: torch shadows\" on, each blade near you also casts its own shadow into your torch's beam. Off (the default), the blades cast none, so the beam on a lawn stays clean; they still sit in the shadows of fences, walls and furniture. Does nothing while torch shadows are off." },
        },
    },
    {
        title = "Darkness, remembered places and colour grading", clip = "darkness",
        entries = {
            { key = "darknessFloorPct", label = "Darkness floor (% of full light)",
              choices = { "0", "10", "15", "20", "30", "40" }, note = { ["0"] = "off (the game's own darkness)", ["20"] = "a dim moonlit floor" },
              tip = "No place you have already seen gets darker than this: pitch-black rooms and nights keep a faint, cool light, so you can make out the floor, walls and furniture instead of a black void. Lamps, fires, torches and daylight are unchanged; places you have never seen stay black. Basements stay pitch black unless you tick the next box. Applied to the game's own light values.",
              why = {
                  cpu = "The floor is applied as the game thread reads each square's light: 17-26 us a frame on a streaming route. Changing it re-reads every loaded square and re-bakes the chunk pictures on screen once.",
              } },
            { key = "darknessFloorBasements", label = "Darkness floor: also in basements",
              tip = "Basements and everything below ground get the darkness floor too. Off: below ground stays as dark as the game makes it." },
            { key = "memoryTint", label = "Remembered places",
              tip = "What you cannot see right now (behind walls, behind you) is drawn like a memory, desaturated, dimmer and cooler, with a soft edge, instead of darkened; rooms of a building you walked out of keep a dim remembered light and their furniture instead of going black. Zombies and other characters out of sight stay hidden as always. It replaces the view cone's own darkening in the same pass. Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default)." },
            { key = "memoryTintPct", label = "Remembered places: strength (%)",
              choices = { "40", "55", "70", "85", "100" }, note = { ["70"] = "default" },
              tip = "How strongly what you cannot see is desaturated and dimmed. 100 turns it fully grey." },
            { key = "memoryLightPct", label = "Remembered places: light of remembered rooms (% of full light)",
              choices = { "5", "10", "15", "20" }, note = { ["10"] = "default" },
              tip = "How bright rooms you have seen stay once they are out of sight (the game fades them to black)." },
            { key = "colorGrading", label = "Colour grading",
              tip = "The picture is colour graded by time of day and weather, like a film: nights shift towards the desaturated blue-green your eyes see in the dark (the Purkinje effect) while lamps and fires keep their colour, dawn is pink and cool in the shadows, the hour before dusk golden, overcast and rain greyer and cooler, storms dark and cold, fog soft and flat. Clear daylight is unchanged. The game's own screen filter and the grade become one colour lookup per pixel (the game's invisible film grain is left out). Custom looks: .cube files named base, night, dawn, dusk, overcast, rain, storm, fog or snow in Zomboid/pzopt/luts. Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default).",
              why = {
                  cores = "The colour table is baked on a low-priority worker (about 24 ms) only when the hour or the weather moves the look, then uploaded in ~30 us.",
                  gpu = "One table lookup per pixel replaces the stock screen shader's colour maths and its 3D-noise grain: the screen pass costs 72 instead of 100 us at 5K on the desktop.",
              } },
            { key = "colorGradingPct", label = "Colour grading: strength (%)",
              choices = { "25", "50", "75", "100", "130" }, note = { ["100"] = "default" },
              tip = "How strong the grade is. 0 would be the game's own colours." },
            { key = "colorGradingNightPct", label = "Colour grading: night vision shift (%)",
              choices = { "0", "50", "100", "150" }, note = { ["100"] = "default" },
              tip = "How much dark places at night lose their colour and turn blue-green, like human night vision. 0 keeps the night's colours." },
        },
    },
    {
        title = "Per-pixel lighting (smooth light, torch and headlight beams drawn per pixel)", clip = "ppl",
        entries = {
            { key = "pixelLight", label = "Per-pixel lighting",
              tip = "The world's light is drawn per pixel instead of being painted into the chunk pictures square by square. The game's own lighting still decides how much light every square gets and what walls hide; the picture follows it smoothly between squares instead of in blocky steps, and a torch or headlight beam is drawn from its own cone, so it has straight edges and follows your aim every frame. Nights look a little darker than stock: stock spreads every lit square's light half a square into its neighbours, even through walls. Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default). Applies on the next launch.",
              why = {
                  cpu = "A light change only marks a small light grid dirty instead of re-baking chunk pictures, so moving torches, headlights and lightning stop causing re-bakes; packing the grid takes 9-29 us a frame.",
                  gpu = "The chunk composite multiplies each pixel by the interpolated light and draws torch and headlight cones per pixel: +50..190 us a frame over the stock program on a laptop (Radeon 890M, 1080p).",
                  vram = "The per-square light grid round the camera lives on the GPU as texture arrays, about 12 MB.",
              } },
            { key = "pplAnalytic", label = "Per-pixel lighting: torch, headlight, lamp and fire shapes",
              tip = "Torches and headlights drawn from their cone, lamps and fires from their round falloff, pixel by pixel. Off: the game's per-square light only, smoothed between squares." },
            { key = "pplPointLights", label = "Per-pixel lighting: lamp and fire shapes",
              tip = "Lamps and fires drawn from their round falloff, pixel by pixel. Off: their light is the game's per-square light, smoothed between squares, and the chunk pictures they reach are drawn with the light-free program.",
              why = {
                  gpu = "Every chunk picture a lamp or fire reaches needs the lit program instead of the light-free one; off, only pictures near torches and headlights pay for per-pixel light.",
              } },
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
              tip = "Street lamps, house lamps and room lights are traced through the world's walls, windows, doorways, fences, floors and tree crowns: they cast real shadows (a fence, a stair, a building's corner), light reaches a room through its windows, walls facing a lamp catch more of it, and the sky lights rooms from their windows and doorways instead of a flat indoor darkening. Each chunk picture keeps its own light layer, worked out again only when a light or the buildings change. Needs a graphics card with OpenGL 4.3 (not macOS). Applies on the next launch.",
              why = {
                  gpu = "Compute traces the lamps and floods the sky only where something changed and relights those chunk layers: a still scene costs about what per-pixel lighting alone does, a 120 km/h night drive +31 us a frame (1.7 %) at 5K.",
                  vram = "A ~60 MB light volume round the camera plus a 144 MB cache of per-chunk light layers.",
              } },
            { key = "voxelLightTorchShadows", label = "Voxel lighting: torch shadows",
              tip = "A torch you carry casts shadows of walls, fences, floors and crowns too (traced again only when the torch moves)." },
            { key = "voxelLightSkyMethod", label = "Voxel lighting: daylight indoors",
              choices = { "flood", "cascades", "cones" }, note = { ["flood"] = "default" },
              tip = "How the sky's light reaches into rooms. Flood: spread from the open sky through windows and doorways. Cascades: rays towards the sky from every square, shared between neighbours (the sky through a window comes from where it faces). Cones: the same with every ray traced.",
              why = {
                  gpu = "Worked out again when buildings change: flood ~32 us of compute for the whole window, cascades ~0.9 ms and cones ~2 ms (17 rays a cell) on the desktop at 5K.",
              } },
            { key = "voxelLightSkyFloorPct", label = "Voxel lighting: least daylight deep inside rooms (%)",
              choices = { "20", "35", "50", "65", "80" }, note = { ["50"] = "default: as the base game's darkest rooms", ["20"] = "darker than the base game" },
              tip = "The least of the outdoor daylight a room keeps far from its windows and doorways. The base game keeps about half of it; lower makes the inside of large buildings darker than in the base game, higher brighter. Applies on the next launch." },
            { key = "voxelLightSkyDecayPct", label = "Voxel lighting: how far daylight reaches into rooms (%)",
              choices = { "60", "72", "80", "88" }, note = { ["72"] = "default" },
              tip = "How much of the daylight coming in through a window or doorway is left one square further into the room. Higher carries it deeper into the room before it falls to the least daylight above. Applies on the next launch." },
            { key = "voxelLightBounce", label = "Voxel lighting: bounced light",
              tip = "Lit floors and walls light their surroundings a little: a lamp pool fills the area round it, a lit window spills warm light outside." },
            { key = "pplShadows", label = "Per-pixel lighting: torch shadows",
              tip = "Posts, fences, furniture, stairs and walls cast shadows into your torch's beam in their own shape: a shadow map of everything round the torch, built from the world's own depth (also what is off screen or hidden behind a wall), in the same frame, so the shadows move with the torch. Applies at the next launch.",
              why = {
                  gpu = "The shadow map is built on the GPU only when the torch or the world round it changes (~0.2 ms at 5K on the desktop, zoomed in), kept while you stand; reading it adds ~0.05 ms (sharp edges) to ~0.1 ms (soft) a frame where the beam falls.",
                  vram = "A 2048 x 2048 depth map (16 MB) and an 8 MB list of the cells it draws.",
              } },
            { key = "pplShadowFilter", label = "Per-pixel lighting: torch shadow edges",
              choices = { "soft", "pcf" }, note = { ["soft"] = "default" },
              tip = "Soft: sharp where an object touches the ground and softer further away, as a real torch's lens draws them. pcf: crisp edges everywhere, a little cheaper. Applies at the next launch.",
              why = {
                  gpu = "Soft reads the shadow map 8 times a pixel instead of once where the beam falls: about +0.07 ms a frame on the desktop at 5K, zoomed in.",
              } },
            { key = "pplTorchFeetGlow", label = "Per-pixel lighting: torch glow at your feet",
              tip = "A torch in your hands also lights a small circle round your feet, behind and beside you. Off: only the beam in front of you is lit. Applies at once." },
        },
    },
    {
        title = "Light from the torch itself (flashlights, lanterns and weapon lights shine from the item you carry)", clip = "torchsource",
        entries = {
            { key = "torchSource", label = "Light from the torch itself",
              tip = "A flashlight, lantern, lighter or weapon light shines from the item in your hand, on your webbing or under your gun barrel, at its height, instead of from the middle of your feet: the beam starts at the lens and moves with your arm, a lantern held at your side lights that side, and with Per-pixel lighting the beam, its shadows, the glow in fog and the shadows other people cast from it all start there too. The game's own square-by-square lighting starts from the same spot (never through a wall) and holds still while you stand still. Applies at once." },
            { key = "torchSourceAim", label = "Light from the torch itself: beam direction",
              choices = { "look", "item" }, note = { look = "default: where you look", item = "where the item points (follows your hand)" },
              tip = "Where the beam points. Where you look (the game's own) keeps the beam steady on your aim; where the item points makes it follow the flashlight in your hand as you walk and turn, and a flashlight tilted down lights the ground nearer (with Per-pixel lighting)." },
            { key = "torchSourceSelfShadow", label = "Light from the torch itself: your body casts a shadow",
              tip = "With Per-pixel lighting: your own body shades the light you carry, so a lantern in one hand leaves the other side of you in a soft shadow, and a light at your hip does not light what is behind you. A torch held out in front is never tested. Applies on the next launch." },
        },
    },
    {
        title = "Relief (parallax textures: bricks, stones, planks and shingles catch the light)", clip = "relief",
        entries = {
            { key = "relief", label = "Relief (parallax textures)",
              tip = "The fine relief the art paints (mortar between bricks and stones, gaps between planks and floor tiles, roof shingles, cobbles) catches the light that moves: a low sun or the moon rakes it and the grooves fall into shade, a torch, headlight or lamp sweeping along a wall brings the stones out. Floors and walls only: furniture keeps its painted shading. Sun and moon: needs Sun shadows. Torches, headlights and lamps: needs Per-pixel lighting. Windows, Linux and macOS (on a Mac with \"macOS: OpenGL 4.1\" on, the default). Applies on the next launch.",
              why = {
                  gpu = "Each chunk picture is encoded once after it bakes (~27 us a bake) and the sun and moon relit four pictures at a time when they move a step (~100 us); a 120 km/h drive adds ~67 us a frame to the bakes.",
                  vram = "Two bytes for every texel of every chunk picture (the relief code and the baked light factor, ~2 MB a 1024 x 1024 picture), up to a 512 MB budget while streaming.",
              } },
            { key = "reliefDepthPct", label = "Relief: depth (%)",
              choices = { "50", "100", "150", "200" }, note = { ["150"] = "default" },
              tip = "How deep the grooves between bricks, planks and tiles are." },
            { key = "reliefSunPct", label = "Relief: in sunlight and moonlight (%)",
              choices = { "0", "50", "100", "150" }, note = { ["100"] = "default", ["0"] = "torches and lamps only" },
              tip = "How strongly the sun and the moon bring the relief out (the part of their light that reaches the surface directly: none in shade)." },
            { key = "reliefTorchShadowSteps", label = "Relief: grooves in torch shadow",
              choices = { "0", "2", "4" }, note = { ["0"] = "default (off)", ["4"] = "costs ~0.12 ms at 5K" },
              tip = "The grooves also fall into the shadow of their own edges in torch, headlight and lamp light (each lit pixel follows the light a few texels across the relief). The sun and the moon always have it (baked).",
              why = {
                  gpu = "Each torch-lit pixel follows the light a few texels across the relief: +124 us a frame in the composite with 4 steps, on the desktop at 5K.",
              } },
        },
    },
}

-- The Enhancements tab's keys apply as soon as Apply is pressed (Java: Config's live reload, pzopt.Enhancements), except
-- the two HDR output switches: on Linux they pick the window the game is started with.
local NEXT_LAUNCH_ONLY = { hdr = true, hdrAuto = true, godRaysRoofExact = true, carOccupant = true, carOccupantOcclusion = true, carOccupantLightPct = true,
    -- per-pixel lighting: read once at start-up (the chunk composite shader is patched when the game loads it)
    pixelLight = true, pplAnalytic = true, pplPointLights = true, pplNormals = true, pplWrapPct = true, pplSmooth = true,
    pplWetSpecular = true, pplSpecPct = true, pplShadows = true, pplShadowFilter = true, torchSourceSelfShadow = true,
    -- voxel lighting: its shader code is compiled into the per-pixel lighting programs at start-up
    voxelLight = true, voxelLightTorchShadows = true, voxelLightSkyMethod = true, voxelLightBounce = true,
    voxelLightSkyFloorPct = true, voxelLightSkyDecayPct = true,
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
        title = "Performance overlay (F9 or the \"Toggle performance overlay\" key binding; L3 + R3 on a controller)",
        entries = {
            { key = "overlaySampling", label = "Sample frame times and utilization (needed for F9 / L3 + R3)",
              tip = "Records every presented frame, times the GPU with GL timer queries and samples the CPU load twice a second on a background thread. Off by default: without it F9 (or L3 + R3 on a controller) only shows a notice. \"Show the overlay from boot\" and \"Log every presented frame\" turn it on too.",
              why = {
                  render = "Each presented frame gets a timestamp and a ring entry, and its draw commands are bracketed by a GL timer query (not on a Mac, where Apple's timer queries are slow).",
                  cores = "A low-priority thread wakes twice a second for the game and render threads' CPU time and the GPU busy share; the slow Windows load counters are read only while the overlay is shown or logging.",
              } },
            { key = "overlay", label = "Show the overlay from boot",
              tip = "Frame rate, frame-time tail (p99, p99.9, max, 1%-low, jitter, spikes), GPU busy share, game and render thread load, and a frame-time graph. The key (F9) or L3 + R3 on a controller toggles it any time.",
              why = {
                  cpu = "While shown the game thread recomputes the statistics and lays out the panel every 250 ms, queues the frame-graph bars every frame, and pauses briefly for each stack sample the tree and verdict take.",
                  render = "The render thread draws the panel texture (redrawn at each refresh) and the live frame bars every frame, on top of the sampling's timer queries.",
                  cores = "Showing it starts the background samplers: CPU / GPU load and power twice a second, and the game thread's stack 25 times a second for the tree and the verdict.",
              } },
            { key = "overlayLog", label = "Log every presented frame",
              tip = "Writes Zomboid/pzopt-overlay.out, one CSV row per frame in MangoHud's column names, for harness/analyze.py. Harness runs log regardless.",
              why = {
                  cpu = "The log keeps the game-thread stack sampler running even with the overlay hidden: each sample pauses the game thread briefly (25 a second by default).",
                  render = "The render thread formats and writes each frame's row a few frames late, once that frame's GPU timer has resolved.",
                  cores = "The background threads keep sampling the load, the power counters and the stacks for the files while the overlay is hidden.",
                  disk = "One CSV row a frame to Zomboid/pzopt-overlay.out through a 64 KB buffer, plus pzopt-gamethread.out each second and pzopt-power.out twice a second.",
              } },
            { key = "overlayStats", label = "Frame statistics",
              choices = { "off", "fps", "tails", "full" },
              note = { fps = "the fps line", tails = "+ p99 / p99.9 / max, 1%-low, jitter, spikes", full = "+ GPU, thread, process and machine load, heap" },
              tip = "The lines at the top of the overlay." },
            { key = "overlayPower", label = "Power draw",
              tip = "A line with the watts the machine draws while you play and the energy each frame costs (J/frame = watts / fps): CPU package, GPU board power, an AMD APU's socket, or the whole machine while a laptop runs on battery. Read from the counters the system exposes: NVIDIA GPUs through the driver (Windows and Linux), AMD GPUs and APUs, and on Linux the CPU's RAPL counters, which only root may read on most distributions (the line then says CPU n/a). While the frame log is on it also writes Zomboid/pzopt-power.out twice a second." },
            { key = "overlayTree", label = "Game-thread tree",
              choices = { "off", "0", "3", "5", "8" },
              note = { ["0"] = "phases only", ["3"] = "3 sub-phases per phase", ["5"] = "5 sub-phases per phase", ["8"] = "8 sub-phases per phase" },
              tip = "What the game thread is doing, from its call stack sampled on a background thread: the phases (update / render / lighting) with their share of the time, under each the biggest sub-phases (chunk bakes, zombies, UI draw, frame hand-off...) with a bar, the wait share in red and the hottest methods. Also logged per second to Zomboid/pzopt-gamethread.out for harness/analyze.py.",
              why = {
                  cpu = "The tree needs the game thread's stack sampled (25 a second by default): each sample stops it at its next safepoint for a stack walk of tens of microseconds.",
                  cores = "The sampler thread walks each stack and folds it into phases, sub-phases and hot methods (~80 us a sample).",
              } },
            { key = "overlayVerdict", label = "Verdict line",
              choices = { "off", "short", "detailed" },
              note = { short = "\"at the cap\" / \"GPU bound\" / \"nothing saturated\"", detailed = "+ the two biggest game-thread sub-phases when it is the game thread" },
              tip = "What is holding the frame rate below the cap." },
            { key = "overlayGraph", label = "Frame-time graph",
              choices = { "off", "240", "480", "960" },
              note = { ["240"] = "last 240 frames (480 px)", ["480"] = "last 480 frames (960 px)", ["960"] = "last 960 frames (1920 px)" },
              tip = "A bar per presented frame (green under 1.1x the cap budget, amber under 2x, red above; GPU time in blue) with ms ticks and the budget line.",
              why = {
                  cpu = "With the graph's redraw rate at 0 (the default) the game thread queues one sprite per bar every frame, plus the GPU time bars: about 480 for the last 240 frames.",
                  render = "The render thread draws those bar sprites every frame.",
              } },
            { key = "overlayGraphHz", label = "Frame-time graph redraws per second",
              choices = { "0", "15", "30", "60" },
              note = { ["0"] = "every frame (one sprite per bar)", ["15"] = "15 times a second", ["30"] = "30 times a second", ["60"] = "60 times a second" },
              tip = "With \"Draw the overlay as one texture\" on, the frame-time bars are redrawn into that texture this often instead of drawn as ~480 sprites every frame. 0 draws them every frame.",
              why = {
                  cpu = "15 to 60 redraws a second into the panel texture replace the ~480 bar sprites the game thread queues every frame at 0 (the default); a lower rate is cheaper and the bars move in steps.",
                  render = "The render thread draws one textured quad a frame and repaints the graph's rectangle only at this rate, instead of every bar every frame.",
              } },
            { key = "overlayFlame", label = "Game-thread flame graph",
              choices = { "off", "right", "right-wide", "below" },
              note = { right = "column beside the statistics, 900 px", ["right-wide"] = "column beside the statistics, 1400 px", below = "under the frame graph, panel width" },
              tip = "The last 5 s of stack samples as a flame graph: root (GameWindow.frameStep) at the bottom, callees above, width = share of the time, biggest first from the left; update green, render blue, lighting amber, pzopt frames magenta. Off by default. harness/flamegraph.py draws a whole run as an SVG.",
              why = {
                  cpu = "It needs the game thread's stack samples (each a brief pause); with \"Draw the overlay as one texture\" off every box and label is a sprite every frame, the overlay's heaviest element.",
                  cores = "The sampler thread keeps the last 5 s of folded stacks for the graph.",
              } },
            { key = "overlayFlameDepth", label = "Game-thread flame graph rows",
              choices = { "12", "16", "24", "32", "48" },
              tip = "How many call levels above GameWindow.frameStep the flame graph shows." },
            { key = "gameThreadProfileHz", label = "Game-thread stack samples per second",
              choices = { "10", "25", "50", "100", "200", "500" },
              tip = "Higher resolves short phases sooner. 25 (the default) gives 125 samples over the overlay's 5 s window. Sampling only runs while the overlay is shown or its log is on, and only when the tree, the flame graph, the detailed verdict or the log needs it.",
              why = {
                  cpu = "Each sample stops the game thread at its next safepoint for a stack walk: about 0.15 ms a sample on a MacBook, so 100 a second takes ~1.5 % of its time and 25 about a quarter of that.",
                  cores = "The sampler thread walks and folds each stack (~80 us a sample), so its load grows with the rate.",
              } },
            { key = "overlayTexture", label = "Draw the overlay as one texture",
              tip = "The overlay's text, tree and flame graph only change four times a second, so they are drawn into an offscreen texture then and each frame shows that texture plus the live frame-time bars. Off draws every letter and box as its own sprite every frame (thousands of quads).",
              why = {
                  cpu = "The game thread queues one quad a frame instead of thousands of glyph and box sprites: drawn as sprites they cost 12 % of the frame rate on a game-thread-bound laptop.",
                  render = "The render thread draws one textured quad instead of thousands of sprites and repaints the texture only at each refresh.",
                  gpu = "One quad instead of thousands of small blended sprites every frame.",
              } },
            { key = "overlayRefreshMs", label = "Overlay refresh interval (ms)",
              choices = { "100", "250", "500", "1000" },
              tip = "How often the overlay's numbers, game-thread tree and verdict are recomputed and its texture redrawn. The frame-time graph has its own rate.",
              why = {
                  cpu = "Each refresh sorts the window's frame times, merges the stack tree and lays out the panel on the game thread: a longer interval does it less often (250 ms: four times a second), at the price of slower numbers.",
              } },
            { key = "overlayCorner", label = "Overlay corner",
              choices = { "tl", "tr", "bl", "br" },
              tip = "Where the overlay sits: top-left, top-right, bottom-left, bottom-right." },
            { key = "overlayFont", label = "Overlay font",
              choices = { "auto", "CodeMedium", "CodeSmall", "CodeLarge", "Small", "Medium", "Large" },
              tip = "The UI font the overlay text uses. auto follows the screen height: CodeSmall under 1000 px, CodeMedium under 1800, CodeLarge above. Whatever the font, the panel fits the screen: the frame graph shows fewer frames, the flame graph keeps up to a third of the width (hints and legend are cut to the rest) or moves under the frame graph and long lines are cut when it would not." },
        },
    },
    {
        title = "Performance overlay: fps colour",
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
        title = "Console log",
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
-- names the runs). A section's `clip` is the default for its keys; KEY_CLIP picks another for one key. Only settings
-- that change the picture have one (2026-10-20, the maintainer): the others say "No visual comparison available"
-- (their fps route clips are gone).
local CLIP_TITLES = {
    -- the performance overlay's elements: the spinning route with the overlay off / with every default element on
    -- (overlayFont=Large, each clip a crop of the same capture; the overlay shows its own numbers, no burned counter)
    overlay = "The whole overlay with every element on: statistics, game-thread tree, verdict, frame graph, flame graph",
    ovstats = "The statistics lines: fps, frame time, p99 / p99.9 / max, 1 %-low, jitter, spikes, GPU and thread loads",
    ovtree = "The game-thread tree: phases, their sub-phases and hot methods, biggest first, waits in red",
    ovverdict = "The verdict line: what holds the frame rate below the cap",
    ovgraph = "The frame-time graph: one bar per presented frame, GPU time in blue, the budget line and ms ticks",
    ovflame = "The flame graph: the last 5 s of game-thread stacks, root at the bottom, biggest first from the left",
    -- the Enhancements tab: every optimization on in both runs, only the enhancement differs (2026-09-25, runs enh-*)
    upscale = "Rosewood, camera spinning, uncapped, max zoom, RTX 4090 at 5120x2160: the world at the screen size vs rendered at 67 % and upscaled with FSR 1.0",
    fsrzoom = "A furnished Rosewood house at noon, zoom 1, still camera, a 1:1 pixel crop around the player: FSR 1.0 at quality (67 %, sharpening 80) against the native picture",
    dlss = "Rosewood, camera spinning, uncapped, max zoom, RTX 4090 at 5120x2160: the world at the screen size vs DLSS at the defaults (preset E, 67 % output, RCAS finish)",
    dlsszoom = "A furnished Rosewood house at noon, zoom 1, still camera, a 1:1 pixel crop around the player: DLSS at the defaults against the native picture",
    hdr = "Night, fires beside a police car with its light bar: the HDR side is tone-mapped to fit this SDR preview, so its lights look brighter, not as bright as on an HDR screen",
    hdrday = "River shore at 15:00, clear sky, a crop of the pier and the water: the sun glitter on the water, tone-mapped to fit this SDR preview",
    ao = "A Rosewood living room at noon, zoom 1, the player turning in place: ambient occlusion off vs on",
    -- darkness floor, remembered places, colour grading (2026-09-26, runs cap-*: the game's own frames, the player turning in place)
    darkness = "Rosewood house at 01:00, the player turning in place: darkness floor 20 %, remembered places and colour grading together",
    memory = "Rosewood house at 13:00, the player turning in place: what is out of sight is drawn grey, dimmer and cooler (colour grading off)",
    grade = "Rosewood house at 20:30, clear sky, the player turning in place: colour grading off vs on (the warm evening look)",
    -- voxel lighting (2026-10-19, the game's own frames: stock vs every default + per-pixel and voxel lighting)
    voxel = "A parking lot at 01:00, street lamps and room lights on, the camera panning east: stock vs voxel lighting (lamps and room lights traced through walls, windows and fences)",
    -- god rays through doors (2026-10-18, runs grmenu-*: the game's own frames at 1:1, the door toggled every 2 s)
    godrays = "The Riverside fire station at 15:00, zoom 1: the sun comes in through the garage doors as they open and close",
    godraysdoor = "A shop door at 17:00, zoom 1, the player just inside: the door settings off vs on (the light follows the door's swing, the open door blocks its share, the player's shadow in the shaft, dust stirred up)",
    godraysspill = "A Rosewood house at 23:00 in dark fog, its lamps on, zoom 1: the rooms' lamplight spills out of the windows and the opening door into the fog",
    -- the benchmark's scenes (2026-10-20, the run planner's card; harness/menu-gifs.py kind `scene`: one capture run per
    -- scene, the GIF at 512x216 and the AVIF at 1024x432 in the same bytes)
    ["bench-spin"] = "Rosewood at noon, south through town at the widest zoom, the view turning 90 degrees a second",
    ["bench-drive120"] = "120 km/h east on KY-60 at noon, the widest zoom",
    ["bench-louisville"] = "Downtown Louisville, about 2,000 zombies, a slow turning walk at the widest zoom",
    ["bench-drive120south"] = "120 km/h through Rosewood at noon, past the shops on North Main Street, the widest zoom",
    ["bench-horde"] = "The Riverside pier at noon: the horde of 150 fast zombies reaching the player",
    ["bench-sweep"] = "Rosewood at noon, the camera turning in place at the widest zoom: every Visuals feature the sweep measures on",
    visuals = "A Rosewood house at 08:00, zoom 1: every Visuals feature off vs on (sun shadows, ambient occlusion, god rays, per-pixel light, colour grading)",
    -- one clip per Visuals setting (2026-10-20, runs mc-*: the game's own frames, the two runs on the same build, save, spot,
    -- hour and route with an empty options file, only the setting differs; docs/menu-clips-2026-10-20.md, Jev judged each)
    ppl = "A parking lot at 23:00, the lamps on, a hand torch, the player turning in place, zoom 1: per-pixel lighting off vs on",
    torchsource = "The same lot at 01:00, a hand torch and a lantern, zoom 0.5, a crop round the player: torch source off vs on (per-pixel lighting on in both)",
    relief = "A brick facade at 11:00, zoom 1, a 1:1 pixel crop: relief off vs on (per-pixel lighting and sun shadows on in both)",
    sunshadows = "A Rosewood house at 16:30, clear sky, zoom 1, the player turning in place: sun shadows off vs on",
    clouds = "Rosewood at 16:00, max zoom, half cloud cover, the clouds at 8x speed: cloud shadows off vs on (sun shadows on in both)",
    moon = "A full moon at 23:00, zoom 1: moon shadows off vs on (sun shadows on in both)",
    reflections = "The Riverside pier at 15:00, zoom 1, a crop of the river: reflections off vs on",
    ripples = "Walking through puddles in the rain at noon, zoom 0.5: puddle ripples off vs on (round raindrop rings, footsteps)",
    wakes = "Driving at 30 km/h through puddles at noon, zoom 0.75: car wakes off vs on (the trail behind the car)",
    carglass = "Three parked cars at 10:30, zoom 0.5: car glass off vs on (one run switching every 4 s: the same frame, only the glass)",
    occupant = "Driving at 60 km/h at 11:00, zoom 0.5, car glass on in both: the people inside off vs on",
    outlines = "A crowd of 200 round the church at noon, zoom 1, a crop: outlines of the hidden parts of zombies off vs on",
    mirrors = "A Rosewood bedroom at noon, zoom 0.5, a 1:1 pixel crop: mirrors and windows off vs on (the panes reflect the room)",
    props = "A glass case, a TV, a glass door and a fridge by the road, walking east, zoom 0.5: reflective props off vs on",
    sway = "A Rosewood yard at noon in the wind, zoom 1: foliage sway off vs on",
    darkfloor = "A Rosewood house at 01:00, the player turning in place: darkness floor 0 % vs 20 % (remembered places and grading off)",
    blood = "A Rosewood living room at 15:00, fresh blood on the floor, the player turning, zoom 0.5, a 1:1 pixel crop: wet blood off vs on (reflections on in both)",
    treecut = "Driving at 60 km/h past trees at noon, zoom 1: Build 42.21's tree cutaway while driving vs this setting off (the default)",
}
-- Clips whose stock side is a shared GIF (one stock run for several group clips): <STOCK_FILE[clip]>-stock.gif.
local STOCK_FILE = { upscale = "native", dlss = "native", fsrzoom = "nativezoom", dlsszoom = "nativezoom" }
-- The captions over the two clips; the overlay clips are "off" / "on" rather than stock / optimized.
local CLIP_SIDES = {
    default = { "STOCK GAME", "OPTIMIZED (every optimization on)" },
    overlay = { "OVERLAY OFF", "OVERLAY ON (F9 / L3 + R3)" },
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
    visuals = { "VISUALS OFF", "EVERY VISUALS FEATURE ON" },
    ["bench-sweep"] = { "VISUALS OFF", "EVERY VISUALS FEATURE ON" },
    ppl = { "PER-PIXEL LIGHTING OFF", "PER-PIXEL LIGHTING ON" },
    torchsource = { "LIGHT FROM THE PLAYER", "LIGHT FROM THE TORCH" },
    relief = { "RELIEF OFF", "RELIEF ON" },
    sunshadows = { "SUN SHADOWS OFF", "SUN SHADOWS ON" },
    clouds = { "CLOUD SHADOWS OFF", "CLOUD SHADOWS ON" },
    moon = { "MOON SHADOWS OFF", "MOON SHADOWS ON" },
    reflections = { "REFLECTIONS OFF", "REFLECTIONS ON" },
    ripples = { "STOCK RIPPLES", "PUDDLE RIPPLES ON" },
    wakes = { "CAR WAKES OFF", "CAR WAKES ON" },
    carglass = { "CAR GLASS OFF", "CAR GLASS ON" },
    occupant = { "NOBODY INSIDE (STOCK)", "THE PEOPLE INSIDE ON" },
    outlines = { "OUTLINES OFF", "OUTLINES ON" },
    mirrors = { "MIRRORS AND WINDOWS OFF", "MIRRORS AND WINDOWS ON" },
    props = { "REFLECTIVE PROPS OFF", "REFLECTIVE PROPS ON" },
    sway = { "FOLIAGE SWAY OFF", "FOLIAGE SWAY ON" },
    darkfloor = { "DARKNESS FLOOR 0 %", "DARKNESS FLOOR 20 %" },
    blood = { "WET BLOOD OFF", "WET BLOOD ON" },
    treecut = { "GAME'S 42.21 RULE (SEE-THROUGH)", "OFF, THE DEFAULT (TREES STAY)" },
}
-- The line under the clips: what is the same in both, and what the burned-in number is (clips without one say so).
local CLIP_NOTES = {
    fsrzoom = ". Same save, spot and machine; the picture is the point, no counter.",
    dlsszoom = ". Same save, spot and machine; the picture is the point, no counter.",
    hdr = ". Same save, spot and machine; no counter.",
    hdrday = ". Same save, spot and machine; no counter.",
    ao = ". Same save, spot and hour, frames lined up; no counter.",
    darkness = ". Same save, spot and hour, frames lined up; no counter.",
    memory = ". Same save, spot and hour, frames lined up; no counter.",
    grade = ". Same save, spot and hour, frames lined up; no counter.",
    voxel = ". Same save, route and hour, frames lined up; no counter.",
    godrays = ". Same save, spot and hour, frames lined up; no counter.",
    godraysdoor = ". Same save, spot and hour, frames lined up; no counter.",
    godraysspill = ". Same save, spot and hour, frames lined up; no counter.",
    ["bench-spin"] = ". The optimized defaults, the game's own frames; no counter.",
    ["bench-drive120"] = ". The optimized defaults, the game's own frames; no counter.",
    ["bench-louisville"] = ". The optimized defaults, the game's own frames; no counter.",
    ["bench-drive120south"] = ". The optimized defaults, the game's own frames; no counter.",
    ["bench-horde"] = ". The optimized defaults, the game's own frames; no counter.",
    ["bench-sweep"] = ". The game's own frames; no counter.",
    visuals = ". Same save, spot and hour, frames lined up; no counter.",
    ppl = ". Same save, spot and hour, frames lined up; no counter.",
    torchsource = ". Same save, spot and hour, frames lined up; no counter.",
    relief = ". Same save, spot and hour, frames lined up; no counter.",
    sunshadows = ". Same save, spot and hour, frames lined up; no counter.",
    clouds = ". Same save, spot and hour, frames lined up; no counter.",
    moon = ". Same save, spot and hour, frames lined up; no counter.",
    reflections = ". Same save, spot and hour, frames lined up; no counter.",
    ripples = ". Same save, spot and hour, frames lined up; no counter.",
    wakes = ". Same save, route and hour, frames matched by route time; no counter.",
    occupant = ". Same save, route and hour, frames matched by route time; no counter.",
    outlines = ". Same save, spot and hour, frames lined up; no counter.",
    mirrors = ". Same save, spot and hour, frames lined up; no counter.",
    props = ". Same save, spot and hour, frames lined up; no counter.",
    sway = ". Same save, spot and hour, frames lined up; no counter.",
    darkfloor = ". Same save, spot and hour, frames lined up; no counter.",
    blood = ". Same save, spot and hour, frames lined up; no counter.",
    treecut = ". Same save, route and hour, frames matched by route time; no counter.",
    carglass = ". One run, the same cars and frame, the glass switched; no counter.",
}
local function clipSides(clip)
    if CLIP_SIDES[clip] then return CLIP_SIDES[clip] end
    if string.sub(clip, 1, 2) == "ov" then return CLIP_SIDES.overlay end
    return CLIP_SIDES.default
end
local KEY_CLIP = {
    memoryTint = "memory", memoryTintPct = "memory", memoryLightPct = "memory", colorGrading = "grade", colorGradingPct = "grade",
    fsrSharpnessPct = "fsrzoom", dlssWaterCurrent = "dlss", dlssWaterHistoryPct = "dlss", dlssPreset = "dlss", dlssOutputPct = "dlss", dlssOutputFilter = "dlsszoom", dlssSharpen = "dlsszoom",
    hdrSunPct = "hdrday", hdrGlintPct = "hdrday",
    godRaysDoorLeaf = "godraysdoor", godRaysDoorSwingMs = "godraysdoor", godRaysCharShadows = "godraysdoor", godRaysDoorDustPct = "godraysdoor",
    godRaysSpill = "godraysspill", godRaysSpillPct = "godraysspill",
    -- the Visuals keys without their section's clip (2026-10-20, docs/menu-clips-2026-10-20.md; per-pixel lighting, torch source
    -- and relief show their sections' clips "ppl" / "torchsource" / "relief")
    voxelLight = "voxel", voxelLightTorchShadows = "voxel", voxelLightSkyMethod = "voxel", voxelLightBounce = "voxel", voxelLightSkyFloorPct = "voxel", voxelLightSkyDecayPct = "voxel",
    cloudShadows = "clouds", cloudOpacityPct = "clouds", cloudSpeedPct = "clouds", cloudScalePct = "clouds", moonShadows = "moon", moonShadowPct = "moon",
    ripplesWake = "wakes", ripplesVehicles = "wakes", carOccupant = "occupant", carOccupantOcclusion = "occupant", carOccupantLightPct = "occupant",
    mirrorsProps = "props", mirrorsPropGlassPct = "props", darknessFloorPct = "darkfloor", darknessFloorBasements = "darkfloor", colorGradingNightPct = "grade",
    driveTreeCutaway = "treecut",
    windSpriteSway = "sway",
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
-- bars describe moving it away from stock in the direction the tab offers. Each non-zero level has its reason in the entry's
-- `why` (tests/lua/effect-why.lua).
local EFFECTS = {
    corePlacement = { cores = -1 },
    workerQosMac = { cpu = -2, cores = 1 },
    gpuPstate = { gpu = -1 },
    jitSteady = { cores = -2 },
    gcHeap = { cpu = -1, ram = 1 },
    gcHeapFixed = { ram = 1 },
    luaGcNoop = { cpu = -1 },
    gcPreTouch = { ram = 1, load = 1 },
    gcMode = { cpu = -1, cores = -1 },
    gcPauseMs = {},
    gcG1Tuned = { cpu = -1 },
    gcSlack = { cpu = -1 },
    gcSlackQueue = {},
    lightingSyncPark = { cores = -1 },
    visBlurReduce = { gpu = -1 },
    enabled = { cpu = -3, render = -3, gpu = -1, cores = 2, ram = 1, disk = 1, load = -3, chunks = -2 },
    -- chunk textures
    treesInChunkTexture = { cpu = -3, render = -1, gpu = -2, chunks = 1 },
    windSpriteSway = { cpu = -3, render = -3, gpu = -2 },
    treeBakeMaxChunksPerSec = { cpu = -1, gpu = 1, chunks = -1 },
    treeBakeDirect = {},
    treeBakePass = { cpu = 1, gpu = 1 },
    treeAppend = { cpu = -2, gpu = -2, chunks = -1 },
    driveTreeCutaway = { cpu = 1, gpu = 2 },
    edgeTestFast = { cpu = -1 },
    treeRebakeLazy = { cpu = -1, gpu = -2 },
    treeRebakeLingerMs = {},
    treeCutawayReach = { cpu = -1, gpu = -2 },
    treeCutawayReachPx = {},
    bloodBake = { cpu = -2, render = -2, gpu = -1, chunks = -2 },
    bloodAppend = { cpu = -2, gpu = -1 },
    bloodSettleSec = { cpu = 1 },
    bloodFadeFix = { cpu = -1, gpu = -1 },
    bloodAppendVegetation = { cpu = -1 },
    bloodAppendPlants = { cpu = -2, gpu = -1 },
    bloodRebakeCoalesceMs = { cpu = -1, gpu = -1 },
    windowsInChunkTexture = { cpu = -1 },
    translucentTilesInChunkTexture = { cpu = -3, render = -2, gpu = -1 },
    translucentLightsPerFrame = {},
    glassTilesPerFrame = {},
    floorDecalsPerFrame = {},
    translucentGridsPerFrame = {},
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
    zoomPlaceholder = {},
    zoomFrameMs = {},
    zoomEaseMs = {},
    zoomEase = {},
    lightingStrongDelta = { cpu = 1, gpu = 1 },
    lightingStrongBudget = { cpu = -1, gpu = -2 },
    lightingStrongFrameMs = { gpu = -1 },
    entityUpdateParallel = { cpu = -2, cores = 1 },
    renderPrepParallel = { cpu = -1, cores = 1 },
    pplPackParallel = { cpu = -1, cores = 1 },
    pplTorchNearChunk = { cpu = -1 },
    schedulerClassifyParallel = { cpu = -1, cores = 1 },
    schedulerFillParallel = { cpu = -1, cores = 1 },
    playerLosParallel = { cpu = -2, cores = 1 },
    zombieStatsFold = { cpu = -1 },
    visPolyAsync = { cpu = -1, cores = 1 },
    aoContextParallel = { cpu = -1, cores = 1 },
    translucentOrderCache = { cpu = -1 },
    losLightPrefetch = { cpu = 1, cores = 1 },
    zombieSpawnSpread = { cpu = -1 },
    zombieModelAddBudgetUs = { cpu = -1 },
    burstGovernor = { cpu = -1 },
    entityUpdatePipeline = { cores = 1 },
    entityUpdatePlayerFirst = {},
    entityUpdateMoreStates = { cpu = -1, cores = 1 },
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
    postupdateNoopSkip = { cpu = -1 },
    postupdateEligibleOnWorker = { cpu = -1, cores = 1 },
    postupdateVehicleOnWorker = { cpu = -1, cores = 1 },
    postupdateFuse = { cpu = -2, cores = 1 },
    postupdateClean = { cpu = -2, cores = 1 },
    separateEdgeCache = { cpu = -1, cores = -1 },
    luaSilentSkip = { cpu = -1 },
    luaZombieLod = { cpu = -2 },
    luaZombieBudgetUs = { cpu = -2 },
    luaZombieMaxGapMs = { cpu = -1 },
    luaZombieHz = { cpu = -2 },
    luaZombieLodNear = { cpu = 1 },
    luaZombieLodHz1 = { cpu = 1 },
    luaZombieLodHz2 = { cpu = 1 },
    luaZombieWriters = {},
    luaZombieTimeComp = {},
    luaZombieCaptureDue = { cpu = -1 },
    luaZombieCheapUs = {},
    luaZombieCostShare = {},
    luaZombieDirtySkip = { cpu = -1 },
    luaZombieBatchApi = {},
    luaCollideDedupe = {},
    randPerWorker = { cpu = -1, cores = -1 },
    lazyPose = { cpu = -1 },
    animatorPipeline = { cpu = -1, cores = 1 },
    animBatchAsync = { cpu = -1, cores = 1 },
    guardedCallbacks = { cpu = -1, cores = 1 },
    modelLockPerInstance = { cpu = -1, cores = 1 },
    poolStatsBatched = { cores = 1 },
    ecsLookupFast = { cpu = -1 },
    lookupMemo = { cpu = -1 },
    actionConditionFast = { cpu = -1 },
    skinTransformsPrecompute = { cpu = -1, cores = 1 },
    skinPalettePrecompute = { cpu = -1, cores = 1 },
    shadowPrep = { cpu = -1, cores = 1 },
    boneIndexCache = { cpu = -1 },
    lightingReadParallel = { cpu = -2, cores = 1 },
    zombieCullSortFast = { cpu = -1 },
    vehicleCull = { cpu = -1 },
    nearVehicleGrid = { cpu = -1 },
    separateMemo = {},
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
    stateParamMemo = { cpu = -1 },
    actionGroupCache = { cpu = -1 },
    profilerThreadMemo = { cpu = -1 },
    zombieSimLodTiles = { cpu = -3 },
    zombieSimLodSteps = { cpu = -1 },
    zombieCheckSpread = { cpu = -1 },
    zombieLodDynamic = { cpu = -2, render = -1, gpu = -2 },
    zombieLodMin3d = {},
    zombieLodHysteresis = {},
    zombieLodFixed3d = { cpu = -2, render = -1, gpu = -2 },
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
    cutawayRadius = { cpu = -1 },
    gridStackInterval = { cpu = -1 },
    lightSwitchCheckFrames = { cpu = -1 },
    rainTiles = { cpu = -3, render = -3 },
    puddleCache = { cpu = -3, ram = 1 },
    rainSplashesFast = { cpu = -1 },
    puddleEarlyZ = { gpu = -2 },
    puddleVbo = { render = -3, gpu = -2, vram = 1 },
    puddleCacheFrames = { cpu = -1 },
    weatherMaskIdleSkip = { cpu = -1, gpu = -1 },
    weatherFxScalePct = { gpu = -1, vram = -1 },
    fogPass = { cpu = -1, render = -2, gpu = -3, vram = 1 },
    ambientOcclusion = { gpu = 1, vram = 1 },
    sunShadows = { render = 1, gpu = 2, vram = 1 },
    sunShadowCharacters = { gpu = 1 },
    sunShadowStaticLights = { cpu = 1, gpu = 1 },
    sunShadowRoomLights = { cpu = 1, gpu = 1 },
    sunShadowMeshes = { render = 1, gpu = 1, vram = 1 },
    sunShadowRate = { render = 1, gpu = 1 },
    entityShadows = { render = 1, cores = 1, gpu = 1 },
    entityShadowCasters = { gpu = 1 },
    entityShadowAoPct = { render = 1, cores = 1 },
    cloudShadows = { gpu = 1 },
    reflections = { render = 1, gpu = 1, vram = 1 },
    reflectionsFlow = {},
    reflectionsFlowPct = {},
    carGlass = { render = 1, gpu = 1 },
    mirrors = { render = 1, gpu = 1, vram = 1 },
    mirrorsWindows = { gpu = 1 },
    mirrorsWallMirrors = { gpu = 1 },
    mirrorsWindowPct = {},
    mirrorsProps = { gpu = 1 },
    mirrorsPropGlassPct = {},
    mirrorsViewLateralPct = {},
    mirrorsViewDropPct = {},
    mirrorsModels = { render = 1, gpu = 1 },
    mirrorsGeometry = { vram = 1 },
    carGlassReflectPct = {},
    carGlassInteriorPct = {},
    carGlassSunPct = {},
    carGlassRainPct = {},
    carOccupant = { render = 1 },
    carOccupantOcclusion = {},
    carOccupantLightPct = {},
    occludedZombieOutlines = { render = 1, gpu = 1 },
    occludedOutlineIgnorePlants = {},
    occludedOutlineWidth = {},
    occludedOutlineColour = {},
    occludedOutlineOpacityPct = {},
    ripples = { cores = 1, gpu = 1 },
    ripplesStrengthPct = {},
    ripplesVehicles = {},
    ripplesPerSec = {},
    ripplesMode = {},
    ripplesRain = { gpu = -1 },
    ripplesRainPct = {},
    ripplesWake = { gpu = 1 },
    bloodWet = { cpu = 1, gpu = 1 },
    bloodWetMinutes = { gpu = 1 },
    bloodReflectPct = {},
    bloodSheenPct = {},
    bloodGlintPct = {},
    godRays = { cpu = 1, gpu = 1, vram = 1 },
    godRaysLocal = { gpu = 1 },
    godRaysCharShadows = { gpu = 1 },
    godRaysSpill = { gpu = 1 },
    godRaysBouncePct = { gpu = 1 },
    foliageSway = { gpu = 1, vram = 1 },
    groundHexTiling = {},
    groundMacroPct = {},
    groundHeightBlend = {},
    groundScatter = {},
    groundScatterDensityPct = {},
    glints = { gpu = 1 },
    glintPass = { gpu = 1 },
    glintAsync = { cpu = -1, cores = 1 },
    glintPct = {},
    glintHdrPct = {},
    groundCoverFrost = {},
    groundCoverWind = { gpu = 1 },
    groundCoverPlacement = {},
    groundCoverAssets = {},
    groundTracks = { cpu = 1, gpu = 1, vram = 1 },
    groundTracksPersist = { disk = 1 },
    groundTracksDays = {},
    groundTracksLook = {},
    groundTracksWater = {},
    groundTracksPom = {},
    groundTracksAo = {},
    groundTracksShadowSteps = {},
    groundTracksStrengthPct = {},
    groundTracksVehicles = {},
    groundTracksFeet = {},
    groundTracksBodies = {},
    groundTracksGrass = {},
    groundTracksRebakesPerSec = {},
    groundTracksRealtime = { gpu = 1 },
    grassBlades = { gpu = 2, vram = 1 },
    grassBladeRadius = { gpu = 1 },
    grassBladeDensityPct = { gpu = 1 },
    foliageSwayTaps = { gpu = 1 },
    relief = { gpu = 1, vram = 2 },
    reliefTorchShadowSteps = { gpu = 2 },
    darknessFloorPct = { cpu = 1 },
    memoryTint = {},
    colorGrading = { cores = 1, gpu = -1 },
    spriteFilter = { gpu = 1 },
    spriteFilterMin = { gpu = -1 },
    pixelLight = { cpu = -1, gpu = 1, vram = 1 },
    voxelLight = { gpu = 1, vram = 1 },
    voxelLightSkyMethod = { gpu = 1 },
    pplPointLights = { gpu = 1 },
    pplShadows = { gpu = 2, vram = 1 },
    pplShadowFilter = { gpu = 1 },
    pplTorchFeetGlow = {},
    torchSource = {},
    torchSourceAim = {},
    torchSourceSelfShadow = {},
    aoScalePct = { vram = 1 },
    vrr = { cpu = -1, gpu = -1 },
    vrrCap = { cpu = -1, gpu = -1 },
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
    borderlessFullscreen = {},
    macPresent = { render = 1, gpu = 1, vram = 1 },
    limiterSleep = { cpu = -1 },
    upscaler = { gpu = -3, vram = 1 },
    upscalerQuality = { gpu = -2 },
    upscalerScalePct = { gpu = -2 },
    fsrSharpnessPct = {},
    dynRes = { gpu = -2, vram = 1 },
    dynResUpscaler = {},
    dynResTargetPct = { gpu = -1 },
    dynResMinPct = { gpu = -1 },
    dynResMaxPct = { gpu = -1 },
    dynResFps = { gpu = -1 },
    dynResController = {},
    upscalerObjectMv = {},
    dlssWaterCurrent = { gpu = 1, vram = 1 },
    dlssWaterHistoryPct = {},
    hdr = { render = 1, cores = 1, gpu = 1, vram = 1 },
    hdrAuto = { render = 1, cores = 1, gpu = 1, vram = 1 },
    hdrLightPct = {},
    hdrBloomPct = { gpu = 1 },
    hdrGlintPct = { gpu = 1 },
    dlssPreset = { gpu = -2 },
    dlssSharpen = {},
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
    uiTickStagger = { cpu = -1 },
    uiLuaFast = { cpu = -1 },
    modProfile = { cpu = -1 },
    modCompat = { load = 1 },
    uiRetainedMods = { cpu = -1 },
    luaWorkerGate = {},
    luaEventDispatchFast = { cpu = -1 },
    luaEventSkipEmpty = {},
    luaTickHz = { cpu = -2 },
    luaTickAdaptive = { cpu = -1 },
    luaTickInputWake = { cpu = -1 },
    luaErrorThrottle = {},
    hotsaveWarmup = { cpu = -1, load = 1 },
    mapStreetMemo = { cpu = -1 },
    mapStreetCache = { cpu = -1 },
    mapVisitedFast = { cpu = -1 },
    luaJit = { cpu = -2 },
    luaJavaFast = { cpu = -1 },
    luaFastTable = { cpu = -1 },
    luaPatternFast = { cpu = -1 },
    luaStringFast = { cpu = -1 },
    luaIndexCache = {},
    luaInternConstants = {},
    luaSkipEmpty = {},
    persistentVbo = { render = -3 },
    persistentVboFrameSync = { render = -2 },
    persistentVboSlots = { render = -1, vram = 1 },
    vboBatchKb = { render = -2, vram = 1 },
    vboFastQuads = { render = -1 },
    -- multiplayer
    luaChecksumExempt = {},
    -- updates
    updateCheck = {},
    updateFromWorkshop = {},
    updatePrefetch = {},
    -- overlay
    overlaySampling = { render = 1, cores = 1 },
    overlay = { cpu = 1, render = 1, cores = 1 },
    overlayLog = { cpu = 1, render = 1, cores = 1, disk = 1 },
    overlayTree = { cpu = 1, cores = 1 },
    overlayVerdict = {},
    overlayPower = {},
    overlayGraph = { cpu = 1, render = 1 },
    overlayTexture = { cpu = -2, render = -2, gpu = -1 },
    overlayRefreshMs = { cpu = -1 },
    overlayGraphHz = { cpu = -1, render = -1 },
    overlayFlame = { cpu = 1, cores = 1 },
    gameThreadProfileHz = { cpu = 1, cores = 1 },
    consoleLog = {},
    -- chunk streaming
    parallel = { cores = 3, ram = 1, chunks = -3 },
    workers = { cores = 2, chunks = -1 },
    loadWorkers = { cores = 2, load = -1 },
    wake = { chunks = -3 },
    chunkHandoffDivisor = { cpu = -1, chunks = 1 },
    -- mod Lua while chunks arrive (lua-stream)
    luaLoadStream = { cpu = -2, chunks = -2 },
    luaLoadBudgetUs = {},
    luaLoadNearestFirst = { chunks = -1 },
    luaLoadChunkSplit = { cpu = -1 },
    luaLoadSlack = { cpu = -1 },
    luaLoadPrefilter = { cpu = -2 },
    luaLoadSigProject = { cpu = -1 },
    luaLoadBatch = {},
    luaLoadNoopSkip = { cpu = -2 },
    mapObjectsFast = {},
    slackWork = { cpu = -1, chunks = 1 },
    handoffRecalcFast = { cpu = -1, chunks = -1 },
    propIdCache = { cpu = -1 },
    chunkHandoffCost = { cpu = -1, chunks = -1 },
    chunkHandoffSlackMaxWait = { cpu = -1, chunks = -1 },
    chunkHandoffForceAnyFrame = { chunks = -1 },
    chunkHandoffFrameBudgetUs = { cpu = -1, chunks = 1 },
    chunkGridWidth = { cpu = -2, cores = -1, gpu = -1, vram = -1, ram = -1, load = -1, chunks = -1 }, -- the bars describe the smaller grids
    chunkGridFollowView = { chunks = -1 }, -- the upstairs screen corners are filled; same chunk count
    hotsaveStaged = { cpu = -1 },
    hotsaveIntervalSec = { cpu = -2, disk = -2 },
    -- boot
    fmodAsync = { cores = 1, load = -2 },
    preloadAnimSets = { cores = 1, load = -2 },
    bootPump = { cores = 2, ram = 1, load = -2 },
    bootFileThreads = { cores = 2, load = -1 },
    earlyModels = { load = -1 },
    luaPrecompile = { cores = 2, load = -2 },
    animClipCache = { cores = -2, disk = 2, load = -2 },
    packIndex = { disk = 1, load = -2 },
    scriptParserFast = { ram = -1, load = -2 },
    itemParamSwitch = { load = -1 },
    -- world load
    fileThreads = { cores = 2, load = -1 },
    fileInflight = { ram = 1, load = -1 },
    textureBufferMb = { render = 2, ram = 2, load = -1 },
    parallelDepthMaps = { cores = 1, load = -1 },
    loaderCpuFixes = { cores = -1, load = -1 },
    shaderCache = { render = -1, load = -3 },
    shaderWarmup = { render = -1 },
    mipmapArrays = { cores = -1 },
    texCompress = { render = -3, cores = 1, gpu = 1, load = -1 },
    noLoadFade = { load = -1 },
    noIntroWait = { load = -2 },
    noClickToStart = { load = -2 },
    noLoadingScreen = { load = -1 },
    resumeShot = { disk = 1, load = -1 },
    centerFirstLoad = { load = -2, chunks = -1 },
    tileDefPreload = { cores = 1, load = -2 },
    skipIdChecks = { load = -1 },
    voronoiFast = { cores = -1, load = -2 },
    earlyTilePacks = { load = -1 },
    aotCache = { disk = 1, load = -2 },
    reflexBoost = { gpu = -2 },
    reflexCapFps = { cpu = -1, gpu = -1 },
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
                if entry.why and entry.why[axis.id] then len = len + addTerms(tf, entry.why[axis.id], W_TIP) end
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
API.MASTER_CLIPS = { enhancementsEnabled = "visuals", profilerEnabled = "overlay" }
API.GROUP_NAMES = { "Optimizations", ENHANCEMENTS_TAB, PROFILER_TAB }
API.CATS = CATS
API.AXES = AXES
API.LEVELS = LEVELS -- (the menu's compact effect line)
API.FX_COLOURS = { less = C_OPT, more = C_STOCK, moreText = C_STOCK_TEXT, work = C_BLUE }
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
-- why the setting moves each resource it moves: its entry's `why` (axis id -> one or two sentences; every non-zero axis of
-- its EFFECTS row has one, tests/lua/effect-why.lua), drawn under its description
function API.why(key)
    if not API.whyOf then
        API.whyOf = {}
        for _, m in ipairs(API.MASTERS) do API.whyOf[m.key] = m.why end
        for _, list in ipairs({ SECTIONS, ENHANCEMENT_SECTIONS, PROFILER_SECTIONS }) do
            for _, s in ipairs(list) do
                for _, e in ipairs(s.entries or {}) do API.whyOf[e.key] = e.why end
            end
        end
    end
    return API.whyOf[key] or {}
end

-- (2026-10-23) A setting's effects at a glance: split into what it saves, what it costs and the idle cores it puts to work
-- (API.sides, the detail card's columns), said as one sentence (API.summary, under the card's name), and weighed against
-- what limits this PC's frames in its newest benchmark run (API.verdict, the icon beside the name in the list).
-- each resource's name in the sentence; the strength words of a saving and of a cost, by level
API.NOUN = { cpu = "game-thread time", render = "render-thread time", cores = "worker-thread time", gpu = "GPU time", vram = "VRAM",
    ram = "RAM", disk = "disk use", load = "load time", chunks = "work per chunk arrival" }
API.SAVE_WORDS = { "small saving in", "clear saving in", "big saving in" }
API.COST_WORDS = { "a little more", "more", "a lot more" }

-- whether the setting moves any resource (the game's Kahlua has no next())
function API.moves(key)
    for _, v in pairs(EFFECTS[key] or {}) do
        if v ~= 0 then return true end
    end
    return false
end

-- { saves = { {axis, level 1..3}... }, costs = {...}, work = {...} } in AXES order
function API.sides(key)
    local fx, out = EFFECTS[key] or {}, { saves = {}, costs = {}, work = {} }
    for _, axis in ipairs(AXES) do
        local v = math.max(-3, math.min(3, fx[axis.id] or 0))
        if v < 0 then table.insert(out.saves, { axis = axis, level = -v })
        elseif v > 0 then table.insert(out[axis.moreIsWork and "work" or "costs"], { axis = axis, level = v }) end
    end
    return out
end

-- the resource a setting moves most, { axis, level = -3..3 } (nil: moves nothing). Work put on idle cores counts one
-- level lower: it is what a saving elsewhere costs, not the point of the setting (the zombie batches save game-thread
-- time and spread it over the workers). Ties go to the AXES order.
function API.top(key)
    local fx, best, score = EFFECTS[key] or {}, nil, -1
    for _, axis in ipairs(AXES) do
        local v = math.max(-3, math.min(3, fx[axis.id] or 0))
        local s = math.abs(v) - ((axis.moreIsWork and v > 0) and 1 or 0)
        if v ~= 0 and s > score then best, score = { axis = axis, level = v }, s end
    end
    return best
end

-- the colour of a move on an axis: a saving green, work on idle cores blue, a cost amber
function API.fxColour(axis, v)
    return v < 0 and C_OPT or (axis.moreIsWork and C_BLUE or C_STOCK)
end

-- "Big saving in game-thread time, clear saving in GPU time; costs a little more work per chunk arrival." (nil: moves
-- nothing measurable)
function API.summary(key)
    local s = API.sides(key)
    local function nouns(list)
        local t = {}
        for _, r in ipairs(list) do table.insert(t, API.NOUN[r.axis.id]) end
        if #t <= 1 then return t[1] or "" end
        return table.concat(t, ", ", 1, #t - 1) .. " and " .. t[#t]
    end
    local function byLevel(list, words, lead)
        local parts = {}
        for level = 3, 1, -1 do
            local same = {}
            for _, r in ipairs(list) do if r.level == level then table.insert(same, r) end end
            if #same > 0 then table.insert(parts, ((lead and #parts == 0) and (lead .. " ") or "") .. words[level] .. " " .. nouns(same)) end
        end
        return parts
    end
    local parts = {}
    local saves = byLevel(s.saves, API.SAVE_WORDS)
    if #saves > 0 then table.insert(parts, table.concat(saves, ", ")) end
    if #s.work > 0 then table.insert(parts, "puts idle cores to work") end
    local costs = byLevel(s.costs, API.COST_WORDS, "costs")
    if #costs > 0 then table.insert(parts, table.concat(costs, ", ")) end
    if #parts == 0 then return nil end
    local text = table.concat(parts, "; ")
    -- (the strength words are written for the middle of a sentence: the first one takes a capital)
    return (string.gsub(text, "^%l", string.upper)) .. "."
end

-- this PC's newest benchmark run: { run, date, gpu, game, render, cap } (shares of the frames each limited, %), or nil;
-- asked of Java at most every 5 s (a benchmark can finish while the screen is open)
function API.machine()
    local now = getTimestampMs()
    if API.machineAt and now - API.machineAt < 5000 then return API.machineOf end
    API.machineAt = now
    local ok, text = pcall(function() return getPerformance():pzoptBenchmark("machine", "") end)
    API.machineOf = nil
    if ok and type(text) == "string" and text ~= "" then
        local c = {}
        for f in string.gmatch(text, "[^\t]+") do table.insert(c, f) end
        if #c >= 6 then
            API.machineOf = { run = c[1], date = c[2], gpu = tonumber(c[3]) or 0, game = tonumber(c[4]) or 0,
                render = tonumber(c[5]) or 0, cap = tonumber(c[6]) or 0 }
        end
    end
    return API.machineOf
end

-- What the setting does to this PC's frame rate: "helps" | "costs" | "neutral", and why in a sentence; nil without a
-- benchmark of this PC or for a setting that moves nothing. Its game-thread, render-thread and GPU levels are weighed by the
-- share of frames each limited (a GPU cost on a PC whose game thread limits it changes nothing you see); the sentence names
-- the stage that weighs most.
API.VERDICT_STAGE = { cpu = { "game", "game thread" }, render = { "render", "render thread" }, gpu = { "gpu", "graphics card" } }
API.VERDICT_AT = 0.35 -- (the weighed sum past which a setting helps or costs: a level-1 change on a stage limiting a third)
function API.verdict(key)
    local fx = EFFECTS[key]
    if not API.moves(key) then return nil end
    local m = API.machine()
    if not m then return nil end
    local score, gain, loss = 0, { 0 }, { 0 }
    local spare, idle = {}, {}
    for _, axis in ipairs({ "cpu", "render", "gpu" }) do
        local st, v = API.VERDICT_STAGE[axis], fx[axis] or 0
        local share = (m[st[1]] or 0) / 100
        local c = -v * share
        score = score + c
        if c > gain[1] then gain = { c, st[2], share } end
        if -c > loss[1] then loss = { -c, st[2], share } end
        if v > 0 and share < 0.25 then table.insert(spare, st[2]) end
        if v < 0 and share < 0.25 then table.insert(idle, st[2]) end
    end
    local bench = " (your benchmark of " .. tostring(m.date) .. ")"
    local function pct(x) return math.floor(100 * x + 0.5) .. " %" end
    -- (a PC that held its cap shows no saving in its frame rate, and the levels cannot say whether a cost breaks the cap)
    if m.cap < 50 and score >= API.VERDICT_AT then
        return "helps", "Helps your PC: its " .. gain[2] .. " limited " .. pct(gain[3]) .. " of the frames" .. bench
            .. ", and this takes work off it."
    elseif m.cap < 50 and score <= -API.VERDICT_AT then
        return "costs", "Costs frame rate on your PC: its " .. loss[2] .. " limited " .. pct(loss[3]) .. " of the frames"
            .. bench .. ", and this adds work there."
    end
    local what
    if m.cap >= 50 then
        what = "your PC held its frame cap in " .. math.floor(m.cap + 0.5) .. " % of the frames" .. bench
    elseif #spare > 0 then
        what = "it adds work to the " .. table.concat(spare, " and ") .. ", which your PC has to spare" .. bench
    elseif #idle > 0 then
        what = "it saves work on the " .. table.concat(idle, " and ") .. ", which is not what limits your PC" .. bench
    else
        what = "it does not change what limits your PC's frames" .. bench
    end
    return "neutral", "Little change to your frame rate: " .. what .. "."
end
-- how many of the chart's rows a setting moves (the chart draws only those; none = no chart)
function API.effectRows(key)
    local fx, n = EFFECTS[key] or {}, 0
    for _, axis in ipairs(AXES) do
        if (fx[axis.id] or 0) ~= 0 then n = n + 1 end
    end
    return n
end
function API.compatReason(key) return compatReason(key) end

-- the clip a setting shows: its key's own, else its section's; nil = no visual comparison (a setting that does not change
-- the picture)
function API.clipOf(key, section)
    return API.MASTER_CLIPS[key] or KEY_CLIP[key] or (section and section.clip) or nil
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

-- The value that switches a choice setting off ("off", "false", or the layout's offValues), nil when it has none
function API.offValue(key, e)
    local L = PzoptSettingsLayout or {}
    if L.offValues and L.offValues[key] then return L.offValues[key] end
    for _, c in ipairs((e and e.choices) or {}) do
        if tostring(c) == "off" or tostring(c) == "false" then return tostring(c) end
    end
    return nil
end

-- The leaf a switched choice's value moves to (the layout's `leaves`): { label, tip, off, values = the choices but the
-- off one }, or nil when the setting has no leaf (no off value, one other value, or `leaves[key] = false`). The setting's
-- row keeps the switch; the leaf row under it picks among `values`.
function API.leaf(key, e)
    local off = API.offValue(key, e)
    if not (e and e.choices and off) then return nil end
    local spec = ((PzoptSettingsLayout or {}).leaves or {})[key]
    if spec == false then return nil end
    local values = {}
    for _, c in ipairs(e.choices) do
        if tostring(c) ~= off then table.insert(values, tostring(c)) end
    end
    if #values < 2 then return nil end
    spec = spec or {}
    return { label = spec.label or "Mode", tip = spec.tip, off = off, values = values }
end

-- A subcategory's keys as a tree: each setting with its modifiers under it (the main menu's settings pages draw them
-- indented under the setting's switch). Nothing in the entries says which key tunes which, so it is read from the labels
-- and the order, and the layout's `parents` corrects the rest:
--  1. `parents[key]`: the key's parent ("" = a setting of its own);
--  2. a label "God rays: brightness (%)" (or "Upscaler, NVIDIA DLSS: preset") goes under the earlier key labelled "God rays"
--     ("Upscaler"), or starting with it ("HDR output: automatic"), or under the first setting whose label has the same
--     "Frame queue:" prefix, else (a subcategory made of whole tab sections, `sectioned`) under the first setting of its
--     section (Mirrors: ... under "Mirror and window reflections");
--  3. a key that switches nothing (a number, a choice without an off value) goes under the setting before it;
--  4. anything else is a setting of its own.
-- Two levels at most under a setting. kindOf(key) -> "bool" | "choice" | "other"; entryOf(key) -> { e, section }.
function API.nest(keys, entryOf, kindOf, sectioned)
    local L = PzoptSettingsLayout or {}
    local fixed = L.parents or {}
    local top, nodeOf, list = {}, {}, {}
    local function lower(s) return string.lower(s or "") end
    local function prefixOf(label)
        local p = string.match(label or "", "^(.-): ")
        return p and lower(p) or nil
    end
    local function isSwitch(k)
        local b = entryOf(k)
        local kind = kindOf(k)
        return kind == "bool" or (kind == "choice" and API.offValue(k, b and b.e) ~= nil)
    end
    local function startsWith(s, p)
        if s == p then return true end
        if string.sub(s, 1, #p) ~= p then return false end
        local nextCh = string.sub(s, #p + 1, #p + 1)
        return nextCh == " " or nextCh == ":" or nextCh == "," or nextCh == "("
    end
    local function attach(n, parent)
        while parent and parent.depth >= 2 do parent = parent.parent end
        if parent then
            n.parent, n.depth = parent, parent.depth + 1
            table.insert(parent.kids, n)
        else
            n.depth = 0
            table.insert(top, n)
        end
    end
    for _, k in ipairs(keys) do
        local b = entryOf(k)
        local label = lower(b and b.e and b.e.label or k)
        local n = { key = k, kids = {}, label = label, prefix = prefixOf(label), section = b and b.section or "" }
        nodeOf[k] = n
        local parent = nil
        local f = fixed[k]
        if f ~= nil then
            parent = f ~= "" and nodeOf[f] or nil
        else
            local p = n.prefix
            local p2 = p and string.match(p, "^(.-),") or nil
            if p then
                for pass = 1, 3 do -- (an exact label: the nearest; a label or prefix it starts: the first, "Frame queue: ...")
                    for j = (pass == 1 and #list or 1), (pass == 1 and 1 or #list), (pass == 1 and -1 or 1) do
                        local x = list[j]
                        local hit = (pass == 1 and (x.label == p or (p2 and x.label == p2)))
                            or (pass == 2 and x.prefix ~= p and (startsWith(x.label, p) or (p2 and startsWith(x.label, p2))))
                            or (pass == 3 and x.depth == 0 and x.prefix == p)
                        if hit then parent = x break end
                    end
                    if parent then break end
                end
                for _, x in ipairs((sectioned and not parent) and list or {}) do
                    if x.depth == 0 and x.section == n.section then parent = x break end
                end
            end
            if not parent and not isSwitch(k) and #list > 0 then
                parent = list[#list]
                while parent.depth > 0 do parent = parent.parent end
            end
        end
        attach(n, parent)
        table.insert(list, n)
    end
    return top
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

-- What Recommended does: the Optimizations master, its category switches and every Optimizations setting at the default
local function recommendedOptimizations()
    return (API.set("enabled", "") and 1 or 0) + API.resetKeys(API.catMasters(1)) + API.resetKeys(API.pageKeys(1))
end

-- "All enhancements" / "All optimizations" (2026-10-24): every switch of a group on, read from the entries, so a new
-- setting joins by itself. A switch is a tick box (default true / false) or a choice with an off value (API.offValue);
-- its "on" is what the menu's switch turns it on to: the default when that is not off, the layout's onValues, else the
-- first choice that is not off. Numbers, colours, curves and choices without an off value stay at the default, and so do
-- pinned keys (API.set), keys a Java mod patches (mod compatibility keeps them off) and ALL_SKIP.
local ALL_SKIP = {
    -- Enhancements: "on" renders the world smaller (upscaler, dynamic resolution), forces HDR on a screen the check
    -- found none on, hides the people in a car behind the seats (off = the cutaway, on purpose), or sharpens DLSS (taste)
    upscaler = true, dynRes = true, hdr = true, carOccupantOcclusion = true, dlssSharpen = true,
    -- remembered places stays as it is (maintainer, 2026-10-24)
    memoryTint = true,
    -- Optimizations: on = the game's own 42.21 tree rule; a third of the frame rate on a Mac; baked glass hides zombies;
    -- hot-save parts written at different moments; measured slower; tearing; Metal present adds input delay; off on
    -- Windows by design; mod-drawn UI follows the mod compatibility choice
    driveTreeCutaway = true, macGlTimerQueries = true, windowsInChunkTexture = true, hotsaveStaged = true,
    losLightPrefetch = true, vsyncAdaptive = true, macPresent = true, limiterSleep = true, uiRetainedMods = true,
}

local function isExperimental(e)
    return string.find(string.lower((e.label or "") .. " " .. (e.tip or "")), "experimental", 1, true) ~= nil
end

-- the value a setting's switch turns it on to, nil = leave it at the default
local function allOnValue(e)
    local p, key = perf(), e.key
    if e.bezier or e.colour or not p:isPzoptOptionKnown(key) then return nil end
    local def = p:getPzoptOptionDefault(key)
    if not e.choices then return (def == "true" or def == "false") and "true" or nil end
    local off = API.offValue(key, e)
    if not off then return nil end
    if def ~= off then return def end
    local on = ((PzoptSettingsLayout or {}).onValues or {})[key]
    if on then return on end
    for _, c in ipairs(e.choices) do
        if tostring(c) ~= off then return tostring(c) end
    end
    return nil
end

-- every switch of a page's sections on (but the experimental ones when `noExperimental`); returns how many changed
local function allOn(page, noExperimental)
    local n = (API.set(API.MASTERS[page].key, "true") and 1 or 0)
    for _, k in ipairs(API.catMasters(page)) do
        if API.set(k, "true") then n = n + 1 end
    end
    for _, sec in ipairs(sectionsOf(page)) do
        for _, e in ipairs(sec.entries or {}) do
            if e.key then
                local on = nil
                if not ALL_SKIP[e.key] and not (noExperimental and isExperimental(e)) and compatReason(e.key) == "" then
                    on = allOnValue(e)
                end
                if API.set(e.key, on) then n = n + 1 end
            end
        end
    end
    return n
end

function API.presets()
    local list = {
        { title = "Recommended (every optimization on)", tip = RECOMMENDED_TIP, apply = function()
            local n = recommendedOptimizations()
            return n .. (n == 1 and " setting" or " settings") .. " changed: every optimization at the build's default."
        end },
        { title = "Stock game (every optimization off)", tip = STOCK_TIP, apply = function()
            local changed = API.set("enabled", "false")
            return changed and "Optimizations switched off: the next launch is the stock game's code." or "Optimizations were already off."
        end },
        { title = "All enhancements", tip = "Every enhancement on, but the experimental ones and remembered places.", apply = function()
            local n = recommendedOptimizations() + allOn(2, true)
            return n .. (n == 1 and " setting" or " settings") .. " changed."
        end },
        { title = "All optimizations", tip = "Every optimization on, the experimental ones too; enhancements at their defaults.", apply = function()
            -- the Enhancements master, its category switches and its settings back to the default (maintainer, 2026-10-24)
            local n = allOn(1, false) + API.resetKeys({ API.MASTERS[2].key }) + API.resetKeys(API.catMasters(2))
                + API.resetKeys(API.pageKeys(2))
            return n .. (n == 1 and " setting" or " settings") .. " changed."
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

-- The export's key=value lines (the saved choices that differ from the defaults, all three groups), sorted, without the
-- headings: what a saved preset holds and what the preset card compares to tell which preset is in force
function API.settingsLines()
    local rows, seen = {}, {}
    for page = 1, 3 do
        for _, key in ipairs(exportKeys(page)) do
            if not seen[key] then
                seen[key] = true
                local v = exportValue(key)
                if v then table.insert(rows, key .. "=" .. v) end
            end
        end
    end
    table.sort(rows)
    return table.concat(rows, "\n")
end

-- ---- saved presets (the menu's preset card, 2026-10-20): Zomboid/Lua/pzopt-presets.ini, a [name] heading per preset and
-- its key=value lines (an export without the group comments); loading one is an import of its text
local PRESETS_FILE = "pzopt-presets.ini"

function API.userPresets()
    local list = {}
    local r = getFileReader(PRESETS_FILE, false)
    if not r then return list end
    local cur
    local line = r:readLine()
    while line do
        local name = string.match(line, "^%[(.*)%]%s*$")
        if name then
            cur = { name = name, lines = {} }
            table.insert(list, cur)
        elseif cur and string.match(line, "^[%w_%.%-]+=") then
            table.insert(cur.lines, (string.gsub(line, "%s+$", "")))
        end
        line = r:readLine()
    end
    r:close()
    for _, pr in ipairs(list) do
        table.sort(pr.lines)
        pr.text = table.concat(pr.lines, "\n")
    end
    return list
end

local function writeUserPresets(list)
    local w = getFileWriter(PRESETS_FILE, true, false)
    if not w then return false end
    for _, pr in ipairs(list) do
        w:write("[" .. pr.name .. "]\n")
        if pr.text ~= "" then w:write(pr.text .. "\n") end
    end
    w:close()
    return true
end

-- the current settings under `name` (a preset of that name is replaced); returns ok
function API.saveUserPreset(name, text)
    name = string.gsub(string.gsub(name or "", "[%[%]\r\n]", ""), "^%s*(.-)%s*$", "%1")
    if name == "" then return false end
    local list, done = API.userPresets(), false
    for _, pr in ipairs(list) do
        if pr.name == name then pr.text, done = text, true end
    end
    if not done then table.insert(list, { name = name, text = text }) end
    return writeUserPresets(list), name
end

function API.deleteUserPreset(name)
    local list = {}
    for _, pr in ipairs(API.userPresets()) do
        if pr.name ~= name then table.insert(list, pr) end
    end
    return writeUserPresets(list)
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
