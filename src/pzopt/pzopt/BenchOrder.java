package pzopt;

import java.util.ArrayList;
import java.util.List;

/**
 * The order of a benchmark's passes (techniques 3, 17, 18): optional untimed warm-up first, then the passes. With the
 * stock comparison each scenario's passes run in an ABBA pattern (optimized, stock, stock, optimized, ...), so a slow
 * drift over the run (the machine heating up, a background task) weighs on both sides alike; scenarios interleave by
 * pass round so one scenario's passes do not all run at the same time of the run.
 */
public final class BenchOrder {
   private BenchOrder() {
   }

   /** "scenario/config/pass,..." for the plan. */
   static String steps(List<String> ids, int passes, boolean ab, boolean warmup) {
      List<String> out = new ArrayList<>();
      if (warmup) {
         for (String id : ids) {
            out.add(id + "/W/0");
         }
      }
      for (int round = 1; round <= passes; round++) {
         for (String id : ids) {
            if (!ab || "sweep".equals(id)) {
               out.add(id + "/A/" + round);
            } else if (round % 2 == 1) {
               out.add(id + "/A/" + round);
               out.add(id + "/B/" + round);
            } else {
               out.add(id + "/B/" + round);
               out.add(id + "/A/" + round);
            }
         }
      }
      return String.join(",", out);
   }
}
