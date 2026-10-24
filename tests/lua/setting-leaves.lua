-- The PZ OPTIMIZATION screen never draws a switch and a dropdown on one row (maintainer, 2026-10-24): a choice setting with
-- an off value and two or more other values gets a leaf row for its value (PzoptSettings.leaf). Every such setting has its
-- leaf named in the layout's `leaves` (or `leaves[key] = false`), and every `leaves` entry is such a setting.
-- Run from the repository root: luajit tests/lua/setting-leaves.lua (or lua5.1)
local stub
stub = setmetatable({}, { __index = function() return stub end, __call = function() return stub end,
    __concat = function() return "" end })
setmetatable(_G, { __index = function() return stub end })

assert((loadfile)("src/lua/client/pzopt/pzopt_optimizations_layout.lua"))()
local L = rawget(_G, "PzoptSettingsLayout")
local src = assert(io.open("src/lua/client/pzopt/pzopt_optimizations_options.lua")):read("*a")
src = src .. "\n__LEAVES = { groups = { SECTIONS, ENHANCEMENT_SECTIONS, PROFILER_SECTIONS }, api = PzoptSettings }\n"
assert((loadstring or load)(src, "options"))()
local t = rawget(_G, "__LEAVES")

local failures, n, seen = {}, 0, {}
local function fail(key, msg) table.insert(failures, key .. ": " .. msg) end
for _, list in ipairs(t.groups) do
    for _, s in ipairs(list) do
        for _, e in ipairs(s.entries or {}) do
            local spec = (L.leaves or {})[e.key]
            local leaf = t.api.leaf(e.key, e)
            if e.key then seen[e.key] = true end
            if leaf then
                n = n + 1
                if spec == nil then fail(e.key, "switch + choice without a `leaves` entry (its leaf would be called \"Mode\")") end
                if type(spec) == "table" and (type(spec.label) ~= "string" or spec.label == "") then fail(e.key, "leaf without a label") end
                if type(spec) == "table" and spec.tip and string.find(spec.tip, "[<>]") then fail(e.key, "leaf tip holds < or >") end
            elseif spec then
                fail(e.key, "`leaves` names a setting that is not a choice with an off value and two or more others")
            end
        end
    end
end
for k in pairs(L.leaves or {}) do
    if not seen[k] then fail(k, "`leaves` names an unknown key") end
end
if #failures > 0 then
    for _, f in ipairs(failures) do print("FAIL " .. f) end
    os.exit(1)
end
print("setting-leaves: " .. n .. " switched choices, each value on a leaf row of its own")
