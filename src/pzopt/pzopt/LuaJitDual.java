package pzopt;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import se.krka.kahlua.integration.expose.LuaJavaInvoker;
import se.krka.kahlua.integration.expose.MultiLuaJavaInvoker;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.stdlib.BaseLib;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaCallFrame;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Prototype;

/**
 * luaJit rig devLuaJitDual (in-game differential test): a sampled entry of a compiled function first runs its compiled
 * code as a shadow, then the interpreter runs it for real (with every Lua function under it interpreted too), and the
 * two are compared: the results, the error message when one is thrown, and every write to a table that existed before
 * the call (table, key, value, in order), tables the call created compared by content. The shadow's writes are undone
 * and the Lua stack, the frame and the call stack are put back before the real run, so the game sees one call. The
 * shadow stops (and the sample counts as aborted, the call then runs compiled as usual) before anything it could not
 * undo: a Java function other than the pure library ones and the exposed getters (get / is / has / can ..., with
 * devLuaJitDualGetters), an upvalue write, a key removed from an old table, a wipe or a metatable change. Sampled
 * entries per function: 1, 2, 4, 8, ... and every devLuaJitDual-th after.
 */
public final class LuaJitDual {
   public static final int EVERY = Config.DEV_LUA_JIT_DUAL;
   public static final boolean ON = EVERY > 0 && LuaJit.ON;
   static final boolean GETTERS = Config.DEV_LUA_JIT_DUAL_GETTERS;
   static final boolean SELF_TEST = Boolean.getBoolean("pzopt.devLuaJitDualSelfTest");
   static final String TRACE = System.getProperty("pzopt.devLuaJitDualTrace");
   static final int DEBUG_ERRORS = Integer.getInteger("pzopt.devLuaJitDualDebug", 0);

   /** Recording table writes (shadow or real run) on recThread. */
   public static volatile boolean on;
   static Thread recThread;
   static KahluaThread recT;
   static boolean shadow;
   static ArrayList<Object[]> journal;
   /** Tables made during the recorded run: TRUE by Lua code (their writes are not journaled), FALSE inside a Java call (they may stay in a Java object, e.g. getModData: journaled and undone). */
   static IdentityHashMap<Object, Boolean> created;
   static boolean abortFlag;
   static String abortWhy;

   static final AtomicLong samples = new AtomicLong(), compared = new AtomicLong(), mismatches = new AtomicLong(),
         aborted = new AtomicLong(), lost = new AtomicLong(), shadowErrors = new AtomicLong(), osr = new AtomicLong();
   static final Map<String, Integer> abortReasons = new java.util.concurrent.ConcurrentHashMap<>();
   static int logged;

   /** Thrown by the shadow's gates (no stack trace); abortFlag says it was one even when a caller wrapped it. */
   static final class Abort extends RuntimeException {
      Abort() {
         super("luaJit dual: shadow stopped", null, false, false);
      }
   }

   static final Abort ABORT = new Abort();

   /** finish(): the shadow's Java-made tables as the shadow left them (Rec.shadowSnap) while its results are compared. */
   static IdentityHashMap<Object, Map<Object, Object>> snapNow;

   /** The real run in progress: what the shadow did. */
   static final class Rec {
      Prototype proto;
      se.krka.kahlua.vm.Coroutine co;
      int idx;
      Object[] results;
      String error;
      ArrayList<Object[]> shadowJournal;
      IdentityHashMap<Object, Boolean> shadowNew;
      IdentityHashMap<Object, Map<Object, Object>> shadowSnap;
      String realJava;
   }

   private LuaJitDual() {
   }

   static boolean sample(Prototype p) {
      int n = ++p.pzoptDualSeen;
      return (n & (n - 1)) == 0 || n % EVERY == 0;
   }

   /**
    * LuaJit.enter's hook for a function with code: true = the shadow ran and the interpreter must now run the frame
    * (enter returns -1); false = run it compiled as usual.
    */
   public static boolean enter(KahluaThread t, LuaCallFrame f, Prototype p) {
      if (t.pzoptDualFrame != null) {
         Rec r = (Rec)t.pzoptDualRec;
         if (r.co.getCallframeTop() > r.idx) {
            return true; // inside the real run: everything interpreted
         }
         lost.incrementAndGet();
         clear(t);
      }
      if (t.pzoptDualShadow || on) {
         return false;
      }
      if (f.pc != 0) {
         // an OSR entry: the frame's registers already hold state made by the interpreter (live pairs iterators,
         // tables made before the loop); the shadow would advance those iterators and the real run resume exhausted ones
         osr.incrementAndGet();
         return false;
      }
      if (!sample(p)) {
         return false;
      }
      samples.incrementAndGet();
      Object[] saved = t.pzoptDualSave(f);
      int returnBase = KahluaThread.pzoptJitReturnBase(f);
      se.krka.kahlua.vm.Coroutine co = f.coroutine;
      begin(t, true);
      t.pzoptDualShadow = true;
      int st = -1;
      Throwable err = null;
      try {
         st = LuaJit.invoke(t, f);
      } catch (Throwable e) {
         err = e;
      } finally {
         t.pzoptDualShadow = false;
         on = false;
      }
      boolean stop = abortFlag;
      if (!stop && err == null && st != LuaJit.ST_RETURNED) {
         stop = true;
         abortWhy = "status " + st;
      }
      Object[] results = null;
      if (!stop && err == null) {
         int top = co.getTop();
         results = new Object[Math.max(0, top - returnBase)];
         System.arraycopy(co.objectStack, returnBase, results, 0, results.length);
         if (SELF_TEST && results.length > 0 && results[0] instanceof Double d) {
            results[0] = d + 1; // the rig's own check: every such sample must be reported
         }
      }
      ArrayList<Object[]> j = journal;
      IdentityHashMap<Object, Boolean> made = created;
      // a table the shadow made inside a Java call (journaled: it may stay in a Java object) loses the shadow's writes in
      // the undo; its content as the shadow left it is kept for the comparison (string split's result table, 2026-10-26)
      IdentityHashMap<Object, Map<Object, Object>> snap = new IdentityHashMap<>();
      for (Object[] w : j) {
         if (w[1] instanceof KahluaTableImpl kt && made.get(kt) == Boolean.FALSE && !snap.containsKey(kt)) {
            snap.put(kt, new java.util.LinkedHashMap<>(kt.delegate));
         }
      }
      undo(j);
      t.pzoptDualRestore(f, saved);
      journal = null;
      created = null;
      if (stop) {
         aborted.incrementAndGet();
         abortReasons.merge(abortWhy != null ? abortWhy : "?", 1, Integer::sum);
         if (TRACE != null && (TRACE.equals("*") || LuaJitCompiler.describe(p).contains(TRACE))) {
            Log.info("luaJit dual: trace abort " + LuaJitCompiler.describe(p) + ": " + abortWhy);
         }
         abortFlag = false;
         abortWhy = null;
         return false;
      }
      if (err != null) {
         if (shadowErrors.incrementAndGet() <= DEBUG_ERRORS || err.getMessage() == null && DEBUG_ERRORS < 0) {
            Log.warn("luaJit dual: shadow error in " + LuaJitCompiler.describe(p) + ": " + err);
            err.printStackTrace();
         }
      }
      Rec r = new Rec();
      r.proto = p;
      r.co = co;
      r.idx = co.getCallframeTop() - 1;
      r.results = results;
      r.error = err != null ? String.valueOf(err.getMessage()) : null;
      r.shadowJournal = j;
      r.shadowNew = made;
      r.shadowSnap = snap;
      t.pzoptDualRec = r;
      t.pzoptDualFrame = f;
      begin(t, false);
      return true;
   }

   /** A shadow run is in progress on this thread (UiDeps leaves its Java calls out of a recorded render's log). */
   public static boolean inShadow() {
      return on && shadow && Thread.currentThread() == recThread;
   }

   static void begin(KahluaThread t, boolean sh) {
      recT = t;
      journal = new ArrayList<>();
      created = new IdentityHashMap<>();
      shadow = sh;
      recThread = Thread.currentThread();
      abortFlag = false;
      abortWhy = null;
      on = true;
   }

   static void clear(KahluaThread t) {
      t.pzoptDualFrame = null;
      t.pzoptDualRec = null;
      on = false;
      recT = null;
      journal = null;
      created = null;
   }

   static void stop(String why) {
      if (!abortFlag) {
         abortFlag = true;
         abortWhy = why;
      }
      throw ABORT;
   }

   static boolean mine() {
      return on && Thread.currentThread() == recThread;
   }

   /** OP_RETURN of the real run's frame: compare. */
   public static void realReturned(KahluaThread t, int returnBase, int n) {
      Rec r = (Rec)t.pzoptDualRec;
      Object[] real = new Object[Math.max(0, n)];
      System.arraycopy(r.co.objectStack, returnBase, real, 0, real.length);
      finish(t, r, real, null);
   }

   /** luaMainloop's catch after unwinding: the real run's frame is gone when its index is above the call stack. */
   public static void realUnwound(KahluaThread t, Throwable e) {
      Rec r = (Rec)t.pzoptDualRec;
      if (r != null && r.co.getCallframeTop() <= r.idx) {
         finish(t, r, null, String.valueOf(e.getMessage()));
      }
   }

   static void finish(KahluaThread t, Rec r, Object[] real, String realError) {
      ArrayList<Object[]> rj = journal;
      IdentityHashMap<Object, Boolean> rNew = created;
      clear(t);
      compared.incrementAndGet();
      String why = null;
      if (r.realJava != null) {
         why = "the interpreter called " + r.realJava + ", the compiled code did not";
      } else if (r.error != null || realError != null) {
         if (r.error == null || realError == null || !norm(r.error).equals(norm(realError))) {
            why = "error: compiled " + r.error + " / interpreted " + realError;
         }
      }
      IdentityHashMap<Object, Object> map = new IdentityHashMap<>();
      snapNow = r.shadowSnap;
      try {
      if (why == null && real != null) {
         if (real.length != r.results.length) {
            why = "results: compiled " + r.results.length + " / interpreted " + real.length;
         } else {
            for (int i = 0; i < real.length && why == null; i++) {
               if (!eq(r.results[i], real[i], r.shadowNew, rNew, map, 0)) {
                  why = "result " + (i + 1) + ": compiled " + show(r.results[i]) + " / interpreted " + show(real[i]);
               }
            }
         }
      }
      if (why == null) {
         if (rj.size() != r.shadowJournal.size()) {
            why = "table writes: compiled " + r.shadowJournal.size() + " / interpreted " + rj.size() + firstDiff(r.shadowJournal, rj, r.shadowNew, rNew, map);
         } else {
            String d = firstDiff(r.shadowJournal, rj, r.shadowNew, rNew, map);
            if (!d.isEmpty()) {
               why = "table writes" + d;
            }
         }
      }
      } finally {
         snapNow = null;
      }
      if (TRACE != null && (TRACE.equals("*") || LuaJitCompiler.describe(r.proto).contains(TRACE))) {
         Log.info("luaJit dual: trace " + LuaJitCompiler.describe(r.proto) + " writes " + r.shadowJournal.size() + "/" + rj.size()
               + " made " + r.shadowNew.size() + "/" + rNew.size() + " -> " + why);
      }
      if (why != null) {
         mismatches.incrementAndGet();
         if (logged < 50) {
            logged++;
            Log.warn("luaJit dual: MISMATCH in " + LuaJitCompiler.describe(r.proto) + ": " + why);
         }
      }
   }

   static String firstDiff(ArrayList<Object[]> a, ArrayList<Object[]> b, IdentityHashMap<Object, Boolean> an,
         IdentityHashMap<Object, Boolean> bn, IdentityHashMap<Object, Object> map) {
      int n = Math.min(a.size(), b.size());
      for (int i = 0; i < n; i++) {
         Object[] x = a.get(i), y = b.get(i);
         // x[1] by eq too: a table made inside a Java frame (journaled) is a different object in each run
         if (x[0] != y[0] || !eq(x[1], y[1], an, bn, map, 0) || !eq(x[2], y[2], an, bn, map, 0) || !eq(x[3], y[3], an, bn, map, 0)) {
            return " (first difference at write " + (i + 1) + ": compiled " + x[0] + " " + show(x[2]) + " = " + show(x[3])
                  + " / interpreted " + y[0] + " " + show(y[2]) + " = " + show(y[3]) + ")";
         }
      }
      return "";
   }

   static final Pattern ADDRESS = Pattern.compile("(table|function|userdata|coroutine|thread) 0x-?[0-9a-fA-F]+");

   /** An error message without the identity hashes of tables / functions in it (the two runs made different ones). */
   static String norm(String s) {
      return ADDRESS.matcher(s).replaceAll("$1 0x?");
   }

   static String show(Object o) {
      String s = o == null ? "nil" : o instanceof String str ? '"' + str + '"' : String.valueOf(o);
      return s.length() > 80 ? s.substring(0, 80) + "..." : s;
   }

   /** Equal as Lua sees them: numbers by bits, strings by content, objects made by the calls by content / prototype. */
   static boolean eq(Object a, Object b, IdentityHashMap<Object, Boolean> an, IdentityHashMap<Object, Boolean> bn,
         IdentityHashMap<Object, Object> map, int depth) {
      if (a == b) {
         return true; // both runs saw the same object (an old one, or one a getter made in the shadow and kept)
      }
      if (a == null || b == null) {
         return false;
      }
      if (a instanceof Double x && b instanceof Double y) {
         return Double.doubleToLongBits(x) == Double.doubleToLongBits(y);
      }
      if (a instanceof String || a instanceof Boolean) {
         return a.equals(b);
      }
      if (a instanceof LuaClosure x && b instanceof LuaClosure y) {
         return x.prototype == y.prototype; // closures are not tracked: one made by each run, or the same old one
      }
      if (a instanceof se.krka.kahlua.vm.KahluaArray x && b instanceof se.krka.kahlua.vm.KahluaArray y) {
         // table.newarray's arrays are not tracked (no constructor hook): one made by each run, compared by content
         Object m = map.get(a);
         if (m != null) {
            return m == b;
         }
         map.put(a, b);
         int n = x.len();
         if (n != y.len() || depth > 8) {
            return n == y.len();
         }
         for (int i = 1; i <= n; i++) {
            if (!eq(x.rawget(i), y.rawget(i), an, bn, map, depth + 1)) {
               return false;
            }
         }
         return true;
      }
      if (!an.containsKey(a) || !bn.containsKey(b)) {
         return false;
      }
      Object m = map.get(a);
      if (m != null) {
         return m == b;
      }
      map.put(a, b);
      if (a instanceof KahluaTableImpl x && b instanceof KahluaTableImpl y) {
         if (depth > 8) {
            return true;
         }
         Map<Object, Object> xd = snapNow != null && snapNow.containsKey(x) ? snapNow.get(x) : x.delegate; // the shadow's side as it left it
         if (xd.size() != y.delegate.size() || !eq(x.getMetatable(), y.getMetatable(), an, bn, map, depth + 1)) {
            return false;
         }
         Iterator<Map.Entry<Object, Object>> i = xd.entrySet().iterator(), k = y.delegate.entrySet().iterator();
         while (i.hasNext() && k.hasNext()) {
            Map.Entry<Object, Object> p = i.next(), q = k.next();
            if (!eq(p.getKey(), q.getKey(), an, bn, map, depth + 1) || !eq(p.getValue(), q.getValue(), an, bn, map, depth + 1)) {
               return false;
            }
         }
         return true;
      }
      return false;
   }

   static void undo(ArrayList<Object[]> j) {
      boolean was = on;
      on = false;
      for (int i = j.size() - 1; i >= 0; i--) {
         Object[] w = j.get(i);
         ((KahluaTableImpl)w[1]).rawset(w[2], w[4]);
      }
      on = was;
   }

   // ---- the hooks (KahluaTableImpl, KahluaThread, LuaJavaFast), each only while on / a dual run is active

   /** KahluaTableImpl's constructor. */
   public static void created(KahluaTableImpl t) {
      if (mine()) {
         LuaCallFrame cf = recT.getCurrentCoroutine().currentCallFrame();
         created.put(t, cf == null || cf.isJava() ? Boolean.FALSE : Boolean.TRUE);
      }
   }

   /** KahluaTableImpl.iterator: a pairs iterator made during a recorded run. */
   public static void createdIterator(Object it) {
      if (mine()) {
         created.put(it, Boolean.TRUE);
      }
   }

   /** KahluaTableImpl.rawset, before the write. */
   public static void write(KahluaTableImpl t, Object key, Object value) {
      if (!mine() || created.get(t) == Boolean.TRUE) {
         return;
      }
      Object old = t.delegate.get(key);
      if (shadow && value == null && old != null) {
         stop("key removed");
      }
      journal.add(new Object[] {"set", t, key, value, old});
   }

   /** KahluaTableImpl.pzoptJitSetSlot, before the write. */
   public static void writeSlot(KahluaTableImpl t, LuaMap m, int s, Object value) {
      if (mine()) {
         write(t, m.keyAt(s), value);
      }
   }

   /** KahluaTableImpl.wipe / setMetatable, before the change. */
   public static void change(KahluaTableImpl t, String what, Object value) {
      if (!mine() || created.get(t) == Boolean.TRUE) {
         return;
      }
      if (shadow) {
         stop(what);
      }
      journal.add(new Object[] {what, t, what, value, null});
   }

   /** KahluaThread's interpreter SETUPVAL and the compiled one. */
   public static void upvalue(KahluaThread t) {
      if (t.pzoptDualShadow) {
         stop("upvalue write");
      }
   }

   /** Every call of a Java function from Lua (callJava, the generated LuaJavaFast calls). */
   public static void java(KahluaThread t, Object fn) {
      if (t.pzoptDualShadow) {
         if (!allowed(fn)) {
            stop("java " + name(fn));
         } else if (fn instanceof se.krka.kahlua.vm.KahluaTableIterator && mine() && created.get(fn) == null) {
            stop("old iterator"); // a pairs iterator made before the run: the shadow would advance the real run's one
         }
      } else if (t.pzoptDualRec instanceof Rec r && r.realJava == null && r.co.getCallframeTop() > r.idx && !allowed(fn)) {
         r.realJava = name(fn);
      }
   }

   static String name(Object fn) {
      if (fn instanceof LuaJavaFast jf) {
         return jf.pzoptName;
      }
      return fn instanceof LuaJavaInvoker || fn instanceof MultiLuaJavaInvoker || fn instanceof BaseLib ? fn.toString() : fn.getClass().getName();
   }

   static final Pattern GETTER = Pattern.compile("(get|is|has|can|contains)([A-Z0-9_].*)?|instanceof|size|equals|toString");
   static final Pattern IMPURE = Pattern.compile("(?i).*(random|rand|millis|nano|timestamp|next).*");
   static final java.util.Set<String> BASE = java.util.Set.of("select", "type", "tostring", "tonumber", "getmetatable", "unpack",
         "rawequal", "rawset", "rawget", "error");

   /** allowed() per function object (its regexes were a quarter of the rig's cost in game, dual1). */
   static final Map<Object, Boolean> ALLOWED = java.util.Collections.synchronizedMap(new IdentityHashMap<>());

   static boolean allowed(Object fn) {
      if (!(fn instanceof LuaJavaInvoker || fn instanceof MultiLuaJavaInvoker || fn instanceof LuaJavaFast)) {
         return allowed0(fn); // library functions and per-call objects (pairs iterators): no regex, not cached
      }
      Boolean b = ALLOWED.get(fn);
      if (b == null) {
         b = allowed0(fn);
         ALLOWED.put(fn, b);
      }
      return b;
   }

   static boolean allowed0(Object fn) {
      if (fn instanceof BaseLib) {
         return BASE.contains(fn.toString());
      }
      String c = fn.getClass().getName();
      if (c.startsWith("se.krka.kahlua.stdlib.StringLib") || c.startsWith("se.krka.kahlua.stdlib.TableLib")
            || c.startsWith("se.krka.kahlua.j2se.MathLib") || c.startsWith("se.krka.kahlua.j2se.KahluaTableImpl")) {
         return true;
      }
      if (GETTERS && (fn instanceof LuaJavaInvoker || fn instanceof MultiLuaJavaInvoker || fn instanceof LuaJavaFast)) {
         String n = name(fn);
         return n != null && GETTER.matcher(n).matches() && !IMPURE.matcher(n).matches();
      }
      return false;
   }

   public static String summary() {
      if (!ON) {
         return "";
      }
      StringBuilder sb = new StringBuilder(" dual=").append(samples.get()).append('/').append(compared.get()).append('/')
            .append(mismatches.get()).append(" dual_aborted=").append(aborted.get()).append(" dual_lost=").append(lost.get()).append(" dual_osr=").append(osr.get())
            .append(" dual_errors=").append(shadowErrors.get());
      java.util.List<Map.Entry<String, Integer>> l = new ArrayList<>(abortReasons.entrySet());
      l.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
      sb.append(" dual_stops=");
      for (int i = 0; i < Math.min(6, l.size()); i++) {
         sb.append(i > 0 ? "," : "").append(l.get(i).getKey().replace(' ', '_')).append(':').append(l.get(i).getValue());
      }
      return sb.toString();
   }
}
