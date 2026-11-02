package pzopt;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * devTexLoadStats (2026-10-29, the Discord "auto atlas every loaded texture" suggestion): what texture loading costs the
 * render thread and how much the textures hold. Every TextureID upload (TextureID.generateHwId) is timed and sorted by
 * where its pixels came from: a texture-pack atlas page, a loose PNG of the game's media, a mod's file, or a runtime
 * texture without a file (framebuffers, composites). Totals before and after world ready, the slow uploads during play one
 * line each, the game's own texture byte count (TextureID.totalMemUsed). Dev only; the console lines go to the load trace.
 */
public final class TexLoadStats {
   private TexLoadStats() {
   }

   private static final String[] KINDS = {"pack", "game", "mod", "runtime"};
   /** [phase 0 = before world ready, 1 = play][kind] -> count, ns, bytes */
   private static final long[][][] TOTALS = new long[2][KINDS.length][3];
   private static final HashMap<String, long[]> FOLDERS = new HashMap<>();
   private static volatile boolean playing;
   private static int slowLogged;
   private static long lastLog;

   /** Harness: world ready. */
   public static void worldReady() {
      if (!Config.DEV_TEX_LOAD_STATS) {
         return;
      }
      Log.info(line("tex load (to world ready)", 0));
      playing = true;
   }

   /** Harness: route done. */
   public static void routeDone() {
      if (Config.DEV_TEX_LOAD_STATS) {
         Log.info(line("tex load (play)", 1));
      }
   }

   /** Render thread, TextureID.generateHwId: one upload of {@code bytes} (the game's RGBA accounting) taking {@code ns}. */
   public static void add(String path, int w, int h, long ns, long bytes) {
      String p = path == null ? null : path.replace('\\', '/');
      int kind = p == null ? 3 : p.startsWith("@pack@") ? 0 : p.contains("/workshop/") || p.contains("/mods/") ? 2 : 1;
      int phase = playing ? 1 : 0;
      long[] t = TOTALS[phase][kind];
      t[0]++;
      t[1] += ns;
      t[2] += bytes;
      if (kind == 1 || kind == 2) {
         int m = p.indexOf("/media/");
         String rest = m >= 0 ? p.substring(m + 7) : p;
         int s1 = rest.indexOf('/');
         int s2 = s1 < 0 ? -1 : rest.indexOf('/', s1 + 1);
         String key = KINDS[kind] + ":" + (s2 > 0 ? rest.substring(0, s2) : s1 > 0 ? rest.substring(0, s1) : rest);
         long[] f = FOLDERS.computeIfAbsent(key, k -> new long[3]);
         f[0]++;
         f[1] += ns;
         f[2] += bytes;
      }
      if (phase == 1 && ns > 1_000_000L && slowLogged < 200) {
         slowLogged++;
         Log.info(String.format(Locale.ROOT, "tex load slow upload in play: %.2f ms %dx%d %s %s", ns / 1e6, w, h, KINDS[kind], p));
      }
      long now = System.nanoTime();
      if (phase == 1 && now - lastLog > 10_000_000_000L) {
         lastLog = now;
         Log.info(line("tex load (play so far)", 1));
      }
   }

   private static String line(String head, int phase) {
      StringBuilder sb = new StringBuilder(head).append(':');
      for (int k = 0; k < KINDS.length; k++) {
         long[] t = TOTALS[phase][k];
         sb.append(String.format(Locale.ROOT, " %s=%d/%.1fms/%.0fMB", KINDS[k], t[0], t[1] / 1e6, t[2] / 1048576.0));
      }
      sb.append(String.format(Locale.ROOT, "; textures held %.0f MB (TextureID.totalMemUsed)",
            zombie.core.textures.TextureID.totalMemUsed / 1048576.0));
      if (phase == 0) {
         sb.append("; loose files by folder:");
         FOLDERS.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[2], a.getValue()[2])).limit(20)
            .forEach(e -> sb.append(String.format(Locale.ROOT, " %s=%d/%.1fms/%.0fMB", e.getKey(), e.getValue()[0], e.getValue()[1] / 1e6, e.getValue()[2] / 1048576.0)));
         sb.append("; ").append(FileTaskStats.spans());
      }
      return sb.toString();
   }

   /** For tests and logs: the folder rows as a map copy. */
   static Map<String, long[]> folders() {
      return new HashMap<>(FOLDERS);
   }
}
