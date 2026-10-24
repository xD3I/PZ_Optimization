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
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunk;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoFloorBloodSplat;
import zombie.iso.IsoGridSquare;

/**
 * Wet blood (2026-09-29, Enhancements tab, off by default; docs/findings-blood-decals-2026-09-29.md): fresh floor splats
 * are a thin film of liquid until they dry, so they reflect and catch the light. The splats themselves stay baked in the
 * chunk textures (BloodDecals); the wet ones on screen are drawn once more each frame as a surface layer, like the puddles:
 * <ul>
 *   <li>the main pass, right after the puddles (before anything that stands on the floor): the scene mirrored in them
 *       (pzopt.Ssr's pixel-projected keys: their level-0 squares join the reflection map as puddle squares, so the chunk
 *       composite and the characters near them scatter into it) with the film's Fresnel, and a GGX sheen of the sun and of
 *       the square's own light;</li>
 *   <li>the HDR glint-only pass (HdrGlint): the same surface's sun, sky and lamp speculars above the SDR white.</li>
 * </ul>
 * The film's normal comes from the splat's own alpha as a height field (flat pools, bright rims), its roughness and
 * reach from its age: wet for {@code bloodWetMinutes} of game time, drying from the edges in. A layer quad carries the
 * floor's depth (as the chunk texture holds it), so tables, walls and bodies in front of the floor hide it.
 */
public final class BloodWet {
   private BloodWet() {
   }

   private static volatile boolean failed = CoreGl.legacyMac();
   static final int TEXELS = 4, MAX = 4096;

   public static long frames, splats, drawsMain, drawsGlint, maxSplats;

   public static boolean on() {
      return Config.BLOOD_WET && Overrides.enabled() && !failed && Core.getInstance() != null && Core.getInstance().getOptionBloodDecals() > 0;
   }

   public static String stats() {
      return "blood wet: frames=" + frames + " rebuilds=" + rebuilds + " reuses=" + reuses + " chunkBuilds=" + chunkBuilds + " splats=" + splats + " max=" + maxSplats
            + " main=" + drawsMain + " glint=" + drawsGlint + (failed ? " (failed)" : "");
   }

   // ------------------------------------------------------------------------------------------------ the frame's wet splats

   /** A set of wet splats: 4 RGBA32F texels each, and the textures of their runs (reused while nothing changes). */
   static final class Frame {
      float[] data = new float[MAX * TEXELS * 4];
      int n;
      final int[] runTex = new int[64], runFrom = new int[64], runTo = new int[64];
      int runs;
      float texelU, texelV;
      int stamp;
   }

   /** One frame's draw of a set (its own uniforms: the render thread may still draw the previous frame's). */
   static final class Pass extends TextureDraw.GenericDrawer {
      Frame frame;
      boolean glint;
      float jx, jy, wtime;

      @Override
      public void render() {
         Gpu.draw(this);
      }
   }

   /** The wet splats of one chunk: their fixed data, rebuilt when its queue changes or one of them dries. */
   static final class WetChunk {
      int version = Integer.MIN_VALUE, size = -1, option = -1, tileScale = -1;
      Object last;
      float nextDry;
      int n;
      float[] s = new float[64 * 16];
      int[] sq = new int[64], level = new int[64], tex = new int[64];
      float[] age = new float[64];
      long outside; // per square of level 0..: bit = square index (levels mixed: rare, the flag is per square)
      long visKey;
      int nLevels;
      final int[] levels = new int[8];
      final long[] occupied = new long[8]; // squares holding wet splats, per level in levels[]

      void grow(int m) {
         if (m <= this.sq.length) {
            return;
         }
         int k = Math.max(m, this.sq.length * 2);
         this.s = java.util.Arrays.copyOf(this.s, k * 16);
         this.sq = java.util.Arrays.copyOf(this.sq, k);
         this.level = java.util.Arrays.copyOf(this.level, k);
         this.tex = java.util.Arrays.copyOf(this.tex, k);
         this.age = java.util.Arrays.copyOf(this.age, k);
      }
   }

   private static final java.util.IdentityHashMap<IsoChunk, WetChunk> wetChunks = new java.util.IdentityHashMap<>();
   private static final Frame[] FRAMES = {new Frame(), new Frame(), new Frame(), new Frame()};
   private static final Pass[] PASSES = new Pass[16];
   private static int frameIndex, passIndex;
   private static Frame cur, lastBuilt;
   private static long lastKey = Long.MIN_VALUE;
   private static float curJx, curJy, curWtime;
   /** level-0 wet squares per chunk this frame, for the reflection map (Ssr.beforeComposite) */
   private static final java.util.IdentityHashMap<IsoChunk, long[]> wetBits = new java.util.IdentityHashMap<>();
   private static int wetStamp;
   private static boolean anyLevel0;
   public static long rebuilds, reuses, chunkBuilds;

   private static long[] sortKeys = new long[256];
   private static float[] SORTED = new float[MAX * TEXELS * 4];

   static {
      for (int i = 0; i < PASSES.length; i++) {
         PASSES[i] = new Pass();
      }
   }

   private static WetChunk wetChunk(IsoChunk c, float worldAge, float wetHours, int option, byte[] density, int ts) {
      zombie.core.utils.BoundedQueue<IsoFloorBloodSplat> q = c.floorBloodSplats;
      int size = q.size();
      WetChunk w = wetChunks.get(c);
      Object last = size > 0 ? q.get(size - 1) : null;
      if (w != null && w.version == c.pzoptBloodVersion && w.size == size && w.last == last && w.option == option && w.tileScale == ts && worldAge < w.nextDry) {
         return w;
      }
      if (w == null) {
         w = new WetChunk();
         wetChunks.put(c, w);
      }
      chunkBuilds++;
      w.version = c.pzoptBloodVersion;
      w.size = size;
      w.last = last;
      w.option = option;
      w.tileScale = ts;
      w.n = 0;
      w.outside = 0L;
      w.nextDry = Float.MAX_VALUE;
      w.nLevels = 0;
      for (int i = size - 1; i >= 0; i--) {
         IsoFloorBloodSplat b = q.get(i);
         float age = worldAge - b.worldAge;
         if (age >= wetHours) {
            break;
         }
         if (age < 0F || b.type < 0 || b.type >= BloodDecals.TYPES || b.index >= 1 && b.index <= 10 && density[b.index - 1] == 0) {
            continue;
         }
         Texture tex = BloodDecals.typeTexture(b.type);
         int lx = zombie.core.math.PZMath.fastfloor(b.x), ly = zombie.core.math.PZMath.fastfloor(b.y);
         int level = zombie.core.math.PZMath.fastfloor(b.z);
         if (tex == null || tex.getTextureId() == null || lx < 0 || lx >= 8 || ly < 0 || ly >= 8 || c.getGridSquare(lx, ly, level) == null) {
            continue; // (no square: stock never bakes it either)
         }
         w.grow(w.n + 1);
         int k = w.n++;
         w.nextDry = Math.min(w.nextDry, b.worldAge + wetHours);
         // the baked splat's place (IsoSprite.renderBloodSplat in its own chunk's texture), in absolute screen pixels
         float sxf = b.x * (float)(32 * ts) - b.y * (float)(32 * ts);
         float syf = b.y * (float)(16 * ts) + b.x * (float)(16 * ts) + (0.0F - b.z) * (float)(96 * ts);
         float ox = (c.wx * 8 - c.wy * 8) * (float)(32 * ts), oy = (c.wx * 8 + c.wy * 8) * (float)(16 * ts);
         float wx = c.wx * 8 + b.x, wy = c.wy * 8 + b.y;
         int o = k * 16;
         w.s[o] = ox + (int)sxf - BloodDecals.typeGeom[b.type * 4];
         w.s[o + 1] = oy + (int)syf - BloodDecals.typeGeom[b.type * 4 + 1];
         w.s[o + 2] = BloodDecals.typeGeom[b.type * 4 + 2];
         w.s[o + 3] = BloodDecals.typeGeom[b.type * 4 + 3];
         w.s[o + 4] = BloodDecals.typeUv[b.type * 4];
         w.s[o + 5] = BloodDecals.typeUv[b.type * 4 + 1];
         w.s[o + 6] = BloodDecals.typeUv[b.type * 4 + 2];
         w.s[o + 7] = BloodDecals.typeUv[b.type * 4 + 3];
         w.s[o + 8] = IsoDepthHelper.calculateDepth(wx, wy, b.z); // + the chunk's depth from the camera, per frame
         w.s[o + 9] = oy + syf;
         w.s[o + 12] = wx;
         w.s[o + 13] = wy;
         w.sq[k] = lx + ly * 8;
         w.level[k] = level;
         w.tex[k] = tex.getTextureId().getID();
         w.age[k] = b.worldAge;
         int li = 0;
         while (li < w.nLevels && w.levels[li] != level) {
            li++;
         }
         if (li == w.nLevels && li < w.levels.length) {
            w.levels[li] = level;
            w.occupied[li] = 0L;
            w.nLevels++;
         }
         if (li < w.nLevels) {
            w.occupied[li] |= 1L << (lx + ly * 8);
         }
         IsoGridSquare sq = c.getGridSquare(lx, ly, level);
         if (sq != null && sq.isOutside()) {
            w.outside |= 1L << (lx + ly * 8);
         }
      }
      return w;
   }

   /**
    * Game thread, FBORenderCell before the chunk composite (ahead of Ssr.beforeComposite): the on-screen wet splats. Per
    * chunk the wet ones are kept (WetChunk); per frame only their squares' visibility and light are looked up, once per
    * square, and the set is rebuilt and uploaded only when that, the camera's chunk or a quarter game minute changed.
    */
   public static void collect(int playerIndex, java.util.List<IsoChunk> chunks) {
      try {
         collectSplats(playerIndex, chunks);
      } catch (RuntimeException e) {
         // thrown out of here it would end FBORenderCell's render of this frame (no chunk composite: a stale picture)
         cur = null;
         lastBuilt = null;
         anyLevel0 = false;
         wetBits.clear();
         failed = true;
         Log.warn("blood wet: collect failed, wet blood off: " + e);
      }
   }

   private static void collectSplats(int playerIndex, java.util.List<IsoChunk> chunks) {
      cur = null;
      anyLevel0 = false;
      wetBits.clear();
      if (!on() || chunks == null || !BloodDecals.types()) {
         lastKey = Long.MIN_VALUE;
         return;
      }
      float worldAge = (float)GameTime.getInstance().getWorldAgeHours();
      float wetHours = Math.max(1, Config.BLOOD_WET_MINUTES) / 60F;
      int option = Core.getInstance().getOptionBloodDecals();
      byte[] density = IsoChunk.renderByIndex[option - 1];
      int ts = Core.tileScale;
      float camZ = IsoCamera.frameState.camCharacterZ;
      int camX = zombie.core.math.PZMath.fastfloor(IsoCamera.frameState.camCharacterX), camY = zombie.core.math.PZMath.fastfloor(IsoCamera.frameState.camCharacterY);
      int camCX = zombie.core.math.PZMath.fastfloor(camX / 8.0F), camCY = zombie.core.math.PZMath.fastfloor(camY / 8.0F);
      zombie.iso.PlayerCamera camera = IsoCamera.cameras[playerIndex];
      curJx = camera.fixJigglyModelsX * camera.zoom;
      curJy = camera.fixJigglyModelsY * camera.zoom;
      curWtime = (float)(System.nanoTime() % 3_600_000_000_000L) / 1e9F;
      int frameNo = zombie.iso.IsoWorld.instance.getFrameNo();
      // pass 1: the wet chunks and what decides the set (their versions, their squares' visibility), hashed
      long key = 1469598103934665603L ^ ((long)camCX << 32 | (camCY & 0xFFFFFFFFL)) * 31L ^ playerIndex ^ (long)(worldAge * 240F) * 1099511628211L
            ^ (System.nanoTime() / 100_000_000L) * 7919L ^ option; // a refresh every 100 ms (wetness, light) whatever the frame rate
      wetList.clear();
      for (int ci = 0; ci < chunks.size(); ci++) {
         IsoChunk c = chunks.get(ci);
         zombie.core.utils.BoundedQueue<IsoFloorBloodSplat> q = c.floorBloodSplats;
         int size = q.size();
         if (size == 0 || worldAge - q.get(size - 1).worldAge >= wetHours) {
            continue;
         }
         WetChunk w = wetChunk(c, worldAge, wetHours, option, density, ts);
         if (w.n == 0) {
            continue;
         }
         // the visibility of the squares holding wet splats, per level (cutaway, occlusion, levels above an underground camera):
         // one lookup per occupied square, not per splat
         long vis = 0L;
         zombie.iso.fboRenderChunk.FBORenderLevels levels = c.getRenderLevels(playerIndex);
         for (int li = 0; li < w.nLevels; li++) {
            int level = w.levels[li];
            if (camZ < 0F && level > zombie.core.math.PZMath.fastfloor(camZ) || level < c.minLevel || level > c.maxLevel || !levels.isOnScreen(level)) {
               continue;
            }
            zombie.iso.fboRenderChunk.FBORenderCutaways.ChunkLevelData cut = c.getCutawayDataForLevel(level);
            if (cut == null) {
               continue;
            }
            // ChunkLevelData.shouldRenderSquare's flag, read straight (squares behind something are left to the depth test)
            byte[] flags = cut.squareFlags[playerIndex];
            long occ = w.occupied[li], lv = 0L;
            while (occ != 0L) {
               int s = Long.numberOfTrailingZeros(occ);
               occ &= occ - 1L;
               if ((flags[s] & 1) != 0) {
                  lv |= 1L << s;
               }
            }
            vis |= lv;
            if (level == 0 && lv != 0L) {
               wetBits.computeIfAbsent(c, x -> new long[1])[0] |= lv;
               anyLevel0 = true;
            }
         }
         w.visKey = vis;
         key = (key ^ System.identityHashCode(c)) * 1099511628211L ^ w.version * 31L ^ vis ^ w.n;
         wetList.add(c);
      }
      if (wetList.isEmpty()) {
         lastKey = Long.MIN_VALUE;
         return;
      }
      if (key == lastKey && lastBuilt != null) {
         cur = lastBuilt; // same set as last frame: no rebuild, no upload
         reuses++;
         queueUpdate();
         return;
      }
      lastKey = key;
      rebuilds++;
      Frame f = FRAMES[frameIndex++ & 3];
      f.n = 0;
      f.runs = 0;
      float[] d = f.data;
      for (int ci = 0; ci < wetList.size() && f.n < MAX; ci++) {
         IsoChunk c = wetList.get(ci);
         WetChunk w = wetChunks.get(c);
         int lastLevel = Integer.MIN_VALUE;
         float chunkDepth = 0F;
         float[] lightSq = LIGHT_SQ;
         long lightDone = 0L;
         for (int k = 0; k < w.n && f.n < MAX; k++) {
            int s = w.sq[k];
            if ((w.visKey >>> s & 1L) == 0L) {
               continue;
            }
            int level = w.level[k];
            if (level != lastLevel) {
               lastLevel = level;
               chunkDepth = IsoDepthHelper.getChunkDepthData(camCX, camCY, c.wx, c.wy, level).depthStart;
               lightDone = 0L;
            }
            if ((lightDone >>> s & 1L) == 0L) {
               lightDone |= 1L << s;
               IsoGridSquare sq = c.getGridSquare(s & 7, s >> 3, level);
               lightSq[s] = sq == null ? 0F : 0.5F * (luma(sq.getVertLight(0, playerIndex)) + luma(sq.getVertLight(2, playerIndex)));
            }
            float age = worldAge - w.age[k];
            // wetness: 1 fresh, 0 dry (ease-out: a film dries fast at first, the thick middle last)
            float wet = Math.max(0F, 1F - age / wetHours);
            wet = wet * (2F - wet);
            float fresh = age < 72F ? 0.25F + (1F - age / 72F) * 0.75F : 0.25F;
            int o = f.n * TEXELS * 4, so = k * 16;
            System.arraycopy(w.s, so, d, o, 8);
            d[o + 8] = chunkDepth + w.s[so + 8];
            d[o + 9] = w.s[so + 9];
            d[o + 10] = wet;
            d[o + 11] = (w.outside >>> s & 1L) != 0L ? 1F : 0F;
            d[o + 12] = w.s[so + 12];
            d[o + 13] = w.s[so + 13];
            d[o + 14] = lightSq[s];
            d[o + 15] = 0.27F * fresh;
            if (sortKeys.length <= f.n) {
               sortKeys = java.util.Arrays.copyOf(sortKeys, sortKeys.length * 2);
            }
            sortKeys[f.n] = ((long)w.tex[k] << 32) | f.n;
            f.n++;
         }
      }
      if (f.n == 0) {
         lastBuilt = null;
         return;
      }
      // runs by texture page (the splat textures usually share one): stable order, reorder the data
      java.util.Arrays.sort(sortKeys, 0, f.n);
      float[] sorted = SORTED;
      int prev = -1;
      for (int i = 0; i < f.n; i++) {
         int id = (int)(sortKeys[i] >>> 32);
         int src = (int)(sortKeys[i] & 0xFFFFFFFFL);
         System.arraycopy(d, src * TEXELS * 4, sorted, i * TEXELS * 4, TEXELS * 4);
         if (f.runs == 0 || id != prev && f.runs < f.runTex.length) { // (the first run opens whatever its id: -1 threw here)
            f.runTex[f.runs] = id;
            f.runFrom[f.runs] = i;
            f.runTo[f.runs] = i + 1;
            f.runs++;
            prev = id;
         } else {
            f.runTo[f.runs - 1] = i + 1;
         }
      }
      System.arraycopy(sorted, 0, d, 0, f.n * TEXELS * 4);
      Texture t0 = BloodDecals.typeTexture(0);
      f.texelU = t0 != null && t0.getWidthHW() > 0 ? 1F / t0.getWidthHW() : 1F / 1024F;
      f.texelV = t0 != null && t0.getHeightHW() > 0 ? 1F / t0.getHeightHW() : 1F / 1024F;
      f.stamp = ++wetStamp;
      cur = f;
      lastBuilt = f;
      splats += f.n;
      maxSplats = Math.max(maxSplats, f.n);
      queueUpdate();
   }

   private static final ArrayList<IsoChunk> wetList = new ArrayList<>();
   private static final float[] LIGHT_SQ = new float[64];

   private static void queueUpdate() {
      frames++;
      if (!Hdr.active) {
         HdrGlint.update(); // the sun and the sky for the SDR sheen (HDR updates them itself)
      }
      if ((frames & 255) == 0) {
         wetChunks.keySet().removeIf(c -> c.floorBloodSplats.isEmpty() || c.pzoptBlood == null); // chunks gone (their blood reset)
      }
   }

   private static float luma(int abgr) {
      return 0.2126F * zombie.core.Color.getRedChannelFromABGR(abgr) + 0.7152F * zombie.core.Color.getGreenChannelFromABGR(abgr)
            + 0.0722F * zombie.core.Color.getBlueChannelFromABGR(abgr);
   }

   /** Ssr.beforeComposite: are there wet level-0 splats (they count as puddle squares in the reflection map)? */
   static boolean anyWetLevel0() {
      return anyLevel0 && Ssr.active();
   }

   /** Ssr.beforeComposite: the chunk's wet level-0 squares (8 x 8 bits). */
   static long ssrBits(IsoChunk c) {
      if (!anyLevel0) {
         return 0L;
      }
      long[] b = wetBits.get(c);
      return b == null ? 0L : b[0];
   }

   /** Game thread, FBORenderCell right after the puddles: the main-pass layer (reflection + sheen). */
   public static void queueMain() {
      Frame f = cur;
      if (f == null || f.n == 0) {
         return;
      }
      SpriteRenderer.instance.drawGeneric(pass(f, false));
   }

   private static Pass pass(Frame f, boolean glint) {
      Pass p = PASSES[passIndex++ & (PASSES.length - 1)];
      p.frame = f;
      p.glint = glint;
      p.jx = curJx;
      p.jy = curJy;
      p.wtime = curWtime;
      return p;
   }

   /** Game thread, FBORenderCell inside the HDR glint-only pass (after the puddles'): the glints. */
   public static void queueGlint() {
      Frame f = cur;
      if (f == null || f.n == 0 || !Hdr.active || Config.BLOOD_GLINT_PCT <= 0) {
         return;
      }
      SpriteRenderer.instance.drawGeneric(pass(f, true));
   }

   // ------------------------------------------------------------------------------------------------ render thread

   static final String VERT = String.join("\n",
         "#version 330 compatibility",
         "uniform sampler2D uData;",
         "uniform mat4 uMVP;",
         "uniform vec4 uJ;     // jiggle x, y, K per unit x + y, bias (depth units)",
         "uniform int uBase;",
         "uniform float uTs;",
         "out vec2 vUv;",
         "out vec2 vWorld;",
         "flat out vec4 vP;    // wetness, outside, light, baked alpha",
         "void main() {",
         "   int i = uBase + gl_InstanceID;",
         "   vec4 a = texelFetch(uData, ivec2(i * 4, 0), 0);",
         "   vec4 u = texelFetch(uData, ivec2(i * 4 + 1, 0), 0);",
         "   vec4 d = texelFetch(uData, ivec2(i * 4 + 2, 0), 0);",
         "   vec4 w = texelFetch(uData, ivec2(i * 4 + 3, 0), 0);",
         "   int c = gl_VertexID;",
         "   float cx = (c == 1 || c == 2) ? 1.0 : 0.0;",
         "   float cy = (c >= 2) ? 1.0 : 0.0;",
         "   float X = a.x + a.z * cx;",
         "   float Y = a.y + a.w * cy;",
         "   gl_Position = uMVP * vec4(X + uJ.x, Y + uJ.y, 0.0, 1.0);",
         "   // the floor's depth under this corner: one K per unit of x + y, 16 ts screen rows per unit",
         "   float depth = d.x - (Y - d.y) / (16.0 * uTs) * uJ.z - uJ.w;",
         "   gl_Position.z = (depth * 2.0 - 1.0) * gl_Position.w;",
         "   vUv = vec2(mix(u.x, u.z, cx), mix(u.y, u.w, cy));",
         "   // world squares of this corner on the floor (for the glitter cells): x - y across, x + y down",
         "   float du = (X - (a.x + a.z * 0.5)) / (32.0 * uTs), dv = (Y - d.y) / (16.0 * uTs);",
         "   vWorld = w.xy + vec2(dv + du, dv - du) * 0.5;",
         "   vP = vec4(d.z, d.w, w.z, w.w);",
         "}");

   /** The film: coverage and normal from the splat's alpha, roughness from its wetness. */
   static final String FILM = String.join("\n",
         "vec4 vertColour = vec4(1.0); // Ssr's puddle helper reads it",
         "uniform float WTime;",
         "uniform sampler2D DIFFUSE;",
         "uniform vec4 uTexel;  // texel size of the splat page (u, v), normal strength, -",
         "in vec2 vUv;",
         "in vec2 vWorld;",
         "flat in vec4 vP;",
         "float pzCover;",
         "vec3 pzFilmNormal() {",
         "   float a = texture(DIFFUSE, vUv).a;",
         "   // blood pools: flat where the splat is dense, a meniscus toward its edges; the film dries from the edges in",
         "   pzCover = smoothstep(0.08 + 0.5 * (1.0 - vP.x), 0.45 + 0.4 * (1.0 - vP.x), a);",
         "   if (pzCover * vP.x < 0.01) discard; // before the gradient taps and the reflection lookup",
         "   float ax = texture(DIFFUSE, vUv + vec2(uTexel.x, 0.0)).a - texture(DIFFUSE, vUv - vec2(uTexel.x, 0.0)).a;",
         "   float ay = texture(DIFFUSE, vUv + vec2(0.0, uTexel.y)).a - texture(DIFFUSE, vUv - vec2(0.0, uTexel.y)).a;",
         "   // the water shaders' frame: x screen right, y up, z screen down (a floor step down the screen is half as long)",
         "   return normalize(vec3(-ax * uTexel.z, 1.0, -ay * uTexel.z * 2.0));",
         "}",
         "float pzGgx(vec3 n, vec3 h, float r) {",
         "   float a2 = r * r * r * r;",
         "   float nh = max(dot(n, h), 0.0);",
         "   float q = nh * nh * (a2 - 1.0) + 1.0;",
         "   return a2 / (3.14159265 * q * q);",
         "}");

   static final String MAIN_FRAG_HEAD = "#version 330 compatibility\n#extension GL_ARB_shader_image_load_store : require\n";

   static final String MAIN_FRAG_BODY = String.join("\n",
         "uniform vec4 uSun;   // direction (water frame, leaned toward the view's mirror like the water glint), strength",
         "uniform vec4 uSky;   // sky colour, strength",
         "uniform vec4 uK;     // reflection strength, sheen strength, ssr on, dev view",
         "out vec4 fragColor;",
         "void main() {",
         "   vec3 n = pzFilmNormal();",
         "   float k = pzCover * vP.x * vP.w / 0.27;",
         "   if (k < 0.004) discard;",
         "   vec3 V = normalize(vec3(0.0, 0.62, 0.78));",
         "   float nv = max(dot(n, V), 0.0);",
         "   // a dielectric film (plasma ~ water, F0 0.02)",
         "   float F = 0.02 + 0.98 * pow(1.0 - nv, 5.0);",
         "   vec3 refl = vec3(0.0);",
         "   float kr = 0.0;",
         "#ifdef PZ_SSR_PPR",
         "   if (uK.z > 0.5) {",
         "      vec3 r;",
         "      kr = pzSsrLookup(n, vec4(0.0, 1.0, 0.0, 0.0), vec4(0.0), vec4(0.0), r, true); // a still film (reflectionsFlow: calm)",
         "      refl = r;",
         "   }",
         "#endif",
         "   // what the film mirrors, at the film's own Fresnel: the sky outdoors (or the room's light) a few %, the scene the",
         "   // reflection found (a zombie standing in the pool) boosted like the puddles' (x F / F at the camera's view)",
         "   vec3 sky = mix(vec3(vP.z * 0.5), uSky.rgb, vP.y);",
         "   float ms = clamp(F * uK.x * k, 0.0, 0.08);",
         "   float mr = clamp(kr * F / 0.050625 * 0.45 * uK.x * k, 0.0, 0.6);",
         "   vec3 env = (sky * ms * (1.0 - mr) + refl * mr) / max(ms + mr - ms * mr, 1e-4);",
         "   float m = ms + mr - ms * mr;",
         "   // the sun: a broad lobe on the meniscus plus the coagulating skin's micro-facets (world-anchored cells, one facet",
         "   // each, a narrow mirror lobe: sparkles that stay put and move with the light)",
         "   vec3 L = normalize(uSun.xyz);",
         "   vec3 H = normalize(L + V);",
         "   float rough = mix(0.5, 0.2, vP.x);",
         "   float lobe = pzGgx(n, H, rough) * max(dot(n, L), 0.0) * (0.02 + 0.98 * pow(1.0 - max(dot(V, H), 0.0), 5.0));",
         "   vec2 cell = floor(vWorld * 24.0);",
         "   vec2 h2 = fract(sin(vec2(dot(cell, vec2(127.1, 311.7)), dot(cell, vec2(269.5, 183.3)))) * 43758.5453);",
         "   vec3 mf = normalize(n + vec3(h2.x - 0.5, 0.0, h2.y - 0.5) * mix(0.9, 0.35, vP.x));",
         "   float spark = pow(max(dot(mf, H), 0.0), 600.0) * step(0.55, fract(h2.x * 7.13 + h2.y * 3.7));",
         "   float sun = uSun.w * vP.y * (lobe * 2.5 + spark * 1.5 * vP.x);",
         "   vec3 Hl = normalize(normalize(vec3(0.0, 1.0, -0.35)) + V);",
         "   float lamp = vP.z * pzGgx(n, Hl, rough + 0.1) * 0.03;",
         "   vec3 spec = vec3(1.0, 0.97, 0.92) * min(sun + lamp, 3.0) * uK.y * k;",
         "   if (uK.w > 0.5) {",
         "      vec3 dv = uK.w < 1.5 ? vec3(k, 0.0, k) : uK.w < 2.5 ? vec3(kr, kr * 0.5, 0.0) + refl * kr : uK.w < 3.5 ? vec3(sun) : n * 0.5 + 0.5;",
         "      fragColor = vec4(dv, 1.0);",
         "      return;",
         "   }",
         "   // a wet film darkens what is under it (less light scattered back out of the blood): deeper red, the gloss on top",
         "   float dark = 0.15 * k * (1.0 - m);",
         "   fragColor = vec4(env * m + spec, m + dark);",
         "}");

   static final String GLINT_FRAG_BODY = String.join("\n",
         "uniform vec4 uG;     // glint strength, -, -, -",
         "void main() {",
         "   vec3 n = pzFilmNormal();",
         "   float k = pzCover * vP.x * vP.w / 0.27;",
         "   if (k < 0.004) discard;",
         "   vec3 V = normalize(vec3(0.0, 0.62, 0.78));",
         "   float rough = mix(0.45, 0.07, vP.x);",
         "   vec3 L = normalize(pzHdrSun.xyz);",
         "   vec3 H = normalize(L + V);",
         "   float nl = max(dot(n, L), 0.0);",
         "   float vh = max(dot(V, H), 0.0);",
         "   float Fh = 0.02 + 0.98 * pow(1.0 - vh, 5.0);",
         "   float nv = max(dot(n, V), 0.0);",
         "   float F = 0.02 + 0.98 * pow(1.0 - nv, 5.0);",
         "   // the sun (outside only), the sky, and the lamps of the light map, as the water's glint but on the film",
         "   vec3 g = vec3(1.0, 0.96, 0.88) * (pzGgx(n, H, rough) * nl * Fh * pzHdrSun.w * vP.y);",
         "   g += pzHdrSky.rgb * (F * pzHdrSky.a * vP.y);",
         "   if (pzHdrF.z > 0.5 && pzHdrGlintP.z > 0.0) {",
         "      vec4 m = pzHdrLightAt(pzWindowPx());",
         "      vec3 Hl = normalize(normalize(vec3(0.0, 1.0, -0.35)) + V);",
         "      g += m.rgb * (m.a * pzGgx(n, Hl, rough + 0.05) * 0.05 * pzHdrGlintP.z);",
         "   }",
         "   g *= k * uG.x;",
         "   gl_FragData[0] = vec4(0.0);",
         "   gl_FragData[1] = vec4(g / (1.0 + g), pzSurfNow());",
         "}");

   static final class Gpu {
      private static int mainProg, glintProg, dataTex, vao;
      private static boolean mainPpr;
      private static final FloatBuffer mat = BufferUtils.createFloatBuffer(16);
      private static final Matrix4f mvp = new Matrix4f();
      private static FloatBuffer upload;
      private static final java.util.HashMap<Integer, int[]> U = new java.util.HashMap<>();
      private static int lastUploadStamp = -1;

      private static int[] u(int prog) {
         return U.computeIfAbsent(prog, p -> {
            String[] names = {"uData", "uMVP", "uJ", "uBase", "uTs", "DIFFUSE", "uTexel", "uSun", "uK", "uG", "WTime", "uSky"};
            int[] l = new int[names.length];
            for (int i = 0; i < names.length; i++) {
               l[i] = GL20.glGetUniformLocation(p, names[i]);
            }
            return l;
         });
      }

      /**
       * Render thread, at world entry (shaderWarmup): both programs built before the first blood is drawn (~60 ms cold, mid-play
       * before); not while reflections are wanted but not patched in yet (the surface program bakes in their mode).
       */
      static void warm() {
         boolean ssrWanted = Config.SSR && Config.SSR_STRENGTH_PCT > 0;
         if (!on() || ssrWanted && !(Ssr.supported() && (!"ppr".equals(Ssr.mode()) || Ssr.ppr()))) {
            return;
         }
         init(false);
         init(true);
      }

      private static boolean init(boolean glint) {
         if (failed) {
            return false;
         }
         if (vao == 0) {
            vao = GL30.glGenVertexArrays();
            dataTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, dataTex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, MAX * TEXELS, 1, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer)null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         }
         if (!glint && mainProg == 0) {
            mainPpr = Ssr.ppr();
            String head = MAIN_FRAG_HEAD + (mainPpr ? "#define PZ_SSR_PPR\n" : "");
            String frag = head + FILM + "\n" + (Ssr.supported() ? Ssr.WATER_GLSL : "") + "\n" + MAIN_FRAG_BODY;
            if (!Ssr.supported()) {
               frag = frag.replace("#define PZ_SSR_PPR\n", "");
            }
            mainProg = Shaders.program("blood wet", VERT, frag);
            if (mainProg == 0) {
               failed = true;
               return false;
            }
            Log.info("blood wet: surface program " + mainProg + (mainPpr ? " with reflections" : ""));
         }
         if (glint && glintProg == 0) {
            String frag = "#version 330 compatibility\n" + FILM + "\n" + Hdr.SURFACE_GLSL + "\n" + GLINT_FRAG_BODY;
            glintProg = Shaders.program("blood wet glint", VERT, frag);
            if (glintProg == 0) {
               Log.warn("blood wet: no glint program, glints off");
               glintProg = -1;
            }
         }
         return glint ? glintProg > 0 : mainProg > 0;
      }

      static void draw(Pass p) {
         Frame f = p.frame;
         boolean glint = p.glint;
         if (f == null || f.n == 0) {
            return;
         }
         try {
            if (!init(glint)) {
               return;
            }
            int prog = glint ? glintProg : mainProg;
            int[] l = u(prog);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, dataTex);
            if (lastUploadStamp != f.stamp) {
               int floats = f.n * TEXELS * 4;
               if (upload == null || upload.capacity() < floats) {
                  upload = BufferUtils.createFloatBuffer(MAX * TEXELS * 4);
               }
               upload.clear();
               upload.put(f.data, 0, floats).flip();
               GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, f.n * TEXELS, 1, GL11.GL_RGBA, GL11.GL_FLOAT, upload);
               lastUploadStamp = f.stamp;
            }
            zombie.core.ShaderHelper.glUseProgramObjectARB(prog); // tracked: Ssr / HdrGlint set their uniforms on the bound program
            GL20.glUniform1i(l[0], 1);
            // the puddles' projection (IsoPuddles.puddlesProjection): absolute screen pixels of the rendering player's camera
            int pi = SpriteRenderer.instance.getRenderingPlayerIndex();
            zombie.iso.PlayerCamera cam = SpriteRenderer.instance.getRenderingPlayerCamera(pi);
            mvp.setOrtho(cam.getOffX(), cam.getOffX() + cam.offscreenWidth, cam.getOffY() + cam.offscreenHeight, cam.getOffY(), -1.0F, 1.0F);
            mat.clear();
            mvp.get(mat);
            GL20.glUniformMatrix4fv(l[1], false, mat);
            GL20.glUniform4f(l[2], p.jx, p.jy, BloodDecals.K, BloodDecals.K * Config.BLOOD_APPEND_BIAS_PCT / 100F + 1.0E-4F);
            GL20.glUniform1f(l[4], Core.tileScale);
            GL20.glUniform1i(l[5], 0);
            GL20.glUniform4f(l[6], f.texelU, f.texelV, 6.0F, 0F);
            if (glint) {
               HdrGlint.surfaceUniforms();
               GL20.glUniform4f(l[9], Config.BLOOD_GLINT_PCT / 100F, 0F, 0F, 0F);
               GL20.glUniform1f(l[10], p.wtime);
            } else {
               boolean ssr = Ssr.active() && Ssr.ppr() && mainPpr;
               if (ssr) {
                  Ssr.puddleUniforms(0);
               }
               float sunK = HdrGlint.sunStrength * (Hdr.active ? 0.35F : 1.0F);
               GL20.glUniform4f(l[7], HdrGlint.sun[0], HdrGlint.sun[1], HdrGlint.sun[2], sunK);
               GL20.glUniform4f(l[8], Config.BLOOD_REFLECT_PCT / 100F, Config.BLOOD_SHEEN_PCT / 100F, ssr ? 1F : 0F, Config.DEV_BLOOD_WET_VIEW);
               GL20.glUniform4f(l[11], HdrGlint.sky[0], HdrGlint.sky[1], HdrGlint.sky[2], HdrGlint.sky[3]);
               GL11.glEnable(GL11.GL_BLEND);
               GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
            }
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            if (glint) {
               GL11.glDepthFunc(GL11.GL_LEQUAL);
               GL11.glDepthMask(false);
            } else {
               // one film per pixel: the first splat's fragment writes the (floor - bias) depth, overlapping splats then fail
               // LESS instead of blending the film in again (five layers turned 20 % of sky into 67 %); what is drawn later
               // stands above the floor and still passes
               GL11.glDepthFunc(GL11.GL_LESS);
               GL11.glDepthMask(true);
            }
            GL11.glEnable(GL32.GL_DEPTH_CLAMP);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL30.glBindVertexArray(vao);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            for (int r = 0; r < f.runs; r++) {
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, f.runTex[r]);
               GL20.glUniform1i(l[3], f.runFrom[r]);
               GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, f.runTo[r] - f.runFrom[r]);
            }
            GL30.glBindVertexArray(0);
            GL11.glDisable(GL32.GL_DEPTH_CLAMP);
            if (glint) {
               drawsGlint++;
            } else {
               drawsMain++;
            }
         } catch (Throwable t) {
            failed = true;
            Log.warn("blood wet: draw failed, wet blood off: " + t);
         } finally {
            zombie.core.ShaderHelper.glUseProgramObjectARB(0);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            Texture.lastTextureID = -1;
            GL11.glDepthMask(true);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GLStateRenderThread.restore();
            zombie.core.ShaderHelper.forgetCurrentlyBound();
            SpriteRenderer.ringBuffer.restoreVbos = true;
            SpriteRenderer.ringBuffer.restoreBoundTextures = true;
         }
      }
   }
}
