package se.krka.kahlua.integration.expose;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.LuaCallFrame;

public class MultiLuaJavaInvoker implements JavaFunction {
   private final List<LuaJavaInvoker> invokers = new ArrayList<>();
   private static final Comparator<? super LuaJavaInvoker> COMPARATOR = new Comparator<LuaJavaInvoker>() {
      public int compare(LuaJavaInvoker o1, LuaJavaInvoker o2) {
         if (o2.getNumMethodParams() == o1.getNumMethodParams()) {
            boolean allInt1 = o1.isAllInt();
            boolean allInt2 = o2.isAllInt();
            return allInt1 ? 1 : (allInt2 ? -1 : 0);
         } else {
            return o2.getNumMethodParams() - o1.getNumMethodParams();
         }
      }
   };

   private int pzoptHits; // pzopt: luaJavaFast
   private int pzoptMisses; // pzopt: luaJavaFast

   public int call(LuaCallFrame callFrame, int nArguments) {
      if (pzopt.LuaJavaFast.ON && pzopt.LuaJavaFast.active() && this.pzoptMisses >= 0) { // pzopt: luaJavaFast
         int r = this.pzoptTryFast(callFrame, nArguments); // pzopt: luaJavaFast, -1 = the stock passes below
         if (r >= 0) { // pzopt: luaJavaFast
            this.pzoptHits++; // pzopt: luaJavaFast
            return r; // pzopt: luaJavaFast
         } // pzopt: luaJavaFast
         if (++this.pzoptMisses > 1024 && this.pzoptMisses > 4 * this.pzoptHits) { // pzopt: luaJavaFast, mostly shapes it does not cover
            this.pzoptMisses = -1; // pzopt: luaJavaFast, off for this function
         } // pzopt: luaJavaFast
      } // pzopt: luaJavaFast
      MethodArguments methodArguments = null;
      int inS = this.invokers.size();
      int best = -1;

      for (int n = 0; n < inS; n++) {
         LuaJavaInvoker invoker = this.invokers.get(n);
         if (invoker.matchesArgumentTypes(callFrame, nArguments)) {
            methodArguments = invoker.prepareCall(callFrame, nArguments);
            boolean bValid = methodArguments.isValid();
            if (bValid) {
               int val = invoker.call(methodArguments);
               ReturnValues.put(methodArguments.getReturnValues());
               MethodArguments.put(methodArguments);
               return val;
            }

            best = n;
            break;
         }
      }

      if (best == -1) {
         for (int n = 0; n < inS; n++) {
            LuaJavaInvoker invoker = this.invokers.get(n);
            if (invoker.matchesArgumentTypesOrPrimitives(callFrame, nArguments)) {
               methodArguments = invoker.prepareCall(callFrame, nArguments);
               boolean bValid = methodArguments.isValid();
               if (bValid) {
                  int val = invoker.call(methodArguments);
                  ReturnValues.put(methodArguments.getReturnValues());
                  MethodArguments.put(methodArguments);
                  return val;
               }

               best = n;
               break;
            }
         }
      }

      for (int n = 0; n < inS; n++) {
         if (n != best) {
            LuaJavaInvoker invoker = this.invokers.get(n);
            methodArguments = invoker.prepareCall(callFrame, nArguments);
            boolean bValid = methodArguments.isValid();
            if (bValid) {
               int val = invoker.call(methodArguments);
               ReturnValues.put(methodArguments.getReturnValues());
               MethodArguments.put(methodArguments);
               return val;
            }

            MethodArguments.put(methodArguments);
         }
      }

      if (methodArguments != null) {
         methodArguments.assertValid();
         MethodArguments.put(methodArguments);
      }

      throw new RuntimeException("No implementation found for function: " + this.getMethodSignature(callFrame, nArguments) + ")");
   }

   /**
    * pzopt: luaJavaFast. The overload stock's first two passes pick (the first with exactly the argument classes, else the
    * first that takes them as primitives), called through its generated class; -1 when that one has none yet or this call
    * needs its stock path (then the stock passes run from the start: nothing was changed).
    */
   private int pzoptTryFast(LuaCallFrame callFrame, int nArguments) { // pzopt: luaJavaFast
      int inS = this.invokers.size(); // pzopt: luaJavaFast
      for (int n = 0; n < inS; n++) { // pzopt: luaJavaFast
         LuaJavaInvoker invoker = this.invokers.get(n); // pzopt: luaJavaFast
         if (invoker.matchesArgumentTypes(callFrame, nArguments)) { // pzopt: luaJavaFast
            return invoker.pzoptTryFast(callFrame, nArguments); // pzopt: luaJavaFast
         } // pzopt: luaJavaFast
      } // pzopt: luaJavaFast
      for (int n = 0; n < inS; n++) { // pzopt: luaJavaFast
         LuaJavaInvoker invoker = this.invokers.get(n); // pzopt: luaJavaFast
         if (invoker.matchesArgumentTypesOrPrimitives(callFrame, nArguments)) { // pzopt: luaJavaFast
            return invoker.pzoptTryFast(callFrame, nArguments); // pzopt: luaJavaFast
         } // pzopt: luaJavaFast
      } // pzopt: luaJavaFast
      return -1; // pzopt: luaJavaFast
   } // pzopt: luaJavaFast

   /** pzopt: luaJitDirectArgs, the overload pzoptTryFast would call for argument values x (stock's first two passes), or null (none, or this function's fast path is off). */
   public LuaJavaInvoker pzoptSelect(Object[] x) { // pzopt: luaJitDirectArgs
      if (this.pzoptMisses < 0) { // pzopt: luaJitDirectArgs
         return null; // pzopt: luaJitDirectArgs
      } // pzopt: luaJitDirectArgs
      int inS = this.invokers.size(); // pzopt: luaJitDirectArgs
      for (int n = 0; n < inS; n++) { // pzopt: luaJitDirectArgs
         if (this.invokers.get(n).pzoptMatches(x, false)) { // pzopt: luaJitDirectArgs
            return this.invokers.get(n); // pzopt: luaJitDirectArgs
         } // pzopt: luaJitDirectArgs
      } // pzopt: luaJitDirectArgs
      for (int n = 0; n < inS; n++) { // pzopt: luaJitDirectArgs
         if (this.invokers.get(n).pzoptMatches(x, true)) { // pzopt: luaJitDirectArgs
            return this.invokers.get(n); // pzopt: luaJitDirectArgs
         } // pzopt: luaJitDirectArgs
      } // pzopt: luaJitDirectArgs
      return null; // pzopt: luaJitDirectArgs
   } // pzopt: luaJitDirectArgs

   public String getMethodSignature(LuaCallFrame callFrame, int nArguments) {
      String methodName = this.getMethodName();
      String methodArgs = this.getParametersString(callFrame, nArguments);
      return methodName + "(" + methodArgs + ")";
   }

   public String getMethodName() {
      return !this.invokers.isEmpty() ? this.invokers.get(0).toString() : "!noname!";
   }

   public String getParametersString(LuaCallFrame callFrame, int nArguments) {
      StringBuilder returnVal = new StringBuilder();

      for (int i = 0; i < nArguments; i++) {
         Object o = callFrame.get(i);
         Class<?> parameterType = o != null ? o.getClass() : null;
         if (!returnVal.isEmpty()) {
            returnVal.append(", ");
         }

         returnVal.append(parameterType);
         returnVal.append(' ');
         returnVal.append(o);
      }

      return returnVal.toString();
   }

   public void addInvoker(LuaJavaInvoker invoker) {
      if (!this.invokers.contains(invoker)) {
         this.invokers.add(invoker);
         Collections.sort(this.invokers, COMPARATOR);
      }
   }

   public List<LuaJavaInvoker> getInvokers() {
      return this.invokers;
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) {
         return true;
      } else if (o != null && this.getClass() == o.getClass()) {
         MultiLuaJavaInvoker that = (MultiLuaJavaInvoker)o;
         return this.invokers.equals(that.invokers);
      } else {
         return false;
      }
   }

   @Override
   public int hashCode() {
      return this.invokers.hashCode();
   }

   @Override
   public String toString() {
      return this.getMethodName();
   }
}
