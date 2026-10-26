package pzopt;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaClosure;

/**
 * luaStringFast: string.find / match / gmatch / gsub over random subjects and patterns (classes, sets, quantifiers,
 * anchors, captures, %b, %f, back-references, malformed ones) give the same results and errors with the matcher's
 * position objects allocated plainly as with the game's pooled ones. A back-reference past the open captures is left out
 * (no pattern in the game or the mod list has one): stock Kahlua reads a stale capture of an earlier call there, whose
 * pooled position object a later call may have reused, so its answer depends on the call history (LuaStringFast).
 */
public final class LuaStringFastTest {
   static final String RUN = """
      local srcs, pats, out = ...
      local function rec(...)
        local n = select('#', ...)
        local t = {...}
        for k = 1, n do t[k] = tostring(t[k]) end
        out[#out + 1] = table.concat(t, "|", 1, n)
      end
      for i = 1, #srcs do
        for j = 1, #pats do
          local s, p = srcs[i], pats[j]
          rec(pcall(string.find, s, p))
          if i == 1 then
            rec(pcall(string.find, nil, p)) -- argument errors: wrapped once, as stock
            rec(pcall(string.find, s, p, "x"))
            rec(pcall(string.gsub, s, p, nil))
            rec(pcall(string.gsub, s, p, "x", "y"))
            rec(pcall(string.gsub, nil, p, "x"))
            rec(pcall(string.gsub, s, p, function() error("in repl") end))
          end
          rec(pcall(string.find, s, p, 3))
          rec(pcall(string.find, s, p, -4))
          rec(pcall(string.find, s, p, 100))
          rec(pcall(string.find, s, p, -100))
          rec(pcall(string.match, s, p, 2))
          rec(pcall(string.gsub, s, p, "[%1|%2|%%|%a]%"))
          rec(pcall(string.gsub, s, p, "%0%0", 0))
          rec(pcall(string.gsub, s, p, 7))
          rec(pcall(string.gsub, s, p, function(a) if a == "l" then return false end end))
          rec(pcall(string.gsub, s, p, true))
          rec(pcall(string.match, s, p))
          rec(pcall(string.gsub, s, p, "<%0>"))
          rec(pcall(string.gsub, s, p, "", 2))
          rec(pcall(string.gsub, s, p, function(a, b) return a and (tostring(a) .. "!") end))
          rec(pcall(string.gsub, s, p, {a = "A", hello = "H", ["1"] = false}))
          local ok, err = pcall(function()
            local r = {}
            for a, b in string.gmatch(s, p) do
              r[#r + 1] = tostring(a) .. "," .. tostring(b)
              if #r > 40 then break end
            end
            out[#out + 1] = table.concat(r, ";")
          end)
          if not ok then out[#out + 1] = "gmatch err " .. tostring(err) end
        end
      end
      return #out
      """;

   static final String[] ATOMS = {"a", "b", "l", "o", " ", ".", "%a", "%d", "%s", "%w", "%p", "%l", "%u", "%x", "%c", "%A",
      "%S", "[aeiou]", "[^%s]", "[%w_]", "[a-f]", "[_%-%s/\\\\]", "%.", "%%", "%(", "(%a+)", "(.-)", "()", "%b()", "%f[%w]",
      "(%a)%1", "%bx%%1", "[", "%", "x*", "o+", "l-", "e?", "%d+", "(%d+)%.(%d+)", "(a", "a)", "$a", "%z", "[%a-z]",
      "[a-]", "[]]", "[^]]", "%f[%s]", "%b", "%f", "%bxy", "a\u0000b", "\u0000", "[%]", "%f[%a%d]", "(()l)", "(o(r)l)",
      "^(%s*)(.-)(%s*)$", "[^%w%s_%-]", "%W+", "%U", ".?", "[\u00e9-\u00ff]", "\u00e9",
      "[^a]*", "%s*", "[%w_]+", "%d?", "%z+", "^%s+", "[^%s]-", "\u00e9+", "%"};
   static final String[] SUBJECTS_BASE = {"", "hello world", "Hello_World-42/x", "  spaces  ", "a.b.c", "(nested (parens))",
      "Base.Axe", "Fav:Base.Pistol", "12.5 kg", "UPPER lower 123", "tab\there", "%percent%", "end.", "nul\u0000inside",
      "caf\u00e9 \u00fcber \u0663\u0664", "]]x[[", "a)b(c", "xyxyxy"};

   public static void main(String[] args) throws Exception {
      Random rnd = new Random(42);
      List<String> srcs = new ArrayList<>(List.of(SUBJECTS_BASE));
      String alphabet = "abcdeHlo LW_-./()%123\t";
      while (srcs.size() < 48) {
         StringBuilder sb = new StringBuilder();
         for (int k = rnd.nextInt(24); k > 0; k--) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
         }
         srcs.add(sb.toString());
      }
      List<String> pats = new ArrayList<>();
      for (String a : ATOMS) {
         pats.add(a);
      }
      while (pats.size() < 300) {
         StringBuilder sb = new StringBuilder(rnd.nextInt(6) == 0 ? "^" : "");
         for (int k = 1 + rnd.nextInt(4); k > 0; k--) {
            sb.append(ATOMS[rnd.nextInt(ATOMS.length)]);
         }
         if (rnd.nextInt(6) == 0) {
            sb.append('$');
         }
         pats.add(sb.toString());
      }
      LuaStringFast.on = false;
      LuaPattern.on = false;
      LuaPattern.poolSkip = false;
      List<String> stock = run(srcs, pats);
      LuaStringFast.on = true;
      List<String> plain = run(srcs, pats);
      LuaStringFast.on = false;
      LuaPattern.on = true;
      List<String> fastPooled = run(srcs, pats);
      LuaPattern.poolSkip = true; // luaPatternNoPool: the pool entry only for the stock matcher
      List<String> fast = run(srcs, pats);
      boolean single0 = LuaPattern.single;
      LuaPattern.single = true; // luaPatternSingle: one-item patterns by a scan
      List<String> fastSingle = run(srcs, pats);
      LuaPattern.single = single0;
      int applies = 0;
      for (String pat : pats) {
         applies += LuaPattern.applies(pat) ? 1 : 0;
      }
      LuaPattern.on = false;
      List<String> stockNoPool = run(srcs, pats); // every pattern back to the stock matcher through the second pass
      Check.check(applies > pats.size() * 3 / 4, "only " + applies + " patterns on luaPatternFast");
      compare("luaStringFast", stock, plain, srcs.size(), pats.size());
      compare("luaPatternFast, pooled (" + applies + " patterns on it)", stock, fastPooled, srcs.size(), pats.size());
      compare("luaPatternFast + luaPatternNoPool", stock, fast, srcs.size(), pats.size());
      compare("luaPatternNoPool alone", stock, stockNoPool, srcs.size(), pats.size());
      compare("luaPatternFast + luaPatternSingle", stock, fastSingle, srcs.size(), pats.size());
   }

   static void compare(String what, List<String> stock, List<String> fast, int nsrc, int npat) {
      Check.check(stock.size() == fast.size(), "result counts " + stock.size() + " vs " + fast.size());
      int diff = 0;
      for (int i = 0; i < stock.size(); i++) {
         if (!stock.get(i).equals(fast.get(i))) {
            if (++diff <= 10) {
               System.out.println("MISMATCH #" + i + "\n  stock: " + stock.get(i) + "\n  fast:  " + fast.get(i));
            }
         }
      }
      System.out.println("LuaStringFastTest " + what + ": " + stock.size() + " results (" + nsrc + " subjects x " + npat
            + " patterns), " + diff + " mismatches");
      Check.check(diff == 0, diff + " mismatches");
   }

   static List<String> run(List<String> srcs, List<String> pats) throws Exception {
      LuaVm vm = new LuaVm();
      KahluaTable s = vm.platform.newTable();
      for (int i = 0; i < srcs.size(); i++) {
         s.rawset((double)(i + 1), srcs.get(i));
      }
      KahluaTable p = vm.platform.newTable();
      for (int i = 0; i < pats.size(); i++) {
         p.rawset((double)(i + 1), pats.get(i));
      }
      KahluaTable out = vm.platform.newTable();
      LuaClosure fn = vm.load(RUN, "LuaStringFastTest");
      Object[] r = vm.run(fn, s, p, out);
      Check.check(r.length > 0 && Boolean.TRUE.equals(r[0]), "the run threw: " + java.util.Arrays.toString(r));
      List<String> l = new ArrayList<>();
      for (int i = 1; i <= out.len(); i++) {
         l.add(String.valueOf(out.rawget((double)i)).replaceAll("0x[0-9a-f]+", "0x?").replaceAll("@[0-9a-f]+", "@?"));
      }
      return l;
   }
}
