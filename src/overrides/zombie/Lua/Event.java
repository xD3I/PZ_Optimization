package zombie.Lua;

import java.util.ArrayList;
import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaCallFrame;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Platform;
import zombie.GameProfiler;
import zombie.GameProfiler.ProfileArea;
import zombie.core.logger.ExceptionLogger;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;

public final class Event {
   public static final int ADD = 0;
   public static final int NUM_FUNCTIONS = 1;
   private final Event.Add add;
   private final Event.Remove remove;
   public final ArrayList<LuaClosure> callbacks = new ArrayList<>();
   public String name;
   private final int index;
   private final boolean pzoptUiEvent; // pzopt: uiRetained, an event after which the UI shows something else
   public Object pzoptTick; // pzopt: luaTick, pzopt.LuaTick's state of this event (its handlers' schedule)

   public boolean trigger(KahluaTable env, LuaCaller caller, Object[] params) {
      if (this.pzoptUiEvent) { // pzopt: uiRetained, every UI element renders fresh
         pzopt.UiRetained.uiEvent(); // pzopt: uiRetained
      } // pzopt: uiRetained
      if (this.callbacks.isEmpty()) {
         return false;
      }
      if (pzopt.LuaTick.ON) { // pzopt: luaTick, the loop with the fast dispatch / the mod tick rate (stock behaviour while its keys are off)
         return pzopt.LuaTick.trigger(this, caller, params); // pzopt: luaTick
      } // pzopt: luaTick

      GameProfiler profiler = GameProfiler.getInstance();
      if (DebugOptions.instance.checks.slowLuaEvents.getValue()) {
         for (int n = 0; n < this.callbacks.size(); n++) {
            LuaClosure closure = this.callbacks.get(n);

            try {
               ProfileArea var20 = profiler.profile("Lua - " + this.name);

               try {
                  long start = System.nanoTime();
                  pzopt.LuaEventProfile.call(caller, closure, params, this.name); // pzopt: luaEventProfile
                  double delayMS = (System.nanoTime() - start) / 1000000.0;
                  if (delayMS > 250.0) {
                     DebugType.Lua.warn("SLOW Lua event callback %s %s %dms", new Object[]{closure.prototype.file, closure, (int)delayMS});
                  }
               } catch (Throwable var14) {
                  if (var20 != null) {
                     try {
                        var20.close();
                     } catch (Throwable var12) {
                        var14.addSuppressed(var12);
                     }
                  }

                  throw var14;
               }

               if (var20 != null) {
                  var20.close();
               }
            } catch (Exception ex) {
               ExceptionLogger.logException(ex);
            }

            if (!this.callbacks.contains(closure)) {
               n--;
            }
         }

         return true;
      } else {
         for (int n = 0; n < this.callbacks.size(); n++) {
            LuaClosure closure = this.callbacks.get(n);

            try {
               ProfileArea ex = profiler.profile("Lua - " + this.name);

               try {
                  pzopt.LuaEventProfile.call(caller, closure, params, this.name); // pzopt: luaEventProfile
               } catch (Throwable var16) {
                  if (ex != null) {
                     try {
                        ex.close();
                     } catch (Throwable var13) {
                        var16.addSuppressed(var13);
                     }
                  }

                  throw var16;
               }

               if (ex != null) {
                  ex.close();
               }
            } catch (Exception ex) {
               ExceptionLogger.logException(ex);
            }

            if (!this.callbacks.contains(closure)) {
               n--;
            }
         }

         return true;
      }
   }

   public Event(String name, int index) {
      this.index = index;
      this.name = name;
      this.pzoptUiEvent = pzopt.UiRetained.isUiEvent(name); // pzopt: uiRetained
      this.add = new Event.Add(this);
      this.remove = new Event.Remove(this);
   }

   public void register(Platform platform, KahluaTable environment) {
      KahluaTable table = platform.newTable();
      table.rawset("Add", this.add);
      table.rawset("Remove", this.remove);
      environment.rawset(this.name, table);
   }

   public static final class Add implements JavaFunction {
      Event e;

      public Add(Event e) {
         this.e = e;
      }

      public int call(LuaCallFrame callFrame, int nArguments) {
         if (LuaCompiler.rewriteEvents) {
            return 0;
         }

         if (callFrame.get(0) instanceof LuaClosure tab) {
            this.e.callbacks.add(tab);
         }

         return 0;
      }
   }

   public static final class Remove implements JavaFunction {
      Event e;

      public Remove(Event e) {
         this.e = e;
      }

      public int call(LuaCallFrame callFrame, int nArguments) {
         if (LuaCompiler.rewriteEvents) {
            return 0;
         }

         if (callFrame.get(0) instanceof LuaClosure tab) {
            this.e.callbacks.remove(tab);
         }

         return 0;
      }
   }
}
