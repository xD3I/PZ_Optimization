package pzopt;

import java.util.ArrayList;
import java.util.List;

/** The feature sweep's analysis: a known cost recovered, a cost-free feature inside the noise floor, a baseline step seen. */
public class BenchSweepTest {
   public static void main(String[] args) {
      List<String[]> plan = new ArrayList<>();
      plan.add(new String[] {"testA", "true", "false", "Costly"});
      plan.add(new String[] {"testB", "true", "false", "Free"});
      plan.add(new String[] {"testC", "true", "false", "Leaves state"});
      for (int i = 0; i < 8; i++) { // 11 features as on the desktop: 10 noise samples, of which a step touches two
         plan.add(new String[] {"free" + i, "true", "false", "Free " + i});
      }
      int windows = 2 * plan.size() + 1;
      double secs = BenchSweep.PREROLL_S + windows * BenchSweep.WINDOW_S;
      int n = (int)(secs * 1000);
      double[] t = new double[n];
      float[] ms = new float[n], gpu = new float[n];
      java.util.Random r = new java.util.Random(7);
      for (int i = 0; i < n; i++) {
         t[i] = i / 1000.0;
         int w = (int)Math.floor((t[i] - BenchSweep.PREROLL_S) / BenchSweep.WINDOW_S);
         double base = 1.0 + (w >= 6 ? -0.1 : 0); // testC (window 5) leaves the baseline 0.1 ms lower
         double v = base + (w == 1 ? 0.2 : 0) + (r.nextGaussian() * 0.05);
         ms[i] = (float)v;
         gpu[i] = (float)(v * 0.8);
      }
      String out = BenchSweep.analyse(plan, t, ms, gpu, n);
      String[] e = out.split(";");
      Check.check(e.length == 12 && e[11].startsWith(BenchSweep.NOISE + "|"), "eleven features and the noise entry: " + out);
      double costA = Double.parseDouble(e[0].split("\\|")[4]);
      Check.check(Math.abs(costA - 0.2) < 0.01, "the costly feature's 0.2 ms recovered: " + costA);
      double costB = Double.parseDouble(e[1].split("\\|")[4]);
      Check.check(Math.abs(costB) < 0.01, "a free feature costs ~0: " + costB);
      double shiftC = Double.parseDouble(e[2].split("\\|")[6]);
      Check.check(Math.abs(shiftC + 0.1) < 0.01, "the step after testC shows as its baseline shift: " + shiftC);
      double noise = Double.parseDouble(e[11].split("\\|")[4]);
      Check.check(noise < 0.01, "the robust noise floor ignores the one step: " + noise);
      System.out.println("BenchSweepTest ok");
   }
}
