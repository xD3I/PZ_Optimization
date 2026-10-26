package pzopt;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaCallFrame;
import se.krka.kahlua.vm.Prototype;
import zombie.core.Core;

/**
 * luaJit (Lua VM pass, 2026-10-25): hot Lua functions compiled to JVM bytecode.
 *
 * The interpreter (KahluaThread.luaMainloop) counts every entry of a Lua function; at {@link Config#LUA_JIT_THRESHOLD}
 * the function's Prototype is queued and a background thread compiles it ({@link LuaJitCompiler}) into a hidden class
 * of the se.krka.kahlua.vm package. From then on the interpreter, on entering a frame of that function, calls its
 * code instead of interpreting it, and compiled code calls compiled code directly.
 *
 * The compiled code keeps the interpreter's world: every Lua frame is still pushed on the coroutine's call-frame stack
 * with its closure, pc (set before every instruction that can throw or call) and restoreTop, results and arguments
 * still travel on the Lua stack, so tracebacks, error messages, pcall, the mod profiler (pzopt.ModLuaProfile) and
 * LuaManager's stack walks see what they saw. Errors are not caught by compiled code: they unwind to the luaMainloop
 * that entered it, whose own handler logs and pops exactly as for interpreted frames. Whatever compiled code cannot
 * continue (a Java function that switched coroutine, a callee without code, the Java-depth limit) it hands back to
 * that luaMainloop with the frame state the interpreter would have had (the {@code ST_*} statuses).
 */
public final class LuaJit {
   public static final boolean ON = Config.LUA_JIT && Overrides.enabled();
   static final int THRESHOLD = Math.max(1, Config.LUA_JIT_THRESHOLD);
   /** Compiled activations nested on the Java stack before calls go back to the interpreter (each luaMainloop level starts again). */
   public static final int MAX_DEPTH = 100;
   /** devGtAlternateKeys lists luaJit: a within-run A/B, the off phases interpret. */
   static final boolean AB = ZombAb.listed(ZombAb.LUA_JIT);
   /** devGtAlternateKeys=luaJitNew: an in-run A/B of the newest VM keys alone (ZombAb.LUA_JIT_NEW). */
   static final boolean AB_NEW = ZombAb.listed(ZombAb.LUA_JIT_NEW);

   /** False in the off half of a luaJitNew A/B (and of a luaJit one): the keys under test take their old path this call. */
   public static boolean newOn() {
      return !(AB_NEW && !ZombAb.on(ZombAb.LUA_JIT_NEW)) && !(AB && !ZombAb.on(ZombAb.LUA_JIT));
   }

   /** The function returned: results at its returnBase, frame popped (OP_RETURN). */
   public static final int ST_RETURNED = 0;
   /** The interpreter continues the coroutine's current frame as it stands. */
   public static final int ST_DEOPT = 1;
   /** As after OP_CALL of a Java function: the coroutine's current frame (or none / a Java frame: luaMainloop returns). */
   public static final int ST_AFTER_JAVA = 2;
   /** As after OP_TAILCALL of a Java function that switched coroutine. */
   public static final int ST_AFTER_TAIL_JAVA = 3;
   /** Internal to {@link #invoke}: the frame now holds a tail-called Lua function at pc 0. */
   public static final int ST_TAILCALL = 4;
   /** lua was reset during the run: luaMainloop throws as after any instruction. */
   public static final int ST_RESET = 5;
   /** Internal to a split function's dispatcher (luaJitSplit): the frame continues at f.pc in another region. */
   public static final int ST_REGION = 6;

   /** luaJitSplitRaw: the thread's hand-over array of a region exit (Lua frames have at most 250 registers). */
   public static double[] regionD(KahluaThread t) {
      double[] d = t.pzoptJitD;
      if (d == null) {
         t.pzoptJitD = d = new double[256];
      }
      return d;
   }

   static final AtomicLong compiled = new AtomicLong();
   static final AtomicLong failed = new AtomicLong();
   static final AtomicLong compileNanos = new AtomicLong();
   static final AtomicLong codeBytes = new AtomicLong();
   /** luaJitSpecArith: failed speculations, and the functions compiled again without them. */
   static final AtomicLong specFails = new AtomicLong();
   static final java.util.concurrent.ConcurrentHashMap<Prototype, Boolean> NO_SPEC = new java.util.concurrent.ConcurrentHashMap<>();
   static final int SPEC_LIMIT = Config.LUA_JIT_SPEC_LIMIT;

   /**
    * luaJitSpecArith: a compiled arithmetic's result was not a number (the frame went to the interpreter); at
    * luaJitSpecLimit failures of one code its function is compiled again without speculation, and the old code hands
    * every later frame to the new one (call sites keep the old code).
    */
   public static void specFailed(LuaJitCode c) {
      specFails.incrementAndGet();
      Prototype p = c.proto;
      if (++c.specFails == SPEC_LIMIT && p != null && NO_SPEC.putIfAbsent(p, Boolean.TRUE) == null) {
         synchronized (LuaJit.class) {
            startWorkers();
            queue.add(p);
         }
      }
   }
   /** Statuses seen by luaMainloop's hook (main thread only, racy reads for the log are fine). */
   public static final long[] exits = new long[6];

   private static final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
   private static Thread worker;
   /** luaJitWorkers: compiler threads taking from the one queue (the first also logs the idle summary). */
   static final int WORKERS = Math.max(1, Math.min(8, Config.LUA_JIT_WORKERS));

   private static void startWorkers() {
      if (worker != null) {
         return;
      }
      for (int i = 0; i < WORKERS; i++) {
         Thread w = new Thread(LuaJit::work, i == 0 ? "pzopt-luajit" : "pzopt-luajit-" + i);
         w.setDaemon(true);
         w.setPriority(Thread.MIN_PRIORITY + 1);
         w.start();
         if (i == 0) {
            worker = w;
         }
      }
   }
   private static MethodHandles.Lookup lookup;

   private LuaJit() {
   }

   /**
    * luaMainloop's hook, at the entry of a frame (pc 0): -1 = interpret it, else the run's status. Counts the entry of a
    * function without code and queues it at the threshold.
    */
   public static int enter(KahluaThread t, LuaCallFrame f, Prototype p) {
      LuaJitCode c = p.pzoptJit;
      if (c == null) {
         if (++p.pzoptJitCount == THRESHOLD && rateOk(p)) {
            queue(p);
         }
         return -1;
      }
      if (f.canYield || Core.debug || t.pzoptJitDepth >= MAX_DEPTH || AB && !ZombAb.on(ZombAb.LUA_JIT)) {
         return -1;
      }
      if (LuaJitDual.ON && LuaJitDual.enter(t, f, p)) {
         return -1; // devLuaJitDual: the shadow ran; the interpreter runs the frame for real (or this is inside such a run)
      }
      int st = invoke(t, f);
      exits[st]++;
      return st;
   }

   static final long RATE_NS = Config.LUA_JIT_RATE_MS * 1_000_000L;

   /**
    * luaJitRateMs: at the threshold, whether the count came fast enough; else the count and its clock start over (the first
    * count, from load, only starts the clock).
    */
   static boolean rateOk(Prototype p) {
      if (RATE_NS <= 0) {
         return true;
      }
      long now = System.nanoTime();
      long since = p.pzoptJitWindow;
      if (since != 0 && now - since <= RATE_NS) {
         return true;
      }
      p.pzoptJitCount = 0;
      p.pzoptJitWindow = now;
      return false;
   }

   public static final boolean OSR = Config.LUA_JIT_OSR && ON;
   public static final boolean NESTED = Config.LUA_JIT_NESTED;
   static final int LOOP_THRESHOLD = Math.max(1, Config.LUA_JIT_LOOP_THRESHOLD);

   /**
    * luaJitOsr: luaMainloop's hook after a backward jump (JMP, FORLOOP): true = the interpreter should hand the frame to
    * the function's compiled code now (enter at this loop header); counts back-edges of a function without code and
    * queues it at the threshold.
    */
   public static boolean backEdge(KahluaThread t, Prototype p) {
      if (p.pzoptJit != null) {
         if (LuaJitDual.ON && t.pzoptDualFrame != null) {
            return false; // devLuaJitDual: a real run interprets everything
         }
         return true;
      }
      if (++p.pzoptJitLoops == LOOP_THRESHOLD) {
         queue(p);
      }
      return false;
   }

   /**
    * Runs the compiled code of a frame pushed and initialised by the caller (pc 0), following Lua tail calls. ST_DEOPT
    * (the frame untouched, for the interpreter) when its function has no code or the Java stack is deep enough.
    */
   public static int invoke(KahluaThread t, LuaCallFrame f) {
      if (t.pzoptJitDepth >= MAX_DEPTH) {
         return ST_DEOPT;
      }
      int depth = t.pzoptJitDepth;
      t.pzoptJitDepth = depth + 1;
      try {
         for (;;) {
            LuaJitCode c = f.closure.prototype.pzoptJit;
            if (c == null) {
               return ST_DEOPT;
            }
            int st = c.run(t, f);
            if (st != ST_TAILCALL) {
               return st;
            }
         }
      } finally {
         // absolute: direct calls (luaJitDirectCalls) count their own level without a finally of their own
         t.pzoptJitDepth = depth;
      }
   }

   /** Compiled GETTABLE with a number key held unboxed: a table's 1..n run by index, else the stock tableget of the boxed key. */
   public static Object getNum(KahluaThread t, Object table, double d) {
      if (table instanceof se.krka.kahlua.j2se.KahluaTableImpl kt && (UI_DEPS || !UiDeps.on)) {
         Object v = kt.pzoptSeqGet(d);
         if (v != null) {
            if (UiDeps.on) {
               UiDeps.read(kt, se.krka.kahlua.vm.KahluaUtil.toDouble(d), v); // what rawget logs for the boxed key
            }
            return v;
         }
      }
      return t.tableget(table, se.krka.kahlua.vm.KahluaUtil.toDouble(d));
   }

   /**
    * Compiled read of a constant String key (GETGLOBAL, GETTABLE, SELF): the site's remembered position in the table's LuaMap,
    * else one hash lookup (the position remembered), else the stock metatable walk; anything but a plain LuaMap-backed table
    * takes the stock tableget.
    */
   static final boolean STATS = Config.DEV_LUA_JIT_STATS;
   /** devLuaJitStats: get slot hit / hash find / absent (metatable walk) / Java IC hit / Java miss / other; set slot hit / find / stock */
   static final long[] stat = new long[20];

   public static Object getStr(KahluaThread t, Object table, Object key, LuaJitIc ic) {
      // luaJitGetStrInline: the common case (the key at the site's remembered position of the table's own map, a value
      // there, nothing recorded) in a method small enough for C2 to inline into the compiled body; the rest is getStrSlow
      if (GET_INLINE && FAST_TABLE && !STATS && table instanceof se.krka.kahlua.j2se.KahluaTableImpl kt
            && (!UiDeps.on || UI_INLINE && ic.uiTable == kt && ic.uiLog == UiDeps.topId)) {
         // luaJitUiInline: while a render records, a site that already logged this table for the current log
         // (UiDeps.readSite's memo) would log nothing again, so its slot hit needs no slow path either
         LuaMap m = kt.pzoptJitMap();
         if (m != null) {
            int s = ic.slot;
            if (s < m.used && m.keys[s] == key) {
               Object v = m.vals[s];
               if (v != null) {
                  return v;
               }
            }
         }
      } else if (GET_JAVA_INLINE && GET_INLINE && !STATS && !(table instanceof se.krka.kahlua.vm.KahluaTable) && table != null) {
         LuaJitIc.Java j = ic.java; // luaJitGetJavaInline: a Java object's method, as getStrSlow's first test
         if (j != null && table.getClass() == j.cls() && j.epoch() == se.krka.kahlua.j2se.KahluaTableImpl.pzoptJitEpoch && j.thread() == t
               && !UiDeps.on) {
            return j.value();
         }
      }
      return getStrSlow(t, table, key, ic);
   }

   static final boolean GET_INLINE = Config.LUA_JIT_GET_STR_INLINE;
   static final boolean UI_INLINE = Config.LUA_JIT_UI_INLINE && Config.LUA_JIT_UI_READ_MEMO && Config.LUA_JIT_UI_DEPS;
   static final boolean SELF_JAVA = Config.LUA_JIT_SELF_JAVA;

   /**
    * luaJitSelfJava: a SELF's method lookup (`obj:m()`). Its small inlined part is the site's Java-object entry (a Java
    * object's method, getStrSlow's first test); a table's method is rarely in the instance itself, so a table goes
    * straight to getStrSlow (which tries the site's position first, as getStr would).
    */
   public static Object getSelf(KahluaThread t, Object obj, Object key, LuaJitIc ic) {
      if (!STATS && !UiDeps.on && (!AB_NEW || newOn())) {
         LuaJitIc.Java j = ic.java;
         if (j != null && obj != null && obj.getClass() == j.cls() && j.epoch() == se.krka.kahlua.j2se.KahluaTableImpl.pzoptJitEpoch && j.thread() == t) {
            return j.value();
         }
         if (obj instanceof se.krka.kahlua.j2se.KahluaTableImpl) {
            return getStrSlow(t, obj, key, ic);
         }
      }
      return getStr(t, obj, key, ic);
   }
   static final boolean GET_JAVA_INLINE = Config.LUA_JIT_GET_JAVA_INLINE;
   static final boolean SLOT2 = Config.LUA_JIT_SLOT2;
   /** luaJitSlot4: two more remembered positions (four table layouts a site reads, most recent first). */
   static final boolean SLOT4 = Config.LUA_JIT_SLOT4 && SLOT2;

   /** luaJitSlot4: the key at the site's third or fourth remembered position (moved to the front), else -1. */
   static int slot34(LuaMap m, Object key, LuaJitIc ic) {
      int s3 = ic.slot3;
      if (s3 < m.used && m.keys[s3] == key) {
         ic.slot3 = ic.slot2;
         ic.slot2 = ic.slot;
         ic.slot = s3;
         return s3;
      }
      int s4 = ic.slot4;
      if (s4 < m.used && m.keys[s4] == key) {
         ic.slot4 = ic.slot3;
         ic.slot3 = ic.slot2;
         ic.slot2 = ic.slot;
         ic.slot = s4;
         return s4;
      }
      return -1;
   }

   static Object getStrSlow(KahluaThread t, Object table, Object key, LuaJitIc ic) {
      if (!(table instanceof se.krka.kahlua.vm.KahluaTable)) {
         LuaJitIc.Java j = ic.java;
         if (j != null && table != null && table.getClass() == j.cls() && j.epoch() == se.krka.kahlua.j2se.KahluaTableImpl.pzoptJitEpoch
               && j.thread() == t && (UI_DEPS || !UiDeps.on)) {
            if (STATS) {
               stat[3]++;
            }
            if (UiDeps.on) {
               // the two reads the stock walk logs: the class metatable's __index, then the key in that table
               UiDeps.read(j.mt(), "__index", j.idx());
               UiDeps.read(j.idx(), key, j.value());
            }
            return j.value();
         }
         if (STATS) {
            stat[4]++;
         }
         return javaIndex(t, table, key, ic);
      }
      if (FAST_TABLE && table instanceof se.krka.kahlua.j2se.KahluaTableImpl kt) {
         LuaMap m = UI_DEPS || !UiDeps.on ? kt.pzoptJitMap() : null;
         if (m != null) {
            int s = ic.slot;
            Object v;
            if (s < m.used && m.keys[s] == key) {
               if (STATS) {
                  stat[0]++;
               }
               v = m.vals[s];
            } else if (SLOT2 && ic.slot2 < m.used && m.keys[ic.slot2] == key && (!AB_NEW || newOn())) {
               if (STATS) {
                  stat[18]++;
               }
               s = ic.slot2; // luaJitSlot2: the other layout; the two swap so the most recent is first
               ic.slot2 = ic.slot;
               ic.slot = s;
               v = m.vals[s];
            } else if (SLOT4 && (s = slot34(m, key, ic)) >= 0) {
               if (STATS) {
                  stat[19]++;
               }
               v = m.vals[s];
            } else {
               s = m.find(key, LuaMap.hash(key));
               if (s < 0) {
                  if (STATS) {
                     stat[2]++;
                  }
                  LuaJitIc.Miss before = ic.miss, before2 = ic.miss2;
                  v = MISS_CACHE ? kt.pzoptMissGetIc(key, ic) : kt.pzoptMissGet(key);
                  if (STATS && MISS_CACHE) {
                     // devLuaJitStats: the site's miss cache answered (9), its second entry answered (15), or it was refilled
                     // (10): the same metatable whose version moved (12), a cold site (13), another metatable (14)
                     if (ic.miss == before) {
                        stat[9]++;
                     } else if (before2 != null && ic.miss == before2) {
                        stat[15]++;
                     } else {
                        stat[10]++;
                        stat[before == null ? 13 : before.mt() == kt.getMetatable() ? 12 : 14]++;
                     }
                  }
                  if (v != null) {
                     if (UiDeps.on) {
                        UiDeps.readSite(kt, key, v, ic); // rawget's whole answer, as rawget logs it
                     }
                     return v;
                  }
                  if (TABLEGET_HOOKED && !(TABLEGET_ZB_ONLY && !(key instanceof String ks && ks.startsWith("zb")))) {
                     return t.tableget(table, key);
                  }
                  if (UiDeps.on) {
                     UiDeps.read(kt, key, null); // the stock walk's first rawget; the rest of it logs its own reads
                     if (NIL_UI && NIL_CACHE && MISS_CACHE && ic.bucket >= 0 && (!AB_NEW || newOn())) {
                        return NIL_POLY ? nilPoly(t, kt, key, ic, true) : nilUi(t, kt, key, ic);
                     }
                  } else if (NIL_CACHE && MISS_CACHE && ic.bucket >= 0 && (!AB_NEW || newOn())) {
                     if (NIL_POLY) {
                        return nilPoly(t, kt, key, ic, false);
                     }
                     Object mt = kt.getMetatable();
                     long[] kv = se.krka.kahlua.j2se.KahluaTableImpl.pzoptKeyVer;
                     long ver = se.krka.kahlua.j2se.KahluaTableImpl.pzoptMetaVersion, k = kv[ic.bucket], iv = kv[INDEX_BUCKET];
                     if (mt != null && ic.nilMt == mt && ic.nilVer == ver && ic.nilKv == k && ic.nilIv == iv) {
                        if (STATS) {
                           stat[16]++;
                        }
                        return null; // proven nil for this class (luaJitNilCache)
                     }
                     Object r = t.pzoptJitTablegetMiss(table, key);
                     if (STATS) {
                        walkStat(kt, key, r);
                     }
                     if (r == null && mt != null && kt.getMetatable() == mt && ver == se.krka.kahlua.j2se.KahluaTableImpl.pzoptMetaVersion && k == kv[ic.bucket]
                           && iv == kv[INDEX_BUCKET] && (NIL_UI ? (ic.nilChain = kt.pzoptNilChain(key)) != null : kt.pzoptNilProof(key))) {
                        ic.nilMt = mt;
                        ic.nilVer = ver;
                        ic.nilKv = k;
                        ic.nilIv = iv;
                        if (STATS) {
                           stat[17]++;
                        }
                     }
                     return r;
                  }
                  if (STATS) {
                     Object r = t.pzoptJitTablegetMiss(table, key);
                     walkStat(kt, key, r);
                     return r;
                  }
                  return t.pzoptJitTablegetMiss(table, key);
               }
               if (STATS) {
                  stat[1]++;
               }
               if (SLOT4) {
                  ic.slot4 = ic.slot3;
                  ic.slot3 = ic.slot2;
               }
               if (SLOT2) {
                  ic.slot2 = ic.slot;
               }
               ic.slot = s;
               v = m.vals[s];
            }
            if (v != null) {
               if (UiDeps.on) {
                  UiDeps.readSite(kt, key, v, ic);
               }
               return v;
            }
         }
      }
      if (STATS) {
         stat[5]++;
      }
      return t.tableget(table, key);
   }

   /** devLuaJitStats: what reached the full __index walk (pzoptJitTablegetMiss), by the shape of the chain and the key. */
   static final java.util.HashMap<String, long[]> walks = new java.util.HashMap<>();

   static void walkStat(se.krka.kahlua.j2se.KahluaTableImpl kt, Object key, Object r) {
      Object mt = kt.getMetatable();
      String shape;
      if (UiDeps.on) {
         shape = "ui"; // a recorded render: a rawget here would log a read the render never made
      } else if (mt == null) {
         shape = "nomt";
      } else if (!(mt instanceof se.krka.kahlua.j2se.KahluaTableImpl m)) {
         shape = "mt-other";
      } else {
         Object idx = m.rawget("__index");
         shape = idx == null ? "mt-noindex" : idx == mt ? "mt-self" : idx instanceof se.krka.kahlua.vm.KahluaTable ? "mt-table" : "mt-fn";
      }
      String k = (r == null ? "nil " : "hit ") + shape + " " + key;
      long[] c = walks.get(k);
      if (c == null) {
         walks.put(k, c = new long[1]);
      }
      c[0]++;
   }

   static String walkTop() {
      StringBuilder b = new StringBuilder();
      java.util.HashMap<String, long[]> byShape = new java.util.HashMap<>();
      for (var e : walks.entrySet()) {
         String k = e.getKey();
         String sh = k.substring(0, k.indexOf(' ', 4));
         byShape.computeIfAbsent(sh, x -> new long[1])[0] += e.getValue()[0];
      }
      b.append(" walk_shapes=");
      byShape.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0])).forEach(e -> b.append(e.getKey().replace(' ', ':')).append('=').append(e.getValue()[0]).append(','));
      b.append(" walk_top=");
      walks.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0])).limit(25).forEach(e -> b.append(e.getKey().replace(' ', ':')).append('=').append(e.getValue()[0]).append(','));
      return b.toString();
   }

   static final boolean FAST_TABLE = Config.LUA_FAST_TABLE;
   /** luaMetaVersionNarrow: KahluaTableImpl.setMetatable bumps the class-chain version only for a table used as a metatable. */
   public static final boolean META_NARROW = Config.LUA_META_VERSION_NARROW;
   public static final boolean KEY_VERSION = Config.LUA_META_KEY_VERSION;
   public static final boolean MISS_POLY = Config.LUA_JIT_MISS_POLY;
   static final boolean MISS_CACHE = Config.LUA_JIT_MISS_CACHE;
   /**
    * luaJitUiDeps: the table fast paths above stay on while a retained UI element's render is recorded (UiDeps.on), logging
    * the reads (and setStr the read + write) the stock rawget / tableSet would log; false = the stock paths then.
    */
   static final boolean UI_DEPS = Config.LUA_JIT_UI_DEPS;
   /**
    * A Java mod patches KahluaThread.tableget (ZombieBuddy: its zb* functions are read as absent keys of any table or
    * object, e.g. {@code klass:zbget("N")}): an absent constant key takes the patched stock tableget instead of our copy of
    * its miss path, so those names resolve in compiled code as in the interpreter.
    */
   static final boolean TABLEGET_HOOKED = ModCompat.patchedByAny("se/krka/kahlua/vm/KahluaThread.tableget");
   /**
    * luaJitZbTableget: the only tableget patch is ZombieBuddy's own (me.zed_0xff.zombie_buddy.patches...
    * Patch_KahluaThread.Patch_tableget, 2.3.x): it skips the stock body and answers a function for its names zbinspect,
    * zbmethod, zbmethods, zbfields, zbget, zbset, zbcall, zbkeys, zbvalues, zbgrep, zbmap, and runs the stock tableget
    * unchanged for every other key. So an absent key that does not start with "zb" takes our copy of the stock miss path
    * (no second own-map lookup, chain walk and __index walk for a nil answer); "zb*" keys still take the patched method.
    */
   /** luaJitNilCache (Config.LUA_JIT_NIL_CACHE): a site's proven-nil answer per metatable; INDEX_BUCKET = "__index"'s key version. */
   static final boolean NIL_CACHE = Config.LUA_JIT_NIL_CACHE;
   /** luaJitNilCacheUi (Config.LUA_JIT_NIL_CACHE_UI): the nil cache while a UI render is recorded, its walk's reads replayed. */
   static final boolean NIL_UI = Config.LUA_JIT_NIL_CACHE_UI;

   /**
    * luaJitNilCacheUi: getStrSlow's nil answer while a UI render is recorded (the table's own read already logged). A site's
    * proven nil (the same rule as luaJitNilCache) logs the reads the stock walk would (LuaJitIc.nilChain: they depend on the
    * metatable alone; nothing when the site already logged that chain in the same recording) instead of walking; otherwise the stock walk,
    * which logs its own reads, and the proof taken after it with UiDeps off.
    */
   /** luaJitNilPoly (Config.LUA_JIT_NIL_POLY): a site's proven nils for its last two metatables (LuaJitIc.nilA / nilB). */
   static final boolean NIL_POLY = Config.LUA_JIT_NIL_POLY;

   /**
    * luaJitNilPoly: getStrSlow's nil answer (the table's own map and the miss cache found nothing) through the site's proven
    * nils of its last two metatables, most recent first (a render method of a base class reads `self.optional` of instances
    * of several subclasses: one remembered metatable was replaced on every call). ui: a UI render is recorded (the table's
    * own read already logged): a proven nil logs its chain's reads as nilUi does, once per entry per recording.
    */
   static Object nilPoly(KahluaThread t, se.krka.kahlua.j2se.KahluaTableImpl kt, Object key, LuaJitIc ic, boolean ui) {
      Object mt = kt.getMetatable();
      long[] kv = se.krka.kahlua.j2se.KahluaTableImpl.pzoptKeyVer;
      long ver = se.krka.kahlua.j2se.KahluaTableImpl.pzoptMetaVersion, k = kv[ic.bucket], iv = kv[INDEX_BUCKET];
      if (mt != null) {
         LuaJitIc.Nil e = ic.nilA;
         if (e == null || e.mt != mt) {
            LuaJitIc.Nil e2 = ic.nilB;
            if (e2 != null && e2.mt == mt) {
               ic.nilB = e; // the most recent first
               ic.nilA = e2;
            }
            e = e2;
         }
         if (e != null && e.mt == mt && e.ver == ver && e.kv == k && e.iv == iv) {
            if (STATS) {
               stat[16]++;
            }
            if (ui) {
               boolean rec = UiDeps.recording();
               int id = UiDeps.topId;
               if (!rec || e.uiLog != id) {
                  Object[] ch = e.chain;
                  for (int i = 0; i < ch.length; i += 3) {
                     UiDeps.read((se.krka.kahlua.j2se.KahluaTableImpl)ch[i], ch[i + 1], ch[i + 2]);
                  }
                  if (rec) {
                     e.uiLog = id;
                  }
               }
            }
            return null; // proven nil for this class
         }
      }
      Object r = t.pzoptJitTablegetMiss(kt, key);
      if (STATS && !ui) {
         walkStat(kt, key, r);
      }
      if (r == null && mt != null && kt.getMetatable() == mt && ver == se.krka.kahlua.j2se.KahluaTableImpl.pzoptMetaVersion && k == kv[ic.bucket]
            && iv == kv[INDEX_BUCKET]) {
         Object[] ch;
         boolean was = UiDeps.on;
         UiDeps.on = false; // the proof's own rawgets are not reads of a render
         try {
            ch = kt.pzoptNilChain(key);
         } finally {
            UiDeps.on = was;
         }
         if (ch != null) {
            if (STATS) {
               stat[17]++;
            }
            LuaJitIc.Nil a = ic.nilA;
            if (a != null && a.mt != mt) {
               ic.nilB = a; // another metatable's entry (the lookup above moved this one's to the front): it stays second
            }
            boolean rec = ui && UiDeps.recording();
            ic.nilA = new LuaJitIc.Nil(mt, ver, k, iv, ch, rec ? UiDeps.topId : 0); // the walk just logged its reads in this recording
         }
      }
      return r;
   }

   static Object nilUi(KahluaThread t, se.krka.kahlua.j2se.KahluaTableImpl kt, Object key, LuaJitIc ic) {
      Object mt = kt.getMetatable();
      long[] kv = se.krka.kahlua.j2se.KahluaTableImpl.pzoptKeyVer;
      long ver = se.krka.kahlua.j2se.KahluaTableImpl.pzoptMetaVersion, k = kv[ic.bucket], iv = kv[INDEX_BUCKET];
      Object[] ch = ic.nilChain;
      if (ch != null && mt != null && ic.nilMt == mt && ic.nilVer == ver && ic.nilKv == k && ic.nilIv == iv) {
         boolean rec = UiDeps.recording();
         int id = UiDeps.topId;
         if (!rec || ic.nilUiChain != ch || ic.nilUiLog != id) {
            for (int i = 0; i < ch.length; i += 3) {
               UiDeps.read((se.krka.kahlua.j2se.KahluaTableImpl)ch[i], ch[i + 1], ch[i + 2]);
            }
            if (rec) {
               ic.nilUiChain = ch;
               ic.nilUiLog = id;
            }
         }
         return null;
      }
      Object r = t.pzoptJitTablegetMiss(kt, key);
      if (r == null && mt != null && kt.getMetatable() == mt && ver == se.krka.kahlua.j2se.KahluaTableImpl.pzoptMetaVersion && k == kv[ic.bucket]
            && iv == kv[INDEX_BUCKET]) {
         boolean was = UiDeps.on;
         UiDeps.on = false; // the proof's own rawgets are not reads of the render
         try {
            ch = kt.pzoptNilChain(key);
         } finally {
            UiDeps.on = was;
         }
         if (ch != null) {
            ic.nilMt = mt;
            ic.nilVer = ver;
            ic.nilKv = k;
            ic.nilIv = iv;
            ic.nilChain = ch;
            boolean rec = UiDeps.recording();
            ic.nilUiChain = rec ? ch : null; // the walk just logged them in this recording
            ic.nilUiLog = rec ? UiDeps.topId : 0;
         }
      }
      return r;
   }
   static final int INDEX_BUCKET = se.krka.kahlua.j2se.KahluaTableImpl.pzoptKeyBucket("__index");
   static final boolean TABLEGET_ZB_ONLY = TABLEGET_HOOKED && Config.LUA_JIT_ZB_TABLEGET
         && ModCompat.patchedOnlyBy("se/krka/kahlua/vm/KahluaThread.tableget", "me/zed_0xff/zombie_buddy/patches/");

   /**
    * Compiled SETTABLE of a constant String key: when the table's own LuaMap already holds a value under the key (the
    * site's remembered position, else one lookup), stock tableSet's rawget is non-null and its rawset replaces that value:
    * done here with rawset's bookkeeping (KahluaTableImpl.pzoptJitSetSlot). A nil value, an absent key or anything but a
    * plain LuaMap-backed table takes the stock tableSet.
    */
   public static void setStr(KahluaThread t, Object table, Object key, Object value, LuaJitIc ic) {
      if (FAST_TABLE && value != null && table instanceof se.krka.kahlua.j2se.KahluaTableImpl kt && (UI_DEPS || !UiDeps.on)) {
         LuaMap m = kt.pzoptJitMap();
         if (m != null) {
            int s = ic.slot;
            if (s >= m.used || m.keys[s] != key) {
               s = m.find(key, LuaMap.hash(key));
               if (s >= 0) {
                  ic.slot = s;
               }
               if (STATS) {
                  stat[7]++;
               }
            } else if (STATS) {
               stat[6]++;
            }
            Object old;
            if (s >= 0 && (old = m.vals[s]) != null) {
               if (UiDeps.on) {
                  UiDeps.read(kt, key, old); // tableSet's rawget before its rawset (pzoptJitSetSlot logs the write)
               }
               if (kt.pzoptJitSetSlot(m, s, value)) {
                  return;
               }
            }
         }
      }
      if (STATS) {
         stat[8]++;
      }
      t.tableSet(table, key, value);
   }

   /**
    * getStr's miss for a Java object (or nil): the stock tableget, and when that answer came straight from the class
    * metatable's __index table, the site remembers it and the tables it was read through are watched.
    */
   static Object javaIndex(KahluaThread t, Object obj, Object key, LuaJitIc ic) {
      if (obj != null && (UI_DEPS || !UiDeps.on) && t.getmetatable(obj, true) instanceof se.krka.kahlua.j2se.KahluaTableImpl mt) {
         int epoch = se.krka.kahlua.j2se.KahluaTableImpl.pzoptJitEpoch;
         if (mt.rawget("__index") instanceof se.krka.kahlua.j2se.KahluaTableImpl idx) {
            Object v = idx.rawget(key);
            if (v != null && epoch == se.krka.kahlua.j2se.KahluaTableImpl.pzoptJitEpoch) {
               watch(mt);
               watch(idx);
               ic.java = new LuaJitIc.Java(obj.getClass(), t, v, epoch, mt, idx);
               return v;
            }
         }
      }
      return t.tableget(obj, key);
   }

   /** The table and its metatable chain (rawget falls back through it): a write to any of them drops what the sites remember. */
   static void watch(se.krka.kahlua.j2se.KahluaTableImpl tb) {
      for (int i = 0; i < 64 && tb != null; i++) {
         tb.pzoptJitWatched = true;
         tb = tb.getMetatable() instanceof se.krka.kahlua.j2se.KahluaTableImpl m ? m : null;
      }
   }

   static synchronized void queue(Prototype p) {
      if (p.pzoptJitState != 0) {
         return;
      }
      p.pzoptJitState = 1;
      startWorkers();
      queue.add(p);
   }

   /** Other background work of the Lua VM pass (luaJavaFast's classes), on the same worker. */
   static synchronized void queueTask(Runnable r) {
      startWorkers();
      queue.add(r);
   }

   private static void work() {
      for (;;) {
         Object o;
         try {
            o = queue.poll(10, java.util.concurrent.TimeUnit.SECONDS);
         } catch (InterruptedException e) {
            return;
         }
         if (Thread.currentThread() == worker && System.nanoTime() - lastLogNanos > 10_000_000_000L) {
            // every 10 s (busy or idle), when it changed: what is compiled, the sites' relinks, the hottest functions
            // that run interpreted
            lastLogNanos = System.nanoTime();
            String line = summary() + " " + LuaJavaFast.summary() + " " + LuaJitSites.summary() + hotRefused();
            if (!line.equals(lastLogged)) {
               lastLogged = line;
               Log.info("luaJit: epoch_ms=" + System.currentTimeMillis() + " " + line);
            }
         }
         if (o == null) {
            continue;
         }
         if (o instanceof Prototype p) {
            compileNow(p);
         } else {
            try {
               ((Runnable)o).run();
            } catch (Throwable e) {
               Log.warn("luaJit: background task failed: " + e);
            }
         }
      }
   }

   private static String lastLogged = "";
   private static long lastLogNanos = System.nanoTime();
   /** Functions the compiler refused (unsupported shape, too big): their entries keep counting in pzoptJitCount. */
   static final java.util.concurrent.ConcurrentLinkedQueue<Prototype> refusedProtos = new java.util.concurrent.ConcurrentLinkedQueue<>();

   /** The five refused functions with the most entries (they run in the interpreter). */
   static String hotRefused() {
      java.util.List<Prototype> l = new java.util.ArrayList<>(refusedProtos);
      if (l.isEmpty()) {
         return "";
      }
      l.sort((x, y) -> Integer.compare(y.pzoptJitCount, x.pzoptJitCount));
      StringBuilder sb = new StringBuilder(" hot_interpreted=");
      for (int i = 0; i < Math.min(5, l.size()); i++) {
         Prototype p = l.get(i);
         sb.append(i > 0 ? "; " : "").append(LuaJitCompiler.describe(p)).append(" x").append(p.pzoptJitCount).append(' ')
               .append(p.pzoptJitWhy);
      }
      return sb.toString();
   }

   /** Compiles one function now (the worker, tests); its code is installed on success. */
   public static boolean compileNow(Prototype p) {
      long t0 = System.nanoTime();
      try {
         LuaJitCode code = LuaJitCompiler.compile(p, lookup());
         long dt = System.nanoTime() - t0;
         compileNanos.addAndGet(dt);
         if (code == null) {
            p.pzoptJitState = 3;
            failed.incrementAndGet();
            refusedProtos.add(p);
            return false;
         }
         code.proto = p;
         LuaJitCode old = p.pzoptJit;
         p.pzoptJit = code;
         if (old != null) {
            old.next = code; // luaJitSpecArith: compiled again without speculation
         }
         p.pzoptJitState = 2;
         long n = compiled.incrementAndGet();
         if (Config.DEV_LUA_JIT_LOG) {
            Log.info("luaJit: compiled " + code.name + " in " + dt / 1000 + " us");
         } else if (n == 1 || n == 100 || n == 1000 || n % 5000 == 0) {
            Log.info("luaJit: " + summary());
         }
         return true;
      } catch (Throwable e) {
         p.pzoptJitState = 3;
         p.pzoptJitWhy = String.valueOf(e);
         refusedProtos.add(p);
         long n = failed.incrementAndGet();
         if (Config.DEV_LUA_JIT_LOG || n <= 5) {
            Log.warn("luaJit: could not compile " + LuaJitCompiler.describe(p) + ": " + e);
         }
         return false;
      }
   }

   public static String summary() {
      return "compiled=" + compiled.get() + " failed=" + failed.get() + " compile_ms=" + compileNanos.get() / 1_000_000
            + " code_kb=" + codeBytes.get() / 1024 + " exits=" + exits[0] + "/" + exits[1] + "/" + exits[2] + "/" + exits[3]
            + "/" + exits[5] + " split=" + LuaJitCompiler.splitFunctions.get() + "/" + LuaJitCompiler.splitRegions.get() + " whole_huge=" + LuaJitCompiler.wholeHuge.get() + "/" + LuaJitCompiler.WHOLE
            + (LuaJitCompiler.SPEC ? " spec_fails=" + specFails.get() + "/" + NO_SPEC.size() : "") + LuaJitDual.summary()
            + (STATS ? " get=" + stat[0] + "/" + stat[1] + "/" + stat[2] + "/" + stat[3] + "/" + stat[4] + "/" + stat[5] + " set=" + stat[6] + "/" + stat[7] + "/" + stat[8]
               + " miss_ic=" + stat[9] + "/" + stat[15] + "/" + stat[10] + " miss_why=" + stat[12] + "/" + stat[13] + "/" + stat[14] + " nil_ic=" + stat[16] + "/" + stat[17] + " slot2=" + stat[18] + " slot34=" + stat[19] + " meta_version=" + se.krka.kahlua.j2se.KahluaTableImpl.pzoptMetaVersion + " meta_writes=" + se.krka.kahlua.j2se.KahluaTableImpl.pzoptMetaWrites
               + " mt_sets=" + se.krka.kahlua.j2se.KahluaTableImpl.pzoptMtSets + walkTop() : "");
   }

   private static synchronized MethodHandles.Lookup lookup() throws IllegalAccessException {
      if (lookup == null) {
         lookup = MethodHandles.privateLookupIn(KahluaThread.class, MethodHandles.lookup());
      }
      return lookup;
   }
}
