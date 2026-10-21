package pzopt;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * What a benchmark run was measured with, for the past results' comparison: the benchmark's own options, the PZ
 * Optimization settings off the build's defaults, the game's display and performance settings (options.ini), the active
 * mods and the Java agents. Taken once in the main-menu process that plans the run ({@link Benchmark}'s start), before
 * any pass pins a setting, into {@code runs/<run>/setup.txt}, one tab-separated line per item:
 * <pre>
 * R key value            a benchmark option of the run (passes, stock comparison, uncapped, ...)
 * O key value default    a PZ Optimization setting whose value differs from the build's default
 * G key value            a game setting of {@link #GAME_KEYS}
 * M id name version      an active mod
 * J name                 a Java agent (-javaagent jar, named after the mod that ships it)
 * </pre>
 */
final class BenchSetup {
   static final String FILE = "setup.txt";

   /** The options.ini keys that change what a frame costs or shows, with their meaning, in the order they are listed. */
   static final String[][] GAME_KEYS = {
      {"width", "Screen width"}, {"height", "Screen height"}, {"fullScreen", "Fullscreen"}, {"borderless", "Borderless window"},
      {"vsync", "Vsync"}, {"frameRate", "Frame rate limit"}, {"uncappedFPS", "Uncapped frame rate"}, {"lightFPS", "Lighting updates a second"},
      {"fogQuality", "Fog quality"}, {"water", "Water quality"}, {"puddles", "Puddles"}, {"perfPuddles", "Puddle quality"},
      {"perfSkybox", "Sky quality"}, {"bPerfReflections", "Reflections"}, {"maxTextureSize", "Max texture size"},
      {"maxVehicleTextureSize", "Max vehicle texture size"}, {"textureCompression", "Texture compression"}, {"texture2x", "Double-size textures"},
      {"highResPlacedItems", "High-resolution placed items"}, {"modelTextureMipmaps", "Model texture mipmaps"},
      {"simpleClothingTextures", "Simple clothing textures"}, {"simpleWeaponTextures", "Simple weapon textures"}, {"bloodDecals", "Blood decals"},
      {"corpseShadows", "Corpse shadows"}, {"3DGroundItem", "3D ground items"}, {"doWindSpriteEffects", "Wind sprite effects"},
      {"doDoorSpriteEffects", "Door sprite effects"}, {"doContainerOutline", "Container outlines"}, {"doVideoEffects", "Video effects"},
      {"renderPrecipitation", "Precipitation"}, {"renderPrecipIndoors", "Precipitation indoors"}, {"precipitationSpeedMultiplier", "Precipitation speed"},
      {"disableLightningDuringStorms", "No lightning flashes"}, {"uiRenderOffscreen", "UI drawn offscreen"}, {"uiRenderFPS", "UI frame rate"},
      {"tieredZombieUpdates", "Tiered zombie updates"}, {"maxActiveRagdolls", "Max active ragdolls"}, {"usePhysicsHitReaction", "Physics hit reactions"},
      {"screenFilter", "Texture filtering"}, {"viewConeOpacity", "View cone opacity"}, {"zoom", "Zoom"}, {"zoomLevels1x", "Zoom levels"},
      {"zoomLevels2x", "Zoom levels (double-size textures)"},
   };

   /** The benchmark options a run records (Benchmark's plan keys), with their meaning. */
   static final String[][] RUN_KEYS = {
      {"mode", "Stock comparison (ab) or PZ Optimization only (standard)"}, {"passes", "Passes per scenario"}, {"scenarios", "Scenarios"},
      {"uncapped", "Uncapped frame rate during the run"}, {"timedemo", "Timedemo"}, {"warmup", "Warm-up pass"}, {"seed", "Same random seed every pass"},
      {"cooldown", "Cool-down between passes (s)"},
   };

   private BenchSetup() {
   }

   /** Writes the run's setup.txt from this process's settings (the main menu's, i.e. the player's). */
   static void capture(String run, Properties plan, String scenarios, int passes) {
      StringBuilder b = new StringBuilder();
      Map<String, String> runOpts = new LinkedHashMap<>();
      runOpts.put("mode", plan.getProperty("mode", ""));
      runOpts.put("passes", Integer.toString(passes));
      runOpts.put("scenarios", scenarios);
      for (String k : new String[] {"uncapped", "timedemo", "warmup", "seed", "cooldown"}) {
         runOpts.put(k, plan.getProperty(k, ""));
      }
      for (Map.Entry<String, String> e : runOpts.entrySet()) {
         line(b, "R", e.getKey(), e.getValue());
      }
      for (Map.Entry<String, String> e : Config.nonDefault().entrySet()) {
         if (!BenchSystem.isMeasurementKey(e.getKey())) {
            String def = Config.defaultOf(e.getKey());
            line(b, "O", e.getKey(), e.getValue(), def == null ? "" : def);
         }
      }
      Properties game = readIni(new File(UserOptions.zomboidDir(), "options.ini"));
      for (String[] g : GAME_KEYS) {
         String v = game.getProperty(g[0]);
         if (v != null) {
            line(b, "G", g[0], v);
         }
      }
      try {
         for (String id : zombie.modding.ActiveMods.getById("default").getMods()) {
            zombie.gameStates.ChooseGameInfo.Mod mod = zombie.gameStates.ChooseGameInfo.getModDetails(id);
            line(b, "M", id, mod == null ? id : mod.getName(), mod == null ? "" : mod.getModVersion());
         }
      } catch (Throwable t) {
         Log.warn("benchmark: active mods: " + t);
      }
      try {
         for (String j : ModCompat.sources().keySet()) {
            line(b, "J", j);
         }
      } catch (Throwable t) {
         Log.warn("benchmark: java agents: " + t);
      }
      File f = new File(BenchPlan.runDir(run), FILE);
      try {
         f.getParentFile().mkdirs();
         Files.writeString(f.toPath(), b.toString(), StandardCharsets.UTF_8);
      } catch (IOException e) {
         Log.warn("benchmark: could not write " + f + ": " + e);
      }
   }

   private static void line(StringBuilder b, String kind, String... cols) {
      b.append(kind);
      for (String c : cols) {
         b.append('\t').append(c == null ? "" : c.replace('\t', ' ').replace('\n', ' '));
      }
      b.append('\n');
   }

   private static Properties readIni(File f) {
      Properties p = new Properties();
      try {
         for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
            int eq = l.indexOf('=');
            if (eq > 0) {
               p.setProperty(l.substring(0, eq).trim(), l.substring(eq + 1).trim());
            }
         }
      } catch (IOException e) {
         // no options.ini (a fresh install): no game settings listed
      }
      return p;
   }

   /** A run's setup lines, null when the run has none (a run from before setups were recorded). */
   static List<String[]> read(String run) {
      File f = new File(BenchPlan.runDir(run), FILE);
      if (!f.isFile()) {
         return null;
      }
      List<String[]> out = new ArrayList<>();
      try {
         for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
            String[] c = l.split("\t", -1);
            if (c.length >= 2) {
               out.add(c);
            }
         }
      } catch (IOException e) {
         return null;
      }
      return out;
   }

   /** The history row's summary: "N settings changed, M mods" ("" for a run without a setup). */
   static String summary(String run) {
      List<String[]> s = read(run);
      if (s == null) {
         return "";
      }
      int o = 0, m = 0, j = 0;
      for (String[] c : s) {
         switch (c[0]) {
            case "O" -> o++;
            case "M" -> m++;
            case "J" -> j++;
            default -> {
            }
         }
      }
      return (o == 0 ? "PZ Optimization defaults" : o + (o == 1 ? " setting" : " settings") + " changed") + ", "
            + (m == 0 ? "no mods" : m + (m == 1 ? " mod" : " mods")) + (j > 0 ? ", " + j + " Java " + (j == 1 ? "mod" : "mods") : "");
   }

   /**
    * The comparison of two runs' setups, rows "D section key label valueA valueB same" in section order: run (benchmark
    * options), opt (PZ Optimization, a setting at its default in one run shows its default there), game, mods (and Java
    * mods), sys (the machine, from each report's SYS lines). A run without a setup reads "not recorded".
    */
   static List<String[]> compare(List<String[]> a, List<String[]> b, Map<String, String> sysA, Map<String, String> sysB) {
      List<String[]> rows = new ArrayList<>();
      // benchmark options
      Map<String, String> ra = kind(a, "R", 2), rb = kind(b, "R", 2);
      for (String[] k : RUN_KEYS) {
         add(rows, "run", k[0], k[1], val(a, ra, k[0], ""), val(b, rb, k[0], ""));
      }
      // PZ Optimization settings: the union of both runs' changed keys
      Map<String, String> oa = kind(a, "O", 2), ob = kind(b, "O", 2), da = kind(a, "O", 3), db = kind(b, "O", 3);
      Set<String> keys = new java.util.TreeSet<>(oa.keySet());
      keys.addAll(ob.keySet());
      for (String k : keys) {
         String def = da.containsKey(k) ? da.get(k) : db.containsKey(k) ? db.get(k) : Config.defaultOf(k);
         String dflt = def == null ? "default" : def + " (default)";
         add(rows, "opt", k, k, val(a, oa, k, dflt), val(b, ob, k, dflt));
      }
      // game settings
      Map<String, String> ga = kind(a, "G", 2), gb = kind(b, "G", 2);
      for (String[] g : GAME_KEYS) {
         if (ga.containsKey(g[0]) || gb.containsKey(g[0])) {
            add(rows, "game", g[0], g[1], val(a, ga, g[0], "not set"), val(b, gb, g[0], "not set"));
         }
      }
      // mods: active or not, the version when on
      Map<String, String> ma = kind(a, "M", 2), mb = kind(b, "M", 2), va = kind(a, "M", 3), vb = kind(b, "M", 3);
      Set<String> mods = new LinkedHashSet<>(ma.keySet());
      mods.addAll(mb.keySet());
      for (String id : mods) {
         String name = ma.containsKey(id) ? ma.get(id) : mb.get(id);
         add(rows, "mods", id, name, a == null ? "not recorded" : ma.containsKey(id) ? on(va.get(id)) : "off",
               b == null ? "not recorded" : mb.containsKey(id) ? on(vb.get(id)) : "off");
      }
      Map<String, String> ja = kind(a, "J", 1), jb = kind(b, "J", 1);
      Set<String> agents = new LinkedHashSet<>(ja.keySet());
      agents.addAll(jb.keySet());
      for (String j : agents) {
         add(rows, "mods", "java:" + j, j + " (Java)", a == null ? "not recorded" : ja.containsKey(j) ? "on" : "off",
               b == null ? "not recorded" : jb.containsKey(j) ? "on" : "off");
      }
      // the machine
      String[][] sys = {{"cpu", "CPU"}, {"gpu", "GPU"}, {"gl", "Graphics driver"}, {"ram_gb", "RAM (GB)"}, {"os", "System"}, {"java", "Java"},
         {"screen", "Screen"}, {"refresh_hz", "Refresh rate (Hz)"}, {"game_version", "Game version"}, {"pzopt_build", "PZ Optimization build"},
         {"frame_cap", "Frame cap"}, {"vsync", "Vsync"}};
      for (String[] s : sys) {
         add(rows, "sys", s[0], s[1], sysA.getOrDefault(s[0], ""), sysB.getOrDefault(s[0], ""));
      }
      return rows;
   }

   private static String on(String version) {
      return version == null || version.isEmpty() ? "on" : "on (" + version + ")";
   }

   private static void add(List<String[]> rows, String section, String key, String label, String va, String vb) {
      rows.add(new String[] {"D", section, key, label, va, vb, va.equals(vb) ? "1" : "0"});
   }

   /** A recorded value, the fallback when the run has a setup without it, "not recorded" when it has none. */
   private static String val(List<String[]> setup, Map<String, String> m, String key, String fallback) {
      return setup == null ? "not recorded" : m.getOrDefault(key, fallback);
   }

   private static Map<String, String> kind(List<String[]> setup, String kind, int col) {
      Map<String, String> m = new LinkedHashMap<>();
      if (setup != null) {
         for (String[] c : setup) {
            if (c[0].equals(kind) && c.length > Math.max(1, col)) {
               m.put(c[1], c[col]);
            }
         }
      }
      return m;
   }
}
