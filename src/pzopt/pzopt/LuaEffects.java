package pzopt;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import se.krka.kahlua.integration.expose.LuaJavaInvoker;
import se.krka.kahlua.integration.expose.MultiLuaJavaInvoker;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Prototype;
import zombie.Lua.LuaManager;

/**
 * Records one Lua call (lua-stream, 2026-10-25; ideas 4, 5 and 6 and their checkers): what it changed and what it read.
 *
 * <p>It rides on uiVerify's hooks (KahluaTableImpl's constructor / rawset / rawget / len / iterator / wipe and
 * MethodCaller.call; pzopt.UiDeps forwards them here while {@link #recording}), so no hot path gets a new test.
 *
 * <p><b>Effects</b>: a value-changing write into a table that existed before the call (a table the call created is its
 * own local), a Lua -> Java call that is not a getter by uiVerify's name rules or that creates something, a void Java
 * call, a Java call that threw, a Lua builtin with a side effect (print, math.random), an error, and an upvalue the handler
 * reaches whose value the call changed (SETUPVAL has no hook: pzopt.LuaStreamDispatch compares the values before and after).
 *
 * <p><b>Coverage</b> (idea 4): the call read nothing but the square signature pzopt.LuaStreamDispatch hashes (getters of the
 * square, its objects, sprites, property containers, object lists and world items, a few session constants) and Lua
 * values. Anything else, a coordinate, mod data, the cell, another square, a Java object taken out of a Lua table, a Java
 * object used as a table key, tostring / string.format of anything, a clock, makes the call uncovered.
 *
 * <p><b>Reads</b>: every table read with its key and answer (rawget's answer includes the class walk), and every closure the
 * call took out of a table; the dispatcher keeps them per handler and checks them before reusing what it learned.
 */
public final class LuaEffects {
   private LuaEffects() {
   }

   /** Read by UiDeps' hook entry points: forward to this class. Game thread only. */
   public static boolean recording;
   private static Thread thread;
   private static int epoch = 1;
   private static int callDepth;
   /** The call being recorded had an effect / read something outside the signature. */
   public static boolean effect, uncovered;
   public static String why, uncoveredWhy;
   /** The parts of the square signature the call's covered getters read (pzopt.LuaStreamDispatch's C_* bits). */
   static int touched;

   // the call's reads: tables, per table its keys and answers (ITER / LEN as UiDeps' pseudo-keys), closures taken out of tables
   static KahluaTableImpl[] rt = new KahluaTableImpl[32];
   static Object[][] rk = new Object[32][];
   static Object[][] rv = new Object[32][];
   static int[] rkn = new int[32];
   static int rn;
   static LuaClosure[] rc = new LuaClosure[16];
   static int rcn;
   static final int MAX_KEYS = 48; // beyond this a table's keys are not kept: any change to it drops what was learned

   // devLuaLoadOrderCheck (pzopt.LuaOrderCheck): the call's footprint, what a call run in another order could see or change.
   // Tables by identity hash and key hash (WHOLE: the whole table), squares packed (sqKey), the rest in classes: G_READ a
   // read of global state (the cell, players, the clock, a static getter), G_WRITE a write to it, G_RNG a random draw.
   static boolean fp = Config.DEV_LUA_LOAD_ORDER_CHECK; // the unit test sets it
   static final int WHOLE = 0x7fffffff, FP_MAX = 256;
   static final int G_READ = 1, G_WRITE = 2, G_RNG = 4;
   static int[] fpRt = new int[FP_MAX], fpRk = new int[FP_MAX], fpWt = new int[FP_MAX], fpWk = new int[FP_MAX];
   // a key the call read as a number and then wrote as another number (a counter: t.n = t.n + 1): fpRinc / fpWinc
   static Object[] fpRv = new Object[FP_MAX], fpKeyName = new Object[FP_MAX];
   static boolean[] fpRinc = new boolean[FP_MAX], fpWinc = new boolean[FP_MAX];
   static long[] fpRs = new long[FP_MAX], fpWs = new long[FP_MAX];
   static int fpRn, fpWn, fpRsn, fpWsn, fpG;
   static String fpGWhy, fpGReadWhy;

   /** Starts recording one call (false when uiVerify is recording a UI render right now: no nesting). */
   public static boolean begin() {
      if (UiDeps.on || recording) {
         return false;
      }
      thread = Thread.currentThread();
      epoch++;
      callDepth = 0;
      effect = false;
      uncovered = false;
      touched = 0;
      java.util.Arrays.fill(fpRv, 0, fpRn, null);
      java.util.Arrays.fill(fpKeyName, 0, fpWn, null);
      fpRn = fpWn = fpRsn = fpWsn = fpG = 0;
      fpGWhy = fpGReadWhy = null;
      why = null;
      uncoveredWhy = null;
      for (int i = 0; i < rn; i++) {
         rt[i] = null;
         java.util.Arrays.fill(rk[i], 0, Math.min(rkn[i], MAX_KEYS), null);
         java.util.Arrays.fill(rv[i], 0, Math.min(rkn[i], MAX_KEYS), null);
         rkn[i] = 0;
      }
      rn = 0;
      for (int i = 0; i < rcn; i++) {
         rc[i] = null;
      }
      rcn = 0;
      recording = true;
      UiDeps.on = true;
      return true;
   }

   /** Ends the recording. */
   public static void end() {
      recording = false;
      UiDeps.on = false;
   }

   static void mark(String w) {
      if (!effect) {
         effect = true;
         why = w;
      }
   }

   static void uncover(String w) {
      if (!uncovered) {
         uncovered = true;
         uncoveredWhy = w;
      }
   }

   static void created(KahluaTableImpl t) {
      if (Thread.currentThread() == thread) {
         t.pzoptDepBorn = -epoch; // negative: never one of uiVerify's epochs
      }
   }

   /** The born mark of a mod-data table a covered getModData returned in this call: the signature hashes its contents. */
   private static int sigMark() {
      return -epoch - 0x20000000;
   }

   static void write(KahluaTableImpl t) {
      write(t, null);
   }

   /** A value-changing write of {@code key} (null: the whole table, a wipe). */
   static void write(KahluaTableImpl t, Object key) {
      write(t, key, null);
   }

   /** A value-changing write of {@code key} to {@code value} (key null: the whole table, a wipe). */
   static void write(KahluaTableImpl t, Object key, Object value) {
      if (Thread.currentThread() == thread && t.pzoptDepBorn != -epoch) {
         mark("table write");
         if (fp) {
            if (fpWn == FP_MAX) {
               global(G_WRITE, "many table writes");
            } else {
               int id = System.identityHashCode(t), kh = keyHash(key);
               boolean inc = false;
               if (value instanceof Double) {
                  for (int i = 0; i < fpRn; i++) {
                     if (fpRt[i] == id && fpRk[i] == kh && fpRv[i] instanceof Double) {
                        inc = true; // read as a number before, written as one: a counter (order-free if that is all the calls do with it)
                        fpRinc[i] = true;
                        break;
                     }
                  }
               }
               fpWt[fpWn] = id;
               fpWk[fpWn] = kh;
               fpWinc[fpWn] = inc;
               fpKeyName[fpWn++] = key instanceof String ? key : null;
            }
         }
      }
   }

   static int keyHash(Object key) {
      if (key == null || key == UiDeps.ITER || key == UiDeps.LEN) {
         return WHOLE;
      }
      int h = key instanceof String || key instanceof Double || key instanceof Boolean || key instanceof Integer ? key.hashCode() : System.identityHashCode(key);
      return h == WHOLE ? 0 : h;
   }

   static void global(int g, String w) {
      if ((g & G_WRITE) != 0 && (fpG & G_WRITE) == 0) {
         fpGWhy = w;
      }
      if ((g & G_READ) != 0 && (fpG & G_READ) == 0) {
         fpGReadWhy = w;
      }
      fpG |= g;
   }

   static long sqKey(int x, int y, int z) {
      return ((long)x << 36) | ((long)(y & 0xFFFFFF) << 12) | (z + 64 & 0xFFF);
   }

   static void fpSquare(zombie.iso.IsoGridSquare sq, boolean write) {
      if (sq != null) {
         fpSquare(sqKey(sq.getX(), sq.getY(), sq.getZ()), write);
      }
   }

   static void fpSquare(long k, boolean write) {
      long[] a = write ? fpWs : fpRs;
      int n = write ? fpWsn : fpRsn;
      for (int i = 0; i < n; i++) {
         if (a[i] == k) {
            return;
         }
      }
      if (n == FP_MAX) {
         global(write ? G_WRITE : G_READ, write ? "many squares written" : "many squares read");
         return;
      }
      a[n] = k;
      if (write) {
         fpWsn = n + 1;
      } else {
         fpRsn = n + 1;
      }
   }

   /** The square a Java object the call used lives on (null: none / not a world thing). */
   private static zombie.iso.IsoGridSquare squareOf(Object self) {
      if (self instanceof zombie.iso.IsoGridSquare sq) {
         return sq;
      }
      if (self instanceof zombie.iso.IsoObject o) {
         return o.getSquare();
      }
      if (self instanceof zombie.inventory.InventoryItem it) {
         if (it.getWorldItem() != null) {
            return it.getWorldItem().getSquare();
         }
         return it.getContainer() != null && it.getContainer().getParent() != null ? it.getContainer().getParent().getSquare() : null;
      }
      if (self instanceof zombie.inventory.ItemContainer c) {
         return c.getParent() != null ? c.getParent().getSquare() : null;
      }
      return null;
   }

   /** A world object the call reached that has no single square: its state counts as global. */
   private static boolean globalThing(Object self) {
      return self instanceof zombie.iso.IsoCell || self instanceof zombie.iso.IsoWorld || self instanceof zombie.iso.IsoMetaGrid
         || self instanceof zombie.GameTime || self instanceof zombie.iso.IsoChunk || self instanceof zombie.iso.IsoChunkMap
         || self instanceof zombie.iso.areas.IsoBuilding || self instanceof zombie.iso.BuildingDef;
   }

   private static boolean dataThing(Object self) {
      return self instanceof String || self instanceof Enum<?> || self instanceof zombie.iso.sprite.IsoSprite
         || self instanceof zombie.core.properties.PropertyContainer || self instanceof zombie.iso.RoomDef || self instanceof zombie.iso.areas.IsoRoom
         || self instanceof KahluaTable || self instanceof Number || self instanceof Boolean;
   }

   /** The call's footprint of one Java call (devLuaLoadOrderCheck). */
   private static void fpCall(Method method, Object self, Object[] params, boolean eff, Object result) {
      String n = method.getName();
      boolean rng = n.startsWith("ZombRand") || n.startsWith("Rand") || n.contains("random") || n.contains("Random");
      if (rng) {
         global(G_RNG, method.getDeclaringClass().getSimpleName() + "." + n);
      }
      if ((n.equals("getGridSquare") || n.equals("getOrCreateGridSquare")) && params != null && params.length == 3 && params[0] instanceof Number x
            && params[1] instanceof Number y && params[2] instanceof Number z) {
         int bx = 0, by = 0;
         if (self instanceof zombie.iso.IsoChunk ch) {
            bx = ch.wx * 8;
            by = ch.wy * 8;
         }
         fpSquare(sqKey(bx + (int)Math.floor(x.doubleValue()), by + (int)Math.floor(y.doubleValue()), (int)Math.floor(z.doubleValue())), eff); // the square asked for, there or not
         return;
      }
      if (result instanceof zombie.iso.IsoGridSquare r) {
         fpSquare(r, false);
      }
      if (self == null) {
         if (eff && !rng) {
            global(G_WRITE, method.getDeclaringClass().getSimpleName() + "." + n);
         } else if (!eff && !(java.lang.reflect.Modifier.isStatic(method.getModifiers()) && STATIC.contains(n))) {
            global(G_READ, method.getDeclaringClass().getSimpleName() + "." + n);
         }
         return;
      }
      zombie.iso.IsoGridSquare sq = squareOf(self);
      if (sq != null) {
         fpSquare(sq, eff);
      } else if (globalThing(self) || self instanceof zombie.characters.IsoGameCharacter || self instanceof zombie.vehicles.BaseVehicle) {
         global(eff ? G_WRITE : G_READ, self.getClass().getSimpleName() + "." + n);
      } else if (eff && !dataThing(self) && !(self instanceof zombie.iso.IsoObject) && !(self instanceof zombie.inventory.InventoryItem)) {
         global(G_WRITE, self.getClass().getSimpleName() + "." + n); // a list, a map, a manager: whose, not known
      } else if (eff && dataThing(self)) {
         global(G_WRITE, self.getClass().getSimpleName() + "." + n);
      }
      // an object or item on no square (just created by the call), a getter of data: nothing another call can see
   }


   static void read(KahluaTableImpl t, Object key, Object value) {
      if (Thread.currentThread() != thread || t.pzoptDepBorn == -epoch) {
         return;
      }
      if (fp) {
         if (fpRn == FP_MAX) {
            global(G_READ, "many table reads");
         } else {
            int id = System.identityHashCode(t), kh = keyHash(key);
            boolean dup = false;
            for (int i = fpRn - 1; i >= 0 && i >= fpRn - 8; i--) {
               if (fpRt[i] == id && fpRk[i] == kh) {
                  dup = true;
                  break;
               }
            }
            if (!dup) {
               fpRt[fpRn] = id;
               fpRk[fpRn] = kh;
               fpRv[fpRn] = value instanceof Double ? value : null;
               fpRinc[fpRn++] = false;
            }
         }
         if (value instanceof JavaFunction && !(value instanceof LuaJavaInvoker) && !(value instanceof MultiLuaJavaInvoker)) {
            Byte k = FNS.get(value);
            if (k == null || k == FN_UNCOVERED) {
               String vn = String.valueOf(key);
               if (vn.equals("setmetatable")) {
                  global(G_WRITE, "setmetatable");
               } else if (!vn.equals("tostring") && !vn.equals("format")) {
                  global(G_READ, "builtin " + vn);
               }
            } else if (k == FN_EFFECT && !"print".equals(key)) {
               global(G_RNG, "builtin " + key);
            }
         }
      }
      if (t.pzoptDepBorn == sigMark()) {
         // the square's or an object's mod data: its keys and plain values are in the signature, nothing more
         if (value != null && !(value instanceof String) && !(value instanceof Double) && !(value instanceof Boolean)) {
            uncover("mod data value " + value.getClass().getSimpleName());
         }
         if (key != null && key != UiDeps.ITER && key != UiDeps.LEN && !(key instanceof String) && !(key instanceof Double) && !(key instanceof Boolean)) {
            uncover("mod data key " + key.getClass().getSimpleName());
         }
         return;
      }
      if (key != null && !plainValue(key)) {
         uncover("key " + key.getClass().getSimpleName());
      }
      if (value instanceof LuaClosure lc) {
         addClosure(lc);
      } else if (value instanceof JavaFunction && !(value instanceof LuaJavaInvoker) && !(value instanceof MultiLuaJavaInvoker)) {
         Byte k = FNS.get(value);
         if (k == null) {
            uncover("builtin " + value);
         } else if (k == FN_EFFECT) {
            mark("builtin " + value);
         } else if (k == FN_UNCOVERED) {
            uncover("builtin " + value);
         }
      } else if (value != null && !plainValue(value) && !(value instanceof Enum<?>) && !(value instanceof Class<?>) && !(value instanceof JavaFunction)) {
         uncover("java value " + value.getClass().getSimpleName()); // a Java object kept in a Lua table: not this square's
      }
      int slot;
      if (t.pzoptDepMark == -epoch && t.pzoptDepSlot < rn && rt[t.pzoptDepSlot] == t) {
         slot = t.pzoptDepSlot;
      } else {
         if (rn == rt.length) {
            int n = rn * 2;
            rt = java.util.Arrays.copyOf(rt, n);
            rk = java.util.Arrays.copyOf(rk, n);
            rv = java.util.Arrays.copyOf(rv, n);
            rkn = java.util.Arrays.copyOf(rkn, n);
         }
         slot = rn++;
         rt[slot] = t;
         if (rk[slot] == null) {
            rk[slot] = new Object[8];
            rv[slot] = new Object[8];
         }
         rkn[slot] = 0;
         t.pzoptDepMark = -epoch; // uiVerify's marks are log ids (positive): its next recording re-adds the table
         t.pzoptDepSlot = slot;
      }
      int n = rkn[slot];
      if (n > MAX_KEYS) {
         return;
      }
      Object[] ks = rk[slot];
      for (int i = 0; i < n; i++) {
         if (ks[i] == key || ks[i] != null && ks[i].equals(key)) {
            return; // first answer kept: a later read of the same key after a local write is not an input
         }
      }
      if (n == MAX_KEYS) {
         rkn[slot] = MAX_KEYS + 1; // too many: the table counts as a whole
         return;
      }
      if (n == ks.length) {
         rk[slot] = ks = java.util.Arrays.copyOf(ks, n * 2);
         rv[slot] = java.util.Arrays.copyOf(rv[slot], n * 2);
      }
      ks[n] = key;
      rv[slot][n] = value;
      rkn[slot] = n + 1;
   }

   private static boolean plainValue(Object v) {
      return v instanceof String || v instanceof Double || v instanceof Boolean || v instanceof KahluaTable || v instanceof LuaClosure
         || v == UiDeps.ITER || v == UiDeps.LEN || v instanceof Integer;
   }

   private static void addClosure(LuaClosure lc) {
      for (int i = 0; i < rcn; i++) {
         if (rc[i] == lc) {
            return;
         }
      }
      if (rcn == rc.length) {
         rc = java.util.Arrays.copyOf(rc, rcn * 2);
      }
      rc[rcn++] = lc;
   }

   static int enter() {
      if (Thread.currentThread() != thread) {
         return -1;
      }
      return callDepth++;
   }

   static void exit(int d) {
      if (d >= 0) {
         callDepth = d;
      }
   }

   static void call(int d, Method method, Object self, boolean hasResult, Object result) {
      call(d, method, self, null, hasResult, result);
   }

   static void call(int d, Method method, Object self, Object[] params, boolean hasResult, Object result) {
      if (d != 0 || Thread.currentThread() != thread) {
         return;
      }
      String name = method.getName();
      if (fp) {
         fpCall(method, self, params, !hasResult || !UiDeps.isPure(name) || name.contains("Create") || name.startsWith("create") || name.startsWith("getOrAdd"), result);
      }
      if (!hasResult) {
         mark("void " + method.getDeclaringClass().getSimpleName() + "." + name);
      } else if (!UiDeps.isPure(name) || name.contains("Create") || name.startsWith("create") || name.startsWith("getOrAdd")) {
         mark(method.getDeclaringClass().getSimpleName() + "." + name);
      } else if (UiDeps.isClock(name)) {
         uncover("clock " + name);
      } else if (name.equals("getModData") && (method.getDeclaringClass() == zombie.iso.IsoObject.class
            || method.getDeclaringClass() == zombie.iso.IsoGridSquare.class)) {
         touched |= method.getDeclaringClass() == zombie.iso.IsoObject.class ? LuaStreamDispatch.C_OBJS | LuaStreamDispatch.C_OMOD : LuaStreamDispatch.C_SQMOD;
         if (result instanceof KahluaTableImpl t) {
            t.pzoptDepBorn = sigMark(); // created by this call or not: writing it is an effect, reading it is covered by the signature
         } else {
            uncover("getModData " + (result == null ? "null" : result.getClass().getSimpleName()));
         }
      } else if (!covered(method, self)) {
         uncover(method.getDeclaringClass().getSimpleName() + "." + name);
      } else {
         Integer b = PARTS.get(method);
         if (b == null) {
            PARTS.put(method, b = parts(method));
         }
         touched |= b;
      }
   }

   private static final IdentityHashMap<Method, Integer> PARTS = new IdentityHashMap<>();

   /** The signature parts a covered getter's answer follows from (luaLoadSigProject: a handler's signature hashes only these). */
   static int parts(Method m) {
      Class<?> dc = m.getDeclaringClass();
      String n = m.getName();
      if (dc == zombie.iso.IsoGridSquare.class) {
         return switch (n) {
            case "getZ" -> LuaStreamDispatch.C_Z;
            case "getRoom", "isInARoom", "getBuilding" -> LuaStreamDispatch.C_ROOM;
            case "HasTree" -> LuaStreamDispatch.C_TREE;
            case "getObjects", "getFloor" -> LuaStreamDispatch.C_OBJS;
            case "getSpecialObjects" -> LuaStreamDispatch.C_OBJS | LuaStreamDispatch.C_SPECIAL;
            case "getWorldObjects" -> LuaStreamDispatch.C_WORLD;
            case "hasModData" -> LuaStreamDispatch.C_SQMOD;
            case "getDeadBodys" -> LuaStreamDispatch.C_DEAD;
            default -> LuaStreamDispatch.C_PROPS; // getProperties, isOutside, Is, Has, has
         };
      }
      if (dc == zombie.iso.IsoObject.class || dc == zombie.iso.objects.IsoWorldInventoryObject.class) {
         return switch (n) {
            case "getSquare" -> 0;
            case "getObjectIndex" -> LuaStreamDispatch.C_OBJS;
            case "getName" -> LuaStreamDispatch.C_ONAME;
            case "hasModData" -> LuaStreamDispatch.C_OMOD;
            case "getContainerCount" -> LuaStreamDispatch.C_OCONT;
            case "getItem" -> LuaStreamDispatch.C_WORLD;
            default -> LuaStreamDispatch.C_OSPRITE | LuaStreamDispatch.C_ONAME; // sprite, sprite name, texture name, properties, type, object name
         } | LuaStreamDispatch.C_OBJS;
      }
      if (dc == zombie.iso.sprite.IsoSprite.class) {
         return LuaStreamDispatch.C_OSPRITE;
      }
      if (zombie.core.properties.PropertyContainer.class.isAssignableFrom(dc)) {
         return LuaStreamDispatch.C_PROPS | LuaStreamDispatch.C_OSPRITE; // the square's or a sprite's
      }
      if (zombie.inventory.InventoryItem.class.isAssignableFrom(dc)) {
         return LuaStreamDispatch.C_WORLD;
      }
      if (dc == zombie.iso.areas.IsoRoom.class || dc == zombie.iso.RoomDef.class) {
         return LuaStreamDispatch.C_ROOM;
      }
      if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
         return switch (n) {
            case "getNumActivePlayers" -> LuaStreamDispatch.C_PLAYERS;
            case "instof", "instanceof" -> LuaStreamDispatch.C_OBJS | LuaStreamDispatch.C_WORLD; // the class of an object
            default -> 0; // isClient, isServer, getDebug, isCoopHost: constant for the session
         };
      }
      return 0; // lists (their owner's part is set), strings, enums
   }

   static void threw(int d, Method method) {
      if (d == 0 && Thread.currentThread() == thread) {
         mark("throws " + method.getName());
         if (fp) {
            global(G_WRITE, "throws " + method.getName()); // what it did before throwing is not known
         }
      }
   }

   // ---- coverage: the getters whose answers follow from the square signature

   private static final IdentityHashMap<Method, Boolean> COVERED = new IdentityHashMap<>();
   private static final java.util.Set<String> SQUARE = java.util.Set.of("getObjects", "getSpecialObjects", "getWorldObjects", "getZ", "isOutside",
      "getRoom", "getProperties", "Is", "Has", "has", "HasTree", "isInARoom", "getFloor", "hasModData", "getDeadBodys",
      "getBuilding"); // getBuilding: whether there is one (its methods or its use as a key are not covered)
   private static final java.util.Set<String> OBJECT = java.util.Set.of("getSprite", "getSpriteName", "getTextureName", "getName", "getObjectName",
      "getSquare", "getProperties", "getType", "getObjectIndex", "hasModData", "getContainerCount");
   private static final java.util.Set<String> SPRITE = java.util.Set.of("getName", "getProperties", "getType", "getID");
   private static final java.util.Set<String> LIST = java.util.Set.of("size", "get", "isEmpty", "contains", "indexOf");
   private static final java.util.Set<String> ITEM = java.util.Set.of("getFullType", "getType");
   private static final java.util.Set<String> STATIC = java.util.Set.of("isClient", "isServer", "instanceof", "instof", "getDebug", "isCoopHost",
      "getNumActivePlayers"); // instof: a class test, and every covered value's class follows from the signature

   static boolean covered(Method m, Object self) {
      Boolean c = COVERED.get(m);
      if (c == null) {
         c = covered0(m);
         COVERED.put(m, c);
      }
      return c;
   }

   private static boolean covered0(Method m) {
      Class<?> dc = m.getDeclaringClass();
      String n = m.getName();
      if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
         return STATIC.contains(n) && dc.getName().startsWith("zombie.Lua.LuaManager");
      }
      // the declaring class exactly: a subclass's override may read state the signature does not hash
      if (dc == zombie.iso.IsoGridSquare.class) {
         return SQUARE.contains(n);
      }
      if (dc == zombie.iso.IsoObject.class) {
         return OBJECT.contains(n);
      }
      if (dc == zombie.iso.objects.IsoWorldInventoryObject.class) {
         return n.equals("getItem");
      }
      if (dc == zombie.iso.areas.IsoRoom.class) {
         return n.equals("getName") || n.equals("getRoomDef"); // the room's name is in the signature
      }
      if (dc == zombie.iso.RoomDef.class) {
         return n.equals("getName");
      }
      if (dc == zombie.iso.sprite.IsoSprite.class) {
         return SPRITE.contains(n);
      }
      if (zombie.core.properties.PropertyContainer.class.isAssignableFrom(dc)) {
         return UiDeps.isPure(n) || n.equals("Val") || n.equals("propertyEquals");
      }
      if (java.util.Collection.class.isAssignableFrom(dc) || java.util.AbstractList.class.isAssignableFrom(dc)) {
         return LIST.contains(n);
      }
      if (zombie.inventory.InventoryItem.class.isAssignableFrom(dc)) {
         return ITEM.contains(n);
      }
      if (dc == String.class || dc == Object.class && n.equals("equals")) {
         return true;
      }
      return dc.isEnum() && (n.equals("name") || n.equals("ordinal") || n.equals("toString") || n.equals("equals"));
   }

   // ---- Lua builtins by identity (read from the environment once, hooks off)

   static final Byte FN_PURE = 0, FN_EFFECT = 1, FN_UNCOVERED = 2;
   private static final IdentityHashMap<Object, Byte> FNS = new IdentityHashMap<>();
   private static boolean fnsDone;

   /** The Lua builtins' classes, once the environment exists (the dispatcher calls it before recording). */
   static void initFns() {
      if (fnsDone || LuaManager.env == null) {
         return;
      }
      fnsDone = true;
      KahluaTable env = LuaManager.env;
      for (String n : new String[]{"type", "tonumber", "ipairs", "pairs", "select", "rawget", "rawequal", "getmetatable", "assert", "error",
         "pcall", "rawset"}) {
         put(env.rawget(n), FN_PURE); // rawset writes through KahluaTableImpl.rawset: the write hooks see it
      }
      put(env.rawget("print"), FN_EFFECT);
      put(env.rawget("tostring"), FN_UNCOVERED); // an object's identity
      put(env.rawget("setmetatable"), FN_UNCOVERED); // changes a table without a rawset
      put(env.rawget("unpack"), FN_UNCOVERED); // reads past the hooks
      if (env.rawget("string") instanceof KahluaTable s) {
         for (String n : new String[]{"sub", "len", "upper", "lower", "find", "match", "gmatch", "gsub", "byte", "char", "rep", "reverse",
            "trim", "split", "contains", "startsWith", "endsWith"}) {
            put(s.rawget(n), FN_PURE);
         }
         put(s.rawget("format"), FN_UNCOVERED); // %s of an object
      }
      if (env.rawget("math") instanceof KahluaTable m) {
         for (String n : new String[]{"floor", "ceil", "abs", "min", "max", "sqrt", "pow", "fmod", "modf", "huge", "pi", "sin", "cos", "tan",
            "atan", "atan2", "exp", "log", "log10", "deg", "rad", "asin", "acos"}) {
            put(m.rawget(n), FN_PURE);
         }
         put(m.rawget("random"), FN_EFFECT);
         put(m.rawget("randomseed"), FN_EFFECT);
      }
      if (env.rawget("table") instanceof KahluaTable t) {
         for (String n : new String[]{"insert", "remove", "concat", "wipe", "newarray", "pairs", "ipairs"}) {
            put(t.rawget(n), FN_PURE); // their writes go through rawset / wipe: the write hooks see them
         }
         put(t.rawget("isempty"), FN_UNCOVERED); // reads past the hooks
      }
   }

   private static void put(Object f, Byte k) {
      if (f instanceof JavaFunction && k != null) {
         FNS.put(f, k);
      }
   }

   // ---- SETUPVAL (an upvalue assignment) anywhere in a prototype or its nested functions

   private static final int SETUPVAL = 8;
   private static final IdentityHashMap<Prototype, Boolean> PROTO_SETUPVAL = new IdentityHashMap<>();

   static boolean setsUpvalue(Prototype p) {
      if (p == null) {
         return false;
      }
      Boolean b = PROTO_SETUPVAL.get(p);
      if (b == null) {
         b = scanProto(p, 0);
         PROTO_SETUPVAL.put(p, b);
      }
      return b;
   }

   private static boolean scanProto(Prototype p, int depth) {
      if (p == null || depth > 50) {
         return false;
      }
      int[] code = p.code;
      if (code != null) {
         for (int op : code) {
            if ((op & 63) == SETUPVAL) {
               return true;
            }
         }
      }
      if (p.prototypes != null) {
         for (Prototype q : p.prototypes) {
            if (scanProto(q, depth + 1)) {
               return true;
            }
         }
      }
      return false;
   }
}
