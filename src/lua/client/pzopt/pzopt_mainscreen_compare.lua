-- PZ Optimization: two benchmark passes compared, drawn as the Grafana compare page (2026-10-21).
--
-- harness/grafana/dashboards/pzopt-compare.json ("PZ compare") puts harness runs side by side: their summary numbers with
-- the change against the base run, every run's frame time, fps, GPU, utilization and power per second on one route-time
-- axis, the frame-time percentiles and the game-thread phases. The in-game compare page (PZ OPTIMIZATION > Benchmark >
-- Compare two runs) does the same for two sides, A (the reference) and B, each a pass of a saved run (its scene, variant
-- and pass, as the run page picks one) or a pasted share code, on the run page's panel system (pzopt_mainscreen_report.lua:
-- the grid, the rows that fold, the scrolling, the time series, the sidebar's section leaves): stat tiles with A, B and
-- the change of B against A (green better, red worse, grey within 1 % or a number that is neither), both sides' series
-- overlaid in two colours, the frame-time distribution of both, tables with A, B and change columns (percentiles, the
-- game thread's sub-phases, every scene's result, the Mods (Lua) row) and what differs in the two runs' setups.
--
-- The numbers are the run page's own (PzoptReportDash.load: pzopt.BenchDash per pass), the scenes' results the report's
-- (PzoptBench.report) and the setup rows Past results' (PzoptBench.compare); nothing here measures or estimates. A share
-- code carries only each scene's fps, 1 % low and p99 (the mean of its passes) and the PC, so every other panel shows that
-- side's "no data" note. This file loads before the report file (name order) and only reaches it at run time.

require "ISUI/ISPanel"

PzoptReportCompare = PzoptReportCompare or {}
local C = PzoptReportCompare

local C_TEXT = { r = 0.94, g = 0.94, b = 0.96 }
local C_GREY = { r = 0.66, g = 0.66, b = 0.70 }
local C_DIM = { r = 0.52, g = 0.52, b = 0.56 }
local C_BORDER = { r = 0.31, g = 0.31, b = 0.35 }
local C_CARD = { r = 0.04, g = 0.04, b = 0.06, a = 0.85 }
local C_DIFF = { r = 1, g = 0.85, b = 0.35 } -- (a setting that is not the same in both runs, as Past results marked it)
-- the two sides (Grafana's blue and orange: apart for every kind of colour vision), better / worse (its green and red)
C.SIDE = { a = "#5794F2", b = "#FF9830" }
local GOOD, BAD = "#73BF69", "#F2495C"
local CODE_WHY = "a share code carries only each scene's fps, 1 % low and p99"
local NO_DATA = "No data in this report"

local function R()
    return PzoptReportDash
end

local function tw(font, s)
    return getTextManager():MeasureStringX(font, tostring(s or ""))
end

local function fh(font)
    return getTextManager():getFontHeight(font)
end

local function clip(font, s, w)
    s = tostring(s or "")
    if w <= 0 then return "" end
    if tw(font, s) <= w then return s end
    while #s > 1 and tw(font, s .. "...") > w do s = string.sub(s, 1, #s - 1) end
    return s .. "..."
end

local function num(v)
    if v == nil or v == "" or v == false then return nil end
    local x = tonumber(v)
    if x and x ~= x then return nil end -- (NaN)
    return x
end

-- The page's panels on the run page's 24-column grid (pzopt-compare.json's panels, the run page's stat tiles in the
-- place of its Runs / Change tables): t = cstat (fields { name, better (1 higher, -1 lower, 0 neither), unit, dec } read
-- from the run page's panel src), ts (a series of each side from the first of `from`: { run page panel, series name }
-- (nil: its first), drawn by the run page's time series), hist2 (both distributions), ctable (A / B / change columns,
-- built by `kind`), row.
C.SPEC = {
    { id = 1, t = "cstat", title = "Frame time (route)", x = 0, y = 0, w = 8, h = 5, src = 1,
      fields = { { "p50", -1, "ms", 2 }, { "p99", -1, "ms", 2 }, { "p99.9", -1, "ms", 2 }, { "max", -1, "ms", 2 } } },
    { id = 2, t = "cstat", title = "Throughput", x = 8, y = 0, w = 8, h = 5, src = 2,
      fields = { { "fps", 1, "none", 1 }, { "1% low", 1, "none", 1 }, { ">33 ms", -1, "none", 0 }, { "jitter ms", -1, "ms", 2 } } },
    { id = 3, t = "cstat", title = "Utilization (route mean)", x = 16, y = 0, w = 8, h = 5, src = 3,
      fields = { { "CPU", 0, "percent", 0 }, { "busiest core", 0, "percent", 0 }, { "GPU", 0, "percent", 0 }, { "game thread", 0, "percent", 0 },
          { "render thread", 0, "percent", 0 } } },
    { id = 4, t = "cstat", title = "Chunks / GC", x = 0, y = 5, w = 8, h = 5, src = 5,
      fields = { { "chunk p99 ms", -1, "ms", 1 }, { "chunks/s", 0, "none", 1 }, { "GC pauses", -1, "none", 0 }, { "worst GC ms", -1, "ms", 0 } } },
    { id = 5, t = "cstat", title = "GPU", x = 8, y = 5, w = 8, h = 5, src = 6,
      fields = { { "GPU ms/frame", -1, "ms", 2 }, { "watts", -1, "watt", 1 }, { string.char(176) .. "C", -1, "celsius", 0 }, { "VRAM MiB", 0, "mbytes", 0 } } },
    { id = 6, t = "cstat", title = "Power (route mean)", x = 16, y = 5, w = 8, h = 5, src = 23,
      fields = { { "total W", -1, "watt", 1 }, { "CPU W", -1, "watt", 1 }, { "GPU W", -1, "watt", 1 }, { "J/frame", -1, "joule", 2 } } },
    { id = 7, t = "ctable", kind = "scenes", title = "Every scene of the two runs (mean over its passes)", x = 0, y = 10, w = 24, h = 7 },
    { id = 10, t = "row", title = "Frame time per second (x = route time, 00:00:00 = route start)", x = 0, y = 17, w = 24, h = 1 },
    { id = 11, t = "ts", title = "p99 frame time per second", x = 0, y = 18, w = 12, h = 8, from = { { 12, "p99" } }, unit = "ms",
      thr = { { "none", -1e300 }, { "#F2495C", 16.7 } }, draw = "line", fill = 0, log = true, softMin = 0, dashed = true, ps = 4 },
    { id = 12, t = "ts", title = "Worst frame per second", x = 12, y = 18, w = 12, h = 8, from = { { 12, "max" } }, unit = "ms",
      thr = { { "none", -1e300 }, { "#F2495C", 16.7 } }, draw = "line", fill = 0, log = true, softMin = 0, dashed = true, ps = 4 },
    { id = 13, t = "ts", title = "fps per second", x = 0, y = 26, w = 12, h = 8, from = { { 13, "fps" } }, unit = "none",
      thr = { { "none", -1e300 }, { "#73BF69", 240 } }, draw = "line", fill = 0, softMin = 0, dashed = true, ps = 4 },
    { id = 14, t = "ts", title = "p50 frame time per second", x = 12, y = 26, w = 12, h = 8, from = { { 12, "p50" } }, unit = "ms",
      thr = { { "none", -1e300 }, { "#F2495C", 16.7 } }, draw = "line", fill = 0, log = true, softMin = 0, dashed = true, ps = 4 },
    { id = 15, t = "hist2", title = "Frame time distribution (route, % of frames)", x = 0, y = 34, w = 12, h = 9, unit = "ms" },
    { id = 16, t = "ctable", kind = "pct", title = "Percentiles (route, presented frames and GPU time)", x = 12, y = 34, w = 12, h = 9 },
    { id = 20, t = "row", title = "Utilization", x = 0, y = 43, w = 24, h = 1 },
    { id = 21, t = "ts", title = "GPU busy", x = 0, y = 44, w = 12, h = 8, from = { { 17, "GPU" }, { 18, "GPU" } }, unit = "percent", min = 0, max = 100,
      draw = "line", fill = 0, softMin = 0, ps = 4 },
    { id = 22, t = "ts", title = "Busiest core", x = 12, y = 44, w = 12, h = 8, from = { { 17, "busiest core" } }, unit = "percent", min = 0, max = 100,
      draw = "line", fill = 0, softMin = 0, ps = 4 },
    { id = 23, t = "ts", title = "CPU (whole machine)", x = 0, y = 52, w = 12, h = 8, from = { { 17, "CPU" }, { 18, "CPU" } }, unit = "percent", min = 0, max = 100,
      draw = "line", fill = 0, softMin = 0, ps = 4 },
    { id = 24, t = "ts", title = "Game thread load", x = 12, y = 52, w = 12, h = 8, from = { { 18, "game thread" } }, unit = "percent", min = 0, max = 100,
      draw = "line", fill = 0, softMin = 0, ps = 4 },
    { id = 30, t = "row", title = "Power", x = 0, y = 60, w = 24, h = 1 },
    { id = 31, t = "ts", title = "Power, whole machine", x = 0, y = 61, w = 12, h = 8, from = { { 25, "game total" }, { 24 } }, unit = "watt", min = 0,
      draw = "line", fill = 0, softMin = 0, ps = 4 },
    { id = 32, t = "ts", title = "GPU power", x = 12, y = 61, w = 12, h = 8, from = { { 25, "game GPU" }, { 19, "GPU W" } }, unit = "watt", min = 0,
      draw = "line", fill = 0, softMin = 0, ps = 4 },
    { id = 33, t = "ts", title = "CPU package power", x = 0, y = 69, w = 12, h = 8, from = { { 25, "game CPU" } }, unit = "watt", min = 0,
      draw = "line", fill = 0, softMin = 0, ps = 4 },
    { id = 34, t = "ts", title = "Energy per frame (per second)", x = 12, y = 69, w = 12, h = 8, from = { { 26 } }, unit = "joule", min = 0,
      draw = "line", fill = 0, softMin = 0, ps = 4 },
    { id = 40, t = "row", title = "Game thread (pzopt-gamethread.out, share of stack samples)", x = 0, y = 77, w = 24, h = 1 },
    { id = 41, t = "ctable", kind = "phases", title = "Sub-phases over the route (% of samples; the change in points)", x = 0, y = 78, w = 24, h = 11 },
    { id = 50, t = "row", title = "Mods (Lua): each mod's Lua on the game thread (ModLuaProfile)", x = 0, y = 89, w = 24, h = 1 },
    { id = 51, t = "cstat", title = "Lua per frame (route mean)", x = 0, y = 90, w = 12, h = 5, src = 61,
      fields = { { "all Lua", -1, "ms", 3 }, { "game's Lua", -1, "ms", 3 }, { "mods", -1, "ms", 3 } } },
    { id = 52, t = "cstat", title = "Mods' share of the frame", x = 12, y = 90, w = 12, h = 5, src = 62,
      fields = { { "mods", -1, "percent", 1 }, { "all Lua", -1, "percent", 1 } } },
    { id = 53, t = "ctable", kind = "mods", title = "Mods (route): Lua time per frame, share of the frame, the slowest 1 % of frames", x = 0, y = 95, w = 24, h = 11 },
    { id = 60, t = "row", title = "Setup (benchmark options, PZ Optimization settings, game settings, mods, PC)", x = 0, y = 106, w = 24, h = 1 },
    { id = 61, t = "ctable", kind = "setup", title = "Setup of the two runs", x = 0, y = 107, w = 24, h = 12 },
}
C.SHORT = { [10] = "Frame time", [20] = "Utilization", [30] = "Power", [40] = "Game thread", [50] = "Mods (Lua)", [60] = "Setup" }
C.collapsed = C.collapsed or {} -- row id -> true while folded (its own: the run page's ids mean other rows)
C.orderedSpec = nil -- (a reload brings a new SPEC)
C.reports = C.reports or {}
C.setups = C.setups or {}

-- ------------------------------------------------------------------ the two sides

-- a side: { run, scene, cfg, pass } (empty fields: the run page's choice) or { code = true, scene, cfg }; a run id alone
-- is that run
function C.side(x)
    if type(x) == "table" then return x end
    return { run = x }
end

-- the state openPage keeps (benchState().cmp): A, B, a pasted code ({ text, t = PzoptBench.decode }) and the setup
-- table's "every setting"
function C.state(a, b)
    local st = { a = C.side(a), b = C.side(b) }
    if not st.a.scene and st.b.scene then st.a.scene = st.b.scene end -- (the same scene on both sides when one names it)
    if not st.b.scene and st.a.scene then st.b.scene = st.a.scene end
    return st
end

local function scenesOf(it)
    local t = {}
    for e in string.gmatch(it[6] or "", "[^;]+") do
        local id = string.match(e, "^([^=]+)=")
        if id then table.insert(t, id) end
    end
    return t
end

local function hasScene(it, id)
    for _, s in ipairs(scenesOf(it)) do
        if s == id then return true end
    end
    return false
end

-- the run before `run` (older) that has the scene, else the one before it, else the one after it; nil alone
function C.previous(items, run, scene)
    local at = nil
    for k, it in ipairs(items) do
        if it[1] == run then at = k end
    end
    if not at then return nil end
    for k = at + 1, #items do
        if not scene or hasScene(items[k], scene) then return items[k][1] end
    end
    if items[at + 1] then return items[at + 1][1] end
    if items[at - 1] then return items[at - 1][1] end
    return nil
end

-- "Compare two runs" from the sidebar: the newest run against the one before it on the same scene (nil without two)
function C.defaultState()
    local items = PzoptBench.history()
    if #items < 2 then return nil end
    local b = items[1][1]
    local scene = scenesOf(items[1])[1]
    local a = C.previous(items, b, scene)
    if not a then return nil end
    return C.state({ run = a, scene = scene }, { run = b, scene = scene })
end

-- a run's report (head, scores, scenes, PC), read once while the game runs (the newest few kept)
function C.report(run)
    for _, e in ipairs(C.reports) do
        if e.run == run then return e.d end
    end
    local d = PzoptBench.report(run)
    table.insert(C.reports, 1, { run = run, d = d })
    while #C.reports > 8 do table.remove(C.reports) end
    return d
end

local function runLabel(it)
    local date = it[2] or it[1]
    return ((it[4] or "") ~= "" and (it[4] .. "  " .. date)) or date
end

-- a share code's pass as the run page's data: its scenes and variants as choices, its fps / 1 % low / p99 in the panels
-- the run page has them in (Throughput, Frame time, Percentiles)
local function codeData(sd, t, titles)
    local d = { vars = { scene = {}, config = {}, pass = {} }, panels = {}, code = true }
    local ids, seen = {}, {}
    for k in pairs(t.scen) do
        local id = string.match(k, "^(.-)/%a$")
        if id and not seen[id] then
            seen[id] = true
            table.insert(ids, id)
        end
    end
    table.sort(ids)
    if #ids == 0 then return d end
    local scene = (sd.scene and seen[sd.scene]) and sd.scene or ids[1]
    for _, id in ipairs(ids) do table.insert(d.vars.scene, { id = id, label = titles[id] or id, sel = id == scene }) end
    local cfg = nil
    for _, c in ipairs({ "A", "B" }) do
        if t.scen[scene .. "/" .. c] then
            cfg = cfg or c
            if sd.cfg == c then cfg = c end
        end
    end
    for _, c in ipairs({ "A", "B" }) do
        if t.scen[scene .. "/" .. c] then
            table.insert(d.vars.config, { id = c, label = c == "B" and "Stock game" or "PZ Optimization", sel = c == cfg })
        end
    end
    local f = t.scen[scene .. "/" .. cfg] or {}
    local valid = (f.n or "") ~= "0" -- (no valid pass: its numbers are not measurements)
    local function v(x) return valid and num(x) and x or "" end
    d.sel = { scene = scene, cfg = cfg, pass = "", title = titles[scene] or scene, passes = f.n }
    d.panels[1] = { v = { { "p99", v(f.p99) } }, rows = {}, s = {} }
    d.panels[2] = { v = { { "fps", v(f.fps) }, { "1% low", v(f.low1) } }, rows = {}, s = {} }
    d.panels[15] = { v = {}, s = {}, cols = { "source", "frames", "p50", "p90", "p99" }, rows = { { "presented", "", "", "", v(f.p99) } } }
    return d
end

local function scenarioTitles()
    local titles = {}
    for _, sc in ipairs(PzoptBench.scenarios() or {}) do titles[sc.id] = sc.title end
    return titles
end

-- why a side has no data in a panel: the code's limit, the pass not read, the run page's own reason for that panel
local function whyOf(side, from)
    if side.code then return CODE_WHY end
    if side.data.error then return "this pass could not be read: " .. tostring(side.data.error) end
    for _, f in ipairs(from) do
        local p = side.data.panels[f[1]]
        if p and p.why then return p.why end
    end
    for _, s in ipairs(R().SPEC) do
        if s.id == from[1][1] and s.why then return s.why end
    end
    return "not recorded in this pass"
end

local function shorten(s, n)
    s = tostring(s or "")
    if #s > n then return string.sub(s, 1, n - 3) .. "..." end
    return s
end

-- both sides of a comparison (`st`, `onlyDiff` for the setup table) and every panel's A / B data
function C.load(st, onlyDiff)
    local cd = { st = st, panels = {}, vars = {} }
    cd.items = PzoptBench.history()
    local titles = scenarioTitles()
    for _, k in ipairs({ "a", "b" }) do
        local sd = st[k]
        local side = { key = k, letter = string.upper(k), hex = C.SIDE[k], col = R().rgb(C.SIDE[k]) }
        if sd.code and st.code and st.code.t then
            side.code = true
            side.data = codeData(sd, st.code.t, titles)
            side.sys = st.code.t.sys or {}
        elseif not sd.run then -- (the code chosen while none is pasted any more)
            side.data = { vars = { scene = {}, config = {}, pass = {} }, panels = {}, error = "no run chosen" }
            side.rep, side.sys = { scores = {}, scen = {}, sys = {} }, {}
        else
            side.run = sd.run
            side.data = R().load(sd.run, sd.scene, sd.cfg, sd.pass)
            side.rep = C.report(sd.run)
            side.sys = side.rep.sys or {}
            for _, it in ipairs(cd.items) do
                if it[1] == sd.run then side.item = it end
            end
        end
        cd[k] = side
    end
    -- the legend's name of a side: the run's name or date (its pass when both sides are passes of one run)
    for _, k in ipairs({ "a", "b" }) do
        local side = cd[k]
        local name = side.code and "share code" or (side.item and runLabel(side.item) or tostring(side.run))
        local sel = side.data.sel
        if not side.code and cd.a.run == cd.b.run and sel then
            name = name .. " (" .. tostring(sel.title) .. ", " .. (sel.cfg == "B" and "stock, " or "") .. "pass " .. tostring(sel.pass) .. ")"
        end
        side.name = side.letter .. ": " .. shorten(name, 48)
    end
    -- the time axis: both windows, the longer route
    local wa, wb = cd.a.data.window, cd.b.data.window
    if wa or wb then
        wa, wb = wa or wb, wb or wa
        cd.window = { math.min(wa[1], wb[1]), math.max(wa[2], wb[2]) }
    end
    cd.route = math.max(cd.a.data.route or 0, cd.b.data.route or 0)
    if cd.a.run and cd.b.run then
        local key = cd.a.run .. "\t" .. cd.b.run
        if not C.setups[key] then
            C.setups = { [key] = PzoptBench.compare(cd.a.run, cd.b.run) } -- (the one shown)
        end
        cd.setup = C.setups[key]
    end
    cd.onlyDiff = onlyDiff ~= false
    C.build(cd)
    return cd
end

-- ------------------------------------------------------------------ the panels' data

-- B against A: its text and colour (`better` 1: higher is better, -1: lower, 0: neither; `points`: a share's change)
function C.change(a, b, better, points)
    if not a or not b then return "-", C_DIM end
    local d, txt
    if points then
        d = b - a
        txt = R().fixed(math.abs(d), 1) .. " pt"
    else
        if a == 0 then return "-", C_DIM end
        d = (b - a) / math.abs(a) * 100
        txt = R().fixed(math.abs(d), 1) .. " %"
    end
    if txt ~= "0.0 %" and txt ~= "0.0 pt" then txt = (d >= 0 and "+" or "-") .. txt end
    if better == 0 or math.abs(d) < 1 then return txt, C_GREY end
    return txt, R().rgb(d * better > 0 and GOOD or BAD)
end

local function field(d, src, name)
    local p = d.panels[src]
    if not p then return nil end
    for _, f in ipairs(p.v or {}) do
        if f[1] == name then return num(f[2]) end
    end
    return nil
end

local function seriesOf(d, from)
    for _, f in ipairs(from) do
        local p = d.panels[f[1]]
        if p and not p.why then
            for _, e in ipairs(p.s or {}) do
                if not e.lo and (f[2] == nil or e.name == f[2]) then return e end
            end
        end
    end
    return nil
end

-- a table panel of a pass by column name: { rows, col index by name }
local function tableOf(d, id)
    local p = d.panels[id]
    if not p or p.why or not p.cols then return nil end
    local ix = {}
    for i, c in ipairs(p.cols) do ix[c] = i end
    return { rows = p.rows or {}, ix = ix }
end

local function cell(t, c) return { t, c } end

local function fmt(v, unit, dec)
    if not v then return "-" end
    return R().fmt(v, unit, dec)
end

-- a row of A, B and the change of one number
local function abc(label, a, b, unit, dec, better, points)
    local ct, cc = C.change(a, b, better, points)
    return { cell(label), cell(fmt(a, unit, dec)), cell(fmt(b, unit, dec)), cell(ct, cc) }
end

local BUILD = {}

-- every scene of the two runs: fps, 1 % low, p99 of each side (the report's scene rows; the code's numbers)
function BUILD.scenes(cd)
    local function scenesOfSide(side)
        local m = {}
        if side.code then
            for k, f in pairs(side.data.code and cd.st.code.t.scen or {}) do
                local ok = (f.n or "") ~= "0"
                m[k] = { fps = ok and num(f.fps) or nil, low = ok and num(f.low1) or nil, p99 = ok and num(f.p99) or nil }
            end
        else
            for _, s in ipairs(side.rep.scen or {}) do
                local ok = s[5] ~= "0"
                m[s[2] .. "/" .. s[4]] = { title = s[3], fps = ok and num(s[6]) or nil, low = ok and num(s[8]) or nil, p99 = ok and num(s[10]) or nil }
            end
        end
        return m
    end
    local ma, mb = scenesOfSide(cd.a), scenesOfSide(cd.b)
    local keys, seen = {}, {}
    local function addKeys(m)
        local list = {}
        for k in pairs(m) do table.insert(list, k) end
        table.sort(list)
        for _, k in ipairs(list) do
            if not seen[k] then
                seen[k] = true
                table.insert(keys, k)
            end
        end
    end
    addKeys(mb)
    addKeys(ma)
    if #keys == 0 then return nil end
    local titles = scenarioTitles()
    local rows = {}
    for _, k in ipairs(keys) do
        local a, b = ma[k] or {}, mb[k] or {}
        local id, cfg = string.match(k, "^(.-)/(%a)$")
        local label = (a.title or b.title or titles[id] or id or k) .. (cfg == "B" and " (stock game)" or "")
        local fa, fb = C.change(a.fps, b.fps, 1)
        local la, lb = C.change(a.low, b.low, 1)
        local pa, pb = C.change(a.p99, b.p99, -1)
        table.insert(rows, { cell(label), cell(fmt(a.fps, "none", 1)), cell(fmt(b.fps, "none", 1)), cell(fa, fb),
            cell(fmt(a.low, "none", 1)), cell(fmt(b.low, "none", 1)), cell(la, lb),
            cell(fmt(a.p99, "ms", 2)), cell(fmt(b.p99, "ms", 2)), cell(pa, pb) })
    end
    return { cols = { { "scene", flex = true }, { "A fps" }, { "B fps" }, { "change" }, { "A 1% low" }, { "B 1% low" }, { "change" },
        { "A p99" }, { "B p99" }, { "change" } }, rows = rows }
end

-- the percentiles of the presented frames and the GPU time (the run page's Percentiles table, one row per number)
function BUILD.pct(cd)
    local ta, tb = tableOf(cd.a.data, 15), tableOf(cd.b.data, 15)
    if not ta and not tb then return nil end
    local function get(t, source, col)
        if not t or not t.ix[col] then return nil end
        for _, r in ipairs(t.rows) do
            if r[1] == source then return num(r[t.ix[col]]) end
        end
        return nil
    end
    local rows = {}
    for _, c in ipairs({ "p50", "p90", "p99", "p99.9", "max" }) do
        table.insert(rows, abc(c, get(ta, "presented", c), get(tb, "presented", c), "ms", 2, -1))
    end
    table.insert(rows, abc("frames > 16.7 ms", get(ta, "presented", ">16.7"), get(tb, "presented", ">16.7"), "none", 0, -1))
    table.insert(rows, abc("frames > 33.3 ms", get(ta, "presented", ">33.3"), get(tb, "presented", ">33.3"), "none", 0, -1))
    table.insert(rows, abc("stdev", get(ta, "presented", "stdev"), get(tb, "presented", "stdev"), "ms", 2, -1))
    table.insert(rows, abc("frames", get(ta, "presented", "frames"), get(tb, "presented", "frames"), "none", 0, 0))
    for _, c in ipairs({ "p50", "p99", "max" }) do
        table.insert(rows, abc("GPU time " .. c, get(ta, "GPU time", c), get(tb, "GPU time", c), "ms", 2, -1))
    end
    return { cols = { { "", flex = true }, { "A" }, { "B" }, { "change" } }, rows = rows }
end

-- one table of a pass by its first column (a name) and a number column, both sides' rows, the largest first
local function byName(cd, id, col)
    local ta, tb = tableOf(cd.a.data, id), tableOf(cd.b.data, id)
    if not ta and not tb then return nil end
    local names, va, vb = {}, {}, {}
    local function read(t, into)
        if not t or not t.ix[col] then return end
        for _, r in ipairs(t.rows) do
            local n = r[1]
            if n and n ~= "" then
                if into[n] == nil and va[n] == nil and vb[n] == nil then table.insert(names, n) end
                into[n] = r
            end
        end
    end
    read(ta, va)
    read(tb, vb)
    local function top(n)
        return math.max(num(va[n] and va[n][ta.ix[col]]) or 0, num(vb[n] and vb[n][tb.ix[col]]) or 0)
    end
    table.sort(names, function(x, y) return top(x) > top(y) end)
    return names, va, vb, ta, tb
end

-- the game thread's sub-phases (the run page's Sub-phases (route) table) of both sides; the change in points
function BUILD.phases(cd)
    local names, va, vb, ta, tb = byName(cd, 30, "share")
    if not names then return nil end
    local rows = {}
    for _, n in ipairs(names) do
        local a = va[n] and num(va[n][ta.ix.share]) or nil
        local b = vb[n] and num(vb[n][tb.ix.share]) or nil
        table.insert(rows, abc(n, a, b, "percent", 1, 0, true))
    end
    return { cols = { { "sub-phase", flex = true }, { "A" }, { "B" }, { "change" } }, rows = rows }
end

-- every mod's Lua on both sides (the run page's Mods (route) table)
function BUILD.mods(cd)
    local names, va, vb, ta, tb = byName(cd, 64, "ms/frame")
    if not names then return nil end
    local rows = {}
    local function at(m, t, n, c)
        return m[n] and t and t.ix[c] and num(m[n][t.ix[c]]) or nil
    end
    for _, n in ipairs(names) do
        local r = abc(n, at(va, ta, n, "ms/frame"), at(vb, tb, n, "ms/frame"), "ms", 3, -1)
        for _, c in ipairs({ { "% of frame", "percent", 1 }, { "p99 spike ms", "ms", 2 } }) do
            local a, b = at(va, ta, n, c[1]), at(vb, tb, n, c[1])
            local ct, cc = C.change(a, b, -1)
            table.insert(r, cell(fmt(a, c[2], c[3])))
            table.insert(r, cell(fmt(b, c[2], c[3])))
            table.insert(r, cell(ct, cc))
        end
        table.insert(rows, r)
    end
    return { cols = { { "mod", flex = true }, { "A ms/frame" }, { "B ms/frame" }, { "change" }, { "A % of frame" }, { "B % of frame" }, { "change" },
        { "A p99 spike" }, { "B p99 spike" }, { "change" } }, rows = rows }
end

-- the two runs' setups (Past results' compare rows past its scores): every section, what differs (all with onlyDiff off)
function BUILD.setup(cd)
    if not cd.setup then return nil end
    local rows = {}
    for _, r in ipairs(PzoptBench.compareRows(cd.setup, cd.onlyDiff)) do
        if r.section ~= "Scores" then
            if r.kind == "section" then
                table.insert(rows, { head = true, cell(r.label, C_GREY) })
            elseif r.kind == "note" then
                table.insert(rows, { cell(r.label, C_DIM), cell(""), cell("") })
            else
                local diff = r.kind == "diff"
                table.insert(rows, { cell(r.label, diff and C_DIFF or nil), cell(r.a ~= "" and r.a or "-"), cell(r.b ~= "" and r.b or "-") })
            end
        end
    end
    return { cols = { { "setting", flex = true }, { "A", flex = true }, { "B", flex = true } }, rows = rows }
end

-- every panel's data: { ok (data on a side), why, and per type: fields | s (series) | hists | cols + rows }
function C.build(cd)
    for _, s in ipairs(C.SPEC) do
        local p = { v = {}, rows = {}, s = {} }
        if s.t == "cstat" then
            p.fields = {}
            for _, f in ipairs(s.fields) do
                local a, b = field(cd.a.data, s.src, f[1]), field(cd.b.data, s.src, f[1])
                table.insert(p.fields, { name = f[1], better = f[2], unit = f[3], dec = f[4], a = a, b = b })
                if a or b then p.ok = true end
            end
            p.noA, p.noB = cd.a.data.panels[s.src] == nil, cd.b.data.panels[s.src] == nil
            if not p.ok then p.why = whyOf(cd.a, { { s.src } }) end
        elseif s.t == "ts" then
            local whys = {}
            for _, side in ipairs({ cd.a, cd.b }) do
                local e = seriesOf(side.data, s.from)
                if e then
                    p.ok = true
                    table.insert(p.s, { name = side.name, t = e.t, v = e.v, col = side.hex })
                else
                    local why = whyOf(side, s.from)
                    table.insert(whys, side.letter .. ": " .. why)
                    table.insert(p.s, { name = side.letter .. ": no data (" .. shorten(why, 60) .. ")", t = {}, v = {}, col = side.hex })
                end
            end
            if not p.ok then
                p.s = {}
                p.why = table.concat(whys, "; ")
            end
        elseif s.t == "hist2" then
            p.hists = {}
            for _, side in ipairs({ cd.a, cd.b }) do
                local h = side.data.panels[14] and side.data.panels[14].hist
                local total = 0
                for _, c in ipairs(h and h.counts or {}) do total = total + (c or 0) end
                if h and total > 0 then
                    p.ok = true
                    p.bucket = h.bucket
                    table.insert(p.hists, { side = side, h = h, total = total })
                else
                    table.insert(p.hists, { side = side, why = whyOf(side, { { 14 } }) })
                end
            end
            if not p.ok then p.why = whyOf(cd.a, { { 14 } }) end
        elseif s.t == "ctable" then
            local t = BUILD[s.kind](cd)
            if t and #t.rows > 0 then
                p.cols, p.rows, p.ok = t.cols, t.rows, true
            elseif s.kind == "setup" then
                p.why = (cd.a.code or cd.b.code) and "a share code carries no settings" or "no setup recorded"
            elseif s.kind == "scenes" then
                p.why = "no scene result in either run"
            elseif s.kind == "mods" then
                p.why = whyOf(cd.a.code and cd.b or cd.a, { { 64 } })
            else
                p.why = whyOf(cd.a.code and cd.b or cd.a, { { s.kind == "pct" and 15 or 30 } })
            end
        end
        cd.panels[s.id] = p
    end
end

-- ------------------------------------------------------------------ the choosers (the menu's PzoptMenuControl specs)

-- a side's title and date (as the run page's head: the date, then the run's name; head[3] is the date)
local function sideTitle(side)
    if side.code then return "Share code", "their PC" end
    local title = (side.rep and side.rep.head and side.rep.head[3]) or (side.item and side.item[2]) or tostring(side.run or "no run chosen")
    local date = (side.rep and side.rep.head and side.rep.head[4]) or (side.item and side.item[4]) or ""
    return title, date
end

-- (2026-10-21, maintainer) per side: its title ("Run A", the run's date and name) on a line of its own, its dropdowns under
-- it (the run: every saved run, the pasted share code; then the pass's scene, variant and pass), A over B, a divider
-- under B's; each column as wide on both lines
function C.controls(cd, st)
    local out, cols = {}, {}
    local function chooser(col, label, list, apply)
        if not list or #list == 0 then return end
        local function at()
            for i, v in ipairs(list) do
                if v.sel then return i end
            end
            return 1
        end
        local spec = { type = "chooser", label = label, choices = list,
            get = function() return list[at()].label end,
            set = function(dir)
                local n = #list
                apply(list[(((at() - 1 + (dir or 1)) % n) + n) % n + 1].id)
            end,
            options = function() return R().dropOptions(list, apply) end }
        cols[col] = cols[col] or {}
        table.insert(cols[col], spec)
        table.insert(out, spec)
    end
    for _, k in ipairs({ "a", "b" }) do
        local sd, side = st[k], cd[k]
        local title, date = sideTitle(side)
        table.insert(out, { type = "heading", label = "Run " .. side.letter, segs = {
            { "Run " .. side.letter, side.col }, { "   " .. title, C_TEXT }, { date ~= "" and ("   " .. date) or "", C_GREY } } })
        local runs = {}
        if st.code then table.insert(runs, { id = "", label = "Share code (their PC)", sel = side.code == true }) end
        for _, it in ipairs(cd.items) do table.insert(runs, { id = it[1], label = runLabel(it), sel = not side.code and it[1] == side.run }) end
        chooser("run", "Run", runs, function(id)
            -- (the scene and variant stay when the other run has them; its first pass)
            if id == "" then st[k] = { code = true, scene = sd.scene, cfg = sd.cfg } else st[k] = { run = id, scene = sd.scene, cfg = sd.cfg } end
        end)
        local sel = side.data.sel or {}
        local vars = side.data.vars or {}
        chooser("scene", "Scene", vars.scene, function(id) sd.scene, sd.cfg, sd.pass = id, nil, nil end)
        chooser("variant", "Variant", vars.config, function(id) sd.scene, sd.cfg, sd.pass = sel.scene, id, nil end)
        if not side.code then chooser("pass", "Pass", vars.pass, function(id) sd.scene, sd.cfg, sd.pass = sel.scene, sel.cfg, id end) end
    end
    table.insert(out, { type = "rule", label = "" })
    for _, specs in pairs(cols) do
        local w = 0
        for _, s in ipairs(specs) do w = math.max(w, R().controlW(s)) end
        for _, s in ipairs(specs) do s.w = w end
    end
    return out
end

-- ------------------------------------------------------------------ the strip over the panels

-- per side: its letter, then its scene, variant, pass, score and PC (its title is over its dropdowns: C.controls); then
-- how to read the change
function C.strip(o, W)
    local out, y = {}, 0
    local cd = o.data
    local function line(segs, font)
        table.insert(out, { segs = segs, font = font, y = y })
        y = y + fh(font) + 2
    end
    for _, side in ipairs({ cd.a, cd.b }) do
        local sel = side.data.sel or {}
        local parts = {}
        if sel.title then
            local pass = side.code and ((sel.passes and sel.passes ~= "") and ("mean of " .. sel.passes .. " passes") or "mean of its passes") or ("pass " .. tostring(sel.pass))
            table.insert(parts, sel.title .. ", " .. (sel.cfg == "B" and "Stock game" or "PZ Optimization") .. ", " .. pass)
        end
        -- (2026-10-21, the Mac: a share code without a score, from a pass that was invalid, fell through to side.rep: nil)
        local sc
        if side.code then sc = side.sys["score." .. (sel.cfg or "A")]
        elseif side.rep and side.rep.scores and side.rep.scores[sel.cfg or "A"] then sc = side.rep.scores[sel.cfg or "A"].score end
        if sc and sc ~= "" then table.insert(parts, "score " .. tostring(sc)) end
        local pc = {}
        for _, k in ipairs({ "cpu", "gpu" }) do
            if side.sys[k] and side.sys[k] ~= "" then table.insert(pc, side.sys[k]) end
        end
        if side.sys.screen and side.sys.screen ~= "" then table.insert(pc, side.sys.screen) end
        if #pc > 0 then table.insert(parts, table.concat(pc, " | ")) end
        if side.data.error then table.insert(parts, "this pass could not be read (" .. tostring(side.data.error) .. ")") end
        line({ { side.letter .. "  ", side.col }, { clip(UIFont.Small, table.concat(parts, "   "), W - tw(UIFont.Small, side.letter .. "  ")), C_GREY } }, UIFont.Small)
    end
    -- (2026-10-21, maintainer) a side run with the full performance overlay on (BenchResults: PROFILER) carries its cost
    local pa = not cd.a.code and cd.a.rep and cd.a.rep.profiler
    local pb = not cd.b.code and cd.b.rep and cd.b.rep.profiler
    if pa or pb then
        local warn = (pa and pb) and "Both runs had the full performance overlay on: their scores are lower than without it, "
                .. "but they compare with each other."
            or ("Only " .. (pa and "A" or "B") .. " had the full performance overlay on: drawing and sampling it cost frames, so "
                .. (pa and "A" or "B") .. "'s numbers are lower than without it and the change includes the overlay's cost.")
        for _, l in ipairs(PzoptBench.wrap(warn, UIFont.Small, W)) do line({ { l, { r = 1, g = 0.75, b = 0.3 } } }, UIFont.Small) end
    end
    local note = "Change: B against A; green is better, red worse, grey within 1 % (or a number that is neither)."
    if cd.a.code or cd.b.code then note = note .. " The share code side has only fps, 1 % low and p99: its other panels say no data." end
    for _, l in ipairs(PzoptBench.wrap(note, UIFont.Small, W)) do line({ { l, C_DIM } }, UIFont.Small) end
    o.strip = out
    return y + 6
end

-- ------------------------------------------------------------------ drawing

-- a stat tile: per field its name, A's and B's value under their letters, the change; side by side
function C.drawStat(o, it, x, y)
    local p = it.p
    local pad = 6
    local bx, by = x + pad, y + o.titleH
    local bw, bh = it.w - 2 * pad, it.h - o.titleH - pad
    local n = #p.fields
    local cw = (bw - (n - 1) * 4) / n
    local hS = o.hS
    local vf = (hS * 2 + fh(UIFont.Medium) * 2 + 12 <= bh) and UIFont.Medium or UIFont.Small
    local hv = fh(vf)
    local total = hS * 2 + hv * 2 + 8
    local ty0 = by + math.max(0, math.floor((bh - total) / 2))
    local ca, cb = R().rgb(C.SIDE.a), R().rgb(C.SIDE.b)
    for i, f in ipairs(p.fields) do
        local cx = math.floor(bx + (i - 1) * (cw + 4))
        local w = math.floor(cw)
        o:drawRect(cx, by, w, bh, 0.5, 0.08, 0.08, 0.1)
        local ty = ty0
        o:drawText(clip(UIFont.Small, f.name, w - 10), cx + 6, ty, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
        ty = ty + hS + 2
        for _, sd in ipairs({ { "A", f.a, ca, p.noA }, { "B", f.b, cb, p.noB } }) do
            local lw = tw(UIFont.Small, sd[1]) + 8
            o:drawText(sd[1], cx + 6, ty + math.floor((hv - hS) / 2), sd[3].r, sd[3].g, sd[3].b, 1, UIFont.Small)
            local txt = sd[2] and R().fmt(sd[2], f.unit, f.dec) or (sd[4] and "no data" or "-")
            local font = sd[2] and vf or UIFont.Small
            if tw(font, txt) > w - lw - 12 then font = UIFont.Small end
            local c = sd[2] and C_TEXT or C_DIM
            o:drawTextRight(clip(font, txt, w - lw - 12), cx + w - 6, ty + math.floor((hv - fh(font)) / 2), c.r, c.g, c.b, 1, font)
            ty = ty + hv + 2
        end
        local ct, cc = C.change(f.a, f.b, f.better)
        o:drawText(clip(UIFont.Small, "B vs A", w - tw(UIFont.Small, ct) - 18), cx + 6, ty, C_DIM.r, C_DIM.g, C_DIM.b, 1, UIFont.Small)
        o:drawTextRight(ct, cx + w - 6, ty, cc.r, cc.g, cc.b, 1, UIFont.Small)
    end
end

-- a table with A / B / change columns: number columns as wide as their widest cell, the text columns share the rest;
-- a heading row spans the table; the wheel scrolls the rows (PzoptReportPanel:onMouseWheel)
function C.drawTable(o, it, x, y)
    local p = it.p
    local pad = 6
    local bx, bw = x + pad, it.w - 2 * pad
    if it.colsW ~= it.w then
        local ws, fixed, flexW = {}, 0, 0
        for j, c in ipairs(p.cols) do
            local m = tw(UIFont.Small, c[1])
            for r = 1, math.min(#p.rows, 80) do
                local row = p.rows[r]
                if not row.head and row[j] then m = math.max(m, tw(UIFont.Small, row[j][1])) end
            end
            ws[j] = m + 14
            if c.flex then flexW = flexW + ws[j] else fixed = fixed + ws[j] end
        end
        local k = fixed > bw * 0.75 and (bw * 0.75 / fixed) or 1
        local rest = bw - fixed * k
        for j, c in ipairs(p.cols) do
            if c.flex then ws[j] = math.floor(rest * ws[j] / math.max(1, flexW)) else ws[j] = math.floor(ws[j] * k) end
        end
        it.colW, it.colsW = ws, it.w
    end
    local ws = it.colW
    local rh = o.hS + math.max(8, math.floor(12 * o.scale))
    local hy = y + o.titleH
    local cx = bx
    for j, c in ipairs(p.cols) do
        local ty = hy + math.floor((rh - o.hS) / 2)
        if c.flex then
            o:drawText(clip(UIFont.Small, c[1], ws[j] - 12), cx + 6, ty, C_TEXT.r, C_TEXT.g, C_TEXT.b, 1, UIFont.Small)
        else
            local hc = (string.sub(c[1], 1, 2) == "A " or c[1] == "A") and R().rgb(C.SIDE.a)
                or ((string.sub(c[1], 1, 2) == "B " or c[1] == "B") and R().rgb(C.SIDE.b)) or C_TEXT
            o:drawTextRight(clip(UIFont.Small, c[1], ws[j] - 12), cx + ws[j] - 6, ty, hc.r, hc.g, hc.b, 1, UIFont.Small)
        end
        cx = cx + ws[j]
    end
    o:drawRect(bx, hy + rh - 1, bw, 1, 0.5, C_BORDER.r, C_BORDER.g, C_BORDER.b)
    local top = hy + rh
    local vis = math.max(1, math.floor((y + it.h - pad - top) / rh))
    it.maxScroll = math.max(0, #p.rows - vis)
    it.scroll = math.max(0, math.min(it.scroll or 0, it.maxScroll))
    for r = 1, math.min(vis, #p.rows - it.scroll) do
        local row = p.rows[it.scroll + r]
        local ry = top + (r - 1) * rh
        local ty = ry + math.floor((rh - o.hS) / 2)
        if row.head then
            local c = row[1][2] or C_GREY
            o:drawText(clip(UIFont.Small, row[1][1], bw - 12), bx + 6, ty, c.r, c.g, c.b, 1, UIFont.Small)
        else
            cx = bx
            for j, col in ipairs(p.cols) do
                local v = row[j]
                if v then
                    local c = v[2] or C_TEXT
                    if col.flex then
                        o:drawText(clip(UIFont.Small, v[1], ws[j] - 12), cx + 6, ty, c.r, c.g, c.b, 1, UIFont.Small)
                    else
                        o:drawTextRight(clip(UIFont.Small, v[1], ws[j] - 12), cx + ws[j] - 6, ty, c.r, c.g, c.b, 1, UIFont.Small)
                    end
                end
                cx = cx + ws[j]
            end
        end
        o:drawRect(bx, ry + rh - 1, bw, 1, 0.25, C_BORDER.r, C_BORDER.g, C_BORDER.b)
    end
    if it.maxScroll > 0 then -- where the rows in view are
        local h = it.h - (top - y) - pad
        local barH = math.max(10, math.floor(h * vis / #p.rows))
        local barY = top + math.floor((h - barH) * it.scroll / it.maxScroll)
        o:drawRect(x + it.w - 4, barY, 2, barH, 0.6, 1, 1, 1)
    end
end

-- both frame-time distributions as the share of each side's frames per 0.5 ms bucket (frames counted alike whatever the
-- two frame rates), each side's bars in its colour over the other's, its legend under the chart (a side without data:
-- why); as the run page's histogram
function C.histOps(o, it)
    local p = it.p
    local ops, W, H, hS = {}, it.w, it.h, o.hS
    local pad = 8
    local bucket = p.bucket or 0.5
    local lo, hi, top = nil, nil, 0
    for _, e in ipairs(p.hists) do
        if e.h then
            local n = #e.h.counts
            lo = math.min(lo or e.h.lo, e.h.lo)
            hi = math.max(hi or (e.h.lo + n), e.h.lo + n)
            for i = 1, n do top = math.max(top, 100 * (e.h.counts[i] or 0) / e.total) end
        end
    end
    local legH = hS + 6
    local py = o.titleH + math.floor(hS / 2)
    local ph = H - py - (hS + 6) - legH - pad
    if ph < hS * 2 then ph = hS * 2 end
    local A = R().axis({ softMin = 0 }, 0, math.max(top, 0.01), nil, "percent", py, ph, hS)
    local axisW = 0
    for _, v in ipairs(A.ticks) do axisW = math.max(axisW, tw(UIFont.Small, R().tickText(A, v))) end
    local px = pad + axisW + 8
    local pw = W - px - pad
    local x0, x1 = lo * bucket, hi * bucket
    if x1 <= x0 then x1 = x0 + bucket end
    local function X(v) return px + (v - x0) / (x1 - x0) * pw end
    for _, v in ipairs(A.ticks) do
        local yy = math.floor(A.y(v))
        table.insert(ops, { 1, px, yy, px + pw, yy, 1, 1, 1, 0.07 })
        table.insert(ops, { 4, R().tickText(A, v), px - 4, yy - math.floor(hS / 2), C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small, 1 })
    end
    local step = R().nice((x1 - x0) / math.max(2, math.floor(pw / (tw(UIFont.Small, "000.0 ms") + 16))))
    local v = math.ceil(x0 / step - 1e-9) * step
    local XA = { unit = "ms", step = step }
    while v <= x1 + 1e-9 do
        local xx = math.floor(X(v))
        table.insert(ops, { 1, xx, py, xx, py + ph, 1, 1, 1, 0.07 })
        table.insert(ops, { 4, R().tickText(XA, v), xx, py + ph + 3, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small, 2 })
        v = v + step
    end
    local base = math.floor(A.y(0))
    for _, e in ipairs(p.hists) do
        if e.h then
            local c = e.side.col
            -- one bar per bucket, or per pixel column when the buckets are narrower than a pixel (its highest share)
            local colX, colTop = nil, nil
            local function flush(xa, xb, share)
                if share and share > 0 then
                    local yy = math.floor(A.y(share))
                    local w = math.max(1, xb - xa - (xb - xa > 3 and 1 or 0))
                    table.insert(ops, { 2, xa, yy, w, math.max(1, base - yy), c.r, c.g, c.b, 0.45 })
                    table.insert(ops, { 1, xa, yy, xa + w, yy, c.r, c.g, c.b, 1 })
                end
            end
            for i = 1, #e.h.counts do
                local share = 100 * (e.h.counts[i] or 0) / e.total
                local xa = math.floor(X((e.h.lo + i - 1) * bucket))
                local xb = math.floor(X((e.h.lo + i) * bucket))
                if xb - xa >= 1 then
                    if colX then flush(colX, colX + 1, colTop) colX, colTop = nil, nil end
                    flush(xa, xb, share)
                elseif colX ~= xa then
                    if colX then flush(colX, colX + 1, colTop) end
                    colX, colTop = xa, share
                elseif share > colTop then
                    colTop = share
                end
            end
            if colX then flush(colX, colX + 1, colTop) end
        end
    end
    local lx, ly = pad, H - pad - legH + 4
    for _, e in ipairs(p.hists) do
        local c = e.side.col
        local name = e.h and e.side.name or (e.side.letter .. ": no data (" .. tostring(e.why) .. ")")
        name = clip(UIFont.Small, name, math.floor((W - 2 * pad) / 2) - 30)
        table.insert(ops, { 2, lx, ly + math.floor(hS / 2) - 1, 14, 3, c.r, c.g, c.b, 1 })
        table.insert(ops, { 4, name, lx + 20, ly, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small, 0 })
        lx = lx + 20 + tw(UIFont.Small, name) + 18
    end
    return ops
end

-- a panel: rows and time series as the run page draws them, the compare page's own types here
function C.drawItem(o, it, x, y)
    local s = it.spec
    if s.t == "row" or s.t == "ts" then return R().drawItem(o, it, x, y) end
    o:drawRect(x, y, it.w, it.h, C_CARD.a, C_CARD.r, C_CARD.g, C_CARD.b)
    o:drawRectBorder(x, y, it.w, it.h, 0.6, C_BORDER.r, C_BORDER.g, C_BORDER.b)
    o:drawText(clip(UIFont.Small, s.title, it.w - 16), x + 8, y + 4, C_TEXT.r, C_TEXT.g, C_TEXT.b, 1, UIFont.Small)
    local p = it.p
    if not (p and p.ok) then return R().noData(o, it, x, y) end
    if s.t == "cstat" then
        C.drawStat(o, it, x, y)
    elseif s.t == "ctable" then
        C.drawTable(o, it, x, y)
    elseif s.t == "hist2" then
        if it.opsW ~= it.w or it.opsH ~= it.h then
            it.ops = C.histOps(o, it)
            it.opsW, it.opsH = it.w, it.h
        end
        R().runOps(o, it.ops, x, y)
    end
end

-- ------------------------------------------------------------------ the page model and the menu's entry points

C.MODEL = {
    ordered = function()
        if not C.orderedSpec then C.orderedSpec = R().orderSpec(C.SPEC) end
        return C.orderedSpec
    end,
    short = function() return C.SHORT end,
    collapsed = C.collapsed,
    has = function(cd, s)
        local p = cd.panels[s.id]
        return p ~= nil and p.ok == true
    end,
    panel = function(cd, s) return cd.panels[s.id] end,
    strip = function(o, W) return C.strip(o, W) end,
    item = function(o, it, x, y) return C.drawItem(o, it, x, y) end,
}

-- the sidebar's leaves under "Compare two runs": Overview, then every row with a panel that has data on a side
function C.sections(cd)
    return R().sections(cd, C.MODEL)
end

function C.controlW(spec)
    return R().controlW(spec)
end

-- the menu's showDash: the panel for the compare page's `dash` ({ mod = C, state, data, actions })
function C.newPanel(x, y, w, h, dash)
    return R().newPanel(x, y, w, h, dash, C.MODEL)
end

-- (the menu's message when the page has no two sides)
C.NO_TWO = "Two runs are needed: run the benchmark twice (the run planner), then compare them here; or paste a friend's share code "
    .. "on a report (COMPARE A CODE)."
C.NO_DATA = NO_DATA
