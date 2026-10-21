package pzopt;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The machine and settings a benchmark result belongs to (technique 14): CPU, cores, RAM, GPU renderer / vendor / GL
 * version, OS, Java, screen, refresh, game revision, pzopt build, the PZ Optimization settings that differ from the
 * defaults, the game's own display options; {@link #key} hashes what makes two results comparable.
 */
public final class BenchSystem {
   private static volatile String glVendor = "", glRenderer = "", glVersion = "";
   private static volatile int refreshHz;
   private static volatile boolean glCaptured;

   private BenchSystem() {
   }

   /** Render thread (Overlay.gpuBegin), once: the GL strings and the monitor's refresh rate. */
   static void captureGl() {
      if (glCaptured) {
         return;
      }
      glCaptured = true;
      try {
         glVendor = s(org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR));
         glRenderer = s(org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));
         glVersion = s(org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION));
      } catch (Throwable t) {
         Log.warn("benchmark: GL strings: " + t);
      }
      try {
         long mon = org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor();
         org.lwjgl.glfw.GLFWVidMode mode = mon == 0L ? null : org.lwjgl.glfw.GLFW.glfwGetVideoMode(mon);
         if (mode != null) {
            refreshHz = mode.refreshRate();
         }
      } catch (Throwable t) {
         Log.warn("benchmark: refresh rate: " + t);
      }
   }

   static int refreshHz() {
      return refreshHz;
   }

   private static String s(String v) {
      return v == null ? "" : v.trim();
   }

   static Map<String, String> describe() {
      Map<String, String> m = new LinkedHashMap<>();
      m.put("cpu", cpuName());
      m.put("cores", Integer.toString(Runtime.getRuntime().availableProcessors()));
      long ram = 0;
      try {
         java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
         if (os instanceof com.sun.management.OperatingSystemMXBean so) {
            ram = so.getTotalMemorySize();
         }
      } catch (Throwable ignored) {
      }
      m.put("ram_gb", String.format(java.util.Locale.ROOT, "%.1f", ram / 1073741824.0));
      m.put("gpu", glRenderer);
      m.put("gpu_vendor", glVendor);
      m.put("gl", glVersion);
      m.put("os", System.getProperty("os.name", "") + " " + System.getProperty("os.version", "") + " " + System.getProperty("os.arch", ""));
      m.put("java", System.getProperty("java.vm.name", "") + " " + System.getProperty("java.runtime.version", System.getProperty("java.version", "")));
      m.put("heap_max_mb", Long.toString(Runtime.getRuntime().maxMemory() / 1048576L));
      m.put("gc", gcNames());
      try {
         zombie.core.Core c = zombie.core.Core.getInstance();
         m.put("screen", c.getScreenWidth() + "x" + c.getScreenHeight());
         m.put("game_version", String.valueOf(c.getGameVersion()));
         m.put("vsync", Boolean.toString(c.getOptionVSync()));
         m.put("fullscreen", Boolean.toString(c.isFullScreen()));
      } catch (Throwable t) {
         m.put("screen", "?");
      }
      m.put("refresh_hz", Integer.toString(refreshHz));
      m.put("pzopt_build", String.valueOf(BuildInfo.get("commit")));
      m.put("pzopt_enabled", Boolean.toString(Overrides.enabled()));
      m.put("frame_cap", FrameCap.uncappedNow() ? "uncapped" : Integer.toString(FrameCap.lockNow()));
      StringBuilder b = new StringBuilder();
      for (Map.Entry<String, String> e : Config.nonDefault().entrySet()) {
         if (isMeasurementKey(e.getKey())) {
            continue; // the benchmark's own pins (overlayLog, pacingLog...) say nothing about the player's setup
         }
         b.append(b.length() > 0 ? ";" : "").append(e.getKey()).append('=').append(e.getValue());
      }
      m.put("settings", b.toString());
      return m;
   }

   static boolean isMeasurementKey(String k) {
      return k.equals("overlay") || k.equals("overlayLog") || k.equals("pacingLog") || k.equals("updateCheck") || k.equals("enabled")
            || k.equals("instrument") || k.equals("consoleLog") || k.equals("benchmarkAuto");
   }

   /** What must match for two results to be compared: hardware, screen, game, build, settings, cap. */
   static String key(Map<String, String> sys) {
      String[] keys = {"cpu", "cores", "gpu", "os", "screen", "game_version", "pzopt_build", "settings", "frame_cap", "vsync"};
      StringBuilder b = new StringBuilder();
      for (String k : keys) {
         b.append(k).append('=').append(sys.getOrDefault(k, "")).append('\n');
      }
      try {
         byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
         StringBuilder h = new StringBuilder();
         for (int i = 0; i < 6; i++) {
            h.append(String.format("%02x", d[i]));
         }
         return h.toString();
      } catch (Exception e) {
         return Integer.toHexString(b.toString().hashCode());
      }
   }

   /** The same machine, whatever the settings (for "this PC before / after a settings change"). */
   static String machineKey(Map<String, String> sys) {
      Map<String, String> m = new LinkedHashMap<>(sys);
      m.put("settings", "");
      m.put("pzopt_build", "");
      m.put("frame_cap", "");
      m.put("vsync", "");
      return key(m);
   }

   private static String gcNames() {
      StringBuilder b = new StringBuilder();
      for (java.lang.management.GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
         b.append(b.length() > 0 ? "+" : "").append(g.getName());
      }
      return b.toString();
   }

   static String cpuName() {
      String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
      try {
         if (os.contains("linux")) {
            for (String line : Files.readAllLines(new File("/proc/cpuinfo").toPath())) {
               if (line.startsWith("model name")) {
                  return line.substring(line.indexOf(':') + 1).trim();
               }
            }
         } else if (os.contains("win")) {
            String id = System.getenv("PROCESSOR_IDENTIFIER");
            String reg = run("reg", "query", "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0", "/v", "ProcessorNameString");
            int i = reg.indexOf("REG_SZ");
            if (i >= 0) {
               return reg.substring(i + 6).trim();
            }
            return id == null ? "" : id;
         } else if (os.contains("mac")) {
            return run("sysctl", "-n", "machdep.cpu.brand_string").trim();
         }
      } catch (Exception ignored) {
      }
      return System.getProperty("os.arch", "");
   }

   private static String run(String... cmd) throws Exception {
      Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      byte[] out = p.getInputStream().readAllBytes();
      p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
      return new String(out, java.nio.charset.StandardCharsets.UTF_8);
   }
}
