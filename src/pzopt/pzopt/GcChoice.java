package pzopt;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * gcMode (2026-09-23, maintainer's decision: G1 by default): which garbage collector the next launch uses. The game's
 * launcher JSON (ProjectZomboid64.json) starts the JVM with ZGC; on the 4-core Dell G1 was +11 % fps with half the frames
 * over 100 ms (ZGC's concurrent threads compete with the game on few cores). "stock" leaves the JSON alone and undoes our
 * switch, "g1" switches (the default), "auto" switches only on machines with {@code gcG1Cores} cores or fewer.
 *
 * The edit replaces -XX:+UseZGC with -XX:+UseG1GC in the top-level and per-platform vmArgs, adds -XX:MaxGCPauseMillis
 * when {@code gcPauseMs} > 0, and the marker -Dpzopt.gc=g1 (also listing the pause flag it added, -Dpzopt.gc=g1,pause),
 * which is how this class, scripts/pzopt.sh and the installers recognise and undo it. It takes effect on the next
 * launch. Harness runs own the JSON (run.sh --gc) and are left alone. Backed up once and written atomically, as AotCache.
 * The macOS app bundle (Info.plist) is not changed.
 *
 * gcHeap / gcHeapFixed / gcPreTouch (2026-10-05): the heap the next launch gets. The effective -Xmx (the last one) is
 * replaced by gcHeap: auto (default) = gcHeapAutoMb (4 GB), or gcHeapAutoModsMb (8 GB) when gcHeapAutoMods or more mods
 * are enabled (main menu's mods/default.txt or the last save's mods.txt, the larger); game = the launcher's own (3072 MB
 * on the Steam depots); a number = that many MB. What we write is never more than half the machine's RAM.
 * gcHeapFixed sets -Xms to the same size (the heap never grows or shrinks), gcPreTouch adds -XX:+AlwaysPreTouch (every
 * heap page committed at boot). The marker -Dpzopt.heap=&lt;old -Xmx&gt;,&lt;old -Xms&gt;,&lt;pre-touch added 0|1&gt;,&lt;-Xmx
 * written&gt;,&lt;-Xms written&gt;,&lt;gcHeap&gt; ("none" for an absent flag; written "-" = left as it was, "p" = the player's)
 * records what was there and what we put, so every undo (this class, scripts/pzopt.sh, install.sh, install.ps1) puts the
 * old value back where our value still stands. Since 2026-10-19 (Workshop, three players: "the mod resets my -Xmx12g to
 * 3g"; until then every launch restored the marker's old -Xmx over whatever the file held before writing gcHeap, and auto
 * replaced any -Xmx, larger ones too): auto only raises the heap (an -Xmx at or above auto's size stays, even past the
 * half-RAM cap), and a flag changed by hand after we wrote it is the player's: no launch, undo or uninstaller writes over
 * it until gcHeap is changed in the menu. Markers of the three-field form are read as before for the old value; what we
 * wrote is inferred ({@link #legacyOurs}). Older installers' undo reads only the first three fields; a "p" marker's old
 * values are the player's own, so they put his value back too.
 *
 * gcG1Tuned (2026-10-14, maintainer; the GC pass, docs/override-edits.md "Defaults ... gcSlack and players' G1 settings"):
 * with gcMode=g1 the switch also writes {@link #TUNE_FLAGS} (a 2-4 % young generation, no survivor copies, mixed
 * collections in 24 small steps, a 512 MB code cache that no longer starts marking cycles) after an
 * -XX:+UnlockExperimentalVMOptions and an -XX:+IgnoreUnrecognizedVMOptions (both only when that array lacks them), and the
 * pause target becomes 5 ms unless gcPauseMs names one. The marker -Dpzopt.gctune=g1[,unlock][,ignore] says which of the
 * two shared flags were ours, so every undo removes exactly what was added. An unknown -XX flag stops the JVM, so the set
 * is written only when the running Java is the release it was checked on ({@link #TUNE_FEATURE}) and the running JVM
 * knows each product flag; IgnoreUnrecognizedVMOptions keeps a later Java that drops one of them starting. A launcher JSON
 * that already sets one of these flags (a player tuning G1 himself) gets the plain switch.
 */
public final class GcChoice {
   static final String MARKER = "-Dpzopt.gc=g1";
   static final String JIT_MARKER = "-Dpzopt.jit=steady";
   /**
    * jitSteady: the flags that follow JIT_MARKER; the undo removes every argument that sets one of these flags (by name).
    * -XX:-UseTypeSpeculation since 2026-10-15 (maintainer; f8-resid): C2 no longer speculates on profiled argument / return
    * types, which removed the ~45 speculative-type deoptimizations of a Louisville route (IsoPlayer.updateLOS deoptimized at
    * the horde's first sight).
    */
   static final String[] JIT_FLAGS = {"-XX:PerMethodTrapLimit=0", "-XX:PerBytecodeTrapLimit=0", "-XX:-UseTypeSpeculation"};
   static final String MARKER_PAUSE = "-Dpzopt.gc=g1,pause";
   private static final String ZGC = "-XX:+UseZGC";
   private static final String G1 = "-XX:+UseG1GC";
   private static final String PAUSE = "-XX:MaxGCPauseMillis=";
   static final String HEAP_MARKER = "-Dpzopt.heap=";
   /** Heap marker field 4 / 5: we left this flag as it was. */
   static final String HEAP_LEFT = "-";
   /** Heap marker field 4 / 5: the player changed this flag by hand after we wrote it; his while gcHeap stays field 6. */
   static final String HEAP_PLAYER = "p";
   private static final String XMX = "-Xmx";
   private static final String XMS = "-Xms";
   private static final String PRETOUCH = "-XX:+AlwaysPreTouch";
   static final String TUNE_MARKER = "-Dpzopt.gctune=";
   static final String UNLOCK = "-XX:+UnlockExperimentalVMOptions";
   static final String IGNORE = "-XX:+IgnoreUnrecognizedVMOptions";
   /** gcG1Tuned: the GC pass's G1 set (HANDOFF-gc fin4 without the heap flags), in this order after UNLOCK. */
   static final String[] TUNE_FLAGS = {"-XX:G1NewSizePercent=2", "-XX:G1MaxNewSizePercent=4", "-XX:MaxTenuringThreshold=0",
         "-XX:ReservedCodeCacheSize=512m", "-XX:SweeperThreshold=50", "-XX:-G1UseAdaptiveIHOP", "-XX:InitiatingHeapOccupancyPercent=70",
         "-XX:G1MixedGCCountTarget=24", "-XX:G1MixedGCLiveThresholdPercent=50", "-XX:G1HeapWastePercent=10"};
   /** Flags of TUNE_FLAGS a JVM without UnlockExperimentalVMOptions does not show to HotSpotDiagnosticMXBean. */
   private static final java.util.Set<String> TUNE_EXPERIMENTAL = java.util.Set.of("G1NewSizePercent", "G1MaxNewSizePercent", "G1MixedGCLiveThresholdPercent");
   /** gcG1Tuned's pause target when gcPauseMs is 0. */
   static final int TUNE_PAUSE_MS = 5;
   /** The Java feature release every TUNE_FLAGS flag was checked on (the game's Zulu 25.0.1 on Linux and Windows). */
   static final int TUNE_FEATURE = 25;

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
      Log.info("gc: this launch's JVM flags: " + launchFlags());
      if (HarnessFlags.get("mode") != null) {
         return; // run.sh writes the launcher JSON for every run (--gc)
      }
      apply(Path.of(System.getProperty("user.dir")));
   }

   /** The running JVM's -XX / -Xm* arguments and our launcher markers (the proof of what a launch got). */
   static String launchFlags() {
      try {
         StringBuilder sb = new StringBuilder();
         for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (a.startsWith("-XX:") || a.startsWith("-Xmx") || a.startsWith("-Xms") || a.startsWith("-Dpzopt.gc") || a.startsWith(HEAP_MARKER)) {
               sb.append(sb.length() > 0 ? " " : "").append(a);
            }
         }
         return sb.toString();
      } catch (Throwable e) {
         return "(" + e + ")";
      }
   }

   /** The launcher JSON in {@code game} for the next launch (step(), outside harness runs). */
   static void apply(Path game) {
      try {
         Path json = game.resolve("ProjectZomboid64.json");
         if (!Files.isRegularFile(json)) {
            return;
         }
         JSONObject j = new JSONObject(Files.readString(json, StandardCharsets.UTF_8));
         String before = j.toString();
         boolean want = Overrides.enabled() && wantG1();
         toStock(j); // from a clean stock form, so a changed gcPauseMs or mode is applied exactly
         boolean tune = want && Config.GC_G1_TUNED && Config.GC_MODE.equals("g1") && !playerTunes(j) && tuneSupported();
         if (want) {
            toG1(j, Config.GC_PAUSE_MS > 0 ? Config.GC_PAUSE_MS : tune ? TUNE_PAUSE_MS : 0, tune);
         }
         jitToStock(j);
         if (Overrides.enabled() && Config.JIT_STEADY) {
            jitToSteady(j);
         }
         int mods = enabledMods();
         int heapMb = heapMb(wantMb(Config.GC_HEAP, mods));
         String kept = "";
         if (Overrides.enabled()) {
            kept = heapApply(j, Config.GC_HEAP, heapMb, Config.GC_HEAP_FIXED, Config.GC_PRE_TOUCH);
         } else {
            heapToStock(j);
         }
         boolean changed = !j.toString().equals(before);
         Log.info("gc: running " + currentGc() + "; gcMode=" + Config.GC_MODE + " gcPauseMs=" + Config.GC_PAUSE_MS + " gcG1Tuned=" + Config.GC_G1_TUNED + " ("
               + Runtime.getRuntime().availableProcessors() + " cores) -> next launch " + (want ? tune ? "G1 tuned (" + TUNE_PAUSE_MS + " ms target unless gcPauseMs)" : "G1" : "the launcher's own collector")
               + "; heap now " + (Runtime.getRuntime().maxMemory() >> 20) + " MB max, gcHeap=" + Config.GC_HEAP + " (" + mods + " mods) -> "
               + (heapMb > 0 ? heapMb + " MB" : "the launcher's own")
               + (heapMb > 0 && heapMb != wantMb(Config.GC_HEAP, mods) ? " (clamped to half of " + (physicalMb() >> 10) + " GB RAM)" : "")
               + kept
               + " gcHeapFixed=" + Config.GC_HEAP_FIXED + " gcPreTouch=" + Config.GC_PRE_TOUCH
               + (changed ? " (launcher JSON updated)" : ""));
         if (changed) {
            save(game, j);
         }
      } catch (Throwable e) {
         Log.warn("gc: " + e);
      }
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
      return toG1(j, pauseMs, false);
   }

   /** ZGC -> G1 (+ pause target, + the gcG1Tuned set when {@code tune}) + markers, in every vmArgs array that selects ZGC. */
   static boolean toG1(JSONObject j, int pauseMs, boolean tune) {
      boolean[] changed = {false};
      forEachVmArgsDeep(j, args -> {
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
         if (tune) {
            // IgnoreUnrecognizedVMOptions is read before the other options (its place does not matter); the unlock must
            // come before the experimental flags (HotSpot refuses the launch otherwise)
            StringBuilder mark = new StringBuilder(TUNE_MARKER).append("g1");
            if (indexOf(args, IGNORE) < 0) {
               args.put(IGNORE);
               mark.append(",ignore");
            }
            if (indexOf(args, UNLOCK) < 0) {
               args.put(UNLOCK);
               mark.append(",unlock");
            }
            for (String f : TUNE_FLAGS) {
               args.put(f);
            }
            args.put(mark.toString());
         }
         changed[0] = true;
      });
      return changed[0];
   }

   /** jitSteady: the marker and the flags, in every vmArgs array (the flags a user set himself stay: we only add ours). */
   static void jitToSteady(JSONObject j) {
      forEachVmArgs(j, args -> {
         if (indexOf(args, JIT_MARKER) >= 0) {
            return;
         }
         for (int i = 0; i < args.length(); i++) {
            if (isJitFlag(args.optString(i))) {
               return; // the player tunes one of these flags himself
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
      forEachVmArgs(j, args -> {
         if (indexOf(args, JIT_MARKER) < 0) {
            return;
         }
         for (int i = args.length() - 1; i >= 0; i--) {
            String a = args.optString(i);
            if (a.equals(JIT_MARKER) || isJitFlag(a)) {
               args.remove(i);
            }
         }
      });
   }

   /** Undo toG1 where one of our markers is (the gcG1Tuned set first). */
   static boolean toStock(JSONObject j) {
      boolean[] changed = {tuneToStock(j)};
      forEachVmArgsDeep(j, args -> {
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

   /** Undo the gcG1Tuned set where its marker is: the marker, TUNE_FLAGS, and the unlock / ignore flags it says it added. */
   static boolean tuneToStock(JSONObject j) {
      boolean[] changed = {false};
      forEachVmArgsDeep(j, args -> {
         int m = lastPrefixIndex(args, TUNE_MARKER);
         if (m < 0) {
            return;
         }
         java.util.List<String> added = java.util.Arrays.asList(args.optString(m).substring(TUNE_MARKER.length()).split(","));
         for (int i = args.length() - 1; i >= 0; i--) {
            if (args.optString(i).startsWith(TUNE_MARKER)) {
               args.remove(i);
            }
         }
         for (String f : TUNE_FLAGS) {
            int i = lastIndexOf(args, f);
            if (i >= 0) {
               args.remove(i);
            }
         }
         if (added.contains("unlock")) {
            int i = lastIndexOf(args, UNLOCK);
            if (i >= 0) {
               args.remove(i);
            }
         }
         if (added.contains("ignore")) {
            int i = lastIndexOf(args, IGNORE);
            if (i >= 0) {
               args.remove(i);
            }
         }
         changed[0] = true;
      });
      return changed[0];
   }

   /** Whether {@code a} sets one of JIT_FLAGS' flags (any value, either sign). */
   static boolean isJitFlag(String a) {
      String n = flagName(a);
      if (n == null) {
         return false;
      }
      for (String f : JIT_FLAGS) {
         if (n.equals(flagName(f))) {
            return true;
         }
      }
      return false;
   }

   /** The flag name of a -XX argument (-XX:+Name, -XX:-Name, -XX:Name=value), or null. */
   static String flagName(String a) {
      if (!a.startsWith("-XX:")) {
         return null;
      }
      String n = a.substring(4);
      if (n.startsWith("+") || n.startsWith("-")) {
         n = n.substring(1);
      }
      int eq = n.indexOf('=');
      return eq >= 0 ? n.substring(0, eq) : n;
   }

   /** Whether any vmArgs array (any depth) already sets one of TUNE_FLAGS' flags: the player's own G1 tuning wins. */
   static boolean playerTunes(JSONObject j) {
      java.util.Set<String> ours = new java.util.HashSet<>();
      for (String f : TUNE_FLAGS) {
         ours.add(flagName(f));
      }
      boolean[] found = {false};
      forEachVmArgsDeep(j, args -> {
         for (int i = 0; i < args.length(); i++) {
            String n = flagName(args.optString(i));
            found[0] |= n != null && ours.contains(n);
         }
      });
      return found[0];
   }

   /** The running Java is the release TUNE_FLAGS was checked on, and it knows each flag (an unknown one stops the JVM). */
   static boolean tuneSupported() {
      if (Runtime.version().feature() != TUNE_FEATURE) {
         Log.info("gc: Java " + Runtime.version() + ", the tuned G1 set was checked on Java " + TUNE_FEATURE + ": plain G1 for the next launch");
         return false;
      }
      try {
         com.sun.management.HotSpotDiagnosticMXBean hs = ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
         boolean unlocked = "true".equals(hs.getVMOption("UnlockExperimentalVMOptions").getValue());
         hs.getVMOption("IgnoreUnrecognizedVMOptions");
         for (String f : TUNE_FLAGS) {
            String n = flagName(f);
            if (!unlocked && TUNE_EXPERIMENTAL.contains(n)) {
               continue; // a locked experimental flag is not listed until unlocked
            }
            hs.getVMOption(n); // IllegalArgumentException for a flag this JVM does not have
         }
         return true;
      } catch (Throwable e) {
         Log.info("gc: the tuned G1 set does not fit this Java (" + e + "): plain G1 for the next launch");
         return false;
      }
   }

   /** pzopt.Uninstall: the launcher's own collector and JIT flags again (both undos); true when the JSON changed. */
   static boolean undo(Path game) throws java.io.IOException {
      Path json = game.resolve("ProjectZomboid64.json");
      if (!Files.isRegularFile(json)) {
         return false;
      }
      JSONObject j = new JSONObject(Files.readString(json, StandardCharsets.UTF_8));
      String before = j.toString();
      toStock(j);
      jitToStock(j);
      heapToStock(j);
      if (j.toString().equals(before)) {
         return false;
      }
      save(game, j);
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

   /** heapApply with an explicit size ({@code heapMb} MB, 0 = gcHeap=game). */
   static void heapToPzopt(JSONObject j, int heapMb, boolean fixed, boolean preTouch) {
      heapApply(j, heapMb > 0 ? String.valueOf(heapMb) : "game", heapMb, fixed, preTouch);
   }

   /**
    * gcHeap / gcHeapFixed / gcPreTouch into every vmArgs array (each array is first put back to what it is without us, the
    * player's hand edits kept, see {@link #heapArray}). {@code setting} is gcHeap, {@code heapMb} its size after the RAM
    * clamp (0 = the launcher's own). Returns what was left to the player, for the log ("" when nothing).
    */
   static String heapApply(JSONObject j, String setting, int heapMb, boolean fixed, boolean preTouch) {
      String key = setting.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
      StringBuilder kept = new StringBuilder();
      forEachVmArgs(j, args -> heapArray(args, key, heapMb, fixed, preTouch, kept));
      return kept.toString();
   }

   /**
    * One vmArgs array. Undo (setting null): where our marker is, every flag we wrote that still holds what we wrote gets
    * its old value back; a flag that differs from what we wrote was changed by hand since and stays (2026-10-19, Workshop:
    * "I set -Xmx12g in the .json, your mod reverts it to 3g": the old undo put the marker's 3072m back over any edit, at
    * every launch and in every uninstaller). Apply (setting set): after that undo, gcHeap is written, except
    * <ul>
    * <li>over a flag the player changed by hand: his from then on, kept as "p" in the marker while gcHeap stays the
    * setting it was seen under (a new menu choice applies again, the undo leaves it);</li>
    * <li>with gcHeap=auto over an -Xmx at or above auto's size (or one that does not parse): auto only ever raises the
    * heap, a player's 12 GB is never lowered, the half-RAM cap included (it limits only what we write).</li>
    * </ul>
    */
   private static void heapArray(JSONArray args, String setting, int heapMb, boolean fixed, boolean preTouch, StringBuilder kept) {
      boolean redo = setting != null;
      boolean auto = "auto".equals(setting);
      boolean ownMx = false;
      boolean ownMs = false;
      int m = lastPrefixIndex(args, HEAP_MARKER);
      if (m >= 0) {
         String[] f = args.optString(m).substring(HEAP_MARKER.length()).split(",", -1);
         boolean legacy = f.length < 5; // a marker from before 2026-10-19: <old -Xmx>,<old -Xms>,<pre-touch> only
         for (int i = args.length() - 1; i >= 0; i--) {
            if (args.optString(i).startsWith(HEAP_MARKER)) {
               args.remove(i);
            }
         }
         String wroteFor = field(f, 5, "");
         ownMx = undoFlag(args, XMX, field(f, 0, "none"), legacy ? null : field(f, 3, HEAP_LEFT), setting, wroteFor, redo && auto ? heapMb : 0);
         ownMs = undoFlag(args, XMS, field(f, 1, "none"), legacy ? null : field(f, 4, HEAP_LEFT), setting, wroteFor, 0);
         if (field(f, 2, "0").equals("1")) {
            int t = indexOf(args, PRETOUCH);
            if (t >= 0) {
               args.remove(t);
            }
         }
      }
      if (!redo) {
         return;
      }
      int mx = lastPrefixIndex(args, XMX);
      int ms = lastPrefixIndex(args, XMS);
      String baseMx = mx < 0 ? "none" : args.optString(mx).substring(XMX.length());
      String baseMs = ms < 0 ? "none" : args.optString(ms).substring(XMS.length());
      String newMx = null;
      if (ownMx) {
         kept.append(", -Xmx").append(baseMx).append(" kept (changed by hand)");
      } else if (heapMb > 0) {
         long cur = mx < 0 ? -1 : sizeMb(baseMx);
         if (auto && mx >= 0 && (cur < 0 || cur >= heapMb)) {
            if (cur != heapMb) {
               kept.append(", -Xmx").append(baseMx).append(" kept (auto never lowers a heap)");
            }
         } else {
            newMx = heapMb + "m";
         }
      }
      String newMs = null;
      boolean mxFromMs = false;
      if (ownMs) {
         kept.append(", -Xms").append(baseMs).append(" kept (changed by hand)");
         String effMx = newMx != null ? newMx : mx >= 0 ? baseMx : null;
         if (!ownMx && effMx != null && sizeMb(baseMs) > sizeMb(effMx)) {
            // the player's own -Xms above the -Xmx: the JVM would refuse to start. His minimum wins: the -Xmx rises to it
            // and is his from then on ("p", the old value his), so no undo puts a smaller -Xmx under his -Xms
            newMx = baseMs;
            mxFromMs = true;
         }
      } else {
         String effMx = newMx != null ? newMx : mx >= 0 ? baseMx : null;
         if (fixed && effMx != null) {
            newMs = effMx;
         } else if (newMx != null && ms >= 0 && sizeMb(baseMs) > sizeMb(newMx)) {
            // a player's own -Xms above the new -Xmx: the JVM refuses to start ("Initial heap size set to a larger value
            // than the maximum heap size") before console.txt exists, and every launch after it fails the same way
            // (Discord, 2026-10-17: -Xms5120m -Xmx5120m with gcHeap=4096). The marker keeps the old -Xms for the undo.
            newMs = newMx;
         }
      }
      if (newMx != null && newMx.equals(baseMx)) {
         newMx = null;
      }
      if (newMs != null && newMs.equals(baseMs)) {
         newMs = null;
      }
      boolean addTouch = preTouch && indexOf(args, PRETOUCH) < 0;
      if (newMx == null && newMs == null && !addTouch && !ownMx && !ownMs && !mxFromMs) {
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
      args.put(HEAP_MARKER + (mxFromMs ? baseMs : baseMx) + "," + baseMs + "," + (addTouch ? 1 : 0) + ","
            + (mxFromMs || ownMx ? HEAP_PLAYER : newMx != null ? newMx : HEAP_LEFT)
            + "," + (newMs != null ? newMs : ownMs ? HEAP_PLAYER : HEAP_LEFT) + "," + setting);
   }

   /**
    * One flag of a marked array back to its state without us; true when it is the player's own. {@code wrote}: what we wrote
    * (HEAP_LEFT: we left it, HEAP_PLAYER: the player's, remembered while gcHeap is still {@code wroteFor}; null: a marker
    * from before 2026-10-19, which does not say: we only ever wrote "&lt;N&gt;m" sizes of 512 MB steps from 1 GB, any other
    * value is the player's, and with gcHeap=auto ({@code autoMb} &gt; 0) a value above auto's size that is not one of auto's
    * own sizes is kept as his too: a "12288m" typed by hand is never lowered, at worst a 12 GB menu choice made before
    * switching back to auto stays once).
    */
   private static boolean undoFlag(JSONArray args, String flag, String old, String wrote, String setting, String wroteFor, int autoMb) {
      int i = lastPrefixIndex(args, flag);
      String cur = i < 0 ? "none" : args.optString(i).substring(flag.length());
      if (wrote == null) {
         long size = sizeMb(cur);
         boolean autoSize = size == heapMb(Config.GC_HEAP_AUTO_MB) || size == heapMb(Config.GC_HEAP_AUTO_MODS_MB);
         if (!legacyOurs(cur) || autoMb > 0 && size > autoMb && !autoSize) {
            return true;
         }
         restore(args, flag, old);
         return false;
      }
      if (wrote.equals(HEAP_LEFT)) {
         return false;
      }
      if (wrote.equals(HEAP_PLAYER)) {
         return wroteFor.equals(setting);
      }
      if (cur.equals(wrote)) {
         restore(args, flag, old);
         return false;
      }
      return true; // changed by hand after we wrote it
   }

   /** A legacy marker's flag value we could have written: "&lt;N&gt;m", N a multiple of 512 from 1024 (heapMb's sizes). */
   static boolean legacyOurs(String v) {
      if (!v.matches("[0-9]{4,9}m")) {
         return false;
      }
      long n = Long.parseLong(v.substring(0, v.length() - 1));
      return n >= 1024 && n % 512 == 0;
   }

   private static String field(String[] f, int i, String def) {
      return i < f.length && !f[i].isEmpty() ? f[i] : def;
   }

   /** A -Xmx / -Xms size ("5120m", "6g", "4194304k", bytes) in MB; -1 when it does not parse. */
   static long sizeMb(String v) {
      if (v == null || v.isEmpty()) {
         return -1;
      }
      char u = Character.toLowerCase(v.charAt(v.length() - 1));
      String n = Character.isDigit(u) ? v : v.substring(0, v.length() - 1);
      try {
         long x = Long.parseLong(n);
         return switch (u) {
            case 't' -> x << 20;
            case 'g' -> x << 10;
            case 'm' -> x;
            case 'k' -> x >> 10;
            default -> Character.isDigit(u) ? x >> 20 : -1;
         };
      } catch (NumberFormatException e) {
         return -1;
      }
   }

   /** The first vmArgs array whose last -Xms is larger than its last -Xmx (the JVM would refuse to start), else null. */
   static String heapConflict(JSONObject j) {
      String[] bad = {null};
      forEachVmArgsDeep(j, args -> {
         int mx = lastPrefixIndex(args, XMX);
         int ms = lastPrefixIndex(args, XMS);
         if (bad[0] == null && mx >= 0 && ms >= 0) {
            long x = sizeMb(args.optString(mx).substring(XMX.length()));
            long s = sizeMb(args.optString(ms).substring(XMS.length()));
            if (x > 0 && s > x) {
               bad[0] = args.optString(ms) + " > " + args.optString(mx);
            }
         }
      });
      return bad[0];
   }

   /**
    * Undo heapApply where its marker is: our pre-touch flag removed, the old -Xmx / -Xms back (or gone) where the flag still
    * holds what we wrote; a flag changed by hand since stays as the player left it.
    */
   static void heapToStock(JSONObject j) {
      forEachVmArgs(j, args -> heapArray(args, null, 0, false, false, null));
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

   private static int lastIndexOf(JSONArray a, String s) {
      for (int i = a.length() - 1; i >= 0; i--) {
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

   /** The top-level vmArgs and every per-platform section's ("windows": {"vmArgs": ...}). */
   private static void forEachVmArgs(JSONObject j, java.util.function.Consumer<JSONArray> f) {
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

   /**
    * Every vmArgs array at any depth: the collector. The Windows JSON picks it per OS version one level further down
    * ("windows": {"10.0.17134": {"vmArgs": ["-XX:+UseZGC"]}}), which {@link #forEachVmArgs} never reached, so G1 was
    * never switched on there (Windows test 2026-10-11). The heap and JIT flags stay in the arrays forEachVmArgs walks.
    */
   static void forEachVmArgsDeep(JSONObject j, java.util.function.Consumer<JSONArray> f) {
      JSONArray a = j.optJSONArray("vmArgs");
      if (a != null) {
         f.accept(a);
      }
      for (String k : j.keySet()) {
         JSONObject sec = j.optJSONObject(k);
         if (sec != null) {
            forEachVmArgsDeep(sec, f);
         }
      }
   }

   private static void save(Path game, JSONObject j) throws java.io.IOException {
      Path json = game.resolve("ProjectZomboid64.json");
      Path backup = game.resolve("ProjectZomboid64.json.pzopt-backup");
      if (!Files.exists(backup)) {
         Files.copy(json, backup);
      }
      Path tmp = game.resolve("ProjectZomboid64.json.pzopt-tmp");
      Files.writeString(tmp, j.toString(1) + "\n", StandardCharsets.UTF_8);
      JSONObject check = new JSONObject(Files.readString(tmp, StandardCharsets.UTF_8));
      if (!check.has("mainClass") || check.getJSONArray("classpath").length() == 0 || check.getJSONArray("vmArgs").length() == 0) {
         Files.deleteIfExists(tmp);
         throw new java.io.IOException("launcher JSON check failed; left unchanged");
      }
      String conflict = heapConflict(check);
      if (conflict != null) {
         Files.deleteIfExists(tmp);
         throw new java.io.IOException("launcher JSON would not start the JVM (" + conflict + "); left unchanged");
      }
      AotCache.replaceJson(tmp, json); // in place when the running launcher holds the file (Windows)
   }
}
