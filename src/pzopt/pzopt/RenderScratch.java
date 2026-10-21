package pzopt;

import zombie.iso.sprite.shapers.WallShaperN;
import zombie.iso.sprite.shapers.WallShaperSliceN;
import zombie.iso.sprite.shapers.WallShaperSliceW;
import zombie.iso.sprite.shapers.WallShaperW;
import zombie.iso.sprite.shapers.WallShaperWhole;
import zombie.tileDepth.CutawayAttachedModifier;
import zombie.tileDepth.TileDepthModifier;
import zombie.tileDepth.TileSeamModifier;

/**
 * tileRecordParallel: the draw modifiers the tile code fills and then hands to a sprite draw as its texture-draw
 * modifier (the depth / seam / cutaway modifiers, the wall shapers with their corner colours). Stock keeps one of each
 * ({@code X.instance}) and compares them by identity along the way; a thread recording a tile draw unit gets its own
 * set, and every overridden class asks for them here, so the identity tests hold on each thread. Any other thread gets
 * the stock instances.
 */
public final class RenderScratch {
   final TileDepthModifier tdm = new TileDepthModifier();
   final TileSeamModifier tsm = new TileSeamModifier();
   final CutawayAttachedModifier cam = new CutawayAttachedModifier();
   final WallShaperWhole wsWhole = new WallShaperWhole();
   final WallShaperN wsN = new WallShaperN();
   final WallShaperW wsW = new WallShaperW();
   final WallShaperSliceN wsSliceN = new WallShaperSliceN();
   final WallShaperSliceW wsSliceW = new WallShaperSliceW();
   // bakeRecordParallel: the floor pass's shapers (vertex colours, shore, tint: filled per floor, then handed to its draw)
   final zombie.iso.sprite.shapers.FloorShaperAttachedSprites fsAttached = new zombie.iso.sprite.shapers.FloorShaperAttachedSprites();
   final zombie.iso.sprite.shapers.FloorShaperDeDiamond fsDeDiamond = new zombie.iso.sprite.shapers.FloorShaperDeDiamond();
   final zombie.iso.sprite.shapers.FloorShaperDiamond fsDiamond = new zombie.iso.sprite.shapers.FloorShaperDiamond();

   public static zombie.iso.sprite.shapers.FloorShaperAttachedSprites fsAttached() {
      RenderScratch s = current();
      return s == null ? zombie.iso.sprite.shapers.FloorShaperAttachedSprites.instance : s.fsAttached;
   }

   public static zombie.iso.sprite.shapers.FloorShaperDeDiamond fsDeDiamond() {
      RenderScratch s = current();
      return s == null ? zombie.iso.sprite.shapers.FloorShaperDeDiamond.instance : s.fsDeDiamond;
   }

   public static zombie.iso.sprite.shapers.FloorShaperDiamond fsDiamond() {
      RenderScratch s = current();
      return s == null ? zombie.iso.sprite.shapers.FloorShaperDiamond.instance : s.fsDiamond;
   }

   private static RenderScratch current() {
      if (!DrawRecorder.recording) {
         return null;
      }
      DrawRecorder r = DrawRecorder.current();
      if (r == null) {
         return null;
      }
      if (r.renderScratch == null) {
         r.renderScratch = new RenderScratch();
      }
      return r.renderScratch;
   }

   public static TileDepthModifier tdm() {
      RenderScratch s = current();
      return s == null ? TileDepthModifier.instance : s.tdm;
   }

   public static TileSeamModifier tsm() {
      RenderScratch s = current();
      return s == null ? TileSeamModifier.instance : s.tsm;
   }

   public static CutawayAttachedModifier cam() {
      RenderScratch s = current();
      return s == null ? CutawayAttachedModifier.instance : s.cam;
   }

   public static WallShaperWhole wsWhole() {
      RenderScratch s = current();
      return s == null ? WallShaperWhole.instance : s.wsWhole;
   }

   public static WallShaperN wsN() {
      RenderScratch s = current();
      return s == null ? WallShaperN.instance : s.wsN;
   }

   public static WallShaperW wsW() {
      RenderScratch s = current();
      return s == null ? WallShaperW.instance : s.wsW;
   }

   public static WallShaperSliceN wsSliceN() {
      RenderScratch s = current();
      return s == null ? WallShaperSliceN.instance : s.wsSliceN;
   }

   public static WallShaperSliceW wsSliceW() {
      RenderScratch s = current();
      return s == null ? WallShaperSliceW.instance : s.wsSliceW;
   }
}
