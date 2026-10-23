-- Every setting's description against its impact chart: each resource its EFFECTS row moves has a reason in its entry's
-- `why`, no reason stands for a resource it does not move, no reason names an unknown resource, every reason is one or
-- two plain sentences (no rich-text tags, at most 280 characters) and every entry has a description.
-- Run from the repository root: luajit tests/lua/effect-why.lua (or lua5.1)
local stub
stub = setmetatable({}, { __index = function() return stub end, __call = function() return stub end,
    __concat = function() return "" end })
setmetatable(_G, { __index = function() return stub end })

local path = "src/lua/client/pzopt/pzopt_optimizations_options.lua"
local src = assert(io.open(path)):read("*a")
-- (the chunk's locals are only reachable from inside it: the check is appended to it)
src = src .. [[

__EFFECT_WHY = { groups = { SECTIONS, ENHANCEMENT_SECTIONS, PROFILER_SECTIONS }, masters = { MASTER, ENHANCEMENTS_MASTER, PROFILER_MASTER },
    effects = EFFECTS, axes = AXES }
]]
assert((loadstring or load)(src, "options"))()
local t = rawget(_G, "__EFFECT_WHY")

local axes = {}
for _, a in ipairs(t.axes) do axes[a.id] = true end
local failures, checked = {}, 0
local function fail(key, msg) table.insert(failures, key .. ": " .. msg) end

local function check(e)
    checked = checked + 1
    local fx, why = t.effects[e.key] or {}, e.why or {}
    if type(e.tip) ~= "string" or e.tip == "" then fail(e.key, "no description") end
    for id, v in pairs(fx) do
        if not axes[id] then fail(e.key, "EFFECTS names an unknown resource " .. id) end
        if v ~= 0 and not why[id] then fail(e.key, "moves " .. id .. " (" .. v .. ") without a reason in `why`") end
    end
    for id, text in pairs(why) do
        if not axes[id] then fail(e.key, "`why` names an unknown resource " .. id) end
        if (fx[id] or 0) == 0 then fail(e.key, "`why` explains " .. id .. ", which its EFFECTS row does not move") end
        if type(text) ~= "string" or text == "" then fail(e.key, "empty reason for " .. id)
        elseif #text > 280 then fail(e.key, "reason for " .. id .. " is " .. #text .. " characters (at most 280)")
        elseif string.find(text, "[<>]") then fail(e.key, "reason for " .. id .. " holds < or >") end
    end
end

for _, m in ipairs(t.masters) do check(m) end
for _, list in ipairs(t.groups) do
    for _, s in ipairs(list) do
        for _, e in ipairs(s.entries or {}) do check(e) end
    end
end

if #failures > 0 then
    for _, f in ipairs(failures) do print("FAIL " .. f) end
    print(#failures .. " problems in " .. checked .. " settings")
    os.exit(1)
end
print("effect-why: " .. checked .. " settings, every resource they move explained")
