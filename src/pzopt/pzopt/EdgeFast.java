package pzopt;

import java.util.concurrent.atomic.AtomicLong;
import zombie.core.properties.PropertyContainer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.GridSquareEdgeFacingDirection;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoWindow;


/**
 * edgeTestFast. Build 42.21 rewrote IsoGridSquare's wall / window / door tests round edge objects: isBlockedTo ->
 * isWallTo / isWindowBlockedTo / isDoorBlockedTo -> isEdgeElementTo (a TriPredicate per edge) -> isEdgeElement (a
 * BiPredicate per facing) -> findObject (Type.tryCastTo and another BiPredicate per object). The call sites are shared by
 * every test, so they are megamorphic and nothing inlines; IsoMovingObject.separate asks it for every neighbour square of
 * every zombie each frame (1.5-2 % of the game thread in the Louisville horde, 42.20 had flag checks there). This is the
 * same computation written out: the same four edge cases in the same order, the same neighbour lookups through the
 * current cell, the same flag and object tests, no lambdas. devEdgeFastCheck compares it with the game's on every call.
 */
public final class EdgeFast {
   private EdgeFast() {
   }

   private static final int WALL = 0;
   private static final int WINDOW = 1;
   private static final int DOOR = 2;
   private static final int NORTH = 0;
   private static final int WEST = 1;
   private static final int SOUTH = 2;
   private static final int EAST = 3;

   private static final AtomicLong CHECKS = new AtomicLong();
   private static final AtomicLong MISMATCHES = new AtomicLong();
   private static final AtomicLong FAST_NS = new AtomicLong();
   private static final AtomicLong GAME_NS = new AtomicLong();
   private static volatile long nextLogMs;

   /** IsoGridSquare.isBlockedTo(other) of 42.21, for a, b that may be on any thread the caller may already use. */
   public static boolean isBlockedTo(IsoGridSquare a, IsoGridSquare b) {
      if (Config.DEV_EDGE_FAST_CHECK) {
         long t0 = System.nanoTime();
         boolean fast = b == null || b == a ? a.isBlockedTo(b) : compute(a, b);
         long t1 = System.nanoTime();
         boolean ref = a.isBlockedTo(b);
         long t2 = System.nanoTime();
         FAST_NS.addAndGet(t1 - t0);
         GAME_NS.addAndGet(t2 - t1);
         check(fast, ref);
         return fast;
      }
      return b == null || b == a ? a.isBlockedTo(b) : compute(a, b);
   }

   private static boolean compute(IsoGridSquare a, IsoGridSquare b) {
      IsoCell cell = IsoWorld.instance.currentCell;
      return edgeTo(WALL, a, b, cell) || edgeTo(WINDOW, a, b, cell) || edgeTo(DOOR, a, b, cell) || a.isStairBlockedTo(b);
   }

   /** isEdgeElementTo: east of, west of, south of, north of, each with its two edges, in the game's order. */
   private static boolean edgeTo(int kind, IsoGridSquare a, IsoGridSquare b, IsoCell cell) {
      return a.x > b.x && (edge(kind, a, WEST, cell) || edge(kind, b, EAST, cell))
         || a.x < b.x && (edge(kind, b, WEST, cell) || edge(kind, a, EAST, cell))
         || a.y > b.y && (edge(kind, a, NORTH, cell) || edge(kind, b, SOUTH, cell))
         || a.y < b.y && (edge(kind, b, NORTH, cell) || edge(kind, a, SOUTH, cell));
   }

   /** isEdgeElement: north / west are the square's own, south / east its neighbour's north / west. */
   private static boolean edge(int kind, IsoGridSquare sq, int along, IsoCell cell) {
      switch (along) {
         case NORTH:
            return facing(kind, sq, true);
         case WEST:
            return facing(kind, sq, false);
         case SOUTH: {
            IsoGridSquare s = cell.getGridSquare(sq.x, sq.y + 1, sq.z);
            return s != null && facing(kind, s, true);
         }
         default: {
            IsoGridSquare e = cell.getGridSquare(sq.x + 1, sq.y, sq.z);
            return e != null && facing(kind, e, false);
         }
      }
   }

   /** isWall / hasBlockedWindow / hasBlockedDoor for one facing (north = NORTH_SOUTH, else EAST_WEST). */
   private static boolean facing(int kind, IsoGridSquare sq, boolean north) {
      if (kind == WALL) {
         PropertyContainer p = sq.getProperties();
         return north ? p.has(IsoFlagType.collideN) && !p.has(IsoFlagType.windowN) : p.has(IsoFlagType.collideW) && !p.has(IsoFlagType.windowW);
      }
      GridSquareEdgeFacingDirection dir = north ? GridSquareEdgeFacingDirection.NORTH_SOUTH : GridSquareEdgeFacingDirection.EAST_WEST;
      java.util.List<IsoObject> objects = sq.getObjects();
      for (int i = 0; i < objects.size(); i++) {
         IsoObject o = objects.get(i);
         if (kind == WINDOW) {
            if (o instanceof IsoWindow w && w.isBlocked(dir)) {
               return true;
            }
         } else if (o instanceof IsoDoor d && d.isBlocked(dir) || o instanceof IsoThumpable t && t.isBlockedDoor(dir)) {
            return true;
         }
      }
      return false;
   }

   private static void check(boolean fast, boolean ref) {
      long n = CHECKS.incrementAndGet();
      if (ref != fast) {
         MISMATCHES.incrementAndGet();
      }
      long now = System.currentTimeMillis();
      if (now >= nextLogMs) {
         nextLogMs = now + 10000L;
         Log.info("edgeTestFast check: " + n + " isBlockedTo calls, " + MISMATCHES.get() + " differ from the game's; ns per call: written out "
            + FAST_NS.get() / Math.max(1, n) + ", the game's " + GAME_NS.get() / Math.max(1, n));
      }
   }

   public static String counters() {
      return "edgeFastChecks=" + CHECKS.get() + " edgeFastMismatches=" + MISMATCHES.get();
   }
}
