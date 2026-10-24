package pzopt;

import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.IsoTree;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteManager;

/**
 * Harness rig ground_field=R[,layout] (ground and grass, 2026-10-24, the "ground without the grid" verdicts): the open level-0
 * squares within R of the route start (outside, no building, no wall / solid object, not water) get bare natural ground, as
 * the map would lay it: zones of sand, dirt, light / medium / dark grass from a low-frequency world noise (layout "zones",
 * the default) or one material ("grass", "dirt", "sand", "dark"), each square one of the material's four whole variants
 * (+0, +5, +6, +7) picked by a hash, trees and plants removed; where a zone meets a lower one the higher material's
 * blends_natural edge goes on the lower square's floor as an attached sprite (a half toward the corner both neighbours share,
 * else a strip along the edge), so the transitions use the game's own pieces. Bench save copies only.
 */
final class GroundFieldRig {
   private GroundFieldRig() {
   }

   // blends_natural_01 rows by material, ordered low to high (a higher material's edge lies over a lower square)
   private static final int[] BASE = {0, 64, 16, 32, 48}; // sand, dirt, light grass, medium grass, dark grass
   private static final int[] WHOLE = {0, 5, 6, 7};

   static void apply(IsoCell cell, int px, int py, String spec) {
      String[] t = spec.split(",");
      int r = Integer.parseInt(t[0].trim());
      String layout = t.length > 1 ? t[1].trim() : "zones";
      int fixed = switch (layout) {
         case "sand" -> 0;
         case "dirt" -> 1;
         case "grass" -> 3;
         case "dark" -> 4;
         default -> -1;
      };
      int n = 2 * r + 1;
      int[] zone = new int[n * n];
      boolean[] open = new boolean[n * n];
      int placed = 0, edges = 0, trees = 0;
      for (int j = 0; j < n; j++) {
         for (int i = 0; i < n; i++) {
            int x = px - r + i, y = py - r + j;
            IsoGridSquare sq = cell.getGridSquare(x, y, 0);
            open[j * n + i] = sq != null && sq.getFloor() != null && sq.isOutside() && sq.getBuilding() == null && !sq.isWaterSquare()
               && !sq.getProperties().has(IsoFlagType.solid) && !sq.getProperties().has(IsoFlagType.solidtrans)
               && !sq.getProperties().has(IsoFlagType.collideW) && !sq.getProperties().has(IsoFlagType.collideN);
            zone[j * n + i] = fixed >= 0 ? fixed : zoneAt(x, y);
         }
      }
      for (int j = 0; j < n; j++) {
         for (int i = 0; i < n; i++) {
            if (!open[j * n + i]) {
               continue;
            }
            int x = px - r + i, y = py - r + j;
            IsoGridSquare sq = cell.getGridSquare(x, y, 0);
            for (int k = sq.getObjects().size() - 1; k >= 0; k--) {
               IsoObject o = sq.getObjects().get(k);
               if (o instanceof IsoTree) {
                  sq.transmitRemoveItemFromSquare(o);
                  trees++;
               }
            }
            int z = zone[j * n + i];
            IsoObject f = sq.addFloor("blends_natural_01_" + (BASE[z] + WHOLE[hash(x, y, 7) & 3]));
            placed++;
            if (f == null || fixed >= 0) {
               continue;
            }
            // the higher neighbour zones' edges: -y, -x, +x, +y neighbours
            int zn = j > 0 ? zone[(j - 1) * n + i] : z, zw = i > 0 ? zone[j * n + i - 1] : z;
            int ze = i < n - 1 ? zone[j * n + i + 1] : z, zs = j < n - 1 ? zone[(j + 1) * n + i] : z;
            for (int b = z + 1; b < BASE.length; b++) {
               boolean bn = zn == b, bw = zw == b, be = ze == b, bs = zs == b;
               int off = -1;
               if (bn && bw) {
                  off = 1; // half toward the north corner
               } else if (be && bs) {
                  off = 2; // south
               } else if (bw && bs) {
                  off = 3; // west
               } else if (bn && be) {
                  off = 4; // east
               } else if (bn) {
                  off = 8; // strip along the -y edge
               } else if (bw) {
                  off = 9;
               } else if (be) {
                  off = 10;
               } else if (bs) {
                  off = 11;
               }
               if (off >= 8 && (hash(x, y, 11 + b) & 1) != 0) {
                  off += 4; // the strips' second set
               }
               if (off > 0) {
                  IsoSprite s = IsoSpriteManager.instance.getSprite("blends_natural_01_" + (BASE[b] + off));
                  if (s != null) {
                     f.addAttachedAnimSprite(s);
                     edges++;
                  }
               }
            }
         }
      }
      int rb = GroundBake.rebakeAll("ground_field rig", false);
      Log.info("harness: ground_field " + layout + " r=" + r + " around " + px + "," + py + ": " + placed + " floors, " + edges + " edges, "
         + trees + " trees removed, " + rb + " chunk textures re-bake");
   }

   /** The zone of a square: value noise over 9-square cells, quantised into the five materials. */
   private static int zoneAt(int x, int y) {
      double v = noise(x / 9.0, y / 9.0, 1) * 0.7 + noise(x / 4.0, y / 4.0, 2) * 0.3;
      return Math.max(0, Math.min(4, (int)(v * 5.0)));
   }

   private static double noise(double x, double y, int s) {
      int xi = (int)Math.floor(x), yi = (int)Math.floor(y);
      double fx = x - xi, fy = y - yi;
      fx = fx * fx * (3 - 2 * fx);
      fy = fy * fy * (3 - 2 * fy);
      double a = (hash(xi, yi, s) & 1023) / 1023.0, b = (hash(xi + 1, yi, s) & 1023) / 1023.0;
      double c = (hash(xi, yi + 1, s) & 1023) / 1023.0, d = (hash(xi + 1, yi + 1, s) & 1023) / 1023.0;
      return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
   }

   private static int hash(int x, int y, int s) {
      int h = x * 374761393 + y * 668265263 + s * 2147483647;
      h = (h ^ h >>> 13) * 1274126177;
      return h ^ h >>> 16;
   }
}
