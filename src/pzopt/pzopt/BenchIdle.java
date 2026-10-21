package pzopt;

/**
 * Technique 13/19's idle gate: before a pass's world is created the main menu waits until other programs leave the CPU
 * alone (machine load minus the game's under {@link #QUIET_PCT} % for {@link #QUIET_S} s, at most {@link #MAX_WAIT_S} s).
 * On the desktop a database import of another tool took 46-61 % of the CPU at the start of two Louisville passes (17 and
 * 43 fps against 101-110 for the quiet ones); a player's updater, browser or antivirus does the same. Sampled every
 * 500 ms on a daemon thread from the JDK's OperatingSystemMXBean (PDH on Windows: never on the game thread).
 */
final class BenchIdle {
   static final double QUIET_PCT = 10, QUIET_S = 3, MAX_WAIT_S = 90;
   private static volatile double background = Double.NaN;
   private static volatile long quietSinceNs, startedNs;
   private static Thread thread;

   private BenchIdle() {
   }

   static synchronized void start() {
      if (thread != null) {
         return;
      }
      startedNs = System.nanoTime();
      thread = new Thread(() -> {
         java.lang.management.OperatingSystemMXBean b = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
         if (!(b instanceof com.sun.management.OperatingSystemMXBean os)) {
            return;
         }
         while (true) {
            try {
               Thread.sleep(500L);
            } catch (InterruptedException e) {
               return;
            }
            double sys = os.getCpuLoad(), proc = os.getProcessCpuLoad();
            if (sys < 0 || proc < 0) {
               continue;
            }
            double bg = Math.max(0, 100 * (sys - proc));
            background = bg;
            if (bg < QUIET_PCT) {
               if (quietSinceNs == 0L) {
                  quietSinceNs = System.nanoTime();
               }
            } else {
               quietSinceNs = 0L;
            }
         }
      }, "pzopt-bench-idle");
      thread.setDaemon(true);
      thread.start();
   }

   /** "go|wait <tab> background % <tab> seconds waited": go once the machine was quiet long enough or the wait ran out. */
   static String poll() {
      start();
      long now = System.nanoTime();
      double waited = (now - startedNs) / 1e9;
      long q = quietSinceNs;
      boolean quiet = q != 0L && (now - q) / 1e9 >= QUIET_S;
      boolean go = quiet || waited >= MAX_WAIT_S;
      return (go ? "go" : "wait") + "\t" + String.format(java.util.Locale.ROOT, "%.0f\t%.0f", Double.isNaN(background) ? -1 : background, waited);
   }

   static double waitedSeconds() {
      return startedNs == 0L ? 0 : (System.nanoTime() - startedNs) / 1e9;
   }
}
