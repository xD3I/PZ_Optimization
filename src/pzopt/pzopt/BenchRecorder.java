package pzopt;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Arrays;

/**
 * What a benchmark pass measures between the route's start and end (docs/plan-builtin-benchmark.md, techniques 5-11,
 * 13). Fed from hooks that already exist for the overlay and the harness, so a pass costs what a harness run costs:
 * <ul>
 * <li>{@link #onFrame} from {@code Overlay.logFrame} (render thread, a few frames late so the GPU timer query of the
 * frame has resolved): presented-frame interval, GPU time, the render thread's time from the frame's acquire to the
 * swap's return, the game thread's last step;</li>
 * <li>{@link #onPacing} from {@code Pacing.write}: game step start to GPU completion (latency);</li>
 * <li>{@link #onTick} once per game frame (Harness.onFrame): focus and pause checks only; utilization, power, clocks and
 * heap are sampled at 2 Hz by the daemon thread pzopt-bench-sampler.</li>
 * </ul>
 * Arrays grow; a 90 s pass at 500 fps is 45k frames, 1 MB.
 */
public final class BenchRecorder {
   static volatile boolean on;
   private static long startNs, endNs;

   /** Seconds since the recording started (the HUD's sweep window), 0 before it. */
   static double elapsedS() {
      return on ? (System.nanoTime() - startNs) / 1e9 : 0;
   }
   private static int n;
   private static float[] frameMs = new float[16384], gpuMs = new float[16384], renderMs = new float[16384], stepMs = new float[16384], presentMs = new float[16384];
   private static long[] endAtNs = new long[16384];
   private static int lat;
   private static float[] latencyMs = new float[16384];

   // utilization, sampled at 2 Hz by pzopt-bench-sampler
   private static int utilN;
   private static double sumProcess, sumSystem, sumGame, sumRender, sumGpu, sumBackground;
   private static double maxGame, maxRender;
   private static int powerN;
   private static double sumTotalW, sumCpuW, sumGpuW;
   private static int clockN;
   private static double clockFirst, clockLast;
   private static final double[] clockRing = new double[6];
   private static long heapPeak, heapStart;
   private static long gcCount0, gcMs0;
   private static volatile long gcMaxPauseMs;
   private static Object gcListener;
   private static long allocStart;
   static int unfocusedFrames, pausedFrames, ticks;
   private static int chunks0;

   private BenchRecorder() {
   }

   /** Route start (game thread). */
   static void start() {
      n = 0;
      lat = 0;
      utilN = powerN = clockN = 0;
      sumProcess = sumSystem = sumGame = sumRender = sumGpu = sumBackground = 0;
      maxGame = maxRender = 0;
      sumTotalW = sumCpuW = sumGpuW = 0;
      unfocusedFrames = pausedFrames = ticks = 0;
      MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
      heapStart = heapPeak = mem.getHeapMemoryUsage().getUsed();
      long[] gc = gcTotals();
      gcCount0 = gc[0];
      gcMs0 = gc[1];
      gcMaxPauseMs = 0;
      allocStart = allocatedBytes();
      installGcListener();
      chunks0 = Stats.loadedChunks();
      startNs = System.nanoTime();
      startEpochMs = System.currentTimeMillis();
      endNs = 0;
      startSampler();
      on = true;
   }

   /** Route end (game thread): stop recording. Frames still in the overlay's lag land before this returns. */
   static synchronized void stop() {
      endNs = System.nanoTime();
      endEpochMs = System.currentTimeMillis();
      on = false;
   }

   private static long startEpochMs, endEpochMs;

   static synchronized void onFrame(float ms, float gpu, float render, float step, float present, long frameEndNs) {
      if (!on || frameEndNs < startNs) {
         return;
      }
      if (n == frameMs.length) {
         int len = n * 2;
         frameMs = Arrays.copyOf(frameMs, len);
         gpuMs = Arrays.copyOf(gpuMs, len);
         renderMs = Arrays.copyOf(renderMs, len);
         stepMs = Arrays.copyOf(stepMs, len);
         presentMs = Arrays.copyOf(presentMs, len);
         endAtNs = Arrays.copyOf(endAtNs, len);
      }
      frameMs[n] = ms;
      gpuMs[n] = gpu;
      renderMs[n] = render;
      stepMs[n] = step;
      presentMs[n] = present;
      endAtNs[n] = frameEndNs;
      n++;
   }

   /** One Pacing row: [1] = the game step's start, [6] = the GPU finished the frame (0 = unknown). */
   static synchronized void onPacing(long[] row) {
      if (!on || row[6] <= 0L || row[1] <= 0L || row[1] < startNs) {
         return;
      }
      if (lat == latencyMs.length) {
         latencyMs = Arrays.copyOf(latencyMs, lat * 2);
      }
      latencyMs[lat++] = (row[6] - row[1]) / 1e6f;
   }

   /** Game thread, once per frame while the route runs: two flag reads, nothing else (the observer effect). */
   static void onTick() {
      if (!on) {
         return;
      }
      ticks++;
      try {
         if (!org.lwjglx.opengl.Display.isActive()) {
            unfocusedFrames++;
         }
      } catch (Throwable ignored) {
      }
      try {
         if (zombie.GameTime.isGamePaused()) {
            pausedFrames++;
         }
      } catch (Throwable ignored) {
      }
   }

   private static Thread sampler;

   /** The 2 Hz samples (utilization, heap, power, clocks) on a daemon thread of their own, never on the game thread. */
   private static void startSampler() {
      if (sampler != null) {
         return;
      }
      sampler = new Thread(() -> {
         while (true) {
            try {
               Thread.sleep(500L);
            } catch (InterruptedException e) {
               return;
            }
            if (on) {
               float[] l = Overlay.loads();
               sample(l[0], l[1], l[2], l[3], l[4]);
            }
         }
      }, "pzopt-bench-sampler");
      sampler.setDaemon(true);
      sampler.start();
   }

   // not synchronized on the class: onFrame (render thread) takes that lock every frame and the /sys reads here are slow
   private static void sample(float processLoad, float systemLoad, float gameLoad, float renderLoad, float gpuLoad) {
      utilN++;
      sumProcess += processLoad;
      sumSystem += systemLoad;
      sumGame += gameLoad;
      sumRender += renderLoad;
      sumGpu += gpuLoad;
      sumBackground += Math.max(0, systemLoad - processLoad);
      maxGame = Math.max(maxGame, gameLoad);
      maxRender = Math.max(maxRender, renderLoad);
      long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
      heapPeak = Math.max(heapPeak, heap);
      if (!Float.isNaN(Power.totalW) || !Float.isNaN(Power.gpuW)) {
         powerN++;
         sumTotalW += Float.isNaN(Power.totalW) ? 0 : Power.totalW;
         sumCpuW += Float.isNaN(Power.cpuW) ? 0 : Power.cpuW;
         sumGpuW += Float.isNaN(Power.gpuW) ? 0 : Power.gpuW;
      }
      double mhz = cpuMhz();
      if (mhz > 0) {
         clockRing[clockN % clockRing.length] = mhz;
         clockN++;
         if (clockN == clockRing.length) {
            clockFirst = avg(clockRing, clockRing.length);
         }
         if (clockN >= clockRing.length) {
            clockLast = avg(clockRing, clockRing.length);
         }
      }
   }

   private static double avg(double[] v, int k) {
      double s = 0;
      for (int i = 0; i < k; i++) {
         s += v[i];
      }
      return s / k;
   }

   /**
    * The fastest core's current clock in MHz (Linux cpufreq), 0 elsewhere. The mean over all cores follows how the load
    * spreads (idle cores clock down), which read as a 15 % "drop" on a drive pass; the fastest core is the boost clock.
    */
   private static double cpuMhz() {
      try {
         java.io.File base = new java.io.File("/sys/devices/system/cpu");
         if (!base.isDirectory()) {
            return 0;
         }
         double mx = 0;
         for (int i = 0; i < 256; i++) {
            java.io.File f = new java.io.File(base, "cpu" + i + "/cpufreq/scaling_cur_freq");
            if (!f.isFile()) {
               if (i > 0) {
                  break;
               }
               continue;
            }
            mx = Math.max(mx, Long.parseLong(new String(java.nio.file.Files.readAllBytes(f.toPath())).trim()) / 1000.0);
         }
         return mx;
      } catch (Exception e) {
         return 0;
      }
   }

   private static long[] gcTotals() {
      long c = 0, t = 0;
      for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
         c += Math.max(0, b.getCollectionCount());
         t += Math.max(0, b.getCollectionTime());
      }
      return new long[] {c, t};
   }

   private static long allocatedBytes() {
      try {
         java.lang.management.ThreadMXBean t = ManagementFactory.getThreadMXBean();
         if (t instanceof com.sun.management.ThreadMXBean st) {
            return st.getTotalThreadAllocatedBytes();
         }
      } catch (Throwable ignored) {
      }
      return -1;
   }

   /** Longest collector pause of the pass, from the JDK's GC notifications (pauses only, not concurrent cycles). */
   private static void installGcListener() {
      if (gcListener != null) {
         return;
      }
      try {
         javax.management.NotificationListener l = (note, hb) -> {
            if (!on || !"com.sun.management.gc.notification".equals(note.getType())) {
               return;
            }
            javax.management.openmbean.CompositeData cd = (javax.management.openmbean.CompositeData)note.getUserData();
            com.sun.management.GarbageCollectionNotificationInfo info = com.sun.management.GarbageCollectionNotificationInfo.from(cd);
            String name = info.getGcName();
            if (name != null && (name.contains("Cycles") || name.contains("Concurrent"))) {
               return; // ZGC / Shenandoah cycles run beside the game; their pauses are reported separately
            }
            gcMaxPauseMs = Math.max(gcMaxPauseMs, info.getGcInfo().getDuration());
         };
         for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (b instanceof javax.management.NotificationEmitter e) {
               e.addNotificationListener(l, null, null);
            }
         }
         gcListener = l;
      } catch (Throwable t) {
         Log.warn("benchmark: no GC notifications: " + t);
      }
   }

   /** The pass's numbers as flat key=value pairs (BenchResults writes and reads them). */
   static synchronized java.util.LinkedHashMap<String, String> summary() {
      java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();
      BenchStats.Frames f = BenchStats.frames(frameMs, n);
      double wall = ((endNs > 0 ? endNs : System.nanoTime()) - startNs) / 1e9;
      put(m, "frames", f.frames);
      put(m, "seconds", f.seconds);
      put(m, "wall_seconds", wall);
      put(m, "fps", f.avgFps);
      put(m, "median_ms", f.medianMs);
      put(m, "mean_ms", f.meanMs);
      put(m, "min_ms", f.minMs);
      put(m, "max_ms", f.maxMs);
      put(m, "p90_ms", f.p90Ms);
      put(m, "p95_ms", f.p95Ms);
      put(m, "p99_ms", f.p99Ms);
      put(m, "p999_ms", f.p999Ms);
      put(m, "low1_avg", f.low1Avg);
      put(m, "low01_avg", f.low01Avg);
      put(m, "low5_avg", f.low5Avg);
      put(m, "low1_pct", f.low1Pct);
      put(m, "low01_pct", f.low01Pct);
      put(m, "stutters", f.stutters);
      put(m, "over50", f.over50);
      put(m, "over100", f.over100);
      put(m, "jitter_ms", f.jitterMs);
      put(m, "jitter_pct", f.jitterPct);
      put(m, "below60_pct", f.below60Pct);
      put(m, "below30_pct", f.below30Pct);
      put(m, "cv_pct", f.cvPct);
      m.put("hist", join(f.hist));
      m.put("curve", join(f.curve));
      put(m, "score", BenchStats.score(f.avgFps, f.low1Avg));
      bottlenecks(m);
      if (lat > 0) {
         float[] l = Arrays.copyOf(latencyMs, lat);
         Arrays.sort(l);
         put(m, "latency_median_ms", BenchStats.percentile(l, lat, 50));
         put(m, "latency_p99_ms", BenchStats.percentile(l, lat, 99));
      }
      if (utilN > 0) {
         put(m, "cpu_process_pct", sumProcess / utilN);
         put(m, "cpu_system_pct", sumSystem / utilN);
         put(m, "cpu_background_pct", sumBackground / utilN);
         put(m, "game_thread_pct", sumGame / utilN);
         put(m, "render_thread_pct", sumRender / utilN);
         put(m, "game_thread_max_pct", maxGame);
         put(m, "render_thread_max_pct", maxRender);
         put(m, "gpu_busy_pct", sumGpu / utilN);
      }
      if (powerN > 0) {
         double w = sumTotalW / powerN;
         put(m, "power_total_w", w);
         put(m, "power_cpu_w", sumCpuW / powerN);
         put(m, "power_gpu_w", sumGpuW / powerN);
         if (w > 0 && f.avgFps > 0) {
            put(m, "joules_per_frame", w / f.avgFps);
            put(m, "fps_per_watt", f.avgFps / w);
         }
      }
      if (clockN >= 2 * clockRing.length) {
         put(m, "cpu_mhz_first", clockFirst);
         put(m, "cpu_mhz_last", clockLast);
      }
      long[] gc = gcTotals();
      put(m, "gc_count", gc[0] - gcCount0);
      put(m, "gc_ms", gc[1] - gcMs0);
      put(m, "gc_max_pause_ms", gcMaxPauseMs);
      put(m, "heap_peak_mb", heapPeak / 1048576.0);
      put(m, "heap_start_mb", heapStart / 1048576.0);
      long alloc = allocatedBytes();
      if (alloc >= 0 && allocStart >= 0 && wall > 0) {
         put(m, "alloc_mb_per_s", (alloc - allocStart) / 1048576.0 / wall);
      }
      int chunks = Stats.loadedChunks() - chunks0;
      put(m, "chunks", chunks);
      put(m, "chunks_per_s", wall > 0 ? chunks / wall : 0);
      put(m, "unfocused_frames", unfocusedFrames);
      put(m, "paused_frames", pausedFrames);
      put(m, "ticks", ticks);
      m.put("per_second", perSecond());
      displayPacing(m, f);
      BenchSuite.Scenario sc = BenchPlan.scenario();
      if (sc != null && "sweep".equals(sc.id)) {
         double[] t = new double[n];
         for (int i = 0; i < n; i++) {
            t[i] = (endAtNs[i] - startNs) / 1e9;
         }
         m.put("sweep", BenchSweep.analyse(t, frameMs, gpuMs, n));
      }
      m.put("game_phases", gamePhases());
      return m;
   }

   /**
    * Technique 30: the frames against the display's refresh. On a fixed-refresh screen a frame that takes longer than
    * one refresh period is shown for two (a repeated image); when the frame rate is at or above the refresh rate, every
    * such frame is a visible hitch. refresh_miss_pct = frames over 1.5 periods; pacing_smooth_pct = frames within 25 %
    * of the median interval (an even cadence, whatever the rate).
    */
   private static void displayPacing(java.util.Map<String, String> m, BenchStats.Frames f) {
      int hz = BenchSystem.refreshHz();
      if (n == 0) {
         return;
      }
      int even = 0;
      double med = f.medianMs;
      for (int i = 0; i < n; i++) {
         if (Math.abs(frameMs[i] - med) <= 0.25 * med) {
            even++;
         }
      }
      put(m, "pacing_smooth_pct", 100.0 * even / n);
      if (hz > 0) {
         double period = 1000.0 / hz;
         int miss = 0;
         for (int i = 0; i < n; i++) {
            if (frameMs[i] > 1.5 * period) {
               miss++;
            }
         }
         put(m, "refresh_hz", hz);
         put(m, "refresh_miss_pct", 100.0 * miss / n);
      }
   }

   /**
    * The game thread's time by sub-phase over the timed window, from the overlay's stack sampler log
    * (pzopt.GameThreadProfile, Zomboid/pzopt-gamethread.out, one row a second): "phase/sub=percent;..." top 8.
    */
   private static String gamePhases() {
      try {
         GameThreadProfile.flushLog();
         java.io.File f = new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt-gamethread.out");
         if (!f.isFile()) {
            return "";
         }
         java.util.HashMap<String, Long> sub = new java.util.HashMap<>();
         long total = 0;
         for (String line : java.nio.file.Files.readAllLines(f.toPath())) {
            if (line.startsWith("#")) {
               continue;
            }
            String[] c = line.split("\t");
            if (c.length < 2) {
               continue;
            }
            long t = Long.parseLong(c[0]);
            if (t < startEpochMs || t > endEpochMs + 1000) {
               continue;
            }
            total += Long.parseLong(c[1]);
            for (int i = 2; i < c.length; i++) {
               int eq = c[i].lastIndexOf('=');
               if (eq > 2 && c[i].startsWith("s:")) {
                  sub.merge(c[i].substring(2, eq), Long.parseLong(c[i].substring(eq + 1)), Long::sum);
               }
            }
         }
         if (total == 0) {
            return "";
         }
         java.util.List<java.util.Map.Entry<String, Long>> l = new java.util.ArrayList<>(sub.entrySet());
         l.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
         StringBuilder b = new StringBuilder();
         for (int i = 0; i < Math.min(8, l.size()); i++) {
            b.append(i > 0 ? ";" : "").append(l.get(i).getKey().replace(';', ',').replace('=', ':')).append('=')
                  .append(String.format(java.util.Locale.ROOT, "%.1f", 100.0 * l.get(i).getValue() / total));
         }
         return b.toString();
      } catch (Exception e) {
         return "";
      }
   }

   /**
    * Technique 6: each frame's limiting stage, PresentMon "busy" style. GPU bound when the GPU was busy for most of the
    * frame's interval, game-thread bound when the game thread's own work (its step minus the wait at the frame hand-off)
    * filled it, render-thread bound when the render thread's work (acquire to the swap call) filled it, display bound
    * when the frame waited at the present (v-sync, the swap's hold) or at the limiter's cap, else "mixed" (no stage
    * filled it alone: the stages wait on each other).
    */
   private static void bottlenecks(java.util.Map<String, String> m) {
      if (n == 0) {
         return;
      }
      int cap = FrameCap.uncappedNow() ? 0 : FrameCap.lockNow();
      double capMs = cap > 0 ? 1000.0 / cap : 0;
      int gpu = 0, game = 0, render = 0, display = 0, mixed = 0;
      double gpuSum = 0, stepSum = 0, renderSum = 0, presentSum = 0;
      for (int i = 0; i < n; i++) {
         float f = frameMs[i];
         gpuSum += gpuMs[i];
         stepSum += stepMs[i];
         renderSum += renderMs[i];
         presentSum += presentMs[i];
         if (gpuMs[i] >= 0.85f * f) {
            gpu++;
         } else if (stepMs[i] >= 0.85f * f) {
            game++;
         } else if (renderMs[i] >= 0.85f * f) {
            render++;
         } else if (presentMs[i] >= 0.4f * f || capMs > 0 && f >= 0.9 * capMs && f <= 1.15 * capMs) {
            display++;
         } else {
            mixed++;
         }
      }
      put(m, "bound_gpu_pct", 100.0 * gpu / n);
      put(m, "bound_game_pct", 100.0 * game / n);
      put(m, "bound_render_pct", 100.0 * render / n);
      put(m, "bound_cap_pct", 100.0 * display / n);
      put(m, "bound_mixed_pct", 100.0 * mixed / n);
      put(m, "gpu_ms_mean", gpuSum / n);
      put(m, "step_ms_mean", stepSum / n);
      put(m, "render_ms_mean", renderSum / n);
      put(m, "present_ms_mean", presentSum / n);
      put(m, "cap_fps", cap);
   }

   /** fps per second of route time, comma-separated (technique 29's rows). */
   private static String perSecond() {
      StringBuilder b = new StringBuilder();
      if (n == 0) {
         return "";
      }
      long t0 = startNs;
      int sec = 0, count = 0;
      for (int i = 0; i < n; i++) {
         int s = (int)((endAtNs[i] - t0) / 1_000_000_000L);
         while (s > sec) {
            b.append(b.length() > 0 ? "," : "").append(count);
            count = 0;
            sec++;
         }
         count++;
      }
      b.append(b.length() > 0 ? "," : "").append(count);
      return b.toString();
   }

   /** The pass's frames as CSV in PresentMon's column names (technique 21), for CapFrameX and other analysers. */
   static synchronized void writeFramesCsv(java.io.File f, String app) {
      try (java.io.BufferedWriter w = java.nio.file.Files.newBufferedWriter(f.toPath())) {
         w.write("Application,ProcessID,TimeInSeconds,MsBetweenPresents,MsCPUBusy,MsGPUBusy,MsRenderThread,MsInPresentAPI\n");
         long pid = ProcessHandle.current().pid();
         StringBuilder b = new StringBuilder(96);
         for (int i = 0; i < n; i++) {
            b.setLength(0);
            b.append(app).append(',').append(pid).append(',');
            b.append(String.format(java.util.Locale.ROOT, "%.6f,%.4f,%.4f,%.4f,%.4f,%.4f\n", (endAtNs[i] - startNs) / 1e9, frameMs[i], stepMs[i], gpuMs[i], renderMs[i], presentMs[i]));
            w.write(b.toString());
         }
      } catch (java.io.IOException e) {
         Log.warn("benchmark: could not write " + f + ": " + e);
      }
   }

   static int frames() {
      return n;
   }

   private static void put(java.util.Map<String, String> m, String k, double v) {
      m.put(k, Double.isNaN(v) || Double.isInfinite(v) ? "nan" : String.format(java.util.Locale.ROOT, "%.4f", v));
   }

   private static void put(java.util.Map<String, String> m, String k, long v) {
      m.put(k, Long.toString(v));
   }

   private static String join(double[] v) {
      StringBuilder b = new StringBuilder();
      for (int i = 0; i < v.length; i++) {
         b.append(i > 0 ? "," : "").append(String.format(java.util.Locale.ROOT, "%.3f", v[i]));
      }
      return b.toString();
   }
}
