package zombie.Lua;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaCallFrame;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Platform;
import se.krka.kahlua.vm.Prototype;
import zombie.UsedFromLua;
import zombie.debug.DebugType;
import zombie.util.IPooledObject;
import zombie.util.Pool;
import zombie.util.PooledObject;

@UsedFromLua
public final class LuaEventManager implements JavaFunction {
   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
   static {
      pzopt.Overrides.onClassLoaded("zombie.Lua.LuaEventManager");
   }

   public static final ArrayList<LuaClosure> OnTickCallbacks = new ArrayList<LuaClosure>();
   private static Object[][] a1 = new Object[1][1];
   private static Object[][] a2 = new Object[1][2];
   private static Object[][] a3 = new Object[1][3];
   private static Object[][] a4 = new Object[1][4];
   private static Object[][] a5 = new Object[1][5];
   private static Object[][] a6 = new Object[1][6];
   private static Object[][] a7 = new Object[1][7];
   private static Object[][] a8 = new Object[1][8];
   private static int a1index;
   private static int a2index;
   private static int a3index;
   private static int a4index;
   private static int a5index;
   private static int a6index;
   private static int a7index;
   private static int a8index;
   private static final ArrayList<Event> EventList = new ArrayList<Event>();
   private static final HashMap<String, Event> EventMap = new HashMap<String, Event>();
   private static final ArrayList<QueuedEvent> QueuedEvents = new ArrayList<QueuedEvent>();

   private static boolean IsMainThread() {
      return LuaManager.thread.debugOwnerThread == Thread.currentThread();
   }

   private static void AddQueuedEvent(QueuedEvent qe) {
      synchronized (QueuedEvents) {
         QueuedEvents.add(qe);
      }
   }

   private static void QueueEvent(Event e) {
      QueuedEvent qe = QueuedEvent.EventPool.alloc();
      qe.e = e;
      LuaEventManager.AddQueuedEvent(qe);
   }

   private static void QueueEvent(Event e, Object p1) {
      QueuedEvent qe = QueuedEvent.EventPool.alloc();
      qe.e = e;
      qe.a.add(p1);
      LuaEventManager.AddQueuedEvent(qe);
   }

   private static void QueueEvent(Event e, Object p1, Object p2) {
      QueuedEvent qe = QueuedEvent.EventPool.alloc();
      qe.e = e;
      qe.a.add(p1);
      qe.a.add(p2);
      LuaEventManager.AddQueuedEvent(qe);
   }

   private static void QueueEvent(Event e, Object p1, Object p2, Object p3) {
      QueuedEvent qe = QueuedEvent.EventPool.alloc();
      qe.e = e;
      qe.a.add(p1);
      qe.a.add(p2);
      qe.a.add(p3);
      LuaEventManager.AddQueuedEvent(qe);
   }

   private static void QueueEvent(Event e, Object p1, Object p2, Object p3, Object p4) {
      QueuedEvent qe = QueuedEvent.EventPool.alloc();
      qe.e = e;
      qe.a.add(p1);
      qe.a.add(p2);
      qe.a.add(p3);
      qe.a.add(p4);
      LuaEventManager.AddQueuedEvent(qe);
   }

   private static void QueueEvent(Event e, Object p1, Object p2, Object p3, Object p4, Object p5) {
      QueuedEvent qe = QueuedEvent.EventPool.alloc();
      qe.e = e;
      qe.a.add(p1);
      qe.a.add(p2);
      qe.a.add(p3);
      qe.a.add(p4);
      qe.a.add(p5);
      LuaEventManager.AddQueuedEvent(qe);
   }

   private static void QueueEvent(Event e, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6) {
      QueuedEvent qe = QueuedEvent.EventPool.alloc();
      qe.e = e;
      qe.a.add(p1);
      qe.a.add(p2);
      qe.a.add(p3);
      qe.a.add(p4);
      qe.a.add(p5);
      qe.a.add(p6);
      LuaEventManager.AddQueuedEvent(qe);
   }

   private static void QueueEvent(Event e, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) {
      QueuedEvent qe = QueuedEvent.EventPool.alloc();
      qe.e = e;
      qe.a.add(p1);
      qe.a.add(p2);
      qe.a.add(p3);
      qe.a.add(p4);
      qe.a.add(p5);
      qe.a.add(p6);
      qe.a.add(p7);
      LuaEventManager.AddQueuedEvent(qe);
   }

   private static void QueueEvent(Event e, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8) {
      QueuedEvent qe = QueuedEvent.EventPool.alloc();
      qe.e = e;
      qe.a.add(p1);
      qe.a.add(p2);
      qe.a.add(p3);
      qe.a.add(p4);
      qe.a.add(p5);
      qe.a.add(p6);
      qe.a.add(p7);
      qe.a.add(p8);
      LuaEventManager.AddQueuedEvent(qe);
   }

   public static void RunQueuedEvents() {
      synchronized (QueuedEvents) {
         LuaEventManager.RunQueuedEventsInternal();
      }
   }

   private static void RunQueuedEventsInternal() {
      for (int i = 0; i < QueuedEvents.size(); ++i) {
         QueuedEvent qe = QueuedEvents.get(i);
         switch (qe.a.size()) {
            case 0:
               qe.e.trigger(LuaManager.env, LuaManager.caller, null);
               break;
            case 1:
               LuaEventManager.RunQueuedEvent(qe, a1index, a1);
               break;
            case 2:
               LuaEventManager.RunQueuedEvent(qe, a2index, a2);
               break;
            case 3:
               LuaEventManager.RunQueuedEvent(qe, a3index, a3);
               break;
            case 4:
               LuaEventManager.RunQueuedEvent(qe, a4index, a4);
               break;
            case 5:
               LuaEventManager.RunQueuedEvent(qe, a5index, a5);
               break;
            case 6:
               LuaEventManager.RunQueuedEvent(qe, a6index, a6);
               break;
            case 7:
               LuaEventManager.RunQueuedEvent(qe, a7index, a7);
               break;
            case 8:
               LuaEventManager.RunQueuedEvent(qe, a8index, a8);
         }

         QueuedEvent.EventPool.release((IPooledObject)qe);
      }

      QueuedEvents.clear();
   }

   private static void RunQueuedEvent(QueuedEvent qe, int index, Object[][] ax) {
      if (index == ax.length) {
         ax = (Object[][])Arrays.copyOf(ax, ax.length * 2);

         for (int n = index; n < ax.length; ++n) {
            ax[n] = new Object[3];
         }
      }

      Object[] a = ax[index];

      for (int i = 0; i < qe.a.size(); ++i) {
         a[i] = qe.a.get(i);
      }

      ++index;

      try {
         qe.e.trigger(LuaManager.env, LuaManager.caller, a);
      } catch (Throwable throwable) {
         for (int i = 0; i < qe.a.size(); ++i) {
            a[i] = null;
         }

         qe.e = null;
         qe.a.clear();
         throw throwable;
      }

      for (int i = 0; i < qe.a.size(); ++i) {
         a[i] = null;
      }

      qe.e = null;
      qe.a.clear();
   }

   /** pzopt: luaSilentSkip (zombie redesign, f8-zomb2), game thread: whether any Lua function listens to this event now. */
   public static boolean pzoptHasListeners(String event) { // pzopt
      synchronized (EventMap) { // pzopt
         Event e = EventMap.get(event); // pzopt
         return e != null && !e.callbacks.isEmpty(); // pzopt
      } // pzopt
   } // pzopt

   private static Event checkEvent(String event) {
      Event e = EventMap.get(event);
      if (e == null) {
         DebugType.Lua.println("LuaEventManager: adding unknown event \"" + event + "\"");
         e = LuaEventManager.AddEvent(event);
      }

      if (e.callbacks.isEmpty()) {
         return null;
      } else {
         return e;
      }
   }

   public static void triggerEvent(String event) {
      // pzopt: entityUpdateParallel. A frame worker mid-batch never dispatches a Lua event: the main-thread path
      // below writes the shared static argument slots a1..a8/a1index..a8index and the off-thread path takes the
      // EventMap monitor and queues into a shared pool. With entityUpdateLuaReplay (default on) the dispatch is
      // captured and the game thread replays it in queue order after the join; with it off, counted and dropped,
      // first occurrence logged with its stack.
      if (pzopt.UpdateBatch.onWorkerNow()) { // pzopt: entityUpdateParallel
         pzopt.UpdateBatch.captureLuaEvent(event); // pzopt: entityUpdateLuaReplay -- captured on the worker, replayed in queue order on the game thread after the join (counted and dropped when the key is off)
         return; // pzopt: entityUpdateParallel
      } // pzopt: entityUpdateParallel

      synchronized (EventMap) {
         if (LuaManager.env == null) {
            return;
         }

         Event e = LuaEventManager.checkEvent(event);
         if (e == null) {
            return;
         }

         if (!LuaEventManager.IsMainThread()) {
            LuaEventManager.QueueEvent(e);
            return;
         }

         e.trigger(LuaManager.env, LuaManager.caller, null);
      }
   }

   public static void triggerEvent(String event, Object param1) {
      if (pzopt.UpdateBatch.onWorkerNow()) { // pzopt: entityUpdateParallel, see triggerEvent(String)
         pzopt.UpdateBatch.captureLuaEvent(event, param1); // pzopt: entityUpdateLuaReplay -- captured on the worker, replayed in queue order on the game thread after the join (counted and dropped when the key is off)
         return; // pzopt: entityUpdateParallel
      } // pzopt: entityUpdateParallel

      synchronized (EventMap) {
         if (LuaManager.env == null) {
            return;
         }

         Event e = LuaEventManager.checkEvent(event);
         if (e == null) {
            return;
         }

         if (!LuaEventManager.IsMainThread()) {
            LuaEventManager.QueueEvent(e, param1);
            return;
         }

         if (a1index == a1.length) {
            a1 = (Object[][])Arrays.copyOf(a1, a1.length * 2);

            for (int n = a1index; n < a1.length; ++n) {
               LuaEventManager.a1[n] = new Object[1];
            }
         }

         Object[] a = a1[a1index];
         a[0] = param1;
         ++a1index;

         try {
            e.trigger(LuaManager.env, LuaManager.caller, a);
         } finally {
            --a1index;
            a[0] = null;
         }
      }
   }

   public static void triggerEventGarbage(String event, Object param1) {
      LuaEventManager.triggerEvent(event, param1);
   }

   public static void triggerEventUnique(String event, Object param1) {
      LuaEventManager.triggerEvent(event, param1);
   }

   public static void triggerEvent(String event, Object param1, Object param2) {
      if (pzopt.UpdateBatch.onWorkerNow()) { // pzopt: entityUpdateParallel, see triggerEvent(String)
         pzopt.UpdateBatch.captureLuaEvent(event, param1, param2); // pzopt: entityUpdateLuaReplay -- captured on the worker, replayed in queue order on the game thread after the join (counted and dropped when the key is off)
         return; // pzopt: entityUpdateParallel
      } // pzopt: entityUpdateParallel

      synchronized (EventMap) {
         Event e = LuaEventManager.checkEvent(event);
         if (e == null) {
            return;
         }

         if (!LuaEventManager.IsMainThread()) {
            LuaEventManager.QueueEvent(e, param1, param2);
            return;
         }

         if (a2index == a2.length) {
            a2 = (Object[][])Arrays.copyOf(a2, a2.length * 2);

            for (int n = a2index; n < a2.length; ++n) {
               LuaEventManager.a2[n] = new Object[2];
            }
         }

         Object[] a = a2[a2index];
         a[0] = param1;
         a[1] = param2;
         ++a2index;

         try {
            e.trigger(LuaManager.env, LuaManager.caller, a);
         } finally {
            --a2index;
            a[0] = null;
            a[1] = null;
         }
      }
   }

   public static void triggerEventGarbage(String event, Object param1, Object param2) {
      LuaEventManager.triggerEvent(event, param1, param2);
   }

   public static void triggerEvent(String event, Object param1, Object param2, Object param3) {
      if (pzopt.UpdateBatch.onWorkerNow()) { // pzopt: entityUpdateParallel, see triggerEvent(String)
         pzopt.UpdateBatch.captureLuaEvent(event, param1, param2, param3); // pzopt: entityUpdateLuaReplay -- captured on the worker, replayed in queue order on the game thread after the join (counted and dropped when the key is off)
         return; // pzopt: entityUpdateParallel
      } // pzopt: entityUpdateParallel

      synchronized (EventMap) {
         Event e = LuaEventManager.checkEvent(event);
         if (e == null) {
            return;
         }

         if (!LuaEventManager.IsMainThread()) {
            LuaEventManager.QueueEvent(e, param1, param2, param3);
            return;
         }

         if (a3index == a3.length) {
            a3 = (Object[][])Arrays.copyOf(a3, a3.length * 2);

            for (int n = a3index; n < a3.length; ++n) {
               LuaEventManager.a3[n] = new Object[3];
            }
         }

         Object[] a = a3[a3index];
         a[0] = param1;
         a[1] = param2;
         a[2] = param3;
         ++a3index;

         try {
            e.trigger(LuaManager.env, LuaManager.caller, a);
         } finally {
            --a3index;
            a[0] = null;
            a[1] = null;
            a[2] = null;
         }
      }
   }

   public static void triggerEventGarbage(String event, Object param1, Object param2, Object param3) {
      LuaEventManager.triggerEvent(event, param1, param2, param3);
   }

   public static void triggerEvent(String event, Object param1, Object param2, Object param3, Object param4) {
      if (pzopt.UpdateBatch.onWorkerNow()) { // pzopt: entityUpdateParallel, see triggerEvent(String)
         pzopt.UpdateBatch.captureLuaEvent(event, param1, param2, param3, param4); // pzopt: entityUpdateLuaReplay -- captured on the worker, replayed in queue order on the game thread after the join (counted and dropped when the key is off)
         return; // pzopt: entityUpdateParallel
      } // pzopt: entityUpdateParallel

      synchronized (EventMap) {
         Event e = LuaEventManager.checkEvent(event);
         if (e == null) {
            return;
         }

         if (!LuaEventManager.IsMainThread()) {
            LuaEventManager.QueueEvent(e, param1, param2, param3, param4);
            return;
         }

         if (a4index == a4.length) {
            a4 = (Object[][])Arrays.copyOf(a4, a4.length * 2);

            for (int n = a4index; n < a4.length; ++n) {
               LuaEventManager.a4[n] = new Object[4];
            }
         }

         Object[] a = a4[a4index];
         a[0] = param1;
         a[1] = param2;
         a[2] = param3;
         a[3] = param4;
         ++a4index;

         try {
            e.trigger(LuaManager.env, LuaManager.caller, a);
         } finally {
            --a4index;
            a[0] = null;
            a[1] = null;
            a[2] = null;
            a[3] = null;
         }
      }
   }

   public static void triggerEventGarbage(String event, Object param1, Object param2, Object param3, Object param4) {
      LuaEventManager.triggerEvent(event, param1, param2, param3, param4);
   }

   public static void triggerEvent(String event, Object param1, Object param2, Object param3, Object param4, Object param5) {
      if (pzopt.UpdateBatch.onWorkerNow()) { // pzopt: entityUpdateParallel, see triggerEvent(String)
         pzopt.UpdateBatch.captureLuaEvent(event, param1, param2, param3, param4, param5); // pzopt: entityUpdateLuaReplay -- captured on the worker, replayed in queue order on the game thread after the join (counted and dropped when the key is off)
         return; // pzopt: entityUpdateParallel
      } // pzopt: entityUpdateParallel

      synchronized (EventMap) {
         Event e = LuaEventManager.checkEvent(event);
         if (e == null) {
            return;
         }

         if (!LuaEventManager.IsMainThread()) {
            LuaEventManager.QueueEvent(e, param1, param2, param3, param4, param5);
            return;
         }

         if (a5index == a5.length) {
            a5 = (Object[][])Arrays.copyOf(a5, a5.length * 2);

            for (int n = a5index; n < a5.length; ++n) {
               LuaEventManager.a5[n] = new Object[5];
            }
         }

         Object[] a = a5[a5index];
         a[0] = param1;
         a[1] = param2;
         a[2] = param3;
         a[3] = param4;
         a[4] = param5;
         ++a5index;

         try {
            e.trigger(LuaManager.env, LuaManager.caller, a);
         } finally {
            --a5index;
            a[0] = null;
            a[1] = null;
            a[2] = null;
            a[3] = null;
            a[4] = null;
         }
      }
   }

   public static void triggerEvent(String event, Object param1, Object param2, Object param3, Object param4, Object param5, Object param6) {
      if (pzopt.UpdateBatch.onWorkerNow()) { // pzopt: entityUpdateParallel, see triggerEvent(String)
         pzopt.UpdateBatch.captureLuaEvent(event, param1, param2, param3, param4, param5, param6); // pzopt: entityUpdateLuaReplay -- captured on the worker, replayed in queue order on the game thread after the join (counted and dropped when the key is off)
         return; // pzopt: entityUpdateParallel
      } // pzopt: entityUpdateParallel

      synchronized (EventMap) {
         Event e = LuaEventManager.checkEvent(event);
         if (e == null) {
            return;
         }

         if (!LuaEventManager.IsMainThread()) {
            LuaEventManager.QueueEvent(e, param1, param2, param3, param4, param5, param6);
            return;
         }

         if (a6index == a6.length) {
            a6 = (Object[][])Arrays.copyOf(a6, a6.length * 2);

            for (int n = a6index; n < a6.length; ++n) {
               LuaEventManager.a6[n] = new Object[6];
            }
         }

         Object[] a = a6[a6index];
         a[0] = param1;
         a[1] = param2;
         a[2] = param3;
         a[3] = param4;
         a[4] = param5;
         a[5] = param6;
         ++a6index;

         try {
            e.trigger(LuaManager.env, LuaManager.caller, a);
         } finally {
            --a6index;
            a[0] = null;
            a[1] = null;
            a[2] = null;
            a[3] = null;
            a[4] = null;
            a[5] = null;
         }
      }
   }

   public static void triggerEvent(String event, Object param1, Object param2, Object param3, Object param4, Object param5, Object param6, Object param7) {
      if (pzopt.UpdateBatch.onWorkerNow()) { // pzopt: entityUpdateParallel, see triggerEvent(String)
         pzopt.UpdateBatch.captureLuaEvent(event, param1, param2, param3, param4, param5, param6, param7); // pzopt: entityUpdateLuaReplay -- captured on the worker, replayed in queue order on the game thread after the join (counted and dropped when the key is off)
         return; // pzopt: entityUpdateParallel
      } // pzopt: entityUpdateParallel

      synchronized (EventMap) {
         Event e = LuaEventManager.checkEvent(event);
         if (e == null) {
            return;
         }

         if (!LuaEventManager.IsMainThread()) {
            LuaEventManager.QueueEvent(e, param1, param2, param3, param4, param5, param6, param7);
            return;
         }

         if (a7index == a7.length) {
            a7 = (Object[][])Arrays.copyOf(a7, a7.length * 2);

            for (int n = a7index; n < a7.length; ++n) {
               LuaEventManager.a7[n] = new Object[7];
            }
         }

         Object[] a = a7[a7index];
         a[0] = param1;
         a[1] = param2;
         a[2] = param3;
         a[3] = param4;
         a[4] = param5;
         a[5] = param6;
         a[6] = param7;
         ++a7index;

         try {
            e.trigger(LuaManager.env, LuaManager.caller, a);
         } finally {
            --a7index;
            a[0] = null;
            a[1] = null;
            a[2] = null;
            a[3] = null;
            a[4] = null;
            a[5] = null;
            a[6] = null;
         }
      }
   }

   public static void triggerEvent(String event, Object param1, Object param2, Object param3, Object param4, Object param5, Object param6, Object param7, Object param8) {
      if (pzopt.UpdateBatch.onWorkerNow()) { // pzopt: entityUpdateParallel, see triggerEvent(String)
         pzopt.UpdateBatch.captureLuaEvent(event, param1, param2, param3, param4, param5, param6, param7, param8); // pzopt: entityUpdateLuaReplay -- captured on the worker, replayed in queue order on the game thread after the join (counted and dropped when the key is off)
         return; // pzopt: entityUpdateParallel
      } // pzopt: entityUpdateParallel

      synchronized (EventMap) {
         Event e = LuaEventManager.checkEvent(event);
         if (e == null) {
            return;
         }

         if (!LuaEventManager.IsMainThread()) {
            LuaEventManager.QueueEvent(e, param1, param2, param3, param4, param5, param6, param7, param8);
            return;
         }

         if (a8index == a8.length) {
            a8 = (Object[][])Arrays.copyOf(a8, a8.length * 2);

            for (int n = a8index; n < a8.length; ++n) {
               LuaEventManager.a8[n] = new Object[8];
            }
         }

         Object[] a = a8[a8index];
         a[0] = param1;
         a[1] = param2;
         a[2] = param3;
         a[3] = param4;
         a[4] = param5;
         a[5] = param6;
         a[6] = param7;
         a[7] = param8;
         ++a8index;

         try {
            e.trigger(LuaManager.env, LuaManager.caller, a);
         } finally {
            --a8index;
            a[0] = null;
            a[1] = null;
            a[2] = null;
            a[3] = null;
            a[4] = null;
            a[5] = null;
            a[6] = null;
            a[7] = null;
         }
      }
   }

   public static Event AddEvent(String name) {
      Event event = EventMap.get(name);
      if (event != null) {
         return event;
      } else {
         event = new Event(name, EventList.size());
         EventList.add(event);
         EventMap.put(name, event);
         Object o = LuaManager.env.rawget("Events");
         if (o instanceof KahluaTable) {
            KahluaTable table = (KahluaTable)o;
            event.register(LuaManager.platform, table);
         } else {
            DebugType.Lua.error("ERROR: 'Events' table not found or not a table");
         }

         return event;
      }
   }

   private static void AddEvents() {
      LuaEventManager.AddEvent("OnGameBoot");
      LuaEventManager.AddEvent("OnPreGameStart");
      LuaEventManager.AddEvent("OnTick");
      LuaEventManager.AddEvent("OnTickEvenPaused");
      LuaEventManager.AddEvent("OnRenderUpdate");
      LuaEventManager.AddEvent("OnFETick");
      LuaEventManager.AddEvent("OnGameStart");
      LuaEventManager.AddEvent("OnPreUIDraw");
      LuaEventManager.AddEvent("OnPostUIDraw");
      LuaEventManager.AddEvent("OnCharacterCollide");
      LuaEventManager.AddEvent("OnKeyStartPressed");
      LuaEventManager.AddEvent("OnKeyPressed");
      LuaEventManager.AddEvent("OnContextKey");
      LuaEventManager.AddEvent("OnObjectCollide");
      LuaEventManager.AddEvent("OnNPCSurvivorUpdate");
      LuaEventManager.AddEvent("OnPlayerUpdate");
      LuaEventManager.AddEvent("OnZombieUpdate");
      LuaEventManager.AddEvent("OnZombieCreate");
      LuaEventManager.AddEvent("OnTriggerNPCEvent");
      LuaEventManager.AddEvent("OnMultiTriggerNPCEvent");
      LuaEventManager.AddEvent("OnLoadMapZones");
      LuaEventManager.AddEvent("OnLoadedMapZones");
      LuaEventManager.AddEvent("OnAddBuilding");
      LuaEventManager.AddEvent("OnCreateLivingCharacter");
      LuaEventManager.AddEvent("OnChallengeQuery");
      LuaEventManager.AddEvent("OnClickedAnimalForContext");
      LuaEventManager.AddEvent("OnFillInventoryObjectContextMenu");
      LuaEventManager.AddEvent("OnPreFillInventoryObjectContextMenu");
      LuaEventManager.AddEvent("OnFillWorldObjectContextMenu");
      LuaEventManager.AddEvent("OnPreFillWorldObjectContextMenu");
      LuaEventManager.AddEvent("OnRefreshInventoryWindowContainers");
      LuaEventManager.AddEvent("OnGamepadConnect");
      LuaEventManager.AddEvent("OnGamepadDisconnect");
      LuaEventManager.AddEvent("OnJoypadActivate");
      LuaEventManager.AddEvent("OnJoypadActivateUI");
      LuaEventManager.AddEvent("OnJoypadBeforeDeactivate");
      LuaEventManager.AddEvent("OnJoypadDeactivate");
      LuaEventManager.AddEvent("OnJoypadBeforeReactivate");
      LuaEventManager.AddEvent("OnJoypadReactivate");
      LuaEventManager.AddEvent("OnJoypadRenderUI");
      LuaEventManager.AddEvent("OnJoypadDebugRenderUIOptionSet");
      LuaEventManager.AddEvent("OnMakeItem");
      LuaEventManager.AddEvent("OnWeaponHitCharacter");
      LuaEventManager.AddEvent("OnWeaponSwing");
      LuaEventManager.AddEvent("OnWeaponHitTree");
      LuaEventManager.AddEvent("OnWeaponHitXp");
      LuaEventManager.AddEvent("OnWeaponSwingHitPoint");
      LuaEventManager.AddEvent("OnPlayerAttackFinished");
      LuaEventManager.AddEvent("OnLoginState");
      LuaEventManager.AddEvent("OnLoginStateSuccess");
      LuaEventManager.AddEvent("OnCharacterCreateStats");
      LuaEventManager.AddEvent("OnLoadSoundBanks");
      LuaEventManager.AddEvent("OnObjectLeftMouseButtonDown");
      LuaEventManager.AddEvent("OnObjectLeftMouseButtonUp");
      LuaEventManager.AddEvent("OnObjectRightMouseButtonDown");
      LuaEventManager.AddEvent("OnObjectRightMouseButtonUp");
      LuaEventManager.AddEvent("OnDoTileBuilding");
      LuaEventManager.AddEvent("OnDoTileBuilding2");
      LuaEventManager.AddEvent("OnDoTileBuilding3");
      LuaEventManager.AddEvent("RenderOpaqueObjectsInWorld");
      LuaEventManager.AddEvent("OnConnectFailed");
      LuaEventManager.AddEvent("OnConnected");
      LuaEventManager.AddEvent("OnDisconnect");
      LuaEventManager.AddEvent("OnConnectionStateChanged");
      LuaEventManager.AddEvent("OnQRReceived");
      LuaEventManager.AddEvent("OnGoogleAuthRequest");
      LuaEventManager.AddEvent("OnScoreboardUpdate");
      LuaEventManager.AddEvent("OnMouseMove");
      LuaEventManager.AddEvent("OnMouseDown");
      LuaEventManager.AddEvent("OnMouseUp");
      LuaEventManager.AddEvent("OnRightMouseDown");
      LuaEventManager.AddEvent("OnRightMouseUp");
      LuaEventManager.AddEvent("OnMouseWheel");
      LuaEventManager.AddEvent("OnNewSurvivorGroup");
      LuaEventManager.AddEvent("OnPlayerSetSafehouse");
      LuaEventManager.AddEvent("OnLoad");
      LuaEventManager.AddEvent("AddXP");
      LuaEventManager.AddEvent("LevelPerk");
      LuaEventManager.AddEvent("LogLevelPerk");
      LuaEventManager.AddEvent("OnSave");
      LuaEventManager.AddEvent("OnMainMenuEnter");
      LuaEventManager.AddEvent("OnGameStateEnter");
      LuaEventManager.AddEvent("OnPreMapLoad");
      LuaEventManager.AddEvent("OnPostFloorSquareDraw");
      LuaEventManager.AddEvent("OnPostFloorLayerDraw");
      LuaEventManager.AddEvent("OnPostTilesSquareDraw");
      LuaEventManager.AddEvent("OnPostTileDraw");
      LuaEventManager.AddEvent("OnPostWallSquareDraw");
      LuaEventManager.AddEvent("OnPostCharactersSquareDraw");
      LuaEventManager.AddEvent("OnCreateUI");
      LuaEventManager.AddEvent("OnMapLoadCreateIsoObject");
      LuaEventManager.AddEvent("OnCreateSurvivor");
      LuaEventManager.AddEvent("OnCreatePlayer");
      LuaEventManager.AddEvent("OnPlayerDeath");
      LuaEventManager.AddEvent("OnZombieDead");
      LuaEventManager.AddEvent("OnCharacterDeath");
      LuaEventManager.AddEvent("OnCharacterMeet");
      LuaEventManager.AddEvent("OnSpawnRegionsLoaded");
      LuaEventManager.AddEvent("OnPostMapLoad");
      LuaEventManager.AddEvent("OnAIStateExecute");
      LuaEventManager.AddEvent("OnAIStateEnter");
      LuaEventManager.AddEvent("OnAIStateExit");
      LuaEventManager.AddEvent("OnAIStateChange");
      LuaEventManager.AddEvent("OnPlayerMove");
      LuaEventManager.AddEvent("OnInitWorld");
      LuaEventManager.AddEvent("OnNewGame");
      LuaEventManager.AddEvent("OnIsoThumpableLoad");
      LuaEventManager.AddEvent("OnIsoThumpableSave");
      LuaEventManager.AddEvent("ReuseGridsquare");
      LuaEventManager.AddEvent("LoadGridsquare");
      LuaEventManager.AddEvent("LoadChunk");
      LuaEventManager.AddEvent("EveryOneMinute");
      LuaEventManager.AddEvent("EveryTenMinutes");
      LuaEventManager.AddEvent("EveryDays");
      LuaEventManager.AddEvent("EveryHours");
      LuaEventManager.AddEvent("OnDusk");
      LuaEventManager.AddEvent("OnDawn");
      LuaEventManager.AddEvent("OnEquipPrimary");
      LuaEventManager.AddEvent("OnEquipSecondary");
      LuaEventManager.AddEvent("OnClothingUpdated");
      LuaEventManager.AddEvent("OnWeatherPeriodStart");
      LuaEventManager.AddEvent("OnWeatherPeriodStage");
      LuaEventManager.AddEvent("OnWeatherPeriodComplete");
      LuaEventManager.AddEvent("OnWeatherPeriodStop");
      LuaEventManager.AddEvent("OnRainStart");
      LuaEventManager.AddEvent("OnRainStop");
      LuaEventManager.AddEvent("OnAmbientSound");
      LuaEventManager.AddEvent("OnWorldSound");
      LuaEventManager.AddEvent("OnResetLua");
      LuaEventManager.AddEvent("OnModsModified");
      LuaEventManager.AddEvent("OnSeeNewRoom");
      LuaEventManager.AddEvent("OnNewFire");
      LuaEventManager.AddEvent("OnFillContainer");
      LuaEventManager.AddEvent("OnChangeWeather");
      LuaEventManager.AddEvent("OnRenderTick");
      LuaEventManager.AddEvent("OnDestroyIsoThumpable");
      LuaEventManager.AddEvent("OnPostSave");
      LuaEventManager.AddEvent("OnResolutionChange");
      LuaEventManager.AddEvent("OnWaterAmountChange");
      LuaEventManager.AddEvent("OnClientCommand");
      LuaEventManager.AddEvent("OnServerCommand");
      LuaEventManager.AddEvent("OnProcessTransaction");
      LuaEventManager.AddEvent("OnProcessAction");
      LuaEventManager.AddEvent("OnContainerUpdate");
      LuaEventManager.AddEvent("OnObjectAdded");
      LuaEventManager.AddEvent("OnObjectAboutToBeRemoved");
      LuaEventManager.AddEvent("onLoadModDataFromServer");
      LuaEventManager.AddEvent("OnGameTimeLoaded");
      LuaEventManager.AddEvent("OnCGlobalObjectSystemInit");
      LuaEventManager.AddEvent("OnSGlobalObjectSystemInit");
      LuaEventManager.AddEvent("OnWorldMessage");
      LuaEventManager.AddEvent("OnKeyKeepPressed");
      LuaEventManager.AddEvent("SendCustomModData");
      LuaEventManager.AddEvent("ServerPinged");
      LuaEventManager.AddEvent("OnServerStarted");
      LuaEventManager.AddEvent("OnLoadedTileDefinitions");
      LuaEventManager.AddEvent("OnPostRender");
      LuaEventManager.AddEvent("DoSpecialTooltip");
      LuaEventManager.AddEvent("OnCoopJoinFailed");
      LuaEventManager.AddEvent("OnServerWorkshopItems");
      LuaEventManager.AddEvent("OnVehicleDamageTexture");
      LuaEventManager.AddEvent("OnCustomUIKey");
      LuaEventManager.AddEvent("OnCustomUIKeyPressed");
      LuaEventManager.AddEvent("OnCustomUIKeyReleased");
      LuaEventManager.AddEvent("OnTileObjectAdded");
      LuaEventManager.AddEvent("OnDeviceText");
      LuaEventManager.AddEvent("OnRadioInteraction");
      LuaEventManager.AddEvent("OnLoadRadioScripts");
      LuaEventManager.AddEvent("OnAcceptInvite");
      LuaEventManager.AddEvent("OnCoopServerMessage");
      LuaEventManager.AddEvent("OnReceiveUserlog");
      LuaEventManager.AddEvent("OnAdminMessage");
      LuaEventManager.AddEvent("ReceiveFactionInvite");
      LuaEventManager.AddEvent("AcceptedFactionInvite");
      LuaEventManager.AddEvent("ReceiveSafehouseInvite");
      LuaEventManager.AddEvent("AcceptedSafehouseInvite");
      LuaEventManager.AddEvent("ViewTickets");
      LuaEventManager.AddEvent("ViewBannedIPs");
      LuaEventManager.AddEvent("ViewBannedSteamIDs");
      LuaEventManager.AddEvent("SyncFaction");
      LuaEventManager.AddEvent("SyncFactionServer");
      LuaEventManager.AddEvent("RefreshCheats");
      LuaEventManager.AddEvent("OnReceiveItemListNet");
      LuaEventManager.AddEvent("OnMiniScoreboardUpdate");
      LuaEventManager.AddEvent("OnSafehousesChanged");
      LuaEventManager.AddEvent("OnWarUpdate");
      LuaEventManager.AddEvent("RequestTrade");
      LuaEventManager.AddEvent("AcceptedTrade");
      LuaEventManager.AddEvent("RequestMedicalCheck");
      LuaEventManager.AddEvent("AcceptedMedicalCheck");
      LuaEventManager.AddEvent("TradingUIAddItem");
      LuaEventManager.AddEvent("TradingUIRemoveItem");
      LuaEventManager.AddEvent("TradingUIUpdateState");
      LuaEventManager.AddEvent("OnGridBurnt");
      LuaEventManager.AddEvent("OnPreDistributionMerge");
      LuaEventManager.AddEvent("OnDistributionMerge");
      LuaEventManager.AddEvent("OnPostDistributionMerge");
      LuaEventManager.AddEvent("MngInvReceiveItems");
      LuaEventManager.AddEvent("OnTileRemoved");
      LuaEventManager.AddEvent("OnServerStartSaving");
      LuaEventManager.AddEvent("OnServerFinishSaving");
      LuaEventManager.AddEvent("OnMechanicActionDone");
      LuaEventManager.AddEvent("OnClimateTick");
      LuaEventManager.AddEvent("OnThunderEvent");
      LuaEventManager.AddEvent("OnEnterVehicle");
      LuaEventManager.AddEvent("OnSteamGameJoin");
      LuaEventManager.AddEvent("OnTabAdded");
      LuaEventManager.AddEvent("OnSetDefaultTab");
      LuaEventManager.AddEvent("OnTabRemoved");
      LuaEventManager.AddEvent("OnAddMessage");
      LuaEventManager.AddEvent("SwitchChatStream");
      LuaEventManager.AddEvent("OnChatWindowInit");
      LuaEventManager.AddEvent("OnAlertMessage");
      LuaEventManager.AddEvent("OnInitSeasons");
      LuaEventManager.AddEvent("OnClimateTickDebug");
      LuaEventManager.AddEvent("OnInitModdedWeatherStage");
      LuaEventManager.AddEvent("OnUpdateModdedWeatherStage");
      LuaEventManager.AddEvent("OnClimateManagerInit");
      LuaEventManager.AddEvent("OnPressReloadButton");
      LuaEventManager.AddEvent("OnPressRackButton");
      LuaEventManager.AddEvent("OnPressWalkTo");
      LuaEventManager.AddEvent("OnHitZombie");
      LuaEventManager.AddEvent("OnBeingHitByZombie");
      LuaEventManager.AddEvent("OnServerStatisticReceived");
      LuaEventManager.AddEvent("OnDynamicMovableRecipe");
      LuaEventManager.AddEvent("OnInitGlobalModData");
      LuaEventManager.AddEvent("OnReceiveGlobalModData");
      LuaEventManager.AddEvent("OnInitRecordedMedia");
      LuaEventManager.AddEvent("onUpdateIcon");
      LuaEventManager.AddEvent("preAddForageDefs");
      LuaEventManager.AddEvent("preAddSkillDefs");
      LuaEventManager.AddEvent("preAddZoneDefs");
      LuaEventManager.AddEvent("preAddCatDefs");
      LuaEventManager.AddEvent("preAddItemDefs");
      LuaEventManager.AddEvent("onAddForageDefs");
      LuaEventManager.AddEvent("onFillSearchIconContextMenu");
      LuaEventManager.AddEvent("onItemFall");
      LuaEventManager.AddEvent("OnTemplateTextInit");
      LuaEventManager.AddEvent("OnPlayerGetDamage");
      LuaEventManager.AddEvent("OnWeaponHitThumpable");
      LuaEventManager.AddEvent("OnFishingActionMPUpdate");
      LuaEventManager.AddEvent("OnThrowableExplode");
      LuaEventManager.AddEvent("OnSourceWindowFileReload");
      LuaEventManager.AddEvent("OnSpawnVehicleStart");
      LuaEventManager.AddEvent("OnSpawnVehicleEnd");
      LuaEventManager.AddEvent("OnMovingObjectCrop");
      LuaEventManager.AddEvent("OnOverrideSearchManager");
      LuaEventManager.AddEvent("OnSleepingTick");
      LuaEventManager.AddEvent("OnRolesReceived");
      LuaEventManager.AddEvent("OnNetworkUsersReceived");
      LuaEventManager.AddEvent("OnServerCustomizationDataReceived");
      LuaEventManager.AddEvent("OnDeadBodySpawn");
      LuaEventManager.AddEvent("OnAnimalTracks");
      LuaEventManager.AddEvent("OnItemFound");
      LuaEventManager.AddEvent("SetDragItem");
      LuaEventManager.AddEvent("OnSteamServerResponded");
      LuaEventManager.AddEvent("OnSteamServerResponded2");
      LuaEventManager.AddEvent("OnSteamServerFailedToRespond2");
      LuaEventManager.AddEvent("OnSteamRulesRefreshComplete");
      LuaEventManager.AddEvent("OnSteamRefreshInternetServers");
      LuaEventManager.AddEvent("OptionControllerButtonStyleChanged");
      LuaEventManager.AddEvent("OptionGamepadBindingPresetChanged");
   }

   public static void clear() {
   }

   public static void register(Platform platform, KahluaTable environment) {
      KahluaTable table = platform.newTable();
      environment.rawset("Events", table);
      LuaEventManager.AddEvents();
   }

   public static void reroute(Prototype prototype, LuaClosure luaClosure) {
      for (int n = 0; n < EventList.size(); ++n) {
         Event e = EventList.get(n);

         for (int m = 0; m < e.callbacks.size(); ++m) {
            LuaClosure c = (LuaClosure)e.callbacks.get(m);
            if (c.prototype.filename.equals(prototype.filename) && c.prototype.name.equals(prototype.name)) {
               e.callbacks.set(m, luaClosure);
            }
         }
      }
   }

   public static void Reset() {
      for (int n = 0; n < EventList.size(); ++n) {
         Event e = EventList.get(n);
         e.callbacks.clear();
      }

      EventList.clear();
      EventMap.clear();
   }

   public static void getEvents(ArrayList<Event> eventList, HashMap<String, Event> eventMap) {
      eventList.clear();
      eventList.addAll(EventList);
      eventMap.clear();
      eventMap.putAll(EventMap);
   }

   public static void setEvents(ArrayList<Event> eventList, HashMap<String, Event> eventMap) {
      EventList.clear();
      EventList.addAll(eventList);
      EventMap.clear();
      EventMap.putAll(eventMap);
   }

   public static void ResetCallbacks() {
      for (int n = 0; n < EventList.size(); ++n) {
         Event e = EventList.get(n);
         e.callbacks.clear();
      }
   }

   @Override
   public int call(LuaCallFrame callFrame, int nArguments) {
      return 0;
   }

   private int OnTick(LuaCallFrame callFrame, int nArguments) {
      return 0;
   }

   public static class QueuedEvent extends PooledObject {
      public static final Pool<QueuedEvent> EventPool = new Pool<QueuedEvent>(QueuedEvent::new);
      public Event e;
      public final ArrayList<Object> a = new ArrayList<Object>();
   }
}
