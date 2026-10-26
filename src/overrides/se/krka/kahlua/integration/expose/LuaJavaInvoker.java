package se.krka.kahlua.integration.expose;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import se.krka.kahlua.converter.KahluaConverterManager;
import se.krka.kahlua.integration.expose.caller.Caller;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.LuaCallFrame;

public class LuaJavaInvoker implements JavaFunction {
   private final LuaJavaClassExposer exposer;
   private final KahluaConverterManager manager;
   private final Class<?> clazz;
   private final String name;
   private final Caller caller;
   private final Class<?>[] parameterTypes;
   private final int numMethodParams;
   private final Class<?> varargType;
   private final boolean hasSelf;
   private final boolean needsReturnValues;
   private final boolean hasVarargs;
   private pzopt.LuaJavaFast pzoptFast; // pzopt: luaJavaFast, the generated call (or LuaJavaFast.NONE)
   private int pzoptCalls; // pzopt: luaJavaFast

   public LuaJavaInvoker(LuaJavaClassExposer exposer, KahluaConverterManager manager, Class<?> clazz, String name, Caller caller) {
      this.exposer = exposer;
      this.manager = manager;
      this.clazz = clazz;
      this.name = name;
      this.caller = caller;
      this.parameterTypes = caller.getParameterTypes();
      this.varargType = caller.getVarargType();
      this.hasSelf = caller.hasSelf();
      this.needsReturnValues = caller.needsMultipleReturnValues();
      this.hasVarargs = caller.hasVararg();
      this.numMethodParams = this.parameterTypes.length + this.toInt(this.needsReturnValues) + this.toInt(this.hasVarargs);
   }

   private int toInt(boolean b) {
      return b ? 1 : 0;
   }

   public MethodArguments prepareCall(LuaCallFrame callFrame, int nArguments) {
      MethodArguments methodArguments = MethodArguments.get(this.numMethodParams);
      int javaParamCounter = 0;
      int luaArgCounter = 0;
      int selfDecr = this.toInt(this.hasSelf);
      if (this.hasSelf) {
         Object self = nArguments <= 0 ? null : callFrame.get(0);
         if (self == null || !this.clazz.isInstance(self)) {
            methodArguments.fail(this.syntaxErrorMessage(this.name + ": Expected a method call but got a function call."));
            return methodArguments;
         }

         methodArguments.setSelf(self);
         luaArgCounter++;
      }

      ReturnValues returnValues = ReturnValues.get(this.manager, callFrame);
      methodArguments.setReturnValues(returnValues);
      if (this.needsReturnValues) {
         methodArguments.getParams()[javaParamCounter] = returnValues;
         javaParamCounter++;
      }

      if (nArguments - luaArgCounter < this.parameterTypes.length) {
         int expected = this.parameterTypes.length;
         int got = nArguments - selfDecr;
         methodArguments.fail("expected %d argument%s, got %d".formatted(expected, expected == 1 ? "" : "s", got));
         return methodArguments;
      }

      if (!this.hasVarargs && luaArgCounter != 0 && this.parameterTypes.length < nArguments - luaArgCounter) {
         int expected = this.parameterTypes.length;
         int got = nArguments - selfDecr;
         methodArguments.fail("expected %d argument%s, got %d".formatted(expected, expected == 1 ? "" : "s", got));
         return methodArguments;
      }

      for (int i = 0; i < this.parameterTypes.length; i++) {
         Object o = callFrame.get(luaArgCounter + i);
         int parameterIndex = luaArgCounter + i - selfDecr;
         Class<?> parameterType = this.parameterTypes[i];
         Object obj = o;
         if (!parameterType.isInstance(o)) {
            obj = this.convert(o, parameterType);
         }

         if (o != null && obj == null) {
            methodArguments.fail("expected argument of type %s, got %s".formatted(parameterType.getSimpleName(), o.getClass().getSimpleName()));
            return methodArguments;
         }

         methodArguments.getParams()[javaParamCounter + i] = obj;
      }

      javaParamCounter += this.parameterTypes.length;
      luaArgCounter += this.parameterTypes.length;
      if (this.hasVarargs) {
         int numVarargs = nArguments - luaArgCounter;
         if (numVarargs < 0) {
         }

         Object[] varargs = (Object[])Array.newInstance(this.varargType, numVarargs);

         for (int i = 0; i < numVarargs; i++) {
            Object o = callFrame.get(luaArgCounter + i);
            int parameterIndex = luaArgCounter + i - selfDecr;
            Object obj = this.convert(o, this.varargType);
            varargs[i] = obj;
            if (o != null && obj == null) {
               methodArguments.fail("expected argument of type %s, got %s".formatted(this.varargType.getSimpleName(), o.getClass().getSimpleName()));
               return methodArguments;
            }
         }

         methodArguments.getParams()[javaParamCounter] = varargs;
         javaParamCounter++;
         luaArgCounter += numVarargs;
      } else {
         if (nArguments == 1 && luaArgCounter == 0) {
            return methodArguments;
         }

         if (luaArgCounter != nArguments) {
            int expected = luaArgCounter;
            int got = nArguments - selfDecr;
            methodArguments.fail("expected %d argument%s, got %d".formatted(expected, expected == 1 ? "" : "s", got));
            return methodArguments;
         }
      }

      return methodArguments;
   }

   public int call(LuaCallFrame callFrame, int nArguments) {
      if (pzopt.LuaJavaFast.ON && pzopt.LuaJavaFast.active()) { // pzopt: luaJavaFast
         int r = this.pzoptTryFast(callFrame, nArguments); // pzopt: luaJavaFast, -1 = the stock path below
         if (r >= 0) { // pzopt: luaJavaFast
            return r; // pzopt: luaJavaFast
         } // pzopt: luaJavaFast
      } // pzopt: luaJavaFast
      MethodArguments methodArguments = this.prepareCall(callFrame, nArguments);
      methodArguments.assertValid();
      int val = this.call(methodArguments);
      ReturnValues.put(methodArguments.getReturnValues());
      MethodArguments.put(methodArguments);
      return val;
   }

   /** pzopt: luaJavaFast, the stock body of call(LuaCallFrame, int) (a compiled call site whose generated call declined). */
   public int pzoptStockCall(LuaCallFrame callFrame, int nArguments) { // pzopt: luaJavaFast
      MethodArguments methodArguments = this.prepareCall(callFrame, nArguments); // pzopt: luaJavaFast
      methodArguments.assertValid(); // pzopt: luaJavaFast
      int val = this.call(methodArguments); // pzopt: luaJavaFast
      ReturnValues.put(methodArguments.getReturnValues()); // pzopt: luaJavaFast
      MethodArguments.put(methodArguments); // pzopt: luaJavaFast
      return val; // pzopt: luaJavaFast
   } // pzopt: luaJavaFast

   /** pzopt: luaJavaFast, the luaJit worker found this method's shape not covered (calls always take the stock path). */
   public boolean pzoptFastIsNone() { // pzopt: luaJavaFast
      return this.pzoptFast == pzopt.LuaJavaFast.NONE; // pzopt: luaJavaFast
   } // pzopt: luaJavaFast

   /** pzopt: luaJavaFast, the generated call when there is one and it may run now, else null (the call goes through call(LuaCallFrame, int)). */
   public pzopt.LuaJavaFast pzoptFast() { // pzopt: luaJavaFast
      pzopt.LuaJavaFast f = this.pzoptFast; // pzopt: luaJavaFast
      return f != null && f != pzopt.LuaJavaFast.NONE && pzopt.LuaJavaFast.active() ? f : null; // pzopt: luaJavaFast
   } // pzopt: luaJavaFast

   public int call(MethodArguments methodArguments) {
      try {
         ReturnValues returnValues = methodArguments.getReturnValues();
         this.caller.call(methodArguments.getSelf(), returnValues, methodArguments.getParams());
         return returnValues.getNArguments();
      } catch (IllegalArgumentException | InstantiationException | IllegalAccessException e) {
         throw new RuntimeException(e);
      } catch (InvocationTargetException e) {
         throw new RuntimeException(e.getCause());
      }
   }

   private Object convert(Object o, Class<?> parameterType) {
      return o == null ? null : this.manager.fromLuaToJava(o, parameterType);
   }

   private String syntaxErrorMessage(String errorMessage) {
      String syntax = this.getFunctionSyntax();
      if (syntax != null) {
         errorMessage = errorMessage + " Correct syntax: " + syntax;
      }

      return errorMessage;
   }

   private String newError(int i, String message) {
      int argumentIndex = i + 1;
      String errorMessage = message + " at argument #" + argumentIndex;
      String argumentName = this.getParameterName(i);
      if (argumentName != null) {
         errorMessage = errorMessage + ", " + argumentName;
      }

      return errorMessage;
   }

   private String getFunctionSyntax() {
      MethodDebugInformation methodDebug = this.getMethodDebugData();
      return methodDebug != null ? methodDebug.getLuaDescription() : null;
   }

   public MethodDebugInformation getMethodDebugData() {
      ClassDebugInformation debugInformation = this.exposer.getDebugdata(this.clazz);
      return debugInformation == null ? null : (MethodDebugInformation)debugInformation.getMethods().get(this.caller.getDescriptor());
   }

   private String getParameterName(int i) {
      MethodDebugInformation methodDebug = this.getMethodDebugData();
      return methodDebug != null ? ((MethodParameter)methodDebug.getParameters().get(i)).getName() : null;
   }

   @Override
   public String toString() {
      return this.name;
   }

   public int getNumMethodParams() {
      return this.numMethodParams;
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) {
         return true;
      }

      if (o != null && this.getClass() == o.getClass()) {
         LuaJavaInvoker that = (LuaJavaInvoker)o;
         if (!this.caller.equals(that.caller)) {
            return false;
         } else {
            return !this.clazz.equals(that.clazz) ? false : this.name.equals(that.name);
         }
      } else {
         return false;
      }
   }

   @Override
   public int hashCode() {
      int result = this.clazz.hashCode();
      result = 31 * result + this.name.hashCode();
      return 31 * result + this.caller.hashCode();
   }

   public boolean matchesArgumentTypes(LuaCallFrame callFrame, int nArguments) {
      int luaArgCounter = 0;
      if (this.hasSelf) {
         Object self = nArguments <= 0 ? null : callFrame.get(0);
         if (self == null || !this.clazz.isInstance(self)) {
            return false;
         }

         luaArgCounter++;
      }

      if (this.parameterTypes.length != nArguments - luaArgCounter) {
         return false;
      }

      for (int i = 0; i < this.parameterTypes.length; i++) {
         Object o = callFrame.get(luaArgCounter + i);
         Class<?> parameterType = this.parameterTypes[i];
         if (!parameterType.isInstance(o)) {
            return false;
         }
      }

      return true;
   }

   public boolean matchesArgumentTypesOrPrimitives(LuaCallFrame callFrame, int nArguments) {
      int luaArgCounter = 0;
      if (this.hasSelf) {
         Object self = nArguments <= 0 ? null : callFrame.get(0);
         if (self == null || !this.clazz.isInstance(self)) {
            return false;
         }

         luaArgCounter++;
      }

      if (this.parameterTypes.length != nArguments - luaArgCounter) {
         return false;
      }

      for (int i = 0; i < this.parameterTypes.length; i++) {
         Object o = callFrame.get(luaArgCounter + i);
         Class<?> parameterType = this.parameterTypes[i];
         if (!parameterType.isInstance(o)) {
            if (parameterType.isPrimitive()) {
               if (o == null) {
                  return false;
               }

               if (o instanceof Double) {
                  if (parameterType == void.class || parameterType == boolean.class) {
                     return false;
                  }
                  continue; // pzopt: decompiler fix, the jar's branch shape (one shared return false below)
               }
               if (o instanceof Boolean && parameterType == boolean.class) { // pzopt: decompiler fix
                  continue; // pzopt: decompiler fix
               } // pzopt: decompiler fix
            } else if (o == null) { // pzopt: decompiler fix
               continue; // pzopt: decompiler fix
            }
            return false; // pzopt: decompiler fix
         }
      }

      return true;
   }

   /** pzopt: luaJitDirectArgs, matchesArgumentTypes (prims false) or matchesArgumentTypesOrPrimitives (true) of argument values x (x.length = nArguments). */
   public boolean pzoptMatches(Object[] x, boolean prims) { // pzopt: luaJitDirectArgs
      int nArguments = x.length; // pzopt: luaJitDirectArgs
      int luaArgCounter = 0; // pzopt: luaJitDirectArgs
      if (this.hasSelf) { // pzopt: luaJitDirectArgs
         Object self = nArguments <= 0 ? null : x[0]; // pzopt: luaJitDirectArgs
         if (self == null || !this.clazz.isInstance(self)) { // pzopt: luaJitDirectArgs
            return false; // pzopt: luaJitDirectArgs
         } // pzopt: luaJitDirectArgs
         luaArgCounter++; // pzopt: luaJitDirectArgs
      } // pzopt: luaJitDirectArgs
      if (this.parameterTypes.length != nArguments - luaArgCounter) { // pzopt: luaJitDirectArgs
         return false; // pzopt: luaJitDirectArgs
      } // pzopt: luaJitDirectArgs
      for (int i = 0; i < this.parameterTypes.length; i++) { // pzopt: luaJitDirectArgs
         Object o = x[luaArgCounter + i]; // pzopt: luaJitDirectArgs
         Class<?> parameterType = this.parameterTypes[i]; // pzopt: luaJitDirectArgs
         if (!parameterType.isInstance(o)) { // pzopt: luaJitDirectArgs
            if (!prims) { // pzopt: luaJitDirectArgs
               return false; // pzopt: luaJitDirectArgs
            } // pzopt: luaJitDirectArgs
            if (parameterType.isPrimitive()) { // pzopt: luaJitDirectArgs
               if (o == null) { // pzopt: luaJitDirectArgs
                  return false; // pzopt: luaJitDirectArgs
               } // pzopt: luaJitDirectArgs
               if (o instanceof Double) { // pzopt: luaJitDirectArgs
                  if (parameterType == void.class || parameterType == boolean.class) { // pzopt: luaJitDirectArgs
                     return false; // pzopt: luaJitDirectArgs
                  } // pzopt: luaJitDirectArgs
                  continue; // pzopt: luaJitDirectArgs
               } // pzopt: luaJitDirectArgs
               if (o instanceof Boolean && parameterType == boolean.class) { // pzopt: luaJitDirectArgs
                  continue; // pzopt: luaJitDirectArgs
               } // pzopt: luaJitDirectArgs
            } else if (o == null) { // pzopt: luaJitDirectArgs
               continue; // pzopt: luaJitDirectArgs
            } // pzopt: luaJitDirectArgs
            return false; // pzopt: luaJitDirectArgs
         } // pzopt: luaJitDirectArgs
      } // pzopt: luaJitDirectArgs
      return true; // pzopt: luaJitDirectArgs
   } // pzopt: luaJitDirectArgs

   /** pzopt: luaJavaFast, the generated call's result, or -1 (not generated yet, not covered, or this call needs the stock path). */
   public int pzoptTryFast(LuaCallFrame callFrame, int nArguments) { // pzopt: luaJavaFast
      pzopt.LuaJavaFast f = this.pzoptFast; // pzopt: luaJavaFast
      if (f != null) { // pzopt: luaJavaFast
         return f.call(callFrame, nArguments); // pzopt: luaJavaFast
      } // pzopt: luaJavaFast
      if (++this.pzoptCalls == pzopt.LuaJavaFast.THRESHOLD) { // pzopt: luaJavaFast
         pzopt.LuaJavaFast.queue(this, this.manager, this.clazz, this.caller, this.hasSelf, this.parameterTypes, this.hasVarargs, this.needsReturnValues); // pzopt: luaJavaFast
      } // pzopt: luaJavaFast
      return -1; // pzopt: luaJavaFast
   } // pzopt: luaJavaFast

   public void pzoptSetFast(pzopt.LuaJavaFast f) { // pzopt: luaJavaFast
      this.pzoptFast = f; // pzopt: luaJavaFast
   } // pzopt: luaJavaFast

   public boolean isAllInt() {
      if (this.parameterTypes != null && this.parameterTypes.length != 0) {
         for (Class<?> parameterType : this.parameterTypes) {
            if (parameterType != int.class) {
               return false;
            }
         }

         return true;
      } else {
         return false;
      }
   }
}
