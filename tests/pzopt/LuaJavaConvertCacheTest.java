package pzopt;

import se.krka.kahlua.converter.JavaToLuaConverter;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaClosure;

/**
 * luaJavaConvertCache: a generated Java call keeps its last result class's converter. Results must be what
 * KahluaConverterManager.fromJavaToLua gives: the game's array converter installed (an int[] becomes a table, any other
 * object passes as it is), a site whose result class changes every call, and a converter added after the sites have
 * cached theirs (KahluaConverterManager.pzoptEpoch) must take effect at once.
 */
public final class LuaJavaConvertCacheTest {
   public static void main(String[] args) throws Exception {
      System.setProperty("pzopt.luaJit", "true");
      System.setProperty("pzopt.luaJitThreshold", "1");
      System.setProperty("pzopt.luaJitRateMs", "0"); // probe runs once: compiled at its first entry, whatever the rate
      System.setProperty("pzopt.luaJavaFastThreshold", "2");
      System.setProperty("pzopt.luaJitLoopThreshold", "25");
      LuaJavaFast.testSync = true; // the generated calls made at once, not on the background compiler
      Check.check(LuaJavaFast.CONVERT_CACHE, "luaJavaConvertCache is on");
      LuaVm vm = new LuaVm();
      new zombie.Lua.KahluaArrayConverter(vm.platform, vm.converters).install();
      LuaClosure chunk = vm.load("""
            local JC = JC
            function probe(n)
              local out = {}
              local sq0 = SQ0 -- a Java object held by Lua (getGridSquare would convert it itself)
              for i = 1, n do
                local a = JC:arrOrObj(3)
                local o = JC:arrOrObj(-1)
                local m = JC:arrOrObj(i % 2 == 0 and 2 or -1)
                local sq = JC:id(sq0)
                out[#out + 1] = type(a) .. ":" .. (type(a) == "table" and (a[1] .. "," .. a[2] .. "," .. a[3]) or "-")
                  .. "|" .. type(o) .. "|" .. type(m) .. "|" .. tostring(type(sq) == "string" and sq or type(sq))
              end
              return table.concat(out, " ")
            end
            """, "convert_cache");
      vm.env.rawset("SQ0", vm.cell.getGridSquare(1, 2, 0));
      vm.run(chunk);
      Object fn = vm.env.rawget("probe");
      String warm = (String)vm.run(fn, 200.0)[1];
      String[] parts = warm.split(" ");
      Check.check(parts.length == 200, "200 results");
      for (int i = 0; i < parts.length; i++) {
         String want = "table:0,3,6|userdata|" + ((i + 1) % 2 == 0 ? "table" : "userdata") + "|userdata";
         Check.check(parts[i].equals(want), "call " + i + ": " + parts[i] + " want " + want);
      }
      Check.check(LuaJit.compiled.get() > 0, "probe was compiled");
      // a converter added later: the sites' cached converters are stale from now on
      vm.converters.addJavaConverter(new JavaToLuaConverter<LuaVmStubs.Square>() {
         @Override
         public Object fromJavaToLua(LuaVmStubs.Square sq) {
            return "SQ" + sq.getX();
         }

         @Override
         public Class<LuaVmStubs.Square> getJavaType() {
            return LuaVmStubs.Square.class;
         }
      });
      String after = (String)vm.run(fn, 20.0)[1];
      for (String p : after.split(" ")) {
         Check.check(p.endsWith("|SQ1"), "the new converter applies at once: " + p);
      }
      Check.check(LuaJavaFast.summary().contains("java_fast=") && !LuaJavaFast.summary().contains("java_fast=0 "), "generated calls: " + LuaJavaFast.summary());
      System.out.println("LuaJavaConvertCacheTest ok (" + LuaJit.summary().trim() + ", " + LuaJavaFast.summary() + ")");
   }
}
