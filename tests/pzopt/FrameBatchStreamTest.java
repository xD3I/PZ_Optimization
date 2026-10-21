package pzopt;

import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * FrameBatch streams (entityUpdateStream, postupdateFuse; zombie redesign pass 2026-10-13, f8-zomb2): every published task
 * runs exactly once whatever the publish pattern, a join by another batch user seals the stream with what it has, helpOne
 * never claims past an unsealed count, and the task list of a stream (StreamTasks) is only released after the join.
 *
 * <p>The last part reproduces the zomb2-all1 hang deterministically: ActionEval released its list at the seal, a task that
 * had not started then read a null and the evaluation it carried never ran. Here a task is held until the list was released
 * at the seal (the old order) and must see the null; with the release after the join (the new order) no task can.
 */
public class FrameBatchStreamTest {
   public static void main(String[] args) throws Exception {
      long t0 = System.nanoTime();
      java.util.Random rnd = new java.util.Random(42);
      for (int iter = 0; iter < 3000; iter++) {
         int total = 1 + rnd.nextInt(600);
         AtomicIntegerArray hits = new AtomicIntegerArray(total);
         FrameBatch.runStream(hits::incrementAndGet, f -> Check.check(f == null, "no task failure"));
         int published = 0;
         boolean externalJoin = rnd.nextInt(8) == 0;
         int joinAt = rnd.nextInt(total + 1);
         while (published < total) {
            Check.check(!FrameBatch.helpOne(), "helpOne does not claim on an unsealed stream");
            int step = Math.min(total - published, 1 + rnd.nextInt(40));
            published += step;
            FrameBatch.publish(published);
            if (externalJoin && published >= joinAt) {
               // another batch user mid-stream: run() joins (and seals) the pending stream first
               FrameBatch.run(3, i -> { });
               Check.check(!FrameBatch.streamOpen(), "a join seals the stream");
               break;
            }
         }
         if (published < total) {
            final int from = published;
            FrameBatch.runAsync(total - from, i -> hits.incrementAndGet(from + i), f -> { });
         } else {
            FrameBatch.seal(total);
         }
         FrameBatch.join();
         for (int i = 0; i < total; i++) {
            Check.check(hits.get(i) == 1, "iteration " + iter + ": task " + i + " ran once, ran " + hits.get(i));
         }
         Check.check(System.nanoTime() - t0 < 120_000_000_000L, "the streams finish (no hang)");
      }

      // StreamTasks: the old order (release at the seal) loses a task that had not started; the new one cannot
      Check.check(lostWithRelease(true) > 0, "releasing at the seal is caught: a held task read a null");
      Check.check(lostWithRelease(false) == 0, "releasing after the join: every task read its item");
      System.out.println("FrameBatchStreamTest ok (" + (System.nanoTime() - t0) / 1_000_000L + " ms)");
   }

   /** Tasks read their item; task 0 waits until the game thread has sealed (and, in the old order, released). Returns the nulls read. */
   private static int lostWithRelease(boolean atSeal) {
      StreamTasks<Object> list = new StreamTasks<>();
      java.util.concurrent.CountDownLatch sealed = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.atomic.AtomicInteger nulls = new java.util.concurrent.atomic.AtomicInteger();
      FrameBatch.runStream(j -> {
         if (j == 0 && Thread.currentThread() instanceof FrameBatch.Worker) {
            sealed.await();
         }
         if (list.item(j) == null) {
            nulls.incrementAndGet();
         }
      }, f -> { });
      int n = 0;
      for (int i = 0; i < 64; i++) {
         n = list.add(new Object(), false);
         FrameBatch.publish(n);
      }
      // let a worker claim task 0 (and block in it) before the seal
      long until = System.nanoTime() + 50_000_000L;
      while (System.nanoTime() < until) {
         Thread.onSpinWait();
      }
      FrameBatch.seal(n);
      if (atSeal) {
         list.release();
      }
      sealed.countDown();
      FrameBatch.join();
      if (!atSeal) {
         list.release();
      }
      return nulls.get();
   }
}
