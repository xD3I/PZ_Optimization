package pzopt;

import zombie.GameTime;
import zombie.core.Core;
import zombie.erosion.ErosionMain;
import zombie.erosion.season.ErosionSeason;

/**
 * {@code season=spring|summer|autumn|winter} (harness flag, the built-in benchmark's per-scene season; 2026-10-24): the
 * world runs on a date in that season. PZ has no season of its own, only the date: erosion (tree and plant sprites, wall
 * vines, flower beds), the climate (temperature, dawn / dusk, the season's light colours, rain or snow) and pzopt.Sky all
 * follow {@code GameTime}'s day and month. So the date is moved once, in {@code IsoWorld.init} right after
 * {@code ErosionGlobals.Boot} (GameTime is loaded, no chunk is yet, the climate initialises after it), through the game's
 * own day-change path ({@code ErosionMain.mainTimer}: a new erosion epoch, so every chunk the save already holds re-runs
 * its erosion with the new season when it loads, as after a midnight).
 *
 * <p>The day is picked inside the save's year from the erosion season itself (its boundaries move year to year with
 * noise): the one where every tree and plant shows the season ({@code ErosionCategory.currentSeason} turns objects one
 * by one through a season, by each square's magic number): summer and autumn are split in two displays, all objects
 * show the first at mid-season (0.5: green summer leaves, autumn colours); spring is not split, every object shows it
 * only near its end (0.9: blossoms); winter shows bare trees throughout (0.5, its coldest stretch).
 *
 * <p>Winter also lays snow on the ground: {@code Core.setForceSnow} (the debug option the climate reads on every climate
 * tick: 70 % snow cover, snowy tree sprites, {@code ClimateManager.updateSnow}), pinned by {@link Scene} from world-ready
 * on (the climate's first update clears it: {@code updateSandboxOverrides} sees the sandbox climate cycle as changed), so
 * a winter pass draws the snow layer through its settle instead of only after a snowfall; with
 * {@code weather=rain|storm} the precipitation falls as snow. Unset: the save's own date. Nothing is written back: runs use a copied save.
 */
public final class Season {
   private Season() {
   }

   private static String name = "";
   private static int target = -1;
   private static String picked = "";

   /** IsoWorld.init, right after ErosionGlobals.Boot (game thread, world loading). */
   public static void afterErosionBoot() {
      String v = HarnessFlags.get("season");
      if (v == null || v.isBlank()) {
         return;
      }
      int season = parse(v);
      if (season < 0) {
         Log.warn("harness: unknown season '" + v.trim() + "' (spring|summer|autumn|winter); the save's date stays");
         return;
      }
      ErosionMain em = ErosionMain.getInstance();
      GameTime gt = GameTime.getInstance();
      if (em == null || em.getSeasons() == null || gt == null) {
         Log.warn("harness: season=" + v.trim() + ": erosion not started, the save's date stays");
         return;
      }
      int year = gt.getYear();
      int[] md = pick(em.getSeasons().clone(), season, year);
      if (md == null) {
         Log.warn("harness: season=" + v.trim() + ": no day of " + year + " is in that erosion season; the save's date stays");
         return;
      }
      int fromMonth = gt.getMonth(), fromDay = gt.getDay();
      name = v.trim().toLowerCase(java.util.Locale.ROOT);
      target = season;
      gt.setMonth(md[0]);
      gt.setDay(md[1]);
      em.mainTimer(); // the game's day change: season.setDay + a new erosion epoch (chunks re-run erosion as they load)
      ErosionSeason s = em.getSeasons();
      picked = String.format(java.util.Locale.ROOT, "%d-%02d-%02d (season day %.0f of %.0f, %.0f C)", year, md[0] + 1, md[1] + 1,
            s.getSeasonDay(), s.getSeasonDays(), s.getDayMeanTemperature());
      Log.info("harness: season " + name + ": date " + (fromMonth + 1) + "/" + (fromDay + 1) + " -> " + picked
            + ", erosion season " + s.getSeasonName() + (season == ErosionSeason.SEASON_WINTER ? ", snow on the ground (forceSnow)" : ""));
   }

   /** Scene.apply (world-ready) and Scene.tick: season=winter keeps the snow on the ground. */
   static void tick() {
      if (target == ErosionSeason.SEASON_WINTER && !Core.getInstance().isForceSnow()) {
         Core.getInstance().setForceSnow(true);
      }
   }

   /** True for season=winter: Scene makes forced precipitation fall as snow. */
   static boolean winter() {
      return target == ErosionSeason.SEASON_WINTER;
   }

   static int parse(String v) {
      return switch (v.trim().toLowerCase(java.util.Locale.ROOT)) {
         case "spring" -> ErosionSeason.SEASON_SPRING;
         case "summer" -> ErosionSeason.SEASON_SUMMER;
         case "autumn", "fall" -> ErosionSeason.SEASON_AUTUMN;
         case "winter" -> ErosionSeason.SEASON_WINTER;
         default -> -1;
      };
   }

   /**
    * The {month, day} (GameTime's 0-based fields) of {@code year} in erosion season {@code season} closest to the
    * progression where every object shows it. {@code s} is a scratch copy: setDay is called as the game calls it
    * (ErosionMain passes GameTime's 0-based day straight to GregorianCalendar, so day d is the calendar's d-th).
    */
   static int[] pick(ErosionSeason s, int season, int year) {
      float want = season == ErosionSeason.SEASON_SPRING ? 0.9f : 0.5f;
      int[] best = null;
      float bestErr = Float.MAX_VALUE;
      java.util.GregorianCalendar c = new java.util.GregorianCalendar(year, 0, 1);
      while (c.get(java.util.Calendar.YEAR) == year) {
         int month = c.get(java.util.Calendar.MONTH), day = c.get(java.util.Calendar.DAY_OF_MONTH) - 1;
         s.setDay(day, month, year);
         if (s.getSeason() == season && s.getSeasonDays() > 0f) {
            float err = Math.abs(s.getSeasonDay() / s.getSeasonDays() - want);
            if (err < bestErr) {
               bestErr = err;
               best = new int[]{month, day};
            }
         }
         c.add(java.util.Calendar.DAY_OF_MONTH, 1);
      }
      return best;
   }

   /** Lines for pzopt-bench.out. */
   static String summary() {
      if (target < 0) {
         return "";
      }
      String now = "";
      try {
         ErosionSeason s = zombie.iso.weather.ClimateManager.getInstance().getSeason();
         now = s.getSeasonName() + " " + String.format(java.util.Locale.ROOT, "%.1f", zombie.iso.weather.ClimateManager.getInstance().getTemperature()) + " C";
      } catch (Exception ignored) {
      }
      return "\nseason=" + name + "\nseason_date=" + picked + "\nseason_now=" + now
            + "\nsnow_frac=" + zombie.iso.weather.ClimateManager.getInstance().getSnowFracNow();
   }
}
