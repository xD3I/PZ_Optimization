-- pzopt harness: hands-off game runs for PZ_Optimization.
--
-- harness/run.sh writes ~/Zomboid/Lua/pzopt-harness.txt (getFileReader resolves
-- under Lua/) as key=value lines before launching the game. If the file names a
-- mode, this script continues latestSave.ini from the main menu's own tick as
-- soon as the menu accepts input (no click, no fixed wait) and, when quit_after is set, quits that many
-- seconds after the world is up. Everything else the harness does lives in
-- Java (pzopt.*), driven from the overridden WorldStreamer on the game thread.
--
-- Protocol on the flag file (it is the only state that survives: this script
-- is reloaded when the game resets Lua for the save's mod set, and the menu
-- fires OnMainMenuEnter more than once at start-up):
--   consumed=1   appended here once Continue has been triggered in this process
--   started=1    appended by pzopt.Harness once the world was up; back at the
--                menu with it set means the run is over -> quit to desktop
-- Without the file this script does nothing.

local FLAG_FILE = "pzopt-harness.txt"

local rawLines = {}

local function readFlags()
    local reader = getFileReader(FLAG_FILE, false)
    if not reader then return nil end
    local flags = {}
    local n = 0
    rawLines = {}
    while true do
        local line = reader:readLine()
        if line == nil then break end
        table.insert(rawLines, line)
        local k, v = string.match(line, "^%s*([%w_]+)%s*=%s*(.-)%s*$")
        if k then flags[k] = v; n = n + 1 end
    end
    reader:close()
    if n == 0 then return nil end
    return flags
end

local function appendFlag(line)
    -- true append: pzopt.Harness may already have added started=1 (the game auto-loads the
    -- save before the menu delay elapses), and rewriting from a stale copy would drop it
    local w = getFileWriter(FLAG_FILE, true, true)
    if not w then return end
    w:write(line .. "\n")
    w:close()
end

local pending = nil
local quitAtMs = nil
-- The options rigs (2026-09-24, retargeted 2026-10-18 to the main menu's PZ OPTIMIZATION screen, pzopt_mainscreen_menu.lua:
-- the game's Options screen has no PZ Optimization tab any more). Each stays on the main menu, opens the screen and quits
-- when done (no Continue). The flag names are the tab-era ones:
-- options_tab=<name>: open the screen (PZ Optimization = home; the old tab names Optimizations / Enhancements / Profiler
--   open those groups), log its page, 3 s later write Zomboid/Screenshots/pzopt-options.png, quit 2 s after.
-- options_nav=<page>: the page shown first: a menu node (home, perf, vis/light, perf/drive/1, problems/2, bench/setup...),
--   key:<setting key> (that setting on its page, the cursor on it), or the tab-era forms home | cat:<category id>:<sub,
--   0 = the category's page>[:simple|advanced|everything] | problems[:<n>] | search:<text>.
-- options_search=<text>: typed into the screen's search box 1 s after opening (its results page shows; _ for a space).
-- options_select=<key>: that setting on its page with its detail (impact chart, clips), as key:<key>.
-- options_clips=1|0: the before / after clips of a setting's detail on / off (previewClips, saved: give the run a scratch
--   -Dpzopt.userOptionsFile) before the first page shows.
-- options_shots=<page>;<page>;...: shows each page in turn and writes Screenshots/pzopt-options-<n>.png per page (1.5 s
--   each), then quits.
local optionsCheck = nil
local seqTick
local OLD_TABS = { Optimizations = "perf", Enhancements = "vis", Profiler = "tools", ["PZ Optimization"] = "home" }
local LEVELS = { simple = 1, advanced = 2, everything = 3 }
-- options_joy=<step>,<step>,...: the screen's controller navigation without a pad: the steps call its own joypad handlers
--   as the game does for a pad (focus = gain focus; up / down / left / right; a, b, x, y, lb, rb, back = those buttons),
--   one every 0.6 s, logging the focused element and the page after each ("[pzopt-harness] joy: ..."); shot writes
--   Screenshots/pzopt-joy-<n>.png. Quits when done. (benchmark-pad-check.sh walks the screen with a real virtual pad.)
local JOY = { id = 0, player = 0 }
-- options_read=1: the readability tour of every settings page of the screen (home, each group, each category, each
--   subcategory and each further page of its list in the Everything view, Fix a problem and each problem, a search) through
--   the benchmark's BENCHREAD rig (PzoptBenchRead): every text drawn with its rectangles, a screenshot
--   Screenshots/pzopt-bench-ui-read-<n>-<stop>.png per stop. harness/options-readability.py <run> collects them and runs
--   harness/benchmark-readability.py (Jev).

local function menuScreen()
    return PzoptMainMenu and (PzoptMainMenu.instance() or PzoptMainMenu.show())
end

-- the node a page spec names (see options_nav above); nil + key for key:<key>, nil + nil + text for a search
local function navNode(spec)
    spec = spec or "home"
    if OLD_TABS[spec] then return OLD_TABS[spec] end
    local parts = {}
    for part in string.gmatch(spec .. ":", "([^:]*):") do table.insert(parts, part) end
    local kind = parts[1] ~= "" and parts[1] or "home"
    if kind == "key" then return nil, parts[2] end
    if kind == "search" then return nil, nil, string.sub(spec, 8) end
    if kind == "problems" then return (parts[2] and parts[2] ~= "") and ("problems/" .. parts[2]) or "problems" end
    if kind == "cat" then
        for _, g in ipairs((PzoptSettingsLayout or {}).groups or {}) do
            for _, c in ipairs(g.cats or {}) do
                if c.id == parts[2] then
                    local sub = tonumber(parts[3]) or 0
                    return g.id .. "/" .. c.id .. (sub > 0 and ("/" .. sub) or ""), nil, nil, LEVELS[parts[4] or ""]
                end
            end
        end
        return "home"
    end
    return spec
end

local function applyNav(spec)
    local m = menuScreen()
    if not m then return nil end
    local node, key, text, level = navNode(spec)
    if level then m:setView(level) end
    if text then
        m.search:setText(text)
        m:runSearch(text)
    elseif key then
        m:openKey(key)
    else
        m:selectNode(node, "master")
    end
    return m
end

local function joyDescribe(m)
    if not m then return "no screen" end
    local el = m.joypadButtons and m.joypadButtons[1]
    local name = el and (el.name or el.title) or "-"
    return "pane " .. tostring(m.pane) .. " row " .. tostring(m.joypadIndexY) .. " el '" .. tostring(name) .. "' page "
        .. tostring(m.selected) .. (m.help and "+help" or "") .. (m.prompt and "+prompt" or "")
end
local JOY_BUTTONS = { a = "AButton", b = "BButton", x = "XButton", y = "YButton", lb = "LBumper", rb = "RBumper", back = "Back" }
local function joyStep(m, step, n)
    if step == "focus" then
        m:onGainJoypadFocus(JOY)
    elseif step == "down" then m:onJoypadDirDown(JOY)
    elseif step == "up" then m:onJoypadDirUp(JOY)
    elseif step == "left" then m:onJoypadDirLeft(JOY)
    elseif step == "right" then m:onJoypadDirRight(JOY)
    elseif JOY_BUTTONS[step] then m:onJoypadDown(Joypad[JOY_BUTTONS[step]], JOY)
    elseif step == "shot" then getCore():TakeFullScreenshot("pzopt-joy-" .. n .. ".png")
    end
end

-- options_read's stops: a page per stop, a long list's further pages as stops of their own (the stops a stop returns run
-- next), the Everything view throughout
local function readName(node)
    return "opt-" .. string.gsub(node, "/", "-")
end
local function listPageStops(name)
    local m = PzoptMainMenu.instance()
    local out = {}
    if not m then return out end
    local n, vis = #m.list.items, m.list:visibleCount()
    local i, p = vis + 1, 2
    while i <= n do
        local target = i
        table.insert(out, { name = name .. "-p" .. p, fn = function()
            local mm = PzoptMainMenu.instance()
            mm.list.top = target
            mm.list:setSelected(target)
            mm:showDetail()
        end })
        i, p = i + vis, p + 1
    end
    return out
end
local function pageStop(node, children)
    return { name = readName(node), fn = function()
        local m = PzoptMainMenu.instance()
        m:selectNode(node, "master")
        local more = listPageStops(readName(node))
        if children then
            for _, it in ipairs(m.page.items or {}) do
                if it.kind == "node" and it.id and string.find(it.id, "^" .. string.gsub(node, "%p", "%%%0") .. "/") then
                    table.insert(more, pageStop(it.id, string.find(it.id, "/[^/]+/") == nil))
                end
            end
        end
        return more
    end }
end
local function readStops(c)
    local stops = {
        { name = "opt-home", fn = function()
            local m = PzoptMainMenu.instance()
            m.forceHints = true
            m:setView(3)
            m:selectNode("home", "master")
            return listPageStops("opt-home")
        end },
    }
    for _, g in ipairs((PzoptSettingsLayout or {}).groups or {}) do table.insert(stops, pageStop(g.id, true)) end
    table.insert(stops, pageStop("problems", false))
    for i = 1, #((PzoptSettingsLayout or {}).problems or {}) do table.insert(stops, pageStop("problems/" .. i, false)) end
    table.insert(stops, { name = "opt-search", fn = function()
        local m = PzoptMainMenu.instance()
        m.search:setText("shadow")
        m:runSearch("shadow")
        return listPageStops("opt-search")
    end })
    table.insert(stops, { name = "opt-done", fn = function()
        local m = PzoptMainMenu.instance()
        m.search:setText("")
        m:runSearch("")
        m:setView(1)
        m:selectNode("home", "master")
        c.readDone = true
    end })
    return stops
end
-- compat_check=1 (2026-10-02): stay on the main menu, log the pzopt menu items, write Screenshots/pzopt-menu.png, open
-- the mod compatibility dialog (PzoptCompatDialog.show), log its text, write
-- Screenshots/pzopt-compat.png, close it and quit. With `--mod pzopt-compat-javafixture` (harness/compat/make-java-fixture.sh)
-- the dialog lists the fixture's jars and the two settings it switches off. compat_choose=performance|compatibility
-- presses that choice's button after the first screenshot, logs the profile state and writes
-- Screenshots/pzopt-compat-chosen.png (it saves the choice: pass --vmarg -Dpzopt.userOptionsFile=<scratch file>).
local compatCheck = nil
-- menu_hold=<secs> (repro-ui 2026-10-16, Workshop "Show Performance Overlay blocks the mod list"): stay on the main menu
-- that long, log every bottom-panel item ("[pzopt-harness] menu item: <internal> '<text>' x y w h visible") once the
-- menu is up and again 1 s later ("menu items logged" marks the second), log each item click the game dispatches, quit.
local menuHold = nil
local function menuItemsLog(ms, tag)
    for _, ch in pairs(ms.bottomPanel:getChildren()) do
        if ch.Type == "ISLabel" then
            print(string.format("[pzopt-harness] menu item%s: %s '%s' x=%d y=%d w=%d h=%d visible=%s", tag, tostring(ch.internal),
                tostring(ch.name), ch:getAbsoluteX(), ch:getAbsoluteY(), ch:getWidth(), ch:getHeight(), tostring(ch:isVisible())))
        end
    end
end
local function menuHoldTick()
    local c = menuHold
    local ms = MainScreen.instance
    if not ms or (ms.delay and ms.delay > 0) or not ms.optionsOption or not ms.optionsOption:isVisible() then return end
    local now = getTimestampMs()
    if not c.at then
        c.at = now
        print("[pzopt-harness] menu hold: menu up, screen=" .. getCore():getScreenWidth() .. "x" .. getCore():getScreenHeight())
        menuItemsLog(ms, "")
        if not c.hooked then
            c.hooked = true
            local stock = MainScreen.onMenuItemMouseDownMainMenu
            MainScreen.onMenuItemMouseDownMainMenu = function(item, x, y)
                print("[pzopt-harness] menu hold: click on " .. tostring(item.internal) .. " epoch=" .. getTimestampMs())
                return stock(item, x, y)
            end
            for _, ch in pairs(ms.bottomPanel:getChildren()) do
                if ch.onMouseDown == stock then ch.onMouseDown = MainScreen.onMenuItemMouseDownMainMenu end
                if ch.internal == "PZOPT_OVERLAY" then
                    local own = ch.onMouseDown
                    ch.onMouseDown = function(item, x2, y2)
                        print("[pzopt-harness] menu hold: click on PZOPT_OVERLAY epoch=" .. getTimestampMs())
                        return own(item, x2, y2)
                    end
                end
            end
        end
    elseif not c.logged2 and now - c.at >= 1000 then
        c.logged2 = true
        menuItemsLog(ms, " (1s)")
        print("[pzopt-harness] menu hold: menu items logged")
    elseif now - c.at >= c.secs * 1000 then
        menuHold = false
        print("[pzopt-harness] menu hold: done, quitting")
        getCore():quit()
    end
end

-- spawn_select=<text> (2026-10-19, Workshop "Nellis Air Base: the game freezes when I select the spawn location"): stays on
-- the main menu and starts a new Apocalypse game the way the menu does (SOLO -> NewGameScreen:clickPlay, WorldSelect first
-- when it has choices: the world whose title or map folders contain the text, else the first, then its Next), so
-- MapSpawnSelect opens as for a player; 3 s later selects the first spawn region whose title contains the text (lower case,
-- _ for a space), spawn_wait s (8) later writes Screenshots/pzopt-spawn-2.png and with spawn_next=1 presses Next, 4 s later
-- quits; spawn_mode=sandbox starts a Sandbox game instead; spawn_input=1 leaves the region click to the run (writes
-- Lua/pzopt-spawn-click.txt "<item x> <item y> <map x> <map y>" for xdotool, see the run's wrapper); spawn_next=play goes on through the profession and character screens and presses Play (a new world named
-- pzopt-spawn-<epoch ms> in Saves/Apocalypse: delete it after the run). "[pzopt-harness] spawn: tick" every 500 ms with the map panel's data state: a freeze shows as the ticks stopping.
local spawnCheck = nil
local function spawnLog(s) print("[pzopt-harness] spawn: " .. s) end
local function spawnTick()
    local c = spawnCheck
    local ms = MainScreen.instance
    if not ms or (ms.delay and ms.delay > 0) or not ms.soloScreen then return end
    local now = getTimestampMs()
    local mss = MapSpawnSelect and MapSpawnSelect.instance
    if not c.beat or now - c.beat >= 500 then
        c.beat = now
        local st = ""
        if mss and mss.mapPanel and mss.mapPanel.mapAPI then
            local okd, cnt = pcall(function() return mss.mapPanel.mapAPI:getDataCount() end)
            local okl, ld = pcall(function() return mss.mapPanel.mapAPI:isDataLoaded() end)
            st = " visible=" .. tostring(mss:isVisible()) .. " data=" .. tostring(okd and cnt or "?") .. " loaded="
                .. tostring(okl and ld or "?") .. " reset=" .. tostring(mss.mapPanel.hasResetView)
        end
        spawnLog("tick step=" .. c.step .. " t=" .. now .. st)
    end
    if now < c.at then return end
    if c.step == 0 then
        ActiveMods.getById("currentGame"):copyFrom(ActiveMods.getById("default"))
        ms.soloScreen:setVisible(true)
        spawnLog("solo screen shown")
        c.step, c.at = 1, now + 1500
        if c.enable and not c.resumed then
            -- spawn_enable=<id;id;...>: the new game screen's CHOOSE MODS path: the mods (in Zomboid/mods, not in
            -- default.txt) switched on for this game only, then the Lua reset the mod screen does ("NewGameMods");
            -- the rig resumes after the reset (spawn_reset_done=1 in the flag file) on the new game screen
            local cur = ActiveMods.getById("currentGame")
            for id in string.gmatch(c.enable, "[^;]+") do cur:setModActive(id, true) end
            appendFlag("spawn_reset_done=1")
            spawnLog("mods " .. c.enable .. " enabled for the new game; ResetLua(currentGame, NewGameMods)")
            spawnCheck = false
            getCore():ResetLua("currentGame", "NewGameMods")
            return
        end
    elseif c.step == 1 then
        local ss = ms.soloScreen
        local pick = nil
        for _, p in ipairs(ss.panels or {}) do
            if p.data and p.data.mode == (c.sandbox and GameMode.SANDBOX or GameMode.APOCALYPSE):toString() then pick = p end
        end
        if not pick then spawnLog("no game mode panel; done"); spawnCheck = false; getCore():quit(); return end
        ss.selectedItem = pick
        local t0 = getTimestampMs()
        ss:clickPlay()
        spawnLog("clickPlay " .. (getTimestampMs() - t0) .. " ms; WorldSelect visible=" .. tostring(WorldSelect.instance
            and WorldSelect.instance:isVisible()) .. " MapSpawnSelect visible=" .. tostring(mss and mss:isVisible()))
        c.step, c.at = 2, now + 2000
    elseif c.step == 2 then
        local ws = WorldSelect.instance
        if ws and ws:isVisible() then
            local sel = 1
            for i, it in ipairs(ws.listbox.items) do
                local dirs = {}
                local md = it.item.mapDirs
                for j = 0, (md and md:size() or 0) - 1 do table.insert(dirs, tostring(md:get(j))) end
                local d = table.concat(dirs, ";")
                spawnLog("world " .. i .. " '" .. tostring(it.item.name) .. "' dirs=" .. d)
                if string.find(string.lower(tostring(it.item.name) .. " " .. d), c.pattern, 1, true) then sel = i end
            end
            ws.listbox.selected = sel
            ws:onSelectWorld()
            local t0 = getTimestampMs()
            ws:clickNext()
            spawnLog("world " .. sel .. " chosen, Next " .. (getTimestampMs() - t0) .. " ms")
        end
        c.step, c.at = 3, now + 3000
    elseif c.step == 3 then
        if not (mss and mss:isVisible()) then spawnLog("MapSpawnSelect not visible; done"); spawnCheck = false; getCore():quit(); return end
        local sel = nil
        for i, it in ipairs(mss.listbox.items) do
            spawnLog("region " .. i .. " '" .. tostring(it.item.name) .. "' dir=" .. tostring(it.item.dir))
            if not sel and string.find(string.lower(tostring(it.item.name)), c.pattern, 1, true) then sel = i end
        end
        getCore():TakeFullScreenshot("pzopt-spawn-1.png")
        if mss.textEntry then mss.textEntry:setText("pzopt-spawn-" .. tostring(now)) end
        if not sel then spawnLog("no region matches '" .. c.pattern .. "'"); sel = mss.listbox.selected end
        if c.input then
            -- spawn_input=1: the run's xdotool clicks the region (real mouse input), then drags and wheels the map
            local lb = mss.listbox
            local x = lb:getAbsoluteX() + 40
            local y = lb:getAbsoluteY() + (sel - 1) * lb.itemheight + lb.itemheight / 2 + (lb:getYScroll() or 0)
            local mp = mss.mapPanel
            local w = getFileWriter("pzopt-spawn-click.txt", true, false)
            if w then
                w:write(string.format("%d %d %d %d\n", math.floor(x), math.floor(y), math.floor(mp:getAbsoluteX() + mp:getWidth() / 2),
                    math.floor(mp:getAbsoluteY() + mp:getHeight() / 2)))
                w:close()
            end
            spawnLog("click file written: item " .. math.floor(x) .. "," .. math.floor(y))
        else
            mss.listbox.selected = sel
        end
        spawnLog("region " .. sel .. " selected")
        c.step, c.at = 4, now + c.wait * 1000
    elseif c.step == 4 then
        getCore():TakeFullScreenshot("pzopt-spawn-2.png")
        if c.next then
            local t0 = getTimestampMs()
            mss:clickNext()
            spawnLog("Next " .. (getTimestampMs() - t0) .. " ms; profession screen visible="
                .. tostring(ms.charCreationProfession and ms.charCreationProfession:isVisible()))
        end
        c.step, c.at = 5, now + 4000
    elseif c.step == 5 and c.play then
        getCore():TakeFullScreenshot("pzopt-spawn-3.png")
        ms.charCreationProfession:onOptionMouseDown({ internal = "NEXT" })
        spawnLog("profession Next; character screen visible=" .. tostring(ms.charCreationMain:isVisible()))
        c.step, c.at = 6, now + 3000
    elseif c.step == 6 then
        spawnLog("play: world " .. tostring(getWorld():getWorld()) .. " map " .. tostring(getWorld():getMap()))
        spawnCheck = false
        ms.charCreationMain:onOptionMouseDown({ internal = "NEXT" })
    else
        getCore():TakeFullScreenshot("pzopt-spawn-3.png")
        spawnLog("done")
        spawnCheck = false
        getCore():quit()
    end
end

local function compatTick()
    local c = compatCheck
    local ms = MainScreen.instance
    if not ms or (ms.delay and ms.delay > 0) or not ms.exitOption or not ms.exitOption:isVisible() then return end
    local now = getTimestampMs()
    if not c.menuMs then
        local names = {}
        for _, child in pairs(ms.bottomPanel:getChildren()) do
            if child.Type == "ISLabel" and child:isVisible() then
                table.insert(names, string.format("%s@%d", tostring(child.name), child:getY()))
            end
        end
        table.sort(names, function(a, b) return tonumber(a:match("@(%d+)$")) < tonumber(b:match("@(%d+)$")) end)
        print("[pzopt-harness] compat check: menu " .. table.concat(names, " | "))
        getCore():TakeFullScreenshot("pzopt-menu.png")
        c.menuMs = now
    elseif not c.openedMs and now - c.menuMs >= 2000 then
        -- the check's dialog (pzopt_mainscreen_compat.lua; the main menu's PZ OPTIMIZATION screen shows the same scan as a page)
        if not PzoptCompatDialog then
            print("[pzopt-harness] compat check: item NOT FOUND, quitting")
            compatCheck = false
            getCore():quit()
            return
        end
        PzoptCompatDialog.show()
        local dlg = PzoptCompatDialog and PzoptCompatDialog.instance
        print("[pzopt-harness] compat check: dialog " .. (dlg and "open" or "NOT OPEN"))
        if dlg then
            print("[pzopt-harness] compat check: profile '" .. tostring(dlg.profileStatus) .. "' restart=" .. tostring(dlg.restart:isVisible()))
            print("[pzopt-harness] compat check: text " .. tostring(dlg.text.text):gsub(" <[^>]*> ", " "):gsub("%s+", " "))
        end
        c.openedMs = now
    elseif c.openedMs and not c.shotMs and now - c.openedMs >= 2000 then
        getCore():TakeFullScreenshot("pzopt-compat.png")
        c.shotMs = now
    elseif c.choose and not c.choseMs and c.shotMs and now - c.shotMs >= 2000 then
        local dlg = PzoptCompatDialog and PzoptCompatDialog.instance
        if dlg then
            dlg.profileButtons[c.choose]:forceClick()
            local p = getPerformance()
            print("[pzopt-harness] compat check: chose " .. c.choose .. "; modProfile now=" .. p:getPzoptOption("modProfile")
                .. " saved=" .. p:getPzoptOptionSaved("modProfile") .. " status='" .. tostring(dlg.profileStatus)
                .. "' restart=" .. tostring(dlg.restart:isVisible()))
        end
        c.choseMs = now
    elseif c.choseMs and not c.chosenShotMs and now - c.choseMs >= 1500 then
        getCore():TakeFullScreenshot("pzopt-compat-chosen.png")
        c.chosenShotMs = now
    elseif c.shotMs and (not c.choose or c.chosenShotMs) and now - (c.chosenShotMs or c.shotMs) >= 2000 then
        if PzoptCompatDialog and PzoptCompatDialog.instance then PzoptCompatDialog.instance:close() end
        compatCheck = false
        print("[pzopt-harness] compat check: done, quitting")
        getCore():quit()
    end
end

-- options_io=1 (2026-10-01; the screen's Export settings / Import settings... since 2026-10-18): 1 s after opening, Export
-- (PzoptSettings.exportSettings: logs the text and whether Zomboid/pzopt/settings-export.ini matches the clipboard), then
-- the import of that text plus IO_CHANGES and an unknown key (PzoptSettings.importSettings, the Import box's OK), logs
-- each value before / after and the import's message; screenshots pzopt-io-{1-export,2-import,3-result}.png, then the
-- settings back from the first export and the clipboard restored. The screen saves at once: pass `--vmarg
-- -Dpzopt.userOptionsFile=<copy>` so the import writes a copy, not the player's Zomboid/pzopt/options.ini.
local IO_CHANGES = { treesInChunkTexture = "false", fogScalePct = "50", overlayStats = "full", spriteFilter = "sharp" }

local function ioLog(s)
    print("[pzopt-harness] options io: " .. s)
end

local function nextValue(key)
    local p = getPerformance()
    local v = p:getPzoptOptionSaved(key)
    return v ~= "" and v or (p:getPzoptOptionDefault(key) .. " (default)")
end

-- options_profile=<preset title, _ for a space> (2026-10-07, the low-end presets): 1 s after the screen opens, apply the
-- home page's preset with that title (PzoptSettings.presets), log its message, every pzopt setting off its default
-- ("profile: key=value") and the stock Display options a preset sets, write Screenshots/pzopt-profile.png, then quit (via
-- the screenshot step). It saves at once: give the run a scratch -Dpzopt.userOptionsFile.
local function stockState()
    local core, perf = getCore(), getPerformance()
    local function v(f) local ok, r = pcall(f); return ok and tostring(r) or "?" end
    return "vsync=" .. v(function() return core:getOptionVSync() end)
        .. " framerate=" .. v(function() return perf:getFramerate() end)
        .. " uncapped=" .. v(function() return perf:isFramerateUncapped() end)
        .. " lightFPS=" .. v(function() return perf:getLightingFPS() end)
        .. " uiRenderFPS=" .. v(function() return core:getOptionUIRenderFPS() end)
        .. " textureCompression=" .. v(function() return core:getOptionTextureCompression() end)
end

local function profileStep(name)
    name = string.gsub(name, "_", " ") -- the flag cannot carry spaces through the queue's remote command line
    local preset = nil
    for _, pr in ipairs(PzoptSettings and PzoptSettings.presets() or {}) do
        if pr.title == name then preset = pr end
    end
    if not preset then print("[pzopt-harness] profile: preset '" .. name .. "' NOT FOUND"); return end
    print("[pzopt-harness] profile: before " .. stockState())
    local msg = preset.apply()
    local p, n = getPerformance(), 0
    for page = 1, 2 do
        for _, k in ipairs(PzoptSettings.pageKeys(page)) do
            local saved = p:getPzoptOptionSaved(k)
            if saved ~= "" and saved ~= p:getPzoptOptionDefault(k) then
                print("[pzopt-harness] profile: " .. k .. "=" .. saved)
                n = n + 1
            end
        end
    end
    print("[pzopt-harness] profile: '" .. name .. "' applied (" .. tostring(msg) .. "), " .. n .. " pzopt setting(s) off their default; "
        .. "after " .. stockState())
    local m = PzoptMainMenu.instance()
    if m then m:refreshPage() end
    getCore():TakeFullScreenshot("pzopt-profile.png")
end

-- one step per call, 1.5 s apart; true when done
local function ioStep(io, now)
    if now < (io.at or 0) then return false end
    local s = io.step or 0
    if s == 0 then
        io.clip = Clipboard.getClipboard() or ""
        local msg = PzoptSettings.exportSettings()
        io.text = Clipboard.getClipboard() or ""
        local n = 0
        for line in string.gmatch(io.text, "[^\n]+") do
            if string.match(line, "^[%w_]+=") then n = n + 1 end
            ioLog("  " .. line)
        end
        ioLog("export: " .. msg)
        ioLog("export " .. n .. " keys, " .. #io.text .. " chars, file "
            .. (getPerformance():getPzoptSettingsExportFile() == io.text and "matches the clipboard" or "DIFFERS from the clipboard"))
        local m = PzoptMainMenu.instance()
        if m then m:selectNode("home", "master", "export") end
    elseif s == 1 then
        getCore():TakeFullScreenshot("pzopt-io-1-export.png")
    elseif s == 2 then
        local extra = { "noSuchKey=1" }
        for k, v in pairs(IO_CHANGES) do
            table.insert(extra, k .. "=" .. v)
            ioLog("before " .. k .. "=" .. nextValue(k))
        end
        local text = io.text .. table.concat(extra, "\n") .. "\n"
        Clipboard.setClipboard(text)
        ioLog("import source " .. #PzoptSettings.importSource() .. " chars (the clipboard: " .. #text .. ")")
        local msg = PzoptSettings.importSettings(text)
        ioLog("result: " .. tostring(msg))
        for k, want in pairs(IO_CHANGES) do
            local cur = nextValue(k)
            ioLog("after " .. k .. "=" .. cur .. (cur == want and " ok" or (" WANTED " .. want)))
        end
        local m = PzoptMainMenu.instance()
        if m then m:selectNode("home", "master", "import") end
    elseif s == 3 then
        getCore():TakeFullScreenshot("pzopt-io-2-import.png")
    elseif s == 4 then
        -- the settings back as the first export had them
        ioLog("restore: " .. tostring(PzoptSettings.importSettings(io.text ~= "" and io.text or "# none\nenabled=true\n")))
        local m = PzoptMainMenu.instance()
        if m then m:refreshPage() end
    elseif s == 5 then
        getCore():TakeFullScreenshot("pzopt-io-3-result.png")
    else
        Clipboard.setClipboard(io.clip)
        ioLog("done (settings restored from the first export, clipboard restored)")
        return true
    end
    io.step, io.at = s + 1, now + 1500
    return false
end

-- options_seq=<secs>/<tab>;<secs>/<tab>;... (repro-ui 2026-10-16, tooltip flicker report): after opening, show each
-- tab in turn for <secs> s (tab = an index into the options tabs, or pz[@<spec>] for the PZ OPTIMIZATION screen on the page
-- an options_nav spec names; written for the old PZ Optimization tab, ported 2026-10-21 untested),
-- log "[pzopt-harness] options seq: ..." with the window rect and, 0.6 s after each switch, one "TIP" line per visible
-- control that has a tooltip (absolute UI px), so a job wrapper can move the real mouse over them; quits after the last.
local function tipScan(el, out, depth)
    if not el or depth > 12 or #out >= 40 then return end
    local okv, vis = pcall(function() return el:isReallyVisible() end)
    if not okv or not vis then return end
    local tip = el.tooltip or el.toolTipMap or el.tooltipMap or el.tooltipText
    if tip and tip ~= "" and el.getAbsoluteX then
        local x, y, w, h = el:getAbsoluteX(), el:getAbsoluteY(), el:getWidth(), el:getHeight()
        if w > 4 and h > 4 and x >= 0 and y >= 0 and x + w <= getCore():getScreenWidth() and y + h <= getCore():getScreenHeight() then
            table.insert(out, string.format("%d %d %d %d %s", x, y, w, h, tostring(el.Type)))
        end
    end
    local okc, kids = pcall(function() return el:getChildren() end)
    if okc and kids then
        for _, k in pairs(kids) do tipScan(k, out, depth + 1) end
    end
end

seqTick = function(c, ms, now)
    local mo = ms.mainOptions
    if c.seqIndex and c.seqScanAt and now >= c.seqScanAt then
        c.seqScanAt = nil
        local out = {}
        tipScan(c.seqRoot or mo, out, 0)
        for i, l in ipairs(out) do print("[pzopt-harness] options seq: TIP " .. c.seqIndex .. " " .. l) end
        print("[pzopt-harness] options seq: tips " .. c.seqIndex .. " n=" .. #out .. " epoch=" .. getTimestampMs())
        return
    end
    if c.seqIndex and now < c.seqUntil then return end
    local i = (c.seqIndex or 0) + 1
    if i > #c.seq then
        c.seqDone, c.shotMs = true, now - 1500
        print("[pzopt-harness] options seq: done epoch=" .. getTimestampMs())
        return
    end
    local e = c.seq[i]
    local tab, nav, found, root = e.tab, nil, nil, mo
    if string.sub(tab, 1, 2) == "pz" then
        -- since 2026-10-18 the settings live on the PZ OPTIMIZATION screen: pz@<spec> = an applyNav spec (pz alone = home)
        nav = string.match(tab, "^pz@(.+)$") or "home"
        root = applyNav(nav)
        tab, found = "PZ OPTIMIZATION", root ~= nil
        root = root or mo
    else
        local v = mo.tabs.viewList[tonumber(tab) or 1]
        tab = v and v.name or tab
        found = mo.tabs:activateView(tab)
    end
    c.seqRoot = root
    print(string.format("[pzopt-harness] options seq: step %d tab=%s nav=%s found=%s secs=%s epoch=%d screen=%dx%d win=%d,%d,%d,%d",
        i, tostring(tab), tostring(nav), tostring(found), tostring(e.secs), getTimestampMs(), getCore():getScreenWidth(),
        getCore():getScreenHeight(), root:getAbsoluteX(), root:getAbsoluteY(), root:getWidth(), root:getHeight()))
    c.seqIndex, c.seqUntil, c.seqScanAt = i, now + e.secs * 1000, now + 600
end

local function optionsTick()
    local c = optionsCheck
    local ms = MainScreen.instance
    if not ms or (ms.delay and ms.delay > 0) or not ms.pzoptMenuOption then return end
    local now = getTimestampMs()
    if not c.openedMs then
        local m = menuScreen()
        if not m then
            print("[pzopt-harness] options: the PZ OPTIMIZATION screen NOT FOUND")
            optionsCheck = false
            getCore():quit()
            return
        end
        if c.clips and PzoptSettings then PzoptSettings.setClipsOn(c.clips == "1" or c.clips == "true") end
        local nav = c.nav or OLD_TABS[c.tab or ""] or "home"
        if c.select then nav = "key:" .. c.select end
        applyNav(nav)
        print("[pzopt-harness] options: PZ OPTIMIZATION screen shown, page " .. tostring(m.selected) .. " (" .. nav .. ")")
        c.openedMs = now
    elseif c.read and not c.readStarted then
        if now < c.openedMs + 1500 then return end
        c.readStarted = true
        PzoptBenchRead.begin(readStops(c))
        print("[pzopt-harness] options: readability tour started")
    elseif c.seq and not c.seqDone then
        seqTick(c, ms, now)
    elseif c.read and not c.readDone then
        return
    elseif c.read and not c.shotMs then
        c.shotMs = now + 2000 -- the tour's last stop is logged and shot
        print("[pzopt-harness] READ: done")
    elseif c.joy and not c.joyDone then
        if now < (c.joyAt or (c.openedMs + 1500)) then return end
        local i = (c.joyIndex or 0) + 1
        if i > #c.joy then
            c.joyDone, c.shotMs = true, now
            return
        end
        local m = PzoptMainMenu.instance()
        local ok, err = pcall(joyStep, m, c.joy[i], i)
        print("[pzopt-harness] joy: " .. i .. " " .. c.joy[i] .. (ok and "" or (" ERROR " .. tostring(err))) .. " -> " .. joyDescribe(PzoptMainMenu.instance()))
        c.joyIndex, c.joyAt = i, now + 600
    elseif c.shots and not c.shotsDone then
        -- one page every 1.5 s, the screenshot 1 s after showing it
        local i = c.shotIndex or 0
        if i == 0 or now >= c.shotAt then
            if i > 0 and not c.shotTaken then
                getCore():TakeFullScreenshot("pzopt-options-" .. i .. ".png")
                print("[pzopt-harness] options: screenshot " .. i .. " " .. c.shots[i])
                c.shotTaken, c.shotAt = true, now + 500
                return
            end
            i = i + 1
            if i > #c.shots then
                c.shotsDone, c.shotMs = true, now
                return
            end
            applyNav(c.shots[i])
            c.shotIndex, c.shotAt, c.shotTaken = i, now + 1000, false
        end
    elseif c.search and not c.searched and now - c.openedMs >= 1000 then
        c.searched = true
        local m = PzoptMainMenu.instance()
        m.search:setText(c.search)
        m:runSearch(c.search)
        print("[pzopt-harness] options: search '" .. c.search .. "' typed, page " .. tostring(m.selected) .. ", "
            .. tostring(m.page and m.page.count or 0) .. " results")
    elseif c.profile and not c.profileDone and now - c.openedMs >= 1000 then
        c.profileDone = true
        profileStep(c.profile)
    elseif c.io and not c.ioDone and now - c.openedMs >= 1000 then
        c.ioDone = ioStep(c.io, now)
    elseif not c.shotMs and now - c.openedMs >= 3000 then
        getCore():TakeFullScreenshot("pzopt-options.png")
        print("[pzopt-harness] options: screenshot requested")
        c.shotMs = now
    elseif c.shotMs and now - c.shotMs >= 2000 then
        optionsCheck = false
        print("[pzopt-harness] options: done, quitting")
        getCore():quit()
    end
end

-- ingame_options (repro-ui): the in-world entry points to this file's options rig (a later "local optionsCheck" shadows it)
local function optionsFromWorld(seq) optionsCheck = { tab = "Optimizations", seq = seq } end
local function optionsWorldTick()
    if not optionsCheck then return end
    local ok, err = pcall(optionsTick)
    if not ok then print("[pzopt-harness] ingame options: rig error " .. tostring(err)); optionsCheck = false; getCore():quit() end
end

local function onMainMenuEnter()
    local flags = readFlags()
    if not flags or not flags.mode then return end
    if flags.started then
        print("[pzopt-harness] run finished, quitting to desktop")
        getCore():quit()
        return
    end
    if flags.consumed and not flags.pad and not (flags.spawn_reset_done and not flags.spawn_resumed) then return end -- Continue already triggered by this process (Lua was reset)
    if flags.pad and pad == false then return end -- pad script done, quitting
    if flags.compat_check and flags.compat_check ~= "" then
        if compatCheck == nil then
            appendFlag("consumed=1")
            compatCheck = { choose = (flags.compat_choose and flags.compat_choose ~= "") and flags.compat_choose or nil }
        end
        return
    end
    if flags.menu_hold and flags.menu_hold ~= "" then
        if menuHold == nil then
            appendFlag("consumed=1")
            menuHold = { secs = tonumber(flags.menu_hold) or 20 }
        end
        return
    end
    if flags.mods_shot and flags.mods_shot ~= "" then   -- pzopt_harness_mods.lua drives the Mods screen and quits
        appendFlag("consumed=1")
        return
    end
    if flags.spawn_select and flags.spawn_select ~= "" then
        if spawnCheck == nil then
            appendFlag("consumed=1")
            local resumed = flags.spawn_reset_done ~= nil
            if resumed then appendFlag("spawn_resumed=1"); print("[pzopt-harness] spawn: resumed after the Lua reset") end
            spawnCheck = { pattern = string.lower((string.gsub(flags.spawn_select, "_", " "))), step = 0, at = 0,
                wait = tonumber(flags.spawn_wait or "") or 8, next = flags.spawn_next == "1" or flags.spawn_next == "play",
                play = flags.spawn_next == "play", sandbox = flags.spawn_mode == "sandbox", input = flags.spawn_input == "1",
                enable = (flags.spawn_enable and flags.spawn_enable ~= "") and flags.spawn_enable or nil, resumed = resumed }
            if resumed then spawnCheck.step, spawnCheck.at = 1, getTimestampMs() + 3000 end
        end
        return
    end
    local anyOptions = false
    for _, k in ipairs({ "options_tab", "options_nav", "options_shots", "options_read", "options_joy", "options_io", "options_profile",
                         "options_select", "options_search", "options_clips" }) do
        if flags[k] and flags[k] ~= "" then anyOptions = true end
    end
    if anyOptions then
        if optionsCheck == nil then
            appendFlag("consumed=1")
            local shots = nil
            if flags.options_shots and flags.options_shots ~= "" then
                shots = {}
                for s in string.gmatch(flags.options_shots, "[^;]+") do table.insert(shots, s) end
            end
            optionsCheck = { tab = flags.options_tab ~= "" and flags.options_tab or nil, search = (flags.options_search and flags.options_search ~= "") and (string.gsub(flags.options_search, "_", " ")) or nil,
                clips = flags.options_clips ~= "" and flags.options_clips or nil,
                nav = flags.options_nav ~= "" and flags.options_nav or nil, shots = shots,
                read = (flags.options_read and flags.options_read ~= "") and {} or nil,
                joy = (flags.options_joy and flags.options_joy ~= "") and (function()
                    local t = {}
                    for s in string.gmatch(flags.options_joy, "[^,]+") do table.insert(t, s) end
                    return t
                end)() or nil,
                select = flags.options_select ~= "" and flags.options_select or nil,
                io = (flags.options_io and flags.options_io ~= "") and {} or nil,
                profile = (flags.options_profile and flags.options_profile ~= "") and flags.options_profile or nil,
                seq = (flags.options_seq and flags.options_seq ~= "") and (function()
                    local t = {}
                    for s in string.gmatch(flags.options_seq, "[^;]+") do
                        local secs, tab = string.match(s, "^([%d%.]+)/(.+)$")
                        if secs then table.insert(t, { secs = tonumber(secs), tab = tab }) end
                    end
                    return t
                end)() or nil }
        end
        return
    end
    print("[pzopt-harness] mode=" .. tostring(flags.mode) .. " quit_after=" .. tostring(flags.quit_after))
    if flags.menu_check and flags.menu_check ~= "" and MainScreen.instance then
        -- menu_check=1 (2026-09-23): show and hide the main menu's server settings, sandbox, character creation,
        -- multiplayer, credits and spawn screens before the harness presses Continue; logs build time and any error
        -- per screen (the rig of the dropped lazyMenuScreens experiment, kept for menu changes)
        for _, k in ipairs({ "serverSettingsScreen", "sandOptions", "charCreationMain", "multiplayer", "creditsScreen", "mapSpawnSelect" }) do
            local o = MainScreen.instance[k]
            if not o then
                print("[pzopt-harness] menu check: " .. k .. " missing")
            else
                local wasPending = o.pzoptCreatePending
                local t0 = getTimestampMs()
                local ok, err = pcall(function() o:setVisible(true); o:setVisible(false) end)
                print("[pzopt-harness] menu check: " .. k .. " pending=" .. tostring(wasPending) .. " -> built="
                    .. tostring(o.pzoptCreated) .. " in " .. (getTimestampMs() - t0) .. " ms, ok=" .. tostring(ok)
                    .. (ok and "" or (" error " .. tostring(err))))
            end
        end
        local ok2, err2 = pcall(function() return MainScreen.instance.sandOptions:getSandboxPreset() end)
        print("[pzopt-harness] menu check: sandOptions:getSandboxPreset ok=" .. tostring(ok2) .. " " .. tostring(err2))
    end
    pending = flags
end


-- pad=1 (run.sh --pad, 2026-09-23): menu profiling with a virtual pad. The harness stays on the main menu,
-- writes Lua/pzopt-pad-ready.txt once the menu accepts input (run.sh then feeds the pad script), logs every pad
-- handler call ("[pzopt-pad] <handler> t=<epoch ms> ms=<handler ms> <focus before> -> <focus after>"), every menu
-- frame (OnFETick gap) of 25 ms or more, and quits once run.sh appends pad_done=1 to the flag file.
local pad = nil
local function padFocus(jd)
    local f = jd and jd.focus
    if not f then return "none" end
    local s = tostring(f.Type or "?")
    if f.joypadIndexY then s = s .. ":" .. tostring(f.joypadIndexY) end
    if f.joypadIndex then s = s .. "," .. tostring(f.joypadIndex) end
    return s
end
local function padWrap(name)
    local orig = JoypadControllerData[name]
    if type(orig) ~= "function" then return end
    JoypadControllerData[name] = function(self, a, b)
        local before = padFocus(self.joypad)
        local t0 = getTimestampMs()
        orig(self, a, b)
        local t1 = getTimestampMs()
        print("[pzopt-pad] " .. name .. (a ~= nil and ("(" .. tostring(a) .. ")") or "") .. " id=" .. tostring(self.id)
            .. " t=" .. tostring(t0) .. " ms=" .. tostring(t1 - t0) .. " " .. before .. " -> " .. padFocus(self.joypad))
    end
end
local function startPad()
    for _, n in ipairs({ "onPressUp", "onPressDown", "onPressLeft", "onPressRight", "onPressButton", "onReleaseButton",
                         "onPressButtonNoFocus" }) do
        padWrap(n)
    end
    pad = { last = getTimestampMs(), n = 0, check = 0 }
    local w = getFileWriter("pzopt-pad-ready.txt", true, false)
    if w then w:write("ready_epoch_ms=" .. tostring(pad.last) .. "\n"); w:close() end
    print("[pzopt-pad] menu ready t=" .. tostring(pad.last))
end
local function padTick()
    local now = getTimestampMs()
    local gap = now - pad.last
    pad.last = now
    pad.n = pad.n + 1
    if gap >= 25 then print("[pzopt-pad] slow frame t=" .. tostring(now) .. " gap=" .. tostring(gap)) end
    if now >= pad.check then
        pad.check = now + 500
        local flags = readFlags()
        if flags and flags.pad_done then
            print("[pzopt-pad] script done, " .. pad.n .. " menu frames; quitting to desktop")
            pad = false
            getCore():quit()
        end
    end
end

-- OnFETick is the only per-frame event the main menu fires (OnTickEvenPaused is in-world only,
-- which is why earlier versions of this file never pressed Continue by themselves)
local function onFETick()
    if spawnCheck then
        local ok, err = pcall(spawnTick)
        if not ok then print("[pzopt-harness] spawn: rig error " .. tostring(err)); spawnCheck = false; getCore():quit() end
        return
    end
    if menuHold then
        local ok, err = pcall(menuHoldTick)
        if not ok then print("[pzopt-harness] menu hold: rig error " .. tostring(err)); menuHold = false; getCore():quit() end
        return
    end
    if compatCheck then
        local ok, err = pcall(compatTick)
        if not ok then print("[pzopt-harness] compat check: rig error " .. tostring(err)); compatCheck = false; getCore():quit() end
        return
    end
    if optionsCheck then
        local ok, err = pcall(optionsTick)
        if not ok then print("[pzopt-harness] options: rig error " .. tostring(err)); optionsCheck = false; getCore():quit() end
        return
    end
    if pad then padTick() end
    if pending then
        local ms = MainScreen.instance
        -- MainScreen ignores menu actions while its own start-up delay runs; wait for that, nothing more
        if not ms or (ms.delay and ms.delay > 0) then return end
        local flags = pending
        pending = nil
        appendFlag("consumed=1")
        -- quit_after counts from here whether we continue or the game is already loading the save
        if flags.quit_after then
            local secs = tonumber(flags.quit_after)
            if secs then quitAtMs = getTimestampMs() + secs * 1000 end
        end
        if flags.pad then
            startPad()
            return
        end
        if getPlayer() or ms.inGame then
            print("[pzopt-harness] a world is already loading; not continuing")
            return
        end
        if not MainScreen.latestSaveWorld then
            print("[pzopt-harness] no latest save to continue")
            return
        end
        print("[pzopt-harness] continuing latest save " .. tostring(MainScreen.latestSaveWorld) .. " (" .. tostring(MainScreen.latestSaveGameMode) .. ")")
        MainScreen.continueLatestSave(MainScreen.latestSaveGameMode, MainScreen.latestSaveWorld)
        if flags.stock_drive and flags.inputlag == "1" then
            -- no pzopt.Harness presses click-to-start in an uninstalled game: the input driver's script (run.sh
            -- --inputlag) clicks it, so it starts now instead of after the player exists
            local w = getFileWriter("pzopt-inputlag-ready.txt", true, false)
            if w then w:write("ready_epoch_ms=" .. tostring(getTimestampMs()) .. "\n"); w:close() end
            print("[pzopt-stockdrive] loading; the input driver clicks click-to-start")
        end
    end
end

-- lure=<animal type> (2026-09-22, CanSee repro): lure_at seconds after the player exists, spawn the
-- animal 8 tiles away, put a carrot in the primary hand and queue the stock ISLureAnimal action, i.e.
-- the context menu's "Lure" path (lureAnimal -> IsoAnimal.tryLure -> CanSee(IsoMovingObject)). A
-- status line every 2 s: distance, lured count, current action. Stock: the animal walks up to the player.
local lure = nil
local function lureTick()
    if lure == false then return end
    local player = getPlayer()
    if not player then return end
    if lure == nil then
        local flags = readFlags()
        if not flags or not flags.lure or flags.lure == "" then lure = false; return end
        lure = { kind = flags.lure, breed = flags.lure_breed or "holstein",
                 atMs = getTimestampMs() + (tonumber(flags.lure_at) or 10) * 1000 }
    end
    local now = getTimestampMs()
    if not lure.animal then
        if now < lure.atMs then return end
        local sq = player:getCurrentSquare()
        local target = nil
        for _, d in ipairs({ {8, 0}, {-8, 0}, {0, 8}, {0, -8}, {6, 6}, {-6, -6} }) do
            local s = getCell():getGridSquare(sq:getX() + d[1], sq:getY() + d[2], sq:getZ())
            if s and s:isFree(false) then target = s; break end
        end
        if not target then print("[pzopt-harness] lure: no free square 8 tiles from the player"); lure = false; return end
        local breed = AnimalDefinitions.getDef(lure.kind):getBreedByName(lure.breed)
        local animal = addAnimal(getCell(), target:getX(), target:getY(), target:getZ(), lure.kind, breed)
        animal:addToWorld()
        lure.animal = animal
        local item = player:getInventory():AddItem("Base.Carrots")
        player:setPrimaryHandItem(item)
        print("[pzopt-harness] lure: " .. lure.kind .. " at " .. target:getX() .. "," .. target:getY() .. ", player at " .. sq:getX() .. "," .. sq:getY() .. ", queueing ISLureAnimal with " .. item:getFullType())
        ISTimedActionQueue.add(ISLureAnimal:new(player, animal, item))
        lure.logMs = now
        return
    end
    if now - lure.logMs >= 2000 then
        lure.logMs = now
        print(string.format("[pzopt-harness] lure: dist=%.1f lured=%d action=%s", lure.animal:DistTo(player),
            player:getLuredAnimals():size(),
            tostring(ISTimedActionQueue.getTimedActionQueue(player).queue[1] and ISTimedActionQueue.getTimedActionQueue(player).queue[1].Type)))
    end
end

-- squarehud=R (2026-10-19, Workshop report "a zombie broke a window / furniture placed: ~3 fps, 3k errors a minute"):
-- squarehud_at (3) s after the player exists, a small top-level Lua panel whose render reads getObjects():size() of
-- every square within R tiles of the player's square then (the squares stay fixed) and draws one line that does not
-- show the count (a mod HUD reading the squares around the player). uiVerify then logs one List.size call per square,
-- a PZArrayList receiver each; a change of one square's objects that draws the same is learned as noise, and the old
-- Log.noisy compared the other squares' lists with Objects.equals (PZArrayList.listIterator throws, every frame).
-- furniture=S: every S s (from squarehud_at + 2) a chair is put on the square south-east of that centre square
-- (IsoGridSquare.AddTileObject), the next time taken away again (RemoveTileObject); one log line each.
local squareHud = nil
local function squareHudTick()
    if squareHud == false then return end
    local player = getPlayer()
    if not player then return end
    if squareHud == nil then
        local flags = readFlags()
        if not flags or not flags.squarehud or flags.squarehud == "" then squareHud = false; return end
        squareHud = { r = tonumber(flags.squarehud) or 4, atMs = getTimestampMs() + (tonumber(flags.squarehud_at) or 3) * 1000,
                      every = tonumber(flags.furniture) or 0 }
    end
    local now = getTimestampMs()
    if now < squareHud.atMs then return end
    if not squareHud.panel then
        local sq = player:getCurrentSquare()
        if not sq then return end
        PzoptSquareHud = PzoptSquareHud or ISUIElement:derive("PzoptSquareHud")
        function PzoptSquareHud:render()
            local n = 0
            local cell = getCell()
            for x = self.cx - self.r, self.cx + self.r do
                for y = self.cy - self.r, self.cy + self.r do
                    local s = cell:getGridSquare(x, y, self.cz)
                    if s then n = n + s:getObjects():size() end
                end
            end
            self:drawText(n > 0 and "pzopt square hud: objects around" or "pzopt square hud: nothing around", 2, 2, 1, 1, 1, 1, UIFont.Small)
        end
        local p = PzoptSquareHud:new(40, 200, 260, 24)
        p.cx, p.cy, p.cz, p.r = sq:getX(), sq:getY(), sq:getZ(), squareHud.r
        p:initialise()
        p:addToUIManager()
        squareHud.panel = p
        squareHud.nextMs = now + 2000
        print(string.format("[pzopt-harness] squarehud: panel up, %d squares around %d,%d,%d", (2 * p.r + 1) ^ 2, p.cx, p.cy, p.cz))
        return
    end
    if squareHud.every > 0 and now >= squareHud.nextMs then
        squareHud.nextMs = now + squareHud.every * 1000
        local p = squareHud.panel
        local s = getCell():getGridSquare(p.cx + 1, p.cy + 1, p.cz)
        if not s then return end
        if squareHud.chair then
            s:RemoveTileObject(squareHud.chair)
            squareHud.chair = nil
            print(string.format("[pzopt-harness] furniture: chair removed at %d,%d (%d objects)", s:getX(), s:getY(), s:getObjects():size()))
        else
            squareHud.chair = IsoObject.new(s, "furniture_seating_indoor_01_0", "Chair")
            s:AddTileObject(squareHud.chair)
            print(string.format("[pzopt-harness] furniture: chair placed at %d,%d (%d objects)", s:getX(), s:getY(), s:getObjects():size()))
        end
    end
end

-- glass=R (2026-10-01, puddle reflection flicker with broken glass): glass_at (4) s after the player exists, broken
-- glass (IsoGridSquare.addBrokenGlass, the stock IsoBrokenGlass floor sprite, baked as a MinusFloor object) on every
-- free level-0 square within R tiles of the player, glass_dx / glass_dy (0) tiles away; one log line with the count.
-- With weather=storm thunder_secs=0 puddles=0.8 and a still camera (route=S:1 speed=1 hold=N) the puddles lie over
-- the shards: runs `glass-*`.
local glass = nil
local function glassTick()
    if glass == false then return end
    local player = getPlayer()
    if not player then return end
    if glass == nil then
        local flags = readFlags()
        if not flags or not flags.glass or flags.glass == "" then glass = false; return end
        local win = flags.glass:match("^window([NW]?)$")
        if flags.glass == "find" then win = "" end -- list the candidates (nearest first, up to 12), smash nothing
        glass = { r = win and (tonumber(flags.glass_r) or 40) or (tonumber(flags.glass) or 3), window = win,
                  dx = tonumber(flags.glass_dx) or 0, dy = tonumber(flags.glass_dy) or 0,
                  atMs = getTimestampMs() + (tonumber(flags.glass_at) or 4) * 1000, find = flags.glass == "find",
                  n = tonumber(flags.glass_n) or 1 }
    end
    if getTimestampMs() < glass.atMs then return end
    local sq = player:getCurrentSquare()
    if not sq then return end
    if glass.window then
        -- glass=windowN|windowW: the nearest exterior window of that facing within glass_r (40) tiles on level 0,
        -- smashed (no alarm), broken glass on both of its squares, the player put 3 tiles along the wall on the
        -- outside square so a route E:6 (N) / S:6 (W) walks past it; the route origin is the player's position at
        -- route start, so this teleport must land before it (glass_at)
        local want = glass.window
        local cell = getCell()
        local r = glass.r
        local best, bestD, bestOut, bestWx, bestWy = nil, 1e9, nil, 0, 0
        local fb, fbD, fbOut, fbWx, fbWy = nil, 1e9, nil, 0, 0 -- fallback: any window of the facing with a free opposite square
        local nSq, nWin, nFacing, nShown = 0, 0, 0, 0
        local found = {}
        for x = sq:getX() - r, sq:getX() + r do
            for y = sq:getY() - r, sq:getY() + r do
                local s = cell:getGridSquare(x, y, 0)
                if s then
                    nSq = nSq + 1
                    local objs = s:getObjects()
                    for i = 0, objs:size() - 1 do
                        local o = objs:get(i)
                        if instanceof(o, "IsoWindow") then
                            nWin = nWin + 1
                            local north = o:getNorth()
                            if (want == "" or north == (want == "N")) and not o:isDestroyed() then
                                nFacing = nFacing + 1
                                local opp = cell:getGridSquare(north and x or x - 1, north and y - 1 or y, 0)
                                -- "outside" = the square has puddle geometry (level-0 outdoor squares only; isOutside() and
                                -- getRoom() said false / nil for every square of the Rosewood store, 2026-10-01)
                                local sOut, oOut = s:getPuddles() ~= nil, opp and opp:getPuddles() ~= nil or false
                                local d = (x - sq:getX()) ^ 2 + (y - sq:getY()) ^ 2
                                table.insert(found, { d = d, x = x, y = y, north = north, sOut = sOut, oOut = oOut, o = o })
                                if glass.find then
                                elseif nShown < 4 then
                                    nShown = nShown + 1
                                    print(string.format("[pzopt-harness] glass: window%s %d,%d puddles=%s opposite puddles=%s", north and "N" or "W", x, y,
                                        tostring(sOut), tostring(oOut)))
                                end
                                local out = nil
                                if opp and oOut and not sOut then out = opp
                                elseif opp and sOut and not oOut then out = s end
                                if out and d < bestD then best, bestD, bestOut, bestWx, bestWy = o, d, out, x, y end
                                if opp and d < fbD then fb, fbD, fbOut, fbWx, fbWy = o, d, (sOut and s or opp), x, y end
                            end
                        end
                    end
                end
            end
        end
        print(string.format("[pzopt-harness] glass: scanned %d squares, %d windows, %d facing %s, exterior match=%s", nSq, nWin, nFacing, want, tostring(best ~= nil)))
        if glass.find then
            table.sort(found, function(a, b) return a.d < b.d end)
            for i = 1, math.min(12, #found) do
                local w = found[i]
                local ox, oy = w.x, w.y
                if w.north then oy = oy - 1 else ox = ox - 1 end
                print(string.format("[pzopt-harness] glass: find %2d: window%s %d,%d dist %.0f puddles=%s opposite(%d,%d) puddles=%s", i, w.north and "N" or "W",
                    w.x, w.y, math.sqrt(w.d), tostring(w.sOut), ox, oy, tostring(w.oOut)))
            end
            glass = false
            return
        end
        if not best and fb then best, bestOut, bestWx, bestWy = fb, fbOut, fbWx, fbWy; print("[pzopt-harness] glass: no exterior test match, taking the nearest window of that facing") end
        if not best then print("[pzopt-harness] glass: no window" .. want .. " within " .. r .. " tiles of " .. sq:getX() .. "," .. sq:getY()); glass = false; return end
        best:smashWindow(false, false)
        best:addBrokenGlass(false)
        best:addBrokenGlass(true)
        if glass.n > 1 then
            -- glass_n=N: the N nearest windows of the facing (a shop front), glass on both squares of each
            table.sort(found, function(a, b) return a.d < b.d end)
            local k = 1
            for i = 1, #found do
                if k >= glass.n then break end
                local w = found[i]
                if w.o ~= best and not w.o:isDestroyed() then
                    w.o:smashWindow(false, false); w.o:addBrokenGlass(false); w.o:addBrokenGlass(true); k = k + 1
                    print(string.format("[pzopt-harness] glass: also window%s %d,%d", w.north and "N" or "W", w.x, w.y))
                end
            end
        end
        local north = best:getNorth()
        print(string.format("[pzopt-harness] glass: window%s at %d,%d smashed, glass on both squares, outside square %d,%d (put the route past it with start= / route=)",
            north and "N" or "W", bestWx, bestWy, bestOut:getX(), bestOut:getY()))
        glass = false
        return
    end
    local cx, cy = sq:getX() + glass.dx, sq:getY() + glass.dy
    local n, skipped = 0, 0
    for x = cx - glass.r, cx + glass.r do
        for y = cy - glass.r, cy + glass.r do
            local s = getCell():getGridSquare(x, y, 0)
            if s and s:isFree(false) and s:getBrokenGlass() == nil then
                s:addBrokenGlass(); n = n + 1
            else skipped = skipped + 1 end
        end
    end
    print(string.format("[pzopt-harness] glass: %d squares of broken glass round %d,%d (r=%d), %d skipped", n, cx, cy, glass.r, skipped))
    glass = false
end

-- options_check=S (2026-09-23; since 2026-10-18 the settings are the PZ OPTIMIZATION screen, not an Options tab): S seconds
-- into the world, log the in-game options screen's lazy build (pending / created, the key bindings it loaded) and build it
-- through toUI as opening it does, then open the pause menu, the PZ OPTIMIZATION screen from its item (logs the page and
-- the screen's size, writes Screenshots/pzopt-options-check.png), close it back onto the pause menu and resume.
-- (pzmenu_check is the fuller in-game check: the overlay toggle and a live key.)
local ingameOptions = nil
local function optionsCheckTick()
    if ingameOptions == false then return end
    if not getPlayer() then return end
    local now = getTimestampMs()
    if ingameOptions == nil then
        local flags = readFlags()
        if not flags or not flags.options_check or flags.options_check == "" then ingameOptions = false; return end
        ingameOptions = { atMs = now + (tonumber(flags.options_check) or 10) * 1000, step = 0 }
    end
    local c = ingameOptions
    if now < c.atMs then return end
    c.atMs = now + 2000
    local ms = MainScreen.instance
    local function menuKey()
        local k = getCore():getKey("Main Menu")
        return k ~= 0 and k or Keyboard.KEY_ESCAPE
    end
    if c.step == 0 then
        local mo = ms and ms.mainOptions
        if not mo then print("[pzopt-harness] options check: no in-game MainOptions"); ingameOptions = false; return end
        local keys = {}
        for _, k in ipairs({"Forward", "Backward", "Left", "Right", "Run", "Interact", "Toggle Inventory", "Aim"}) do
            table.insert(keys, k .. "=" .. tostring(getCore():getKey(k)))
        end
        print("[pzopt-harness] options check: screen pending=" .. tostring(mo.pzoptCreatePending) .. " created=" .. tostring(mo.pzoptCreated)
            .. ", keys " .. table.concat(keys, " "))
        local tui = getTimestampMs()
        mo:toUI() -- opening the screen calls toUI first (MainScreen)
        local names = {}
        for _, v in ipairs(mo.tabs and mo.tabs.viewList or {}) do table.insert(names, v.name) end
        print("[pzopt-harness] options check: toUI (builds a deferred screen) " .. (getTimestampMs() - tui) .. " ms, pending="
            .. tostring(mo.pzoptCreatePending) .. ", tabs " .. table.concat(names, " | "))
        ToggleEscapeMenu(menuKey())
        print("[pzopt-harness] options check: pause menu open=" .. tostring(ms:isVisible()) .. " item=" .. tostring(ms.pzoptMenuOption and ms.pzoptMenuOption.name))
    elseif c.step == 1 then
        if not ms.pzoptMenuOption then print("[pzopt-harness] options check: no PZ OPTIMIZATION item in the pause menu"); ingameOptions = false; return end
        ms.pzoptMenuOption.onMouseDown(ms.pzoptMenuOption, 0, 0)
        local m = PzoptMainMenu.instance()
        print("[pzopt-harness] options check: PZ OPTIMIZATION screen open=" .. tostring(m ~= nil) .. " page=" .. tostring(m and m.selected)
            .. " size=" .. tostring(m and m.width) .. "x" .. tostring(m and m.height) .. " in game=" .. tostring(ms.inGame))
    elseif c.step == 2 then
        getCore():TakeFullScreenshot("pzopt-options-check.png")
    elseif c.step == 3 then
        local m = PzoptMainMenu.instance()
        if m then m:close("options_check") end
        print("[pzopt-harness] options check: screen closed, pause menu open=" .. tostring(ms:isVisible()))
    else
        ToggleEscapeMenu(menuKey())
        print("[pzopt-harness] options check: done, resumed (pause menu open=" .. tostring(ms:isVisible()) .. ")")
        ingameOptions = false
        return
    end
    c.step = c.step + 1
end

-- pause_menu=S (2026-09-23): S seconds into the world, open the pause menu the way Esc does (ToggleEscapeMenu),
-- close it pause_menu_secs (5) later. pause_menu_cap=<fps> sets the "Menu framerate" combo to that entry for the
-- rig and puts the player's choice back when the menu closes. The frame cap's own console line ("frame cap: menu
-- phase 5.0 s, 300 frames, 60.0 fps (cap 60 fps)") is the measurement: the pause menu runs at the menu cap.
local pauseMenu = nil
-- pause_menu_modrow=true (2026-10-19, Workshop "the PZO entries in the ESC menu overlap other mods' options"): before the
-- menu opens, a row added the way pause-menu mods add theirs (an ISLabel right under Options, Exit and Quit moved down by
-- its height, nothing else); 1.5 s after opening every visible row of the column is logged ("[pzopt-harness] pause menu
-- row: <name> y=<y> h=<h>") with the overlaps ("[pzopt-harness] pause menu rows: N visible, M overlapping") and
-- Screenshots/pzopt-pausemenu.png is written.
function PzoptHarnessPauseModRow()
    local ms = MainScreen.instance
    if not ms or not ms.optionsOption or not ms.bottomPanel then print("[pzopt-harness] pause menu: no menu for the mod row") return end
    local h = ms.optionsOption:getHeight()
    local row = ISLabel:new(ms.optionsOption:getX(), ms.optionsOption:getBottom(), h, "FAKE MOD OPTIONS", 1, 0.8, 0.3, 1, UIFont.Large, true)
    row.internal = "FAKE_MOD_ROW"
    row:initialise()
    ms.bottomPanel:addChild(row)
    if ms.exitOption then ms.exitOption:setY(ms.exitOption:getY() + h) end
    if ms.quitToDesktopOption then ms.quitToDesktopOption:setY(ms.quitToDesktopOption:getY() + h) end
    ms.bottomPanel:setHeight(ms.bottomPanel:getHeight() + h)
    print("[pzopt-harness] pause menu: fake mod row added at y=" .. row:getY())
end
function PzoptHarnessPauseRows()
    local ms = MainScreen.instance
    if not ms or not ms.bottomPanel then return end
    local rows = {}
    for _, c in pairs(ms.bottomPanel:getChildren()) do
        if c.Type == "ISLabel" and c:isVisible() then table.insert(rows, c) end
    end
    table.sort(rows, function(a, b) return a:getY() < b:getY() end)
    local overlaps = 0
    for i, c in ipairs(rows) do
        print("[pzopt-harness] pause menu row: " .. tostring(c.internal or c.name) .. " y=" .. c:getY() .. " h=" .. c:getHeight())
        for j = i + 1, #rows do
            local d = rows[j]
            if d:getY() < c:getY() + c:getHeight() - 1 and c:getY() < d:getY() + d:getHeight() - 1 then overlaps = overlaps + 1 end
        end
    end
    print("[pzopt-harness] pause menu rows: " .. #rows .. " visible, " .. overlaps .. " overlapping, panel h=" .. ms.bottomPanel:getHeight())
    getCore():TakeFullScreenshot("pzopt-pausemenu.png")
end
local function escapeKey()
    local k = getCore():getKey("Main Menu")
    return k ~= 0 and k or Keyboard.KEY_ESCAPE
end
local function pauseMenuTick()
    if pauseMenu == false then return end
    if not getPlayer() then return end
    if pauseMenu == nil then
        local flags = readFlags()
        if not flags or not flags.pause_menu or flags.pause_menu == "" then pauseMenu = false; return end
        pauseMenu = { openMs = getTimestampMs() + (tonumber(flags.pause_menu) or 10) * 1000,
            secs = tonumber(flags.pause_menu_secs) or 5, cap = tonumber(flags.pause_menu_cap),
            modRow = flags.pause_menu_modrow == "true" }
    end
    local now = getTimestampMs()
    if not pauseMenu.closeMs and now >= pauseMenu.openMs then
        local perf = getPerformance()
        if pauseMenu.cap then
            pauseMenu.oldIndex = perf:getMenuFramerateIndex()
            local fpsTable = { 500, 430, 400, 330, 300, 244, 240, 165, 144, 120, 95, 90, 75, 60, 55, 45, 30, 24 } -- FrameCap.FPS_TABLE
            for i, fps in ipairs(fpsTable) do
                if fps == pauseMenu.cap then perf:setMenuFramerateIndex(i + 2) end
            end
        end
        if pauseMenu.modRow then PzoptHarnessPauseModRow() end
        ToggleEscapeMenu(escapeKey())
        pauseMenu.closeMs = now + pauseMenu.secs * 1000
        pauseMenu.rowsMs = now + 1500
        print("[pzopt-harness] pause menu: open=" .. tostring(MainScreen.instance and MainScreen.instance:isVisible())
            .. " menu cap index=" .. tostring(perf:getMenuFramerateIndex()))
    elseif pauseMenu.rowsMs and now >= pauseMenu.rowsMs then
        pauseMenu.rowsMs = nil
        PzoptHarnessPauseRows()
    elseif pauseMenu.closeMs and not pauseMenu.closed and now >= pauseMenu.closeMs then
        if MainScreen.instance and MainScreen.instance:isVisible() then
            ToggleEscapeMenu(escapeKey())
        end
        pauseMenu.closed = true
        print("[pzopt-harness] pause menu: closed, open=" .. tostring(MainScreen.instance and MainScreen.instance:isVisible()))
    elseif pauseMenu.closed and now >= pauseMenu.closeMs + 1000 then
        -- a second later, so the frame cap's phase line still names the rig's cap
        if pauseMenu.oldIndex then getPerformance():setMenuFramerateIndex(pauseMenu.oldIndex) end
        pauseMenu = false
    end
end

-- ingame_options=S (repro-ui 2026-10-16): S seconds into the world open the pause menu (as Esc does), then Options, and
-- play options_seq there (see options_seq above: tabs in turn, TIP lines, quits the game after the last step).
local ingameOptions = nil
local function ingameOptionsTick()
    if ingameOptions == false then return end
    if not getPlayer() then return end
    if ingameOptions == nil then
        local flags = readFlags()
        if not flags or not flags.ingame_options or flags.ingame_options == "" then ingameOptions = false; return end
        ingameOptions = { openMs = getTimestampMs() + (tonumber(flags.ingame_options) or 8) * 1000, flags = flags }
    end
    local now = getTimestampMs()
    if not ingameOptions.opened then
        if now < ingameOptions.openMs then return end
        ingameOptions.opened = true
        ToggleEscapeMenu(escapeKey())
        print("[pzopt-harness] ingame options: pause menu open=" .. tostring(MainScreen.instance and MainScreen.instance:isVisible()))
        local seq = {}
        for e in string.gmatch(ingameOptions.flags.options_seq or "10/1", "[^;]+") do
            local secs, tab = string.match(e, "^([%d%.]+)/(.+)$")
            if secs then table.insert(seq, { secs = tonumber(secs), tab = tab }) end
        end
        optionsFromWorld(seq)
        return
    end
    optionsWorldTick()
end

-- inputlag=1 (run.sh --inputlag, 2026-09-24): in-game input-lag profile. 6 s after the player exists this writes
-- Lua/pzopt-inputlag-ready.txt (run.sh then feeds the driver script); inputlag_pad=1 in the flag file gives the
-- driver's virtual Xbox 360 pad to player 1 the way JoypadState.onGameStart does for a menu pad; inputlag_done=1 quits.
local INPUTLAG_GUID = "030000005e0400008e02000010010000"
local inputLag = nil
local function inputLagBindPad()
    local id = nil
    for i = 0, getControllerCount() - 1 do
        if isControllerConnected(i) and getControllerGUID(i) == INPUTLAG_GUID then id = i end
    end
    if not id then print("[pzopt-inputlag] no connected controller with GUID " .. INPUTLAG_GUID); return end
    local controller = JoypadState.controllers[id]
    local joypadData = JoypadState.joypads[1]
    joypadData:setActive(true)
    controller:setJoypad(joypadData)
    joypadData.inMainMenu = false
    joypadData.focus = nil
    joypadData.player = 0
    JoypadState.players[1] = joypadData
    local playerObj = getSpecificPlayer(0)
    setPlayerJoypad(0, joypadData.id, playerObj, nil, false)
    getPlayerInventory(0):setController(joypadData.id)
    getPlayerLoot(0):setController(joypadData.id)
    print("[pzopt-inputlag] pad (controller " .. id .. ") bound to player 1, joypad bind " .. tostring(playerObj:getJoypadBind()))
end
local function inputLagTick()
    if inputLag == false then return end
    if not getPlayer() then return end
    local now = getTimestampMs()
    if inputLag == nil then
        local flags = readFlags()
        if not flags or flags.inputlag ~= "1" then inputLag = false; return end
        if flags.stock_drive then inputLag = { ready = true, check = 0, stock = true } -- the drive stays seated (stockDriveTick)
        else inputLag = { readyMs = now + 6000, check = 0 } end
    end
    local player = getPlayer()
    if player:getVehicle() and not inputLag.stock then
        -- the bench save starts in the drive bench's car: W/S would be throttle / brake. Get out on foot first
        if not inputLag.exiting then
            inputLag.exiting = true
            ISTimedActionQueue.add(ISExitVehicle:new(player))
            print("[pzopt-inputlag] player is in a vehicle; exiting it before the script")
        end
        inputLag.readyMs = now + 3000
        return
    end
    if not inputLag.ready and now >= inputLag.readyMs then
        inputLag.ready = true
        local w = getFileWriter("pzopt-inputlag-ready.txt", true, false)
        if w then w:write("ready_epoch_ms=" .. tostring(now) .. "\n"); w:close() end
        print("[pzopt-inputlag] world ready t=" .. tostring(now))
    end
    if now < inputLag.check then return end
    inputLag.check = now + 250
    local flags = readFlags()
    if not flags then return end
    if flags.inputlag_pad and not inputLag.bound then
        inputLag.bound = true
        local ok, err = pcall(inputLagBindPad)
        if not ok then print("[pzopt-inputlag] pad bind error " .. tostring(err)) end
    end
    if flags.inputlag_done then
        print("[pzopt-inputlag] script done, quitting")
        inputLag = false
        getCore():quit()
    end
end

-- stock_drive=1 (2026-10-18, the car flicker on a truly uninstalled game: `--install stock --inputlag
-- harness/inputlag/stock-click.txt`): the drive mode in Lua with the game's own calls only, since pzopt.Harness is not
-- there. On the bench save (the player stands at the KY-60 path start, 8010,11204): stock_drive_hour (1) o'clock,
-- weather stopped, grid power on and the light switches within 50 tiles on (Scene.lightsOnAround), a Base.RaceCar12
-- spawned facing east with headlights on and the player seated, the camera zoomed out, then the game's cruise control
-- (setRegulator) at stock_drive_kmh (120) with no steering: the road runs straight east. A line every second
-- ([pzopt-stockdrive] t= pos= kmh= yaw=); quits after stock_drive_secs (35) of driving.
local stockDrive = nil
local function stockDriveTick()
    if stockDrive == false then return end
    local player = getPlayer()
    if not player then return end
    local now = getTimestampMs()
    if stockDrive == nil then
        local flags = readFlags()
        if not flags or not flags.stock_drive then stockDrive = false; return end
        stockDrive = { t0 = now, step = 0, hour = tonumber(flags.stock_drive_hour) or 1,
            kmh = tonumber(flags.stock_drive_kmh) or 120, secs = tonumber(flags.stock_drive_secs) or 35, logMs = 0 }
        appendFlag("started=1") -- back at the menu = the run is over (the menu quits to the desktop)
    end
    local s = stockDrive
    local dt = now - s.t0
    if s.step == 0 then
        s.step = 1
        local gt = getGameTime()
        gt:setLastTimeOfDay(s.hour); gt:setTimeOfDay(s.hour)
        getClimateManager():stopWeatherAndThunder()
        getWorld():setHydroPowerOn(true)
        local shut = math.ceil(getWorld():getWorldAgeDays()) + 30
        local opt = getSandboxOptions():getOptionByName("ElecShutModifier")
        if opt:getValue() < shut then opt:setValue(shut) end
        local on, px, py = 0, math.floor(player:getX()), math.floor(player:getY())
        for z = 0, 3 do for y = py - 50, py + 50 do for x = px - 50, px + 50 do
            local sq = getCell():getGridSquare(x, y, z)
            if sq then
                local objs = sq:getObjects()
                for i = 0, objs:size() - 1 do
                    local o = objs:get(i)
                    if instanceof(o, "IsoLightSwitch") and not o:isActivated() then o:setActive(true, false, true); on = on + 1 end
                end
            end
        end end end
        print(string.format("[pzopt-stockdrive] hour %.1f, weather stopped, hydro power on, ElecShutModifier %d, %d switches on, player at %d,%d,%d vehicle=%s",
            s.hour, opt:getValue(), on, px, py, math.floor(player:getZ()), tostring(player:getVehicle())))
    elseif s.step == 1 and dt >= 2000 then
        s.step = 2
        local v = player:getVehicle()
        if not v then
            local sq = getCell():getGridSquare(math.floor(player:getX()), math.floor(player:getY()), 0)
            v = addVehicleDebug("Base.RaceCar12", IsoDirections.E, 0, sq)
            if not v then print("[pzopt-stockdrive] could not spawn the car"); stockDrive = false; return end
            v:repair()
            player:getInventory():AddItem(v:createVehicleKey())
            v:setAngles(0, 90, 0) -- addVehicleDebug adds up to 11 deg of random heading; the road runs due east
            if not v:enter(0, player) then print("[pzopt-stockdrive] could not seat the player"); stockDrive = false; return end
        end
        s.v = v
        v:setHeadlightsOn(true)
        print("[pzopt-stockdrive] car " .. tostring(v:getScriptName()) .. " at " .. string.format("%.1f,%.1f", v:getX(), v:getY()) .. ", player seated " .. tostring(player:getVehicle() == v))
    elseif s.step == 2 and dt >= 4000 then
        s.step = 3
        local v = s.v
        v:setAngles(0, 90, 0)
        v:setKeysInIgnition(true)
        v:engineDoRunning()
        for _ = 1, 12 do getCore():doZoomScroll(0, 1) end
        v:setRegulator(true)
        v:setRegulatorSpeed(s.kmh)
        s.driveMs = now
        print("[pzopt-stockdrive] drive start epoch_ms=" .. tostring(now) .. " engine=" .. tostring(v:isEngineRunning()) .. " regulator " .. s.kmh)
    elseif s.step == 3 then
        local v = s.v
        if now - s.logMs >= 1000 then
            s.logMs = now
            print(string.format("[pzopt-stockdrive] t=%.0f pos=%.1f,%.1f kmh=%.1f yaw=%.1f headlights=%s zoom=%.2f epoch_ms=%d",
                (now - s.driveMs) / 1000, v:getX(), v:getY(), v:getCurrentSpeedKmHour(), v:getAngleY(), tostring(v:getHeadlightsOn()),
                getCore():getZoom(0), now))
        end
        if now - s.driveMs >= s.secs * 1000 then
            print("[pzopt-stockdrive] done, quitting")
            stockDrive = false
            getCore():quit()
        end
    end
end

-- world_map=<s> (2026-09-27, Workshop report "CTD when opening the map or at max zoom"): <s> seconds after the player
-- exists open the world map, then sweep its zoom (setZoom, no easing) from the maximum down to the base zoom (fully
-- zoomed out), back up and down again in world_map_steps (24) steps of world_map_step_ms (250) each, logging
-- [pzopt-map] zoom=; it stays fully zoomed out until world_map_secs (30) after opening, then closes the map.
local worldMap = nil
local function worldMapTick()
    if worldMap == false then return end
    if not getPlayer() then return end
    local now = getTimestampMs()
    if worldMap == nil then
        local flags = readFlags()
        if not flags or not flags.world_map or flags.world_map == "" then worldMap = false; return end
        worldMap = { openMs = now + (tonumber(flags.world_map) or 8) * 1000, secs = tonumber(flags.world_map_secs) or 30,
            stepMs = tonumber(flags.world_map_step_ms) or 250, steps = tonumber(flags.world_map_steps) or 24, i = 0 }
    end
    if not worldMap.opened then
        if now < worldMap.openMs then return end
        worldMap.opened = true
        print("[pzopt-map] opening world map, allowed=" .. tostring(ISWorldMap.IsAllowed()))
        ISWorldMap.ShowWorldMap(0)
        worldMap.closeMs = now + worldMap.secs * 1000
        worldMap.nextMs = now + 1000
        print("[pzopt-map] open=" .. tostring(ISWorldMap_instance and ISWorldMap_instance:isVisible()))
        return
    end
    local ui = ISWorldMap_instance
    if not ui then return end
    if now >= worldMap.closeMs then
        ui:close()
        print("[pzopt-map] closed")
        worldMap = false
        return
    end
    local n = worldMap.steps
    if now < worldMap.nextMs or worldMap.i > 3 * n then return end
    worldMap.nextMs = now + worldMap.stepMs
    local base, max = ui.mapAPI:getBaseZoom(), 18.0
    -- 0..n: max -> base, n..2n: base -> max, 2n..3n: max -> base
    local i = worldMap.i
    local t = (i <= n) and (i / n) or ((i <= 2 * n) and (1 - (i - n) / n) or ((i - 2 * n) / n))
    ui.mapAPI:setZoom(max + (base - max) * t)
    worldMap.i = i + 1
    print("[pzopt-map] step " .. i .. " zoom=" .. string.format("%.3f", ui.mapAPI:getZoomF()) .. " base=" .. string.format("%.3f", base))
end

-- pzmenu_check=S (2026-10-18): S seconds into the world (the bench save), the PZ Optimization menu over the pause menu:
-- Esc opens the pause menu, its PZ OPTIMIZATION item opens the screen, the Profiler's overlay toggle (live) and a live
-- Profiler key change at once, the screen closes back onto the pause menu, Esc resumes; every step is a [pzopt-harness]
-- pzmenu line and an in-game screenshot (Screenshots/pzopt-pzmenu-<n>.png). With pzmenu_read=1 the same screens are the
-- readability tour's stops (BENCHREAD lines + Screenshots/pzopt-bench-ui-read-*.png, harness/benchmark-readability.py).
local pzmenu = nil
local PZMENU_KEY = "overlayGraph" -- a live Profiler key (Profiler > Performance overlay > Overlay)
local function pzmenuTick()
    if pzmenu == false then return end
    if not getPlayer() then return end
    if pzmenu == nil then
        local flags = readFlags()
        if not flags or not flags.pzmenu_check or flags.pzmenu_check == "" then pzmenu = false; return end
        pzmenu = { at = getTimestampMs() + (tonumber(flags.pzmenu_check) or 10) * 1000, step = 0, read = flags.pzmenu_read == "1" }
    end
    local now = getTimestampMs()
    if now < pzmenu.at then return end
    local ms = MainScreen.instance
    local function log(t) print("[pzopt-harness] pzmenu: " .. t) end
    local function shot(n) getCore():TakeFullScreenshot("pzopt-pzmenu-" .. n .. ".png") end
    local st = pzmenu.step
    pzmenu.step = st + 1
    pzmenu.at = now + 2500
    if pzmenu.read and st == 0 then
        -- the readability tour over the pause menu (its stops; the tour logs and shoots each one). The "ig-" names keep its
        -- screenshots (Screenshots/pzopt-bench-ui-read-NN-<name>.png) apart from a main-menu tour's on the same machine
        local stops = {
            { name = "ig-pause", fn = function() ToggleEscapeMenu(escapeKey()) ms.bottomPanel.pzoptReadRoot = true end },
            { name = "ig-pzmenu", fn = function()
                ms.bottomPanel.pzoptReadRoot = nil
                ms.pzoptMenuOption.onMouseDown(ms.pzoptMenuOption, 0, 0)
                PzoptMainMenu.instance().forceHints = true
            end },
            { name = "ig-pzmenu-tools-overlay-1", fn = function() PzoptMainMenu.instance():select("tools/overlay/1") end },
            { name = "ig-pzmenu-search", fn = function()
                local m = PzoptMainMenu.instance()
                m.search:setText("shadow")
                m:runSearch("shadow")
            end },
            { name = "ig-pzmenu-clips", fn = function()
                local m = PzoptMainMenu.instance()
                m.search:setText("")
                m:runSearch("")
                if PzoptSettings then PzoptSettings.setClipsOn(true) end
                m:openKey("fogPass")
            end },
            { name = "ig-pzmenu-perf-drive-1", fn = function()
                if PzoptSettings then PzoptSettings.setClipsOn(false) end
                PzoptMainMenu.instance():select("perf/drive/1")
            end },
            { name = "ig-pzmenu-restart-prompt", fn = function() PzoptMainMenu.instance():devStep("vehicleSmooth", 1) end },
            { name = "ig-pzmenu-restart-later", fn = function() PzoptMainMenu.instance():hideRestartPrompt() end },
            { name = "ig-pzmenu-bench-setup", fn = function()
                local m = PzoptMainMenu.instance()
                m.forceNoPrompt = true
                m:devStep("vehicleSmooth", -1)
                m.forceNoPrompt = nil
                m:select("bench/setup")
            end },
            { name = "ig-pzmenu-help", fn = function() PzoptMainMenu.instance():setHelp(true) end },
            { name = "ig-pause-again", fn = function()
                local m = PzoptMainMenu.instance()
                m:setHelp(false)
                m:close()
                ms.bottomPanel.pzoptReadRoot = true
            end },
            { name = "ig-resumed", fn = function()
                ms.bottomPanel.pzoptReadRoot = nil
                ToggleEscapeMenu(escapeKey()) -- the game runs again (quit_after then quits to the desktop, not to the main menu)
                print("[pzopt-harness] pzmenu: resumed, pause menu open=" .. tostring(ms:isVisible()))
            end },
        }
        PzoptBenchRead.begin(stops)
        pzmenu = false
        log("readability tour over the pause menu started (" .. #stops .. " stops)")
        return
    end
    if st == 0 then
        ToggleEscapeMenu(escapeKey())
        log("pause menu open=" .. tostring(ms and ms:isVisible()) .. " item=" .. tostring(ms and ms.pzoptMenuOption and ms.pzoptMenuOption.name))
        shot(1)
    elseif st == 1 then
        ms.pzoptMenuOption.onMouseDown(ms.pzoptMenuOption, 0, 0)
        local m = PzoptMainMenu.instance()
        log("screen open=" .. tostring(m ~= nil) .. " page=" .. tostring(m and m.selected) .. " in game=" .. tostring(ms.inGame))
        shot(2)
    elseif st == 2 then
        local m = PzoptMainMenu.instance()
        m:select("tools/overlay/1")
        local p = getPerformance()
        log("overlay before: visible=" .. tostring(p:isPzoptOverlayVisible()) .. " sampling=" .. tostring(p:isPzoptOverlaySampling()))
        m:activateItem(1) -- the toggle row (first on the page): shows the overlay (and switches the measuring on if it was off)
        log("overlay after the toggle: visible=" .. tostring(p:isPzoptOverlayVisible()) .. " sampling=" .. tostring(p:isPzoptOverlaySampling()))
        pzmenu.keyBefore = p:getPzoptOption(PZMENU_KEY)
        m:devStep(PZMENU_KEY, 1)
        log("live key " .. PZMENU_KEY .. ": " .. tostring(pzmenu.keyBefore) .. " -> " .. p:getPzoptOption(PZMENU_KEY) .. " (in force now)")
    elseif st == 3 then
        shot(3) -- the overlay over the menu, a few seconds into its measuring
        log("overlay showing: visible=" .. tostring(getPerformance():isPzoptOverlayVisible()))
    elseif st == 4 then
        local m = PzoptMainMenu.instance()
        m:devStep(PZMENU_KEY, -1)
        m:close()
        log("screen closed: screen=" .. tostring(PzoptMainMenu.instance() ~= nil) .. " pause menu open=" .. tostring(ms:isVisible())
            .. " " .. PZMENU_KEY .. "=" .. getPerformance():getPzoptOption(PZMENU_KEY))
        shot(4)
    elseif st == 5 then
        ToggleEscapeMenu(escapeKey())
        log("resumed: pause menu open=" .. tostring(ms:isVisible()))
    elseif st == 6 then
        getPerformance():togglePzoptOverlay()
        log("done (overlay visible=" .. tostring(getPerformance():isPzoptOverlayVisible()) .. ")")
        pzmenu = false
    end
end

-- pzmenu_masters=S (2026-10-18, the category master switches): S seconds into the world, the PZ Optimization screen over
-- the pause menu; Enhancements > Lighting and shadows: god rays on (live), then its category switch off and on again (its
-- row, A): the setting in force follows at once; Optimizations > Driving: its switch off asks for the restart (the
-- prompt), LATER, back on: nothing waits. Each step is a "[pzopt-harness] masters:" line and a screenshot
-- (Screenshots/pzopt-masters-<n>.png). Runs on the run's own options file (--vmarg -Dpzopt.userOptionsFile=<scratch>).
local masters = nil
local function mastersTick()
    if masters == false then return end
    if not getPlayer() then return end
    if masters == nil then
        local flags = readFlags()
        if not flags or not flags.pzmenu_masters or flags.pzmenu_masters == "" then masters = false; return end
        masters = { at = getTimestampMs() + (tonumber(flags.pzmenu_masters) or 10) * 1000, step = 0 }
    end
    local now = getTimestampMs()
    if now < masters.at then return end
    local ms = MainScreen.instance
    local p = getPerformance()
    local function log(t) print("[pzopt-harness] masters: " .. t) end
    local function shot(n) getCore():TakeFullScreenshot("pzopt-masters-" .. n .. ".png") end
    local function row1(m) m.list:setSelected(1) return m.list.items[1] end
    local st = masters.step
    masters.step = st + 1
    masters.at = now + 2000
    if st == 0 then
        ToggleEscapeMenu(escapeKey())
        ms.pzoptMenuOption.onMouseDown(ms.pzoptMenuOption, 0, 0)
        PzoptSettings.set("godRays", "true")
        log("godRays saved true: in force " .. p:getPzoptOption("godRays") .. ", catLightEnabled " .. p:getPzoptOption("catLightEnabled"))
    elseif st == 1 then
        local m = PzoptMainMenu.instance()
        m:selectNode("vis/light", "master")
        local it = row1(m)
        m:activateItem(1)
        log("category switch " .. tostring(it and it.master) .. " A: now " .. p:getPzoptOption("catLightEnabled") .. ", godRays in force "
            .. p:getPzoptOption("godRays") .. " (live: false expected), restart prompt " .. tostring(m.prompt == true))
        shot(1)
    elseif st == 2 then
        local m = PzoptMainMenu.instance()
        m:activateItem(1)
        log("category switch A again: catLightEnabled " .. p:getPzoptOption("catLightEnabled") .. ", godRays in force " .. p:getPzoptOption("godRays")
            .. " (true expected)")
        PzoptSettings.set("godRays", "")
    elseif st == 3 then
        local m = PzoptMainMenu.instance()
        m:selectNode("perf/drive", "master")
        local it = row1(m)
        m:activateItem(1)
        log("category switch " .. tostring(it and it.master) .. " A: saved " .. tostring(p:getPzoptOptionSaved("catDriveEnabled"))
            .. ", in force " .. p:getPzoptOption("catDriveEnabled") .. " (next launch), restart prompt " .. tostring(m.prompt == true))
        shot(2)
    elseif st == 4 then
        local m = PzoptMainMenu.instance()
        m:hideRestartPrompt()
        m:activateItem(1)
        log("category switch A again: saved '" .. p:getPzoptOptionSaved("catDriveEnabled") .. "', prompt " .. tostring(m.prompt == true)
            .. ", reminder " .. tostring(m:reminder() ~= nil))
        shot(3)
    elseif st == 5 then
        PzoptMainMenu.instance():close("masters rig")
        ToggleEscapeMenu(escapeKey())
        log("done")
        masters = false
    end
end

-- pzmenu_final=S (2026-10-19, the one in-game pass of round 3): S seconds into the world, the pause menu, its PZ OPTIMIZATION
-- item, a search ("fog": the results), a setting whose impact chart has rows (fogScalePct: GPU, VRAM), a category switch
-- (Enhancements > Lighting and shadows: live, god rays follow at once; Optimizations > Driving: the restart prompt, LATER,
-- back on), the screen closed, the game's own Options from the pause menu (its tabs: no pzopt tab), Back, resume. Every
-- step is a "[pzopt-harness] final:" line and a screenshot (Screenshots/pzopt-final-<n>.png); "final: PASS" or
-- "final: FAIL <what>" at the end. Runs on the run's own options file (--vmarg -Dpzopt.userOptionsFile=<scratch>).
local final = nil
local function finalTick()
    if final == false then return end
    if not getPlayer() then return end
    if final == nil then
        local flags = readFlags()
        if not flags or not flags.pzmenu_final or flags.pzmenu_final == "" then final = false; return end
        final = { at = getTimestampMs() + (tonumber(flags.pzmenu_final) or 10) * 1000, step = 0, fails = {} }
    end
    local now = getTimestampMs()
    if now < final.at then return end
    local ms = MainScreen.instance
    local p = getPerformance()
    local function log(t) print("[pzopt-harness] final: " .. t) end
    local function check(ok, what) if not ok then table.insert(final.fails, what) end log((ok and "ok " or "FAIL ") .. what) end
    local function shot(n) getCore():TakeFullScreenshot("pzopt-final-" .. n .. ".png") end
    local st = final.step
    final.step = st + 1
    final.at = now + 2000
    if st == 0 then
        ToggleEscapeMenu(escapeKey())
        ms.pzoptMenuOption.onMouseDown(ms.pzoptMenuOption, 0, 0)
        check(PzoptMainMenu.instance() ~= nil and PzoptMainMenu.instance():isVisible(), "the screen opened from the pause menu")
        shot(1)
    elseif st == 1 then
        local m = PzoptMainMenu.instance()
        m.search:setText("fog")
        m:runSearch("fog")
        local n = m.page and m.page.count or 0
        local first = m.list.items[1] and tostring(m.list.items[1].id) or "-"
        check(m.selected == "search" and n > 0, "search \"fog\": " .. n .. " results, first " .. first)
        shot(2)
    elseif st == 2 then
        local m = PzoptMainMenu.instance()
        m.search:setText("")
        m:runSearch("")
        m:openKey("fogScalePct")
        local it = m.list.items[m.list.selected or 0]
        local rows = PzoptSettings.effectRows("fogScalePct")
        check(it and it.id == "fogScalePct" and m.impactChart ~= nil and rows > 0,
            "fogScalePct on its page, impact chart with " .. rows .. " rows (" .. tostring(m.impactChart ~= nil) .. ")")
        shot(3)
    elseif st == 3 then
        local m = PzoptMainMenu.instance()
        PzoptSettings.set("godRays", "true")
        m:selectNode("vis/light", "master")
        m.list:setSelected(1)
        m:activateItem(1)
        check(p:getPzoptOption("catLightEnabled") == "false" and p:getPzoptOption("godRays") == "false" and not m.prompt,
            "Lighting and shadows switch off: live (god rays " .. p:getPzoptOption("godRays") .. "), no restart prompt")
        m:activateItem(1)
        check(p:getPzoptOption("catLightEnabled") == "true" and p:getPzoptOption("godRays") == "true", "Lighting and shadows switch on again: god rays " .. p:getPzoptOption("godRays"))
        PzoptSettings.set("godRays", "")
        m:selectNode("perf/drive", "master")
        m.list:setSelected(1)
        m:activateItem(1)
        check(p:getPzoptOptionSaved("catDriveEnabled") == "false" and m.prompt == true, "Driving switch off: saved for the next launch, restart prompt shown")
        shot(4)
    elseif st == 4 then
        local m = PzoptMainMenu.instance()
        m:hideRestartPrompt()
        m:activateItem(1)
        check(p:getPzoptOptionSaved("catDriveEnabled") == "" and not m.prompt and m:reminder() == nil, "Driving switch on again: nothing waits")
        m:close("final rig")
        check(PzoptMainMenu.instance() == nil and ms:isVisible(), "closed onto the pause menu")
    elseif st == 5 then
        MainScreen.onMenuItemMouseDownMainMenu(ms.optionsOption, 0, 0)
    elseif st == 6 then
        local mo = ms.mainOptions
        local names, has = {}, false
        for _, v in ipairs(mo and mo.tabs and mo.tabs.viewList or {}) do
            table.insert(names, tostring(v.name))
            if v.name == "PZ Optimization" or v.name == "Optimizations" or v.name == "Enhancements" or v.name == "Profiler" then has = true end
        end
        check(#names > 0 and not has, "the game's Options: tabs " .. table.concat(names, " | ") .. " (no pzopt tab)")
        shot(5)
        if mo then mo:onOptionMouseDown(mo.backButton, 0, 0) end
    elseif st == 7 then
        if ms:isVisible() then ToggleEscapeMenu(escapeKey()) end
        log(#final.fails == 0 and "PASS" or ("FAIL " .. table.concat(final.fails, "; ")))
        final = false
    end
end

-- options_screen=1 (2026-10-18): at the main menu, the game's own Options screen opened from its item; logs its tab names
-- ("[pzopt-harness] options screen: tabs ...", PZ Optimization must not be one) and writes Screenshots/pzopt-options-screen.png,
-- then closes it and quits; the run's save is not continued, and the rig quits after 90 s whatever happened.
local optionsScreen = nil
local function optionsScreenTick()
    if optionsScreen == false then return end
    local ms = MainScreen.instance
    if optionsScreen == nil then
        local flags = readFlags()
        if not flags or flags.options_screen ~= "1" then optionsScreen = false; return end
        optionsScreen = { at = getTimestampMs() + 6000, step = 0, deadline = getTimestampMs() + 90000 }
        -- stays on the main menu: the run's save is not continued
        pending = nil
        appendFlag("consumed=1")
    end
    if getTimestampMs() > optionsScreen.deadline then -- never got to act (no main menu): the run must not hold the machine
        print("[pzopt-harness] options screen: gave up after 90 s (no main menu); quitting")
        optionsScreen = false
        getCore():quit()
        return
    end
    if not ms or ms.inGame or (ms.delay and ms.delay > 0) or getTimestampMs() < optionsScreen.at then return end
    local st = optionsScreen.step
    optionsScreen.step = st + 1
    optionsScreen.at = getTimestampMs() + 2500
    if st == 0 then
        MainScreen.onMenuItemMouseDownMainMenu(ms.optionsOption, 0, 0)
    elseif st == 1 then
        local mo = ms.mainOptions
        local names = {}
        for _, v in ipairs(mo and mo.tabs and mo.tabs.viewList or {}) do table.insert(names, tostring(v.name)) end
        local has = false
        for _, n in ipairs(names) do if n == "PZ Optimization" or n == "Optimizations" or n == "Enhancements" or n == "Profiler" then has = true end end
        print("[pzopt-harness] options screen: tabs " .. table.concat(names, " | ") .. "; a pzopt tab: " .. tostring(has))
        getCore():TakeFullScreenshot("pzopt-options-screen.png")
    elseif st == 2 then
        local mo = ms.mainOptions
        if mo then mo:setVisible(false) end
        if ms.bottomPanel then ms.bottomPanel:setVisible(true) end
        print("[pzopt-harness] options screen: closed; quitting")
        optionsScreen = false
        getCore():quit()
    end
end
Events.OnFETick.Add(function()
    local ok, err = pcall(optionsScreenTick)
    if not ok then print("[pzopt-harness] options screen: rig error " .. tostring(err)); optionsScreen = false end
end)

-- ui_cycle=S (2026-10-19, Workshop "Stack overflow in pzopt.UiDeps.validate"): S seconds into the world, two panels of the
-- same size swap parent and child every ui_cycle_swap_ms (1000) ms, the child at the parent's corner, so it stays where it
-- was on screen (a mod wrapping a window in a container, a docking window). Their renders are above the retained UI's
-- cheap limits and read their own table, so each is replayed and validated: the child replayed inside the new parent
-- while its own read set still holds the old child (a cycle of read sets). Drawn for ui_cycle_secs (20);
-- "[pzopt-harness] ui_cycle: alive <n> frames" every 5 s, "ui_cycle: done" at the end. (A top-level element with a parent
-- is no case: UIManager.update removes it with an IllegalStateException.)
PzoptHarnessUiCycle = nil
function PzoptHarnessUiCycleTick()
    local u = PzoptHarnessUiCycle
    if u == false then return end
    if not getPlayer() then return end
    if u == nil then
        local flags = readFlags()
        if not flags or not flags.ui_cycle or flags.ui_cycle == "" then PzoptHarnessUiCycle = false return end
        PzoptHarnessUiCycle = { at = getTimestampMs() + (tonumber(flags.ui_cycle) or 5) * 1000,
            secs = tonumber(flags.ui_cycle_secs) or 20, swapMs = tonumber(flags.ui_cycle_swap_ms) or 1000, frames = 0 }
        return
    end
    local now = getTimestampMs()
    if not u.built then
        if now < u.at then return end
        -- a render above the retained UI's cheap limit (uiRetainedCheapUs), reading its own table: replayed and validated
        local function heavy(self)
            ISPanel.render(self)
            local c = self.rigColor
            for i = 0, 399 do
                self:drawRect(4 + (i % 20) * 6, 4 + math.floor(i / 20) * 4, 4, 2, c.a, c.r, c.g, c.b)
            end
        end
        local a = ISPanel:new(700, 200, 300, 200)
        a:initialise()
        a:addToUIManager()
        local b = ISPanel:new(0, 0, 300, 200)
        b:initialise()
        a:addChild(b)
        for _, p in ipairs({ a, b }) do
            p.rigColor = { r = 0.9, g = 0.9, b = 0.2, a = 0.6 }
            p.render = heavy
        end
        u.a, u.b = a, b
        u.built, u.until_, u.next, u.swap = now, now + u.secs * 1000, now + 5000, now + u.swapMs
        print("[pzopt-harness] ui_cycle: built (two same-size panels swapping parent and child every " .. u.swapMs .. " ms)")
        return
    end
    u.frames = u.frames + 1
    if now >= u.swap then
        u.swap = now + u.swapMs
        local top, child = u.a, u.b
        if child:getParent() == nil then top, child = u.b, u.a end
        local x, y = top:getX(), top:getY()
        top:removeChild(child)
        top:removeFromUIManager()
        child:setX(x) child:setY(y)
        child:addToUIManager()
        top:setX(0) top:setY(0)
        child:addChild(top)
        u.swaps = (u.swaps or 0) + 1
    end
    if now >= u.next then
        u.next = now + 5000
        print("[pzopt-harness] ui_cycle: alive " .. u.frames .. " frames, " .. tostring(u.swaps or 0) .. " swaps")
    end
    if now >= u.until_ then
        local top = u.a:getParent() == nil and u.a or u.b
        top:removeFromUIManager()
        print("[pzopt-harness] ui_cycle: done, " .. u.frames .. " frames")
        PzoptHarnessUiCycle = false
    end
end

local function onTickEvenPaused()
    local okc, errc = pcall(PzoptHarnessUiCycleTick)
    if not okc then print("[pzopt-harness] ui_cycle: rig error " .. tostring(errc)); PzoptHarnessUiCycle = false end
    local okf, errf = pcall(finalTick)
    if not okf then print("[pzopt-harness] final: FAIL rig error " .. tostring(errf)); final = false end
    local okm, errm = pcall(mastersTick)
    if not okm then print("[pzopt-harness] masters: rig error " .. tostring(errm)); masters = false end
    local okz, errz = pcall(pzmenuTick)
    if not okz then print("[pzopt-harness] pzmenu: rig error " .. tostring(errz)); pzmenu = false end
    local ok5, err5 = pcall(worldMapTick)
    if not ok5 then print("[pzopt-map] rig error " .. tostring(err5)); worldMap = false end
    local ok4, err4 = pcall(inputLagTick)
    if not ok4 then print("[pzopt-inputlag] rig error " .. tostring(err4)); inputLag = false end
    local ok6, err6 = pcall(stockDriveTick)
    if not ok6 then print("[pzopt-stockdrive] rig error " .. tostring(err6)); stockDrive = false; getCore():quit() end
    local ok3, err3 = pcall(pauseMenuTick)
    if not ok3 then print("[pzopt-harness] pause menu: rig error " .. tostring(err3)); pauseMenu = false end
    local okg, errg = pcall(glassTick)
    if not okg then print("[pzopt-harness] glass: rig error " .. tostring(errg)); glass = false end
    local okq, errq = pcall(squareHudTick)
    if not okq then print("[pzopt-harness] squarehud: rig error " .. tostring(errq)); squareHud = false end
    local ok, err = pcall(lureTick)
    if not ok then print("[pzopt-harness] lure: rig error " .. tostring(err)); lure = false end
    local ok2, err2 = pcall(optionsCheckTick)
    if not ok2 then print("[pzopt-harness] options check: rig error " .. tostring(err2)); ingameOptions = false end
    if quitAtMs and getTimestampMs() >= quitAtMs then
        quitAtMs = nil
        print("[pzopt-harness] quit_after reached, quitting")
        -- from the pause menu getCore():quit() goes to the main menu instead of the desktop (a run waited for an exit that
        -- never came, 2026-10-18): the PZ Optimization screen and the pause menu close first, then the game quits
        pcall(function()
            local m = PzoptMainMenu and PzoptMainMenu.instance and PzoptMainMenu.instance()
            if m then m:close("quit_after") end
            local ms = MainScreen.instance
            if ms and ms.inGame and ms:isVisible() then ToggleEscapeMenu(escapeKey()) end
        end)
        getCore():quit()
    end
end

-- lua_wrap=<table>[,<table>] (2026-09-23): wrap every function of those global tables with a millisecond timer and
-- log each call over 2 ms (nested calls are logged too, innermost first). The Lua event profile (luaEventProfile) names
-- the slow handler; this splits a handler's own plain Lua calls. Installed at boot, so load-time calls are covered.
local function installLuaWrap()
    local flags = readFlags()
    if not flags or not flags.lua_wrap or flags.lua_wrap == "" then return end
    for name in string.gmatch(flags.lua_wrap, "[^,]+") do
        local t = _G[name]
        if name == "ISUIElement" or name == "ISPanel" or name == "ISBaseObject" or name == "ISPanelJoypad" then
            -- every screen inherits these: wrapping them broke the main menu build (2026-09-23, flip-menuwrap2)
            print("[pzopt-harness] lua_wrap: refusing base UI class " .. name)
        elseif type(t) ~= "table" then
            print("[pzopt-harness] lua_wrap: no global table " .. name)
        else
            local n = 0
            for k, v in pairs(t) do
                if type(v) == "function" then
                    local fname = name .. "." .. tostring(k)
                    t[k] = function(...)
                        local t0 = getTimestampMs()
                        local r = { v(...) }
                        local ms = getTimestampMs() - t0
                        if ms >= 2 then print("[pzopt-harness] lua_wrap: " .. fname .. " " .. ms .. " ms") end
                        return unpack(r)
                    end
                    n = n + 1
                end
            end
            print("[pzopt-harness] lua_wrap: " .. n .. " functions of " .. name .. " wrapped")
        end
    end
end
Events.OnGameBoot.Add(installLuaWrap)

-- lua_prof=<table>[,<table>] (2026-09-23, menu profiling): the per-frame methods (prerender, render, update, draw*,
-- onJoypad*, pick, select, layout*) of those global tables timed and summed; every 2 s one console line per busy
-- method: "[pzopt-luaprof] t=<epoch ms> frames=<n> <Table.method> <ms per frame> <calls per frame>". Millisecond
-- stamps, but summed over thousands of calls the rounding averages out. Inclusive: a method that calls another
-- wrapped one counts both. Instance-level functions (o.prerender = ...) are not seen.
local luaProf = nil
local PROF_NAMES = { prerender = true, render = true, update = true, pick = true, select = true }
local function profName(fname)
    return PROF_NAMES[fname] or fname:match("^draw") or fname:match("^onJoypad") or fname:match("^layout")
        or fname:match("^relayout")
end
local function installLuaProf()
    local flags = readFlags()
    if not flags or not flags.lua_prof or flags.lua_prof == "" then return end
    luaProf = { acc = {}, frames = 0, next = getTimestampMs() + 2000 }
    local n = 0
    for name in string.gmatch(flags.lua_prof, "[^,]+") do
        local t = _G[name]
        if type(t) == "table" then
            local fns = {}
            for fname, fn in pairs(t) do
                if type(fn) == "function" and type(fname) == "string" and profName(fname) then fns[fname] = fn end
            end
            for fname, fn in pairs(fns) do
                local key = name .. "." .. fname
                local a = { 0, 0 }
                luaProf.acc[key] = a
                t[fname] = function(...)
                    local t0 = getTimestampMs()
                    local r1, r2, r3 = fn(...)
                    a[1] = a[1] + (getTimestampMs() - t0)
                    a[2] = a[2] + 1
                    return r1, r2, r3
                end
                n = n + 1
            end
        else
            print("[pzopt-luaprof] no global table " .. name)
        end
    end
    print("[pzopt-luaprof] " .. n .. " methods wrapped")
end
local function luaProfTick()
    if not luaProf then return end
    luaProf.frames = luaProf.frames + 1
    local now = getTimestampMs()
    if now < luaProf.next then return end
    luaProf.next = now + 2000
    local f = luaProf.frames
    luaProf.frames = 0
    local rows = {}
    for k, a in pairs(luaProf.acc) do
        if a[1] > 0 or a[2] > 0 then table.insert(rows, { k, a[1], a[2] }) end
        a[1] = 0; a[2] = 0
    end
    table.sort(rows, function(x, y) return x[2] > y[2] end)
    for i = 1, math.min(#rows, 25) do
        local r = rows[i]
        print(string.format("[pzopt-luaprof] t=%d frames=%d %s %.3f %.1f", now, f, r[1], r[2] / math.max(f, 1), r[3] / math.max(f, 1)))
    end
end
Events.OnGameBoot.Add(installLuaProf)
Events.OnFETick.Add(luaProfTick)

Events.OnMainMenuEnter.Add(onMainMenuEnter)
Events.OnFETick.Add(onFETick)
Events.OnTickEvenPaused.Add(onTickEvenPaused)
Events.OnTickEvenPaused.Add(function() local ok, err = pcall(ingameOptionsTick); if not ok then print("[pzopt-harness] ingame options: " .. tostring(err)); ingameOptions = false end end)
