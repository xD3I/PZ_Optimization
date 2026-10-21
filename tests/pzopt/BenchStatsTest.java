package pzopt;

/** The built-in benchmark's statistics: percentiles, lows, stutters, histogram, pass spread, Welch's test, bootstrap, score. */
public class BenchStatsTest {
   static boolean near(double a, double b, double tol) {
      return Math.abs(a - b) <= tol;
   }

   public static void main(String[] args) {
      // 1000 frames of 10 ms with 10 frames of 40 ms: 100 fps nominal, the 1 % low = the 40 ms frames
      float[] ms = new float[1000];
      java.util.Arrays.fill(ms, 10f);
      for (int i = 50; i < 1000; i += 100) {
         ms[i] = 40f;
      }
      BenchStats.Frames f = BenchStats.frames(ms, ms.length);
      Check.check(f.frames == 1000, "frame count");
      Check.check(near(f.seconds, 10.3, 1e-6), "seconds = " + f.seconds);
      Check.check(near(f.avgFps, 1000 / 10.3, 1e-6), "fps = frames / time: " + f.avgFps);
      Check.check(near(f.low1Avg, 25, 1e-6), "1 % low (average) = 25 fps: " + f.low1Avg);
      Check.check(near(f.medianMs, 10, 1e-6), "median");
      Check.check(f.maxMs == 40f && f.minMs == 10f, "min / max");
      Check.check(f.stutters == 10, "10 stutters: " + f.stutters);
      Check.check(f.over50 == 0, "nothing over 50 ms");
      Check.check(near(f.below30Pct, 100 * 400 / 10300.0, 1e-6), "time below 30 fps: " + f.below30Pct);
      double h = 0;
      for (double b : f.hist) {
         h += b;
      }
      Check.check(near(h, 100, 1e-6), "histogram sums to 100 %: " + h);
      Check.check(near(f.jitterMs, 20 * 30 / 999.0, 1e-6), "jitter: " + f.jitterMs);
      Check.check(f.curve[0] == 10 && f.curve[f.curve.length - 1] == 40, "curve ends");
      // percentile interpolation
      float[] s = {1f, 2f, 3f, 4f};
      Check.check(near(BenchStats.percentile(s, 4, 50), 2.5, 1e-9), "median of 1..4");
      Check.check(near(BenchStats.percentile(s, 4, 100), 4, 1e-9) && near(BenchStats.percentile(s, 4, 0), 1, 1e-9), "ends");
      // a steady ramp has no stutters
      float[] ramp = new float[500];
      for (int i = 0; i < ramp.length; i++) {
         ramp[i] = 5f + i * 0.01f;
      }
      Check.check(BenchStats.frames(ramp, ramp.length).stutters == 0, "no stutter in a ramp");

      // passes: mean, sd, CI, outlier
      BenchStats.Passes p = BenchStats.passes(new double[] {100, 102, 98, 101, 99});
      Check.check(near(p.mean, 100, 1e-9), "pass mean");
      Check.check(near(p.sd, Math.sqrt(2.5), 1e-9), "pass sd: " + p.sd);
      Check.check(near(p.ci95, 2.776 * Math.sqrt(2.5) / Math.sqrt(5), 1e-6), "pass CI: " + p.ci95);
      Check.check(p.outliers.length == 0, "no outlier");
      BenchStats.Passes po = BenchStats.passes(new double[] {100, 101, 99, 100, 60});
      Check.check(po.outliers.length == 1 && po.outliers[0] == 4, "60 is an outlier");

      // Student t: P(|T| >= 2.228) with df 10 = 0.05; df 1, t = 12.706 -> 0.05
      Check.check(near(BenchStats.studentTwoSidedP(2.228, 10), 0.05, 0.001), "t p-value df 10: " + BenchStats.studentTwoSidedP(2.228, 10));
      Check.check(near(BenchStats.studentTwoSidedP(12.706, 1), 0.05, 0.001), "t p-value df 1");
      Check.check(near(BenchStats.studentTwoSidedP(0, 5), 1, 1e-9), "t = 0 -> p = 1");
      Check.check(near(Math.exp(BenchStats.lgamma(5)), 24, 1e-6), "Gamma(5) = 24");
      double[] w = BenchStats.welch(new double[] {100, 101, 99, 100}, new double[] {110, 111, 109, 110});
      Check.check(w[2] < 0.001, "clearly different means: p = " + w[2]);
      double[] w2 = BenchStats.welch(new double[] {100, 104, 96, 100}, new double[] {101, 97, 103, 99});
      Check.check(w2[2] > 0.5, "same means: p = " + w2[2]);

      // bootstrap speedup interval contains the ratio and is deterministic
      double[] a = {50, 52, 48, 51}, b = {75, 76, 74, 77};
      double[] r1 = BenchStats.ratioCi(a, b, 2000, 7), r2 = BenchStats.ratioCi(a, b, 2000, 7);
      Check.check(r1[1] <= r1[0] && r1[0] <= r1[2], "interval brackets the ratio: " + java.util.Arrays.toString(r1));
      Check.check(r1[1] == r2[1] && r1[2] == r2[2], "fixed seed, same interval");

      Check.check(near(BenchStats.geomean(new double[] {2, 8}), 4, 1e-9), "geomean");
      Check.check(near(BenchStats.score(100, 100), 1000, 1e-9), "score of 100 / 100");
      double[] sm = BenchStats.spikeMap(new double[][] {{100, 100, 40, 100}, {100, 100, 50, 100}, {30, 100, 100, 100}});
      Check.check(near(sm[2], 2 / 3.0, 1e-9) && near(sm[0], 1 / 3.0, 1e-9) && sm[1] == 0, "spike map: " + java.util.Arrays.toString(sm));
      System.out.println("BenchStatsTest ok");
   }
}
