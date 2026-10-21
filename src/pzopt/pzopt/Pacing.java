package pzopt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLCapabilities;
import zombie.GameWindow;
import zombie.ZomboidFileSystem;
import zombie.gameStates.GameLoadingState;

/**
 * Frame pacing for variable refresh rate displays.
 *
 * On a VRR display every frame is shown the moment it is ready, so the present cadence is the display cadence.
 * The frame limiter (GameWindow.mainThreadStep) paces the start of the game-thread step, i.e. the game time each
 * frame shows; the frame then reaches the screen after its own update + hand-off + render + GPU time, which varies
 * frame to frame. Motion on screen is smooth when the gap between two flips equals the game-time step between the
 * two frames. On a fixed-refresh display that variation hides behind the vsync grid; a VRR display shows it as is
 * (desktop, 100 fps cap, VRR on: steps 10.00 ms apart with 0.26 ms jitter, flips 3.1 ms off their step on average,
 * 54 % of frames off by more than 2 ms; harness/pacing.py "ON-SCREEN JUDDER").
 *
 * {@link #beforeSwap()} holds the swap until {@code step start + L}, L being a high percentile
 * ({@code presentPacingPct}) of how long recent frames took from step start to "ready":
 * <ul>
 * <li>{@code cpu}: ready = the render thread reached the swap. The GPU may still be drawing, so the flip then waits
 * for it: the flip lands 0.5-8 ms after the swap.</li>
 * <li>{@code gpu}: ready = the GPU finished the frame, read back a few frames later from a GL_TIMESTAMP query mapped
 * onto System.nanoTime; the render thread never blocks on the GPU. An on-time frame is complete when its swap is
 * issued, so it flips a fixed ~0.45 ms later.</li>
 * <li>{@code gpufinish}: the render thread waits for the frame's fence, then holds. Exact, but the render thread no
 * longer overlaps the GPU: at 100 fps it fell to 84 fps and the queue fed back into L (34 ms step-to-screen).</li>
 * </ul>
 * Frames later than L go as soon as ready. The hold never makes the render thread spend more than one cap interval
 * on a frame (it would fall behind the game thread and frames would queue), and there is no hold when uncapped.
 *
 * The same hooks log one row per presented frame to Zomboid/pzopt-pacing.out ({@code pacingLog}, on in instrumented
 * runs; harness/pacing.py): epoch_ms, step start, render-thread acquire, swap call, swap return, the hold and the
 * frame's GPU completion (System.nanoTime; rows are written {@link #LAG_ROWS} frames late so the GPU time is known).
 *
 * Threads: {@link #stepStart} and {@link #onPush} on the game thread, the rest on the render thread. Every pushed
 * frame is rendered (pushFrameDown waits for the ready slot), so a single-producer single-consumer ring of step
 * starts pairs each rendered frame with the game time it was built from.
 */
public final class Pacing {
   public static final boolean LOG = Config.PACING_LOG && Overrides.enabled();
   static final int OFF = 0, CPU = 1, GPU = 2, GPU_FINISH = 3, AUTO = 4;
   public static final int MODE = !Overrides.enabled() ? OFF : switch (Config.PRESENT_PACING.toLowerCase(Locale.ROOT)) {
      case "cpu" -> CPU;
      case "gpu", "true", "on" -> GPU;
      case "gpufinish" -> GPU_FINISH;
      case "auto" -> AUTO;
      default -> OFF;
   };
   private static final String[] NAMES = {"off", "cpu", "gpu", "gpufinish", "auto"};
   /** frameQueue: the render thread presents each capped frame at its limiter slot + D (see {@link #queueHold}). */
   public static final boolean QUEUE = Config.FRAME_QUEUE && Overrides.enabled();
   /** limiterCatchUp: missed cap slots the limiter makes up (0 = stock modulo). */
   public static final int CATCH_UP = !Overrides.enabled() ? 0 : Math.max(0, Config.LIMITER_CATCH_UP);
   private static final boolean ACTIVE = LOG || MODE != OFF || QUEUE;

   private static final int RING = 64;
   private static final long[] ring = new long[RING];
   private static final long[] ringSlot = new long[RING]; // frameQueue: the limiter slot each pushed frame serves
   private static final long[] ringPush = new long[RING]; // frameQueue: when the game thread arrived at the hand-off
   private static final boolean[] ringQueued = new boolean[RING]; // frameQueue: the pushed frame's step ran under the queue
   private static volatile int head; // written by the game thread
   private static volatile int tail; // written by the render thread

   private static volatile long stepStartNs;
   private static volatile long stepSlotNs;
   /** Game thread: the limiter's frame interval in ns, 0 when uncapped. */
   private static volatile long capIntervalNs;
   /** Game thread: the current step's frame goes through the frame queue (capped and {@link #queueAllowed}). */
   private static volatile boolean stepQueued;
   /** Game thread: why the queue is off for the current capped step ("" = on), logged when it changes. */
   private static String queueOffReason = null;
   /** Game thread: +1 each time the queue turns on (a world loaded, a menu closed); the render thread restarts a low cap's D on it. */
   private static volatile int queueOnCount;

   // --- render thread -------------------------------------------------------------------------
   /** The configured mode after the GL checks (AUTO stays AUTO); {@link #effectiveMode()} resolves AUTO per frame. */
   private static int mode = MODE;
   /** What AUTO means on this GL: gpu with timestamp queries, else cpu. */
   private static int autoMode = GPU;
   private static long frameSimNs;
   private static long frameSlotNs;
   private static long framePushNs;
   private static boolean frameQueued;
   private static long acquireNs;
   private static long swapCallNs;
   private static long waitNs;
   private static long frameNo;

   private static final int WINDOW = 120;
   private static final long[] lagWin = new long[WINDOW];
   private static final long[] sorted = new long[WINDOW];
   private static int lagCount;
   private static int lagPos;
   private static long targetLagNs;
   private static int framesSinceTarget;

   // GPU completion: one GL_TIMESTAMP query per frame, read back when available
   private static final int QUERIES = 8;
   private static int[] queryIds;
   private static final long[] queryFrame = new long[QUERIES];
   private static final long[] querySim = new long[QUERIES];
   private static boolean timestamps;
   // pacingQueryBuffer (tail2 pass 2026-10-13): the timestamps land in a persistently mapped buffer (GL_QUERY_BUFFER,
   // ARB_query_buffer_object) and are read from client memory, so the per-frame result polling no longer asks the driver
   // (each glGetQueryObject waited for NVIDIA's threaded driver queue: up to 9 ms render-thread stalls in texture-heavy frames)
   private static QueryBuf queryBuf;
   private static boolean glChecked;
   private static long gpuToCpuNs;
   private static long lastCalibrationNs;

   // log rows wait LAG_ROWS frames for their GPU completion time
   static final int LAG_ROWS = 10;
   private static final long[][] rows = new long[16][9];
   private static BufferedWriter log;
   private static boolean logFailed;
   private static long logStartNs;
   private static long logStartEpochMs;
   private static long lastFlushNs;
   private static final StringBuilder line = new StringBuilder(128);

   private Pacing() {
   }

   /** Game thread, at the start of a frame step (the time the frame's game state is built for). */
   public static void stepStart(long nowNs, long intervalNs) {
      stepStart(nowNs, intervalNs, nowNs);
   }

   /**
    * Game thread, at the start of a frame step; {@code slotNs} = the cap grid slot the step serves (the limiter's
    * deadline: nowNs when on time, earlier when the step starts late or catches up).
    */
   public static void stepStart(long nowNs, long intervalNs, long slotNs) {
      stepSlotNs = slotNs;
      GtAb.frame(); // devGtAlternate: this frame's A/B phase
      TorchSource.frameStart(); // devTorchSourceCycle: this frame's variant
      VisBlink.frame(); // devVisBlinkTrace: the last frame's visibility blinks
      AnimalPreload.drain(); // animalPreload (tail pass): preloaded action groups into the game's map
      stepStartNs = nowNs;
      capIntervalNs = intervalNs;
      stepQueued = QUEUE && intervalNs > 0L && queueAllowed(); // uncapped: false without asking anything
      if (Config.BURST_GOVERNOR && Overrides.enabled()) { // burstGovernor (f8-resid): is this a heavy step
         heavy = intervalNs > 0L && (nowNs - slotNs > Config.BURST_LATE_US * 1000L || lastWorkNs > intervalNs * Config.BURST_WORK_PCT / 100L)
               && GtAb.on(GtAb.BURST_GOVERNOR);
         if (heavy) {
            heavySteps++;
            heavyRun++;
         } else {
            heavyRun = 0;
         }
      }
      if (Config.GC_SLACK_FIT && Config.GC_SLACK && intervalNs > 0L) {
         GcSlack.stepStart(); // gcSlackFit: a due collection at the step's start when the render thread's and this frame's room allow it
      }
   }

   private static boolean heavy; // game thread only
   public static long heavySteps, spawnsHeld, addsHeld; // burstGovernor census (game thread)

   /** burstGovernor (f8-resid): the current step is heavy, deferrable work takes its burst budgets. Game thread. */
   public static boolean heavyStep() {
      return heavy;
   }

   private static int heavyRun; // heavy steps in a row, this one included (game thread)
   public static long heavyAddHolds; // census: heavy steps whose model adds were held (zombieModelAddHeavyEvery)

   /**
    * zombieModelAddHeavyEvery (f9-burst): this heavy step gives no zombie its 3D model; one heavy step in N still does, so a
    * scene that stays heavy keeps upgrading its zombies. Called once per step from IsoWorld.sceneCullZombies (the first
    * would-be add). Game thread.
    */
   public static boolean heavyAddHold() {
      int n = Config.ZOMBIE_MODEL_ADD_HEAVY_EVERY;
      if (n <= 0 || !heavy || heavyRun % n == 0) {
         return false;
      }
      heavyAddHolds++;
      return true;
   }

   /** Game thread: System.nanoTime() at the start of the current step (bakeDeadlinePct). */
   public static long stepStartNs() {
      return stepStartNs;
   }

   /** Game thread, right before SpriteRenderer.pushFrameDown: the frame being pushed shows this step's time. */
   public static void onPush() {
      if (!ACTIVE) {
         return;
      }
      lastWorkNs = System.nanoTime() - stepStartNs; // gcSlackFit: the step's own work before the hand-off
      int h = head;
      if (h - tail >= RING) {
         return; // render thread not consuming (no render state yet): drop
      }
      ring[h & (RING - 1)] = stepStartNs;
      ringSlot[h & (RING - 1)] = stepSlotNs;
      ringPush[h & (RING - 1)] = System.nanoTime();
      ringQueued[h & (RING - 1)] = stepQueued;
      head = h + 1;
   }

   /** Render thread, after acquireStateForRendering returned a frame. */
   public static void onAcquire() {
      if (!ACTIVE) {
         return;
      }
      acquireNs = System.nanoTime();
      int t = tail;
      if (t != head) {
         frameSimNs = ring[t & (RING - 1)];
         frameSlotNs = ringSlot[t & (RING - 1)];
         framePushNs = ringPush[t & (RING - 1)];
         frameQueued = ringQueued[t & (RING - 1)];
         tail = t + 1;
      } else {
         frameSimNs = stepStartNs;
         frameSlotNs = stepSlotNs;
         framePushNs = acquireNs;
         frameQueued = stepQueued;
      }
      // gcSlackFit: the render thread's schedule for the game thread (when its next swap goes, how much render work is left)
      rtTargetNs = QUEUE && frameQueued && capIntervalNs > 0L && frameSlotNs != 0L && qTargetNs > 0L ? frameSlotNs + qTargetNs : 0L;
      rtAcquireNs = acquireNs;
   }

   // --- gcSlackFit (tail6 2026-10-14): the render thread's schedule, read by the game thread -----------------------------
   /** Render thread: when it took its current frame. */
   private static volatile long rtAcquireNs;
   /** Render thread: its current frame's swap target under frameQueue (slot + D), 0 = none (not held). */
   private static volatile long rtTargetNs;
   /** Render thread: when its current frame's submission ended (beforeSwap entered). */
   private static volatile long rtSubmitDoneNs;
   /** Render thread: when its last swap returned. */
   private static volatile long rtSwapRetNs;
   /** Render thread: acquire -> swap call, max(EWMA, 0.75 x decaying peak) (ns). */
   private static volatile long rtSubmitEstNs = 3_000_000L;
   private static double rtSubmitMean = 3_000_000.0, rtSubmitPeak = 3_000_000.0;

   // gcSlackFit's pause planner: the pause it chose (game thread)
   private static long planS, planE;

   /**
    * gcSlackFit, game thread: when a stop-the-world pause of {@code p} ns, preceded by {@code fill} ns of game-thread filler,
    * may start without delaying any swap of the frame queue or dropping a limiter slot; -1 when no start in reach does.
    * Candidates: right away (the pause ends before the render thread's next swap with its remaining submission done), or just
    * after that swap (the render thread then has a whole frame's room), if the caller can wait that long ({@code maxWait}: the
    * filler must start by now + maxWait). Each candidate is checked against: the frame the render thread holds (its swap at
    * slot + D), every frame already pushed (rendered one after the other from that swap, each done by its slot + D), and the step
    * the pause delays (at a step start: this one, {@code deadline} 0; in the slack: the next one, starting at max(deadline, pause
    * end)): its frame done by its slot + D after {@code work} ns of step and the render submission, and its end inside two cap
    * intervals of its slot (a longer step drops a limiter slot).
    */
   public static long planPause(long now, long fill, long p, long margin, long work, long deadline, long maxWait) {
      long d = qTargetNs;
      long interval = capIntervalNs;
      if (!QUEUE || d <= 0L || interval <= 0L) {
         return -1L;
      }
      planAfterAcquire = 0L;
      long s1 = now + fill;
      if (pauseOk(now, s1, s1 + p, margin, work, deadline, d, interval)) {
         return s1;
      }
      long a = rtAcquireNs;
      long tF = rtTargetNs;
      if (a != 0L && rtSwapRetNs < a && tF > now && tF - now <= maxWait) {
         // after the swap: the filler starts once the render thread's swap has returned (waitSwap), the pause after it
         long s2 = tF + 150_000L + fill;
         if (pauseOk(now, s2, s2 + p, margin, work, deadline, d, interval)) {
            planAfterAcquire = a;
            planSwapAt = tF;
            return s2;
         }
      }
      return -1L;
   }

   private static long planAfterAcquire, planSwapAt;

   /** gcSlackQueue=slack, game thread: the swap target of the frame the render thread holds now (submission done), else 0. */
   static long holdTargetNs() {
      long a = rtAcquireNs;
      long t = rtTargetNs;
      return a != 0L && rtSubmitDoneNsAny >= a && rtSwapRetNs < a ? t : 0L;
   }

   /** frameQueue is holding frames now (capped, D learned). */
   static boolean queueActive() {
      return QUEUE && stepQueued && capIntervalNs > 0L && qTargetNs > 0L;
   }

   /**
    * frameQueue (on by default at a frame cap since 2026-10-15, maintainer), game thread at a capped step's start: whether this
    * step's frame goes through the queue. It does only while a world is being played; it stays out where the hold would add
    * delay for nothing or fight another pacer (docs/override-edits.md, "Frame queue on by default"):
    * <ul>
    * <li>menus, the pause menu and the loading screen: nothing there needs an even cadence, and the cursor should not lag; the
    * loading screen's long frames would also fill D's window with samples of up to 250 ms;</li>
    * <li>vsync on without variable refresh: the swap already waits for the vblank, so the hold only moves frames to a later
    * vblank (more delay, the cadence is the display's);</li>
    * <li>the cap is only vrrCap's (the player chose Uncapped and variable refresh caps it): an uncapped player keeps
    * presentPacing;</li>
    * <li>macOS: the compositor presents on the display's refresh grid like vsync, and the Metal bridge (macPresent) paces its
    * own presents; never measured there;</li>
    * <li>reflexSleep: it drives the hand-off queue towards empty, the frame queue keeps it about two frames deep.</li>
    * </ul>
    * A frame outside the queue takes presentPacing's path, as before.
    */
   private static boolean queueAllowed() {
      String why;
      if (!GameWindow.isIngameState() || GameWindow.states.current instanceof GameLoadingState || FrameCap.pauseMenuUp()) {
         why = "menu";
      } else if (MAC_OS || MacPresent.active()) {
         why = "macOS";
      } else if (org.lwjglx.opengl.Display.isVSyncEnabledPzopt() && !Vrr.active()) {
         why = "vsync";
      } else if (LowLatency.REFLEX) {
         why = "reflexSleep";
      } else if (FrameCap.vrrOnlyCapNow()) {
         why = "uncapped (variable refresh cap only)";
      } else {
         why = "";
      }
      if (!why.equals(queueOffReason)) {
         queueOffReason = why;
         if (why.isEmpty()) {
            queueTurnedOn(); // frameQueueLowCapCalmMs: the render thread restarts a low cap's D from its floor
         }
         Log.info(why.isEmpty() ? "frame queue state: on (capped world)" : "frame queue state: off (" + why + "), presentPacing applies");
      }
      return why.isEmpty();
   }

   private static final boolean MAC_OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

   /**
    * gcSlackAfterSwap, its own thread: waits until the render thread has swapped a frame it held to its slot + D (on time), so
    * whatever runs next has the render thread's whole interval; at most {@code maxWaitNs}. True when such a swap was seen.
    */
   static boolean waitHeldSwap(long maxWaitNs) {
      long end = System.nanoTime() + maxWaitNs;
      while (true) {
         long now = System.nanoTime();
         if (now > end) {
            return false;
         }
         long a = rtAcquireNs;
         long t = rtTargetNs;
         if (a != 0L && t > now + 150_000L && rtSwapRetNs < a && rtSubmitDoneNsAny >= a) {
            // the frame acquired at a has finished its submission and is held until t: wake just before t, then spin to its swap
            long w = t - 250_000L - now;
            if (w > 20_000L) {
               LockSupport.parkNanos(w);
            }
            long limit = t + 3_000_000L;
            while (rtSwapRetNs < a) {
               if (System.nanoTime() > limit) {
                  return false;
               }
               Thread.onSpinWait();
            }
            return true;
         }
         LockSupport.parkNanos(300_000L);
      }
   }

   /**
    * gcSlackQueue=hold, its own thread: waits until the render thread holds a frame (submission done, swap not yet) whose swap
    * target is at least {@code minLeftNs} away; returns that target, or -1 after {@code maxWaitNs}.
    */
   static long waitHoldStart(long minLeftNs, long maxWaitNs) {
      long end = System.nanoTime() + maxWaitNs;
      while (true) {
         long now = System.nanoTime();
         if (now > end) {
            return -1L;
         }
         long a = rtAcquireNs;
         long t = rtTargetNs;
         if (a != 0L && rtSubmitDoneNsAny >= a && rtSwapRetNs < a && t - now >= minLeftNs) {
            return t;
         }
         LockSupport.parkNanos(100_000L);
      }
   }

   /** Render thread: when its current frame's submission ended (beforeSwap), for gcSlackQueue. */
   private static volatile long rtSubmitDoneNsAny;

   static long submitEstUs() {
      return rtSubmitEstNs / 1000L;
   }

   static long queueTargetUs() {
      return qTargetNs / 1000L;
   }
   /** gcSlackFit: failed checks of the pause planner (no frame target, the held frame's swap, a pushed frame, the delayed step's two intervals, its frame). */
   public static final long[] planFail = new long[5];

   /**
    * gcSlackFit, game thread: when the planned pause comes after a swap, wait for it: park until just before the swap's target,
    * then until the render thread's swap has returned (at most 3 ms past the target). True when the swap was seen.
    */
   public static boolean waitPlannedSwap() {
      long a = planAfterAcquire;
      if (a == 0L) {
         return true;
      }
      planAfterAcquire = 0L;
      long t = planSwapAt;
      long w = t - 100_000L - System.nanoTime();
      if (w > 20_000L) {
         LockSupport.parkNanos(w);
      }
      long limit = t + 3_000_000L;
      while (rtSwapRetNs < a) {
         long now = System.nanoTime();
         if (now > limit) {
            return false;
         }
         Thread.onSpinWait();
      }
      return true;
   }

   /** The render thread's finish of a submission started at x, with the pause [s, e]. */
   private static long renderDone(long x, long sub, long s, long e) {
      if (e <= x || s >= x + sub) {
         return x + sub;
      }
      return s <= x ? e + sub : x + sub + (e - s);
   }

   private static boolean pauseOk(long now, long s, long e, long margin, long work, long deadline, long d, long interval) {
      long sub = rtSubmitEstNs;
      long a = rtAcquireNs;
      boolean hasF = a != 0L && rtSwapRetNs < a;
      long rt = now; // when the render thread is free for the next frame
      if (hasF) {
         long tF = rtTargetNs;
         if (tF == 0L) {
            planFail[0]++;
            return false; // presented as soon as it is ready: any pause delays it
         }
         if (s < tF) {
            long left = rtSubmitDoneNs >= a ? 0L : Math.max(300_000L, a + sub - s); // its submission still to do at the pause
            if (e + left > tF - margin) {
               planFail[1]++;
               return false;
            }
         }
         rt = Math.max(now, tF);
      }
      // the frames already pushed, rendered in order after that
      int h = head;
      for (int t = tail; t != h; t++) {
         long slot = ringSlot[t & (RING - 1)];
         if (slot == 0L) {
            planFail[0]++;
            return false;
         }
         long done = renderDone(rt, sub, s, e);
         if (done > slot + d - margin) {
            planFail[2]++;
            return false;
         }
         rt = Math.max(done, slot + d);
      }
      // the step the pause delays, and its frame
      long slot = deadline == 0L ? stepSlotNs : deadline;
      long start = deadline == 0L ? e : Math.max(deadline, e);
      long push = start + work;
      if (push > slot + 2L * interval - margin) {
         planFail[3]++;
         return false;
      }
      long done = renderDone(Math.max(rt, push), sub, s, e);
      if (done > slot + d - margin) {
         planFail[4]++;
         return false;
      }
      planS = s;
      planE = e;
      return true;
   }

   /** Game thread: the last step's time from its start to the frame hand-off (gcSlackFit). */
   static long lastWorkNs;

   /** gcSlackFit, game thread: the current step's cap slot (the limiter's deadline it serves). */
   static long stepSlotNs() {
      return stepSlotNs;
   }


   /** Render thread: the last frame's acquire -> swap-call time before any hold (pzopt.BakeScheduler's adaptive budget). */
   public static volatile long lastSubmitNs;
   /** frameQueueGpu: GPU done - submit end of the latest frame whose timestamp came back (bakeBudgetGpu). */
   public static volatile long lastGpuPastNs;

   /** Render thread, right before Display.update (the swap): optionally hold the present, then stamp the call. */
   public static void beforeSwap() {
      Overlay.swapCallNs = System.nanoTime(); // the built-in benchmark: render work ends, the present (hold + swap) begins
      BenchShot.beforeSwap(); // the built-in benchmark's screenshots: the finished frame, before it is presented
      BenchFocus.beforeSwap(); // the built-in benchmark: a restarted pass's window takes the focus (macOS opens it behind)
      if (!ACTIVE) {
         return;
      }
      lastSubmitNs = System.nanoTime() - acquireNs;
      if (GcSlack.AFTER_SWAP) {
         rtSubmitDoneNsAny = acquireNs + lastSubmitNs; // gcSlackAfterSwap: the frame is now only held
      }
      if (Config.GC_SLACK_FIT) { // gcSlackFit: the render thread's submission is done, its cost learned
         rtSubmitDoneNs = acquireNs + lastSubmitNs;
         double s = lastSubmitNs;
         rtSubmitMean += 0.1 * (s - rtSubmitMean);
         rtSubmitPeak = Math.max(rtSubmitPeak * 0.98, s);
         rtSubmitEstNs = (long)Math.max(rtSubmitMean, 0.75 * rtSubmitPeak);
      }
      if (!glChecked) {
         initGl();
      }
      long now = System.nanoTime();
      waitNs = 0L;
      long frame = frameNo++;
      long interval = capIntervalNs;
      int m = effectiveMode();
      if (m != lastMode) {
         lagCount = 0;
         targetLagNs = 0L;
         lastMode = m;
      }
      if (timestamps) {
         collectQueries(now);
         issueQuery(frame);
      }
      if (QUEUE && frameQueued && interval > 0L && frameSlotNs != 0L && GtAb.on(GtAb.FRAME_QUEUE)) { // (GtAb: devGtAlternateKeys=frameQueue alternates it within a run; the render thread reads the phase the game thread set for its current step)
         swapCallNs = queueHold(now, interval);
         return;
      }
      if (m == GPU_FINISH && interval > 0L) {
         long sync = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
         GL32.glClientWaitSync(sync, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 100_000_000L);
         GL32.glDeleteSync(sync);
         now = System.nanoTime();
      } else if (m != OFF && interval > 0L) {
         GL11.glFlush(); // the frame's commands reach the GPU now, not at the swap after the hold
      }
      if (m != OFF && frameSimNs != 0L && interval > 0L) {
         if (m == CPU || m == GPU_FINISH) {
            addLag(now - frameSimNs);
         }
         if (lagCount > 0) {
            if (++framesSinceTarget >= 15 || targetLagNs == 0L) {
               framesSinceTarget = 0;
               System.arraycopy(lagWin, 0, sorted, 0, lagCount);
               Arrays.sort(sorted, 0, lagCount);
               int idx = Math.min(lagCount - 1, lagCount * Config.PRESENT_PACING_PCT / 100);
               targetLagNs = sorted[idx] + Config.PRESENT_PACING_MARGIN_US * 1000L;
            }
            long target = frameSimNs + targetLagNs;
            // the render thread must be ready for the next frame within one interval of taking this one
            long guard = acquireNs + interval - 300_000L;
            if (target > guard) {
               target = guard;
            }
            if (target > now) {
               waitUntil(target);
               long after = System.nanoTime();
               waitNs = after - now;
               now = after;
            }
         }
      } else if (interval == 0L) {
         lagCount = 0;
         targetLagNs = 0L;
      }
      swapCallNs = now;
   }

   private static int lastMode = -1;

   // --- frameQueue (render thread) -------------------------------------------------------------
   private static final int Q_WINDOW = 240;
   private static final long[] qWin = new long[Q_WINDOW];
   private static final long[] qSorted = new long[Q_WINDOW];
   private static int qCount, qPos, qSince;
   private static long qTargetNs;
   /** frameQueue: the D the percentile (and the decay) asks for; qTargetNs follows it at frameQueueSlewUs per frame. */
   private static long qWantNs, qWantAtNs;
   /** frameQueueGpu: per query slot, the frame's CPU slot -> ready time and its beforeSwap time, until its GPU timestamp is read. */
   private static final long[] qCpuLag = new long[QUERIES], qBefore = new long[QUERIES];
   private static final boolean[] qGpuPending = new boolean[QUERIES];
   private static long qFrames, qHeld, qLate, qHoldNsSum, qTargetNsSum, qLastLogNs, qOver, qOverMaxNs;
   private static BufferedWriter qLog;
   private static long qFlushNs;

   /**
    * frameQueue: holds the swap of the frame acquired last until its limiter slot + D and returns the swap-call time.
    *
    * D is the {@code frameQueuePct} percentile (+ margin) of "slot -> ready" over the last {@link #Q_WINDOW} frames, where
    * ready = the game thread's arrival at the hand-off + this frame's render submit (acquire -> swap call). That is the time
    * the frame would have been ready had the render thread been free when it was handed over, so the hold itself (which
    * keeps the render thread busy and delays later acquires) never feeds back into D (presentPacing=gpu measured step start
    * -> GPU done: its own queueing raised its target, p90 27 ms at the Louisville cap, and its one-interval guard made the
    * render thread a stage of >= one interval per frame that could never catch up). On-time frames therefore reach the swap
    * exactly one cap interval apart, whatever their own game step took; a frame later than its slot + D goes at once.
    */
   private static long queueHold(long now, long interval) {
      GL11.glFlush(); // the frame's commands reach the GPU now: it is drawn during the hold
      long ready = framePushNs + (now - acquireNs);
      long lag = ready - frameSlotNs;
      long f = frameNo - 1L;
      int qs = (int)(f % QUERIES);
      if (Config.FRAME_QUEUE_GPU && timestamps && queryFrame[qs] == f) {
         // frameQueueGpu: the sample waits for this frame's GPU timestamp (collectQueries)
         qCpuLag[qs] = lag;
         qBefore[qs] = now;
         qGpuPending[qs] = true;
      } else {
         addQueueSample(lag, now);
      }
      queueD(now, interval);
      long target = frameSlotNs + qTargetNs;
      long latest = now + 3L * interval; // a bogus slot never parks the render thread for long
      if (target > latest) {
         target = latest;
      }
      waitNs = 0L;
      qFrames++;
      qTargetNsSum += qTargetNs;
      if (target > now) {
         // frameQueueSpinUs (in-run A/B: devGtAlternateKeys=frameQueueSpin, off phase = the 1 ms spin of waitUntil)
         boolean shortSpin = Config.FRAME_QUEUE_SPIN_US != 1000 && GtAb.on(GtAb.FQ_SPIN);
         // frameQueueRtHold only with a short spin: a SCHED_FIFO thread spinning 1 ms keeps a CFS thread that shares its CPU
         // (often the GL driver's worker, woken next to it) off the CPU, and the next swap waits for that worker (f9p-rtab1-3:
         // 150 swap blocks > 0.5 ms in the FIFO phase vs 2 outside it)
         rtHeld = shortSpin && Config.FRAME_QUEUE_RT_HOLD > 0 && GtAb.on(GtAb.RT_HOLD) && rtEnter();
         SwapProbe.holdBegin(); // devSwapProbe: the render thread's run-queue wait during the hold
         if (shortSpin) {
            holdUntil(target); // frameQueueSpinUs: park (timer slack 1 ns) to the last frameQueueSpinUs, spin the rest
         } else {
            waitUntil(target);
         }
         long after = System.nanoTime();
         if (rtHeld && Config.FRAME_QUEUE_RT_HOLD == 1) {
            rtExit(); // frameQueueRtHold=1: back to SCHED_OTHER before the swap
         }
         SwapProbe.holdEnd(after - target);
         if (after - target > 200_000L) {
            qOver++; // the hold ended late (the render thread had no CPU when its deadline came)
            qOverMaxNs = Math.max(qOverMaxNs, after - target);
         }
         waitNs = after - now;
         now = after;
         qHeld++;
         qHoldNsSum += waitNs;
      } else if (now - target > 500_000L) {
         qLate++;
      }
      if (LOG) {
         queueLog(lag, target);
      }
      if (now - qLastLogNs > 10_000_000_000L) {
         if (qLastLogNs != 0L && qFrames > 0) {
            Log.info(String.format(Locale.ROOT, "frame queue: %d frames, D %.2f ms (p%d of slot -> ready, last %.2f, floor %.2f), held %d (%.2f ms mean), late %d, catch-up slots %d, dropped slots %d, holds ended > 0.2 ms late %d (max %.2f ms)%s",
                  qFrames, qTargetNsSum / 1e6 / qFrames, Config.FRAME_QUEUE_PCT, qTargetNs / 1e6, interval * queueMinPct(interval) / 100L / 1e6, qHeld, qHeld > 0 ? qHoldNsSum / 1e6 / qHeld : 0.0, qLate,
                  catchUpSlots, droppedSlots, qOver, qOverMaxNs / 1e6, Config.FRAME_QUEUE_RT_HOLD > 0 ? ", rt holds " + rtHolds + (rtState < 0 ? " (unavailable)" : "") : "") + (Config.BURST_GOVERNOR ? "; burst governor: heavy steps " + heavySteps + ", spawn frames held " + spawnsHeld + ", model adds deferred " + zombie.iso.IsoWorld.pzoptAddsDeferred + ", heavy-step add holds (candidates) " + heavyAddHolds : ""));
         }
         qLastLogNs = now;
         qFrames = qHeld = qLate = qHoldNsSum = qTargetNsSum = qOver = qOverMaxNs = rtHolds = 0L;
         catchUpSlots = droppedSlots = 0L;
      }
      return now;
   }

   /**
    * frameQueue: D for this frame (qTargetNs, also returned): the {@code frameQueuePct} percentile of the window + margin,
    * clamped to [floor, frameQueueMaxPct], falling at most at the decay rate and moving at most a slew step per frame.
    *
    * <p>At caps below frameQueueFixedMinFps (the adaptive queue, round 11) the rates are in percent of the cap interval
    * (frameQueueLowCapDecayPct per second, frameQueueLowCapMarginPct on top of the margin; the slew stays frameQueueSlewUs)
    * and, with frameQueueLowCapCalmMs, D restarts from its floor whenever the queue turns on (world loaded, menu or pause menu
    * closed) or the cap changes: the window is emptied and takes no samples until the frames have been ready within one cap
    * interval of their slot for that long (at most {@link #Q_CALM_MAX_NS}). Round 11 learned from the world's start bursts
    * instead: D sat at the 260 % ceiling and fell 1 ms a second, 20 s at a 60 fps cap and ~67 s at 30 (D 56-86 ms on the
    * whole Louisville route). At caps of frameQueueFixedMinFps or more the code path is the one of before (fixed D).
    */
   static long queueD(long now, long interval) {
      boolean low = lowCap(interval);
      int on = queueOnCount;
      if (on != qSeenOn || Math.abs(interval - qIntervalNs) > qIntervalNs / 100L) {
         qSeenOn = on;
         qIntervalNs = interval;
         if (low && Config.FRAME_QUEUE_LOW_CAP_CALM_MS > 0) {
            queueRestart(now, interval);
         } else {
            qWarm = false; // a restart's wait ends with its low cap
         }
      }
      if (qCount > 0 && (++qSince >= 15 || qWantNs == 0L || Config.FRAME_QUEUE_SLEW_US > 0)) { // with frameQueueSlewUs every frame (the slew smooths it; ~3 us for the sort)
         qSince = 0;
         System.arraycopy(qWin, 0, qSorted, 0, qCount);
         Arrays.sort(qSorted, 0, qCount);
         long d = qSorted[Math.min(qCount - 1, qCount * Config.FRAME_QUEUE_PCT / 100)] + Config.FRAME_QUEUE_MARGIN_US * 1000L;
         if (low) {
            d += interval * Config.FRAME_QUEUE_LOW_CAP_MARGIN_PCT / 100L; // frameQueueLowCapMarginPct (20 %: 3.3 ms at 60 fps)
         }
         long lo = interval * queueMinPct(interval) / 100L;
         long hi = interval * Config.FRAME_QUEUE_MAX_PCT / 100L;
         d = Math.max(lo, Math.min(hi, d));
         if (low && Config.FRAME_QUEUE_LOW_CAP_DECAY_PCT > 0 && qWantNs > 0L) {
            // frameQueueLowCapDecayPct: D falls at most this share of the cap interval a second (25 %: 4.2 ms/s at 60 fps, 8.3 at 30)
            long fall = interval * Config.FRAME_QUEUE_LOW_CAP_DECAY_PCT / 100L * Math.min(100_000_000_000L, Math.max(0L, now - qWantAtNs)) / 1_000_000_000L;
            d = Math.max(d, qWantNs - fall);
         } else if (Config.FRAME_QUEUE_DECAY_US_PER_S > 0 && qWantNs > 0L) {
            // frameQueueDecayUsPerS: D falls at most this fast after a burst
            long fall = Config.FRAME_QUEUE_DECAY_US_PER_S * Math.max(0L, now - qWantAtNs) / 1_000_000L;
            d = Math.max(d, qWantNs - fall);
         }
         qWantNs = d;
         qWantAtNs = now;
      }
      if (Config.FRAME_QUEUE_SLEW_US > 0 && qTargetNs > 0L) {
         // frameQueueSlewUs: D moves towards the new value a step per frame, so no present interval stretches by a whole jump
         long step = Config.FRAME_QUEUE_SLEW_US * 1000L;
         qTargetNs = qWantNs > qTargetNs ? Math.min(qWantNs, qTargetNs + step) : Math.max(qWantNs, qTargetNs - step);
      } else {
         qTargetNs = qWantNs;
      }
      return qTargetNs;
   }

   /** frameQueueLowCapCalmMs: the longest a low cap's restart waits for calm frames before D learns from whatever comes. */
   static final long Q_CALM_MAX_NS = 10_000_000_000L;
   /** frameQueue: the interval and queue-on count queueD last saw (a change at a low cap restarts D). */
   private static long qIntervalNs;
   private static int qSeenOn;
   /** frameQueueLowCapCalmMs: D waits at its floor for calm frames; since when, and since when the frames are calm. */
   private static boolean qWarm;
   private static long qWarmStartNs, qCalmFromNs;

   /** frameQueueLowCapCalmMs, render thread: a low cap's D starts again from its floor with an empty window. */
   private static void queueRestart(long now, long interval) {
      qCount = qPos = qSince = 0;
      long lo = interval * queueMinPct(interval) / 100L;
      qWantNs = lo;
      qWantAtNs = now;
      if (qTargetNs == 0L) {
         qTargetNs = lo; // else the slew takes D there (a cap changed in game)
      }
      qWarm = true;
      qWarmStartNs = qCalmFromNs = now;
   }

   /** Game thread: the frame queue turned on (queueAllowed); the next queueD restarts a low cap's D. */
   static void queueTurnedOn() {
      queueOnCount++;
   }

   /** frameQueueLowCapCalmMs: D waits at its floor for calm frames (PacingQueueFloorTest). */
   static boolean queueCalmWait() {
      return qWarm;
   }

   /** frameQueue: the cap interval is below frameQueueFixedMinFps (the adaptive queue with the low-cap rules). */
   static boolean lowCap(long intervalNs) {
      return Config.FRAME_QUEUE_FIXED_MIN_FPS > 0 && intervalNs > 0L && Math.round(1.0e9 / intervalNs) < Config.FRAME_QUEUE_FIXED_MIN_FPS;
   }

   /**
    * frameQueue: D's floor in percent of the cap interval. frameQueueMinPct (the fixed D, 260 %) at caps of
    * frameQueueFixedMinFps (100) or more; below it frameQueueLowCapMinPct (60 %, the adaptive queue): 2.6 intervals are
    * 43 ms at a 60 fps cap and 87 ms at 30 (maintainer 2026-10-15).
    */
   static int queueMinPct(long intervalNs) {
      return lowCap(intervalNs) ? Config.FRAME_QUEUE_LOW_CAP_MIN_PCT : Config.FRAME_QUEUE_MIN_PCT;
   }

   /** frameQueue: one slot -> ready sample into the percentile window (now: the render thread's clock, for the calm wait). */
   static void addQueueSample(long lag, long now) {
      if (qWarm) {
         // frameQueueLowCapCalmMs: the restart's samples count only once the frames have been ready within one interval for that long
         if (lag > qIntervalNs || lag <= 0L) {
            qCalmFromNs = now;
         }
         boolean calm = now - qCalmFromNs >= Config.FRAME_QUEUE_LOW_CAP_CALM_MS * 1_000_000L;
         if (!calm && now - qWarmStartNs < Q_CALM_MAX_NS) {
            return;
         }
         qWarm = false;
         Log.info(String.format(Locale.ROOT, "frame queue: low cap %d fps, D learns after %.1f s at its floor %.2f ms (%s)", Math.round(1.0e9 / Math.max(1L, qIntervalNs)),
               (now - qWarmStartNs) / 1e9, qWantNs / 1e6, calm ? "frames ready within one interval for " + Config.FRAME_QUEUE_LOW_CAP_CALM_MS + " ms" : "no calm stretch in " + Q_CALM_MAX_NS / 1_000_000_000L + " s"));
      }
      if (lag > 0L && lag < 250_000_000L) {
         qWin[qPos] = lag;
         qPos = (qPos + 1) % Q_WINDOW;
         qCount = Math.min(Q_WINDOW, qCount + 1);
      }
   }

   /** pzopt-framequeue.out: sim_ns slot_ns push_ns lag_ns target_ns per frame (join with pzopt-pacing.out on sim_ns). */
   private static void queueLog(long lag, long target) {
      if (logFailed) {
         return;
      }
      try {
         if (qLog == null) {
            String dir = ZomboidFileSystem.instance.getCacheDir();
            if (dir == null) {
               return;
            }
            qLog = new BufferedWriter(new FileWriter(new File(dir, "pzopt-framequeue.out"), false), 1 << 16);
            qLog.write("# sim_ns slot_ns push_ns lag_ns target_ns (frameQueue, System.nanoTime; join with pzopt-pacing.out on sim_ns)\n");
         }
         StringBuilder b = line;
         b.setLength(0);
         b.append(frameSimNs).append(' ').append(frameSlotNs).append(' ').append(framePushNs).append(' ').append(lag).append(' ').append(target).append('\n');
         qLog.write(b.toString());
         long t = System.nanoTime();
         if (t - qFlushNs > 1_000_000_000L) {
            qLog.flush();
            qFlushNs = t;
         }
      } catch (IOException | RuntimeException e) {
         logFailed = true;
         Log.warn("pacing: frame queue log stopped: " + e);
      }
   }

   // limiter catch-up counters (game thread writes, the render thread's log line reads; approximate is fine)
   private static volatile long catchUpSlots, droppedSlots;

   /**
    * GameWindow.mainThreadStep after a capped step: the limiter's accumulator for the next step. Stock keeps
    * {@code acc % interval} (whole overrun intervals are dropped: a 20 ms step at a 120 cap gives one frame for two slots);
    * with {@link #CATCH_UP} = c the next steps make up to c missed slots back to back.
    */
   public static long limiterRemainder(long acc, long interval) {
      if (CATCH_UP <= 0) {
         return acc % interval;
      }
      long r = acc - interval;
      if (r >= interval) {
         catchUpSlots++;
      }
      long limit = (CATCH_UP + 1L) * interval;
      if (r >= limit) {
         long kept = CATCH_UP * interval + r % interval;
         droppedSlots += (r - kept) / interval;
         r = kept;
      }
      return r;
   }

   /** Render thread: the frame number the next afterSwap logs (for on-glass feedback matched later). */
   public static long currentFrame() {
      return frameNo - 1;
   }

   /** The limiter's current frame interval (0 = uncapped). */
   /** The last step's work before its hand-off (ns; frameQueue / pacing active). Game thread. */
   public static long lastWorkNs() {
      return lastWorkNs;
   }

   public static long capIntervalNs() {
      return capIntervalNs;
   }

   /** Render thread: frame f reached the screen at shownNs (System.nanoTime base); fills the log row while it is pending. */
   public static void onGlass(long f, long shownNs) {
      long[] row = rows[(int)(f & 15)];
      if (row[7] == f && f > frameNo - 1 - LAG_ROWS) {
         row[8] = shownNs;
      }
   }

   /**
    * The pacing mode for this frame. AUTO: gpu wherever the GL has timestamp queries (since 2026-09-25 also at a fixed
    * refresh: the Rosewood drive's present jitter 1.3 -> 0.4 ms, frames below the cap 28 -> 11 %, for ~1.7 ms more
    * step-to-screen time on average), the cpu fallback only while VRR is active, off under the macOS Metal bridge
    * (it paces its own presents).
    */
   static int effectiveMode() {
      return mode == AUTO ? autoResolved() : mode;
   }

   private static int autoResolved() {
      if (MacPresent.active()) {
         return OFF;
      }
      return autoMode == GPU || Vrr.active() ? autoMode : OFF;
   }

   public static String describeMode() {
      String m = mode == AUTO ? "auto (" + NAMES[autoResolved()] + ")" : NAMES[mode];
      return QUEUE ? "frameQueue at a cap, else " + m : m;
   }

   private static void addLag(long lag) {
      if (lag <= 0L || lag > 250_000_000L) {
         return;
      }
      lagWin[lagPos] = lag;
      lagPos = (lagPos + 1) % WINDOW;
      lagCount = Math.min(WINDOW, lagCount + 1);
   }

   private static void initGl() {
      glChecked = true;
      try {
         GLCapabilities caps = GL.getCapabilities();
         timestamps = (caps.OpenGL33 || caps.GL_ARB_timer_query) && CoreGl.timerQueries();
         if (mode == GPU_FINISH && !(caps.OpenGL32 || caps.GL_ARB_sync)) {
            mode = CPU;
         }
         if (timestamps) {
            queryIds = new int[QUERIES];
            GL15.glGenQueries(queryIds);
            queryBuf = QueryBuf.create(QUERIES, "pacing");
            Arrays.fill(queryFrame, -1L);
            calibrate(System.nanoTime());
         } else {
            autoMode = CPU;
            if (mode == GPU) {
               mode = CPU;
            }
         }
         if (QUEUE) {
            Log.info("pacing: frameQueue on (p" + Config.FRAME_QUEUE_PCT + " + " + Config.FRAME_QUEUE_MARGIN_US + " us, D " + Config.FRAME_QUEUE_MIN_PCT + ".." + Config.FRAME_QUEUE_MAX_PCT
               + " % of the cap interval" + (Config.FRAME_QUEUE_FIXED_MIN_FPS > 0 ? " at caps of " + Config.FRAME_QUEUE_FIXED_MIN_FPS + " fps or more, " + Config.FRAME_QUEUE_LOW_CAP_MIN_PCT + ".." + Config.FRAME_QUEUE_MAX_PCT + " % below" : "") + ", limiter catch-up " + CATCH_UP + " slots, GPU samples " + (Config.FRAME_QUEUE_GPU && timestamps) + ", slew "
               + Config.FRAME_QUEUE_SLEW_US + " us/frame, decay " + Config.FRAME_QUEUE_DECAY_US_PER_S + " us/s"
               + (Config.FRAME_QUEUE_FIXED_MIN_FPS > 0 ? "; below " + Config.FRAME_QUEUE_FIXED_MIN_FPS + " fps margin +" + Config.FRAME_QUEUE_LOW_CAP_MARGIN_PCT
                  + " %, decay " + Config.FRAME_QUEUE_LOW_CAP_DECAY_PCT + " %/s of the interval, restart from the floor after "
                  + Config.FRAME_QUEUE_LOW_CAP_CALM_MS + " ms of calm frames" : "") + "); presentPacing applies uncapped only");
         }
         if (MODE != OFF) {
            Log.info("pacing: presentPacing=" + Config.PRESENT_PACING + " -> " + NAMES[mode] + (mode == AUTO ? " (" + NAMES[autoMode] + (autoMode == GPU ? ")" : " while VRR is active)") : "")
               + " (GL timestamps " + (timestamps ? "yes" : "no") + ", p" + Config.PRESENT_PACING_PCT + " + " + Config.PRESENT_PACING_MARGIN_US + " us)");
         }
      } catch (Throwable t) {
         timestamps = false;
         autoMode = CPU;
         if (mode == GPU || mode == GPU_FINISH) {
            mode = CPU;
         }
         Log.warn("pacing: GL queries unavailable: " + t);
      }
   }

   /** GPU clock -> System.nanoTime offset; GL_TIMESTAMP read synchronously returns the GPU's current time. */
   private static void calibrate(long now) {
      long before = System.nanoTime();
      long gpu = GL32.glGetInteger64(GL33.GL_TIMESTAMP);
      long after = System.nanoTime();
      gpuToCpuNs = (before + after) / 2L - gpu;
      lastCalibrationNs = now;
   }

   private static void issueQuery(long frame) {
      int slot = (int)(frame % QUERIES);
      if (queryFrame[slot] != -1L) {
         return; // still pending from QUERIES frames ago (GPU far behind): skip this frame
      }
      GL33.glQueryCounter(queryIds[slot], GL33.GL_TIMESTAMP);
      if (queryBuf != null) { // pacingQueryBuffer: the GPU writes the result into the slot once it is there
         queryBuf.request(slot, queryIds[slot]);
      }
      queryFrame[slot] = frame;
      querySim[slot] = frameSimNs;
   }

   private static void collectQueries(long now) {
      if (now - lastCalibrationNs > 8_000_000_000L) { // tail pass: every 8 s, was 1 s: glGetInteger64(GL_TIMESTAMP) waits for NVIDIA's threaded driver queue (a render-thread stall in 15 of the slow frames of run t1-jfrn1); the GPU and CPU clocks drift far less than a microsecond in 8 s
         calibrate(now);
      }
      for (int i = 0; i < QUERIES; i++) {
         long f = queryFrame[i];
         if (f == -1L) {
            continue;
         }
         long done;
         if (queryBuf != null) { // pacingQueryBuffer: read from client memory, no driver call
            long v = queryBuf.get(i);
            if (v == -1L) {
               continue;
            }
            done = v + gpuToCpuNs;
         } else {
            if (GL15.glGetQueryObjecti(queryIds[i], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
               continue;
            }
            done = GL33.glGetQueryObjecti64(queryIds[i], GL15.GL_QUERY_RESULT) + gpuToCpuNs;
         }
         queryFrame[i] = -1L;
         if (qGpuPending[i]) { // frameQueueGpu: the frame's slot -> ready time plus its GPU time past the end of its submit
            qGpuPending[i] = false;
            addQueueSample(qCpuLag[i] + Math.max(0L, done - qBefore[i]), now);
            lastGpuPastNs = Math.max(0L, done - qBefore[i]); // bakeBudgetGpu (f8-resid): the GPU's time past the end of the latest timed frame's submit
         }
         if (effectiveMode() == GPU && querySim[i] != 0L) {
            addLag(done - querySim[i]);
         }
         long[] row = rows[(int)(f & 15)];
         if (row[7] == f) {
            row[6] = done;
         }
      }
   }

   /** Render thread, after Display.update returned. */
   public static void afterSwap() {
      if (rtHeld) {
         rtExit(); // frameQueueRtHold=2: SCHED_FIFO from the hold to the swap's return
      }
      if (Config.GC_SLACK_FIT || GcSlack.AFTER_SWAP) {
         rtSwapRetNs = System.nanoTime(); // gcSlackFit / gcSlackAfterSwap: the render thread is free until its next acquire
      }
      if (!LOG) {
         return;
      }
      long ret = System.nanoTime();
      long f = frameNo - 1;
      long[] row = rows[(int)(f & 15)];
      row[0] = ret;
      row[1] = frameSimNs;
      row[2] = acquireNs;
      row[3] = swapCallNs;
      row[4] = ret;
      row[5] = waitNs;
      row[6] = 0L;
      row[7] = f;
      row[8] = 0L;
      if (f >= LAG_ROWS) {
         write(rows[(int)((f - LAG_ROWS) & 15)]);
      }
   }

   private static void write(long[] row) {
      BenchRecorder.onPacing(row); // the built-in benchmark's latency: step start to GPU done
      if (logFailed) {
         return;
      }
      try {
         if (log == null) {
            String dir = ZomboidFileSystem.instance.getCacheDir();
            if (dir == null) {
               return;
            }
            File file = new File(dir, "pzopt-pacing.out");
            log = new BufferedWriter(new FileWriter(file, false), 1 << 16);
            logStartNs = System.nanoTime();
            logStartEpochMs = System.currentTimeMillis();
            log.write("# epoch_ms sim_ns acquire_ns swap_call_ns swap_ret_ns wait_ns gpu_done_ns shown_ns (System.nanoTime; shown = on-glass time where the platform reports it (macOS Metal bridge); presentPacing="
               + Config.PRESENT_PACING + " mode=" + NAMES[mode] + ")\n");
            Log.info("pacing: logging frames to " + file);
         }
         StringBuilder b = line;
         b.setLength(0);
         b.append(logStartEpochMs + (row[4] - logStartNs) / 1_000_000L);
         for (int i = 1; i <= 6; i++) {
            b.append(' ').append(row[i]);
         }
         b.append(' ').append(row[8]);
         b.append('\n');
         log.write(b.toString());
         if (row[4] - lastFlushNs > 1_000_000_000L) {
            log.flush();
            lastFlushNs = row[4];
         }
      } catch (IOException | RuntimeException e) {
         logFailed = true;
         Log.warn("pacing: log stopped: " + e);
      }
   }

   public static void flushLog() {
      for (BufferedWriter w : new BufferedWriter[]{log, qLog}) {
         if (w != null) {
            try {
               w.flush();
            } catch (IOException ignored) {
            }
         }
      }
   }

   /**
    * limiterSleep: parks until limiterSpinUs before the limiter's next step and returns; the stock limiter loop spins the
    * rest, so a late wake costs nothing but the spin it replaces.
    */
   public static void limiterWait(long stepNs) {
      SlackWork.run(stepNs); // slackWork: deferred game-thread jobs that fit before the next step
      if (!slackSet) {
         slackSet = true;
         timerSlack1ns();
      }
      long left = stepNs - System.nanoTime() - Config.LIMITER_SPIN_US * 1000L;
      if (left > 20_000L) {
         LockSupport.parkNanos(left);
      }
   }

   private static boolean slackSet; // game thread only

   /**
    * Linux: the calling thread's timer slack to 1 ns (default 50 us), so a park ends when asked; prctl(PR_SET_TIMERSLACK)
    * only touches the calling thread.
    */
   static void timerSlack1ns() {
      if (!new java.io.File("/proc/self/task").isDirectory()) {
         return;
      }
      try {
         java.lang.foreign.Linker l = java.lang.foreign.Linker.nativeLinker();
         java.lang.invoke.MethodHandle prctl = l.downcallHandle(l.defaultLookup().find("prctl").orElseThrow(),
               java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_INT,
                     java.lang.foreign.ValueLayout.JAVA_LONG), java.lang.foreign.Linker.Option.firstVariadicArg(1));
         int r = (int) prctl.invokeExact(29, 1L); // PR_SET_TIMERSLACK
         if (r != 0) {
            Log.warn("limiterSleep: prctl(PR_SET_TIMERSLACK) = " + r);
         }
      } catch (Throwable t) {
         Log.warn("limiterSleep: no timer slack change (" + t + ")");
      }
   }

   // --- frameQueueRtHold (f9-present, render thread) --------------------------------------------
   private static final int SCHED_OTHER = 0, SCHED_FIFO = 1, SCHED_RESET_ON_FORK = 0x40000000;
   private static java.lang.invoke.MethodHandle schedSetscheduler;
   private static java.lang.foreign.MemorySegment schedParam;
   /** 0 = not tried, 1 = works, -1 = refused (RLIMIT_RTPRIO 0 / no FFM): stays off. */
   private static int rtState;
   private static boolean rtHeld;
   private static long rtHolds;

   /**
    * frameQueueRtHold: the calling (render) thread to SCHED_FIFO at frameQueueRtPrio. A woken RT thread preempts any CFS
    * thread on its CPU (or the kernel pushes it to a CPU running a lower class), so the hold's park ends when its timer fires
    * instead of when the CPU frees up. SCHED_RESET_ON_FORK: a thread started meanwhile does not inherit the policy.
    */
   private static boolean rtEnter() {
      if (rtState < 0) {
         return false;
      }
      try {
         if (rtState == 0) {
            java.lang.foreign.Linker l = java.lang.foreign.Linker.nativeLinker();
            schedSetscheduler = l.downcallHandle(l.defaultLookup().find("sched_setscheduler").orElseThrow(),
                  java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_INT,
                        java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.ADDRESS));
            schedParam = java.lang.foreign.Arena.global().allocate(java.lang.foreign.ValueLayout.JAVA_INT);
         }
         schedParam.set(java.lang.foreign.ValueLayout.JAVA_INT, 0L, Config.FRAME_QUEUE_RT_PRIO);
         int r = (int) schedSetscheduler.invokeExact(0, SCHED_FIFO | SCHED_RESET_ON_FORK, schedParam);
         if (r != 0) {
            rtState = -1;
            Log.warn("frameQueueRtHold: sched_setscheduler(SCHED_FIFO " + Config.FRAME_QUEUE_RT_PRIO + ") refused (RLIMIT_RTPRIO?); off");
            return false;
         }
         if (rtState == 0) {
            Log.info("frameQueueRtHold: the render thread holds its frames at SCHED_FIFO " + Config.FRAME_QUEUE_RT_PRIO
                  + (Config.FRAME_QUEUE_RT_HOLD == 2 ? " until the swap returns" : ""));
         }
         rtState = 1;
         rtHolds++;
         return true;
      } catch (Throwable t) {
         rtState = -1;
         Log.warn("frameQueueRtHold: unavailable (" + t + "); off");
         return false;
      }
   }

   private static void rtExit() {
      rtHeld = false;
      try {
         schedParam.set(java.lang.foreign.ValueLayout.JAVA_INT, 0L, 0);
         int r = (int) schedSetscheduler.invokeExact(0, SCHED_OTHER | SCHED_RESET_ON_FORK, schedParam);
         if (r != 0) {
            rtState = -1;
            Log.warn("frameQueueRtHold: could not return to SCHED_OTHER (" + r + "); off");
         }
      } catch (Throwable t) {
         rtState = -1;
      }
   }

   private static boolean rtSlackSet; // render thread only

   /**
    * frameQueue's hold with {@code frameQueueSpinUs}: the render thread's timer slack set to 1 ns once, then parks to that many us
    * before the deadline and spins the rest. The 1 ms spin of {@link #waitUntil} keeps the render thread runnable for an eighth of
    * every frame; 2-5 holds a run still ended 0.7-1.2 ms late without any safepoint (run pace2-sp1's safepoint log: GC only).
    */
   private static void holdUntil(long deadlineNs) {
      if (!rtSlackSet) {
         rtSlackSet = true;
         timerSlack1ns();
      }
      long spin = Config.FRAME_QUEUE_SPIN_US * 1000L;
      while (true) {
         long left = deadlineNs - System.nanoTime();
         if (left <= 0L) {
            return;
         }
         if (left > spin + 20_000L) {
            LockSupport.parkNanos(left - spin);
         } else {
            Thread.onSpinWait();
         }
      }
   }

   /** Sleeps to ~1 ms before the deadline (park is coarse), then spins the rest. */
   public static void waitUntil(long deadlineNs) {
      while (true) {
         long left = deadlineNs - System.nanoTime();
         if (left <= 0L) {
            return;
         }
         if (left > 1_200_000L) {
            LockSupport.parkNanos(left - 1_000_000L);
         } else {
            Thread.onSpinWait();
         }
      }
   }
}
