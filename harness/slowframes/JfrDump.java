// Dumps the samples of an async-profiler / JFR recording for a few threads, compactly, for harness/slowframes.py.
//
//   java harness/slowframes/JfrDump.java <asprof.jfr> <out.txt> [threads=MainThread,main] [fromEpochMs] [toEpochMs]
//
// Output lines:
//   s <id> <root;...;leaf>                         a stack (root first, Class.method; native frames as they come)
//   x <kind> <thread> <epochNs> <stackId> <weight>  one sample: kind c = CPU (jdk.ExecutionSample), w = wall
//                                                  (profiler.WallClockSample, weight = samples batched), a = allocation
//                                                  (jdk.ObjectAllocationSample / profiler alloc, weight = bytes)
//   g <kind> <epochNs> <durUs> <name>              JVM GC pause / ZGC allocation stall / safepoint when the recording has them
// Tail pass 2026-10-13.
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import jdk.jfr.consumer.*;

public class JfrDump {
   public static void main(String[] a) throws Exception {
      Path in = Path.of(a[0]);
      Set<String> want = new HashSet<>(Arrays.asList((a.length > 2 ? a[2] : "MainThread,main").split(",")));
      long from = a.length > 3 ? Long.parseLong(a[3]) * 1_000_000L : Long.MIN_VALUE;
      long to = a.length > 4 ? Long.parseLong(a[4]) * 1_000_000L : Long.MAX_VALUE;
      Map<String, Integer> ids = new HashMap<>();
      long n = 0;
      try (RecordingFile rf = new RecordingFile(in);
            BufferedWriter out = Files.newBufferedWriter(Path.of(a[1]))) {
         while (rf.hasMoreEvents()) {
            RecordedEvent e = rf.readEvent();
            String type = e.getEventType().getName();
            long t = ns(e.getStartTime());
            if (t < from || t > to) {
               continue;
            }
            switch (type) {
               case "jdk.GCPhasePause", "jdk.SafepointBegin", "jdk.ZAllocationStall", "jdk.GarbageCollection" -> {
                  out.write("g " + type.substring(4) + " " + t + " " + e.getDuration().toNanos() / 1000 + " "
                        + (e.hasField("name") ? String.valueOf((Object) e.getValue("name")).replace(' ', '_') : "-") + "\n");
                  continue;
               }
               default -> {
               }
            }
            String kind;
            long weight = 1;
            if (type.equals("jdk.ExecutionSample")) {
               kind = "c";
            } else if (type.equals("jdk.NativeMethodSample")) {
               kind = "n"; // a thread inside a native method (JFR's own sampler): where a blocked render thread waits
            } else if (type.equals("profiler.WallClockSample")) {
               kind = "w";
               if (e.hasField("samples")) {
                  weight = ((Number) e.getValue("samples")).longValue();
               }
            } else if (type.equals("jdk.ObjectAllocationSample") || type.equals("jdk.ObjectAllocationInNewTLAB")
                  || type.equals("jdk.ObjectAllocationOutsideTLAB")) {
               kind = "a";
               for (String f : new String[]{"weight", "allocationSize", "tlabSize"}) {
                  if (e.hasField(f)) {
                     weight = ((Number) e.getValue(f)).longValue();
                     break;
                  }
               }
            } else {
               continue;
            }
            RecordedThread th = e.hasField("sampledThread") ? e.getThread("sampledThread") : e.getThread("eventThread");
            String name = th == null ? "?" : th.getJavaName() != null ? th.getJavaName() : th.getOSName();
            if (name == null || !want.contains(name) && !want.contains("*")) {
               continue;
            }
            RecordedStackTrace st = e.getStackTrace();
            StringBuilder sb = new StringBuilder(1024);
            if (st != null) {
               List<RecordedFrame> fr = st.getFrames();
               for (int i = fr.size() - 1; i >= 0; i--) {
                  RecordedMethod m = fr.get(i).getMethod();
                  if (m == null) {
                     continue;
                  }
                  if (sb.length() > 0) {
                     sb.append(';');
                  }
                  String cls = m.getType() == null ? "" : m.getType().getName();
                  sb.append(cls.isEmpty() ? "" : cls + ".").append(m.getName().replace(' ', '_'));
               }
               if (kind.equals("a") && e.hasField("objectClass") && e.getValue("objectClass") != null) {
                  sb.append(";[alloc ").append(((RecordedClass) e.getValue("objectClass")).getName().replace(' ', '_')).append(']');
               }
            }
            String s = sb.toString();
            Integer id = ids.get(s);
            if (id == null) {
               id = ids.size();
               ids.put(s, id);
               out.write("s " + id + " " + (s.isEmpty() ? "-" : s) + "\n");
            }
            out.write("x " + kind + " " + name.replace(' ', '_') + " " + t + " " + id + " " + weight + "\n");
            n++;
         }
      }
      System.err.println("jfrdump: " + n + " samples, " + ids.size() + " stacks");
   }

   static long ns(Instant i) {
      return i.getEpochSecond() * 1_000_000_000L + i.getNano();
   }
}
