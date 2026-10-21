package pzopt;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * FrameBatch's background lane (zombieDrawLate, zombie redesign round 3, 2026-10-13, f8-zomb3): the bone batch runs beside
 * the foreground batches through the render. Checked here without the game:
 * <ol>
 * <li>every background task runs exactly once whatever foreground traffic (run, runAsync + join, streams) comes in between,
 *     and its completion runs once, at joinBackground;</li>
 * <li>a foreground run / join never waits for the background: with every background task blocked on a latch the test only
 *     opens after the foreground returned, the foreground still completes (a join of the background would hang);</li>
 * <li>the per-thread flags the draw copy relies on: Worker.bgTask is set exactly inside background tasks, the game thread's
 *     flag only inside the background tasks it runs in joinBackground, never in a foreground task;</li>
 * <li>a new background batch joins the previous one first (at most one in flight).</li>
 * </ol>
 */
public class FrameBatchBackgroundTest {
   public static void main(String[] args) throws Exception {
      long t0 = System.nanoTime();
      Thread game = Thread.currentThread();
      java.util.Random rnd = new java.util.Random(7);
      AtomicInteger flagErrors = new AtomicInteger();

      for (int iter = 0; iter < 2000; iter++) {
         int bgN = 1 + rnd.nextInt(400);
         AtomicIntegerArray bgHits = new AtomicIntegerArray(bgN);
         AtomicInteger done = new AtomicInteger();
         FrameBatch.runBackground(bgN, i -> {
            Thread t = Thread.currentThread();
            boolean flag = t instanceof FrameBatch.Worker w ? w.bgTask : t == game && FrameBatch.gameThreadInBackgroundTask();
            if (!flag) {
               flagErrors.incrementAndGet();
            }
            bgHits.incrementAndGet(i);
            spin(rnd.nextInt(3) == 0 ? 2_000 : 0);
         }, f -> {
            Check.check(f == null, "no background failure");
            done.incrementAndGet();
         });
         // foreground traffic while the background runs
         int kinds = rnd.nextInt(4);
         for (int k = 0; k < kinds; k++) {
            int n = 1 + rnd.nextInt(200);
            AtomicIntegerArray fg = new AtomicIntegerArray(n);
            FrameBatch.Runner r = i -> {
               Thread t = Thread.currentThread();
               if (t instanceof FrameBatch.Worker w ? w.bgTask : FrameBatch.gameThreadInBackgroundTask()) {
                  flagErrors.incrementAndGet();
               }
               fg.incrementAndGet(i);
            };
            switch (rnd.nextInt(3)) {
               case 0 -> Check.check(FrameBatch.run(n, r) == null, "foreground run ok");
               case 1 -> {
                  FrameBatch.runAsync(n, r, f -> { });
                  FrameBatch.join();
               }
               default -> {
                  FrameBatch.runStream(r, f -> { });
                  FrameBatch.publish(n / 2);
                  FrameBatch.publish(n);
                  FrameBatch.seal(n);
                  FrameBatch.join();
               }
            }
            for (int i = 0; i < n; i++) {
               Check.check(fg.get(i) == 1, "iteration " + iter + ": foreground task " + i + " ran once");
            }
            Check.check(FrameBatch.hasBackground(), "a foreground join leaves the background batch alone");
         }
         if (rnd.nextBoolean()) {
            FrameBatch.joinBackground();
         } // else the next runBackground joins it
         if (!FrameBatch.hasBackground()) {
            for (int i = 0; i < bgN; i++) {
               Check.check(bgHits.get(i) == 1, "iteration " + iter + ": background task " + i + " ran once, ran " + bgHits.get(i));
            }
            Check.check(done.get() == 1, "the completion ran once");
         }
         Check.check(System.nanoTime() - t0 < 180_000_000_000L, "no hang");
      }
      FrameBatch.joinBackground();
      Check.check(flagErrors.get() == 0, "bgTask / game-thread flags exact (" + flagErrors.get() + " errors)");

      // the foreground never waits for the background: every background task blocks until the foreground has returned
      CountDownLatch open = new CountDownLatch(1);
      AtomicInteger bgDone = new AtomicInteger();
      FrameBatch.runBackground(64, i -> {
         if (!open.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("latch never opened");
         }
         bgDone.incrementAndGet();
      }, f -> Check.check(f == null, "blocked background finished cleanly"));
      Thread.sleep(50); // the workers are all inside background tasks now
      AtomicIntegerArray fg = new AtomicIntegerArray(500);
      Thread watchdog = new Thread(() -> {
         try {
            Thread.sleep(20_000);
            System.err.println("FrameBatchBackgroundTest: the foreground waited for the background (hang)");
            System.exit(1);
         } catch (InterruptedException e) {
            // finished in time
         }
      });
      watchdog.setDaemon(true);
      watchdog.start();
      Check.check(FrameBatch.run(500, fg::incrementAndGet) == null, "foreground run under a blocked background");
      FrameBatch.runAsync(100, i -> { }, f -> { });
      FrameBatch.join();
      watchdog.interrupt();
      for (int i = 0; i < 500; i++) {
         Check.check(fg.get(i) == 1, "foreground task " + i + " ran once under a blocked background");
      }
      Check.check(bgDone.get() < 64, "the background was still running when the foreground returned");
      open.countDown();
      FrameBatch.joinBackground();
      Check.check(bgDone.get() == 64, "every blocked background task ran after the latch");

      // z3-ld1 hang (2026-10-13): the bone math the game thread ran inside joinBackground reached an AnimationPlayer guard, which
      // joined the background again and waited for the very task it was running. A nested joinBackground now returns at once,
      // and the guard's own test (gameThreadInBackgroundTask) is true there.
      for (int rep = 0; rep < 200; rep++) {
         AtomicInteger nestedOk = new AtomicInteger();
         AtomicIntegerArray hits = new AtomicIntegerArray(300);
         FrameBatch.runBackground(300, i -> {
            hits.incrementAndGet(i);
            if (Thread.currentThread() == game) {
               Check.check(FrameBatch.gameThreadInBackgroundTask() && FrameBatch.gameThreadInTask(), "the game thread knows it runs a background task");
               FrameBatch.joinBackground(); // what AnimBatch.guard -> join did
               nestedOk.incrementAndGet();
            }
         }, f -> Check.check(f == null, "nested-join batch finished cleanly"));
         Thread watchdog2 = new Thread(() -> {
            try {
               Thread.sleep(20_000);
               System.err.println("FrameBatchBackgroundTest: nested joinBackground hung");
               System.exit(1);
            } catch (InterruptedException e) {
               // fine
            }
         });
         watchdog2.setDaemon(true);
         watchdog2.start();
         FrameBatch.joinBackground();
         watchdog2.interrupt();
         for (int i = 0; i < 300; i++) {
            Check.check(hits.get(i) == 1, "nested-join repetition " + rep + ": task " + i + " ran once");
         }
         Check.check(!FrameBatch.hasBackground(), "joined");
      }
      Check.check(FrameBatch.backgroundUsable(), "no background timeout so far");

      // a background task that never ends: the join gives up after its bound, the lane goes off, no hang
      CountDownLatch never = new CountDownLatch(1);
      FrameBatch.runBackground(1, i -> never.await(), f -> { });
      Thread.sleep(50); // a worker holds the one task
      long j0 = System.nanoTime();
      FrameBatch.joinBackground();
      long joinMs = (System.nanoTime() - j0) / 1_000_000L;
      Check.check(joinMs < 5_000, "the bounded background join returned (" + joinMs + " ms)");
      Check.check(!FrameBatch.backgroundUsable() && FrameBatch.backgroundTimeouts == 1, "the lane is off after a timeout");
      never.countDown();
      System.out.println("FrameBatchBackgroundTest ok (" + (System.nanoTime() - t0) / 1_000_000L + " ms)");
   }

   private static void spin(long nanos) {
      long until = System.nanoTime() + nanos;
      while (System.nanoTime() < until) {
         Thread.onSpinWait();
      }
   }
}
