package pzopt;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * gcMode (2026-09-23, maintainer's decision: G1 by default): which garbage collector the next launch uses. The game's
 * launcher JSON (ProjectZomboid64.json) starts the JVM with ZGC; on the 4-core Dell G1 was +11 % fps with half the frames
 * over 100 ms (ZGC's concurrent threads compete with the game on few cores). "stock" leaves the JSON alone and undoes our
 * switch, "g1" switches (the default), "auto" switches only on machines with {@code gcG1Cores} cores or fewer.
 *
 * The edit replaces -XX:+UseZGC with -XX:+UseG1GC in every vmArgs array that holds it, at any depth (the real Windows
 * launcher keeps its collector in windows."10.0.17134".vmArgs), adds -XX:MaxGCPauseMillis to that array
 * when {@code gcPauseMs} > 0, and the marker -Dpzopt.gc=g1 (also listing the pause flag it added, -Dpzopt.gc=g1,pause),
 * which is how this class, scripts/pzopt.sh and the installers recognise and undo it, in vmArgs arrays at any depth
 * (tests/pzopt/InstallerLauncherTest runs the scripts' undo on the real Windows layout). It takes effect on the next
 * launch. Harness runs own the JSON (run.sh --gc) and are left alone. Read and written through pzopt.LauncherJson, as
 * AotCache. The macOS app bundle (Info.plist) is not changed.
 *
 * gcHeap / gcHeapFixed / gcPreTouch (2026-10-05): the heap the next launch gets. The effective -Xmx (the last one) is
 * replaced by gcHeap: auto (default) = gcHeapAutoMb (4 GB), or gcHeapAutoModsMb (8 GB) when gcHeapAutoMods or more mods
 * are enabled (main menu's mods/default.txt or the last save's mods.txt, the larger); game = the launcher's own (3072 MB
 * on the Steam depots); a number = that many MB. Never more than half the machine's RAM.
 * gcHeapFixed sets -Xms to the same size (the heap never grows or shrinks), gcPreTouch adds -XX:+AlwaysPreTouch (every
 * heap page committed at boot). The marker -Dpzopt.heap=&lt;old -Xmx&gt;,&lt;old -Xms&gt;,&lt;pre-touch added 0|1&gt; ("none" for an
 * absent flag) records what was there, so every undo (this class, scripts/pzopt.sh, install.sh, install.ps1) puts it back.
 */
public final class GcChoice {
   static final String MARKER = "-Dpzopt.gc=g1";
   static final String JIT_MARKER = "-Dpzopt.jit=steady";
   /** jitSteady: the flags that follow JIT_MARKER; the undo removes every argument with one of these prefixes. */
   static final String[] JIT_FLAGS = {"-XX:PerMethodTrapLimit=0", "-XX:PerBytecodeTrapLimit=0"};
   static final String MARKER_PAUSE = "-Dpzopt.gc=g1,pause";
   private static final String ZGC = "-XX:+UseZGC";
   private static final String G1 = "-XX:+UseG1GC";
   private static final String PAUSE = "-XX:MaxGCPauseMillis=";
   static final String HEAP_MARKER = "-Dpzopt.heap=";
   private static final String XMX = "-Xmx";
   private static final String XMS = "-Xms";
   private static final String PRETOUCH = "-XX:+AlwaysPreTouch";

   private GcChoice() {
   }

   public static boolean wantG1() {
      String m = Config.GC_MODE;
      if (m.equals("g1")) {
         return true;
      }
      return m.equals("auto") && Runtime.getRuntime().availableProcessors() <= Config.GC_G1_CORES;
   }

   /** From boot, on AotCache's daemon thread (the other launcher-JSON writer), after its own step. */
   static void step() {
      if (HarnessFlags.get("mode") != null) {
         return; // run.sh writes the launcher JSON for every run (--gc)
      }
      try {
         Path game = Path.of(System.getProperty("user.dir"));
         Path json = game.resolve("ProjectZomboid64.json");
         if (!Files.isRegularFile(json)) {
            return;
         }
         JSONObject j = LauncherJson.read(game);
         String before = j.toString();
         String next = apply(j, Overrides.enabled() && wantG1(), Config.GC_PAUSE_MS, Overrides.enabled() && Config.JIT_STEADY);
         heapToStock(j);
         int mods = enabledMods();
         int heapMb = heapMb(wantMb(Config.GC_HEAP, mods));
         if (Overrides.enabled()) {
            heapToPzopt(j, heapMb, Config.GC_HEAP_FIXED, Config.GC_PRE_TOUCH);
         }
         boolean changed = !j.toString().equals(before);
         String written = "";
         if (changed) {
            written = LauncherJson.save(game, j) ? " (launcher JSON updated)" : " (launcher JSON staged until the game exits)";
         }
         Log.info("gc: running " + currentGc() + "; gcMode=" + Config.GC_MODE + " gcPauseMs=" + Config.GC_PAUSE_MS + " ("
               + Runtime.getRuntime().availableProcessors() + " cores) -> next launch " + next
               + "; heap now " + (Runtime.getRuntime().maxMemory() >> 20) + " MB max, gcHeap=" + Config.GC_HEAP + " (" + mods + " mods) -> "
               + (heapMb > 0 ? heapMb + " MB" : "the launcher's own")
               + (heapMb > 0 && heapMb != wantMb(Config.GC_HEAP, mods) ? " (clamped to half of " + (physicalMb() >> 10) + " GB RAM)" : "")
               + " gcHeapFixed=" + Config.GC_HEAP_FIXED + " gcPreTouch=" + Config.GC_PRE_TOUCH
               + written);
      } catch (Throwable e) {
         Log.warn("gc: " + e);
      }
   }

   /** A boot's edit of the launcher JSON; returns the next launch's collector for the log line. */
   static String apply(JSONObject j, boolean want, int pauseMs, boolean jit) {
      toStock(j); // from a clean stock form, so a changed gcPauseMs or mode is applied exactly
      boolean g1 = want && toG1(j, pauseMs);
      jitToStock(j);
      if (jit) {
         jitToSteady(j);
      }
      if (g1) {
         return "G1";
      }
      return want ? "the launcher's own collector (no -XX:+UseZGC in the launcher JSON to switch)" : "the launcher's own collector";
   }

   private static String currentGc() {
      StringBuilder sb = new StringBuilder();
      for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
         sb.append(sb.length() > 0 ? ", " : "").append(b.getName());
      }
      return sb.toString();
   }

   /** ZGC -> G1 (+ pause target) + marker, in every vmArgs array that selects ZGC. */
   static boolean toG1(JSONObject j, int pauseMs) {
      boolean[] changed = {false};
      forEachVmArgs(j, args -> {
         int z = indexOf(args, ZGC);
         if (z < 0) {
            return;
         }
         args.put(z, G1);
         if (pauseMs > 0 && prefixIndex(args, PAUSE) < 0) {
            args.put(PAUSE + pauseMs);
            args.put(MARKER_PAUSE);
         } else {
            args.put(MARKER);
         }
         changed[0] = true;
      });
      return changed[0];
   }

   /** jitSteady: the marker and the flags, in the jit arrays (the flags a user set himself stay: we only add ours). */
   static void jitToSteady(JSONObject j) {
      forEachTopVmArgs(j, args -> {
         if (indexOf(args, JIT_MARKER) >= 0) {
            return;
         }
         for (String f : JIT_FLAGS) {
            if (prefixIndex(args, f.substring(0, f.indexOf('=') + 1)) >= 0) {
               return; // the player tunes this flag himself
            }
         }
         args.put(JIT_MARKER);
         for (String f : JIT_FLAGS) {
            args.put(f);
         }
      });
   }

   /** Undo jitToSteady where our marker is. */
   static void jitToStock(JSONObject j) {
      forEachTopVmArgs(j, args -> {
         if (indexOf(args, JIT_MARKER) < 0) {
            return;
         }
         for (int i = args.length() - 1; i >= 0; i--) {
            String a = args.optString(i);
            boolean ours = a.equals(JIT_MARKER);
            for (String f : JIT_FLAGS) {
               ours |= a.startsWith(f.substring(0, f.indexOf('=') + 1));
            }
            if (ours) {
               args.remove(i);
            }
         }
      });
   }

   /** Undo toG1 where one of our markers is. */
   static boolean toStock(JSONObject j) {
      boolean[] changed = {false};
      forEachVmArgs(j, args -> {
         int m = indexOf(args, MARKER);
         int mp = indexOf(args, MARKER_PAUSE);
         if (m < 0 && mp < 0) {
            return;
         }
         if (mp >= 0) {
            int p = prefixIndex(args, PAUSE);
            if (p >= 0) {
               args.remove(p);
            }
         }
         for (int i = args.length() - 1; i >= 0; i--) {
            String a = args.optString(i);
            if (a.equals(MARKER) || a.equals(MARKER_PAUSE)) {
               args.remove(i);
            }
         }
         int g = indexOf(args, G1);
         if (g >= 0) {
            args.put(g, ZGC);
         }
         changed[0] = true;
      });
      return changed[0];
   }

   /** pzopt.Uninstall: the launcher's own collector and JIT flags again (both undos); true when the JSON changed. */
   static boolean undo(Path game) throws java.io.IOException {
      Path json = game.resolve("ProjectZomboid64.json");
      if (!Files.isRegularFile(json)) {
         return false;
      }
      JSONObject j = LauncherJson.read(game);
      String before = j.toString();
      toStock(j);
      jitToStock(j);
      heapToStock(j);
      if (j.toString().equals(before)) {
         return false;
      }
      LauncherJson.save(game, j);
      return true;
   }

   /** gcHeap as MB before the RAM clamp: 0 = the launcher's own. */
   static int wantMb(String heap, int mods) {
      if (heap.equals("game") || heap.equals("0")) {
         return 0;
      }
      if (heap.equals("auto")) {
         return mods >= Config.GC_HEAP_AUTO_MODS ? Config.GC_HEAP_AUTO_MODS_MB : Config.GC_HEAP_AUTO_MB;
      }
      try {
         return Math.max(0, Integer.parseInt(heap.endsWith("m") ? heap.substring(0, heap.length() - 1) : heap));
      } catch (NumberFormatException e) {
         return Config.GC_HEAP_AUTO_MB;
      }
   }

   /** Enabled mods the next launch will likely run: the main menu's (mods/default.txt) or the last save's, the larger. */
   static int enabledMods() {
      java.io.File z = UserOptions.zomboidDir();
      int n = countMods(new java.io.File(z, "mods/default.txt"));
      try {
         java.util.List<String> l = Files.readAllLines(new java.io.File(z, "latestSave.ini").toPath(), StandardCharsets.UTF_8);
         if (l.size() >= 2) {
            n = Math.max(n, countMods(new java.io.File(z, "Saves/" + l.get(1).trim() + "/" + l.get(0).trim() + "/mods.txt")));
         }
      } catch (Exception e) {
         // no save yet
      }
      return n;
   }

   /** "mod = <id>," lines of a mods.txt, the harness's own and pzopt's left out. */
   static int countMods(java.io.File f) {
      int n = 0;
      try {
         for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.startsWith("mod") && t.contains("=")) {
               String id = t.substring(t.indexOf('=') + 1).replace(",", "").trim();
               if (!id.isEmpty() && !id.startsWith("pzopt-") && !id.equals("PZ_Optimization")) {
                  n++;
               }
            }
         }
      } catch (Exception e) {
         return 0;
      }
      return n;
   }

   /** gcHeap as MB: 0 stays 0 (the launcher's own); otherwise at least 1024 MB and at most half the RAM (512 MB steps). */
   static int heapMb(int want) {
      if (want <= 0) {
         return 0;
      }
      long half = physicalMb() / 2;
      int cap = half <= 0 ? want : (int) Math.max(1024, half / 512 * 512);
      return Math.max(1024, Math.min(want, cap));
   }

   private static long physicalMb() {
      try {
         return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize() >> 20;
      } catch (Throwable e) {
         return 0;
      }
   }

   /** gcHeap / gcHeapFixed / gcPreTouch into every vmArgs array, with the marker recording the flags they replaced. */
   static void heapToPzopt(JSONObject j, int heapMb, boolean fixed, boolean preTouch) {
      forEachTopVmArgs(j, args -> {
         if (prefixIndex(args, HEAP_MARKER) >= 0) {
            return;
         }
         int mx = lastPrefixIndex(args, XMX);
         int ms = lastPrefixIndex(args, XMS);
         String oldMx = mx < 0 ? "none" : args.optString(mx).substring(XMX.length());
         String oldMs = ms < 0 ? "none" : args.optString(ms).substring(XMS.length());
         String newMx = heapMb > 0 ? heapMb + "m" : mx < 0 ? null : oldMx;
         String newMs = fixed && newMx != null ? newMx : null;
         boolean addTouch = preTouch && indexOf(args, PRETOUCH) < 0;
         if (heapMb <= 0 && newMs == null && !addTouch) {
            return;
         }
         if (newMx != null) {
            set(args, mx, XMX + newMx);
         }
         if (newMs != null) {
            set(args, ms, XMS + newMs);
         }
         if (addTouch) {
            args.put(PRETOUCH);
         }
         args.put(HEAP_MARKER + oldMx + "," + oldMs + "," + (addTouch ? 1 : 0));
      });
   }

   /** Undo heapToPzopt where its marker is: the old -Xmx / -Xms back (or gone), our pre-touch flag removed. */
   static void heapToStock(JSONObject j) {
      forEachTopVmArgs(j, args -> {
         int m = lastPrefixIndex(args, HEAP_MARKER);
         if (m < 0) {
            return;
         }
         String[] old = args.optString(m).substring(HEAP_MARKER.length()).split(",");
         for (int i = args.length() - 1; i >= 0; i--) {
            if (args.optString(i).startsWith(HEAP_MARKER)) {
               args.remove(i);
            }
         }
         restore(args, XMX, old.length > 0 ? old[0] : "none");
         restore(args, XMS, old.length > 1 ? old[1] : "none");
         if (old.length > 2 && old[2].equals("1")) {
            int t = indexOf(args, PRETOUCH);
            if (t >= 0) {
               args.remove(t);
            }
         }
      });
   }

   private static void set(JSONArray a, int i, String v) {
      if (i >= 0) {
         a.put(i, v);
      } else {
         a.put(v);
      }
   }

   private static void restore(JSONArray a, String flag, String old) {
      int i = lastPrefixIndex(a, flag);
      if (old.equals("none")) {
         if (i >= 0) {
            a.remove(i);
         }
      } else {
         set(a, i, flag + old);
      }
   }

   private static int lastPrefixIndex(JSONArray a, String prefix) {
      for (int i = a.length() - 1; i >= 0; i--) {
         if (a.optString(i).startsWith(prefix)) {
            return i;
         }
      }
      return -1;
   }

   private static int indexOf(JSONArray a, String s) {
      for (int i = 0; i < a.length(); i++) {
         if (s.equals(a.optString(i))) {
            return i;
         }
      }
      return -1;
   }

   private static int prefixIndex(JSONArray a, String prefix) {
      for (int i = 0; i < a.length(); i++) {
         if (a.optString(i).startsWith(prefix)) {
            return i;
         }
      }
      return -1;
   }

   /**
    * Every vmArgs array at any depth: the top level, a per-platform section's ("windows": {"vmArgs": ...}) and a per-OS
    * version one's ("windows": {"10.0.17134": {"vmArgs": ...}}, where the real Windows launcher keeps its collector and
    * which the native launcher appends to the top-level vmArgs). toG1 / toStock work per array, so a stock collector in
    * another version's section (the G1 of "6.1") carries no marker and stays as it is.
    */
   private static void forEachVmArgs(JSONObject j, java.util.function.Consumer<JSONArray> f) {
      JSONArray a = j.optJSONArray("vmArgs");
      if (a != null) {
         f.accept(a);
      }
      for (String k : j.keySet()) {
         JSONObject sec = j.optJSONObject(k);
         if (sec != null) {
            forEachVmArgs(sec, f);
         }
      }
   }

   /**
    * jitSteady's and the heap's arrays: the top-level vmArgs and every per-platform section's, not the per-OS version
    * sections below them. A version section is appended to the top level, so flags there would come twice and after a
    * player's own.
    */
   private static void forEachTopVmArgs(JSONObject j, java.util.function.Consumer<JSONArray> f) {
      JSONArray top = j.optJSONArray("vmArgs");
      if (top != null) {
         f.accept(top);
      }
      for (String k : j.keySet()) {
         JSONObject sec = j.optJSONObject(k);
         JSONArray a = sec == null ? null : sec.optJSONArray("vmArgs");
         if (a != null) {
            f.accept(a);
         }
      }
   }
}
