package pzopt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The text formats the benchmark's in-game samplers read (2026-10-21, BenchCapture): Linux's /proc files, parsed the
 * way harness/sysmon.sh and harness/schedmon.py parse them, so a pass's numbers mean what a harness run's mean. Pure
 * functions over strings (the tests feed them fixtures); {@link #read} is the one file access, a small read of a
 * /proc or /sys file that returns null when it is missing or not readable.
 */
final class BenchProc {
   private BenchProc() {
   }

   /** A small text file (procfs / sysfs) in full, or null. */
   static String read(String path) {
      try {
         return new String(Files.readAllBytes(Path.of(path)), StandardCharsets.US_ASCII);
      } catch (IOException | RuntimeException e) {
         return null;
      }
   }

   /**
    * /proc/stat's cpu lines as {busy, total} jiffies, the aggregate first, then cpu0, cpu1 ... (sysmon.sh's read_cpus:
    * idle = idle + iowait, total = every field).
    */
   static long[][] cpuTimes(String stat) {
      if (stat == null) {
         return null;
      }
      java.util.ArrayList<long[]> out = new java.util.ArrayList<>();
      int i = 0;
      while (i < stat.length()) {
         int end = stat.indexOf('\n', i);
         if (end < 0) {
            end = stat.length();
         }
         if (stat.startsWith("cpu", i)) {
            String[] f = stat.substring(i, end).trim().split("\\s+");
            long tot = 0, idle = 0;
            for (int k = 1; k < f.length; k++) {
               long v = parseLong(f[k]);
               tot += v;
               if (k == 4 || k == 5) {
                  idle += v;
               }
            }
            out.add(new long[] {tot - idle, tot});
         } else if (!out.isEmpty()) {
            break; // (the cpu lines come first)
         }
         i = end + 1;
      }
      return out.isEmpty() ? null : out.toArray(new long[0][]);
   }

   /** {machine busy %, busiest logical CPU %} between two cpuTimes readings (sysmon's cpu_pct, cpu_busiest_core_pct). */
   static double[] cpuLoad(long[][] prev, long[][] cur) {
      if (prev == null || cur == null || prev.length != cur.length) {
         return new double[] {Double.NaN, Double.NaN};
      }
      double agg = Double.NaN, mx = Double.NaN;
      for (int i = 0; i < cur.length; i++) {
         long db = cur[i][0] - prev[i][0], dt = cur[i][1] - prev[i][1];
         double p = dt > 0 ? 100.0 * db / dt : 0;
         if (i == 0) {
            agg = p;
         } else if (Double.isNaN(mx) || p > mx) {
            mx = p;
         }
      }
      return new double[] {agg, mx};
   }

   /** The first number after a key at the start of a line ("ctxt 123", "pswpin 4", "VmRSS:   123 kB"), or -1. */
   static long field(String text, String key) {
      if (text == null) {
         return -1;
      }
      int from = 0;
      while (true) {
         int at = text.indexOf(key, from);
         if (at < 0) {
            return -1;
         }
         int j = at + key.length();
         if ((at == 0 || text.charAt(at - 1) == '\n') && j < text.length() && (text.charAt(j) == ' ' || text.charAt(j) == '\t' || text.charAt(j) == ':')) {
            while (j < text.length() && (text.charAt(j) == ' ' || text.charAt(j) == '\t' || text.charAt(j) == ':')) {
               j++;
            }
            int s = j;
            while (j < text.length() && Character.isDigit(text.charAt(j))) {
               j++;
            }
            return j > s ? parseLong(text.substring(s, j)) : -1;
         }
         from = at + 1;
      }
   }

   /** A /proc/pressure/* file's {some total us, full total us} (schedmon.py's psi(); a missing line is 0). */
   static long[] psi(String text) {
      long some = 0, full = 0;
      if (text == null) {
         return null;
      }
      for (String line : text.split("\n")) {
         int t = line.indexOf("total=");
         if (t < 0) {
            continue;
         }
         long v = parseLong(line.substring(t + 6).trim().split("\\s+")[0]);
         if (line.startsWith("some")) {
            some = v;
         } else if (line.startsWith("full")) {
            full = v;
         }
      }
      return new long[] {some, full};
   }

   /** A task's schedstat: {ns on the CPU, ns runnable waiting for a CPU, times run}, or null. */
   static long[] schedstat(String text) {
      if (text == null) {
         return null;
      }
      String[] f = text.trim().split("\\s+");
      if (f.length < 3) {
         return null;
      }
      return new long[] {parseLong(f[0]), parseLong(f[1]), parseLong(f[2])};
   }

   /** A thread name as schedmon writes it (spaces to underscores; the kernel's comm is 15 characters). */
   static String comm(String text) {
      return text == null ? "?" : text.trim().replace(' ', '_');
   }

   /**
    * /proc/self/status's resident memory by kind, in MiB: anon (Java heap + native malloc), file (mapped files, the
    * libraries and the driver's file-backed mappings included), shmem, total (VmRSS), swap. The counters the kernel
    * keeps per process: reading them walks no page table (smaps' per-mapping split does, under the process's mm lock).
    */
   static Map<String, Double> memory(String status) {
      Map<String, Double> m = new java.util.LinkedHashMap<>();
      if (status == null) {
         return m;
      }
      String[][] keys = {{"RssAnon", "anon_mib"}, {"RssFile", "file_mib"}, {"RssShmem", "shmem_mib"}, {"VmRSS", "total_mib"}, {"VmSwap", "swap_mib"}};
      for (String[] k : keys) {
         long kb = field(status, k[0]);
         if (kb >= 0) {
            m.put(k[1], kb / 1024.0);
         }
      }
      return m;
   }

   /** The line of a pp_dpm_* file marked current ("1: 2000Mhz *"), in MHz; NaN without one. */
   static double dpmMhz(String text) {
      if (text == null) {
         return Double.NaN;
      }
      for (String line : text.split("\n")) {
         if (line.trim().endsWith("*")) {
            String s = line.substring(line.indexOf(':') + 1).trim().toLowerCase(java.util.Locale.ROOT);
            int e = 0;
            while (e < s.length() && (Character.isDigit(s.charAt(e)) || s.charAt(e) == '.')) {
               e++;
            }
            try {
               double v = Double.parseDouble(s.substring(0, e));
               return s.startsWith("ghz", e) ? v * 1000 : v;
            } catch (NumberFormatException ex) {
               return Double.NaN;
            }
         }
      }
      return Double.NaN;
   }

   /**
    * A console line as harness/grafana/ingest.py's parse_loadtrace reads pzopt-loadtrace.out: {epoch ms, level,
    * category, text}; null for a line without a stamp. "LOG  : General     f:0> text" and "WARN : General ... > text".
    */
   static String[] consoleLine(String line) {
      int tab = line.indexOf('\t');
      if (tab <= 0) {
         return null;
      }
      for (int i = 0; i < tab; i++) {
         if (!Character.isDigit(line.charAt(i))) {
            return null;
         }
      }
      String rest = line.substring(tab + 1);
      // <n>,<cat>,<cat>,"<console line>" (the line quoted CSV-style, "" for a quote)
      int q = rest.indexOf('"');
      String text = rest;
      String cat = "";
      if (q >= 0) {
         String[] pre = rest.substring(0, q).split(",", -1);
         cat = pre.length > 1 ? pre[1] : "";
         text = rest.substring(q + 1);
         if (text.endsWith("\"")) {
            text = text.substring(0, text.length() - 1);
         }
         text = text.replace("\"\"", "\"");
      }
      text = text.replace("%0A", "\n").replace("%09", "\t").replace("%22", "\""); // (DebugLog's recording escapes \n and ")
      String level = "";
      int colon = text.indexOf(':');
      int gt = text.indexOf('>');
      if (colon > 0 && gt > colon) {
         String lv = text.substring(0, colon).trim();
         if (lv.matches("LOG|WARN|ERROR|DEBUG|TRACE|INFO")) {
            level = lv;
            String head = text.substring(colon + 1, gt).trim();
            int sp = head.indexOf(' ');
            cat = sp > 0 ? head.substring(0, sp) : head;
            text = text.substring(gt + 1);
            if (text.startsWith(" ")) {
               text = text.substring(1);
            }
         }
      }
      return new String[] {line.substring(0, tab), level, cat, text.strip()};
   }

   /** Grafana's log level of a console level (ERROR -> error, WARN -> warning, else info). */
   static String logLevel(String level) {
      String l = level.toLowerCase(java.util.Locale.ROOT);
      return l.startsWith("err") ? "error" : l.startsWith("warn") ? "warning" : "info";
   }

   static long parseLong(String s) {
      try {
         return Long.parseLong(s.trim());
      } catch (NumberFormatException e) {
         return 0;
      }
   }
}
