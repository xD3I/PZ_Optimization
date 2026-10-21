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
      check(ha.contains("-Xms6144m") && ha.contains("-XX:+AlwaysPreTouch") && ha.contains(GcChoice.HEAP_MARKER + "3072m,none,1,6144m,6144m,6144"), "-Xms, pre-touch, marker: " + ha);
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
      check(ba.contains(GcChoice.HEAP_MARKER + "5120m,5120m,0,4096m,4096m,4096"), "marker keeps the player's sizes: " + ba);
      GcChoice.heapToStock(big);
      check(big.similar(bigStock), "undo gives the player's -Xms / -Xmx back: " + big);
      JSONObject small = new JSONObject(STOCK.replace("\"-Xmx3072m\"", "\"-Xms2g\",\"-Xmx3072m\""));
      GcChoice.heapToPzopt(small, 4096, false, false);
      check(small.getJSONArray("vmArgs").toList().contains("-Xms2g"), "a smaller -Xms stays as it is: " + small);
      heapPlayerChoice(stock);
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

   /** 2026-10-19 (Workshop: "the mod resets my -Xmx12g to 3g"): auto never lowers a heap, a hand edit wins, every undo agrees. */
   private static void heapPlayerChoice(JSONObject stock) {
      int auto = GcChoice.heapMb(Config.GC_HEAP_AUTO_MB);
      int autoMods = GcChoice.heapMb(Config.GC_HEAP_AUTO_MODS_MB);
      // stock 3072 -> auto writes its size (4096, or 8192 with a big mod list), undone exactly
      for (int size : new int[] {auto, autoMods}) {
         JSONObject a = new JSONObject(STOCK);
         GcChoice.heapApply(a, "auto", size, false, false);
         java.util.List<Object> aa = a.getJSONArray("vmArgs").toList();
         check(aa.get(0).equals("-Xmx" + size + "m") && aa.contains(GcChoice.HEAP_MARKER + "3072m,none,0," + size + "m,-,auto"), "auto raises the stock heap: " + aa);
         String once = a.toString();
         GcChoice.heapApply(a, "auto", size, false, false);
         check(a.toString().equals(once), "auto re-applied is a no-op");
         GcChoice.heapToStock(a);
         check(a.similar(stock), "auto undone exactly: " + a);
      }
      // a player's 12g before we touched the file: auto leaves it (no marker, nothing for an undo to put back)
      JSONObject p12 = new JSONObject(STOCK.replace("-Xmx3072m", "-Xmx12g"));
      JSONObject p12Stock = new JSONObject(p12.toString());
      check(!GcChoice.heapApply(p12, "auto", auto, false, false).isEmpty() && p12.similar(p12Stock), "auto keeps the player's 12g: " + p12);
      GcChoice.heapApply(p12, "auto", autoMods, false, false);
      check(p12.similar(p12Stock), "auto with mods keeps it too");
      // above the half-RAM cap (auto's size clamped to 2048 on a small machine): still his
      JSONObject p6 = new JSONObject(STOCK.replace("-Xmx3072m", "-Xmx6144m"));
      GcChoice.heapApply(p6, "auto", 2048, false, false);
      check(p6.getJSONArray("vmArgs").toList().get(0).equals("-Xmx6144m") && !p6.toString().contains("pzopt.heap"), "the cap never shrinks a player's heap: " + p6);
      // ... but an explicit number is written (the menu choice), and undone to the player's value
      JSONObject pe = new JSONObject(p12Stock.toString());
      GcChoice.heapApply(pe, "16384", 16384, false, false);
      check(pe.getJSONArray("vmArgs").toList().get(0).equals("-Xmx16384m"), "an explicit size replaces it: " + pe);
      GcChoice.heapToStock(pe);
      check(pe.similar(p12Stock), "and the undo gives the 12g back: " + pe);
      // the player edits -Xmx after we wrote ours: kept on the next launch, on a re-apply and by every undo
      JSONObject ed = new JSONObject(STOCK);
      GcChoice.heapApply(ed, "auto", auto, false, false);
      JSONObject edited = new JSONObject(ed.toString().replace("-Xmx" + auto + "m", "-Xmx12g"));
      JSONObject next = new JSONObject(edited.toString());
      String note = GcChoice.heapApply(next, "auto", auto, false, false);
      java.util.List<Object> na = next.getJSONArray("vmArgs").toList();
      check(na.get(0).equals("-Xmx12g") && !na.contains("-Xmx3072m") && na.contains(GcChoice.HEAP_MARKER + "12g,none,0,p,-,auto") && note.contains("by hand"),
            "the hand edit survives the next launch: " + na);
      String twice = next.toString();
      GcChoice.heapApply(next, "auto", auto, false, false);
      check(next.toString().equals(twice), "and every launch after it");
      // a hand edit below auto's size is his too (auto does not raise it back while gcHeap stays auto)
      JSONObject low = new JSONObject(ed.toString().replace("-Xmx" + auto + "m", "-Xmx2g"));
      GcChoice.heapApply(low, "auto", auto, false, false);
      GcChoice.heapApply(low, "auto", auto, false, false);
      check(low.getJSONArray("vmArgs").toList().get(0).equals("-Xmx2g"), "a hand edit to 2g stays under auto: " + low);
      // a later menu choice applies again over it, and its undo gives his value back
      GcChoice.heapApply(low, "8192", 8192, false, false);
      check(low.getJSONArray("vmArgs").toList().get(0).equals("-Xmx8192m"), "a new menu choice applies: " + low);
      GcChoice.heapToStock(low);
      check(low.getJSONArray("vmArgs").toList().get(0).equals("-Xmx2g") && !low.toString().contains("pzopt.heap"), "its undo gives the 2g back: " + low);
      // undo / uninstall right after the hand edit: his value stays, not the marker's 3072
      JSONObject un = new JSONObject(edited.toString());
      GcChoice.heapToStock(un);
      check(un.getJSONArray("vmArgs").toList().get(0).equals("-Xmx12g") && !un.toString().contains("pzopt.heap"), "undo keeps the hand edit: " + un);
      JSONObject un2 = new JSONObject(next.toString());
      GcChoice.heapToStock(un2);
      check(un2.getJSONArray("vmArgs").toList().get(0).equals("-Xmx12g") && !un2.toString().contains("pzopt.heap"), "undo of a 'p' marker keeps it: " + un2);
      // a marker of the old three-field form (installs 2026-10-05..10-18) with the player's 12g typed over our 4096
      JSONObject legacy = new JSONObject(STOCK.replace("\"-Xmx3072m\"", "\"-Xmx12g\"").replace("\"-XX:-OmitStackTraceInFastThrow\"",
            "\"-XX:-OmitStackTraceInFastThrow\",\"" + GcChoice.HEAP_MARKER + "3072m,none,0\""));
      JSONObject legacyUndo = new JSONObject(legacy.toString());
      GcChoice.heapApply(legacy, "auto", auto, false, false);
      check(legacy.getJSONArray("vmArgs").toList().get(0).equals("-Xmx12g"), "legacy marker: the 12g survives the launch: " + legacy);
      GcChoice.heapToStock(legacyUndo);
      check(legacyUndo.getJSONArray("vmArgs").toList().get(0).equals("-Xmx12g") && !legacyUndo.toString().contains("pzopt.heap"), "legacy marker: and the undo: " + legacyUndo);
      // ... "12288m" typed by hand looks like one of our sizes, but auto never lowers it
      JSONObject legacyM = new JSONObject(legacy.toString().replace("-Xmx12g", "-Xmx12288m").replace(GcChoice.HEAP_MARKER + "12g,none,0,p,-,auto", GcChoice.HEAP_MARKER + "3072m,none,0"));
      GcChoice.heapApply(legacyM, "auto", auto, false, false);
      check(legacyM.getJSONArray("vmArgs").toList().get(0).equals("-Xmx12288m"), "legacy marker: a typed 12288m is not lowered by auto: " + legacyM);
      // ... and a legacy marker over our own auto size is still ours: the undo gives the launcher's 3072 back
      JSONObject legacyOurs = new JSONObject(STOCK.replace("\"-Xmx3072m\"", "\"-Xmx" + auto + "m\"").replace("\"-XX:-OmitStackTraceInFastThrow\"",
            "\"-XX:-OmitStackTraceInFastThrow\",\"" + GcChoice.HEAP_MARKER + "3072m,none,0\""));
      GcChoice.heapToStock(legacyOurs);
      check(legacyOurs.getJSONArray("vmArgs").toList().get(0).equals("-Xmx3072m") && !legacyOurs.toString().contains("pzopt.heap"), "legacy marker: our own size undone: " + legacyOurs);
      check(GcChoice.legacyOurs("4096m") && GcChoice.legacyOurs("7680m") && !GcChoice.legacyOurs("12g") && !GcChoice.legacyOurs("3000m") && !GcChoice.legacyOurs("none"), "legacy sizes");
      // -Xms: fixed follows the kept -Xmx; a hand-edited -Xms is his, and our -Xmx never goes below it
      JSONObject fx = new JSONObject(p12Stock.toString());
      GcChoice.heapApply(fx, "auto", auto, true, false);
      java.util.List<Object> fa = fx.getJSONArray("vmArgs").toList();
      check(fa.contains("-Xmx12g") && fa.contains("-Xms12g") && GcChoice.heapConflict(fx) == null, "fixed under a kept 12g: " + fa);
      GcChoice.heapToStock(fx);
      check(fx.similar(p12Stock), "fixed undone: " + fx);
      JSONObject msEd = new JSONObject(STOCK);
      GcChoice.heapApply(msEd, "auto", auto, true, false);
      msEd = new JSONObject(msEd.toString().replace("-Xms" + auto + "m", "-Xms6g"));
      GcChoice.heapApply(msEd, "auto", auto, true, false);
      java.util.List<Object> ma = msEd.getJSONArray("vmArgs").toList();
      check(ma.contains("-Xms6g") && ma.contains("-Xmx6g") && GcChoice.heapConflict(msEd) == null, "a hand-edited -Xms above our -Xmx raises it: " + ma);
      GcChoice.heapToStock(msEd);
      ma = msEd.getJSONArray("vmArgs").toList();
      check(ma.contains("-Xms6g") && ma.contains("-Xmx6g") && GcChoice.heapConflict(msEd) == null && !msEd.toString().contains("pzopt.heap"), "undo keeps the player's -Xms and an -Xmx it fits under: " + ma);
      // explicit settings unchanged: a number replaces any -Xmx (a larger one too), game leaves it
      JSONObject ex = new JSONObject(STOCK.replace("-Xmx3072m", "-Xmx12g"));
      GcChoice.heapApply(ex, "6144", 6144, false, false);
      check(ex.getJSONArray("vmArgs").toList().get(0).equals("-Xmx6144m") && ex.toString().contains(GcChoice.HEAP_MARKER + "12g,none,0,6144m,-,6144"), "explicit number: " + ex);
      GcChoice.heapToStock(ex);
      check(ex.getJSONArray("vmArgs").toList().get(0).equals("-Xmx12g"), "explicit number undone");
      JSONObject gm = new JSONObject(STOCK);
      GcChoice.heapApply(gm, "auto", auto, false, false);
      GcChoice.heapApply(gm, "game", 0, false, false);
      check(gm.similar(stock), "gcHeap=game after auto: the launcher's own again: " + gm);
   }

   private static void check(boolean ok, String what) {
      if (!ok) {
         throw new AssertionError("GcChoiceTest: " + what);
      }
   }
}
