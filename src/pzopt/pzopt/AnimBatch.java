package pzopt;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoZombie;
import zombie.core.skinnedmodel.animation.AnimationPlayer;

/**
 * The zombies' animation bone math on the other cores ({@code animBonesParallel}, 2026-09-22).
 *
 * <p>Every zombie with a 3D model spends its postupdate in {@code AnimationPlayer.Update}: the multi-track tick (which
 * fires the animation events) and then the standard-animation math, i.e. blending every track's keyframes into the bone
 * transforms, the body angles, the twist bones, the model-space and skin matrices. On the Louisville horde preset that
 * math is 9 % of the game thread (17 % for the whole zombie postupdate, 98 % busy). It only touches the player's own
 * arrays, the read-only clips, thread-safe pools and the scratch objects the override made thread-local, so it can run
 * anywhere: during {@code MovingObjectUpdateScheduler.postupdate()} the override's {@code updateInternal} keeps the
 * multi-track tick on the game thread and hands the rest to this batch; after the last object the batch runs on the
 * {@link FrameBatch} workers and the game thread together and is joined before anything (attachments, the render data)
 * reads a bone.
 *
 * <p>Not batched: players and animals, a zombie without a model (its update is non-visual), a zombie being grappled or
 * grappling (the other side reads its bones in the same loop), one that reanimated a dead player (the player copies its
 * bones right after), a ragdoll, a recording player, a child player (copies its parent's bones). Those run inline.
 *
 * <p>The batch runs after the loop rather than overlapping it because {@code IsoGameCharacter.updateAnimPlayer} (the
 * model-less path, most of a horde) flips {@code PerformanceSettings.interpolateAnims} around each call and the keyframe
 * sampling reads that flag.
 */
public final class AnimBatch {
   private AnimBatch() {
   }

   private static final boolean ENABLED = Config.ANIM_BONES_PARALLEL && Config.effectiveWorkers() > 1;

   private static boolean active; // game thread only: submissions accepted
   private static AnimationPlayer[] players = new AnimationPlayer[1024];
   private static float[] deltas = new float[1024];
   private static int count;
   private static volatile boolean failed;

   public static long batched, inline, frames, maxBatch, waitNanos, workNanos; // counters for the log

   private static final boolean ASYNC = Config.ANIM_BATCH_ASYNC;
   private static int pendingCount; // animBatchAsync: players[0..pendingCount) belong to the batch in flight

   /** Game thread: from here on the eligible zombies' bone math is queued instead of run. */
   public static void begin() {
      join();
      gameThread = Thread.currentThread();
      if (!ENABLED || failed) {
         return;
      }
      count = 0;
      slot.set(0);
      drawFrame = ASYNC && ZombieLate.DRAW && ZombAb.on(ZombAb.DRAW_LATE) && !CapsuleShadow.wanted(); // zombieDrawLate (f8-zomb3): latched for the frame's batch
      // the slots are claimed without a lock (animatorParallel submits from every frame worker), so the arrays are
      // sized up front for every moving object of the cell; a submit past the end runs its bone math inline
      int need = zombie.iso.IsoWorld.instance.getCell().getObjectList().size() + 64;
      if (players.length < need) {
         players = new AnimationPlayer[need * 2];
         deltas = new float[need * 2];
      }
      active = true;
   }

   private static final java.util.concurrent.atomic.AtomicInteger slot = new java.util.concurrent.atomic.AtomicInteger();

   /** Called by AnimationPlayer.updateInternal in place of the standard-animation math; true = queued, run it later. */
   public static boolean submit(AnimationPlayer player, float deltaT) { // lock-free: animatorParallel submits from the frame workers
      if (!active) {
         return false;
      }
      IsoGameCharacter character = player.getIsoGameCharacter();
      if (!(character instanceof IsoZombie) || !player.pzoptBatchable()) {
         inline++;
         return false;
      }
      IsoZombie zombie = (IsoZombie)character;
      if (zombie.isBeingGrappled() || zombie.isGrappling() || zombie.getReanimatedPlayer() != null) {
         inline++;
         return false;
      }
      int i = slot.getAndIncrement();
      if (i >= players.length) {
         inline++;
         return false; // more submitters than moving objects at begin(): cannot happen, but never write past the end
      }
      players[i] = player;
      deltas[i] = deltaT;
      if (ASYNC) {
         if (drawFrame) {
            player.pzoptDrawSnapshot(); // zombieDrawLate (f8-zomb3): the pose the render keeps while this bone math runs
         }
         player.pzoptInFlight = true;
      }
      batched++;
      return true;
   }

   /** Game thread: run everything queued since begin() on the workers and this thread (pzopt.FrameBatch), and wait for all of it. */
   public static void flush() {
      if (!active) {
         return;
      }
      active = false;
      count = Math.min(slot.get(), players.length); // every submitter has returned: the batches that submit were joined before this
      if (count == 0) {
         return;
      }
      frames++;
      if (count > maxBatch) {
         maxBatch = count;
      }
      if (ASYNC) {
         // animBatchAsync: the workers compute the bones while the game thread goes on with the rest of the frame's
         // logic; IsoWorld.FinishAnimation (the game's own join point of its threadAnimation debug option, right before
         // the render phase) or the next frame batch joins it.
         pendingCount = count;
         count = 0;
         for (int i = 0; i < pendingCount; i++) {
            players[i].pzoptInFlight = true; // zombieLateApply cleared the held zombies' flags before its apply
         }
         if (drawFrame && FrameBatch.backgroundUsable()) {
            // zombieDrawLate (f8-zomb3): the background lane, past FinishAnimation and through the render; the render draws
            // these players from their copies; ZombieLate.frameStart (the next frame) or a touch the copy does not cover joins it
            background = true;
            snapshots += pendingCount;
            FrameBatch.runBackground(pendingCount, i -> players[i].pzoptRunDeferred(deltas[i]), AnimBatch::asyncDone);
            return;
         }
         background = false;
         FrameBatch.runAsync(pendingCount, i -> players[i].pzoptRunDeferred(deltas[i]), AnimBatch::asyncDone);
         return;
      }
      long w0 = FrameBatch.workNanos;
      long q0 = FrameBatch.waitNanos;
      Throwable t = FrameBatch.run(count, i -> players[i].pzoptRunDeferred(deltas[i]));
      workNanos += FrameBatch.workNanos - w0;
      waitNanos += FrameBatch.waitNanos - q0;
      if (t != null && !failed) {
         failed = true; // back to the inline path from the next frame on
         Log.warn("animBonesParallel: deferred bone update failed, batching off: " + t);
      }
      java.util.Arrays.fill(players, 0, count, null);
      count = 0;
   }

   private static void asyncDone(Throwable t) {
      if (t != null && !failed) {
         failed = true;
         Log.warn("animBonesParallel: deferred bone update failed, batching off: " + t);
      }
      for (int i = 0; i < pendingCount; i++) {
         players[i].pzoptInFlight = false;
      }
      java.util.Arrays.fill(players, 0, pendingCount, null);
      pendingCount = 0;
      background = false;
   }

   private static Thread gameThread;
   public static long guardJoins; // animBatchAsync: game-thread touches of an in-flight player before the planned join

   /** AnimationPlayer accessors of a player whose bones are in flight: the game thread waits for the batch first; a worker (the batch itself) goes on. */
   public static void guard() {
      if (Thread.currentThread() != gameThread || FrameBatch.gameThreadInBackgroundTask()) {
         return; // a worker, or the game thread running a bone task itself inside the background join: never a join from inside the batch (z3-ld1 hung so)
      }
      if (ZombieLate.held()) {
         ZombieLate.touch(ZombieLate.R_ANIM); // zombieLateApply (f8-zomb3): a held zombie's player is touched: its apply runs first
      }
      if (pendingCount == 0 || !(background ? FrameBatch.hasBackground() : FrameBatch.hasPending())) {
         return;
      }
      guardJoins++;
      if (background) {
         ZombieLate.drawJoinsGuard++;
         if (ZombieLate.drawJoinsGuard <= 10) {
            Log.info("zombieDrawLate: a render-time touch not covered by the draw copy joins the bones: " + java.util.Arrays.toString(java.util.Arrays.copyOfRange(Thread.currentThread().getStackTrace(), 2, 9)));
         }
      }
      if (Config.DEV_ANIM_ASYNC_TRACE && guardJoins <= 20) {
         Log.info("animBatchAsync: early join from " + java.util.Arrays.toString(java.util.Arrays.copyOfRange(Thread.currentThread().getStackTrace(), 2, 9)));
      }
      join();
   }

   /** Game thread: wait for the bone batch in flight (animBatchAsync); every reader of a zombie's bones after the postupdate loop is behind this. */
   public static void join() {
      if (pendingCount > 0) {
         if (background) {
            FrameBatch.joinBackground(); // zombieDrawLate: runs asyncDone
         } else {
            FrameBatch.join();
         }
      }
   }

   private static boolean drawFrame; // zombieDrawLate (f8-zomb3): this frame's bone batch goes to the background lane
   private static boolean background; // the batch in flight is on the background lane
   public static long snapshots;

   /** zombieDrawLate: the frame's bone batch is (or will be) drawn late. */
   static boolean drawLate() {
      return drawFrame && ASYNC && FrameBatch.backgroundUsable();
   }

   /** Game thread or reader: a bone batch is in flight on the background lane. */
   static boolean pendingBackground() {
      return background && pendingCount > 0;
   }

   /** One line for the periodic FBORenderCell log. */
   public static String describe() {
      return "anim batch: frames=" + frames + " batched=" + batched + " inline=" + inline + " max=" + maxBatch
            + " work ms=" + (workNanos / 1_000_000L) + " wait ms=" + (waitNanos / 1_000_000L) + (failed ? " FAILED" : "")
            + (ASYNC ? " async guardJoins=" + guardJoins : "")
            + " shadow computed=" + ShadowPrep.computed + " served=" + ShadowPrep.served + " fallback=" + ShadowPrep.fallback;
   }
}
