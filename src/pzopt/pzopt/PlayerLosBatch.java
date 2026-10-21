package pzopt;

import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerArray;
import zombie.ai.states.ClimbThroughWindowState;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.core.PerformanceSettings;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoPhysicsObject;
import zombie.iso.IsoUtils;
import zombie.iso.LightingJNI;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;

/**
 * The player's line-of-sight pass with its per-object reads on the frame workers ({@code playerLosParallel}, f9-los,
 * 2026-10-15).
 *
 * <p>{@code IsoPlayer.updateLOS} walks every moving object of the cell (~2,500 in the Louisville horde) once per frame. For
 * each it computes the distance to the player, reads the could-see / can-see bits of the object's square (a lazy lighting
 * refresh when the square's light changed this pass), asks a zombie whether it is climbing through a window (its head
 * square then counts), and then applies the result: the object's target alpha, the zombie's spot test, the player's
 * counters and the spotted list. On the flip that is ~1.1-1.2 ms of a 13 ms frame, mostly memory reads of objects and
 * squares the walk touches once.
 *
 * <p>Split: the game thread walks the object set into an array and hands it to the workers in chunks of {@link #PER_TASK}
 * (an asynchronous {@link FrameBatch} batch). A worker computes per object what the stock loop would read before any side
 * effect: the class filters, the distance (the same float expression), the square, and the visibility bits through
 * {@code JNILighting.pzoptPeekVis} — only when reading them would not refresh the square (read already in this lighting
 * pass, or the native says unchanged: the native getter is a pure read). Then the game thread runs the stock loop body in
 * the set's order with those answers, waiting per chunk (and running an unclaimed chunk itself).
 *
 * <p>An object keeps the stock reads at its place (flag {@link #SERIAL}) when its square's light would be refreshed by
 * the read (the refresh has side effects: level invalidation, room-seen hooks), when it is a zombie climbing through a
 * window that could not or cannot be seen from its square (the head square: an animation-player read that may replace the
 * player), a corpse reanimated for grappling (reads its grappler's target alpha), or another player (the
 * hide-players-behind-you rule). Nothing the game thread does in the loop changes an input of a later object's reads:
 * the loop writes target alphas, the spot test writes the spotting zombie and the player, a refresh writes only its own
 * square, and the set cannot change (stock would throw).
 *
 * <p>A zombie that could not and cannot be seen ends in stock's "not visible" branch with nothing but
 * {@code setTargetAlpha(player, 0)} (no spot test without could-see; its alpha snap needs a target of 1): the worker writes
 * that target alpha itself and marks it {@link #DONE}, so the game thread never touches it. The only stock reader of
 * another object's target alpha inside the loop is a grapple-only corpse reading its grappler's: {@link #grapplerAlpha}
 * answers with the value from before the worker's write when the grappler comes later in the loop.
 *
 * <p>Single player, the native lighting's FBO render path, no entity flight or other asynchronous batch pending. Anything
 * a task throws turns the rest of its chunk into {@link #SERIAL} and the pass off for the session.
 */
public final class PlayerLosBatch {
   private PlayerLosBatch() {
   }

   public static final int SKIP = 1; // a physics object or a vehicle: stock skips it
   public static final int DONE = 4; // nothing left for the game thread (no square, or the early target alpha)
   public static final int SERIAL = 8; // the game thread reads it the stock way at its place
   public static final int COULD = 16; // could-see, from the square
   public static final int CAN = 32; // can-see, from the square
   public static final int EARLY = 64; // with DONE: the worker set the target alpha to 0 (oldAlpha holds the value before)

   private static final int PER_TASK = 64;
   public static IsoMovingObject[] objs = new IsoMovingObject[4096];
   public static IsoGridSquare[] squares = new IsoGridSquare[4096];
   public static float[] dist = new float[4096];
   public static byte[] flags = new byte[4096];
   private static float[] oldAlpha = new float[4096];
   private static int[] peekAt = new int[4096]; // devPlayerLosCheck: the peek's answer and the square's tick when the worker read it
   private static int[] tickAt = new int[4096];
   private static AtomicIntegerArray doneStamp = new AtomicIntegerArray(4096 / PER_TASK + 1);
   private static int[] notDirty = new int[4096 / PER_TASK + 1];
   private static volatile int n; // the walk's final count, written before the seal
   private static int stamp;
   private static int playerIndex;
   private static float locX, locY;
   private static boolean earlyAlpha;
   private static Object token;
   private static volatile Throwable failure;
   private static boolean failed;
   public static long frames, objects, serial, early, helped, parks, waitNanos, checks, mismatches;

   /** Game thread, updateLOS: this frame's pass may take the parallel path. */
   public static boolean enabled() {
      return Config.PLAYER_LOS_PARALLEL && !failed && Overrides.enabled() && ZombAb.on(ZombAb.LOS_PARALLEL) && !GameServer.server
            && !GameClient.client && PerformanceSettings.fboRenderChunk && Config.effectiveWorkers() > 1 && !FrameBatch.hasPending()
            && !UpdateBatch.hasPendingBatch() && !FrameBatch.gameThreadInTask() && !(Thread.currentThread() instanceof FrameBatch.Worker);
   }

   /** Game thread: walk the set into the array, streaming chunks to the workers; returns the object count. */
   public static int start(Set<IsoMovingObject> set, int pIndex, float x, float y, boolean seeEveryone) {
      int cap = set.size() + PER_TASK;
      if (objs.length < cap) {
         objs = new IsoMovingObject[cap + 1024];
         squares = new IsoGridSquare[cap + 1024];
         dist = new float[cap + 1024];
         flags = new byte[cap + 1024];
         oldAlpha = new float[cap + 1024];
         peekAt = new int[cap + 1024];
         tickAt = new int[cap + 1024];
         doneStamp = new AtomicIntegerArray((cap + 1024) / PER_TASK + 1);
         notDirty = new int[(cap + 1024) / PER_TASK + 1];
      }
      stamp++;
      playerIndex = pIndex;
      locX = x;
      locY = y;
      earlyAlpha = !seeEveryone;
      int k = 0;
      for (IsoMovingObject o : set) {
         objs[k++] = o;
      }
      n = k;
      // the whole walk first, then every chunk at once: a stream (chunks published during the walk) kept eleven workers
      // spinning for unpublished chunks through the walk, +3-7 ms of CPU a frame on the power-limited flip
      FrameBatch.runAsync((k + PER_TASK - 1) / PER_TASK, PlayerLosBatch::task, PlayerLosBatch::joined);
      token = FrameBatch.pendingBatch();
      frames++;
      objects += k;
      return k;
   }

   /** Game thread, before object {@code i} (a chunk's first): wait for its chunk, running unclaimed ones meanwhile. */
   public static void await(int i) {
      int t = i / PER_TASK;
      if (doneStamp.get(t) == stamp) {
         return;
      }
      long t0 = System.nanoTime();
      // joinSpinPark (f11-mac): after ~200 spins the wait parks in 10 us slices like the other joins (ShortWait); spinning on
      // kept the game thread on a core while the worker that holds the chunk waited for one under outside load
      boolean park = Config.JOIN_SPIN_PARK && ZombAb.on(ZombAb.SPIN_PARK);
      int spins = 0;
      while (doneStamp.get(t) != stamp) {
         if (FrameBatch.isPending(token) && FrameBatch.helpOne()) {
            helped++;
         } else if (!park || ++spins < 200) {
            Thread.onSpinWait();
         } else {
            ShortWait.pause(10_000L);
            parks++;
         }
      }
      waitNanos += System.nanoTime() - t0;
   }

   /** Game thread, after the loop (also when it threw): the stream joined, the arrays cleared, the notDirty reads added. */
   public static void finish(int count) {
      if (FrameBatch.isPending(token)) {
         FrameBatch.join();
      }
      token = null;
      int sum = 0;
      for (int t = 0, tasks = (count + PER_TASK - 1) / PER_TASK; t < tasks; t++) {
         sum += notDirty[t];
      }
      LightingJNI.JNILighting.pzoptNotDirty(sum);
      java.util.Arrays.fill(objs, 0, count, null);
      java.util.Arrays.fill(squares, 0, count, null);
      Throwable f = failure;
      if (f != null && !failed) {
         failed = true;
         Log.warn("playerLosParallel: a task failed, the stock loop from now on: " + f);
      }
   }

   private static void joined(Throwable f) {
      if (f != null && failure == null) {
         failure = f;
      }
   }

   private static void task(int t) {
      int from = t * PER_TASK;
      int to = from + PER_TASK;
      if (to > n) {
         to = n;
      }
      int nd = 0;
      int i = from;
      try {
         for (; i < to; i++) {
            flags[i] = SERIAL;
            nd += classify(i);
         }
      } catch (Throwable e) {
         for (; i < to; i++) {
            flags[i] = SERIAL;
         }
         if (failure == null) {
            failure = e;
         }
      } finally {
         notDirty[t] = nd;
         doneStamp.set(t, stamp);
      }
   }

   /** One object, on a worker: its flags and reads; returns the notDirty reads it stood in for. */
   private static int classify(int i) {
      IsoMovingObject o = objs[i];
      if (o instanceof IsoPhysicsObject || o instanceof BaseVehicle) {
         flags[i] = SKIP;
         return 0;
      }
      if (o instanceof IsoPlayer && !(o instanceof IsoAnimal)) {
         flags[i] = SERIAL; // the player itself (SELF, decided by the game thread) or another player
         return 0;
      }
      dist[i] = IsoUtils.DistanceTo(o.getX(), o.getY(), locX, locY);
      IsoGridSquare sq = o.getCurrentSquare();
      if (sq == null) {
         flags[i] = DONE; // stock: only the unused "close" count
         return 0;
      }
      squares[i] = sq;
      IsoZombie z = o instanceof IsoZombie zz ? zz : null;
      if (z != null && z.isReanimatedForGrappleOnly()) {
         return 0; // SERIAL
      }
      IsoGridSquare.ILighting lighting = sq.lighting[playerIndex];
      if (!(lighting instanceof LightingJNI.JNILighting jl)) {
         return 0;
      }
      if (Config.DEV_PLAYER_LOS_CHECK) {
         tickAt[i] = jl.pzoptTick();
      }
      int vis = jl.pzoptPeekVis();
      if (Config.DEV_PLAYER_LOS_CHECK) {
         peekAt[i] = vis;
      }
      if (vis < 0) {
         return 0; // a read would refresh the square
      }
      boolean couldSee = (vis & 4) != 0;
      boolean canSee = (vis & 2) != 0;
      if (z != null && (!couldSee || !canSee) && z.isCurrentState(ClimbThroughWindowState.instance())) {
         return 0; // the head square may count
      }
      int nd = (vis & 8) != 0 ? 2 : 0; // stock's isCouldSee and isCanSee each ask the native once
      if (z != null && !couldSee && !canSee && earlyAlpha) {
         oldAlpha[i] = z.getTargetAlpha(playerIndex);
         z.setTargetAlpha(playerIndex, 0.0F);
         flags[i] = DONE | EARLY;
         return nd;
      }
      flags[i] = (byte)((couldSee ? COULD : 0) | (canSee ? CAN : 0));
      return nd;
   }

   /**
    * Game thread, a grapple-only corpse at loop index {@code i}: {@code grappledBy.getTargetAlpha()} as stock reads it there.
    * A grappler later in the loop whose target alpha a worker already set reads as its value from before that write.
    */
   public static float grapplerAlpha(IsoMovingObject grappledBy, int i, int count) {
      if (grappledBy instanceof IsoZombie && IsoPlayer.getPlayerIndex() == playerIndex) {
         for (int j = i + 1; j < count; j++) {
            if (objs[j] == grappledBy) {
               await(j);
               if ((flags[j] & EARLY) != 0) {
                  return oldAlpha[j];
               }
               break;
            }
         }
      }
      return grappledBy.getTargetAlpha();
   }

   public static void countSerial() {
      serial++;
   }

   public static void countEarly() {
      early++;
   }

   /** devPlayerLosCheck: one comparison of a worker answer with the stock read at the object's place. */
   public static void check(boolean same, IsoMovingObject o, String what) {
      check(same, -1, o, what);
   }

   /** devPlayerLosCheck, object {@code i}: on a mismatch the log line carries what the worker saw and what the square says now. */
   public static void check(boolean same, int i, IsoMovingObject o, String what) {
      checks++;
      if (!same) {
         mismatches++;
         if (mismatches <= 20) {
            Log.warn("playerLosParallel mismatch: " + o.getClass().getSimpleName() + " id " + o.getID() + " " + what + (i >= 0 ? diag(i, o) : ""));
         }
      }
   }

   private static String diag(int i, IsoMovingObject o) {
      IsoGridSquare sq = o.getCurrentSquare();
      StringBuilder b = new StringBuilder();
      b.append(" | worker: peek ").append(peekAt[i]).append(" tick ").append(tickAt[i]).append(" flags ").append(flags[i]);
      b.append(" | now: square same ").append(sq == squares[i]).append(" counter ").append(LightingJNI.getUpdateCounter(playerIndex));
      if (sq != null && sq.lighting[playerIndex] instanceof LightingJNI.JNILighting jl) {
         b.append(" tick ").append(jl.pzoptTick()).append(" dirty ").append(LightingJNI.getSquareDirty(playerIndex, sq.x, sq.y, sq.z + 32));
      }
      if (o instanceof IsoZombie z) {
         b.append(" climbing ").append(z.isCurrentState(ClimbThroughWindowState.instance()));
      }
      b.append(" frame ").append(zombie.iso.IsoWorld.instance.getFrameNo());
      return b.toString();
   }

   public static String describe() {
      return "playerLos frames=" + frames + " objects=" + objects + " serial=" + serial + " early=" + early + " helped=" + helped + " parks=" + parks
            + " wait ms=" + waitNanos / 1_000_000L + (Config.DEV_PLAYER_LOS_CHECK ? " checks=" + checks + " mismatches=" + mismatches : "")
            + (failed ? " FAILED" : "");
   }
}
