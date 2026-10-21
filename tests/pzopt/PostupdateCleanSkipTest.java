package pzopt;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import zombie.MovingObjectUpdateSchedulerUpdateBucket;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.characters.IsoZombie;
import zombie.iso.IsoMovingObject;

/**
 * postupdateClean exactness (f8-cleanfix, 2026-10-13): stock's bucket loop ({@code for i < size: list.get(i).postupdate()})
 * skips the entry right after an entity that removes itself (the list shifts under the loop) and never runs an entity another
 * one removed before its turn. A clean zombie did its movement and the tail of its postupdate on its movement task before the
 * loop, so for a zombie stock never runs that frame PostupdateBatch.finish() must put both back.
 *
 * <p>The real bucket override's loop is driven over a list of clean zombies (allocated without a constructor: the loop never
 * calls into a clean zombie, or only reaches IsoZombie.postupdate's guard) and probe entities that remove themselves, remove
 * entries ahead of them or behind them, many at once (more than cleanAt's look-ahead, so the guard path confirms the zombie).
 * The same list is walked by a model of stock's loop; after finish() exactly the zombies the model reached keep their moved
 * state, every other one is back to its state before the movement batch.
 */
public final class PostupdateCleanSkipTest {
   static MovingObjectUpdateSchedulerUpdateBucket bucket;

   /** A non-zombie entity of the bucket: what its postupdate does to the list. */
   static final class Probe extends IsoMovingObject {
      final int kind; // 0 removes itself, 1 removes the n entries after it, 2 removes the entry n before it (behind the loop)
      final int n;

      Probe(int kind, int n) {
         super(false);
         this.kind = kind;
         this.n = n;
      }

      @Override
      public void postupdate() {
         act(bucket.getBucket(0), this, true);
      }

      /** On the real bucket (removeObject) or on the model's plain list. */
      void act(List<IsoMovingObject> list, IsoMovingObject self, boolean real) {
         int at = list.indexOf(self);
         List<IsoMovingObject> victims = new ArrayList<>();
         if (this.kind == 0) {
            victims.add(self);
         } else if (this.kind == 1) {
            for (int j = at + 1; j < Math.min(list.size(), at + 1 + this.n); j++) {
               victims.add(list.get(j));
            }
         } else if (at - this.n >= 0) {
            victims.add(list.get(at - this.n));
         }
         for (IsoMovingObject v : victims) {
            if (real) {
               bucket.removeObject(v);
            } else {
               list.remove(v);
            }
         }
      }
   }

   static sun.misc.Unsafe unsafe;
   static Field fQueue, fCount, fClean, fCleanFrame, fCursor, fReached, fCanSee;

   public static void main(String[] args) throws Throwable {
      zombie.core.random.RandStandard.INSTANCE.init();
      Field uf = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
      uf.setAccessible(true);
      unsafe = (sun.misc.Unsafe) uf.get(null);
      fQueue = field(PostupdateBatch.class, "queue");
      fCount = field(PostupdateBatch.class, "count");
      fClean = field(PostupdateBatch.class, "clean");
      fCleanFrame = field(PostupdateBatch.class, "cleanFrame");
      fCursor = field(PostupdateBatch.class, "cursor");
      fReached = field(PostupdateBatch.class, "reached");
      fCanSee = field(IsoZombie.class, "canSeeTarget");
      Check.check(Config.POSTUPDATE_CLEAN, "postupdateClean on in the test JVM");
      // -Dpzopt.devCleanEarly=true: the pre-fix shape, which must show the defect (the unreached zombies keep their work)
      bucket = new MovingObjectUpdateSchedulerUpdateBucket(UpdateSchedulerSimulationLevel.FULL);

      // the deterministic shapes first: the skipped slot, a removal ahead, a removal behind, a run longer than the look-ahead
      int[][][] shapes = {
         {{-1}, {0, 0}, {-1}, {-1}},          // z0, P(remove self), z1 (skipped by stock), z2
         {{-1}, {1, 2}, {-1}, {-1}, {-1}},    // z0, P(remove the next two), z1, z2 (never run), z3
         {{-1}, {-1}, {2, 2}, {-1}, {-1}},    // z0, z1, P(remove z0, behind the loop): z2 skipped
         {{-1}, {1, 12}, {-1}, {-1}, {-1}, {-1}, {-1}, {-1}, {-1}, {-1}, {-1}, {-1}, {-1}, {-1}, {-1}, {-1}}, // 12 ahead: guard path
      };
      long[] totals = new long[3];
      for (int[][] shape : shapes) {
         frame(shape, totals);
      }
      Check.check(totals[1] > 0 && totals[2] > 0, "the shapes reach both kinds: kept " + totals[1] + ", put back " + totals[2]);

      Random r = new Random(42);
      for (int f = 0; f < 3000; f++) {
         int n = 4 + r.nextInt(60);
         int[][] shape = new int[n][];
         for (int i = 0; i < n; i++) {
            int roll = r.nextInt(100);
            if (roll < 82) {
               shape[i] = new int[]{-1};
            } else if (roll < 92) {
               shape[i] = new int[]{0, 0};
            } else if (roll < 97) {
               shape[i] = new int[]{1, 1 + r.nextInt(roll < 95 ? 3 : 14)};
            } else {
               shape[i] = new int[]{2, 1 + r.nextInt(4)};
            }
         }
         frame(shape, totals);
      }
      System.out.println("PostupdateCleanSkipTest ok" + (Config.DEV_CLEAN_EARLY ? " (devCleanEarly: the defect reproduced)" : "") + ": " + totals[0] + " clean zombies, " + totals[1] + " reached (kept), " + totals[2]
         + " not reached by stock's loop");
   }

   /** One frame: build the list, run the override's loop and finish(), check every zombie against the model of stock's loop. */
   static void frame(int[][] shape, long[] totals) throws Throwable {
      List<IsoMovingObject> list = bucket.getBucket(0);
      list.clear();
      List<IsoZombie> zs = new ArrayList<>();
      for (int[] e : shape) {
         if (e[0] < 0) {
            IsoZombie z = (IsoZombie) unsafe.allocateInstance(IsoZombie.class);
            zs.add(z);
            list.add(z);
         } else {
            list.add(new Probe(e[0], e[1]));
         }
      }
      // the model: stock's loop over a copy
      List<IsoMovingObject> model = new ArrayList<>(list);
      IdentityHashMap<IsoMovingObject, Boolean> stockRan = new IdentityHashMap<>();
      for (int i = 0; i < model.size(); i++) {
         IsoMovingObject o = model.get(i);
         if (o instanceof Probe p) {
            p.act(model, o, false);
         } else {
            stockRan.put(o, Boolean.TRUE);
         }
      }
      // PostupdateBatch as prepass leaves it after a movement batch in which every zombie came out clean (flag 1)
      int count = zs.size();
      IsoZombie[] queue = (IsoZombie[]) fQueue.get(null);
      if (queue.length < count) {
         queue = new IsoZombie[count * 2];
         fQueue.set(null, queue);
      }
      byte[] clean = (byte[]) fClean.get(null);
      if (clean.length < count) {
         clean = new byte[count * 2];
         fClean.set(null, clean);
      }
      boolean[] reached = (boolean[]) fReached.get(null);
      if (reached.length < count) {
         reached = new boolean[count * 2];
         fReached.set(null, reached);
      }
      long stamp = PostupdateBatch.frameStamp();
      for (int k = 0; k < count; k++) {
         IsoZombie z = zs.get(k);
         queue[k] = z;
         clean[k] = 1;
         reached[k] = false;
         z.pzoptCleanIdx = k;
         PostupdateBatch.Snap before = new PostupdateBatch.Snap();
         before.x = k;
         before.y = 2 * k;
         before.cleanUnbalanced = 0.5F;
         before.cleanSeenTime = 3.0F;
         before.cleanCanSee = true;
         before.cleanHeadPending = false;
         z.pzoptMoveRestore(before); // the state before the movement batch
         z.pzoptMoveSnap = before; // what the task saved
         PostupdateBatch.Snap moved = new PostupdateBatch.Snap();
         moved.x = k + 0.25F;
         moved.y = 2 * k + 0.25F;
         z.pzoptMoveRestore(moved); // the task's movement
         z.pzoptCleanUndo(new PostupdateBatch.Snap()); // the tail's writes: unbalanced 0, seen time 0, canSee false ...
         z.pzoptHeadPending = true; // ... the head left to the evaluation task
         z.pzoptCleanFrame = stamp; // ... done this frame
      }
      fCount.set(null, count);
      fCursor.set(null, 0);
      fCleanFrame.set(null, true);

      bucket.postupdate(0);
      PostupdateBatch.finish();

      for (int k = 0; k < count; k++) {
         IsoZombie z = zs.get(k);
         boolean ran = stockRan.containsKey(z);
         totals[0]++;
         if (ran) {
            totals[1]++;
            Check.check(z.getX() == k + 0.25F && z.getY() == 2 * k + 0.25F && z.pzoptHeadPending && z.pzoptCleanFrame == stamp
                  && z.getUnbalancedLevel() == 0.0F && !fCanSee.getBoolean(z),
               "a zombie stock's loop reaches keeps its movement task's postupdate (zombie " + k + " of " + count + ")");
         } else if (Config.DEV_CLEAN_EARLY) {
            totals[2]++;
            Check.check(z.getX() == k + 0.25F && z.pzoptHeadPending, "devCleanEarly: a zombie stock never ran keeps its task's movement and"
               + " head mark (the defect; zombie " + k + ")");
         } else {
            totals[2]++;
            Check.check(z.pzoptMoveDiff(z.pzoptMoveSnap) == null, "a zombie stock's loop never reaches has its movement put back (zombie " + k
               + " of " + count + ", at " + z.getX() + "," + z.getY() + ")");
            Check.check(z.getUnbalancedLevel() == 0.5F && z.getTargetSeenTime() == 3.0F && fCanSee.getBoolean(z) && !z.pzoptHeadPending
                  && z.pzoptCleanFrame == 0L,
               "a zombie stock's loop never reaches has its tail put back (zombie " + k + " of " + count + ")");
         }
      }
      list.clear();
   }

   static Field field(Class<?> c, String name) throws NoSuchFieldException {
      Field f = c.getDeclaredField(name);
      f.setAccessible(true);
      return f;
   }
}
