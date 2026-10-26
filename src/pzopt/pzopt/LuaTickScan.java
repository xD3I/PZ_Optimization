package pzopt;

import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Prototype;
import se.krka.kahlua.vm.UpValue;

/**
 * luaTickScan (2026-10-25, pzopt.LuaTick): does a Lua event handler do something a player sees or feels the frame it
 * happens: read the keyboard, mouse or pad, place UI or read the screen / camera, draw, move or turn a character or a
 * vehicle, set animation variables? Such a handler keeps running every frame when its event is capped (spacing it out
 * would make input late, the UI jump or a ridden elevator judder). Also kept: a handler that tests its numberTicks
 * argument with a modulo (it throttles itself on the frame count and would miss its frames when called every k-th).
 *
 * <p>The scan reads the handler's Kahlua bytecode: the names it fetches (GETGLOBAL, SELF method names, GETTABLE string
 * keys) and, through a light register tracking, the Lua functions it calls (globals, upvalues, fields of global
 * tables: up to {@link #MAX_DEPTH} calls deep, {@link #MAX_PROTOS} prototypes per handler) and the functions it defines.
 * No control flow: every instruction counts (an over-approximation, the safe side). Result: null (nothing sensitive) or
 * the reason ("input isKeyDown", "ticks modulo", ...). Any failure counts as sensitive.
 */
public final class LuaTickScan {
   private LuaTickScan() {
   }

   static final int MAX_DEPTH = 6; // NRE's fall guard: OnTick closure -> pcall(driver) -> closure -> guardStep -> repin -> pcall(closure) -> setZ
   static final int MAX_PROTOS = 400;

   private static final int OP_MOVE = 0, OP_LOADK = 1, OP_GETUPVAL = 4, OP_GETGLOBAL = 5, OP_GETTABLE = 6, OP_SELF = 11,
         OP_MOD = 16, OP_CALL = 28, OP_TAILCALL = 29, OP_CLOSURE = 36;

   /** Names (methods, globals, fields) that mark a handler as per-frame, by kind. */
   private static final String[][] SENSITIVE = {
      {"input", "isKeyDown", "isKeyPressed", "isKeyPressedImpl", "wasKeyDown", "isMouseButtonDown", "isMouseButtonPressed", "isMouseButtonReleased",
         "getMouseX", "getMouseY", "getMouseXScaled", "getMouseYScaled", "getMouseWheel", "isJoypadPressed", "isJoypadDown",
         "isJoypadLTPressed", "isJoypadRTPressed", "isJoypadLeftPressed", "isJoypadRightPressed", "isJoypadUp", "isJoypadDown",
         "getJoypadAimingAxisX", "getJoypadAimingAxisY", "getJoypadMovementAxisX", "getJoypadMovementAxisY", "getJoypadLeftTriggerAxis",
         "getJoypadRightTriggerAxis", "getJoypadData", "isAButtonPressed", "isBButtonPressed", "isXButtonPressed", "isYButtonPressed",
         "getKeyCode", "isShiftKeyDown", "isCtrlKeyDown", "isAltKeyDown", "GameKeyboard", "Keyboard", "Mouse", "isAimKeyDown",
         "isAiming", "getLastMouseX", "getLastMouseY", "getMouseDX", "getMouseDY"},
      {"screen", "getPlayerScreenLeft", "getPlayerScreenTop", "getPlayerScreenWidth", "getPlayerScreenHeight", "isoToScreenX",
         "isoToScreenY", "screenToIsoX", "screenToIsoY", "getCameraOffX", "getCameraOffY", "IsoCamera", "getOffscreenWidth",
         "getOffscreenHeight", "getZoom", "setZoom", "getScreenWidth", "getScreenHeight"},
      {"ui", "setX", "setY", "setXY", "setWidth", "setHeight", "bringToTop", "addToUIManager", "setVisible", "setAlwaysOnTop",
         "setCapture", "setJoypadFocus", "setFocus", "drawRect", "drawRectBorder", "drawText", "drawTextCentre", "drawTextRight",
         "drawTexture", "drawTextureScaled", "DrawText", "DrawTextCentre", "getTextManager", "render", "prerender", "setHaloNote",
         "addLineChatElement", "Say"},
      {"move", "setLx", "setLy", "setLz", "setNx", "setNy", "setZ", "setPosition", "teleportTo", "setForwardDirection",
         "setDir", "setTargetAndCurrentDirection", "faceLocation", "faceLocationF", "faceThisObject", "setCurrent", "setCurrentSquare",
         "setX", "setY", "setLastX", "setLastY", "setLastZ", "setMoving", "setSprinting", "setRunning", "setSneaking",
         "setVariable", "clearVariable", "setPlayingDeathSound", "playEmote", "setWorldTransform", "setAngles", "setPhysicsActive",
         "setPos", "setPosAndDir", "setIgnoreMovement", "setBlockMovement", "setAuthorizeMeleeAction", "setAnimated"},
   };
   private static final java.util.HashMap<String, String> KIND = new java.util.HashMap<>();
   /** The kinds that pin a handler (luaTickScanKinds). */
   static final Set<String> KINDS = ids(Config.LUA_TICK_SCAN_KINDS);
   static {
      for (String[] g : SENSITIVE) {
         for (int i = 1; i < g.length; i++) {
            KIND.putIfAbsent(g[i], g[0]);
         }
      }
   }

   /**
    * Mods known to need their handlers every frame (reviewed by hand, 2026-10-25), by mod id. A player's
    * luaTickModsCapped overrides an entry; luaTickModsEveryFrame adds to it.
    */
   static final Set<String> KNOWN = new HashSet<>(Arrays.asList(
   ));

   /** The reason handler {@code c} must run every frame, or null. */
   public static String scan(LuaClosure c, KahluaTable env) {
      return scanWithout(c, env, null);
   }

   /** The reason, kind {@code skip} not counted (luaTickInputWake: is input the handler's only reason?). */
   public static String scanWithout(LuaClosure c, KahluaTable env, String skip) {
      try {
         Walk w = new Walk(env);
         w.skip = skip;
         w.closure(c, 0);
         return w.reason;
      } catch (Throwable t) {
         return "scan failed " + t.getClass().getSimpleName();
      }
   }

   /** Test hook: the reason for a bare prototype (no environment, no upvalues). */
   static String scanPrototype(Prototype p) {
      Walk w = new Walk(null);
      w.proto(p, null, 0, true);
      return w.reason;
   }

   private static final class Walk {
      final KahluaTable rootEnv;
      KahluaTable env; // the environment of the closure being read (its globals)
      final IdentityHashMap<Prototype, Boolean> seen = new IdentityHashMap<>();
      String reason;
      String skip; // a kind not counted

      Walk(KahluaTable env) {
         this.rootEnv = env;
         this.env = env;
      }

      void closure(LuaClosure c, int depth) {
         if (c == null || c.prototype == null || reason != null) {
            return;
         }
         KahluaTable saved = env;
         env = c.env != null ? c.env : rootEnv;
         try {
            UpValue[] u = c.upvalues;
            Object[] up = null;
            if (u != null) {
               up = new Object[u.length];
               for (int i = 0; i < u.length; i++) {
                  up[i] = u[i] == null ? null : u[i].getValue();
               }
            }
            proto(c.prototype, up, depth, depth == 0);
         } finally {
            env = saved;
         }
      }

      /** upvalues: the values the prototype's upvalues hold (a closure's, or those a CLOSURE instruction captures), null = unknown. */
      void proto(Prototype p, Object[] upvalues, int depth, boolean handler) {
         if (p == null || reason != null || seen.containsKey(p) || seen.size() >= MAX_PROTOS) {
            return;
         }
         seen.put(p, Boolean.TRUE);
         int[] code = p.code;
         Object[] k = p.constants;
         if (code == null) {
            return;
         }
         Object[] reg = new Object[Math.max(1, p.maxStacksize) + 2];
         for (int pc = 0; pc < code.length && reason == null; pc++) {
            int op = code[pc];
            int opcode = op & 63;
            int a = op >>> 6 & 0xFF;
            int c = op >>> 14 & 0x1FF;
            int b = op >>> 23 & 0x1FF;
            int bx = op >>> 14;
            switch (opcode) {
               case OP_MOVE -> set(reg, a, get(reg, b));
               case OP_LOADK -> set(reg, a, bx < k.length ? k[bx] : null);
               case OP_GETGLOBAL -> {
                  Object name = bx < k.length ? k[bx] : null;
                  name(name);
                  set(reg, a, env != null && name instanceof String s ? env.rawget(s) : null);
               }
               case OP_GETUPVAL -> set(reg, a, upvalues != null && b < upvalues.length ? upvalues[b] : null);
               case OP_GETTABLE -> {
                  Object key = c >= 256 && c - 256 < k.length ? k[c - 256] : get(reg, c);
                  name(key);
                  Object t = get(reg, b);
                  set(reg, a, t instanceof KahluaTable kt && key instanceof String ? kt.rawget(key) : null);
               }
               case OP_SELF -> {
                  Object key = c >= 256 && c - 256 < k.length ? k[c - 256] : get(reg, c);
                  name(key);
                  Object t = get(reg, b);
                  set(reg, a + 1, t);
                  set(reg, a, t instanceof KahluaTable kt && key instanceof String ? kt.rawget(key) : null);
               }
               case OP_MOD -> {
                  // the handler's own first parameter (register 0 = numberTicks / the player) in a modulo: a frame-count throttle
                  if (handler && p.numParams > 0 && (b == 0 || c == 0) && KINDS.contains("ticks") && !"ticks".equals(skip)) {
                     reason = "ticks modulo";
                  }
                  set(reg, a, null);
               }
               case OP_CALL, OP_TAILCALL -> {
                  Object f = get(reg, a);
                  if (depth < MAX_DEPTH) {
                     if (f instanceof LuaClosure lc) {
                        closure(lc, depth + 1);
                     }
                     // Lua functions handed to the call (pcall(driver), xpcall, a mod's own dispatcher) may run too
                     int last = b == 0 ? Math.min(reg.length - 1, a + 8) : Math.min(reg.length - 1, a + b - 1);
                     for (int r = a + 1; r <= last && reason == null; r++) {
                        if (get(reg, r) instanceof LuaClosure arg) {
                           closure(arg, depth + 1);
                        }
                     }
                  }
                  set(reg, a, null);
               }
               case OP_CLOSURE -> {
                  // a function it defines (it may be called, or handed to pcall): read with the values it captures. The
                  // instructions after CLOSURE are its upvalue list (MOVE = a register of ours, GETUPVAL = an upvalue of ours)
                  Prototype child = p.prototypes != null && bx < p.prototypes.length ? p.prototypes[bx] : null;
                  int nu = child == null ? 0 : Math.max(0, child.numUpvalues);
                  Object[] cu = new Object[nu];
                  for (int j = 0; j < nu && pc + 1 + j < code.length; j++) {
                     int ins = code[pc + 1 + j];
                     int ib = ins >>> 23 & 0x1FF;
                     cu[j] = (ins & 63) == OP_MOVE ? get(reg, ib) : (ins & 63) == OP_GETUPVAL && upvalues != null && ib < upvalues.length ? upvalues[ib] : null;
                  }
                  pc += nu;
                  if (child != null && depth < MAX_DEPTH) {
                     proto(child, cu, depth + 1, false);
                  }
                  set(reg, a, null);
               }
               default -> set(reg, a, null);
            }
         }
      }

      void name(Object o) {
         if (o instanceof String s && reason == null) {
            String kind = KIND.get(s);
            if (kind != null && KINDS.contains(kind) && !kind.equals(skip)) {
               reason = kind + " " + s;
            }
         }
      }

      static Object get(Object[] reg, int i) {
         return i >= 0 && i < reg.length ? reg[i] : null;
      }

      static void set(Object[] reg, int i, Object v) {
         if (i >= 0 && i < reg.length) {
            reg[i] = v;
         }
      }
   }

   /** "a,b; c" -> the set of trimmed ids (case kept: mod ids are case sensitive). */
   static Set<String> ids(String csv) {
      Set<String> s = new HashSet<>();
      if (csv == null) {
         return s;
      }
      for (String x : csv.split("[,;]")) {
         String t = x.trim();
         if (!t.isEmpty()) {
            s.add(t.startsWith("mod:") ? t.substring(4) : t);
         }
      }
      return s;
   }

   static String lower(String s) {
      return s == null ? "" : s.toLowerCase(Locale.ROOT);
   }
}
