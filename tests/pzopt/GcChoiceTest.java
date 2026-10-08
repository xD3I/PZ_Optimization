package pzopt;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONObject;

/**
 * pzopt.GcChoice's launcher edit: ZGC -> G1 with the marker (top level and per-platform sections), the optional pause
 * target, the undo back to the exact stock arguments, re-applying is a no-op, and a JSON without ZGC or our marker is
 * left alone. The real Windows launcher keeps its collector one level deeper, per OS version (windows."10.0.17134"),
 * which the native launcher appends to the top-level vmArgs: the switch must reach it and leave every launch with one
 * collector. The boot's log line names G1 only when the JSON was switched.
 */
public class GcChoiceTest {
   private static final String STOCK = "{\"mainClass\":\"m\",\"classpath\":[\".\",\"projectzomboid.jar\"],"
         + "\"vmArgs\":[\"-Xmx3072m\",\"-XX:+UseZGC\",\"-XX:-OmitStackTraceInFastThrow\"],"
         + "\"windows\":{\"vmArgs\":[\"-Xmx3072m\",\"-XX:+UseZGC\"]}}";
   /** The structure of the real Windows ProjectZomboid64.json (2026-10-01). */
   private static final String WINDOWS_REAL = "{\"mainClass\":\"zombie/gameStates/MainScreenState\",\"classpath\":[\".\",\"projectzomboid.jar\"],"
         + "\"vmArgs\":[\"-Djava.awt.headless=true\",\"--enable-native-access=ALL-UNNAMED\",\"--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED\","
         + "\"-Xmx12288m\",\"-Dzomboid.steam=1\",\"-Dzomboid.znetlog=1\",\"-Djava.library.path=win64/;.\",\"-XX:-CreateCoredumpOnCrash\","
         + "\"-XX:-OmitStackTraceInFastThrow\",\"-agentlib:zbNative\"],"
         + "\"windows\":{\"6.1\":{\"vmArgs\":[\"-XX:+UseG1GC\"]},\"10.0.17134\":{\"vmArgs\":[\"-XX:+UseZGC\"]}}}";
   private static final String[] COLLECTORS = {"-XX:+UseG1GC", "-XX:+UseZGC", "-XX:+UseParallelGC", "-XX:+UseSerialGC", "-XX:+UseShenandoahGC"};

   public static void main(String[] args) {
      JSONObject stock = new JSONObject(STOCK);

      JSONObject j = new JSONObject(STOCK);
      check(GcChoice.toG1(j, 0), "switches");
      check(j.getJSONArray("vmArgs").toList().contains("-XX:+UseG1GC") && !j.getJSONArray("vmArgs").toList().contains("-XX:+UseZGC"), "top level G1");
      check(j.getJSONArray("vmArgs").toList().contains(GcChoice.MARKER), "marker");
      check(j.getJSONObject("windows").getJSONArray("vmArgs").toList().contains("-XX:+UseG1GC"), "windows section G1");
      check(!j.toString().contains("MaxGCPauseMillis"), "no pause target at 0");
      check(!GcChoice.toG1(j, 0), "second switch is a no-op");
      check(GcChoice.toStock(j), "undo");
      check(j.similar(stock), "undo gives the stock JSON back: " + j);

      JSONObject p = new JSONObject(STOCK);
      GcChoice.toG1(p, 50);
      check(p.getJSONArray("vmArgs").toList().contains("-XX:MaxGCPauseMillis=50"), "pause target added");
      check(p.getJSONArray("vmArgs").toList().contains(GcChoice.MARKER_PAUSE), "pause marker");
      GcChoice.toStock(p);
      check(p.similar(stock), "undo removes the pause target too: " + p);

      JSONObject g1 = new JSONObject(STOCK.replace("-XX:+UseZGC", "-XX:+UseG1GC"));
      String before = g1.toString();
      check(!GcChoice.toG1(g1, 0) && !GcChoice.toStock(g1) && g1.toString().equals(before), "a JSON already on G1 without our marker is left alone");
      // jitSteady: the marker and the trap-limit flags in every section, idempotent, undone exactly, a player's own flag kept
      JSONObject js = new JSONObject(STOCK);
      GcChoice.jitToSteady(js);
      check(js.getJSONArray("vmArgs").toList().contains(GcChoice.JIT_MARKER), "jit marker");
      for (String f : GcChoice.JIT_FLAGS) {
         check(js.getJSONArray("vmArgs").toList().contains(f) && js.getJSONObject("windows").getJSONArray("vmArgs").toList().contains(f), "jit flag " + f);
      }
      String once = js.toString();
      GcChoice.jitToSteady(js);
      check(js.toString().equals(once), "second jitToSteady is a no-op");
      GcChoice.jitToStock(js);
      check(js.similar(stock), "jitToStock gives the stock JSON back: " + js);
      JSONObject own = new JSONObject(STOCK.replace("\"-XX:-OmitStackTraceInFastThrow\"", "\"-XX:-OmitStackTraceInFastThrow\",\"-XX:PerMethodTrapLimit=50\""));
      String ownBefore = own.getJSONArray("vmArgs").toString();
      GcChoice.jitToSteady(own);
      check(own.getJSONArray("vmArgs").toString().equals(ownBefore), "a player's own trap-limit flag: the section is left alone");
      GcChoice.jitToStock(own);
      check(own.getJSONArray("vmArgs").toList().contains("-XX:PerMethodTrapLimit=50"), "undo keeps the player's own flag");
      // gcHeap / gcHeapFixed / gcPreTouch: -Xmx replaced in place, -Xms and pre-touch added, all undone exactly
      JSONObject h = new JSONObject(STOCK);
      GcChoice.heapToPzopt(h, 6144, true, true);
      java.util.List<Object> ha = h.getJSONArray("vmArgs").toList();
      check(ha.get(0).equals("-Xmx6144m") && !ha.contains("-Xmx3072m"), "-Xmx replaced in place: " + ha);
      check(ha.contains("-Xms6144m") && ha.contains("-XX:+AlwaysPreTouch") && ha.contains(GcChoice.HEAP_MARKER + "3072m,none,1"), "-Xms, pre-touch, marker: " + ha);
      check(h.getJSONObject("windows").getJSONArray("vmArgs").toList().contains("-Xmx6144m"), "windows section heap");
      String hOnce = h.toString();
      GcChoice.heapToPzopt(h, 6144, true, true);
      check(h.toString().equals(hOnce), "second heapToPzopt is a no-op");
      GcChoice.heapToStock(h);
      check(h.similar(stock), "heapToStock gives the stock JSON back: " + h);
      JSONObject h0 = new JSONObject(STOCK);
      GcChoice.heapToPzopt(h0, 0, false, false);
      check(h0.similar(stock), "gcHeap=game without fixed / pre-touch leaves the JSON alone");
      GcChoice.heapToPzopt(h0, 0, true, false);
      check(h0.getJSONArray("vmArgs").toList().contains("-Xms3072m"), "fixed at the launcher's own size: " + h0);
      GcChoice.heapToStock(h0);
      check(h0.similar(stock), "undo of the fixed-only edit");
      JSONObject touched = new JSONObject(STOCK.replace("\"-XX:+UseZGC\",\"-XX:-Omit", "\"-XX:+UseZGC\",\"-XX:+AlwaysPreTouch\",\"-XX:-Omit"));
      JSONObject touchedStock = new JSONObject(touched.toString());
      GcChoice.heapToPzopt(touched, 4096, false, true);
      GcChoice.heapToStock(touched);
      check(touched.similar(touchedStock), "a player's own pre-touch flag stays: " + touched);
      check(GcChoice.heapMb(0) == 0 && GcChoice.heapMb(512) == 1024, "0 stays the launcher's own, at least 1 GB");
      check(GcChoice.heapMb(1 << 20) < 1 << 20 && GcChoice.heapMb(1 << 20) % 512 == 0, "1 TB is clamped to half the RAM, 512 MB steps");
      // gcHeap=auto: 4 GB, 8 GB from gcHeapAutoMods mods; game = the launcher's own; a number is MB
      check(GcChoice.wantMb("auto", 0) == Config.GC_HEAP_AUTO_MB && GcChoice.wantMb("auto", Config.GC_HEAP_AUTO_MODS - 1) == Config.GC_HEAP_AUTO_MB, "auto, few mods");
      check(GcChoice.wantMb("auto", Config.GC_HEAP_AUTO_MODS) == Config.GC_HEAP_AUTO_MODS_MB, "auto, a big mod list");
      check(GcChoice.wantMb("game", 200) == 0 && GcChoice.wantMb("6144", 0) == 6144 && GcChoice.wantMb("6144m", 0) == 6144, "game / MB");
      try {
         java.nio.file.Path mt = java.nio.file.Files.createTempFile("pzopt-mods", ".txt");
         java.nio.file.Files.writeString(mt, "VERSION = 1,\n\nmods\n{\n    mod = A,\n    mod = Authentic Z - Current,\n    mod = pzopt-harness,\n}\n\nmaps\n{\n}\n");
         check(GcChoice.countMods(mt.toFile()) == 2, "mods.txt counted without the harness mod");
         java.nio.file.Files.delete(mt);
      } catch (java.io.IOException e) {
         throw new AssertionError(e);
      }
      windowsVersionSections();
      logLine();
      System.out.println("GcChoiceTest ok");
   }

   /** The real Windows layout: the collector in windows."10.0.17134".vmArgs. */
   static void windowsVersionSections() {
      JSONObject stock = new JSONObject(WINDOWS_REAL);
      oneCollectorPerLaunch(stock, "stock");

      JSONObject j = new JSONObject(WINDOWS_REAL);
      check(GcChoice.toG1(j, 0), "the switch reaches windows.10.0.17134: " + j);
      List<Object> win10 = j.getJSONObject("windows").getJSONObject("10.0.17134").getJSONArray("vmArgs").toList();
      check(win10.contains("-XX:+UseG1GC") && win10.contains(GcChoice.MARKER) && !win10.contains("-XX:+UseZGC"),
            "10.0.17134 holds G1 and the marker, no ZGC: " + win10);
      check(j.getJSONObject("windows").getJSONObject("6.1").getJSONArray("vmArgs").toList().equals(List.of("-XX:+UseG1GC")),
            "6.1 is exactly the stock G1: " + j.getJSONObject("windows").getJSONObject("6.1"));
      check(j.getJSONArray("vmArgs").toString().equals(stock.getJSONArray("vmArgs").toString()), "the top level is unchanged: " + j.getJSONArray("vmArgs"));
      oneCollectorPerLaunch(j, "after toG1");
      String once = j.toString();
      check(!GcChoice.toG1(j, 0) && j.toString().equals(once), "a second toG1 is a no-op");
      check(GcChoice.toStock(j), "toStock undoes the nested switch");
      check(j.toString().equals(stock.toString()), "toStock restores the JSON exactly: " + j);

      JSONObject p = new JSONObject(WINDOWS_REAL);
      check(GcChoice.toG1(p, 50), "the pause variant switches");
      List<Object> win10p = p.getJSONObject("windows").getJSONObject("10.0.17134").getJSONArray("vmArgs").toList();
      check(win10p.contains("-XX:+UseG1GC") && win10p.contains("-XX:MaxGCPauseMillis=50") && win10p.contains(GcChoice.MARKER_PAUSE)
            && !win10p.contains("-XX:+UseZGC"), "the pause target lands in the same section: " + win10p);
      check(!p.getJSONArray("vmArgs").toString().contains("MaxGCPauseMillis") && !p.getJSONObject("windows").getJSONObject("6.1").toString().contains("Pause"),
            "no pause target elsewhere: " + p);
      oneCollectorPerLaunch(p, "after toG1 with a pause target");
      check(GcChoice.toStock(p) && p.toString().equals(stock.toString()), "toStock removes the pause target too: " + p);

      // jitSteady: its flags once per launch, at the top level (a version section is appended to it)
      JSONObject js = new JSONObject(WINDOWS_REAL);
      GcChoice.jitToSteady(js);
      check(js.getJSONArray("vmArgs").toList().contains(GcChoice.JIT_MARKER), "jit marker at the top level");
      check(!js.getJSONObject("windows").toString().contains("TrapLimit") && !js.getJSONObject("windows").toString().contains("pzopt.jit"),
            "no jit flags in the version sections: " + js.getJSONObject("windows"));
      GcChoice.jitToStock(js);
      check(js.toString().equals(stock.toString()), "jitToStock restores the real layout exactly: " + js);

      // gcHeap: -Xmx replaced at the top level only (a version section is appended to it)
      JSONObject hs = new JSONObject(WINDOWS_REAL);
      GcChoice.heapToPzopt(hs, 8192, false, false);
      check(hs.getJSONArray("vmArgs").toList().contains("-Xmx8192m") && !hs.getJSONArray("vmArgs").toList().contains("-Xmx12288m"),
            "heap replaced at the top level: " + hs.getJSONArray("vmArgs"));
      check(hs.getJSONObject("windows").toString().equals(stock.getJSONObject("windows").toString()),
            "no heap flags in the version sections: " + hs.getJSONObject("windows"));
      GcChoice.heapToStock(hs);
      check(hs.toString().equals(stock.toString()), "heapToStock restores the real layout exactly: " + hs);
   }

   /** The boot's next-launch phrase follows what the edit did. */
   static void logLine() {
      JSONObject j = new JSONObject(WINDOWS_REAL);
      check(GcChoice.apply(j, true, 0, false).equals("G1"), "real layout, gcMode=g1: the next launch is G1");
      String switched = j.toString();
      check(GcChoice.apply(j, true, 0, false).equals("G1") && j.toString().equals(switched), "the next boot keeps the switch as it is");
      check(GcChoice.apply(j, false, 0, false).equals("the launcher's own collector") && j.toString().equals(new JSONObject(WINDOWS_REAL).toString()),
            "gcMode=stock: the launcher's own collector, the switch undone");
      JSONObject none = new JSONObject(WINDOWS_REAL.replace("-XX:+UseZGC", "-XX:+UseParallelGC"));
      String said = GcChoice.apply(none, true, 0, false);
      check(!said.equals("G1") && said.contains("no -XX:+UseZGC"), "no ZGC to switch: the line says so, not G1: " + said);
   }

   /** Every launch the native launcher builds (the top-level vmArgs + one windows version section) selects one collector. */
   static void oneCollectorPerLaunch(JSONObject j, String when) {
      JSONObject win = j.getJSONObject("windows");
      for (String ver : win.keySet()) {
         List<Object> launch = new ArrayList<>(j.getJSONArray("vmArgs").toList());
         launch.addAll(win.getJSONObject(ver).getJSONArray("vmArgs").toList());
         Set<String> found = new TreeSet<>();
         for (Object a : launch) {
            for (String c : COLLECTORS) {
               if (c.equals(a)) {
                  found.add(c);
               }
            }
         }
         check(found.size() == 1, when + ": the Windows " + ver + " launch selects exactly one collector, has " + found + " in " + launch);
      }
   }

   private static void check(boolean ok, String what) {
      if (!ok) {
         throw new AssertionError("GcChoiceTest: " + what);
      }
   }
}
