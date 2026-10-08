package pzopt;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import zombie.GameTime;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoMovingObject;

/**
 * The animals of a combined frame update after the join ({@code animalsAfterJoin}, 2026-09-28), not in the inline phase
 * under the flight. With a herd near a horde the inline phase cost more than the overlap gained: every zombie getter of an
 * animal's sight walk went through the position snapshot while the workers wrote the same objects (animal LOS +1.3 ms flip,
 * +1.6 ms desktop, +4.9 ms Mac with 60 cows; docs/findings-gt-offload-2026-09-27.md, "PR #35 update").
 *
 * <p>Runtime, on a real {@code IsoAnimal}: {@code queueInline} routes by {@code instanceof IsoAnimal}, so a stand-in class
 * would test nothing. Its constructors need a loaded world, so the probe is allocated without running any of them
 * ({@code Unsafe.allocateInstance}, reached reflectively); its field initializers do not run either, which is why every
 * recording field starts at its zero value and is set after allocation. The zombie stand-ins are the plain
 * {@code IsoMovingObject} probes of CombinedDispatchTest ({@code super(false)}: the no-arg constructor NPEs here).
 */
public class AnimalsAfterJoinTest {

   static final List<String> ORDER = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
   static final AtomicInteger BATCHED_DONE = new AtomicInteger();

   /** A real IsoAnimal whose four update calls only record what they saw. */
   static final class Cow extends IsoAnimal {
      Cow() {
         super((zombie.iso.IsoCell) null); // never runs: newCow allocates the object without its constructors
      }

      // zero-initialised under allocateInstance; newCow sets the name
      String name;
      StringBuilder calls;
      int updates;
      int sawLevel;
      float sawGlobal;
      float sawPom;
      boolean sawPending;
      boolean sawFrozen;
      int sawBatchedDone;
      IsoMovingObject watch; // a batched entity whose snapshot state update() records
      boolean boom;

      private void call(char c) {
         if (this.calls == null) {
            this.calls = new StringBuilder();
         }
         this.calls.append(c);
      }

      @Override
      public void setCurrentSimulationLevel(UpdateSchedulerSimulationLevel level) {
         this.call('L');
         this.sawLevel = level.ordinal();
      }

      @Override
      public void preupdate() {
         this.call('P');
      }

      @Override
      public void frameStep() {
         this.call('F');
      }

      @Override
      public void update() {
         this.call('U');
         GameTime gt = GameTime.getInstance();
         this.sawGlobal = gt.perObjectMultiplier;
         this.sawPom = UpdateBatch.pom(gt);
         this.sawPending = UpdateBatch.hasPendingBatch();
         this.sawFrozen = this.watch != null && UpdateBatch.frozen(this.watch);
         this.sawBatchedDone = BATCHED_DONE.get();
         this.updates++;
         ORDER.add(this.name);
         if (this.boom) {
            throw new IllegalStateException("boom " + this.name);
         }
      }
   }

   /** A batchable zombie stand-in for the flight. */
   static final class Walker extends IsoMovingObject {
      final long spinNanos;

      Walker(long spinNanos) {
         super(false);
         this.spinNanos = spinNanos;
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
         long until = System.nanoTime() + this.spinNanos;
         while (System.nanoTime() < until) {
            Thread.onSpinWait();
         }
         BATCHED_DONE.incrementAndGet();
      }
   }

   /** A non-animal inline entity: the player's or a vehicle's place in the inline queue. */
   static final class Rider extends IsoMovingObject {
      final String name;
      int updates;
      boolean sawPending;

      Rider(String name) {
         super(false);
         this.name = name;
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
         this.updates++;
         this.sawPending = UpdateBatch.hasPendingBatch();
         ORDER.add(this.name);
      }
   }

   static Cow newCow(String name) throws ReflectiveOperationException {
      Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
      java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
      theUnsafe.setAccessible(true);
      Cow cow = (Cow) unsafeClass.getMethod("allocateInstance", Class.class).invoke(theUnsafe.get(null), Cow.class);
      cow.name = name;
      return cow;
   }

   /** How many slots of the animal queue still hold a reference (the queue's own array, read reflectively). */
   static int slotsHeld() throws ReflectiveOperationException {
      java.lang.reflect.Field f = UpdateBatch.class.getDeclaredField("animalQueue");
      f.setAccessible(true);
      int held = 0;
      for (Object o : (Object[]) f.get(null)) {
         if (o != null) {
            held++;
         }
      }
      return held;
   }

   public static void main(String[] args) throws Exception {
      Check.check(Config.ANIMALS_AFTER_JOIN, "animalsAfterJoin defaults on");
      GameTime gt = GameTime.getInstance();

      // ── 1. routing: animals leave the inline phase and run after the join, in queue order, each under its own stamps ──
      ORDER.clear();
      UpdateBatch.latchFrame(true);
      gt.perObjectMultiplier = 2.0F;
      Cow c1 = newCow("c1");
      Rider player = new Rider("player");
      Cow c2 = newCow("c2");
      UpdateBatch.queueInline(c1, 1);
      UpdateBatch.queueInline(player, 1);
      UpdateBatch.queueInline(c2, 1);
      gt.perObjectMultiplier = 8.0F;
      Cow c3 = newCow("c3");
      Rider vehicle = new Rider("vehicle");
      UpdateBatch.queueInline(c3, 4);
      UpdateBatch.queueInline(vehicle, 4);
      gt.perObjectMultiplier = 1.0F;
      long counted0 = UpdateBatch.getAnimalsAfterJoin();

      UpdateBatch.dispatchCombined(); // nothing batchable this frame: the phases must run all the same
      UpdateBatch.runInlinePhase();
      Check.check(c1.updates == 0 && c2.updates == 0 && c3.updates == 0, "no animal ran in the inline phase");
      Check.check(ORDER.equals(List.of("player", "vehicle")),
            "the inline phase still ran the non-animal entities, in queue order, got " + ORDER);
      UpdateBatch.joinPending();
      UpdateBatch.runAnimalPhase();
      Check.check(ORDER.equals(List.of("player", "vehicle", "c1", "c2", "c3")),
            "the animal phase ran every queued animal in queue order, got " + ORDER);
      Check.check("LPFU".equals(String.valueOf(c1.calls)) && "LPFU".equals(String.valueOf(c2.calls))
                  && "LPFU".equals(String.valueOf(c3.calls)),
            "each animal got the stock four calls, in order, once: " + c1.calls + " " + c2.calls + " " + c3.calls);
      Check.check(c1.sawGlobal == 2.0F && c2.sawGlobal == 2.0F && c3.sawGlobal == 8.0F,
            "each animal ran under its own bucket's multiplier, got " + c1.sawGlobal + " " + c2.sawGlobal + " " + c3.sawGlobal);
      Check.check(c1.sawPom == 2.0F && c3.sawPom == 8.0F, "pom() on the game thread resolves to the same value");
      Check.check(c1.sawLevel == 1 && c2.sawLevel == 1 && c3.sawLevel == 4, "each animal ran under its own simulation level");
      Check.check(gt.perObjectMultiplier == 1.0F, "the global multiplier is 1.0 after the animal phase");
      Check.check(UpdateBatch.getAnimalsAfterJoin() - counted0 == 3, "the status-line counter took the three animals");
      Check.check(UpdateBatch.describe().contains(" animalsAfterJoin="), "the batch status line carries the counter");
      Check.check(slotsHeld() == 0, "the phase nulled its slots");
      UpdateBatch.runAnimalPhase(); // the queue was drained: a second call runs nobody again
      Check.check(c1.updates == 1 && c2.updates == 1 && c3.updates == 1, "every animal updated exactly once");

      gt.perObjectMultiplier = 3.0F; // a frame with no animal queued (every non-combined frame) is untouched by the phase
      UpdateBatch.runAnimalPhase();
      Check.check(gt.perObjectMultiplier == 3.0F, "an empty animal phase does not even write the multiplier");
      gt.perObjectMultiplier = 1.0F;

      // ── 2. never under a flight: called with the flight still airborne, the phase lands it before the first animal ──
      ORDER.clear();
      BATCHED_DONE.set(0);
      UpdateBatch.latchFrame(true);
      int n = 48;
      Walker[] walkers = new Walker[n];
      for (int i = 0; i < n; i++) {
         walkers[i] = new Walker(200_000L); // ~0.2 ms each: the flight is still working when the phase is called
         UpdateBatch.add(walkers[i], 0);
      }
      Cow h1 = newCow("h1");
      h1.watch = walkers[0];
      Cow h2 = newCow("h2");
      h2.watch = walkers[n - 1];
      UpdateBatch.queueInline(h1, 0);
      UpdateBatch.queueInline(h2, 0);
      Check.check(UpdateBatch.dispatchCombined(), "the flight went up");
      Check.check(UpdateBatch.hasPendingBatch(), "the flight is airborne");
      UpdateBatch.runInlinePhase();
      Check.check(h1.updates == 0 && h2.updates == 0, "the inline phase under the flight did not run the herd");
      UpdateBatch.runAnimalPhase(); // no joinPending first: the phase itself must refuse to run an animal under a flight
      Check.check(h1.updates == 1 && h2.updates == 1, "both animals ran");
      Check.check(!h1.sawPending && !h2.sawPending, "no animal updated while an entity flight was pending");
      Check.check(h1.sawBatchedDone == n && h2.sawBatchedDone == n,
            "every batched task had finished before the first animal updated, saw " + h1.sawBatchedDone + " of " + n);
      Check.check(!h1.sawFrozen && !h2.sawFrozen, "an animal reads a batched entity's position directly, not through the snapshot");
      Check.check(!UpdateBatch.hasPendingBatch(), "the flight is down");
      Check.check(!UpdateBatch.hasFailed(), "nothing failed");

      // ── 3. a throwing animal: its exception propagates unchanged, the multiplier and the queue are left clean ──
      ORDER.clear();
      UpdateBatch.latchFrame(true);
      gt.perObjectMultiplier = 4.0F;
      Cow t1 = newCow("t1");
      Cow t2 = newCow("t2");
      t2.boom = true;
      Cow t3 = newCow("t3");
      UpdateBatch.queueInline(t1, 2);
      UpdateBatch.queueInline(t2, 2);
      UpdateBatch.queueInline(t3, 2);
      gt.perObjectMultiplier = 1.0F;
      UpdateBatch.dispatchCombined();
      UpdateBatch.runInlinePhase();
      UpdateBatch.joinPending();
      Throwable thrown = null;
      try {
         UpdateBatch.runAnimalPhase();
      } catch (IllegalStateException e) {
         thrown = e;
      }
      Check.check(thrown != null && "boom t2".equals(thrown.getMessage()), "the animal's own exception propagated unchanged, got " + thrown);
      Check.check(t2.sawGlobal == 4.0F, "the thrower ran under its bucket's multiplier");
      Check.check(gt.perObjectMultiplier == 1.0F, "a throwing animal does not leave the global multiplier at its bucket's value");
      Check.check(t1.updates == 1 && t3.updates == 0,
            "the animals before the throw ran and the one after it did not (the stock bucket loop aborts the same way)");
      Check.check(slotsHeld() == 0, "a throwing phase leaves no reference in the queue");
      UpdateBatch.runAnimalPhase();
      Check.check(t3.updates == 0, "the dropped animal does not run on a later call");

      // ── 4. the latch drops a frame's abandoned animals, on either arm ──
      ORDER.clear();
      UpdateBatch.latchFrame(true);
      UpdateBatch.queueInline(newCow("stale1"), 0);
      UpdateBatch.queueInline(newCow("stale2"), 0);
      Check.check(slotsHeld() == 2, "the abandoned frame left two animals queued");
      UpdateBatch.latchFrame(true); // the next frame begins; the previous one never reached its animal phase
      Check.check(slotsHeld() == 0, "latchFrame(true) nulled the abandoned animal slots");
      UpdateBatch.runAnimalPhase();
      Check.check(ORDER.isEmpty(), "the stale animals did not run in the next frame, got " + ORDER);
      UpdateBatch.queueInline(newCow("stale3"), 0);
      UpdateBatch.latchFrame(false); // a combined frame may never come again (the failure latch, a multiplayer session)
      Check.check(slotsHeld() == 0, "latchFrame(false) drops them too");
      UpdateBatch.latchFrame(true);
      UpdateBatch.runAnimalPhase();
      Check.check(ORDER.isEmpty(), "nothing stale ran, got " + ORDER);

      // ── 5. key off, the A/B control arm: the animal stays in the inline phase, under the flight, as before ──
      UpdateBatch.setAnimalsAfterJoinForTest(false);
      try {
         ORDER.clear();
         UpdateBatch.latchFrame(true);
         UpdateBatch.add(new Walker(0L), 0);
         gt.perObjectMultiplier = 2.0F;
         Cow o1 = newCow("o1");
         Rider rider = new Rider("rider");
         UpdateBatch.queueInline(o1, 1);
         UpdateBatch.queueInline(rider, 1);
         gt.perObjectMultiplier = 1.0F;
         long counted1 = UpdateBatch.getAnimalsAfterJoin();
         UpdateBatch.dispatchCombined();
         UpdateBatch.runInlinePhase();
         Check.check(ORDER.equals(List.of("o1", "rider")), "with the key off the animal ran in the inline phase in queue order, got " + ORDER);
         Check.check(o1.sawPending && rider.sawPending, "... while the flight was airborne, today's shape");
         Check.check(o1.sawGlobal == 2.0F && o1.sawLevel == 1, "... under its own stamps");
         UpdateBatch.joinPending();
         UpdateBatch.runAnimalPhase();
         Check.check(o1.updates == 1, "and not again after the join");
         Check.check(UpdateBatch.getAnimalsAfterJoin() == counted1, "the counter only counts the animal phase");
      } finally {
         UpdateBatch.setAnimalsAfterJoinForTest(Config.ANIMALS_AFTER_JOIN);
      }
      Check.check(gt.perObjectMultiplier == 1.0F, "the global multiplier is 1.0 at the end");

      System.out.println("AnimalsAfterJoinTest ok");
   }
}
