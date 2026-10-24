package pzopt;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The data of the menu's benchmark report page, laid out as the Grafana run page (harness/grafana/dashboards/
 * pzopt-run.json, 2026-10-21): one pass of a run (a scene, its config, its pass number: the in-game counterpart of a
 * harness run) answered panel by panel from the run folder: the pass's step-NN.properties, its frames CSV, its 2 Hz
 * samples and game-thread profile (BenchTimeline, runs from 2026-10-21 on), the run's setup.txt. A panel the report has
 * no data for is left out; the page then shows "No data in this report" in its place (pzopt_mainscreen_report.lua owns
 * the panels' titles, sizes, units and colours; this class only answers their queries).
 * <p>
 * Lines (tab-separated): {@code VAR kind id label selected}, {@code SEL scene config pass step title}, {@code ROUTE s},
 * then per panel {@code P id} followed by {@code V name value} (stat fields, "" = no data), {@code C col...} and
 * {@code R cell...} (a table), {@code S name t,t,... v,v,...} (a series on route seconds, "nan" = a gap),
 * {@code B name t,... min,... max,...} (every frame as a min / max band per column), {@code HIST bucket first counts,...}.
 */
final class BenchDash {
   static final int BAND_COLUMNS = 600;
   static final double HIST_BUCKET_MS = 0.5; // the panel's bucketSize

   private BenchDash() {
   }

   /** {@code run \t scene \t config \t pass}; empty fields pick the first scene, PZ Optimization's config, the first pass. */
   static String text(String arg) {
      String[] a = (arg == null ? "" : arg).split("\t", -1);
      String run = a[0].trim();
      if (run.isEmpty() || run.contains("..") || run.contains("/") || run.contains("\\")) {
         return "error\tno run";
      }
      List<Map<String, String>> steps = new ArrayList<>();
      for (Map<String, String> s : BenchResults.readSteps(run)) {
         if (s.get("scenario") != null && !"W".equals(s.get("config"))) { // (the warm-up pass is not timed)
            steps.add(s);
         }
      }
      StringBuilder b = new StringBuilder();
      if (steps.isEmpty()) {
         return "error\tno pass";
      }
      String scene = pick(a, 1, steps, "scenario", null, null);
      String cfg = pick(a, 2, steps, "config", "scenario", scene);
      if ((a.length <= 2 || a[2].isEmpty()) && has(steps, scene, "A")) {
         cfg = "A";
      }
      Map<String, String> pass = null;
      LinkedHashSet<String> scenes = new LinkedHashSet<>(), cfgs = new LinkedHashSet<>();
      List<Map<String, String>> passes = new ArrayList<>();
      for (Map<String, String> s : steps) {
         scenes.add(s.get("scenario"));
         if (scene.equals(s.get("scenario"))) {
            cfgs.add(s.get("config"));
            if (cfg.equals(s.get("config"))) {
               passes.add(s);
            }
         }
      }
      if (passes.isEmpty()) {
         return "error\tno pass of " + scene + "/" + cfg;
      }
      String want = a.length > 3 ? a[3].trim() : "";
      if (want.isEmpty()) {
         want = Config.DEV_REPORT_PASS; // (dev: the report tour's pass, benchmark-run.sh ui:report devReportPass=N)
      }
      for (Map<String, String> p : passes) {
         if (want.equals(p.get("pass"))) {
            pass = p;
         }
      }
      if (pass == null) {
         pass = passes.get(0);
      }
      for (String s : scenes) {
         BenchSuite.Scenario sc = BenchSuite.byId(s);
         line(b, "VAR", "scene", s, sc == null ? s : sc.title, s.equals(scene) ? "1" : "0");
      }
      for (String c : cfgs) {
         line(b, "VAR", "config", c, cfgName(c), c.equals(cfg) ? "1" : "0");
      }
      for (Map<String, String> p : passes) {
         String n = p.getOrDefault("pass", "?");
         line(b, "VAR", "pass", n, "Pass " + n + ("ok".equals(p.get("status")) ? "" : " (" + p.getOrDefault("status", "?") + ")"), p == pass ? "1" : "0");
      }
      BenchSuite.Scenario sc = BenchSuite.byId(scene);
      int step = Integer.parseInt(pass.getOrDefault("step", "0"));
      line(b, "SEL", scene, cfg, pass.getOrDefault("pass", ""), Integer.toString(step), sc == null ? scene : sc.title);
      double seconds = BenchResults.d(pass, "seconds");
      line(b, "ROUTE", fmt(seconds));
      line(b, "WINDOW", fmt(BenchDashMore.WINDOW_FROM), fmt(BenchDashMore.WINDOW_TO)); // Grafana's time range (route start -30 s to +120 s)
      File dir = BenchPlan.runDir(run);
      String base = String.format("step-%02d-%s-%s-%s", step, scene, cfg, pass.getOrDefault("pass", ""));
      Frames fr = Frames.read(new File(dir, base + "-frames.csv"));
      Samples sm = Samples.read(new File(dir, base + "-samples.csv"));
      List<String[]> gt = readGameThread(new File(dir, base + "-gamethread.out"), routeStartMs(pass));
      BenchDashMore more = BenchDashMore.read(dir, base, pass); // the in-game sysmon / schedmon / traces (BenchCapture)
      stats(b, pass, fr);
      runTable(b, run, dir, pass, sc, cfg);
      runData(b, dir, base);
      frameCharts(b, fr, more);
      if (!more.machine(b)) { // panels 17 and 19 from the in-game sysmon when the pass has it
         utilization(b, pass, sm);
      } else {
         utilizationThreads(b, sm);
      }
      power(b, pass, sm, fr);
      gameThread(b, pass, gt);
      ModLuaDash.panels(b, dir, base); // the Mods (Lua) row: ModLuaProfile's files of this pass
      gcPauses(b, pass);
      configured(b, run);
      counters(b, pass);
      more.panels(b);
      return b.toString();
   }

   static String cfgName(String c) {
      return "B".equals(c) ? "Stock game" : "PZ Optimization";
   }

   private static boolean has(List<Map<String, String>> steps, String scene, String cfg) {
      for (Map<String, String> s : steps) {
         if (scene.equals(s.get("scenario")) && cfg.equals(s.get("config"))) {
            return true;
         }
      }
      return false;
   }

   /** The requested value of a field when some step has it (within the scene, for the config), else the first one's. */
   private static String pick(String[] a, int i, List<Map<String, String>> steps, String key, String within, String withinValue) {
      String want = a.length > i ? a[i].trim() : "";
      String first = null;
      for (Map<String, String> s : steps) {
         if (within != null && !withinValue.equals(s.get(within))) {
            continue;
         }
         String v = s.get(key);
         if (first == null) {
            first = v;
         }
         if (v != null && v.equals(want)) {
            return v;
         }
      }
      return first == null ? "" : first;
   }

   // ------------------------------------------------------------------ the stat tiles (row 1 and 2, the power row)

   private static void stats(StringBuilder b, Map<String, String> p, Frames fr) {
      panel(b, 1);
      v(b, "p50", p, "median_ms");
      v(b, "p99", p, "p99_ms");
      v(b, "p99.9", p, "p999_ms");
      v(b, "max", p, "max_ms");
      panel(b, 2);
      v(b, "fps", p, "fps");
      v(b, "1% low", p, "low1_avg");
      line(b, "V", ">33 ms", fr == null ? "" : Integer.toString(fr.over(33.3)));
      v(b, "jitter ms", p, "jitter_ms");
      panel(b, 3);
      v(b, "CPU", p, "cpu_system_pct");
      v(b, "busiest core", p, "cpu_busiest_core_pct");
      v(b, "GPU", p, "gpu_busy_pct");
      v(b, "game thread", p, "game_thread_pct");
      v(b, "render thread", p, "render_thread_pct");
      panel(b, 4);
      line(b, "V", "Jev", "");
      line(b, "V", "bound", bound(p));
      line(b, "V", "route", "ok".equals(p.get("status")) ? "valid" : "INVALID");
      line(b, "V", "variant", cfgName(p.get("config")));
      panel(b, 5);
      v(b, "chunk p99 ms", p, "chunk_p99_ms");
      v(b, "chunks/s", p, "chunks_per_s");
      v(b, "GC pauses", p, "gc_count");
      v(b, "worst GC ms", p, "gc_max_pause_ms");
      panel(b, 6);
      v(b, "GPU ms/frame", p, "gpu_ms_mean");
      v(b, "watts", p, "power_gpu_w");
      v(b, "°C", p, "gpu_c");
      v(b, "VRAM MiB", p, "vram_mib");
      panel(b, 23);
      v(b, "total W", p, "power_total_w");
      v(b, "CPU W", p, "power_cpu_w");
      v(b, "GPU W", p, "power_gpu_w");
      v(b, "J/frame", p, "joules_per_frame");
      line(b, "V", "source", Double.isNaN(BenchResults.d(p, "power_total_w")) ? "" : "game");
   }

   /** The run page's "bound" rule (GPU at 90 %, the game thread at 90 %, else at the cap or headroom), on this pass's cap. */
   static String bound(Map<String, String> p) {
      double gpu = BenchResults.d(p, "gpu_busy_pct"), game = BenchResults.d(p, "game_thread_pct"), fps = BenchResults.d(p, "fps");
      double cap = BenchResults.d(p, "cap_fps");
      if (gpu >= 90) {
         return "GPU-bound";
      }
      if (game >= 90) {
         return "game thread";
      }
      if (Double.isNaN(fps)) {
         return "";
      }
      if (cap > 0 && fps >= cap - 5) {
         return "at " + (int)cap + " cap";
      }
      return "headroom left";
   }

   // ------------------------------------------------------------------ the tables under the tiles

   private static void runTable(StringBuilder b, String run, File dir, Map<String, String> p, BenchSuite.Scenario sc, String cfg) {
      panel(b, 7);
      line(b, "C", "fact", "value");
      double started = BenchResults.d(p, "started_ms");
      if (Double.isNaN(started)) {
         double end = BenchResults.d(p, "ended_ms"), wall = BenchResults.d(p, "wall_seconds");
         started = Double.isNaN(end) || Double.isNaN(wall) ? Double.NaN : end - wall * 1000;
      }
      fact(b, "started", Double.isNaN(started) ? BenchResults.dateOf(run) : new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date((long)started)));
      String machine = join(" | ", p.get("sys.cpu"), p.get("sys.gpu"), p.get("sys.ram_gb") == null ? null : p.get("sys.ram_gb") + " GB");
      fact(b, "machine", machine);
      fact(b, "platform", p.get("sys.os"));
      fact(b, "mode", setupValue(run, "R", "mode"));
      fact(b, "preset", p.get("scenario"));
      fact(b, "route", sc == null ? null : sc.title);
      fact(b, "resolution", p.get("sys.screen"));
      fact(b, "opengl", p.get("sys.gl"));
      fact(b, "jvm", p.get("sys.java"));
      fact(b, "power profile", p.get("sys.power_profile")); // BenchPower; Grafana's Run table shows the same two rows
      fact(b, "cpu power", p.get("sys.cpu_power"));
      fact(b, "variant", cfgName(cfg));
      fact(b, "props", p.get("sys.settings"));
      if (sc != null && !sc.flags.isEmpty()) {
         StringBuilder f = new StringBuilder();
         for (Map.Entry<String, String> e : sc.flags.entrySet()) {
            f.append(f.length() > 0 ? " " : "").append(e.getKey()).append('=').append(e.getValue());
         }
         fact(b, "flags", f.toString());
      }
      double s = BenchResults.d(p, "seconds");
      fact(b, "route seconds", Double.isNaN(s) ? null : String.format(Locale.ROOT, "%.2f", s));
      fact(b, "zombies loaded", p.get("zombies_start"));
      fact(b, "path", dir.getAbsolutePath());
   }

   private static void fact(StringBuilder b, String k, String v) {
      if (v != null && !v.isEmpty()) {
         line(b, "R", k, v);
      }
   }

   private static String join(String sep, String... parts) {
      StringBuilder b = new StringBuilder();
      for (String s : parts) {
         if (s != null && !s.isEmpty()) {
            b.append(b.length() > 0 ? sep : "").append(s);
         }
      }
      return b.length() == 0 ? null : b.toString();
   }

   private static void runData(StringBuilder b, File dir, String base) {
      File[] files = dir.listFiles();
      if (files == null || files.length == 0) {
         return;
      }
      long bytes = 0;
      for (File f : files) {
         bytes += f.length();
      }
      panel(b, 9);
      line(b, "C", "folder", "frames", "files", "size", "uploaded");
      File frames = new File(dir, base + "-frames.csv");
      line(b, "R", "open the run folder", frames.isFile() ? frames.getName() : "", Integer.toString(files.length), prettySize(bytes), "");
   }

   /** pg_size_pretty's units (bytes, kB, MB, GB: switches when the value reaches 10240 of the smaller unit). */
   static String prettySize(long bytes) {
      String[] u = {"bytes", "kB", "MB", "GB", "TB"};
      double v = bytes;
      int i = 0;
      while (i < u.length - 1 && Math.abs(v) >= 10239.5) {
         v /= 1024;
         i++;
      }
      return Math.round(v) + " " + u[i];
   }

   // ------------------------------------------------------------------ frame time row

   private static void frameCharts(StringBuilder b, Frames fr, BenchDashMore more) {
      if (fr == null || fr.n == 0) {
         return;
      }
      double end = fr.t[fr.n - 1];
      panel(b, 11);
      more.gameFrameBand(b); // the game loop's frames (Stats.frameTick, the harness's pzopt-frames.out), runs from 2026-10-21 on
      band(b, "presented", fr, fr.frame, end); // the frames CSV: the overlay's presented-frame intervals (Grafana's overlay table)
      band(b, "GPU", fr, fr.gpu, end);
      // per second of route time: p50 / p99 / max, fps and the slowest frame as fps
      int secs = (int)Math.floor(end) + 1;
      double[] t = new double[secs], p50 = new double[secs], p99 = new double[secs], mx = new double[secs], fps = new double[secs], slow = new double[secs];
      int i = 0;
      for (int s = 0; s < secs; s++) {
         int from = i;
         while (i < fr.n && fr.t[i] < s + 1) {
            i++;
         }
         t[s] = s;
         float[] v = Arrays.copyOfRange(fr.frame, from, i);
         Arrays.sort(v);
         if (v.length == 0) {
            p50[s] = p99[s] = mx[s] = fps[s] = slow[s] = Double.NaN;
            continue;
         }
         double sum = 0;
         for (float x : v) {
            sum += x;
         }
         p50[s] = BenchStats.percentile(v, v.length, 50);
         p99[s] = BenchStats.percentile(v, v.length, 99);
         mx[s] = v[v.length - 1];
         fps[s] = sum > 0 ? v.length / (sum / 1000) : Double.NaN;
         slow[s] = mx[s] > 0 ? 1000 / mx[s] : Double.NaN;
      }
      panel(b, 12);
      series(b, "p50", t, p50);
      series(b, "p99", t, p99);
      series(b, "max", t, mx);
      panel(b, 13);
      series(b, "fps", t, fps);
      series(b, "slowest frame as fps", t, slow);
      // the distribution: frames per 0.5 ms bucket
      float[] sorted = Arrays.copyOf(fr.frame, fr.n);
      Arrays.sort(sorted);
      int lo = (int)Math.floor(sorted[0] / HIST_BUCKET_MS), hi = (int)Math.floor(sorted[fr.n - 1] / HIST_BUCKET_MS);
      if (hi - lo > 4000) { // (a pass with a stall of seconds: the long tail in one last bucket)
         hi = lo + 4000;
      }
      int[] counts = new int[hi - lo + 1];
      for (int k = 0; k < fr.n; k++) {
         counts[Math.min(counts.length - 1, (int)Math.floor(fr.frame[k] / HIST_BUCKET_MS) - lo)]++;
      }
      panel(b, 14);
      StringBuilder c = new StringBuilder();
      for (int k = 0; k < counts.length; k++) {
         c.append(k > 0 ? "," : "").append(counts[k]);
      }
      line(b, "HIST", fmt(HIST_BUCKET_MS), Integer.toString(lo), c.toString());
      panel(b, 15);
      line(b, "C", "source", "frames", "p50", "p90", "p99", "p99.9", "max", ">16.7", ">33.3", "stdev");
      more.gameFrameRow(b);
      pctRow(b, "presented", sorted, fr.n);
      float[] g = Arrays.copyOf(fr.gpu, fr.n);
      Arrays.sort(g);
      pctRow(b, "GPU time", g, fr.n);
      more.flipRow(b);
   }

   private static void pctRow(StringBuilder b, String name, float[] sorted, int n) {
      int over16 = 0, over33 = 0;
      double sum = 0, sq = 0;
      for (int i = 0; i < n; i++) {
         if (sorted[i] > 16.7f) {
            over16++;
         }
         if (sorted[i] > 33.3f) {
            over33++;
         }
         sum += sorted[i];
      }
      double mean = sum / n;
      for (int i = 0; i < n; i++) {
         sq += (sorted[i] - mean) * (sorted[i] - mean);
      }
      double sd = n > 1 ? Math.sqrt(sq / (n - 1)) : Double.NaN; // (Postgres stddev: the sample's)
      line(b, "R", name, Integer.toString(n), fmt(BenchStats.percentile(sorted, n, 50)), fmt(BenchStats.percentile(sorted, n, 90)),
            fmt(BenchStats.percentile(sorted, n, 99)), fmt(BenchStats.percentile(sorted, n, 99.9)), fmt(sorted[n - 1]),
            Integer.toString(over16), Integer.toString(over33), fmt(sd));
   }

   /** Every frame of a series as BAND_COLUMNS columns over [0, end]: each column's lowest and highest value. */
   private static void band(StringBuilder b, String name, Frames fr, float[] v, double end) {
      int cols = BAND_COLUMNS;
      double[] lo = new double[cols], hi = new double[cols], t = new double[cols];
      Arrays.fill(lo, Double.NaN);
      Arrays.fill(hi, Double.NaN);
      double span = Math.max(1e-6, end);
      for (int i = 0; i < fr.n; i++) {
         int c = Math.min(cols - 1, Math.max(0, (int)(fr.t[i] / span * cols)));
         double x = v[i];
         if (Double.isNaN(lo[c]) || x < lo[c]) {
            lo[c] = x;
         }
         if (Double.isNaN(hi[c]) || x > hi[c]) {
            hi[c] = x;
         }
      }
      for (int c = 0; c < cols; c++) {
         t[c] = (c + 0.5) * span / cols;
      }
      line(b, "B", name, csv(t), csv(lo), csv(hi));
   }

   // ------------------------------------------------------------------ utilization and power rows

   private static void utilization(StringBuilder b, Map<String, String> p, Samples s) {
      if (s == null) {
         return;
      }
      panel(b, 17);
      series(b, "CPU", s.t, s.col("cpu_system_pct"));
      series(b, "GPU", s.t, s.col("gpu_pct"));
      panel(b, 18);
      series(b, "game thread", s.t, s.col("game_thread_pct"));
      series(b, "render thread", s.t, s.col("render_thread_pct"));
      series(b, "GPU", s.t, s.col("gpu_pct"));
      series(b, "CPU", s.t, s.col("cpu_system_pct"));
      double cores = BenchResults.d(p, "sys.cores");
      double[] proc = s.col("cpu_process_pct"), gpuW = s.col("gpu_w");
      boolean anyW = anyNumber(gpuW);
      if (!Double.isNaN(cores) || anyW) {
         panel(b, 19);
         if (!Double.isNaN(cores)) {
            double[] c = new double[proc.length];
            for (int i = 0; i < c.length; i++) {
               c[i] = proc[i] / 100 * cores; // (the process's share of every core, as cores busy)
            }
            series(b, "game process cores", s.t, c);
         }
         if (anyW) {
            series(b, "GPU W", s.t, gpuW);
         }
      }
   }

   /** Panel 18 (the overlay's thread loads) from the samples file, when panels 17 and 19 come from the in-game sysmon. */
   private static void utilizationThreads(StringBuilder b, Samples s) {
      if (s == null) {
         return;
      }
      panel(b, 18);
      series(b, "game thread", s.t, s.col("game_thread_pct"));
      series(b, "render thread", s.t, s.col("render_thread_pct"));
      series(b, "GPU", s.t, s.col("gpu_pct"));
      series(b, "CPU", s.t, s.col("cpu_system_pct"));
   }

   private static void power(StringBuilder b, Map<String, String> p, Samples s, Frames fr) {
      if (s == null) {
         return;
      }
      String[][] cols = {{"total_w", "game total"}, {"cpu_w", "game CPU"}, {"soc_w", "game APU socket"}, {"gpu_w", "game GPU"}, {"bat_w", "game battery"}};
      boolean any = false;
      for (String[] c : cols) {
         any |= anyNumber(s.col(c[0]));
      }
      if (!any) {
         return;
      }
      panel(b, 25);
      for (String[] c : cols) {
         double[] v = s.col(c[0]);
         if (anyNumber(v)) {
            series(b, c[1], s.t, v);
         }
      }
      double[] total = s.col("total_w");
      if (fr == null || fr.n == 0 || !anyNumber(total)) {
         return;
      }
      // J/frame per second: the second's mean power over its frame rate
      int secs = (int)Math.floor(fr.t[fr.n - 1]) + 1;
      double[] frames = new double[secs], ms = new double[secs], w = new double[secs], wn = new double[secs];
      for (int i = 0; i < fr.n; i++) {
         int k = Math.min(secs - 1, (int)Math.floor(fr.t[i]));
         frames[k]++;
         ms[k] += fr.frame[i];
      }
      for (int i = 0; i < s.n; i++) {
         int k = (int)Math.floor(s.t[i]);
         if (k >= 0 && k < secs && !Double.isNaN(total[i])) {
            w[k] += total[i];
            wn[k]++;
         }
      }
      List<Double> tt = new ArrayList<>(), jj = new ArrayList<>();
      for (int k = 0; k < secs; k++) {
         if (wn[k] > 0 && ms[k] > 0) {
            tt.add((double)k);
            jj.add(w[k] / wn[k] / (frames[k] / (ms[k] / 1000)));
         }
      }
      if (!tt.isEmpty()) {
         panel(b, 26);
         series(b, "J/frame (in game)", unbox(tt), unbox(jj));
      }
   }

   // ------------------------------------------------------------------ game thread row (the stack sampler's log)

   /** The route's start in epoch ms (the per-second logs' clock): started_ms, else the end minus the wall time. */
   static double routeStartMs(Map<String, String> p) {
      double s = BenchResults.d(p, "started_ms");
      if (!Double.isNaN(s)) {
         return s;
      }
      double end = BenchResults.d(p, "ended_ms"), wall = BenchResults.d(p, "wall_seconds");
      return Double.isNaN(end) || Double.isNaN(wall) ? Double.NaN : end - wall * 1000;
   }

   /** pzopt-gamethread.out rows as ingest.py reads them: {t (route s, mid-second), kind, name, count, samples}. */
   static List<String[]> readGameThread(File f, double startMs) {
      List<String[]> out = new ArrayList<>();
      if (!f.isFile() || Double.isNaN(startMs)) {
         return out;
      }
      try {
         for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
            if (line.startsWith("#")) {
               continue;
            }
            String[] c = line.split("\t");
            if (c.length < 2) {
               continue;
            }
            long epoch;
            int n;
            try {
               epoch = Long.parseLong(c[0]);
               n = Integer.parseInt(c[1]);
            } catch (NumberFormatException e) {
               continue;
            }
            String t = fmt((epoch - startMs) / 1000.0 - 0.5);
            out.add(new String[] {t, "total", "samples", Integer.toString(n), Integer.toString(n)});
            for (int i = 2; i < c.length; i++) {
               int eq = c[i].lastIndexOf('=');
               int colon = c[i].indexOf(':');
               if (eq < 0 || colon < 0 || colon > eq) {
                  continue;
               }
               out.add(new String[] {t, c[i].substring(0, colon), c[i].substring(colon + 1, eq), c[i].substring(eq + 1), Integer.toString(n)});
            }
         }
      } catch (IOException e) {
         return new ArrayList<>();
      }
      return out;
   }

   private static void gameThread(StringBuilder b, Map<String, String> p, List<String[]> rows) {
      if (rows.isEmpty()) {
         String ph = p.get("game_phases"); // (runs before 2026-10-21: the route's top eight sub-phases, shares only)
         if (ph != null && !ph.isEmpty()) {
            panel(b, 30);
            line(b, "C", "name", "samples", "share");
            for (String e : ph.split(";")) {
               int eq = e.lastIndexOf('=');
               if (eq > 0) {
                  line(b, "R", e.substring(0, eq), "", e.substring(eq + 1));
               }
            }
         }
         return;
      }
      double seconds = BenchResults.d(p, "seconds");
      double routeEnd = Double.isNaN(seconds) ? Double.MAX_VALUE : seconds;
      long total = 0;
      Map<String, Map<String, Long>> sums = new HashMap<>();
      // the per-second shares of the phases ("p") and sub-phases ("s"): name -> t -> share
      Map<String, TreeMap<Double, Double>> phases = new LinkedHashMap<>(), subs = new LinkedHashMap<>();
      TreeMap<Double, Boolean> times = new TreeMap<>();
      for (String[] r : rows) {
         double t = Double.parseDouble(r[0]);
         long count = Long.parseLong(r[3]);
         long n = Long.parseLong(r[4]);
         boolean inRoute = t >= -0.5 && t <= routeEnd + 0.5;
         if ("total".equals(r[1])) {
            times.put(t, true);
            if (inRoute) {
               total += count;
            }
            continue;
         }
         if (inRoute) {
            sums.computeIfAbsent(r[1], k -> new HashMap<>()).merge(r[2], count, Long::sum);
         }
         if (n > 0 && ("p".equals(r[1]) || "s".equals(r[1]))) {
            ("p".equals(r[1]) ? phases : subs).computeIfAbsent(r[2], k -> new TreeMap<>()).put(t, 100.0 * count / n);
         }
      }
      double[] ts = new double[times.size()];
      int k = 0;
      for (double t : times.keySet()) {
         ts[k++] = t;
      }
      if (!phases.isEmpty()) {
         panel(b, 28);
         for (Map.Entry<String, TreeMap<Double, Double>> e : phases.entrySet()) {
            series(b, e.getKey(), ts, fill(ts, e.getValue()));
         }
      }
      List<String> top = topNames(sums.get("s"), 12);
      if (!top.isEmpty()) {
         panel(b, 29);
         for (String name : top) {
            TreeMap<Double, Double> m = subs.get(name);
            series(b, name, ts, fill(ts, m == null ? new TreeMap<>() : m));
         }
      }
      String[][] tables = {{"30", "s", "30"}, {"31", "l", "40"}, {"32", "w", "25"}};
      for (String[] tb : tables) {
         Map<String, Long> m = sums.get(tb[1]);
         if (m == null || m.isEmpty() || total == 0) {
            continue;
         }
         panel(b, Integer.parseInt(tb[0]));
         line(b, "C", "name", "samples", "share");
         for (String name : topNames(m, Integer.parseInt(tb[2]))) {
            long v = m.get(name);
            line(b, "R", name, Long.toString(v), fmt(100.0 * v / total));
         }
      }
   }

   /** A series on the log's seconds: its share where the second has the name, 0 where it does not (ingest's rows). */
   private static double[] fill(double[] ts, TreeMap<Double, Double> m) {
      double[] v = new double[ts.length];
      for (int i = 0; i < ts.length; i++) {
         Double x = m.get(ts[i]);
         v[i] = x == null ? 0 : x;
      }
      return v;
   }

   private static List<String> topNames(Map<String, Long> m, int limit) {
      List<String> out = new ArrayList<>();
      if (m == null) {
         return out;
      }
      List<Map.Entry<String, Long>> l = new ArrayList<>(m.entrySet());
      l.sort((x, y) -> {
         int c = Long.compare(y.getValue(), x.getValue());
         return c != 0 ? c : x.getKey().compareTo(y.getKey());
      });
      for (int i = 0; i < Math.min(limit, l.size()); i++) {
         out.add(l.get(i).getKey());
      }
      return out;
   }

   // ------------------------------------------------------------------ chunks / GC, configured, counters

   private static void gcPauses(StringBuilder b, Map<String, String> p) {
      String s = p.get("gc_pauses");
      if (s == null || s.isEmpty()) {
         return;
      }
      List<Double> t = new ArrayList<>(), ms = new ArrayList<>();
      for (String e : s.split(",")) {
         int c = e.indexOf(':');
         if (c > 0) {
            try {
               t.add(Double.parseDouble(e.substring(0, c)));
               ms.add(Double.parseDouble(e.substring(c + 1)));
            } catch (NumberFormatException ignored) {
            }
         }
      }
      if (!t.isEmpty()) {
         panel(b, 36);
         series(b, "pause", unbox(t), unbox(ms));
      }
   }

   /**
    * The run's setup.txt as the run page's "Configured by the harness" rows: the PZ Optimization settings away from the
    * defaults are the run's props, the game's options.ini its options, the mods its mods, the benchmark's own choices
    * its run.opts.
    */
   private static void configured(StringBuilder b, String run) {
      List<String[]> setup = BenchSetup.read(run);
      if (setup == null || setup.isEmpty()) {
         return;
      }
      List<String[]> rows = new ArrayList<>();
      for (String[] c : setup) {
         String kind = switch (c[0]) {
            case "O" -> "prop";
            case "G" -> "option";
            case "M", "J" -> "mods";
            case "R" -> "run.opts";
            default -> null;
         };
         if (kind == null) {
            continue;
         }
         String value = c.length > 2 ? c[2] : "";
         if ("M".equals(c[0]) && c.length > 3 && !c[3].isEmpty()) {
            value = value + " " + c[3];
         }
         rows.add(new String[] {kind, c[1], value});
      }
      if (rows.isEmpty()) {
         return;
      }
      Map<String, Integer> order = Map.of("flag", 0, "prop", 1, "option", 2);
      rows.sort((x, y) -> {
         int c = Integer.compare(order.getOrDefault(x[0], 3), order.getOrDefault(y[0], 3));
         if (c != 0) {
            return c;
         }
         c = x[0].compareTo(y[0]);
         return c != 0 ? c : x[1].compareTo(y[1]);
      });
      panel(b, 55);
      line(b, "C", "kind", "key", "value");
      for (String[] r : rows) {
         line(b, "R", r[0], r[1], r[2]);
      }
   }

   private static String setupValue(String run, String kind, String key) {
      List<String[]> setup = BenchSetup.read(run);
      if (setup != null) {
         for (String[] c : setup) {
            if (kind.equals(c[0]) && key.equals(c[1]) && c.length > 2) {
               return c[2];
            }
         }
      }
      return null;
   }

   /** Keys the counters table leaves out: the lists behind the charts, and the route start. */
   private static final java.util.Set<String> LISTS = java.util.Set.of("per_second", "curve", "hist", "game_phases", "gc_pauses", "sweep", "started_ms", "step");

   /** Every value the pass recorded, grouped (the horde's counters, the PC, the pass), as the run page's counters. */
   private static void counters(StringBuilder b, Map<String, String> p) {
      TreeMap<String, String> rows = new TreeMap<>();
      for (Map.Entry<String, String> e : p.entrySet()) {
         String k = e.getKey();
         if (LISTS.contains(k) || e.getValue() == null || e.getValue().isEmpty()) {
            continue;
         }
         String grp = k.startsWith("sys.") ? "system" : k.startsWith("horde_") ? "horde" : "pass";
         String key = k.startsWith("sys.") ? k.substring(4) : k;
         rows.put(grp + "\t" + key, e.getValue());
      }
      if (rows.isEmpty()) {
         return;
      }
      panel(b, 59);
      line(b, "C", "group", "key", "value");
      for (Map.Entry<String, String> e : rows.entrySet()) {
         String[] gk = e.getKey().split("\t", 2);
         line(b, "R", gk[0], gk[1], e.getValue());
      }
   }

   // ------------------------------------------------------------------ the pass's files

   /** A frames CSV (BenchRecorder.writeFramesCsv): TimeInSeconds, MsBetweenPresents, MsCPUBusy, MsGPUBusy per frame. */
   static final class Frames {
      int n;
      double[] t = new double[0];
      float[] frame = new float[0], step = new float[0], gpu = new float[0];

      static Frames read(File f) {
         if (!f.isFile()) {
            return null;
         }
         Frames r = new Frames();
         int cap = 16384;
         r.t = new double[cap];
         r.frame = new float[cap];
         r.step = new float[cap];
         r.gpu = new float[cap];
         try (BufferedReader in = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            String line = in.readLine(); // the header
            while ((line = in.readLine()) != null) {
               String[] c = line.split(",");
               if (c.length < 6) {
                  continue;
               }
               try {
                  double t = Double.parseDouble(c[2]);
                  float ms = Float.parseFloat(c[3]), st = Float.parseFloat(c[4]), g = Float.parseFloat(c[5]);
                  if (r.n == r.t.length) {
                     int len = r.n * 2;
                     r.t = Arrays.copyOf(r.t, len);
                     r.frame = Arrays.copyOf(r.frame, len);
                     r.step = Arrays.copyOf(r.step, len);
                     r.gpu = Arrays.copyOf(r.gpu, len);
                  }
                  r.t[r.n] = t;
                  r.frame[r.n] = ms;
                  r.step[r.n] = st;
                  r.gpu[r.n] = g;
                  r.n++;
               } catch (NumberFormatException ignored) {
               }
            }
         } catch (IOException e) {
            return null;
         }
         return r.n == 0 ? null : r;
      }

      int over(double ms) {
         int k = 0;
         for (int i = 0; i < n; i++) {
            if (frame[i] > ms) {
               k++;
            }
         }
         return k;
      }
   }

   /** A samples CSV (BenchTimeline.HEADER), columns by name; an empty cell is NaN. */
   static final class Samples {
      int n;
      double[] t;
      Map<String, double[]> cols = new HashMap<>();

      static Samples read(File f) {
         if (!f.isFile()) {
            return null;
         }
         try {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            if (lines.size() < 2) {
               return null;
            }
            String[] head = lines.get(0).split(",");
            Samples s = new Samples();
            s.n = lines.size() - 1;
            for (String h : head) {
               s.cols.put(h, new double[s.n]);
            }
            for (int i = 0; i < s.n; i++) {
               String[] c = lines.get(i + 1).split(",", -1);
               for (int k = 0; k < head.length; k++) {
                  double v = Double.NaN;
                  if (k < c.length && !c[k].isEmpty()) {
                     try {
                        v = Double.parseDouble(c[k]);
                     } catch (NumberFormatException ignored) {
                     }
                  }
                  s.cols.get(head[k])[i] = v;
               }
            }
            s.t = s.col("t_s");
            return s;
         } catch (IOException e) {
            return null;
         }
      }

      double[] col(String name) {
         double[] v = cols.get(name);
         if (v == null) {
            v = new double[n];
            Arrays.fill(v, Double.NaN);
         }
         return v;
      }
   }

   // ------------------------------------------------------------------ text

   private static boolean anyNumber(double[] v) {
      for (double x : v) {
         if (!Double.isNaN(x)) {
            return true;
         }
      }
      return false;
   }

   private static double[] unbox(List<Double> l) {
      double[] a = new double[l.size()];
      for (int i = 0; i < a.length; i++) {
         a[i] = l.get(i);
      }
      return a;
   }

   private static void panel(StringBuilder b, int id) {
      line(b, "P", Integer.toString(id));
   }

   private static void v(StringBuilder b, String name, Map<String, String> p, String key) {
      double x = BenchResults.d(p, key);
      line(b, "V", name, Double.isNaN(x) ? "" : fmt(x));
   }

   private static void series(StringBuilder b, String name, double[] t, double[] v) {
      line(b, "S", name, csv(t), csv(v));
   }

   static String csv(double[] v) {
      StringBuilder b = new StringBuilder(v.length * 6);
      for (int i = 0; i < v.length; i++) {
         b.append(i > 0 ? "," : "").append(fmt(v[i]));
      }
      return b.toString();
   }

   /** A number in at most four decimals, trailing zeros dropped; "nan" for a gap. */
   static String fmt(double v) {
      if (Double.isNaN(v) || Double.isInfinite(v)) {
         return "nan";
      }
      String s = String.format(Locale.ROOT, "%.4f", v);
      int end = s.length();
      while (end > 0 && s.charAt(end - 1) == '0') {
         end--;
      }
      if (end > 0 && s.charAt(end - 1) == '.') {
         end--;
      }
      s = s.substring(0, end);
      return s.equals("-0") ? "0" : s;
   }

   private static void line(StringBuilder b, String kind, String... cols) {
      b.append(kind);
      for (String c : cols) {
         b.append('\t').append(c == null ? "" : c.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '));
      }
      b.append('\n');
   }
}
