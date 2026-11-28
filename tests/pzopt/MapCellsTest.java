package pzopt;

import java.util.Random;

/** mapCellFast: MapCells.cellVisible gives stock isCellVisible's answer for every cell, in range and out, on random maps. */
public class MapCellsTest {
   public static void main(String[] args) {
      Random rnd = new Random(42);
      int cells = 0;
      for (int trial = 0; trial < 600; trial++) {
         int w = 1 + rnd.nextInt(trial < 80 ? 3 : 48);
         int h = 1 + rnd.nextInt(trial < 80 ? 3 : 48);
         int minX = rnd.nextInt(200) - 20;
         int minY = rnd.nextInt(200) - 20;
         byte[] vis = new byte[w * 8 / 4 * h * 8];
         switch (trial % 4) {
            case 1 -> { for (int i = 0; i < vis.length; i++) vis[i] = (byte)(rnd.nextInt(50) == 0 ? rnd.nextInt(256) : 0); }
            case 2 -> rnd.nextBytes(vis);
            case 3 -> { for (int k = 0, n = 1 + rnd.nextInt(6); k < n; k++) vis[rnd.nextInt(vis.length)] = (byte)(1 << rnd.nextInt(8)); }
            default -> { }
         }
         for (int cy = minY - 2; cy < minY + h + 2; cy++) {
            for (int cx = minX - 2; cx < minX + w + 2; cx++) {
               boolean fast = MapCells.cellVisible(vis, w, h, minX, minY, cx, cy);
               boolean stock = stockCellVisible(vis, w, h, minX, minY, cx, cy);
               Check.check(fast == stock, "trial " + trial + " " + w + "x" + h + " cell " + cx + "," + cy + ": fast " + fast + ", stock " + stock);
               cells++;
            }
         }
      }
      System.out.println("MapCellsTest: " + cells + " cells, all equal to stock");
   }

   /** Build 42.22 WorldMapVisited.isCellVisible -> hasFlags(..., 3, true) -> getFlags, with getSpan / getWidthInCells inlined. */
   static boolean stockCellVisible(byte[] visited, int widthInCells, int heightInCells, int minX, int minY, int cellX, int cellY) {
      int xMin = cellX * 256 - 1, yMin = cellY * 256 - 1, xMax = (cellX + 1) * 256 + 1, yMax = (cellY + 1) * 256 + 1;
      int flags = 3;
      boolean bAnyOK = true;
      xMin -= minX * 256;
      yMin -= minY * 256;
      xMax -= minX * 256;
      yMax -= minY * 256;
      xMin = Math.max(0, Math.min(xMin, widthInCells * 256 - 1));
      yMin = Math.max(0, Math.min(yMin, heightInCells * 256 - 1));
      xMax = Math.max(0, Math.min(xMax, widthInCells * 256 - 1));
      yMax = Math.max(0, Math.min(yMax, heightInCells * 256 - 1));
      if (xMin != xMax && yMin != yMax) {
         int xMinUnit = xMin / 32, xMaxUnit = xMax / 32, yMinUnit = yMin / 32, yMaxUnit = yMax / 32;
         if (xMax % 32 == 0) xMaxUnit--;
         if (yMax % 32 == 0) yMaxUnit--;
         int span = widthInCells * 8 / 4;
         for (int y = yMinUnit; y <= yMaxUnit; y++) {
            for (int x = xMinUnit; x <= xMaxUnit; x++) {
               int b = visited[x / 4 + y * span];
               int it = b >> x % 4 * 2 & 3;
               if (bAnyOK) {
                  if ((it & flags) != 0) return true;
               } else if ((it & flags) != flags) {
                  return false;
               }
            }
         }
         return !bAnyOK;
      }
      return false;
   }
}
