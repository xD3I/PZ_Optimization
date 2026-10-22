package pzopt;

import java.util.ArrayList;
import java.util.Locale;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;

/**
 * Harness scene {@code explore=tour} (2026-10-21, the maintainer: "a run that showcases the reflections and visual features
 * by walking around a house with a gym room and next to a water source"): the player walks a fixed list of stops on foot
 * with the game's own "walk to" (pzopt.Nav: the pathfinder, doors opened on the way, never a teleport, never movement keys),
 * and at each stop turns to its view (a mirror, a window, the river) and holds it, or looks once round. The stops come from
 * {@code tour=<preset>} ({@link #PRESETS}) or {@code tour=x,y,z[@deg][+spin];...} (deg: the facing to hold, 0 = east, 90 =
 * south; +spin: one full turn there).
 *
 * <p>With {@code director=jev} harness/explore-director.py (scene "tour") decides every 0.3 s from
 * {@code Zomboid/pzopt-explore-state.json}: face_view, look_around, next_stop, hold, done. Without it (the built-in
 * benchmark's "Reflections walk") an autopilot plays the same order: arrive, face the view and hold it
 * {@code tour_hold} s, turn round once in {@code tour_spin_secs} s where asked, next. Every event is logged with its epoch
 * ms ({@code harness: tour: ...}) so a capture can be cut per stop; {@code harness: tour walk: done} and the nav summary
 * end it (harness/navjudge.py).
 */
final class TourWalk {
   private TourWalk() {
   }

   static final String[] ACTIONS = {"face_view", "look_around", "next_stop", "hold", "done"};

   /**
    * Named tours. riverside-gym: the Riverside house with a home gym (gym room 6283-6286 x 5377-5379, the full-length prop
    * mirror recreational_sports_01_77..79 on its west wall, facing east) between the Ohio (water at y <= 5364, the
    * railway on the bank at 5365-5366) and the street: along the bank, through the gate (6276,5368) into the neighbour's
    * yard (the gate's two sides and the railway beside it are stops with no view: the walk passes them without a
    * pause; straight to the pool the pathfinder vaulted the fence corner at 6278,5368, run refl-gym2) to the north edge of
    * its swimming pool (water 6269-6273 x 5373-5382: the river lies north of everything on this
    * bank, so nothing here reflects in it from the iso camera; the pool lies screen-below the player and the loungers),
    * back out and round to the gym's window from outside, in at the front door (6290-6291,5377), the living room, the gym in front of the mirror, the
    * living-room window from inside, out again, the kitchen window, the bank east of the house.
    */
   static final String[][] PRESETS = {
      {"riverside-gym", "6291.5,5365.5,0@270+spin;6281.5,5365.5,0@270;6277.5,5366.5,0;6276.5,5367.5,0;6276.5,5369.5,0;6271.5,5372.4,0@90+spin;"
            + "6276.5,5369.5,0;6276.5,5367.5,0;6277.5,5366.5,0;6285.5,5375.6,0@90;6291.5,5378.5,0@180;6285.5,5378.5,0@180+spin;"
            + "6285.6,5379.5,0@180;6288.5,5378.4,0@270;6294.5,5375.6,0@90;6299.5,5365.5,0@270+spin"},
   };

   static final class Stop {
      float x, y;
      int z;
      float face = Float.NaN;
      boolean spin, arrived, faced, looked, failed;
      String label = "";
   }

   private static final ArrayList<Stop> stops = new ArrayList<>();
   private static boolean on, director, finished, selfTest;
   private static int si = -1, commands, commandSeq = -1;
   private static String command = "hold", tourName = "";
   private static long startNs, lastNs, lastStateNs, lastCmdCheckNs, lastLogNs, actionNs;
   private static float holdSecs, spinSecs, limitSecs, turned;
   /**
    * The facing the turns steer, kept here: Nav.face ignores a change under 1 degree, so a turn stepped from the player's
    * own angle every frame never moved at 156 fps (run refl-gym1: 360 / 4 s = 0.6 degrees a frame, no look round at all).
    */
   private static float heading = Float.NaN;
   private static java.io.File stateFile, cmdFile;

   static boolean active() {
      return on;
   }

   static boolean finished() {
      return finished;
   }

   /** The stops of a tour flag: a preset's name or the list itself. */
   static String expand(String tour) {
      for (String[] p : PRESETS) {
         if (p[0].equalsIgnoreCase(tour.trim())) return p[1];
      }
      return tour;
   }

   static void worldReady(IsoPlayer p) {
      on = true;
      director = "jev".equalsIgnoreCase(HarnessFlags.get("director", "").trim());
      selfTest = "true".equalsIgnoreCase(HarnessFlags.get("nav_selftest", "false").trim());
      holdSecs = Float.parseFloat(HarnessFlags.get("tour_hold", "2.5").trim());
      spinSecs = Float.parseFloat(HarnessFlags.get("tour_spin_secs", "4").trim());
      limitSecs = Float.parseFloat(HarnessFlags.get("tour_max_seconds", "240").trim());
      tourName = HarnessFlags.get("tour", "riverside-gym").trim();
      for (String s : expand(tourName).split(";")) {
         s = s.trim();
         if (s.isEmpty()) continue;
         Stop st = new Stop();
         int plus = s.indexOf('+');
         if (plus >= 0) {
            st.spin = s.substring(plus + 1).trim().equalsIgnoreCase("spin");
            s = s.substring(0, plus);
         }
         int at = s.indexOf('@');
         if (at >= 0) {
            st.face = Float.parseFloat(s.substring(at + 1).trim());
            s = s.substring(0, at);
         }
         String[] xyz = s.split(",");
         st.x = Float.parseFloat(xyz[0].trim());
         st.y = Float.parseFloat(xyz[1].trim());
         st.z = xyz.length > 2 ? Integer.parseInt(xyz[2].trim()) : 0;
         stops.add(st);
      }
      java.io.File zd = new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir());
      stateFile = new java.io.File(zd, "pzopt-explore-state.json");
      cmdFile = new java.io.File(zd, "pzopt-explore-cmd.txt");
      cmdFile.delete();
      stateFile.delete(); // a previous run's state would be answered before this run writes its own
      p.getCheats().set(zombie.characters.CheatType.GOD_MODE, true);
      Log.info(String.format(Locale.ROOT, "harness: explore=tour %s: %d stops, hold %.1f s, a turn round in %.1f s, %s", tourName, stops.size(), holdSecs, spinSecs,
            director ? "director jev" : "autopilot"));
   }

   static void routeStart(IsoPlayer p) {
      startNs = System.nanoTime();
      lastNs = startNs;
      IsoCell cell = IsoWorld.instance.getCell();
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < stops.size(); i++) {
         Stop st = stops.get(i);
         // a stop the body cannot stand on with room to turn (a chair moved, another map version) moves to the nearest that can
         if (!Nav.canStandClear(st.x, st.y, st.z)) {
            float[] near = nearestClear(st.x, st.y, st.z);
            if (near != null) {
               Log.info(String.format(Locale.ROOT, "harness: tour: stop %d moved %.2f,%.2f -> %.2f,%.2f (room to stand and turn)", i + 1, st.x, st.y, near[0], near[1]));
               st.x = near[0];
               st.y = near[1];
            }
         }
         IsoGridSquare sq = cell == null ? null : cell.getGridSquare((int)Math.floor(st.x), (int)Math.floor(st.y), st.z);
         zombie.iso.RoomDef r = sq == null || sq.getRoom() == null ? IsoWorld.instance.getMetaGrid().getRoomAt((int)Math.floor(st.x), (int)Math.floor(st.y), st.z) : sq.getRoom().getRoomDef();
         st.label = r == null || r.name == null ? "outside" : r.name;
         sb.append(String.format(Locale.ROOT, " %d:%s@%.1f,%.1f,%d%s%s", i + 1, st.label, st.x, st.y, st.z, Float.isNaN(st.face) ? "" : String.format(Locale.ROOT, " face %.0f", st.face), st.spin ? " spin" : ""));
      }
      Log.info("harness: tour: stops" + sb);
      // the walk opens the doors on its way (the pathfinder's door check); a locked one would fail the leg (MirrorWalk's rule)
      int unlocked = 0;
      if (cell != null && !stops.isEmpty()) {
         float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
         for (Stop st : stops) {
            x0 = Math.min(x0, st.x);
            y0 = Math.min(y0, st.y);
            x1 = Math.max(x1, st.x);
            y1 = Math.max(y1, st.y);
         }
         for (int z = 0; z <= 1; z++) {
            for (int y = (int)y0 - 4; y <= (int)y1 + 4; y++) {
               for (int x = (int)x0 - 4; x <= (int)x1 + 4; x++) {
                  IsoGridSquare sq = cell.getGridSquare(x, y, z);
                  if (sq == null) continue;
                  for (int i = 0; i < sq.getObjects().size(); i++) {
                     if (sq.getObjects().get(i) instanceof zombie.iso.objects.IsoDoor d && (d.isLocked() || d.isLockedByKey()) && !d.isBarricaded()) {
                        d.setLocked(false);
                        d.setLockedByKey(false);
                        unlocked++;
                     }
                  }
               }
            }
         }
      }
      if (unlocked > 0) Log.info("harness: tour: " + unlocked + " locked door(s) on the way unlocked");
      if (stops.isEmpty()) {
         finished = true;
         return;
      }
      if (!director) command = "next_stop";
      if (!selfTest) next(p);
   }

   private static float[] nearestClear(float x, float y, int z) {
      for (float r = 0.25F; r <= 1.5F; r += 0.25F) {
         for (int k = 0; k < 16; k++) {
            double a = k * Math.PI / 8;
            float nx = x + (float)(r * Math.cos(a)), ny = y + (float)(r * Math.sin(a));
            if (Nav.canStandClear(nx, ny, z)) return new float[] {nx, ny};
         }
      }
      return null;
   }

   /** Walk to the next stop (the last one walked: done). */
   private static void next(IsoPlayer p) {
      si++;
      if (si >= stops.size()) {
         command = "done";
         return;
      }
      Stop st = stops.get(si);
      turned = 0F;
      heading = Float.NaN; // the walk turns the player: the next turn starts from where he faces then
      Nav.go(p, st.x, st.y, st.z, "tour stop " + (si + 1) + " " + st.label);
   }

   private static void event(String what) {
      Stop st = stops.get(si);
      IsoPlayer p = IsoPlayer.getInstance();
      Log.info(String.format(Locale.ROOT, "harness: tour: %s stop %d %s (%.2f,%.2f,%d) at %.2f,%.2f,%.1f dir %.0f epoch_ms=%d", what, si + 1, st.label, st.x, st.y, st.z,
            p == null ? 0F : p.getX(), p == null ? 0F : p.getY(), p == null ? 0F : p.getZ(), p == null ? 0F : p.getDirectionAngle(), System.currentTimeMillis()));
   }

   /** Turn the facing toward {@code target} degrees by at most {@code step}; true when there. */
   private static boolean turnToward(IsoPlayer p, float target, float step) {
      if (Float.isNaN(heading)) heading = p.getDirectionAngle();
      float d = ((target - heading) % 360F + 540F) % 360F - 180F;
      if (Math.abs(d) <= step) {
         heading = target;
         Nav.face(p, target);
         return true;
      }
      heading += Math.signum(d) * step;
      Nav.face(p, heading);
      return false;
   }

   static void tick(IsoPlayer p, long nowNs) {
      if (finished || stops.isEmpty()) return;
      float dt = Math.min(0.1F, (nowNs - lastNs) / 1e9F);
      lastNs = nowNs;
      if (director && nowNs - lastCmdCheckNs >= 100_000_000L) {
         lastCmdCheckNs = nowNs;
         readCommand(p, nowNs);
      }
      if (selfTest && Nav.selfTest(p, nowNs)) return; // nav_selftest=true: the collision counter's control first
      if (selfTest && si < 0 && Nav.status() == Nav.IDLE) next(p); // the walk waited for the push
      Showcase.releaseKeys(); // no movement key may be down: it would cancel the game's walk
      Nav.frame(p, nowNs);
      float secs = (nowNs - startNs) / 1e9F;
      if (!command.equals("done") && secs > limitSecs + 5F) {
         Log.info(String.format(Locale.ROOT, "harness: tour walk: time limit passed by 5 s without a done, ending (%d commands)", commands));
         command = "done";
         Nav.cancel();
      }
      if (si < 0 || si >= stops.size()) {
         if (command.equals("done")) end(nowNs);
         return;
      }
      Stop st = stops.get(si);
      if (!st.arrived && !st.failed) {
         int s = Nav.status();
         if (s == Nav.ARRIVED) {
            st.arrived = true;
            actionNs = nowNs;
            event("arrived at");
         } else if (s == Nav.FAILED) {
            st.failed = true;
            st.arrived = true; // the walk goes on from where it stands
            actionNs = nowNs;
            event("could not reach");
         }
      }
      if (st.arrived) {
         if (!director) autopilot(p, st, nowNs);
         act(p, st, nowNs, dt);
      }
      if (command.equals("done")) {
         end(nowNs);
         return;
      }
      if (nowNs - lastLogNs >= 2_000_000_000L) {
         lastLogNs = nowNs;
         Log.info(String.format(Locale.ROOT, "harness: tour walk: t=%.0fs %s stop %d/%d %s pos=%.2f,%.2f,%.1f dir=%.0f", secs, command, si + 1, stops.size(), st.label,
               p.getX(), p.getY(), p.getZ(), p.getDirectionAngle()));
      }
      if (director && nowNs - lastStateNs >= 300_000_000L) {
         lastStateNs = nowNs;
         writeState(p, st, secs);
      }
   }

   /** Without a director: face the view and hold it, turn round where asked, then the next stop. */
   private static void autopilot(IsoPlayer p, Stop st, long nowNs) {
      boolean wantFace = !Float.isNaN(st.face);
      if (wantFace && !st.faced) {
         command = "face_view";
      } else if (st.spin && !st.looked) {
         command = "look_around";
      } else {
         command = "next_stop";
         next(p);
      }
   }

   private static void act(IsoPlayer p, Stop st, long nowNs, float dt) {
      switch (command) {
         case "face_view" -> {
            if (Float.isNaN(st.face)) {
               st.faced = true;
               return;
            }
            boolean there = turnToward(p, st.face, 240F * dt);
            if (!there) actionNs = nowNs; // the hold counts from facing it
            if (there && !st.faced && nowNs - actionNs >= (long)(holdSecs * 1e9)) {
               st.faced = true;
               event("faced the view at");
            }
         }
         case "look_around" -> {
            if (st.looked) return;
            float step = 360F / Math.max(0.5F, spinSecs) * dt;
            if (Float.isNaN(heading)) heading = p.getDirectionAngle();
            heading += step;
            Nav.face(p, heading);
            turned += step;
            if (turned >= 360F) {
               st.looked = true;
               event("looked round at");
            }
         }
         default -> {
         }
      }
   }

   private static void end(long nowNs) {
      if (finished) return;
      finished = true;
      int reached = 0;
      for (Stop s : stops) if (s.arrived && !s.failed) reached++;
      Log.info(String.format(Locale.ROOT, "harness: tour walk: done at +%.1f s, %d of %d stops reached, %d commands epoch_ms=%d", (nowNs - startNs) / 1e9, reached, stops.size(),
            commands, System.currentTimeMillis()));
      Log.info("harness: nav summary: " + Nav.summary());
   }

   // ---- the director (Jev) ----

   private static void readCommand(IsoPlayer p, long nowNs) {
      try {
         if (cmdFile == null || !cmdFile.isFile()) return;
         String[] parts = java.nio.file.Files.readString(cmdFile.toPath()).trim().split("\\s+");
         if (parts.length < 2) return;
         int seq = Integer.parseInt(parts[0]);
         if (seq == commandSeq) return;
         commandSeq = seq;
         String c = parts[1];
         if (!java.util.Arrays.asList(ACTIONS).contains(c) || c.equals("hold") || c.equals(command) && !c.equals("next_stop")) return;
         Stop st = si >= 0 && si < stops.size() ? stops.get(si) : null;
         if (c.equals("next_stop") && (st != null && !st.arrived)) return; // still walking there
         // a started check runs to its end before another command replaces it
         if (!c.equals("done") && st != null && st.arrived && (command.equals("face_view") && !st.faced || command.equals("look_around") && !st.looked)) return;
         if (c.equals("done")) Nav.cancel();
         Log.info("harness: tour walk director: " + command + " -> " + c + " (#" + seq + ") epoch_ms=" + System.currentTimeMillis());
         commands++;
         command = c;
         actionNs = nowNs;
         if (c.equals("next_stop")) next(p);
      } catch (Exception e) {
         // a half-written file: the next check reads it
      }
   }

   private static void writeState(IsoPlayer p, Stop st, float secs) {
      String json = String.format(Locale.ROOT,
            "{\"t\":%d,\"scene\":\"tour\",\"seconds_since_start\":%.1f,\"time_limit_seconds\":%.0f,\"time_is_up\":%b,\"current_action\":\"%s\","
                  + "\"stop\":{\"number\":%d,\"stops_total\":%d,\"is_last_stop\":%b,\"place\":\"%s\",\"arrived_at_stop\":%b,\"could_not_reach_it\":%b,"
                  + "\"has_a_view_to_face\":%b,\"faced_view_here\":%b,\"wants_a_look_around\":%b,\"looked_around_here\":%b,\"all_done_here\":%b},"
                  + "\"player\":{\"moving\":%b,\"distance_to_stop_tiles\":%.2f}}",
            System.currentTimeMillis(), secs, limitSecs, secs >= limitSecs, command,
            si + 1, stops.size(), si + 1 >= stops.size(), st.label, st.arrived, st.failed,
            !Float.isNaN(st.face), st.faced || Float.isNaN(st.face), st.spin, st.looked || !st.spin, st.arrived && (st.faced || Float.isNaN(st.face)) && (st.looked || !st.spin),
            p.isPlayerMoving(), Math.hypot(st.x - p.getX(), st.y - p.getY()));
      try {
         java.io.File tmp = new java.io.File(stateFile.getPath() + ".tmp");
         java.nio.file.Files.writeString(tmp.toPath(), json);
         java.nio.file.Files.move(tmp.toPath(), stateFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (Exception e) {
         Log.warn("harness: tour walk: state write failed: " + e);
      }
   }
}
