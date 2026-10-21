package pzopt;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The built-in benchmark's scenarios (docs/plan-builtin-benchmark.md): our own bench runs (harness/queue/benches.json)
 * as harness flags for {@link Harness}, plus where the fresh benchmark world spawns the player and what the world's
 * sandbox pins. The routes are the benches' own; what differs from a harness run is the world (a new sandbox world per
 * pass instead of the maintainer's bench save) and the pinned hour and weather (the bench save's 13:50 and its weather).
 */
public final class BenchSuite {
   private BenchSuite() {
   }

   public static final class Scenario {
      public final String id, title, what, stresses;
      /** spawn square of the fresh world (the debug-scenario startLoc) */
      public final int x, y, z;
      /** harness flags (HarnessFlags keys), without the benchmark's own additions */
      public final Map<String, String> flags;
      /** zombie population of the world: "normal" (sandbox default) or "max" (multipliers 4, as the louisville preset) */
      public final String population;
      /** rough seconds from world entry to the route end, for the progress bar and the time estimate */
      public final int seconds;

      Scenario(String id, String title, String what, String stresses, int x, int y, int z, String population, int seconds, String... kv) {
         this.id = id;
         this.title = title;
         this.what = what;
         this.stresses = stresses;
         this.x = x;
         this.y = y;
         this.z = z;
         this.population = population;
         this.seconds = seconds;
         Map<String, String> m = new LinkedHashMap<>();
         for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
         }
         this.flags = m;
      }
   }

   /** Rosewood, on KY-60 by the gas station: the bench save's own spot (its car stands at 8002,11204). */
   private static final int RW_X = 8004, RW_Y = 11206;

   public static final Scenario[] ALL = {
      new Scenario("spin", "Rosewood spin", "South through Rosewood at the widest zoom with the view spinning 90 degrees a second: cutaways, the vision cone, lighting and chunk streaming change every frame.",
            "game thread, render thread, frame pacing", RW_X, RW_Y, 0, "normal", 25 + 6,
            "mode", "bench", "route", "S:450", "speed", "18", "turn", "90", "zoom", "max", "settle", "6"),
      new Scenario("drive120", "120 km/h highway drive", "A car driven at 120 km/h east on KY-60 for 1,200 tiles: the heaviest chunk streaming and chunk-texture bake load.",
            "chunk arrival, disk, render thread, GPU, frame pacing", 8010, 11206, 0, "normal", 45 + 6,
            "mode", "drive", "path", "8010,11204.5/9210,11204.5", "kmh", "120", "zoom", "max", "max_seconds", "90", "settle", "6"),
      new Scenario("louisville", "Louisville horde", "Downtown Louisville with the zombie population at its maximum (about 2,000 zombies), a slow spinning walk: zombie simulation, animation and character drawing.",
            "game thread, other cores, RAM", RW_X, RW_Y, 0, "normal", 25 + 24,
            // as the harness preset: the world starts in Rosewood, the population multipliers go to 4 at world-ready and the
            // teleport loads Louisville with them (a world created with them spawned twice the zombies: 4,216 vs 2,265)
            // population 2.5 (multiplier, start and peak) lands a fresh world's downtown near the bench save's ~2,300
            // zombies (default population: 201, 4x: 6,707)
            "mode", "bench", "start", "12450,1280", "population", "2.5", "route", "S:150", "speed", "6", "turn", "90", "zoom", "max", "settle", "20"),
      new Scenario("drive120south", "Rosewood town drive", "120 km/h through Rosewood: KY-60, right onto North Main Street and south through the town, braking for the corner.",
            "chunk arrival, render thread, GPU, frame pacing", 8010, 11206, 0, "normal", 40 + 6,
            "mode", "drive", "path", "8010,11204.5/8106,11204.5/8106,11965.5", "kmh", "120", "zoom", "max", "max_seconds", "90", "settle", "6"),
      new Scenario("horde", "Horde shooting", "The Riverside pier at noon: the player runs to the pier, turns and aims; then a horde of 150 fast zombies comes and the player shoots until every one is down (at most 90 s). An M16, and a .44 Magnum drawn instead of the second reload. Combat, gunfire, ragdolls.",
            "game thread, render thread, ragdolls", 6445, 5195, 0, "normal", 60 + 6,
            "mode", "bench", "showcase", "horde", "pier", "6436,5186", "horde_dir", "SE", "burn", "false", "fire_line", "false",
            "no_attack", "true", "horde", "150", "mags", "20", "horde_when", "ready", "sidearm", "Base.Revolver_Long", "sidearm_rounds", "60",
            // end=horde_dead (2026-10-18): the route ends 2 s after the last horde zombie died, at 90 s at the latest (the old
            // fixed 25 s + 10 s hold ended with ~135 of the 150 alive); the route's own leg is only the timer underneath
            "route", "S:1", "speed", "0.04", "end", "horde_dead", "end_cap", "90", "end_tail", "2", "zoom", "1", "settle", "6"),
      new Scenario("sweep", "Visuals feature costs", "The camera stands still in Rosewood and turns while each Visuals feature that switches live is turned on and off in turn: what every feature costs on this PC, in milliseconds per frame.",
            "GPU, render thread (per feature)", RW_X, RW_Y, 0, "normal", 120,
            "mode", "bench", "zoom", "max", "settle", "6"),
   };

   /** Scenarios in the score (the sweep measures features, not a frame rate). */
   static boolean scored(String id) {
      return !"sweep".equals(id);
   }

   /** Flags every benchmark pass adds: noon, clear weather (the bench routes' daylight, without the save's weather). */
   static final String[] COMMON = {"time_of_day", "12", "weather", "clear"};

   public static Scenario byId(String id) {
      for (Scenario s : ALL) {
         if (s.id.equals(id)) {
            return s;
         }
      }
      return null;
   }

   /**
    * Timedemo (technique 27) applies to the walked bench routes; the drives follow the car's physics and the horde its
    * autopilot, the sweep's switches run on the wall clock.
    */
   static boolean timedemoApplies(Scenario s) {
      return "bench".equals(s.flags.get("mode")) && !s.flags.containsKey("showcase") && !"sweep".equals(s.id);
   }

   /** The pass's harness flags: the scenario's own, the common pins, and the timedemo step (route steps a second, 0 = wall clock). */
   static Map<String, String> flagsFor(Scenario s, int fixedStep) {
      Map<String, String> m = new LinkedHashMap<>();
      for (int i = 0; i + 1 < COMMON.length; i += 2) {
         m.put(COMMON[i], COMMON[i + 1]);
      }
      m.putAll(s.flags);
      if ("sweep".equals(s.id)) {
         BenchSweep.flags(m);
      }
      if (fixedStep > 0 && timedemoApplies(s)) {
         // technique 27: the route advances 1/fixedStep s of route time per frame; the step is this PC's own frame rate
         // from the scenario's real-time warm-up pass, so the route keeps its real speed (a fixed 1/60 s ran the spin 4x
         // fast at ~480 fps, 6 s instead of 25, with 4x the chunk streaming a second: 240 fps, runs bench-ftimedemo)
         m.put("fixed_step", Integer.toString(fixedStep));
      }
      m.put("benchmark", "1");
      return m;
   }
}
