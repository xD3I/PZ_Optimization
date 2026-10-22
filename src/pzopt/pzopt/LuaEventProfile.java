package pzopt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.vm.LuaClosure;
import zombie.Lua.LuaManager;

/**
 * luaEventProfile (2026-09-23, dev / measurement): every Lua event handler call (zombie.Lua.Event.trigger) timed and
 * summed per event and handler (source file and first line), so the Lua side of a load can be read handler by handler;
 * JFR only sees the Kahlua interpreter. dump() logs the biggest rows; NoLoadingScreen calls it when the world becomes
 * visible and again when it is complete. A handler that fires another event is counted inclusive in both.
 */
public final class LuaEventProfile {
   public static final boolean ON = Config.LUA_EVENT_PROFILE;
   private static final HashMap<String, long[]> rows = new HashMap<>(); // calls, ns, max ns
   private static final HashMap<String, long[]> events = new HashMap<>();

   private LuaEventProfile() {
   }

   /** Event.trigger's handler call, timed when the profile is on. */
   public static void call(LuaCaller caller, LuaClosure closure, Object[] params, String event) {
      if (!ON) {
         if (ModLuaProfile.on) { // the benchmark's mod Lua profiler: the same call, counted, its event named for the sampler
            ModLuaProfile.callEvent(caller, LuaManager.thread, closure, params, event);
            return;
         }
         caller.protectedCallVoid(LuaManager.thread, closure, params);
         return;
      }
      long t0 = System.nanoTime();
      try {
         if (ModLuaProfile.on) {
            ModLuaProfile.callEvent(caller, LuaManager.thread, closure, params, event);
         } else {
            caller.protectedCallVoid(LuaManager.thread, closure, params);
         }
      } finally {
         long ns = System.nanoTime() - t0;
         String where = closure.prototype == null ? "?" : closure.prototype.filename + ":"
               + (closure.prototype.lines != null && closure.prototype.lines.length > 0 ? closure.prototype.lines[0] : 0)
               + (closure.prototype.name != null ? " " + closure.prototype.name : "");
         synchronized (rows) {
            add(rows, event + " | " + where, ns);
            add(events, event, ns);
         }
      }
   }

   private static void add(HashMap<String, long[]> m, String k, long ns) {
      long[] v = m.computeIfAbsent(k, x -> new long[3]);
      v[0]++;
      v[1] += ns;
      v[2] = Math.max(v[2], ns);
   }

   /** Logs the events and handlers by total time since the last dump, then starts over. */
   public static void dump(String when) {
      if (!ON) {
         return;
      }
      ArrayList<Map.Entry<String, long[]>> ev;
      ArrayList<Map.Entry<String, long[]>> rs;
      synchronized (rows) {
         ev = new ArrayList<>(events.entrySet());
         rs = new ArrayList<>(rows.entrySet());
         events.clear();
         rows.clear();
      }
      ev.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
      rs.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
      StringBuilder sb = new StringBuilder("lua events up to " + when + " (total ms, calls, max ms):");
      for (int i = 0; i < Math.min(20, ev.size()); i++) {
         long[] v = ev.get(i).getValue();
         sb.append(String.format("%n  event %-28s %8.1f %6d %7.1f", ev.get(i).getKey(), v[1] / 1e6, v[0], v[2] / 1e6));
      }
      for (int i = 0; i < Math.min(40, rs.size()); i++) {
         long[] v = rs.get(i).getValue();
         sb.append(String.format("%n  %8.1f %6d %7.1f  %s", v[1] / 1e6, v[0], v[2] / 1e6, rs.get(i).getKey()));
      }
      Log.info(sb.toString());
   }
}
