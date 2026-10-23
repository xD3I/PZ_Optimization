package pzopt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a Lua function was loaded from (Lua mod compatibility, 2026-10-01): a closure's prototype keeps the path
 * LuaManager.RunLua loaded (FuncState.currentfullFile: the game's own file, or the mod file that replaces it at the
 * same relative path, or a mod's own file). The game's media/lua is "game" (ours under it, pzopt/, is "pzopt"); a file
 * inside a mod folder is "mod:<id>" (the id= of the nearest mod.info above it, a Build 42 common/ folder's from its
 * version folders beside it); anything else "other".
 *
 * <p>Users: the retained UI renders mod-drawn elements at the stock rate (uiRetainedMods), pzopt_ui_fast.lua only
 * replaces functions that are still the game's own (getPzoptLuaOrigin), the Lua gate's log line.
 */
public final class LuaOrigin {
   private LuaOrigin() {
   }

   private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Win");
   private static final String GAME_LUA = norm(canonical(new File("media" + File.separator + "lua"))) + "/";
   private static final Map<String, String> byFile = new ConcurrentHashMap<>();
   private static final Map<String, String> byDir = new ConcurrentHashMap<>();

   /** "game", "pzopt", "mod:<id>" or "other" for a Lua file path. */
   public static String of(String path) {
      if (path == null || path.isEmpty()) {
         return "other";
      }
      return byFile.computeIfAbsent(path, LuaOrigin::classify);
   }

   /** The origin of a Lua function value: of(its file), "java" for a Java function, "other" for anything else. */
   public static String ofFunction(Object fn) {
      if (fn instanceof se.krka.kahlua.vm.LuaClosure c) {
         return c.prototype != null ? of(c.prototype.filename) : "other";
      }
      return fn instanceof se.krka.kahlua.vm.JavaFunction ? "java" : "other";
   }

   /** True for a Lua function a mod (or an unknown source) defined: neither the game's nor ours; false for any other value. */
   public static boolean fromMod(Object fn) {
      if (!(fn instanceof se.krka.kahlua.vm.LuaClosure)) {
         return false;
      }
      String o = ofFunction(fn);
      return !(o.equals("game") || o.equals("pzopt"));
   }

   /** For log lines: "the game's file", "PZ Optimization", "mod <id>". */
   public static String describe(String path) {
      String o = of(path);
      return o.startsWith("mod:") ? "mod " + o.substring(4) : o.equals("game") ? "the game's file" : o.equals("pzopt") ? "PZ Optimization" : "unknown origin";
   }

   private static String classify(String path) {
      String p = norm(canonical(new File(path)));
      if (p.startsWith(GAME_LUA)) {
         return p.substring(GAME_LUA.length()).matches("[^/]+/pzopt/.*") ? "pzopt" : "game";
      }
      File dir = new File(path).getAbsoluteFile().getParentFile();
      for (int depth = 0; dir != null && depth < 12; depth++, dir = dir.getParentFile()) {
         String id = byDir.computeIfAbsent(dir.getPath(), d -> dirModId(new File(d)));
         if (!id.isEmpty()) {
            return "mod:" + id;
         }
      }
      return "other";
   }

   /**
    * The mod id of a folder: its mod.info's, or for a Build 42 mod's {@code common} folder (which has none: the mod.info
    * files sit in the version folders beside it, 42/ or 42.19/) the first version folder's.
    */
   private static String dirModId(File dir) {
      String id = modId(new File(dir, "mod.info"));
      if (!id.isEmpty() || !dir.getName().equalsIgnoreCase("common")) {
         return id;
      }
      File[] siblings = dir.getParentFile() == null ? null : dir.getParentFile().listFiles(File::isDirectory);
      if (siblings == null) {
         return "";
      }
      java.util.Arrays.sort(siblings);
      for (File s : siblings) {
         String v = s.equals(dir) ? "" : modId(new File(s, "mod.info"));
         if (!v.isEmpty()) {
            return v;
         }
      }
      return "";
   }

   private static String modId(File info) {
      if (!info.isFile()) {
         return "";
      }
      try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(info), StandardCharsets.UTF_8))) {
         String line;
         while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("id=")) {
               return line.substring(3).trim();
            }
         }
      } catch (IOException ignored) {
      }
      return "?";
   }

   private static String canonical(File f) {
      try {
         return f.getCanonicalPath();
      } catch (IOException e) {
         return f.getAbsolutePath();
      }
   }

   private static String norm(String p) {
      p = p.replace('\\', '/');
      return WINDOWS ? p.toLowerCase(Locale.ROOT) : p;
   }
}
