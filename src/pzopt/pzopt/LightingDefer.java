package pzopt;

import java.util.ArrayList;

/**
 * A batched entity's lazy light reads, made worker-safe (entityUpdateParallel, 2026-09-27).
 *
 * <p>A zombie updated on a frame worker reads its square's light (the visibility test of {@code updateSeenVisibility},
 * {@code spottedNew}); the first read of a square in a lighting pass is {@code JNILighting.update}, which rewrites the
 * square's light fields and then marks the chunk level dirty, feeds LightDirt / the puddle batch / the cutaway check and
 * runs the room / meta "square seen" hooks. Per-level flags written by two workers at once lose updates, and the hooks
 * touch rooms, zones and the music system. PR #35 left these on the worker. On a batch task the refresh of one square
 * now runs under that {@code JNILighting}'s monitor (two zombies on one square), and every side effect is appended to the
 * task's own list here, which the game thread runs at the join in task (= stock) order. The light values themselves are
 * the native's pure reads, the same whichever thread asks first.
 */
public final class LightingDefer {
   private LightingDefer() {
   }

   private static final Tl<ArrayList<Runnable>> SINK = new Tl<>(null);
   private static ArrayList<Runnable>[] lists = newLists(256);
   public static long deferred;

   @SuppressWarnings("unchecked")
   private static ArrayList<Runnable>[] newLists(int n) {
      return new ArrayList[n];
   }

   /** The running batch task's effect list, or null (game thread, LightingBatch tasks: effects run where they happen). */
   public static ArrayList<Runnable> current() {
      return SINK.get();
   }

   /** Game thread, before a batch of n tasks: room for their lists. */
   static void prepare(int n) {
      if (lists.length < n) {
         ArrayList<Runnable>[] grown = newLists(Math.max(n, lists.length * 2));
         System.arraycopy(lists, 0, grown, 0, lists.length);
         lists = grown;
      }
   }

   /** Worker, at the start of task i. */
   static void begin(int i) {
      ArrayList<Runnable> l = lists[i];
      if (l == null) {
         l = new ArrayList<>();
         lists[i] = l;
      }
      SINK.set(l);
   }

   /** Worker, at the end of a task. */
   static void end() {
      SINK.set(null); // tail pass: set(null), not remove(): the thread's map entry stays and the next set() reuses it (remove + set made a ThreadLocalMap.Entry per task, 12.5 MB/s on the Louisville horde); get() reads null either way
   }

   /** Game thread: task i's effects (tileRecordParallel splices unit by unit, each unit's effects first). */
   static void applyOne(int i) {
      ArrayList<Runnable> l = lists[i];
      if (l == null || l.isEmpty()) {
         return;
      }
      deferred += l.size();
      for (int k = 0; k < l.size(); k++) {
         l.get(k).run();
      }
      l.clear();
   }

   /** Game thread, at the join: tasks 0..n-1's effects in order. */
   static void apply(int n) {
      for (int i = 0; i < n; i++) {
         ArrayList<Runnable> l = lists[i];
         if (l == null || l.isEmpty()) {
            continue;
         }
         deferred += l.size();
         for (int k = 0; k < l.size(); k++) {
            l.get(k).run();
         }
         l.clear();
      }
   }
}
