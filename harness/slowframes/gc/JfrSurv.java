// JfrSurv <pzopt.jfr> <fromEpochMs> <toEpochMs> [depth=6] [top=30]
// Route-window allocation and survivor profile of a JFR recording:
//  1. jdk.ObjectAllocationSample (weight = bytes) per thread group, per class, per allocation path (first non-JDK frames),
//  2. jdk.PromoteObjectInNewPLAB / OutsidePLAB per class (PLAB size / object size as weight; the first PLAB of each GC worker
//     per GC skipped: those start on the roots (sleeping / waiting threads' objects) and over-count them),
//  3. jdk.OldObjectSample allocated inside the window (objects still alive at the dump): per allocation path (count).
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import jdk.jfr.consumer.*;

public class JfrSurv {
   static String group(String t) {
      if (t == null) return "?";
      if (t.startsWith("pzopt-frame")) return "frame-workers";
      if (t.startsWith("[tid=") || t.startsWith("GC Thread")) return t.replaceAll("[#\\d]+", "*");
      return t.replaceAll("[-_#]?\\d+$", "*");
   }

   static String path(RecordedStackTrace st, int depth) {
      if (st == null) return "-";
      List<RecordedFrame> fr = st.getFrames();
      StringBuilder sb = new StringBuilder();
      int n = 0;
      for (RecordedFrame f : fr) { // leaf first
         RecordedMethod m = f.getMethod();
         if (m == null) continue;
         String cls = m.getType() == null ? "" : m.getType().getName();
         if (n == 0 && (cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("sun."))) continue;
         String s = cls.substring(cls.lastIndexOf('.') + 1) + "." + m.getName();
         sb.append(n == 0 ? "" : " < ").append(s);
         if (++n >= depth) break;
      }
      return sb.toString();
   }

   static void top(String title, Map<String, Long> m, double div, String unit, int top) {
      long tot = 0;
      for (long v : m.values()) tot += v;
      System.out.printf("== %s (total %.1f %s)%n", title, tot / div, unit);
      m.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(top)
            .forEach(e -> System.out.printf("  %7.2f %s  %s%n", e.getValue() / div, unit, e.getKey().length() > 250 ? e.getKey().substring(0, 250) : e.getKey()));
   }

   public static void main(String[] a) throws Exception {
      long from = Long.parseLong(a[1]) * 1_000_000L, to = Long.parseLong(a[2]) * 1_000_000L;
      int depth = a.length > 3 ? Integer.parseInt(a[3]) : 6, topN = a.length > 4 ? Integer.parseInt(a[4]) : 30;
      double secs = (to - from) / 1e9;
      Map<String, Long> allocThread = new HashMap<>(), allocClass = new HashMap<>(), allocPath = new HashMap<>();
      Map<String, Long> promo = new HashMap<>(), promoOld = new HashMap<>(), oldPath = new HashMap<>(), oldClass = new HashMap<>();
      Set<String> firstSeen = new HashSet<>();
      long promoTotal = 0, nOld = 0, nOldAll = 0;
      Map<String, Long> ccache = new TreeMap<>();
      try (RecordingFile rf = new RecordingFile(Path.of(a[0]))) {
         while (rf.hasMoreEvents()) {
            RecordedEvent e = rf.readEvent();
            String type = e.getEventType().getName();
            long t = ns(e.getStartTime());
            switch (type) {
               case "jdk.ObjectAllocationSample" -> {
                  if (t < from || t > to) continue;
                  long w = e.getLong("weight");
                  RecordedThread th = e.getThread("eventThread");
                  String g = group(th == null ? null : th.getJavaName() != null ? th.getJavaName() : th.getOSName());
                  RecordedClass c = e.getClass("objectClass");
                  String cn = c == null ? "?" : c.getName();
                  allocThread.merge(g, w, Long::sum);
                  allocClass.merge(cn, w, Long::sum);
                  allocPath.merge("[" + g + "] " + cn + "  <  " + path(e.getStackTrace(), depth), w, Long::sum);
               }
               case "jdk.PromoteObjectInNewPLAB", "jdk.PromoteObjectOutsidePLAB" -> {
                  if (t < from || t > to) continue;
                  boolean outside = type.endsWith("OutsidePLAB");
                  RecordedThread th = e.getThread("eventThread");
                  String key = e.getValue("gcId") + "/" + (th == null ? "?" : th.getOSName()) + "/" + e.getBoolean("tenured");
                  long w = outside ? e.getLong("objectSize") : e.getLong("plabSize");
                  if (!outside && firstSeen.add(key)) continue;
                  RecordedClass c = e.getClass("objectClass");
                  String cn = c == null ? "?" : c.getName();
                  promo.merge(cn, w, Long::sum);
                  promoTotal += w;
               }
               case "jdk.OldObjectSample" -> {
                  nOldAll++;
                  long at = ns(e.getInstant("allocationTime"));
                  if (at < from || at > to) continue;
                  nOld++;
                  RecordedObject o = e.getValue("object");
                  String cn = "?";
                  if (o != null && o.hasField("type") && o.getValue("type") != null) cn = ((RecordedClass) o.getValue("type")).getName();
                  oldClass.merge(cn, 1L, Long::sum);
                  oldPath.merge(cn + "  <  " + path(e.getStackTrace(), depth), 1L, Long::sum);
               }
               case "jdk.CodeCacheStatistics" -> {
                  ccache.put(String.format("%d %s", t / 1_000_000_000L, e.getString("codeBlobType")), e.getLong("unallocatedCapacity"));
               }
               default -> {
               }
            }
         }
      }
      System.out.printf("window %.1f s%n", secs);
      top("allocation by thread group (MB/s)", allocThread, 1e6 * secs, "MB/s", 15);
      top("allocation by class (MB/s)", allocClass, 1e6 * secs, "MB/s", topN);
      top("allocation by path (MB/s)", allocPath, 1e6 * secs, "MB/s", topN * 2);
      top("copied by GC (promotion events, MB in window)", promo, 1e6, "MB", topN);
      System.out.printf("old-object samples: %d in the window of %d%n", nOld, nOldAll);
      top("still alive at the dump, allocated in the window: class (samples)", oldClass, 1, "", topN);
      top("still alive at the dump, allocated in the window: path (samples)", oldPath, 1, "", topN);
      int i = 0;
      for (var en : ccache.entrySet()) if (i++ % 6 == 0) System.out.println("codecache " + en.getKey() + " unallocated " + en.getValue() / 1048576 + " MB");
   }

   static long ns(Instant i) {
      return i.getEpochSecond() * 1_000_000_000L + i.getNano();
   }
}
