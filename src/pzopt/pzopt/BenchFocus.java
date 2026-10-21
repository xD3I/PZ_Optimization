package pzopt;

/**
 * A benchmark pass's window takes the focus. On macOS a process that pzopt.Restart starts opens its window behind the
 * others (the Mac, 2026-10-18: every pass after the first ran unfocused, 100 % of its frames, so it was invalid), while
 * a player's own launch is frontmost. glfwFocusWindow activates the application and raises the window; it is called on
 * the render thread (the main thread on macOS, where GLFW must be called), once a second while the window is not
 * focused, during the first 30 s of a benchmark process and at most five times: a player who switches away during a
 * pass is never pulled back (that pass is reported as unfocused instead).
 */
final class BenchFocus {
   private static final long WINDOW_NS = 30_000_000_000L;
   private static final int MAX_TRIES = 5;
   private static long lastNs;
   private static int tries;
   private static boolean done;

   private BenchFocus() {
   }

   /** Render thread, right before the swap (Pacing.beforeSwap). */
   static void beforeSwap() {
      if (done) {
         return;
      }
      long now = System.nanoTime();
      if (now - lastNs < 1_000_000_000L) {
         return;
      }
      lastNs = now;
      long up = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() * 1_000_000L;
      if (up > WINDOW_NS || tries >= MAX_TRIES || !BenchPlan.active()) {
         done = true;
         return;
      }
      try {
         long window = org.lwjglx.opengl.Display.getWindow();
         if (window == 0L || org.lwjglx.opengl.Display.isActive()) {
            return;
         }
         tries++;
         org.lwjgl.glfw.GLFW.glfwFocusWindow(window);
         Log.info("benchmark: window not focused " + up / 1_000_000_000L + " s after start; focus requested (" + tries + "/" + MAX_TRIES + ")");
      } catch (Throwable t) {
         done = true;
         Log.warn("benchmark: focus request failed: " + t);
      }
   }
}
