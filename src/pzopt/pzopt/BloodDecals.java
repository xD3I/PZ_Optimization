package pzopt;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.joml.Matrix4f;
import zombie.GameTime;
import zombie.IndieGL;
import zombie.core.Core;
import zombie.core.SceneShaderStore;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunk;
import zombie.iso.IsoDirections;
import zombie.iso.IsoFloorBloodSplat;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteManager;

/**
 * Floor blood decals without their bake cost (2026-09-29, docs/findings-blood-decals-2026-09-29.md).
 *
 * <p>Stock bakes the floor splats into every chunk-level texture: {@code FBORenderCell.renderOneLevel_Blood}, nine calls a
 * bake (the chunk, then its neighbours' splats that reach one tile over), each walking the chunk's whole 1,000-splat queue
 * and its fade list, and for every splat drawn a colour hash, four vertex-light reads, a shader start, a depth-test and a
 * blend change and one sprite. At the cap that doubled the game-thread cost of a bake.
 *
 * <p>Here each chunk keeps its live splats once, sorted by level and type in stock's draw order, with stock's colour hash
 * computed by the same float operations ({@link Cache}), rebuilt only when the queue changes (fingerprint: a version
 * {@code IsoChunk.addBloodSplat} bumps, the sizes, the ends). A bake then asks for one level of one chunk:
 * <ul>
 *   <li>{@code bloodBake=cpu}: the stock state once, then one sprite per splat straight to the sprite renderer (no
 *       TileDepthModifier: the default shader never reads its depth texture) - the exact stock picture, any GL;</li>
 *   <li>{@code bloodBake=gpu} (default): one instanced draw per texture run of that level from a shared RGBA32F atlas
 *       (one row per bloody chunk, two texels a splat, uploaded when the row changes); the vertex shader does stock's
 *       placement (truncated screen position, texture offsets), rectangle and density tests, age and light, and packs the
 *       colour to 8 bits as the sprite renderer would. Light and the cutaway / blacked-out tests are per square: a 64-entry
 *       table per chunk level, filled once a frame.</li>
 * </ul>
 * The fade list (splats pushed out at the cap) keeps the stock per-splat path, interleaved by type as stock orders it
 * ({@code bloodFadeFix} drops them instead: in chunk textures they never faded).
 *
 * <p>{@code bloodAppend}: a new splat is drawn into the finished textures it lands in (its chunk's and, within a tile of an
 * edge, the neighbours' - stock never re-baked those) instead of marking the level {@code DIRTY_BLOOD}; the quad is
 * depth-tested against the texture's own depth at the floor plane, so what stands on the floor stays in front. A splat
 * the density option hides re-bakes nothing. {@code bloodSettleSec} later lets one ordinary re-bake of the level restore
 * stock's exact order (blood under flat floor objects).
 */
public final class BloodDecals {
   private BloodDecals() {
   }

   public static final String MODE = Config.BLOOD_BAKE;
   public static final boolean ON = !"off".equals(MODE) && Overrides.enabled();
   public static final boolean GPU_WANTED = ON && "gpu".equals(MODE);
   public static final boolean APPEND = Config.BLOOD_APPEND && Overrides.enabled();
   private static volatile boolean gpuFailed = CoreGl.legacyMac();

   public static final int TYPES = IsoFloorBloodSplat.FLOOR_BLOOD_TYPES.length;
   static final int FLOATS = 8; // two RGBA32F texels a splat
   static final int ROW_SPLATS = 1024, ROW_TEXELS = ROW_SPLATS * 2;
   /** depth units per unit of (x + y) in a chunk texture (IsoDepthHelper: CHUNK_DEPTH / 16) */
   static final float K = 0.023093667F / 16.0F;

   // ------------------------------------------------------------------------------------------------ counters
   public static long bakeCalls, bakeCallsSkipped, splatsEmitted, gpuDraws, gpuInstances, cpuSprites, fadeSprites, cacheBuilds, rowUploads;
   public static long appendsAo, appendsUnsafe, appendsPlants, plantRedraws, appendsQueued, appendsDrawn, appendsFellBack, appendsHidden, appendsRefused, settleRebakes, fadeDropped;

   public static String stats() {
      return "blood: mode=" + (useGpu() ? "gpu" : ON ? "cpu" : "off") + " bakeCalls=" + bakeCalls + " skipped=" + bakeCallsSkipped + " splats=" + splatsEmitted
            + " gpuDraws=" + gpuDraws + " instances=" + gpuInstances + " cpuSprites=" + cpuSprites + " fadeSprites=" + fadeSprites + " caches=" + cacheBuilds
            + " rowUploads=" + rowUploads + " | append ao=" + appendsAo + " unsafe=" + appendsUnsafe + " plants=" + appendsPlants + " plantRedraws=" + plantRedraws + " queued=" + appendsQueued + " drawn=" + appendsDrawn + " fellBack=" + appendsFellBack + " hidden=" + appendsHidden
            + " refused=" + appendsRefused + " rebakes deferred=" + rebakesDeferred + " coalesced=" + rebakesCoalesced + " issued=" + rebakesIssued
            + " settleRebakes=" + settleRebakes + " fadeDropped=" + fadeDropped;
   }

   static boolean useGpu() {
      return GPU_WANTED && !gpuFailed;
   }

   // ------------------------------------------------------------------------------------------------ the per-chunk cache

   /** A chunk's live splats in stock's draw order per level: level, then type, then queue order. */
   public static final class Cache {
      IsoChunk chunk;
      int version = Integer.MIN_VALUE, size = -1;
      Object first, last;
      int n;
      // sorted splats (index into the arrays below = draw order)
      float[] x = new float[64], y = new float[64], z = new float[64], age = new float[64], hr = new float[64], hg = new float[64], hb = new float[64];
      int[] type = new int[64], index = new int[64];
      final int[] levelStart = new int[64], levelCount = new int[64]; // by level + 32
      final long[] levelSquares = new long[64]; // the squares holding a splat, by level + 32
      /** GPU: this cache's atlas row (-1 none) and the uploaded snapshot's serial */
      int row = -1;
      float[] data; // FLOATS per splat, draw order; replaced (never mutated) on a rebuild
      long serial;
      // the per-square table of one level (render flag, light factor), for one frame
      final float[][] sq = new float[64][];
      final int[] sqFrame = new int[64];
      final int[] sqPlayer = new int[64];

      Cache(IsoChunk c) {
         this.chunk = c;
         java.util.Arrays.fill(this.sqFrame, -1);
      }

      void grow(int need) {
         if (need <= this.x.length) {
            return;
         }
         int m = Math.max(need, this.x.length * 2);
         this.x = java.util.Arrays.copyOf(this.x, m);
         this.y = java.util.Arrays.copyOf(this.y, m);
         this.z = java.util.Arrays.copyOf(this.z, m);
         this.age = java.util.Arrays.copyOf(this.age, m);
         this.hr = java.util.Arrays.copyOf(this.hr, m);
         this.hg = java.util.Arrays.copyOf(this.hg, m);
         this.hb = java.util.Arrays.copyOf(this.hb, m);
         this.type = java.util.Arrays.copyOf(this.type, m);
         this.index = java.util.Arrays.copyOf(this.index, m);
      }
   }

   private static long serialCounter;
   private static long[] sortKeys = new long[1024];

   /** Game thread: the chunk's cache, rebuilt when its queue changed. */
   public static Cache cache(IsoChunk c) {
      Cache k = c.pzoptBlood instanceof Cache kk ? kk : null;
      if (k == null) {
         k = new Cache(c);
         c.pzoptBlood = k;
      }
      zombie.core.utils.BoundedQueue<IsoFloorBloodSplat> q = c.floorBloodSplats;
      int size = q.size();
      Object first = size > 0 ? q.get(0) : null, last = size > 0 ? q.get(size - 1) : null;
      if (k.version == c.pzoptBloodVersion && k.size == size && k.first == first && k.last == last) {
         return k;
      }
      k.version = c.pzoptBloodVersion;
      k.size = size;
      k.first = first;
      k.last = last;
      build(k, q, size);
      return k;
   }

   private static void build(Cache k, zombie.core.utils.BoundedQueue<IsoFloorBloodSplat> q, int size) {
      cacheBuilds++;
      if (sortKeys.length < size) {
         sortKeys = new long[Math.max(size, sortKeys.length * 2)];
      }
      int m = 0;
      for (int i = 0; i < size; i++) {
         IsoFloorBloodSplat b = q.get(i);
         if (b == null || b.type < 0 || b.type >= TYPES) {
            continue; // stock skips these
         }
         int level = zombie.core.math.PZMath.fastfloor(b.z);
         if (level < -32 || level > 31) {
            continue;
         }
         sortKeys[m++] = ((long)(level + 32) << 40) | ((long)b.type << 32) | i;
      }
      java.util.Arrays.sort(sortKeys, 0, m);
      k.grow(m);
      java.util.Arrays.fill(k.levelCount, 0);
      java.util.Arrays.fill(k.levelSquares, 0L);
      java.util.Arrays.fill(k.sqFrame, -1);
      float[] data = new float[Math.max(1, m) * FLOATS];
      for (int j = 0; j < m; j++) {
         long key = sortKeys[j];
         int li = (int)(key >>> 40);
         IsoFloorBloodSplat b = q.get((int)(key & 0xFFFFFFFFL));
         if (k.levelCount[li]++ == 0) {
            k.levelStart[li] = j;
         }
         k.x[j] = b.x;
         k.y[j] = b.y;
         k.z[j] = b.z;
         k.age[j] = b.worldAge;
         k.type[j] = b.type;
         k.index[j] = b.index;
         float[] h = HASH;
         hash(b, h);
         float r = h[0], g = h[1], bl = h[2];
         k.hr[j] = r;
         k.hg[j] = g;
         k.hb[j] = bl;
         int sx = zombie.core.math.PZMath.fastfloor(b.x), sy = zombie.core.math.PZMath.fastfloor(b.y);
         if (sx >= 0 && sx < 8 && sy >= 0 && sy < 8) {
            k.levelSquares[li] |= 1L << (sx + sy * 8);
         }
         pack(b, r, g, bl, data, j * FLOATS);
      }
      k.n = m;
      k.data = data;
      k.serial = ++serialCounter;
      if (useGpu() && m > 0 && k.row < 0) {
         k.row = Atlas.allocRow();
      }
   }

   private static final float[] HASH = new float[3];

   /** Stock's colour hash (FBORenderCell.renderOneLevel_Blood): the same float operations in the same order. */
   static void hash(IsoFloorBloodSplat b, float[] out) {
      float r = 1.0F, g = 1.0F, bl = 1.0F;
      float aa = (b.x + b.y / b.x) * (b.type + 1);
      float bb = aa * b.x / b.y * (b.type + 1) / (aa + b.y);
      float cc = bb * aa * bb * b.x / (b.y + 2.0F);
      aa *= 42367.543F;
      bb *= 6367.123F;
      cc *= 23367.133F;
      aa %= 1000.0F;
      bb %= 1000.0F;
      cc %= 1000.0F;
      aa /= 1000.0F;
      bb /= 1000.0F;
      cc /= 1000.0F;
      if (aa > 0.25F) {
         aa = 0.25F;
      }
      r -= aa * 2.0F;
      g -= aa * 2.0F;
      bl -= aa * 2.0F;
      r += bb / 3.0F;
      g -= cc / 3.0F;
      bl -= cc / 3.0F;
      out[0] = r;
      out[1] = g;
      out[2] = bl;
   }

   /** One splat's two texels: x, y (its chunk's frame), world age, type + 32 index | hashed colour, z. */
   static void pack(IsoFloorBloodSplat b, float r, float g, float bl, float[] data, int o) {
      data[o] = b.x;
      data[o + 1] = b.y;
      data[o + 2] = b.worldAge;
      data[o + 3] = b.type + 32 * Math.max(0, Math.min(15, b.index));
      data[o + 4] = r;
      data[o + 5] = g;
      data[o + 6] = bl;
      data[o + 7] = b.z;
   }

   /** IsoChunk reset (game thread or the loader): the chunk's row goes back to the pool. */
   public static void reset(IsoChunk c) {
      c.pzoptBloodVersion++;
      if (Thread.currentThread() == zombie.GameWindow.gameThread) {
         forget(c);
      }
      if (c.pzoptBlood instanceof Cache k) {
         if (k.row >= 0) {
            Atlas.freeRow(k.row);
            k.row = -1;
         }
         k.size = -1;
      }
   }

   // ------------------------------------------------------------------------------------------------ sprites of the types

   private static final Texture[] typeTex = new Texture[TYPES];
   /** per type: u0, v0, u1, v1 | x offset (-w/2 ts + offsetX), y offset, width, height (bake space, stock renderBloodSplat) */
   static final float[] typeUv = new float[TYPES * 4], typeGeom = new float[TYPES * 4];
   private static int typeTileScale = -1;
   private static boolean typesReady;

   /** Game thread: the splat sprites (stock creates them the same way on first use) and their placement per type. */
   static boolean types() {
      int ts = Core.tileScale;
      if (typesReady && ts == typeTileScale) {
         return true;
      }
      boolean all = true, create = false;
      for (int t = 0; t < TYPES; t++) {
         String name = IsoFloorBloodSplat.FLOOR_BLOOD_TYPES[t];
         IsoSprite sp = IsoFloorBloodSplat.spriteMap.get(name);
         if (sp == null) {
            sp = IsoSprite.CreateSprite(IsoSpriteManager.instance);
            sp.LoadSingleTexture(name);
            IsoFloorBloodSplat.spriteMap.put(name, sp);
         }
         Texture tex = sp.getTextureForCurrentFrame(IsoDirections.N);
         typeTex[t] = tex;
         if (tex == null || !tex.isReady() || tex.getTextureId() == null) {
            all = false;
            continue;
         }
         if (tex.getTextureId().getID() == -1) {
            // loaded but its GL texture not created yet: TextureID creates it at its first bind on the render thread, and
            // stock's Texture.render is the only caller that binds it (the bake and the wet layer bind the raw id; the wet
            // layer's runs threw on the -1 id every rebuild, each one aborting that frame's cell render: the maintainer's
            // "burn-in image" after a torch toggle, 2026-09-29). Create it and use stock's splats until it exists
            all = false;
            createPending[t] = tex;
            create = true;
         }
         typeUv[t * 4] = tex.getXStart();
         typeUv[t * 4 + 1] = tex.getYStart();
         typeUv[t * 4 + 2] = tex.getXEnd();
         typeUv[t * 4 + 3] = tex.getYEnd();
         // the half size (renderBloodSplat centres the sprite) less the texture's trim offset (Texture.render adds it)
         typeGeom[t * 4] = (float)tex.getWidth() / 2.0F * (float)ts - tex.offsetX;
         typeGeom[t * 4 + 1] = (float)tex.getHeight() / 2.0F * (float)ts - tex.offsetY;
         typeGeom[t * 4 + 2] = tex.getWidth();
         typeGeom[t * 4 + 3] = tex.getHeight();
      }
      typesReady = all;
      typeTileScale = ts;
      if (create && !createQueued) {
         createQueued = true;
         SpriteRenderer.instance.drawGeneric(CREATE);
      }
      return all;
   }

   private static final Texture[] createPending = new Texture[TYPES];
   private static volatile boolean createQueued;

   /** Render thread: the pending type textures' GL textures created (TextureID's lazy create at a bind). */
   private static final TextureDraw.GenericDrawer CREATE = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         for (int t = 0; t < TYPES; t++) {
            Texture tex = createPending[t];
            createPending[t] = null;
            if (tex != null && tex.getTextureId() != null) {
               tex.getTextureId().bind();
            }
         }
         createQueued = false;
      }
   };

   static Texture typeTexture(int t) {
      return typeTex[t];
   }

   // ------------------------------------------------------------------------------------------------ the bake

   /** Supplies the per-square table of a chunk level: FBORenderCell's cutaway, blacked-out and light rules. */
   public interface SquareRules {
      /** out[i * 4 .. + 3] for square i (x + 8 y) in {@code mask}: r, g, b light factor (0 when blacked out), 1 = drawn (0 = not). */
      void fill(IsoChunk c, int level, int playerIndex, long mask, float[] out);
   }

   private static final ArrayList<IsoFloorBloodSplat>[] fadeByType = fadeLists();

   @SuppressWarnings("unchecked")
   private static ArrayList<IsoFloorBloodSplat>[] fadeLists() {
      ArrayList<IsoFloorBloodSplat>[] a = new ArrayList[TYPES];
      for (int i = 0; i < TYPES; i++) {
         a[i] = new ArrayList<>();
      }
      return a;
   }

   /**
    * Game thread, FBORenderCell.renderOneLevel_Blood after stock's option checks: this chunk's splats of level
    * {@code zza} within the rectangle into the texture being baked. Returns false when stock's loop must run instead.
    */
   public static boolean bake(IsoChunk chunk, int zza, int minX, int minY, int maxX, int maxY, int optionBloodDecals, SquareRules rules) {
      if (!ON || zza < -32 || zza > 31 || FBORenderChunkManager.instance.renderChunk == null || !types()) {
         return false;
      }
      bakeCalls++;
      BloodDecals.rules = rules;
      Cache k = cache(chunk);
      int li = zza + 32;
      int count = k.levelCount[li];
      ArrayList<IsoFloorBloodSplat> fade = chunk.floorBloodSplatsFade;
      if (Config.BLOOD_FADE_FIX && !fade.isEmpty()) {
         fadeDropped += fade.size();
         fade.clear();
      }
      if (count == 0 && fade.isEmpty()) {
         bakeCallsSkipped++;
         return true;
      }
      float worldAge = (float)GameTime.getInstance().getWorldAgeHours();
      int playerIndex = IsoCamera.frameState.playerIndex;
      byte[] density = zombie.iso.IsoChunk.renderByIndex[optionBloodDecals - 1];
      int cx = chunk.wx * 8;
      int cy = chunk.wy * 8;
      // the fade list, per type as stock gathers it (it walks the fade list before the queue)
      boolean anyFade = false;
      if (!fade.isEmpty()) {
         for (int t = 0; t < TYPES; t++) {
            fadeByType[t].clear();
         }
         for (int n = 0; n < fade.size(); n++) {
            IsoFloorBloodSplat b = fade.get(n);
            if ((b.index < 1 || b.index > 10 || density[b.index - 1] != 0) && !(cx + b.x < minX) && !(cx + b.x > maxX) && !(cy + b.y < minY)
                  && !(cy + b.y > maxY) && zombie.core.math.PZMath.fastfloor(b.z) == zza && b.type >= 0 && b.type < TYPES) {
               b.chunk = chunk;
               fadeByType[b.type].add(b);
               anyFade = true;
            }
         }
      }
      float[] sq = null;
      if (count > 0) {
         sq = squares(k, chunk, zza, playerIndex, rules);
      }
      boolean gpu = useGpu() && k.row >= 0 && count > 0;
      int start = k.levelStart[li], end = start + count;
      boolean stateSet = false;
      if (!anyFade && gpu) {
         stateSet = stockState();
         queueGpu(k, start, end, sq, minX, minY, maxX, maxY, worldAge, optionBloodDecals, chunk);
         return true;
      }
      // per type: the fade splats (stock path), then the queue's (gpu run or sprites)
      int j = start;
      for (int t = 0; t < TYPES; t++) {
         if (anyFade && !fadeByType[t].isEmpty()) {
            if (!stateSet) {
               stateSet = stockState();
            }
            fadeSprites += fadeByType[t].size();
            FadeDraw.draw(fadeByType[t], worldAge, playerIndex, rules);
         }
         int runStart = j;
         while (j < end && k.type[j] == t) {
            j++;
         }
         if (j > runStart) {
            if (!stateSet) {
               stateSet = stockState();
            }
            if (gpu) {
               queueGpu(k, runStart, j, sq, minX, minY, maxX, maxY, worldAge, optionBloodDecals, chunk);
            } else {
               cpuRun(k, runStart, j, sq, minX, minY, maxX, maxY, worldAge, density, chunk);
            }
         }
      }
      return true;
   }

   /** The state stock sets before each splat (IsoSprite.renderBloodSplat), once. */
   private static boolean stockState() {
      SpriteRenderer.instance.StartShader(SceneShaderStore.defaultShaderId, IsoCamera.frameState.playerIndex);
      IndieGL.disableDepthTest();
      IndieGL.glBlendFuncSeparate(770, 771, 773, 1);
      return true;
   }

   private static int sqFrameNo() {
      return zombie.iso.IsoWorld.instance.getFrameNo();
   }

   /** The level's per-square table, once a frame per chunk level (bakes of the nine textures around share it). */
   private static float[] squares(Cache k, IsoChunk chunk, int zza, int playerIndex, SquareRules rules) {
      int li = zza + 32;
      int frame = sqFrameNo();
      float[] t = k.sq[li];
      if (t == null) {
         t = k.sq[li] = new float[256];
      }
      if (k.sqFrame[li] != frame || k.sqPlayer[li] != playerIndex) {
         k.sqFrame[li] = frame;
         k.sqPlayer[li] = playerIndex;
         java.util.Arrays.fill(t, 0F);
         rules.fill(chunk, zza, playerIndex, k.levelSquares[li], t);
      }
      return t;
   }

   /** bloodBake=cpu: one sprite per splat, stock's arithmetic, no per-splat state. */
   private static void cpuRun(Cache k, int from, int to, float[] sq, int minX, int minY, int maxX, int maxY, float worldAge, byte[] density, IsoChunk chunk) {
      Texture tex = typeTex[k.type[from]];
      if (tex == null) {
         return;
      }
      int cx = chunk.wx * 8, cy = chunk.wy * 8;
      FBORenderChunkManager m = FBORenderChunkManager.instance;
      float goX = m.getXOffset(), goY = m.getYOffset();
      float bx = (float)(m.renderChunk.chunk.wx * 8), by = (float)(m.renderChunk.chunk.wy * 8);
      float ts32 = (float)(32 * Core.tileScale), ts16 = (float)(16 * Core.tileScale), ts96 = (float)(96 * Core.tileScale);
      float hw = (float)tex.getWidth() / 2.0F * (float)Core.tileScale, hh = (float)tex.getHeight() / 2.0F * (float)Core.tileScale;
      float w = tex.getWidth(), h = tex.getHeight();
      for (int j = from; j < to; j++) {
         int idx = k.index[j];
         if (idx >= 1 && idx <= 10 && density[idx - 1] == 0) {
            continue;
         }
         float bxw = cx + k.x[j], byw = cy + k.y[j];
         if (bxw < minX || bxw > maxX || byw < minY || byw > maxY) {
            continue;
         }
         int s = zombie.core.math.PZMath.fastfloor(k.x[j]) + zombie.core.math.PZMath.fastfloor(k.y[j]) * 8;
         if (s < 0 || s >= 64 || sq[s * 4 + 3] < 0.5F) {
            continue;
         }
         float r = k.hr[j], g = k.hg[j], b = k.hb[j], a = 0.27F;
         float deltaAge = worldAge - k.age[j];
         if (deltaAge >= 0.0F && deltaAge < 72.0F) {
            float f = 1.0F - deltaAge / 72.0F;
            r *= 0.2F + f * 0.8F;
            g *= 0.2F + f * 0.8F;
            b *= 0.2F + f * 0.8F;
            a *= 0.25F + f * 0.75F;
         } else {
            r *= 0.2F;
            g *= 0.2F;
            b *= 0.2F;
            a *= 0.25F;
         }
         r *= sq[s * 4];
         g *= sq[s * 4 + 1];
         b *= sq[s * 4 + 2];
         // IsoSprite.renderBloodSplat: the position in the baked chunk's frame, truncated, centred, the texture's offsets
         float x = (float)(chunk.wx * 8) + k.x[j] - bx;
         float y = (float)(chunk.wy * 8) + k.y[j] - by;
         float sx = 0.0F;
         sx += x * ts32;
         sx -= y * ts32;
         float sy = 0.0F;
         sy += y * ts16;
         sy += x * ts16;
         sy += (0.0F - k.z[j]) * ts96;
         sx = (int)sx;
         sy = (int)sy;
         sx -= hw;
         sy -= hh;
         sx += goX;
         sy += goY;
         SpriteRenderer.instance.render(tex, sx + tex.offsetX, sy + tex.offsetY, w, h, r, g, b, a, null);
         cpuSprites++;
         splatsEmitted++;
      }
   }

   /** The fade splats: stock's own loop body (their counter runs down per draw), without the per-splat state. */
   private static final class FadeDraw {
      static void draw(ArrayList<IsoFloorBloodSplat> list, float worldAge, int playerIndex, SquareRules rules) {
         for (int i = 0; i < list.size(); i++) {
            IsoFloorBloodSplat b = list.get(i);
            Texture tex = typeTex[b.type];
            float[] h = FADE_HASH;
            hash(b, h);
            float r = h[0], g = h[1], bl = h[2], a = 0.27F;
            float deltaAge = worldAge - b.worldAge;
            if (deltaAge >= 0.0F && deltaAge < 72.0F) {
               float f = 1.0F - deltaAge / 72.0F;
               r *= 0.2F + f * 0.8F;
               g *= 0.2F + f * 0.8F;
               bl *= 0.2F + f * 0.8F;
               a *= 0.25F + f * 0.75F;
            } else {
               r *= 0.2F;
               g *= 0.2F;
               bl *= 0.2F;
               a *= 0.25F;
            }
            if (b.fade > 0) {
               a = a * (b.fade / (zombie.core.PerformanceSettings.getLockFPS() * 5.0F));
               if (--b.fade == 0) {
                  b.chunk.floorBloodSplatsFade.remove(b);
               }
            }
            float[] one = FADE_SQ;
            int lx = zombie.core.math.PZMath.fastfloor(b.x), ly = zombie.core.math.PZMath.fastfloor(b.y);
            if (lx < 0 || lx >= 8 || ly < 0 || ly >= 8) {
               continue;
            }
            java.util.Arrays.fill(one, 0F);
            rules.fill(b.chunk, zombie.core.math.PZMath.fastfloor(b.z), playerIndex, 1L << (lx + ly * 8), one);
            int s = (lx + ly * 8) * 4;
            if (one[s + 3] < 0.5F || tex == null) {
               continue;
            }
            r *= one[s];
            g *= one[s + 1];
            bl *= one[s + 2];
            FBORenderChunkManager m = FBORenderChunkManager.instance;
            float x = (float)(b.chunk.wx * 8) + b.x - (float)(m.renderChunk.chunk.wx * 8);
            float y = (float)(b.chunk.wy * 8) + b.y - (float)(m.renderChunk.chunk.wy * 8);
            float sx = zombie.iso.IsoUtils.XToScreen(x, y, b.z, 0);
            float sy = zombie.iso.IsoUtils.YToScreen(x, y, b.z, 0);
            sx = (int)sx;
            sy = (int)sy;
            sx -= (float)tex.getWidth() / 2.0F * (float)Core.tileScale;
            sy -= (float)tex.getHeight() / 2.0F * (float)Core.tileScale;
            sx += m.getXOffset();
            sy += m.getYOffset();
            SpriteRenderer.instance.render(tex, sx + tex.offsetX, sy + tex.offsetY, tex.getWidth(), tex.getHeight(), r, g, bl, a, null);
         }
      }

      private static final float[] FADE_SQ = new float[256];
      private static final float[] FADE_HASH = new float[3];
   }

   // ------------------------------------------------------------------------------------------------ gpu drawer

   private static void queueGpu(Cache k, int from, int to, float[] sq, int minX, int minY, int maxX, int maxY, float worldAge, int optionBloodDecals,
                                IsoChunk chunk) {
      Drawer d = Drawer.alloc();
      FBORenderChunkManager m = FBORenderChunkManager.instance;
      d.setup(k.type, k.data, k.row, k.serial, from, to, sq, minX, minY, maxX, maxY, worldAge, optionBloodDecals, chunk,
         m.renderChunk.chunk.wx * 8, m.renderChunk.chunk.wy * 8, m.getXOffset(), m.getYOffset(), false, 0F);
      SpriteRenderer.instance.drawGeneric(d);
      gpuDraws++;
      gpuInstances += to - from;
      splatsEmitted += to - from;
   }

   /** One chunk level's instanced splats into the bound chunk texture (a bake, or an append on top of a finished one). */
   public static final class Drawer extends TextureDraw.GenericDrawer {
      private static final ArrayList<Drawer> pool = new ArrayList<>();
      int row, from, to;
      float[] data;
      long serial;
      final float[] sq = new float[256];
      final float[] uv = new float[TYPES * 4], geom = new float[TYPES * 4];
      final int[] texIds = new int[TYPES];
      final int[] runFrom = new int[TYPES + 1], runTo = new int[TYPES + 1];
      int runs;
      float minX, minY, maxX, maxY, worldAge, cx, cy, bx, by, goX, goY;
      int mask, tileScale;
      boolean append, tint, plants;
      float minLevel;
      Texture expect;

      static Drawer alloc() {
         synchronized (pool) {
            if (!pool.isEmpty()) {
               return pool.remove(pool.size() - 1);
            }
         }
         return new Drawer();
      }

      void setup(int[] types, float[] data, int row, long serial, int from, int to, float[] sqIn, int minX, int minY, int maxX, int maxY, float worldAge,
                 int option, IsoChunk chunk, int bakeX8, int bakeY8, float goX, float goY, boolean append, float minLevel) {
         this.row = row;
         this.data = data;
         this.serial = serial;
         this.from = from;
         this.to = to;
         System.arraycopy(sqIn, 0, this.sq, 0, 256);
         System.arraycopy(typeUv, 0, this.uv, 0, TYPES * 4);
         System.arraycopy(typeGeom, 0, this.geom, 0, TYPES * 4);
         // texture runs (consecutive types on the same texture page share one draw)
         this.runs = 0;
         int j = from;
         int prevId = -1;
         while (j < to) {
            int t = types[j];
            Texture tex = typeTex[t];
            int id = tex == null || tex.getTextureId() == null ? 0 : tex.getTextureId().getID();
            int s = j;
            while (j < to && types[j] == t) {
               j++;
            }
            if (id == 0) {
               continue;
            }
            if (this.runs > 0 && id == prevId && this.runTo[this.runs - 1] == s) {
               this.runTo[this.runs - 1] = j;
            } else {
               this.texIds[this.runs] = id;
               this.runFrom[this.runs] = s;
               this.runTo[this.runs] = j;
               this.runs++;
            }
            prevId = id;
         }
         this.minX = minX;
         this.minY = minY;
         this.maxX = maxX;
         this.maxY = maxY;
         this.worldAge = worldAge;
         this.cx = (float)(chunk.wx * 8);
         this.cy = (float)(chunk.wy * 8);
         this.bx = (float)bakeX8;
         this.by = (float)bakeY8;
         this.goX = goX;
         this.goY = goY;
         byte[] density = zombie.iso.IsoChunk.renderByIndex[option - 1];
         int msk = 0;
         for (int i = 0; i < 10; i++) {
            if (density[i] != 0) {
               msk |= 1 << i;
            }
         }
         this.mask = msk;
         this.tileScale = Core.tileScale;
         this.append = append;
         this.minLevel = minLevel;
         this.tint = append && Config.DEV_BLOOD_APPEND_TINT;
      }

      @Override
      public void render() {
         Gpu.draw(this);
      }

      @Override
      public void postRender() {
         this.data = null;
         this.expect = null;
         this.plants = false;
         synchronized (pool) {
            if (pool.size() < 256) {
               pool.add(this);
            }
         }
      }
   }

   /** The shared splat atlas: RGBA32F, 2048 texels a row, one row per bloody chunk. */
   static final class Atlas {
      private static final java.util.ArrayDeque<Integer> free = new java.util.ArrayDeque<>();
      static final int SCRATCH_ROW = 0; // the appends' splats, uploaded before each append draw
      private static int nextRow = 1;
      static final int MAX_ROWS = 2048;

      static synchronized int allocRow() {
         if (!free.isEmpty()) {
            return free.pop();
         }
         if (nextRow >= MAX_ROWS) {
            return -1;
         }
         return nextRow++;
      }

      static synchronized void freeRow(int row) {
         free.push(row);
      }

      static synchronized int rowsInUse() {
         return nextRow;
      }
   }

   static final String VERT = String.join("\n",
         "#version 400", // precise (no fused multiply-add: the truncated screen position must match the CPU's)
         "uniform sampler2D uData;",
         "uniform int uRow;",
         "uniform int uBase;",
         "uniform mat4 uMVP;",
         "uniform vec4 uRect;   // minX, minY, maxX, maxY (world squares)",
         "uniform vec4 uOrigin; // the splats' chunk x8, y8; the baked chunk x8, y8",
         "uniform vec4 uGo;     // goX, goY, tile scale, world age",
         "uniform int uMask;    // the density option's 10 index bits",
         "uniform vec4 uUv[21];",
         "uniform vec4 uGeom[21]; // w/2 ts - offsetX, h/2 ts - offsetY, w, h",
         "uniform vec4 uSq[64];   // light factor, drawn",
         "uniform vec4 uAppend;   // x 1 = an append (depth from the floor plane), y the texture's lowest level, z K, w bias (depth units)",
         "uniform int uTint;      // dev: appended splats drawn green",
         "out vec2 vUv;",
         "flat out vec4 vCol;",
         "void main() {",
         "   int i = uBase + gl_InstanceID;",
         "   vec4 a = texelFetch(uData, ivec2(i * 2, uRow), 0);",
         "   vec4 b = texelFetch(uData, ivec2(i * 2 + 1, uRow), 0);",
         "   int pk = int(a.w + 0.5);",
         "   int type = pk & 31;",
         "   int idx = pk >> 5;",
         "   bool vis = !(idx >= 1 && idx <= 10 && ((uMask >> (idx - 1)) & 1) == 0);",
         "   precise float wx = uOrigin.x + a.x;",
         "   precise float wy = uOrigin.y + a.y;",
         "   vis = vis && !(wx < uRect.x) && !(wx > uRect.z) && !(wy < uRect.y) && !(wy > uRect.w);",
         "   int s = int(floor(a.x)) + int(floor(a.y)) * 8;",
         "   vec4 sq = uSq[clamp(s, 0, 63)];",
         "   vis = vis && s >= 0 && s < 64 && sq.w > 0.5;",
         "   if (!vis) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); vUv = vec2(0.0); vCol = vec4(0.0); return; }",
         "   // IsoSprite.renderBloodSplat in the baked chunk's frame: truncated screen position, centred, the texture's offsets",
         "   precise float x = wx - uOrigin.z;",
         "   precise float y = wy - uOrigin.w;",
         "   float ts = uGo.z;",
         "   precise float sx = x * (32.0 * ts) - y * (32.0 * ts);",
         "   precise float syf = y * (16.0 * ts) + x * (16.0 * ts) + (0.0 - b.w) * (96.0 * ts);",
         "   float sy = trunc(syf);",
         "   sx = trunc(sx);",
         "   vec4 g = uGeom[type];",
         "   vec4 uv = uUv[type];",
         "   int c = gl_VertexID;",
         "   float cxq = (c == 1 || c == 2) ? 1.0 : 0.0;",
         "   float cyq = (c >= 2) ? 1.0 : 0.0;",
         "   float X = sx - g.x + uGo.x + g.z * cxq;",
         "   float Y = sy - g.y + uGo.y + g.w * cyq;",
         "   gl_Position = uMVP * vec4(X, Y, 0.0, 1.0);",
         "   if (uAppend.x > 0.5) {",
         "      // the texture's depth of the floor under this corner (ChunkAo: K (20 - (x + y) - 2 (z - lowest level)) in the texture's",
         "      // chunk frame); the splat lies flat, so one unit of x + y per 16 ts rows down the quad",
         "      float d0 = uAppend.z * (20.0 - x - y - 2.0 * (b.w - uAppend.y));",
         "      float d = d0 - (Y - (syf + uGo.y)) / (16.0 * ts) * uAppend.z - uAppend.w;",
         "      gl_Position.z = (d * 2.0 - 1.0) * gl_Position.w;",
         "   } else {",
         "      gl_Position.z = 0.0;",
         "   }",
         "   vUv = vec2(mix(uv.x, uv.z, cxq), mix(uv.y, uv.w, cyq));",
         "   // stock's colour: hash, age (72 game hours), the square's light; packed to 8 bits as Color.colorToABGR truncates",
         "   vec3 col = b.xyz;",
         "   float al = 0.27;",
         "   float deltaAge = uGo.w - a.z;",
         "   if (deltaAge >= 0.0 && deltaAge < 72.0) {",
         "      float f = 1.0 - deltaAge / 72.0;",
         "      col *= 0.2 + f * 0.8;",
         "      al *= 0.25 + f * 0.75;",
         "   } else {",
         "      col *= 0.2;",
         "      al *= 0.25;",
         "   }",
         "   col *= sq.rgb;",
         "   vec4 q = clamp(vec4(col, al), 0.0, 1.0);",
         "   vCol = floor(q * 255.0) / 255.0;",
         "   if (uTint == 1) vCol = vec4(-1.0);",
         "}");

   static final String FRAG = String.join("\n",
         "#version 330",
         "uniform sampler2D DIFFUSE;",
         "uniform sampler2D uPlantTex; // bloodAppendPlants: the plants over the splat (premultiplied colour, coverage), texel for texel",
         "uniform int uPlants;",
         "in vec2 vUv;",
         "flat in vec4 vCol;",
         "out vec4 fragColor;",
         "void main() {",
         "   vec4 c = texture(DIFFUSE, vUv, 0.0) * vCol;",
         "   if (vCol.a < 0.0) c = vec4(0.0, 1.0, 0.0, texture(DIFFUSE, vUv, 0.0).a > 0.0 ? 0.85 : 0.0); // dev tint: appended splats",
         "   if (c.a == 0.0) discard;",
         "   if (uPlants == 1) {",
         "      // under the plants: blended with (ONE, ONE_MINUS_SRC_ALPHA) this gives T (1 - a) + P.rgb a + (1 - P.a) a blood",
         "      vec4 P = texelFetch(uPlantTex, ivec2(gl_FragCoord.xy), 0);",
         "      fragColor = vec4(P.rgb * c.a + (1.0 - P.a) * c.a * c.rgb, c.a);",
         "      return;",
         "   }",
         "   fragColor = c;",
         "}");

   /** Render thread. */
   static final class Gpu {
      private static int program, tex, texRows, vao;
      private static int uData, uRow, uBase, uMVP, uRect, uOrigin, uGo, uMask, uUv, uGeom, uSq, uAppend, uTint, uDiffuse, uPlants, uPlantTex;
      private static final long[] rowSerial = new long[Atlas.MAX_ROWS];
      private static FloatBuffer upload;
      private static final FloatBuffer mat = BufferUtils.createFloatBuffer(16);
      private static final FloatBuffer vec64 = BufferUtils.createFloatBuffer(256);
      private static final FloatBuffer vecT = BufferUtils.createFloatBuffer(TYPES * 4);
      private static final Matrix4f mvp = new Matrix4f();

      private static boolean init() {
         if (program != 0) {
            return true;
         }
         if (gpuFailed) {
            return false;
         }
         program = Shaders.program("blood decals", VERT, FRAG);
         if (program == 0) {
            gpuFailed = true;
            Log.warn("blood decals: the instanced program did not build, sprites from now on");
            return false;
         }
         uData = GL20.glGetUniformLocation(program, "uData");
         uRow = GL20.glGetUniformLocation(program, "uRow");
         uBase = GL20.glGetUniformLocation(program, "uBase");
         uMVP = GL20.glGetUniformLocation(program, "uMVP");
         uRect = GL20.glGetUniformLocation(program, "uRect");
         uOrigin = GL20.glGetUniformLocation(program, "uOrigin");
         uGo = GL20.glGetUniformLocation(program, "uGo");
         uMask = GL20.glGetUniformLocation(program, "uMask");
         uUv = GL20.glGetUniformLocation(program, "uUv");
         uGeom = GL20.glGetUniformLocation(program, "uGeom");
         uSq = GL20.glGetUniformLocation(program, "uSq");
         uAppend = GL20.glGetUniformLocation(program, "uAppend");
         uTint = GL20.glGetUniformLocation(program, "uTint");
         uPlants = GL20.glGetUniformLocation(program, "uPlants");
         uPlantTex = GL20.glGetUniformLocation(program, "uPlantTex");
         uDiffuse = GL20.glGetUniformLocation(program, "DIFFUSE");
         vao = GL30.glGenVertexArrays();
         Log.info("blood decals: instanced splat program " + program);
         return true;
      }

      private static void ensureTexture(int rows) {
         int want = Math.max(64, texRows);
         while (want < rows) {
            want *= 2;
         }
         want = Math.min(want, Atlas.MAX_ROWS);
         if (tex != 0 && want == texRows) {
            return;
         }
         if (tex != 0) {
            GL11.glDeleteTextures(tex);
         }
         tex = GL11.glGenTextures();
         GL13.glActiveTexture(GL13.GL_TEXTURE1);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, ROW_TEXELS, want, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer)null);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         texRows = want;
         java.util.Arrays.fill(rowSerial, 0L);
         Log.info("blood decals: splat atlas " + ROW_TEXELS + "x" + want + " RGBA32F");
      }

      static void draw(Drawer d) {
         if (d.runs == 0 || d.row < 0 || d.data == null) {
            return;
         }
         try {
            if (!init()) {
               return;
            }
            if (d.expect != null && !BloodDecals.boundIs(d.expect)) {
               appendsRefused++;
               return;
            }
            ensureTexture(Math.max(d.row + 1, Atlas.rowsInUse()));
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            if (rowSerial[d.row] != d.serial) {
               int n = d.data.length / FLOATS;
               int texels = Math.min(ROW_TEXELS, n * 2);
               if (upload == null || upload.capacity() < texels * 4) {
                  upload = BufferUtils.createFloatBuffer(ROW_TEXELS * 4);
               }
               upload.clear();
               upload.put(d.data, 0, texels * 4).flip();
               GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, d.row, texels, 1, GL11.GL_RGBA, GL11.GL_FLOAT, upload);
               rowSerial[d.row] = d.serial;
               rowUploads++;
            }
            GL20.glUseProgram(program);
            GL20.glUniform1i(uData, 1);
            GL20.glUniform1i(uDiffuse, 0);
            GL20.glUniform1i(uRow, d.row);
            mvp.set(Core.getInstance().projectionMatrixStack.peek()).mul(Core.getInstance().modelViewMatrixStack.peek());
            mat.clear();
            mvp.get(mat);
            GL20.glUniformMatrix4fv(uMVP, false, mat);
            GL20.glUniform4f(uRect, d.minX, d.minY, d.maxX, d.maxY);
            GL20.glUniform4f(uOrigin, d.cx, d.cy, d.bx, d.by);
            GL20.glUniform4f(uGo, d.goX, d.goY, d.tileScale, d.worldAge);
            GL20.glUniform1i(uMask, d.mask);
            vecT.clear();
            vecT.put(d.uv).flip();
            GL20.glUniform4fv(uUv, vecT);
            vecT.clear();
            vecT.put(d.geom).flip();
            GL20.glUniform4fv(uGeom, vecT);
            vec64.clear();
            vec64.put(d.sq).flip();
            GL20.glUniform4fv(uSq, vec64);
            GL20.glUniform4f(uAppend, d.append ? 1F : 0F, d.minLevel, K, K * Config.BLOOD_APPEND_BIAS_PCT / 100F);
            GL20.glUniform1i(uTint, d.tint ? 1 : 0);
            boolean plants = d.plants && PlantLayer.tex != 0;
            GL20.glUniform1i(uPlants, plants ? 1 : 0);
            GL20.glUniform1i(uPlantTex, 2);
            if (plants) {
               GL13.glActiveTexture(GL13.GL_TEXTURE2);
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, PlantLayer.tex);
            }
            if (d.append) {
               GL11.glEnable(GL11.GL_DEPTH_TEST);
               GL11.glDepthFunc(GL11.GL_LEQUAL);
               GL11.glDepthMask(false);
               GL11.glEnable(GL32.GL_DEPTH_CLAMP);
               GL11.glEnable(GL11.GL_BLEND);
               if (plants) {
                  GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
               } else {
                  GL14.glBlendFuncSeparate(770, 771, 773, 1);
               }
            }
            GL30.glBindVertexArray(vao);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            for (int r = 0; r < d.runs; r++) {
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, d.texIds[r]);
               GL20.glUniform1i(uBase, d.runFrom[r]);
               GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, d.runTo[r] - d.runFrom[r]);
            }
            GL30.glBindVertexArray(0);
            if (d.append) {
               GL11.glDisable(GL32.GL_DEPTH_CLAMP);
               GL11.glDepthMask(true);
               appendsDrawn++;
            }
         } catch (Throwable t) {
            gpuFailed = true;
            Log.warn("blood decals: gpu draw failed, sprites from now on: " + t);
         } finally {
            GL20.glUseProgram(0);
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            Texture.lastTextureID = -1;
            GLStateRenderThread.restore();
            zombie.core.ShaderHelper.forgetCurrentlyBound();
            SpriteRenderer.ringBuffer.restoreVbos = true;
            SpriteRenderer.ringBuffer.restoreBoundTextures = true;
         }
      }
   }

   // ------------------------------------------------------------------------------------------------ appends

   /** New splats of one source chunk for one texture (target chunk, level group). */
   static final class Append {
      IsoChunk target, source;
      int level;
      long plantMask; // the target's squares whose plants are drawn again over the new splats
      final ArrayList<IsoFloorBloodSplat> splats = new ArrayList<>(4);
   }

   private static final ArrayList<Append> appends = new ArrayList<>();
   private static final ArrayList<Append> appendPool = new ArrayList<>();
   /** bloodSettleSec: first append time (ms) per chunk level group (index level + 32), 0 = none pending */
   private static final java.util.IdentityHashMap<IsoChunk, long[]> settle = new java.util.IdentityHashMap<>();
   private static long appendSerial = Long.MIN_VALUE / 2;

   /**
    * Game thread, IsoChunk.addBloodSplat right after the splat joined the queue (instead of marking its level
    * DIRTY_BLOOD): true = handled (queued for this frame's append, or nothing will ever draw it), false = stock's re-bake.
    */
   public static boolean added(IsoChunk c, IsoFloorBloodSplat b) {
      if (!APPEND) {
         return false;
      }
      if (ChunkAo.enabled()) {
         appendsAo++;
         return false; // ambient occlusion / sun shadows multiply a texture's colour after its bake, sometimes frames later: an
                       // appended splat would miss that multiply or get it twice, so the level re-bakes as stock
      }
      int option = Core.getInstance().getOptionBloodDecals();
      if (option <= 0 || option > 10 || !zombie.debug.DebugOptions.instance.terrain.renderTiles.bloodDecals.getValue()
            || b.index >= 1 && b.index <= 10 && zombie.iso.IsoChunk.renderByIndex[option - 1][b.index - 1] == 0 || b.type < 0 || b.type >= TYPES) {
         appendsHidden++; // stock re-bakes the level to draw nothing new
         return true;
      }
      if (zombie.core.math.PZMath.fastfloor(b.z) < -32 || zombie.core.math.PZMath.fastfloor(b.z) > 31) {
         return false;
      }
      zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
      int foot = Config.BLOOD_APPEND_VEGETATION ? FOOT_CLEAR : footprint(cell, c.wx * 8 + b.x, c.wy * 8 + b.y, zombie.core.math.PZMath.fastfloor(b.z), b.type);
      if (foot == FOOT_UNSAFE || foot == FOOT_PLANTS && (!Config.BLOOD_APPEND_PLANTS || redraw == null)) {
         appendsUnsafe++;
         // stock bakes those over the blood and the depth test cannot tell them from the floor, so the level re-bakes; a
         // short delay lets the rest of the spray (a hit throws splats over several frames) share that one re-bake
         return deferRebake(c, zombie.core.math.PZMath.fastfloor(b.z));
      }
      if (foot == FOOT_PLANTS) {
         appendsPlants++;
      }
      float wx = c.wx * 8 + b.x, wy = c.wy * 8 + b.y;
      int level = zombie.core.math.PZMath.fastfloor(b.z);
      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            IsoChunk t = dx == 0 && dy == 0 ? c : cell == null ? null : cell.getChunk(c.wx + dx, c.wy + dy);
            if (t == null || level < t.minLevel || level > t.maxLevel) {
               continue;
            }
            // the rectangle t's bake draws c's splats in (renderOneLevel_Blood: its own square area, one tile over for a neighbour)
            int minX = t == c ? t.wx * 8 : t.wx * 8 - 1, minY = t == c ? t.wy * 8 : t.wy * 8 - 1;
            int maxX = t == c ? (t.wx + 1) * 8 : (t.wx + 1) * 8 + 1, maxY = t == c ? (t.wy + 1) * 8 : (t.wy + 1) * 8 + 1;
            if (wx < minX || wx > maxX || wy < minY || wy > maxY) {
               continue;
            }
            Append a = null;
            // the recent entries only (splats come in bursts per chunk): a longer list just gets a second entry, drawn after the first
            for (int i = appends.size() - 1; i >= Math.max(0, appends.size() - 32); i--) {
               Append e = appends.get(i);
               if (e.target == t && e.source == c && FBORenderLevelsMin(e.level) == FBORenderLevelsMin(level)) {
                  a = e;
                  break;
               }
            }
            if (a == null) {
               a = appendPool.isEmpty() ? new Append() : appendPool.remove(appendPool.size() - 1);
               a.target = t;
               a.source = c;
               a.level = level;
               a.plantMask = 0L;
               a.splats.clear();
               appends.add(a);
            }
            a.splats.add(b);
            appendsQueued++;
            for (int q = 0; q < (foot == FOOT_PLANTS ? plantSqN : 0); q++) {
               int px = plantSq[q * 2] - t.wx * 8, py = plantSq[q * 2 + 1] - t.wy * 8;
               if (px >= 0 && px < 8 && py >= 0 && py < 8) {
                  a.plantMask |= 1L << (px + py * 8); // the target's own plants under the splat (a texture holds its chunk's objects only)
               }
            }
         }
      }
      return true;
   }

   static final int FOOT_CLEAR = 0, FOOT_PLANTS = 1, FOOT_UNSAFE = 2;
   /** FOOT_PLANTS: the world squares (x, y) under the splat whose plants the append draws again over it */
   private static int[] plantSq = new int[64];
   private static int plantSqN;

   /**
    * What the bake draws after the blood, flat on the floor, on the squares under the splat's sprite. CLEAR: nothing (the
    * depth test keeps walls, furniture and trees in front of an appended splat). PLANTS: only grass, bushes and
    * floor-attached plants (MinusFloor objects at the floor's depth: stock draws them over the blood), alone on their
    * squares: the append draws them again over the splat (plantSq). UNSAFE: a body, an item, flattened vegetation, plants
    * sharing a square with other objects, or low-quality puddles baked into the texture: the level re-bakes.
    */
   private static int footprint(zombie.iso.IsoCell cell, float wx, float wy, int z, int type) {
      plantSqN = 0;
      if (cell == null) {
         return FOOT_UNSAFE;
      }
      if (zombie.core.PerformanceSettings.puddlesQuality == 2 && zombie.iso.IsoPuddles.getInstance().shouldRenderPuddles()) {
         return FOOT_UNSAFE; // low-quality puddles are baked into the texture after the blood
      }
      Texture tex = typesReady ? typeTex[type] : null;
      int ts = Math.max(1, Core.tileScale);
      // the sprite's half extent in squares: across (x - y) 32 ts px a unit, down (x + y) 16 ts px a unit
      float hw = tex == null ? 64F : tex.getWidth() * 0.5F * ts, hh = tex == null ? 32F : tex.getHeight() * 0.5F * ts;
      int r = (int)Math.ceil((hw / (32F * ts) + hh / (16F * ts)) * 0.5F) + 1;
      int cx = zombie.core.math.PZMath.fastfloor(wx), cy = zombie.core.math.PZMath.fastfloor(wy);
      int playerIndex = IsoCamera.frameState.playerIndex;
      int result = FOOT_CLEAR;
      for (int y = cy - r; y <= cy + r; y++) {
         for (int x = cx - r; x <= cx + r; x++) {
            zombie.iso.IsoGridSquare sq = cell.getGridSquare(x, y, z);
            if (sq == null) {
               continue;
            }
            if (!sq.getStaticMovingObjects().isEmpty() || !sq.getWorldObjects().isEmpty()) {
               return FOOT_UNSAFE;
            }
            boolean plants = false, other = false;
            java.util.List<zombie.iso.IsoObject> objects = sq.getObjects();
            for (int i = 0; i < objects.size(); i++) {
               zombie.iso.IsoObject o = objects.get(i);
               zombie.iso.sprite.IsoSprite sp = o.sprite;
               if (sp != null && (sp.solidfloor || sp.renderLayer == 1) && !(o instanceof zombie.iso.objects.IsoFire)) {
                  continue; // the floor pass (FBORenderCell.isObjectRenderLayer_Floor): floors, road markings, rugs, drawn before the blood
               }
               zombie.iso.fboRenderChunk.ObjectRenderLayer layer = o.getRenderInfo(playerIndex).layer;
               if (layer == zombie.iso.fboRenderChunk.ObjectRenderLayer.Vegetation || layer == zombie.iso.fboRenderChunk.ObjectRenderLayer.Corpse
                     || layer == zombie.iso.fboRenderChunk.ObjectRenderLayer.WorldInventoryObject) {
                  return FOOT_UNSAFE;
               }
               boolean plant = sp != null && (sp.isBush || sp.canBeRemoved || sp.attachedFloor) && !(o instanceof zombie.iso.objects.IsoTree);
               if (plant && layer == zombie.iso.fboRenderChunk.ObjectRenderLayer.MinusFloor) {
                  plants = true;
               } else if (layer == zombie.iso.fboRenderChunk.ObjectRenderLayer.MinusFloor || layer == zombie.iso.fboRenderChunk.ObjectRenderLayer.MinusFloorSE) {
                  other = true;
               }
            }
            if (plants && other) {
               return FOOT_UNSAFE; // a plant drawn before or after a fence on the same square: redrawn it could cover the fence's foot
            }
            if (plants) {
               if (plantSqN * 2 + 2 > plantSq.length) {
                  plantSq = java.util.Arrays.copyOf(plantSq, plantSq.length * 2);
               }
               plantSq[plantSqN * 2] = x;
               plantSq[plantSqN * 2 + 1] = y;
               plantSqN++;
               result = FOOT_PLANTS;
            }
         }
      }
      return result;
   }

   private static int FBORenderLevelsMin(int level) {
      return zombie.iso.fboRenderChunk.FBORenderLevels.calculateMinLevel(level);
   }

   /**
    * Game thread, FBORenderCell after the bakes of the frame, before the composite (where treeAppend flushes): each
    * queued append drawn into its texture inside a bake's begin / end (the texture bound with its depth, mipmaps
    * regenerated after); a texture that is dirty will bake the splat itself; one that is missing, off screen or not in
    * this frame's composite re-bakes as stock would. Then the due settle re-bakes.
    */
   public static void flush(int playerIndex, float zoom, SquareRules r, PlantRedraw pr) {
      if (!APPEND) {
         return;
      }
      rules = r;
      redraw = pr;
      flushRebakes();
      if (!appends.isEmpty()) {
         FBORenderChunkManager mgr = FBORenderChunkManager.instance;
         float worldAge = (float)GameTime.getInstance().getWorldAgeHours();
         int option = Core.getInstance().getOptionBloodDecals();
         long now = System.currentTimeMillis();
         boolean typesOk = types();
         for (int i = 0; i < appends.size(); i++) {
            Append a = appends.get(i);
            IsoChunk t = a.target;
            for (int p = 0; p < 4; p++) {
               if (p != playerIndex && zombie.characters.IsoPlayer.players[p] != null) {
                  t.getRenderLevels(p).invalidateLevel(a.level, 1L); // split screen: the other views re-bake as stock
               }
            }
            zombie.iso.fboRenderChunk.FBORenderLevels levels = t.getRenderLevels(playerIndex);
            zombie.iso.fboRenderChunk.FBORenderChunk rc = levels.getFBOForLevel(a.level, zoom);
            if (rc == null || levels.isDirty(a.level, zoom)) {
               continue; // its next bake draws the splat from the queue
            }
            if (!typesOk || !useGpu() || rc.tex == null || !levels.isOnScreen(a.level) || !mgr.toRenderThisFrame.contains(rc) || a.level < rc.getMinLevel()
                  || a.level > rc.getTopLevel()) {
               levels.invalidateLevel(a.level, 1L);
               appendsFellBack++;
               continue;
            }
            Drawer d = appendDrawer(a, rc, worldAge, option, playerIndex);
            if (d == null) {
               levels.invalidateLevel(a.level, 1L);
               appendsFellBack++;
               continue;
            }
            SpriteRenderer.instance.glDoEndFrame();
            SpriteRenderer.instance.glDoStartFrameFlipY(rc.w, rc.h, rc.highRes ? 1.0F : 0.0F, playerIndex);
            rc.beginMainThread(false);
            if (a.plantMask != 0L && redraw != null) {
               // the plants stock bakes over the blood, drawn once more but into a cleared layer sharing the texture's depth
               // (their premultiplied colour and coverage, P); the splat is then composited under them: T (1 - a) +
               // P.rgb a + (1 - P.a) a blood, stock's floor -> blood -> plants order without drawing a plant twice
               SpriteRenderer.instance.drawGeneric(PlantLayer.begin(rc));
               redraw.redraw(t, rc, a.level, a.plantMask, playerIndex);
               SpriteRenderer.instance.drawGeneric(PlantLayer.END);
               d.plants = true;
               plantRedraws++;
            }
            SpriteRenderer.instance.drawGeneric(d);
            rc.endMainThread();
            SpriteRenderer.instance.glDoEndFrame();
            SpriteRenderer.instance.glDoStartFrame(Core.getInstance().getScreenWidth(), Core.getInstance().getScreenHeight(), Core.getInstance().getCurrentPlayerZoom(), playerIndex);
            if (Config.BLOOD_SETTLE_SEC > 0) {
               long[] times = settle.computeIfAbsent(t, x -> new long[64]);
               int li = rc.getMinLevel() + 32;
               if (li >= 0 && li < 64 && times[li] == 0L) {
                  times[li] = now;
               }
            }
         }
         for (int i = 0; i < appends.size(); i++) {
            Append a = appends.get(i);
            a.target = null;
            a.source = null;
            a.splats.clear();
            appendPool.add(a);
         }
         appends.clear();
      }
      if (Config.BLOOD_SETTLE_SEC > 0 && !settle.isEmpty()) {
         long now = System.currentTimeMillis();
         long due = Config.BLOOD_SETTLE_SEC * 1000L;
         java.util.Iterator<java.util.Map.Entry<IsoChunk, long[]>> it = settle.entrySet().iterator();
         while (it.hasNext()) {
            java.util.Map.Entry<IsoChunk, long[]> e = it.next();
            long[] times = e.getValue();
            boolean any = false;
            for (int li = 0; li < 64; li++) {
               if (times[li] == 0L) {
                  continue;
               }
               if (now - times[li] >= due) {
                  times[li] = 0L;
                  e.getKey().invalidateRenderChunkLevel(li - 32, 1L);
                  settleRebakes++;
               } else {
                  any = true;
               }
            }
            if (!any) {
               it.remove();
            }
         }
      }
   }

   /** bloodRebakeCoalesceMs: blood-only re-bakes due per chunk level (index level + 32, ms; 0 = none) */
   private static final java.util.IdentityHashMap<IsoChunk, long[]> rebakeDue = new java.util.IdentityHashMap<>();
   public static long rebakesDeferred, rebakesCoalesced, rebakesIssued;

   private static boolean deferRebake(IsoChunk c, int level) {
      if (Config.BLOOD_REBAKE_COALESCE_MS <= 0 || level < -32 || level > 31) {
         return false;
      }
      long[] due = rebakeDue.computeIfAbsent(c, x -> new long[64]);
      if (due[level + 32] != 0L) {
         rebakesCoalesced++;
      } else {
         due[level + 32] = System.currentTimeMillis() + Config.BLOOD_REBAKE_COALESCE_MS;
         rebakesDeferred++;
      }
      return true;
   }

   private static void flushRebakes() {
      if (rebakeDue.isEmpty()) {
         return;
      }
      long now = System.currentTimeMillis();
      java.util.Iterator<java.util.Map.Entry<IsoChunk, long[]>> it = rebakeDue.entrySet().iterator();
      while (it.hasNext()) {
         java.util.Map.Entry<IsoChunk, long[]> e = it.next();
         long[] due = e.getValue();
         boolean any = false;
         for (int li = 0; li < 64; li++) {
            if (due[li] == 0L) {
               continue;
            }
            if (now >= due[li]) {
               due[li] = 0L;
               e.getKey().invalidateRenderChunkLevel(li - 32, 1L);
               rebakesIssued++;
            } else {
               any = true;
            }
         }
         if (!any) {
            it.remove();
         }
      }
   }

   /** The quads of one append in the texture's frame (FBORenderChunkManager.beginRenderChunkLevel's offsets), or null. */
   private static Drawer appendDrawer(Append a, zombie.iso.fboRenderChunk.FBORenderChunk rc, float worldAge, int option, int playerIndex) {
      int n = a.splats.size();
      if (n == 0 || option <= 0) {
         return null;
      }
      n = Math.min(n, ROW_SPLATS);
      float[] data = new float[n * FLOATS];
      int[] types = new int[n];
      float[] h = HASH;
      for (int i = 0; i < n; i++) {
         IsoFloorBloodSplat b = a.splats.get(i);
         hash(b, h);
         pack(b, h[0], h[1], h[2], data, i * FLOATS);
         types[i] = b.type;
      }
      // the source chunk's squares under the new splats: cutaway, blacked out, light (pixelLight bakes a texture's own
      // chunk white: the composite lights it per pixel)
      long mask = 0L;
      for (int i = 0; i < n; i++) {
         IsoFloorBloodSplat b = a.splats.get(i);
         int sx = zombie.core.math.PZMath.fastfloor(b.x), sy = zombie.core.math.PZMath.fastfloor(b.y);
         if (sx >= 0 && sx < 8 && sy >= 0 && sy < 8) {
            mask |= 1L << (sx + sy * 8);
         }
      }
      float[] sq = APPEND_SQ;
      java.util.Arrays.fill(sq, 0F);
      if (rules == null) {
         return null;
      }
      rules.fill(a.source, a.level, playerIndex, mask, sq);
      if (PixelLight.ACTIVE && a.source == a.target) {
         for (int i = 0; i < 64; i++) {
            if (sq[i * 4 + 3] > 0.5F) {
               sq[i * 4] = sq[i * 4 + 1] = sq[i * 4 + 2] = 1F;
            }
         }
      }
      IsoChunk t = a.target;
      float goX = rc.w / 2.0F;
      float goY = (rc.getTopLevel() - rc.getMinLevel() + 1) * zombie.iso.fboRenderChunk.FBORenderChunk.PIXELS_PER_LEVEL
         + rc.getMinLevel() * zombie.iso.fboRenderChunk.FBORenderChunk.PIXELS_PER_LEVEL
         + zombie.iso.fboRenderChunk.FBORenderLevels.extraHeightForJumboTrees(rc.getMinLevel(), rc.getTopLevel());
      boolean same = a.source == t;
      int minX = same ? t.wx * 8 : t.wx * 8 - 1, minY = same ? t.wy * 8 : t.wy * 8 - 1;
      int maxX = same ? (t.wx + 1) * 8 : (t.wx + 1) * 8 + 1, maxY = same ? (t.wy + 1) * 8 : (t.wy + 1) * 8 + 1;
      Drawer d = Drawer.alloc();
      d.setup(types, data, Atlas.SCRATCH_ROW, ++appendSerial, 0, n, sq, minX, minY, maxX, maxY, worldAge, option, a.source, t.wx * 8, t.wy * 8, goX, goY,
         true, rc.getMinLevel());
      d.expect = rc.tex;
      return d;
   }

   private static final float[] APPEND_SQ = new float[256];
   /** FBORenderCell's square rules, handed over by its first bake (appends run outside any bake) */
   static volatile SquareRules rules;
   /** FBORenderCell: draws a chunk level's plants on the given squares into its texture again, as its bake does */
   static volatile PlantRedraw redraw;

   public interface PlantRedraw {
      void redraw(IsoChunk target, zombie.iso.fboRenderChunk.FBORenderChunk rc, int level, long squares, int playerIndex);
   }

   /** IsoChunk reset: its pending appends and settle entry go. */
   static void forget(IsoChunk c) {
      settle.remove(c);
      rebakeDue.remove(c);
      for (int i = appends.size() - 1; i >= 0; i--) {
         Append a = appends.get(i);
         if (a.target == c || a.source == c) {
            appends.remove(i);
         }
      }
   }

   /**
    * bloodAppendPlants, render thread: a cleared RGBA8 layer the size of a chunk texture, attached with that texture's own
    * depth, that the plants over a new splat are drawn into (the game's object path; its premultiplied blend and
    * coverage alpha make the layer hold P = plant colour x coverage, coverage). The splat drawer then reads it texel for
    * texel. Foliage sway may attach its attribute texture to the bound framebuffer while a plant draws: detached after.
    */
   static final class PlantLayer {
      static int tex, fbo, w, h, prevFbo;

      static TextureDraw.GenericDrawer begin(zombie.iso.fboRenderChunk.FBORenderChunk rc) {
         BEGIN.rc = rc;
         return BEGIN;
      }

      private static final Begin BEGIN = new Begin();

      static final class Begin extends TextureDraw.GenericDrawer {
         zombie.iso.fboRenderChunk.FBORenderChunk rc;

         @Override
         public void render() {
            zombie.iso.fboRenderChunk.FBORenderChunk r = this.rc;
            if (r == null || r.tex == null || r.depth == null) {
               tex = 0;
               return;
            }
            try {
               int tw = r.tex.getWidthHW(), th = r.tex.getHeightHW();
               if (tex == 0 || tw != w || th != h) {
                  if (tex != 0) {
                     GL11.glDeleteTextures(tex);
                  }
                  w = tw;
                  h = th;
                  tex = GL11.glGenTextures();
                  GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
                  GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer)null);
                  GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                  GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                  GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                  Texture.lastTextureID = -1;
               }
               if (fbo == 0) {
                  fbo = GL30.glGenFramebuffers();
               }
               prevFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
               GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
               GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
               GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, r.depth.getID(), 0);
               GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
               boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
               GL11.glDisable(GL11.GL_SCISSOR_TEST);
               GL11.glClearColor(0F, 0F, 0F, 0F);
               GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
               if (scissor) {
                  GL11.glEnable(GL11.GL_SCISSOR_TEST);
               }
            } catch (Throwable t) {
               tex = 0;
               Log.warn("blood decals: plant layer failed: " + t);
            }
         }
      }

      static final TextureDraw.GenericDrawer END = new TextureDraw.GenericDrawer() {
         @Override
         public void render() {
            if (fbo == 0) {
               return;
            }
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, 0, 0); // foliage sway's, if it attached one
            GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
         }
      };
   }

   /** Render thread: is the texture the bound draw framebuffer's colour attachment (TreeBake's check)? */
   static boolean boundIs(Texture expect) {
      int bound;
      if (Config.GL_NO_SYNC) {
         zombie.iso.fboRenderChunk.FBORenderChunk cur = FBORenderChunkManager.instance.renderThreadCurrent;
         bound = cur == null || cur.tex == null || cur.tex.getTextureId() == null ? -1 : cur.tex.getTextureId().getID();
      } else {
         bound = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
      }
      return expect.getTextureId() != null && bound == expect.getTextureId().getID();
   }
}
