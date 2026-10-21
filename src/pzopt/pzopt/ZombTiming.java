package pzopt;

/**
 * Per-frame timers of the zombie phases on the game thread (zombie redesign pass, 2026-10-13, f8-zomb2), written as extra
 * columns of pzopt-gtab.out after GtAb's own sections (only while a devGtAlternate alternation runs, like GtAb's timers).
 * The flight's worker CPU and wall are measured on the workers (one nanoTime pair per task) and folded in at the join.
 */
public final class ZombTiming {
   private ZombTiming() {
   }

   public static final int SEP = 0; // SeparateBatch.run
   public static final int COLLECT = 1; // the buckets' collection loop (update minus separation, dispatch, inline, join)
   public static final int DISPATCH = 2; // UpdateBatch.dispatchCombined (snapshot, vehicle polys, wake)
   public static final int INLINE = 3; // UpdateBatch.runInlinePhase (the runway under the flight)
   public static final int JOIN = 4; // FrameBatch.join of the entity flight (the game thread's tasks + the wait)
   public static final int DRAIN = 5; // joinPending after the join: squares, light effects, emitters, physics, Lua replay
   public static final int FLIGHT_CPU = 6; // summed task ns of the entity flight (all threads)
   public static final int FLIGHT_WALL = 7; // dispatch -> last task finished
   public static final int FLIGHT_N = 8; // count: entities in the flight
   public static final int PRECLAIMED = 9; // count: tasks the workers had claimed when the game thread joined
   public static final int GT_TASKS = 10; // count: flight tasks the game thread ran
   public static final int EVAL_APPLY1 = 11; // AnimParallel.apply step 1 (state changes + arm)
   public static final int EVAL_APPLY3 = 12; // AnimParallel.apply step 3 (events + finish)
   public static final int VOCALS = 13; // reserved
   public static final int X0 = 14, X1 = 15, X2 = 16, X3 = 17; // free experiment columns
   public static final int HOIST = 18; // entityUpdateStreamHoist: the players' and vehicles' updates before the collection
   public static final int SCENE_ADD = 19; // sceneCullZombies' second loop (model slots, ModelManager.Add)
   public static final int RENDERLAST = 20; // the cell's renderlast loop
   public static final int X4 = 21, X5 = 22;
   public static final int N = 23;

   public static final String HEADER = " z_sep z_collect z_dispatch z_inline z_join z_drain z_flight_cpu z_flight_wall z_flight_n z_preclaimed z_gt_tasks z_apply1 z_apply3 z_vocals z_x0 z_x1 z_x2 z_x3 z_hoist z_scene_add z_renderlast z_x4 z_x5";

   private static final long[] NS = new long[N];
   public static final boolean ON = GtAb.TIMING;

   // the flight's worker-side clock: one slot per thread (no shared cache line per task), summed at the join
   private static final java.util.concurrent.CopyOnWriteArrayList<long[]> SLOTS = new java.util.concurrent.CopyOnWriteArrayList<>();
   private static final Tl<long[]> SLOT = new Tl<>(() -> {
      long[] s = new long[3]; // cpu ns, last end ns, game-thread tasks
      SLOTS.add(s);
      return s;
   });
   private static long flightStart;

   public static long begin() {
      return ON ? System.nanoTime() : 0L;
   }

   public static void end(int col, long t) {
      if (t != 0L) {
         NS[col] += System.nanoTime() - t;
      }
   }

   public static void add(int col, long v) {
      if (ON) {
         NS[col] += v;
      }
   }

   /** Game thread: the flight was just dispatched. */
   public static void flightDispatched(int n) {
      if (!ON) {
         return;
      }
      for (long[] s : SLOTS) {
         s[0] = 0L;
         s[1] = 0L;
         s[2] = 0L;
      }
      flightStart = System.nanoTime();
      NS[FLIGHT_N] += n;
   }

   /** Any thread: one flight task took [t0, now]. */
   public static void task(long t0, boolean gameThread) {
      long t1 = System.nanoTime();
      long[] s = SLOT.get();
      s[0] += t1 - t0;
      s[1] = t1;
      if (gameThread) {
         s[2]++;
      }
   }

   /** Game thread, after the flight's join (every task finished: the slots are published by the join). */
   public static void flightJoined(int preClaimed) {
      if (!ON) {
         return;
      }
      long cpu = 0L, end = 0L, gt = 0L;
      for (long[] s : SLOTS) {
         cpu += s[0];
         end = Math.max(end, s[1]);
         gt += s[2];
      }
      NS[FLIGHT_CPU] += cpu;
      if (end > flightStart) {
         NS[FLIGHT_WALL] += end - flightStart;
      }
      NS[PRECLAIMED] += preClaimed;
      NS[GT_TASKS] += gt;
   }

   /** GtAb.frame: append this frame's columns and reset. */
   static void append(StringBuilder sb) {
      for (int i = 0; i < N; i++) {
         sb.append(' ').append(NS[i]);
      }
      java.util.Arrays.fill(NS, 0L);
   }
}
