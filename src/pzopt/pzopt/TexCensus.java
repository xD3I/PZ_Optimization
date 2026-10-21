package pzopt;

import java.util.HashMap;
import java.util.Map;

/**
 * devTexCensus (tail pass 2026-10-13): which textures the render thread creates during play. On the Louisville route ~5,500
 * textures were created in 25 s and the frame hand-off stalls (the render thread waiting for NVIDIA's driver) sat exactly in
 * the frames that created them. Counts per folder (the path up to its last '/', numbers folded) and size class, logged every
 * 10 s from the render thread. Dev only.
 */
public final class TexCensus {
   private TexCensus() {
   }

   private static final HashMap<String, int[]> COUNTS = new HashMap<>();
   private static long lastLog = System.nanoTime();
   private static int total;

   /** Render thread, TextureID.generateHwId. */
   public static void add(String path, int w, int h) {
      String key = path == null ? "(no path)" : path.replace('\\', '/');
      int slash = key.lastIndexOf('/');
      key = (slash > 0 ? key.substring(0, slash) : key).replaceAll("[0-9]+", "#");
      int px = Math.max(w, h);
      key += px >= 1024 ? " [>=1024]" : px >= 256 ? " [256-1023]" : " [<256]";
      COUNTS.computeIfAbsent(key, k -> new int[1])[0]++;
      total++;
      long now = System.nanoTime();
      if (now - lastLog > 10_000_000_000L) {
         lastLog = now;
         StringBuilder sb = new StringBuilder("tex census: " + total + " textures created; by folder:");
         COUNTS.entrySet().stream().sorted((a, b) -> b.getValue()[0] - a.getValue()[0]).limit(15)
            .forEach(e -> sb.append(' ').append(e.getKey()).append('=').append(e.getValue()[0]).append(';'));
         Log.info(sb.toString());
      }
   }
}
