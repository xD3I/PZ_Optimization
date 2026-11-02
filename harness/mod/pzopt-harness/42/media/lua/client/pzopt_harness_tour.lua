-- pzopt harness: the PZ OPTIMIZATION screen's mouse tour for a preview video (2026-10-19, docs/menu-critique-2026-10-19.md
-- section 4, the tab layout). Flag options_tour=1 (with the other options rigs: stays on the main menu, the screen open on
-- HOME). The steps below ask harness/menu-tour.sh for real mouse moves, clicks, wheel notches and typing through
-- Zomboid/Lua/pzopt-tour-input.txt (one request per line: "move X Y MS", "click X Y MS", "wheel up|down N", "type TEXT"),
-- window coordinates taken from the screen's own layout, so the hover, the clicks and the typing reach the game the way a
-- player's do. Screenshots Screenshots/pzopt-tour-<name>.png; "[pzopt-tour]" lines in console.txt.

PzoptHarnessTour = {}

local REQ = "pzopt-tour-input.txt"

local function log(s) print("[pzopt-tour] " .. s) end

local function req(line)
    local w = getFileWriter(REQ, true, true)
    if w then
        w:write(line .. "\n")
        w:close()
    end
end

local function screen() return PzoptMainMenu and PzoptMainMenu.instance() end

-- window coordinates of a point inside an element
local function abs(el, x, y) return { math.floor(el:getAbsoluteX() + x), math.floor(el:getAbsoluteY() + y) } end

local function tabPoint(id)
    local m = screen()
    for _, r in ipairs(m.tabRects or {}) do
        if r.id == id then return abs(m, r.x + r.w / 2, m.tabY + m.tabH / 2) end
    end
    return nil
end

local function sidePoint(id)
    local m = screen()
    for _, r in ipairs(m.side.rows or {}) do
        if r.id == id and not r.hidden then return abs(m.side, 40 + r.depth * 14, r.y + r.h / 2) end
    end
    return nil
end

-- a list row: its label (dx = 80) or its value zone (value = true)
local function rowIndex(pred)
    local m = screen()
    for i, it in ipairs(m.list.items or {}) do
        if m.list.rowsY and m.list.rowsY[i] and pred(it) then return i end
    end
    return nil
end

local function rowPoint(i, where)
    if not i then return nil end
    local l = screen().list
    local r = l.rowsY and l.rowsY[i]
    if not r then return nil end
    local x = 80
    if where == "label" then -- (right of the row's switch or fold arrow: a click there selects, it does not toggle)
        if l.toggleX and l.toggleX[i] then x = l.toggleX[i][2] + 40
        elseif l.foldX and l.foldX[i] then x = l.foldX[i][2] + 30 end
    end
    if where == "value" and l.valueX and l.valueX[i] then x = l.valueX[i] + 6 end
    if where == "reset" and l.resetX and l.resetX[i] then x = (l.resetX[i][1] + l.resetX[i][2]) / 2 end
    if where == "toggle" and l.toggleX and l.toggleX[i] then x = (l.toggleX[i][1] + l.toggleX[i][2]) / 2 end
    if where == "fold" and l.foldX and l.foldX[i] then x = (l.foldX[i][1] + l.foldX[i][2]) / 2 end
    return abs(l, x, r[1] + r[2] / 2)
end

local function bySetting(key) return function(it) return it.setting == key end end
local function anySetting(it) return it.setting ~= nil end

-- a sidebar row by a test on the row (the benchmark's reports and their sections have no id of their own)
local function sideFind(pred)
    local m = screen()
    for _, r in ipairs(m.side.rows or {}) do
        if not r.hidden and r.y and pred(r) then return abs(m.side, 40 + (r.depth or 0) * 14, r.y + r.h / 2) end
    end
    return nil
end

local function centre(el) return el and abs(el, el:getWidth() / 2, el:getHeight() / 2) or nil end

-- the preset box in the bar, an entry of its open list by label
local function presetBox()
    local c = screen().presetCard
    local bx, by, bw, bh = c:boxRect()
    return abs(c, bx + bw / 2, by + bh / 2)
end
local function presetEntry(label)
    local pop = screen().presetPop
    for _, hb in ipairs(pop.hit or {}) do
        local e = pop.entries[hb[1]]
        if e and e.label == label then return abs(pop, pop:getWidth() / 2, (hb[2] + hb[3]) / 2) end
    end
    return nil
end

-- the dialogs: the screen's last confirm / message, the text box opened last
local BOX = {}
local function dialogButton(which)
    local m = screen()
    if which == "yes" then return centre(m.confirmModal and m.confirmModal.yes) end
    if which == "ok" then return centre(m.messageModal and m.messageModal.ok) end
    if which == "box" then return centre(BOX.last and BOX.last.yes) end
    return nil
end

-- game UI coordinates -> the X server's (the two can differ: desktop scaling, a window offset); measured at the start by
-- two moves to known X points and the game's own mouse position after each
local K = { ax = 1, bx = 0, ay = 1, by = 0 }
local CAL = {}

local function moveTo(pt, ms, click)
    if not pt then log("target not found") return end
    local x = math.floor(K.ax * pt[1] + K.bx + 0.5)
    local y = math.floor(K.ay * pt[2] + K.by + 0.5)
    req((click and "click " or "move ") .. x .. " " .. y .. " " .. (ms or 500))
end

local function shot(name)
    getCore():TakeFullScreenshot("pzopt-tour-" .. name .. ".png")
    log("screenshot " .. name)
end

-- options_tour=mods: the benchmark run of 2026-10-21 05:07 with 289 Workshop mods enabled (their Lua 72.6 ms a frame)
local MODS_RUN = "20261021-050700"

-- the steps: { delay before the step (ms), function }
local function steps(variant)
    local t = {}
    local function at(ms, fn) table.insert(t, { ms, fn }) end
    at(1200, function() req("move 1000 600 150") end)
    at(900, function() CAL[1] = { getMouseX(), getMouseY() } req("move 3000 1600 150") end)
    at(900, function()
        CAL[2] = { getMouseX(), getMouseY() }
        local dx, dy = CAL[2][1] - CAL[1][1], CAL[2][2] - CAL[1][2]
        if dx ~= 0 and dy ~= 0 then
            K.ax, K.ay = 2000 / dx, 1000 / dy
            K.bx, K.by = 1000 - K.ax * CAL[1][1], 600 - K.ay * CAL[1][2]
        end
        log(string.format("calibration: game (%d,%d) (%d,%d) -> X = %.4f g + %.1f, Y = %.4f g + %.1f", CAL[1][1], CAL[1][2],
            CAL[2][1], CAL[2][2], K.ax, K.bx, K.ay, K.by))
        moveTo({ getCore():getScreenWidth() * 0.62, getCore():getScreenHeight() * 0.55 }, 400)
    end)
    if variant == "mods" then -- (2026-10-23) the teaser's second part: a benchmark report of a modded game, its Mods (Lua) row
        at(1500, function() log("tab bench") moveTo(tabPoint("bench"), 600, true) end)
        at(2000, function()
            local m = screen()
            local run = MODS_RUN
            log("report " .. run)
            local pt = sideFind(function(r) return r.run == run and r.fold ~= nil and not r.scene and not r.section end)
            if pt then moveTo(pt, 900, true)
            else log("report row not on screen: opened directly") m:openPage("bench/report", { run }) end
        end)
        at(3200, function() shot("mods-report") end)
        at(300, function() log("section: Mods (Lua)") moveTo(sideFind(function(r) return r.section == 60 end), 700, true) end)
        at(4500, function() shot("mods-row") end)
        at(300, function()
            local p = screen().detailText
            if p then moveTo(abs(p, p:getWidth() * 0.55, p:getHeight() * 0.5), 700) end
        end)
        for k = 1, 5 do
            at(k == 1 and 1200 or 3200, function() req("wheel down 3") end)
        end
        at(3200, function() shot("mods-functions") end)
        at(1500, function() log("done") end)
        return t
    end
    -- (2026-10-23, the release teaser) the screen opens on Optimizations; each card leads with its summary, the verdict on
    -- this PC and the SAVES / COSTS columns with the resources' icons, the list shows each row's main resource as an icon
    at(1800, function() log("start page") shot("start") end)
    at(600, function() log("sidebar: What bakes") moveTo(sidePoint("perf/world/1"), 700, true) end)
    at(1800, function() log("click treesInChunkTexture") moveTo(rowPoint(rowIndex(bySetting("treesInChunkTexture")), "label"), 600, true) end)
    at(3800, function() shot("card-trees") end)
    -- the list's icons: the cursor runs down the rows (no click)
    at(300, function()
        local i = rowIndex(bySetting("bakeBudget")) or rowIndex(bySetting("bakeScheduler"))
        moveTo(rowPoint(i, "label"), 2200)
    end)
    at(2600, function() log("click treeAppend") moveTo(rowPoint(rowIndex(bySetting("treeAppend")), "label"), 600, true) end)
    at(3400, function() shot("card-append") end)
    -- the bar's sidebar switch: the page takes the whole width, then the sidebar comes back
    at(400, function() log("sidebar off") moveTo(centre(screen().sideToggle), 700, true) end)
    at(3000, function() shot("sidebar-off") end)
    at(300, function() log("sidebar on") moveTo(centre(screen().sideToggle), 400, true) end)
    at(1800, function() log("sidebar: On other cores") moveTo(sidePoint("perf/zombies/1"), 700, true) end)
    at(1800, function() log("click animBonesParallel") moveTo(rowPoint(rowIndex(bySetting("animBonesParallel")), "label"), 500, true) end)
    at(3600, function() shot("card-bones") end)
    -- Loading: a setting that shortens the boot (the hourglass)
    at(300, function() log("tab boot") moveTo(tabPoint("boot"), 600, true) end)
    at(1800, function() log("click the first Loading setting") moveTo(rowPoint(rowIndex(anySetting), "label"), 600, true) end)
    at(3400, function() shot("card-load") end)
    -- Enhancements: God rays and its before / after clip
    at(300, function() log("tab vis") moveTo(tabPoint("vis"), 600, true) end)
    at(1800, function() log("sidebar: God rays") moveTo(sidePoint("vis/light/4"), 600, true) end)
    at(1800, function() log("click godRays") moveTo(rowPoint(rowIndex(bySetting("godRays")), "label"), 500, true) end)
    at(4200, function() shot("godrays") end)
    -- presets: the list (built in, saved on this PC), one loaded, the current settings saved under a name
    at(300, function() log("preset list") moveTo(presetBox(), 800, true) end)
    at(2000, function() moveTo(presetEntry("Recommended"), 600) end)
    at(1400, function() moveTo(presetEntry("Enhanced"), 600) end)
    at(1600, function() shot("presets") log("load Enhanced") moveTo(presetEntry("Enhanced"), 200, true) end)
    at(1600, function() moveTo(dialogButton("yes"), 600, true) end)
    at(2600, function() shot("preset-loaded") moveTo(dialogButton("ok"), 600, true) end)
    at(1600, function() log("SAVE...") moveTo(centre(screen().presetSave), 700, true) end)
    at(1400, function() moveTo(centre(BOX.last and BOX.last.entry), 500, true) end)
    -- (the box opens with the loaded preset's name: emptied, else the typed name is appended to it)
    at(700, function() if BOX.last and BOX.last.entry then BOX.last.entry:setText("") end end)
    at(900, function() req("type Night drive") end)
    at(1600, function() moveTo(dialogButton("box"), 600, true) end)
    at(2400, function() moveTo(dialogButton("ok"), 600, true) end)
    at(1200, function() log("preset list") moveTo(presetBox(), 600, true) end)
    at(2600, function() shot("presets-saved") moveTo(presetBox(), 400, true) end)
    -- the benchmark: the run planner (scenes, passes, Start), then the newest report and its Mods (Lua) section (each
    -- enabled mod's Lua per frame, sampled during the pass)
    at(1200, function() log("tab bench") moveTo(tabPoint("bench"), 600, true) end)
    at(3400, function() shot("planner") end)
    at(300, function()
        local m = screen()
        local newest = m:benchHistory()[1]
        log("report " .. tostring(newest and newest[1]))
        moveTo(sideFind(function(r) return newest and r.run == newest[1] and r.fold ~= nil and not r.scene and not r.section end), 700, true)
    end)
    at(3600, function() shot("report") end)
    at(300, function() log("section: Mods (Lua)") moveTo(sideFind(function(r) return r.section == 60 end), 700, true) end)
    at(3600, function() shot("report-mods") end)
    at(300, function()
        local p = screen().detailText
        if p then moveTo(abs(p, p:getWidth() / 2, p:getHeight() / 2), 600) end
    end)
    at(900, function() req("wheel down 6") end)
    at(3200, function() shot("report-mods-functions") end)
    -- Mods & multiplayer: the compatibility check and its choice
    at(300, function() log("tab compat") moveTo(tabPoint("compat"), 600, true) end)
    at(3200, function() shot("compat") end)
    -- search "memory": the results, then the heap on its page (the RAM icon)
    at(300, function()
        log("search")
        local s = screen().search
        moveTo(abs(s, s:getWidth() / 2, s:getHeight() / 2), 700, true)
    end)
    at(900, function() req("type memory") end)
    at(3000, function() shot("search") end)
    at(500, function() log("open gcHeap") moveTo(rowPoint(rowIndex(bySetting("gcHeap")), "label"), 600, true) end)
    at(400, function() moveTo(rowPoint(rowIndex(bySetting("gcHeap")), "label"), 300, true) end) -- (a second click opens its page)
    at(2500, function()
        local m = screen()
        log("landed on " .. tostring(m.selected) .. ", row " .. tostring(m.list.items[m.list.selected or 0] and m.list.items[m.list.selected].setting))
        local i = rowIndex(bySetting("gcHeap"))
        if i then moveTo(rowPoint(i, "label"), 400) end
    end)
    at(3600, function() shot("heap") end)
    at(1500, function() log("done") end)
    return t
end

function PzoptHarnessTour.start(variant)
    if ISTextBox and not BOX.hooked then -- (the SAVE... name box: its OK button for the click)
        BOX.hooked = true
        local init0 = ISTextBox.initialise
        ISTextBox.initialise = function(self, ...)
            BOX.last = self
            return init0(self, ...)
        end
    end
    local w = getFileWriter(REQ, true, false) -- emptied
    if w then w:close() end
    PzoptHarnessTour.t = steps(variant)
    PzoptHarnessTour.i = 0
    PzoptHarnessTour.at = getTimestampMs()
    log("started, " .. #PzoptHarnessTour.t .. " steps")
end

-- true when the tour is done
function PzoptHarnessTour.tick(now, variant)
    local T = PzoptHarnessTour
    if not T.t then T.start(variant) end
    local nxt = T.t[T.i + 1]
    if not nxt then return true end
    if now - T.at < nxt[1] then return false end
    T.i, T.at = T.i + 1, now
    local ok, err = pcall(nxt[2])
    if not ok then log("step " .. T.i .. " ERROR " .. tostring(err)) end
    local m = screen()
    log("step " .. T.i .. " mouse " .. getMouseX() .. "," .. getMouseY() .. " page " .. tostring(m and m.selected))
    return false
end
