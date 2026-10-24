package pzopt;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The PC's power state for the benchmark report (2026-10-24: the desktop sat in power-saver, boost off, 4.7 GHz, for every
 * run; uncapped CPU-bound scenes are 3-19 % faster in balanced). Two fields on every platform, the same two harness/run.sh
 * writes to run.opts:
 * <ul>
 * <li>{@code power_profile}: Linux power-profiles-daemon's profile (its state.ini), else the ACPI platform profile, else
 * tuned's; Windows the active power scheme (+ the Windows 10 / 11 power mode when it is not balanced, + battery saver);
 * macOS low-power / high-power / automatic.</li>
 * <li>{@code cpu_power}: space-separated key=value detail. Linux {@code governor= epp= boost= max_khz=} (cpu0's cpufreq,
 * boost per CPU first, then the global switch), as run.sh; Windows {@code scheme= mode= source= saver= min_state=
 * max_state= boost_mode=}; macOS {@code source= thermal= lowpowermode= powermode=}.</li>
 * </ul>
 * Missing values read "-", never an exception. Linux reads a few sysfs files inline (microseconds). Windows (powrprof /
 * kernel32 through FFM: no process, no console window) and macOS (Foundation / IOKit through FFM, plus one {@code pmset -g}
 * for the power mode) are read on a daemon thread: {@link #prime} starts it, {@link #fields} returns the newest reading
 * and starts a fresh one. Not part of {@link BenchSystem#key}: a run in another profile stays comparable, the compare
 * table marks the row instead.
 */
final class BenchPower {
   static final String NONE = "-";
   private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
   private static final boolean LINUX = OS.contains("linux"), WIN = OS.contains("win"), MAC = OS.contains("mac");
   private static volatile Map<String, String> cached;
   private static final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean();
   private static volatile boolean logged;

   private BenchPower() {
   }

   /** Any thread, cheap: starts the first reading in the background (Windows / macOS) and logs it once. */
   static void prime() {
      if (LINUX) {
         if (!logged) {
            logged = true;
            log(linux(BenchPower::readFile));
         }
         return;
      }
      refreshAsync();
   }

   /** power_profile and cpu_power now (Linux) or as last read in the background ("-" until the first reading). */
   static Map<String, String> fields() {
      if (LINUX) {
         return linux(BenchPower::readFile);
      }
      Map<String, String> m = cached;
      refreshAsync();
      return m != null ? m : unknown();
   }

   static Map<String, String> unknown() {
      return of(NONE, NONE);
   }

   private static Map<String, String> of(String profile, String cpu) {
      Map<String, String> m = new LinkedHashMap<>();
      m.put("power_profile", profile == null || profile.isEmpty() ? NONE : profile);
      m.put("cpu_power", cpu == null || cpu.isEmpty() ? NONE : cpu);
      return m;
   }

   private static void refreshAsync() {
      if (!busy.compareAndSet(false, true)) {
         return;
      }
      Thread t = new Thread(() -> {
         try {
            Map<String, String> m = WIN ? windows() : MAC ? mac() : unknown();
            cached = m;
            if (!logged) {
               logged = true;
               log(m);
            }
         } catch (Throwable t2) {
            Log.info("benchmark: power state unreadable: " + t2);
         } finally {
            busy.set(false);
         }
      }, "pzopt-bench-power");
      t.setDaemon(true);
      t.start();
   }

   private static void log(Map<String, String> m) {
      Log.info("benchmark: power state: power_profile=" + m.get("power_profile") + " cpu_power=" + m.get("cpu_power"));
   }

   // ------------------------------------------------------------------ Linux

   static String readFile(String path) {
      try {
         Path p = Path.of(path);
         return Files.isReadable(p) ? Files.readString(p, StandardCharsets.UTF_8).trim() : null;
      } catch (Throwable t) {
         return null;
      }
   }

   /** Linux, from {@code read} (path -> trimmed content, null when absent): run.sh's power_profile= and cpu_power= lines. */
   static Map<String, String> linux(Function<String, String> read) {
      String profile = ppdProfile(read.apply("/var/lib/power-profiles-daemon/state.ini"));
      if (profile == null) {
         profile = read.apply("/sys/firmware/acpi/platform_profile");
      }
      if (profile == null || profile.isEmpty()) {
         profile = read.apply("/etc/tuned/active_profile");
      }
      String cpu = "/sys/devices/system/cpu/cpu0/cpufreq/";
      String boost = read.apply(cpu + "boost");
      if (boost == null || boost.isEmpty()) {
         boost = read.apply("/sys/devices/system/cpu/cpufreq/boost");
      }
      if (boost == null || boost.isEmpty()) { // intel_pstate: boost = not no_turbo
         String noTurbo = read.apply("/sys/devices/system/cpu/intel_pstate/no_turbo");
         boost = "1".equals(noTurbo) ? "0" : "0".equals(noTurbo) ? "1" : null;
      }
      String detail = "governor=" + dash(read.apply(cpu + "scaling_governor")) + " epp=" + dash(read.apply(cpu + "energy_performance_preference"))
            + " boost=" + dash(boost) + " max_khz=" + dash(read.apply(cpu + "scaling_max_freq"));
      return of(profile, detail);
   }

   /** power-profiles-daemon's state.ini: the [State] section's Profile= (null without one). */
   static String ppdProfile(String stateIni) {
      if (stateIni == null) {
         return null;
      }
      boolean state = false;
      for (String l : stateIni.split("\n")) {
         l = l.trim();
         if (l.startsWith("[")) {
            state = l.equals("[State]");
         } else if (state && l.startsWith("Profile=")) {
            String v = l.substring(8).trim();
            return v.isEmpty() ? null : v;
         }
      }
      return null;
   }

   private static String dash(String v) {
      return v == null || v.isEmpty() ? NONE : v.replace(' ', '_');
   }

   // ------------------------------------------------------------------ Windows

   static final String GUID_PROCESSOR_SUBGROUP = "54533251-82be-4824-96c1-47b60b740d00";
   static final String GUID_THROTTLE_MIN = "893dee8e-2bef-41e0-89c6-b55d0929964c";
   static final String GUID_THROTTLE_MAX = "bc5038f7-23e0-4960-96da-33abaf5935ec";
   static final String GUID_BOOST_MODE = "be337238-0d82-4146-a960-4f3749d470c7";

   /** The built-in power schemes by GUID (custom schemes keep their friendly name). */
   static String schemeName(String guid) {
      return switch (guid == null ? "" : guid.toLowerCase(Locale.ROOT)) {
         case "381b4222-f694-41f0-9685-ff5bb260df2e" -> "balanced";
         case "8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c" -> "high-performance";
         case "a1841308-3541-4fab-bc81-f71556f20b4a" -> "power-saver";
         case "e9a42b02-d5df-448d-aa00-03f14749eb61" -> "ultimate-performance";
         default -> null;
      };
   }

   /** The power mode overlay (Settings > Power mode; Windows 10 1709+ / 11) by GUID. */
   static String overlayName(String guid) {
      return switch (guid == null ? "" : guid.toLowerCase(Locale.ROOT)) {
         case "00000000-0000-0000-0000-000000000000" -> "balanced";
         case "961cc777-2547-4f9d-8174-7d86181b8a7a" -> "best-power-efficiency";
         case "3af9b8d9-7c97-431d-ad78-34a8bfea439b" -> "better-performance";
         case "ded574b5-45a0-4f42-8737-46345c09c238" -> "best-performance";
         default -> guid == null ? null : guid.toLowerCase(Locale.ROOT);
      };
   }

   /** PERFBOOSTMODE's values (Processor performance boost mode). */
   static String boostModeName(long v) {
      return switch ((int)v) {
         case 0 -> "disabled";
         case 1 -> "enabled";
         case 2 -> "aggressive";
         case 3 -> "efficient-enabled";
         case 4 -> "efficient-aggressive";
         case 5 -> "aggressive-at-guaranteed";
         case 6 -> "efficient-aggressive-at-guaranteed";
         default -> v < 0 ? null : Long.toString(v);
      };
   }

   /**
    * The two fields from what Windows reported (null = not read): the scheme's GUID and friendly name, the effective power
    * mode overlay GUID, AC power, battery saver, the processor min / max state (%) and boost mode of the current source.
    */
   static Map<String, String> windowsFields(String schemeGuid, String friendly, String overlayGuid, Boolean ac, Boolean saver,
         long minState, long maxState, long boostMode) {
      String known = schemeName(schemeGuid);
      String scheme = known != null ? known : friendly != null && !friendly.isEmpty() ? friendly : schemeGuid;
      String mode = overlayGuid == null ? null : overlayName(overlayGuid);
      StringBuilder profile = new StringBuilder(scheme == null ? NONE : scheme);
      if (mode != null && !mode.equals("balanced")) {
         profile.append('/').append(mode);
      }
      if (Boolean.TRUE.equals(saver)) {
         profile.append("/battery-saver");
      }
      String detail = "scheme=" + dash(friendly != null && !friendly.isEmpty() ? friendly : scheme) + " mode=" + dash(mode)
            + " source=" + (ac == null ? NONE : ac ? "ac" : "battery") + " saver=" + (saver == null ? NONE : saver ? "on" : "off")
            + " min_state=" + (minState < 0 ? NONE : minState + "%") + " max_state=" + (maxState < 0 ? NONE : maxState + "%")
            + " boost_mode=" + dash(boostModeName(boostMode));
      return of(scheme == null ? NONE : profile.toString(), detail);
   }

   /** A GUID in Windows' in-memory layout (Data1-3 little-endian) from its text form. */
   static byte[] guidBytes(String guid) {
      String h = guid.replace("-", "");
      byte[] b = new byte[16];
      for (int i = 0; i < 16; i++) {
         b[i] = (byte)Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
      }
      byte[] out = b.clone();
      out[0] = b[3];
      out[1] = b[2];
      out[2] = b[1];
      out[3] = b[0];
      out[4] = b[5];
      out[5] = b[4];
      out[6] = b[7];
      out[7] = b[6];
      return out;
   }

   static String guidString(byte[] g) {
      return String.format(Locale.ROOT, "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x", g[3], g[2], g[1], g[0], g[5], g[4], g[7], g[6],
            g[8], g[9], g[10], g[11], g[12], g[13], g[14], g[15]);
   }

   private static Map<String, String> windows() {
      String scheme = null, friendly = null, overlay = null;
      Boolean ac = null, saver = null;
      long min = -1, max = -1, boost = -1;
      try (Arena a = Arena.ofConfined()) {
         Linker l = Linker.nativeLinker();
         SymbolLookup pp = SymbolLookup.libraryLookup("powrprof.dll", a);
         SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32.dll", a);
         ValueLayout.OfInt I = ValueLayout.JAVA_INT;
         var P = ValueLayout.ADDRESS;
         try {
            MethodHandle status = l.downcallHandle(k32.find("GetSystemPowerStatus").orElseThrow(), FunctionDescriptor.of(I, P));
            MemorySegment s = a.allocate(12);
            if ((int)status.invoke(s) != 0) {
               int line = s.get(ValueLayout.JAVA_BYTE, 0) & 0xff; // ACLineStatus: 0 offline, 1 online, 255 unknown
               ac = line == 1 ? Boolean.TRUE : line == 0 ? Boolean.FALSE : null;
               saver = (s.get(ValueLayout.JAVA_BYTE, 3) & 1) != 0; // SystemStatusFlag: battery saver on
            }
         } catch (Throwable t) {
            // no status: "-"
         }
         MemorySegment schemeSeg = null;
         try {
            MethodHandle active = l.downcallHandle(pp.find("PowerGetActiveScheme").orElseThrow(), FunctionDescriptor.of(I, P, P));
            MethodHandle free = l.downcallHandle(k32.find("LocalFree").orElseThrow(), FunctionDescriptor.of(P, P));
            MemorySegment out = a.allocate(P);
            if ((int)active.invoke(MemorySegment.NULL, out) == 0) {
               MemorySegment g = out.get(P, 0).reinterpret(16);
               schemeSeg = a.allocate(16);
               schemeSeg.copyFrom(g);
               scheme = guidString(schemeSeg.toArray(ValueLayout.JAVA_BYTE));
               MemorySegment ignored = (MemorySegment)free.invoke(out.get(P, 0));
            }
         } catch (Throwable t) {
            // no scheme
         }
         if (schemeSeg != null) {
            try {
               MethodHandle name = l.downcallHandle(pp.find("PowerReadFriendlyName").orElseThrow(), FunctionDescriptor.of(I, P, P, P, P, P, P));
               MemorySegment buf = a.allocate(1024), size = a.allocate(I);
               size.set(I, 0, 1024);
               if ((int)name.invoke(MemorySegment.NULL, schemeSeg, MemorySegment.NULL, MemorySegment.NULL, buf, size) == 0) {
                  int n = Math.min(1024, size.get(I, 0));
                  String s = new String(buf.asSlice(0, n).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_16LE);
                  int z = s.indexOf('\0');
                  friendly = (z >= 0 ? s.substring(0, z) : s).trim();
               }
            } catch (Throwable t) {
               // the GUID's built-in name, or the GUID
            }
            try {
               MethodHandle read = l.downcallHandle(pp.find(Boolean.FALSE.equals(ac) ? "PowerReadDCValueIndex" : "PowerReadACValueIndex").orElseThrow(),
                     FunctionDescriptor.of(I, P, P, P, P, P));
               MemorySegment sub = a.allocateFrom(ValueLayout.JAVA_BYTE, guidBytes(GUID_PROCESSOR_SUBGROUP)), v = a.allocate(I);
               long[] got = new long[3];
               String[] settings = {GUID_THROTTLE_MIN, GUID_THROTTLE_MAX, GUID_BOOST_MODE};
               for (int i = 0; i < 3; i++) {
                  v.set(I, 0, 0);
                  got[i] = (int)read.invoke(MemorySegment.NULL, schemeSeg, sub, a.allocateFrom(ValueLayout.JAVA_BYTE, guidBytes(settings[i])), v) == 0
                        ? Integer.toUnsignedLong(v.get(I, 0)) : -1;
               }
               min = got[0];
               max = got[1];
               boost = got[2];
            } catch (Throwable t) {
               // no processor settings
            }
         }
         try { // Windows 10 1709+: the power mode slider's overlay scheme in effect
            MethodHandle eff = l.downcallHandle(pp.find("PowerGetEffectiveOverlayScheme").orElseThrow(), FunctionDescriptor.of(I, P));
            MemorySegment g = a.allocate(16);
            if ((int)eff.invoke(g) == 0) {
               overlay = guidString(g.toArray(ValueLayout.JAVA_BYTE));
            }
         } catch (Throwable t) {
            // older Windows: no power mode
         }
      } catch (Throwable t) {
         Log.info("benchmark: power state (Windows): " + t);
      }
      return windowsFields(scheme, friendly, overlay, ac, saver, min, max, boost);
   }

   // ------------------------------------------------------------------ macOS

   /** NSProcessInfo.thermalState's values. */
   static String thermalName(long v) {
      return switch ((int)v) {
         case 0 -> "nominal";
         case 1 -> "fair";
         case 2 -> "serious";
         case 3 -> "critical";
         default -> null;
      };
   }

   /** {@code pmset -g}'s value of {@code key} (a "key  value" line; null without one). */
   static String pmsetValue(String out, String key) {
      if (out == null) {
         return null;
      }
      for (String l : out.split("\n")) {
         String[] c = l.trim().split("\\s+");
         if (c.length >= 2 && c[0].equals(key)) {
            return c[1];
         }
      }
      return null;
   }

   /**
    * The two fields from what macOS reported (null = not read): Low Power Mode (NSProcessInfo, else pmset's lowpowermode),
    * pmset's powermode (0 automatic, 1 low, 2 high power), the providing power source, the thermal state.
    */
   static Map<String, String> macFields(Boolean lowPower, String pmsetOut, String source, String thermal) {
      String lpm = pmsetValue(pmsetOut, "lowpowermode"), pm = pmsetValue(pmsetOut, "powermode");
      Boolean low = lowPower != null ? lowPower : lpm != null ? Boolean.valueOf("1".equals(lpm)) : null;
      String profile;
      if (Boolean.TRUE.equals(low) || "1".equals(pm)) {
         profile = "low-power";
      } else if ("2".equals(pm)) {
         profile = "high-power";
      } else if (low != null || pm != null) {
         profile = "automatic";
      } else {
         profile = NONE;
      }
      String src = source == null ? null : source.toLowerCase(Locale.ROOT).startsWith("ac") ? "ac"
            : source.toLowerCase(Locale.ROOT).startsWith("battery") ? "battery" : source;
      String detail = "source=" + dash(src) + " thermal=" + dash(thermal) + " lowpowermode=" + (low == null ? NONE : low ? "1" : "0")
            + " powermode=" + dash(pm);
      return of(profile, detail);
   }

   private static Map<String, String> mac() {
      Boolean low = null;
      String thermal = null, source = null, pmset = null;
      Linker l = Linker.nativeLinker();
      var P = ValueLayout.ADDRESS;
      try {
         Arena g = Arena.global();
         SymbolLookup objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", g);
         SymbolLookup.libraryLookup("/System/Library/Frameworks/Foundation.framework/Foundation", g);
         MethodHandle getClass = l.downcallHandle(objc.find("objc_getClass").orElseThrow(), FunctionDescriptor.of(P, P));
         MethodHandle sel = l.downcallHandle(objc.find("sel_registerName").orElseThrow(), FunctionDescriptor.of(P, P));
         MemorySegment send = objc.find("objc_msgSend").orElseThrow();
         MethodHandle sendP = l.downcallHandle(send, FunctionDescriptor.of(P, P, P));
         MethodHandle sendL = l.downcallHandle(send, FunctionDescriptor.of(ValueLayout.JAVA_LONG, P, P));
         MethodHandle sendB = l.downcallHandle(send, FunctionDescriptor.of(ValueLayout.JAVA_BYTE, P, P));
         MethodHandle sendBP = l.downcallHandle(send, FunctionDescriptor.of(ValueLayout.JAVA_BYTE, P, P, P));
         try (Arena a = Arena.ofConfined()) {
            MemorySegment cls = (MemorySegment)getClass.invoke(a.allocateFrom("NSProcessInfo"));
            MemorySegment info = (MemorySegment)sendP.invoke(cls, (MemorySegment)sel.invoke(a.allocateFrom("processInfo")));
            if (!info.equals(MemorySegment.NULL)) {
               MemorySegment responds = (MemorySegment)sel.invoke(a.allocateFrom("respondsToSelector:"));
               MemorySegment thermalSel = (MemorySegment)sel.invoke(a.allocateFrom("thermalState"));
               if ((byte)sendBP.invoke(info, responds, thermalSel) != 0) {
                  thermal = thermalName((long)sendL.invoke(info, thermalSel));
               }
               MemorySegment lowSel = (MemorySegment)sel.invoke(a.allocateFrom("isLowPowerModeEnabled")); // macOS 12+
               if ((byte)sendBP.invoke(info, responds, lowSel) != 0) {
                  low = (byte)sendB.invoke(info, lowSel) != 0;
               }
            }
         }
      } catch (Throwable t) {
         Log.info("benchmark: power state (NSProcessInfo): " + t);
      }
      try (Arena a = Arena.ofConfined()) {
         Arena g = Arena.global();
         SymbolLookup iokit = SymbolLookup.libraryLookup("/System/Library/Frameworks/IOKit.framework/IOKit", g);
         SymbolLookup cf = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", g);
         MethodHandle copyInfo = l.downcallHandle(iokit.find("IOPSCopyPowerSourcesInfo").orElseThrow(), FunctionDescriptor.of(P));
         MethodHandle providing = l.downcallHandle(iokit.find("IOPSGetProvidingPowerSourceType").orElseThrow(), FunctionDescriptor.of(P, P));
         MethodHandle cstr = l.downcallHandle(cf.find("CFStringGetCString").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_BYTE, P, P, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
         MethodHandle release = l.downcallHandle(cf.find("CFRelease").orElseThrow(), FunctionDescriptor.ofVoid(P));
         MemorySegment blob = (MemorySegment)copyInfo.invoke();
         if (!blob.equals(MemorySegment.NULL)) {
            try {
               MemorySegment type = (MemorySegment)providing.invoke(blob); // Get rule: not ours to release
               if (!type.equals(MemorySegment.NULL)) {
                  MemorySegment buf = a.allocate(128);
                  if ((byte)cstr.invoke(type, buf, 128L, 0x08000100) != 0) { // kCFStringEncodingUTF8
                     source = buf.getString(0);
                  }
               }
            } finally {
               release.invoke(blob);
            }
         }
      } catch (Throwable t) {
         Log.info("benchmark: power state (IOKit): " + t);
      }
      try { // the power mode (High Power Mode machines) only pmset reports; off the game thread, no window on macOS
         Process p = new ProcessBuilder("pmset", "-g").redirectErrorStream(true).start();
         byte[] out = p.getInputStream().readAllBytes();
         if (p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
            pmset = new String(out, StandardCharsets.UTF_8);
         } else {
            p.destroy();
         }
      } catch (Throwable t) {
         // no pmset
      }
      return macFields(low, pmset, source, thermal);
   }
}
