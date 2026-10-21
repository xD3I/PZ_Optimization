package pzopt;

import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoDoor;

/**
 * Harness rig {@code find=sundoor} (2026-10-18, god rays through open doors): at the route setup the rig takes the exterior
 * door within 80 squares that the key light would come in through (a room on one side, the outside on the other, the light
 * from the outside), not under a porch roof unless {@code door_cover=any}, plain doors before double / garage doors, the
 * most head-on to the light first; {@code door_at=x,y,z,N|W} pins one. The player stands {@code door_dist} (3) squares into
 * the room from it, the door closed and unlocked. From the route start, every {@code door_toggle} seconds (3) the door is
 * toggled through the game's own ToggleDoor (the player's hand): {@code harness: door toggled open|closed epoch_ms=} lines.
 */
final class DoorRig {
   private static IsoDoor door;
   private static final java.util.ArrayList<IsoDoor> more = new java.util.ArrayList<>(); // door_all=true: the other doors of the same wall
   private static long periodNs, nextNs;
   private static int toggles, maxToggles = Integer.MAX_VALUE; // door_max=N: N toggles, then the door stays (door_max=1: open from the first toggle on)

   private DoorRig() {
   }

   /** The doors the rig toggles (GodRays' dev log: what the door did, frame by frame). */
   static IsoDoor door() {
      return door;
   }

   /** The rig's door edge is (x, y, z, north) (GodRays' log of what the light volumes made of it). */
   static boolean at(int x, int y, int z, boolean north) {
      IsoDoor d = door;
      return d != null && d.getSquare() != null && d.getSquare().x == x && d.getSquare().y == y && d.getSquare().z == z && d.getNorth() == north;
   }

   /** Harness route setup (game thread): picks the door; returns the player's start {x, y, z} or null. */
   static int[] setup(IsoPlayer p) {
      periodNs = (long)(Float.parseFloat(HarnessFlags.get("door_toggle", "3").trim()) * 1e9);
      int dist = Integer.parseInt(HarnessFlags.get("door_dist", "3").trim());
      maxToggles = Integer.parseInt(HarnessFlags.get("door_max", String.valueOf(Integer.MAX_VALUE)).trim());
      String coverFlag = HarnessFlags.get("door_cover", "open").trim();
      boolean anyCover = "any".equals(coverFlag) || "only".equals(coverFlag), onlyCover = "only".equals(coverFlag); // only: porch-roofed doors alone
      String kind = HarnessFlags.get("door_kind", "any").trim(); // garage: garage doors alone
      String at = HarnessFlags.get("door_at", "").trim();
      boolean needLight = "light".equals(HarnessFlags.get("door_need", "sun").trim());
      boolean standOut = "out".equals(HarnessFlags.get("door_stand", "in").trim());
      IsoCell cell = IsoWorld.instance.currentCell;
      if (cell == null || p == null) {
         return null;
      }
      GodRays.lightNow();
      float lx = GodRays.light[0], ly = GodRays.light[1];
      int px = p.getXi(), py = p.getYi();
      IsoDoor best = null;
      IsoGridSquare bestIn = null;
      boolean bestNorth = false, bestInHere = false;
      float bestScore = -1e9F;
      int listed = 0;
      int r = at.isEmpty() ? 80 : 0;
      int[] pin = null;
      if (!at.isEmpty()) {
         String[] s = at.split(",");
         pin = new int[] {Integer.parseInt(s[0].trim()), Integer.parseInt(s[1].trim()), Integer.parseInt(s[2].trim()), s.length > 3 && "W".equalsIgnoreCase(s[3].trim()) ? 0 : 1};
      }
      for (int z = 0; z <= 1; z++) {
         for (int y = py - r; y <= py + r; y++) {
            for (int x = px - r; x <= px + r; x++) {
               int sx = pin != null ? pin[0] : x, sy = pin != null ? pin[1] : y, sz = pin != null ? pin[2] : z;
               IsoGridSquare sq = cell.getGridSquare(sx, sy, sz);
               if (sq == null) {
                  continue;
               }
               for (int i = 0; i < sq.getSpecialObjects().size(); i++) {
                  IsoObject o = sq.getSpecialObjects().get(i);
                  if (!(o instanceof IsoDoor d) || d.isBarricaded()) {
                     continue;
                  }
                  boolean north = d.getNorth();
                  if (pin != null && north != (pin[3] == 1)) {
                     continue;
                  }
                  IsoGridSquare other = cell.getGridSquare(north ? sx : sx - 1, north ? sy - 1 : sy, sz);
                  boolean inHere = sq.getRoom() != null, inOther = other != null && other.getRoom() != null;
                  if (inHere == inOther) {
                     continue;
                  }
                  float along = north ? ly : lx; // the light must come from the outside side (door_need=light: a ceiling light in the room instead)
                  zombie.iso.areas.IsoRoom rm = inHere ? sq.getRoom() : other.getRoom();
                  if (needLight ? rm.roomLights.isEmpty() : !(inHere ? along < -0.15F : along > 0.15F)) {
                     continue;
                  }
                  if (needLight) {
                     along = 1F; // (any side)
                  }
                  if (!needLight && shaded(cell, inHere ? other : sq, lx, ly, GodRays.light[2])) {
                     continue; // another building (or this one's other wing) between the door and the sun
                  }
                  boolean covered = GodRays.outsideCovered(sq, north);
                  if (covered && !anyCover && pin == null || onlyCover && !covered && pin == null) {
                     continue;
                  }
                  String name = o.getSprite() != null ? o.getSprite().getName() : "?";
                  boolean dbl = o.getProperties() != null && (o.getProperties().has(zombie.core.properties.IsoPropertyType.DOUBLE_DOOR)
                     || o.getProperties().has(zombie.core.properties.IsoPropertyType.GARAGE_DOOR));
                  boolean garage = o.getProperties() != null && o.getProperties().has(zombie.core.properties.IsoPropertyType.GARAGE_DOOR);
                  if ("garage".equals(kind) && !garage && pin == null) {
                     continue;
                  }
                  float score = Math.abs(along) * 10F - (dbl && !"garage".equals(kind) ? 20F : 0F) - (covered && !onlyCover ? 40F : 0F) - 0.02F * (Math.abs(sx - px) + Math.abs(sy - py));
                  if (listed++ < 30) {
                     Log.info(String.format(java.util.Locale.ROOT, "harness: door: candidate %s at %d,%d,%d %s open=%b covered=%b along %.2f score %.2f", name, sx, sy, sz,
                        north ? "N" : "W", d.isOpen(), covered, along, score));
                  }
                  if (score > bestScore) {
                     bestScore = score;
                     best = d;
                     bestIn = inHere ? sq : other;
                     bestNorth = north;
                     bestInHere = inHere;
                  }
               }
               if (pin != null) {
                  break;
               }
            }
            if (pin != null) {
               break;
            }
         }
         if (pin != null) {
            break;
         }
      }
      if (pin == null) {
         census(cell, px, py, 80);
      }
      if (best == null) {
         Log.warn("harness: door: no sunlit exterior door within " + r + " squares of " + px + "," + py + " (light " + lx + "," + ly + ")");
         return null;
      }
      door = best;
      door.setLocked(false);
      door.setLockedByKey(false);
      if (door.isOpen()) {
         door.ToggleDoor(p); // start closed
      }
      more.clear();
      if ("true".equals(HarnessFlags.get("door_all", "false").trim())) {
         // the exterior doors of the same kind on the same wall line within 16 squares (a fire station's bays), closed too
         boolean garage = best.getProperties() != null && best.getProperties().has(zombie.core.properties.IsoPropertyType.GARAGE_DOOR);
         IsoGridSquare b0 = best.getSquare();
         for (int k = -16; k <= 16; k++) {
            IsoGridSquare q = cell.getGridSquare(bestNorth ? b0.x + k : b0.x, bestNorth ? b0.y : b0.y + k, b0.z);
            if (q == null || k == 0) {
               continue;
            }
            for (int i = 0; i < q.getSpecialObjects().size(); i++) {
               if (q.getSpecialObjects().get(i) instanceof IsoDoor d && d.getNorth() == bestNorth && !d.isBarricaded()
                  && (d.getProperties() != null && d.getProperties().has(zombie.core.properties.IsoPropertyType.GARAGE_DOOR)) == garage) {
                  d.setLocked(false);
                  d.setLockedByKey(false);
                  if (d.isOpen()) {
                     d.ToggleDoor(p);
                  }
                  more.add(d);
               }
            }
         }
         Log.info("harness: door: door_all: " + more.size() + " more doors on the wall");
      }
      // the spot: dist squares into the room, straight in from the door (door_stand=out: out of it)
      IsoGridSquare from = bestIn;
      int stepX = bestNorth ? 0 : bestInHere ? 1 : -1, stepY = bestNorth ? bestInHere ? 1 : -1 : 0;
      if (standOut) {
         IsoGridSquare ds0 = best.getSquare();
         from = bestInHere ? cell.getGridSquare(bestNorth ? ds0.x : ds0.x - 1, bestNorth ? ds0.y - 1 : ds0.y, ds0.z) : ds0;
         stepX = -stepX;
         stepY = -stepY;
      }
      int ix = from.x, iy = from.y, iz = from.z;
      for (int k = 0; k < dist; k++) {
         IsoGridSquare nsq = cell.getGridSquare(ix + stepX, iy + stepY, iz);
         if (nsq == null || (standOut ? nsq.getRoom() != null : nsq.getRoom() != bestIn.getRoom())) {
            break;
         }
         ix += stepX;
         iy += stepY;
      }
      // a little to the side of the doorway's line: the player stands out of the shaft
      int side = Integer.parseInt(HarnessFlags.get("door_side", "1").trim());
      IsoGridSquare ssq = cell.getGridSquare(ix + (bestNorth ? side : 0), iy + (bestNorth ? 0 : side), iz);
      if (side != 0 && ssq != null && ssq.getRoom() == (standOut ? null : bestIn.getRoom())) {
         ix += bestNorth ? side : 0;
         iy += bestNorth ? 0 : side;
      }
      IsoGridSquare target = cell.getGridSquare(ix, iy, iz);
      if (target != null) {
         p.setX(ix + 0.5F);
         p.setY(iy + 0.5F);
         p.setZ(iz);
         p.setLastX(ix + 0.5F);
         p.setLastY(iy + 0.5F);
         p.setLastZ(iz);
         p.setCurrent(target);
      }
      IsoGridSquare ds = door.getSquare();
      Log.info(String.format(java.util.Locale.ROOT, "harness: door: using %s at %d,%d,%d %s (room %s), player to %d,%d,%d, light (%.2f,%.2f,%.2f)",
         door.getSprite() != null ? door.getSprite().getName() : "?", ds.x, ds.y, ds.z, bestNorth ? "N" : "W",
         bestIn.getRoom() != null ? bestIn.getRoom().getName() : "?", ix, iy, iz, lx, ly, GodRays.light[2]));
      return new int[] {ix, iy, iz};
   }

   /**
    * The exterior doors within r squares on levels 0-1 (a room on one side only): how many the roof rule closes (a floor
    * or a roof right above the outside square), by kind; logged once (how often a door can show god rays at all).
    */
   private static void census(IsoCell cell, int px, int py, int r) {
      int doors = 0, covered = 0, dbl = 0, dblCovered = 0, garage = 0, garageCovered = 0, glass = 0;
      java.util.TreeMap<String, Integer> covers = new java.util.TreeMap<>();
      for (int z = 0; z <= 1; z++) {
         for (int y = py - r; y <= py + r; y++) {
            for (int x = px - r; x <= px + r; x++) {
               IsoGridSquare sq = cell.getGridSquare(x, y, z);
               if (sq == null) {
                  continue;
               }
               for (int i = 0; i < sq.getSpecialObjects().size(); i++) {
                  if (!(sq.getSpecialObjects().get(i) instanceof IsoDoor d)) {
                     continue;
                  }
                  boolean north = d.getNorth();
                  IsoGridSquare other = cell.getGridSquare(north ? x : x - 1, north ? y - 1 : y, z);
                  if ((sq.getRoom() != null) == (other != null && other.getRoom() != null)) {
                     continue;
                  }
                  boolean c = GodRays.outsideCovered(sq, north);
                  boolean isG = d.getProperties() != null && d.getProperties().has(zombie.core.properties.IsoPropertyType.GARAGE_DOOR);
                  boolean isD = d.getProperties() != null && d.getProperties().has(zombie.core.properties.IsoPropertyType.DOUBLE_DOOR);
                  boolean isT = d.getProperties() != null && d.getProperties().has(zombie.core.properties.IsoPropertyType.DOOR_TRANS);
                  doors++;
                  covered += c ? 1 : 0;
                  if (isG) {
                     garage++;
                     garageCovered += c ? 1 : 0;
                  } else if (isD) {
                     dbl++;
                     dblCovered += c ? 1 : 0;
                  }
                  glass += isT ? 1 : 0;
                  if (c) {
                     IsoGridSquare out = sq.getRoom() != null ? other : sq;
                     IsoGridSquare above = cell.getGridSquare(out.x, out.y, z + 1);
                     String what = above == null ? "?" : above.getFloor() != null ? "floor " + (above.getFloor().getSprite() != null ? above.getFloor().getSprite().getName() : "?") : "roof";
                     what = what.replaceAll("_\\d+$", "");
                     covers.merge(what, 1, Integer::sum);
                  }
               }
            }
         }
      }
      Log.info(String.format(java.util.Locale.ROOT, "harness: door census within %d of %d,%d: %d exterior doors, %d roof-ruled (%.0f %%); double %d (%d ruled), garage %d (%d ruled), see-through %d; covers %s",
         r, px, py, doors, covered, doors > 0 ? 100.0 * covered / doors : 0.0, dbl, dblCovered, garage, garageCovered, glass, covers));
   }

   /**
    * A rough sun test for the pick (the god rays' occupancy may not be built round a fresh start yet): from the middle of
    * the outside square toward the light, any square of a room or with a wall within the distance the ray stays under one
    * level (from mid height).
    */
   private static boolean shaded(IsoCell cell, IsoGridSquare out, float lx, float ly, float lz) {
      double h = Math.hypot(lx, ly);
      if (h < 1e-3 || lz <= 0F) {
         return lz <= 0F;
      }
      double slope = lz / h / GodRays.LEVEL; // levels a square
      double reach = Math.min(30.0, 0.6 / slope);
      for (double t = 0.7; t < reach; t += 0.5) {
         int x = (int)Math.floor(out.x + 0.5 + lx / h * t), y = (int)Math.floor(out.y + 0.5 + ly / h * t);
         IsoGridSquare q = cell.getGridSquare(x, y, out.z);
         if (q != null && (q.getRoom() != null || q.has(zombie.iso.SpriteDetails.IsoFlagType.WallN) || q.has(zombie.iso.SpriteDetails.IsoFlagType.WallW)
            || q.has(zombie.iso.SpriteDetails.IsoFlagType.WallNW))) {
            return true;
         }
      }
      return false;
   }

   /** Scene.routeStart. */
   static void routeStart(long nowNs) {
      nextNs = nowNs + periodNs;
   }

   /** Scene.tick (game thread). */
   static void tick(IsoPlayer p, long nowNs) {
      if (door == null || nextNs == 0L || nowNs < nextNs || p == null || toggles >= maxToggles) {
         return;
      }
      toggles++;
      nextNs += periodNs;
      door.setLocked(false);
      door.setLockedByKey(false);
      door.ToggleDoor(p);
      for (IsoDoor d : more) {
         if (d.isOpen() != door.isOpen()) { // (a garage's leaves toggle together: one toggle a group)
            d.setLocked(false);
            d.setLockedByKey(false);
            d.ToggleDoor(p);
         }
      }
      Log.info("harness: door toggled " + (door.isOpen() ? "open" : "closed") + " epoch_ms=" + System.currentTimeMillis());
   }
}
