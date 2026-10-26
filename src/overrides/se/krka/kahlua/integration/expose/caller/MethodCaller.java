package se.krka.kahlua.integration.expose.caller;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import se.krka.kahlua.integration.expose.ReturnValues;
import se.krka.kahlua.integration.processor.DescriptorUtil;
import zombie.core.logger.ExceptionLogger;
import zombie.ui.UIManager;

public class MethodCaller extends AbstractCaller {
   private final Method method;
   private final Object owner;
   private final boolean hasSelf;
   private final boolean hasReturnValue;
   private final boolean pzoptZombieWriter; // pzopt: luaZombieWriters=watch, a state-changing method (pzopt.LuaZombieWatch)

   public MethodCaller(Method method, Object owner, boolean hasSelf) {
      super(method.getParameterTypes(), method.isVarArgs());
      this.method = method;
      this.owner = owner;
      this.hasSelf = hasSelf;
      method.setAccessible(true);
      this.hasReturnValue = !method.getReturnType().equals(void.class);
      this.pzoptZombieWriter = pzopt.LuaZombieWatch.isWriter(method); // pzopt: luaZombieWriters=watch
      if (this.hasReturnValue && this.needsMultipleReturnValues()) {
         throw new IllegalArgumentException("Must have a void return type if first argument is a ReturnValues: got: " + method.getReturnType());
      }
   }

   @Override
   public void call(Object self, ReturnValues rv, Object[] params) throws IllegalArgumentException, IllegalAccessException, InvocationTargetException {
      if (!this.hasSelf) {
         self = this.owner;
      }

      int pzoptDepth = pzopt.UiDeps.on ? pzopt.UiDeps.enter() : -1; // pzopt: uiVerify, a Lua -> Java call of a recorded UI render
      if (this.pzoptZombieWriter && pzopt.LuaZombieWatch.target != null) { // pzopt: luaZombieWriters=watch, the write barrier
         pzopt.LuaZombieWatch.call(self); // pzopt: luaZombieWriters=watch
      } // pzopt: luaZombieWriters=watch
      long pzoptJ = pzopt.LuaStreamJava.on ? System.nanoTime() : 0L; // pzopt: devLuaStreamJava, the Java a LoadGridsquare / LoadChunk handler calls, counted and timed
      try {
         Object ret = this.method.invoke(self, params);
         if (pzoptDepth >= 0) { // pzopt: uiVerify
            pzopt.UiDeps.call(pzoptDepth, this.method, self, params, this.hasReturnValue, ret); // pzopt: uiVerify, logged for validation
         } // pzopt: uiVerify
         if (this.hasReturnValue) {
            rv.push(ret);
         }
      } catch (InvocationTargetException e) {
         pzopt.UiDeps.threw(pzoptDepth, this.method); // pzopt: uiVerify
         logException(e.getTargetException());
      } catch (Throwable e) {
         pzopt.UiDeps.threw(pzoptDepth, this.method); // pzopt: uiVerify
         logException(e);
      }
      pzopt.UiDeps.exit(pzoptDepth); // pzopt: uiVerify
      if (pzoptJ != 0L) { // pzopt: devLuaStreamJava
         pzopt.LuaStreamJava.call(this.method, pzoptJ); // pzopt
      } // pzopt
   }

   public Method pzoptMethod() { // pzopt: luaJavaFast
      return this.method; // pzopt: luaJavaFast
   } // pzopt: luaJavaFast

   public Object pzoptOwner() { // pzopt: luaJavaFast
      return this.owner; // pzopt: luaJavaFast
   } // pzopt: luaJavaFast

   public static void pzoptLogException(Throwable throwable) { // pzopt: luaJavaFast, an exception of the method called by pzopt.LuaJavaFast
      logException(throwable); // pzopt: luaJavaFast
   } // pzopt: luaJavaFast

   private static void logException(Throwable throwable) {
      UIManager.defaultthread.doStacktraceProper();
      UIManager.defaultthread.debugException(throwable);
      ExceptionLogger.logException(throwable);
   }

   @Override
   public boolean hasSelf() {
      return this.hasSelf;
   }

   @Override
   public String getDescriptor() {
      return DescriptorUtil.getDescriptor(this.method);
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) {
         return true;
      }

      if (o != null && this.getClass() == o.getClass()) {
         MethodCaller that = (MethodCaller)o;
         if (!this.method.equals(that.method)) {
            return false;
         } else {
            return this.owner != null ? this.owner.equals(that.owner) : that.owner == null;
         }
      } else {
         return false;
      }
   }

   @Override
   public int hashCode() {
      int result = this.method.hashCode();
      return 31 * result + (this.owner != null ? this.owner.hashCode() : 0);
   }
}
