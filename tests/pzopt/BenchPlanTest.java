package pzopt;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The built-in benchmark's plan between processes (2026-10-19, the Workshop "endless benchmark bootloop"): a run whose
 * passes keep dying is abandoned as a whole, a claim does not refresh the stale stamp, a plan of another build is
 * abandoned, and BootRepair's abandon marks a running plan aborted with plain file access.
 */
public class BenchPlanTest {
   static Properties running(String steps, int step, int claim, int attempts) {
      Properties p = new Properties();
      p.setProperty("run", "20261019-000000");
      p.setProperty("state", "running");
      p.setProperty("steps", steps);
      p.setProperty("step", Integer.toString(step));
      p.setProperty("claim", Integer.toString(claim));
      p.setProperty("attempts", Integer.toString(attempts));
      p.setProperty("latest_save", "my-save\nApocalypse\n");
      return p;
   }

   public static void main(String[] args) throws Exception {
      Path home = Files.createTempDirectory("pzopt-plan-test");
      System.setProperty("deployment.user.cachedir", home.toString()); // UserOptions.zomboidDir -> <home>/Zomboid
      File latest = new File(UserOptions.zomboidDir(), "latestSave.ini");

      // 1. a step claimed by a process that never advanced: the next launch counts an attempt and claims it again,
      //    without refreshing the progress stamp
      Properties p = running("spin/A/1,spin/A/2,spin/A/3", 0, 0, 0);
      p.setProperty("updated_ms", "1000");
      BenchPlan.write(p);
      p = BenchPlan.read();
      p.setProperty("updated_ms", Long.toString(System.currentTimeMillis() - 60_000)); // a minute ago: not stale
      long stamp = Long.parseLong(p.getProperty("updated_ms"));
      BenchPlan.writeClaim(p);
      Check.check(Long.parseLong(BenchPlan.read().getProperty("updated_ms")) == stamp, "writeClaim keeps the stamp");
      BenchPlan.resetForTests();
      Check.check(BenchPlan.active() && BenchPlan.step() == 0, "the same step is claimed again (attempt 1)");
      p = BenchPlan.read();
      Check.check("1".equals(p.getProperty("attempts")) && "running".equals(p.getProperty("state")), "attempt 1 counted: " + p.getProperty("attempts"));
      Check.check(Long.parseLong(p.getProperty("updated_ms")) == stamp, "the claim did not refresh the stamp");

      // 2. the second death on the same step abandons the run (not the step): a normal game, the player's files back
      BenchPlan.resetForTests();
      Check.check(!BenchPlan.active(), "after two deaths no step runs");
      p = BenchPlan.read();
      Check.check("aborted".equals(p.getProperty("state")), "the run is aborted: " + p.getProperty("state"));
      Check.check(p.getProperty("abort_reason", "").contains("2 times in a row"), "reason names the deaths: " + p.getProperty("abort_reason"));
      Check.check("1".equals(p.getProperty("cleaned")), "cleaned at once");
      Check.check(latest.isFile() && Files.readString(latest.toPath(), StandardCharsets.UTF_8).startsWith("my-save"), "latestSave.ini restored");
      List<Map<String, String>> steps = BenchResults.readSteps("20261019-000000");
      Check.check(steps.size() == 1 && "spin".equals(steps.get(0).get("scenario")), "the failed pass is on record: " + steps);
      BenchPlan.resetForTests();
      Check.check(!BenchPlan.active() && "aborted".equals(BenchPlan.read().getProperty("state")), "an aborted plan stays a normal game");

      // 3. a plan made by another build is abandoned instead of resumed
      p = running("spin/A/1", 0, -1, 0);
      p.setProperty("run", "20261019-000001");
      p.setProperty("build", "not-this-build");
      BenchPlan.write(p);
      BenchPlan.resetForTests();
      Check.check(!BenchPlan.active(), "another build's plan runs no step");
      p = BenchPlan.read();
      Check.check("aborted".equals(p.getProperty("state")) && p.getProperty("abort_reason", "").contains("build changed"), "aborted for the build: " + p.getProperty("abort_reason"));

      // 4. a fresh claim of a healthy plan still works, and the stale rule abandons a plan untouched for 20 minutes
      p = running("spin/A/1", 0, -1, 0);
      p.setProperty("run", "20261019-000002");
      p.setProperty("build", BenchPlan.currentBuild());
      BenchPlan.write(p);
      BenchPlan.resetForTests();
      Check.check(BenchPlan.active() && BenchPlan.step() == 0, "a healthy plan's first step is claimed");
      p = BenchPlan.read();
      p.setProperty("updated_ms", Long.toString(System.currentTimeMillis() - 25L * 60_000));
      BenchPlan.writeClaim(p);
      BenchPlan.resetForTests();
      Check.check(!BenchPlan.active() && "stale".equals(BenchPlan.read().getProperty("abort_reason")), "25 minutes untouched: stale");

      // 5. BootRepair's abandon: plain file access, running -> aborted, a finished plan left alone
      p = running("spin/A/1", 0, 0, 0);
      p.setProperty("run", "20261019-000003");
      BenchPlan.write(p);
      String msg = BenchPlan.abandon(UserOptions.zomboidDir(), "the install was removed by the boot repair");
      Check.check(msg != null && msg.contains("20261019-000003") && msg.contains("aborted"), "abandon reports: " + msg);
      p = BenchPlan.read();
      Check.check("aborted".equals(p.getProperty("state")) && p.getProperty("abort_reason", "").contains("boot repair"), "abandoned plan: " + p.getProperty("abort_reason"));
      Check.check(BenchPlan.abandon(UserOptions.zomboidDir(), "again") == null, "an aborted plan is not abandoned again");
      p.setProperty("state", "done");
      BenchPlan.write(p);
      Check.check(BenchPlan.abandon(UserOptions.zomboidDir(), "done") == null, "a finished plan is left alone");

      // 6. abortRunning (Uninstall, the player's cancel): running -> aborted + cleaned; nothing running -> null
      p = running("spin/A/1", 0, 0, 0);
      p.setProperty("run", "20261019-000004");
      BenchPlan.write(p);
      Check.check("20261019-000004".equals(BenchPlan.abortRunning("PZ Optimization uninstalled")), "abortRunning returns the run");
      p = BenchPlan.read();
      Check.check("aborted".equals(p.getProperty("state")) && "1".equals(p.getProperty("cleaned")), "aborted and cleaned");
      Check.check(BenchPlan.abortRunning("again") == null, "nothing running: null");
      System.out.println("BenchPlanTest ok");
   }
}
