package zombie.core.input;

import java.util.ArrayList;
import org.lwjglx.input.Controller;
import org.lwjglx.input.Keyboard;
import org.lwjglx.input.Mouse;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;
import zombie.core.Core;
import zombie.core.Translator;
import zombie.input.ControllerStateCache;
import zombie.input.GameKeyboard;
import zombie.input.JoypadManager;

public final class Input {
   public static final int ANY_CONTROLLER = -1;
   private final Controller[] controllers = new Controller[16];
   private final ArrayList<Controller> newlyConnected = new ArrayList<>();
   private final ArrayList<Controller> newlyDisconnected = new ArrayList<>();
   private final boolean[][] controllerPressed = new boolean[16][15];
   private final boolean[][] controllerWasPressed = new boolean[16][15];
   private final float[][] controllerPov = new float[16][2];
   private final ControllerStateCache controllerStateCache = new ControllerStateCache();

   public static String getKeyName(int code) {
      if (code >= 10000) {
         return Translator.getText("IGUI_mouse_btn_" + (code - 10000), new Object[0]);
      }

      String keyName = Keyboard.getKeyName(code);
      String translatedKeyName = Translator.getTextOrNull("IGUI_Key_" + keyName, new Object[0]);
      return translatedKeyName != null ? translatedKeyName : keyName;
   }

   public static int getKeyCode(String keyName) {
      if (System.getProperty("os.name").contains("OS X")) {
         if ("Left ALT".equals(keyName)) {
            return 219;
         }

         if ("Right ALT".equals(keyName)) {
            return 220;
         }
      }

      if ("Right SHIFT".equals(keyName)) {
         return 54;
      } else if ("Left SHIFT".equals(keyName)) {
         return 42;
      } else if ("Left ALT".equals(keyName)) {
         return 56;
      } else {
         return "Right ALT".equals(keyName) ? 184 : Keyboard.getKeyIndex(keyName);
      }
   }

   public int getControllerCount() {
      return this.controllers.length;
   }

   public int getAxisCount(int index) {
      Controller controller = this.getController(index);
      return controller == null ? 0 : controller.getAxisCount();
   }

   public float getAxisValue(int index, int axis) {
      Controller controller = this.getController(index);
      return controller == null ? 0.0F : controller.getAxisValue(axis);
   }

   public String getAxisName(int index, int axis) {
      Controller controller = this.getController(index);
      return controller == null ? null : controller.getAxisName(axis);
   }

   public boolean isControllerLeftD(int index) {
      if (index == -1) {
         for (int i = 0; i < this.controllers.length; i++) {
            if (this.isControllerLeftD(i)) {
               return true;
            }
         }

         return false;
      } else {
         Controller controller = this.getController(index);
         return controller == null ? false : controller.getPovX() < -0.5F;
      }
   }

   public boolean isControllerRightD(int index) {
      if (index == -1) {
         for (int i = 0; i < this.controllers.length; i++) {
            if (this.isControllerRightD(i)) {
               return true;
            }
         }

         return false;
      } else {
         Controller controller = this.getController(index);
         return controller == null ? false : controller.getPovX() > 0.5F;
      }
   }

   public boolean isControllerUpD(int index) {
      if (index == -1) {
         for (int i = 0; i < this.controllers.length; i++) {
            if (this.isControllerUpD(i)) {
               return true;
            }
         }

         return false;
      } else {
         Controller controller = this.getController(index);
         return controller == null ? false : controller.getPovY() < -0.5F;
      }
   }

   public boolean isControllerDownD(int index) {
      if (index == -1) {
         for (int i = 0; i < this.controllers.length; i++) {
            if (this.isControllerDownD(i)) {
               return true;
            }
         }

         return false;
      } else {
         Controller controller = this.getController(index);
         return controller == null ? false : controller.getPovY() > 0.5F;
      }
   }

   private Controller checkControllerButton(int index, int button) {
      Controller controller = this.getController(index);
      if (controller == null) {
         return null;
      } else {
         return button >= 0 && button < controller.getButtonCount() ? controller : null;
      }
   }

   public boolean isButtonPressedD(int button, int index) {
      if (index == -1) {
         for (int i = 0; i < this.controllers.length; i++) {
            if (this.isButtonPressedD(button, i)) {
               return true;
            }
         }

         return false;
      } else {
         Controller controller = this.checkControllerButton(index, button);
         return controller == null ? false : this.controllerPressed[index][button];
      }
   }

   public boolean wasButtonPressed(int index, int button) {
      Controller controller = this.checkControllerButton(index, button);
      return controller == null ? false : this.controllerWasPressed[index][button];
   }

   public boolean isButtonStartPress(int index, int button) {
      return !this.wasButtonPressed(index, button) && this.isButtonPressedD(button, index);
   }

   public boolean isButtonReleasePress(int index, int button) {
      return this.wasButtonPressed(index, button) && !this.isButtonPressedD(button, index);
   }

   public void initControllers() {
      this.updateGameThread();
      this.updateGameThread();
   }

   private void onControllerConnected(Controller controller) {
      JoypadManager.instance.onControllerConnected(controller);
      if (LuaManager.env != null) {
         LuaEventManager.triggerEvent("OnGamepadConnect", controller.getID());
      }
   }

   private void onControllerDisconnected(Controller controller) {
      JoypadManager.instance.onControllerDisconnected(controller);
      if (LuaManager.env != null) {
         LuaEventManager.triggerEvent("OnGamepadDisconnect", controller.getID());
      }
   }

   public void poll() {
      if (!Core.getInstance().isDoingTextEntry()) {
         while (GameKeyboard.getEventQueuePolling().next()) {
         }
      }

      while (Mouse.next()) {
      }

      this.controllerStateCache.poll();
   }

   public Controller getController(int index) {
      return index >= 0 && index < this.controllers.length ? this.controllers[index] : null;
   }

   public int getButtonCount(int index) {
      Controller controller = this.getController(index);
      return controller == null ? null : controller.getButtonCount();
   }

   public String getButtonName(int index, int button) {
      Controller controller = this.getController(index);
      return controller == null ? null : controller.getButtonName(button);
   }

   public void updateGameThread() {
      if (!this.controllerStateCache.getState().isCreated()) {
         this.controllerStateCache.swap();
      } else {
         boolean pzoptFresh = pzopt.InputLatch.PAD_FRESH; // pzopt: padFresh, the button edges come from the poll this frame swaps in (stock read the previous one and swapped at the end: every pad button a frame late, the hat and sticks read later were not)
         if (pzoptFresh) { // pzopt: padFresh
            this.controllerStateCache.swap(); // pzopt: padFresh
         } // pzopt: padFresh
         if (this.checkConnectDisconnect(this.controllerStateCache)) {
            for (int i = 0; i < this.newlyDisconnected.size(); i++) {
               Controller controller = this.newlyDisconnected.get(i);
               this.onControllerDisconnected(controller);
            }

            for (int i = 0; i < this.newlyConnected.size(); i++) {
               Controller controller = this.newlyConnected.get(i);
               this.onControllerConnected(controller);
            }
         }

         for (int i = 0; i < this.getControllerCount(); i++) {
            Controller controller = this.getController(i);
            if (controller != null) {
               int buttonCount = controller.getButtonCount();

               for (int buttonIdx = 0; buttonIdx < buttonCount; buttonIdx++) {
                  this.controllerWasPressed[i][buttonIdx] = this.controllerPressed[i][buttonIdx];
                  if (this.controllerPressed[i][buttonIdx] && !controller.isButtonPressed(buttonIdx)) {
                     this.controllerPressed[i][buttonIdx] = false;
                  } else if (!this.controllerPressed[i][buttonIdx] && controller.isButtonPressed(buttonIdx)) {
                     this.controllerPressed[i][buttonIdx] = true;
                     JoypadManager.instance.onPressed(i, buttonIdx);
                  }
               }

               int axisCount = controller.getAxisCount();

               for (int axisIdx = 0; axisIdx < axisCount; axisIdx++) {
                  float axisValue = controller.getAxisValue(axisIdx);
                  if ((!controller.isGamepad() || axisIdx != 4) && axisIdx != 5) {
                     if (axisValue < -0.5F) {
                        JoypadManager.instance.onPressedAxisNeg(i, axisIdx);
                     }

                     if (axisValue > 0.5F) {
                        JoypadManager.instance.onPressedAxis(i, axisIdx);
                     }
                  } else if (axisValue > 0.0F) {
                     JoypadManager.instance.onPressedTrigger(i, axisIdx);
                  }
               }

               float povX = controller.getPovX();
               float povY = controller.getPovY();
               if (povX != this.controllerPov[i][0] || povY != this.controllerPov[i][1]) {
                  this.controllerPov[i][0] = povX;
                  this.controllerPov[i][1] = povY;
                  JoypadManager.instance.onPressedPov(i);
               }
            }
         }

         if (!pzoptFresh) { // pzopt: padFresh swapped at the top
            this.controllerStateCache.swap();
         } // pzopt: padFresh
      }
   }

   private boolean checkConnectDisconnect(ControllerStateCache controllerState) {
      boolean bChanged = false;
      this.newlyConnected.clear();
      this.newlyDisconnected.clear();

      for (int i = 0; i < 16; i++) {
         Controller controller = controllerState.getController(i);
         if (controller != this.controllers[i]) {
            bChanged = true;
            if (controller != null && controller.isGamepad()) {
               this.newlyConnected.add(controller);
            } else {
               if (this.controllers[i] != null) {
                  this.newlyDisconnected.add(this.controllers[i]);
               }

               controller = null;
            }

            this.controllers[i] = controller;
         }
      }

      return bChanged;
   }

   public void quit() {
      this.controllerStateCache.quit();
   }
}
