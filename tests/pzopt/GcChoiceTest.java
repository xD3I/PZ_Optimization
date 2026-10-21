package pzopt;

import org.json.JSONObject;

/**
 * pzopt.GcChoice's launcher edit: ZGC -> G1 with the marker (top level and per-platform sections), the optional pause
 * target, the undo back to the exact stock arguments, re-applying is a no-op, and a JSON without ZGC or our marker is
 * left alone.
 */
public class GcChoiceTest {
   private static final String STOCK = "{\"mainClass\":\"m\",\"classpath\":[\".\",\"projectzomboid.jar\"],"
         + "\"vmArgs\":[\"-Xmx3072m\",\"-XX:+UseZGC\",\"-XX:-OmitStackTraceInFastThrow\"],"
         + "\"windows\":{\"vmArgs\":[\"-Xmx3072m\",\"-XX:+UseZGC\"]}}";

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
      // the real Windows JSON: the collector per OS version, one level under "windows" (2026-10-11)
      String win = "{\"mainClass\":\"m\",\"classpath\":[\".\",\"projectzomboid.jar\"],\"vmArgs\":[\"-Xmx3072m\"],"
            + "\"windows\":{\"6.1\":{\"vmArgs\":[\"-XX:+UseG1GC\"]},\"10.0.17134\":{\"vmArgs\":[\"-XX:+UseZGC\"]}}}";
      JSONObject w = new JSONObject(win);
      check(GcChoice.toG1(w, 0), "windows: switches");
      JSONObject w10 = w.getJSONObject("windows").getJSONObject("10.0.17134");
      check(w10.getJSONArray("vmArgs").toList().contains("-XX:+UseG1GC") && w10.getJSONArray("vmArgs").toList().contains(GcChoice.MARKER), "windows 10 section G1 + marker");
      check(w.getJSONObject("windows").getJSONObject("6.1").getJSONArray("vmArgs").toList().equals(java.util.List.of("-XX:+UseG1GC")), "windows 6.1 section untouched");
      check(GcChoice.toStock(w) && w.similar(new JSONObject(win)), "windows: undo gives the stock JSON back: " + w);
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
      // -XX:-UseTypeSpeculation (2026-10-15): one of the jitSteady flags; a player's own +UseTypeSpeculation keeps the section stock
      check(java.util.Arrays.asList(GcChoice.JIT_FLAGS).contains("-XX:-UseTypeSpeculation"), "jitSteady carries -XX:-UseTypeSpeculation");
      JSONObject ownTs = new JSONObject(STOCK.replace("\"-XX:-OmitStackTraceInFastThrow\"", "\"-XX:-OmitStackTraceInFastThrow\",\"-XX:+UseTypeSpeculation\""));
      String ownTsBefore = ownTs.getJSONArray("vmArgs").toString();
      GcChoice.jitToSteady(ownTs);
      check(ownTs.getJSONArray("vmArgs").toString().equals(ownTsBefore), "a player's own UseTypeSpeculation: the section is left alone");
      // an older install's marker with only the two trap-limit flags is upgraded by apply()'s undo + redo
      JSONObject old = new JSONObject(STOCK.replace("\"-XX:-OmitStackTraceInFastThrow\"", "\"-XX:-OmitStackTraceInFastThrow\",\"" + GcChoice.JIT_MARKER + "\",\"-XX:PerMethodTrapLimit=0\",\"-XX:PerBytecodeTrapLimit=0\""));
      GcChoice.jitToStock(old);
      GcChoice.jitToSteady(old);
      check(old.getJSONArray("vmArgs").toList().contains("-XX:-UseTypeSpeculation") && old.getJSONArray("vmArgs").toList().stream().filter(GcChoice.JIT_MARKER::equals).count() == 1, "old jit form upgraded: " + old.getJSONArray("vmArgs"));
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
      // a player's own -Xms above gcHeap's -Xmx (Discord 2026-10-17): lowered with it, or the JVM refuses to start
      JSONObject big = new JSONObject(STOCK.replace("\"-Xmx3072m\"", "\"-Xms5120m\",\"-Xmx5120m\""));
      JSONObject bigStock = new JSONObject(big.toString());
      GcChoice.heapToPzopt(big, 4096, false, false);
      java.util.List<Object> ba = big.getJSONArray("vmArgs").toList();
      check(ba.contains("-Xmx4096m") && ba.contains("-Xms4096m") && !ba.contains("-Xms5120m") && GcChoice.heapConflict(big) == null,
            "-Xms lowered to the new -Xmx: " + ba);
      check(ba.contains(GcChoice.HEAP_MARKER + "5120m,5120m,0"), "marker keeps the player's sizes: " + ba);
      GcChoice.heapToStock(big);
      check(big.similar(bigStock), "undo gives the player's -Xms / -Xmx back: " + big);
      JSONObject small = new JSONObject(STOCK.replace("\"-Xmx3072m\"", "\"-Xms2g\",\"-Xmx3072m\""));
      GcChoice.heapToPzopt(small, 4096, false, false);
      check(small.getJSONArray("vmArgs").toList().contains("-Xms2g"), "a smaller -Xms stays as it is: " + small);
      check(GcChoice.heapConflict(new JSONObject(STOCK.replace("\"-Xmx3072m\"", "\"-Xms6g\",\"-Xmx4096m\""))) != null, "conflict found");
      check(GcChoice.heapConflict(new JSONObject(STOCK)) == null, "stock has no conflict");
      check(GcChoice.sizeMb("5120m") == 5120 && GcChoice.sizeMb("6G") == 6144 && GcChoice.sizeMb("4194304k") == 4096
            && GcChoice.sizeMb("1073741824") == 1024 && GcChoice.sizeMb("x") == -1, "sizeMb units");
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
      // gcG1Tuned (2026-10-14): the tuned set after the unlock, the 5 ms target, undone exactly; a player's own unlock /
      // tuning stays; the Windows per-version section gets it where its ZGC was
      JSONObject t = new JSONObject(STOCK);
      check(GcChoice.toG1(t, GcChoice.TUNE_PAUSE_MS, true), "tuned: switches");
      java.util.List<Object> ta = t.getJSONArray("vmArgs").toList();
      int unlockAt = ta.indexOf(GcChoice.UNLOCK);
      check(unlockAt >= 0 && ta.contains(GcChoice.IGNORE) && ta.contains("-XX:MaxGCPauseMillis=5") && ta.contains(GcChoice.MARKER_PAUSE)
            && ta.contains(GcChoice.TUNE_MARKER + "g1,ignore,unlock"), "tuned: unlock, ignore, pause, markers: " + ta);
      for (String f : GcChoice.TUNE_FLAGS) {
         check(ta.indexOf(f) > unlockAt, "tuned flag after the unlock: " + f);
      }
      check(!GcChoice.toG1(t, GcChoice.TUNE_PAUSE_MS, true), "tuned: second switch is a no-op");
      check(GcChoice.toStock(t) && t.similar(stock), "tuned: undo gives the stock JSON back: " + t);
      JSONObject tu = new JSONObject(STOCK.replace("\"-XX:-OmitStackTraceInFastThrow\"", "\"-XX:+UnlockExperimentalVMOptions\",\"-XX:-OmitStackTraceInFastThrow\""));
      JSONObject tuStock = new JSONObject(tu.toString());
      GcChoice.toG1(tu, GcChoice.TUNE_PAUSE_MS, true);
      check(tu.getJSONArray("vmArgs").toList().contains(GcChoice.TUNE_MARKER + "g1,ignore"), "a player's own unlock is not added twice: " + tu);
      GcChoice.toStock(tu);
      check(tu.similar(tuStock), "undo keeps the player's own unlock: " + tu);
      check(!GcChoice.playerTunes(new JSONObject(STOCK)) && GcChoice.playerTunes(new JSONObject(STOCK.replace("\"-Xmx3072m\",\"-XX:+UseZGC\",\"-XX:-Omit",
            "\"-Xmx3072m\",\"-XX:+UseZGC\",\"-XX:G1HeapWastePercent=7\",\"-XX:-Omit"))), "a player's own G1 tuning is seen");
      check(GcChoice.flagName("-XX:+UnlockExperimentalVMOptions").equals("UnlockExperimentalVMOptions") && GcChoice.flagName("-XX:G1NewSizePercent=2").equals("G1NewSizePercent")
            && GcChoice.flagName("-Xmx3g") == null, "flag names");
      JSONObject tw = new JSONObject(win);
      GcChoice.toG1(tw, GcChoice.TUNE_PAUSE_MS, true);
      check(tw.getJSONObject("windows").getJSONObject("10.0.17134").getJSONArray("vmArgs").toList().contains("-XX:G1NewSizePercent=2")
            && !tw.getJSONArray("vmArgs").toList().contains("-XX:G1NewSizePercent=2"), "windows: the tuned set in the version section: " + tw);
      check(GcChoice.toStock(tw) && tw.similar(new JSONObject(win)), "windows: tuned undo: " + tw);
      System.out.println("GcChoiceTest ok");
   }

   private static void check(boolean ok, String what) {
      if (!ok) {
         throw new AssertionError("GcChoiceTest: " + what);
      }
   }
}
