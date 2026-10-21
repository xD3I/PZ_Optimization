package pzopt;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;

/**
 * {@code gcSlack} (2026-10-14, Louisville 120 GC pass): the young collection moved into the frame's slack. With G1 a young
 * pause comes when eden is full, wherever the game thread is: mid-step, a 3-10 ms pause makes the step late and stalls
 * the render thread's frame too. At a frame cap the game thread parks at the end of most steps ({@link SlackWork}); when
 * eden will be full within {@code gcSlackFrames} steps and the predicted cost (the filler below plus the learned pause)
 * fits the slack left plus {@code gcSlackOverUs}, this producer fills the rest of eden with short-lived filler arrays (each
 * well under half a region, so in eden, never humongous) until the collector counts a collection. The collection G1
 * would have run a few steps later runs now, while the game thread would otherwise park. A collection due inside the
 * next step runs at the first slack call whatever the slack (it would come mid-step otherwise). Only the moment of the
 * collection changes: no game state is touched, the filler is garbage the collection frees.
 *
 * <p>Where eden ends: G1's young target is not what the Eden pool reports as committed (that is up to ~180 MB more on
 * the Louisville route), so the trigger point is learned from the collections themselves: the Eden pool's usage before
 * each young collection ({@code com.sun.management.GcInfo}), the median of the last five.
 *
 * <p>{@code gcSlackAlternate}: every other collection cycle stays natural, for a within-run A/B (the console's
 * "gc slack: collect" lines give the scheduled ones' times).
 */
public final class GcSlack {
   private GcSlack() {
   }

   private static MemoryPoolMXBean eden;
   private static GarbageCollectorMXBean young; // "G1 Young Generation": young and mixed pauses
   private static final ArrayList<GarbageCollectorMXBean> COLLECTORS = new ArrayList<>();
   private static boolean initDone;
   private static boolean usable;
   private static long regionBytes = 2L << 20;
   // Pacing.stepStartNs of the step last sampled: pending() runs once per job pick of SlackWork.run's loop, and SlackWork.run
   // itself runs once per game step (its first limiterWait pass), so the collection stays pending for the whole of that run
   private static long sampledStep = Long.MIN_VALUE;
   private static long lastUsed = -1L;
   private static long lastCount = -1L;
   private static long lastYoungTimeMs = -1L;
   private static long lastYoungCount = -1L;
   private static long remaining;
   private static long lastRemaining = -1L;
   private static boolean due;
   private static boolean urgent; // the collection would come inside the next step
   private static double perStep = 1 << 20;
   private static boolean skipCycle; // gcSlackAlternate: this cycle stays natural
   private static final long[] CAPS = new long[5]; // eden used before the last young collections
   private static int capN;
   private static long capEst = -1L;
   private static double pauseNs = 3_000_000.0; // learned young pause (the collector's own time, ms resolution, averaged)
   private static double fillNsPerByte = 0.15; // learned filler cost (zeroing), ns a byte
   private static Object sink;
   // jdk.internal.misc.Unsafe.allocateUninitializedArray (the game's launcher exports jdk.internal.misc to the class path):
   // filler arrays nobody reads need no zeroing; on the route zeroing cost ~0.12 ns a byte, ~4 ms for 33 MB of eden
   private static java.lang.invoke.MethodHandle uninit;
   private static boolean uninitTried;
   public static long targetAfterUsed;
   public static long scheduled, urgentRuns, natural, missed, noCollect, fillerBytes, fillerNs, dueSteps, steps;
   private static long remBeforeNaturalSum, remBeforeNaturalN, remBeforeNaturalMax;

   static final SlackWork.Producer PRODUCER = new SlackWork.Producer() {
      @Override
      public boolean pending() {
         sample();
         if (due && !skipCycle && AFTER_SWAP && !SLACK_ROOM && Pacing.queueActive()) {
            requestAfterSwap(); // gcSlackAfterSwap: the pzopt-gc-after-swap thread runs it right after the render thread's next swap
            return false;
         }
         return due && !skipCycle;
      }

      @Override
      public long nextCostNs() {
         fitNow = false;
         if (urgent) {
            return 0L; // now, whatever the slack, beats mid-step
         }
         if (SLACK_ROOM && Pacing.queueActive()) {
            // gcSlackQueue=slack: in the game thread's slack, only while the render thread holds a frame whose swap is far
            // enough away for the filler and the pause (planned with the longest recent pause, relaxed to the mean over ~25
            // steps of waiting); the filler stops short of the swap if eden is larger than estimated
            long now = System.nanoTime();
            long t = Pacing.holdTargetNs();
            long pHi = asPauseMax() + 300_000L, pLo = (long)asPauseEwma + 300_000L;
            long plan = pHi - (pHi - Math.min(pHi, pLo)) * Math.min(roomWaitSteps, 25) / 25;
            if (t > 0L && t - now >= plan + 600_000L) {
               slackStopAt = t - plan - 200_000L;
               return 0L;
            }
            if (roomStep != Pacing.stepStartNs()) {
               roomStep = Pacing.stepStartNs();
               roomWaitSteps++;
            }
            return Long.MAX_VALUE;
         }
         if (Config.GC_SLACK_FIT && GtAb.on(GtAb.GC_FIT)) {
            if (fits()) {
               return 0L;
            }
            if (remaining > (long)Config.GC_SLACK_FIT_FALLBACK_MB << 20) {
               return Long.MAX_VALUE;
            }
            fallbacks++; // little eden left: the slack collection beats a natural one mid-step (gcSlackFitFallbackMb)
         }
         double cost = Math.min(pauseNs, 6_000_000.0) + Math.max(0L, remaining) * fillNsPerByte;
         return Math.max(0L, (long)cost - Config.GC_SLACK_OVER_US * 1000L);
      }

      @Override
      public int nextAgeFrames() {
         // ahead of the other slack jobs (a collection that misses the slack costs a whole pause mid-step, a deferred job
         // only waits a frame), but never the forced path: a natural collection comes anyway
         return Config.SLACK_MAX_WAIT_FRAMES - 1;
      }

      @Override
      public void runNext() {
         collect();
      }
   };

   private static void init() {
      initDone = true;
      try {
         for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getName().equals("G1 Eden Space")) {
               eden = p;
            }
         }
         for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            // young and mixed pauses ("G1 Young Generation"), full ones ("G1 Old Generation"); the concurrent cycle's count
            // ("G1 Concurrent GC": remark and cleanup) does not empty eden
            if (!g.getName().contains("Concurrent")) {
               COLLECTORS.add(g);
            }
            if (g.getName().equals("G1 Young Generation")) {
               young = g;
            }
         }
         try {
            String v = ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class).getVMOption("G1HeapRegionSize").getValue();
            regionBytes = Math.max(1L << 20, Long.parseLong(v));
         } catch (Throwable ignored) {
         }
         usable = eden != null && young != null && young instanceof com.sun.management.GarbageCollectorMXBean;
         Log.info("gc slack: " + (usable ? "on, region " + (regionBytes >> 20) + " MB, collect " + Config.GC_SLACK_FRAMES + " steps ahead, "
               + Config.GC_SLACK_OVER_US + " us over the slack allowed, " + (uninit() != null ? "uninitialised" : "zeroed") + " filler" + (Config.GC_SLACK_ALTERNATE ? ", every other cycle (A/B)" : "")
               : "off (not G1: no \"G1 Eden Space\" pool / \"G1 Young Generation\" collector)"));
      } catch (Throwable t) {
         usable = false;
         Log.warn("gc slack: off (" + t + ")");
      }
   }

   private static long gcCount() {
      long n = 0;
      for (int i = 0; i < COLLECTORS.size(); i++) {
         n += COLLECTORS.get(i).getCollectionCount();
      }
      return n;
   }

   /** After a collection: eden's usage before it (the trigger point) and the pause, learned. */
   private static void learnCollection() {
      try {
         com.sun.management.GcInfo gi = ((com.sun.management.GarbageCollectorMXBean)young).getLastGcInfo();
         if (gi != null) {
            MemoryUsage before = gi.getMemoryUsageBeforeGc().get("G1 Eden Space");
            if (before != null && before.getUsed() > 0L) {
               CAPS[capN++ % CAPS.length] = before.getUsed();
               int n = Math.min(capN, CAPS.length);
               long[] s = java.util.Arrays.copyOf(CAPS, n);
               java.util.Arrays.sort(s);
               capEst = s[n / 2];
            }
            // G1 sizes eden anew at every collection (on the route 20-40 regions, from the pause predictions): the Eden
            // pool's committed size right after the collection is that new target, the next trigger point
            MemoryUsage after = gi.getMemoryUsageAfterGc().get("G1 Eden Space");
            if (Config.GC_SLACK_TARGET_AFTER && after != null && after.getCommitted() >= regionBytes) {
               capEst = after.getCommitted();
               targetAfterUsed++;
            }
         }
         long t = young.getCollectionTime();
         long c = young.getCollectionCount();
         if (lastYoungTimeMs >= 0L && t >= lastYoungTimeMs && c > lastYoungCount) {
            // per collection: the collector's time (whole ms) over the collections since the last look
            double per = (t - lastYoungTimeMs) * 1_000_000.0 / (c - lastYoungCount);
            pauseNs = pauseNs * 0.8 + Math.min(per, 20_000_000.0) * 0.2;
         }
         lastYoungTimeMs = t;
         lastYoungCount = c;
      } catch (Throwable ignored) {
      }
   }

   /** Once a step: eden's fill, its rate per step, and whether the collection is due within gcSlackFrames steps. */
   private static void sample() {
      long step = Pacing.stepStartNs();
      if (step == sampledStep) {
         return;
      }
      sampledStep = step;
      if (!initDone) {
         init();
      }
      if (!usable) {
         due = false;
         return;
      }
      steps++;
      long fs = asFillSerial;
      boolean asTouched = fs != asFillSeen || asRequestBytes != 0L; // gcSlackAfterSwap's thread filled eden since the last sample
      asFillSeen = fs;
      MemoryUsage u = eden.getUsage();
      long used = u.getUsed();
      long count = gcCount();
      if (lastCount >= 0L && count != lastCount) {
         // a collection since the last step that this producer did not run (its own reset lastCount in collect)
         long as = asCollections;
         boolean byAfterSwap = as != asSeen; // gcSlackAfterSwap: the thread's own collection, not a natural one
         asSeen = as;
         if (byAfterSwap) {
            scheduled += count - lastCount;
         } else {
            natural += count - lastCount;
         }
         if (lastRemaining >= 0L && !byAfterSwap) {
            remBeforeNaturalSum += lastRemaining;
            remBeforeNaturalN++;
            remBeforeNaturalMax = Math.max(remBeforeNaturalMax, lastRemaining);
         }
         if (due && !skipCycle && !byAfterSwap) {
            missed++; // it was due but never ran
         }
         if (Config.GC_SLACK_FIT && count - lastCount == 1L) {
            // gcSlackFit: a natural pause counts towards the expected one too (whole ms from the collector, rounded up), so a
            // few long scheduled ones cannot keep the estimate high with no fit left to correct it
            try {
               com.sun.management.GcInfo gi = ((com.sun.management.GarbageCollectorMXBean)young).getLastGcInfo();
               if (gi != null && gi.getDuration() >= 0L) {
                  PAUSES[pauseN++ % PAUSES.length] = (gi.getDuration() + 1L) * 1_000_000L - 500_000L;
               }
            } catch (Throwable ignored) {
            }
         }
         learnCollection();
         newCycle();
      } else if (lastUsed >= 0L && used >= lastUsed && !asTouched) { // (gcSlackAfterSwap: the thread's filler is not the game's rate)
         perStep = perStep * 0.95 + (used - lastUsed) * 0.05;
      }
      long nc = asNoCollectBytes;
      if (nc != asNoCollectSeen) { // gcSlackAfterSwap: a filler that ran no collection: eden is larger than learned this cycle
         if (capEst > 0L) {
            capEst += nc - asNoCollectSeen;
         }
         asNoCollectSeen = nc;
      }
      if (lastYoungTimeMs < 0L) {
         lastYoungTimeMs = young.getCollectionTime();
         lastYoungCount = young.getCollectionCount();
      }
      lastUsed = used;
      lastCount = count;
      long cap = capEst > 0L ? Math.min(capEst, u.getCommitted()) : u.getCommitted();
      remaining = cap - used;
      lastRemaining = remaining;
      // due: a few steps from the end, or past gcSlackEarlyPct of eden (the streamer allocates in bursts of tens of MB, so
      // the per-step rate alone misses most collections: on Louisville the step before a natural one still showed ~40 MB)
      due = cap > 0L && capEst > 0L && (remaining <= (long)(perStep * Config.GC_SLACK_FRAMES) + regionBytes
            || used >= cap * Config.GC_SLACK_EARLY_PCT / 100L);
      urgent = due && remaining <= (long)(perStep * 1.5) + regionBytes;
      if (due) {
         dueSteps++;
      }
   }

   private static void newCycle() {
      skipCycle = Config.GC_SLACK_ALTERNATE && !skipCycle;
   }

   // ---- gcSlackFit (tail6 2026-10-14) ----
   private static final long[] PAUSES = new long[8]; // the last measured scheduled pauses (ns)
   private static int pauseN;
   private static long fitStep = Long.MIN_VALUE; // the step whose first fit check was counted
   private static boolean fitNow; // the collection about to run passed the fit test
   private static boolean fitAtStart; // it runs at the step's start (else in the slack)
   private static double fillEstNs = 1_500_000.0; // EWMA of the measured filler time before the collection
   private static double fillPeakNs = 2_000_000.0; // its decaying peak (0.9 a collection)
   private static double stepEstNs = 6_000_000.0; // EWMA of the game step's work up to its frame hand-off (Pacing.lastWorkNs)
   private static double workPeakNs = 6_000_000.0; // its decaying peak (0.99 a step)
   private static boolean collectedLastStep;
   public static long fitRuns, fitStartRuns, fitWaitSteps, fitRenderBound, fitGameBound, fitWaitNs;

   /**
    * gcSlackFit, game thread, at the start of a capped step (Pacing.stepStart). Under frameQueue a pause delays nothing when it
    * ends before the render thread's next swap with its submission done, or starts right after a swap (the render thread then has
    * a whole interval for one submission) and leaves the delayed step and its frame inside their limits ({@link
    * Pacing#planPause}). On Louisville D is about two intervals, so the swap comes about when a step starts: the collection runs
    * here, before the step's work; in the slack ({@link #fits}) the game thread can wait for a swap that comes before its deadline
    * (the drive: a short D).
    */
   static void stepStart() {
      long last = Pacing.lastWorkNs; // the last step's work up to its hand-off (not when it held this producer's collection)
      if (last > 0L && last < 100_000_000L && !collectedLastStep) {
         stepEstNs += 0.1 * (last - stepEstNs);
         workPeakNs = Math.max(workPeakNs * 0.99, last);
      }
      collectedLastStep = false;
      if (!Config.GC_SLACK_FIT_START || !GtAb.on(GtAb.GC_FIT) || !SlackWork.active()) {
         return;
      }
      sample();
      if (!due || skipCycle) {
         return;
      }
      long now = System.nanoTime();
      long fill = (long)Math.max(fillEstNs, 0.75 * fillPeakNs); // conservative: a longer filler would move the pause later
      long start = Pacing.planPause(now, fill, fitPauseNs(), Config.GC_SLACK_FIT_MARGIN_US * 1000L, workEstNs(), 0L, 300_000L);
      if (start >= 0L) {
         collectedLastStep = true;
         fitNow = true;
         fitAtStart = true;
         fitStartRuns++;
         collect();
      } else {
         countWait(true);
      }
   }

   /** gcSlackFit: the step's work before its hand-off, conservatively (its EWMA or 3/4 of its slowly decaying peak). */
   private static long workEstNs() {
      return (long)Math.max(stepEstNs, 0.75 * workPeakNs);
   }

   private static long fallbacks; // gcSlackFit: steps that fell back to the slack rule with little eden left
   private static long swapWaitTimeouts; // gcSlackFit: planned swaps not seen within 3 ms of their target

   private static void countWait(boolean renderBound) {
      long step = Pacing.stepStartNs();
      if (step != fitStep) {
         fitStep = step;
         fitWaitSteps++;
         if (renderBound) {
            fitRenderBound++;
         } else {
            fitGameBound++;
         }
      }
   }

   /** The pause a scheduled collection is expected to take: the 75th percentile of the last eight measured (4 ms until then). */
   private static long fitPauseNs() {
      int n = Math.min(pauseN, PAUSES.length);
      if (n < 3) {
         return 4_000_000L;
      }
      long[] s = java.util.Arrays.copyOf(PAUSES, n);
      java.util.Arrays.sort(s);
      return s[Math.min(n - 1, n * 3 / 4)];
   }

   /**
    * gcSlackFit: does the filler plus the expected pause end before the render thread's next swap (less gcSlackFitMarginUs) and
    * before this step's deadline + gcSlackFitOverUs.
    */
   private static boolean fits() {
      long now = System.nanoTime();
      long fill = (long)Math.max(fillEstNs, 0.75 * fillPeakNs); // conservative: a longer filler would move the pause later
      long deadline = SlackWork.currentDeadlineNs;
      long start = Pacing.planPause(now, fill, fitPauseNs(), Config.GC_SLACK_FIT_MARGIN_US * 1000L, workEstNs(), deadline,
            Math.max(0L, deadline - now - 300_000L));
      boolean ok = start >= 0L;
      if (ok && Config.GC_SLACK_FIT_OVER_US >= 0 && start + fitPauseNs() > deadline + Config.GC_SLACK_FIT_OVER_US * 1000L) {
         ok = false; // gcSlackFitOverUs: only where the game thread would idle anyway
      }
      if (!ok) {
         countWait(false);
      } else {
         fitNow = true;
         fitAtStart = false;
      }
      return ok;
   }

   /** Fill the rest of eden until the collector runs (bounded: eden left + sixteen regions, at most 80 MB). */
   private static void collect() {
      if (fitNow) {
         long w0 = System.nanoTime(); // gcSlackFit: a pause planned after a swap waits for that swap, then fills
         if (!Pacing.waitPlannedSwap()) {
            swapWaitTimeouts++;
         }
         fitWaitNs += System.nanoTime() - w0;
      }
      long t0 = System.nanoTime();
      long c0 = gcCount();
      long limit = Math.min(Math.max(0L, remaining) + 16L * regionBytes, 80L << 20); // uninitialised filler: ~0.03-0.04 ns a byte, a wrong estimate costs ~3 ms at most
      int chunk = (int)Math.min((long)Config.GC_SLACK_FILL_CHUNK_KB << 10, regionBytes / 4L); // well below half a region: never humongous
      long filled = 0L;
      java.lang.invoke.MethodHandle mh = uninit();
      long maxIterNs = 0L; // gcSlackFit: the allocation that ran into the collection holds the pause
      long ti = t0;
      long stopAt = slackStopAt; // gcSlackQueue=slack: the filler stops this short of the held swap
      slackStopAt = Long.MAX_VALUE;
      while (filled < limit && ti < stopAt) {
         if (mh != null) {
            try {
               sink = (Object)mh.invokeExact(byte.class, chunk);
            } catch (Throwable t) {
               uninit = null;
               mh = null;
               sink = new byte[chunk];
            }
         } else {
            sink = new byte[chunk];
         }
         filled += chunk;
         long tn = System.nanoTime();
         maxIterNs = Math.max(maxIterNs, tn - ti);
         ti = tn;
         if ((filled & ((1L << 20) - 1L)) == 0L && gcCount() != c0) {
            break;
         }
      }
      sink = null;
      long t1 = System.nanoTime();
      long c1 = gcCount();
      fillerBytes += filled;
      fillerNs += t1 - t0;
      boolean wasUrgent = urgent;
      boolean wasFit = fitNow;
      fitNow = false;
      if (c1 != c0) {
         scheduled++;
         if (wasUrgent) {
            urgentRuns++;
         }
         if (wasFit) {
            fitRuns++;
         }
         PAUSES[pauseN++ % PAUSES.length] = maxIterNs; // gcSlackFit: the measured pause (the allocation that waited for it)
         if (SLACK_ROOM) {
            asPauses[asPauseN++ & 7] = maxIterNs; // gcSlackQueue=slack: the planner's pauses
            asPauseEwma += 0.2 * (maxIterNs - asPauseEwma);
            roomWaitSteps = 0;
         }
         fillEstNs += 0.2 * (Math.max(0L, (t1 - t0) - maxIterNs) - fillEstNs); // gcSlackFit: the filler's own time
         fillPeakNs = Math.max(fillPeakNs * 0.9, Math.max(0L, (t1 - t0) - maxIterNs));
         long pauseBefore = lastYoungTimeMs;
         learnCollection();
         long pauseMs = lastYoungTimeMs - pauseBefore;
         long fillOnly = (t1 - t0) - Math.max(0L, pauseMs) * 1_000_000L;
         if (filled > (1L << 20) && fillOnly > 0L) {
            fillNsPerByte = fillNsPerByte * 0.8 + (double)fillOnly / filled * 0.2;
         }
         newCycle();
      } else {
         noCollect++;
         if (capEst > 0L) {
            capEst += filled; // eden is larger than learned this cycle
         }
      }
      lastCount = c1;
      lastUsed = -1L;
      lastRemaining = -1L;
      due = false;
      urgent = false;
      if (Config.INSTRUMENT) {
         Log.info("gc slack: collect at " + System.currentTimeMillis() + " ms, " + (filled >> 10) + " KB filler, " + (t1 - t0) / 1000L
               + " us with the pause" + (wasUrgent ? " (urgent)" : "") + (c1 == c0 ? " (no collection)" : "")
               + ", pause " + maxIterNs / 1000L + " us, fill " + Math.max(0L, (t1 - t0) - maxIterNs) / 1000L + " us, mode "
               + (wasFit ? "fit " + (fitAtStart ? "start" : "slack") : wasUrgent ? "urgent" : SLACK_ROOM ? "room" : Config.GC_SLACK_FIT && GtAb.on(GtAb.GC_FIT) ? "fallback" : "v1"));
      }
   }

   // ---- gcSlackAfterSwap (f8-pace2 2026-10-15) ----
   /**
    * gcSlackAfterSwap: under frameQueue the scheduled young collection runs right after a held swap, from its own thread. With D
    * about two intervals the render thread holds a frame for ~6 ms of every 8.33, so a collection started in the game thread's
    * slack overlapped the hold's end and delayed the swap (run pace2-q1: 8 of 28 pauses, a fifth of the frames over 9 ms). Right
    * after a swap the render thread has a whole interval for its next submission (~2 ms + the ~4 ms pause fit in 8.33) and the
    * game thread is a few ms into a step whose frame is due D later, so the pause delays neither. Only the moment changes: the
    * filler is garbage, as in collect().
    */
   static final boolean HOLD = Config.FRAME_QUEUE && "hold".equalsIgnoreCase(Config.GC_SLACK_QUEUE);
   static final boolean SLACK_ROOM = Config.FRAME_QUEUE && "slack".equalsIgnoreCase(Config.GC_SLACK_QUEUE);
   static final boolean AFTER_SWAP = HOLD || SLACK_ROOM || Config.FRAME_QUEUE && "swap".equalsIgnoreCase(Config.GC_SLACK_QUEUE);
   private static long slackStopAt = Long.MAX_VALUE, roomStep = Long.MIN_VALUE; // game thread (gcSlackQueue=slack)
   private static int roomWaitSteps;
   private static double asPauseEwma = 4_500_000.0, asPausePeak = 4_500_000.0; // the thread's measured pauses (ns)
   private static final long[] asPauses = new long[8]; // the last eight (gcSlackQueue=hold plans with their maximum)
   private static int asPauseN;

   private static long asPauseMax() {
      if (asPauseN < 3) {
         return 6_000_000L;
      }
      long m = 0L;
      for (int i = 0; i < Math.min(8, asPauseN); i++) {
         m = Math.max(m, asPauses[i]);
      }
      return m;
   }
   private static volatile long asRequestBytes; // > 0: a request is pending (the filler limit)
   private static volatile long asCollections; // collections the thread caused (read by sample() for the attribution)
   private static long asSeen; // game thread
   private static volatile long asFillSerial, asNoCollectBytes; // fills done; filler bytes that ran no collection
   private static long asFillSeen, asNoCollectSeen; // game thread
   private static Thread asThread;
   private static Object asSink;
   public static volatile long asRuns, asNoSwap, asNoCollect, asFillNs, asWaitNs, asPauseOffNs;

   private static long asReqStep = Long.MIN_VALUE; // game thread: the step that posted the last request

   private static void requestAfterSwap() {
      long step = Pacing.stepStartNs();
      if (asRequestBytes != 0L || step == asReqStep) {
         return; // pending, or posted in this step (sample() sees the collection at the next step's start)
      }
      asReqStep = step;
      if (asThread == null) {
         asThread = new Thread(GcSlack::afterSwapLoop, "pzopt-gc-after-swap");
         asThread.setDaemon(true);
         asThread.setPriority(Thread.MAX_PRIORITY);
         asThread.start();
      }
      asRequestBytes = Math.max(1L, Math.min(Math.max(0L, remaining) + 16L * regionBytes, 80L << 20));
      java.util.concurrent.locks.LockSupport.unpark(asThread);
   }

   private static void afterSwapLoop() {
      Pacing.timerSlack1ns();
      while (true) {
         long limit = asRequestBytes;
         if (limit == 0L) {
            java.util.concurrent.locks.LockSupport.park();
            continue;
         }
         try {
            long w0 = System.nanoTime();
            long stopAt = Long.MAX_VALUE;
            long pauseEst = (long)Math.max(asPauseEwma, 0.8 * asPausePeak);
            if (HOLD) {
               // gcSlackQueue=hold: fill while the render thread holds a frame whose swap is far enough away for the filler and the
               // longest recent pause; a filler that has not reached the collection that pause + 0.2 ms before the swap stops
               // (garbage stays, the next request's filler is short). No hold with room: keep waiting (a natural collection may
               // come first, as without the queue); collecting anyway put 6 of 28 pauses over a swap (run pace2-q7)
               // the room asked for starts at the longest of the last eight pauses + 0.9 ms and relaxes over 200 ms of waiting
               // to their mean + 0.6 ms (a single long pause asked for holds that never came: 25 of 36 requests of run
               // pace2-q13 found none in a second, and 14 natural pauses followed)
               long pHi = asPauseMax() + 300_000L;
               long pLo = (long)asPauseEwma + 300_000L;
               long t = -1L, plan = pHi;
               for (long waited = 0L; waited < 1_000_000_000L && t < 0L; waited = System.nanoTime() - w0) {
                  plan = pHi - (pHi - Math.min(pHi, pLo)) * Math.min(waited, 200_000_000L) / 200_000_000L;
                  t = Pacing.waitHoldStart(plan + 600_000L, 10_000_000L);
               }
               if (t > 0L) {
                  stopAt = t - plan - 200_000L;
               } else {
                  asNoSwap++;
                  asRequestBytes = 0L;
                  continue; // a second without such a hold: drop the request (the game thread posts it again)
               }
            } else if (!Pacing.waitHeldSwap(40_000_000L)) {
               asNoSwap++; // no held swap within 40 ms (late frames): collect now, as the slack would
            } else {
               // gcSlackQueue=swap: the filler gets gcSlackQueueFillUs after the swap; one that has not reached the collection by then
               // stops (the garbage stays, the next request's filler after the next held swap is that much shorter), so the pause
               // starts within that time of the swap (filling to the collection whatever it took: 2.6 ms after the swap on average,
               // 7 of 28 pauses then delayed the next frame, run pace2-q6; stopping at the estimate: 1 of 25, pace2-q4)
               stopAt = System.nanoTime() + Config.GC_SLACK_QUEUE_FILL_US * 1000L;
            }
            long t0 = System.nanoTime();
            asWaitNs += t0 - w0;
            long c0 = gcCount();
            int chunk = (int)Math.min((long)Config.GC_SLACK_FILL_CHUNK_KB << 10, regionBytes / 4L);
            java.lang.invoke.MethodHandle mh = uninit;
            long filled = 0L, maxIter = 0L, ti = t0, pauseAt = t0;
            // until the collection comes: the eden estimate is often low (G1 resizes eden at every pause), and a filler that
            // stops short leaves eden full of garbage for a natural pause a few frames later at any moment (run pace2-q4: 19 of
            // 75); past the estimate each 32 MB costs ~1 ms more before the pause
            limit = Math.max(limit, 256L << 20);
            while (filled < limit && ti < stopAt) {
               if (mh != null) {
                  try {
                     asSink = (Object)mh.invokeExact(byte.class, chunk);
                  } catch (Throwable t) {
                     mh = null;
                     asSink = new byte[chunk];
                  }
               } else {
                  asSink = new byte[chunk];
               }
               filled += chunk;
               long tn = System.nanoTime();
               if (tn - ti > maxIter) {
                  maxIter = tn - ti;
                  pauseAt = ti;
               }
               ti = tn;
               if ((filled & ((1L << 20) - 1L)) == 0L && gcCount() != c0) {
                  break;
               }
            }
            asSink = null;
            asPauseOffNs += pauseAt - t0;
            long t1 = System.nanoTime();
            asFillNs += t1 - t0;
            if (gcCount() != c0) {
               asRuns++;
               asCollections++;
               asPauses[asPauseN++ & 7] = maxIter;
               asPauseEwma += 0.2 * (maxIter - asPauseEwma);
               asPausePeak = Math.max(asPausePeak * 0.9, maxIter);
            } else {
               asNoCollect++;
               asNoCollectBytes += filled;
            }
            asFillSerial++;
            if (Config.INSTRUMENT) {
               Log.info("gc slack: collect at " + System.currentTimeMillis() + " ms, " + (filled >> 10) + " KB filler, " + (t1 - t0) / 1000L
                     + " us with the pause, waited " + (t0 - w0) / 1000L + " us for the swap, pause " + maxIter / 1000L + " us " + (pauseAt - t0) / 1000L + " us after it"
                     + (stopAt != Long.MAX_VALUE ? ", ended " + (stopAt - (pauseAt + maxIter)) / 1000L + " us before the stop line" : "")
                     + (gcCount() == c0 ? " (no collection)" : "") + ", mode " + (HOLD ? "in-hold" : "after-swap"));
            }
         } catch (Throwable t) {
            Log.warn("gc slack: after-swap collection failed: " + t);
         }
         asRequestBytes = 0L;
      }
   }

   private static java.lang.invoke.MethodHandle uninit() {
      if (!uninitTried) {
         uninitTried = true;
         if (Config.GC_SLACK_UNINIT) {
            try {
               Class<?> uc = Class.forName("jdk.internal.misc.Unsafe");
               Object u = uc.getMethod("getUnsafe").invoke(null);
               uninit = java.lang.invoke.MethodHandles.lookup().findVirtual(uc, "allocateUninitializedArray",
                     java.lang.invoke.MethodType.methodType(Object.class, Class.class, int.class)).bindTo(u);
               Object probe = (Object)uninit.invokeExact(byte.class, 16);
               if (!(probe instanceof byte[])) {
                  uninit = null;
               }
            } catch (Throwable t) {
               uninit = null;
               Log.info("gc slack: zeroed filler (" + t + ")");
            }
         }
      }
      return uninit;
   }

   private static String collectors() {
      StringBuilder sb = new StringBuilder();
      for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
         sb.append(" | ").append(g.getName().replace(' ', '_')).append(" count=").append(g.getCollectionCount()).append(" ms=").append(g.getCollectionTime());
      }
      return sb.toString();
   }

   /** pzopt-bench.out's gc_slack=. */
   public static String describe() {
      return "scheduled=" + scheduled + " urgent=" + urgentRuns + " natural=" + natural + " missed=" + missed + " noCollect=" + noCollect
            + " steps=" + steps + " dueSteps=" + dueSteps
            + " edenLeftBeforeNaturalMB(avg/max)=" + (remBeforeNaturalN > 0 ? remBeforeNaturalSum / remBeforeNaturalN >> 20 : -1) + "/" + (remBeforeNaturalMax >> 20)
            + " edenCapEstMB=" + (capEst >> 20) + " targetAfter=" + targetAfterUsed + " fillerMB=" + (fillerBytes >> 20) + " fillerAndPauseMs=" + fillerNs / 1_000_000L
            + " pauseEstUs=" + (long)(pauseNs / 1000) + " fillNsPerByte=" + String.format("%.3f", fillNsPerByte)
            + " perStepKB=" + (long)(perStep / 1024)
            + (AFTER_SWAP ? " queue(" + Config.GC_SLACK_QUEUE + "): runs=" + asRuns + " noSwap=" + asNoSwap + " noCollect=" + asNoCollect + " fillAndPauseMs=" + asFillNs / 1_000_000L + " waitMs=" + asWaitNs / 1_000_000L + " pauseAfterStartUs(mean)=" + (asRuns + asNoCollect > 0 ? asPauseOffNs / 1000L / (asRuns + asNoCollect) : 0L) : "")
            + (Config.GC_SLACK_FIT ? " fit: runs=" + fitRuns + " atStart=" + fitStartRuns + " fillEstUs=" + (long)(fillEstNs / 1000) + " workEstUs=" + (long)(stepEstNs / 1000) + "/" + (long)(workPeakNs / 1000) + " waitSteps=" + fitWaitSteps + " (at start " + fitRenderBound + ", in slack " + fitGameBound + ") waitedMs=" + fitWaitNs / 1_000_000L + " fallbackSteps=" + fallbacks + " swapWaitTimeouts=" + swapWaitTimeouts + " planFail(target/held/pushed/twoIntervals/delayedFrame)=" + java.util.Arrays.toString(Pacing.planFail) + " submitEstUs=" + Pacing.submitEstUs() + " dUs=" + Pacing.queueTargetUs() + " pauseP75Us=" + fitPauseNs() / 1000L : "")
            + collectors();
   }
}
