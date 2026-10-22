package pzopt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
      Map<String, Integer> each = new HashMap<>();
      for (String id : ids) {
         each.put(id, passes);
      }
      return steps(ids, each, ab, warmup);
   }

   /**
    * The same with each scenario's own number of passes (the menu's planner, 2026-10-21): the rounds go up to the most
    * passes, a scenario takes part in the rounds up to its own count.
    */
   static String steps(List<String> ids, Map<String, Integer> passes, boolean ab, boolean warmup) {
      List<String> out = new ArrayList<>();
      if (warmup) {
         for (String id : ids) {
            out.add(id + "/W/0");
         }
      }
      int rounds = 0;
      for (String id : ids) {
         rounds = Math.max(rounds, passes.getOrDefault(id, 1));
      }
      for (int round = 1; round <= rounds; round++) {
         for (String id : ids) {
            if (round > passes.getOrDefault(id, 1)) {
               continue;
            }
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
