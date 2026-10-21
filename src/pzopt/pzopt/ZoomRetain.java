package pzopt;

import zombie.characters.IsoGameCharacter;
import zombie.core.Core;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunk;
import zombie.iso.IsoUtils;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.fboRenderChunk.FBORenderLevels;

/**
 * Keeps baked chunk-level textures across camera zoom changes (Config.ZOOM_RETAIN, 2026-09-22).
 *
 * Stock frees a chunk level's textures the frame it leaves the screen (FBORenderCell.checkNewlyOnScreenChunks,
 * FBORenderLevels.freeFBOsForLevel) and creates them again, DIRTY_CREATE, when it returns. Zooming in shrinks the
 * screen, so it frees most of what was visible; zooming back out re-bakes every one of those levels, in the frame
 * they return: a 0.25 to 2.5 wheel spin at 5120x2160 is 320 to 410 bakes in one frame, 80 to 375 ms (stock
 * runs zs-out-jump9); a single notch out at wide zoom is a 45 to 51 ms frame (zs-out-wheel).
 *
 * The rule here: a level's normal-scale texture is kept while its chunk lies inside the screen rectangle the widest
 * zoom would show (the same set stock holds at that zoom, so the texture memory stays within stock's own maximum);
 * the high-res texture (used below zoom 0.75) while the chunk lies inside the rectangle of the widest zoom below
 * 0.75. The rectangles are centred on the camera character like the camera itself (PlayerCamera.center), one chunk
 * of margin. A chunk unloading still frees everything (FBORenderLevels.freeChunk).
 */
public final class ZoomRetain {
   private ZoomRetain() {
   }

   private static final float MARGIN_TILES = 8.0F;

   /** The scale-1 rectangle: true when the chunk would be on screen at the widest zoom. */
   public static boolean keepNormal(IsoChunk c, int playerIndex) {
      Core core = Core.getInstance();
      if (core.offscreenBuffer == null) {
         return false;
      }
      return withinZoomRect(c, playerIndex, core.offscreenBuffer.getMaxZoom());
   }

   /** The scale-2 rectangle: true when the chunk would be on screen at the widest zoom that uses high-res textures. */
   public static boolean keepHighRes(IsoChunk c, int playerIndex) {
      Core core = Core.getInstance();
      if (core.offscreenBuffer == null) {
         return false;
      }
      float zoom = core.offscreenBuffer.pzoptWidestZoomBelow(0.75F);
      return zoom > 0.0F && withinZoomRect(c, playerIndex, zoom);
   }

   /**
    * True when the chunk's screen-space box (all its levels, as IsoChunk.IsOnScreen computes it) overlaps the
    * rectangle a camera at `zoom` centred on the camera character would show.
    */
   public static boolean withinZoomRect(IsoChunk c, int playerIndex, float zoom) {
      IsoGameCharacter ch = IsoCamera.getCameraCharacter();
      if (ch == null) {
         return false;
      }
      float cx = IsoUtils.XToScreen(ch.getX(), ch.getY(), 0.0F, 0);
      float cy = IsoUtils.YToScreen(ch.getX(), ch.getY(), IsoCamera.frameState.calculateCameraZ(ch), 0);
      float halfW = IsoCamera.getScreenWidth(playerIndex) * zoom / 2.0F + MARGIN_TILES * 32 * Core.tileScale;
      float halfH = IsoCamera.getScreenHeight(playerIndex) * zoom / 2.0F + MARGIN_TILES * 16 * Core.tileScale;
      int x0 = c.wx * 8;
      int y0 = c.wy * 8;
      // the diamond's extremes: west corner (x0, y0+8), east corner (x0+8, y0), north corner at the top level, south
      // corner at the bottom level
      float minX = IsoUtils.XToScreen(x0, y0 + 8, 0.0F, 0);
      float maxX = IsoUtils.XToScreen(x0 + 8, y0, 0.0F, 0);
      float minY = IsoUtils.YToScreen(x0, y0, c.maxLevel + 1, 0) - FBORenderChunk.JUMBO_L_HEIGHT;
      float maxY = IsoUtils.YToScreen(x0 + 8, y0 + 8, c.minLevel, 0);
      return maxX > cx - halfW && minX < cx + halfW && maxY > cy - halfH && minY < cy + halfH;
   }

   /**
    * Off-screen level: free what is outside its retention rectangle, keep the rest. Returns true when something was kept.
    */
   public static boolean releaseOffScreen(FBORenderLevels renderLevels, IsoChunk c, int level, int playerIndex) {
      long bit = 1L << (renderLevels.getMinLevel(level) + 32);
      c.pzoptZoomReturned[playerIndex] &= ~bit; // off screen: nothing pending for it (a stale bit would wait for a plan forever)
      c.pzoptZoomAllowed[playerIndex] &= ~bit;
      if (!keepNormal(c, playerIndex)) {
         renderLevels.freeFBOsForLevel(level);
         return false;
      }
      kept++;
      if (FBORenderLevels.getTextureScale(0.5F) > 1) { // high-res textures in use at all (a debug option)
         FBORenderChunk highRes = renderLevels.getFBOForLevel(level, 0.5F);
         if (highRes != null && !keepHighRes(c, playerIndex)) {
            renderLevels.freeFBO(highRes);
         }
      }
      return true;
   }

   /** True when the level pair is waiting for the zoom plan (pending, not allowed this frame): its bake preparation waits too. */
   public static boolean waiting(IsoChunk c, int playerIndex, int minLevel) {
      long bit = 1L << (minLevel + 32);
      return (c.pzoptZoomReturned[playerIndex] & bit) != 0L && (c.pzoptZoomAllowed[playerIndex] & bit) == 0L;
   }

   /** Counters for the periodic log line (FBORenderCell.pzoptTlFrame) and the harness zoom trace. */
   public static long kept, returned, rebakes, creations, urgent, placeholders, floodFrames;
   /** Allowed bits still pending after the chunk loop (a level that never reached the gate), dropped by FBORenderCell.pzoptZoomSettle. */
   public static long dropped;
   /** Granted levels whose bake preparation waited with them and ran at the grant, and the square flags it changed (FBORenderCell.pzoptPrepareZoomGranted). */
   public static long latePrepared, lateFlagFixes;
   /** Dirty flags (bit index) of the returned levels that baked outside the zoom budget. */
   public static final int[] urgentFlags = new int[16];
   /** Dev tally: bakes in the frame the zoom changed, and their dirt / origin (Harness zoom trace). */
   public static long changeFrameBakes, changeFrameCreates, changeFrameFirstSight, changeFrameOffScreen;
   public static final int[] changeFrameFlags = new int[16];
}
