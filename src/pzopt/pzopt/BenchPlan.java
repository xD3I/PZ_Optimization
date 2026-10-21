package pzopt;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The built-in benchmark's plan (docs/plan-builtin-benchmark.md), the only state that survives between its processes:
 * {@code Zomboid/pzopt/benchmark/plan.properties}. One pass of one scenario runs per process (Harness keeps its route in
 * statics, read once); the process that starts the benchmark writes the plan and restarts the game, every process
 * claims the next step at class init ({@link #decide}, from Config and HarnessFlags, JDK only: Config initialises
 * before the game's file system), runs it, advances the plan at the route end and restarts the game again; the last
 * one restarts into the report.
 *
 * Keys: run (id), state (running | report | done | aborted), steps (scenario/config/pass,...; config A = PZ
 * Optimization as the player set it, B = stock, W = warm-up, not timed), step (next index), claim / claim_pid /
 * attempts (crash guard: a process that dies before advancing leaves its claim; the next one counts an attempt and
 * aborts the whole run at {@link #MAX_ATTEMPTS}: a world that cannot load fails every step the same way, and skipping
 * step by step was two launches per step with no way out, Workshop "endless benchmark bootloop" 2026-10-18), build (the
 * pzopt commit that planned the run: another build aborts it), options (uncapped, timedemo, cooldown, screenshot, hud,
 * auto), updated_ms (progress only, never a claim: a plan untouched for {@link #STALE_MS} is abandoned, so a player who
 * quit mid-run gets a normal game). A running plan is also aborted by the player (Escape / a click / B during the pass's
 * countdown at the main menu, Benchmark "cancel"), by Uninstall and by BootRepair ({@link #abandon}).
 */
public final class BenchPlan {
   static final int MAX_ATTEMPTS = 2;
   static final long STALE_MS = 20L * 60L * 1000L;
   /** World name of a benchmark pass (Saves/Sandbox/<name>); one per pass, deleted by the next process. */
   static final String WORLD = "pzopt-benchmark";

   private static volatile boolean decided;
   /** This process's step, or -1 when it runs none. */
   private static int myStep = -1;
   private static Properties plan;
   private static String[] myParts;

   private BenchPlan() {
   }

   static File dir() {
      return new File(UserOptions.zomboidDir(), "pzopt" + File.separator + "benchmark");
   }

   static File planFile() {
      return new File(dir(), "plan.properties");
   }

   static File runDir(String run) {
      return new File(dir(), "runs" + File.separator + run);
   }

   static synchronized Properties read() {
      Properties p = new Properties();
      File f = planFile();
      if (f.isFile()) {
         try (InputStream in = new FileInputStream(f)) {
            p.load(in);
         } catch (IOException e) {
            Log.warn("benchmark: could not read " + f + ": " + e);
         }
      }
      return p;
   }

   /** Written whole beside the plan, then renamed over it: a crash mid-write never leaves half a plan. Stamps progress. */
   static synchronized void write(Properties p) {
      write(p, true);
   }

   /**
    * A step's claim: written without the progress stamp, so a player relaunching a game that dies in every pass does
    * not keep the plan fresh for the stale rule (the claim is the only write of a process that never got anywhere).
    */
   static synchronized void writeClaim(Properties p) {
      write(p, false);
   }

   private static synchronized void write(Properties p, boolean progress) {
      File f = planFile();
      f.getParentFile().mkdirs();
      if (progress || p.getProperty("updated_ms") == null) {
         p.setProperty("updated_ms", Long.toString(System.currentTimeMillis()));
      }
      File tmp = new File(f.getParentFile(), "plan.properties.tmp");
      try (OutputStream out = new FileOutputStream(tmp)) {
         p.store(out, "PZ Optimization built-in benchmark");
      } catch (IOException e) {
         Log.warn("benchmark: could not write " + tmp + ": " + e);
         return;
      }
      try {
         Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException e) {
         try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
         } catch (IOException e2) {
            Log.warn("benchmark: could not replace " + f + ": " + e2);
         }
      }
   }

   static List<String[]> steps(Properties p) {
      List<String[]> out = new ArrayList<>();
      for (String s : p.getProperty("steps", "").split(",")) {
         String[] parts = s.trim().split("/");
         if (parts.length == 3 && BenchSuite.byId(parts[0]) != null) {
            out.add(parts);
         }
      }
      return out;
   }

   /** Decides once per process whether it runs a benchmark step, claiming it in the plan. Safe from any class init. */
   static void decide() {
      if (decided) {
         return;
      }
      synchronized (BenchPlan.class) {
         if (decided) {
            return;
         }
         try {
            decideLocked();
         } catch (RuntimeException e) {
            Log.warn("benchmark: plan unreadable, ignored: " + e);
            myStep = -1;
         }
         decided = true;
      }
   }

   private static void decideLocked() {
      if (new File(UserOptions.zomboidDir(), "Lua" + File.separator + "pzopt-harness.txt").isFile()) {
         return; // a harness/run.sh run owns this process; a running plan waits for the next launch
      }
      Properties p = read();
      String state = p.getProperty("state", "");
      boolean finishedUncleaned = ("done".equals(state) || "aborted".equals(state)) && !"1".equals(p.getProperty("cleaned"));
      if ("running".equals(state) || "report".equals(state) || finishedUncleaned) {
         // the pass before this process is over: its world goes, Continue points at the player's save again, and the
         // options file the feature sweep switched is the player's own again (before Config reads it)
         deleteWorlds();
         restoreLatestSave(p);
         restoreUserOptions(p);
         restoreFile(p, "game_options", new File(UserOptions.zomboidDir(), "options.ini"));
         if (finishedUncleaned) {
            p.setProperty("cleaned", "1"); // once: later changes to the player's files are theirs
            write(p);
         }
      }
      if (!"running".equals(state)) {
         plan = p;
         return;
      }
      String planned = p.getProperty("build", "");
      String build = currentBuild();
      if (!planned.isEmpty() && !build.isEmpty() && !planned.equals(build)) {
         Log.warn("benchmark: plan " + p.getProperty("run") + " was made by build " + planned + ", this is " + build + "; abandoned");
         abortNow(p, "the installed build changed (" + planned + " -> " + build + ")");
         plan = p;
         return;
      }
      long updated = Long.parseLong(p.getProperty("updated_ms", "0"));
      if (System.currentTimeMillis() - updated > STALE_MS) {
         Log.warn("benchmark: plan " + p.getProperty("run") + " untouched for " + (System.currentTimeMillis() - updated) / 60000 + " min; abandoned");
         p.setProperty("state", "aborted");
         p.setProperty("abort_reason", "stale");
         write(p);
         restoreLatestSave(p);
         restoreUserOptions(p);
         restoreFile(p, "game_options", new File(UserOptions.zomboidDir(), "options.ini"));
         plan = p;
         return;
      }
      List<String[]> steps = steps(p);
      int step = Integer.parseInt(p.getProperty("step", "0"));
      int claim = Integer.parseInt(p.getProperty("claim", "-1"));
      int attempts = Integer.parseInt(p.getProperty("attempts", "0"));
      if (claim == step) {
         // the process that claimed this step ended without advancing: a crash, a kill, or a quit by hand
         attempts++;
         Log.warn("benchmark: step " + step + " did not finish in the last process (attempt " + attempts + ")");
         if (attempts >= MAX_ATTEMPTS) {
            // the run, not the step: a world that cannot load (a mod, a crash) fails every step alike, and every launch
            // would be another pass until the plan ran out (Workshop, 2026-10-18: "endless benchmark bootloop")
            String why = "the game ended during pass " + (step + 1) + " of " + steps.size() + " (" + String.join("/", steps.get(step)) + ") "
                  + attempts + " times in a row";
            BenchResults.writeFailed(p.getProperty("run"), step, steps.get(step), why);
            Log.warn("benchmark: run " + p.getProperty("run") + " abandoned: " + why + "; this is a normal game");
            abortNow(p, why);
            plan = p;
            return;
         }
      } else {
         attempts = 0;
      }
      if (step >= steps.size()) {
         p.setProperty("step", Integer.toString(step));
         p.setProperty("state", "report");
         p.setProperty("claim", "-1");
         write(p);
         plan = p;
         return;
      }
      p.setProperty("step", Integer.toString(step));
      p.setProperty("claim", Integer.toString(step));
      p.setProperty("claim_pid", Long.toString(ProcessHandle.current().pid()));
      p.setProperty("attempts", Integer.toString(attempts));
      writeClaim(p);
      myStep = step;
      myParts = steps.get(step);
      plan = p;
      restoreAtExit(p); // a pass process only: the report process is the player's menu again, their changes there stay
      Log.info("benchmark: this process runs step " + step + " of " + steps.size() + ": " + String.join("/", myParts) + " (run " + p.getProperty("run") + ")");
   }

   /** The pzopt commit of this build, "" when unknown (a plan carries the one that made it). */
   static String currentBuild() {
      String c = BuildInfo.get("commit");
      return c == null ? "" : c.trim();
   }

   /**
    * Ends a run: the state, the reason, and the player's files back (latestSave.ini, their options file, the game's
    * options.ini). The worlds of the passes go at the next normal start (the finished-uncleaned branch of decide).
    */
   static synchronized void abort(Properties p, String why) {
      p.setProperty("state", "aborted");
      p.setProperty("abort_reason", why == null || why.isEmpty() ? "cancelled" : why);
      write(p);
      restoreLatestSave(p);
      restoreUserOptions(p);
      restoreFile(p, "game_options", new File(UserOptions.zomboidDir(), "options.ini"));
   }

   /** abort, plus what the next start would otherwise do: the passes' worlds deleted, the plan marked cleaned. */
   private static synchronized void abortNow(Properties p, String why) {
      abort(p, why);
      p.setProperty("cleaned", "1");
      write(p);
      deleteWorlds();
   }

   /**
    * Aborts a running or unreported plan now, with its worlds: Uninstall (no pzopt process comes after it to clean up)
    * and the player's cancel. Returns the aborted run's id, or null when nothing was running.
    */
   static synchronized String abortRunning(String why) {
      Properties p = read();
      String state = p.getProperty("state", "");
      if (!"running".equals(state) && !"report".equals(state)) {
         return null;
      }
      abortNow(p, why);
      if (plan != null && plan != p) {
         plan.setProperty("state", "aborted");
      }
      Log.info("benchmark: run " + p.getProperty("run") + " aborted: " + why);
      return p.getProperty("run");
   }

   /**
    * BootRepair's abandon (JDK only, before any game code; the folder given, not UserOptions): a running plan is marked
    * aborted so the repaired or removed install never resumes a benchmark the player cannot stop. The player's files
    * come back at the next pzopt start (finished-uncleaned branch); a stock game after a removal reads its own options.
    * Returns a log line, or null when no plan was running.
    */
   static String abandon(File zomboidDir, String reason) {
      File f = new File(zomboidDir, "pzopt" + File.separator + "benchmark" + File.separator + "plan.properties");
      if (!f.isFile()) {
         return null;
      }
      Properties p = new Properties();
      try (InputStream in = new FileInputStream(f)) {
         p.load(in);
      } catch (IOException e) {
         return "benchmark plan unreadable (" + e + ")";
      }
      String state = p.getProperty("state", "");
      if (!"running".equals(state) && !"report".equals(state)) {
         return null;
      }
      p.setProperty("state", "aborted");
      p.setProperty("abort_reason", reason);
      p.setProperty("updated_ms", Long.toString(System.currentTimeMillis()));
      try (OutputStream out = new FileOutputStream(f)) {
         p.store(out, "PZ Optimization built-in benchmark");
      } catch (IOException e) {
         return "benchmark plan " + p.getProperty("run") + " could not be marked aborted (" + e + ")";
      }
      return "benchmark run " + p.getProperty("run") + " was " + state + "; aborted (" + reason + ")";
   }

   /** Tests only: the next decide() reads the plan again. */
   static synchronized void resetForTests() {
      decided = false;
      myStep = -1;
      myParts = null;
      plan = null;
   }

   /** True when this process runs a benchmark step. */
   public static boolean active() {
      decide();
      return myStep >= 0;
   }

   static int step() {
      decide();
      return myStep;
   }

   static String[] parts() {
      decide();
      return myParts;
   }

   static Properties plan() {
      decide();
      return plan;
   }

   static boolean option(String key) {
      Properties p = plan();
      return p != null && "1".equals(p.getProperty(key, "0"));
   }

   static BenchSuite.Scenario scenario() {
      return active() ? BenchSuite.byId(myParts[0]) : null;
   }

   /** This pass's timedemo step (route steps a second), 0 when the route runs on the wall clock. */
   static int timedemoStep;

   /**
    * The timedemo step: the frame rate of this scenario's real-time warm-up pass in this run (Benchmark.start adds the
    * warm-up whenever timedemo is on). Without one (the warm-up failed) the pass runs on the wall clock.
    */
   private static int calibratedStep() {
      for (Map<String, String> r : BenchResults.readSteps(plan.getProperty("run"))) {
         if (myParts[0].equals(r.get("scenario")) && "W".equals(r.get("config")) && "complete".equals(r.get("route_status"))) {
            double fps = BenchResults.d(r, "fps");
            if (fps > 0) {
               int step = (int)Math.max(20, Math.min(2000, Math.round(fps)));
               Log.info("benchmark: timedemo step " + step + " a second (the warm-up pass's frame rate)");
               return step;
            }
         }
      }
      Log.warn("benchmark: timedemo without a warm-up result; this pass runs on the wall clock");
      return 0;
   }

   /** The harness flags of this process's step, or null. Read by HarnessFlags when there is no harness flag file. */
   static Properties harnessFlags() {
      if (!active()) {
         return null;
      }
      Properties f = new Properties();
      timedemoStep = option("timedemo") && !"W".equals(myParts[1]) && BenchSuite.timedemoApplies(scenario()) ? calibratedStep() : 0;
      for (Map.Entry<String, String> e : BenchSuite.flagsFor(scenario(), timedemoStep).entrySet()) {
         f.setProperty(e.getKey(), e.getValue());
      }
      return f;
   }

   /**
    * Settings this process pins above the player's options.ini (Config treats them like pzopt.properties): the stock
    * pass switches PZ Optimization off, the overlay stays hidden (observer effect, technique 26), the frame log runs.
    */
   static Map<String, String> configPins() {
      Map<String, String> m = new LinkedHashMap<>();
      if (!active()) {
         return m;
      }
      m.put("overlay", "false");
      m.put("overlayLog", "true");
      m.put("pacingLog", "true");
      m.put("updateCheck", "false");
      if ("B".equals(myParts[1])) {
         m.put("enabled", "false");
      }
      if (option("uncapped")) {
         m.put("uncappedFps", "true");
      }
      return m;
   }

   /** Saves the player's latestSave.ini before the first pass (the passes' worlds replace it). */
   static void backupLatestSave(Properties p) {
      File src = new File(UserOptions.zomboidDir(), "latestSave.ini");
      if (src.isFile()) {
         try {
            p.setProperty("latest_save", new String(Files.readAllBytes(src.toPath()), StandardCharsets.UTF_8));
         } catch (IOException e) {
            Log.warn("benchmark: could not read " + src + ": " + e);
         }
      }
   }

   /** Saves the player's PZ Optimization options file: the feature sweep switches settings live (and so writes it). */
   static void backupUserOptions(Properties p) {
      File f = UserOptions.file();
      try {
         p.setProperty("user_options", f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "");
         p.setProperty("user_options_existed", f.isFile() ? "1" : "0");
      } catch (IOException e) {
         Log.warn("benchmark: could not read " + f + ": " + e);
      }
   }

   private static boolean exitHook;

   /**
    * A pass process puts the player's options files back once more when it ends. The game saves options.ini from many places
    * (a zoom change, player events, its own exit) with what it holds in memory, and a pass holds v-sync off and the
    * frame rate uncapped: the last process of an unattended run quit with those saved, and the restore of the next
    * launch never came (2026-10-18: v-sync left off on the desktop, v-sync and 120 fps lost on the Mac).
    */
   private static synchronized void restoreAtExit(Properties p) {
      if (exitHook) {
         return;
      }
      exitHook = true;
      Properties backup = (Properties)p.clone();
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
         restoreUserOptions(backup);
         restoreFile(backup, "game_options", new File(UserOptions.zomboidDir(), "options.ini"));
      }, "pzopt-bench-restore"));
   }

   static void restoreUserOptions(Properties p) {
      String v = p.getProperty("user_options");
      if (v == null) {
         return;
      }
      File f = UserOptions.file();
      try {
         if ("0".equals(p.getProperty("user_options_existed"))) {
            Files.deleteIfExists(f.toPath());
         } else if (!f.isFile() || !v.equals(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))) {
            Files.write(f.toPath(), v.getBytes(StandardCharsets.UTF_8));
         }
      } catch (IOException e) {
         Log.warn("benchmark: could not restore " + f + ": " + e);
      }
   }

   /** A player file the passes may rewrite (the game's options.ini: an uncapped pass turns v-sync off in memory). */
   static void backupFile(Properties p, String key, File f) {
      try {
         if (f.isFile()) {
            p.setProperty(key, new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
         }
      } catch (IOException e) {
         Log.warn("benchmark: could not read " + f + ": " + e);
      }
   }

   static void restoreFile(Properties p, String key, File f) {
      String v = p.getProperty(key);
      if (v == null) {
         return;
      }
      try {
         if (!f.isFile() || !v.equals(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))) {
            Files.write(f.toPath(), v.getBytes(StandardCharsets.UTF_8));
            Log.info("benchmark: " + f.getName() + " restored to the player's copy");
         }
      } catch (IOException e) {
         Log.warn("benchmark: could not restore " + f + ": " + e);
      }
   }

   /** Puts the player's latestSave.ini back, so Continue loads their game, not a benchmark world. */
   static void restoreLatestSave(Properties p) {
      String v = p.getProperty("latest_save");
      if (v == null) {
         return;
      }
      File dst = new File(UserOptions.zomboidDir(), "latestSave.ini");
      try {
         Files.write(dst.toPath(), v.getBytes(StandardCharsets.UTF_8));
      } catch (IOException e) {
         Log.warn("benchmark: could not restore " + dst + ": " + e);
      }
   }

   /** Deletes the benchmark worlds a previous pass left (Saves/Sandbox/pzopt-benchmark*). */
   static void deleteWorlds() {
      File saves = new File(UserOptions.zomboidDir(), "Saves" + File.separator + "Sandbox");
      File[] list = saves.listFiles((d, n) -> n.startsWith(WORLD));
      if (list == null) {
         return;
      }
      for (File f : list) {
         deleteTree(f);
      }
   }

   static void deleteTree(File f) {
      File[] kids = f.listFiles();
      if (kids != null) {
         for (File k : kids) {
            deleteTree(k);
         }
      }
      if (!f.delete() && f.exists()) {
         Log.warn("benchmark: could not delete " + f);
      }
   }
}
