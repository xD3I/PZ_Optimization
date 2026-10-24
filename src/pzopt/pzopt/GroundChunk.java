package pzopt;

import java.util.function.Consumer;
import zombie.iso.IsoChunk;

/**
 * Per-chunk slots for the ground features (2026-10-24, ground-common): a feature's per-chunk cache (ground cover's
 * ChunkCover, glints' masks) lives on the chunk object itself ({@code IsoChunk.pzoptGround}) instead of an
 * IdentityHashMap keyed by chunk, and goes with the chunk: {@code IsoChunk.resetForStore} (unload and pool reuse) calls
 * {@link #reused}, which drops every slot, hands each value to its feature's release hook and bumps the chunk's generation.
 *
 * <p>Asynchronous builds: take {@link #gen(IsoChunk)} when the build starts and land the result with
 * {@link #putIf(IsoChunk, int, int, Object)}; it refuses a result whose chunk was reused meanwhile (the worker may keep its
 * IsoChunk reference, the object is the next chunk by then).
 *
 * <p>Cost: nothing while no feature registered a slot ({@link #reused} returns on one static read); a lookup is an array read.
 */
public final class GroundChunk {
   private GroundChunk() {
   }

   public static final int MAX = 8;
   private static final String[] NAMES = new String[MAX];
   @SuppressWarnings("unchecked")
   private static final Consumer<Object>[] RELEASE = new Consumer[MAX];
   private static int count;
   private static volatile boolean any;
   public static long reuses, released, refused;
   private static final Object LOCK = new Object(); // (not the chunk: the game may lock it)

   /** A slot id for {@code name} (the same id every call), with an optional hook that gets each value a reuse drops. */
   public static synchronized int id(String name, Consumer<Object> release) {
      for (int i = 0; i < count; i++) {
         if (NAMES[i].equals(name)) {
            if (release != null) {
               RELEASE[i] = release;
            }
            return i;
         }
      }
      if (count >= MAX) {
         throw new IllegalStateException("GroundChunk: more than " + MAX + " slots");
      }
      NAMES[count] = name;
      RELEASE[count] = release;
      any = true;
      return count++;
   }

   public static int id(String name) {
      return id(name, null);
   }

   /** The value of slot {@code id} on chunk {@code c}, or null. */
   public static Object get(IsoChunk c, int id) {
      Object[] s = c == null ? null : c.pzoptGround;
      return s == null ? null : s[id];
   }

   /** Sets slot {@code id} (null clears it); the game thread, or any thread that owns the chunk's slot. */
   public static void put(IsoChunk c, int id, Object v) {
      synchronized (LOCK) {
         Object[] s = c.pzoptGround;
         if (s == null) {
            if (v == null) {
               return;
            }
            s = c.pzoptGround = new Object[MAX];
         }
         s[id] = v;
      }
   }

   /** The chunk's generation (bumped on every reuse): take it when an asynchronous build of a slot starts. */
   public static int gen(IsoChunk c) {
      return c.pzoptGroundGen;
   }

   /** {@link #put} if the chunk was not reused since {@code gen}; false (nothing stored) otherwise. */
   public static boolean putIf(IsoChunk c, int id, int gen, Object v) {
      synchronized (LOCK) {
         if (c.pzoptGroundGen != gen) {
            refused++;
            return false;
         }
         put(c, id, v);
         return true;
      }
   }

   /** IsoChunk.resetForStore: the slots belong to the previous chunk. */
   public static void reused(IsoChunk c) {
      if (!any) {
         return;
      }
      Object[] s;
      synchronized (LOCK) {
         c.pzoptGroundGen++;
         s = c.pzoptGround;
         c.pzoptGround = null;
      }
      reuses++;
      if (s == null) {
         return;
      }
      for (int i = 0; i < count; i++) {
         Object v = s[i];
         Consumer<Object> r = RELEASE[i];
         if (v != null && r != null) {
            released++;
            try {
               r.accept(v);
            } catch (Throwable t) {
               Log.warn("ground chunk: release of " + NAMES[i] + " failed: " + t);
            }
         }
      }
   }

   public static String stats() {
      return "ground chunk slots: " + count + ", reuses " + reuses + ", released " + released + ", stale results refused " + refused;
   }
}
