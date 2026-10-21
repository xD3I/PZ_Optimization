package pzopt;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The built-in benchmark (2026-10-18, docs/plan-builtin-benchmark.md): the main menu's "Benchmark" item
 * (media/lua/client/pzopt/pzopt_benchmark.lua) starts it, every pass runs in a process of its own (BenchPlan), the last
 * process shows the report (BenchResults). This class is the glue: the Lua entry point {@link #call}, the hooks Harness
 * calls at world-ready / route start / route end, the restart between passes.
 */
public final class Benchmark {
   private static long worldRequestedNs, worldReadyNs;
   private static boolean routeEnded;
   /** The pass of this process ended (results written, plan advanced, the next process armed): the Lua quits at the menu. */
   private static volatile boolean passDone;
   private static String finishMessage = "";

   private Benchmark() {
   }

   // ------------------------------------------------------------------ Harness hooks (game thread)

   static void onWorldReady() {
      if (!BenchPlan.active()) {
         return;
      }
      worldReadyNs = System.nanoTime();
      if (!"0".equals(BenchPlan.plan().getProperty("seed", "1"))) {
         BenchSeed.seed("world-ready");
      }
   }

   static void onRouteStart() {
      if (!BenchPlan.active()) {
         return;
      }
      if (!"0".equals(BenchPlan.plan().getProperty("seed", "1"))) {
         BenchSeed.seed("route-start");
      }
      BenchSuite.Scenario sc = BenchPlan.scenario();
      if (sc != null && "sweep".equals(sc.id)) {
         BenchSweep.rebakeAll(); // every window from the same re-baked state (BenchSweep.PREROLL_S)
      }
      BenchRecorder.start();
      zombiesAtStart = zombies();
      Log.info("benchmark: route started, recording (" + zombiesAtStart + " zombies, " + vehicles() + " vehicles loaded)");
   }

   /** Called every game frame by Harness while a route runs. */
   static void onTick(long nowNs) {
      BenchRecorder.onTick();
   }

   /** Route end (or a rejected run): results, the plan's next step, the restart. Then Harness quits to the menu. */
   static void onRouteEnd(String status, String reason) {
      if (!BenchPlan.active() || routeEnded) {
         return;
      }
      routeEnded = true;
      BenchRecorder.stop();
      try {
         Properties plan = BenchPlan.plan();
         String run = plan.getProperty("run");
         int step = BenchPlan.step();
         String[] parts = BenchPlan.parts();
         LinkedHashMap<String, String> r = new LinkedHashMap<>();
         r.put("scenario", parts[0]);
         r.put("config", parts[1]);
         r.put("pass", parts[2]);
         r.put("route_status", status);
         r.put("route_reason", reason == null ? "" : reason);
         r.put("load_seconds", worldRequestedNs > 0 && worldReadyNs > worldRequestedNs ? String.format(java.util.Locale.ROOT, "%.2f", (worldReadyNs - worldRequestedNs) / 1e9) : "nan");
         r.put("zombies_start", Integer.toString(zombiesAtStart));
         r.put("idle_wait_s", String.format(java.util.Locale.ROOT, "%.1f", idleWaitS));
         r.put("zombies_end", Integer.toString(zombies()));
         r.put("vehicles_end", Integer.toString(vehicles()));
         r.putAll(Showcase.benchFields()); // the horde scenario: horde_left / horde_killed / horde_clear_s / shots ...
         r.put("boot_seconds", String.format(java.util.Locale.ROOT, "%.2f", bootSeconds));
         r.put("timedemo", BenchPlan.option("timedemo") ? "1" : "0");
         r.put("timedemo_step", Integer.toString(BenchPlan.timedemoStep));
         r.put("seeded", "0".equals(plan.getProperty("seed", "1")) ? "0" : "1");
         r.put("hud", BenchPlan.option("hud") ? "1" : "0");
         r.put("cooldown", plan.getProperty("cooldown", "0"));
         r.putAll(BenchRecorder.summary());
         for (Map.Entry<String, String> e : BenchSystem.describe().entrySet()) {
            r.put("sys." + e.getKey(), e.getValue());
         }
         r.put("ended_ms", Long.toString(System.currentTimeMillis()));
         BenchResults.validate(r);
         File dir = BenchPlan.runDir(run);
         dir.mkdirs();
         BenchResults.writeStep(run, step, r);
         if (!"W".equals(parts[1])) {
            BenchRecorder.writeFramesCsv(new File(dir, String.format("step-%02d-%s-%s-%s-frames.csv", step, parts[0], parts[1], parts[2])), "ProjectZomboid");
         }
         if (BenchPlan.option("screenshot") && !"W".equals(parts[1])) {
            try {
               // the frame after the timed window, straight into the run folder (the last pass's would come after the report)
               BenchShot.request(new File(dir, String.format("shot-%02d.png", step)));
            } catch (Throwable t) {
               Log.warn("benchmark: screenshot: " + t);
            }
         }
         Log.info("benchmark: step " + step + " " + String.join("/", parts) + " done: " + r.get("fps") + " fps, 1% low " + r.get("low1_avg") + ", status " + r.get("status"));
         advance(plan, step);
      } catch (RuntimeException e) {
         Log.warn("benchmark: could not finish the pass: " + e);
         java.io.StringWriter sw = new java.io.StringWriter();
         e.printStackTrace(new java.io.PrintWriter(sw));
         Log.warn(sw.toString());
         advance(BenchPlan.plan(), BenchPlan.step());
      }
   }

   /** The step is over: the plan moves on and a restart is armed for when this process ends. */
   private static void advance(Properties plan, int step) {
      plan.setProperty("step", Integer.toString(step + 1));
      plan.setProperty("claim", "-1");
      plan.setProperty("attempts", "0");
      List<String[]> steps = BenchPlan.steps(plan);
      if (step + 1 >= steps.size()) {
         plan.setProperty("state", "report");
      }
      BenchPlan.write(plan);
      boolean auto = "1".equals(plan.getProperty("auto", "0"));
      if (step + 1 >= steps.size() && auto) {
         BenchResults.buildReport(plan.getProperty("run")); // automation: the report now, no report screen, no restart
         finishMessage = "auto";
         plan.setProperty("state", "done");
         BenchPlan.write(plan);
         passDone = true;
         return;
      }
      if (!Restart.relaunch()) {
         Log.warn("benchmark: could not arm the restart; the benchmark stops here");
         plan.setProperty("state", "report");
         BenchPlan.write(plan);
      }
      passDone = true;
   }

   private static double bootSeconds;
   private static int zombiesAtStart = -1;

   /** The scene's size, so passes of one scenario can be checked for the same load (zombies / vehicles loaded). */
   static int zombies() {
      try {
         zombie.iso.IsoCell c = zombie.iso.IsoWorld.instance.getCell();
         return c == null ? -1 : c.getZombieList().size();
      } catch (Throwable t) {
         return -1;
      }
   }

   static int vehicles() {
      try {
         zombie.iso.IsoCell c = zombie.iso.IsoWorld.instance.getCell();
         return c == null ? -1 : c.getVehicles().size();
      } catch (Throwable t) {
         return -1;
      }
   }

   // ------------------------------------------------------------------ Lua (PerformanceSettings.pzoptBenchmark)

   /** One entry point for pzopt_benchmark.lua; strings in, strings out (tab-separated lines). */
   public static String call(String cmd, String arg) {
      try {
         return switch (cmd) {
            case "scenarios" -> scenarios();
            case "menu" -> menu();
            case "start" -> start(arg);
            case "report" -> BenchResults.reportText(arg);
            case "history" -> BenchResults.historyText();
            case "compareRuns" -> compareRuns(arg);
            case "shot" -> shot(arg);
            case "devRebuild" -> devRebuild();
            case "html" -> BenchResults.htmlPath(arg);
            case "open" -> BenchResults.open(arg);
            case "share" -> BenchResults.shareCode(arg);
            case "decode" -> BenchResults.decodeShare(arg == null ? "" : arg.trim());
            case "folder" -> BenchPlan.dir().getAbsolutePath();
            case "hud" -> hud();
            case "abort" -> abort(arg);
            case "cancel" -> cancel();
            case "isAuto" -> BenchPlan.option("auto") ? "1" : "0";
            case "reportShown" -> reportShown();
            case "auto" -> autoRequested();
            case "devUi" -> Config.DEV_BENCHMARK_UI;
            case "devCompare" -> devCompare();
            case "idle" -> idlePoll();
            default -> "error\tunknown command " + cmd;
         };
      } catch (RuntimeException e) {
         Log.warn("benchmark: " + cmd + " failed: " + e);
         return "error\t" + e;
      }
   }

   private static String scenarios() {
      StringBuilder b = new StringBuilder();
      for (BenchSuite.Scenario s : BenchSuite.ALL) {
         b.append(s.id).append('\t').append(s.title).append('\t').append(s.seconds).append('\t').append(s.what).append('\t').append(s.stresses).append('\n');
      }
      return b.toString();
   }

   /**
    * The main menu asks what to do: "" (nothing, a normal session), "world" + the pass to create, "quit" (this
    * process's pass is over; the next process is armed), "report" + run id, "cooldown" + seconds.
    */
   private static String menu() {
      Properties p = BenchPlan.plan();
      if (bootSeconds == 0) {
         bootSeconds = (System.currentTimeMillis() - java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime()) / 1000.0;
      }
      if (passDone) {
         return "quit";
      }
      if (p == null) {
         return "";
      }
      String state = p.getProperty("state", "");
      if ("report".equals(state) && !BenchPlan.active()) {
         if ("1".equals(p.getProperty("auto", "0"))) {
            BenchResults.buildReport(p.getProperty("run"));
            p.setProperty("state", "done");
            BenchPlan.write(p);
            return "quit";
         }
         BenchResults.buildReport(p.getProperty("run"));
         return "report\t" + p.getProperty("run");
      }
      if (!BenchPlan.active()) {
         return "";
      }
      BenchSuite.Scenario s = BenchPlan.scenario();
      String[] parts = BenchPlan.parts();
      int total = BenchPlan.steps(p).size();
      worldRequestedNs = System.nanoTime();
      if (BenchPlan.option("uncapped")) {
         try {
            zombie.core.Core.getInstance().setOptionVSync(false); // in memory; the next process puts options.ini back
            // the stock pass (enabled=false) has no pzopt frame cap: the game's own limiter read options.ini's frameRate
            // and held every stock pass at 60 fps (run bench-fab: "speed-ups" of 11x that were the limiter)
            zombie.core.PerformanceSettings.instance.setFramerateUncapped(true);
         } catch (Throwable t) {
            Log.warn("benchmark: could not switch v-sync off: " + t);
         }
      }
      if (!"0".equals(p.getProperty("seed", "1"))) {
         BenchSeed.seed("world-create");
      }
      String cfg = switch (parts[1]) {
         case "B" -> "stock game";
         case "W" -> "warm-up";
         default -> "PZ Optimization";
      };
      return "world\t" + s.id + '\t' + s.x + '\t' + s.y + '\t' + s.z + '\t' + s.population + '\t' + (BenchPlan.step() + 1) + '\t' + total + '\t' + s.title
            + '\t' + cfg + '\t' + parts[2] + '\t' + p.getProperty("cooldown", "0") + '\t' + BenchPlan.WORLD;
   }

   /** The menu's Start: a new plan, the latestSave backup, the restart into the first pass. */
   private static String start(String arg) {
      Map<String, String> o = new LinkedHashMap<>();
      for (String kv : arg.split(";")) {
         int eq = kv.indexOf('=');
         if (eq > 0) {
            o.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
         }
      }
      List<String> ids = new ArrayList<>();
      for (String id : o.getOrDefault("scenarios", "").split(",")) {
         if (BenchSuite.byId(id.trim()) != null) {
            ids.add(id.trim());
         }
      }
      if (ids.isEmpty()) {
         return "error\tno scenario chosen";
      }
      int passes = Math.max(1, Math.min(10, Integer.parseInt(o.getOrDefault("passes", "1"))));
      boolean ab = "ab".equals(o.get("mode"));
      // timedemo (technique 27) steps the route at this PC's frame rate, measured by a real-time warm-up pass; the stock
      // pass of an A/B runs at a fraction of that rate, so the two would not share a step: an A/B runs on the wall clock
      boolean timedemo = "1".equals(o.get("timedemo")) && !ab;
      boolean warmup = "1".equals(o.get("warmup")) || timedemo;
      String steps = BenchOrder.steps(ids, passes, ab, warmup);
      Properties p = new Properties();
      String run = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
      p.setProperty("run", run);
      p.setProperty("state", "running");
      p.setProperty("steps", steps);
      p.setProperty("step", "0");
      p.setProperty("claim", "-1");
      p.setProperty("attempts", "0");
      p.setProperty("mode", ab ? "ab" : "standard");
      p.setProperty("created_ms", Long.toString(System.currentTimeMillis()));
      p.setProperty("build", BenchPlan.currentBuild()); // another build aborts the plan instead of resuming it
      for (String k : new String[] {"uncapped", "timedemo", "screenshot", "hud", "auto", "warmup"}) {
         p.setProperty(k, "1".equals(o.get(k)) ? "1" : "0");
      }
      p.setProperty("timedemo", timedemo ? "1" : "0");
      p.setProperty("warmup", warmup ? "1" : "0");
      p.setProperty("seed", "0".equals(o.get("seed")) ? "0" : "1"); // technique 2 on unless switched off (profiling)
      p.setProperty("cooldown", Integer.toString(Math.max(0, Math.min(120, Integer.parseInt(o.getOrDefault("cooldown", "0"))))));
      p.setProperty("label", o.getOrDefault("label", ""));
      BenchPlan.backupLatestSave(p);
      BenchPlan.backupUserOptions(p);
      BenchPlan.backupFile(p, "game_options", new File(UserOptions.zomboidDir(), "options.ini"));
      BenchPlan.write(p);
      BenchPlan.runDir(run).mkdirs();
      BenchSetup.capture(run, p, String.join(",", ids), passes); // the player's settings and mods, before any pass pins one
      Log.info("benchmark: run " + run + " planned: " + steps);
      if (!Restart.relaunch()) {
         p.setProperty("state", "aborted");
         p.setProperty("abort_reason", "restart unavailable");
         BenchPlan.write(p);
         return "error\tthe game could not arrange its own restart on this system";
      }
      return "ok\t" + run;
   }

   /** -Dpzopt.benchmark=<scenarios|all>[:passes] at boot (technique 25): plans and starts an unattended run once. */
   private static String autoRequested() {
      String v = System.getProperty("pzopt.benchmark");
      if (v == null || v.isBlank()) {
         v = Config.BENCHMARK_AUTO;
      }
      if (v == null || v.isBlank() || "off".equals(v)) {
         return "";
      }
      Properties p = BenchPlan.plan();
      if (p != null && p.getProperty("auto_started_by", "").equals(Long.toString(java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime()))) {
         return "";
      }
      if (p != null && ("running".equals(p.getProperty("state")) || "report".equals(p.getProperty("state")))) {
         return ""; // a run is under way (this process may be one of its passes)
      }
      // scenarios[:passes[:standard|ab[:options]]], options joined by '+': timedemo, nohud, noseed, capped, warmup,
      // shots, cool<seconds> (the profiling runs of docs/plan-builtin-benchmark.md toggle one technique at a time)
      String[] sp = v.split(":");
      String ids = "all".equals(sp[0]) ? allIds() : sp[0];
      String passes = sp.length > 1 ? sp[1] : "1";
      String mode = sp.length > 2 ? sp[2] : "standard";
      java.util.Set<String> opt = new java.util.HashSet<>(java.util.Arrays.asList((sp.length > 3 ? sp[3] : "").split("\\+")));
      String cool = "0";
      for (String s : opt) {
         if (s.startsWith("cool")) {
            cool = s.substring(4);
         }
      }
      String autoFlag = opt.contains("report") ? "0" : "1"; // report: end on the report screen as a player would (UI check)
      String r = start("scenarios=" + ids + ";passes=" + passes + ";mode=" + mode + ";uncapped=" + (opt.contains("capped") ? "0" : "1") + ";auto=" + autoFlag
            + ";screenshot=" + (opt.contains("shots") ? "1" : "0") + ";timedemo=" + (opt.contains("timedemo") ? "1" : "0") + ";hud=" + (opt.contains("nohud") ? "0" : "1")
            + ";seed=" + (opt.contains("noseed") ? "0" : "1") + ";warmup=" + (opt.contains("warmup") ? "1" : "0") + ";cooldown=" + cool + ";label=" + v);
      return r.startsWith("ok") ? "started" : r;
   }

   private static String allIds() {
      StringBuilder b = new StringBuilder();
      for (BenchSuite.Scenario s : BenchSuite.ALL) {
         if (BenchSuite.scored(s.id)) {
            b.append(b.length() > 0 ? "," : "").append(s.id);
         }
      }
      return b.toString();
   }

   /** The HUD line during a pass (technique 26): which pass, what, time left. */
   private static String hud() {
      if (!BenchPlan.active() || !BenchPlan.option("hud")) {
         return "";
      }
      Properties p = BenchPlan.plan();
      String[] parts = BenchPlan.parts();
      BenchSuite.Scenario s = BenchPlan.scenario();
      int total = BenchPlan.steps(p).size();
      String cfg = switch (parts[1]) {
         case "B" -> "stock game";
         case "W" -> "warm-up";
         default -> "PZ Optimization";
      };
      float frac = Harness.REQUESTED ? Harness.routeFraction() : 0f;
      String state = BenchRecorder.on ? "recording" : frac >= 1f ? "saving the results" : "preparing";
      if (BenchRecorder.on && "sweep".equals(s.id)) {
         state = BenchSweep.windowLabel(BenchRecorder.elapsedS()); // the camera stands still on purpose: say what is measured
      } else if (BenchPlan.timedemoStep > 0) {
         state += ", timedemo " + BenchPlan.timedemoStep + " steps a second";
      }
      int step = BenchPlan.step();
      int runPct = total > 0 ? (int)(100 * (step + frac) / total) : 0;
      return (step + 1) + "\t" + total + "\t" + s.title + "\t" + cfg + "\t" + parts[2] + "\t" + state + "\t" + BenchRecorder.frames()
            + "\t" + (int)(100 * frac) + "\t" + runPct + "\t" + Math.round(secondsLeft(p, step, s, frac));
   }

   /**
    * Seconds a pass takes beyond its scenario's own time (boot, world creation, quit): measured on the desktop's runs of
    * 2026-10-18 (step end times), 12-21 s with PZ Optimization, 51-56 s for a stock pass (the stock boot is slower).
    */
   private static final int PROCESS_S = 16, PROCESS_STOCK_S = 52;

   /**
    * The HUD's time left for the whole run: the rest of this pass's route (and its settle while it has not started), then
    * every later pass's scenario time, process start and cool-down.
    */
   private static double secondsLeft(Properties p, int step, BenchSuite.Scenario s, float frac) {
      int cool = 0;
      try {
         cool = Integer.parseInt(p.getProperty("cooldown", "0").trim());
      } catch (NumberFormatException e) {
         // none
      }
      float settle = settleS(s);
      double left = (1f - frac) * Math.max(1f, s.seconds - settle) + (frac == 0f && !BenchRecorder.on ? settle : 0f);
      List<String[]> steps = BenchPlan.steps(p);
      for (int i = step + 1; i < steps.size(); i++) {
         BenchSuite.Scenario t = BenchSuite.byId(steps.get(i)[0]);
         left += (t != null ? t.seconds : 40) + ("B".equals(steps.get(i)[1]) ? PROCESS_STOCK_S : PROCESS_S) + cool;
      }
      return left;
   }

   private static float settleS(BenchSuite.Scenario s) {
      try {
         return Float.parseFloat(s.flags.getOrDefault("settle", "0"));
      } catch (NumberFormatException e) {
         return 0f;
      }
   }

   /**
    * A screenshot of the next presented frame (the UI included, {@link BenchShot}) into Zomboid/Screenshots, for the
    * unattended screen checks on machines whose desktop capture cannot see the game (the flip):
    * pzopt-bench-<run>-<step>-<what>.png during a pass, pzopt-bench-ui-<what>.png at the menu.
    */
   private static String shot(String what) {
      String w = (what == null ? "" : what).replaceAll("[^A-Za-z0-9-]", "");
      String name = BenchPlan.active() ? String.format("pzopt-bench-%s-%02d-%s.png", BenchPlan.plan().getProperty("run", ""), BenchPlan.step(), w) : "pzopt-bench-ui-" + w + ".png";
      BenchShot.request(BenchShot.screenshots(name)); // written by the render thread at the next present, which logs "benchmark: screenshot <name>"
      return name;
   }

   /**
    * The screen readability rig (devBenchmarkUi=read): every stored run's report.txt / .json / .html built again with this
    * build, so the HTML check reads each kind of report (stock comparison, feature sweep, invalid passes) as players get it.
    */
   private static String devRebuild() {
      File[] dirs = new File(BenchPlan.dir(), "runs").listFiles(File::isDirectory);
      int n = 0;
      for (File d : dirs == null ? new File[0] : dirs) {
         if (new File(d, "report.txt").isFile()) {
            BenchResults.buildReport(d.getName());
            n++;
         }
      }
      Log.info("benchmark: dev: " + n + " reports rebuilt");
      return Integer.toString(n);
   }

   /** Past results, Compare: arg "runA\trunB" (A the baseline), the rows of BenchResults.compareRuns. */
   private static String compareRuns(String arg) {
      String[] r = (arg == null ? "" : arg).split("\t");
      if (r.length < 2 || r[0].isBlank() || r[1].isBlank()) {
         return "error\ttwo runs needed";
      }
      return BenchResults.compareRuns(r[0].trim(), r[1].trim());
   }

   /**
    * Rig for the compare screen (devBenchmarkUi=compare): "run\tcode", the newest report with a stock comparison (else the
    * newest report) and the share code of the newest other run with scenario results. Empty when there are not two.
    */
   private static String devCompare() {
      File[] dirs = new File(BenchPlan.dir(), "runs").listFiles(File::isDirectory);
      if (dirs == null) {
         return "";
      }
      java.util.Arrays.sort(dirs, (a, b) -> b.getName().compareTo(a.getName()));
      String mine = null, newest = null;
      for (File d : dirs) {
         String txt = BenchResults.reportText(d.getName());
         if (txt.contains("\nS\t")) {
            newest = newest == null ? d.getName() : newest;
            if (mine == null && txt.contains("\nAB\t")) {
               mine = d.getName();
            }
         }
      }
      mine = mine == null ? newest : mine;
      for (File d : dirs) {
         if (mine != null && !d.getName().equals(mine) && BenchResults.reportText(d.getName()).contains("\nS\tspin")) {
            return mine + "\t" + BenchResults.shareCode(d.getName());
         }
      }
      return "";
   }

   private static String abort(String why) {
      Properties p = BenchPlan.plan();
      if (p == null) {
         return "";
      }
      BenchPlan.abort(p, why);
      return "ok";
   }

   /**
    * The player's stop during a pass's countdown at the main menu (Escape, a click or B; Workshop 2026-10-18: "how to stop
    * benchmark in game"). The plan is aborted with its worlds and the player's files put back; this process is a pass
    * (Harness flags and Config pins from the plan: it must not play on), so the Lua quits it and a restart follows
    * ("restart"), or the player starts the game again by hand ("quit").
    */
   private static String cancel() {
      String run = BenchPlan.abortRunning("stopped by the player");
      if (run == null) {
         return "";
      }
      passDone = true; // menu() answers quit from now on
      return Restart.relaunch() ? "restart" : "quit";
   }

   private static String reportShown() {
      Properties p = BenchPlan.plan();
      if (p != null && "report".equals(p.getProperty("state"))) {
         p.setProperty("state", "done");
         BenchPlan.write(p);
      }
      return "ok";
   }

   private static String idlePoll() {
      String r = BenchIdle.poll();
      if (r.startsWith("go")) {
         idleWaitS = BenchIdle.waitedSeconds();
         worldRequestedNs = System.nanoTime(); // the load time counts from here
      }
      return r;
   }

   private static double idleWaitS;

   static String finishMessage() {
      return finishMessage;
   }
}
