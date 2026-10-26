package se.krka.kahlua.vm;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.stdlib.BaseLib;
import zombie.Lua.LuaManager;
import zombie.core.Core;
import zombie.core.logger.ExceptionLogger;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.debug.StackTraceContainer;
import zombie.network.GameServer;
import zombie.ui.UIManager;

public class KahluaThread {
   private static final int FIELDS_PER_FLUSH = 50;
   private static final int OP_MOVE = 0;
   private static final int OP_LOADK = 1;
   private static final int OP_LOADBOOL = 2;
   private static final int OP_LOADNIL = 3;
   private static final int OP_GETUPVAL = 4;
   private static final int OP_GETGLOBAL = 5;
   private static final int OP_GETTABLE = 6;
   private static final int OP_SETGLOBAL = 7;
   private static final int OP_SETUPVAL = 8;
   private static final int OP_SETTABLE = 9;
   private static final int OP_NEWTABLE = 10;
   private static final int OP_SELF = 11;
   private static final int OP_ADD = 12;
   private static final int OP_SUB = 13;
   private static final int OP_MUL = 14;
   private static final int OP_DIV = 15;
   private static final int OP_MOD = 16;
   private static final int OP_POW = 17;
   private static final int OP_UNM = 18;
   private static final int OP_NOT = 19;
   private static final int OP_LEN = 20;
   private static final int OP_CONCAT = 21;
   private static final int OP_JMP = 22;
   private static final int OP_EQ = 23;
   private static final int OP_LT = 24;
   private static final int OP_LE = 25;
   private static final int OP_TEST = 26;
   private static final int OP_TESTSET = 27;
   private static final int OP_CALL = 28;
   private static final int OP_TAILCALL = 29;
   private static final int OP_RETURN = 30;
   private static final int OP_FORLOOP = 31;
   private static final int OP_FORPREP = 32;
   private static final int OP_TFORLOOP = 33;
   private static final int OP_SETLIST = 34;
   private static final int OP_CLOSE = 35;
   private static final int OP_CLOSURE = 36;
   private static final int OP_VARARG = 37;
   private static final int MAX_INDEX_RECURSION = 100;
   private static final String[] meta_ops = new String[38];
   public static LuaCallFrame lastCallFrame;
   private final Coroutine rootCoroutine;
   private final Map<Class<?>, KahluaTable> cachedMetatables = new IdentityHashMap<>();
   public Coroutine currentCoroutine;
   private final boolean doProfiling = false;
   public Thread debugOwnerThread;
   private final PrintStream out;
   private final Platform platform;
   private final StringBuilder concatBuffer = new StringBuilder();
   public boolean step;
   public String currentfile;
   public int currentLine;
   public int lastLine;
   public int lastCallFrameIdx;
   public boolean reset;
   public ArrayList<KahluaThread.Entry> profileEntries = new ArrayList<>();
   public HashMap<String, KahluaThread.Entry> profileEntryMap = new HashMap<>();
   private static final String CLASS_NAME = KahluaThread.class.getName();
   private static final String METHOD_NAME_LUA_MAINLOOP = "luaMainloop";
   public static int errorCount;
   public static final ArrayList<String> m_errors_list = new ArrayList<>();
   private final StringBuilder stringBuilder = new StringBuilder();
   private final StringWriter stringWriter = new StringWriter();
   private final PrintWriter printWriter = new PrintWriter(this.stringWriter);
   HashMap<String, ArrayList<Long>> breakpointMap = new HashMap<>();
   HashMap<KahluaTable, ArrayList<Object>> breakpointDataMap = new HashMap<>();
   HashMap<KahluaTable, ArrayList<Object>> breakpointReadDataMap = new HashMap<>();
   public boolean stepInto;
   /** pzopt: luaJit, compiled Lua activations on the Java stack (pzopt.LuaJit.invoke bounds it). */
   public int pzoptJitDepth;
   /** pzopt: devLuaJitDual, a compiled shadow run is in progress on this thread. */
   public boolean pzoptDualShadow; // pzopt: devLuaJitDual
   /** pzopt: devLuaJitDual, the frame the interpreter runs for real after a shadow (null: none), and what the shadow did. */
   public LuaCallFrame pzoptDualFrame; // pzopt: devLuaJitDual
   public Object pzoptDualRec; // pzopt: devLuaJitDual
   /** pzopt: luaJitDirectArgs, the status of a direct-arguments call that went the stack way (pzopt.LuaJitSites.STACK). */
   public int pzoptJitSt;
   /** pzopt: luaJitSplitRaw, the unboxed numbers a jump between two regions of a split function hands over (pzopt.LuaJit.regionD). */
   public double[] pzoptJitD; // pzopt: luaJitSplitRaw
   /** pzopt: luaJitNested, the exception a nested luaMainloop (an interpreted callee of compiled code) already reported. */
   private Throwable pzoptJitReported;

   public Coroutine getCurrentCoroutine() {
      return this.currentCoroutine;
   }

   public KahluaThread(Platform platform, KahluaTable environment) {
      this(null, platform, environment);
   }

   public KahluaThread(PrintStream stream, Platform platform, KahluaTable environment) {
      this.platform = platform;
      this.out = (PrintStream)(stream != null ? stream : DebugType.Lua.getLogStream());
      this.rootCoroutine = new Coroutine(platform, environment, this);
      this.currentCoroutine = this.rootCoroutine;
   }

   public int call(int nArguments) {
      int top = this.currentCoroutine.getTop();
      int base = top - nArguments - 1;
      Object o = this.currentCoroutine.objectStack[base];
      if (o == null) {
         throw new RuntimeException("tried to call nil");
      }

      try {
         if (o instanceof JavaFunction javaFunction) {
            return this.callJava(javaFunction, base + 1, base, nArguments);
         }
      } catch (Exception ex) {
         throw new RuntimeException(ex.getClass().getName() + " " + ex.getMessage() + " in " + o);
      }

      if (o instanceof LuaClosure luaClosure) {
         LuaCallFrame callFrame = this.currentCoroutine.pushNewCallFrame(luaClosure, null, base + 1, base, nArguments, false, false);
         callFrame.init();
         this.luaMainloop();
         int nReturnValues = this.currentCoroutine.getTop() - base;
         this.currentCoroutine.stackTrace = "";
         return nReturnValues;
      } else {
         throw new RuntimeException("tried to call a non-function");
      }
   }

   private int callJava(JavaFunction f, int localBase, int returnBase, int nArguments) {
      if (pzopt.LuaJitDual.ON && (this.pzoptDualShadow || this.pzoptDualFrame != null)) { // pzopt: devLuaJitDual
         pzopt.LuaJitDual.java(this, f); // pzopt: devLuaJitDual
      } // pzopt: devLuaJitDual
      Coroutine coroutine = this.currentCoroutine;
      LuaCallFrame callFrame = coroutine.pushNewCallFrame(null, f, localBase, returnBase, nArguments, false, false);
      int nReturnValues = f.call(callFrame, nArguments);
      int top = callFrame.getTop();
      int actualReturnBase = top - nReturnValues;
      int diff = returnBase - localBase;
      callFrame.stackCopy(actualReturnBase, diff, nReturnValues);
      callFrame.setTop(nReturnValues + diff);
      coroutine.popCallFrame();
      return nReturnValues;
   }

   private final Object prepareMetatableCall(Object o) {
      return !(o instanceof JavaFunction) && !(o instanceof LuaClosure) ? this.getMetaOp(o, "__call") : o;
   }

   public boolean isCurrent(String file, int line) {
      return line == this.currentLine;
   }

   public static boolean isLuaMainloop(StackTraceElement stackTraceElement) {
      return stackTraceElement != null && CLASS_NAME.equals(stackTraceElement.getClassName()) && "luaMainloop".equals(stackTraceElement.getMethodName());
   }

   private final void luaMainloop() {
      LuaCallFrame callFrame = this.currentCoroutine.currentCallFrame();
      LuaClosure closure = callFrame.closure;
      Prototype prototype = closure.prototype;
      int[] opcodes = prototype.code;
      int returnBase = callFrame.returnBase;
      String file = "";
      long start = 0L;
      boolean bStepWorthy = true;
      boolean pzoptEnter = pzopt.LuaJit.ON; // pzopt: luaJit, the frame just entered may run its compiled code

      while (!this.reset) {
         if (Core.debug && this == LuaManager.thread) {
            Coroutine c = this.getCurrentCoroutine();
            if (c != null) {
               this.lastLine = this.currentLine;
               LuaCallFrame f = c.currentCallFrame();
               if (f.closure != null) {
                  this.currentfile = f.closure.prototype.filename;
                  this.currentLine = f.closure.prototype.lines[f.pc];
                  if (this.step && this.currentLine != this.lastLine) {
                     if (this.stepInto) {
                        this.step = false;
                        UIManager.debugBreakpoint(f.closure.prototype.filename, this.currentLine - 1L);
                        this.lastCallFrameIdx = c.getCallframeTop();
                        bStepWorthy = true;
                     } else if (c.getCallframeTop() <= this.lastCallFrameIdx) {
                        this.step = false;
                        this.lastCallFrameIdx = c.getCallframeTop();
                        UIManager.debugBreakpoint(f.closure.prototype.filename, this.currentLine - 1L);
                        bStepWorthy = true;
                     }
                  }

                  if (this.breakpointMap.containsKey(f.closure.prototype.filename)) {
                     ArrayList<Long> i = this.breakpointMap.get(f.closure.prototype.filename);
                     if (i.contains((long)f.closure.prototype.lines[f.pc])
                        && (f.pc == 0 || f.closure.prototype.lines[f.pc - 1] != f.closure.prototype.lines[f.pc])) {
                        UIManager.debugBreakpoint(f.closure.prototype.filename, f.closure.prototype.lines[f.pc]);
                     }
                  }
               }
            }
         }

         bStepWorthy = true;

         try {
            pzoptOp: { // pzopt: luaJit, a compiled run takes the place of this iteration's instruction
            if (pzoptEnter) { // pzopt: luaJit
               pzoptEnter = false; // pzopt: luaJit
               int pzoptSt = pzopt.LuaJit.enter(this, callFrame, prototype); // pzopt: luaJit, -1 = interpret; else how the compiled run ended
               if (pzoptSt >= 0) { // pzopt: luaJit
                  if (pzoptSt == 5) { // pzopt: luaJit, lua was reset during it: the check after the try throws
                     break pzoptOp; // pzopt: luaJit
                  } // pzopt: luaJit
                  if (pzoptSt == 0 && !callFrame.fromLua) { // pzopt: luaJit, returned to Java (OP_RETURN's !fromLua)
                     return; // pzopt: luaJit
                  } // pzopt: luaJit
                  callFrame = this.currentCoroutine.currentCallFrame(); // pzopt: luaJit
                  if (pzoptSt == 2 && (callFrame == null || callFrame.isJava())) { // pzopt: luaJit, as OP_CALL after a Java call
                     return; // pzopt: luaJit
                  } // pzopt: luaJit
                  if (pzoptSt == 3 && callFrame.isJava()) { // pzopt: luaJit, as OP_TAILCALL after a Java call that switched coroutine
                     return; // pzopt: luaJit
                  } // pzopt: luaJit
                  closure = callFrame.closure; // pzopt: luaJit
                  prototype = closure.prototype; // pzopt: luaJit
                  opcodes = prototype.code; // pzopt: luaJit
                  returnBase = callFrame.returnBase; // pzopt: luaJit
                  if ((pzoptSt == 0 || pzoptSt == 2) && callFrame.restoreTop) { // pzopt: luaJit, the caller's restoreTop (OP_RETURN / OP_CALL)
                     callFrame.setTop(prototype.maxStacksize); // pzopt: luaJit
                  } // pzopt: luaJit
                  pzoptEnter = pzoptSt == 1 && callFrame.pc == 0; // pzopt: luaJit, a callee left to the interpreter may have code of its own
                  break pzoptOp; // pzopt: luaJit
               } // pzopt: luaJit
            } // pzopt: luaJit
            if (this.step) {
               boolean var106 = false;
            }

            int op = opcodes[callFrame.pc++];
            int opcode = op & 63;
            switch (opcode) {
               case 0: {
                  int a = getA8(op);
                  int b = getB9(op);
                  callFrame.set(a, callFrame.get(b));
                  bStepWorthy = false;
                  break;
               }
               case 1: {
                  int a = getA8(op);
                  int b = getBx(op);
                  if (Core.debug && this == LuaManager.thread) {
                     this.setLocalVarToStack(callFrame, a);
                  }

                  callFrame.set(a, prototype.constants[b]);
                  break;
               }
               case 2: {
                  int a = getA8(op);
                  int b = getB9(op);
                  int c = getC9(op);
                  Boolean bool = b == 0 ? Boolean.FALSE : Boolean.TRUE;
                  if (Core.debug && this == LuaManager.thread) {
                     this.setLocalVarToStack(callFrame, a);
                  }

                  callFrame.set(a, bool);
                  if (c != 0) {
                     callFrame.pc++;
                  }
                  break;
               }
               case 3: {
                  int a = getA8(op);
                  int b = getB9(op);
                  if (Core.debug && this == LuaManager.thread) {
                     this.setLocalVarToStack(callFrame, a);
                  }

                  callFrame.stackClear(a, b);
                  break;
               }
               case 4: {
                  int a = getA8(op);
                  int b = getB9(op);
                  UpValue uv = closure.upvalues[b];
                  if (Core.debug && this == LuaManager.thread) {
                     this.setLocalVarToStack(callFrame, a);
                  }

                  callFrame.set(a, uv.getValue());
                  break;
               }
               case 5: {
                  int a = getA8(op);
                  int b = getBx(op);
                  Object res = this.tableget(closure.env, prototype.constants[b]);
                  if (Core.debug && this == LuaManager.thread) {
                     this.setLocalVarToStack(callFrame, a);
                  }

                  callFrame.set(a, res);
                  break;
               }
               case 6: {
                  int a = getA8(op);
                  int b = getB9(op);
                  int c = getC9(op);
                  Object bObj = callFrame.get(b);
                  Object key = this.getRegisterOrConstant(callFrame, c, prototype);
                  Object res = this.tableget(bObj, key);
                  if (Core.debug && this == LuaManager.thread) {
                     this.setLocalVarToStack(callFrame, a);
                  }

                  callFrame.set(a, res);
                  break;
               }
               case 7: {
                  int a = getA8(op);
                  int b = getBx(op);
                  Object value = callFrame.get(a);
                  Object key = prototype.constants[b];
                  if (value instanceof LuaClosure luaClosure && key instanceof String) {
                     luaClosure.debugName = key.toString();
                  }

                  if (LuaCompiler.rewriteEvents) {
                     Object o = closure.env.rawget(key);
                     if (o instanceof KahluaTable && o != value) {
                        KahluaTableImpl tab = (KahluaTableImpl)o;
                        tab.setRewriteTable(value);
                     }

                     this.tableSet(closure.env, key, value);
                  } else {
                     this.tableSet(closure.env, key, value);
                  }
                  break;
               }
               case 8: {
                  int a = getA8(op);
                  int b = getB9(op);
                  UpValue uv = closure.upvalues[b];
                  if (Core.debug && this == LuaManager.thread) {
                     this.setLocalVarToStack(callFrame, a);
                  }

                  if (pzopt.LuaJitDual.ON && this.pzoptDualShadow) { // pzopt: devLuaJitDual
                     pzopt.LuaJitDual.upvalue(this); // pzopt: devLuaJitDual
                  } // pzopt: devLuaJitDual
                  uv.setValue(callFrame.get(a));
                  break;
               }
               case 9: {
                  int a = getA8(op);
                  int b = getB9(op);
                  int c = getC9(op);
                  Object aObj = callFrame.get(a);
                  Object key = this.getRegisterOrConstant(callFrame, b, prototype);
                  Object value = this.getRegisterOrConstant(callFrame, c, prototype);
                  this.tableSet(aObj, key, value);
                  break;
               }
               case 10: {
                  int a = getA8(op);
                  KahluaTable t = this.platform.newTable();
                  if (Core.debug && this == LuaManager.thread) {
                     this.setLocalVarToStack(callFrame, a);
                  }

                  callFrame.set(a, t);
                  break;
               }
               case 11: {
                  int a = getA8(op);
                  int b = getB9(op);
                  int c = getC9(op);
                  Object key = this.getRegisterOrConstant(callFrame, c, prototype);
                  Object bObj = callFrame.get(b);
                  lastCallFrame = callFrame;
                  Object fun = this.tableget(bObj, key);
                  callFrame.set(a, fun);
                  callFrame.set(a + 1, bObj);
                  bStepWorthy = false;
                  break;
               }
               case 12:
               case 13:
               case 14:
               case 15:
               case 16:
               case 17: {
                  int a = getA8(op);
                  int b = getB9(op);
                  int c = getC9(op);
                  Object bo = this.getRegisterOrConstant(callFrame, b, prototype);
                  Object co = this.getRegisterOrConstant(callFrame, c, prototype);
                  Double bd = null;
                  Double cd = null;
                  Object res = null;
                  if ((bd = KahluaUtil.rawTonumber(bo)) != null && (cd = KahluaUtil.rawTonumber(co)) != null) {
                     res = this.primitiveMath(bd, cd, opcode);
                  } else {
                     String meta_op = meta_ops[opcode];
                     Object metafun = this.getBinMetaOp(bo, co, meta_op);
                     if (metafun == null) {
                        this.doStacktraceProper(callFrame);
                        String funcName = "unknown";
                        if (closure.debugName != null) {
                           funcName = closure.debugName;
                        } else if (prototype.name != null) {
                           funcName = prototype.name;
                        }

                        KahluaUtil.fail(meta_op + " not defined for operands in " + funcName);
                     }

                     res = this.call(metafun, bo, co, null);
                  }

                  if (Core.debug && this == LuaManager.thread) {
                     this.setLocalVarToStack(callFrame, a);
                  }

                  callFrame.set(a, res);
                  break;
               }
               case 18: {
                  int a = getA8(op);
                  int b = getB9(op);
                  Object aObj = callFrame.get(b);
                  Double aDouble = KahluaUtil.rawTonumber(aObj);
                  Object res;
                  if (aDouble != null) {
                     res = KahluaUtil.toDouble(-KahluaUtil.fromDouble(aDouble));
                  } else {
                     Object metafun = this.getMetaOp(aObj, "__unm");
                     res = this.call(metafun, aObj, null, null);
                  }

                  callFrame.set(a, res);
                  break;
               }
               case 19: {
                  int a = getA8(op);
                  int b = getB9(op);
                  Object aObj = callFrame.get(b);
                  callFrame.set(a, KahluaUtil.toBoolean(!KahluaUtil.boolEval(aObj)));
                  bStepWorthy = false;
                  break;
               }
               case 20: {
                  int a = getA8(op);
                  int b = getB9(op);
                  Object o = callFrame.get(b);
                  Object res;
                  if (o instanceof KahluaTable t) {
                     res = KahluaUtil.toDouble(t.len());
                  } else if (o instanceof String s) {
                     res = KahluaUtil.toDouble(s.length());
                  } else {
                     Object f = this.getMetaOp(o, "__len");
                     if (f == null) {
                        this.doStacktraceProper(callFrame);
                     }

                     KahluaUtil.luaAssert(f != null, "__len not defined for operand");
                     res = this.call(f, o, null, null);
                  }

                  callFrame.set(a, res);
                  bStepWorthy = false;
                  break;
               }
               case 21: {
                  int a = getA8(op);
                  int b = getB9(op);
                  int c = getC9(op);
                  int first = b;
                  int last = c;
                  Object res = callFrame.get(last);
                  last--;

                  while (first <= last) {
                     String resStr = KahluaUtil.rawTostring(res);
                     if (resStr != null) {
                        int nStrings = 0;

                        for (int pos = last; first <= pos; nStrings++) {
                           Object o = callFrame.get(pos);
                           pos--;
                           if (KahluaUtil.rawTostring(o) == null) {
                              break;
                           }
                        }

                        if (nStrings > 0) {
                           StringBuilder concatBuffer = this.concatBuffer;
                           concatBuffer.setLength(0);

                           for (int firstString = last - nStrings + 1; firstString <= last; firstString++) {
                              concatBuffer.append(KahluaUtil.rawTostring(callFrame.get(firstString)));
                           }

                           concatBuffer.append(resStr);
                           res = concatBuffer.toString();
                           last -= nStrings;
                        }
                     }

                     if (first <= last) {
                        Object leftConcat = callFrame.get(last);
                        Object metafun = this.getBinMetaOp(leftConcat, res, "__concat");
                        if (metafun == null) {
                           KahluaUtil.fail("__concat not defined for operands: " + leftConcat + " and " + res);
                        }

                        res = this.call(metafun, leftConcat, res, null);
                        last--;
                     }
                  }

                  callFrame.set(a, res);
                  bStepWorthy = false;
                  break;
               }
               case 22:
                  callFrame.pc = callFrame.pc + getSBx(op);
                  if (pzopt.LuaJit.OSR && getSBx(op) < 0 && pzopt.LuaJit.ON && pzopt.LuaJit.backEdge(this, prototype)) { // pzopt: luaJitOsr, a loop: its compiled code may take over at the header
                     pzoptEnter = true; // pzopt: luaJitOsr
                  } // pzopt: luaJitOsr
                  break;
               case 23:
               case 24:
               case 25: {
                  int a = getA8(op);
                  int b = getB9(op);
                  int c = getC9(op);
                  Object bo = this.getRegisterOrConstant(callFrame, b, prototype);
                  Object co = this.getRegisterOrConstant(callFrame, c, prototype);
                  if (bo instanceof Double && co instanceof Double) {
                     double bd_primitive = KahluaUtil.fromDouble(bo);
                     double cd_primitive = KahluaUtil.fromDouble(co);
                     if (opcode == 23) {
                        if (bd_primitive == cd_primitive == (a == 0)) {
                           callFrame.pc++;
                        }
                     } else if (opcode == 24) {
                        if (bd_primitive < cd_primitive == (a == 0)) {
                           callFrame.pc++;
                        }
                     } else if (bd_primitive <= cd_primitive == (a == 0)) {
                        callFrame.pc++;
                     }
                  } else if (bo instanceof String bs && co instanceof String cs) {
                     if (opcode == 23) {
                        if (bo.equals(co) == (a == 0)) {
                           callFrame.pc++;
                        }
                     } else {
                        int cmp = bs.compareTo(cs);
                        if (opcode == 24) {
                           if (cmp < 0 == (a == 0)) {
                              callFrame.pc++;
                           }
                        } else if (cmp <= 0 == (a == 0)) {
                           callFrame.pc++;
                        }
                     }
                  } else {
                     boolean resBool;
                     if (bo == co && opcode == 23) {
                        resBool = true;
                     } else {
                        boolean invert = false;
                        String meta_op = meta_ops[opcode];
                        Object metafun = this.getCompMetaOp(bo, co, meta_op);
                        if (metafun == null && opcode == 25) {
                           metafun = this.getCompMetaOp(bo, co, "__lt");
                           Object tmp = bo;
                           bo = co;
                           co = tmp;
                           invert = true;
                        }

                        if (metafun == null && opcode == 23) {
                           resBool = BaseLib.luaEquals(bo, co);
                        } else {
                           if (metafun == null) {
                              this.doStacktraceProper(callFrame);
                              KahluaUtil.fail(meta_op + " not defined for operand");
                           }

                           Object res = this.call(metafun, bo, co, null);
                           resBool = KahluaUtil.boolEval(res);
                        }

                        if (invert) {
                           resBool = !resBool;
                        }
                     }

                     if (resBool == (a == 0)) {
                        callFrame.pc++;
                     }
                  }

                  bStepWorthy = false;
                  break;
               }
               case 26: {
                  int a = getA8(op);
                  int c = getC9(op);
                  Object value = callFrame.get(a);
                  if (KahluaUtil.boolEval(value) == (c == 0)) {
                     callFrame.pc++;
                  }
                  break;
               }
               case 27: {
                  int a = getA8(op);
                  int b = getB9(op);
                  int c = getC9(op);
                  Object value = callFrame.get(b);
                  if (KahluaUtil.boolEval(value) != (c == 0)) {
                     callFrame.set(a, value);
                  } else {
                     callFrame.pc++;
                  }
                  break;
               }
               case 28: {
                  int a = getA8(op);
                  int b = getB9(op);
                  int c = getC9(op);
                  int nArguments2 = b - 1;
                  if (nArguments2 != -1) {
                     callFrame.setTop(a + nArguments2 + 1);
                  } else {
                     nArguments2 = callFrame.getTop() - a - 1;
                  }

                  callFrame.restoreTop = c != 0;
                  int base = callFrame.localBase;
                  int localBase2 = base + a + 1;
                  int returnBase2 = base + a;
                  Object funObject = callFrame.get(a);
                  if (funObject == null) {
                     int fdsfds = 0;
                     funObject = callFrame.get(a);
                  }

                  if (funObject == null) {
                     this.doStacktraceProper(callFrame);
                     if (callFrame.getClosure().debugName != null) {
                        KahluaUtil.fail("Object tried to call nil in " + callFrame.getClosure().debugName);
                     } else if (callFrame.getClosure().prototype != null && callFrame.getClosure().prototype.name != null) {
                        KahluaUtil.fail("Object tried to call nil in " + callFrame.getClosure().prototype.name);
                     } else {
                        KahluaUtil.fail("Object tried to call nil in unknown");
                     }
                  }

                  Object fun = this.prepareMetatableCall(funObject);
                  if (fun == null) {
                     KahluaUtil.fail("Object " + funObject + " did not have __call metatable set");
                  }

                  if (fun != funObject) {
                     localBase2 = returnBase2;
                     nArguments2++;
                  }

                  if (fun instanceof LuaClosure luaClosure) {
                     LuaCallFrame newCallFrame = this.currentCoroutine
                        .pushNewCallFrame(luaClosure, null, localBase2, returnBase2, nArguments2, true, callFrame.canYield);
                     newCallFrame.init();
                     callFrame = newCallFrame;
                     closure = newCallFrame.closure;
                     prototype = closure.prototype;
                     opcodes = prototype.code;
                     returnBase = callFrame.returnBase;
                     pzoptEnter = pzopt.LuaJit.ON; // pzopt: luaJit
                     break;
                  } else {
                     if (!(fun instanceof JavaFunction javaFunction)) {
                        throw new RuntimeException("Tried to call a non-function: " + fun);
                     }

                     this.callJava(javaFunction, localBase2, returnBase2, nArguments2);
                     callFrame = this.currentCoroutine.currentCallFrame();
                     if (callFrame != null && !callFrame.isJava()) {
                        closure = callFrame.closure;
                        prototype = closure.prototype;
                        opcodes = prototype.code;
                        returnBase = callFrame.returnBase;
                        if (callFrame.restoreTop) {
                           callFrame.setTop(prototype.maxStacksize);
                        }
                        break;
                     }

                     return;
                  }
               }
               case 29: {
                  int base = callFrame.localBase;
                  this.currentCoroutine.closeUpvalues(base);
                  int a = getA8(op);
                  int b = getB9(op);
                  int nArguments2 = b - 1;
                  if (nArguments2 == -1) {
                     nArguments2 = callFrame.getTop() - a - 1;
                  }

                  callFrame.restoreTop = false;
                  Object funObject = callFrame.get(a);

                  try {
                     KahluaUtil.luaAssert(funObject != null, "Tried to call nil");
                  } catch (Exception ex) {
                     if (Core.debug && UIManager.defaultthread == LuaManager.thread) {
                        UIManager.debugBreakpoint(LuaManager.thread.currentfile, LuaManager.thread.currentLine - 1);
                     }

                     this.debugException(ex);
                     this.doStacktraceProper(callFrame);
                     KahluaUtil.fail("");
                  }

                  Object fun = this.prepareMetatableCall(funObject);
                  if (fun == null) {
                     KahluaUtil.fail("Object did not have __call metatable set");
                  }

                  int localBase2 = returnBase + 1;
                  if (fun != funObject) {
                     localBase2 = returnBase;
                     nArguments2++;
                  }

                  this.currentCoroutine.stackCopy(base + a, returnBase, nArguments2 + 1);
                  this.currentCoroutine.setTop(returnBase + nArguments2 + 1);
                  if (fun instanceof LuaClosure luaClosure) {
                     callFrame.localBase = localBase2;
                     callFrame.nArguments = nArguments2;
                     callFrame.closure = luaClosure;
                     callFrame.init();
                     pzoptEnter = pzopt.LuaJit.ON; // pzopt: luaJit
                  } else {
                     if (!(fun instanceof JavaFunction)) {
                        KahluaUtil.fail("Tried to call a non-function: " + fun);
                     }

                     Coroutine oldCoroutine = this.currentCoroutine;
                     this.callJava((JavaFunction)fun, localBase2, returnBase, nArguments2);
                     if (pzopt.LuaJitDual.ON && callFrame == this.pzoptDualFrame && oldCoroutine == this.currentCoroutine) { // pzopt: devLuaJitDual, the real run of a shadowed frame returned through a Java tail call
                        pzopt.LuaJitDual.realReturned(this, returnBase, oldCoroutine.getTop() - returnBase); // pzopt: devLuaJitDual
                     } // pzopt: devLuaJitDual
                     callFrame = this.currentCoroutine.currentCallFrame();
                     oldCoroutine.popCallFrame();
                     if (oldCoroutine != this.currentCoroutine) {
                        if (oldCoroutine.isDead() && oldCoroutine != this.rootCoroutine && this.currentCoroutine.getParent() == oldCoroutine) {
                           this.currentCoroutine.resume(oldCoroutine.getParent());
                           oldCoroutine.destroy();
                           this.currentCoroutine.getParent().currentCallFrame().push(Boolean.TRUE);
                        }

                        callFrame = this.currentCoroutine.currentCallFrame();
                        if (callFrame.isJava()) {
                           return;
                        }
                     } else {
                        if (!callFrame.fromLua) {
                           return;
                        }

                        callFrame = this.currentCoroutine.currentCallFrame();
                        if (callFrame.restoreTop) {
                           callFrame.setTop(callFrame.closure.prototype.maxStacksize);
                        }
                     }
                  }

                  closure = callFrame.closure;
                  prototype = closure.prototype;
                  opcodes = prototype.code;
                  returnBase = callFrame.returnBase;
                  break;
               }
               case 30: {
                  int a = getA8(op);
                  int b = getB9(op) - 1;
                  int base = callFrame.localBase;
                  this.currentCoroutine.closeUpvalues(base);
                  if (b == -1) {
                     b = callFrame.getTop() - a;
                  }

                  this.currentCoroutine.stackCopy(callFrame.localBase + a, returnBase, b);
                  this.currentCoroutine.setTop(returnBase + b);
                  if (pzopt.LuaJitDual.ON && callFrame == this.pzoptDualFrame) { // pzopt: devLuaJitDual, the real run of a shadowed frame returned
                     pzopt.LuaJitDual.realReturned(this, returnBase, b); // pzopt: devLuaJitDual
                  } // pzopt: devLuaJitDual
                  if (!callFrame.fromLua) {
                     this.currentCoroutine.popCallFrame();
                     return;
                  }

                  if (callFrame.canYield && this.currentCoroutine.atBottom()) {
                     callFrame.localBase = callFrame.returnBase;
                     Coroutine coroutine = this.currentCoroutine;
                     Coroutine.yieldHelper(callFrame, callFrame, b);
                     coroutine.popCallFrame();
                     callFrame = this.currentCoroutine.currentCallFrame();
                     if (callFrame == null || callFrame.isJava()) {
                        return;
                     }
                  } else {
                     this.currentCoroutine.popCallFrame();
                  }

                  callFrame = this.currentCoroutine.currentCallFrame();
                  closure = callFrame.closure;
                  prototype = closure.prototype;
                  opcodes = prototype.code;
                  returnBase = callFrame.returnBase;
                  if (callFrame.restoreTop) {
                     callFrame.setTop(prototype.maxStacksize);
                  }
                  break;
               }
               case 31: {
                  int a = getA8(op);
                  double iter = KahluaUtil.fromDouble(callFrame.get(a));
                  double end = KahluaUtil.fromDouble(callFrame.get(a + 1));
                  double step = KahluaUtil.fromDouble(callFrame.get(a + 2));
                  iter += step;
                  Double iterDouble = KahluaUtil.toDouble(iter);
                  callFrame.set(a, iterDouble);
                  if (step > 0.0 ? !(iter <= end) : !(iter >= end)) {
                     callFrame.clearFromIndex(a);
                  } else {
                     int bx = getSBx(op);
                     callFrame.pc += bx;
                     callFrame.set(a + 3, iterDouble);
                     if (pzopt.LuaJit.OSR && pzopt.LuaJit.ON && pzopt.LuaJit.backEdge(this, prototype)) { // pzopt: luaJitOsr
                        pzoptEnter = true; // pzopt: luaJitOsr
                     } // pzopt: luaJitOsr
                  }
                  break;
               }
               case 32: {
                  int a = getA8(op);
                  int b = getSBx(op);
                  double iter = KahluaUtil.fromDouble(callFrame.get(a));
                  double step = KahluaUtil.fromDouble(callFrame.get(a + 2));
                  callFrame.set(a, KahluaUtil.toDouble(iter - step));
                  callFrame.pc += b;
                  break;
               }
               case 33: {
                  int a = getA8(op);
                  int c = getC9(op);
                  callFrame.setTop(a + 6);
                  callFrame.stackCopy(a, a + 3, 3);
                  this.call(2);
                  callFrame.clearFromIndex(a + 3 + c);
                  callFrame.setPrototypeStacksize();
                  Object aObj3 = callFrame.get(a + 3);
                  if (aObj3 != null) {
                     callFrame.set(a + 2, aObj3);
                  } else {
                     callFrame.pc++;
                  }
                  break;
               }
               case 34: {
                  int a = getA8(op);
                  int b = getB9(op);
                  int c = getC9(op);
                  if (b == 0) {
                     b = callFrame.getTop() - a - 1;
                  }

                  if (c == 0) {
                     c = opcodes[callFrame.pc++];
                  }

                  int offset = (c - 1) * 50;
                  KahluaTable t = (KahluaTable)callFrame.get(a);

                  for (int i = 1; i <= b; i++) {
                     Object keyx = KahluaUtil.toDouble(offset + i);
                     Object valuex = callFrame.get(a + i);
                     t.rawset(keyx, valuex);
                  }
                  break;
               }
               case 35: {
                  int a = getA8(op);
                  callFrame.closeUpvalues(a);
                  break;
               }
               case 36: {
                  int a = getA8(op);
                  int b = getBx(op);
                  Prototype newPrototype = prototype.prototypes[b];
                  LuaClosure newClosure = new LuaClosure(newPrototype, closure.env);
                  callFrame.set(a, newClosure);
                  int numUpvalues = newPrototype.numUpvalues;

                  for (int i = 0; i < numUpvalues; i++) {
                     op = opcodes[callFrame.pc++];
                     opcode = op & 63;
                     b = getB9(op);
                     switch (opcode) {
                        case 0:
                           newClosure.upvalues[i] = callFrame.findUpvalue(b);
                           break;
                        case 4:
                           newClosure.upvalues[i] = closure.upvalues[b];
                     }
                  }
                  break;
               }
               case 37: {
                  int a = getA8(op);
                  int b = getB9(op) - 1;
                  callFrame.pushVarargs(a, b);
               }
            }
            } // pzopt: luaJit, end of pzoptOp
         } catch (Throwable e) {
            if (e == this.pzoptJitReported) { // pzopt: luaJitNested, a nested luaMainloop of compiled code reported it already (stock: one loop, one report)
               this.pzoptJitReported = null; // pzopt: luaJitNested
            } else if (pzopt.LuaJitDual.ON && this.pzoptDualShadow) { // pzopt: devLuaJitDual, a shadow run's error is not reported (the real run reports its own)
            } else { // pzopt: luaJitNested
            ExceptionLogger.logException(e);
            this.debugException(e);
            this.doStacktraceProper(callFrame);
            if (Core.debug && UIManager.defaultthread == LuaManager.thread) {
               DebugType.Lua.printStackTrace(LogSeverity.Error, -1, "DEBUG-MODE STACK TRACE:", new Object[0]);
               UIManager.debugBreakpoint(LuaManager.thread.currentfile, LuaManager.thread.currentLine - 1);
            }
            } // pzopt: luaJitNested

            boolean rethrow = true;

            do {
               callFrame = this.currentCoroutine.currentCallFrame();
               if (callFrame == null) {
                  Coroutine parent = this.currentCoroutine.getParent();
                  if (parent != null) {
                     LuaCallFrame nextCallFrame = parent.currentCallFrame();
                     nextCallFrame.push(Boolean.FALSE);
                     nextCallFrame.push(e.getMessage());
                     nextCallFrame.push(this.currentCoroutine.stackTrace);
                     this.currentCoroutine.destroy();
                     this.currentCoroutine = parent;
                     callFrame = this.currentCoroutine.currentCallFrame();
                     closure = callFrame.closure;
                     prototype = closure.prototype;
                     opcodes = prototype.code;
                     returnBase = callFrame.returnBase;
                     rethrow = false;
                  }
                  break;
               }

               this.currentCoroutine.addStackTrace(callFrame);
               this.currentCoroutine.popCallFrame();
            } while (callFrame.fromLua);

            if (callFrame != null) {
               callFrame.closeUpvalues(0);
            }

            if (pzopt.LuaJitDual.ON && this.pzoptDualFrame != null) { // pzopt: devLuaJitDual, the real run of a shadowed frame may have ended in this error
               pzopt.LuaJitDual.realUnwound(this, e); // pzopt: devLuaJitDual
            } // pzopt: devLuaJitDual

            if (rethrow) {
               throw e;
            }
         }

         if (this.reset) {
            throw new RuntimeException("lua was reset");
         }
      }
   }

   private void DoProfileTiming(String file, long start, long end) {
   }

   public StringBuilder startErrorMessage() {
      this.stringBuilder.setLength(0);
      return this.stringBuilder;
   }

   public void flushErrorMessage() {
      if (pzopt.LuaJitDual.ON && this.pzoptDualShadow) { // pzopt: devLuaJitDual, a shadow run reports nothing (the real run reports its own)
         return; // pzopt: devLuaJitDual
      } // pzopt: devLuaJitDual
      String str = this.stringBuilder.toString().stripTrailing();
      DebugType.General.error(str);

      while (m_errors_list.size() >= 40) {
         m_errors_list.remove(0);
      }

      m_errors_list.add(str);
      errorCount++;
   }

   public void doStacktraceProper(LuaCallFrame callFrame) {
      if (callFrame != null) {
         StringBuilder sb = this.startErrorMessage();
         sb.append("dumping Lua stack trace\n");
         sb.append("-----------------------------------------\n");
         sb.append("STACK TRACE\n");
         sb.append("-----------------------------------------\n");
         sb.append(StackTraceContainer.getStackTraceString(LuaManager.getLuaStackStrace(callFrame.coroutine), "\t", 0, -1));
         this.flushErrorMessage();
         if (Core.debug && !GameServer.server) {
            LuaCallFrame f = callFrame.coroutine.getCallFrame(callFrame.coroutine.getCallframeTop() - 1);
            if (f != null) {
               LuaManager.debugcaller.pcall(LuaManager.debugthread, LuaManager.env.rawget("DoLuaError"), new Object[]{f.getFilename(), f.pc});
            }
         }
      }
   }

   public void doStacktraceProper() {
      LuaCallFrame callFrame = this.currentCoroutine.currentCallFrame();
      this.doStacktraceProper(callFrame);
   }

   public void debugException(Throwable ex) {
      if (pzopt.LuaJitDual.ON && this.pzoptDualShadow) { // pzopt: devLuaJitDual, a shadow run reports nothing
         return; // pzopt: devLuaJitDual
      } // pzopt: devLuaJitDual
      this.stringWriter.getBuffer().setLength(0);
      ex.printStackTrace(this.printWriter);
      String str = this.stringWriter.toString();
      m_errors_list.add(str);
      errorCount++;
   }

   protected Object getMetaOp(Object o, String meta_op) {
      KahluaTable meta = (KahluaTable)this.getmetatable(o, true);
      return meta == null ? null : meta.rawget(meta_op);
   }

   private final Object getCompMetaOp(Object a, Object b, String meta_op) {
      KahluaTable meta1 = (KahluaTable)this.getmetatable(a, true);
      KahluaTable meta2 = (KahluaTable)this.getmetatable(b, true);
      if (meta1 != null && meta2 != null) {
         Object meta_operator1 = meta1.rawget(meta_op);
         Object meta_operator2 = meta2.rawget(meta_op);
         return meta_operator1 == meta_operator2 && meta_operator1 != null ? meta_operator1 : null;
      } else {
         return null;
      }
   }

   private final Object getBinMetaOp(Object a, Object b, String meta_op) {
      Object op = this.getMetaOp(a, meta_op);
      return op != null ? op : this.getMetaOp(b, meta_op);
   }

   private final Object getRegisterOrConstant(LuaCallFrame callFrame, int index, Prototype prototype) {
      int cindex = index - 256;
      return cindex < 0 ? callFrame.get(index) : prototype.constants[cindex];
   }

   private static final int getA8(int op) {
      return op >>> 6 & 0xFF;
   }

   private static final int getC9(int op) {
      return op >>> 14 & 511;
   }

   private static final int getB9(int op) {
      return op >>> 23 & 511;
   }

   private static final int getBx(int op) {
      return op >>> 14;
   }

   private static final int getSBx(int op) {
      return (op >>> 14) - 131071;
   }

   private Double primitiveMath(Double x, Double y, int opcode) {
      double v1 = KahluaUtil.fromDouble(x);
      double v2 = KahluaUtil.fromDouble(y);
      double res = 0.0;
      switch (opcode) {
         case 12:
            res = v1 + v2;
            break;
         case 13:
            res = v1 - v2;
            break;
         case 14:
            res = v1 * v2;
            break;
         case 15:
            res = v1 / v2;
            break;
         case 16:
            if (v2 == 0.0) {
               res = Double.NaN;
            } else {
               int ipart = (int)(v1 / v2);
               res = v1 - ipart * v2;
            }
            break;
         case 17:
            res = this.platform.pow(v1, v2);
      }

      return KahluaUtil.toDouble(res);
   }

   public Object call(Object fun, Object arg1, Object arg2, Object arg3) {
      int oldTop = this.currentCoroutine.getTop();
      int argslen = 3;
      this.currentCoroutine.setTop(oldTop + 1 + 3);
      this.currentCoroutine.objectStack[oldTop] = fun;
      this.currentCoroutine.objectStack[oldTop + 1] = arg1;
      this.currentCoroutine.objectStack[oldTop + 2] = arg2;
      this.currentCoroutine.objectStack[oldTop + 3] = arg3;
      int nReturnValues = this.call(3);
      Object ret = null;
      if (nReturnValues >= 1) {
         ret = this.currentCoroutine.objectStack[oldTop];
      }

      this.currentCoroutine.setTop(oldTop);
      return ret;
   }

   public Object call(Object fun, Object[] args) {
      int oldTop = this.currentCoroutine.getTop();
      int argslen = args == null ? 0 : args.length;
      this.currentCoroutine.setTop(oldTop + 1 + argslen);
      this.currentCoroutine.objectStack[oldTop] = fun;

      for (int i = 1; i <= argslen; i++) {
         this.currentCoroutine.objectStack[oldTop + i] = args[i - 1];
      }

      int nReturnValues = this.call(argslen);
      Object ret = null;
      if (nReturnValues >= 1) {
         ret = this.currentCoroutine.objectStack[oldTop];
      }

      this.currentCoroutine.setTop(oldTop);
      return ret;
   }

   public Object tableget(Object table, Object key) {
      Object curObj = table;

      for (int i = 100; i > 0; i--) {
         boolean isTable = curObj instanceof KahluaTable;
         if (isTable) {
            KahluaTable t = (KahluaTable)curObj;
            Object res = t.rawget(key);
            if (res != null) {
               return res;
            }
         }

         Object metaOp = this.getMetaOp(curObj, "__index");
         if (metaOp == null) {
            if (isTable) {
               return null;
            }

            StringBuilder sb = this.startErrorMessage();
            sb.append("-------------------------------------------------------------\n");
            sb.append("attempted index: " + key + " of non-table: " + curObj + "\n");
            this.flushErrorMessage();
            this.doStacktraceProper(this.currentCoroutine.currentCallFrame());
            throw new RuntimeException("attempted index: " + key + " of non-table: " + curObj);
         }

         if (metaOp instanceof JavaFunction || metaOp instanceof LuaClosure) {
            return this.call(metaOp, table, key, null);
         }

         curObj = metaOp;
      }

      throw new RuntimeException("loop in gettable");
   }

   /** pzopt: luaJitInlineCache, tableget of a table whose rawget(key) is already known to be null (the stock loop from its __index step). */
   public Object pzoptJitTablegetMiss(Object table, Object key) { // pzopt: luaJitInlineCache
      Object curObj = table; // pzopt: luaJitInlineCache

      for (int i = 100; i > 0; i--) { // pzopt: luaJitInlineCache
         boolean isTable = curObj instanceof KahluaTable; // pzopt: luaJitInlineCache
         if (isTable && i != 100) { // pzopt: luaJitInlineCache
            KahluaTable t = (KahluaTable)curObj; // pzopt: luaJitInlineCache
            Object res = t.rawget(key); // pzopt: luaJitInlineCache
            if (res != null) { // pzopt: luaJitInlineCache
               return res; // pzopt: luaJitInlineCache
            } // pzopt: luaJitInlineCache
         } // pzopt: luaJitInlineCache

         Object metaOp = this.getMetaOp(curObj, "__index"); // pzopt: luaJitInlineCache
         if (metaOp == null) { // pzopt: luaJitInlineCache
            if (isTable) { // pzopt: luaJitInlineCache
               return null; // pzopt: luaJitInlineCache
            } // pzopt: luaJitInlineCache

            StringBuilder sb = this.startErrorMessage(); // pzopt: luaJitInlineCache
            sb.append("-------------------------------------------------------------\n"); // pzopt: luaJitInlineCache
            sb.append("attempted index: " + key + " of non-table: " + curObj + "\n"); // pzopt: luaJitInlineCache
            this.flushErrorMessage(); // pzopt: luaJitInlineCache
            this.doStacktraceProper(this.currentCoroutine.currentCallFrame()); // pzopt: luaJitInlineCache
            throw new RuntimeException("attempted index: " + key + " of non-table: " + curObj); // pzopt: luaJitInlineCache
         } // pzopt: luaJitInlineCache

         if (metaOp instanceof JavaFunction || metaOp instanceof LuaClosure) { // pzopt: luaJitInlineCache
            return this.call(metaOp, table, key, null); // pzopt: luaJitInlineCache
         } // pzopt: luaJitInlineCache

         curObj = metaOp; // pzopt: luaJitInlineCache
      } // pzopt: luaJitInlineCache

      throw new RuntimeException("loop in gettable"); // pzopt: luaJitInlineCache
   } // pzopt: luaJitInlineCache

   public void tableSet(Object table, Object key, Object value) {
      Object curObj = table;

      for (int i = 100; i > 0; i--) {
         Object metaOp;
         if (curObj instanceof KahluaTable t) {
            if (t.rawget(key) != null) {
               t.rawset(key, value);
               return;
            }

            metaOp = this.getMetaOp(curObj, "__newindex");
            if (metaOp == null) {
               t.rawset(key, value);
               return;
            }
         } else {
            metaOp = this.getMetaOp(curObj, "__newindex");
            if (metaOp == null) {
               this.doStacktraceProper(this.currentCoroutine.currentCallFrame());
            }

            KahluaUtil.luaAssert(metaOp != null, "attempted index of non-table");
         }

         if (metaOp instanceof JavaFunction || metaOp instanceof LuaClosure) {
            this.call(metaOp, table, key, value);
            return;
         }

         curObj = metaOp;
      }

      throw new RuntimeException("loop in settable");
   }

   public void setmetatable(Object o, KahluaTable metatable) {
      KahluaUtil.luaAssert(o != null, "Can't set metatable for nil");
      if (o instanceof KahluaTable t) {
         t.setMetatable(metatable);
      } else {
         KahluaUtil.fail("Could not set metatable for object");
      }
   }

   public Object getmetatable(Object o, boolean raw) {
      if (o == null) {
         return null;
      }

      KahluaTable metatable;
      if (o instanceof KahluaTable kahluaTable) {
         metatable = kahluaTable.getMetatable();
      } else {
         metatable = this.getClassMetatable(o.getClass());
      }

      if (!raw && metatable != null) {
         Object meta2 = metatable.rawget("__metatable");
         if (meta2 != null) {
            return meta2;
         }
      }

      return metatable;
   }

   private KahluaTable getClassMetatable(Class<?> c) {
      if (this.cachedMetatables.containsKey(c)) {
         return this.cachedMetatables.get(c);
      }

      KahluaTable metatables = KahluaUtil.getClassMetatables(this.platform, this.getEnvironment());
      Deque<Class<?>> queue = new ArrayDeque<>();
      queue.add(c);
      KahluaTable metatable = null;

      while (!queue.isEmpty()) {
         Class<?> clazz = queue.poll();
         metatable = (KahluaTable)this.tableget(metatables, clazz);
         if (metatable != null) {
            break;
         }

         Class<?> superclass = clazz.getSuperclass();
         if (superclass != null) {
            queue.add(superclass);
         }

         Collections.addAll(queue, clazz.getInterfaces());
      }

      this.cachedMetatables.put(c, metatable);
      return metatable;
   }

   public Object[] pcall(Object fun, Object[] args) {
      int nArgs = args == null ? 0 : args.length;
      Coroutine coroutine = this.currentCoroutine;
      int oldTop = coroutine.getTop();
      coroutine.setTop(oldTop + 1 + nArgs);
      coroutine.objectStack[oldTop] = fun;
      if (nArgs > 0) {
         System.arraycopy(args, 0, coroutine.objectStack, oldTop + 1, nArgs);
      }

      int nRet = this.pcall(nArgs);
      KahluaUtil.luaAssert(coroutine == this.currentCoroutine, "Internal Kahlua error - coroutine changed in pcall");
      Object[] ret = null;
      if (args.length == nRet) {
         ret = args;
      } else {
         ret = new Object[nRet];
      }

      System.arraycopy(coroutine.objectStack, oldTop, ret, 0, nRet);
      coroutine.setTop(oldTop);
      return ret;
   }

   public void pcallvoid(Object fun, Object[] args) {
      int nArgs = args == null ? 0 : args.length;
      Coroutine coroutine = this.currentCoroutine;
      int oldTop = coroutine.getTop();
      coroutine.setTop(oldTop + 1 + nArgs);
      coroutine.objectStack[oldTop] = fun;
      if (nArgs > 0) {
         System.arraycopy(args, 0, coroutine.objectStack, oldTop + 1, nArgs);
      }

      int nRet = this.pcall(nArgs);
      KahluaUtil.luaAssert(coroutine == this.currentCoroutine, "Internal Kahlua error - coroutine changed in pcall");
      coroutine.setTop(oldTop);
   }

   public void pcallvoid(Object fun, Object arg) {
      Coroutine coroutine = this.currentCoroutine;
      int oldTop = coroutine.getTop();
      coroutine.setTop(oldTop + 1 + 1);
      coroutine.objectStack[oldTop] = fun;
      coroutine.objectStack[oldTop + 1] = arg;
      int nRet = this.pcall(1);
      KahluaUtil.luaAssert(coroutine == this.currentCoroutine, "Internal Kahlua error - coroutine changed in pcall");
      coroutine.setTop(oldTop);
   }

   public void pcallvoid(Object fun, Object arg, Object arg2) {
      Coroutine coroutine = this.currentCoroutine;
      int oldTop = coroutine.getTop();
      coroutine.setTop(oldTop + 1 + 2);
      coroutine.objectStack[oldTop] = fun;
      coroutine.objectStack[oldTop + 1] = arg;
      coroutine.objectStack[oldTop + 2] = arg2;
      int nRet = this.pcall(2);
      KahluaUtil.luaAssert(coroutine == this.currentCoroutine, "Internal Kahlua error - coroutine changed in pcall");
      coroutine.setTop(oldTop);
   }

   public void pcallvoid(Object fun, Object arg, Object arg2, Object arg3) {
      Coroutine coroutine = this.currentCoroutine;
      int oldTop = coroutine.getTop();
      coroutine.setTop(oldTop + 1 + 3);
      coroutine.objectStack[oldTop] = fun;
      coroutine.objectStack[oldTop + 1] = arg;
      coroutine.objectStack[oldTop + 2] = arg2;
      coroutine.objectStack[oldTop + 3] = arg3;
      int nRet = this.pcall(3);
      KahluaUtil.luaAssert(coroutine == this.currentCoroutine, "Internal Kahlua error - coroutine changed in pcall");
      coroutine.setTop(oldTop);
   }

   public Boolean pcallBoolean(Object fun, Object arg) {
      Coroutine coroutine = this.currentCoroutine;
      int oldTop = coroutine.getTop();
      coroutine.setTop(oldTop + 1 + 1);
      coroutine.objectStack[oldTop] = fun;
      coroutine.objectStack[oldTop + 1] = arg;
      int nRet = this.pcall(1);
      KahluaUtil.luaAssert(coroutine == this.currentCoroutine, "Internal Kahlua error - coroutine changed in pcall");
      Boolean ret = null;
      if (nRet > 1) {
         Boolean calledOK = (Boolean)coroutine.objectStack[oldTop];
         if (calledOK && coroutine.objectStack[oldTop + 1] instanceof Boolean b) {
            ret = b ? Boolean.TRUE : Boolean.FALSE;
         }
      }

      coroutine.setTop(oldTop);
      return ret;
   }

   public Boolean pcallBoolean(Object fun, Object arg, Object arg2) {
      Coroutine coroutine = this.currentCoroutine;
      int oldTop = coroutine.getTop();
      coroutine.setTop(oldTop + 1 + 2);
      coroutine.objectStack[oldTop] = fun;
      coroutine.objectStack[oldTop + 1] = arg;
      coroutine.objectStack[oldTop + 2] = arg2;
      int nRet = this.pcall(2);
      KahluaUtil.luaAssert(coroutine == this.currentCoroutine, "Internal Kahlua error - coroutine changed in pcall");
      Boolean ret = null;
      if (nRet > 1) {
         Boolean calledOK = (Boolean)coroutine.objectStack[oldTop];
         if (calledOK && coroutine.objectStack[oldTop + 1] instanceof Boolean b) {
            ret = b ? Boolean.TRUE : Boolean.FALSE;
         }
      }

      coroutine.setTop(oldTop);
      return ret;
   }

   public Boolean pcallBoolean(Object fun, Object arg, Object arg2, Object arg3) {
      Coroutine coroutine = this.currentCoroutine;
      int oldTop = coroutine.getTop();
      coroutine.setTop(oldTop + 1 + 3);
      coroutine.objectStack[oldTop] = fun;
      coroutine.objectStack[oldTop + 1] = arg;
      coroutine.objectStack[oldTop + 2] = arg2;
      coroutine.objectStack[oldTop + 3] = arg3;
      int nRet = this.pcall(3);
      KahluaUtil.luaAssert(coroutine == this.currentCoroutine, "Internal Kahlua error - coroutine changed in pcall");
      Boolean ret = null;
      if (nRet > 1) {
         Boolean calledOK = (Boolean)coroutine.objectStack[oldTop];
         if (calledOK && coroutine.objectStack[oldTop + 1] instanceof Boolean b) {
            ret = b ? Boolean.TRUE : Boolean.FALSE;
         }
      }

      coroutine.setTop(oldTop);
      return ret;
   }

   public Boolean pcallBoolean(Object fun, Object[] args) {
      int nArgs = args == null ? 0 : args.length;
      Coroutine coroutine = this.currentCoroutine;
      int oldTop = coroutine.getTop();
      coroutine.setTop(oldTop + 1 + nArgs);
      coroutine.objectStack[oldTop] = fun;
      if (nArgs > 0) {
         System.arraycopy(args, 0, coroutine.objectStack, oldTop + 1, nArgs);
      }

      int nRet = this.pcall(nArgs);
      KahluaUtil.luaAssert(coroutine == this.currentCoroutine, "Internal Kahlua error - coroutine changed in pcall");
      Boolean ret = null;
      if (nRet > 1) {
         Boolean calledOK = (Boolean)coroutine.objectStack[oldTop];
         if (calledOK && coroutine.objectStack[oldTop + 1] instanceof Boolean b) {
            ret = b ? Boolean.TRUE : Boolean.FALSE;
         }
      }

      coroutine.setTop(oldTop);
      return ret;
   }

   public Object[] pcall(Object fun) {
      return this.pcall(fun, null);
   }

   public int pcall(int nArguments) {
      if (this.debugOwnerThread != Thread.currentThread()) {
         DebugType.Lua
            .error(
               "Lua code called from the wrong thread, owner=%s current=%s", new Object[]{this.debugOwnerThread.getName(), Thread.currentThread().getName()}
            );
         DebugType.Lua.printStackTrace();
      }

      Coroutine coroutine = this.currentCoroutine;
      LuaCallFrame currentCallFrame = coroutine.currentCallFrame();
      coroutine.stackTrace = "";
      int oldBase = coroutine.getTop() - nArguments - 1;

      Object errorMessage;
      Throwable exception;
      try {
         int oldCallframetop = coroutine.getCallframeTop();
         int nValues = this.call(nArguments);
         int newCallframeTop = coroutine.getCallframeTop();
         if (oldCallframetop != newCallframeTop) {
            boolean newTop = false;
         }

         KahluaUtil.luaAssert(oldCallframetop == newCallframeTop, "error - call stack depth changed.");
         if (oldCallframetop != newCallframeTop) {
            boolean var13 = false;
         }

         int newTop = oldBase + nValues + 1;
         coroutine.setTop(newTop);
         coroutine.stackCopyNoDebugStuff(oldBase, oldBase + 1, nValues);
         coroutine.objectStack[oldBase] = Boolean.TRUE;
         return 1 + nValues;
      } catch (KahluaException e) {
         exception = e;
         errorMessage = e.errorMessage;
      } catch (Throwable e) {
         exception = e;
         errorMessage = e.getMessage() + " " + e.getClass().getName();
      }

      KahluaUtil.luaAssert(coroutine == this.currentCoroutine, "Internal Kahlua error - coroutine changed in pcall");
      if (currentCallFrame != null) {
         currentCallFrame.closeUpvalues(0);
      }

      coroutine.cleanCallFrames(currentCallFrame);
      if (pzopt.LuaJitDual.ON && this.pzoptDualFrame != null) { // pzopt: devLuaJitDual, the real run of a shadowed frame may have ended in this error
         pzopt.LuaJitDual.realUnwound(this, exception); // pzopt: devLuaJitDual
      } // pzopt: devLuaJitDual
      coroutine.setTop(oldBase + 4);
      coroutine.objectStack[oldBase] = Boolean.FALSE;
      coroutine.objectStack[oldBase + 1] = errorMessage;
      coroutine.objectStack[oldBase + 2] = coroutine.stackTrace;
      coroutine.objectStack[oldBase + 3] = exception;
      coroutine.stackTrace = "";
      return 4;
   }

   public KahluaTable getEnvironment() {
      return this.currentCoroutine.environment;
   }

   public PrintStream getOut() {
      return this.out;
   }

   public Platform getPlatform() {
      return this.platform;
   }

   public void breakpointToggle(String file, int line) {
      ArrayList<Long> i;
      if (!this.breakpointMap.containsKey(file)) {
         i = new ArrayList<>();
         this.breakpointMap.put(file, i);
      } else {
         i = this.breakpointMap.get(file);
      }

      if (!i.contains((long)line)) {
         i.add((long)line);
      } else {
         i.remove((long)line);
      }
   }

   public boolean hasBreakpoint(String file, int line) {
      return this.breakpointMap.containsKey(file) && this.breakpointMap.get(file).contains((long)line);
   }

   public void toggleBreakOnChange(KahluaTable table, Object key) {
      ArrayList<Object> i;
      if (!this.breakpointDataMap.containsKey(table)) {
         i = new ArrayList<>();
         this.breakpointDataMap.put(table, i);
      } else {
         i = this.breakpointDataMap.get(table);
      }

      if (!i.contains(key)) {
         i.add(key);
      } else {
         i.remove(key);
      }
   }

   public void toggleBreakOnRead(KahluaTable table, Object key) {
      ArrayList<Object> i;
      if (!this.breakpointReadDataMap.containsKey(table)) {
         i = new ArrayList<>();
         this.breakpointReadDataMap.put(table, i);
      } else {
         i = this.breakpointReadDataMap.get(table);
      }

      if (!i.contains(key)) {
         i.add(key);
      } else {
         i.remove(key);
      }
   }

   public boolean hasDataBreakpoint(KahluaTable kahluaTableImpl, Object key) {
      if (!this.breakpointDataMap.containsKey(kahluaTableImpl)) {
         return false;
      }

      ArrayList<Object> i = this.breakpointDataMap.get(kahluaTableImpl);
      return i.contains(key);
   }

   public boolean hasReadDataBreakpoint(KahluaTable kahluaTableImpl, Object key) {
      if (!this.breakpointReadDataMap.containsKey(kahluaTableImpl)) {
         return false;
      }

      ArrayList<Object> i = this.breakpointReadDataMap.get(kahluaTableImpl);
      return i.contains(key);
   }

   protected void setLocalVarToStack(LuaCallFrame callFrame, int a) {
      Prototype prototype1 = callFrame.closure.prototype;
      int[] locvarlines = prototype1.locvarlines;
      if (locvarlines != null) {
         int line = prototype1.lines[callFrame.pc - 1];
         boolean bLastOnLine = prototype1.lines[callFrame.pc] != line;
         if (bLastOnLine) {
            while (callFrame.localsAssigned < locvarlines.length && line > locvarlines[callFrame.localsAssigned] && locvarlines[callFrame.localsAssigned] != 0) {
               callFrame.localsAssigned++;
            }

            if (callFrame.localsAssigned < locvarlines.length && locvarlines[callFrame.localsAssigned] == line) {
               int n = callFrame.localsAssigned++;
               String name = prototype1.locvars[n];
               callFrame.setLocalVarToStack(name, callFrame.localBase + a);
            }
         }
      }
   }

   /** pzopt: luaJit, the slow paths of compiled Lua code (pzopt.LuaJitCompiler); each body is the interpreter's own block for that case. */
   public Object pzoptJitArith(LuaCallFrame callFrame, Object bo, Object co, int opcode) { // pzopt: luaJit
      Double bd = null; // pzopt: luaJit
      Double cd = null; // pzopt: luaJit
      Object res = null; // pzopt: luaJit
      if ((bd = KahluaUtil.rawTonumber(bo)) != null && (cd = KahluaUtil.rawTonumber(co)) != null) { // pzopt: luaJit
         res = this.primitiveMath(bd, cd, opcode); // pzopt: luaJit
      } else { // pzopt: luaJit
         String meta_op = meta_ops[opcode]; // pzopt: luaJit
         Object metafun = this.getBinMetaOp(bo, co, meta_op); // pzopt: luaJit
         if (metafun == null) { // pzopt: luaJit
            this.doStacktraceProper(callFrame); // pzopt: luaJit
            String funcName = "unknown"; // pzopt: luaJit
            LuaClosure closure = callFrame.closure; // pzopt: luaJit
            Prototype prototype = closure.prototype; // pzopt: luaJit
            if (closure.debugName != null) { // pzopt: luaJit
               funcName = closure.debugName; // pzopt: luaJit
            } else if (prototype.name != null) { // pzopt: luaJit
               funcName = prototype.name; // pzopt: luaJit
            } // pzopt: luaJit

            KahluaUtil.fail(meta_op + " not defined for operands in " + funcName); // pzopt: luaJit
         } // pzopt: luaJit

         res = this.call(metafun, bo, co, null); // pzopt: luaJit
      } // pzopt: luaJit

      return res; // pzopt: luaJit
   } // pzopt: luaJit

   public Object pzoptJitUnm(Object aObj) { // pzopt: luaJit
      Double aDouble = KahluaUtil.rawTonumber(aObj); // pzopt: luaJit
      Object res; // pzopt: luaJit
      if (aDouble != null) { // pzopt: luaJit
         res = KahluaUtil.toDouble(-KahluaUtil.fromDouble(aDouble)); // pzopt: luaJit
      } else { // pzopt: luaJit
         Object metafun = this.getMetaOp(aObj, "__unm"); // pzopt: luaJit
         res = this.call(metafun, aObj, null, null); // pzopt: luaJit
      } // pzopt: luaJit

      return res; // pzopt: luaJit
   } // pzopt: luaJit

   public Object pzoptJitLen(LuaCallFrame callFrame, Object o) { // pzopt: luaJit
      Object res; // pzopt: luaJit
      if (o instanceof KahluaTable t) { // pzopt: luaJit
         res = KahluaUtil.toDouble(t.len()); // pzopt: luaJit
      } else if (o instanceof String s) { // pzopt: luaJit
         res = KahluaUtil.toDouble(s.length()); // pzopt: luaJit
      } else { // pzopt: luaJit
         Object f = this.getMetaOp(o, "__len"); // pzopt: luaJit
         if (f == null) { // pzopt: luaJit
            this.doStacktraceProper(callFrame); // pzopt: luaJit
         } // pzopt: luaJit

         KahluaUtil.luaAssert(f != null, "__len not defined for operand"); // pzopt: luaJit
         res = this.call(f, o, null, null); // pzopt: luaJit
      } // pzopt: luaJit

      return res; // pzopt: luaJit
   } // pzopt: luaJit

   public Object pzoptJitConcat(LuaCallFrame callFrame, int b, int c) { // pzopt: luaJit
      int first = b; // pzopt: luaJit
      int last = c; // pzopt: luaJit
      Object res = callFrame.get(last); // pzopt: luaJit
      last--; // pzopt: luaJit

      while (first <= last) { // pzopt: luaJit
         String resStr = KahluaUtil.rawTostring(res); // pzopt: luaJit
         if (resStr != null) { // pzopt: luaJit
            int nStrings = 0; // pzopt: luaJit

            for (int pos = last; first <= pos; nStrings++) { // pzopt: luaJit
               Object o = callFrame.get(pos); // pzopt: luaJit
               pos--; // pzopt: luaJit
               if (KahluaUtil.rawTostring(o) == null) { // pzopt: luaJit
                  break; // pzopt: luaJit
               } // pzopt: luaJit
            } // pzopt: luaJit

            if (nStrings > 0) { // pzopt: luaJit
               StringBuilder concatBuffer = this.concatBuffer; // pzopt: luaJit
               concatBuffer.setLength(0); // pzopt: luaJit

               for (int firstString = last - nStrings + 1; firstString <= last; firstString++) { // pzopt: luaJit
                  concatBuffer.append(KahluaUtil.rawTostring(callFrame.get(firstString))); // pzopt: luaJit
               } // pzopt: luaJit

               concatBuffer.append(resStr); // pzopt: luaJit
               res = concatBuffer.toString(); // pzopt: luaJit
               last -= nStrings; // pzopt: luaJit
            } // pzopt: luaJit
         } // pzopt: luaJit

         if (first <= last) { // pzopt: luaJit
            Object leftConcat = callFrame.get(last); // pzopt: luaJit
            Object metafun = this.getBinMetaOp(leftConcat, res, "__concat"); // pzopt: luaJit
            if (metafun == null) { // pzopt: luaJit
               KahluaUtil.fail("__concat not defined for operands: " + leftConcat + " and " + res); // pzopt: luaJit
            } // pzopt: luaJit

            res = this.call(metafun, leftConcat, res, null); // pzopt: luaJit
            last--; // pzopt: luaJit
         } // pzopt: luaJit
      } // pzopt: luaJit

      return res; // pzopt: luaJit
   } // pzopt: luaJit

   /** pzopt: luaJit, OP_EQ / OP_LT / OP_LE when the operands are not two numbers or two strings: the comparison's result. */
   public boolean pzoptJitCompare(LuaCallFrame callFrame, Object bo, Object co, int opcode) { // pzopt: luaJit
      boolean resBool; // pzopt: luaJit
      if (bo == co && opcode == 23) { // pzopt: luaJit
         resBool = true; // pzopt: luaJit
      } else { // pzopt: luaJit
         boolean invert = false; // pzopt: luaJit
         String meta_op = meta_ops[opcode]; // pzopt: luaJit
         Object metafun = this.getCompMetaOp(bo, co, meta_op); // pzopt: luaJit
         if (metafun == null && opcode == 25) { // pzopt: luaJit
            metafun = this.getCompMetaOp(bo, co, "__lt"); // pzopt: luaJit
            Object tmp = bo; // pzopt: luaJit
            bo = co; // pzopt: luaJit
            co = tmp; // pzopt: luaJit
            invert = true; // pzopt: luaJit
         } // pzopt: luaJit

         if (metafun == null && opcode == 23) { // pzopt: luaJit
            resBool = BaseLib.luaEquals(bo, co); // pzopt: luaJit
         } else { // pzopt: luaJit
            if (metafun == null) { // pzopt: luaJit
               this.doStacktraceProper(callFrame); // pzopt: luaJit
               KahluaUtil.fail(meta_op + " not defined for operand"); // pzopt: luaJit
            } // pzopt: luaJit

            Object res = this.call(metafun, bo, co, null); // pzopt: luaJit
            resBool = KahluaUtil.boolEval(res); // pzopt: luaJit
         } // pzopt: luaJit

         if (invert) { // pzopt: luaJit
            resBool = !resBool; // pzopt: luaJit
         } // pzopt: luaJit
      } // pzopt: luaJit

      return resBool; // pzopt: luaJit
   } // pzopt: luaJit

   public void pzoptJitSetGlobal(LuaClosure closure, Object key, Object value) { // pzopt: luaJit
      if (value instanceof LuaClosure luaClosure && key instanceof String) { // pzopt: luaJit
         luaClosure.debugName = key.toString(); // pzopt: luaJit
      } // pzopt: luaJit

      if (LuaCompiler.rewriteEvents) { // pzopt: luaJit
         Object o = closure.env.rawget(key); // pzopt: luaJit
         if (o instanceof KahluaTable && o != value) { // pzopt: luaJit
            KahluaTableImpl tab = (KahluaTableImpl)o; // pzopt: luaJit
            tab.setRewriteTable(value); // pzopt: luaJit
         } // pzopt: luaJit

         this.tableSet(closure.env, key, value); // pzopt: luaJit
      } else { // pzopt: luaJit
         this.tableSet(closure.env, key, value); // pzopt: luaJit
      } // pzopt: luaJit
   } // pzopt: luaJit

   /** pzopt: luaJit, OP_CALL of nil: the interpreter's report and error. */
   public void pzoptJitCallNil(LuaCallFrame callFrame) { // pzopt: luaJit
      this.doStacktraceProper(callFrame); // pzopt: luaJit
      if (callFrame.getClosure().debugName != null) { // pzopt: luaJit
         KahluaUtil.fail("Object tried to call nil in " + callFrame.getClosure().debugName); // pzopt: luaJit
      } else if (callFrame.getClosure().prototype != null && callFrame.getClosure().prototype.name != null) { // pzopt: luaJit
         KahluaUtil.fail("Object tried to call nil in " + callFrame.getClosure().prototype.name); // pzopt: luaJit
      } else { // pzopt: luaJit
         KahluaUtil.fail("Object tried to call nil in unknown"); // pzopt: luaJit
      } // pzopt: luaJit
   } // pzopt: luaJit

   /** pzopt: luaJit, OP_CALL of anything but a Lua or Java function (a __call metatable): 0 = returned, else pzopt.LuaJit's status. */
   public int pzoptJitCallOther(LuaCallFrame callFrame, Object funObject, int localBase2, int returnBase2, int nArguments2) { // pzopt: luaJit
      Object fun = this.prepareMetatableCall(funObject); // pzopt: luaJit
      if (fun == null) { // pzopt: luaJit
         KahluaUtil.fail("Object " + funObject + " did not have __call metatable set"); // pzopt: luaJit
      } // pzopt: luaJit

      if (fun != funObject) { // pzopt: luaJit
         localBase2 = returnBase2; // pzopt: luaJit
         nArguments2++; // pzopt: luaJit
      } // pzopt: luaJit

      if (fun instanceof LuaClosure luaClosure) { // pzopt: luaJit
         LuaCallFrame newCallFrame = this.currentCoroutine.pushNewCallFrame(luaClosure, null, localBase2, returnBase2, nArguments2, true, callFrame.canYield); // pzopt: luaJit
         newCallFrame.init(); // pzopt: luaJit
         return pzopt.LuaJit.invoke(this, newCallFrame); // pzopt: luaJit
      } else { // pzopt: luaJit
         if (!(fun instanceof JavaFunction javaFunction)) { // pzopt: luaJit
            throw new RuntimeException("Tried to call a non-function: " + fun); // pzopt: luaJit
         } // pzopt: luaJit

         Coroutine coroutine = this.currentCoroutine; // pzopt: luaJit
         int frames = coroutine.getCallframeTop(); // pzopt: luaJit
         this.callJava(javaFunction, localBase2, returnBase2, nArguments2); // pzopt: luaJit
         return this.currentCoroutine == coroutine && coroutine.getCallframeTop() == frames ? 0 : 2; // pzopt: luaJit
      } // pzopt: luaJit
   } // pzopt: luaJit

   /** pzopt: luaJit, OP_CALL of a Java function from compiled code: 0 = back in the caller, 2 = the coroutine or frame changed (the interpreter takes over). */
   public int pzoptJitCallJava(JavaFunction javaFunction, int localBase2, int returnBase2, int nArguments2) { // pzopt: luaJit
      Coroutine coroutine = this.currentCoroutine; // pzopt: luaJit
      int frames = coroutine.getCallframeTop(); // pzopt: luaJit
      this.callJava(javaFunction, localBase2, returnBase2, nArguments2); // pzopt: luaJit
      return this.currentCoroutine == coroutine && coroutine.getCallframeTop() == frames ? 0 : 2; // pzopt: luaJit
   } // pzopt: luaJit

   /** pzopt: luaJit, OP_TAILCALL (arguments on the Lua stack): 4 = the frame now holds the Lua callee at pc 0, 0 = returned through a Java function, 3 = that Java function switched coroutine. */
   public int pzoptJitTailCall(LuaCallFrame callFrame, int a, int b) { // pzopt: luaJit
      int base = callFrame.localBase; // pzopt: luaJit
      this.currentCoroutine.closeUpvalues(base); // pzopt: luaJit
      int nArguments2 = b - 1; // pzopt: luaJit
      if (nArguments2 == -1) { // pzopt: luaJit
         nArguments2 = callFrame.getTop() - a - 1; // pzopt: luaJit
      } // pzopt: luaJit

      callFrame.restoreTop = false; // pzopt: luaJit
      Object funObject = callFrame.get(a); // pzopt: luaJit

      try { // pzopt: luaJit
         KahluaUtil.luaAssert(funObject != null, "Tried to call nil"); // pzopt: luaJit
      } catch (Exception ex) { // pzopt: luaJit
         if (Core.debug && UIManager.defaultthread == LuaManager.thread) { // pzopt: luaJit
            UIManager.debugBreakpoint(LuaManager.thread.currentfile, LuaManager.thread.currentLine - 1); // pzopt: luaJit
         } // pzopt: luaJit

         this.debugException(ex); // pzopt: luaJit
         this.doStacktraceProper(callFrame); // pzopt: luaJit
         KahluaUtil.fail(""); // pzopt: luaJit
      } // pzopt: luaJit

      Object fun = this.prepareMetatableCall(funObject); // pzopt: luaJit
      if (fun == null) { // pzopt: luaJit
         KahluaUtil.fail("Object did not have __call metatable set"); // pzopt: luaJit
      } // pzopt: luaJit

      int returnBase = callFrame.returnBase; // pzopt: luaJit
      int localBase2 = returnBase + 1; // pzopt: luaJit
      if (fun != funObject) { // pzopt: luaJit
         localBase2 = returnBase; // pzopt: luaJit
         nArguments2++; // pzopt: luaJit
      } // pzopt: luaJit

      this.currentCoroutine.stackCopy(base + a, returnBase, nArguments2 + 1); // pzopt: luaJit
      this.currentCoroutine.setTop(returnBase + nArguments2 + 1); // pzopt: luaJit
      if (fun instanceof LuaClosure luaClosure) { // pzopt: luaJit
         callFrame.localBase = localBase2; // pzopt: luaJit
         callFrame.nArguments = nArguments2; // pzopt: luaJit
         callFrame.closure = luaClosure; // pzopt: luaJit
         callFrame.init(); // pzopt: luaJit
         return 4; // pzopt: luaJit
      } else { // pzopt: luaJit
         if (!(fun instanceof JavaFunction)) { // pzopt: luaJit
            KahluaUtil.fail("Tried to call a non-function: " + fun); // pzopt: luaJit
         } // pzopt: luaJit

         Coroutine oldCoroutine = this.currentCoroutine; // pzopt: luaJit
         this.callJava((JavaFunction)fun, localBase2, returnBase, nArguments2); // pzopt: luaJit
         oldCoroutine.popCallFrame(); // pzopt: luaJit
         if (oldCoroutine != this.currentCoroutine) { // pzopt: luaJit
            if (oldCoroutine.isDead() && oldCoroutine != this.rootCoroutine && this.currentCoroutine.getParent() == oldCoroutine) { // pzopt: luaJit
               this.currentCoroutine.resume(oldCoroutine.getParent()); // pzopt: luaJit
               oldCoroutine.destroy(); // pzopt: luaJit
               this.currentCoroutine.getParent().currentCallFrame().push(Boolean.TRUE); // pzopt: luaJit
            } // pzopt: luaJit

            return 3; // pzopt: luaJit
         } else { // pzopt: luaJit
            return 0; // pzopt: luaJit
         } // pzopt: luaJit
      } // pzopt: luaJit
   } // pzopt: luaJit

   /** pzopt: luaJit, a compiled frame's pc (for the profiler / tracebacks) and its restoreTop, set by the generated code. */
   /** pzopt: luaJit, the shared part of a compiled OP_CALL (arguments already on the Lua stack, the frame's top set): 0 = returned into the caller, else pzopt.LuaJit's status (5 when lua was reset). */
   public int pzoptJitCall(LuaCallFrame callFrame, Object funObject, int a, int nArguments2, boolean restoreTop) { // pzopt: luaJit
      callFrame.restoreTop = restoreTop; // pzopt: luaJit
      int base = callFrame.localBase + a; // pzopt: luaJit
      int st; // pzopt: luaJit
      if (funObject instanceof LuaClosure luaClosure && pzopt.LuaJit.NESTED && luaClosure.prototype.pzoptJit == null // pzopt: luaJitNested
            && this.pzoptJitDepth < pzopt.LuaJit.MAX_DEPTH) { // pzopt: luaJitNested
         // a callee without code runs in a luaMainloop of its own (as a Lua function Java calls), so this frame stays compiled
         LuaCallFrame newCallFrame = this.currentCoroutine.pushNewCallFrame(luaClosure, null, base + 1, base, nArguments2, false, false); // pzopt: luaJitNested, returns to here
         newCallFrame.init(); // pzopt: luaJitNested
         this.pzoptJitDepth++; // pzopt: luaJitNested
         try { // pzopt: luaJitNested
            this.luaMainloop(); // pzopt: luaJitNested
         } catch (Throwable e) { // pzopt: luaJitNested
            this.pzoptJitReported = e; // pzopt: luaJitNested, its catch reported it; the loop this frame runs under only unwinds
            throw e; // pzopt: luaJitNested
         } finally { // pzopt: luaJitNested
            this.pzoptJitDepth--; // pzopt: luaJitNested
         } // pzopt: luaJitNested
         st = 0; // pzopt: luaJitNested
      } else if (funObject instanceof LuaClosure luaClosure) { // pzopt: luaJit
         LuaCallFrame newCallFrame = this.currentCoroutine.pushNewCallFrame(luaClosure, null, base + 1, base, nArguments2, true, false); // pzopt: luaJit
         newCallFrame.init(); // pzopt: luaJit
         st = pzopt.LuaJit.invoke(this, newCallFrame); // pzopt: luaJit
      } else if (funObject instanceof JavaFunction javaFunction) { // pzopt: luaJit
         st = this.pzoptJitCallJava(javaFunction, base + 1, base, nArguments2); // pzopt: luaJit
      } else { // pzopt: luaJit
         if (funObject == null) { // pzopt: luaJit
            this.pzoptJitCallNil(callFrame); // pzopt: luaJit
         } // pzopt: luaJit
         st = this.pzoptJitCallOther(callFrame, funObject, base + 1, base, nArguments2); // pzopt: luaJit
      } // pzopt: luaJit
      if (st == 0) { // pzopt: luaJit
         if (restoreTop) { // pzopt: luaJit
            callFrame.coroutine.setTop(callFrame.localBase + callFrame.closure.prototype.maxStacksize); // pzopt: luaJit
         } // pzopt: luaJit
         if (this.reset) { // pzopt: luaJit
            return pzopt.LuaJit.ST_RESET; // pzopt: luaJit
         } // pzopt: luaJit
      } // pzopt: luaJit
      return st; // pzopt: luaJit
   } // pzopt: luaJit

   /** luaJitDirectCalls: OP_CALL's frame push for a compiled callee entered from the call site; null (nothing done) at the depth limit. */
   public LuaCallFrame pzoptJitPushLua(LuaCallFrame callFrame, LuaClosure luaClosure, int a, int nArguments2, boolean restoreTop) { // pzopt: luaJitDirectCalls
      if (this.pzoptJitDepth >= pzopt.LuaJit.MAX_DEPTH) { // pzopt: luaJitDirectCalls
         return null; // pzopt: luaJitDirectCalls
      } // pzopt: luaJitDirectCalls
      callFrame.restoreTop = restoreTop; // pzopt: luaJitDirectCalls
      int base = callFrame.localBase + a; // pzopt: luaJitDirectCalls
      LuaCallFrame newCallFrame = this.currentCoroutine.pushNewCallFrame(luaClosure, null, base + 1, base, nArguments2, true, false); // pzopt: luaJitDirectCalls
      newCallFrame.init(); // pzopt: luaJitDirectCalls
      this.pzoptJitDepth++; // pzopt: luaJitDirectCalls, LuaJit.invoke's finally puts it back if the callee throws
      return newCallFrame; // pzopt: luaJitDirectCalls
   } // pzopt: luaJitDirectCalls

   /** luaJitDirectCalls + luaJavaFast: callJava's frame push for an exposed method called through its generated class from the call site. */
   public LuaCallFrame pzoptJitPushJava(LuaCallFrame callFrame, se.krka.kahlua.integration.expose.LuaJavaInvoker f, int a, int nArguments2, boolean restoreTop) { // pzopt: luaJitDirectCalls
      callFrame.restoreTop = restoreTop; // pzopt: luaJitDirectCalls
      int base = callFrame.localBase + a; // pzopt: luaJitDirectCalls
      return this.currentCoroutine.pushNewCallFrame(null, f, base + 1, base, nArguments2, false, false); // pzopt: luaJitDirectCalls
   } // pzopt: luaJitDirectCalls

   /** devLuaJitDual: what a shadow run of frame f (at its entry) may change: the Lua stack from its returnBase up, the stack top, the call stack, the frame, the stack trace. */
   public Object[] pzoptDualSave(LuaCallFrame f) { // pzopt: devLuaJitDual
      Coroutine co = f.coroutine; // pzopt: devLuaJitDual
      Object[] stack = new Object[co.objectStack.length - f.returnBase]; // pzopt: devLuaJitDual
      System.arraycopy(co.objectStack, f.returnBase, stack, 0, stack.length); // pzopt: devLuaJitDual
      return new Object[] {stack, co.getTop(), co.getCallframeTop(), f.closure, f.javaFunction, f.pc, f.localBase, f.returnBase, f.nArguments, // pzopt: devLuaJitDual
         f.fromLua, f.canYield, f.restoreTop, f.localsAssigned, co.stackTrace, this.pzoptJitReported, this.pzoptJitDepth, this.currentCoroutine}; // pzopt: devLuaJitDual
   } // pzopt: devLuaJitDual

   /** devLuaJitDual: puts back what pzoptDualSave saved (the shadow's upvalues of the frame's slots closed first). */
   public void pzoptDualRestore(LuaCallFrame f, Object[] s) { // pzopt: devLuaJitDual
      Coroutine co = f.coroutine; // pzopt: devLuaJitDual
      int localBase = (Integer)s[6]; // pzopt: devLuaJitDual
      int returnBase = (Integer)s[7]; // pzopt: devLuaJitDual
      co.closeUpvalues(localBase); // pzopt: devLuaJitDual
      this.currentCoroutine = (Coroutine)s[16]; // pzopt: devLuaJitDual
      co.setCallFrameStackTop((Integer)s[2]); // pzopt: devLuaJitDual
      f.closure = (LuaClosure)s[3]; // pzopt: devLuaJitDual
      f.javaFunction = (JavaFunction)s[4]; // pzopt: devLuaJitDual
      f.pc = (Integer)s[5]; // pzopt: devLuaJitDual
      f.localBase = localBase; // pzopt: devLuaJitDual
      f.returnBase = returnBase; // pzopt: devLuaJitDual
      f.nArguments = (Integer)s[8]; // pzopt: devLuaJitDual
      f.fromLua = (Boolean)s[9]; // pzopt: devLuaJitDual
      f.canYield = (Boolean)s[10]; // pzopt: devLuaJitDual
      f.restoreTop = (Boolean)s[11]; // pzopt: devLuaJitDual
      f.localsAssigned = (Integer)s[12]; // pzopt: devLuaJitDual
      co.stackTrace = (String)s[13]; // pzopt: devLuaJitDual
      this.pzoptJitReported = (Throwable)s[14]; // pzopt: devLuaJitDual
      this.pzoptJitDepth = (Integer)s[15]; // pzopt: devLuaJitDual
      co.setTop((Integer)s[1]); // pzopt: devLuaJitDual
      Object[] stack = (Object[])s[0]; // pzopt: devLuaJitDual
      int n = Math.min(stack.length, co.objectStack.length - returnBase); // pzopt: devLuaJitDual
      System.arraycopy(stack, 0, co.objectStack, returnBase, n); // pzopt: devLuaJitDual
      java.util.Arrays.fill(co.objectStack, returnBase + n, co.objectStack.length, null); // pzopt: devLuaJitDual
   } // pzopt: devLuaJitDual

   /** luaJitDirectArgs: callJava's frame push of any Java function (pzopt.LuaJavaFast.directThrow, around the exception report). */
   public LuaCallFrame pzoptJitPushJavaFn(LuaCallFrame callFrame, JavaFunction f, int a, int nArguments2, boolean restoreTop) { // pzopt: luaJitDirectArgs
      callFrame.restoreTop = restoreTop; // pzopt: luaJitDirectArgs
      int base = callFrame.localBase + a; // pzopt: luaJitDirectArgs
      return this.currentCoroutine.pushNewCallFrame(null, f, base + 1, base, nArguments2, false, false); // pzopt: luaJitDirectArgs
   } // pzopt: luaJitDirectArgs

   /** luaJitDirectCalls + luaJavaFast: the rest of callJava (the stock call when the generated one declined) and of pzoptJitCall. */
   public int pzoptJitAfterJava(LuaCallFrame callFrame, LuaCallFrame javaFrame, se.krka.kahlua.integration.expose.LuaJavaInvoker f, int nReturnValues, int nArguments2, int frames, boolean restoreTop) { // pzopt: luaJitDirectCalls
      Coroutine coroutine = javaFrame.coroutine; // pzopt: luaJitDirectCalls, callJava's
      if (nReturnValues < 0) { // pzopt: luaJitDirectCalls
         nReturnValues = f.pzoptStockCall(javaFrame, nArguments2); // pzopt: luaJitDirectCalls
      } // pzopt: luaJitDirectCalls
      int top = javaFrame.getTop(); // pzopt: luaJitDirectCalls
      int actualReturnBase = top - nReturnValues; // pzopt: luaJitDirectCalls
      int diff = javaFrame.returnBase - javaFrame.localBase; // pzopt: luaJitDirectCalls
      javaFrame.stackCopy(actualReturnBase, diff, nReturnValues); // pzopt: luaJitDirectCalls
      javaFrame.setTop(nReturnValues + diff); // pzopt: luaJitDirectCalls
      coroutine.popCallFrame(); // pzopt: luaJitDirectCalls
      if (this.currentCoroutine != coroutine || coroutine.getCallframeTop() != frames) { // pzopt: luaJitDirectCalls, as pzoptJitCallJava
         return pzopt.LuaJit.ST_AFTER_JAVA; // pzopt: luaJitDirectCalls
      } // pzopt: luaJitDirectCalls
      if (restoreTop) { // pzopt: luaJitDirectCalls
         callFrame.coroutine.setTop(callFrame.localBase + callFrame.closure.prototype.maxStacksize); // pzopt: luaJitDirectCalls
      } // pzopt: luaJitDirectCalls
      if (this.reset) { // pzopt: luaJitDirectCalls
         return pzopt.LuaJit.ST_RESET; // pzopt: luaJitDirectCalls
      } // pzopt: luaJitDirectCalls
      return 0; // pzopt: luaJitDirectCalls
   } // pzopt: luaJitDirectCalls

   /** luaJitDirectCalls: after the callee's run, as pzoptJitCall after LuaJit.invoke (a tail call continues in invoke). */
   public int pzoptJitAfterLua(LuaCallFrame callFrame, LuaCallFrame newCallFrame, int st, boolean restoreTop) { // pzopt: luaJitDirectCalls
      this.pzoptJitDepth--; // pzopt: luaJitDirectCalls
      if (st == pzopt.LuaJit.ST_TAILCALL) { // pzopt: luaJitDirectCalls
         st = pzopt.LuaJit.invoke(this, newCallFrame); // pzopt: luaJitDirectCalls
      } // pzopt: luaJitDirectCalls
      if (st == 0) { // pzopt: luaJitDirectCalls
         if (restoreTop) { // pzopt: luaJitDirectCalls
            callFrame.coroutine.setTop(callFrame.localBase + callFrame.closure.prototype.maxStacksize); // pzopt: luaJitDirectCalls
         } // pzopt: luaJitDirectCalls
         if (this.reset) { // pzopt: luaJitDirectCalls
            return pzopt.LuaJit.ST_RESET; // pzopt: luaJitDirectCalls
         } // pzopt: luaJitDirectCalls
      } // pzopt: luaJitDirectCalls
      return st; // pzopt: luaJitDirectCalls
   } // pzopt: luaJitDirectCalls

   public static void pzoptJitRestoreTop(LuaCallFrame callFrame, boolean restoreTop) { // pzopt: luaJit
      callFrame.restoreTop = restoreTop; // pzopt: luaJit
   } // pzopt: luaJit

   public static int pzoptJitReturnBase(LuaCallFrame callFrame) { // pzopt: luaJit
      return callFrame.returnBase; // pzopt: luaJit
   } // pzopt: luaJit

   public static boolean pzoptJitFromLua(LuaCallFrame callFrame) { // pzopt: luaJit
      return callFrame.fromLua; // pzopt: luaJit
   } // pzopt: luaJit

   static {
      meta_ops[12] = "__add";
      meta_ops[13] = "__sub";
      meta_ops[14] = "__mul";
      meta_ops[15] = "__div";
      meta_ops[16] = "__mod";
      meta_ops[17] = "__pow";
      meta_ops[23] = "__eq";
      meta_ops[24] = "__lt";
      meta_ops[25] = "__le";
   }

   public static class Entry {
      public String file;
      public double time;
   }

   private static class ProfileEntryComparitor implements Comparator<KahluaThread.Entry> {
      public ProfileEntryComparitor() {
      }

      public int compare(KahluaThread.Entry o1, KahluaThread.Entry o2) {
         double dist1 = o1.time;
         double dist2 = o2.time;
         if (dist1 > dist2) {
            return -1;
         } else {
            return dist2 > dist1 ? 1 : 0;
         }
      }
   }
}
