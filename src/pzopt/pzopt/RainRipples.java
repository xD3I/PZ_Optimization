package pzopt;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import zombie.core.Core;
import zombie.core.ShaderHelper;
import zombie.iso.IsoPuddles;

/**
 * The raindrops' rings on puddles and wet ground (2026-10-11, key {@code ripplesRain}, with {@code ripples}; write-up
 * docs/findings-rain-wakes-2026-10-11.md). The stock puddle shader's {@code getRain} draws a ring per screen cell with a
 * fixed spot per cell, the rings cut at the cell's 2 x 2 neighbourhood and tilting the normal through {@code .xxy} (the x
 * slope twice): squares of half rings that pulse in place. Here every mode draws the same ring, a raindrop's version of
 * the footstep's (pzopt.Ripples' closed form of the iWave impulse response, scaled by {@link #S} in space and sqrt(S) in
 * time, as gravity waves scale), filtered for the pixel's footprint (a crest finer than a pixel fades instead of
 * shimmering), into the same wave normal as the footsteps (so the sky reflection, HDR's glints and pzopt.Ssr bend with it):
 * <ul>
 *   <li>{@code cells}: procedural, world-anchored cells, each with one drop a cycle at a new random spot (a hash of the
 *       cell and the cycle), four layers by rain intensity, each pixel the 2 x 2 cells a ring can reach: ALU only;</li>
 *   <li>{@code texture}: Lagarde's ripple texture (Remember Me, 2013; UE's rain ripple material): drops baked into a
 *       tileable texture (each texel: its drop's offset, time offset, rank), one fetch a layer, the ring maths per pixel;</li>
 *   <li>{@code flipbook}: the whole superposed field precomputed over one period (32 frames, two layers in RGBA8 of a
 *       mipmapped 3D texture: hardware filtering in space and time, mips are the pixel filter), two fetches;</li>
 *   <li>{@code field}: texture-space shading: a compute pass evaluates the cells into a small periodic texture each frame
 *       (cost fixed, not per pixel), the pixel reads one texel;</li>
 *   <li>{@code sim}: a periodic iWave simulation (the footstep sim's kernel, its gravity scaled for the drops' size), drops
 *       injected as Gaussian impulses: real interference; the pixel reads four texels.</li>
 * </ul>
 * {@code stock} keeps the game's rings, {@code none} draws none (the reference for what stock costs).
 */
final class RainRipples {
   private RainRipples() {
   }

   static final String[] MODES = {"stock", "none", "cells", "texture", "flipbook", "field", "sim"};
   static final int STOCK = 0, NONE = 1, CELLS = 2, TEXTURE = 3, FLIPBOOK = 4, FIELD = 5, SIM = 6;

   // the ring: the footstep's closed form (Ripples.ALU_*) scaled S in space, sqrt(S) in time (gravity-wave scaling)
   // 0.4: a crest 0.09 squares (4 px down the screen at zoom 1; a real drop's would be under a pixel), the ring 0.7 squares at
   // the end of its 1.1 s (the first smoke run's 0.25 drew crests the pixel filter mostly removed)
   static final float S = Config.DEV_RAIN_SCALE_PCT / 100F, ST = (float)Math.sqrt(S);
   static final float LIFE = Ripples.LIFE * ST; // 1.14 s
   static final float REACH = (Ripples.ALU_R1 * (float)Math.pow(Ripples.LIFE, Ripples.ALU_RP) + Ripples.ALU_R0) * S; // squares
   static final float CYCLE = Math.round(LIFE * 1.1F * 100F) / 100F; // a cell's drop period (s): a drop a cycle, life LIFE
   static final float AMP = Config.DEV_RAIN_AMP_PCT / 100F; // the drop's strength against a footstep's
   static final int LAYERS = 4;
   static final float CELL = (float)Math.ceil(REACH / 0.75F * 8F) / 8F; // cells: a cell (squares); the drop within +-CELL/4 of its centre, so 2 x 2 cells suffice (REACH <= 0.75 CELL)
   static final float SPAN = 4F; // texture / flipbook / field / sim: the tile (squares), N texels a side
   static final int PERIOD_CELLS = Math.round(40F / CELL); // cells: the hash repeats every 40 squares: the view origin is taken mod that
   static final float PERIOD = PERIOD_CELLS * CELL; // 40 squares (a multiple of SPAN)
   static final int N = 320; // 80 texels a square: the inner crests 4 texels
   static final int FRAMES = 32; // flipbook frames a cycle
   static final float RANGE = 1F; // flipbook: slope -0.5 .. 0.5 in a byte (a ring is 0.05 - 0.3)
   static final int UNIT_TEX = 21, UNIT_VOL = 22; // the puddle program's units (Ripples 18-20)

   static volatile boolean failed;
   public static long frames, simSteps, simDrops, fieldDispatches;
   static String status = "";

   static int modeIndex(String m) {
      for (int i = 0; i < MODES.length; i++) {
         if (MODES[i].equals(m)) {
            return i;
         }
      }
      return CELLS;
   }

   // ------------------------------------------------------------------------------------------------ game thread

   private static final java.util.SplittableRandom RNG = new java.util.SplittableRandom(0x7a1d);
   private static float simAcc;
   private static int simPending;
   static final int MAX_DROPS = 16, MAX_STEPS = 4;

   /** The rain part of a frame: the mode, the clock, the density, the sim's steps and drops. */
   static final class RainFrame {
      int mode;
      float time, density, amp, footprint;
      int steps;
      float frac;
      final float[] drops = new float[MAX_STEPS * MAX_DROPS * 4];
      final int[] nDrops = new int[MAX_STEPS];
   }

   /** Game thread, Ripples.gather (first player, once a frame): the clock's steps for the simulation. */
   static void advance(float dt, int mode) {
      if (mode != SIM) {
         simPending = 0;
         return;
      }
      simAcc += dt * SIM_HZ;
      int s = (int)simAcc;
      simAcc -= s;
      simPending = Math.min(MAX_STEPS, simPending + s);
   }

   /** Game thread, Ripples.build: this frame's rain state; whether it needs the frame sent (a mode other than stock). */
   static boolean build(RainFrame r, int mode, int playerIndex) {
      r.mode = mode;
      r.time = (float)(Ripples.clock % (CYCLE * 200.0)); // every pattern repeats each CYCLE: the wrap is seamless
      float ri = IsoPuddles.getInstance().getRainIntensity(); // the shader's rainIntensity: 0 .. 2
      r.density = Math.max(0F, Math.min(1F, ri * 0.5F));
      float zoom = Core.getInstance().getZoom(playerIndex);
      // a screen pixel in squares (offscreen px are 1/64 square along the view's x; zoom offscreen px a screen pixel)
      // (along the world axes 1/72 square at zoom 1, 1/45 down the screen: the geometric mean)
      // (along the world axes 1/72 square at zoom 1, 1/45 down the screen: the geometric mean), as a Gaussian's sigma: 0.5 of it
      r.footprint = 0.5F * zoom / 57F * (2F / Core.tileScale);
      r.amp = AMP * Config.RIPPLES_RAIN_PCT / 100F;
      r.steps = 0;
      r.frac = simAcc;
      if (mode == SIM) {
         r.steps = simPending;
         simPending = 0;
         // drops this step: density x (LAYERS / CELL^2 / CYCLE) drops a square a second over the tile
         float perStep = r.density * LAYERS / (CELL * CELL * CYCLE) * SPAN * SPAN / SIM_HZ;
         for (int k = 0; k < r.steps; k++) {
            float want = perStep;
            int n = 0;
            while (want > 0F && n < MAX_DROPS) {
               if (want >= 1F || RNG.nextFloat() < want) {
                  int o = (k * MAX_DROPS + n) * 4;
                  r.drops[o] = RNG.nextFloat() * N;
                  r.drops[o + 1] = RNG.nextFloat() * N;
                  r.drops[o + 2] = SIM_DEPTH * (0.6F + 0.4F * RNG.nextFloat());
                  r.drops[o + 3] = SIM_DROP_TEXELS;
                  n++;
               }
               want -= 1F;
            }
            r.nDrops[k] = n;
            simDrops += n;
         }
      }
      frames++;
      return mode != STOCK;
   }

   static String stats() {
      return "rain: frames=" + frames + " field_dispatches=" + fieldDispatches + " sim_steps=" + simSteps + " sim_drops=" + simDrops + status + (failed ? " (failed)" : "");
   }

   // ------------------------------------------------------------------------------------------------ the ring (CPU twin)

   /** The ring's radial slope at r squares from the drop, t s after it (no pixel filter): pzRainRing's twin. */
   static float ring(float r, float t) {
      float rs = r / S, ts = t / ST;
      if (ts < 0F || ts >= Ripples.LIFE) {
         return 0F;
      }
      float rv = Ripples.ALU_R1 * (float)Math.pow(ts, Ripples.ALU_RP) + Ripples.ALU_R0;
      if (rs > rv) {
         return 0F;
      }
      float x = Math.max(0F, Math.min(1F, (rv - rs) / (0.4F * rv)));
      float k = Ripples.ALU_K * (1F + Ripples.ALU_CHIRP * (1F - rs / rv));
      float e = Math.max(0F, Math.min(1F, (ts - Ripples.LIFE) / (Ripples.LIFE * 0.65F - Ripples.LIFE)));
      float fade = e * e * (3F - 2F * e);
      return Ripples.ALU_A * (float)Math.exp(Ripples.ALU_B * ts) * fade * x * x * (3F - 2F * x) * (float)Math.sin(k * rs - Ripples.ALU_K * Ripples.ALU_VP * ts) / (float)Math.sqrt(1F + 4F * rs);
   }

   // ------------------------------------------------------------------------------------------------ baked textures

   private static volatile java.nio.ShortBuffer texData;
   private static volatile ByteBuffer volData;
   private static volatile boolean texStarted, volStarted;
   static int texTex, volTex;

   /**
    * Lagarde's ripple texture, two drops a texel: two independent sets of non-overlapping drops (rg, ba: the texel's offset
    * from its drop's centre / REACH, 16 bits). A drop's time offset and rank are a hash of its centre, which the shader
    * recovers from the offset (centres snapped to the middle of a 1/64-square cell, so the recovered one never straddles two).
    */
   static java.nio.ShortBuffer buildTexture(long seed) {
      java.nio.ShortBuffer b = BufferUtils.createShortBuffer(N * N * 4);
      for (int i = 0; i < N * N * 4; i++) {
         b.put(i, (short)0xffff); // outside every drop: offset (1, 1), |o| > 1
      }
      java.util.SplittableRandom r = new java.util.SplittableRandom(seed);
      float rad = REACH / SPAN * N; // texels
      float snap = N / SPAN / 64F; // texels a 1/64-square cell
      int total = 0;
      for (int set = 0; set < 2; set++) {
         float[] cx = new float[512], cy = new float[512];
         int n = 0;
         for (int tries = 0; tries < 40000 && n < cx.length; tries++) {
            float x = ((float)Math.floor(r.nextFloat() * N / snap) + 0.5F) * snap, y = ((float)Math.floor(r.nextFloat() * N / snap) + 0.5F) * snap;
            boolean ok = true;
            for (int i = 0; i < n && ok; i++) {
               float dx = Math.abs(x - cx[i]), dy = Math.abs(y - cy[i]);
               dx = Math.min(dx, N - dx);
               dy = Math.min(dy, N - dy);
               ok = dx * dx + dy * dy >= 4F * rad * rad;
            }
            if (ok) {
               cx[n] = x;
               cy[n] = y;
               n++;
            }
         }
         total += n;
         for (int i = 0; i < n; i++) {
            int x0 = (int)Math.floor(cx[i] - rad) - 1, x1 = (int)Math.ceil(cx[i] + rad) + 1, y0 = (int)Math.floor(cy[i] - rad) - 1, y1 = (int)Math.ceil(cy[i] + rad) + 1;
            for (int y = y0; y <= y1; y++) {
               for (int x = x0; x <= x1; x++) {
                  float ox = (x + 0.5F - cx[i]) / rad, oy = (y + 0.5F - cy[i]) / rad;
                  if (ox * ox + oy * oy > 1.25F) {
                     continue; // a little past the rim: the bilinear filter at the rim mixes this drop's offsets, not the outside's
                  }
                  int o = (Math.floorMod(y, N) * N + Math.floorMod(x, N)) * 4 + set * 2;
                  b.put(o, (short)Math.round(Math.max(0F, Math.min(65535F, (ox * 0.5F + 0.5F) * 65535F))));
                  b.put(o + 1, (short)Math.round(Math.max(0F, Math.min(65535F, (oy * 0.5F + 0.5F) * 65535F))));
               }
            }
         }
      }
      status += " texture_drops=" + total;
      return b;
   }

   /**
    * The flipbook: FRAMES frames over one CYCLE of the tile, two independent layers (rg, ba), each the sum of its drops'
    * slopes (drops born at random times in the cycle and spots in the tile, both periodic), density LAYERS / CELL^2 / CYCLE
    * a layer like the cells.
    */
   static ByteBuffer buildFlipbook(long seed) {
      float[] g = new float[N * N * 4 * FRAMES];
      java.util.SplittableRandom r = new java.util.SplittableRandom(seed);
      float perLayer = SPAN * SPAN / (CELL * CELL * CYCLE); // drops a layer a cycle (one cells layer's density)
      float texel = SPAN / N;
      int rad = (int)Math.ceil(REACH / texel);
      int drops = 0;
      for (int layer = 0; layer < 2; layer++) {
         int nd = Math.round(perLayer);
         for (int d = 0; d < nd; d++) {
            float x = r.nextFloat() * SPAN, y = r.nextFloat() * SPAN, t0 = r.nextFloat() * CYCLE, a = 0.6F + 0.4F * r.nextFloat();
            drops++;
            for (int f = 0; f < FRAMES; f++) {
               float t = f * CYCLE / FRAMES;
               float age = t - t0;
               if (age < 0F) {
                  age += CYCLE;
               }
               if (age >= LIFE) {
                  continue;
               }
               int cxT = (int)Math.floor(x / texel), cyT = (int)Math.floor(y / texel);
               for (int j = -rad; j <= rad; j++) {
                  for (int i = -rad; i <= rad; i++) {
                     float dx = (cxT + i + 0.5F) * texel - x, dy = (cyT + j + 0.5F) * texel - y;
                     float rr = (float)Math.sqrt(dx * dx + dy * dy);
                     if (rr > REACH) {
                        continue;
                     }
                     float s = ring(rr, age) * a / (rr + 1e-4F);
                     int o = ((f * N + Math.floorMod(cyT + j, N)) * N + Math.floorMod(cxT + i, N)) * 4 + layer * 2;
                     g[o] += dx * s;
                     g[o + 1] += dy * s;
                  }
               }
            }
         }
      }
      ByteBuffer b = BufferUtils.createByteBuffer(g.length);
      for (float v : g) {
         b.put((byte)Math.round(Math.max(0F, Math.min(255F, (v / RANGE + 0.5F) * 255F))));
      }
      b.flip();
      status += " flipbook_drops=" + drops;
      return b;
   }

   private static void startBake(boolean volume) {
      Thread t = new Thread(() -> {
         try {
            long t0 = System.nanoTime();
            if (volume) {
               volData = buildFlipbook(0xf11b);
            } else {
               texData = buildTexture(0x7e47);
            }
            Log.info(String.format("ripples: rain %s baked in %.1f ms", volume ? "flipbook" : "texture", (System.nanoTime() - t0) / 1e6));
         } catch (Throwable e) {
            failed = true;
            Log.warn("ripples: rain bake failed: " + e);
         }
      }, "pzopt-rain-bake");
      t.setDaemon(true);
      t.start();
   }

   /** Render thread: the mode's texture on its unit (created from the worker's bake when it is ready); false while baking. */
   private static boolean bindBaked(int mode) {
      if (mode == TEXTURE) {
         if (texTex == 0) {
            if (texData == null) {
               if (!texStarted) {
                  texStarted = true;
                  startBake(false);
               }
               return false;
            }
            texTex = GL11.glGenTextures();
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT_TEX);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texTex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA16, N, N, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_SHORT, texData);
            texData = null;
         }
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT_TEX);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, texTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         return true;
      }
      if (volTex == 0) {
         if (volData == null) {
            if (!volStarted) {
               volStarted = true;
               startBake(true);
            }
            return false;
         }
         volTex = GL11.glGenTextures();
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT_VOL);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, volTex);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL11.GL_REPEAT);
         GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
         GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, N, N, FRAMES, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, volData);
         GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
         GL30.glGenerateMipmap(GL12.GL_TEXTURE_3D);
         volData = null;
      }
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT_VOL);
      GL11.glBindTexture(GL12.GL_TEXTURE_3D, volTex);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      return true;
   }

   // ------------------------------------------------------------------------------------------------ GPU passes

   static final float SIM_HZ = 60F;
   static final float SIM_RES = N / SPAN; // 102.4 texels a square
   // the footstep sim's gravity at 16 texels a square, scaled for a ring S its size at SIM_RES texels a square, sqrt(S) its time:
   // the ring is S * SIM_RES / RES times as many texels (time x sqrt of that), and must run 1/sqrt(S) faster: g x that^2
   static final float SIM_G;
   static final float SIM_ALPHA = 0.03F, SIM_DROP_TEXELS = 1.6F, SIM_DEPTH = -0.08F;

   static {
      float texelScale = S * SIM_RES / RipplesModel.RES;
      float timeScale = (float)Math.sqrt(texelScale) / ST;
      SIM_G = RipplesModel.G * timeScale * timeScale;
   }

   private static int fieldProg, stepProg, uFieldP, uStepK, uStepInfo, uStepDrops, uStepKern;
   private static final int[] simTex = new int[2];
   private static int fieldTex, simCur;
   private static boolean gpuTried, gpuOk;
   private static final FloatBuffer DROP_BUF = BufferUtils.createFloatBuffer(MAX_DROPS * 4);

   private static boolean gpuInit() {
      if (gpuTried) {
         return gpuOk;
      }
      gpuTried = true;
      if (!GL.getCapabilities().OpenGL43) {
         Log.warn("ripples: rain field / sim need OpenGL 4.3 compute: drawn as cells");
         return false;
      }
      fieldProg = RipplesGpu.program(FIELD_CS, "rain field");
      stepProg = RipplesGpu.program(STEP_CS, "rain sim step");
      if (fieldProg == 0 || stepProg == 0) {
         return false;
      }
      uFieldP = GL20.glGetUniformLocation(fieldProg, "P");
      uStepK = GL20.glGetUniformLocation(stepProg, "K");
      uStepInfo = GL20.glGetUniformLocation(stepProg, "info");
      uStepDrops = GL20.glGetUniformLocation(stepProg, "drops");
      uStepKern = GL20.glGetUniformLocation(stepProg, "kern");
      GL20.glUseProgram(stepProg);
      FloatBuffer kb = BufferUtils.createFloatBuffer(RipplesModel.KERNEL.length);
      kb.put(RipplesModel.KERNEL).flip();
      GL20.glUniform1fv(uStepKern, kb);
      GL20.glUseProgram(0);
      fieldTex = RipplesGpu.texture(N, N, GL30.GL_RG16F, GL11.GL_LINEAR, GL11.GL_REPEAT);
      simTex[0] = RipplesGpu.texture(N, N, GL30.GL_RG16F, GL11.GL_LINEAR, GL11.GL_REPEAT);
      simTex[1] = RipplesGpu.texture(N, N, GL30.GL_RG16F, GL11.GL_LINEAR, GL11.GL_REPEAT);
      RipplesGpu.GL44Clear.zero(simTex[0], N);
      RipplesGpu.GL44Clear.zero(simTex[1], N);
      gpuOk = true;
      Log.info(String.format("ripples: rain compute ready (tile %.1f squares, %d^2, sim g %.3f)", SPAN, N, SIM_G));
      return true;
   }

   /** Is the mode drawable here (compute for field / sim)? Else cells. */
   static int effective(int mode) {
      if (mode == FLIPBOOK && noVolume) {
         return CELLS;
      }
      if ((mode == FIELD || mode == SIM) && (RipplesGpu.gl43 == 0 || gpuTried && !gpuOk)) { // (game thread: the cached capability)
         return CELLS;
      }
      return mode;
   }

   /** Render thread, Ripples.Begin: the field's evaluation or the simulation's steps for this frame. */
   static void render(RainFrame r) {
      if (failed || r.mode != FIELD && r.mode != SIM || !gpuInit()) {
         return;
      }
      if (r.mode == FIELD && r.density <= 0F) {
         return;
      }
      GpuSections.markNow("ripples.rain", false);
      try {
         if (r.mode == FIELD) {
            GL20.glUseProgram(fieldProg);
            GL20.glUniform4f(uFieldP, r.time, r.density, r.footprint, 0F);
            GL42.glBindImageTexture(0, fieldTex, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RG16F);
            GL43.glDispatchCompute(N / 16, N / 16, 1);
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            fieldDispatches++;
         } else if (r.steps > 0) {
            GL20.glUseProgram(stepProg);
            GL20.glUniform4f(uStepK, (2F - SIM_ALPHA) / (1F + SIM_ALPHA), 1F / (1F + SIM_ALPHA), SIM_G / (1F + SIM_ALPHA), 0F);
            for (int k = 0; k < r.steps; k++) {
               int nd = r.nDrops[k];
               DROP_BUF.clear();
               DROP_BUF.put(r.drops, k * MAX_DROPS * 4, MAX_DROPS * 4).flip();
               GL20.glUniform4fv(uStepDrops, DROP_BUF);
               GL20.glUniform4i(uStepInfo, nd, N, 0, 0);
               GL42.glBindImageTexture(0, simTex[simCur], 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_RG16F);
               GL42.glBindImageTexture(1, simTex[simCur ^ 1], 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RG16F);
               GL43.glDispatchCompute(N / 16, N / 16, 1);
               GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
               simCur ^= 1;
               simSteps++;
            }
         }
      } catch (Throwable t) {
         failed = true;
         Log.warn("ripples: rain compute failed, off: " + t);
      } finally {
         GL42.glBindImageTexture(0, 0, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_RG16F);
         GL42.glBindImageTexture(1, 0, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_RG16F);
         GL20.glUseProgram(0);
         ShaderHelper.forgetCurrentlyBound();
         GpuSections.markNow("ripples.rain", true);
      }
   }

   // ------------------------------------------------------------------------------------------------ puddle uniforms

   static final String[] UNIFORMS = {"pzRainP", "pzRainW", "pzRainTex", "pzRainVol"};
   private static final java.util.HashMap<Integer, int[]> LOC = new java.util.HashMap<>();

   /** Render thread, Ripples.puddleUniforms (the puddle program bound): the frame's rain mode and its texture. */
   static void uniforms(int prog, Ripples.Frame f) {
      int[] l = LOC.get(prog);
      if (l == null) {
         l = new int[UNIFORMS.length];
         for (int i = 0; i < l.length; i++) {
            l[i] = GL20.glGetUniformLocation(prog, UNIFORMS[i]);
         }
         LOC.put(prog, l);
         if (l[2] >= 0) {
            GL20.glUniform1i(l[2], UNIT_TEX);
         }
         if (l[3] >= 0) {
            GL20.glUniform1i(l[3], UNIT_VOL);
         }
      }
      if (l[0] < 0) {
         return;
      }
      RainFrame r = f == null ? null : f.rain;
      int mode = r == null || failed || !Ripples.on() ? STOCK : r.mode;
      if (mode == TEXTURE || mode == FLIPBOOK) {
         if (!bindBaked(mode)) {
            mode = CELLS; // baking (a fraction of a second, once)
         }
      } else if (mode == FIELD || mode == SIM) {
         if (!gpuOk) {
            mode = CELLS;
         } else {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT_TEX);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, mode == FIELD ? fieldTex : simTex[simCur]);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
         }
      }
      if (mode == STOCK) {
         GL20.glUniform4f(l[0], 0F, 0F, 0F, 0F);
         return;
      }
      GL20.glUniform4f(l[0], mode, r.time, r.amp, mode == SIM ? r.frac : r.density);
      // the world square of the draw camera's view px (0, 0) at level 0, mod the patterns' common period
      org.joml.Vector4f off = IsoPuddles.getInstance().getShaderOffset();
      double a = off.x / (32.0 * Core.tileScale), b = off.y / (16.0 * Core.tileScale);
      double wx = ((a + b) * 0.5) % PERIOD, wy = ((b - a) * 0.5) % PERIOD;
      GL20.glUniform4f(l[1], (float)(wx < 0 ? wx + PERIOD : wx), (float)(wy < 0 ? wy + PERIOD : wy), 1F / (32F * Core.tileScale), r.footprint);
   }

   // ------------------------------------------------------------------------------------------------ shaders

   static String f(float v) {
      return Float.toString(v);
   }

   /** The ring, the hash and the cells (GLSL 1.20; the compute passes include it too). {@code FP} is the pixel footprint. */
   static String common(String footprint) {
      return String.join("\n",
            "// pzopt: rain rings (pzopt.RainRipples): a raindrop's ring is the footstep's scaled " + S + " in space, " + ST + " in time",
            "vec3 pzRainHash(vec3 p) { p = fract(p * vec3(0.1031, 0.1030, 0.0973)); p += dot(p, p.yxz + 33.33); return fract((p.xxy + p.yxx) * p.zyx); }",
            "float pzRainRing(float r, float t) {",
            "   float rs = r * " + f(1F / S) + ", ts = t * " + f(1F / ST) + ";",
            "   if (ts >= " + f(Ripples.LIFE) + ") return 0.0;",
            "   float rv = " + f(Ripples.ALU_R1) + " * pow(ts, " + f(Ripples.ALU_RP) + ") + " + f(Ripples.ALU_R0) + ";",
            "   if (rs > rv) return 0.0;",
            "   float x = clamp((rv - rs) / (0.4 * rv), 0.0, 1.0);",
            "   float k = " + f(Ripples.ALU_K) + " * (1.0 + " + f(Ripples.ALU_CHIRP) + " * (1.0 - rs / rv));",
            "   float kp = k * " + f(1F / S) + " * " + footprint + "; // the crest's frequency a pixel: filtered (a Gaussian pixel), not aliased",
            "   float fade = smoothstep(" + f(Ripples.LIFE) + ", " + f(Ripples.LIFE * 0.65F) + ", ts);",
            "   return " + f(Ripples.ALU_A) + " * exp(" + f(Ripples.ALU_B) + " * ts - 0.5 * kp * kp) * fade * x * x * (3.0 - 2.0 * x) * sin(k * rs - " + f(Ripples.ALU_K * Ripples.ALU_VP) + " * ts) * inversesqrt(1.0 + 4.0 * rs);",
            "}",
            "// the cells: w world squares, the hash periodic over per cells; LAYERS layers by density, the 2 x 2 cells a ring reaches",
            "vec2 pzRainCells(vec2 w, float density, float time, float per) {",
            "   vec2 g = vec2(0.0);",
            "   float nl = density * " + LAYERS + ".0;",
            "   for (int L = 0; L < " + LAYERS + "; L++) {",
            "      float lw = clamp(nl - float(L), 0.0, 1.0);",
            "      if (lw <= 0.0) break;",
            "      vec2 q = w * " + f(1F / CELL) + " + vec2(0.37, 0.61) * float(L);",
            "      vec2 b = floor(q - 0.5);",
            "      for (int j = 0; j < 2; j++) {",
            "         for (int i = 0; i < 2; i++) {",
            "            vec2 c = b + vec2(float(i), float(j));",
            "            vec2 cm = mod(c, per);",
            "            float u = time * " + f(1F / CYCLE) + " + pzRainHash(vec3(cm, float(L) * 7.0 + 0.5)).x;",
            "            float n = floor(u);",
            "            vec3 h = pzRainHash(vec3(cm + 0.5, mod(n, 256.0) * 0.731 + float(L) * 17.0));",
            "            if (h.z > lw) continue; // this cycle's drop is not in this density",
            "            vec2 dv = (q - c - 0.25 - 0.5 * h.xy) * " + f(CELL) + "; // squares from the drop (within +-CELL/4 of the cell's centre)",
            "            float r2 = dot(dv, dv);",
            "            if (r2 > " + f(REACH * REACH) + ") continue;",
            "            float r = sqrt(r2) + 1e-4;",
            "            g += dv * ((0.6 + 0.4 * h.x) * pzRainRing(r, (u - n) * " + f(CYCLE) + ") / r);",
            "         }",
            "      }",
            "   }",
            "   return g;",
            "}");
   }

   static volatile boolean noVolume; // the plain sampler3D declaration was used (see Ripples.patchShader): kept unless that compile failed

   /**
    * The puddle unit's raindrop text. Its sampler3D needs an explicit unit: the game validates the program with every
    * sampler on unit 0 and Mesa refuses two sampler types on one unit (2026-09-27, god rays' sampler3D blacked the
    * flip's world); without 420pack the flipbook is left out.
    */
   static String glsl(boolean binding) {
      noVolume = false;
      return GLSL.replace("PZ_RAIN_VOL_DECL", binding ? "layout(binding = " + UNIT_VOL + ") uniform sampler3D pzRainVol; // flipbook (an explicit unit: see glsl())"
            : "uniform sampler3D pzRainVol; // flipbook (no layout(binding) here: the unit is set after the link)");
   }

   static final String GLSL = String.join("\n",
         "// pzopt: rain rings (pzopt.RainRipples)",
         "uniform vec4 pzRainP; // x mode (0 the game's, 1 none, 2 cells, 3 texture, 4 flipbook, 5 field, 6 sim), y clock (s), z strength, w sim: step blend",
         "uniform vec4 pzRainW; // xy the world square of the view's px (0, 0) mod " + PERIOD + ", z squares a px / 2, w a screen pixel in squares",
         "uniform sampler2D pzRainTex; // texture: the drops; field: the slopes; sim: the height field",
         "PZ_RAIN_VOL_DECL",
         "vec2 pzRainG = vec2(0.0); // this pixel's raindrop slope (world squares: x, y)",
         common("pzRainW.w"),
         "vec2 pzRainSlope(vec2 fc) {",
         "   float mode = pzRainP.x;",
         "   float density = clamp(rainIntensity * 0.5, 0.0, 1.0);",
         "   if (density <= 0.0 && mode < 5.5) return vec2(0.0);",
         "   vec2 d = fc * WOffset.zw * pzRainW.z;",
         "   vec2 w = pzRainW.xy + vec2(d.x * 0.5 + d.y, d.y - d.x * 0.5); // world squares (periodic)",
         "   float time = pzRainP.y;",
         "   if (mode < 1.5) return vec2(0.0); // none",
         "   if (mode < 2.5) return pzRainCells(w, density, time, " + PERIOD_CELLS + ".0);",
         "   if (mode < 3.5) {",
         "      // texture: two fetches of two drop sets each (rg, ba), the second fetch transposed and offset; a drop's",
         "      // phase and rank are a hash of its centre (this texel minus its offset)",
         "      vec2 g = vec2(0.0);",
         "      float nl = density * " + LAYERS + ".0;",
         "      for (int F = 0; F < 2; F++) {",
         "         if (nl <= float(2 * F)) break;",
         "         vec2 wl = F == 1 ? w.yx + vec2(0.93, 0.41) : w;",
         "         vec2 uvt = wl * " + f(1F / SPAN) + ";",
         "         vec4 e = texture2D(pzRainTex, uvt);",
         "         for (int k = 0; k < 2; k++) {",
         "            float lw = clamp(nl - float(2 * F + k), 0.0, 1.0);",
         "            vec2 o = (k == 0 ? e.rg : e.ba) * 2.0 - 1.0;",
         "            float oo = dot(o, o);",
         "            if (oo >= 1.0 || lw <= 0.0) continue;",
         "            vec2 cen = floor(mod(fract(uvt) * " + f(SPAN) + " - o * " + f(REACH) + ", " + f(SPAN) + ") * 64.0);",
         "            vec3 h = pzRainHash(vec3(cen, float(2 * F + k) * 3.0 + 1.5));",
         "            float u = time * " + f(1F / CYCLE) + " + h.x;",
         "            float n = floor(u);",
         "            if (fract(h.y + n * 0.618) > lw) continue;",
         "            float r = sqrt(oo) * " + f(REACH) + " + 1e-4;",
         "            vec2 dv = o * " + f(REACH) + ";",
         "            if (F == 1) dv = dv.yx;",
         "            g += dv * ((0.6 + 0.4 * h.z) * pzRainRing(r, (u - n) * " + f(CYCLE) + ") / r);",
         "         }",
         "      }",
         "      return g;",
         "   }",
         "   if (mode < 4.5) {",
         "      // flipbook: two fetches of two layers each, the second transposed, offset and half a cycle on",
         "      float nl = density * " + LAYERS + ".0;",
         "      vec4 a = texture3D(pzRainVol, vec3(w * " + f(1F / SPAN) + ", time * " + f(1F / CYCLE) + "));",
         "      vec2 g = (a.rg - 0.5) * " + f(RANGE) + " * clamp(nl, 0.0, 1.0) + (a.ba - 0.5) * " + f(RANGE) + " * clamp(nl - 1.0, 0.0, 1.0);",
         "      if (nl > 2.0) {",
         "         vec4 b = texture3D(pzRainVol, vec3(w.yx * " + f(1F / SPAN) + " + vec2(0.37, 0.61), time * " + f(0.9F / CYCLE) + " + 0.5)); // its own period: the two loops line up only every 12.5 s",
         "         vec2 gb = (b.rg - 0.5) * clamp(nl - 2.0, 0.0, 1.0) + (b.ba - 0.5) * clamp(nl - 3.0, 0.0, 1.0);",
         "         g += gb.yx * " + f(RANGE) + ";",
         "      }",
         "      return g;",
         "   }",
         "   vec2 uv = w * " + f(1F / SPAN) + ";",
         "   if (mode < 5.5) return texture2D(pzRainTex, uv).rg; // field",
         "   // sim: the height field's slope (central differences), the last two steps blended to the frame's time",
         "   float e = " + f(1F / N) + ";",
         "   vec2 bl = vec2(pzRainP.w, 1.0 - pzRainP.w);",
         "   float hx = dot(texture2D(pzRainTex, uv + vec2(e, 0.0)).rg - texture2D(pzRainTex, uv - vec2(e, 0.0)).rg, bl);",
         "   float hy = dot(texture2D(pzRainTex, uv + vec2(0.0, e)).rg - texture2D(pzRainTex, uv - vec2(0.0, e)).rg, bl);",
         "   return vec2(hx, hy) * " + f(SIM_RES * 0.5F) + ";",
         "}",
         "// the stock call's replacement: the slope into pzRainG (the wave normal's plane), the shade term the stock adds",
         "vec3 pzRain(vec2 fc) {",
         // The stock surface mask also covers roofed/interior vertices; crest alpha must not resurrect them.
         "   vec2 g = pzRainSlope(fc) * pzRainP.z * clamp(puddlesDirNone, 0.0, 1.0);",
         "   pzRainG = vec2(g.x - g.y, g.x + g.y) * 0.7071;",
         "   return vec3(0.0, 0.0, (1.0 - rainIntensity * 0.5) * (1.0 - min(1.0, 1.5 * dot(pzRainG, pzRainG))));",
         "}",
         "");

   static final String FIELD_CS = String.join("\n",
         "#version 430",
         "layout(local_size_x = 16, local_size_y = 16) in;",
         "layout(rg16f, binding = 0) writeonly uniform image2D dst;",
         "uniform vec4 P; // x clock, y density, z a screen pixel in squares",
         common("P.z"),
         "void main() {",
         "   ivec2 c = ivec2(gl_GlobalInvocationID.xy);",
         "   vec2 w = (vec2(c) + 0.5) * " + f(SPAN / N) + ";",
         "   imageStore(dst, c, vec4(pzRainCells(w, P.y, P.x, " + Math.round(SPAN / CELL) + ".0), 0.0, 0.0));",
         "}");

   static final int APRON = RipplesModel.P, SH = 16 + 2 * RipplesModel.P;

   static final String STEP_CS = String.join("\n",
         "#version 430",
         "layout(local_size_x = 16, local_size_y = 16) in;",
         "layout(rg16f, binding = 0) readonly uniform image2D src; // r height now, g a step ago (toroidal)",
         "layout(rg16f, binding = 1) writeonly uniform image2D dst;",
         "uniform ivec4 info; // x drops this step, y size",
         "uniform vec4 K; // iWave a1, a2, a3",
         "uniform vec4 drops[" + MAX_DROPS + "]; // texel x, y, depth, radius",
         "uniform float kern[" + (2 * APRON + 1) * (2 * APRON + 1) + "];",
         "shared float sh[" + SH * SH + "];",
         "void main() {",
         "   int Nn = info.y;",
         "   ivec2 base = ivec2(gl_WorkGroupID.xy) * 16;",
         "   for (int i = int(gl_LocalInvocationIndex); i < " + SH * SH + "; i += 256) {",
         "      ivec2 o = ivec2(i % " + SH + ", i / " + SH + ") - " + APRON + ";",
         "      ivec2 wq = base + o;",
         "      sh[i] = imageLoad(src, (wq % Nn + Nn) % Nn).r; // toroidal (N is not a power of two)",
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
         "   float nh = K.x * h - K.y * imageLoad(src, c).g - K.z * conv;",
         "   float cur = h;",
         "   for (int i = 0; i < info.x; i++) {",
         "      vec2 dd = vec2(c) + 0.5 - drops[i].xy;",
         "      dd -= float(Nn) * floor(dd / float(Nn) + 0.5);",
         "      float b = drops[i].z * exp(-dot(dd, dd) / (drops[i].w * drops[i].w));",
         "      nh += b;",
         "      cur += b;",
         "   }",
         "   imageStore(dst, c, vec4(nh, cur, 0.0, 0.0));",
         "}");

   /** The puddle unit's text with the raindrop modes in (before SphereMap, after the footsteps' GLSL), or null. */
   static String patch(String c) {
      String call = "vec3 rainWaves = getRain(uv);";
      if (!c.contains(call)) {
         return null;
      }
      c = c.replace(call, "vec3 rainWaves; if (pzRainP.x > 0.5) rainWaves = pzRain(fragCoord); else rainWaves = getRain(uv); // pzopt: rain rings (pzopt.RainRipples; 0 = the game's)");
      return c;
   }
}
