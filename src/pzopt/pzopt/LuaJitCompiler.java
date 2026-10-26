package pzopt;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import se.krka.kahlua.vm.Prototype;

/**
 * luaJit: compiles one Kahlua Prototype (Lua 5.1 bytecode) into a hidden class extending {@link LuaJitCode}.
 *
 * Every instruction becomes the interpreter's own operation on the same objects: the same helper methods
 * (KahluaThread.tableget / tableSet / call, KahluaUtil.fromDouble / toDouble, the pzoptJit* copies of luaMainloop's
 * slow paths), so values, metamethods, error messages (helpful NullPointerException texts included, since every
 * unboxing that can fail goes through KahluaUtil.fromDouble as in the interpreter) and the Lua stack seen by callees
 * are the interpreter's. What changes is the dispatch: no opcode fetch, decode or switch, constants and operand kinds
 * resolved at compile time, numbers added without the generic path, jumps as JVM jumps.
 *
 * Registers: with {@link Config#LUA_JIT_LOCALS} every register that no closure captures lives in a JVM local (the
 * interpreter writes every register into the coroutine's long-lived object stack, a GC write barrier on each store).
 * The Lua stack gets those values only where something reads them from there: the arguments of a call (spilled before
 * it), CONCAT / TFORLOOP / B=0 operand windows, and the live registers of a frame handed to the interpreter (deopt).
 * Results of calls are read back from the stack (filled). A liveness analysis picks what to spill and fill, so the
 * stack holds every value the interpreter or a callee can read, exactly as it would have.
 */
final class LuaJitCompiler {
   static final int MOVE = 0, LOADK = 1, LOADBOOL = 2, LOADNIL = 3, GETUPVAL = 4, GETGLOBAL = 5, GETTABLE = 6,
         SETGLOBAL = 7, SETUPVAL = 8, SETTABLE = 9, NEWTABLE = 10, SELF = 11, ADD = 12, SUB = 13, MUL = 14, DIV = 15,
         MOD = 16, POW = 17, UNM = 18, NOT = 19, LEN = 20, CONCAT = 21, JMP = 22, EQ = 23, LT = 24, LE = 25, TEST = 26,
         TESTSET = 27, CALL = 28, TAILCALL = 29, RETURN = 30, FORLOOP = 31, FORPREP = 32, TFORLOOP = 33, SETLIST = 34,
         CLOSE = 35, CLOSURE = 36, VARARG = 37;

   static final int FIELDS_PER_FLUSH = 50;
   /** HotSpot does not JIT-compile methods with more bytecode than this (DontCompileHugeMethods). */
   static final int HUGE = 8000;

   static final ClassDesc CD_KT = ClassDesc.of("se.krka.kahlua.vm.KahluaThread");
   static final ClassDesc CD_LCF = ClassDesc.of("se.krka.kahlua.vm.LuaCallFrame");
   static final ClassDesc CD_CO = ClassDesc.of("se.krka.kahlua.vm.Coroutine");
   static final ClassDesc CD_LC = ClassDesc.of("se.krka.kahlua.vm.LuaClosure");
   static final ClassDesc CD_PROTO = ClassDesc.of("se.krka.kahlua.vm.Prototype");
   static final ClassDesc CD_UV = ClassDesc.of("se.krka.kahlua.vm.UpValue");
   static final ClassDesc CD_TABLE = ClassDesc.of("se.krka.kahlua.vm.KahluaTable");
   static final ClassDesc CD_JF = ClassDesc.of("se.krka.kahlua.vm.JavaFunction");
   static final ClassDesc CD_KU = ClassDesc.of("se.krka.kahlua.vm.KahluaUtil");
   static final ClassDesc CD_LUABOX = ClassDesc.of("pzopt.LuaBox");
   static final ClassDesc CD_PLATFORM = ClassDesc.of("se.krka.kahlua.vm.Platform");
   static final ClassDesc CD_CODE = ClassDesc.of("pzopt.LuaJitCode");
   static final ClassDesc CD_LUAJIT = ClassDesc.of("pzopt.LuaJit");
   static final ClassDesc CD_DARR = ConstantDescs.CD_double.arrayType();
   static final ClassDesc CD_IC = ClassDesc.of("pzopt.LuaJitIc");
   static final boolean IC = Config.LUA_JIT_IC;
   static final boolean DIRECT = Config.LUA_JIT_DIRECT_CALLS;
   static final boolean JAVA_FAST = LuaJavaFast.ON;
   static final boolean INDY = Config.LUA_JIT_INDY_CALLS;
   static final boolean OSR = Config.LUA_JIT_OSR;
   static final boolean SPLIT = Config.LUA_JIT_SPLIT;
   /**
    * luaJitSplitRaw: a jump between two regions of a split function hands the numbers kept unboxed at its target over in
    * KahluaThread.pzoptJitD instead of boxing them onto the Lua stack; f.pc carries RAW_PC then.
    */
   static final boolean SPLIT_RAW = Config.LUA_JIT_SPLIT_RAW;
   static final int RAW_PC = 1 << 24;
   /**
    * luaJitMixedNum: an unboxed number is not boxed for an arithmetic or a comparison whose other operand is an object (only
    * the object is checked and unboxed; the slow path boxes it for the metamethod), nor for a TEST / TESTSET (a number is
    * true).
    */
   static final boolean MIXED = Config.LUA_JIT_MIXED_NUM;
   static final boolean SPLIT_DP = Config.LUA_JIT_SPLIT_DP;
   static final boolean ENTRY_COMPACT = Config.LUA_JIT_ENTRY_COMPACT;
   static final boolean SPEC = Config.LUA_JIT_SPEC_ARITH;
   /**
    * luaJitWholeBelow: functions up to this many bytes of JVM code compile as one method (not split, not refused), for a
    * JVM run with -XX:-DontCompileHugeMethods, which JIT-compiles them (-1: that, read from the running JVM).
    */
   static final int WHOLE = wholeBelow();
   static final int LIMIT = Math.max(HUGE, WHOLE);

   static int wholeBelow() {
      int k = Config.LUA_JIT_WHOLE_BELOW;
      if (k >= 0) {
         return k;
      }
      try {
         com.sun.management.HotSpotDiagnosticMXBean hs = java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
         return "false".equals(hs.getVMOption("DontCompileHugeMethods").getValue()) ? 32000 : 0;
      } catch (Throwable e) {
         return 0;
      }
   }
   static final int SPLIT_ABOVE = Config.LUA_JIT_SPLIT_ABOVE;
   static final java.util.concurrent.atomic.AtomicLong wholeHuge = new java.util.concurrent.atomic.AtomicLong();
   static final java.util.concurrent.atomic.AtomicLong splitFunctions = new java.util.concurrent.atomic.AtomicLong(),
         splitRegions = new java.util.concurrent.atomic.AtomicLong();
   static final ClassDesc CD_SELF = ClassDesc.of("se.krka.kahlua.vm.PzoptLuaJit");
   /** Tests: the region starts of every split function (null: chosen by buildSplit). */
   static int[] testCuts;
   static final java.lang.constant.DirectMethodHandleDesc BSM_SITE = java.lang.constant.MethodHandleDesc.ofMethod(
         java.lang.constant.DirectMethodHandleDesc.Kind.STATIC, ClassDesc.of("pzopt.LuaJitSites"), "bootstrap",
         MethodTypeDesc.of(ConstantDescs.CD_CallSite, ConstantDescs.CD_MethodHandles_Lookup, ConstantDescs.CD_String, ConstantDescs.CD_MethodType));
   static final java.lang.constant.DynamicCallSiteDesc SITE_CALL = java.lang.constant.DynamicCallSiteDesc.of(BSM_SITE, "call",
         MethodTypeDesc.of(ConstantDescs.CD_int, ClassDesc.of("se.krka.kahlua.vm.KahluaThread"), ClassDesc.of("se.krka.kahlua.vm.LuaCallFrame"),
               ConstantDescs.CD_Object, ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_boolean));
   static final boolean DIRECT_ARGS = Config.LUA_JIT_DIRECT_ARGS && INDY;
   /** luaJitFrameLite: a RETURN of a function without CLOSURE skips closeUpvalues (nothing can be open on its frame). */
   static final boolean FRAME_LITE = Config.LUA_JIT_FRAME_LITE;
   /** luaJitTypedArgs: a direct-arguments call passes its unboxed number arguments as doubles. */
   static final boolean TYPED_ARGS = Config.LUA_JIT_TYPED_ARGS && DIRECT_ARGS;
   static final java.util.concurrent.ConcurrentHashMap<Integer, java.lang.constant.DynamicCallSiteDesc> SITE_ARGS_TYPED = new java.util.concurrent.ConcurrentHashMap<>();

   static java.lang.constant.DynamicCallSiteDesc siteArgs(int k, int dmask, int nmask) {
      if (dmask == 0 && nmask == 0) {
         return SITE_ARGS[k];
      }
      return SITE_ARGS_TYPED.computeIfAbsent(k << 20 | dmask << 10 | nmask, x -> java.lang.constant.DynamicCallSiteDesc.of(BSM_ARGS, "callArgs",
            MethodTypeDesc.ofDescriptor(LuaJitSites.typeArgs(k, dmask, nmask).toMethodDescriptorString())));
   }

   /** CALL pc goes through {@link Gen#callArgs} (fixed arguments and results). */
   static boolean argsCall(int b, int c) {
      return DIRECT_ARGS && b != 0 && c != 0 && b - 1 <= LuaJitSites.MAX_ARGS;
   }
   static final ClassDesc CD_SITES = ClassDesc.of("pzopt.LuaJitSites");
   static final java.lang.constant.DirectMethodHandleDesc BSM_ARGS = java.lang.constant.MethodHandleDesc.ofMethod(
         java.lang.constant.DirectMethodHandleDesc.Kind.STATIC, CD_SITES, "bootstrapArgs",
         MethodTypeDesc.of(ConstantDescs.CD_CallSite, ConstantDescs.CD_MethodHandles_Lookup, ConstantDescs.CD_String, ConstantDescs.CD_MethodType));
   static final java.lang.constant.DynamicCallSiteDesc[] SITE_ARGS = new java.lang.constant.DynamicCallSiteDesc[LuaJitSites.MAX_ARGS + 1];

   static {
      for (int k = 0; k <= LuaJitSites.MAX_ARGS; k++) {
         SITE_ARGS[k] = java.lang.constant.DynamicCallSiteDesc.of(BSM_ARGS, "callArgs",
               MethodTypeDesc.ofDescriptor(LuaJitSites.typeArgs(k).toMethodDescriptorString()));
      }
   }

   static final ClassDesc CD_LJI = ClassDesc.of("se.krka.kahlua.integration.expose.LuaJavaInvoker");
   static final ClassDesc CD_LJF = ClassDesc.of("pzopt.LuaJavaFast");
   static final ClassDesc CD_OBJ = ConstantDescs.CD_Object;
   static final ClassDesc CD_OBJS = CD_OBJ.arrayType();
   static final ClassDesc CD_UVS = CD_UV.arrayType();
   static final ClassDesc CD_D = ConstantDescs.CD_Double;
   static final ClassDesc CD_B = ConstantDescs.CD_Boolean;
   static final ClassDesc CD_S = ConstantDescs.CD_String;
   static final ClassDesc CD_int = ConstantDescs.CD_int;
   static final ClassDesc CD_bool = ConstantDescs.CD_boolean;
   static final ClassDesc CD_dbl = ConstantDescs.CD_double;
   static final ClassDesc CD_void = ConstantDescs.CD_void;

   static final MethodTypeDesc MT_RUN = MethodTypeDesc.of(CD_int, CD_KT, CD_LCF);
   static final MethodTypeDesc MT_OO_O = MethodTypeDesc.of(CD_OBJ, CD_OBJ, CD_OBJ);
   static final MethodTypeDesc MT_OOO_V = MethodTypeDesc.of(CD_void, CD_OBJ, CD_OBJ, CD_OBJ);
   static final MethodTypeDesc MT_D_DBL = MethodTypeDesc.of(CD_D, CD_dbl);
   static final MethodTypeDesc MT_J_DBL = MethodTypeDesc.of(CD_D, ConstantDescs.CD_long);
   static final MethodTypeDesc MT_O_D = MethodTypeDesc.of(CD_dbl, CD_OBJ);
   static final MethodTypeDesc MT_D = MethodTypeDesc.of(CD_dbl);
   static final MethodTypeDesc MT_I = MethodTypeDesc.of(CD_int);
   static final MethodTypeDesc MT_I_V = MethodTypeDesc.of(CD_void, CD_int);
   static final MethodTypeDesc MT_V = MethodTypeDesc.of(CD_void);
   static final MethodTypeDesc MT_O = MethodTypeDesc.of(CD_OBJ);
   static final MethodTypeDesc MT_O_V = MethodTypeDesc.of(CD_void, CD_OBJ);

   private static final ClassHierarchyResolver RESOLVER = ClassHierarchyResolver
         .ofClassLoading(LuaJitCompiler.class.getClassLoader()).cached();

   private LuaJitCompiler() {
   }

   /**
    * devLuaJitClassNames: the compiled class is named after its function (file, name, first line), so a profile tells the
    * compiled functions apart (otherwise all are PzoptLuaJit/0x...).
    */
   static ClassDesc selfDesc(Prototype p) {
      if (!Config.DEV_LUA_JIT_CLASS_NAMES) {
         return CD_SELF;
      }
      String f = p.filename != null ? p.filename : String.valueOf(p.file);
      int slash = Math.max(f.lastIndexOf('/'), f.lastIndexOf('\\'));
      String mod = "";
      int mi = f.indexOf("/mods/");
      if (mi >= 0) {
         int e = f.indexOf('/', mi + 6);
         mod = (e > 0 ? f.substring(mi + 6, e) : "") + "__";
      }
      int line = p.lines != null && p.lines.length > 0 ? p.lines[0] : 0;
      String n = mod + f.substring(slash + 1).replace(".lua", "") + "__" + (p.name != null ? p.name : "anon") + "_" + line;
      return ClassDesc.of("se.krka.kahlua.vm.PzoptLuaJit_" + n.replaceAll("[^A-Za-z0-9_]", "_"));
   }

   static String describe(Prototype p) {
      int line = p.lines != null && p.lines.length > 0 ? p.lines[0] : 0;
      return (p.name != null ? p.name : "?") + " (" + (p.filename != null ? p.filename : p.file) + ":" + line + ")";
   }

   /** The code of one function, or null when it cannot be compiled (unsupported shape, too big). */
   static LuaJitCode compile(Prototype p, MethodHandles.Lookup lookup) throws Throwable {
      Gen g = new Gen(p, Config.LUA_JIT_LOCALS);
      if (!g.analyse()) {
         p.pzoptJitWhy = g.reason;
         if (Config.DEV_LUA_JIT_LOG) {
            Log.info("luaJit: not compiled " + describe(p) + ": " + g.reason);
         }
         return null;
      }
      byte[] bytes;
      int len;
      try {
         bytes = g.build();
         len = codeLength(bytes);
      } catch (IllegalArgumentException e) {
         // past the class file's 64 KB method limit (or a branch offset it cannot encode): only the split form can fit
         if (!SPLIT || Config.LUA_JIT_HUGE) {
            throw e;
         }
         java.util.regex.Matcher m = java.util.regex.Pattern.compile("Code length (\\d+)").matcher(String.valueOf(e.getMessage()));
         int est = m.find() ? Integer.parseInt(m.group(1)) : 2 * 65536;
         g = new Gen(p, Config.LUA_JIT_LOCALS);
         g.analyse();
         byte[] s = g.buildSplit(est);
         if (s == null) {
            p.pzoptJitWhy = "over 64 KB of JVM code, " + g.splitWhy;
            return null;
         }
         int sl = 0;
         for (int l : methodLengths(s)) {
            sl += l;
         }
         splitFunctions.incrementAndGet();
         splitRegions.addAndGet(g.cuts.length);
         return define(p, g, s, sl, lookup);
      }
      if (len > LIMIT && g.direct && !INDY) {
         // the direct call sites are the bigger form of CALL: without them the function may still fit HotSpot's limit
         g = new Gen(p, Config.LUA_JIT_LOCALS);
         g.direct = false;
         g.analyse();
         bytes = g.build();
         len = codeLength(bytes);
      }
      boolean split = false;
      if (len > Math.max(SPLIT_ABOVE, WHOLE) && SPLIT && !Config.LUA_JIT_HUGE) {
         byte[] s = g.buildSplit(len);
         if (s != null) {
            bytes = s;
            len = 0;
            for (int l : methodLengths(s)) {
               len += l;
            }
            split = true;
            splitFunctions.incrementAndGet();
            splitRegions.addAndGet(g.cuts.length);
         }
      }
      if (!split && len > HUGE && len <= LIMIT) {
         wholeHuge.incrementAndGet(); // luaJitWholeBelow
      }
      if (len > LIMIT && !split && !Config.LUA_JIT_HUGE) {
         p.pzoptJitWhy = len + " bytes" + (g.splitWhy != null ? ", " + g.splitWhy : "");
         if (Config.DEV_LUA_JIT_LOG) {
            Log.info("luaJit: not compiled " + describe(p) + ": " + len + " bytes of JVM code");
         }
         return null;
      }
      return define(p, g, bytes, len, lookup);
   }

   static LuaJitCode define(Prototype p, Gen g, byte[] bytes, int len, MethodHandles.Lookup lookup) throws Throwable {
      MethodHandles.Lookup hl = lookup.defineHiddenClassWithClassData(bytes, Collections.unmodifiableList(g.data), true);
      MethodHandle ctor = hl.findConstructor(hl.lookupClass(), MethodType.methodType(void.class));
      LuaJitCode code = (LuaJitCode)ctor.invoke();
      code.name = describe(p);
      LuaJit.codeBytes.addAndGet(len);
      return code;
   }

   /** For tests: the class file of one function (null when not compilable), and the reason. */
   static byte[] classFile(Prototype p, boolean locals, String[] reason) {
      Gen g = new Gen(p, locals);
      if (!g.analyse()) {
         reason[0] = g.reason;
         return null;
      }
      return g.build();
   }

   /** The code lengths of a split function's region methods (r0, r1, ...), in region order. */
   static int[] methodLengths(byte[] bytes) {
      ClassModel cm = ClassFile.of().parse(bytes);
      java.util.TreeMap<Integer, Integer> m = new java.util.TreeMap<>();
      for (MethodModel mm : cm.methods()) {
         String name = mm.methodName().stringValue();
         if (name.length() > 1 && name.charAt(0) == 'r' && Character.isDigit(name.charAt(1))) {
            m.put(Integer.parseInt(name.substring(1)), mm.findAttribute(Attributes.code()).map(CodeAttribute::codeLength).orElse(0));
         }
      }
      int[] r = new int[m.size()];
      int i = 0;
      for (int v : m.values()) {
         r[i++] = v;
      }
      return r;
   }

   static int codeLength(byte[] bytes) {
      ClassModel cm = ClassFile.of().parse(bytes);
      for (MethodModel m : cm.methods()) {
         if (m.methodName().equalsString("run")) {
            return m.findAttribute(Attributes.code()).map(CodeAttribute::codeLength).orElse(0);
         }
      }
      return 0;
   }

   static int opA(int op) {
      return op >>> 6 & 0xFF;
   }

   static int opC(int op) {
      return op >>> 14 & 511;
   }

   static int opB(int op) {
      return op >>> 23 & 511;
   }

   static int opBx(int op) {
      return op >>> 14;
   }

   static int opSBx(int op) {
      return (op >>> 14) - 131071;
   }

   /** One compilation. */
   static final class Gen {
      final Prototype p;
      final ClassDesc self;
      final int[] code;
      final int n;
      final int max;
      final boolean locals;
      String reason;
      /** false for the words after CLOSURE (upvalue pseudo-instructions) and SETLIST's C=0 data word. */
      boolean[] insn;
      boolean[] target;
      boolean[] captured;
      /** For a B=0 consumer: the first register of the multiple values on the stack (the producer's A), else -1. */
      int[] mstart;
      BitSet[] liveIn;
      BitSet[] liveOut;
      BitSet[] useSet;
      BitSet[] defSet;

      final List<Object> data = new ArrayList<>();
      final IdentityHashMap<Object, Integer> dataIndex = new IdentityHashMap<>();

      // JVM local slots
      static final int THIS = 0, T = 1, F = 2, CO = 3, BASE = 4, CL = 5;
      int regBase = 6;
      int tmpO0, tmpO1, tmpO2, tmpStk, tmpI0, tmpI1, tmpI2, tmpD0, tmpD1, tmpD2;
      /** luaJitNumbers: per local register, OBJ (its object slot holds it), RAW (only its double slot: a number not boxed
       * yet) or NUM (both); the state at each instruction's entry (null: unreachable) and while one is generated. */
      static final byte S_OBJ = 0, S_RAW = 1, S_NUM = 2;
      boolean nums;
      byte[][] inS;
      byte[] cur;
      int dBase;
      int curPc;
      final List<Object[]> stubs = new ArrayList<>();
      /** Shared exits of the calls handed to the interpreter, one per set of registers to write back (key: live set + raw set). */
      final java.util.LinkedHashMap<String, Object[]> deoptExits = new java.util.LinkedHashMap<>();

      CodeBuilder cb;
      Label[] labels;
      int lastPcLine = Integer.MIN_VALUE;

      /** luaJitDirectCalls for this build (off for a second build when the first was past HUGE). */
      boolean direct = DIRECT;

      /** The instructions being generated: [lo, hi) (the whole function, or one region of a split one). */
      int lo, hi;
      /** luaJitSplit: the first pc of every region (cuts[0] = 0), null when the function is one method. */
      int[] cuts;
      /** luaJitSplitDp: the build that measures each instruction's bytes (line numbers = pc + 1). */
      boolean sizeLines;
      /** luaJitSplitDp: each instruction's bytes in the whole function's build, and the region budget of the last cuts. */
      int[] perPc;
      int dpBudget;
      String splitWhy;
      /** luaJitSplit: the exits of the current region, one per target pc and set of registers to box. */
      final java.util.LinkedHashMap<String, Object[]> regionExits = new java.util.LinkedHashMap<>();

      Gen(Prototype p, boolean locals) {
         this.p = p;
         this.self = selfDesc(p);
         this.code = p.code;
         this.n = p.code.length;
         this.hi = this.n;
         this.max = Math.max(p.maxStacksize, 1);
         this.locals = locals;
         this.nums = locals && Config.LUA_JIT_NUMBERS;
         this.noSpec = !SPEC || LuaJit.NO_SPEC.containsKey(p);
      }

      /** luaJitSpecArith: off for this function (the key, or its speculations failed often: LuaJit.specFailed). */
      final boolean noSpec;
      /**
       * luaJitSpecArith: the exits of the current method's failed speculations, shared by the sites with the same result
       * register and unboxed registers: {label, a, raw registers, registers to write}.
       */
      final java.util.LinkedHashMap<String, Object[]> specExits = new java.util.LinkedHashMap<>();

      /**
       * luaJitSpecArith: an arithmetic instruction whose operands may be anything (one an object) keeps its result
       * unboxed: a number from the fast path stays in the double slot, the slow path's (metamethod, string coercion) result
       * is unboxed when it is a Double that is not -0.0, else the frame goes to the interpreter after the instruction.
       */
      boolean specArith(int pc, byte[] st) {
         if (this.noSpec || !this.nums || st == null) {
            return false;
         }
         int op = this.code[pc];
         int o = op & 63;
         if (o < ADD || o > POW) {
            return false;
         }
         int a = opA(op), b = opB(op), c = opC(op);
         if (!isLocal(a) || this.mstart[pc] >= 0 || staticNum(pc, st)) {
            return false;
         }
         return !(isConst(b) && !(constant(b) instanceof Double)) && !(isConst(c) && !(constant(c) instanceof Double));
      }

      boolean hasSpec() {
         if (this.noSpec || !this.nums) {
            return false;
         }
         for (int pc = 0; pc < this.n; pc++) {
            if (this.insn[pc] && specArith(pc, this.inS[pc])) {
               return true;
            }
         }
         return false;
      }

      /** luaJitSpecArith: run() hands the frame to the code that replaced this one (recompiled without speculation). */
      void redirect(CodeBuilder c) {
         if (!hasSpec()) {
            return;
         }
         Label own = c.newLabel();
         c.aload(0);
         c.getfield(CD_CODE, "next", CD_CODE);
         c.dup();
         c.ifnull(own);
         c.aload(T);
         c.aload(F);
         c.invokevirtual(CD_CODE, "run", MT_RUN);
         c.ireturn();
         c.labelBinding(own);
         c.pop();
      }

      Label specExit(int pc, int a) {
         BitSet raw = new BitSet();
         BitSet live = this.liveOut[pc];
         for (int r = live.nextSetBit(0); r >= 0; r = live.nextSetBit(r + 1)) {
            if (r != a && isLocal(r) && this.cur != null && this.cur[r] == S_RAW) {
               raw.set(r);
            }
         }
         int hi = Math.min(Math.max(live.length(), a + 1), this.max);
         Object[] e = this.specExits.computeIfAbsent(a + "/" + raw, k -> new Object[] {this.cb.newLabel(), a, raw, 0});
         e[3] = Math.max((Integer)e[3], hi);
         return (Label)e[0];
      }

      /**
       * The failed speculations: the unboxed registers boxed into their slots, the result into R(A)'s, then one chain
       * writes the local registers to the Lua stack (luaJitEntryCompact's); f.pc is the next instruction; ST_DEOPT.
       */
      void emitSpecExits() {
         if (this.specExits.isEmpty()) {
            return;
         }
         CodeBuilder cb = this.cb;
         int hi = 0;
         for (Object[] e : this.specExits.values()) {
            hi = Math.max(hi, (Integer)e[3]);
         }
         Label[] at = chain(hi);
         for (Object[] e : this.specExits.values()) {
            int a = (Integer)e[1];
            BitSet raw = (BitSet)e[2];
            cb.labelBinding((Label)e[0]);
            for (int r = raw.nextSetBit(0); r >= 0; r = raw.nextSetBit(r + 1)) {
               materialize(r);
            }
            cb.aload(this.tmpO2);
            cb.astore(slot(a));
            loadStack(); // f.pc is the next instruction already (the slow path's setPc)
            cb.astore(this.tmpStk);
            cb.goto_(at[(Integer)e[3]]);
         }
         emitChain(at);
         cb.aload(0);
         cb.invokestatic(CD_LUAJIT, "specFailed", MethodTypeDesc.of(ConstantDescs.CD_void, CD_CODE));
         cb.iconst_1(); // LuaJit.ST_DEOPT
         cb.ireturn();
      }

      boolean fail(String why) {
         this.reason = why;
         return false;
      }

      /**
       * luaJitFrameLite: the function makes no closure, so no upvalue ever points into its frame (only CLOSURE, run by
       * this frame, opens one on its registers; a callee's are closed when it returns or unwinds) and the stock RETURN's
       * closeUpvalues(localBase) has nothing to close.
       */
      boolean noClosure() {
         if (this.noClosure == 0) {
            this.noClosure = 1;
            for (int op : this.code) {
               if ((op & 63) == CLOSURE) {
                  this.noClosure = 2;
                  break;
               }
            }
         }
         return this.noClosure == 1;
      }

      int noClosure;

      boolean isLocal(int r) {
         return this.locals && r >= 0 && r < this.max && !this.captured[r];
      }

      int slot(int r) {
         return this.regBase + r;
      }

      // ---------------------------------------------------------------- analysis

      boolean analyse() {
         if (this.n == 0) {
            return fail("empty");
         }
         if (p.maxStacksize > 250) {
            return fail("maxStacksize " + p.maxStacksize);
         }
         this.insn = new boolean[this.n];
         this.target = new boolean[this.n + 2];
         this.captured = new boolean[this.max];
         this.mstart = new int[this.n + 1];
         Arrays.fill(this.mstart, -1);
         Arrays.fill(this.insn, true);
         for (int pc = 0; pc < this.n; pc++) {
            if (!this.insn[pc]) {
               continue;
            }
            int op = this.code[pc];
            int o = op & 63;
            if (o > VARARG) {
               return fail("opcode " + o);
            }
            if (o == CLOSURE) {
               int bx = opBx(op);
               if (p.prototypes == null || bx >= p.prototypes.length) {
                  return fail("closure index");
               }
               int nup = p.prototypes[bx].numUpvalues;
               for (int j = 0; j < nup; j++) {
                  int q = pc + 1 + j;
                  if (q >= this.n) {
                     return fail("closure upvalues past the end");
                  }
                  this.insn[q] = false;
                  int u = this.code[q];
                  if ((u & 63) == MOVE) {
                     int b = opB(u);
                     if (b >= this.max) {
                        return fail("captured register");
                     }
                     this.captured[b] = true;
                  }
               }
            } else if (o == SETLIST && opC(op) == 0) {
               if (pc + 1 >= this.n) {
                  return fail("setlist data past the end");
               }
               this.insn[pc + 1] = false;
            }
         }
         // jump targets and successors
         for (int pc = 0; pc < this.n; pc++) {
            if (!this.insn[pc]) {
               continue;
            }
            for (int s : succ(pc)) {
               if (s < 0 || s >= this.n || !this.insn[s]) {
                  return fail("jump to " + s + " from " + pc);
               }
            }
            int op = this.code[pc];
            int o = op & 63;
            if (o == JMP || o == FORLOOP || o == FORPREP) {
               this.target[pc + 1 + opSBx(op)] = true;
            } else if (o == LOADBOOL && opC(op) != 0 || o == EQ || o == LT || o == LE || o == TEST || o == TESTSET
                  || o == TFORLOOP) {
               this.target[pc + 2] = true;
               this.target[pc + 1] = true;
            } else if (o == CLOSURE) {
               this.target[pc + 1 + p.prototypes[opBx(op)].numUpvalues] = true;
            } else if (o == SETLIST && opC(op) == 0) {
               this.target[pc + 2] = true;
            }
         }
         int last = this.n - 1;
         while (last > 0 && !this.insn[last]) {
            last--;
         }
         int lo = this.code[last] & 63;
         if (lo != RETURN && lo != TAILCALL && lo != JMP) {
            return fail("does not end in a return");
         }
         // multiple values: a producer (CALL C=0, VARARG B=0) and the B=0 consumer right after it
         for (int pc = 0; pc < this.n; pc++) {
            if (!this.insn[pc]) {
               continue;
            }
            int op = this.code[pc];
            int o = op & 63;
            boolean producer = o == CALL && opC(op) == 0 || o == VARARG && opB(op) == 0;
            if (producer) {
               int q = pc + 1;
               if (q >= this.n || !this.insn[q] || this.target[q]) {
                  return fail("multiple values not consumed at " + pc);
               }
               int c = this.code[q];
               int co = c & 63;
               int a2 = opA(c);
               boolean ok = (co == CALL || co == TAILCALL) && opB(c) == 0 && a2 < opA(op)
                     || co == RETURN && opB(c) == 0 && a2 <= opA(op) || co == SETLIST && opB(c) == 0 && a2 < opA(op);
               if (!ok) {
                  return fail("multiple values consumed by opcode " + co + " at " + q);
               }
               this.mstart[q] = opA(op);
            }
            boolean consumer = (o == CALL || o == TAILCALL || o == SETLIST) && opB(op) == 0 || o == RETURN && opB(op) == 0;
            if (consumer && this.mstart[pc] < 0 && o == RETURN && pc > 0 && this.insn[pc - 1]
                  && (this.code[pc - 1] & 63) == TAILCALL && !this.target[pc]) {
               // the compiler's RETURN A 0 after a TAILCALL: never reached (Kahlua's TAILCALL pops the frame and goes on in
               // the caller for Lua and Java callees alike); generated as a return of the values from A
               this.mstart[pc] = opA(op);
            }
            if (consumer && this.mstart[pc] < 0) {
               return fail("B=0 without a producer at " + pc);
            }
         }
         liveness();
         if (this.nums && !numbers()) {
            this.nums = false;
         }
         // well-formedness the spill / fill scheme relies on
         for (int pc = 0; pc < this.n; pc++) {
            if (!this.insn[pc]) {
               continue;
            }
            int op = this.code[pc];
            int o = op & 63;
            if (o == CALL && opC(op) == 0 || o == VARARG && opB(op) == 0) {
               int a = opA(op);
               BitSet out = this.liveOut[pc];
               int r = out.nextSetBit(a);
               if (r >= 0) {
                  return fail("register " + r + " live over multiple values at " + pc);
               }
            }
         }
         return true;
      }

      int[] succ(int pc) {
         int op = this.code[pc];
         int o = op & 63;
         switch (o) {
            case JMP:
               return new int[] {pc + 1 + opSBx(op)};
            case LOADBOOL:
               return opC(op) != 0 ? new int[] {pc + 2} : new int[] {pc + 1};
            case EQ:
            case LT:
            case LE:
            case TEST:
            case TESTSET:
            case TFORLOOP:
               return new int[] {pc + 1, pc + 2};
            case FORLOOP:
               return new int[] {pc + 1 + opSBx(op), pc + 1};
            case FORPREP:
               return new int[] {pc + 1 + opSBx(op)};
            case RETURN:
            case TAILCALL:
               return new int[0];
            case CLOSURE:
               return new int[] {pc + 1 + p.prototypes[opBx(op)].numUpvalues};
            case SETLIST:
               return opC(op) == 0 ? new int[] {pc + 2} : new int[] {pc + 1};
            default:
               return new int[] {pc + 1};
         }
      }

      void use(BitSet s, int r) {
         if (r < 256 && isLocal(r)) {
            s.set(r);
         }
      }

      void range(BitSet s, int from, int to) {
         for (int r = from; r <= to; r++) {
            if (isLocal(r)) {
               s.set(r);
            }
         }
      }

      void liveness() {
         BitSet[] use = new BitSet[this.n];
         BitSet[] def = new BitSet[this.n];
         for (int pc = 0; pc < this.n; pc++) {
            BitSet u = new BitSet();
            BitSet d = new BitSet();
            use[pc] = u;
            def[pc] = d;
            if (!this.insn[pc]) {
               continue;
            }
            int op = this.code[pc];
            int a = opA(op);
            int b = opB(op);
            int c = opC(op);
            switch (op & 63) {
               case MOVE:
                  use(u, b);
                  range(d, a, a);
                  break;
               case LOADK:
               case GETUPVAL:
               case GETGLOBAL:
               case NEWTABLE:
               case LOADBOOL:
                  range(d, a, a);
                  break;
               case LOADNIL:
                  range(d, a, b);
                  break;
               case GETTABLE:
                  use(u, b);
                  use(u, c);
                  range(d, a, a);
                  break;
               case SETGLOBAL:
               case SETUPVAL:
               case TEST:
                  use(u, a);
                  break;
               case SETTABLE:
                  use(u, a);
                  use(u, b);
                  use(u, c);
                  break;
               case SELF:
                  use(u, b);
                  use(u, c);
                  range(d, a, a + 1);
                  break;
               case ADD:
               case SUB:
               case MUL:
               case DIV:
               case MOD:
               case POW:
                  use(u, b);
                  use(u, c);
                  range(d, a, a);
                  break;
               case UNM:
               case NOT:
               case LEN:
                  use(u, b);
                  range(d, a, a);
                  break;
               case CONCAT:
                  range(u, b, c);
                  range(d, a, a);
                  break;
               case EQ:
               case LT:
               case LE:
                  use(u, b);
                  use(u, c);
                  break;
               case TESTSET:
                  use(u, b);
                  break;
               case CALL:
                  if (b != 0) {
                     range(u, a, a + b - 1);
                  } else {
                     range(u, a, this.mstart[pc] - 1);
                  }
                  range(d, a, this.max - 1);
                  break;
               case TAILCALL:
                  if (b != 0) {
                     range(u, a, a + b - 1);
                  } else {
                     range(u, a, this.mstart[pc] - 1);
                  }
                  break;
               case RETURN:
                  if (b != 0) {
                     range(u, a, a + b - 2);
                  } else {
                     range(u, a, this.mstart[pc] - 1);
                  }
                  break;
               case FORLOOP:
                  range(u, a, a + 2);
                  range(d, a, a);
                  // the visible loop variable is written on the way back into the body and cleared (nil) on the way out
                  if (a + 3 < this.max) {
                     range(d, a + 3, a + 3);
                  }
                  break;
               case FORPREP:
                  use(u, a);
                  use(u, a + 2);
                  range(d, a, a);
                  break;
               case TFORLOOP:
                  range(u, a, a + 2);
                  range(d, a + 3, this.max - 1);
                  break;
               case SETLIST:
                  if (b != 0) {
                     range(u, a, a + b);
                  } else {
                     range(u, a, this.mstart[pc] - 1);
                  }
                  break;
               case CLOSURE:
                  range(d, a, a);
                  break;
               case VARARG:
                  if (b != 0) {
                     range(d, a, a + b - 2);
                  } else {
                     range(d, a, this.max - 1);
                  }
                  break;
               default:
                  break;
            }
         }
         this.useSet = use;
         this.defSet = def;
         this.liveIn = new BitSet[this.n];
         this.liveOut = new BitSet[this.n];
         for (int pc = 0; pc < this.n; pc++) {
            this.liveIn[pc] = new BitSet();
            this.liveOut[pc] = new BitSet();
         }
         int[][] succs = new int[this.n][];
         for (int pc = 0; pc < this.n; pc++) {
            succs[pc] = this.insn[pc] ? succ(pc) : new int[0];
         }
         boolean changed = true;
         while (changed) {
            changed = false;
            for (int pc = this.n - 1; pc >= 0; pc--) {
               if (!this.insn[pc]) {
                  continue;
               }
               BitSet out = new BitSet();
               for (int s : succs[pc]) {
                  out.or(this.liveIn[s]);
               }
               BitSet in = (BitSet)out.clone();
               in.andNot(def[pc]);
               in.or(use[pc]);
               if (!in.equals(this.liveIn[pc]) || !out.equals(this.liveOut[pc])) {
                  this.liveIn[pc] = in;
                  this.liveOut[pc] = out;
                  changed = true;
               }
            }
         }
      }


      // ---------------------------------------------------------------- numbers (luaJitNumbers)

      boolean numConst(int rk) {
         return rk >= 256 && p.constants[rk - 256] instanceof Double;
      }

      /** luaJitMixedNum: rk is a register held only unboxed (S_RAW) in state st. */
      boolean rawIn(byte[] st, int rk) {
         return rk < 256 && isLocal(rk) && st[rk] == S_RAW;
      }

      /** luaJitMixedNum: the operand rk of the current instruction is a register held only unboxed. */
      boolean rawOp(int rk) {
         return MIXED && this.nums && this.cur != null && rawIn(this.cur, rk);
      }

      /** Register r's number is in its double slot. */
      boolean dval(byte[] st, int r) {
         return isLocal(r) && st[r] >= S_RAW;
      }

      boolean numOperand(byte[] st, int rk) {
         return rk >= 256 ? numConst(rk) : dval(st, rk);
      }

      /** An arithmetic instruction (or a comparison) whose operands are all numbers known to the compiler. */
      boolean staticNum(int pc, byte[] st) {
         if (!this.nums) {
            return false;
         }
         int op = this.code[pc];
         switch (op & 63) {
            case ADD:
            case SUB:
            case MUL:
            case DIV:
            case MOD:
            case POW:
            case EQ:
            case LT:
            case LE:
               return numOperand(st, opB(op)) && numOperand(st, opC(op));
            case UNM:
               return dval(st, opB(op));
            default:
               return false;
         }
      }

      /** The local registers instruction pc reads as objects (in state st, before it runs). */
      BitSet objUses(int pc, byte[] st) {
         BitSet u = (BitSet)this.useSet[pc].clone();
         int op = this.code[pc];
         int a = opA(op);
         int b = opB(op);
         int c = opC(op);
         switch (op & 63) {
            case MOVE:
               if (isLocal(a)) {
                  u.clear(b);
               }
               break;
            case ADD:
            case SUB:
            case MUL:
            case DIV:
            case MOD:
            case POW:
            case EQ:
            case LT:
            case LE:
            case UNM:
               if (staticNum(pc, st)) {
                  if (b < 256) {
                     u.clear(b);
                  }
                  if ((op & 63) != UNM && c < 256) {
                     u.clear(c);
                  }
               } else if (MIXED && this.nums && (op & 63) != UNM) {
                  // luaJitMixedNum: the unboxed operand stays unboxed (boxed only on the slow path)
                  if (rawIn(st, b)) {
                     u.clear(b);
                  }
                  if (rawIn(st, c) && c != b) {
                     u.clear(c);
                  }
               }
               break;
            case FORLOOP:
               u.clear(a, a + 3);
               break;
            case FORPREP:
               u.clear(a);
               u.clear(a + 2);
               break;
            case TEST:
               if (MIXED && this.nums && dval(st, a)) {
                  u.clear(a); // luaJitMixedNum: a number is true
               }
               break;
            case TESTSET:
               if (MIXED && this.nums && dval(st, b)) {
                  u.clear(b); // luaJitMixedNum: true; C == 0 never copies it, C != 0 boxes it into R(A) alone
               }
               break;
            case NOT:
               if (MIXED && this.nums && dval(st, b)) {
                  u.clear(b); // luaJitMixedNum: not a number is false
               }
               break;
            case GETTABLE:
               if (this.nums && c < 256 && dval(st, c) && c != b) {
                  u.clear(c);
               }
               break;
            case CALL:
               if (TYPED_ARGS && this.nums && argsCall(b, c)) {
                  for (int r = a + 1; r < a + b; r++) {
                     if (isLocal(r) && st[r] == S_RAW) {
                        u.clear(r); // luaJitTypedArgs: passed unboxed
                     }
                  }
               }
               break;
            default:
               break;
         }
         return u;
      }

      /** The state after the object reads of instruction pc made its raw registers boxed (they are NUM from then on). */
      void materializeState(int pc, byte[] st) {
         BitSet u = objUses(pc, st);
         for (int r = u.nextSetBit(0); r >= 0; r = u.nextSetBit(r + 1)) {
            if (isLocal(r) && st[r] == S_RAW) {
               st[r] = S_NUM;
            }
         }
      }

      void setState(byte[] st, int r, byte v) {
         if (r >= 0 && r < this.max && isLocal(r)) {
            st[r] = v;
         }
      }

      /** The state on the edge from instruction pc (state st: after its materialization) to successor s. */
      byte[] edgeOut(int pc, int s, byte[] st) {
         byte[] o = st.clone();
         int op = this.code[pc];
         int a = opA(op);
         int b = opB(op);
         int c = opC(op);
         switch (op & 63) {
            case MOVE:
               if (isLocal(a)) {
                  o[a] = isLocal(b) ? st[b] : S_OBJ;
               }
               return o;
            case LOADK:
               setState(o, a, p.constants[opBx(op)] instanceof Double ? S_NUM : S_OBJ);
               return o;
            case ADD:
            case SUB:
            case MUL:
            case DIV:
            case MOD:
            case POW:
            case UNM:
               setState(o, a, staticNum(pc, st) || specArith(pc, st) ? S_RAW : S_OBJ);
               return o;
            case FORPREP:
               setState(o, a, S_RAW);
               if (isLocal(a + 2) && st[a + 2] == S_OBJ) {
                  o[a + 2] = S_NUM;
               }
               return o;
            case FORLOOP:
               if (s == pc + 1 + opSBx(op)) {
                  setState(o, a, S_RAW);
                  for (int r = a + 1; r <= a + 2; r++) {
                     if (isLocal(r) && st[r] == S_OBJ) {
                        o[r] = S_NUM;
                     }
                  }
                  setState(o, a + 3, S_RAW);
               } else {
                  for (int r = a; r < this.max; r++) {
                     setState(o, r, S_OBJ);
                  }
               }
               return o;
            case TESTSET:
               if (s == pc + 1) {
                  setState(o, a, S_OBJ);
               }
               return o;
            case TFORLOOP:
               setState(o, a + 2, S_OBJ);
               break;
            default:
               break;
         }
         BitSet d = this.defSet[pc];
         for (int r = d.nextSetBit(0); r >= 0; r = d.nextSetBit(r + 1)) {
            setState(o, r, S_OBJ);
         }
         return o;
      }

      /** Forward analysis of the register states; false when it does not settle (then no numbers for this function). */
      boolean numbers() {
         this.inS = new byte[this.n][];
         this.inS[0] = new byte[this.max];
         java.util.ArrayDeque<Integer> work = new java.util.ArrayDeque<>();
         boolean[] queued = new boolean[this.n];
         work.add(0);
         queued[0] = true;
         long budget = 64L * this.n + 1000;
         while (!work.isEmpty()) {
            if (--budget < 0) {
               this.inS = null;
               return false;
            }
            int pc = work.poll();
            queued[pc] = false;
            byte[] st = this.inS[pc].clone();
            materializeState(pc, st);
            for (int sc : succ(pc)) {
               byte[] o = edgeOut(pc, sc, st);
               byte[] in = this.inS[sc];
               boolean changed = false;
               if (in == null) {
                  this.inS[sc] = o;
                  changed = true;
               } else {
                  for (int r = 0; r < this.max; r++) {
                     if (o[r] < in[r]) {
                        in[r] = o[r];
                        changed = true;
                     }
                  }
               }
               if (changed && !queued[sc]) {
                  work.add(sc);
                  queued[sc] = true;
               }
            }
         }
         return true;
      }

      /**
       * The exit a call takes when it hands the frame to the interpreter (status on the operand stack): the live registers
       * below a written to the Lua stack (not after a reset: the interpreter throws), then the status returned. Call sites
       * with the same registers share one exit.
       */
      Label deoptExit(BitSet live, int a) {
         BitSet regs = new BitSet();
         BitSet raw = new BitSet();
         for (int r = 0; r < a && r < this.max; r++) {
            if (isLocal(r) && live.get(r)) {
               regs.set(r);
               if (this.nums && this.cur != null && this.cur[r] == S_RAW) {
                  raw.set(r);
               }
            }
         }
         String key = regs + "/" + raw;
         Object[] e = this.deoptExits.get(key);
         if (e == null) {
            e = new Object[] {this.cb.newLabel(), regs, raw};
            this.deoptExits.put(key, e);
         }
         return (Label)e[0];
      }

      /**
       * luaJitEntryCompact: one chain per method that writes the local registers below some register to the Lua stack
       * (entered at the label of the highest one it must write, falling through to register 0), then ends as `tail` says.
       * Registers below that one but dead at the exit are written too: their slots hold an older value of the register (or
       * null), which nothing reads before it is written again.
       */
      Label[] chain(int hi) {
         Label[] at = new Label[hi + 1];
         for (int r = 0; r <= hi; r++) {
            at[r] = this.cb.newLabel();
         }
         return at;
      }

      void emitChain(Label[] at) {
         CodeBuilder cb = this.cb;
         for (int r = at.length - 1; r >= 1; r--) {
            cb.labelBinding(at[r]);
            if (isLocal(r - 1)) {
               cb.aload(this.tmpStk);
               index(r - 1);
               cb.aload(slot(r - 1));
               cb.aastore();
            }
         }
         cb.labelBinding(at[0]);
      }

      void emitDeoptExits() {
         CodeBuilder cb = this.cb;
         if (ENTRY_COMPACT && this.locals) {
            // luaJitEntryCompact: each exit boxes its unboxed registers into their slots and joins the shared chain
            int hi = 0;
            for (Object[] e : this.deoptExits.values()) {
               BitSet regs = (BitSet)e[1];
               hi = Math.max(hi, regs.length());
            }
            Label[] at = chain(hi);
            Label ret = cb.newLabel();
            for (Object[] e : this.deoptExits.values()) {
               cb.labelBinding((Label)e[0]);
               BitSet regs = (BitSet)e[1];
               BitSet raw = (BitSet)e[2];
               if (regs.isEmpty()) {
                  cb.ireturn();
                  continue;
               }
               cb.dup();
               cb.loadConstant(LuaJit.ST_RESET);
               cb.if_icmpeq(ret);
               for (int r = raw.nextSetBit(0); r >= 0; r = raw.nextSetBit(r + 1)) {
                  materialize(r);
               }
               loadStack();
               cb.astore(this.tmpStk);
               cb.goto_(at[regs.length()]);
            }
            if (!this.deoptExits.isEmpty()) {
               emitChain(at);
               cb.labelBinding(ret);
               cb.ireturn();
            }
            return;
         }
         for (Object[] e : this.deoptExits.values()) {
            cb.labelBinding((Label)e[0]);
            BitSet regs = (BitSet)e[1];
            BitSet raw = (BitSet)e[2];
            Label ret = cb.newLabel();
            if (!regs.isEmpty()) {
               cb.dup();
               cb.loadConstant(LuaJit.ST_RESET);
               cb.if_icmpeq(ret);
               loadStack();
               cb.astore(this.tmpStk);
               for (int r = regs.nextSetBit(0); r >= 0; r = regs.nextSetBit(r + 1)) {
                  cb.aload(this.tmpStk);
                  index(r);
                  if (raw.get(r)) {
                     cb.dload(dslot(r));
                     toDouble();
                  } else {
                     cb.aload(slot(r));
                  }
                  cb.aastore();
               }
            }
            cb.labelBinding(ret);
            cb.ireturn();
         }
      }

      /** Boxes register r's double into its object slot (stock boxing: KahluaUtil.toDouble). */
      void materialize(int r) {
         this.cb.dload(dslot(r));
         toDouble();
         this.cb.astore(slot(r));
      }

      int dslot(int r) {
         return this.dBase + 2 * r;
      }

      /** The registers to box on the edge from the current instruction to s. */
      BitSet edgeFix(int s) {
         BitSet fix = new BitSet();
         if (!this.nums) {
            return fix;
         }
         byte[] o = edgeOut(this.curPc, s, this.cur);
         byte[] in = this.inS[s];
         BitSet live = this.liveIn[s];
         for (int r = 0; r < this.max; r++) {
            if (isLocal(r) && o[r] == S_RAW && in != null && in[r] == S_OBJ && live.get(r)) {
               fix.set(r);
            }
         }
         return fix;
      }

      /** The label a jump of the current instruction to s goes to (a stub boxing raw registers when s needs them). */
      Label to(int s) {
         BitSet fix = edgeFix(s);
         if (fix.isEmpty()) {
            return L(s);
         }
         Label l = this.cb.newLabel();
         this.stubs.add(new Object[] {l, s, fix});
         return l;
      }

      /** The instruction's own code is done: its fall-through edge's boxing, then the stubs of its jumps. */
      void endInstruction(int pc) {
         int ft = fallThrough(pc);
         if (ft >= 0) {
            BitSet fix = edgeFix(ft);
            for (int r = fix.nextSetBit(0); r >= 0; r = fix.nextSetBit(r + 1)) {
               materialize(r);
            }
            if (!this.stubs.isEmpty() || ft >= this.hi) {
               this.cb.goto_(L(ft));
            }
         }
         for (Object[] st : this.stubs) {
            this.cb.labelBinding((Label)st[0]);
            BitSet fix = (BitSet)st[2];
            for (int r = fix.nextSetBit(0); r >= 0; r = fix.nextSetBit(r + 1)) {
               materialize(r);
            }
            this.cb.goto_(L((Integer)st[1]));
         }
         this.stubs.clear();
      }

      /** The successor reached by falling off the end of instruction pc's code, or -1. */
      int fallThrough(int pc) {
         int op = this.code[pc];
         switch (op & 63) {
            case JMP:
            case RETURN:
            case TAILCALL:
            case FORPREP:
            case EQ:
            case LT:
            case LE:
            case TEST:
            case TESTSET:
            case TFORLOOP:
               return -1;
            case LOADBOOL:
               return opC(op) != 0 ? -1 : pc + 1;
            case CLOSURE:
               return pc + 1 + p.prototypes[opBx(op)].numUpvalues;
            case SETLIST:
               return opC(op) == 0 ? -1 : pc + 1;
            default:
               return pc + 1;
         }
      }

      /** thread, table, key on the operand stack: tableget, through a per-site inline cache for a constant String key (luaJitInlineCache). */
      void tableRead(Object constKey) {
         tableRead(constKey, false);
      }

      /** self: a SELF's method lookup (`obj:m()`), through LuaJit.getSelf when luaJitSelfJava is on. */
      void tableRead(Object constKey, boolean self) {
         if (IC && constKey instanceof String) {
            loadData(new LuaJitIc(), CD_IC);
            this.cb.invokestatic(CD_LUAJIT, self && LuaJit.SELF_JAVA ? "getSelf" : "getStr", MethodTypeDesc.of(CD_OBJ, CD_KT, CD_OBJ, CD_OBJ, CD_IC));
         } else {
            this.cb.invokevirtual(CD_KT, "tableget", MT_OO_O);
         }
      }

      /** Loads register r's number as a double: its double slot, or its object unboxed (KahluaUtil.fromDouble, the stock NPE / CCE). */
      void loadDouble(int r) {
         if (this.nums && dval(this.cur, r)) {
            this.cb.dload(dslot(r));
         } else {
            loadReg(r);
            this.cb.invokestatic(CD_KU, "fromDouble", MT_O_D);
         }
      }

      /** Loads a number operand known to the compiler (a double slot or a number constant). */
      void loadNumOperand(int rk) {
         if (rk >= 256) {
            this.cb.loadConstant(((Double)p.constants[rk - 256]).doubleValue());
         } else {
            this.cb.dload(dslot(rk));
         }
      }

      /** Stores the double on the operand stack, normalised as KahluaUtil.toDouble would (-0.0 is +0.0), into register r. */
      void storeDouble(int r) {
         this.cb.dconst_0();
         this.cb.dadd();
         if (this.nums && isLocal(r)) {
            this.cb.dstore(dslot(r));
         } else {
            toDouble();
            storeReg(r);
         }
      }

      // ---------------------------------------------------------------- code generation

      byte[] build() {
         ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(RESOLVER));
         if (this.cuts != null) {
            return buildRegions();
         }
         return cf.build(self, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
            clb.withSuperclass(CD_CODE);
            clb.withMethodBody(ConstantDescs.INIT_NAME, MT_V, ClassFile.ACC_PUBLIC, c -> {
               c.aload(0);
               c.invokespecial(CD_CODE, ConstantDescs.INIT_NAME, MT_V);
               c.return_();
            });
            clb.withMethodBody("run", MT_RUN, ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL, this::body);
         });
      }

      int k(Object v) {
         Integer i = this.dataIndex.get(v);
         if (i == null) {
            i = this.data.size();
            this.data.add(v);
            this.dataIndex.put(v, i);
         }
         return i;
      }

      void loadData(Object v, ClassDesc type) {
         this.cb.ldc(DynamicConstantDesc.ofNamed(ConstantDescs.BSM_CLASS_DATA_AT, ConstantDescs.DEFAULT_NAME, type, k(v)));
      }

      void loadK(int idx) {
         Object v = p.constants[idx];
         if (v == null) {
            this.cb.aconst_null();
         } else if (v == Boolean.TRUE) {
            this.cb.getstatic(CD_B, "TRUE", CD_B);
         } else if (v == Boolean.FALSE) {
            this.cb.getstatic(CD_B, "FALSE", CD_B);
         } else {
            loadData(v, CD_OBJ);
         }
      }

      Object constant(int rk) {
         return rk >= 256 ? p.constants[rk - 256] : null;
      }

      boolean isConst(int rk) {
         return rk >= 256;
      }

      void loadStack() {
         this.cb.aload(CO);
         this.cb.getfield(CD_CO, "objectStack", CD_OBJS);
      }

      void index(int r) {
         this.cb.iload(BASE);
         if (r != 0) {
            this.cb.loadConstant(r);
            this.cb.iadd();
         }
      }

      void loadReg(int r) {
         if (isLocal(r)) {
            if (this.nums && this.cur != null && this.cur[r] == S_RAW) {
               throw new IllegalStateException("object read of an unboxed register " + r + " at " + this.curPc);
            }
            this.cb.aload(slot(r));
         } else {
            loadStack();
            index(r);
            this.cb.aaload();
         }
      }

      void loadRK(int rk) {
         if (rk >= 256) {
            loadK(rk - 256);
         } else {
            loadReg(rk);
         }
      }

      /** Stores the reference on top of the operand stack into register r. */
      void storeReg(int r) {
         if (isLocal(r)) {
            this.cb.astore(slot(r));
         } else {
            this.cb.astore(this.tmpO2);
            loadStack();
            index(r);
            this.cb.aload(this.tmpO2);
            this.cb.aastore();
         }
      }

      /** The JVM locals of registers in [from, to] that are live (per the set) written to the Lua stack. */
      void spill(BitSet live, int from, int to) {
         boolean loaded = false;
         for (int r = from; r <= to && r < this.max; r++) {
            if (isLocal(r) && (live == null || live.get(r))) {
               if (!loaded) {
                  loadStack();
                  this.cb.astore(this.tmpStk);
                  loaded = true;
               }
               this.cb.aload(this.tmpStk);
               index(r);
               if (this.nums && this.cur != null && this.cur[r] == S_RAW) {
                  this.cb.dload(dslot(r));
                  toDouble();
               } else {
                  this.cb.aload(slot(r));
               }
               this.cb.aastore();
            }
         }
      }

      /** The JVM locals of the live registers in [from, to] read back from the Lua stack. */
      void fill(BitSet live, int from, int to) {
         boolean loaded = false;
         for (int r = from; r <= to && r < this.max; r++) {
            if (isLocal(r) && (live == null || live.get(r))) {
               if (!loaded) {
                  loadStack();
                  this.cb.astore(this.tmpStk);
                  loaded = true;
               }
               this.cb.aload(this.tmpStk);
               index(r);
               this.cb.aaload();
               this.cb.astore(slot(r));
            }
         }
      }

      void nullLocals(BitSet live, int from) {
         for (int r = from; r < this.max; r++) {
            if (isLocal(r) && live.get(r)) {
               this.cb.aconst_null();
               this.cb.astore(slot(r));
            }
         }
      }

      /** f.pc = pc + 1 (the interpreter's pc while it executes instruction pc), skipped when the line is the same. */
      void setPc(int pc, boolean always) {
         int line = p.lines != null && pc < p.lines.length ? p.lines[pc] : Integer.MIN_VALUE + 1;
         if (!always && line == this.lastPcLine && p.lines != null && p.lines.length == this.n) {
            return;
         }
         this.cb.aload(F);
         this.cb.loadConstant(pc + 1);
         this.cb.putfield(CD_LCF, "pc", CD_int);
         this.lastPcLine = line;
      }

      void checkReset() {
         Label ok = this.cb.newLabel();
         this.cb.aload(T);
         this.cb.getfield(CD_KT, "reset", CD_bool);
         this.cb.ifeq(ok);
         this.cb.loadConstant(LuaJit.ST_RESET);
         this.cb.ireturn();
         this.cb.labelBinding(ok);
      }

      void toDouble() {
         if (LuaBox.ALLOC) {
            this.cb.invokestatic(CD_LUABOX, "box", MT_D_DBL); // luaJitBoxAlloc
         } else {
            this.cb.invokestatic(CD_KU, "toDouble", MT_D_DBL);
         }
      }

      void unboxChecked() {
         this.cb.checkcast(CD_D);
         this.cb.invokevirtual(CD_D, "doubleValue", MT_D);
      }

      void body(CodeBuilder c) {
         redirect(c);
         bodyRange(c, 0, this.n);
      }

      /**
       * The code of instructions [lo, hi): the whole function (0, n), or one region of a split function (luaJitSplit),
       * whose jumps out of the range leave through {@link #regionExit}.
       */
      void bodyRange(CodeBuilder c, int lo, int hi) {
         // the ClassFile API may run this again (wide jumps for code past 32 KB): no state from a previous pass
         this.cb = c;
         this.lo = lo;
         this.hi = hi;
         this.deoptExits.clear();
         this.specExits.clear();
         this.regionExits.clear();
         this.stubs.clear();
         this.cur = null;
         this.lastPcLine = Integer.MIN_VALUE;
         this.regBase = 6;
         int next = this.regBase + this.max;
         this.tmpO0 = next++;
         this.tmpO1 = next++;
         this.tmpO2 = next++;
         this.tmpStk = next++;
         this.tmpI0 = next++;
         this.tmpI1 = next++;
         this.tmpI2 = next++;
         this.tmpD0 = next;
         next += 2;
         this.tmpD1 = next;
         next += 2;
         this.tmpD2 = next;
         next += 2;
         this.dBase = next;
         this.labels = new Label[this.n + 2];
         for (int i = 0; i < this.labels.length; i++) {
            this.labels[i] = c.newLabel();
         }
         // prologue
         c.aload(F);
         c.getfield(CD_LCF, "coroutine", CD_CO);
         c.astore(CO);
         c.aload(F);
         c.getfield(CD_LCF, "localBase", CD_int);
         c.istore(BASE);
         c.aload(F);
         c.getfield(CD_LCF, "closure", CD_LC);
         c.astore(CL);
         java.util.TreeSet<Integer> keys = entryKeys(lo, hi);
         entries(keys, lo == 0);
         if (lo == 0 && this.locals) {
            BitSet in0 = this.liveIn[0];
            for (int r = 0; r < this.max; r++) {
               if (isLocal(r) && !in0.get(r)) {
                  c.aconst_null();
                  c.astore(slot(r));
               }
            }
            fill(in0, 0, this.max - 1);
            if (this.nums) {
               for (int r = 0; r < this.max; r++) {
                  if (isLocal(r)) {
                     c.dconst_0();
                     c.dstore(dslot(r));
                  }
               }
            }
         }
         for (int pc = lo; pc < hi; pc++) {
            if (!this.insn[pc]) {
               continue;
            }
            c.labelBinding(this.labels[pc]);
            if (Config.DEV_LUA_JIT_CLASS_NAMES || this.sizeLines) {
               c.lineNumber(pc + 1); // devLuaJitClassNames: the JVM line is the Lua pc + 1 (profiles and code size per instruction)
            }
            if (this.target[pc] || pc == lo || keys.contains(pc)) {
               this.lastPcLine = Integer.MIN_VALUE;
            }
            this.curPc = pc;
            if (this.nums) {
               this.cur = this.inS[pc] != null ? this.inS[pc].clone() : new byte[this.max];
               BitSet u = objUses(pc, this.cur);
               for (int r = u.nextSetBit(0); r >= 0; r = u.nextSetBit(r + 1)) {
                  if (isLocal(r) && this.cur[r] == S_RAW) {
                     materialize(r);
                     this.cur[r] = S_NUM;
                  }
               }
            }
            emit(pc);
            endInstruction(pc);
         }
         emitDeoptExits();
         emitRegionExits();
         emitSpecExits();
      }

      /** The label of instruction pc: its code, or (outside the range being generated) the exit to its region. */
      Label L(int pc) {
         if (pc < this.lo || pc >= this.hi) {
            return regionExit(pc);
         }
         return this.labels[pc];
      }

      /**
       * The pcs where a frame can enter the code of [lo, hi) other than a fresh call (pc 0): with luaJitOsr the loop
       * headers (the target of a backward JMP or of FORLOOP); in a region of a split function also its first instruction and
       * every instruction a jump from another region reaches.
       */
      java.util.TreeSet<Integer> entryKeys(int lo, int hi) {
         java.util.TreeSet<Integer> keys = new java.util.TreeSet<>();
         boolean region = this.cuts != null;
         for (int pc = 0; pc < this.n; pc++) {
            int op = this.code[pc];
            int o = op & 63;
            if (!this.insn[pc]) {
               continue;
            }
            if (OSR && (o == JMP || o == FORLOOP)) {
               int h = pc + 1 + opSBx(op);
               if (h > 0 && h <= pc && h >= lo && h < hi) {
                  keys.add(h);
               }
            }
            if (region && (pc < lo || pc >= hi)) {
               for (int sc : succ(pc)) {
                  if (sc > 0 && sc >= lo && sc < hi) {
                     keys.add(sc);
                  }
               }
            }
         }
         if (region && lo > 0) {
            keys.add(lo);
         }
         keys.removeIf(h -> !this.insn[h] || this.nums && this.inS[h] == null);
         return keys;
      }

      /**
       * Entries other than pc 0 (luaJitOsr, luaJitSplit), selected by f.pc: the live registers are read off the Lua stack
       * (numbers the analysis keeps unboxed are unboxed; a value that is not a number there leaves the frame to the
       * interpreter untouched) and the code continues at the instruction's label. With normal, f.pc 0 is the ordinary
       * entry (the code after this); without (a region that does not start the function) an f.pc that is not an entry
       * leaves the frame to the interpreter.
       */
      void entries(java.util.TreeSet<Integer> heads, boolean normal) {
         CodeBuilder c = this.cb;
         if (heads.isEmpty()) {
            if (!normal) {
               c.iconst_1(); // LuaJit.ST_DEOPT
               c.ireturn();
            }
            return;
         }
         Label normalL = c.newLabel(), untouched = c.newLabel();
         if (normal) {
            c.aload(F);
            c.getfield(CD_LCF, "pc", CD_int);
            c.ifeq(normalL);
         }
         int[] keys = heads.stream().mapToInt(Integer::intValue).toArray();
         java.util.List<java.lang.classfile.instruction.SwitchCase> cases = new ArrayList<>();
         Label[] entry = new Label[keys.length];
         for (int i = 0; i < keys.length; i++) {
            entry[i] = c.newLabel();
            cases.add(java.lang.classfile.instruction.SwitchCase.of(keys[i], entry[i]));
         }
         Label[] rawEntry = new Label[keys.length];
         BitSet cross = SPLIT_RAW && this.cuts != null ? crossTargets() : null;
         for (int i = 0; i < keys.length; i++) {
            if (cross != null && cross.get(keys[i]) && !rawIn(keys[i]).isEmpty()) {
               rawEntry[i] = c.newLabel();
               cases.add(java.lang.classfile.instruction.SwitchCase.of(keys[i] | RAW_PC, rawEntry[i]));
            }
         }
         if (ENTRY_COMPACT && this.locals) {
            // luaJitEntryCompact: every register's slots cleared once before the switch; each entry then loads only its
            // live registers (the same slots as each entry clearing the dead ones itself)
            for (int r = 0; r < this.max; r++) {
               if (isLocal(r)) {
                  c.aconst_null();
                  c.astore(slot(r));
                  if (this.nums) {
                     c.dconst_0();
                     c.dstore(dslot(r));
                  }
               }
            }
         }
         c.aload(F);
         c.getfield(CD_LCF, "pc", CD_int);
         c.lookupswitch(untouched, cases);
         for (int i = 0; i < keys.length; i++) {
            if (rawEntry[i] != null) {
               rawEntry(keys[i], rawEntry[i]);
            }
         }
         for (int i = 0; i < keys.length; i++) {
            int h = keys[i];
            c.labelBinding(entry[i]);
            if (this.locals) {
               BitSet live = this.liveIn[h];
               for (int r = 0; r < this.max && !ENTRY_COMPACT; r++) {
                  if (isLocal(r)) {
                     if (!live.get(r)) {
                        c.aconst_null();
                        c.astore(slot(r));
                     }
                     if (this.nums) {
                        c.dconst_0();
                        c.dstore(dslot(r));
                     }
                  }
               }
               fill(live, 0, this.max - 1);
               if (this.nums) {
                  byte[] st = this.inS[h];
                  for (int r = 0; r < this.max; r++) {
                     if (isLocal(r) && st[r] >= S_RAW && (live.get(r) || Config.DEV_LUA_JIT_ENTRY_DEOPT)) {
                        c.aload(slot(r));
                        c.instanceOf(CD_D);
                        c.ifeq(untouched);
                        c.aload(slot(r));
                        c.checkcast(CD_D);
                        c.invokevirtual(CD_D, "doubleValue", MethodTypeDesc.of(CD_dbl));
                        c.dstore(dslot(r));
                     }
                  }
               }
            }
            c.goto_(this.labels[h]);
         }
         c.labelBinding(untouched);
         c.iconst_1(); // LuaJit.ST_DEOPT: the interpreter continues the frame as it stands
         c.ireturn();
         if (normal) {
            c.labelBinding(normalL);
         }
         this.osr = keys.length;
      }

      int[] crossCuts;
      BitSet cross;

      /** luaJitSplitRaw: the instructions a jump (or fall-through) from another region reaches (for the current cuts). */
      BitSet crossTargets() {
         if (this.crossCuts != this.cuts) {
            BitSet b = new BitSet();
            for (int pc = 0; pc < this.n; pc++) {
               if (this.insn[pc]) {
                  int rp = regionOf(pc);
                  for (int sc : succ(pc)) {
                     if (sc >= 0 && sc < this.n && regionOf(sc) != rp) {
                        b.set(sc);
                     }
                  }
               }
            }
            this.cross = b;
            this.crossCuts = this.cuts;
         }
         return this.cross;
      }

      int regionOf(int pc) {
         int i = java.util.Arrays.binarySearch(this.cuts, pc);
         return i >= 0 ? i : -i - 2;
      }

      /**
       * luaJitSplitRaw: the registers a jump from another region hands over unboxed at s (live, JVM locals, unboxed and not
       * also boxed at s): empty when the function is not split or the key is off.
       */
      BitSet rawIn(int s) {
         BitSet b = new BitSet();
         if (!SPLIT_RAW || !this.nums || !this.locals || this.cuts == null || s < 0 || s >= this.n || this.inS[s] == null) {
            return b;
         }
         BitSet live = this.liveIn[s];
         byte[] in = this.inS[s];
         for (int r = 0; r < this.max; r++) {
            if (isLocal(r) && live.get(r) && in[r] == S_RAW) {
               b.set(r);
            }
         }
         return b;
      }

      /**
       * luaJitSplitRaw: the entry at h from another region's raw exit (f.pc = h | RAW_PC): the registers rawIn(h) from
       * KahluaThread.pzoptJitD, the others off the Lua stack as at a plain entry. A value that is not a number leaves the
       * frame to the interpreter with the handed-over numbers boxed onto the stack first and f.pc = h.
       */
      void rawEntry(int h, Label at) {
         CodeBuilder c = this.cb;
         BitSet arr = rawIn(h);
         Label back = c.newLabel();
         c.labelBinding(at);
         BitSet live = this.liveIn[h];
         for (int r = 0; r < this.max; r++) {
            if (isLocal(r)) {
               if (!live.get(r)) {
                  c.aconst_null();
                  c.astore(slot(r));
               }
               c.dconst_0();
               c.dstore(dslot(r));
            }
         }
         fill(live, 0, this.max - 1); // the handed-over registers' stack slots are stale: their object slots are never read unboxed (S_RAW)
         byte[] st = this.inS[h];
         for (int r = 0; r < this.max; r++) {
            if (!isLocal(r) || st[r] < S_RAW || !(live.get(r) || Config.DEV_LUA_JIT_ENTRY_DEOPT)) {
               continue;
            }
            if (arr.get(r)) {
               c.aload(T);
               c.getfield(CD_KT, "pzoptJitD", CD_DARR);
               c.loadConstant(r);
               c.daload();
               c.dstore(dslot(r));
            } else {
               c.aload(slot(r));
               c.instanceOf(CD_D);
               c.ifeq(back);
               c.aload(slot(r));
               c.checkcast(CD_D);
               c.invokevirtual(CD_D, "doubleValue", MethodTypeDesc.of(CD_dbl));
               c.dstore(dslot(r));
            }
         }
         c.goto_(this.labels[h]);
         c.labelBinding(back);
         loadStack();
         c.astore(this.tmpStk);
         for (int r = arr.nextSetBit(0); r >= 0; r = arr.nextSetBit(r + 1)) {
            c.aload(this.tmpStk);
            index(r);
            c.aload(T);
            c.getfield(CD_KT, "pzoptJitD", CD_DARR);
            c.loadConstant(r);
            c.daload();
            toDouble();
            c.aastore();
         }
         c.aload(F);
         c.loadConstant(h);
         c.putfield(CD_LCF, "pc", CD_int);
         c.iconst_1(); // LuaJit.ST_DEOPT
         c.ireturn();
      }

      /**
       * luaJitSplit: the exit of the current instruction's jump (or fall-through) to s in another region: the registers
       * live at s written to the Lua stack (unboxed numbers boxed, as at a deopt), f.pc = s and ST_REGION to the
       * dispatcher, which enters the region holding s there. Jumps with the same target and boxing share one exit.
       */
      Label regionExit(int s) {
         BitSet raw = new BitSet();
         BitSet arr = new BitSet();
         if (this.nums && this.cur != null && s >= 0 && s < this.n) {
            byte[] o = edgeOut(this.curPc, s, this.cur);
            byte[] in = this.inS[s];
            BitSet live = this.liveIn[s];
            for (int r = 0; r < this.max; r++) {
               if (isLocal(r) && live.get(r) && o[r] == S_RAW && (in == null || in[r] != S_OBJ)) {
                  raw.set(r);
               }
            }
            arr = rawIn(s);
            if (!arr.isEmpty() && !crossTargets().get(s)) {
               arr = new BitSet();
            }
            for (int r = arr.nextSetBit(0); r >= 0; r = arr.nextSetBit(r + 1)) {
               if (o[r] < S_RAW) {
                  arr = new BitSet(); // its double is not at hand here: the plain exit (the entry unboxes the stack)
                  break;
               }
            }
         }
         String key = s + "/" + raw + "/" + arr;
         Object[] e = this.regionExits.get(key);
         if (e == null) {
            e = new Object[] {this.cb.newLabel(), s, raw, arr};
            this.regionExits.put(key, e);
         }
         return (Label)e[0];
      }

      void emitRegionExits() {
         CodeBuilder cb = this.cb;
         Label[] at = null;
         if (ENTRY_COMPACT && this.locals) {
            // luaJitEntryCompact: the exits that hand nothing over as doubles and whose target is outside a multi-result
            // window share one chain (f and the target pc on the operand stack meanwhile)
            int hi = 0;
            for (Object[] e : this.regionExits.values()) {
               int s = (Integer)e[1];
               if (((BitSet)e[3]).isEmpty() && s >= 0 && s < this.n && this.mstart[s] < 0) {
                  hi = Math.max(hi, this.liveIn[s].length());
               }
            }
            if (hi > 0) {
               at = chain(Math.min(hi, this.max));
            }
         }
         for (Object[] e : this.regionExits.values()) {
            if (at != null) {
               int s = (Integer)e[1];
               BitSet raw = (BitSet)e[2];
               if (((BitSet)e[3]).isEmpty() && s >= 0 && s < this.n && this.mstart[s] < 0 && !this.liveIn[s].isEmpty()) {
                  cb.labelBinding((Label)e[0]);
                  for (int r = raw.nextSetBit(0); r >= 0; r = raw.nextSetBit(r + 1)) {
                     materialize(r);
                  }
                  cb.aload(F);
                  cb.loadConstant(s);
                  loadStack();
                  cb.astore(this.tmpStk);
                  cb.goto_(at[Math.min(this.liveIn[s].length(), this.max)]);
                  continue;
               }
            }
            cb.labelBinding((Label)e[0]);
            int s = (Integer)e[1];
            BitSet raw = (BitSet)e[2];
            BitSet arr = (BitSet)e[3];
            if (this.locals && s >= 0 && s < this.n) {
               BitSet live = this.liveIn[s];
               boolean loaded = false;
               for (int r = 0; r < this.max; r++) {
                  if (isLocal(r) && live.get(r) && !arr.get(r)) {
                     if (!loaded) {
                        loadStack();
                        cb.astore(this.tmpStk);
                        loaded = true;
                     }
                     cb.aload(this.tmpStk);
                     index(r);
                     if (raw.get(r)) {
                        cb.dload(dslot(r));
                        toDouble();
                     } else {
                        cb.aload(slot(r));
                     }
                     cb.aastore();
                  }
               }
            }
            if (!arr.isEmpty()) {
               cb.aload(T);
               cb.invokestatic(CD_LUAJIT, "regionD", MethodTypeDesc.of(CD_DARR, CD_KT));
               cb.astore(this.tmpO0);
               for (int r = arr.nextSetBit(0); r >= 0; r = arr.nextSetBit(r + 1)) {
                  cb.aload(this.tmpO0);
                  cb.loadConstant(r);
                  cb.dload(dslot(r));
                  cb.dastore();
               }
            }
            cb.aload(F);
            cb.loadConstant(arr.isEmpty() ? s : s | RAW_PC);
            cb.putfield(CD_LCF, "pc", CD_int);
            cb.loadConstant(LuaJit.ST_REGION);
            cb.ireturn();
         }
         if (at != null) {
            emitChain(at);
            cb.putfield(CD_LCF, "pc", CD_int);
            cb.loadConstant(LuaJit.ST_REGION);
            cb.ireturn();
         }
      }

      /** luaJitSplit: run() of a split function: the region holding f.pc runs until the frame leaves the function's code. */
      void dispatcher(CodeBuilder c) {
         redirect(c);
         Label loop = c.newLabel();
         c.labelBinding(loop);
         c.aload(F);
         c.getfield(CD_LCF, "pc", CD_int);
         if (SPLIT_RAW) {
            c.loadConstant(RAW_PC - 1);
            c.iand();
         }
         c.istore(3);
         pick(c, 0, this.cuts.length - 1, loop);
      }

      void pick(CodeBuilder c, int i, int j, Label loop) {
         if (i == j) {
            Label ret = c.newLabel();
            c.aload(THIS);
            c.aload(T);
            c.aload(F);
            c.invokespecial(self, "r" + i, MT_RUN);
            c.dup();
            c.loadConstant(LuaJit.ST_REGION);
            c.if_icmpne(ret);
            c.pop();
            c.goto_(loop);
            c.labelBinding(ret);
            c.ireturn();
            return;
         }
         int m = (i + j + 1) / 2;
         Label upper = c.newLabel();
         c.iload(3);
         c.loadConstant(this.cuts[m]);
         c.if_icmpge(upper);
         pick(c, i, m - 1, loop);
         c.labelBinding(upper);
         pick(c, m, j, loop);
      }

      /** The class of a split function: run() = the dispatcher, r0 .. rk = the regions [cuts[i], cuts[i + 1]). */
      byte[] buildRegions() {
         ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(RESOLVER));
         int[] cuts = this.cuts;
         return cf.build(self, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
            clb.withSuperclass(CD_CODE);
            clb.withMethodBody(ConstantDescs.INIT_NAME, MT_V, ClassFile.ACC_PUBLIC, c -> {
               c.aload(0);
               c.invokespecial(CD_CODE, ConstantDescs.INIT_NAME, MT_V);
               c.return_();
            });
            clb.withMethodBody("run", MT_RUN, ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL, this::dispatcher);
            for (int i = 0; i < cuts.length; i++) {
               int lo = cuts[i], hi = i + 1 < cuts.length ? cuts[i + 1] : this.n;
               clb.withMethodBody("r" + i, MT_RUN, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL, c -> bodyRange(c, lo, hi));
            }
         });
      }

      /**
       * luaJitSplit: the function as regions of at most HUGE bytes of JVM code each (null when it needs more than
       * luaJitSplitMax). The first cut is an even split by instruction count into fullLen / target regions, each cut moved
       * within a window to the instruction with the fewest live registers outside loops; a region still past HUGE is cut in
       * two again.
       */
      byte[] buildSplit(int fullLen) {
         int target = Math.max(64, Math.min(5000, SPLIT_ABOVE * 5 / 8));
         int maxRegions = Config.LUA_JIT_SPLIT_MAX;
         // loop depth per pc (backward edges), the cost of starting a region there
         int[] depth = new int[this.n + 1];
         for (int pc = 0; pc < this.n; pc++) {
            if (!this.insn[pc]) {
               continue;
            }
            for (int sc : succ(pc)) {
               if (sc <= pc && sc >= 0) {
                  depth[sc]++;
                  depth[pc + 1]--;
               }
            }
         }
         for (int pc = 1; pc <= this.n; pc++) {
            depth[pc] += depth[pc - 1];
         }
         List<Integer> cand = new ArrayList<>();
         for (int pc = 1; pc < this.n; pc++) {
            if (this.insn[pc] && this.mstart[pc] < 0 && (!this.nums || this.inS[pc] != null)) {
               cand.add(pc);
            }
         }
         if (cand.isEmpty()) {
            this.splitWhy = "no place to split";
            return null;
         }
         int k = Math.max(2, (fullLen + target - 1) / target);
         if (k > maxRegions && (fullLen + maxRegions - 1) / maxRegions <= HUGE * 3 / 4) {
            // bigger regions instead of more of them (each still well under HotSpot's limit)
            target = (fullLen + maxRegions - 1) / maxRegions;
            k = maxRegions;
         }
         if (k > maxRegions) {
            this.splitWhy = "more than " + maxRegions + " regions (" + fullLen + " bytes)";
            return null;
         }
         java.util.TreeSet<Integer> cutSet = new java.util.TreeSet<>();
         cutSet.add(0);
         if (testCuts != null) {
            for (int c : testCuts) {
               cutSet.add(c);
            }
         } else {
            dpBudget = target;
            int[] dp = SPLIT_DP ? dpCuts(cand, depth, target, maxRegions) : null;
            if (dp != null) {
               for (int c : dp) {
                  cutSet.add(c);
               }
            } else {
               for (int j = 1; j < k; j++) {
                  cutSet.add(bestCut(cand, depth, 0, this.n, (long)this.n * j / k, Math.max(1, this.n / k / 3)));
               }
            }
         }
         for (int round = 0; round < 8; round++) {
            this.cuts = cutSet.stream().mapToInt(Integer::intValue).toArray();
            byte[] bytes;
            try {
               bytes = buildRegions();
            } catch (IllegalArgumentException e) {
               this.splitWhy = "region build: " + e.getMessage();
               this.cuts = null;
               return null;
            }
            int[] lens = methodLengths(bytes);
            boolean ok = true;
            if (SPLIT_DP && testCuts == null && this.perPc != null) {
               // luaJitSplitDp: a region past HUGE (its entries and exits on top of its instructions): the cuts again, for
               // a budget smaller by that region's share over
               int maxLen = 0;
               for (int len : lens) {
                  maxLen = Math.max(maxLen, len);
               }
               if (maxLen > HUGE) {
                  dpBudget = (int)((long)dpBudget * (HUGE * 95 / 100) / maxLen) - 32;
                  int[] dp = dpBudget > 64 ? dpCuts(cand, depth, dpBudget, maxRegions) : null;
                  if (dp != null) {
                     cutSet.clear();
                     cutSet.add(0);
                     for (int c : dp) {
                        cutSet.add(c);
                     }
                     continue;
                  }
               }
            }
            for (int i = 0; i < lens.length; i++) {
               if (lens[i] > HUGE) {
                  ok = false;
                  int lo = this.cuts[i], hi = i + 1 < this.cuts.length ? this.cuts[i + 1] : this.n;
                  int c = bestCut(cand, depth, lo + 1, hi, (lo + hi) / 2, Math.max(1, (hi - lo) / 6));
                  if (c <= lo || c >= hi) {
                     this.splitWhy = "region of " + lens[i] + " bytes cannot be cut";
                     this.cuts = null;
                     return null;
                  }
                  cutSet.add(c);
               }
            }
            if (ok) {
               return bytes;
            }
            if (cutSet.size() > maxRegions) {
               this.splitWhy = "more than " + maxRegions + " regions";
               this.cuts = null;
               return null;
            }
         }
         this.splitWhy = "regions did not fit";
         this.cuts = null;
         return null;
      }

      /**
       * luaJitSplitDp: the cuts that cost least to cross, as a shortest path over the candidate cuts: a cut costs
       * 8^(loop depth) x (4 + the local registers live there) (a crossing boxes them, leaves the region method and enters
       * the next; inside a loop it happens every iteration), each region's instructions at most `budget` bytes of the
       * whole function's code (each instruction measured in a build with line numbers). Null: no such cuts (or more than
       * maxRegions regions, or too many candidates); the even split then.
       */
      int[] dpCuts(List<Integer> cand, int[] depth, int budget, int maxRegions) {
         int m = cand.size();
         if (m > 4000) {
            return null;
         }
         int[] per = this.perPc;
         if (per == null) {
         per = new int[this.n + 1];
         this.sizeLines = true;
         try {
            byte[] b = build();
            for (MethodModel mm : ClassFile.of().parse(b).methods()) {
               if (!mm.methodName().stringValue().equals("run")) {
                  continue;
               }
               CodeAttribute code = mm.findAttribute(Attributes.code()).get();
               List<int[]> lines = new ArrayList<>();
               for (java.lang.classfile.Attribute<?> at : code.attributes()) {
                  if (at instanceof java.lang.classfile.attribute.LineNumberTableAttribute lt) {
                     for (java.lang.classfile.attribute.LineNumberInfo li : lt.lineNumbers()) {
                        lines.add(new int[] {li.startPc(), li.lineNumber() - 1});
                     }
                  }
               }
               lines.sort((x, y) -> Integer.compare(x[0], y[0]));
               for (int i = 0; i < lines.size(); i++) {
                  int pc = lines.get(i)[1];
                  if (pc >= 0 && pc < this.n) {
                     per[pc] += (i + 1 < lines.size() ? lines.get(i + 1)[0] : code.codeLength()) - lines.get(i)[0];
                  }
               }
            }
         } catch (IllegalArgumentException e) {
            return null; // past 64 KB as one method: the even split (which never needs the whole build)
         } finally {
            this.sizeLines = false;
         }
         per[this.n - 1] = Math.min(per[this.n - 1], 64); // the exits emitted after the last instruction belong to no region
         this.perPc = per;
         }
         long[] pre = new long[this.n + 1];
         for (int pc = 0; pc < this.n; pc++) {
            pre[pc + 1] = pre[pc] + per[pc];
         }
         // node 0 = pc 0, node i + 1 = cand[i]
         int[] at = new int[m + 1];
         at[0] = 0;
         for (int i = 0; i < m; i++) {
            at[i + 1] = cand.get(i);
         }
         long[] best = new long[m + 1];
         int[] from = new int[m + 1];
         int[] count = new int[m + 1];
         java.util.Arrays.fill(best, Long.MAX_VALUE);
         best[0] = 0;
         for (int j = 1; j <= m; j++) {
            long w = cutCost(at[j], depth);
            for (int i = j - 1; i >= 0; i--) {
               if (pre[at[j]] - pre[at[i]] > budget) {
                  break; // earlier starts only make the region bigger
               }
               if (best[i] != Long.MAX_VALUE && best[i] + w < best[j]) {
                  best[j] = best[i] + w;
                  from[j] = i;
                  count[j] = count[i] + 1;
               }
            }
         }
         int end = -1;
         long endCost = Long.MAX_VALUE;
         for (int i = 0; i <= m; i++) {
            if (best[i] != Long.MAX_VALUE && pre[this.n] - pre[at[i]] <= budget && best[i] < endCost) {
               endCost = best[i];
               end = i;
            }
         }
         if (end <= 0 || count[end] + 1 > maxRegions) {
            return null;
         }
         int[] r = new int[count[end]];
         for (int i = end, q = r.length - 1; i > 0; i = from[i]) {
            r[q--] = at[i];
         }
         if (Boolean.getBoolean("pzopt.devSplitDpDebug")) {
            System.out.println("dp cuts " + java.util.Arrays.toString(r) + " cost " + endCost + " sizes " + pre[this.n]);
            for (int c : r) System.out.println("  cut " + c + " depth " + depth[c] + " pre " + pre[c] + " w " + cutCost(c, depth));
         }
         return r;
      }

      long cutCost(int pc, int[] depth) {
         int live = 0;
         if (this.locals) {
            BitSet l = this.liveIn[pc];
            for (int r = l.nextSetBit(0); r >= 0; r = l.nextSetBit(r + 1)) {
               if (isLocal(r)) {
                  live++;
               }
            }
         }
         return (1L << (3 * Math.min(depth[pc], 12))) * (4 + live);
      }

      /** The candidate cut in [from, to) nearest the window around pos with the lowest cost (loop depth first, then live registers). */
      int bestCut(List<Integer> cand, int[] depth, int from, int to, long pos, int window) {
         int best = -1;
         long bestCost = Long.MAX_VALUE;
         for (int pc : cand) {
            if (pc < from || pc >= to || pc < pos - window || pc > pos + window) {
               continue;
            }
            int live = 0;
            if (this.locals) {
               BitSet l = this.liveIn[pc];
               for (int r = l.nextSetBit(0); r >= 0; r = l.nextSetBit(r + 1)) {
                  if (isLocal(r)) {
                     live++;
                  }
               }
            }
            long cost = depth[pc] * 100000L + live * 100L + Math.abs(pc - pos);
            if (cost < bestCost) {
               bestCost = cost;
               best = pc;
            }
         }
         if (best < 0) {
            // no candidate in the window: the nearest one in range
            for (int pc : cand) {
               if (pc >= from && pc < to && (best < 0 || Math.abs(pc - pos) < Math.abs(best - pos))) {
                  best = pc;
               }
            }
         }
         return best;
      }

      int osr;

      void emit(int pc) {
         CodeBuilder c = this.cb;
         int op = this.code[pc];
         int o = op & 63;
         int a = opA(op);
         int b = opB(op);
         int cc = opC(op);
         switch (o) {
            case MOVE:
               if (this.nums && isLocal(a) && isLocal(b)) {
                  if (this.cur[b] >= S_RAW) {
                     c.dload(dslot(b));
                     c.dstore(dslot(a));
                  }
                  if (this.cur[b] != S_RAW) {
                     c.aload(slot(b));
                     c.astore(slot(a));
                  }
                  break;
               }
               loadReg(b);
               storeReg(a);
               break;
            case LOADK:
               loadK(opBx(op));
               storeReg(a);
               if (this.nums && isLocal(a) && p.constants[opBx(op)] instanceof Double kd) {
                  c.loadConstant(kd.doubleValue());
                  c.dstore(dslot(a));
               }
               break;
            case LOADBOOL:
               c.getstatic(CD_B, b == 0 ? "FALSE" : "TRUE", CD_B);
               storeReg(a);
               if (cc != 0) {
                  c.goto_(to(pc + 2));
               }
               break;
            case LOADNIL:
               for (int r = a; r <= b; r++) {
                  c.aconst_null();
                  storeReg(r);
               }
               break;
            case GETUPVAL:
               c.aload(CL);
               c.getfield(CD_LC, "upvalues", CD_UVS);
               c.loadConstant(b);
               c.aaload();
               c.invokevirtual(CD_UV, "getValue", MT_O);
               storeReg(a);
               break;
            case GETGLOBAL:
               setPc(pc, false);
               c.aload(T);
               c.aload(CL);
               c.getfield(CD_LC, "env", CD_TABLE);
               loadK(opBx(op));
               tableRead(p.constants[opBx(op)]);
               storeReg(a);
               checkReset();
               break;
            case GETTABLE:
               setPc(pc, false);
               c.aload(T);
               loadReg(b);
               if (this.nums && cc < 256 && cc != b && dval(this.cur, cc)) {
                  // luaJitNumbers: t[i] with i held unboxed
                  c.dload(dslot(cc));
                  c.invokestatic(CD_LUAJIT, "getNum", MethodTypeDesc.of(CD_OBJ, CD_KT, CD_OBJ, CD_dbl));
               } else {
                  loadRK(cc);
                  tableRead(constant(cc));
               }
               storeReg(a);
               checkReset();
               break;
            case SETGLOBAL:
               setPc(pc, false);
               c.aload(T);
               c.aload(CL);
               loadK(opBx(op));
               loadReg(a);
               c.invokevirtual(CD_KT, "pzoptJitSetGlobal", MethodTypeDesc.of(CD_void, CD_LC, CD_OBJ, CD_OBJ));
               checkReset();
               break;
            case SETUPVAL:
               if (LuaJitDual.ON) {
                  c.aload(T);
                  c.invokestatic(ClassDesc.of("pzopt.LuaJitDual"), "upvalue", MethodTypeDesc.of(CD_void, CD_KT));
               }
               c.aload(CL);
               c.getfield(CD_LC, "upvalues", CD_UVS);
               c.loadConstant(b);
               c.aaload();
               loadReg(a);
               c.invokevirtual(CD_UV, "setValue", MT_O_V);
               break;
            case SETTABLE:
               setPc(pc, false);
               c.aload(T);
               loadReg(a);
               loadRK(b);
               loadRK(cc);
               if (IC && b >= 256 && p.constants[b - 256] instanceof String) {
                  loadData(new LuaJitIc(), CD_IC);
                  c.invokestatic(CD_LUAJIT, "setStr", MethodTypeDesc.of(CD_void, CD_KT, CD_OBJ, CD_OBJ, CD_OBJ, CD_IC));
               } else {
                  c.invokevirtual(CD_KT, "tableSet", MT_OOO_V);
               }
               checkReset();
               break;
            case NEWTABLE:
               c.aload(T);
               c.invokevirtual(CD_KT, "getPlatform", MethodTypeDesc.of(CD_PLATFORM));
               c.invokeinterface(CD_PLATFORM, "newTable", MethodTypeDesc.of(CD_TABLE));
               storeReg(a);
               break;
            case SELF:
               setPc(pc, false);
               loadRK(cc);
               c.astore(this.tmpO1);
               loadReg(b);
               c.astore(this.tmpO0);
               c.aload(F);
               c.putstatic(CD_KT, "lastCallFrame", CD_LCF);
               c.aload(T);
               c.aload(this.tmpO0);
               c.aload(this.tmpO1);
               tableRead(constant(cc), true);
               storeReg(a);
               c.aload(this.tmpO0);
               storeReg(a + 1);
               checkReset();
               break;
            case ADD:
            case SUB:
            case MUL:
            case DIV:
            case MOD:
            case POW:
               arith(pc, o, a, b, cc);
               break;
            case UNM: {
               if (staticNum(pc, this.cur)) {
                  c.dload(dslot(b));
                  c.dneg();
                  storeDouble(a);
                  break;
               }
               Label slow = c.newLabel();
               Label done = c.newLabel();
               loadReg(b);
               c.astore(this.tmpO0);
               c.aload(this.tmpO0);
               c.instanceOf(CD_D);
               c.ifeq(slow);
               c.aload(this.tmpO0);
               unboxChecked();
               c.dneg();
               toDouble();
               storeReg(a);
               c.goto_(done);
               c.labelBinding(slow);
               setPc(pc, true);
               c.aload(T);
               c.aload(this.tmpO0);
               c.invokevirtual(CD_KT, "pzoptJitUnm", MethodTypeDesc.of(CD_OBJ, CD_OBJ));
               storeReg(a);
               checkReset();
               c.labelBinding(done);
               this.lastPcLine = Integer.MIN_VALUE;
               break;
            }
            case NOT: {
               if (MIXED && this.nums && dval(this.cur, b)) {
                  c.getstatic(CD_B, "FALSE", CD_B); // luaJitMixedNum: not a number is false
                  storeReg(a);
                  break;
               }
               Label isFalse = c.newLabel();
               Label st = c.newLabel();
               loadReg(b);
               c.astore(this.tmpO0);
               c.aload(this.tmpO0);
               c.ifnull(isFalse);
               c.aload(this.tmpO0);
               c.getstatic(CD_B, "FALSE", CD_B);
               c.if_acmpeq(isFalse);
               c.getstatic(CD_B, "FALSE", CD_B);
               c.goto_(st);
               c.labelBinding(isFalse);
               c.getstatic(CD_B, "TRUE", CD_B);
               c.labelBinding(st);
               storeReg(a);
               break;
            }
            case LEN: {
               Label notTable = c.newLabel();
               Label slow = c.newLabel();
               Label done = c.newLabel();
               loadReg(b);
               c.astore(this.tmpO0);
               c.aload(this.tmpO0);
               c.instanceOf(CD_TABLE);
               c.ifeq(notTable);
               c.aload(this.tmpO0);
               c.checkcast(CD_TABLE);
               c.invokeinterface(CD_TABLE, "len", MT_I);
               c.i2l();
               c.invokestatic(CD_KU, "toDouble", MT_J_DBL);
               storeReg(a);
               c.goto_(done);
               c.labelBinding(notTable);
               c.aload(this.tmpO0);
               c.instanceOf(CD_S);
               c.ifeq(slow);
               c.aload(this.tmpO0);
               c.checkcast(CD_S);
               c.invokevirtual(CD_S, "length", MT_I);
               c.i2l();
               c.invokestatic(CD_KU, "toDouble", MT_J_DBL);
               storeReg(a);
               c.goto_(done);
               c.labelBinding(slow);
               setPc(pc, true);
               c.aload(T);
               c.aload(F);
               c.aload(this.tmpO0);
               c.invokevirtual(CD_KT, "pzoptJitLen", MethodTypeDesc.of(CD_OBJ, CD_LCF, CD_OBJ));
               storeReg(a);
               checkReset();
               c.labelBinding(done);
               this.lastPcLine = Integer.MIN_VALUE;
               break;
            }
            case CONCAT:
               spill(null, b, cc);
               setPc(pc, false);
               c.aload(T);
               c.aload(F);
               c.loadConstant(b);
               c.loadConstant(cc);
               c.invokevirtual(CD_KT, "pzoptJitConcat", MethodTypeDesc.of(CD_OBJ, CD_LCF, CD_int, CD_int));
               storeReg(a);
               checkReset();
               break;
            case JMP:
               c.goto_(to(pc + 1 + opSBx(op)));
               break;
            case EQ:
            case LT:
            case LE:
               compare(pc, o, a, b, cc);
               break;
            case TEST: {
               // if (boolEval(R(A)) == (C == 0)) skip the next instruction
               if (MIXED && this.nums && dval(this.cur, a)) {
                  c.goto_(cc == 0 ? to(pc + 2) : to(pc + 1)); // luaJitMixedNum: a number is true
                  break;
               }
               Label falsy = c.newLabel();
               loadReg(a);
               c.astore(this.tmpO0);
               c.aload(this.tmpO0);
               c.ifnull(falsy);
               c.aload(this.tmpO0);
               c.getstatic(CD_B, "FALSE", CD_B);
               c.if_acmpeq(falsy);
               c.goto_(cc == 0 ? to(pc + 2) : to(pc + 1));
               c.labelBinding(falsy);
               c.goto_(cc == 0 ? to(pc + 1) : to(pc + 2));
               break;
            }
            case TESTSET: {
               // if (boolEval(R(B)) != (C == 0)) R(A) = R(B) else skip
               if (MIXED && this.nums && dval(this.cur, b)) {
                  // luaJitMixedNum: a number is true: C != 0 copies it (boxed into R(A) only), C == 0 skips
                  if (cc != 0) {
                     if (this.cur[b] == S_RAW) {
                        c.dload(dslot(b));
                        toDouble();
                     } else {
                        loadReg(b);
                     }
                     storeReg(a);
                     c.goto_(to(pc + 1));
                  } else {
                     c.goto_(to(pc + 2));
                  }
                  break;
               }
               Label falsy = c.newLabel();
               Label set = c.newLabel();
               loadReg(b);
               c.astore(this.tmpO0);
               c.aload(this.tmpO0);
               c.ifnull(falsy);
               c.aload(this.tmpO0);
               c.getstatic(CD_B, "FALSE", CD_B);
               c.if_acmpeq(falsy);
               // truthy: boolEval == true; set when (C == 0) is false, i.e. C != 0
               c.goto_(cc != 0 ? set : to(pc + 2));
               c.labelBinding(falsy);
               c.goto_(cc != 0 ? to(pc + 2) : set);
               c.labelBinding(set);
               c.aload(this.tmpO0);
               storeReg(a);
               c.goto_(to(pc + 1));
               break;
            }
            case CALL:
               call(pc, a, b, cc);
               break;
            case TAILCALL:
               setPc(pc, true);
               if (b != 0) {
                  spill(null, a, a + b - 1);
               } else {
                  spill(null, a, this.mstart[pc] - 1);
               }
               c.aload(T);
               c.aload(F);
               c.loadConstant(a);
               c.loadConstant(b);
               c.invokevirtual(CD_KT, "pzoptJitTailCall", MethodTypeDesc.of(CD_int, CD_LCF, CD_int, CD_int));
               c.ireturn();
               break;
            case RETURN:
               ret(pc, a, b);
               break;
            case FORLOOP:
               forloop(pc, a, opSBx(op));
               break;
            case FORPREP:
               setPc(pc, false);
               loadDouble(a);
               loadDouble(a + 2);
               if (this.nums && isLocal(a + 2) && this.cur[a + 2] == S_OBJ) {
                  c.dup2();
                  c.dstore(dslot(a + 2));
               }
               c.dsub();
               storeDouble(a);
               c.goto_(to(pc + 1 + opSBx(op)));
               break;
            case TFORLOOP:
               tforloop(pc, a, cc);
               break;
            case SETLIST:
               setlist(pc, a, b, cc);
               break;
            case CLOSE:
               c.aload(F);
               c.loadConstant(a);
               c.invokevirtual(CD_LCF, "closeUpvalues", MT_I_V);
               break;
            case CLOSURE:
               closure(pc, a, opBx(op));
               break;
            case VARARG:
               c.aload(F);
               c.loadConstant(a);
               c.loadConstant(b - 1);
               c.invokevirtual(CD_LCF, "pushVarargs", MethodTypeDesc.of(CD_void, CD_int, CD_int));
               if (b != 0) {
                  fill(this.liveOut[pc], a, a + b - 2);
               }
               break;
            default:
               throw new IllegalStateException("opcode " + o);
         }
      }

      /** Pushes the double of an RK operand known to be a number (a Double constant or a register checked to hold a Double). */
      void loadNum(int rk, int tmp) {
         Object k = constant(rk);
         if (k instanceof Double d) {
            this.cb.loadConstant(d.doubleValue());
         } else {
            this.cb.aload(tmp);
            unboxChecked();
         }
      }

      /** Both operands are doubles on the operand stack (and the platform below them for POW): the stock operation. */
      void arithOp(int o) {
         CodeBuilder cb = this.cb;
         switch (o) {
            case ADD:
               cb.dadd();
               break;
            case SUB:
               cb.dsub();
               break;
            case MUL:
               cb.dmul();
               break;
            case DIV:
               cb.ddiv();
               break;
            case MOD: {
               // v2 == 0 ? NaN : v1 - (int)(v1 / v2) * v2
               Label nz = cb.newLabel();
               Label end = cb.newLabel();
               cb.dstore(this.tmpD1);
               cb.dstore(this.tmpD0);
               cb.dload(this.tmpD1);
               cb.dconst_0();
               cb.dcmpl();
               cb.ifne(nz);
               cb.loadConstant(Double.NaN);
               cb.goto_(end);
               cb.labelBinding(nz);
               cb.dload(this.tmpD0);
               cb.dload(this.tmpD0);
               cb.dload(this.tmpD1);
               cb.ddiv();
               cb.d2i();
               cb.i2d();
               cb.dload(this.tmpD1);
               cb.dmul();
               cb.dsub();
               cb.labelBinding(end);
               break;
            }
            case POW:
               cb.invokeinterface(CD_PLATFORM, "pow", MethodTypeDesc.of(CD_dbl, CD_dbl, CD_dbl));
               break;
            default:
               throw new IllegalStateException();
         }
      }

      void arith(int pc, int o, int a, int b, int c) {
         CodeBuilder cb = this.cb;
         if (staticNum(pc, this.cur)) {
            // luaJitNumbers: both operands are numbers the compiler holds unboxed
            if (o == POW) {
               cb.aload(T);
               cb.invokevirtual(CD_KT, "getPlatform", MethodTypeDesc.of(CD_PLATFORM));
            }
            loadNumOperand(b);
            loadNumOperand(c);
            arithOp(o);
            storeDouble(a);
            return;
         }
         Label slow = cb.newLabel();
         Label done = cb.newLabel();
         boolean spec = specArith(pc, this.cur); // luaJitSpecArith: the result stays unboxed
         boolean rb = rawOp(b), rc = rawOp(c); // luaJitMixedNum: held unboxed, boxed only for the slow path
         if (!rb) {
            loadRK(b);
            cb.astore(this.tmpO0);
         }
         if (!rc) {
            loadRK(c);
            cb.astore(this.tmpO1);
         }
         Object kb = constant(b);
         Object kc = constant(c);
         boolean fast = !(isConst(b) && !(kb instanceof Double)) && !(isConst(c) && !(kc instanceof Double));
         if (fast) {
            if (!(kb instanceof Double) && !rb) {
               cb.aload(this.tmpO0);
               cb.instanceOf(CD_D);
               cb.ifeq(slow);
            }
            if (!(kc instanceof Double) && !rc) {
               cb.aload(this.tmpO1);
               cb.instanceOf(CD_D);
               cb.ifeq(slow);
            }
            if (o == POW) {
               cb.aload(T);
               cb.invokevirtual(CD_KT, "getPlatform", MethodTypeDesc.of(CD_PLATFORM));
            }
            if (rb) {
               cb.dload(dslot(b));
            } else {
               loadNum(b, this.tmpO0);
            }
            if (rc) {
               cb.dload(dslot(c));
            } else {
               loadNum(c, this.tmpO1);
            }
            switch (o) {
               case ADD:
                  cb.dadd();
                  break;
               case SUB:
                  cb.dsub();
                  break;
               case MUL:
                  cb.dmul();
                  break;
               case DIV:
                  cb.ddiv();
                  break;
               case MOD: {
                  // v2 == 0 ? NaN : v1 - (int)(v1 / v2) * v2
                  Label nz = cb.newLabel();
                  Label end = cb.newLabel();
                  cb.dstore(this.tmpD1);
                  cb.dstore(this.tmpD0);
                  cb.dload(this.tmpD1);
                  cb.dconst_0();
                  cb.dcmpl();
                  cb.ifne(nz);
                  cb.loadConstant(Double.NaN);
                  cb.goto_(end);
                  cb.labelBinding(nz);
                  cb.dload(this.tmpD0);
                  cb.dload(this.tmpD0);
                  cb.dload(this.tmpD1);
                  cb.ddiv();
                  cb.d2i();
                  cb.i2d();
                  cb.dload(this.tmpD1);
                  cb.dmul();
                  cb.dsub();
                  cb.labelBinding(end);
                  break;
               }
               case POW:
                  cb.invokeinterface(CD_PLATFORM, "pow", MethodTypeDesc.of(CD_dbl, CD_dbl, CD_dbl));
                  break;
               default:
                  throw new IllegalStateException();
            }
            if (spec) {
               storeDouble(a);
            } else {
               toDouble();
               storeReg(a);
            }
            cb.goto_(done);
         }
         cb.labelBinding(slow);
         boxRawOperands(rb, b, rc, c);
         setPc(pc, true);
         cb.aload(T);
         cb.aload(F);
         cb.aload(this.tmpO0);
         cb.aload(this.tmpO1);
         cb.loadConstant(o);
         cb.invokevirtual(CD_KT, "pzoptJitArith", MethodTypeDesc.of(CD_OBJ, CD_LCF, CD_OBJ, CD_OBJ, CD_int));
         if (spec) {
            cb.astore(this.tmpO2);
            checkReset();
            Label fail = specExit(pc, a);
            cb.aload(this.tmpO2);
            cb.instanceOf(CD_D);
            cb.ifeq(fail);
            cb.aload(this.tmpO2);
            cb.checkcast(CD_D);
            cb.invokevirtual(CD_D, "doubleValue", MethodTypeDesc.of(CD_dbl));
            cb.dup2();
            cb.invokestatic(CD_D, "doubleToRawLongBits", MethodTypeDesc.of(ConstantDescs.CD_long, CD_dbl));
            cb.loadConstant(Long.MIN_VALUE); // -0.0: kept as the object it is (unboxed, it would come back as +0.0)
            cb.lcmp();
            Label notNeg0 = cb.newLabel();
            cb.ifne(notNeg0);
            cb.pop2();
            cb.goto_(fail);
            cb.labelBinding(notNeg0);
            cb.dstore(dslot(a));
         } else {
            storeReg(a);
            checkReset();
         }
         cb.labelBinding(done);
         this.lastPcLine = Integer.MIN_VALUE;
      }

      /** luaJitMixedNum: the unboxed operands boxed into tmpO0 / tmpO1 for the slow path (as stock's box of them). */
      void boxRawOperands(boolean rb, int b, boolean rc, int c) {
         if (rb) {
            this.cb.dload(dslot(b));
            toDouble();
            this.cb.astore(this.tmpO0);
         }
         if (rc) {
            this.cb.dload(dslot(c));
            toDouble();
            this.cb.astore(this.tmpO1);
         }
      }

      void compare(int pc, int o, int a, int b, int c) {
         CodeBuilder cb = this.cb;
         // result true: skip the next instruction when (a == 0), i.e. go to pc + 2
         Label onTrue = a == 0 ? to(pc + 2) : to(pc + 1);
         Label onFalse = a == 0 ? to(pc + 1) : to(pc + 2);
         if (staticNum(pc, this.cur)) {
            // luaJitNumbers: two numbers held unboxed, the stock number comparison
            loadNumOperand(b);
            loadNumOperand(c);
            if (o == EQ) {
               cb.dcmpl();
               cb.ifeq(onTrue);
            } else if (o == LT) {
               cb.dcmpg();
               cb.iflt(onTrue);
            } else {
               cb.dcmpg();
               cb.ifle(onTrue);
            }
            cb.goto_(onFalse);
            return;
         }
         Label notNum = cb.newLabel();
         Label generic = cb.newLabel();
         boolean rb = rawOp(b), rc = rawOp(c); // luaJitMixedNum
         if (!rb) {
            loadRK(b);
            cb.astore(this.tmpO0);
         }
         if (!rc) {
            loadRK(c);
            cb.astore(this.tmpO1);
         }
         Object kb = constant(b);
         Object kc = constant(c);
         boolean numPossible = !(isConst(b) && !(kb instanceof Double)) && !(isConst(c) && !(kc instanceof Double));
         boolean strPossible = !(isConst(b) && !(kb instanceof String)) && !(isConst(c) && !(kc instanceof String)) && !rb && !rc;
         if (numPossible) {
            if (!(kb instanceof Double) && !rb) {
               cb.aload(this.tmpO0);
               cb.instanceOf(CD_D);
               cb.ifeq(notNum);
            }
            if (!(kc instanceof Double) && !rc) {
               cb.aload(this.tmpO1);
               cb.instanceOf(CD_D);
               cb.ifeq(notNum);
            }
            if (rb) {
               cb.dload(dslot(b));
            } else {
               loadNum(b, this.tmpO0);
            }
            if (rc) {
               cb.dload(dslot(c));
            } else {
               loadNum(c, this.tmpO1);
            }
            if (o == EQ) {
               cb.dcmpl();
               cb.ifeq(onTrue);
            } else if (o == LT) {
               cb.dcmpg();
               cb.iflt(onTrue);
            } else {
               cb.dcmpg();
               cb.ifle(onTrue);
            }
            cb.goto_(onFalse);
         }
         cb.labelBinding(notNum);
         if (strPossible) {
            if (!(kb instanceof String)) {
               cb.aload(this.tmpO0);
               cb.instanceOf(CD_S);
               cb.ifeq(generic);
            }
            if (!(kc instanceof String)) {
               cb.aload(this.tmpO1);
               cb.instanceOf(CD_S);
               cb.ifeq(generic);
            }
            if (o == EQ) {
               cb.aload(this.tmpO0);
               cb.aload(this.tmpO1);
               cb.invokevirtual(CD_OBJ, "equals", MethodTypeDesc.of(CD_bool, CD_OBJ));
               cb.ifne(onTrue);
            } else {
               cb.aload(this.tmpO0);
               cb.checkcast(CD_S);
               cb.aload(this.tmpO1);
               cb.checkcast(CD_S);
               cb.invokevirtual(CD_S, "compareTo", MethodTypeDesc.of(CD_int, CD_S));
               if (o == LT) {
                  cb.iflt(onTrue);
               } else {
                  cb.ifle(onTrue);
               }
            }
            cb.goto_(onFalse);
         }
         cb.labelBinding(generic);
         boxRawOperands(rb, b, rc, c);
         setPc(pc, true);
         cb.aload(T);
         cb.aload(F);
         cb.aload(this.tmpO0);
         cb.aload(this.tmpO1);
         cb.loadConstant(o);
         cb.invokevirtual(CD_KT, "pzoptJitCompare", MethodTypeDesc.of(CD_bool, CD_LCF, CD_OBJ, CD_OBJ, CD_int));
         cb.istore(this.tmpI0);
         checkReset();
         cb.iload(this.tmpI0);
         cb.ifne(onTrue);
         cb.goto_(onFalse);
      }

      /**
       * luaJitDirectArgs: CALL with B-1 fixed arguments and C-1 fixed results: the function and arguments go to the site as
       * JVM values (pzopt.LuaJitSites.bootstrapArgs). A result other than LuaJitSites.STACK is the callee's first result
       * (an exposed Java method called without the Lua stack); STACK = the call went the stack way, status in
       * KahluaThread.pzoptJitSt, results on the Lua stack as after the plain compiled call.
       */
      void callArgs(int pc, int a, int b, int c) {
         CodeBuilder cb = this.cb;
         setPc(pc, true);
         int k = b - 1;
         cb.aload(T);
         cb.aload(F);
         loadReg(a);
         cb.loadConstant(a);
         cb.iconst_1();
         int dmask = 0, nmask = 0;
         for (int i = 1; i <= k; i++) {
            if (TYPED_ARGS && this.nums && this.cur != null && isLocal(a + i) && this.cur[a + i] == S_RAW) {
               cb.dload(dslot(a + i)); // luaJitTypedArgs: the number unboxed (objUses left it raw)
               dmask |= 1 << i - 1;
            } else if (TYPED_ARGS && this.nums && this.cur != null && isLocal(a + i) && this.cur[a + i] == S_NUM) {
               loadReg(a + i);
               cb.checkcast(CD_D); // a boxed number (its object, as stock passes it)
               nmask |= 1 << i - 1;
            } else {
               loadReg(a + i);
            }
         }
         cb.invokedynamic(siteArgs(k, dmask, nmask));
         cb.dup();
         cb.astore(this.tmpO0);
         cb.getstatic(CD_SITES, "STACK", CD_OBJ);
         Label stack = cb.newLabel(), join = cb.newLabel();
         cb.if_acmpeq(stack);
         int nres = c - 1;
         if (nres > 0) {
            cb.aload(this.tmpO0);
            storeReg(a);
            for (int r = a + 1; r < a + nres; r++) {
               cb.aconst_null();
               storeReg(r);
            }
         }
         cb.goto_(join);
         cb.labelBinding(stack);
         cb.aload(T);
         cb.getfield(CD_KT, "pzoptJitSt", CD_int);
         cb.dup();
         cb.ifne(deoptExit(this.liveOut[pc], a));
         cb.pop();
         fill(this.liveOut[pc], a, this.max - 1);
         cb.labelBinding(join);
         this.lastPcLine = Integer.MIN_VALUE;
         setPcLine(pc);
      }

      void call(int pc, int a, int b, int c) {
         if (argsCall(b, c)) {
            callArgs(pc, a, b, c);
            return;
         }
         CodeBuilder cb = this.cb;
         setPc(pc, true);
         int ms = b != 0 ? -1 : this.mstart[pc];
         if (b != 0) {
            spill(null, a, a + b - 1);
            cb.aload(F);
            cb.loadConstant(a + b);
            cb.invokevirtual(CD_LCF, "setTop", MT_I_V);
         } else {
            spill(null, a, ms - 1);
            cb.aload(F);
            cb.invokevirtual(CD_LCF, "getTop", MT_I);
            cb.loadConstant(a + 1);
            cb.isub();
            cb.istore(this.tmpI1);
         }
         if (INDY) {
            // an invokedynamic site with its own inline cache of callees (pzopt.LuaJitSites)
            cb.aload(T);
            cb.aload(F);
            loadReg(a);
            cb.loadConstant(a);
            nargs(b);
            cb.loadConstant(c != 0 ? 1 : 0);
            cb.invokedynamic(SITE_CALL);
         } else if (this.direct) {
            // a compiled Lua callee is entered from this site's own invokevirtual of run (its own type profile, so HotSpot
            // can inline a callee that this site always calls); anything else takes pzoptJitCall
            Label slow = cb.newLabel(), join = cb.newLabel();
            loadReg(a);
            cb.astore(this.tmpO0);
            cb.aload(this.tmpO0);
            cb.instanceOf(CD_LC);
            cb.ifeq(slow);
            cb.aload(this.tmpO0);
            cb.checkcast(CD_LC);
            cb.getfield(CD_LC, "prototype", CD_PROTO);
            cb.getfield(CD_PROTO, "pzoptJit", CD_CODE);
            cb.astore(this.tmpO1);
            cb.aload(this.tmpO1);
            cb.ifnull(slow);
            cb.aload(T);
            cb.aload(F);
            cb.aload(this.tmpO0);
            cb.checkcast(CD_LC);
            cb.loadConstant(a);
            nargs(b);
            cb.loadConstant(c != 0 ? 1 : 0);
            cb.invokevirtual(CD_KT, "pzoptJitPushLua", MethodTypeDesc.of(CD_LCF, CD_LCF, CD_LC, CD_int, CD_int, CD_bool));
            cb.astore(this.tmpO2);
            cb.aload(this.tmpO2);
            cb.ifnull(slow);
            cb.aload(T);
            cb.aload(F);
            cb.aload(this.tmpO2);
            cb.aload(this.tmpO1);
            cb.aload(T);
            cb.aload(this.tmpO2);
            cb.invokevirtual(CD_CODE, "run", MT_RUN);
            cb.loadConstant(c != 0 ? 1 : 0);
            cb.invokevirtual(CD_KT, "pzoptJitAfterLua", MethodTypeDesc.of(CD_int, CD_LCF, CD_LCF, CD_int, CD_bool));
            cb.goto_(join);
            cb.labelBinding(slow);
            if (JAVA_FAST) {
               // an exposed Java method with a generated call (luaJavaFast): its call from this site's own invokevirtual
               Label slow2 = cb.newLabel();
               cb.aload(this.tmpO0);
               cb.instanceOf(CD_LJI);
               cb.ifeq(slow2);
               cb.aload(this.tmpO0);
               cb.checkcast(CD_LJI);
               cb.invokevirtual(CD_LJI, "pzoptFast", MethodTypeDesc.of(CD_LJF));
               cb.astore(this.tmpO1);
               cb.aload(this.tmpO1);
               cb.ifnull(slow2);
               cb.aload(CO);
               cb.invokevirtual(CD_CO, "getCallframeTop", MT_I);
               cb.istore(this.tmpI2);
               cb.aload(T);
               cb.aload(F);
               cb.aload(this.tmpO0);
               cb.checkcast(CD_LJI);
               cb.loadConstant(a);
               nargs(b);
               cb.loadConstant(c != 0 ? 1 : 0);
               cb.invokevirtual(CD_KT, "pzoptJitPushJava", MethodTypeDesc.of(CD_LCF, CD_LCF, CD_LJI, CD_int, CD_int, CD_bool));
               cb.astore(this.tmpO2);
               cb.aload(this.tmpO1);
               cb.aload(this.tmpO2);
               nargs(b);
               cb.invokevirtual(CD_LJF, "call", MethodTypeDesc.of(CD_int, CD_LCF, CD_int));
               cb.istore(this.tmpI0);
               cb.aload(T);
               cb.aload(F);
               cb.aload(this.tmpO2);
               cb.aload(this.tmpO0);
               cb.checkcast(CD_LJI);
               cb.iload(this.tmpI0);
               nargs(b);
               cb.iload(this.tmpI2);
               cb.loadConstant(c != 0 ? 1 : 0);
               cb.invokevirtual(CD_KT, "pzoptJitAfterJava", MethodTypeDesc.of(CD_int, CD_LCF, CD_LCF, CD_LJI, CD_int, CD_int, CD_int, CD_bool));
               cb.goto_(join);
               cb.labelBinding(slow2);
            }
            cb.aload(T);
            cb.aload(F);
            cb.aload(this.tmpO0);
            cb.loadConstant(a);
            nargs(b);
            cb.loadConstant(c != 0 ? 1 : 0);
            cb.invokevirtual(CD_KT, "pzoptJitCall", MethodTypeDesc.of(CD_int, CD_LCF, CD_OBJ, CD_int, CD_int, CD_bool));
            cb.labelBinding(join);
         } else {
            cb.aload(T);
            cb.aload(F);
            loadReg(a);
            cb.loadConstant(a);
            nargs(b);
            cb.loadConstant(c != 0 ? 1 : 0);
            cb.invokevirtual(CD_KT, "pzoptJitCall", MethodTypeDesc.of(CD_int, CD_LCF, CD_OBJ, CD_int, CD_int, CD_bool));
         }
         cb.dup();
         cb.ifne(deoptExit(this.liveOut[pc], a));
         cb.pop();
         fill(this.liveOut[pc], a, this.max - 1);
         this.lastPcLine = Integer.MIN_VALUE;
         setPcLine(pc);
      }

      /** After a call the frame's pc is the call's own; later instructions on its line need not store it again. */
      void setPcLine(int pc) {
         if (p.lines != null && pc < p.lines.length) {
            this.lastPcLine = p.lines[pc];
         }
      }

      void nargs(int b) {
         if (b != 0) {
            this.cb.loadConstant(b - 1);
         } else {
            this.cb.iload(this.tmpI1);
         }
      }

      void ret(int pc, int a, int b) {
         CodeBuilder cb = this.cb;
         setPc(pc, false);
         if (!(FRAME_LITE && noClosure())) {
            cb.aload(CO);
            cb.iload(BASE);
            cb.invokevirtual(CD_CO, "closeUpvalues", MT_I_V);
         }
         if (b != 0) {
            int nres = b - 1;
            if (nres > 0) {
               cb.aload(F);
               cb.getfield(CD_LCF, "returnBase", CD_int);
               cb.istore(this.tmpI0);
               for (int k = 0; k < nres; k++) {
                  loadStack();
                  cb.iload(this.tmpI0);
                  if (k != 0) {
                     cb.loadConstant(k);
                     cb.iadd();
                  }
                  loadReg(a + k);
                  cb.aastore();
               }
            }
            cb.aload(CO);
            cb.aload(F);
            cb.getfield(CD_LCF, "returnBase", CD_int);
            if (nres != 0) {
               cb.loadConstant(nres);
               cb.iadd();
            }
            cb.invokevirtual(CD_CO, "setTop", MT_I_V);
         } else {
            spill(null, a, this.mstart[pc] - 1);
            // b = top - a
            cb.aload(F);
            cb.invokevirtual(CD_LCF, "getTop", MT_I);
            cb.loadConstant(a);
            cb.isub();
            cb.istore(this.tmpI1);
            cb.aload(CO);
            index(a);
            cb.aload(F);
            cb.getfield(CD_LCF, "returnBase", CD_int);
            cb.iload(this.tmpI1);
            cb.invokevirtual(CD_CO, "stackCopy", MethodTypeDesc.of(CD_void, CD_int, CD_int, CD_int));
            cb.aload(CO);
            cb.aload(F);
            cb.getfield(CD_LCF, "returnBase", CD_int);
            cb.iload(this.tmpI1);
            cb.iadd();
            cb.invokevirtual(CD_CO, "setTop", MT_I_V);
         }
         cb.aload(CO);
         cb.invokevirtual(CD_CO, "popCallFrame", MT_V);
         cb.loadConstant(LuaJit.ST_RETURNED);
         cb.ireturn();
      }

      void forloop(int pc, int a, int sbx) {
         CodeBuilder cb = this.cb;
         Label neg = cb.newLabel();
         Label cont = cb.newLabel();
         Label exit = cb.newLabel();
         setPc(pc, false);
         loadDouble(a);
         cb.dstore(this.tmpD0);
         loadDouble(a + 1);
         cb.dstore(this.tmpD1);
         loadDouble(a + 2);
         cb.dstore(this.tmpD2);
         cb.dload(this.tmpD0);
         cb.dload(this.tmpD2);
         cb.dadd();
         cb.dup2();
         cb.dstore(this.tmpD0);
         boolean raw = this.nums && isLocal(a);
         if (raw) {
            // luaJitNumbers: the index stays a double
            cb.dconst_0();
            cb.dadd();
            cb.dstore(dslot(a));
         } else {
            toDouble();
            cb.astore(this.tmpO0);
            cb.aload(this.tmpO0);
            storeReg(a);
         }
         if (this.nums) {
            // the limit and step keep their unboxed values for the next round (edgeOut: NUM on the back edge), also when
            // the index register is not a JVM local (a closure captures it elsewhere)
            for (int r = a + 1; r <= a + 2; r++) {
               if (isLocal(r) && this.cur[r] == S_OBJ) {
                  cb.dload(r == a + 1 ? this.tmpD1 : this.tmpD2);
                  cb.dstore(dslot(r));
               }
            }
         }
         cb.dload(this.tmpD2);
         cb.dconst_0();
         cb.dcmpl();
         cb.ifle(neg);
         // step > 0: exit when !(iter <= end)
         cb.dload(this.tmpD0);
         cb.dload(this.tmpD1);
         cb.dcmpg();
         cb.ifgt(exit);
         cb.goto_(cont);
         cb.labelBinding(neg);
         // exit when !(iter >= end)
         cb.dload(this.tmpD0);
         cb.dload(this.tmpD1);
         cb.dcmpl();
         cb.iflt(exit);
         cb.labelBinding(cont);
         if (raw && isLocal(a + 3)) {
            cb.dload(dslot(a));
            cb.dstore(dslot(a + 3));
         } else {
            if (raw) {
               cb.dload(dslot(a));
               toDouble();
            } else {
               cb.aload(this.tmpO0);
            }
            storeReg(a + 3);
            if (!raw && this.nums && isLocal(a + 3)) {
               // edgeOut: R(A+3) is RAW on the back edge, its double slot must hold it too (the index is on the Lua stack)
               cb.dload(this.tmpD0);
               cb.dconst_0();
               cb.dadd();
               cb.dstore(dslot(a + 3));
            }
         }
         cb.goto_(to(pc + 1 + sbx));
         cb.labelBinding(exit);
         cb.aload(F);
         cb.loadConstant(a);
         cb.invokevirtual(CD_LCF, "clearFromIndex", MT_I_V);
         nullLocals(this.liveIn[pc + 1], a);
      }

      void tforloop(int pc, int a, int c) {
         CodeBuilder cb = this.cb;
         setPc(pc, true);
         spill(null, a, a + 2);
         cb.aload(F);
         cb.loadConstant(a + 6);
         cb.invokevirtual(CD_LCF, "setTop", MT_I_V);
         cb.aload(F);
         cb.loadConstant(a);
         cb.loadConstant(a + 3);
         cb.loadConstant(3);
         cb.invokevirtual(CD_LCF, "stackCopy", MethodTypeDesc.of(CD_void, CD_int, CD_int, CD_int));
         cb.aload(T);
         cb.iconst_2();
         cb.invokevirtual(CD_KT, "call", MethodTypeDesc.of(CD_int, CD_int));
         cb.pop();
         cb.aload(F);
         cb.loadConstant(a + 3 + c);
         cb.invokevirtual(CD_LCF, "clearFromIndex", MT_I_V);
         cb.aload(F);
         cb.invokevirtual(CD_LCF, "setPrototypeStacksize", MT_V);
         checkReset();
         Label exit = cb.newLabel();
         loadStack();
         index(a + 3);
         cb.aaload();
         cb.astore(this.tmpO0);
         cb.aload(this.tmpO0);
         cb.ifnull(exit);
         cb.aload(this.tmpO0);
         storeReg(a + 2);
         fill(this.liveIn[pc + 1], a + 3, this.max - 1);
         cb.goto_(to(pc + 1));
         cb.labelBinding(exit);
         fill(this.liveIn[pc + 2], a + 3, this.max - 1);
         cb.goto_(to(pc + 2));
      }

      void setlist(int pc, int a, int b, int c) {
         CodeBuilder cb = this.cb;
         int cv = c != 0 ? c : this.code[pc + 1];
         int offset = (cv - 1) * FIELDS_PER_FLUSH;
         loadReg(a);
         cb.checkcast(CD_TABLE);
         cb.astore(this.tmpO0);
         if (b != 0) {
            for (int i = 1; i <= b; i++) {
               cb.aload(this.tmpO0);
               cb.loadConstant((long)(offset + i));
               cb.invokestatic(CD_KU, "toDouble", MT_J_DBL);
               loadReg(a + i);
               cb.invokeinterface(CD_TABLE, "rawset", MethodTypeDesc.of(CD_void, CD_OBJ, CD_OBJ));
            }
         } else {
            spill(null, a + 1, this.mstart[pc] - 1);
            // n = top - a - 1; for i in 1..n: t.rawset(toDouble(offset + i), get(a + i))
            cb.aload(F);
            cb.invokevirtual(CD_LCF, "getTop", MT_I);
            cb.loadConstant(a + 1);
            cb.isub();
            cb.istore(this.tmpI1);
            cb.iconst_1();
            cb.istore(this.tmpI0);
            Label head = cb.newLabel();
            Label end = cb.newLabel();
            cb.labelBinding(head);
            cb.iload(this.tmpI0);
            cb.iload(this.tmpI1);
            cb.if_icmpgt(end);
            cb.aload(this.tmpO0);
            cb.loadConstant(offset);
            cb.iload(this.tmpI0);
            cb.iadd();
            cb.i2l();
            cb.invokestatic(CD_KU, "toDouble", MT_J_DBL);
            cb.aload(F);
            cb.loadConstant(a);
            cb.iload(this.tmpI0);
            cb.iadd();
            cb.invokevirtual(CD_LCF, "get", MethodTypeDesc.of(CD_OBJ, CD_int));
            cb.invokeinterface(CD_TABLE, "rawset", MethodTypeDesc.of(CD_void, CD_OBJ, CD_OBJ));
            cb.iinc(this.tmpI0, 1);
            cb.goto_(head);
            cb.labelBinding(end);
         }
         if (c == 0) {
            cb.goto_(to(pc + 2));
         }
      }

      void closure(int pc, int a, int bx) {
         CodeBuilder cb = this.cb;
         Prototype np = p.prototypes[bx];
         cb.new_(CD_LC);
         cb.dup();
         loadData(np, CD_PROTO);
         cb.aload(CL);
         cb.getfield(CD_LC, "env", CD_TABLE);
         cb.invokespecial(CD_LC, ConstantDescs.INIT_NAME, MethodTypeDesc.of(CD_void, CD_PROTO, CD_TABLE));
         cb.astore(this.tmpO0);
         cb.aload(this.tmpO0);
         storeReg(a);
         int nup = np.numUpvalues;
         for (int j = 0; j < nup; j++) {
            int u = this.code[pc + 1 + j];
            int uo = u & 63;
            int ub = opB(u);
            if (uo == MOVE) {
               cb.aload(this.tmpO0);
               cb.getfield(CD_LC, "upvalues", CD_UVS);
               cb.loadConstant(j);
               cb.aload(F);
               cb.loadConstant(ub);
               cb.invokevirtual(CD_LCF, "findUpvalue", MethodTypeDesc.of(CD_UV, CD_int));
               cb.aastore();
            } else if (uo == GETUPVAL) {
               cb.aload(this.tmpO0);
               cb.getfield(CD_LC, "upvalues", CD_UVS);
               cb.loadConstant(j);
               cb.aload(CL);
               cb.getfield(CD_LC, "upvalues", CD_UVS);
               cb.loadConstant(ub);
               cb.aaload();
               cb.aastore();
            }
         }
         if (nup > 0) {
            cb.goto_(to(pc + 1 + nup));
         }
      }
   }
}
