-- pzopt: the built-in benchmark (docs/plan-builtin-benchmark.md, 2026-10-18).
--  Its pages (setup, past results, run comparison, report) are pages of the main menu's PZ Optimization screen
--  (pzopt_mainscreen_menu.lua); this file gives them their data and actions (PzoptBench). Start: pzopt.Benchmark writes the
--  plan and restarts the game. Every pass runs in a process of its own: at the main menu this file asks Java what the
--  process is for ("menu"): a pass -> a fresh sandbox world at the scenario's spot (the game's own debug-scenario spawn,
--  selectedDebugScenario.startLoc, read by IsoWorld.init), the route itself is pzopt.Harness; the pass over -> quit (the next
--  process is already armed); the run over -> its report page. A small HUD names the pass while it runs.
--  Java side: PerformanceSettings.pzoptBenchmark(cmd, arg) -> pzopt.Benchmark.call.
-- Installed by scripts/pzopt.sh into <game dir>/media/lua/client/pzopt/ (loose game-dir Lua, no mod to enable).

local FONT_SMALL = getTextManager():getFontHeight(UIFont.Small)
local FONT_MEDIUM = getTextManager():getFontHeight(UIFont.Medium)
local FONT_LARGE = getTextManager():getFontHeight(UIFont.Large)
local PAD = 12
local BTN_HGT = math.max(25, FONT_SMALL + 6)

local function perf()
    return getPerformance()
end

local function call(cmd, arg)
    local ok, r = pcall(function() return perf():pzoptBenchmark(cmd, arg or "") end)
    if ok and r ~= nil then return r end
    return "error\t" .. tostring(r)
end

local function split(s, sep)
    local t = {}
    if s == nil then return t end
    local start = 1
    while true do
        local i = string.find(s, sep, start, true)
        if not i then
            table.insert(t, string.sub(s, start))
            break
        end
        table.insert(t, string.sub(s, start, i - 1))
        start = i + 1
    end
    return t
end

local function rows(text)
    local out = {}
    for _, line in ipairs(split(text or "", "\n")) do
        if line ~= "" then table.insert(out, split(line, "\t")) end
    end
    return out
end

-- a side held at the frame cap or the refresh makes the speed-up a bound: A held = at least, B held = at most
-- ------------------------------------------------------------------ text that fits (every screen down to 1280 x 720)

local function textW(font, s)
    return getTextManager():MeasureStringX(font, tostring(s or ""))
end

-- the text cut with "..." to maxW (only for free-form values: names, labels, driver strings)
local function fitText(s, font, maxW)
    s = tostring(s or "")
    if textW(font, s) <= maxW then return s end
    while #s > 1 and textW(font, s .. "...") > maxW do s = string.sub(s, 1, #s - 1) end
    return s .. "..."
end

-- a word wider than maxW in pieces that fit: broken after ; , : / - where it can, else between any two characters
-- (the zoom level lists, a kernel version string)
local function breakWord(w, font, maxW)
    local pieces = {}
    while textW(font, w) > maxW and #w > 1 do
        local cut = 1
        for i = 1, #w - 1 do
            if textW(font, string.sub(w, 1, i)) > maxW then break end
            cut = i
        end
        local soft = nil
        for i = cut, math.max(1, math.floor(cut / 2)), -1 do
            if string.find(";,:/-", string.sub(w, i, i), 1, true) then soft = i break end
        end
        cut = soft or cut
        table.insert(pieces, string.sub(w, 1, cut))
        w = string.sub(w, cut + 1)
    end
    table.insert(pieces, w)
    return pieces
end

-- the lines of a text broken at spaces to maxW (a word wider than maxW broken into pieces that fit)
local function wrapText(s, font, maxW)
    local words = {}
    for _, w in ipairs(split(tostring(s or ""), " ")) do
        for _, p in ipairs(breakWord(w, font, maxW)) do table.insert(words, p) end
    end
    local lines, line = {}, ""
    for _, w in ipairs(words) do
        local test = line == "" and w or (line .. " " .. w)
        if line ~= "" and textW(font, test) > maxW then
            table.insert(lines, line)
            line = w
        else
            line = test
        end
    end
    if line ~= "" then table.insert(lines, line) end
    for i, l in ipairs(lines) do lines[i] = fitText(l, font, maxW) end
    return lines
end

-- parts joined by sep, a new line whenever the next part would pass maxW (the report's "CPU | GPU | ..." lines)
local function wrapParts(parts, sep, font, maxW)
    local lines, line = {}, ""
    for _, p in ipairs(parts) do
        if p ~= "" and textW(font, p) > maxW then
            -- a part wider than the line (a GPU and driver string in a narrow page): its own lines, broken at spaces
            if line ~= "" then table.insert(lines, line) end
            local pl = wrapText(p, font, maxW)
            for i = 1, #pl - 1 do table.insert(lines, pl[i]) end
            line = pl[#pl] or ""
        elseif p ~= "" then
            local test = line == "" and p or (line .. sep .. p)
            if line ~= "" and textW(font, test) > maxW then
                table.insert(lines, line)
                line = p
            else
                line = test
            end
        end
    end
    if line ~= "" then table.insert(lines, line) end
    for i, l in ipairs(lines) do lines[i] = fitText(l, font, maxW) end
    return lines
end

local function abText(ab)
    local held = ab[8] or "-"
    if held == "AB" then return "capped" end
    if held == "A" then return ">=" .. ab[3] .. "x" end
    if held == "B" then return "<=" .. ab[3] .. "x" end
    return ab[3] .. "x"
end

local function num(s)
    local v = tonumber(s)
    return v
end

local function nums(csv)
    local out = {}
    for _, v in ipairs(split(csv or "", ",")) do
        local n = tonumber(v)
        if n then table.insert(out, n) end
    end
    return out
end

-- ------------------------------------------------------------------ the pass's world

local SANDBOX_POP_MAX = 4.0

-- A fresh sandbox world at the scenario's spot. The stock "Apocalypse" quick start (MainScreen, item APOCALYPSE) plus
-- the spawn of the debug scenarios and pinned sandbox settings: no random helicopter or meta events (gunshots, alarms)
-- during a pass, the population of the scenario.
local function startWorld(c)
    local x, y, z = tonumber(c[3]), tonumber(c[4]), tonumber(c[5])
    local population, world = c[6], c[14] or "pzopt-benchmark"
    local ms = MainScreen.instance
    print(string.format("[pzopt] benchmark: pass %s/%s %s (%s, pass %s): new world at %d,%d,%d", c[7], c[8], c[9], c[10], c[11], x, y, z))
    ms:setDefaultSandboxVars()
    SandboxVars.Helicopter = 1
    SandboxVars.MetaEvent = 1
    SandboxVars.StarterKit = false
    SandboxVars.TimeSinceApo = 1
    if population == "max" then
        SandboxVars.ZombieConfig = SandboxVars.ZombieConfig or {}
        SandboxVars.ZombieConfig.PopulationMultiplier = SANDBOX_POP_MAX
        SandboxVars.ZombieConfig.PopulationStartMultiplier = SANDBOX_POP_MAX
        SandboxVars.ZombieConfig.PopulationPeakMultiplier = SANDBOX_POP_MAX
    end
    pcall(function() getSandboxOptions():updateFromLua() end)
    pcall(function() WorldGenParams.INSTANCE:setSeedString("pzopt-benchmark") end)
    local okDesc = pcall(function() ms.desc = SurvivorFactory.CreateSurvivor(SurvivorType.Neutral, false) end)
    if not okDesc or not ms.desc then ms.desc = SurvivorFactory.CreateSurvivor() end
    selectedDebugScenario = { name = "pzopt benchmark", startLoc = { x = x, y = y, z = z } } -- IsoWorld.init spawns there
    getWorld():setGameMode("Sandbox")
    getWorld():setMap("DEFAULT")
    getWorld():setWorld(world)
    deleteSave("Sandbox/" .. world)
    createWorld(world)
    GameWindow.doRenderEvent(false)
    forceChangeState(LoadingQueueState.new())
end

-- ------------------------------------------------------------------ main menu: what this process is for

local menu = {}

local function quitGame(why)
    print("[pzopt] benchmark: " .. why .. "; quitting (the next pass starts by itself)")
    getCore():quit()
end

-- Stopping a run (2026-10-19, Workshop "endless benchmark bootloop" / "how to stop benchmark in game"): every pass shows its
-- countdown over the main menu for at least STOP_GRACE_S seconds, and Escape, a mouse click or the controller's B during it
-- aborts the whole plan (Benchmark "cancel": the plan, the pass worlds and the player's files), then this process ends and
-- the game restarts as a normal game (the process is a pass: Harness flags and pins from the plan). Unattended runs
-- (benchmarkAuto, harness/benchmark-run.sh) are not stopped by input.
local STOP_GRACE_S = 4
local function wantsStop()
    local ok, stop = pcall(function()
        if isKeyDown(Keyboard.KEY_ESCAPE) then return true end
        if isMouseButtonDown(0) then return true end
        local jd = JoypadState.getMainMenuJoypad()
        if jd and jd.id and jd.id >= 0 and jd:isBPressed() then return true end -- JoypadData.isBPressed: the pad's B now
        return false
    end)
    return ok and stop == true
end

-- the readability rig's state and tour step (devBenchmarkUi=read), defined with the rig further down; menuTick runs it
local read = { mode = nil, stop = 0, logging = false, name = "", nextLog = {} }
local readTick

-- The restart rig (devBenchmarkUi=restart, harness/benchmark-run.sh ui:restart): the first process changes a next-launch
-- setting on the PZ Optimization screen, shows the restart prompt and restarts through it (the screen's own restartNow,
-- pzopt.Restart); the restarted process checks the setting is in force, puts it back to its default and ends the tour.
local RESTART_MARK = "pzopt-menu-restart.txt"
local RESTART_KEY = "vehicleSmooth"

local function restartMark()
    local r = getFileReader(RESTART_MARK, false)
    if not r then return nil end
    local line = r:readLine()
    r:close()
    if not line or line == "" then return nil end
    return line
end

function PzoptMenuRestartRigWrite(pend)
    local w = getFileWriter(RESTART_MARK, true, false)
    for _, p in ipairs(pend) do
        if p.key == RESTART_KEY then w:write(p.key .. "=" .. tostring(p.want)) end
    end
    w:close()
end

local function restartTick(self, now)
    local R = menu.devRestart
    if R.step == 0 then
        local mark = restartMark()
        if mark then -- the restarted process
            local key, want = string.match(mark, "^([^=]+)=(.*)$")
            local got = perf():getPzoptOption(key)
            print(string.format("[pzopt] menu: restart check: %s now=%s want=%s ok=%s", key, got, want, tostring(got == want)))
            perf():setPzoptOption(key, "") -- back to the default
            local w = getFileWriter(RESTART_MARK, true, false)
            w:write("")
            w:close()
            print("[pzopt] menu: restart check: " .. key .. " saved back to its default (the next launch's value; in force until then: " .. perf():getPzoptOption(key) .. ")")
            print("[pzopt] benchmark: restart tour done")
            R.step = 9
            return
        end
        PzoptMenuRestartRig = PzoptMenuRestartRigWrite
        self.pzoptMenuOption.onMouseDown(self.pzoptMenuOption, 0, 0)
        local m = PzoptMainMenu.instance()
        m:select("perf/drive/1")
        m:devStep(RESTART_KEY, 1)
        R.step, R.at = 1, now + 2500
        call("shot", "restart-prompt")
    elseif R.step == 1 and now >= R.at then
        R.step = 2
        print("[pzopt] menu: restart rig: restarting through the prompt")
        PzoptMainMenu.instance():restartNow()
    end
end

local function menuTick(self)
    if self.inGame or (self.delay or 0) > 0 then return end
    local now = getTimestampMs()
    if not menu.asked then
        menu.asked = true
        local c = split(call("menu"), "\t")
        if c[1] == "world" then
            menu.world = c
            local cool = tonumber(c[12]) or 0
            menu.auto = call("isAuto") == "1"
            menu.startAt = now + math.max(cool, menu.auto and 0 or STOP_GRACE_S) * 1000
            if cool > 0 then print("[pzopt] benchmark: cooling down " .. c[12] .. " s before the pass") end
        elseif c[1] == "quit" then
            quitGame("pass done")
        elseif c[1] == "report" then
            menu.report = c[2]
        elseif c[1] == "error" then
            print("[pzopt] benchmark: " .. tostring(c[2]))
        else
            local a = call("auto")
            if a == "started" then quitGame("unattended benchmark planned"); return end -- no dev screen on the way out
            local dev = call("devUi")
            if dev == "panel" then menu.devPanel = true
            elseif dev == "history" then menu.devHistory = true
            elseif dev == "compare" then menu.devCompare = true
            elseif dev == "runs" then menu.devRuns = true
            elseif dev == "read" then menu.devRead = true
            elseif dev == "restart" then menu.devRestart = { step = 0 }
            elseif dev == "pad" then read.padOn = true end
        end
    end
    if menu.stopped then
        if now >= menu.stopped.at then
            print("[pzopt] benchmark: stopped by the player; quitting" .. (menu.stopped.restart and " (the game restarts)" or ""))
            menu.stopped.at = math.huge
            getCore():quit()
        end
        return
    end
    if menu.world and not menu.auto and wantsStop() then
        local r = call("cancel")
        print("[pzopt] benchmark: stopped by the player during the countdown (" .. tostring(r) .. ")")
        menu.world = nil
        menu.stopped = { at = now + 1500, restart = r == "restart" }
        return
    end
    if menu.world and now >= menu.startAt then
        -- the idle gate: other programs must leave the CPU alone first (pzopt.BenchIdle, at most 90 s)
        local g = split(call("idle"), "\t")
        menu.idle = g
        if g[1] == "wait" then return end
        local c = menu.world
        menu.world = nil
        local ok, err = pcall(startWorld, c)
        if not ok then
            print("[pzopt] benchmark: could not create the pass's world: " .. tostring(err))
            call("abort", "world creation failed: " .. tostring(err))
        end
    end
    if menu.devPanel and self.bottomPanel and self.bottomPanel:isVisible() then
        menu.devPanel = nil
        PzoptBenchmarkPanel.show()
        print("[pzopt] benchmark: dev: setup panel shown")
        menu.uiShot = { at = now + 2000, name = "panel" }
    end
    if menu.devHistory and self.bottomPanel and self.bottomPanel:isVisible() then
        menu.devHistory = nil
        PzoptBenchmarkHistory.show()
        print("[pzopt] benchmark: dev: past results shown")
        menu.uiShot = { at = now + 2000, name = "history" }
    end
    if menu.devRestart and self.bottomPanel and (self.bottomPanel:isVisible() or menu.devRestart.step > 0) then
        pcall(restartTick, self, now)
    end
    if menu.devRead and self.bottomPanel and (self.bottomPanel:isVisible() or read.stops) then
        readTick(now)
    end
    if menu.devRuns and self.bottomPanel and self.bottomPanel:isVisible() then
        menu.devRuns = nil -- the two newest past results side by side (Past results, Compare)
        local items = PzoptBench.history()
        if #items >= 2 then
            PzoptBenchmarkRunCompare.show(items[2][1], items[1][1])
            menu.uiShot = { at = now + 2000, name = "runs" }
        else
            print("[pzopt] benchmark: dev: no two runs")
        end
    end
    if menu.devCompare and self.bottomPanel and self.bottomPanel:isVisible() then
        menu.devCompare = nil
        local c = split(call("devCompare"), "\t")
        if c[2] then
            local r = PzoptBenchmarkReport.show(c[1])
            if r then r:applyShareCode(c[2]) end
            print("[pzopt] benchmark: dev: report compare shown (" .. c[1] .. ")")
            menu.uiShot = { at = now + 2000, name = "compare" }
        else
            print("[pzopt] benchmark: dev: no two runs to compare")
        end
    end
    if menu.report and self.bottomPanel and self.bottomPanel:isVisible() then
        local run = menu.report
        menu.report = nil
        call("reportShown")
        PzoptBenchmarkReport.show(run)
        print("[pzopt] benchmark: report of run " .. tostring(run) .. " shown")
    end
    -- the screen checks (devBenchmarkUi): the game's own screenshot once the dialog has drawn, for machines whose
    -- desktop capture cannot see the game window (the flip)
    if menu.uiShot and now >= menu.uiShot.at then
        local name = menu.uiShot.name
        menu.uiShot = nil
        call("shot", name)
    end
end

-- ------------------------------------------------------------------ screen readability rig (devBenchmarkUi=read)
-- A tour of every benchmark screen (the main menu item, the setup dialog, past results, the run comparison both ways and
-- page by page, the report per scenario row and with a share code applied). At each stop one frame's texts are logged
-- with their element's and dialog's rectangles (BENCHREAD lines) and the game's own screenshot is taken; the HUD and the
-- cool-down notice log theirs every few seconds during a run with the same setting. harness/benchmark-readability.py
-- checks them (text past its element, its dialog or the screen; texts on top of each other; "..." cuts; OCR read-back)
-- and asks Jev.

local function readOn()
    if read.mode == nil then read.mode = call("devUi") == "read" end
    return read.mode
end

local function readLine(screen, kind, font, ax, ay, w, h, el, clip, root, text)
    -- in a game the frame logs for a while (the UI is not drawn every frame there): each text once per stop
    local key = read.stop .. "|" .. math.floor(ax) .. "|" .. math.floor(ay) .. "|" .. tostring(text)
    read.seen = read.seen or {}
    if read.seen[key] then return end
    read.seen[key] = true
    print(string.format("[pzopt] BENCHREAD stop=%d screen=%s kind=%s font=%s x=%d y=%d w=%d h=%d el=%d,%d,%d,%d clip=%s root=%d,%d,%d,%d scr=%d,%d text=%s",
        read.stop, screen, kind, tostring(font), math.floor(ax), math.floor(ay), w, h, math.floor(el[1]), math.floor(el[2]), el[3], el[4], clip,
        math.floor(root[1]), math.floor(root[2]), root[3], root[4], getCore():getScreenWidth(), getCore():getScreenHeight(), tostring(text)))
end

-- a text drawn straight through the text manager (the HUD, the cool-down notice), logged every few seconds per screen
local function readDirect(screen, font, x, y, text, align)
    if not readOn() or not text or text == "" then return end
    local now = getTimestampMs()
    if (read.nextLog[screen .. y] or 0) > now then return end
    read.nextLog[screen .. y] = now + 3000
    local tm = getTextManager()
    local w, h = tm:MeasureStringX(font, text), tm:getFontHeight(font)
    if align == "centre" then x = x - w / 2 end
    local sw, sh = getCore():getScreenWidth(), getCore():getScreenHeight()
    local scr = { 0, 0, sw, sh }
    readLine(screen, "direct", font, x, y, w, h, scr, "0", scr, text)
end

local function readRoot(el)
    local e = el
    while e do
        if e.pzoptReadRoot then return e end
        e = e.parent
    end
    return nil
end

local function rect(e)
    return { e:getAbsoluteX(), e:getAbsoluteY(), math.floor(e:getWidth()), math.floor(e:getHeight()) }
end

local function readLog(self, kind, str, x, y, font)
    if not read.logging or str == nil or str == "" then return end
    local root = readRoot(self)
    if not root then return end
    font = font or UIFont.Small
    local tm = getTextManager()
    local text = tostring(str)
    local w, h = tm:MeasureStringX(font, text), tm:getFontHeight(font)
    local ax = x + self:getAbsoluteX() + self:getXScroll()
    local ay = y + self:getAbsoluteY() + self:getYScroll()
    if kind == "right" then ax = ax - w elseif kind == "centre" then ax = ax - w / 2 end
    -- lists and rich text panels draw through a stencil of their own rectangle and scroll vertically
    local clip = (self.Type == "ISScrollingListBox" or (self.Type == "ISRichTextPanel" and self.clip)) and "1" or "0"
    readLine(read.name, kind, font, ax, ay, w, h, rect(self), clip, rect(root), text)
end

local function readWrap()
    if read.wrapped then return end
    read.wrapped = true
    for name, kind in pairs({ drawText = "left", drawTextRight = "right", drawTextCentre = "centre" }) do
        local orig = ISUIElement[name]
        ISUIElement[name] = function(self, str, x, y, r, g, b, a, font)
            if read.logging then pcall(readLog, self, kind, str, x, y, font) end
            return orig(self, str, x, y, r, g, b, a, font)
        end
    end
end

-- the stops: { name, fn }; a stop's fn may return more stops to run right after it (list pages)
local function listPages(prefix, list)
    local stops = {}
    local total = math.max(#list.items * list.itemheight, list:getScrollHeight() or 0) -- rows of two lines make it taller
    local pages = math.max(1, math.ceil(total / math.max(1, list.height)))
    for p = 2, pages do
        table.insert(stops, { name = prefix .. "-p" .. p, fn = function() list:setYScroll(-(p - 1) * list.height) end })
    end
    return stops
end

-- a list of the PZ Optimization screen, page by page: one stop per further page of rows
local function listPages(prefix)
    local m = PzoptMainMenu.instance()
    local list = m and m.list
    if not list or #list.items == 0 then return {} end
    local stops, first = {}, 1
    list.top = 1
    local vis = list:visibleCount()
    local p = 1
    while first + vis <= #list.items do
        first = first + vis
        p = p + 1
        local top = first
        table.insert(stops, { name = prefix .. "-p" .. p, fn = function() list.top = top end })
        list.top = top
        vis = list:visibleCount()
    end
    list.top = 1
    return stops
end

local function readStops()
    local ms = MainScreen.instance
    local s = {}
    local function add(name, fn) table.insert(s, { name = name, fn = fn }) end
    add("menu", function() ms.bottomPanel.pzoptReadRoot = true end)
    -- the PZ Optimization screen (pzopt_mainscreen_menu.lua): home, a settings group, one of its categories, a
    -- subcategory of that (its settings, editable), the other groups, the benchmark's list, the update and compatibility
    -- pages, then the benchmark's own pages: setup, past results, the run comparison (both ways) and the report
    -- the item's own click: home, or the UPDATE page while an update is offered; the controller's hint bar drawn throughout
    add("pzmenu", function()
        ms.bottomPanel.pzoptReadRoot = nil
        ms.pzoptMenuOption.onMouseDown(ms.pzoptMenuOption, 0, 0)
        PzoptMainMenu.instance().forceHints = true
    end)
    for _, id in ipairs({ "perf", "perf/smooth", "perf/smooth/1", "vis", "vis/light", "tools", "tools/overlay/1", "bench", "update", "compat" }) do
        add("pzmenu-" .. id:gsub("/", "-"), function() PzoptMainMenu.instance():select(id) end)
    end
    -- the pages the options tab had (2026-10-18): the home page's presets and tools, a category's own switch, Fix a problem,
    -- the search's results, a setting's detail with the before / after clips, the zoom curve's numbers, the colour row
    add("pzmenu-home-tools", function() PzoptMainMenu.instance():selectNode("home", "master", "uninstall") end)
    add("pzmenu-perf-drive", function() PzoptMainMenu.instance():select("perf/drive") end)
    add("pzmenu-problems", function() PzoptMainMenu.instance():select("problems") end)
    add("pzmenu-problems-1", function() PzoptMainMenu.instance():select("problems/1") end)
    add("pzmenu-search", function()
        local m = PzoptMainMenu.instance()
        m.search:setText("fog")
        m:runSearch("fog")
    end)
    add("pzmenu-clips", function()
        local m = PzoptMainMenu.instance()
        m.search:setText("")
        m:runSearch("")
        if PzoptSettings then PzoptSettings.setClipsOn(true) end
        m:openKey("vehicleSmooth")
    end)
    add("pzmenu-zoom-curve", function()
        if PzoptSettings then PzoptSettings.setClipsOn(false) end
        PzoptMainMenu.instance():openKey("zoomEase")
    end)
    add("pzmenu-colour", function() PzoptMainMenu.instance():openKey("occludedOutlineColour") end)
    -- the impact chart draws only the resources a setting moves: two (fogScalePct), the most of any (chunkGridWidth, five),
    -- none (vehicleSmooth: no chart, the controls right under the text)
    add("pzmenu-chart-few", function() PzoptMainMenu.instance():openKey("fogScalePct") end)
    add("pzmenu-chart-many", function() PzoptMainMenu.instance():openKey("chunkGridWidth") end)
    add("pzmenu-chart-none", function() PzoptMainMenu.instance():openKey("vehicleSmooth") end)
    add("pzmenu-view-simple", function() PzoptMainMenu.instance():setView(1) end)
    -- a next-launch setting changed: the restart prompt, then LATER (the reminder in the breadcrumbs' line), then back
    add("pzmenu-restart-prompt", function()
        local m = PzoptMainMenu.instance()
        m:select("perf/drive/1")
        m:devStep("vehicleSmooth", 1)
    end)
    add("pzmenu-restart-later", function() PzoptMainMenu.instance():hideRestartPrompt() end)
    add("pzmenu-help", function()
        local m = PzoptMainMenu.instance()
        m.forceNoPrompt = true
        m:devStep("vehicleSmooth", -1) -- the setting back (its default); nothing waits for a restart any more
        m.forceNoPrompt = nil
        m:setHelp(true)
    end)
    add("panel", function()
        local m = PzoptMainMenu.instance()
        m:setHelp(false)
        m:select("bench/setup")
    end)
    add("history", function()
        PzoptMainMenu.instance():select("bench/history")
        return listPages("history")
    end)
    add("runs", function()
        local items = PzoptBench.history()
        if #items < 2 then return end
        PzoptBenchmarkRunCompare.show(items[2][1], items[1][1])
        return listPages("runs")
    end)
    add("runs-all", function()
        local m = PzoptMainMenu.instance()
        m:setOnlyDiff(false)
        return listPages("runs-all")
    end)
    add("report", function()
        local c = split(call("devCompare"), "\t")
        if not c[1] or c[1] == "" then return end
        read.code = c[2]
        PzoptBenchmarkReport.show(c[1])
        local m = PzoptMainMenu.instance()
        local more = {}
        for i = 2, #m.list.items do
            table.insert(more, { name = "report-row" .. i, fn = function() m.list:setSelected(i) m:showDetail() end })
        end
        return more
    end)
    add("report-code", function()
        local m = PzoptMainMenu.instance()
        if m and read.code then m.list:setSelected(1) m:applyShareCode(read.code) end
    end)
    return s
end

readTick = function(now)
    if not read.stops then
        readWrap()
        call("devRebuild")
        read.stops, read.at = readStops(), now + 1500
        return
    end
    if read.logging or read.pending or now < read.at then return end
    if #read.stops == 0 then
        -- Escape as the keyboard sends it (MainScreen:onKeyRelease): the open dialog first, then the PZ Optimization screen
        local ms = MainScreen.instance
        local okEsc, err = pcall(function()
            local m = PzoptMainMenu.instance()
            local before = m and m.selected or "-"
            ms:onKeyRelease(Keyboard.KEY_ESCAPE)
            print(string.format("[pzopt] menu: escape check: page %s, screen open %s -> %s, column visible %s",
                tostring(before), tostring(m ~= nil), tostring(PzoptMainMenu.instance() ~= nil), tostring(ms.bottomPanel:isVisible())))
        end)
        if not okEsc then print("[pzopt] menu: escape check failed: " .. tostring(err)) end
        print("[pzopt] benchmark: read tour done (" .. read.stop .. " stops)")
        read.stops, read.at = {}, math.huge
        return
    end
    local st = table.remove(read.stops, 1)
    read.stop = read.stop + 1
    read.name = st.name
    print("[pzopt] benchmark: read stop " .. read.stop .. " " .. st.name) -- where a tour stopped, if it does
    local ok, more = pcall(st.fn)
    if not ok then print("[pzopt] benchmark: read stop " .. st.name .. " failed: " .. tostring(more)) end
    if ok and type(more) == "table" then
        for i = #more, 1, -1 do table.insert(read.stops, 1, more[i]) end
    end
    read.pending = true -- the stop's screen draws for a second, then one frame is logged (readFrameEnd)
    read.at = now + 1000
end

-- The same tour in a game (the harness's pzmenu_read rig: the pause menu and the PZ Optimization screen over the world):
-- its own stops, driven from OnPostUIDraw
PzoptBenchRead = {}
function PzoptBenchRead.begin(stops)
    readWrap()
    read.mode = true
    read.stops, read.at, read.ingame = stops, getTimestampMs() + 1500, true
end

-- OnPostUIDraw: one logged frame per stop, then the game's own screenshot of it
local function readFrameEnd()
    if read.logging and read.ingame and getTimestampMs() < (read.logUntil or 0) then return end -- a game: half a second
    if read.logging then
        read.logging = false
        print("[pzopt] BENCHREAD-END stop=" .. read.stop .. " screen=" .. read.name)
        call("shot", string.format("read-%02d-%s", read.stop, read.name))
        read.at = getTimestampMs() + 600
    elseif read.pending and getTimestampMs() >= read.at then
        read.pending = false
        read.logging = true
        read.logUntil = getTimestampMs() + 600
    end
end

-- ------------------------------------------------------------------ controller rig (devBenchmarkUi=pad)
-- harness/benchmark-pad-check.sh drives the screens with a virtual pad; every change of what the pad drives (the dialog,
-- its focused row / column / control, a list's or the report table's selection) is one BENCHPAD line it reads back.
-- The script ends the trace by writing Zomboid/Lua/pzopt-bench-pad-done.txt.
local pad = { last = nil, nextPoll = 0 }

local function padName(el)
    if not el then return "-" end
    local function named(v) return v ~= nil and v ~= "" and v or nil end -- a tick box's title is "" (its label is its option)
    -- (the on-screen keyboard's letter keys draw their character: chLower)
    local n = named(el.title) or named(el.name) or named(el.chLower) or (el.options and named(el.options[el.joypadIndex or 1])) or el.Type or "?"
    return tostring(n):gsub("%s+", " ")
end

local function padTrace()
    local jd = JoypadState.getMainMenuJoypad()
    local f = jd and jd.focus
    local who, row, col, el, sel = "none", 0, 0, "-", ""
    if f then
        who = (f == MainScreen.instance and "MainScreen") or f.Type or "?"
        row, col = f.joypadIndexY or 0, f.joypadIndex or 0
        local btns = f.joypadButtons
        local e = btns and btns[f.joypadIndex or 1]
        if f == MainScreen.instance and f.joypadButtonsY and f.joypadButtonsY[row] then e = f.joypadButtonsY[row][1] end
        el = padName(e)
        if e and e.Type == "ISScrollingListBox" then sel = " list=" .. tostring(e.selected) .. "/" .. #e.items end
        if e and e.Type == "ISTickBox" then sel = " option=" .. tostring(e.joypadIndex) .. "/" .. #e.options end
        -- the PZ Optimization screen: its detail text's scroll (the right stick, LT / RT), a chooser's or tick box's value
        -- (left / right / A change it), the page (+help while the controller help is up, +prompt for the restart prompt)
        if f.Type == "PzoptMainMenu" and f.detailText then
            sel = sel .. " scroll=" .. tostring(math.floor(f.detailText:getYScroll() or 0))
        end
        if e and e.spec and e.spec.get and e.spec.type ~= "entry" then
            sel = sel .. " value=" .. string.gsub(tostring(e.spec.get()), "%s+", "_")
        end
        if f.Type == "PzoptMainMenu" then
            sel = sel .. " page=" .. tostring(f.selected) .. (f.help and "+help" or "") .. (f.prompt and "+prompt" or "")
            if f.query and f.query ~= "" then sel = sel .. " search=" .. string.gsub(f.query, "%s+", "_") end
        end
        -- the game's on-screen keyboard (L3 in the PZ Optimization screen: its search box): what it holds so far
        if (f.Type == "OnScreenKeyboardPanel" or f.Type == "OnScreenKeyboardEntry") and OnScreenKeyboard then
            sel = sel .. " text=" .. string.gsub(tostring(OnScreenKeyboard.GetCurrentText() or ""), "%s+", "_")
        end
        -- a dialog whose D-pad scrolls its text (the update and compatibility pages): where the text is
        if (f.Type == "PzoptCompatDialog" or f.Type == "PzoptUpdateDialog") and f.text then
            sel = sel .. " scroll=" .. tostring(math.floor(f.text:getYScroll() or 0))
        end
    end
    local open = {}
    for _, c in ipairs({ PzoptUpdateDialog, PzoptCompatDialog }) do
        if c and c.instance then table.insert(open, c.Type) end
    end
    local modal = ""
    local ui = UIManager.getUI()
    for i = 0, ui:size() - 1 do
        local t = ui:get(i):getTable()
        if t and (t.Type == "ISTextBox" or t.Type == "ISModalDialog") and t:isVisible() then modal = " modal=" .. t.Type end
    end
    local line = string.format("focus=%s row=%d col=%d el=%s%s open=%s%s", who, row, col, el, sel, #open > 0 and table.concat(open, ",") or "-", modal)
    if line ~= pad.last then
        pad.last = line
        print("[pzopt] BENCHPAD " .. line)
    end
    local now = getTimestampMs()
    if now >= pad.nextPoll then
        pad.nextPoll = now + 500
        local r = getFileReader("pzopt-bench-pad-done.txt", false)
        if r then
            r:close()
            print("[pzopt] benchmark: pad tour done")
            pad.finished = true
        end
    end
end

-- the cool-down screen between passes (technique 19) and the "starting" notice: drawn over the main menu
local function menuOverlay(self)
    local w, h = getCore():getScreenWidth(), getCore():getScreenHeight()
    if menu.stopped then
        self:drawRect(0, 0, w, h, 0.7, 0, 0, 0)
        getTextManager():DrawStringCentre(UIFont.Large, w / 2, h / 2 - FONT_LARGE, "Benchmark stopped", 1, 1, 1, 1)
        local line2 = menu.stopped.restart and "The game restarts as a normal game..." or "The game closes; start it again for a normal game."
        getTextManager():DrawStringCentre(UIFont.Medium, w / 2, h / 2 + 4, line2, 0.8, 0.8, 0.8, 1)
        return
    end
    if not menu.world then return end
    local c = menu.world
    local left = math.max(0, math.ceil((menu.startAt - getTimestampMs()) / 1000))
    self:drawRect(0, 0, w, h, 0.7, 0, 0, 0)
    local line1 = string.format("Benchmark pass %s of %s: %s (%s)", c[7], c[8], c[9], c[10])
    local line2 = left > 0 and string.format("Cooling down: %d s", left) or "Loading the benchmark world..."
    if left <= 0 and menu.idle and menu.idle[1] == "wait" then
        line2 = string.format("Waiting for other programs to settle: %s %% of the CPU busy (%s s)", menu.idle[2] or "?", menu.idle[3] or "0")
    end
    getTextManager():DrawStringCentre(UIFont.Large, w / 2, h / 2 - FONT_LARGE, line1, 1, 1, 1, 1)
    getTextManager():DrawStringCentre(UIFont.Medium, w / 2, h / 2 + 4, line2, 0.8, 0.8, 0.8, 1)
    readDirect("overlay", UIFont.Large, w / 2, h / 2 - FONT_LARGE, line1, "centre")
    readDirect("overlay", UIFont.Medium, w / 2, h / 2 + 4, line2, "centre")
    if left > 0 and not menu.auto then
        local line3 = "Esc, a click or (B) stops the benchmark"
        getTextManager():DrawStringCentre(UIFont.Small, w / 2, h / 2 + 4 + FONT_MEDIUM + 8, line3, 0.65, 0.65, 0.65, 1)
        readDirect("overlay", UIFont.Small, w / 2, h / 2 + 4 + FONT_MEDIUM + 8, line3, "centre")
    end
end

-- ------------------------------------------------------------------ HUD during a pass (technique 26)

local hud = { text = nil, nextMs = 0, on = nil }

local function hudDraw()
    if hud.on == false then return end
    local now = getTimestampMs()
    if now >= hud.nextMs then
        hud.nextMs = now + 500
        local c = split(call("hud"), "\t")
        if c[1] == nil or c[1] == "" or c[1] == "error" then
            hud.lines = nil
            if hud.on == nil then hud.on = false end -- not a benchmark process: never ask again
            return
        end
        hud.on = true
        local what = string.format("BENCHMARK  %s/%s  %s  -  %s, pass %s  -  %s", c[1], c[2], c[3], c[4], c[5], c[6])
        local progress = nil
        local pct, runPct, left = tonumber(c[8]), tonumber(c[9]), tonumber(c[10])
        if pct then -- this pass's route as a bar (ASCII: the game's fonts have no block glyphs), then the whole run
            local n = math.floor(pct / 5 + 0.5)
            progress = "[" .. string.rep("|", n) .. string.rep(".", 20 - n) .. "] " .. pct .. " %"
            if runPct and left then
                local eta = left >= 90 and string.format("about %d min left", math.floor(left / 60 + 0.5)) or string.format("about %d s left", left)
                progress = progress .. "   run " .. runPct .. " %, " .. eta
            end
        end
        -- one line when it fits the screen, else the progress on a second line (a narrow window, a long sweep label)
        local one = progress and (what .. "  " .. progress) or what
        if getTextManager():MeasureStringX(UIFont.Small, one) <= getCore():getScreenWidth() - 24 then
            hud.lines = { one }
        else
            hud.lines = { what, progress }
        end
    end
    if hud.lines then
        local w = getCore():getScreenWidth()
        for i, line in ipairs(hud.lines) do
            local tw = getTextManager():MeasureStringX(UIFont.Small, line)
            local y = 6 + (i - 1) * (FONT_SMALL + 2)
            getTextManager():DrawString(UIFont.Small, (w - tw) / 2, y, line, 1, 1, 1, 0.75)
            readDirect("hud", UIFont.Small, (w - tw) / 2, y, line)
        end
    end
end

-- ------------------------------------------------------------------ the benchmark's pages: data and actions
-- The setup, past results, run comparison and report are pages of the main menu's PZ Optimization screen
-- (pzopt_mainscreen_menu.lua, 2026-10-18: the shared sidebar / breadcrumbs / list | detail layout of every PZ
-- Optimization menu). This file gives them their data and actions (PzoptBench); PzoptBenchmarkPanel / History /
-- RunCompare / Report.show(...) open those pages (the dev screens, the report after a run, older callers).

local PASSES = { 1, 2, 3, 5 }
local COOLDOWN = { 0, 5, 15, 30 }

local SETUP_OPTIONS = {
    { id = "ab", title = "Compare with the stock game (PZ Optimization off)", default = false,
      desc = "Every scene also runs with PZ Optimization switched off, in A-B-B-A order, so the report shows how much faster this "
          .. "PC is with it. Doubles the run's length; a stock pass boots slower." },
    { id = "uncapped", title = "Uncapped frame rate during the passes", default = true,
      desc = "The frame limiter and v-sync are off while the scenes run, so the score measures what the PC can do instead of the "
          .. "cap. Off: your own frame cap stays (a pass held at the cap reports a bound, not a number)." },
    { id = "warmup", title = "Warm-up pass first (not timed)", default = false,
      desc = "One untimed pass first, so caches, shaders and the file system are warm before anything is measured." },
    { id = "timedemo", title = "Timedemo: camera locked to frames (walked routes)", default = false,
      desc = "The walked routes advance by frames instead of seconds: every run draws exactly the same pictures, at the cost of "
          .. "the game's real-time behaviour. For comparing two GPUs or drivers." },
    { id = "screenshot", title = "Screenshot at the end of each pass", default = true,
      desc = "The game's own screenshot when a pass ends, kept with the run's results: a check that the scene looked right." },
    { id = "hud", title = "Show the benchmark line during passes", default = true,
      desc = "A small line at the top of the screen during a pass: the scene, the pass, its progress and the time left." },
}

local function parseHistoryItem(it)
    -- each scenario's average fps over its passes ("spin 481 fps (2 passes)")
    local parts = {}
    for _, e in ipairs(split(it[6] or "", ";")) do
        local kv = split(e, "=")
        if kv[2] then
            local sum, n = 0, 0
            for _, v in ipairs(split(kv[2], "|")) do
                local x = tonumber(v)
                if x then sum, n = sum + x, n + 1 end
            end
            if n > 0 then table.insert(parts, string.format("%s %.0f fps%s", kv[1], sum / n, n > 1 and (" (" .. n .. " passes)") or "")) end
        end
    end
    return parts
end

local SECTION_TITLES = {
    run = "Benchmark options",
    opt = "PZ Optimization settings (those changed from the defaults in either run)",
    game = "Game settings",
    mods = "Mods",
    sys = "PC",
}
local SECTION_ORDER = { "run", "opt", "game", "mods", "sys" }

local function optLabel(key)
    local ok, label = pcall(function() return PzoptOptionLabel and PzoptOptionLabel(key) end)
    return (ok and label) or key
end

local function pctChange(a, b)
    local x, y = tonumber(a), tonumber(b)
    if not x or not y or x == 0 then return "" end
    local p = (y - x) / x * 100
    return (p >= 0 and "+" or "-") .. string.format("%.1f", math.abs(p)) .. " %"
end

local COLORS = { { 0.16, 0.62, 0.56 }, { 0.91, 0.44, 0.32 }, { 0.27, 0.48, 0.62 }, { 0.91, 0.77, 0.42 }, { 0.55, 0.42, 0.67 }, { 0.42, 0.6, 0.31 } }
local CURVE_LABELS = { "0", "1", "5", "10", "25", "50", "75", "90", "95", "99", "99.5", "99.9", "100" }
local HIST_LABELS = { "0", "2", "3", "4", "5", "6", "7", "8.3", "10", "12.5", "16.7", "20", "25", "33", "50", "100+" }

local function cfgName(c)
    return c == "B" and "stock" or "optimized"
end

local function chartFrame(el, x, y, w, h, title)
    el:drawRect(x, y, w, h, 0.5, 0.08, 0.08, 0.09)
    el:drawRectBorder(x, y, w, h, 0.4, 1, 1, 1)
    el:drawText(fitText(title, UIFont.Small, w - 12), x + 6, y + 3, 0.75, 0.75, 0.78, 1, UIFont.Small)
end

local function lineChart(el, x, y, w, h, title, series, unit)
    chartFrame(el, x, y, w, h, title)
    local max, len = 0, 0
    for _, s in ipairs(series) do
        len = math.max(len, #s)
        for _, v in ipairs(s) do if v > max then max = v end end
    end
    if len < 2 or max <= 0 then
        el:drawText("no data", x + 6, y + FONT_SMALL + 6, 0.6, 0.6, 0.6, 1, UIFont.Small)
        return
    end
    local maxLabel = string.format("%.0f %s", max, unit)
    local top, bottom, left = y + FONT_SMALL + 8, y + h - 6, x + 6 + textW(UIFont.Small, maxLabel) + 6
    el:drawTextRight(maxLabel, left - 4, top - 4, 0.6, 0.6, 0.6, 1, UIFont.Small)
    if bottom - FONT_SMALL >= top - 4 + FONT_SMALL + 2 then
        el:drawTextRight("0", left - 4, bottom - FONT_SMALL, 0.6, 0.6, 0.6, 1, UIFont.Small)
    end
    el:drawRect(left, bottom, x + w - 4 - left, 1, 0.4, 1, 1, 1)
    for si, s in ipairs(series) do
        local col = COLORS[(si - 1) % #COLORS + 1]
        local px, py
        for i, v in ipairs(s) do
            local cx = left + (x + w - 4 - left) * (i - 1) / (len - 1)
            local cy = bottom - (bottom - top) * v / max
            if px then el.javaObject:DrawLine(nil, px, py, cx, cy, 1.5, col[1], col[2], col[3], 0.95) end
            px, py = cx, cy
        end
    end
end

local function barChart(el, x, y, w, h, title, values, labels, col)
    chartFrame(el, x, y, w, h, title)
    local max = 0
    for _, v in ipairs(values) do if v > max then max = v end end
    if #values == 0 or max <= 0 then
        el:drawText("no data", x + 6, y + FONT_SMALL + 6, 0.6, 0.6, 0.6, 1, UIFont.Small)
        return
    end
    local top, bottom = y + FONT_SMALL + 8 + FONT_SMALL, y + h - 4 - FONT_SMALL
    local bw = (w - 8) / #values
    local labelW = 0
    for i, v in ipairs(values) do
        labelW = math.max(labelW, textW(UIFont.Small, v >= 10 and string.format("%.0f", v) or string.format("%.1f", v)), textW(UIFont.Small, labels[i] or ""))
    end
    for i, v in ipairs(values) do
        local bh = (bottom - top) * v / max
        local bx = x + 4 + (i - 1) * bw
        el:drawRect(bx + 1, bottom - bh, bw - 2, bh, 0.9, col[1], col[2], col[3])
        if bw >= labelW + 4 then
            el:drawTextCentre(v >= 10 and string.format("%.0f", v) or string.format("%.1f", v), bx + bw / 2, bottom - bh - FONT_SMALL, 0.8, 0.8, 0.8, 1, UIFont.Small)
            if labels[i] then el:drawTextCentre(labels[i], bx + bw / 2, bottom + 1, 0.6, 0.6, 0.6, 1, UIFont.Small) end
        end
    end
end

PzoptBench = {}

function PzoptBench.scenarios()
    local out = {}
    for _, r in ipairs(rows(call("scenarios"))) do
        table.insert(out, { id = r[1], title = r[2], seconds = tonumber(r[3]) or 40, what = r[4] or "", stresses = r[5] or "" })
    end
    return out
end

PzoptBench.options = SETUP_OPTIONS
PzoptBench.passes = PASSES
PzoptBench.cooldown = COOLDOWN

-- a setup: { scen = { [id] = true }, opt = { [id] = bool }, passes = index, cool = index, label = text }
function PzoptBench.newSetup(scenarios)
    local s = { scen = {}, opt = {}, passes = 3, cool = 1, label = "" }
    for _, sc in ipairs(scenarios) do s.scen[sc.id] = sc.id ~= "sweep" end
    for _, o in ipairs(SETUP_OPTIONS) do s.opt[o.id] = o.default end
    return s
end

-- the selected scenes, passes, A/B, and the estimate in seconds
function PzoptBench.estimate(setup, scenarios)
    local ids, secs = {}, 0
    for _, s in ipairs(scenarios) do
        if setup.scen[s.id] then
            table.insert(ids, s.id)
            secs = secs + s.seconds + 16 -- + boot and world load of the pass's own process (Benchmark.PROCESS_S)
        end
    end
    local passes = PASSES[setup.passes] or 1
    local ab = setup.opt.ab
    local total = secs * passes * (ab and 2 or 1) + (ab and 36 * #ids * passes or 0) + (setup.opt.warmup and secs or 0)
    local cool = COOLDOWN[setup.cool] or 0
    total = total + cool * #ids * passes * (ab and 2 or 1)
    return ids, passes, ab, total, #ids * passes * (ab and 2 or 1)
end

-- plans the run and quits (the next process starts it); returns nil or the error
function PzoptBench.start(setup, scenarios)
    local ids, passes, ab = PzoptBench.estimate(setup, scenarios)
    if #ids == 0 then return "no scene chosen" end
    local o = setup.opt
    local arg = "scenarios=" .. table.concat(ids, ",") .. ";passes=" .. passes .. ";mode=" .. (ab and "ab" or "standard")
        .. ";uncapped=" .. (o.uncapped and "1" or "0") .. ";warmup=" .. (o.warmup and "1" or "0")
        .. ";timedemo=" .. (o.timedemo and "1" or "0") .. ";screenshot=" .. (o.screenshot and "1" or "0")
        .. ";hud=" .. (o.hud and "1" or "0") .. ";cooldown=" .. (COOLDOWN[setup.cool] or 0)
        .. ";label=" .. string.gsub(setup.label or "", "[;=\t\n]", " ")
    local c = split(call("start", arg), "\t")
    if c[1] == "ok" then
        print("[pzopt] benchmark: run " .. tostring(c[2]) .. " planned, restarting")
        if MainScreen.instance and MainScreen.instance.inGame then
            MainScreen.instance:quitToDesktop() -- from the pause menu: the stock quit to desktop saves the game first
        else
            getCore():quit()
        end
        return nil
    end
    print("[pzopt] benchmark: " .. tostring(c[2]))
    return c[2] or "could not start"
end

-- the past runs: { run, date, score, label, ?, per-scene fps, setup summary } with the fps parts parsed
function PzoptBench.history()
    local items = rows(call("history"))
    for _, it in ipairs(items) do it.parts = parseHistoryItem(it) end
    return items
end

function PzoptBench.folder()
    local dir = call("folder")
    pcall(function() openUrl("file://" .. dir) end)
end

-- two runs side by side: the heads, then rows { kind = head | fps | low | section | same | diff | note, label, a, b, extra }
function PzoptBench.compare(runA, runB)
    local c = { runA = runA, runB = runB, heads = {}, scen = {}, setup = {} }
    for _, r in ipairs(rows(call("compareRuns", runA .. "\t" .. runB))) do
        if r[1] == "H" then table.insert(c.heads, r)
        elseif r[1] == "S" then table.insert(c.scen, r)
        elseif r[1] == "D" then table.insert(c.setup, r) end
    end
    print("[pzopt] benchmark: runs compared (" .. runA .. " vs " .. runB .. ")")
    return c
end

function PzoptBench.compareRows(c, onlyDiff)
    local out = {}
    local section = nil
    local function add(kind, label, a, b, extra)
        table.insert(out, { kind = kind, label = label, a = a or "", b = b or "", extra = extra or "", section = section })
    end
    local ha, hb = c.heads[1] or {}, c.heads[2] or {}
    section = "Scores"
    add("section", "Scores")
    add("head", "Score", ha[5], hb[5], pctChange(ha[5], hb[5]))
    for _, s in ipairs(c.scen) do
        add("fps", s[3] .. " fps", s[4], s[5], pctChange(s[4], s[5]))
        add("low", s[3] .. " 1 % low", s[6], s[7], pctChange(s[6], s[7]))
    end
    for _, sec in ipairs(SECTION_ORDER) do
        section = SECTION_TITLES[sec]
        add("section", SECTION_TITLES[sec])
        local n, shown = 0, 0
        for _, d in ipairs(c.setup) do
            if d[2] == sec then
                n = n + 1
                if not onlyDiff or d[7] ~= "1" then
                    shown = shown + 1
                    local label = sec == "opt" and optLabel(d[3]) or d[4]
                    add(d[7] == "1" and "same" or "diff", label, d[5], d[6])
                end
            end
        end
        if shown == 0 then
            add("note", n == 0 and (sec == "mods" and "None in either run" or "Nothing recorded") or "The same in both runs")
        end
    end
    return out
end

-- one run's report: d.head, d.sys, d.scores, d.scen (rows), charts, phases, recommendations, d.file
function PzoptBench.report(run)
    local d = { run = run, sys = {}, scores = {}, scen = {}, curve = {}, hist = {}, ft = {}, persec = {}, ab = {}, cmp = {}, rec = {}, warn = {}, sweep = {}, phases = {} }
    for _, c in ipairs(rows(call("report", run))) do
        local t = c[1]
        if t == "H" then d.head = c
        elseif t == "SYS" then d.sys[c[2]] = c[3] or ""
        elseif t == "SCORE" then d.scores[c[2]] = { score = c[3], ci = c[4] }
        elseif t == "S" then table.insert(d.scen, c)
        elseif t == "CURVE" then d.curve[c[2] .. "/" .. c[3]] = nums(c[4])
        elseif t == "HIST" then d.hist[c[2] .. "/" .. c[3]] = nums(c[4])
        elseif t == "FT" then d.ft[c[2] .. "/" .. c[3]] = nums(c[4])
        elseif t == "PERSEC" then
            local k = c[2] .. "/" .. c[3]
            d.persec[k] = d.persec[k] or {}
            table.insert(d.persec[k], nums(c[5]))
        elseif t == "AB" then d.ab[c[2]] = c
        elseif t == "SWEEP" then table.insert(d.sweep, c)
        elseif t == "SWEEPNOISE" then d.sweepNoise = tonumber(c[2])
        elseif t == "PHASES" then d.phases[c[2] .. "/" .. c[3]] = c[4]
        elseif t == "CMP" then d.cmp[c[2]] = c
        elseif t == "PREV" then d.prev = c
        elseif t == "REC" then table.insert(d.rec, c)
        elseif t == "FILE" then d.file = c[2]
        end
    end
    return d
end

-- the scenario rows of a report as the list shows them, and the selected one's facts as text lines
function PzoptBench.scenLabel(s)
    return s[3] .. "  (" .. cfgName(s[4]) .. ", " .. s[5] .. ")"
end

function PzoptBench.scenFacts(d, s, theirs)
    local lines = {}
    table.insert(lines, string.format("Average %s fps (+- %s at 95 %%), 1 %% low %s, 0.1 %% low %s, p99 %s ms, %s stutters, score %s",
        s[6], s[7], s[8], s[9], s[10], s[11], s[12]))
    table.insert(lines, string.format("Frame time spent: GPU %s %%, game thread %s %%, render thread %s %%, display %s %%", s[14], s[15], s[16], s[17]))
    local ab, cmp = d.ab[s[2]], d.cmp[s[2]]
    if s[4] == "A" and ab then table.insert(lines, "Against the stock game: " .. abText(ab)) end
    if s[4] == "A" and cmp then table.insert(lines, "Against the last run: " .. (cmp[5] or "") .. " % " .. (cmp[7] or "")) end
    if theirs and not theirs.bad then
        local t = theirs.scen[s[2] .. "/" .. s[4]]
        table.insert(lines, "Their PC: " .. (t and (t.fps .. " fps, 1 % low " .. t.low1) or "-"))
    end
    return lines
end

function PzoptBench.recText(d)
    local parts = {}
    for _, r in ipairs(d.rec) do
        local col = r[2] == "warn" and "<RGB:1,0.6,0.5>" or (r[2] == "tip" and "<RGB:0.6,0.8,1>" or "<RGB:0.7,0.95,0.8>")
        table.insert(parts, col .. string.gsub(r[3] or "", "[<>]", "") .. " <RGB:1,1,1> ")
    end
    if #parts == 0 then table.insert(parts, "No findings.") end
    return table.concat(parts, " <LINE> ")
end

-- the charts of a report row into el's rectangle (x, y, w, h): two line charts, then two bar charts when there is room
function PzoptBench.drawCharts(el, d, s, x, y, w, h)
    if not s then return end
    local k = s[2] .. "/" .. s[4]
    local cw = (w - 12) / 2
    local minH = FONT_SMALL * 4 + 20
    if s[2] == "sweep" and #d.sweep > 0 then
        local noise = d.sweepNoise and 2 * d.sweepNoise or 0
        local ry = y
        local maxc = 0.05
        for _, r in ipairs(d.sweep) do maxc = math.max(maxc, math.abs(tonumber(r[6]) or 0)) end
        local nameW = 0
        for _, r in ipairs(d.sweep) do nameW = math.max(nameW, textW(UIFont.Small, r[3])) end
        nameW = math.min(nameW, math.floor(w * 0.35))
        for _, r in ipairs(d.sweep) do
            if ry + FONT_SMALL > y + h then break end
            local cost = tonumber(r[6]) or 0
            local quiet = math.abs(cost) < noise
            local vt = quiet and string.format("~0 (%+.3f ms)", cost) or string.format("%+.3f ms", cost)
            el:drawText(fitText(r[3], UIFont.Small, nameW), x, ry, 0.92, 0.92, 0.92, 1, UIFont.Small)
            local vw = textW(UIFont.Small, "~0 (+0.000 ms)")
            local bx = x + nameW + 12
            local bw = math.max(20, w - nameW - 24 - vw)
            local len = bw * math.abs(cost) / maxc
            if quiet then
                el:drawRect(bx, ry + 2, len, FONT_SMALL - 4, 0.6, 0.5, 0.5, 0.5)
            else
                el:drawRect(bx, ry + 2, len, FONT_SMALL - 4, 0.9, cost >= 0 and 0.91 or 0.16, cost >= 0 and 0.44 or 0.62, cost >= 0 and 0.32 or 0.56)
            end
            el:drawTextRight(vt, x + w, ry, 0.85, 0.85, 0.85, 1, UIFont.Small)
            ry = ry + FONT_SMALL + 3
        end
        return
    end
    if h < minH then return end
    local two = h >= 2 * minH + 12
    local ch = two and math.floor((h - 12) / 2) or h
    lineChart(el, x, y, cw, ch, "Frame times (ms)", { d.ft[k] or {} }, "ms")
    lineChart(el, x + cw + 12, y, cw, ch, "Fps, each pass", d.persec[k] or {}, "fps")
    if not two then return end
    local curve, maxMs = {}, nil
    for i, v in ipairs(d.curve[k] or {}) do
        if i < #CURVE_LABELS then table.insert(curve, v) else maxMs = v end
    end
    barChart(el, x, y + ch + 12, cw, ch, "ms at percentile" .. (maxMs and string.format(" (max %.0f)", maxMs) or ""), curve, CURVE_LABELS, COLORS[3])
    barChart(el, x + cw + 12, y + ch + 12, cw, ch, "Time share by ms", d.hist[k] or {}, HIST_LABELS, COLORS[1])
end

function PzoptBench.open(run)
    local c = split(call("open", run), "\t")
    if c[1] ~= "ok" then print("[pzopt] benchmark: open failed: " .. tostring(c[2])) end
end

function PzoptBench.share(run)
    local code = call("share", run)
    if code and string.sub(code, 1, 6) == "PZOB1:" then
        Clipboard.setClipboard(code)
        return true
    end
    return false
end

-- a friend's share code: their fps per scenario, their PC
function PzoptBench.decode(code)
    local text = call("decode", code)
    if text == "" or string.sub(text, 1, 5) == "error" then return { bad = true } end
    local t = { scen = {}, sys = {} }
    for _, line in ipairs(split(text, "\n")) do
        local k, v = string.match(line, "^([^=]+)=(.*)$")
        if k then
            local id, cfg = string.match(k, "^s%.([%w]+)%.(%a)$")
            if id then
                local f = split(v, "|")
                t.scen[id .. "/" .. cfg] = { fps = f[1], low1 = f[2], p99 = f[3], n = f[4] }
            else
                t.sys[k] = v
            end
        end
    end
    return t
end

-- the PC lines of a report ("CPU | GPU | ..."), broken to a width
function PzoptBench.pcLines(d, theirs, w)
    local out = {}
    local function lines(parts, r, g, b)
        for _, l in ipairs(wrapParts(parts, "  |  ", UIFont.Small, w)) do table.insert(out, { l, r, g, b }) end
    end
    lines({ d.sys.cpu or "", d.sys.gpu or "", (d.sys.ram_gb or "?") .. " GB", (d.sys.screen or "") .. " @ " .. (d.sys.refresh_hz or "?") .. " Hz",
        "cap " .. (d.sys.frame_cap or "?") }, 0.8, 0.8, 0.8)
    lines({ d.sys.os or "", d.sys.java or "", "PZ Optimization " .. (d.sys.pzopt_build or "") }, 0.6, 0.6, 0.6)
    if theirs then
        if theirs.bad then
            lines({ "That is not a benchmark share code." }, 1, 0.85, 0.5)
        else
            lines({ "Their PC: " .. (theirs.sys.cpu or "?"), theirs.sys.gpu or "?", theirs.sys.screen or "?",
                "cap " .. (theirs.sys.frame_cap or "?"), "score " .. (theirs.sys["score.A"] or "-") }, 1, 0.85, 0.5)
        end
    end
    return out
end

PzoptBench.wrap = wrapText
PzoptBench.fit = fitText
PzoptBench.call = call

-- The old entry points: each opens its page of the PZ Optimization screen (the screen first when it is not up)
local function openPage(id, arg)
    if not PzoptMainMenu then return nil end
    return PzoptMainMenu.openAt(id, arg)
end

PzoptBenchmarkPanel = { show = function() return openPage("bench/setup") end }
PzoptBenchmarkHistory = { show = function() return openPage("bench/history") end }
PzoptBenchmarkRunCompare = { show = function(runA, runB) return openPage("bench/compare", { runA, runB }) end }
PzoptBenchmarkReport = { show = function(run) return openPage("bench/report", { run }) end }

-- the PZ Optimization screen's benchmark pages from its list: setup, past results, the two newest runs compared, the
-- newest report (nil when there is no run for it)
function PzoptBenchmarkOpen(kind)
    return openPage("bench/" .. kind)
end

local function install()
    if not MainScreen or MainScreen.pzoptBenchmarkItem then return end
    local ok, has = pcall(function() return getPerformance():hasPzoptOptions() end)
    if not ok or not has then
        print("[pzopt] benchmark: PerformanceSettings override not loaded or build mismatch, not hooked")
        return
    end
    MainScreen.pzoptBenchmarkItem = true
    -- the item that opens the setup dialog is the BENCHMARK card of the PZ Optimization screen (pzopt_mainscreen_menu.lua)
    local stockPrerender = MainScreen.prerender
    function MainScreen:prerender(...)
        stockPrerender(self, ...)
        local okTick, err = pcall(menuTick, self)
        if not okTick then print("[pzopt] benchmark: menu tick: " .. tostring(err)) end
    end
    local stockRender = MainScreen.render
    function MainScreen:render(...)
        stockRender(self, ...)
        if menu.world or menu.stopped then pcall(menuOverlay, self) end
    end
end

install()
Events.OnGameBoot.Add(install)
-- a world started: the spawn override must not reach a later new game, and the next menu visit asks again
Events.OnGameStart.Add(function()
    if selectedDebugScenario and selectedDebugScenario.name == "pzopt benchmark" then selectedDebugScenario = nil end
    menu = {}
end)
Events.OnMainMenuEnter.Add(function() menu.asked = nil end)
Events.OnPostUIDraw.Add(function()
    if read.ingame and read.stops then pcall(readTick, getTimestampMs()) end
    if read.stops then pcall(readFrameEnd) end
    if read.padOn and not pad.finished then pcall(padTrace) end
    if hud.on == false then return end
    pcall(hudDraw)
end)
