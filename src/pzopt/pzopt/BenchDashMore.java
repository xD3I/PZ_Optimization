package pzopt;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The report page's panels that read BenchCapture's per-pass files (2026-10-21): the in-game counterparts of the
 * harness's sysmon, schedmon, pzopt-threads.out, pzopt-chunks.out, pzopt-pacing.out, present.txt, pzopt-stacks.out,
 * pzopt-loadtrace.out, pzopt-input.out and pzopt-drive.out, answered in the Grafana run page's units and series names
 * (BenchDash's line format). A panel without data gets a {@code WHY} line: why this pass has none (the platform, the
 * variant, a run from before these files), which the page shows under "No data in this report".
 */
final class BenchDashMore {
   /** Grafana's time range on the run page: 30 s before the route to 120 s after its start. */
   static final double WINDOW_FROM = -30, WINDOW_TO = 120;

   final File dir;
   final String base;
   final double startMs, routeS;
   final String os, cfg;
   /** Runs from 2026-10-21 on write the captures; older runs' panels say "a run before this version". */
   final boolean captured;
   Table sysmon, sched, series, chunks, gameFrames, pacing, flips;

   private BenchDashMore(File dir, String base, Map<String, String> pass) {
      this.dir = dir;
      this.base = base;
      this.startMs = BenchDash.routeStartMs(pass);
      double s = BenchResults.d(pass, "seconds");
      this.routeS = Double.isNaN(s) ? 0 : s;
      this.os = pass.getOrDefault("sys.os", "");
      this.cfg = pass.getOrDefault("config", "");
      sysmon = Table.read(file("sysmon.csv"));
      if (sysmon != null && !Double.isNaN(startMs)) { // (sysmon.sh's epoch_ms column, on route time)
         double[] e = sysmon.num("epoch_ms");
         double[] t = new double[e.length];
         for (int i = 0; i < t.length; i++) {
            t[i] = (e[i] - startMs) / 1000.0;
         }
         sysmon.t = t;
      }
      sched = Table.read(file("sched.csv"));
      series = Table.read(file("series.csv"));
      chunks = Table.read(file("chunks.csv"));
      gameFrames = Table.read(file("gameframes.csv"));
      pacing = Table.read(file("pacing.csv"));
      flips = Table.read(file("flips.csv"));
      captured = sysmon != null || gameFrames != null || file("threads.out").isFile();
   }

   static BenchDashMore read(File dir, String base, Map<String, String> pass) {
      return new BenchDashMore(dir, base, pass);
   }

   File file(String suffix) {
      return new File(dir, base + "-" + suffix);
   }

   private String old(String why) {
      return captured ? why : "recorded by runs of this version on";
   }

   private boolean linux() {
      return os.toLowerCase(Locale.ROOT).contains("linux");
   }

   private String osName() {
      return os.isEmpty() ? "this OS" : os.split(" ")[0];
   }

   // ------------------------------------------------------------------ the frame row (BenchDash.frameCharts)

   /** "Every frame"'s game-frame series (Stats.frameTick's clock) as a min / max band over Grafana's window. */
   void gameFrameBand(StringBuilder b) {
      if (gameFrames != null && gameFrames.n > 0) {
         band(b, "game frame", gameFrames.t, gameFrames.num("ms"), windowFrom(), windowTo(gameFrames.t));
      }
   }

   /** The percentile table's game-frame row over the route (0 frames for runs before the game frames were kept). */
   void gameFrameRow(StringBuilder b) {
      routeRow(b, "game frame", gameFrames, "ms");
   }

   void flipRow(StringBuilder b) {
      routeRow(b, "flip interval", flips, "interval_ms");
   }

   private void routeRow(StringBuilder b, String name, Table tb, String col) {
      float[] v = tb == null ? new float[0] : tb.inRoute(col, 0, routeS > 0 ? routeS : Double.MAX_VALUE);
      if (v.length == 0) {
         line(b, "R", name, "0", "", "", "", "", "", "0", "0", "");
         return;
      }
      Arrays.sort(v);
      int n = v.length, o16 = 0, o33 = 0;
      double sum = 0, sq = 0;
      for (float x : v) {
         sum += x;
         o16 += x > 16.7f ? 1 : 0;
         o33 += x > 33.3f ? 1 : 0;
      }
      double mean = sum / n;
      for (float x : v) {
         sq += (x - mean) * (x - mean);
      }
      line(b, "R", name, Integer.toString(n), fmt(BenchStats.percentile(v, n, 50)), fmt(BenchStats.percentile(v, n, 90)), fmt(BenchStats.percentile(v, n, 99)),
            fmt(BenchStats.percentile(v, n, 99.9)), fmt(v[n - 1]), Integer.toString(o16), Integer.toString(o33), n > 1 ? fmt(Math.sqrt(sq / (n - 1))) : "nan");
   }

   // ------------------------------------------------------------------ utilization (BenchDash.utilization)

   /** Panels 17 and 19 from the in-game sysmon (with the pre-roll); false = the caller draws them from the samples file. */
   boolean machine(StringBuilder b) {
      if (sysmon == null || sysmon.n == 0 || sysmon.t == null) {
         return false;
      }
      panel(b, 17);
      seriesIf(b, "CPU", sysmon.t, sysmon.num("cpu_pct"));
      seriesIf(b, "busiest core", sysmon.t, sysmon.num("cpu_busiest_core_pct"));
      seriesIf(b, "GPU", sysmon.t, sysmon.num("gpu_pct"));
      double[] cores = sysmon.num("game_cpu_pct");
      for (int i = 0; i < cores.length; i++) {
         cores[i] /= 100;
      }
      panel(b, 19);
      seriesIf(b, "game process cores", sysmon.t, cores);
      seriesIf(b, "GPU W", sysmon.t, sysmon.num("gpu_w"));
      seriesIf(b, "GPU °C", sysmon.t, sysmon.num("gpu_c"));
      return true;
   }

   // ------------------------------------------------------------------ the panels of the captures

   void panels(StringBuilder b) {
      panel(b, 8);
      why(b, "the in-game benchmark records no video (the mod ships no recorder); the screenshot option keeps a still per pass");
      gpuClocks(b);
      threadTable(b);
      panel(b, 24);
      why(b, "sysmon samples beside harness runs; the game's own readings of the same sensors are on the right");
      chunkPanels(b);
      flipPanel(b);
      drive(b);
      stacks(b);
      schedPanels(b);
      seriesPanels(b);
      pacingPanel(b);
      inputs(b);
      console(b);
   }

   private void gpuClocks(StringBuilder b) {
      panel(b, 20);
      if (sysmon == null || sysmon.t == null) {
         why(b, old(""));
         return;
      }
      boolean any = seriesIf(b, "SM MHz", sysmon.t, sysmon.num("gpu_sm_mhz"));
      any |= seriesIf(b, "memory MHz", sysmon.t, sysmon.num("gpu_mem_mhz"));
      any |= seriesIf(b, "VRAM MiB", sysmon.t, sysmon.num("vram_mib"));
      if (!any) {
         why(b, "no GPU sensor readable on this PC (NVIDIA's NVML on Linux / Windows, the amdgpu driver's files on Linux)");
      }
   }

   private void threadTable(StringBuilder b) {
      panel(b, 21);
      File f = file("threads.out");
      if (!f.isFile()) {
         why(b, old("no thread CPU times in this pass"));
         return;
      }
      List<String[]> rows = new ArrayList<>();
      boolean table = false;
      for (String l : lines(f)) {
         if (l.startsWith("#")) {
            continue;
         }
         String[] c = l.split("\t");
         if (c[0].equals("thread")) {
            table = true;
         } else if (table && c.length >= 3) {
            rows.add(c);
         }
      }
      if (rows.isEmpty()) {
         return;
      }
      rows.sort((x, y) -> Double.compare(num(y[2]), num(x[2])));
      line(b, "C", "thread", "CPU ms", "% of wall");
      for (int i = 0; i < Math.min(40, rows.size()); i++) {
         String[] r = rows.get(i);
         line(b, "R", r[0], r[1], fmt(num(r[2]) * 100));
      }
   }

   private void chunkPanels(StringBuilder b) {
      if (chunks == null || chunks.n == 0) {
         panel(b, 34);
         why(b, old("no chunk streamed in this pass's window"));
         panel(b, 35);
         why(b, old("no chunk streamed in this pass's window"));
         return;
      }
      panel(b, 34);
      series(b, "total", chunks.t, chunks.num("total_ms"));
      String[][] st = {{"queue_wait_ms", "queue wait"}, {"load_ms", "load"}, {"recalc_wait_ms", "recalc wait"}, {"recalc_ms", "recalc"}, {"publish_wait_ms", "publish wait"}};
      TreeMap<Integer, double[]> sec = new TreeMap<>(); // second -> per stage {sum, n}..., chunks
      for (int i = 0; i < chunks.n; i++) {
         int s = (int)Math.floor(chunks.t[i]);
         double[] a = sec.computeIfAbsent(s, x -> new double[2 * st.length + 1]);
         for (int k = 0; k < st.length; k++) {
            double v = chunks.num(st[k][0])[i];
            if (!Double.isNaN(v)) {
               a[2 * k] += v;
               a[2 * k + 1]++;
            }
         }
         a[2 * st.length]++;
      }
      double[] t = new double[sec.size()];
      double[][] v = new double[st.length + 1][sec.size()];
      int j = 0;
      for (Map.Entry<Integer, double[]> e : sec.entrySet()) {
         t[j] = e.getKey();
         double[] a = e.getValue();
         for (int k = 0; k < st.length; k++) {
            v[k][j] = a[2 * k + 1] > 0 ? a[2 * k] / a[2 * k + 1] : Double.NaN;
         }
         v[st.length][j] = a[2 * st.length];
         j++;
      }
      panel(b, 35);
      for (int k = 0; k < st.length; k++) {
         seriesIf(b, st[k][1], t, v[k]);
      }
      series(b, "chunks/s", t, v[st.length]);
   }

   private void flipPanel(StringBuilder b) {
      panel(b, 37);
      if (flips == null || flips.n == 0) {
         why(b, "on-screen flip times come from the harness's X Present probe; in game only the Mac's Metal bridge (macPresent) reports them");
         return;
      }
      series(b, "flip interval", flips.t, flips.num("interval_ms"));
   }

   private void drive(StringBuilder b) {
      File f = file("drive.out");
      List<String> l = f.isFile() ? lines(f) : List.of();
      if (l.size() < 2) {
         String why = old("this scene is not a path-pilot drive");
         panel(b, 39);
         why(b, why);
         panel(b, 40);
         why(b, why);
         return;
      }
      String[] head = l.get(0).split("\t");
      Map<String, Integer> col = new HashMap<>();
      for (int i = 0; i < head.length; i++) {
         col.put(head[i], i);
      }
      int n = l.size() - 1;
      double[] t = new double[n];
      Map<String, double[]> v = new LinkedHashMap<>();
      for (String h : head) {
         v.put(h, new double[n]);
      }
      int k = 0;
      for (int i = 1; i < l.size(); i++) {
         String[] c = l.get(i).split("\t");
         if (c.length != head.length) {
            continue;
         }
         t[k] = num(c[0]);
         for (int x = 1; x < head.length; x++) {
            v.get(head[x])[k] = num(c[x]);
         }
         k++;
      }
      t = Arrays.copyOf(t, k);
      panel(b, 39);
      for (String key : new String[] {"kmh", "target_kmh"}) {
         if (col.containsKey(key)) {
            seriesIf(b, key, t, Arrays.copyOf(v.get(key), k));
         }
      }
      panel(b, 40);
      for (String key : new String[] {"xte", "hdg_err", "steer", "brake", "offset", "obstacles", "chunk_ahead"}) {
         if (col.containsKey(key)) {
            seriesIf(b, key, t, Arrays.copyOf(v.get(key), k));
         }
      }
   }

   /** The flame graph's nodes ("F level value self label", depth first, children by name) and the samples per second. */
   private void stacks(StringBuilder b) {
      File f = file("stacks.out");
      if (!f.isFile()) {
         String why = old("no game-thread stack samples in this pass");
         panel(b, 42);
         why(b, why);
         panel(b, 43);
         why(b, why);
         return;
      }
      Map<String, String> defs = new HashMap<>();
      Node root = new Node("all");
      TreeMap<Double, Double> perSecond = new TreeMap<>();
      for (String line : lines(f)) {
         if (line.isEmpty() || line.startsWith("#")) {
            continue;
         }
         if (line.startsWith("f ")) {
            String[] p = line.split(" ", 3);
            if (p.length == 3) {
               defs.put(p[1], p[2]);
            }
         } else if (line.startsWith("t ")) {
            String[] p = line.split(" ");
            if (p.length >= 3 && !Double.isNaN(startMs)) {
               perSecond.merge((num(p[1]) - startMs) / 1000.0 - 0.5, num(p[2]), Double::sum);
            }
         } else {
            int sp = line.lastIndexOf(' ');
            if (sp <= 0) {
               continue;
            }
            long c = (long)num(line.substring(sp + 1));
            Node n = root;
            n.value += c;
            for (String id : line.substring(0, sp).split(";")) {
               n = n.child(defs.getOrDefault(id, "#" + id));
               n.value += c;
            }
            n.self += c;
         }
      }
      panel(b, 42);
      if (root.value > 0) {
         long min = Math.max(1, root.value / 2000); // (narrower than 1/2000 of the graph: not drawn at any panel width)
         int[] rows = {0};
         emit(b, root, 0, min, rows);
      }
      panel(b, 43);
      if (!perSecond.isEmpty()) {
         double[] t = new double[perSecond.size()], v = new double[perSecond.size()];
         int i = 0;
         for (Map.Entry<Double, Double> e : perSecond.entrySet()) {
            t[i] = e.getKey();
            v[i++] = e.getValue();
         }
         series(b, "MainThread", t, v);
      }
   }

   static final class Node {
      final String label;
      long value, self;
      TreeMap<String, Node> kids;

      Node(String label) {
         this.label = label;
      }

      Node child(String name) {
         if (kids == null) {
            kids = new TreeMap<>();
         }
         return kids.computeIfAbsent(name, Node::new);
      }
   }

   private static final int MAX_NODES = 6000;

   private static void emit(StringBuilder b, Node n, int level, long min, int[] rows) {
      if (rows[0]++ >= MAX_NODES) {
         return;
      }
      line(b, "F", Integer.toString(level), Long.toString(n.value), Long.toString(n.self), n.label);
      if (n.kids != null) {
         for (Node k : n.kids.values()) {
            if (k.value >= min) {
               emit(b, k, level + 1, min, rows);
            }
         }
      }
   }

   private void schedPanels(StringBuilder b) {
      if (sched == null || sched.n == 0) {
         String why = old("no thread samples in this pass");
         panel(b, 45);
         why(b, why);
         panel(b, 46);
         why(b, why);
         return;
      }
      String[] thread = sched.text("thread");
      double[] cpu = sched.num("cpu_pct"), runq = sched.num("runq_pct");
      Map<String, Double> sum = new HashMap<>();
      for (int i = 0; i < sched.n; i++) {
         sum.merge(thread[i], Double.isNaN(cpu[i]) ? 0 : cpu[i], Double::sum);
      }
      List<String> top = new ArrayList<>(sum.keySet());
      top.sort((x, y) -> {
         int c = Double.compare(sum.get(y), sum.get(x));
         return c != 0 ? c : x.compareTo(y);
      });
      top = top.subList(0, Math.min(12, top.size()));
      panel(b, 45);
      perThread(b, top, thread, cpu);
      panel(b, 46);
      if (!sched.any("runq_pct")) {
         why(b, "run-queue wait is read from Linux's /proc/self/task (this pass: " + osName() + "); the chart beside shows the Java threads' CPU");
         return;
      }
      perThread(b, top, thread, runq);
   }

   private void perThread(StringBuilder b, List<String> names, String[] thread, double[] v) {
      for (String name : names) {
         List<Double> t = new ArrayList<>(), x = new ArrayList<>();
         for (int i = 0; i < sched.n; i++) {
            if (name.equals(thread[i])) {
               t.add(sched.t[i]);
               x.add(v[i]);
            }
         }
         seriesIf(b, name, unbox(t), unbox(x));
      }
   }

   private void seriesPanels(StringBuilder b) {
      String why = linux() || os.isEmpty() ? old("no pressure readings in this pass") : "Linux only: /proc/pressure and /proc/vmstat (this pass: " + osName() + ")";
      String whyMem = linux() || os.isEmpty() ? old("no memory readings in this pass") : "Linux only: /proc/self/status (this pass: " + osName() + ")";
      Map<String, Map<String, List<double[]>>> by = new LinkedHashMap<>();
      if (series != null) {
         String[] src = series.text("source"), key = series.text("key");
         double[] v = series.num("value");
         for (int i = 0; i < series.n; i++) {
            by.computeIfAbsent(src[i], x -> new LinkedHashMap<>()).computeIfAbsent(key[i], x -> new ArrayList<>()).add(new double[] {series.t[i], v[i]});
         }
      }
      String[][] panels = {{"47", "psi", why}, {"48", "mem", whyMem}};
      for (String[] p : panels) {
         panel(b, Integer.parseInt(p[0]));
         Map<String, List<double[]>> m = by.get(p[1]);
         if (m == null) {
            why(b, p[2]);
            continue;
         }
         for (Map.Entry<String, List<double[]>> e : m.entrySet()) {
            double[] t = new double[e.getValue().size()], x = new double[t.length];
            for (int i = 0; i < t.length; i++) {
               t[i] = e.getValue().get(i)[0];
               x[i] = e.getValue().get(i)[1];
            }
            series(b, e.getKey(), t, x);
         }
      }
   }

   private static final String[][] PACING_NAMES = {{"sim_step", "sim step"}, {"acquire_lag", "acquire lag"}, {"swap", "swap"}, {"hold", "hold"},
         {"gpu_after_swap", "GPU after swap"}, {"shown_after_swap", "shown after swap"}};

   private void pacingPanel(StringBuilder b) {
      panel(b, 49);
      if (pacing == null || pacing.n == 0) {
         why(b, "B".equals(cfg) ? "the stock game has no pacing hooks (PZ Optimization's render loop records them)" : old("no presented frame in this pass"));
         return;
      }
      for (String[] m : PACING_NAMES) {
         double[] lo = pacing.num(m[0] + "_min"), hi = pacing.num(m[0] + "_max");
         boolean any = false;
         for (double x : hi) {
            any |= !Double.isNaN(x);
         }
         if (any) {
            line(b, "B", m[1], csv(pacing.t), csv(lo), csv(hi));
         }
      }
   }

   private static final Set<String> ANALOG = Set.of("x", "y", "lx", "ly", "rx", "ry", "lt", "rt"), STEP = Set.of("wheel", "hat");

   private void inputs(StringBuilder b) {
      File f = file("input.out");
      if (!f.isFile() || Double.isNaN(startMs)) {
         String why = old("the input log was off in this pass");
         for (int id : new int[] {51, 52, 53, 54}) {
            panel(b, id);
            why(b, why);
         }
         return;
      }
      List<String[]> ev = new ArrayList<>(); // {t, device, control, value}
      for (String l : lines(f)) {
         String[] c = l.split("\t");
         if (l.startsWith("#") || c.length != 4 || c[0].isEmpty() || !Character.isDigit(c[0].charAt(0))) {
            continue;
         }
         double t = (num(c[0]) / 1000.0 - startMs) / 1000.0;
         if (t >= WINDOW_FROM - 1e-9) {
            ev.add(new String[] {fmt(t), c[1], c[2], c[3]});
         }
      }
      Map<String, List<String[]>> keys = new LinkedHashMap<>(), analog = new LinkedHashMap<>(), step = new LinkedHashMap<>();
      for (String[] e : ev) {
         String m = e[1] + " " + e[2];
         (ANALOG.contains(e[2]) ? analog : STEP.contains(e[2]) ? step : keys).computeIfAbsent(m, x -> new ArrayList<>()).add(e);
      }
      panel(b, 51);
      if (keys.isEmpty()) {
         line(b, "S", "no input recorded", fmt(windowTo(null)), "0");
      }
      for (Map.Entry<String, List<String[]>> e : keys.entrySet()) {
         eventSeries(b, e.getKey(), e.getValue());
      }
      panel(b, 52);
      if (analog.isEmpty()) {
         why(b, "no pointer or stick moved in this pass");
      }
      for (Map.Entry<String, List<String[]>> e : analog.entrySet()) {
         eventSeries(b, e.getKey(), e.getValue());
      }
      panel(b, 53);
      if (step.isEmpty()) {
         why(b, "no wheel or pad hat input in this pass");
      }
      for (Map.Entry<String, List<String[]>> e : step.entrySet()) {
         eventSeries(b, e.getKey(), e.getValue());
      }
      panel(b, 54);
      if (ev.isEmpty()) {
         why(b, "no input in this pass");
         return;
      }
      line(b, "C", "s from route start", "device", "control", "value");
      for (int i = 0; i < Math.min(5000, ev.size()); i++) {
         String[] e = ev.get(i);
         line(b, "R", fmt(Math.round(num(e[0]) * 1000) / 1000.0), e[1], e[2], e[3]);
      }
   }

   private static void eventSeries(StringBuilder b, String name, List<String[]> ev) {
      double[] t = new double[ev.size()], v = new double[ev.size()];
      for (int i = 0; i < t.length; i++) {
         t[i] = num(ev.get(i)[0]);
         v[i] = num(ev.get(i)[3]);
      }
      series(b, name, t, v);
   }

   /** The console lines of the window ("L t level body", Grafana's logs panel: category, then the text). */
   private void console(StringBuilder b) {
      panel(b, 57);
      File f = file("console.out");
      if (!f.isFile() || Double.isNaN(startMs)) {
         why(b, old("the console trace was off in this pass"));
         return;
      }
      int n = 0;
      for (String l : lines(f)) {
         String[] c = BenchProc.consoleLine(l);
         if (c == null) {
            continue;
         }
         double t = (num(c[0]) - startMs) / 1000.0;
         if (t < WINDOW_FROM - 1e-9) {
            continue;
         }
         line(b, "L", fmt(t), BenchProc.logLevel(c[1]), (c[2].isEmpty() ? "" : c[2] + " ") + c[3]);
         if (++n >= 5000) {
            break;
         }
      }
   }

   // ------------------------------------------------------------------ helpers

   double windowFrom() {
      return WINDOW_FROM;
   }

   /** Grafana's window end, or later when the pass ran longer (nothing of it is cut off). */
   double windowTo(double[] t) {
      double to = Math.max(WINDOW_TO, routeS);
      if (t != null) {
         for (double x : t) {
            if (x > to) {
               to = x;
            }
         }
      }
      return to;
   }

   private static void band(StringBuilder b, String name, double[] t, double[] v, double from, double to) {
      int cols = BenchDash.BAND_COLUMNS;
      double[] lo = new double[cols], hi = new double[cols], tc = new double[cols];
      Arrays.fill(lo, Double.NaN);
      Arrays.fill(hi, Double.NaN);
      double span = Math.max(1e-6, to - from);
      for (int i = 0; i < t.length; i++) {
         if (Double.isNaN(v[i])) {
            continue;
         }
         int c = Math.min(cols - 1, Math.max(0, (int)((t[i] - from) / span * cols)));
         if (Double.isNaN(lo[c]) || v[i] < lo[c]) {
            lo[c] = v[i];
         }
         if (Double.isNaN(hi[c]) || v[i] > hi[c]) {
            hi[c] = v[i];
         }
      }
      for (int c = 0; c < cols; c++) {
         tc[c] = from + (c + 0.5) * span / cols;
      }
      line(b, "B", name, csv(tc), csv(lo), csv(hi));
   }

   static List<String> lines(File f) {
      try {
         return Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
      } catch (IOException | RuntimeException e) {
         return List.of();
      }
   }

   static double num(String s) {
      if (s == null || s.isEmpty()) {
         return Double.NaN;
      }
      try {
         return Double.parseDouble(s.trim());
      } catch (NumberFormatException e) {
         return Double.NaN;
      }
   }

   private static double[] unbox(List<Double> l) {
      double[] a = new double[l.size()];
      for (int i = 0; i < a.length; i++) {
         a[i] = l.get(i);
      }
      return a;
   }

   private static boolean seriesIf(StringBuilder b, String name, double[] t, double[] v) {
      for (double x : v) {
         if (!Double.isNaN(x)) {
            series(b, name, t, v);
            return true;
         }
      }
      return false;
   }

   private static void series(StringBuilder b, String name, double[] t, double[] v) {
      line(b, "S", name, csv(t), csv(v));
   }

   private static void panel(StringBuilder b, int id) {
      line(b, "P", Integer.toString(id));
   }

   private static void why(StringBuilder b, String text) {
      if (text != null && !text.isEmpty()) {
         line(b, "WHY", text);
      }
   }

   private static String csv(double[] v) {
      return BenchDash.csv(v);
   }

   private static String fmt(double v) {
      return BenchDash.fmt(v);
   }

   private static void line(StringBuilder b, String kind, String... cols) {
      b.append(kind);
      for (String c : cols) {
         b.append('\t').append(c == null ? "" : c.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '));
      }
      b.append('\n');
   }

   /** A CSV with a header (comment lines skipped): numeric columns by name, the text of any column, t = t_s. */
   static final class Table {
      int n;
      double[] t;
      String[] head;
      List<String[]> rows = new ArrayList<>();
      private final Map<String, double[]> cache = new HashMap<>();

      static Table read(File f) {
         if (!f.isFile()) {
            return null;
         }
         Table tb = new Table();
         for (String l : lines(f)) {
            if (l.isEmpty() || l.startsWith("#")) {
               continue;
            }
            String[] c = l.split(",", -1);
            if (tb.head == null) {
               tb.head = c;
            } else if (c.length == tb.head.length) {
               tb.rows.add(c);
            }
         }
         if (tb.head == null) {
            return null;
         }
         tb.n = tb.rows.size();
         tb.t = tb.num("t_s");
         return tb;
      }

      int index(String col) {
         for (int i = 0; i < head.length; i++) {
            if (head[i].equals(col)) {
               return i;
            }
         }
         return -1;
      }

      double[] num(String col) {
         double[] v = cache.get(col);
         if (v == null) {
            int k = index(col);
            v = new double[n];
            for (int i = 0; i < n; i++) {
               v[i] = k < 0 ? Double.NaN : BenchDashMore.num(rows.get(i)[k]);
            }
            cache.put(col, v);
         }
         return v.clone();
      }

      String[] text(String col) {
         int k = index(col);
         String[] v = new String[n];
         for (int i = 0; i < n; i++) {
            v[i] = k < 0 ? "" : rows.get(i)[k];
         }
         return v;
      }

      boolean any(String col) {
         for (double x : num(col)) {
            if (!Double.isNaN(x)) {
               return true;
            }
         }
         return false;
      }

      /** A column's values whose t lies in [from, to]. */
      float[] inRoute(String col, double from, double to) {
         double[] v = num(col);
         float[] out = new float[n];
         int k = 0;
         for (int i = 0; i < n; i++) {
            if (t[i] >= from && t[i] <= to && !Double.isNaN(v[i])) {
               out[k++] = (float)v[i];
            }
         }
         return Arrays.copyOf(out, k);
      }
   }
}
