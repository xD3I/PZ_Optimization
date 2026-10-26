package pzopt;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;

/**
 * {@code devLuaStreamJava} (lua-stream, 2026-10-25, idea 7's census): every Lua -> Java call (Kahlua MethodCaller.call) made
 * while a LoadGridsquare or LoadChunk handler runs (LuaStream.evBegin / evEnd), counted and timed per method (inclusive
 * of what the method calls, without Kahlua's own argument conversion), so the Java the streaming handlers spend their
 * time in can be read method by method. Dev only: {@link #on} is false without the key.
 */
public final class LuaStreamJava {
   private LuaStreamJava() {
   }

   /** Read by MethodCaller.call: the key is on and a stream event handler is running (game thread). */
   public static boolean on;
   private static final IdentityHashMap<Method, long[]> ROWS = new IdentityHashMap<>();
   private static long lastLogNs;

   static void enter() {
      if (Config.DEV_LUA_STREAM_JAVA) {
         on = true;
      }
   }

   static void exit() {
      on = false;
   }

   public static void call(Method m, long t0) {
      long ns = System.nanoTime() - t0;
      if (Thread.currentThread() != LuaStream.gameThread()) {
         return;
      }
      long[] r = ROWS.get(m);
      if (r == null) {
         r = new long[2];
         ROWS.put(m, r);
      }
      r[0]++;
      r[1] += ns;
   }

   /** Logs the top methods (every 30 s while rows come in, and on demand). */
   static void maybeLog(long now, boolean force) {
      if (!Config.DEV_LUA_STREAM_JAVA || ROWS.isEmpty() || (!force && now - lastLogNs < 30_000_000_000L)) {
         return;
      }
      lastLogNs = now;
      ArrayList<java.util.Map.Entry<Method, long[]>> l = new ArrayList<>(ROWS.entrySet());
      l.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
      long calls = 0, ns = 0;
      for (java.util.Map.Entry<Method, long[]> e : l) {
         calls += e.getValue()[0];
         ns += e.getValue()[1];
      }
      StringBuilder b = new StringBuilder("lua stream java: " + calls + " calls " + ns / 1_000_000L + " ms inside stream handlers (top: calls, ms, ns a call)");
      for (int i = 0; i < Math.min(60, l.size()); i++) {
         long[] v = l.get(i).getValue();
         Method m = l.get(i).getKey();
         b.append(String.format(java.util.Locale.ROOT, "%n  %9d %8.1f %6d  %s.%s(%d)", v[0], v[1] / 1e6, v[0] > 0 ? v[1] / v[0] : 0,
               m.getDeclaringClass().getSimpleName(), m.getName(), m.getParameterCount()));
      }
      Log.info(b.toString());
   }
}
