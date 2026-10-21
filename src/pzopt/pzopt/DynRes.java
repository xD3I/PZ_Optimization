package pzopt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLCapabilities;
import zombie.GameWindow;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureFBO;

/**
 * Dynamic resolution ({@code dynRes}, docs/findings-dynamic-resolution-2026-10-03.md): the world's render scale
 * ({@link RenderScale}) follows the GPU time per frame so the GPU fits the frame cap's interval.
 *
 * <p>Threads. The game thread picks the scale of the frame it builds ({@link #beginFrame}, right after
 * {@code SpriteRenderer.NewFrame}) and files it under that frame's SpriteRenderState; the render thread latches it when it
 * acquires the frame, before the replay, so both threads agree on every frame's scale (the game thread's
 * composite and fog uniforms, the render thread's viewport and resolve). The render thread brackets each frame's replay
 * with two GL_TIMESTAMP queries ({@link #gpuBegin} / {@link #gpuEnd}), reads them a few frames later without a stall,
 * and feeds the controller, which publishes the scale it wants; the game thread moves towards it under a rate limit,
 * a deadband and a width quantum.
 *
 * <p>Controllers ({@code dynResController}):
 * <ul>
 * <li>{@code model}: GPU ms = a + b * x, x = the pixel fraction (scale squared), tracked by a two-state Kalman filter with
 * innovation gating (a lone outlier, e.g. a chunk-bake burst the scale cannot prevent and only reacts to late, is not
 * learnt; two heavier or three lighter in a row are a new regime). The scale is solved from the model for a target of
 * {@code dynResTargetPct} of the interval, lowered by the measured noise; when the per-pixel part is a small share of the
 * frame (bakes, draw-bound composite) the scale stays at its maximum, since a smaller image would not buy frame time.</li>
 * <li>{@code pi}: integral control of log x on log(target / GPU ms), the measurement re-expressed at the scale in use
 * now (a Smith predictor for the frames in flight), median of the last three samples.</li>
 * <li>{@code step}: the stock-engine pattern: over the interval -> drop at once to the proportional scale; under the
 * target for 15 samples -> raise by 2 %.</li>
 * </ul>
 */
public final class DynRes {
   private DynRes() {
   }

   // ================================================================ game thread: per-frame scale

   private static final Marker[] MARKERS = new Marker[8];
   private static float frameScale = 1.0F; // the scale of the frame being built
   private static int upFrames, downFrames; // consecutive frames the wish stood beyond the deadband
   private static int pinnedFrames, probeLeft; // dynResProbe
   private static boolean probeDown;
   private static int worldFrames;
   private static final int WARMUP_FRAMES = 120;
   private static volatile boolean overTarget; // render thread: the last samples' median is over the controller's target
   private static final CpuModel CPU = new CpuModel();
   private static long probes;
   private static long frameNo;

   static {
      for (int i = 0; i < MARKERS.length; i++) {
         MARKERS[i] = new Marker();
      }
   }

   /**
    * A frame's scale and interval, kept per SpriteRenderState (the object a frame is built in and handed over with; the
    * game reuses a few): the render thread looks its frame's up when it acquires it. A draw-list marker would not do:
    * Core.StartFrame clears the list (prePopulating) after the frame has begun.
    */
   private static final class Marker {
      float scale;
      float budgetMs;
      float capMs; // the cap's own interval (budgetMs is longer when the game thread is: dynResCpuAware)
      long frame;
      int bakes; // chunk-level bakes granted in this frame (set later in the frame by the bake scheduler)

      void latch() {
         RenderScale.latchRenderScale(this.scale);
         renderBudgetMs = this.budgetMs;
         renderCapMs = this.capMs;
         renderFrame = this.frame;
         renderBakes = this.bakes;
      }
   }

   private static final Object[] STATE_KEYS = new Object[8];

   private static Marker markerOf(Object state) {
      for (int i = 0; i < STATE_KEYS.length; i++) {
         if (STATE_KEYS[i] == state) {
            return MARKERS[i];
         }
         if (STATE_KEYS[i] == null) {
            STATE_KEYS[i] = state; // game thread only; published to the render thread by the frame hand-over
            return MARKERS[i];
         }
      }
      return MARKERS[MARKERS.length - 1];
   }

   /** Game thread: the number of the frame being built. */
   static long gameFrameNo() {
      return frameNo;
   }

   /** Render thread: the number of the frame being replayed (0 without dynRes). */
   static long renderFrameNo() {
      return on() ? renderFrame : 0L;
   }

   /** Render thread, after it acquired a frame (before the replay): that frame's scale, interval and bake count. */
   private static void latchFrame() {
      Object st = SpriteRenderer.instance.states.getRendering();
      for (int i = 0; i < STATE_KEYS.length; i++) {
         if (STATE_KEYS[i] == st) {
            MARKERS[i].latch();
            return;
         }
      }
   }

   private static Marker currentMarker; // the marker of the frame being built (game thread)
   private static int pendingBakes; // levels the bake scheduler left waiting at the end of the last frame's plan
   private static int lastBakeBudget;

   /** Game thread, BakeScheduler.plan (player 0): this frame bakes {@code granted} levels and leaves {@code waiting}. */
   public static void onBakesPlanned(int granted, int waiting, int budget) {
      Marker m = currentMarker;
      if (m != null) {
         m.bakes = granted;
      }
      pendingBakes = waiting;
      lastBakeBudget = budget;
   }

   /** Is the scale dynamic this frame (dynRes on and the scaled pass active)? */
   public static boolean on() {
      return RenderScale.dynamic();
   }

   static float minScale() {
      return Config.DYN_RES_MIN_PCT / 100.0F;
   }

   static float maxScale() {
      return RenderScale.dynamicMax();
   }

   /** Game thread, GameWindow.renderInternal right after SpriteRenderer.NewFrame: the scale of the frame now being built. */
   public static void beginFrame() {
      if (!on()) {
         return;
      }
      float max = maxScale(), min = minScale();
      long intervalNs = Config.DYN_RES_FPS > 0 ? 1_000_000_000L / Config.DYN_RES_FPS : Pacing.capIntervalNs();
      float budgetMs = intervalNs > 0L ? intervalNs / 1.0e6F : 0.0F;
      gameCapMs = budgetMs;
      float cpuCapScale = 2.0F;
      if (budgetMs > 0.0F && Config.DYN_RES_CPU_AWARE) {
         // dynResCpuAware: frames come no faster than the game thread builds them; the GPU only has to keep up with that
         // (a smaller image would not raise the frame rate of a CPU-bound scene)
         float busy = gameBusyMs();
         if (busy > budgetMs) {
            budgetMs = busy;
            // on shared-power hardware (an APU) the game thread itself slows down as the GPU works harder: the frame
            // rate comes first, so the scale stops where the game thread would be 3 % slower than at the lowest scale
            cpuCapScale = CPU.maxScale(minScale(), 1.03F);
         }
      }
      boolean world = GameWindow.isIngameState();
      worldFrames = world ? worldFrames + 1 : 0;
      if (worldFrames < WARMUP_FRAMES) {
         budgetMs = 0.0F; // the first frames in a world (loading, the first bakes) teach the model nothing true: no control yet
      }
      gameBudgetMs = world ? budgetMs : 0.0F;
      float s = frameScale;
      if (s < min || s > max) {
         s = Math.max(min, Math.min(max, s));
      }
      float target = budgetMs <= 0.0F || !world ? max : Math.max(min, Math.min(Math.min(max, cpuCapScale), targetScale));
      float forced = world ? forcedScale() : -1.0F;
      if (forced > 0.0F) {
         frameScale = quantize(forced, 0.1F, 1.0F);
         RenderScale.setGameScale(frameScale);
         queueMarker(frameScale);
         return;
      }
      boolean urgent = urgentDown;
      float band = Config.DYN_RES_DEADBAND_PCT / 100.0F * s;
      // time hysteresis: a wish beyond the deadband has to hold for a few frames before the scale follows it (down:
      // two frames, or at once when the model says the scale in use is over the interval; up: dynResUpDelayFrames)
      if (target < s - band || urgent && target < s) {
         downFrames++;
         upFrames = 0;
      } else if (target > s + band || target >= max && target > s) {
         upFrames++;
         downFrames = 0;
      } else {
         upFrames = 0;
         downFrames = 0;
      }
      if (target < s && (urgent || downFrames >= 2)) {
         s = Math.max(target, s - Config.DYN_RES_DOWN_PER_MILLE / 1000.0F);
      } else if (target > s && upFrames >= Config.DYN_RES_UP_DELAY_FRAMES) {
         s = Math.min(target, s + Config.DYN_RES_UP_PER_MILLE / 1000.0F);
      }
      frameScale = s;
      // dynResProbe: pinned at the minimum and still wanting less, the model may only believe the pixels matter (it has
      // seen one scale for a while); a second of that renders the next 8 frames 30 % larger, so it measures the slope again
      float frameOnly0 = s;
      if (Config.DYN_RES_PROBE && world && budgetMs > 0.0F) {
         if (probeLeft > 0) {
            probeLeft--;
            frameOnly0 = probeDown ? Math.max(min, max - 0.3F) : Math.min(max, min + 0.3F);
         } else if (s <= min + 0.005F && target <= min + 0.005F) {
            if (++pinnedFrames >= Math.max(30, Math.round(1000.0F / budgetMs))) {
               pinnedFrames = 0;
               probeLeft = 8;
               probeDown = false;
               probes++;
            }
         } else if (s >= max - 0.005F && target >= max - 0.005F && overTarget) {
            // pinned at the top while the GPU is over its target: the model holds that the pixels are cheap, and at one
            // scale it cannot learn otherwise; 8 frames 30 % smaller test it
            if (++pinnedFrames >= Math.max(30, Math.round(1000.0F / budgetMs))) {
               pinnedFrames = 0;
               probeLeft = 8;
               probeDown = true;
               probes++;
            }
         } else {
            pinnedFrames = 0;
         }
      }
      // dynResBakeFeedforward: a frame the bake scheduler will fill with the levels left waiting renders smaller for
      // this frame only (the model's bake term), so the burst does not push it over the interval
      float frameOnly = frameOnly0;
      int predicted = Math.min(pendingBakes, Math.max(1, lastBakeBudget));
      if (Config.DYN_RES_BAKE_FEEDFORWARD && predicted > 0 && budgetMs > 0.0F && world && snapInit) {
         float ff = solveSnapshot(budgetMs, min, max, predicted);
         if (ff < frameOnly) {
            frameOnly = ff;
            feedforwardFrames++;
         }
      }
      frameOnly = quantize(frameOnly, min, max);
      RenderScale.setGameScale(frameOnly);
      queueMarker(frameOnly);
   }

   private static final float[] BUSY = new float[16];
   private static int busyCount;
   private static final float[] BUSY_SORT = new float[16];

   /** Game thread, RenderThread.Ready after the hand-off: this frame's own time, step start to the hand-off (no limiter, no ready-slot wait). */
   public static void pushed(long readyStartNs) {
      long start = Pacing.stepStartNs();
      if (!on() || start == 0L || readyStartNs <= start) {
         return;
      }
      float busyMs = (readyStartNs - start) / 1.0e6F;
      BUSY[busyCount++ & 15] = busyMs;
      Marker m = currentMarker;
      if (m != null && worldFrames >= WARMUP_FRAMES) {
         CPU.update(m.scale * m.scale, busyMs);
      }
   }

   /**
    * The game thread's own time per frame against the pixel fraction (recursive least squares, forgetting 0.995):
    * busy = p + q x. On a desktop with its own GPU q is ~0; on an APU the GPU's load slows the CPU and q > 0.
    */
   static final class CpuModel {
      double p, q;
      double p00 = 100.0, p01, p11 = 100.0;
      long n;

      void update(double x, double busy) {
         if (busy <= 0.0 || busy > 200.0) {
            return;
         }
         double lam = 0.995;
         double k0n = this.p00 + this.p01 * x, k1n = this.p01 + this.p11 * x;
         double den = lam + k0n + k1n * x;
         double k0 = k0n / den, k1 = k1n / den;
         double res = busy - (this.p + this.q * x);
         this.p += k0 * res;
         this.q += k1 * res;
         double n00 = (this.p00 - k0 * k0n) / lam, n01 = (this.p01 - k0 * k1n) / lam, n11 = (this.p11 - k1 * k1n) / lam;
         this.p00 = Math.min(1e4, n00);
         this.p01 = n01;
         this.p11 = Math.min(1e4, n11);
         this.n++;
      }

      /** The largest scale whose predicted game-thread time is within {@code factor} of the time at {@code min}. */
      float maxScale(float min, float factor) {
         if (this.n < 240 || this.q <= 0.0 || this.p11 > 1.0) {
            return 2.0F; // not learnt yet, or the scale does not slow the CPU
         }
         double xMin = min * min;
         double bound = (this.p + this.q * xMin) * factor;
         double x = (bound - this.p) / this.q;
         return x <= xMin ? min : (float)Math.sqrt(x);
      }
   }

   /** The median of the last 16 frames' own game-thread time. */
   private static float gameBusyMs() {
      int n = Math.min(16, busyCount);
      if (n < 4) {
         return 0.0F;
      }
      System.arraycopy(BUSY, 0, BUSY_SORT, 0, n);
      java.util.Arrays.sort(BUSY_SORT, 0, n);
      return BUSY_SORT[n / 2];
   }

   private static void queueMarker(float scale) {
      Marker m = markerOf(SpriteRenderer.instance.states.getPopulating());
      m.scale = scale;
      m.budgetMs = gameBudgetMs;
      m.capMs = gameCapMs;
      m.frame = ++frameNo;
      m.bakes = 0;
      currentMarker = m;
      pendingBakes = 0;
   }

   private static float[] forceSpec;
   private static boolean forceParsed;
   private static long forceStartNs;

   /** devDynResForce: the open-loop scale of this frame, or -1. */
   private static float forcedScale() {
      if (!forceParsed) {
         forceParsed = true;
         String s = Config.DEV_DYN_RES_FORCE;
         if (s != null && !s.isBlank()) {
            try {
               String[] p = s.split(",");
               float shape = p.length > 3 ? switch (p[3].trim()) { case "sine" -> 1; case "ramp" -> 2; default -> 0; } : 0;
               forceSpec = new float[] {Float.parseFloat(p[0].trim()) / 100.0F, Float.parseFloat(p[1].trim()) / 100.0F, Float.parseFloat(p[2].trim()), shape};
               Log.info("dynRes: forced scale " + p[0] + ".." + p[1] + " % every " + p[2] + " s");
            } catch (RuntimeException e) {
               Log.warn("dynRes: bad devDynResForce " + s + ": " + e);
            }
         }
      }
      float[] f = forceSpec;
      if (f == null) {
         return -1.0F;
      }
      long now = System.nanoTime();
      if (forceStartNs == 0L) {
         forceStartNs = now;
      }
      double phase = ((now - forceStartNs) / 1.0e9) % Math.max(0.01, f[2]) / Math.max(0.01, f[2]);
      double w = f[3] == 1 ? 0.5 - 0.5 * Math.cos(2.0 * Math.PI * phase) : f[3] == 2 ? phase : phase < 0.5 ? 0.0 : 1.0;
      return (float)(f[0] + (f[1] - f[0]) * w);
   }

   /** A scale whose render width is a multiple of dynResStepPx (the maximum stays exact). */
   static float quantize(float s, float min, float max) {
      if (s >= max) {
         return max;
      }
      int w = RenderScale.fullWidth(0);
      int step = Config.DYN_RES_STEP_PX;
      if (w <= 0 || step <= 1) {
         return s;
      }
      int qw = Math.round(s * w / step) * step;
      float q = (float)qw / w;
      if (q < min) {
         q = (float)(((int)Math.ceil(min * w / step)) * step) / w;
      }
      return Math.min(max, q);
   }

   /** The scale and frame setting changed (RenderScale.reconfigure): start again from the maximum. */
   static void reset() {
      frameScale = maxScale();
      targetScale = maxScale();
      urgentDown = false;
      resetModel = true;
   }

   // ================================================================ shared game <-> render thread

   private static volatile float targetScale = 1.0F; // the controller's wish (render thread writes)
   private static volatile boolean urgentDown; // the last samples were over the interval itself: skip the deadband going down
   private static volatile float gameBudgetMs; // game thread writes: the interval of the frame being built (0 = none)
   private static float gameCapMs; // game thread: the cap's own interval of the frame being built
   private static volatile boolean resetModel; // reconfigure asks the render thread to start the model afresh
   // the model as last learnt, for the game thread's per-frame bake feedforward
   private static volatile double snapA, snapB, snapC, snapSigma;
   private static volatile boolean snapInit;
   private static volatile boolean snapPixelsMinor; // the model is sure the pixels are a small share of the frame
   private static long feedforwardFrames;

   private static float solveSnapshot(float budgetMs, float min, float max, int bakes) {
      double t = Math.max(0.6 * budgetMs, Math.min(budgetMs * Config.DYN_RES_TARGET_PCT / 100.0, budgetMs - snapSigma));
      double a = snapA + snapC * bakes, b = snapB;
      double xMax = max * max;
      if (a + b * xMax <= t || snapPixelsMinor) {
         return max;
      }
      double x = (t - a) / Math.max(1e-6, b);
      return x <= min * min ? min : (float)Math.sqrt(Math.min(xMax, x));
   }

   // ================================================================ render thread: GPU time per frame

   private static final int SLOTS = 16;
   private static int[] queryIds;
   private static boolean glChecked, glOk;
   private static final long[] slotFrame = new long[SLOTS]; // 0 = free
   private static final long[] slotCpuBegin = new long[SLOTS];
   private static final long[] slotCpuEnd = new long[SLOTS];
   private static final float[] slotScale = new float[SLOTS];
   private static final float[] slotBudget = new float[SLOTS];
   private static final boolean[] slotCpuBound = new boolean[SLOTS];
   private static final int[] slotLoad = new int[SLOTS];
   private static final int[] slotBakes = new int[SLOTS];
   private static int activeSlot = -1;
   private static int nextSlot;
   private static long gpuToCpuNs, lastCalibrationNs;
   private static long cpuBeginNs;

   // latched from the frame's marker / dev load drawer (render thread)
   private static float renderBudgetMs;
   private static float renderCapMs;
   private static long renderFrame;
   private static int renderBakes;
   private static int renderLoadIters;

   /** Render thread, before SpriteRenderer.postRender. */
   public static void gpuBegin() {
      if (on()) {
         latchFrame();
      }
      if (!on() && !logging()) {
         return;
      }
      try {
         if (!glChecked) {
            initGl();
         }
         if (!glOk) {
            return;
         }
         long now = System.nanoTime();
         collect(now);
         int q = nextSlot;
         if (slotFrame[q] != 0L) {
            activeSlot = -1; // every slot still in flight
            return;
         }
         GL33.glQueryCounter(queryIds[2 * q], GL33.GL_TIMESTAMP);
         cpuBeginNs = System.nanoTime();
         activeSlot = q;
         renderLoadIters = 0;
      } catch (Throwable t) {
         glOk = false;
         Log.warn("dynRes: timer queries failed: " + t);
      }
   }

   /** Render thread, after SpriteRenderer.postRender (before the swap). */
   public static void gpuEnd() {
      int q = activeSlot;
      if (q < 0 || !glOk) {
         return;
      }
      try {
         GL33.glQueryCounter(queryIds[2 * q + 1], GL33.GL_TIMESTAMP);
         if (queryBuf != null) { // pacingQueryBuffer (tail2)
            queryBuf.request(2 * q, queryIds[2 * q]);
            queryBuf.request(2 * q + 1, queryIds[2 * q + 1]);
         }
         slotCpuBegin[q] = cpuBeginNs;
         slotCpuEnd[q] = System.nanoTime();
         slotScale[q] = RenderScale.scale();
         slotBudget[q] = renderBudgetMs;
         slotCpuBound[q] = renderBudgetMs > renderCapMs + 0.01F;
         slotLoad[q] = renderLoadIters;
         slotBakes[q] = renderBakes;
         slotFrame[q] = Math.max(1L, renderFrame);
         nextSlot = (q + 1) % SLOTS;
         activeSlot = -1;
      } catch (Throwable t) {
         glOk = false;
         Log.warn("dynRes: timer queries failed: " + t);
      }
   }

   private static boolean logging() {
      return Config.DYN_RES_LOG && Overrides.enabled();
   }

   private static void initGl() {
      glChecked = true;
      GLCapabilities caps = GL.getCapabilities();
      glOk = (caps.OpenGL33 || caps.GL_ARB_timer_query) && CoreGl.timerQueries();
      if (!glOk) {
         Log.warn("dynRes: no GL timer queries: the scale stays at " + Config.DYN_RES_MAX_PCT + " %");
         return;
      }
      queryIds = new int[2 * SLOTS];
      GL15.glGenQueries(queryIds);
      queryBuf = QueryBuf.create(2 * SLOTS, "dynRes"); // pacingQueryBuffer (tail2): no per-frame result polling
      calibrate(System.nanoTime());
   }

   private static QueryBuf queryBuf; // pacingQueryBuffer (tail2)

   private static void calibrate(long now) {
      long before = System.nanoTime();
      long gpu = GL32.glGetInteger64(GL33.GL_TIMESTAMP);
      long after = System.nanoTime();
      gpuToCpuNs = (before + after) / 2L - gpu;
      lastCalibrationNs = now;
   }

   private static void collect(long now) {
      if (now - lastCalibrationNs > (queryBuf != null ? 8_000_000_000L : 1_000_000_000L)) { // pacingQueryBuffer (tail2): every 8 s as Pacing (glGetInteger64(GL_TIMESTAMP) waits for the threaded driver)
         calibrate(now);
      }
      // oldest first, so the controller sees the frames in order
      for (int k = 0; k < SLOTS; k++) {
         int q = (nextSlot + k) % SLOTS;
         if (slotFrame[q] == 0L) {
            continue;
         }
         long t0, t1;
         if (queryBuf != null) { // pacingQueryBuffer (tail2): from client memory
            t0 = queryBuf.get(2 * q);
            t1 = queryBuf.get(2 * q + 1);
            if (t0 == -1L || t1 == -1L) {
               continue;
            }
         } else {
            if (GL15.glGetQueryObjecti(queryIds[2 * q + 1], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
               continue;
            }
            t0 = GL33.glGetQueryObjecti64(queryIds[2 * q], GL15.GL_QUERY_RESULT);
            t1 = GL33.glGetQueryObjecti64(queryIds[2 * q + 1], GL15.GL_QUERY_RESULT);
         }
         long frame = slotFrame[q];
         slotFrame[q] = 0L;
         float gpuMs = Math.max(0L, t1 - t0) / 1.0e6F;
         float cpuMs = (slotCpuEnd[q] - slotCpuBegin[q]) / 1.0e6F;
         float startLagMs = (t0 + gpuToCpuNs - slotCpuBegin[q]) / 1.0e6F;
         float endLagMs = (t1 + gpuToCpuNs - slotCpuEnd[q]) / 1.0e6F;
         sample(frame, slotScale[q], slotBudget[q], gpuMs, cpuMs, startLagMs, endLagMs, slotLoad[q], slotBakes[q], slotCpuBound[q]);
      }
   }

   // ================================================================ controllers (render thread)

   private static final Model MODEL = new Model();
   private static final float[] recent = new float[16];
   private static int recentCount;
   private static int underCount;
   private static long samples, outliers, starvedSkips;

   /** A GPU ms at least this close behind the render thread's last command: the GPU waited for the CPU (its span is the CPU's). */
   private static final float STARVED_LAG_MS = 0.25F;

   private static void sample(long frame, float scale, float budgetMs, float gpuMs, float cpuMs, float startLag, float endLag, int load, int bakes, boolean cpuBound) {
      samples++;
      if (resetModel) {
         resetModel = false;
         MODEL.reset();
         recentCount = 0;
         underCount = 0;
      }
      recent[recentCount++ & 15] = gpuMs;
      boolean starved = endLag < STARVED_LAG_MS;
      int flags = starved ? 1 : 0;
      float wish = targetScale;
      boolean urgent = false;
      if (on() && budgetMs > 0.0F && scale > 0.0F) {
         // the game thread sets the pace (dynResCpuAware): the GPU only has to stay under it, its spikes go into the frames
         // in flight; else dynResTargetPct of the cap's interval
         float target = budgetMs * (cpuBound ? Config.DYN_RES_CPU_TARGET_PCT : Config.DYN_RES_TARGET_PCT) / 100.0F;
         float x = scale * scale;
         float min = minScale(), max = maxScale();
         boolean skip = Config.DYN_RES_STARVE_GATE && starved && gpuMs > target;
         if (skip) {
            starvedSkips++;
            flags |= 2;
         }
         switch (Config.DYN_RES_CONTROLLER) {
            case "pi" -> {
               if (!skip) {
                  float med = median3();
                  // the measurement re-expressed at the scale in use now (frames in flight already moved it)
                  float now = RenderScale.scale();
                  float tNow = med * (now * now) / x;
                  float e = (float)Math.log(target / Math.max(0.05F, tNow));
                  float xNew = now * now * (float)Math.exp(0.35F * e);
                  wish = (float)Math.sqrt(Math.max(min * min, Math.min(max * max, xNew)));
               }
            }
            case "step" -> {
               if (!skip && median3() > budgetMs * 0.98F) {
                  wish = Math.max(min, scale * (float)Math.sqrt(target / median3()));
                  underCount = 0;
               } else if (maxRecent(15) < target * 0.9F) {
                  if (++underCount >= 15) {
                     wish = Math.min(max, RenderScale.scale() + 0.02F);
                     underCount = 0;
                  }
               } else {
                  underCount = 0;
               }
            }
            default -> {
               if (!skip) {
                  int o = MODEL.update(x, gpuMs, Config.DYN_RES_BAKE_TERM || Config.DYN_RES_BAKE_FEEDFORWARD ? bakes : 0);
                  if (o != 0) {
                     outliers++;
                     flags |= 4;
                  }
               }
               wish = MODEL.solve(budgetMs, target, min, max, MODEL.bakeMean, cpuBound); // a frame with the average bakes
               snapA = MODEL.a();
               snapB = MODEL.b();
               snapC = MODEL.c();
               snapSigma = MODEL.headroom();
               snapInit = MODEL.init;
               snapPixelsMinor = MODEL.pixelsMinor(max);
            }
         }
         // over the interval: the model's own prediction at the scale in use now (a lone spike is not a reason to drop);
         // the other controllers: the median of the last five samples
         if ("model".equals(Config.DYN_RES_CONTROLLER) || !"pi".equals(Config.DYN_RES_CONTROLLER) && !"step".equals(Config.DYN_RES_CONTROLLER)) {
            float now = RenderScale.scale();
            urgent = MODEL.init && MODEL.a() + MODEL.b() * now * now + MODEL.c() * MODEL.bakeMean > budgetMs;
         } else {
            urgent = !skip && median5() > budgetMs;
         }
      }
      targetScale = wish;
      urgentDown = urgent;
      overTarget = on() && budgetMs > 0.0F && median3() > budgetMs * Config.DYN_RES_TARGET_PCT / 100.0F;
      if (logging()) {
         log(frame, scale, budgetMs, gpuMs, cpuMs, startLag, endLag, wish, flags, load, bakes);
      }
   }

   private static float median5() {
      int n = Math.min(5, recentCount);
      float[] v = new float[n];
      for (int i = 0; i < n; i++) {
         v[i] = recent[(recentCount - 1 - i) & 15];
      }
      java.util.Arrays.sort(v);
      return n == 0 ? 0.0F : v[n / 2];
   }

   private static float median3() {
      int n = Math.min(3, recentCount);
      float a = recent[(recentCount - 1) & 15];
      if (n < 3) {
         return a;
      }
      float b = recent[(recentCount - 2) & 15], c = recent[(recentCount - 3) & 15];
      return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
   }

   private static float maxRecent(int n) {
      n = Math.min(n, Math.min(16, recentCount));
      float m = 0.0F;
      for (int i = 1; i <= n; i++) {
         m = Math.max(m, recent[(recentCount - i) & 15]);
      }
      return m;
   }

   /**
    * GPU ms = a + b * x + c * n (x = pixel fraction, n = chunk-level bakes in the frame), a three-state Kalman filter.
    * Process noise lets the terms drift (the scene changes); the measurement noise is learnt from the accepted residuals.
    * Gating: a residual beyond 3 sigma is an outlier and not learnt, unless three in a row share its sign (a new regime:
    * the covariance is opened up). The bake term only sees data with dynResBakeFeedforward (n = 0 otherwise).
    */
   static final class Model {
      static final int N = 3;
      final double[] th = new double[N]; // a ms, b ms per unit pixel fraction, c ms per bake
      final double[][] p = new double[N][N]; // covariance
      double r; // measurement variance (ms^2)
      double mad; // mean absolute residual (ms), the robust spread the target keeps clear of the interval
      double bakeMean; // the bakes of an average frame (EMA): the scale is solved for it, and the headroom is the spread around it
      int runSign, runLength;
      boolean init;
      private final double[] h = new double[N], ph = new double[N];

      double a() {
         return this.th[0];
      }

      double b() {
         return this.th[1];
      }

      double c() {
         return this.th[2];
      }

      void reset() {
         this.init = false;
         this.runLength = 0;
      }

      /** Returns 0 for a learnt sample, +-1 for a gated outlier. */
      int update(float x, float t, int bakes) {
         if (!this.init) {
            // prior: half the frame fixed, half per pixel, 0.05 ms a bake; wide covariance
            this.th[0] = 0.5 * t;
            this.th[1] = 0.5 * t / Math.max(0.05, x);
            this.th[2] = 0.05;
            for (double[] row : this.p) {
               java.util.Arrays.fill(row, 0.0);
            }
            this.p[0][0] = (0.5 * t) * (0.5 * t);
            this.p[1][1] = this.p[0][0] / (x * x);
            this.p[2][2] = 0.1 * 0.1;
            this.r = Math.max(0.0025, 0.01 * t * t);
            this.mad = Math.sqrt(this.r);
            this.bakeMean = bakes;
            this.init = true;
            return 0;
         }
         // predict. The frame mostly gets heavier or lighter as a whole (fog rolls in, a town comes into view): drift
         // proportional to the terms keeps their split, so a jump seen at one scale (where the split cannot be told)
         // moves both in proportion instead of loading it all onto the fixed term; a little free drift lets the split
         // itself move once the scale varies. The bake term drifts slowly on its own.
         double qr = Config.DYN_RES_DRIFT_PER_MILLE / 1000.0;
         proportional(qr * qr);
         independent(qr * qr);
         this.p[2][2] += 1e-6;
         this.h[0] = 1.0;
         this.h[1] = x;
         this.h[2] = bakes;
         this.bakeMean += 0.01 * (bakes - this.bakeMean);
         double pred = 0.0;
         for (int i = 0; i < N; i++) {
            pred += this.h[i] * this.th[i];
         }
         double res = t - pred;
         double s = innovation();
         int sign = res > 0 ? 1 : -1;
         if (res * res > 9.0 * s) {
            if (sign == this.runSign) {
               this.runLength++;
            } else {
               this.runSign = sign;
               this.runLength = 1;
            }
            if (this.runLength < (sign > 0 ? 2 : 3)) { // heavier: two in a row are a new regime (a late reaction costs frames); lighter: three
               return sign;
            }
            // a new regime: the frame's cost is unknown up to a common factor, the split is kept (at one scale the data
            // cannot move it the right way); the per-frame independent drift lets the scales that follow correct it
            proportional(1.0);
            s = innovation();
            this.runLength = 0;
         } else {
            this.runLength = 0;
            this.r += 0.02 * (Math.max(0.0004, res * res) - this.r); // accepted residuals teach the noise level
            // the headroom's spread: around the frame of average bakes, so a bake burst (explained by the bake term, not
            // learnt as a heavier scene) still counts as the tail the interval has to absorb
            double typical = res + this.th[2] * (bakes - this.bakeMean);
            this.mad += 0.02 * (Math.abs(typical) - this.mad);
         }
         for (int i = 0; i < N; i++) {
            this.th[i] += this.ph[i] / s * res;
         }
         for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
               this.p[i][j] -= this.ph[i] * this.ph[j] / s;
            }
            this.p[i][i] = Math.max(1e-7, this.p[i][i]);
         }
         // non-negative terms; a clipped term moves its share to the fixed one (the prediction at x stays)
         if (this.th[2] < 0.0) {
            this.th[0] += this.th[2] * bakes;
            this.th[2] = 0.0;
         }
         if (this.th[1] < 0.0) {
            this.th[0] += this.th[1] * x;
            this.th[1] = 0.0;
         }
         if (this.th[0] < 0.0) {
            this.th[1] += this.th[0] / Math.max(0.05, x);
            this.th[0] = 0.0;
         }
         return 0;
      }

      /** The per-pixel term is under 15 % of the frame at the largest scale, and its uncertainty under 10 %. */
      boolean pixelsMinor(float max) {
         double xMax = max * max, full = this.th[0] + this.th[1] * xMax;
         return this.init && this.th[1] * xMax < 0.15 * full && Math.sqrt(this.p[1][1]) * xMax < 0.1 * full;
      }

      /** Adds v * (a, b)(a, b)' to the covariance of the fixed and per-pixel terms: uncertainty in their common factor. */
      private void proportional(double v) {
         double a = Math.max(0.05, this.th[0]), b = Math.max(0.05, this.th[1]);
         this.p[0][0] += v * a * a;
         this.p[0][1] += v * a * b;
         this.p[1][0] += v * a * b;
         this.p[1][1] += v * b * b;
      }

      /** Adds v * diag(a^2, b^2): each term uncertain by its own factor, so the split can move once the scale varies. */
      private void independent(double v) {
         double a = Math.max(0.05, this.th[0]), b = Math.max(0.05, this.th[1]);
         this.p[0][0] += v * a * a;
         this.p[1][1] += v * b * b;
      }

      /** P h into ph; returns h' P h + r. */
      private double innovation() {
         double s = this.r;
         for (int i = 0; i < N; i++) {
            double v = 0.0;
            for (int j = 0; j < N; j++) {
               v += this.p[i][j] * this.h[j];
            }
            this.ph[i] = v;
            s += this.h[i] * v;
         }
         return s;
      }

      float sigma() {
         return (float)Math.sqrt(this.r);
      }

      /** The headroom kept under the interval: dynResSigmaPct / 100 x 1.25 x the mean absolute residual (= sigma for a normal spread). */
      float headroom() {
         return (float)(Config.DYN_RES_SIGMA_PCT / 100.0 * 1.25 * this.mad);
      }

      /**
       * The scale whose predicted GPU time meets the target (less one sigma of noise), within [min, max], for a frame
       * expected to bake {@code bakes} chunk levels.
       */
      float solve(float budgetMs, float target, float min, float max, int bakes) {
         return solve(budgetMs, target, min, max, bakes, false);
      }

      float solve(float budgetMs, float target, float min, float max, double bakes, boolean noHeadroom) {
         return solveAt(budgetMs, target, min, max, bakes, noHeadroom);
      }

      /** noHeadroom: the target is the whole interval (a CPU-paced frame). */
      private float solveAt(float budgetMs, float target, float min, float max, double bakes, boolean noHeadroom) {
         if (!this.init) {
            return max;
         }
         double t = noHeadroom ? target : Math.min(target, budgetMs - headroom());
         t = Math.max(0.6 * budgetMs, t);
         double a = this.th[0] + this.th[2] * bakes, b = this.th[1];
         double xMax = max * max;
         double full = a + b * xMax;
         if (full <= t) {
            return max;
         }
         // pixels are a small share of the frame, and the model is sure of it: a smaller image would not buy the time back
         if (b * xMax < 0.15 * full && Math.sqrt(this.p[1][1]) * xMax < 0.1 * full) {
            return max;
         }
         double x = (t - a) / Math.max(1e-6, b);
         if (x <= min * min) {
            return min;
         }
         return (float)Math.sqrt(Math.min(xMax, x));
      }
   }

   // ================================================================ log

   private static BufferedWriter log;
   private static boolean logFailed;
   private static long logStartNs, logStartEpochMs, lastFlushNs;
   private static final StringBuilder LINE = new StringBuilder(160);

   private static void log(long frame, float scale, float budgetMs, float gpuMs, float cpuMs, float startLag, float endLag, float wish, int flags, int load, int bakes) {
      if (logFailed) {
         return;
      }
      try {
         long now = System.nanoTime();
         if (log == null) {
            String dir = ZomboidFileSystem.instance.getCacheDir();
            if (dir == null) {
               return;
            }
            File file = new File(dir, "pzopt-dynres.out");
            log = new BufferedWriter(new FileWriter(file, false), 1 << 16);
            logStartNs = now;
            logStartEpochMs = System.currentTimeMillis();
            log.write("# epoch_ms frame scale budget_ms gpu_ms cpu_ms start_lag_ms end_lag_ms target_scale model_a model_b sigma flags load_iters bakes model_c"
               + " (flags: 1 starved, 2 starved+over skipped, 4 gated outlier; dynRes=" + Config.DYN_RES + " controller=" + Config.DYN_RES_CONTROLLER
               + " target=" + Config.DYN_RES_TARGET_PCT + "% min=" + Config.DYN_RES_MIN_PCT + "% max=" + Config.DYN_RES_MAX_PCT + "% upscaler=" + RenderScale.mode() + ")\n");
            Log.info("dynRes: logging frames to " + file);
         }
         StringBuilder b = LINE;
         b.setLength(0);
         b.append(logStartEpochMs + (now - logStartNs) / 1_000_000L).append(' ').append(frame).append(' ');
         f(b, scale, 4).append(' ');
         f(b, budgetMs, 3).append(' ');
         f(b, gpuMs, 3).append(' ');
         f(b, cpuMs, 3).append(' ');
         f(b, startLag, 3).append(' ');
         f(b, endLag, 3).append(' ');
         f(b, wish, 4).append(' ');
         f(b, (float)MODEL.a(), 3).append(' ');
         f(b, (float)MODEL.b(), 3).append(' ');
         f(b, MODEL.sigma(), 3).append(' ');
         b.append(flags).append(' ').append(load).append(' ').append(bakes).append(' ');
         f(b, (float)MODEL.c(), 4).append('\n');
         log.write(b.toString());
         if (now - lastFlushNs > 1_000_000_000L) {
            log.flush();
            lastFlushNs = now;
         }
      } catch (IOException | RuntimeException e) {
         logFailed = true;
         Log.warn("dynRes: log stopped: " + e);
      }
   }

   private static StringBuilder f(StringBuilder b, float v, int decimals) {
      return b.append(String.format(Locale.ROOT, "%." + decimals + "f", v));
   }

   public static void flushLog() {
      BufferedWriter w = log;
      if (w != null) {
         try {
            w.flush();
         } catch (IOException ignored) {
         }
      }
   }

   /** The overlay's first line: "   res 73 %" while the scale is dynamic. */
   static String overlayText() {
      return on() ? String.format(Locale.ROOT, "   res %.0f %%", frameScale * 100.0F) : "";
   }

   /** One line for the console / harness summary. */
   public static String describe() {
      if (!on()) {
         return "off";
      }
      return String.format(Locale.ROOT, "%s scale=%.3f target=%.3f samples=%d outliers=%d starvedSkips=%d feedforwardFrames=%d probes=%d cpuP=%.3f cpuQ=%.3f a=%.3f b=%.3f c=%.4f sigma=%.3f headroom=%.3f", Config.DYN_RES_CONTROLLER,
         frameScale, targetScale, samples, outliers, starvedSkips, feedforwardFrames, probes, CPU.p, CPU.q, MODEL.a(), MODEL.b(), MODEL.c(), MODEL.sigma(), MODEL.headroom());
   }

   // ================================================================ dev rig: synthetic per-pixel GPU load (devDynResLoad)

   private static int[] loadSpec; // low, high, period ms, shape (0 square, 1 sine, 2 ramp)
   private static boolean loadParsed;
   private static long loadStartNs;
   private static final LoadDrawer[] LOAD = {new LoadDrawer(), new LoadDrawer(), new LoadDrawer(), new LoadDrawer()};
   private static int nextLoad;
   private static int loadProgram, loadItersLoc, loadZeroLoc, loadVbo;
   private static boolean loadFailed;

   private static int[] loadSpec() {
      if (!loadParsed) {
         loadParsed = true;
         String s = Config.DEV_DYN_RES_LOAD;
         if (s != null && !s.isBlank()) {
            try {
               String[] p = s.split(",");
               int shape = p.length > 3 ? switch (p[3].trim()) { case "sine" -> 1; case "ramp" -> 2; default -> 0; } : 0;
               loadSpec = new int[] {Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), (int)(Float.parseFloat(p[2].trim()) * 1000.0F), shape};
               Log.info("dynRes: dev load " + loadSpec[0] + ".." + loadSpec[1] + " iterations, period " + loadSpec[2] + " ms, shape " + shape);
            } catch (RuntimeException e) {
               Log.warn("dynRes: bad devDynResLoad " + s + ": " + e);
            }
         }
      }
      return loadSpec;
   }

   /** Game thread, MultiTextureFBO2.render before the resolve: queue this frame's synthetic load into the world image. */
   public static void queueDevLoad() {
      int[] spec = loadSpec();
      if (spec == null || !GameWindow.isIngameState()) {
         return;
      }
      long now = System.nanoTime();
      if (loadStartNs == 0L) {
         loadStartNs = now;
      }
      double phase = ((now - loadStartNs) / 1.0e6) % Math.max(1, spec[2]) / Math.max(1, spec[2]);
      double w = switch (spec[3]) {
         case 1 -> 0.5 - 0.5 * Math.cos(2.0 * Math.PI * phase);
         case 2 -> phase;
         default -> phase < 0.5 ? 0.0 : 1.0;
      };
      int iters = (int)Math.round(spec[0] + (spec[1] - spec[0]) * w);
      if (iters <= 0) {
         return;
      }
      LoadDrawer d = LOAD[nextLoad++ & 3];
      d.iters = iters;
      SpriteRenderer.instance.drawGeneric(d);
   }

   private static final class LoadDrawer extends TextureDraw.GenericDrawer {
      int iters;

      @Override
      public void render() {
         renderLoadIters = this.iters;
         try {
            drawLoad(this.iters);
         } catch (Throwable t) {
            loadFailed = true;
            Log.warn("dynRes: dev load failed: " + t);
         }
      }
   }

   private static final String LOAD_FRAG = String.join("\n",
      "#version 330",
      "uniform int iters;",
      "uniform float zero;",
      "out vec4 fragColor;",
      "void main() {",
      "   vec2 p = gl_FragCoord.xy * 0.001;",
      "   float acc = 0.0;",
      "   for (int i = 0; i < iters; i++) {",
      "      acc = sin(p.x * float(i) + acc) * cos(p.y + acc);",
      "   }",
      "   fragColor = vec4(acc * zero);",
      "}");

   private static void drawLoad(int iters) {
      if (loadFailed) {
         return;
      }
      TextureFBO world = Core.getInstance().getOffscreenBuffer();
      if (world == null) {
         return;
      }
      if (loadProgram == 0) {
         loadProgram = Shaders.program("dynres load", Upscaler.QUAD_VERT, LOAD_FRAG);
         if (loadProgram == 0) {
            loadFailed = true;
            return;
         }
         loadItersLoc = GL20.glGetUniformLocation(loadProgram, "iters");
         loadZeroLoc = GL20.glGetUniformLocation(loadProgram, "zero");
         loadVbo = GL15.glGenBuffers();
         java.nio.FloatBuffer quad = org.lwjgl.BufferUtils.createFloatBuffer(8);
         quad.put(-1.0F).put(-1.0F).put(1.0F).put(-1.0F).put(-1.0F).put(1.0F).put(1.0F).put(1.0F).flip();
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, loadVbo);
         GL15.glBufferData(GL15.GL_ARRAY_BUFFER, quad, GL15.GL_STATIC_DRAW);
      }
      int previousFbo = TextureFBO.lastID;
      int[] r = RenderScale.scaledRect(0);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, world.getBufferId());
      GL11.glViewport(r[0], r[1], r[2], r[3]);
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL11.glDisable(GL11.GL_STENCIL_TEST);
      GL11.glDisable(GL11.GL_CULL_FACE);
      GL11.glDepthMask(false);
      GL11.glEnable(GL11.GL_BLEND);
      GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, loadVbo);
      for (int i = 1; i < 5; i++) {
         GL20.glDisableVertexAttribArray(i);
      }
      GL20.glEnableVertexAttribArray(0);
      GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
      GL20.glUseProgram(loadProgram);
      GL20.glUniform1i(loadItersLoc, iters);
      GL20.glUniform1f(loadZeroLoc, 0.0F);
      GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
      GL11.glViewport(0, 0, Core.width, Core.height);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      Texture.lastTextureID = -1;
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
      for (int i = 0; i < 5; i++) {
         GL20.glEnableVertexAttribArray(i);
      }
      GL20.glUseProgram(0);
      GL11.glDepthMask(true);
      GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
      GLStateRenderThread.restore();
      SpriteRenderer.ringBuffer.restoreVbos = true;
      SpriteRenderer.ringBuffer.restoreBoundTextures = true;
   }
}
