package pzopt;

import zombie.VirtualZombieManager;
import zombie.ai.State;
import zombie.ai.states.ClimbOverFenceState;
import zombie.ai.states.ClimbOverWallState;
import zombie.ai.states.ClimbThroughWindowState;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoZombie;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;

/**
 * The zombies' postupdate movement on the frame workers ({@code postupdateParallel}, Louisville 120 plan B, 2026-10-06).
 *
 * <p>Stock {@code IsoMovingObject.postupdate} moves one zombie after another on the game thread: impulse and the one-tile
 * clamp, the vehicle resolution, {@code DoCollide} against the squares' collision data, stairs and slopes, the new square.
 * For a calm zombie almost all of that reads the static world and writes the zombie's own fields, so before the bucket
 * loop this batch runs that same stock body for every eligible zombie of the frame on the {@link FrameBatch} workers, and
 * the loop, at the zombie's own place, only commits what touches shared state: the move between the squares'
 * moving-object lists ({@code setMovingSquare}, deferred here).
 *
 * <p><b>Speculative, exact or stock.</b> Each task saves the zombie's movement fields ({@link Snap}) and runs the body. Every
 * point where stock would touch shared state or call out of the zombie (a special object's collide hook and its Lua event,
 * the fence climb / thump target after a collision, tree noises and the foliage push, the virtualisation at the loaded
 * area's edge, a vehicle within reach of the vehicle resolution) calls {@link #hazard}, which on a worker throws a
 * stackless {@link Bail}: the task restores the saved fields and the loop runs the stock body inline at the zombie's place.
 * The vehicle resolution itself is skipped on a worker when no vehicle is near: with no obstacle in its rectangle it returns
 * its input (CollideWithObstacles: no edge, no closest point).
 *
 * <p>What differs from stock: a computed zombie moves against the world as it is before the loop instead of after the
 * entities ahead of it in the bucket. Zombie-zombie pushing is in the update ({@code separate}); what the loop can change
 * under a waiting zombie is a door or window broken by another zombie's anim event, a vehicle's contact, a hit. The rig
 * ({@code devPostupdateCheck}) recomputes a sample through the stock body at the zombie's place and counts differences.
 */
public final class PostupdateBatch {
   private PostupdateBatch() {
   }

   /** The movement fields a postupdate writes (IsoMovingObject fills and restores them). */
   public static final class Snap {
      public float x, y, z, nx, ny, lx, ly, lz, impulsex, impulsey;
      public zombie.iso.IsoGridSquare current, last, square;
      public boolean collidedN, collidedS, collidedW, collidedE, collidedThisFrame, collidedWithDoor, collidedWithVehicle, altCollide, firstUpdate;
      public zombie.iso.IsoObject collidedObject;
      public String collideType;
      public int timeSinceZombieAttack;
      public IsoZombie lastTargettedBy;
      // postupdateClean (f8-cleanfix): what IsoZombie.pzoptCleanPostupdate's tail changes, for pzoptCleanUndo
      public float cleanUnbalanced, cleanSeenTime;
      public boolean cleanCanSee, cleanHeadPending;
   }

   /** The stackless throwable of a hazard on a worker. */
   static final class Bail extends RuntimeException {
      Bail() {
         super("postupdate hazard", null, false, false);
      }
   }

   private static final Bail BAIL = new Bail();
   private static final boolean ENABLED_KEY = Config.POSTUPDATE_PARALLEL;
   private static final boolean CHECK = Config.DEV_POSTUPDATE_CHECK;
   private static final int CHECK_EVERY = 7;
   private static final float VEHICLE_MARGIN = 0.75F; // squares added round a vehicle's polygon (a moving vehicle's step this frame, the garage radius reduction ignored)

   private static volatile boolean active; // a batch is on the workers now
   private static volatile boolean failed;
   private static long frame = 1L; // the stamp of this frame's computed zombies (IsoMovingObject.pzoptMoveFrame)

   private static IsoZombie[] queue = new IsoZombie[512];
   private static int count;
   private static float[] vehicles = new float[64];
   private static int vehicleCount;

   private static final Tl<boolean[]> COMPUTING = new Tl<>(() -> new boolean[1]);
   private static final Tl<zombie.iso.Vector2> TEMPO = new Tl<>(zombie.iso.Vector2::new);
   private static final Tl<org.joml.Vector2f> VEC2F = new Tl<>(org.joml.Vector2f::new);

   // counters (written by one thread at a time per field, read for the stats line)
   private static long frames, computed, bailed, ineligible, notTaken;
   private static long scanNs, vehicleNs, runNs, pendingAtStart;
   private static final java.util.concurrent.atomic.AtomicLong failures = new java.util.concurrent.atomic.AtomicLong();
   private static final java.util.concurrent.atomic.AtomicLong bails = new java.util.concurrent.atomic.AtomicLong();
   private static long checked, checkDiffs;
   private static String firstDiff;

   /** Any thread: a movement batch is on the workers now (PolygonalMap2.resolveCollision's lock, zombie pass 2026-10-13). */
   public static boolean activeNow() {
      return active;
   }

   /** Any thread: inside a movement task (the game thread too, while it helps). */
   public static boolean computing() {
      return active && COMPUTING.get()[0];
   }

   // the reasons a task bails (counted per reason in the stats line)
   public static final int H_COLLIDE_WITH = 0, H_FENCE = 1, H_TREE = 2, H_SWAY = 3, H_VIRTUALIZE = 4, H_VEHICLE = 5, H_EXCEPTION = 6;
   private static final String[] H_NAMES = {"collideWith", "fence", "tree", "sway", "virtualize", "vehicle", "exception"};
   private static final java.util.concurrent.atomic.AtomicLongArray BAILS = new java.util.concurrent.atomic.AtomicLongArray(H_NAMES.length);

   /** A point where stock touches shared state: on a worker task, abandon the task (the zombie runs stock inline). */
   public static void hazard(int reason) {
      if (active && COMPUTING.get()[0]) {
         BAILS.incrementAndGet(reason);
         throw BAIL;
      }
   }

   /** This thread's scratch for IsoMovingObject's static tempo vector (tasks only). */
   public static zombie.iso.Vector2 tempo() {
      return TEMPO.get();
   }

   /**
    * In a task, instead of PolygonalMap2.resolveCollision: (nx, ny) unchanged when no vehicle is within reach (what the
    * resolution returns with no obstacle), else a hazard.
    */
   public static org.joml.Vector2f vehicleFree(IsoGameCharacter chr, float nx, float ny) {
      // CollideWithObstacles.getObstaclesInRect: a vehicle is an obstacle when its polygon's bounds intersect the move's
      // bounds grown by one square; the bounds here are that polygon's corners plus VEHICLE_MARGIN (taken before the loop)
      float x = chr.getX(), y = chr.getY();
      float x1 = Math.min(x, nx) - 1.0F, y1 = Math.min(y, ny) - 1.0F, x2 = Math.max(x, nx) + 1.0F, y2 = Math.max(y, ny) + 1.0F;
      float[] v = vehicles;
      for (int i = 0, n = vehicleCount * 4; i < n; i += 4) {
         if (v[i] <= x2 && v[i + 2] >= x1 && v[i + 1] <= y2 && v[i + 3] >= y1) {
            if (vehiclesOnWorker) {
               // postupdateVehicleOnWorker (f8-zomb2): stock's resolution here, under PolygonalMap2's CollideWithObstacles lock (taken
               // while this batch is active); the vehicles do not move in the postupdate and their polygons were brought up to date
               // on the game thread before the batch, so it answers what it answers at the zombie's place in the loop
               vehicleResolved.incrementAndGet();
               return zombie.pathfind.PolygonalMap2.instance.resolveCollision(chr, nx, ny, VEC2F.get());
            }
            BAILS.incrementAndGet(H_VEHICLE);
            throw BAIL;
         }
      }
      return VEC2F.get().set(nx, ny);
   }

   /** IsoMovingObject.setMovingSquare in a task: latched for the commit. */
   public static boolean deferMove(IsoMovingObject o, IsoGridSquare sq) {
      if (active && COMPUTING.get()[0]) {
         o.pzoptMoveSq = sq;
         // postupdateNoopSkip (zombie redesign, f8-zomb2): the stock call with the zombie's own square, already in that square's
         // list, changes nothing; only this zombie adds or removes itself there, so it is still a no-op at its commit
         o.pzoptMoveSqSet = !(NOOP_SKIP && sq != null && sq == o.getMovingSquare() && ZombAb.on(ZombAb.PU_NOOP) && sq.getMovingObjects().contains(o));
         return true;
      }
      return false;
   }

   private static final boolean NOOP_SKIP = Config.POSTUPDATE_NOOP_SKIP;

   private static boolean enabled() {
      return ENABLED_KEY && !failed && Overrides.enabled() && GtAb.on(GtAb.POSTUPDATE) && !GameClient.client && !GameServer.server
         && FrameBatch.THREADS > 1;
   }

   /**
    * Game thread, the top of the scheduler's postupdate: the eligible zombies of this frame's buckets moved on the workers.
    */
   public static void prepass(zombie.MovingObjectUpdateSchedulerUpdateBucket[] levels, int frameCounter) {
      count = 0;
      cursor = 0; // postupdateClean
      cleanFrame = false;
      postStamp++; // postupdateClean: every postupdate, enabled or not, so a clean mark never outlives its frame
      if (!enabled()) {
         return;
      }
      frame++;
      frames++;
      long t0 = System.nanoTime();
      for (zombie.MovingObjectUpdateSchedulerUpdateBucket level : levels) {
         java.util.List<IsoMovingObject> list = level.getBucket(frameCounter);
         for (int i = 0, n = list.size(); i < n; i++) {
            if (list.get(i) instanceof IsoZombie z) {
               if (ELIGIBLE_ON_WORKER && ZombAb.on(ZombAb.EXTRA1) || eligible(z)) { // postupdateEligibleOnWorker (f8-zomb2): the task tests it
                  if (count == queue.length) {
                     queue = java.util.Arrays.copyOf(queue, count * 2);
                  }
                  queue[count++] = z;
               } else {
                  ineligible++;
               }
            }
         }
      }
      if (count == 0) {
         return;
      }
      if (reached.length < count) {
         reached = new boolean[Math.max(count, reached.length * 2)];
      } else {
         java.util.Arrays.fill(reached, 0, count, false);
      }
      if (SKIP_CHECK) {
         skipBefore();
      }
      long t1 = System.nanoTime();
      vehiclesOnWorker = VEHICLE_ON_WORKER && ZombAb.on(ZombAb.EXTRA2);
      snapshotVehicles();
      long t2 = System.nanoTime();
      if (FrameBatch.hasPending()) {
         pendingAtStart++; // run() joins an asynchronous batch first
      }
      long b0 = bails.get();
      boolean big = count >= Config.POSTUPDATE_STREAM_MIN; // a handful of zombies (a drive): the stream's fixed cost (a wake, a join) is more than it saves
      ActionEval.allowStream(big);
      cleanFrame = big && CLEAN && ZombAb.on(ZombAb.CLEAN) && NOOP_SKIP && ZombAb.on(ZombAb.PU_NOOP);
      if (cleanFrame && clean.length < count) {
         clean = new byte[Math.max(count, clean.length * 2)];
      }
      active = true;
      Throwable t;
      try {
         t = FrameBatch.run(count, PostupdateBatch::compute);
      } finally {
         active = false;
      }
      long t3 = System.nanoTime();
      scanNs += t1 - t0;
      vehicleNs += t2 - t1;
      runNs += t3 - t2;
      if (t != null && !failed) {
         failed = true; // compute() catches what the body throws; this is the batch machinery itself
         Log.warn("postupdateParallel: the batch failed, off for the session: " + t);
      }
      if (EARLY && cleanFrame && t == null) { // devCleanEarly: the pre-f8-cleanfix shape (see cleanAt / finish for why it is gone)
         for (int i = 0; i < count; i++) {
            if (clean[i] == 2) {
               ActionEval.publishCleanEarly(queue[i]); // postupdateClean: its evaluation runs under the whole bucket loop
            }
         }
      }
      long nb = bails.get() - b0;
      bailed += nb;
      computed += count - nb;
   }

   private static boolean eligible(IsoZombie z) {
      if (VirtualZombieManager.instance.isReused(z) || z.isDead() || z.isDestroyed() || z.getCurrentSquare() == null || z.getVehicle() != null
         || z.isBeingGrappled() || z.isGrappling() || z.getReanimatedPlayer() != null || !z.pzoptAnimPlayerSettled()) {
         return false;
      }
      State s = z.getCurrentState();
      return s != ClimbOverFenceState.instance() && s != ClimbThroughWindowState.instance() && s != ClimbOverWallState.instance();
   }

   // per vehicle: x, y, z it was measured at, then the bounds (minX, minY, maxX, maxY)
   private static final java.util.IdentityHashMap<BaseVehicle, float[]> BOUNDS = new java.util.IdentityHashMap<>();
   private static final org.joml.Vector3f CORNER = new org.joml.Vector3f();
   private static long boundsFrame;

   /**
    * The bounds of every vehicle's polygon-plus-radius, from the same corners VehiclePoly.init takes (the script's extents
    * round the centre-of-mass offset, through the world transform), without touching the vehicle's own polygon cache;
    * re-measured when the vehicle's position changed.
    */
   private static void snapshotVehicles() {
      java.util.Collection<BaseVehicle> list = IsoWorld.instance.currentCell.getVehicles();
      int n = list.size();
      if (vehicles.length < n * 4) {
         vehicles = new float[n * 4 + 32];
      }
      if (++boundsFrame % 600 == 0) {
         BOUNDS.keySet().retainAll(new java.util.HashSet<>(list)); // drop vehicles that left the cell
      }
      int i = 0;
      for (BaseVehicle v : list) {
         if (i == n) {
            break;
         }
         zombie.scripting.objects.VehicleScript script = v.getScript();
         if (script == null) {
            continue; // getVehiclesInRect skips it too
         }
         if (vehiclesOnWorker) {
            v.getPoly(); // postupdateVehicleOnWorker: the lazy polygon init (shared scratch) here, the workers only read
         }
         float[] b = BOUNDS.get(v);
         if (b == null || b[0] != v.getX() || b[1] != v.getY() || b[2] != v.getZ()) {
            if (b == null) {
               BOUNDS.put(v, b = new float[7]);
            }
            b[0] = v.getX();
            b[1] = v.getY();
            b[2] = v.getZ();
            org.joml.Vector3f ext = script.getExtents();
            org.joml.Vector3f com = script.getCenterOfMassOffset();
            float hw = ext.x / 2.0F + 0.15F, hl = ext.z / 2.0F + 0.15F;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int c = 0; c < 4; c++) {
               v.getWorldPos(com.x + ((c & 1) == 0 ? -hw : hw), 0.0F, com.z + ((c & 2) == 0 ? -hl : hl), CORNER);
               minX = Math.min(minX, CORNER.x);
               minY = Math.min(minY, CORNER.y);
               maxX = Math.max(maxX, CORNER.x);
               maxY = Math.max(maxY, CORNER.y);
            }
            b[3] = minX - VEHICLE_MARGIN;
            b[4] = minY - VEHICLE_MARGIN;
            b[5] = maxX + VEHICLE_MARGIN;
            b[6] = maxY + VEHICLE_MARGIN;
         }
         vehicles[i * 4] = b[3];
         vehicles[i * 4 + 1] = b[4];
         vehicles[i * 4 + 2] = b[5];
         vehicles[i * 4 + 3] = b[6];
         i++;
      }
      vehicleCount = i;
   }

   private static final boolean ELIGIBLE_ON_WORKER = Config.POSTUPDATE_ELIGIBLE_ON_WORKER;

   // ---- postupdateClean (zombie redesign pass, 2026-10-13, f8-zomb2) ----
   // A computed zombie whose square move is a no-op and whose whole remaining postupdate needs nothing of the game thread
   // (IsoZombie.pzoptCleanPostupdate: no target, no hit infos, states without side-effecting condition variables, the head on
   // the evaluation task) finishes its postupdate in its movement task. Its flag (1 skip, 2 skip and join ActionEval's queue)
   // sits in a dense array in queue order, and the bucket loop, which walks the same lists in the same order, compares the
   // entry with the queue by reference: a clean zombie's slot costs the loop no read of the zombie at all.
   private static final boolean CLEAN = Config.POSTUPDATE_CLEAN;
   private static byte[] clean = new byte[1024];
   private static boolean cleanFrame; // game thread
   private static int cursor; // game thread: the bucket loop's position in the queue
   private static final java.util.concurrent.atomic.AtomicLong cleanCount = new java.util.concurrent.atomic.AtomicLong();
   private static long cleanSkipped, cleanResync;

   /** IsoZombie.postupdate: this frame's movement-queue stamp (a clean zombie carries it in pzoptCleanFrame). */
   public static long cleanGuarded; // IsoZombie.postupdate reached for a zombie already finished in its task

   public static long frameStamp() {
      return postStamp;
   }

   private static volatile long postStamp = 1L;

   /**
    * Game thread, the bucket postupdate loop, first thing per entity: -1 the entity is not the next queued zombie (the loop
    * runs it as before), 0 it is and runs as before, 1 its postupdate is done (skip it), 2 done, and it joins ActionEval's
    * queue at this place (appendClean).
    */
   public static int cleanAt(IsoMovingObject o) {
      if (cursor >= count) {
         return -1;
      }
      if (queue[cursor] != o) {
         // an entity removed from a bucket list during the loop shifts the list under it: find the entry a few slots on
         int k = cursor + 1;
         int end = Math.min(count, cursor + 8);
         while (k < end && queue[k] != o) {
            k++;
         }
         if (k >= end) {
            return -1;
         }
         cleanResync++;
         cursor = k;
      }
      int k = cursor++;
      reached[k] = true;
      if (!cleanFrame) {
         return 0;
      }
      int f = clean[k];
      if (f != 0) {
         cleanSkipped++;
         if (f == 2) {
            ActionEval.appendClean(queue[k]);
         }
      }
      return f;
   }

   // ---- postupdateClean exactness (f8-cleanfix, 2026-10-13) ----
   // Stock's bucket loop (`for i < size: list.get(i).postupdate()`) skips the entry right after an entity that removes itself
   // during its postupdate (IsoZombie.postUpdateInternal: no current square -> removeFromWorld -> the scheduler's removeObject
   // shifts the list under the loop), and an entity removed by another one before the loop reaches it is never run either: stock
   // runs NO postupdate for such a zombie that frame (no movement, no tail, no head, no evaluation, no animator). A clean zombie
   // has done its movement and tail in its movement task before the loop, and its evaluation (with postupdateFuse its context
   // update and animator) used to be published before the loop too (publishCleanEarly): ~95 zombies a run were moved, evaluated
   // and animated in a frame where stock leaves them alone. Now: the evaluation goes out when the loop reaches the zombie
   // (ActionEval.appendClean), the loop's visit is recorded per queue entry (`reached`: cleanAt, or IsoZombie.postupdate when the
   // cursor lost track), and finish() puts back the movement and the tail of every clean zombie the loop never reached, as it
   // already did for the other computed zombies (pzoptMoveFrame).
   private static final boolean EARLY = Config.DEV_CLEAN_EARLY;
   private static boolean[] reached = new boolean[1024]; // game thread: the bucket loop reached queue entry k this frame
   private static long cleanUnreached, cleanUndone;

   /** Game thread, IsoZombie.postupdate (the loop reached the zombie): its queue entry, when it has one this frame, is marked. */
   public static void reachedAt(IsoZombie z) {
      int k = z.pzoptCleanIdx;
      if (k >= 0 && k < count && queue[k] == z) {
         reached[k] = true;
      }
   }

   /**
    * Game thread, IsoZombie.postupdate of a clean zombie the loop reached where cleanAt did not recognise it (more removals than its
    * look-ahead): what cleanAt does at its place, the queueing in ActionEval for an animating one.
    */
   public static void cleanGuard(IsoZombie z) {
      int k = z.pzoptCleanIdx;
      if (cleanFrame && k >= 0 && k < count && queue[k] == z && clean[k] == 2) {
         ActionEval.appendClean(z);
      }
   }
   private static final boolean VEHICLE_ON_WORKER = Config.POSTUPDATE_VEHICLE_ON_WORKER;
   private static volatile boolean vehiclesOnWorker;
   private static final java.util.concurrent.atomic.AtomicLong vehicleResolved = new java.util.concurrent.atomic.AtomicLong();
   private static final java.util.concurrent.atomic.AtomicLong ineligibleOnWorker = new java.util.concurrent.atomic.AtomicLong();

   private static void compute(int i) {
      IsoZombie z = queue[i];
      z.pzoptCleanIdx = i; // f8-cleanfix: reachedAt / cleanGuard find the entry from the zombie
      if (cleanFrame) {
         clean[i] = 0; // postupdateClean: this frame's flag only (a stale 1 from an earlier frame skipped a zombie that bailed here: zomb2-br-on5/6)
      }
      if (ELIGIBLE_ON_WORKER && !eligible(z)) { // postupdateEligibleOnWorker (f8-zomb2): the scan's test, here on the worker (pure reads; the
         ineligibleOnWorker.incrementAndGet(); // grid, the players and the reuse set do not change during the batch); the loop runs stock for it
         return;
      }
      Snap s = z.pzoptMoveSnap;
      if (s == null) {
         s = z.pzoptMoveSnap = new Snap();
      }
      z.pzoptMoveSave(s);
      z.pzoptMoveSq = null;
      z.pzoptMoveSqSet = false;
      boolean[] c = COMPUTING.get();
      c[0] = true;
      try {
         z.pzoptMovingPostupdate();
         z.pzoptMoveFrame = frame;
         if (cleanFrame) {
            byte f = 0;
            if (!z.pzoptMoveSqSet) { // the square move is a no-op (postupdateNoopSkip): nothing to commit in the loop
               f = z.pzoptCleanPostupdate();
               if (f != 0) {
                  z.pzoptMoveFrame = 0L; // its loop slot is skipped: take() never runs, finish() must not put it back
                  z.pzoptCleanFrame = postStamp; // IsoZombie.postupdate returns at once should the loop reach it anyway
                  cleanCount.incrementAndGet();
               }
            }
            clean[i] = f;
         }
      } catch (Bail b) {
         z.pzoptMoveRestore(s);
         bails.incrementAndGet();
      } catch (Throwable t) {
         z.pzoptMoveRestore(s); // the stock body runs inline and meets whatever this was itself
         bails.incrementAndGet();
         failures.incrementAndGet();
         BAILS.incrementAndGet(H_EXCEPTION);
         if (failures.get() <= 3) {
            Log.warn("postupdateParallel: a movement task threw (the zombie ran stock inline): " + t);
         }
      } finally {
         c[0] = false;
      }
   }

   /**
    * Game thread, the zombie's place in the bucket loop: true when its movement was computed this frame and is now
    * committed (the square move), false when the stock body must run inline.
    */
   public static boolean take(IsoGameCharacter chr) {
      if (chr.pzoptMoveFrame != frame || count == 0) {
         return false;
      }
      chr.pzoptMoveFrame = 0L;
      if (CHECK && (++checkTick % CHECK_EVERY) == 0) {
         check(chr);
         return true;
      }
      if (chr.pzoptMoveSqSet) {
         chr.setMovingSquare(chr.pzoptMoveSq);
         ZombTiming.add(ZombTiming.X3, 1L); // zombie redesign census: computed zombies whose square move is committed
      }
      return true;
   }

   private static int checkTick;
   private static final Snap RESULT = new Snap();

   /** devPostupdateCheck: the computed result kept aside, the stock body run from the saved start, the two compared. */
   private static void check(IsoGameCharacter chr) {
      IsoGridSquare sq = chr.pzoptMoveSq;
      chr.pzoptMoveSave(RESULT);
      chr.pzoptMoveRestore(chr.pzoptMoveSnap);
      chr.pzoptMovingPostupdate(); // stock, on the game thread (the zombie keeps the stock result)
      checked++;
      String d = chr.pzoptMoveDiff(RESULT);
      if (d == null && chr.pzoptMoveSqSet && chr.getMovingSquare() != sq) {
         d = "movingSq";
      }
      if (d != null) {
         checkDiffs++;
         if (firstDiff == null || checkDiffs <= 5) {
            firstDiff = d;
            Log.info("postupdateParallel check: difference in " + d + " for zombie " + chr.getID() + " at " + chr.getX() + "," + chr.getY());
         }
      }
   }

   /** Game thread, after the bucket loop: a computed zombie the loop never reached (removed meanwhile) is put back. */
   public static void finish() {
      if (SKIP_CHECK) {
         skipAfterLoop();
      }
      for (int i = 0; i < count; i++) {
         IsoZombie z = queue[i];
         if (z.pzoptMoveFrame == frame) {
            z.pzoptMoveFrame = 0L;
            z.pzoptMoveRestore(z.pzoptMoveSnap);
            notTaken++;
         } else if (cleanFrame && clean[i] != 0 && !reached[i]) {
            // f8-cleanfix: a clean zombie stock's loop never ran (the entry after a self-removal, or removed before its turn):
            // its movement and tail put back; its evaluation never went out (it goes out at the zombie's place in the loop)
            cleanUnreached++;
            if (!EARLY) {
               z.pzoptMoveRestore(z.pzoptMoveSnap);
               z.pzoptCleanUndo(z.pzoptMoveSnap);
               cleanUndone++;
            }
         }
         queue[i] = null;
      }
      count = 0;
   }

   // ---- devCleanSkipCheck (f8-cleanfix): every queued zombie the bucket loop did not reach, against stock (no postupdate) ----
   // Before the movement batch each queued zombie's movement fields (Snap) and the rest of what a postupdate writes
   // (IsoGameCharacter / IsoZombie.pzoptSkipPrint) are saved. finish() lists the entries the loop never reached; after the frame's
   // postupdate (ActionEval.flush, AnimBatch.flush) the pending stream is joined and each of them compared with its saved state:
   // stock never ran its postupdate, so every field must be as it was. Counted apart: zombies still in the cell's object list
   // (the skipped slot) and zombies removed during the loop (their removal writes fields of its own).
   private static final boolean SKIP_CHECK = Config.DEV_CLEAN_SKIP_CHECK;
   public static final int PRINT_CHAR = 8, PRINT = 11; // IsoGameCharacter's slots, then IsoZombie's
   private static final String[] PRINT_NAMES = {"forwardX", "forwardY", "turning", "headMarks", "evalDone", "actionState", "animTime", "animTracks",
      "unbalanced", "targetSeenTime", "canSeeTarget"};
   private static Snap[] skipSnap = new Snap[0];
   private static double[] skipPrint = new double[0];
   private static final double[] NOW = new double[PRINT];
   private static final Snap NOW_SNAP = new Snap();
   private static IsoZombie[] skipZ = new IsoZombie[16];
   private static int[] skipK = new int[16];
   private static byte[] skipFlag = new byte[16];
   private static int skipN;
   private static long skipChecked, skipDiffs, skipGoneChecked, skipGoneDiffs, skipCleanChecked;
   private static int skipLogged;

   private static void skipBefore() {
      if (skipSnap.length < count) {
         int n = Math.max(count, skipSnap.length * 2);
         Snap[] a = java.util.Arrays.copyOf(skipSnap, n);
         for (int i = skipSnap.length; i < n; i++) {
            a[i] = new Snap();
         }
         skipSnap = a;
         skipPrint = new double[n * PRINT];
      }
      for (int i = 0; i < count; i++) {
         queue[i].pzoptMoveSave(skipSnap[i]);
         queue[i].pzoptSkipPrint(skipPrint, i * PRINT);
      }
   }

   private static void skipAfterLoop() {
      skipN = 0;
      for (int i = 0; i < count; i++) {
         if (!reached[i]) {
            if (skipN == skipZ.length) {
               skipZ = java.util.Arrays.copyOf(skipZ, skipN * 2);
               skipK = java.util.Arrays.copyOf(skipK, skipN * 2);
               skipFlag = java.util.Arrays.copyOf(skipFlag, skipN * 2);
            }
            skipZ[skipN] = queue[i];
            skipK[skipN] = i;
            skipFlag[skipN] = cleanFrame ? clean[i] : 0;
            skipN++;
         }
      }
   }

   /** Game thread, the end of the scheduler's postupdate (after ActionEval.flush and AnimBatch.flush). */
   public static void skipCheck() {
      if (!SKIP_CHECK || skipN == 0) {
         return;
      }
      FrameBatch.join(); // an evaluation / animator task of an unreached zombie may still be out on the stream
      java.util.Collection<IsoMovingObject> objects = IsoWorld.instance.currentCell.getObjectList();
      for (int j = 0; j < skipN; j++) {
         IsoZombie z = skipZ[j];
         int k = skipK[j];
         boolean gone = !objects.contains(z);
         String d = z.pzoptMoveDiff(skipSnap[k]);
         if (d == null) {
            z.pzoptSkipPrint(NOW, 0);
            for (int f = 0; f < PRINT; f++) {
               if (Double.doubleToLongBits(NOW[f]) != Double.doubleToLongBits(skipPrint[k * PRINT + f])) {
                  d = PRINT_NAMES[f];
                  break;
               }
            }
         }
         if (skipFlag[j] != 0) {
            skipCleanChecked++;
         }
         if (gone) {
            skipGoneChecked++;
         } else {
            skipChecked++;
         }
         if (d != null) {
            if (gone) {
               skipGoneDiffs++;
            } else {
               skipDiffs++;
            }
            if (skipLogged < 12) {
               skipLogged++;
               Log.info("devCleanSkipCheck: zombie " + z.getID() + " not reached by the bucket loop (" + (gone ? "removed" : "skipped slot")
                  + ", clean flag " + skipFlag[j] + ") differs from its state before the postupdate in " + d);
            }
         }
         skipZ[j] = null;
      }
      skipN = 0;
   }

   private static String bailReasons() {
      StringBuilder b = new StringBuilder();
      for (int i = 0; i < H_NAMES.length; i++) {
         b.append(i == 0 ? "" : " ").append(H_NAMES[i]).append('=').append(BAILS.get(i));
      }
      return b.toString();
   }

   public static String describe() {
      return "postupdate batch: frames=" + frames + " computed=" + computed + " bailed=" + bailed + " failures=" + failures.get() + " ineligible=" + ineligible
         + " notTaken=" + notTaken + (ELIGIBLE_ON_WORKER ? " ineligibleOnWorker=" + ineligibleOnWorker.get() : "") + (VEHICLE_ON_WORKER ? " vehicleResolved=" + vehicleResolved.get() : "") + (CLEAN ? " clean=" + cleanCount.get() + " cleanSkipped=" + cleanSkipped + " cleanResync=" + cleanResync + " cleanGuarded=" + cleanGuarded + " cleanUnreached=" + cleanUnreached + " cleanUndone=" + cleanUndone : "") + " bails(" + bailReasons() + ")"
         + (frames > 0 ? String.format(" us/frame scan=%.1f vehicles=%.1f run=%.1f pendingAtStart=%d", scanNs / 1e3 / frames, vehicleNs / 1e3 / frames, runNs / 1e3 / frames, pendingAtStart) : "") + (SKIP_CHECK ? " skipCheck slot=" + skipChecked + " slotDiffs=" + skipDiffs + " removed=" + skipGoneChecked + " removedDiffs=" + skipGoneDiffs + " clean=" + skipCleanChecked : "")
         + (CHECK ? " checked=" + checked + " diffs=" + checkDiffs + (firstDiff != null ? " first=" + firstDiff : "") : "")
         + (failed ? " FAILED" : "");
   }
}
