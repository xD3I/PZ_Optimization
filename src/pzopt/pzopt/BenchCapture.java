package pzopt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.LockSupport;

/**
 * What the harness's outside collectors record beside a run, recorded by the game for a built-in benchmark pass
 * (2026-10-21), so the report page (BenchDash, the Grafana run page) has the same panels: from the world being ready
 * (a pre-roll of up to {@link #PREROLL_S} s before the route, Grafana's window starts 30 s before the route) to the
 * route's end.
 * <ul>
 * <li>The sampler thread pzopt-bench-capture, twice a second and never on the game thread: harness/sysmon.sh's columns
 * (machine CPU and the busiest core from /proc/stat, the game process's CPU, GPU busy / clocks / temperature / VRAM via
 * {@link BenchGpu}), harness/schedmon.py's per-OS-thread CPU and run-queue wait (/proc/self/task/N/schedstat), the
 * machine's pressure stall and paging (/proc/pressure, /proc/vmstat), the process's resident memory by kind
 * (/proc/self/status), and every Java thread's CPU time (ThreadMXBean.getThreadCpuTime: the CPU-per-thread table on
 * every OS, the per-thread chart where /proc is missing). Linux reads are small procfs files; nothing calls the
 * PDH-backed OperatingSystemMXBean loads (the Windows stutter of 2026-09-21, and only off the game thread anyway).</li>
 * <li>Hooks that cost an array store: each game frame's start (Stats.frameTick, the harness's pzopt-frames.out
 * clock), each streamed chunk's stage times (Stats.done, the harness's pzopt-chunks.out), each presented frame's
 * pacing row (Pacing.write, the harness's pzopt-pacing.out, kept as a 0.1 s min / max summary).</li>
 * <li>At the pass's end the window of the game's own per-second logs (the game-thread stacks, the console lines of
 * pzopt-loadtrace.out, pzopt-input.out) and the path pilot's telemetry are copied next to the pass's frames.</li>
 * </ul>
 * Files, all {@code <base>-*} beside {@code <base>-frames.csv}, times in seconds from the route's start unless named:
 * sysmon.csv (sysmon.sh's columns with epoch_ms), sched.csv, series.csv (psi / mem), threads.out (pzopt-threads.out's
 * format), gameframes.csv, chunks.csv, pacing.csv, flips.csv (on-glass flips, where the platform reports them: the
 * Mac's Metal bridge), stacks.out, console.out, input.out, drive.out.
 */
final class BenchCapture {
   static final double PREROLL_S = 30;
   private static final long PERIOD_NS = 500_000_000L;
   private static final int MAX_ROWS = 1 << 21; // a hung pass stops growing the arrays at 2M entries
   static final String SYSMON_HEADER = "epoch_ms,cpu_pct,cpu_busiest_core_pct,gpu_pct,gpu_sm_mhz,gpu_mem_mhz,gpu_w,gpu_c,vram_mib,game_cpu_pct,bat_w,cpu_w,soc_w,total_w";

   /** On from the world being ready to the route's end (the hooks' gate: one volatile read). */
   static volatile boolean armed;
   private static volatile long routeStartNs, routeEndNs;
   private static long armNs, epochOffsetMs;
   private static Thread sampler;
   private static final Object lock = new Object();
   private static final boolean LINUX = new File("/proc/self/task").isDirectory();

   private BenchCapture() {
   }

   // ------------------------------------------------------------------ pass hooks (Benchmark, game thread)

   /** benchCapture=false (-D or pzopt.properties): a pass without these collectors (the A/B that shows they cost the frame times nothing). */
   static final boolean ENABLED = Config.BENCH_CAPTURE; // -D or pzopt.properties (benchmark-run.sh benchCapture=false)

   /** World ready: the pre-roll starts. */
   static void arm() {
      if (!ENABLED) {
         return;
      }
      synchronized (lock) {
         clear();
         armNs = System.nanoTime();
         epochOffsetMs = System.currentTimeMillis() - armNs / 1_000_000L;
         routeStartNs = 0;
         routeEndNs = 0;
      }
      armed = true;
      startSampler();
   }

   /** The route starts: the sampler takes a sample now, so the per-thread totals start at the route's start. */
   static void routeStart() {
      if (!ENABLED) {
         return;
      }
      if (!armed) {
         arm();
      }
      routeStartNs = System.nanoTime();
      LockSupport.unpark(sampler);
   }

   /** The route ended: one last sample at the end (waits up to 0.3 s for it, after the timed window), then off. */
   static void routeEnd() {
      if (!armed) {
         return;
      }
      long end = System.nanoTime();
      routeEndNs = end;
      LockSupport.unpark(sampler);
      long deadline = end + 300_000_000L;
      while (lastTickNs() < end && System.nanoTime() < deadline) {
         try {
            Thread.sleep(2L);
         } catch (InterruptedException e) {
            break;
         }
      }
      armed = false;
   }

   // ------------------------------------------------------------------ per-event hooks (an array store each)

   private static long[] gf = new long[1 << 10];
   private static int gfN;

   /** Stats.frameTick, game thread: a game frame starts. */
   static void gameFrame(long nowNs) {
      if (gfN == gf.length) {
         if (gfN >= MAX_ROWS) {
            return;
         }
         gf = Arrays.copyOf(gf, gfN * 2);
      }
      gf[gfN++] = nowNs;
   }

   private static final List<Object[]> chunks = new ArrayList<>();

   /** Stats.done, the streamer / recalc threads: a chunk was handed to the game thread. Times in System.nanoTime. */
   static void chunk(long enqueued, long loadStart, long loadEnd, long recalcStart, long recalcEnd, long publish, int wx, int wy, int minLevel,
         int maxLevel, String jobType, String thread) {
      synchronized (chunks) {
         if (chunks.size() < MAX_ROWS) {
            chunks.add(new Object[] {new long[] {enqueued, loadStart, loadEnd, recalcStart, recalcEnd, publish}, wx, wy, minLevel, maxLevel, jobType, thread});
         }
      }
   }

   private static final Object pacingLock = new Object();
   private static long[][] pr = new long[8][1 << 10];
   private static int prN;

   /** Pacing.write, render thread: one presented frame's row (System.nanoTime stamps: [1] step start ... [8] on glass). */
   static void pacing(long[] row) {
      synchronized (pacingLock) {
         if (prN == pr[0].length) {
            if (prN >= MAX_ROWS) {
               return;
            }
            for (int i = 0; i < pr.length; i++) {
               pr[i] = Arrays.copyOf(pr[i], prN * 2);
            }
         }
         for (int i = 1; i <= 8; i++) {
            pr[i - 1][prN] = row.length > i ? row[i] : 0L;
         }
         prN++;
      }
   }

   // ------------------------------------------------------------------ the sampler

   /** One sample: sysmon's row, the threads, the machine's pressure and the process's memory. */
   static final class Tick {
      long ns, procNs = -1;
      double cpu = Double.NaN, busiest = Double.NaN, gpu = Double.NaN, sm = Double.NaN, mem = Double.NaN, gpuW = Double.NaN, gpuC = Double.NaN,
            vram = Double.NaN, gameCpu = Double.NaN;
      long[] jIds, jCpu; // every Java thread's CPU time (cumulative ns)
      String[] tComm; // OS threads (Linux): CPU / run-queue ns since the last sample, per thread name
      long[] tRun, tWait;
      long[] psi; // cumulative: cpu some, mem some, mem full, io some (us), pswpin, pswpout, pgmajfault, ctxt
      Map<String, Double> memory;
   }

   private static final List<Tick> ticks = new ArrayList<>();
   private static long[][] prevCpu;
   private static long prevProcNs = -1, prevTickNs;
   private static final Map<String, long[]> prevTask = new HashMap<>(); // tid -> {run, wait}
   private static final Map<String, String> taskNames = new HashMap<>();
   private static final Map<Long, String> javaNames = new java.util.concurrent.ConcurrentHashMap<>(); // (the sampler adds, write reads)
   private static long tickCount, tickNsSum, tickNsMax, tickCpuNs;

   private static long lastTickNs() {
      synchronized (lock) {
         return ticks.isEmpty() ? 0 : ticks.get(ticks.size() - 1).ns;
      }
   }

   private static synchronized void startSampler() {
      if (sampler != null) {
         return;
      }
      Thread t = new Thread(() -> {
         while (true) {
            LockSupport.parkNanos(PERIOD_NS);
            if (Thread.interrupted()) {
               return;
            }
            if (!armed) {
               continue;
            }
            try {
               tick();
            } catch (Throwable e) {
               Log.warn("benchmark: capture sample failed: " + e);
            }
         }
      }, "pzopt-bench-capture");
      t.setDaemon(true);
      t.setPriority(Thread.MIN_PRIORITY);
      sampler = t;
      t.start();
   }

   private static ThreadMXBean mx; // (created by the sampler: the hooks' first read of `armed` initialises this class on the game thread)

   /** One sample (the sampler thread; the tests call it directly to measure its cost). */
   static void tick() {
      long t0 = System.nanoTime();
      if (mx == null) {
         mx = ManagementFactory.getThreadMXBean();
      }
      ThreadMXBean MX = mx;
      long c0 = cpuNow();
      Tick k = new Tick();
      k.ns = t0;
      double dtNs = prevTickNs == 0 ? 0 : t0 - prevTickNs;
      prevTickNs = t0;
      // machine CPU, busiest core (Linux /proc/stat); elsewhere the overlay sampler's machine load
      String stat = LINUX ? BenchProc.read("/proc/stat") : null;
      long[][] cpu = BenchProc.cpuTimes(stat);
      if (cpu != null) {
         double[] l = BenchProc.cpuLoad(prevCpu, cpu);
         k.cpu = l[0];
         k.busiest = l[1];
         prevCpu = cpu;
      } else {
         float[] l = Overlay.loads();
         k.cpu = l[1] > 0 ? l[1] : Double.NaN;
      }
      // the game process's CPU (100 = one core, sysmon's game_cpu_pct)
      try {
         if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            k.procNs = os.getProcessCpuTime(); // (times / GetProcessTimes: not the PDH-backed load)
            if (prevProcNs >= 0 && k.procNs >= 0 && dtNs > 0) {
               k.gameCpu = 100.0 * (k.procNs - prevProcNs) / dtNs;
            }
            prevProcNs = k.procNs;
         }
      } catch (Throwable ignored) {
      }
      // the GPU: sysmon's own sources, else the GL timer's busy share for gpu_pct
      double[] g = BenchGpu.read();
      k.gpu = !Double.isNaN(g[BenchGpu.BUSY]) ? g[BenchGpu.BUSY] : Overlay.loads()[4];
      k.sm = g[BenchGpu.SM];
      k.mem = g[BenchGpu.MEM];
      k.gpuC = g[BenchGpu.TEMP];
      k.vram = g[BenchGpu.VRAM];
      k.gpuW = Power.gpuW; // (pzopt.Power's NVML / hwmon reading, the overlay sampler's)
      // every Java thread's CPU time
      try {
         if (!MX.isThreadCpuTimeEnabled()) {
            MX.setThreadCpuTimeEnabled(true);
         }
         long[] ids = MX.getAllThreadIds();
         long[] ns = new long[ids.length];
         for (int i = 0; i < ids.length; i++) {
            ns[i] = MX.getThreadCpuTime(ids[i]);
            if (ns[i] >= 0 && !javaNames.containsKey(ids[i])) {
               ThreadInfo info = MX.getThreadInfo(ids[i], 0); // (no stack: no safepoint)
               javaNames.put(ids[i], info == null ? "thread-" + ids[i] : info.getThreadName());
            }
         }
         k.jIds = ids;
         k.jCpu = ns;
      } catch (Throwable ignored) {
      }
      if (LINUX) {
         linuxThreads(k);
         long[] c = BenchProc.psi(BenchProc.read("/proc/pressure/cpu"));
         long[] m = BenchProc.psi(BenchProc.read("/proc/pressure/memory"));
         long[] io = BenchProc.psi(BenchProc.read("/proc/pressure/io"));
         String vm = BenchProc.read("/proc/vmstat");
         if (c != null && m != null && io != null && vm != null) {
            k.psi = new long[] {c[0], m[0], m[1], io[0], BenchProc.field(vm, "pswpin"), BenchProc.field(vm, "pswpout"), BenchProc.field(vm, "pgmajfault"),
                  BenchProc.field(stat, "ctxt")};
         }
         k.memory = BenchProc.memory(BenchProc.read("/proc/self/status"));
      }
      synchronized (lock) {
         ticks.add(k);
         long dt = System.nanoTime() - t0;
         tickCount++;
         tickNsSum += dt;
         tickNsMax = Math.max(tickNsMax, dt);
         long c1 = cpuNow();
         if (c0 >= 0 && c1 >= 0) {
            tickCpuNs += c1 - c0;
         }
      }
   }

   private static long cpuNow() {
      try {
         return mx == null ? -1 : mx.getCurrentThreadCpuTime();
      } catch (Throwable t) {
         return -1;
      }
   }

   /** schedmon.py's per-thread counters, as deltas since the last sample summed per thread name. */
   private static void linuxThreads(Tick k) {
      String[] tids = new File("/proc/self/task").list();
      if (tids == null) {
         return;
      }
      Map<String, long[]> by = new HashMap<>();
      java.util.HashSet<String> alive = new java.util.HashSet<>();
      for (String tid : tids) {
         long[] s = BenchProc.schedstat(BenchProc.read("/proc/self/task/" + tid + "/schedstat"));
         if (s == null) {
            continue;
         }
         alive.add(tid);
         String name = taskNames.get(tid);
         if (name == null) {
            name = BenchProc.comm(BenchProc.read("/proc/self/task/" + tid + "/comm"));
            taskNames.put(tid, name);
         }
         long[] p = prevTask.put(tid, new long[] {s[0], s[1]});
         if (p == null) {
            continue;
         }
         long dr = s[0] - p[0], dw = s[1] - p[1];
         if (dr > 0 || dw > 0) {
            long[] a = by.computeIfAbsent(name, x -> new long[2]);
            a[0] += Math.max(0, dr);
            a[1] += Math.max(0, dw);
         }
      }
      prevTask.keySet().retainAll(alive);
      taskNames.keySet().retainAll(alive);
      k.tComm = by.keySet().toArray(new String[0]);
      k.tRun = new long[k.tComm.length];
      k.tWait = new long[k.tComm.length];
      for (int i = 0; i < k.tComm.length; i++) {
         long[] a = by.get(k.tComm[i]);
         k.tRun[i] = a[0];
         k.tWait[i] = a[1];
      }
   }

   private static void clear() {
      ticks.clear();
      prevCpu = null;
      prevProcNs = -1;
      prevTickNs = 0;
      prevTask.clear();
      tickCount = tickNsSum = tickNsMax = tickCpuNs = 0;
      gfN = 0;
      synchronized (chunks) {
         chunks.clear();
      }
      synchronized (pacingLock) {
         prN = 0;
      }
   }

   // ------------------------------------------------------------------ the pass's numbers and files

   private static double rel(long ns) {
      return (ns - routeStartNs) / 1e9;
   }

   /** The pass's summary keys (BenchResults' step file): the run page's stat tiles and the sampler's own cost. */
   static LinkedHashMap<String, String> summary() {
      LinkedHashMap<String, String> m = new LinkedHashMap<>();
      synchronized (lock) {
         if (tickCount == 0 || routeStartNs == 0) {
            return m;
         }
         long end = routeEndNs > 0 ? routeEndNs : System.nanoTime();
         double busy = 0, temp = 0, vram = Double.NaN;
         int nb = 0, nt = 0;
         for (Tick k : ticks) {
            if (k.ns < routeStartNs || k.ns > end + 50_000_000L) {
               continue;
            }
            if (!Double.isNaN(k.busiest)) {
               busy += k.busiest;
               nb++;
            }
            if (!Double.isNaN(k.gpuC)) {
               temp += k.gpuC;
               nt++;
            }
            if (!Double.isNaN(k.vram)) {
               vram = Double.isNaN(vram) ? k.vram : Math.max(vram, k.vram);
            }
         }
         put(m, "cpu_busiest_core_pct", nb > 0 ? busy / nb : Double.NaN);
         put(m, "gpu_c", nt > 0 ? temp / nt : Double.NaN);
         put(m, "vram_mib", vram);
         m.put("gpu_sensors", BenchGpu.source());
         put(m, "capture_samples", tickCount);
         put(m, "capture_sample_mean_us", tickNsSum / 1e3 / tickCount);
         put(m, "capture_sample_max_us", tickNsMax / 1e3);
         double wall = (end - armNs) / 1e9;
         put(m, "capture_cpu_pct_of_core", wall > 0 ? 100.0 * tickCpuNs / 1e9 / wall : Double.NaN);
      }
      double[] lat = chunkTotalsMs(routeStartNs, routeEndNs > 0 ? routeEndNs : System.nanoTime());
      if (lat.length > 0) {
         Arrays.sort(lat);
         float[] f = new float[lat.length];
         for (int i = 0; i < f.length; i++) {
            f[i] = (float)lat[i];
         }
         put(m, "chunk_p50_ms", BenchStats.percentile(f, f.length, 50));
         put(m, "chunk_p99_ms", BenchStats.percentile(f, f.length, 99));
         put(m, "chunk_max_ms", lat[lat.length - 1]);
         put(m, "chunks_traced", (long)lat.length);
      }
      return m;
   }

   /** Each chunk's enqueue-to-publish time in ms, for the chunks enqueued in [fromNs, toNs]. */
   private static double[] chunkTotalsMs(long fromNs, long toNs) {
      synchronized (chunks) {
         double[] out = new double[chunks.size()];
         int n = 0;
         for (Object[] c : chunks) {
            long[] t = (long[])c[0];
            if (t[0] >= fromNs && t[0] <= toNs) {
               double tot = 0;
               for (double v : stages(t)) {
                  if (!Double.isNaN(v)) {
                     tot += v;
                  }
               }
               out[n++] = tot;
            }
         }
         return Arrays.copyOf(out, n);
      }
   }

   /** A chunk's stages in ms (queue wait, load, recalc wait, recalc, publish wait); NaN = a stage it skipped. */
   static double[] stages(long[] t) {
      long[][] pairs = {{t[0], t[1]}, {t[1], t[2]}, {t[2], t[3]}, {t[3], t[4]}, {t[4], t[5]}};
      double[] v = new double[5];
      for (int i = 0; i < 5; i++) {
         long a = pairs[i][0], b = pairs[i][1];
         v[i] = a == 0 || b == 0 || b < a ? Double.NaN : (b - a) / 1e6; // (ingest: a negative offset is NULL)
      }
      return v;
   }

   /** Writes the pass's files into its run folder (game thread, after the timed window). Never throws. */
   static void write(File dir, String base) {
      try {
         synchronized (lock) {
            if (routeStartNs == 0) {
               return;
            }
            long from = routeStartNs - (long)(PREROLL_S * 1e9), to = routeEndNs > 0 ? routeEndNs : System.nanoTime();
            writeSysmon(new File(dir, base + "-sysmon.csv"), from, to);
            writeSched(new File(dir, base + "-sched.csv"), from, to);
            writeSeries(new File(dir, base + "-series.csv"), from, to);
            writeThreads(new File(dir, base + "-threads.out"));
            writeGameFrames(new File(dir, base + "-gameframes.csv"), from, to);
            writeChunks(new File(dir, base + "-chunks.csv"), from, to);
            writePacing(new File(dir, base + "-pacing.csv"), new File(dir, base + "-flips.csv"), from, to);
            long startMs = routeStartNs / 1_000_000L + epochOffsetMs, fromMs = startMs - (long)(PREROLL_S * 1000), toMs = to / 1_000_000L + epochOffsetMs + 1000;
            String cache = cacheDir();
            if (cache != null) {
               GameThreadProfile.flushLog();
               copyStacks(new File(cache, "pzopt-stacks.out"), new File(dir, base + "-stacks.out"), fromMs, toMs);
               LoadTrace.flush(); // the route's lines were still in its 16 KB buffer (the window ended at its last flush)
               copyWindow(new File(cache, "pzopt-loadtrace.out"), new File(dir, base + "-console.out"), fromMs, toMs, 1);
               InputRecorder.flush();
               copyWindow(new File(cache, "pzopt-input.out"), new File(dir, base + "-input.out"), fromMs, toMs, 1000);
            }
            String drive = BenchPlan.active() ? Harness.driveRowsText() : null;
            if (drive != null && drive.indexOf('\n') < drive.length() - 1) {
               Files.writeString(new File(dir, base + "-drive.out").toPath(), drive, StandardCharsets.UTF_8);
            }
         }
      } catch (Throwable t) {
         Log.warn("benchmark: could not write the pass's captures: " + t);
      }
   }

   private static String cacheDir() {
      try {
         return zombie.ZomboidFileSystem.instance == null ? null : zombie.ZomboidFileSystem.instance.getCacheDir();
      } catch (Throwable t) {
         return null;
      }
   }

   private static void writeSysmon(File f, long from, long to) throws IOException {
      StringBuilder b = new StringBuilder(SYSMON_HEADER).append('\n');
      int n = 0;
      for (Tick k : ticks) {
         if (k.ns < from || k.ns > to + 50_000_000L) {
            continue;
         }
         b.append(k.ns / 1_000_000L + epochOffsetMs);
         for (double v : new double[] {k.cpu, k.busiest, k.gpu, k.sm, k.mem, k.gpuW, k.gpuC, k.vram, k.gameCpu}) {
            b.append(',').append(Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%.1f", v));
         }
         b.append(",,,,\n"); // bat_w, cpu_w, soc_w, total_w: pzopt.Power's readings are the samples file's
         n++;
      }
      if (n > 0) {
         Files.writeString(f.toPath(), b.toString(), StandardCharsets.UTF_8);
      }
   }

   /** Per second and thread: CPU % and run-queue % (Grafana's sched table); /proc's OS threads, else Java's. */
   private static void writeSched(File f, long from, long to) throws IOException {
      TreeMap<Integer, Map<String, double[]>> secs = new TreeMap<>();
      boolean proc = false;
      Tick prev = null;
      for (Tick k : ticks) {
         boolean in = k.ns >= from && k.ns <= to + 50_000_000L;
         int sec = (int)Math.floor(rel(k.ns));
         if (k.tComm != null) {
            proc = true;
            if (in && prev != null) {
               Map<String, double[]> m = secs.computeIfAbsent(sec, x -> new HashMap<>());
               for (int i = 0; i < k.tComm.length; i++) {
                  double[] a = m.computeIfAbsent(k.tComm[i], x -> new double[2]);
                  a[0] += k.tRun[i];
                  a[1] += k.tWait[i];
               }
            }
         } else if (in && prev != null && k.jIds != null && prev.jIds != null) {
            Map<Long, Long> before = new HashMap<>();
            for (int i = 0; i < prev.jIds.length; i++) {
               before.put(prev.jIds[i], prev.jCpu[i]);
            }
            Map<String, double[]> m = secs.computeIfAbsent(sec, x -> new HashMap<>());
            for (int i = 0; i < k.jIds.length; i++) {
               Long b0 = before.get(k.jIds[i]);
               long d = b0 == null || b0 < 0 || k.jCpu[i] < 0 ? 0 : k.jCpu[i] - b0;
               if (d > 0) {
                  m.computeIfAbsent(javaNames.getOrDefault(k.jIds[i], "?"), x -> new double[] {0, Double.NaN})[0] += d;
               }
            }
         }
         prev = k;
      }
      if (secs.isEmpty()) {
         return;
      }
      StringBuilder b = new StringBuilder("# per second and thread: CPU % (100 = one core) and run-queue wait % (runnable, no CPU); source=" + (proc ? "proc" : "jvm")
            + "\nt_s,thread,cpu_pct,runq_pct\n");
      for (Map.Entry<Integer, Map<String, double[]>> e : secs.entrySet()) {
         for (Map.Entry<String, double[]> t : new TreeMap<>(e.getValue()).entrySet()) {
            double[] a = t.getValue();
            b.append(e.getKey() + 0.5).append(',').append(t.getKey().replace(',', '_')).append(',').append(String.format(Locale.ROOT, "%.2f", a[0] / 1e7)).append(',')
                  .append(Double.isNaN(a[1]) ? "" : String.format(Locale.ROOT, "%.2f", a[1] / 1e7)).append('\n');
         }
      }
      Files.writeString(f.toPath(), b.toString(), StandardCharsets.UTF_8);
   }

   /** Grafana's series rows: psi (pressure stall %, paging and context switches per s) and mem (MiB by kind). */
   private static void writeSeries(File f, long from, long to) throws IOException {
      StringBuilder b = new StringBuilder("t_s,source,key,value\n");
      String[] psiKeys = {"cpu_some_pct", "mem_some_pct", "mem_full_pct", "io_some_pct", "pswpin_s", "pswpout_s", "pgmajfault_s", "ctxt_s"};
      Tick prev = null;
      int n = 0;
      for (Tick k : ticks) {
         boolean in = k.ns >= from && k.ns <= to + 50_000_000L;
         String t = String.format(Locale.ROOT, "%.3f", rel(k.ns));
         if (in && k.psi != null && prev != null && prev.psi != null && k.ns > prev.ns) {
            double dt = (k.ns - prev.ns) / 1e9;
            for (int i = 0; i < psiKeys.length; i++) {
               if (k.psi[i] < 0 || prev.psi[i] < 0) {
                  continue;
               }
               double v = i < 4 ? (k.psi[i] - prev.psi[i]) / 1e4 / dt : (k.psi[i] - prev.psi[i]) / dt;
               b.append(t).append(",psi,").append(psiKeys[i]).append(',').append(String.format(Locale.ROOT, "%.3f", v)).append('\n');
               n++;
            }
         }
         if (in && k.memory != null) {
            for (Map.Entry<String, Double> e : k.memory.entrySet()) {
               b.append(t).append(",mem,").append(e.getKey()).append(',').append(String.format(Locale.ROOT, "%.1f", e.getValue())).append('\n');
               n++;
            }
         }
         prev = k;
      }
      if (n > 0) {
         Files.writeString(f.toPath(), b.toString(), StandardCharsets.UTF_8);
      }
   }

   /** pzopt-threads.out's format (Harness.writeThreadCpu): every Java thread's CPU over the route. */
   private static void writeThreads(File f) throws IOException {
      Tick a = null, z = null;
      for (Tick k : ticks) {
         if (a == null && k.ns >= routeStartNs - 5_000_000L && k.jIds != null) {
            a = k;
         }
         if (k.jIds != null && (routeEndNs == 0 || k.ns <= routeEndNs + 50_000_000L)) {
            z = k;
         }
      }
      if (a == null || z == null || z == a) {
         return;
      }
      double secs = (z.ns - a.ns) / 1e9;
      Map<Long, Long> start = new HashMap<>();
      for (int i = 0; i < a.jIds.length; i++) {
         start.put(a.jIds[i], a.jCpu[i]);
      }
      List<Object[]> rows = new ArrayList<>();
      long sum = 0;
      for (int i = 0; i < z.jIds.length; i++) {
         if (z.jCpu[i] < 0) {
            continue;
         }
         long d = z.jCpu[i] - Math.max(0, start.getOrDefault(z.jIds[i], 0L));
         if (d > 0) {
            rows.add(new Object[] {javaNames.getOrDefault(z.jIds[i], "thread-" + z.jIds[i]), d});
            sum += d;
         }
      }
      rows.sort((x, y) -> Long.compare((long)y[1], (long)x[1]));
      StringBuilder b = new StringBuilder();
      b.append("# route_seconds=").append(String.format(Locale.ROOT, "%.3f", secs)).append(" cores=").append(Runtime.getRuntime().availableProcessors()).append('\n');
      if (a.procNs >= 0 && z.procNs >= 0) {
         b.append("# process_cpu_ms=").append((z.procNs - a.procNs) / 1_000_000L).append(" process_share=")
               .append(String.format(Locale.ROOT, "%.4f", (z.procNs - a.procNs) / 1e9 / secs)).append('\n');
      }
      b.append("# live_threads_cpu_ms=").append(sum / 1_000_000L).append('\n');
      b.append("thread\tcpu_ms\tshare_of_wall\n");
      for (Object[] r : rows) {
         long ns = (long)r[1];
         b.append(r[0]).append('\t').append(ns / 1_000_000L).append('\t').append(String.format(Locale.ROOT, "%.3f", ns / 1e9 / secs)).append('\n');
      }
      Files.writeString(f.toPath(), b.toString(), StandardCharsets.UTF_8);
   }

   /** Every game frame (Stats.frameTick's clock, the harness's pzopt-frames.out): its start and its duration. */
   private static void writeGameFrames(File f, long from, long to) throws IOException {
      if (gfN < 2) {
         return;
      }
      StringBuilder b = new StringBuilder(gfN * 16).append("t_s,ms\n");
      int n = 0;
      for (int i = 1; i < gfN; i++) {
         if (gf[i - 1] < from || gf[i - 1] > to) {
            continue;
         }
         b.append(String.format(Locale.ROOT, "%.5f,%.3f\n", rel(gf[i - 1]), (gf[i] - gf[i - 1]) / 1e6));
         n++;
      }
      if (n > 0) {
         Files.writeString(f.toPath(), b.toString(), StandardCharsets.UTF_8);
      }
   }

   static final String CHUNKS_HEADER = "t_s,wx,wy,min_level,max_level,job,thread,queue_wait_ms,load_ms,recalc_wait_ms,recalc_ms,publish_wait_ms,total_ms";

   private static void writeChunks(File f, long from, long to) throws IOException {
      StringBuilder b = new StringBuilder(CHUNKS_HEADER).append('\n');
      int n = 0;
      synchronized (chunks) {
         for (Object[] c : chunks) {
            long[] t = (long[])c[0];
            if (t[0] < from || t[0] > to) {
               continue;
            }
            double[] st = stages(t);
            double tot = 0;
            b.append(String.format(Locale.ROOT, "%.4f", rel(t[0]))).append(',').append(c[1]).append(',').append(c[2]).append(',').append(c[3]).append(',').append(c[4])
                  .append(',').append(String.valueOf(c[5]).replace(',', '_')).append(',').append(String.valueOf(c[6]).replace(',', '_'));
            for (double v : st) {
               b.append(',').append(Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%.3f", v));
               if (!Double.isNaN(v)) {
                  tot += v;
               }
            }
            b.append(',').append(String.format(Locale.ROOT, "%.3f", tot)).append('\n');
            n++;
         }
      }
      if (n > 0) {
         Files.writeString(f.toPath(), b.toString(), StandardCharsets.UTF_8);
      }
   }

   static final String[] PACING = {"sim_step", "acquire_lag", "swap", "hold", "gpu_after_swap", "shown_after_swap"};
   static final double PACING_BIN_S = 0.1;

   /**
    * Pacing rows as harness/grafana/ingest.py's parse_pacing reads them (per frame: sim step, acquire lag, swap, hold,
    * GPU after swap, shown after swap, ms), kept as each 0.1 s bin's frame count, minimum and maximum: the run page draws
    * every frame as points, and at the report's width a 0.1 s column of points is its minimum to its maximum. The on-glass
    * flip intervals, where the platform reports them, frame by frame.
    */
   private static void writePacing(File f, File flips, long from, long to) throws IOException {
      int n;
      long[][] r;
      synchronized (pacingLock) {
         n = prN;
         r = new long[pr.length][];
         for (int i = 0; i < pr.length; i++) {
            r[i] = Arrays.copyOf(pr[i], n);
         }
      }
      if (n == 0) {
         return;
      }
      TreeMap<Integer, double[]> bins = new TreeMap<>(); // bin -> frames, then min / max per metric
      StringBuilder fl = new StringBuilder("t_s,interval_ms\n");
      int flipN = 0;
      long prevSim = 0, lastShown = 0;
      for (int i = 0; i < n; i++) {
         long sim = r[0][i], acq = r[1][i], sc = r[2][i], sr = r[3][i], wait = r[4][i], gpu = r[5][i], shown = r[7][i];
         double[] v = {prevSim != 0 && sim > prevSim ? (sim - prevSim) / 1e6 : Double.NaN, sim != 0 && acq > sim ? (acq - sim) / 1e6 : Double.NaN,
               sr > sc && sc > 0 ? (sr - sc) / 1e6 : Double.NaN, wait != 0 ? wait / 1e6 : Double.NaN, gpu > sr && sr > 0 ? (gpu - sr) / 1e6 : Double.NaN,
               shown > sr && sr > 0 ? (shown - sr) / 1e6 : Double.NaN};
         if (sim != 0) {
            prevSim = sim;
         }
         if (shown > 0) {
            if (lastShown > 0 && shown > lastShown && shown >= from && shown <= to) {
               fl.append(String.format(Locale.ROOT, "%.5f,%.3f\n", rel(shown), (shown - lastShown) / 1e6));
               flipN++;
            }
            if (shown > lastShown) {
               lastShown = shown;
            }
         }
         if (sr < from || sr > to) {
            continue;
         }
         int bin = (int)Math.floor(rel(sr) / PACING_BIN_S);
         double[] a = bins.computeIfAbsent(bin, x -> {
            double[] z = new double[1 + 2 * PACING.length];
            Arrays.fill(z, Double.NaN);
            z[0] = 0;
            return z;
         });
         a[0]++;
         for (int m = 0; m < PACING.length; m++) {
            if (Double.isNaN(v[m])) {
               continue;
            }
            if (Double.isNaN(a[1 + 2 * m]) || v[m] < a[1 + 2 * m]) {
               a[1 + 2 * m] = v[m];
            }
            if (Double.isNaN(a[2 + 2 * m]) || v[m] > a[2 + 2 * m]) {
               a[2 + 2 * m] = v[m];
            }
         }
      }
      StringBuilder b = new StringBuilder("# pacing per " + PACING_BIN_S + " s bin of swap returns: frames, then each metric's min and max in ms\nt_s,frames");
      for (String m : PACING) {
         b.append(',').append(m).append("_min,").append(m).append("_max");
      }
      b.append('\n');
      for (Map.Entry<Integer, double[]> e : bins.entrySet()) {
         double[] a = e.getValue();
         b.append(String.format(Locale.ROOT, "%.2f", (e.getKey() + 0.5) * PACING_BIN_S)).append(',').append((int)a[0]);
         for (int k = 1; k < a.length; k++) {
            b.append(',').append(Double.isNaN(a[k]) ? "" : String.format(Locale.ROOT, "%.3f", a[k]));
         }
         b.append('\n');
      }
      if (!bins.isEmpty()) {
         Files.writeString(f.toPath(), b.toString(), StandardCharsets.UTF_8);
      }
      if (flipN > 0) {
         Files.writeString(flips.toPath(), fl.toString(), StandardCharsets.UTF_8);
      }
   }

   /** The lines of a log whose first field (epoch ms, or epoch us with div 1000) lies in [fromMs, toMs], and its comments. */
   /** The most a copied log window keeps (its newest lines): a 500-mod boot logs several MB in the pre-roll. */
   static final long WINDOW_MAX_BYTES = 768L * 1024;

   static void copyWindow(File src, File dst, long fromMs, long toMs, long div) throws IOException {
      if (!src.isFile()) {
         return;
      }
      StringBuilder b = new StringBuilder();
      java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();
      long bytes = 0;
      int kept = 0, dropped = 0;
      try (java.io.BufferedReader in = Files.newBufferedReader(src.toPath(), StandardCharsets.UTF_8)) {
         String line;
         while ((line = in.readLine()) != null) {
            if (line.startsWith("#")) {
               b.append(line).append('\n');
               continue;
            }
            int end = 0;
            while (end < line.length() && Character.isDigit(line.charAt(end))) {
               end++;
            }
            if (end == 0 || end > 18) {
               continue;
            }
            long t = Long.parseLong(line.substring(0, end)) / div;
            if (t >= fromMs && t <= toMs) {
               lines.addLast(line);
               bytes += line.length() + 1;
               kept++;
               while (bytes > WINDOW_MAX_BYTES && lines.size() > 1) { // a boot that logs MBs (hundreds of mods): the newest lines stay
                  bytes -= lines.removeFirst().length() + 1;
                  dropped++;
               }
            }
         }
      } catch (java.nio.charset.MalformedInputException e) {
         return;
      }
      if (kept > 0) {
         if (dropped > 0) {
            b.append("# ").append(dropped).append(" earlier lines of the window left out (over ").append(WINDOW_MAX_BYTES / 1024).append(" KB)\n");
         }
         for (String l : lines) {
            b.append(l).append('\n');
         }
         Files.writeString(dst.toPath(), b.toString(), StandardCharsets.UTF_8);
      }
   }

   /**
    * pzopt-stacks.out's seconds in [fromMs, toMs] with the frame ids they use ('f id name' lines are written once, the
    * first time a frame appears, so a window keeps the definitions from before it).
    */
   static void copyStacks(File src, File dst, long fromMs, long toMs) throws IOException {
      if (!src.isFile()) {
         return;
      }
      Map<String, String> defs = new HashMap<>();
      java.util.LinkedHashSet<String> used = new java.util.LinkedHashSet<>();
      StringBuilder body = new StringBuilder();
      String header = null;
      boolean in = false;
      int seconds = 0;
      try (java.io.BufferedReader r = Files.newBufferedReader(src.toPath(), StandardCharsets.UTF_8)) {
         String line;
         while ((line = r.readLine()) != null) {
            if (line.startsWith("#")) {
               if (header == null) {
                  header = line;
               }
            } else if (line.startsWith("f ")) {
               int sp = line.indexOf(' ', 2);
               if (sp > 0) {
                  defs.put(line.substring(2, sp), line);
               }
            } else if (line.startsWith("t ")) {
               String[] f = line.split(" ");
               long t = f.length > 1 ? BenchProc.parseLong(f[1]) : 0;
               in = t >= fromMs && t <= toMs;
               if (in) {
                  body.append(line).append('\n');
                  seconds++;
               }
            } else if (in && !line.isEmpty()) {
               body.append(line).append('\n');
               int sp = line.lastIndexOf(' ');
               if (sp > 0) {
                  for (String id : line.substring(0, sp).split(";")) {
                     used.add(id);
                  }
               }
            }
         }
      }
      if (seconds == 0) {
         return;
      }
      StringBuilder b = new StringBuilder();
      if (header != null) {
         b.append(header).append('\n');
      }
      for (String id : used) {
         String d = defs.get(id);
         if (d != null) {
            b.append(d).append('\n');
         }
      }
      b.append(body);
      Files.writeString(dst.toPath(), b.toString(), StandardCharsets.UTF_8);
   }

   private static void put(Map<String, String> m, String k, long v) {
      m.put(k, Long.toString(v));
   }

   private static void put(Map<String, String> m, String k, double v) {
      m.put(k, Double.isNaN(v) || Double.isInfinite(v) ? "nan" : String.format(Locale.ROOT, "%.4f", v));
   }

   // ------------------------------------------------------------------ tests

   /** Tests: the route window in nanoTime (start, end), as the hooks would set it. */
   static void testWindow(long startNs, long endNs) {
      synchronized (lock) {
         if (armNs == 0) {
            armNs = startNs - 1;
            epochOffsetMs = System.currentTimeMillis() - System.nanoTime() / 1_000_000L;
         }
         routeStartNs = startNs;
         routeEndNs = endNs;
      }
   }

   static void testReset() {
      synchronized (lock) {
         clear();
         armNs = 0;
         routeStartNs = routeEndNs = 0;
      }
   }
}
