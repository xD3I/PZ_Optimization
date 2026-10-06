package pzopt;

import zombie.iso.IsoMovingObject;

/**
 * The combined dispatch (entityUpdatePipeline, spec 2026-09-27): the whole frame's batchables in ONE
 * workers-only flight, per-entity multiplier and simulation level, the inline phase under the flight,
 * the nested-batch guard, and the runway metric preClaimed. Runtime, real IsoMovingObjects — the same
 * probe subclass idiom as UpdateBatchTest (a bare IsoMovingObject subclass is constructible in a plain
 * JVM; its four calls record into arrays).
 */
public class CombinedDispatchTest {

   /** A batchable probe: records the pom() and level its update saw, and which thread class ran it. */
   static class Probe extends IsoMovingObject {
      static final java.util.List<String> ORDER = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
      final String name;
      volatile float sawPom = Float.NaN;
      volatile int sawLevel = -1;
      volatile boolean onWorker;
      volatile long spinNanos;

      Probe(String name, long spinNanos) {
         super(false); // as in UpdateBatchTest: the no-arg IsoMovingObject registers into the cell and NPEs in a plain JVM
         this.name = name;
         this.spinNanos = spinNanos;
      }

      @Override
      public void setCurrentSimulationLevel(zombie.UpdateSchedulerSimulationLevel l) {
         // level probes use the int-recording seam below; the enum setter is what the runner calls in prod —
         // record its ordinal so the per-entity level assertion works either way
         this.sawLevel = l.ordinal();
      }

      @Override
      public void preupdate() {
      }

      @Override
      public void frameStep() {
      }

      @Override
      public void update() {
         this.sawPom = UpdateBatch.pom(zombie.GameTime.getInstance());
         this.onWorker = Thread.currentThread() instanceof FrameBatch.Worker;
         ORDER.add(this.name);
         long until = System.nanoTime() + this.spinNanos;
         while (System.nanoTime() < until) {
            Thread.onSpinWait(); // busy so the runway/ordering assertions have something to overlap
         }
      }

      @Override
      public void postupdate() {
      }
   }

   public static void main(String[] args) throws Exception {
      zombie.GameTime gt = zombie.GameTime.getInstance();

      // ── 1. per-entity multiplier and level: two "buckets" queued into one combined frame ──
      UpdateBatch.latchFrame(true); // combined on for this frame (test seam: forces the latch)
      gt.perObjectMultiplier = 2.0F;
      Probe a1 = new Probe("a1", 0);
      Probe a2 = new Probe("a2", 0);
      UpdateBatch.add(a1, 0);
      UpdateBatch.add(a2, 0);
      gt.perObjectMultiplier = 8.0F;
      Probe b1 = new Probe("b1", 0);
      UpdateBatch.add(b1, 3);
      gt.perObjectMultiplier = 1.0F;

      UpdateBatch.dispatchCombined();
      UpdateBatch.joinPending();
      Check.check(a1.sawPom == 2.0F && a2.sawPom == 2.0F, "group A tasks read their own bucket's multiplier");
      Check.check(b1.sawPom == 8.0F, "group B task reads ITS bucket's multiplier, not the last one set");
      Check.check(a1.sawLevel == 0 && b1.sawLevel == 3, "each task ran under its own simulation level");
      Check.check(UpdateBatch.pom(gt) == 1.0F, "the game thread's pom() follows the live field after the join");

      // ── 2. inline queue: order and multiplier per group, global restored ──
      Probe.ORDER.clear();
      UpdateBatch.latchFrame(true);
      gt.perObjectMultiplier = 4.0F;
      Probe i1 = new Probe("i1", 0);
      Probe i2 = new Probe("i2", 0);
      UpdateBatch.queueInline(i1, 1);
      UpdateBatch.queueInline(i2, 1);
      gt.perObjectMultiplier = 16.0F;
      Probe i3 = new Probe("i3", 0);
      UpdateBatch.queueInline(i3, 4);
      gt.perObjectMultiplier = 1.0F;
      UpdateBatch.dispatchCombined(); // empty batch: inline phase must still run
      UpdateBatch.runInlinePhase();
      UpdateBatch.joinPending();
      Check.check(Probe.ORDER.equals(java.util.List.of("i1", "i2", "i3")), "inline entities run in queue order, got " + Probe.ORDER);
      Check.check(i1.sawPom == 4.0F && i2.sawPom == 4.0F && i3.sawPom == 16.0F,
            "each inline entity ran under its group's multiplier (pom() falls through to the live global on the game thread)");
      Check.check(!i1.onWorker && !i3.onWorker, "inline entities run on the game thread");
      Check.check(gt.perObjectMultiplier == 1.0F, "the global multiplier is 1.0 after the inline phase");

      // ── 3. runway: workers claim tasks while the game thread is busy (the defect's direct negation) ──
      UpdateBatch.latchFrame(true);
      gt.perObjectMultiplier = 1.0F;
      int n = 64;
      java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
      for (int i = 0; i < n; i++) {
         UpdateBatch.add(new Probe("r" + i, 200_000L) {
            @Override
            public void update() {
               super.update();
               started.countDown();
            }
         }, 0);
      }
      UpdateBatch.dispatchCombined();
      // Require actual progress before joining, not a scheduler timeslice within an arbitrary 3 ms.
      boolean ranBeforeJoin = started.await(10, java.util.concurrent.TimeUnit.SECONDS);
      long preClaimedBefore = UpdateBatch.getPreClaimed();
      UpdateBatch.joinPending();
      Check.check(ranBeforeJoin && FrameBatch.lastPreClaimed() > 0,
            "workers claimed tasks before the join (preClaimed=" + FrameBatch.lastPreClaimed() + ") — today's shape never achieves this");
      // and the flight owns that number itself: FrameBatch's is the last join's, whoever joined last (the shared pool
      // lands AnimBatch and friends later in the same frame), so the entity flight accumulates its own at its own join
      Check.check(UpdateBatch.getPreClaimed() - preClaimedBefore == FrameBatch.lastPreClaimed(),
            "the flight's cumulative counter took THIS join's preClaimed (delta="
                  + (UpdateBatch.getPreClaimed() - preClaimedBefore) + ", join=" + FrameBatch.lastPreClaimed() + ")");

      // ── 4. nested-batch guard: FrameBatch.run with a combined flight pending lands it FULLY first ──
      UpdateBatch.latchFrame(true);
      Probe nb = new Probe("nb", 0);
      UpdateBatch.add(nb, 0);
      UpdateBatch.dispatchCombined();
      long nestedBefore = UpdateBatch.getNestedJoins();
      int[] ran = {0};
      Throwable t = FrameBatch.run(4, i -> ran[0]++); // an inline entity's code path reaching a batch user
      Check.check(t == null && ran[0] == 4, "the nested batch itself ran clean");
      Check.check(UpdateBatch.getNestedJoins() == nestedBefore + 1, "the guard counted the nested landing");
      Check.check(!UpdateBatch.hasPendingBatch(), "the flight was landed by the guard (full joinPending, not a raw join)");
      Check.check(nb.sawPom == 1.0F, "the flown task still ran (landed through the guard)");

      // ── 5. a throwing task still latches off and the frame still lands ──
      UpdateBatch.resetFailedForTest();
      UpdateBatch.latchFrame(true);
      UpdateBatch.add(new Probe("ok", 0) {
      }, 0);
      UpdateBatch.add(new Probe("boom", 0) {
         @Override
         public void update() {
            throw new IllegalStateException("boom");
         }
      }, 0);
      UpdateBatch.dispatchCombined();
      UpdateBatch.joinPending();
      Check.check(UpdateBatch.hasFailed(), "a worker throw latches batching off");
      Check.check(!UpdateBatch.hasPendingBatch(), "the failed flight still landed");
      UpdateBatch.resetFailedForTest();

      // ── 6. the latch itself: what it stamps and the two queues it resets. Reachable only since the enabled() gate
      //      moved to latchFrameFromConfig(): with it inside latchFrame, this JVM latched false whatever the caller
      //      asked (enabled() short-circuits on entityUpdateParallel, which defaults off), so combinedFrame() and
      //      both resets ran in no test at all. That is why these four lines are new, not the flag ──
      Probe.ORDER.clear();
      gt.perObjectMultiplier = 1.0F;
      UpdateBatch.latchFrame(true);
      Check.check(UpdateBatch.combinedFrame(), "latchFrame(true) latches the combined frame");
      UpdateBatch.latchFrame(false);
      Check.check(!UpdateBatch.combinedFrame(), "latchFrame(false) latches it off");

      // a batchable queued into a frame that then never dispatched: the next latch must drop it, or it flies twice
      UpdateBatch.latchFrame(true);
      UpdateBatch.add(new Probe("stale-batch", 0), 0);
      Check.check(UpdateBatch.pending() == 1, "the abandoned frame left one entity queued");
      UpdateBatch.latchFrame(true); // the next frame begins; the previous one never reached dispatchCombined
      Check.check(UpdateBatch.pending() == 0, "latchFrame(true) cleared the abandoned queue");
      Probe fresh = new Probe("fresh", 0);
      UpdateBatch.add(fresh, 2);
      UpdateBatch.dispatchCombined();
      UpdateBatch.joinPending();
      Check.check(fresh.sawPom == 1.0F && fresh.sawLevel == 2, "the fresh entity flew under its own pom and level");
      Check.check(Probe.ORDER.equals(java.util.List.of("fresh")),
            "the stale entity did not run in the next flight, got " + Probe.ORDER);

      // the inline queue resets the same way, and its abandoned slots are nulled rather than left pinning entities
      Probe.ORDER.clear();
      UpdateBatch.latchFrame(true);
      UpdateBatch.queueInline(new Probe("stale-inline", 0), 0);
      UpdateBatch.queueInline(new Probe("stale-inline2", 0), 0);
      UpdateBatch.latchFrame(true); // again: the frame that queued them never ran its inline phase
      UpdateBatch.runInlinePhase();
      Check.check(Probe.ORDER.isEmpty(), "latchFrame(true) reset the abandoned inline queue, got " + Probe.ORDER);

      System.out.println("CombinedDispatchTest ok");
   }
}
