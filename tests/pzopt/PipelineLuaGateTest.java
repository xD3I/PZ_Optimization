package pzopt;

import java.util.concurrent.atomic.AtomicBoolean;
import zombie.iso.IsoMovingObject;

/**
 * luaWorkerGate on the combined flight (entityUpdatePipeline). A live session (2026-10-01, Windows, 325 mods) had a
 * zombie trample a crop on a pipeline worker: GlobalObject.destroyThisObject called Lua from pzopt-frame-3 while the
 * game thread ran the player's Lua in the inline phase, the Kahlua stack underflowed four times and batching switched
 * itself off. The gate's test drives plain FrameBatch tasks; this one drives dispatchCombined, the path that session
 * took, and pins what makes it safe there:
 *
 * <ul>
 *   <li>a void Lua call on a worker's task is deferred: not run on the worker, not run during the inline phase;
 *   <li>the join replays every one on the game thread, once, in queue order, each under its own entity's multiplier;
 *       a task the game thread takes over in the join runs its call where it stands, as with the key off;
 *   <li>a Lua call with a result waits on the worker until the game thread serves it in the join, never while the game
 *       thread is inside the inline phase.
 * </ul>
 */
public class PipelineLuaGateTest {
   static final java.util.List<int[]> RAN = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
   static final java.util.List<Thread> RAN_ON = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
   static final java.util.List<Float> RAN_POM = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
   static volatile Thread game;
   static volatile Trampler[] tasks;

   /** A batched entity whose update reaches Lua the way a trampled crop does (a void call), some also a valued call. */
   static class Trampler extends IsoMovingObject {
      final int index;
      final boolean valued;
      volatile boolean onWorker, deferred;
      volatile Object valuedResult;

      Trampler(int index, boolean valued) {
         super(false); // the no-arg IsoMovingObject registers into the cell and NPEs in a plain JVM
         this.index = index;
         this.valued = valued;
      }

      @Override
      public void setCurrentSimulationLevel(zombie.UpdateSchedulerSimulationLevel l) {
      }

      @Override
      public void preupdate() {
      }

      @Override
      public void frameStep() {
      }

      @Override
      public void update() {
         this.onWorker = Thread.currentThread() instanceof FrameBatch.Worker;
         AtomicBoolean ran = new AtomicBoolean();
         final int idx = this.index;
         Runnable lua = () -> {
            ran.set(true);
            RAN.add(new int[] {idx});
            RAN_ON.add(Thread.currentThread());
            RAN_POM.add(UpdateBatch.pom(zombie.GameTime.getInstance()));
         };
         // what the LuaCaller override does: a worker hands the call to the gate, the game thread (helping with a task
         // in the join) runs it where it stands, as with the key off
         if (LuaGate.onWorker()) {
            LuaGate.callVoid(null, lua);
         } else {
            lua.run();
         }
         this.deferred = !ran.get();
         if (this.valued) {
            this.valuedResult = LuaGate.onWorker() ? LuaGate.call(null, () -> Thread.currentThread()) : Thread.currentThread();
         }
         long until = System.nanoTime() + 50_000L;
         while (System.nanoTime() < until) {
            Thread.onSpinWait();
         }
      }

      @Override
      public void postupdate() {
      }
   }

   /** An inline entity (the player's slot): runs on the game thread under the flight and watches for gate work. */
   static class Inline extends IsoMovingObject {
      volatile int ranBefore = -1, ranAfter = -1, answeredAfter = -1;

      Inline() {
         super(false);
      }

      @Override
      public void setCurrentSimulationLevel(zombie.UpdateSchedulerSimulationLevel l) {
      }

      @Override
      public void preupdate() {
      }

      @Override
      public void frameStep() {
      }

      @Override
      public void update() {
         this.ranBefore = RAN.size();
         long until = System.nanoTime() + 20_000_000L; // 20 ms "in Lua": the workers reach their calls meanwhile
         while (System.nanoTime() < until) {
            Thread.onSpinWait();
         }
         this.ranAfter = RAN.size();
         int answered = 0;
         for (Trampler x : tasks) {
            if (x.valued && x.valuedResult != null) {
               answered++; // a worker's valued call is only answered by the game thread serving it
            }
         }
         this.answeredAfter = answered;
      }

      @Override
      public void postupdate() {
      }
   }

   public static void main(String[] args) throws Exception {
      game = Thread.currentThread();
      zombie.GameTime gt = zombie.GameTime.getInstance();
      final int n = 64;
      Trampler[] t = new Trampler[n];
      tasks = t;
      long handled0 = LuaGate.handled(), timeouts0 = LuaGate.timeouts();

      UpdateBatch.resetFailedForTest();
      UpdateBatch.latchFrame(true); // test seam: a combined frame
      for (int i = 0; i < n; i++) {
         gt.perObjectMultiplier = i < n / 2 ? 2.0F : 8.0F; // two "buckets", stamped per entity at add
         t[i] = new Trampler(i, i % 8 == 0);
         UpdateBatch.add(t[i], i < n / 2 ? 0 : 3);
      }
      gt.perObjectMultiplier = 1.0F;
      Inline inline = new Inline();
      UpdateBatch.queueInline(inline, 4);

      UpdateBatch.dispatchCombined();
      UpdateBatch.runInlinePhase();
      Check.check(RAN.isEmpty(), "no captured Lua call ran before the join, got " + RAN.size());
      UpdateBatch.joinPending();

      int onWorkers = 0, valuedOnWorkers = 0;
      for (int i = 0; i < n; i++) {
         // a worker's call waits for the join; a task the game thread took in the join ran its call where it stood
         Check.check(t[i].deferred == t[i].onWorker, "task " + i + ": deferred=" + t[i].deferred + " onWorker=" + t[i].onWorker);
         if (t[i].onWorker) {
            onWorkers++;
            if (t[i].valued) {
               valuedOnWorkers++;
            }
         }
      }
      Check.check(onWorkers > 0, "workers took part");

      Check.check(inline.ranBefore == 0 && inline.ranAfter == 0,
            "no worker's Lua call ran during the inline phase: " + inline.ranBefore + " -> " + inline.ranAfter);
      Check.check(inline.answeredAfter == 0,
            "the game thread answered no worker's valued Lua call while it was in the inline phase: " + inline.answeredAfter);

      Check.check(RAN.size() == n, "every call ran exactly once: " + RAN.size() + " of " + n);
      boolean[] seen = new boolean[n];
      int lastReplayed = -1;
      for (int k = 0; k < n; k++) {
         int idx = RAN.get(k)[0];
         Check.check(!seen[idx], "task " + idx + "'s call ran twice");
         seen[idx] = true;
         Check.check(RAN_ON.get(k) == game, "task " + idx + "'s call ran on the game thread, not " + RAN_ON.get(k).getName());
         float want = idx < n / 2 ? 2.0F : 8.0F;
         Check.check(RAN_POM.get(k) == want, "task " + idx + "'s call saw its entity's multiplier " + want + ", got " + RAN_POM.get(k));
         if (t[idx].onWorker) {
            Check.check(idx > lastReplayed, "replayed in queue order: task " + idx + " after task " + lastReplayed);
            lastReplayed = idx;
         }
      }

      for (int i = 0; i < n; i += 8) {
         Check.check(t[i].valuedResult == game, "task " + i + "'s valued Lua call ran on the game thread: " + t[i].valuedResult);
      }

      Check.check(LuaGate.timeouts() == timeouts0, "no gate timeout: " + LuaGate.describe());
      Check.check(LuaGate.handled() - handled0 == onWorkers + valuedOnWorkers,
            "gate counted " + onWorkers + " replayed + " + valuedOnWorkers + " served: " + LuaGate.describe());
      Check.check(UpdateBatch.pom(gt) == 1.0F, "the game thread's pom() follows the live field after the join");
      Check.check(!UpdateBatch.hasPendingBatch(), "the flight landed");
      System.out.println("PipelineLuaGateTest ok " + LuaGate.describe());
   }
}
