package pzopt;

import java.util.ArrayList;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoDoor;

/**
 * Harness rig {@code thump=N} (2026-09-22): N zombies thumping a locked exterior door off-screen, the repro of the
 * "zombies thump doors in bursts of 8 / 16" report. {@code thump_at} s after the route starts (default 3) the rig picks
 * the closed exterior door on the ground floor nearest the player (at least 4 tiles away, on screen), locks it, sets its
 * health to {@code thump_hp} (default 500) and spawns the zombies on its outside square with the door as their thump
 * target (re-set whenever the game clears it). {@code thump_leave} s after the spawn (default 8) the player is teleported
 * {@code thump_dist} tiles (default 35: off-screen, inside the 70-tile thump sound range) east of the door: zombies
 * drawn once with a model are then scene-culled and run at their state's minimum simulation level (SIXTEENTH, one
 * update per 16 frames; a never-drawn zombie takes the distance ladder, which a high frame rate lifts back to FULL).
 * {@code thump_reveal} s after the spawn (default 0 = never) the player is teleported back to where they stood. A line every second: strikes (sum of the zombies' timeThumping increments), strikes in the
 * last second, door health, the first zombie's simulation level; {@code thump=} in pzopt-bench.out.
 * A/B: {@code --prop devActionEvalUnitMultiplier=true} is the pre-fix behaviour.
 */
final class ThumpRig {
   private static int zombies = -1; // -1 = flags not read yet, 0 = off
   private static long atNs, leaveNs, revealNs, spawnNs, lastLogNs, startNs;
   private static int homeX, homeY;
   private static boolean left;
   private static float wantDist;
   private static int hp;
   private static IsoDoor door;
   private static IsoGridSquare outside;
   private static final ArrayList<IsoZombie> spawned = new ArrayList<>();
   private static int[] lastThumping = new int[0];
   private static long strikes, strikesAtLastLog;
   private static int doorBrokenAtMs = -1;
   private static boolean revealed;

   private ThumpRig() {
   }

   static void tick(IsoPlayer p, long nowNs) {
      if (zombies == 0) {
         return;
      }
      if (zombies < 0) {
         zombies = Integer.parseInt(HarnessFlags.get("thump", "0").trim());
         if (zombies == 0) {
            return;
         }
         startNs = nowNs;
         atNs = nowNs + (long)(Float.parseFloat(HarnessFlags.get("thump_at", "3")) * 1e9);
         wantDist = Float.parseFloat(HarnessFlags.get("thump_dist", "35"));
         hp = Integer.parseInt(HarnessFlags.get("thump_hp", "500").trim());
         leaveNs = (long)(Float.parseFloat(HarnessFlags.get("thump_leave", "8")) * 1e9);
         float reveal = Float.parseFloat(HarnessFlags.get("thump_reveal", "0"));
         revealNs = reveal > 0f ? (long)(reveal * 1e9) : -1L;
         Log.info("harness: thump rig: " + zombies + " zombies at a door ~" + wantDist + " tiles away in " + HarnessFlags.get("thump_at", "3")
               + " s, door hp " + hp + (revealNs > 0 ? ", reveal after " + reveal + " s" : "") + ", devActionEvalUnitMultiplier=" + Config.DEV_ACTION_EVAL_UNIT_MULTIPLIER);
      }
      if (door == null) {
         if (nowNs >= atNs) {
            spawn(p, nowNs);
         }
         return;
      }
      for (int i = 0; i < spawned.size(); i++) {
         IsoZombie z = spawned.get(i);
         int t = z.getTimeThumping();
         if (t > lastThumping[i]) {
            strikes += t - lastThumping[i];
         }
         lastThumping[i] = t;
         if (!door.isDestroyed() && z.getThumpTarget() == null && z.getThumpTimer() <= 0 && z.getCurrentSquare() != null) {
            if (z.DistToSquared(door.getX() + 0.5f, door.getY() + 0.5f) <= 6.0f) {
               z.setThumpTarget(door);
            } else {
               z.pathToLocation(outside.getX(), outside.getY(), outside.getZ());
            }
         }
      }
      if (doorBrokenAtMs < 0 && door.isDestroyed()) {
         doorBrokenAtMs = (int)((nowNs - spawnNs) / 1_000_000L);
         Log.info("harness: thump: door broken " + doorBrokenAtMs + " ms after the spawn, strikes=" + strikes);
      }
      if (!left && nowNs - spawnNs >= leaveNs) {
         left = true;
         Log.info("harness: thump: leave, teleporting the player " + (int)wantDist + " tiles east of the door");
         p.teleportTo(door.getSquare().getX() + (int)wantDist, door.getSquare().getY(), 0);
      }
      if (revealNs > 0 && left && !revealed && nowNs - spawnNs >= revealNs) {
         revealed = true;
         Log.info("harness: thump: reveal, teleporting the player back to " + homeX + "," + homeY);
         p.teleportTo(homeX, homeY, 0);
      }
      if (nowNs - lastLogNs >= 1_000_000_000L) {
         lastLogNs = nowNs;
         IsoZombie first = spawned.isEmpty() ? null : spawned.get(0);
         Log.info(String.format(java.util.Locale.ROOT, "harness: thump t=%.1f epoch_ms=%d strikes=%d last_s=%d door_hp=%d destroyed=%s sim=%s culled=%s dist=%.1f",
               (nowNs - spawnNs) / 1e9, System.currentTimeMillis(), strikes, strikes - strikesAtLastLog, door.getHealth(), door.isDestroyed(),
               first == null ? "-" : first.getCurrentSimulationLevel(), first != null && first.isSceneCulled(), first == null ? -1f : first.DistTo(p)));
         strikesAtLastLog = strikes;
      }
   }

   private static void spawn(IsoPlayer p, long nowNs) {
      IsoCell cell = IsoWorld.instance.getCell();
      int px = p.getXi(), py = p.getYi();
      int r = 25;
      homeX = px;
      homeY = py;
      float best = Float.MAX_VALUE;
      for (int y = py - r; y <= py + r; y++) {
         for (int x = px - r; x <= px + r; x++) {
            IsoGridSquare sq = cell.getGridSquare(x, y, 0);
            if (sq == null) {
               continue;
            }
            IsoDoor d = sq.getIsoDoor();
            if (d == null || d.isOpen() || d.isDestroyed()) {
               continue;
            }
            IsoGridSquare other = d.isNorth() ? cell.getGridSquare(x, y - 1, 0) : cell.getGridSquare(x - 1, y, 0);
            if (other == null || (sq.getRoom() == null) == (other.getRoom() == null)) {
               continue; // exterior doors only: one side in a room, the other outside
            }
            IsoGridSquare out = sq.getRoom() == null ? sq : other;
            if (!out.isFree(false)) {
               continue;
            }
            float dist = (float)Math.hypot(x - px, y - py);
            if (dist < 4f) {
               continue; // not under the player
            }
            float score = dist;
            if (score < best) {
               best = score;
               door = d;
               outside = out;
            }
         }
      }
      if (door == null) {
         Log.warn("harness: thump: no closed exterior door 4-" + r + " tiles from " + px + "," + py + ", rig off");
         zombies = 0;
         return;
      }
      door.setLocked(true);
      door.setLockedByKey(true);
      door.setHealth(hp);
      ArrayList<IsoZombie> list = LuaManager.GlobalObject.addZombiesInOutfit(outside.getX(), outside.getY(), 0, zombies, null, 50);
      spawned.addAll(list);
      lastThumping = new int[spawned.size()];
      spawnNs = nowNs;
      lastLogNs = nowNs;
      for (IsoZombie z : spawned) {
         z.setThumpTarget(door);
      }
      Log.info("harness: thump: door at " + door.getSquare().getX() + "," + door.getSquare().getY() + (door.isNorth() ? " (north edge)" : " (west edge)")
            + ", zombies on " + outside.getX() + "," + outside.getY() + ", " + spawned.size() + " spawned, distance " + String.format(java.util.Locale.ROOT, "%.1f", Math.hypot(outside.getX() - px, outside.getY() - py))
            + ", " + (nowNs - startNs) / 1_000_000L + " ms after the rig start");
   }

   static boolean isRigZombie(IsoZombie z) {
      return spawned.contains(z);
   }

   static String summary() {
      if (zombies <= 0) {
         return "";
      }
      return "\nthump=zombies:" + spawned.size() + " strikes:" + strikes + " door_hp:" + (door == null ? -1 : door.getHealth())
            + " broken_ms:" + doorBrokenAtMs + " unit_multiplier:" + Config.DEV_ACTION_EVAL_UNIT_MULTIPLIER;
   }
}
