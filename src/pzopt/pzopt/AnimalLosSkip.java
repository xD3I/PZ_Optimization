package pzopt;

import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoMovingObject;

/**
 * An idle animal's line of sight without the walk ({@code animalLosSkip}, zombie redesign round 3, 2026-10-13, f8-zomb3).
 *
 * <p>IsoAnimal extends IsoPlayer, so the entity scheduler updates every animal in place before the zombies' flight goes out
 * (entityUpdatePlayerFirst), and each animal's {@code updateLOS} walks the cell's whole object set (~2,500 on the Louisville
 * horde): 0.48 ms of the game thread a frame (run z3-prof1), with the flight not dispatched yet. For an animal that is idle
 * the walk changes nothing: every {@code BaseAnimalBehavior.spotted} call it makes clears {@code spottedChr} (already null),
 * ticks {@code lastAlerted} down to 0 (already 0) and returns before any other branch, and the spotted list ends as the
 * animal alone (as it was). Stock's only branches that act (a flight, an alert, stress, the player acceptance and its random
 * roll) need a zombie within 10 squares of an animal that flees zombies, a non-animal player within 10 squares of a tame one,
 * or any player near a wild one. So the call site skips the walk when, at that point:
 * <ul>
 * <li>the animal is idle: {@code spottedChr} null, {@code lastAlerted} exactly 0, its spotted list exactly itself;</li>
 * <li>it does not flee zombies, or no zombie stands within 10 + 2 squares (positions from this frame's snapshot, taken at
 *     the scheduler's update before the collection: nothing moves a zombie by 2 squares inside the collection; every zombie
 *     counts, also the ones stock's filters drop, and any level within 2 of the animal's);</li>
 * <li>it is not wild, and no non-animal player stands within 10 + 1 squares (live positions, any level).</li>
 * </ul>
 * Everything else takes the stock walk. Nothing runs on another thread. {@code devAnimalLosSkipCheck} runs the stock walk
 * anyway for every skipped animal and compares what it changed (spottedChr, lastAlerted, the spotted list, stress, the alert,
 * the alerted / fighting / attack targets, the behaviour flag, the player acceptance values): {@code mismatches} must stay 0.
 */
public final class AnimalLosSkip {
   private AnimalLosSkip() {
   }

   private static final boolean ENABLED = Config.ANIMAL_LOS_SKIP;
   private static final boolean CHECK = Config.DEV_ANIMAL_LOS_SKIP_CHECK;
   private static final float ZOMBIE_REACH = 10.0F + 2.0F;
   private static final float PLAYER_REACH = 10.0F + 1.0F;
   private static final int CELL = 16; // > ZOMBIE_REACH: a 3x3 block of cells covers the reach

   // the frame's zombie snapshot, binned on a CELL grid over its bounding box (counting sort, arrays reused)
   private static float[] zx = new float[4096], zy = new float[4096], zz = new float[4096];
   private static float[] sx = new float[4096], sy = new float[4096], sz = new float[4096]; // sorted by cell
   private static int[] cellOf = new int[4096];
   private static int[] cellStart = new int[1];
   private static int zn, minCx, minCy, gw, gh;
   private static final java.util.ArrayList<IsoPlayer> players = new java.util.ArrayList<>(4);
   private static boolean ready; // the snapshot is this frame's

   public static long frames, asked, skipped, checks, mismatches;
   public static long rSpotted, rAlerted, rWild, rList, rPlayer, rZombie; // why an animal took the walk

   public static boolean enabled() {
      return ENABLED && Overrides.enabled() && ZombAb.on(ZombAb.ANIMAL_LOS_SKIP) && !zombie.network.GameClient.client && !zombie.network.GameServer.server;
   }

   /** Game thread, MovingObjectUpdateScheduler's update before the buckets' collection: this frame's zombie positions. */
   public static void frameStart() {
      ready = false;
      stale = true; // built at the frame's first skip() (no cost in a frame without animals)
   }

   private static boolean stale;

   private static void build() {
      stale = false;
      if (!enabled()) {
         return;
      }
      zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.getCell();
      if (cell == null) {
         return;
      }
      frames++;
      zn = 0;
      players.clear();
      float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
      for (IsoMovingObject o : cell.getObjectList()) {
         if (o instanceof IsoZombie z) {
            if (zn == zx.length) {
               int m = zn * 2;
               zx = java.util.Arrays.copyOf(zx, m);
               zy = java.util.Arrays.copyOf(zy, m);
               zz = java.util.Arrays.copyOf(zz, m);
               sx = new float[m];
               sy = new float[m];
               sz = new float[m];
               cellOf = new int[m];
            }
            float x = z.getX(), y = z.getY();
            zx[zn] = x;
            zy[zn] = y;
            zz[zn] = z.getZ();
            zn++;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
         } else if (o instanceof IsoPlayer p && !(o instanceof IsoAnimal)) {
            players.add(p);
         }
      }
      if (zn > 0) {
         minCx = (int) Math.floor(minX / CELL);
         minCy = (int) Math.floor(minY / CELL);
         gw = (int) Math.floor(maxX / CELL) - minCx + 1;
         gh = (int) Math.floor(maxY / CELL) - minCy + 1;
         if (gw <= 0 || gh <= 0 || (long) gw * gh > 1_000_000L) {
            return; // degenerate: no snapshot, every animal walks
         }
         int cells = gw * gh;
         if (cellStart.length < cells + 1) {
            cellStart = new int[cells + 1];
         } else {
            java.util.Arrays.fill(cellStart, 0, cells + 1, 0);
         }
         for (int i = 0; i < zn; i++) {
            int c = ((int) Math.floor(zx[i] / CELL) - minCx) + ((int) Math.floor(zy[i] / CELL) - minCy) * gw;
            cellOf[i] = c;
            cellStart[c + 1]++;
         }
         for (int c = 0; c < cells; c++) {
            cellStart[c + 1] += cellStart[c];
         }
         int[] fill = FILL.length < cells ? (FILL = new int[cells]) : FILL;
         System.arraycopy(cellStart, 0, fill, 0, cells);
         for (int i = 0; i < zn; i++) {
            int k = fill[cellOf[i]]++;
            sx[k] = zx[i];
            sy[k] = zy[i];
            sz[k] = zz[i];
         }
      }
      ready = true;
   }

   private static int[] FILL = new int[1];

   /** Game thread, the end of the scheduler's update: the snapshot is no longer valid (the flight and the postupdate move zombies). */
   public static void frameEnd() {
      ready = false;
      stale = false;
      inUpdate = false;
   }

   private static boolean inUpdate; // between frameStart and frameEnd (the scheduler's update)

   /** Game thread, the scheduler's update start: animals updated from here to frameEnd may skip. */
   public static void updateStart() {
      frameStart();
      inUpdate = true;
   }

   /** Game thread, IsoAnimal's update in place of its updateLOS call: true = the walk is skipped (it would change nothing). */
   public static boolean skip(IsoAnimal a) {
      if (stale && inUpdate) {
         build();
      }
      if (!ready || !enabled()) {
         return false;
      }
      asked++;
      if (!quiet(a)) {
         return false;
      }
      if (CHECK) {
         check(a);
         return true; // the stock walk ran inside the check
      }
      skipped++;
      for (int k = 0; k < draws; k++) {
         zombie.core.random.Rand.Next(zombie.core.random.Rand.AdjustForFramerate(25000)); // the walk's own rolls (wild animal, far moving players)
      }
      rolls += draws;
      return true;
   }

   private static boolean quiet(IsoAnimal a) {
      if (a.getBehavior() == null || a.adef == null) {
         return false;
      }
      if (a.spottedChr != null) {
         rSpotted++;
         return false;
      }
      if (a.getBehavior().lastAlerted != 0.0F) {
         rAlerted++;
         return false;
      }
      boolean wild = a.isWild();
      java.util.Stack<IsoMovingObject> list = a.getSpottedList();
      if (list.size() != 1 || list.get(0) != a) {
         rList++;
         return false;
      }
      float ax = a.getX(), ay = a.getY(), az = a.getZ();
      draws = 0;
      if (wild) {
         // a wild animal rolls Rand.Next(AdjustForFramerate(25000)) once for every moving player its walk hands to spotted();
         // for a player beyond its spotting distance (and 3 squares) the roll is the call's only effect: the skip rolls the same
         // number of times at the same place, so the game's random stream stays bit for bit the same
         zombie.iso.IsoGridSquare sq = a.getCurrentSquare();
         if (sq == null || sq.getProperties().has(zombie.iso.SpriteDetails.IsoFlagType.smoke)) {
            rWild++;
            return false; // spotted() returns at once for every object: no roll; rare, left to the walk
         }
         float reach = Math.max(3.0F, (float) a.adef.spottingDist) + 1.0F;
         for (int i = 0; i < players.size(); i++) {
            IsoPlayer p = players.get(i);
            if (Math.abs(p.getZ() - az) > 1.0F || p.getCurrentSquare() == null || p.isInvisible() || p.isGhostMode() || p.isDead()) {
               continue; // the walk or spotted() drops this player before the roll
            }
            float dx = p.getX() - ax, dy = p.getY() - ay;
            if (dx * dx + dy * dy < reach * reach) {
               rWild++;
               return false;
            }
            if (p.isPlayerMoving()) {
               draws++;
            }
         }
      } else {
         for (int i = 0; i < players.size(); i++) {
            IsoPlayer p = players.get(i);
            float dx = p.getX() - ax, dy = p.getY() - ay;
            if (dx * dx + dy * dy < PLAYER_REACH * PLAYER_REACH) {
               rPlayer++;
               return false;
            }
         }
      }
      if (!a.adef.fleeZombies || zn == 0) {
         return true;
      }
      int cx = (int) Math.floor(ax / CELL) - minCx, cy = (int) Math.floor(ay / CELL) - minCy;
      float r2 = ZOMBIE_REACH * ZOMBIE_REACH;
      for (int gy = cy - 1; gy <= cy + 1; gy++) {
         if (gy < 0 || gy >= gh) {
            continue;
         }
         for (int gx = cx - 1; gx <= cx + 1; gx++) {
            if (gx < 0 || gx >= gw) {
               continue;
            }
            int c = gx + gy * gw;
            for (int k = cellStart[c]; k < cellStart[c + 1]; k++) {
               float dx = sx[k] - ax, dy = sy[k] - ay;
               if (dx * dx + dy * dy < r2 && Math.abs(sz[k] - az) <= 2.0F) {
                  rZombie++;
                  return false;
               }
            }
         }
      }
      return true;
   }

   /** devAnimalLosSkipCheck: the stock walk runs anyway; what it changed is compared with "nothing". */
   private static void check(IsoAnimal a) {
      checks++;
      Object before = fingerprint(a);
      boolean own = WorkerRand.installed(); // the game thread's own draws (other threads draw from the same generator)
      WorkerRand.countThread = Thread.currentThread();
      long c0 = WorkerRand.counted;
      int id0 = randId();
      a.updateLOS();
      int id1 = randId();
      long c1 = WorkerRand.counted;
      WorkerRand.countThread = null;
      Object after = fingerprint(a);
      int drawn = own ? (int) (c1 - c0) : id0 < 0 || id1 < 0 ? draws : Math.floorMod(id1 - id0, 10000);
      if (!before.equals(after) || drawn != draws) {
         mismatches++;
         if (mismatches <= 10) {
            Log.warn("animalLosSkip check: the walk did more than a skip would: " + before + " -> " + after + ", rolls " + drawn + " predicted " + draws
                  + " (own count " + own + ", nearest zombie " + nearestZombie(a) + ", wild " + a.isWild() + ", flees " + a.adef.fleeZombies + ")");
         }
      }
      rolls += draws;
   }

   private static String nearestZombie(IsoAnimal a) {
      float best = Float.MAX_VALUE;
      for (IsoMovingObject o : zombie.iso.IsoWorld.instance.getCell().getObjectList()) {
         if (o instanceof IsoZombie z) {
            float dx = z.getX() - a.getX(), dy = z.getY() - a.getY();
            best = Math.min(best, dx * dx + dy * dy);
         }
      }
      return String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(best));
   }

   private static int draws; // the rolls the walk of the animal being asked would make
   public static long rolls;
   private static java.lang.reflect.Field idField;

   /** devAnimalLosSkipCheck: RandStandard's draw counter (RandAbstract.id, +1 per integer draw, wraps at 10000), or -1. */
   private static int randId() {
      try {
         if (idField == null) {
            idField = zombie.core.random.RandAbstract.class.getDeclaredField("id");
            idField.setAccessible(true);
         }
         return idField.getInt(zombie.core.random.RandStandard.INSTANCE);
      } catch (ReflectiveOperationException | RuntimeException e) {
         return -1;
      }
   }

   private static Object fingerprint(IsoAnimal a) {
      java.util.Stack<IsoMovingObject> list = a.getSpottedList();
      double acceptance = 0.0;
      for (Float f : a.playerAcceptanceList.values()) {
         acceptance += f;
      }
      return java.util.List.of(String.valueOf(System.identityHashCode(a.spottedChr)), a.getBehavior().lastAlerted, list.size(), list.isEmpty() ? 0 : System.identityHashCode(list.get(0)),
         a.getStress(), a.isAlerted(), String.valueOf(System.identityHashCode(a.alertedChr)), String.valueOf(System.identityHashCode(a.fightingOpponent)),
         String.valueOf(System.identityHashCode(a.atkTarget)), a.getBehavior().isDoingBehavior, a.playerAcceptanceList.size(), acceptance);
   }

   /** One line for the periodic log. */
   public static String describe() {
      return "animalLosSkip: frames=" + frames + " asked=" + asked + " skipped=" + skipped + " walked: spotted=" + rSpotted + " alerted=" + rAlerted + " wild=" + rWild + " list=" + rList + " player=" + rPlayer + " zombie=" + rZombie + " rolls=" + rolls + (CHECK ? " checked=" + checks + " mismatches=" + mismatches : "");
   }
}
