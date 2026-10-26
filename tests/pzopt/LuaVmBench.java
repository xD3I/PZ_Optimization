package pzopt;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import se.krka.kahlua.vm.KahluaTable;

/**
 * Lua VM microbenchmark (not a *Test: scripts/test.sh does not run it; CPU-heavy, so run it as a queue cmd job):
 * {@code java -cp build/tests:build/classes:<jar> [-Dpzopt.luaJit=...] pzopt.LuaVmBench [seconds-per-kernel] [kernel...]}
 * Loads tests/pzopt/fixtures/luavm/bench_*.lua into a {@link LuaVm}, warms every kernel up, then times each one for the
 * given seconds and prints ns per kernel call plus the checksum it returned (a checksum that differs between two
 * configurations is a VM bug, not noise). The JVM options should match the game's (ZGC, -XX:-UseTypeSpeculation).
 */
public final class LuaVmBench {
   public static void main(String[] args) throws Exception {
      double seconds = args.length > 0 ? Double.parseDouble(args[0]) : 2.0;
      List<String> only = new ArrayList<>();
      for (int i = 1; i < args.length; i++) {
         only.add(args[i]);
      }
      LuaVm vm = new LuaVm();
      Path dir = LuaVm.fixtures();
      for (String f : new String[]{"bench_core.lua", "bench_mod.lua"}) {
         vm.run(vm.loadFile(dir.resolve(f)));
      }
      KahluaTable bench = (KahluaTable) vm.env.rawget("BENCH");
      String[] order = {"arith", "array", "records", "oop", "strings", "closures", "varargs", "calls", "globals", "loadgridsquare", "ontick"};
      int[] reps = {50, 50, 20, 200, 100, 100, 200, 5, 200, 1, 200};
      String label = System.getProperty("pzopt.benchLabel", "run");
      double geo = 0.0;
      int nGeo = 0;
      for (int k = 0; k < order.length; k++) {
         if (!only.isEmpty() && !only.contains(order[k])) {
            continue;
         }
         Object fn = bench.rawget(order[k]);
         Double n = (double) reps[k];
         long warmEnd = System.nanoTime() + (long) (seconds * 0.5e9);
         Object sum = null;
         while (System.nanoTime() < warmEnd) {
            sum = vm.run(fn, n)[1];
         }
         long calls = 0;
         long t0 = System.nanoTime();
         long end = t0 + (long) (seconds * 1e9);
         long t;
         do {
            sum = vm.run(fn, n)[1];
            calls++;
            t = System.nanoTime();
         } while (t < end);
         double ns = (t - t0) / (double) calls;
         geo += Math.log(ns);
         nGeo++;
         System.out.println(String.format(Locale.ROOT, "bench %-8s %-14s %12.0f ns/call  calls=%d  sum=%s", label, order[k], ns, calls, sum));
      }
      System.out.println(String.format(Locale.ROOT, "bench %-8s %-14s %12.0f ns (geomean)", label, "ALL", Math.exp(geo / Math.max(1, nGeo))));
   }
}
