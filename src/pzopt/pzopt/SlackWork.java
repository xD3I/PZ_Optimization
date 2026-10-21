package pzopt;

/**
 * {@code slackWork} (2026-10-05, Louisville 120 fps pass): deferrable game-thread work run in the frame's slack. At a frame
 * cap the game thread ends most steps early and parks until the next one (Pacing.limiterWait). Work that may wait a few
 * frames (a far chunk's hand-off, far containers' loot rolls, far zombies turning real) is queued by its producer and run
 * here, one job at a time, while the job's estimated cost fits the time left before the next step (less
 * {@code slackMarginUs}). The producers learn their costs as they run (a running mean per kind of job), and a job that has
 * waited {@code slackMaxWaitFrames} runs whatever its cost, so nothing starves. Uncapped (no slack) the producers keep their
 * in-frame budgets.
 */
public final class SlackWork {
   private SlackWork() {
   }

   /** A queue of deferred jobs. Game thread only. */
   public interface Producer {
      /** Is a job queued. */
      boolean pending();

      /** The next job's estimated cost (ns). */
      long nextCostNs();

      /** Frames the next job has waited. */
      int nextAgeFrames();

      /** Run the next job (it learns its own cost). */
      void runNext();

      /** Frames after which the next job runs whatever its cost (f8-handoff: the chunk hand-off may set its own). */
      default int maxWaitFrames() {
         return Config.SLACK_MAX_WAIT_FRAMES;
      }

      /** An overdue job runs in the next slack, light frame or not (f8-handoff: the chunk hand-off may ask for it). */
      default boolean forceAnyFrame() {
         return false;
      }
   }

   private static final java.util.ArrayList<Producer> PRODUCERS = new java.util.ArrayList<>();
   private static int frame;
   private static boolean gcRegistered;
   public static long jobs, forced, slackNs, usedNs, overruns, overruns1ms, overrunNs, failures;
   private static final java.util.HashMap<String, Integer> FORCED = new java.util.HashMap<>();
   private static long lastLogNs;
   private static long lastStepNs = Long.MIN_VALUE;
   /** The deadline of the current step's slack run (game thread). */
   static long currentDeadlineNs;

   public static void register(Producer p) {
      if (!PRODUCERS.contains(p)) {
         PRODUCERS.add(p);
      }
   }

   /** Is the slack scheduler on for this frame (a cap, the key on). */
   public static boolean active() {
      return Config.SLACK_WORK && Overrides.enabled() && Pacing.capIntervalNs() > 0L && GtAb.on(GtAb.SLACK_WORK);
   }

   /** The frame counter the producers age their jobs by (one tick per game step). */
   public static int frame() {
      return frame;
   }

   /**
    * Game thread, at the start of the limiter wait: run what fits before {@code deadlineNs}. The stock limiter loop calls
    * the wait on every pass (park, then spin), so only the first call of a game step counts a frame and runs jobs: a later
    * pass of the same step has less time left than the first had.
    */
   public static void run(long deadlineNs) {
      long step = Pacing.stepStartNs();
      if (step == lastStepNs) {
         return;
      }
      lastStepNs = step;
      frame++;
      currentDeadlineNs = deadlineNs; // gcSlackFit: the producers' view of this step's deadline
      if (Config.GC_SLACK && !gcRegistered) {
         gcRegistered = true;
         register(GcSlack.PRODUCER); // gcSlack: the young collection as a slack job
      }
      if (!active() || PRODUCERS.isEmpty()) {
         return;
      }

      long t0 = System.nanoTime();
      long margin = Config.SLACK_MARGIN_US * 1000L;
      slackNs += Math.max(0L, deadlineNs - t0);
      boolean ranForced = false;
      while (true) {
         long now = System.nanoTime();
         long left = deadlineNs - now - margin;
         Producer best = null;
         boolean force = false;
         for (int i = 0; i < PRODUCERS.size(); i++) {
            Producer p = PRODUCERS.get(i);
            if (!p.pending()) {
               continue;
            }
            int age = p.nextAgeFrames();
            int maxWait = p.maxWaitFrames(); // f8-handoff: per producer (the global slackMaxWaitFrames unless it sets its own)
            // overdue: into a light frame (half the interval still free) at once, into any frame only at four times the wait
            if (!ranForced && age >= maxWait
                  && (p.forceAnyFrame() || deadlineNs - now >= Pacing.capIntervalNs() / 2 || age >= 4 * maxWait)) {
               best = p;
               force = true;
               break;
            }
            if (p.nextCostNs() <= left && (best == null || p.nextAgeFrames() > best.nextAgeFrames())) {
               best = p;
            }
         }
         if (best == null) {
            break;
         }
         if (force) {
            ranForced = true; // at most one over-budget job a frame
            forced++;
            FORCED.merge(best.getClass().getName(), 1, Integer::sum);
         }
         try {
            best.runNext();
         } catch (Throwable t) { // a job's failure costs that job, not the game thread
            failures++;
            if (failures <= 5) {
               Log.warn("slack work: " + best.getClass().getName() + " failed: " + t);
            }
         }
         jobs++;
      }
      long end = System.nanoTime();
      usedNs += end - t0;
      if (end > deadlineNs) {
         overruns++;
         overrunNs += end - deadlineNs;
         if (end - deadlineNs > 1_000_000L) {
            overruns1ms++;
         }
      }
      if (Config.INSTRUMENT && t0 - lastLogNs > 10_000_000_000L) {
         lastLogNs = t0;
         Log.info("slack work: jobs " + jobs + " forced " + forced + " slack " + slackNs / 1_000_000L + " ms used " + usedNs / 1_000_000L + " ms, forced by producer " + FORCED + ", overruns " + overruns + " (> 1 ms " + overruns1ms + ", " + overrunNs / 1_000_000L + " ms)");
      }
   }

   /** A job kind's cost: running mean and decaying peak. */
   public static final class Cost {
      private double meanNs;
      private double peakNs;
      private final double peakShare;

      public Cost(double initialNs) {
         this(initialNs, 0.75);
      }

      /** peakShare 0: the mean alone (jobs of one size); above it, that share of a decaying peak bounds it from below. */
      public Cost(double initialNs, double peakShare) {
         this.meanNs = initialNs;
         this.peakNs = initialNs;
         this.peakShare = peakShare;
      }

      /** Conservative: the mean or peakShare of a slowly decaying peak, whichever is larger. */
      public long estimate() {
         return (long)Math.max(this.meanNs, this.peakNs * this.peakShare);
      }

      public void learn(long ns) {
         this.meanNs = this.meanNs * 0.8 + ns * 0.2;
         this.peakNs = Math.max(this.peakNs * 0.98, ns);
      }
   }
}
