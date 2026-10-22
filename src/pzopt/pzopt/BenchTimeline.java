package pzopt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;

/**
 * A benchmark pass's timelines (2026-10-21, the report page drawn like the Grafana run page): the 2 Hz samples of
 * pzopt-bench-sampler (machine and process CPU, game / render thread, GPU busy, the power readings, the heap) and each
 * collector pause, kept in memory while the pass runs and written beside its frames as {@code <base>-samples.csv};
 * {@link #copyWindow} keeps the timed window of a per-second log (pzopt-gamethread.out). BenchDash reads them back.
 * The sampler thread adds, the game thread writes after the pass: every method holds this object's lock.
 */
final class BenchTimeline {
   /** The samples file's columns (t_s = seconds since the route started; percent loads; watts; MiB). */
   static final String HEADER = "t_s,cpu_system_pct,cpu_process_pct,game_thread_pct,render_thread_pct,gpu_pct,total_w,cpu_w,gpu_w,soc_w,bat_w,heap_mb";
   static final int COLS = 12;

   private double[][] rows = new double[512][];
   private int n;
   private double[] gcT = new double[64], gcMs = new double[64];
   private int gcN;

   synchronized void clear() {
      n = 0;
      gcN = 0;
   }

   synchronized void sample(double t, double... v) {
      if (n == rows.length) {
         rows = Arrays.copyOf(rows, n * 2);
      }
      double[] r = new double[COLS];
      r[0] = t;
      for (int i = 0; i < v.length && i + 1 < COLS; i++) {
         r[i + 1] = v[i];
      }
      rows[n++] = r;
   }

   synchronized void gcPause(double t, double ms) {
      if (gcN == gcT.length) {
         gcT = Arrays.copyOf(gcT, gcN * 2);
         gcMs = Arrays.copyOf(gcMs, gcN * 2);
      }
      gcT[gcN] = t;
      gcMs[gcN] = ms;
      gcN++;
   }

   synchronized int size() {
      return n;
   }

   /** "t:ms,t:ms" (seconds since the route started, the pause in ms), the pass property gc_pauses. */
   synchronized String gcText() {
      StringBuilder b = new StringBuilder();
      for (int i = 0; i < gcN; i++) {
         b.append(i > 0 ? "," : "").append(String.format(Locale.ROOT, "%.3f:%.1f", gcT[i], gcMs[i]));
      }
      return b.toString();
   }

   synchronized void writeCsv(File f) throws IOException {
      if (n == 0) {
         return;
      }
      try (BufferedWriter w = Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8)) {
         w.write(HEADER);
         w.write('\n');
         for (int i = 0; i < n; i++) {
            StringBuilder b = new StringBuilder(96);
            for (int c = 0; c < COLS; c++) {
               if (c > 0) {
                  b.append(',');
               }
               double v = rows[i][c];
               if (!Double.isNaN(v) && !Double.isInfinite(v)) { // (a reading the platform lacks: an empty cell)
                  b.append(String.format(Locale.ROOT, c == 0 ? "%.3f" : "%.2f", v));
               }
            }
            w.write(b.append('\n').toString());
         }
      }
   }

   /**
    * Copies the lines of a per-second log whose first field (epoch ms) lies in [fromMs, toMs], and its comment lines,
    * into {@code dst}. Nothing is written when no line falls in the window.
    */
   static void copyWindow(File src, File dst, long fromMs, long toMs) throws IOException {
      StringBuilder b = new StringBuilder();
      int kept = 0;
      for (String line : Files.readAllLines(src.toPath(), StandardCharsets.UTF_8)) {
         if (line.startsWith("#")) {
            b.append(line).append('\n');
            continue;
         }
         int end = 0;
         while (end < line.length() && Character.isDigit(line.charAt(end))) {
            end++;
         }
         if (end == 0) {
            continue;
         }
         long t;
         try {
            t = Long.parseLong(line.substring(0, end));
         } catch (NumberFormatException e) {
            continue;
         }
         if (t >= fromMs && t <= toMs) {
            b.append(line).append('\n');
            kept++;
         }
      }
      if (kept > 0) {
         Files.writeString(dst.toPath(), b.toString(), StandardCharsets.UTF_8);
      }
   }
}
