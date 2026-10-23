package pzopt;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The built-in benchmark's in-game collectors (BenchCapture, BenchProc, BenchGpu) and the report panels that read their
 * files (BenchDashMore through BenchDash.text): the /proc parsers on fixtures, a pass written from synthetic hooks and
 * real samples of this JVM, the panels' answers on that folder and on a Windows pass, and what the collectors cost.
 */
public class BenchCaptureTest {
   static List<String[]> panel(String text, int id) {
      return BenchDashTest.panel(text, id);
   }

   static String value(List<String[]> rows, String kind, String name) {
      return BenchDashTest.value(rows, kind, name);
   }

   static String why(List<String[]> rows) {
      return rows.stream().filter(r -> r[0].equals("WHY")).map(r -> r[1]).findFirst().orElse("?");
   }

   public static void main(String[] args) throws Exception {
      parsers();
      java.nio.file.Path home = Files.createTempDirectory("pzopt-capture-test");
      System.setProperty("deployment.user.cachedir", home.toString()); // UserOptions.zomboidDir -> <home>/Zomboid
      passFolder(home.toFile());
      cost();
      System.out.println("BenchCaptureTest: ok");
   }

   // ------------------------------------------------------------------ /proc, sysfs and console parsers

   static void parsers() {
      String stat = "cpu  100 0 100 700 100 0 0 0 0 0\ncpu0 50 0 50 300 100 0 0 0 0 0\ncpu1 50 0 50 400 0 0 0 0 0 0\nintr 5\nctxt 12345\nbtime 1\n";
      String stat2 = "cpu  200 0 200 800 100 0 0 0 0 0\ncpu0 150 0 50 300 100 0 0 0 0 0\ncpu1 50 0 150 500 0 0 0 0 0 0\nintr 5\nctxt 12999\n";
      long[][] a = BenchProc.cpuTimes(stat), b = BenchProc.cpuTimes(stat2);
      Check.check(a.length == 3 && a[0][0] == 200 && a[0][1] == 1000, "aggregate busy 200 of 1000 (idle + iowait idle): " + a[0][0] + "/" + a[0][1]);
      double[] l = BenchProc.cpuLoad(a, b);
      Check.check(Math.abs(l[0] - 66.67) < 0.01 && Math.abs(l[1] - 100) < 1e-9, "machine 200/300 = 66.7 %, busiest cpu0 100/100: " + l[0] + " " + l[1]);
      Check.check(BenchProc.field(stat2, "ctxt") == 12999 && BenchProc.field(stat2, "intr") == 5 && BenchProc.field(stat2, "ctx") == -1, "fields by whole key");
      long[] psi = BenchProc.psi("some avg10=0.00 avg60=0.00 avg300=0.00 total=1234\nfull avg10=0.00 avg60=0.00 avg300=0.00 total=56\n");
      Check.check(psi[0] == 1234 && psi[1] == 56, "PSI totals: " + psi[0] + " " + psi[1]);
      long[] ss = BenchProc.schedstat("123456789 2345678 910\n");
      Check.check(ss[0] == 123456789L && ss[1] == 2345678L && ss[2] == 910, "schedstat run / wait / runs");
      Check.check("pzopt-bench-cap".equals(BenchProc.comm("pzopt-bench-cap\n")) && "Java2D_Disposer".equals(BenchProc.comm("Java2D Disposer")), "comm as schedmon writes it");
      Map<String, Double> mem = BenchProc.memory("Name:\tjava\nVmRSS:\t 2097152 kB\nRssAnon:\t 1048576 kB\nRssFile:\t  524288 kB\nRssShmem:\t 10240 kB\nVmSwap:\t       0 kB\n");
      Check.check(mem.get("anon_mib") == 1024 && mem.get("file_mib") == 512 && mem.get("shmem_mib") == 10 && mem.get("total_mib") == 2048 && mem.get("swap_mib") == 0,
            "status memory in MiB: " + mem);
      Check.check(BenchProc.dpmMhz("0: 96Mhz\n1: 456Mhz *\n2: 1000Mhz\n") == 456 && Double.isNaN(BenchProc.dpmMhz("0: 96Mhz\n")), "the current pp_dpm level");
      String[] c = BenchProc.consoleLine("1792536706484\t0,General,General,\"LOG  : General     > [pzopt] file system: 10 worker threads\"");
      Check.check(c != null && c[0].equals("1792536706484") && c[1].equals("LOG") && c[2].equals("General") && c[3].equals("[pzopt] file system: 10 worker threads"),
            "a console line: " + String.join("|", c));
      c = BenchProc.consoleLine("1792536706500\t3,General,General,\"WARN : General      f:0 at AngelCodeFont.parseFnt   > a \"\"quoted\"\" page\"");
      Check.check(c[1].equals("WARN") && c[3].equals("a \"quoted\" page") && "warning".equals(BenchProc.logLevel(c[1])), "a warning, quotes unescaped: " + c[3]);
      c = BenchProc.consoleLine("1792548839750\t0,Mod,General,\"LOG  : Mod         > mod %22ETO_B%22 overrides a.png%0Anext\"");
      Check.check(c[3].equals("mod \"ETO_B\" overrides a.png\nnext"), "DebugLog's %22 / %0A escapes decoded: " + c[3]);
      Check.check(BenchProc.consoleLine("no stamp") == null && "error".equals(BenchProc.logLevel("ERROR")) && "info".equals(BenchProc.logLevel("")), "levels");
      double[] st = BenchCapture.stages(new long[] {1_000_000, 3_000_000, 4_000_000, 0, 0, 9_000_000});
      Check.check(st[0] == 2 && st[1] == 1 && Double.isNaN(st[2]) && Double.isNaN(st[3]) && Double.isNaN(st[4]), "a chunk's skipped recalc stages are empty");
   }

   // ------------------------------------------------------------------ a pass: hooks, samples, files, panels

   static void passFolder(File home) throws Exception {
      BenchCapture.testReset();
      BenchCapture.tick(); // a sample of the pre-roll
      long start = System.nanoTime(), t0 = start - 2_000_000_000L; // the route starts now; the hooks' pre-roll from 2 s before
      BenchCapture.testWindow(start, start + 4_000_000_000L);
      // game frames every 4 ms from t0, one 50 ms frame at route +1 s
      long ns = t0;
      while (ns < start + 4_000_000_000L) {
         BenchCapture.gameFrame(ns);
         ns += Math.abs(ns - (start + 1_000_000_000L)) < 2_000_000L ? 50_000_000L : 4_000_000L;
      }
      // chunks: one before the route, three in it (one without a recalc)
      BenchCapture.chunk(t0 + 100_000_000L, t0 + 110_000_000L, t0 + 120_000_000L, t0 + 121_000_000L, t0 + 130_000_000L, t0 + 131_000_000L, 1, 2, 0, 3, "LoadChunk", "streamer");
      for (int i = 0; i < 3; i++) {
         long e = start + 500_000_000L + i * 1_000_000_000L;
         boolean noRecalc = i == 2;
         BenchCapture.chunk(e, e + 2_000_000L, e + 7_000_000L, noRecalc ? 0 : e + 8_000_000L, noRecalc ? 0 : e + 18_000_000L, e + 20_000_000L, 10 + i, 20, 0, 1,
               "LoadChunk", "pzopt-recalc-" + i);
      }
      // pacing rows: a frame every 5 ms through the route; shown on glass 8 ms after the swap
      for (long f = start; f < start + 4_000_000_000L; f += 5_000_000L) {
         long[] row = new long[9];
         row[1] = f;
         row[2] = f + 1_000_000L;
         row[3] = f + 3_000_000L;
         row[4] = f + 3_500_000L;
         row[5] = 200_000L;
         row[6] = f + 4_500_000L;
         row[8] = f + 11_500_000L;
         BenchCapture.pacing(row);
      }
      // real samples of this JVM: the sampler's tick, as the thread would take them
      for (int i = 0; i < 3; i++) {
         BenchCapture.tick();
         Thread.sleep(30);
      }
      Map<String, String> sum = BenchCapture.summary();
      Check.check(sum.containsKey("capture_samples") && "3".equals(sum.get("chunks_traced")), "summary: samples and the route's chunks: " + sum);
      double p99 = Double.parseDouble(sum.get("chunk_p99_ms"));
      Check.check(p99 > 19.5 && p99 <= 20.0001, "chunk p99 over 20 / 20 / 7 ms (the last without recalc): " + p99);

      String run = "20261021-130000";
      File dir = BenchPlan.runDir(run);
      dir.mkdirs();
      String base = "step-00-spin-A-1";
      BenchCapture.write(dir, base);
      for (String f : new String[] {"gameframes.csv", "chunks.csv", "pacing.csv", "flips.csv"}) {
         Check.check(new File(dir, base + "-" + f).isFile(), "written: " + f);
      }
      List<String> gfl = Files.readAllLines(new File(dir, base + "-gameframes.csv").toPath());
      Check.check(gfl.get(1).startsWith("-") && gfl.stream().anyMatch(x -> x.endsWith(",50.000")), "game frames from the pre-roll on, the 50 ms frame kept");
      List<String> ch = Files.readAllLines(new File(dir, base + "-chunks.csv").toPath());
      Check.check(ch.size() == 5 && ch.get(0).equals(BenchCapture.CHUNKS_HEADER) && ch.get(4).contains(",,,"), "four chunks, the last without recalc stages: " + ch);
      List<String> pc = Files.readAllLines(new File(dir, base + "-pacing.csv").toPath());
      Check.check(pc.size() > 30 && pc.get(1).startsWith("t_s,frames,sim_step_min"), "pacing bins: " + pc.size());
      Check.check(pc.get(5).split(",")[1].equals("20") && pc.get(5).contains("8.000"), "20 frames a 0.1 s bin, shown 8 ms after the swap: " + pc.get(5));
      if (new File("/proc/self/task").isDirectory()) {
         for (String f : new String[] {"sysmon.csv", "sched.csv", "series.csv", "threads.out"}) {
            Check.check(new File(dir, base + "-" + f).isFile(), "written on Linux: " + f);
         }
         List<String> sm = Files.readAllLines(new File(dir, base + "-sysmon.csv").toPath());
         Check.check(sm.get(0).equals(BenchCapture.SYSMON_HEADER) && sm.get(1).split(",", -1).length == BenchCapture.SYSMON_HEADER.split(",").length,
               "sysmon.sh's columns: " + sm.get(1));
         Check.check(Files.readString(new File(dir, base + "-sched.csv").toPath()).contains("source=proc"), "OS threads from /proc");
      }

      // the game's per-second logs: a window copy of the stacks keeps the frame ids it uses
      long startMs = System.currentTimeMillis();
      File stacks = new File(home, "stacks.out");
      Files.writeString(stacks.toPath(), "# header\nf 0 MainThread.mainLoop\nf 1 GameWindow.logic\nt " + (startMs - 60_000) + " 10\n0;1 10\nf 2 IsoWorld.update\n"
            + "t " + (startMs + 1000) + " 25\n0;2 20\n0;1 5\nt " + (startMs + 2000) + " 25\n0;2 25\n", StandardCharsets.UTF_8);
      BenchCapture.copyStacks(stacks, new File(dir, base + "-stacks.out"), startMs - 30_000, startMs + 5000);
      List<String> sk = Files.readAllLines(new File(dir, base + "-stacks.out").toPath());
      Check.check(sk.contains("f 1 GameWindow.logic") && sk.contains("f 2 IsoWorld.update") && sk.stream().filter(x -> x.startsWith("t ")).count() == 2,
            "the window's two seconds and every id they use: " + sk);
      File input = new File(home, "input.out");
      Files.writeString(input.toPath(), "# pzopt input log\n" + (startMs - 90_000) * 1000 + "\tkey\tW\t1\n" + (startMs + 500) * 1000 + "\tkey\tW\t1\n" + (startMs + 900) * 1000
            + "\tkey\tW\t0\n" + (startMs + 1200) * 1000 + "\tmouse\tx\t640\n", StandardCharsets.UTF_8);
      BenchCapture.copyWindow(input, new File(dir, base + "-input.out"), startMs - 30_000, startMs + 5000, 1000);
      Check.check(Files.readAllLines(new File(dir, base + "-input.out").toPath()).size() == 4, "the input window (epoch us): the header and three events");
      Files.writeString(new File(dir, base + "-console.out").toPath(), startMs + 300 + "\t0,General,General,\"LOG  : General     > [pzopt] benchmark: route started\"\n"
            + (startMs + 800) + "\t1,General,General,\"ERROR: General     > something failed\"\n", StandardCharsets.UTF_8);
      Files.writeString(new File(dir, base + "-drive.out").toPath(), DrivePilot.HEADER + "\n0.050\t1\t2\t0\t0.10\t1.0\t5\t20.0\t120.0\t0.1\t0.0\t0\t0.0\t0\t-\t0\n"
            + "0.100\t1\t2\t0\t0.12\t1.0\t6\t22.0\t120.0\t0.1\t0.0\t0\t0.0\t0\t-\t1\n", StandardCharsets.UTF_8);

      // the pass's properties as Benchmark writes them
      Map<String, String> p = new LinkedHashMap<>();
      p.put("scenario", "spin");
      p.put("config", "A");
      p.put("pass", "1");
      p.put("status", "ok");
      p.put("seconds", "4.0");
      p.put("started_ms", Long.toString(startMs));
      p.put("sys.os", "Linux 7.2");
      p.putAll(sum);
      p.put("gpu_c", "61.5");
      p.put("vram_mib", "9000");
      BenchResults.writeStep(run, 0, p);
      StringBuilder frames = new StringBuilder("Application,ProcessID,TimeInSeconds,MsBetweenPresents,MsCPUBusy,MsGPUBusy,MsRenderThread,MsInPresentAPI\n");
      for (int i = 1; i <= 800; i++) {
         frames.append(String.format(java.util.Locale.ROOT, "ProjectZomboid,1,%.6f,5.0000,4.0000,2.0000,1.0000,0.1000\n", i * 0.005));
      }
      Files.writeString(new File(dir, base + "-frames.csv").toPath(), frames.toString(), StandardCharsets.UTF_8);
      String text = BenchDash.text(run + "\t\t\t");
      Check.check(text.contains("WINDOW\t-30\t120"), "Grafana's window");
      Check.check(Math.abs(Double.parseDouble(value(panel(text, 5), "V", "chunk p99 ms")) - p99) < 1e-3, "chunk p99 tile: " + value(panel(text, 5), "V", "chunk p99 ms"));
      Check.check("61.5".equals(value(panel(text, 6), "V", "°C")) && "9000".equals(value(panel(text, 6), "V", "VRAM MiB")), "GPU tile: temperature, VRAM");
      Check.check(value(panel(text, 11), "B", "game frame") != null && value(panel(text, 11), "B", "presented") != null, "game frames and presented frames");
      List<String[]> pct = panel(text, 15);
      Check.check("game frame".equals(pct.get(1)[1]) && Integer.parseInt(pct.get(1)[2]) > 900 && "1".equals(pct.get(1)[9]), "game-frame row over the route: " + String.join(",", pct.get(1)));
      Check.check("flip interval".equals(pct.get(4)[1]) && Integer.parseInt(pct.get(4)[2]) > 700 && "5".equals(pct.get(4)[3]), "flip intervals from on-glass times: " + String.join(",", pct.get(4)));
      Check.check(value(panel(text, 34), "S", "total") != null, "chunk latency points");
      List<String[]> p35 = panel(text, 35);
      Check.check(value(p35, "S", "recalc") != null && value(p35, "S", "chunks/s") != null, "chunk stages per second");
      Check.check(value(panel(text, 37), "S", "flip interval") != null, "flip chart");
      Check.check(value(panel(text, 39), "S", "kmh") != null && value(panel(text, 40), "S", "chunk_ahead") != null, "drive panels");
      List<String[]> flame = panel(text, 42);
      Check.check(flame.size() == 4 && flame.get(0)[4].equals("all") && flame.get(0)[2].equals("50") && flame.get(1)[4].equals("MainThread.mainLoop")
            && flame.get(2)[4].equals("GameWindow.logic") && flame.get(3)[4].equals("IsoWorld.update") && flame.get(3)[2].equals("45"), "the flame graph's nodes depth first, by name: "
            + flame.stream().map(r -> String.join(" ", r)).toList());
      Check.check(value(panel(text, 43), "S", "MainThread") != null, "samples per second");
      Check.check(value(panel(text, 49), "B", "shown after swap") != null && value(panel(text, 49), "B", "sim step") != null, "pacing bands");
      Check.check(value(panel(text, 51), "S", "key W") != null && value(panel(text, 52), "S", "mouse x") != null, "keys and the pointer");
      Check.check(panel(text, 53).get(0)[0].equals("WHY"), "no wheel: a reason");
      Check.check(panel(text, 54).size() == 4 && panel(text, 54).get(1)[1].equals("0.5"), "every input event, route seconds: " + String.join("|", panel(text, 54).get(1)));
      List<String[]> logs = panel(text, 57);
      Check.check(logs.size() == 2 && logs.get(0)[2].equals("info") && logs.get(0)[3].equals("General [pzopt] benchmark: route started") && logs.get(1)[2].equals("error"),
            "console lines with Grafana's levels: " + logs.stream().map(r -> String.join("|", r)).toList());
      if (new File("/proc/self/task").isDirectory()) {
         Check.check(value(panel(text, 17), "S", "busiest core") != null && value(panel(text, 19), "S", "game process cores") != null, "machine and process from the in-game sysmon");
         Check.check(panel(text, 21).size() >= 2 && panel(text, 21).get(0)[1].equals("thread") && panel(text, 21).get(1)[1].equals("main"), "CPU per thread table: " + panel(text, 21).stream().map(r -> String.join("|", r)).toList());
         Check.check(panel(text, 45).stream().anyMatch(r -> r[0].equals("S")), "CPU per OS thread");
         Check.check(panel(text, 48).stream().anyMatch(r -> r[0].equals("S") && r[1].equals("total_mib")), "memory by kind");
      }
      Check.check(panel(text, 8).get(0)[0].equals("WHY") && panel(text, 24).get(0)[0].equals("WHY"), "video and sysmon power: reasons");

      // the same pass on Windows: the Linux-only panels say why
      new File(dir, base + "-series.csv").delete();
      Files.writeString(new File(dir, base + "-sched.csv").toPath(), "# source=jvm\nt_s,thread,cpu_pct,runq_pct\n0.5,MainThread,90.00,\n1.5,MainThread,91.00,\n", StandardCharsets.UTF_8);
      new File(dir, base + "-flips.csv").delete();
      p.put("sys.os", "Windows 11");
      BenchResults.writeStep(run, 0, p);
      text = BenchDash.text(run + "\t\t\t");
      Check.check(value(panel(text, 45), "S", "MainThread") != null, "Java threads' CPU on Windows");
      Check.check(why(panel(text, 46)).contains("Linux") && why(panel(text, 47)).contains("Linux only") && why(panel(text, 48)).contains("Linux only"), "Linux-only panels: "
            + why(panel(text, 46)) + " / " + why(panel(text, 47)));
      Check.check(why(panel(text, 37)).contains("X Present") && "0".equals(panel(text, 15).get(4)[2]), "no flips off the Mac: a reason, 0 frames");
      BenchCapture.testReset();
   }

   // ------------------------------------------------------------------ what the collectors cost

   static void cost() throws Exception {
      BenchCapture.testReset();
      long t = System.nanoTime();
      BenchCapture.testWindow(t, t + 60_000_000_000L);
      // the game's process has ~200 threads (the frame / bake / recalc pools, the JVM's own): park as many here
      java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
      List<Thread> idle = new java.util.ArrayList<>();
      for (int i = 0; i < 200; i++) {
         Thread th = new Thread(() -> {
            try {
               done.await();
            } catch (InterruptedException ignored) {
            }
         }, "idle-" + i);
         th.setDaemon(true);
         th.start();
         idle.add(th);
      }
      for (int i = 0; i < 5; i++) {
         BenchCapture.tick(); // (warm-up: class loading, NVML discovery, the new threads' names)
      }
      int n = 40;
      long[] each = new long[n];
      long cpu0 = java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
      for (int i = 0; i < n; i++) {
         long a = System.nanoTime();
         BenchCapture.tick();
         each[i] = System.nanoTime() - a;
      }
      long cpu = java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - cpu0;
      int threads = Thread.activeCount();
      done.countDown();
      java.util.Arrays.sort(each);
      double mean = java.util.Arrays.stream(each).average().orElse(0) / 1e3;
      double gpuUs = 0;
      for (int i = 0; i < 100; i++) {
         long a = System.nanoTime();
         BenchGpu.read();
         gpuUs += (System.nanoTime() - a) / 1e3;
      }
      gpuUs /= 100;
      int frames = 2_000_000;
      long a = System.nanoTime();
      for (int i = 0; i < frames; i++) {
         if (i % (1 << 20) == 0) {
            BenchCapture.testReset(); // (the arrays' cap)
         }
         BenchCapture.gameFrame(a + i);
      }
      double frameNs = (System.nanoTime() - a) / (double)frames;
      long[] row = new long[9];
      int rows = 500_000;
      a = System.nanoTime();
      for (int i = 0; i < rows; i++) {
         row[1] = i;
         BenchCapture.pacing(row);
      }
      double pacingNs = (System.nanoTime() - a) / (double)rows;
      a = System.nanoTime();
      for (int i = 0; i < 100_000; i++) {
         BenchCapture.chunk(i, i + 1, i + 2, i + 3, i + 4, i + 5, i, i, 0, 1, "LoadChunk", "t");
      }
      double chunkNs = (System.nanoTime() - a) / 1e5;
      BenchCapture.testReset();
      System.out.println(String.format(java.util.Locale.ROOT,
            "BenchCaptureTest cost (%d threads): sampler tick mean %.0f us, median %.0f us, max %.0f us, CPU %.0f us a tick (2 Hz = %.3f %% of one core; GPU sensors %s %.0f us); "
                  + "game-frame hook %.1f ns, pacing hook %.1f ns, chunk hook %.0f ns",
            threads, mean, each[n / 2] / 1e3, each[n - 1] / 1e3, cpu / 1e3 / n, cpu / 1e3 / n * 2 / 1e6 * 100, BenchGpu.source(), gpuUs, frameNs, pacingNs, chunkNs));
      Check.check(mean < 50_000, "a sample stays far below the 500 ms period: " + mean + " us");
      Check.check(frameNs < 200 && pacingNs < 500, "the per-frame hooks are an array store: " + frameNs + " / " + pacingNs + " ns");
   }
}
