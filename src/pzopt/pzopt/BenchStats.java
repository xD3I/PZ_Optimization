package pzopt;

import java.util.Arrays;

/**
 * The built-in benchmark's numbers (2026-10-18, docs/plan-builtin-benchmark.md): plain functions over frame-time arrays
 * and over per-pass results, no game classes, so tests/pzopt/BenchStatsTest checks them on the JVM alone.
 *
 * Conventions: frame times in milliseconds; "fps" of a window = frames / seconds (never the mean of per-frame fps,
 * which overweights fast frames); the 1 % low is given both ways players meet it: the average of the slowest 1 % of
 * frames as fps ({@code low1Avg}, CapFrameX / GamersNexus) and the 99th percentile frame time as fps ({@code low1Pct},
 * MSI Afterburner / FrameView).
 */
public final class BenchStats {
   private BenchStats() {
   }

   /** Edges (ms) of the time-weighted frame-time histogram; the last bucket is open. */
   public static final float[] HIST_EDGES = {0f, 2f, 3f, 4f, 5f, 6f, 7f, 8.33f, 10f, 12.5f, 16.67f, 20f, 25f, 33.3f, 50f, 100f};
   /** Percentile curve points (percent of frames at or under the frame time). */
   public static final float[] CURVE_P = {0f, 1f, 5f, 10f, 25f, 50f, 75f, 90f, 95f, 99f, 99.5f, 99.9f, 100f};

   /** One timed window's frame-time numbers. */
   public static final class Frames {
      public int frames;
      public double seconds;
      public double avgFps, medianMs, meanMs, minMs, maxMs, sdMs;
      public double p90Ms, p95Ms, p99Ms, p999Ms;
      public double low1Avg, low01Avg, low1Pct, low01Pct, low5Avg;
      /** frames over 2x the median of the 31 frames around them (and over 4 ms: noise at 500 fps is no stutter) */
      public int stutters;
      /** frames over 50 ms / 100 ms */
      public int over50, over100;
      /** mean |ft[i] - ft[i-1]| in ms, and the same as a share of the mean frame time */
      public double jitterMs, jitterPct;
      /** share of the window's time spent in frames slower than 60 / 30 fps, in percent */
      public double below60Pct, below30Pct;
      /** frame time coefficient of variation in percent */
      public double cvPct;
      /** time-weighted histogram over HIST_EDGES, percent of the window */
      public double[] hist = new double[HIST_EDGES.length];
      /** frame time at each CURVE_P point */
      public double[] curve = new double[CURVE_P.length];
   }

   /** Linear-interpolated percentile of an ascending array (p in 0..100). */
   public static double percentile(float[] sorted, int n, double p) {
      if (n <= 0) {
         return 0;
      }
      if (n == 1) {
         return sorted[0];
      }
      double r = p / 100.0 * (n - 1);
      int lo = (int)Math.floor(r);
      int hi = Math.min(n - 1, lo + 1);
      double f = r - lo;
      return sorted[lo] * (1 - f) + sorted[hi] * f;
   }

   /** Average of the slowest {@code share} (e.g. 0.01) of the frames of an ascending array, in ms; at least one frame. */
   public static double slowestAvgMs(float[] sorted, int n, double share) {
      if (n <= 0) {
         return 0;
      }
      int k = Math.max(1, (int)Math.round(n * share));
      double s = 0;
      for (int i = n - k; i < n; i++) {
         s += sorted[i];
      }
      return s / k;
   }

   public static Frames frames(float[] ms, int n) {
      Frames r = new Frames();
      r.frames = n;
      if (n == 0) {
         return r;
      }
      float[] s = Arrays.copyOf(ms, n);
      Arrays.sort(s);
      double sum = 0, sq = 0;
      for (int i = 0; i < n; i++) {
         sum += ms[i];
      }
      r.seconds = sum / 1000.0;
      r.meanMs = sum / n;
      for (int i = 0; i < n; i++) {
         double d = ms[i] - r.meanMs;
         sq += d * d;
      }
      r.sdMs = n > 1 ? Math.sqrt(sq / (n - 1)) : 0;
      r.cvPct = r.meanMs > 0 ? 100 * r.sdMs / r.meanMs : 0;
      r.avgFps = r.seconds > 0 ? n / r.seconds : 0;
      r.minMs = s[0];
      r.maxMs = s[n - 1];
      r.medianMs = percentile(s, n, 50);
      r.p90Ms = percentile(s, n, 90);
      r.p95Ms = percentile(s, n, 95);
      r.p99Ms = percentile(s, n, 99);
      r.p999Ms = percentile(s, n, 99.9);
      r.low1Avg = fps(slowestAvgMs(s, n, 0.01));
      r.low01Avg = fps(slowestAvgMs(s, n, 0.001));
      r.low5Avg = fps(slowestAvgMs(s, n, 0.05));
      r.low1Pct = fps(r.p99Ms);
      r.low01Pct = fps(r.p999Ms);
      for (int i = 0; i < CURVE_P.length; i++) {
         r.curve[i] = percentile(s, n, CURVE_P[i]);
      }
      double jit = 0;
      double t60 = 0, t30 = 0;
      for (int i = 0; i < n; i++) {
         float f = ms[i];
         if (i > 0) {
            jit += Math.abs(f - ms[i - 1]);
         }
         if (f > 1000f / 60f) {
            t60 += f;
         }
         if (f > 1000f / 30f) {
            t30 += f;
         }
         if (f > 50f) {
            r.over50++;
         }
         if (f > 100f) {
            r.over100++;
         }
         int b = HIST_EDGES.length - 1;
         while (b > 0 && f < HIST_EDGES[b]) {
            b--;
         }
         r.hist[b] += f;
      }
      for (int b = 0; b < r.hist.length; b++) {
         r.hist[b] = sum > 0 ? 100 * r.hist[b] / sum : 0;
      }
      r.jitterMs = n > 1 ? jit / (n - 1) : 0;
      r.jitterPct = r.meanMs > 0 ? 100 * r.jitterMs / r.meanMs : 0;
      r.below60Pct = sum > 0 ? 100 * t60 / sum : 0;
      r.below30Pct = sum > 0 ? 100 * t30 / sum : 0;
      r.stutters = stutters(ms, n, 31, 2.0, 4.0);
      return r;
   }

   /** Frames longer than {@code factor} x the median of the {@code window} frames centred on them, and over {@code minMs}. */
   public static int stutters(float[] ms, int n, int window, double factor, double minMs) {
      if (n < 3) {
         return 0;
      }
      int half = window / 2;
      float[] buf = new float[window];
      int count = 0;
      for (int i = 0; i < n; i++) {
         int a = Math.max(0, i - half), b = Math.min(n, i + half + 1);
         int k = b - a;
         System.arraycopy(ms, a, buf, 0, k);
         Arrays.sort(buf, 0, k);
         double med = k % 2 == 1 ? buf[k / 2] : 0.5 * (buf[k / 2 - 1] + buf[k / 2]);
         if (ms[i] > factor * med && ms[i] > minMs) {
            count++;
         }
      }
      return count;
   }

   public static double fps(double ms) {
      return ms > 0 ? 1000.0 / ms : 0;
   }

   // ------------------------------------------------------------------ several passes

   /** Mean, spread and the 95 % confidence interval of a set of pass values. */
   public static final class Passes {
      public int n;
      public double mean, sd, cvPct, ci95, median, min, max;
      /** indexes of passes more than 3 scaled MADs from the median */
      public int[] outliers = new int[0];
   }

   public static Passes passes(double[] v) {
      Passes p = new Passes();
      p.n = v.length;
      if (v.length == 0) {
         return p;
      }
      double s = 0;
      for (double x : v) {
         s += x;
      }
      p.mean = s / v.length;
      double q = 0;
      for (double x : v) {
         q += (x - p.mean) * (x - p.mean);
      }
      p.sd = v.length > 1 ? Math.sqrt(q / (v.length - 1)) : 0;
      p.cvPct = p.mean != 0 ? 100 * p.sd / Math.abs(p.mean) : 0;
      p.ci95 = v.length > 1 ? tQuantile975(v.length - 1) * p.sd / Math.sqrt(v.length) : 0;
      double[] c = v.clone();
      Arrays.sort(c);
      p.min = c[0];
      p.max = c[c.length - 1];
      p.median = median(c);
      if (v.length >= 3) {
         double[] dev = new double[v.length];
         for (int i = 0; i < v.length; i++) {
            dev[i] = Math.abs(v[i] - p.median);
         }
         Arrays.sort(dev);
         double mad = 1.4826 * median(dev);
         int[] out = new int[v.length];
         int k = 0;
         for (int i = 0; i < v.length; i++) {
            if (mad > 0 && Math.abs(v[i] - p.median) > 3 * mad) {
               out[k++] = i;
            }
         }
         p.outliers = Arrays.copyOf(out, k);
      }
      return p;
   }

   static double median(double[] sorted) {
      int n = sorted.length;
      return n == 0 ? 0 : n % 2 == 1 ? sorted[n / 2] : 0.5 * (sorted[n / 2 - 1] + sorted[n / 2]);
   }

   /** Two-sided 97.5 % quantile of Student's t with df degrees of freedom (table to 30, then the normal limit). */
   public static double tQuantile975(double df) {
      final double[] t = {12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228, 2.201, 2.179, 2.160, 2.145, 2.131,
         2.120, 2.110, 2.101, 2.093, 2.086, 2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042};
      if (df < 1) {
         return Double.NaN;
      }
      int i = (int)Math.floor(df);
      if (i <= 30) {
         return t[i - 1];
      }
      return 1.960 + 2.4 / df; // within 0.002 of the exact value from df 30 on
   }

   /** Welch's two-sample t-test: {t, df, two-sided p}. NaN p when either side has fewer than two passes. */
   public static double[] welch(double[] a, double[] b) {
      Passes pa = passes(a), pb = passes(b);
      if (a.length < 2 || b.length < 2) {
         return new double[] {Double.NaN, Double.NaN, Double.NaN};
      }
      double va = pa.sd * pa.sd / a.length, vb = pb.sd * pb.sd / b.length;
      double se = Math.sqrt(va + vb);
      if (se == 0) {
         return new double[] {pa.mean == pb.mean ? 0 : Double.POSITIVE_INFINITY, a.length + b.length - 2, pa.mean == pb.mean ? 1 : 0};
      }
      double t = (pa.mean - pb.mean) / se;
      double df = (va + vb) * (va + vb) / (va * va / (a.length - 1) + vb * vb / (b.length - 1));
      double p = studentTwoSidedP(t, df);
      return new double[] {t, df, p};
   }

   /** P(|T| >= |t|) for Student's t with df degrees of freedom: I_{df/(df+t^2)}(df/2, 1/2). */
   public static double studentTwoSidedP(double t, double df) {
      double x = df / (df + t * t);
      return incompleteBeta(df / 2, 0.5, x);
   }

   /** Regularized incomplete beta I_x(a, b) (continued fraction, Numerical Recipes 6.4). */
   public static double incompleteBeta(double a, double b, double x) {
      if (x <= 0) {
         return 0;
      }
      if (x >= 1) {
         return 1;
      }
      double bt = Math.exp(lgamma(a + b) - lgamma(a) - lgamma(b) + a * Math.log(x) + b * Math.log(1 - x));
      if (x < (a + 1) / (a + b + 2)) {
         return bt * betacf(a, b, x) / a;
      }
      return 1 - bt * betacf(b, a, 1 - x) / b;
   }

   private static double betacf(double a, double b, double x) {
      final double eps = 1e-12, fpmin = 1e-300;
      double qab = a + b, qap = a + 1, qam = a - 1, c = 1, d = 1 - qab * x / qap;
      if (Math.abs(d) < fpmin) {
         d = fpmin;
      }
      d = 1 / d;
      double h = d;
      for (int m = 1; m <= 300; m++) {
         int m2 = 2 * m;
         double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
         d = 1 + aa * d;
         if (Math.abs(d) < fpmin) {
            d = fpmin;
         }
         c = 1 + aa / c;
         if (Math.abs(c) < fpmin) {
            c = fpmin;
         }
         d = 1 / d;
         h *= d * c;
         aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
         d = 1 + aa * d;
         if (Math.abs(d) < fpmin) {
            d = fpmin;
         }
         c = 1 + aa / c;
         if (Math.abs(c) < fpmin) {
            c = fpmin;
         }
         d = 1 / d;
         double del = d * c;
         h *= del;
         if (Math.abs(del - 1) < eps) {
            break;
         }
      }
      return h;
   }

   /** ln Gamma(x) (Lanczos, g = 7). */
   public static double lgamma(double x) {
      final double[] c = {0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313, -176.61502916214059,
         12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7};
      if (x < 0.5) {
         return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - lgamma(1 - x);
      }
      x -= 1;
      double a = c[0];
      double t = x + 7.5;
      for (int i = 1; i < 9; i++) {
         a += c[i] / (x + i);
      }
      return 0.5 * Math.log(2 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(a);
   }

   /**
    * 95 % bootstrap interval of mean(b) / mean(a) (percentile method, fixed seed so a report reads the same twice):
    * the speedup of B over A with its uncertainty. {ratio, lo, hi}.
    */
   public static double[] ratioCi(double[] a, double[] b, int resamples, long seed) {
      double ra = mean(a), rb = mean(b);
      double ratio = ra != 0 ? rb / ra : Double.NaN;
      if (a.length < 2 || b.length < 2) {
         return new double[] {ratio, Double.NaN, Double.NaN};
      }
      java.util.SplittableRandom rnd = new java.util.SplittableRandom(seed);
      double[] r = new double[resamples];
      for (int i = 0; i < resamples; i++) {
         double sa = 0, sb = 0;
         for (int k = 0; k < a.length; k++) {
            sa += a[rnd.nextInt(a.length)];
         }
         for (int k = 0; k < b.length; k++) {
            sb += b[rnd.nextInt(b.length)];
         }
         r[i] = sa != 0 ? (sb / b.length) / (sa / a.length) : Double.NaN;
      }
      Arrays.sort(r);
      return new double[] {ratio, r[(int)(0.025 * (resamples - 1))], r[(int)Math.ceil(0.975 * (resamples - 1))]};
   }

   public static double mean(double[] v) {
      double s = 0;
      for (double x : v) {
         s += x;
      }
      return v.length > 0 ? s / v.length : 0;
   }

   /** Geometric mean of the positive values (0 when there are none). */
   public static double geomean(double[] v) {
      double s = 0;
      int k = 0;
      for (double x : v) {
         if (x > 0) {
            s += Math.log(x);
            k++;
         }
      }
      return k > 0 ? Math.exp(s / k) : 0;
   }

   /**
    * The benchmark score of one scenario: 10 x the geometric mean of the average fps and the 1 % low (average
    * definition), so a smooth run beats one with the same average and worse tails.
    */
   public static double score(double avgFps, double low1Avg) {
      return avgFps > 0 && low1Avg > 0 ? 10 * Math.sqrt(avgFps * low1Avg) : 0;
   }

   /**
    * Recurring spikes (technique 29): per-second fps rows of several passes on the route's time axis; a second is a
    * spike in a pass when its fps is under 75 % of that pass's median second. Returns per second the share of passes
    * with a spike there; recurring (>= half of the passes) = content, the rest = system noise.
    */
   public static double[] spikeMap(double[][] perSecondFps) {
      int len = 0;
      for (double[] row : perSecondFps) {
         len = Math.max(len, row.length);
      }
      double[] share = new double[len];
      if (perSecondFps.length == 0) {
         return share;
      }
      for (double[] row : perSecondFps) {
         double[] c = row.clone();
         Arrays.sort(c);
         double med = median(c);
         for (int i = 0; i < row.length; i++) {
            if (row[i] < 0.75 * med) {
               share[i] += 1.0 / perSecondFps.length;
            }
         }
      }
      return share;
   }
}
