package pzopt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.locks.LockSupport;
import zombie.ZomboidFileSystem;

/**
 * devSwapProbe (f9-present, 2026-10-15): where the render thread's "swap" time goes.
 *
 * Pacing's swap block (swap call -> swap return) spans the whole of Display.update(true): the GLX swap, the input-lag
 * and pacing hooks, glfwPollEvents, Keyboard / Mouse polls, the cursor update and glfwGetWindowAttrib(FOCUSED) (on X11
 * a synchronous XGetInputFocus round trip to the X server). {@link #mark} stamps the boundaries ({@link #SEGMENTS});
 * {@link #frameEnd} writes one row per frame to pzopt-swapprobe.out with each segment's duration in microseconds.
 *
 * A sampler thread watches the open segment: once it has lasted {@code devSwapProbeSampleUs}, it reads the render
 * thread's /proc/self/task/TID/syscall (number, first two arguments) and wchan, resolves the fd of a syscall that has one,
 * and reads the X server's (Xwayland) and kwin_wayland's main-thread state + wchan; one "S" row per sample. Reading another
 * thread of the same process needs no ptrace permission; the other processes' wchan needs the same uid only.
 */
public final class SwapProbe {
   public static final boolean ON = Config.DEV_SWAP_PROBE && Overrides.enabled();
   /** Segment boundaries, in call order within Display.update(true). */
   public static final int CALL = 0, SWAP_BEGIN = 1, SWAP_END = 2, POLL_BEGIN = 3, POLL_END = 4, INPUT_END = 5, CURSOR_END = 6,
         FOCUS_BEGIN = 7, FOCUS_END = 8, RET = 9, N = 10;
   private static final String[] SEGMENTS = {"pre", "swap", "post", "poll", "input", "cursor", "misc", "focus", "tail"};
   private static final long[] t = new long[N];
   private static long frame;
   private static Thread inUpdate;
   private static volatile int openSeg = -1;
   private static volatile long openSince;
   private static volatile long openFrame;
   private static BufferedWriter out;
   private static boolean failed;
   private static long lastFlush;
   private static String rtTask;
   private static String xwTask, kwTask;
   private static Thread sampler;
   private static final StringBuilder sb = new StringBuilder(256);

   private SwapProbe() {
   }

   /** Render thread: a boundary of Display.update(true). */
   public static void mark(int k) {
      if (!ON) {
         return;
      }
      if (k == CALL) {
         inUpdate = Thread.currentThread();
      } else if (inUpdate != Thread.currentThread()) {
         return; // processMessages outside update(true)
      }
      long now = System.nanoTime();
      t[k] = now;
      if (k == RET) {
         inUpdate = null;
      }
      if (rtTask == null) {
         init();
      }
      // the segment that starts here is "k" (segment i runs from boundary i to boundary i + 1)
      if (k < RET) {
         openFrame = frame;
         openSince = now;
         openSeg = k;
      } else {
         openSeg = -1;
         frameEnd();
      }
   }

   private static long holdRun, holdWait, holdSlices;

   /** Render thread, frameQueue's hold starts: the thread's schedstat (on-CPU ns, run-queue wait ns, timeslices). */
   public static void holdBegin() {
      if (!ON) {
         return;
      }
      long[] s = schedstat();
      holdRun = s[0];
      holdWait = s[1];
      holdSlices = s[2];
   }

   /** Render thread, the hold ended overshootNs after its target: an "H" row when it was late by more than 0.2 ms. */
   public static void holdEnd(long overshootNs) {
      if (!ON || overshootNs <= 200_000L || out == null) {
         return;
      }
      long[] s = schedstat();
      String line = "H " + frame + " " + overshootNs / 1000L + " " + (s[1] - holdWait) / 1000L + " " + (s[0] - holdRun) / 1000L + " "
            + (s[2] - holdSlices) + "\n";
      try {
         synchronized (SwapProbe.class) {
            out.write(line);
         }
      } catch (IOException ignored) {
      }
   }

   private static long[] schedstat() {
      String[] p = read("/proc/thread-self/schedstat").split(" ");
      long[] r = new long[3];
      for (int i = 0; i < 3 && i < p.length; i++) {
         try {
            r[i] = Long.parseLong(p[i]);
         } catch (NumberFormatException ignored) {
         }
      }
      return r;
   }

   private static void frameEnd() {
      if (failed) {
         return;
      }
      try {
         if (out == null) {
            String dir = ZomboidFileSystem.instance.getCacheDir();
            if (dir == null) {
               return;
            }
            out = new BufferedWriter(new FileWriter(new File(dir, "pzopt-swapprobe.out"), false), 1 << 16);
            out.write("# F frame call_ns then us per segment: " + String.join(" ", SEGMENTS) + " (0 = boundary not reached)\n");
            out.write("# H frame overshoot_us runqueue_wait_us oncpu_us timeslices (frameQueue holds that ended > 0.2 ms late)\n");
            out.write("# S frame seg t_us rt_syscall arg0 arg1 rt_wchan fd_target | xwayland state wchan | kwin state wchan | watched threads not asleep: tid:comm=state,wchan,syscall\n");
         }
         StringBuilder b = sb;
         b.setLength(0);
         b.append("F ").append(frame).append(' ').append(t[CALL]);
         for (int i = 0; i < RET; i++) {
            long a = t[i], z = t[i + 1];
            b.append(' ').append(a != 0L && z != 0L ? (z - a) / 1000L : 0L);
         }
         b.append('\n');
         synchronized (SwapProbe.class) {
            out.write(b.toString());
            long now = System.nanoTime();
            if (now - lastFlush > 1_000_000_000L) {
               out.flush();
               lastFlush = now;
            }
         }
      } catch (IOException | RuntimeException e) {
         failed = true;
         Log.warn("swap probe: log stopped: " + e);
      }
      java.util.Arrays.fill(t, 0L);
      frame++;
   }

   private static void init() {
      try {
         rtTask = "/proc/self/task/" + Paths.get("/proc/thread-self").toRealPath().getFileName();
      } catch (IOException | RuntimeException e) {
         rtTask = "";
      }
      xwTask = findMain("Xwayland");
      kwTask = findMain("kwin_wayland");
      scanNative();
      Log.info("swap probe: render task " + rtTask + ", xwayland " + xwTask + ", kwin " + kwTask + ", sample after "
            + Config.DEV_SWAP_PROBE_SAMPLE_US + " us");
      if (!rtTask.isEmpty()) {
         sampler = new Thread(SwapProbe::sampleLoop, "pzopt-swapprobe");
         sampler.setDaemon(true);
         sampler.start();
      }
   }

   /** Threads of this process that are not plainly Java ones (driver workers inherit the render thread's comm). */
   private static volatile String[] nativeTasks = new String[0];
   private static volatile String[] nativeNames = new String[0];
   private static long lastScanNs;
   private static final String[] JAVA = {"pzopt", "pool-", "Thread", "C1 ", "C2 ", "GC ", "G1 ", "VM ", "Signal", "Finalizer",
         "Reference", "Common-Cleaner", "Notification", "Service", "Sweeper", "Monitor", "Attach", "process reaper", "JFR", "Lighting",
         "World", "Pathfind", "MainThread", "Java2D", "AWT", "Timer", "jdk", "ForkJoin", "Cleaner", "Network", "Shutdown", "Destroy"};

   private static void scanNative() {
      lastScanNs = System.nanoTime();
      java.util.ArrayList<String> t = new java.util.ArrayList<>(), n = new java.util.ArrayList<>();
      StringBuilder all = new StringBuilder();
      try (var ds = Files.newDirectoryStream(Paths.get("/proc/self/task"))) {
         for (Path p : ds) {
            String task = "/proc/self/task/" + p.getFileName();
            if (task.equals(rtTask)) {
               continue;
            }
            String c = read(task + "/comm");
            boolean java = false;
            for (String j : JAVA) {
               java |= c.startsWith(j);
            }
            if (!java) {
               t.add(task);
               n.add(p.getFileName() + ":" + c.replace(' ', '_'));
               all.append(' ').append(p.getFileName()).append(':').append(c.replace(' ', '_'));
            }
         }
      } catch (IOException | RuntimeException ignored) {
      }
      nativeTasks = t.toArray(new String[0]);
      nativeNames = n.toArray(new String[0]);
      Log.info("swap probe: watched threads" + all);
   }

   /** One sample of every watched thread that is running or not in a plain sleep: name state wchan syscall-nr. */
   private static String nativeStates() {
      StringBuilder b = new StringBuilder();
      String[] t = nativeTasks, n = nativeNames;
      for (int i = 0; i < t.length; i++) {
         String st = state(t[i]);
         String wc = read(t[i] + "/wchan");
         if (st.equals("S") && (wc.equals("futex_wait_queue") || wc.equals("__futex_wait") || wc.equals("do_epoll_wait")
               || wc.equals("ep_poll") || wc.equals("hrtimer_nanosleep") || wc.equals("do_nanosleep"))) {
            continue;
         }
         String sc = read(t[i] + "/syscall");
         int sp = sc.indexOf(' ');
         b.append(' ').append(n[i]).append('=').append(st).append(',').append(wc.isEmpty() ? "0" : wc).append(',')
               .append(sp > 0 ? sc.substring(0, sp) : sc);
      }
      return b.toString();
   }

   private static String findMain(String comm) {
      try (var ds = Files.newDirectoryStream(Paths.get("/proc"))) {
         for (Path p : ds) {
            String n = p.getFileName().toString();
            if (!n.chars().allMatch(Character::isDigit)) {
               continue;
            }
            try {
               if (Files.readString(p.resolve("comm")).trim().equals(comm)) {
                  return "/proc/" + n + "/task/" + n;
               }
            } catch (IOException ignored) {
            }
         }
      } catch (IOException ignored) {
      }
      return null;
   }

   private static String read(String path) {
      try {
         return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.US_ASCII).trim();
      } catch (IOException | RuntimeException e) {
         return "?";
      }
   }

   /** "state" field of /proc/.../stat (after the comm's closing parenthesis). */
   private static String state(String task) {
      String s = read(task + "/stat");
      int i = s.lastIndexOf(')');
      return i >= 0 && i + 2 < s.length() ? s.substring(i + 2, i + 3) : "?";
   }

   private static void sampleLoop() {
      long minNs = Math.max(20L, Config.DEV_SWAP_PROBE_SAMPLE_US) * 1000L;
      long lastFrame = -1L;
      int perFrame = 0;
      while (true) {
         int seg = openSeg;
         long since = openSince;
         long f = openFrame;
         long now = System.nanoTime();
         if (seg < 0 || now - since < minNs) {
            LockSupport.parkNanos(seg < 0 ? 200_000L : 25_000L);
            continue;
         }
         if (f != lastFrame) {
            lastFrame = f;
            perFrame = 0;
         }
         if (perFrame >= 64) {
            LockSupport.parkNanos(100_000L);
            continue;
         }
         perFrame++;
         String sc = read(rtTask + "/syscall");
         String wc = read(rtTask + "/wchan");
         String fdt = "-";
         String[] p = sc.split(" ");
         if (p.length >= 3) {
            int nr = -1;
            try {
               nr = Integer.parseInt(p[0]);
            } catch (NumberFormatException ignored) {
            }
            // read / write / ioctl / recvmsg / sendmsg / recvfrom / sendto / fsync / fdatasync / writev / readv: arg0 is an fd
            if (nr == 0 || nr == 1 || nr == 16 || nr == 47 || nr == 46 || nr == 45 || nr == 44 || nr == 20 || nr == 19) {
               try {
                  long fd = Long.decode(p[1]);
                  fdt = Files.readSymbolicLink(Paths.get("/proc/self/fd/" + fd)).toString();
               } catch (IOException | RuntimeException ignored) {
                  fdt = "?";
               }
            }
         }
         if (now - lastScanNs > 5_000_000_000L) {
            scanNative(); // threads come and go (the driver's worker is created with the context, FMOD's at load)
         }
         String nat = nativeStates();
         String xw = xwTask == null ? "- -" : state(xwTask) + " " + read(xwTask + "/wchan");
         String kw = kwTask == null ? "- -" : state(kwTask) + " " + read(kwTask + "/wchan");
         if (openSeg != seg || openFrame != f) {
            continue; // the segment ended while we read: the sample may belong to the next one
         }
         String[] a = sc.split(" ");
         String line = "S " + f + " " + SEGMENTS[seg] + " " + (now - since) / 1000L + " " + (a.length > 0 ? a[0] : "?") + " "
               + (a.length > 1 ? a[1] : "-") + " " + (a.length > 2 ? a[2] : "-") + " " + (wc.isEmpty() ? "0" : wc) + " " + fdt
               + " | " + xw + " | " + kw + " |" + nat + "\n";
         try {
            synchronized (SwapProbe.class) {
               if (out != null) {
                  out.write(line);
               }
            }
         } catch (IOException e) {
            return;
         }
         LockSupport.parkNanos(Math.max(20L, Config.DEV_SWAP_PROBE_PERIOD_US) * 1000L);
      }
   }
}
