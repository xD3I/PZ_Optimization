package pzopt;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaTableIterator;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Prototype;

/**
 * luaJit differential test: every case of fixtures/luavm/jit_cases.lua and every benchmark kernel runs in an
 * interpreter-only Kahlua state and in states where the functions are compiled (all of them; a seeded random half,
 * so compiled and interpreted frames call each other; tiering up through the background compiler while running).
 * Results (numbers by their bits, tables in pairs order), error messages, pcall's traceback strings and the
 * "dumping Lua stack trace" reports must be identical.
 */
public class LuaJitDiffTest {
   static int mismatches;
   static int compared;

   public static void main(String[] args) throws Exception {
      System.setProperty("pzopt.luaJit", "true");
      System.setProperty("pzopt.luaJitThreshold", args.length > 0 ? args[0] : "1000000000");
      System.setProperty("pzopt.luaJavaFastThreshold", "2");
      System.setProperty("pzopt.luaJitLoopThreshold", "25");
      LuaJavaFast.testSync = true;
      Check.check(LuaJit.ON, "luaJit must be on for this test");
      boolean locals = Config.LUA_JIT_LOCALS;

      List<String> ref = runAll("interp", -1L);
      if (Boolean.getBoolean("pzopt.diffDump")) {
         ref.forEach(System.out::println);
      }
      List<String> javaFast = runAll("interp-javafast", -2L);
      List<String> all = runAll("jit-all", 0L);
      List<String> half1 = runAll("jit-half", 1L);
      List<String> half2 = runAll("jit-half", 2L);
      compare("interp-javafast", ref, javaFast);
      compare("jit-all", ref, all);
      compare("jit-half-1", ref, half1);
      compare("jit-half-2", ref, half2);
      System.out.println("LuaJitDiffTest: " + compared + " results compared, " + mismatches + " mismatches (locals="
            + locals + ", " + LuaJit.summary() + ", " + LuaJavaFast.summary() + ", " + LuaJitSites.summary() + ")");
      Check.check(mismatches == 0, mismatches + " mismatches");
      Check.check(LuaJitDual.mismatches.get() == 0, "devLuaJitDual: " + LuaJitDual.mismatches.get() + " mismatches");
   }

   static void compare(String mode, List<String> ref, List<String> got) {
      Check.check(ref.size() == got.size(), mode + ": result count " + ref.size() + " vs " + got.size());
      for (int i = 0; i < ref.size(); i++) {
         compared++;
         if (!ref.get(i).equals(got.get(i))) {
            mismatches++;
            if (mismatches <= 30) {
               System.out.println("MISMATCH " + mode + "\n  interp: " + ref.get(i) + "\n  jit:    " + got.get(i));
            }
         }
      }
   }

   /** seed -1: interpreter only, stock Java calls (the reference); -2: interpreter only; 0: compile every function; else a random half. */
   static List<String> runAll(String mode, long seed) throws Exception {
      LuaJavaFast.testOff = seed == -1;
      resetPools();
      LuaVm vm = new LuaVm();
      // the game's error paths (MethodArguments.assertValid, MethodCaller.logException) report through these
      zombie.ui.UIManager.defaultthread = vm.thread;
      zombie.Lua.LuaManager.thread = vm.thread;
      if (seed < 0) {
         vm.thread.pzoptJitDepth = 1_000_000; // never enters compiled code
      }
      Random rnd = new Random(seed);
      Path dir = LuaVm.fixtures();
      List<String> out = new ArrayList<>();
      LuaClosure casesChunk = vm.loadFile(dir.resolve("jit_cases.lua"));
      LuaClosure core = vm.loadFile(dir.resolve("bench_core.lua"));
      LuaClosure mod = vm.loadFile(dir.resolve("bench_mod.lua"));
      LuaClosure big = vm.loadFile(dir.resolve("jit_big.lua"));
      if (seed >= 0) {
         IdentityHashMap<Prototype, Boolean> seen = new IdentityHashMap<>();
         for (LuaClosure c : new LuaClosure[] {casesChunk, core, mod, big}) {
            compileTree(c.prototype, rnd, seed, seen);
         }
         compileEnv(vm.env, rnd, seed, seen, 0);
      }
      Object[] r = vm.run(casesChunk);
      KahluaTable cases = (KahluaTable)r[1];
      KahluaTable bigCases = (KahluaTable)vm.run(big)[1];
      for (int i = 1; i <= bigCases.len(); i++) {
         cases.rawset((double)(cases.len() + 1), bigCases.rawget((double)i));
      }
      int nCases = cases.len();
      for (int i = 1; i <= nCases; i++) {
         KahluaTable c = (KahluaTable)cases.rawget((double)i);
         String name = (String)c.rawget(1.0);
         Object fn = c.rawget(2.0);
         if (Boolean.getBoolean("pzopt.diffTrace")) {
            System.out.println(mode + " case " + name);
         }
         for (int rep = 0; rep < 2; rep++) {
            KahluaThread.m_errors_list.clear();
            int errBefore = 0;
            Object[] res = vm.thread.pcall(fn, new Object[0]);
            // a check_ case counts its own wrong answers against a cache-free reference: its first result must be 0
            Check.check(!name.startsWith("check_") || res.length > 1 && Boolean.TRUE.equals(res[0]) && res[1] instanceof Double d0
                  && d0 == 0.0, name + " (" + mode + "): " + java.util.Arrays.toString(res));
            StringBuilder sb = new StringBuilder(name).append(" #").append(rep).append(": ");
            describe(sb, res);
            List<String> errs = new ArrayList<>(KahluaThread.m_errors_list.subList(Math.min(errBefore, KahluaThread.m_errors_list.size()), KahluaThread.m_errors_list.size()));
            for (String e : errs) {
               sb.append(" | report: ").append(firstLines(e));
            }
            out.add(sb.toString().replaceAll("0x[0-9a-f]+", "0x?").replaceAll("@[0-9a-f]+", "@?"));
         }
         // a third run recorded as a retained UI element's render (uiVerify): the read set the compiled code logs
         // (luaJitUiDeps: its fast paths log what stock rawget / tableSet log) must be the interpreter's
         // and a second recording (the sites' per-recording memos, luaJitUiReadMemo / luaJitNilCacheUi, must log again)
         for (int ui = 0; ui < 2; ui++) {
            KahluaThread.m_errors_list.clear();
            UiDeps.Log log = new UiDeps.Log();
            Object[] res;
            UiDeps.begin(log, null);
            try {
               res = vm.thread.pcall(fn, new Object[0]);
            } finally {
               UiDeps.end(log);
               UiDeps.reset();
            }
            StringBuilder sb = new StringBuilder(name).append(ui == 0 ? " #ui: " : " #ui2: ");
            describe(sb, res);
            sb.append(" | reads: ").append(readSet(log));
            out.add(sb.toString().replaceAll("0x[0-9a-f]+", "0x?").replaceAll("@[0-9a-f]+", "@?"));
         }
      }
      vm.run(core);
      vm.run(mod);
      KahluaTable bench = (KahluaTable)vm.env.rawget("BENCH");
      String[] order = {"arith", "array", "records", "oop", "strings", "closures", "varargs", "calls", "globals", "loadgridsquare", "ontick"};
      for (String k : order) {
         Object fn = bench.rawget(k);
         for (int rep = 0; rep < 3; rep++) {
            Object[] res = vm.thread.pcall(fn, new Object[] {3.0});
            StringBuilder sb = new StringBuilder("bench." + k + " #" + rep + ": ");
            describe(sb, res);
            out.add(sb.toString());
         }
      }
      return out;
   }

   /**
    * Fresh MethodArguments / ReturnValues pools for every run: stock MultiLuaJavaInvoker puts its last failed
    * MethodArguments back twice when no overload fits (jf_overloads), and the duplicate later hands out an object a failed
    * call left invalid, so a later valid call of that size fails; the pools are static and would carry it into the next run.
    */
   @SuppressWarnings("unchecked")
   static void resetPools() throws Exception {
      java.lang.reflect.Field f = se.krka.kahlua.integration.expose.MethodArguments.class.getDeclaredField("Pools");
      f.setAccessible(true);
      java.util.ArrayList<Object>[] pools = (java.util.ArrayList<Object>[])f.get(null);
      for (int n = 0; n < pools.length; n++) {
         pools[n].clear();
         for (int x = 0; x < 20; x++) {
            pools[n].add(new se.krka.kahlua.integration.expose.MethodArguments(n));
         }
      }
   }

   /** An error report keeps its Lua lines; a Java exception's printed trace keeps only its first line (Java frames differ). */
   static String firstLines(String e) {
      if (e.startsWith("dumping Lua stack trace") || !e.contains("\n\tat ")) {
         return e.replace('\n', '/');
      }
      return e.substring(0, e.indexOf('\n'));
   }

   static void compileTree(Prototype p, Random rnd, long seed, IdentityHashMap<Prototype, Boolean> seen) {
      if (seen.put(p, Boolean.TRUE) != null) {
         return;
      }
      if (seed == 0 || rnd.nextBoolean()) {
         boolean ok = LuaJit.compileNow(p);
         if (!ok && seed == 0) {
            System.out.println("not compiled: " + LuaJitCompiler.describe(p) + ": " + p.pzoptJitWhy);
         }
      }
      if (p.prototypes != null) {
         for (Prototype q : p.prototypes) {
            compileTree(q, rnd, seed, seen);
         }
      }
   }

   static void compileEnv(KahluaTable t, Random rnd, long seed, IdentityHashMap<Prototype, Boolean> seen, int depth) {
      KahluaTableIterator it = t.iterator();
      while (it.advance()) {
         Object v = it.getValue();
         if (v instanceof LuaClosure c) {
            compileTree(c.prototype, rnd, seed, seen);
         } else if (v instanceof KahluaTable sub && depth < 1 && sub != t) {
            compileEnv(sub, rnd, seed, seen, depth + 1);
         }
      }
   }

   /** A recorded render's read set, tables by first-read order: per table its keys (in read order) and the values first seen. */
   static String readSet(UiDeps.Log log) {
      StringBuilder sb = new StringBuilder();
      sb.append("mutating=").append(log.mutating()).append(" verifiable=").append(log.verifiable()).append(" tables=").append(log.nt);
      for (int i = 0; i < log.nt; i++) {
         sb.append(" [").append(i).append(":");
         for (int k = 0; k < log.scount[i]; k++) {
            Object key = log.skeys[i][k];
            Object v = log.svals[i][k];
            sb.append(' ').append(key == UiDeps.ITER ? "<iter>" : key == UiDeps.LEN ? "<len>" : String.valueOf(key)).append('=');
            sb.append(v == null ? "nil" : v instanceof Double || v instanceof String || v instanceof Boolean ? String.valueOf(v) : v.getClass().getSimpleName());
         }
         sb.append(']');
      }
      // the Java calls it logged (luaJavaFastUiDeps: the generated calls log what MethodCaller logs)
      sb.append(" calls=").append(log.n);
      for (int i = 0; i < log.n; i++) {
         sb.append(" {").append(log.m[i] == null ? "-" : log.m[i].getName()).append(" k").append(log.kind[i]).append(" self=")
               .append(log.self[i] == null ? "nil" : log.self[i].getClass().getSimpleName()).append(" args=");
         for (Object a : log.args[i]) {
            sb.append(a == null ? "nil" : a.getClass().getSimpleName() + ":" + (a instanceof Number || a instanceof String || a instanceof Boolean
                  || a instanceof Character ? a : "?")).append(',');
         }
         Object r = log.res[i];
         sb.append(" res=").append(r == null ? "nil" : r.getClass().getSimpleName() + ":" + (r instanceof Number || r instanceof String
               || r instanceof Boolean || r instanceof Character ? r : "?")).append('}');
      }
      sb.append(" opaque=").append(log.opaque).append(log.why == null ? "" : " why=" + log.why);
      return sb.toString();
   }

   static void describe(StringBuilder sb, Object[] res) {
      for (int i = 0; i < res.length; i++) {
         if (i > 0) {
            sb.append(", ");
         }
         Object v = res[i];
         if (v instanceof Throwable e) {
            sb.append("ex:").append(e.getClass().getName()).append(':').append(e.getMessage());
         } else {
            value(sb, v, 0, new IdentityHashMap<>());
         }
      }
   }

   static void value(StringBuilder sb, Object v, int depth, IdentityHashMap<Object, Boolean> seen) {
      if (v == null) {
         sb.append("nil");
      } else if (v instanceof Double d) {
         sb.append("n:").append(d).append('#').append(Long.toHexString(Double.doubleToRawLongBits(d)));
      } else if (v instanceof String s) {
         sb.append("s:\"").append(s).append('"');
      } else if (v instanceof Boolean b) {
         sb.append(b);
      } else if (v instanceof KahluaTable t) {
         if (seen.put(t, Boolean.TRUE) != null || depth > 4) {
            sb.append("t{...}");
            return;
         }
         sb.append("t{");
         KahluaTableIterator it = t.iterator();
         boolean first = true;
         while (it.advance()) {
            if (!first) {
               sb.append(' ');
            }
            first = false;
            value(sb, it.getKey(), depth + 1, seen);
            sb.append('=');
            value(sb, it.getValue(), depth + 1, seen);
         }
         sb.append('}');
      } else if (v instanceof LuaClosure c) {
         sb.append("f:").append(c.prototype.name).append(':').append(c.prototype.lines.length > 0 ? c.prototype.lines[0] : 0);
      } else if (v instanceof JavaFunction) {
         sb.append("j:").append(v.getClass().getSimpleName());
      } else {
         sb.append("o:").append(v.getClass().getSimpleName()).append(':').append(v);
      }
   }
}
