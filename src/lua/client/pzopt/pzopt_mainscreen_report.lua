-- PZ Optimization: the benchmark report page drawn as the Grafana run page (2026-10-21).
--
-- harness/grafana/dashboards/pzopt-run.json ("PZ run") is the page every harness run is read on; the in-game report
-- (PZ OPTIMIZATION > Benchmark > a report in the sidebar) shows the same panels in the same order, arrangement, titles,
-- units, threshold colours and chart types, for one pass of the run (a scene, its variant, its pass: the in-game
-- counterpart of a harness run). The numbers come from pzopt.BenchDash (perf():pzoptBenchmark("dash", ...)), which
-- answers each panel from the run folder; a panel the in-game benchmark has no data for says "No data in this report"
-- and why, as Grafana says "No data". Nothing here computes or estimates a number: it lays out, formats and draws.
--
-- The grid is Grafana's (24 columns, rows of 30 px + 8 px margins) scaled to the page area; below about 48 small-font
-- characters of width the panels stack in one column in their order, as Grafana's mobile view does. Rows fold and open
-- on a click. The menu (pzopt_mainscreen_menu.lua, showDash) puts the scene / variant / pass choosers and the report's
-- actions over the panel and gives the controller the same reach as before; this file loads after it (name order) and
-- is only reached at run time.

require "ISUI/ISPanel"

PzoptReportDash = PzoptReportDash or {}
local R = PzoptReportDash

local DEG = string.char(176)
local C_TEXT = { r = 0.94, g = 0.94, b = 0.96 }
local C_GREY = { r = 0.66, g = 0.66, b = 0.70 }
local C_DIM = { r = 0.52, g = 0.52, b = 0.56 }
local C_BORDER = { r = 0.31, g = 0.31, b = 0.35 }
local C_CARD = { r = 0.04, g = 0.04, b = 0.06, a = 0.85 }
local C_LINK = { r = 0.43, g = 0.62, b = 1.0 } -- (Grafana's link blue, #6E9FFF)
local C_OK = { r = 0.5, g = 1, b = 0.7 }
local NO_DATA = "No data in this report"

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

-- a comma list of numbers; "nan" (a gap) is false
local function nums(csv)
    local out = {}
    for x in string.gmatch(csv or "", "[^,]+") do
        if x == "nan" then table.insert(out, false) else table.insert(out, tonumber(x) or false) end
    end
    return out
end

-- The panels of pzopt-run.json, in its order (scratchpad gen_spec.py from the dashboard JSON): t = stat | table | ts |
-- hist | row | flame | state | logs; x, y, w, h on the 24-column grid; unit, dec(imals), min, max, thr(esholds:
-- { colour, from }), cm (stat colour mode), vs (stat value size, px), draw / fill (%) / stack / log (base 2) / softMin /
-- dashed (threshold lines) / ps (point size) of a time series, ov (field overrides: color, unit, right axis, w(idth),
-- hidden, dec, gauge, max, link). Added for the game: why (the reason a panel has no data here), order (the panel's query
-- column order: Grafana's palette colour of a series is its position there), lr (legend on the right), text (a table of
-- text values: no number formatting).
R.SPEC ={
    { id = 1, t = "stat", title = "Frame time (route)", x = 0, y = 0, w = 8, h = 4, unit = "ms", dec = 2, thr = { { "#73BF69", -1e300 }, { "#EAB839", 4.167 }, { "#FF9830", 10 }, { "#F2495C", 16.7 } }, cm = "background", vs = 26 },
    { id = 2, t = "stat", title = "Throughput", x = 8, y = 0, w = 8, h = 4, unit = "none", dec = 1, thr = { { "text", -1e300 } }, cm = "none", vs = 26 },
    { id = 3, t = "stat", title = "Utilization (route mean)", x = 16, y = 0, w = 8, h = 4, unit = "percent", dec = 0, thr = { { "#F2495C", -1e300 }, { "#FF9830", 50 }, { "#EAB839", 75 }, { "#73BF69", 90 } }, cm = "background", vs = 26 },
    { id = 4, t = "stat", title = "Verdict", x = 0, y = 4, w = 8, h = 3, unit = "none", thr = { { "text", -1e300 } }, cm = "none", vs = 18 },
    { id = 5, t = "stat", title = "Chunks / GC", x = 8, y = 4, w = 8, h = 3, unit = "none", dec = 1, thr = { { "text", -1e300 } }, cm = "none", vs = 26 },
    { id = 6, t = "stat", title = "GPU", x = 16, y = 4, w = 8, h = 3, unit = "none", dec = 1, thr = { { "text", -1e300 } }, cm = "none", vs = 26 },
    { id = 7, text = true, t = "table", title = "Run", x = 0, y = 7, w = 24, h = 11, ov = { fact = { w = 130 } } },
    { id = 8, why = "the in-game benchmark records no video", t = "table", title = "Recording", x = 0, y = 18, w = 24, h = 3, ov = { video = { link = true, w = 200 }, url = { hidden = true }, uploaded = { unit = "dateTimeAsIso", w = 180 } } },
    { id = 9, text = true, t = "table", title = "Run data", x = 0, y = 21, w = 24, h = 3, ov = { folder = { link = true, w = 200 }, url = { hidden = true }, frames = { link = true, w = 200 }, frames_url = { hidden = true }, uploaded = { unit = "dateTimeAsIso", w = 180 } } },
    { id = 10, t = "row", title = "Frame time (x = route time, 00:00:00 = route start)", x = 0, y = 24, w = 24, h = 1 },
    { id = 11, order = { "game frame", "presented", "GPU" }, t = "ts", title = "Every frame", x = 0, y = 25, w = 24, h = 10, unit = "ms", thr = { { "none", -1e300 }, { "#73BF69", 4.167 }, { "#FF9830", 10 }, { "#F2495C", 16.7 } }, draw = "line", fill = 8, log = true, softMin = 0, dashed = true, ps = 4, ov = { GPU = { color = "#B877D9" }, presented = { color = "#8AB8FF" } } },
    { id = 12, order = { "p50", "p99", "max" }, t = "ts", title = "Per second: p50 / p99 / max", x = 0, y = 35, w = 12, h = 8, unit = "ms", thr = { { "none", -1e300 }, { "#FF9830", 10 }, { "#F2495C", 16.7 } }, draw = "line", fill = 8, log = true, softMin = 0, dashed = true, ps = 4 },
    { id = 13, order = { "fps", "slowest frame as fps" }, t = "ts", title = "fps per second", x = 12, y = 35, w = 12, h = 8, unit = "none", thr = { { "none", -1e300 }, { "#73BF69", 240 } }, draw = "line", fill = 8, softMin = 0, dashed = true, ps = 4 },
    { id = 14, t = "hist", title = "Frame time distribution (route)", x = 0, y = 43, w = 12, h = 8, unit = "ms", fill = 70, bucket = 0.5, noLegend = true },
    { id = 15, t = "table", title = "Percentiles (route)", x = 12, y = 43, w = 12, h = 8, unit = "ms", ov = { frames = { unit = "none" }, [">16.7"] = { unit = "none" }, [">33.3"] = { unit = "none" }, source = { w = 110 } } },
    { id = 16, t = "row", title = "Utilization", x = 0, y = 51, w = 24, h = 1 },
    { id = 17, why = "recorded by runs of this version on", order = { "CPU", "busiest core", "GPU" }, t = "ts", title = "Machine (sysmon)", x = 0, y = 52, w = 12, h = 8, unit = "percent", min = 0, max = 100, draw = "line", fill = 8, softMin = 0, ps = 4 },
    { id = 18, why = "recorded by runs of this version on", order = { "game thread", "render thread", "GPU", "CPU" }, t = "ts", title = "Threads (overlay, per frame)", x = 12, y = 52, w = 12, h = 8, unit = "percent", min = 0, max = 100, draw = "line", fill = 8, softMin = 0, ps = 4 },
    { id = 19, why = "recorded by runs of this version on", order = { "game process cores", "GPU W", "GPU " .. DEG .. "C" }, t = "ts", title = "Game process cores / GPU power", x = 0, y = 60, w = 12, h = 8, unit = "none", draw = "line", fill = 8, softMin = 0, ps = 4, ov = { ["GPU W"] = { unit = "watt", right = true }, ["GPU " .. DEG .. "C"] = { unit = "celsius", right = true } } },
    { id = 20, why = "recorded by runs of this version on", t = "ts", title = "GPU clocks / VRAM", x = 12, y = 60, w = 12, h = 8, unit = "none", draw = "line", fill = 8, softMin = 0, ps = 4, ov = { ["VRAM MiB"] = { unit = "mbytes", right = true } } },
    { id = 21, why = "recorded by runs of this version on", t = "table", title = "CPU per thread (route)", x = 0, y = 68, w = 24, h = 9, ov = { ["% of wall"] = { unit = "percent", thr = { { "#73BF69", -1e300 }, { "#FF9830", 75 }, { "#F2495C", 90 } }, color = "thresholds", gauge = true, max = 100, min = 0 }, thread = { w = 260 } } },
    { id = 22, t = "row", title = "Power", x = 0, y = 77, w = 24, h = 1 },
    { id = 23, t = "stat", title = "Power (route mean)", x = 0, y = 78, w = 24, h = 4, unit = "none", dec = 2, thr = { { "text", -1e300 } }, cm = "none", vs = 20 },
    { id = 24, why = "sysmon runs beside harness runs only (the game's own readings are on the right)", t = "ts", title = "Power (sysmon, every 0.5 s)", x = 0, y = 82, w = 12, h = 8, unit = "watt", min = 0, draw = "line", fill = 8, softMin = 0, ps = 4 },
    { id = 25, why = "no power reading on this machine, or a run before this version", t = "ts", title = "Power (in game / macpower)", x = 12, y = 82, w = 12, h = 8, unit = "watt", min = 0, draw = "line", fill = 8, softMin = 0, ps = 4 },
    { id = 26, why = "needs a total power reading (and a run of this version on)", order = { "J/frame (sysmon)", "J/frame (in game)" }, t = "ts", title = "Energy per frame (per second)", x = 0, y = 90, w = 24, h = 8, unit = "joule", min = 0, draw = "line", fill = 8, softMin = 0, ps = 4 },
    { id = 27, t = "row", title = "Game thread (pzopt-gamethread.out, share of stack samples)", x = 0, y = 98, w = 24, h = 1 },
    { id = 28, why = "recorded by runs of this version on", lr = true, t = "ts", title = "Phases", x = 0, y = 99, w = 24, h = 9, unit = "percent", min = 0, max = 100, draw = "line", fill = 60, stack = true, softMin = 0, ps = 4 },
    { id = 29, why = "recorded by runs of this version on", lr = true, t = "ts", title = "Sub-phases (top 12 over the route)", x = 0, y = 108, w = 24, h = 9, unit = "percent", min = 0, draw = "line", fill = 60, stack = true, softMin = 0, ps = 4 },
    { id = 30, why = "no game-thread profile in this pass", t = "table", title = "Sub-phases (route)", x = 0, y = 117, w = 8, h = 12, ov = { share = { unit = "percent", dec = 1, gauge = true, max = 100, min = 0 }, samples = { w = 80 } } },
    { id = 31, why = "recorded by runs of this version on", t = "table", title = "Hot methods (route)", x = 8, y = 117, w = 8, h = 12, ov = { share = { unit = "percent", dec = 1, gauge = true, max = 25, min = 0 }, samples = { w = 80 } } },
    { id = 32, why = "recorded by runs of this version on", t = "table", title = "Waits (not runnable) (route)", x = 16, y = 117, w = 8, h = 12, ov = { share = { unit = "percent", dec = 1, gauge = true, max = 100, min = 0 }, samples = { w = 80 } } },
    { id = 33, t = "row", title = "Chunks, GC, flips", x = 0, y = 129, w = 24, h = 1 },
    { id = 34, why = "recorded by runs of this version on", t = "ts", title = "Chunk latency (enqueue -> publish, one point per chunk)", x = 0, y = 130, w = 12, h = 8, unit = "ms", draw = "points", fill = 8, log = true, softMin = 0, ps = 3 },
    { id = 35, why = "recorded by runs of this version on", t = "ts", title = "Chunk stages (per-second mean) and chunks/s", x = 12, y = 130, w = 12, h = 8, unit = "ms", draw = "line", fill = 60, stack = true, softMin = 0, ps = 4, ov = { ["chunks/s"] = { unit = "none", color = "text", right = true } } },
    { id = 36, why = "no collector pause in this pass, or a run before this version", t = "ts", title = "GC pauses", x = 0, y = 138, w = 12, h = 8, unit = "ms", draw = "points", fill = 8, softMin = 0, ps = 3 },
    { id = 37, why = "X Present flip times come from the harness's present probe", t = "ts", title = "Flip interval on screen (X Present)", x = 12, y = 138, w = 12, h = 8, unit = "ms", draw = "points", fill = 8, log = true, softMin = 0, ps = 3 },
    { id = 38, t = "row", title = "Drive (path pilot, pzopt-drive.out)", x = 0, y = 146, w = 24, h = 1 },
    { id = 39, why = "recorded by runs of this version on", t = "ts", title = "Speed", x = 0, y = 147, w = 12, h = 8, unit = "velocitykmh", draw = "line", fill = 8, softMin = 0, ps = 4 },
    { id = 40, why = "recorded by runs of this version on", t = "ts", title = "Line and inputs", x = 12, y = 147, w = 12, h = 8, unit = "none", draw = "line", fill = 8, softMin = 0, ps = 4 },
    { id = 41, t = "row", title = "Profiler: flame graph (pick the source and threads above; drag across any time panel to narrow the window)", x = 0, y = 155, w = 24, h = 1 },
    { id = 42, why = "recorded by runs of this version on", t = "flame", title = "Flame graph", x = 0, y = 156, w = 24, h = 22, unit = "short" },
    { id = 43, why = "recorded by runs of this version on", lr = true, t = "ts", title = "Samples per second by thread", x = 0, y = 178, w = 24, h = 8, unit = "none", draw = "line", fill = 60, stack = true, softMin = 0, ps = 4 },
    { id = 44, t = "row", title = "Scheduler (schedmon.txt: --schedmon), memory pressure, frame pacing", x = 0, y = 186, w = 24, h = 1 },
    { id = 45, why = "recorded by runs of this version on", lr = true, t = "ts", title = "CPU per OS thread (top 12)", x = 0, y = 187, w = 12, h = 9, unit = "percent", draw = "line", fill = 8, softMin = 0, ps = 4 },
    { id = 46, why = "recorded by runs of this version on", lr = true, t = "ts", title = "Run-queue wait per OS thread (runnable, no CPU)", x = 12, y = 187, w = 12, h = 9, unit = "percent", draw = "line", fill = 8, softMin = 0, ps = 4 },
    { id = 47, why = "recorded by runs of this version on", t = "ts", title = "Pressure stall (PSI) and paging", x = 0, y = 196, w = 12, h = 8, unit = "none", draw = "line", fill = 8, softMin = 0, ps = 4, ov = { ["re:.*_pct"] = { unit = "percent" } } },
    { id = 48, why = "recorded by runs of this version on", t = "ts", title = "Game process memory by mapping", x = 12, y = 196, w = 12, h = 8, unit = "mbytes", draw = "points", fill = 8, softMin = 0, ps = 3 },
    { id = 49, why = "recorded by runs of this version on", t = "ts", title = "Frame pacing (pzopt-pacing.out)", x = 0, y = 204, w = 24, h = 9, unit = "ms", draw = "points", fill = 8, log = true, softMin = 0, ps = 3 },
    { id = 50, t = "row", title = "Inputs sent to the game (pzopt-input.out: every key / mouse / pad change the game thread saw)", x = 0, y = 213, w = 24, h = 1 },
    { id = 51, why = "recorded by runs of this version on", t = "state", title = "Keys and buttons", x = 0, y = 214, w = 24, h = 10, thr = { { "none", -1e300 }, { "#73BF69", 0.5 } }, fill = 80 },
    { id = 52, why = "recorded by runs of this version on", t = "ts", title = "Analog: pointer and sticks", x = 0, y = 224, w = 12, h = 8, unit = "none", draw = "line", fill = 8, softMin = 0, ps = 4, ov = { ["re:mouse .*"] = { unit = "suffix: px", right = true } } },
    { id = 53, why = "recorded by runs of this version on", t = "ts", title = "Mouse wheel and pad hat", x = 12, y = 224, w = 12, h = 8, unit = "none", draw = "bars", fill = 80, softMin = 0, ps = 4 },
    { id = 54, why = "recorded by runs of this version on", t = "table", title = "Every input event", x = 0, y = 232, w = 12, h = 12 },
    { id = 55, why = "no setup file in this run", text = true, t = "table", title = "Configured by the harness (flags, props, options, env, vmargs, mods, run.opts)", x = 12, y = 232, w = 12, h = 12, ov = { kind = { w = 90 }, key = { w = 220 } } },
    { id = 56, t = "row", title = "Console (pzopt-loadtrace.out: every console line with its epoch)", x = 0, y = 244, w = 24, h = 1 },
    { id = 57, why = "recorded by runs of this version on", t = "logs", title = "Console", x = 0, y = 245, w = 24, h = 14 },
    { id = 58, t = "row", title = "Counters", x = 0, y = 259, w = 24, h = 1 },
    { id = 59, why = "no counters in this pass", t = "table", title = "Counters (pzopt-bench.out, bake / batch counters, chunk stats)", x = 0, y = 260, w = 24, h = 12, ov = { group = { w = 220 } } },
}


-- Grafana's classic palette (palette-classic), in its order
R.PALETTE = { "#7EB26D", "#EAB839", "#6ED0E0", "#EF843C", "#E24D42", "#1F78C1", "#BA43A9", "#705DA0", "#508642", "#CCA300",
    "#447EBC", "#C15C17", "#890F02", "#0A437C", "#6D1F62", "#584477", "#B7DBAB", "#F4D598", "#70DBED", "#F9BA8F", "#F29191",
    "#82B5D8", "#E5A8E2", "#AEA2E0" }
-- a gauge cell's thresholds when the override gives none (Grafana's defaults: green, red from 80)
R.GAUGE_THR = { { "#73BF69", -1e300 }, { "#F2495C", 80 } }
R.SUFFIX = { ms = " ms", percent = "%", watt = " W", joule = " J", celsius = " " .. DEG .. "C", velocitykmh = " km/h",
    mbytes = " MiB", gbytes = " GiB", ["suffix: px"] = " px" }
R.collapsed = R.collapsed or {} -- row id -> true while folded (kept while the game runs)
R.cache = R.cache or {}
R.rgbCache = R.rgbCache or {}

local HEX = "0123456789ABCDEF"

-- "#RRGGBB" -> { r, g, b } (0..1); "text" is the text colour; "none" / "transparent" are nil
function R.rgb(hex)
    if hex == nil or hex == "none" or hex == "transparent" then return nil end
    if hex == "text" then return C_TEXT end
    local c = R.rgbCache[hex]
    if c then return c end
    local h = string.upper(string.sub(hex, 2))
    local function byte(i)
        local a = string.find(HEX, string.sub(h, i, i), 1, true) or 1
        local b = string.find(HEX, string.sub(h, i + 1, i + 1), 1, true) or 1
        return ((a - 1) * 16 + (b - 1)) / 255
    end
    c = { r = byte(1), g = byte(3), b = byte(5) }
    R.rgbCache[hex] = c
    return c
end

-- the threshold colour of a value (the base step for a value that is not a number)
function R.thr(thr, v)
    if not thr or not thr[1] then return nil end
    local c = thr[1][1]
    if type(v) ~= "number" then return c end
    for i = 2, #thr do
        if v >= thr[i][2] then c = thr[i][1] end
    end
    return c
end

-- ------------------------------------------------------------------ numbers as Grafana writes them

function R.round(v)
    if v >= 0 then return math.floor(v + 0.5) end
    return -math.floor(-v + 0.5)
end

function R.fixed(v, dec)
    local s
    if dec <= 0 then s = string.format("%d", R.round(v)) else s = string.format("%." .. math.floor(dec) .. "f", v) end
    if string.sub(s, 1, 1) == "-" and tonumber(s) == 0 then s = string.sub(s, 2) end -- (no "-0.0")
    return s
end

-- the decimals Grafana picks without a "decimals" setting (by magnitude), trailing zeros dropped
function R.autoText(v)
    local a = math.abs(v)
    local dec = (a == 0 or a >= 100) and 0 or (a >= 10 and 1 or (a >= 1 and 2 or 3))
    local s = R.fixed(v, dec)
    if string.find(s, ".", 1, true) then
        s = string.gsub(s, "0+$", "")
        s = string.gsub(s, "%.$", "")
    end
    if s == "-0" then s = "0" end
    return s
end

function R.fmt(v, unit, dec)
    if type(v) ~= "number" then return tostring(v or "") end
    if v ~= v then return "NaN" end
    if unit == "mbytes" and math.abs(v) >= 1024 then v, unit = v / 1024, "gbytes" end
    local s = dec and R.fixed(v, dec) or R.autoText(v)
    return s .. (R.SUFFIX[unit or "none"] or "")
end

-- route seconds as the run page's x axis writes them (HH:MM:SS, 00:00:00 = the route's start)
function R.clock(t)
    local s = math.floor(t + 0.5)
    s = ((s % 86400) + 86400) % 86400 -- (Kahlua's % keeps the dividend's sign)
    return string.format("%02d:%02d:%02d", math.floor(s / 3600), math.floor(s / 60) % 60, s % 60)
end

function R.nice(x)
    if x <= 0 then return 1 end
    local e = 10 ^ math.floor(math.log(x) / math.log(10))
    local f = x / e
    if f <= 1 then return e elseif f <= 2 then return 2 * e elseif f <= 2.5 then return 2.5 * e elseif f <= 5 then return 5 * e end
    return 10 * e
end

-- ------------------------------------------------------------------ the data (pzopt.BenchDash)

function R.parse(text)
    local d = { vars = { scene = {}, config = {}, pass = {} }, panels = {} }
    local cur = nil
    for _, line in ipairs(split(text or "", "\n")) do
        if line ~= "" then
            local c = split(line, "\t")
            local k = c[1]
            if k == "error" then
                d.error = c[2] or "?"
            elseif k == "VAR" then
                if d.vars[c[2]] then table.insert(d.vars[c[2]], { id = c[3] or "", label = c[4] or c[3] or "", sel = c[5] == "1" }) end
            elseif k == "SEL" then
                d.sel = { scene = c[2], cfg = c[3], pass = c[4], step = c[5], title = c[6] }
            elseif k == "ROUTE" then
                d.route = tonumber(c[2])
            elseif k == "WINDOW" then
                d.window = { tonumber(c[2]) or 0, tonumber(c[3]) or 0 }
            elseif cur and k == "WHY" then
                cur.why = c[2]
            elseif cur and k == "F" then
                cur.flame = cur.flame or {}
                table.insert(cur.flame, { tonumber(c[2]) or 0, tonumber(c[3]) or 0, tonumber(c[4]) or 0, c[5] or "" })
            elseif cur and k == "L" then
                cur.logs = cur.logs or {}
                table.insert(cur.logs, { tonumber(c[2]) or 0, c[3] or "info", c[4] or "" })
            elseif k == "P" then
                cur = { id = tonumber(c[2]), v = {}, rows = {}, s = {} }
                d.panels[cur.id] = cur
            elseif cur and k == "V" then
                table.insert(cur.v, { c[2] or "", c[3] or "" })
            elseif cur and k == "C" then
                local cols = {}
                for i = 2, #c do table.insert(cols, c[i]) end
                cur.cols = cols
            elseif cur and k == "R" then
                local row = {}
                for i = 2, #c do table.insert(row, c[i]) end
                table.insert(cur.rows, row)
            elseif cur and k == "S" then
                table.insert(cur.s, { name = c[2] or "", t = nums(c[3]), v = nums(c[4]) })
            elseif cur and k == "B" then
                table.insert(cur.s, { name = c[2] or "", t = nums(c[3]), lo = nums(c[4]), hi = nums(c[5]) })
            elseif cur and k == "HIST" then
                cur.hist = { bucket = tonumber(c[2]) or 0.5, lo = tonumber(c[3]) or 0, counts = nums(c[4]) }
            end
        end
    end
    return d
end

-- one pass of a run (empty fields: the first scene, PZ Optimization's variant, the first pass); the last few are kept
function R.load(run, scene, cfg, pass)
    local key = table.concat({ run or "", scene or "", cfg or "", pass or "" }, "\t")
    for _, e in ipairs(R.cache) do
        if e.key == key then return e.d end
    end
    local t0 = getTimestampMs()
    local d = R.parse(PzoptBench.call("dash", key))
    d.run = run
    PzoptLogInfo(string.format("[pzopt] benchmark: report panels of %s %s/%s/%s read in %d ms%s", tostring(run), tostring(d.sel and d.sel.scene),
        tostring(d.sel and d.sel.cfg), tostring(d.sel and d.sel.pass), getTimestampMs() - t0, d.error and (" (" .. d.error .. ")") or ""))
    if not d.error then
        table.insert(R.cache, 1, { key = key, d = d })
        while #R.cache > 6 do table.remove(R.cache) end
    end
    return d
end

-- the run page's variables as the menu's choosers (scene, variant, pass): `st` is the menu's report state
function R.controls(data, st)
    local out = {}
    local function chooser(label, kind, apply)
        local list = data.vars[kind]
        if not list or #list == 0 then return end
        local function at()
            for i, v in ipairs(list) do
                if v.sel then return i end
            end
            return 1
        end
        table.insert(out, { type = "chooser", label = label, choices = list,
            get = function() return list[at()].label end,
            set = function(dir)
                local n = #list
                local i = (((at() - 1 + (dir or 1)) % n) + n) % n + 1
                apply(list[i].id)
            end,
            options = function() return R.dropOptions(list, apply) end })
    end
    chooser("Scene", "scene", function(id) st.scene, st.cfg, st.pass = id, nil, nil end)
    chooser("Variant", "config", function(id) st.cfg, st.pass = id, nil end)
    chooser("Pass", "pass", function(id) st.pass = id end)
    return out
end

-- (2026-10-21) a chooser's choices as the menu's dropdown list (PzoptMenuControl: a chooser with `options` is a dropdown)
function R.dropOptions(list, apply)
    local t = {}
    for _, v in ipairs(list) do
        local id = v.id
        table.insert(t, { label = v.label, current = v.sel == true, pick = function() apply(id) end })
    end
    return t
end

-- a chooser's width: its label, the widest choice, the arrows (a dropdown: its box's padding and down arrow)
function R.controlW(spec)
    local widest = 0
    for _, c in ipairs(spec.choices or {}) do widest = math.max(widest, tw(UIFont.Small, c.label)) end
    if spec.options then return tw(UIFont.Small, spec.label) + 16 + widest + 16 + fh(UIFont.Small) + 10 end
    return tw(UIFont.Small, spec.label) + 16 + widest + 2 * (tw(UIFont.Small, "<") + 14) + 12
end

-- ------------------------------------------------------------------ layout

-- the panels by grid position (top to bottom, left to right), and each row's span of grid rows
function R.ordered()
    if R.orderedSpec then return R.orderedSpec end
    R.orderedSpec = R.orderSpec(R.SPEC)
    return R.orderedSpec
end

-- (2026-10-21) any page's SPEC in that order (the compare page's too)
function R.orderSpec(spec)
    local list = {}
    for i, s in ipairs(spec) do list[i] = s end
    table.sort(list, function(a, b)
        if a.y ~= b.y then return a.y < b.y end
        return a.x < b.x
    end)
    local bottom = 0
    for _, s in ipairs(list) do bottom = math.max(bottom, s.y + s.h) end
    local last = nil
    for _, s in ipairs(list) do
        if s.t == "row" then
            if last then last.span = s.y - last.y - 1 end
            last = s
        end
    end
    if last then last.span = bottom - last.y - 1 end
    return list
end

-- (2026-10-21) the sidebar's leaves under the scene shown: the page's top, then every row with a panel that has data, by its
-- short name; a click scrolls the page to the row (scrollToRow) and the leaf of the row at the top of the page is lit (currentRow)
R.SHORT = { [10] = "Frame time", [16] = "Utilization", [22] = "Power", [27] = "Game thread", [60] = "Mods (Lua)",
    [33] = "Chunks, GC, flips", [38] = "Drive", [41] = "Flame graph", [44] = "Scheduler, memory, pacing", [50] = "Inputs",
    [56] = "Console", [58] = "Counters" }

local function hasData(p)
    if not p or p.why then return false end
    return #p.v > 0 or #p.rows > 0 or #p.s > 0 or p.hist ~= nil or p.flame ~= nil or p.logs ~= nil
end

-- (2026-10-21) what a page of panels is: its panels in order, its rows' short names, the rows folded, whether a panel has
-- data, the strip over the panels and a panel's drawing. The run page's is R.MODEL (below); the compare page
-- (pzopt_mainscreen_compare.lua) brings its own, and the layout, rows, sidebar sections and scrolling here serve both.
R.MODEL = {
    ordered = function() return R.ordered() end,
    short = function() return R.SHORT end,
    collapsed = R.collapsed,
    has = function(data, s) return hasData(data.panels[s.id]) end,
    panel = function(data, s) return data.panels[s.id] end,
    strip = function(o, W) return R.layoutStrip(o, W) end,
    item = function(o, it, x, y) return R.drawItem(o, it, x, y) end,
}

function R.model(o)
    return (o and o.model) or R.MODEL
end

function R.sections(data, M)
    M = M or R.MODEL
    local out = { { id = 0, label = "Overview" } }
    if not data or data.error then return out end
    local cur, any = nil, false
    local function close()
        if cur and any then table.insert(out, cur) end
    end
    local short = M.short()
    for _, s in ipairs(M.ordered()) do
        if s.t == "row" then
            close()
            cur, any = { id = s.id, label = short[s.id] or string.match(s.title, "^([^(:]+)") or s.title }, false
            cur.label = string.gsub(cur.label, "%s+$", "")
        elseif cur and M.has(data, s) then
            any = true
        end
    end
    close()
    return out
end

-- the row's top at the page's top, the row opened (0: the page's top); the leaf stays lit until the page is scrolled
function R.scrollToRow(o, id)
    if id ~= 0 then R.model(o).collapsed[id] = nil end
    R.layout(o)
    local y = 0
    if id ~= 0 then
        for _, it in ipairs(o.items or {}) do
            if it.spec.id == id then y = it.y break end
        end
    end
    o.yOff = math.max(0, math.min(y, math.max(0, (o.contentH or 0) - o.height)))
    o.pickedRow = id
end

-- the row at the page's top (the leaf picked last while the page has not been scrolled since)
function R.currentRow(o)
    if o.pickedRow then return o.pickedRow end
    local top, cur = (o.yOff or 0) + (o.hS or 0), 0
    for _, it in ipairs(o.items or {}) do
        if it.spec.t == "row" and it.y <= top then cur = it.spec.id end
    end
    return cur
end

-- the run's head, score, findings and PC over the panels (what the report said before it became the run page)
function R.layoutStrip(o, W)
    local out, y = {}, 0
    local d, dash = o.data, o.dash
    local rep = dash.rep or { scores = {}, rec = {}, sys = {} }
    local function line(segs, font)
        table.insert(out, { segs = segs, font = font, y = y })
        y = y + fh(font) + 2
    end
    local title = (rep.head and rep.head[3]) or tostring(dash.run or "")
    local sub = rep.head and rep.head[4] or ""
    local sel = d.sel and d.sel.title or ""
    line({ { clip(UIFont.Medium, title, math.floor(W * 0.6)), C_TEXT }, { (sub ~= "" and ("   " .. sub) or ""), C_GREY } }, UIFont.Medium)
    local segs = {}
    for _, cfg in ipairs({ "A", "B" }) do
        local sc = rep.scores and rep.scores[cfg]
        if sc then
            table.insert(segs, { (cfg == "A" and "Score " or "Stock score "), C_GREY })
            table.insert(segs, { tostring(sc.score), C_OK })
            if sc.ci and sc.ci ~= "-" then table.insert(segs, { " +- " .. tostring(sc.ci), C_DIM }) end
            table.insert(segs, { "      ", C_GREY })
        end
    end
    if sel ~= "" then
        table.insert(segs, { "Shown: ", C_GREY })
        table.insert(segs, { sel .. ", " .. (d.sel.cfg == "B" and "Stock game" or "PZ Optimization") .. ", pass " .. tostring(d.sel.pass), C_TEXT })
    end
    if #segs > 0 then line(segs, UIFont.Small) end
    if d.error then line({ { "This report's panels could not be read (" .. tostring(d.error) .. ").", { r = 1, g = 0.6, b = 0.5 } } }, UIFont.Small) end
    for _, r in ipairs(rep.rec or {}) do
        local col = r[2] == "warn" and { r = 1, g = 0.6, b = 0.5 } or (r[2] == "tip" and { r = 0.6, g = 0.8, b = 1 } or { r = 0.7, g = 0.95, b = 0.8 })
        local text = string.gsub(r[3] or "", "[<>]", "")
        for _, l in ipairs(PzoptBench.wrap(text, UIFont.Small, W)) do line({ { l, col } }, UIFont.Small) end
    end
    if rep.sys then
        for _, l in ipairs(PzoptBench.pcLines(rep, nil, W)) do line({ { l[1], { r = l[2], g = l[3], b = l[4] } } }, UIFont.Small) end
    end
    o.strip = out
    return y + 6
end

-- every panel's rectangle in content coordinates (o.items), the content's height
function R.layout(o)
    o.layoutW, o.layoutH = o.width, o.height
    local hS = fh(UIFont.Small)
    o.hS, o.hM = hS, fh(UIFont.Medium)
    o.barW = 8
    local W = o.width - o.barW
    o.mobile = W < hS * 48
    local unit = math.max(math.floor(hS * 1.7), math.floor(30 * W / 1900))
    local gap = math.max(6, math.floor(8 * unit / 30))
    o.unit, o.gap, o.scale = unit, gap, unit / 30
    o.titleH = hS + 8
    local M = R.model(o)
    local y0 = M.strip(o, W)
    local colW = (W - 23 * gap) / 24
    local items, shift, folded, y = {}, 0, false, y0
    for _, s in ipairs(M.ordered()) do
        if s.t == "row" then folded = M.collapsed[s.id] == true end
        if s.t == "row" or not folded then
            local it = { spec = s, p = M.panel(o.data, s), scroll = 0 }
            it.h = s.h * unit + (s.h - 1) * gap
            if o.mobile then
                it.x, it.y, it.w = 0, y, W
                y = y + it.h + gap
            else
                it.x = math.floor(s.x * (colW + gap))
                it.w = math.floor(s.w * colW + (s.w - 1) * gap)
                it.y = y0 + (s.y - shift) * (unit + gap)
            end
            table.insert(items, it)
        end
        if s.t == "row" and folded then shift = shift + (s.span or 0) end
    end
    local bottom = y0
    for _, it in ipairs(items) do bottom = math.max(bottom, it.y + it.h) end
    o.items = items
    o.contentH = bottom + gap
    o.yOff = math.max(0, math.min(o.yOff or 0, math.max(0, o.contentH - o.height)))
end

-- ------------------------------------------------------------------ drawing

-- the ops of a chart, built once per size: { 1, x1, y1, x2, y2, r, g, b, a } line, { 2, x, y, w, h, r, g, b, a } rect,
-- { 3, x1, y1, x2, y2, x3, y3, x4, y4, r, g, b, a } quad, { 4, text, x, y, r, g, b, a, font, align (0 left, 1 right, 2 centre) }
function R.runOps(o, ops, x, y)
    local jo = o.javaObject
    for i = 1, #ops do
        local op = ops[i]
        local k = op[1]
        if k == 1 then
            jo:DrawLine(nil, x + op[2], y + op[3], x + op[4], y + op[5], 1, op[6], op[7], op[8], op[9])
        elseif k == 2 then
            o:drawRect(x + op[2], y + op[3], op[4], op[5], op[9], op[6], op[7], op[8])
        elseif k == 3 then
            o:drawPolygon(nil, x + op[2], y + op[3], x + op[4], y + op[5], x + op[6], y + op[7], x + op[8], y + op[9], op[10], op[11], op[12], op[13])
        elseif k == 4 then
            if op[10] == 1 then
                o:drawTextRight(op[2], x + op[3], y + op[4], op[5], op[6], op[7], op[8], op[9])
            elseif op[10] == 2 then
                o:drawTextCentre(op[2], x + op[3], y + op[4], op[5], op[6], op[7], op[8], op[9])
            else
                o:drawText(op[2], x + op[3], y + op[4], op[5], op[6], op[7], op[8], op[9])
            end
        end
    end
end

function R.noData(o, it, x, y)
    local why = (it.p and it.p.why) or it.spec.why
    local cy = y + o.titleH + math.floor((it.h - o.titleH - o.hS * (why and 2 or 1)) / 2)
    o:drawTextCentre(clip(UIFont.Small, NO_DATA, it.w - 16), x + it.w / 2, cy, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
    if why then
        o:drawTextCentre(clip(UIFont.Small, "(" .. why .. ")", it.w - 16), x + it.w / 2, cy + o.hS + 2, C_DIM.r, C_DIM.g, C_DIM.b, 1, UIFont.Small)
    end
end

-- a value's text colour on a coloured background (Grafana: dark text on a bright colour)
local function onColour(c)
    if (c.r * 299 + c.g * 587 + c.b * 114) * 255 / 1000 > 180 then return { r = 0.13, g = 0.13, b = 0.15 } end
    return { r = 1, g = 1, b = 1 }
end

-- a stat panel: value_and_name per field, side by side when the panel is wider than tall (orientation auto)
function R.drawStat(o, it, x, y)
    local s, p = it.spec, it.p
    local fields = p and p.v or {}
    if #fields == 0 then return R.noData(o, it, x, y) end
    local pad = 6
    local bx, by = x + pad, y + o.titleH
    local bw, bh = it.w - 2 * pad, it.h - o.titleH - pad
    local n = #fields
    local horiz = bw > bh
    local cw, ch
    if horiz then cw, ch = (bw - (n - 1) * 4) / n, bh else cw, ch = bw, (bh - (n - 1) * 4) / n end
    local big = (s.vs or 26) >= 24 and UIFont.Large or UIFont.Medium
    for i, f in ipairs(fields) do
        local cx = horiz and math.floor(bx + (i - 1) * (cw + 4)) or bx
        local cy = horiz and by or math.floor(by + (i - 1) * (ch + 4))
        local num = tonumber(f[2])
        local empty = f[2] == ""
        local txt = empty and "No data" or (num and R.fmt(num, s.unit, s.dec) or f[2])
        local tc = empty and C_GREY or C_TEXT
        local nc = empty and C_DIM or C_TEXT
        if not empty then
            local c = R.rgb(R.thr(s.thr, num))
            if c and s.cm == "background" then
                o:drawRect(cx, cy, math.floor(cw), math.floor(ch), 0.9, c.r, c.g, c.b)
                tc = onColour(c)
                nc = tc
            elseif c then
                tc = c
            end
        end
        local font = empty and UIFont.Small or big
        if tw(font, txt) > cw - 8 then font = UIFont.Medium end
        if tw(font, txt) > cw - 8 then font = UIFont.Small end
        local hv = fh(font)
        local ty = cy + math.max(0, math.floor((ch - (o.hS + 2 + hv)) / 2))
        o:drawTextCentre(clip(UIFont.Small, f[1], cw - 6), cx + cw / 2, ty, nc.r, nc.g, nc.b, empty and 1 or 0.85, UIFont.Small)
        o:drawTextCentre(clip(font, txt, cw - 4), cx + cw / 2, ty + o.hS + 2, tc.r, tc.g, tc.b, 1, font)
    end
end

-- a table: its header, the rows (wheel-scrolled inside the panel), units per column, gauges, links
function R.drawTable(o, it, x, y)
    local s, p = it.spec, it.p
    if not p or not p.cols or #p.rows == 0 then return R.noData(o, it, x, y) end
    local ov = s.ov or {}
    local cols = {}
    for i, name in ipairs(p.cols) do
        local c = ov[name] or {}
        if not c.hidden then table.insert(cols, { i = i, name = name, ov = c }) end
    end
    local pad = 6
    local bx, bw = x + pad, it.w - 2 * pad
    local minW = o.hS * 3
    local fixed, nFree = 0, 0
    for _, c in ipairs(cols) do
        if c.ov.w then
            c.w = math.floor(c.ov.w * o.scale)
            fixed = fixed + c.w
        else
            nFree = nFree + 1
        end
    end
    if fixed > 0 and fixed + nFree * minW > bw then
        local k = math.max(0.3, (bw - nFree * minW) / fixed)
        fixed = 0
        for _, c in ipairs(cols) do
            if c.ov.w then
                c.w = math.floor(c.w * k)
                fixed = fixed + c.w
            end
        end
    end
    local free = math.max(0, bw - fixed)
    for _, c in ipairs(cols) do
        if not c.ov.w then c.w = math.floor(free / math.max(1, nFree)) end
    end
    local rh = o.hS + math.max(8, math.floor(12 * o.scale))
    local hy = y + o.titleH
    local cx = bx
    for _, c in ipairs(cols) do
        c.x = cx
        o:drawText(clip(UIFont.Small, c.name, c.w - 12), cx + 6, hy + math.floor((rh - o.hS) / 2), C_TEXT.r, C_TEXT.g, C_TEXT.b, 1, UIFont.Small)
        cx = cx + c.w
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
        for _, c in ipairs(cols) do
            local v = row[c.i] or ""
            local num = (not s.text) and v ~= "" and tonumber(v) or nil
            local unit = c.ov.unit or s.unit
            if c.ov.link and v ~= "" then
                local t = clip(UIFont.Small, v, c.w - 12)
                o:drawText(t, c.x + 6, ty, C_LINK.r, C_LINK.g, C_LINK.b, 1, UIFont.Small)
                o:drawRect(c.x + 6, ty + o.hS, tw(UIFont.Small, t), 1, 0.7, C_LINK.r, C_LINK.g, C_LINK.b)
                table.insert(o.links, { x = c.x, y = ry, w = c.w, h = rh, url = R.linkOf(o, c.name, v) })
            elseif c.ov.gauge and num then
                local txt = R.fmt(num, unit, c.ov.dec)
                local reserve = tw(UIFont.Small, R.fmt(c.ov.max or 100, unit, c.ov.dec)) + 12
                local gw = math.max(4, c.w - reserve - 12)
                local col = R.rgb(R.thr(c.ov.thr or R.GAUGE_THR, num)) or C_TEXT
                local frac = math.max(0, math.min(1, (num - (c.ov.min or 0)) / math.max(1e-9, (c.ov.max or 100) - (c.ov.min or 0))))
                o:drawRect(c.x + 6, ry + 4, gw, rh - 8, 0.25, 0.3, 0.3, 0.34)
                o:drawRect(c.x + 6, ry + 4, math.floor(gw * frac), rh - 8, 0.9, col.r, col.g, col.b)
                o:drawText(txt, c.x + 6 + gw + 6, ty, col.r, col.g, col.b, 1, UIFont.Small)
            elseif num then
                o:drawTextRight(clip(UIFont.Small, R.fmt(num, unit, c.ov.dec), c.w - 12), c.x + c.w - 6, ty, C_TEXT.r, C_TEXT.g, C_TEXT.b, 1, UIFont.Small)
            else
                o:drawText(clip(UIFont.Small, v, c.w - 12), c.x + 6, ty, C_TEXT.r, C_TEXT.g, C_TEXT.b, 1, UIFont.Small)
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

-- a link cell's target: the run folder (Run data's folder), the pass's frames file
function R.linkOf(o, col, v)
    local path = nil
    local facts = o.data.panels[7]
    for _, r in ipairs(facts and facts.rows or {}) do
        if r[1] == "path" then path = r[2] end
    end
    if not path then return nil end
    if col == "frames" then return "file://" .. path .. "/" .. v end
    return "file://" .. path
end

-- the decimals that write every multiple of a tick step exactly (0.25 -> 2, 2.5 -> 1, 50 -> 0)
function R.stepDecimals(step)
    local dec = 0
    while dec < 6 and math.abs(step * 10 ^ dec - math.floor(step * 10 ^ dec + 0.5)) > 1e-6 * step * 10 ^ dec do dec = dec + 1 end
    return dec
end

-- the y axis of a chart: its range, ticks and a value -> pixel function over [py, py + ph]. Ticks are values of the
-- series (A.y takes them); a MiB axis reaching 1 GiB is written in GiB throughout (A.div), not "768 MiB" under "1.22 GiB".
-- Log axes step 1-2-5 (0.5, 1, 2, 5, 10 ms ...; decades only when those do not fit), linear ones a nice step written
-- with the step's decimals on every tick (0.00, 0.25, 0.50, not 0, 0.3, 0.5).
function R.axis(s, lo, hi, posMin, unit, py, ph, hS)
    if lo == nil then return nil end
    local A = { unit = unit, ticks = {}, div = 1 }
    if unit == "mbytes" and math.max(math.abs(lo), math.abs(hi)) >= 1024 then A.unit, A.div = "gbytes", 1024 end
    local div = A.div
    lo, hi = lo / div, hi / div
    if s.log then
        if not posMin then return nil end
        posMin = posMin / div
        hi = math.max(hi, posMin)
        -- the range: the 1-2-5 values just around the data
        local e0 = math.floor(math.log(posMin) / math.log(10) + 1e-9)
        local e1 = math.ceil(math.log(hi) / math.log(10) - 1e-9)
        local all = {}
        for e = e0, e1 do
            for _, m in ipairs({ 1, 2, 5 }) do table.insert(all, { m * 10 ^ e, m == 1 }) end
        end
        local a, b = all[1][1], all[#all][1]
        for _, v in ipairs(all) do
            if v[1] <= posMin * (1 + 1e-9) then a = v[1] end
        end
        for i = #all, 1, -1 do
            if all[i][1] >= hi * (1 - 1e-9) then b = all[i][1] end
        end
        if b <= a then b = a * 2 end
        local la, lb = math.log(a), math.log(b)
        local function px(v) return (math.log(v) - la) / (lb - la) * ph end
        local gap = hS * 1.3 -- (labels that fit one above the other)
        local function fits(t)
            for i = 2, #t do
                if px(t[i]) - px(t[i - 1]) < gap then return false end
            end
            return true
        end
        local ticks, decades = {}, {}
        for _, v in ipairs(all) do
            if v[1] >= a * (1 - 1e-9) and v[1] <= b * (1 + 1e-9) then
                table.insert(ticks, v[1])
                if v[2] then table.insert(decades, v[1]) end
            end
        end
        if not fits(ticks) then
            -- too dense: the decades, the range's ends where they clear the nearest decade; then every n-th decade
            ticks = {}
            if #decades == 0 or px(decades[1]) >= gap then table.insert(ticks, a) end
            for _, v in ipairs(decades) do
                if #ticks == 0 or v > ticks[#ticks] * (1 + 1e-9) then table.insert(ticks, v) end
            end
            if #decades == 0 or ph - px(decades[#decades]) >= gap then
                if b > ticks[#ticks] * (1 + 1e-9) then table.insert(ticks, b) end
            end
            local every = 1
            while not fits(ticks) and every < #decades do
                every = every + 1
                ticks = {}
                for i = 1, #decades, every do table.insert(ticks, decades[i]) end
            end
        end
        for _, v in ipairs(ticks) do table.insert(A.ticks, v * div) end
        A.y = function(v)
            if not v or v <= 0 then return nil end
            local f = (math.log(v / div) - la) / (lb - la)
            return py + ph - math.max(0, math.min(1, f)) * ph
        end
        return A
    end
    local mn = s.min and s.min / div or (s.softMin and math.min(0, lo)) or lo
    local mx = s.max and s.max / div or hi
    if mx <= mn then mx = mn + 1 end
    local step = R.nice((mx - mn) / math.max(2, math.floor(ph / (hS * 2.2))))
    if not s.max then mx = math.ceil(mx / step - 1e-9) * step end
    if not s.min and not s.softMin then mn = math.floor(mn / step + 1e-9) * step end
    if mx <= mn then mx = mn + step end
    local v = math.ceil(mn / step - 1e-9) * step
    while v <= mx + step * 1e-6 do
        table.insert(A.ticks, math.floor(v / step + 0.5) * step * div) -- (no float drift: 0, not -1e-17)
        v = v + step
    end
    A.step = step
    A.y = function(x)
        if not x then return nil end
        local f = (x / div - mn) / (mx - mn)
        return py + ph - math.max(0, math.min(1, f)) * ph
    end
    A.base = A.y(math.max(mn, math.min(mx, 0)) * div)
    return A
end

function R.tickText(A, v)
    local div = A.div or 1
    if A.step then return R.fmt(v / div, A.unit, R.stepDecimals(A.step)) end
    return R.fmt(v / div, A.unit)
end

-- route seconds on a time axis: as R.clock, before the route's start with a minus (-00:00:05, not 23:59:55)
function R.tickClock(t)
    if t < -0.5 then return "-" .. R.clock(-t) end
    return R.clock(t)
end

-- a series' points as pixel columns { x, top, bot, first, last, brk } (several points in one pixel column merge; a gap
-- (nan) or more than 3 s between points (insertNulls 3000) breaks the line)
function R.columns(t, v, X, Y)
    local cols, cur, prevT, brk = {}, nil, nil, true
    for i = 1, #t do
        local ti, vi = t[i], v[i]
        local py = (ti and vi) and Y(vi) or nil
        if not py then
            brk = true
        else
            if prevT and ti - prevT > 3 then brk = true end
            local px = math.floor(X(ti))
            if cur and cur.x == px and not brk then
                if py < cur.top then cur.top = py end
                if py > cur.bot then cur.bot = py end
                cur.last = py
            else
                cur = { x = px, top = py, bot = py, first = py, last = py, brk = brk }
                table.insert(cols, cur)
            end
            brk = false
            prevT = ti
        end
    end
    return cols
end

-- one series of a chart as ops: the fill (into ops), the line or points (returned, drawn after every fill)
function R.seriesOps(o, s, sr, A, X, ops, fillA, py, ph)
    local e, c = sr.e, sr.col
    if e.lo then
        -- every frame (min / max per column): one vertical line per pixel column, stretched to meet its neighbour
        local cols, cur, brk = {}, nil, true
        for i = 1, #e.t do
            local a, b = A.y(e.hi[i]), A.y(e.lo[i])
            if not (e.t[i] and a and b) then
                brk = true
            else
                local xx = math.floor(X(e.t[i]))
                if cur and cur.x == xx and not brk then
                    if a < cur.top then cur.top = a end
                    if b > cur.bot then cur.bot = b end
                else
                    cur = { x = xx, top = a, bot = b, brk = brk }
                    table.insert(cols, cur)
                end
                brk = false
            end
        end
        if fillA > 0 then
            local base = A.base or (py + ph)
            local every = math.max(1, math.ceil(#cols / 200))
            local prev = nil
            for i = 1, #cols, every do
                local cc = cols[i]
                if prev and not cc.brk then
                    table.insert(ops, { 3, prev.x, prev.top, cc.x, cc.top, cc.x, base, prev.x, base, c.r, c.g, c.b, fillA })
                end
                prev = cc
            end
        end
        local line = {}
        for i, cc in ipairs(cols) do
            local top, bot = cc.top, cc.bot
            local pr = cols[i - 1]
            if pr and not cc.brk then
                if bot < pr.top then bot = pr.top end
                if top > pr.bot then top = pr.bot end
            end
            top, bot = math.floor(top), math.floor(bot)
            if bot <= top then bot = top + 1 end
            table.insert(line, { 1, cc.x, top, cc.x, bot, c.r, c.g, c.b, 1 })
        end
        return line
    else
        local vals = (s.stack and sr.top) or e.v
        local cols = R.columns(e.t, vals, X, A.y)
        local line = {}
        if s.draw == "points" then
            local r = math.max(1, math.floor((s.ps or 3) * o.scale / 2))
            for _, cc in ipairs(cols) do
                table.insert(line, { 2, cc.x - r, math.floor(cc.top) - r, 2 * r + 1, math.floor(cc.bot - cc.top) + 2 * r + 1, c.r, c.g, c.b, 1 })
            end
        else
            if fillA > 0 then
                local baseCols = s.stack and sr.base and R.columns(e.t, sr.base, X, A.y) or nil
                for i = 2, #cols do
                    local a, b = cols[i - 1], cols[i]
                    if not b.brk then
                        local ya, yb = A.base or (py + ph), A.base or (py + ph)
                        if baseCols and baseCols[i - 1] and baseCols[i] then ya, yb = baseCols[i - 1].last, baseCols[i].first end
                        table.insert(ops, { 3, a.x, a.last, b.x, b.first, b.x, yb, a.x, ya, c.r, c.g, c.b, fillA })
                    end
                end
            end
            for i, cc in ipairs(cols) do
                local pr = cols[i - 1]
                if pr and not cc.brk then
                    table.insert(line, { 1, pr.x, math.floor(pr.last), cc.x, math.floor(cc.first), c.r, c.g, c.b, 1 })
                end
                if cc.bot - cc.top >= 1 then
                    table.insert(line, { 1, cc.x, math.floor(cc.top), cc.x, math.floor(cc.bot), c.r, c.g, c.b, 1 })
                elseif cc.brk and not (cols[i + 1] and not cols[i + 1].brk) then -- (a point alone)
                    table.insert(line, { 2, cc.x, math.floor(cc.top), 1, 1, c.r, c.g, c.b, 1 })
                end
            end
        end
        return line
    end
end

-- a time series panel as ops (see runOps): axes, grid, threshold lines, the series (line / points, fill, stacking), legend
function R.tsOps(o, it)
    local s, p = it.spec, it.p
    local ops = {}
    local W, H, hS = it.w, it.h, o.hS
    local ov = s.ov or {}
    local ser = {}
    for i, e in ipairs(p.s) do
        local c = ov[e.name] or {}
        local idx = i
        for k, nm in ipairs(s.order or {}) do
            if nm == e.name then idx = k end
        end
        local col = R.rgb(c.color or e.col or R.PALETTE[((idx - 1) % #R.PALETTE) + 1]) or C_TEXT -- (e.col: the compare page's A / B)
        table.insert(ser, { e = e, col = col, right = c.right == true, unit = c.unit })
    end
    local pad = 8
    -- the legend: a list under the chart, or a column on its right
    local legH, legW = 0, 0
    if not s.noLegend then
        if s.lr then
            for _, sr in ipairs(ser) do legW = math.max(legW, 22 + tw(UIFont.Small, sr.e.name)) end
            legW = math.min(legW + 8, math.floor(W * 0.4))
        else
            local lx, lines = 0, 1
            for _, sr in ipairs(ser) do
                local iw = 22 + tw(UIFont.Small, sr.e.name) + 14
                if lx > 0 and lx + iw > W - 2 * pad then
                    lines = lines + 1
                    lx = 0
                end
                sr.lx, sr.ll = lx, lines
                lx = lx + iw
            end
            legH = lines * (hS + 2) + 4
        end
    end
    local py = o.titleH + math.floor(hS / 2)
    local ph = H - py - (hS + 6) - legH - pad
    if ph < hS * 2 then ph = hS * 2 end
    -- the ranges (stacked series: their running sums)
    local sums = {}
    local function range(right)
        local lo, hi, pos = nil, nil, nil
        local function ext(v)
            if not v then return end
            if not lo or v < lo then lo = v end
            if not hi or v > hi then hi = v end
            if v > 0 and (not pos or v < pos) then pos = v end
        end
        for _, sr in ipairs(ser) do
            if sr.right == right then
                local e = sr.e
                if e.lo then
                    for i = 1, #e.lo do
                        ext(e.lo[i])
                        ext(e.hi[i])
                    end
                elseif s.stack and not right then
                    sr.base, sr.top = {}, {}
                    for i = 1, #e.v do
                        local b = sums[i] or 0
                        local v = e.v[i] or 0
                        sr.base[i], sr.top[i] = b, b + v
                        sums[i] = b + v
                        ext(b + v)
                        ext(b)
                    end
                else
                    for i = 1, #e.v do ext(e.v[i]) end
                end
            end
        end
        return lo, hi, pos
    end
    local lo, hi, pos = range(false)
    local unitL = s.unit
    local AL = R.axis(s, lo, hi, pos, unitL, py, ph, hS)
    local unitR = nil
    for _, sr in ipairs(ser) do
        if sr.right then unitR = sr.unit or s.unit end
    end
    local rlo, rhi, rpos = range(true)
    local AR = unitR and R.axis({ softMin = s.softMin, log = s.log }, rlo, rhi, rpos, unitR, py, ph, hS) or nil
    local function axisW(A)
        if not A then return 0 end
        local w = 0
        for _, v in ipairs(A.ticks) do w = math.max(w, tw(UIFont.Small, R.tickText(A, v))) end
        return w + 8
    end
    local px = pad + axisW(AL)
    local pw = W - px - pad - axisW(AR) - (legW > 0 and (legW + 8) or 0)
    if pw < 20 then pw = 20 end
    -- x: the route's seconds, the panel's first to last point (R.xRange)
    local x0, x1 = R.xRange(o, p)
    local function X(t) return px + (t - x0) / (x1 - x0) * pw end
    local gc = 0.07
    -- grid and ticks
    local lw = tw(UIFont.Small, x0 < -0.5 and "-00:00:00" or "00:00:00") + 16
    local step = 3600
    for _, st in ipairs({ 1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 1800, 3600 }) do
        if (x1 - x0) / st * lw <= pw then
            step = st
            break
        end
    end
    local t = math.ceil(x0 / step - 1e-9) * step
    local half = math.floor(tw(UIFont.Small, x0 < -0.5 and "-00:00:00" or "00:00:00") / 2) + 2 -- (the first / last label inside the panel)
    local lastR = -1e9
    while t <= x1 + 1e-6 do
        local xx = math.floor(X(t))
        table.insert(ops, { 1, xx, py, xx, py + ph, 1, 1, 1, gc })
        local cx = math.max(half, math.min(W - half, xx))
        if cx - half >= lastR then -- (a label pushed inside the panel would overlap its neighbour: left out)
            table.insert(ops, { 4, R.tickClock(t), cx, py + ph + 3, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small, 2 })
            lastR = cx + half
        end
        t = t + step
    end
    if AL then
        for _, v in ipairs(AL.ticks) do
            local yy = math.floor(AL.y(v))
            table.insert(ops, { 1, px, yy, px + pw, yy, 1, 1, 1, gc })
            table.insert(ops, { 4, R.tickText(AL, v), px - 4, yy - math.floor(hS / 2), C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small, 1 })
        end
    end
    if AR then
        for _, v in ipairs(AR.ticks) do
            local yy = math.floor(AR.y(v))
            table.insert(ops, { 4, R.tickText(AR, v), px + pw + 4, yy - math.floor(hS / 2), C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small, 0 })
        end
    end
    -- the threshold lines (dashed; the base step is not drawn)
    if s.dashed and s.thr and AL then
        for i = 2, #s.thr do
            local c = R.rgb(s.thr[i][1])
            local v = s.thr[i][2]
            local yy = c and AL.y(v) or nil
            if yy and yy > py and yy < py + ph then
                yy = math.floor(yy)
                local xx = px
                while xx < px + pw do
                    table.insert(ops, { 1, xx, yy, math.min(xx + 6, px + pw), yy, c.r, c.g, c.b, 0.8 })
                    xx = xx + 11
                end
            end
        end
    end
    -- the series: fills first, then lines / points
    local fillA = (s.fill or 0) / 100
    local drawn = {}
    for k = #ser, 1, -1 do -- (stacked: the top series first under the lines)
        local sr = ser[k]
        local A = sr.right and AR or AL
        if A then table.insert(drawn, R.seriesOps(o, s, sr, A, X, ops, fillA, py, ph)) end
    end
    for k = #drawn, 1, -1 do
        for _, op in ipairs(drawn[k]) do table.insert(ops, op) end
    end
    -- the legend
    if not s.noLegend then
        for i, sr in ipairs(ser) do
            local c = sr.col
            local lx, ly
            if s.lr then
                lx, ly = W - pad - legW + 4, py + (i - 1) * (hS + 2)
                if ly + hS > H - pad then break end
            else
                lx, ly = pad + sr.lx, H - pad - legH + 4 + (sr.ll - 1) * (hS + 2)
            end
            table.insert(ops, { 2, lx, ly + math.floor(hS / 2) - 1, 14, 3, c.r, c.g, c.b, 1 })
            local name = s.lr and clip(UIFont.Small, sr.e.name, legW - 26) or sr.e.name
            table.insert(ops, { 4, name, lx + 20, ly, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small, 0 })
        end
    end
    return ops
end

-- the frame time distribution: frames per bucket (Grafana's histogram, bucket 0.5 ms, bars at 70 %)
function R.histOps(o, it)
    local s, h = it.spec, it.p.hist
    local ops, W, H, hS = {}, it.w, it.h, o.hS
    local pad = 8
    local n = #h.counts
    local top = 0
    for i = 1, n do top = math.max(top, h.counts[i] or 0) end
    local py = o.titleH + math.floor(hS / 2)
    local ph = H - py - (hS + 6) - pad
    local A = R.axis({ softMin = 0 }, 0, math.max(1, top), nil, "none", py, ph, hS)
    local axisW = 0
    for _, v in ipairs(A.ticks) do axisW = math.max(axisW, tw(UIFont.Small, R.tickText(A, v))) end
    local px = pad + axisW + 8
    local pw = W - px - pad
    local x0, x1 = h.lo * h.bucket, (h.lo + n) * h.bucket
    local function X(v) return px + (v - x0) / (x1 - x0) * pw end
    for _, v in ipairs(A.ticks) do
        local yy = math.floor(A.y(v))
        table.insert(ops, { 1, px, yy, px + pw, yy, 1, 1, 1, 0.07 })
        table.insert(ops, { 4, R.tickText(A, v), px - 4, yy - math.floor(hS / 2), C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small, 1 })
    end
    local step = R.nice((x1 - x0) / math.max(2, math.floor(pw / (tw(UIFont.Small, "000.0 ms") + 16))))
    local v = math.ceil(x0 / step - 1e-9) * step
    local XA = { unit = s.unit, step = step }
    while v <= x1 + 1e-9 do
        local xx = math.floor(X(v))
        table.insert(ops, { 1, xx, py, xx, py + ph, 1, 1, 1, 0.07 })
        table.insert(ops, { 4, R.tickText(XA, v), xx, py + ph + 3, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small, 2 })
        v = v + step
    end
    local c = R.rgb(R.PALETTE[1])
    local a = (s.fill or 70) / 100
    local base = math.floor(A.y(0))
    -- one bar per bucket, or per pixel column when the buckets are narrower than a pixel (the highest count)
    local colTop, colX = nil, nil
    local function flush(x1p, x2p, cnt)
        if cnt and cnt > 0 then
            local yy = math.floor(A.y(cnt))
            local w = math.max(1, x2p - x1p - (x2p - x1p > 3 and 1 or 0))
            table.insert(ops, { 2, x1p, yy, w, math.max(1, base - yy), c.r, c.g, c.b, a })
            table.insert(ops, { 1, x1p, yy, x1p + w, yy, c.r, c.g, c.b, 1 })
        end
    end
    for i = 1, n do
        local cnt = h.counts[i] or 0
        local xa = math.floor(X(x0 + (i - 1) * h.bucket))
        local xb = math.floor(X(x0 + i * h.bucket))
        if xb - xa >= 1 then
            if colX then flush(colX, colX + 1, colTop) colX, colTop = nil, nil end
            flush(xa, xb, cnt)
        else
            if colX ~= xa then
                if colX then flush(colX, colX + 1, colTop) end
                colX, colTop = xa, cnt
            elseif cnt > colTop then
                colTop = cnt
            end
        end
    end
    if colX then flush(colX, colX + 1, colTop) end
    return ops
end

-- a chart's time axis: the first to the last point of its own series, so every chart fills its width (Grafana's fixed
-- window, 30 s before the route to 120 s after its start, left two thirds of a 40 s pass empty; one range for the page
-- left the panels whose data starts at the route empty on the left), else the route
function R.xRange(o, p)
    if not p.xr then
        local lo, hi = nil, nil
        for _, e in ipairs(p.s or {}) do
            local v = e.lo or e.v
            for i, t in ipairs(e.t or {}) do
                if t and v and v[i] then -- (a band's empty columns hold no point)
                    if not lo or t < lo then lo = t end
                    if not hi or t > hi then hi = t end
                end
            end
        end
        if not lo then lo, hi = 0, o.data.route or 0 end
        if hi <= lo then hi = lo + 1 end
        p.xr = { lo, hi }
    end
    return p.xr[1], p.xr[2]
end

-- the time axis' labels and grid lines (as in tsOps) into ops
function R.timeTicks(ops, X, x0, x1, px, pw, py, ph, W)
    local lw = tw(UIFont.Small, x0 < -0.5 and "-00:00:00" or "00:00:00") + 16
    local step = 3600
    for _, st in ipairs({ 1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 1800, 3600 }) do
        if (x1 - x0) / st * lw <= pw then
            step = st
            break
        end
    end
    local half = math.floor(tw(UIFont.Small, x0 < -0.5 and "-00:00:00" or "00:00:00") / 2) + 2
    local t = math.ceil(x0 / step - 1e-9) * step
    local lastR = -1e9
    while t <= x1 + 1e-6 do
        local xx = math.floor(X(t))
        table.insert(ops, { 1, xx, py, xx, py + ph, 1, 1, 1, 0.07 })
        local cx = math.max(half, math.min(W - half, xx))
        if cx - half >= lastR then -- (as in tsOps: no label over its neighbour)
            table.insert(ops, { 4, R.tickClock(t), cx, py + ph + 3, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small, 2 })
            lastR = cx + half
        end
        t = t + step
    end
end

-- the flame graph (Grafana's flamegraph panel, top-down): nodes { level, value, self, label } depth first, a child's
-- bar starts where its previous sibling ended; width = its samples' share, colour from yellow (few) to red (many)
function R.flameOps(o, it)
    local rows = it.p.flame
    local ops, W, H, hS = {}, it.w, it.h, o.hS
    local pad = 8
    local total = rows[1][2]
    if not total or total <= 0 then return ops end
    local rh = hS + 4
    local top = o.titleH + 2
    local levels = math.max(1, math.floor((H - top - pad) / (rh + 1)))
    local pw = W - 2 * pad
    local start, used = {}, {}
    local cut = false
    local lo, hi = R.rgb("#EAB839"), R.rgb("#E24D42")
    for _, r in ipairs(rows) do
        local L, v, label = r[1], r[2], r[4]
        local x = 0
        if L > 0 then
            x = (start[L - 1] or 0) + (used[L - 1] or 0)
            used[L - 1] = (used[L - 1] or 0) + v
        end
        start[L], used[L] = x, 0
        if L >= levels then
            cut = true
        else
            local bx = pad + x / total * pw
            local bw = v / total * pw
            if bw >= 1 then
                local f = math.min(1, math.sqrt(v / total))
                local c = { r = lo.r + (hi.r - lo.r) * f, g = lo.g + (hi.g - lo.g) * f, b = lo.b + (hi.b - lo.b) * f }
                local by = top + L * (rh + 1)
                table.insert(ops, { 2, math.floor(bx), by, math.max(1, math.floor(bw) - 1), rh, c.r, c.g, c.b, 0.9 })
                if bw > tw(UIFont.Small, "...") + 10 then
                    local txt = L == 0 and (label .. " (" .. tostring(total) .. " samples)") or (label .. " " .. R.fixed(100 * v / total, 1) .. "%")
                    table.insert(ops, { 4, clip(UIFont.Small, txt, bw - 6), math.floor(bx) + 3, by + 2, 0.1, 0.1, 0.12, 1, UIFont.Small, 0 })
                end
            end
        end
    end
    if cut then
        table.insert(ops, { 4, "(deeper levels: open the run folder's stacks file in harness/flamegraph.py)", W - pad, H - pad - hS, C_DIM.r, C_DIM.g, C_DIM.b, 1, UIFont.Small, 1 })
    end
    return ops
end

-- the state timeline (Grafana's state-timeline: one lane per series, a value holds until the next one; coloured by the
-- thresholds, "none" left empty; row height 0.8)
function R.stateOps(o, it)
    local s, p = it.spec, it.p
    local ops, W, H, hS = {}, it.w, it.h, o.hS
    local pad = 8
    local labW = 0
    for _, e in ipairs(p.s) do labW = math.max(labW, tw(UIFont.Small, e.name)) end
    labW = math.min(labW + 12, math.floor(W * 0.35))
    local py = o.titleH + 2
    local ph = H - py - (hS + 6) - pad
    local px, pw = pad + labW, W - 2 * pad - labW
    local x0, x1 = R.xRange(o, p)
    local function X(t) return px + (t - x0) / (x1 - x0) * pw end
    R.timeTicks(ops, X, x0, x1, px, pw, py, ph, W)
    local n = #p.s
    local lane = ph / math.max(1, n)
    local fillA = (s.fill or 80) / 100
    for i, e in ipairs(p.s) do
        local ly = py + (i - 1) * lane
        local bh = math.max(1, math.floor(lane * 0.8))
        local by = math.floor(ly + (lane - bh) / 2)
        if lane >= hS * 0.8 then
            table.insert(ops, { 4, clip(UIFont.Small, e.name, labW - 8), pad, math.floor(ly + (lane - hS) / 2), C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small, 0 })
        end
        for k = 1, #e.t do
            local t0, v = e.t[k], e.v[k]
            local t1 = e.t[k + 1] or x1
            local c = (t0 and v) and R.rgb(R.thr(s.thr, v)) or nil
            if c and t1 and t1 > t0 then
                local a, b = math.floor(X(t0)), math.floor(X(t1))
                table.insert(ops, { 2, a, by, math.max(1, b - a), bh, c.r, c.g, c.b, fillA })
            end
        end
    end
    return ops
end

-- the console (Grafana's logs panel: time, a level colour, the line; oldest first, the wheel scrolls)
function R.drawLogs(o, it, x, y)
    local logs = it.p.logs
    local pad = 6
    local rh = o.hS + 2
    local top = y + o.titleH
    local vis = math.max(1, math.floor((it.h - o.titleH - pad) / rh))
    it.maxScroll = math.max(0, #logs - vis)
    it.scroll = math.max(0, math.min(it.scroll or 0, it.maxScroll))
    local timeW = tw(UIFont.Small, "00:00:00.000") + 12
    local LEVEL = { error = R.rgb("#F2495C"), warning = R.rgb("#FADE2A"), info = R.rgb("#73BF69") }
    for r = 1, math.min(vis, #logs - it.scroll) do
        local l = logs[it.scroll + r]
        local ry = top + (r - 1) * rh
        local c = LEVEL[l[2]] or LEVEL.info
        o:drawRect(x + pad, ry + 1, 3, rh - 2, 1, c.r, c.g, c.b)
        local t = l[1]
        local ms = math.floor((t - math.floor(t)) * 1000 + 0.5)
        if ms >= 1000 then ms = 999 end
        o:drawText(R.clock(math.floor(t)) .. string.format(".%03d", ms), x + pad + 8, ry, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
        o:drawText(clip(UIFont.Small, l[3], it.w - timeW - 2 * pad - 12), x + pad + 8 + timeW, ry, C_TEXT.r, C_TEXT.g, C_TEXT.b, 1, UIFont.Small)
    end
    if it.maxScroll > 0 then
        local h = it.h - o.titleH - pad
        local barH = math.max(10, math.floor(h * vis / #logs))
        local barY = top + math.floor((h - barH) * it.scroll / it.maxScroll)
        o:drawRect(x + it.w - 4, barY, 2, barH, 0.6, 1, 1, 1)
    end
end

function R.drawRow(o, it, x, y)
    local s = it.spec
    local M = R.model(o)
    local folded = M.collapsed[s.id] == true
    local font = (o.hM <= it.h) and UIFont.Medium or UIFont.Small
    local h = fh(font)
    local ty = y + math.floor((it.h - h) / 2)
    local chev = folded and ">" or "v"
    o:drawText(chev, x + 2, ty, C_GREY.r, C_GREY.g, C_GREY.b, 1, font)
    local cx = x + 2 + tw(font, "v") + 10
    local extra = ""
    if folded then
        local n = 0
        local on = false
        for _, sp in ipairs(M.ordered()) do
            if sp.t == "row" then on = sp == s elseif on then n = n + 1 end
        end
        extra = "  (" .. n .. " panel" .. (n == 1 and "" or "s") .. ")"
    end
    local title = clip(font, s.title, it.w - (cx - x) - tw(UIFont.Small, extra) - 8)
    o:drawText(title, cx, ty, C_TEXT.r, C_TEXT.g, C_TEXT.b, 1, font)
    if extra ~= "" then o:drawText(extra, cx + tw(font, title), ty + h - o.hS, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small) end
    table.insert(o.rowHits, { x = x, y = y, w = it.w, h = it.h, id = s.id })
end

function R.drawItem(o, it, x, y)
    local s = it.spec
    if s.t == "row" then return R.drawRow(o, it, x, y) end
    o:drawRect(x, y, it.w, it.h, C_CARD.a, C_CARD.r, C_CARD.g, C_CARD.b)
    o:drawRectBorder(x, y, it.w, it.h, 0.6, C_BORDER.r, C_BORDER.g, C_BORDER.b)
    o:drawText(clip(UIFont.Small, s.title, it.w - 16), x + 8, y + 4, C_TEXT.r, C_TEXT.g, C_TEXT.b, 1, UIFont.Small)
    if s.t == "stat" then
        R.drawStat(o, it, x, y)
    elseif s.t == "table" then
        R.drawTable(o, it, x, y)
    elseif s.t == "ts" or s.t == "hist" then
        local p = it.p
        local has = p and ((s.t == "ts" and #p.s > 0) or (s.t == "hist" and p.hist and #p.hist.counts > 0))
        if not has then return R.noData(o, it, x, y) end
        if it.opsW ~= it.w or it.opsH ~= it.h then
            it.ops = s.t == "ts" and R.tsOps(o, it) or R.histOps(o, it)
            it.opsW, it.opsH = it.w, it.h
        end
        R.runOps(o, it.ops, x, y)
    elseif s.t == "flame" or s.t == "state" then
        local p = it.p
        local has = p and ((s.t == "flame" and p.flame and #p.flame > 0) or (s.t == "state" and #p.s > 0))
        if not has then return R.noData(o, it, x, y) end
        if it.opsW ~= it.w or it.opsH ~= it.h then
            it.ops = s.t == "flame" and R.flameOps(o, it) or R.stateOps(o, it)
            it.opsW, it.opsH = it.w, it.h
        end
        R.runOps(o, it.ops, x, y)
    elseif s.t == "logs" then
        if not (it.p and it.p.logs and #it.p.logs > 0) then return R.noData(o, it, x, y) end
        R.drawLogs(o, it, x, y)
    else
        R.noData(o, it, x, y)
    end
end

function R.draw(o)
    o.links, o.rowHits = {}, {}
    local top, H = o.yOff or 0, o.height
    for _, l in ipairs(o.strip or {}) do
        local y = l.y - top
        if y + fh(l.font) > 0 and y < H then
            local x = 0
            for _, sg in ipairs(l.segs) do
                if sg[1] ~= "" then
                    o:drawText(sg[1], x, y, sg[2].r, sg[2].g, sg[2].b, 1, l.font)
                    x = x + tw(l.font, sg[1])
                end
            end
        end
    end
    local item = R.model(o).item
    for _, it in ipairs(o.items or {}) do
        local y = it.y - top
        if y + it.h > 0 and y < H then item(o, it, it.x, y) end
    end
    local maxScroll = math.max(0, (o.contentH or 0) - H)
    if maxScroll > 0 then -- where the page is
        local barH = math.max(16, math.floor(H * H / o.contentH))
        local barY = math.floor((H - barH) * top / maxScroll)
        o:drawRect(o.width - 4, barY, 3, barH, 0.55, 1, 1, 1)
    end
end

-- ------------------------------------------------------------------ the panel (the menu's detailText while it shows)

PzoptReportPanel = ISPanel:derive("PzoptReportPanel")

function PzoptReportPanel:new(x, y, w, h, dash)
    local o = ISPanel:new(x, y, w, h)
    setmetatable(o, self)
    self.__index = self
    o.dash, o.data = dash, dash.data or { vars = {}, panels = {} }
    o.background = false
    o.yOff = 0
    o.name = "Report panels"
    return o
end

function PzoptReportPanel:initialise()
    ISPanel.initialise(self)
    R.layout(self)
end

-- the scroll as the menu reads it (ISRichTextPanel's convention: 0 at the top, negative further down)
function PzoptReportPanel:getYScroll()
    return -(self.yOff or 0)
end

function PzoptReportPanel:setYScroll(v)
    local y = math.max(0, math.min(math.max(0, (self.contentH or 0) - self.height), -(v or 0)))
    if y ~= self.yOff then self.pickedRow = nil end
    self.yOff = y
end

function PzoptReportPanel:getScrollHeight()
    return self.contentH or self.height
end

function PzoptReportPanel:prerender()
    if self.layoutW ~= self.width or self.layoutH ~= self.height then R.layout(self) end
    self:setStencilRect(0, 0, self.width, self.height)
    R.draw(self)
    self:clearStencilRect()
end

function PzoptReportPanel:render()
end

-- the item under a point of the panel
function PzoptReportPanel:itemAt(x, y)
    local cy = y + (self.yOff or 0)
    for _, it in ipairs(self.items or {}) do
        if x >= it.x and x < it.x + it.w and cy >= it.y and cy < it.y + it.h then return it end
    end
    return nil
end

-- the wheel: a table (a log) with more rows than it shows scrolls its rows while it can, else the page scrolls
function PzoptReportPanel:onMouseWheel(del)
    local it = self:itemAt(self:getMouseX(), self:getMouseY())
    if it and (it.maxScroll or 0) > 0 then
        local s = math.max(0, math.min(it.maxScroll, (it.scroll or 0) + (del > 0 and 2 or -2)))
        if s ~= it.scroll then
            it.scroll = s
            return true
        end
    end
    self:setYScroll(self:getYScroll() - del * self.hS * 3)
    return true
end

function PzoptReportPanel:onMouseDown(x, y)
    self.pressed = true
    return true
end

function PzoptReportPanel:onMouseUp(x, y)
    if not self.pressed then return true end
    self.pressed = false
    for _, h in ipairs(self.rowHits or {}) do
        if x >= h.x and x < h.x + h.w and y >= h.y and y < h.y + h.h then
            local folded = R.model(self).collapsed
            folded[h.id] = not folded[h.id] or nil
            getSoundManager():playUISound("UIActivateMainMenuItem")
            PzoptLogInfo("[pzopt] menu: report row " .. tostring(h.id) .. (folded[h.id] and " folded" or " opened"))
            R.layout(self)
            return true
        end
    end
    for _, l in ipairs(self.links or {}) do
        if l.url and x >= l.x and x < l.x + l.w and y >= l.y and y < l.y + l.h then
            getSoundManager():playUISound("UIActivateMainMenuItem")
            pcall(function() openUrl(l.url) end)
            return true
        end
    end
    return true
end

function PzoptReportPanel:onMouseUpOutside(x, y)
    self.pressed = false
end

-- the menu's entry: the panel for a report page's `dash` ({ run, rep, data, state, actions }); `model` another
-- page's (the compare page's), else the run page's
function R.newPanel(x, y, w, h, dash, model)
    local o = PzoptReportPanel:new(x, y, w, h, dash)
    o.model = model
    o:initialise()
    R.lastPanel = o -- (the report tour of devBenchmarkUi=report scrolls it)
    return o
end
