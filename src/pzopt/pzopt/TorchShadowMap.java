package pzopt;

import java.util.ArrayList;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import zombie.core.Core;
import zombie.core.textures.Texture;
import zombie.iso.IsoCamera;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoUtils;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.fboRenderChunk.FBORenderLevels;

/**
 * The torch's shadow map ({@code pplShadowMode=map}, 2026-10-25): the static world seen from the player's torch, as an
 * omnidirectional depth map built every frame before the chunk composite, which reads it per pixel.
 *
 * <p>The casters are the chunk textures themselves: every texel of a baked chunk level carries the exact depth of what it
 * shows (the sprites' own depth maps under their alpha), so with the texture's placement it is a point of the world. A
 * mesh of cells over the texels near the torch (one instance per cell of {@code pplShadowMapCell} texels, its four corners
 * fetched in the vertex shader, a corner more than a step behind the cell's front-most one pulled onto it so a post's
 * edge never stretches into a curtain to the ground behind) is drawn from the torch into one octahedral map (the lower
 * hemisphere is its inner diamond: the floor round the holder has no seam; cells across a fold of the upper hemisphere's
 * triangles are dropped), the depth the distance over the map's range. Same-frame, so no lag and no reprojection;
 * the chunk textures hold what other chunks, the cutaway's walls and the screen's edge hide in the scene depth.
 *
 * <p>Left out (the screen-space mask never had them either): the per-frame translucent tiles, characters and vehicles
 * (CapsuleShadow casts theirs), and what a sprite hides of itself (each texel is the front of its object: a post lit
 * from its side casts the shadow of its front half's silhouette).
 */
public final class TorchShadowMap {
   private TorchShadowMap() {
   }

   /** (Config only: PixelLight's shader text reads it while PixelLight initialises; the render thread checks ACTIVE.) */
   static final boolean ENABLED = Config.PIXEL_LIGHT && Config.PPL_SHADOWS
      && (Config.PPL_SHADOW_MODE.startsWith("map"));
   /** pplShadowMode=map-ism: the map splatted from the scene depth's points after the composite (imperfect shadow maps), read one frame late */
   static final boolean ISM = ENABLED && "map-ism".equals(Config.PPL_SHADOW_MODE);
   /** pplShadowFilter=evsm | msm: the map also as filtered moments (exponential variance / Hamburger 4-moment), one tap a read */
   static final boolean MOMENTS = ENABLED && ("evsm".equals(Config.PPL_SHADOW_FILTER) || "msm".equals(Config.PPL_SHADOW_FILTER));
   /** pplShadowMapParam: how the sphere round the torch lies in the texture (0 octahedral, 1 dual paraboloid, 2 cube atlas) */
   static final int PARAM = "planar".equals(Config.PPL_SHADOW_MAP_PARAM) ? 3 : "cube".equals(Config.PPL_SHADOW_MAP_PARAM) ? 2 : "dp".equals(Config.PPL_SHADOW_MAP_PARAM) ? 1 : 0;
   /** views a caster is drawn into (dp: both hemispheres; cube: the six faces; each drops what is not its own) */
   static final int VIEWS = PARAM == 2 ? 6 : PARAM == 1 ? 2 : 1;
   static final int MAX = 128;
   static final double DPXY = PixelLight.DEPTH_PER_XY;

   /** One frame's casters (game thread fills it, the render thread draws it). */
   static final class Casters {
      int n;
      final Texture[] tex = new Texture[MAX];
      final int[] rect = new int[MAX * 4]; // first texel x, y, cells across, cells down
      final int[] cell = new int[MAX]; // texels per cell
      final float[] map = new float[MAX * 5]; // kA, cA, kB, cB, cC (relative to the origin square)
      final zombie.iso.IsoChunk[] chunk = new zombie.iso.IsoChunk[MAX];
      boolean want; // the torch has a shadow this frame
      boolean reuse; // pplShadowMapCache: nothing the map shows changed since the last one: the render thread keeps it
      long serial; // the collect's number (a kept map must be the one built from the collect just before)
      float z0; // the torch's floor (squares of height): pplShadowMapParam=planar projects onto it
      String dump; // devPplShadowDumpAt: a tag to dump this frame's map under
      float lx, ly, lz, range; // the torch: x, y relative to the origin square, z in squares of height; the map's range

      void clear() {
         java.util.Arrays.fill(this.tex, 0, this.n, null);
         java.util.Arrays.fill(this.chunk, 0, this.n, null);
         this.n = 0;
         this.want = false;
         this.reuse = false;
         this.dump = null;
      }
   }

   public static long collects, casterTextures, cellsQueued;
   private static int dumpNext;

   /**
    * Game thread, PixelLight.beforeComposite once the frame's lights are known: the chunk textures of this frame's
    * composite whose squares and levels come within the map's range of the torch, with their texel rectangle over that
    * range and their texel -> world mapping (the composite's placement of the texture, FBORenderChunk.renderInWorldMainThread).
    */
   static void collect(Casters c, ArrayList<FBORenderChunk> list, int playerIndex, int ox, int oy, float d0, float lx, float ly, float lzLevel, float lh, float reach) {
      c.clear();
      if (!ENABLED) {
         return;
      }
      collects++;
      if (!Config.DEV_PPL_SHADOW_DUMP_AT.isEmpty() && PixelLight.worldUpNs != 0L) {
         double t = (System.nanoTime() - PixelLight.worldUpNs) / 1e9;
         String[] at = Config.DEV_PPL_SHADOW_DUMP_AT.split(",");
         while (dumpNext < at.length && t >= Double.parseDouble(at[dumpNext].trim())) {
            c.dump = "torchmap-" + at[dumpNext].trim() + "s";
            dumpNext++;
         }
      }
      float range = Math.min(reach + 1.0F, Config.PPL_SHADOW_MAP_SQUARES);
      c.want = true;
      c.lx = lx;
      c.ly = ly;
      c.lz = (lzLevel + lh) * 2.4494897F;
      c.range = range + 2.0F;
      c.z0 = (float)Math.floor(lzLevel + 0.05F) * 2.4494897F;
      if (ISM) {
         return; // the casters are the scene depth's points, after the composite
      }
      int level = (int)Math.floor(lzLevel + 0.05F);
      int camWX = (int)Math.floor(IsoCamera.frameState.camCharacterX / 8.0F), camWY = (int)Math.floor(IsoCamera.frameState.camCharacterY / 8.0F);
      for (int i = 0; i < list.size() && c.n < MAX; i++) {
         addCaster(c, list.get(i), ox, oy, d0, lx, ly, level, range, camWX, camWY);
      }
      // pplShadowMapOffscreen: the chunks round the torch that are not on the screen this frame (zoomed in, the torch near
      // the screen's edge) still cast, from the textures they kept (zoomRetain), as long as they hold one at this zoom
      int onScreen = c.n;
      if (Config.PPL_SHADOW_MAP_OFFSCREEN && zombie.iso.IsoWorld.instance != null && zombie.iso.IsoWorld.instance.currentCell != null) {
         zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
         float zoom = Core.getInstance().getZoom(playerIndex);
         int ax = (int)Math.floor((lx + ox - range) / 8.0F), bx = (int)Math.floor((lx + ox + range) / 8.0F);
         int ay = (int)Math.floor((ly + oy - range) / 8.0F), by = (int)Math.floor((ly + oy + range) / 8.0F);
         for (int wy = ay; wy <= by && c.n < MAX; wy++) {
            for (int wx = ax; wx <= bx && c.n < MAX; wx++) {
               zombie.iso.IsoChunk ch = cell.getChunk(wx, wy);
               if (ch == null) {
                  continue;
               }
               for (int lv = level; lv <= level + 1 && c.n < MAX; lv++) {
                  if (lv < ch.minLevel || lv > ch.maxLevel) {
                     continue;
                  }
                  FBORenderChunk rc = ch.getRenderLevels(playerIndex).getFBOForLevel(lv, zoom);
                  if (rc == null || rc.depth == null) {
                     continue;
                  }
                  boolean have = false;
                  for (int k = 0; k < c.n && !have; k++) {
                     have = c.tex[k] == rc.depth;
                  }
                  if (!have && addCaster(c, rc, ox, oy, d0, lx, ly, level, range, camWX, camWY) && c.n > onScreen) {
                     offscreenCasters++;
                  }
               }
            }
         }
      }
      cacheCheck(c);
   }

   public static long offscreenCasters;

   /** One chunk texture as a caster when it comes within the map's range (false: it does not). */
   private static boolean addCaster(Casters c, FBORenderChunk rc, int ox, int oy, float d0, float lx, float ly, int level, float range, int camWX, int camWY) {
      double a32 = 32.0 * Core.tileScale, a16 = 16.0 * Core.tileScale;
      int cell = Config.PPL_SHADOW_MAP_CELL;
      if (rc.depth == null || rc.chunk == null || !rc.isInit) {
         return false;
      }
      int min = rc.getMinLevel(), top = rc.getTopLevel();
      if (top < level || min > level + 1) {
         return false; // the torch lights its own level (pplOwnLevelLights); what stands on it and the floor above it can cast
      }
      float x0 = rc.chunk.wx * 8 - ox, y0 = rc.chunk.wy * 8 - oy;
      float dx = Math.max(0.0F, Math.max(x0 - lx, lx - (x0 + 8.0F))), dy = Math.max(0.0F, Math.max(y0 - ly, ly - (y0 + 8.0F)));
      if (dx * dx + dy * dy > range * range) {
         return false;
      }
      Texture t = rc.getTexture();
      if (t == null) {
         return false;
      }
      int whw = rc.depth.getWidthHW(), hhw = rc.depth.getHeightHW();
      if (whw <= 0 || hhw <= 0) {
         return false;
      }
      double W = rc.highRes ? rc.w / 2.0 : rc.w, H = rc.highRes ? rc.h / 2.0 : rc.h;
      double xs = t.getXStart(), xe = t.getXEnd(), ys = t.getYStart(), ye = t.getYEnd();
      if (Math.abs(xe - xs) < 1e-9 || Math.abs(ye - ys) < 1e-9) {
         return false;
      }
      double sxL = IsoUtils.XToScreen(rc.chunk.wx * 8, rc.chunk.wy * 8, min, 0) - W / 2.0;
      double syT = IsoUtils.YToScreen(rc.chunk.wx * 8, rc.chunk.wy * 8, min, 0) - FBORenderChunk.PIXELS_PER_LEVEL * (top - min + 1)
         - FBORenderLevels.extraHeightForJumboTrees(min, top);
      double ku = W / (xe - xs), kv = H / (ye - ys);
      double kA = ku / a32, cA = (sxL - xs * ku) / a32 - (ox - oy);
      double kB = kv / a16, cB = (syT - ys * kv) / a16 - (ox + oy);
      float D0 = IsoDepthHelper.getChunkDepthData(camWX, camWY, rc.chunk.wx, rc.chunk.wy, min).depthStart;
      double cC = (d0 - D0) / DPXY;
      // the range box round the torch in (x - y, x + y - 6z) over the texture's levels (a tall sprite up to a level and a
      // half above its top), then in texels
      double aLo = lx - ly - 2.0 * range, aHi = lx - ly + 2.0 * range;
      double bLo = lx + ly - 2.0 * range - 6.0 * (top + 1.5), bHi = lx + ly + 2.0 * range - 6.0 * min;
      double u0 = (aLo - cA) / kA, u1 = (aHi - cA) / kA, v0 = (bLo - cB) / kB, v1 = (bHi - cB) / kB;
      double uMin = Math.max(Math.min(u0, u1), Math.min(xs, xe)), uMax = Math.min(Math.max(u0, u1), Math.max(xs, xe));
      double vMin = Math.max(Math.min(v0, v1), Math.min(ys, ye)), vMax = Math.min(Math.max(v0, v1), Math.max(ys, ye));
      if (uMax <= uMin || vMax <= vMin) {
         return false;
      }
      // the cell: about 1 / pplShadowMapCellsPerSquare of a square across (a texel spans kA / whw of x - y, a square across
      // the view is 1.414 of it), at least pplShadowMapCell texels
      double sqPerTexel = Math.abs(kA) / whw * 0.70710678;
      int cps = Config.PPL_SHADOW_MAP_CELLS_PER_SQUARE * ((PixelLight.costMask & PixelLight.SM_FINE) != 0 ? 2 : 1); // dev: twice as fine
      int cellT = Math.max(cell, Math.min(16, (int)Math.round(1.0 / (cps * sqPerTexel))));
      int ti0 = Math.max(0, (int)Math.floor(uMin * whw) - 1), ti1 = Math.min(whw - 1, (int)Math.ceil(uMax * whw) + 1);
      int tj0 = Math.max(0, (int)Math.floor(vMin * hhw) - 1), tj1 = Math.min(hhw - 1, (int)Math.ceil(vMax * hhw) + 1);
      int nx = (ti1 - ti0 + cellT - 1) / cellT, ny = (tj1 - tj0 + cellT - 1) / cellT;
      if (nx <= 0 || ny <= 0) {
         return false;
      }
      int k = c.n++;
      c.tex[k] = rc.depth;
      c.chunk[k] = rc.chunk;
      c.rect[k * 4] = ti0;
      c.rect[k * 4 + 1] = tj0;
      c.rect[k * 4 + 2] = nx;
      c.rect[k * 4 + 3] = ny;
      c.cell[k] = cellT;
      c.map[k * 5] = (float)kA;
      c.map[k * 5 + 1] = (float)cA;
      c.map[k * 5 + 2] = (float)kB;
      c.map[k * 5 + 3] = (float)cB;
      c.map[k * 5 + 4] = (float)cC;
      casterTextures++;
      cellsQueued += (long)nx * ny;
      return true;
   }

   // pplShadowMapCache (game thread): the last collected casters, and the chunks baked since
   private static final java.util.ArrayList<zombie.iso.IsoChunk> bakedSince = new java.util.ArrayList<>();
   private static final Casters last = new Casters();
   private static boolean lastValid;
   private static int lastCm;
   public static long reuses;
   private static long collectSerial;

   /** PixelLight.bakeBegin (game thread): a chunk's textures are drawn again; a map that holds them is built again. */
   static void chunkBaked(zombie.iso.IsoChunk ch) {
      if (ENABLED && Config.PPL_SHADOW_MAP_CACHE && bakedSince.size() < 4096) {
         bakedSince.add(ch);
      }
   }

   /**
    * The map is kept (no build) when the torch, the caster list (textures, rectangles, cells, placements, the torch's
    * floor) and the dev cost bits are what they were last frame and none of those chunks baked since: standing still.
    */
   private static void cacheCheck(Casters c) {
      boolean same = Config.PPL_SHADOW_MAP_CACHE && lastValid && c.dump == null && lastCm == PixelLight.costMask && last.n == c.n && last.lx == c.lx
         && last.ly == c.ly && last.lz == c.lz && last.range == c.range && last.z0 == c.z0;
      for (int i = 0; same && i < c.n; i++) {
         same = last.tex[i] == c.tex[i] && last.chunk[i] == c.chunk[i] && last.cell[i] == c.cell[i];
         for (int j = 0; same && j < 4; j++) {
            same = last.rect[i * 4 + j] == c.rect[i * 4 + j];
         }
         for (int j = 0; same && j < 5; j++) {
            same = last.map[i * 5 + j] == c.map[i * 5 + j];
         }
         for (int j = 0; same && j < bakedSince.size(); j++) {
            same = bakedSince.get(j) != c.chunk[i];
         }
      }
      c.reuse = same;
      c.serial = ++collectSerial;
      bakedSince.clear();
      lastCm = PixelLight.costMask;
      last.n = c.n;
      last.lx = c.lx;
      last.ly = c.ly;
      last.lz = c.lz;
      last.range = c.range;
      last.z0 = c.z0;
      System.arraycopy(c.tex, 0, last.tex, 0, c.n);
      System.arraycopy(c.chunk, 0, last.chunk, 0, c.n);
      System.arraycopy(c.cell, 0, last.cell, 0, c.n);
      System.arraycopy(c.rect, 0, last.rect, 0, c.n * 4);
      System.arraycopy(c.map, 0, last.map, 0, c.n * 5);
      lastValid = c.want;
   }

   /** The filter the composite reads with: 0 hard, 1 pcf, 2 pcss (dev cost bits first, then pplShadowFilter). */
   static float filter(int costMask) {
      if ((costMask & PixelLight.SM_HARD) != 0) {
         return 0.0F;
      }
      if ((costMask & PixelLight.SM_PCF) != 0) {
         return 1.0F;
      }
      if ((costMask & PixelLight.SM_PCSS) != 0) {
         return 2.0F;
      }
      if ((costMask & PixelLight.SM_SOFT) != 0) {
         return 5.0F;
      }
      return configFilter();
   }

   /** pplShadowFilter as the composite's compiled-in filter (0 hard, 1 pcf, 2 pcss, 3 evsm, 4 msm). */
   static int configFilter() {
      switch (Config.PPL_SHADOW_FILTER) {
         case "hard":
            return 0;
         case "pcss":
            return 2;
         case "soft":
            return 5;
         case "evsm":
            return MOMENTS ? 3 : 1;
         case "msm":
            return MOMENTS ? 4 : 1;
         default:
            return 1;
      }
   }

   /** The texture the composite reads: the filtered moments for evsm / msm, else the depth. */
   static int readTexture(int costMask) {
      return MOMENTS && filter(costMask) >= 3.0F ? momTex[0] : tex;
   }

   // ------------------------------------------------------------------------------------------------ render thread

   static int tex, fbo, size, program, vbo, ibo;
   static int texW, texH; // the texture: oct N x N, dp 2N x N (a hemisphere's disc N across), cube 3T x 2T (T = N / 2)
   static float texelsPerRadian; // where the parametrization is coarsest (oct: about N / 3.54; dp and cube: at a view's centre)
   static float cubeWiden = 1.0F;
   private static final int[] u = new int[7];
   // moments: momTex[0] (with the depth: fboM) blurred across into momTex[1] (fboB[1]) and up back into momTex[0] (fboB[0])
   static final int[] momTex = new int[2];
   private static int fboM, programM, blurProgram;
   // the geometry-shader cells (depth, moments); 0: not linked (the vertex-shader cells draw)
   static int programG, programGM;
   private static final int[] uG = new int[7], uGM = new int[7];
   private static final int[] fboB = new int[2], um = new int[7], ub = new int[2];
   static boolean failed;
   private static boolean builtLast; // the map holds the last frame's casters (render thread)
   private static long builtSerial; // the collect the map was built (or kept) for
   static long builds, drawnCells, buildNs;
   private static int logCountdown = 600;

   /** Render thread, PixelLight's frame before the composite: the map of this frame's casters. False: no map (no shadow). */
   static boolean build(Casters c) {
      if (failed || !c.want) {
         builtLast = false;
         return false;
      }
      if (c.reuse && builtLast && builtSerial == c.serial - 1) {
         reuses++;
         builtSerial = c.serial;
         logStats();
         return true; // pplShadowMapCache: the map from the last frame is this frame's
      }
      long t0 = System.nanoTime();
      try {
         if (program == 0 && !init()) {
            failed = true;
            return false;
         }
         GpuSections.markNow("torchmap", false);
         Timer.stamp(0);
         int prevFbo = ShadowAtlas.worldFbo();
         GL11.glPushAttrib(GL11.GL_VIEWPORT_BIT | GL11.GL_SCISSOR_BIT);
         ShadowAtlas.Tracked saved = ShadowAtlas.Tracked.save();
         try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, MOMENTS ? fboM : fbo);
            zombie.core.opengl.GLStateRenderThread.ScissorTest.set(false);
            zombie.core.opengl.GLStateRenderThread.StencilTest.set(false);
            zombie.core.opengl.GLStateRenderThread.Blend.set(false);
            zombie.core.opengl.GLStateRenderThread.DepthTest.set(true);
            zombie.core.opengl.GLStateRenderThread.DepthMask.set(true);
            zombie.core.opengl.GLStateRenderThread.DepthFunc.set(GL11.GL_LESS);
            zombie.core.opengl.GLStateRenderThread.ColorMask.set(MOMENTS, MOMENTS, MOMENTS, MOMENTS);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glViewport(0, 0, texW, texH);
            GL11.glClearDepth(1.0);
            if (MOMENTS) {
               boolean evsm = "evsm".equals(Config.PPL_SHADOW_FILTER);
               // the moments of the far plane (depth 1)
               // (glClearBuffer: the game's clear colour stays as it was, no glGet)
               CLEAR_M[0] = evsm ? (float)Math.exp(EVSM_POS) : 1.0F;
               CLEAR_M[1] = evsm ? (float)Math.exp(2.0 * EVSM_POS) : 1.0F;
               CLEAR_M[2] = evsm ? (float)-Math.exp(-EVSM_NEG) : 1.0F;
               CLEAR_M[3] = evsm ? (float)Math.exp(-2.0 * EVSM_NEG) : 1.0F;
               GL30.glClearBufferfv(GL11.GL_COLOR, 0, CLEAR_M);
               GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            } else {
               GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            }
            int cm = PixelLight.costMask;
            float thr = (float)(0.35 * DPXY); // a corner this far (in x + y + 2z) behind the cell's front belongs to what is behind
            // the cells' thickness along the view (pplShadowThickPct; dev: bit SM_SHEET the bare front surface, SM_THICK_CONST the
            // most for every cell)
            float thick = (cm & PixelLight.SM_SHEET) != 0 ? 0.0F : Config.PPL_SHADOW_THICK_PCT / 100.0F;
            if (thick > 0.0F && Config.PPL_SHADOW_THICK_AUTO && (cm & PixelLight.SM_THICK_CONST) == 0) {
               thick = -thick;
            }
            int indices = thick == 0.0F ? 6 : 36;
            // the compute cells unless a dev bit asks for the geometry-shader (SM_GS_CELLS) or vertex-shader (SM_VS_CELLS) ones
            boolean cs = (MOMENTS ? csProgramM : csProgram) != 0 && (cm & (PixelLight.SM_GS_CELLS | PixelLight.SM_VS_CELLS)) == 0;
            if (cs) {
               buildCompute(c, thr, thick, indices);
            }
            // the geometry-shader cells unless the dev bit SM_VS_CELLS asks for the vertex-shader ones (b4)
            boolean gs = !cs && (MOMENTS ? programGM : programG) != 0 && (cm & PixelLight.SM_VS_CELLS) == 0;
            if (!cs) {
               int[] u = gs ? (MOMENTS ? uGM : uG) : MOMENTS ? um : TorchShadowMap.u;
               int viewLoc = gs || MOMENTS ? u[6] : u[5];
               GL20.glUseProgram(gs ? (MOMENTS ? programGM : programG) : MOMENTS ? programM : program);
               GL20.glUniform1i(u[0], 0);
               if (MOMENTS) {
                  GL20.glUniform1f(u[5], "evsm".equals(Config.PPL_SHADOW_FILTER) ? 3.0F : 4.0F);
               }
               GL20.glUniform4f(u[3], c.lx, c.ly, c.lz, 1.0F / c.range);
               // uView.z: the thickness per corner (pplShadowThickCorner; dev bit SM_CELL_THICK: per cell)
               boolean corner = Config.PPL_SHADOW_THICK_CORNER && (cm & PixelLight.SM_CELL_THICK) == 0;
               boolean smooth = Config.PPL_SHADOW_SMOOTH != ((cm & PixelLight.SM_SMOOTH_FLIP) != 0);
               GL20.glUniform4f(viewLoc, cubeWiden, c.lz - c.z0, (corner ? 1.0F : 0.0F) + (smooth ? 2.0F : 0.0F), Config.PPL_SHADOW_BOX_GAP_PCT / 100.0F);
               for (int i = 0; i < CLIPS; i++) {
                  GL11.glEnable(GL30.GL_CLIP_DISTANCE0 + i);
               }
               GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
               for (int i = gs ? 0 : 1; i < 5; i++) {
                  GL20.glDisableVertexAttribArray(i);
               }
               if (!gs) {
                  GL20.glEnableVertexAttribArray(0);
                  GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
               }
               GL13.glActiveTexture(GL13.GL_TEXTURE0);
               GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ibo);
               for (int i = 0; i < c.n; i++) {
                  Texture t = c.tex[i];
                  if (t == null || t.getID() <= 0) {
                     continue;
                  }
                  GL11.glBindTexture(GL11.GL_TEXTURE_2D, t.getID());
                  GL20.glUniform4i(u[1], c.rect[i * 4], c.rect[i * 4 + 1], c.rect[i * 4 + 2], c.cell[i]);
                  GL20.glUniform4f(u[2], c.map[i * 5], c.map[i * 5 + 1], c.map[i * 5 + 2], c.map[i * 5 + 3]);
                  GL20.glUniform4f(u[4], c.map[i * 5 + 4], (float)(1.0 / DPXY), thr, thick);
                  int cells = c.rect[i * 4 + 2] * c.rect[i * 4 + 3];
                  if (gs) {
                     GL31.glDrawArraysInstanced(GL11.GL_POINTS, 0, cells, VIEWS);
                  } else {
                     GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES, indices, GL11.GL_UNSIGNED_SHORT, 0L, cells * VIEWS);
                  }
                  drawnCells += cells;
               }
               for (int i = 0; i < CLIPS; i++) {
                  GL11.glDisable(GL30.GL_CLIP_DISTANCE0 + i);
               }
            }
            if (MOMENTS) {
               // a separable 5-tap Gaussian over the moments (what makes them filterable: one bilinear tap reads a soft edge)
               zombie.core.opengl.GLStateRenderThread.DepthTest.set(false);
               zombie.core.opengl.GLStateRenderThread.DepthMask.set(false);
               GL20.glUseProgram(blurProgram);
               GL20.glUniform1i(ub[0], 0);
               for (int pass = 0; pass < 2; pass++) {
                  GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboB[1 - pass]);
                  GL11.glBindTexture(GL11.GL_TEXTURE_2D, momTex[pass]);
                  GL20.glUniform2i(ub[1], pass == 0 ? 1 : 0, pass == 0 ? 0 : 1);
                  GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
               }
            }
         } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            Texture.lastTextureID = -1;
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0); // (the sprite ring buffer binds its own again: restoreVbos)
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            for (int i = 0; i < 5; i++) {
               GL20.glEnableVertexAttribArray(i);
            }
            GL20.glUseProgram(0);
            zombie.core.ShaderHelper.forgetCurrentlyBound();
            zombie.core.DefaultShader.isActive = false;
            saved.restore();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL11.glPopAttrib();
            zombie.core.SpriteRenderer.ringBuffer.restoreVbos = true; // the sprite ring buffer binds its buffers and textures again
            zombie.core.SpriteRenderer.ringBuffer.restoreBoundTextures = true;
         }
         GpuSections.markNow("torchmap", true);
         Timer.stamp(1);
         Timer.collect("torch shadow map build");
         builds++;
         builtLast = true;
         builtSerial = c.serial;
         if (c.dump != null) {
            dump(c);
         }
         return true;
      } catch (Throwable t) {
         failed = true;
         builtLast = false;
         Log.warn("torch shadow map: failed, shadows off: " + t);
         return false;
      } finally {
         buildNs += System.nanoTime() - t0;
         logStats();
      }
   }

   /** Every 1200 frames with a map (built or kept): the build / reuse counters. */
   private static void logStats() {
      if (--logCountdown <= 0) {
         logCountdown = 1200;
         Log.info(String.format(java.util.Locale.ROOT, "torch shadow map: %d builds (%d frames kept the last map, %d off-screen casters in all), %.1f casters and %.0f cells a build, %.1f us cpu a build", builds, reuses, offscreenCasters,
            builds > 0 ? casterTextures / (double)Math.max(collects, 1) : 0.0, builds > 0 ? drawnCells / (double)builds : 0.0, builds > 0 ? buildNs / 1e3 / builds : 0.0));
      }
   }

   // ------------------------------------------------------------------------------------------------ compute cells (GL 4.3)

   static int csProgram, csProgramM, compProgram, recBuf, cmdBuf, csVao;
   private static final int[] cu = new int[9], du = new int[4], duM = new int[5];
   static final int CAP = 131072; // records (64 bytes each: 8 MB)
   private static final java.nio.IntBuffer CMD = org.lwjgl.BufferUtils.createIntBuffer(5);

   private static boolean initCompute() {
      if (!Config.PPL_SHADOW_MAP_COMPUTE || !org.lwjgl.opengl.GL.getCapabilities().OpenGL43) {
         return false;
      }
      compProgram = linkCompute(cellComp(PARAM));
      csProgram = AmbientOcclusion.link(csVert(PARAM), CELL_FRAG);
      csProgramM = MOMENTS ? AmbientOcclusion.link(csVert(PARAM), MOMENT_FRAG) : 0;
      if (compProgram == 0 || csProgram == 0 || MOMENTS && csProgramM == 0) {
         Log.warn("torch shadow map: the compute cells did not link, the geometry-shader cells draw");
         compProgram = csProgram = csProgramM = 0;
         return false;
      }
      String[] cn = {"Depth", "uRect", "uCell", "uCap", "uMapAB", "uMapC", "uLight", "uView", "uSmooth"};
      for (int i = 0; i < cn.length; i++) {
         cu[i] = GL20.glGetUniformLocation(compProgram, cn[i]);
      }
      String[] dn = {"uLight", "uCap", "uView", "uKind"};
      for (int i = 0; i < 3; i++) {
         du[i] = GL20.glGetUniformLocation(csProgram, dn[i]);
      }
      for (int i = 0; i < 4 && MOMENTS; i++) {
         duM[i] = GL20.glGetUniformLocation(csProgramM, dn[i]);
      }
      recBuf = GL15.glGenBuffers();
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, recBuf);
      GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long)CAP * 64L, GL15.GL_DYNAMIC_COPY);
      cmdBuf = GL15.glGenBuffers();
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, cmdBuf);
      GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, 20L, GL15.GL_DYNAMIC_DRAW);
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
      csVao = GL30.glGenVertexArrays();
      GL30.glBindVertexArray(csVao);
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
      GL20.glEnableVertexAttribArray(0);
      GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
      GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ibo);
      GL30.glBindVertexArray(0);
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
      return true;
   }

   private static int linkCompute(String src) {
      int sh = compile(GL43.GL_COMPUTE_SHADER, src);
      if (sh == 0) {
         return 0;
      }
      int p = GL20.glCreateProgram();
      GL20.glAttachShader(p, sh);
      GL20.glLinkProgram(p);
      GL20.glDeleteShader(sh);
      if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
         Log.warn("torch shadow map: compute link failed: " + GL20.glGetProgramInfoLog(p, 4096));
         GL20.glDeleteProgram(p);
         return 0;
      }
      return p;
   }

   /** The compute cells: the casting cells of every caster into the record list, then one indirect instanced draw of them. */
   private static void buildCompute(Casters c, float thr, float thick, int indices) {
      CMD.clear();
      CMD.put(indices).put(0).put(0).put(0).put(0).flip();
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, cmdBuf);
      GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, CMD);
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
      GL20.glUseProgram(compProgram);
      GL20.glUniform1i(cu[0], 0);
      GL20.glUniform1i(cu[3], CAP);
      GL20.glUniform4f(cu[6], c.lx, c.ly, c.lz, 1.0F / c.range);
      GL20.glUniform4f(cu[7], cubeWiden, c.lz - c.z0, 1.0F, Config.PPL_SHADOW_BOX_GAP_PCT / 100.0F);
      GL20.glUniform1i(cu[8], Config.PPL_SHADOW_SMOOTH != ((PixelLight.costMask & PixelLight.SM_SMOOTH_FLIP) != 0) ? 1 : 0); // dev bit: the other way
      GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, cmdBuf);
      GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, recBuf);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      for (int i = 0; i < c.n; i++) {
         Texture t = c.tex[i];
         if (t == null || t.getID() <= 0) {
            continue;
         }
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, t.getID());
         int nx = c.rect[i * 4 + 2], ny = c.rect[i * 4 + 3];
         GL20.glUniform4i(cu[1], c.rect[i * 4], c.rect[i * 4 + 1], nx, ny);
         GL20.glUniform1i(cu[2], c.cell[i]);
         GL20.glUniform4f(cu[4], c.map[i * 5], c.map[i * 5 + 1], c.map[i * 5 + 2], c.map[i * 5 + 3]);
         GL20.glUniform4f(cu[5], c.map[i * 5 + 4], (float)(1.0 / DPXY), thr, thick);
         GL43.glDispatchCompute((nx + 7) / 8, (ny + 7) / 8, 1);
         drawnCells += (long)nx * ny;
      }
      GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_COMMAND_BARRIER_BIT);
      int[] d = MOMENTS ? duM : du;
      GL20.glUseProgram(MOMENTS ? csProgramM : csProgram);
      GL20.glUniform4f(d[0], c.lx, c.ly, c.lz, 1.0F / c.range);
      GL20.glUniform1i(d[1], CAP);
      GL20.glUniform4f(d[2], cubeWiden, c.lz - c.z0, 1.0F, Config.PPL_SHADOW_BOX_GAP_PCT / 100.0F);
      if (MOMENTS) {
         GL20.glUniform1f(d[3], "evsm".equals(Config.PPL_SHADOW_FILTER) ? 3.0F : 4.0F);
      }
      for (int i = 0; i < CLIPS; i++) {
         GL11.glEnable(GL30.GL_CLIP_DISTANCE0 + i);
      }
      GL30.glBindVertexArray(csVao);
      GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, cmdBuf);
      GL40.glDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_SHORT, 0L);
      GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
      GL30.glBindVertexArray(0);
      for (int i = 0; i < CLIPS; i++) {
         GL11.glDisable(GL30.GL_CLIP_DISTANCE0 + i);
      }
      GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, 0);
      GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, 0);
   }

   /** devPplTiming: the GPU time of the map's build (or splat), logged every 600 frames. */
   static final class Timer {
      private static int[] q;
      private static int slot;
      private static final boolean[] pending = new boolean[8];
      private static long sum, count;
      private static final int[] masks = new int[8];
      private static final java.util.HashMap<Integer, long[]> perMask = new java.util.HashMap<>();

      static void stamp(int i) {
         if (!Config.DEV_PPL_TIMING) {
            return;
         }
         if (q == null) {
            q = new int[16];
            GL15.glGenQueries(q);
         }
         GL33.glQueryCounter(q[slot * 2 + i], GL33.GL_TIMESTAMP);
      }

      static void collect(String what) {
         if (!Config.DEV_PPL_TIMING) {
            return;
         }
         pending[slot] = true;
         masks[slot] = PixelLight.costMask;
         slot = (slot + 1) % 8;
         if (pending[slot] && GL15.glGetQueryObjecti(q[slot * 2 + 1], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
            long ns = GL33.glGetQueryObjecti64(q[slot * 2 + 1], GL15.GL_QUERY_RESULT) - GL33.glGetQueryObjecti64(q[slot * 2], GL15.GL_QUERY_RESULT);
            sum += ns;
            // devPplAlternate: each mask's frames summed apart for the whole run (a line "... alt:" with every mask's mean)
            long[] m = perMask.computeIfAbsent(masks[slot], k -> new long[2]);
            m[0] += ns;
            m[1]++;
            if (++count >= 600) {
               Log.info(String.format(java.util.Locale.ROOT, "%s gpu: %.1f us (%d frames) epoch_ms=%d", what, sum / 1e3 / count, count, System.currentTimeMillis()));
               sum = count = 0;
               if (perMask.size() > 1) {
                  StringBuilder sb = new StringBuilder(what + " gpu alt:");
                  for (java.util.Map.Entry<Integer, long[]> e : new java.util.TreeMap<>(perMask).entrySet()) {
                     sb.append(String.format(java.util.Locale.ROOT, " m%d %.1f us (%d)", e.getKey(), e.getValue()[0] / 1e3 / Math.max(1L, e.getValue()[1]), e.getValue()[1]));
                  }
                  Log.info(sb.toString());
               }
            }
         }
         pending[slot] = false;
      }
   }

   static boolean ismValid;
   static final float[] ismL = new float[4]; // the torch the splatted map was made for (x, y, z, 1 / range)
   static int ismOx, ismOy; // the origin square ismL is relative to (the next frame's may differ)
   static float ismZ0; // and its floor (planar)
   private static int ismProgram;
   private static final int[] iu = new int[7];

   /**
    * pplShadowMode=map-ism, render thread right after the composite: every second window pixel of the scene depth as a
    * point from the torch into the map (a point as wide as the pixel's footprint seen from the torch), which the next
    * frame's composite reads with this frame's torch.
    */
   static void ism(int depthTex, float[] vp, float[] map, float[] light, float z0, int ox, int oy, Casters dumpWith) {
      ismValid = false;
      if (failed || depthTex == 0 || light[3] <= 0.0F) {
         return;
      }
      try {
         if (program == 0 && !init()) {
            failed = true;
            return;
         }
         if (ismProgram == 0) {
            ismProgram = AmbientOcclusion.link(ISM_VERT, CELL_FRAG);
            if (ismProgram == 0) {
               failed = true;
               Log.warn("torch shadow map: the splat program did not link, shadows off");
               return;
            }
            String[] names = {"SceneDepth", "uVp", "pplMapA", "pplMapC", "uLight", "uSize", "uView"};
            for (int i = 0; i < names.length; i++) {
               iu[i] = GL20.glGetUniformLocation(ismProgram, names[i]);
            }
         }
         GpuSections.markNow("torchism", false);
         Timer.stamp(0);
         int prevFbo = ShadowAtlas.worldFbo();
         GL11.glPushAttrib(GL11.GL_VIEWPORT_BIT | GL11.GL_SCISSOR_BIT);
         ShadowAtlas.Tracked saved = ShadowAtlas.Tracked.save();
         int stride = 2;
         int nx = (int)(vp[2] / stride), ny = (int)(vp[3] / stride);
         try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            zombie.core.opengl.GLStateRenderThread.ScissorTest.set(false);
            zombie.core.opengl.GLStateRenderThread.StencilTest.set(false);
            zombie.core.opengl.GLStateRenderThread.Blend.set(false);
            zombie.core.opengl.GLStateRenderThread.DepthTest.set(true);
            zombie.core.opengl.GLStateRenderThread.DepthMask.set(true);
            zombie.core.opengl.GLStateRenderThread.DepthFunc.set(GL11.GL_LESS);
            zombie.core.opengl.GLStateRenderThread.ColorMask.set(false, false, false, false);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL32_PROGRAM_POINT_SIZE);
            GL11.glViewport(0, 0, texW, texH);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            GL20.glUseProgram(ismProgram);
            GL20.glUniform1i(iu[0], 0);
            GL20.glUniform4f(iu[1], vp[0], vp[1], nx, stride);
            GL20.glUniform4f(iu[2], map[0], map[1], map[2], map[3]);
            GL20.glUniform4f(iu[3], map[4], map[5], 0.0F, 0.0F);
            GL20.glUniform4f(iu[4], light[0], light[1], light[2], light[3]);
            GL20.glUniform4f(iu[5], texelsPerRadian, Math.abs(map[0]) * stride, 0.0F, 0.0F);
            GL20.glUniform4f(iu[6], cubeWiden, light[2] - z0, 0.0F, 0.0F);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL20.glEnableVertexAttribArray(0);
            for (int i = 1; i < 5; i++) {
               GL20.glDisableVertexAttribArray(i);
            }
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
            GL31.glDrawArraysInstanced(GL11.GL_POINTS, 0, 1, nx * ny * VIEWS);
         } finally {
            GL11.glDisable(GL32_PROGRAM_POINT_SIZE);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            Texture.lastTextureID = -1;
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            for (int i = 0; i < 5; i++) {
               GL20.glEnableVertexAttribArray(i);
            }
            GL20.glUseProgram(0);
            zombie.core.ShaderHelper.forgetCurrentlyBound();
            zombie.core.DefaultShader.isActive = false;
            saved.restore();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL11.glPopAttrib();
            zombie.core.SpriteRenderer.ringBuffer.restoreVbos = true;
            zombie.core.SpriteRenderer.ringBuffer.restoreBoundTextures = true;
         }
         GpuSections.markNow("torchism", true);
         Timer.stamp(1);
         Timer.collect("torch shadow map splat");
         System.arraycopy(light, 0, ismL, 0, 4);
         ismOx = ox;
         ismZ0 = z0;
         ismOy = oy;
         ismValid = true;
         builds++;
         if (dumpWith != null && dumpWith.dump != null) {
            dump(dumpWith);
         }
      } catch (Throwable t) {
         failed = true;
         Log.warn("torch shadow map: splat failed, shadows off: " + t);
      }
   }

   private static final int GL32_PROGRAM_POINT_SIZE = 0x8642;

   /** devPplShadowDumpAt: the map read back (a sync, dev only), 1 - depth as 8 bits, into the capture folder. */
   private static void dump(Casters c) {
      try {
         int size = texW, sizeH = texH; // (the dump's own names: width, height)
         java.nio.FloatBuffer px = org.lwjgl.BufferUtils.createFloatBuffer(size * sizeH);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
         GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, px);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         Texture.lastTextureID = -1;
         java.io.File dir = FrameCapture.dir();
         dir.mkdirs();
         byte[] img = new byte[size * sizeH];
         int written = 0;
         float mn = 1.0F;
         for (int y = 0; y < sizeH; y++) {
            for (int x = 0; x < size; x++) {
               float d = px.get((sizeH - 1 - y) * size + x);
               if (d < 0.99999F) {
                  written++;
                  mn = Math.min(mn, d);
               }
               img[y * size + x] = (byte)Math.round(255.0F * Math.max(0.0F, 1.0F - d));
            }
         }
         java.io.File f = new java.io.File(dir, c.dump + ".pgm");
         try (java.io.OutputStream o = new java.io.BufferedOutputStream(new java.io.FileOutputStream(f))) {
            o.write(("P5\n" + size + " " + sizeH + "\n255\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            o.write(img);
         }
         long cells = 0;
         for (int i = 0; i < c.n; i++) {
            cells += (long)c.rect[i * 4 + 2] * c.rect[i * 4 + 3];
         }
         Log.info(String.format(java.util.Locale.ROOT, "torch shadow map: dev dump %s: torch %.3f,%.3f,%.3f range %.2f, %d casters, %d cells, %d texels written, nearest %.4f",
            f.getName(), c.lx, c.ly, c.lz, c.range, c.n, cells, written, mn));
      } catch (Throwable t) {
         Log.warn("torch shadow map: dev dump failed: " + t);
      }
   }

   private static boolean init() {
      size = Config.PPL_SHADOW_MAP_RES;
      texW = PARAM == 1 ? 2 * size : PARAM == 2 ? 3 * (size / 2) : size;
      texH = size;
      texelsPerRadian = PARAM == 1 ? size / 4.0F : PARAM == 2 ? size / 4.0F : size / 3.545F; // (planar: per square on the floor, per frame)
      cubeWiden = PARAM == 2 ? 1.0F / (1.0F + 2.0F * CUBE_MARGIN / (size / 2.0F)) : 1.0F;
      program = AmbientOcclusion.link(CELL_VERT, CELL_FRAG);
      if (program == 0) {
         Log.warn("torch shadow map: the cell program did not link, shadows off");
         return false;
      }
      String[] names = {"Depth", "uRect", "uMapAB", "uLight", "uMapC", "uView"};
      for (int i = 0; i < names.length; i++) {
         u[i] = GL20.glGetUniformLocation(program, names[i]);
      }
      String[] gnames = {"Depth", "uRect", "uMapAB", "uLight", "uMapC", "uKind", "uView"};
      programG = Config.PPL_SHADOW_MAP_GS ? link3(GS_VERT, CELL_GEOM, CELL_FRAG) : 0;
      for (int i = 0; i < gnames.length; i++) {
         uG[i] = programG != 0 ? GL20.glGetUniformLocation(programG, gnames[i]) : -1;
      }
      if (Config.PPL_SHADOW_MAP_GS && programG == 0) {
         Log.warn("torch shadow map: the geometry-shader cells did not link, the vertex-shader cells draw");
      }
      tex = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F, texW, texH, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.ByteBuffer)null);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12_CLAMP);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12_CLAMP);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      Texture.lastTextureID = -1;
      fbo = GL30.glGenFramebuffers();
      int prev = ShadowAtlas.worldFbo();
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, tex, 0);
      GL11.glDrawBuffer(GL11.GL_NONE);
      GL11.glReadBuffer(GL11.GL_NONE);
      int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
      if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
         Log.warn("torch shadow map: framebuffer incomplete (" + status + "), shadows off");
         return false;
      }
      if (MOMENTS && !initMoments()) {
         return false;
      }
      vbo = GL15.glGenBuffers();
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
      // indexed, so the post-transform cache runs the vertex shader once a corner (12 a cell), not once a triangle corner
      // three layers of 4 (z 1: the surface, 0: the box's near end just behind it, -1: its back); corner i = (x > 0) + 2 (y < 0) + 4 layer
      GL15.glBufferData(GL15.GL_ARRAY_BUFFER, new float[] {-1, 1, 1, 1, 1, 1, -1, -1, 1, 1, -1, 1, -1, 1, 0, 1, 1, 0, -1, -1, 0, 1, -1, 0, -1, 1, -1, 1, 1, -1, -1, -1, -1,
         1, -1, -1}, GL15.GL_STATIC_DRAW);
      ibo = GL15.glGenBuffers();
      GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ibo);
      // the sheet; the back face; the four sides from the near end to the back
      GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, new short[] {0, 1, 2, 2, 1, 3, 8, 10, 9, 9, 10, 11, 4, 8, 5, 5, 8, 9, 6, 7, 10, 7, 11, 10, 4, 6, 8, 6, 10, 8, 5, 9, 7,
         7, 9, 11}, GL15.GL_STATIC_DRAW);
      GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
      initCompute();
      Log.info("torch shadow map: " + texW + "x" + texH + " " + (PARAM == 3 ? "planar (onto the torch's floor)" : PARAM == 2 ? "cube atlas" : PARAM == 1 ? "dual paraboloid" : "octahedral") + " map, cells of " + Config.PPL_SHADOW_MAP_CELL + " texels, range " + Config.PPL_SHADOW_MAP_SQUARES
         + " squares, " + (compProgram != 0 ? "compute cells" : programG != 0 ? "geometry-shader cells" : "vertex-shader cells") + (Config.PPL_SHADOW_THICK_CORNER ? ", thickness per corner" : "") + ", filter " + Config.PPL_SHADOW_FILTER + ", cells " + Config.PPL_SHADOW_THICK_PCT / 100.0F + " squares thick at most" + (Config.PPL_SHADOW_THICK_AUTO ? " (from the surface's runs)" : "")
         + (ISM ? ", splatted from the scene depth" : ""));
      return true;
   }

   private static final int GL12_CLAMP = 0x812F;
   private static final float[] CLEAR_M = new float[4];
   static final double EVSM_POS = 40.0, EVSM_NEG = 5.0; // the exponential warps (fp32: e^80 still fits)

   private static boolean initMoments() {
      programM = AmbientOcclusion.link(CELL_VERT, MOMENT_FRAG);
      programGM = Config.PPL_SHADOW_MAP_GS ? link3(GS_VERT, CELL_GEOM, MOMENT_FRAG) : 0;
      String[] gnames = {"Depth", "uRect", "uMapAB", "uLight", "uMapC", "uKind", "uView"};
      for (int i = 0; i < gnames.length; i++) {
         uGM[i] = programGM != 0 ? GL20.glGetUniformLocation(programGM, gnames[i]) : -1;
      }
      blurProgram = AmbientOcclusion.link(BLUR_VERT, BLUR_FRAG);
      if (programM == 0 || blurProgram == 0) {
         Log.warn("torch shadow map: the moment programs did not link, shadows off");
         return false;
      }
      String[] names = {"Depth", "uRect", "uMapAB", "uLight", "uMapC", "uKind", "uView"};
      for (int i = 0; i < names.length; i++) {
         um[i] = GL20.glGetUniformLocation(programM, names[i]);
      }
      ub[0] = GL20.glGetUniformLocation(blurProgram, "Src");
      ub[1] = GL20.glGetUniformLocation(blurProgram, "uDir");
      int prev = ShadowAtlas.worldFbo();
      for (int i = 0; i < 2; i++) {
         momTex[i] = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, momTex[i]);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, texW, texH, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (java.nio.ByteBuffer)null);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12_CLAMP);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12_CLAMP);
         fboB[i] = GL30.glGenFramebuffers();
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboB[i]);
         GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, momTex[i], 0);
         if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
            Log.warn("torch shadow map: moment framebuffer incomplete, shadows off");
            return false;
         }
      }
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      Texture.lastTextureID = -1;
      fboM = GL30.glGenFramebuffers();
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboM);
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, momTex[0], 0);
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, tex, 0);
      int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
      if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
         Log.warn("torch shadow map: moment framebuffer incomplete (" + status + "), shadows off");
         return false;
      }
      return true;
   }

   /**
    * One instance per cell: its four corners' depths, each corner's world point (texel centre -> x - y, x + y - 6z through
    * the texture's placement, depth -> x + y + 2z), from the torch, into the octahedral map. A corner that is empty or more
    * than a step behind the cell's front-most corner takes that corner's depth (the cell is a piece of the front surface:
    * an object's edge is at most a cell wider, never a curtain to the floor behind it).
    */
   /** Cube atlas: each face's view is widened by this many texels a side (filter taps near a face's edge stay on it). */
   static final int CUBE_MARGIN = 4;

   /**
    * The projection into the texture, per parametrization (vertex side): smProj(v, view, clip) = the texture's NDC x, y and
    * the distance; false when the point belongs to another view by a margin (the caller drops the cell); clip = the
    * clip distances that keep a view's triangles in its own part of the texture.
    */
   static String projGlsl(int param) {
      String head = "uniform vec4 uView;\n"; // x: the cube face's widening 1 / (1 + 2 margin / T)
      if (param == 0) {
         return head + String.join("\n",
            "bool smProj(vec3 v, int view, out vec3 o, out vec4 clip) {", // the octahedral point (the lower hemisphere inside the diamond)
            "   float d = length(v);",
            "   vec3 w = vec3(v.xy, -v.z) / max(abs(v.x) + abs(v.y) + abs(v.z), 1e-6);",
            "   vec2 p = w.xy;",
            "   if (w.z < 0.0) p = (1.0 - abs(p.yx)) * vec2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);",
            "   o = vec3(p, d);",
            "   clip = vec4(1.0);",
            "   return true;",
            "}");
      }
      if (param == 1) {
         return head + String.join("\n",
            "bool smProj(vec3 v, int view, out vec3 o, out vec4 clip) {", // view 0: the lower hemisphere (left half), 1: the upper (right half)
            "   float d = length(v);",
            "   vec3 n = v / max(d, 1e-6);",
            "   bool up = view == 1;",
            "   vec2 p = n.xy / max(up ? 1.0 + n.z : 1.0 - n.z, 1e-3);",
            "   float x = p.x * 0.5 + (up ? 0.5 : -0.5);",
            "   o = vec3(x, p.y, d);",
            "   clip = vec4(up ? x : -x, 1.0, 1.0, 1.0);",
            "   return up ? n.z > -0.2 : n.z < 0.2;",
            "}");
      }
      if (param == 3) {
         // planar: a point's projection from the torch onto its floor (uView.y: the floor's height below the torch; the plane's
         // half width is the map's range), the depth the share of the drop to the floor (a perspective map looking straight down)
         return head + String.join("\n",
            "bool smProj(vec3 v, int view, out vec3 o, out vec4 clip) {",
            "   float t = -uView.y / min(v.z, -1e-4);", // v.z < 0: below the torch
            "   o = vec3(v.xy * t * uLight.w, 1.0 / t);",
            "   clip = vec4(1.0);",
            "   return v.z < -0.02;",
            "}");
      }
      return head + String.join("\n",
         "bool smProj(vec3 v, int view, out vec3 o, out vec4 clip) {", // view = the face: +x, -x, +y, -y, +z, -z in a 3 x 2 atlas
         "   float d = length(v);",
         "   int m = view >> 1;",
         "   float w = ((view & 1) == 0 ? 1.0 : -1.0) * v[m];",
         "   vec2 l = vec2(v[(m + 1) % 3], v[(m + 2) % 3]) / max(w, 1e-4) * uView.x;",
         "   o = vec3((float(view % 3) + l.x * 0.5 + 0.5) / 3.0 * 2.0 - 1.0, (float(view / 3) + l.y * 0.5 + 0.5) / 2.0 * 2.0 - 1.0, d);",
         "   clip = vec4(1.0 + l.x, 1.0 - l.x, 1.0 + l.y, 1.0 - l.y);",
         "   return w > 0.2 * d;",
         "}");
   }

   /** The same projection on the read side: the texture uv of direction v (the cube face by the dominant axis). */
   static String uvGlsl(int param) {
      if (param == 0) {
         return String.join("\n",
            "vec2 pplSmUv(vec3 v) {",
            "   vec3 w = vec3(v.xy, -v.z) / max(abs(v.x) + abs(v.y) + abs(v.z), 1e-6);",
            "   vec2 p = w.xy;",
            "   if (w.z < 0.0) p = (1.0 - abs(p.yx)) * vec2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);",
            "   return p * 0.5 + 0.5;",
            "}");
      }
      if (param == 1) {
         return String.join("\n",
            "vec2 pplSmUv(vec3 v) {",
            "   vec3 n = normalize(v);",
            "   bool up = n.z > 0.0;",
            "   vec2 p = n.xy / (up ? 1.0 + n.z : 1.0 - n.z);",
            "   return vec2((p.x * 0.5 + (up ? 0.5 : -0.5)) * 0.5 + 0.5, p.y * 0.5 + 0.5);",
            "}");
      }
      if (param == 3) {
         return String.join("\n",
            "vec2 pplSmUv(vec3 v) {", // pplSmO.w: the floor's height below the torch; the plane's half width is the range (pplSmP.w = 1 / it)
            "   float t = -pplSmO.w / min(v.z, -1e-4);",
            "   return v.xy * t * pplSmP.w * 0.5 + 0.5;",
            "}");
      }
      return String.join("\n",
         "vec2 pplSmUv(vec3 v) {",
         "   vec3 a = abs(v);",
         "   int m = a.x >= a.y && a.x >= a.z ? 0 : (a.y >= a.z ? 1 : 2);",
         "   int f = m * 2 + (v[m] >= 0.0 ? 0 : 1);",
         "   vec2 l = vec2(v[(m + 1) % 3], v[(m + 2) % 3]) / a[m] * pplSmO.w;",
         "   return vec2((float(f % 3) + l.x * 0.5 + 0.5) / 3.0, (float(f / 3) + l.y * 0.5 + 0.5) / 2.0);",
         "}");
   }

   /** The clip distances a parametrization uses (dp: 1, cube: 4), from a vec4 variable (null: all -1, the vertex dropped). */
   static final int CLIPS = clips(PARAM);

   static int clips(int param) {
      return param == 2 ? 4 : param == 1 ? 1 : 0;
   }

   static int views(int param) {
      return param == 2 ? 6 : param == 1 ? 2 : 1;
   }

   static String clipAssign(int param, String v) {
      StringBuilder b = new StringBuilder();
      for (int i = 0; i < clips(param); i++) {
         b.append("   gl_ClipDistance[").append(i).append("] = ").append(v == null ? "-1.0" : v + "[" + i + "]").append(";\n");
      }
      return b.toString();
   }

   static final String CELL_VERT = cellVert(PARAM);

   static String cellVert(int param) {
      return String.join("\n",
      "#version 330",
      clips(param) > 0 ? "out float gl_ClipDistance[" + clips(param) + "];" : "",
      "in vec3 aPosition;", // the cube's corner: x, y -1 / 1 = the cell's corners, z 1 front / -1 back
      "uniform sampler2D Depth;",
      "uniform ivec4 uRect;", // first texel x, y; cells across; texels per cell
      "uniform vec4 uMapAB;", // x - y = kA u + cA, x + y - 6z = kB v + cB (u, v: GL texture coordinates; relative to the origin square)
      "uniform vec4 uMapC;", // x + y + 2z = cC - depth * kC; z: the depth step that leaves a surface; w: the thickness (squares; < 0: -w at most, from the run)
      "uniform vec4 uLight;", // the torch (x, y, z in squares of height), 1 / the map's range
      "const float LEVEL = 2.4494897;",
      "vec3 world(ivec2 t, float C, vec2 inv) {",
      "   float A = uMapAB.x * (float(t.x) + 0.5) * inv.x + uMapAB.y;",
      "   float B = uMapAB.z * (float(t.y) + 0.5) * inv.y + uMapAB.w;",
      "   float Z = (C - B) * 0.125;",
      "   float S = C - 2.0 * Z;",
      "   return vec3((S + A) * 0.5, (S - A) * 0.5, Z * LEVEL);",
      "}",
      projGlsl(param),
      // the run of the surface through texel c along step st: texels until the depth jumps or the texture is empty (at most 8 steps)
      "float run(ivec2 c, ivec2 st, float d0, ivec2 sz) {",
      "   float n = 0.0, prev = d0;",
      "   for (int i = 1; i <= 8; i++) {",
      "      float d = texelFetch(Depth, clamp(c + st * i, ivec2(0), sz - 1), 0).r;",
      "      if (d >= 0.99999 || abs(d - prev) > uMapC.z) break;",
      "      prev = d;",
      "      n += 1.0;",
      "   }",
      "   return n;",
      "}",
      "void main() {",
      "   int s = uRect.w;",
      "   int view = gl_InstanceID % " + views(param) + ", cell = gl_InstanceID / " + views(param) + ";",
      "   ivec2 c0 = uRect.xy + ivec2(cell % uRect.z, cell / uRect.z) * s;",
      "   ivec2 sz = textureSize(Depth, 0);",
      "   vec2 inv = 1.0 / vec2(sz);",
      "   ivec2 off[4] = ivec2[4](ivec2(0, 0), ivec2(s, 0), ivec2(0, s), ivec2(s, s));",
      "   float d[4];",
      "   float front = 2.0;",
      "   for (int i = 0; i < 4; i++) {",
      "      d[i] = texelFetch(Depth, clamp(c0 + off[i], ivec2(0), sz - 1), 0).r;",
      "      if (d[i] < 0.99999) front = min(front, d[i]);",
      "   }",
      "   gl_Position = vec4(2.0, 2.0, 2.0, 1.0);", // dropped
      clipAssign(param, null),
      "   if (front > 1.5) return;",
      // cheap tests first: the corners' front points; a cell lying on the torch's own floor never hides anything above it
      // from the torch (most cells: the ground), nor a cell wholly out of range
      "   vec3 wf[4];",
      "   float Cf[4];",
      "   bool onFloor = true;",
      "   float dnear = 1e9;",
      "   bool own[4];",
      "   for (int i = 0; i < 4; i++) {",
      "      own[i] = d[i] < 0.99999 && d[i] - front < uMapC.z;",
      "      Cf[i] = uMapC.x - (own[i] ? d[i] : front) * uMapC.y;",
      "   }",
      PULL,
      "   for (int i = 0; i < 4; i++) {",
      "      wf[i] = world(c0 + off[i], Cf[i], inv) - uLight.xyz;",
      "      onFloor = onFloor && (!own[i] || wf[i].z + uView.y < " + FLOOR_EPS + ");",
      "      dnear = min(dnear, length(wf[i]));",
      "   }",
      "   if (onFloor || dnear * uLight.w >= 1.0) return;",
      "#define uSmooth (uView.z > 1.5 ? 1 : 0)", // vertex / geometry cells: uView.z = thickness per corner + 2 smooth
      SMOOTH,
      // the cell's thickness along the view (x + y + 2z falls 8 a level going back: 1.633 a square): a constant, or the
      // narrower of the surface's runs across and up the screen through the cell (a post as deep as it is wide, a rail as
      // deep as it is tall), at most -w
      "   float th = uMapC.w;",
      "   if (th < 0.0) {",
      "      ivec2 cc = clamp(c0 + ivec2(s / 2), ivec2(0), sz - 1);",
      "      float dc = texelFetch(Depth, cc, 0).r;",
      "      if (dc >= 0.99999) dc = front;",
      "      float ur = run(cc, ivec2(s, 0), dc, sz), ul = run(cc, ivec2(-s, 0), dc, sz);",
      "      float vr = run(cc, ivec2(0, s), dc, sz), vl = run(cc, ivec2(0, -s), dc, sz);",
      // an elliptical section: the depth at the cell's place across the run (a round post is deepest on its axis)
      "      float pu = (ur - ul) / (ur + ul + 1.0), pv = (vr - vl) / (vr + vl + 1.0);",
      "      float wu = (ur + ul + 1.0) * float(s) * abs(uMapAB.x) * inv.x * 0.70710678 * sqrt(max(1.0 - pu * pu, 0.2));", // across: A = x - y, a square perpendicular to the view is 1.414 of A
      "      float wv = (vr + vl + 1.0) * float(s) * abs(uMapAB.z) * inv.y * 0.40824829 * sqrt(max(1.0 - pv * pv, 0.2));", // up: B = x + y - 6z, a square of height is 6 / 2.449 of B
      "      th = min(min(wu, wv), -uMapC.w);",
      "   }",
      // layer: 0 the surface (the sheet), 1 the box's near end a little behind it (uRect... see uView.w: its side faces start
      // there, so the steps between neighbouring cells of a curved surface never shade the surface itself), 2 its back
      "   int layer = aPosition.z > 0.5 ? 0 : (aPosition.z > -0.5 ? 1 : 2);",
      "   bool back = layer == 2;",
      "   int ci = (aPosition.x > 0.0 ? 1 : 0) + (aPosition.y < 0.0 ? 2 : 0);",
      "   vec3 q[4], qk[4];",
      "   bool outer = false, mine = true;",
      "   float dmin = 1e9;",
      "   vec3 me = vec3(0.0);",
      "   vec4 clip = vec4(1.0), cl;",
      "   for (int i = 0; i < 4; i++) {",
      "      vec3 qb;",
      "      vec4 clb;",
      "      mine = smProj(wf[i], view, q[i], cl) && mine;",
      "      if (th > 0.0) mine = smProj(world(c0 + off[i], Cf[i] - th * 1.6329932, inv) - uLight.xyz, view, qb, clb) && mine; else { qb = q[i]; clb = cl; }",
      "      outer = outer || abs(q[i].x) + abs(q[i].y) > 1.0 || abs(qb.x) + abs(qb.y) > 1.0;",
      "      dmin = min(dmin, min(q[i].z, qb.z));",
      "      qk[i] = qb;",
      "      if (i == ci) {",
      "         me = back ? qb : q[i];",
      "         clip = back ? clb : cl;",
      "         if (layer == 1) {",
      "            bool m1 = smProj(world(c0 + off[i], Cf[i] - min(uView.w, th) * 1.6329932, inv) - uLight.xyz, view, me, clip);",
      "         }",
      "      }",
      "   }",
      "   if (!mine || dmin < 0.03 || dmin * uLight.w >= 1.0) return;",
      param == 0 ? String.join("\n",
      "   if (outer) {", // the upper hemisphere's triangles fold at the axes: a cell across one is dropped
      "      vec4 sx = sign(vec4(q[0].x, q[1].x, q[2].x, q[3].x)), sy = sign(vec4(q[0].y, q[1].y, q[2].y, q[3].y));",
      "      vec4 bx = sign(vec4(qk[0].x, qk[1].x, qk[2].x, qk[3].x)), by = sign(vec4(qk[0].y, qk[1].y, qk[2].y, qk[3].y));",
      "      if (any(notEqual(sx, sx.xxxx)) || any(notEqual(sy, sy.xxxx)) || any(notEqual(bx, sx.xxxx)) || any(notEqual(by, sy.xxxx))) return;",
      "   }") : "",
      clipAssign(param, "clip"),
      param == 3 ? "   gl_Position = vec4(me.xy, me.z * 2.0 - 1.0, 1.0);" : "   gl_Position = vec4(me.xy, me.z * uLight.w * 2.0 - 1.0, 1.0);",
      "}");
   }

   /** One instance per point: a window pixel of the scene depth (every second), its world point from the torch into the map. */
   static final String ISM_VERT = ismVert(PARAM);

   static String ismVert(int param) {
      return String.join("\n",
      "#version 330",
      "in vec3 aPosition;",
      "uniform sampler2D SceneDepth;",
      "uniform vec4 uVp;", // the viewport's origin (window px), points across, window px between points
      "uniform vec4 pplMapA;", // PixelLight's window mapping: x - y = A.x px + A.y, x + y - 6z = A.z py + A.w
      "uniform vec4 pplMapC;", // x + y + 2z = C.x depth + C.y
      "uniform vec4 uLight;",
      "uniform vec4 uSize;", // texels per radian, a point's footprint (squares)
      projGlsl(param),
      "void main() {",
      "   int nx = int(uVp.z);",
      "   int view = gl_InstanceID % " + views(param) + ", pt = gl_InstanceID / " + views(param) + ";",
      "   vec2 f = uVp.xy + (vec2(pt % nx, pt / nx) + 0.5) * uVp.w;",
      "   float d = texelFetch(SceneDepth, ivec2(f), 0).r;",
      "   gl_Position = vec4(2.0, 2.0, 2.0, 1.0);",
      "   gl_PointSize = 1.0;",
      "   if (d >= 1.0) return;",
      "   float A = pplMapA.x * f.x + pplMapA.y, B = pplMapA.z * f.y + pplMapA.w, C = pplMapC.x * d + pplMapC.y;",
      "   float Z = (C - B) * 0.125, S = C - 2.0 * Z;",
      "   vec3 v = vec3((S + A) * 0.5, (S - A) * 0.5, Z * 2.4494897) - uLight.xyz;",
      "   float dist = length(v);",
      "   if (dist < 0.03 || dist * uLight.w >= 1.0) return;",
      "   vec3 o;",
      "   vec4 clip;",
      "   if (!smProj(v, view, o, clip) || min(min(clip.x, clip.y), min(clip.z, clip.w)) < 0.0) return;",
      param == 3 ? "   gl_Position = vec4(o.xy, o.z * 2.0 - 1.0, 1.0);" : "   gl_Position = vec4(o.xy, dist * uLight.w * 2.0 - 1.0, 1.0);",
      "   gl_PointSize = clamp(uSize.y / dist * uSize.x * 1.5, 1.0, 16.0);",
      "}");
   }

   /**
    * The geometry-shader cells (default since b5): one point a cell (gl_VertexID; the view in gl_InstanceID), the cell's
    * work done once in the geometry shader, which emits nothing for the floor round the torch, empty texels or cells out of
    * range (most of them) and the box as six strips for the rest. The vertex-shader cells (cellVert) ran it once per box
    * corner (12 a cell).
    */
   static final String GS_VERT = String.join("\n",
      "#version 330",
      "flat out int vCell;",
      "flat out int vView;",
      "void main() {",
      "   vCell = gl_VertexID;",
      "   vView = gl_InstanceID;",
      "   gl_Position = vec4(0.0, 0.0, 0.0, 1.0);",
      "}");

   static final String CELL_GEOM = cellGeom(PARAM);

   /**
    * Height above the torch's floor (squares) under which a cell's own corners are floor (or below it): it hides nothing on
    * or above the floor from the torch. Only the corners on the surface count: a corner pulled onto the front from an
    * empty texel or from what is behind has no height of its own (b4 took them, so floor cells at the edge of a floor's
    * texels and at pavement seams stood up to 0.15 squares off the floor and shaded the floor round them).
    */
   static final String FLOOR_EPS = "0.05";

   /**
    * A pulled corner (empty texel, or what is behind) on the plane of the cell's three own corners when it has them (an
    * edge cell of a floor stays on the floor, of a wall on the wall); else on the front corner's depth.
    */
   static final String PULL = "   for (int i = 0; i < 4; i++) {\n"
      + "      int a = i ^ 1, b = i ^ 2, o = i ^ 3;\n"
      + "      if (!own[i] && own[a] && own[b] && own[o]) Cf[i] = Cf[a] + Cf[b] - Cf[o];\n"
      + "   }"; // (a constant: CELL_VERT above reads it during class init)

   /**
    * pplShadowSilhouette=smooth (2026-10-26): an edge cell (some corners empty) was drawn whole, so a slanted silhouette in
    * the chunk texture became a staircase of cells and the shadow's edge teeth one cell (1/24 square) high, magnified by
    * the caster-to-receiver distance (3-4x at a carport pole). As marching squares cuts a cell: an empty corner between two
    * filled ones moves onto their diagonal, one beside a single filled corner halfway to it, one with only the opposite corner
    * filled halfway towards that.
    */
   static final String SMOOTH = "   if (uSmooth != 0) {\n"
      + "      vec3 w0[4] = wf;\n"
      + "      for (int i = 0; i < 4; i++) {\n"
      + "         if (own[i]) continue;\n"
      + "         int a = i ^ 1, b = i ^ 2, o = i ^ 3;\n"
      + "         if (own[a] && own[b]) wf[i] = 0.5 * (w0[a] + w0[b]);\n"
      + "         else if (own[a]) wf[i] = 0.5 * (w0[i] + w0[a]);\n"
      + "         else if (own[b]) wf[i] = 0.5 * (w0[i] + w0[b]);\n"
      + "         else wf[i] = 0.5 * (w0[i] + w0[o]);\n"
      + "      }\n"
      + "   }";

   static String cellGeom(int param) {
      return String.join("\n",
      "#version 330",
      "layout(points) in;",
      "layout(triangle_strip, max_vertices = 24) out;",
      clips(param) > 0 ? "out float gl_ClipDistance[" + clips(param) + "];" : "",
      "flat in int vCell[];",
      "flat in int vView[];",
      "uniform sampler2D Depth;",
      "uniform ivec4 uRect;",
      "uniform vec4 uMapAB;",
      "uniform vec4 uMapC;",
      "uniform vec4 uLight;",
      "const float LEVEL = 2.4494897;",
      "vec3 world(ivec2 t, float C, vec2 inv) {",
      "   float A = uMapAB.x * (float(t.x) + 0.5) * inv.x + uMapAB.y;",
      "   float B = uMapAB.z * (float(t.y) + 0.5) * inv.y + uMapAB.w;",
      "   float Z = (C - B) * 0.125;",
      "   float S = C - 2.0 * Z;",
      "   return vec3((S + A) * 0.5, (S - A) * 0.5, Z * LEVEL);",
      "}",
      projGlsl(param),
      "float run(ivec2 c, ivec2 st, float d0, ivec2 sz) {",
      "   float n = 0.0, prev = d0;",
      "   for (int i = 1; i <= 8; i++) {",
      "      float d = texelFetch(Depth, clamp(c + st * i, ivec2(0), sz - 1), 0).r;",
      "      if (d >= 0.99999 || abs(d - prev) > uMapC.z) break;",
      "      prev = d;",
      "      n += 1.0;",
      "   }",
      "   return n;",
      "}",
      // the surface's depth along the view at texel c (squares): the narrower of its runs across and up the screen, an
      // elliptical section by c's place across each run, at most -uMapC.w
      "float thickAt(ivec2 c, float dc, int s, ivec2 sz, vec2 inv) {",
      "   float ur = run(c, ivec2(s, 0), dc, sz), ul = run(c, ivec2(-s, 0), dc, sz);",
      "   float vr = run(c, ivec2(0, s), dc, sz), vl = run(c, ivec2(0, -s), dc, sz);",
      "   float pu = (ur - ul) / (ur + ul + 1.0), pv = (vr - vl) / (vr + vl + 1.0);",
      "   float wu = (ur + ul + 1.0) * float(s) * abs(uMapAB.x) * inv.x * 0.70710678 * sqrt(max(1.0 - pu * pu, 0.2));",
      "   float wv = (vr + vl + 1.0) * float(s) * abs(uMapAB.z) * inv.y * 0.40824829 * sqrt(max(1.0 - pv * pv, 0.2));",
      "   return min(min(wu, wv), -uMapC.w);",
      "}",
      "void emitV(vec3 o, vec4 cl) {",
      clipAssign(param, "cl"),
      param == 3 ? "   gl_Position = vec4(o.xy, o.z * 2.0 - 1.0, 1.0);" : "   gl_Position = vec4(o.xy, o.z * uLight.w * 2.0 - 1.0, 1.0);",
      "   EmitVertex();",
      "}",
      "void main() {",
      "   int s = uRect.w;",
      "   int view = vView[0], cell = vCell[0];",
      "   ivec2 c0 = uRect.xy + ivec2(cell % uRect.z, cell / uRect.z) * s;",
      "   ivec2 sz = textureSize(Depth, 0);",
      "   vec2 inv = 1.0 / vec2(sz);",
      "   ivec2 off[4] = ivec2[4](ivec2(0, 0), ivec2(s, 0), ivec2(0, s), ivec2(s, s));",
      "   float d[4];",
      "   float front = 2.0;",
      "   for (int i = 0; i < 4; i++) {",
      "      d[i] = texelFetch(Depth, clamp(c0 + off[i], ivec2(0), sz - 1), 0).r;",
      "      if (d[i] < 0.99999) front = min(front, d[i]);",
      "   }",
      "   if (front > 1.5) return;",
      "   vec3 wf[4];",
      "   float Cf[4];",
      "   bool own[4];", // the corner is the surface's own texel (not pulled onto the front)
      "   bool onFloor = true;",
      "   float dnear = 1e9;",
      "   for (int i = 0; i < 4; i++) {",
      "      own[i] = d[i] < 0.99999 && d[i] - front < uMapC.z;",
      "      Cf[i] = uMapC.x - (own[i] ? d[i] : front) * uMapC.y;",
      "   }",
      PULL,
      "   for (int i = 0; i < 4; i++) {",
      "      wf[i] = world(c0 + off[i], Cf[i], inv) - uLight.xyz;",
      "      onFloor = onFloor && (!own[i] || wf[i].z + uView.y < " + FLOOR_EPS + ");",
      "      dnear = min(dnear, length(wf[i]));",
      "   }",
      "   if (onFloor || dnear * uLight.w >= 1.0) return;",
      "#define uSmooth (uView.z > 1.5 ? 1 : 0)", // vertex / geometry cells: uView.z = thickness per corner + 2 smooth
      SMOOTH,
      // thickness per corner (uView.z > 0.5: neighbouring cells share their corners' back points, so a curved surface's back
      // is one continuous sheet, no steps), else per cell (b4)
      "   float th[4];",
      "   float thc = uMapC.w;",
      "   if (thc < 0.0) {",
      "      if (mod(uView.z, 2.0) > 0.5) {",
      "         float tsum = 0.0, tn = 0.0;",
      "         for (int i = 0; i < 4; i++) {",
      "            th[i] = -1.0;",
      "            if (own[i]) {",
      "               th[i] = thickAt(clamp(c0 + off[i], ivec2(0), sz - 1), d[i], s, sz, inv);",
      "               tsum += th[i];",
      "               tn += 1.0;",
      "            }",
      "         }",
      "         thc = tn > 0.0 ? tsum / tn : 0.0;",
      "         for (int i = 0; i < 4; i++) {",
      "            if (th[i] < 0.0) th[i] = thc;",
      "         }",
      "      } else {",
      "         ivec2 cc = clamp(c0 + ivec2(s / 2), ivec2(0), sz - 1);",
      "         float dc = texelFetch(Depth, cc, 0).r;",
      "         if (dc >= 0.99999) dc = front;",
      "         thc = thickAt(cc, dc, s, sz, inv);",
      "         for (int i = 0; i < 4; i++) th[i] = thc;",
      "      }",
      "   } else {",
      "      for (int i = 0; i < 4; i++) th[i] = thc;",
      "   }",
      "   vec3 q0[4], q1[4], q2[4];", // the surface, the box's near end (uView.w behind it), its back
      "   vec4 c0l[4], c1l[4], c2l[4];",
      "   bool mine = true, outer = false;",
      "   float dmin = 1e9;",
      "   bool box = thc > 0.0;",
      "   for (int i = 0; i < 4; i++) {",
      "      mine = smProj(wf[i], view, q0[i], c0l[i]) && mine;",
      "      if (box) {",
      "         mine = smProj(world(c0 + off[i], Cf[i] - th[i] * 1.6329932, inv) - uLight.xyz, view, q2[i], c2l[i]) && mine;",
      "         bool m1 = smProj(world(c0 + off[i], Cf[i] - min(uView.w, th[i]) * 1.6329932, inv) - uLight.xyz, view, q1[i], c1l[i]);",
      "      } else {",
      "         q2[i] = q0[i];",
      "         c2l[i] = c0l[i];",
      "      }",
      "      outer = outer || abs(q0[i].x) + abs(q0[i].y) > 1.0 || abs(q2[i].x) + abs(q2[i].y) > 1.0;",
      "      dmin = min(dmin, min(q0[i].z, q2[i].z));",
      "   }",
      "   if (!mine || dmin < 0.03 || dmin * uLight.w >= 1.0) return;",
      param == 0 ? String.join("\n",
      "   if (outer) {",
      "      vec4 sx = sign(vec4(q0[0].x, q0[1].x, q0[2].x, q0[3].x)), sy = sign(vec4(q0[0].y, q0[1].y, q0[2].y, q0[3].y));",
      "      vec4 bx = sign(vec4(q2[0].x, q2[1].x, q2[2].x, q2[3].x)), by = sign(vec4(q2[0].y, q2[1].y, q2[2].y, q2[3].y));",
      "      if (any(notEqual(sx, sx.xxxx)) || any(notEqual(sy, sy.xxxx)) || any(notEqual(bx, sx.xxxx)) || any(notEqual(by, sy.xxxx))) return;",
      "   }") : "",
      "   for (int i = 0; i < 4; i++) emitV(q0[i], c0l[i]);",
      "   EndPrimitive();",
      "   if (!box) return;",
      "   for (int i = 0; i < 4; i++) emitV(q2[i], c2l[i]);",
      "   EndPrimitive();",
      // the four sides from the near end to the back (corners 0 1 / 2 3: edges 0-1, 1-3, 3-2, 2-0)
      "   ivec4 ea = ivec4(0, 1, 3, 2), eb = ivec4(1, 3, 2, 0);",
      "   for (int e = 0; e < 4; e++) {",
      "      int a = ea[e], b = eb[e];",
      "      emitV(q1[a], c1l[a]);",
      "      emitV(q1[b], c1l[b]);",
      "      emitV(q2[a], c2l[a]);",
      "      emitV(q2[b], c2l[b]);",
      "      EndPrimitive();",
      "   }",
      "}");
   }

   /** Links a vertex + geometry + fragment program (the geometry-shader cells). 0 on failure. */
   static int link3(String vert, String geom, String frag) {
      int[] sh = {compile(GL20.GL_VERTEX_SHADER, vert), compile(GL32_GEOMETRY_SHADER, geom), compile(GL20.GL_FRAGMENT_SHADER, frag)};
      if (sh[0] == 0 || sh[1] == 0 || sh[2] == 0) {
         for (int s : sh) {
            if (s != 0) {
               GL20.glDeleteShader(s);
            }
         }
         return 0;
      }
      int p = GL20.glCreateProgram();
      for (int s : sh) {
         GL20.glAttachShader(p, s);
      }
      GL20.glLinkProgram(p);
      for (int s : sh) {
         GL20.glDeleteShader(s);
      }
      if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
         Log.warn("torch shadow map: geometry program link failed: " + GL20.glGetProgramInfoLog(p, 4096));
         GL20.glDeleteProgram(p);
         return 0;
      }
      return p;
   }

   private static final int GL32_GEOMETRY_SHADER = 0x8DD9;

   private static int compile(int type, String source) {
      int s = GL20.glCreateShader(type);
      GL20.glShaderSource(s, source);
      GL20.glCompileShader(s);
      if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
         Log.warn("torch shadow map: shader compile failed: " + GL20.glGetShaderInfoLog(s, 4096));
         GL20.glDeleteShader(s);
         return 0;
      }
      return s;
   }

   /**
    * The compute cells (GL 4.3, default since b7): one thread a cell in 8 x 8 tiles; each tile's 9 x 9 corners (depth,
    * thickness from the surface's runs) once in shared memory, then each cell's tests; a cell that casts appends its four
    * front points (from the torch) and its thickness to a list, one record per (cell, view), and the instanced draw that
    * follows (an indirect count) draws only those. The floor round the torch, empty texels and cells out of range (most
    * of the grid) cost a thread each, no vertex, no geometry-shader output space.
    * Record: 4 x vec4, corner i: xyz its front point from the torch, w = view * 64 + its thickness in x + y + 2z.
    */
   static String cellComp(int param) {
      return String.join("\n",
      "#version 430",
      "layout(local_size_x = 8, local_size_y = 8) in;",
      "uniform sampler2D Depth;",
      "uniform ivec4 uRect;", // first texel x, y; cells across, down
      "uniform int uCell;", // texels a cell
      "uniform int uCap;", // records the list holds
      "uniform vec4 uMapAB;",
      "uniform vec4 uMapC;",
      "uniform vec4 uLight;",
      "uniform int uSmooth;", // pplShadowSilhouette=smooth: the empty corners of an edge cell cut it as marching squares does
      "layout(std430, binding = 0) buffer Cmd { uint cmd[5]; };", // DrawElementsIndirectCommand: count, instanceCount, ...
      "layout(std430, binding = 1) writeonly buffer Rec { vec4 rec[]; };",
      "const float LEVEL = 2.4494897;",
      "shared float sD[81];",
      "shared float sT[81];",
      "vec3 world(ivec2 t, float C, vec2 inv) {",
      "   float A = uMapAB.x * (float(t.x) + 0.5) * inv.x + uMapAB.y;",
      "   float B = uMapAB.z * (float(t.y) + 0.5) * inv.y + uMapAB.w;",
      "   float Z = (C - B) * 0.125;",
      "   float S = C - 2.0 * Z;",
      "   return vec3((S + A) * 0.5, (S - A) * 0.5, Z * LEVEL);",
      "}",
      projGlsl(param),
      "float run(ivec2 c, ivec2 st, float d0, ivec2 sz) {",
      "   float n = 0.0, prev = d0;",
      "   for (int i = 1; i <= 8; i++) {",
      "      float d = texelFetch(Depth, clamp(c + st * i, ivec2(0), sz - 1), 0).r;",
      "      if (d >= 0.99999 || abs(d - prev) > uMapC.z) break;",
      "      prev = d;",
      "      n += 1.0;",
      "   }",
      "   return n;",
      "}",
      "float thickAt(ivec2 c, float dc, int s, ivec2 sz, vec2 inv) {",
      "   float ur = run(c, ivec2(s, 0), dc, sz), ul = run(c, ivec2(-s, 0), dc, sz);",
      "   float vr = run(c, ivec2(0, s), dc, sz), vl = run(c, ivec2(0, -s), dc, sz);",
      "   float pu = (ur - ul) / (ur + ul + 1.0), pv = (vr - vl) / (vr + vl + 1.0);",
      "   float wu = (ur + ul + 1.0) * float(s) * abs(uMapAB.x) * inv.x * 0.70710678 * sqrt(max(1.0 - pu * pu, 0.2));",
      "   float wv = (vr + vl + 1.0) * float(s) * abs(uMapAB.z) * inv.y * 0.40824829 * sqrt(max(1.0 - pv * pv, 0.2));",
      "   return min(min(wu, wv), -uMapC.w);",
      "}",
      // one corner of the tile into shared memory: its depth (2: empty) and, when the thickness comes from the runs, its thickness
      "void corner(ivec2 g, ivec2 base, int s, ivec2 sz, vec2 inv) {",
      "   ivec2 t = clamp(uRect.xy + (base + g) * s, ivec2(0), sz - 1);",
      "   float d = texelFetch(Depth, t, 0).r;",
      "   int k = g.y * 9 + g.x;",
      "   sD[k] = d < 0.99999 ? d : 2.0;",
      "   sT[k] = uMapC.w < 0.0 && d < 0.99999 ? thickAt(t, d, s, sz, inv) : max(uMapC.w, 0.0);",
      "}",
      "void main() {",
      "   int s = uCell;",
      "   ivec2 sz = textureSize(Depth, 0);",
      "   vec2 inv = 1.0 / vec2(sz);",
      "   ivec2 l = ivec2(gl_LocalInvocationID.xy), base = ivec2(gl_WorkGroupID.xy) * 8;",
      "   corner(l, base, s, sz, inv);",
      "   if (l.x == 7) corner(ivec2(8, l.y), base, s, sz, inv);",
      "   if (l.y == 7) corner(ivec2(l.x, 8), base, s, sz, inv);",
      "   if (l.x == 7 && l.y == 7) corner(ivec2(8, 8), base, s, sz, inv);",
      "   barrier();",
      "   ivec2 cell = base + l;",
      "   if (cell.x >= uRect.z || cell.y >= uRect.w) return;",
      "   ivec2 c0 = uRect.xy + cell * s;",
      "   ivec2 off[4] = ivec2[4](ivec2(0, 0), ivec2(s, 0), ivec2(0, s), ivec2(s, s));",
      "   int kk[4] = int[4](l.y * 9 + l.x, l.y * 9 + l.x + 1, l.y * 9 + l.x + 9, l.y * 9 + l.x + 10);",
      "   float d[4];",
      "   float front = 2.0;",
      "   for (int i = 0; i < 4; i++) {",
      "      d[i] = sD[kk[i]];",
      "      front = min(front, d[i]);",
      "   }",
      "   if (front > 1.5) return;",
      "   vec3 wf[4];",
      "   float Cf[4];",
      "   bool own[4];",
      "   bool onFloor = true;",
      "   float dnear = 1e9;",
      "   for (int i = 0; i < 4; i++) {",
      "      own[i] = d[i] < 1.5 && d[i] - front < uMapC.z;",
      "      Cf[i] = uMapC.x - (own[i] ? d[i] : front) * uMapC.y;",
      "   }",
      PULL,
      "   for (int i = 0; i < 4; i++) {",
      "      wf[i] = world(c0 + off[i], Cf[i], inv) - uLight.xyz;",
      "      onFloor = onFloor && (!own[i] || wf[i].z + uView.y < " + FLOOR_EPS + ");",
      "      dnear = min(dnear, length(wf[i]));",
      "   }",
      "   if (onFloor || dnear * uLight.w >= 1.0) return;",
      SMOOTH,
      "   float th[4];",
      "   float tsum = 0.0, tn = 0.0;",
      "   for (int i = 0; i < 4; i++) {",
      "      th[i] = own[i] ? sT[kk[i]] : -1.0;",
      "      if (own[i]) { tsum += th[i]; tn += 1.0; }",
      "   }",
      "   float thc = tn > 0.0 ? tsum / tn : 0.0;",
      "   for (int i = 0; i < 4; i++) {",
      "      if (th[i] < 0.0) th[i] = thc;",
      "   }",
      // back along the view by th squares: x + y + 2z falls 1.633 th; the world point moves by that times (3/8, 3/8, LEVEL/8)
      "   const vec3 BACK = vec3(0.375, 0.375, 0.30618622);",
      "   for (int view = 0; view < " + views(param) + "; view++) {",
      "      bool mine = true, outer = false;",
      "      float dmin = 1e9;",
      "      vec3 q0[4], q2[4];",
      "      vec4 cl;",
      "      for (int i = 0; i < 4; i++) {",
      "         mine = smProj(wf[i], view, q0[i], cl) && mine;",
      "         mine = smProj(wf[i] - th[i] * 1.6329932 * BACK, view, q2[i], cl) && mine;",
      "         outer = outer || abs(q0[i].x) + abs(q0[i].y) > 1.0 || abs(q2[i].x) + abs(q2[i].y) > 1.0;",
      "         dmin = min(dmin, min(q0[i].z, q2[i].z));",
      "      }",
      "      if (!mine || dmin < 0.03 || dmin * uLight.w >= 1.0) continue;",
      param == 0 ? String.join("\n",
      "      if (outer) {",
      "         vec4 sx = sign(vec4(q0[0].x, q0[1].x, q0[2].x, q0[3].x)), sy = sign(vec4(q0[0].y, q0[1].y, q0[2].y, q0[3].y));",
      "         vec4 bx = sign(vec4(q2[0].x, q2[1].x, q2[2].x, q2[3].x)), by = sign(vec4(q2[0].y, q2[1].y, q2[2].y, q2[3].y));",
      "         if (any(notEqual(sx, sx.xxxx)) || any(notEqual(sy, sy.xxxx)) || any(notEqual(bx, sx.xxxx)) || any(notEqual(by, sy.xxxx))) continue;",
      "      }") : "",
      "      uint r = atomicAdd(cmd[1], 1u);",
      "      if (r >= uint(uCap)) continue;",
      "      for (int i = 0; i < 4; i++) rec[r * 4u + uint(i)] = vec4(wf[i], float(view) * 64.0 + th[i] * 1.6329932);",
      "   }",
      "}");
   }

   /** The compute cells' draw: one instance a record, the box's 12 corners (layer 0 the surface, 1 the near end, 2 the back). */
   static String csVert(int param) {
      return String.join("\n",
      "#version 430",
      clips(param) > 0 ? "out float gl_ClipDistance[" + clips(param) + "];" : "",
      "in vec3 aPosition;",
      "uniform vec4 uLight;",
      "uniform int uCap;",
      "layout(std430, binding = 1) readonly buffer Rec { vec4 rec[]; };",
      projGlsl(param),
      "void main() {",
      "   if (gl_InstanceID >= uCap) {", // the list overflowed: the count went on, the records did not
      "      gl_Position = vec4(2.0, 2.0, 2.0, 1.0);",
      clipAssign(param, null).replace("   gl_", "      gl_"),
      "      return;",
      "   }",
      "   int ci = (aPosition.x > 0.0 ? 1 : 0) + (aPosition.y < 0.0 ? 2 : 0);",
      "   int layer = aPosition.z > 0.5 ? 0 : (aPosition.z > -0.5 ? 1 : 2);",
      "   vec4 p = rec[gl_InstanceID * 4 + ci];",
      "   float view = floor(p.w / 64.0), dc = p.w - view * 64.0;",
      "   float back = layer == 0 ? 0.0 : (layer == 1 ? min(uView.w * 1.6329932, dc) : dc);",
      "   vec3 o;",
      "   vec4 clip;",
      "   bool m = smProj(p.xyz - back * vec3(0.375, 0.375, 0.30618622), int(view), o, clip);",
      clipAssign(param, "clip"),
      param == 3 ? "   gl_Position = vec4(o.xy, o.z * 2.0 - 1.0, 1.0);" : "   gl_Position = vec4(o.xy, o.z * uLight.w * 2.0 - 1.0, 1.0);",
      "}");
   }

   static final String CELL_FRAG = String.join("\n",
      "#version 330",
      "void main() {",
      "}");

   /** pplShadowFilter=evsm | msm: the cell's moments (3: EVSM, warps 40 / 5; 4: raw 4 moments for Hamburger MSM). */
   static final String MOMENT_FRAG = String.join("\n",
      "#version 330",
      "uniform float uKind;",
      "out vec4 oMoments;",
      "void main() {",
      "   float d = gl_FragCoord.z;",
      "   if (uKind < 3.5) {",
      "      float p = exp(40.0 * d), n = -exp(-5.0 * d);",
      "      oMoments = vec4(p, p * p, n, n * n);",
      "   } else {",
      "      float d2 = d * d;",
      "      oMoments = vec4(d, d2, d2 * d, d2 * d2);",
      "   }",
      "}");

   static final String BLUR_VERT = String.join("\n",
      "#version 330",
      "void main() {",
      "   gl_Position = vec4(float(gl_VertexID & 1) * 2.0 - 1.0, float(gl_VertexID >> 1) * 2.0 - 1.0, 0.0, 1.0);",
      "}");

   static final String BLUR_FRAG = String.join("\n",
      "#version 330",
      "uniform sampler2D Src;",
      "uniform ivec2 uDir;",
      "out vec4 o;",
      "void main() {",
      "   ivec2 p = ivec2(gl_FragCoord.xy), hi = textureSize(Src, 0) - 1;",
      "   o = texelFetch(Src, p, 0) * 0.375",
      "      + (texelFetch(Src, clamp(p + uDir, ivec2(0), hi), 0) + texelFetch(Src, clamp(p - uDir, ivec2(0), hi), 0)) * 0.25",
      "      + (texelFetch(Src, clamp(p + 2 * uDir, ivec2(0), hi), 0) + texelFetch(Src, clamp(p - 2 * uDir, ivec2(0), hi), 0)) * 0.0625;",
      "}");

   /**
    * The composite's read (PixelLight's LIGHT_GLSL under PPL_SM_MAP): the share of the torch reaching P, from the map on
    * the mask's unit. The receiver moves along its normal by pplSmQ.z map texels (a texel spans ~3 d / N squares at d).
    */
   static final String SAMPLE_GLSL; // (after FILTER_PICK: static init order)

   /** The filter, compiled in (PPL_SM_FILTER: pplShadowFilter, or a dev variant's). */
   static final String FILTER_PICK = String.join("\n",
      "#if PPL_SM_FILTER == 0",
      "   return texelFetch(pplShadowMask, ivec2(uv * pplSmO.yz), 0).r >= ref ? 1.0 : 0.0;",
      "#elif PPL_SM_FILTER == 4",
      "   return pplSmMsm(uv, ref);",
      "#elif PPL_SM_FILTER == 3",
      "   return pplSmEvsm(uv, ref);",
      "#elif PPL_SM_FILTER == 5",
      "   return pplSmSoft(uv, ref, d);",
      "#elif PPL_SM_FILTER == 2",
      "   return pplSmPcss(uv, ref, d);",
      "#else",
      "   return pplSmPcf(uv, ref);",
      "#endif");

   static {
      SAMPLE_GLSL = sampleGlsl(PARAM);
   }

   static String sampleGlsl(int param) {
      return String.join("\n",
      // (the march's uniforms, other meanings here) pplSmP: the torch x, y (relative to the origin square), z in squares of
      // height, 1 / the map's range; pplSmV: texels per radian (about, where it is coarsest), bias (squares), normal offset
      // (texels), lens radius (squares); pplSmO: the filter (0 hard, 1 pcf, 2 pcss, 3 evsm, 4 msm), the texture's width and
      // height, the cube face's widening
      "#ifndef PPL_SM_FILTER",
      "#define PPL_SM_FILTER " + configFilter(),
      "#endif",
      uvGlsl(param),
      "float pplSmPcf(vec2 uv, float ref) {", // bilinear: the share of the 2x2 texels round uv farther than ref
      "   vec2 tc = uv * pplSmO.yz - 0.5;",
      "   vec2 f = fract(tc);",
      "   vec4 g = textureGather(pplShadowMask, (floor(tc) + 1.0) / pplSmO.yz);",
      "   vec4 s = step(vec4(ref), g);",
      "   return mix(mix(s.w, s.z, f.x), mix(s.x, s.y, f.x), f.y);",
      "}",
      // contact-hardening (PCSS): the blockers inside the lens's cone towards the receiver (3x3 gathers, at most 16 texels out:
      // a blocker 0.4 squares from the torch), their mean distance, the penumbra the lens leaves at the receiver
      // (lens (d - db) / db), filtered by 3x3 bilinear taps that wide
      "float pplSmPcss(vec2 uv, float ref, float dr) {",
      "   float tpr = pplSmV.x;", // map texels per radian, about
      "   vec2 rs = clamp(pplSmV.w / 0.4 * tpr, 1.0, 16.0) / 1.5 / pplSmO.yz;",
      "   float bsum = 0.0, bn = 0.0;",
      "   for (int j = -1; j <= 1; j++) {",
      "      for (int i = -1; i <= 1; i++) {",
      "         vec4 g = textureGather(pplShadowMask, uv + vec2(i, j) * rs);",
      "         vec4 b = step(g, vec4(ref));",
      "         bsum += dot(b, g);",
      "         bn += dot(b, vec4(1.0));",
      "      }",
      "   }",
      "   if (bn < 0.5) return 1.0;",
      "   float db = bsum / bn / pplSmP.w;",
      "   float w = pplSmV.w * max(dr - db, 0.0) / max(db, 0.05);",
      "   vec2 rf = clamp(w / max(dr, 0.05) * tpr, 0.5, 16.0) / 1.5 / pplSmO.yz;",
      "   float sum = 0.0;",
      "   for (int j = -1; j <= 1; j++) {",
      "      for (int i = -1; i <= 1; i++) {",
      "         sum += pplSmPcf(uv + vec2(i, j) * rf, ref);",
      "      }",
      "   }",
      "   return sum / 9.0;",
      "}",
      // soft (b10): PCSS with 4 + 4 taps: the blocker search's 4 gathers and the filter's 4 bilinear taps on a rotated grid
      // (one fixed pattern, no noise: no shimmer without TAA); a lit pixel stops after the 4 search gathers
      "const vec2 pplSmRg[4] = vec2[4](vec2(-0.25, -0.75), vec2(0.75, -0.25), vec2(0.25, 0.75), vec2(-0.75, 0.25));",
      "float pplSmSoft(vec2 uv, float ref, float dr) {",
      "   float tpr = pplSmV.x;",
      "   vec2 rs = clamp(pplSmV.w / 0.4 * tpr, 1.0, 16.0) / pplSmO.yz;",
      "   float bsum = 0.0, bn = 0.0;",
      "   for (int i = 0; i < 4; i++) {",
      "      vec4 g = textureGather(pplShadowMask, uv + pplSmRg[i] * rs);",
      "      vec4 b = step(g, vec4(ref));",
      "      bsum += dot(b, g);",
      "      bn += dot(b, vec4(1.0));",
      "   }",
      "   if (bn < 0.5) return 1.0;",
      "   float db = bsum / bn / pplSmP.w;",
      "   float w = pplSmV.w * max(dr - db, 0.0) / max(db, 0.05);",
      "   vec2 rf = clamp(w / max(dr, 0.05) * tpr, 0.5, 16.0) / pplSmO.yz;",
      "   float sum = 0.0;",
      "   for (int i = 0; i < 4; i++) {",
      "      sum += pplSmPcf(uv + pplSmRg[i] * rf, ref);",
      "   }",
      "   return sum * 0.25;",
      "}",
      // exponential variance shadow maps: Chebyshev's bound on both warps, the lesser, light bleeding cut below 0.25
      "float pplCheb(vec2 m, float t, float mv) {",
      "   if (t <= m.x) return 1.0;",
      "   float v = max(m.y - m.x * m.x, mv), e = t - m.x;",
      "   return v / (v + e * e);",
      "}",
      "float pplSmEvsm(vec2 uv, float ref) {",
      "   vec4 m = texture(pplShadowMask, uv);",
      "   float p = exp(40.0 * ref), q = -exp(-5.0 * ref);",
      "   float a = pplCheb(m.xy, p, 1e-8 * p * p * 1600.0), b = pplCheb(m.zw, q, 1e-8 * q * q * 25.0);",
      "   return clamp((min(a, b) - 0.25) / 0.75, 0.0, 1.0);",
      "}",
      // Hamburger 4-moment shadow maps (Peters and Klein 2015), raw moments in fp32
      "float pplSmMsm(vec2 uv, float zf) {",
      "   vec4 b = mix(texture(pplShadowMask, uv), vec4(0.5), 3e-5);",
      "   float L32D22 = -b.x * b.y + b.z;",
      "   float D22 = -b.x * b.x + b.y;",
      "   float sdv = -b.y * b.y + b.w;",
      "   float D33D22 = dot(vec2(sdv, -L32D22), vec2(D22, L32D22));",
      "   float InvD22 = 1.0 / D22;",
      "   float L32 = L32D22 * InvD22;",
      "   vec3 c = vec3(1.0, zf, zf * zf);",
      "   c.y -= b.x;",
      "   c.z -= b.y + L32 * c.y;",
      "   c.y *= InvD22;",
      "   c.z *= D22 / D33D22;",
      "   c.y -= L32 * c.z;",
      "   c.x -= dot(c.yz, b.xy);",
      "   float p = c.y / c.z, q = c.x / c.z;",
      "   float r = sqrt(max(p * p * 0.25 - q, 0.0));",
      "   float z1 = -p * 0.5 - r, z2 = -p * 0.5 + r;",
      "   vec4 sw = z2 < zf ? vec4(z1, zf, 1.0, 1.0) : (z1 < zf ? vec4(zf, z1, 0.0, 1.0) : vec4(0.0));",
      "   float quot = (sw.x * z2 - b.x * (sw.x + z2) + b.y) / ((z2 - sw.y) * (zf - z1));",
      "   return 1.0 - clamp(sw.z + sw.w * quot, 0.0, 1.0);",
      "}",
      param == 3 ? String.join("\n",
      // planar: the receiver projected from the torch onto the floor like the casters, its share of the drop the reference
      // (pplSmV.x: map texels per square on the floor; pcss reads as pcf)
      "float pplMaskN(vec3 P, vec3 n) {",
      "   vec3 v0 = P * vec3(1.0, 1.0, PPL_LEVEL) - pplSmP.xyz;",
      "   if (v0.z > -0.02) return 1.0;",
      "   float ndl = clamp(dot(n, -normalize(v0)), 0.05, 1.0);",
      "   float tnt = min(sqrt(1.0 - ndl * ndl) / ndl, 6.0);",
      "   float tx = v0.z / -pplSmO.w / pplSmV.x;", // a map texel at the receiver, squares
      "   vec3 v = v0 + n * (pplSmV.z * tx * (1.0 + tnt));",
      "   float r = -min(v.z, -1e-4) / pplSmO.w;",
      "   vec2 uv = pplSmUv(v);",
      "   if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))) || r >= 0.999) return 1.0;",
      "   float ref = r - (pplSmV.y + 0.5 * tx * tnt) / pplSmO.w;",
      FILTER_PICK.replace("pplSmPcss(uv, ref, d)", "pplSmPcf(uv, ref)").replace("pplSmSoft(uv, ref, d)", "pplSmPcf(uv, ref)"),
      "}") : String.join("\n",
      "float pplMaskN(vec3 P, vec3 n) {",
      "   vec3 Pm = P * vec3(1.0, 1.0, PPL_LEVEL);",
      "   float d0 = length(Pm - pplSmP.xyz);",
      // slope-scaled: a surface the torch grazes moves further along its normal and takes more depth bias (a map texel
      // spans d0 / texels-per-radian squares across the ray, tan(angle) times that along the surface)
      "   float ndl = clamp(dot(n, (pplSmP.xyz - Pm) / max(d0, 1e-4)), 0.05, 1.0);",
      "   float tnt = min(sqrt(1.0 - ndl * ndl) / ndl, 6.0);",
      "   float tx = d0 / pplSmV.x;",
      "   vec3 v = Pm + n * (pplSmV.z * tx * (1.0 + tnt)) - pplSmP.xyz;",
      "   float d = length(v);",
      "   if (d * pplSmP.w >= 0.999) return 1.0;",
      "   vec2 uv = pplSmUv(v);",
      "   float ref = (d - pplSmV.y - 0.5 * tx * tnt) * pplSmP.w;",
      FILTER_PICK,
      "}"));
   }
}
