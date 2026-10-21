package pzopt;

/**
 * A thread-local value without the ThreadLocal lookup on the threads that ask most (zombie redesign pass, 2026-10-13,
 * f8-zomb2): a {@link FrameBatch.Worker} keeps its values in an array on its own Thread object, the game thread in a static
 * array only it touches, every other thread in a plain {@link ThreadLocal}. Same semantics as
 * {@code ThreadLocal.withInitial(init)} (or {@code new ThreadLocal<>()} with a null init): one value per thread, created on
 * the thread's first {@link #get}. The per-frame zombie batches asked ThreadLocals tens of thousands of times a frame (the
 * per-thread scratch of the parallel passes, the profiler's per-thread instance), each a hash probe plus, under ZGC, a
 * weak-reference load barrier: ~4 % of the entity flight.
 */
public final class Tl<T> {
   private static final java.util.concurrent.atomic.AtomicInteger NEXT = new java.util.concurrent.atomic.AtomicInteger();
   private static Object[] game = new Object[64]; // the game thread's values (only the game thread reads or writes it)

   private final int slot = NEXT.getAndIncrement();
   private final java.util.function.Supplier<? extends T> init;
   private final ThreadLocal<T> other;

   public Tl(java.util.function.Supplier<? extends T> init) {
      this.init = init;
      this.other = init != null ? ThreadLocal.withInitial(init) : new ThreadLocal<>();
   }

   private Object[] array(Thread t) {
      if (t instanceof FrameBatch.Worker w) {
         Object[] a = w.tl;
         if (this.slot >= a.length) {
            a = w.tl = java.util.Arrays.copyOf(a, Math.max(this.slot + 1, a.length * 2));
         }
         return a;
      }
      if (t == zombie.GameWindow.gameThread) {
         Object[] a = game;
         if (this.slot >= a.length) {
            a = game = java.util.Arrays.copyOf(a, Math.max(this.slot + 1, a.length * 2));
         }
         return a;
      }
      return null;
   }

   private static final Object NULL = new Object(); // an explicit null on the fast path

   @SuppressWarnings("unchecked")
   public T get() {
      Object[] a = this.array(Thread.currentThread());
      if (a == null) {
         return this.other.get();
      }
      Object v = a[this.slot];
      if (v == null) {
         // first use on the fast path: the value this thread already has (a game thread that asked before
         // GameWindow.gameThread was set keeps its object, the profiler's open frame included), else the initial one
         v = this.other.get();
         a[this.slot] = v == null ? NULL : v;
         return (T) v;
      }
      return v == NULL ? null : (T) v;
   }

   public void set(T value) {
      Object[] a = this.array(Thread.currentThread());
      if (a == null) {
         this.other.set(value);
      } else {
         a[this.slot] = value == null ? NULL : value;
      }
   }

   public void remove() {
      Object[] a = this.array(Thread.currentThread());
      if (a == null) {
         this.other.remove();
      } else {
         this.other.remove();
         a[this.slot] = null; // the next get() takes a fresh initial value, as ThreadLocal.remove
      }
   }
}
