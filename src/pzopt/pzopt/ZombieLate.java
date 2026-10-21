package pzopt;

import zombie.characters.IsoZombie;

/**
 * The zombie frame's late apply and late draw (zombie redesign round 3, 2026-10-13, worktree f8-zomb3; both behaviour
 * differences the maintainer approved, each behind its own key and off by default until the checks are seen).
 *
 * <p><b>Late apply ({@code zombieLateApply}).</b> The postupdate ends with {@link ActionEval#flush}: the transition
 * evaluations of the queued zombies (most of them already published to the frame workers during the bucket loop by
 * {@code postupdateFuse}) are waited for one by one in queue order, the state changes and the anim events applied
 * (AnimParallel.apply steps 1 and 3), and the animators of the zombies that changed state run on the workers in between.
 * The workers' share of that (~0.6 ms of evaluations and animators on the Louisville horde) is more than the loop lasts,
 * so the game thread waited for it. With the key the flush only seals the stream and holds the queue; the apply runs at
 * {@code IsoWorld.FinishAnimation} (the game's own animation join point, right before the render: stock's
 * {@code threadAnimation} debug option runs the whole postupdate concurrently up to there), so the evaluations and the
 * fused animators overlap the rest of the frame's logic (updateThread, entity / animal / radio updates, UpdateStuff, OnTick,
 * the UI update). The bone batch is started after the apply, as before.
 *
 * <p>What sees a different order (the approved difference): every game-thread step between the end of the postupdate and
 * FinishAnimation sees a queued zombie before this frame's action-state change, its anim events (footsteps, attack hit
 * checks, thump strikes, the state's anim-event hooks) and its animator step, instead of after them. Engine writes that
 * would collide with the held apply finish it first ({@link #touch}): a state-machine change of a held zombie (death, hit
 * reaction, Lua {@code changeState}), its removal from the world, ModelManager removing or resetting its model, and any
 * game-thread access to its animation player (the AnimationPlayer accessors' guards). Reads that are not guarded (Lua
 * reading a variable or the current action state) see the pre-apply value and may race with a worker's evaluation or
 * fused animator of that zombie (the threadAnimation option's exposure); the workers read the frame's
 * {@code fpsMultiplier} latched at the hold, never a game-thread code's temporary value (AnimatedModel / IsoDeadBody set it
 * to 100 around their own poses).
 *
 * <p><b>Late draw ({@code zombieDrawLate}).</b> The bone batch ({@link AnimBatch}) runs on the frame workers' background
 * lane ({@link FrameBatch#runBackground}) through the render instead of being joined at FinishAnimation or by the first
 * render-time batch, and the render draws each zombie whose bones are in flight with the pose of its previous bone update:
 * {@code AnimationPlayer.pzoptDrawSnapshot} copies the render outputs (skin palettes, model transforms, the bone-time angle,
 * the shadow pair) when its bone math is queued, before the worker overwrites them, and the accessors the render reads
 * serve the copy while the batch is in flight ({@link #serving}). Everything else the render reads of a zombie (position,
 * alpha, outline, model slot, lighting) was final by FinishAnimation (only the bone math runs during the render). The
 * batch is joined at the top of the next frame's update ({@link #frameStart}) or by any game-thread access the copy does
 * not cover (the guards).
 */
public final class ZombieLate {
   private ZombieLate() {
   }

   static final boolean LATE = Config.ZOMBIE_LATE_APPLY;
   static final boolean DRAW = Config.ZOMBIE_DRAW_LATE;

   private static boolean held; // game thread: this frame's apply waits for FinishAnimation
   private static IsoZombie[] heldQueue;
   private static int heldCount;
   private static int heldGen;
   private static volatile float latchedFps = Float.NaN; // the frame's fpsMultiplier as the workers see it while held

   public static long touchInTask;
   public static long holds, finishes, touches, touchState, touchRemove, touchModel, touchAnim, touchBegin, touchOther, touchReuse;
   public static long drawFrames, drawServed, drawJoinsGuard, drawJoinsFrame, drawJoinNanos;

   /** Game thread, ActionEval.flush: hold the apply of this frame's queue until FinishAnimation (true = held). */
   static boolean tryHold(IsoZombie[] queue, int count, int generation) {
      if (!LATE || !ZombAb.on(ZombAb.LATE_APPLY) || count == 0) {
         return false;
      }
      gameThread = Thread.currentThread();
      held = true;
      heldQueue = queue;
      heldCount = count;
      heldGen = generation;
      latchedFps = zombie.GameTime.getInstance().fpsMultiplier;
      holds++;
      for (int i = 0; i < count; i++) {
         IsoZombie z = queue[i];
         z.pzoptLateGen = generation;
         zombie.core.skinnedmodel.animation.AnimationPlayer p = z.getAnimationPlayer();
         if (p != null) {
            p.pzoptInFlight = true; // every game-thread access through the AnimationPlayer accessors finishes the apply first
         }
      }
      return true;
   }

   /** True while a late apply is held (game thread). */
   public static boolean held() {
      return held;
   }

   /** Any thread, GameTime: the fpsMultiplier a frame worker uses while the apply is held (the field otherwise). */
   public static float fps(float field) {
      float l = latchedFps;
      return l == l && Thread.currentThread() instanceof FrameBatch.Worker ? l : field;
   }

   /** Game thread: is this zombie's apply held right now. */
   public static boolean pending(IsoZombie z) {
      return held && z.pzoptLateGen == heldGen;
   }

   public static final int R_STATE = 1, R_REMOVE = 2, R_MODEL = 3, R_ANIM = 4, R_BEGIN = 5, R_OTHER = 6, R_REUSE = 7; // R_REUSE: f8-streamfix

   /** Game thread, a hook that would collide with the held apply: finish it now (and start the bone batch). */
   public static void touch(int reason) {
      if (!held || !onGameThread()) {
         return;
      }
      if (FrameBatch.gameThreadInTask()) {
         touchInTask++; // inside a batch task the game thread runs: the apply (batches of its own) cannot start here; FinishAnimation runs it
         return;
      }
      touches++;
      switch (reason) {
         case R_STATE -> touchState++;
         case R_REMOVE -> touchRemove++;
         case R_MODEL -> touchModel++;
         case R_ANIM -> touchAnim++;
         case R_BEGIN -> touchBegin++;
         case R_REUSE -> touchReuse++; // f8-streamfix
         default -> touchOther++;
      }
      finish();
   }

   /** Game thread, a zombie-specific hook: finish the held apply only when this zombie is in it. */
   public static void touch(IsoZombie z, int reason) {
      if (held && z.pzoptLateGen == heldGen) {
         touch(reason);
      }
   }

   private static Thread gameThread;
   private static volatile boolean drawPhase; // zombieDrawLate: between FinishAnimation and the next frame's start (the copies are served)

   private static boolean onGameThread() {
      return Thread.currentThread() == gameThread;
   }

   /** Game thread, the held apply and the bone batch's start. */
   private static void finish() {
      if (!held) {
         return;
      }
      held = false;
      IsoZombie[] q = heldQueue;
      int n = heldCount;
      heldQueue = null;
      heldCount = 0;
      long zt = ZombTiming.begin();
      try {
         for (int i = 0; i < n; i++) {
            zombie.core.skinnedmodel.animation.AnimationPlayer p = q[i].getAnimationPlayer();
            if (p != null) {
               p.pzoptInFlight = false; // AnimBatch.flush sets it again for the players whose bones it queues
            }
         }
         ActionEval.finishLate();
      } finally {
         latchedFps = Float.NaN;
         try {
            AnimBatch.flush();
         } finally {
            ZombTiming.end(ZombTiming.X4, zt);
         }
      }
   }

   /** Game thread, MovingObjectUpdateScheduler.postupdate: the apply was held, so the bone flush waits for it too. */
   public static boolean holdsBoneFlush() {
      gameThread = Thread.currentThread();
      return held;
   }

   /**
    * Game thread, IsoWorld.FinishAnimation: the held apply runs, then the bone batch is joined (stock order) or, with
    * zombieDrawLate, left in flight on the background lane through the render.
    */
   public static void finishAnimation() {
      gameThread = Thread.currentThread();
      if (held) {
         finishes++;
         finish();
      }
      if (!AnimBatch.drawLate()) {
         AnimBatch.join();
      } else {
         drawFrames++;
         drawPhase = AnimBatch.pendingBackground(); // from here to the next frame's start the render reads the draw copies
      }
   }

   /** Game thread, the top of the next frame's update: the late-drawn bone batch is landed before the simulation reads bones. */
   public static void frameStart() {
      WorkerRand.ensure(); // randPerWorker (f8-zomb3): the game's generator wrapped before the frame's batches
      drawPhase = false;
      if (held) {
         touch(R_BEGIN);
      }
      if (AnimBatch.pendingBackground()) {
         long t0 = System.nanoTime();
         drawJoinsFrame++;
         AnimBatch.join();
         long dt = System.nanoTime() - t0;
         drawJoinNanos += dt;
         ZombTiming.add(ZombTiming.X5, dt);
      }
   }

   /**
    * Game thread, an AnimationPlayer accessor of a player in flight: true when the render may read this player's draw
    * copy instead of joining the bone batch (zombieDrawLate, the batch on the background lane, a copy taken).
    */
   public static boolean serving(zombie.core.skinnedmodel.animation.AnimationPlayer p) {
      if (!drawPhase || !p.pzoptDrawValid || !AnimBatch.pendingBackground()) {
         return false;
      }
      Thread t = Thread.currentThread();
      if (t instanceof FrameBatch.Worker w ? w.bgTask : t == gameThread && FrameBatch.gameThreadInBackgroundTask()) {
         return false; // the bone math itself (a background task) reads and writes the live arrays
      }
      drawServed++; // stats only (the characters-draw pool reads it too)
      return true;
   }

   public static long drawPaletteMisses, drawSkinComputes; // stats only

   /** One line for the periodic log. */
   public static String describe() {
      return "zombie late: holds=" + holds + " finishes=" + finishes + " touches=" + touches + " (state " + touchState + " remove " + touchRemove
            + " model " + touchModel + " anim " + touchAnim + " begin " + touchBegin + " other " + touchOther + " reuse " + touchReuse + " inTask " + touchInTask + ") draw frames=" + drawFrames
            + " served=" + drawServed + " guardJoins=" + drawJoinsGuard + " frameJoins=" + drawJoinsFrame + " frameJoin ms=" + (drawJoinNanos / 1_000_000L)
            + " snapshots=" + AnimBatch.snapshots + " paletteMisses=" + drawPaletteMisses + " skinComputes=" + drawSkinComputes + " bgJoin ms=" + (FrameBatch.backgroundJoinNanos / 1_000_000L) + " bgHelped=" + FrameBatch.backgroundHelped + " randPerWorker installs=" + WorkerRand.installs + " | " + AnimalLosSkip.describe();
   }
}
