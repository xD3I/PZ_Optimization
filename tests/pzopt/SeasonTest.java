package pzopt;

import zombie.erosion.season.ErosionSeason;

/**
 * pzopt.Season (season= flag) and the benchmark planner's season: the names parse, a planned season becomes the pass's
 * season= flag, the start date (and a plan from before seasons) none. The day picker itself needs ErosionSeason.setDay,
 * which reads SandboxOptions (its class initializer runs the game's Lua): it is checked in game (the "harness: season"
 * console line and season_date= in pzopt-bench.out).
 */
public class SeasonTest {
   public static void main(String[] args) {
      check(Season.parse("Autumn") == ErosionSeason.SEASON_AUTUMN && Season.parse("fall") == ErosionSeason.SEASON_AUTUMN, "autumn names");
      check(Season.parse("winter ") == ErosionSeason.SEASON_WINTER && Season.parse("monsoon") == -1 && Season.parse("start") == -1, "season names");
      check(BenchSuite.seasonOf("winter").equals("winter") && BenchSuite.seasonOf(null).equals("start") && BenchSuite.seasonOf("x").equals("start"), "planner seasons");
      BenchSuite.Scenario spin = BenchSuite.byId("spin");
      check(spin != null, "spin scenario");
      check("autumn".equals(BenchSuite.flagsFor(spin, 0, "clear", "off", "12", "autumn").get("season")), "flagsFor season");
      check(!BenchSuite.flagsFor(spin, 0, "clear", "off", "12", "start").containsKey("season"), "flagsFor start date = no flag");
      check(!BenchSuite.flagsFor(spin, 0, "clear", "off", "12", null).containsKey("season"), "flagsFor old plan = no flag");
      System.out.println("SeasonTest OK");
   }

   static void check(boolean ok, String what) {
      if (!ok) {
         throw new AssertionError(what);
      }
   }
}
