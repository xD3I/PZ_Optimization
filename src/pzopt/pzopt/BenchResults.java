package pzopt;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * A benchmark run's results (docs/plan-builtin-benchmark.md, techniques 4, 13, 15-17, 20-23, 29): one properties file
 * per pass ({@code step-NN.properties}), aggregated at the end into the report: {@code report.txt} (tab-separated
 * lines the Lua report screen reads), {@code report.json}, {@code report.html} (standalone, inline SVG charts) and a
 * line in {@code history.tsv} (the player's own leaderboard and the "same PC, previous run" comparison).
 */
public final class BenchResults {
   private BenchResults() {
   }

   static File stepFile(String run, int step) {
      return new File(BenchPlan.runDir(run), String.format("step-%02d.properties", step));
   }

   static void writeStep(String run, int step, Map<String, String> r) {
      Properties p = new Properties();
      for (Map.Entry<String, String> e : r.entrySet()) {
         p.setProperty(e.getKey(), e.getValue() == null ? "" : e.getValue());
      }
      File f = stepFile(run, step);
      f.getParentFile().mkdirs();
      try (OutputStream out = new FileOutputStream(f)) {
         p.store(out, "benchmark pass");
      } catch (IOException e) {
         Log.warn("benchmark: could not write " + f + ": " + e);
      }
   }

   static void writeFailed(String run, int step, String[] parts, String reason) {
      Map<String, String> m = new LinkedHashMap<>();
      m.put("scenario", parts[0]);
      m.put("config", parts[1]);
      m.put("pass", parts[2]);
      m.put("status", "failed");
      m.put("invalid_reason", reason);
      writeStep(run, step, m);
   }

   /** Technique 13: why a pass is not a measurement, or what weakens it. Sets status, invalid_reason, warnings. */
   static void validate(Map<String, String> r) {
      List<String> invalid = new ArrayList<>();
      List<String> warn = new ArrayList<>();
      if (!"complete".equals(r.get("route_status"))) {
         invalid.add("the route did not complete (" + r.get("route_status") + (r.get("route_reason").isEmpty() ? "" : ": " + r.get("route_reason")) + ")");
      }
      double frames = d(r, "frames"), ticks = d(r, "ticks");
      if (frames < 100) {
         invalid.add("only " + (int)frames + " frames recorded");
      }
      if (ticks > 0 && d(r, "unfocused_frames") / ticks > 0.02) {
         invalid.add(String.format(Locale.ROOT, "the game window lost focus for %.0f %% of the pass", 100 * d(r, "unfocused_frames") / ticks));
      }
      if (d(r, "paused_frames") > 0) {
         invalid.add("the game was paused during the pass");
      }
      // other programs' CPU (machine load minus the game's): 36 % on the desktop (a database import) halved a Louisville
      // pass, 99 -> 56 fps; a quarter of the machine taken is a different machine
      double bg = d(r, "cpu_background_pct");
      if (bg >= 25) {
         invalid.add(String.format(Locale.ROOT, "other programs used %.0f %% of the CPU during the pass", bg));
      } else if (bg >= 10) {
         warn.add(String.format(Locale.ROOT, "other programs used %.0f %% of the CPU", bg));
      }
      double f0 = d(r, "cpu_mhz_first"), f1 = d(r, "cpu_mhz_last");
      if (f0 > 0 && f1 > 0 && f1 < 0.9 * f0) {
         warn.add(String.format(Locale.ROOT, "the CPU clock fell %.0f %% during the pass (heat or power limit)", 100 * (1 - f1 / f0)));
      }
      // held at the frame cap or the refresh: the number is the limit, not what the PC can do (the stock passes of run
      // bench-fab sat at the game's own 60 fps limiter)
      double cap = d(r, "cap_fps"), fps = d(r, "fps");
      boolean held = cap > 0 && fps >= 0.95 * cap || d(r, "bound_cap_pct") >= 60;
      r.put("cap_held", held ? "1" : "0");
      if (held) {
         warn.add(cap > 0 ? String.format(Locale.ROOT, "held at the frame cap (%d fps): the result is the cap, not the PC's limit", (int)cap)
               : "held at the screen's refresh (v-sync): the result is the refresh rate, not the PC's limit");
      }
      if (r.containsKey("horde_left") && d(r, "horde_left") > 0) { // the horde scenario ends when the horde is dead: here end_cap ended it
         warn.add(String.format(Locale.ROOT, "%d of %d horde zombies were still alive at the end (%s)", (int)d(r, "horde_left"), (int)d(r, "horde_total"), r.get("horde_end")));
      }
      r.put("status", invalid.isEmpty() ? "ok" : "invalid");
      r.put("invalid_reason", String.join("; ", invalid));
      r.put("warnings", String.join("; ", warn));
   }

   static double d(Map<String, String> m, String k) {
      String v = m.get(k);
      if (v == null || v.isEmpty() || v.equals("nan")) {
         return Double.NaN;
      }
      try {
         return Double.parseDouble(v);
      } catch (NumberFormatException e) {
         return Double.NaN;
      }
   }

   static List<Map<String, String>> readSteps(String run) {
      List<Map<String, String>> out = new ArrayList<>();
      File dir = BenchPlan.runDir(run);
      for (int i = 0; i < 1000; i++) {
         File f = stepFile(run, i);
         if (!f.isFile()) {
            if (i > 200) {
               break;
            }
            continue;
         }
         Properties p = new Properties();
         try (InputStream in = new FileInputStream(f)) {
            p.load(in);
         } catch (IOException e) {
            continue;
         }
         Map<String, String> m = new LinkedHashMap<>();
         for (String k : p.stringPropertyNames()) {
            m.put(k, p.getProperty(k));
         }
         m.put("step", Integer.toString(i));
         out.add(m);
      }
      return out;
   }

   // ------------------------------------------------------------------ aggregation

   /** One scenario under one config across its passes. */
   static final class Agg {
      String scenario, config;
      List<Map<String, String>> passes = new ArrayList<>(), bad = new ArrayList<>();
      BenchStats.Passes fps, low1, low01, p99, score, stutters, latency, gpuMs, stepMs;
      int[] outliers = new int[0];
      Map<String, Double> mean = new LinkedHashMap<>();

      double[] col(String k, boolean dropOutliers) {
         List<Double> v = new ArrayList<>();
         for (int i = 0; i < passes.size(); i++) {
            if (dropOutliers && contains(outliers, i)) {
               continue;
            }
            double x = d(passes.get(i), k);
            if (!Double.isNaN(x)) {
               v.add(x);
            }
         }
         double[] a = new double[v.size()];
         for (int i = 0; i < a.length; i++) {
            a[i] = v.get(i);
         }
         return a;
      }

      /** True when a pass sat at the frame cap or the refresh (BenchResults.validate's cap_held). */
      boolean held() {
         for (Map<String, String> p : passes) {
            double cap = d(p, "cap_fps");
            if ("1".equals(p.get("cap_held")) || cap > 0 && d(p, "fps") >= 0.95 * cap) {
               return true;
            }
         }
         return false;
      }
   }

   static boolean contains(int[] a, int x) {
      for (int v : a) {
         if (v == x) {
            return true;
         }
      }
      return false;
   }

   static final String[] MEAN_KEYS = {"fps", "low1_avg", "low01_avg", "low1_pct", "low01_pct", "median_ms", "p99_ms", "p999_ms", "max_ms", "stutters", "over50",
      "jitter_ms", "below60_pct", "below30_pct", "cv_pct", "score", "bound_gpu_pct", "bound_game_pct", "bound_render_pct", "bound_cap_pct", "gpu_ms_mean",
      "step_ms_mean", "render_ms_mean", "latency_median_ms", "latency_p99_ms", "cpu_process_pct", "cpu_system_pct", "cpu_background_pct", "game_thread_pct",
      "render_thread_pct", "gpu_busy_pct", "power_total_w", "joules_per_frame", "fps_per_watt", "gc_count", "gc_ms", "gc_max_pause_ms", "heap_peak_mb",
      "alloc_mb_per_s", "chunks_per_s", "load_seconds", "cap_fps", "pacing_smooth_pct", "refresh_miss_pct", "bound_mixed_pct", "present_ms_mean", "zombies_start", "zombies_end", "vehicles_end"};

   static Map<String, Agg> aggregate(List<Map<String, String>> steps) {
      Map<String, Agg> m = new LinkedHashMap<>();
      for (BenchSuite.Scenario s : BenchSuite.ALL) {
         for (String cfg : new String[] {"A", "B"}) {
            for (Map<String, String> st : steps) {
               if (s.id.equals(st.get("scenario")) && cfg.equals(st.get("config"))) {
                  Agg a = m.computeIfAbsent(s.id + "/" + cfg, k -> new Agg());
                  a.scenario = s.id;
                  a.config = cfg;
                  if ("ok".equals(st.get("status"))) {
                     a.passes.add(st);
                  } else {
                     a.bad.add(st);
                  }
               }
            }
         }
      }
      for (Agg a : m.values()) {
         a.fps = BenchStats.passes(a.col("fps", false));
         a.outliers = a.fps.outliers;
         a.fps = BenchStats.passes(a.col("fps", true));
         a.low1 = BenchStats.passes(a.col("low1_avg", true));
         a.low01 = BenchStats.passes(a.col("low01_avg", true));
         a.p99 = BenchStats.passes(a.col("p99_ms", true));
         a.score = BenchStats.passes(a.col("score", true));
         a.stutters = BenchStats.passes(a.col("stutters", true));
         a.latency = BenchStats.passes(a.col("latency_median_ms", true));
         for (String k : MEAN_KEYS) {
            double[] v = a.col(k, true);
            a.mean.put(k, v.length == 0 ? Double.NaN : BenchStats.mean(v));
         }
      }
      return m;
   }

   // ------------------------------------------------------------------ report

   static File reportFile(String run, String name) {
      return new File(BenchPlan.runDir(run), name);
   }

   /** Writes report.txt / .json / .html and the history line (once). */
   static synchronized void buildReport(String run) {
      if (run == null) {
         return;
      }
      File txt = reportFile(run, "report.txt");
      List<Map<String, String>> steps = readSteps(run);
      Properties plan = BenchPlan.plan();
      collectScreenshots(run);
      Map<String, Agg> agg = aggregate(steps);
      Map<String, String> sys = firstSys(steps);
      String key = BenchSystem.key(sys);
      Map<String, String> prev = previousRun(run, key);
      StringBuilder t = new StringBuilder();
      // a report built again later (another run's plan is current) keeps the label and mode its first build wrote
      String[] old = new String[0];
      try {
         for (String l : txt.isFile() ? Files.readAllLines(txt.toPath()) : new ArrayList<String>()) {
            if (l.startsWith("H\t")) {
               old = l.split("\t", -1);
               break;
            }
         }
      } catch (IOException e) {
         // none: built for the first time
      }
      boolean current = plan != null && run.equals(plan.getProperty("run"));
      String label = current ? plan.getProperty("label", "") : old.length > 3 ? old[3] : "";
      String mode = current ? plan.getProperty("mode", "standard") : old.length > 4 && !old[4].isEmpty() ? old[4] : "standard";
      t.append("H\t").append(run).append('\t').append(dateOf(run)).append('\t').append(label).append('\t').append(mode).append('\t').append(key).append('\n');
      for (Map.Entry<String, String> e : sys.entrySet()) {
         t.append("SYS\t").append(e.getKey()).append('\t').append(e.getValue().replace('\t', ' ')).append('\n');
      }
      double[] scoreA = configScore(agg, "A"), scoreB = configScore(agg, "B");
      if (scoreA[0] > 0) {
         t.append("SCORE\tA\t").append(f0(scoreA[0])).append('\t').append(f0(scoreA[1])).append('\n');
      }
      if (scoreB[0] > 0) {
         t.append("SCORE\tB\t").append(f0(scoreB[0])).append('\t').append(f0(scoreB[1])).append('\n');
      }
      for (Agg a : agg.values()) {
         BenchSuite.Scenario s = BenchSuite.byId(a.scenario);
         StringBuilder reasons = new StringBuilder();
         for (Map<String, String> b : a.bad) {
            reasons.append(reasons.length() > 0 ? " | " : "").append("pass ").append(b.get("pass")).append(": ").append(b.get("invalid_reason"));
         }
         for (int o : a.outliers) {
            reasons.append(reasons.length() > 0 ? " | " : "").append("pass ").append(a.passes.get(o).get("pass")).append(": outlier, left out");
         }
         t.append("S\t").append(a.scenario).append('\t').append(s.title).append('\t').append(a.config).append('\t').append(a.fps.n)
               .append('\t').append(f1(a.fps.mean)).append('\t').append(a.fps.n >= 2 ? f1(a.fps.ci95) : "-").append('\t').append(f1(a.low1.mean)).append('\t').append(f1(a.low01.mean))
               .append('\t').append(f2(a.p99.mean)).append('\t').append(f1(a.stutters.mean)).append('\t').append(f0(a.score.mean)).append('\t').append(f1(a.fps.cvPct))
               .append('\t').append(f0(a.mean.get("bound_gpu_pct"))).append('\t').append(f0(a.mean.get("bound_game_pct"))).append('\t').append(f0(a.mean.get("bound_render_pct")))
               .append('\t').append(f0(a.mean.get("bound_cap_pct"))).append('\t').append(f1(a.mean.get("latency_median_ms"))).append('\t').append(f0(a.mean.get("gc_max_pause_ms")))
               .append('\t').append(f1(a.mean.get("load_seconds"))).append('\t').append(a.passes.size() + a.bad.size()).append('\t').append(reasons.toString().replace('\t', ' '))
               .append('\t').append(f1(stabilityPct(a.col("fps", false))))
               .append('\n');
         StringBuilder detail = new StringBuilder();
         for (String k : MEAN_KEYS) {
            detail.append(k).append('=').append(f2(a.mean.get(k))).append(' ');
         }
         t.append("D\t").append(a.scenario).append('\t').append(a.config).append('\t').append(detail.toString().trim()).append('\n');
         if (!a.passes.isEmpty()) {
            Map<String, String> first = a.passes.get(0);
            t.append("CURVE\t").append(a.scenario).append('\t').append(a.config).append('\t').append(first.getOrDefault("curve", "")).append('\n');
            t.append("HIST\t").append(a.scenario).append('\t').append(a.config).append('\t').append(meanCsv(a.passes, "hist")).append('\n');
            t.append("FT\t").append(a.scenario).append('\t').append(a.config).append('\t').append(frameTrace(run, first, 400)).append('\n');
            if (!first.getOrDefault("game_phases", "").isEmpty()) {
               t.append("PHASES\t").append(a.scenario).append('\t').append(a.config).append('\t').append(first.get("game_phases")).append('\n');
            }
            double[][] rows = new double[a.passes.size()][];
            for (int i = 0; i < a.passes.size(); i++) {
               Map<String, String> ps = a.passes.get(i);
               t.append("PERSEC\t").append(a.scenario).append('\t').append(a.config).append('\t').append(ps.get("pass")).append('\t').append(ps.getOrDefault("per_second", "")).append('\n');
               rows[i] = csvD(ps.getOrDefault("per_second", ""));
            }
            if (rows.length >= 2) {
               double[] map = BenchStats.spikeMap(rows);
               int recurring = 0, random = 0;
               for (double v : map) {
                  if (v >= 0.5) {
                     recurring++;
                  } else if (v > 0) {
                     random++;
                  }
               }
               t.append("SPIKES\t").append(a.scenario).append('\t').append(a.config).append('\t').append(recurring).append('\t').append(random).append('\n');
            }
            for (Map<String, String> ps : a.passes) {
               if (!ps.getOrDefault("warnings", "").isEmpty()) {
                  t.append("WARN\t").append(a.scenario).append('\t').append(a.config).append('\t').append(ps.get("pass")).append('\t').append(ps.get("warnings")).append('\n');
               }
            }
         }
      }
      for (BenchSuite.Scenario s : BenchSuite.ALL) {
         Agg a = agg.get(s.id + "/A"), b = agg.get(s.id + "/B");
         if (a != null && b != null && a.fps.n > 0 && b.fps.n > 0) {
            double[] r = BenchStats.ratioCi(a.col("fps", true), b.col("fps", true), 4000, 42);
            double[] w = BenchStats.welch(a.col("fps", true), b.col("fps", true));
            double[] rl = BenchStats.ratioCi(b.col("low1_avg", true), a.col("low1_avg", true), 4000, 43);
            // r = mean(B) / mean(A): the speedup of A over B is its inverse
            // which side sat at a cap: the speed-up is then a bound (A held: at least; B held: at most)
            String held = (a.held() ? "A" : "") + (b.held() ? "B" : "");
            t.append("AB\t").append(s.id).append('\t').append(f2(1 / r[0])).append('\t').append(f2(1 / r[2])).append('\t').append(f2(1 / r[1]))
                  .append('\t').append(f3(w[2])).append('\t').append(f2(rl[0])).append('\t').append(held.isEmpty() ? "-" : held).append('\n');
         }
      }
      if (prev != null) {
         t.append("PREV\t").append(prev.get("run")).append('\t').append(prev.getOrDefault("label", "")).append('\n');
         Map<String, double[]> prevFps = parseHistFps(prev.getOrDefault("fps", ""));
         for (BenchSuite.Scenario s : BenchSuite.ALL) {
            Agg a = agg.get(s.id + "/A");
            double[] pv = prevFps.get(s.id);
            if (a == null || pv == null || a.fps.n == 0) {
               continue;
            }
            double[] now = a.col("fps", true);
            double[] w = BenchStats.welch(pv, now);
            double pm = BenchStats.mean(pv), nm = BenchStats.mean(now);
            double delta = pm > 0 ? 100 * (nm - pm) / pm : Double.NaN;
            String verdict;
            if (!Double.isNaN(w[2])) {
               verdict = w[2] < 0.05 ? (delta > 0 ? "faster" : "slower") : "same";
            } else {
               verdict = Math.abs(delta) < 3 ? "same" : (delta > 0 ? "faster?" : "slower?");
            }
            t.append("CMP\t").append(s.id).append('\t').append(f1(pm)).append('\t').append(f1(nm)).append('\t').append(f1(delta)).append('\t').append(f3(w[2])).append('\t').append(verdict).append('\n');
         }
      }
      Agg sw = agg.get("sweep/A");
      if (sw != null && !sw.passes.isEmpty()) {
         for (String line : sweepLines(sw.passes)) {
            t.append(line.startsWith(BenchSweep.NOISE + "\t") ? "SWEEPNOISE\t" + line.substring(BenchSweep.NOISE.length() + 1) : "SWEEP\t" + line).append('\n');
         }
      }
      for (String[] rec : BenchAdvice.advise(agg, sys)) {
         t.append("REC\t").append(rec[0]).append('\t').append(rec[1]).append('\n');
      }
      String html = BenchHtml.render(run, t.toString(), agg, steps);
      writeString(reportFile(run, "report.html"), html);
      t.append("FILE\t").append(reportFile(run, "report.html").getAbsolutePath()).append('\n');
      writeString(txt, t.toString());
      writeString(reportFile(run, "report.json"), BenchHtml.json(run, sys, agg, steps));
      appendHistory(run, key, BenchSystem.machineKey(sys), label, scoreA[0], agg);
      Log.info("benchmark: report of run " + run + " written to " + txt.getParentFile());
   }

   /**
    * Frame rate stability as 3DMark's stress tests define it: the slowest pass's average fps over the fastest one's, in %
    * (3DMark passes a system at 97 %). Every valid pass counts, outliers included: they are what it is about. NaN under 2.
    */
   static double stabilityPct(double[] fps) {
      if (fps.length < 2) {
         return Double.NaN;
      }
      double lo = Double.MAX_VALUE, hi = 0;
      for (double v : fps) {
         lo = Math.min(lo, v);
         hi = Math.max(hi, v);
      }
      return hi > 0 ? 100 * lo / hi : Double.NaN;
   }

   /** The feature sweep averaged over its passes: "key\tlabel\tbase ms\ton ms\tcost ms\tgpu cost ms\tpasses\tshift ms", the noise line last. */
   static List<String> sweepLines(List<Map<String, String>> passes) {
      Map<String, double[]> sum = new LinkedHashMap<>();
      Map<String, String> label = new LinkedHashMap<>();
      for (Map<String, String> p : passes) {
         for (String e : p.getOrDefault("sweep", "").split(";")) {
            String[] c = e.split("\\|");
            if (c.length < 6) {
               continue;
            }
            double[] a = sum.computeIfAbsent(c[0], k -> new double[6]);
            label.put(c[0], c[1]);
            for (int i = 0; i < 5; i++) {
               try {
                  a[i] += i + 2 < c.length ? Double.parseDouble(c[2 + i]) : Double.NaN;
               } catch (NumberFormatException ex) {
                  a[i] = Double.NaN;
               }
            }
            a[5]++;
         }
      }
      List<String> out = new ArrayList<>();
      for (Map.Entry<String, double[]> e : sum.entrySet()) {
         double[] a = e.getValue();
         if (BenchSweep.NOISE.equals(e.getKey())) {
            // the noise of one pass's cost estimate; the mean over the passes has 1/sqrt(passes) of it:
            // "_noise\tms\tgpu ms\tpasses"
            out.add(e.getKey() + "\t" + f3(a[2] / a[5] / Math.sqrt(a[5])) + "\t" + f3(a[3] / a[5] / Math.sqrt(a[5])) + "\t" + (int)a[5]);
            continue;
         }
         // "key\tlabel\tbase\ton\tcost\tgpu cost\tpasses\tshift": the costs to 3 decimals (the floor is ~0.01-0.04 ms)
         out.add(e.getKey() + "\t" + label.get(e.getKey()) + "\t" + f3(a[0] / a[5]) + "\t" + f3(a[1] / a[5]) + "\t" + f3(a[2] / a[5]) + "\t" + f3(a[3] / a[5]) + "\t" + (int)a[5]
               + "\t" + f3(a[4] / a[5]));
      }
      return out;
   }

   /** Geometric mean over the scenarios of the per-scenario mean score, and its +- from the scenarios' CIs (first order). */
   static double[] configScore(Map<String, Agg> agg, String cfg) {
      List<Double> v = new ArrayList<>();
      double relVar = 0;
      for (Agg a : agg.values()) {
         if (cfg.equals(a.config) && BenchSuite.scored(a.scenario) && a.score.n > 0 && a.score.mean > 0) {
            v.add(a.score.mean);
            relVar += a.score.n >= 2 ? Math.pow(a.score.ci95 / a.score.mean, 2) : Double.NaN; // one pass: no interval
         }
      }
      if (v.isEmpty()) {
         return new double[] {0, 0};
      }
      double[] arr = new double[v.size()];
      for (int i = 0; i < arr.length; i++) {
         arr[i] = v.get(i);
      }
      double g = BenchStats.geomean(arr);
      return new double[] {g, g * Math.sqrt(relVar) / arr.length};
   }

   static Map<String, String> firstSys(List<Map<String, String>> steps) {
      Map<String, String> m = new LinkedHashMap<>();
      for (Map<String, String> st : steps) {
         if (!"A".equals(st.get("config")) && !m.isEmpty()) {
            continue;
         }
         boolean any = false;
         for (Map.Entry<String, String> e : st.entrySet()) {
            if (e.getKey().startsWith("sys.")) {
               m.put(e.getKey().substring(4), e.getValue());
               any = true;
            }
         }
         if (any && "A".equals(st.get("config"))) {
            break;
         }
      }
      return m;
   }

   private static String meanCsv(List<Map<String, String>> passes, String key) {
      double[] sum = null;
      int k = 0;
      for (Map<String, String> p : passes) {
         double[] v = csvD(p.getOrDefault(key, ""));
         if (v.length == 0) {
            continue;
         }
         if (sum == null) {
            sum = new double[v.length];
         }
         for (int i = 0; i < Math.min(v.length, sum.length); i++) {
            sum[i] += v[i];
         }
         k++;
      }
      if (sum == null) {
         return "";
      }
      StringBuilder b = new StringBuilder();
      for (int i = 0; i < sum.length; i++) {
         b.append(i > 0 ? "," : "").append(f2(sum[i] / k));
      }
      return b.toString();
   }

   static double[] csvD(String s) {
      if (s == null || s.isBlank()) {
         return new double[0];
      }
      String[] p = s.split(",");
      double[] v = new double[p.length];
      for (int i = 0; i < p.length; i++) {
         try {
            v[i] = Double.parseDouble(p[i].trim());
         } catch (NumberFormatException e) {
            v[i] = Double.NaN;
         }
      }
      return v;
   }

   /** The pass's frame times reduced to {@code points} values (each the slowest frame of its slice: spikes stay visible). */
   static String frameTrace(String run, Map<String, String> pass, int points) {
      File f = new File(BenchPlan.runDir(run), String.format("step-%02d-%s-%s-%s-frames.csv", Integer.parseInt(pass.get("step")), pass.get("scenario"), pass.get("config"), pass.get("pass")));
      if (!f.isFile()) {
         return "";
      }
      List<Float> ms = new ArrayList<>();
      try {
         for (String line : Files.readAllLines(f.toPath())) {
            String[] c = line.split(",");
            if (c.length > 3 && !c[0].equals("Application")) {
               ms.add(Float.parseFloat(c[3]));
            }
         }
      } catch (IOException | NumberFormatException e) {
         return "";
      }
      int n = ms.size();
      if (n == 0) {
         return "";
      }
      StringBuilder b = new StringBuilder();
      int k = Math.min(points, n);
      for (int i = 0; i < k; i++) {
         int a = (int)((long)i * n / k), e = (int)((long)(i + 1) * n / k);
         float mx = 0;
         for (int j = a; j < Math.max(e, a + 1); j++) {
            mx = Math.max(mx, ms.get(j));
         }
         b.append(i > 0 ? "," : "").append(String.format(Locale.ROOT, "%.2f", mx));
      }
      return b.toString();
   }

   private static void collectScreenshots(String run) {
      File shots = new File(UserOptions.zomboidDir(), "Screenshots");
      File[] list = shots.listFiles((d, n) -> n.startsWith("pzopt-bench-" + run));
      if (list == null) {
         return;
      }
      for (File f : list) {
         File dst = new File(BenchPlan.runDir(run), f.getName().replace("pzopt-bench-" + run + "-", "shot-"));
         try {
            Files.move(f.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
         } catch (IOException e) {
            Log.warn("benchmark: could not move " + f + ": " + e);
         }
      }
   }

   static String dateOf(String run) {
      try {
         java.util.Date d = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").parse(run);
         return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(d);
      } catch (Exception e) {
         return run;
      }
   }

   // ------------------------------------------------------------------ history (technique 16, 22)

   static File historyFile() {
      return new File(BenchPlan.dir(), "history.tsv");
   }

   private static void appendHistory(String run, String key, String machine, String label, double score, Map<String, Agg> agg) {
      List<String> lines = readHistoryLines();
      for (String l : lines) {
         if (l.startsWith(run + "\t")) {
            return; // the report was rebuilt; the run is listed already
         }
      }
      StringBuilder fps = new StringBuilder();
      for (Agg a : agg.values()) {
         if (!"A".equals(a.config) || a.fps.n == 0 || !BenchSuite.scored(a.scenario)) {
            continue;
         }
         fps.append(fps.length() > 0 ? ";" : "").append(a.scenario).append('=');
         double[] v = a.col("fps", true);
         for (int i = 0; i < v.length; i++) {
            fps.append(i > 0 ? "|" : "").append(f1(v[i]));
         }
      }
      String line = run + "\t" + dateOf(run) + "\t" + key + "\t" + machine + "\t" + f0(score) + "\t" + label.replace('\t', ' ') + "\t" + fps + "\n";
      try {
         historyFile().getParentFile().mkdirs();
         Files.writeString(historyFile().toPath(), line, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
      } catch (IOException e) {
         Log.warn("benchmark: could not append to " + historyFile() + ": " + e);
      }
   }

   static List<String> readHistoryLines() {
      try {
         return historyFile().isFile() ? Files.readAllLines(historyFile().toPath()) : new ArrayList<>();
      } catch (IOException e) {
         return new ArrayList<>();
      }
   }

   static Map<String, String> parseHistory(String line) {
      String[] c = line.split("\t", -1);
      if (c.length < 7) {
         return null;
      }
      Map<String, String> m = new LinkedHashMap<>();
      m.put("run", c[0]);
      m.put("date", c[1]);
      m.put("key", c[2]);
      m.put("machine", c[3]);
      m.put("score", c[4]);
      m.put("label", c[5]);
      m.put("fps", c[6]);
      return m;
   }

   /** The newest earlier run with the same comparability key (same PC, screen, game, build, settings, cap). */
   static Map<String, String> previousRun(String run, String key) {
      Map<String, String> best = null;
      for (String l : readHistoryLines()) {
         Map<String, String> h = parseHistory(l);
         if (h != null && key.equals(h.get("key")) && h.get("run").compareTo(run) < 0) {
            best = h;
         }
      }
      return best;
   }

   static Map<String, double[]> parseHistFps(String s) {
      Map<String, double[]> m = new LinkedHashMap<>();
      for (String part : s.split(";")) {
         int eq = part.indexOf('=');
         if (eq <= 0) {
            continue;
         }
         String[] v = part.substring(eq + 1).split("\\|");
         double[] a = new double[v.length];
         for (int i = 0; i < v.length; i++) {
            try {
               a[i] = Double.parseDouble(v[i]);
            } catch (NumberFormatException e) {
               a[i] = Double.NaN;
            }
         }
         m.put(part.substring(0, eq), a);
      }
      return m;
   }

   /** "run\tdate\tscore\tlabel\tsame machine (1/0)\tfps summary\tsetup summary" per past run, newest first. */
   static String historyText() {
      List<String> lines = readHistoryLines();
      StringBuilder b = new StringBuilder();
      for (int i = lines.size() - 1; i >= 0; i--) {
         Map<String, String> h = parseHistory(lines.get(i));
         if (h == null) {
            continue;
         }
         b.append(h.get("run")).append('\t').append(h.get("date")).append('\t').append(h.get("score")).append('\t').append(h.get("label")).append('\t')
               .append(h.get("machine")).append('\t').append(h.get("fps")).append('\t').append(BenchSetup.summary(h.get("run"))).append('\n');
      }
      return b.toString();
   }

   /**
    * Two past runs side by side (Past results, Compare): "H\trun\tdate\tlabel\tscore" for run a then run b; per scenario
    * either measured with PZ Optimization on "S\tid\ttitle\tfpsA\tfpsB\tlow1A\tlow1B" ("" where a run lacks it); then
    * the setup rows of {@link BenchSetup#compare} (benchmark options, PZ Optimization settings, game settings, mods, PC).
    */
   static String compareRuns(String a, String b) {
      StringBuilder out = new StringBuilder();
      String[] runs = {a, b};
      List<Map<String, String[]>> scen = new ArrayList<>();
      List<Map<String, String>> sys = new ArrayList<>();
      for (String run : runs) {
         Map<String, String> h = null;
         for (String l : readHistoryLines()) {
            Map<String, String> p = parseHistory(l);
            if (p != null && run.equals(p.get("run"))) {
               h = p;
            }
         }
         String score = h != null ? h.get("score") : "";
         score = "0".equals(score) ? "" : score; // a run without a valid pass has no score
         Map<String, String[]> s = new LinkedHashMap<>();
         Map<String, String> y = new LinkedHashMap<>();
         for (String line : reportText(run).split("\n")) {
            String[] c = line.split("\t", -1);
            if (c[0].equals("S") && c.length > 7 && "A".equals(c[3])) {
               boolean none = "0".equals(c[4]); // no valid pass: no fps to compare (the report's 0.0 is not a measurement)
               s.put(c[1], new String[] {c[2], none ? "" : c[5], none ? "" : c[7]});
            } else if (c[0].equals("SYS") && c.length > 2) {
               y.put(c[1], c[2]);
            } else if (c[0].equals("SCORE") && c.length > 2 && "A".equals(c[1]) && score.isEmpty()) {
               score = c[2];
            }
         }
         scen.add(s);
         sys.add(y);
         out.append("H\t").append(run).append('\t').append(dateOf(run)).append('\t').append(h != null ? h.get("label") : "").append('\t').append(score).append('\n');
      }
      java.util.Set<String> ids = new java.util.LinkedHashSet<>(scen.get(0).keySet());
      ids.addAll(scen.get(1).keySet());
      for (String id : ids) {
         String[] x = scen.get(0).get(id), y = scen.get(1).get(id);
         out.append("S\t").append(id).append('\t').append((x != null ? x : y)[0]).append('\t').append(x != null ? x[1] : "").append('\t').append(y != null ? y[1] : "")
               .append('\t').append(x != null ? x[2] : "").append('\t').append(y != null ? y[2] : "").append('\n');
      }
      for (String[] r : BenchSetup.compare(BenchSetup.read(a), BenchSetup.read(b), sys.get(0), sys.get(1))) {
         out.append(String.join("\t", r)).append('\n');
      }
      return out.toString();
   }

   static String reportText(String run) {
      if (run == null || run.isEmpty()) {
         return "";
      }
      File f = reportFile(run, "report.txt");
      if (!f.isFile()) {
         buildReport(run);
      }
      try {
         return Files.readString(f.toPath());
      } catch (IOException e) {
         return "error\t" + e;
      }
   }

   static String htmlPath(String run) {
      File f = reportFile(run, "report.html");
      return f.isFile() ? f.getAbsolutePath() : "";
   }

   /** Opens the HTML report in the system's browser (xdg-open / open / start). */
   static String open(String run) {
      String path = htmlPath(run);
      if (path.isEmpty()) {
         return "error\tno report";
      }
      String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
      String[] cmd = os.contains("win") ? new String[] {"cmd", "/c", "start", "", path} : os.contains("mac") ? new String[] {"open", path} : new String[] {"xdg-open", path};
      try {
         new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
         return "ok\t" + path;
      } catch (IOException e) {
         return "error\t" + e.getMessage();
      }
   }

   /**
    * Technique 22: the run as one line of text to paste anywhere (Discord): "PZOB1:" + base64url of the deflated
    * summary (machine, score, per scenario fps / 1 % low / p99 / passes); {@link #decodeShare} reads it back.
    */
   static String shareCode(String run) {
      String txt = reportText(run);
      StringBuilder b = new StringBuilder("v=1\n");
      for (String line : txt.split("\n")) {
         String[] c = line.split("\t", -1);
         if (c[0].equals("SYS") && c.length > 2 && (c[1].equals("cpu") || c[1].equals("gpu") || c[1].equals("screen") || c[1].equals("os") || c[1].equals("pzopt_build") || c[1].equals("frame_cap"))) {
            b.append(c[1]).append('=').append(c[2]).append('\n');
         } else if (c[0].equals("SCORE")) {
            b.append("score.").append(c[1]).append('=').append(c[2]).append('\n');
         } else if (c[0].equals("S") && c.length > 9) {
            b.append("s.").append(c[1]).append('.').append(c[3]).append('=').append(c[5]).append('|').append(c[7]).append('|').append(c[9]).append('|').append(c[4]).append('\n');
         }
      }
      byte[] raw = b.toString().getBytes(StandardCharsets.UTF_8);
      java.util.zip.Deflater def = new java.util.zip.Deflater(9);
      def.setInput(raw);
      def.finish();
      byte[] buf = new byte[raw.length + 64];
      int len = def.deflate(buf);
      def.end();
      return "PZOB1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(buf, len));
   }

   static String decodeShare(String code) {
      if (code == null || !code.startsWith("PZOB1:")) {
         return "";
      }
      try {
         byte[] in = java.util.Base64.getUrlDecoder().decode(code.substring(6).trim());
         java.util.zip.Inflater inf = new java.util.zip.Inflater();
         inf.setInput(in);
         byte[] out = new byte[65536];
         int len = inf.inflate(out);
         inf.end();
         return new String(out, 0, len, StandardCharsets.UTF_8);
      } catch (Exception e) {
         return "";
      }
   }

   static void writeString(File f, String s) {
      try {
         f.getParentFile().mkdirs();
         Files.writeString(f.toPath(), s, StandardCharsets.UTF_8);
      } catch (IOException e) {
         Log.warn("benchmark: could not write " + f + ": " + e);
      }
   }

   static String f0(Double v) {
      return v == null || Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.0f", v);
   }

   static String f1(Double v) {
      return v == null || Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.1f", v);
   }

   static String f2(Double v) {
      return v == null || Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.2f", v);
   }

   static String f3(Double v) {
      return v == null || Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.3f", v);
   }
}
