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
    if where == "value" and l.valueX and l.valueX[i] then x = l.valueX[i] + 6 end
    if where == "reset" and l.resetX and l.resetX[i] then x = (l.resetX[i][1] + l.resetX[i][2]) / 2 end
    if where == "toggle" and l.toggleX and l.toggleX[i] then x = (l.toggleX[i][1] + l.toggleX[i][2]) / 2 end
    if where == "fold" and l.foldX and l.foldX[i] then x = (l.foldX[i][1] + l.foldX[i][2]) / 2 end
    return abs(l, x, r[1] + r[2] / 2)
end

local function bySetting(key) return function(it) return it.setting == key end end

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

-- the steps: { delay before the step (ms), function }
local function steps()
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
    at(1500, function() log("home") shot("home") end)
    for _, id in ipairs({ "perf", "vis", "tools", "bench", "update", "compat" }) do
        at(2200, function() log("tab " .. id) moveTo(tabPoint(id), 450, true) end)
    end
    at(2400, function() log("tab vis") moveTo(tabPoint("vis"), 500, true) end)
    -- the sidebar is the category page's table of contents: God rays scrolls Lighting and shadows to its heading
    at(2200, function() log("sidebar: Lighting and shadows") moveTo(sidePoint("vis/light"), 600, true) end)
    at(2200, function() log("sidebar: God rays") moveTo(sidePoint("vis/light/4"), 500, true) end)
    -- the tree: a click on a row shows its detail (resting the mouse selects nothing)
    at(2200, function() log("click godRays") moveTo(rowPoint(rowIndex(bySetting("godRays")), "label"), 500, true) end)
    at(2800, function() shot("settings") end)
    at(400, function() log("click a modifier") moveTo(rowPoint(rowIndex(bySetting("godRaysStrengthPct")), "label"), 450, true) end)
    at(2400, function() log("click godRays") moveTo(rowPoint(rowIndex(bySetting("godRays")), "label"), 450, true) end)
    -- one click on the switch: on, the modifiers under it lose their dim, the amber dot and reset appear
    at(1800, function() log("toggle godRays") moveTo(rowPoint(rowIndex(bySetting("godRays")), "toggle"), 500, true) end)
    at(2600, function() shot("changed") end)
    at(600, function() log("reset godRays") moveTo(rowPoint(rowIndex(bySetting("godRays")), "reset"), 500, true) end)
    -- folding: God rays' modifiers, a group, every group, then every group open again
    at(2000, function() log("fold godRays") moveTo(rowPoint(rowIndex(bySetting("godRays")), "fold"), 500, true) end)
    at(2400, function() shot("folded-setting") end)
    at(400, function() log("open godRays") moveTo(rowPoint(rowIndex(bySetting("godRays")), "fold"), 400, true) end)
    at(2000, function() log("fold a group") moveTo(rowPoint(rowIndex(function(it) return it.id == "group:vis/light/2" end), "label"), 500, true) end)
    at(2400, function() shot("folded-group") end)
    at(400, function()
        log("fold all")
        local m = screen()
        local f = m.foldAllRect
        if f then moveTo(abs(m, f[1] + f[3] / 2, f[2] + f[4] / 2), 500, true) end
    end)
    at(2400, function() shot("folded-all") end)
    at(400, function()
        log("open all")
        local m = screen()
        local f = m.foldAllRect
        if f then moveTo(abs(m, f[1] + f[3] / 2, f[2] + f[4] / 2), 400, true) end
    end)
    at(2200, function() req("wheel up 30") end)
    -- search "memory": the results, then the heap on its page
    at(2200, function()
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
    at(3200, function() shot("heap") end)
    at(1500, function() log("done") end)
    return t
end

function PzoptHarnessTour.start()
    local w = getFileWriter(REQ, true, false) -- emptied
    if w then w:close() end
    PzoptHarnessTour.t = steps()
    PzoptHarnessTour.i = 0
    PzoptHarnessTour.at = getTimestampMs()
    log("started, " .. #PzoptHarnessTour.t .. " steps")
end

-- true when the tour is done
function PzoptHarnessTour.tick(now)
    local T = PzoptHarnessTour
    if not T.t then T.start() end
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
