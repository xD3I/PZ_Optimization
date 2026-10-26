package pzopt;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.util.concurrent.atomic.AtomicLong;
import se.krka.kahlua.integration.expose.LuaJavaInvoker;
import se.krka.kahlua.integration.expose.MultiLuaJavaInvoker;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaCallFrame;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Prototype;

/**
 * luaJitIndyCalls (Lua VM pass, 2026-10-25): every CALL of compiled code is an invokedynamic site with a polymorphic
 * inline cache of up to {@link #MAX_TARGETS} callees.
 *
 * A site starts unlinked: its first calls go through {@link #miss}, which runs the call the stock way
 * (KahluaThread.pzoptJitCall) and, when the callee is a compiled Lua function or an exposed Java method with a
 * generated call (luaJavaFast), puts a guard for that callee in front of the site's target: a Lua callee is recognised
 * by its Prototype and entered through its code object (bound as a constant: HotSpot devirtualises and can inline it),
 * a Java method by its invoker's identity and called through its generated class. A callee the site has no fast form
 * for leaves the site as it is (the next call looks again); after {@link #MAX_TARGETS} links, or after {@link #MAX_MISSES}
 * calls the site could not link, the site's fallback becomes the plain stock call. The call itself is the same
 * operation in every form (frame push, results, deopt statuses), so a guard that fails only costs the slower path.
 */
public final class LuaJitSites {
   static final int MAX_TARGETS = 4;
   static final int MAX_MISSES = 64;
   static final MethodType TYPE = MethodType.methodType(int.class, KahluaThread.class, LuaCallFrame.class, Object.class, int.class, int.class,
         boolean.class);
   static final MethodHandle GENERIC, MISS, CALL_LUA, IS_PROTO, CALL_JAVA, IS_SAME;
   static final AtomicLong sites = new AtomicLong(), links = new AtomicLong(), megamorphic = new AtomicLong();
   static final boolean NO_GENERIC = Config.LUA_JIT_SITE_NO_GENERIC;
   static final int PATIENCE = Config.LUA_JIT_SITE_PATIENCE, CALLEE_THRESHOLD = Config.LUA_JIT_CALLEE_THRESHOLD;
   /** relinks (setTarget after the bootstrap), the time they took, those after 256+ missed calls, links left out (luaJitSitePatience) */
   static final AtomicLong relinks = new AtomicLong(), relinkNanos = new AtomicLong(), relinksLate = new AtomicLong(), patienceSkips = new AtomicLong(),
         calleeQueued = new AtomicLong();

   static void relink(MutableCallSite s, MethodHandle h, int missed) {
      long t0 = System.nanoTime();
      s.setTarget(h);
      relinkNanos.addAndGet(System.nanoTime() - t0);
      relinks.incrementAndGet();
      if (missed >= 256) {
         relinksLate.incrementAndGet();
      }
   }

   /** luaJitCalleeThreshold: a callee without code, called from compiled code, queued early. */
   static void calleeSeen(Prototype p) {
      if (CALLEE_THRESHOLD > 0 && p.pzoptJitState == 0 && p.pzoptJitCount >= CALLEE_THRESHOLD) {
         calleeQueued.incrementAndGet();
         LuaJit.queue(p);
      }
   }

   static {
      try {
         MethodHandles.Lookup l = MethodHandles.lookup();
         GENERIC = l.findVirtual(KahluaThread.class, "pzoptJitCall",
               MethodType.methodType(int.class, LuaCallFrame.class, Object.class, int.class, int.class, boolean.class));
         MISS = l.findStatic(LuaJitSites.class, "miss", TYPE.insertParameterTypes(0, Site.class));
         CALL_LUA = l.findStatic(LuaJitSites.class, "callLua", TYPE.insertParameterTypes(0, LuaJitCode.class));
         IS_PROTO = l.findStatic(LuaJitSites.class, "isProto", MethodType.methodType(boolean.class, Prototype.class, Object.class));
         CALL_JAVA = l.findStatic(LuaJitSites.class, "callJava", TYPE.insertParameterTypes(0, LuaJavaInvoker.class, LuaJavaFast.class));
         IS_SAME = l.findStatic(LuaJitSites.class, "isSame", MethodType.methodType(boolean.class, Object.class, Object.class));
      } catch (ReflectiveOperationException e) {
         throw new ExceptionInInitializerError(e);
      }
   }

   private LuaJitSites() {
   }

   static final class Site extends MutableCallSite {
      int targets;
      int misses;
      int calls; // calls through the miss handler
      final MethodHandle miss;
      final java.util.ArrayList<MethodHandle[]> guards = new java.util.ArrayList<>(2);

      Site() {
         super(TYPE);
         this.miss = MethodHandles.insertArguments(MISS, 0, this);
         this.setTarget(this.miss);
      }

      /** The guards in link order (the first-linked callee tested first), then the terminal handle. */
      MethodHandle chain(MethodHandle terminal) {
         MethodHandle c = terminal;
         for (int i = this.guards.size() - 1; i >= 0; i--) {
            MethodHandle[] g = this.guards.get(i);
            c = MethodHandles.guardWithTest(g[0], g[1], c);
         }
         return c;
      }
   }

   /** The bootstrap of every compiled CALL site: (KahluaThread, LuaCallFrame, Object function, int a, int nArguments, boolean restoreTop)int. */
   public static CallSite bootstrap(MethodHandles.Lookup lookup, String name, MethodType type) {
      sites.incrementAndGet();
      return new Site();
   }

   static int miss(Site s, KahluaThread t, LuaCallFrame f, Object fun, int a, int n, boolean restoreTop) {
      int missed = s.calls;
      if (missed < Integer.MAX_VALUE) {
         s.calls = missed + 1;
      }
      if (s.targets < MAX_TARGETS && s.misses < MAX_MISSES) {
         MethodHandle fast = null, test = null;
         boolean never = true; // this callee will never have a fast form (else it may get one: not compiled / generated yet)
         if (fun instanceof LuaClosure lc) {
            LuaJitCode code = lc.prototype.pzoptJit;
            if (code != null) {
               test = MethodHandles.insertArguments(IS_PROTO, 0, lc.prototype);
               fast = MethodHandles.insertArguments(CALL_LUA, 0, code);
            }
            never = lc.prototype.pzoptJitState == 3;
            if (code == null) {
               calleeSeen(lc.prototype);
            }
         } else if (fun instanceof LuaJavaInvoker inv) {
            LuaJavaFast jf = inv.pzoptFast();
            if (jf != null) {
               test = MethodHandles.insertArguments(IS_SAME, 0, inv);
               fast = MethodHandles.insertArguments(CALL_JAVA, 0, inv, jf);
            }
            never = inv.pzoptFastIsNone();
         }
         if (fast != null && PATIENCE > 0 && missed > PATIENCE) {
            patienceSkips.incrementAndGet();
            s.targets = MAX_TARGETS; // luaJitSitePatience: the site stays as it is, its misses take the plain call
         } else if (fast != null) {
            s.targets++;
            links.incrementAndGet();
            s.guards.add(new MethodHandle[] {MethodHandles.dropArguments(test, 0, KahluaThread.class, LuaCallFrame.class), fast});
            // the site keeps missing into here until it is full; then a callee it does not know takes the stock call
            relink(s, s.chain(s.targets == MAX_TARGETS && !NO_GENERIC ? GENERIC : s.miss), missed);
         } else if (never && ++s.misses == MAX_MISSES) {
            megamorphic.incrementAndGet();
            if (!NO_GENERIC) {
               relink(s, s.chain(GENERIC), missed);
            }
         }
      }
      return t.pzoptJitCall(f, fun, a, n, restoreTop);
   }

   static boolean isProto(Prototype p, Object fun) {
      return fun instanceof LuaClosure lc && lc.prototype == p;
   }

   static boolean isSame(Object a, Object b) {
      return a == b;
   }

   static int callLua(LuaJitCode code, KahluaThread t, LuaCallFrame f, Object fun, int a, int n, boolean restoreTop) {
      LuaCallFrame nf = t.pzoptJitPushLua(f, (LuaClosure)fun, a, n, restoreTop);
      if (nf == null) {
         return t.pzoptJitCall(f, fun, a, n, restoreTop);
      }
      return t.pzoptJitAfterLua(f, nf, code.run(t, nf), restoreTop);
   }

   static int callJava(LuaJavaInvoker inv, LuaJavaFast jf, KahluaThread t, LuaCallFrame f, Object fun, int a, int n, boolean restoreTop) {
      if (!LuaJavaFast.active()) {
         return t.pzoptJitCall(f, fun, a, n, restoreTop);
      }
      int frames = f.coroutine.getCallframeTop();
      LuaCallFrame javaFrame = t.pzoptJitPushJava(f, inv, a, n, restoreTop);
      int r = jf.call(javaFrame, n);
      return t.pzoptJitAfterJava(f, javaFrame, inv, r, n, frames, restoreTop);
   }

   // ------------------------------------------------------------------------------------------------ luaJitDirectArgs

   /**
    * luaJitDirectArgs: the result of a direct-arguments site whose call went the stack way (the arguments written to the
    * Lua stack from R(A), the results there afterwards, the status in KahluaThread.pzoptJitSt); any other result is the
    * first result of an exposed Java method called with the arguments as JVM values (nothing on the Lua stack).
    */
   public static final Object STACK = new Object() {
      @Override
      public String toString() {
         return "pzopt.LuaJitSites.STACK";
      }
   };
   public static final int MAX_ARGS = 8;
   static final MethodHandle[] LUA_K = new MethodHandle[MAX_ARGS + 1], GEN_K = new MethodHandle[MAX_ARGS + 1], SIG_K = new MethodHandle[MAX_ARGS + 1];
   static final MethodHandle MISS_ARGS;
   static final AtomicLong argSites = new AtomicLong(), argLinks = new AtomicLong(), argJava = new AtomicLong(), argMulti = new AtomicLong();

   static {
      try {
         MethodHandles.Lookup l = MethodHandles.lookup();
         for (int k = 0; k <= MAX_ARGS; k++) {
            LUA_K[k] = l.findStatic(LuaJitSites.class, "lua" + k, typeArgs(k).insertParameterTypes(0, LuaJitCode.class));
            GEN_K[k] = l.findStatic(LuaJitSites.class, "gen" + k, typeArgs(k));
            MethodType st = typeArgs(k).changeReturnType(boolean.class).insertParameterTypes(0, Object.class);
            for (int i = 0; i < k; i++) {
               st = st.insertParameterTypes(1 + i, Class.class);
            }
            SIG_K[k] = l.findStatic(LuaJitSites.class, "sig" + k, st);
         }
         MISS_ARGS = l.findStatic(LuaJitSites.class, "missArgs", MethodType.methodType(Object.class, ArgSite.class, KahluaThread.class,
               LuaCallFrame.class, Object.class, int.class, boolean.class, Object[].class));
      } catch (ReflectiveOperationException e) {
         throw new ExceptionInInitializerError(e);
      }
   }

   /** (KahluaThread, LuaCallFrame, Object function, int a, boolean restoreTop, Object x1 .. xk)Object. */
   public static MethodType typeArgs(int k) {
      MethodType t = MethodType.methodType(Object.class, KahluaThread.class, LuaCallFrame.class, Object.class, int.class, boolean.class);
      for (int i = 0; i < k; i++) {
         t = t.appendParameterTypes(Object.class);
      }
      return t;
   }

   /** typeArgs(k) with x{i+1} a double for every bit i of dmask (luaJitTypedArgs: a compiled site's unboxed numbers). */
   public static MethodType typeArgs(int k, int dmask) {
      return typeArgs(k, dmask, 0);
   }

   /** typeArgs(k) with x{i+1} a double for bit i of dmask and a Double for bit i of nmask (a register known to hold a number). */
   public static MethodType typeArgs(int k, int dmask, int nmask) {
      MethodType t = typeArgs(k);
      for (int i = 0; i < k; i++) {
         if ((dmask >> i & 1) != 0) {
            t = t.changeParameterType(5 + i, double.class);
         } else if ((nmask >> i & 1) != 0) {
            t = t.changeParameterType(5 + i, Double.class);
         }
      }
      return t;
   }

   /**
    * luaJitTypedArgs: h, whose x{i+1} are doubles at the bits of hmask and objects elsewhere, as a handle of the site type
    * (doubles at dmask: numbers the compiled code held unboxed; Doubles at nmask: boxed numbers). An unboxed number becomes
    * what stock's box of it holds (-0.0 boxes as +0.0, as BoxedStaticValues / LuaBox): normalized for a double parameter,
    * LuaBox.box for an object one; a boxed number is unboxed for a double parameter (its own value: a -0.0 constant stays).
    */
   public static MethodHandle typedArgs(MethodHandle h, int hmask, int dmask, int nmask, MethodType type) {
      return typedArgs(h, hmask, dmask, nmask, 0, type);
   }

   /**
    * typedArgs, and at the bits of omask the site passes an object that a class test in front (allDoubles) found to be a
    * Double, cast and unboxed for h's double parameter.
    */
   public static MethodHandle typedArgs(MethodHandle h, int hmask, int dmask, int nmask, int omask, MethodType type) {
      if (hmask == 0 && dmask == 0 && nmask == 0) {
         return h;
      }
      int k = h.type().parameterCount() - 5;
      MethodHandle[] fs = new MethodHandle[k];
      for (int i = 0; i < k; i++) {
         boolean hd = (hmask >> i & 1) != 0, d = (dmask >> i & 1) != 0, n = (nmask >> i & 1) != 0;
         if (hd) {
            fs[i] = d ? NORM : n ? UNBOX : (omask >> i & 1) != 0 ? OBJ_UNBOX : null;
            if (fs[i] == null) {
               throw new IllegalArgumentException("a double parameter without a number at " + i);
            }
         } else if (d) {
            fs[i] = BOX.asType(MethodType.methodType(h.type().parameterType(5 + i), double.class));
         }
      }
      return MethodHandles.filterArguments(h, 5, fs).asType(type.changeReturnType(h.type().returnType()));
   }

   static double norm(double d) {
      return d + 0.0;
   }

   /** A Double standing for an unboxed number in a class test (sigK compares only classes: no box needed). */
   static final Double PROBE = 0.0;

   static Object probe(double d) {
      return PROBE;
   }

   /**
    * luaJitTypedArgs: the overload test h (sigK, objects only) as a handle of the site type: an unboxed number is tested
    * as the Double stock would box it into, without boxing it (the class is all the test reads).
    */
   static MethodHandle typedTest(MethodHandle h, int dmask, int nmask, MethodType type) {
      if (dmask == 0) {
         return typedArgs(h, 0, 0, nmask, type);
      }
      int k = h.type().parameterCount() - 5;
      MethodHandle[] fs = new MethodHandle[k];
      for (int i = 0; i < k; i++) {
         if ((dmask >> i & 1) != 0) {
            fs[i] = PROBE_H;
         }
      }
      return MethodHandles.filterArguments(h, 5, fs).asType(type.changeReturnType(h.type().returnType()));
   }

   static final MethodHandle BOX, NORM, UNBOX, PROBE_H, OBJ_UNBOX;
   static final MethodHandle[] ALL_D = new MethodHandle[5];

   static double objUnbox(Object o) {
      return ((Double)o).doubleValue();
   }

   static boolean allD1(Object a) {
      return a instanceof Double;
   }

   static boolean allD2(Object a, Object b) {
      return a instanceof Double && b instanceof Double;
   }

   static boolean allD3(Object a, Object b, Object c) {
      return a instanceof Double && b instanceof Double && c instanceof Double;
   }

   static boolean allD4(Object a, Object b, Object c, Object d) {
      return a instanceof Double && b instanceof Double && c instanceof Double && d instanceof Double;
   }

   /** The test "the site's arguments at the bits of omask (at most 4, objects in the site type) are all Doubles", of the site type. */
   static MethodHandle allDoubles(int omask, MethodType type) {
      int n = Integer.bitCount(omask);
      MethodHandle m = ALL_D[n];
      int[] reorder = new int[n];
      for (int i = 0, j = 0; i < 31; i++) {
         if ((omask >> i & 1) != 0) {
            reorder[j++] = 5 + i;
         }
      }
      return MethodHandles.permuteArguments(m, type.changeReturnType(boolean.class), reorder);
   }

   static {
      try {
         MethodHandles.Lookup l = MethodHandles.lookup();
         BOX = l.findStatic(LuaBox.class, "box", MethodType.methodType(Double.class, double.class));
         NORM = l.findStatic(LuaJitSites.class, "norm", MethodType.methodType(double.class, double.class));
         UNBOX = l.findVirtual(Double.class, "doubleValue", MethodType.methodType(double.class));
         PROBE_H = l.findStatic(LuaJitSites.class, "probe", MethodType.methodType(Object.class, double.class));
         OBJ_UNBOX = l.findStatic(LuaJitSites.class, "objUnbox", MethodType.methodType(double.class, Object.class));
         for (int i = 1; i <= 4; i++) {
            Class<?>[] ps = new Class<?>[i];
            java.util.Arrays.fill(ps, Object.class);
            ALL_D[i] = l.findStatic(LuaJitSites.class, "allD" + i, MethodType.methodType(boolean.class, ps));
         }
      } catch (ReflectiveOperationException e) {
         throw new ExceptionInInitializerError(e);
      }
   }

   static final class ArgSite extends MutableCallSite {
      final int k;
      final int dmask, nmask; // luaJitTypedArgs: the arguments this site passes as doubles / as Doubles

      /** h (the arguments as objects) as a handle of this site's type. */
      MethodHandle adapt(MethodHandle h) {
         return typedArgs(h, 0, this.dmask, this.nmask, this.type());
      }
      int targets;
      int misses;
      int calls; // calls through the miss handler
      final MethodHandle miss;
      final java.util.ArrayList<MethodHandle[]> guards = new java.util.ArrayList<>(2);

      ArgSite(MethodType type, int k) {
         super(type);
         this.k = k;
         int m = 0, nm = 0;
         for (int i = 0; i < k; i++) {
            if (type.parameterType(5 + i) == double.class) {
               m |= 1 << i;
            } else if (type.parameterType(5 + i) == Double.class) {
               nm |= 1 << i;
            }
         }
         this.dmask = m;
         this.nmask = nm;
         this.miss = typedArgs(MethodHandles.insertArguments(MISS_ARGS, 0, this).asCollector(Object[].class, k), 0, m, nm, type);
         this.setTarget(this.miss);
      }

      MethodHandle chain(MethodHandle terminal) {
         MethodHandle c = terminal;
         for (int i = this.guards.size() - 1; i >= 0; i--) {
            MethodHandle[] g = this.guards.get(i);
            c = MethodHandles.guardWithTest(g[0], g[1], c);
         }
         return c;
      }
   }

   /** The bootstrap of a compiled CALL with a fixed argument count and fixed results (luaJitDirectArgs). */
   public static CallSite bootstrapArgs(MethodHandles.Lookup lookup, String name, MethodType type) {
      argSites.incrementAndGet();
      return new ArgSite(type, type.parameterCount() - 5);
   }

   static Object missArgs(ArgSite s, KahluaThread t, LuaCallFrame f, Object fun, int a, boolean restoreTop, Object[] x) {
      int missed = s.calls;
      if (missed < Integer.MAX_VALUE) {
         s.calls = missed + 1;
      }
      if (s.targets < MAX_TARGETS && s.misses < MAX_MISSES) {
         MethodHandle fast = null, test = null;
         boolean never = true;
         if (fun instanceof LuaClosure lc) {
            LuaJitCode code = lc.prototype.pzoptJit;
            if (code != null) {
               test = MethodHandles.insertArguments(IS_PROTO, 0, lc.prototype);
               fast = s.adapt(MethodHandles.insertArguments(LUA_K[s.k], 0, code));
            }
            never = lc.prototype.pzoptJitState == 3;
            if (code == null) {
               calleeSeen(lc.prototype);
            }
         } else if (fun instanceof LuaJavaInvoker inv) {
            LuaJavaFast jf = inv.pzoptFast();
            if (jf != null) {
               if (jf.direct != null && jf.directArity == s.k) {
                  test = MethodHandles.insertArguments(IS_SAME, 0, inv);
                  fast = jf.directFor(s.dmask, s.nmask, s.type());
                  argJava.incrementAndGet();
               }
               // a generated class without a direct form for this argument count never gets one
               never = fast == null;
            } else {
               never = inv.pzoptFastIsNone();
            }
         } else if (fun instanceof MultiLuaJavaInvoker m) {
            // an overloaded method: the overload stock picks depends only on the arguments' classes (null as its own), so
            // the site links (this function, these classes) to that overload's direct call
            LuaJavaInvoker inv = m.pzoptSelect(x);
            LuaJavaFast jf = inv != null ? inv.pzoptFast() : null;
            if (jf != null && jf.direct != null && jf.directArity == s.k) {
               Object[] bound = new Object[1 + s.k];
               bound[0] = m;
               for (int i = 0; i < s.k; i++) {
                  bound[1 + i] = cls(x[i]);
               }
               test = typedTest(MethodHandles.insertArguments(SIG_K[s.k], 0, bound), s.dmask, s.nmask, s.type());
               fast = jf.directFor(s.dmask, s.nmask, s.type());
               argMulti.incrementAndGet();
            }
            never = inv == null || jf != null && fast == null || jf == null && inv.pzoptFastIsNone();
         }
         if (fast != null && PATIENCE > 0 && missed > PATIENCE) {
            patienceSkips.incrementAndGet();
            s.targets = MAX_TARGETS; // luaJitSitePatience
         } else if (fast != null) {
            s.targets++;
            argLinks.incrementAndGet();
            s.guards.add(new MethodHandle[] {test.type().parameterCount() == 1 ? MethodHandles.dropArguments(test, 0, KahluaThread.class, LuaCallFrame.class) : test, fast});
            relink(s, s.chain(s.targets == MAX_TARGETS && !NO_GENERIC ? s.adapt(GEN_K[s.k]) : s.miss), missed);
         } else if (never && ++s.misses == MAX_MISSES) {
            megamorphic.incrementAndGet();
            if (!NO_GENERIC) {
               relink(s, s.chain(s.adapt(GEN_K[s.k])), missed);
            }
         }
      }
      for (int i = 0; i < x.length; i++) {
         f.set(a + 1 + i, x[i]);
      }
      return genStack(t, f, fun, a, x.length, restoreTop);
   }

   static Class<?> cls(Object o) {
      return o == null ? null : o.getClass();
   }

   static boolean sig0(Object m, KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop) {
      return fn == m;
   }

   static boolean sig1(Object m, Class<?> c1, KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1) {
      return fn == m && cls(x1) == c1;
   }

   static boolean sig2(Object m, Class<?> c1, Class<?> c2, KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1, Object x2) {
      return fn == m && cls(x1) == c1 && cls(x2) == c2;
   }

   static boolean sig3(Object m, Class<?> c1, Class<?> c2, Class<?> c3, KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1, Object x2, Object x3) {
      return fn == m && cls(x1) == c1 && cls(x2) == c2 && cls(x3) == c3;
   }

   static boolean sig4(Object m, Class<?> c1, Class<?> c2, Class<?> c3, Class<?> c4, KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1, Object x2, Object x3, Object x4) {
      return fn == m && cls(x1) == c1 && cls(x2) == c2 && cls(x3) == c3 && cls(x4) == c4;
   }

   static boolean sig5(Object m, Class<?> c1, Class<?> c2, Class<?> c3, Class<?> c4, Class<?> c5, KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1, Object x2, Object x3, Object x4, Object x5) {
      return fn == m && cls(x1) == c1 && cls(x2) == c2 && cls(x3) == c3 && cls(x4) == c4 && cls(x5) == c5;
   }

   static boolean sig6(Object m, Class<?> c1, Class<?> c2, Class<?> c3, Class<?> c4, Class<?> c5, Class<?> c6, KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1, Object x2, Object x3, Object x4, Object x5, Object x6) {
      return fn == m && cls(x1) == c1 && cls(x2) == c2 && cls(x3) == c3 && cls(x4) == c4 && cls(x5) == c5 && cls(x6) == c6;
   }

   static boolean sig7(Object m, Class<?> c1, Class<?> c2, Class<?> c3, Class<?> c4, Class<?> c5, Class<?> c6, Class<?> c7, KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1, Object x2, Object x3, Object x4, Object x5, Object x6, Object x7) {
      return fn == m && cls(x1) == c1 && cls(x2) == c2 && cls(x3) == c3 && cls(x4) == c4 && cls(x5) == c5 && cls(x6) == c6 && cls(x7) == c7;
   }

   static boolean sig8(Object m, Class<?> c1, Class<?> c2, Class<?> c3, Class<?> c4, Class<?> c5, Class<?> c6, Class<?> c7, Class<?> c8, KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1, Object x2, Object x3, Object x4, Object x5, Object x6, Object x7, Object x8) {
      return fn == m && cls(x1) == c1 && cls(x2) == c2 && cls(x3) == c3 && cls(x4) == c4 && cls(x5) == c5 && cls(x6) == c6 && cls(x7) == c7 && cls(x8) == c8;
   }

   /** A Lua callee with code, its arguments written to the Lua stack as the plain compiled call does. */
   static Object luaStack(LuaJitCode code, KahluaThread t, LuaCallFrame f, Object fn, int a, int n, boolean restoreTop) {
      f.set(a, fn);
      f.setTop(a + 1 + n);
      t.pzoptJitSt = callLua(code, t, f, fn, a, n, restoreTop);
      return STACK;
   }

   /** Any callee: the arguments written to the Lua stack, then the stock call of compiled code. */
   static Object genStack(KahluaThread t, LuaCallFrame f, Object fn, int a, int n, boolean restoreTop) {
      f.set(a, fn);
      f.setTop(a + 1 + n);
      t.pzoptJitSt = t.pzoptJitCall(f, fn, a, n, restoreTop);
      return STACK;
   }

   static Object lua0(LuaJitCode code, KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop) {
      return luaStack(code, t, f, fn, a, 0, restoreTop);
   }

   static Object gen0(KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop) {
      return genStack(t, f, fn, a, 0, restoreTop);
   }

   static Object lua1(LuaJitCode code, KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1) {
      f.set(a + 1, x1);
      return luaStack(code, t, f, fn, a, 1, restoreTop);
   }

   static Object gen1(KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1) {
      f.set(a + 1, x1);
      return genStack(t, f, fn, a, 1, restoreTop);
   }

   static Object lua2(LuaJitCode code, KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1, Object x2) {
      f.set(a + 1, x1);
      f.set(a + 2, x2);
      return luaStack(code, t, f, fn, a, 2, restoreTop);
   }

   static Object gen2(KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1, Object x2) {
      f.set(a + 1, x1);
      f.set(a + 2, x2);
      return genStack(t, f, fn, a, 2, restoreTop);
   }

   static Object lua3(LuaJitCode code, KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1, Object x2, Object x3) {
      f.set(a + 1, x1);
      f.set(a + 2, x2);
      f.set(a + 3, x3);
      return luaStack(code, t, f, fn, a, 3, restoreTop);
   }

   static Object gen3(KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1, Object x2, Object x3) {
      f.set(a + 1, x1);
      f.set(a + 2, x2);
      f.set(a + 3, x3);
      return genStack(t, f, fn, a, 3, restoreTop);
   }

   static Object lua4(LuaJitCode code, KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1, Object x2, Object x3, Object x4) {
      f.set(a + 1, x1);
      f.set(a + 2, x2);
      f.set(a + 3, x3);
      f.set(a + 4, x4);
      return luaStack(code, t, f, fn, a, 4, restoreTop);
   }

   static Object gen4(KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1, Object x2, Object x3, Object x4) {
      f.set(a + 1, x1);
      f.set(a + 2, x2);
      f.set(a + 3, x3);
      f.set(a + 4, x4);
      return genStack(t, f, fn, a, 4, restoreTop);
   }

   static Object lua5(LuaJitCode code, KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1, Object x2, Object x3, Object x4, Object x5) {
      f.set(a + 1, x1);
      f.set(a + 2, x2);
      f.set(a + 3, x3);
      f.set(a + 4, x4);
      f.set(a + 5, x5);
      return luaStack(code, t, f, fn, a, 5, restoreTop);
   }

   static Object gen5(KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1, Object x2, Object x3, Object x4, Object x5) {
      f.set(a + 1, x1);
      f.set(a + 2, x2);
      f.set(a + 3, x3);
      f.set(a + 4, x4);
      f.set(a + 5, x5);
      return genStack(t, f, fn, a, 5, restoreTop);
   }

   static Object lua6(LuaJitCode code, KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1, Object x2, Object x3, Object x4, Object x5, Object x6) {
      f.set(a + 1, x1);
      f.set(a + 2, x2);
      f.set(a + 3, x3);
      f.set(a + 4, x4);
      f.set(a + 5, x5);
      f.set(a + 6, x6);
      return luaStack(code, t, f, fn, a, 6, restoreTop);
   }

   static Object gen6(KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1, Object x2, Object x3, Object x4, Object x5, Object x6) {
      f.set(a + 1, x1);
      f.set(a + 2, x2);
      f.set(a + 3, x3);
      f.set(a + 4, x4);
      f.set(a + 5, x5);
      f.set(a + 6, x6);
      return genStack(t, f, fn, a, 6, restoreTop);
   }

   static Object lua7(LuaJitCode code, KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1, Object x2, Object x3, Object x4, Object x5, Object x6, Object x7) {
      f.set(a + 1, x1);
      f.set(a + 2, x2);
      f.set(a + 3, x3);
      f.set(a + 4, x4);
      f.set(a + 5, x5);
      f.set(a + 6, x6);
      f.set(a + 7, x7);
      return luaStack(code, t, f, fn, a, 7, restoreTop);
   }

   static Object gen7(KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1, Object x2, Object x3, Object x4, Object x5, Object x6, Object x7) {
      f.set(a + 1, x1);
      f.set(a + 2, x2);
      f.set(a + 3, x3);
      f.set(a + 4, x4);
      f.set(a + 5, x5);
      f.set(a + 6, x6);
      f.set(a + 7, x7);
      return genStack(t, f, fn, a, 7, restoreTop);
   }

   static Object lua8(LuaJitCode code, KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1, Object x2, Object x3, Object x4, Object x5, Object x6, Object x7, Object x8) {
      f.set(a + 1, x1);
      f.set(a + 2, x2);
      f.set(a + 3, x3);
      f.set(a + 4, x4);
      f.set(a + 5, x5);
      f.set(a + 6, x6);
      f.set(a + 7, x7);
      f.set(a + 8, x8);
      return luaStack(code, t, f, fn, a, 8, restoreTop);
   }

   static Object gen8(KahluaThread t, LuaCallFrame f, Object fn, int a, boolean restoreTop, Object x1, Object x2, Object x3, Object x4, Object x5, Object x6, Object x7, Object x8) {
      f.set(a + 1, x1);
      f.set(a + 2, x2);
      f.set(a + 3, x3);
      f.set(a + 4, x4);
      f.set(a + 5, x5);
      f.set(a + 6, x6);
      f.set(a + 7, x7);
      f.set(a + 8, x8);
      return genStack(t, f, fn, a, 8, restoreTop);
   }

   public static String summary() {
      return "sites=" + sites.get() + " links=" + links.get() + " arg_sites=" + argSites.get() + " arg_links=" + argLinks.get()
            + " arg_java=" + argJava.get() + " arg_multi=" + argMulti.get() + " megamorphic=" + megamorphic.get()
            + " relinks=" + relinks.get() + "/" + relinkNanos.get() / 1_000_000 + "ms late=" + relinksLate.get() + " patience_skips=" + patienceSkips.get()
            + " callee_queued=" + calleeQueued.get();
   }
}
