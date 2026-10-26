package pzopt;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.UpValue;
import zombie.GameProfiler;
import zombie.Lua.Event;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;
import zombie.core.logger.ExceptionLogger;
import zombie.core.properties.PropertyContainer;
import zombie.debug.DebugOptions;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoWorldInventoryObject;

/**
 * The LoadGridsquare event of a chunk joining the world, dispatched handler by handler (lua-stream, 2026-10-25; ideas 4, 5, 6
 * and the per-handler census). With every key off IsoChunk calls the stock {@code LuaEventManager.triggerEvent}; with one on,
 * this class replays Event.trigger's loop (same lock, same handler list walked with stock's remove-while-iterating rule, the
 * same profiler area, the call through LuaEventProfile so the profilers see it) and may leave a handler out:
 *
 * <ul>
 * <li>{@code luaLoadPrefilter} (exact): a handler is skipped on a square whose signature ({@link #sig}) it met before
 * with no effect while it read nothing but the signature and Lua values (pzopt.LuaEffects decides both), and none of what
 * it read changed since: every table it read (its write count, else key by key), every upvalue of every closure it reached.
 * Given the same inputs a Lua function computes the same thing, so it would again do nothing.
 * <li>{@code luaLoadNoopSkip} (risky): the same without the coverage condition, after {@code luaLoadNoopLearn} no-effect
 * calls; a handler that reads a coordinate is then wrong on the one square where it would act.
 * <li>{@code luaLoadBatch}: the squares' events are held during the square loop and run handler by handler afterwards.
 * </ul>
 *
 * One skip in {@code luaLoadNoopSample} runs anyway, recorded (an effect there drops what the handler learned, counted as a
 * mismatch); {@code devLuaLoadCheck} runs every skip that way (the dual-run checker).
 */
public final class LuaStreamDispatch {
   private LuaStreamDispatch() {
   }

   public static final String EVENT = "LoadGridsquare";
   private static final String AREA = "Lua - " + EVENT;
   private static final boolean EXACT = Config.LUA_LOAD_PREFILTER;
   private static final boolean RISKY = Config.LUA_LOAD_NOOP_SKIP;
   private static final boolean SKIP = EXACT || RISKY;
   private static final boolean RECORD_ALL = Config.DEV_LUA_LOAD_EFFECTS;
   private static final boolean CHECK = Config.DEV_LUA_LOAD_CHECK;
   public static final boolean BATCH = Config.LUA_LOAD_BATCH;
   private static final boolean ORDER_CHECK = Config.DEV_LUA_LOAD_ORDER_CHECK; // pzopt.LuaOrderCheck: every call recorded with its footprint
   public static final boolean ON = SKIP || RECORD_ALL || BATCH || ORDER_CHECK || Config.DEV_LUA_STREAM_HANDLERS;
   private static int curPos; // the running handler's position in the event's list (LuaOrderCheck)
   private static final int LEARN = RISKY ? Config.LUA_LOAD_NOOP_LEARN : 1;
   private static final boolean PROJECT = Config.LUA_LOAD_SIG_PROJECT && !RISKY; // risky calls read more than these parts

   // the parts of the square signature (luaLoadSigProject: a handler's signature hashes the parts its covered getters read)
   static final int C_Z = 1, C_ROOM = 2, C_TREE = 4, C_PROPS = 8, C_OBJS = 16, C_OSPRITE = 32, C_ONAME = 64, C_OMOD = 128, C_OCONT = 256,
      C_SPECIAL = 512, C_WORLD = 1024, C_SQMOD = 2048, C_DEAD = 4096, C_PLAYERS = 8192, C_ALL = 16383;

   /** IsoChunk's square step: the LoadGridsquare event of one square. */
   public static void loadGridsquare(IsoGridSquare sq) {
      if (!ON || !Overrides.enabled()) {
         LuaEventManager.triggerEvent(EVENT, sq);
         return;
      }
      if (BATCH && batchChunk != null && Thread.currentThread() == batchThread) {
         batch.add(sq);
         return;
      }
      dispatch(sq, -1);
   }

   // ---- the event's handler list (LuaEventManager.EventMap, private)

   private static HashMap<String, Event> eventMap;
   private static boolean mapFailed;

   @SuppressWarnings("unchecked")
   private static HashMap<String, Event> map() {
      if (eventMap == null && !mapFailed) {
         try {
            Field f = LuaEventManager.class.getDeclaredField("EventMap");
            f.setAccessible(true);
            eventMap = (HashMap<String, Event>)f.get(null);
         } catch (Throwable t) {
            mapFailed = true;
            Log.warn("lua stream dispatch: LuaEventManager.EventMap not found (" + t + "): the stock dispatch runs");
         }
      }
      return eventMap;
   }

   private static final Object[][] ARGS = new Object[8][1];
   private static int argDepth;

   /** One square's handlers: every handler ({@code only} < 0) or the one at index {@code only} (a batch step). */
   private static void dispatch(IsoGridSquare sq, int only) {
      HashMap<String, Event> m = map();
      if (m == null || pzopt.UpdateBatch.onWorkerNow() || LuaManager.thread == null || LuaManager.thread.debugOwnerThread != Thread.currentThread()
            || DebugOptions.instance.checks.slowLuaEvents.getValue() || argDepth == ARGS.length) {
         if (only <= 0) {
            LuaEventManager.triggerEvent(EVENT, sq);
         }
         return;
      }
      synchronized (m) {
         if (LuaManager.env == null) {
            return;
         }
         Event e = m.get(EVENT);
         if (e == null || e.callbacks.isEmpty()) {
            return;
         }
         Object[] a = ARGS[argDepth++];
         a[0] = sq;
         sigDone = false;
         try {
            ArrayList<LuaClosure> cbs = e.callbacks;
            GameProfiler profiler = GameProfiler.getInstance();
            int from = only < 0 ? 0 : only;
            for (int n = from; n < cbs.size() && (only < 0 || n == only); n++) {
               LuaClosure closure = cbs.get(n);
               curPos = n;
               try (GameProfiler.ProfileArea area = profiler.profile(AREA)) {
                  callOne(handler(closure), closure, sq, a);
               } catch (Exception ex) {
                  ExceptionLogger.logException(ex);
               }
               if (!cbs.contains(closure)) {
                  n--;
                  if (only >= 0) {
                     break;
                  }
               }
            }
         } finally {
            a[0] = null;
            argDepth--;
         }
      }
   }

   // ---- per handler

   static final class H {
      final LuaClosure closure;
      final String name;
      long calls, ns, maxNs, skips, recorded, recNs, effects, uncovered, learnedSigs, invalidations, checks, mismatches, notRecordable;
      long upvalueWrites, retries, checksUnknown, maskGrowths;
      int mask = PROJECT ? 0 : C_ALL; // the signature parts its learned calls read
      String effectWhy, uncoveredWhy;
      final HashMap<String, int[]> whyCounts = new HashMap<>();
      boolean never; // stopped learning: (almost) every recorded call had an effect or read outside the signature
      boolean setsUpvalue;
      final LongIntMap learned = new LongIntMap(); // signature -> no-effect calls seen (>= LEARN: skipped), -1: runs always
      // what the learned calls read: tables (their write counts and keys / answers), closures (their upvalues and values)
      KahluaTableImpl[] dt = new KahluaTableImpl[8];
      long[] dw = new long[8];
      Object[][] dk = new Object[8][];
      Object[][] dv = new Object[8][];
      int[] dkn = new int[8];
      int dn;
      final IdentityHashMap<LuaClosure, Boolean> closures = new IdentityHashMap<>();
      UpValue[] uv = new UpValue[8];
      Object[] uvv = new Object[8];
      int un;

      H(LuaClosure c) {
         closure = c;
         se.krka.kahlua.vm.Prototype p = c.prototype;
         String n = p == null ? "?" : p.filename + ":" + (p.lines != null && p.lines.length > 0 ? p.lines[0] : 0) + (p.name != null ? " " + p.name : "");
         name = n;
      }

      void forget() {
         learned.clear();
         for (int i = 0; i < dn; i++) {
            dt[i] = null;
            dk[i] = null;
            dv[i] = null;
         }
         dn = 0;
         closures.clear();
         for (int i = 0; i < un; i++) {
            uv[i] = null;
            uvv[i] = null;
         }
         un = 0;
         invalidations++;
      }
   }

   private static final IdentityHashMap<LuaClosure, H> HANDLERS = new IdentityHashMap<>();
   private static final ArrayList<H> ORDER = new ArrayList<>();

   private static H handler(LuaClosure c) {
      H h = HANDLERS.get(c);
      if (h == null) {
         h = new H(c);
         HANDLERS.put(c, h);
         ORDER.add(h);
      }
      return h;
   }

   public static long skipsTotal, callsTotal, recordedTotal, mismatchesTotal, frameSkips;

   private static void callOne(H h, LuaClosure closure, IsoGridSquare sq, Object[] a) {
      callsTotal++;
      if (SKIP && !h.never && LuaStreamAb.on(LuaStreamAb.PREFILTER)) { // the A/B's off half: every handler runs, unrecorded
         long s = sig(sq, h.mask);
         int seen = h.learned.get(s);
         if (seen >= LEARN && valid(h)) {
            h.skips++;
            skipsTotal++;
            frameSkips++;
            if (CHECK || h.skips % Config.LUA_LOAD_NOOP_SAMPLE == 0) {
               h.checks++;
               boolean effect = recordCall(h, closure, sq, a);
               if (!effect && lastRetry) {
                  h.checksUnknown++;
               } else if (!effect && (LuaEffects.touched & ~h.mask) != 0 && !LuaEffects.uncovered) {
                  grow(h); // a path the learned calls never took: impossible with equal parts but for a hash collision
                  h.checksUnknown++;
               } else if (effect || EXACT && !RISKY && LuaEffects.uncovered) {
                  sigDone = false;
                  h.mismatches++;
                  mismatchesTotal++;
                  if (h.mismatches <= 20) {
                     Log.warn("lua load check: " + h.name + " skipped on square " + sq.getX() + "," + sq.getY() + "," + sq.getZ() + " but "
                           + (effect ? "had an effect (" + LuaEffects.why + ")" : "read " + LuaEffects.uncoveredWhy) + "; " + h.mismatches + " so far");
                  }
                  h.forget();
                  h.learned.put(s, -1);
               }
            }
            return;
         }
         if (seen >= 0) {
            boolean effect = recordCall(h, closure, sq, a);
            if (effect) {
               sigDone = false; // the square may have changed
            }
            if ((LuaEffects.touched & ~h.mask) != 0) {
               grow(h); // the call read a part the signature left out so far: what was learned is keyed without it
               seen = 0;
               s = sig(sq, h.mask); // after an effect from the square as it is now: a key for "always runs" either way
            }
            if (effect) {
               h.learned.put(s, -1);
            } else if (lastRetry) {
               // a closure first met in this call may have assigned an upvalue unseen: neither learned nor ruled out
            } else if (LuaEffects.uncovered && !RISKY) {
               h.learned.put(s, -1);
            } else {
               if ((h.dn > 0 || h.un > 0) && !valid(h)) {
                  seen = 0; // what the earlier no-effect calls read has changed since: all of it forgotten
               }
               merge(h);
               h.learned.put(s, seen + 1);
               if (seen + 1 == LEARN) {
                  h.learnedSigs++;
               }
            }
            if (h.recorded >= 256 && h.learnedSigs * 64 < h.recorded && h.skips * 16 < h.recorded) {
               h.never = true; // learning does not pay for this handler
               Log.info("lua stream dispatch: " + h.name + " stops learning after " + h.recorded + " recorded calls (effects " + h.effects
                     + ", reads beyond the signature " + h.uncovered + ", " + h.uncoveredWhy + ")");
            }
            if (h.learned.size() > 200_000) {
               h.learned.clear();
            }
            return;
         }
      } else if (RECORD_ALL || ORDER_CHECK) {
         recordCall(h, closure, sq, a);
         sigDone = false;
         return;
      }
      long t0 = System.nanoTime();
      LuaEventProfile.call(LuaManager.caller, closure, a, EVENT);
      sigDone = false; // a handler not recorded may have changed the square
      long ns = System.nanoTime() - t0;
      h.calls++;
      h.ns += ns;
      if (ns > h.maxNs) {
         h.maxNs = ns;
      }
   }

   /** Runs the handler with pzopt.LuaEffects recording; true when it had an effect. */
   private static boolean recordCall(H h, LuaClosure closure, IsoGridSquare sq, Object[] a) {
      LuaEffects.initFns();
      if (!LuaEffects.begin()) {
         h.notRecordable++;
         LuaEventProfile.call(LuaManager.caller, closure, a, EVENT);
         return true;
      }
      // the upvalues the handler reaches (through the closures in its upvalues and those met in earlier calls): their values
      // before the call, to see an upvalue assignment (SETUPVAL has no hook) by what it changed
      addClosure(h, closure, 0);
      int un0 = h.un;
      if (PRE.length < un0) {
         PRE = new Object[un0 * 2];
      }
      for (int i = 0; i < un0; i++) {
         PRE[i] = h.uv[i].getValue();
      }
      lastRetry = false;
      long t0 = System.nanoTime();
      Object[] r;
      try {
         r = LuaManager.caller.pcall(LuaManager.thread, closure, sq);
      } finally {
         LuaEffects.end();
      }
      for (int i = 0; i < un0; i++) {
         if (!sameValue(h.uv[i].getValue(), PRE[i])) {
            LuaEffects.mark("upvalue write");
            if (ORDER_CHECK) {
               LuaEffects.global(LuaEffects.G_WRITE, "upvalue write");
            }
            h.upvalueWrites++;
            break;
         }
      }
      java.util.Arrays.fill(PRE, 0, un0, null);
      for (int i = 0; i < LuaEffects.rcn; i++) {
         LuaClosure c = LuaEffects.rc[i];
         if (!h.closures.containsKey(c)) {
            int before = h.un;
            boolean sets = h.setsUpvalue;
            h.setsUpvalue = false;
            addClosure(h, c, 0); // from now on its upvalues are watched too
            if (h.setsUpvalue || LuaEffects.setsUpvalue(c.prototype) || before != h.un && reachesSetupval(h, before)) {
               lastRetry = true;
            }
            h.setsUpvalue |= sets;
         }
      }
      long ns = System.nanoTime() - t0;
      h.calls++;
      h.ns += ns;
      h.recNs += ns;
      if (ns > h.maxNs) {
         h.maxNs = ns;
      }
      h.recorded++;
      recordedTotal++;
      if (r == null || r.length == 0 || r[0] != Boolean.TRUE) {
         LuaEffects.mark("error " + (r != null && r.length > 1 ? r[1] : "?"));
      }
      if (!h.setsUpvalue && LuaEffects.setsUpvalue(closure.prototype)) {
         h.setsUpvalue = true; // census only: code that can assign an upvalue (what it did is checked above)
      }
      if (lastRetry && !LuaEffects.effect) {
         h.retries++;
      }
      if (LuaEffects.effect) {
         h.effects++;
         h.effectWhy = LuaEffects.why;
         count(h, "effect: " + LuaEffects.why);
      } else if (LuaEffects.uncovered) {
         h.uncovered++;
         h.uncoveredWhy = LuaEffects.uncoveredWhy;
         count(h, "read: " + LuaEffects.uncoveredWhy);
      }
      if (ORDER_CHECK) {
         if (lastRetry) {
            LuaEffects.global(LuaEffects.G_WRITE, "a closure met first may have set an upvalue");
         }
         if (r == null || r.length == 0 || r[0] != Boolean.TRUE) {
            LuaEffects.global(LuaEffects.G_WRITE, "error");
         }
         LuaOrderCheck.add(sq.chunk, sq, curPos, h.name, false);
      }
      return LuaEffects.effect;
   }

   private static String protoName(LuaClosure c) {
      se.krka.kahlua.vm.Prototype p = c.prototype;
      return p == null ? "?" : p.filename + ":" + (p.lines != null && p.lines.length > 0 ? p.lines[0] : 0) + (p.name != null ? " " + p.name : "");
   }

   private static void count(H h, String why) {
      String k = why.length() > 120 ? why.substring(0, 120) : why;
      int[] c = h.whyCounts.get(k);
      if (c == null) {
         if (h.whyCounts.size() > 64) {
            return;
         }
         h.whyCounts.put(k, c = new int[1]);
      }
      c[0]++;
   }

   // ---- what a handler read: kept, checked before a skip

   private static long tableVersion(KahluaTableImpl t) {
      return t.pzoptWrites * 1_000_003L + UiDeps.chainWrites(t);
   }

   /** Adds the last recorded call's reads to the handler's (they are current: the call just made them). */
   static void merge(H h) {
      for (int i = 0; i < LuaEffects.rn; i++) {
         KahluaTableImpl t = LuaEffects.rt[i];
         int j = 0;
         while (j < h.dn && h.dt[j] != t) {
            j++;
         }
         int kn = LuaEffects.rkn[i];
         if (j == h.dn) {
            if (h.dn == h.dt.length) {
               int n = h.dn * 2;
               h.dt = java.util.Arrays.copyOf(h.dt, n);
               h.dw = java.util.Arrays.copyOf(h.dw, n);
               h.dk = java.util.Arrays.copyOf(h.dk, n);
               h.dv = java.util.Arrays.copyOf(h.dv, n);
               h.dkn = java.util.Arrays.copyOf(h.dkn, n);
            }
            h.dt[j] = t;
            h.dw[j] = tableVersion(t);
            h.dk[j] = new Object[Math.max(4, Math.min(kn, LuaEffects.MAX_KEYS))];
            h.dv[j] = new Object[h.dk[j].length];
            h.dkn[j] = 0;
            h.dn++;
         } else {
            h.dw[j] = tableVersion(t); // valid() ran before: what changed since did not touch the keys read
         }
         if (h.dkn[j] > LuaEffects.MAX_KEYS) {
            continue;
         }
         if (kn > LuaEffects.MAX_KEYS) {
            h.dkn[j] = LuaEffects.MAX_KEYS + 1;
            continue;
         }
         Object[] ks = LuaEffects.rk[i];
         Object[] vs = LuaEffects.rv[i];
         for (int k = 0; k < kn; k++) {
            Object key = ks[k];
            int n = h.dkn[j];
            boolean found = false;
            for (int q = 0; q < n; q++) {
               if (h.dk[j][q] == key || h.dk[j][q] != null && h.dk[j][q].equals(key)) {
                  found = true;
                  break;
               }
            }
            if (found) {
               continue;
            }
            if (n == LuaEffects.MAX_KEYS) {
               h.dkn[j] = LuaEffects.MAX_KEYS + 1;
               break;
            }
            if (n == h.dk[j].length) {
               h.dk[j] = java.util.Arrays.copyOf(h.dk[j], n * 2);
               h.dv[j] = java.util.Arrays.copyOf(h.dv[j], n * 2);
            }
            h.dk[j][n] = key;
            h.dv[j][n] = vs[k];
            h.dkn[j] = n + 1;
         }
      }
      // closures: the handler, those taken out of tables, and (transitively) those held in their upvalues
      addClosure(h, h.closure, 0);
      for (int i = 0; i < LuaEffects.rcn; i++) {
         addClosure(h, LuaEffects.rc[i], 0);
      }
   }

   private static void addClosure(H h, LuaClosure c, int depth) {
      if (c == null || depth > 8 || h.closures.put(c, Boolean.TRUE) != null) {
         return;
      }
      if (c.upvalues == null) {
         return;
      }
      for (UpValue u : c.upvalues) {
         if (u == null) {
            continue;
         }
         if (h.un == h.uv.length) {
            h.uv = java.util.Arrays.copyOf(h.uv, h.un * 2);
            h.uvv = java.util.Arrays.copyOf(h.uvv, h.un * 2);
         }
         Object v = u.getValue();
         h.uv[h.un] = u;
         h.uvv[h.un] = v;
         h.un++;
         if (v instanceof LuaClosure lc) {
            if (LuaEffects.setsUpvalue(lc.prototype)) {
               h.setsUpvalue = true;
            }
            addClosure(h, lc, depth + 1);
         }
      }
   }

   private static final Object ABSENT = new Object();

   private static Object[] PRE = new Object[64];
   /** The last recorded call met a closure whose upvalues had no snapshot before it (and that can assign one). */
   static boolean lastRetry;

   static boolean sameValue(Object now, Object was) {
      return now == was || now instanceof Double d && d.equals(was) || now instanceof String s && s.equals(was)
         || now instanceof Boolean b && b.equals(was);
   }

   /** True when a closure added from index {@code from} of the handler's upvalue list on can assign an upvalue. */
   private static boolean reachesSetupval(H h, int from) {
      for (int i = from; i < h.un; i++) {
         if (h.uvv[i] instanceof LuaClosure lc && LuaEffects.setsUpvalue(lc.prototype)) {
            return true;
         }
      }
      return false;
   }

   private static void grow(H h) {
      h.mask |= LuaEffects.touched;
      h.learned.clear();
      h.maskGrowths++;
   }

   /** True when nothing the handler's learned calls read has changed; else forgets what it learned. */
   static boolean valid(H h) {
      for (int i = 0; i < h.un; i++) {
         Object now = h.uv[i].getValue();
         Object was = h.uvv[i];
         if (!sameValue(now, was)) {
            count(h, "forgot: upvalue changed (" + (now == null ? "nil" : now.getClass().getSimpleName()) + ")");
            h.forget();
            return false;
         }
      }
      for (int i = 0; i < h.dn; i++) {
         KahluaTableImpl t = h.dt[i];
         long v = tableVersion(t);
         if (v == h.dw[i]) {
            continue;
         }
         if (h.dkn[i] > LuaEffects.MAX_KEYS || !sameKeys(t, h.dk[i], h.dv[i], h.dkn[i])) {
            count(h, h.dkn[i] > LuaEffects.MAX_KEYS ? "forgot: a table read whole (>" + LuaEffects.MAX_KEYS + " keys) changed"
                  : hasIter(h.dk[i], h.dkn[i]) ? "forgot: a table walked (pairs / next) changed" : "forgot: a key read changed");
            h.forget();
            return false;
         }
         h.dw[i] = v;
      }
      return true;
   }

   private static boolean hasIter(Object[] ks, int n) {
      for (int k = 0; k < n; k++) {
         if (ks[k] == UiDeps.ITER) {
            return true;
         }
      }
      return false;
   }

   private static boolean sameKeys(KahluaTableImpl t, Object[] ks, Object[] vs, int n) {
      boolean wasOn = UiDeps.on;
      UiDeps.on = false; // our own reads are not a UI render's
      try {
         for (int k = 0; k < n; k++) {
            Object key = ks[k];
            Object now;
            if (key == UiDeps.ITER) {
               return false; // a walk over the table: any change counts
            } else if (key == UiDeps.LEN) {
               now = t.len();
            } else {
               now = t.rawget(key);
            }
            Object was = vs[k];
            if (now != was && (now == null || was == null || !UiDeps.safeEquals(now, was))) {
               return false;
            }
         }
         return true;
      } finally {
         UiDeps.on = wasOn;
      }
   }

   // ---- the square signature: everything a covered getter can answer

   private static boolean sigDone;
   private static final int[] SIG_MASK = new int[8];
   private static final long[] SIG_VALUE = new long[8];
   private static int sigN;
   private static Field pcFlags1, pcFlags2, pcKeys;
   private static boolean pcFailed;

   /** The square's signature over the parts in {@code mask} (cached per mask until the square may have changed). */
   static long sig(IsoGridSquare sq, int mask) {
      if (sigDone) {
         for (int i = 0; i < sigN; i++) {
            if (SIG_MASK[i] == mask) {
               return SIG_VALUE[i];
            }
         }
      } else {
         sigN = 0;
         sigDone = true;
      }
      long h = 0x9E3779B97F4A7C15L ^ mask;
      if ((mask & C_Z) != 0) {
         h = mix(h, sq.getZ());
      }
      if ((mask & C_TREE) != 0) {
         h = mix(h, sq.HasTree() ? 2 : 1);
      }
      if ((mask & C_ROOM) != 0) {
         h = mix(h, sq.getRoom() == null ? 0 : 1 + str(sq.getRoom().getName())); // IsoRoom.getName / getRoomDef().getName()
         h = mix(h, sq.getBuilding() != null ? 1 : 0); // getBuilding: only whether there is one
      }
      if ((mask & C_PLAYERS) != 0) {
         h = mix(h, zombie.characters.IsoPlayer.numPlayers); // getNumActivePlayers
      }
      if ((mask & C_SQMOD) != 0) {
         h = mix(h, sq.hasModData() ? modData(sq.getModData()) : 0);
      }
      if ((mask & C_DEAD) != 0) {
         h = mix(h, deadBodies(sq));
      }
      if ((mask & C_PROPS) != 0) {
         h = mix(h, props(sq.getProperties()));
      }
      if ((mask & (C_OBJS | C_SPECIAL)) != 0) {
         var objs = sq.getObjects();
         int n = objs.size();
         h = mix(h, n);
         for (int i = 0; i < n; i++) {
            h = object(h, (IsoObject)objs.get(i), mask);
         }
      }
      if ((mask & C_SPECIAL) != 0) {
         var special = sq.getSpecialObjects();
         h = mix(h, special == null ? -1 : special.size());
      }
      if ((mask & C_WORLD) != 0) {
         var world = sq.getWorldObjects();
         int wn = world == null ? 0 : world.size();
         h = mix(h, wn);
         for (int i = 0; i < wn; i++) {
            IsoWorldInventoryObject w = world.get(i);
            h = object(h, w, mask | C_OSPRITE | C_ONAME);
            h = mix(h, w == null || w.getItem() == null ? 0 : str(w.getItem().getFullType()));
            h = mix(h, w == null || w.getItem() == null ? 0 : str(w.getItem().getType()));
         }
      }
      if (sigN < SIG_MASK.length) {
         SIG_MASK[sigN] = mask;
         SIG_VALUE[sigN] = h;
         sigN++;
      }
      return h;
   }

   private static long object(long h, IsoObject o, int mask) {
      if (o == null) {
         return mix(h, 7);
      }
      h = mix(h, o.getClass().getName().hashCode()); // every call on an object depends on its class
      if ((mask & C_OSPRITE) != 0) {
         h = mix(h, o.sprite == null ? 0 : str(o.sprite.name));
         h = mix(h, str(o.spriteName));
      }
      if ((mask & C_ONAME) != 0) {
         h = mix(h, str(o.name));
      }
      if ((mask & C_OMOD) != 0) {
         h = mix(h, o.hasModData() ? modData(o.getModData()) : 0);
      }
      if ((mask & C_OCONT) != 0) {
         h = mix(h, o.getContainerCount());
      }
      return h;
   }

   private static int deadBodies(IsoGridSquare sq) {
      var list = sq.getStaticMovingObjects();
      int n = 0;
      for (int i = 0, m = list == null ? 0 : list.size(); i < m; i++) {
         if (list.get(i) instanceof zombie.iso.objects.IsoDeadBody) {
            n++;
         }
      }
      return n;
   }

   /** The mod data's keys and plain values (order-free); a table or Java value inside counts by kind only (reading one is uncovered). */
   static int modData(se.krka.kahlua.vm.KahluaTable t) {
      if (!(t instanceof KahluaTableImpl ti)) {
         return 0x0DD;
      }
      int sum = 0x3A7A + ti.delegate.size();
      for (var e : ti.delegate.entrySet()) {
         Object k = e.getKey();
         Object v = e.getValue();
         int kh = k == null ? 0 : k instanceof String || k instanceof Double || k instanceof Boolean ? k.hashCode() : k.getClass().getName().hashCode();
         int vh = v == null ? 0 : v instanceof String || v instanceof Double || v instanceof Boolean ? v.hashCode() : 0x7AB1E ^ v.getClass().getName().hashCode();
         sum += kh * 0x01000193 ^ vh;
      }
      return sum;
   }

   private static int str(String s) {
      return s == null ? 0x51ED : s.hashCode();
   }

   private static long props(PropertyContainer pc) {
      if (pc == null) {
         return 3;
      }
      if (pcFlags1 == null && !pcFailed) {
         try {
            pcFlags1 = PropertyContainer.class.getDeclaredField("spriteFlags1");
            pcFlags2 = PropertyContainer.class.getDeclaredField("spriteFlags2");
            pcKeys = PropertyContainer.class.getDeclaredField("keyArray");
            pcFlags1.setAccessible(true);
            pcFlags2.setAccessible(true);
            pcKeys.setAccessible(true);
         } catch (Throwable t) {
            pcFailed = true;
         }
      }
      if (pcFailed) {
         return System.identityHashCode(pc) * 31L + System.nanoTime(); // unknown layout: no two squares share a signature
      }
      try {
         long h = mix(pcFlags1.getLong(pc), pcFlags2.getLong(pc));
         short[] k = (short[])pcKeys.get(pc);
         if (k != null) {
            for (short s : k) {
               h = mix(h, s);
            }
         }
         return h;
      } catch (Throwable t) {
         return System.nanoTime();
      }
   }

   private static long mix(long h, long v) {
      h ^= v + 0x9E3779B97F4A7C15L + (h << 6) + (h >>> 2);
      h *= 0xBF58476D1CE4E5B9L;
      return h ^ (h >>> 31);
   }

   // ---- luaLoadBatch: the chunk's squares held during its square loop, then handler by handler

   private static IsoChunk batchChunk;
   private static Thread batchThread;
   private static final ArrayList<IsoGridSquare> batch = new ArrayList<>();
   private static int batchHandler, batchSquare;
   public static long batchChunks, batchSquares;

   /** IsoChunk.pzoptLoadPre's end: the square loop of {@code c} starts. */
   public static void batchBegin(IsoChunk c) {
      if (!BATCH || !Overrides.enabled()) {
         return;
      }
      if (batchChunk != null && batchChunk != c) {
         batchFlushAll(); // a chunk handed off inside another's loop: the first one's held events first
      }
      batchChunk = c;
      batchThread = Thread.currentThread();
      batch.clear();
      batchHandler = 0;
      batchSquare = 0;
   }

   /** True while the held events of the square loop just ended still have to run. */
   public static boolean batchPending() {
      return batchChunk != null;
   }

   /** One handler over every held square (or the rest of one when {@code deadlineNs} passes); true when all ran. */
   public static boolean batchStep(long deadlineNs) {
      if (batchChunk == null) {
         return true;
      }
      HashMap<String, Event> m = map();
      Event e;
      if (m == null) {
         e = null;
      } else {
         synchronized (m) {
            e = m.get(EVENT);
         }
      }
      int handlers = e == null ? 0 : e.callbacks.size();
      IsoChunk c = batchChunk;
      batchChunk = null; // events from now on dispatch at once (a handler that loads squares)
      try {
         if (e == null) {
            for (IsoGridSquare sq : batch) {
               LuaEventManager.triggerEvent(EVENT, sq);
            }
            batchHandler = Integer.MAX_VALUE;
         }
         while (batchHandler < handlers) {
            while (batchSquare < batch.size()) {
               sigDone = false;
               dispatch(batch.get(batchSquare++), batchHandler);
               if (deadlineNs != 0L && System.nanoTime() >= deadlineNs) {
                  return finishOrHold(c);
               }
            }
            batchSquare = 0;
            batchHandler++;
            handlers = e.callbacks.size();
         }
      } catch (Throwable t) {
         ExceptionLogger.logException(t);
      }
      batchChunks++;
      batchSquares += batch.size();
      batch.clear();
      batchHandler = 0;
      batchSquare = 0;
      return true;
   }

   private static boolean finishOrHold(IsoChunk c) {
      batchChunk = c;
      return false;
   }

   /** IsoChunk.pzoptLoadMid's start (and a nested chunk): every held event runs now. */
   public static void batchFlushAll() {
      while (!batchStep(0L)) {
      }
   }

   // ---- luaLoadChunkSplit: a streamed chunk's LoadChunk handlers, one per hand-off step

   public static final String CHUNK_EVENT = "LoadChunk";
   private static final String CHUNK_AREA = "Lua - " + CHUNK_EVENT;
   /** Set by LuaStream around the last part of the chunk it steps: that chunk's LoadChunk is held for chunkStep. */
   public static IsoChunk holdChunk;
   private static IsoChunk pendingChunk;
   static int chunkCursor;
   public static long chunkSteps;

   /** IsoChunk.pzoptLoadEnd: the LoadChunk event. */
   public static void loadChunk(IsoChunk c) {
      if (c != null && c == holdChunk) {
         pendingChunk = c;
         chunkCursor = 0;
         return;
      }
      LuaStream.ready(c);
      LuaEventManager.triggerEvent(CHUNK_EVENT, c);
   }

   /** The LoadChunk handler count (0 without the event map or the event). */
   public static int chunkHandlers() {
      HashMap<String, Event> m = map();
      if (m == null) {
         return 0;
      }
      synchronized (m) {
         Event e = m.get(CHUNK_EVENT);
         return e == null ? 0 : e.callbacks.size();
      }
   }

   /** Runs the held chunk's next LoadChunk handler (stock's loop, one turn of it); true when none is left. */
   public static boolean chunkStep(IsoChunk c) {
      if (pendingChunk != c || c == null) {
         return true;
      }
      HashMap<String, Event> m = map();
      if (m == null || LuaManager.thread == null || LuaManager.thread.debugOwnerThread != Thread.currentThread()) {
         pendingChunk = null;
         LuaEventManager.triggerEvent(CHUNK_EVENT, c);
         return true;
      }
      synchronized (m) {
         Event e = LuaManager.env == null ? null : m.get(CHUNK_EVENT);
         if (e == null || chunkCursor >= e.callbacks.size()) {
            pendingChunk = null;
            return true;
         }
         ArrayList<LuaClosure> cbs = e.callbacks;
         LuaClosure closure = cbs.get(chunkCursor);
         Object[] a = ARGS[argDepth++];
         a[0] = c;
         try (GameProfiler.ProfileArea area = GameProfiler.getInstance().profile(CHUNK_AREA)) {
            if (ORDER_CHECK && LuaEffects.begin()) { // the order check: the LoadChunk handler's footprint (upvalues not compared here)
               LuaEffects.initFns();
               Object[] r;
               try {
                  r = LuaManager.caller.pcall(LuaManager.thread, closure, c);
               } finally {
                  LuaEffects.end();
               }
               if (r == null || r.length == 0 || r[0] != Boolean.TRUE) {
                  LuaEffects.global(LuaEffects.G_WRITE, "error");
               }
               LuaOrderCheck.add(c, null, chunkCursor, protoName(closure), true);
            } else {
               LuaEventProfile.call(LuaManager.caller, closure, a, CHUNK_EVENT);
            }
         } catch (Exception ex) {
            ExceptionLogger.logException(ex);
         } finally {
            a[0] = null;
            argDepth--;
         }
         chunkSteps++;
         if (cbs.contains(closure)) {
            chunkCursor++; // stock's n-- after a handler removed itself, then n++: the same index again
         }
         if (chunkCursor >= cbs.size()) {
            pendingChunk = null;
            return true;
         }
         return false;
      }
   }

   // ---- census (BenchRecorder: <step>-luahandlers.csv; console at the end)

   /** BenchRecorder.start: counters from zero (what the handlers learned is kept). */
   static void resetCounters() {
      for (H h : ORDER) {
         h.calls = h.ns = h.maxNs = h.skips = h.recorded = h.recNs = h.effects = h.uncovered = h.checks = h.mismatches = 0L;
         h.invalidations = h.notRecordable = h.upvalueWrites = h.retries = h.checksUnknown = h.maskGrowths = 0L;
         h.whyCounts.clear();
      }
      skipsTotal = callsTotal = recordedTotal = mismatchesTotal = batchChunks = batchSquares = 0L;
      LuaStreamMapObjects.squares = LuaStreamMapObjects.candidates = LuaStreamMapObjects.mismatches = 0L;
      LuaStreamMapObjects.fastNs = LuaStreamMapObjects.stockNs = 0L;
   }

   static String maskName(int m) {
      if (m == C_ALL) {
         return "all";
      }
      StringBuilder b = new StringBuilder();
      String[] names = {"z", "room", "tree", "props", "objs", "sprite", "name", "objmod", "containers", "special", "world", "sqmod", "dead", "players"};
      for (int i = 0; i < names.length; i++) {
         if ((m & 1 << i) != 0) {
            b.append(b.length() == 0 ? "" : "+").append(names[i]);
         }
      }
      return b.length() == 0 ? "none" : b.toString();
   }

   static void writeCsv(java.io.File f) {
      if (Config.MAP_OBJECTS_FAST) {
         Log.info("map objects fast: squares " + LuaStreamMapObjects.squares + " with a registered sprite " + LuaStreamMapObjects.candidates
               + (Config.DEV_MAP_OBJECTS_CHECK ? " check mismatches " + LuaStreamMapObjects.mismatches + ", decision ms fast " + LuaStreamMapObjects.fastNs / 1_000_000
                  + " stock walk " + LuaStreamMapObjects.stockNs / 1_000_000 : " (check off)"));
      }
      if (ORDER.isEmpty()) {
         return;
      }
      ArrayList<H> hs = new ArrayList<>(ORDER);
      hs.sort((x, y) -> Long.compare(y.ns, x.ns));
      try (java.io.PrintWriter w = new java.io.PrintWriter(new java.io.BufferedWriter(new java.io.FileWriter(f)))) {
         w.println("handler\tcalls\tms\tmean_us\tmax_us\trecorded\trecorded_ms\teffects\tuncovered\tlearned_sigs\tskips\tchecks\tmismatches\tinvalidations\tnever\tsets_upvalue\tupvalue_writes\tretries\tchecks_unknown\tmask\tmask_growths\ttop_reasons");
         for (H h : hs) {
            StringBuilder why = new StringBuilder();
            h.whyCounts.entrySet().stream().sorted((x, y) -> y.getValue()[0] - x.getValue()[0]).limit(4)
                  .forEach(en -> why.append(en.getKey().replace('\t', ' ')).append(" x").append(en.getValue()[0]).append("; "));
            w.printf(java.util.Locale.ROOT, "%s\t%d\t%.3f\t%.3f\t%.1f\t%d\t%.3f\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%b\t%b\t%d\t%d\t%d\t%s\t%d\t%s%n", h.name, h.calls, h.ns / 1e6,
                  h.calls == 0 ? 0.0 : h.ns / 1e3 / h.calls, h.maxNs / 1e3, h.recorded, h.recNs / 1e6, h.effects, h.uncovered, h.learnedSigs, h.skips, h.checks,
                  h.mismatches, h.invalidations, h.never, h.setsUpvalue, h.upvalueWrites, h.retries, h.checksUnknown, maskName(h.mask), h.maskGrowths, why);
         }
      } catch (Throwable t) {
         Log.warn("lua stream dispatch: could not write " + f + ": " + t);
      }
      Log.info("lua stream dispatch: handlers " + hs.size() + " calls " + callsTotal + " skips " + skipsTotal + " recorded " + recordedTotal + " mismatches "
            + mismatchesTotal + " batch chunks " + batchChunks);
   }

   /** A long -> int map (open addressing; absent = 0). */
   static final class LongIntMap {
      private long[] keys = new long[1024];
      private int[] vals = new int[1024];
      private boolean[] used = new boolean[1024];
      private int size;

      int size() {
         return size;
      }

      int get(long k) {
         int mask = keys.length - 1;
         int i = (int)(k ^ (k >>> 32)) & mask;
         while (used[i]) {
            if (keys[i] == k) {
               return vals[i];
            }
            i = (i + 1) & mask;
         }
         return 0;
      }

      void put(long k, int v) {
         if (size * 2 >= keys.length) {
            grow();
         }
         int mask = keys.length - 1;
         int i = (int)(k ^ (k >>> 32)) & mask;
         while (used[i]) {
            if (keys[i] == k) {
               vals[i] = v;
               return;
            }
            i = (i + 1) & mask;
         }
         used[i] = true;
         keys[i] = k;
         vals[i] = v;
         size++;
      }

      void clear() {
         if (size == 0) {
            return;
         }
         keys = new long[1024];
         vals = new int[1024];
         used = new boolean[1024];
         size = 0;
      }

      private void grow() {
         long[] ok = keys;
         int[] ov = vals;
         boolean[] ou = used;
         keys = new long[ok.length * 2];
         vals = new int[ok.length * 2];
         used = new boolean[ok.length * 2];
         size = 0;
         for (int i = 0; i < ok.length; i++) {
            if (ou[i]) {
               put(ok[i], ov[i]);
            }
         }
      }
   }
}
