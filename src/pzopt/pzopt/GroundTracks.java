package pzopt;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;
import zombie.GameTime;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.properties.IsoPropertyType;
import zombie.core.properties.PropertyContainer;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;
import zombie.iso.weather.ClimateManager;
import zombie.vehicles.BaseVehicle;

/**
 * Ground that remembers (2026-10-24, Config {@code groundTracks}, the "Ground and grass" Visuals; candidate G of
 * docs/plan-graphics-enhancements.md). Tyres, feet and bodies press tracks into grass, mud, sand, gravel and snow; the tracks
 * keep their shape and fade over game days, each surface by its own rule (grass flattens, yellows and stands up again over
 * ~2 days, mud keeps its ruts and berms and holds water, sand slumps, snow takes deep ruts and fills up while it snows).
 *
 * <p>The state is a world-anchored toroidal map around the camera (pzopt.Wakes' trail map, 16 texels a square, 128 squares,
 * RGBA8: height, wear, freshness, slip; harness/ground/tracks/gt_cs.glsl). The game thread turns this frame's wheel lines
 * (BaseVehicle.wheelInfo: contact, slip), strides (zombies, players, animals) and new bodies into primitives, bins them per
 * square and hands them to one compute dispatch (a work group a square); the rules are Sumner, O'Brien and Hodgins' (cut,
 * compression, berms, the angle of repose) with Bekker-Wong's multipass sinkage. Decay is a compute pass every
 * groundTracksDecayMinutes game minutes over the live chunks (rain softens mud, snowfall refills, grass stands up).
 *
 * <p>The look ({@code groundTracksLook=bake}): drawn into each chunk texture at its bake, after the floors and snow and
 * before the blood and the objects, as a per-channel factor (relief offset sampling along the camera's fixed ray, the rut
 * relit by the key light with its self-shadow, cavity AO, per-material colour response, water in ruts), so it costs nothing
 * a frame; a chunk being stamped re-bakes at most every groundTracksRebakeMs.
 *
 * <p>Interim storage: pzopt.GroundState (ground-common) will own the map, stamping and persistence; this class keeps its
 * own minimal map until then (tracks are lost when they leave the 128-square window).
 */
public final class GroundTracks {
   private GroundTracks() {
   }

   static final int TPS = 16, TN = 2048, SPAN = TN / TPS, CS = 8, SLOTS = SPAN / CS;
   static final int MAX_PRIMS = 2048, MAX_CELLS = 8192, MAX_PAIRS = 16384;
   static final int MAT_NONE = 0, MAT_GRASS = 1, MAT_DIRT = 2, MAT_SAND = 3, MAT_GRAVEL = 4, MAT_SNOW = 5;

   public static long frames, primsTotal, primsDropped, cellsTotal, decayPasses, decayCells, bakeDraws, rebakesAsked, clears, stampDispatches;
   public static long gameNs;
   static volatile boolean failed;

   public static boolean on() {
      return Config.GROUND_TRACKS && Overrides.enabled() && !failed && !CoreGl.legacyMac();
   }

   public static String stats() {
      return "ground tracks: " + (on() ? "on" : "off") + " frames=" + frames + " prims=" + primsTotal + " dropped=" + primsDropped + " cells=" + cellsTotal
         + " dispatches=" + stampDispatches + " decayPasses=" + decayPasses + " decayCells=" + decayCells + " bakeDraws=" + bakeDraws + " rebakes=" + rebakesAsked
         + " clears=" + clears + " live=" + liveSlots() + " game_us/frame=" + (frames == 0 ? 0 : gameNs / 1000L / frames) + (failed ? " (failed)" : "");
   }

   // ------------------------------------------------------------------------------------------------ the window (game thread)

   private static final long NONE = Long.MIN_VALUE;
   private static int wcx = Integer.MIN_VALUE, wcy; // the window's first chunk
   private static final long[] slotKey = new long[SLOTS * SLOTS];
   private static final boolean[] slotLive = new boolean[SLOTS * SLOTS];
   private static final long[] slotRebakeMs = new long[SLOTS * SLOTS]; // last re-bake asked
   private static final boolean[] slotPending = new boolean[SLOTS * SLOTS]; // stamped since its last re-bake
   private static final byte[][] slotMat = new byte[SLOTS * SLOTS][];
   private static final long[] slotMatMs = new long[SLOTS * SLOTS];
   private static final int[] clearList = new int[SLOTS * SLOTS];
   private static int nClear;

   static {
      java.util.Arrays.fill(slotKey, NONE);
   }

   static int slot(int cx, int cy) {
      return (cx & (SLOTS - 1)) + (cy & (SLOTS - 1)) * SLOTS;
   }

   static long key(int cx, int cy) {
      return ((long)cx << 32) ^ (cy & 0xFFFFFFFFL);
   }

   static boolean inWindow(int cx, int cy) {
      return cx >= wcx && cy >= wcy && cx < wcx + SLOTS && cy < wcy + SLOTS;
   }

   private static int liveSlots() {
      int n = 0;
      for (boolean b : slotLive) {
         n += b ? 1 : 0;
      }
      return n;
   }

   /** Recentre the window on the camera's chunk when it strays 3 chunks from the middle; slots that change hands are cleared. */
   private static void window(float camX, float camY) {
      int pcx = (int)Math.floor(camX / CS), pcy = (int)Math.floor(camY / CS);
      int ncx = pcx - SLOTS / 2, ncy = pcy - SLOTS / 2;
      if (wcx != Integer.MIN_VALUE && Math.abs(ncx - wcx) < 3 && Math.abs(ncy - wcy) < 3) {
         return;
      }
      wcx = ncx;
      wcy = ncy;
      for (int cy = wcy; cy < wcy + SLOTS; cy++) {
         for (int cx = wcx; cx < wcx + SLOTS; cx++) {
            int s = slot(cx, cy);
            long k = key(cx, cy);
            if (slotKey[s] != k) {
               if (slotLive[s] && nClear < clearList.length) {
                  clearList[nClear++] = s; // its old chunk's tracks leave the window (GroundState will save them)
                  clears++;
               }
               slotKey[s] = k;
               slotLive[s] = false;
               slotPending[s] = false;
               slotMat[s] = null;
            }
         }
      }
   }

   // ------------------------------------------------------------------------------------------------ materials

   /** The surface of each of a chunk's 64 squares at level 0 (cached a few seconds: snow comes and goes). */
   private static byte[] materials(int cx, int cy, long nowMs) {
      int s = slot(cx, cy);
      byte[] m = slotMat[s];
      if (m != null && nowMs - slotMatMs[s] < 5000L) {
         return m;
      }
      if (m == null) {
         m = new byte[64];
      }
      IsoCell cell = IsoWorld.instance.getCell();
      boolean snowAnywhere = zombie.iso.fboRenderChunk.FBORenderSnow.getInstance().isSnowAnywhere();
      for (int y = 0; y < CS; y++) {
         for (int x = 0; x < CS; x++) {
            int wx = cx * CS + x, wy = cy * CS + y;
            IsoGridSquare sq = cell == null ? null : cell.getGridSquare(wx, wy, 0);
            int mat = material(sq);
            if (mat != MAT_NONE && snowAnywhere && cell.gridSquareIsSnow(wx, wy, 0)) {
               mat = MAT_SNOW;
            }
            m[x + y * CS] = (byte)mat;
         }
      }
      slotMat[s] = m;
      slotMatMs[s] = nowMs;
      return m;
   }

   /** A square's natural ground (FloorMaterial / FootstepMaterial of its floor, else the blends_natural tile row), or none. */
   static int material(IsoGridSquare sq) {
      if (sq == null) {
         return MAT_NONE;
      }
      IsoObject floor = sq.getFloor();
      if (floor == null || floor.getSprite() == null) {
         return MAT_NONE;
      }
      PropertyContainer p = floor.getProperties();
      String fm = p == null ? null : p.get(IsoPropertyType.FLOOR_MATERIAL);
      if (fm == null && p != null) {
         fm = p.get(IsoPropertyType.FOOTSTEP_MATERIAL);
      }
      if (fm != null) {
         switch (fm) {
            case "Grass":
               return MAT_GRASS;
            case "Dirt":
            case "Clay":
               return MAT_DIRT;
            case "Sand":
               return MAT_SAND;
            case "Gravel":
               return MAT_GRAVEL;
            default:
               break; // Road, Water, Burnt, Concrete, Wood ...: by name below, else none
         }
      }
      String n = floor.getSprite().getName();
      if (n == null) {
         return MAT_NONE;
      }
      if (n.startsWith("blends_natural_01_")) {
         int i = parseTail(n);
         if (i < 0) {
            return MAT_NONE;
         }
         if (i < 16) {
            return MAT_SAND;
         }
         if (i < 64) {
            return MAT_GRASS;
         }
         if (i < 80) {
            return MAT_DIRT;
         }
         if (i < 96) {
            return MAT_GRAVEL; // dry grass over dirt / gravel
         }
         if (i < 112) {
            return MAT_DIRT;
         }
         return MAT_NONE;
      }
      if (n.startsWith("floors_exterior_natural_01_") || n.startsWith("blends_natural_02_")) {
         return MAT_GRASS;
      }
      return MAT_NONE;
   }

   private static int parseTail(String n) {
      int u = n.lastIndexOf('_');
      int v = 0;
      if (u < 0 || u == n.length() - 1) {
         return -1;
      }
      for (int i = u + 1; i < n.length(); i++) {
         char ch = n.charAt(i);
         if (ch < '0' || ch > '9') {
            return -1;
         }
         v = v * 10 + (ch - '0');
      }
      return v;
   }

   // ------------------------------------------------------------------------------------------------ primitives (game thread)

   // this frame's primitives, absolute world squares (converted to the frame origin when binned): 12 floats each (gt_rules.glsl)
   private static final double[] P = new double[MAX_PRIMS * 12];
   private static final double[] PB = new double[MAX_PRIMS * 4]; // their bounding boxes (squares)
   private static int nP;

   private static boolean prim(double ax, double ay, double az, double aw, float b0, float b1, float b2, float b3, float c0, float c1, float c2, float kind,
                               double x0, double y0, double x1, double y1) {
      if (nP >= MAX_PRIMS) {
         primsDropped++;
         return false;
      }
      int o = nP * 12;
      P[o] = ax;
      P[o + 1] = ay;
      P[o + 2] = az;
      P[o + 3] = aw;
      P[o + 4] = b0;
      P[o + 5] = b1;
      P[o + 6] = b2;
      P[o + 7] = b3;
      P[o + 8] = c0;
      P[o + 9] = c1;
      P[o + 10] = c2;
      P[o + 11] = kind;
      int b = nP * 4;
      PB[b] = x0;
      PB[b + 1] = y0;
      PB[b + 2] = x1;
      PB[b + 3] = y1;
      nP++;
      return true;
   }

   /** A wheel's line this frame: from (x0, y0) to (x1, y1), half width, depth (before the material's sink), berm, slip 0..1. */
   static void wheel(double x0, double y0, double x1, double y1, float half, float depth, float berm, float slip) {
      float s = Config.GROUND_TRACKS_STRENGTH_PCT / 100F;
      double m = half + 0.3 * (1.0 + slip);
      prim(x0, y0, x1, y1, half, depth * s, berm * s, slip, 1F, 0.1F, 1F, 0F, Math.min(x0, x1) - m, Math.min(y0, y1) - m, Math.max(x0, x1) + m, Math.max(y0, y1) + m);
   }

   /** A footprint / hoofprint / body: an ellipse at (x, y) heading (fx, fy) (unit), half length / width, depth, berm. */
   static void ellipse(double x, double y, float fx, float fy, float len, float wid, float depth, float berm, float wear, float heelToe) {
      float s = Config.GROUND_TRACKS_STRENGTH_PCT / 100F;
      double m = len + 0.25;
      prim(x, y, fx, fy, len, wid, depth * s, berm * s, wear, 0.06F, heelToe, 1F, x - m, y - m, x + m, y + m);
   }

   /** A drag mark: a shallow wide line. */
   static void drag(double x0, double y0, double x1, double y1, float half) {
      float s = Config.GROUND_TRACKS_STRENGTH_PCT / 100F;
      double m = half + 0.2;
      prim(x0, y0, x1, y1, half, 0.012F * s, 0.004F * s, 0F, 2.5F, 0.08F, 0F, 2F, Math.min(x0, x1) - m, Math.min(y0, y1) - m, Math.max(x0, x1) + m, Math.max(y0, y1) + m);
   }

   // ------------------------------------------------------------------------------------------------ sources

   private static final Vector3f WP = new Vector3f();
   private static final java.util.IdentityHashMap<BaseVehicle, double[]> wheelsLast = new java.util.IdentityHashMap<>();
   private static final ArrayList<BaseVehicle> moving = new ArrayList<>();
   private static final ArrayList<IsoGameCharacter> animals = new ArrayList<>();
   private static int vehicleScan, animalScan, zCursor;
   private static long frameNo;

   private static void vehicles(IsoCell cell) {
      if (++vehicleScan >= 20) {
         vehicleScan = 0;
         moving.clear();
         for (BaseVehicle v : cell.getVehicles()) {
            if (Math.abs(v.getCurrentSpeedKmHour()) > 0.5F) {
               moving.add(v);
            }
         }
      }
      for (IsoPlayer p : IsoPlayer.players) {
         BaseVehicle v = p == null ? null : p.getVehicle();
         if (v != null && !moving.contains(v)) {
            moving.add(v);
         }
      }
      for (int i = 0; i < moving.size(); i++) {
         vehicle(moving.get(i));
      }
      if ((frameNo & 255L) == 0L) {
         wheelsLast.keySet().removeIf(v -> !moving.contains(v));
      }
   }

   private static void vehicle(BaseVehicle v) {
      zombie.scripting.objects.VehicleScript script = v.getScript();
      if (script == null || v.getZ() >= 0.5F || v.wheelInfo == null) {
         return;
      }
      int nw = Math.min(script.getWheelCount(), v.wheelInfo.length);
      if (nw <= 0 || nw > 8) {
         return;
      }
      double[] last = wheelsLast.get(v);
      if (last == null) {
         last = new double[17];
         wheelsLast.put(v, last);
         last[16] = 0.0;
      }
      // load: a 1,400 kg car presses 0.10 squares into mud before the material's sink; heavier ones deeper (Bekker: z ~ W^(1/n))
      float mass = Math.max(300F, v.getMass());
      float depth = 0.10F * (float)Math.sqrt(mass / 1400F);
      depth = Math.min(depth, 0.2F);
      float half = mass > 2500F ? 0.13F : 0.10F;
      Vector3f mo = script.getModel() != null ? script.getModel().offset : null;
      boolean had = last[16] != 0.0;
      for (int w = 0; w < nw; w++) {
         zombie.scripting.objects.VehicleScript.Wheel sw = script.getWheel(w);
         BaseVehicle.WheelInfo wi = v.wheelInfo[w];
         v.getWorldPos(sw.offset.x + (mo != null ? mo.x : 0F), 0F, sw.offset.z + (mo != null ? mo.z : 0F), WP, script);
         double x = WP.x, y = WP.y;
         if (had) {
            double lx = last[w * 2], ly = last[w * 2 + 1];
            double d = Math.hypot(x - lx, y - ly);
            if (d > 0.01 && d < 4.0) {
               // Bullet's skidInfo: 1 full grip .. 0 sliding; a spinning or locked wheel digs and smears
               float slip = 1F - Math.max(0F, Math.min(1F, wi.skidInfo));
               boolean airborne = wi.suspensionLength <= 0F && false;
               if (!airborne) {
                  wheel(lx, ly, x, y, half, depth * (1F + 0.6F * slip), 0.035F * (1F + slip), slip);
               }
            } else if (d < 0.01) {
               continue; // keep the last point: a creeping car still joins up its line
            }
         }
         last[w * 2] = x;
         last[w * 2 + 1] = y;
      }
      last[16] = 1.0;
   }

   private static final float STRIDE_WALK = 0.42F, STRIDE_RUN = 0.7F;

   private static void characters(IsoCell cell, float dt) {
      ArrayList<IsoZombie> zs = cell.getZombieList();
      int n = zs.size();
      // a crowd is looked at round robin (each zombie at least every ~0.3 s); the stride line is kept, so no print is missed
      int visits = n <= 160 ? n : Math.min(n, Math.max(64, (int)Math.ceil(n * dt / 0.3F)));
      if (zCursor >= n) {
         zCursor = 0;
      }
      for (int v = 0; v < visits; v++) {
         IsoZombie z = zs.get(zCursor);
         if (++zCursor >= n) {
            zCursor = 0;
         }
         character(z, 0.045F, 1F);
      }
      for (IsoPlayer p : IsoPlayer.players) {
         if (p != null) {
            character(p, 0.05F, 1F);
         }
      }
      if (++animalScan >= 120) {
         animalScan = 0;
         animals.clear();
         for (IsoMovingObject o : cell.getObjectList()) {
            if (o instanceof zombie.characters.animals.IsoAnimal a) {
               animals.add(a);
            }
         }
      }
      for (int i = 0; i < animals.size(); i++) {
         IsoGameCharacter a = animals.get(i);
         if (a.getCurrentSquare() != null) {
            character(a, 0.04F, 0.7F);
         }
      }
   }

   /** A character's strides since its last print, each a footprint beside the walked line, feet alternating. */
   private static void character(IsoGameCharacter c, float depth, float size) {
      if (c.getZ() >= 0.5F || c.getVehicle() != null) {
         c.pzoptGtX = 0F;
         c.pzoptGtY = 0F;
         return;
      }
      float x = c.getX(), y = c.getY();
      if (c.isDead()) {
         if (!c.pzoptGtDead && Config.GROUND_TRACKS_BODIES) {
            c.pzoptGtDead = true;
            // the body: an imprint as long as a person, along its facing
            float a = c.getAnimAngleRadians();
            float fx = (float)Math.cos(a), fy = (float)Math.sin(a);
            ellipse(x, y, fx, fy, 0.85F * size, 0.28F * size, 0.035F, 0.01F, 1F, 0F);
         }
         return;
      }
      float lx = c.pzoptGtX, ly = c.pzoptGtY;
      float dx = x - lx, dy = y - ly;
      float d2 = dx * dx + dy * dy;
      if (d2 < STRIDE_WALK * STRIDE_WALK * size * size && (lx != 0F || ly != 0F)) {
         return; // not a stride yet (the common case)
      }
      if (lx == 0F && ly == 0F || d2 > 9F) {
         c.pzoptGtX = x;
         c.pzoptGtY = y;
         return;
      }
      float stride = (c.isRunning() || c.isSprinting() ? STRIDE_RUN : STRIDE_WALK) * size;
      if (d2 < stride * stride) {
         return;
      }
      float d = (float)Math.sqrt(d2);
      float ux = dx / d, uy = dy / d;
      int steps = Math.min(6, (int)(d / stride));
      if (Config.GROUND_TRACKS_FEET) {
         float dragged = c.isCrawling() ? 1F : 0F;
         for (int k = 1; k <= steps; k++) {
            float px = lx + ux * stride * k, py = ly + uy * stride * k;
            if (dragged > 0F) {
               // a crawler drags its body: a smear, no prints
               drag(px - ux * stride, py - uy * stride, px, py, 0.22F);
               continue;
            }
            c.pzoptGtFoot ^= 1;
            float side = (c.pzoptGtFoot == 0 ? 0.09F : -0.09F) * size;
            ellipse(px - uy * side, py + ux * side, ux, uy, 0.14F * size, 0.055F * size, depth, 0.012F, 1F, 1F);
         }
      }
      c.pzoptGtX = lx + ux * stride * steps;
      c.pzoptGtY = ly + uy * stride * steps;
   }

   // ------------------------------------------------------------------------------------------------ the frame (game thread)

   private static long lastFrameCount = -1L;
   private static long lastNanos;
   private static double lastDecayHours = -1.0;
   private static float wet;
   private static final int[] cellOf = new int[SPAN * SPAN];
   private static final long[] cellFrame = new long[SPAN * SPAN];
   private static final int[] pairCell = new int[MAX_PAIRS], pairPrim = new int[MAX_PAIRS];
   private static final ArrayList<Frame> pool = new ArrayList<>();

   /** FBORenderCell.renderInternal, before the chunk bakes: this frame's stamps, the decay tick, the window's clears. */
   public static void frameStart(int playerIndex) {
      if (!Config.GROUND_TRACKS) {
         return;
      }
      long fc = IsoCamera.frameState.frameCount;
      if (fc == lastFrameCount || !on()) {
         return; // once a frame (split screen renders the cell once a player)
      }
      lastFrameCount = fc;
      long t0 = System.nanoTime();
      float dt = lastNanos == 0L ? 1F / 60F : Math.min(0.25F, (t0 - lastNanos) / 1e9F);
      lastNanos = t0;
      frames++;
      frameNo++;
      IsoCell cell = IsoWorld.instance.getCell();
      IsoPlayer pl = IsoPlayer.players[playerIndex];
      if (cell == null || pl == null) {
         return;
      }
      window(pl.getX(), pl.getY());
      nP = 0;
      if (Config.GROUND_TRACKS_VEHICLES) {
         vehicles(cell);
      }
      characters(cell, dt);
      weather(dt);
      Frame f = alloc();
      long nowMs = System.currentTimeMillis();
      bin(f, nowMs);
      decay(f, nowMs);
      f.nClear = nClear;
      System.arraycopy(clearList, 0, f.clear, 0, nClear);
      nClear = 0;
      f.ox = wcx * CS;
      f.oy = wcy * CS;
      f.tick = (int)frameNo;
      rebakes(nowMs);
      if (f.nCells > 0 || f.nDecay > 0 || f.nClear > 0) {
         SpriteRenderer.instance.drawGeneric(f);
      } else {
         release(f);
      }
      gameNs += System.nanoTime() - t0;
      if (Config.DEV_GROUND_TRACKS_LOG && frames % 600L == 0L) {
         Log.info(stats());
      }
   }

   private static Frame alloc() {
      synchronized (pool) {
         if (!pool.isEmpty()) {
            Frame f = pool.remove(pool.size() - 1);
            f.nCells = f.nPrims = f.nIndex = f.nDecay = f.nClear = 0;
            return f;
         }
      }
      return new Frame();
   }

   static void release(Frame f) {
      synchronized (pool) {
         if (pool.size() < 8) {
            pool.add(f);
         }
      }
   }

   /** The primitives binned per square: cells (one compute work group each) and their primitive lists. */
   private static void bin(Frame f, long nowMs) {
      int ox = wcx * CS, oy = wcy * CS;
      int nPairs = 0;
      f.nPrims = 0;
      for (int i = 0; i < nP; i++) {
         int b = i * 4;
         int x0 = (int)Math.floor(PB[b]), y0 = (int)Math.floor(PB[b + 1]), x1 = (int)Math.floor(PB[b + 2]), y1 = (int)Math.floor(PB[b + 3]);
         x0 = Math.max(x0, ox);
         y0 = Math.max(y0, oy);
         x1 = Math.min(x1, ox + SPAN - 1);
         y1 = Math.min(y1, oy + SPAN - 1);
         if (x0 > x1 || y0 > y1 || (x1 - x0 + 1) * (y1 - y0 + 1) > 64) {
            continue;
         }
         int pi = -1;
         for (int sy = y0; sy <= y1; sy++) {
            for (int sx = x0; sx <= x1; sx++) {
               int cx = Math.floorDiv(sx, CS), cy = Math.floorDiv(sy, CS);
               byte[] mats = materials(cx, cy, nowMs);
               int mat = mats[(sx - cx * CS) + (sy - cy * CS) * CS];
               if (mat == MAT_NONE) {
                  continue;
               }
               int li = (sx - ox) + (sy - oy) * SPAN;
               int ci;
               if (cellFrame[li] != frameNo) {
                  if (f.nCells >= MAX_CELLS) {
                     continue;
                  }
                  cellFrame[li] = frameNo;
                  ci = f.nCells++;
                  cellOf[li] = ci;
                  int o = ci * 4;
                  f.cells[o] = ((sx * TPS) & (TN - 1)) | (((sy * TPS) & (TN - 1)) << 16);
                  f.cells[o + 1] = (sx - ox + 32768) | ((sy - oy + 32768) << 16);
                  f.cells[o + 2] = 0;
                  f.cells[o + 3] = mat << 24;
                  int s = slot(cx, cy);
                  slotLive[s] = true;
                  slotPending[s] = true;
               } else {
                  ci = cellOf[li];
               }
               if (nPairs >= MAX_PAIRS) {
                  continue;
               }
               if (pi < 0) {
                  pi = f.nPrims++;
                  int o = i * 12, q = pi * 12;
                  f.prims[q] = (float)(P[o] - ox);
                  f.prims[q + 1] = (float)(P[o + 1] - oy);
                  boolean line = P[o + 11] != 1.0;
                  f.prims[q + 2] = line ? (float)(P[o + 2] - ox) : (float)P[o + 2];
                  f.prims[q + 3] = line ? (float)(P[o + 3] - oy) : (float)P[o + 3];
                  for (int k = 4; k < 12; k++) {
                     f.prims[q + k] = (float)P[o + k];
                  }
               }
               pairCell[nPairs] = ci;
               pairPrim[nPairs] = pi;
               nPairs++;
            }
         }
      }
      primsTotal += f.nPrims;
      cellsTotal += f.nCells;
      // counting sort of the pairs by cell: each cell's primitives contiguous in the index list
      int[] cnt = f.count;
      java.util.Arrays.fill(cnt, 0, f.nCells, 0);
      for (int k = 0; k < nPairs; k++) {
         cnt[pairCell[k]]++;
      }
      int run = 0;
      for (int c = 0; c < f.nCells; c++) {
         f.cells[c * 4 + 2] = run;
         f.cells[c * 4 + 3] |= cnt[c] & 0xFFFF;
         int n = cnt[c];
         cnt[c] = run;
         run += n;
      }
      for (int k = 0; k < nPairs; k++) {
         f.index[cnt[pairCell[k]]++] = pairPrim[k];
      }
      f.nIndex = nPairs;
   }

   // ------------------------------------------------------------------------------------------------ weather and decay

   private static final float[] WEATHER = new float[4];

   /** The ground's wetness (0..1): rain wets it within the hour, it dries over hours (slower in the cold). */
   private static void weather(float dt) {
      ClimateManager cm = ClimateManager.getInstance();
      if (cm == null) {
         return;
      }
      boolean snowing = cm.getPrecipitationIsSnow();
      float rain = snowing ? 0F : clamp01(cm.getRainIntensity());
      float snow = snowing ? clamp01(cm.getSnowIntensity()) : 0F;
      float temp = cm.getTemperature();
      WEATHER[0] = rain;
      WEATHER[1] = snow;
      WEATHER[2] = temp;
      WEATHER[3] = clamp01(cm.getWindIntensity());
      float gh = (float)(GameTime.getInstance().getGameWorldSecondsSinceLastUpdate() / 3600.0);
      if (!(gh > 0F) || gh > 1F) {
         gh = 0F;
      }
      float tauDry = temp < 5F ? 18F : temp < 15F ? 10F : 5F;
      wet += rain * (1F - wet) * Math.min(1F, gh / 0.75F) - wet * Math.min(1F, gh / tauDry);
      wet = clamp01(wet);
   }

   private static void decay(Frame f, long nowMs) {
      double hours = GameTime.getInstance().getWorldAgeHours();
      if (lastDecayHours < 0.0 || hours < lastDecayHours) {
         lastDecayHours = hours;
         return;
      }
      double due = Config.GROUND_TRACKS_DECAY_MINUTES / 60.0;
      if (hours - lastDecayHours < due) {
         return;
      }
      float dt = (float)(hours - lastDecayHours) * 2F / Config.GROUND_TRACKS_DAYS;
      lastDecayHours = hours;
      f.dt = dt;
      System.arraycopy(WEATHER, 0, f.weather, 0, 4);
      int n = 0;
      for (int cy = wcy; cy < wcy + SLOTS; cy++) {
         for (int cx = wcx; cx < wcx + SLOTS; cx++) {
            int s = slot(cx, cy);
            if (!slotLive[s]) {
               continue;
            }
            byte[] mats = materials(cx, cy, nowMs);
            for (int q = 0; q < 64; q++) {
               if (mats[q] == MAT_NONE || n >= MAX_CELLS) {
                  continue;
               }
               int sx = cx * CS + (q & 7), sy = cy * CS + (q >> 3);
               int o = n * 4;
               f.decay[o] = ((sx * TPS) & (TN - 1)) | (((sy * TPS) & (TN - 1)) << 16);
               f.decay[o + 1] = (sx - wcx * CS + 32768) | ((sy - wcy * CS + 32768) << 16);
               f.decay[o + 2] = 0;
               f.decay[o + 3] = mats[q] << 24;
               n++;
            }
            slotPending[s] = true; // its texture shows the decayed tracks at its next re-bake turn
         }
      }
      f.nDecay = n;
      decayPasses++;
      decayCells += n;
   }

   /** Chunks stamped since their last bake re-bake (their level 0) at most every groundTracksRebakeMs. */
   private static void rebakes(long nowMs) {
      IsoCell cell = IsoWorld.instance.getCell();
      int budget = 4;
      for (int cy = wcy; cy < wcy + SLOTS && budget > 0; cy++) {
         for (int cx = wcx; cx < wcx + SLOTS && budget > 0; cx++) {
            int s = slot(cx, cy);
            if (!slotPending[s] || nowMs - slotRebakeMs[s] < Config.GROUND_TRACKS_REBAKE_MS) {
               continue;
            }
            IsoChunk c = cell.getChunk(cx, cy);
            slotPending[s] = false;
            slotRebakeMs[s] = nowMs;
            if (c != null) {
               c.invalidateRenderChunkLevel(0, 1L); // DIRTY_BLOOD: a floor decal changed
               rebakesAsked++;
               budget--;
            }
         }
      }
   }

   static float clamp01(float v) {
      return v < 0F ? 0F : v > 1F ? 1F : v;
   }

   // ------------------------------------------------------------------------------------------------ the bake pass

   private static final float[] LIGHT = new float[4];

   /** FBORenderCell.pzoptBakeSnow's end (game thread, in the bake stream): the tracks drawn over the chunk level's floors. */
   public static void bakeFloor(IsoChunk c, int level) {
      if (level != 0 || !on() || c == null || !inWindow(c.wx, c.wy)) {
         return;
      }
      int s = slot(c.wx, c.wy);
      if (!slotLive[s] || slotKey[s] != key(c.wx, c.wy)) {
         return;
      }
      byte[] mats = materials(c.wx, c.wy, System.currentTimeMillis());
      FBORenderChunkManager m = FBORenderChunkManager.instance;
      if (m.renderChunk == null) {
         return;
      }
      Bake b = Bake.alloc();
      java.util.Arrays.fill(b.mat, 0);
      for (int i = 0; i < 64; i++) {
         b.mat[i >> 3] |= (mats[i] & 15) << ((i & 7) * 4);
      }
      b.goX = m.getXOffset();
      b.goY = m.getYOffset();
      b.ts = Core.tileScale;
      b.level = level;
      int tx = (c.wx * CS * TPS) & (TN - 1), ty = (c.wy * CS * TPS) & (TN - 1);
      b.uvX = tx / (float)TN;
      b.uvY = ty / (float)TN;
      light(b);
      b.wet = wet;
      b.snowDepth = snowDepth();
      SpriteRenderer.instance.drawGeneric(b);
      bakeDraws++;
   }

   private static float snowDepth() {
      ClimateManager cm = ClimateManager.getInstance();
      return cm == null ? 0.15F : 0.06F + 0.24F * clamp01(cm.getSnowFracNow());
   }

   /** The key light now: the sun (pzopt.Sky; the moon at night carries no relief here) and its direct share of the floor's light. */
   private static void light(Bake b) {
      Sky.update(-1F);
      double[] sun = Sky.sun;
      ClimateManager cm = ClimateManager.getInstance();
      float day = cm == null ? 1F : clamp01(cm.getDayLightStrength());
      float cloud = cm == null ? 0F : clamp01(cm.getCloudIntensity());
      float share = sun[2] > 0.0 ? 0.7F * day * (1F - 0.75F * cloud) : 0F;
      b.lx = (float)sun[0];
      b.ly = (float)sun[1];
      b.lz = (float)Math.max(sun[2], 0.02);
      b.share = share;
      float k = 0.55F + 0.45F * (1F - cloud);
      b.skyR = 0.55F * k;
      b.skyG = 0.62F * k;
      b.skyB = 0.72F * k;
   }

   static final class Bake extends TextureDraw.GenericDrawer {
      private static final ArrayList<Bake> bpool = new ArrayList<>();
      final int[] mat = new int[8];
      float goX, goY, uvX, uvY, lx, ly, lz, share, wet, snowDepth, skyR, skyG, skyB;
      int ts, level;

      static Bake alloc() {
         synchronized (bpool) {
            return bpool.isEmpty() ? new Bake() : bpool.remove(bpool.size() - 1);
         }
      }

      @Override
      public void render() {
         GroundTracks.renderBake(this);
      }

      @Override
      public void postRender() {
         synchronized (bpool) {
            if (bpool.size() < 64) {
               bpool.add(this);
            }
         }
      }
   }

   static final class Frame extends TextureDraw.GenericDrawer {
      final float[] prims = new float[MAX_PRIMS * 12];
      final int[] cells = new int[MAX_CELLS * 4], decay = new int[MAX_CELLS * 4], index = new int[MAX_PAIRS], count = new int[MAX_CELLS];
      final int[] clear = new int[SLOTS * SLOTS];
      final float[] weather = new float[4];
      int nPrims, nCells, nIndex, nDecay, nClear, ox, oy, tick;
      float dt;

      @Override
      public void render() {
         GroundTracks.renderFrame(this);
      }

      @Override
      public void postRender() {
         release(this);
      }
   }

   // ------------------------------------------------------------------------------------------------ render thread

   private static boolean tried, ok;
   private static int stateTex, csProg, bakeProg, cellsBuf, primsBuf, indexBuf, vao;
   private static int uMode, uDt, uWeather, uTick;
   private static int bMVP, bGo, bState, bUv, bMat, bLight, bWeather, bSky;
   private static final IntBuffer INTS = BufferUtils.createIntBuffer(Math.max(MAX_CELLS * 4, MAX_PAIRS));
   private static final FloatBuffer FLOATS = BufferUtils.createFloatBuffer(MAX_PRIMS * 12);
   private static final FloatBuffer MAT = BufferUtils.createFloatBuffer(16);
   private static final Matrix4f MVP = new Matrix4f();
   private static final ByteBuffer REST = BufferUtils.createByteBuffer(4).put((byte)192).put((byte)0).put((byte)0).put((byte)0).flip();

   static String header(boolean compute) {
      return "#version 430\n#define GT_TPS " + TPS + ".0\n#define GT_TN " + TN + "\n#define GT_POM " + (Config.GROUND_TRACKS_POM ? 1 : 0) + "\n#define GT_SHADOW "
         + Config.GROUND_TRACKS_SHADOW_STEPS + "\n#define GT_AO " + (Config.GROUND_TRACKS_AO ? 1 : 0) + "\n";
   }

   private static boolean init() {
      if (tried) {
         return ok;
      }
      tried = true;
      if (!GL.getCapabilities().OpenGL43) {
         failed = true;
         Log.warn("ground tracks: needs OpenGL 4.3 (compute), off");
         return false;
      }
      csProg = RipplesGpu.program(header(true) + GroundTracksGlsl.RULES + "\n" + GroundTracksGlsl.CS, "ground tracks compute");
      String frag = header(false) + GroundTracksGlsl.BAKE_FRAG.replace("// (gt_look.glsl follows)", GroundTracksGlsl.LOOK);
      bakeProg = link(header(false) + GroundTracksGlsl.BAKE_VERT, frag, "ground tracks bake");
      if (csProg == 0 || bakeProg == 0) {
         failed = true;
         Log.warn("ground tracks: programs failed, off");
         return false;
      }
      uMode = GL20.glGetUniformLocation(csProg, "mode");
      uDt = GL20.glGetUniformLocation(csProg, "dt");
      uWeather = GL20.glGetUniformLocation(csProg, "weather");
      uTick = GL20.glGetUniformLocation(csProg, "tick");
      bMVP = GL20.glGetUniformLocation(bakeProg, "uMVP");
      bGo = GL20.glGetUniformLocation(bakeProg, "uGo");
      bState = GL20.glGetUniformLocation(bakeProg, "uState");
      bUv = GL20.glGetUniformLocation(bakeProg, "uUv");
      bMat = GL20.glGetUniformLocation(bakeProg, "uMat");
      bLight = GL20.glGetUniformLocation(bakeProg, "uLight");
      bWeather = GL20.glGetUniformLocation(bakeProg, "uWeather");
      bSky = GL20.glGetUniformLocation(bakeProg, "uSky");
      stateTex = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, stateTex);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
      GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, TN, TN);
      GL44.glClearTexImage(stateTex, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, REST);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      cellsBuf = ssbo((long)MAX_CELLS * 16);
      primsBuf = ssbo((long)MAX_PRIMS * 48);
      indexBuf = ssbo((long)MAX_PAIRS * 4);
      vao = GL30.glGenVertexArrays();
      ok = true;
      Log.info("ground tracks: ready (map " + TN + "^2 RGBA8 over " + SPAN + " squares, " + TPS + " texels a square)");
      return true;
   }

   private static int ssbo(long bytes) {
      int b = GL15.glGenBuffers();
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, b);
      GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, bytes, GL15.GL_DYNAMIC_DRAW);
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
      return b;
   }

   static int link(String vs, String fs, String name) {
      int v = shader(GL20.GL_VERTEX_SHADER, vs, name), f = shader(GL20.GL_FRAGMENT_SHADER, fs, name);
      if (v == 0 || f == 0) {
         return 0;
      }
      int p = GL20.glCreateProgram();
      GL20.glAttachShader(p, v);
      GL20.glAttachShader(p, f);
      GL20.glLinkProgram(p);
      GL20.glDeleteShader(v);
      GL20.glDeleteShader(f);
      if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
         Log.warn("ground tracks: " + name + " link: " + GL20.glGetProgramInfoLog(p, 8192));
         GL20.glDeleteProgram(p);
         return 0;
      }
      return p;
   }

   private static int shader(int type, String src, String name) {
      int s = GL20.glCreateShader(type);
      GL20.glShaderSource(s, src);
      GL20.glCompileShader(s);
      if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
         Log.warn("ground tracks: " + name + " shader: " + GL20.glGetShaderInfoLog(s, 8192));
         GL20.glDeleteShader(s);
         return 0;
      }
      return s;
   }

   private static void upload(int buf, int[] data, int n) {
      INTS.clear();
      INTS.put(data, 0, n).flip();
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buf);
      GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, INTS);
   }

   static void renderFrame(Frame f) {
      if (failed || !init()) {
         return;
      }
      GpuSections.begin("ground.tracks");
      try {
         for (int i = 0; i < f.nClear; i++) {
            int s = f.clear[i];
            GL44.glClearTexSubImage(stateTex, 0, (s & (SLOTS - 1)) * CS * TPS, (s / SLOTS) * CS * TPS, 0, CS * TPS, CS * TPS, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, REST);
         }
         if (f.nCells > 0 || f.nDecay > 0) {
            GL20.glUseProgram(csProg);
            GL42.glBindImageTexture(0, stateTex, 0, false, 0, GL15.GL_READ_WRITE, GL11.GL_RGBA8);
            GL30.glUniform1ui(uTick, f.tick);
            if (f.nClear > 0) {
               GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            }
            if (f.nDecay > 0) {
               upload(cellsBuf, f.decay, f.nDecay * 4);
               GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, cellsBuf);
               GL20.glUniform1i(uMode, 1);
               GL20.glUniform1f(uDt, f.dt);
               GL20.glUniform4f(uWeather, f.weather[0], f.weather[1], f.weather[2], f.weather[3]);
               GL43.glDispatchCompute(f.nDecay, 1, 1);
               GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
               // the slump of the decayed walls (rain, thaw): one relax pass
               GL20.glUniform1i(uMode, 2);
               GL43.glDispatchCompute(f.nDecay, 1, 1);
               GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            }
            if (f.nCells > 0) {
               upload(cellsBuf, f.cells, f.nCells * 4);
               upload(indexBuf, f.index, Math.max(1, f.nIndex));
               FLOATS.clear();
               FLOATS.put(f.prims, 0, Math.max(12, f.nPrims * 12)).flip();
               GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, primsBuf);
               GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, FLOATS);
               GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, cellsBuf);
               GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, primsBuf);
               GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, indexBuf);
               GL20.glUniform1i(uMode, 0);
               GL43.glDispatchCompute(f.nCells, 1, 1);
               GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
               GL20.glUniform1i(uMode, 2);
               GL43.glDispatchCompute(f.nCells, 1, 1);
               stampDispatches++;
            }
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT | GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
         }
      } catch (Throwable t) {
         failed = true;
         Log.warn("ground tracks: compute failed, off: " + t);
      } finally {
         GL42.glBindImageTexture(0, 0, 0, false, 0, GL15.GL_READ_ONLY, GL11.GL_RGBA8);
         for (int i = 1; i < 4; i++) {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, i, 0);
         }
         GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
         GL20.glUseProgram(0);
         zombie.core.ShaderHelper.forgetCurrentlyBound();
         GpuSections.end("ground.tracks");
      }
   }

   static void renderBake(Bake b) {
      if (failed || !init()) {
         return;
      }
      GpuSections.begin("ground.bake");
      try {
         GL20.glUseProgram(bakeProg);
         MVP.set(Core.getInstance().projectionMatrixStack.peek()).mul(Core.getInstance().modelViewMatrixStack.peek());
         MAT.clear();
         MVP.get(MAT);
         GL20.glUniformMatrix4fv(bMVP, false, MAT);
         GL20.glUniform4f(bGo, b.goX, b.goY, b.ts, b.level);
         GL13.glActiveTexture(GL13.GL_TEXTURE3);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, stateTex);
         GL20.glUniform1i(bState, 3);
         GL20.glUniform4f(bUv, b.uvX, b.uvY, TPS / (float)TN, 0F);
         INTS.clear();
         INTS.put(b.mat, 0, 8).flip();
         GL20.glUniform4iv(bMat, INTS);
         GL20.glUniform4f(bLight, b.lx, b.ly, b.lz, b.share);
         GL20.glUniform4f(bWeather, b.wet, b.snowDepth, Config.DEV_GROUND_TRACKS_VIEW, 0F);
         GL20.glUniform3f(bSky, b.skyR, b.skyG, b.skyB);
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL11.glDepthMask(false);
         GL11.glDisable(GL11.GL_STENCIL_TEST);
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL11.glEnable(GL11.GL_BLEND);
         GL14.glBlendFuncSeparate(GL11.GL_DST_COLOR, GL11.GL_SRC_COLOR, GL11.GL_ZERO, GL11.GL_ONE); // dst * 2 src, alpha kept
         GL30.glBindVertexArray(vao);
         GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
         GL30.glBindVertexArray(0);
      } catch (Throwable t) {
         failed = true;
         Log.warn("ground tracks: bake pass failed, off: " + t);
      } finally {
         GL20.glUseProgram(0);
         GL13.glActiveTexture(GL13.GL_TEXTURE3);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         Texture.lastTextureID = -1;
         GLStateRenderThread.restore();
         zombie.core.ShaderHelper.forgetCurrentlyBound();
         SpriteRenderer.ringBuffer.restoreVbos = true;
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
         GL11.glDepthMask(true);
         GpuSections.end("ground.bake");
      }
   }
}
