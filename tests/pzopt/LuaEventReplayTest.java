package pzopt;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import zombie.Lua.LuaEventManager;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.iso.IsoMovingObject;

/**
 * Worker-fired Lua events are captured and replayed, not dropped ({@code entityUpdateLuaReplay}, default on).
 *
 * <p>The first wired run summary showed the cost of the drop guard: {@code luaSuppressed=4,383,274} in a 26 s
 * Louisville route — the per-zombie update event, deleted for every batched zombie every frame. In stock those
 * handlers RUN, so part of the measured speedup was skipped work, and any mod hooking per-zombie events is
 * silently dead while batching is on. This is xD3I's AnimParallel pattern applied to the update batch: a worker
 * mid-batch appends {@code (event, args…)} to its task's capture list; after the join the game thread walks the
 * tasks in queue order — stock's serial event order — and fans each record back through the real
 * {@code triggerEvent} overloads, still inside the bucket window so handlers see the bucket's
 * {@code perObjectMultiplier} exactly as stock's inline dispatch did.
 *
 * <p>Dispatch into a live Kahlua state is not observable in a bare JVM ({@code LuaManager.env} stays null and
 * every overload returns before touching the argument slots), so the runtime half of this test pins the counter
 * flow — captured on the worker, zero mid-flight replays, replayed after the join, suppressed stays untouched —
 * and the bytecode half pins the fan-out: {@code UpdateBatch}'s replay must invoke real
 * {@code LuaEventManager.triggerEvent} overloads, and every overload's worker guard must route to the capture
 * funnel instead of the drop counter.
 */
public class LuaEventReplayTest {

   static final class Probe extends IsoMovingObject {
      Runnable onUpdate;
      volatile long replayedMidFlight = -1L;

      Probe() {
         super(false);
      }

      @Override
      public void setCurrentSimulationLevel(UpdateSchedulerSimulationLevel level) {
      }

      @Override
      public void preupdate() {
      }

      @Override
      public void frameStep() {
      }

      @Override
      public void update() {
         if (this.onUpdate != null) {
            this.onUpdate.run();
         }
      }
   }

   public static void main(String[] args) throws Exception {
      zombie.core.random.RandStandard.INSTANCE.init();
      UpdateSchedulerSimulationLevel level = UpdateSchedulerSimulationLevel.FULL;

      Check.check(Config.ENTITY_UPDATE_LUA_REPLAY, "entityUpdateLuaReplay defaults to on: dropping mod events is not a default");

      // ── worker events are captured, not dropped, and replayed once the batch has joined ──
      long suppressed0 = UpdateBatch.getLuaSuppressedCount();
      long captured0 = UpdateBatch.getLuaCapturedCount();
      long replayed0 = UpdateBatch.getLuaReplayedCount();

      int n = 64;
      Probe[] probes = new Probe[n];
      UpdateBatch.clear();
      for (int i = 0; i < n; i++) {
         Probe p = new Probe();
         final int index = i;
         p.onUpdate = () -> {
            // The workers park between batches (~50 us to wake); without a little work in the early tasks the
            // calling thread drains the whole queue alone and no probe ever runs on a worker — the same nudge
            // UpdateBatchTest and FrameBatchTest use.
            if ((index & 7) == 0) {
               try {
                  Thread.sleep(1);
               } catch (InterruptedException ie) {
                  Thread.currentThread().interrupt();
               }
            }
            if (UpdateBatch.onWorkerNow()) {
               // The real overloads, straight through the override's guard. Only the 0- and 1-argument shapes:
               // they are the two whose vanilla bodies null-check LuaManager.env, so their replay dispatch is a
               // no-op in a bare JVM instead of an NPE (same constraint LuaEventGuardTest documents). The other
               // seven arities are covered by the bytecode pin on replayLuaEvents below.
               LuaEventManager.triggerEvent("PzoptReplayProbe");
               LuaEventManager.triggerEvent("PzoptReplayProbe", p);
               // Mid-flight, nothing may have replayed yet: the fan-out is the game thread's after the join.
               p.replayedMidFlight = UpdateBatch.getLuaReplayedCount() - replayed0;
            }
         };
         probes[i] = p;
         UpdateBatch.add(p);
      }
      Check.check(UpdateBatch.run(level), "the batch ran");

      long onWorker = 0;
      for (Probe p : probes) {
         if (p.replayedMidFlight >= 0) {
            onWorker++;
            Check.check(p.replayedMidFlight == 0, "no replay happened mid-flight: " + p.replayedMidFlight);
         }
      }
      Check.check(onWorker > 0, "at least one probe ran on a worker: " + onWorker);

      long captured = UpdateBatch.getLuaCapturedCount() - captured0;
      long replayed = UpdateBatch.getLuaReplayedCount() - replayed0;
      Check.check(captured == onWorker * 2, "every worker-fired event was captured (2 per worker probe), captured="
            + captured + " workerProbes=" + onWorker);
      Check.check(replayed == captured, "every captured event was replayed after the join: replayed=" + replayed
            + " captured=" + captured);
      Check.check(UpdateBatch.getLuaSuppressedCount() == suppressed0,
            "nothing was dropped while replay is on: suppressed moved by "
                  + (UpdateBatch.getLuaSuppressedCount() - suppressed0));

      // ── a later batch with no events replays nothing ──
      Probe quiet = new Probe();
      UpdateBatch.clear();
      UpdateBatch.add(quiet);
      UpdateBatch.run(level);
      Check.check(UpdateBatch.getLuaReplayedCount() - replayed0 == replayed, "an event-free batch replays nothing");

      // ── bytecode pin 1: the replay fans out through real LuaEventManager.triggerEvent overloads ──
      ClassModel updateBatch = ClassFile.of().parse(Files.readAllBytes(Path.of("build/classes/pzopt/UpdateBatch.class")));
      Set<String> replayCalls = new HashSet<>();
      for (MethodModel m : updateBatch.methods()) {
         if (!m.methodName().stringValue().contains("replayLuaEvents") || m.code().isEmpty()) {
            continue;
         }
         m.code().get().forEach(el -> {
            if (el instanceof InvokeInstruction inv
                  && inv.owner().asInternalName().equals("zombie/Lua/LuaEventManager")
                  && inv.name().stringValue().equals("triggerEvent")) {
               replayCalls.add(inv.typeSymbol().descriptorString());
            }
         });
      }
      Check.check(replayCalls.size() == 9, "replayLuaEvents dispatches through all nine triggerEvent overloads, found "
            + replayCalls.size() + ": " + replayCalls);

      // ── bytecode pin 2: every triggerEvent overload's guard routes to the capture funnel ──
      ClassModel lem = ClassFile.of().parse(Files.readAllBytes(Files.isRegularFile(Path.of("build/classes/zombie/Lua/LuaEventManager.class"))
            ? Path.of("build/classes/zombie/Lua/LuaEventManager.class") // the loose layout of an unsigned game jar
            : Path.of("build/classes/pzopt/overrides/zombie/Lua/LuaEventManager.class")));
      int overloads = 0;
      int captureRouted = 0;
      for (MethodModel m : lem.methods()) {
         if (!m.methodName().stringValue().equals("triggerEvent") || m.code().isEmpty()) {
            continue;
         }
         overloads++;
         boolean[] routed = {false};
         m.code().get().forEach(el -> {
            if (el instanceof InvokeInstruction inv
                  && inv.owner().asInternalName().equals("pzopt/UpdateBatch")
                  && inv.name().stringValue().equals("captureLuaEvent")) {
               routed[0] = true;
            }
         });
         if (routed[0]) {
            captureRouted++;
         }
      }
      Check.check(overloads == 9, "the override ships nine triggerEvent overloads: " + overloads);
      Check.check(captureRouted == 9, "every overload's worker guard routes to captureLuaEvent, routed=" + captureRouted);

      System.out.println("LuaEventReplayTest ok");
   }
}
