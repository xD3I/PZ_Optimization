package pzopt;

import se.krka.kahlua.vm.LuaClosure;

/**
 * luaStringFast / luaPatternFast microbenchmark (not a test): the string patterns mods run per frame (MiniHealthPlus' per-mod gsub, the
 * favourite-weapon gmatch, item-type matches) with the game's pooled matcher positions vs plain allocation vs the int-position matcher, interleaved
 * rounds. Args: seconds per round (default 2), rounds (default 5).
 */
public final class LuaStringBench {
   static final String RUN = """
      local n = ...
      local ids = {"MiniHealthPlus", "BringBackJumboTrees", "Mod-Manager", "tsarslib", "ModOptions", "Fav:Base.Pistol;Base.Axe;Base.Katana"}
      local acc = 0
      for i = 1, n do
        local s = ids[i % #ids + 1]
        local a = string.gsub(s, "[%-%s_]", "")
        acc = acc + #a
        for w in string.gmatch(s, "[^;:]+") do acc = acc + 1 end
        local m1, m2 = string.match("Base.Axe", "^(%w+)%.(%w+)$")
        if m1 then acc = acc + #m2 end
        if string.find(s, "^Fav") then acc = acc + 1 end
        local x = string.gsub("12.5 kg", "(%d+)%.(%d+)", "%2.%1")
        acc = acc + #x
      end
      return acc
      """;

   static void mode(int m) {
      LuaStringFast.on = m == 1;
      LuaPattern.on = m == 2;
   }

   public static void main(String[] args) throws Exception {
      double secs = args.length > 0 ? Double.parseDouble(args[0]) : 2.0;
      int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 5;
      LuaVm vm = new LuaVm();
      LuaClosure fn = vm.load(RUN, "LuaStringBench");
      int batch = 20000;
      String[] names = {"stock pool    ", "luaStringFast ", "luaPatternFast"};
      for (int w = 0; w < 3; w++) {
         for (int m = 0; m < 3; m++) {
            mode(m);
            vm.run(fn, (double)batch);
         }
      }
      double[][] ns = new double[3][rounds];
      for (int r = 0; r < rounds; r++) {
         for (int m = 0; m < 3; m++) {
            mode(m);
            long t0 = System.nanoTime();
            long iters = 0;
            long deadline = t0 + (long)(secs * 1e9);
            while (System.nanoTime() < deadline) {
               vm.run(fn, (double)batch);
               iters += batch;
            }
            ns[m][r] = (System.nanoTime() - t0) / (double)iters;
         }
      }
      for (int m = 0; m < 3; m++) {
         double[] a = ns[m].clone();
         java.util.Arrays.sort(a);
         StringBuilder sb = new StringBuilder();
         for (double v : ns[m]) {
            sb.append(String.format(" %.0f", v));
         }
         System.out.printf("%s: median %.0f ns per iteration (5 string calls), rounds%s%n", names[m],
               a[a.length / 2], sb);
      }
   }
}
