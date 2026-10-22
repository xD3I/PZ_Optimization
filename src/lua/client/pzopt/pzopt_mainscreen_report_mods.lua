-- PZ Optimization: the benchmark report's "Mods (Lua)" row (2026-10-21).
--
-- How much of each frame every enabled mod's Lua took during the pass (pzopt.ModLuaProfile: the Lua stack sampled off
-- the game thread at 2 kHz, every event handler and UI call counted), answered by pzopt.ModLuaDash and drawn by
-- pzopt_mainscreen_report.lua's own panel types: stat tiles, tables with gauges, a stacked time series. This file only
-- adds the row's panels to PzoptReportDash.SPEC, below the game-thread row (the later rows move down by its height);
-- it loads after pzopt_mainscreen_report.lua (name order) and again after it on a Lua reload.

PzoptReportDash = PzoptReportDash or {}

local WHY = "this pass ran without the mod Lua profiler (its benchmark option off, or a run before this version)"
local MS_THR = { { "#73BF69", -1e300 }, { "#EAB839", 0.1 }, { "#FF9830", 0.5 }, { "#F2495C", 1 } }
local PCT_THR = { { "#73BF69", -1e300 }, { "#EAB839", 2 }, { "#FF9830", 5 }, { "#F2495C", 10 } }
local SPIKE_THR = { { "#73BF69", -1e300 }, { "#EAB839", 0.5 }, { "#FF9830", 2 }, { "#F2495C", 4 } }

-- y is relative to the row's own top; ids 60-69 (the same ids as pzopt-run.json's Mods row: a SPEC that holds them already keeps its own)
local ROW = {
    { id = 60, t = "row", title = "Mods (Lua): each mod's Lua on the game thread (ModLuaProfile, sampled at 2 kHz)", x = 0, y = 0, w = 24, h = 1 },
    { id = 61, why = WHY, t = "stat", title = "Lua per frame (route mean)", x = 0, y = 1, w = 7, h = 4, unit = "ms", dec = 3, thr = { { "text", -1e300 } }, cm = "none", vs = 26 },
    { id = 62, why = WHY, t = "stat", title = "Mods' share of the frame", x = 7, y = 1, w = 5, h = 4, unit = "percent", dec = 1, thr = PCT_THR, cm = "background", vs = 26 },
    { id = 63, why = WHY, t = "stat", title = "Worst mod", x = 12, y = 1, w = 7, h = 4, unit = "ms", dec = 3, thr = MS_THR, cm = "none", vs = 20 },
    { id = 69, why = WHY, t = "stat", title = "Profiler", x = 19, y = 1, w = 5, h = 4, unit = "none", thr = { { "text", -1e300 } }, cm = "none", vs = 18 },
    { id = 64, why = WHY, t = "table", title = "Mods (route): Lua time per frame, calls, the slowest 1 % of frames", x = 0, y = 5, w = 24, h = 10,
      ov = { mod = { w = 300 }, kind = { w = 120 },
          ["ms/frame"] = { unit = "ms", dec = 3, gauge = true, min = 0, max = 2, thr = MS_THR },
          ["% of frame"] = { unit = "percent", dec = 1, gauge = true, min = 0, max = 20, thr = PCT_THR },
          ["calls/frame"] = { unit = "none", dec = 1 },
          ["p99 spike ms"] = { unit = "ms", dec = 2, gauge = true, min = 0, max = 5, thr = SPIKE_THR },
          files = { unit = "none", dec = 0, w = 70 } } },
    { id = 65, why = WHY, lr = true, t = "ts", title = "Lua per mod (ms per frame, 0.5 s means)", x = 0, y = 15, w = 24, h = 9, unit = "ms", min = 0,
      draw = "line", fill = 60, stack = true, softMin = 0, ps = 4, ov = { ["game's Lua"] = { color = "#8AB8FF" } } },
    { id = 66, why = WHY, t = "table", title = "Top Lua functions (route): self = own code and the Java it calls, total = with every function it calls", x = 0, y = 24, w = 24, h = 12,
      ov = { mod = { w = 220 }, ["function"] = { w = 420 }, event = { w = 170 },
          ["self ms/frame"] = { unit = "ms", dec = 3, gauge = true, min = 0, max = 1, thr = MS_THR },
          ["total ms/frame"] = { unit = "ms", dec = 3 },
          ["calls/frame"] = { unit = "none", dec = 1 } } },
    { id = 67, why = WHY, t = "table", title = "Lua by event (route)", x = 0, y = 36, w = 12, h = 10,
      ov = { event = { w = 170 }, ["Lua ms/frame"] = { unit = "ms", dec = 3, gauge = true, min = 0, max = 2, thr = MS_THR },
          ["mods ms/frame"] = { unit = "ms", dec = 3 }, ["calls/frame"] = { unit = "none", dec = 1 } } },
    { id = 68, why = WHY, t = "table", title = "Slowest frames: whose Lua ran in them", x = 12, y = 36, w = 12, h = 10, unit = "ms",
      ov = { ["at s"] = { unit = "none", dec = 2, w = 80 }, ["frame ms"] = { unit = "ms", dec = 2 }, ["Lua ms"] = { unit = "ms", dec = 2 }, ["mods ms"] = { unit = "ms", dec = 2 },
          ["its ms"] = { unit = "ms", dec = 2 } } },
}

-- below the game-thread row: where the next row ("Chunks, GC, flips", id 33) starts, else at the bottom
local function install()
    local R = PzoptReportDash
    if not R.SPEC then return end
    for _, s in ipairs(R.SPEC) do
        if s.id == 60 then return end
    end
    local at, bottom = nil, 0
    for _, s in ipairs(R.SPEC) do
        if s.id == 33 then at = s.y end
        bottom = math.max(bottom, s.y + s.h)
    end
    at = at or bottom
    local height = 0
    for _, s in ipairs(ROW) do height = math.max(height, s.y + s.h) end
    for _, s in ipairs(R.SPEC) do
        if s.y >= at then s.y = s.y + height end
    end
    for _, s in ipairs(ROW) do
        local c = {}
        for k, v in pairs(s) do c[k] = v end
        c.y = s.y + at
        table.insert(R.SPEC, c)
    end
    R.orderedSpec = nil
end

install()
