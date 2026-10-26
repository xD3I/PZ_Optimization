package pzopt;

import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Prototype;

/**
 * luaJit corpus check (a tool, not a unit test): compiles every function of every .lua file under the given
 * directories, defines each class (the JVM verifier runs) and prints the refusal reasons and the JVM code sizes.
 * Usage: java -cp ... pzopt.LuaJitCorpus <dir>...  (read-only on the directories).
 */
public class LuaJitCorpus {
   public static void main(String[] args) throws Exception {
      System.setProperty("pzopt.luaJit", "true");
      LuaVm vm = new LuaVm();
      MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(KahluaThread.class, MethodHandles.lookup());
      Map<String, Integer> reasons = new TreeMap<>();
      Map<String, String> reasonExample = new TreeMap<>();
      int[] sizeBuckets = new int[8]; // <500 <1000 <2000 <4000 <8000 <16000 <32000 >=32000
      int files = 0, parseFail = 0, protos = 0, ok = 0, refused = 0, threw = 0, huge = 0, over64k = 0;
      long bytesTotal = 0;
      long t0 = System.nanoTime();
      List<Path> all = new ArrayList<>();
      for (String d : args) {
         try (Stream<Path> s = Files.walk(Path.of(d))) {
            s.filter(p -> p.toString().endsWith(".lua") && Files.isRegularFile(p)).forEach(all::add);
         }
      }
      for (Path f : all) {
         files++;
         LuaClosure c;
         try {
            c = vm.load(new String(Files.readAllBytes(f), StandardCharsets.UTF_8), f.getFileName().toString());
         } catch (Throwable e) {
            parseFail++;
            continue;
         }
         List<Prototype> ps = new ArrayList<>();
         collect(c.prototype, ps);
         for (Prototype p : ps) {
            protos++;
            String[] why = new String[1];
            byte[] bytes;
            try {
               bytes = LuaJitCompiler.classFile(p, true, why);
            } catch (Throwable e) {
               // past the 64 KB method limit as one method: compile() takes the split form
               huge++;
               try {
                  if (LuaJitCompiler.compile(p, lookup) != null) {
                     ok++;
                     over64k++;
                     continue;
                  }
               } catch (Throwable e2) {
                  e = e2;
               }
               threw++;
               String m = String.valueOf(p.pzoptJitWhy != null ? p.pzoptJitWhy : e);
               String k = "over 64 KB, not split: " + (m.length() > 120 ? m.substring(0, 120) : m).replaceAll("[0-9]+", "N");
               reasons.merge(k, 1, Integer::sum);
               reasonExample.putIfAbsent(k, f + " " + LuaJitCompiler.describe(p) + ": " + m);
               continue;
            }
            if (bytes == null) {
               refused++;
               String k = why[0] == null ? "?" : why[0].replaceAll("[0-9]+", "N");
               reasons.merge(k, 1, Integer::sum);
               reasonExample.putIfAbsent(k, f + " " + LuaJitCompiler.describe(p) + ": " + why[0]);
               continue;
            }
            int len = LuaJitCompiler.codeLength(bytes);
            if (FIT && len < 60000) {
               double[] x = new double[39];
               for (int pc = 0; pc < p.code.length; pc++) {
                  x[Math.min(p.code[pc] & 63, 37)]++;
               }
               x[38] = 1;
               for (int i = 0; i < 39; i++) {
                  xty[i] += x[i] * len;
                  for (int j = 0; j < 39; j++) {
                     xtx[i][j] += x[i] * x[j];
                  }
                  opCount[i] += x[i];
               }
            }
            bytesTotal += len;
            int b = len < 500 ? 0 : len < 1000 ? 1 : len < 2000 ? 2 : len < 4000 ? 3 : len < 8000 ? 4 : len < 16000 ? 5 : len < 32000 ? 6 : 7;
            sizeBuckets[b]++;
            if (len > 8000) {
               huge++;
            }
            try {
               LuaJitCode code = LuaJitCompiler.compile(p, lookup);
               if (code != null) {
                  ok++;
               } else {
                  refused++;
                  String k = "not compiled: " + String.valueOf(p.pzoptJitWhy).replaceAll("[0-9]+", "N");
                  reasons.merge(k, 1, Integer::sum);
                  reasonExample.putIfAbsent(k, f + " " + LuaJitCompiler.describe(p) + ": " + p.pzoptJitWhy);
               }
            } catch (Throwable e) {
               threw++;
               String m = String.valueOf(e);
               String k = "define threw " + (m.length() > 160 ? m.substring(0, 160) : m).replaceAll("[0-9]+", "N");
               reasons.merge(k, 1, Integer::sum);
               reasonExample.putIfAbsent(k, f + " " + LuaJitCompiler.describe(p) + ": " + m);
            }
         }
      }
      long ms = (System.nanoTime() - t0) / 1_000_000;
      System.out.println("files=" + files + " parse_fail=" + parseFail + " functions=" + protos + " compiled=" + ok
            + " refused=" + refused + " threw=" + threw + " over_8000_bytes=" + huge + " over_64k_split=" + over64k + " jvm_code_kb=" + bytesTotal / 1024
            + " ms=" + ms);
      System.out.println("code size buckets <500 <1k <2k <4k <8k <16k <32k >=32k: " + java.util.Arrays.toString(sizeBuckets));
      if (FIT) {
         fit();
      }
      reasons.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(40)
            .forEach(e -> System.out.println(String.format("%7d  %s%n         e.g. %s", e.getValue(), e.getKey(), reasonExample.get(e.getKey()))));
   }

   static final boolean FIT = Boolean.getBoolean("corpus.fit");
   static final double[][] xtx = new double[39][39];
   static final double[] xty = new double[39];
   static final double[] opCount = new double[39];

   /** Least squares: JVM bytes per Lua opcode (ridge-regularised Gaussian elimination). */
   static void fit() {
      int n = 39;
      double[][] m = new double[n][n + 1];
      for (int i = 0; i < n; i++) {
         System.arraycopy(xtx[i], 0, m[i], 0, n);
         m[i][i] += 1e-3;
         m[i][n] = xty[i];
      }
      for (int c = 0; c < n; c++) {
         int piv = c;
         for (int r = c + 1; r < n; r++) {
            if (Math.abs(m[r][c]) > Math.abs(m[piv][c])) {
               piv = r;
            }
         }
         double[] t = m[c];
         m[c] = m[piv];
         m[piv] = t;
         for (int r = 0; r < n; r++) {
            if (r != c && m[c][c] != 0) {
               double f = m[r][c] / m[c][c];
               for (int k = c; k <= n; k++) {
                  m[r][k] -= f * m[c][k];
               }
            }
         }
      }
      String[] names = {"MOVE", "LOADK", "LOADBOOL", "LOADNIL", "GETUPVAL", "GETGLOBAL", "GETTABLE", "SETGLOBAL", "SETUPVAL", "SETTABLE",
         "NEWTABLE", "SELF", "ADD", "SUB", "MUL", "DIV", "MOD", "POW", "UNM", "NOT", "LEN", "CONCAT", "JMP", "EQ", "LT", "LE", "TEST",
         "TESTSET", "CALL", "TAILCALL", "RETURN", "FORLOOP", "FORPREP", "TFORLOOP", "SETLIST", "CLOSE", "CLOSURE", "VARARG", "const"};
      double total = 0;
      for (int i = 0; i < n; i++) {
         double b = m[i][i] != 0 ? m[i][n] / m[i][i] : 0;
         total += b * opCount[i];
      }
      for (int i = 0; i < n; i++) {
         double b = m[i][i] != 0 ? m[i][n] / m[i][i] : 0;
         System.out.println(String.format("fit %-9s %8.1f bytes each  x %9.0f = %5.1f %%", names[i], b, opCount[i], 100 * b * opCount[i] / total));
      }
   }

   static void collect(Prototype p, List<Prototype> out) {
      out.add(p);
      if (p.prototypes != null) {
         for (Prototype q : p.prototypes) {
            collect(q, out);
         }
      }
   }
}
