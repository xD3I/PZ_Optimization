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
      // (2026-10-21, maintainer) the horde fights like a player (pzopt.HordeTactics, tactics=kite); since 2026-10-23 the same
      // scene and tactics as the harness horde bench (docs/findings-horde-bench-rework-2026-10-21.md "Under 80 s"): the old
      // Riverside pier, the horde from the south-east out of the gap between the blocks, the fight held in the arena round
      // the pier, the M16 with Laser + red dot, Desensitized, house alarms off, the aim on the head bone; zombies attack (no
      // no_attack): the first hit ends the pass as failed (kill switch), end_cap 200 s + a 220 s watchdog end a stuck one.
      // Harness clear times with the director 44.2-55.2 s (h80-p39/p40/p41), 0 hits; seconds = 70 + the settle
      new Scenario("horde", "Horde shooting", "The old pier in Riverside at noon: a horde of 150 fast zombies comes out of the gap between the blocks to the south-east and the player fights it like a player would: shoots their heads while they are far, runs round them when they come close or crowd in, reloads with room, and walks up to the last ones until every one is down. Zombies can attack: the first bite or scratch ends the pass as failed. Combat, gunfire, ragdolls.",
            "game thread, render thread, ragdolls", 6445, 5195, 0, "normal", 70 + 6,
            "mode", "bench", "showcase", "horde", "tactics", "kite", "start", "6445,5195", "pier", "6436,5186", "arena", "6450,5198,22",
            "horde_dir", "SE", "horde_gap", "12",
            "burn", "false", "fire_line", "false", "ambulance", "Base.VanAmbulance", "siren", "1", "horde", "150", "mags", "20", "horde_when", "ready",
            "sidearm", "Base.Revolver_Long", "sidearm_rounds", "24",
            // the tuned fight of h80-p39 (the M16's draw of the .44 is off: a magazine swap is faster)
            "body_shot_ms", "300", "crowd_zombies", "20", "sidearm_draw", "false", "kite_tiles", "3", "shoot_tiles", "5.5",
            "reflex_tiles", "2.2", "backpedal", "false", "reload_room", "5", "blind_tiles", "10", "close_in_left", "20",
            "close_in_tiles", "9", "floor_close_tiles", "5", "aim_settle", "-1",
            // end=horde_dead: the route ends 2 s after the last horde zombie died; end_cap / the watchdog are the safety net
            "route", "S:1", "speed", "0.02", "end", "horde_dead", "end_cap", "200", "end_tail", "2", "zoom", "1", "settle", "6"),
      // (2026-10-21, maintainer) outside the Rosewood church's main doors (east front, 8150,11517-11519): the player walks a
      // 2-square square on the pavement in front of them and turns once round at each corner, a lap per measured window
      // (BenchSweep, pzopt.SquareWalk); 4 s pre-roll + 23 windows (laps) of 10 s: 11 of the 14 features switch live on
      // this build (pixelLight, relief, mirrors apply at the next launch), bench-refl-sweep1 walked 234 s; the walk ends the route
      new Scenario("sweep", "Visuals feature costs", "The player walks a small square outside the Rosewood church's east doors, turning once round at every corner, while each Visuals feature that switches live is turned on and off in turn: what every feature costs on this PC, in milliseconds per frame.",
            "GPU, render thread (per feature)", 8153, 11515, 0, "normal", 234 + 6,
            "mode", "bench", "explore", "square", "square", "8153.5,11515.5,2", "square_spin", "360", "zombies", "off", "zoom", "max", "settle", "6"),
      // (2026-10-21, maintainer) the reflections showcase walk: the Riverside house with a home gym on the Ohio's bank (gym
      // 6283-6286 x 5377-5379, its full-length prop mirror on the west wall), puddles after rain, no zombies; the tour's stops
      // are pzopt.TourWalk's riverside-gym (walked with the game's own walk-to, pzopt.Nav; 16 stops, the autopilot's walk 99.6 s
      // in bench-refl-sweep1); the route's time is only the cap
      new Scenario("reflections", "Reflections walk", "A house with a home gym on the river bank in Riverside, after rain, no zombies: the player walks along the river, through a gate to the neighbour's swimming pool, looks in at the windows, goes in, stands before the gym's full-length mirror and turns round, and walks out to the river again. Mirrors, windows, water and puddle reflections when those Visuals are on.",
            "GPU, render thread, reflections", 6291, 5370, 0, "normal", 100 + 6,
            "mode", "bench", "explore", "tour", "tour", "riverside-gym", "zombies", "off", "puddles", "0.7", "route", "S:1", "speed", "0.004", "zoom", "0.75", "settle", "6"),
   };

   /** Scenarios in the score (the sweep measures features, not a frame rate). */
   static boolean scored(String id) {
      return !"sweep".equals(id);
   }

   /** Flags every benchmark pass adds: noon, clear weather (the bench routes' daylight, without the save's weather). */
   static final String[] COMMON = {"time_of_day", "12", "weather", "clear"};

   /** The weathers a scenario can run in (the menu's planner): Scene's weather= values. */
   static final java.util.List<String> WEATHERS = java.util.List.of("clear", "rain", "storm");

   /** A planned fog (Scene's fog=): "off", "heavy" or an intensity 0..1; "off" for anything else. */
   static String fogOf(String v) {
      if (v == null || v.isBlank() || "off".equals(v.trim()) || "heavy".equals(v.trim())) {
         return v == null || v.isBlank() ? "off" : v.trim();
      }
      try {
         float f = Float.parseFloat(v.trim());
         return f > 0f && f <= 1f ? Float.toString(f) : "off";
      } catch (NumberFormatException e) {
         return "off";
      }
   }

   /** A planned hour (0-23), noon when it is missing or not a number. */
   static int hourOf(String v) {
      try {
         int h = Integer.parseInt(v == null ? "12" : v.trim());
         return h >= 0 && h <= 23 ? h : 12;
      } catch (NumberFormatException e) {
         return 12;
      }
   }

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

   /**
    * The pass's harness flags: the scenario's own, the common pins, the plan's weather, fog and hour for it (the menu's
    * planner, 2026-10-21; null: clear, no fog, noon) and the timedemo step (route steps a second, 0 = wall clock).
    */
   static Map<String, String> flagsFor(Scenario s, int fixedStep, String weather, String fog, String hour) {
      Map<String, String> m = new LinkedHashMap<>();
      for (int i = 0; i + 1 < COMMON.length; i += 2) {
         m.put(COMMON[i], COMMON[i + 1]);
      }
      m.putAll(s.flags);
      if (hour != null) {
         m.put("time_of_day", Integer.toString(hourOf(hour)));
      }
      if (weather != null && WEATHERS.contains(weather)) {
         m.put("weather", weather);
      }
      if (fog != null && !"off".equals(fogOf(fog))) { // (fog=off would pin it at 0: the weather's own pins already hold none)
         m.put("fog", fogOf(fog));
      }
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
