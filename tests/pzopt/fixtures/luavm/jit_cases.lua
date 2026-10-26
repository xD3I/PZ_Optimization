-- luaJit differential cases: each returns values that LuaJitDiffTest compares between the interpreter and the
-- compiled code (results, error messages, tracebacks). Keep every case deterministic.
local cases = {}
local function case(name, fn) cases[#cases + 1] = {name, fn} end

case("arith", function()
  local a, b = 7, 3
  return a + b, a - b, a * b, a / b, a % b, -a % b, a % -b, 5.5 % 2, a ^ 2, 2 ^ 0.5, -a, 0 / 0 ~= 0 / 0, 1 / 0, -1 / 0
end)
case("mod_zero", function() local z = 0 return 5 % z, -5 % z, 0 % z end)
case("neg_zero", function()
  local t = {}
  local z = -0.0
  t[z] = "a"
  local nz = 0 * -1
  t[nz] = "b"
  local c = 0
  for k, v in pairs(t) do c = c + 1 end
  return c, t[0], 1 / (z + 0), tostring(-z)
end)
case("string_coercion", function() return "10" + 1, "3" * "4", 10 .. "", 1.5 .. "x", "0x10" + 0, -"2" end)
case("string_coercion_fail", function() return "abc" + 1 end)
case("concat", function()
  local a = "x"
  local n = 12
  return a .. n .. "y" .. 1.25, a .. a .. a .. a .. a, ("p"):rep(3) .. "q"
end)
case("concat_fail", function() return "a" .. {} end)
case("compare", function()
  local r = {}
  local vals = {1, 2, 2.5, "a", "b", "ab", "B"}
  for i = 1, #vals do
    for j = 1, #vals do
      local x, y = vals[i], vals[j]
      if type(x) == type(y) then
        r[#r + 1] = tostring(x < y) .. tostring(x <= y) .. tostring(x == y) .. tostring(x > y) .. tostring(x >= y) .. tostring(x ~= y)
      else
        r[#r + 1] = tostring(x == y)
      end
    end
  end
  return table.concat(r, ",")
end)
case("compare_fail", function() return 1 < "x" end)
case("compare_nan", function() local n = 0 / 0 return n == n, n < n, n <= n, n > 1, 1 < n end)
case("logic", function()
  local t = {}
  local x = nil
  local y = false
  t[1] = x and 1 or 2
  t[2] = y or "d"
  t[3] = not x
  t[4] = not 0
  t[5] = (x == nil) and "nil" or "no"
  t[6] = 1 and nil
  t[7] = false or nil
  return t[1], t[2], t[3], t[4], t[5], t[6], t[7]
end)
case("numeric_for", function()
  local s = 0
  for i = 1, 10 do s = s + i end
  for i = 10, 1, -2 do s = s + i * 100 end
  for i = 0.5, 2.6, 0.25 do s = s + i end
  for i = 1, 0 do s = s + 1000000 end
  for i = 1, 3 do local j = i i = i + 10 s = s + j end
  return s
end)
case("for_bad_limit", function() for i = 1, "x" do end end)
case("for_nil_step", function() local st for i = 1, 2, st do end end)
case("while_repeat", function()
  local i, s = 0, 0
  while i < 100 do i = i + 1 if i % 7 == 0 then s = s + i end if i > 90 then break end end
  repeat i = i - 3 s = s + 1 until i < 10
  return i, s
end)
case("tables", function()
  local t = {10, 20, 30, x = 1, y = 2, [100] = "h", [2.5] = "f"}
  t[#t + 1] = 40
  table.insert(t, 50)
  table.insert(t, 1, 5)
  local r = table.remove(t)
  local keys = {}
  for k, v in pairs(t) do keys[#keys + 1] = tostring(k) .. "=" .. tostring(v) end
  local ik = {}
  for i, v in ipairs(t) do ik[#ik + 1] = i .. ":" .. v end
  return #t, r, table.concat(keys, " "), table.concat(ik, " "), t.x, t[2.5], rawget(t, "y"), t.nothing
end)
case("table_ctor_many", function()
  local t = {1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59,60}
  local function three() return "a", "b", "c" end
  local u = {three()}
  local v = {three(), three()}
  local w = {0, three()}
  return #t, t[50], t[51], t[60], #u, #v, #w, w[4]
end)
case("varargs", function()
  local function f(...) return select("#", ...), ... end
  local function g(a, ...) local t = {...} return a, #t, t[1], t[#t] end
  local function h(...) local a, b = ... return a, b end
  local function pass(...) return f(...) end
  local r = {f(1, nil, 3)}
  return r[1], r[2], r[3], r[4], g(1, 2, 3, 4), h(9), select(2, "a", "b", "c"), pass("x", "y")
end)
case("multret", function()
  local function m() return 1, 2, 3 end
  local function none() end
  local a, b, c, d = m()
  local x = m()
  local t = {m(), m()}
  local s = select("#", none())
  local u = {none(), 5}
  return a, b, c, d, x, #t, s, u[1], u[2], (m())
end)
case("tailcall_deep", function()
  local function loop(n, acc) if n == 0 then return acc end return loop(n - 1, acc + n) end
  return loop(20000, 0)
end)
case("recursion", function()
  local function fib(n) if n < 2 then return n end return fib(n - 1) + fib(n - 2) end
  return fib(20)
end)
case("deep_recursion", function()
  local function d(n) if n == 0 then return 0 end return 1 + d(n - 1) end
  return d(150)
end)
case("closures", function()
  local fs = {}
  for i = 1, 5 do fs[i] = function() return i * 10 end end
  local counter = 0
  local function inc() counter = counter + 1 return counter end
  inc() inc()
  local function mk() local v = 0 return function() v = v + 1 return v end, function() return v end end
  local up, get = mk()
  up() up() up()
  local acc = {}
  local j = 1
  while j <= 3 do local k = j acc[j] = function() return k + j end j = j + 1 end
  return fs[1](), fs[5](), counter, get(), acc[1](), acc[3]()
end)
case("upvalue_shared", function()
  local x = 1
  local function a() x = x + 1 end
  local function b() return x end
  a() a()
  local y = b()
  x = 100
  return y, b()
end)
case("metatables", function()
  local V = {}
  V.__index = V
  V.__add = function(p, q) return setmetatable({v = p.v + q.v}, V) end
  V.__eq = function(p, q) return p.v == q.v end
  V.__lt = function(p, q) return p.v < q.v end
  V.__le = function(p, q) return p.v <= q.v end
  V.__concat = function(p, q) return "V" .. (type(p) == "table" and p.v or p) .. (type(q) == "table" and q.v or q) end
  V.__unm = function(p) return setmetatable({v = -p.v}, V) end
  V.__call = function(self, a) return self.v + a end
  function V.new(v) return setmetatable({v = v}, V) end
  function V:get() return self.v end
  local a, b = V.new(1), V.new(2)
  local c = a + b
  return c.v, c:get(), a == b, a == V.new(1), a < b, a <= b, b < a, a .. b, a .. "s", (-a).v, a(10)
end)
case("index_newindex", function()
  local log = {}
  local base = {x = 1}
  local proxy = setmetatable({}, {__index = function(t, k) log[#log + 1] = k return k .. "!" end,
    __newindex = function(t, k, v) log[#log + 1] = "set" .. k rawset(t, k, v * 2) end})
  local chain = setmetatable({}, {__index = setmetatable({y = 2}, {__index = base})})
  local a = proxy.foo
  proxy.bar = 4
  local b = proxy.bar
  return a, b, chain.x, chain.y, chain.z, table.concat(log, ",")
end)
case("index_nil_fail", function() local t = nil return t.x end)
case("call_nil_fail", function() local f = nil return f(1) end)
case("call_field_nil_fail", function() local t = {} return t.missing(1) end)
case("error_string", function() error("boom") end)
case("error_table", function() error({code = 5}) end)
case("error_level", function() local function f() error("lvl", 2) end f() end)
case("pcall", function()
  local ok, e = pcall(function() error("inner") end)
  local ok2, e2 = pcall(function() local x = nil return x.y end)
  local ok3, a, b = pcall(function(p, q) return p + q, p * q end, 3, 4)
  local ok4, e4 = pcall(error, "direct")
  return ok, e, ok2, e2, ok3, a, b, ok4, e4
end)
case("nested_pcall_error", function()
  local function lvl3() error("deep") end
  local function lvl2() lvl3() end
  local function lvl1() local ok, e = pcall(lvl2) return ok, e end
  return lvl1()
end)
case("coroutines", function()
  local co = coroutine.create(function(a, b)
    local c = coroutine.yield(a + b)
    local d, e = coroutine.yield(c * 2)
    return d + e
  end)
  local r = {}
  local ok, v = coroutine.resume(co, 1, 2) r[#r + 1] = tostring(ok) .. v
  ok, v = coroutine.resume(co, 10) r[#r + 1] = tostring(ok) .. v
  ok, v = coroutine.resume(co, 3, 4) r[#r + 1] = tostring(ok) .. v
  r[#r + 1] = coroutine.status(co)
  local gen = coroutine.wrap(function() for i = 1, 3 do coroutine.yield(i) end end)
  r[#r + 1] = gen() + gen() + gen()
  local bad = coroutine.create(function() local x = nil return x.field end)
  local ok2, e2 = coroutine.resume(bad)
  r[#r + 1] = tostring(ok2) .. tostring(e2)
  return table.concat(r, "|")
end)
case("coroutine_calls_compiled", function()
  local function work(n) local s = 0 for i = 1, n do s = s + i end return s end
  local co = coroutine.create(function()
    local t = 0
    for i = 1, 4 do t = t + work(i * 10) coroutine.yield(t) end
    return "done"
  end)
  local out = {}
  while true do
    local ok, v = coroutine.resume(co)
    out[#out + 1] = tostring(v)
    if coroutine.status(co) == "dead" then break end
  end
  return table.concat(out, ",")
end)
case("strings", function()
  local s = "Hello World"
  return #s, s:upper(), s:sub(2, 4), s:find("World"), s:gsub("o", "0"), string.format("%d-%5.2f-%s", 3, 2.5, "x"), s:byte(1), string.char(72, 105), ("%s"):format(12)
end)
case("len", function()
  local t = {1, 2, 3, nil, 5}
  local s = "abc"
  local e = {}
  return #t >= 3, #s, #e, #"", #{n = 1}
end)
case("len_fail", function() return #5 end)
case("setlist_varargs", function()
  local function v(...) return {...} end
  local t = v(1, 2, 3)
  local u = {select(2, 1, 2, 3, 4)}
  return #t, t[3], #u, u[1]
end)
case("self_calls", function()
  local obj = {n = 0}
  function obj:add(k) self.n = self.n + k return self end
  obj:add(1):add(2):add(3)
  local s = "abc"
  return obj.n, s:len(), s:rep(2)
end)
case("globals", function()
  _G.__jit_g1 = 5
  __jit_g2 = (__jit_g1 or 0) + 1
  local r = __jit_g2 + __jit_g1
  __jit_g1 = nil
  return r, __jit_g1, type(print)
end)
case("loadnil_multi", function()
  local a, b, c, d = 1
  local e
  return a, b, c, d, e
end)
case("generic_for_custom", function()
  local function iter(t, i) i = i + 1 if t[i] then return i, t[i] end end
  local s = 0
  for i, v in iter, {5, 6, 7}, 0 do s = s + i * v end
  for k in next, {a = 1} do s = s + 100 end
  return s
end)
case("java_api", function()
  local cell = getCell()
  local sq = cell:getGridSquare(3, 4, 0)
  local objs = sq:getObjects()
  local names = {}
  for i = 0, objs:size() - 1 do
    local o = objs:get(i)
    local spr = o:getSprite()
    names[#names + 1] = spr and spr:getName() or "nil"
  end
  return objs:size(), table.concat(names, ","), sq:getX(), sq:getY(), isClient()
end)
case("java_call_bad_args", function()
  local sq = getCell():getGridSquare(1, 1, 0)
  return pcall(function() return sq:getObjects():get("x") end)
end)
case("many_locals", function()
  local a1,a2,a3,a4,a5,a6,a7,a8,a9,a10 = 1,2,3,4,5,6,7,8,9,10
  local b1,b2,b3,b4,b5,b6,b7,b8,b9,b10 = a10,a9,a8,a7,a6,a5,a4,a3,a2,a1
  local s = 0
  for i = 1, 10 do s = s + a1 * b1 + a5 * b5 + i end
  return s, a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10 + b1
end)
case("unm_meta_fail", function() local t = {} return -t end)
case("arith_meta_fail", function() local t = {} return t + 1 end)
case("tostring_tonumber", function()
  return tostring(1), tostring(1.5), tostring(1e100), tonumber("12"), tonumber("x"), tonumber("10", 16), tostring(nil), tostring(true)
end)
case("int_float", function()
  local x = 3
  local y = 3.0
  local t = {}
  t[x] = "int"
  t[y] = "float"
  return t[3], x == y, 7 / 2, math.floor(7 / 2), math.max(1, 5, 3), math.min(4, 2), math.abs(-3)
end)

-- luaJitNumbers: values held unboxed
local function opaque(x) return x end
case("num_negzero_chain", function()
  local z = 0 * -1
  local w = -z
  local a = 0
  local b = a * -2
  return 1 / z, 1 / w, 1 / b, 1 / (b + 0), 1 / (-0.0), tostring(z), opaque(z), 1 / opaque(b)
end)
case("num_loops", function()
  local s, t = 0, {}
  for i = 10, 1, -1 do s = s + i * 0.5 end
  for i = 0, 1, 0.25 do t[#t + 1] = i end
  for i = 1, 3 do for j = i, 3 do s = s + i * j end end
  for i = 1, 0 do s = s + 1000 end
  for i = 1.5, 3.5 do s = s + i end
  for i = 1, 5 do i = i * 2 s = s + i end
  return s, t[1], t[5], #t
end)
case("num_loop_capture", function()
  local fs = {}
  for i = 1, 4 do fs[i] = function() return i * 10 end end
  local r = {}
  for k = 1, 4 do r[k] = fs[k]() end
  return r[1], r[4]
end)
case("num_escape", function()
  local t = {}
  local x = 3
  local y = x * 2
  t[y] = y + 1
  t.k = y / 4
  local g = opaque(y) + opaque(x % 2)
  BenchNumEscape = y ^ 2
  return t[6], t.k, g, BenchNumEscape, y, select("#", y, x)
end)
case("num_merge", function()
  local x = 1
  for i = 1, 6 do
    if i % 2 == 0 then x = x * 3 else x = tostring(x) .. "" end
    x = tonumber(x) + i
  end
  local y = 5
  if opaque(true) then y = y + 1 else y = "s" end
  local z = 7
  while z < 100 do z = z * 2 + 1 end
  local q = 1
  repeat q = q + 0.5 until q > 3
  return x, y, z, q
end)
case("num_compare_loop", function()
  local n, c = 0 / 0, 0
  for i = 1, 10 do
    if i == 5 then c = c + 1 end
    if i < 3 then c = c + 10 end
    if i <= 3 then c = c + 100 end
    if 4 < i then c = c + 1000 end
    if n == n then c = c + 1e6 end
    if n ~= n then c = c + 1e5 end
    if i > n or i < n then c = c + 1e7 end
  end
  return c
end)
case("num_mod_pow", function()
  local a, b, c = 7, -3, 0
  return a % b, -a % 3, a % c, 2 ^ 10, a ^ 0.5, (-8) ^ (1 / 3), 5.5 % 2, a / c, -a / c
end)
case("num_index_loop", function()
  local t = {}
  for i = 1, 20 do t[i] = i * i end
  t.x = 5
  local s = 0
  for i = 1, 25 do s = s + (t[i] or -1) end
  local h = {[1.5] = "h", [30] = "far"}
  local mt = setmetatable({}, {__index = function(_, k) return k * 2 end})
  local u = 0
  for i = 1, 3 do u = u + mt[i] end
  return s, h[1.5], h[30], h[29 + 1], u, t[0], t[-1]
end)
case("num_deopt_live", function()
  local acc = 0
  local k = 2
  for i = 1, 30 do
    local before = i * k
    local r = opaque(before)
    acc = acc + r + before + i
    k = k + 0.5
  end
  return acc, k
end)
case("num_vs_string", function()
  local x = 10
  local s = x .. "|" .. (x + 0.5) .. "|" .. (x * 1e20) .. "|" .. (-x)
  local n = "5" + x
  return s, n, #tostring(x / 3)
end)
case("num_for_err_in_body", function()
  local s = 0
  for i = 1, 5 do
    s = s + i
    if i == 3 then local t = nil return s + t.x end
  end
  return s
end)

-- luaJitInlineCache: constant-key reads through remembered positions
case("ic_shapes", function()
  local function getx(o) return o.x end
  local a = {x = 1, y = 2}
  local b = {y = 3, x = 4}
  local c = {z = 1}
  local Base = {x = "base"}
  local d = setmetatable({}, {__index = Base})
  local e = setmetatable({}, {__index = function(t, k) return k .. "!" end})
  local r = {}
  for i = 1, 3 do
    r[#r + 1] = getx(a); r[#r + 1] = getx(b); r[#r + 1] = tostring(getx(c)); r[#r + 1] = getx(d); r[#r + 1] = getx(e)
  end
  a.x = nil
  r[#r + 1] = tostring(getx(a))
  a.x = 9
  r[#r + 1] = getx(a)
  for i = 1, 40 do a["k" .. i] = i end
  for i = 1, 40, 2 do a["k" .. i] = nil end
  for i = 41, 80 do a["k" .. i] = i end
  r[#r + 1] = getx(a)
  Base.x = "base2"
  r[#r + 1] = getx(d)
  rawset(d, "x", "own")
  r[#r + 1] = getx(d)
  return table.concat(r, ",")
end)
case("ic_globals", function()
  ICG1 = 5
  local function rd() return ICG1, ICG2 end
  local a1, a2 = rd()
  ICG1 = nil
  local b1, b2 = rd()
  ICG2 = "two"
  ICG1 = 7
  local c1, c2 = rd()
  return a1, a2, b1, b2, c1, c2
end)
case("ic_methods", function()
  local Cls = {}
  Cls.__index = Cls
  function Cls.new(v) return setmetatable({v = v}, Cls) end
  function Cls:get() return self.v end
  local Sub = setmetatable({}, {__index = Cls})
  Sub.__index = Sub
  function Sub.new(v) local o = Cls.new(v) return setmetatable(o, Sub) end
  function Sub:get() return "sub" .. self.v end
  local s = 0
  local objs = {Cls.new(1), Sub.new(2), Cls.new(3)}
  local out = {}
  for round = 1, 3 do
    for i = 1, #objs do out[#out + 1] = objs[i]:get() end
    if round == 2 then function Cls:get() return -self.v end end
  end
  return table.concat(out, ",")
end)
case("ic_nontable", function()
  local s = "abc"
  local r = {s:upper(), s:len(), ("x"):rep(3)}
  local ok, err = pcall(function() local n = 5 return n.field end)
  return r[1], r[2], r[3], ok, err
end)

case("ic_java_patch", function()
  local sq = getCell():getGridSquare(1, 2, 0)
  local function gx(o) return o:getX() end
  local r = {}
  for i = 1, 3 do r[#r + 1] = gx(sq) end
  local mt = getmetatable(sq)
  local idx = mt.__index
  local old = idx.getX
  idx.getX = function(self) return 99 end
  r[#r + 1] = gx(sq)
  idx.getX = old
  r[#r + 1] = gx(sq)
  local oldIndex = mt.__index
  mt.__index = function(o, k) return function() return k end end
  r[#r + 1] = gx(sq)
  mt.__index = oldIndex
  r[#r + 1] = gx(sq)
  local objs = getCell():getGridSquare(3, 4, 0):getObjects()
  for i = 0, objs:size() - 1 do r[#r + 1] = tostring(objs:get(i):getSprite() ~= nil) end
  local ok = pcall(function() return sq:noSuchMethod() end)
  r[#r + 1] = tostring(ok)
  return table.concat(r, ",")
end)

-- luaJavaFast: every Java call is repeated past luaJavaFastThreshold, so the stock and the generated path both run
local function jrep(f)
  local r = {}
  for i = 1, 6 do
    local p = {pcall(f, i)}
    for j = 1, #p do r[#r + 1] = tostring(p[j]) end
    r[#r + 1] = "/"
  end
  return table.concat(r, " ")
end

case("jf_numbers", function()
  return jrep(function(i)
    return JC:addI(i, 2.7), JC:addI(-2.5, 1e10), JC:mulL(i, 3.9), JC:halfF(i + 0.1), JC:sqrtD(i), 1 / JC:negZero(),
      JC:toB(300 + i), JC:toC(65 + i), JC:addI(0/0, 1), JC:halfF(1/0), JC:mulL(-1e30, 1)
  end)
end)

case("jf_bool_ref", function()
  return jrep(function(i)
    local t = {i}
    return JC:notZ(i % 2 == 0), JC:cat("a" .. i, "b"), JC:cat(nil, "x"), JC:id(t) == t, JC:id(nil), JC:id(i), JC:boxD(i),
      JC:list(i, "x"):size(), JC:list(i, "x"):get(0)
  end)
end)

case("jf_bad_args", function()
  return jrep(function(i)
    local r = {}
    r[#r + 1] = select("#", pcall(JC.addI, JC, i))
    r[#r + 1] = tostring(pcall(JC.addI, JC, i, 2, 3))
    r[#r + 1] = tostring(pcall(JC.addI, nil, i, 2))
    r[#r + 1] = tostring(pcall(JC.addI, "notjc", i, 2))
    r[#r + 1] = tostring(pcall(JC.addI, JC, "7", 2))
    r[#r + 1] = tostring(pcall(JC.addI, JC, true, 2))
    r[#r + 1] = tostring(pcall(JC.notZ, JC, 1))
    r[#r + 1] = tostring(pcall(JC.cat, JC, 5, "b"))
    r[#r + 1] = tostring(pcall(JC.toS, JC, 5))
    r[#r + 1] = tostring(pcall(JC.boxI, JC, 5))
    r[#r + 1] = select("#", JC:addI(nil, 1))
    r[#r + 1] = tostring(pcall(JC.addI, JC, {}, 1))
    return table.concat(r, ",")
  end)
end)

case("jf_errors", function()
  return jrep(function(i)
    local ok, a = pcall(JC.boom, JC, "x" .. i)
    return ok, a, select("#", JC:boom("y"))
  end)
end)

case("jf_overloads", function()
  return jrep(function(i)
    return JC:over(i), JC:over("s" .. i), JC:over(i, i + 0.5), JC:over({}) ~= nil, JC:over(true), JC:over(nil), pcall(JC.over, JC, i, "x")
  end)
end)

case("jf_varargs_rv", function()
  return jrep(function(i)
    return JC:count(), JC:count(i), JC:count(i, "a", nil, true), JC:two(i)
  end)
end)

case("jf_globals", function()
  return jrep(function(i)
    local before = JC:getCalls()
    local a = jcAdd(i, 3)
    jcNoop()
    jcNoop(1)
    local ok = pcall(jcNoop, 1, 2)
    return a, jcSAdd(i, 4), JC:getCalls() - before, ok, pcall(jcAdd, i), pcall(jcAdd, i, 1, 2)
  end)
end)

-- luaJitDirectCalls: errors thrown through chains of compiled-to-compiled calls past the depth limit, then normal calls again
case("direct_depth_errors", function()
  local function down(n, failAt)
    if n == failAt then error("fail at " .. n) end
    if n == 0 then return 0 end
    return 1 + down(n - 1, failAt)
  end
  local function step(x) return x + 1 end
  local function chain(x) return step(step(step(x))) end
  local r = {}
  for round = 1, 4 do
    r[#r + 1] = down(30, -1)
    r[#r + 1] = down(160, -1)
    r[#r + 1] = select(2, pcall(down, 160, 5))
    r[#r + 1] = select(2, pcall(down, 160, 150))
    r[#r + 1] = select(2, pcall(down, 40, 39))
    local acc = 0
    for i = 1, 50 do acc = chain(acc) end
    r[#r + 1] = acc
  end
  return table.concat(r, ",")
end)

-- luaJitInlineCache writes: present keys replaced in place, nil writes, removal and re-add, __newindex, class tables
case("ic_writes", function()
  local r = {}
  local logged = {}
  local proxy = setmetatable({}, {__newindex = function(t, k, v) logged[#logged + 1] = k .. "=" .. tostring(v); rawset(t, k, v) end})
  local Class = {}
  Class.__index = Class
  function Class.get(self) return self.v end
  local o = setmetatable({v = 1}, Class)
  local function bump(t) t.count = t.count + 1 end
  for round = 1, 3 do
    local t = {count = 0, name = "a"}
    for i = 1, 20 do bump(t) end
    t.name = nil
    t.name = "b"
    t.extra = round
    t.extra = nil
    t.count = t.count * 2
    r[#r + 1] = t.count .. t.name .. tostring(t.extra)
    proxy.x = round
    proxy.x = round + 1
    proxy.y = nil
    o.v = round
    r[#r + 1] = o:get()
    Class.get = function(self) return -self.v end
    r[#r + 1] = o:get()
    Class.get = function(self) return self.v end
    local keys = {}
    for k, v in pairs(t) do keys[#keys + 1] = tostring(k) .. ":" .. tostring(v) end
    table.sort(keys)
    r[#r + 1] = table.concat(keys, ";")
  end
  return table.concat(r, ","), table.concat(logged, ",")
end)

-- luaJitOsr: long loops in a function the interpreter runs (called once), entered at their headers; loops whose body
-- calls functions without code (deopt, then re-entry at the next back-edge); numbers, strings and tables live across
case("osr_loops", function()
  local function once(n)
    local acc, s, t = 0, "", {}
    for i = 1, n do
      acc = acc + i * 0.5
      if i % 97 == 0 then s = s .. i end
      t[#t + 1] = i
    end
    local j = 0
    while j < n do
      j = j + 3
      acc = acc - 1
    end
    local k = 0
    repeat k = k + 1; acc = acc + (k % 7) until k >= n
    for key, v in pairs({a = 1, b = 2, c = 3}) do acc = acc + v end
    return acc, s, #t, j, k
  end
  local helpers = {
    function(x) return x + 1 end, function(x) return x * 1.5 - 3 end, function(x) return (x % 1000) + 7 end,
    function(x) return x - 2 end, function(x) local t = {x} return t[1] + #t end, function(x) return x + 0.25 end,
  }
  local function calls(n)
    local acc = 0
    for i = 1, n do acc = helpers[(i % #helpers) + 1](acc) end
    return acc
  end
  local r = {}
  for round = 1, 3 do
    r[#r + 1] = table.concat({once(3000)}, ",")
    r[#r + 1] = calls(2000)
  end
  return table.concat(r, ";")
end)


-- a numeric for whose hidden index / limit registers a closure captures later (the same register slots reused for
-- locals after the loop): the index lives on the Lua stack, the loop variable in a JVM local
case("for_captured_index", function()
  local t = {}
  for i = 1, 5 do t[i] = i * 10 end
  local sum = 0
  for j = 10, 1, -3 do sum = sum + j * 2; t[#t + 1] = j end
  local a, b, c = 1, 2, 3
  local f = function() return a + b + c end
  a = a + 1
  return t[1], t[5], t[6], #t, sum, f()
end)
case("for_captured_long", function()
  local rows = {}
  for i = 1, 12 do
    local cells = {}
    for k = 1, 4 do cells[k] = { x = i * 10 + k, y = k * 5 } end
    rows[i] = { cells = cells, z = i % 3 }
  end
  local p, q = 4, 5
  local g = function() return p * q end
  local acc = 0
  for i = 1, #rows do acc = acc + rows[i].cells[2].x + rows[i].z end
  return acc, g()
end)

-- nested tables built in loops over pairs / ipairs and stored in a module table at the end (forageSystem.generateLootTable)
LootSys = {zoneDefs = {Forest = {a = 1}, Farm = {b = 2}}, catDefs = {Berries = {zones = {Forest = 1}}, Herbs = {zones = {Farm = 2}}},
  itemDefs = {["Base.Berry1"] = {zones = {Forest = 1}, categories = {"Berries"}}, ["Base.Herb1"] = {zones = {Farm = 1, Nowhere = 1}, categories = {"Herbs", "Nope"}}}}
function LootSys.generate()
  local lootTable = {}
  for zoneName, zoneDef in pairs(LootSys.zoneDefs) do
    lootTable[zoneName] = {categories = {}, definition = zoneDef}
    for catName, catDef in pairs(LootSys.catDefs) do
      lootTable[zoneName].categories[catName] = {items = {}, definition = catDef}
    end
  end
  for itemType, itemDef in pairs(LootSys.itemDefs) do
    for zoneName in pairs(itemDef.zones) do
      if LootSys.zoneDefs[zoneName] then
        for _, catName in ipairs(itemDef.categories) do
          if LootSys.catDefs[catName] then
            lootTable[zoneName].categories[catName].items[itemType] = {definition = itemDef}
          end
        end
      end
    end
  end
  LootSys.lootTable = lootTable
end
case("nested_build", function()
  local n = 0
  for r = 1, 3 do
    LootSys.generate()
    for z, zt in pairs(LootSys.lootTable) do for c, ct in pairs(zt.categories) do for i in pairs(ct.items) do n = n + 1 end end end
  end
  return n
end)

case("nested_build_outer", function() LootSys.generate(); LootSys.generate(); LootSys.generate(); return LootSys.lootTable.Forest.categories.Berries.items["Base.Berry1"] ~= nil end)
case("nested_build_direct", LootSys.generate)

-- the same at the game's size (forageSystem: ~30 zones, ~20 categories, ~1500 item definitions)
BigLoot = {zoneDefs = {}, catDefs = {}, itemDefs = {}}
for z = 1, 30 do BigLoot.zoneDefs["Zone" .. z] = {name = "Zone" .. z, densityMin = z} end
for c = 1, 20 do
  local zones = {}
  for z = 1, 30, 1 + c % 3 do zones["Zone" .. z] = c * z end
  BigLoot.catDefs["Cat" .. c] = {name = "Cat" .. c, zones = zones}
end
for i = 1, 1500 do
  local zones, cats = {}, {}
  for z = 1 + i % 7, 30, 1 + i % 5 do zones["Zone" .. z] = i % 11 end
  if i % 13 == 0 then zones.Nowhere = 1 end
  for c = 1 + i % 4, 20, 3 + i % 6 do cats[#cats + 1] = "Cat" .. c end
  BigLoot.itemDefs["Base.Item" .. i] = {type = "Base.Item" .. i, zones = zones, categories = cats}
end
function BigLoot.generateLootTable()
  local lootTable = {};
  local itemDefs = BigLoot.itemDefs;
  local zoneDefs = BigLoot.zoneDefs;
  local catDefs = BigLoot.catDefs;
  for zoneName, zoneDef in pairs(zoneDefs) do
    lootTable[zoneName] = {
      categories = {},
      definition = zoneDef,
    };
    for catName, catDef in pairs(catDefs) do
      lootTable[zoneName].categories[catName] = {
        items = {},
        definition = catDef;
      };
    end;
  end;
  for zoneName in pairs(zoneDefs) do
    for catName, catDef in pairs(catDefs) do
      lootTable[zoneName].categories[catName].zones = catDef.zones;
    end;
  end;
  for itemType, itemDef in pairs(itemDefs) do
    for zoneName in pairs(itemDef.zones) do
      if zoneDefs[zoneName] then
        for _, catName in ipairs(itemDef.categories) do
          if catDefs[catName] then
            lootTable[zoneName].categories[catName].items[itemType] = {
              definition = itemDef,
            };
          end;
        end;
      end;
    end;
  end;
  BigLoot.lootTable = lootTable;
end
case("big_loot_direct", BigLoot.generateLootTable)
case("big_loot_count", function()
  BigLoot.generateLootTable()
  local n, nz, nc, ni, nk = 0, 0, 0, 0, 0
  for z, zt in pairs(BigLoot.lootTable) do nz = nz + 1; for c, ct in pairs(zt.categories) do nc = nc + 1; for i in pairs(ct.items) do n = n + 1 end end end
  for k in pairs(BigLoot.itemDefs) do nk = nk + 1 end
  for k, d in pairs(BigLoot.itemDefs) do for z in pairs(d.zones) do ni = ni + 1 end end
  return n, nz, nc, ni, nk
end)

case("big_loot_walk", function()
  local n, nz = 0, 0
  for z, zt in pairs(BigLoot.lootTable) do nz = nz + 1; for c, ct in pairs(zt.categories) do for i in pairs(ct.items) do n = n + 1 end end end
  local t = {}
  for i = 1, 3000 do t["k" .. i] = i end
  local m = 0
  for k, v in pairs(t) do m = m + 1 end
  local direct = 0
  for z = 1, 30 do if BigLoot.lootTable["Zone" .. z] then direct = direct + 1 end end
  local seen = {}
  for z in pairs(BigLoot.lootTable) do seen[#seen + 1] = z end
  return n, nz, m, direct, table.concat(seen, ",")
end)

-- rows made by table.newarray (a KahluaArray, made inside a Java frame) in a table stored on an old global (TrailerCargo_Units)
TrailerRows = TrailerRows or {}
case("newarray_rows", function()
  local A = table.newarray
  TrailerRows.Units = {["9mmRounds"] = 1.0936, ["AlarmClock"] = 1.0936, ["Aluminum"] = 3.2808}
  TrailerRows.BoundsExtra = {
    ["Box"] = A(0.1, 0.2, 0.3, true),
    ["Crate"] = A(1.5, 2.5, 0.75, false),
  }
  local s = 0
  for k, row in pairs(TrailerRows.BoundsExtra) do s = s + row[1] + row[2] end
  return s, #TrailerRows.BoundsExtra.Box
end)

-- luaJitMissCache: instance method reads answered by the class chain, while the class, the instance and its metatable change
case("miss_ic_class_change", function()
  local A = {}
  A.__index = A
  function A:val() return 1 end
  local B = setmetatable({}, {__index = A})
  B.__index = B
  function B:val2() return 20 end
  local o = setmetatable({}, A)
  local p = setmetatable({}, B)
  local function call(x) return x:val() end
  local function call2(x) return x.val2 and x:val2() or -1 end
  local s, log = 0, {}
  for i = 1, 300 do
    s = s + call(o) + call(p) + call2(o) + call2(p)
    if i == 100 then function A:val() return 2 end end
    if i == 150 then p.val = function() return 100 end end
    if i == 200 then setmetatable(o, B); function B:val() return 7 end end
    if i == 250 then p.val = nil; B.val2 = nil end
    if i % 50 == 0 then log[#log + 1] = s end
  end
  return s, table.concat(log, ",")
end)

-- luaMetaVersionNarrow: instances get and change metatables without bumping the class version; every lookup is checked
-- against a cache-free walk (pairs over each table of the metatable chain), so a stale chain or miss cache shows as bad > 0
case("check_instance_mt_churn", function()
  local function own(t, k)
    for kk, vv in pairs(t) do if kk == k then return vv end end
    return nil
  end
  local function walk(t, k)
    local v = own(t, k)
    local m = getmetatable(t)
    local n = 0
    while v == nil and m ~= nil and n < 8 do
      v = own(m, k)
      m = getmetatable(m)
      n = n + 1
    end
    return v
  end
  local A = {kind = "A", a = 1}
  local B = setmetatable({kind = "B", b = 2}, A)
  local C = {kind = "C", c = 3}
  local keys = {"kind", "a", "b", "c", "x", "cnt"}
  local bad, sum, objs = 0, 0, {}
  local seed = 12345
  local function pick() seed = (seed * 16807) % 2147483647; return objs[seed % #objs + 1] end -- Park-Miller: every object, not objs[i % #objs]
  local function get(o, k) return o[k] end
  for i = 1, 400 do
    local o = setmetatable({x = i}, i % 3 == 0 and A or (i % 3 == 1 and B or C))
    objs[#objs + 1] = o
    if i % 7 == 0 then setmetatable(pick(), (i % 2 == 0) and C or B) end
    if i == 120 then setmetatable(B, C) end -- a class's own metatable: the chains through B change
    if i == 200 then A.b = 20 end
    if i % 11 == 0 then A.cnt = i end -- a class table that keeps a counter (luaMetaKeyVersion: only its key goes stale)
    if i % 13 == 0 then B.kind = "B" .. i end
    if i % 17 == 0 then C.cnt = nil end
    if i == 260 then setmetatable(objs[5], nil) end
    if i == 300 then local D = setmetatable({kind = "D"}, objs[9]); objs[#objs + 1] = setmetatable({}, D); setmetatable(objs[9], A) end
    if i == 330 then setmetatable(B, A); C.c = 30 end
    for j = 1, #keys do
      local o2 = pick()
      local k = keys[j]
      local v1, v2 = get(o2, k), walk(o2, k)
      if v1 ~= v2 then bad = bad + 1 end
      if type(v1) == "number" then sum = sum + v1 end
    end
  end
  return bad, sum
end)

-- luaJitUiDeps: tables made at load (so a recorded render logs their reads): instance fields, class methods through the
-- metatable chain (luaJitMissCache), absent keys, t[i], writes of present keys, a Java object's methods (JC)
UiFix = UiFix or {}
do
  local Base = {}
  Base.__index = Base
  function Base:name() return "base" end
  function Base:area() return self.w * self.h end
  local Box = setmetatable({}, {__index = Base})
  Box.__index = Box
  function Box:name() return "box" .. self.w end
  UiFix.Base, UiFix.Box = Base, Box
  UiFix.items = {}
  for i = 1, 12 do UiFix.items[i] = setmetatable({w = i, h = 2, label = "i" .. i}, i % 2 == 0 and Box or Base) end
  UiFix.cfg = {scale = 1.5, show = true, title = "inv"}
  UiFix.list = {10, 20, 30, 40}
end
case("uideps_reads", function()
  local r, s = {}, 0
  for k = 1, 3 do
    for i = 1, #UiFix.items do
      local it = UiFix.items[i]
      s = s + it:area() + (it.missing or 0) + UiFix.list[(i % 4) + 1]
      r[#r + 1] = it:name()
      if UiFix.cfg.show then s = s + UiFix.cfg.scale end
      it.label = "j" .. i -- a write of a present key: read + write logged
      it.h = 2 -- an unchanged write
    end
    r[#r + 1] = tostring(JC:addI(k, 1)) .. UiFix.cfg.title
  end
  return s, table.concat(r, ",")
end)

-- a table made inside a Java call (string split) and filled there: the dual rig must compare it as the shadow left it
case("java_made_table", function()
  local r = {}
  for i = 1, 30 do
    local parts = ("Vehicles.Update.n" .. i):split("\\.")
    r[#r + 1] = #parts .. parts[1] .. parts[#parts]
    local t = parts
    t[#t + 1] = "x"
    r[#r + 1] = #t
  end
  return table.concat(r, ",")
end)

-- luaJitNilCache: constant-key reads that end in nil after the __index walk, then every change that must end the cached nil
case("nil_cache_churn", function()
  local Base = {}
  Base.__index = Base
  local Mid = setmetatable({}, Base)
  Mid.__index = Mid
  local Other = {kind = "other"}
  Other.__index = Other
  local Plain = {} -- an __index table no table uses as its metatable (its writes move no version)
  local objs = {}
  for i = 1, 12 do objs[i] = setmetatable({id = i}, i % 2 == 0 and Mid or Base) end
  local function read(o) return o.missing, o.extra, o.kind end
  local out, calls = {}, 0
  for step = 1, 260 do
    if step == 40 then Base.missing = 5 end
    if step == 60 then Base.missing = nil end
    if step == 80 then Mid.__index = Other end
    if step == 100 then Mid.__index = Plain end
    if step == 110 then Plain.extra = "plain" end
    if step == 120 then Plain.extra = nil; Plain.missing = 7 end
    if step == 130 then Mid.__index = Mid end
    if step == 140 then setmetatable(objs[2], Other) end
    if step == 150 then objs[4].missing = "own" end
    if step == 160 then objs[4].missing = nil end
    if step == 170 then Base.__index = function(t, k) calls = calls + 1 return k == "extra" and calls or nil end end
    if step == 190 then Base.__index = Base end
    if step == 200 then setmetatable(Mid, Other) end
    if step == 220 then Other.extra = "o" end
    if step == 230 then setmetatable(Base, Plain) end
    if step == 240 then Plain.extra = "plain2" end
    if step == 250 then rawset(Plain, "missing", 9) end
    local o = objs[step % 12 + 1]
    local a, b, c = read(o)
    out[#out + 1] = tostring(a) .. "/" .. tostring(b) .. "/" .. tostring(c)
  end
  return table.concat(out, ","), calls
end)

-- luaJitNilCacheUi: nil reads through class tables made outside the recorded run (so its read set has them), the classes
-- changed during the run and put back at its end (the three runs of a case start alike)
do
  local B = {}
  B.__index = B
  local M = setmetatable({}, B)
  M.__index = M
  local O = {kind = "o"}
  O.__index = O
  local P = {}
  NilUi = {B = B, M = M, O = O, P = P, objs = {}}
  for i = 1, 10 do NilUi.objs[i] = setmetatable({id = i}, i % 2 == 0 and M or B) end
end
case("nil_cache_ui", function()
  local U = NilUi
  local B, M, O, P = U.B, U.M, U.O, U.P
  local out = {}
  local function read(o) return o.missing, o.extra, o.kind end -- both classes: the site's nil proof changes hands
  local function readOdd(o) return o.missing, o.extra, o.kind end -- one class per site (M, then O for objs[2])
  local function readEven(o) return o.missing, o.extra, o.kind end
  for step = 1, 200 do
    if step == 30 then B.missing = 5 end
    if step == 50 then B.missing = nil end
    if step == 70 then M.__index = O end
    if step == 90 then M.__index = P end
    if step == 100 then P.extra = "p" end
    if step == 110 then P.extra = nil end
    if step == 120 then M.__index = M end
    if step == 140 then setmetatable(U.objs[2], O) end
    if step == 160 then setmetatable(M, O) end
    if step == 180 then setmetatable(M, B) end
    local o = U.objs[step % 10 + 1]
    local a, b, c = read(o)
    local a2, b2, c2
    if step % 2 == 1 then a2, b2, c2 = readOdd(o) else a2, b2, c2 = readEven(o) end
    out[#out + 1] = tostring(a) .. "/" .. tostring(b) .. "/" .. tostring(c) .. tostring(a2) .. "/" .. tostring(b2) .. "/" .. tostring(c2)
  end
  setmetatable(U.objs[2], M)
  return table.concat(out, ",")
end)

-- luaJitNilCacheUi: the same with one class per read site only (no other site logs the class chain first)
case("nil_cache_ui_mono", function()
  local U = NilUi
  local B, M, O, P = U.B, U.M, U.O, U.P
  local out = {}
  local function readOdd(o) return o.missing, o.extra, o.kind end
  local function readEven(o) return o.missing, o.extra, o.kind end
  for step = 1, 200 do
    if step == 30 then B.missing = 5 end
    if step == 50 then B.missing = nil end
    if step == 70 then M.__index = O end
    if step == 90 then M.__index = P end
    if step == 100 then P.extra = "p" end
    if step == 110 then P.extra = nil end
    if step == 120 then M.__index = M end
    if step == 140 then setmetatable(U.objs[2], O) end
    if step == 160 then setmetatable(M, O) end
    if step == 180 then setmetatable(M, B) end
    local o = U.objs[step % 10 + 1]
    local a, b, c
    if step % 2 == 1 then a, b, c = readOdd(o) else a, b, c = readEven(o) end
    out[#out + 1] = tostring(a) .. "/" .. tostring(b) .. "/" .. tostring(c)
  end
  setmetatable(U.objs[2], M)
  return table.concat(out, ",")
end)

-- luaJitNilCacheUi: nothing changes during the run: every read the walks log comes from the sites' proven nils alone
case("nil_cache_ui_static", function()
  local U = NilUi
  local out = {}
  local function readOdd(o) return o.missing, o.extra, o.kind end
  local function readEven(o) return o.missing, o.extra end
  for step = 1, 40 do
    local o = U.objs[step % 10 + 1]
    local a, b, c
    if step % 2 == 1 then a, b, c = readOdd(o) else a, b, c = readEven(o) end
    out[#out + 1] = tostring(a) .. "/" .. tostring(b) .. "/" .. tostring(c)
  end
  return table.concat(out, ",")
end)

-- luaJitSlot2: one read site over instances of two layouts (fields set in two orders), keys removed and added back
case("slot2_layouts", function()
  local objs = {}
  for i = 1, 16 do
    if i % 2 == 0 then objs[i] = {x = i, y = i * 2, name = "e" .. i} else objs[i] = {name = "o" .. i, y = i * 3, x = -i} end
  end
  local function rd(o) return (o.x or 0) + (o.y or 0), o.name end
  local s, names = 0, {}
  for step = 1, 300 do
    local o = objs[step % 16 + 1]
    if step == 100 then objs[3].x = nil end
    if step == 150 then objs[3].x = 33; objs[4].y = nil end
    if step == 200 then objs[4].z = 1; objs[4].y = 44; objs[5].name = nil end
    if step == 250 then for k in pairs(objs[6]) do objs[6][k] = nil end; objs[6].name = "fresh"; objs[6].x = 6 end
    local a, n = rd(o)
    s = s + a
    if step % 7 == 0 then names[#names + 1] = tostring(n) end
  end
  return s, table.concat(names, ",")
end)

-- luaJitTypedArgs: numbers the compiled code holds unboxed (arithmetic results, loop counters) and boxed (constants, loop
-- limits) passed straight to Java methods of every numeric kind, to an Object / Double parameter, to an overload, to Lua
-- functions and through a callee that changes; -0.0 from arithmetic must reach Java as stock's box of it (+0.0), a -0.0
-- constant as itself
case("typed_args", function()
  local out = {}
  local nz = -0.0
  local function lf(a, b, c) return tostring(a) .. ":" .. tostring(b) .. ":" .. tostring(1 / (c or 1)) end
  local function lg(a, b, c) return "g" .. tostring(a + b) .. tostring(c) end
  local fs = {lf, lg, JC.over}
  for i = 0, 40 do
    local z = -(i * 0)
    local h = i * 0.5
    local r = {}
    r[#r + 1] = JC:addI(i * 2, i - 1)
    r[#r + 1] = JC:addI(i * 2, 7)
    r[#r + 1] = JC:addI(i, 3)
    r[#r + 1] = 1 / JC:sqrtD(z)
    r[#r + 1] = 1 / JC:sqrtD(nz)
    r[#r + 1] = 1 / JC:sqrtD(-0.0)
    r[#r + 1] = 1 / JC:halfF(z)
    r[#r + 1] = JC:halfF(h + 0.25)
    r[#r + 1] = JC:mulL(i * 3, i + 1e9)
    r[#r + 1] = JC:toB(i * 9)
    r[#r + 1] = JC:toC(65 + i)
    r[#r + 1] = tostring(JC:id(h))
    r[#r + 1] = tostring(1 / JC:id(z))
    r[#r + 1] = tostring(JC:boxD(h + 1))
    r[#r + 1] = tostring(JC:boxD(i))
    r[#r + 1] = JC:over(h, i + 1)
    r[#r + 1] = JC:over(i * 2)
    r[#r + 1] = lf(h, i + 1, z)
    r[#r + 1] = lf(i, 0.5, nz)
    local f = fs[i % 3 + 1]
    r[#r + 1] = tostring(f == JC.over and f(JC, h, i) or f(h, i * 2, z))
    r[#r + 1] = tostring(pcall(JC.addI, JC, h, (i % 5 == 0) and "x" or i))
    -- an unboxed argument beside a number held as an object (a table read): the typed call behind a class test
    local t = {v = i + 0.5, w = (i % 7 == 0) and "5" or i, s = (i % 11 == 0) and "x" or 2}
    r[#r + 1] = JC:addI(i * 2, t.v)
    r[#r + 1] = tostring(pcall(function() return JC:addI(i * 2, t.w) end))
    r[#r + 1] = tostring(pcall(function() return JC:mulL(i * 3, t.s) end))
    r[#r + 1] = tostring(pcall(function() return JC:addI(i + 1, t.nope) end))
    r[#r + 1] = 1 / JC:halfF(t.v - t.v + z)
    out[#out + 1] = table.concat(r, ",")
  end
  return table.concat(out, " ")
end)

-- luaJitSlot4: one read site over instances of six layouts (more than the four remembered), a key removed and put back,
-- reads repeated inside one call (the recorded UI pass: the second read of a table at a site takes the inlined path)
case("slot4_layouts", function()
  local objs = {}
  local orders = {{"x", "y", "name"}, {"name", "x", "y"}, {"y", "name", "x"}, {"pad", "x", "y", "name"},
    {"pad", "pad2", "name", "y", "x"}, {"y", "x", "pad", "name"}}
  for i = 1, 24 do
    local o = {}
    for _, k in ipairs(orders[i % 6 + 1]) do
      o[k] = (k == "name") and ("n" .. i) or i * (k == "x" and 1 or 3)
    end
    objs[i] = o
  end
  local function rd(o) return (o.x or 0) + (o.y or 0) + (o.x or 0), o.name, o.name end
  local s, names = 0, {}
  for step = 1, 400 do
    local o = objs[(step * 7) % 24 + 1]
    if step == 120 then objs[5].x = nil end
    if step == 180 then objs[5].x = 55 end
    if step == 240 then objs[9].name = nil; objs[10].pad = nil end
    if step == 300 then for k in pairs(objs[11]) do objs[11][k] = nil end; objs[11].y = 1; objs[11].name = "z" end
    local a, n, n2 = rd(o)
    s = s + a
    if step % 9 == 0 then names[#names + 1] = tostring(n) .. tostring(n2) end
  end
  return s, table.concat(names, ",")
end)

-- luaJitMixedNum: unboxed numbers meeting objects in arithmetic and comparisons (boxed numbers from tables, numbers with
-- metamethods behind them, strings that coerce and strings that error), and numbers as TEST / TESTSET operands
-- (`(d < 0) and -d or d`, `d or 0`, `not d`), the same registers later used as objects
-- no closure may capture the numbers (captured registers live on the Lua stack, never unboxed)
function mixedErr(t, k, n)
  for j = 1, 3 do
    local d = j * 2 - n % 3
    if j == 3 then
      if k == 1 then return t.bad + d elseif k == 2 then return d < t.s elseif k == 3 then return t.nope - d
      elseif k == 5 then return d < t.box elseif k == 6 then return t.box <= d end
      return d .. t.s, d + t.s, t.s * d, d < t.neg
    end
  end
end
case("mixed_num", function()
  local function v(x) return type(x) == "number" and x or "o" end
  local mt = {__add = function(a, b) return "add" .. v(a) .. v(b) end, __sub = function(a, b) return "sub" .. v(a) .. v(b) end,
    __lt = function(a, b) return v(a) == "o" and v(b) > 0 or v(b) == "o" and v(a) > 0 end,
    __le = function(a, b) return v(a) == "o" and v(b) > 1 or v(b) == "o" and v(a) > 1 end,
    __mul = function(a, b) return (v(a) == "o" and b or a) * 7 end, __unm = function(a) return "neg" end}
  local box = setmetatable({}, mt)
  local t = {box = box, px = 10, py = 20.5, s = "3", bad = "x", neg = -4}
  local out = {}
  local acc = 0
  for i = 1, 300 do
    local dx = i % 7 - 3
    local dy = (i * 3) % 11 - 5
    local adx = (dx < 0) and -dx or dx
    local ady = (dy < 0) and -dy or dy
    local m = dx or 0
    local n = not dy
    local px = (i % 50 == 0) and t.s or t.px
    local sx = px + dx
    local sy = t.py * dy
    local lt = (t.px < dx) and 1 or 0
    local le = (dy <= t.neg) and 1 or 0
    local eq = (dx == t.px) and 1 or 0
    acc = acc + adx + ady + m + (n and 1 or 0) + sx + sy + lt + le + eq
    if i % 37 == 0 then
      local o = box
      out[#out + 1] = tostring(o + dx) .. tostring(dx - o) .. tostring(dx * o) .. tostring(o * dy)
    end
    if i % 61 == 0 then
      out[#out + 1] = tostring(select(2, pcall(mixedErr, t, 1, i)))
      out[#out + 1] = tostring(select(2, pcall(mixedErr, t, 2, i)))
      out[#out + 1] = tostring(select(2, pcall(mixedErr, t, 3, i)))
      out[#out + 1] = tostring(select(2, pcall(mixedErr, t, 4, i)))
      out[#out + 1] = tostring(select(2, pcall(mixedErr, t, 5, i)))
      out[#out + 1] = tostring(select(2, pcall(mixedErr, t, 6, i)))
    end
    if i % 89 == 0 then
      dx = "str" .. dx  -- the register becomes an object
      out[#out + 1] = dx
    end
  end
  return acc, table.concat(out, ";")
end)

-- deopt exits with unboxed numbers live: a coroutine switch inside a Java call (coroutine.wrap's function) hands the
-- compiled frame to the interpreter in every iteration (luaJitEntryCompact's shared write-back chain); the loop is
-- entered again at its back-edge
case("deopt_raw", function()
  local co = coroutine.wrap(function() for i = 1, 1000 do coroutine.yield(i * 3) end end)
  local acc, x, t = 0, 0.5, {k = 7}
  local s = ""
  for i = 1, 200 do
    local y = x * i
    local z = i + 0.25
    local w = t.k + i
    local v = co()
    acc = acc + y + z + v + w
    if i % 50 == 0 then s = s .. y .. "/" .. z .. "/" .. w .. ";" end
  end
  return acc, s
end)

-- luaJitSpecArith: arithmetic on values read from tables keeps its result unboxed; the slow path's results: numbers
-- (string coercion, __add returning a number), a table from __add (the frame goes to the interpreter after the
-- instruction), -0.0 from a metamethod (kept as the object), and more than luaJitSpecLimit failures (compiled again)
case("spec_arith", function()
  local V = {}
  V.__index = V
  V.__add = function(a, b)
    if type(a) == "table" and a.neg0 then return -0.0 end
    if type(a) == "table" and a.num then return a.num + (type(b) == "number" and b or 0) end
    return setmetatable({x = (type(a) == "table" and a.x or a) + (type(b) == "table" and b.x or b)}, V)
  end
  V.__mul = function(a, b) return 3 end
  local items = {{v = 1}, {v = 2.5}, {v = "4"}, {v = setmetatable({num = 10}, V)}, {v = setmetatable({x = 1}, V)},
    {v = setmetatable({neg0 = true}, V)}, {v = -0.0}, {v = 1e300}}
  local out = {}
  local acc = 0
  for step = 1, 240 do
    local it = items[step % #items + 1]
    local s = it.v + step
    local m = it.v * 2
    local d = type(s) == "number" and s - 1 or 0
    if type(s) == "table" then
      out[#out + 1] = "t" .. s.x
    elseif step % 13 == 0 then
      out[#out + 1] = tostring(s) .. "," .. tostring(d) .. "," .. tostring(m) .. "," .. tostring(1 / s)
    end
    if type(d) == "number" then acc = acc + d end
    local held = {s} -- boxed: a -0.0 kept as the metamethod's object, not re-boxed as +0.0
    if type(s) == "number" and 1 / held[1] < 0 then out[#out + 1] = "neg0@" .. step end
  end
  local z = items[7].v + 0
  return acc, table.concat(out, ";"), tostring(z), tostring(1 / z)
end)

-- the same function called many times: its speculation fails on every call after warm-up (past luaJitSpecLimit it is
-- compiled again), the results identical throughout
function specMany(t, k)
  local r = t.a + k
  local q = t.b * k
  return type(r) == "table" and r.x or r, q
end
case("spec_recompile", function()
  local M = {__add = function(a, b) return setmetatable({x = b}, getmetatable(a)) end}
  local plain, meta = {a = 1, b = 2}, {a = setmetatable({}, M), b = 3}
  local acc, s = 0, ""
  for i = 1, 300 do
    local x, q = specMany(i > 100 and i % 3 == 0 and meta or plain, i)
    acc = acc + x + q
    if i % 37 == 0 then s = s .. x .. ":" .. q .. ";" end
  end
  return acc, s
end)

return cases
