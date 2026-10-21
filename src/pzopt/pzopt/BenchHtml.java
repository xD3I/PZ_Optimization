package pzopt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Technique 21: the benchmark report as one self-contained HTML file (no scripts, no network: charts are inline SVG)
 * and as JSON. Built from the same tab-separated lines the in-game report reads, so both always agree.
 */
final class BenchHtml {
   private BenchHtml() {
   }

   private static final String[] COLORS = {"#2a9d8f", "#e76f51", "#457b9d", "#e9c46a", "#8d6cab", "#6a994e"};

   static String render(String run, String txt, Map<String, BenchResults.Agg> agg, List<Map<String, String>> steps) {
      Map<String, String> sys = new LinkedHashMap<>();
      Map<String, String[]> scores = new LinkedHashMap<>();
      List<String[]> sweep = new ArrayList<>();
      double sweepNoise = Double.NaN;
      List<String[]> rows = new ArrayList<>(), ab = new ArrayList<>(), cmp = new ArrayList<>(), recs = new ArrayList<>(), warns = new ArrayList<>(), spikes = new ArrayList<>();
      Map<String, String> curve = new LinkedHashMap<>(), hist = new LinkedHashMap<>(), ft = new LinkedHashMap<>(), phases = new LinkedHashMap<>();
      Map<String, List<String>> persec = new LinkedHashMap<>();
      String[] head = null, prev = null;
      for (String line : txt.split("\n")) {
         String[] c = line.split("\t", -1);
         switch (c[0]) {
            case "H" -> head = c;
            case "SYS" -> sys.put(c[1], c.length > 2 ? c[2] : "");
            case "SCORE" -> scores.put(c[1], c);
            case "S" -> rows.add(c);
            case "AB" -> ab.add(c);
            case "SWEEP" -> sweep.add(c);
            case "SWEEPNOISE" -> sweepNoise = BenchResults.d(java.util.Map.of("v", c[1]), "v");
            case "CMP" -> cmp.add(c);
            case "PREV" -> prev = c;
            case "REC" -> recs.add(c);
            case "WARN" -> warns.add(c);
            case "SPIKES" -> spikes.add(c);
            case "CURVE" -> curve.put(c[1] + "/" + c[2], c[3]);
            case "HIST" -> hist.put(c[1] + "/" + c[2], c[3]);
            case "FT" -> ft.put(c[1] + "/" + c[2], c[3]);
            case "PHASES" -> phases.put(c[1] + "/" + c[2], c[3]);
            case "PERSEC" -> persec.computeIfAbsent(c[1] + "/" + c[2], k -> new ArrayList<>()).add(c[4]);
            default -> {
            }
         }
      }
      StringBuilder h = new StringBuilder(64 * 1024);
      h.append("<!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">");
      h.append("<title>PZ Benchmark ").append(esc(run)).append("</title><style>");
      h.append(":root{--bg:#f6f5f2;--fg:#1d1d1f;--mut:#6b6b70;--card:#fff;--line:#e2e0db;--good:#2a9d8f;--bad:#c0392b}");
      h.append("@media (prefers-color-scheme:dark){:root{--bg:#141517;--fg:#ececee;--mut:#9a9aa2;--card:#1d1f22;--line:#2e3034}}");
      h.append("body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.45 system-ui,-apple-system,Segoe UI,sans-serif}main{max-width:1100px;margin:auto;padding:24px 16px}");
      h.append("h1{font-size:26px;margin:0 0 4px}h2{font-size:18px;margin:28px 0 10px}.mut{color:var(--mut)}.card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px 16px;margin:10px 0}");
      h.append("table{border-collapse:collapse;width:100%;font-variant-numeric:tabular-nums}th,td{text-align:right;padding:5px 8px;border-bottom:1px solid var(--line);white-space:nowrap}th:first-child,td:first-child{text-align:left}");
      h.append(".score{font-size:44px;font-weight:700}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(320px,1fr));gap:12px}.tw{overflow-x:auto}"
            // long values without spaces (zoom lists, method names, the settings string) wrap instead of widening the card
            + ".kv td{white-space:normal;overflow-wrap:anywhere;text-align:left}.ph{table-layout:fixed}.ph td:first-child{white-space:normal;overflow-wrap:anywhere}");
      h.append(".rec{padding:6px 10px;border-left:4px solid var(--line);margin:6px 0}.rec.tip{border-color:#457b9d}.rec.warn{border-color:#e76f51}.rec.info{border-color:#2a9d8f}");
      h.append("svg{width:100%;height:auto}svg text{fill:var(--mut);font-size:11px}.ax{stroke:var(--line)}</style></head><body><main>");
      h.append("<h1>Project Zomboid benchmark</h1><div class=mut>PZ Optimization built-in benchmark &middot; run ").append(esc(run));
      if (head != null) {
         h.append(" &middot; ").append(esc(head[2]));
         if (head.length > 3 && !head[3].isEmpty()) {
            h.append(" &middot; ").append(esc(head[3]));
         }
      }
      h.append("</div>");
      h.append("<div class=grid>");
      for (String[] s : scores.values()) {
         h.append("<div class=card><div class=mut>").append("A".equals(s[1]) ? "Score, PZ Optimization" : "Score, stock game").append("</div><div class=score>")
               .append(esc(s[2])).append("</div><div class=mut>&plusmn; ").append(esc(s[3])).append(" (95 % interval) &middot; 10 &times; geometric mean of fps and 1 % low</div></div>");
      }
      h.append("<div class=card><div class=mut>System</div><div>").append(esc(sys.getOrDefault("cpu", ""))).append("<br>").append(esc(sys.getOrDefault("gpu", "")))
            .append("<br>").append(esc(sys.getOrDefault("ram_gb", ""))).append(" GB RAM &middot; ").append(esc(sys.getOrDefault("screen", ""))).append(" @ ")
            .append(esc(sys.getOrDefault("refresh_hz", ""))).append(" Hz<br><span class=mut>").append(esc(sys.getOrDefault("os", ""))).append(" &middot; ")
            .append(esc(sys.getOrDefault("java", ""))).append(" &middot; cap ").append(esc(sys.getOrDefault("frame_cap", ""))).append("</span></div></div></div>");

      // two tables of at most 12 columns: one of 19 scrolled sideways inside its card even on a 1920 px window
      h.append("<h2>Results</h2><div class='card tw'><table><tr><th>Scenario</th><th>Config</th><th>Passes</th><th>Avg fps</th><th>&plusmn; 95 %</th><th>1 % low</th><th>0.1 % low</th><th>p99 ms</th><th>Stutters</th><th>Score</th><th>CV %</th><th>Stability</th></tr>");
      for (String[] r : rows) {
         h.append("<tr><td>").append(esc(r[2])).append("</td><td>").append("A".equals(r[3]) ? "optimized" : "stock").append("</td>");
         for (int i = 4; i <= 12 && i < r.length; i++) {
            h.append("<td>").append(esc(r[i])).append("</td>");
         }
         h.append("<td>").append(r.length > 22 && !r[22].equals("nan") ? esc(r[22]) + " %" : "-").append("</td>");
         h.append("</tr>");
         if (r.length > 21 && !r[21].isEmpty()) {
            h.append("<tr><td colspan=12 class=mut style='text-align:left;white-space:normal'>").append(esc(r[21])).append("</td></tr>");
         }
      }
      h.append("</table><p class=mut>Stability: the slowest pass's fps over the fastest pass's (3DMark's frame rate stability; 97 % or more is steady).</p></div>");
      h.append("<h2>What limited the frames</h2><div class='card tw'><table><tr><th>Scenario</th><th>Config</th><th>GPU</th><th>Game</th><th>Render</th><th>Display</th><th>Latency ms</th><th>GC max ms</th><th>Load s</th></tr>");
      for (String[] r : rows) {
         h.append("<tr><td>").append(esc(r[2])).append("</td><td>").append("A".equals(r[3]) ? "optimized" : "stock").append("</td>");
         for (int i = 13; i <= 19 && i < r.length; i++) {
            h.append("<td>").append(esc(r[i])).append(i <= 16 ? " %" : "").append("</td>");
         }
         h.append("</tr>");
      }
      h.append("</table><p class=mut>GPU / Game / Render / Display: share of frames limited by the graphics card, the game thread's own work, the render thread's work, or the display (v-sync, the frame cap); the rest no single stage filled. Latency: game step start to the GPU finishing the frame.</p></div>");

      if (!ab.isEmpty()) {
         h.append("<h2>PZ Optimization vs the stock game</h2><div class='card tw'><table><tr><th>Scenario</th><th>Speed-up (avg fps)</th><th>95 % interval</th><th>p (Welch)</th><th>1 % low speed-up</th></tr>");
         for (String[] a : ab) {
            String held = a.length > 7 ? a[7] : "-";
            String bound = held.equals("A") ? "&ge; " : held.equals("B") ? "&le; " : held.equals("AB") ? "capped: " : "";
            h.append("<tr><td>").append(esc(title(a[1]))).append("</td><td>").append(bound).append(esc(a[2])).append("&times;</td><td>").append(esc(a[3])).append(" &ndash; ").append(esc(a[4]))
                  .append("&times;</td><td>").append(esc(a[5])).append("</td><td>").append(esc(a[6])).append("&times;</td></tr>");
         }
         h.append("</table><p class=mut>&ge; / &le;: PZ Optimization's / the stock game's passes sat at the frame cap or the screen's refresh, so the speed-up is a bound.</p></div>");
      }
      if (!sweep.isEmpty()) {
         h.append("<h2>What each Visuals feature costs on this PC</h2><div class='card tw'><table><tr><th>Feature</th><th>Off ms</th><th>On ms</th><th>Cost ms / frame</th><th>GPU ms</th><th>Passes</th><th>Back to baseline</th></tr>");
         for (String[] w : sweep) {
            double cost = BenchResults.d(java.util.Map.of("v", w[5]), "v");
            boolean quiet = !Double.isNaN(sweepNoise) && Math.abs(cost) < 2 * sweepNoise;
            h.append("<tr><td>").append(esc(w[2])).append("</td><td>").append(esc(w[3])).append("</td><td>").append(esc(w[4])).append("</td><td>").append(quiet ? "&asymp;0 (" + esc(w[5]) + ", within noise)" : esc(w[5]))
                  .append("</td><td>").append(esc(w[6])).append("</td><td>").append(esc(w[7])).append("</td><td>");
            double shift = w.length > 8 ? BenchResults.d(java.util.Map.of("v", w[8]), "v") : Double.NaN;
            h.append(Double.isNaN(shift) || Double.isNaN(sweepNoise) ? "-" : Math.abs(shift) > 4 * sweepNoise
                  ? String.format(java.util.Locale.ROOT, "moved %+.3f ms", shift) : "yes").append("</td></tr>");
         }
         h.append("</table><p class=mut>The camera turns in place; each feature is switched for one turn between turns in your own setting. Features that apply only at the next launch are not in the sweep."
               + (Double.isNaN(sweepNoise) ? "" : String.format(java.util.Locale.ROOT, " Noise floor (the same estimate between two unswitched turns): &plusmn;%.3f ms; a cost inside twice that is not measurable on this PC.", 2 * sweepNoise)) + "</p></div>");
      }
      if (prev != null && !cmp.isEmpty()) {
         h.append("<h2>Against the previous run on this setup (").append(esc(prev[1])).append(")</h2><div class='card tw'><table><tr><th>Scenario</th><th>Before fps</th><th>Now fps</th><th>Change</th><th>p</th><th>Verdict</th></tr>");
         for (String[] c : cmp) {
            h.append("<tr><td>").append(esc(title(c[1]))).append("</td><td>").append(esc(c[2])).append("</td><td>").append(esc(c[3])).append("</td><td>").append(esc(c[4])).append(" %</td><td>")
                  .append(esc(c[5])).append("</td><td>").append(esc(c[6])).append("</td></tr>");
         }
         h.append("</table></div>");
      }
      if (!recs.isEmpty()) {
         h.append("<h2>What limits this PC</h2><div class=card>");
         for (String[] r : recs) {
            h.append("<div class='rec ").append(esc(r[1])).append("'>").append(esc(r[2])).append("</div>");
         }
         h.append("</div>");
      }
      h.append("<h2>Per scenario</h2><div class=grid>");
      for (String[] r : rows) {
         String k = r[1] + "/" + r[3];
         h.append("<div class=card><b>").append(esc(r[2])).append("</b> <span class=mut>").append("A".equals(r[3]) ? "optimized" : "stock").append("</span>");
         h.append("<div class=mut>Frame times, first pass (ms; each point the slowest frame of its slice)</div>").append(line(BenchResults.csvD(ft.get(k)), "ms", 0));
         List<String> ps = persec.get(k);
         if (ps != null && !ps.isEmpty()) {
            List<double[]> lines = new ArrayList<>();
            for (String p : ps) {
               lines.add(BenchResults.csvD(p));
            }
            h.append("<div class=mut>Frames per second over the route, every pass</div>").append(multi(lines, "fps"));
         }
         h.append("<div class=mut>Percentile curve (ms at 0, 1, 5, 10, 25, 50, 75, 90, 95, 99, 99.5, 99.9, 100 %)</div>").append(bars(BenchResults.csvD(curve.get(k)), pLabels(), "#457b9d"));
         h.append("<div class=mut>Share of time by frame time (ms)</div>").append(bars(BenchResults.csvD(hist.get(k)), histLabels(), "#2a9d8f"));
         String ph = phases.get(k);
         if (ph != null && !ph.isEmpty()) {
            h.append("<div class=mut>Game thread time by sub-phase, first pass (stack samples)</div><table class=ph>");
            for (String e : ph.split(";")) {
               int eq = e.lastIndexOf('=');
               if (eq <= 0) {
                  continue;
               }
               double pct = BenchResults.d(java.util.Map.of("v", e.substring(eq + 1)), "v");
               h.append("<tr><td>").append(esc(e.substring(0, eq))).append("</td><td style='width:40%'><div style='background:#457b9d;height:9px;width:")
                     .append(String.format(Locale.ROOT, "%.1f", Math.min(100, pct))).append("%'></div></td><td style='width:4.5em'>").append(esc(e.substring(eq + 1))).append(" %</td></tr>");
            }
            h.append("</table>");
         }
         h.append("</div>");
      }
      h.append("</div>");
      if (!spikes.isEmpty()) {
         h.append("<h2>Spikes across passes</h2><div class='card tw'><table><tr><th>Scenario</th><th>Config</th><th>Seconds slow in most passes (content)</th><th>Seconds slow in one pass (system)</th></tr>");
         for (String[] s : spikes) {
            h.append("<tr><td>").append(esc(title(s[1]))).append("</td><td>").append("A".equals(s[2]) ? "optimized" : "stock").append("</td><td>").append(esc(s[3])).append("</td><td>").append(esc(s[4])).append("</td></tr>");
         }
         h.append("</table></div>");
      }
      h.append("<h2>Setup</h2><div class='card tw'><table class=kv>");
      for (Map.Entry<String, String> e : sys.entrySet()) {
         h.append("<tr><td>").append(esc(e.getKey())).append("</td><td style='text-align:left;white-space:normal'>").append(esc(e.getValue())).append("</td></tr>");
      }
      h.append("</table></div>");
      List<String[]> setup = BenchSetup.read(run);
      if (setup != null) { // the player's settings and mods when the run started (BenchSetup)
         String[][] kinds = {{"O", "PZ Optimization settings changed from the defaults"}, {"G", "Game settings"}, {"M", "Mods"}, {"J", "Java mods"}};
         h.append("<div class=grid>");
         for (String[] k : kinds) {
            h.append("<div class=card><b>").append(k[1]).append("</b><table class=kv>");
            int n = 0;
            for (String[] c : setup) {
               if (!c[0].equals(k[0])) {
                  continue;
               }
               n++;
               String label = c[1], value = c.length > 2 ? c[2] : "";
               if (k[0].equals("G")) {
                  for (String[] g : BenchSetup.GAME_KEYS) {
                     label = g[0].equals(c[1]) ? g[1] : label;
                  }
               } else if (k[0].equals("O")) {
                  value += c.length > 3 ? " (default " + c[3] + ")" : "";
               } else if (k[0].equals("M")) {
                  label = value;
                  value = c.length > 3 ? c[3] : "";
               }
               h.append("<tr><td style='text-align:left'>").append(esc(label)).append("</td><td style='text-align:left;white-space:normal'>").append(esc(value)).append("</td></tr>");
            }
            if (n == 0) {
               h.append("<tr><td class=mut style='text-align:left'>").append(k[0].equals("O") ? "all at their defaults" : "none").append("</td></tr>");
            }
            h.append("</table></div>");
         }
         h.append("</div>");
      }
      h.append("<p class=mut>Frame data per pass: the <code>step-*-frames.csv</code> files beside this report (PresentMon columns; CapFrameX and similar tools open them).</p></main></body></html>\n");
      return h.toString();
   }

   private static String title(String id) {
      BenchSuite.Scenario s = BenchSuite.byId(id);
      return s == null ? id : s.title;
   }

   private static String[] pLabels() {
      String[] l = new String[BenchStats.CURVE_P.length];
      for (int i = 0; i < l.length; i++) {
         float p = BenchStats.CURVE_P[i];
         l[i] = p == (int)p ? Integer.toString((int)p) : Float.toString(p);
      }
      return l;
   }

   private static String[] histLabels() {
      String[] l = new String[BenchStats.HIST_EDGES.length];
      for (int i = 0; i < l.length; i++) {
         float e = BenchStats.HIST_EDGES[i];
         l[i] = (e == (int)e ? Integer.toString((int)e) : String.format(Locale.ROOT, "%.1f", e)) + (i == l.length - 1 ? "+" : "");
      }
      return l;
   }

   /** One line chart with a y axis from 0 (or {@code floor}) to the max. */
   private static String line(double[] v, String unit, double floor) {
      List<double[]> l = new ArrayList<>();
      l.add(v);
      return multi(l, unit);
   }

   private static String multi(List<double[]> lines, String unit) {
      double max = 0;
      int len = 0;
      for (double[] v : lines) {
         for (double x : v) {
            if (!Double.isNaN(x)) {
               max = Math.max(max, x);
            }
         }
         len = Math.max(len, v.length);
      }
      if (len < 2 || max <= 0) {
         return "<p class=mut>no data</p>";
      }
      max = niceCeil(max);
      // t: room above the top grid line for its axis number (11 px text, centred on the line); the unit sits 5 above the bottom
      int w = 520, hh = 150, l = 40, b = 18, t = 12;
      StringBuilder s = new StringBuilder();
      s.append("<svg viewBox='0 0 ").append(w).append(' ').append(hh).append("' role=img>");
      for (int i = 0; i <= 4; i++) {
         double y = t + (hh - b - t) * i / 4.0;
         s.append("<line class=ax x1=").append(l).append(" x2=").append(w).append(" y1=").append(f(y)).append(" y2=").append(f(y)).append(" />");
         s.append("<text x=2 y=").append(f(y + 4)).append('>').append(String.format(Locale.ROOT, "%.0f", max * (4 - i) / 4)).append("</text>");
      }
      s.append("<text x=").append(w - 30).append(" y=").append(hh - 5).append('>').append(esc(unit)).append("</text>");
      int ci = 0;
      for (double[] v : lines) {
         s.append("<polyline fill=none stroke-width=1.4 stroke='").append(COLORS[ci++ % COLORS.length]).append("' points='");
         for (int i = 0; i < v.length; i++) {
            if (Double.isNaN(v[i])) {
               continue;
            }
            double x = l + (w - l - 4) * i / (double)(len - 1);
            double y = t + (hh - b - t) * (1 - v[i] / max);
            s.append(f(x)).append(',').append(f(y)).append(' ');
         }
         s.append("' />");
      }
      return s.append("</svg>").toString();
   }

   private static String bars(double[] v, String[] labels, String color) {
      if (v.length == 0) {
         return "<p class=mut>no data</p>";
      }
      double max = 0;
      for (double x : v) {
         if (!Double.isNaN(x)) {
            max = Math.max(max, x);
         }
      }
      if (max <= 0) {
         return "<p class=mut>no data</p>";
      }
      int w = 520, hh = 130, b = 18;
      double bw = (w - 8) / (double)v.length;
      StringBuilder s = new StringBuilder("<svg viewBox='0 0 " + w + " " + hh + "' role=img>");
      for (int i = 0; i < v.length; i++) {
         double bh = Double.isNaN(v[i]) ? 0 : (hh - b - 18) * v[i] / max; // 18: the tallest bar's value label above it
         double x = 4 + i * bw;
         s.append("<rect x=").append(f(x + 1)).append(" y=").append(f(hh - b - bh)).append(" width=").append(f(bw - 2)).append(" height=").append(f(bh)).append(" fill='").append(color).append("' />");
         s.append("<text x=").append(f(x + 2)).append(" y=").append(f(hh - b - bh - 3)).append('>').append(String.format(Locale.ROOT, v[i] >= 10 ? "%.0f" : "%.1f", v[i])).append("</text>");
         if (i < labels.length) {
            s.append("<text x=").append(f(x + 2)).append(" y=").append(hh - 4).append('>').append(esc(labels[i])).append("</text>");
         }
      }
      return s.append("</svg>").toString();
   }

   private static double niceCeil(double v) {
      double p = Math.pow(10, Math.floor(Math.log10(v)));
      for (double m : new double[] {1, 2, 2.5, 5, 10}) {
         if (m * p >= v) {
            return m * p;
         }
      }
      return 10 * p;
   }

   private static String f(double v) {
      return String.format(Locale.ROOT, "%.1f", v);
   }

   static String esc(String s) {
      if (s == null) {
         return "";
      }
      return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
   }

   // ------------------------------------------------------------------ JSON

   static String json(String run, Map<String, String> sys, Map<String, BenchResults.Agg> agg, List<Map<String, String>> steps) {
      StringBuilder j = new StringBuilder();
      j.append("{\n  \"run\": ").append(q(run)).append(",\n  \"system\": {");
      boolean first = true;
      for (Map.Entry<String, String> e : sys.entrySet()) {
         j.append(first ? "" : ",").append("\n    ").append(q(e.getKey())).append(": ").append(q(e.getValue()));
         first = false;
      }
      j.append("\n  },\n  \"scenarios\": [");
      first = true;
      for (BenchResults.Agg a : agg.values()) {
         j.append(first ? "" : ",").append("\n    {\"id\": ").append(q(a.scenario)).append(", \"config\": ").append(q(a.config)).append(", \"passes\": ").append(a.fps.n)
               .append(", \"fps\": ").append(num(a.fps.mean)).append(", \"fps_ci95\": ").append(num(a.fps.ci95)).append(", \"fps_cv_pct\": ").append(num(a.fps.cvPct))
               .append(", \"low1_avg\": ").append(num(a.low1.mean)).append(", \"low01_avg\": ").append(num(a.low01.mean)).append(", \"p99_ms\": ").append(num(a.p99.mean))
               .append(", \"score\": ").append(num(a.score.mean)).append(", \"means\": {");
         boolean f2 = true;
         for (Map.Entry<String, Double> e : a.mean.entrySet()) {
            j.append(f2 ? "" : ", ").append(q(e.getKey())).append(": ").append(num(e.getValue()));
            f2 = false;
         }
         j.append("}}");
         first = false;
      }
      j.append("\n  ],\n  \"passes\": [");
      first = true;
      for (Map<String, String> st : steps) {
         j.append(first ? "" : ",").append("\n    {");
         boolean f3 = true;
         for (Map.Entry<String, String> e : st.entrySet()) {
            if (e.getKey().startsWith("sys.")) {
               continue;
            }
            j.append(f3 ? "" : ", ").append(q(e.getKey())).append(": ").append(q(e.getValue()));
            f3 = false;
         }
         j.append('}');
         first = false;
      }
      return j.append("\n  ]\n}\n").toString();
   }

   private static String num(Double v) {
      return v == null || Double.isNaN(v) || Double.isInfinite(v) ? "null" : String.format(Locale.ROOT, "%.4f", v);
   }

   private static String q(String s) {
      StringBuilder b = new StringBuilder("\"");
      for (char c : (s == null ? "" : s).toCharArray()) {
         switch (c) {
            case '"' -> b.append("\\\"");
            case '\\' -> b.append("\\\\");
            case '\n' -> b.append("\\n");
            case '\t' -> b.append("\\t");
            default -> {
               if (c < 0x20) {
                  b.append(String.format("\\u%04x", (int)c));
               } else {
                  b.append(c);
               }
            }
         }
      }
      return b.append('"').toString();
   }
}
