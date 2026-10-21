package pzopt;

import java.util.concurrent.ConcurrentHashMap;
import zombie.core.textures.Texture;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoPuddlesGeometry;
import zombie.iso.sprite.IsoSprite;

/**
 * floorDecalsPerFrame (2026-10-05, the maintainer: "manholes flicker zoomed out all the way, walking in the rain"). Stock draws
 * Translucent tiles per frame, after the puddles, so a manhole cover or a drain lying in a puddle always covers the water. Baked
 * (translucentTilesInChunkTexture), the cover sits in the chunk texture under the puddle pass, which is depth-tested against it:
 * the cover's object depth lies within the puddle's 1e-4 lift over the floor, and the puddle and the cover traded places in
 * horizontal bands that moved with the camera's sub-pixel offset (runs manhole-*, street_decoration_01_15). A tile lies on the
 * floor when its sprite's opaque rectangle fits in the floor diamond at the bottom of the tile (the diamond is half the tile's
 * width high), with a sixteenth of the width above it for a rim; those stay per frame as in stock on squares that draw
 * puddles (the rest stay baked: +200 tile draws a frame on the spin route when every floor-lying tile went per frame). The
 * sprite answer is cached once its texture's size is known.
 */
public final class FloorDecals {
   private FloorDecals() {
   }

   private static final ConcurrentHashMap<IsoSprite, Boolean> cache = new ConcurrentHashMap<>(); // IsoSprite keeps Object's identity equals / hashCode
   private static final int LOG_MAX = 24;
   private static int logged;

   /**
    * A Translucent tile lying on the floor of a square that draws puddles: the stock bake walk's puddle test (floor, puddle
    * geometry that renders; FBORenderCell caches the square's puddles with it). Indoor litter and pieces on floorless roof
    * squares stay baked: no puddle is drawn over them.
    */
   public static boolean underPuddles(IsoObject object, IsoSprite sprite) {
      if (!onFloor(sprite)) {
         return false;
      }
      IsoGridSquare square = object.square;
      if (square == null || square.getFloor() == null) {
         return false;
      }
      IsoPuddlesGeometry puddles = square.getPuddles();
      return puddles != null && puddles.shouldRender();
   }

   public static boolean onFloor(IsoSprite sprite) {
      Boolean floor = cache.get(sprite);
      if (floor == null) {
         floor = compute(sprite);
         if (floor == null) {
            BakeRecord.unsure(); // bakeRecordParallel: a worker's render-info pass ahead of the bake may not know what the bake's time knows
            return false; // texture size not known yet: baked this time, asked again at the next layer calculation
         }
         cache.put(sprite, floor);
         if (floor && logged < LOG_MAX) {
            logged++;
            Log.info("floor decals: " + sprite.name + " stays per frame (lies on the floor, puddles draw under it as in stock)");
         }
      }
      return floor;
   }

   private static Boolean compute(IsoSprite sprite) {
      Texture t = sprite.getTextureForFrame(0, IsoDirections.N);
      if (t == null) {
         return Boolean.FALSE;
      }
      int w = t.getWidthOrig();
      int h = t.getHeightOrig();
      int th = t.getHeight();
      if (w <= 0 || h <= 0 || th <= 0) {
         return null;
      }
      float top = t.getOffsetY();
      return top >= h - w / 2.0F - w / 16.0F && top + th <= h;
   }
}
