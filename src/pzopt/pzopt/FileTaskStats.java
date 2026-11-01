package pzopt;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per task class: how many ran on the file pool and their summed run time (docs/plan-instant-load.md). */
public final class FileTaskStats {
   private static final ConcurrentHashMap<String, long[]> byClass = new ConcurrentHashMap<>();

   private FileTaskStats() {
   }

   public static void add(Class<?> task, long ns) {
      add(task.getSimpleName(), ns);
   }

   /** a named row (e.g. time the decoders slept in TextureIDAssetManager.waitFileTask) */
   public static void add(String name, long ns) {
      long[] v = byClass.computeIfAbsent(name, k -> new long[4]);
      long end = System.currentTimeMillis();
      long start = end - ns / 1_000_000L;
      synchronized (v) {
         v[0]++;
         v[1] += ns;
         v[2] = v[2] == 0 ? start : Math.min(v[2], start);
         v[3] = Math.max(v[3], end);
      }
   }

   /** One row as "count/seconds" (0/0.0s when it never happened). */
   public static String summaryOf(String name) {
      long[] v = byClass.get(name);
      if (v == null) {
         return "0/0.0s";
      }
      synchronized (v) {
         return String.format(java.util.Locale.ROOT, "%d/%.1fs", v[0], v[1] / 1e9);
      }
   }

   /** Per class the wall span its tasks ran in: first start .. last end, epoch ms (devTexLoadStats, matched to the load trace). */
   public static String spans() {
      ArrayList<Map.Entry<String, long[]>> rows = new ArrayList<>(byClass.entrySet());
      rows.sort((a, b) -> Long.compare(a.getValue()[2], b.getValue()[2]));
      StringBuilder sb = new StringBuilder("file task spans (epoch ms):");
      for (Map.Entry<String, long[]> e : rows) {
         synchronized (e.getValue()) {
            sb.append(' ').append(e.getKey()).append('=').append(e.getValue()[2]).append("..").append(e.getValue()[3]);
         }
      }
      return sb.toString();
   }

   public static String summary() {
      ArrayList<Map.Entry<String, long[]>> rows = new ArrayList<>(byClass.entrySet());
      rows.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
      StringBuilder sb = new StringBuilder("file tasks so far:");
      for (Map.Entry<String, long[]> e : rows) {
         synchronized (e.getValue()) {
            sb.append(String.format(" %s=%d/%.1fs", e.getKey(), e.getValue()[0], e.getValue()[1] / 1e9));
         }
      }
      return sb.toString();
   }
}
