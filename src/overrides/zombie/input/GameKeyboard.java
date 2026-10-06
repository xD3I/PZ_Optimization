package zombie.input;

import java.util.concurrent.atomic.AtomicIntegerArray;
import org.lwjgl.glfw.GLFW;

import org.lwjglx.input.KeyEventQueue;
import zombie.GameWindow;
import zombie.UsedFromLua;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;
import zombie.core.Core;
import zombie.core.Core.KeyBinding;
import zombie.core.opengl.RenderThread;
import zombie.ui.UIManager;

@UsedFromLua
public final class GameKeyboard {
   private static boolean[] down;
   private static boolean[] lastDown;
   private static boolean[] eatKey;
   public static boolean noEventsWhileLoading;
   public static boolean doLuaKeyPressed = true;
   private static final KeyboardStateCache s_keyboardStateCache = new KeyboardStateCache();
   private static final AtomicIntegerArray pzoptKeyBits = new AtomicIntegerArray(org.lwjglx.input.Keyboard.KEYBOARD_SIZE);

   public static void update() {
      if (!s_keyboardStateCache.getState().isCreated()) {
         s_keyboardStateCache.swap();
      } else {
         boolean pzoptFresh = pzopt.InputLatch.KEYBOARD_FRESH; // pzopt: keyboardFresh, read the poll this frame swaps in (stock read the previous one and swapped at the end)
         if (pzoptFresh) { // pzopt
            s_keyboardStateCache.swap(); // pzopt
         } // pzopt
         int c = s_keyboardStateCache.getState().getKeyCount();
         if (down == null) {
            down = new boolean[c];
            lastDown = new boolean[c];
            eatKey = new boolean[c];
         }

         boolean bDoingTextEntry = Core.currentTextEntryBox != null && Core.currentTextEntryBox.isDoingTextEntry();

         for (int n = 1; n < c; n++) {
            lastDown[n] = down[n];
            down[n] = s_keyboardStateCache.getState().isKeyDown(n);
            if (down[n] != lastDown[n]) { // pzopt: uiRetained, a key changed: every UI element renders fresh
               pzopt.UiRetained.keyEvents++; // pzopt: uiRetained
            } // pzopt: uiRetained
            if (!down[n] && lastDown[n]) {
               if (eatKey[n]) {
                  eatKey[n] = false;
                  continue;
               }

               if (noEventsWhileLoading || bDoingTextEntry || LuaManager.thread == UIManager.defaultthread && UIManager.onKeyRelease(n)) {
                  continue;
               }

               if (Core.debug && !doLuaKeyPressed) {
                  System.out.println("KEY RELEASED " + n + " doLuaKeyPressed=false");
               }

               if (LuaManager.thread == UIManager.defaultthread && doLuaKeyPressed) {
                  LuaEventManager.triggerEvent("OnKeyPressed", n);
               }

               if (LuaManager.thread == UIManager.defaultthread) {
                  LuaEventManager.triggerEvent("OnCustomUIKey", n);
                  LuaEventManager.triggerEvent("OnCustomUIKeyReleased", n);
               }
            }

            if (down[n] && lastDown[n]) {
               if (noEventsWhileLoading || bDoingTextEntry || LuaManager.thread == UIManager.defaultthread && UIManager.onKeyRepeat(n)) {
                  continue;
               }

               if (LuaManager.thread == UIManager.defaultthread && doLuaKeyPressed) {
                  LuaEventManager.triggerEvent("OnKeyKeepPressed", n);
               }
            }

            if (down[n]
               && !lastDown[n]
               && !noEventsWhileLoading
               && !bDoingTextEntry
               && !eatKey[n]
               && (LuaManager.thread != UIManager.defaultthread || !UIManager.onKeyPress(n))
               && !eatKey[n]) {
               if (LuaManager.thread == UIManager.defaultthread && doLuaKeyPressed) {
                  LuaEventManager.triggerEvent("OnKeyStartPressed", n);
               }

               if (LuaManager.thread == UIManager.defaultthread) {
                  LuaEventManager.triggerEvent("OnCustomUIKeyPressed", n);
               }
            }
         }

         if (!pzoptFresh) { // pzopt: keyboardFresh swapped at the top
            s_keyboardStateCache.swap();
         } // pzopt
      }
   }

   public static void pzoptRepoll() { // pzopt: late input latch (pzopt.InputLatch), render thread
      s_keyboardStateCache.pzoptRepoll(); // pzopt
   } // pzopt

   public static void poll() {
      s_keyboardStateCache.poll();
   }

   public static void pzoptKeyEvent(int key, int action) {
      if (Core.isUseGameViewport() && !zombie.debug.DebugContext.instance.focusedGameViewport) return;
      int lwjglKey = org.lwjglx.input.KeyCodes.toLwjglKey(key);
      if (lwjglKey >= 0 && lwjglKey < org.lwjglx.input.Keyboard.KEYBOARD_SIZE) {
         if (action == GLFW.GLFW_RELEASE) pzoptKeyBits.set(lwjglKey, 0);
         else if (action == GLFW.GLFW_PRESS || action == GLFW.GLFW_REPEAT) pzoptKeyBits.set(lwjglKey, 1);
      }
      s_keyboardStateCache.pzoptKeyEvent(key, action);
      pzopt.InputThread.requestSample();
   }

   public static void pzoptCharEvent(char value) {
      if (Core.isUseGameViewport() && !zombie.debug.DebugContext.instance.focusedGameViewport) return;
      s_keyboardStateCache.pzoptCharEvent(value);
      pzopt.InputThread.requestSample();
   }

   /** Seeded by Display on the GLFW owner immediately before it publishes InputThread.active(). */
   public static void pzoptSeedKeyState() {
      if (!pzopt.InputThread.isOwnerThread()) throw new IllegalStateException("Keyboard seed requires GLFW owner");
      long window = org.lwjglx.opengl.Display.getWindow();
      boolean focused = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
      for (int key = 0; key < org.lwjglx.input.Keyboard.KEYBOARD_SIZE; key++) {
         int glfwKey = org.lwjglx.input.KeyCodes.toGlfwKey(key);
         pzoptKeyBits.set(key, focused && glfwKey >= 0 && GLFW.glfwGetKey(window, glfwKey) == GLFW.GLFW_PRESS ? 1 : 0);
      }
   }

   public static boolean pzoptIsKeyDown(int key) {
      return !(Core.isUseGameViewport() && !zombie.debug.DebugContext.instance.focusedGameViewport)
         && key >= 0 && key < org.lwjglx.input.Keyboard.KEYBOARD_SIZE && pzoptKeyBits.get(key) != 0;
   }

   public static boolean pzoptNextEvent() { return s_keyboardStateCache.pzoptNextEvent(); }
   public static int pzoptEventKey() { return s_keyboardStateCache.pzoptEventKey(); }
   public static char pzoptEventCharacter() { return s_keyboardStateCache.pzoptEventCharacter(); }
   public static boolean pzoptEventKeyState() { return s_keyboardStateCache.pzoptEventKeyState(); }
   public static long pzoptEventNanoseconds() { return s_keyboardStateCache.pzoptEventNanoseconds(); }

   public static void pzoptFocusLost() {
      for (int key = 0; key < org.lwjglx.input.Keyboard.KEYBOARD_SIZE; key++) pzoptKeyBits.set(key, 0);
      s_keyboardStateCache.pzoptFocusLost();
   }

   public static void pzoptPollDevices() {
      s_keyboardStateCache.pzoptPollDevices();
   }

   public static boolean isKeyDownRaw(int key) {
      return down == null ? false : down[key];
   }

   public static boolean wasKeyDownRaw(int key) {
      return lastDown == null ? false : lastDown[key];
   }

   public static boolean isKeyPressed(int key) {
      return isKeyDown(key) && !wasKeyDown(key);
   }

   public static boolean isKeyPressed(String keyName) {
      return isKeyDown(keyName) && !wasKeyDown(keyName);
   }

   public static int whichKeyPressed(String keyName) {
      if (!isKeyPressed(keyName)) {
         return 0;
      } else if (isKeyPressed(Core.getInstance().getKey(keyName))) {
         return Core.getInstance().getKey(keyName);
      } else {
         return isKeyPressed(Core.getInstance().getAltKey(keyName)) ? Core.getInstance().getAltKey(keyName) : 0;
      }
   }

   public static boolean isKeyDown(int key) {
      if (key >= 10000) {
         return Mouse.isButtonDownUICheck(key - 10000);
      } else if (Core.currentTextEntryBox != null && Core.currentTextEntryBox.isDoingTextEntry()) {
         return false;
      } else if (pzopt.Showcase.keyHeld(key)) { // pzopt: harness showcase=horde, the director's movement keys
         return true;
      } else {
         return down == null ? false : down[key];
      }
   }

   public static boolean isKeyDown(String keyName) {
      KeyBinding keyB = Core.getInstance().getKeyBinding(keyName);
      return Core.getInstance().invalidBindingShiftCtrl(keyB) ? false : isKeyDown(keyB.keyValue()) || isKeyDown(keyB.altKey());
   }

   public static int whichKeyDown(String keyName) {
      if (!isKeyDown(keyName)) {
         return 0;
      } else if (isKeyDown(Core.getInstance().getKey(keyName))) {
         return Core.getInstance().getKey(keyName);
      } else {
         return isKeyDown(Core.getInstance().getAltKey(keyName)) ? Core.getInstance().getAltKey(keyName) : 0;
      }
   }

   public static int whichKeyDownIgnoreMouse(String keyName) {
      int key = Core.getInstance().getKey(keyName);
      if (key < 10000 && isKeyDown(key)) {
         return key;
      }

      key = Core.getInstance().getAltKey(keyName);
      return key < 10000 && isKeyDown(key) ? key : 0;
   }

   public static boolean wasKeyDown(int key) {
      if (key >= 10000) {
         return Mouse.wasButtonDown(key - 10000);
      } else if (Core.currentTextEntryBox != null && Core.currentTextEntryBox.isDoingTextEntry()) {
         return false;
      } else {
         return lastDown == null ? false : lastDown[key];
      }
   }

   public static boolean wasKeyDown(String keyName) {
      return wasKeyDown(Core.getInstance().getKey(keyName)) || wasKeyDown(Core.getInstance().getAltKey(keyName));
   }

   public static int whichKeyWasDown(String keyName) {
      if (wasKeyDown(Core.getInstance().getKey(keyName))) {
         return Core.getInstance().getKey(keyName);
      } else {
         return wasKeyDown(Core.getInstance().getAltKey(keyName)) ? Core.getInstance().getAltKey(keyName) : 0;
      }
   }

   public static void eatKeyPress(int key) {
      if (key >= 0 && key < eatKey.length) {
         eatKey[key] = true;
      }
   }

   public static void setDoLuaKeyPressed(boolean doIt) {
      doLuaKeyPressed = doIt;
   }

   public static KeyEventQueue getEventQueue() {
      assert Thread.currentThread() == GameWindow.gameThread;
      return s_keyboardStateCache.getState().getEventQueue();
   }

   public static KeyEventQueue getEventQueuePolling() {
      assert Thread.currentThread() == RenderThread.renderThread;
      return s_keyboardStateCache.getStatePolling().getEventQueue();
   }

   public static boolean isKeyDown(KeybindId keybindId) {
      return isKeyDown(keybindId.getId());
   }

   public static boolean isKeyPressed(KeybindId keybindId) {
      return isKeyPressed(keybindId.getId());
   }

   public static boolean wasKeyDown(KeybindId keybindId) {
      return wasKeyDown(keybindId.getId());
   }

   public static int whichKeyDown(KeybindId keybindId) {
      return whichKeyDown(keybindId.getId());
   }
}
