package pzopt;

import java.io.File;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The category master switches of the PZ Optimization tab (2026-10-16): every switch known and on by default, the
 * Visuals ones live, every key they gate a real key with a sane off value, and every setting a Performance category
 * lists in pzopt_optimizations_layout.lua either gated by that category's switch or named below as left alone.
 */
public class CategoryMastersTest {
   /** Performance keys a category switch leaves as set: shared pools, guards, tuning of a gated switch, not optimizations. */
   private static final Set<String> UNGATED = Set.of(
         // worker pools other categories share (parallel / workers gate every FrameBatch user through effectiveWorkers)
         "parallel", "workers", "loadWorkers", "frameThreads", "charDrawThreads", "fileThreads", "fileInflight", "bootFileThreads",
         // guards against races and stock crashes
         "guardedCallbacks", "modelLockPerInstance", "luaWorkerGate",
         // not optimizations
         "luaChecksumExempt", "modProfile", "modCompat", "uiRetainedMods", "updateCheck", "updatePrefetch", "updateFromWorkshop",
         // tuning of a gated switch (inert once it is off)
         "macGlTimerQueries", "chunkGridFollowView", "treeRebakeLingerMs", "treeCutawayReachPx", "treeBakeMaxChunksPerSec",
         "treeBakeDirect", "glassTilesPerFrame", "floorDecalsPerFrame", "translucentLightsPerFrame", "bakeFrameBudget",
         "rebakeMaxFrames", "zoomEase", "zoomRebakeBudget", "zoomPlaceholder", "zoomFrameMs", "bloodSettleSec", "bloodAppendPlants",
         "bloodAppendVegetation", "persistentVboFrameSync", "lightingFlush", "lightingRebakeBudget", "lightingRebakeMaxFrames",
         "lightingStrongDelta", "lightingStrongBudget", "lightingStrongFrameMs", "lightingGlobalDeltaPct", "fogScalePct",
         "fogMaskFrames", "puddleCacheFrames", "zombieSimLodSteps", "zombieLodMin3d", "zombieLodMinBlend", "zombieLodUncappedFps",
         "resumeShotDetail", "audioLimiterCeilingDb", "audioLimiterStereoFold",
         // tuning of a gated switch, settings newer than the first cut (2026-10-18): frameQueue's, the chunk hand-off's,
         // bakeBudgetAdaptive's, the entity pipeline's, postupdateParallel's, hordeBatch's, renderPrewarm's, zombieLodDynamic's,
         // zombieFirstSightSprite's, zombieSpawnSpread's and burstGovernor's numbers, aotCache's and uiRetained's own options
         "frameQueueGpu", "frameQueueMinPct", "frameQueueMaxPct", "frameQueueFixedMinFps", "frameQueueLowCapMinPct",
         "frameQueueLowCapCalmMs", "frameQueueLowCapDecayPct", "frameQueueLowCapMarginPct", "chunkHandoffCost",
         "chunkHandoffSlackMaxWait", "chunkHandoffForceAnyFrame", "chunkHandoffFrameBudgetUs", "bakeBudgetGpu",
         "entityUpdatePlayerFirst", "entityUpdateMoreStates", "postupdateFuse", "postupdateClean", "postupdateEligibleOnWorker",
         "postupdateVehicleOnWorker", "postupdateNoopSkip", "hordeBatchUpload", "hordeBatchVao", "hordeBatchOrder",
         "hordeBatchUnits", "hordePaletteMismatch", "renderPrewarmOutlines", "zombieLodFixed3d", "zombieLodHysteresis",
         "zombieLodFadeMs", "zombieCompositeBudgetUs", "zombieCompositeMaxPerFrame", "zombieModelAddBudgetUs", "burstLateUs",
         "burstWorkPct", "zombieSpawnBurstUs", "zombieModelAddBurstUs", "zombieModelAddHeavyEvery", "zombieSpawnSlackBudgetUs",
         "zombieSpawnHeavyMin", "zombieSpawnMaxAgeMs", "zombieSpawnAgedCapUs", "aotNoIndy", "uiVerify", "uiAnimate", "uiSlack",
         "uiMoveReplay");

   public static void main(String[] args) throws Exception {
      for (String m : Config.PERFORMANCE_CATEGORY_MASTERS) {
         Check.check(Config.knows(m) && "true".equals(Config.defaultValue(m)) && !Config.isLive(m), m + ": known, on, next launch");
      }
      for (String m : Config.VISUALS_CATEGORY_MASTERS) {
         Check.check(Config.knows(m) && "true".equals(Config.defaultValue(m)) && Config.isLive(m), m + ": known, on, live");
      }

      // every gated key is read by Config; a tick box's off value is true / false
      Set<String> visualCats = new HashSet<>();
      for (String m : concat(Config.PERFORMANCE_CATEGORY_MASTERS, Config.VISUALS_CATEGORY_MASTERS)) {
         List<String> keys = Config.gatedBy(m);
         Check.check(keys != null && !keys.isEmpty(), m + " gates something");
         for (String k : keys) {
            Check.check(Config.knows(k), m + " gates an unknown key " + k);
            String def = Config.defaultValue(k);
            if ("true".equals(def) || "false".equals(def)) {
               String off = Config.gatedValue(k);
               Check.check("true".equals(off) || "false".equals(off), k + " is a tick box, off value " + off);
            }
         }
      }
      // the Visuals categories split the Visuals master's switches between them, each once
      for (String m : Config.VISUALS_CATEGORY_MASTERS) {
         for (String k : Config.gatedBy(m)) {
            Check.check(visualCats.add(k), k + " in two Visuals categories");
         }
      }
      Check.check(visualCats.equals(new HashSet<>(Config.gatedBy("enhancementsEnabled"))),
            "Visuals categories cover the Visuals master's switches: " + visualCats + " vs " + Config.gatedBy("enhancementsEnabled"));
      // stock values that are not "off"
      Check.check("true".equals(Config.gatedValue("driveTreeCutaway")), "driveTreeCutaway is on in the stock game (42.21)");
      Check.check("0".equals(Config.gatedValue("bakeBudget")) && "driver".equals(Config.gatedValue("texCompress")), "stock numbers");
      Check.check(Config.gatedValue("parallel") == null && Config.gatedValue("workers") == null, "shared pools never gated");

      // the layout: each category's `master` is a switch above, and lists only keys it gates or UNGATED ones
      File layout = find("src/lua/client/pzopt/pzopt_optimizations_layout.lua");
      if (layout == null) {
         System.out.println("CategoryMastersTest: layout not found from " + new File("").getAbsolutePath() + ", layout check skipped");
      } else {
         String text = Files.readString(layout.toPath());
         text = text.substring(0, text.indexOf("home = {"));
         Matcher cat = Pattern.compile("\\{ id = \"(\\w+)\", (master = \"(\\w+)\", )?title").matcher(text);
         Set<String> seen = new HashSet<>();
         int start = -1;
         String id = null;
         String master = null;
         while (true) {
            boolean found = cat.find();
            if (start >= 0 && master != null) {
               String body = text.substring(start, found ? cat.start() : text.length());
               checkCategory(id, master, body);
               seen.add(master);
            }
            if (!found) {
               break;
            }
            start = cat.end();
            id = cat.group(1);
            master = cat.group(3);
         }
         Set<String> all = new HashSet<>(List.of(concat(Config.PERFORMANCE_CATEGORY_MASTERS, Config.VISUALS_CATEGORY_MASTERS)));
         Check.check(seen.equals(all), "layout masters " + seen + " = Config's " + all);
      }
      System.out.println("CategoryMastersTest: ok");
   }

   private static void checkCategory(String id, String master, String body) {
      if (!body.contains("keys = {")) {
         return; // a Visuals category takes whole tab sections; its keys are checked against the Visuals master above
      }
      List<String> gated = Config.gatedBy(master);
      Matcher keys = Pattern.compile("keys = \\{([^}]*)\\}").matcher(body);
      while (keys.find()) {
         Matcher key = Pattern.compile("\"(\\w+)\"").matcher(keys.group(1));
         while (key.find()) {
            String k = key.group(1);
            Check.check(gated.contains(k) || UNGATED.contains(k), "category " + id + ": " + k + " is neither gated by " + master
                  + " (Config.gated) nor listed as left alone (UNGATED)");
            Check.check(!(gated.contains(k) && UNGATED.contains(k)), k + " both gated and UNGATED");
         }
      }
   }

   private static File find(String path) {
      for (File dir = new File("").getAbsoluteFile(); dir != null; dir = dir.getParentFile()) {
         File f = new File(dir, path);
         if (f.isFile()) {
            return f;
         }
      }
      return null;
   }

   private static String[] concat(String[] a, String[] b) {
      String[] out = java.util.Arrays.copyOf(a, a.length + b.length);
      System.arraycopy(b, 0, out, a.length, b.length);
      return out;
   }
}
