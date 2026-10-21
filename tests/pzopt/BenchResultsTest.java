package pzopt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The built-in benchmark's results: pass validation, aggregation (outlier left out), the score, the share code. */
public class BenchResultsTest {
   static Map<String, String> pass(String scenario, String cfg, int n, double fps, double low1) {
      Map<String, String> m = new LinkedHashMap<>();
      m.put("scenario", scenario);
      m.put("config", cfg);
      m.put("pass", Integer.toString(n));
      m.put("status", "ok");
      m.put("fps", Double.toString(fps));
      m.put("low1_avg", Double.toString(low1));
      m.put("score", Double.toString(BenchStats.score(fps, low1)));
      m.put("p99_ms", "8");
      return m;
   }

   public static void main(String[] args) throws Exception {
      java.nio.file.Path home = java.nio.file.Files.createTempDirectory("pzopt-bench-test");
      System.setProperty("deployment.user.cachedir", home.toString()); // UserOptions.zomboidDir -> <home>/Zomboid

      // validation
      Map<String, String> r = new LinkedHashMap<>();
      r.put("route_status", "complete");
      r.put("route_reason", "");
      r.put("frames", "5000");
      r.put("ticks", "5000");
      r.put("unfocused_frames", "0");
      r.put("paused_frames", "0");
      r.put("cpu_background_pct", "3");
      BenchResults.validate(r);
      Check.check("ok".equals(r.get("status")), "a clean pass is ok: " + r);
      r.put("cpu_background_pct", "40");
      BenchResults.validate(r);
      Check.check("invalid".equals(r.get("status")) && r.get("invalid_reason").contains("other programs"), "40 % background CPU invalidates: " + r.get("invalid_reason"));
      r.put("cpu_background_pct", "12");
      r.put("unfocused_frames", "500");
      BenchResults.validate(r);
      Check.check("invalid".equals(r.get("status")) && r.get("invalid_reason").contains("focus") && r.get("warnings").contains("12"), "focus loss invalid, 12 % a warning: " + r);
      r.put("unfocused_frames", "0");
      r.put("cpu_background_pct", "3");
      r.put("cap_fps", "60");
      r.put("fps", "59.9");
      BenchResults.validate(r);
      Check.check("ok".equals(r.get("status")) && "1".equals(r.get("cap_held")) && r.get("warnings").contains("frame cap"), "a pass at the 60 fps cap is valid but held: " + r);
      r.put("cap_fps", "0");
      r.put("fps", "500");
      r.put("route_status", "timeout");
      BenchResults.validate(r);
      Check.check("invalid".equals(r.get("status")) && r.get("invalid_reason").contains("timeout"), "an unfinished route is invalid");

      // aggregation: four passes, one an outlier
      List<Map<String, String>> steps = new ArrayList<>();
      steps.add(pass("spin", "A", 1, 500, 90));
      steps.add(pass("spin", "A", 2, 502, 92));
      steps.add(pass("spin", "A", 3, 498, 88));
      steps.add(pass("spin", "A", 4, 250, 40));
      steps.add(pass("drive120", "A", 1, 600, 150));
      Map<String, BenchResults.Agg> agg = BenchResults.aggregate(steps);
      BenchResults.Agg spin = agg.get("spin/A");
      Check.check(spin.outliers.length == 1 && spin.fps.n == 3 && Math.abs(spin.fps.mean - 500) < 1e-9, "outlier left out of the mean: n=" + spin.fps.n + " mean=" + spin.fps.mean);
      double[] score = BenchResults.configScore(agg, "A");
      double expect = BenchStats.geomean(new double[] {BenchStats.mean(spin.col("score", true)), BenchStats.score(600, 150)});
      Check.check(Math.abs(score[0] - expect) < 1e-6, "score = geometric mean of the scenario scores: " + score[0] + " vs " + expect);
      Check.check(Double.isNaN(score[1]), "a one-pass scenario leaves the score without an interval");

      Check.check(Math.abs(BenchResults.stabilityPct(new double[] {480, 500, 490}) - 96) < 1e-9 && Double.isNaN(BenchResults.stabilityPct(new double[] {500})), "frame rate stability = slowest / fastest pass");

      // share code round trip
      String run = "20261018-120000";
      java.io.File dir = BenchPlan.runDir(run);
      dir.mkdirs();
      java.nio.file.Files.writeString(new java.io.File(dir, "report.txt").toPath(),
            "H\t" + run + "\t2026-10-18 12:00\t\tstandard\tabc\nSYS\tcpu\tTest CPU\nSYS\tgpu\tTest GPU\nSCORE\tA\t1500\t20\n"
            + "S\tspin\tRosewood spin\tA\t3\t500.0\t5.0\t90.0\t40.0\t7.10\t300\t2121\t1.0\t60\t20\t5\t10\t7.0\t20\t3.0\t3\t\n");
      String code = BenchResults.shareCode(run);
      Check.check(code.startsWith("PZOB1:"), "share code prefix");
      String back = BenchResults.decodeShare(code);
      Check.check(back.contains("cpu=Test CPU") && back.contains("score.A=1500") && back.contains("s.spin.A=500.0|90.0|7.10|3"), "share code decodes: " + back);
      Check.check(BenchResults.decodeShare("garbage").isEmpty(), "a wrong code decodes to nothing");
      System.out.println("BenchResultsTest ok");
   }
}
