package pzopt;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoLightSource;
import zombie.iso.IsoRoomLight;
import zombie.iso.IsoWorld;
import zombie.iso.LightingJNI;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;

/**
 * Per-pixel lighting of the static world ({@code pixelLight}, 2026-09-25).
 *
 * <p>Stock bakes the light into the chunk textures: floors get the four corner colours of their square (libLighting's
 * {@code cacheVertLight} 0-3, each about the brightest of the four squares around the corner, which is what makes the light
 * blocky), walls the bottom and top corners, objects the flat {@code lightInfo}; every change re-bakes the chunk level
 * (torches, headlights, lightning, dusk, the vision fade). Here the chunk textures bake unlit (the chunk's squares hand out
 * white light while it bakes) and the chunk composite shader lights every pixel:
 * <ul>
 *   <li>three small texture arrays, one texel per square, toroidal in x, y (the chunk grid) and z (32 levels): the square's
 *       own light without its handheld torch (the native's sample at the centre, what stock draws objects with) and a
 *       "simple square" flag; the connectivity to its eight neighbours (shared corner colours: the native breaks them at
 *       walls), whether the torch reaches it for the native, whether it has a vertical gradient; the wall gradient
 *       (top corners' mean minus the bottom's). A light change re-packs a chunk level: 768 bytes;</li>
 *   <li>per pixel: the world position from the depth (linear in x + y + 2z under the iso projection), the light between
 *       the square centres (one bilinear fetch when all four are simple, else masked by the connectivity), the handheld
 *       torch from its fitted cone (x the native's visibility), vehicle lights' and point lights' shapes on top of the
 *       native's values, the surface's facing to each light from the depth's normal, the torch shadow mask of the
 *       previous frame (reprojected; {@code pplShadows}).</li>
 * </ul>
 * Split screen, the Mac (GL 2.1) and any GL failure fall back to the stock baked light (every texture re-baked).
 *
 * <p>Dev rig {@code devPplDumpAt=s1,s2}: the cached lighting of every loaded square near the player, every light
 * source and the camera to {@code ~/Zomboid/pzopt-ppl/<tag>-squares.txt}, plus the scene depth / colour right after the
 * chunk composite; {@code harness/ppl/} reads them.
 */
public final class PixelLight {
   private PixelLight() {
   }

   /** The chunk textures bake unlit and this pass lights them. Read by the game thread and the lighting-read workers. */
   public static volatile boolean ACTIVE = Config.PIXEL_LIGHT && Overrides.enabled() && !CoreGl.legacyMac();

   static final int LEVELS = 32; // levels the lattice holds (level & 31): every chunk of the vanilla map (-17..8 at the Rosewood base, 0..29 in Louisville) and its air level
   /** IsoDepthHelper: depth per unit of x + y (SQUARE_DEPTH / 2; a level adds 2 units) */
   static final double DEPTH_PER_XY = 0.0028867084 / 2.0;

   private static volatile boolean failed;
   private static boolean rebakeAll;

   private static void fail(String why) {
      if (!failed) {
         failed = true;
         ACTIVE = false;
         rebakeAll = true;
         Log.warn("pixel light: " + why + "; stock baked light for the rest of the session");
      }
   }

   // ------------------------------------------------------------------------------------------------ bake (game thread)

   private static final ArrayList<LightingJNI.JNILighting> whitened = new ArrayList<>();
   private static IsoChunk bakeChunk;
   public static long bakes, whitenedSquares;

   /** A chunk texture starts baking: every square of the chunk hands out white light until its texture is done. */
   public static void bakeBegin(IsoChunk c, int playerIndex) {
      if (bakeChunk == c) {
         return;
      }
      bakeEnd();
      if (bakeChunk != null) {
         unwhitenAll(); // a new texture while the last one still caches: cannot happen, but never leave squares white
      }
      bakeChunk = c;
      bakes++;
      for (int z = c.minLevel; z <= c.maxLevel; z++) {
         IsoGridSquare[] squares = c.squares[z - c.minLevel];
         for (int i = 0; i < squares.length; i++) {
            IsoGridSquare sq = squares[i];
            if (sq != null && sq.lighting[playerIndex] instanceof LightingJNI.JNILighting jl) {
               jl.pzoptWhiten();
               whitened.add(jl);
            }
         }
         int li = z + 32;
         if (li >= 0 && li < 64) {
            c.pzoptPplDirty[li] = 1; // the bake refreshes squares' light lazily
         }
      }
      whitenedSquares += whitened.size();
   }

   /**
    * pplUnseenAmbient: the player has never seen the square (the lattice's "visible" bit is off): it bakes with its own light,
    * black as stock draws it, and the composite lights it with the ambient. Stock draws ForceAmbient sprites (roofs) with the
    * ambient whatever the square's light, so the roofs of undiscovered buildings show; lit from the square they were black,
    * and the joined roof tile stock draws per frame over its baked copy (an eave over a garage) fought that black copy in the
    * depth test (-1e-5 is under a DEPTH16 step): dashes that changed with every camera step (2026-10-10).
    */
   public static boolean unseen(IsoGridSquare sq, int playerIndex) {
      return Config.PPL_UNSEEN_AMBIENT && ACTIVE && sq != null && playerIndex >= 0 && playerIndex < sq.lighting.length
         && sq.lighting[playerIndex] instanceof LightingJNI.JNILighting jl && (jl.pzoptVis() & 7) == 0;
   }

   /** IsoObject.prepareToRender: the ambient a ForceAmbient sprite is drawn with (1 into a bake on a never seen square: the composite adds it). */
   public static float forceAmbient(IsoGridSquare sq, int playerIndex, float ambient) {
      return FBORenderChunkManager.instance.isCaching() && unseen(sq, playerIndex) ? 1.0F : ambient;
   }

   /** After a chunk level's bake ended (and once before the composite): the real light back once the texture is done. */
   public static void bakeEnd() {
      if (bakeChunk == null || FBORenderChunkManager.instance.isCaching()) {
         return;
      }
      unwhitenAll();
   }

   private static void unwhitenAll() {
      for (int i = 0; i < whitened.size(); i++) {
         whitened.get(i).pzoptUnwhiten();
      }
      whitened.clear();
      bakeChunk = null;
   }

   /** A square's light was re-read from the native (game thread or a lighting-read worker; one level per task). */
   public static void lightChanged(IsoGridSquare square) {
      IsoChunk c = square.chunk;
      int li = square.z + 32;
      if (c != null && li >= 0 && li < 64) {
         c.pzoptPplDirty[li] = 1;
      }
   }

   // ------------------------------------------------------------------------------------------------ lattice (game thread)

   private static int n; // squares per side of the lattice (power of two, >= the chunk grid)
   private static IsoChunk[] slotChunk;
   private static int[] slotLevel; // per slot and level & (LEVELS - 1): the level uploaded there
   private static boolean[] slotAir; // per slot and level & (LEVELS - 1): some squares of that level took their column's light from below (pplAirFill)
   private static boolean[] slotLite; // per slot and level & (LEVELS - 1): packed lite (a ring chunk: no connectivity), repacked in full once on screen
   private static boolean[] slotAirPending; // per slot: a light change waits for the next refresh of the borrowed levels
   private static long[] slotAirFrame; // per slot: the frame its borrowed levels were last refreshed (pplAirFill carries a light change up at most every AIR_REFRESH_FRAMES)
   private static final int AIR_REFRESH_FRAMES = 120;
   private static final ArrayList<Frame> RING = new ArrayList<>();
   static long blocksPacked() { // torchSource's cycle rig: lattice blocks packed so far
      return blocksUploaded;
   }

   private static long frames, blocksUploaded, blocksCopied, framesFull, packNs, packSimple, packHidden, packSlow;
   private static int traceSq, traceSeen; // dev (devPplTrace): this frame's packed squares, the seen ones
   private static long traceLum; // and the sum of their packed light
   private static int logCountdown;

   /** composite: the chunk composite shader lights each fragment (no extra pass); pass: a full-screen pass after it */
   static boolean compositeMode() {
      return !"pass".equals(Config.PPL_MODE) && chunkShaderPatched;
   }

   private static Frame pendingPass;
   private static int shadowLight = -1; // this frame's light that gets the shadow mask (composite mode), -1: none
   static volatile int costMask; // dev (devPplCostAt): parts of the shader switched off, for attributing its cost

   /**
    * Game thread, after the bakes and right before FBORenderChunkManager.endFrame() (the chunk composite): the lattice
    * blocks to upload and this frame's camera, queued ahead of the composite (composite mode) or kept for the pass.
    */
   public static void beforeComposite(int playerIndex, ArrayList<IsoChunk> onScreen) {
      if (worldUpNs == 0L && IsoWorld.instance != null && IsoWorld.instance.currentCell != null) {
         worldUpNs = System.nanoTime();
      }
      if (!Config.DEV_PPL_TOGGLE_AT.isEmpty()) {
         scheduledToggles();
      }
      if (!Config.DEV_PPL_COST_AT.isEmpty() && worldUpNs != 0L) {
         double t = (System.nanoTime() - worldUpNs) / 1e9;
         for (String part : Config.DEV_PPL_COST_AT.split(",")) {
            String[] kv = part.split(":");
            if (t >= Double.parseDouble(kv[0].trim())) {
               costMask = Integer.parseInt(kv[1].trim());
            }
         }
      }
      if (!Config.DEV_PPL_ALTERNATE.isEmpty() && worldUpNs != 0L) {
         String[] a = Config.DEV_PPL_ALTERNATE.split(",");
         double t = (System.nanoTime() - worldUpNs) / 1e9 - Double.parseDouble(a[0].trim());
         if (t >= 0.0) {
            costMask = Integer.parseInt(a[((long)(t / Double.parseDouble(a[1].trim())) & 1L) == 0L ? 2 : 3].trim());
         }
      }
      if (rebakeAll) {
         rebakeAll = false;
         rebakeEverything();
      }
      pendingPass = null;
      if (Config.DEV_PPL_TIMING) {
         SpriteRenderer.instance.drawGeneric(COMPOSITE_START);
      }
      if (!ACTIVE) {
         if (chunkShaderPatched && Gl.onSent) {
            SpriteRenderer.instance.drawGeneric(OFF); // the composite shader back to the stock light
         }
         return;
      }
      if (IsoPlayer.numPlayers > 1) {
         fail("split screen");
         return;
      }
      int grid = Math.max(IsoChunkMap.chunkGridWidth, 1) * 8;
      int want = 64;
      while (want < grid) {
         want <<= 1;
      }
      if (want != n) {
         n = want;
         int slots = (n / 8) * (n / 8);
         slotChunk = new IsoChunk[slots];
         slotLevel = new int[slots * LEVELS];
         slotAir = new boolean[slots * LEVELS];
         slotAirFrame = new long[slots];
         slotAirPending = new boolean[slots];
         slotLite = new boolean[slots * LEVELS];
         java.util.Arrays.fill(slotLevel, Integer.MIN_VALUE);
         Log.info("pixel light: " + n + "x" + n + " squares x " + LEVELS + " levels, 3 x " + n * n * LEVELS * 4 / 1048576 + " MB, chunk grid " + IsoChunkMap.chunkGridWidth
            + ", mode " + (compositeMode() ? "composite" : "pass"));
      }
      if (chunkShaderPatched && (zombie.core.SceneShaderStore.chunkRenderShader == null || !zombie.core.SceneShaderStore.chunkRenderShader.isCompiled())) {
         chunkShaderPatched = false; // the game dropped the chunk composite shader (it would draw the unlit texture): light in a pass instead
         Log.warn("pixel light: the chunk composite shader is not in use, pass mode");
      }
      Frame f = freeFrame();
      f.n = n;
      f.blocks = 0;
      f.composite = compositeMode();
      f.ox = (int)Math.floor(IsoCamera.frameState.camCharacterX);
      f.oy = (int)Math.floor(IsoCamera.frameState.camCharacterY);
      ambR = Math.min(1.0F, ambR + 0.0005F);
      ambG = Math.min(1.0F, ambG + 0.0005F);
      ambB = Math.min(1.0F, ambB + 0.0005F);
      int s = n / 8;
      long packT0 = System.nanoTime();
      if (slotStamp == null || slotStamp.length != s * s) {
         slotStamp = new long[s * s];
         slotTop = new int[s * s];
      }
      ArrayList<IsoChunk> packList = Config.PPL_AIR_FILL || Config.PPL_PACK_RING ? withRing(onScreen, s) : onScreen;
      SERIAL_CTX.begin();
      if (Config.PPL_PACK_PARALLEL && GtAb.on(GtAb.PPL_PACK) && Config.DEV_PPL_PROBE <= 0 && !Config.DEV_PPL_TRACE && !packParallelFailed && packList.size() >= 8) {
         packParallel(f, packList, onScreen.size(), s, playerIndex);
      } else {
         for (int i = 0; i < packList.size(); i++) {
            packChunkLevels(SERIAL_CTX, f, packList.get(i), i >= onScreen.size(), s, playerIndex, false); // withRing lists the on-screen chunks first
         }
      }
      SERIAL_CTX.end();
      blocksUploaded += f.blocks;
      if (Config.DEV_PPL_PROBE > 0) {
         probe(playerIndex, f.blocks);
      }
      ambientCommit();
      packNs += System.nanoTime() - packT0;
      // the camera: window px -> (x - y, x + y - 6z) and depth -> x + y + 2z, relative to the origin square
      Core core = Core.getInstance();
      int ox = (int)Math.floor(IsoCamera.frameState.camCharacterX), oy = (int)Math.floor(IsoCamera.frameState.camCharacterY);
      zombie.iso.PlayerCamera cam = IsoCamera.cameras[playerIndex];
      f.zoom = core.getZoom(playerIndex);
      f.ts = Core.tileScale;
      f.offX = IsoCamera.getOffX();
      f.offY = IsoCamera.getOffY();
      f.screenW = IsoCamera.getScreenWidth(playerIndex);
      f.screenH = IsoCamera.getScreenHeight(playerIndex);
      f.ox = ox;
      f.oy = oy;
      f.jx = cam.fixJigglyModelsSquareX;
      f.jy = cam.fixJigglyModelsSquareY;
      f.d0 = IsoDepthHelper.getSquareDepthData(ox, oy, ox, oy, 0.0F).depthStart;
      f.lights = Config.PPL_ANALYTIC ? gatherLights(f, playerIndex) : 0;
      // the chunk textures composited this frame, keyed by their depth texture (the StartShader draw carries it): the render
      // thread picks the light-free variant for those no dynamic light reaches
      ArrayList<zombie.iso.fboRenderChunk.FBORenderChunk> list = FBORenderChunkManager.instance.toRenderThisFrame;
      f.chunks = 0;
      for (int i = 0; i < list.size() && f.chunks < f.chunkKeys.length; i++) {
         zombie.iso.fboRenderChunk.FBORenderChunk rc = list.get(i);
         if (rc.depth == null || rc.chunk == null) {
            continue;
         }
         int k = f.chunks++;
         f.chunkKeys[k] = rc.depth;
         f.chunkRect[k * 4] = rc.chunk.wx * 8 - f.ox;
         f.chunkRect[k * 4 + 1] = rc.chunk.wy * 8 - f.oy;
         f.chunkRect[k * 4 + 2] = rc.getMinLevel();
         f.chunkRect[k * 4 + 3] = rc.getTopLevel();
         f.chunkFlags[k] = chunkFlags(rc.chunk, rc.getMinLevel(), rc.getTopLevel());
      }
      f.wet = Config.PPL_WET_SPECULAR ? zombie.iso.IsoPuddles.getInstance().getWetGroundFinalValue() : 0.0F;
      f.ambient = Config.PPL_UNSEEN_AMBIENT ? zombie.core.opengl.RenderSettings.getInstance().getAmbientForPlayer(playerIndex) : -1.0F; // pplUnseenAmbient: what stock draws ForceAmbient sprites with
      f.shadowLight = -1;
      for (int i = 0; i < f.lights && Config.PPL_SHADOWS; i++) {
         if (f.lc[i * 4 + 3] == 1.0F) { // the first handheld torch: the player's
            f.shadowLight = i;
            break;
         }
      }
      shadowLight = f.composite ? f.shadowLight : -1;
      frames++;
      if (--logCountdown <= 0) {
         logCountdown = 3600;
         Log.info("pixel light: " + stats());
      }
      if (Config.DEV_PPL_TRACE) {
         Log.info(String.format(java.util.Locale.ROOT, "ppl trace: ms=%d cam=%.3f,%.3f o=%d,%d blocks=%d sq=%d lum=%.1f seen=%d full=%d onScreen=%d chunks=%d off=%.1f,%.1f zoom=%.3f d0=%.6f j=%.3f,%.3f comp=%b",
               System.currentTimeMillis(), IsoCamera.frameState.camCharacterX, IsoCamera.frameState.camCharacterY, f.ox, f.oy, f.blocks, traceSq,
               traceSq > 0 ? traceLum / (3.0 * traceSq) : -1.0, traceSeen, framesFull, onScreen.size(), f.chunks, f.offX, f.offY, f.zoom, f.d0, f.jx, f.jy, f.composite));
         traceSq = traceSeen = 0;
         traceLum = 0L;
      }
      f.ease = Config.PPL_EASE_MS > 0 && LightDirt.globalEvents == easeGlobalEvents; // a flash or a dusk step snaps
      easeGlobalEvents = LightDirt.globalEvents;
      if (FrameLog.ON) {
         int kind1 = 0;
         for (int i = 0; i < f.lights; i++) {
            kind1 += f.lc[i * 4 + 3] == 1.0F ? 1 : 0;
         }
         int busy = 0;
         for (int i = 0; i < RING.size(); i++) {
            busy += RING.get(i).free ? 0 : 1;
         }
         f.seq = ++FrameLog.seq;
         f.rendered = 0;
         FrameLog.game(f.seq, f.lights, kind1, mergedTorches, f.blocks, f.chunks, busy, f.ox, f.oy);
      }
      mergedTorches = 0;
      if (f.composite) {
         SpriteRenderer.instance.drawGeneric(f);
      } else {
         pendingPass = f;
      }
   }

   // dev (devPplTiming): GPU time of the chunk composite (and the pass, pass mode) per mode, from timestamp queries
   // bracketing it, logged every 600 frames of one mode with the mode's name: an A/B inside one run (devPplToggleAt)
   // that the machine's clock drift does not blur
   private static final TextureDraw.GenericDrawer COMPOSITE_START = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         CompositeTimer.stamp(0);
      }
   };
   private static final TextureDraw.GenericDrawer COMPOSITE_END = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         CompositeTimer.stamp(1);
         CompositeTimer.collect(ACTIVE);
      }
   };

   static final class CompositeTimer {
      private static int[] q;
      private static int slot;
      private static final boolean[] pending = new boolean[8], mode = new boolean[8];
      private static final int[] slotMask = new int[8];
      private static long sumOn, sumOff, nOn, nOff;
      private static final long[] altSum = new long[2], altN = new long[2];
      private static int altLast = -1, altSince;

      static void stamp(int i) {
         if (q == null) {
            q = new int[16];
            GL15.glGenQueries(q);
         }
         GL33.glQueryCounter(q[slot * 2 + i], GL33.GL_TIMESTAMP);
      }

      static void collect(boolean on) {
         pending[slot] = true;
         mode[slot] = on;
         slotMask[slot] = costMask;
         slot = (slot + 1) % 8;
         if (pending[slot] && GL15.glGetQueryObjecti(q[slot * 2 + 1], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
            long ns = GL33.glGetQueryObjecti64(q[slot * 2 + 1], GL15.GL_QUERY_RESULT) - GL33.glGetQueryObjecti64(q[slot * 2], GL15.GL_QUERY_RESULT);
            if (!Config.DEV_PPL_ALTERNATE.isEmpty()) {
               alternate(slotMask[slot], ns);
            } else if (mode[slot]) {
               sumOn += ns;
               nOn++;
            } else {
               sumOff += ns;
               nOff++;
            }
            if (nOn + nOff >= 600) {
               Log.info(String.format("pixel light composite gpu: %s %.1f us (%d frames) epoch_ms=%d", (nOn >= nOff ? "on" : "off") + (costMask != 0 ? "/m" + costMask : ""),
                  (nOn >= nOff ? sumOn / (double)nOn : sumOff / (double)nOff) / 1e3, Math.max(nOn, nOff), System.currentTimeMillis()));
               sumOn = sumOff = nOn = nOff = 0;
            }
         }
         pending[slot] = false;
      }

      /** devPplAlternate: the two masks summed apart, the first 8 frames after every flip left out, a line every 3000 frames. */
      private static void alternate(int mask, long ns) {
         String[] a = Config.DEV_PPL_ALTERNATE.split(",");
         int k = mask == Integer.parseInt(a[2].trim()) ? 0 : mask == Integer.parseInt(a[3].trim()) ? 1 : -1;
         if (k != altLast) {
            altLast = k;
            altSince = 0;
         }
         if (k < 0 || ++altSince <= 8) {
            return;
         }
         altSum[k] += ns;
         altN[k]++;
         if (altN[0] + altN[1] >= 3000) {
            Log.info(String.format("pixel light composite gpu alt: m%s %.1f us (%d) m%s %.1f us (%d) epoch_ms=%d", a[2].trim(), altSum[0] / 1e3 / Math.max(1L, altN[0]), altN[0], a[3].trim(),
               altSum[1] / 1e3 / Math.max(1L, altN[1]), altN[1], System.currentTimeMillis()));
            altSum[0] = altSum[1] = altN[0] = altN[1] = 0L;
         }
      }
   }

   /** dev (devPplTiming): GPU time of the shadow mask pass, logged every 600 frames. */
   static final class ShadowTimer {
      private static int[] q;
      private static int slot;
      private static final boolean[] pending = new boolean[8];
      private static long sum, count;

      static void stamp(int i) {
         if (q == null) {
            q = new int[16];
            GL15.glGenQueries(q);
         }
         GL33.glQueryCounter(q[slot * 2 + i], GL33.GL_TIMESTAMP);
      }

      static void collect() {
         pending[slot] = true;
         slot = (slot + 1) % 8;
         if (pending[slot] && GL15.glGetQueryObjecti(q[slot * 2 + 1], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
            sum += GL33.glGetQueryObjecti64(q[slot * 2 + 1], GL15.GL_QUERY_RESULT) - GL33.glGetQueryObjecti64(q[slot * 2], GL15.GL_QUERY_RESULT);
            if (++count == 600) {
               Log.info(String.format("pixel light shadow mask gpu: %.1f us (600 frames) epoch_ms=%d", sum / 6e5, System.currentTimeMillis()));
               sum = count = 0;
            }
         }
         pending[slot] = false;
      }
   }

   /** Game thread, right after FBORenderChunkManager.endFrame(): the pass (pass mode) and the dev dumps of the static world. */
   public static void afterComposite(int playerIndex) {
      if (!Config.DEV_PPL_DUMP_AT.isEmpty()) {
         scheduledDumps(playerIndex);
      }
      if (pendingPass != null) {
         SpriteRenderer.instance.drawGeneric(pendingPass);
         pendingPass = null;
      }
      if (Config.DEV_PPL_TIMING) {
         SpriteRenderer.instance.drawGeneric(COMPOSITE_END);
      }
      if (ACTIVE && shadowLight >= 0) {
         SpriteRenderer.instance.drawGeneric(SHADOW_PASS); // the next frame's composite reads it (reprojected)
      }
      if (pendingLitDump != null) {
         Dump d = new Dump();
         d.tag = pendingLitDump + "-lit";
         pendingLitDump = null;
         SpriteRenderer.instance.drawGeneric(d);
      }
   }

   /** Render thread, after the composite: the shadow mask of this frame's torch from the scene depth. */
   private static final TextureDraw.GenericDrawer SHADOW_PASS = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         try {
            GL.shadowPass();
         } catch (Throwable t) {
            Log.warn("pixel light: shadow pass failed, shadows off: " + t);
            GL.shadowFailed = true;
         }
      }
   };

   /** Render thread: the composite shader back to the stock light (the mode was switched off). */
   private static final TextureDraw.GenericDrawer OFF = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         Gl.wantOn = false;
         Gl.serial++;
      }
   };

   static volatile zombie.viewCone.ChunkRenderShader baseShader; // the light-free variant (render thread)
   static volatile zombie.viewCone.ChunkRenderShader stockShader; // dev (cost bit 256): the unpatched stock program
   private static String stockFrag;
   public static long baseDraws, fullDraws, drawLights, culled, mergedPoints;

   /**
    * Render thread, ChunkRenderShader.startRenderThread (that shader's program is bound, its DEPTH and chunkDepth set): the
    * program for this chunk texture is the variant compiled for the kinds of light its list holds (none: the light-free
    * base, 32 registers; the full program 48, 10 waves instead of 16 on the flip's RDNA 3.5), switched through the game's
    * program cache; the first draw of a frame on each program sets its light uniforms, every draw its light list.
    */
   /**
    * Render thread, TextureDraw's StartShader before the bind (pplRemap): the program chunkDraw would switch to for this
    * chunk texture (the variant for its lights, foliage sway's twin of it), bound in place of the full one, so a chunk
    * draw binds one program instead of two (2 % of the render thread on the 120 km/h drive, 2026-10-09).
    */
   public static int remap(int program, TextureDraw texd) {
      if (!Config.PPL_REMAP || !ACTIVE || failed || !Gl.wantOn || texd == null || texd.tex1 == null) {
         return program;
      }
      zombie.viewCone.ChunkRenderShader stock = zombie.core.SceneShaderStore.chunkRenderShader;
      if (stock == null || program != stock.getID() || shaderIsDevStock(stock) || (costMask & (256 | 512 | 8192)) != 0) {
         return program;
      }
      try {
         zombie.core.opengl.Shader want = GL.variantFor(GL.lightBits(texd.tex1));
         if (Config.SWAY_TWIN_REMAP) {
            want = Sway.twinOrSelf(want, texd);
         }
         return want != null ? want.getID() : program;
      } catch (Throwable t) {
         return program;
      }
   }

   private static boolean shaderIsDevStock(zombie.viewCone.ChunkRenderShader s) {
      return s == stockShader;
   }

   public static long visRebakes, visSkipped;

   /**
    * LightingJNI, a square's visibility bits changed under pixelLight (the texture is unlit; the bits decide object
    * alphas in the bake): whether its chunk level must re-bake. The bake reads them only for a change of "seen" (powered
    * objects, first sight), on a square with a cut wall (cutaway alpha by canSee; a window or door's cutaway by its own
    * and its north / west neighbour's couldSee, so also the squares south and east of it). Elsewhere a flip of canSee /
    * couldSee changes nothing baked: those re-bakes were half of all bakes while driving (615 vs 303 a second, 2026-10-09).
    */
   public static boolean visRebake(zombie.iso.IsoGridSquare sq, int was, int now) {
      if (!Config.PPL_VIS_REBAKE_FILTER || sq == null || ((was ^ now) & ~Config.PPL_VIS_REBAKE_SKIP_BITS) != 0 || cutWall(sq)) {
         visRebakes++;
         return true;
      }
      zombie.iso.IsoCell cell = sq.getCell();
      if (cell != null && (cutWall(cell.getGridSquare(sq.x, sq.y + 1, sq.z)) || cutWall(cell.getGridSquare(sq.x + 1, sq.y, sq.z)))) {
         visRebakes++;
         return true;
      }
      visSkipped++;
      return false;
   }

   private static boolean cutWall(zombie.iso.IsoGridSquare sq) {
      return sq != null && (sq.has(zombie.iso.SpriteDetails.IsoFlagType.cutN) || sq.has(zombie.iso.SpriteDetails.IsoFlagType.cutW));
   }

   public static void chunkDraw(zombie.core.opengl.Shader shader, TextureDraw texd) {
      try {
         zombie.viewCone.ChunkRenderShader stock = stockShader;
         if (shader == stock) {
            return;
         }
         if ((costMask & 256) != 0 && stock != null) {
            zombie.core.ShaderHelper.glUseProgramObjectARB(stock.getID()); // dev: the stock program on the same (unlit) bakes, cost only
            stock.startRenderThread(texd);
            return;
         }
         int bits = ACTIVE && Gl.wantOn && !failed ? GL.lightBits(texd.tex1) : -1;
         zombie.core.opengl.Shader want = bits == -1 ? null : GL.variantFor(bits);
         if (Config.SWAY_TWIN_REMAP) {
            want = Sway.twinOrSelf(want, texd); // foliage sway: that variant's twin for a texture with swaying plants, bound once
         }
         if (want != null && want != shader && want != Sway.baseOf(shader)) { // (foliage sway's twin of that variant counts as it)
            zombie.core.ShaderHelper.glUseProgramObjectARB(want.getID()); // through the game's cache: its per-draw ModelViewProjection goes to the bound program
            ((zombie.viewCone.ChunkRenderShader)want).startRenderThread(texd); // DEPTH and chunkDepth on that program, then back here
            return;
         }
         if (shader == baseShader) {
            baseDraws++;
         } else {
            fullDraws++;
            drawLights += bits == -1 ? GL.lights : Integer.bitCount(bits);
         }
         int program = shader.getProgram().getShaderID();
         Integer applied = GL.appliedSerials.get(program);
         if (applied == null || applied != Gl.serial) {
            GL.appliedSerials.put(program, Gl.serial);
            GL.setChunkUniforms(program, shader.getProgram());
            if (Config.DEV_PPL_TRACE) {
               Integer k = GL.chunkIndex.get(texd.tex1);
               Log.info(String.format(java.util.Locale.ROOT, "ppl rtrace: ms=%d serial=%d o=%d,%d d0=%.6f chunk=%s chunkDepth=%.6f", System.currentTimeMillis(), Gl.serial,
                     GL.ox, GL.oy, GL.d0, k == null ? "?" : GL.chunkRect[k * 4] + "," + GL.chunkRect[k * 4 + 1] + "," + GL.chunkRect[k * 4 + 2], texd.chunkDepth));
            }
         }
         if (shader != baseShader && bits != -1) {
            GL.selectLights(program, bits);
         }
         GL.selectLevels(program, texd.tex1);
      } catch (Throwable t) {
         fail("chunk uniforms: " + t);
      }
   }

   static final int V_NO_POINT = 1, V_NO_TORCH = 2, V_NO_WET = 4, V_NO_MASK = 8, V_BASE = 16, V_COPY = 32, V_NO_RELIEF = 64;
   private static final java.util.HashMap<Integer, Integer> programKeys = new java.util.HashMap<>(); // variant programs (dev tint)
   private static String variantDefines = "#define PPL_BASE\n"; // read by patchShader while a variant compiles

   /** Render thread: a variant program of the chunk composite (pzopt_chunkBase's placeholder, the source from here), or null. */
   static zombie.viewCone.ChunkRenderShader compileVariant(int key) {
      StringBuilder d = new StringBuilder();
      if ((key & V_BASE) != 0) {
         d.append("#define PPL_BASE\n");
         if ((key & V_COPY) != 0) d.append("#define PPL_COPY\n"); // dev: a second, identical program
      } else {
         if ((key & V_NO_POINT) != 0) d.append("#define PPL_NO_POINT\n");
         if ((key & V_NO_TORCH) != 0) d.append("#define PPL_NO_TORCH\n");
         if ((key & V_NO_WET) != 0) d.append("#define PPL_NO_WET\n");
         if ((key & V_NO_MASK) != 0) d.append("#define PPL_NO_MASK\n");
      }
      if ((key & V_NO_RELIEF) != 0) d.append("#define PPL_NO_RELIEF\n"); // dev (cost bit 32768): the same program without relief
      variantDefines = d.toString();
      try {
         zombie.viewCone.ChunkRenderShader v = new zombie.viewCone.ChunkRenderShader("pzopt_chunkBase");
         if (v.getProgram() != null && v.isCompiled()) {
            programKeys.put(v.getProgram().getShaderID(), key);
            Log.info("pixel light: chunk program variant " + key + " = " + v.getID() + " (" + variantDefines.replace("#define ", "").replace('\n', ' ').trim() + ")");
            return v;
         }
         Log.warn("pixel light: chunk program variant " + key + " did not compile; the full program instead");
      } catch (Throwable t) {
         Log.warn("pixel light: chunk program variant " + key + " failed (" + t + "); the full program instead");
      }
      return null;
   }

   /**
    * What no light can change in a chunk texture: 1 its squares' light is saturated (daylight: nothing adds to it; over the
    * eight chunks around it too, the light between square centres reaches half a square across the border), 2 none of its
    * squares shows the torch (fog of war, out of reach; its own squares: a half-square soft edge at a chunk border that is
    * also the visibility boundary becomes hard). Over its levels; not packed yet: nothing.
    */
   private static int chunkFlags(IsoChunk c, int z0, int z1) {
      IsoCell cell = IsoWorld.instance.currentCell;
      int own = 3;
      for (int z = z0; z <= z1 && own != 0; z++) {
         int li = z + 32;
         own &= li >= 0 && li < 64 && z >= c.minLevel && z <= c.maxLevel ? c.pzoptPplFlags[li] : 3;
      }
      int and = own & 1; // saturation: the neighbours too
      for (int dy = -1; dy <= 1 && and != 0; dy++) {
         for (int dx = -1; dx <= 1 && and != 0; dx++) {
            IsoChunk n = dx == 0 && dy == 0 ? c : cell.getChunk(c.wx + dx, c.wy + dy);
            if (n == null) {
               continue; // nothing drawn there
            }
            for (int z = z0; z <= z1 && and != 0; z++) {
               int li = z + 32;
               and &= li >= 0 && li < 64 && z >= n.minLevel && z <= n.maxLevel ? n.pzoptPplFlags[li] : 3;
            }
         }
      }
      return and | own & 2;
   }

   static final int MAX_LIGHTS = 16;
   private static int mergedTorches; // devPplFrameLog: torches merged into another at the same spot this frame
   private static long easeGlobalEvents; // pplEaseMs: LightDirt's global light events as of the last frame

   /**
    * devPplFrameLog (2026-10-03, the one-frame light pops under a carried torch + lantern): one line per frame as the game
    * thread hands it over (seq, lights, carried lights, merges, lattice blocks packed, chunks, frames in flight, origin) and
    * one as the render thread draws it (seq, how many times: > 1 a replayed state), epoch ms each, to
    * Zomboid/pzopt-pplframes.out, for lining a capture's pops up with what changed.
    */
   static final class FrameLog {
      static final boolean ON = Config.DEV_PPL_FRAME_LOG;
      static long seq;
      static final java.util.concurrent.atomic.AtomicInteger capped = new java.util.concurrent.atomic.AtomicInteger(), cappedNoTorch = new java.util.concurrent.atomic.AtomicInteger();
      private static final StringBuilder SB = new StringBuilder(1 << 16);
      private static java.io.Writer out;

      static synchronized void game(long s, int lights, int carried, int merged, int blocks, int chunks, int busy, int ox, int oy) {
         SB.append("g ").append(System.currentTimeMillis()).append(' ').append(s).append(' ').append(lights).append(' ').append(carried).append(' ')
            .append(merged).append(' ').append(blocks).append(' ').append(chunks).append(' ').append(busy).append(' ').append(ox).append(' ').append(oy)
            .append(' ').append(capped.getAndSet(0)).append(' ').append(cappedNoTorch.getAndSet(0)).append('\n');
         flush(false);
      }

      static synchronized void render(long s, int n) {
         SB.append("r ").append(System.currentTimeMillis()).append(' ').append(s).append(' ').append(n).append('\n');
         flush(false);
      }

      private static void flush(boolean force) {
         if (!force && SB.length() < 60000) {
            return;
         }
         try {
            if (out == null) {
               out = new java.io.BufferedWriter(new java.io.FileWriter(new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt-pplframes.out")));
               out.write("# g epoch_ms seq lights carried merged blocks chunks frames_in_flight ox oy capped capped_no_torch | r epoch_ms seq times_drawn\n");
            }
            out.write(SB.toString());
            out.flush();
         } catch (java.io.IOException e) {
            Log.warn("ppl frame log: " + e);
         }
         SB.setLength(0);
      }
   }
   static final float TORCH_H = 0.55F, CAR_H = 0.6F; // a lamp's assumed height above its holder / its square, levels (torchSource: a carried light's lens instead)
   private static final float[] candD = new float[4096];
   private static final IsoLightSource[] candL = new IsoLightSource[4096];

   /**
    * The dynamic light sources near the view for the shader's per-pixel shaping, relative to the origin square: torches and
    * headlights (as sent to the native: position, direction, cone, reach) first, then the nearest active point lights
    * (lamps, fires). a = (x, y, z, reach), b = (dir x, dir y, cone cos or -2 for a point light, strength).
    */
   private static int gatherLights(Frame f, int playerIndex) {
      float cx = IsoCamera.frameState.camCharacterX, cy = IsoCamera.frameState.camCharacterY;
      float view = (f.screenW + 2.0F * f.screenH) * f.zoom / (64.0F * f.ts) + 4.0F; // squares from the centre to a screen corner, generously
      int count = 0;
      ArrayList<IsoGameCharacter.TorchInfo> torches = LightingJNI.pzoptTorches();
      TorchSource.refresh(torches); // torchSource: this frame's lens
      for (int i = 0; i < torches.size() && count < MAX_LIGHTS; i++) {
         IsoGameCharacter.TorchInfo t = torches.get(i);
         float tx = TorchSource.x(t), ty = TorchSource.y(t); // torchSource: the drawn item's lens (else the native's position)
         if (t.id == 0 || Math.abs(tx - cx) > view + t.dist || Math.abs(ty - cy) > view + t.dist) {
            continue;
         }
         float len = (float)Math.sqrt(t.angleX * t.angleX + t.angleY * t.angleY);
         if (len < 1e-4F) {
            continue;
         }
         boolean merged = false; // the game sends every active light item of a player at the same spot (torch + another): the native takes the brightest
         for (int j = 0; j < count && !merged; j++) {
            int q = j * 4;
            if (Math.abs(f.la[q] - (tx - f.ox)) < 0.01F && Math.abs(f.la[q + 1] - (ty - f.oy)) < 0.01F && Math.abs(f.lb[q] - t.angleX / len) < 0.01F
               && Math.abs(f.lb[q + 1] - t.angleY / len) < 0.01F && f.lc[q + 3] == (t.id >= 4096 || t.focusing > 0 ? 2.0F : 1.0F)) {
               merged = true;
               if (t.strength * Math.max(1.0F, t.dist) > f.lb[q + 3] * f.la[q + 3]) { // the stronger one (reach x strength)
                  f.la[q + 3] = Math.max(1.0F, t.dist);
                  f.lb[q + 2] = t.cone ? t.dot : -2.0F;
                  f.lb[q + 3] = t.strength;
               }
            }
         }
         if (merged) {
            mergedTorches++;
            continue;
         }
         int k = count * 4;
         f.la[k] = tx - f.ox;
         f.la[k + 1] = ty - f.oy;
         f.la[k + 2] = t.z;
         f.la[k + 3] = TorchSource.reach(t, Math.max(1.0F, t.dist)); // torchSourcePitch: a beam tilted down ends sooner
         f.lb[k] = t.angleX / len;
         f.lb[k + 1] = t.angleY / len;
         f.lb[k + 2] = t.cone ? t.dot : -2.0F;
         f.lb[k + 3] = t.strength;
         f.lc[k] = t.r;
         f.lc[k + 1] = t.g;
         f.lc[k + 2] = t.b;
         f.lc[k + 3] = t.id >= 4096 || t.focusing > 0 ? 2.0F : 1.0F; // 1: a handheld torch (replaces the native's); 2: a vehicle light (sharpens the native's)
         f.lh[count] = f.lc[k + 3] == 1.0F ? TorchSource.height(t, TORCH_H) : CAR_H; // the lamp's height above la.z (torchSource: the lens's)
         boolean body = Config.TORCH_SOURCE_SELF_SHADOW && TorchSource.body() && t.pzoptSrc && t.pzoptHolder != null && f.lc[k + 3] == 1.0F
            && TorchSource.bodyInCone(t, tx, ty, Config.TORCH_SOURCE_BODY_PCT / 100.0F); // a beam pointing away from its carrier: no test at all
         f.lbody[k] = body ? t.pzoptHolder.getX() - f.ox : 0.0F; // the carrier, at the position the frame draws it
         f.lbody[k + 1] = body ? t.pzoptHolder.getY() - f.oy : 0.0F;
         f.lbody[k + 2] = body ? Config.TORCH_SOURCE_BODY_PCT / 100.0F : 0.0F;
         f.lbody[k + 3] = body ? 1.0F : 0.0F;
         count++;
      }
      IsoCell cell = IsoWorld.instance.currentCell;
      java.util.Stack<IsoLightSource> list = cell.getLamppostPositions();
      int nc = 0;
      for (int i = 0; Config.PPL_POINT_LIGHTS && i < list.size() && nc < candL.length; i++) {
         IsoLightSource l = list.get(i);
         if (!l.active || l.radius <= 0) {
            continue;
         }
         float dx = l.x + 0.5F - cx, dy = l.y + 0.5F - cy;
         float r = Math.min(l.radius, 20);
         if (Math.abs(dx) > view + r || Math.abs(dy) > view + r) {
            continue;
         }
         candD[nc] = dx * dx + dy * dy;
         candL[nc++] = l;
      }
      while (count < MAX_LIGHTS && nc > 0) { // nearest first (a handful: selection, not a sort)
         int best = 0;
         for (int i = 1; i < nc; i++) {
            if (candD[i] < candD[best]) {
               best = i;
            }
         }
         IsoLightSource l = candL[best];
         candD[best] = candD[--nc];
         candL[best] = candL[nc];
         float lr = Math.min(1.0F, Math.max(0.0F, l.r * 2.0F)), lg = Math.min(1.0F, Math.max(0.0F, l.g * 2.0F)), lb = Math.min(1.0F, Math.max(0.0F, l.b * 2.0F));
         if (mergePoint(f, count, l.x + 0.5F - f.ox, l.y + 0.5F - f.oy, l.z, Math.min(l.radius, 20), lr, lg, lb)) {
            continue; // a fire's burning squares, a lamp pair: one light (the shader's loop runs once per light per pixel)
         }
         int k = count * 4;
         f.la[k] = l.x + 0.5F - f.ox;
         f.la[k + 1] = l.y + 0.5F - f.oy;
         f.la[k + 2] = l.z;
         f.la[k + 3] = Math.min(l.radius, 20);
         f.lb[k] = 0.0F;
         f.lb[k + 1] = 0.0F;
         f.lb[k + 2] = -2.0F;
         f.lb[k + 3] = 1.0F;
         f.lc[k] = Math.min(1.0F, Math.max(0.0F, l.r * 2.0F)); // as LightingJNI hands it to the native
         f.lc[k + 1] = Math.min(1.0F, Math.max(0.0F, l.g * 2.0F));
         f.lc[k + 2] = Math.min(1.0F, Math.max(0.0F, l.b * 2.0F));
         f.lc[k + 3] = 0.0F; // a point light: the brightest of it and the rest, per channel
         f.lh[count] = CAR_H;
         java.util.Arrays.fill(f.lbody, k, k + 4, 0.0F);
         count++;
      }
      java.util.Arrays.fill(candL, 0, candL.length, null);
      return count;
   }

   /**
    * A point light within 2 squares of one already in the table, on its level, of about its colour, joins it: the reach
    * grows to cover both (max(r, r' + distance)), which is never brighter than the pair; where it is a little dimmer (at
    * the joined light's centre) the native's field, which the shader takes the maximum with, carries it.
    */
   private static boolean mergePoint(Frame f, int count, float x, float y, float z, float r, float cr, float cg, float cb) {
      for (int j = 0; j < count; j++) {
         int q = j * 4;
         if (f.lc[q + 3] != 0.0F || f.la[q + 2] != z) {
            continue;
         }
         float dx = f.la[q] - x, dy = f.la[q + 1] - y, dist = (float)Math.sqrt(dx * dx + dy * dy);
         if (dist <= 2.0F && Math.abs(f.lc[q] - cr) < 0.08F && Math.abs(f.lc[q + 1] - cg) < 0.08F && Math.abs(f.lc[q + 2] - cb) < 0.08F) {
            f.la[q + 3] = Math.max(f.la[q + 3], r + dist);
            f.lc[q] = Math.max(f.lc[q], cr);
            f.lc[q + 1] = Math.max(f.lc[q + 1], cg);
            f.lc[q + 2] = Math.max(f.lc[q + 2], cb);
            mergedPoints++;
            return true;
         }
      }
      return false;
   }

   public static String stats() {
      return "ppl: frames=" + frames + " blocks=" + blocksUploaded + " (copied " + blocksCopied + ")" + " fullFrames=" + framesFull + " bakes=" + bakes + " whitened=" + whitenedSquares
         + " chunk draws light-free=" + baseDraws + " with lights=" + fullDraws + String.format(" (%.1f lights each)", fullDraws > 0 ? (double)drawLights / fullDraws : 0.0) + " lights culled (saturated / hidden / cone)=" + culled + " point lights merged=" + mergedPoints
         + " squares simple=" + packSimple + " hidden=" + packHidden + " slow=" + packSlow
         + String.format(" pack=%.1fus/frame", frames > 0 ? packNs / 1e3 / frames : 0.0)
         + " eased=" + Gl.eased + " easeUploads=" + Gl.easeUploads
         + (failed ? " FAILED" : "") + (Gl.passNs > 0 ? String.format(" gpu pass=%.1fus upload=%.1fus", Gl.passNs / 1e3, Gl.uploadNs / 1e3) : "");
   }

   private static Frame freeFrame() {
      for (int i = 0; i < RING.size(); i++) {
         Frame f = RING.get(i);
         if (f.free) {
            f.free = false;
            return f;
         }
      }
      Frame f = new Frame();
      RING.add(f);
      f.free = false;
      if (RING.size() > 6) {
         Log.warn("pixel light: " + RING.size() + " frames in flight");
      }
      return f;
   }

   /** pplAirFill: the levels from..to of chunk {@code c} that borrow corners from below (or lie above its top) repack. */
   private static void markAirDirty(IsoChunk c, int slot, int from, int to) {
      for (int za = from; za <= to; za++) {
         int la = za + 32;
         if (la >= 0 && la < 64 && (za > c.maxLevel || slotAir[slot * LEVELS + (za & (LEVELS - 1))])) {
            c.pzoptPplDirty[la] = 1;
         }
      }
   }

   /**
    * One chunk of the pack list: its levels that changed since they were last packed (all of them when the chunk just took
    * its lattice slot), each into a block of the frame. {@code atomic}: a pack task on a frame worker (pplPackParallel),
    * blocks taken from the shared counter; the chunk's own slot state is touched by this task alone.
    */
   private static void packChunkLevels(PackCtx x, Frame f, IsoChunk c, boolean ring, int s, int playerIndex, boolean atomic) {
      x.lastAbove = -1;
      int slot = Math.floorMod(c.wx, s) + Math.floorMod(c.wy, s) * s;
      if (slotChunk[slot] != c) {
         slotChunk[slot] = c;
         java.util.Arrays.fill(slotLevel, slot * LEVELS, slot * LEVELS + LEVELS, Integer.MIN_VALUE);
      }
      // one level above the top: tall sprites (tree crowns) reach into it; with pplAirFill up to the tallest neighbour's top
      // + 1 as well: a tree's copy in a neighbour's texture (TreeBake) reads this chunk's squares at that texture's levels,
      // and an unpacked level holds the light of whichever chunk used the slot before
      int zTop = Config.PPL_AIR_FILL ? Math.max(c.maxLevel, neighbourTop(c, s)) + 1 : c.maxLevel + 1;
      // the lattice holds LEVELS of a chunk's levels (level & (LEVELS - 1)). A taller chunk packs the LEVELS from the lowest
      // one drawn this frame up (stock draws from the player's level, the chunk texture holding it from its even level):
      // packing from its bottom left the drawn levels reading a basement's light (issue #44, the Rosewood secret base goes
      // down to -17: its ground drew black in the shapes of the rooms 16 levels below)
      int zLo = c.minLevel;
      if (zTop - zLo >= LEVELS) {
         int drawLo = zombie.iso.fboRenderChunk.FBORenderLevels.calculateMinLevel((int)Math.floor(IsoCamera.frameState.camCharacterZ));
         zLo = Math.max(c.minLevel, Math.min(drawLo, zTop - LEVELS + 1));
         zTop = zLo + LEVELS - 1;
      }
      if (Config.PPL_AIR_FILL && slotAirPending[slot] && frames - slotAirFrame[slot] >= AIR_REFRESH_FRAMES) {
         slotAirFrame[slot] = frames;
         slotAirPending[slot] = false;
         markAirDirty(c, slot, zLo + 1, zTop);
      }
      for (int z = zLo; z <= zTop; z++) {
         int li = z + 32;
         int idx = slot * LEVELS + (z & (LEVELS - 1));
         boolean dirty = li >= 0 && li < 64 && c.pzoptPplDirty[li] != 0;
         if (slotLevel[idx] == z && !dirty && !(slotLite[idx] && !ring)) {
            continue;
         }
         int blk = allocBlock(f, atomic);
         if (blk < 0) {
            x.full++;
            break;
         }
         if (li >= 0 && li < 64) {
            c.pzoptPplDirty[li] = 0;
         }
         slotLevel[idx] = z;
         if (z > c.maxLevel + 1 && x.lastAbove >= 0) {
            copyBlock(x, f, x.lastAbove, z, blk); // every level above the top + 1 is the same: the columns' top corners
            slotAir[idx] = true;
            continue;
         }
         x.lite = ring;
         boolean air = pack(x, f, c, z, playerIndex, blk);
         x.lite = false;
         slotAir[idx] = air;
         slotLite[idx] = ring;
         if (z == c.maxLevel + 1) {
            x.lastAbove = blk;
         }
         if (Config.PPL_AIR_FILL && z <= c.maxLevel && !air) { // (a borrowed level's own repack does not propagate)
            // the levels above lend this level's top corners to their empty squares: they follow its light, at most every
            // AIR_REFRESH_FRAMES (they only light tree crowns and air; repacking them with every ground light change while
            // driving doubled the lattice uploads: +130 us a frame on the game thread)
            if (frames - slotAirFrame[slot] >= AIR_REFRESH_FRAMES) {
               slotAirFrame[slot] = frames;
               slotAirPending[slot] = false;
               markAirDirty(c, slot, z + 1, zTop);
            } else {
               slotAirPending[slot] = true;
            }
         }
      }
   }

   private static final ArrayList<IsoChunk> ringList = new ArrayList<>();
   private static long[] slotStamp; // per lattice slot: the frame a chunk in it joined this frame's pack list
   private static int[] slotTop; // per lattice slot: that chunk's maxLevel (neighbourTop reads it: no cell lookups)

   /**
    * The on-screen chunks and the loaded chunks around them: a texture's pixels reach the squares of the chunks around its
    * own (a tree's copy drawn into a neighbour's texture by TreeBake, the bilinear between square centres across the
    * border), and an unpacked slot holds the light of whichever chunk used it before (a tree at the screen's edge drawn
    * black, or lit by another place). The slot stamps replace a hash set and most cell lookups (~5,000 a frame at max zoom:
    * ~75 us of the game thread while driving).
    */
   private static ArrayList<IsoChunk> withRing(ArrayList<IsoChunk> onScreen, int s) {
      ringList.clear();
      long stamp = frames + 1;
      for (int i = 0, n0 = onScreen.size(); i < n0; i++) {
         IsoChunk c = onScreen.get(i);
         int slot = Math.floorMod(c.wx, s) + Math.floorMod(c.wy, s) * s;
         slotStamp[slot] = stamp;
         slotTop[slot] = c.maxLevel;
         ringList.add(c);
      }
      if (!Config.PPL_PACK_RING) {
         return ringList;
      }
      IsoCell cell = IsoWorld.instance.currentCell;
      for (int i = 0, n0 = onScreen.size(); i < n0; i++) {
         IsoChunk c = onScreen.get(i);
         for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
               int slot = Math.floorMod(c.wx + dx, s) + Math.floorMod(c.wy + dy, s) * s;
               if (slotStamp[slot] == stamp) {
                  continue; // on screen, or already added
               }
               slotStamp[slot] = stamp;
               IsoChunk o = cell.getChunk(c.wx + dx, c.wy + dy);
               slotTop[slot] = o != null ? o.maxLevel : Integer.MIN_VALUE;
               if (o != null) {
                  ringList.add(o);
               }
            }
         }
      }
      return ringList;
   }

   /** The highest level with squares among the eight chunks around {@code c} (their tree copies draw into its squares' light). */
   private static int neighbourTop(IsoChunk c, int s) {
      long stamp = frames + 1;
      int top = c.maxLevel;
      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            int slot = Math.floorMod(c.wx + dx, s) + Math.floorMod(c.wy + dy, s) * s;
            if (slotStamp[slot] == stamp && slotTop[slot] > top) {
               top = slotTop[slot];
            }
         }
      }
      return top;
   }

   /** A copy of block {@code from} of this frame as level {@code z} of the chunk the last pack() wrote, into block {@code blk}. */
   private static void copyBlock(PackCtx x, Frame f, int from, int z, int blk) {
      ByteBuffer b = f.buf();
      b.put(blk * BLOCK_BYTES, b, from * BLOCK_BYTES, BLOCK_BYTES);
      f.bx[blk] = f.bx[from];
      f.by[blk] = f.by[from];
      f.bl[blk] = z & (LEVELS - 1);
      f.bcx[blk] = f.bcx[from];
      f.bcy[blk] = f.bcy[from];
      f.bz[blk] = z;
      x.copied++;
   }

   /** The next block of the frame, or -1 when it is full. Serial: Frame.room (grows the buffer); parallel: the shared counter (packParallel grew it). */
   private static int allocBlock(Frame f, boolean atomic) {
      if (atomic) {
         int blk = PACK_NEXT.getAndIncrement();
         return blk < MAX_BLOCKS ? blk : -1;
      }
      if (!f.room()) {
         return -1;
      }
      return f.blocks++;
   }

   /**
    * One chunk level: two 16x16 RGBA8 corner blocks (bottom, top; corner c of square (x, y) at texel (2x + (c == 1 || c == 2),
    * 2y + (c >= 2))) and an 8x8 info block (rgb: the square's own light, the native's sample at its centre; a: which of the
    * eight neighbours it is connected to, i.e. shares its corner colours with: the native breaks them at walls between a lit
    * room and the dark outside).
    *
    * <p>Returns true when some of its squares took their column's light from below (pplAirFill).
    */
   private static boolean pack(PackCtx px, Frame f, IsoChunk c, int z, int playerIndex, int blk) {
      ByteBuffer b = f.buf();
      int base = blk * BLOCK_BYTES;
      IsoCell cell = IsoWorld.instance.currentCell;
      px.chunk = c;
      px.torchLevel(c, z);
      boolean allSat = true, allHidden = true, air = false;
      for (int y = 0; y < 8; y++) {
         for (int x = 0; x < 8; x++) {
            IsoGridSquare sq = c.getGridSquare(x, y, z);
            boolean above = false;
            if (sq == null && z > c.maxLevel && !Config.PPL_AIR_FILL) {
               sq = c.getGridSquare(x, y, c.maxLevel); // above the top: the top corners of the level below, for both layers
               above = true;
            } else if (sq == null && z > c.minLevel && Config.PPL_AIR_FILL) {
               // no square here (air above the ground, beside a taller building, above the chunk's top): the column's highest
               // square below lends its top corners, for both layers. Tall sprites (tree crowns) reach up here; a 0 drew
               // them black
               for (int zz = Math.min(z - 1, c.maxLevel); zz >= c.minLevel && sq == null; zz--) {
                  sq = c.getGridSquare(x, y, zz);
               }
               above = sq != null;
               air |= above;
            }
            int v0 = 0, v1 = 0, v2 = 0, v3 = 0, t0 = 0, t1 = 0, t2 = 0, t3 = 0;
            int info = 0, conn = 0, tvis = 255, visible = 0;
            float fade = -1.0F; // pplTorchFade: the torch's visibility between 0 and 1 here (-1: all or nothing, tvis)
            if (sq != null && sq.lighting[playerIndex] instanceof LightingJNI.JNILighting jl) {
               visible = (jl.pzoptVis() & 7) != 0 ? 1 : 0; // pplSeenEdge: seen (explored), in sight, or in line of sight; 0: never seen (blacked out)
               if (above) {
                  v0 = t0 = jl.pzoptVert(4);
                  v1 = t1 = jl.pzoptVert(5);
                  v2 = t2 = jl.pzoptVert(6);
                  v3 = t3 = jl.pzoptVert(7);
                  info = meanAbgr(v0, v1, v2, v3);
                  conn = 255;
               } else {
                  v0 = jl.pzoptVert(0);
                  v1 = jl.pzoptVert(1);
                  v2 = jl.pzoptVert(2);
                  v3 = jl.pzoptVert(3);
                  t0 = jl.pzoptVert(4);
                  t1 = jl.pzoptVert(5);
                  t2 = jl.pzoptVert(6);
                  t3 = jl.pzoptVert(7);
                  zombie.core.textures.ColorInfo li = jl.pzoptInfo();
                  // the native adds the torches (the brightest of them) to the square's light: base = light - torch; a
                  // saturated square's base is the ambient (unknown under the clamp)
                  float tr = 0.0F, tg = 0.0F, tb = 0.0F;
                  float hr = 0.0F, hg = 0.0F, hb = 0.0F; // pplTorchVehicleMix: the brightest vehicle light listed here
                  for (int k = 0; Config.PPL_ANALYTIC && k < jl.resultLightCount(); k++) {
                     zombie.iso.IsoGridSquare.ResultLight rl = jl.getResultLight(k);
                     if ((rl.flags & 2) != 0 && rl.id < 4096) { // handheld torches (vehicle lights, 4096 + vehicle * 10 + light, stay in the base: they move too fast for the replacement)
                        tr = Math.max(tr, rl.r);
                        tg = Math.max(tg, rl.g);
                        tb = Math.max(tb, rl.b);
                     } else if ((rl.flags & 2) != 0 && rl.r + rl.g + rl.b > hr + hg + hb) {
                        hr = rl.r;
                        hg = rl.g;
                        hb = rl.b;
                     }
                  }
                  boolean hadTorch = tr + tg + tb > 0.0F; // pplClipBase: no handheld torch here: the light is the base
                  float tmax = Math.max(tr, Math.max(tg, tb)), imax = Math.max(li.r, Math.max(li.g, li.b));
                  // pplTorchVehicleMix: the native adds the brightest of the torch and the vehicle lights listed here (max, not
                  // the sum: measured per square, 2026-10-04); whether it added anything is judged against that one, else a
                  // square a headlight lit counted as "torch listed but not added" and the torch went off in the car's beam
                  float amax = Config.PPL_TORCH_VEHICLE_MIX ? Math.max(tmax, Math.max(hr, Math.max(hg, hb))) : tmax;
                  if (FrameLog.ON && jl.resultLightCount() >= 6) { // devPplFrameLog: the native's light list is full (6): a torch can drop off it
                     FrameLog.capped.incrementAndGet();
                     if (tmax <= 0.0F && torchNear(px, sq.x + 0.5F, sq.y + 0.5F, z)) {
                        FrameLog.cappedNoTorch.incrementAndGet();
                     }
                  }
                  boolean canSee = (jl.pzoptVis() & 2) != 0;
                  // pplTorchFade: where the player can see the square and nothing hides the torch, the native's light ramps up
                  // over a few frames as the square comes into view or into the beam (its fade); the torch is cross-faded in
                  // over that ramp (light / torch 0.5 -> 0.9) instead of switching on at 0.9: base = light - vis x torch, the
                  // per-pixel torch x vis, the same at both ends. The switch flashed whole floor tiles while the player turned
                  // with a lantern (torchSource draws it; 2026-10-03)
                  if (Config.PPL_TORCH_FADE && tmax > 0.02F && canSee && imax < 0.9F * amax && imax > 0.5F * amax) {
                     float u = (imax / amax - 0.5F) / 0.4F;
                     fade = u * u * (3.0F - 2.0F * u);
                     tr *= fade;
                     tg *= fade;
                     tb *= fade;
                     tvis = fade >= 0.5F ? 255 : 0;
                  } else if (tmax > 0.02F && (imax < 0.9F * amax || Config.PPL_TORCH_CAN_SEE && !canSee) || tmax <= 0.02F && !canSee && torchNear(px, sq.x + 0.5F, sq.y + 0.5F, z)) {
                     // the native lists the torch here but did not add it (a wall hides it, or the square is dark for the
                     // player): nothing to take out, and the torch stays off here. A square the player cannot see never took
                     // it: a room lit by its own lamp is brighter than the torch, so the brightness test alone let the torch
                     // through the wall. No entry at all: the model decides where the player can see the square (one the torch
                     // has just turned to is not lit by the native yet; the torch points where the player looks), never where
                     // the player cannot (fog of war)
                     tr = tg = tb = 0.0F;
                     tvis = 0;
                  }
                  // a clamped channel hides the light under the torch; all three take the ambient estimate then (one channel
                  // alone turned the base blue or teal where a warm torch clips red and green first: a ring at the torch's
                  // reach under a bright storm ambient)
                  boolean outside = sq.isOutside();
                  boolean clipped = tmax >= 0.99F || tmax > 0.0F && Math.max(li.r, Math.max(li.g, li.b)) >= 0.999F;
                  int est = outside ? ambOut : ambIn;
                  float er = est >= 0 ? (est & 0xFF) / 255.0F : px.ambR, eg = est >= 0 ? (est >> 8 & 0xFF) / 255.0F : px.ambG, eb = est >= 0 ? (est >> 16 & 0xFF) / 255.0F : px.ambB;
                  // pplTorchVehicleMix: the native adds only the brightest of the torches and vehicle lights to a square. Where a
                  // headlight is the brighter, the torch was never added: nothing of it to take out (taking it out darkened the
                  // beam, so the torch lit nothing in a car's headlights, Discord 2026-10-04); where the torch is, the headlight
                  // it outshone is put back. Either way base = light + vehicle light and the per-pixel torch goes on top
                  float sr = tr, sg = tg, sb = tb, ar = 0.0F, ag = 0.0F, ab = 0.0F;
                  if (Config.PPL_TORCH_VEHICLE_MIX && hr + hg + hb > 0.0F && tr + tg + tb > 0.0F) {
                     if (hr + hg + hb > tr + tg + tb) {
                        sr = sg = sb = 0.0F;
                     } else {
                        ar = hr;
                        ag = hg;
                        ab = hb;
                     }
                  }
                  float br = clipped ? Math.min(li.r, er) : Math.min(1.0F, Math.max(0.0F, li.r - sr) + ar);
                  float bg = clipped ? Math.min(li.g, eg) : Math.min(1.0F, Math.max(0.0F, li.g - sg) + ag);
                  float bb = clipped ? Math.min(li.b, eb) : Math.min(1.0F, Math.max(0.0F, li.b - sb) + ab);
                  if (clipped && Config.PPL_CLIP_BASE) {
                     // pplClipBase: under the clamp the base is at least light - torch (the sum reached 1), and was what the
                     // square had without the torch (remembered); the night ambient estimate alone dropped a street lamp's
                     // light wherever the torch saturated it: a black wedge round the player under the lamp (Discord 2026-10-04)
                     int mem = jl.pzoptBaseMem;
                     float mr = mem >= 0 ? (mem & 0xFF) / 255.0F : er, mg = mem >= 0 ? (mem >> 8 & 0xFF) / 255.0F : eg, mb = mem >= 0 ? (mem >> 16 & 0xFF) / 255.0F : eb;
                     br = Math.max(Math.min(1.0F, Math.max(0.0F, li.r - sr) + ar), Math.min(li.r, mr));
                     bg = Math.max(Math.min(1.0F, Math.max(0.0F, li.g - sg) + ag), Math.min(li.g, mg));
                     bb = Math.max(Math.min(1.0F, Math.max(0.0F, li.b - sb) + ab), Math.min(li.b, mb));
                  }
                  if (Config.PPL_CLIP_BASE && !hadTorch) {
                     jl.pzoptBaseMem = Math.min(255, (int)(li.r * 255.0F + 0.5F)) | Math.min(255, (int)(li.g * 255.0F + 0.5F)) << 8
                           | Math.min(255, (int)(li.b * 255.0F + 0.5F)) << 16;
                  }
                  info = Math.min(255, (int)(br * 255.0F + 0.5F)) | Math.min(255, (int)(bg * 255.0F + 0.5F)) << 8 | Math.min(255, (int)(bb * 255.0F + 0.5F)) << 16;
                  if ((jl.pzoptVis() & 1) != 0 && tr + tg + tb == 0.0F && li.r + li.g + li.b > 0.0F) {
                     px.sample(outside ? 0 : 1, (int)(li.r * 255.0F + 0.5F) | (int)(li.g * 255.0F + 0.5F) << 8 | (int)(li.b * 255.0F + 0.5F) << 16);
                  }
                  if ((jl.pzoptVis() & 1) != 0 && tr + tg + tb == 0.0F && li.r + li.g + li.b > 0.0F && li.r < px.ambR + 0.5F) {
                     px.ambR = Math.min(px.ambR, li.r); // a seen square without torch light: the ambient is at most its light
                     px.ambG = Math.min(px.ambG, li.g);
                     px.ambB = Math.min(px.ambB, li.b);
                  }
                  int wx = sq.x, wy = sq.y;
                  // E, S, W, N, SE, SW, NW, NE: shared corners equal (a ring chunk, off screen, lends only its light: every
                  // neighbour counts as connected, no lookups; it packs in full once it comes on screen)
                  if (px.lite) conn = 255; else {
                  if (same(v1, corner(px, cell, wx + 1, wy, z, 0, playerIndex)) && same(v2, corner(px, cell, wx + 1, wy, z, 3, playerIndex))) conn |= 1;
                  if (same(v3, corner(px, cell, wx, wy + 1, z, 0, playerIndex)) && same(v2, corner(px, cell, wx, wy + 1, z, 1, playerIndex))) conn |= 2;
                  if (same(v0, corner(px, cell, wx - 1, wy, z, 1, playerIndex)) && same(v3, corner(px, cell, wx - 1, wy, z, 2, playerIndex))) conn |= 4;
                  if (same(v0, corner(px, cell, wx, wy - 1, z, 3, playerIndex)) && same(v1, corner(px, cell, wx, wy - 1, z, 2, playerIndex))) conn |= 8;
                  if ((conn & 3) == 3 && same(v2, corner(px, cell, wx + 1, wy + 1, z, 0, playerIndex))) conn |= 16;
                  if ((conn & 6) == 6 && same(v3, corner(px, cell, wx - 1, wy + 1, z, 1, playerIndex))) conn |= 32;
                  if ((conn & 12) == 12 && same(v0, corner(px, cell, wx - 1, wy - 1, z, 2, playerIndex))) conn |= 64;
                  if ((conn & 9) == 9 && same(v1, corner(px, cell, wx + 1, wy - 1, z, 3, playerIndex))) conn |= 128;
                  }
               }
            }
            // the corners' vertical gradient (a ceiling brighter or darker than the floor): walls need the corner layers only then
            int grad = v0 != t0 || v1 != t1 || v2 != t2 || v3 != t3 ? 255 : 0;
            // a: 255 a simple square with the torch visible, 0 a simple square with the torch hidden (fog of war: at night most of
            // the screen), 128 not simple. Only all-255 or all-0 survive the bilinear exactly (the extremes of a mean), so the
            // shader's one fetch knows both the light and the torch visibility there. (A vertical gradient does not count: walls
            // fetch their own texel.)
            // (not simple: 192 torch visible, 64 hidden, so the edge path reads the visibility from the same four fetches)
            // pplWallEdge: a wall on the square's west / north edge that the corners connect across (a window by day, a door
            // frame): bit 0 / 1 of g, and the square is not simple, so the shader's edge path sees it
            int wallEdge = 0;
            if (Config.PPL_WALL_EDGE && sq != null && !above && !px.lite) {
               if ((conn & 4) != 0 && ChunkAo.edgeW(sq)) wallEdge |= 1;
               if ((conn & 8) != 0 && ChunkAo.edgeN(sq)) wallEdge |= 2;
            }
            // pplCutEdge: stock does not draw this square (an upper floor's orphan structure near the player, a collapsed
            // building: bit 4 of g), so nothing of its own shows on its west / north edge; what does is the face of the
            // furniture in the square before it. The hide / show re-bakes the chunk, which repacks it
            int cutEdge = 0;
            if (Config.PPL_CUT_EDGE && Config.PPL_SEEN_EDGE && sq != null && !above && z > 0
                  && zombie.iso.fboRenderChunk.FBORenderCutaways.getInstance().pzoptSquareHidden(playerIndex, sq)) {
               cutEdge = 16;
            }
            // pplTorchFade: a square cross-fading the torch is not simple, its visibility between 64 (hidden) and 192 (visible)
            int simple = fade >= 0.0F ? 64 + Math.round(fade * 128.0F) : conn != 255 || wallEdge != 0 ? (tvis == 255 ? 192 : 64) : tvis == 255 ? 255 : 0;
            if (sq != null && !above) { // (no square: no pixels; above the top: the level below's)
               allSat &= (info & 0xFF) >= 252 && (info >> 8 & 0xFF) >= 252 && (info >> 16 & 0xFF) >= 252;
               if (px.torchFilter ? allHidden && tvis != 0 : true) { // pplTorchNearChunk: once a square decided it, the rest are not asked (the test has no side effects)
                  allHidden &= tvis == 0 || !torchNear(px, sq.x + 0.5F, sq.y + 0.5F, z); // no torch there: none to hide
               }
            }
            if (conn != 255) px.slow++; else if (simple == 0) px.hidden++; else px.simple++;
            if (Config.DEV_PPL_TRACE && sq != null && !above) {
               traceSq++;
               traceLum += (info & 0xFF) + (info >> 8 & 0xFF) + (info >> 16 & 0xFF);
               if (sq.lighting[playerIndex] instanceof LightingJNI.JNILighting tj && (tj.pzoptVis() & 1) != 0) traceSeen++;
            }
            int cell8 = (y * 8 + x) * 4;
            if (Config.DEV_PPL_PROBE > 0 && sq != null && !above) {
               probePacked.put(probeKey(sq.x, sq.y, z), (long)(info & 0xFFFFFF | simple << 24) * 31L + (conn | (tvis & 0x80 | wallEdge) << 8 | grad << 16) * 17L
                     + (grad == 0 ? 0x808080 : wallDelta(v0, v1, v2, v3, t0, t1, t2, t3)));
            }
            b.putInt(base + cell8, info & 0xFFFFFF | simple << 24); // base light; a: a simple square (the shader's one-fetch path)
            // a: outdoors (>= 128: wet in rain) and, in bit 6, visible to the player (pplSeenEdge): 0 / 64 indoors, 128 / 255 outdoors
            int outdoor = (sq != null && sq.isOutside() ? 128 : 0) | (visible != 0 ? (sq.isOutside() ? 127 : 64) : 0);
            // pplNormalSpanWide: bits 2 / 3 of g, the square has a wall on its west / north edge (the wide-span normal's test)
            int wallAt = Config.PPL_NORMAL_SPAN_WIDE > 0 && sq != null && !above ? (ChunkAo.edgeW(sq) ? 4 : 0) | (ChunkAo.edgeN(sq) ? 8 : 0) : 0;
            b.putInt(base + 256 + cell8, conn | (tvis & 0x80 | wallEdge | wallAt | cutEdge) << 8 | grad << 16 | outdoor << 24); // connectivity bits, torch visibility (bit 7) + wall edges W / N (bits 0, 1; walls 2, 3; not drawn 4), vertical gradient, outdoors + visible
            b.putInt(base + 512 + cell8, grad == 0 ? 0x808080 : wallDelta(v0, v1, v2, v3, t0, t1, t2, t3)); // top corners' mean - bottom corners' mean, 0.5 = none
         }
      }
      if (z + 32 >= 0 && z + 32 < 64) {
         c.pzoptPplFlags[z + 32] = (byte)((allSat ? 1 : 0) | (allHidden ? 2 : 0));
      }
      f.bx[blk] = Math.floorMod(c.wx * 8, f.n);
      f.by[blk] = Math.floorMod(c.wy * 8, f.n);
      f.bl[blk] = z & (LEVELS - 1);
      f.bcx[blk] = c.wx;
      f.bcy[blk] = c.wy;
      f.bz[blk] = z;
      return air;
   }

   // ---- dev (devPplProbe): which values change and come back from frame to frame ----

   private static final HashMap<Long, long[]> probePrev = new HashMap<>(); // square -> {corners, info, vis, packed} at t-2 and t-1
   private static final HashMap<Long, Long> probePacked = new HashMap<>(); // this frame's packed squares (pack())
   private static long probeFrames;

   private static long probeKey(int x, int y, int z) {
      return ((long)x << 36) ^ ((long)(y & 0xFFFFF) << 12) ^ (z & 0xFFF);
   }

   private static void probe(int playerIndex, int blocks) {
      IsoPlayer p = IsoPlayer.players[playerIndex];
      if (p == null) {
         probePacked.clear();
         return;
      }
      IsoCell cell = IsoWorld.instance.currentCell;
      int r = Config.DEV_PPL_PROBE, px = (int)Math.floor(p.getX()), py = (int)Math.floor(p.getY()), pz = (int)Math.floor(p.getZ() + 0.05F);
      int sqs = 0, packed = 0;
      int[] ch = new int[4], aba = new int[4];
      for (int y = py - r; y <= py + r; y++) {
         for (int x = px - r; x <= px + r; x++) {
            IsoGridSquare sq = cell.getGridSquare(x, y, pz);
            if (sq == null || !(sq.lighting[playerIndex] instanceof LightingJNI.JNILighting jl)) {
               continue;
            }
            sqs++;
            long corners = 0L;
            for (int i = 0; i < 8; i++) {
               corners = corners * 1000003L + jl.pzoptVert(i);
            }
            zombie.core.textures.ColorInfo li = jl.pzoptInfo();
            long info = (long)(li.r * 1000.0F) * 1000003L * 1000003L + (long)(li.g * 1000.0F) * 1000003L + (long)(li.b * 1000.0F);
            long k = probeKey(x, y, pz);
            long[] h = probePrev.get(k);
            Long pk = probePacked.get(k);
            long now3 = pk != null ? pk : h != null ? h[7] : 0L;
            if (pk != null) packed++;
            long[] v = {corners, info, jl.pzoptVis(), now3};
            if (h == null) {
               h = new long[8];
               for (int i = 0; i < 4; i++) {
                  h[i] = h[4 + i] = v[i];
               }
               probePrev.put(k, h);
               continue;
            }
            for (int i = 0; i < 4; i++) {
               if (v[i] != h[4 + i]) {
                  ch[i]++;
                  if (v[i] == h[i]) aba[i]++;
               }
               h[i] = h[4 + i];
               h[4 + i] = v[i];
            }
         }
      }
      probePacked.clear();
      probeFrames++;
      StringBuilder t = new StringBuilder();
      ArrayList<zombie.characters.IsoGameCharacter.TorchInfo> torches = LightingJNI.pzoptTorches();
      for (int i = 0; i < torches.size(); i++) {
         zombie.characters.IsoGameCharacter.TorchInfo ti = torches.get(i);
         t.append(String.format(java.util.Locale.ROOT, " [id=%d at %.3f,%.3f,%.2f dir %.3f,%.3f dist %.1f str %.2f cone %b dot %.3f]", ti.id, ti.x, ti.y, ti.z, ti.angleX,
               ti.angleY, ti.dist, ti.strength, ti.cone, ti.dot));
      }
      Log.info(String.format(java.util.Locale.ROOT, "ppl probe: f=%d ms=%d pos=%.3f,%.3f,%d cam=%.3f,%.3f sq=%d blocks=%d | corners ch=%d aba=%d | info ch=%d aba=%d | vis ch=%d aba=%d | packed n=%d ch=%d aba=%d | torches%s",
            probeFrames, System.currentTimeMillis(), p.getX(), p.getY(), pz, IsoCamera.frameState.camCharacterX, IsoCamera.frameState.camCharacterY, sqs, blocks, ch[0], aba[0],
            ch[1], aba[1], ch[2], aba[2], packed, ch[3], aba[3], t));
   }

   // the ambient under saturated torch light: the smallest light of a seen square without torch light, kept across frames
   // (reset upwards slowly so dusk and dawn follow); the fallback until the estimates below have samples
   private static float ambR = 1.0F, ambG = 1.0F, ambB = 1.0F;
   // the ambient outdoors / indoors: the most common light among the seen squares without torch light packed in a frame
   // (outdoors without a lamp every square has exactly the ambient), kept until a frame has enough samples; -1 unknown
   private static int ambOut = -1, ambIn = -1;
   private static final int[] ambKeys = new int[2 * 64], ambCounts = new int[2 * 64], ambTotal = new int[2];

   private static void ambientSample(int cls, int rgb) {
      int base = cls * 64, h = (rgb * 0x9E3779B1) >>> 26;
      for (int k = 0; k < 64; k++) {
         int i = base + (h + k & 63);
         if (ambCounts[i] == 0) {
            ambKeys[i] = rgb;
            ambCounts[i] = 1;
            ambTotal[cls]++;
            return;
         }
         if (ambKeys[i] == rgb) {
            ambCounts[i]++;
            ambTotal[cls]++;
            return;
         }
      }
   }

   /** After a frame's packs: the modes become the estimates (enough samples only), the tables are cleared. */
   private static void ambientCommit() {
      for (int cls = 0; cls < 2; cls++) {
         if (ambTotal[cls] >= 24) {
            int best = -1, bestN = 0;
            for (int i = cls * 64; i < cls * 64 + 64; i++) {
               if (ambCounts[i] > bestN) {
                  bestN = ambCounts[i];
                  best = ambKeys[i];
               }
            }
            if (bestN * 4 >= ambTotal[cls]) { // a clear mode (a quarter of the samples or more)
               if (cls == 0) ambOut = best; else ambIn = best;
            }
         }
         ambTotal[cls] = 0;
      }
      java.util.Arrays.fill(ambCounts, 0);
   }

   // ---- pplPackParallel / pplTorchNearChunk (2026-09-27) ----

   /**
    * What one pack task keeps apart from the others: the chunk being packed, its lattice bookkeeping, the counters and the
    * ambient samples of the squares it packed, and the torches that can reach the level it is on. The serial path runs
    * through {@link #SERIAL_CTX}, which samples straight into the static tables and hands its ambient minimum back, so it
    * computes exactly what the loop did before the context existed. A worker's context starts from the frame's ambient
    * and its samples are merged after the batch (the minimum and the per-colour counts do not depend on the order).
    */
   private static final class PackCtx {
      IsoChunk chunk;
      boolean lite;
      int lastAbove = -1;
      long simple, hidden, slow, copied, full;
      float ambR, ambG, ambB;
      final boolean shared;
      final int[] aKeys = new int[2 * 64], aCounts = new int[2 * 64], aTotal = new int[2];
      int[] torch = new int[16];
      int torches;
      boolean torchFilter;
      long frame = -1L;

      PackCtx(boolean shared) {
         this.shared = shared;
      }

      void begin() {
         this.simple = this.hidden = this.slow = this.copied = this.full = 0L;
         this.ambR = PixelLight.ambR;
         this.ambG = PixelLight.ambG;
         this.ambB = PixelLight.ambB;
         if (!this.shared) {
            java.util.Arrays.fill(this.aCounts, 0);
            this.aTotal[0] = this.aTotal[1] = 0;
         }
         this.torchFilter = Config.PPL_TORCH_NEAR_CHUNK && GtAb.on(GtAb.TORCH_NEAR);
      }

      /** Game thread, after the packs: the counters, the ambient minimum and (a worker's) samples into the statics. */
      void end() {
         packSimple += this.simple;
         packHidden += this.hidden;
         packSlow += this.slow;
         blocksCopied += this.copied;
         framesFull += this.full;
         this.simple = this.hidden = this.slow = this.copied = this.full = 0L;
         // (the serial context started from the static and only lowered it: its minimum is its running value)
         PixelLight.ambR = Math.min(PixelLight.ambR, this.ambR);
         PixelLight.ambG = Math.min(PixelLight.ambG, this.ambG);
         PixelLight.ambB = Math.min(PixelLight.ambB, this.ambB);
         if (this.shared) {
            return; // its samples went straight into the static tables
         }
         for (int i = 0; i < this.aKeys.length; i++) {
            for (int k = this.aCounts[i]; k > 0; k--) {
               ambientSample(i / 64, this.aKeys[i]);
            }
         }
      }

      void sample(int cls, int rgb) {
         if (this.shared) {
            ambientSample(cls, rgb);
            return;
         }
         int base = cls * 64, h = (rgb * 0x9E3779B1) >>> 26;
         for (int k = 0; k < 64; k++) {
            int i = base + (h + k & 63);
            if (this.aCounts[i] == 0) {
               this.aKeys[i] = rgb;
               this.aCounts[i] = 1;
               this.aTotal[cls]++;
               return;
            }
            if (this.aKeys[i] == rgb) {
               this.aCounts[i]++;
               this.aTotal[cls]++;
               return;
            }
         }
      }

      /**
       * pplTorchNearChunk: the frame's torches that can reach some square centre of chunk {@code c} at level {@code z}
       * (torchNear's own tests, against the chunk's square-centre rectangle with a small margin); torchNear then walks
       * only those. A torch left out is farther than its reach from every centre, so every answer is the same.
       */
      void torchLevel(IsoChunk c, int z) {
         if (!this.torchFilter) {
            return;
         }
         ArrayList<IsoGameCharacter.TorchInfo> all = LightingJNI.pzoptTorches();
         if (this.torch.length < all.size()) {
            this.torch = new int[all.size() + 16];
         }
         float x0 = c.wx * 8 + 0.5F, x1 = c.wx * 8 + 7.5F, y0 = c.wy * 8 + 0.5F, y1 = c.wy * 8 + 7.5F;
         int n = 0;
         for (int j = 0; j < all.size(); j++) {
            IsoGameCharacter.TorchInfo t = all.get(j);
            if (t.id == 0 || t.id >= 4096 || (Config.PPL_OWN_LEVEL_LIGHTS ? lightLevel(t.z) != z : Math.abs(t.z - z) > 1.5F)) {
               continue;
            }
            double dx = Math.max(0.0, Math.max(x0 - t.x, t.x - x1)), dy = Math.max(0.0, Math.max(y0 - t.y, t.y - y1));
            double r = Math.max(1.0F, t.dist) + 1.0F + 0.01;
            if (dx * dx + dy * dy < r * r) {
               this.torch[n++] = j;
            }
         }
         this.torches = n;
      }
   }

   private static final PackCtx SERIAL_CTX = new PackCtx(true);
   private static final ThreadLocal<PackCtx> PACK_CTX = ThreadLocal.withInitial(() -> new PackCtx(false));
   private static final ArrayList<PackCtx> PACK_CTX_USED = new ArrayList<>();
   private static volatile long packCtxFrame;
   private static final java.util.concurrent.atomic.AtomicInteger PACK_NEXT = new java.util.concurrent.atomic.AtomicInteger();
   private static IsoChunk[] parChunk = new IsoChunk[512];
   private static boolean[] parRing = new boolean[512], parAliased = new boolean[512];
   private static int[] slotSeen, slotFirst;
   private static int slotSeenStamp;
   private static boolean packParallelFailed;
   private static long packParallelFrames, packParallelTasks, packAliasedSerial;

   private static PackCtx packCtx() {
      PackCtx x = PACK_CTX.get();
      if (x.frame != packCtxFrame) {
         x.frame = packCtxFrame;
         x.begin();
         synchronized (PACK_CTX_USED) {
            PACK_CTX_USED.add(x);
         }
      }
      return x;
   }

   /**
    * pplPackParallel, game thread: the pack list's chunks as frame-worker tasks, one chunk (all its levels, in order) per
    * task, blocks taken from a shared counter. Every piece of lattice state a task writes belongs to its chunk's slot or to
    * its own blocks, and every read of the world is a plain field read (the lighting was refreshed on the game thread
    * before the bakes). Chunks that share a lattice slot (the ring aliases at the grid's edge when the grid fills the
    * lattice) are packed after the batch on the game thread, in list order, as the serial loop would.
    */
   private static void packParallel(Frame f, ArrayList<IsoChunk> packList, int onScreenN, int s, int playerIndex) {
      int n = packList.size();
      if (parChunk.length < n) {
         parChunk = new IsoChunk[n + 128];
         parRing = new boolean[n + 128];
         parAliased = new boolean[n + 128];
      }
      if (slotSeen == null || slotSeen.length != s * s) {
         slotSeen = new int[s * s];
         slotFirst = new int[s * s];
      }
      int stamp = ++slotSeenStamp;
      for (int i = 0; i < n; i++) {
         IsoChunk c = packList.get(i);
         int slot = Math.floorMod(c.wx, s) + Math.floorMod(c.wy, s) * s;
         parChunk[i] = c;
         parRing[i] = i >= onScreenN;
         parAliased[i] = false;
         if (slotSeen[slot] == stamp) {
            parAliased[i] = true;
            parAliased[slotFirst[slot]] = true;
         } else {
            slotSeen[slot] = stamp;
            slotFirst[slot] = i;
         }
      }
      if (f.big == null) {
         f.big = BufferUtils.createByteBuffer(MAX_BLOCKS * BLOCK_BYTES).order(ByteOrder.LITTLE_ENDIAN); // what room() grows into at 64 blocks
         f.big.put(0, f.data, 0, f.blocks * BLOCK_BYTES);
      }
      PACK_NEXT.set(f.blocks);
      packCtxFrame++;
      PACK_CTX_USED.clear();
      final IsoChunk[] chunks = parChunk;
      final boolean[] ring = parRing, aliased = parAliased;
      Throwable failure = FrameBatch.run(n, i -> {
         if (!aliased[i]) {
            packChunkLevels(packCtx(), f, chunks[i], ring[i], s, playerIndex, true);
         }
      });
      f.blocks = Math.min(PACK_NEXT.get(), MAX_BLOCKS);
      for (int k = 0; k < PACK_CTX_USED.size(); k++) {
         PACK_CTX_USED.get(k).end();
      }
      packParallelFrames++;
      packParallelTasks += n;
      if (failure != null) {
         packParallelFailed = true;
         f.blocks = 0; // a half-written block must not reach the GPU: nothing this frame, every level again next frame
         java.util.Arrays.fill(slotLevel, Integer.MIN_VALUE);
         Log.warn("pplPackParallel: a pack task failed, serial from now on: " + failure);
         return;
      }
      for (int i = 0; i < n; i++) {
         if (aliased[i]) {
            packAliasedSerial++;
            packChunkLevels(SERIAL_CTX, f, chunks[i], ring[i], s, playerIndex, false);
         }
         chunks[i] = null;
      }
   }

   /** The pack counters for the harness summary (gt_offload=). */
   static String packDescribe() {
      return "pplPack parallelFrames=" + packParallelFrames + " tasks=" + packParallelTasks + " aliasedSerial=" + packAliasedSerial + " blocks=" + blocksUploaded
            + " copied=" + blocksCopied + " fullFrames=" + framesFull + (packParallelFailed ? " FAILED" : "");
   }

   /** The level a light belongs to: a holder on the stairs (z 0.6) lights the level of the stairs' squares. */
   static int lightLevel(float z) {
      return (int)Math.floor(z + 0.05F);
   }

   /** A handheld torch could reach this point (the frame's torch list, reach + a square). */
   private static boolean torchNear(PackCtx p, float x, float y, int z) {
      ArrayList<IsoGameCharacter.TorchInfo> torches = LightingJNI.pzoptTorches();
      int count = p.torchFilter ? p.torches : torches.size();
      for (int j = 0; j < count; j++) {
         IsoGameCharacter.TorchInfo t = torches.get(p.torchFilter ? p.torch[j] : j);
         if (t.id == 0 || t.id >= 4096 || (Config.PPL_OWN_LEVEL_LIGHTS ? lightLevel(t.z) != z : Math.abs(t.z - z) > 1.5F)) {
            continue;
         }
         float dx = x - t.x, dy = y - t.y, r = Math.max(1.0F, t.dist) + 1.0F;
         if (dx * dx + dy * dy < r * r) {
            return true;
         }
      }
      return false;
   }

   /** No square there (a lit corner always has alpha 255; -1 would be a white corner). */
   private static final int NO_SQUARE = 0x00FFFFFF;

   /** A neighbour square's corner colour as lit (NO_SQUARE: none), for the connectivity bits. */
   private static int corner(PackCtx p, IsoCell cell, int x, int y, int z, int i, int playerIndex) {
      IsoChunk c = p.chunk;
      int lx = x - c.wx * 8, ly = y - c.wy * 8;
      IsoGridSquare sq = lx >= 0 && lx < 8 && ly >= 0 && ly < 8 ? c.getGridSquare(lx, ly, z) : cell.getGridSquare(x, y, z); // inside the chunk: no cell lookup
      return sq != null && sq.lighting[playerIndex] instanceof LightingJNI.JNILighting jl ? jl.pzoptVert(i) : NO_SQUARE;
   }

   /** Per channel 128 + (top mean - bottom mean) / 2 of the corner colours: a wall's light from its foot to its top. */
   private static int wallDelta(int v0, int v1, int v2, int v3, int t0, int t1, int t2, int t3) {
      int out = 0;
      for (int sh = 0; sh < 24; sh += 8) {
         int bot = (v0 >> sh & 0xFF) + (v1 >> sh & 0xFF) + (v2 >> sh & 0xFF) + (v3 >> sh & 0xFF);
         int top = (t0 >> sh & 0xFF) + (t1 >> sh & 0xFF) + (t2 >> sh & 0xFF) + (t3 >> sh & 0xFF);
         out |= Math.max(0, Math.min(255, 128 + (top - bot) / 8)) << sh;
      }
      return out;
   }

   private static boolean same(int a, int b) {
      if (b == NO_SQUARE) {
         return false;
      }
      return Math.abs((a & 0xFF) - (b & 0xFF)) <= 3 && Math.abs((a >> 8 & 0xFF) - (b >> 8 & 0xFF)) <= 3 && Math.abs((a >> 16 & 0xFF) - (b >> 16 & 0xFF)) <= 3;
   }

   private static int meanAbgr(int a, int b, int c, int d) {
      int r = ((a & 0xFF) + (b & 0xFF) + (c & 0xFF) + (d & 0xFF)) / 4;
      int g = ((a >> 8 & 0xFF) + (b >> 8 & 0xFF) + (c >> 8 & 0xFF) + (d >> 8 & 0xFF)) / 4;
      int bl = ((a >> 16 & 0xFF) + (b >> 16 & 0xFF) + (c >> 16 & 0xFF) + (d >> 16 & 0xFF)) / 4;
      return r | g << 8 | bl << 16;
   }

   private static int toggleNext;
   private static float[] toggleAt;

   /** devPplToggleAt=s1,s2: the mode flips at those seconds after the world is up (every texture re-baked), for same-scene A/Bs. */
   private static void scheduledToggles() {
      if (toggleAt == null) {
         String[] parts = Config.DEV_PPL_TOGGLE_AT.split(",");
         toggleAt = new float[parts.length];
         for (int i = 0; i < parts.length; i++) {
            toggleAt[i] = Float.parseFloat(parts[i].trim());
         }
      }
      if (toggleNext >= toggleAt.length || worldUpNs == 0L || failed) {
         return;
      }
      if ((System.nanoTime() - worldUpNs) / 1e9 >= toggleAt[toggleNext]) {
         toggleNext++;
         unwhitenAll();
         ACTIVE = !ACTIVE;
         slotChunk = null;
         n = 0; // every lattice block again
         rebakeAll = true;
         Log.info("pixel light: dev toggle, now " + (ACTIVE ? "per-pixel" : "stock baked light") + " epoch_ms=" + System.currentTimeMillis());
      }
   }

   private static void rebakeEverything() {
      IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
      if (cell == null) {
         return;
      }
      int count = 0;
      for (int p = 0; p < 4; p++) {
         IsoChunkMap cm = cell.chunkMap[p];
         if (cm == null) {
            continue;
         }
         for (int cy = 0; cy < IsoChunkMap.chunkGridWidth; cy++) {
            for (int cx = 0; cx < IsoChunkMap.chunkGridWidth; cx++) {
               IsoChunk c = cm.getChunk(cx, cy);
               if (c != null) {
                  c.getRenderLevels(p).invalidateAll(32L);
                  count++;
               }
            }
         }
      }
      Log.info("pixel light: " + count + " chunks re-baked with the stock light");
   }

   static final int BLOCK_BYTES = 768; // per chunk level, 8x8 RGBA8 each: base light + simple flag, connectivity + torch visibility + gradient flag, wall gradient
   static final int MAX_BLOCKS = 1024;

   /** One frame: the lattice blocks to upload and the camera, rendered in stream order before the composite (or after it: the pass). */
   static final class Frame extends TextureDraw.GenericDrawer {
      volatile boolean free = true;
      long seq; // devPplFrameLog
      final int[] bcx = new int[MAX_BLOCKS], bcy = new int[MAX_BLOCKS], bz = new int[MAX_BLOCKS]; // pplEaseMs: each block's chunk and level (a slot reused by another chunk snaps)
      boolean ease; // pplEaseMs: this frame's light changes ease in (false: a global light event, lightning or a dusk step, snaps)
      int rendered; // devPplFrameLog: times the render thread drew it (> 1: a replayed state)
      final ByteBuffer data = BufferUtils.createByteBuffer(64 * BLOCK_BYTES).order(ByteOrder.LITTLE_ENDIAN);
      ByteBuffer big; // grown on demand up to MAX_BLOCKS
      int[] bx = new int[MAX_BLOCKS], by = new int[MAX_BLOCKS], bl = new int[MAX_BLOCKS];
      int blocks, n;
      boolean composite;
      float zoom, offX, offY, d0, jx, jy;
      int ts, screenW, screenH, ox, oy;
      int lights, shadowLight, chunks;
      float wet, ambient;
      final zombie.core.textures.Texture[] chunkKeys = new zombie.core.textures.Texture[1024];
      final float[] chunkRect = new float[1024 * 4];
      final int[] chunkFlags = new int[1024];
      final float[] la = new float[MAX_LIGHTS * 4], lb = new float[MAX_LIGHTS * 4], lc = new float[MAX_LIGHTS * 4];
      final float[] lh = new float[MAX_LIGHTS]; // each light's height above la.z, levels (the shader reads it from pplLc.w's fraction)
      final float[] lbody = new float[MAX_LIGHTS * 4]; // torchSourceSelfShadow: the carrier's body (x, y relative to the origin square, radius, 1) or zeros

      ByteBuffer buf() {
         return this.big != null ? this.big : this.data;
      }

      boolean room() {
         if (this.blocks < 64) {
            return true;
         }
         if (this.blocks >= MAX_BLOCKS) {
            return false;
         }
         if (this.big == null) {
            this.big = BufferUtils.createByteBuffer(MAX_BLOCKS * BLOCK_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            this.big.put(0, this.data, 0, this.blocks * BLOCK_BYTES);
         }
         return true;
      }

      @Override
      public void render() {
         if (FrameLog.ON) {
            FrameLog.render(this.seq, ++this.rendered);
         }
         try {
            GL.render(this);
         } catch (Throwable t) {
            fail("render: " + t);
         }
      }

      /**
       * The state that holds this frame is recycled (game thread, GenericSpriteRenderState.clear): only now can the frame be
       * reused. Not after render(): the render thread renders its last state again while the game thread is late (a chunk
       * crossing, the chunk map shift), and a frame already refilled with the next frame's camera put the lattice mapping one
       * chunk away from the replayed composite's depth for that frame (a whole screen lit from the level above: black, or
       * hidden rooms lit; the flip report, 2026-09-25).
       */
      @Override
      public void postRender() {
         java.util.Arrays.fill(this.chunkKeys, 0, Math.min(this.chunks, this.chunkKeys.length), null); // the depth textures, no longer referenced
         this.free = true;
      }
   }

   // ------------------------------------------------------------------------------------------------ render thread

   private static final Gl GL = new Gl();
   static final int LATTICE_UNIT = 12; // the texture unit the lattice stays bound to for the chunk composite
   static final int INFO_UNIT = 11; // and the per-square light
   static final int VIS_UNIT = 10; // and the per-square torch visibility
   static final int MASK_UNIT = 9; // and the previous frame's torch shadow mask (not 4: FogPass and HdrGlint bind it, the fog between composites)

   static final class Gl {
      private int program, quadVbo, lattice, latticeN, info, vis;
      private final int[] u = new int[12];
      private final int[] viewport = new int[4];
      private final float[] viewportF = new float[4];
      private boolean logged;
      static long passNs, uploadNs;
      // the camera of the newest frame (render thread), what the composite's uniforms are made from
      static int serial;
      final java.util.HashMap<Integer, Integer> appliedSerials = new java.util.HashMap<>();
      static boolean wantOn, onSent;
      private float zoom, offX, offY, d0, jx, jy;
      private int ts, screenW, screenH, ox, oy, n, lights, shadowLight = -1;
      private float wet, ambient = -1.0F;
      private final java.util.IdentityHashMap<zombie.core.textures.Texture, Integer> chunkIndex = new java.util.IdentityHashMap<>();
      private final float[] chunkRect = new float[1024 * 4];
      private final int[] chunkFlags = new int[1024];
      private boolean baseTried;
      private int precompiled;
      private int twinsWarmed;
      // dry: torch and lamps, torch, lamps; then the same in the rain
      private static final int[] PRECOMPILE = {V_NO_WET | V_NO_MASK, V_NO_WET | V_NO_MASK | V_NO_POINT, V_NO_WET | V_NO_MASK | V_NO_TORCH, V_NO_MASK,
         V_NO_MASK | V_NO_POINT, V_NO_MASK | V_NO_TORCH};

      /**
       * The dynamic lights that reach the chunk texture drawn with this depth texture, one bit each (unknown: all): the
       * shader loops over these only (a tiled light list, one tile per chunk texture), none = the light-free variant.
       */
      private final java.util.HashMap<Integer, zombie.viewCone.ChunkRenderShader> variants = new java.util.HashMap<>();
      private boolean switchToggle;

      /** The program for a chunk texture with these lights: a variant without the kinds it does not need, null = the full one. */
      zombie.core.opengl.Shader variantFor(int bits) {
         if (!Config.PPL_VARIANTS || Config.DEV_PPL_VIEW != 0 || baseShader == null) {
            return null;
         }
         if ((costMask & 8192) != 0) { // dev: two identical light-free programs, alternating draw by draw (what a program switch costs)
            if (!this.variants.containsKey(V_BASE | V_COPY)) {
               this.variants.put(V_BASE | V_COPY, compileVariant(V_BASE | V_COPY));
            }
            zombie.viewCone.ChunkRenderShader copy = this.variants.get(V_BASE | V_COPY);
            return (this.switchToggle ^= true) || copy == null ? baseShader : copy;
         }
         if ((costMask & 512) != 0) {
            return baseShader; // dev: the light-free variant everywhere
         }
         boolean point = false, torch = false, vehicle = false, mask = false;
         for (int b = bits & ((1 << this.lights) - 1); b != 0; b &= b - 1) {
            int i = Integer.numberOfTrailingZeros(b);
            float kind = this.lc[i * 4 + 3];
            point |= kind == 0.0F;
            torch |= kind == 1.0F;
            vehicle |= kind == 2.0F;
            mask |= i == this.shadowLight;
         }
         boolean wet = this.wet * Config.PPL_SPEC_PCT / 100.0F > 0.004F;
         if (!point && !torch && !(vehicle && wet)) {
            return baseShader; // a vehicle light only lights the wet glints
         }
         int key = (point ? 0 : V_NO_POINT) | (torch ? 0 : V_NO_TORCH) | (wet ? 0 : V_NO_WET) | (mask && this.maskValid && Config.PPL_SHADOWS ? 0 : V_NO_MASK)
            | ((costMask & 32768) != 0 ? V_NO_RELIEF : 0); // dev: relief compiled out (devPplAlternate A/B of its cost)
         if (key == 0) {
            return null;
         }
         if (!this.variants.containsKey(key)) {
            this.variants.put(key, compileVariant(key)); // once per kind of scene (a few ms, on first use)
         }
         return this.variants.get(key);
      }

      int lightBits(zombie.core.textures.Texture depth) {
         int all = (1 << this.lights) - 1;
         Integer k = depth == null ? null : this.chunkIndex.get(depth);
         if (k == null || Config.DEV_PPL_VIEW != 0) {
            return all;
         }
         int bits = 0;
         float x0 = this.chunkRect[k * 4], y0 = this.chunkRect[k * 4 + 1], z0 = this.chunkRect[k * 4 + 2], z1 = this.chunkRect[k * 4 + 3];
         // saturated light: a lamp or torch adds nothing under the clamp (unless wet: the glints go on top); every square
         // hiding the torch: it is multiplied by a visibility of 0
         boolean sat = (this.chunkFlags[k] & 1) != 0 && this.wet * Config.PPL_SPEC_PCT / 100.0F <= 0.004F, hidden = (this.chunkFlags[k] & 2) != 0;
         for (int i = 0; i < this.lights; i++) {
            float lx = this.la[i * 4], ly = this.la[i * 4 + 1], lz = this.la[i * 4 + 2], reach = this.la[i * 4 + 3];
            float kind = this.lc[i * 4 + 3];
            if (sat && kind < 1.5F || hidden && kind == 1.0F || kind >= 1.0F && !coneReaches(i, x0, y0)) {
               culled++;
               continue;
            }
            if (Config.PPL_OWN_LEVEL_LIGHTS ? lightLevel(lz) < z0 || lightLevel(lz) > z1 : lz < z0 - 1.5F || lz > z1 + 1.5F) {
               continue;
            }
            float dx = Math.max(0.0F, Math.max(x0 - lx, lx - (x0 + 8.0F))), dy = Math.max(0.0F, Math.max(y0 - ly, ly - (y0 + 8.0F)));
            if (dx * dx + dy * dy < (reach + 1.5F) * (reach + 1.5F)) {
               bits |= 1 << i;
            }
         }
         if (bits == 0 && this.shadowLight >= 0 && Config.PPL_SHADOWS) {
            bits = 1 << this.shadowLight; // the mask's torch: the full program (it reads the mask) even out of its reach
         }
         return bits;
      }
      /**
       * Whether torch / headlight i can light any point of the chunk at (x0, y0) (its 8x8 squares plus 3 for tall sprites
       * drawn into it): the shader's cone (pplTorch: the ramp starts at the cone's cos + 0.025, a vehicle's at - 0.28) and
       * the spill at a holder's feet; grid samples plus both cone edges against the rectangle.
       */
      private boolean coneReaches(int i, float x0, float y0) {
         float ax = this.la[i * 4], ay = this.la[i * 4 + 1], reach = this.la[i * 4 + 3] + 1.0F;
         float dx = this.lb[i * 4], dy = this.lb[i * 4 + 1], cone = this.lb[i * 4 + 2];
         if (cone < -1.5F) {
            return true; // no cone: the reach test decided
         }
         boolean car = this.lc[i * 4 + 3] > 1.5F;
         float c0 = (car ? cone - 0.28F : cone + 0.025F) - 0.05F; // a little wider than the shader's ramp
         float m = 3.0F, rx0 = x0 - m, ry0 = y0 - m, rx1 = x0 + 8.0F + m, ry1 = y0 + 8.0F + m;
         float cx = Math.max(rx0, Math.min(ax, rx1)), cy = Math.max(ry0, Math.min(ay, ry1));
         if ((cx - ax) * (cx - ax) + (cy - ay) * (cy - ay) < 1.0F) {
            return true; // the holder is in or next to it (the spill, the cone's apex)
         }
         for (int gy = 0; gy <= 6; gy++) {
            for (int gx = 0; gx <= 6; gx++) {
               float px = rx0 + (rx1 - rx0) * gx / 6.0F - ax, py = ry0 + (ry1 - ry0) * gy / 6.0F - ay;
               float d = (float)Math.sqrt(px * px + py * py);
               if (d < reach && (px * dx + py * dy) > c0 * d) {
                  return true;
               }
            }
         }
         if (c0 <= -1.0F) {
            return true;
         }
         float s = (float)Math.sqrt(Math.max(0.0F, 1.0F - c0 * c0));
         for (int e = -1; e <= 1; e += 2) { // the cone's edges: dir rotated by +-acos(c0)
            float ex = dx * c0 - e * dy * s, ey = dy * c0 + e * dx * s;
            if (segmentHitsRect(ax, ay, ax + ex * reach, ay + ey * reach, rx0, ry0, rx1, ry1)) {
               return true;
            }
         }
         return false;
      }

      private static boolean segmentHitsRect(float x0, float y0, float x1, float y1, float rx0, float ry0, float rx1, float ry1) {
         float t0 = 0.0F, t1 = 1.0F, ddx = x1 - x0, ddy = y1 - y0;
         float[] p = {-ddx, ddx, -ddy, ddy}, q = {x0 - rx0, rx1 - x0, y0 - ry0, ry1 - y0};
         for (int k = 0; k < 4; k++) {
            if (p[k] == 0.0F) {
               if (q[k] < 0.0F) {
                  return false;
               }
            } else {
               float t = q[k] / p[k];
               if (p[k] < 0.0F) {
                  t0 = Math.max(t0, t);
               } else {
                  t1 = Math.min(t1, t);
               }
               if (t0 > t1) {
                  return false;
               }
            }
         }
         return true;
      }

      private final float[] la = new float[MAX_LIGHTS * 4], lb = new float[MAX_LIGHTS * 4], lc = new float[MAX_LIGHTS * 4];
      private final float[] lh = new float[MAX_LIGHTS];
      private final float[] lbody = new float[MAX_LIGHTS * 4];
      private final java.util.HashMap<Integer, int[]> selState = new java.util.HashMap<>(); // program -> {pplSel location, value sent}

      /** Per chunk draw on the full program: its light list (a uniform, sent only when it changes). */
      void selectLights(int program, int bits) {
         int[] st = this.selState.get(program);
         if (st == null) {
            st = new int[] {GL20.glGetUniformLocation(program, "pplSel"), -1};
            this.selState.put(program, st);
         }
         if (st[0] >= 0 && st[1] != bits) {
            GL20.glUniform1i(st[0], bits);
            st[1] = bits;
         }
      }

      private final java.util.HashMap<Integer, int[]> levelState = new java.util.HashMap<>(); // program -> {pplLv location, min, top sent}

      /** Per chunk draw on every program: the levels the chunk texture holds (a uniform, sent only when it changes). */
      void selectLevels(int program, zombie.core.textures.Texture depth) {
         int[] st = this.levelState.get(program);
         if (st == null) {
            st = new int[] {GL20.glGetUniformLocation(program, "pplLv"), Integer.MIN_VALUE, Integer.MIN_VALUE};
            this.levelState.put(program, st);
         }
         Integer k = depth == null ? null : this.chunkIndex.get(depth);
         int lo = k == null ? -64 : (int)this.chunkRect[k * 4 + 2], hi = k == null ? 64 : (int)this.chunkRect[k * 4 + 3];
         // pplAirFill packs every chunk's level above its top (zTop >= maxLevel + 1), so the texture's two levels are all
         // readable: the top is the texture's own (min + 1), not min(that, the chunk's top). With the chunk's top a tree
         // crown copied into a single-storey neighbour's texture read level 0 where its own texture (a chunk with a level 1)
         // read level 1; the two identical crowns tie in depth row by row, and the rows the copy won drew the ground's light:
         // dark bands and every-other-row stripes in a rectangle of the crown (2026-10-04, runs treelines-*)
         if (k != null && Config.PPL_AIR_FILL) {
            hi = lo + 1;
         }
         if (st[0] >= 0 && (st[1] != lo || st[2] != hi)) {
            GL20.glUniform2i(st[0], lo, hi);
            st[1] = lo;
            st[2] = hi;
         }
      }
      private final java.util.HashMap<Integer, int[]> chunkUniforms = new java.util.HashMap<>();
      private int diag;

      // ---- pplEaseMs (2026-10-03, the floor tiles flashing while turning): the native hands its light changes over in batches
      // (a vision pass changes dozens of chunk levels in one frame) and the lattice applied each at once, a lit / dark layout
      // snapping in one frame; without pixelLight the same changes reach the screen through the budgeted re-bakes, spread.
      // Here each re-packed light block of a chunk level already shown eases from the shown values to the new ones over
      // pplEaseMs (rgb, and the torch visibility alpha: 64..192 reads as a fraction on the edge path). A block that now holds
      // another chunk, the first upload of a block and a frame with a global light event (a flash, a dusk step) snap.
      private final java.util.HashMap<Long, byte[]> easeShown = new java.util.HashMap<>(); // the info block as on the GPU
      private final java.util.HashMap<Long, Long> easeOwner = new java.util.HashMap<>(); // its chunk and level
      private final java.util.HashMap<Long, byte[][]> easing = new java.util.HashMap<>(); // key -> {from, to, {t0 as 8 bytes}}
      private final ByteBuffer easeBuf = BufferUtils.createByteBuffer(256);
      private final java.util.ArrayList<Long> easeDone = new java.util.ArrayList<>();
      static long eased, easeUploads;

      private static long easeKey(int bx, int by, int bl) {
         return (long)bl << 40 | (long)by << 20 | bx;
      }

      /** Block i's light section: false = upload it now (snap), true = it eases in (easeStep uploads it). */
      private boolean easeIn(Frame f, int i, ByteBuffer b) {
         long key = easeKey(f.bx[i], f.by[i], f.bl[i]);
         long own = (long)f.bcx[i] << 36 ^ (long)f.bcy[i] << 12 ^ (f.bz[i] + 64);
         byte[] shown = this.easeShown.get(key);
         Long o = this.easeOwner.get(key);
         int base = i * BLOCK_BYTES;
         if (f.ease && shown != null && o != null && o == own) {
            int diff = 0;
            for (int k = 0; k < 256; k++) {
               diff = Math.max(diff, Math.abs((shown[k] & 0xFF) - (b.get(base + k) & 0xFF)));
            }
            if (diff > 6) {
               byte[][] e = this.easing.get(key);
               if (e == null) {
                  e = new byte[][] {new byte[256], new byte[256], new byte[8]};
                  this.easing.put(key, e);
               }
               System.arraycopy(shown, 0, e[0], 0, 256); // from what is on screen now (an ease in progress continues from there)
               for (int k = 0; k < 256; k++) {
                  e[1][k] = b.get(base + k);
               }
               long now = System.nanoTime();
               for (int k = 0; k < 8; k++) {
                  e[2][k] = (byte)(now >>> (k * 8));
               }
               eased++;
               return true;
            }
         }
         if (shown == null) {
            shown = new byte[256];
            this.easeShown.put(key, shown);
         }
         for (int k = 0; k < 256; k++) {
            shown[k] = b.get(base + k);
         }
         this.easeOwner.put(key, own);
         this.easing.remove(key);
         return false;
      }

      /** Every easing light block one step further (the info texture is bound). */
      private void easeStep() {
         if (this.easing.isEmpty()) {
            return;
         }
         long now = System.nanoTime();
         float dur = Config.PPL_EASE_MS * 1e6F;
         for (java.util.Map.Entry<Long, byte[][]> en : this.easing.entrySet()) {
            byte[][] e = en.getValue();
            long t0 = 0L;
            for (int k = 0; k < 8; k++) {
               t0 |= (e[2][k] & 0xFFL) << (k * 8);
            }
            float a = Math.min(1.0F, Math.max(0.0F, (now - t0) / dur));
            float sm = a * a * (3.0F - 2.0F * a);
            long key = en.getKey();
            byte[] shown = this.easeShown.get(key);
            this.easeBuf.clear();
            for (int k = 0; k < 256; k++) {
               int v0 = e[0][k] & 0xFF, v1 = e[1][k] & 0xFF;
               byte v = (byte)Math.round(v0 + (v1 - v0) * sm);
               shown[k] = v;
               this.easeBuf.put(v);
            }
            this.easeBuf.flip();
            int bx = (int)(key & 0xFFFFF), by = (int)(key >>> 20 & 0xFFFFF), bl = (int)(key >>> 40);
            GL12.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, bx, by, bl, 8, 8, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, this.easeBuf);
            easeUploads++;
            if (a >= 1.0F) {
               this.easeDone.add(key);
            }
         }
         for (int i = 0; i < this.easeDone.size(); i++) {
            this.easing.remove(this.easeDone.get(i));
         }
         this.easeDone.clear();
      }

      void render(Frame f) {
         if (failed) {
            return;
         }
         if ((costMask & 128) != 0 && this.latticeN == f.n) { // dev: bit 128, none of the frame's GL work (stale light, cost probe)
            serial++;
            wantOn = true;
            return;
         }
         if (this.latticeN != f.n) {
            this.allocLattice(f.n);
         }
         this.stamp(0);
         // 1. the frame's blocks: three 8x8 texel uploads per chunk level
         GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
         GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
         GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
         ByteBuffer b = f.buf();
         int[] targets = {this.info, this.vis, this.lattice};
         for (int t = 0; t < 3 && (costMask & 32) == 0; t++) { // dev: devPplCostAt bit 32 skips the uploads (stale light, cost probe)
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, targets[t]);
            for (int i = 0; i < f.blocks; i++) {
               if (t == 0 && Config.PPL_EASE_MS > 0 && this.easeIn(f, i, b)) {
                  continue; // pplEaseMs: the light block eases from what is shown (stepped below)
               }
               GL12.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, f.bx[i], f.by[i], f.bl[i], 8, 8, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, b.slice(i * BLOCK_BYTES + t * 256, 256));
            }
            if (t == 0 && Config.PPL_EASE_MS > 0) {
               this.easeStep();
            }
         }
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
         this.stamp(1);
         // 2. the camera
         this.zoom = f.zoom;
         this.offX = f.offX;
         this.offY = f.offY;
         this.d0 = f.d0;
         this.jx = f.jx;
         this.jy = f.jy;
         this.ts = f.ts;
         this.screenW = f.screenW;
         this.screenH = f.screenH;
         this.ox = f.ox;
         this.oy = f.oy;
         this.n = f.n;
         this.lights = f.lights;
         this.wet = f.wet;
         this.ambient = f.ambient;
         this.chunkIndex.clear();
         for (int i = 0; i < f.chunks; i++) {
            this.chunkIndex.put(f.chunkKeys[i], i); // (the keys stay until postRender: a replayed state renders this frame again)
         }
         System.arraycopy(f.chunkRect, 0, this.chunkRect, 0, f.chunks * 4);
         System.arraycopy(f.chunkFlags, 0, this.chunkFlags, 0, f.chunks);
         if (Config.PPL_VARIANTS && f.composite && !this.baseTried) {
            this.baseTried = true;
            if (!Config.DEV_PPL_ALTERNATE.isEmpty() && stockFrag != null) {
               zombie.viewCone.ChunkRenderShader st = new zombie.viewCone.ChunkRenderShader("pzopt_chunkStock");
               if (st.getProgram() != null && st.isCompiled()) {
                  stockShader = st;
                  Log.info("pixel light: dev stock chunk program " + st.getID());
               }
            }
            try {
               baseShader = compileVariant(V_BASE);
               this.variants.put(V_BASE, baseShader);
            } catch (Throwable t) {
               Log.warn("pixel light: the light-free chunk program failed (" + t + "); one program for every chunk texture");
            }
         } else if (baseShader != null && Config.DEV_PPL_VIEW == 0 && this.precompiled < PRECOMPILE.length) {
            int key = PRECOMPILE[this.precompiled++]; // the common variants, one a frame after the world is up (no hitch at first use)
            if (!this.variants.containsKey(key)) {
               this.variants.put(key, compileVariant(key));
            }
         } else if (baseShader != null && Config.DEV_PPL_VIEW == 0 && this.twinsWarmed <= PRECOMPILE.length) {
            // then foliage sway's twin of each, one a frame (built on its first draw it was a 144 ms frame mid-drive)
            int key = this.twinsWarmed == 0 ? V_BASE : PRECOMPILE[this.twinsWarmed - 1];
            this.twinsWarmed++;
            Sway.warmTwin(this.variants.get(key));
         }
         this.shadowLight = f.shadowLight;
         System.arraycopy(f.la, 0, this.la, 0, f.lights * 4);
         System.arraycopy(f.lb, 0, this.lb, 0, f.lights * 4);
         System.arraycopy(f.lc, 0, this.lc, 0, f.lights * 4);
         System.arraycopy(f.lh, 0, this.lh, 0, f.lights);
         System.arraycopy(f.lbody, 0, this.lbody, 0, f.lights * 4);
         serial++;
         wantOn = true;
         if (f.composite) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + VIS_UNIT);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.vis);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + INFO_UNIT);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.info);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + LATTICE_UNIT);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.lattice);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            this.stamp(2);
            this.collect();
            return; // the composite's draws light themselves (setChunkUniforms)
         }
         this.pass();
      }

      /** window px -> (x - y, x + y - 6z) and depth -> x + y + 2z relative to the origin square: mapA = (kA, cA, kB, cB), mapC.xy = (kC, cC) */
      private void mapping(float[] out) {
         GL11.glGetFloatv(GL11.GL_VIEWPORT, this.viewportF);
         double vx = this.viewportF[0], vy = this.viewportF[1], vwF = this.viewportF[2], vhF = this.viewportF[3];
         double sxPerPx = this.screenW / vwF, syPerPx = this.screenH / vhF;
         double a32 = 32.0 * this.ts, a16 = 16.0 * this.ts;
         // pplJiggle: the game draws every chunk texture moved by the camera's jiggle fix (FBORenderChunk.renderInWorldMainThread:
         // fixJigglyModelsX / Y on screen, fixJigglyModelsSquareX + Y off its chunkDepth), i.e. the whole layer moved by (jx, jy)
         // squares; a point rebuilt without it lay (jx, jy) off (its height unchanged). The offset changes every frame while the
         // camera moves, and a surface at a square's edge took its light from the square beside it every other frame: the goods
         // on the Fossoil shelves against the wall of the unlit garage went black and back (2026-09-29)
         double jx = 0.0, jy = 0.0;
         if (Config.PPL_JIGGLE && !zombie.debug.DebugOptions.instance.fboRenderChunk.combinedFbo.getValue()) {
            jx = this.jx;
            jy = this.jy;
         }
         out[0] = (float)(sxPerPx * this.zoom / a32);
         out[1] = (float)(((-vx * sxPerPx) * this.zoom + this.offX) / a32 - (this.ox - this.oy) - (jx - jy));
         out[2] = (float)(-syPerPx * this.zoom / a16);
         out[3] = (float)((((vy + vhF) * syPerPx) * this.zoom + this.offY) / a16 - (this.ox + this.oy) - (jx + jy));
         out[4] = (float)(-1.0 / DEPTH_PER_XY);
         out[5] = (float)(this.d0 / DEPTH_PER_XY - (jx + jy));
      }

      private final float[] map = new float[6];

      /** The chunk composite program is bound: its light uniforms for this frame (or off). */
      void setChunkUniforms(int program, zombie.core.opengl.ShaderProgram sp) {
         int[] loc = this.chunkUniforms.get(program);
         if (loc == null) {
            loc = new int[] {GL20.glGetUniformLocation(program, "pplOn"), GL20.glGetUniformLocation(program, "pplClear"), GL20.glGetUniformLocation(program, "pplMapA"),
               GL20.glGetUniformLocation(program, "pplMapC"), GL20.glGetUniformLocation(program, "pplOrg"), GL20.glGetUniformLocation(program, "pplLa"),
               GL20.glGetUniformLocation(program, "pplLb"), GL20.glGetUniformLocation(program, "pplLn"), GL20.glGetUniformLocation(program, "pplOpt"),
               GL20.glGetUniformLocation(program, "pplLc"), GL20.glGetUniformLocation(program, "pplOpt2"), GL20.glGetUniformLocation(program, "pplSmP"),
               GL20.glGetUniformLocation(program, "pplSmV"), GL20.glGetUniformLocation(program, "pplSmO"), GL20.glGetUniformLocation(program, "pplWet"),
               GL20.glGetUniformLocation(program, "pplShadowMask"), GL20.glGetUniformLocation(program, "pplSmC"), GL20.glGetUniformLocation(program, "pplNoFeet"),
               GL20.glGetUniformLocation(program, "pplLbody")};
            this.chunkUniforms.put(program, loc);
            if (loc[15] >= 0) {
               // the game's ShaderProgram renumbers every sampler2D to units 0, 1, 2... after the link (layout(binding) lost;
               // the sampler2DArray ones keep theirs): the mask's unit again (the program is bound here)
               GL20.glUniform1i(loc[15], MASK_UNIT);
               Shaders.gameSamplerUnits(sp, "pixel light"); // and the game's DIFFUSE / DEPTH on the units it set (0 / 1), whatever order the driver lists them in
            }
         }

         if (loc[0] < 0) {
            return; // not our shader
         }
         if (Config.DEV_PPL_TINT) {
            int key = programKeys.getOrDefault(program, 0);
            boolean torch = (key & V_NO_TORCH) == 0, point = (key & V_NO_POINT) == 0;
            float[] t = (key & V_BASE) != 0 ? new float[] {0.55F, 1.0F, 0.55F} : torch && point ? new float[] {1.0F, 1.0F, 0.45F}
               : torch ? new float[] {1.0F, 0.5F, 0.5F} : new float[] {0.55F, 0.6F, 1.0F};
            GL20.glUniform3f(GL20.glGetUniformLocation(program, "pplTint"), t[0], t[1], t[2]);
         }
         GL20.glUniform1f(loc[1], Config.PPL_DISCARD_CLEAR ? 1.0F : 0.0F);
         if (!wantOn || failed) {
            GL20.glUniform1f(loc[0], 0.0F);
            onSent = false;
            return;
         }
         this.mapping(this.map);
         GL20.glUniform1f(loc[0], (costMask & 64) != 0 ? 0.0F : 1.0F); // dev: bit 64, the shader's light off in per-pixel mode (cost probe)
         GL20.glUniform4f(loc[2], this.map[0], this.map[1], this.map[2], this.map[3]);
         GL20.glUniform4f(loc[3], this.map[4], this.map[5], this.n, Config.DEV_PPL_VIEW);
         GL20.glUniform4i(loc[4], Math.floorMod(this.ox, this.n), Math.floorMod(this.oy, this.n), this.n - 1, LEVELS - 1);
         this.lightUniforms(loc[5], loc[6], loc[7], loc[9], loc[18]);
         GL20.glUniform4f(loc[14], this.wet * Config.PPL_SPEC_PCT / 100.0F, 48.0F, Math.max(this.ambient, 0.0F), this.ambient >= 0.0F ? 1.0F : 0.0F); // z, w: pplUnseenAmbient
         GL20.glUniform1f(loc[17], Config.PPL_TORCH_FEET_GLOW ? 0.0F : 1.0F);
         boolean mask = this.maskValid && !shadowFailed && this.shadowLight >= 0 && Config.PPL_SHADOWS;
         GL20.glUniform4f(loc[10], Config.PPL_SMOOTH ? 1.0F : 0.0F, mask ? this.shadowLight : -1.0F, this.hasTorch() ? 1.0F : 0.0F, costMask);
         if (mask) {
            GL20.glUniform4f(loc[11], this.prevMap[0], this.prevMap[1], this.prevMap[2], this.prevMap[3]);
            GL20.glUniform4f(loc[12], this.prevVp[0], this.prevVp[1], this.prevVp[2], this.prevVp[3]);
            // the previous frame's mapping is relative to its own origin square: x - y and x + y shift by the difference
            GL20.glUniform4f(loc[13], (this.ox - this.oy) - (this.prevOx - this.prevOy), (this.ox + this.oy) - (this.prevOx + this.prevOy), 0.0F, 0.0F);
            GL20.glUniform4f(loc[16], Config.PPL_SHADOW_DEPTH_TEST ? this.prevMap[4] : 0.0F, this.prevMap[5], 0.0F, 0.0F);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + MASK_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.maskTex[1]);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
         }
         GL20.glUniform4f(loc[8], Config.PPL_NORMALS ? 1.0F : 0.0F, Config.PPL_WRAP_PCT / 100.0F, Config.PPL_SHADOWS ? 1.0F : 0.0F, Config.PPL_SHADOW_SQUARES);
         onSent = true;
         if (!this.logged) {
            this.logged = true;
            Log.info(String.format("pixel light: composite mode, viewport %.0fx%.0f at %.1f,%.1f, lattice %d on unit %d", this.viewportF[2], this.viewportF[3],
               this.viewportF[0], this.viewportF[1], this.lattice, LATTICE_UNIT));
         }
      }

      private final FloatBuffer lbuf = BufferUtils.createFloatBuffer(MAX_LIGHTS * 4);

      private boolean hasTorch() {
         for (int i = 0; i < this.lights; i++) {
            if (this.lc[i * 4 + 3] == 1.0F) {
               return true;
            }
         }
         return false;
      }

      private void lightUniforms(int la, int lb, int ln, int lc, int lbody) {
         if (ln < 0) {
            return;
         }
         GL20.glUniform1i(ln, this.lights);
         if (this.lights > 0) {
            this.lbuf.clear();
            this.lbuf.put(this.la, 0, this.lights * 4).flip();
            GL20.glUniform4fv(la, this.lbuf);
            this.lbuf.clear();
            this.lbuf.put(this.lb, 0, this.lights * 4).flip();
            GL20.glUniform4fv(lb, this.lbuf);
            this.lbuf.clear();
            this.lbuf.put(this.lc, 0, this.lights * 4);
            for (int i = 0; i < this.lights; i++) { // the kind's fraction carries the light's height: kind + height / 4
               this.lbuf.put(i * 4 + 3, this.lc[i * 4 + 3] + Math.max(0.0F, Math.min(1.99F, this.lh[i])) * 0.25F);
            }
            this.lbuf.flip();
            GL20.glUniform4fv(lc, this.lbuf);
            if (lbody >= 0) {
               this.lbuf.clear();
               this.lbuf.put(this.lbody, 0, this.lights * 4).flip();
               GL20.glUniform4fv(lbody, this.lbuf);
            }
         }
      }

      // the torch shadow mask: half the viewport per axis, marched in the scene depth right after the composite, blurred;
      // the next frame's composite reads it through the previous frame's mapping
      static boolean shadowFailed;
      private int shadowProgram, blurProgram, maskW, maskH;
      private final int[] maskTex = new int[2], maskFbo = new int[2];
      private final int[] su = new int[8], bu = new int[3];
      boolean maskValid;
      private final float[] prevMap = new float[6], prevVp = new float[4];
      private int prevOx, prevOy;

      void shadowPass() {
         if (shadowFailed || failed || this.shadowLight < 0 || this.shadowLight >= this.lights) {
            this.maskValid = false;
            return;
         }
         GL11.glGetIntegerv(GL11.GL_VIEWPORT, this.viewport);
         GL11.glGetFloatv(GL11.GL_VIEWPORT, this.viewportF);
         int vx = this.viewport[0], vy = this.viewport[1], vw = this.viewport[2], vh = this.viewport[3];
         int sceneFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
         int depthTex = sceneDepthTexture(sceneFbo);
         if (vw <= 0 || vh <= 0 || depthTex == 0) {
            this.maskValid = false;
            return;
         }
         if (this.shadowProgram == 0) {
            this.shadowProgram = AmbientOcclusion.link(AmbientOcclusion.QUAD_VERT, SHADOW_FRAG);
            this.blurProgram = AmbientOcclusion.link(AmbientOcclusion.QUAD_VERT, MASK_BLUR_FRAG);
            if (this.shadowProgram == 0 || this.blurProgram == 0) {
               shadowFailed = true;
               Log.warn("pixel light: the shadow mask shaders did not compile, shadows off");
               return;
            }
            String[] names = {"SceneDepth", "pplMapA", "pplMapC", "pplSv", "pplSa", "pplSb", "pplOpt", "pplSteps"};
            for (int i = 0; i < names.length; i++) {
               this.su[i] = GL20.glGetUniformLocation(this.shadowProgram, names[i]);
            }
            this.bu[0] = GL20.glGetUniformLocation(this.blurProgram, "Mask");
            this.bu[1] = GL20.glGetUniformLocation(this.blurProgram, "texel");
            if (this.quadVbo == 0) {
               this.init(); // the quad (and the pass program, unused here)
            }
         }
         int mw = (vw + 1) / 2, mh = (vh + 1) / 2;
         if (mw != this.maskW || mh != this.maskH) {
            for (int i = 0; i < 2; i++) {
               if (this.maskTex[i] != 0) {
                  GL11.glDeleteTextures(this.maskTex[i]);
                  GL30.glDeleteFramebuffers(this.maskFbo[i]);
               }
               this.maskTex[i] = GL11.glGenTextures();
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.maskTex[i]);
               GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG16F, mw, mh, 0, GL30.GL_RG, GL11.GL_FLOAT, (ByteBuffer)null);
               GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
               GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
               GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
               GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
               this.maskFbo[i] = GL30.glGenFramebuffers();
               GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.maskFbo[i]);
               GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, this.maskTex[i], 0);
               GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            this.maskW = mw;
            this.maskH = mh;
         }
         if (Config.DEV_PPL_TIMING) {
            ShadowTimer.stamp(0);
         }
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL11.glDisable(GL11.GL_STENCIL_TEST);
         GL11.glDisable(GL11.GL_ALPHA_TEST);
         GL11.glDisable(GL11.GL_BLEND);
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL11.glDepthMask(false);
         GL11.glColorMask(true, true, true, true);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.quadVbo);
         GL20.glEnableVertexAttribArray(0);
         for (int i = 1; i < 5; i++) {
            GL20.glDisableVertexAttribArray(i);
         }
         GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
         // 1. march
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.maskFbo[0]);
         GL11.glViewport(0, 0, mw, mh);
         GL20.glUseProgram(this.shadowProgram);
         GL20.glUniform1i(this.su[0], 0);
         GL20.glUniform4f(this.su[1], this.map[0], this.map[1], this.map[2], this.map[3]);
         GL20.glUniform4f(this.su[2], this.map[4], this.map[5], this.n, 0.0F);
         GL20.glUniform4f(this.su[3], this.viewportF[0], this.viewportF[1], 2.0F, 0.0F);
         int k = this.shadowLight * 4;
         GL20.glUniform4f(this.su[4], this.la[k], this.la[k + 1], this.la[k + 2] + this.lh[this.shadowLight], this.la[k + 3]); // z: the lamp's own height
         GL20.glUniform4f(this.su[5], this.lb[k], this.lb[k + 1], this.lb[k + 2], this.lb[k + 3]);
         GL20.glUniform4f(this.su[6], 1.0F, 0.0F, 1.0F, Config.PPL_SHADOW_SQUARES);
         GL20.glUniform1f(this.su[7], Config.PPL_SHADOW_MAX_STEPS);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
         GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
         // 2. depth-aware 3x3 blur
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.maskFbo[1]);
         GL20.glUseProgram(this.blurProgram);
         GL20.glUniform1i(this.bu[0], 0);
         GL20.glUniform2f(this.bu[1], 1.0F / mw, 1.0F / mh);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.maskTex[0]);
         GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
         if (Config.DEV_PPL_TIMING) {
            ShadowTimer.stamp(1);
            ShadowTimer.collect();
         }
         System.arraycopy(this.map, 0, this.prevMap, 0, 6);
         System.arraycopy(this.viewportF, 0, this.prevVp, 0, 4);
         this.prevOx = this.ox;
         this.prevOy = this.oy;
         this.maskValid = true;
         // 3. back to the scene
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, sceneFbo);
         if (this.viewportF[0] != vx || this.viewportF[1] != vy) {
            org.lwjgl.opengl.GL41.glViewportIndexedf(0, this.viewportF[0], this.viewportF[1], this.viewportF[2], this.viewportF[3]);
         } else {
            GL11.glViewport(vx, vy, vw, vh);
         }
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
         for (int i = 0; i < 5; i++) {
            GL20.glEnableVertexAttribArray(i);
         }
         GL20.glUseProgram(0);
         zombie.core.ShaderHelper.forgetCurrentlyBound(); // the game caches the bound program: the next StartShader must bind again
         zombie.core.DefaultShader.isActive = false;
         GL11.glEnable(GL11.GL_DEPTH_TEST);
         GL11.glDepthMask(true);
         GL11.glEnable(GL11.GL_BLEND);
         GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
         GLStateRenderThread.restore();
         SpriteRenderer.ringBuffer.restoreVbos = true;
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      }

      /** Pass mode: a full-screen quad over the scene right after the composite. */
      private void pass() {
         if ((costMask & 1024) != 0) {
            return; // dev: bit 1024, no pass (pass mode's cost A/B)
         }
         GL11.glGetIntegerv(GL11.GL_VIEWPORT, this.viewport);
         int vw = this.viewport[2], vh = this.viewport[3];
         int sceneFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
         int depthTex = sceneDepthTexture(sceneFbo);
         if (vw <= 0 || vh <= 0 || depthTex == 0) {
            fail("the scene depth is not a texture (fbo " + sceneFbo + ")");
            return;
         }
         if (this.program == 0 && !this.init()) {
            fail("the pass shader did not compile");
            return;
         }
         this.mapping(this.map);
         if (!this.logged) {
            this.logged = true;
            Log.info(String.format("pixel light: pass mode on %dx%d at %.1f,%.1f, scene depth texture %d, lattice %d", vw, vh, this.viewportF[0], this.viewportF[1], depthTex,
               this.lattice));
         }
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL11.glDisable(GL11.GL_STENCIL_TEST);
         GL11.glDisable(GL11.GL_ALPHA_TEST);
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL11.glDepthMask(false);
         int view = Config.DEV_PPL_VIEW;
         if (view == 0) {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_ZERO, GL11.GL_SRC_COLOR);
         } else {
            GL11.glDisable(GL11.GL_BLEND);
         }
         GL11.glColorMask(true, true, true, false);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.quadVbo);
         GL20.glEnableVertexAttribArray(0);
         for (int i = 1; i < 5; i++) {
            GL20.glDisableVertexAttribArray(i);
         }
         GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
         GL20.glUseProgram(this.program);
         GL20.glUniform1i(this.u[0], 0);
         GL20.glUniform4f(this.u[2], this.map[0], this.map[1], this.map[2], this.map[3]);
         GL20.glUniform4f(this.u[3], this.map[4], this.map[5], this.n, view);
         GL20.glUniform4i(this.u[4], Math.floorMod(this.ox, this.n), Math.floorMod(this.oy, this.n), this.n - 1, LEVELS - 1);
         this.lightUniforms(this.u[5], this.u[6], this.u[7], this.u[9], -1);
         GL20.glUniform4f(this.u[10], Config.PPL_SMOOTH ? 1.0F : 0.0F, -1.0F, this.hasTorch() ? 1.0F : 0.0F, 0.0F);
         GL20.glUniform1f(this.u[11], Config.PPL_TORCH_FEET_GLOW ? 0.0F : 1.0F);
         GL20.glUniform4f(this.u[8], Config.PPL_NORMALS ? 1.0F : 0.0F, Config.PPL_WRAP_PCT / 100.0F, Config.PPL_SHADOWS ? 1.0F : 0.0F, Config.PPL_SHADOW_SQUARES);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + VIS_UNIT);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.vis);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + INFO_UNIT);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.info);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + LATTICE_UNIT);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.lattice);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
         GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
         this.stamp(2);
         this.collect();
         // the state VBORenderer / the sprite ring buffer expect (as AmbientOcclusion / FogPass leave it)
         GL11.glColorMask(true, true, true, true);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + LATTICE_UNIT);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + INFO_UNIT);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + VIS_UNIT);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
         for (int i = 0; i < 5; i++) {
            GL20.glEnableVertexAttribArray(i);
         }
         GL20.glUseProgram(0);
         zombie.core.ShaderHelper.forgetCurrentlyBound(); // the game caches the bound program: the next StartShader must bind again
         zombie.core.DefaultShader.isActive = false;
         GL11.glEnable(GL11.GL_DEPTH_TEST);
         GL11.glDepthMask(true);
         GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
         GLStateRenderThread.restore();
         SpriteRenderer.ringBuffer.restoreVbos = true;
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      }

      private void allocLattice(int n) {
         this.easeShown.clear(); // pplEaseMs: a new lattice holds nothing shown yet
         this.easeOwner.clear();
         this.easing.clear();
         if (this.lattice != 0) {
            GL11.glDeleteTextures(this.lattice);
         }
         this.lattice = GL11.glGenTextures();
         this.latticeN = n;
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.lattice);
         ByteBuffer zero = BufferUtils.createByteBuffer(n * n * LEVELS * 4); // the wall gradient, one texel per square
         GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA8, n, n, LEVELS, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, zero);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_MAX_LEVEL, 0);
         // one texel per square, toroidal like the lattice: GL_REPEAT lets the hardware interpolate across the wrap
         if (this.info != 0) {
            GL11.glDeleteTextures(this.info);
         }
         this.info = GL11.glGenTextures();
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.info);
         ByteBuffer zero2 = zero;
         GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA8, n, n, LEVELS, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, zero2);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_MAX_LEVEL, 0);
         if (this.vis != 0) {
            GL11.glDeleteTextures(this.vis);
         }
         this.vis = GL11.glGenTextures();
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, this.vis);
         GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA8, n, n, LEVELS, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, zero2);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_MAX_LEVEL, 0);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
      }

      private boolean init() {
         this.program = AmbientOcclusion.link(AmbientOcclusion.QUAD_VERT, PASS_FRAG);
         if (this.program == 0) {
            return false;
         }
         this.u[0] = GL20.glGetUniformLocation(this.program, "SceneDepth");
         this.u[1] = -1; // (the samplers have fixed bindings)
         this.u[2] = GL20.glGetUniformLocation(this.program, "pplMapA");
         this.u[3] = GL20.glGetUniformLocation(this.program, "pplMapC");
         this.u[4] = GL20.glGetUniformLocation(this.program, "pplOrg");
         this.u[5] = GL20.glGetUniformLocation(this.program, "pplLa");
         this.u[6] = GL20.glGetUniformLocation(this.program, "pplLb");
         this.u[7] = GL20.glGetUniformLocation(this.program, "pplLn");
         this.u[8] = GL20.glGetUniformLocation(this.program, "pplOpt");
         this.u[9] = GL20.glGetUniformLocation(this.program, "pplLc");
         this.u[10] = GL20.glGetUniformLocation(this.program, "pplOpt2");
         this.u[11] = GL20.glGetUniformLocation(this.program, "pplNoFeet");
         FloatBuffer quad = BufferUtils.createFloatBuffer(8);
         quad.put(new float[] {-1.0F, -1.0F, 1.0F, -1.0F, 1.0F, 1.0F, -1.0F, 1.0F}).flip();
         this.quadVbo = GL15.glGenBuffers();
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.quadVbo);
         GL15.glBufferData(GL15.GL_ARRAY_BUFFER, quad, GL15.GL_STATIC_DRAW);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
         return true;
      }

      private static int sceneDepthTexture(int fbo) {
         if (fbo == 0) {
            return 0;
         }
         int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
         if (type != GL11.GL_TEXTURE) {
            return 0;
         }
         return GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
      }

      // dev (devPplTiming): GPU time of the upload and the pass, 8 frames in flight
      private int[] queries;
      private int slot;
      private final boolean[] pending = new boolean[8];
      private long sumUpload, sumPass, timed;

      private void stamp(int stage) {
         if (!Config.DEV_PPL_TIMING) {
            return;
         }
         if (this.queries == null) {
            this.queries = new int[8 * 3];
            GL15.glGenQueries(this.queries);
         }
         GL33.glQueryCounter(this.queries[this.slot * 3 + stage], GL33.GL_TIMESTAMP);
      }

      private void collect() {
         if (!Config.DEV_PPL_TIMING) {
            return;
         }
         this.pending[this.slot] = true;
         this.slot = (this.slot + 1) % 8;
         if (this.pending[this.slot]) {
            int base = this.slot * 3;
            if (GL15.glGetQueryObjecti(this.queries[base + 2], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
               long t0 = GL33.glGetQueryObjecti64(this.queries[base], GL15.GL_QUERY_RESULT);
               long t1 = GL33.glGetQueryObjecti64(this.queries[base + 1], GL15.GL_QUERY_RESULT);
               long t2 = GL33.glGetQueryObjecti64(this.queries[base + 2], GL15.GL_QUERY_RESULT);
               this.sumUpload += t1 - t0;
               this.sumPass += t2 - t1;
               if (++this.timed == 1000) {
                  uploadNs = this.sumUpload / this.timed;
                  passNs = this.sumPass / this.timed;
                  Log.info(String.format("pixel light gpu us/frame: upload=%.1f pass=%.1f (1000 frames)", uploadNs / 1e3, passNs / 1e3));
                  this.sumUpload = this.sumPass = this.timed = 0;
               }
            }
            this.pending[this.slot] = false;
         }
      }
   }

   // ------------------------------------------------------------------------------------------------ shaders

   static volatile boolean chunkShaderPatched;

   /** ShaderUnit hook: the chunk composite's fragment shader gets the per-pixel light (test-compiled; stock on failure). */
   /**
    * pplDepthOpaqueOnly: stock's tileWithDepth.frag writes a sprite's depth wherever its depth texture has a value, also
    * where the sprite itself is fully transparent (opaqueWithDepth.frag, the OpaquePixelsOnly tiles, does not). A tile
    * drawn over another in a chunk bake (the goods overlay of a shelf, pixel art full of gaps) stamped its box depth into
    * the gaps, where the texture shows the shelf behind: the lit point of those texels lay up to a square away, some of
    * them across the wall in the unlit garage, and with the upscaler's jitter and the camera's jiggle moving which texel a
    * pixel shows, the Fossoil shelf goods shimmered dark (2026-09-29). With pixelLight the lit point comes from the depth:
    * a fragment of zero alpha writes neither colour (premultiplied, nothing to blend) nor depth.
    */
   private static String depthOpaqueOnly(String code) {
      if (!Config.PPL_DEPTH_OPAQUE_ONLY || !Config.PIXEL_LIGHT || !Overrides.enabled() || CoreGl.legacyMac()) {
         return code;
      }
      String cond = code.contains("if(d > 0)") ? "if(d > 0)" : code.contains("if (d > 0)") ? "if (d > 0)" : null;
      if (cond == null || !code.contains("c *= col;")) {
         Log.warn("pixel light: tileWithDepth.frag not as expected; transparent texels keep writing depth");
         return code;
      }
      Log.info("pixel light: tileWithDepth.frag writes depth only where the sprite is not fully transparent");
      return code.replace(cond, "if(d > 0 && c.a > 0.0)"); // c = the sprite's texel x the vertex colour (col.a = the object's alpha)
   }

   public static String patchShader(String fileName, String code) {
      if (fileName == null || code == null) {
         return code;
      }
      String fn = fileName.replace('\\', '/');
      if (fn.endsWith("/pzopt_chunkBase.vert")) {
         return CHUNK_BASE_VERT; // the shipped files are placeholders: the sources live here
      }
      if (fn.endsWith("/pzopt_chunkBase.frag")) {
         return "#version 420\n" + variantDefines + TINT + CHUNK_FRAG_BODY;
      }
      if (fn.endsWith("/pzopt_chunkStock.vert")) {
         return CHUNK_BASE_VERT;
      }
      if (fn.endsWith("/pzopt_chunkStock.frag")) {
         return stockFrag != null ? stockFrag : code;
      }
      if (fn.endsWith("/tileWithDepth.frag")) {
         return depthOpaqueOnly(code);
      }
      if (!fn.endsWith("/chunkShader.frag")) {
         return code;
      }
      stockFrag = code; // dev: devPplAlternate's stock program
      if ("pass".equals(Config.PPL_MODE)) {
         return code; // pass mode: the composite stays the stock program (the patched one costs its registers even unlit)
      }
      if (!(Config.PIXEL_LIGHT || !Config.DEV_PPL_TOGGLE_AT.isEmpty()) || !Overrides.enabled() || CoreGl.legacyMac()) {
         return code;
      }
      int test = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
      GL20.glShaderSource(test, CHUNK_FRAG);
      GL20.glCompileShader(test);
      boolean ok = GL20.glGetShaderi(test, GL20.GL_COMPILE_STATUS) != 0;
      String log = ok ? "" : GL20.glGetShaderInfoLog(test, 4096);
      GL20.glDeleteShader(test);
      if (!ok) {
         Log.warn("pixel light: the chunk composite shader does not compile, " + fileName + " stays stock (pass mode): " + log);
         return code;
      }
      chunkShaderPatched = true;
      Log.info("pixel light: " + fileName + " lights each composited pixel");
      return CHUNK_FRAG;
   }

   /** The light at a world position (x, y relative to the origin square; z the level): the owner square's corners from the lattice. */
   private static final String LIGHT_GLSL = String.join("\n",
      "layout(binding = 12) uniform sampler2DArray pplWall;", // LATTICE_UNIT: one texel per square, a wall's light from foot to top (0.5 + delta / 2); explicit units: the game validates the program with every sampler on unit 0, and two sampler types on one unit fail on Mesa
      "uniform vec4 pplMapA;", // x - y = pplMapA.x * px + pplMapA.y, x + y - 6z = pplMapA.z * py + pplMapA.w (relative to the origin square)
      "uniform vec4 pplMapC;", // x + y + 2z = pplMapC.x * depth + pplMapC.y; z: lattice squares per side; w: dev view
      "uniform ivec4 pplOrg;", // origin square in the lattice (x, y), squares - 1, levels - 1
      "const float PPL_LEVEL = 2.4494897;", // squares per level of height
      "uniform vec4 pplLa[16];", // dynamic lights: x, y (relative to the origin square), z, reach
      "uniform vec4 pplLb[16];", // direction x, y, cone cos (-2: a point light), strength
      "#ifdef PPL_SELF_SHADOW",
      "uniform vec4 pplLbody[16];", // torchSourceSelfShadow: a carried light's carrier, x, y, radius, 1 (0: none)
      // the carrier's body between a carried lamp and the point (a lantern at the side leaves the other side in its shadow):
      // a soft disc of the body's radius on the ground plane (a lamp ~0.08 squares across); only for points the ray reaches
      // past the body's near side
      "float pplBody(vec2 p, vec2 l, vec4 h) {",
      "   vec2 d = p - l;",
      "   float L = length(d);",
      "   float t = dot(h.xy - l, d) / max(L, 1e-4);", // the body's distance along the ray
      "   if (t <= 0.0) return 1.0;",
      // the lamp is never inside the body: a lantern held 0.2 from the body's middle (inside the 0.22 disc) shaded the whole
      // half-plane towards the body; the disc shrinks to 70 % of the lamp's distance
      "   float r = min(h.z, 0.7 * length(h.xy - l));",
      "   float off = length(h.xy - l - d * (t / L));", // the body's distance off the ray
      // the ray enters the body's disc at t - sqrt(r^2 - off^2): a point before that is lit (the ground under and just behind
      // the body is not: a lit ellipse showed there when only points past the far side counted)
      "   if (L <= t - sqrt(max(r * r - off * off, 0.0))) return 1.0;",
      // the penumbra where the ray passes the body: the lamp (~0.08 across) seen from the point, scaled to the body's plane
      // (similar triangles: lamp x (L - t) / L, never wider than the lamp; dividing by t instead blew it up to squares for
      // a body right beside its lamp, the gun light at the hip half-shaded its whole cone)
      "   float pen = 0.08 * max(L - t, 0.0) / max(L, 1e-3);",
      "   return smoothstep(r - pen, r + pen + 0.04, off);",
      "}",
      "#endif",
      "uniform int pplLn;",
      "uniform int pplSel = -1;", // the lights that reach the chunk texture being drawn (bits; set per draw in the composite: tiled light lists)
      "uniform ivec2 pplLv = ivec2(-64, 64);", // the levels the chunk texture being drawn holds (min, top; set per draw in the composite)
      "uniform vec4 pplOpt;", // x: normals on, y: wrap, z: shadows on, w: shadow march length
      "uniform vec4 pplOpt2;", // x: smoothstep between centres, y: the light the shadow mask belongs to (-1: none)
      "layout(binding = 9) uniform sampler2D pplShadowMask;", // MASK_UNIT: the previous frame's torch shadow mask
      "uniform vec4 pplSmP;", // the previous frame's window mapping (kA, cA, kB, cB)
      "uniform vec4 pplSmV;", // the previous frame's viewport
      "uniform vec4 pplSmO;", // x - y and x + y of this frame's origin minus the previous frame's
      "uniform vec4 pplSmC;", // the previous frame's depth mapping (pplMapC.xy then; x 0: no test)
      "float pplMask(vec3 P) {",
      "   float A = P.x - P.y + pplSmO.x, B = P.x + P.y - 6.0 * P.z + pplSmO.y;",
      "   vec2 f = vec2((A - pplSmP.y) / pplSmP.x, (B - pplSmP.w) / pplSmP.z);",
      "   vec2 uv = (f - pplSmV.xy) / pplSmV.zw;",
      "   if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) return 1.0;",
      "   if (pplSmC.x == 0.0) return texture(pplShadowMask, uv).r;",
      // the mask texel shows another surface than this pixel (a floor change or a cutaway swapped the scene, a mover
      // uncovered it): its shadow belongs to that surface, not to this one (a level is 2 units of x + y + 2z; the mask
      // keeps the depth at half precision, ~0.3 units: half a level of slack). Depth-aware upsampling: the four texels
      // apart, the bilinear weights of those on this pixel's surface. One bilinear read mixed a leaf's depth with the
      // ground's behind it into neither, which took the leaf for another surface and lit it fully: under a torch the bushes
      // in a shadow sparkled with one-frame lit specks as the leaves moved (2026-10-01). None on its surface: the nearest
      // within a level and a half (a leaf over the ground), past that no shadow (another surface)
      "   ivec2 msz = textureSize(pplShadowMask, 0);",
      "   vec2 mtc = uv * vec2(msz) - 0.5;",
      "   ivec2 mi = ivec2(floor(mtc));",
      "   vec2 mfr = mtc - vec2(mi);",
      "   float here = P.x + P.y + 2.0 * P.z + pplSmO.y;",
      "   float msum = 0.0, mw = 0.0, best = 1e9, bestV = 1.0;",
      "   for (int k = 0; k < 4; k++) {",
      "      ivec2 o = ivec2(k & 1, k >> 1);",
      "      vec2 m = texelFetch(pplShadowMask, clamp(mi + o, ivec2(0), msz - 1), 0).rg;",
      "      float dd = abs(pplSmC.x * m.g + pplSmC.y - here);",
      "      float w = (o.x == 1 ? mfr.x : 1.0 - mfr.x) * (o.y == 1 ? mfr.y : 1.0 - mfr.y);",
      "      if (dd <= 1.0) { msum += m.r * w; mw += w; }",
      "      if (dd < best) { best = dd; bestV = m.r; }",
      "   }",
      "   if (mw > 1e-4) return msum / mw;",
      "   return best < 3.0 ? bestV : 1.0;",
      "}",
      // the surface normal from the position's screen derivatives, in squares (a level is 2.449 squares tall), facing the
      // viewer; the sprites' depth textures make it a real normal on furniture too (call in uniform control flow)
      "vec3 pplSnapNormal(vec3 P, vec3 nn);",
      "vec3 pplNormal(vec3 P) {",
      "   vec3 m = P * vec3(1.0, 1.0, PPL_LEVEL);",
      "   return pplSnapNormal(P, cross(dFdx(m), dFdy(m)));",
      "}",
      "vec3 pplSnapNormal(vec3 P, vec3 nn) {",
      "   float l = length(nn);",
      "   nn = l > 1e-10 ? nn / l : vec3(0.0, 0.0, 1.0);",
      "   nn = dot(nn, vec3(3.0, 3.0, PPL_LEVEL)) < 0.0 ? -nn : nn;",
      // tiles are floors and walls: within ~20 degrees of one of the three planes it is that plane (the edge rows written a
      // little low tilt the raw normal into lines along every tile edge); a pixel at a level's height is floor
      "   if (abs(P.z - floor(P.z + 0.5)) < 0.008 || nn.z > 0.94) return vec3(0.0, 0.0, 1.0);",
      "   if (nn.x > 0.94) return vec3(1.0, 0.0, 0.0);",
      "   if (nn.y > 0.94) return vec3(0.0, 1.0, 0.0);",
      "   return nn;",
      "}",
      "uniform vec4 pplLc[16];", // colour, kind (1: a torch, added to the base; 0: a point light, the brightest per channel)
      // a torch's intensity (PixelLight.torchModel, fitted to the native's per-square torch entries)
      // (fitted on flip dumps, 2026-09-25: a handheld torch 1.76 x strength, falloff^1.15, cosine ramp from the cone's + 0.025
      // to 0.95, mean error 0.04; a vehicle light (focused) 1.57 x strength, falloff^1.57, ramp from the cone's - 0.28 to 1.0,
      // mean error 0.055)
      "uniform float pplNoFeet;", // 1: no spill at a handheld torch's holder's feet (pplTorchFeetGlow off; unset = 0 = the spill)
      "float pplTorch(vec2 p, vec4 a, vec4 b, float kind) {",
      "   vec2 v = p - a.xy;",
      "   float d = length(v);",
      "   bool car = kind > 1.5;",
      "   float x = clamp(1.0 - d / a.w, 0.0, 1.0);",
      "   float fall = car ? x * sqrt(x) * (0.93 + 0.07 * x) : x * (0.85 + 0.15 * x);", // ~x^1.57, ~x^1.15 without pow
      "   float ang = 1.0;",
      "   if (b.z > -1.5 && d > 1e-3) {",
      "      float c0 = car ? b.z - 0.28 : b.z + 0.025, c1 = car ? 1.0 : 0.95;",
      "      ang = clamp((dot(v, b.xy) / d - c0) / max(c1 - c0, 0.05), 0.0, 1.0);",
      "      if (!car && pplNoFeet < 0.5) ang = mix(ang, 1.0, clamp(1.0 - 1.5 * d, 0.0, 1.0));", // a little spill at the holder's feet
      "   }",
      "   return min(1.0, (car ? 1.57 : 1.76) * b.w * fall * ang);",
      "}",
      "vec3 pplPos(vec2 f, float d) {",
      "   float A = pplMapA.x * f.x + pplMapA.y;",
      "   float B = pplMapA.z * f.y + pplMapA.w;",
      "   float C = pplMapC.x * d + pplMapC.y;",
      "   float Z = (C - B) * 0.125;",
      "   float S = C - 2.0 * Z;",
      "   return vec3((S + A) * 0.5, (S - A) * 0.5, Z);",
      "}",
      // dz: the z step to the neighbour pixels (floors are flat in z, walls and objects are not)
      "layout(binding = 11) uniform sampler2DArray pplInfo;", // INFO_UNIT: one texel per square, rgb its light without torches, a the torches reach it (a wall does not hide it)
      "layout(binding = 10) uniform sampler2DArray pplConn;", // CONN_UNIT: one texel per square, r its connected neighbours (bits E S W N SE SW NW NE)
      "vec4 pplInfoAt(ivec2 s, int lvl) { return texelFetch(pplInfo, ivec3(s & pplOrg.z, lvl), 0); }",
      "bool pplVisible(float a) { return mod(floor(a * 255.0 + 0.5), 128.0) >= 32.0; }", // the conn texture's alpha: 64 / 255 visible (pplSeenEdge)
      // the facing of a surface to a light, relative to a floor's (floors unchanged; wrap softens the sprites' own shading)
      "float pplFacing(vec3 P, vec3 n, vec3 lp) {",
      "   vec3 ld = normalize(lp - P * vec3(1.0, 1.0, PPL_LEVEL));",
      "   float wr = pplOpt.y;",
      "   return clamp(max(dot(n, ld) + wr, 0.0) / max(ld.z + wr, 0.15), 0.0, 1.25);",
      "}",
      "uniform vec4 pplWet;", // x: wet ground x specular strength, y: shininess, z: the ambient (pplUnseenAmbient, on when w is 1)
      "vec3 pplSpec = vec3(0.0);", // out of pplLight: the wet glints of the lights (added, not multiplied by the surface colour)
      "const vec3 PPL_VIEW = vec3(0.6428, 0.6428, 0.5162);", // towards the camera, in squares (the axis the screen does not see: (3, 3, 1) levels)
      "#ifdef PPL_LAZY_NORMAL",
      "bool pplNeedN = false;",
      "vec3 pplLazyNormal(vec3 P);", // defined by the chunk composite (pplTexelPos)
      "#endif",
      "#if defined(PPL_RELIEF) && defined(RELIEF_SHADOW)",
      "float rlfShadow(vec3 ld);", // pzopt.Relief (after this)
      "#endif",
      "#if defined(PPL_RELIEF) && defined(RELIEF_TORCH_SHADOW)",
      "float rlfShadowCode(vec3 ld);",
      "#endif",
      "#if defined(PPL_RELIEF) && defined(RELIEF_HORIZON)",
      "float rlfHorizon(vec3 ld);",
      "#endif",
      "vec3 pplLight(vec3 P, float dz, vec3 n) {", // n: the surface normal in squares (z up), towards the viewer
      "   int cost = int(pplOpt2.w + 0.5);", // dev: parts switched off (devPplCostAt)
      "   if ((cost & 8) != 0) return vec3(fract(P.x * 0.001) + 0.999);",
      // the square that owns the surface: a hair towards the viewer (a north wall's owner is on its +y side, a west wall's
      // on its +x side, a floor's above it)
      // tile edge rows are written up to 0.005 levels low; at most one row of a wall's top goes up. Along the chunk edges the
      // edge rows sit deeper and took the level below the texture's (no squares there: a black lattice, a dotted dark line
      // along every chunk edge); a wall's top row that goes up in a chunk with no squares above took a black lattice as
      // well (dots along the wall tops): the level stays within the ones the chunk texture holds (top = the chunk's
      // highest level with squares)
      "   float lz = clamp(floor(P.z + 0.006), float(pplLv.x), float(pplLv.y));",
      "   vec2 sq = floor(P.xy + 0.004);",
      "   vec2 fxy = clamp(P.xy - sq, 0.0, 1.0);",
      "   ivec2 s = (ivec2(sq) + pplOrg.xy) & pplOrg.z;",
      // lifted a level by the tolerance: a floor's edge row a hair low or the top row of a wall of the level below; the
      // brighter of the two squares (an unseen upper floor put dark dots along the wall tops)
      "   if (P.z < lz && lz > float(pplLv.x)) {",
      "      vec3 a = pplInfoAt(s, int(lz) & pplOrg.w).rgb, b = pplInfoAt(s, int(lz - 1.0) & pplOrg.w).rgb;",
      "      if (max(b.r, max(b.g, b.b)) > max(a.r, max(a.g, a.b))) lz -= 1.0;",
      "   }",
      "   float fz = clamp(P.z - lz, 0.0, 1.0);",
      "   int lvl = int(lz) & pplOrg.w;",
      // pplSeenEdge: a point just past a square's west or north edge (the +0.004 nudge above, a DEPTH16 step) across a wall,
      // in a square the player has never seen, next to one it has: the surface is the seen room's (an object flush against the
      // wall, its depth box's face on the square's edge; the Fossoil shelf goods took the unlit garage's light behind the
      // wall, a dark speckle that moved with the upscaler's jitter and the camera's jiggle, 2026-09-29)
      "#ifdef PPL_SEEN_EDGE",
      "   if (fxy.x < 0.1 || fxy.y < 0.1) {",
      "      vec4 c0 = texelFetch(pplConn, ivec3(s, lvl), 0);",
      // pplCutEdge: or stock does not draw the square (an upper floor's orphan structure near the player: the outside wall in
      // front of the room is gone, the furniture flush against it shows; a cut-open bathroom's tub took the dark roof square's
      // light, 2026-10-05): a point a little past its west / north edge is the face of the furniture before it
      "      bool hid = (int(c0.g * 255.0 + 0.5) & 16) != 0;",
      "      bool unseen = !pplVisible(c0.a);",
      "      float tol = hid ? 0.1 : 0.03;",
      "      if (unseen || hid) {",
      "         int cb = int(c0.r * 255.0 + 0.5);",
      "         ivec2 sx = (s - ivec2(1, 0)) & pplOrg.z, sy = (s - ivec2(0, 1)) & pplOrg.z;",
      "         if (fxy.x < tol && (hid || (cb & 4) == 0) && pplVisible(texelFetch(pplConn, ivec3(sx, lvl), 0).a)) { s = sx; sq.x -= 1.0; fxy.x = 1.0; }",
      "         else if (fxy.y < tol && (hid || (cb & 8) == 0) && pplVisible(texelFetch(pplConn, ivec3(sy, lvl), 0).a)) { s = sy; sq.y -= 1.0; fxy.y = 1.0; }",
      "      }",
      "   }",
      "#endif",
      "   int view = int(pplMapC.w + 0.5);",
      // 1. the base light is the native's sample at each square's centre (lightInfo without the torches: what stock draws
      // objects with; the corners are the max over the four squares around them, which is what makes stock's light
      // blocky), interpolated between the centres, only across neighbours sharing their corner colours (the native
      // breaks them at walls); the torches' visibility the same way
      "   vec2 q = fxy - 0.5;",
      "   ivec2 dir = ivec2(q.x < 0.0 ? -1 : 1, q.y < 0.0 ? -1 : 1);",
      "   vec2 w = abs(q);",
      "   if (pplOpt2.x > 0.5) w = w * w * (3.0 - 2.0 * w);", // smoothstep between the centres: no Mach bands where the slope changes
      "   float w00 = (1.0 - w.x) * (1.0 - w.y), w10 = w.x * (1.0 - w.y), w01 = (1.0 - w.x) * w.y, w11 = w.x * w.y;",
      // one fetch: the hardware's bilinear between the four centres; its alpha is 1 exactly when all four are simple squares
      // (every neighbour connected, the torches not hidden, no vertical gradient)
      "   vec2 tx = P.xy + vec2(pplOrg.xy) - 0.5;", // in texels, centres at integers
      "   vec2 ti = floor(tx), tf = tx - ti;",
      "   if (pplOpt2.x > 0.5) tf = tf * tf * (3.0 - 2.0 * tf);",
      "   vec4 B = textureLod(pplInfo, vec3((ti + 0.5 + tf) / pplMapC.z, float(lvl)), 0.0);", // the same weights through the hardware's bilinear
      "   float V = B.a < 0.5 ? 0.0 : 1.0;", // a simple neighbourhood: all four torch-visible (1) or all four hidden (0)
      "   bool grad = false;",
      "   int conn = 255;",
      "   if ((B.a < 0.999 && B.a > 0.001 || view == 6) && (cost & 2) == 0) {",
      "      vec3 cc = texelFetch(pplConn, ivec3(s, lvl), 0).rgb;",
      "      conn = int(cc.r * 255.0 + 0.5);",
      "      grad = cc.b > 0.5;",
      "      int bx = dir.x > 0 ? 1 : 4, by = dir.y > 0 ? 2 : 8;",
      "      int bxy = dir.x > 0 ? (dir.y > 0 ? 16 : 128) : (dir.y > 0 ? 32 : 64);",
      "      bool cx = (conn & bx) != 0, cy = (conn & by) != 0, cxy = (conn & bxy) != 0;",
      // pplWallEdge: above the floor, on the side of a wall on the square's west / north edge, the square behind the wall
      // is not blended in even where the native shares its light (a window by day: the wall face around it took half the
      // room's light, a grey column down every window tile, 2026-10-03)
      "#ifdef PPL_WALL_EDGE",
      "      int we = int(cc.g * 255.0 + 0.5);",
      "      if (fz > 0.02) {",
      "         if ((we & 1) != 0 && dir.x < 0) { cx = false; cxy = false; }",
      "         if ((we & 2) != 0 && dir.y < 0) { cy = false; cxy = false; }",
      "      }",
      "#endif",
      // the neighbours' squares, the own one where not connected (a diagonal falls back to the connected side); four
      // independent fetches accumulated at once (chaining the values kept four texels alive: registers)
      "      ivec2 s10 = cx ? s + ivec2(dir.x, 0) : s, s01 = cy ? s + ivec2(0, dir.y) : s;",
      "      ivec2 s11 = cxy ? s + dir : (cx ? s10 : s01);",
      "      vec4 t00 = pplInfoAt(s, lvl), t10 = pplInfoAt(s10, lvl), t01 = pplInfoAt(s01, lvl), t11 = pplInfoAt(s11, lvl);",
      "      B = w00 * t00 + w10 * t10 + w01 * t01 + w11 * t11;",
      // the torch's visibility between the centres, from the same texels' alphas (>= 0.5: visible)
      // (64 / 255 hidden .. 192 / 255 visible on the edge path, 0 / 255 on simple squares; pplTorchFade's cross-fading squares
      // in between: the same ramp reads all four)
      "      V = dot(vec4(w00, w10, w01, w11), clamp((vec4(t00.a, t10.a, t01.a, t11.a) * 255.0 - 64.0) / 128.0, 0.0, 1.0));",
      "   }",
      "   vec3 L = B.rgb;",
      "   vec2 c00 = sq + 0.5, c11 = c00 + vec2(dir);",
      // 2. the dynamic lights at this pixel: torches (their brightest, added, where they reach), point lights (the
      // native's max(light, (1 - d / r) colour) per channel, visible where the centre samples show them)
      "   vec3 torch = vec3(0.0);",
      "   pplSpec = vec3(0.0);",
      "#ifndef PPL_BASE", // the base variant (pzopt_chunkBase: no light in reach of the chunk) has no light loop: half the registers
      "#ifdef PPL_NO_WET",
      "   bool wet = false;",
      "#else",
      "   bool wet = pplWet.x > 0.004 && pplLn > 0;",
      "#endif",
      "   if (wet) wet = texelFetch(pplConn, ivec3(s, lvl), 0).a > 0.5;", // outdoors: the rain wets it
      "   if (view != 6 && (cost & 1) == 0) {",
      "      int bits = pplSel & ((1 << pplLn) - 1);",
      "      while (bits != 0) {", // only this chunk's lights: uniform per draw, so the loop is coherent
      "         int i = findLSB(bits);",
      "         bits &= bits - 1;",
      "         vec4 a = pplLa[i], b = pplLb[i], c = pplLc[i];",
      // a light lights its own level only (a holder on the stairs: the stairs' level); the native's field between the
      // centres already carries whatever reaches another level
      Config.PPL_OWN_LEVEL_LIGHTS ? "         if (floor(a.z + 0.05) != lz) continue;" : "         if (abs(a.z - lz) > 1.5) continue;",
      "         float dd = length(P.xy - a.xy);",
      "         if (dd > a.w + 1.0) continue;",
      // a torch or a vehicle light outside its cone adds nothing here (its glint neither): skipped before the texel normal
      // (and the relief) are fetched, which the reach circle alone left to every pixel within reach
      "         float T0 = c.w > 0.5 ? pplTorch(P.xy, a, b, c.w) : 1.0;",
      "         if (T0 < 0.004 || c.w > 1.5 && !wet) continue;",
      "#ifdef PPL_LAZY_NORMAL",
      "         if (pplNeedN) { n = pplLazyNormal(P); pplNeedN = false; }", // the chunk composite's texel normal: fetched for pixels a light reaches only
      "#endif",
      "         vec3 lpos = vec3(a.xy, (a.z + fract(c.w) * 4.0) * PPL_LEVEL);", // the lamp's height: pplLc.w = kind + height / 4 (0.55 a torch, 0.6 the rest; torchSource: the lens's)
      "         float f = pplOpt.x > 0.5 ? pplFacing(P, n, lpos) : 1.0;",
      "         float glint = 0.0;",
      "         if (wet) {",
      "            vec3 ld = normalize(lpos - P * vec3(1.0, 1.0, PPL_LEVEL));",
      "            glint = pow(max(dot(n, normalize(ld + PPL_VIEW)), 0.0), pplWet.y) * step(0.0, dot(n, ld));",
      "         }",
      // a vehicle light: the native's field between the centres already carries it (a moving car's native footprint lags
      // a pass behind, so it is not replaced); its model only lights the wet glints. (A residual sharpening from the model,
      // T(p) - bilinear T(centres), cost four evaluations and doubled the shader's registers for a barely visible edge.)
      // the variants (pzopt_chunkBase with PPL_NO_*: the kinds of light a chunk texture's list does not hold) leave cases
      // out; fewer live values, more waves in flight
      "         if (c.w > 1.5) {",
      "            if (wet) pplSpec += c.rgb * T0 * glint;",
      "         } else if (c.w > 0.5) {",
      "#ifndef PPL_NO_TORCH",
      "            float tv = T0 * f;",
      "#ifdef RELIEF_SHADOW",
      "            if (tv > 0.01) tv *= rlfShadow(normalize(lpos - P * vec3(1.0, 1.0, PPL_LEVEL)));", // the art's grooves in the torch's shadow
      "#endif",
      "#ifdef RELIEF_TORCH_SHADOW",
      "            if (tv > 0.01) tv *= rlfShadowCode(normalize(lpos - P * vec3(1.0, 1.0, PPL_LEVEL)));", // the same on the relief codes
      "#endif",
      "#ifdef RELIEF_HORIZON",
      "            if (tv > 0.01) tv *= rlfHorizon(normalize(lpos - P * vec3(1.0, 1.0, PPL_LEVEL)));", // from the texel's horizons
      "#endif",
      "#ifndef PPL_NO_MASK",
      "            if (tv > 0.01 && float(i) == pplOpt2.y) tv *= pplMask(P);",
      "#endif",
      "#ifdef PPL_SELF_SHADOW",
      "            if (tv > 0.01 && pplLbody[i].w > 0.5) tv *= pplBody(P.xy, a.xy, pplLbody[i]);",
      "#endif",
      "            torch = max(torch, c.rgb * tv);",
      "            pplSpec += c.rgb * tv * V * glint;",
      "#endif",
      "         } else {",
      "#ifndef PPL_NO_POINT",
      "            float lp = clamp(1.0 - dd / a.w, 0.0, 1.0);",
      // visible where the native's light (interpolated) reaches the prediction: the concave falloff puts the interpolation a
      // little under it, hence the soft threshold
      "            float pred = max(c.r, max(c.g, c.b)) * lp;",
      "            float pv = pred < 0.03 ? 1.0 : smoothstep(0.55, 0.9, max(L.r, max(L.g, L.b)) / pred);",
      "            L = max(L, c.rgb * lp * pv * f);",
      "            pplSpec += c.rgb * lp * pv * glint;",
      "#endif",
      "         }",
      "      }",
      "   }",
      "   L = clamp(L + torch * V, 0.0, 1.0);",
      "   pplSpec *= pplWet.x;",
      "#endif",
      // 3. walls keep the native's vertical gradient (ceiling vs floor corners) on top of it
      "   if (fz > 0.001) {", // walls and objects: the square's light from its foot to its top (0.5: none)
      "      L = clamp(L + fz * (texelFetch(pplWall, ivec3(s, lvl), 0).rgb * 2.0 - 1.0), 0.0, 1.0);",
      "   }",
      // pplUnseenAmbient: a square the player has never seen baked with its own light (black; ForceAmbient roofs their tint): the
      // ambient, as stock draws those roofs
      "#ifdef PPL_UNSEEN_AMBIENT",
      "   if (pplWet.w > 0.5 && !pplVisible(texelFetch(pplConn, ivec3(s, lvl), 0).a)) { L = vec3(pplWet.z); pplSpec = vec3(0.0); }",
      "#endif",
      "#if !defined(PPL_BASE) && defined(PPL_DEV)",
      "   if (view == 5) L = torch * V;",
      "   if (view == 4) L = n * 0.5 + 0.5;",
      "#if !defined(PPL_BASE) && defined(PPL_DEV)",
      "   if (view == 3) { float c = mod(sq.x + sq.y + lz, 2.0); L = vec3(0.35 + 0.5 * c, 0.35 + 0.3 * fxy.x, 0.35 + 0.3 * fxy.y); }",
      "   if (view == 16) L = vec3(fract(P.x), fract(P.y), (mod(floor(P.x), 4.0) * 4.0 + mod(floor(P.y), 4.0) + 0.5) / 16.0);", // dev: the lit point itself, unshaded (the Fossoil shelf speckle)
      "   if (view == 8) L = vec3(float(conn & 15) / 15.0, float(conn >> 4) / 15.0, V);", // dev: connectivity, torch visibility
      "   if (view == 9) L = vec3(pplOpt2.y >= 0.0 ? pplMask(P) : 0.5);", // dev: the torch shadow mask as read (reprojected)
      "#endif",
      "   if (view == 10) L = B.a < 0.999 && B.a > 0.001 ? vec3(1.0, 0.2, 0.2) : vec3(0.2, 1.0, 0.2);", // dev: red where the edge path runs
      "   if (view == 11) L = B.rgb;", // dev: the base light between the centres
      "   if (view == 12) L = vec3(V, B.a, float(wet));", // dev: the torch visibility, the simple flag, wet
      "   if (view == 13) L = vec3(P.z < -0.05 ? 1.0 : 0.0, clamp(P.z / 6.0, 0.0, 1.0), fract(P.z));", // dev: the reconstructed height (red below level 0, green up to 6 levels, blue the fraction)
      "   if (view == 14) L = vec3(clamp((P.z - floor(P.z + 0.5)) * 12.75 + 0.5, 0.0, 1.0));", // dev: the height's offset from the nearest level, grey (the screen shader's desaturation leaves it): 0.5 = on it, 1/255 = 0.0003 levels, +-0.039
      "   if (view == 15) L = vec3(fz > 0.001 ? 1.0 : 0.0, fract(lz / 4.0) + 0.125, P.z < lz ? 1.0 : 0.0);", // dev: red the wall path, green the level read, blue lifted by the tolerance
      "#endif",
      "   return L;",
      "}");

   /** The torch shadow mask: r visibility towards the torch (marched in the scene depth), g the pixel's depth (for the blur). */
   private static final String SHADOW_FRAG = String.join("\n",
      "#version 420",
      "uniform sampler2D SceneDepth;",
      LIGHT_GLSL,
      "float pplDepthAt(vec2 f) { return texelFetch(SceneDepth, ivec2(f), 0).r; }",
      "uniform vec4 pplSv;", // viewport origin x, y (window px), window px per mask texel
      "uniform vec4 pplSa, pplSb;", // the torch
      "uniform float pplSteps;", // most march steps (pplShadowMaxSteps)
      "out vec4 fragColor;",
      "bool pplShadowHit(vec3 Q) {", // a surface in front of the ray point, within the thickness window
      "   vec2 fq = vec2((Q.x - Q.y - pplMapA.y) / pplMapA.x, (Q.x + Q.y - 6.0 * Q.z - pplMapA.w) / pplMapA.z);",
      "   float gap = (Q.x + Q.y + 2.0 * Q.z - pplMapC.y) / pplMapC.x - pplDepthAt(fq);",
      "   return gap > 0.00016 && gap < 0.0035;",
      "}",
      "void main() {",
      "   vec2 f = pplSv.xy + (floor(gl_FragCoord.xy) + 0.5) * pplSv.z;",
      "   float d = texelFetch(SceneDepth, ivec2(f), 0).r;",
      "   float vis = 1.0;",
      "   if (d < 1.0) {",
      "      vec3 P = pplPos(f, d);",
      "      if (pplTorch(P.xy, pplSa, pplSb, 1.0) > 0.01) {",
      "         vec3 L = pplSa.xyz;",
      "         vec3 dd = L - P;",
      "         float len = length(dd.xy);",
      "         float span = min(len - 0.35, pplOpt.w);",
      "         if (span > 0.05) {",
      "            vec3 st = dd / len;",
      // the samples sit on a grid across the screen shared by every pixel (x - y, or x + y - 6z at half weight when the
      // ray runs more up / down the screen: tiles are 2:1), 1/8 apart, so a thin post (a carport pole is ~0.2 of x - y
      // across) holds a sample wherever a ray crosses it, and neighbouring pixels test it at the same columns: the
      // shadow's edge follows the depth test smoothly. A fixed 16 steps over 4 squares (0.35 across) cut every pole's
      // shadow into stripes; per-pixel fractions of the ray at any count left toothed edges (2026-09-30). The grid
      // doubles while a step is under 1.5 window px or the count passes pplShadowMaxSteps; a ray that barely moves on
      // the screen (along the view) keeps 16 even steps
      "            float dA = st.x - st.y, dB = 0.5 * (st.x + st.y - 6.0 * st.z);",
      "            bool alongA = abs(dA) >= abs(dB);",
      "            float du = alongA ? dA : dB;",
      "            float u0 = alongA ? P.x - P.y : 0.5 * (P.x + P.y - 6.0 * P.z);",
      "            float g = 0.125;",
      "            float cnt = abs(du) * span / g;",
      "            if (cnt < 4.0) {",
      "               for (int k = 0; k < 16; k++) {",
      "                  if (pplShadowHit(P + st * ((float(k) + 0.5) / 16.0 * span + 0.06))) { vis = 0.0; break; }",
      "               }",
      "            } else {",
      "               float px = alongA ? abs(pplMapA.x) : 0.5 * abs(pplMapA.z);", // u per window px
      "               g *= exp2(max(max(ceil(log2(1.5 * px / g)), ceil(log2(cnt / max(pplSteps, 16.0)))), 0.0));",
      "               float ua = u0 + du * 0.06, ub = u0 + du * (span + 0.06);",
      "               float m0 = ceil(min(ua, ub) / g - 0.5);",
      "               int steps = int(floor(max(ua, ub) / g - 0.5) - m0) + 1;",
      "               for (int k = 0; k < steps; k++) {",
      "                  if (pplShadowHit(P + st * (((m0 + float(k) + 0.5) * g - u0) / du))) { vis = 0.0; break; }",
      "               }",
      "            }",
      "         }",
      "      }",
      "   }",
      "   fragColor = vec4(vis, d, 0.0, 1.0);",
      "}");

   /** 3x3 blur of the mask, only across texels at a depth close to the centre's. */
   private static final String MASK_BLUR_FRAG = String.join("\n",
      "#version 330",
      "uniform sampler2D Mask;",
      "uniform vec2 texel;",
      "out vec4 fragColor;",
      "void main() {",
      "   ivec2 c = ivec2(gl_FragCoord.xy);",
      "   vec2 m0 = texelFetch(Mask, c, 0).rg;",
      "   float sum = 0.0, wsum = 0.0;",
      "   for (int y = -1; y <= 1; y++) {",
      "      for (int x = -1; x <= 1; x++) {",
      "         vec2 m = texelFetch(Mask, c + ivec2(x, y), 0).rg;",
      "         float w = abs(m.y - m0.y) < 0.0015 ? (x == 0 && y == 0 ? 2.0 : 1.0) : 0.0;",
      "         sum += m.x * w;",
      "         wsum += w;",
      "      }",
      "   }",
      "   fragColor = vec4(sum / max(wsum, 1e-4), m0.y, 0.0, 1.0);",
      "}");

   private static final String PASS_FRAG = String.join("\n",
      "#version 420",
      "uniform sampler2D SceneDepth;",
      LIGHT_GLSL,
      "float pplDepthAt(vec2 f) { return texelFetch(SceneDepth, ivec2(f), 0).r; }",
      "out vec4 fragColor;",
      "void main() {",
      "   float d = texelFetch(SceneDepth, ivec2(gl_FragCoord.xy), 0).r;",
      "   vec3 P = pplPos(gl_FragCoord.xy, d);",
      "   float dz = max(abs(dFdx(P.z)), abs(dFdy(P.z)));",
      "   vec3 n = pplLn > 0 && pplOpt.x > 0.5 ? pplNormal(P) : vec3(0.0, 0.0, 1.0);",
      "   if (d >= 1.0) discard;",
      "   vec3 L = pplLight(P, dz, n);",
      "   if (int(pplMapC.w + 0.5) == 2) L = vec3(1.0);",
      "   fragColor = vec4(L, 1.0);",
      "}");

   /** The game's chunkShader.frag (DIFFUSE x vertex colour, depth = chunkDepth + the texture's depth) with the light multiplied in. */
   private static final String CHUNK_FRAG_BODY = (Config.DEV_COMPOSITE_EMPTY_SKIP > 0 ? "#define PZ_EMPTY_SKIP " + Config.DEV_COMPOSITE_EMPTY_SKIP + ".0\n" : "") + String.join("\n",
      "#ifdef PPL_NO_RELIEF", // dev: a variant without relief (cost A/B)
      "#undef PPL_RELIEF",
      "#undef RELIEF_SHADOW",
      "#undef RELIEF_TORCH_SHADOW",
      "#undef RELIEF_HORIZON",
      "#undef RELIEF_VIEW",
      "#endif",
      "uniform sampler2D DIFFUSE;",
      "uniform sampler2D DEPTH;",
      "uniform int useTexture = 1;",
      "uniform float chunkDepth = 0.0;",
      "uniform float pplOn = 0.0;",
      "uniform float pplClear = 0.0;",
      "#ifdef PPL_TINT",
      "uniform vec3 pplTint = vec3(1.0);", // dev (devPplTint): which program drew the chunk texture
      "#endif",
      "in vec4 col;",
      "in vec2 texCoord;",
      "out vec4 fragColor;",
      "#if defined(PPL_TEXEL) && !defined(PPL_BASE)",
      "#define PPL_LAZY_NORMAL",
      "#endif",
      "#if defined(PPL_TEXEL) && (!defined(PPL_BASE) || defined(RELIEF_VIEW))",
      "#define PPL_TEXEL_N", // the texel normal is defined (dev: relief's views need it in the light-free variant too)
      "vec2 pplTt, pplWpt;", // this pixel's position in chunk-texture texels and window px per texel, for pplLazyNormal
      "#endif",
      LIGHT_GLSL,
      Relief.GLSL,
      "#ifdef PPL_TEXEL_N",
      // pplTexelPos: the torch's facing term takes its normal from the chunk-texture texel the pixel shows, not from screen
      // derivatives: those moved with the camera's sub-pixel offset (a continuous window position with a nearest texel's
      // depth), straddled wall and floor edges differently on odd and even frames and flipped the snapped normal, and the
      // torch light blinked on the walls while the player walked (stairs rig, devPplProbe: the native light never did).
      // The texel's centre and its own depth (texelFetch: where pixel centres fall on texel edges the NEAREST sample can be
      // the next texel), its neighbours PPL_NSPAN texels away (pplNormalSpan: one DEPTH16 texel step along a wall is 2-6 LSB,
      // and the rounding flipped the snapped plane texel by texel, a checker), per axis the side with the smaller depth
      // change (never across an edge), a cleared texel left out. Only for pixels a light reaches (pplLight asks once).
      "vec3 pplLazyNormal(vec3 P) {",
      "   ivec2 mx = textureSize(DEPTH, 0) - 1;",
      "   ivec2 ti = clamp(ivec2(floor(pplTt)), ivec2(0), mx);",
      "#if defined(PPL_RELIEF) && defined(RELIEF_AUX)",
      // relief codes (pzopt.ReliefAux): a floor or wall texel's plane and relief in one fetch, no depth reads
      "   if (pzRlfK.w > 0.5) {",
      "      vec3 nc;",
      "      if (rlfCode(ti, smoothstep(0.3, 0.9, min(abs(pplWpt.x), abs(pplWpt.y))), nc)) return nc;",
      "   }",
      "#endif",
      "   vec2 fc = gl_FragCoord.xy + (floor(pplTt) + 0.5 - pplTt) * pplWpt;",
      "   float d0 = texelFetch(DEPTH, ti, 0).r;",
      "   const int K = PPL_NSPAN;",
      "   float xp = texelFetch(DEPTH, min(ti + ivec2(K, 0), mx), 0).r, xm = texelFetch(DEPTH, max(ti - ivec2(K, 0), ivec2(0)), 0).r;",
      "   float yp = texelFetch(DEPTH, min(ti + ivec2(0, K), mx), 0).r, ym = texelFetch(DEPTH, max(ti - ivec2(0, K), ivec2(0)), 0).r;",
      "   if (xp >= 1.0 && xm >= 1.0 || yp >= 1.0 && ym >= 1.0) return vec3(0.0, 0.0, 1.0);",
      "   float sx = xp < 1.0 && (xm >= 1.0 || abs(xp - d0) <= abs(d0 - xm)) ? 1.0 : -1.0;",
      "   float sy = yp < 1.0 && (ym >= 1.0 || abs(yp - d0) <= abs(d0 - ym)) ? 1.0 : -1.0;",
      "   vec3 P0 = pplPos(fc, chunkDepth + d0);",
      "   vec3 px = pplPos(fc + vec2(sx * float(K) * pplWpt.x, 0.0), chunkDepth + (sx > 0.0 ? xp : xm));",
      "   vec3 py = pplPos(fc + vec2(0.0, sy * float(K) * pplWpt.y), chunkDepth + (sy > 0.0 ? yp : ym));",
      "   vec3 L = vec3(1.0, 1.0, PPL_LEVEL);",
      "   vec3 n0 = pplSnapNormal(P0, cross((px - P0) * L, (py - P0) * L));",
      "#if PPL_NSPAN_WIDE > 0",
      // pplNormalSpanWide: a texel whose normal did not snap to a floor or wall plane tries neighbours PPL_NSPAN_WIDE texels
      // away and keeps that normal only when it does snap. Over the short span the DEPTH16 rounding along a wall still tilted
      // single texels past the snap on a regular lattice, each lit differently: a dark dot mesh on walls in daylight (Discord
      // 2026-10-04). The wide span alone crossed leaf and furniture edges (black blobs on a potted plant): an object's own
      // non-planar normal stays as it was
      // (only a texel whose short-span neighbours lie on one plane: a leaf or a small object's edge is not a wall)
      "   bool shortPlanar = xp < 1.0 && xm < 1.0 && abs(xp + xm - 2.0 * d0) < 4.0 / 65535.0 + 0.25 * abs(xp - xm)",
      "      && yp < 1.0 && ym < 1.0 && abs(yp + ym - 2.0 * d0) < 4.0 / 65535.0 + 0.25 * abs(yp - ym);",
      "   if (shortPlanar && n0.x < 0.9999 && n0.y < 0.9999 && n0.z < 0.9999) {",
      "      const int W = PPL_NSPAN_WIDE;",
      "      float wxp = texelFetch(DEPTH, min(ti + ivec2(W, 0), mx), 0).r, wxm = texelFetch(DEPTH, max(ti - ivec2(W, 0), ivec2(0)), 0).r;",
      "      float wyp = texelFetch(DEPTH, min(ti + ivec2(0, W), mx), 0).r, wym = texelFetch(DEPTH, max(ti - ivec2(0, W), ivec2(0)), 0).r;",
      "      if (!(wxp >= 1.0 && wxm >= 1.0 || wyp >= 1.0 && wym >= 1.0)) {",
      "         float wsx = wxp < 1.0 && (wxm >= 1.0 || abs(wxp - d0) <= abs(d0 - wxm)) ? 1.0 : -1.0;",
      "         float wsy = wyp < 1.0 && (wym >= 1.0 || abs(wyp - d0) <= abs(d0 - wym)) ? 1.0 : -1.0;",
      "         vec3 qx = pplPos(fc + vec2(wsx * float(W) * pplWpt.x, 0.0), chunkDepth + (wsx > 0.0 ? wxp : wxm));",
      "         vec3 qy = pplPos(fc + vec2(0.0, wsy * float(W) * pplWpt.y), chunkDepth + (wsy > 0.0 ? wyp : wym));",
      "         vec3 nw = pplSnapNormal(P0, cross((qx - P0) * L, (qy - P0) * L));",
      // and only a plane the short span already leaned towards (within ~37 degrees) that is a real wall or floor: a wall
      // plane within a fifth of a square of the edge of a square that has a wall there (the visible faces measured 0.01-0.18
      // in), a floor at a level's height. On leaves before a wall the wide span reached past the leaf onto the wall and
      // gave the leaf the wall's plane (dark specks along the leaf edges)
      "         vec2 wsq = floor(P0.xy + 0.004);",
      "         int wl = int(clamp(floor(P0.z + 0.006), float(pplLv.x), float(pplLv.y))) & pplOrg.w;",
      "         int wb = int(texelFetch(pplConn, ivec3((ivec2(wsq) + pplOrg.xy) & pplOrg.z, wl), 0).g * 255.0 + 0.5);",
      "         bool real = nw.x > 0.9999 && (wb & 4) != 0 && P0.x - wsq.x < 0.2 || nw.y > 0.9999 && (wb & 8) != 0 && P0.y - wsq.y < 0.2",
      "            || nw.z > 0.9999 && abs(P0.z - floor(P0.z + 0.5)) < 0.02;",
      "         if (real && dot(nw, n0) > 0.8) n0 = nw;",
      "      }",
      "   }",
      "#endif",
      "#ifdef PPL_RELIEF",
      // relief (pzopt.Relief): the art's height across the texel's plane; a side whose depth leaves the plane (the second
      // difference over the span beyond a few DEPTH16 steps) is left out of the height's difference
      "   bool planar = xp < 1.0 && xm < 1.0 && abs(xp + xm - 2.0 * d0) < 4.0 / 65535.0 + 0.25 * abs(xp - xm)",
      "      && yp < 1.0 && ym < 1.0 && abs(yp + ym - 2.0 * d0) < 4.0 / 65535.0 + 0.25 * abs(yp - ym);",
      "   float fade = smoothstep(0.3, 0.9, min(abs(pplWpt.x), abs(pplWpt.y)));", // minified: the texels' detail is below a pixel
      "#ifdef RELIEF_AUX",
      "   rlfN0 = n0;",
      "   return n0;", // no code here (an object, an edge): no relief
      "#else",
      "   return rlfNormal(n0, ti, vec2(mx + 1), pplMapA.x * pplWpt.x, pplMapA.z * pplWpt.y, planar, fade);",
      "#endif",
      "#else",
      "   return n0;",
      "#endif",
      "}",
      "#endif",
      "void main() {",
      "#ifdef PZ_EMPTY_SKIP", // dev (devCompositeEmptySkip): how much of the composite's GPU time empty texels cost (a coarse mip level fully transparent: discarded first; approximate, faint texels too)
      "   if (textureLod(DIFFUSE, texCoord.st, PZ_EMPTY_SKIP).a <= 0.0) discard;",
      "#endif",
      "   vec4 c = vec4(1.0, 1.0, 1.0, 1.0);",
      "   if (useTexture == 1) c = texture(DIFFUSE, texCoord.st);",
      "   float dt = texture(DEPTH, texCoord.st).r;",
      "#ifdef PPL_TEXEL_Z",
      // pplTexelHeight: the height (hence the level whose light the pixel takes) from the texel the pixel shows: its centre's
      // window y and its own depth. The pixel centre paired with the nearest texel's depth put a point up to half a texel off
      // the surface; the upscaler's jitter moves the pixel centre inside the texel every frame, and the tile-edge rows of a flat
      // roof (written up to 0.005 levels low, the tolerance is 0.006) flipped to the level below: Spiffo's roof flickered with DLSS.
      // The chunk quad is axis-aligned: window y maps linearly to texel y (derivatives taken before any discard)
      "   float zty = texCoord.t * float(textureSize(DEPTH, 0).y);",
      "   float zwy = dFdy(zty);",
      "   vec2 zfc = vec2(gl_FragCoord.x, gl_FragCoord.y + (abs(zwy) > 1e-6 ? (floor(zty) + 0.5 - zty) / zwy : 0.0));",
      "   float zdt = texelFetch(DEPTH, clamp(ivec2(floor(texCoord.st * vec2(textureSize(DEPTH, 0)))), ivec2(0), textureSize(DEPTH, 0) - 1), 0).r;",
      "#endif",
      // an empty texel (no colour, cleared depth) blends nothing and writes the far plane: skipped (no blend, no depth write)
      "   if (pplClear > 0.5 && c.a <= 0.0 && dt >= 1.0) discard;",
      "   float d = chunkDepth + dt;",
      "   gl_FragDepth = d;",
      "   if (pplOn > 0.5 && (int(pplOpt2.w + 0.5) & 16) == 0) {",
      "#ifdef PPL_TEXEL_Z",
      "      vec3 P = pplPos(zfc, chunkDepth + zdt);",
      // a floor within two DEPTH16 steps of a level (0.0013 levels a step) is on that level: snapped, so the tolerance's lift and its brighter-of-two-levels rule (meant for the top
      // row of a wall of the level below) never take it (a flat roof over a lit room read the room's light: bright triangles
      // on Spiffo's roof). Flat = the texels K rows above and below reconstruct within 35 % of a wall's rise over K texels
      "#ifdef PPL_FLOOR_SNAP",
      "      float zr = floor(P.z + 0.5);",
      "      if (abs(P.z - zr) < 0.0026 && P.z != zr) {", // two DEPTH16 steps: a wall top's flat trim sits further below (it went dark at 0.006: the level above's light)
      "         ivec2 zts = textureSize(DEPTH, 0);",
      "         ivec2 zti = clamp(ivec2(floor(texCoord.st * vec2(zts))), ivec2(0), zts - 1);",
      "         const int ZK = 2;",
      "         float zwpt = abs(zwy) > 1e-6 ? 1.0 / zwy : 0.0;", // window px per texel along y (signed)
      "         float zlim = max(0.35 * float(ZK) * abs(pplMapA.z * zwpt) / 6.0, 0.0026);", // a wall rises |kB| / 6 levels a window px; floors 2 DEPTH16 steps
      "         float za = texelFetch(DEPTH, ivec2(zti.x, min(zti.y + ZK, zts.y - 1)), 0).r, zb = texelFetch(DEPTH, ivec2(zti.x, max(zti.y - ZK, 0)), 0).r;",
      "         bool zflat = za < 1.0 || zb < 1.0;", // a cleared texel is no evidence either way
      "         if (za < 1.0) zflat = abs(pplPos(zfc + vec2(0.0, float(ZK) * zwpt), chunkDepth + za).z - P.z) < zlim;",
      "         if (zflat && zb < 1.0) zflat = abs(pplPos(zfc - vec2(0.0, float(ZK) * zwpt), chunkDepth + zb).z - P.z) < zlim;",
      "         if (zflat) P.z = zr;",
      "      }",
      "#endif",
      "#else",
      "      vec3 P = pplPos(gl_FragCoord.xy, d);",
      "#endif",
      "      float dz = 0.0;",
      "#ifdef PPL_BASE",
      "      vec3 n = vec3(0.0, 0.0, 1.0);",
      "#elif defined(PPL_TEXEL)",
      "      vec3 n = vec3(0.0, 0.0, 1.0);",
      "      if (pplLn > 0 && pplOpt.x > 0.5 && (int(pplOpt2.w + 0.5) & 4) == 0) {", // uniform condition: the derivatives stay valid
      "         if ((int(pplOpt2.w + 0.5) & 16384) == 0) {", // dev: cost bit 16384, the screen-derivative normal again (devPplAlternate A/B)
      "            pplTt = texCoord.st * vec2(textureSize(DEPTH, 0));",
      "            vec2 tdx = dFdx(pplTt), tdy = dFdy(pplTt);",
      "            pplWpt = vec2(abs(tdx.x) > 1e-6 ? 1.0 / tdx.x : 0.0, abs(tdy.y) > 1e-6 ? 1.0 / tdy.y : 0.0);", // the chunk quad is axis-aligned
      "            pplNeedN = true;",
      "         } else {",
      "            n = pplNormal(P);",
      "         }",
      "      }",
      "#else",
      "      vec3 n = pplLn > 0 && pplOpt.x > 0.5 && (int(pplOpt2.w + 0.5) & 4) == 0 ? pplNormal(P) : vec3(0.0, 0.0, 1.0);", // uniform condition: the derivatives stay valid
      "#endif",
      "#ifdef PPL_BASE",
      "      if (c.a > 0.0) c.rgb *= pplLight(P, dz, n);", // premultiplied
      "#elif defined(PPL_DEV)",
      "      int view = int(pplMapC.w + 0.5);",
      "      if (c.a > 0.0 && view != 2) {",
      "         vec3 L = pplLight(P, dz, n);",
      "         c.rgb = view == 16 ? L : view == 1 || view == 3 || view == 13 || view == 14 || view == 15 ? L * c.a : c.rgb * L + pplSpec * c.a;", // premultiplied; the glints on top
      "      }",
      "#else",
      "      if (c.a > 0.0) c.rgb = c.rgb * pplLight(P, dz, n) + pplSpec * c.a;", // premultiplied; the glints on top
      "#endif",
      "   }",
      "   fragColor = c * col;",
      "#ifdef RELIEF_VIEW",
      // dev (devReliefView): 1 the surface lit by a low light from the west (relief over flat), 2 the relief normal, 3 that
      // relief factor alone (grey, 0.5 = flat), 4 the self-shadow, 5 the height
      "   pplTt = texCoord.st * vec2(textureSize(DEPTH, 0));",
      "   vec2 rvx = dFdx(pplTt), rvy = dFdy(pplTt);",
      "   pplWpt = vec2(abs(rvx.x) > 1e-6 ? 1.0 / rvx.x : 0.0, abs(rvy.y) > 1e-6 ? 1.0 / rvy.y : 0.0);",
      "   if (c.a > 0.0) {",
      "      rlfN0 = vec3(0.0, 0.0, 1.0);",
      "      vec3 rvn = pplLazyNormal(pplPos(gl_FragCoord.xy, d));",
      "      vec3 rls = normalize(vec3(-0.8, 0.25, 0.45));",
      "      float rsh = clamp(max(dot(rvn, rls), 0.0) / max(dot(rlfN0, rls), 0.2), 0.0, 2.0);",
      "#ifdef RELIEF_SHADOW",
      "      rsh *= rlfShadow(rls);",
      "      if (RELIEF_VIEW == 4) fragColor.rgb = vec3(rlfShadow(rls)) * fragColor.a;",
      "#endif",
      "      if (RELIEF_VIEW == 1) fragColor.rgb *= rsh;",
      "      if (RELIEF_VIEW == 2) fragColor.rgb = (rvn * 0.5 + 0.5) * fragColor.a;",
      "      if (RELIEF_VIEW == 3) fragColor.rgb = vec3(clamp(0.5 * rsh, 0.0, 1.0)) * fragColor.a;",
      "      if (RELIEF_VIEW == 5) fragColor.rgb = vec3(rlfH0) * fragColor.a;",
      "   }",
      "#endif",
      "#ifdef PPL_TINT",
      "   fragColor.rgb *= pplTint;",
      "#endif",
      "}");

   /** The game's chunkShader.frag (DIFFUSE x vertex colour, depth = chunkDepth + the texture's depth) with the light multiplied in. */
   private static final String TINT = (Config.DEV_PPL_TINT ? "#define PPL_TINT\n" : "") + (Config.PPL_TEXEL_POS ? "#define PPL_TEXEL\n#define PPL_NSPAN " + Config.PPL_NORMAL_SPAN + "\n#define PPL_NSPAN_WIDE " + Config.PPL_NORMAL_SPAN_WIDE + "\n" : "")
      + (Config.PPL_TEXEL_HEIGHT ? "#define PPL_TEXEL_Z\n" + (Config.PPL_FLOOR_SNAP ? "#define PPL_FLOOR_SNAP\n" : "") : "") + (Config.PPL_SEEN_EDGE ? "#define PPL_SEEN_EDGE\n" : "") + (Config.PPL_UNSEEN_AMBIENT ? "#define PPL_UNSEEN_AMBIENT\n" : "") + (Config.PPL_WALL_EDGE ? "#define PPL_WALL_EDGE\n" : "") + (Config.TORCH_SOURCE_SELF_SHADOW ? "#define PPL_SELF_SHADOW\n" : "") + Relief.defines(); // the defines every chunk program gets
   private static final String CHUNK_FRAG = "#version 420\n" + (Config.DEV_PPL_VIEW != 0 ? "#define PPL_DEV\n" : "") + TINT + CHUNK_FRAG_BODY; // dev views compiled in only when asked: they keep values alive to the end (registers)
   /** The same without the dynamic lights (chunk textures no light reaches): 32 registers, full occupancy on the 890M (64 with). */
   private static final String CHUNK_BASE_FRAG = "#version 420\n#define PPL_BASE\n" + CHUNK_FRAG_BODY;
   /** A plain pass-through for the base variant's program (position through ModelViewProjection, texture coordinate, colour). */
   private static final String CHUNK_BASE_VERT = String.join("\n",
      "#version 330",
      "layout (location = 0) in vec2 vPos;",
      "layout (location = 1) in vec2 vUV;",
      "layout (location = 2) in vec4 vCol;",
      "uniform mat4 ModelViewProjection;",
      "out vec4 col;",
      "out vec2 texCoord;",
      "void main() {",
      "   gl_Position = ModelViewProjection * vec4(vPos, 0.0, 1.0);",
      "   texCoord = vUV;",
      "   col = vCol;",
      "}");

   // ------------------------------------------------------------------------------------------------ dev dump

   private static String pendingLitDump; // the scene again after this frame's pass: <tag>-lit
   private static long worldUpNs;
   private static int dumpAtNext;
   private static float[] dumpAt;

   private static void scheduledDumps(int playerIndex) {
      if (dumpAt == null) {
         String[] parts = Config.DEV_PPL_DUMP_AT.split(",");
         dumpAt = new float[parts.length];
         for (int i = 0; i < parts.length; i++) {
            dumpAt[i] = Float.parseFloat(parts[i].trim());
         }
      }
      if (dumpAtNext >= dumpAt.length || IsoWorld.instance == null || IsoWorld.instance.currentCell == null) {
         return;
      }
      long now = System.nanoTime();
      if (worldUpNs == 0L) {
         worldUpNs = now;
      }
      if ((now - worldUpNs) / 1e9 >= dumpAt[dumpAtNext]) {
         String tag = "t" + (int)dumpAt[dumpAtNext];
         dumpAtNext++;
         try {
            dumpSquares(tag, playerIndex);
            Dump d = new Dump();
            d.tag = tag;
            SpriteRenderer.instance.drawGeneric(d);
            pendingLitDump = tag;
         } catch (Throwable t) {
            Log.warn("pixel light: dump " + tag + " failed: " + t);
         }
      }
   }

   static File dir() {
      File d = new File(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt-ppl");
      d.mkdirs();
      return d;
   }

   private static void dumpSquares(String tag, int playerIndex) throws java.io.IOException {
      IsoCell cell = IsoWorld.instance.currentCell;
      IsoPlayer player = IsoPlayer.players[playerIndex];
      StringBuilder sb = new StringBuilder(1 << 20);
      float zoom = Core.getInstance().getZoom(playerIndex);
      int camX = (int)Math.floor(IsoCamera.frameState.camCharacterX), camY = (int)Math.floor(IsoCamera.frameState.camCharacterY);
      sb.append("# camera\n");
      sb.append("zoom ").append(zoom).append('\n');
      sb.append("tileScale ").append(Core.tileScale).append('\n');
      sb.append("offX ").append(IsoCamera.getOffX()).append('\n');
      sb.append("offY ").append(IsoCamera.getOffY()).append('\n');
      sb.append("screen ").append(IsoCamera.getScreenLeft(playerIndex)).append(' ').append(IsoCamera.getScreenTop(playerIndex)).append(' ')
         .append(IsoCamera.getScreenWidth(playerIndex)).append(' ').append(IsoCamera.getScreenHeight(playerIndex)).append('\n');
      sb.append("core ").append(Core.width).append(' ').append(Core.height).append('\n');
      sb.append("camChar ").append(IsoCamera.frameState.camCharacterX).append(' ').append(IsoCamera.frameState.camCharacterY).append(' ')
         .append(IsoCamera.frameState.camCharacterZ).append('\n');
      if (player != null) {
         sb.append("player ").append(player.getX()).append(' ').append(player.getY()).append(' ').append(player.getZ()).append('\n');
      }
      sb.append("pixelLight ").append(ACTIVE ? 1 : 0).append('\n');
      sb.append("hour ").append(zombie.GameTime.getInstance().getTimeOfDay()).append('\n');
      sb.append("night ").append(zombie.GameTime.getInstance().getNight()).append('\n');
      sb.append("daylight ").append(zombie.iso.weather.ClimateManager.getInstance().getDayLightStrength()).append('\n');
      for (int i = 0; i < 4; i++) {
         float x = camX + (i == 1 ? 5 : 0), y = camY + (i == 2 ? 5 : 0), z = i == 3 ? 1 : 0;
         sb.append("depthAt ").append(x).append(' ').append(y).append(' ').append(z).append(' ')
            .append(IsoDepthHelper.getSquareDepthData(camX, camY, x, y, z).depthStart).append('\n');
      }
      sb.append("# lights: id x y z radius r g b active building\n");
      for (IsoLightSource l : cell.getLamppostPositions()) {
         sb.append("light ").append(l.id).append(' ').append(l.x).append(' ').append(l.y).append(' ').append(l.z).append(' ').append(l.radius).append(' ')
            .append(l.r).append(' ').append(l.g).append(' ').append(l.b).append(' ').append(l.active).append(' ')
            .append(l.localToBuilding == null ? -1 : l.localToBuilding.id).append('\n');
      }
      sb.append("# rooms: id x y z w h active\n");
      for (IsoRoomLight l : cell.roomLights) {
         sb.append("room ").append(l.id).append(' ').append(l.x).append(' ').append(l.y).append(' ').append(l.z).append(' ').append(l.width).append(' ')
            .append(l.height).append(' ').append(l.active).append('\n');
      }
      sb.append("# torches: id x y z r g b angleX angleY dist strength cone dot focusing\n");
      ArrayList<IsoGameCharacter.TorchInfo> torches = LightingJNI.pzoptTorches();
      for (IsoGameCharacter.TorchInfo t : torches) {
         sb.append("torch ").append(t.id).append(' ').append(t.x).append(' ').append(t.y).append(' ').append(t.z).append(' ').append(t.r).append(' ')
            .append(t.g).append(' ').append(t.b).append(' ').append(t.angleX).append(' ').append(t.angleY).append(' ').append(t.dist).append(' ')
            .append(t.strength).append(' ').append(t.cone).append(' ').append(t.dot).append(' ').append(t.focusing).append('\n');
      }
      sb.append("# squares: x y z | vis lr lg lb dark targetDark lightLevel tick v0..v7 n [id,x,y,z,radius,r,g,b,flags]... | objects\n");
      int px = player != null ? (int)Math.floor(player.getX()) : camX, py = player != null ? (int)Math.floor(player.getY()) : camY;
      int r = 48;
      int count = 0;
      for (int cy = Math.floorDiv(py - r, 8); cy <= Math.floorDiv(py + r, 8); cy++) {
         for (int cx = Math.floorDiv(px - r, 8); cx <= Math.floorDiv(px + r, 8); cx++) {
            IsoChunk chunk = cell.getChunk(cx, cy);
            if (chunk == null) {
               continue;
            }
            for (int z = chunk.minLevel; z <= chunk.maxLevel; z++) {
               for (int y = 0; y < 8; y++) {
                  for (int x = 0; x < 8; x++) {
                     IsoGridSquare sq = chunk.getGridSquare(x, y, z);
                     if (sq == null || !(sq.lighting[playerIndex] instanceof LightingJNI.JNILighting jl)) {
                        continue;
                     }
                     sb.append("sq ").append(sq.x).append(' ').append(sq.y).append(' ').append(sq.z).append(" | ");
                     jl.pzoptDump(sb);
                     sb.append(" |");
                     for (int o = 0; o < sq.getObjects().size(); o++) {
                        zombie.iso.IsoObject obj = sq.getObjects().get(o);
                        sb.append(' ').append(obj.sprite != null && obj.sprite.name != null ? obj.sprite.name : obj.getClass().getSimpleName());
                        sb.append('@').append(obj.getAlpha(playerIndex));
                     }
                     sb.append(" pcf=").append(sq.getPlayerCutawayFlag(playerIndex, 0L)); // the cutaway flags (pplCutEdge)
                     sb.append('\n');
                     count++;
                  }
               }
            }
         }
      }
      Files.writeString(new File(dir(), tag + "-squares.txt").toPath(), sb.toString());
      Log.info("pixel light: dump " + tag + ": " + count + " squares, " + cell.getLamppostPositions().size() + " lights, " + cell.roomLights.size() + " room lights, "
         + torches.size() + " torches");
   }

   /** Render thread, in stream order right after the chunk composite (and this frame's pass): the scene depth + colour. */
   static final class Dump extends TextureDraw.GenericDrawer {
      String tag;

      @Override
      public void render() {
         try {
            int[] vp = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
            int vx = vp[0], vy = vp[1], vw = vp[2], vh = vp[3];
            int sceneFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sceneFbo);
            FloatBuffer depth = BufferUtils.createFloatBuffer(vw * vh);
            GL11.glReadPixels(vx, vy, vw, vh, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
            ByteBuffer color = BufferUtils.createByteBuffer(vw * vh * 4);
            GL11.glReadPixels(vx, vy, vw, vh, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, color);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            ByteBuffer db = ByteBuffer.allocate(vw * vh * 4).order(ByteOrder.LITTLE_ENDIAN);
            db.asFloatBuffer().put(depth);
            Files.write(new File(dir(), this.tag + "-depth.bin").toPath(), db.array());
            byte[] cb = new byte[vw * vh * 4];
            color.get(cb);
            Files.write(new File(dir(), this.tag + "-color.bin").toPath(), cb);
            Core core = Core.getInstance();
            StringBuilder sb = new StringBuilder();
            sb.append("w=").append(vw).append("\nh=").append(vh).append("\nvx=").append(vx).append("\nvy=").append(vy).append("\nfbo=").append(sceneFbo).append('\n');
            if (!core.projectionMatrixStack.isEmpty() && !core.modelViewMatrixStack.isEmpty()) {
               org.joml.Matrix4f p = core.projectionMatrixStack.peek(), m = core.modelViewMatrixStack.peek();
               sb.append("proj=").append(p.m00()).append(',').append(p.m11()).append(',').append(p.m22()).append(',').append(p.m30()).append(',').append(p.m31()).append(',').append(p.m32()).append('\n');
               sb.append("mv=").append(m.m00()).append(',').append(m.m11()).append(',').append(m.m30()).append(',').append(m.m31()).append('\n');
            }
            if (GL.info != 0 && GL.latticeN > 0) {
               // the per-square arrays as the shader reads them: texel (x mod n, y mod n), layer z mod 16
               int n = GL.latticeN;
               for (int a = 0; a < 2; a++) {
                  ByteBuffer ib = BufferUtils.createByteBuffer(n * n * LEVELS * 4);
                  GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, a == 0 ? GL.info : GL.vis);
                  GL11.glGetTexImage(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, ib);
                  byte[] ia = new byte[ib.capacity()];
                  ib.get(ia);
                  Files.write(new File(dir(), this.tag + (a == 0 ? "-info.bin" : "-conn.bin")).toPath(), ia);
               }
               GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
               sb.append("latticeN=").append(n).append("\nlevels=").append(LEVELS).append('\n');
            }
            if (GL.maskValid && GL.maskW > 0) {
               // the torch shadow mask (after the lit composite: this frame's), march result then blurred, RG float each
               ByteBuffer mb = ByteBuffer.allocateDirect(GL.maskW * GL.maskH * 8 * 2).order(ByteOrder.LITTLE_ENDIAN);
               for (int i = 0; i < 2; i++) {
                  GL11.glBindTexture(GL11.GL_TEXTURE_2D, GL.maskTex[i]);
                  mb.position(i * GL.maskW * GL.maskH * 8);
                  GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG, GL11.GL_FLOAT, mb);
               }
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
               byte[] m = new byte[mb.capacity()];
               mb.position(0);
               mb.get(m);
               Files.write(new File(dir(), this.tag + "-mask.bin").toPath(), m);
               sb.append("maskW=").append(GL.maskW).append("\nmaskH=").append(GL.maskH).append('\n');
            }
            Files.writeString(new File(dir(), this.tag + "-view.txt").toPath(), sb.toString());
            Log.info("pixel light: dump " + this.tag + ": scene " + vw + "x" + vh + " at " + vx + "," + vy);
         } catch (Throwable t) {
            Log.warn("pixel light: scene dump failed: " + t);
         }
      }
   }
}
