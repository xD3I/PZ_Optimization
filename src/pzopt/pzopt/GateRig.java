package pzopt;

import java.util.ArrayList;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoObjectType;
import zombie.core.properties.IsoPropertyType;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.objects.IsoDoor;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteManager;

/**
 * Harness rig {@code gate=auto|place} (2026-10-11, Workshop: "Open gates - AO is still there as it's closed", a wired
 * double gate's ambient occlusion staying where the closed gate stood until a zoom out and in). At the route setup the
 * rig takes the closed double door (IsoDoor with DoubleDoor 1..4) of the loaded grid whose four leaves cross a chunk
 * border, chain-link gates first ({@code auto}); without one, or with {@code place}, it builds a chain-link double gate
 * itself (gate_sprite, default the chain-link fixtures_doors_fences_01_66) on the first row of a chunk (y % 8 == 0) whose leaves start at x % 8 == 6, so two leaves stand in each of two
 * chunks and the gate's AO reaches the chunk to the north. The route starts {@code gate_dist} (3) squares south of it.
 * From the route start, every {@code gate_toggle} seconds (3) one event in turn: the gate toggled (IsoDoor.toggleDoubleDoor,
 * stock's own call), then every loaded chunk baked again (the zoom out-and-in the report used as a workaround), then the
 * gate toggled back, a bake, and so on; {@code harness: gate toggled open|closed epoch_ms=} / {@code harness: gate rebake
 * epoch_ms=} lines. With a devCapture sequence, {@code harness/gate-ao.py <run>} compares the last frame before each
 * re-bake with the last frame before the next toggle (same gate state; any difference is a stale bake).
 */
final class GateRig {
   private static IsoObject leaf; // one leaf of the gate: toggleDoubleDoor finds the other three from it
   private static long periodNs, nextNs;
   private static int step;

   private GateRig() {
   }

   /** Harness route setup (game thread): picks or builds the gate; returns the player's start {x, y} or null. */
   static float[] setup(IsoPlayer p, String spec) {
      String mode = spec.trim().toLowerCase(java.util.Locale.ROOT);
      periodNs = (long)(Float.parseFloat(HarnessFlags.get("gate_toggle", "3").trim()) * 1e9);
      int dist = Integer.parseInt(HarnessFlags.get("gate_dist", "3").trim());
      IsoCell cell = IsoWorld.instance.currentCell;
      int[] at = "place".equals(mode) ? null : find(cell, p);
      if (at == null) {
         at = place(cell, p);
      }
      if (at == null) {
         Log.info("harness: gate: no gate found or placed");
         return null;
      }
      boolean north = at[2] == 1;
      // the start: south of a north gate's middle, east of a west gate's middle (the camera looks north-west)
      return north ? new float[]{at[0] + 2.0F, at[1] + dist + 0.5F} : new float[]{at[0] + dist + 0.5F, at[1] - 1.0F};
   }

   /** The closed double doors of the loaded grid (ground floor), the best one's {x, y, north} (leaf 1's square). */
   private static int[] find(IsoCell cell, IsoPlayer p) {
      int px = (int)p.getX(), py = (int)p.getY(), r = 100;
      int bestScore = -1;
      int[] best = null;
      IsoObject bestLeaf = null;
      int listed = 0;
      for (int y = py - r; y <= py + r; y++) {
         for (int x = px - r; x <= px + r; x++) {
            IsoGridSquare sq = cell.getGridSquare(x, y, 0);
            if (sq == null) {
               continue;
            }
            for (int i = 0; i < sq.getSpecialObjects().size(); i++) {
               IsoObject o = sq.getSpecialObjects().get(i);
               if (!(o instanceof IsoDoor d) || d.isOpen() || IsoDoor.getDoubleDoorIndex(o) != 1) {
                  continue;
               }
               boolean north = d.isNorth();
               String name = o.getSprite() == null ? "?" : o.getSprite().getName();
               boolean chain = name != null && name.startsWith("fencing");
               boolean crosses = north ? (x >> 3) != ((x + 3) >> 3) : (y >> 3) != ((y - 3) >> 3);
               boolean edge = north ? (y & 7) == 0 : (x & 7) == 0; // AO onto the chunk north / west of it
               int score = (crosses ? 4 : 0) + (edge ? 2 : 0) + (chain ? 1 : 0);
               if (listed++ < 30) {
                  Log.info("harness: gate: candidate " + name + " at " + x + "," + y + (north ? " north" : " west") + " crosses=" + crosses + " edge=" + edge);
               }
               if (score > bestScore) {
                  bestScore = score;
                  best = new int[]{x, y, north ? 1 : 0};
                  bestLeaf = o;
               }
            }
         }
      }
      Log.info("harness: gate: " + listed + " closed double doors within " + r + " squares" + (best == null ? "" : ", using " + best[0] + "," + best[1] + " score " + bestScore));
      if (bestScore < 2) {
         return null; // one that touches no chunk border shows nothing: build one instead
      }
      leaf = bestLeaf;
      return best;
   }

   /** A chain-link double gate (north wall) built on a chunk's first row near the player; {x, y, 1} or null. */
   private static int[] place(IsoCell cell, IsoPlayer p) {
      // the sprites: a gate's north leaves 1..4 are its base k, k + 1, k + 8, k + 9 (fixtures_doors_fences_01: one gate per
      // 16 tiles from _34; _66 is the chain-link one of the report)
      String base = HarnessFlags.get("gate_sprite", "fixtures_doors_fences_01_66").trim();
      String sheet = base.substring(0, base.lastIndexOf('_') + 1);
      int k0 = Integer.parseInt(base.substring(base.lastIndexOf('_') + 1));
      IsoSprite[] use = new IsoSprite[4];
      int[] off = {0, 1, 8, 9};
      for (int i = 0; i < 4; i++) {
         use[i] = IsoSpriteManager.instance.namedMap.get(sheet + (k0 + off[i]));
         String dd = use[i] == null || use[i].getProperties() == null ? null : use[i].getProperties().get(IsoPropertyType.DOUBLE_DOOR);
         if (use[i] == null || use[i].getType() != IsoObjectType.doorN || !String.valueOf(i + 1).equals(dd)) {
            Log.info("harness: gate: " + sheet + (k0 + off[i]) + " is not north leaf " + (i + 1) + " (DoubleDoor=" + dd + ")");
            return null;
         }
      }
      String useName = base;
      int px = (int)p.getX(), py = (int)p.getY();
      int y = ((py - 6) & ~7) + 8; // the first row of a chunk, a few squares north of the player
      int x = (px & ~7) + 6; // leaves 1, 2 in one chunk, 3, 4 in the next
      ArrayList<IsoGridSquare> squares = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
         IsoGridSquare sq = cell.getGridSquare(x + i, y, 0);
         if (sq == null) {
            Log.info("harness: gate: no square at " + (x + i) + "," + y);
            return null;
         }
         squares.add(sq);
      }
      for (int i = 0; i < 4; i++) {
         IsoDoor d = new IsoDoor(cell, squares.get(i), use[i], true);
         d.setLockedByKey(false);
         d.setLocked(false);
         squares.get(i).AddSpecialObject(d);
         if (i == 0) {
            leaf = d;
         }
      }
      Log.info("harness: gate: placed " + useName + " at " + x + "," + y + " (north wall, leaves " + x + ".." + (x + 3) + ", chunks " + (x >> 3) + "/" + ((x + 3) >> 3) + ", row " + (y & 7) + " of chunk " + (y >> 3) + ")");
      return new int[]{x, y, 1};
   }

   /** Scene.routeStart. */
   static void routeStart(long nowNs) {
      nextNs = nowNs + periodNs;
   }

   private static long traceUntilNs;
   private static int traceFrame;

   /** Scene.tick (game thread). */
   static void tick(IsoPlayer p, long nowNs) {
      if (leaf != null && nowNs < traceUntilNs && traceFrame++ % 6 == 0) {
         StringBuilder sb = new StringBuilder("harness: gate leaves:");
         for (int i = 1; i <= 4; i++) {
            IsoObject o = IsoDoor.getDoubleDoorObject(leaf, i);
            sb.append(' ').append(i).append('=');
            if (o == null || o.getSquare() == null) {
               sb.append("none");
            } else {
               sb.append(o.getSquare().getX()).append(',').append(o.getSquare().getY()).append(" anim=").append(o.isAnimating())
                  .append(" alpha=").append(String.format(java.util.Locale.ROOT, "%.2f/%.2f", o.getAlpha(0), o.getTargetAlpha(0)));
            }
         }
         Log.info(sb.append(" epoch_ms=").append(System.currentTimeMillis()).toString());
      }
      if (leaf == null || nextNs == 0L || nowNs < nextNs) {
         return;
      }
      nextNs += periodNs;
      if ((step++ & 1) == 0) {
         IsoDoor.toggleDoubleDoor(leaf, false);
         traceUntilNs = nowNs + 1_500_000_000L;
         traceFrame = 0;
         boolean open = leaf instanceof IsoDoor d && d.isOpen();
         Log.info("harness: gate toggled " + (open ? "open" : "closed") + " epoch_ms=" + System.currentTimeMillis());
      } else {
         rebakeAll();
         Log.info("harness: gate rebake epoch_ms=" + System.currentTimeMillis());
      }
   }

   /**
    * Every loaded chunk level of player 0 baked again (what a zoom out and in did for the reporter); gate_rebake=gate /
    * north / south / ring only the gate's chunk row, the row north of it, south of it, or those within one chunk of the gate
    * (which re-bake supplies what the toggle's own bakes miss); gate_rebake_flag=N the dirty flag (default 256).
    */
   private static void rebakeAll() {
      IsoChunkMap cm = IsoWorld.instance.currentCell.getChunkMap(0);
      if (cm == null || cm.getChunks() == null || leaf == null || leaf.getSquare() == null) {
         return;
      }
      String mode = HarnessFlags.get("gate_rebake", "all").trim();
      long flag = Long.parseLong(HarnessFlags.get("gate_rebake_flag", "256").trim());
      int gx = leaf.getSquare().getX() >> 3, gy = leaf.getSquare().getY() >> 3;
      StringBuilder sb = new StringBuilder();
      for (IsoChunk c : cm.getChunks()) {
         if (c == null) {
            continue;
         }
         boolean near = c.wx >= gx - 1 && c.wx <= gx + 1;
         boolean take = switch (mode) {
            case "gate" -> c.wy == gy && c.wx >= gx && c.wx <= gx + 1;
            case "north" -> c.wy == gy - 1 && near;
            case "south" -> c.wy == gy + 1 && near;
            case "ring" -> c.wy >= gy - 1 && c.wy <= gy + 1 && c.wx >= gx - 1 && c.wx <= gx + 2;
            default -> true;
         };
         if (take) {
            c.getRenderLevels(0).invalidateAll(flag);
            if (!"all".equals(mode)) {
               sb.append(' ').append(c.wx).append(',').append(c.wy);
            }
         }
      }
      if (sb.length() > 0) {
         Log.info("harness: gate rebake " + mode + " flag " + flag + ":" + sb);
      }
   }
}
