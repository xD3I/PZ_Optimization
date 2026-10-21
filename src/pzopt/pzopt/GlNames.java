package pzopt;

import java.util.HashSet;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * glNoSync (2026-09-25, the Rosewood drive): GL object names without a driver round trip in the frame.
 *
 * <p>A GL call that returns a value (glGenTextures, glGenFramebuffers, glCheckFramebufferStatus, any glGet) makes the
 * render thread wait until NVIDIA's threaded driver has worked through everything queued before it. On the drive the
 * render thread's late frames spent ~25 % of their time in glGenTextures (corpse clothing textures from
 * TextureCombiner, new corpse / item atlas pages, render-chunk textures) and a few % in the framebuffer status check.
 * Names are only reserved numbers, so they are taken from a pool here, refilled with one batched glGen call right after
 * the swap ({@link #refill}), when the render thread has handed the frame over and waits for the next one anyway.
 * Framebuffer names belong to one GL context: the pool serves only the thread that filled it (the render thread),
 * any other caller asks the driver as before. A framebuffer configuration that came out complete once (same attachment
 * sizes, depth texture or renderbuffer, stencil) is not checked again: completeness depends only on those.
 */
public final class GlNames {
   public static final boolean ON = Config.GL_NO_SYNC && Overrides.enabled();
   /** bakeNoSync: the pools also serve the bake passes' own GL objects (AO, sway), whether or not glNoSync is on. */
   public static final boolean POOLS = (Config.GL_NO_SYNC || Config.BAKE_NO_SYNC || Config.COMBINER_POOL) && Overrides.enabled();
   /** combinerPool (tail2): TextureCombiner's composites take pooled names while this is set (render thread), glNoSync or not. */
   public static boolean combinerScope;
   private static final int[] textures = new int[Math.max(8, Config.GL_NAME_POOL)];
   private static final int[] framebuffers = new int[Math.max(4, Config.GL_NAME_POOL / 4)];
   private static final int[] queries = new int[64];
   private static int texN, fboN, queryN;
   public static long bakeTexServed, bakeFboServed, queryServed, bakeFallback;
   private static volatile Thread owner;
   private static final HashSet<Long> completeShapes = new HashSet<>();
   public static long texServed, texFallback, fboServed, fboFallback, refills, statusSkipped, statusChecked;

   private GlNames() {
   }

   /** Render thread, right after the swap: tops the texture pool up to full when under three quarters, the framebuffer pool under half. */
   public static void refill() {
      if (!POOLS) {
         return;
      }
      Thread t = Thread.currentThread();
      if (owner == null) {
         owner = t;
      } else if (owner != t) {
         return;
      }
      if (texN < textures.length * 3 / 4) { // topped up early: a frame creating many textures (corpse clothing, atlas pages) ran the pool dry at half
         int[] fresh = new int[textures.length - texN];
         GL11.glGenTextures(fresh);
         System.arraycopy(fresh, 0, textures, texN, fresh.length);
         texN = textures.length;
         refills++;
      }
      if (fboN < framebuffers.length / 2) {
         int[] fresh = new int[framebuffers.length - fboN];
         GL30.glGenFramebuffers(fresh);
         System.arraycopy(fresh, 0, framebuffers, fboN, fresh.length);
         fboN = framebuffers.length;
         refills++;
      }
      if (Config.BAKE_NO_SYNC && queryN < queries.length / 2) {
         int[] fresh = new int[queries.length - queryN];
         org.lwjgl.opengl.GL15.glGenQueries(fresh);
         System.arraycopy(fresh, 0, queries, queryN, fresh.length);
         queryN = queries.length;
         refills++;
      }
      ChunkAo.pollQueries(); // bakeNoSync: the AO multiplies' occlusion queries read here, after the swap, not inside a bake
   }

   /**
    * bakeNoSync: a texture name for a bake pass's own object (chunk AO terms and scratch, sway attributes), from the pool on
    * the render thread; glGenTextures otherwise. A glGen inside a bake made the render thread wait for NVIDIA's driver thread
    * to work off every draw the bakes queued before it (single baked levels of 10-35 ms on the Louisville horde).
    */
   public static int bakeTexture() {
      if (POOLS && Config.BAKE_NO_SYNC && texN > 0 && Thread.currentThread() == owner) {
         bakeTexServed++;
         return textures[--texN];
      }
      bakeFallback++;
      return GL11.glGenTextures();
   }

   /** bakeNoSync: a framebuffer name for a bake pass (core GL 3.0), pooled on the render thread. */
   public static int bakeFramebuffer() {
      if (POOLS && Config.BAKE_NO_SYNC && fboN > 0 && Thread.currentThread() == owner) {
         bakeFboServed++;
         return framebuffers[--fboN];
      }
      bakeFallback++;
      return GL30.glGenFramebuffers();
   }

   /** bakeNoSync: a query object name, pooled on the render thread. */
   public static int query() {
      if (POOLS && Config.BAKE_NO_SYNC && queryN > 0 && Thread.currentThread() == owner) {
         queryServed++;
         return queries[--queryN];
      }
      bakeFallback++;
      return org.lwjgl.opengl.GL15.glGenQueries();
   }

   /** A texture name for TextureID.generateHwId. */
   public static int texture() {
      if ((ON || combinerScope) && texN > 0 && Thread.currentThread() == owner) {
         texServed++;
         return textures[--texN];
      }
      texFallback++;
      if (GtAb.TIMING) { // tail pass (devFrameSections): the driver round trip's time, read per game frame by GtAb
         long t0 = System.nanoTime();
         int id = GL11.glGenTextures();
         genTexNanos += System.nanoTime() - t0;
         return id;
      }
      return GL11.glGenTextures();
   }

   public static volatile long genTexNanos; // tail pass: render thread's time in glGenTextures round trips (dev timing only)

   /** A framebuffer name for TextureFBO (core GL 3.0 framebuffers only; {@code core} false = the EXT / ARB path). */
   public static int framebuffer(boolean core) {
      if (ON && core && fboN > 0 && Thread.currentThread() == owner) {
         fboServed++;
         return framebuffers[--fboN];
      }
      fboFallback++;
      return -1;
   }

   private static long shape(int w, int h, int depthW, int depthH, boolean depthTexture, boolean stencil) {
      return (long)(w & 0x7FFF) << 49 | (long)(h & 0x7FFF) << 34 | (long)(depthW & 0x7FFF) << 19 | (long)(depthH & 0x7FFF) << 4
         | (depthTexture ? 2 : 0) | (stencil ? 1 : 0);
   }

   /** Whether TextureFBO may skip glCheckFramebufferStatus: this attachment shape already came out complete. */
   public static boolean knownComplete(int w, int h, int depthW, int depthH, boolean depthTexture, boolean stencil) {
      if (!ON || Thread.currentThread() != owner || !completeShapes.contains(shape(w, h, depthW, depthH, depthTexture, stencil))) {
         statusChecked++;
         return false;
      }
      statusSkipped++;
      return true;
   }

   public static void complete(int w, int h, int depthW, int depthH, boolean depthTexture, boolean stencil) {
      if (ON && Thread.currentThread() == owner) {
         completeShapes.add(shape(w, h, depthW, depthH, depthTexture, stencil));
      }
   }

   public static String summary() {
      return " | glNames: textures " + texServed + " pooled / " + texFallback + " asked, framebuffers " + fboServed + " / " + fboFallback
         + ", refills " + refills + ", status checks skipped " + statusSkipped + " / done " + statusChecked
         + (Config.BAKE_NO_SYNC ? ", bake passes: textures " + bakeTexServed + " fbos " + bakeFboServed + " queries " + queryServed + " pooled / " + bakeFallback + " asked" : "");
   }
}
