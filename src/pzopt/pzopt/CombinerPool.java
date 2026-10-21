package pzopt;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;

/**
 * combinerPool (tail2 pass 2026-10-13, the Louisville horde): the render thread's frames that create textures were the
 * largest named cause of late frames (55-76 a 25 s route): ~2,900 TextureCombiner composites (a zombie's body and
 * clothing, with blood, dirt and holes) are made on the route, and stock keeps at most 100 free ones a size (destroying
 * the rest) and re-specifies the storage of every reused one (glTexImage2D(null)), so NVIDIA's threaded driver allocates
 * video memory in bursts and the render thread blocks behind it.
 *
 * <p>With the key on, the TextureCombiner override keeps a reused composite's storage (every combine clears the whole
 * framebuffer before drawing, so the old contents never show), keeps free composites beyond stock's 100 a size up to a
 * VRAM budget ({@link #budgetBytes}), and right after the swap tops up a reserve of free composites of every size that
 * was asked for ({@code combinerReserve}, at most {@code combinerReservePerFrame} a frame), so a burst of zombies coming
 * into view takes ready storage. The pictures are the same: the same sizes, formats and sampler parameters as stock's
 * reuse path (a reserve composite gets them when it is made instead of when it is taken).
 *
 * <p>The VRAM budget: {@code combinerPoolMb} (256 MB) on GPUs with 6 GB or more, scaled down linearly below (a 4 GB GPU
 * gets 170 MB, 2 GB 85 MB); NVIDIA reports its memory through GL_NVX_gpu_memory_info, AMD's GL_ATI_meminfo gives the
 * free texture memory (taken as the size); unknown = half the budget. Free composites are 256-1024 px RGBA8 without
 * mipmaps (0.25-4 MB each).
 */
public final class CombinerPool {
   private CombinerPool() {
   }

   public static final boolean ON = Config.COMBINER_POOL && Overrides.enabled();
   public static final boolean KEEP_STORAGE = ON && Config.COMBINER_KEEP_STORAGE;

   /** combinerPool this frame (devGtAlternate keys "combinerPool": the behaviours switch with the alternation; the pool's contents carry over). */
   public static boolean active() {
      return ON && GtAb.on(GtAb.COMBINER);
   }

   public static boolean keepStorage() {
      return KEEP_STORAGE && GtAb.on(GtAb.COMBINER);
   }
   public static final boolean CENSUS = Config.DEV_COMBINER_CENSUS;

   /** Reserve composites that already carry stock's reuse parameters (render thread). */
   public static final java.util.Set<Object> READY = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
   private static long budget = -1L;
   // devCombinerCheck (dual evaluation): every Nth combine into a reused composite is drawn again into a new composite whose
   // storage is specified the stock way (glTexImage2D(null)); both are read back and compared byte for byte
   public static final int CHECK_EVERY = Math.max(0, Config.DEV_COMBINER_CHECK);
   public static boolean forceFresh, lastReuse;
   public static long checkCount, checked, mismatched;

   public static void compare(zombie.core.textures.Texture a, zombie.core.textures.Texture b) {
      try {
         int n = a.getWidthHW() * a.getHeightHW() * 4;
         if (n != b.getWidthHW() * b.getHeightHW() * 4) {
            mismatched++;
            return;
         }
         java.nio.ByteBuffer ba = org.lwjgl.BufferUtils.createByteBuffer(n), bb = org.lwjgl.BufferUtils.createByteBuffer(n);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, a.getID());
         GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, ba);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, b.getID());
         GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bb);
         zombie.core.textures.Texture.lastTextureID = 0;
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         checked++;
         int diff = ba.mismatch(bb);
         if (diff >= 0) {
            mismatched++;
            if (mismatched <= 5) {
               Log.warn("combiner check: mismatch at byte " + diff + " of " + n + " (" + a.getWidthHW() + "x" + a.getHeightHW() + ")");
            }
         }
         if (checked % 50 == 0) {
            Log.info("combiner check: " + checked + " reused composites compared with a stock-specified one, " + mismatched + " mismatches");
         }
      } catch (Throwable t) {
         Log.warn("combiner check: " + t);
      }
   }
   public static long pooledBytes;
   private static int vramMb = -2;

   /** Render thread: the bytes free composites may hold beyond stock's rule (100 a size). */
   public static long budgetBytes() {
      if (budget < 0L) {
         vramMb = queryVramMb();
         long mb = Math.max(0, Config.COMBINER_POOL_MB);
         if (vramMb < 0) {
            mb /= 2;
         } else if (vramMb < 6144) {
            mb = mb * vramMb / 6144;
         }
         budget = mb << 20;
         Log.info("combiner pool: budget " + mb + " MB (combinerPoolMb " + Config.COMBINER_POOL_MB + ", VRAM "
            + (vramMb < 0 ? "unknown" : vramMb + " MB") + "), reserve " + Config.COMBINER_RESERVE + " a size, "
            + Config.COMBINER_RESERVE_PER_FRAME + " a frame, keep storage " + KEEP_STORAGE);
      }
      return budget;
   }

   /** One query at the first use (a driver round trip, once). -1 = unknown. */
   private static int queryVramMb() {
      try {
         GLCapabilities caps = GL.getCapabilities();
         if (caps.GL_NVX_gpu_memory_info) {
            int kb = GL11.glGetInteger(0x9047); // GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX
            return kb > 0 ? kb >> 10 : -1;
         }
         if (caps.GL_ATI_meminfo) {
            int[] v = new int[4];
            GL11.glGetIntegerv(0x87FC, v); // TEXTURE_FREE_MEMORY_ATI: free KB first
            return v[0] > 0 ? v[0] >> 10 : -1;
         }
      } catch (Throwable t) {
         Log.warn("combiner pool: no VRAM query: " + t);
      }
      return -1;
   }

   // ---- census (devCombinerCensus), render thread ----
   private static final int SIZES = 8; // log2 of the width 5..12 folded into 0..7
   private static final long[] requests = new long[SIZES], reuses = new long[SIZES], made = new long[SIZES], reserved = new long[SIZES];
   private static final long[] destroyed = new long[SIZES], released = new long[SIZES];
   private static final int[] pooledNow = new int[SIZES], pooledMax = new int[SIZES];
   public static long combineNs, combineMaxNs, combines;
   private static int frameMade, frameMaxMade;
   private static long lastLog = System.nanoTime();

   private static int slot(int w) {
      int s = 31 - Integer.numberOfLeadingZeros(Math.max(1, w)) - 5;
      return s < 0 ? 0 : Math.min(SIZES - 1, s);
   }

   public static void onRequest(int w, boolean reuse) {
      int s = slot(w);
      requests[s]++;
      if (reuse) {
         reuses[s]++;
      } else {
         made[s]++;
         frameMade++;
      }
   }

   public static void onReserve(int w) {
      reserved[slot(w)]++;
   }

   public static void onRelease(int w, boolean kept) {
      int s = slot(w);
      released[s]++;
      if (!kept) {
         destroyed[s]++;
      }
   }

   public static void onPoolSize(int w, int n) {
      int s = slot(w);
      pooledNow[s] = n;
      if (n > pooledMax[s]) {
         pooledMax[s] = n;
      }
   }

   public static void onCombine(long ns) {
      combines++;
      combineNs += ns;
      if (ns > combineMaxNs) {
         combineMaxNs = ns;
      }
   }

   /** Render thread, after the swap: the census line every 10 s. */
   public static void censusFrame() {
      if (frameMade > frameMaxMade) {
         frameMaxMade = frameMade;
      }
      frameMade = 0;
      long now = System.nanoTime();
      if (now - lastLog < 10_000_000_000L) {
         return;
      }
      lastLog = now;
      StringBuilder sb = new StringBuilder("combiner census: combines ").append(combines).append(", ")
         .append(combineNs / 1_000_000L).append(" ms render thread (max ").append(combineMaxNs / 1000L).append(" us), new in one frame max ")
         .append(frameMaxMade).append(", pooled ").append(pooledBytes >> 20).append(" MB; per size (requests/reused/new/reserve made/released/destroyed/free now/free max):");
      for (int s = 0; s < SIZES; s++) {
         if (requests[s] == 0 && released[s] == 0) {
            continue;
         }
         sb.append(' ').append(1 << (s + 5)).append("px=").append(requests[s]).append('/').append(reuses[s]).append('/').append(made[s])
            .append('/').append(reserved[s]).append('/').append(released[s]).append('/').append(destroyed[s]).append('/')
            .append(pooledNow[s]).append('/').append(pooledMax[s]).append(';');
      }
      Log.info(sb.toString());
   }
}
