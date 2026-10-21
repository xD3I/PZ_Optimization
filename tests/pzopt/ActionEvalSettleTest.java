package pzopt;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import zombie.characters.action.ActionContext;
import zombie.characters.action.ActionGroup;
import zombie.characters.action.ActionState;
import zombie.characters.action.ActionStateSnapshot;
import zombie.characters.action.ActionTransition;
import zombie.characters.action.IActionCondition;
import zombie.core.skinnedmodel.advancedanimation.IAnimatable;

/**
 * The postupdate stream's task failures (f8-streamfix, 2026-10-13): "ActionStateContainer.currentState null during an
 * evaluation". A zombie's transition evaluation runs on a frame worker into its context's next container while the game thread
 * goes on; a game-thread ActionContext.setCurrentState (setGroup: IsoZombie.resetForReuse, VirtualZombieManager's reuse) or
 * setPlaybackStateSnapshot clears that container under the worker. ActionEval.settle makes every game-thread write into the
 * context wait for the context's own evaluation task first.
 *
 * <ol>
 * <li>Deterministic reproduction: the worker is parked inside a transition condition (the transitions loop re-reads
 *     currentState after it), the game thread writes the context. Unguarded (the pre-fix behaviour, ActionEval.settleOff): the
 *     worker throws the NPE of the runs. Guarded: the write waits for the evaluation; no failure, the write lands after it.</li>
 * <li>Stress: a stream of evaluations of 64 contexts published while the game thread writes random contexts with every
 *     guarded writer, 400 frames. Guarded: no task failure, every evaluation done once. The unguarded count is printed.</li>
 * <li>Stream tokens: a user whose stream another user joined and replaced with its own stream can no longer publish into
 *     that stream (the untokened publish grew the other stream past its task list: a null task item).</li>
 * </ol>
 */
public final class ActionEvalSettleTest {
   public static void main(String[] args) throws Throwable {
      long t0 = System.nanoTime();
      // ActionContext's transfer and ActionEval read GameClient.client / GameServer.server, whose initializers draw from Rand:
      // seeded long before in the game, not in a bare JVM (as UpdateBucketTest)
      zombie.core.random.RandStandard.INSTANCE.init();
      ActionEval.begin();
      Check.check(ActionEval.generation() > 0, "actionEvalParallel is on in the test JVM (ActionEval.begin moved the generation)");

      // 1. deterministic
      Throwable unguarded = raceShape(false);
      Check.check(unguarded instanceof NullPointerException && String.valueOf(unguarded.getMessage()).contains("currentState"),
            "unguarded: the worker's evaluation throws the runs' NPE (currentState null), got " + unguarded);
      Throwable guarded = raceShape(true);
      Check.check(guarded == null, "guarded: no failure, got " + guarded);
      notStarted();

      // 2. stress
      int unguardedFailures = stress(false, 150);
      int guardedFailures = stress(true, 400);
      Check.check(guardedFailures == 0, "guarded stress: no task failure, got " + guardedFailures);

      // 3. tokens
      tokens();

      System.out.println("ActionEvalSettleTest ok (unguarded stress failures " + unguardedFailures + " in 150 frames, guarded 0 in 400; settle runs "
            + ActionEval.settleRuns + " waits " + ActionEval.settleWaits + ", " + (System.nanoTime() - t0) / 1_000_000L + " ms)");
   }

   // ---------------------------------------------------------------------------------------------------------------------

   private static final AtomicInteger owners = new AtomicInteger();

   private static IAnimatable owner() {
      String name = "owner#" + owners.incrementAndGet();
      return (IAnimatable) Proxy.newProxyInstance(IAnimatable.class.getClassLoader(), new Class<?>[]{IAnimatable.class}, (p, m, a) -> {
         if (m.getName().equals("toString")) {
            return name;
         }
         if (m.getName().equals("hashCode")) {
            return System.identityHashCode(p);
         }
         if (m.getName().equals("equals")) {
            return p == a[0];
         }
         Class<?> r = m.getReturnType();
         if (r == boolean.class) {
            return false;
         }
         if (r == int.class || r == short.class || r == byte.class || r == char.class) {
            return r == int.class ? 0 : r == short.class ? (short)0 : r == byte.class ? (byte)0 : (char)0;
         }
         if (r == long.class) {
            return 0L;
         }
         if (r == float.class) {
            return 0.0F;
         }
         if (r == double.class) {
            return 0.0;
         }
         return null;
      });
   }

   private static IActionCondition condition(java.util.function.BooleanSupplier body) {
      return new IActionCondition() {
         @Override
         public String getDescription() {
            return "test";
         }

         @Override
         public boolean passes(ActionContext context, ActionState state) {
            return body.getAsBoolean();
         }

         @Override
         public IActionCondition clone() {
            return this;
         }

         @Override
         public String toString(String indent) {
            return indent + "test";
         }
      };
   }

   @SuppressWarnings("unchecked")
   private static ActionTransition transition(String to, IActionCondition c) throws ReflectiveOperationException {
      ActionTransition t = new ActionTransition();
      Field f = ActionTransition.class.getDeclaredField("transitionTo");
      f.setAccessible(true);
      f.set(t, to);
      Field cs = ActionTransition.class.getDeclaredField("conditions");
      cs.setAccessible(true);
      ((List<IActionCondition>) cs.get(t)).add(c);
      return t;
   }

   /** A state whose transitions all fail (no group lookup); {@code first} is the first transition's condition. */
   private static ActionState state(String name, IActionCondition first, int more) throws ReflectiveOperationException {
      ActionState s = new ActionState(name);
      s.transitions.add(transition("x", first));
      for (int i = 0; i < more; i++) {
         s.transitions.add(transition("y" + i, condition(() -> false)));
      }
      return s;
   }

   /** One evaluation parked in its first condition while the game thread clears the context; returns the task's failure. */
   private static Throwable raceShape(boolean guard) throws Throwable {
      ActionEval.settleOff = !guard;
      try {
         CountDownLatch inCondition = new CountDownLatch(1);
         AtomicReference<Boolean> wroteDuringEval = new AtomicReference<>(Boolean.FALSE);
         java.util.concurrent.atomic.AtomicBoolean writeDone = new java.util.concurrent.atomic.AtomicBoolean();
         IActionCondition park = condition(() -> {
            if (Thread.currentThread() instanceof FrameBatch.Worker) {
               inCondition.countDown();
               long until = System.nanoTime() + 100_000_000L; // guarded, the write waits for this evaluation: give up after 100 ms
               while (!writeDone.get() && System.nanoTime() < until) {
                  Thread.onSpinWait();
               }
               if (writeDone.get()) {
                  wroteDuringEval.set(Boolean.TRUE);
               }
            }
            return false;
         });
         ActionContext ctx = new ActionContext(owner());
         ActionGroup group = new ActionGroup();
         group.addState(state("parked", park, 3));
         ctx.setGroup(group);
         int gen = ActionEval.generation();
         ctx.pzoptTaskGen = gen; // its evaluation task goes out (ActionEval.publish)
         AtomicReference<Throwable> failure = new AtomicReference<>();
         FrameBatch.runAsync(1, i -> ActionEval.evalTask(ctx), failure::set);
         Check.check(inCondition.await(10, TimeUnit.SECONDS), "a worker took the evaluation");
         ActionStateSnapshot cleared = new ActionStateSnapshot(); // no state name: the next container stays cleared (setGroup clears it the same way first)
         ctx.setPlaybackStateSnapshot(cleared);
         writeDone.set(true);
         FrameBatch.join();
         if (guard) {
            Check.check(!wroteDuringEval.get(), "guarded: the write landed after the evaluation, not inside it");
            Check.check(ctx.pzoptEvalDone == gen, "guarded: the evaluation is done");
         }
         return failure.get();
      } finally {
         ActionEval.settleOff = false;
      }
   }

   /**
    * A published evaluation no worker has started (every worker busy on earlier tasks): the write runs it on the game thread at
    * once instead of waiting behind the backlog, and the task that reaches it later skips it.
    */
   private static void notStarted() throws Throwable {
      int workers = FrameBatch.THREADS;
      CountDownLatch release = new CountDownLatch(1);
      CountDownLatch parked = new CountDownLatch(workers);
      AtomicInteger evaluations = new AtomicInteger();
      AtomicReference<Thread> evaluatedOn = new AtomicReference<>();
      ActionContext ctx = new ActionContext(owner());
      ActionGroup group = new ActionGroup();
      group.addState(state("target", condition(() -> {
         evaluations.incrementAndGet();
         evaluatedOn.set(Thread.currentThread());
         return false;
      }), 2));
      ctx.setGroup(group);
      ActionEval.begin();
      int gen = ActionEval.generation();
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Object token = FrameBatch.runStream(j -> {
         if (j < workers) {
            parked.countDown();
            release.await(); // every worker holds an earlier task
         } else {
            ActionEval.evalTask(ctx);
         }
      }, failure::set);
      FrameBatch.publish(token, workers);
      Check.check(parked.await(10, TimeUnit.SECONDS), "every worker parked on an earlier task");
      ctx.pzoptTaskGen = gen;
      FrameBatch.publish(token, workers + 1); // the target's evaluation is out, behind the parked tasks
      long runs0 = ActionEval.settleRuns;
      ctx.reportEvent("wasBumped"); // a write into it: settle runs the evaluation here
      Check.check(ActionEval.settleRuns == runs0 + 1, "the write ran the unstarted evaluation itself");
      Check.check(evaluations.get() == 1 && evaluatedOn.get() == Thread.currentThread(), "evaluated once, on the writing thread");
      Check.check(ctx.pzoptEvalDone == gen, "the evaluation is done before the write");
      release.countDown();
      FrameBatch.seal(token, workers + 1);
      FrameBatch.join();
      Check.check(failure.get() == null, "no task failure");
      Check.check(evaluations.get() == 1, "the task that reached it skipped it (evaluated " + evaluations.get() + " times)");
   }

   /** 400 (or {@code frames}) frames: a stream of evaluations of 64 contexts while the game thread writes; returns failed frames. */
   private static int stress(boolean guard, int frames) throws Throwable {
      ActionEval.settleOff = !guard;
      try {
         java.util.Random rnd = new java.util.Random(42);
         int n = 64;
         ActionContext[] ctx = new ActionContext[n];
         ActionState[] states = new ActionState[2];
         for (int k = 0; k < 2; k++) {
            states[k] = state("s" + k, condition(() -> {
               for (int s = 0; s < 64; s++) {
                  Thread.onSpinWait();
               }
               return false;
            }), 8);
         }
         ActionGroup group = new ActionGroup();
         group.addState(states[0]);
         group.addState(states[1]);
         ActionStateSnapshot[] snapshots = new ActionStateSnapshot[2];
         for (int k = 0; k < 2; k++) {
            snapshots[k] = new ActionStateSnapshot();
            snapshots[k].stateName = states[k].getName();
            snapshots[k].childStateNames = new String[0];
         }
         for (int i = 0; i < n; i++) {
            ctx[i] = new ActionContext(owner());
            ctx[i].setGroup(group);
            ctx[i].setCurrentState(states[i & 1]);
         }
         int failed = 0;
         for (int frame = 0; frame < frames; frame++) {
            ActionEval.begin();
            int gen = ActionEval.generation();
            AtomicIntegerArray ran = new AtomicIntegerArray(n);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Object token = FrameBatch.runStream(j -> {
               ran.incrementAndGet(j);
               ActionEval.evalTask(ctx[j]);
            }, failure::set);
            int published = 0;
            while (published < n) {
               int step = Math.min(n - published, 1 + rnd.nextInt(6));
               for (int i = published; i < published + step; i++) {
                  ctx[i].pzoptTaskGen = gen;
               }
               published += step;
               FrameBatch.publish(token, published);
               for (int w = rnd.nextInt(4); w > 0 && published > 0; w--) {
                  ActionContext c = ctx[rnd.nextInt(published)]; // a published context: its task may be running
                  try {
                     switch (rnd.nextInt(4)) {
                        case 0 -> c.setCurrentState(states[rnd.nextInt(2)]);
                        case 1 -> c.setPlaybackStateSnapshot(snapshots[rnd.nextInt(2)]);
                        case 2 -> c.reportEvent("wasBumped");
                        default -> c.clearActionContextEvents();
                     }
                  } catch (RuntimeException e) {
                     if (guard) {
                        throw e;
                     }
                     failed++; // unguarded: the game thread's own side of the race can throw too
                  }
               }
            }
            FrameBatch.seal(token, n);
            FrameBatch.join();
            if (failure.get() != null) {
               failed++;
            }
            for (int i = 0; i < n; i++) {
               Check.check(ran.get(i) == 1, "frame " + frame + ": evaluation " + i + " ran once");
               if (guard) {
                  Check.check(ctx[i].pzoptEvalDone == gen, "frame " + frame + ": evaluation " + i + " done in its generation");
               }
            }
         }
         return failed;
      } finally {
         ActionEval.settleOff = false;
      }
   }

   /** A stream's token: publishing into a stream another user has joined (and replaced) is dropped. */
   private static void tokens() {
      AtomicIntegerArray a = new AtomicIntegerArray(64);
      AtomicIntegerArray b = new AtomicIntegerArray(64);
      AtomicInteger bPastList = new AtomicInteger();
      Object ta = FrameBatch.runStream(a::incrementAndGet, f -> { });
      FrameBatch.publish(ta, 3);
      Check.check(FrameBatch.streamOpen(ta), "stream A open");
      FrameBatch.run(2, i -> { }); // another batch user: joins (seals) A
      Check.check(!FrameBatch.streamOpen(ta), "a join sealed stream A");
      int bList = 2;
      Object tb = FrameBatch.runStream(j -> {
         if (j >= bList) {
            bPastList.incrementAndGet(); // the shape of the null task item: an index past the stream's own list
         }
         b.incrementAndGet(j);
      }, f -> { });
      FrameBatch.publish(tb, bList);
      FrameBatch.publish(ta, 10); // A's owner still publishing: must not reach B
      FrameBatch.seal(ta, 10);
      Check.check(FrameBatch.streamOpen(tb), "stream B still open after A's late publish / seal");
      FrameBatch.seal(tb, bList);
      FrameBatch.join();
      Check.check(bPastList.get() == 0, "no task of B past its list (got " + bPastList.get() + ")");
      for (int i = 0; i < 3; i++) {
         Check.check(a.get(i) == 1, "A's task " + i + " ran once");
      }
      Check.check(a.get(3) == 0, "A's late publish ran nothing");
      for (int i = 0; i < bList; i++) {
         Check.check(b.get(i) == 1, "B's task " + i + " ran once");
      }
   }
}
