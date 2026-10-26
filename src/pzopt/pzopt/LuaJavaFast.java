package pzopt;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import se.krka.kahlua.converter.KahluaConverterManager;
import se.krka.kahlua.integration.expose.LuaJavaInvoker;
import se.krka.kahlua.integration.expose.caller.Caller;
import se.krka.kahlua.integration.expose.caller.MethodCaller;
import se.krka.kahlua.vm.LuaCallFrame;

/**
 * luaJavaFast (Lua VM pass, 2026-10-25): a Lua call of an exposed Java method without reflection.
 *
 * Stock LuaJavaInvoker.call takes a pooled MethodArguments and ReturnValues, checks and converts every argument through
 * the converter manager's class maps, calls Method.invoke with an Object[] and converts the result through the manager
 * again. After {@link #THRESHOLD} calls of one invoker, the luaJit worker generates a small hidden class for it whose
 * {@link #call} reads the arguments off the Lua stack, checks them with constant classes, converts numbers inline and
 * calls a constant MethodHandle of the method (so HotSpot can inline the Java method into it). It covers the shapes
 * that need no converter beyond the stock number / boolean ones: a fixed argument count, no varargs, no ReturnValues
 * parameter, a MethodCaller. Anything else, any argument the stock code would convert, reject or report an error for,
 * returns -1 and the stock path runs from the start (nothing was changed before the decision). An exception thrown by
 * the method is logged as MethodCaller logs it and the call returns 0 results, as stock.
 *
 * The inline number conversions are the ones zombie.Lua.KahluaNumberConverter installs; before a class is generated
 * the invoker's converter manager is probed with a set of values and an invoker whose manager answers differently (a
 * mod added converters) is never specialised.
 */
public abstract class LuaJavaFast {
   public static final boolean ON = Config.LUA_JAVA_FAST && Overrides.enabled();
   public static final int THRESHOLD = Math.max(1, Config.LUA_JAVA_FAST_THRESHOLD);
   static final boolean DIRECT = Config.LUA_JIT_DIRECT_ARGS && Config.LUA_JIT_INDY_CALLS;
   /** The invoker's shape is not covered: always the stock path. */
   public static final LuaJavaFast NONE = new LuaJavaFast() {
      @Override
      public int call(LuaCallFrame f, int n) {
         return -1;
      }
   };

   static final AtomicLong made = new AtomicLong();
   static final AtomicLong refused = new AtomicLong();

   /** Results pushed (0 or 1), or -1: run the stock path. */
   public abstract int call(LuaCallFrame f, int n);

   /**
    * luaJitDirectArgs: the generated dcall bound to this object, (KahluaThread, LuaCallFrame, Object function, int a,
    * boolean restoreTop, Object x1 .. x{directArity})Object, or null; see {@link LuaJitSites#STACK}.
    */
   public MethodHandle direct;
   public int directArity = -1;
   /**
    * luaJitTypedArgs: dcall with every numeric parameter (int, long, float, double, byte, char) taken as a JVM double
    * (bit i of numMask: the direct call's argument x{i+1}), for a compiled call site that passes those arguments unboxed.
    */
   public MethodHandle directD;
   public int numMask;
   public static final boolean TYPED_ARGS = Config.LUA_JIT_TYPED_ARGS;

   /**
    * The direct call for a site of the given type whose arguments in dmask are unboxed numbers (double) and in nmask boxed
    * numbers (Double): dcallD when every numeric parameter gets a number either way, else dcall.
    */
   public MethodHandle directFor(int dmask, int nmask, MethodType type) {
      if (this.directD != null && (this.numMask & ~(dmask | nmask)) == 0) {
         typedLinks.incrementAndGet();
         return LuaJitSites.typedArgs(this.directD, this.numMask, dmask, nmask, type);
      }
      MethodHandle plain = LuaJitSites.typedArgs(this.direct, 0, dmask, nmask, type);
      int omask = this.numMask & ~(dmask | nmask);
      if (this.directD != null && (this.numMask & dmask) != 0 && Integer.bitCount(omask) <= 4) {
         // luaJitTypedArgs: some numeric parameters get unboxed numbers, the others objects: dcallD while those objects
         // are Doubles (a class test in front), else the boxed call (stock's conversion or error of what they hold)
         typedLinks.incrementAndGet();
         MethodHandle fast = LuaJitSites.typedArgs(this.directD, this.numMask, dmask, nmask, omask, type);
         return MethodHandles.guardWithTest(LuaJitSites.allDoubles(omask, type), fast, plain);
      }
      return plain;
   }

   static final AtomicLong typedLinks = new AtomicLong();

   static int numMask(int[] kinds, boolean hasSelf) {
      int off = hasSelf ? 1 : 0, m = 0;
      for (int i = 0; i < kinds.length; i++) {
         if (kinds[i] != K_OBJ && kinds[i] != K_REF && kinds[i] != K_Z && off + i < 31) {
            m |= 1 << off + i;
         }
      }
      return m;
   }
   /** The exposed method's Lua name (devLuaJitDual's gate). */
   public String pzoptName;
   /** luaJavaFastUiDeps: what MethodCaller hands UiDeps (its method, owner, hasSelf, hasReturnValue). */
   Method uiMethod;
   Object uiOwner;
   boolean uiHasSelf;
   boolean uiHasResult;

   /**
    * luaJavaFastUiDeps (2026-10-26): the generated call runs while a retained UI render is recorded (UiDeps.on) and logs
    * the call as MethodCaller.call does (UiDeps.enter before the method, UiDeps.call with the converted arguments and the
    * raw result, UiDeps.threw on an exception, UiDeps.exit). Off: the stock path during recordings.
    */
   static final boolean UI = Config.LUA_JAVA_FAST_UI_DEPS;

   /**
    * luaJavaConvertCache (2026-10-26): the converter KahluaConverterManager picks for this call's result class, kept at
    * the call while the manager's converter set is the same (KahluaConverterManager.pzoptEpoch, bumped by
    * addJavaConverter, which clears the manager's own class cache). The manager looks it up in a HashMap per call and
    * for most results (game objects) calls the array converter, which answers null for every class that is not an
    * array: pass = the result goes to Lua as it is.
    */
   static final boolean CONVERT_CACHE = Config.LUA_JAVA_CONVERT_CACHE;

   /** One result class's converter (one field read: a racing refill never pairs a class with another's converter). */
   record Cv(Class<?> cls, se.krka.kahlua.converter.JavaToLuaConverter<Object> conv, int epoch, boolean pass) {
   }

   Cv cv;
   int cvMisses;

   /** Generated code: KahluaConverterManager.fromJavaToLua(o) through this call's converter cache. */
   public static Object toLua(Object o, KahluaConverterManager m, LuaJavaFast f) {
      if (o == null) {
         return null;
      }
      Cv e = f.cv;
      if (e != null && e.cls == o.getClass() && e.epoch == m.pzoptEpoch) {
         if (e.pass) {
            return o;
         }
         try { // KahluaConverterManager.fromJavaToLua's body after its lookup
            Object r = e.conv.fromJavaToLua(o);
            return r == null ? o : r;
         } catch (StackOverflowError err) {
            throw new RuntimeException("Could not convert " + String.valueOf(o) + ": it contained recursive elements.");
         }
      }
      return toLuaMiss(o, m, f);
   }

   static Object toLuaMiss(Object o, KahluaConverterManager m, LuaJavaFast f) {
      if (f.cvMisses < 64) { // a call whose results change class all the time keeps the manager's own lookup
         f.cvMisses++;
         int epoch = m.pzoptEpoch;
         Class<?> c = o.getClass();
         se.krka.kahlua.converter.JavaToLuaConverter<Object> conv = m.pzoptJavaConverter(c);
         f.cv = new Cv(c, conv, epoch, m.pzoptPassThrough(c, conv));
      }
      return m.fromJavaToLua(o);
   }

   /** Generated code: MethodCaller.call's UiDeps.call (self is the owner when the method takes none). */
   public static void uiCall(int d, LuaJavaFast f, Object self, Object[] args, Object ret) {
      UiDeps.call(d, f.uiMethod, f.uiHasSelf ? self : f.uiOwner, args, f.uiHasResult, ret);
   }

   /** Generated code: MethodCaller.call's UiDeps.threw. */
   public static void uiThrew(int d, LuaJavaFast f) {
      UiDeps.threw(d, f.uiMethod);
   }

   /** Generated code: MethodCaller.call's depth, -1 when nothing is recorded. */
   public static int uiEnter() {
      return UiDeps.on ? UiDeps.enter() : -1;
   }

   /** devLuaJitDual: the generated call's gate (a shadow run stops before a Java call it could not undo). */
   public static void dualGate(LuaJavaFast jf, LuaCallFrame f) {
      se.krka.kahlua.vm.KahluaThread t = f.getThread();
      if (t.pzoptDualShadow || t.pzoptDualFrame != null) {
         LuaJitDual.java(t, jf);
      }
   }

   /** devLuaJitDual, dcall's: true = take the stack path (a shadow run's Java call gets its Java frame, as the gates expect). */
   public static boolean dualGateT(LuaJavaFast jf, se.krka.kahlua.vm.KahluaThread t) {
      return t.pzoptDualShadow;
   }

   /** dcall's entry test: the generated call may run (as {@link #active}) and the Lua stack's top is at or above R(A). */
   public static boolean directOk(se.krka.kahlua.vm.KahluaThread t, LuaCallFrame f, int a) {
      return active() && f.coroutine.getTop() >= f.localBase + a;
   }

   /**
    * dcall after the method returned: its result, or (the coroutine or the frame stack changed, or lua was reset in it)
    * the stock state after callJava (the result at R(A), the top after it) and the stock status, as STACK.
    */
   public static Object directPost(se.krka.kahlua.vm.KahluaThread t, LuaCallFrame f, int a, int frames, Object r, boolean has) {
      se.krka.kahlua.vm.Coroutine co = f.coroutine;
      boolean same = t.currentCoroutine == co && co.getCallframeTop() == frames;
      if (same && !t.reset) {
         return r;
      }
      int n = has ? 1 : 0;
      if (has) {
         f.set(a, r);
      }
      f.setTop(a + n);
      if (!same) {
         t.pzoptJitSt = LuaJit.ST_AFTER_JAVA;
         return LuaJitSites.STACK;
      }
      f.setTop(f.closure.prototype.maxStacksize);
      t.pzoptJitSt = LuaJit.ST_RESET;
      return LuaJitSites.STACK;
   }

   /** dcall when the method threw: reported as stock reports it (MethodCaller.logException with the Java frame on the call stack), 0 results. */
   public static Object directThrow(se.krka.kahlua.vm.KahluaThread t, LuaCallFrame f, Object fn, int a, int n, Throwable e) {
      t.pzoptJitPushJavaFn(f, (se.krka.kahlua.vm.JavaFunction)fn, a, n, true); // the called function: the invoker, or the overload set it was picked from
      try {
         MethodCaller.pzoptLogException(e);
      } finally {
         f.coroutine.popCallFrame();
      }
      return null;
   }

   /** The invoker's hook: false while the stock path must run (uiVerify recording, the off phase of a luaJit A/B). */
   public static boolean active() {
      return (UI || !UiDeps.on) && !testOff && !(LuaJit.AB && !ZombAb.on(ZombAb.LUA_JIT));
   }

   /** Tests: the stock path everywhere (the reference run), and classes made on the calling thread. */
   static boolean testOff, testSync;

   /** Called by the invoker at its THRESHOLD-th call: its class is made on the luaJit worker and installed there. */
   public static void queue(LuaJavaInvoker inv, KahluaConverterManager manager, Class<?> clazz, Caller caller, boolean hasSelf,
         Class<?>[] params, boolean varargs, boolean needsReturnValues) {
      Runnable task = () -> {
         LuaJavaFast f;
         try {
            f = make(inv.toString(), manager, clazz, caller, hasSelf, params, varargs, needsReturnValues);
         } catch (Throwable e) {
            if (Config.DEV_LUA_JIT_LOG || refused.get() < 5) {
               Log.warn("luaJavaFast: " + inv + ": " + e);
            }
            f = null;
         }
         if (f == null) {
            refused.incrementAndGet();
            f = NONE;
         } else {
            made.incrementAndGet();
         }
         inv.pzoptSetFast(f);
      };
      if (testSync) {
         task.run();
      } else {
         LuaJit.queueTask(task);
      }
   }

   public static String summary() {
      return "java_fast=" + made.get() + " java_stock=" + refused.get() + (TYPED_ARGS ? " typed_links=" + typedLinks.get() : "");
   }

   // ------------------------------------------------------------------------------------------------ generation

   static final int K_OBJ = 0, K_REF = 1, K_D = 2, K_I = 3, K_J = 4, K_F = 5, K_B = 6, K_C = 7, K_Z = 8;

   static int kind(Class<?> c) {
      if (!c.isPrimitive()) {
         return c == Object.class ? K_OBJ : K_REF;
      }
      return c == double.class ? K_D : c == int.class ? K_I : c == long.class ? K_J : c == float.class ? K_F
            : c == byte.class ? K_B : c == char.class ? K_C : c == boolean.class ? K_Z : -1; // short: stock always rejects a number
   }

   static final double[] PROBES = {0.0, -0.0, 1.0, -1.0, 0.5, -0.5, 1.5, -2.5, 65.7, 255.9, 256.0, -129.0, 70000.3, 2.5e9, -2.5e9,
         1e19, -1e19, 9999.5, -9999.5, 10000.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MIN_VALUE};

   /** The stock manager converts a Lua number for a parameter of this kind as the generated code does. */
   static boolean argProbe(KahluaConverterManager m, int k, Class<?> type) {
      for (double d : PROBES) {
         Object want = switch (k) {
            case K_I -> (int)d;
            case K_J -> (long)d;
            case K_F -> (float)d;
            case K_B -> (byte)d;
            case K_C -> (char)(int)d;
            default -> null;
         };
         if (want == null) {
            return true;
         }
         Object got = m.fromLuaToJava(Double.valueOf(d), type);
         if (!want.equals(got)) {
            return false;
         }
      }
      return true;
   }

   /** The stock manager converts a primitive result of this kind as the generated code does (bit-exact Doubles). */
   static boolean retProbe(KahluaConverterManager m, Class<?> r) {
      if (r == void.class) {
         return true;
      }
      if (r == boolean.class) {
         return Boolean.TRUE.equals(m.fromJavaToLua(Boolean.TRUE)) && Boolean.FALSE.equals(m.fromJavaToLua(Boolean.FALSE));
      }
      for (double d : PROBES) {
         Object box;
         Object want;
         if (r == double.class) {
            box = Double.valueOf(d);
            if (m.fromJavaToLua(box) != box) {
               return false;
            }
            continue;
         } else if (r == int.class) {
            box = (int)d;
            want = zombie.core.BoxedStaticValues.toDouble((int)d);
         } else if (r == long.class) {
            box = (long)d;
            want = zombie.core.BoxedStaticValues.toDouble((long)d);
         } else if (r == float.class) {
            box = (float)d;
            want = zombie.core.BoxedStaticValues.toDouble((float)d);
         } else if (r == short.class) {
            box = (short)d;
            want = zombie.core.BoxedStaticValues.toDouble((short)d);
         } else if (r == byte.class) {
            box = (byte)d;
            want = zombie.core.BoxedStaticValues.toDouble((byte)d);
         } else if (r == char.class) {
            box = (char)(int)d;
            want = zombie.core.BoxedStaticValues.toDouble((char)(int)d);
         } else {
            return false;
         }
         if (!want.equals(m.fromJavaToLua(box))) {
            return false;
         }
      }
      return true;
   }

   static LuaJavaFast make(String name, KahluaConverterManager manager, Class<?> clazz, Caller caller, boolean hasSelf,
         Class<?>[] params, boolean varargs, boolean needsReturnValues) throws Throwable {
      if (!(caller instanceof MethodCaller mc) || varargs || needsReturnValues || manager == null || params.length > 12) {
         return null;
      }
      Method m = mc.pzoptMethod();
      Object owner = mc.pzoptOwner();
      boolean isStatic = Modifier.isStatic(m.getModifiers());
      if (m.isVarArgs() || !isStatic && !hasSelf && owner == null || hasSelf != mc.hasSelf()) {
         return null;
      }
      int[] kinds = new int[params.length];
      for (int i = 0; i < params.length; i++) {
         kinds[i] = kind(params[i]);
         if (kinds[i] < 0 || !argProbe(manager, kinds[i], params[i])) {
            return null;
         }
      }
      Class<?> rt = m.getReturnType();
      if (rt.isPrimitive() && !retProbe(manager, rt)) {
         return null;
      }
      MethodHandle mh = MethodHandles.lookup().unreflect(m).asFixedArity();
      boolean passSelf = false;
      if (!isStatic) {
         if (hasSelf) {
            passSelf = true;
         } else {
            mh = mh.bindTo(owner);
         }
      }
      // erased call type: receiver and reference parameters as Object, primitives as they are, a reference result as Object
      List<Class<?>> pt = new ArrayList<>();
      if (passSelf) {
         pt.add(Object.class);
      }
      for (Class<?> c : params) {
         pt.add(c.isPrimitive() ? c : Object.class);
      }
      MethodType erased = MethodType.methodType(rt.isPrimitive() ? rt : Object.class, pt);
      if (mh.type().parameterCount() != erased.parameterCount()) {
         return null;
      }
      mh = mh.asType(erased);
      byte[] bytes = build(erased, kinds, hasSelf, passSelf, params.length, rt);
      List<Object> data = new ArrayList<>();
      data.add(mh); // 0
      data.add(clazz); // 1
      data.add(manager); // 2
      for (Class<?> c : params) {
         data.add(c); // 3 + i
      }
      MethodHandles.Lookup hl = MethodHandles.lookup().defineHiddenClassWithClassData(bytes, Collections.unmodifiableList(data), true);
      LuaJavaFast f = (LuaJavaFast)hl.findConstructor(hl.lookupClass(), MethodType.methodType(void.class)).invoke();
      f.pzoptName = name;
      f.uiMethod = m;
      f.uiOwner = owner;
      f.uiHasSelf = mc.hasSelf();
      f.uiHasResult = rt != void.class;
      int arity = params.length + (hasSelf ? 1 : 0);
      if (DIRECT && arity <= LuaJitSites.MAX_ARGS) {
         f.direct = hl.findVirtual(hl.lookupClass(), "dcall", LuaJitSites.typeArgs(arity)).bindTo(f);
         f.directArity = arity;
         int nm = numMask(kinds, hasSelf);
         if (TYPED_ARGS && nm != 0) {
            f.directD = hl.findVirtual(hl.lookupClass(), "dcallD", LuaJitSites.typeArgs(arity, nm)).bindTo(f);
            f.numMask = nm;
         }
      }
      return f;
   }

   static final ClassDesc CD_SELF = ClassDesc.of("pzopt.LuaJavaFast");
   static final ClassDesc CD_LCF = ClassDesc.of("se.krka.kahlua.vm.LuaCallFrame");
   static final ClassDesc CD_MH = ConstantDescs.CD_MethodHandle;
   static final ClassDesc CD_CLASS = ConstantDescs.CD_Class;
   static final ClassDesc CD_MGR = ClassDesc.of("se.krka.kahlua.converter.KahluaConverterManager");
   static final ClassDesc CD_MC = ClassDesc.of("se.krka.kahlua.integration.expose.caller.MethodCaller");
   static final ClassDesc CD_BOXED = ClassDesc.of("zombie.core.BoxedStaticValues");
   static final ClassDesc CD_LUABOX = ClassDesc.of("pzopt.LuaBox");
   static final MethodTypeDesc MT_TOLUA = MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object, ClassDesc.of("se.krka.kahlua.converter.KahluaConverterManager"), ClassDesc.of("pzopt.LuaJavaFast"));
   static final ClassDesc CD_OBJ = ConstantDescs.CD_Object;
   static final ClassDesc CD_D = ConstantDescs.CD_Double;
   static final ClassDesc CD_Z = ConstantDescs.CD_Boolean;
   static final MethodTypeDesc MT_V = MethodTypeDesc.of(ConstantDescs.CD_void);
   static final MethodTypeDesc MT_CALL = MethodTypeDesc.of(ConstantDescs.CD_int, CD_LCF, ConstantDescs.CD_int);
   static final MethodTypeDesc MT_GET = MethodTypeDesc.of(CD_OBJ, ConstantDescs.CD_int);
   static final MethodTypeDesc MT_PUSH = MethodTypeDesc.of(ConstantDescs.CD_int, CD_OBJ);
   static final MethodTypeDesc MT_ISINST = MethodTypeDesc.of(ConstantDescs.CD_boolean, CD_OBJ);
   static final MethodTypeDesc MT_TODOUBLE = MethodTypeDesc.of(CD_D, ConstantDescs.CD_double);
   private static final ClassHierarchyResolver RESOLVER = ClassHierarchyResolver.ofClassLoading(LuaJavaFast.class.getClassLoader()).cached();

   static DynamicConstantDesc<?> cd(int i, ClassDesc type) {
      return DynamicConstantDesc.ofNamed(ConstantDescs.BSM_CLASS_DATA_AT, ConstantDescs.DEFAULT_NAME, type, i);
   }

   static byte[] build(MethodType erased, int[] kinds, boolean hasSelf, boolean passSelf, int np, Class<?> rt) {
      ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(RESOLVER));
      MethodTypeDesc callType = MethodTypeDesc.ofDescriptor(erased.toMethodDescriptorString());
      return cf.build(ClassDesc.of("pzopt.LuaJavaFastCall"), clb -> {
         clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
         clb.withSuperclass(CD_SELF);
         clb.withMethodBody(ConstantDescs.INIT_NAME, MT_V, ClassFile.ACC_PUBLIC, c -> {
            c.aload(0);
            c.invokespecial(CD_SELF, ConstantDescs.INIT_NAME, MT_V);
            c.return_();
         });
         clb.withMethodBody("call", MT_CALL, ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL, c -> body(c, callType, kinds, hasSelf, passSelf, np, rt));
         int arity = np + (hasSelf ? 1 : 0);
         if (DIRECT && arity <= LuaJitSites.MAX_ARGS) {
            clb.withMethodBody("dcall", MethodTypeDesc.ofDescriptor(LuaJitSites.typeArgs(arity).toMethodDescriptorString()),
                  ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL, c -> dbody(c, callType, kinds, hasSelf, passSelf, np, rt, 0));
            int nm = numMask(kinds, hasSelf);
            if (TYPED_ARGS && nm != 0) {
               clb.withMethodBody("dcallD", MethodTypeDesc.ofDescriptor(LuaJitSites.typeArgs(arity, nm).toMethodDescriptorString()),
                     ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL, c -> dbody(c, callType, kinds, hasSelf, passSelf, np, rt, nm));
            }
         }
      });
   }

   /** locals: 0 this, 1 frame, 2 n, 3 self, 4.. arguments, then the result. */
   static void body(CodeBuilder c, MethodTypeDesc callType, int[] kinds, boolean hasSelf, boolean passSelf, int np, Class<?> rt) {
      Label fail = c.newLabel();
      if (LuaJitDual.ON) {
         c.aload(0);
         c.aload(1);
         c.invokestatic(CD_SELF, "dualGate", MethodTypeDesc.of(ConstantDescs.CD_void, CD_SELF, CD_LCF));
      }
      int off = hasSelf ? 1 : 0;
      // the stock argument count rules: exactly the parameters (+ self); without self a 0-parameter method also takes 1
      if (!hasSelf && np == 0) {
         Label ok = c.newLabel();
         c.iload(2);
         c.ifeq(ok);
         c.iload(2);
         c.iconst_1();
         c.if_icmpne(fail);
         c.labelBinding(ok);
      } else {
         c.iload(2);
         c.ldc(np + off);
         c.if_icmpne(fail);
      }
      if (hasSelf) {
         c.aload(1);
         c.iconst_0();
         c.invokevirtual(CD_LCF, "get", MT_GET);
         c.astore(3);
         c.ldc(cd(1, CD_CLASS));
         c.aload(3);
         c.invokevirtual(CD_CLASS, "isInstance", MT_ISINST);
         c.ifeq(fail);
      }
      for (int i = 0; i < np; i++) {
         int slot = 4 + i;
         c.aload(1);
         c.ldc(off + i);
         c.invokevirtual(CD_LCF, "get", MT_GET);
         c.astore(slot);
         switch (kinds[i]) {
            case K_OBJ -> {
            }
            case K_REF -> {
               Label ok = c.newLabel();
               c.aload(slot);
               c.ifnull(ok);
               c.ldc(cd(3 + i, CD_CLASS));
               c.aload(slot);
               c.invokevirtual(CD_CLASS, "isInstance", MT_ISINST);
               c.ifeq(fail);
               c.labelBinding(ok);
            }
            case K_Z -> {
               c.aload(slot);
               c.instanceOf(CD_Z);
               c.ifeq(fail);
            }
            default -> {
               c.aload(slot);
               c.instanceOf(CD_D);
               c.ifeq(fail);
            }
         }
      }
      int res = 4 + np;
      int d = res + 1;
      Label start = c.newLabel(), end = c.newLabel(), handler = c.newLabel();
      if (UI) {
         c.invokestatic(CD_SELF, "uiEnter", MethodTypeDesc.of(ConstantDescs.CD_int));
         c.istore(d);
      }
      c.labelBinding(start);
      c.ldc(cd(0, CD_MH));
      if (passSelf) {
         c.aload(3);
      }
      for (int i = 0; i < np; i++) {
         c.aload(4 + i);
         switch (kinds[i]) {
            case K_OBJ, K_REF -> {
            }
            case K_Z -> {
               c.checkcast(CD_Z);
               c.invokevirtual(CD_Z, "booleanValue", MethodTypeDesc.of(ConstantDescs.CD_boolean));
            }
            default -> {
               c.checkcast(CD_D);
               c.invokevirtual(CD_D, "doubleValue", MethodTypeDesc.of(ConstantDescs.CD_double));
               switch (kinds[i]) {
                  case K_I -> c.d2i();
                  case K_J -> c.d2l();
                  case K_F -> c.d2f();
                  case K_B -> {
                     c.d2i();
                     c.i2b();
                  }
                  case K_C -> {
                     c.d2i();
                     c.i2c();
                  }
                  default -> {
                  }
               }
            }
         }
      }
      c.invokevirtual(CD_MH, "invokeExact", callType);
      if (UI) {
         uiLog(c, d, d + 1, rt, kinds, np, hasSelf ? 3 : -1, 4);
      }
      if (rt == void.class) {
         if (UI) {
            uiExit(c, d);
         }
         c.iconst_0();
         c.ireturn();
      } else {
         if (rt == double.class) {
            c.invokestatic(CD_D, "valueOf", MethodTypeDesc.of(CD_D, ConstantDescs.CD_double));
         } else if (rt == boolean.class) {
            c.invokestatic(CD_Z, "valueOf", MethodTypeDesc.of(CD_Z, ConstantDescs.CD_boolean));
         } else if (rt == long.class) {
            c.l2d();
            c.invokestatic(LuaBox.ALLOC ? CD_LUABOX : CD_BOXED, LuaBox.ALLOC ? "box" : "toDouble", MT_TODOUBLE); // luaJitBoxAlloc
         } else if (rt == float.class) {
            c.f2d();
            c.invokestatic(LuaBox.ALLOC ? CD_LUABOX : CD_BOXED, LuaBox.ALLOC ? "box" : "toDouble", MT_TODOUBLE); // luaJitBoxAlloc
         } else if (rt.isPrimitive()) { // int, short, byte, char
            c.i2d();
            c.invokestatic(LuaBox.ALLOC ? CD_LUABOX : CD_BOXED, LuaBox.ALLOC ? "box" : "toDouble", MT_TODOUBLE); // luaJitBoxAlloc
         } else {
            c.astore(res);
            if (CONVERT_CACHE) { // luaJavaConvertCache
               c.aload(res);
               c.ldc(cd(2, CD_MGR));
               c.aload(0);
               c.invokestatic(CD_SELF, "toLua", MT_TOLUA);
            } else {
               c.ldc(cd(2, CD_MGR));
               c.aload(res);
               c.invokevirtual(CD_MGR, "fromJavaToLua", MethodTypeDesc.of(CD_OBJ, CD_OBJ));
            }
         }
         c.astore(res);
         c.aload(1);
         c.aload(res);
         c.invokevirtual(CD_LCF, "push", MT_PUSH);
         if (UI) {
            uiExit(c, d);
         }
         c.ireturn();
      }
      c.labelBinding(end);
      c.labelBinding(handler);
      c.astore(res);
      if (UI) {
         c.iload(d);
         c.aload(0);
         c.invokestatic(CD_SELF, "uiThrew", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int, CD_SELF));
      }
      c.aload(res);
      c.invokestatic(CD_MC, "pzoptLogException", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Throwable));
      if (UI) {
         uiExit(c, d);
      }
      c.iconst_0();
      c.ireturn();
      c.exceptionCatchAll(start, end, handler);
      c.labelBinding(fail);
      c.iconst_m1();
      c.ireturn();
   }
   static final ClassDesc CD_UIDEPS = ClassDesc.of("pzopt.UiDeps");

   static void uiExit(CodeBuilder c, int d) {
      c.iload(d);
      c.invokestatic(CD_UIDEPS, "exit", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int));
   }

   /**
    * luaJavaFastUiDeps, right after invokeExact (the raw result of type rt on the stack, left there): when d >= 0,
    * uiCall(d, this, self, the arguments as MethodCaller's converted Object[], the result boxed as Method.invoke boxes it).
    * raw: the first free local (2 slots); selfSlot -1 = no self; arg0: the local of the first Lua argument.
    */
   static void uiLog(CodeBuilder c, int d, int raw, Class<?> rt, int[] kinds, int np, int selfSlot, int arg0) {
      int[] slots = new int[np];
      for (int i = 0; i < np; i++) {
         slots[i] = arg0 + i;
      }
      uiLog(c, d, raw, rt, kinds, np, selfSlot, slots, 0);
   }

   /** uiLog with the local of every argument (slots) and the arguments held as doubles (bit i of dbl: luaJitTypedArgs). */
   static void uiLog(CodeBuilder c, int d, int raw, Class<?> rt, int[] kinds, int np, int selfSlot, int[] slots, int dbl) {
      TypeKind tk = rt == void.class ? null : TypeKind.from(rt.isPrimitive() ? ClassDesc.ofDescriptor(rt.descriptorString()) : CD_OBJ);
      if (tk != null) {
         c.storeLocal(tk, raw);
      }
      Label skip = c.newLabel();
      c.iload(d);
      c.iflt(skip);
      c.iload(d);
      c.aload(0);
      if (selfSlot >= 0) {
         c.aload(selfSlot);
      } else {
         c.aconst_null();
      }
      c.loadConstant(np);
      c.anewarray(CD_OBJ);
      for (int i = 0; i < np; i++) {
         c.dup();
         c.loadConstant(i);
         boolean raw2 = (dbl >> i & 1) != 0;
         if (raw2) {
            c.dload(slots[i]); // luaJitTypedArgs: the number as stock's boxed argument holds it (the site's adapter)
         } else {
            c.aload(slots[i]);
         }
         switch (raw2 && kinds[i] == K_D ? -1 : kinds[i]) {
            case -1 -> c.invokestatic(CD_D, "valueOf", MethodTypeDesc.of(CD_D, ConstantDescs.CD_double));
            case K_OBJ, K_REF, K_Z, K_D -> {
            }
            default -> {
               if (!raw2) {
                  c.checkcast(CD_D);
                  c.invokevirtual(CD_D, "doubleValue", MethodTypeDesc.of(ConstantDescs.CD_double));
               }
               switch (kinds[i]) {
                  case K_I -> {
                     c.d2i();
                     c.invokestatic(ConstantDescs.CD_Integer, "valueOf", MethodTypeDesc.of(ConstantDescs.CD_Integer, ConstantDescs.CD_int));
                  }
                  case K_J -> {
                     c.d2l();
                     c.invokestatic(ConstantDescs.CD_Long, "valueOf", MethodTypeDesc.of(ConstantDescs.CD_Long, ConstantDescs.CD_long));
                  }
                  case K_F -> {
                     c.d2f();
                     c.invokestatic(ConstantDescs.CD_Float, "valueOf", MethodTypeDesc.of(ConstantDescs.CD_Float, ConstantDescs.CD_float));
                  }
                  case K_B -> {
                     c.d2i();
                     c.i2b();
                     c.invokestatic(ConstantDescs.CD_Byte, "valueOf", MethodTypeDesc.of(ConstantDescs.CD_Byte, ConstantDescs.CD_byte));
                  }
                  default -> { // K_C
                     c.d2i();
                     c.i2c();
                     c.invokestatic(ConstantDescs.CD_Character, "valueOf", MethodTypeDesc.of(ConstantDescs.CD_Character, ConstantDescs.CD_char));
                  }
               }
            }
         }
         c.aastore();
      }
      if (tk == null) {
         c.aconst_null();
      } else {
         c.loadLocal(tk, raw);
         if (rt.isPrimitive()) {
            ClassDesc box = ClassDesc.of(switch (tk) {
               case INT -> rt == char.class ? "java.lang.Character" : rt == byte.class ? "java.lang.Byte" : rt == short.class ? "java.lang.Short"
                     : rt == boolean.class ? "java.lang.Boolean" : "java.lang.Integer";
               case LONG -> "java.lang.Long";
               case FLOAT -> "java.lang.Float";
               case DOUBLE -> "java.lang.Double";
               case BOOLEAN -> "java.lang.Boolean";
               case BYTE -> "java.lang.Byte";
               case CHAR -> "java.lang.Character";
               case SHORT -> "java.lang.Short";
               default -> throw new IllegalStateException("result " + rt);
            });
            c.invokestatic(box, "valueOf", MethodTypeDesc.of(box, ClassDesc.ofDescriptor(rt.descriptorString())));
         }
      }
      c.invokestatic(CD_SELF, "uiCall", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int, CD_SELF, CD_OBJ, CD_OBJ.arrayType(), CD_OBJ));
      c.labelBinding(skip);
      if (tk != null) {
         c.loadLocal(tk, raw);
      }
   }

   static final ClassDesc CD_KT = ClassDesc.of("se.krka.kahlua.vm.KahluaThread");
   static final ClassDesc CD_CO = ClassDesc.of("se.krka.kahlua.vm.Coroutine");
   static final ClassDesc CD_SITES = ClassDesc.of("pzopt.LuaJitSites");

   /**
    * luaJitDirectArgs: dcall(t, f, fn, a, restoreTop, x1 .. xn), the arguments as JVM values (x1 = self when the method
    * has one). The same checks and conversions as {@link #body}; a failed check writes the arguments to the Lua stack
    * and makes the stock call (LuaJitSites.gen{n}). locals: 0 this, 1 t, 2 f, 3 fn, 4 a, 5 restoreTop, 6.. x, then
    * frames and the result.
    */
   static void dbody(CodeBuilder c, MethodTypeDesc callType, int[] kinds, boolean hasSelf, boolean passSelf, int np, Class<?> rt, int dmask) {
      Label fail = c.newLabel();
      int off = hasSelf ? 1 : 0;
      int n = np + off;
      int x = 6;
      int[] sl = new int[n]; // the local of x{i+1}: a double (dmask, luaJitTypedArgs) takes two
      int next = 6;
      for (int i = 0; i < n; i++) {
         sl[i] = next;
         next += (dmask >> i & 1) != 0 ? 2 : 1;
      }
      int[] psl = new int[np];
      for (int i = 0; i < np; i++) {
         psl[i] = sl[off + i];
      }
      int pdbl = dmask >>> off;
      int frames = next, res = next + 1;
      if (LuaJitDual.ON) {
         c.aload(0);
         c.aload(1);
         c.invokestatic(CD_SELF, "dualGateT", MethodTypeDesc.of(ConstantDescs.CD_boolean, CD_SELF, CD_KT));
         c.ifne(fail);
      }
      c.aload(1);
      c.aload(2);
      c.iload(4);
      c.invokestatic(CD_SELF, "directOk", MethodTypeDesc.of(ConstantDescs.CD_boolean, CD_KT, CD_LCF, ConstantDescs.CD_int));
      c.ifeq(fail);
      c.aload(2);
      c.getfield(CD_LCF, "coroutine", CD_CO);
      c.invokevirtual(CD_CO, "getCallframeTop", MethodTypeDesc.of(ConstantDescs.CD_int));
      c.istore(frames);
      if (hasSelf) {
         c.ldc(cd(1, CD_CLASS));
         c.aload(x);
         c.invokevirtual(CD_CLASS, "isInstance", MT_ISINST);
         c.ifeq(fail);
      }
      for (int i = 0; i < np; i++) {
         int slot = psl[i];
         if ((pdbl >> i & 1) != 0) {
            continue; // a number already (luaJitTypedArgs)
         }
         switch (kinds[i]) {
            case K_OBJ -> {
            }
            case K_REF -> {
               Label ok = c.newLabel();
               c.aload(slot);
               c.ifnull(ok);
               c.ldc(cd(3 + i, CD_CLASS));
               c.aload(slot);
               c.invokevirtual(CD_CLASS, "isInstance", MT_ISINST);
               c.ifeq(fail);
               c.labelBinding(ok);
            }
            case K_Z -> {
               c.aload(slot);
               c.instanceOf(CD_Z);
               c.ifeq(fail);
            }
            default -> {
               c.aload(slot);
               c.instanceOf(CD_D);
               c.ifeq(fail);
            }
         }
      }
      int d = res + 1;
      Label start = c.newLabel(), end = c.newLabel(), handler = c.newLabel();
      if (UI) {
         c.invokestatic(CD_SELF, "uiEnter", MethodTypeDesc.of(ConstantDescs.CD_int));
         c.istore(d);
      }
      c.labelBinding(start);
      c.ldc(cd(0, CD_MH));
      if (passSelf) {
         c.aload(x);
      }
      for (int i = 0; i < np; i++) {
         boolean dbl = (pdbl >> i & 1) != 0;
         if (dbl) {
            c.dload(psl[i]); // the site's adapter made it the number stock's boxed argument holds (LuaJitSites.typedArgs)
         } else {
            c.aload(psl[i]);
         }
         switch (kinds[i]) {
            case K_OBJ, K_REF -> {
            }
            case K_Z -> {
               c.checkcast(CD_Z);
               c.invokevirtual(CD_Z, "booleanValue", MethodTypeDesc.of(ConstantDescs.CD_boolean));
            }
            default -> {
               if (!dbl) {
                  c.checkcast(CD_D);
                  c.invokevirtual(CD_D, "doubleValue", MethodTypeDesc.of(ConstantDescs.CD_double));
               }
               switch (kinds[i]) {
                  case K_I -> c.d2i();
                  case K_J -> c.d2l();
                  case K_F -> c.d2f();
                  case K_B -> {
                     c.d2i();
                     c.i2b();
                  }
                  case K_C -> {
                     c.d2i();
                     c.i2c();
                  }
                  default -> {
                  }
               }
            }
         }
      }
      c.invokevirtual(CD_MH, "invokeExact", callType);
      if (UI) {
         uiLog(c, d, d + 1, rt, kinds, np, hasSelf ? x : -1, psl, pdbl);
      }
      if (rt == void.class) {
         c.aconst_null();
      } else if (rt == double.class) {
         c.invokestatic(CD_D, "valueOf", MethodTypeDesc.of(CD_D, ConstantDescs.CD_double));
      } else if (rt == boolean.class) {
         c.invokestatic(CD_Z, "valueOf", MethodTypeDesc.of(CD_Z, ConstantDescs.CD_boolean));
      } else if (rt == long.class) {
         c.l2d();
         c.invokestatic(LuaBox.ALLOC ? CD_LUABOX : CD_BOXED, LuaBox.ALLOC ? "box" : "toDouble", MT_TODOUBLE); // luaJitBoxAlloc
      } else if (rt == float.class) {
         c.f2d();
         c.invokestatic(LuaBox.ALLOC ? CD_LUABOX : CD_BOXED, LuaBox.ALLOC ? "box" : "toDouble", MT_TODOUBLE); // luaJitBoxAlloc
      } else if (rt.isPrimitive()) { // int, short, byte, char
         c.i2d();
         c.invokestatic(LuaBox.ALLOC ? CD_LUABOX : CD_BOXED, LuaBox.ALLOC ? "box" : "toDouble", MT_TODOUBLE); // luaJitBoxAlloc
      } else {
         c.astore(res);
         if (CONVERT_CACHE) { // luaJavaConvertCache
            c.aload(res);
            c.ldc(cd(2, CD_MGR));
            c.aload(0);
            c.invokestatic(CD_SELF, "toLua", MT_TOLUA);
         } else {
            c.ldc(cd(2, CD_MGR));
            c.aload(res);
            c.invokevirtual(CD_MGR, "fromJavaToLua", MethodTypeDesc.of(CD_OBJ, CD_OBJ));
         }
      }
      c.astore(res);
      if (UI) {
         uiExit(c, d);
      }
      c.labelBinding(end);
      c.aload(1);
      c.aload(2);
      c.iload(4);
      c.iload(frames);
      c.aload(res);
      c.loadConstant(rt == void.class ? 0 : 1);
      c.invokestatic(CD_SELF, "directPost", MethodTypeDesc.of(CD_OBJ, CD_KT, CD_LCF, ConstantDescs.CD_int, ConstantDescs.CD_int, CD_OBJ,
            ConstantDescs.CD_boolean));
      c.areturn();
      c.labelBinding(handler);
      c.astore(res);
      if (UI) {
         c.iload(d);
         c.aload(0);
         c.invokestatic(CD_SELF, "uiThrew", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int, CD_SELF));
      }
      c.aload(1);
      c.aload(2);
      c.aload(3);
      c.iload(4);
      c.loadConstant(n);
      c.aload(res);
      c.invokestatic(CD_SELF, "directThrow", MethodTypeDesc.of(CD_OBJ, CD_KT, CD_LCF, CD_OBJ, ConstantDescs.CD_int, ConstantDescs.CD_int,
            ConstantDescs.CD_Throwable));
      if (UI) {
         uiExit(c, d);
      }
      c.areturn();
      c.exceptionCatchAll(start, end, handler);
      c.labelBinding(fail);
      c.aload(1);
      c.aload(2);
      c.aload(3);
      c.iload(4);
      c.iload(5);
      for (int i = 0; i < n; i++) {
         if ((dmask >> i & 1) != 0) {
            c.dload(sl[i]);
            c.invokestatic(CD_D, "valueOf", MT_TODOUBLE); // the very value (a -0.0 constant stays -0.0; a raw number came normalized)
         } else {
            c.aload(sl[i]);
         }
      }
      c.invokestatic(CD_SITES, "gen" + n, MethodTypeDesc.ofDescriptor(LuaJitSites.typeArgs(n).toMethodDescriptorString()));
      c.areturn();
   }
}
