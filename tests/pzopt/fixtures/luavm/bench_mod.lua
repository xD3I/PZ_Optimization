-- Mod-shaped Lua for the Lua VM benchmark: LoadGridsquare / OnTick handlers written the way the hottest handlers of the
-- 650-mod list are (FenceSheets, LGExtendedPlumbing, WorldDecay, TrueSewers, NRE), against the stub game API of
-- tests/pzopt/LuaVmStubs.java (exposed through the game's LuaJavaClassExposer, so every call is a real Lua -> Java call).
BENCH = BENCH or {}

local Mod = {}
Mod.seen = {}
Mod.fixtures = {}
Mod.kinds = { fixtures = 1, appliances = 2, furniture = 3 }

local function stringStarts(String, Start)
	return string.sub(String, 1, string.len(Start)) == Start
end

function Mod.key(x, y, z)
	return x .. "," .. y .. "," .. z
end

function Mod.spriteInfo(obj)
	local spr = obj:getSprite()
	if not spr then return nil end
	local name = spr:getName()
	if not name then return nil end
	local cached = Mod.seen[name]
	if cached ~= nil then return cached or nil end
	local info = false
	for prefix, kind in pairs(Mod.kinds) do
		if stringStarts(name, prefix) then
			info = { kind = kind, name = name }
			break
		end
	end
	Mod.seen[name] = info
	return info or nil
end

-- FenceSheets-style: neighbour probes + specials walk
local function onLoadGridsquareA(square)
	local specials = square:getSpecialObjects()
	local cell = getCell()
	local x, y, z = square:getX(), square:getY(), square:getZ()
	local sqN = cell:getGridSquare(x, y - 1, z)
	local sqW = cell:getGridSquare(x - 1, y, z)
	local hits = 0
	if sqN and sqN:isOutside() then hits = hits + 1 end
	if sqW and sqW:isOutside() then hits = hits + 1 end
	for i = 0, specials:size() - 1 do
		local obj = specials:get(i)
		if Mod.spriteInfo(obj) ~= nil then hits = hits + 10 end
	end
	if isClient() then return hits end
	return hits
end

-- LGExtendedPlumbing-style: object walk, sprite properties, lazy key, modData
local function onLoadGridsquareB(square)
	if not square then return 0 end
	local x, y, z = square:getX(), square:getY(), square:getZ()
	local objects = square:getObjects()
	local key = nil
	local found = 0
	for i = 0, objects:size() - 1 do
		local object = objects:get(i)
		local sprite = object:getSprite()
		local props = sprite and sprite:getProperties()
		if props and props:Is("waterAmount") then
			key = key or Mod.key(x, y, z)
			if not Mod.fixtures[key] then
				Mod.fixtures[key] = { x = x, y = y, z = z, amount = tonumber(props:Val("waterAmount")) }
			end
			found = found + 1
		end
		if object:getObjectName() == "Thumpable" and props and props:Is("IsMoveAble") then
			found = found + 2
		end
	end
	if square:hasModData() then
		local md = square:getModData()
		found = found + (md.visits or 0)
	end
	return found
end

-- WorldDecay-style: modData counters, math.floor, table of categories
local categories = {}
for i = 1, 6 do
	categories[i] = { eligKey = "elig" .. i, placedKey = "placed" .. i, chance = i * 0.1 }
end
local function onLoadGridsquareC(square)
	local md = square:getModData()
	md.visits = (md.visits or 0) + 1
	local wx, wy = math.floor(square:getX() / 8), math.floor(square:getY() / 8)
	local score = 0
	for i = 1, #categories do
		local c = categories[i]
		local v = (md[c.eligKey] or 0) + 1
		md[c.eligKey] = v
		if (wx + wy + i) % 3 == 0 then
			score = score + v * c.chance
		end
	end
	return score
end

-- TrueSewers-style: sprite name tests with string.find / sub
local function onLoadGridsquareD(square)
	local floor = square:getFloor()
	if not floor then return 0 end
	local name = floor:getSpriteName()
	if not name then return 0 end
	local n = 0
	if string.find(name, "floors_exterior") then n = n + 1 end
	if string.sub(name, 1, 6) == "blends" then n = n + 2 end
	if name:find("_natural_", 1, true) then n = n + 4 end
	return n
end

local handlers = { onLoadGridsquareA, onLoadGridsquareB, onLoadGridsquareC, onLoadGridsquareD }

-- one "frame" of LoadGridsquare: every handler for every square of a 16x16 patch (the event fires once per
-- streamed square; the game's Event.trigger calls each handler through LuaCaller)
BENCH.loadgridsquare = function(n)
	local cell = getCell()
	local total = 0
	local w, h = cell:getWidth(), cell:getHeight()
	for r = 1, n do
		for y = 0, 15 do
			for x = 0, 15 do
				local sq = cell:getGridSquare((x + r) % w, y, 0)
				for k = 1, #handlers do
					total = total + handlers[k](sq)
				end
			end
		end
	end
	return total
end

-- NRE-style OnTick: rectangle containment over a room list
local rooms = {}
for i = 1, 12 do
	local rects = {}
	for j = 1, 4 do
		rects[j] = { x = i * 10 + j, y = j * 5, x2 = i * 10 + j + 4, y2 = j * 5 + 3 }
	end
	rooms[i] = { rects = rects, z = i % 3 }
end
local function roomContains(room, x, y)
	for i = 1, #room.rects do
		local rect = room.rects[i]
		if x >= rect.x and x < rect.x2 and y >= rect.y and y < rect.y2 then
			return true
		end
	end
	return false
end
local SIDES = { { 0, 0 }, { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 } }
BENCH.ontick = function(n)
	local hits = 0
	for r = 1, n do
		local x, y = 10 + r % 120, r % 25
		for i = 1, #rooms do
			for s = 1, #SIDES do
				if roomContains(rooms[i], x + SIDES[s][1], y + SIDES[s][2]) then hits = hits + 1 end
			end
		end
	end
	return hits
end
