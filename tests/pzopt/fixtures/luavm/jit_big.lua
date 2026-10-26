-- luaJit: functions too big for one JVM method (over the class file's 64 KB code limit): compiled in the split form.
-- Generated (deterministic python, see LUA-JIT-LOG.md iteration 16); LuaJitDiffTest runs every case like jit_cases.lua.
local cases = {}
local function case(name, fn) cases[#cases + 1] = {name, fn} end
local function helper(a, b) return a * 2 + b end

case("big_0", function()
  local t = {}
  local a, b, c, d = 1, 2.5, 3, 0
  local s = ""
  if a > 72 then b = b + 1 else c = c + 2 end
  t[2] = a + 1
  t.f2 = (t.f2 or 0) + c
  t[4] = a + 3
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  if 7 % 97 == 0 then s = s .. "7," end
  d = helper(d, 8) % 1000
  t[10] = a + 9
  b = (b * 1.5 + a) % 100.25
  a = a + 7 * b - c % 5
  a = a + 8 * b - c % 4
  d = helper(d, 0) % 1000
  t[15] = a + 14
  for j = 1, 1 do c = (c + j * a) % 997 end
  a = a + 1 * b - c % 6
  a = a + 7 * b - c % 3
  if 18 % 97 == 0 then s = s .. "18," end
  a = a + 4 * b - c % 5
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 8) % 1000
  for j = 1, 1 do c = (c + j * a) % 997 end
  d = helper(d, 10) % 1000
  b = (b * 1.5 + a) % 100.25
  t.f25 = (t.f25 or 0) + c
  a = a + 7 * b - c % 6
  t[28] = a + 27
  if a > 80 then b = b + 1 else c = c + 2 end
  t.f29 = (t.f29 or 0) + c
  t[31] = a + 30
  for j = 1, 3 do c = (c + j * a) % 997 end
  if 32 % 97 == 0 then s = s .. "32," end
  d = helper(d, 7) % 1000
  t.f4 = (t.f4 or 0) + c
  t.f5 = (t.f5 or 0) + c
  b = (b * 1.5 + a) % 100.25
  if 37 % 97 == 0 then s = s .. "37," end
  a = a + 8 * b - c % 3
  if 39 % 97 == 0 then s = s .. "39," end
  if 40 % 97 == 0 then s = s .. "40," end
  if a > 46 then b = b + 1 else c = c + 2 end
  for j = 1, 1 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  t[45] = a + 44
  if a > 66 then b = b + 1 else c = c + 2 end
  if 46 % 97 == 0 then s = s .. "46," end
  for j = 1, 2 do c = (c + j * a) % 997 end
  a = a + 8 * b - c % 2
  t.f19 = (t.f19 or 0) + c
  if 50 % 97 == 0 then s = s .. "50," end
  if a > 21 then b = b + 1 else c = c + 2 end
  d = helper(d, 0) % 1000
  a = a + 4 * b - c % 6
  d = helper(d, 2) % 1000
  if 55 % 97 == 0 then s = s .. "55," end
  for j = 1, 3 do c = (c + j * a) % 997 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  t.f28 = (t.f28 or 0) + c
  a = a + 7 * b - c % 6
  if a > 66 then b = b + 1 else c = c + 2 end
  d = helper(d, 9) % 1000
  if 62 % 97 == 0 then s = s .. "62," end
  a = a + 8 * b - c % 4
  d = helper(d, 12) % 1000
  if 65 % 97 == 0 then s = s .. "65," end
  b = (b * 1.5 + a) % 100.25
  for j = 1, 2 do c = (c + j * a) % 997 end
  for j = 1, 1 do c = (c + j * a) % 997 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  a = a + 4 * b - c % 3
  if a > 11 then b = b + 1 else c = c + 2 end
  t.f12 = (t.f12 or 0) + c
  a = a + 2 * b - c % 2
  a = a + 8 * b - c % 2
  t.f15 = (t.f15 or 0) + c
  d = helper(d, 11) % 1000
  t.f17 = (t.f17 or 0) + c
  t[29] = a + 78
  if a > 44 then b = b + 1 else c = c + 2 end
  t.f20 = (t.f20 or 0) + c
  t[32] = a + 81
  if a > 20 then b = b + 1 else c = c + 2 end
  t.f23 = (t.f23 or 0) + c
  if a > 84 then b = b + 1 else c = c + 2 end
  t.f25 = (t.f25 or 0) + c
  t.f26 = (t.f26 or 0) + c
  b = (b * 1.5 + a) % 100.25
  for j = 1, 2 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  t[41] = a + 90
  a = a + 5 * b - c % 5
  for j = 1, 2 do c = (c + j * a) % 997 end
  d = helper(d, 2) % 1000
  t.f4 = (t.f4 or 0) + c
  t[46] = a + 95
  t.f6 = (t.f6 or 0) + c
  d = helper(d, 6) % 1000
  if 98 % 97 == 0 then s = s .. "98," end
  a = a + 4 * b - c % 2
  if 100 % 97 == 0 then s = s .. "100," end
  if a > 4 then b = b + 1 else c = c + 2 end
  if a > 57 then b = b + 1 else c = c + 2 end
  if 103 % 97 == 0 then s = s .. "103," end
  d = helper(d, 0) % 1000
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 2) % 1000
  a = a + 7 * b - c % 6
  for j = 1, 3 do c = (c + j * a) % 997 end
  if 109 % 97 == 0 then s = s .. "109," end
  a = a + 5 * b - c % 3
  d = helper(d, 7) % 1000
  a = a + 5 * b - c % 2
  t[14] = a + 113
  t.f24 = (t.f24 or 0) + c
  t.f25 = (t.f25 or 0) + c
  if a > 53 then b = b + 1 else c = c + 2 end
  t.f27 = (t.f27 or 0) + c
  if a > 1 then b = b + 1 else c = c + 2 end
  a = a + 4 * b - c % 6
  b = (b * 1.5 + a) % 100.25
  if a > 99 then b = b + 1 else c = c + 2 end
  a = a + 7 * b - c % 3
  for j = 1, 1 do c = (c + j * a) % 997 end
  d = helper(d, 7) % 1000
  if 125 % 97 == 0 then s = s .. "125," end
  d = helper(d, 9) % 1000
  b = (b * 1.5 + a) % 100.25
  t[29] = a + 128
  if 129 % 97 == 0 then s = s .. "129," end
  t.f10 = (t.f10 or 0) + c
  b = (b * 1.5 + a) % 100.25
  a = a + 6 * b - c % 6
  if 133 % 97 == 0 then s = s .. "133," end
  t.f14 = (t.f14 or 0) + c
  a = a + 3 * b - c % 3
  for j = 1, 3 do c = (c + j * a) % 997 end
  if a > 43 then b = b + 1 else c = c + 2 end
  if 138 % 97 == 0 then s = s .. "138," end
  d = helper(d, 9) % 1000
  t.f20 = (t.f20 or 0) + c
  t[42] = a + 141
  if 142 % 97 == 0 then s = s .. "142," end
  for j = 1, 3 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 2) % 1000
  t[47] = a + 146
  a = a + 2 * b - c % 3
  if a > 21 then b = b + 1 else c = c + 2 end
  d = helper(d, 6) % 1000
  t.f0 = (t.f0 or 0) + c
  for j = 1, 3 do c = (c + j * a) % 997 end
  t.f2 = (t.f2 or 0) + c
  for j = 1, 2 do c = (c + j * a) % 997 end
  for j = 1, 1 do c = (c + j * a) % 997 end
  t.f5 = (t.f5 or 0) + c
  d = helper(d, 0) % 1000
  b = (b * 1.5 + a) % 100.25
  if a > 74 then b = b + 1 else c = c + 2 end
  t[10] = a + 159
  for j = 1, 1 do c = (c + j * a) % 997 end
  if 161 % 97 == 0 then s = s .. "161," end
  t[13] = a + 162
  if 163 % 97 == 0 then s = s .. "163," end
  if a > 16 then b = b + 1 else c = c + 2 end
  for j = 1, 1 do c = (c + j * a) % 997 end
  if 166 % 97 == 0 then s = s .. "166," end
  t[18] = a + 167
  d = helper(d, 12) % 1000
  t[20] = a + 169
  t.f20 = (t.f20 or 0) + c
  for j = 1, 2 do c = (c + j * a) % 997 end
  t[23] = a + 172
  b = (b * 1.5 + a) % 100.25
  t.f24 = (t.f24 or 0) + c
  t[26] = a + 175
  a = a + 5 * b - c % 2
  a = a + 2 * b - c % 5
  t[29] = a + 178
  a = a + 4 * b - c % 3
  if 180 % 97 == 0 then s = s .. "180," end
  if a > 14 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  if a > 87 then b = b + 1 else c = c + 2 end
  d = helper(d, 2) % 1000
  if a > 95 then b = b + 1 else c = c + 2 end
  t[37] = a + 186
  if 187 % 97 == 0 then s = s .. "187," end
  if 188 % 97 == 0 then s = s .. "188," end
  t.f9 = (t.f9 or 0) + c
  t.f10 = (t.f10 or 0) + c
  b = (b * 1.5 + a) % 100.25
  for j = 1, 1 do c = (c + j * a) % 997 end
  d = helper(d, 11) % 1000
  for j = 1, 1 do c = (c + j * a) % 997 end
  a = a + 1 * b - c % 4
  for j = 1, 2 do c = (c + j * a) % 997 end
  if 197 % 97 == 0 then s = s .. "197," end
  for j = 1, 2 do c = (c + j * a) % 997 end
  t[50] = a + 199
  t[1] = a + 200
  for j = 1, 3 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  t[4] = a + 203
  t.f24 = (t.f24 or 0) + c
  d = helper(d, 10) % 1000
  b = (b * 1.5 + a) % 100.25
  for j = 1, 2 do c = (c + j * a) % 997 end
  if a > 69 then b = b + 1 else c = c + 2 end
  d = helper(d, 1) % 1000
  t.f0 = (t.f0 or 0) + c
  d = helper(d, 3) % 1000
  d = helper(d, 4) % 1000
  for j = 1, 1 do c = (c + j * a) % 997 end
  t.f4 = (t.f4 or 0) + c
  t[16] = a + 215
  b = (b * 1.5 + a) % 100.25
  t[18] = a + 217
  for j = 1, 1 do c = (c + j * a) % 997 end
  if 219 % 97 == 0 then s = s .. "219," end
  t.f10 = (t.f10 or 0) + c
  a = a + 6 * b - c % 3
  for j = 1, 3 do c = (c + j * a) % 997 end
  t.f13 = (t.f13 or 0) + c
  d = helper(d, 3) % 1000
  for j = 1, 1 do c = (c + j * a) % 997 end
  t[27] = a + 226
  d = helper(d, 6) % 1000
  d = helper(d, 7) % 1000
  a = a + 4 * b - c % 5
  t[31] = a + 230
  t.f21 = (t.f21 or 0) + c
  t[33] = a + 232
  t[34] = a + 233
  a = a + 1 * b - c % 4
  for j = 1, 2 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  if a > 12 then b = b + 1 else c = c + 2 end
  for j = 1, 1 do c = (c + j * a) % 997 end
  if a > 22 then b = b + 1 else c = c + 2 end
  if a > 18 then b = b + 1 else c = c + 2 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  t[43] = a + 242
  t.f3 = (t.f3 or 0) + c
  if a > 26 then b = b + 1 else c = c + 2 end
  if a > 69 then b = b + 1 else c = c + 2 end
  a = a + 6 * b - c % 6
  d = helper(d, 0) % 1000
  if a > 38 then b = b + 1 else c = c + 2 end
  if 249 % 97 == 0 then s = s .. "249," end
  if a > 6 then b = b + 1 else c = c + 2 end
  d = helper(d, 4) % 1000
  t.f12 = (t.f12 or 0) + c
  t[4] = a + 253
  b = (b * 1.5 + a) % 100.25
  if 255 % 97 == 0 then s = s .. "255," end
  t.f16 = (t.f16 or 0) + c
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  a = a + 7 * b - c % 4
  if a > 33 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  a = a + 7 * b - c % 6
  a = a + 1 * b - c % 4
  if a > 75 then b = b + 1 else c = c + 2 end
  if a > 17 then b = b + 1 else c = c + 2 end
  t.f26 = (t.f26 or 0) + c
  t.f27 = (t.f27 or 0) + c
  if 268 % 97 == 0 then s = s .. "268," end
  if 269 % 97 == 0 then s = s .. "269," end
  if a > 78 then b = b + 1 else c = c + 2 end
  t[22] = a + 271
  d = helper(d, 12) % 1000
  b = (b * 1.5 + a) % 100.25
  a = a + 3 * b - c % 6
  for j = 1, 3 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 4) % 1000
  d = helper(d, 5) % 1000
  for j = 1, 2 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 8) % 1000
  if 282 % 97 == 0 then s = s .. "282," end
  for j = 1, 3 do c = (c + j * a) % 997 end
  t.f14 = (t.f14 or 0) + c
  d = helper(d, 12) % 1000
  a = a + 2 * b - c % 6
  for j = 1, 1 do c = (c + j * a) % 997 end
  d = helper(d, 2) % 1000
  t.f19 = (t.f19 or 0) + c
  t.f20 = (t.f20 or 0) + c
  t.f21 = (t.f21 or 0) + c
  for j = 1, 1 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  t[45] = a + 294
  t[46] = a + 295
  if 296 % 97 == 0 then s = s .. "296," end
  if a > 19 then b = b + 1 else c = c + 2 end
  t.f28 = (t.f28 or 0) + c
  if 299 % 97 == 0 then s = s .. "299," end
  d = helper(d, 1) % 1000
  a = a + 8 * b - c % 5
  for j = 1, 2 do c = (c + j * a) % 997 end
  if a > 69 then b = b + 1 else c = c + 2 end
  a = a + 2 * b - c % 4
  t[6] = a + 305
  t.f6 = (t.f6 or 0) + c
  t[8] = a + 307
  if a > 99 then b = b + 1 else c = c + 2 end
  t[10] = a + 309
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 12) % 1000
  if 312 % 97 == 0 then s = s .. "312," end
  if 313 % 97 == 0 then s = s .. "313," end
  if 314 % 97 == 0 then s = s .. "314," end
  if a > 41 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  if a > 79 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 7) % 1000
  t[21] = a + 320
  if 321 % 97 == 0 then s = s .. "321," end
  if 322 % 97 == 0 then s = s .. "322," end
  t[24] = a + 323
  t.f24 = (t.f24 or 0) + c
  t.f25 = (t.f25 or 0) + c
  d = helper(d, 1) % 1000
  if 327 % 97 == 0 then s = s .. "327," end
  a = a + 4 * b - c % 6
  b = (b * 1.5 + a) % 100.25
  a = a + 1 * b - c % 6
  d = helper(d, 6) % 1000
  t.f2 = (t.f2 or 0) + c
  d = helper(d, 8) % 1000
  if a > 36 then b = b + 1 else c = c + 2 end
  if a > 69 then b = b + 1 else c = c + 2 end
  d = helper(d, 11) % 1000
  t.f7 = (t.f7 or 0) + c
  t.f8 = (t.f8 or 0) + c
  t.f9 = (t.f9 or 0) + c
  b = (b * 1.5 + a) % 100.25
  if a > 69 then b = b + 1 else c = c + 2 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  if 343 % 97 == 0 then s = s .. "343," end
  t[45] = a + 344
  d = helper(d, 7) % 1000
  if 346 % 97 == 0 then s = s .. "346," end
  d = helper(d, 9) % 1000
  t.f18 = (t.f18 or 0) + c
  t[50] = a + 349
  a = a + 2 * b - c % 6
  a = a + 5 * b - c % 3
  t[3] = a + 352
  for j = 1, 3 do c = (c + j * a) % 997 end
  t.f24 = (t.f24 or 0) + c
  if 355 % 97 == 0 then s = s .. "355," end
  for j = 1, 3 do c = (c + j * a) % 997 end
  for j = 1, 1 do c = (c + j * a) % 997 end
  t[9] = a + 358
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  for j = 1, 2 do c = (c + j * a) % 997 end
  if 362 % 97 == 0 then s = s .. "362," end
  for j = 1, 3 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  t[16] = a + 365
  if 366 % 97 == 0 then s = s .. "366," end
  if 367 % 97 == 0 then s = s .. "367," end
  d = helper(d, 4) % 1000
  a = a + 5 * b - c % 6
  d = helper(d, 6) % 1000
  b = (b * 1.5 + a) % 100.25
  if 372 % 97 == 0 then s = s .. "372," end
  t.f13 = (t.f13 or 0) + c
  if a > 57 then b = b + 1 else c = c + 2 end
  d = helper(d, 11) % 1000
  for j = 1, 3 do c = (c + j * a) % 997 end
  a = a + 7 * b - c % 6
  if 378 % 97 == 0 then s = s .. "378," end
  if 379 % 97 == 0 then s = s .. "379," end
  for j = 1, 3 do c = (c + j * a) % 997 end
  t[32] = a + 381
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 6) % 1000
  t.f24 = (t.f24 or 0) + c
  a = a + 7 * b - c % 3
  if 386 % 97 == 0 then s = s .. "386," end
  t.f27 = (t.f27 or 0) + c
  if a > 98 then b = b + 1 else c = c + 2 end
  t[40] = a + 389
  a = a + 6 * b - c % 4
  if 391 % 97 == 0 then s = s .. "391," end
  t.f2 = (t.f2 or 0) + c
  if a > 59 then b = b + 1 else c = c + 2 end
  t.f4 = (t.f4 or 0) + c
  b = (b * 1.5 + a) % 100.25
  if a > 59 then b = b + 1 else c = c + 2 end
  a = a + 5 * b - c % 6
  t[49] = a + 398
  if 399 % 97 == 0 then s = s .. "399," end
  t[1] = a + 400
  for j = 1, 1 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  a = a + 3 * b - c % 6
  if a > 88 then b = b + 1 else c = c + 2 end
  t[6] = a + 405
  if 406 % 97 == 0 then s = s .. "406," end
  t.f17 = (t.f17 or 0) + c
  t.f18 = (t.f18 or 0) + c
  d = helper(d, 6) % 1000
  d = helper(d, 7) % 1000
  d = helper(d, 8) % 1000
  for j = 1, 2 do c = (c + j * a) % 997 end
  t[14] = a + 413
  t[15] = a + 414
  for j = 1, 2 do c = (c + j * a) % 997 end
  a = a + 3 * b - c % 4
  t.f27 = (t.f27 or 0) + c
  for j = 1, 3 do c = (c + j * a) % 997 end
  d = helper(d, 3) % 1000
  if 420 % 97 == 0 then s = s .. "420," end
  if 421 % 97 == 0 then s = s .. "421," end
  if a > 61 then b = b + 1 else c = c + 2 end
  t.f3 = (t.f3 or 0) + c
  for j = 1, 3 do c = (c + j * a) % 997 end
  d = helper(d, 9) % 1000
  t.f6 = (t.f6 or 0) + c
  d = helper(d, 11) % 1000
  a = a + 7 * b - c % 4
  if 429 % 97 == 0 then s = s .. "429," end
  d = helper(d, 1) % 1000
  t.f11 = (t.f11 or 0) + c
  d = helper(d, 3) % 1000
  t[34] = a + 433
  if a > 74 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  if a > 77 then b = b + 1 else c = c + 2 end
  t.f17 = (t.f17 or 0) + c
  b = (b * 1.5 + a) % 100.25
  if a > 17 then b = b + 1 else c = c + 2 end
  if a > 91 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  for j = 1, 2 do c = (c + j * a) % 997 end
  if 443 % 97 == 0 then s = s .. "443," end
  d = helper(d, 2) % 1000
  t[46] = a + 445
  d = helper(d, 4) % 1000
  t.f27 = (t.f27 or 0) + c
  t[49] = a + 448
  t[50] = a + 449
  d = helper(d, 8) % 1000
  if 451 % 97 == 0 then s = s .. "451," end
  for j = 1, 2 do c = (c + j * a) % 997 end
  t[4] = a + 453
  if a > 5 then b = b + 1 else c = c + 2 end
  a = a + 1 * b - c % 3
  a = a + 8 * b - c % 6
  b = (b * 1.5 + a) % 100.25
  for j = 1, 3 do c = (c + j * a) % 997 end
  t.f9 = (t.f9 or 0) + c
  t[11] = a + 460
  if a > 12 then b = b + 1 else c = c + 2 end
  d = helper(d, 7) % 1000
  if 463 % 97 == 0 then s = s .. "463," end
  d = helper(d, 9) % 1000
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  if 467 % 97 == 0 then s = s .. "467," end
  if a > 29 then b = b + 1 else c = c + 2 end
  d = helper(d, 1) % 1000
  t.f20 = (t.f20 or 0) + c
  b = (b * 1.5 + a) % 100.25
  if 472 % 97 == 0 then s = s .. "472," end
  d = helper(d, 5) % 1000
  b = (b * 1.5 + a) % 100.25
  t.f25 = (t.f25 or 0) + c
  for j = 1, 2 do c = (c + j * a) % 997 end
  t[28] = a + 477
  d = helper(d, 10) % 1000
  t[30] = a + 479
  a = a + 1 * b - c % 2
  b = (b * 1.5 + a) % 100.25
  for j = 1, 2 do c = (c + j * a) % 997 end
  t.f3 = (t.f3 or 0) + c
  d = helper(d, 3) % 1000
  if 485 % 97 == 0 then s = s .. "485," end
  if a > 97 then b = b + 1 else c = c + 2 end
  if a > 3 then b = b + 1 else c = c + 2 end
  a = a + 7 * b - c % 3
  a = a + 7 * b - c % 4
  if a > 10 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  t.f12 = (t.f12 or 0) + c
  a = a + 1 * b - c % 6
  a = a + 3 * b - c % 2
  t.f15 = (t.f15 or 0) + c
  t[47] = a + 496
  if 497 % 97 == 0 then s = s .. "497," end
  t[49] = a + 498
  d = helper(d, 5) % 1000
  local n = 0
  for k, v in pairs(t) do if type(v) == "number" then n = n + v end end
  return a, b, c, d, s, n, #t
end)
case("big_1", function()
  local t = {}
  local a, b, c, d = 1, 2.5, 3, 0
  local s = ""
  a = a + 2 * b - c % 2
  for j = 1, 1 do c = (c + j * a) % 997 end
  t.f2 = (t.f2 or 0) + c
  t.f3 = (t.f3 or 0) + c
  d = helper(d, 4) % 1000
  a = a + 3 * b - c % 5
  if 6 % 97 == 0 then s = s .. "6," end
  for j = 1, 3 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  t.f9 = (t.f9 or 0) + c
  a = a + 1 * b - c % 4
  b = (b * 1.5 + a) % 100.25
  for j = 1, 2 do c = (c + j * a) % 997 end
  if 13 % 97 == 0 then s = s .. "13," end
  if a > 71 then b = b + 1 else c = c + 2 end
  if a > 30 then b = b + 1 else c = c + 2 end
  d = helper(d, 3) % 1000
  a = a + 3 * b - c % 4
  if a > 17 then b = b + 1 else c = c + 2 end
  for j = 1, 3 do c = (c + j * a) % 997 end
  if a > 57 then b = b + 1 else c = c + 2 end
  if 21 % 97 == 0 then s = s .. "21," end
  for j = 1, 3 do c = (c + j * a) % 997 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  if a > 96 then b = b + 1 else c = c + 2 end
  if 26 % 97 == 0 then s = s .. "26," end
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 2) % 1000
  b = (b * 1.5 + a) % 100.25
  t.f0 = (t.f0 or 0) + c
  b = (b * 1.5 + a) % 100.25
  for j = 1, 3 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  for j = 1, 3 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 12) % 1000
  for j = 1, 3 do c = (c + j * a) % 997 end
  if a > 78 then b = b + 1 else c = c + 2 end
  t.f11 = (t.f11 or 0) + c
  b = (b * 1.5 + a) % 100.25
  t.f13 = (t.f13 or 0) + c
  t.f14 = (t.f14 or 0) + c
  if 45 % 97 == 0 then s = s .. "45," end
  t.f16 = (t.f16 or 0) + c
  d = helper(d, 8) % 1000
  b = (b * 1.5 + a) % 100.25
  for j = 1, 3 do c = (c + j * a) % 997 end
  t[1] = a + 50
  for j = 1, 3 do c = (c + j * a) % 997 end
  a = a + 4 * b - c % 2
  a = a + 1 * b - c % 4
  d = helper(d, 2) % 1000
  t[6] = a + 55
  if a > 34 then b = b + 1 else c = c + 2 end
  d = helper(d, 5) % 1000
  d = helper(d, 6) % 1000
  a = a + 7 * b - c % 2
  a = a + 6 * b - c % 4
  if a > 31 then b = b + 1 else c = c + 2 end
  a = a + 2 * b - c % 2
  t[14] = a + 63
  a = a + 1 * b - c % 2
  for j = 1, 2 do c = (c + j * a) % 997 end
  if a > 20 then b = b + 1 else c = c + 2 end
  if a > 66 then b = b + 1 else c = c + 2 end
  a = a + 7 * b - c % 6
  a = a + 4 * b - c % 3
  a = a + 1 * b - c % 4
  t[22] = a + 71
  t.f12 = (t.f12 or 0) + c
  for j = 1, 2 do c = (c + j * a) % 997 end
  a = a + 5 * b - c % 5
  a = a + 5 * b - c % 5
  if a > 60 then b = b + 1 else c = c + 2 end
  d = helper(d, 12) % 1000
  t[29] = a + 78
  for j = 1, 1 do c = (c + j * a) % 997 end
  a = a + 8 * b - c % 3
  if 81 % 97 == 0 then s = s .. "81," end
  b = (b * 1.5 + a) % 100.25
  for j = 1, 1 do c = (c + j * a) % 997 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  t.f25 = (t.f25 or 0) + c
  if 86 % 97 == 0 then s = s .. "86," end
  a = a + 3 * b - c % 2
  t.f28 = (t.f28 or 0) + c
  a = a + 3 * b - c % 3
  if a > 12 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 1) % 1000
  a = a + 4 * b - c % 3
  b = (b * 1.5 + a) % 100.25
  t[46] = a + 95
  t.f6 = (t.f6 or 0) + c
  t[48] = a + 97
  d = helper(d, 7) % 1000
  for j = 1, 2 do c = (c + j * a) % 997 end
  if 100 % 97 == 0 then s = s .. "100," end
  t.f11 = (t.f11 or 0) + c
  a = a + 3 * b - c % 2
  if 103 % 97 == 0 then s = s .. "103," end
  if 104 % 97 == 0 then s = s .. "104," end
  if a > 14 then b = b + 1 else c = c + 2 end
  t[7] = a + 106
  d = helper(d, 3) % 1000
  t[9] = a + 108
  t[10] = a + 109
  a = a + 3 * b - c % 3
  t[12] = a + 111
  d = helper(d, 8) % 1000
  a = a + 8 * b - c % 5
  t.f24 = (t.f24 or 0) + c
  if 115 % 97 == 0 then s = s .. "115," end
  d = helper(d, 12) % 1000
  d = helper(d, 0) % 1000
  if 118 % 97 == 0 then s = s .. "118," end
  if 119 % 97 == 0 then s = s .. "119," end
  a = a + 1 * b - c % 5
  if a > 12 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  for j = 1, 1 do c = (c + j * a) % 997 end
  t[25] = a + 124
  for j = 1, 2 do c = (c + j * a) % 997 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  a = a + 7 * b - c % 2
  t[29] = a + 128
  t.f9 = (t.f9 or 0) + c
  d = helper(d, 0) % 1000
  a = a + 8 * b - c % 2
  if 132 % 97 == 0 then s = s .. "132," end
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 5) % 1000
  t[37] = a + 136
  a = a + 5 * b - c % 2
  for j = 1, 2 do c = (c + j * a) % 997 end
  t[40] = a + 139
  d = helper(d, 10) % 1000
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 12) % 1000
  t[44] = a + 143
  for j = 1, 2 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  if a > 96 then b = b + 1 else c = c + 2 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  t[49] = a + 148
  t.f29 = (t.f29 or 0) + c
  t[1] = a + 150
  t[2] = a + 151
  t[3] = a + 152
  for j = 1, 3 do c = (c + j * a) % 997 end
  if 154 % 97 == 0 then s = s .. "154," end
  d = helper(d, 12) % 1000
  t[7] = a + 156
  a = a + 8 * b - c % 2
  b = (b * 1.5 + a) % 100.25
  t.f9 = (t.f9 or 0) + c
  for j = 1, 2 do c = (c + j * a) % 997 end
  if a > 47 then b = b + 1 else c = c + 2 end
  t.f12 = (t.f12 or 0) + c
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  if 165 % 97 == 0 then s = s .. "165," end
  b = (b * 1.5 + a) % 100.25
  t.f17 = (t.f17 or 0) + c
  if 168 % 97 == 0 then s = s .. "168," end
  d = helper(d, 0) % 1000
  if a > 62 then b = b + 1 else c = c + 2 end
  t.f21 = (t.f21 or 0) + c
  if 172 % 97 == 0 then s = s .. "172," end
  t[24] = a + 173
  t[25] = a + 174
  t[26] = a + 175
  for j = 1, 1 do c = (c + j * a) % 997 end
  if a > 53 then b = b + 1 else c = c + 2 end
  t[29] = a + 178
  t[30] = a + 179
  a = a + 3 * b - c % 4
  if 181 % 97 == 0 then s = s .. "181," end
  d = helper(d, 0) % 1000
  for j = 1, 2 do c = (c + j * a) % 997 end
  if a > 67 then b = b + 1 else c = c + 2 end
  t.f5 = (t.f5 or 0) + c
  t[37] = a + 186
  if a > 69 then b = b + 1 else c = c + 2 end
  if 188 % 97 == 0 then s = s .. "188," end
  t[40] = a + 189
  for j = 1, 3 do c = (c + j * a) % 997 end
  d = helper(d, 9) % 1000
  t.f12 = (t.f12 or 0) + c
  if a > 20 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 0) % 1000
  if 196 % 97 == 0 then s = s .. "196," end
  for j = 1, 3 do c = (c + j * a) % 997 end
  if a > 59 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  a = a + 7 * b - c % 3
  if 201 % 97 == 0 then s = s .. "201," end
  a = a + 8 * b - c % 4
  if 203 % 97 == 0 then s = s .. "203," end
  t.f24 = (t.f24 or 0) + c
  if 205 % 97 == 0 then s = s .. "205," end
  b = (b * 1.5 + a) % 100.25
  for j = 1, 3 do c = (c + j * a) % 997 end
  for j = 1, 3 do c = (c + j * a) % 997 end
  t[10] = a + 209
  d = helper(d, 2) % 1000
  d = helper(d, 3) % 1000
  if 212 % 97 == 0 then s = s .. "212," end
  if 213 % 97 == 0 then s = s .. "213," end
  a = a + 6 * b - c % 5
  b = (b * 1.5 + a) % 100.25
  if a > 12 then b = b + 1 else c = c + 2 end
  a = a + 7 * b - c % 3
  if 218 % 97 == 0 then s = s .. "218," end
  d = helper(d, 11) % 1000
  t[21] = a + 220
  if 221 % 97 == 0 then s = s .. "221," end
  d = helper(d, 1) % 1000
  t.f13 = (t.f13 or 0) + c
  d = helper(d, 3) % 1000
  b = (b * 1.5 + a) % 100.25
  if a > 1 then b = b + 1 else c = c + 2 end
  if 227 % 97 == 0 then s = s .. "227," end
  b = (b * 1.5 + a) % 100.25
  t.f19 = (t.f19 or 0) + c
  if a > 59 then b = b + 1 else c = c + 2 end
  d = helper(d, 10) % 1000
  t[33] = a + 232
  for j = 1, 1 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  t[36] = a + 235
  b = (b * 1.5 + a) % 100.25
  for j = 1, 2 do c = (c + j * a) % 997 end
  t.f28 = (t.f28 or 0) + c
  b = (b * 1.5 + a) % 100.25
  a = a + 2 * b - c % 6
  for j = 1, 1 do c = (c + j * a) % 997 end
  if 242 % 97 == 0 then s = s .. "242," end
  t.f3 = (t.f3 or 0) + c
  if a > 6 then b = b + 1 else c = c + 2 end
  if a > 63 then b = b + 1 else c = c + 2 end
  if 246 % 97 == 0 then s = s .. "246," end
  b = (b * 1.5 + a) % 100.25
  t.f8 = (t.f8 or 0) + c
  if a > 1 then b = b + 1 else c = c + 2 end
  t.f10 = (t.f10 or 0) + c
  b = (b * 1.5 + a) % 100.25
  a = a + 6 * b - c % 2
  if 253 % 97 == 0 then s = s .. "253," end
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 8) % 1000
  t.f16 = (t.f16 or 0) + c
  b = (b * 1.5 + a) % 100.25
  if a > 61 then b = b + 1 else c = c + 2 end
  t.f19 = (t.f19 or 0) + c
  t[11] = a + 260
  t.f21 = (t.f21 or 0) + c
  for j = 1, 2 do c = (c + j * a) % 997 end
  for j = 1, 3 do c = (c + j * a) % 997 end
  t.f24 = (t.f24 or 0) + c
  if 265 % 97 == 0 then s = s .. "265," end
  t[17] = a + 266
  d = helper(d, 7) % 1000
  if 268 % 97 == 0 then s = s .. "268," end
  if a > 64 then b = b + 1 else c = c + 2 end
  t[21] = a + 270
  t.f1 = (t.f1 or 0) + c
  a = a + 4 * b - c % 5
  d = helper(d, 0) % 1000
  t.f4 = (t.f4 or 0) + c
  a = a + 2 * b - c % 2
  if 276 % 97 == 0 then s = s .. "276," end
  for j = 1, 1 do c = (c + j * a) % 997 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  t[30] = a + 279
  for j = 1, 2 do c = (c + j * a) % 997 end
  for j = 1, 1 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  t.f14 = (t.f14 or 0) + c
  b = (b * 1.5 + a) % 100.25
  if a > 91 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 2) % 1000
  t.f19 = (t.f19 or 0) + c
  for j = 1, 1 do c = (c + j * a) % 997 end
  t[42] = a + 291
  d = helper(d, 6) % 1000
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 8) % 1000
  for j = 1, 1 do c = (c + j * a) % 997 end
  for j = 1, 1 do c = (c + j * a) % 997 end
  if a > 29 then b = b + 1 else c = c + 2 end
  t.f28 = (t.f28 or 0) + c
  if 299 % 97 == 0 then s = s .. "299," end
  if 300 % 97 == 0 then s = s .. "300," end
  for j = 1, 2 do c = (c + j * a) % 997 end
  for j = 1, 3 do c = (c + j * a) % 997 end
  if 303 % 97 == 0 then s = s .. "303," end
  t.f4 = (t.f4 or 0) + c
  t[6] = a + 305
  for j = 1, 2 do c = (c + j * a) % 997 end
  if 307 % 97 == 0 then s = s .. "307," end
  b = (b * 1.5 + a) % 100.25
  if a > 33 then b = b + 1 else c = c + 2 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  t[13] = a + 312
  if a > 40 then b = b + 1 else c = c + 2 end
  if 314 % 97 == 0 then s = s .. "314," end
  if a > 3 then b = b + 1 else c = c + 2 end
  t[17] = a + 316
  for j = 1, 1 do c = (c + j * a) % 997 end
  for j = 1, 1 do c = (c + j * a) % 997 end
  if 319 % 97 == 0 then s = s .. "319," end
  a = a + 6 * b - c % 3
  if 321 % 97 == 0 then s = s .. "321," end
  t.f22 = (t.f22 or 0) + c
  b = (b * 1.5 + a) % 100.25
  if a > 46 then b = b + 1 else c = c + 2 end
  for j = 1, 1 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  t[28] = a + 327
  if a > 26 then b = b + 1 else c = c + 2 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  if a > 53 then b = b + 1 else c = c + 2 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  t[33] = a + 332
  for j = 1, 1 do c = (c + j * a) % 997 end
  d = helper(d, 9) % 1000
  d = helper(d, 10) % 1000
  a = a + 6 * b - c % 4
  a = a + 3 * b - c % 3
  t[39] = a + 338
  if 339 % 97 == 0 then s = s .. "339," end
  b = (b * 1.5 + a) % 100.25
  t.f11 = (t.f11 or 0) + c
  if a > 41 then b = b + 1 else c = c + 2 end
  t[44] = a + 343
  for j = 1, 3 do c = (c + j * a) % 997 end
  if 345 % 97 == 0 then s = s .. "345," end
  d = helper(d, 8) % 1000
  a = a + 7 * b - c % 5
  b = (b * 1.5 + a) % 100.25
  for j = 1, 3 do c = (c + j * a) % 997 end
  t[1] = a + 350
  b = (b * 1.5 + a) % 100.25
  if 352 % 97 == 0 then s = s .. "352," end
  b = (b * 1.5 + a) % 100.25
  if a > 52 then b = b + 1 else c = c + 2 end
  if 355 % 97 == 0 then s = s .. "355," end
  b = (b * 1.5 + a) % 100.25
  a = a + 2 * b - c % 5
  if a > 15 then b = b + 1 else c = c + 2 end
  if a > 9 then b = b + 1 else c = c + 2 end
  if 360 % 97 == 0 then s = s .. "360," end
  t.f1 = (t.f1 or 0) + c
  b = (b * 1.5 + a) % 100.25
  a = a + 5 * b - c % 2
  for j = 1, 1 do c = (c + j * a) % 997 end
  if a > 3 then b = b + 1 else c = c + 2 end
  if a > 54 then b = b + 1 else c = c + 2 end
  t[18] = a + 367
  for j = 1, 3 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  a = a + 8 * b - c % 3
  t[22] = a + 371
  b = (b * 1.5 + a) % 100.25
  if a > 71 then b = b + 1 else c = c + 2 end
  a = a + 3 * b - c % 6
  a = a + 1 * b - c % 3
  a = a + 6 * b - c % 6
  d = helper(d, 0) % 1000
  if a > 47 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  a = a + 3 * b - c % 6
  t[32] = a + 381
  d = helper(d, 5) % 1000
  t[34] = a + 383
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 8) % 1000
  a = a + 4 * b - c % 5
  for j = 1, 3 do c = (c + j * a) % 997 end
  if 388 % 97 == 0 then s = s .. "388," end
  if a > 65 then b = b + 1 else c = c + 2 end
  t[41] = a + 390
  if a > 80 then b = b + 1 else c = c + 2 end
  d = helper(d, 2) % 1000
  if a > 48 then b = b + 1 else c = c + 2 end
  d = helper(d, 4) % 1000
  t.f5 = (t.f5 or 0) + c
  for j = 1, 2 do c = (c + j * a) % 997 end
  if a > 54 then b = b + 1 else c = c + 2 end
  if a > 50 then b = b + 1 else c = c + 2 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  t[1] = a + 400
  t[2] = a + 401
  b = (b * 1.5 + a) % 100.25
  t.f13 = (t.f13 or 0) + c
  t.f14 = (t.f14 or 0) + c
  b = (b * 1.5 + a) % 100.25
  t.f16 = (t.f16 or 0) + c
  d = helper(d, 4) % 1000
  if 408 % 97 == 0 then s = s .. "408," end
  if a > 89 then b = b + 1 else c = c + 2 end
  t[11] = a + 410
  a = a + 4 * b - c % 3
  d = helper(d, 9) % 1000
  if 413 % 97 == 0 then s = s .. "413," end
  a = a + 3 * b - c % 2
  t.f25 = (t.f25 or 0) + c
  b = (b * 1.5 + a) % 100.25
  a = a + 4 * b - c % 3
  for j = 1, 1 do c = (c + j * a) % 997 end
  d = helper(d, 3) % 1000
  t[21] = a + 420
  if a > 81 then b = b + 1 else c = c + 2 end
  if a > 97 then b = b + 1 else c = c + 2 end
  t[24] = a + 423
  b = (b * 1.5 + a) % 100.25
  t.f5 = (t.f5 or 0) + c
  d = helper(d, 10) % 1000
  if a > 41 then b = b + 1 else c = c + 2 end
  t.f8 = (t.f8 or 0) + c
  t[30] = a + 429
  if 430 % 97 == 0 then s = s .. "430," end
  if 431 % 97 == 0 then s = s .. "431," end
  a = a + 8 * b - c % 4
  t[34] = a + 433
  t.f14 = (t.f14 or 0) + c
  a = a + 4 * b - c % 5
  for j = 1, 2 do c = (c + j * a) % 997 end
  if 437 % 97 == 0 then s = s .. "437," end
  d = helper(d, 9) % 1000
  if 439 % 97 == 0 then s = s .. "439," end
  if a > 88 then b = b + 1 else c = c + 2 end
  if a > 57 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  for j = 1, 2 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  t.f25 = (t.f25 or 0) + c
  d = helper(d, 4) % 1000
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 7) % 1000
  b = (b * 1.5 + a) % 100.25
  for j = 1, 2 do c = (c + j * a) % 997 end
  t[3] = a + 452
  if a > 47 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 0) % 1000
  if a > 87 then b = b + 1 else c = c + 2 end
  t.f7 = (t.f7 or 0) + c
  d = helper(d, 3) % 1000
  t.f9 = (t.f9 or 0) + c
  t[11] = a + 460
  a = a + 1 * b - c % 3
  for j = 1, 1 do c = (c + j * a) % 997 end
  for j = 1, 3 do c = (c + j * a) % 997 end
  t.f14 = (t.f14 or 0) + c
  for j = 1, 2 do c = (c + j * a) % 997 end
  t[17] = a + 466
  if 467 % 97 == 0 then s = s .. "467," end
  b = (b * 1.5 + a) % 100.25
  a = a + 5 * b - c % 6
  if a > 27 then b = b + 1 else c = c + 2 end
  if a > 20 then b = b + 1 else c = c + 2 end
  if 472 % 97 == 0 then s = s .. "472," end
  t[24] = a + 473
  b = (b * 1.5 + a) % 100.25
  t.f25 = (t.f25 or 0) + c
  t[27] = a + 476
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 11) % 1000
  if a > 72 then b = b + 1 else c = c + 2 end
  t.f1 = (t.f1 or 0) + c
  d = helper(d, 1) % 1000
  d = helper(d, 2) % 1000
  for j = 1, 3 do c = (c + j * a) % 997 end
  if 485 % 97 == 0 then s = s .. "485," end
  if 486 % 97 == 0 then s = s .. "486," end
  d = helper(d, 6) % 1000
  d = helper(d, 7) % 1000
  a = a + 5 * b - c % 3
  if a > 79 then b = b + 1 else c = c + 2 end
  if 491 % 97 == 0 then s = s .. "491," end
  if 492 % 97 == 0 then s = s .. "492," end
  t[44] = a + 493
  b = (b * 1.5 + a) % 100.25
  if 495 % 97 == 0 then s = s .. "495," end
  if 496 % 97 == 0 then s = s .. "496," end
  b = (b * 1.5 + a) % 100.25
  if 498 % 97 == 0 then s = s .. "498," end
  t.f19 = (t.f19 or 0) + c
  d = helper(d, 6) % 1000
  d = helper(d, 7) % 1000
  d = helper(d, 8) % 1000
  a = a + 2 * b - c % 6
  a = a + 1 * b - c % 5
  if 505 % 97 == 0 then s = s .. "505," end
  if 506 % 97 == 0 then s = s .. "506," end
  d = helper(d, 0) % 1000
  t.f28 = (t.f28 or 0) + c
  t[10] = a + 509
  for j = 1, 3 do c = (c + j * a) % 997 end
  for j = 1, 3 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  t[14] = a + 513
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 8) % 1000
  t.f6 = (t.f6 or 0) + c
  a = a + 1 * b - c % 5
  a = a + 3 * b - c % 3
  d = helper(d, 12) % 1000
  for j = 1, 1 do c = (c + j * a) % 997 end
  a = a + 3 * b - c % 4
  t[23] = a + 522
  t[24] = a + 523
  if a > 55 then b = b + 1 else c = c + 2 end
  if a > 4 then b = b + 1 else c = c + 2 end
  t.f16 = (t.f16 or 0) + c
  t.f17 = (t.f17 or 0) + c
  b = (b * 1.5 + a) % 100.25
  a = a + 6 * b - c % 4
  t[31] = a + 530
  for j = 1, 1 do c = (c + j * a) % 997 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  t.f23 = (t.f23 or 0) + c
  b = (b * 1.5 + a) % 100.25
  t.f25 = (t.f25 or 0) + c
  if a > 3 then b = b + 1 else c = c + 2 end
  a = a + 6 * b - c % 5
  a = a + 6 * b - c % 6
  a = a + 2 * b - c % 6
  if 540 % 97 == 0 then s = s .. "540," end
  if 541 % 97 == 0 then s = s .. "541," end
  if 542 % 97 == 0 then s = s .. "542," end
  d = helper(d, 10) % 1000
  if a > 20 then b = b + 1 else c = c + 2 end
  a = a + 1 * b - c % 6
  for j = 1, 3 do c = (c + j * a) % 997 end
  if a > 37 then b = b + 1 else c = c + 2 end
  a = a + 1 * b - c % 3
  d = helper(d, 3) % 1000
  if 550 % 97 == 0 then s = s .. "550," end
  t[2] = a + 551
  for j = 1, 1 do c = (c + j * a) % 997 end
  t[4] = a + 553
  d = helper(d, 8) % 1000
  d = helper(d, 9) % 1000
  d = helper(d, 10) % 1000
  t[8] = a + 557
  a = a + 7 * b - c % 2
  t.f19 = (t.f19 or 0) + c
  d = helper(d, 1) % 1000
  a = a + 7 * b - c % 5
  if a > 19 then b = b + 1 else c = c + 2 end
  if 563 % 97 == 0 then s = s .. "563," end
  if a > 58 then b = b + 1 else c = c + 2 end
  for j = 1, 1 do c = (c + j * a) % 997 end
  if a > 66 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  if 568 % 97 == 0 then s = s .. "568," end
  b = (b * 1.5 + a) % 100.25
  if a > 63 then b = b + 1 else c = c + 2 end
  if a > 44 then b = b + 1 else c = c + 2 end
  if a > 2 then b = b + 1 else c = c + 2 end
  t.f3 = (t.f3 or 0) + c
  if a > 19 then b = b + 1 else c = c + 2 end
  if 575 % 97 == 0 then s = s .. "575," end
  t.f6 = (t.f6 or 0) + c
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 8) % 1000
  if 581 % 97 == 0 then s = s .. "581," end
  if 582 % 97 == 0 then s = s .. "582," end
  t.f13 = (t.f13 or 0) + c
  d = helper(d, 12) % 1000
  for j = 1, 3 do c = (c + j * a) % 997 end
  a = a + 7 * b - c % 6
  a = a + 7 * b - c % 4
  a = a + 8 * b - c % 6
  t.f19 = (t.f19 or 0) + c
  t.f20 = (t.f20 or 0) + c
  d = helper(d, 6) % 1000
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  for j = 1, 3 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 11) % 1000
  if a > 58 then b = b + 1 else c = c + 2 end
  t.f28 = (t.f28 or 0) + c
  for j = 1, 2 do c = (c + j * a) % 997 end
  t[1] = a + 600
  d = helper(d, 3) % 1000
  if 602 % 97 == 0 then s = s .. "602," end
  t[4] = a + 603
  if 604 % 97 == 0 then s = s .. "604," end
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  t[8] = a + 607
  if 608 % 97 == 0 then s = s .. "608," end
  b = (b * 1.5 + a) % 100.25
  for j = 1, 3 do c = (c + j * a) % 997 end
  for j = 1, 1 do c = (c + j * a) % 997 end
  if a > 29 then b = b + 1 else c = c + 2 end
  if a > 52 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  if a > 52 then b = b + 1 else c = c + 2 end
  t.f17 = (t.f17 or 0) + c
  for j = 1, 3 do c = (c + j * a) % 997 end
  if 619 % 97 == 0 then s = s .. "619," end
  t.f20 = (t.f20 or 0) + c
  t.f21 = (t.f21 or 0) + c
  for j = 1, 3 do c = (c + j * a) % 997 end
  a = a + 7 * b - c % 6
  a = a + 4 * b - c % 5
  t[26] = a + 625
  t[27] = a + 626
  a = a + 6 * b - c % 4
  for j = 1, 3 do c = (c + j * a) % 997 end
  if 629 % 97 == 0 then s = s .. "629," end
  if a > 95 then b = b + 1 else c = c + 2 end
  for j = 1, 1 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  if 633 % 97 == 0 then s = s .. "633," end
  d = helper(d, 10) % 1000
  b = (b * 1.5 + a) % 100.25
  t[37] = a + 636
  a = a + 6 * b - c % 2
  t.f8 = (t.f8 or 0) + c
  b = (b * 1.5 + a) % 100.25
  if a > 91 then b = b + 1 else c = c + 2 end
  a = a + 1 * b - c % 4
  if 642 % 97 == 0 then s = s .. "642," end
  b = (b * 1.5 + a) % 100.25
  a = a + 8 * b - c % 6
  d = helper(d, 8) % 1000
  d = helper(d, 9) % 1000
  for j = 1, 1 do c = (c + j * a) % 997 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  if 649 % 97 == 0 then s = s .. "649," end
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  t.f22 = (t.f22 or 0) + c
  t[4] = a + 653
  d = helper(d, 4) % 1000
  for j = 1, 1 do c = (c + j * a) % 997 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  if a > 30 then b = b + 1 else c = c + 2 end
  d = helper(d, 8) % 1000
  d = helper(d, 9) % 1000
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 11) % 1000
  if 662 % 97 == 0 then s = s .. "662," end
  t.f3 = (t.f3 or 0) + c
  d = helper(d, 1) % 1000
  if a > 0 then b = b + 1 else c = c + 2 end
  if 666 % 97 == 0 then s = s .. "666," end
  b = (b * 1.5 + a) % 100.25
  for j = 1, 3 do c = (c + j * a) % 997 end
  if a > 86 then b = b + 1 else c = c + 2 end
  d = helper(d, 7) % 1000
  if a > 62 then b = b + 1 else c = c + 2 end
  a = a + 3 * b - c % 3
  d = helper(d, 10) % 1000
  a = a + 2 * b - c % 5
  d = helper(d, 12) % 1000
  if a > 34 then b = b + 1 else c = c + 2 end
  if 677 % 97 == 0 then s = s .. "677," end
  a = a + 1 * b - c % 4
  t[30] = a + 679
  t.f20 = (t.f20 or 0) + c
  a = a + 6 * b - c % 5
  if 682 % 97 == 0 then s = s .. "682," end
  t[34] = a + 683
  t[35] = a + 684
  b = (b * 1.5 + a) % 100.25
  if a > 18 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  t[39] = a + 688
  t.f29 = (t.f29 or 0) + c
  if a > 61 then b = b + 1 else c = c + 2 end
  t[42] = a + 691
  t.f2 = (t.f2 or 0) + c
  t.f3 = (t.f3 or 0) + c
  a = a + 4 * b - c % 2
  if 695 % 97 == 0 then s = s .. "695," end
  if a > 23 then b = b + 1 else c = c + 2 end
  t.f7 = (t.f7 or 0) + c
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 10) % 1000
  a = a + 6 * b - c % 5
  a = a + 3 * b - c % 3
  for j = 1, 3 do c = (c + j * a) % 997 end
  t.f13 = (t.f13 or 0) + c
  t[5] = a + 704
  d = helper(d, 3) % 1000
  if a > 28 then b = b + 1 else c = c + 2 end
  a = a + 6 * b - c % 2
  t.f18 = (t.f18 or 0) + c
  t[10] = a + 709
  for j = 1, 1 do c = (c + j * a) % 997 end
  if a > 44 then b = b + 1 else c = c + 2 end
  if a > 54 then b = b + 1 else c = c + 2 end
  d = helper(d, 11) % 1000
  t.f24 = (t.f24 or 0) + c
  a = a + 1 * b - c % 4
  if a > 92 then b = b + 1 else c = c + 2 end
  t[18] = a + 717
  a = a + 3 * b - c % 5
  if 719 % 97 == 0 then s = s .. "719," end
  for j = 1, 2 do c = (c + j * a) % 997 end
  t.f1 = (t.f1 or 0) + c
  t.f2 = (t.f2 or 0) + c
  t[24] = a + 723
  if a > 94 then b = b + 1 else c = c + 2 end
  t[26] = a + 725
  a = a + 2 * b - c % 3
  for j = 1, 3 do c = (c + j * a) % 997 end
  if a > 38 then b = b + 1 else c = c + 2 end
  if a > 11 then b = b + 1 else c = c + 2 end
  d = helper(d, 2) % 1000
  d = helper(d, 3) % 1000
  for j = 1, 1 do c = (c + j * a) % 997 end
  if a > 85 then b = b + 1 else c = c + 2 end
  if 734 % 97 == 0 then s = s .. "734," end
  d = helper(d, 7) % 1000
  if 736 % 97 == 0 then s = s .. "736," end
  a = a + 6 * b - c % 5
  b = (b * 1.5 + a) % 100.25
  for j = 1, 1 do c = (c + j * a) % 997 end
  t[41] = a + 740
  a = a + 3 * b - c % 5
  if a > 53 then b = b + 1 else c = c + 2 end
  d = helper(d, 2) % 1000
  t.f24 = (t.f24 or 0) + c
  t[46] = a + 745
  t[47] = a + 746
  if a > 25 then b = b + 1 else c = c + 2 end
  t[49] = a + 748
  for j = 1, 1 do c = (c + j * a) % 997 end
  d = helper(d, 9) % 1000
  d = helper(d, 10) % 1000
  b = (b * 1.5 + a) % 100.25
  if 753 % 97 == 0 then s = s .. "753," end
  b = (b * 1.5 + a) % 100.25
  t[6] = a + 755
  for j = 1, 1 do c = (c + j * a) % 997 end
  a = a + 6 * b - c % 2
  if 758 % 97 == 0 then s = s .. "758," end
  a = a + 2 * b - c % 4
  for j = 1, 3 do c = (c + j * a) % 997 end
  d = helper(d, 7) % 1000
  for j = 1, 2 do c = (c + j * a) % 997 end
  d = helper(d, 9) % 1000
  a = a + 3 * b - c % 3
  if a > 93 then b = b + 1 else c = c + 2 end
  if a > 50 then b = b + 1 else c = c + 2 end
  if a > 13 then b = b + 1 else c = c + 2 end
  t.f18 = (t.f18 or 0) + c
  if 769 % 97 == 0 then s = s .. "769," end
  b = (b * 1.5 + a) % 100.25
  a = a + 2 * b - c % 5
  a = a + 2 * b - c % 2
  d = helper(d, 6) % 1000
  if 774 % 97 == 0 then s = s .. "774," end
  a = a + 4 * b - c % 5
  for j = 1, 1 do c = (c + j * a) % 997 end
  if a > 34 then b = b + 1 else c = c + 2 end
  t.f28 = (t.f28 or 0) + c
  t.f29 = (t.f29 or 0) + c
  d = helper(d, 0) % 1000
  t.f1 = (t.f1 or 0) + c
  t.f2 = (t.f2 or 0) + c
  if 783 % 97 == 0 then s = s .. "783," end
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  if 786 % 97 == 0 then s = s .. "786," end
  t.f7 = (t.f7 or 0) + c
  if 788 % 97 == 0 then s = s .. "788," end
  if a > 83 then b = b + 1 else c = c + 2 end
  if 790 % 97 == 0 then s = s .. "790," end
  t.f11 = (t.f11 or 0) + c
  b = (b * 1.5 + a) % 100.25
  if 793 % 97 == 0 then s = s .. "793," end
  for j = 1, 3 do c = (c + j * a) % 997 end
  t[46] = a + 795
  a = a + 5 * b - c % 5
  b = (b * 1.5 + a) % 100.25
  t[49] = a + 798
  b = (b * 1.5 + a) % 100.25
  local n = 0
  for k, v in pairs(t) do if type(v) == "number" then n = n + v end end
  return a, b, c, d, s, n, #t
end)
case("big_error", function()
  local t = {}
  local a, b, c, d = 1, 2.5, 3, 0
  local s = ""
  d = helper(d, 0) % 1000
  if a > 47 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  t[4] = a + 3
  a = a + 8 * b - c % 4
  d = helper(d, 5) % 1000
  d = helper(d, 6) % 1000
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  if 9 % 97 == 0 then s = s .. "9," end
  if a > 29 then b = b + 1 else c = c + 2 end
  if a > 66 then b = b + 1 else c = c + 2 end
  if 12 % 97 == 0 then s = s .. "12," end
  a = a + 2 * b - c % 3
  a = a + 5 * b - c % 2
  t.f15 = (t.f15 or 0) + c
  b = (b * 1.5 + a) % 100.25
  if 17 % 97 == 0 then s = s .. "17," end
  if 18 % 97 == 0 then s = s .. "18," end
  if 19 % 97 == 0 then s = s .. "19," end
  b = (b * 1.5 + a) % 100.25
  if a > 46 then b = b + 1 else c = c + 2 end
  t[23] = a + 22
  a = a + 3 * b - c % 5
  d = helper(d, 11) % 1000
  t.f25 = (t.f25 or 0) + c
  if 26 % 97 == 0 then s = s .. "26," end
  t.f27 = (t.f27 or 0) + c
  if 28 % 97 == 0 then s = s .. "28," end
  if 29 % 97 == 0 then s = s .. "29," end
  for j = 1, 3 do c = (c + j * a) % 997 end
  if 31 % 97 == 0 then s = s .. "31," end
  d = helper(d, 6) % 1000
  for j = 1, 3 do c = (c + j * a) % 997 end
  a = a + 5 * b - c % 6
  if a > 89 then b = b + 1 else c = c + 2 end
  for j = 1, 3 do c = (c + j * a) % 997 end
  t[38] = a + 37
  d = helper(d, 12) % 1000
  t.f9 = (t.f9 or 0) + c
  t.f10 = (t.f10 or 0) + c
  t[42] = a + 41
  t[43] = a + 42
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  t[46] = a + 45
  for j = 1, 1 do c = (c + j * a) % 997 end
  if 47 % 97 == 0 then s = s .. "47," end
  if a > 2 then b = b + 1 else c = c + 2 end
  t.f19 = (t.f19 or 0) + c
  if 50 % 97 == 0 then s = s .. "50," end
  if 51 % 97 == 0 then s = s .. "51," end
  t[3] = a + 52
  a = a + 1 * b - c % 5
  for j = 1, 3 do c = (c + j * a) % 997 end
  t.f25 = (t.f25 or 0) + c
  d = helper(d, 4) % 1000
  a = a + 5 * b - c % 2
  t[9] = a + 58
  t[10] = a + 59
  a = a + 4 * b - c % 5
  t.f1 = (t.f1 or 0) + c
  t.f2 = (t.f2 or 0) + c
  if a > 88 then b = b + 1 else c = c + 2 end
  a = a + 6 * b - c % 4
  for j = 1, 1 do c = (c + j * a) % 997 end
  if 66 % 97 == 0 then s = s .. "66," end
  if 67 % 97 == 0 then s = s .. "67," end
  b = (b * 1.5 + a) % 100.25
  if 69 % 97 == 0 then s = s .. "69," end
  t[21] = a + 70
  t.f11 = (t.f11 or 0) + c
  if 72 % 97 == 0 then s = s .. "72," end
  d = helper(d, 8) % 1000
  t.f14 = (t.f14 or 0) + c
  if 75 % 97 == 0 then s = s .. "75," end
  t.f16 = (t.f16 or 0) + c
  t.f17 = (t.f17 or 0) + c
  for j = 1, 1 do c = (c + j * a) % 997 end
  if 79 % 97 == 0 then s = s .. "79," end
  for j = 1, 1 do c = (c + j * a) % 997 end
  if 81 % 97 == 0 then s = s .. "81," end
  if a > 7 then b = b + 1 else c = c + 2 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  for j = 1, 3 do c = (c + j * a) % 997 end
  for j = 1, 3 do c = (c + j * a) % 997 end
  t.f26 = (t.f26 or 0) + c
  b = (b * 1.5 + a) % 100.25
  a = a + 1 * b - c % 2
  for j = 1, 2 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  t.f1 = (t.f1 or 0) + c
  for j = 1, 1 do c = (c + j * a) % 997 end
  for j = 1, 1 do c = (c + j * a) % 997 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  t.f5 = (t.f5 or 0) + c
  t.f6 = (t.f6 or 0) + c
  if 97 % 97 == 0 then s = s .. "97," end
  t[49] = a + 98
  a = a + 3 * b - c % 4
  d = helper(d, 9) % 1000
  t.f11 = (t.f11 or 0) + c
  d = helper(d, 11) % 1000
  for j = 1, 1 do c = (c + j * a) % 997 end
  if 104 % 97 == 0 then s = s .. "104," end
  t[6] = a + 105
  t[7] = a + 106
  for j = 1, 2 do c = (c + j * a) % 997 end
  d = helper(d, 4) % 1000
  b = (b * 1.5 + a) % 100.25
  if a > 10 then b = b + 1 else c = c + 2 end
  for j = 1, 3 do c = (c + j * a) % 997 end
  d = helper(d, 8) % 1000
  b = (b * 1.5 + a) % 100.25
  t.f24 = (t.f24 or 0) + c
  d = helper(d, 11) % 1000
  t[17] = a + 116
  a = a + 4 * b - c % 4
  if a > 35 then b = b + 1 else c = c + 2 end
  for j = 1, 3 do c = (c + j * a) % 997 end
  t[21] = a + 120
  for j = 1, 3 do c = (c + j * a) % 997 end
  if a > 53 then b = b + 1 else c = c + 2 end
  t.f3 = (t.f3 or 0) + c
  t.f4 = (t.f4 or 0) + c
  b = (b * 1.5 + a) % 100.25
  for j = 1, 3 do c = (c + j * a) % 997 end
  if 127 % 97 == 0 then s = s .. "127," end
  t.f8 = (t.f8 or 0) + c
  if 129 % 97 == 0 then s = s .. "129," end
  if 130 % 97 == 0 then s = s .. "130," end
  a = a + 7 * b - c % 3
  d = helper(d, 2) % 1000
  a = a + 8 * b - c % 6
  if 134 % 97 == 0 then s = s .. "134," end
  d = helper(d, 5) % 1000
  a = a + 8 * b - c % 6
  t.f17 = (t.f17 or 0) + c
  for j = 1, 1 do c = (c + j * a) % 997 end
  t[40] = a + 139
  t.f20 = (t.f20 or 0) + c
  t[42] = a + 141
  d = helper(d, 12) % 1000
  a = a + 1 * b - c % 6
  d = helper(d, 1) % 1000
  if 145 % 97 == 0 then s = s .. "145," end
  a = a + 1 * b - c % 5
  t[48] = a + 147
  if a > 64 then b = b + 1 else c = c + 2 end
  t.f29 = (t.f29 or 0) + c
  d = helper(d, 7) % 1000
  a = a + 7 * b - c % 2
  t[3] = a + 152
  for j = 1, 1 do c = (c + j * a) % 997 end
  t.f4 = (t.f4 or 0) + c
  b = (b * 1.5 + a) % 100.25
  a = a + 6 * b - c % 3
  d = helper(d, 1) % 1000
  t[9] = a + 158
  t[10] = a + 159
  if a > 30 then b = b + 1 else c = c + 2 end
  t.f11 = (t.f11 or 0) + c
  if a > 0 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  if 164 % 97 == 0 then s = s .. "164," end
  a = a + 5 * b - c % 3
  t.f16 = (t.f16 or 0) + c
  if 167 % 97 == 0 then s = s .. "167," end
  a = a + 8 * b - c % 4
  a = a + 1 * b - c % 3
  a = a + 2 * b - c % 2
  t[22] = a + 171
  b = (b * 1.5 + a) % 100.25
  a = a + 2 * b - c % 6
  b = (b * 1.5 + a) % 100.25
  for j = 1, 1 do c = (c + j * a) % 997 end
  for j = 1, 1 do c = (c + j * a) % 997 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  if 178 % 97 == 0 then s = s .. "178," end
  t.f29 = (t.f29 or 0) + c
  for j = 1, 2 do c = (c + j * a) % 997 end
  d = helper(d, 12) % 1000
  for j = 1, 2 do c = (c + j * a) % 997 end
  t[34] = a + 183
  if a > 71 then b = b + 1 else c = c + 2 end
  a = a + 7 * b - c % 2
  if a > 5 then b = b + 1 else c = c + 2 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  if 188 % 97 == 0 then s = s .. "188," end
  a = a + 7 * b - c % 2
  for j = 1, 3 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  for j = 1, 2 do c = (c + j * a) % 997 end
  if 193 % 97 == 0 then s = s .. "193," end
  b = (b * 1.5 + a) % 100.25
  a = a + 4 * b - c % 3
  t.f16 = (t.f16 or 0) + c
  t[48] = a + 197
  if 198 % 97 == 0 then s = s .. "198," end
  d = helper(d, 4) % 1000
  if 200 % 97 == 0 then s = s .. "200," end
  if a > 3 then b = b + 1 else c = c + 2 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  t.f23 = (t.f23 or 0) + c
  t[5] = a + 204
  b = (b * 1.5 + a) % 100.25
  t[7] = a + 206
  if 207 % 97 == 0 then s = s .. "207," end
  t[9] = a + 208
  for j = 1, 3 do c = (c + j * a) % 997 end
  t[11] = a + 210
  a = a + 8 * b - c % 3
  d = helper(d, 4) % 1000
  if 213 % 97 == 0 then s = s .. "213," end
  a = a + 2 * b - c % 6
  t[16] = a + 215
  if 216 % 97 == 0 then s = s .. "216," end
  if a > 3 then b = b + 1 else c = c + 2 end
  for j = 1, 1 do c = (c + j * a) % 997 end
  a = a + 2 * b - c % 5
  t.f10 = (t.f10 or 0) + c
  t.f11 = (t.f11 or 0) + c
  t[23] = a + 222
  a = a + 4 * b - c % 2
  t[25] = a + 224
  a = a + 6 * b - c % 6
  if a > 9 then b = b + 1 else c = c + 2 end
  d = helper(d, 6) % 1000
  if a > 82 then b = b + 1 else c = c + 2 end
  d = helper(d, 8) % 1000
  b = (b * 1.5 + a) % 100.25
  if 231 % 97 == 0 then s = s .. "231," end
  t.f22 = (t.f22 or 0) + c
  for j = 1, 3 do c = (c + j * a) % 997 end
  if 234 % 97 == 0 then s = s .. "234," end
  for j = 1, 3 do c = (c + j * a) % 997 end
  if 236 % 97 == 0 then s = s .. "236," end
  t[38] = a + 237
  if 238 % 97 == 0 then s = s .. "238," end
  d = helper(d, 5) % 1000
  if 240 % 97 == 0 then s = s .. "240," end
  if a > 53 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  if a > 82 then b = b + 1 else c = c + 2 end
  if 244 % 97 == 0 then s = s .. "244," end
  if a > 20 then b = b + 1 else c = c + 2 end
  t[47] = a + 246
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  if a > 17 then b = b + 1 else c = c + 2 end
  t.f11 = (t.f11 or 0) + c
  d = helper(d, 5) % 1000
  if a > 74 then b = b + 1 else c = c + 2 end
  for j = 1, 1 do c = (c + j * a) % 997 end
  t.f15 = (t.f15 or 0) + c
  if 256 % 97 == 0 then s = s .. "256," end
  t.f17 = (t.f17 or 0) + c
  d = helper(d, 11) % 1000
  t.f19 = (t.f19 or 0) + c
  a = a + 5 * b - c % 5
  if 261 % 97 == 0 then s = s .. "261," end
  d = helper(d, 2) % 1000
  if a > 72 then b = b + 1 else c = c + 2 end
  for j = 1, 1 do c = (c + j * a) % 997 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  if a > 53 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 8) % 1000
  b = (b * 1.5 + a) % 100.25
  a = a + 8 * b - c % 2
  if 271 % 97 == 0 then s = s .. "271," end
  a = a + 8 * b - c % 3
  d = helper(d, 0) % 1000
  t[25] = a + 274
  d = helper(d, 2) % 1000
  t.f6 = (t.f6 or 0) + c
  d = helper(d, 4) % 1000
  d = helper(d, 5) % 1000
  t.f9 = (t.f9 or 0) + c
  if a > 23 then b = b + 1 else c = c + 2 end
  a = a + 5 * b - c % 3
  a = a + 6 * b - c % 3
  if 283 % 97 == 0 then s = s .. "283," end
  t[35] = a + 284
  t[36] = a + 285
  t[37] = a + 286
  t[38] = a + 287
  t.f18 = (t.f18 or 0) + c
  t.f19 = (t.f19 or 0) + c
  a = a + 6 * b - c % 5
  for j = 1, 1 do c = (c + j * a) % 997 end
  a = a + 6 * b - c % 4
  if 293 % 97 == 0 then s = s .. "293," end
  if 294 % 97 == 0 then s = s .. "294," end
  b = (b * 1.5 + a) % 100.25
  t[47] = a + 296
  d = helper(d, 11) % 1000
  b = (b * 1.5 + a) % 100.25
  if 299 % 97 == 0 then s = s .. "299," end
  if a > 69 then b = b + 1 else c = c + 2 end
  for j = 1, 1 do c = (c + j * a) % 997 end
  t.f2 = (t.f2 or 0) + c
  t[4] = a + 303
  if 304 % 97 == 0 then s = s .. "304," end
  t[6] = a + 305
  b = (b * 1.5 + a) % 100.25
  t.f7 = (t.f7 or 0) + c
  t[9] = a + 308
  for j = 1, 3 do c = (c + j * a) % 997 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  t.f11 = (t.f11 or 0) + c
  t.f12 = (t.f12 or 0) + c
  t[14] = a + 313
  for j = 1, 3 do c = (c + j * a) % 997 end
  t[16] = a + 315
  b = (b * 1.5 + a) % 100.25
  for j = 1, 1 do c = (c + j * a) % 997 end
  t.f18 = (t.f18 or 0) + c
  if a > 82 then b = b + 1 else c = c + 2 end
  if a > 22 then b = b + 1 else c = c + 2 end
  for j = 1, 3 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  t[24] = a + 323
  t[25] = a + 324
  if a > 42 then b = b + 1 else c = c + 2 end
  if 326 % 97 == 0 then s = s .. "326," end
  t.f27 = (t.f27 or 0) + c
  if a > 58 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  t.f0 = (t.f0 or 0) + c
  if a > 90 then b = b + 1 else c = c + 2 end
  t[33] = a + 332
  t[34] = a + 333
  if a > 96 then b = b + 1 else c = c + 2 end
  if 335 % 97 == 0 then s = s .. "335," end
  for j = 1, 1 do c = (c + j * a) % 997 end
  t.f7 = (t.f7 or 0) + c
  t.f8 = (t.f8 or 0) + c
  if 339 % 97 == 0 then s = s .. "339," end
  a = a + 3 * b - c % 2
  b = (b * 1.5 + a) % 100.25
  t.f12 = (t.f12 or 0) + c
  d = helper(d, 5) % 1000
  for j = 1, 2 do c = (c + j * a) % 997 end
  if 345 % 97 == 0 then s = s .. "345," end
  b = (b * 1.5 + a) % 100.25
  t[48] = a + 347
  for j = 1, 2 do c = (c + j * a) % 997 end
  t[50] = a + 349
  if a > 34 then b = b + 1 else c = c + 2 end
  t[2] = a + 351
  t[3] = a + 352
  t[4] = a + 353
  if a > 89 then b = b + 1 else c = c + 2 end
  d = helper(d, 4) % 1000
  if 356 % 97 == 0 then s = s .. "356," end
  if 357 % 97 == 0 then s = s .. "357," end
  if a > 75 then b = b + 1 else c = c + 2 end
  if a > 50 then b = b + 1 else c = c + 2 end
  d = helper(d, 9) % 1000
  if a > 72 then b = b + 1 else c = c + 2 end
  if a > 25 then b = b + 1 else c = c + 2 end
  t.f3 = (t.f3 or 0) + c
  for j = 1, 2 do c = (c + j * a) % 997 end
  a = a + 8 * b - c % 5
  if 366 % 97 == 0 then s = s .. "366," end
  for j = 1, 3 do c = (c + j * a) % 997 end
  t.f8 = (t.f8 or 0) + c
  b = (b * 1.5 + a) % 100.25
  t.f10 = (t.f10 or 0) + c
  b = (b * 1.5 + a) % 100.25
  a = a + 4 * b - c % 2
  t[24] = a + 373
  d = helper(d, 10) % 1000
  b = (b * 1.5 + a) % 100.25
  if a > 67 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 1) % 1000
  d = helper(d, 2) % 1000
  d = helper(d, 3) % 1000
  a = a + 8 * b - c % 2
  t.f22 = (t.f22 or 0) + c
  if a > 17 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  t[36] = a + 385
  a = a + 1 * b - c % 4
  d = helper(d, 10) % 1000
  t[39] = a + 388
  b = (b * 1.5 + a) % 100.25
  a = a + 6 * b - c % 4
  for j = 1, 2 do c = (c + j * a) % 997 end
  if a > 10 then b = b + 1 else c = c + 2 end
  a = a + 2 * b - c % 4
  d = helper(d, 4) % 1000
  t[46] = a + 395
  d = helper(d, 6) % 1000
  if 397 % 97 == 0 then s = s .. "397," end
  d = helper(d, 8) % 1000
  b = (b * 1.5 + a) % 100.25
  for j = 1, 1 do c = (c + j * a) % 997 end
  a = a + 7 * b - c % 2
  d = helper(d, 12) % 1000
  if a > 50 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  t[7] = a + 406
  if 407 % 97 == 0 then s = s .. "407," end
  d = helper(d, 5) % 1000
  b = (b * 1.5 + a) % 100.25
  t.f20 = (t.f20 or 0) + c
  a = a + 8 * b - c % 5
  if 412 % 97 == 0 then s = s .. "412," end
  b = (b * 1.5 + a) % 100.25
  if a > 58 then b = b + 1 else c = c + 2 end
  a = a + 5 * b - c % 4
  for j = 1, 2 do c = (c + j * a) % 997 end
  for j = 1, 3 do c = (c + j * a) % 997 end
  if 418 % 97 == 0 then s = s .. "418," end
  d = helper(d, 3) % 1000
  a = a + 4 * b - c % 4
  for j = 1, 1 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 7) % 1000
  if a > 26 then b = b + 1 else c = c + 2 end
  a = a + 3 * b - c % 6
  if 426 % 97 == 0 then s = s .. "426," end
  if a > 81 then b = b + 1 else c = c + 2 end
  a = a + 3 * b - c % 2
  if a > 56 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  if a > 7 then b = b + 1 else c = c + 2 end
  a = a + 7 * b - c % 5
  for j = 1, 2 do c = (c + j * a) % 997 end
  a = a + 1 * b - c % 3
  if 435 % 97 == 0 then s = s .. "435," end
  a = a + 7 * b - c % 5
  a = a + 4 * b - c % 3
  t[39] = a + 438
  if 439 % 97 == 0 then s = s .. "439," end
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 12) % 1000
  if a > 42 then b = b + 1 else c = c + 2 end
  t[44] = a + 443
  for j = 1, 1 do c = (c + j * a) % 997 end
  a = a + 5 * b - c % 4
  b = (b * 1.5 + a) % 100.25
  if a ~= nil then local z = nil; a = z.x end
  t.f27 = (t.f27 or 0) + c
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 7) % 1000
  t.f0 = (t.f0 or 0) + c
  a = a + 6 * b - c % 4
  for j = 1, 1 do c = (c + j * a) % 997 end
  a = a + 7 * b - c % 2
  a = a + 2 * b - c % 2
  t[6] = a + 455
  a = a + 3 * b - c % 6
  a = a + 8 * b - c % 2
  d = helper(d, 3) % 1000
  for j = 1, 1 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  for j = 1, 2 do c = (c + j * a) % 997 end
  for j = 1, 3 do c = (c + j * a) % 997 end
  a = a + 7 * b - c % 4
  if 464 % 97 == 0 then s = s .. "464," end
  t[16] = a + 465
  t.f16 = (t.f16 or 0) + c
  if a > 52 then b = b + 1 else c = c + 2 end
  t[19] = a + 468
  if 469 % 97 == 0 then s = s .. "469," end
  for j = 1, 3 do c = (c + j * a) % 997 end
  if 471 % 97 == 0 then s = s .. "471," end
  if a > 94 then b = b + 1 else c = c + 2 end
  if 473 % 97 == 0 then s = s .. "473," end
  for j = 1, 1 do c = (c + j * a) % 997 end
  for j = 1, 2 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 9) % 1000
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  for j = 1, 2 do c = (c + j * a) % 997 end
  if a > 64 then b = b + 1 else c = c + 2 end
  if 482 % 97 == 0 then s = s .. "482," end
  b = (b * 1.5 + a) % 100.25
  a = a + 3 * b - c % 3
  a = a + 8 * b - c % 2
  t[37] = a + 486
  for j = 1, 1 do c = (c + j * a) % 997 end
  a = a + 1 * b - c % 5
  b = (b * 1.5 + a) % 100.25
  for j = 1, 2 do c = (c + j * a) % 997 end
  a = a + 2 * b - c % 3
  if 492 % 97 == 0 then s = s .. "492," end
  t[44] = a + 493
  for j = 1, 3 do c = (c + j * a) % 997 end
  t.f15 = (t.f15 or 0) + c
  t[47] = a + 496
  b = (b * 1.5 + a) % 100.25
  t[49] = a + 498
  d = helper(d, 5) % 1000
  d = helper(d, 6) % 1000
  a = a + 3 * b - c % 3
  a = a + 2 * b - c % 3
  t.f23 = (t.f23 or 0) + c
  d = helper(d, 10) % 1000
  d = helper(d, 11) % 1000
  if 506 % 97 == 0 then s = s .. "506," end
  for j = 1, 3 do c = (c + j * a) % 997 end
  d = helper(d, 1) % 1000
  b = (b * 1.5 + a) % 100.25
  if a > 79 then b = b + 1 else c = c + 2 end
  t[12] = a + 511
  a = a + 2 * b - c % 6
  a = a + 2 * b - c % 3
  t.f4 = (t.f4 or 0) + c
  t[16] = a + 515
  t[17] = a + 516
  b = (b * 1.5 + a) % 100.25
  if 518 % 97 == 0 then s = s .. "518," end
  d = helper(d, 12) % 1000
  t[21] = a + 520
  b = (b * 1.5 + a) % 100.25
  for j = 1, 2 do c = (c + j * a) % 997 end
  b = (b * 1.5 + a) % 100.25
  t[25] = a + 524
  t.f15 = (t.f15 or 0) + c
  b = (b * 1.5 + a) % 100.25
  if 527 % 97 == 0 then s = s .. "527," end
  d = helper(d, 8) % 1000
  t[30] = a + 529
  a = a + 8 * b - c % 4
  t[32] = a + 531
  for j = 1, 2 do c = (c + j * a) % 997 end
  d = helper(d, 0) % 1000
  b = (b * 1.5 + a) % 100.25
  t[36] = a + 535
  for j = 1, 2 do c = (c + j * a) % 997 end
  t[38] = a + 537
  d = helper(d, 5) % 1000
  d = helper(d, 6) % 1000
  for j = 1, 1 do c = (c + j * a) % 997 end
  for j = 1, 1 do c = (c + j * a) % 997 end
  if 542 % 97 == 0 then s = s .. "542," end
  b = (b * 1.5 + a) % 100.25
  t[45] = a + 544
  t.f5 = (t.f5 or 0) + c
  d = helper(d, 0) % 1000
  for j = 1, 1 do c = (c + j * a) % 997 end
  d = helper(d, 2) % 1000
  d = helper(d, 3) % 1000
  b = (b * 1.5 + a) % 100.25
  if 551 % 97 == 0 then s = s .. "551," end
  for j = 1, 1 do c = (c + j * a) % 997 end
  if 553 % 97 == 0 then s = s .. "553," end
  b = (b * 1.5 + a) % 100.25
  if 555 % 97 == 0 then s = s .. "555," end
  b = (b * 1.5 + a) % 100.25
  a = a + 5 * b - c % 2
  if a > 12 then b = b + 1 else c = c + 2 end
  a = a + 3 * b - c % 4
  a = a + 8 * b - c % 2
  d = helper(d, 2) % 1000
  d = helper(d, 3) % 1000
  t.f23 = (t.f23 or 0) + c
  b = (b * 1.5 + a) % 100.25
  if 565 % 97 == 0 then s = s .. "565," end
  a = a + 6 * b - c % 5
  d = helper(d, 8) % 1000
  t.f28 = (t.f28 or 0) + c
  if a > 13 then b = b + 1 else c = c + 2 end
  b = (b * 1.5 + a) % 100.25
  t.f1 = (t.f1 or 0) + c
  if 572 % 97 == 0 then s = s .. "572," end
  b = (b * 1.5 + a) % 100.25
  t[25] = a + 574
  d = helper(d, 3) % 1000
  if a > 62 then b = b + 1 else c = c + 2 end
  t.f7 = (t.f7 or 0) + c
  if 578 % 97 == 0 then s = s .. "578," end
  for j = 1, 1 do c = (c + j * a) % 997 end
  if 580 % 97 == 0 then s = s .. "580," end
  t.f11 = (t.f11 or 0) + c
  b = (b * 1.5 + a) % 100.25
  b = (b * 1.5 + a) % 100.25
  d = helper(d, 12) % 1000
  for j = 1, 2 do c = (c + j * a) % 997 end
  t.f16 = (t.f16 or 0) + c
  a = a + 8 * b - c % 4
  for j = 1, 2 do c = (c + j * a) % 997 end
  d = helper(d, 4) % 1000
  a = a + 1 * b - c % 3
  if a > 68 then b = b + 1 else c = c + 2 end
  a = a + 3 * b - c % 2
  a = a + 4 * b - c % 5
  for j = 1, 2 do c = (c + j * a) % 997 end
  a = a + 8 * b - c % 3
  d = helper(d, 11) % 1000
  a = a + 5 * b - c % 5
  for j = 1, 1 do c = (c + j * a) % 997 end
  t[50] = a + 599
  local n = 0
  for k, v in pairs(t) do if type(v) == "number" then n = n + v end end
  return a, b, c, d, s, n, #t
end)
return cases
