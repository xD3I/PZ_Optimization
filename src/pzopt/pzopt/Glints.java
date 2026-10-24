package pzopt;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import zombie.characters.IsoGameCharacter;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureFBO;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoLightSource;
import zombie.iso.IsoWorld;
import zombie.iso.LightingJNI;
import zombie.iso.weather.ClimateManager;

/**
 * Glints on frost, snow and wet asphalt (ground cover, 2026-10-24; key {@code glints}, the "Ground and grass" subcategory).
 *
 * <p>Ice crystals and the wet grains of asphalt are tiny mirrors: each one flashes when it happens to reflect a light into
 * the eye. Under PZ's orthographic camera the view vector is the same for every pixel, so the sun's glints hardly move
 * (only the slow sun turns them over); they come alive under point lights, whose half vector changes over the ground as the
 * light moves: a torch swept over a frosty lawn, headlights on a wet road, a street lamp over snow.
 *
 * <p>Per frame (game thread, {@link #queue}): the masked squares on screen (frost from {@code GroundCover}, snow from the
 * game's snow grid, wet road from the puddles' wetness), the key light (sun by day, moon by night: {@code pzopt.Sky}) and the
 * point lights near the view (handheld torches, headlights, active lamps: the list the native lighting gets), each square
 * tagged with the lights that reach it. No masked square or no light: nothing is queued, the pass costs exactly 0.
 *
 * <p>Render thread ({@code glintPass=diamonds}): one instanced draw of the squares' floor diamonds, depth-tested (LEQUAL)
 * against the floor's depth (IsoDepthHelper, as the chunk textures hold it), so anything standing on the floor hides the
 * glints behind it; additive. The fragment evaluates the glint NDF of {@code glintModel} (pzopt.GlintGlsl) for each light
 * and kind. Drawn after the composite and the players, before the puddles (puddle water is smooth: no grains glint under it).
 */
public final class Glints {
   private Glints() {
   }

   static final String[] MODELS = {"db23", "db23iso", "jamsi", "sparkle", "explicit"};
   static final int MAX_LIGHTS = 8;
   /**
    * A square's data: two RGBA32F texels (2026-10-24, was four: the upload halved) - (screen x, screen y, depth, kind | level
    * << 2 | light bits << 10), (x - origin, y - origin, strength, sun x 255 | light x 255 << 8); the shaders unpack them
    * (pzgSquare) and rebuild the grid coordinates from the square and the frame origin (uOrigin).
    */
   static final int TEXELS = 2, MAX = 16384;
   static final float METRIC_Z = 2.4494897F; // squares of height per level (sqrt 6: the view vector's 30 degrees)
   static final int K_FROST = 0, K_SNOW = 1, K_WET = 2;

   private static volatile boolean failed = CoreGl.legacyMac();
   public static long frames, framesQueued, instances, maxInstances, maskRebuilds, draws, lightFrames, sunFrames, pointSquares, sunSquares;

   public static boolean on() {
      return Ground.on(Ground.K_GLINTS) && Overrides.enabled() && !failed && Core.getInstance() != null;
   }

   public static String stats() {
      return "glints: frames=" + frames + " queued=" + framesQueued + " instances=" + instances + " max=" + maxInstances + " maskRebuilds=" + maskRebuilds
            + " draws=" + draws + " queue_us=" + (frames > 0 ? String.format(java.util.Locale.ROOT, "%.1f (masks %.1f, lights %.1f, points %.1f)", queueNs / 1000.0 / frames, maskNs / 1000.0 / frames, lightsNs / 1000.0 / frames, pointNs / 1000.0 / frames) : "0") + (framesFull > 0 ? String.format(java.util.Locale.ROOT, " queue_us_cached=%.1f queue_us_qfull=%.1f", (queueNs - queueFullNs) / 1000.0 / Math.max(1, frames - framesFull), queueFullNs / 1000.0 / framesFull) : "") + " async=" + asyncJobs + "/" + syncJobs + " join_us=" + (asyncJobs > 0 ? String.format(java.util.Locale.ROOT, "%.2f", joinNs / 1000.0 / asyncJobs) : "0") + " join_waits=" + joinWaits + " join_max_us=" + joinMaxNs / 1000 + " soft_deferred=" + softDeferred + " levels=" + levelsWalked + " relit=" + levelsRelit + " dispatch_us=" + (asyncJobs > 0 ? String.format(java.util.Locale.ROOT, "%.2f", dispatchNs / 1000.0 / asyncJobs) : "0") + " worker_us=" + (asyncJobs > 0 ? String.format(java.util.Locale.ROOT, "%.1f", workerNs / 1000.0 / asyncJobs) : "0") + " render_waits=" + renderWaits + vstats() + " bulk=" + bulkCopies + " mask_us=" + (maskRebuilds > 0 ? String.format(java.util.Locale.ROOT, "%.2f", maskNs / 1000.0 / maskRebuilds) : "0") + " ring=" + Gpu.ringWrites + " hdr=" + hdrQueued + "/" + Gpu.hdrDraws + " lightFrames=" + lightFrames + " noMaskFrames=" + noMaskFrames + " sunFrames=" + sunFrames + " pointSquares=" + pointSquares + " sunSquares=" + sunSquares + " model=" + model() + " pass=" + PASS_NAMES[passMode()] + " lamps=" + lampsCell + "/" + lampN + "/" + lampsActive + String.format(java.util.Locale.ROOT, " key: sun %.1f deg day %.2f cloud %.2f", lastSunElev, lastDay, lastCloud) + (failed ? " (failed)" : "");
   }

   /** The model in force (devGlintCycle steps through them, then "off"). */
   static int model() {
      if (Config.DEV_GLINT_CYCLE > 0) {
         long step = System.currentTimeMillis() / Config.DEV_GLINT_CYCLE;
         int i = (int)(step % (MODELS.length + 1));
         return i == MODELS.length ? -1 : i;
      }
      if (Config.DEV_GLINT_ALTERNATE > 0 && (System.currentTimeMillis() / Config.DEV_GLINT_ALTERNATE & 1L) == 1L) {
         return -1;
      }
      String m = Config.GLINT_MODEL;
      String v = Ground.variant();
      if (v != null) {
         for (int i = 0; i < MODELS.length; i++) {
            if (MODELS[i].equals(v)) {
               return i;
            }
         }
      }
      for (int i = 0; i < MODELS.length; i++) {
         if (MODELS[i].equals(m)) {
            return i;
         }
      }
      return 1;
   }

   static final String[] PASS_NAMES = {"diamonds", "particles", "compact"};
   static final int P_DIAMONDS = 0, P_PARTICLES = 1, P_COMPACT = 2;
   /** Flake slots a square: 4 lights (the key light first) x 4 angular cells x 4 flakes a cell. */
   static final int SLOTS = 64;

   /** The pass in force (glintPass, or a devGroundCycle name). */
   static int passMode() {
      String v = Ground.variant();
      String m = Config.GLINT_PASS;
      for (int i = 0; i < PASS_NAMES.length; i++) {
         if (PASS_NAMES[i].equals(v)) {
            m = v;
         }
      }
      int p = "particles".equals(m) ? P_PARTICLES : "compact".equals(m) ? P_COMPACT : P_DIAMONDS;
      if (p == P_COMPACT && !Gpu.computeOk()) {
         p = P_PARTICLES;
      }
      return p;
   }

   static String section(int model) {
      return Ground.section(model < 0 ? "ground.glints.off" : "ground.glints." + MODELS[model]);
   }

   static String section(int model, int pass) {
      return pass == P_DIAMONDS || model < 0 ? section(model) : Ground.section("ground.glints." + PASS_NAMES[pass]);
   }

   // ------------------------------------------------------------------------------------------------ the masks

   /**
    * One chunk level's masks: per square the dominant kind (frost, snow, wet) and its strength, whether it is outdoors and
    * how much sun reaches it. Rebuilt every {@code REFRESH} frames (staggered by chunk) and when the chunk changes.
    */
   static final class LevelMask {
      long bits; // squares with a mask
      final byte[] kind = new byte[64];
      final byte[] strength = new byte[64]; // 0..255
      final byte[] sun = new byte[64]; // 0..255: the square's sun visibility (frost's shade term)
      long builtMs = Long.MIN_VALUE;
      int level, ts;
      int coverStamp, wetBucket; // what the mask was built from: the chunk level's ground cover (frost, sun), the wet ground
      boolean snow;
      String force = "";
      // per frame state kept across frames: the squares' light bits (valid while lightKey matches) and light (LUM_MS)
      final byte[] lbits = new byte[64];
      int lightKey;
      boolean lightKeyOk;
      final byte[] lum = new byte[64];
      long lumValid, lumMs = Long.MIN_VALUE / 2;
      int lumPlayer = -1;
      // the chunk level's finished records of the last frame (copied whole while nothing in them changed)
      final float[] out = new float[64 * TEXELS * 4];
      int outN, outOx, outOy, outPoint, outSunN;
      long outVisible;
      float outDepth;
      boolean outOk, outSun;
      /** per masked square, its data block's frame-independent part (the frame adds its chunk depth, origin, light bits) */
      final float[] block = new float[64 * TEXELS * 4];
   }

   static final class ChunkMasks {
      final LevelMask[] levels = new LevelMask[64]; // by level + 32
      int wx = Integer.MIN_VALUE, wy;
      long seenMs;
   }

   /** The masks ride on their chunk (GroundChunk slot: dropped when the game reuses the chunk). */
   static final int SLOT = GroundChunk.id("glint masks");
   static final int REFRESH_MS = 400, SAFETY_MS = 5000, LUM_MS = 100; // + a chunk stagger of up to 250 ms (milliseconds: at 800 fps 30 frames was 40 ms)

   static ChunkMasks masks(IsoChunk c, long now) {
      ChunkMasks cm = (ChunkMasks)GroundChunk.get(c, SLOT);
      if (cm == null || cm.wx != c.wx || cm.wy != c.wy) {
         cm = new ChunkMasks();
         cm.wx = c.wx;
         cm.wy = c.wy;
         GroundChunk.put(c, SLOT, cm);
      }
      cm.seenMs = now;
      return cm;
   }

   /** Mask rebuilds a frame for a mask that is only out of date (a 5 % wet step, the frost / sun moved): the rest next frame. */
   static final int SOFT_BUDGET = 12;

   static LevelMask mask(IsoChunk c, ChunkMasks cm, int level, long now, float wetGround, boolean snowAny, Job j) {
      int li = level + 32;
      if (li < 0 || li >= 64) {
         return null;
      }
      LevelMask m = cm.levels[li];
      if (m == null) {
         m = cm.levels[li] = new LevelMask();
         m.level = level;
      }
      // rebuilt when what it reads changed (the cover's frost / sun, the wet ground's 5 % steps, the forcing, the tile
      // scale), while snow lies (it drifts square by square) every REFRESH_MS, and every SAFETY_MS whatever (a floor
      // placed or dug); the first version rebuilt every 400 ms: 15 rebuilds a frame at 60 fps at zoom 2.5 (gcv-final-smoke)
      int stagger = ((c.wx * 7 + c.wy * 13) & 31) * 8;
      int stamp = GroundCover.coverStamp(c, level), wb = (int)(wetGround * 20F);
      String force = Config.DEV_GLINT_FORCE;
      long age = now - m.builtMs;
      boolean hard = m.builtMs == Long.MIN_VALUE || age < 0L || m.ts != Core.tileScale || !m.force.equals(force);
      if (!hard && m.coverStamp == stamp && m.wetBucket == wb && m.snow == snowAny && !(snowAny && age > REFRESH_MS + stagger)
            && age < SAFETY_MS + stagger * 10L) {
         return m;
      }
      // a mask that is only out of date waits its turn (gcv-cost-wetnight6: a wet step rebuilt ~400 chunk levels in one
      // frame, 4-5 ms on the worker, which the frame's end then waited for); new / rescaled / forced ones are built at once
      if (!hard && !"qfull".equals(j.variant)) {
         if (j.softLeft <= 0) {
            softDeferred++;
            return m;
         }
         j.softLeft--;
      }
      m.builtMs = now;
      m.ts = Core.tileScale;
      m.coverStamp = stamp;
      m.wetBucket = wb;
      m.force = force;
      m.snow = snowAny;
      maskRebuilds++;
      long t0 = System.nanoTime();
      build(c, level, m, wetGround, snowAny);
      maskNs += System.nanoTime() - t0;
      return m;
   }

   private static void build(IsoChunk c, int level, LevelMask m, float wetGround, boolean snowAny) {
      m.bits = 0L;
      m.lightKeyOk = false;
      m.outOk = false;
      m.lumValid = 0L;
      String force = Config.DEV_GLINT_FORCE;
      int forceKind = "frost".equals(force) ? K_FROST : "snow".equals(force) ? K_SNOW : "wet".equals(force) ? K_WET : -1;
      zombie.iso.fboRenderChunk.FBORenderSnow snow = snowAny ? zombie.iso.fboRenderChunk.FBORenderSnow.getInstance() : null;
      GroundCover.LevelCover lc = GroundCover.built(c, level);
      boolean frostOn = GroundCover.on();
      for (int i = 0; i < 64; i++) {
         IsoGridSquare sq = c.getGridSquare(i & 7, i >> 3, level);
         if (sq == null || sq.getFloor() == null || !sq.isOutside()) {
            continue;
         }
         int kind = -1;
         float s = 0F;
         float sunVis = lc == null ? 1F : (lc.sunSq[i] & 255) / 255F;
         if (forceKind >= 0) {
            kind = forceKind;
            s = 1F;
         } else if (snow != null && snow.gridSquareIsSnow(sq.x, sq.y, sq.z)) {
            kind = K_SNOW;
            s = 1F;
         } else {
            float frost = frostOn && lc != null ? (lc.frostSq[i] & 255) / 255F : 0F;
            if (frost > 0.05F) {
               kind = K_FROST;
               s = frost;
            } else if (wetGround > 0.05F && GroundCover.isRoad(sq)) {
               kind = K_WET;
               s = Math.min(1F, wetGround * 1.5F);
            }
         }
         if (kind < 0) {
            continue;
         }
         m.bits |= 1L << i;
         m.kind[i] = (byte)kind;
         m.strength[i] = (byte)Math.round(Math.min(1F, s) * 255F);
         m.sun[i] = (byte)Math.round(Math.max(0F, Math.min(1F, sunVis)) * 255F);
         int x = sq.x, y = sq.y, ts = m.ts, o = i * TEXELS * 4;
         float[] d = m.block;
         // the floor diamond's north corner in absolute screen pixels (IsoUtils.XToScreen / YToScreen at tile scale ts)
         d[o] = (x - y) * (32F * ts);
         d[o + 1] = (x + y) * (16F * ts) - level * (96F * ts);
         d[o + 2] = IsoDepthHelper.calculateDepth(x, y, level); // + the frame's chunk depth
         d[o + 3] = kind | (level & 255) << 2; // | the frame's light bits << 10
         d[o + 6] = (m.strength[i] & 255) / 255F;
         d[o + 7] = m.sun[i] & 255; // + the frame's square light x 255 << 8
      }
   }


   // ------------------------------------------------------------------------------------------------ lights

   /** One frame's lights, relative to the frame origin: point lights then the key light (sun / moon). */
   static final class Lights {
      int n;
      final float[] p = new float[MAX_LIGHTS * 4]; // x, y, z (squares, z in squares), radius
      final float[] d = new float[MAX_LIGHTS * 4]; // dir x, y, cone cos (-2 = all round), strength
      final float[] c = new float[MAX_LIGHTS * 4]; // r, g, b, -
      final float[] sun = new float[4]; // direction (x east, y south, z up), strength (0 = no key light)
      final float[] sunC = new float[4];
      final int[] key = new int[MAX_LIGHTS]; // each light's quantised position, reach and cone (unchanged = same squares)
      // the snapped light the square tests use (a function of the key alone, so cached light bits always match it):
      // x, y, z, reach, dir x, dir y, cone cos; and the slack that covers the snapping (reach, cone)
      final float[] q = new float[MAX_LIGHTS * 8];
      float slackR, slackPos;
   }

   /** Light-key steps: positions / reach in 1/QPOS of a square, directions in 1/QDIR (dev variant "q64": the first, finer keys). */
   static void snapLights(Lights L, int ox, int oy, boolean fine) {
      float qp = fine ? 64F : 4F, qd = fine ? 256F : 32F;
      // what the snapping can move: a position half a step per axis, the reach half a step; fine keys kept the old slack
      L.slackPos = fine ? 0F : 0.5F / qp * 1.4143F;
      L.slackR = fine ? 0F : 0.5F / qp;
      for (int i = 0; i < L.n; i++) {
         int k = i * 4, o = i * 8;
         int ix = Math.round((L.p[k] + ox) * qp), iy = Math.round((L.p[k + 1] + oy) * qp), iz = Math.round(L.p[k + 2] * qp), ir = Math.round(L.p[k + 3] * qp);
         int idx = Math.round(L.d[k] * qd), idy = Math.round(L.d[k + 1] * qd), ic = Math.round(L.d[k + 2] * qd);
         int h = ix;
         h = h * 31 + iy;
         h = h * 31 + iz;
         h = h * 31 + ir;
         h = h * 31 + idx;
         h = h * 31 + idy;
         L.key[i] = h * 31 + ic;
         L.q[o] = ix / qp - ox;
         L.q[o + 1] = iy / qp - oy;
         L.q[o + 2] = iz / qp;
         L.q[o + 3] = ir / qp;
         float dx = idx / qd, dy = idy / qd, len = (float)Math.sqrt(dx * dx + dy * dy);
         L.q[o + 4] = len > 1e-4F ? dx / len : 0F;
         L.q[o + 5] = len > 1e-4F ? dy / len : 0F;
         // the cone's cos, lowered by what the direction's snapping can turn it (each component half a step) plus its own step
         L.q[o + 6] = L.d[k + 2] < -1.5F ? -2F : fine ? ic / qd : ic / qd - (len > 1e-4F ? 0.75F / qd / len : 2F) - 0.5F / qd;
      }
   }

   private static long skyMs = Long.MIN_VALUE / 2, lampScanMs = Long.MIN_VALUE / 2;
   private static float lampScanX = Float.NaN, lampScanY;
   private static float lastDay, lastCloud, lastSunElev; // the key light's inputs, last frame (stats)
   private static int lampN, lampsCell, lampsActive;
   private static final IsoLightSource[] lamps = new IsoLightSource[2048];
   private static final float[] candD = new float[2048];
   private static final IsoLightSource[] candL = new IsoLightSource[2048];

   private static final float WEAK = 0.3F; // a vehicle's taillights / brake lights (strength 0.2)

   private static void addTorch(Lights L, IsoGameCharacter.TorchInfo t, float ox, float oy, float cx, float cy, float view) {
      float tx = TorchSource.x(t), ty = TorchSource.y(t);
      if (t.id == 0 || Math.abs(tx - cx) > view + t.dist || Math.abs(ty - cy) > view + t.dist) {
         return;
      }
      float len = (float)Math.sqrt(t.angleX * t.angleX + t.angleY * t.angleY);
      if (len < 1e-4F) {
         return;
      }
      boolean car = t.id >= 4096 || t.focusing > 0;
      int k = L.n * 4;
      L.p[k] = tx - ox;
      L.p[k + 1] = ty - oy;
      L.p[k + 2] = (t.z + (car ? PixelLight.CAR_H : TorchSource.height(t, PixelLight.TORCH_H))) * METRIC_Z;
      L.p[k + 3] = Math.max(1F, TorchSource.reach(t, Math.max(1F, t.dist)));
      L.d[k] = t.angleX / len;
      L.d[k + 1] = t.angleY / len;
      L.d[k + 2] = t.cone ? t.dot : -2F;
      L.d[k + 3] = t.strength;
      L.c[k] = t.r;
      L.c[k + 1] = t.g;
      L.c[k + 2] = t.b;
      L.c[k + 3] = car ? 2F : 1F;
      L.n++;
   }

   /** The key light (sun / moon): the game thread (Sky.update writes what the sun shadows read too). */
   static void gatherSun(Lights L) {
      L.sun[3] = 0F;
      if (Config.GLINT_SUN) {
         ClimateManager cm = ClimateManager.getInstance();
         long nowMs = System.currentTimeMillis();
         if (nowMs - skyMs > 100L || nowMs < skyMs) { // the sun and the moon move slowly: 10 times a second is plenty
            skyMs = nowMs;
            Sky.update(-1F);
         }
         float day = cm != null ? Math.max(0F, Math.min(1F, cm.getDayLightStrength())) : 1F;
         float cloud = cm != null ? Math.max(0F, Math.min(1F, cm.getCloudIntensity())) : 0F;
         lastDay = day;
         lastCloud = cloud;
         lastSunElev = (float)Sky.sunElevDeg;
         float clear = 1F - 0.85F * cloud;
         if (Sky.sun[2] > 0.0 && day > 0.05F) {
            L.sun[0] = (float)Sky.sun[0];
            L.sun[1] = (float)Sky.sun[1];
            L.sun[2] = (float)Sky.sun[2];
            L.sun[3] = day * clear;
            L.sunC[0] = 1.0F;
            L.sunC[1] = 0.97F;
            L.sunC[2] = 0.9F;
         } else if (Sky.moon[2] > 0.05) {
            L.sun[0] = (float)Sky.moon[0];
            L.sun[1] = (float)Sky.moon[1];
            L.sun[2] = (float)Sky.moon[2];
            L.sun[3] = (float)(Sky.moonBrightness * 0.25) * clear;
            L.sunC[0] = 0.75F;
            L.sunC[1] = 0.82F;
            L.sunC[2] = 1.0F;
         }
      }
   }

   /**
    * The point lights (torches, headlights, lamps) and their keys: the worker under glintAsync. The torch list is rebuilt in
    * the frame's logic (LightingThread.update) and the cell's lamp list when lamps come and go, never during the render.
    */
   static void gatherPoints(Lights L, float ox, float oy, float cx, float cy, float view) {
      L.n = 0;
      if (!Config.GLINT_POINT) {
         return;
      }
      ArrayList<IsoGameCharacter.TorchInfo> torches = LightingJNI.pzoptTorches();
      // the torches and headlights first, the weak ones (taillights, brake lights) after the lamps: eight slots
      for (int i = 0; torches != null && i < torches.size() && L.n < MAX_LIGHTS; i++) {
         IsoGameCharacter.TorchInfo t = torches.get(i);
         if (t.strength >= WEAK) {
            addTorch(L, t, ox, oy, cx, cy, view);
         }
      }
      // the cell's lamps near the view, rescanned 4 times a second or after a 4-square camera move (the list holds the cell's every lamp)
      IsoCell cell = IsoWorld.instance.currentCell;
      long nowL = System.currentTimeMillis();
      if (nowL - lampScanMs > 250L || nowL < lampScanMs || Math.abs(cx - lampScanX) > 4F || Math.abs(cy - lampScanY) > 4F) {
         lampScanMs = nowL;
         lampScanX = cx;
         lampScanY = cy;
         lampN = 0;
         java.util.Stack<IsoLightSource> list = cell.getLamppostPositions();
         lampsCell = list.size();
         for (int i = 0; i < list.size() && lampN < lamps.length; i++) {
            IsoLightSource l = list.get(i);
            float r = Math.min(l.radius, 20);
            if (Math.abs(l.x + 0.5F - cx) <= view + r + 6F && Math.abs(l.y + 0.5F - cy) <= view + r + 6F) {
               lamps[lampN++] = l;
            }
         }
         for (int i = lampN; i < lamps.length && lamps[i] != null; i++) {
            lamps[i] = null;
         }
      }
      int nc = 0;
      lampsActive = 0;
      for (int i = 0; i < lampN && nc < candL.length; i++) {
         IsoLightSource l = lamps[i];
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
         lampsActive++;
      }
      while (L.n < MAX_LIGHTS && nc > 0) {
         int best = 0;
         for (int i = 1; i < nc; i++) {
            if (candD[i] < candD[best]) {
               best = i;
            }
         }
         IsoLightSource l = candL[best];
         candD[best] = candD[--nc];
         candL[best] = candL[nc];
         int k = L.n * 4;
         L.p[k] = l.x + 0.5F - ox;
         L.p[k + 1] = l.y + 0.5F - oy;
         // a lamp's bulb: near the top of its level when it stands on a floor (wall / room lamps); a street lamp's light sits
         // one level up in open air (gcv-dbg-lamps: z = 1 over a level-0 road), its bulb at that level's foot = the post's top
         IsoGridSquare ls = cell.getGridSquare(l.x, l.y, l.z);
         boolean hung = l.z > 0 && (ls == null || ls.getFloor() == null);
         L.p[k + 2] = (hung ? l.z + 0.05F : l.z + 0.9F) * METRIC_Z;
         L.p[k + 3] = Math.min(l.radius, 20);
         L.d[k] = 0F;
         L.d[k + 1] = 0F;
         L.d[k + 2] = -2F;
         L.d[k + 3] = 1F;
         L.c[k] = Math.min(1F, Math.max(0F, l.r * 2F));
         L.c[k + 1] = Math.min(1F, Math.max(0F, l.g * 2F));
         L.c[k + 2] = Math.min(1F, Math.max(0F, l.b * 2F));
         L.c[k + 3] = 0F;
         L.n++;
      }
      for (int i = 0; i < nc; i++) {
         candL[i] = null;
      }
      for (int i = 0; torches != null && i < torches.size() && L.n < MAX_LIGHTS; i++) {
         IsoGameCharacter.TorchInfo t = torches.get(i);
         if (t.strength < WEAK) {
            addTorch(L, t, ox, oy, cx, cy, view);
         }
      }
   }

   // ------------------------------------------------------------------------------------------------ the frame

   static final class Frame {
      final float[] data = new float[MAX * TEXELS * 4];
      int n;
      final Lights lights = new Lights();
      int model, pass;
      float zoom, jx, jy;
      int ts;
      int stamp;
      int ox, oy; // the frame origin (squares)
   }

   static final class Pass extends TextureDraw.GenericDrawer {
      Frame frame;
      boolean hdr;
      int seq; // the worker's job (0 = built on the game thread)

      @Override
      public void render() {
         if (!ready(seq)) {
            return;
         }
         Gpu.draw(this);
      }
   }

   private static final Frame[] FRAMES = {new Frame(), new Frame(), new Frame(), new Frame()};
   private static final Pass[] PASSES = new Pass[8];
   private static int frameIndex, passIndex, stampCounter;

   /**
    * Game thread, FBORenderCell after the players / items and before the puddles: this frame's masked squares in a light's
    * reach, queued as one draw. Nothing queued when there are none.
    */
   public static long queueNs, maskNs, lightsNs, pointNs, hdrQueued, noMaskFrames, queueFullNs, framesFull, bulkCopies;
   private static Frame queuedFrame;
   private static int queuedFrameNo = -1;

   /**
    * Game thread, FBORenderCell inside the HDR glint-only pass (after the wet blood's): this frame's glints once more, into
    * HdrGlint's target, so they reach past the 8-bit world's white in the HDR output. Nothing without HDR or glints this frame.
    */
   public static void queueHdr() {
      if (!on() || Config.GLINT_HDR_PCT <= 0 || !Hdr.active || Hdr.alphaGain || Hdr.tune.glint <= 0F) {
         return;
      }
      join(); // the worker's list finished (long since: the water drew in between)
      Frame f = queuedFrame;
      if (f == null || f.n == 0 || queuedFrameNo != IsoWorld.instance.getFrameNo() || Config.DEV_GLINT_VIEW != 0) {
         return;
      }
      Pass p = PASSES[passIndex++ & 7];
      if (p == null) {
         p = PASSES[(passIndex - 1) & 7] = new Pass();
      }
      p.frame = f;
      p.hdr = true;
      p.seq = 0;
      hdrQueued++;
      String sec = Ground.section("ground.glints.hdr");
      GpuSections.begin(sec);
      SpriteRenderer.instance.drawGeneric(p);
      GpuSections.end(sec);
   }

   public static void queue(int playerIndex, java.util.List<IsoChunk> chunks) {
      WindLeaves.queue(playerIndex); // the ground cover's wind-blown leaves draw at this spot too
      join(); // a job still out (split screen: the other player's; a frame whose end hook did not run)
      if (!on() || chunks == null) {
         return;
      }
      long t0 = System.nanoTime();
      String variant = Ground.variant();
      try {
         build(playerIndex, chunks, variant);
      } catch (RuntimeException e) {
         failed = true;
         Log.warn("glints: frame build failed, glints off: " + e);
      }
      long dt = System.nanoTime() - t0;
      queueNs += dt;
      vstat(variant)[0] += dt;
      vstat(variant)[1]++;
      if ("qfull".equals(variant)) { // dev: the per-square caches' A/B, game-thread side
         queueFullNs += dt;
         framesFull++;
      }
   }

   /** Game thread, FBORenderCell at the end of the level loop: the frame's glint list finished before the frame is handed over. */
   public static void endFrame() {
      join();
   }

   // ------------------------------------------------------------------------------------------------ the worker (glintAsync)

   /** One frame's build: what the chunk walk reads, snapshotted on the game thread. */
   static final class Job {
      int playerIndex, camCX, camCY, ox, oy, seq;
      float wet, camZ, cx, cy, view;
      boolean snowAny;
      long now;
      String variant;
      Frame f;
      java.util.List<IsoChunk> list; // the synchronous build walks the game's list
      IsoChunk[] chunks = new IsoChunk[512]; // the worker walks a copy
      int nChunks;
      long workNs;
      int softLeft; // soft mask rebuilds left this frame
   }

   private static final Job SYNC_JOB = new Job(), ASYNC_JOB = new Job();
   private static Thread worker;
   private static volatile Job job; // the job the worker is to run
   private static volatile int doneSeq; // the last job the worker finished
   private static volatile Throwable workerFailure;
   private static int startedSeq;
   private static Job pending; // game thread: dispatched, not joined
   private static boolean asyncFailed;
   public static long levelsWalked, levelsRelit, softDeferred, joinMaxNs, dispatchNs, asyncJobs, joinNs, joinWaits, workerNs, renderWaits, syncJobs;
   private static final java.util.HashMap<String, long[]> VSTAT = new java.util.HashMap<>(); // per dev variant: game-thread ns, frames, worker ns, jobs

   /** Per dev variant: game-thread us a build (queue + join), worker us a job. */
   static String vstats() {
      StringBuilder sb = new StringBuilder();
      for (java.util.Map.Entry<String, long[]> e : VSTAT.entrySet()) {
         long[] v = e.getValue();
         sb.append(String.format(java.util.Locale.ROOT, " gt_%s=%.1f wk_%s=%.1f", e.getKey(), v[1] > 0 ? v[0] / 1000.0 / v[1] : 0.0, e.getKey(), v[3] > 0 ? v[2] / 1000.0 / v[3] : 0.0));
      }
      return sb.toString();
   }

   static long[] vstat(String variant) {
      return VSTAT.computeIfAbsent(variant == null ? "-" : variant, k -> new long[4]);
   }

   private static boolean asyncUsable(String variant) {
      return Config.GLINT_ASYNC && !asyncFailed && !"qsync".equals(variant) && Config.DEV_GLINT_VIEW != 1;
   }

   private static void dispatch(Job j) {
      if (worker == null) {
         worker = new Thread(Glints::loop, "pzopt-glints");
         worker.setDaemon(true);
         worker.start();
      }
      j.seq = ++startedSeq;
      if (j.seq == 0) {
         j.seq = ++startedSeq; // 0 marks a synchronous frame
      }
      asyncJobs++;
      pending = j;
      job = j;
      java.util.concurrent.locks.LockSupport.unpark(worker);
   }

   private static void loop() {
      while (true) {
         Job j = job;
         if (j == null) {
            java.util.concurrent.locks.LockSupport.park();
            continue;
         }
         CorePlacement.frameCritical(); // macOS: workerQosMac
         long t0 = System.nanoTime();
         try {
            fill(j);
         } catch (Throwable t) {
            workerFailure = t;
            j.f.n = 0;
         }
         j.workNs = System.nanoTime() - t0;
         job = null;
         doneSeq = j.seq;
      }
   }

   /** Game thread: the dispatched job finished (waits for it if needed) and its frame's counters landed. */
   static void join() {
      Job j = pending;
      if (j == null) {
         return;
      }
      pending = null;
      long t0 = System.nanoTime();
      if (doneSeq != j.seq) {
         joinWaits++;
         int spins = 0;
         while (doneSeq != j.seq) {
            if (++spins < 200) {
               Thread.onSpinWait();
            } else {
               ShortWait.pause(20_000L);
               if (System.nanoTime() - t0 > 200_000_000L) {
                  asyncFailed = true; // the worker is lost: the game thread builds from now on (its own job object)
                  Log.warn("glints: the worker did not finish a frame in 200 ms, the game thread builds the glints from now on");
                  j.f.n = 0;
                  break;
               }
            }
         }
      }
      long dt = System.nanoTime() - t0;
      joinNs += dt;
      joinMaxNs = Math.max(joinMaxNs, dt);
      long[] v = vstat(j.variant);
      v[0] += dt;
      v[2] += j.workNs;
      v[3]++;
      workerNs += j.workNs;
      Throwable t = workerFailure;
      if (t != null) {
         workerFailure = null;
         asyncFailed = true;
         Log.warn("glints: the worker's build failed, the game thread builds the glints from now on: " + t);
      }
      land(j.f);
   }

   private static void land(Frame f) {
      instances += f.n;
      maxInstances = Math.max(maxInstances, f.n);
      if (f.n > 0) {
         framesQueued++;
      }
   }

   private static void build(int playerIndex, java.util.List<IsoChunk> chunks, String variant) {
      frames++;
      int frameNo = IsoWorld.instance.getFrameNo();
      int model = model();
      int passMode = passMode();
      if (model < 0) {
         GpuSections.begin(section(-1));
         GpuSections.end(section(-1));
         return;
      }
      zombie.iso.PlayerCamera camera = IsoCamera.cameras[playerIndex];
      float zoom = camera.zoom;
      int ts = Core.tileScale;
      float cx = IsoCamera.frameState.camCharacterX, cy = IsoCamera.frameState.camCharacterY;
      float camZ = IsoCamera.frameState.camCharacterZ;
      float view = (camera.offscreenWidth + 2F * camera.offscreenHeight) * zoom / (64F * ts) + 4F;
      // the frame origin: a multiple of 64 squares (the shader's world positions stay small)
      int ox = (int)Math.floor(cx / 64F) * 64, oy = (int)Math.floor(cy / 64F) * 64;
      float wet = zombie.iso.IsoPuddles.getInstance().getWetGroundFinalValue();
      boolean snowAny = IsoWorld.instance.currentCell.getSnowTarget() > 0;
      // nothing can carry a mask (no frost potential, no snow, a dry road, nothing forced): no light gathering, no walk over the chunks
      if (Config.DEV_GLINT_FORCE.isEmpty() && !snowAny && wet <= 0.05F && Config.DEV_GLINT_VIEW != 1
            && !(GroundCover.on() && Config.GROUND_COVER_FROST && GroundCover.season.frost > 0F)) {
         noMaskFrames++;
         return;
      }
      Frame f = FRAMES[frameIndex++ & 3];
      Lights L = f.lights;
      long tl = System.nanoTime();
      gatherSun(L);
      L.n = 0;
      lightsNs += System.nanoTime() - tl;
      if (L.sun[3] > 0F) {
         sunFrames++;
      }
      // no key light and no point light anywhere (no torch, no lamp in the cell): no glint anywhere
      if (L.sun[3] <= 0F && Config.DEV_GLINT_VIEW != 1 && (!Config.GLINT_POINT
            || LightingJNI.pzoptTorches().isEmpty() && IsoWorld.instance.currentCell.getLamppostPositions().isEmpty())) {
         return;
      }
      boolean async = asyncUsable(variant);
      Job j = async ? ASYNC_JOB : SYNC_JOB;
      j.playerIndex = playerIndex;
      j.camCX = zombie.core.math.PZMath.fastfloor(cx / 8F);
      j.camCY = zombie.core.math.PZMath.fastfloor(cy / 8F);
      j.camZ = camZ;
      j.ox = ox;
      j.oy = oy;
      j.wet = wet;
      j.snowAny = snowAny;
      j.now = System.currentTimeMillis();
      j.variant = variant;
      j.cx = cx;
      j.cy = cy;
      j.view = view;
      j.f = f;
      f.n = 0;
      f.model = model;
      f.pass = passMode;
      f.zoom = zoom;
      f.ts = ts;
      f.jx = camera.fixJigglyModelsX * zoom;
      f.jy = camera.fixJigglyModelsY * zoom;
      f.stamp = ++stampCounter;
      f.ox = ox;
      f.oy = oy;
      if (!async) {
         j.list = chunks;
         syncJobs++;
         fill(j);
         land(f);
         if (f.n > 0) {
            queuePass(f, 0, frameNo, model, passMode);
         }
         return;
      }
      int n = Math.min(chunks.size(), 4096);
      if (j.chunks.length < n) {
         j.chunks = new IsoChunk[Math.max(n, j.chunks.length * 2)];
      }
      for (int i = 0; i < n; i++) {
         j.chunks[i] = chunks.get(i);
      }
      for (int i = n; i < j.nChunks; i++) {
         j.chunks[i] = null;
      }
      j.nChunks = n;
      j.list = null;
      // the draw goes in the list now (its spot under the puddles); its data is the worker's, finished by the frame's end
      long td = System.nanoTime();
      dispatch(j);
      dispatchNs += System.nanoTime() - td;
      queuePass(f, j.seq, frameNo, model, passMode);
   }

   private static void queuePass(Frame f, int seq, int frameNo, int model, int passMode) {
      Pass p = PASSES[passIndex++ & 7];
      if (p == null) {
         p = PASSES[(passIndex - 1) & 7] = new Pass();
      }
      p.frame = f;
      p.hdr = false;
      p.seq = seq;
      queuedFrame = f;
      queuedFrameNo = frameNo;
      String sec = section(model, passMode);
      GpuSections.begin(sec);
      SpriteRenderer.instance.drawGeneric(p);
      GpuSections.end(sec);
   }

   /** Render thread: the worker's frame finished (the game thread joins it before the hand-over: this only guards). */
   static boolean ready(int seq) {
      if (seq == 0 || doneSeq - seq >= 0) {
         return true;
      }
      renderWaits++;
      long t0 = System.nanoTime();
      while (doneSeq - seq < 0) {
         if (System.nanoTime() - t0 > 50_000_000L) {
            return false;
         }
         Thread.onSpinWait();
      }
      return true;
   }

   /** The square's vertex light as lit (pixelLight's white during a bake left out: the worker may read it mid-bake). */
   static int vertLight(IsoGridSquare sq, int corner, int playerIndex) {
      IsoGridSquare.ILighting l = sq.lighting[playerIndex];
      if (l instanceof zombie.iso.LightingJNI.JNILighting jl) {
         return jl.pzoptVert(corner);
      }
      return l == null ? 0 : l.lightverts(corner);
   }

   /** The chunk walk: the masked squares in a light's reach into the job's frame (the worker, or the game thread). */
   private static void fill(Job j) {
      Frame f = j.f;
      Lights L = f.lights;
      int playerIndex = j.playerIndex, camCX = j.camCX, camCY = j.camCY, ox = j.ox, oy = j.oy;
      float camZ = j.camZ, wet = j.wet;
      boolean snowAny = j.snowAny;
      long now = j.now;
      String variant = j.variant; // dev cycle "qfull": light bits and square light worked out every frame (the cache's A/B)
      java.util.List<IsoChunk> list = j.list;
      int nc = list != null ? list.size() : j.nChunks;
      f.n = 0;
      j.softLeft = SOFT_BUDGET;
      long tl = System.nanoTime();
      gatherPoints(L, ox, oy, j.cx, j.cy, j.view);
      snapLights(L, ox, oy, "q64".equals(variant));
      pointNs += System.nanoTime() - tl;
      if (L.n > 0) {
         lightFrames++;
      }
      if (Config.DEV_GLINT_VIEW == 1 && frames == 600) { // dev: the lights of one frame (gating checks)
         StringBuilder sb = new StringBuilder("glints: lights at frame 600, camera " + j.cx + "," + j.cy + " origin " + ox + "," + oy + ":");
         for (int i = 0; i < L.n; i++) {
            sb.append(String.format(java.util.Locale.ROOT, " [%d] p=%.1f,%.1f,%.2f r=%.1f cone=%.2f s=%.2f c=%.2f,%.2f,%.2f w=%.0f", i, L.p[i * 4] + ox, L.p[i * 4 + 1] + oy,
                  L.p[i * 4 + 2], L.p[i * 4 + 3], L.d[i * 4 + 2], L.d[i * 4 + 3], L.c[i * 4], L.c[i * 4 + 1], L.c[i * 4 + 2], L.c[i * 4 + 3]));
         }
         Log.info(sb.append(" sun=").append(L.sun[3]).toString());
      }
      if (L.n == 0 && L.sun[3] <= 0F && Config.DEV_GLINT_VIEW != 1) {
         return; // no light reaches the view: no glint anywhere
      }
      float[] d = f.data;
      for (int ci = 0; ci < nc && f.n < MAX; ci++) {
         IsoChunk c = list != null ? list.get(ci) : j.chunks[ci];
         if (c == null) {
            continue;
         }
         zombie.iso.fboRenderChunk.FBORenderLevels levels = c.getRenderLevels(playerIndex);
         ChunkMasks cms = null;
         for (int level = c.minLevel; level <= c.maxLevel && f.n < MAX; level++) {
            if (camZ < 0F && level > zombie.core.math.PZMath.fastfloor(camZ) || !levels.isOnScreen(level)) {
               continue;
            }
            if (cms == null) {
               cms = masks(c, now);
            }
            LevelMask m = mask(c, cms, level, now, wet, snowAny, j);
            if (m == null || m.bits == 0L) {
               continue;
            }
            zombie.iso.fboRenderChunk.FBORenderCutaways.ChunkLevelData cut = c.getCutawayDataForLevel(level);
            if (cut == null) {
               continue;
            }
            byte[] flags = cut.squareFlags[playerIndex];
            float chunkDepth = IsoDepthHelper.getChunkDepthData(camCX, camCY, c.wx, c.wy, level).depthStart;
            // the lights that reach the chunk level at all (its 8 x 8 box + reach): the squares test only those
            int cb = 0, full = 0;
            float bx0 = c.wx * 8 - ox, by0 = c.wy * 8 - oy, pz = level * METRIC_Z;
            for (int li = 0; li < L.n; li++) {
               int k = li * 8; // the snapped light (Lights.q): what the cached bits were worked out from
               float qx = Math.max(bx0, Math.min(bx0 + 8F, L.q[k])) - L.q[k], qy = Math.max(by0, Math.min(by0 + 8F, L.q[k + 1])) - L.q[k + 1];
               float r = L.q[k + 3] + 0.75F + L.slackR + L.slackPos;
               // a light lights its own level and the levels under it within its reach (a street lamp's light sits a level up)
               if (qx * qx + qy * qy <= r * r && L.q[k + 2] >= pz - METRIC_Z * 0.5F && L.q[k + 2] - pz <= METRIC_Z * 2.5F) {
                  cb |= 1 << li;
                  if (!"qfull".equals(variant) && coversChunk(L, k, bx0, by0)) {
                     full |= 1 << li; // every square of the chunk level: no per-square test for this light
                  }
               }
            }
            boolean sunOn = L.sun[3] > 0F;
            if (cb == 0 && !sunOn && Config.DEV_GLINT_VIEW != 1) {
               continue;
            }
            float[] blk = m.block;
            long bits = m.bits;
            // per square light bits are kept while the lights reaching this chunk level stay put (lamps, a parked car);
            // the squares' light (the darkness gate) is re-read every LUM_MS (gcv-cost-wetnight: 160 us a frame for 2840
            // squares when both were worked out every frame)
            int lk = cb;
            for (int rest = cb; rest != 0; rest &= rest - 1) {
               int li = Integer.numberOfTrailingZeros(rest);
               lk = lk * 31 + li * 1000003 + L.key[li];
            }
            boolean lightsSame = !"qfull".equals(variant) && m.lightKeyOk && m.lightKey == lk;
            levelsWalked++;
            if (!lightsSame) {
               levelsRelit++;
            }
            int lstag = ((c.wx * 5 + c.wy * 11) & 15) * 6;
            boolean lumFresh = true;
            if (m.lumPlayer != playerIndex || now - m.lumMs > LUM_MS + lstag || now < m.lumMs || "qfull".equals(variant)) {
               m.lumMs = now;
               m.lumPlayer = playerIndex;
               m.lumValid = 0L;
               lumFresh = false;
            }
            // the whole chunk level's records as last frame when nothing they hold changed: one copy
            long visible = 0L;
            for (long vb = bits; vb != 0L; vb &= vb - 1L) {
               int s = Long.numberOfTrailingZeros(vb);
               if ((flags[s] & 1) != 0) {
                  visible |= 1L << s;
               }
            }
            if (lightsSame && lumFresh && m.outOk && m.outVisible == visible && m.outDepth == chunkDepth && m.outOx == ox
                  && m.outOy == oy && m.outSun == sunOn && f.n + m.outN <= MAX && Config.DEV_GLINT_VIEW != 1) {
               System.arraycopy(m.out, 0, d, f.n * TEXELS * 4, m.outN * TEXELS * 4);
               f.n += m.outN;
               pointSquares += m.outPoint;
               sunSquares += m.outSunN;
               bulkCopies++;
               continue;
            }
            int startN = f.n;
            long p0 = pointSquares, s0 = sunSquares;
            boolean complete = true;
            while (bits != 0L) {
               if (f.n >= MAX) {
                  complete = false;
                  break;
               }
               int s = Long.numberOfTrailingZeros(bits);
               bits &= bits - 1L;
               int x = c.wx * 8 + (s & 7), y = c.wy * 8 + (s >> 3);
               int lb;
               if (lightsSame) {
                  lb = m.lbits[s] & 255;
               } else {
                  lb = full | squareLights(L, cb & ~full, x + 0.5F - ox, y + 0.5F - oy);
                  m.lbits[s] = (byte)lb;
               }
               if ((flags[s] & 1) == 0) {
                  continue;
               }
               boolean sun = sunOn && (m.sun[s] & 255) > 8;
               if (lb != 0) {
                  pointSquares++;
               }
               if (sun) {
                  sunSquares++;
               }
               if (lb == 0 && !sun && Config.DEV_GLINT_VIEW != 1) {
                  continue;
               }
               // the square's light gates the point lights only (an unseen square shows none): read it only for them
               int lum255 = 255;
               if (lb != 0) {
                  if ((m.lumValid & 1L << s) != 0L) {
                     lum255 = m.lum[s] & 255;
                  } else {
                     float lum = 1F;
                     IsoGridSquare sq = c.getGridSquare(s & 7, s >> 3, level);
                     if (sq != null) {
                        int l0 = vertLight(sq, 0, playerIndex), l2 = vertLight(sq, 2, playerIndex);
                        lum = Math.min(1F, 0.5F * (luma(l0) + luma(l2)) * 2.5F);
                     }
                     lum255 = Math.round(lum * 255F);
                     m.lum[s] = (byte)lum255;
                     m.lumValid |= 1L << s;
                  }
               }
               int o = f.n * TEXELS * 4;
               System.arraycopy(blk, s * TEXELS * 4, d, o, TEXELS * 4);
               d[o + 2] += chunkDepth;
               d[o + 3] += lb << 10;
               d[o + 4] = x - ox;
               d[o + 5] = y - oy;
               d[o + 7] += lum255 << 8;
               f.n++;
            }
            m.lightKey = lk;
            m.lightKeyOk = complete;
            m.outOk = complete;
            if (complete) {
               m.outN = f.n - startN;
               System.arraycopy(d, startN * TEXELS * 4, m.out, 0, m.outN * TEXELS * 4);
               m.outVisible = visible;
               m.outDepth = chunkDepth;
               m.outOx = ox;
               m.outOy = oy;
               m.outSun = sunOn;
               m.outPoint = (int)(pointSquares - p0);
               m.outSunN = (int)(sunSquares - s0);
            }
         }
      }
   }

   /**
    * Light k covers the whole 8 x 8 chunk box (bx0, by0): its four corners within the reach and, for a cone narrower than a
    * half plane (convex), inside it; every square centre then passes squareLights' test.
    */
   static boolean coversChunk(Lights L, int k, float bx0, float by0) {
      float r = L.q[k + 3];
      float c = L.q[k + 6];
      boolean cone = c > -1.5F;
      if (cone && c <= 0F) {
         return false;
      }
      for (int i = 0; i < 4; i++) {
         float dx = bx0 + ((i & 1) != 0 ? 8F : 0F) - L.q[k], dy = by0 + ((i & 2) != 0 ? 8F : 0F) - L.q[k + 1];
         float d2 = dx * dx + dy * dy;
         if (d2 > r * r) {
            return false;
         }
         if (cone) {
            float len = (float)Math.sqrt(d2);
            if (len > 1e-4F && (dx * L.q[k + 4] + dy * L.q[k + 5]) / len < c) {
               return false;
            }
         }
      }
      return true;
   }

   /**
    * Which of the chunk level's lights (cb) reach a square's centre (px, py, frame-origin squares): the snapped light's reach +
    * half a diagonal + the snapping's slack; a cone with a square's half-width of slack (+ the snapped position's).
    */
   static int squareLights(Lights L, int cb, float px, float py) {
      int lb = 0;
      float sp = L.slackPos, sr = L.slackR;
      for (int rest = cb; rest != 0; rest &= rest - 1) {
         int li = Integer.numberOfTrailingZeros(rest);
         int k = li * 8;
         float dx = px - L.q[k], dy = py - L.q[k + 1];
         float r = L.q[k + 3] + 0.75F + sr + sp;
         float d2 = dx * dx + dy * dy;
         if (d2 > r * r) {
            continue;
         }
         float c = L.q[k + 6];
         if (c > -1.5F) { // a cone: the square's centre inside it (a square's half-width of slack)
            float len = (float)Math.sqrt(d2);
            float slack = 0.75F + sp;
            if (len > slack && (dx * L.q[k + 4] + dy * L.q[k + 5]) / len < c - slack / len) {
               continue;
            }
         }
         lb |= 1 << li;
      }
      return lb;
   }

   static float luma(int abgr) {
      float r = (abgr & 255) / 255F, g = (abgr >> 8 & 255) / 255F, b = (abgr >> 16 & 255) / 255F;
      return 0.2126F * r + 0.7152F * g + 0.0722F * b;
   }

   // ------------------------------------------------------------------------------------------------ render thread

   static final String VERT = String.join("\n",
         "#version 330",
         "uniform sampler2D uData;",
         "vec4 pzgTexel(int i) { return texelFetch(uData, ivec2(i & 4095, i >> 12), 0); }",
         "uniform ivec2 uOrigin; // the frame origin (squares, a multiple of 64)",
         "void pzgSquare(int sq, out vec4 a, out vec4 b, out vec4 c, out vec4 e) {",
         "   vec4 t0 = pzgTexel(sq * 2), t1 = pzgTexel(sq * 2 + 1);",
         "   int P = int(t0.w + 0.5), SL = int(t1.w + 0.5);",
         "   int lv = (P >> 2) & 255;",
         "   ivec2 X = ivec2(floor(t1.xy + 0.5)) + uOrigin;",
         "   a = vec4(t0.xyz, float(lv > 127 ? lv - 256 : lv));",
         "   b = vec4(t1.xy, float(((X.x - X.y) * 32) & 4095), float(((X.x + X.y) * 16) & 4095)); // grid space s, t wrapped at 4096",
         "   c = vec4(float(P & 3), t1.z, float(SL & 255) / 255.0, float(SL >> 8) / 255.0);",
         "   e = vec4(float(P >> 10), float(lv), 0.0, 0.0);",
         "}",
         "uniform mat4 uMVP;",
         "uniform vec4 uJ;    // jiggle x, y, K per unit x + y, bias",
         "uniform float uTs;",
         "out vec2 vG;",
         "out vec3 vP;",
         "flat out vec4 vM;   // kind, strength, sun visibility, light gate",
         "flat out ivec2 vB;  // light bits, level",
         "void main() {",
         "   int i = gl_InstanceID;",
         "   vec4 a, b, c, e;",
         "   pzgSquare(i, a, b, c, e);",
         "   int v = gl_VertexID;",
         "   vec2 o = v == 0 ? vec2(0.0) : v == 1 ? vec2(1.0, 0.0) : v == 2 ? vec2(1.0) : vec2(0.0, 1.0);",
         "   vec2 scr = a.xy + vec2((o.x - o.y) * 32.0 * uTs, (o.x + o.y) * 16.0 * uTs);",
         "   gl_Position = uMVP * vec4(scr + uJ.xy, 0.0, 1.0);",
         "   float depth = a.z - (o.x + o.y) * uJ.z - uJ.w;",
         "   gl_Position.z = (depth * 2.0 - 1.0) * gl_Position.w;",
         "   vG = b.zw + vec2((o.x - o.y) * 32.0, (o.x + o.y) * 16.0);",
         "   vP = vec3(b.xy + o, a.w * 2.4494897);",
         "   vM = c;",
         "   vB = ivec2(int(e.x + 0.5), int(e.y + 0.5));",
         "}");

   /**
    * glintPass=particles: the glinting flakes alone, as points (one vertex a flake slot, {@link #SLOTS} a square). Deliot and
    * Belcour's counting at the scale of a square: for each light the half vector's slope falls between four angular cells of
    * width b; each cell's number of facets is a binomial draw (pzgBinom: N = facets a square, p = P22 x b^2) hashed from the
    * square and the cell, so a cell's count never changes while the light stays in it and the bilinear weights fade glints in
    * and out as it moves. Up to four of a cell's facets are drawn (more are folded into their energy), each at its own
    * hashed spot in the square, as a 2 x 2 pixel tent (bilinear splat: the energy is kept as the camera pans by sub-pixels).
    * Energy: the square's smooth reflection (area x F D / (4 cos^4 V.z) E) shared by the facets. A slot with nothing to draw
    * leaves the clip volume (no fragment).
    */
   static final String FLAKE = String.join("\n",
         "uniform sampler2D uData;",
         "vec4 pzgTexel(int i) { return texelFetch(uData, ivec2(i & 4095, i >> 12), 0); }",
         "uniform ivec2 uOrigin; // the frame origin (squares, a multiple of 64)",
         "void pzgSquare(int sq, out vec4 a, out vec4 b, out vec4 c, out vec4 e) {",
         "   vec4 t0 = pzgTexel(sq * 2), t1 = pzgTexel(sq * 2 + 1);",
         "   int P = int(t0.w + 0.5), SL = int(t1.w + 0.5);",
         "   int lv = (P >> 2) & 255;",
         "   ivec2 X = ivec2(floor(t1.xy + 0.5)) + uOrigin;",
         "   a = vec4(t0.xyz, float(lv > 127 ? lv - 256 : lv));",
         "   b = vec4(t1.xy, float(((X.x - X.y) * 32) & 4095), float(((X.x + X.y) * 16) & 4095)); // grid space s, t wrapped at 4096",
         "   c = vec4(float(P & 3), t1.z, float(SL & 255) / 255.0, float(SL >> 8) / 255.0);",
         "   e = vec4(float(P >> 10), float(lv), 0.0, 0.0);",
         "}",
         "uniform mat4 uMVP;",
         "uniform vec4 uJ;    // jiggle x, y, K per unit x + y, bias",
         "uniform float uTs;",
         "uniform vec4 uLp[" + MAX_LIGHTS + "];",
         "uniform vec4 uLd[" + MAX_LIGHTS + "];",
         "uniform vec4 uLc[" + MAX_LIGHTS + "];",
         "uniform vec4 uSun;",
         "uniform vec4 uSunC;",
         "uniform vec4 uKind[3];",
         "uniform vec4 uView; // footprint (grid units per pixel), brightness, dev view, square area in screen px",
         "const vec3 V = vec3(0.61237244, 0.61237244, 0.5);",
         "// one flake slot: its clip position and energy (rgb; 0 = nothing to draw)",
         "bool pzgFlake(int vid, out vec4 clip, out vec3 energy) {",
         "   int sq = vid / " + SLOTS + ", k = vid - sq * " + SLOTS + ";",
         "   int li = k >> 4, cell = (k >> 2) & 3, fi = k & 3;",
         "   vec4 a, b, c, e;",
         "   pzgSquare(sq, a, b, c, e);",
         "   int bits = int(e.x + 0.5), level = int(e.y + 0.5);",
         "   int kind = int(c.x + 0.5);",
         "   vec4 kp = uKind[kind];",
         "   // the slot's light: 0 = the key light (when it reaches the square), then the point lights by bit",
         "   int slot = li, light = -2;",
         "   bool sun = uSun.w > 0.0 && c.z > 0.03;",
         "   if (sun) { if (slot == 0) light = -1; else slot -= 1; }",
         "   if (light == -2) {",
         "      for (int i = 0; i < " + MAX_LIGHTS + "; i++) {",
         "         if ((bits & (1 << i)) == 0) continue;",
         "         if (slot == 0) { light = i; break; }",
         "         slot--;",
         "      }",
         "   }",
         "   if (light == -2) return false;",
         "   if (uView.z == 1.0) { // dev: one dot a square and light, where the flakes would be (positions, depth, gating)",
         "      if (cell != 0 || fi != 0) return false;",
         "      energy = light == -1 ? vec3(4.0, 3.2, 0.0) : vec3(0.0, 2.4, 4.0);",
         "      vec2 o = vec2(0.5);",
         "      vec2 scr = a.xy + vec2((o.x - o.y) * 32.0 * uTs, (o.x + o.y) * 16.0 * uTs);",
         "      clip = uMVP * vec4(scr + uJ.xy, 0.0, 1.0);",
         "      clip.z = clamp((a.z - (o.x + o.y) * uJ.z - uJ.w) * 2.0 - 1.0, -1.0, 1.0) * clip.w;",
         "      return true;",
         "   }",
         "   vec3 P = vec3(b.xy + 0.5, a.w * 2.4494897);",
         "   vec3 L, E;",
         "   if (light == -1) { L = uSun.xyz; E = uSunC.rgb * (uSun.w * c.z); }",
         "   else {",
         "      vec3 d = uLp[light].xyz - P;",
         "      float r = length(d), hr = length(d.xy);",
         "      L = d / max(r, 1e-4);",
         "      float att = clamp(1.0 - hr / uLp[light].w, 0.0, 1.0);",
         "      if (uLd[light].z > -1.5) {",
         "         float cs = hr > 1e-3 ? dot(-d.xy / hr, uLd[light].xy) : 1.0;",
         "         att *= smoothstep(uLd[light].z - 0.04, uLd[light].z + 0.04, cs);",
         "      }",
         "      E = uLc[light].rgb * (uLd[light].w * att * c.w * smoothstep(0.15, 0.4, c.w) * 1.6); // squares the game shows dark (ambient only) glint nothing",
         "   }",
         "   if (L.z <= 0.0 || dot(E, vec3(1.0)) <= 0.0) return false;",
         "   vec3 H = normalize(L + V);",
         "   vec2 m = H.xy / H.z;",
         "   float c2 = H.z * H.z;",
         "   float F = kp.w + (1.0 - kp.w) * pow(1.0 - clamp(dot(L, H), 0.0, 1.0), 5.0);",
         "   vec3 lobe = E * (F / (c2 * c2) / (4.0 * V.z)) * uView.w * c.y * uView.y; // the square's smooth reflection / P22",
         "#ifdef PZG_SMOOTH",
         "   if (cell != 0 || fi != 0) return false; // the rig's reference: the smooth NDF's energy, one slot a light",
         "   energy = lobe * pzgP22(m, kp.y); clip = vec4(0.0); return true;",
         "#endif",
         "   float bw = kp.z;",
         "   vec2 q = m / bw - 0.5;",
         "   vec2 c0 = floor(q), f = q - c0;",
         "   ivec2 off = ivec2(cell & 1, cell >> 1);",
         "   vec2 cc = c0 + vec2(off);",
         "   float w = (off.x == 1 ? f.x : 1.0 - f.x) * (off.y == 1 ? f.y : 1.0 - f.y);",
         "   float N = kp.x * 1024.0; // facets a square (1024 grid units^2)",
         "   float p = pzgP22((cc + 0.5) * bw, kp.y) * bw * bw;",
         "   if (w <= 0.0 || N * p < 1e-4) return false;",
         "   ivec2 gs = ivec2(b.zw + 0.5);",
         "   vec4 u = pzgRand4(ivec4(gs, int(cc.x) * 977 + int(cc.y) * 131 + kind * 7 + level * 71, " + "1013));",
         "   float n = pzgBinom(N, p, u.xy);",
         "   if (float(fi) >= n) return false;",
         "   float share = max(n, 4.0) / 4.0; // a cell with more than four facets: the four drawn carry the rest's energy",
         "   vec4 v = pzgRand4(ivec4(gs, int(cc.x) * 31 + int(cc.y) * 17 + fi * 5 + kind, " + "4099 + level));",
         "   vec2 o = v.xy;",
         "   // the square's discrete P22 is (sum of w n) / (N b^2); its reflection over the square's screen area, one facet's share",
         "   energy = lobe * (w * share / (N * bw * bw));",
         "   vec2 scr = a.xy + vec2((o.x - o.y) * 32.0 * uTs, (o.x + o.y) * 16.0 * uTs);",
         "   clip = uMVP * vec4(scr + uJ.xy, 0.0, 1.0);",
         "   float depth = a.z - (o.x + o.y) * uJ.z - uJ.w;",
         "   clip.z = clamp(depth * 2.0 - 1.0, -1.0, 1.0) * clip.w; // clamped here: GL_DEPTH_CLAMP on points cost 50 us (gcv-cost-state)",
         "   return true;",
         "}");

   static final String PVERT = String.join("\n",
         "#version 330",
         GlintGlsl.LIB,
         FLAKE,
         "flat out vec3 vE;",
         "void main() {",
         "   vec4 clip; vec3 en;",
         "   if (!pzgFlake(gl_VertexID, clip, en)) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); gl_PointSize = 1.0; vE = vec3(0.0); return; }",
         "   gl_Position = clip;",
         "   gl_PointSize = 2.0;",
         "   vE = uView.z == 3.0 ? en * 20.0 : en;",
         "}");

   static final String PFRAG = String.join("\n",
         "#version 330",
         "flat in vec3 vE;",
         "#ifdef PZG_HDR",
         "layout(location = 0) out vec4 fragColor;",
         "layout(location = 1) out vec4 pzgHdrOut; // HdrGlint's target: g / (1 + g), the finished pixel's luminance",
         "uniform sampler2D pzHdrNow;",
         "uniform vec4 pzHdrNowP;",
         "uniform float uHdrK;",
         "void pzgWrite(vec3 c) {",
         "   vec3 g = c * uHdrK;",
         "   if (dot(g, vec3(1.0)) <= 1e-5) discard; // never overwrite the water's / puddles' / wet blood's glint with nothing",
         "   fragColor = vec4(0.0);",
         "   pzgHdrOut = vec4(g / (1.0 + g), 0.003 + 0.997 * dot(texture(pzHdrNow, gl_FragCoord.xy * pzHdrNowP.zw).rgb, vec3(0.2126, 0.7152, 0.0722)));",
         "}",
         "#else",
         "out vec4 fragColor;",
         "void pzgWrite(vec3 c) { fragColor = vec4(c, 0.0); }",
         "#endif",
         "void main() {",
         "   // a 2 x 2 bilinear splat: the point's four pixels share the energy by their distance to its centre",
         "   vec2 d = abs(gl_PointCoord - 0.5) * 2.0;",
         "   vec2 t = max(1.0 - d, 0.0);",
         "   pzgWrite(vE * (t.x * t.y));",
         "}");

   static final int COMPACT_MAX = 262144;

   /** A fragment source's HDR variant: the glint into HdrGlint's target (attachment 1) instead of the world colour. */
   static String hdr(String frag) {
      int nl = frag.indexOf('\n');
      return frag.substring(0, nl + 1) + "#define PZG_HDR 1\n" + frag.substring(nl + 1);
   }

   /** Every glint program's sources by name (GroundCoverTest links them and dumps them for harness/ground/cover/glint_rig.py). */
   static java.util.LinkedHashMap<String, String[]> sources() {
      java.util.LinkedHashMap<String, String[]> m = new java.util.LinkedHashMap<>();
      for (int i = 0; i < MODELS.length; i++) {
         m.put("diamonds-" + MODELS[i], new String[] {VERT, frag(i)});
      }
      m.put("particles", new String[] {PVERT, PFRAG});
      m.put("compact-draw", new String[] {CVERT, PFRAG});
      m.put("compact", new String[] {null, CCOMP});
      m.put("hdr-diamonds-db23iso", new String[] {VERT, hdr(frag(1))});
      m.put("hdr-particles", new String[] {PVERT, hdr(PFRAG)});
      m.put("hdr-compact-draw", new String[] {CVERT, hdr(PFRAG)});
      return m;
   }

   /** glintPass=compact: the same flakes evaluated by a compute shader into a list of the lit ones, drawn by one indirect draw. */
   static final String CCOMP = String.join("\n",
         "#version 430",
         "layout(local_size_x = 64) in;",
         GlintGlsl.LIB,
         FLAKE,
         "uniform int uTotal;",
         "layout(std430, binding = 0) buffer Flakes { vec4 flakes[]; };",
         "layout(std430, binding = 1) buffer Cmd { uint count; uint instances; uint first; uint baseInstance; };",
         "void main() {",
         "   int vid = int(gl_GlobalInvocationID.x);",
         "   if (vid >= uTotal) return;",
         "   vec4 clip; vec3 en;",
         "   if (!pzgFlake(vid, clip, en)) return;",
         "   uint i = atomicAdd(count, 1u);",
         "   if (i >= " + COMPACT_MAX + "u) return;",
         "   flakes[i * 2u] = clip;",
         "   flakes[i * 2u + 1u] = vec4(uView.z == 3.0 ? en * 20.0 : en, 0.0);",
         "}");

   static final String CVERT = String.join("\n",
         "#version 430",
         "layout(std430, binding = 0) readonly buffer Flakes { vec4 flakes[]; };",
         "flat out vec3 vE;",
         "void main() {",
         "   gl_Position = flakes[gl_VertexID * 2];",
         "   vE = flakes[gl_VertexID * 2 + 1].rgb;",
         "   gl_PointSize = 2.0;",
         "}");

   /** A compute program (Shaders has graphics programs only). */
   static int computeProgram(String src) {
      int sh = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
      GL20.glShaderSource(sh, src);
      GL20.glCompileShader(sh);
      if (GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) == 0) {
         Log.warn("glints: compute shader: " + GL20.glGetShaderInfoLog(sh));
         GL20.glDeleteShader(sh);
         return 0;
      }
      int p = GL20.glCreateProgram();
      GL20.glAttachShader(p, sh);
      GL20.glLinkProgram(p);
      GL20.glDeleteShader(sh);
      if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
         Log.warn("glints: compute program: " + GL20.glGetProgramInfoLog(p));
         GL20.glDeleteProgram(p);
         return 0;
      }
      return p;
   }

   /** The fragment shader of one model (MODEL 0..4, as MODELS). */
   static String frag(int model) {
      return String.join("\n",
         "#version 330",
         "#define MODEL " + model,
         GlintGlsl.LIB,
         "uniform vec4 uLp[" + MAX_LIGHTS + "];",
         "uniform vec4 uLd[" + MAX_LIGHTS + "];",
         "uniform vec4 uLc[" + MAX_LIGHTS + "];",
         "uniform vec4 uSun;   // direction, strength",
         "uniform vec4 uSunC;",
         "uniform vec4 uKind[3]; // per kind: facets per grid unit^2, NDF width, angular cell, F0",
         "uniform vec4 uView;  // footprint (grid units per pixel), brightness, dev view, -",
         "in vec2 vG;",
         "in vec3 vP;",
         "flat in vec4 vM;",
         "flat in ivec2 vB;",
         "#ifdef PZG_HDR",
         "layout(location = 0) out vec4 fragColor;",
         "layout(location = 1) out vec4 pzgHdrOut; // HdrGlint's target: g / (1 + g), the finished pixel's luminance",
         "uniform sampler2D pzHdrNow;",
         "uniform vec4 pzHdrNowP;",
         "uniform float uHdrK;",
         "void pzgWrite(vec3 c) {",
         "   vec3 g = c * uHdrK;",
         "   if (dot(g, vec3(1.0)) <= 1e-5) discard; // never overwrite the water's / puddles' / wet blood's glint with nothing",
         "   fragColor = vec4(0.0);",
         "   pzgHdrOut = vec4(g / (1.0 + g), 0.003 + 0.997 * dot(texture(pzHdrNow, gl_FragCoord.xy * pzHdrNowP.zw).rgb, vec3(0.2126, 0.7152, 0.0722)));",
         "}",
         "#else",
         "out vec4 fragColor;",
         "void pzgWrite(vec3 c) { fragColor = vec4(c, 0.0); }",
         "#endif",
         "const vec3 V = vec3(0.61237244, 0.61237244, 0.5);",
         "float ndf(vec2 g, vec2 m, vec4 k, int salt) {",
         "#if MODEL == 0",
         "   return pzgDb23(g, dFdx(g), dFdy(g), k.x, m, k.y, k.z, salt);",
         "#elif MODEL == 1",
         "   return pzgDb23Iso(g, uView.x, k.x, m, k.y, k.z, salt);",
         "#elif MODEL == 2",
         "   return pzgJamsi(g, uView.x, vP.z * 64.0, k.x, m, k.y, k.z);",
         "#elif MODEL == 3",
         "   return pzgSparkle(g, uView.x, m, k.y, k.z);",
         "#else",
         "   return pzgExplicit(g, uView.x, k.x, m, k.y, k.z);",
         "#endif",
         "}",
         "vec3 glint(vec3 L, vec3 E, vec2 g, vec4 k, int salt) {",
         "   if (L.z <= 0.0) return vec3(0.0);",
         "   vec3 H = normalize(L + V);",
         "   vec2 m = H.xy / H.z;",
         "   float D = ndf(g, m, k, salt);",
         "   if (D <= 0.0) return vec3(0.0);",
         "   float c2 = H.z * H.z;",
         "   float F = k.w + (1.0 - k.w) * pow(1.0 - clamp(dot(L, H), 0.0, 1.0), 5.0);",
         "   return E * (F * D / (c2 * c2) / (4.0 * V.z));",
         "}",
         "void main() {",
         "   int kind = int(vM.x + 0.5);",
         "   vec4 k = uKind[kind];",
         "   if (uView.z == 1.0) {",
         "      vec3 col = kind == 0 ? vec3(0.1, 0.25, 0.6) : kind == 1 ? vec3(0.5) : vec3(0.0, 0.4, 0.4);",
         "      pzgWrite(col * vM.y * 0.5);",
         "      return;",
         "   }",
         "   vec3 sum = vec3(0.0);",
         "   int salt = vB.y * 3 + kind;",
         "   if (uSun.w > 0.0) sum += glint(uSun.xyz, uSunC.rgb * (uSun.w * vM.z), vG, k, salt);",
         "   int bits = vB.x;",
         "   for (int i = 0; i < " + MAX_LIGHTS + "; i++) {",
         "      if ((bits & (1 << i)) == 0) continue;",
         "      vec3 d = uLp[i].xyz - vP;",
         "      float r = length(d);",
         "      vec3 L = d / max(r, 1e-4);",
         "      float hr = length(d.xy);",
         "      float att = clamp(1.0 - hr / uLp[i].w, 0.0, 1.0);",
         "      if (uLd[i].z > -1.5) {",
         "         float cs = hr > 1e-3 ? dot(-d.xy / hr, uLd[i].xy) : 1.0;",
         "         att *= smoothstep(uLd[i].z - 0.04, uLd[i].z + 0.04, cs);",
         "      }",
         "      if (att <= 0.0) continue;",
         "      sum += glint(L, uLc[i].rgb * (uLd[i].w * att * vM.w * smoothstep(0.15, 0.4, vM.w) * 1.6), vG, k, salt);",
         "   }",
         "   sum *= vM.y * uView.y;",
         "   if (uView.z == 2.0) { int nb = 0; for (int i = 0; i < " + MAX_LIGHTS + "; i++) nb += (bits >> i) & 1; sum += vec3(0.0, 0.08, 0.0) * float(nb); }",
         "   if (uView.z == 3.0) sum *= 20.0;",
         "   pzgWrite(sum);",
         "}");
   }

   static final class Gpu {
      static final int N_PROG = MODELS.length + 3; // + N_PROG: the HDR variant
      private static final int[] programs = new int[2 * N_PROG];
      private static final int[][] locs = new int[2 * N_PROG][];
      /** A ring of three data textures (and compact buffers): a frame never writes what the GPU may still read (an implicit sync). */
      static final int RING = 3;
      private static final int[] texs = new int[RING], ssbos = new int[RING], indirects = new int[RING];
      private static int tex, vao, lastStamp = -1, slot;
      private static FloatBuffer upload;
      private static final FloatBuffer mat = BufferUtils.createFloatBuffer(16);
      private static final FloatBuffer v8 = BufferUtils.createFloatBuffer(MAX_LIGHTS * 4);
      private static final FloatBuffer v3 = BufferUtils.createFloatBuffer(12);
      private static final Matrix4f mvp = new Matrix4f();
      static final String[] NAMES = {"uData", "uMVP", "uJ", "uTs", "uLp", "uLd", "uLc", "uSun", "uSunC", "uKind", "uView", "uTotal", "uHdrK", "pzHdrNow", "pzHdrNowP", "uOrigin"};

      /** Render thread, at world entry (shaderWarmup) or on the first draw: every model's program. */
      static void warm() {
         if (!Config.GLINTS || !Overrides.enabled()) {
            return;
         }
         for (int i = 0; i < MODELS.length; i++) {
            program(i);
         }
         program(PROG_PARTICLES);
         if (computeOk()) {
            program(PROG_COMPACT);
            program(PROG_COMPACT_DRAW);
         }
      }

      static final int PROG_PARTICLES = MODELS.length, PROG_COMPACT = MODELS.length + 1, PROG_COMPACT_DRAW = MODELS.length + 2;
      private static int ssbo, indirect;
      public static long ringWrites;
      static long uploads;
      private static final java.nio.IntBuffer CMD = BufferUtils.createIntBuffer(4);

      /** Compute shaders and indirect draws (GL 4.3; not on the Mac's 4.1). */
      static boolean computeOk() {
         try {
            return org.lwjgl.opengl.GL.getCapabilities().OpenGL43;
         } catch (Throwable t) {
            return false;
         }
      }

      static int program(int model) {
         if (programs[model] != 0) {
            return programs[model];
         }
         boolean hdr = model >= N_PROG;
         int b = model % N_PROG;
         String name = (hdr ? "hdr " : "") + (b < MODELS.length ? MODELS[b] : b == PROG_PARTICLES ? "particles" : b == PROG_COMPACT ? "compact" : "compact draw");
         String pf = hdr ? hdr(PFRAG) : PFRAG;
         int p = b < MODELS.length ? Shaders.program("glints " + name, VERT, hdr ? hdr(frag(b)) : frag(b))
            : b == PROG_PARTICLES ? Shaders.program("glints " + name, PVERT, pf)
            : b == PROG_COMPACT ? (hdr ? 0 : computeProgram(CCOMP)) : Shaders.program("glints " + name, CVERT, pf);
         if (p == 0) {
            programs[model] = -1;
            Log.warn("glints: the " + name + " program did not build");
            return -1;
         }
         programs[model] = p;
         int[] l = new int[NAMES.length];
         for (int i = 0; i < NAMES.length; i++) {
            l[i] = GL20.glGetUniformLocation(p, NAMES[i]);
         }
         locs[model] = l;
         Log.info("glints: " + name + " program " + p);
         return p;
      }

      static void draw(Pass pass) {
         Frame f = pass.frame;
         if (f == null || f.n == 0) {
            return;
         }
         boolean hdr = pass.hdr;
         if (hdr && (!HdrGlint.glintOnlyNow() || f.stamp != lastStamp)) {
            return; // the glint-only pass is not on, or this frame's main glint draw did not run (its data and list are the HDR draw's)
         }
         try {
            int which = f.pass == P_PARTICLES ? PROG_PARTICLES : f.pass == P_COMPACT ? (hdr ? PROG_COMPACT_DRAW : PROG_COMPACT) : f.model;
            if (hdr) {
               which += N_PROG;
            }
            int prog = program(which);
            if (prog <= 0 || !hdr && f.pass == P_COMPACT && program(PROG_COMPACT_DRAW) <= 0) {
               return;
            }
            int[] l = locs[which];
            if (texs[0] == 0) {
               GL13.glActiveTexture(GL13.GL_TEXTURE1);
               for (int r = 0; r < RING; r++) {
                  texs[r] = GL11.glGenTextures();
                  GL11.glBindTexture(GL11.GL_TEXTURE_2D, texs[r]);
                  GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 4096, MAX * TEXELS / 4096, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer)null);
                  GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                  GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
               }
               vao = GL30.glGenVertexArrays();
            }
            boolean fresh = lastStamp != f.stamp;
            if (fresh) {
               slot = (slot + 1) % Math.max(1, Math.min(RING, Config.DEV_GLINT_RING));
               ringWrites++;
            }
            tex = texs[slot];
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            String dv = Ground.variant(); // dev cycle names "noupload" / "nodraw": the upload's / the draw's share of the cost
            boolean noUpload = Config.DEV_GLINT_NO_UPLOAD || "noupload".equals(dv), noDraw = "nodraw".equals(dv);
            if (fresh && !(noUpload && uploads >= RING)) { // dev: the upload's share of the cost (stale data after the first round)
               uploads++;
               int floats = f.n * TEXELS * 4;
               if (upload == null) {
                  upload = BufferUtils.createFloatBuffer(MAX * TEXELS * 4);
               }
               upload.clear();
               upload.put(f.data, 0, floats);
               upload.flip();
               // only the texels in use: the full rows, then the last row's used part (a full 4096-texel row was 64 KB a
               // frame for a handful of squares)
               int texels = f.n * TEXELS, full = texels / 4096, rest = texels - full * 4096;
               if (full > 0) {
                  upload.limit(full * 4096 * 4);
                  GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 4096, full, GL11.GL_RGBA, GL11.GL_FLOAT, upload);
               }
               if (rest > 0) {
                  upload.limit(floats).position(full * 4096 * 4);
                  GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, full, rest, 1, GL11.GL_RGBA, GL11.GL_FLOAT, upload);
               }
               lastStamp = f.stamp;
            }
            zombie.core.ShaderHelper.glUseProgramObjectARB(prog);
            GL20.glUniform1i(l[0], 1);
            int pi = SpriteRenderer.instance.getRenderingPlayerIndex();
            zombie.iso.PlayerCamera cam = SpriteRenderer.instance.getRenderingPlayerCamera(pi);
            mvp.setOrtho(cam.getOffX(), cam.getOffX() + cam.offscreenWidth, cam.getOffY() + cam.offscreenHeight, cam.getOffY(), -1.0F, 1.0F);
            mat.clear();
            mvp.get(mat);
            GL20.glUniformMatrix4fv(l[1], false, mat);
            GL20.glUniform4f(l[2], f.jx, f.jy, BloodDecals.K, BloodDecals.K * Config.BLOOD_APPEND_BIAS_PCT / 100F + 1.0E-4F);
            GL20.glUniform1f(l[3], f.ts);
            GL20.glUniform2i(l[15], f.ox, f.oy);
            Lights L = f.lights;
            v8.clear();
            v8.put(L.p).flip();
            GL20.glUniform4fv(l[4], v8);
            v8.clear();
            v8.put(L.d).flip();
            GL20.glUniform4fv(l[5], v8);
            v8.clear();
            v8.put(L.c).flip();
            GL20.glUniform4fv(l[6], v8);
            GL20.glUniform4f(l[7], L.sun[0], L.sun[1], L.sun[2], L.sun[3]);
            GL20.glUniform4f(l[8], L.sunC[0], L.sunC[1], L.sunC[2], 0F);
            v3.clear();
            kindParams(v3);
            v3.flip();
            GL20.glUniform4fv(l[9], v3);
            GL20.glUniform4f(l[10], f.zoom / f.ts, Config.GLINT_PCT / 100F, Config.DEV_GLINT_VIEW, 1024F * f.ts * f.ts / (f.zoom * f.zoom));
            if (hdr) {
               TextureFBO world = Core.getInstance().getOffscreenBuffer();
               Texture wt = world != null ? (Texture)world.getTexture() : null;
               GL20.glUniform1f(l[12], Config.GLINT_HDR_PCT / 100F);
               GL20.glUniform1i(l[13], HdrGlint.NOW_UNIT);
               GL20.glUniform4f(l[14], 1F, 0F, wt != null ? 1F / wt.getWidthHW() : 0F, wt != null ? 1F / wt.getHeightHW() : 0F);
               if (f.pass == P_COMPACT) {
                  ssbo = ssbos[slot];
                  indirect = indirects[slot];
                  GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, ssbo);
                  GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, indirect);
               }
            } else if (f.pass == P_COMPACT) {
               // the lit flakes into a list (compute), then one indirect draw of exactly those
               if (ssbos[0] == 0) {
                  for (int r = 0; r < RING; r++) {
                     ssbos[r] = GL15.glGenBuffers();
                     GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbos[r]);
                     GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long)COMPACT_MAX * 32L, GL15.GL_DYNAMIC_COPY);
                     indirects[r] = GL15.glGenBuffers();
                     GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, indirects[r]);
                     GL15.glBufferData(GL40.GL_DRAW_INDIRECT_BUFFER, 16L, GL15.GL_DYNAMIC_COPY);
                  }
                  GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
               }
               ssbo = ssbos[slot];
               indirect = indirects[slot];
               CMD.clear();
               CMD.put(0).put(1).put(0).put(0).flip();
               GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, indirect);
               GL15.glBufferSubData(GL40.GL_DRAW_INDIRECT_BUFFER, 0L, CMD);
               GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, ssbo);
               GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, indirect);
               int total = f.n * SLOTS;
               GL20.glUniform1i(l[11], total);
               GL43.glDispatchCompute((total + 63) / 64, 1, 1);
               GL42.glMemoryBarrier(GL42.GL_COMMAND_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);
               prog = programs[PROG_COMPACT_DRAW];
               zombie.core.ShaderHelper.glUseProgramObjectARB(prog);
            }
            if (!hdr) {
               GL11.glEnable(GL11.GL_BLEND);
               GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
            } else {
               GL30.glDisablei(GL11.GL_BLEND, 1); // HdrGlint's target is replaced, never blended (its encoding is not additive)
               GL30.glColorMaski(0, false, false, false, false);
            }
            // dev cycle names (cost split): pnodepth / pnoclamp / pnosprite / phalf
            if ("pnodepth".equals(dv)) {
               GL11.glDisable(GL11.GL_DEPTH_TEST);
            } else {
               GL11.glEnable(GL11.GL_DEPTH_TEST);
            }
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(false);
            // GL_DEPTH_CLAMP for the floor diamonds only: with points on NVIDIA it cost ~50 us of the particle pass's 66 even
            // for the culled slots (gcv-cost-state 2026-10-24: 66 -> 12 us); the point programs clamp their depth themselves
            boolean clampHw = f.pass == P_DIAMONDS ? !"dnoclamp".equals(dv) : "pclamp".equals(dv);
            if (clampHw) {
               GL11.glEnable(GL32.GL_DEPTH_CLAMP);
            }
            GL30.glBindVertexArray(vao);
            if (f.pass == P_DIAMONDS) {
               GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, f.n);
            } else {
               boolean sprite = !"pnosprite".equals(dv);
               if (sprite) {
                  GL11.glEnable(GL32.GL_PROGRAM_POINT_SIZE);
                  if (!CoreGl.active) {
                     GL11.glEnable(GL20.GL_POINT_SPRITE); // a compatibility context defines gl_PointCoord only for point sprites
                  }
               } else {
                  GL11.glPointSize(2F);
               }
               if (noDraw) {
                  // dev: everything but the draw
               } else if (f.pass == P_PARTICLES) {
                  GL11.glDrawArrays(GL11.GL_POINTS, 0, "phalf".equals(dv) ? f.n * SLOTS / 2 : f.n * SLOTS);
               } else {
                  GL40.glDrawArraysIndirect(GL11.GL_POINTS, 0L);
                  GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
               }
               GL11.glDisable(GL32.GL_PROGRAM_POINT_SIZE);
               if (!CoreGl.active) {
                  GL11.glDisable(GL20.GL_POINT_SPRITE);
               }
            }
            GL30.glBindVertexArray(0);
            GL11.glDisable(GL32.GL_DEPTH_CLAMP);
            draws++;
            if (hdr) {
               hdrDraws++;
            }
         } catch (Throwable t) {
            failed = true;
            Log.warn("glints: draw failed, glints off: " + t);
         } finally {
            if (f.pass == P_COMPACT && ssbos[0] != 0) {
               GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, 0);
               GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, 0);
            }
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
            if (hdr) {
               // the glint-only pass goes on (HdrGlint's OFF ends it): its colour mask and its unblended target stay
               GL30.glColorMaski(0, false, false, false, false);
               GL30.glDisablei(GL11.GL_BLEND, 1);
            }
         }
      }

      public static long hdrDraws;
   }

   /**
    * Per kind (frost, snow, wet): facets per grid unit^2 (glintFlakes per square: a square is 1024 grid units^2), the
    * Beckmann width of their normals, the angular cell, F0. Frost: thin plates in every orientation (wide), ice's F0; snow:
    * a denser, rounder grain; wet asphalt: water over aggregate, the facets near the surface's normal.
    */
   static void kindParams(FloatBuffer out) {
      float rho = Config.GLINT_FLAKES / 1024F;
      float rough = Config.GLINT_ROUGH_PCT / 100F, tw = Config.GLINT_TWINKLE_PCT / 100F;
      out.put(rho).put(1.6F * rough).put(0.04F * tw).put(0.018F);
      out.put(rho * 1.6F).put(1.3F * rough).put(0.035F * tw).put(0.018F);
      out.put(rho * 0.6F).put(0.22F * rough).put(0.025F * tw).put(0.02F);
   }
}
