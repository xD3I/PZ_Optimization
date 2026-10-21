package pzopt;

import java.io.File;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/**
 * The CPU's physical cores and whether its cores are of two kinds (f8-flip, 2026-10-15), read once from Linux sysfs when
 * {@link Config} loads; anywhere else (Windows, macOS) or on any read error it answers "unknown" and the callers keep their
 * old defaults. No logging here: Config loads before the logger.
 *
 * <p>Used for the default of {@code frameThreads}: the flip (Ryzen AI 9 HX 370, 4 Zen 5 + 8 Zen 5c cores, 24 threads) ran its
 * frame batches 0.64 / 0.75 ms a frame faster with 11 workers than with 8 (two in-run A/Bs on the Louisville horde, runs
 * f8f-thr11v8 / f8f-thr11v8b), and 16 were no better than 11 (f8f-thr16v11).
 */
public final class CpuTopology {
   private CpuTopology() {
   }

   /** Physical cores (distinct package + core id pairs), 0 when unknown. */
   public static final int PHYSICAL_CORES;
   /** The cores are of two kinds (boost ranking / capacity spread over 15 %, or Intel's cpu_atom PMU). */
   public static final boolean HYBRID;

   static {
      int phys = 0;
      boolean hybrid = false;
      try {
         File[] cpus = new File("/sys/devices/system/cpu").listFiles((d, name) -> name.matches("cpu\\d+"));
         if (cpus != null && cpus.length > 0) {
            Set<Long> cores = new HashSet<>();
            long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
            boolean complete = true;
            for (File c : cpus) {
               long core = read(c, "topology/core_id");
               long pkg = Math.max(0L, read(c, "topology/physical_package_id"));
               if (core < 0) {
                  complete = false;
                  break;
               }
               cores.add(pkg << 32 | core);
               long v = read(c, "cpufreq/amd_pstate_highest_perf");
               if (v < 0) {
                  v = read(c, "acpi_cppc/highest_perf");
               }
               if (v < 0) {
                  v = read(c, "cpu_capacity");
               }
               if (v < 0) {
                  v = read(c, "cpufreq/cpuinfo_max_freq");
               }
               if (v > 0) {
                  lo = Math.min(lo, v);
                  hi = Math.max(hi, v);
               }
            }
            if (complete) {
               phys = cores.size();
               hybrid = new File("/sys/devices/cpu_atom/cpus").isFile() || lo != Long.MAX_VALUE && hi >= lo * 1.15;
            }
         }
      } catch (Throwable t) {
         phys = 0;
         hybrid = false;
      }
      PHYSICAL_CORES = phys;
      HYBRID = hybrid;
   }

   /**
    * The default number of frame workers: 8 (the value every machine had before), except on a hybrid CPU with more than nine
    * physical cores, where one per physical core besides the game thread's, at most 11 (the measured optimum on the flip).
    */
   public static int frameThreadsDefault() {
      if (HYBRID && PHYSICAL_CORES > 9) {
         return Math.min(11, PHYSICAL_CORES - 1);
      }
      return 8;
   }

   private static long read(File dir, String rel) {
      try {
         return Long.parseLong(Files.readString(new File(dir, rel).toPath()).trim());
      } catch (Exception e) {
         return -1L;
      }
   }
}
