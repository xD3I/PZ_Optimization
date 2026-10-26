-- Pure-Lua kernels for the Lua VM benchmark (tests/pzopt/LuaVmBench.java). Each BENCH entry runs its kernel n times
-- and returns a checksum, so the same file is the differential test's input too (interpreter vs compiled must agree).
BENCH = BENCH or {}

local floor = math.floor

-- numeric loop with arithmetic (Kahlua's % truncates: see pz-lua-mod-pitfalls)
BENCH.arith = function(n)
	local s = 0
	for r = 1, n do
		for i = 1, 200 do
			s = s + (i * 3 + r) % 7 - i / 4
		end
	end
	return s
end

-- array build, # and ipairs / numeric reads
BENCH.array = function(n)
	local total = 0
	for r = 1, n do
		local t = {}
		for i = 1, 64 do
			t[#t + 1] = i * r
		end
		for i, v in ipairs(t) do
			total = total + v - i
		end
		for i = 1, #t do
			total = total + t[i]
		end
	end
	return total
end

-- records with string keys, pairs over a hash table
BENCH.records = function(n)
	local total = 0
	for r = 1, n do
		local list = {}
		for i = 1, 16 do
			list[i] = { x = i, y = r, name = "item" .. i, weight = i * 0.5 }
		end
		local byName = {}
		for i = 1, #list do
			local it = list[i]
			byName[it.name] = it
			total = total + it.x * it.weight + it.y
		end
		for k, v in pairs(byName) do
			total = total + v.x
		end
	end
	return total
end

-- the game's ISBaseObject:derive class pattern: methods through the metatable chain
local Base = {}
Base.__index = Base
function Base:derive(type)
	local o = {}
	setmetatable(o, self)
	self.__index = self
	o.Type = type
	return o
end
function Base:new(x)
	local o = {}
	setmetatable(o, self)
	self.__index = self
	o.x = x
	o.hits = 0
	return o
end
function Base:hit(d)
	self.hits = self.hits + d
	return self.hits
end
local Mid = Base:derive("Mid")
function Mid:get()
	return self.x + self.hits
end
local Leaf = Mid:derive("Leaf")
function Leaf:hit(d)
	return Mid.hit(self, d * 2)
end

BENCH.oop = function(n)
	local total = 0
	local objs = {}
	for i = 1, 8 do
		objs[i] = Leaf:new(i)
	end
	for r = 1, n do
		for i = 1, #objs do
			local o = objs[i]
			o:hit(1)
			total = total + o:get()
		end
	end
	return total
end

-- strings: concat, comparisons, string library calls
BENCH.strings = function(n)
	local count = 0
	local names = { "floors_exterior_natural_01_12", "walls_exterior_house_01_4", "fixtures_sinks_01_2",
		"appliances_cooking_01_8", "furniture_storage_02_16", "vegetation_trees_01_8" }
	for r = 1, n do
		for i = 1, #names do
			local s = names[i]
			if string.find(s, "fixtures", 1, true) then count = count + 1 end
			if s:sub(1, 5) == "walls" then count = count + 2 end
			local key = s .. "_" .. r
			if #key > 30 then count = count + 1 end
			if s < "m" then count = count + 3 end
		end
	end
	return count
end

-- closures and upvalues
BENCH.closures = function(n)
	local total = 0
	local function counter()
		local c = 0
		return function(d)
			c = c + d
			return c
		end
	end
	for r = 1, n do
		local f = counter()
		for i = 1, 20 do
			total = total + f(i)
		end
	end
	return total
end

-- varargs, select, math.max (a Lua function in stdlib.lua), multiple returns
local function sum(...)
	local s = 0
	for i = 1, select("#", ...) do
		s = s + select(i, ...)
	end
	return s, select("#", ...)
end
BENCH.varargs = function(n)
	local total = 0
	for r = 1, n do
		local a, b = sum(r, 2, 3, 4)
		total = total + a + b + math.max(r % 5, 2, 3) + math.min(r, 7)
	end
	return total
end

-- recursive calls (call frames)
local function fib(k)
	if k < 2 then return k end
	return fib(k - 1) + fib(k - 2)
end
BENCH.calls = function(n)
	local total = 0
	for r = 1, n do
		total = total + fib(12)
	end
	return total
end

-- globals read and written in a loop (GETGLOBAL / SETGLOBAL)
BenchGlobalCounter = 0
BENCH.globals = function(n)
	for r = 1, n do
		for i = 1, 20 do
			BenchGlobalCounter = BenchGlobalCounter + floor(i / 3)
		end
	end
	return BenchGlobalCounter
end
