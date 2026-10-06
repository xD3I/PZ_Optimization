package org.lwjglx.input;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.glfw.GLFW;
import org.lwjglx.opengl.Display;

/** LWJGL keyboard compatibility API, with active input-thread reads served from owner publications. */
public class Keyboard {
   public static final int CHAR_NONE=0, KEY_NONE=0, KEY_ESCAPE=1, KEY_1=2, KEY_2=3, KEY_3=4, KEY_4=5, KEY_5=6, KEY_6=7, KEY_7=8, KEY_8=9, KEY_9=10, KEY_0=11, KEY_MINUS=12, KEY_EQUALS=13, KEY_BACK=14, KEY_TAB=15, KEY_Q=16, KEY_W=17, KEY_E=18, KEY_R=19, KEY_T=20, KEY_Y=21, KEY_U=22, KEY_I=23, KEY_O=24, KEY_P=25, KEY_LBRACKET=26, KEY_RBRACKET=27, KEY_RETURN=28, KEY_LCONTROL=29, KEY_A=30, KEY_S=31, KEY_D=32, KEY_F=33, KEY_G=34, KEY_H=35, KEY_J=36, KEY_K=37, KEY_L=38, KEY_SEMICOLON=39, KEY_APOSTROPHE=40, KEY_GRAVE=41, KEY_LSHIFT=42, KEY_BACKSLASH=43, KEY_Z=44, KEY_X=45, KEY_C=46, KEY_V=47, KEY_B=48, KEY_N=49, KEY_M=50, KEY_COMMA=51, KEY_PERIOD=52, KEY_SLASH=53, KEY_RSHIFT=54, KEY_MULTIPLY=55, KEY_LMENU=56, KEY_SPACE=57, KEY_CAPITAL=58;
   public static final int KEY_F1=59, KEY_F2=60, KEY_F3=61, KEY_F4=62, KEY_F5=63, KEY_F6=64, KEY_F7=65, KEY_F8=66, KEY_F9=67, KEY_F10=68, KEY_NUMLOCK=69, KEY_SCROLL=70, KEY_NUMPAD7=71, KEY_NUMPAD8=72, KEY_NUMPAD9=73, KEY_SUBTRACT=74, KEY_NUMPAD4=75, KEY_NUMPAD5=76, KEY_NUMPAD6=77, KEY_ADD=78, KEY_NUMPAD1=79, KEY_NUMPAD2=80, KEY_NUMPAD3=81, KEY_NUMPAD0=82, KEY_DECIMAL=83, KEY_F11=87, KEY_F12=88, KEY_F13=100, KEY_F14=101, KEY_F15=102, KEY_F16=103, KEY_F17=104, KEY_F18=105, KEY_KANA=112, KEY_F19=113;
   public static final int KEY_CONVERT=121, KEY_NOCONVERT=123, KEY_YEN=125, KEY_NUMPADEQUALS=141, KEY_CIRCUMFLEX=144, KEY_AT=145, KEY_COLON=146, KEY_UNDERLINE=147, KEY_KANJI=148, KEY_STOP=149, KEY_AX=150, KEY_UNLABELED=151, KEY_NUMPADENTER=156, KEY_RCONTROL=157, KEY_SECTION=167, KEY_NUMPADCOMMA=179, KEY_DIVIDE=181, KEY_SYSRQ=183, KEY_RMENU=184, KEY_FUNCTION=196, KEY_PAUSE=197, KEY_HOME=199, KEY_UP=200, KEY_PRIOR=201, KEY_LEFT=203, KEY_RIGHT=205, KEY_END=207, KEY_DOWN=208, KEY_NEXT=209, KEY_INSERT=210, KEY_DELETE=211, KEY_CLEAR=218, KEY_LMETA=219, KEY_LWIN=219, KEY_RMETA=220, KEY_RWIN=220, KEY_APPS=221, KEY_POWER=222, KEY_SLEEP=223, KEYBOARD_SIZE=256;
   private static boolean repeatEvents;
   private static final String[] keyName = new String[256];
   private static final Map<String,Integer> keyMap = new HashMap<>(253);

   public Keyboard() { }
   public static void addKeyEvent(int key, int action) {
      if (pzopt.InputThread.active()) zombie.input.GameKeyboard.pzoptKeyEvent(key, action);
      else zombie.input.GameKeyboard.getEventQueuePolling().addKeyEvent(key, action);
   }
   public static void addCharEvent(char value) {
      if (pzopt.InputThread.active()) zombie.input.GameKeyboard.pzoptCharEvent(value);
      else zombie.input.GameKeyboard.getEventQueuePolling().addCharEvent(value);
   }
   public static void create() { initKeyNames(); }
   public static void initKeyNames() {
      if (pzopt.InputThread.active() && !pzopt.InputThread.isOwnerThread()) {
         pzopt.InputThread.invoke(Keyboard::initKeyNames);
         return;
      }
      Arrays.fill(keyName, null);
      keyMap.clear();
      try {
         for (Field field : Keyboard.class.getFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) && Modifier.isPublic(modifiers) && Modifier.isFinal(modifiers)
                  && field.getType() == Integer.TYPE && field.getName().startsWith("KEY_") && !field.getName().endsWith("WIN")) {
               int key = field.getInt(null);
               String name = field.getName().substring(4);
               int glfwKey = KeyCodes.toGlfwKey(key);
               if (glfwKey >= 320 && glfwKey <= 328) { name = name.replace("NUMPAD", "KP_"); glfwKey = -1; }
               else if (glfwKey >= 330 && glfwKey <= 336) { name = name.replace("NUMPAD", "") + " (Numpad)"; glfwKey = -1; }
               if (glfwKey != -1) {
                  int scancode = GLFW.glfwGetKeyScancode(glfwKey);
                  if (scancode > 0) {
                     String glfwName = GLFW.glfwGetKeyName(glfwKey, 0);
                     if (glfwName != null) name = glfwName.toUpperCase();
                  }
               }
               keyName[key] = name;
               keyMap.put(name, key);
            }
         }
      } catch (Exception exception) { exception.printStackTrace(); }
   }
   public static boolean isKeyDown(int key) {
      int glfwKey = KeyCodes.toGlfwKey(key);
      if (glfwKey == -1) return false;
      if (pzopt.InputThread.active()) return zombie.input.GameKeyboard.pzoptIsKeyDown(key);
      return GLFW.glfwGetKey(Display.getWindow(), glfwKey) == GLFW.GLFW_PRESS;
   }
   public static void poll() { }
   public static void enableRepeatEvents(boolean enabled) { repeatEvents = enabled; }
   public static boolean areRepeatEventsEnabled() { return repeatEvents; }
   public static boolean isRepeatEvent() { return repeatEvents; }
   public static boolean next() { return zombie.input.GameKeyboard.pzoptNextEvent(); }
   public static int getEventKey() { return zombie.input.GameKeyboard.pzoptEventKey(); }
   public static char getEventCharacter() { return zombie.input.GameKeyboard.pzoptEventCharacter(); }
   public static boolean getEventKeyState() { return zombie.input.GameKeyboard.pzoptEventKeyState(); }
   public static long getEventNanoseconds() { return zombie.input.GameKeyboard.pzoptEventNanoseconds(); }
   public static String getKeyName(int key) { return keyName[key]; }
   public static int getKeyIndex(String name) { Integer index = keyMap.get(name); return index == null ? 0 : index; }
   public static boolean isCreated() { return Display.isCreated(); }
   public static void destroy() { }
}
