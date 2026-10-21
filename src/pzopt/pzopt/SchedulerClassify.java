package pzopt;

import java.util.Set;
import zombie.MovingObjectUpdateScheduler;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.iso.IsoMovingObject;

/**
 * The update scheduler's per-object simulation level on the frame workers ({@code schedulerClassifyParallel},
 * 2026-09-27).
 *
 * <p>{@code MovingObjectUpdateScheduler.startFrame} walks every moving object of the cell (~2,500 on the Louisville
 * horde) and asks each for its simulation level: its minimum (dead, ragdolling, falling, the state machine's states), its
 * distance, level and alpha against every player, the frame rate. That is 3.5 % of the game thread there, and every input
 * is a read of state no one writes during the walk. So the walk is split: the game thread takes the set's objects in its
 * iteration order and does the loop's one write first ({@code setCurrentSquareFromPosition} for an object without a
 * square: the classification never reads another object's square), the frame workers classify, and the game thread
 * fills the buckets and the separation batch in that same order. A character whose animation player would be replaced
 * by the body-model check inside {@code getAnimationPlayer} (a write) is left to the game thread, as is everything after
 * a failed task.
 */
public final class SchedulerClassify {
   private SchedulerClassify() {
   }

   private static final int PER_TASK = 64;
   private static IsoMovingObject[] objs = new IsoMovingObject[4096];
   private static UpdateSchedulerSimulationLevel[] levels = new UpdateSchedulerSimulationLevel[4096];
   private static int n;
   private static float fps;
   private static MovingObjectUpdateScheduler scheduler;
   private static boolean failed;
   public static long frames, objects, serialObjects, checks, mismatches;
   public static int updatedThisFrame; // objects the buckets update this frame (the frame-mod test of the bucket), for pzopt-gtab.out

   public static boolean enabled() {
      return Config.SCHEDULER_CLASSIFY_PARALLEL && !failed && Overrides.enabled() && GtAb.on(GtAb.SCHED);
   }

   /** Game thread, startFrame after the buckets were cleared: the loop over the cell's objects. */
   public static void run(MovingObjectUpdateScheduler s, Set<IsoMovingObject> set, float averageFps) {
      if (fillEnabled()) {
         runFill(s, set, averageFps);
         return;
      }
      int count = set.size();
      if (objs.length < count) {
         objs = new IsoMovingObject[count + 1024];
         levels = new UpdateSchedulerSimulationLevel[count + 1024];
      }
      n = 0;
      for (IsoMovingObject o : set) {
         if (o.getCurrentSquare() == null) {
            o.setCurrentSquareFromPosition();
         }
         objs[n++] = o;
      }
      scheduler = s;
      fps = averageFps;
      frames++;
      objects += n;
      if (n >= PER_TASK * 2) {
         Throwable t = FrameBatch.run((n + PER_TASK - 1) / PER_TASK, SchedulerClassify::task);
         if (t != null) {
            failed = true;
            Log.warn("schedulerClassifyParallel: a task failed, the game thread classifies from now on: " + t);
            java.util.Arrays.fill(levels, 0, n, null);
         }
      } else {
         java.util.Arrays.fill(levels, 0, n, null);
      }
      if (Config.DEV_SCHED_CHECK) {
         for (int i = 0; i < n; i++) {
            if (levels[i] != null) {
               checks++;
               UpdateSchedulerSimulationLevel serial = s.pzoptClassifySerial(objs[i], averageFps);
               if (serial != levels[i]) {
                  mismatches++;
                  if (mismatches <= 20) {
                     Log.warn("schedulerClassify mismatch: " + objs[i].getClass().getSimpleName() + " id " + objs[i].getID() + " worker " + levels[i] + " game thread " + serial);
                  }
               }
            }
         }
      }
      updatedThisFrame = 0;
      long frameCounter = s.getFrameCounter();
      for (int i = 0; i < n; i++) {
         IsoMovingObject o = objs[i];
         UpdateSchedulerSimulationLevel sim = levels[i];
         if (sim == null) {
            serialObjects++;
            sim = s.pzoptClassifySerial(o, averageFps);
         }
         s.pzoptAdd(o, sim);
         if (o.getID() % sim.getFrameMod() == (int)(frameCounter % (long)sim.getFrameMod())) {
            updatedThisFrame++;
         }
         objs[i] = null;
         levels[i] = null;
      }
   }

   private static void task(int t) {
      int from = t * PER_TASK, to = Math.min(n, from + PER_TASK);
      for (int i = from; i < to; i++) {
         levels[i] = scheduler.pzoptClassify(objs[i], fps);
      }
   }

   // ---- schedulerFillParallel (f9-los, 2026-10-15): the rest of startFrame on the workers ----
   // The classification task also does the missing-square test (a field read per object); the square fix (a write) and the
   // classification of an object a worker may not classify stay on the game thread after the batch, in the set's order;
   // IsoZombie answers its minimum simulation level from the ecsLookupFast field; the task also makes the reads of the bucket add (the object's id modulo the level's
   // frame mod, player / vehicle), so the game thread's fill loop in the set's order touches only arrays and the buckets'
   // lists (bucket.pzoptAddAt), and the separation queue and the counters follow from the same flags.
   private static final int F_ZOMBIE = 1, F_DUE = 2, F_NO_SQUARE = 4, F_WORKER = 8, F_IN_PLACE = 16;
   private static byte[] fl = new byte[4096];
   private static short[] bucketIndex = new short[4096]; // getID() % frameMod (frameMod <= 16)
   private static volatile int streamN;
   private static long fillFrameCounter;
   private static volatile Throwable fillFailure;
   private static boolean fillFailed;
   public static long fillFrames, fillNoSquare, fillChecks, fillMismatches;

   private static final java.util.concurrent.atomic.AtomicLong minLevelChecks = new java.util.concurrent.atomic.AtomicLong();
   private static final java.util.concurrent.atomic.AtomicLong minLevelMismatches = new java.util.concurrent.atomic.AtomicLong();

   /** devSchedFillCheck, any thread: IsoZombie's memoised minimum simulation level against the stock component lookup. */
   public static void minLevelChecked(boolean same) {
      minLevelChecks.incrementAndGet();
      if (!same) {
         minLevelMismatches.incrementAndGet();
      }
   }

   private static boolean fillEnabled() {
      return Config.SCHEDULER_FILL_PARALLEL && !fillFailed && ZombAb.on(ZombAb.SCHED_FILL) && Config.effectiveWorkers() > 1
            && !FrameBatch.hasPending() && !UpdateBatch.hasPendingBatch() && !FrameBatch.gameThreadInTask();
   }

   private static void runFill(MovingObjectUpdateScheduler s, Set<IsoMovingObject> set, float averageFps) {
      int cap = set.size() + PER_TASK;
      if (objs.length < cap || fl.length < cap) {
         objs = new IsoMovingObject[cap + 1024];
         levels = new UpdateSchedulerSimulationLevel[cap + 1024];
         fl = new byte[cap + 1024];
         bucketIndex = new short[cap + 1024];
      }
      scheduler = s;
      fps = averageFps;
      fillFrameCounter = s.getFrameCounter();
      int k = 0;
      for (IsoMovingObject o : set) {
         objs[k++] = o; // no square test here: the task reads it (a field of every object, a cache miss each)
      }
      n = k;
      streamN = k;
      fillFailure = FrameBatch.run((k + PER_TASK - 1) / PER_TASK, SchedulerClassify::fillTask); // (a stream during the walk kept the workers spinning: +CPU, slower on the flip)
      frames++;
      fillFrames++;
      objects += n;
      if (fillFailure != null) {
         fillFailed = true;
         Log.warn("schedulerFillParallel: a task failed, the classification loop from now on: " + fillFailure);
         java.util.Arrays.fill(levels, 0, n, null);
         java.util.Arrays.fill(fl, 0, n, (byte)0);
      }
      for (int i = 0; i < n; i++) {
         if (levels[i] == null) { // the stock loop's own steps for this object, at its place in the set's order
            IsoMovingObject o = objs[i];
            if (o.getCurrentSquare() == null) {
               o.setCurrentSquareFromPosition();
               fillNoSquare++;
            }
            serialObjects++;
            UpdateSchedulerSimulationLevel sim = s.pzoptClassifySerial(o, averageFps);
            levels[i] = sim;
            fl[i] = flagsOf(o, sim, i);
         } else if (Config.DEV_SCHED_CHECK) {
            checks++;
            UpdateSchedulerSimulationLevel serial = s.pzoptClassifySerial(objs[i], averageFps);
            if (serial != levels[i]) {
               mismatches++;
               if (mismatches <= 20) {
                  Log.warn("schedulerClassify mismatch: " + objs[i].getClass().getSimpleName() + " id " + objs[i].getID() + " worker " + levels[i] + " game thread " + serial);
               }
            }
         }
      }
      updatedThisFrame = 0;
      for (int i = 0; i < n; i++) { // the stock loop's adds in the set's order, from the task's reads: no object is touched here
         int f = fl[i];
         s.pzoptAddTail(objs[i], levels[i], bucketIndex[i], (f & F_IN_PLACE) != 0, (f & F_ZOMBIE) != 0, (f & F_DUE) != 0);
         if ((f & F_DUE) != 0) {
            updatedThisFrame++;
         }
      }
      if (Config.DEV_SCHED_FILL_CHECK) {
         checkFill(s);
      }
      java.util.Arrays.fill(objs, 0, n, null);
      java.util.Arrays.fill(levels, 0, n, null);
   }

   /** The reads pzoptAdd and the bucket's add make of the object, done where the object is read anyway (index {@code i}). */
   private static byte flagsOf(IsoMovingObject o, UpdateSchedulerSimulationLevel sim, int i) {
      int frameMod = sim.getFrameMod();
      int index = o.getID() % frameMod;
      bucketIndex[i] = (short)index;
      return (byte)((o instanceof zombie.characters.IsoZombie ? F_ZOMBIE : 0) | (index == (int)(fillFrameCounter % (long)frameMod) ? F_DUE : 0)
            | (o instanceof zombie.characters.IsoPlayer || o instanceof zombie.vehicles.BaseVehicle ? F_IN_PLACE : 0));
   }

   private static void fillTask(int t) {
      int from = t * PER_TASK;
      int to = Math.min(from + PER_TASK, streamN);
      for (int i = from; i < to; i++) {
         levels[i] = null;
      }
      for (int i = from; i < to; i++) {
         IsoMovingObject o = objs[i];
         if (o.getCurrentSquare() == null) {
            fl[i] = F_NO_SQUARE; // the square fix is a write: the game thread, then its classification
            continue;
         }
         UpdateSchedulerSimulationLevel sim = scheduler.pzoptClassify(o, fps);
         if (sim != null) {
            fl[i] = (byte)(flagsOf(o, sim, i) | F_WORKER);
            levels[i] = sim;
         }
      }
   }

   /** devSchedFillCheck: every list of every level's bucket against the serial fill of the same classification. */
   private static void checkFill(MovingObjectUpdateScheduler s) {
      for (int order = 0; order < UpdateSchedulerSimulationLevel.numValues(); order++) {
         zombie.MovingObjectUpdateSchedulerUpdateBucket bucket = s.pzoptLevelBucket(order);
         int frameMod = 1 << order;
         for (int k = 0; k < frameMod; k++) {
            java.util.List<IsoMovingObject> list = bucket.getBucket(k);
            int at = 0;
            int inPlace = 0;
            boolean same = true;
            for (int i = 0; i < n; i++) {
               if (levels[i].getUpdateOrderIndex() == order && objs[i].getID() % frameMod == k) {
                  if (at >= list.size() || list.get(at) != objs[i]) {
                     same = false;
                  }
                  at++;
                  if (objs[i] instanceof zombie.characters.IsoPlayer || objs[i] instanceof zombie.vehicles.BaseVehicle) {
                     inPlace++;
                  }
               }
            }
            fillChecks++;
            if (!same || at != list.size() || inPlace != bucket.pzoptInPlaceCount(k)) {
               fillMismatches++;
               if (fillMismatches <= 20) {
                  Log.warn("schedulerFill mismatch: level " + order + " list " + k + " expected " + at + " got " + list.size() + " same order " + same);
               }
            }
         }
      }
   }

   public static String describe() {
      return "schedulerClassify frames=" + frames + " objects=" + objects + " serial=" + serialObjects + (Config.DEV_SCHED_CHECK ? " checks=" + checks + " mismatches=" + mismatches : "") + (failed ? " FAILED" : "")
            + (fillFrames > 0 ? " fill frames=" + fillFrames + " noSquare=" + fillNoSquare + (Config.DEV_SCHED_FILL_CHECK ? " fillChecks=" + fillChecks + " fillMismatches=" + fillMismatches + " minLevelChecks=" + minLevelChecks.get() + " minLevelMismatches=" + minLevelMismatches.get() : "") + (fillFailed ? " FILL FAILED" : "") : "");
   }
}
