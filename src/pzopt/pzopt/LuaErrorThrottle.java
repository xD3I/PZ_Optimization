package pzopt;

import java.util.HashMap;

/**
 * luaErrorThrottle (2026-10-25, pzopt.LuaTick): a mod's event handler that throws every frame makes the game format and
 * print the same Java stack trace (ExceptionLogger: a caller-prefix stack walk + printException) and the same Lua stack
 * trace (KahluaThread.flushErrorMessage -> DebugLogStream.errorWithTraceOffset) every frame. With the key at N, the
 * first N identical errors of one handler are printed as stock, the rest counted (a line every 100: "repeated K times"),
 * so the console stays readable and the frame does not pay for the formatting. What the game counts is untouched:
 * ExceptionLogger's exception counter and popup, KahluaThread's errorCount and its error list (the debug error UI) see
 * every error. Only errors raised while LuaTick dispatches a handler (on the thread that dispatches it) are considered.
 *
 * <p>No static state that needs Config: DebugLogStream calls {@link #droppedLine} for every error line from the very
 * first log line of the boot, before anything else is up; the fast path is a null test.
 */
public final class LuaErrorThrottle {
   private LuaErrorThrottle() {
   }

   /** The handler being dispatched (set by LuaTick around its call when the key is on), and its thread. */
   static Object current;
   static Thread owner;
   static int limit;

   private static Throwable lastEx;
   private static boolean lastDrop;
   private static final HashMap<Key, int[]> counts = new HashMap<>();
   static long dropped; // census

   private record Key(Object handler, String what) {
      @Override
      public boolean equals(Object o) {
         return o instanceof Key k && k.handler == handler && k.what.equals(what);
      }

      @Override
      public int hashCode() {
         return System.identityHashCode(handler) * 31 + what.hashCode();
      }
   }

   /** ExceptionLogger: is this exception a repeat that is not printed (same answer for the same exception object). */
   public static boolean dropped(Throwable ex) {
      Object h = current;
      if (h == null || ex == null || Thread.currentThread() != owner) {
         return false;
      }
      if (ex == lastEx) {
         return lastDrop;
      }
      lastEx = ex;
      lastDrop = count(h, "ex " + ex.getClass().getName() + ": " + ex.getMessage());
      return lastDrop;
   }

   /** DebugLogStream.errorWithTraceOffset: is this error line a repeat that is not printed. */
   public static boolean droppedLine(Object format) {
      Object h = current;
      if (h == null || format == null || Thread.currentThread() != owner) {
         return false;
      }
      return count(h, "line " + format);
   }

   private static boolean count(Object h, String what) {
      if (counts.size() > 2000) {
         counts.clear();
      }
      int[] n = counts.computeIfAbsent(new Key(h, what), k -> new int[1]);
      n[0]++;
      if (n[0] <= limit) {
         return false;
      }
      dropped++;
      if ((n[0] - limit) % 100 == 0) {
         Object hh = current;
         current = null; // our own line must print
         try {
            String first = what.length() > 160 ? what.substring(0, 160) + "..." : what;
            Log.info("luaErrorThrottle: the same Lua error repeated " + n[0] + " times by " + hh + " (" + (n[0] - limit)
                  + " not printed): " + first.replace('\n', ' '));
         } finally {
            current = hh;
         }
      }
      return true;
   }
}
