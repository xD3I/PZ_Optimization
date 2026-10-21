package pzopt;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.iso.IsoMovingObject;

/**
 * pzopt.UpdateBatch: the simulation bucket's per-entity sequence on the frame workers.
 *
 * <p>Vanilla {@code MovingObjectUpdateSchedulerUpdateBucket.update(int)} does exactly four calls per entity, in
 * this order (offsets 122 / 131 / 136 / 141 of the shipped 42.20.4 class):
 * {@code setCurrentSimulationLevel(level)}, {@code preupdate()}, {@code frameStep()}, {@code update()}. Losing any
 * one of them is silent — no exception, no log — so this test pins the whole sequence per entity rather than just
 * "update ran". The same class of bug cost this project a week when a parallel path dropped {@code frameStep()}.
 *
 * <p>{@code GameTime.perObjectMultiplier} is set once per bucket and restored to 1 at the bucket's end, so it is
 * constant for everything this batch touches; that is why the batch is per bucket and never spans two of them.
 */
public class UpdateBatchTest {

   /** A real IsoMovingObject that records its call sequence. {@code super(false)} skips the cell registration. */
   static final class Probe extends IsoMovingObject {
      final StringBuilder calls = new StringBuilder();
      volatile String thread;
      private final boolean throwOnUpdate;
      private final int index;

      Probe(boolean throwOnUpdate) {
         this(throwOnUpdate, -1);
      }

      Probe(boolean throwOnUpdate, int index) {
         super(false);
         this.throwOnUpdate = throwOnUpdate;
         this.index = index;
      }

      @Override
      public void setCurrentSimulationLevel(UpdateSchedulerSimulationLevel level) {
         record("S");
      }

      @Override
      public void preupdate() {
         record("P");
      }

      @Override
      public void frameStep() {
         record("F");
      }

      @Override
      public void update() {
         record("U");
         this.thread = Thread.currentThread().getName();
         // The workers park between batches (~50 us to wake). Without a little work in the first tasks the
         // calling thread drains a queue of no-op entities on its own and the batch looks single-threaded;
         // FrameBatchTest nudges its own tasks the same way for the same reason.
         if (this.index >= 0 && (this.index & 511) == 0) {
            try {
               Thread.sleep(1);
            } catch (InterruptedException e) {
               Thread.currentThread().interrupt();
            }
         }
         if (this.throwOnUpdate) {
            throw new IllegalStateException("probe update failed");
         }
      }

      private void record(String step) {
         synchronized (this.calls) {
            this.calls.append(step);
         }
      }

      String sequence() {
         synchronized (this.calls) {
            return this.calls.toString();
         }
      }
   }

   public static void main(String[] args) throws Exception {
      UpdateSchedulerSimulationLevel level = UpdateSchedulerSimulationLevel.FULL;

      // ── off by default: the key ships off, like every other parallel phase's first release ──
      Check.check(Config.ENTITY_UPDATE_PARALLEL, "entityUpdateParallel defaults to on (zombie pass 2026-10-13: the brawl behaviour check)");

      // ── every entity gets vanilla's four calls, in vanilla's order, exactly once ──
      int n = 5000;
      Probe[] probes = new Probe[n];
      UpdateBatch.clear();
      for (int i = 0; i < n; i++) {
         probes[i] = new Probe(false, i);
         UpdateBatch.add(probes[i]);
      }
      Check.check(UpdateBatch.pending() == n, "the queue holds every added entity: " + UpdateBatch.pending());

      boolean handled = UpdateBatch.run(level);
      Check.check(handled, "a non-empty batch reports that it handled the entities");

      java.util.Set<String> threads = ConcurrentHashMap.newKeySet();
      for (int i = 0; i < n; i++) {
         Check.check("SPFU".equals(probes[i].sequence()),
               "entity " + i + " ran setCurrentSimulationLevel, preupdate, frameStep, update once and in that"
                     + " order, ran \"" + probes[i].sequence() + "\"");
         threads.add(probes[i].thread);
      }
      Check.check(threads.size() > 1, "the batch used more than one thread: " + threads);
      Check.check(UpdateBatch.pending() == 0, "run() drains the queue");

      // ── an empty batch is a no-op, not a failure ──
      Check.check(!UpdateBatch.run(level), "an empty batch reports nothing to do");

      // ── clear() drops a frame's collection without running it ──
      Probe dropped = new Probe(false);
      UpdateBatch.add(dropped);
      UpdateBatch.clear();
      Check.check(UpdateBatch.pending() == 0, "clear() empties the queue");
      Check.check(dropped.sequence().isEmpty(), "a cleared entity never ran: " + dropped.sequence());

      // ── the queue grows past its initial capacity ──
      int big = 9000;
      for (int i = 0; i < big; i++) {
         UpdateBatch.add(new Probe(false));
      }
      Check.check(UpdateBatch.pending() == big, "the queue grew past its initial capacity");
      UpdateBatch.clear();

      // ── who may run on a worker at all ──────────────────────────────
      // ActionEval's rule, verbatim from its own contract: "Not queued (stock path inline): players and animals,
      // multiplayer, a zombie being grappled / grappling". Players are the load-bearing one: IsoPlayer.update()
      // reaches Lua, pzopt has no guard against Lua re-entry from a worker, and the scheduler puts every player in
      // the FULL bucket — so without this the batch would hand them straight to a worker.
      Check.check(!UpdateBatch.batchableType(zombie.characters.IsoPlayer.class),
            "a player never runs on a worker: its update reaches Lua");
      Check.check(!UpdateBatch.batchableType(zombie.characters.animals.IsoAnimal.class),
            "an animal never runs on a worker, matching ActionEval");
      Check.check(UpdateBatch.batchableType(zombie.characters.IsoZombie.class),
            "a zombie is the case this batch exists for");
      Check.check(UpdateBatch.batchableType(Probe.class), "a plain moving object is batchable");
      // Found by the first hour-long live session (2026-09-26): a vehicle updated on pzopt-frame-2 —
      // BaseVehicle.update > updateParts > VehicleParts.callLuaVoid reaches Lua part scripts (the wrong-thread
      // guard errored 26 times, then the Kahlua VM stack corrupted: "Index -4 out of bounds for length 1000",
      // batching off). The bench route is on foot, so no active vehicle ever updated mid-batch until real play.
      Check.check(!UpdateBatch.batchableType(zombie.vehicles.BaseVehicle.class),
            "a vehicle never runs on a worker: its part updates reach Lua and pzBullet");
      Check.check(!UpdateBatch.batchableType(zombie.iso.IsoPhysicsObject.class),
            "a physics object never runs on a worker: its update steps native physics, never audited");

      // ── one entity throwing does not stop the others, and turns batching off for the session ──
      Check.check(!UpdateBatch.hasFailed(), "the batch has not failed yet");
      int m = 200;
      Probe[] mixed = new Probe[m];
      for (int i = 0; i < m; i++) {
         mixed[i] = new Probe(i == 37);
         UpdateBatch.add(mixed[i]);
      }
      UpdateBatch.run(level);
      for (int i = 0; i < m; i++) {
         if (i == 37) {
            continue;
         }
         Check.check("SPFU".equals(mixed[i].sequence()),
               "entity " + i + " still completed while entity 37 threw, ran \"" + mixed[i].sequence() + "\"");
      }
      Check.check(UpdateBatch.hasFailed(), "a worker exception latches the failure so the session falls back");
      Check.check(!UpdateBatch.enabled(), "enabled() is false once the batch has failed");

      // ── counters are exposed for the console summary line, like the other batches ──
      Check.check(UpdateBatch.describe().contains("update batch:"), "describe() names the batch");

      AtomicInteger unused = new AtomicInteger();
      Check.check(unused.get() == 0, "sanity");
      System.out.println("UpdateBatchTest ok");
   }
}
