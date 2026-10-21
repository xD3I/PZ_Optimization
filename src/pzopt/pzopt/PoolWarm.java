package pzopt;

/**
 * Chunk-arrival pass (2026-10-13, Louisville locked 120): the object pools behind the young-GC survivors.
 *
 * <p>The G1 young pauses of the Louisville route copy 50-95 MB each; JFR's promotion events put the zombies' per-frame
 * render data first (Matrix4f 14.5 % of the copied bytes: {@code AnimatedModelInstanceRenderData.init} allocates a
 * 60-bone palette whenever {@code ModelInstanceRenderData.alloc} finds its pool empty) and the streamed squares and
 * objects next. The render pools are {@code zombie.popman.ObjectPool}s capped at 1,024 / 4,096 free objects: with
 * ~1,500 drawn model instances and three frames in flight the releases past the cap are dropped and the next frames
 * allocate new ones (with their palettes), objects that live three frames and so get copied by every young pause.
 *
 * <p>{@code renderPoolMax} raises those caps (never lowers them). A pool's cap only decides whether a released object is
 * kept or dropped; every user re-initialises what it takes from a pool, as stock does for every pooled object, so what
 * the game computes does not change. {@code devPoolLog} logs the pools' free sizes and the squares created (from
 * {@code IsoGridSquare.idMax}) every 2 s.
 */
public final class PoolWarm {
   private PoolWarm() {
   }

   private static final String[][] RENDER_POOLS = {
      {"zombie.core.skinnedmodel.model.ModelSlotRenderData", "pool"},
      {"zombie.core.skinnedmodel.model.ModelInstanceRenderData", "pool"},
      {"zombie.core.skinnedmodel.advancedanimation.AnimatedModel", "instDataPool"},
      {"zombie.core.skinnedmodel.model.ModelInstanceTextureInitializer", "pool"},
      {"zombie.core.skinnedmodel.ModelCameraRenderData", "s_pool"},
      {"zombie.core.skinnedmodel.model.ModelInstance$PlayerData", "pool"},
   };
   private static zombie.popman.ObjectPool<?>[] pools;
   private static boolean done;
   private static long lastLogNs;
   private static int lastId = Integer.MIN_VALUE;
   private static long lastDisc = -1;

   private static zombie.popman.ObjectPool<?>[] pools() {
      if (pools == null) {
         pools = new zombie.popman.ObjectPool<?>[RENDER_POOLS.length];
         for (int i = 0; i < RENDER_POOLS.length; i++) {
            try {
               java.lang.reflect.Field f = Class.forName(RENDER_POOLS[i][0]).getDeclaredField(RENDER_POOLS[i][1]);
               f.setAccessible(true);
               pools[i] = (zombie.popman.ObjectPool<?>)f.get(null);
            } catch (Throwable t) {
               Log.warn("pool warm: no pool " + RENDER_POOLS[i][0] + "." + RENDER_POOLS[i][1] + ": " + t);
            }
         }
      }
      return pools;
   }

   private static final java.util.HashMap<zombie.popman.ObjectPool<?>, Integer> MAX = new java.util.HashMap<>();

   private static int maxOf(zombie.popman.ObjectPool<?> p) {
      Integer m = MAX.get(p);
      if (m != null) {
         return m;
      }
      try {
         java.lang.reflect.Field f = zombie.popman.ObjectPool.class.getDeclaredField("maxSize");
         f.setAccessible(true);
         m = f.getInt(p);
      } catch (Throwable t) {
         m = -1;
      }
      MAX.put(p, m);
      return m;
   }

   private static java.util.Collection<?> reuseQueue;

   private static int reuseQueueSize() {
      try {
         if (reuseQueue == null) {
            java.lang.reflect.Field f = zombie.iso.WorldReuserThread.class.getDeclaredField("reuseGridSquares");
            f.setAccessible(true);
            reuseQueue = (java.util.Collection<?>)f.get(zombie.iso.WorldReuserThread.instance);
         }
         return reuseQueue.size();
      } catch (Throwable t) {
         return -1;
      }
   }

   /** Game thread, once a frame (IsoChunkMap.update). */
   public static void frame() {
      if (!Overrides.enabled()) {
         return;
      }
      if (!done) {
         done = true;
         if (Config.RENDER_POOL_MAX > 0) {
            StringBuilder sb = new StringBuilder();
            for (zombie.popman.ObjectPool<?> p : pools()) {
               if (p != null) {
                  int old = maxOf(p);
                  if (old >= 0 && old < Config.RENDER_POOL_MAX) {
                     p.setMaxSize(Config.RENDER_POOL_MAX);
                     MAX.put(p, Config.RENDER_POOL_MAX);
                     sb.append(' ').append(old).append("->").append(Config.RENDER_POOL_MAX);
                  }
               }
            }
            Log.info("pool warm: render pool caps" + sb);
         }
      }
      if (Config.DEV_POOL_LOG) {
         long now = System.nanoTime();
         if (now - lastLogNs >= 2_000_000_000L) {
            lastLogNs = now;
            int id = zombie.iso.IsoGridSquare.idMax;
            StringBuilder sb = new StringBuilder("pool warm: squares created ");
            sb.append(lastId == Integer.MIN_VALUE ? 0 : id - lastId);
            lastId = id;
            long disc = zombie.iso.IsoGridSquare.pzoptDiscards;
            sb.append(", discarded ").append(lastDisc < 0 ? 0 : disc - lastDisc).append(" (dropped ").append(zombie.iso.IsoGridSquare.pzoptDiscardsDropped).append(')');
            lastDisc = disc;
            sb.append(", square cache ").append(zombie.iso.IsoGridSquare.isoGridSquareCache.size());
            sb.append(", object cache ").append(zombie.iso.CellLoader.isoObjectCache.size());
            sb.append(", tree cache ").append(zombie.iso.CellLoader.isoTreeCache.size());
            sb.append(", save queue ").append(zombie.iso.ChunkSaveWorker.instance.toSaveQueue.size());
            sb.append(", reuse queue ").append(reuseQueueSize());
            sb.append(", hand-off queue ").append(zombie.iso.IsoChunk.loadGridSquare.size());
            sb.append(", paced ").append(ChunkPace.paced).append(" backlog frames ").append(ChunkPace.backlogFrames).append(" steady frames ").append(ChunkPace.steadyFrames);
            sb.append(", render pools free/max");
            zombie.popman.ObjectPool<?>[] ps = pools();
            for (int i = 0; i < ps.length; i++) {
               if (ps[i] != null) {
                  String n = RENDER_POOLS[i][0];
                  sb.append(' ').append(n.substring(n.lastIndexOf('.') + 1)).append('=').append(ps[i].size()).append('/').append(maxOf(ps[i]));
               }
            }
            Log.info(sb.toString());
         }
      }
   }
}
