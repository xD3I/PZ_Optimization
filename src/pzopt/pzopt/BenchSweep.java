package pzopt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Technique 24: what each Visuals feature costs on this PC, in one pass. The camera stands still at the Rosewood spot and
 * turns 120 degrees a second (one full turn per measured window, so every window sees the same views); every feature
 * that applies live (Config.isLive) is switched to its other state for a window between windows in the player's own
 * state, through Harness's live_set (what the PZ OPTIMIZATION screen does on a change). Each window: {@link #SETTLE_S} s unmeasured
 * (shader compiles, re-bakes), then {@link #MEASURE_S} s measured. The cost of a feature = its window's mean frame time
 * minus the mean of the two neighbouring base windows (drift cancels to first order), plus the GPU time the same way.
 * The player's options file is written by every switch: BenchPlan saves it before the run and the next process puts it
 * back (and the pass sets every key back at its end).
 */
final class BenchSweep {
   static final float SETTLE_S = 1f, MEASURE_S = 3f;
   static final float WINDOW_S = SETTLE_S + MEASURE_S;
   /**
    * Unmeasured time after the route start's full re-bake ({@link #rebakeAll}). The first sweep (bench-fsweep, both
    * passes) drew the same still views at 1.18 ms a frame until the first switch that re-bakes every chunk texture
    * (ambient occlusion) and at 1.08 ms after it, for the rest of the pass: the textures baked while the world streamed in
    * cost more to draw than the same textures baked again, and every feature before that switch had another baseline.
    */
   static final float PREROLL_S = 4f;
   /** The analysis's pseudo feature carrying the noise of a cost estimate. */
   static final String NOISE = "_noise";

   /** key, the "on" value, label */
   static final String[][] FEATURES = {
      {"upscaler", "fsr1", "Upscaling (FSR 1.0)"},
      {"spriteFilter", "sharp", "Sprite filtering"},
      {"ambientOcclusion", "true", "Ambient occlusion"},
      {"sunShadows", "true", "Sun shadows"},
      {"pixelLight", "true", "Per-pixel lighting"},
      {"godRays", "true", "God rays"},
      {"colorGrading", "true", "Colour grading"},
      {"memoryTint", "true", "Remembered places"},
      {"darknessFloorPct", "30", "Darkness floor"},
      {"foliageSway", "true", "Foliage sway"},
      {"ripples", "true", "Puddle ripples"},
      {"relief", "true", "Relief"},
      {"reflections", "true", "Reflections"},
      {"mirrors", "true", "Mirrors and windows"},
   };

   private BenchSweep() {
   }

   private static List<String[]> planned;

   /**
    * The features that switch live on this build, each with the value to switch to and back. {key, other, own, label}.
    * Taken once per process, when the pass's flags are built (before any switch): read mid-pass, the switched feature's
    * own value would be the switched one.
    */
   static synchronized List<String[]> plan() {
      if (planned == null) {
         planned = planNow();
      }
      return planned;
   }

   private static List<String[]> planNow() {
      List<String[]> out = new ArrayList<>();
      for (String[] f : FEATURES) {
         if (!Config.knows(f[0]) || !Config.isLive(f[0])) {
            continue;
         }
         String own = Config.value(f[0]);
         if (own == null) {
            continue;
         }
         String off = Config.defaultValue(f[0]);
         boolean ownIsOn = own.equalsIgnoreCase(f[1]) || (!own.equalsIgnoreCase(off) && !own.equals("false") && !own.equals("0") && !own.equals("off") && !own.equals("stock"));
         String other = ownIsOn ? offValue(f[0], off) : f[1];
         if (other.equalsIgnoreCase(own)) {
            continue;
         }
         out.add(new String[] {f[0], other, own, f[2]});
      }
      return out;
   }

   private static String offValue(String key, String def) {
      return switch (key) {
         case "upscaler" -> "off";
         case "spriteFilter" -> "stock";
         case "darknessFloorPct" -> "0";
         default -> def == null || def.equals("true") ? "false" : def;
      };
   }

   /** What the sweep measures at this time of the pass (the HUD). */
   static String windowLabel(double s) {
      if (s < PREROLL_S) {
         return "re-baking the view";
      }
      int w = (int)((s - PREROLL_S) / WINDOW_S);
      List<String[]> p = plan();
      if (w % 2 == 0 || w / 2 >= p.size()) {
         return "your settings (baseline)";
      }
      String[] f = p.get(w / 2);
      return f[3] + " " + (f[1].equalsIgnoreCase(offValue(f[0], Config.defaultValue(f[0]))) ? "off" : "on");
   }

   /** Every loaded chunk texture bakes again (spread over the next frames by the bake budgets), as an AO switch does. */
   static void rebakeAll() {
      zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
      int chunks = 0;
      if (cell != null) {
         for (int p = 0; p < 4; p++) {
            zombie.iso.IsoChunkMap cm = cell.getChunkMap(p);
            if (cm == null || cm.ignore || cm.getChunks() == null) {
               continue;
            }
            for (zombie.iso.IsoChunk c : cm.getChunks()) {
               if (c != null) {
                  c.getRenderLevels(p).invalidateAll(zombie.iso.fboRenderChunk.FBORenderChunk.DIRTY_OBJECT_MODIFY);
                  chunks++;
               }
            }
         }
      }
      Log.info("benchmark: sweep pre-roll, " + chunks + " chunks bake again");
   }

   /** Harness flags of the sweep pass: the still, turning camera and the live_set schedule. */
   static void flags(java.util.Map<String, String> m) {
      List<String[]> p = plan();
      int windows = 2 * p.size() + 1;
      float secs = PREROLL_S + windows * WINDOW_S;
      m.put("route", "S:1");
      m.put("speed", String.format(Locale.ROOT, "%.5f", 1f / secs));
      m.put("turn", "120");
      StringBuilder b = new StringBuilder();
      for (int i = 0; i < p.size(); i++) {
         float on = PREROLL_S + (1 + 2 * i) * WINDOW_S, off = on + WINDOW_S;
         b.append(b.length() > 0 ? "," : "").append(p.get(i)[0]).append('=').append(p.get(i)[1]).append('@').append(String.format(Locale.ROOT, "%.2f", on));
         b.append(',').append(p.get(i)[0]).append('=').append(p.get(i)[2]).append('@').append(String.format(Locale.ROOT, "%.2f", off));
      }
      m.put("live_set", b.toString());
   }

   /**
    * The per-feature costs from the pass's frames (frame end times relative to the route start, seconds; frame ms; GPU
    * ms): "key|label|base ms|on ms|cost ms|gpu cost ms|shift ms;..." in the plan's order, shift = the base window after
    * the feature minus the one before it (a switch that leaves state behind shows here), then the noise entry.
    */
   static String analyse(double[] t, float[] ms, float[] gpu, int n) {
      return analyse(plan(), t, ms, gpu, n);
   }

   static String analyse(List<String[]> p, double[] t, float[] ms, float[] gpu, int n) {
      int windows = 2 * p.size() + 1;
      double[] mean = new double[windows], gmean = new double[windows];
      for (int w = 0; w < windows; w++) {
         double a = PREROLL_S + w * WINDOW_S + SETTLE_S, b = PREROLL_S + (w + 1) * WINDOW_S;
         double s = 0, g = 0;
         int k = 0;
         for (int i = 0; i < n; i++) {
            if (t[i] >= a && t[i] < b) {
               s += ms[i];
               g += gpu[i];
               k++;
            }
         }
         mean[w] = k > 0 ? s / k : Double.NaN;
         gmean[w] = k > 0 ? g / k : Double.NaN;
      }
      StringBuilder out = new StringBuilder();
      for (int i = 0; i < p.size(); i++) {
         int on = 1 + 2 * i;
         double base = 0.5 * (mean[on - 1] + mean[on + 1]);
         double gbase = 0.5 * (gmean[on - 1] + gmean[on + 1]);
         // the switch goes to the other state: report the cost of the feature being ON, whichever the player's state is
         boolean measuredOn = !p.get(i)[1].equals(offValue(p.get(i)[0], Config.defaultValue(p.get(i)[0])));
         double cost = measuredOn ? mean[on] - base : base - mean[on];
         double gcost = measuredOn ? gmean[on] - gbase : gbase - gmean[on];
         out.append(out.length() > 0 ? ";" : "").append(p.get(i)[0]).append('|').append(p.get(i)[3]).append('|')
               .append(String.format(Locale.ROOT, "%.3f|%.3f|%.3f|%.3f|%.3f", measuredOn ? base : mean[on], measuredOn ? mean[on] : base, cost, gcost, mean[on + 1] - mean[on - 1]));
      }
      // the noise floor (an A/A control): each interior base window against the mean of the base windows either side of
      // it, the same estimator as a feature's cost with nothing switched. The first sweep (bench-fsweep) gave costs of
      // -0.08..+0.05 ms at ~1.1 ms frames, the same size both ways: without the floor they read as real costs and gains
      // Robust (1.4826 x the median absolute value): one step in the baseline must not widen the floor for every feature.
      List<Double> dv = new ArrayList<>(), dg = new ArrayList<>();
      for (int w = 2; w + 2 < windows; w += 2) {
         double a = mean[w] - 0.5 * (mean[w - 2] + mean[w + 2]);
         double g = gmean[w] - 0.5 * (gmean[w - 2] + gmean[w + 2]);
         if (!Double.isNaN(a) && !Double.isNaN(g)) {
            dv.add(Math.abs(a));
            dg.add(Math.abs(g));
         }
      }
      if (!dv.isEmpty()) {
         out.append(out.length() > 0 ? ";" : "").append(NOISE).append("|Noise between turns|")
               .append(String.format(Locale.ROOT, "nan|nan|%.4f|%.4f|nan", 1.4826 * median(dv), 1.4826 * median(dg)));
      }
      return out.toString();
   }

   private static double median(List<Double> v) {
      v.sort(null);
      int n = v.size();
      return n % 2 == 1 ? v.get(n / 2) : 0.5 * (v.get(n / 2 - 1) + v.get(n / 2));
   }
}
