package pzopt;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * mapCellFast (2026-11-22): the world map and minimap ask {@code WorldMapVisited.isCellVisible} for every cell in view on
 * every frame (WorldMapRenderer.Drawer.filterFeatures, render thread). Stock answers through hasFlags: up to 100
 * getFlags calls per cell (a 10 x 10 block of 32-square units), each one re-deriving the row span through
 * IsoWorld.getMetaGrid() and doing a divide and a modulo. On a large map zoomed out this was 40 % of the render thread's
 * samples (5,307 of 13,067 in a 15-minute JFR, M1 / Asahi, a 160 x 160-cell modded map). Here the same question is
 * answered from the packed flag bytes: a cell's block row is at most 10 units = 20 bits, so one little-endian int read
 * and a mask per row, with the same early exit. The array is read live every call: nothing is cached, nothing goes
 * stale. Measured against the jar's own method on a 160 x 160-cell map: 2.82 to 0.43 ms for every cell, 6.6x, the same
 * answer for all 26,896 cells (in range and out). devMapCellCheck runs stock beside it and logs any difference.
 */
public final class MapCells {
   private static final VarHandle INT_LE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
   private static long checks;
   private static long mismatches;

   private MapCells() {
   }

   /** The answer of stock {@code hasFlags(cellX*256-1, cellY*256-1, (cellX+1)*256+1, (cellY+1)*256+1, 3, true)}. */
   public static boolean cellVisible(byte[] visited, int widthInCells, int heightInCells, int minX, int minY, int cellX, int cellY) {
      int xMin = cellX * 256 - 1 - minX * 256;
      int yMin = cellY * 256 - 1 - minY * 256;
      int xMax = (cellX + 1) * 256 + 1 - minX * 256;
      int yMax = (cellY + 1) * 256 + 1 - minY * 256;
      int wq = widthInCells * 256 - 1;
      int hq = heightInCells * 256 - 1;
      xMin = clamp(xMin, 0, wq);
      yMin = clamp(yMin, 0, hq);
      xMax = clamp(xMax, 0, wq);
      yMax = clamp(yMax, 0, hq);
      if (xMin == xMax || yMin == yMax) {
         return false;
      }
      int x0 = xMin / 32;
      int x1 = xMax / 32;
      int y0 = yMin / 32;
      int y1 = yMax / 32;
      if (xMax % 32 == 0) {
         x1--;
      }
      if (yMax % 32 == 0) {
         y1--;
      }
      if (x1 < x0 || y1 < y0) {
         return false;
      }
      int span = widthInCells * 8 / 4; // stock getSpan(): bytes per unit row, 4 units (2 bits each) per byte
      int b0 = x0 >> 2;
      int lo = (x0 - (b0 << 2)) * 2;
      int nbits = (x1 - x0 + 1) * 2;
      if (lo + nbits <= 32 && y1 * span + b0 + 4 <= visited.length) {
         int mask = (nbits == 32 ? -1 : (1 << nbits) - 1) << lo;
         for (int y = y0; y <= y1; y++) {
            if (((int)INT_LE.get(visited, y * span + b0) & mask) != 0) {
               return true;
            }
         }
         return false;
      }
      int b1 = x1 >> 2; // the array's last bytes: masked byte reads
      int firstMask = 0xFF << ((x0 & 3) * 2) & 0xFF;
      int lastMask = 0xFF >>> ((3 - (x1 & 3)) * 2);
      for (int y = y0; y <= y1; y++) {
         int row = y * span;
         if (b0 == b1) {
            if ((visited[row + b0] & firstMask & lastMask) != 0) {
               return true;
            }
         } else {
            if ((visited[row + b0] & firstMask) != 0) {
               return true;
            }
            for (int b = b0 + 1; b < b1; b++) {
               if (visited[row + b] != 0) {
                  return true;
               }
            }
            if ((visited[row + b1] & lastMask) != 0) {
               return true;
            }
         }
      }
      return false;
   }

   /** devMapCellCheck: the stock answer computed beside ours. */
   public static void check(boolean fast, boolean stock, int cellX, int cellY) {
      checks++;
      if (fast != stock) {
         mismatches++;
         Log.warn("mapCellFast check: cell " + cellX + "," + cellY + " fast=" + fast + " stock=" + stock + ", " + mismatches + " of " + checks + " differ");
      } else if ((checks & 0xFFFFF) == 0) {
         Log.info("mapCellFast check: " + checks + " cells checked, " + mismatches + " differed");
      }
   }

   private static int clamp(int v, int lo, int hi) {
      return v < lo ? lo : v > hi ? hi : v;
   }
}
