package pzopt;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;

/**
 * "Is there any vehicle in the chunks round this character?" from one grid per frame ({@code nearVehicleGrid}, 2026-10-13).
 *
 * <p>Stock {@code IsoGameCharacter.getNearVehicle} (every zombie, every update) and {@code isNearSirenVehicle} look up 16
 * chunks round the character through {@code IsoCell.getChunk} and walk each chunk's {@code vehicles} list; downtown
 * almost every one of those lists is empty, so the 16 lookups are the whole cost (2.4 % of the game thread on the
 * Louisville horde, 2,678 zombies). This class keeps, per chunk-map slot, whether the chunk there holds a vehicle, as a
 * summed-area table: one rectangle query tells that every chunk of the window is empty, which is exactly when stock's loop
 * returns null / false. Any other answer ("maybe") runs the stock loop, so the result never differs.
 *
 * <p>The grid is valid for one frame ({@link FrameTick}) and only while nothing that feeds it changed: every size change
 * of a chunk's vehicle list ({@link VehicleList}) and every change of the chunk map (a chunk set or cleared, the buffers
 * swapped, the origin moved: hooks in the {@code IsoChunkMap} override) bumps {@link #changed()}, and the next query
 * rebuilds. Single player only (one chunk map, as {@code IsoCell.getChunk} reads it with one player); a server, split
 * screen or an ignored map takes the stock loop. Rig {@code devNearVehicleCheck}: every Nth "empty" answer also runs the
 * stock loop and counts a difference.
 */
public final class VehicleNear {
   private VehicleNear() {
   }

   private static final AtomicInteger MODS = new AtomicInteger();
   private static final boolean KEY = Config.NEAR_VEHICLE_GRID;
   private static final boolean CHECK = Config.DEV_NEAR_VEHICLE_CHECK;

   /** The change counter (separateAsync, f8-zomb2: a chunk-grid or vehicle-list change under the asynchronous separation). */
   public static int mods() {
      return MODS.get();
   }

   /** Something a grid was built from changed: a chunk's vehicle list or the chunk map. Any thread. */
   public static void changed() {
      MODS.incrementAndGet();
   }

   private static final class Grid {
      final IsoChunkMap map;
      final int w;
      final int originX;
      final int originY;
      final int frame;
      final int mods;
      final int[] sat; // (w + 1) x (w + 1) summed-area table of "the slot's chunk holds a vehicle"

      Grid(IsoChunkMap map, int w, int originX, int originY, int frame, int mods, int[] sat) {
         this.map = map;
         this.w = w;
         this.originX = originX;
         this.originY = originY;
         this.frame = frame;
         this.mods = mods;
         this.sat = sat;
      }
   }

   private static volatile Grid grid;
   private static final Object LOCK = new Object();
   private static final LongAdder EMPTY = new LongAdder();
   private static final LongAdder MAYBE = new LongAdder();
   private static final LongAdder STOCK = new LongAdder();
   private static long rebuilds;
   private static final AtomicInteger checkTick = new AtomicInteger();
   private static final LongAdder CHECKED = new LongAdder();
   private static final LongAdder DIFFS = new LongAdder();

   private static boolean enabled() {
      return KEY && Overrides.enabled() && !GameServer.server && IsoPlayer.numPlayers == 1 && GtAb.on(GtAb.NEAR_VEHICLE);
   }

   /**
    * True when every chunk with world chunk coordinates in [cx0, cx1) x [cy0, cy1) is empty of vehicles as
    * {@code IsoCell.getChunk} would return it: then stock's loop over that window finds nothing. False = unknown, run stock.
    */
   public static boolean none(int cx0, int cy0, int cx1, int cy1) {
      if (!enabled()) {
         STOCK.increment();
         return false;
      }
      Grid g = current();
      if (g == null) {
         STOCK.increment();
         return false;
      }
      int x0 = Math.max(0, cx0 - g.originX), y0 = Math.max(0, cy0 - g.originY);
      int x1 = Math.min(g.w, cx1 - g.originX), y1 = Math.min(g.w, cy1 - g.originY);
      if (x0 >= x1 || y0 >= y1) {
         EMPTY.increment(); // the whole window lies outside the chunk map: getChunk returns null for every slot
         return true;
      }
      int s1 = g.w + 1;
      int[] sat = g.sat;
      int sum = sat[y1 * s1 + x1] - sat[y0 * s1 + x1] - sat[y1 * s1 + x0] + sat[y0 * s1 + x0];
      if (sum == 0) {
         EMPTY.increment();
         return true;
      }
      MAYBE.increment();
      return false;
   }

   /** devNearVehicleCheck: true when this "empty" answer should be checked against the stock loop. */
   public static boolean checkThis() {
      return CHECK && (checkTick.incrementAndGet() & 15) == 0;
   }

   /** devNearVehicleCheck: the stock loop's answer for a window {@link #none} called empty. */
   public static void checked(boolean stockFoundOne, int cx0, int cy0) {
      CHECKED.increment();
      if (stockFoundOne) {
         DIFFS.increment();
         if (DIFFS.sum() <= 5) {
            Log.info("nearVehicleGrid check: stock found a vehicle in a window the grid called empty, chunks from " + cx0 + "," + cy0);
         }
      }
   }

   private static Grid current() {
      IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
      if (cell == null || cell.chunkMap == null) {
         return null;
      }
      IsoChunkMap map = cell.chunkMap[0];
      if (map == null || map.ignore) {
         return null;
      }
      int frame = FrameTick.frame();
      Grid g = grid;
      if (g != null && g.frame == frame && g.mods == MODS.get() && g.map == map) {
         return g;
      }
      synchronized (LOCK) {
         g = grid;
         int mods = MODS.get();
         if (g != null && g.frame == frame && g.mods == mods && g.map == map) {
            return g;
         }
         IsoChunk[] chunks = map.getChunks();
         int w = IsoChunkMap.chunkGridWidth;
         if (chunks == null || chunks.length < w * w) {
            return null;
         }
         int s1 = w + 1;
         int[] sat = new int[s1 * s1];
         for (int y = 0; y < w; y++) {
            int row = 0;
            for (int x = 0; x < w; x++) {
               IsoChunk c = chunks[w * y + x];
               if (c != null && !c.vehicles.isEmpty()) {
                  row++;
               }
               sat[(y + 1) * s1 + x + 1] = sat[y * s1 + x + 1] + row;
            }
         }
         rebuilds++;
         g = new Grid(map, w, map.getWorldXMin(), map.getWorldYMin(), frame, mods, sat);
         grid = g;
         return g;
      }
   }

   public static String describe() {
      return "near vehicle grid: empty=" + EMPTY.sum() + " maybe=" + MAYBE.sum() + " stock=" + STOCK.sum() + " rebuilds=" + rebuilds
         + (CHECK ? " checked=" + CHECKED.sum() + " diffs=" + DIFFS.sum() : "");
   }
}
