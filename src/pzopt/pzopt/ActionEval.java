package pzopt;

import zombie.GameTime;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoZombie;
import zombie.characters.action.ActionContext;
import zombie.network.GameClient;
import zombie.network.GameServer;

/**
 * The zombies' action-context transition evaluation on the other cores ({@code actionEvalParallel}, 2026-09-22,
 * docs/plan-zombie-multithread.md §3.2 step 2).
 *
 * <p>Every frame every animating zombie's {@code ActionContext.update()} evaluates the transitions of its current
 * action state (and of its sub-states): tens of conditions, each a variable lookup and a callback into the zombie,
 * then a compare. It was 7-10 % of the game thread on the Louisville horde after the typed-operand rewrite. The
 * evaluation only reads the zombie (its variables, its target's position, the event set of the context) and writes
 * the context's own "next" container, so it can run on the {@link FrameBatch} workers; the state change itself
 * (history, enter / exit hooks, the events) and everything after it in {@code postUpdateAnimating} (the animator, the
 * model slot, the light info) must stay on the game thread in the original order.
 *
 * <p>The postupdate loop is split in two by the {@code MovingObjectUpdateScheduler} override:
 * <ol>
 * <li>the loop itself: every object's {@code postupdate()} as before, but an eligible zombie's {@code postUpdateAnimating}
 *     stops after the forward direction, aim angle and turning flags (the inputs the conditions read) and queues the
 *     zombie here ({@link #submit});</li>
 * <li>{@link #flush}: the queued contexts evaluate on the workers into their "next" containers; then, on the game
 *     thread and in queue order, each zombie runs the rest of its {@code postUpdateAnimating}
 *     ({@code pzoptPostUpdateAnimatingRest}: the {@code ActionContext.update()} override finds the evaluation done and
 *     only applies it, then the animator and the model update as stock).</li>
 * </ol>
 * The bone batch ({@code AnimBatch}) collects its work during step 2 and runs after it, as before.
 *
 * <p>Not queued (stock path inline): players and animals, multiplayer, a zombie being grappled / grappling / that
 * reanimated a player (its counterpart reads it in the same loop), and any zombie whose current state or sub-states
 * have a transition condition other than a variable compare or an event test (a {@code LuaCall} runs Lua). A
 * condition callback reached from a worker must not touch shared scratch: {@code IsoZombie.isFacingTarget} is the
 * one that did (static vectors), fixed in the IsoZombie override. {@code devActionEvalCheck=true} re-evaluates every
 * queued context on the game thread at apply time and counts disagreements ({@code mismatches}), which is the
 * determinism rig of this phase (two runs of the game are never frame-aligned, so a run-to-run checksum cannot be).
 */
public final class ActionEval {
   private ActionEval() {
   }

   private static final boolean ENABLED = Config.ACTION_EVAL_PARALLEL && Config.effectiveWorkers() > 1;

   private static boolean active; // game thread only: submissions accepted
   private static boolean applying; // game thread only: inside the phase-2 loop
   private static int generation; // one per flush; a context stamps its evaluation with it
   private static IsoZombie[] queue = new IsoZombie[1024];
   private static int count;
   private static volatile boolean failed;

   public static long batched, inline, frames, maxBatch, waitNanos, workNanos, mismatches, checks; // counters for the log
   public static long filterMisses; // devActionEvalCheck: lookups the name filter skipped that did resolve to an impure callback

   /** Marker for a null variable value in a snapshot map. */
   public static final Object NULL = new Object();

   /**
    * Callback variables audited as pure reads of the character (fields, its target's position, static settings), which a
    * worker may call; every other callback a state's conditions reference is read on the game thread into the snapshot
    * before the batch (stock's side effects stay there, in order). Keys are the slot keys, lower case. Stored slots need
    * no entry. Audit of 2026-09-22 (IsoZombie / IsoGameCharacter / IsoMovingObject callback bodies).
    */
   public static final java.util.Set<String> PURE_CALLBACKS = java.util.Set.of(
      "bonfloor", "bknockeddown", "hashitreaction", "bdead", "usephysichitreaction", "bclient", "bmultiplayer", "hitreaction",
      "playerattackposition", "bfakedead", "bstaggerback", "isvehiclecollision", "useragdollvehiclecollision", "canragdoll",
      "isfacingtarget", "realstate", "bumped", "bfalling", "isragdollfall", "issimulationactive", "fallonfront",
      "previousstate", "bcrawling", "issitting", "alerted", "isragdoll", "bbecomecrawler", "bknifedeath",
      "bisreanimatedforgrappleonly", "breanimate", "lasttargetseen", "bpathfindprediction", "bstoplunging", "zombiewalktype", "crawlertype",
      "eatspeed", "lungetimer", "reanimatetimer", "unbalancedlevel", "targetseentime", "bjawstabattach", "hitheadtype",
      "attackdiddamage", "attackoutcome", "bumptype", "collidetype", "footinjurytype", "onbed", "sittingonfurniture",
      "sitonground", "frombehind", "killedbyfall", "isprone", "isgettingup", "footinjury", "angle", "animangle", "twist",
      "targettwist", "shouldertwist", "excesstwist", "numtwistbones", "anglestepdelta", "angletwistdelta", "beenmovingfor",
      "momentumscalar", "isoverencumbered", "criticalhit", "falltime", "aim", "baimatfloor", "aimatflooramount",
      "verticalaimangle", "attackanim", "shoveanim", "stompanim", "isstompanim", "performinghostileanim", "firemode",
      "isanimatingbackwards", "iseditingragdoll", "isupright", "isonback", "bheadlookaround", "lookhorizontal",
      "lookvertical", "hitforce", "hitdir", "hideequippedhandl", "hideequippedhandr", "isunarmed", "ismeleeweaponequipped",
      "israngedweaponequipped", "isrendered", "stateeventdelaytimer",
      // Second audit, 2026-09-22 afternoon: the callbacks the first pass never met. IsoGameCharacter field getters
      // and derived reads (the hand items, the worn items, the action queue, the fall table) with no write and no
      // shared scratch; IsoZombie's network-moving test, small-vehicle test, distance to target, canSeeTarget field
      // and shouldGetUpFromCrawl (state comparisons only).
      "bumpdone", "bumpfall", "bumpfalltype", "bumpstaggered", "rangedweaponempty", "choptreespeed", "movedelta",
      "turndelta", "maxtwist", "isturning", "isturning90", "isturningaround", "bmoving", "hastarget",
      "grapplethrowoutwindow", "grapplethrowoverfence", "grapplethrowintocontainer", "recoilvarx", "recoilvary",
      "shouttype", "shoutitemmodel", "fallspeedseverity", "aimingmode", "hastimedactions",
      "bmovingnetwork", "bistargetissmallvehicle", "distancetotarget", "bcanseetarget", "bgetupfromcrawl",
      // Third audit, 2026-09-22: two grid reads (the square's objects and properties, the sheet-rope walk down).
      "intrees", "canclimbdownrope",
      // 42.21 (2026-09-28): IsoGameCharacter.isNearWallCrouching, a field getter.
      "nearwallcrouching");
      // Deliberately NOT here (they write through a getter or use shared scratch, so the game thread reads them into
      // the snapshot, in stock's order): battack / bhastarget / shouldsprint / getShouldAttack clear the target,
      // bthump drops the thump target, beatbodytarget rescans the corpses, blunge runs a pathfind line test through a
      // shared point pool, turndirection uses the class's static vectors; battackvehicle, bpassengerexposed,
      // bundervehicle, bbeingsteppedon, canclimbdownrope and intrees are not audited yet.

   /**
    * actionSnapshotFilter: the lower-case keys of every callback variable a character registers, read once off the first
    * batched zombie ({@code registerVariableCallbacks} runs in the constructor, so the set is complete and the same for
    * every zombie). A condition operand whose variable name is not in here can only ever resolve to a stored slot — a
    * pure read — so it never needs a snapshot; a name in here but also in {@link #PURE_CALLBACKS} is an audited pure
    * callback. Everything else is the handful of callbacks with side effects, and only those are read per frame.
    */
   private static java.util.Set<String> callbackKeys;

   /** Game thread, once: classify the character's variables for {@link #impureCallback}. */
   public static void initCallbackKeys(Object owner) {
      if (callbackKeys != null || !Config.ACTION_SNAPSHOT_FILTER
            || !(owner instanceof zombie.core.skinnedmodel.advancedanimation.IAnimationVariableRegistry registry)) {
         return;
      }
      java.util.HashSet<String> keys = new java.util.HashSet<>();
      int impure = 0;
      for (zombie.core.skinnedmodel.advancedanimation.IAnimationVariableSlot slot : registry.getGameVariablesInternal().getGameVariables()) {
         if (!(slot instanceof zombie.core.skinnedmodel.advancedanimation.AnimationVariableSlotCallback)) {
            continue;
         }
         String key = slot.getKey();
         if (key == null) {
            continue;
         }
         String lower = key.toLowerCase(java.util.Locale.ENGLISH);
         keys.add(lower);
         if (!PURE_CALLBACKS.contains(lower)) {
            impure++;
         }
      }
      if (keys.isEmpty()) {
         return; // nothing readable yet: stay conservative and try again with the next zombie
      }
      callbackKeys = keys;
      Log.info("actionSnapshotFilter: " + keys.size() + " callback variables, " + impure + " of them read per frame on the game thread");
   }

   /** animatorParallel: true once the callback variables are classified (before that every name counts as impure). */
   public static boolean callbackKeysReady() {
      return callbackKeys != null;
   }

   /** True when a condition operand of this variable name may resolve to a callback with side effects. */
   public static boolean impureCallback(String name) {
      java.util.Set<String> keys = callbackKeys;
      if (keys == null) {
         return true; // not classified yet: snapshot, i.e. the pre-filter behaves like the full walk
      }
      String lower = name.toLowerCase(java.util.Locale.ENGLISH);
      return keys.contains(lower) && !PURE_CALLBACKS.contains(lower) && !(Config.GUARDED_CALLBACKS && GUARDED_CALLBACKS.contains(lower));
   }

   /**
    * guardedCallbacks (2026-09-23): callbacks whose side effect is conditional and rare, with a guard right before it in
    * the IsoZombie override ({@link AnimParallel#conditionalGuard}): bHasTarget / shouldSprint clear a target that has
    * become a reanimated corpse, bPassengerExposed walks the vehicle areas only when the target sits in a vehicle. Off
    * the game thread the guard throws before the effect: the transition evaluation leaves the context unstamped (the
    * game thread evaluates it stock-wise in the apply loop), the animator task finishes the zombie on the game thread.
    * Otherwise they are plain reads, so they need no snapshot read on the game thread.
    */
   public static final java.util.Set<String> GUARDED_CALLBACKS = java.util.Set.of("bhastarget", "shouldsprint", "bpassengerexposed", "battack", "bthump", "blunge", "battackvehicle", "beatbodytarget");

   public static long guardedFallbacks; // evaluations left to the game thread because a guarded callback hit its side effect

   private static final Tl<java.util.IdentityHashMap<Object, Object>> SNAPSHOT = new Tl<>(null);

   /** The snapshot of the context being evaluated on this thread, or null (read the variables directly). */
   public static java.util.IdentityHashMap<Object, Object> currentSnapshot() {
      return SNAPSHOT.get();
   }

   public static void enterSnapshot(java.util.IdentityHashMap<Object, Object> snapshot) {
      SNAPSHOT.set(snapshot);
   }

   public static void exitSnapshot() {
      SNAPSHOT.set(null); // tail pass: keeps the thread's map entry for the next enterSnapshot (remove + set allocated an Entry per evaluation, 3.5 MB/s); get() reads null either way
   }

   /** Game thread: from here on eligible zombies stop their postUpdateAnimating after the turning flags and queue here. */
   public static void begin() {
      if (lateHeld) {
         ZombieLate.touch(ZombieLate.R_BEGIN); // zombieLateApply: a held apply never outlives its frame (FinishAnimation normally ran it)
      }
      if (!ENABLED || failed) {
         return;
      }
      if (stream.size() > 0) {
         // the previous frame's stream is landed before its task list is dropped, and before the generation moves on
         // (f8-streamfix: a task still running after the increment stamped its zombie done / evaluated in the NEW
         // generation, so this frame's apply would have taken that stale evaluation without waiting for the new one)
         FrameBatch.join();
         stream.release();
      }
      generation++;
      count = 0;
      active = true;
      streamState = 0; // postupdateFuse
      streamAllowed = true; // PostupdateBatch.prepass narrows it for a small frame
   }

   private static volatile boolean streamOff; // a bounded wait gave up: no more early evaluations this session
   private static boolean streamAllowed = true; // game thread: this frame has zombies enough for the stream (postupdateStreamMin)

   /** PostupdateBatch.prepass: whether this frame's postupdate is big enough for the early-evaluation stream. */
   static void allowStream(boolean allowed) {
      streamAllowed = allowed;
   }

   /** AnimParallel's bounded waits: a task never came; the streams stop for the session (logged once). */
   static void streamGaveUp(String what) {
      if (!streamOff) {
         streamOff = true;
         Log.warn("postupdateFuse: " + what + "; early evaluations off for the session");
      }
   }

   // ---- postupdateFuse (zombie redesign pass, 2026-10-13, f8-zomb2) ----
   //
   // The transition evaluations used to start after the postupdate loop, and the apply loop then waited for them and for
   // the animators (~0.3 ms a frame on the Louisville horde) and ran every zombie's context update itself. A zombie with no
   // target reads nothing the rest of the loop changes (its own variables and events, the static grid), so it is published
   // to a stream batch right after its loop body (its snapshot taken), and the evaluation runs during the loop. When the
   // evaluation changes no state, the same task consumes it (the context update of an unchanged container: its events
   // cleared), arms the animator capture and runs the animator and model update, exactly the work apply() would hand a worker
   // after its step 1; apply() skips such a zombie in step 1 and finishes it in step 3 in queue order as before. The
   // evaluation-done mark is set after that decision, so the apply loop never sees a half-fused zombie.
   private static final boolean FUSE = Config.POSTUPDATE_FUSE;
   private static int streamState; // 0 not opened this frame, 1 open, 2 sealed by another batch user (no more publishing)
   private static final StreamTasks<IsoZombie> stream = new StreamTasks<>(); // task j: its zombie (pzoptStreamGen = the generation it went out in), may it fuse
   private static final Tl<boolean[]> DEFER = new Tl<>(() -> new boolean[1]);
   public static long fuseEarly, fuseLate, fuseStreams, fuseDied;
   static volatile long streamNullItems; // f8-zomb3: stream tasks that found no zombie (diagnosis open)

   // ---- postupdateClean (zombie redesign pass, 2026-10-13, f8-zomb2): a zombie whose whole postupdate ran on its movement task
   // joins the queue here at its place in the bucket loop, without the loop touching it (pzopt.PostupdateBatch.cleanAt) ----

   /** A frame worker (PostupdateBatch's task): submit()'s conditions for a zombie with no target, from worker-safe reads. */
   public static boolean cleanSubmitOk(IsoZombie z) {
      if (!active || callbackKeys == null || !HEAD_ON_WORKER || !GtAb.on(GtAb.HEAD_ON_WORKER) || GameClient.client || GameServer.server) {
         return false;
      }
      ActionContext ctx = z.getActionContext();
      return ctx != null && ctx.getGroup() != null && ctx.pzoptCleanSafe();
   }

   /** A frame worker, committed: what submit() does to the zombie itself (the empty snapshot, the head left to the task). */
   public static void cleanPrepare(IsoZombie z) {
      z.getActionContext().pzoptSnapshotClear();
      z.pzoptHeadPending = true;
   }

   /** Game thread, the bucket loop at the zombie's place: submit()'s queueing of a clean zombie. */
   public static void appendClean(IsoZombie zombie) {
      if (count == queue.length) {
         queue = java.util.Arrays.copyOf(queue, count * 2);
      }
      queue[count++] = zombie;
      batched++;
      cleanAppended++;
      if (zombie.pzoptStreamGen != generation && FUSE && AnimParallel.pipelined() && ZombAb.on(ZombAb.PU_FUSE)) {
         publishEarly(zombie); // postupdateFuse: no target (normally already out: publishCleanEarly)
      }
   }

   /**
    * Game thread, right after the movement batch and before the bucket loop (PostupdateBatch): a clean zombie that will join the
    * queue in the loop has nothing left for the loop to change before its evaluation (no target, an empty snapshot), so its
    * evaluation goes out now and the whole loop is its runway; appendClean then only queues it.
    */
   public static void publishCleanEarly(IsoZombie zombie) {
      if (FUSE && AnimParallel.pipelined() && ZombAb.on(ZombAb.PU_FUSE)) {
         cleanEarly++;
         publishEarly(zombie);
      }
   }

   public static long cleanEarly;

   public static long cleanAppended;

   /** ActionContext.pzoptEvaluate: true inside a stream task, which marks the evaluation done itself after its fused tail. */
   public static boolean deferEvalDone() {
      return FUSE && DEFER.get()[0];
   }

   private static void publish(IsoZombie z, boolean fuse) {
      z.pzoptStreamGen = generation;
      z.getActionContext().pzoptTaskGen = generation; // f8-streamfix: its evaluation task is out (settle)
      PuStream.publishEvals(stream.add(z, fuse));
   }

   // ---- f8-streamfix: the game thread never writes into a zombie whose evaluation task may still be running ----
   //
   // A published zombie's task (the transition evaluation into the context's next container, and with postupdateFuse its
   // context update and animator) runs on a frame worker while the game thread goes on: through the rest of the bucket loop
   // and, until a FrameBatch join lands the stream, beyond it. A game-thread write into that zombie's action context or state
   // in that window raced with the task: ActionContext.setGroup -> setCurrentState clears the next container the worker is
   // evaluating (an NPE "this.currentState is null" in ActionStateContainer when the worker read it in between, otherwise an
   // evaluation of a half-written container), reportEvent / changeState / removeFromWorld / resetForReuse change what it
   // reads (stock evaluates a zombie in its own loop slot, before any later write). Every such write first waits here for the
   // task to finish, which is stock's order.
   static boolean settleOff; // tests only: the unguarded behaviour, to show the race (ActionEvalSettleTest)
   public static long settleWaits, settleRuns, settleWaitNanos, settleTimeouts;

   // Each evaluation task claims its context for the generation first (one atomic swap): the task and settle() never both run
   // it, and a write that meets a published evaluation no worker has started yet runs it right there instead of waiting behind
   // the stream's backlog (~1 ms a removal in run sf-cap1 when settle only waited).
   private static final java.lang.invoke.VarHandle CLAIM;

   static {
      try {
         CLAIM = java.lang.invoke.MethodHandles.lookup().findVarHandle(zombie.characters.action.ActionContext.class, "pzoptTaskClaim", int.class);
      } catch (ReflectiveOperationException e) {
         throw new ExceptionInInitializerError(e);
      }
   }

   /** Any thread: take this generation's evaluation of the context; false when another thread took it already. */
   static boolean claim(zombie.characters.action.ActionContext ctx) {
      int gen = generation;
      return (int) CLAIM.getAndSet(ctx, gen) != gen;
   }

   /** The evaluation task of an asynchronous evaluation batch (not the stream's): run unless settle() took it. */
   static void evalTask(zombie.characters.action.ActionContext ctx) {
      if (claim(ctx)) {
         evalBody(ctx);
      }
   }

   /** The evaluation itself: a character's (its head on the worker first, headOnWorker), else the context's alone (tests). */
   private static void evalBody(zombie.characters.action.ActionContext ctx) {
      if (ctx.getOwner() instanceof IsoGameCharacter c) {
         c.pzoptEvalTask();
      } else {
         ctx.pzoptEvaluate();
      }
   }
   private static int settleLogged;

   /** Settle reasons, for the log. */
   public static final int S_CONTEXT = 0, S_EVENT = 1, S_STATE = 2, S_REMOVE = 3, S_REUSE = 4, S_HIT = 5;
   private static final String[] S_NAMES = {"context", "event", "state", "remove", "reuse", "hit"};
   private static final long[] settleBy = new long[S_NAMES.length];

   /**
    * Game thread, before it writes into the character this context belongs to: wait until the character's evaluation task of
    * this generation (if one went out and has not finished) is done. Free when none is out (two int compares). A frame worker
    * (a task writing its own zombie) and the game thread inside a batch task of its own never wait here.
    */
   public static void settle(zombie.characters.action.ActionContext ctx, int reason) {
      if (ZombieLate.held() && ctx != null && ctx.getOwner() instanceof IsoZombie lz) {
         ZombieLate.touch(lz, ZombieLate.R_OTHER); // zombieLateApply: a held zombie's apply runs before any write into it (its events, state, context)
      }
      int gen = generation;
      if (ctx == null || ctx.pzoptTaskGen != gen || ctx.pzoptEvalDone == gen || settleOff) {
         return;
      }
      if (Thread.currentThread() instanceof FrameBatch.Worker || FrameBatch.gameThreadInTask()) {
         return;
      }
      long t0 = System.nanoTime();
      if (claim(ctx)) {
         // not started yet: the game thread runs the evaluation itself, now (stock's order: before the write), and the task
         // that reaches it later finds it claimed. No fused tail here: the apply loop finishes this zombie as an unfused one.
         settleRuns++;
         settleBy[reason]++;
         UpdateBatch.pomSet(1.0F); // the perObjectMultiplier the flush-time evaluation sees, as the stream task's
         try {
            evalBody(ctx);
         } catch (Throwable t) {
            Log.warn("postupdate stream: an evaluation run before a write failed: " + t); // a task's failure is logged the same way
         } finally {
            UpdateBatch.pomClear();
            ctx.pzoptMarkEvalDone();
         }
         settleWaitNanos += System.nanoTime() - t0;
         return;
      }
      settleWaits++;
      settleBy[reason]++;
      if (settleLogged < 5) {
         settleLogged++;
         StringBuilder b = new StringBuilder("postupdate stream: a write (" + S_NAMES[reason] + ") to " + ctx.getOwner() + " waited for its evaluation task; caller:");
         StackTraceElement[] st = new Throwable().getStackTrace();
         for (int i = 1; i < Math.min(st.length, 12); i++) {
            b.append("\n    at ").append(st[i]);
         }
         Log.info(b.toString());
      }
      int spins = 0;
      while (ctx.pzoptEvalDone != gen) {
         if (FrameBatch.helpOne()) {
            continue;
         }
         if (++spins < 500) {
            Thread.onSpinWait();
         } else {
            ShortWait.pause(5_000L);
            if (System.nanoTime() - t0 > 200_000_000L) {
               settleTimeouts++;
               streamGaveUp("a write to " + ctx.getOwner() + " waited 200 ms for its evaluation task (generation " + gen + ", done " + ctx.pzoptEvalDone + ")");
               break;
            }
         }
      }
      settleWaitNanos += System.nanoTime() - t0;
   }

   /** settle() for a character (zombies only: no other character's evaluation goes to a task). */
   public static void settle(IsoGameCharacter c, int reason) {
      if (c instanceof IsoZombie) {
         settle(c.getActionContext(), reason);
      }
   }

   private static String settleCounts() {
      StringBuilder b = new StringBuilder();
      for (int i = 0; i < S_NAMES.length; i++) {
         b.append(i == 0 ? "" : " ").append(S_NAMES[i]).append(' ').append(settleBy[i]);
      }
      return b.toString();
   }

   /** Game thread, submit(): a target-less zombie's evaluation goes to the workers now. */
   private static void publishEarly(IsoZombie z) {
      if (streamState == 2 || streamOff || !streamAllowed) {
         return;
      }
      if (streamState == 0) {
         if (!PuStream.isOpen()) {
            if (FrameBatch.hasPending()) {
               return; // some batch is still out: this zombie waits for the flush like before
            }
            PuStream.open(0);
         }
         AnimParallel.setBaseInterpolate(zombie.core.PerformanceSettings.interpolateAnims);
         streamState = 1;
         fuseStreams++;
      } else if (!PuStream.isOpen()) {
         streamState = 2; // another batch user joined it: the rest waits for the flush
         fuseDied++;
         return;
      }
      fuseEarly++;
      publish(z, true);
   }

   static void streamTask(int j) {
      IsoZombie z = stream.item(j);
      if (z == null) { // zombie redesign round 3 (f8-zomb3): seen once a run in 3 of 22 runs with zombieLateApply, 1 of 71 without (an NPE here before); diagnosis open
         streamNullItems++;
         if (streamNullItems <= 5) {
            Log.warn("postupdate stream: task " + j + " has no zombie (list size " + stream.size() + ", generation " + generation + ", stream state " + streamState
                  + ", late held " + ZombieLate.held() + ", thread " + Thread.currentThread().getName() + ")");
         }
         return;
      }
      if (!claim(z.getActionContext())) {
         return; // f8-streamfix: settle() ran this evaluation on the game thread before a write into the zombie
      }
      if (!stream.flag(j)) {
         z.pzoptEvalTask();
         return;
      }
      boolean[] defer = DEFER.get();
      defer[0] = true;
      UpdateBatch.pomSet(1.0F); // the perObjectMultiplier the flush-time evaluation sees (the buckets reset it to 1)
      try {
         z.pzoptEvalTask();
         defer[0] = false;
         fusedTail(z);
      } finally {
         defer[0] = false;
         UpdateBatch.pomClear();
         z.getActionContext().pzoptMarkEvalDone();
      }
   }

   private static void fusedTail(IsoZombie z) {
      if (z.pzoptHeadOnGame || z.pzoptHeadPending || !AnimParallel.eligibleOnWorker(z) || !z.getActionContext().pzoptUpdateNoChange()) {
         return;
      }
      UpdateBatch.pomSet(Config.DEV_ACTION_EVAL_UNIT_MULTIPLIER ? 1.0F : (float) z.getCurrentSimulationLevel().getFrameMod()); // useMultiplier's value
      AnimCapture c = z.pzoptCapture();
      c.arm(z.getAnimationTimeDelta());
      c.fusedGen = generation;
      z.pzoptRestWorker();
   }

   /** Game thread, flush: every queued zombie the stream did not take, then the seal. False: no stream this frame. */
   private static boolean sealStream() {
      if (streamState == 0) {
         return false;
      }
      if (streamState == 1 && PuStream.isOpen()) {
         for (int i = 0; i < count; i++) {
            if (queue[i].pzoptStreamGen != generation) {
               fuseLate++;
               publish(queue[i], false);
            }
         }
         PuStream.seal(); // streamZ stays until begin(): the tasks read it until the stream is joined (a fill here lost evaluations: zomb2-all1 hung)
         return true;
      }
      // sealed early by another batch user: the rest as a plain evaluation batch
      int n = 0;
      IsoZombie[] rest = new IsoZombie[count];
      for (int i = 0; i < count; i++) {
         if (queue[i].pzoptStreamGen != generation) {
            rest[n++] = queue[i];
         }
      }
      final IsoZombie[] r = rest;
      for (int i = 0; i < n; i++) {
         r[i].getActionContext().pzoptTaskGen = generation; // f8-streamfix: its evaluation task is out (settle)
      }
      FrameBatch.runAsync(n, j -> evalTask(r[j].getActionContext()), ActionEval::evalDone);
      return true;
   }

   /** The generation of the batch being evaluated / applied; a context's evaluation is only applied in the same one. */
   public static int generation() {
      return generation;
   }

   /** True while the game thread applies the batch (phase 2); an evaluation is consumed by update() only then. */
   public static boolean applying() {
      return applying;
   }

   /**
    * Called by IsoGameCharacter.postUpdateAnimating right after the turning flags: true = the rest of it runs in
    * flush(), in the same order, after the parallel evaluation.
    */
   public static boolean submit(IsoGameCharacter character) {
      return submit(character, false);
   }

   /** headOnWorker: queue the zombie before its postUpdateAnimating head; the evaluation task runs the head first. */
   public static boolean submitWithHead(IsoGameCharacter character) {
      return HEAD_ON_WORKER && GtAb.on(GtAb.HEAD_ON_WORKER) && submit(character, true); // devGtAlternate: within-run A/B
   }

   private static final boolean HEAD_ON_WORKER = Config.HEAD_ON_WORKER;

   private static boolean submit(IsoGameCharacter character, boolean headPending) {
      if (!active) {
         return false;
      }
      if (!(character instanceof IsoZombie)) {
         return false;
      }
      IsoZombie zombie = (IsoZombie)character;
      if (GameClient.client || GameServer.server || zombie.isBeingGrappled() || zombie.isGrappling() || zombie.getReanimatedPlayer() != null) {
         inline++;
         return false;
      }
      initCallbackKeys(zombie); // actionSnapshotFilter: classify the callback variables before the first state is cached
      ActionContext context = zombie.getActionContext();
      if (context == null || context.getGroup() == null || !context.pzoptOffThreadSafe()) {
         inline++;
         return false;
      }
      context.pzoptSnapshot(); // the side-effect callbacks, here and now like stock
      boolean pzoptEarly = FUSE && zombie.getTarget() == null && AnimParallel.pipelined() && ZombAb.on(ZombAb.PU_FUSE); // postupdateFuse
      if (ZombTiming.ON && !context.pzoptSnapshotEmpty()) {
         ZombTiming.add(ZombTiming.X1, 1L); // zombie redesign census: zombies whose snapshot read a side-effecting callback
      }
      zombie.pzoptHeadPending = headPending;
      if (count == queue.length) {
         queue = java.util.Arrays.copyOf(queue, count * 2);
      }
      queue[count++] = zombie;
      batched++;
      if (pzoptEarly) {
         publishEarly(zombie); // postupdateFuse
      }
      return true;
   }

   /** Game thread, after the postupdate loop: evaluate the queued contexts on the workers, then finish the queued zombies in order. */
   public static void flush() {
      if (streamState == 0 && PuStream.isOpen()) {
         PuStream.close(); // postupdateMoveStream opened it and no evaluation joined: sealed here, joined by the next batch
      }
      if (!active) {
         return;
      }
      active = false;
      if (count == 0) {
         return;
      }
      frames++;
      if (count > maxBatch) {
         maxBatch = count;
      }
      if (AnimParallel.pipelined()) {
         // animatorPipeline: the evaluations run on the workers alone; the apply loop takes each zombie as soon as its
         // evaluation is done (AnimParallel.apply step 1) instead of this thread evaluating a share and then applying
         if (!sealStream()) { // postupdateFuse: the stream already holds the early ones; the rest join it here
            for (int i = 0; i < count; i++) {
               queue[i].getActionContext().pzoptTaskGen = generation; // f8-streamfix: its evaluation task is out (settle)
            }
            FrameBatch.runAsync(count, i -> evalTask(queue[i].getActionContext()), ActionEval::evalDone);
         }
         if (ZombieLate.tryHold(queue, count, generation)) { // zombieLateApply (f8-zomb3): the apply waits for FinishAnimation
            lateHeld = true;
            return;
         }
         applyPipelined();
         return;
      }
      flushSerial();
   }

   private static boolean lateHeld; // zombieLateApply: the queue is held for finishLate

   /** zombieLateApply (f8-zomb3), game thread: the held apply of this frame's queue (pzopt.ZombieLate.finish). */
   static void finishLate() {
      if (!lateHeld) {
         return;
      }
      lateHeld = false;
      applyPipelined();
   }

   private static void applyPipelined() {
      applying = true;
      GameTime pipelineTime = GameTime.getInstance();
      float pipelineMultiplier = pipelineTime.perObjectMultiplier;
      try {
         AnimParallel.apply(queue, count, true);
      } finally {
         pipelineTime.perObjectMultiplier = pipelineMultiplier; // see the multiplier note below
         applying = false;
         java.util.Arrays.fill(queue, 0, count, null);
         count = 0;
      }
   }

   private static void flushSerial() {
      long w0 = FrameBatch.workNanos;
      long q0 = FrameBatch.waitNanos;
      Throwable t = FrameBatch.run(count, i -> queue[i].pzoptEvalTask());
      workNanos += FrameBatch.workNanos - w0;
      waitNanos += FrameBatch.waitNanos - q0;
      if (t != null && !failed) {
         failed = true; // the contexts that did not get their stamp evaluate on the game thread below; stock path from the next frame on
         Log.warn("actionEvalParallel: transition evaluation failed on a worker, batching off: " + t);
      }
      applying = true;
      // Each bucket's postupdate ran its zombies at perObjectMultiplier = the bucket's frame mod and reset it to 1 at
      // the end; the rest of postUpdateAnimating (animation advance) must see the same multiplier, or a zombie on a
      // reduced simulation level (off-screen: SIXTEENTH, frame mod 16) advances its thump animation 1 frame per update
      // while ThumpState counts the strikes over 16: every strike counted up to 16 times (bursts of 8 / 16 thumps).
      GameTime gameTime = GameTime.getInstance();
      float multiplier = gameTime.perObjectMultiplier;
      try {
         if (AnimParallel.enabled()) {
            AnimParallel.apply(queue, count, false); // animatorParallel: the animators and models of the queue on the workers (it sets the multiplier per zombie too)
         } else {
            for (int i = 0; i < count; i++) {
               useMultiplier(queue[i]);
               queue[i].pzoptPostUpdateAnimatingRest();
            }
         }
      } finally {
         gameTime.perObjectMultiplier = multiplier;
         applying = false;
         java.util.Arrays.fill(queue, 0, count, null);
         count = 0;
      }
   }

   /**
    * Game thread: the perObjectMultiplier the zombie's bucket ran its postupdate with (its simulation level's frame mod),
    * for the rest of its postUpdateAnimating (see the note in flush). Callers restore the frame's value afterwards.
    */
   public static void useMultiplier(IsoZombie zombie) {
      if (!Config.DEV_ACTION_EVAL_UNIT_MULTIPLIER) {
         GameTime.getInstance().perObjectMultiplier = zombie.getCurrentSimulationLevel().getFrameMod();
      }
   }

   private static void evalDone(Throwable t) {
      if (t != null && !failed) {
         failed = true;
         Log.warn("actionEvalParallel: transition evaluation failed on a worker, batching off: " + t);
      }
   }

   /** One line for the periodic FBORenderCell log. */
   public static String describe() {
      return "action eval: frames=" + frames + " batched=" + batched + " inline=" + inline + " max=" + maxBatch
            + " work ms=" + (workNanos / 1_000_000L) + " wait ms=" + (waitNanos / 1_000_000L)
            + " guardedFallbacks=" + guardedFallbacks + " cleanAppended=" + cleanAppended + " cleanEarly=" + cleanEarly + (FUSE ? " fuseStreams=" + fuseStreams + " fuseEarly=" + fuseEarly + " fuseLate=" + fuseLate + " fuseDied=" + fuseDied : "")
            + " settle runs=" + settleRuns + " waits=" + settleWaits + " (" + settleCounts() + ") settle ms=" + (settleWaitNanos / 1_000_000L) + " settle timeouts=" + settleTimeouts
            + (Config.DEV_ACTION_EVAL_CHECK ? " checked=" + checks + " mismatches=" + mismatches + " filterMisses=" + filterMisses : "") + (failed ? " FAILED" : "");
   }
}
