package pzopt;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

/**
 * The GPU columns of harness/sysmon.sh read from inside the game for the built-in benchmark (2026-10-21, BenchCapture):
 * busy %, SM and memory clocks, temperature and VRAM used. The same sources in the same order as sysmon.sh, without
 * running a program: NVIDIA through the driver's own NVML library (nvidia-smi's library; Linux and Windows, called
 * through the JDK's foreign-function linker like pzopt.Power's board power), else the first amdgpu card with a busy
 * counter (Linux sysfs: gpu_busy_percent, hwmon freq1 / temp1, pp_dpm_mclk, mem_info_vram_used). Anything else (an
 * AMD or Intel GPU on Windows, macOS) reads nothing: the columns stay empty and the report says why. Called from the
 * benchmark's sampler thread only, twice a second.
 */
final class BenchGpu {
   /** {gpu %, SM MHz, memory MHz, °C, VRAM MiB}; NaN = not readable here. */
   static final int BUSY = 0, SM = 1, MEM = 2, TEMP = 3, VRAM = 4;

   private static boolean discovered;
   private static String source = "none";
   // NVML (device 0: nvidia-smi's first line, what sysmon.sh reads)
   private static MethodHandle clock, temp, memInfo, util;
   private static MemorySegment dev, outInt, outMem;
   // amdgpu sysfs
   private static String amdDev, amdHwmon;

   private BenchGpu() {
   }

   /** Where the readings come from: "NVML", "amdgpu <card>" or "none". */
   static synchronized String source() {
      discover();
      return source;
   }

   static synchronized double[] read() {
      discover();
      double[] v = {Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN};
      try {
         if (dev != null) {
            if ((int)clock.invokeExact(dev, 1, outInt) == 0) { // NVML_CLOCK_SM
               v[SM] = Integer.toUnsignedLong(outInt.get(ValueLayout.JAVA_INT, 0));
            }
            if ((int)clock.invokeExact(dev, 2, outInt) == 0) { // NVML_CLOCK_MEM
               v[MEM] = Integer.toUnsignedLong(outInt.get(ValueLayout.JAVA_INT, 0));
            }
            if ((int)temp.invokeExact(dev, 0, outInt) == 0) { // NVML_TEMPERATURE_GPU
               v[TEMP] = Integer.toUnsignedLong(outInt.get(ValueLayout.JAVA_INT, 0));
            }
            if ((int)memInfo.invokeExact(dev, outMem) == 0) { // nvmlMemory_t {total, free, used}
               v[VRAM] = outMem.get(ValueLayout.JAVA_LONG, 16) / 1048576.0;
            }
            if ((int)util.invokeExact(dev, outMem) == 0) { // nvmlUtilization_t {gpu, memory}
               v[BUSY] = Integer.toUnsignedLong(outMem.get(ValueLayout.JAVA_INT, 0));
            }
         } else if (amdDev != null) {
            v[BUSY] = num(BenchProc.read(amdDev + "/gpu_busy_percent"), 1);
            if (amdHwmon != null) {
               v[SM] = num(BenchProc.read(amdHwmon + "/freq1_input"), 1e6);
               v[TEMP] = num(BenchProc.read(amdHwmon + "/temp1_input"), 1000);
            }
            v[MEM] = BenchProc.dpmMhz(BenchProc.read(amdDev + "/pp_dpm_mclk"));
            v[VRAM] = num(BenchProc.read(amdDev + "/mem_info_vram_used"), 1048576);
         }
      } catch (Throwable t) {
         Log.info("benchmark: GPU sensors stopped: " + t);
         dev = null;
         amdDev = null;
         source = "none";
      }
      return v;
   }

   private static double num(String s, double div) {
      if (s == null || s.isBlank()) {
         return Double.NaN;
      }
      try {
         return Double.parseDouble(s.trim()) / div;
      } catch (NumberFormatException e) {
         return Double.NaN;
      }
   }

   private static void discover() {
      if (discovered) {
         return;
      }
      discovered = true;
      String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
      if (os.contains("win") || os.contains("linux")) {
         nvml(os.contains("win") ? "nvml.dll" : "libnvidia-ml.so.1");
      }
      if (dev == null && os.contains("linux")) {
         File[] cards = new File("/sys/class/drm").listFiles();
         if (cards != null) {
            java.util.Arrays.sort(cards);
            for (File c : cards) {
               String d = c.getPath() + "/device";
               if (!c.getName().matches("card\\d+") || !new File(d, "gpu_busy_percent").canRead()) {
                  continue;
               }
               amdDev = d;
               File[] hw = new File(d, "hwmon").listFiles();
               if (hw != null && hw.length > 0) {
                  java.util.Arrays.sort(hw);
                  amdHwmon = hw[0].getPath();
               }
               source = "amdgpu " + c.getName();
               break;
            }
         }
      }
      Log.info("benchmark: GPU sensors " + source);
   }

   private static void nvml(String name) {
      SymbolLookup lib;
      try {
         lib = SymbolLookup.libraryLookup(name, Arena.global());
      } catch (IllegalArgumentException e) {
         return; // not an NVIDIA driver
      }
      try {
         Linker linker = Linker.nativeLinker();
         MethodHandle init = linker.downcallHandle(lib.find("nvmlInit_v2").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
         MethodHandle byIndex = linker.downcallHandle(lib.find("nvmlDeviceGetHandleByIndex_v2").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
         FunctionDescriptor typed = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
         FunctionDescriptor plain = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
         clock = linker.downcallHandle(lib.find("nvmlDeviceGetClockInfo").orElseThrow(), typed);
         temp = linker.downcallHandle(lib.find("nvmlDeviceGetTemperature").orElseThrow(), typed);
         memInfo = linker.downcallHandle(lib.find("nvmlDeviceGetMemoryInfo").orElseThrow(), plain);
         util = linker.downcallHandle(lib.find("nvmlDeviceGetUtilizationRates").orElseThrow(), plain);
         if ((int)init.invokeExact() != 0) {
            return;
         }
         Arena a = Arena.global();
         MemorySegment h = a.allocate(ValueLayout.ADDRESS);
         if ((int)byIndex.invokeExact(0, h) != 0) {
            return;
         }
         outInt = a.allocate(ValueLayout.JAVA_INT);
         outMem = a.allocate(24, 8);
         dev = h.get(ValueLayout.ADDRESS, 0);
         source = "NVML";
      } catch (Throwable t) {
         Log.info("benchmark: NVML unusable: " + t);
         dev = null;
      }
   }
}
