package pzopt;

import java.util.function.Consumer;
import zombie.core.Core;
import zombie.core.textures.TextureDraw;

/**
 * Late-latched software cursor ({@code cursorLatch}, 2026-09-24). The render thread shifts the captured cursor sprite
 * to the latest safely published producer position just before replay; native events are pumped only by InputThread.
 * This changes only the drawn cursor, not the simulation's frame snapshot.
 */
public final class CursorLatch implements Consumer<TextureDraw> {
   public static final boolean ON = Config.CURSOR_LATCH && Overrides.enabled();
   private static final CursorLatch INSTANCE = new CursorLatch();
   private static final int SLOTS = 4;
   private static final Object[] slotState = new Object[SLOTS];
   private static final TextureDraw[] slotDraw = new TextureDraw[SLOTS];
   private static final int[] slotX = new int[SLOTS], slotY = new int[SLOTS];
   private static final float[] slotScaleX = new float[SLOTS], slotScaleY = new float[SLOTS];
   private static final float[] slotOffsetX = new float[SLOTS], slotOffsetY = new float[SLOTS];
   private static float pendingScaleX, pendingScaleY, pendingOffsetX, pendingOffsetY;
   private static int next, pendingX, pendingY;
   private static long latched, lastLogNs;
   private static double shiftSum;

   private CursorLatch() {
   }

   /** Game thread: the modifier for the cursor sprite drawn at (usedX, usedY) (the Mouse.getXA / getYA it used). */
   public static Consumer<TextureDraw> capture(int usedX, int usedY) {
      if (!ON) {
         return null;
      }
      pendingX = usedX;
      pendingY = usedY;
      pendingScaleX = pendingScaleY = 1;
      pendingOffsetX = pendingOffsetY = 0;
      if (InputThread.active() && zombie.debug.DebugContext.isUsingGameViewportWindow()) {
         var viewport = zombie.debug.DebugContext.instance.viewport;
         pendingOffsetX = viewport.transformXToGame(ImGuiInput.eventScreenX(0));
         pendingOffsetY = viewport.transformYToGame(ImGuiInput.eventScreenY(0));
         pendingScaleX = viewport.transformXToGame(ImGuiInput.eventScreenX(1)) - pendingOffsetX;
         pendingScaleY = viewport.transformYToGame(ImGuiInput.eventScreenY(1)) - pendingOffsetY;
      }
      return INSTANCE;
   }

   @Override
   public void accept(TextureDraw texd) {
      synchronized (slotState) {
         int i = next++ % SLOTS;
         slotState[i] = zombie.core.SpriteRenderer.instance.states.getPopulating();
         slotDraw[i] = texd;
         slotX[i] = pendingX;
         slotY[i] = pendingY;
         slotScaleX[i] = pendingScaleX; slotScaleY[i] = pendingScaleY;
         slotOffsetX[i] = pendingOffsetX; slotOffsetY[i] = pendingOffsetY;
      }
   }

   /** Render thread, lockStepRenderStep before the sprite replay of renderState. */
   public static void beforeReplay(Object renderState) {
      if (!ON) {
         return;
      }
      TextureDraw texd = null;
      int usedX = 0, usedY = 0;
      float scaleX = 1, scaleY = 1, offsetX = 0, offsetY = 0;
      synchronized (slotState) {
         for (int i = 0; i < SLOTS; i++) {
            if (slotState[i] == renderState && slotDraw[i] != null) {
               texd = slotDraw[i];
               usedX = slotX[i];
               usedY = slotY[i];
               scaleX = slotScaleX[i]; scaleY = slotScaleY[i];
               offsetX = slotOffsetX[i]; offsetY = slotOffsetY[i];
               slotState[i] = null;
               slotDraw[i] = null;
            }
         }
      }
      if (texd == null) {
         return;
      }
      int x;
      int y;
      if (InputThread.active()) {
         long position = SubframeInput.latestPosition();
         x = (int)(position >> 32);
         y = (int)position;
      } else {
         x = org.lwjglx.input.Mouse.pzoptLatestX();
         y = Core.getInstance().getScreenHeight() - org.lwjglx.input.Mouse.pzoptLatestY() - 1;
      }
      float dx = x * scaleX + offsetX - usedX, dy = y * scaleY + offsetY - usedY;
      texd.x0 += dx;
      texd.x1 += dx;
      texd.x2 += dx;
      texd.x3 += dx;
      texd.y0 += dy;
      texd.y1 += dy;
      texd.y2 += dy;
      texd.y3 += dy;
      latched++;
      shiftSum += Math.hypot(dx, dy);
      long now = System.nanoTime();
      if (now - lastLogNs > 10_000_000_000L) {
         if (lastLogNs != 0L) {
            Log.info(String.format("cursor latch: %d frames, mean shift %.1f px", latched, shiftSum / Math.max(1, latched)));
         }
         lastLogNs = now;
         latched = 0;
         shiftSum = 0.0;
      }
   }
}
