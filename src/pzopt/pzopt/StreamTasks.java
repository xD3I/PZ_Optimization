package pzopt;

/**
 * The task list of a FrameBatch stream (zombie redesign pass, 2026-10-13, f8-zomb2): task j is item j. The items are read by
 * the stream's tasks until the stream has been joined, so {@link #release} may only run after a FrameBatch join (ActionEval
 * releases at the next frame's begin). Releasing at the seal lost the evaluations of tasks that had not started yet and hung
 * the game in AnimParallel.waitEval (run zomb2-all1); tests/pzopt/FrameBatchStreamTest covers the order.
 */
final class StreamTasks<T> {
   private Object[] items = new Object[1024];
   private boolean[] flags = new boolean[1024];
   private int count;

   int add(T item, boolean flag) {
      if (this.count == this.items.length) {
         this.items = java.util.Arrays.copyOf(this.items, this.count * 2);
         this.flags = java.util.Arrays.copyOf(this.flags, this.count * 2);
      }
      this.items[this.count] = item;
      this.flags[this.count] = flag;
      return ++this.count;
   }

   @SuppressWarnings("unchecked")
   T item(int j) {
      return (T) this.items[j];
   }

   boolean flag(int j) {
      return this.flags[j];
   }

   int size() {
      return this.count;
   }

   /** Game thread, only once the stream holding these tasks has been joined. */
   void release() {
      java.util.Arrays.fill(this.items, 0, this.count, null);
      this.count = 0;
   }
}
