package zombie.input;

import java.io.File;
import org.lwjglx.LWJGLException;
import org.lwjglx.input.Cursor;
import zombie.GameTime;
import zombie.UsedFromLua;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.logger.ExceptionLogger;
import zombie.core.textures.Texture;
import zombie.core.utils.NativeImage;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

@UsedFromLua
public final class Mouse {
   protected static int x;
   protected static int y;
   private static float timeRightPressed;
   private static final float TIME_RIGHT_PRESSED_SECONDS = 0.15F;
   public static final int BTN_OFFSET = 10000;
   public static final int BTN_0 = 10000;
   public static final int BTN_1 = 10001;
   public static final int BTN_2 = 10002;
   public static final int BTN_3 = 10003;
   public static final int BTN_4 = 10004;
   public static final int BTN_5 = 10005;
   public static final int BTN_6 = 10006;
   public static final int BTN_7 = 10007;
   public static final int LMB = 10000;
   public static final int RMB = 10001;
   public static final int MMB = 10002;
   public static boolean[] buttonDownStates;
   public static boolean[] buttonPrevStates;
   public static long lastActivity;
   public static int wheelDelta;
   private static final MouseStateCache s_mouseStateCache = new MouseStateCache();
   public static boolean[] uiCaptured = new boolean[10];
   static Cursor blankCursor;
   static Cursor defaultCursor;
   private static boolean isCursorVisible = true;
   private static Texture mouseCursorTexture;

   public static int getWheelState() {
      if (pzopt.InputThread.active() && pzopt.SubframeInput.inEvent()) {
         return pzopt.SubframeInput.eventWheelSteps();
      }
      return wheelDelta;
   }

   public static int getButtonCount() {
      if (pzopt.InputThread.active()) return 8;
      return s_mouseStateCache.getState().getButtonCount();
   }

   public static synchronized int getXA() {
      if (pzopt.InputThread.active() && pzopt.SubframeInput.inEvent()) return pzoptGameX(pzopt.SubframeInput.eventX());
      return pzopt.Showcase.aimOverride ? pzopt.Showcase.aimXA : x; // pzopt: harness showcase=horde aims the game's mouse at a zombie
   }

   public static synchronized int getYA() {
      if (pzopt.InputThread.active() && pzopt.SubframeInput.inEvent()) return pzoptGameY(pzopt.SubframeInput.eventY());
      return pzopt.Showcase.aimOverride ? pzopt.Showcase.aimYA : y; // pzopt: harness showcase=horde aims the game's mouse at a zombie
   }

   public static synchronized int getX() {
      return (int)(getXA() * Core.getInstance().getZoom(0)); // pzopt: same event/frame position as unscaled UI input
   }

   public static synchronized int getY() {
      return (int)(getYA() * Core.getInstance().getZoom(0)); // pzopt: same event/frame position as unscaled UI input
   }

   public static boolean isButtonKey(int key) {
      return key >= 10000;
   }

   public static boolean isButtonDown(int number) {
      return buttonDownStates != null ? buttonDownStates[number] : false;
   }

   public static boolean wasButtonDown(int number) {
      return buttonPrevStates != null ? buttonPrevStates[number] : false;
   }

   public static boolean isButtonPressed(int number) {
      return buttonDownStates != null && buttonPrevStates != null ? !buttonPrevStates[number] && buttonDownStates[number] : false;
   }

   public static boolean isButtonReleased(int number) {
      return buttonDownStates != null && buttonPrevStates != null ? buttonPrevStates[number] && !buttonDownStates[number] : false;
   }

   public static void UIBlockButtonDown(int number) {
      uiCaptured[number] = true;
   }

   public static boolean isButtonDownUICheck(int number) {
      if (buttonDownStates == null) {
         return false;
      }

      boolean b = buttonDownStates[number];
      if (!b) {
         uiCaptured[number] = false;
      } else if (uiCaptured[number]) {
         return false;
      }

      return number == 1 ? isRightDelay() : b;
   }

   public static boolean isRightDelay() {
      if (pzopt.InputThread.active() && !pzopt.Showcase.holdButtons) {
         return !uiCaptured[1] && isButtonDown(1)
            && pzopt.SubframeInput.rightHeldNanos() >= (long)(pzopt.InputLatch.AIM_HOLD_S * 1_000_000_000L);
      }
      return !uiCaptured[1] && buttonDownStates != null && buttonDownStates[1] ? timeRightPressed >= pzopt.InputLatch.AIM_HOLD_S : false; // pzopt: aimHoldMs (stock 0.15 s)
   }

   public static boolean isLeftDown() {
      return isButtonDown(0);
   }

   public static boolean isLeftPressed() {
      return isButtonPressed(0);
   }

   public static boolean isLeftReleased() {
      return isButtonReleased(0);
   }

   public static boolean isLeftUp() {
      return !isButtonDown(0);
   }

   public static boolean isMiddleDown() {
      return isButtonDown(2);
   }

   public static boolean isMiddlePressed() {
      return isButtonPressed(2);
   }

   public static boolean isMiddleReleased() {
      return isButtonReleased(2);
   }

   public static boolean isMiddleUp() {
      return !isButtonDown(2);
   }

   public static boolean isRightDown() {
      return isButtonDown(1);
   }

   public static boolean isRightPressed() {
      return isButtonPressed(1);
   }

   public static boolean isRightReleased() {
      return isButtonReleased(1);
   }

   public static boolean isRightUp() {
      return !isButtonDown(1);
   }

   public static synchronized void update() {
      if (pzopt.InputThread.active()) {
         pzoptUpdateFrame();
         return;
      }
      MouseState state = s_mouseStateCache.getState();
      if (!state.isCreated()) {
         s_mouseStateCache.swap();

         try {
            org.lwjglx.input.Mouse.create();
         } catch (LWJGLException e) {
            DebugType.General.printException(e, LogSeverity.Error);
         }
      } else {
         int lastX = x;
         int lastY = y;
         x = state.getX();
         y = Core.getInstance().getScreenHeight() - state.getY() - 1;
         wheelDelta = state.getDWheel();
         state.resetDWheel();
         boolean bActivity = lastX != x || lastY != y || wheelDelta != 0;
         if (buttonDownStates == null) {
            buttonDownStates = new boolean[state.getButtonCount()];
         }

         if (buttonPrevStates == null) {
            buttonPrevStates = new boolean[state.getButtonCount()];
         }

         for (int i = 0; i < buttonDownStates.length; i++) {
            buttonPrevStates[i] = buttonDownStates[i];
         }

         for (int i = 0; i < buttonDownStates.length; i++) {
            if (buttonDownStates[i] != state.isButtonDown(i)) {
               bActivity = true;
            }

            buttonDownStates[i] = state.isButtonDown(i);
         }

         if (pzopt.Showcase.holdButtons && buttonDownStates.length > 1) { // pzopt: harness showcase=horde, a player holding aim and pulling the trigger
            buttonDownStates[0] = pzopt.Showcase.fireDown; // pzopt: harness showcase fire (left)
            buttonDownStates[1] = true; // pzopt: harness showcase aim (right)
         }

         if (buttonDownStates[1]) {
            timeRightPressed = timeRightPressed + GameTime.getInstance().getRealworldSecondsSinceLastUpdate();
         } else {
            timeRightPressed = 0.0F;
         }

         if (bActivity) {
            lastActivity = System.currentTimeMillis();
         }

         s_mouseStateCache.swap();
         AimingReticle.update();
      }
   }

   private static void pzoptUpdateFrame() {
      int previous = 0;
      if (buttonDownStates != null) {
         for (int i = 0; i < buttonDownStates.length; i++) if (buttonDownStates[i]) previous |= 1 << i;
      }
      boolean cancelled = pzopt.SubframeInput.reset || pzopt.SubframeInput.hasPendingReset();
      pzoptButtonState(cancelled ? 0 : pzopt.SubframeInput.buttons(), cancelled ? 0 : previous);
      if (pzopt.SubframeInput.hasPosition()) {
         x = pzoptGameX(pzopt.SubframeInput.x());
         y = pzoptGameY(pzopt.SubframeInput.y());
      }
      wheelDelta = cancelled ? 0 : pzopt.SubframeInput.wheel();
      if (cancelled) {
         java.util.Arrays.fill(uiCaptured, false);
      } else if (pzopt.Showcase.holdButtons) {
         buttonDownStates[0] = pzopt.Showcase.fireDown;
         buttonDownStates[1] = true;
      }
      if (buttonDownStates[1]) {
         timeRightPressed += GameTime.getInstance().getRealworldSecondsSinceLastUpdate();
      } else {
         timeRightPressed = 0.0F;
      }
      if (pzopt.SubframeInput.count != 0) lastActivity = System.currentTimeMillis();
      AimingReticle.update();
   }

   private static int pzoptGameX(int px) {
      return zombie.debug.DebugContext.isUsingGameViewportWindow()
         ? (int)zombie.debug.DebugContext.instance.viewport.transformXToGame(pzopt.ImGuiInput.eventScreenX(px)) : px;
   }

   private static int pzoptGameY(int py) {
      return zombie.debug.DebugContext.isUsingGameViewportWindow()
         ? (int)zombie.debug.DebugContext.instance.viewport.transformYToGame(pzopt.ImGuiInput.eventScreenY(py)) : py;
   }

   /** Game-thread event scopes also expose the stock arrays to Lua/native game bindings. */
   public static void pzoptButtonState(int down, int previous) {
      if (buttonDownStates == null) buttonDownStates = new boolean[8];
      if (buttonPrevStates == null) buttonPrevStates = new boolean[8];
      for (int i = 0; i < buttonDownStates.length; i++) {
         buttonDownStates[i] = (down & (1 << i)) != 0;
         buttonPrevStates[i] = (previous & (1 << i)) != 0;
      }
   }

   public static void poll() {
      if (pzopt.InputThread.active()) return;
      s_mouseStateCache.poll();
   }

   public static synchronized void setXY(int x, int y) {
      s_mouseStateCache.getState().setCursorPosition(x, Core.getInstance().getOffscreenHeight(0) - 1 - y);
   }

   public static Cursor loadCursor(String filename) throws LWJGLException {
      File file = ZomboidFileSystem.instance.getMediaFile("ui/" + filename);

      try {
         NativeImage image = NativeImage.read(file.getAbsolutePath(), true);

         Cursor var8;
         label50: {
            try {
               if (image == null) {
                  var8 = null;
                  break label50;
               }

               if (pzopt.InputThread.active() && !pzopt.InputThread.isOwnerThread()) {
                  var8 = pzopt.InputThread.call(() -> {
                     try {
                        return new Cursor(image.width(), image.height(), 1, 1, 1, image.pixels().asIntBuffer(), null);
                     } catch (LWJGLException ex) {
                        throw new IllegalStateException("Creating native cursor", ex);
                     }
                  });
               } else {
                  var8 = new Cursor(image.width(), image.height(), 1, 1, 1, image.pixels().asIntBuffer(), null);
               }
            } catch (Throwable var6) {
               if (image != null) {
                  try {
                     image.close();
                  } catch (Throwable var5) {
                     var6.addSuppressed(var5);
                  }
               }

               throw var6;
            }

            if (image != null) {
               image.close();
            }

            return var8;
         }

         if (image != null) {
            image.close();
         }

         return var8;
      } catch (Exception ex) {
         ExceptionLogger.logException(ex);
         return null;
      }
   }

   public static void initCustomCursor() {
      if (blankCursor == null) {
         try {
            blankCursor = loadCursor("cursor_blank.png");
            defaultCursor = loadCursor("cursor_white.png");
         } catch (LWJGLException e) {
            DebugType.General.printException(e, LogSeverity.Error);
         }
      }

      if (defaultCursor != null) {
         try {
            org.lwjglx.input.Mouse.setNativeCursor(defaultCursor);
         } catch (LWJGLException e) {
            DebugType.General.printException(e, LogSeverity.Error);
         }
      }
   }

   public static void setCursorVisible(boolean bVisible) {
      isCursorVisible = bVisible;
   }

   public static boolean isCursorVisible() {
      return isCursorVisible;
   }

   public static void renderCursorTexture() {
      if (isCursorVisible()) {
         if (mouseCursorTexture == null) {
            mouseCursorTexture = Texture.getSharedTexture("media/ui/cursor_white.png");
         }

         if (mouseCursorTexture != null && mouseCursorTexture.isReady()) {
            int mouseX = getXA();
            int mouseY = getYA();
            int hotSpotX = 1;
            int hotSpotY = 1;
            SpriteRenderer.instance
               .render(mouseCursorTexture, mouseX - 1, mouseY - 1, mouseCursorTexture.getWidth(), mouseCursorTexture.getHeight(), 1.0F, 1.0F, 1.0F, 1.0F, pzopt.CursorLatch.capture(mouseX, mouseY)); // pzopt: cursorLatch, the render thread moves the sprite to the newest pointer position
         }
      }
   }
}
