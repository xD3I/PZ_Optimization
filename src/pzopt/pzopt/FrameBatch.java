package pzopt;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The per-frame worker pool of the zombie passes (2026-09-22, docs/plan-zombie-multithread.md §4.1).
 *
 * <p>One batch at a time: the game thread hands over {@code count} indexed tasks and a {@link Runner}, the daemon
 * workers ({@code pzopt-frame-N}, {@code frameThreads} of them, clamped to cores - 1) and the game thread itself pull
 * indices from a shared cursor until none are left, and {@link #run} returns when every task has finished. The wake is
 * a generation counter under one monitor (~50 µs), the join spins briefly then waits in short slices (pzopt.ShortWait: 20 µs parks, yields on Windows); a batch of a
 * few milliseconds hides both. {@code AnimBatch} (the bone math) and {@code ActionEval} (the action-context transitions)
 * are the two users; the game thread never blocks inside a batch on anything but the join.
 *
 * <p>A task that throws marks the batch failed; the runner decides what that means (both users switch themselves off
 * for the rest of the session and take the stock path).
 */
public final class FrameBatch {
   private FrameBatch() {
   }

   /** One indexed task. */
   public interface Runner {
      void run(int index) throws Throwable;
   }

   /**
    * A frame worker. The per-thread fields replace game statics a worker must not flip (animatorParallel): the
    * {@code PerformanceSettings.interpolateAnims} switch of the model-less update and the anim-event capture of the
    * character being updated on this thread.
    */
   public static final class Worker extends Thread {
      public boolean noInterpolate; // animatorParallel: this thread's keyframe sampling behaves as interpolateAnims=false
      public AnimCapture capture; // animatorParallel: the character whose anim events this thread is capturing, or null
      public Object drawRecorder; // tileRecordParallel: the DrawRecorder of the tile draw unit this thread is recording, or null
      public Object[] tl = new Object[64];
      int index; // frameThreadsAlt: this worker's number (a batch uses workers 0..limit-1) // pzopt.Tl: this worker's thread-local values (zombie redesign, f8-zomb2)
      public java.util.Random rng; // randPerWorker (f8-zomb3): this worker's own generator (pzopt.WorkerRand)
      public boolean bgTask; // zombieDrawLate (f8-zomb3): this worker is running a background-lane task
      public int coreGen; // coreFrameCpus alternation (f8-flip): the placement generation this worker last applied
      public Object zCurrent; // entityUpdateParallel (zombie redesign, f8-zomb2): the entity this worker's flight task updates (UpdateBatch.CURRENT without the ThreadLocal lookup)

      Worker(Runnable r, String name) {
         super(r, name);
      }
   }

   public static final int THREADS = Math.max(1, Math.min(Config.FRAME_THREADS, Config.CPUS - 1));
   // frameThreadsAlt (zombie redesign, f8-zomb2): the worker count of the alternation's "on" half (bit 40, in-run A/B of the
   // pool size); the pool starts the larger of the two and a batch only wakes its own count's workers into work
   private static final int ALT = Config.FRAME_THREADS_ALT > 0 ? Math.max(1, Math.min(Config.FRAME_THREADS_ALT, Config.CPUS - 1)) : 0;
   private static final int SPAWN = Math.max(THREADS, ALT);

   private static int batchLimit() {
      return ALT > 0 && ZombAb.on(ZombAb.THREADS_ALT) ? ALT : THREADS;
   }

   /**
    * One batch: its runner, size and counters in one object, published to the workers through {@link #current}. A worker
    * that wakes late works on the batch object it read, whose cursor is exhausted, and never touches a newer batch's
    * counters with an older batch's runner (the first version kept the fields loose and a late worker once read a null
    * runner against the next batch's cursor: an NPE and a task counted as finished without running, 2026-09-22).
    */
   private static final class Batch {
      final Runner runner;
      volatile int count; // entityUpdateStream (zombie redesign, f8-zomb2): grows while the batch is a stream, final once sealed
      volatile boolean sealed; // false only for a stream the game thread is still publishing tasks into
      final int limit = batchLimit(); // frameThreadsAlt: workers past this many leave the batch to the others
      final AtomicInteger cursor = new AtomicInteger();
      final AtomicInteger finished = new AtomicInteger();
      volatile Throwable failure;

      Batch(Runner runner, int count) {
         this.runner = runner;
         this.count = count;
         this.sealed = true;
      }

      Batch(Runner runner) { // a stream: tasks are published as the game thread collects them (runStream / publish / seal)
         this.runner = runner;
         this.count = 0;
         this.sealed = false;
      }

      /** A claimed index past the published count: wait for it to be published (true) or for the seal to drop it (false). */
      private boolean awaitPublished(int i) {
         int spins = 0;
         while (i >= this.count) {
            if (this.sealed) {
               return i < this.count; // seal writes the final count before the flag
            }
            if (++spins < 2000) {
               Thread.onSpinWait();
            } else {
               ShortWait.pause(10_000L);
            }
         }
         return true;
      }

      /** zombieDrawLate (f8-zomb3), background lane: claim and run one task; false when none is left to claim. */
      boolean workOne() {
         int i = this.cursor.getAndIncrement();
         if (i >= this.count) {
            return false;
         }
         try {
            this.runner.run(i);
         } catch (Throwable t) {
            if (this.failure == null) {
               this.failure = t;
            }
         } finally {
            this.finished.incrementAndGet();
         }
         return true;
      }

      boolean hasUnclaimed() {
         return this.cursor.get() < this.count;
      }

      void work() {
         boolean serve = !(Thread.currentThread() instanceof Worker); // the game thread runs the workers' Lua calls (LuaGate)
         int i;
         while (true) {
            i = this.cursor.getAndIncrement();
            if (i >= this.count && (this.sealed && i >= this.count || !this.awaitPublished(i))) {
               break;
            }
            if (serve) {
               LuaGate.service();
               gtTaskDepth++; // zombie round 3: the game thread inside a task (ZombieLate.touch must not apply from here)
            }
            try {
               this.runner.run(i);
            } catch (Throwable t) {
               if (this.failure == null) {
                  this.failure = t;
               }
            } finally {
               if (serve) {
                  gtTaskDepth--;
               }
               this.finished.incrementAndGet();
            }
         }
      }
   }

   private static final Object gate = new Object();
   private static volatile int generation; // written under gate; volatile so a spinning worker sees a new batch without the monitor
   private static volatile Batch current; // the batch the workers should join
   private static Thread[] workers;
   private static boolean running; // game thread only

   public static long batches, tasks, waitNanos, workNanos;
   public static long asyncBatches, asyncJoinNanos, asyncHelped; // animBatchAsync: batches started without a join, game-thread time at their joins, tasks the game thread took at the join

   /** Called on the game thread when an asynchronous batch has been joined, with its first failure or null. */
   public interface Completion {
      void done(Throwable failure);
   }

   private static Batch pending; // game thread only: the asynchronous batch not joined yet
   private static Completion pendingDone;

   /** True while an asynchronous batch may still be running (game thread only). */
   public static boolean hasPending() {
      return pending != null;
   }

   /**
    * Game thread: start tasks 0..count-1 on the workers only and return at once (animBatchAsync). The batch is joined by
    * {@link #join()}, which every later {@link #run} / {@link #runAsync} does first, so at most one batch is ever in
    * flight and the one-batch-at-a-time rule of the workers holds. {@code done} runs on the game thread at the join.
    */
   public static void runAsync(int n, Runner r, Completion done) {
      landEntityFlight(); // spec 3.4: a nested batch under a combined flight lands it through UpdateBatch, not join()
      join();
      if (n <= 0) {
         done.done(null);
         return;
      }
      if (running) {
         throw new IllegalStateException("FrameBatch.runAsync inside a batch");
      }
      if (workers == null) {
         start();
      }
      batches++;
      tasks += n;
      asyncBatches++;
      Batch batch = new Batch(r, n);
      pending = batch;
      pendingDone = done;
      current = batch;
      synchronized (gate) {
         generation++;
         gate.notifyAll();
      }
   }

   // ---- the background lane (zombieDrawLate, zombie redesign round 3, 2026-10-13, f8-zomb3) ----
   // One batch beside the foreground one: the workers take its tasks one at a time whenever no foreground batch has work for
   // them, and go back to the foreground after each task, so a render-time batch (tile records, render prep, the bakes' own
   // batches) never waits for it nor joins it. Only its owner joins it (joinBackground); join() and the foreground's
   // one-batch rule ignore it. The bone batch is its only user: it runs from FinishAnimation through the render.
   private static volatile Batch background;
   private static Completion backgroundDone;
   private static boolean gtInBackground; // game thread: running a background task inside joinBackground
   public static long backgrounds, backgroundJoinNanos, backgroundHelped;

   /** Game thread: start a background batch (the previous one is joined first; a foreground batch in flight stays). */
   public static void runBackground(int n, Runner r, Completion done) {
      joinBackground();
      if (n <= 0) {
         done.done(null);
         return;
      }
      if (workers == null) {
         start();
      }
      batches++;
      tasks += n;
      backgrounds++;
      backgroundDone = done;
      background = new Batch(r, n);
      synchronized (gate) {
         gate.notifyAll(); // parked workers re-check: the background has work
      }
   }

   /** True while a background batch has not been joined. */
   public static boolean hasBackground() {
      return background != null;
   }

   private static int gtTaskDepth; // game thread (or a non-worker caller): inside a batch task it runs itself (work / helpOne)

   /** Game thread: inside a batch task it runs itself (a join or a help): a hook there must not start or join a batch. */
   public static boolean gameThreadInTask() {
      return gtTaskDepth > 0 || gtInBackground;
   }

   /** zombieDrawLate: the game thread is running a background task (the bone math reads the live arrays). */
   public static boolean gameThreadInBackgroundTask() {
      return gtInBackground;
   }

   private static boolean joiningBackground; // game thread: inside joinBackground (a task it runs may reach a guard: no nested join)
   private static volatile boolean backgroundFailed; // a background join gave up waiting: the lane is off for the session
   public static long backgroundTimeouts, backgroundNested;
   private static final long BACKGROUND_WAIT_LIMIT_NANOS = 200_000_000L;

   /** zombieDrawLate: false once a background join gave up (the bone batch is then joined like any async batch). */
   public static boolean backgroundUsable() {
      return !backgroundFailed;
   }

   /**
    * Game thread: finish the background batch: take its unclaimed tasks here, wait for the rest, run its completion. A call
    * from inside a task this thread runs for the join (a guard reached by the bone math) returns at once: the outer join
    * finishes the batch. The wait for tasks running on workers is bounded: past 200 ms it is logged once, the lane is off for
    * the session and the completion runs (the task that never ended is a lost one; one slow frame, not a hung game).
    */
   public static void joinBackground() {
      Batch batch = background;
      if (batch == null) {
         return;
      }
      if (joiningBackground) {
         backgroundNested++;
         return;
      }
      joiningBackground = true;
      try {
         joinBackground(batch);
      } finally {
         joiningBackground = false;
      }
   }

   private static void joinBackground(Batch batch) {
      long t0 = System.nanoTime();
      gtInBackground = true;
      try {
         while (batch.workOne()) {
            backgroundHelped++;
         }
      } finally {
         gtInBackground = false;
      }
      int spins = 0;
      while (batch.finished.get() < batch.count) {
         if (++spins < 200) {
            Thread.onSpinWait();
         } else {
            ShortWait.pause(20_000L);
            if (System.nanoTime() - t0 > BACKGROUND_WAIT_LIMIT_NANOS) {
               backgroundTimeouts++;
               if (!backgroundFailed) {
                  backgroundFailed = true;
                  Log.warn("frame batch: a background task did not finish in 200 ms (" + batch.finished.get() + " of " + batch.count + "); the background lane is off for the session");
               }
               break;
            }
         }
      }
      background = null;
      Completion done = backgroundDone;
      backgroundDone = null;
      backgroundJoinNanos += System.nanoTime() - t0;
      done.done(batch.failure);
   }

   public static long helped; // tasks of an asynchronous batch the game thread ran while waiting for one (helpOne)

   // ---- entityUpdateStream (zombie redesign pass, 2026-10-13, f8-zomb2) ----
   // An asynchronous batch whose task count grows while the game thread still collects: workers start on the first
   // published tasks at once, claim an index past the count and wait for it to be published (or for the seal to drop it).
   public static long streams, streamSealedByJoin;

   /**
    * Game thread: start an empty stream on the workers only (joined like runAsync's batch, after {@link #seal}). Returns the
    * stream's token: {@link #publish(Object, int)} / {@link #seal(Object, int)} / {@link #streamOpen(Object)} act on that stream
    * only (f8-streamfix: the untokened calls act on whatever batch is pending, so a user whose stream another user had joined and
    * replaced with its own stream would have published into that one).
    */
   public static Object runStream(Runner r, Completion done) {
      landEntityFlight();
      join();
      if (running) {
         throw new IllegalStateException("FrameBatch.runStream inside a batch");
      }
      if (workers == null) {
         start();
      }
      batches++;
      asyncBatches++;
      streams++;
      Batch batch = new Batch(r);
      pending = batch;
      pendingDone = done;
      current = batch;
      synchronized (gate) {
         generation++;
         gate.notifyAll();
      }
      return batch;
   }

   /** Game thread: tasks 0..count-1 of the stream {@code token} may run, if it is still the pending, unsealed batch. */
   public static void publish(Object token, int count) {
      if (token != null && token == pending) {
         publish(count);
      }
   }

   /** Game thread: the final task count of the stream {@code token}, if it is still pending (a join already sealed it otherwise). */
   public static void seal(Object token, int count) {
      if (token != null && token == pending) {
         seal(count);
      }
   }

   /** Game thread: the asynchronous batch {@link #runAsync} just started (the token of {@link #isPending}), or null. */
   public static Object pendingBatch() {
      return pending;
   }

   /** Game thread: true while {@code token} is still the pending batch (f9-los: an in-order consumer helps only its own stream). */
   public static boolean isPending(Object token) {
      return token != null && token == pending;
   }

   /** Game thread: true while the stream {@code token} is pending and unsealed. */
   public static boolean streamOpen(Object token) {
      return token != null && token == pending && !((Batch) token).sealed;
   }

   /** Game thread: tasks 0..count-1 of the pending stream may run (count only grows). */
   public static void publish(int count) {
      Batch batch = pending;
      if (batch != null && !batch.sealed && count > batch.count) {
         batch.count = count;
      }
   }

   /** Game thread: the pending stream's final task count; no task past it runs. */
   public static void seal(int count) {
      Batch batch = pending;
      if (batch != null && !batch.sealed) {
         tasks += Math.max(count, batch.count);
         batch.count = Math.max(count, batch.count);
         batch.sealed = true;
      }
   }

   /** Game thread: true while a stream is pending and unsealed. */
   public static boolean streamOpen() {
      Batch batch = pending;
      return batch != null && !batch.sealed;
   }

   private static volatile int lastPreClaimed; // spec 3.5 (review finding 7): cursor position at the last join's entry

   /**
    * Tasks the workers had claimed when the last join started — the combined dispatch's runway metric.
    *
    * <p>Per JOIN, not per user. This pool is shared: {@code AnimBatch}, {@code ActionEval}, {@code LightingBatch},
    * {@code SeparateBatch} and {@code CharDraw} all land through {@link #join()}, several of them later in the same
    * frame than the entity flight, so the value standing here belongs to whichever batch joined last. A user that
    * wants ITS number must read this at its own join and keep it: {@code UpdateBatch.joinPending} does, and
    * {@code UpdateBatch.getPreClaimed()} is the entity flight's own cumulative total.
    */
   public static int lastPreClaimed() {
      return lastPreClaimed;
   }

   /**
    * The combined dispatch's nested-batch guard (spec 3.4): a batch user reached from an inline entity's update
    * while the frame's own entity flight is airborne lands that flight FULLY first — the squares, the emitter and
    * Lua drains, the snapshot window, the failure latch. A raw {@link #join()} here would strip all of it, and two
    * batches in flight break the one-batch rule the workers are built on.
    *
    * <p>The existing {@code running} check cannot stand in for this: {@code running} is false for the whole combined
    * flight ({@link #runAsync} returns at once) and false again during the inline phase, which is exactly the window
    * the guard exists for.
    *
    * <p>It fires only from a thread that may join at all. From a {@link Worker} — or from the game thread while it
    * is working a batch task, which is the same situation — the join would wait for {@code finished == count},
    * a count that includes the caller's own unfinished task: it would wait for itself. Such a re-entrant call is
    * this class's pre-existing hazard (today {@code run} throws on it through {@code running}, and {@code runAsync}
    * would hang in {@code join}); the guard deliberately leaves that behaviour untouched instead of adding a
    * deadlock of its own. The fast path is one plain boolean load, so the thread test is never reached in a frame
    * without a combined flight.
    */
   private static void landEntityFlight() {
      if (UpdateBatch.hasPendingBatch() && !(Thread.currentThread() instanceof Worker) && !UpdateBatch.onBatchTaskNow()) {
         UpdateBatch.nestedJoin();
      }
   }

   /**
    * Game thread, while waiting for a task of the asynchronous batch: claim the next task no worker has started and run
    * it here. False when every task has been claimed (the caller spins for the one it waits for).
    */
   public static boolean helpOne() {
      LuaGate.service(); // every caller is a game-thread wait loop
      Batch batch = pending;
      if (batch == null || !batch.sealed) { // entityUpdateStream: an index claimed past an unsealed stream's count could never run
         return false;
      }
      int i = batch.cursor.getAndIncrement();
      if (i >= batch.count) {
         return false;
      }
      helped++;
      gtTaskDepth++;
      try {
         batch.runner.run(i);
      } catch (Throwable t) {
         if (batch.failure == null) {
            batch.failure = t;
         }
      } finally {
         gtTaskDepth--;
         batch.finished.incrementAndGet();
      }
      return true;
   }

   /**
    * Game thread: finish the asynchronous batch, if one is in flight: take whatever tasks no worker has started yet, wait
    * for the rest, then run its completion. Cheap when nothing is pending.
    */
   public static void join() {
      Batch batch = pending;
      if (batch == null) {
         return;
      }
      if (!batch.sealed) { // entityUpdateStream: its owner seals it before any join; a join from elsewhere lands what is published
         streamSealedByJoin++;
         seal(batch.count);
      }
      pending = null;
      Completion done = pendingDone;
      pendingDone = null;
      long t0 = System.nanoTime();
      int before = batch.cursor.get();
      lastPreClaimed = Math.max(before, 0); // pzopt spec 3.5: the fix's acceptance number — tasks the workers claimed before this join
      batch.work();
      if (before < batch.count) {
         asyncHelped += batch.count - Math.max(before, 0);
      }
      int spins = 0;
      while (batch.finished.get() < batch.count) {
         LuaGate.service(); // a worker may be waiting for its Lua call
         if (++spins < 200) {
            Thread.onSpinWait();
         } else {
            ShortWait.pause(20_000L);
         }
      }
      asyncJoinNanos += System.nanoTime() - t0;
      done.done(batch.failure);
   }

   /**
    * f8-render1 (recording passes): {@link #join} with a bound. False when the batch did not finish within {@code timeoutNs}:
    * it is abandoned (its completion never runs; a worker still inside it is lost to later batches, which the other workers
    * and the game thread finish), so the caller can take its stock path instead of hanging.
    */
   public static boolean joinWithin(long timeoutNs) {
      Batch batch = pending;
      if (batch == null) {
         return true;
      }
      if (!batch.sealed) { // integ-r2: as join(): an unsealed entityUpdateStream lands what is published before the bounded wait
         streamSealedByJoin++;
         seal(batch.count);
      }
      pending = null;
      Completion done = pendingDone;
      pendingDone = null;
      long t0 = System.nanoTime();
      batch.work();
      int spins = 0;
      while (batch.finished.get() < batch.count) {
         LuaGate.service();
         if (++spins < 200) {
            Thread.onSpinWait();
         } else {
            ShortWait.pause(20_000L);
            if ((spins & 15) == 0 && System.nanoTime() - t0 > timeoutNs) {
               Log.warn("frame batch: an asynchronous batch did not finish in " + timeoutNs / 1_000_000L + " ms (" + batch.finished.get() + " of " + batch.count
                  + " tasks); abandoned");
               asyncJoinNanos += System.nanoTime() - t0;
               return false;
            }
         }
      }
      asyncJoinNanos += System.nanoTime() - t0;
      done.done(batch.failure);
      return true;
   }

   /** f8-render1: what {@link #runWithin} returns when its batch did not finish in time (the batch is abandoned). */
   public static final class Timeout extends RuntimeException {
      Timeout(String msg) {
         super(msg, null, false, false);
      }
   }

   /** f8-render1 (recording passes): {@link #run} with a bound; a {@link Timeout} when the tasks did not all finish in time. */
   public static Throwable runWithin(int n, Runner r, long timeoutNs) {
      landEntityFlight();
      if (n <= 0) {
         return null;
      }
      if (running) {
         throw new IllegalStateException("FrameBatch.run is not reentrant");
      }
      if (!joinWithin(timeoutNs)) {
         return new Timeout("the batch before did not finish");
      }
      if (workers == null) {
         start();
      }
      running = true;
      long t0 = System.nanoTime();
      batches++;
      tasks += n;
      Batch batch = new Batch(r, n);
      current = batch;
      synchronized (gate) {
         generation++;
         gate.notifyAll();
      }
      batch.work();
      int spins = 0;
      while (batch.finished.get() < n) {
         LuaGate.service();
         if (++spins < 200) {
            Thread.onSpinWait();
         } else {
            ShortWait.pause(20_000L);
            if ((spins & 15) == 0 && System.nanoTime() - t0 > timeoutNs) {
               running = false;
               return new Timeout("a batch did not finish in " + timeoutNs / 1_000_000L + " ms (" + batch.finished.get() + " of " + n + " tasks)");
            }
         }
      }
      running = false;
      return batch.failure;
   }

   /**
    * Game thread: run tasks 0..count-1 on the workers and this thread, wait for all of them, and return the first
    * exception a task threw (null when every task finished).
    */
   public static Throwable run(int n, Runner r) {
      landEntityFlight(); // spec 3.4: a nested batch under a combined flight lands it through UpdateBatch, not join()
      if (n <= 0) {
         return null;
      }
      if (running) {
         throw new IllegalStateException("FrameBatch.run is not reentrant");
      }
      join(); // animBatchAsync: one batch in flight at a time
      if (workers == null) {
         start();
      }
      running = true;
      long t0 = System.nanoTime();
      batches++;
      tasks += n;
      Batch batch = new Batch(r, n);
      current = batch;
      synchronized (gate) {
         generation++;
         gate.notifyAll();
      }
      batch.work();
      long t1 = System.nanoTime();
      int spins = 0;
      while (batch.finished.get() < n) {
         LuaGate.service(); // a worker may be waiting for its Lua call
         if (++spins < 200) {
            Thread.onSpinWait();
         } else {
            ShortWait.pause(20_000L);
         }
      }
      long t2 = System.nanoTime();
      workNanos += t1 - t0;
      waitNanos += t2 - t1;
      running = false;
      return batch.failure;
   }

   private static void start() {
      // Force CorePlacement's class initialization HERE, on the calling thread. Every worker's first act is
      // CorePlacement.background(), so without this the first worker to arrive runs that <clinit> (Config, the JMX
      // thread bean: 17 ms measured in a bare JVM) while the other fourteen queue on the class-init lock, and the
      // pool's first batch runs almost entirely on the caller — with the combined dispatch that is a whole frame of
      // zombies serialized. Free in the game, where the frame limiter has called onStep since boot; active() has no
      // side effect, unlike background(), which would put the CALLING thread on the efficient cores on macOS.
      CorePlacement.active();
      workers = new Thread[SPAWN];
      for (int k = 0; k < SPAWN; k++) {
         Worker t = new Worker(FrameBatch::workerLoop, "pzopt-frame-" + k);
         t.index = k;
         t.setDaemon(true);
         t.setPriority(Thread.NORM_PRIORITY);
         workers[k] = t;
         t.start();
      }
      Log.info("frameThreads: " + THREADS + " worker threads for the per-frame zombie batches");
   }

   private static final long SPIN_NANOS = Config.FRAME_SPIN_US * 1000L;

   private static void workerLoop() {
      CorePlacement.frameCritical(); // macOS: workerQosMac (utility by default, E cores first)
      Worker self = (Worker) Thread.currentThread();
      int seen = 0;
      while (true) {
         // frameSpinUs: the frame's batches come back to back (separation, transitions, animators, bones, lighting);
         // a worker that just finished one spins a little for the next instead of parking, so it starts within
         // microseconds instead of queueing on the monitor behind the others
         if (SPIN_NANOS > 0 && GtAb.on(GtAb.FRAME_SPIN)) { // devGtAlternate: frameSpin, the within-run A/B of the spin
            long until = System.nanoTime() + SPIN_NANOS;
            while (generation == seen && System.nanoTime() < until) {
               Thread.onSpinWait();
            }
         }
         if (generation != seen) {
            seen = generation;
            CorePlacement.frameWorker(self); // f8-flip: coreFrameCpus alternation
            CorePlacement.frameCritical(); // f11-mac: workerQosMac alternation (one thread-local read otherwise)
            Batch batch = current;
            if (batch != null && self.index < batch.limit) {
               batch.work();
               PoolStats.flush(); // poolStatsBatched: this worker's pool counts of the batch
            }
            continue;
         }
         Batch bg = background; // zombieDrawLate (f8-zomb3): one background task, then the foreground is looked at again
         if (bg != null && bg.hasUnclaimed()) {
            self.bgTask = true;
            boolean ran;
            try {
               ran = bg.workOne();
            } finally {
               self.bgTask = false;
            }
            if (!ran) {
               PoolStats.flush();
            }
            continue;
         }
         synchronized (gate) {
            while (generation == seen) {
               Batch b2 = background;
               if (b2 != null && b2.hasUnclaimed()) {
                  break; // zombieDrawLate: background work arrived
               }
               try {
                  gate.wait();
               } catch (InterruptedException e) {
                  return;
               }
            }
         }
      }
   }

   /** One line for the periodic FBORenderCell log. */
   public static String describe() {
      return "frame batches=" + batches + " tasks=" + tasks + " work ms=" + (workNanos / 1_000_000L) + " wait ms=" + (waitNanos / 1_000_000L)
            + " async=" + asyncBatches + " helped=" + helped + " async join ms=" + (asyncJoinNanos / 1_000_000L) + " async helped=" + asyncHelped;
   }
}
