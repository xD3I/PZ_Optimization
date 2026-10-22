package pzopt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * The report page's "Mods (Lua)" row (2026-10-21, panels 60-69 of pzopt_mainscreen_report_mods.lua): BenchDash's
 * answers for a pass from the files ModLuaProfile wrote beside its frames ({@code <base>-luaprof.properties} and
 * {@code <base>-luaprof.csv}). A pass without them (the option off, a run before this version) adds no panel, and the
 * page says "No data in this report" in their place. Lines in BenchDash's format (P, V, C, R, S).
 */
final class ModLuaDash {
   /** Seconds per point of the per-mod time series (the frames' ms averaged over the bucket). */
   static final double BUCKET_S = 0.5;
   /** Mods drawn as their own series; the rest are summed into "other mods". */
   static final int SERIES_MODS = 8;

   private ModLuaDash() {
   }

   static void panels(StringBuilder b, File dir, String base) {
      Properties p = read(new File(dir, base + "-luaprof.properties"));
      if (p == null) {
         return;
      }
      boolean counted = "on".equals(p.getProperty("counting", "on"));
      panel(b, 61);
      v(b, "all Lua", p.getProperty("lua_ms"));
      v(b, "game's Lua", p.getProperty("game_lua_ms"));
      v(b, "mods", p.getProperty("mods_ms"));
      panel(b, 62);
      v(b, "mods", p.getProperty("mods_pct"));
      v(b, "all Lua", p.getProperty("lua_pct"));
      panel(b, 63);
      String worst = p.getProperty("worst_mod", "");
      line(b, "V", "mod", worst.isEmpty() ? "none" : p.getProperty("worst_mod_name", worst));
      v(b, "ms/frame", worst.isEmpty() ? "" : p.getProperty("worst_mod_ms"));
      panel(b, 69);
      v(b, "samples", p.getProperty("samples"));
      v(b, "Hz", p.getProperty("sample_hz"));
      v(b, "cost us/frame", p.getProperty("overhead_us"));

      List<String[]> origins = rows(p, "origin.");
      if (!origins.isEmpty()) {
         panel(b, 64);
         line(b, "C", "mod", "kind", "ms/frame", "% of frame", "calls/frame", "p99 spike ms", "files");
         for (String[] o : origins) { // id, kind, name, ms, pct, calls, p99, files
            line(b, "R", cell(o, 2), cell(o, 1), num(cell(o, 3)), num(cell(o, 4)), counted ? num(cell(o, 5)) : "", num(cell(o, 6)), cell(o, 7));
         }
      }
      series(b, dir, base, origins);
      List<String[]> fns = rows(p, "fn.");
      if (!fns.isEmpty()) {
         panel(b, 66);
         line(b, "C", "mod", "function", "event", "self ms/frame", "total ms/frame", "calls/frame");
         for (int i = 0; i < Math.min(60, fns.size()); i++) {
            String[] f = fns.get(i); // origin, file, function, line, event, self, total, calls
            line(b, "R", shortName(origins, cell(f, 0)), cell(f, 1) + ":" + cell(f, 2) + " (" + cell(f, 3) + ")", cell(f, 4), num(cell(f, 5)), num(cell(f, 6)),
                  counted ? num(cell(f, 7)) : "");
         }
      }
      List<String[]> events = rows(p, "event.");
      if (!events.isEmpty()) {
         panel(b, 67);
         line(b, "C", "event", "Lua ms/frame", "mods ms/frame", "calls/frame", "most Lua");
         for (String[] e : events) { // label, lua ms, mods ms, calls, top origin, top ms
            line(b, "R", cell(e, 0), num(cell(e, 1)), num(cell(e, 2)), counted ? num(cell(e, 3)) : "", shortName(origins, cell(e, 4)));
         }
      }
      List<String[]> spikes = rows(p, "spike.");
      if (!spikes.isEmpty()) {
         panel(b, 68);
         line(b, "C", "at s", "frame ms", "Lua ms", "mods ms", "most Lua", "its ms");
         for (String[] s : spikes) { // t, frame ms, lua ms, mods ms, top origin, top ms
            double t = parse(cell(s, 0));
            line(b, "R", Double.isNaN(t) ? "" : String.format(Locale.ROOT, "%.2f", t), num(cell(s, 1)), num(cell(s, 2)), num(cell(s, 3)), shortName(origins, cell(s, 4)), num(cell(s, 5)));
         }
      }
   }

   /** Panel 65: each mod's ms per frame over the route (0.5 s means), the biggest SERIES_MODS mods, the rest, the game's. */
   private static void series(StringBuilder b, File dir, String base, List<String[]> origins) {
      File f = new File(dir, base + "-luaprof.csv");
      if (!f.isFile()) {
         return;
      }
      List<String> head;
      List<double[]> rows = new ArrayList<>();
      try (BufferedReader in = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
         String h = in.readLine();
         if (h == null) {
            return;
         }
         head = Arrays.asList(h.split(","));
         String line;
         while ((line = in.readLine()) != null) {
            String[] c = line.split(",", -1);
            double[] r = new double[head.size()];
            for (int i = 0; i < r.length; i++) {
               r[i] = i < c.length ? parse(c[i]) : Double.NaN;
            }
            rows.add(r);
         }
      } catch (IOException e) {
         return;
      }
      int tCol = head.indexOf("t_s");
      if (tCol < 0 || rows.isEmpty()) {
         return;
      }
      double end = rows.get(rows.size() - 1)[tCol];
      int nb = Math.max(1, (int)Math.floor(end / BUCKET_S) + 1);
      double[] t = new double[nb];
      int[] frames = new int[nb];
      for (int i = 0; i < nb; i++) {
         t[i] = (i + 0.5) * BUCKET_S;
      }
      int[] bucket = new int[rows.size()];
      for (int i = 0; i < rows.size(); i++) {
         double x = rows.get(i)[tCol];
         bucket[i] = Double.isNaN(x) ? -1 : Math.min(nb - 1, Math.max(0, (int)Math.floor(x / BUCKET_S)));
         if (bucket[i] >= 0) {
            frames[bucket[i]]++;
         }
      }
      // the columns: mods by the order of the mods table (already by cost), the game's Lua, ours
      List<String> modCols = new ArrayList<>();
      for (String c : head) {
         if (c.startsWith("mod:")) {
            modCols.add(c);
         }
      }
      List<String> order = new ArrayList<>();
      for (String[] o : origins) {
         if (modCols.contains(cell(o, 0))) {
            order.add(cell(o, 0));
         }
      }
      for (String c : modCols) {
         if (!order.contains(c)) {
            order.add(c);
         }
      }
      List<String> names = new ArrayList<>();
      List<double[]> sums = new ArrayList<>();
      double[] rest = null;
      for (int k = 0; k < order.size(); k++) {
         double[] s = sum(rows, bucket, nb, head.indexOf(order.get(k)));
         if (k < SERIES_MODS) {
            names.add(shortName(origins, order.get(k)));
            sums.add(s);
         } else {
            if (rest == null) {
               rest = new double[nb];
            }
            for (int i = 0; i < nb; i++) {
               rest[i] += s[i];
            }
         }
      }
      if (rest != null) {
         names.add("other mods");
         sums.add(rest);
      }
      for (String[] other : new String[][] {{"game", "game's Lua"}, {"pzopt", "PZ Optimization"}, {"other", "unknown origin"}}) {
         int c = head.indexOf(other[0]);
         if (c >= 0) {
            names.add(other[1]);
            sums.add(sum(rows, bucket, nb, c));
         }
      }
      if (names.isEmpty()) {
         return;
      }
      panel(b, 65);
      for (int k = 0; k < names.size(); k++) {
         double[] v = sums.get(k);
         double[] mean = new double[nb];
         for (int i = 0; i < nb; i++) {
            mean[i] = frames[i] > 0 ? v[i] / frames[i] : Double.NaN;
         }
         line(b, "S", names.get(k), BenchDash.csv(t), BenchDash.csv(mean));
      }
   }

   private static double[] sum(List<double[]> rows, int[] bucket, int nb, int col) {
      double[] s = new double[nb];
      if (col < 0) {
         return s;
      }
      for (int i = 0; i < rows.size(); i++) {
         double x = rows.get(i)[col];
         if (bucket[i] >= 0 && !Double.isNaN(x)) {
            s[bucket[i]] += x;
         }
      }
      return s;
   }

   /** A table's name for an origin id: the mod's name as the mods table writes it (id without "mod:" if unknown). */
   private static String shortName(List<String[]> origins, String id) {
      if (id == null || id.isEmpty()) {
         return "";
      }
      for (String[] o : origins) {
         if (id.equals(cell(o, 0))) {
            return cell(o, 2);
         }
      }
      if (id.startsWith("mod:")) {
         return id.substring(4);
      }
      return ModLuaProfile.displayName(id);
   }

   static Properties read(File f) {
      if (!f.isFile()) {
         return null;
      }
      Properties p = new Properties();
      try (InputStreamReader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
         p.load(r);
      } catch (IOException e) {
         return null;
      }
      return p;
   }

   /** prefix.0, prefix.1, ... split on tabs, until the first missing index. */
   static List<String[]> rows(Properties p, String prefix) {
      List<String[]> out = new ArrayList<>();
      for (int i = 0; i < 100000; i++) {
         String v = p.getProperty(prefix + i);
         if (v == null) {
            break;
         }
         out.add(v.split("\t", -1));
      }
      return out;
   }

   private static String cell(String[] r, int i) {
      return i < r.length ? r[i] : "";
   }

   /** A number cell as BenchDash writes numbers ("" for none). */
   private static String num(String s) {
      double x = parse(s);
      return Double.isNaN(x) ? "" : BenchDash.fmt(x);
   }

   static double parse(String s) {
      if (s == null || s.isEmpty() || "nan".equals(s)) {
         return Double.NaN;
      }
      try {
         return Double.parseDouble(s);
      } catch (NumberFormatException e) {
         return Double.NaN;
      }
   }

   private static void panel(StringBuilder b, int id) {
      line(b, "P", Integer.toString(id));
   }

   private static void v(StringBuilder b, String name, String value) {
      line(b, "V", name, num(value));
   }

   private static void line(StringBuilder b, String kind, String... cols) {
      b.append(kind);
      for (String c : cols) {
         b.append('\t').append(c == null ? "" : c.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '));
      }
      b.append('\n');
   }
}
