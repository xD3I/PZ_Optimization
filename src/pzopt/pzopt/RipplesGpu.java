package pzopt;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import zombie.core.ShaderHelper;

/**
 * The compute side of pzopt.Ripples (GL 4.3; macOS's 4.1 core keeps the fragment modes):
 * <ul>
 *   <li>{@code field}: the tiles with rings get a slot each in an atlas (64 x 64 texels for a 128 px tile: half the view's
 *       resolution); one dispatch evaluates every slot's rings per texel; the puddle pixel reads one bilinear texel;</li>
 *   <li>{@code sim}: a world-anchored height field, 16 texels a square, toroidal over 128 squares (2048^2, two RG16F
 *       textures ping-ponged: height now and a step ago), stepped by iWave (pzopt.RipplesModel: the 13 x 13 dispersive
 *       kernel through shared memory) at {@code SIM_HZ} on the 2 x 2 square tiles a footstep woke in the last
 *       {@code SIM_HOLD} s (each footstep a Gaussian depression), damped; the
 *       squares' own puddle level (IsoGridSquare.getPuddlesInGround, a 128^2 R8 mask) holds the water's edge at rest, so
 *       the rings reflect off the puddle's shore. Tiles that fall asleep are zeroed once, then cost nothing.</li>
 * </ul>
 */
final class RipplesGpu {
   private RipplesGpu() {
   }

   // field atlas
   static final int SLOTS = 512, SLOT_TEXELS = 64, ATLAS_SLOTS_X = 32, ATLAS_W = ATLAS_SLOTS_X * SLOT_TEXELS, ATLAS_H = (SLOTS / ATLAS_SLOTS_X) * SLOT_TEXELS;
   // simulation
   static final int SIM_RES = RipplesModel.RES, SIM_N = 2048, SIM_SPAN = SIM_N / SIM_RES, SIM_TILE = 32, SIM_TILES = SIM_N / SIM_TILE;
   static final float SIM_HZ = RipplesModel.HZ, SIM_HOLD = 2.0F;
   static final int MAX_STEPS = 4, MAX_IMPULSES = 256;

   private static volatile boolean supported = !CoreGl.legacyMac();
   static long fieldDispatches, simSteps, simTileSteps, simClears, maxActive;

   static boolean supported() {
      return supported;
   }

   /** OpenGL 4.3 (compute) on the render thread's context: -1 until the first puddle draw has looked (game-thread safe). */
   static volatile int gl43 = -1;

   /** Render thread: remember whether the context has compute shaders (GL.getCapabilities throws on the game thread). */
   static void noteCaps() {
      if (gl43 < 0) {
         gl43 = GL.getCapabilities().OpenGL43 ? 1 : 0;
      }
   }

   static String stats() {
      return "gpu: field_dispatches=" + fieldDispatches + " sim_steps=" + simSteps + " sim_tile_steps=" + simTileSteps + " sim_clears=" + simClears
            + " sim_max_active=" + maxActive + (supported ? "" : " (no compute)");
   }

   private static String wantedFor;
   private static boolean wanted;

   static boolean simWanted() {
      String m = Config.RIPPLES_MODE;
      if (m != wantedFor) { // the live key changed (a new string): look again
         wantedFor = m;
         wanted = "sim".equals(m) || Config.DEV_RIPPLES_ALTERNATE > 0 && Config.DEV_RIPPLES_MODES.contains("sim");
      }
      return wanted || "sim".equals(Config.RIPPLES_WAKE) || Config.DEV_RIPPLES_ALTERNATE > 0 && Config.DEV_WAKE_MODES.contains("sim"); // the wakes in the sim
   }

   static float simHeightScale() {
      return SIM_RES; // a texel's height step -> slope a square
   }

   // ------------------------------------------------------------------------------------------------ sim, game thread

   private static final float[] impX = new float[MAX_IMPULSES], impY = new float[MAX_IMPULSES], impA = new float[MAX_IMPULSES];
   private static final boolean[] impBig = new boolean[MAX_IMPULSES];
   private static int nImp;
   private static float simAcc;
   private static int pendingSteps;
   private static final float[] tileExpiry = new float[SIM_TILES * SIM_TILES];
   private static final int[] tileWX = new int[SIM_TILES * SIM_TILES], tileWY = new int[SIM_TILES * SIM_TILES]; // true world square of each active tile's corner
   private static final int[] active = new int[SIM_TILES * SIM_TILES];
   private static int nActive;
   private static final byte[] mask = new byte[SIM_SPAN * SIM_SPAN];
   private static boolean maskDirty = true;

   static final class SimFrame {
      int steps;
      final float[] imp = new float[MAX_IMPULSES * 4]; // texel x, y (toroidal), depth, radius (texels)
      int nImp;
      final int[] tiles = new int[SIM_TILES * SIM_TILES * 2];
      int nTiles;
      final int[] clears = new int[SIM_TILES * SIM_TILES * 2];
      int nClears;
      final byte[] mask = new byte[SIM_SPAN * SIM_SPAN];
      boolean maskNew;
      boolean any;
      float frac; // how far into the next step the frame is (the shader blends the last two steps)
   }

   /** Game thread, Ripples.emit: a footstep or a wheel: an impulse for the next step, its tiles awake. */
   static void impulse(float x, float y, int z, float amp, boolean big) {
      if (z != 0 || !simWanted() || nImp >= MAX_IMPULSES) {
         return;
      }
      impX[nImp] = x;
      impY[nImp] = y;
      impA[nImp] = amp;
      impBig[nImp] = big;
      nImp++;
      float until = (float)Ripples.clock + SIM_HOLD * (big ? 1.5F : 1F);
      int r = big ? 2 : 1;
      int tx = Math.floorMod((int)Math.floor(x * SIM_RES / SIM_TILE), SIM_TILES), ty = Math.floorMod((int)Math.floor(y * SIM_RES / SIM_TILE), SIM_TILES);
      int sqPerTile = SIM_TILE / SIM_RES;
      int baseX = (int)Math.floor(x) - Math.floorMod((int)Math.floor(x), sqPerTile), baseY = (int)Math.floor(y) - Math.floorMod((int)Math.floor(y), sqPerTile);
      for (int j = -r; j <= r; j++) {
         for (int i = -r; i <= r; i++) {
            int t = Math.floorMod(ty + j, SIM_TILES) * SIM_TILES + Math.floorMod(tx + i, SIM_TILES);
            if (tileExpiry[t] <= Ripples.clock) {
               active[nActive++] = t;
               tileWX[t] = baseX + i * sqPerTile;
               tileWY[t] = baseY + j * sqPerTile;
               // the shore: the puddle level of the tile's squares (a tile wakes with its squares' state)
               for (int sy = 0; sy < sqPerTile; sy++) {
                  for (int sx = 0; sx < sqPerTile; sx++) {
                     int wx = baseX + i * sqPerTile + sx, wy = baseY + j * sqPerTile + sy;
                     zombie.iso.IsoGridSquare sq = zombie.iso.IsoWorld.instance.getCell().getGridSquare(wx, wy, 0);
                     float lv = sq == null ? -1F : sq.getPuddlesInGround();
                     byte v = (byte)Math.max(0, Math.min(255, Math.round(lv / 0.3F * 255F)));
                     int mi = Math.floorMod(wy, SIM_SPAN) * SIM_SPAN + Math.floorMod(wx, SIM_SPAN);
                     if (mask[mi] != v) {
                        mask[mi] = v;
                        maskDirty = true;
                     }
                  }
               }
            }
            tileExpiry[t] = Math.max(tileExpiry[t], until);
         }
      }
   }

   /** Game thread, once a frame: the steps this frame's game time is worth. */
   static void advance(float dt) {
      if (!simWanted()) {
         return;
      }
      simAcc += dt * SIM_HZ;
      int s = (int)simAcc;
      simAcc -= s;
      pendingSteps += s;
      if (pendingSteps > MAX_STEPS) {
         pendingSteps = MAX_STEPS; // a long frame: the waves slow down rather than the frame
      }
   }

   /** Game thread, Ripples.build in sim mode: the frame's steps, impulses and tiles. Returns whether the field is awake. */
   static boolean buildSim(Ripples.Frame fr, int playerIndex) {
      SimFrame s = fr.sim;
      s.nTiles = 0;
      s.nClears = 0;
      s.nImp = 0;
      s.steps = pendingSteps;
      pendingSteps = 0;
      s.frac = simAcc;
      float now = (float)Ripples.clock;
      for (int i = 0; i < nActive; ) {
         int t = active[i];
         if (tileExpiry[t] <= now) {
            s.clears[s.nClears * 2] = t % SIM_TILES;
            s.clears[s.nClears * 2 + 1] = t / SIM_TILES;
            s.nClears++;
            active[i] = active[--nActive];
         } else {
            s.tiles[s.nTiles * 2] = t % SIM_TILES;
            s.tiles[s.nTiles * 2 + 1] = t / SIM_TILES;
            s.nTiles++;
            i++;
         }
      }
      maxActive = Math.max(maxActive, nActive);
      float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
      int sq = SIM_TILE / SIM_RES;
      for (int i = 0; i < nActive; i++) {
         int t = active[i];
         x0 = Math.min(x0, tileWX[t]);
         y0 = Math.min(y0, tileWY[t]);
         x1 = Math.max(x1, tileWX[t] + sq);
         y1 = Math.max(y1, tileWY[t] + sq);
      }
      fr.bx0 = x0;
      fr.by0 = y0;
      fr.bx1 = x1;
      fr.by1 = y1;
      for (int i = 0; i < nImp; i++) {
         float sc = impBig[i] ? Ripples.BIG : 1F;
         s.imp[i * 4] = (float)Math.floorMod((long)Math.floor(impX[i] * SIM_RES * 256), (long)SIM_N * 256) / 256F;
         s.imp[i * 4 + 1] = (float)Math.floorMod((long)Math.floor(impY[i] * SIM_RES * 256), (long)SIM_N * 256) / 256F;
         s.imp[i * 4 + 2] = RipplesModel.DEPTH * impA[i] * sc;
         s.imp[i * 4 + 3] = RipplesModel.IMPULSE_TEXELS * sc; // texels
      }
      s.nImp = s.steps > 0 ? nImp : 0;
      if (s.steps > 0) {
         nImp = 0;
      }
      s.maskNew = maskDirty;
      if (maskDirty) {
         System.arraycopy(mask, 0, s.mask, 0, mask.length);
         maskDirty = false;
      }
      s.any = s.nTiles > 0;
      return s.any || s.nClears > 0;
   }

   // ------------------------------------------------------------------------------------------------ render thread

   private static int atlasTex, fieldProg, ringSsbo, slotSsbo, uFieldP, uFieldK, uFieldLut, uStepKern;
   private static int[] simTex = new int[2];
   private static int simCur, maskTex, maskPbo, stepProg, clearProg, tileSsbo, impSsbo, uStepInfo, uStepK, uStepMask, uClearInfo;
   private static boolean initTried;
   private static final FloatBuffer RINGS = BufferUtils.createFloatBuffer(Ripples.MAX * 4);
   private static final IntBuffer SLOT_BUF = BufferUtils.createIntBuffer(SLOTS * (3 + Ripples.PER_TILE));
   private static final IntBuffer TILE_BUF = BufferUtils.createIntBuffer(SIM_TILES * SIM_TILES * 2);
   private static final FloatBuffer IMP_BUF = BufferUtils.createFloatBuffer(MAX_IMPULSES * 4);
   private static final ByteBuffer MASK_BUF = BufferUtils.createByteBuffer(SIM_SPAN * SIM_SPAN);

   private static boolean init() {
      if (initTried) {
         return supported;
      }
      initTried = true;
      if (!GL.getCapabilities().OpenGL43) {
         supported = false;
         Log.warn("ripples: no compute shaders (OpenGL 4.3), field / sim run as tiled");
         return false;
      }
      fieldProg = computeProgram(FIELD_CS, "ripples field");
      stepProg = computeProgram(STEP_CS, "ripples sim step");
      clearProg = computeProgram(CLEAR_CS, "ripples sim clear");
      if (fieldProg == 0 || stepProg == 0 || clearProg == 0) {
         supported = false;
         Log.warn("ripples: compute programs failed, field / sim run as tiled");
         return false;
      }
      uFieldP = GL20.glGetUniformLocation(fieldProg, "P");
      uFieldK = GL20.glGetUniformLocation(fieldProg, "pzRipK");
      uFieldLut = GL20.glGetUniformLocation(fieldProg, "lutTex");
      uStepKern = GL20.glGetUniformLocation(stepProg, "kern");
      GL20.glUseProgram(stepProg);
      FloatBuffer kb = BufferUtils.createFloatBuffer(RipplesModel.KERNEL.length);
      kb.put(RipplesModel.KERNEL).flip();
      GL20.glUniform1fv(uStepKern, kb);
      GL20.glUseProgram(0);
      uStepInfo = GL20.glGetUniformLocation(stepProg, "info");
      uStepK = GL20.glGetUniformLocation(stepProg, "K");
      uStepMask = GL20.glGetUniformLocation(stepProg, "shore");
      uClearInfo = GL20.glGetUniformLocation(clearProg, "info");
      ringSsbo = GL15.glGenBuffers();
      slotSsbo = GL15.glGenBuffers();
      tileSsbo = GL15.glGenBuffers();
      impSsbo = GL15.glGenBuffers();
      Log.info("ripples: compute programs ready (field " + ATLAS_W + "x" + ATLAS_H + ", sim " + SIM_N + "^2 at " + SIM_RES + " texels a square)");
      return true;
   }

   static int program(String src, String name) {
      return computeProgram(src, name);
   }

   /** A 2D texture of one level (two-channel float formats: RG). */
   static int texture(int w, int h, int internal, int filter, int wrap) {
      return tex(w, h, internal, GL30.GL_RG, GL11.GL_FLOAT, filter, wrap);
   }

   private static int computeProgram(String src, String name) {
      int sh = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
      GL20.glShaderSource(sh, src);
      GL20.glCompileShader(sh);
      if (GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) == 0) {
         Log.warn("ripples: " + name + " shader: " + GL20.glGetShaderInfoLog(sh, 8192));
         GL20.glDeleteShader(sh);
         return 0;
      }
      int p = GL20.glCreateProgram();
      GL20.glAttachShader(p, sh);
      GL20.glLinkProgram(p);
      GL20.glDeleteShader(sh);
      if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
         Log.warn("ripples: " + name + " link: " + GL20.glGetProgramInfoLog(p, 8192));
         GL20.glDeleteProgram(p);
         return 0;
      }
      return p;
   }

   private static int tex(int w, int h, int internal, int format, int type, int filter, int wrap) {
      int t = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, wrap);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, wrap);
      GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, internal, w, h);
      return t;
   }

   /** Render thread, Ripples.Begin (ahead of the frame's puddle draws): the field or the simulation steps. */
   static void render(Ripples.Frame f) {
      if (f.mode != Ripples.FIELD && f.mode != Ripples.SIM || !init()) {
         return;
      }
      String section = f.mode == Ripples.FIELD ? "ripples.field" : "ripples.sim";
      GpuSections.markNow(section, false);
      try {
         if (f.mode == Ripples.FIELD) {
            field(f);
         } else {
            sim(f);
         }
      } catch (Throwable t) {
         supported = false;
         Log.warn("ripples: compute pass failed, off: " + t);
      } finally {
         GL42.glBindImageTexture(0, 0, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_RG16F);
         GL42.glBindImageTexture(1, 0, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_RG16F);
         GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, 0);
         GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, 0);
         DefaultProgram.restore(); // (frame start, before the cutaway mask: the default program, not 0)
         GpuSections.markNow(section, true);
      }
   }

   private static void field(Ripples.Frame f) {
      if (f.nSlots == 0) {
         return;
      }
      if (atlasTex == 0) {
         atlasTex = tex(ATLAS_W, ATLAS_H, GL30.GL_RG16F, GL30.GL_RG, GL11.GL_FLOAT, GL11.GL_LINEAR, GL12.GL_CLAMP_TO_EDGE);
      }
      RINGS.clear();
      RINGS.put(f.src, 0, f.n * 4).flip();
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ringSsbo);
      GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, RINGS, GL15.GL_STREAM_DRAW);
      SLOT_BUF.clear();
      SLOT_BUF.put(f.slots, 0, f.nSlots * (3 + Ripples.PER_TILE)).flip();
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, slotSsbo);
      GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, SLOT_BUF, GL15.GL_STREAM_DRAW);
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
      Ripples.bindLut();
      GL20.glUseProgram(fieldProg);
      GL20.glUniform4f(uFieldP, 1F / (32F * zombie.core.Core.tileScale), Ripples.TILE_PX, SLOT_TEXELS / (float)Ripples.TILE_PX, 0F);
      GL20.glUniform4f(uFieldK, RipplesModel.reach0, RipplesModel.reachV, 1F / RipplesModel.RMAX, 1F / Ripples.LIFE);
      GL20.glUniform1i(uFieldLut, Ripples.LUT_UNIT);
      GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, ringSsbo);
      GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, slotSsbo);
      GL42.glBindImageTexture(0, atlasTex, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RG16F);
      GL43.glDispatchCompute(SLOT_TEXELS / 8, SLOT_TEXELS / 8, f.nSlots);
      GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
      fieldDispatches++;
   }

   private static void sim(Ripples.Frame f) {
      SimFrame s = f.sim;
      if (simTex[0] == 0) {
         simTex[0] = tex(SIM_N, SIM_N, GL30.GL_RG16F, GL30.GL_RG, GL11.GL_FLOAT, GL11.GL_LINEAR, GL11.GL_REPEAT);
         simTex[1] = tex(SIM_N, SIM_N, GL30.GL_RG16F, GL30.GL_RG, GL11.GL_FLOAT, GL11.GL_LINEAR, GL11.GL_REPEAT);
         GL44Clear.zero(simTex[0]);
         GL44Clear.zero(simTex[1]);
         maskTex = tex(SIM_SPAN, SIM_SPAN, GL30.GL_R8, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, GL11.GL_LINEAR, GL11.GL_REPEAT);
         maskPbo = GL15.glGenBuffers();
      }
      if (s.maskNew) {
         s.maskNew = false;
         MASK_BUF.clear();
         MASK_BUF.put(s.mask).flip();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, maskTex);
         GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, maskPbo);
         GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, MASK_BUF, GL15.GL_STREAM_DRAW);
         GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
         GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SIM_SPAN, SIM_SPAN, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, 0L);
         GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
         GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
      }
      if (s.nClears > 0) {
         TILE_BUF.clear();
         TILE_BUF.put(s.clears, 0, s.nClears * 2).flip();
         GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, tileSsbo);
         GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, TILE_BUF, GL15.GL_STREAM_DRAW);
         GL20.glUseProgram(clearProg);
         GL20.glUniform4i(uClearInfo, 0, SIM_N, SIM_TILE, 0);
         GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, tileSsbo);
         GL42.glBindImageTexture(0, simTex[0], 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RG16F);
         GL42.glBindImageTexture(1, simTex[1], 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RG16F);
         GL43.glDispatchCompute(SIM_TILE / 16, SIM_TILE / 16, s.nClears);
         GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
         simClears += s.nClears;
         s.nClears = 0;
      }
      if (s.nTiles == 0 || s.steps == 0) {
         return;
      }
      TILE_BUF.clear();
      TILE_BUF.put(s.tiles, 0, s.nTiles * 2).flip();
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, tileSsbo);
      GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, TILE_BUF, GL15.GL_STREAM_DRAW);
      IMP_BUF.clear();
      IMP_BUF.put(s.imp, 0, Math.max(1, s.nImp) * 4).flip();
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, impSsbo);
      GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, IMP_BUF, GL15.GL_STREAM_DRAW);
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
      GL20.glUseProgram(stepProg);
      GL20.glUniform4f(uStepK, RipplesModel.A1, RipplesModel.A2, RipplesModel.A3, 0.25F);
      GL20.glUniform1i(uStepMask, Ripples.FIELD_UNIT);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + Ripples.FIELD_UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, maskTex);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, tileSsbo);
      GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, impSsbo);
      for (int k = 0; k < s.steps; k++) {
         GL20.glUniform4i(uStepInfo, k == 0 ? s.nImp : 0, SIM_N, SIM_TILE, 0);
         GL42.glBindImageTexture(0, simTex[simCur], 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_RG16F);
         GL42.glBindImageTexture(1, simTex[simCur ^ 1], 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RG16F);
         GL43.glDispatchCompute(SIM_TILE / 16, SIM_TILE / 16, s.nTiles);
         GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
         simCur ^= 1;
         s.nImp = 0;
         simSteps++;
         simTileSteps += s.nTiles;
      }
   }

   /** Render thread, Ripples.puddleUniforms: the field's atlas or the current height field on the puddle program's unit. */
   static void bindForPuddles(Ripples.Frame f) {
      int t = f.mode == Ripples.FIELD ? atlasTex : simTex[simCur];
      if (t == 0) {
         return;
      }
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + Ripples.FIELD_UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
   }

   /** Zero a whole texture once (GL 4.4 clear when there is one, else a buffer upload). */
   static final class GL44Clear {
      static void zero(int tex) {
         zero(tex, SIM_N);
      }

      static void zero(int tex, int n) {
         if (GL.getCapabilities().OpenGL44) {
            org.lwjgl.opengl.GL44.glClearTexImage(tex, 0, GL30.GL_RG, GL11.GL_FLOAT, (FloatBuffer)null);
         } else {
            int rows = Math.min(64, n);
            FloatBuffer z = BufferUtils.createFloatBuffer(n * 2 * rows);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            for (int y = 0; y < n; y += rows) {
               GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, y, n, rows, GL30.GL_RG, GL11.GL_FLOAT, z);
            }
         }
      }
   }

   // ------------------------------------------------------------------------------------------------ compute shaders

   static final String FIELD_CS = String.join("\n",
         "#version 430",
         "layout(local_size_x = 8, local_size_y = 8) in;",
         "layout(rg16f, binding = 0) writeonly uniform image2D atlas;",
         "layout(std430, binding = 0) readonly buffer Rings { vec4 rings[]; };",
         "layout(std430, binding = 1) readonly buffer Slots { int slots[]; }; // a slot: tile x, y, ring count, " + Ripples.PER_TILE + " ring indices",
         "uniform vec4 P; // x squares a px / 2, y tile px, z texels a px",
         "uniform vec4 pzRipK; // the profile's reach and scales (as the puddle unit's)",
         "uniform sampler2D lutTex;",
         "float pzRipLutAt(vec2 uv) { return textureLod(lutTex, uv, 0.0).r; }",
         Ripples.RING_FN,
         "void main() {",
         "   int s = int(gl_WorkGroupID.z);",
         "   int o = s * " + (3 + Ripples.PER_TILE) + ";",
         "   ivec2 l = ivec2(gl_GlobalInvocationID.xy);",
         "   vec2 p = vec2(slots[o], slots[o + 1]) * P.y + (vec2(l) + 0.5) / P.z;",
         "   int n = slots[o + 2];",
         "   vec2 g = vec2(0.0);",
         "   for (int i = 0; i < n; i++) g += pzRipSlope(p, rings[slots[o + 3 + i]], P.x, true);",
         "   imageStore(atlas, ivec2(s % " + ATLAS_SLOTS_X + ", s / " + ATLAS_SLOTS_X + ") * " + SLOT_TEXELS + " + l, vec4(g, 0.0, 0.0));",
         "}");

   static final int APRON = RipplesModel.P, SH = 16 + 2 * RipplesModel.P; // the shared tile: 16 x 16 outputs + the kernel's reach

   static final String STEP_CS = String.join("\n",
         "#version 430",
         "layout(local_size_x = 16, local_size_y = 16) in;",
         "layout(rg16f, binding = 0) readonly uniform image2D src; // r height now, g a step ago",
         "layout(rg16f, binding = 1) writeonly uniform image2D dst;",
         "layout(std430, binding = 0) readonly buffer Tiles { ivec2 tiles[]; };",
         "layout(std430, binding = 1) readonly buffer Imp { vec4 imp[]; }; // texel x, y, depth, radius",
         "uniform sampler2D shore; // the squares' puddle level (toroidal, one texel a square)",
         "uniform ivec4 info; // x impulses this step, y field size, z tile size",
         "uniform vec4 K; // iWave: x a1, y a2, z a3; w shore threshold",
         "uniform float kern[" + (2 * APRON + 1) * (2 * APRON + 1) + "]; // the vertical-derivative kernel",
         "shared float sh[" + SH * SH + "];",
         "void main() {",
         "   int N = info.y, m = N - 1;",
         "   ivec2 base = tiles[gl_WorkGroupID.z] * info.z + ivec2(gl_WorkGroupID.xy) * 16;",
         "   for (int i = int(gl_LocalInvocationIndex); i < " + SH * SH + "; i += 256) {",
         "      ivec2 o = ivec2(i % " + SH + ", i / " + SH + ") - " + APRON + ";",
         "      sh[i] = imageLoad(src, (base + o) & m).r;",
         "   }",
         "   barrier();",
         "   ivec2 l = ivec2(gl_LocalInvocationID.xy);",
         "   ivec2 c = base + l;",
         "   float conv = 0.0;",
         "   for (int j = 0; j < " + (2 * APRON + 1) + "; j++) {",
         "      int row = (l.y + j) * " + SH + " + l.x;",
         "      for (int i = 0; i < " + (2 * APRON + 1) + "; i++) conv += kern[j * " + (2 * APRON + 1) + " + i] * sh[row + i];",
         "   }",
         "   float h = sh[(l.y + " + APRON + ") * " + SH + " + l.x + " + APRON + "];",
         "   float prev = imageLoad(src, c).g;",
         "   float nh = K.x * h - K.y * prev - K.z * conv;",
         "   float cur = h;",
         "   for (int i = 0; i < info.x; i++) {",
         "      vec4 q = imp[i];",
         "      vec2 d = vec2(c) + 0.5 - q.xy;",
         "      d -= float(N) * floor(d / float(N) + 0.5); // toroidal",
         "      float b = q.z * exp(-dot(d, d) / (q.w * q.w));",
         "      nh += b;",
         "      cur += b; // zero velocity: the footstep is in both steps",
         "   }",
         "   // the shore (Tessendorf's obstruction): water only where the square holds a puddle (bilinear between squares)",
         "   float keep = smoothstep(K.w, K.w + 0.15, texture(shore, (vec2(c) + 0.5) / float(N)).r);",
         "   imageStore(dst, c, vec4(nh * keep, cur * keep, 0.0, 0.0));",
         "}");

   static final String CLEAR_CS = String.join("\n",
         "#version 430",
         "layout(local_size_x = 16, local_size_y = 16) in;",
         "layout(rg16f, binding = 0) writeonly uniform image2D a;",
         "layout(rg16f, binding = 1) writeonly uniform image2D b;",
         "layout(std430, binding = 0) readonly buffer Tiles { ivec2 tiles[]; };",
         "uniform ivec4 info; // z tile size",
         "void main() {",
         "   ivec2 c = tiles[gl_WorkGroupID.z] * info.z + ivec2(gl_GlobalInvocationID.xy);",
         "   imageStore(a, c, vec4(0.0));",
         "   imageStore(b, c, vec4(0.0));",
         "}");
}
