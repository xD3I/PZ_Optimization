package pzopt;

import java.util.IdentityHashMap;
import zombie.network.statistics.counters.PoolCounter;
import zombie.util.Pool;

/**
 * poolStatsBatched (2026-09-23): the game counts every pooled-object alloc and release in shared per-pool statistics
 * counters ({@code PoolCounter}: {@code AtomicDouble} compare-and-set loops). With the animators and the bone math on the
 * frame workers, nine threads hit the same few counters thousands of times a frame and every animator task ran 2.4x
 * slower than on one thread (Louisville: 5.0 ms of task time a frame with 8 workers, 2.1 ms with 1). A frame worker now
 * tallies its counts per pool stack here and adds them to the shared counters once at the end of each batch
 * ({@link FrameBatch}); the totals are the same, only published a batch later. The first event of each pool stack
 * still goes through the stock call, which registers the stack for monitoring.
 */
public final class PoolStats {
   private PoolStats() {
   }

   public static final boolean ENABLED = Config.POOL_STATS_BATCHED;

   private static final class Tally {
      PoolCounter counter;
      long alloc, reused, release;
   }

   /** Per worker thread: pool stack -> its pending counts. */
   private static final Tl<IdentityHashMap<Pool.PoolStacks, Tally>> TALLIES = new Tl<>(IdentityHashMap::new);

   /** True when the event was tallied (a frame worker); false = the caller counts it the stock way. */
   public static boolean alloc(PoolCounter counter, Pool.PoolStacks stacks, boolean reused) {
      if (!(Thread.currentThread() instanceof FrameBatch.Worker)) {
         return false;
      }
      IdentityHashMap<Pool.PoolStacks, Tally> map = TALLIES.get();
      Tally t = map.get(stacks);
      if (t == null) {
         t = new Tally();
         t.counter = counter;
         map.put(stacks, t);
         return false; // first sight: the stock call registers the stack with its monitor
      }
      if (reused) {
         t.reused++;
      } else {
         t.alloc++;
      }
      return true;
   }

   public static boolean release(PoolCounter counter, Pool.PoolStacks stacks) {
      if (!(Thread.currentThread() instanceof FrameBatch.Worker)) {
         return false;
      }
      IdentityHashMap<Pool.PoolStacks, Tally> map = TALLIES.get();
      Tally t = map.get(stacks);
      if (t == null) {
         t = new Tally();
         t.counter = counter;
         map.put(stacks, t);
         return false;
      }
      t.release++;
      return true;
   }

   /** A frame worker at the end of a batch: publish its tallies. */
   static void flush() {
      if (!ENABLED) {
         return;
      }
      for (Tally t : TALLIES.get().values()) {
         if (t.alloc != 0) {
            t.counter.alloc.increase(t.alloc);
            t.alloc = 0;
         }
         if (t.reused != 0) {
            t.counter.reused.increase(t.reused);
            t.counter.reusedTotal.increase(t.reused);
            t.reused = 0;
         }
         if (t.release != 0) {
            t.counter.release.increase(t.release);
            t.release = 0;
         }
      }
   }
}
