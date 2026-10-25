package pzopt;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import zombie.core.Core;
import zombie.core.ShaderHelper;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoPuddles;
import zombie.vehicles.BaseVehicle;

/**
 * What a car leaves in the puddles it drives through (2026-10-11, key {@code ripplesWake}, with {@code ripples};
 * docs/findings-rain-wakes-2026-10-11.md). {@code rings} is the first pass's answer (a big footstep ring at each rear
 * wheel every 0.3 - 1.5 squares: a dotted line of rings, not a wake). The others draw a continuous wake:
 * <ul>
 *   <li>{@code trail}: the interaction render target (the trail map of the snow / water trails of God of War, Horizon, the
 *       jess::codes video): a world-anchored toroidal map (128 squares, 4 texels a square) where each moving car stamps,
 *       each frame, the slab it swept since the last one (between its last two rear-axle lines, so a turn leaves no gap):
 *       per texel the time the car passed, the texel's offset across the car's line, its velocity. Nothing is
 *       simulated: the puddle pixel turns (age, offset) into the wake's profile, two tyre grooves refilling and a wave
 *       group running out of each groove, dispersing. A compute dispatch a car a frame over the slab's few texels;</li>
 *   <li>{@code kelvin}: the same map, the profile Kelvin's ship wake (the 19.47 degree wedge of transverse and divergent
 *       waves, their wave numbers from the stationary-phase condition, closed form per pixel);</li>
 *   <li>{@code particles}: wave particles (Yuksel, House and Keyser, SIGGRAPH 2007): each tyre sheds particles to both
 *       sides as it moves; each carries a small wave packet outward and fades. Births only are uploaded; the GPU moves them
 *       (position from age), bins them into one-square tiles (atomics) and gathers each texel's tile into a slope map;</li>
 *   <li>{@code sim}: the wheels inject into the footsteps' iWave simulation (RipplesGpu) every 0.15 squares: a moving
 *       source in dispersive water makes its own wake, shores included (the footsteps then run in that sim too).</li>
 * </ul>
 */
final class Wakes {
   private Wakes() {
   }

   static final String[] MODES = {"rings", "trail", "kelvin", "particles", "sim"};
   static final int RINGS = 0, TRAIL = 1, KELVIN = 2, PARTICLES = 3, SIM = 4;
   static final int UNIT = 23;

   // the trail map
   static final float SPAN = 128F; // squares (toroidal)
   static final int TN = 512, TEX_SQ = TN / (int)SPAN; // 4 texels a square
   static final float UMAX = 3.2F; // the slab's half width (squares from the car's line)
   static final float LIFE = 6F; // s a trail texel matters
   static final float VMAX = 24F; // squares a second at full velocity length
   static final float B_TRACK = 0.42F; // the rear tyres' half spacing (squares)
   static final int TTILE = 16, TTILES = (int)SPAN / TTILE; // clear tiles (squares)
   // the occupancy gate (2026-10-11, rw-cost-wake1: the trail map's fetch on every puddle pixel in the tiles' box was +24 us):
   // one byte per 4-square cell, a 1 KB texture always in cache, tested before the trail map is read
   static final int OCC = 2, OCC_N = (int)SPAN / OCC, UNIT_OCC = 24; // 2 squares: hugs the 6.4-square-wide slab (4 let the whole lane through)
   // the particles
   static final int PMAX = 16384;
   static final float P_SPACING = 0.15F, P_RADIUS = 0.3F, P_SPEED = 0.55F, P_LIFE = 2.6F, P_K = 2F * (float)Math.PI / 0.3F; // a 0.13-square carrier aliased at 8 texels a square
   static final int PRES = 16, PN = 1024, PTILE_CAP = 128; // slope map texels a square, its size, particles a one-square tile
   static final float SIM_SPACING = 0.15F;

   static volatile boolean failed;
   public static long stamps, births, dispatches, clears, maxLive, overflowTiles;

   static String stats() {
      return "wake: uploaded_texels=" + uploadedTexels + " stamps=" + stamps + " births=" + births + " dispatches=" + dispatches + " clears=" + clears + " p_maxlive=" + maxLive + (failed ? " (failed)" : "");
   }

   static int modeIndex(String m) {
      for (int i = 0; i < MODES.length; i++) {
         if (MODES[i].equals(m)) {
            return i;
         }
      }
      return RINGS;
   }

   static int effective(int mode) {
      if (mode == SIM) {
         return RipplesGpu.supported() ? SIM : RINGS;
      }
      if (mode != RINGS && (failed || RipplesGpu.gl43 == 0)) { // (game thread: the cached capability)
         return RINGS;
      }
      return mode;
   }

   // ------------------------------------------------------------------------------------------------ game thread

   // the gate (2026-10-11, rw-cost-wake4/5: one box over every live tile spanned the screen on a drive, so all ~11 M puddle
   // pixels paid the occupancy fetch, +21 us): up to NBOX world boxes along the cars' recent paths, one per BOX_S seconds of
   // travel, tested in ALU before any fetch
   static final int NBOX = 8;
   static final float BOX_S = 0.75F;

   private static final class Box {
      double x0, y0, x1, y1;
      float start, expiry;
   }

   private static final java.util.ArrayList<Box> boxes = new java.util.ArrayList<>();

   /** Grow the track's current box (a new one every BOX_S s) by a world rectangle that matters until {@code until}. */
   private static void gate(Track tr, double x0, double y0, double x1, double y1, float now, float until) {
      Box b = tr.box;
      if (b == null || now - b.start > BOX_S || !boxes.contains(b)) {
         b = new Box();
         b.x0 = x0;
         b.y0 = y0;
         b.x1 = x1;
         b.y1 = y1;
         b.start = now;
         tr.box = b;
         boxes.add(b);
      } else {
         b.x0 = Math.min(b.x0, x0);
         b.y0 = Math.min(b.y0, y0);
         b.x1 = Math.max(b.x1, x1);
         b.y1 = Math.max(b.y1, y1);
      }
      b.expiry = Math.max(b.expiry, until);
   }

   /** Game thread, build: drop the expired boxes, merge the oldest past NBOX, copy them to the frame. */
   private static void gates(WakeFrame w, float now) {
      boxes.removeIf(b -> b.expiry <= now);
      while (boxes.size() > NBOX) {
         Box a = boxes.remove(0), b = boxes.get(0); // the two oldest become one
         b.x0 = Math.min(a.x0, b.x0);
         b.y0 = Math.min(a.y0, b.y0);
         b.x1 = Math.max(a.x1, b.x1);
         b.y1 = Math.max(a.y1, b.y1);
         b.expiry = Math.max(a.expiry, b.expiry);
      }
      w.nBoxes = boxes.size();
      for (int i = 0; i < w.nBoxes; i++) {
         Box b = boxes.get(i);
         w.boxes[i * 4] = b.x0;
         w.boxes[i * 4 + 1] = b.y0;
         w.boxes[i * 4 + 2] = b.x1;
         w.boxes[i * 4 + 3] = b.y1;
      }
   }

   private static final class Track {
      Box box;
      double cx, cy;
      float lx, ly, t, emit;
      boolean init;
      int seen;
   }

   private static final java.util.IdentityHashMap<BaseVehicle, Track> tracks = new java.util.IdentityHashMap<>();
   static final int MAX_STAMPS = 64, MAX_BIRTHS = 512, MAX_RECTS = 256;
   // this frame's stamps (absolute world): c0x c0y n0x n0y t0 c1x c1y n1x n1y t1 vx vy
   private static final double[] pStamp = new double[MAX_STAMPS * 12];
   private static int nStamp;
   // births (absolute world): x y dx dy t0 amp
   private static final double[] pBirth = new double[MAX_BIRTHS * 6];
   private static int nBirth;
   private static final Vector3f WL = new Vector3f(), WR = new Vector3f();
   private static int frameNo;

   /** Ripples.scan (game thread), a moving vehicle or a local player's car, wake modes other than rings / sim. */
   static void vehicle(BaseVehicle v, int mode) {
      zombie.scripting.objects.VehicleScript script = v.getScript();
      if (script == null) {
         return;
      }
      Track tr = tracks.get(v);
      if (tr == null) {
         tr = new Track();
         tracks.put(v, tr);
      }
      tr.seen = frameNo;
      // the rear axle: the wheels with the lowest z offset
      Vector3f mo = script.getModel() != null ? script.getModel().offset : null;
      float minZ = Float.MAX_VALUE;
      for (int w = 0; w < script.getWheelCount(); w++) {
         minZ = Math.min(minZ, script.getWheel(w).offset.z);
      }
      int found = 0;
      for (int w = 0; w < script.getWheelCount() && found < 2; w++) {
         Vector3f off = script.getWheel(w).offset;
         if (off.z > minZ + 0.01F) {
            continue;
         }
         v.getWorldPos(off.x + (mo != null ? mo.x : 0F), 0F, off.z + (mo != null ? mo.z : 0F), found == 0 ? WL : WR, script);
         found++;
      }
      if (found < 2) {
         return;
      }
      double cx = (WL.x + WR.x) * 0.5, cy = (WL.y + WR.y) * 0.5;
      float lx = WL.x - WR.x, ly = WL.y - WR.y;
      float ll = (float)Math.sqrt(lx * lx + ly * ly);
      if (ll < 1e-3F) {
         return;
      }
      lx /= ll;
      ly /= ll;
      float now = (float)Ripples.clock;
      double dx = cx - tr.cx, dy = cy - tr.cy;
      double d = Math.sqrt(dx * dx + dy * dy);
      if (!tr.init || d > 8.0) {
         tr.init = true;
         tr.cx = cx;
         tr.cy = cy;
         tr.lx = lx;
         tr.ly = ly;
         tr.t = now;
         return;
      }
      if (d < 0.02) {
         return; // parked or creeping: the last line stays
      }
      // keep the lateral axis's sign continuous (the wheel order is fixed, but be safe)
      if (lx * tr.lx + ly * tr.ly < 0F) {
         lx = -lx;
         ly = -ly;
      }
      float dt = Math.max(1e-3F, now - tr.t);
      double vx = dx / dt, vy = dy / dt;
      double speed = d / dt;
      boolean wet = wet(v, WL) || wet(v, WR);
      if (wet) {
         if (mode == TRAIL || mode == KELVIN) {
            if (nStamp < MAX_STAMPS) {
               int o = nStamp * 12;
               pStamp[o] = tr.cx;
               pStamp[o + 1] = tr.cy;
               pStamp[o + 2] = tr.lx;
               pStamp[o + 3] = tr.ly;
               pStamp[o + 4] = tr.t;
               pStamp[o + 5] = cx;
               pStamp[o + 6] = cy;
               pStamp[o + 7] = lx;
               pStamp[o + 8] = ly;
               pStamp[o + 9] = now;
               pStamp[o + 10] = vx;
               pStamp[o + 11] = vy;
               nStamp++;
               stamps++;
               gate(tr, Math.min(tr.cx, cx) - UMAX - 0.5, Math.min(tr.cy, cy) - UMAX - 0.5, Math.max(tr.cx, cx) + UMAX + 0.5, Math.max(tr.cy, cy) + UMAX + 0.5, now, now + LIFE + 0.5F);
            }
         } else if (mode == PARTICLES) {
            float amp = (float)Math.min(1.0, 0.35 + speed / 10.0);
            double ux = dx / d, uy = dy / d;
            tr.emit += (float)d;
            while (tr.emit >= P_SPACING && nBirth + 2 <= MAX_BIRTHS) {
               tr.emit -= P_SPACING;
               double f = 1.0 - tr.emit / d; // along this frame's segment
               double bx = tr.cx + dx * f, by = tr.cy + dy * f;
               float bt = tr.t + (float)(f * dt);
               for (int side = -1; side <= 1; side += 2) {
                  double wx = bx + lx * B_TRACK * side, wy = by + ly * B_TRACK * side;
                  // outward, leaning back a little (the tyre moves on while the wave leaves); the inward one cost a third of the
                  // gather (rw-cost-wake2: 23.5 us compute) for waves the other tyre's cover
                  birth(wx, wy, lx * side - ux * 0.25, ly * side - uy * 0.25, bt, amp);
               }
               double reach = P_SPEED * P_LIFE + P_RADIUS + B_TRACK + 0.3;
               gate(tr, bx - reach, by - reach, bx + reach, by + reach, now, now + P_LIFE + 0.2F);
            }
            if (tr.emit > P_SPACING) {
               tr.emit = 0F;
            }
         }
      }
      tr.cx = cx;
      tr.cy = cy;
      tr.lx = lx;
      tr.ly = ly;
      tr.t = now;
   }

   private static boolean wet(BaseVehicle v, Vector3f w) {
      IsoGridSquare sq = v.getCell().getGridSquare((int)Math.floor(w.x), (int)Math.floor(w.y), (int)Math.floor(v.getZ()));
      return sq != null && sq.getPuddlesInGround() > 0.09F;
   }

   private static void birth(double x, double y, double dx, double dy, float t, float amp) {
      double l = Math.sqrt(dx * dx + dy * dy);
      int o = nBirth * 6;
      pBirth[o] = x;
      pBirth[o + 1] = y;
      pBirth[o + 2] = dx / l;
      pBirth[o + 3] = dy / l;
      pBirth[o + 4] = t;
      pBirth[o + 5] = amp;
      nBirth++;
      births++;
   }

   // the trail map's tiles: expiry per tile (clock), which are live
   private static final float[] tileExpiry = new float[TTILES * TTILES];
   private static final int[] tileWX = new int[TTILES * TTILES], tileWY = new int[TTILES * TTILES];
   private static final int[] activeTiles = new int[TTILES * TTILES];
   private static final byte[] occState = new byte[OCC_N * OCC_N];
   private static final float[] occExpiry = new float[OCC_N * OCC_N];
   private static boolean occDirty;
   private static int nActiveTiles;
   // the particles: a ring of PMAX in birth order (the oldest die first); the GPU buffer mirrors it
   private static final double[] partX = new double[PMAX], partY = new double[PMAX];
   private static final float[] partT = new float[PMAX];
   private static int pHead, pLive;
   private static double baseX = Double.NaN, baseY = Double.NaN; // the particles' coordinates are relative to this

   /** One frame's wake work for the render thread. */
   static final class WakeFrame {
      int mode;
      float now;
      final float[] stamps = new float[MAX_STAMPS * 12]; // relative to the map: c0 (map squares), n0, t0 / 64, c1, n1, t1 / 64 (unwrapped), v
      int nStamps;
      final int[] clears = new int[TTILES * TTILES];
      final byte[] occ = new byte[OCC_N * OCC_N];
      boolean occNew;
      int nClears;
      // the gate (absolute world squares) and the particle box
      double bx0, by0, bx1, by1;
      final double[] boxes = new double[NBOX * 4]; // the gate boxes (absolute world squares)
      // devWakeStampCpu: the slabs (and cleared tiles) rasterised on the game thread, uploaded as rectangles of the map
      boolean cpu;
      short[] up = new short[64 * 1024];
      final int[] rects = new int[MAX_RECTS * 5]; // x, y, w, h, offset (shorts) in up
      int nRects, upLen;
      int nBoxes;
      boolean any;
      final float[] births = new float[MAX_BIRTHS * 8]; // x y (relative to base) dx dy t0 amp, slot, -
      int nBirths, pFirst, pCount; // the live ring range
      double pbx, pby; // the particle box's origin (absolute, snapped to a texel); its size in texels
      int pw, ph;
      double baseX, baseY;
   }

   /** Ripples.build (game thread): this frame's stamps, births, clears and gates; whether the frame must be sent. */
   static boolean build(Ripples.Frame fr, int mode, int playerIndex) {
      WakeFrame w = fr.wake;
      w.mode = mode;
      w.now = (float)Ripples.clock;
      w.nStamps = 0;
      w.nClears = 0;
      w.nBirths = 0;
      w.any = false;
      frameNo++;
      if (tracks.size() > 0 && (frameNo & 63) == 0) {
         tracks.values().removeIf(t -> frameNo - t.seen > 120); // vehicles gone or parked for 2 s
      }
      if (mode == TRAIL || mode == KELVIN) {
         buildTrail(w);
      } else {
         nStamp = 0;
      }
      if (mode == PARTICLES) {
         buildParticles(w);
      } else {
         nBirth = 0;
      }
      gates(w, w.now);
      return w.any || w.nClears > 0;
   }

   private static void buildTrail(WakeFrame w) {
      float now = w.now;
      for (int i = 0; i < nStamp; i++) {
         int o = i * 12;
         double c0x = pStamp[o], c0y = pStamp[o + 1], c1x = pStamp[o + 5], c1y = pStamp[o + 6];
         // the map's coordinates: world mod SPAN, the second line next to the first (unwrapped)
         double m0x = Math.floorMod((long)Math.floor(c0x * 1024), (long)SPAN * 1024) / 1024.0, m0y = Math.floorMod((long)Math.floor(c0y * 1024), (long)SPAN * 1024) / 1024.0;
         int s = i * 12;
         w.stamps[s] = (float)m0x;
         w.stamps[s + 1] = (float)m0y;
         w.stamps[s + 2] = (float)pStamp[o + 2];
         w.stamps[s + 3] = (float)pStamp[o + 3];
         double t0 = pStamp[o + 4] / 64.0, t1 = pStamp[o + 9] / 64.0;
         double tb = Math.floor(t0);
         w.stamps[s + 4] = (float)(t0 - tb);
         w.stamps[s + 5] = (float)(m0x + (c1x - c0x));
         w.stamps[s + 6] = (float)(m0y + (c1y - c0y));
         w.stamps[s + 7] = (float)pStamp[o + 7];
         w.stamps[s + 8] = (float)pStamp[o + 8];
         w.stamps[s + 9] = (float)(t1 - tb);
         w.stamps[s + 10] = (float)pStamp[o + 10];
         w.stamps[s + 11] = (float)pStamp[o + 11];
         // the tiles it touches stay live LIFE seconds
         double x0 = Math.min(c0x, c1x) - UMAX, x1 = Math.max(c0x, c1x) + UMAX, y0 = Math.min(c0y, c1y) - UMAX, y1 = Math.max(c0y, c1y) + UMAX;
         // the occupancy cells it touches (one cell more each side: the bilinear fetch's footprint and the cell's own size)
         for (int cy = (int)Math.floor(y0 / OCC) - 1; cy <= (int)Math.floor(y1 / OCC) + 1; cy++) {
            for (int cx = (int)Math.floor(x0 / OCC) - 1; cx <= (int)Math.floor(x1 / OCC) + 1; cx++) {
               int ci = Math.floorMod(cy, OCC_N) * OCC_N + Math.floorMod(cx, OCC_N);
               if (occExpiry[ci] <= now) {
                  occState[ci] = (byte)255;
                  occDirty = true;
               }
               occExpiry[ci] = now + LIFE + 0.5F;
            }
         }
         for (int ty = (int)Math.floor(y0 / TTILE); ty <= (int)Math.floor(y1 / TTILE); ty++) {
            for (int tx = (int)Math.floor(x0 / TTILE); tx <= (int)Math.floor(x1 / TTILE); tx++) {
               int t = Math.floorMod(ty, TTILES) * TTILES + Math.floorMod(tx, TTILES);
               if (tileExpiry[t] <= now) {
                  activeTiles[nActiveTiles++] = t;
               }
               tileWX[t] = tx * TTILE;
               tileWY[t] = ty * TTILE;
               tileExpiry[t] = now + LIFE + 1F;
            }
         }
      }
      w.nStamps = nStamp;
      nStamp = 0;
      w.cpu = Config.DEV_WAKE_STAMP_CPU;
      w.nRects = 0;
      w.upLen = 0;
      if (w.cpu) {
         for (int i = 0; i < w.nStamps; i++) {
            rasterise(w, i * 12);
         }
      }
      if ((frameNo & 7) == 0) {
         for (int ci = 0; ci < occState.length; ci++) {
            if (occState[ci] != 0 && occExpiry[ci] <= now) {
               occState[ci] = 0;
               occDirty = true;
            }
         }
      }
      w.occNew = occDirty;
      if (occDirty) {
         System.arraycopy(occState, 0, w.occ, 0, occState.length);
         occDirty = false;
      }
      double bx0 = Double.MAX_VALUE, by0 = Double.MAX_VALUE, bx1 = -Double.MAX_VALUE, by1 = -Double.MAX_VALUE;
      for (int i = 0; i < nActiveTiles; ) {
         int t = activeTiles[i];
         if (tileExpiry[t] <= now) {
            w.clears[w.nClears++] = t;
            activeTiles[i] = activeTiles[--nActiveTiles];
            if (w.cpu) {
               int x0 = (t % TTILES) * TTILE * TEX_SQ, y0 = (t / TTILES) * TTILE * TEX_SQ, n = TTILE * TEX_SQ;
               for (int y = y0; y < y0 + n; y++) {
                  java.util.Arrays.fill(mirror, (y * TN + x0) * 4, (y * TN + x0 + n) * 4, (short)0);
               }
               rect(w, x0, y0, n, n);
            }
            continue;
         }
         bx0 = Math.min(bx0, tileWX[t]);
         by0 = Math.min(by0, tileWY[t]);
         bx1 = Math.max(bx1, tileWX[t] + TTILE);
         by1 = Math.max(by1, tileWY[t] + TTILE);
         i++;
      }
      w.any = nActiveTiles > 0;
      w.bx0 = bx0;
      w.by0 = by0;
      w.bx1 = bx1;
      w.by1 = by1;
      clears += w.nClears;
   }

   private static final short[] mirror = new short[TN * TN * 4]; // devWakeStampCpu: the trail map as the GPU holds it

   /** The slab of stamp {@code s} (w.stamps, map coordinates) into the mirror, as STAMP_CS does; its box queued for upload. */
   private static void rasterise(WakeFrame w, int s) {
      float[] st = w.stamps;
      float ax = st[s], ay = st[s + 1], anx = st[s + 2], any = st[s + 3], bx = st[s + 5], by = st[s + 6], bnx = st[s + 7], bny = st[s + 8];
      float x0 = Math.min(Math.min(ax - Math.abs(anx) * UMAX, bx - Math.abs(bnx) * UMAX), Math.min(ax, bx));
      float x1 = Math.max(Math.max(ax + Math.abs(anx) * UMAX, bx + Math.abs(bnx) * UMAX), Math.max(ax, bx));
      float y0 = Math.min(Math.min(ay - Math.abs(any) * UMAX, by - Math.abs(bny) * UMAX), Math.min(ay, by));
      float y1 = Math.max(Math.max(ay + Math.abs(any) * UMAX, by + Math.abs(bny) * UMAX), Math.max(ay, by));
      int tx0 = (int)Math.floor(x0 * TEX_SQ) - 1, ty0 = (int)Math.floor(y0 * TEX_SQ) - 1;
      int tx1 = (int)Math.ceil(x1 * TEX_SQ) + 1, ty1 = (int)Math.ceil(y1 * TEX_SQ) + 1;
      float fax = any, fay = -anx, fbx = bny, fby = -bnx;
      float trx = bx - ax, tryy = by - ay;
      if (fax * trx + fay * tryy < 0F) {
         fax = -fax;
         fay = -fay;
      }
      if (fbx * trx + fby * tryy < 0F) {
         fbx = -fbx;
         fby = -fby;
      }
      float ta = st[s + 4], tb = st[s + 9];
      short vb = unorm(st[s + 10] / VMAX * 0.5F + 0.5F), va = unorm(st[s + 11] / VMAX * 0.5F + 0.5F);
      for (int ty = ty0; ty < ty1; ty++) {
         float qy = (ty + 0.5F) / TEX_SQ;
         int row = Math.floorMod(ty, TN) * TN;
         for (int tx = tx0; tx < tx1; tx++) {
            float qx = (tx + 0.5F) / TEX_SQ;
            float da = (qx - ax) * fax + (qy - ay) * fay, db = (qx - bx) * fbx + (qy - by) * fby;
            if (da < -0.02F || db > 0F) {
               continue;
            }
            float sv = Math.max(0F, Math.min(1F, da / Math.max(da - db, 1e-5F)));
            float u = ((qx - ax) * anx + (qy - ay) * any) * (1F - sv) + ((qx - bx) * bnx + (qy - by) * bny) * sv;
            if (Math.abs(u) > UMAX) {
               continue;
            }
            float t = ta + (tb - ta) * sv;
            int o = (row + Math.floorMod(tx, TN)) * 4;
            mirror[o] = unorm(t - (float)Math.floor(t));
            mirror[o + 1] = unorm(u * (0.5F / UMAX) + 0.5F);
            mirror[o + 2] = vb;
            mirror[o + 3] = va;
         }
      }
      // the box, split where it wraps the map
      int wx0 = Math.floorMod(tx0, TN), wy0 = Math.floorMod(ty0, TN), ww = tx1 - tx0, wh = ty1 - ty0;
      int w1 = Math.min(ww, TN - wx0), h1 = Math.min(wh, TN - wy0);
      rect(w, wx0, wy0, w1, h1);
      if (ww > w1) {
         rect(w, 0, wy0, ww - w1, h1);
      }
      if (wh > h1) {
         rect(w, wx0, 0, w1, wh - h1);
         if (ww > w1) {
            rect(w, 0, 0, ww - w1, wh - h1);
         }
      }
   }

   private static short unorm(float v) {
      return (short)Math.round(Math.max(0F, Math.min(1F, v)) * 65535F);
   }

   /** A rectangle of the mirror (no wrap) copied for upload. */
   private static void rect(WakeFrame w, int x, int y, int rw, int rh) {
      if (rw <= 0 || rh <= 0 || w.nRects >= MAX_RECTS) {
         return;
      }
      int need = w.upLen + rw * rh * 4;
      if (need > w.up.length) {
         w.up = java.util.Arrays.copyOf(w.up, Math.max(need, w.up.length * 2));
      }
      for (int r = 0; r < rh; r++) {
         System.arraycopy(mirror, ((y + r) * TN + x) * 4, w.up, w.upLen + r * rw * 4, rw * 4);
      }
      int o = w.nRects * 5;
      w.rects[o] = x;
      w.rects[o + 1] = y;
      w.rects[o + 2] = rw;
      w.rects[o + 3] = rh;
      w.rects[o + 4] = w.upLen;
      w.upLen = need;
      w.nRects++;
      uploadedTexels += (long)rw * rh;
   }

   public static long uploadedTexels;

   private static void buildParticles(WakeFrame w) {
      float now = w.now;
      // retire the dead (birth order: the oldest first)
      while (pLive > 0) {
         int i = Math.floorMod(pHead - pLive, PMAX);
         if (now - partT[i] < P_LIFE) {
            break;
         }
         pLive--;
      }
      if (pLive == 0 && nBirth > 0 || Double.isNaN(baseX)) {
         if (nBirth > 0) {
            baseX = Math.floor(pBirth[0]);
            baseY = Math.floor(pBirth[1]);
         }
      }
      if (!Double.isNaN(baseX) && nBirth > 0 && (Math.abs(pBirth[0] - baseX) > 1500 || Math.abs(pBirth[1] - baseY) > 1500)) {
         pLive = 0; // far from the base (a teleport): start afresh
         baseX = Math.floor(pBirth[0]);
         baseY = Math.floor(pBirth[1]);
      }
      for (int b = 0; b < nBirth; b++) {
         int o = b * 6, slot = pHead;
         pHead = (pHead + 1) % PMAX;
         if (pLive < PMAX) {
            pLive++;
         }
         partX[slot] = pBirth[o];
         partY[slot] = pBirth[o + 1];
         partT[slot] = (float)pBirth[o + 4];
         int s = b * 8;
         w.births[s] = (float)(pBirth[o] - baseX);
         w.births[s + 1] = (float)(pBirth[o + 1] - baseY);
         w.births[s + 2] = (float)pBirth[o + 2];
         w.births[s + 3] = (float)pBirth[o + 3];
         w.births[s + 4] = (float)(pBirth[o + 4] % 1024.0);
         w.births[s + 5] = (float)pBirth[o + 5];
         w.births[s + 6] = slot;
      }
      w.nBirths = nBirth;
      nBirth = 0;
      maxLive = Math.max(maxLive, pLive);
      w.pCount = pLive;
      w.pFirst = Math.floorMod(pHead - pLive, PMAX);
      w.baseX = baseX;
      w.baseY = baseY;
      if (pLive == 0) {
         return;
      }
      // the box: the live particles' births grown by how far they travel, round the player's view at most 64 squares
      double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
      for (int k = 0, i = w.pFirst; k < pLive; k++, i = (i + 1) % PMAX) {
         x0 = Math.min(x0, partX[i]);
         x1 = Math.max(x1, partX[i]);
         y0 = Math.min(y0, partY[i]);
         y1 = Math.max(y1, partY[i]);
      }
      double grow = P_SPEED * P_LIFE + P_RADIUS + 0.2;
      x0 -= grow;
      y0 -= grow;
      x1 += grow;
      y1 += grow;
      double max = (double)PN / PRES;
      if (x1 - x0 > max || y1 - y0 > max) {
         zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
         double pcx = p != null ? p.getX() : (x0 + x1) / 2, pcy = p != null ? p.getY() : (y0 + y1) / 2;
         x0 = Math.max(x0, pcx - max / 2);
         y0 = Math.max(y0, pcy - max / 2);
         x1 = Math.min(x1, x0 + max);
         y1 = Math.min(y1, y0 + max);
      }
      w.pbx = Math.floor(x0 * PRES) / PRES;
      w.pby = Math.floor(y0 * PRES) / PRES;
      w.pw = Math.min(PN, (int)Math.ceil((x1 - w.pbx) * PRES / (double)PRES) * PRES); // whole tiles
      w.ph = Math.min(PN, (int)Math.ceil((y1 - w.pby) * PRES / (double)PRES) * PRES); // whole tiles
      w.bx0 = w.pbx;
      w.by0 = w.pby;
      w.bx1 = w.pbx + (double)w.pw / PRES;
      w.by1 = w.pby + (double)w.ph / PRES;
      w.any = w.pw > 0 && w.ph > 0;
   }

   // ------------------------------------------------------------------------------------------------ render thread

   private static boolean tried, ok;
   private static int occTex, occPbo, occPbo2, partOccTex;
   private static java.nio.ShortBuffer upBuf;
   private static final java.nio.ByteBuffer OCC_BUF = BufferUtils.createByteBuffer(OCC_N * OCC_N);
   private static int trailTex, slopeTex, stampProg, clearProg, binProg, gatherProg, partSsbo, countSsbo, listSsbo;
   private static int uStampA, uStampB, uStampC, uStampO, uClear, uBinP, uBinB, uGatherP, uGatherB;
   private static final IntBuffer CLEAR_BUF = BufferUtils.createIntBuffer(TTILES * TTILES * 2);
   private static final FloatBuffer BIRTH_BUF = BufferUtils.createFloatBuffer(8);

   private static boolean init() {
      if (tried) {
         return ok;
      }
      tried = true;
      if (!GL.getCapabilities().OpenGL43) {
         failed = true;
         return false;
      }
      stampProg = RipplesGpu.program(STAMP_CS, "wake stamp");
      clearProg = RipplesGpu.program(CLEAR_CS, "wake clear");
      binProg = RipplesGpu.program(BIN_CS, "wake bin");
      gatherProg = RipplesGpu.program(GATHER_CS, "wake gather");
      if (stampProg == 0 || clearProg == 0 || binProg == 0 || gatherProg == 0) {
         failed = true;
         Log.warn("ripples: wake programs failed: wakes drawn as rings");
         return false;
      }
      uStampA = GL20.glGetUniformLocation(stampProg, "A");
      uStampB = GL20.glGetUniformLocation(stampProg, "B");
      uStampC = GL20.glGetUniformLocation(stampProg, "C");
      uStampO = GL20.glGetUniformLocation(stampProg, "O");
      uClear = GL20.glGetUniformLocation(clearProg, "info");
      uBinP = GL20.glGetUniformLocation(binProg, "P");
      uBinB = GL20.glGetUniformLocation(binProg, "B");
      uGatherP = GL20.glGetUniformLocation(gatherProg, "P");
      uGatherB = GL20.glGetUniformLocation(gatherProg, "B");
      trailTex = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, trailTex);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
      GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA16, TN, TN);
      org.lwjgl.opengl.GL44.glClearTexImage(trailTex, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer)null);
      slopeTex = RipplesGpu.texture(PN, PN, GL30.GL_RG16F, GL11.GL_LINEAR, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
      occTex = r8(OCC_N, GL11.GL_REPEAT);
      occPbo = GL15.glGenBuffers();
      occPbo2 = GL15.glGenBuffers();
      partOccTex = r8(PN / PRES, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
      partSsbo = GL15.glGenBuffers();
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, partSsbo);
      GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long)PMAX * 8 * 4, GL15.GL_DYNAMIC_DRAW);
      countSsbo = GL15.glGenBuffers();
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, countSsbo);
      GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long)(PN / PRES) * (PN / PRES) * 4, GL15.GL_DYNAMIC_DRAW);
      listSsbo = GL15.glGenBuffers();
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, listSsbo);
      GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long)(PN / PRES) * (PN / PRES) * PTILE_CAP * 4, GL15.GL_DYNAMIC_DRAW);
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
      ok = true;
      Log.info("ripples: wake compute ready (trail map " + TN + "^2 over " + SPAN + " squares, particles " + PMAX + ", slope map " + PN + "^2)");
      return true;
   }

   private static final FloatBuffer BIRTHS = BufferUtils.createFloatBuffer(MAX_BIRTHS * 8);

   /** A zeroed R8 occupancy texture, nearest. */
   private static int r8(int n, int wrap) {
      int t = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, wrap);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, wrap);
      GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_R8, n, n);
      org.lwjgl.opengl.GL44.glClearTexImage(t, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer)null);
      return t;
   }

   /** Render thread, Ripples.Begin: clear the expired trail tiles, stamp the slabs, or move / bin / gather the particles. */
   static void render(Ripples.Frame fr) {
      WakeFrame w = fr.wake;
      if (failed || w.mode != TRAIL && w.mode != KELVIN && w.mode != PARTICLES || !w.any && w.nClears == 0 || !init()) {
         return;
      }
      GpuSections.markNow("ripples.wake", false);
      try {
         if (w.mode == PARTICLES) {
            particles(w);
         } else {
            trail(w);
         }
      } catch (Throwable t) {
         failed = true;
         Log.warn("ripples: wake compute failed, rings from now on: " + t);
      } finally {
         GL42.glBindImageTexture(0, 0, 0, false, 0, GL15.GL_READ_ONLY, GL11.GL_RGBA16);
         for (int i = 0; i < 3; i++) {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, i, 0);
         }
         DefaultProgram.restore(); // (frame start, before the cutaway mask: the default program, not 0)
         GpuSections.markNow("ripples.wake", true);
      }
   }

   private static void trail(WakeFrame w) {
      if (w.occNew) {
         w.occNew = false;
         OCC_BUF.clear();
         OCC_BUF.put(w.occ).flip();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, occTex);
         GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, occPbo); // (Mesa waits for its driver thread on a client-memory upload)
         GL15.glBufferData(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, OCC_BUF, GL15.GL_STREAM_DRAW);
         GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
         GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, OCC_N, OCC_N, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, 0L);
         GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
         GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, 0);
      }
      if (w.cpu) {
         // the rasterised slabs and cleared tiles: one buffer, a sub-image a rectangle (no compute pass at all)
         if (w.nRects > 0) {
            if (upBuf == null || upBuf.capacity() < w.upLen) {
               upBuf = BufferUtils.createShortBuffer(Math.max(w.upLen, 64 * 1024));
            }
            upBuf.clear();
            upBuf.put(w.up, 0, w.upLen).flip();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, trailTex);
            GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, occPbo2);
            GL15.glBufferData(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, upBuf, GL15.GL_STREAM_DRAW);
            for (int i = 0; i < w.nRects; i++) {
               int o = i * 5;
               GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, w.rects[o], w.rects[o + 1], w.rects[o + 2], w.rects[o + 3], GL11.GL_RGBA, GL11.GL_UNSIGNED_SHORT, (long)w.rects[o + 4] * 2);
            }
            GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, 0);
         }
         return;
      }
      if (w.nClears > 0) {
         GL20.glUseProgram(clearProg);
         GL42.glBindImageTexture(0, trailTex, 0, false, 0, GL15.GL_WRITE_ONLY, GL11.GL_RGBA16);
         for (int i = 0; i < w.nClears; i++) {
            int t = w.clears[i];
            GL20.glUniform4i(uClear, (t % TTILES) * TTILE * TEX_SQ, (t / TTILES) * TTILE * TEX_SQ, TN - 1, 0);
            GL43.glDispatchCompute(TTILE * TEX_SQ / 16, TTILE * TEX_SQ / 16, 1);
         }
         GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
         dispatches += w.nClears;
      }
      if (w.nStamps == 0) {
         return;
      }
      GL20.glUseProgram(stampProg);
      GL42.glBindImageTexture(0, trailTex, 0, false, 0, GL15.GL_WRITE_ONLY, GL11.GL_RGBA16);
      for (int i = 0; i < w.nStamps; i++) {
         int s = i * 12;
         float c0x = w.stamps[s], c0y = w.stamps[s + 1], n0x = w.stamps[s + 2], n0y = w.stamps[s + 3];
         float c1x = w.stamps[s + 5], c1y = w.stamps[s + 6], n1x = w.stamps[s + 7], n1y = w.stamps[s + 8];
         // the slab's box in texels (corners of both lines at +-UMAX)
         float x0 = Math.min(Math.min(c0x - Math.abs(n0x) * UMAX, c1x - Math.abs(n1x) * UMAX), Math.min(c0x, c1x));
         float x1 = Math.max(Math.max(c0x + Math.abs(n0x) * UMAX, c1x + Math.abs(n1x) * UMAX), Math.max(c0x, c1x));
         float y0 = Math.min(Math.min(c0y - Math.abs(n0y) * UMAX, c1y - Math.abs(n1y) * UMAX), Math.min(c0y, c1y));
         float y1 = Math.max(Math.max(c0y + Math.abs(n0y) * UMAX, c1y + Math.abs(n1y) * UMAX), Math.max(c0y, c1y));
         int tx0 = (int)Math.floor(x0 * TEX_SQ) - 1, ty0 = (int)Math.floor(y0 * TEX_SQ) - 1;
         int tw = (int)Math.ceil(x1 * TEX_SQ) + 1 - tx0, th = (int)Math.ceil(y1 * TEX_SQ) + 1 - ty0;
         // relative to the box's first texel (small numbers)
         float ox = tx0 / (float)TEX_SQ, oy = ty0 / (float)TEX_SQ;
         GL20.glUniform4f(uStampA, c0x - ox, c0y - oy, n0x, n0y);
         GL20.glUniform4f(uStampB, c1x - ox, c1y - oy, n1x, n1y);
         GL20.glUniform4f(uStampC, w.stamps[s + 4], w.stamps[s + 9], w.stamps[s + 10] / VMAX, w.stamps[s + 11] / VMAX);
         GL20.glUniform4i(uStampO, tx0, ty0, TN - 1, 0);
         GL43.glDispatchCompute((tw + 7) / 8, (th + 7) / 8, 1);
         GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT); // the next slab paints over this one
         dispatches++;
      }
   }

   private static void particles(WakeFrame w) {
      if (w.nBirths > 0) {
         GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, partSsbo);
         for (int b = 0; b < w.nBirths; b++) {
            BIRTH_BUF.clear();
            BIRTH_BUF.put(w.births, b * 8, 8).flip();
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, (long)(int)w.births[b * 8 + 6] * 32, BIRTH_BUF);
         }
      }
      if (w.pCount == 0 || w.pw <= 0 || w.ph <= 0) {
         GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
         return;
      }
      int tilesX = w.pw / PRES, tilesY = w.ph / PRES;
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, countSsbo);
      org.lwjgl.opengl.GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, GL30.GL_R32UI, 0L, (long)tilesX * tilesY * 4, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (IntBuffer)null);
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
      GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
      float bx = (float)(w.pbx - w.baseX), by = (float)(w.pby - w.baseY);
      // bin: each live particle into the one-square tiles its packet reaches
      GL20.glUseProgram(binProg);
      GL20.glUniform4f(uBinP, w.now % 1024F, w.pFirst, w.pCount, 0F);
      GL20.glUniform4f(uBinB, bx, by, tilesX, tilesY);
      GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, partSsbo);
      GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, countSsbo);
      GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, listSsbo);
      GL43.glDispatchCompute((w.pCount + 63) / 64, 1, 1);
      GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
      // gather: each texel of the box sums its tile's packets
      GL20.glUseProgram(gatherProg);
      GL20.glUniform4f(uGatherP, w.now % 1024F, 0F, 0F, 0F);
      GL20.glUniform4f(uGatherB, bx, by, tilesX, tilesY);
      GL42.glBindImageTexture(0, slopeTex, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RG16F);
      GL42.glBindImageTexture(1, partOccTex, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_R8);
      GL43.glDispatchCompute(tilesX, tilesY, 1);
      GL42.glBindImageTexture(1, 0, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_R8);
      GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
      GL42.glBindImageTexture(0, 0, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_RG16F);
      dispatches += 2;
   }

   // ------------------------------------------------------------------------------------------------ puddle uniforms

   static final String[] UNIFORMS = {"pzWakeP", "pzWakeW", "pzWakeB", "pzWakeTex", "pzWakeOcc", "pzWakeBx"};
   private static final FloatBuffer BOX_BUF = BufferUtils.createFloatBuffer(NBOX * 4);
   private static final java.util.HashMap<Integer, int[]> LOC = new java.util.HashMap<>();

   static void uniforms(int prog, Ripples.Frame f) {
      int[] l = LOC.get(prog);
      if (l == null) {
         l = new int[UNIFORMS.length];
         for (int i = 0; i < l.length; i++) {
            l[i] = GL20.glGetUniformLocation(prog, UNIFORMS[i]);
         }
         LOC.put(prog, l);
         if (l[3] >= 0) {
            GL20.glUniform1i(l[3], UNIT);
         }
         if (l[4] >= 0) {
            GL20.glUniform1i(l[4], UNIT_OCC);
         }
      }
      if (l[0] < 0) {
         return;
      }
      WakeFrame w = f == null ? null : f.wake;
      int mode = w == null || failed || !ok || !Ripples.on() || !w.any || w.nBoxes == 0 || Config.DEV_WAKE_NO_DRAW ? RINGS : w.mode;
      if (mode != TRAIL && mode != KELVIN && mode != PARTICLES) {
         GL20.glUniform4f(l[0], 0F, 0F, 0F, 0F);
         return;
      }
      org.joml.Vector4f off = IsoPuddles.getInstance().getShaderOffset();
      double a = off.x / (32.0 * Core.tileScale), b = off.y / (16.0 * Core.tileScale);
      double wx0 = (a + b) * 0.5, wy0 = (b - a) * 0.5; // the world square of the draw camera's view px (0, 0), level 0
      float strength = Config.RIPPLES_STRENGTH_PCT / 100F;
      GL20.glUniform4f(l[2], w.nBoxes, 0F, 0F, 0F);
      if (l[5] >= 0) {
         BOX_BUF.clear();
         for (int i = 0; i < NBOX; i++) {
            if (i < w.nBoxes) {
               BOX_BUF.put((float)(w.boxes[i * 4] - wx0)).put((float)(w.boxes[i * 4 + 1] - wy0)).put((float)(w.boxes[i * 4 + 2] - wx0)).put((float)(w.boxes[i * 4 + 3] - wy0));
            } else {
               BOX_BUF.put(1F).put(1F).put(0F).put(0F); // empty
            }
         }
         BOX_BUF.flip();
         GL20.glUniform4fv(l[5], BOX_BUF);
      }
      if (mode == PARTICLES) {
         GL20.glUniform4f(l[0], 3F, 0F, strength, 0F);
         // the slope map's texel of the view origin: world - box origin, in map uv
         GL20.glUniform4f(l[1], (float)(wx0 - w.pbx) * PRES / PN, (float)(wy0 - w.pby) * PRES / PN, 1F / (32F * Core.tileScale), (float)PRES / PN);
      } else {
         GL20.glUniform4f(l[0], mode, (w.now / 64F) % 1F, strength, f.rain.footprint); // w: a screen pixel in squares (RainRipples.build)
         double mx = wx0 % SPAN, my = wy0 % SPAN;
         GL20.glUniform4f(l[1], (float)(mx < 0 ? mx + SPAN : mx), (float)(my < 0 ? my + SPAN : my), 1F / (32F * Core.tileScale), 1F / SPAN);
      }
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, mode == PARTICLES ? slopeTex : trailTex);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT_OCC);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, mode == PARTICLES ? partOccTex : occTex);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
   }

   // ------------------------------------------------------------------------------------------------ shaders

   static String f(float v) {
      return Float.toString(v);
   }

   /** The puddle unit's wake text: with the vertex gate's varying when the vertex unit takes it, else a constant 0 (no wakes). */
   static String glsl() {
      return GLSL.replace("VARYING_NEAR", vertOk() ? "varying float pzWakeNear; // > 0 on squares near a car's recent path (the vertex shader's gate)"
            : "const float pzWakeNear = 0.0; // the vertex unit did not take the gate: no wakes");
   }

   /** The puddle unit's wake (GLSL 1.20): the slope in the wave normal's plane, only where the puddle shows. */
   static final String GLSL = String.join("\n",
         "// pzopt: car wakes (pzopt.Wakes)",
         "uniform vec4 pzWakeP; // x mode (0 none, 1 trail, 2 kelvin, 3 particles), y the clock / 64 (mod 1), z strength, w a screen pixel in squares",
         "uniform vec4 pzWakeW; // trail: xy the world square of the view's px (0, 0) mod the map, z squares a px / 2, w 1 / map span; particles: xy its uv, w texels a square / size",
         "uniform vec4 pzWakeB; // x the gate boxes in use",
         "uniform vec4 pzWakeBx[" + NBOX + "]; // the gate: boxes along the cars' recent paths, world squares from the view's px (0, 0)",
         "uniform sampler2D pzWakeTex;",
         "uniform sampler2D pzWakeOcc; // the occupancy gate (trail: a byte per 4 squares; particles: per square of the box)",
         "vec2 pzWakeG = vec2(0.0);",
         "VARYING_NEAR",
         "// the trail's profile across the car's line (u squares, + left), t s after the car passed, v squares a second:",
         "// two tyre grooves refilling, a wave group running out of each groove both ways, widening as it disperses",
         "float pzWakeTrailDh(float u, float t, float v) { // d/du of the profile, closed form (one evaluation, not two)",
         "   float dh = 0.0;",
         "   float run = " + f(0.55F) + " * t + 0.1; // the group's front (squares from the groove)",
         "   float sig = 0.07 + 0.09 * t, is2 = 1.0 / (sig * sig);",
         "   float decay = exp(-0.6 * t) * clamp(v * 0.25, 0.25, 1.0);",
         "   float groove = 0.04 * exp(-1.4 * t);",
         "   for (int k = 0; k < 2; k++) {",
         "      float sd = u - (k == 0 ? " + f(B_TRACK) + " : -" + f(B_TRACK) + ");",
         "      float s = abs(sd), sgn = sd < 0.0 ? -1.0 : 1.0;",
         "      float d = 0.0;",
         "      if (s < 0.35) d = 180.0 * s * groove * exp(-s * s * 90.0); // groove -g e0: d/ds = 180 s g e0 (nothing past 0.35: e0 < 1e-4)",
         "      float x = s - run;",
         "      if (x * x * is2 < 9.0) { // within 3 sigma of the group's front, else ~0: most pixels skip the transcendentals",
         "         float e = exp(-x * x * is2), sn = sin(48.0 * x), cs = cos(48.0 * x);",
         "         float q = inversesqrt(1.0 + 2.0 * s);",
         "         d += 0.025 * decay * q * e * (-2.0 * x * is2 * sn + 48.0 * cs - sn * q * q); // wave a e sn q: d/ds = a q (e (-2 x is2 sn + 48 cs) - e sn q^2)",
         "      }",
         "      dh += d * sgn;",
         "   }",
         "   return dh;",
         "}",
         "// Kelvin's wedge behind a source at v squares a second: x behind, y across; transverse and divergent waves",
         "float pzWakeKelvinH(float u, float t, float v) {",
         "   float x = v * t + 0.25, y = abs(u);",
         "   float tt = y / x;",
         "   float c2 = 1.0 - 8.0 * tt * tt; // the stationary phase has two roots inside the 19.47 degree wedge",
         "   if (c2 <= 0.0) return 0.0;",
         "   float sq = sqrt(c2);",
         "   float lam = clamp(0.3 + 0.012 * v * v, 0.3, 1.0); // the transverse wavelength (squares)",
         "   float k0 = 6.2832 / lam;",
         "   float t1 = (1.0 - sq) / (4.0 * tt + 1e-5), t2 = (1.0 + sq) / (4.0 * tt + 1e-5); // tan(psi): transverse, divergent",
         "   t2 = min(t2, 1.6); // past that the divergent crests are finer than the pixels",
         "   float p1 = k0 * (x + y * t1) * sqrt(1.0 + t1 * t1), p2 = k0 * (x + y * t2) * sqrt(1.0 + t2 * t2);",
         "   float r = sqrt(x * x + y * y);",
         "   float amp = 0.05 * exp(-0.45 * t) * inversesqrt(1.0 + r) / (pow(c2, 0.25) + 0.35) * smoothstep(0.0, 0.08, c2);",
         "   float k1 = k0 * (1.0 + t1 * t1), k2 = k0 * (1.0 + t2 * t2); // each wave's number: filtered by the pixel's footprint",
         "   float f1 = exp(-0.5 * k1 * k1 * pzWakeP.w * pzWakeP.w), f2 = exp(-0.5 * k2 * k2 * pzWakeP.w * pzWakeP.w);",
         "   return amp * (f1 * cos(p1) + 0.8 * f2 * cos(p2)) * clamp(v * 0.2, 0.2, 1.0);",
         "}",
         "vec2 pzWake(vec2 fc, float level) {",
         "   if (pzWakeP.x < 0.5) return vec2(0.0);",
         "   float show = smoothstep(0.26, 0.31, level); // only where the puddle shows",
         "   if (show <= 0.0) return vec2(0.0);",
         "   vec2 d = fc * WOffset.zw * pzWakeW.z;",
         "   vec2 dv = vec2(d.x * 0.5 + d.y, d.y - d.x * 0.5) + vec2(3.0 * puddlesZCoordinate); // world squares from the view's (0, 0)",
         "   if (pzWakeNear <= 0.0) return vec2(0.0); // the vertex shader found this square away from every car's recent path: no fetch, no loop",
         "   vec2 g;",
         "   if (pzWakeP.x > 2.5) {",
         "      vec2 uv = pzWakeW.xy + dv * pzWakeW.w;",
         "      if (texture2D(pzWakeOcc, uv).r < 0.5) return vec2(0.0); // no packet in this square: no slope-map fetch",
         "      g = texture2D(pzWakeTex, uv).rg;",
         "   } else {",
         "      vec2 uv = (pzWakeW.xy + dv) * pzWakeW.w;",
         "      if (texture2D(pzWakeOcc, uv).r < 0.5) return vec2(0.0); // no car passed this 4-square cell lately: no trail-map fetch",
         "      vec4 e = texture2D(pzWakeTex, uv);",
         "      vec2 vel = e.ba * 2.0 - 1.0;",
         "      float vl = length(vel);",
         "      if (vl < 0.002) return vec2(0.0);",
         "      float t = fract(pzWakeP.y - e.r) * 64.0;",
         "      if (t > " + f(LIFE) + ") return vec2(0.0);",
         "      float u = (e.g - 0.5) * " + f(2F * UMAX) + ";",
         "      vec2 dir = vel / vl;",
         "      vec2 lat = vec2(-dir.y, dir.x); // +u: left of the car's travel",
         "      float v = vl * " + f(VMAX) + ";",
         "      float fade = smoothstep(" + f(LIFE) + ", " + f(LIFE * 0.6F) + ", t) * smoothstep(" + f(UMAX) + ", " + f(UMAX - 0.6F) + ", abs(u));",
         "      float dh;",
         "      if (pzWakeP.x < 1.5) dh = pzWakeTrailDh(u, t, v);",
         "      else dh = (pzWakeKelvinH(u + 0.015, t, v) - pzWakeKelvinH(u - 0.015, t, v)) * " + f(1F / 0.03F) + ";",
         "      g = lat * (dh * fade);",
         "   }",
         "   g *= pzWakeP.z * show;",
         "   return vec2(g.x - g.y, g.x + g.y) * 0.7071;",
         "}",
         "");

   /**
    * The puddles' vertex unit (GLSL 330): per vertex, whether its square is near a car's recent path (the gate boxes, grown
    * by a square and a half: a vertex decides for the quad round it), as a varying. rw-cost-wake6/7: the same test per pixel
    * (an 8-box loop over ~11 M puddle pixels at 5K) cost more than the fetch it saved; per vertex it is ~10 k tests a frame.
    */
   private static Boolean vertOk;

   /**
    * Does the game's puddle vertex unit take the gate? Read from the game folder once: the fragment unit reads the varying
    * only when the vertex unit writes it (an unwritten varying is a link error on Mesa: no puddles at all).
    */
   static boolean vertOk() {
      if (vertOk == null) {
         boolean ok = true;
         try {
            for (String name : new String[] {"puddles_common.vert.glsl", "pzopt_puddles_common.vert.glsl"}) { // stock and puddleEarlyZ's
               java.nio.file.Path p = java.nio.file.Path.of("media", "shaders", name);
               if (java.nio.file.Files.exists(p)) {
                  ok &= patchVert(java.nio.file.Files.readString(p)) != null;
               }
            }
         } catch (Throwable t) {
            ok = false;
         }
         vertOk = ok;
         if (!ok) {
            failed = true;
            Log.warn("ripples: the puddle vertex unit has changed: wakes drawn as rings");
         }
      }
      return vertOk;
   }

   static String patchVert(String code) {
      String head = "void puddlesMain(void)";
      String tail = "puddlesDirNone = 1.0 - aDirNone;"; // (not the vDepth line: puddleEarlyZ's variant rewrites it)
      if (!code.contains(head) || !code.contains(tail) || !code.contains("gl_Position = ModelViewProjection")) {
         return null;
      }
      String decl = String.join("\n",
            "// pzopt: car wakes (pzopt.Wakes), the gate per vertex",
            "uniform vec4 WOffset;",
            "uniform mat4 PuddlesParams;",
            "uniform vec4 pzWakeB;",
            "uniform vec4 pzWakeW;",
            "uniform vec4 pzWakeBx[" + NBOX + "];",
            "out float pzWakeNear;",
            "");
      String test = String.join("\n",
            "",
            "\tpzWakeNear = 0.0; // pzopt: car wakes, the gate (pzopt.Wakes)",
            "\tif (pzWakeB.x > 0.5) {",
            "\t\tvec2 ndc = gl_Position.xy / gl_Position.w;",
            "\t\tvec2 d = vec2(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5) * WOffset.zw * pzWakeW.z;",
            "\t\tvec2 dv = vec2(d.x * 0.5 + d.y, d.y - d.x * 0.5) + vec2(3.0 * PuddlesParams[0].w);",
            "\t\tfor (int i = 0; i < " + NBOX + "; i++) {",
            "\t\t\tif (float(i) >= pzWakeB.x) break;",
            "\t\t\tvec4 b = pzWakeBx[i];",
            "\t\t\tif (dv.x >= b.x - 1.5 && dv.y >= b.y - 1.5 && dv.x <= b.z + 1.5 && dv.y <= b.w + 1.5) { pzWakeNear = 1.0; break; }",
            "\t\t}",
            "\t}");
      return code.replace(head, decl + head).replace(tail, tail + test);
   }

   /** Stamp one slab: the texels between line A (the car's last rear axle) and line B (now), within UMAX of the line. */
   static final String STAMP_CS = String.join("\n",
         "#version 430",
         "layout(local_size_x = 8, local_size_y = 8) in;",
         "layout(rgba16, binding = 0) writeonly uniform image2D map;",
         "uniform vec4 A; // line A: its point (squares from the box's first texel), its unit across",
         "uniform vec4 B; // line B",
         "uniform vec4 C; // x t_A / 64, y t_B / 64 (unwrapped), zw velocity / VMAX",
         "uniform ivec4 O; // xy the box's first texel (map texels, unwrapped), z map size - 1",
         "void main() {",
         "   ivec2 l = ivec2(gl_GlobalInvocationID.xy);",
         "   vec2 q = (vec2(l) + 0.5) * " + f(1F / TEX_SQ) + ";",
         "   vec2 fa = vec2(A.w, -A.z), fb = vec2(B.w, -B.z); // forward normals of the lines (sign fixed below)",
         "   vec2 travel = B.xy - A.xy;",
         "   if (dot(fa, travel) < 0.0) fa = -fa;",
         "   if (dot(fb, travel) < 0.0) fb = -fb;",
         "   float da = dot(q - A.xy, fa), db = dot(q - B.xy, fb);",
         "   if (da < -0.02 || db > 0.0) return; // not between the two lines (a hair before A: no seam between slabs)",
         "   float s = clamp(da / max(da - db, 1e-5), 0.0, 1.0);",
         "   float u = mix(dot(q - A.xy, A.zw), dot(q - B.xy, B.zw), s);",
         "   if (abs(u) > " + f(UMAX) + ") return;",
         "   float t = fract(mix(C.x, C.y, s));",
         "   imageStore(map, (O.xy + l) & O.z, vec4(t, u * " + f(0.5F / UMAX) + " + 0.5, C.zw * 0.5 + 0.5));",
         "}");

   static final String CLEAR_CS = String.join("\n",
         "#version 430",
         "layout(local_size_x = 16, local_size_y = 16) in;",
         "layout(rgba16, binding = 0) writeonly uniform image2D map;",
         "uniform ivec4 info; // xy the tile's first texel, z map size - 1",
         "void main() {",
         "   imageStore(map, (info.xy + ivec2(gl_GlobalInvocationID.xy)) & info.z, vec4(0.0));",
         "}");

   static final String PARTICLE = String.join("\n",
         "struct Part { vec4 a; vec4 b; }; // a: x y (squares from the base) dx dy, b: t0 (s mod 1024) amp slot -",
         "layout(std430, binding = 0) readonly buffer Parts { Part parts[]; };",
         "vec2 pzPartPos(Part p, float now, out float age) {",
         "   age = mod(now - p.b.x, 1024.0);",
         "   return p.a.xy + p.a.zw * (" + f(P_SPEED) + " * age);",
         "}");

   static final String BIN_CS = String.join("\n",
         "#version 430",
         "layout(local_size_x = 64) in;",
         PARTICLE,
         "layout(std430, binding = 1) buffer Counts { uint counts[]; };",
         "layout(std430, binding = 2) writeonly buffer Lists { uint lists[]; };",
         "uniform vec4 P; // x now, y first slot, z live count",
         "uniform vec4 B; // xy the box origin (squares from the base), zw its tiles",
         "void main() {",
         "   uint k = gl_GlobalInvocationID.x;",
         "   if (k >= uint(P.z)) return;",
         "   uint slot = (uint(P.y) + k) % " + PMAX + "u;",
         "   float age;",
         "   vec2 p = pzPartPos(parts[slot], P.x, age) - B.xy;",
         "   if (age >= " + f(P_LIFE) + ") return;",
         "   ivec2 t0 = ivec2(floor(p - " + f(P_RADIUS) + ")), t1 = ivec2(floor(p + " + f(P_RADIUS) + "));",
         "   ivec2 nt = ivec2(B.zw);",
         "   for (int y = max(t0.y, 0); y <= min(t1.y, nt.y - 1); y++) {",
         "      for (int x = max(t0.x, 0); x <= min(t1.x, nt.x - 1); x++) {",
         "         uint ti = uint(y * nt.x + x);",
         "         uint n = atomicAdd(counts[ti], 1u);",
         "         if (n < " + PTILE_CAP + "u) lists[ti * " + PTILE_CAP + "u + n] = slot;",
         "      }",
         "   }",
         "}");

   static final String GATHER_CS = String.join("\n",
         "#version 430",
         "layout(local_size_x = " + PRES + ", local_size_y = " + PRES + ") in;",
         PARTICLE,
         "layout(std430, binding = 1) readonly buffer Counts { uint counts[]; };",
         "layout(std430, binding = 2) readonly buffer Lists { uint lists[]; };",
         "layout(rg16f, binding = 0) writeonly uniform image2D slope;",
         "uniform vec4 P; // x now",
         "uniform vec4 B; // xy the box origin (squares from the base), zw its tiles",
         "layout(r8, binding = 1) writeonly uniform image2D occ; // a texel a tile: any packet here (the puddle's gate)",
         "void main() {",
         "   ivec2 tile = ivec2(gl_WorkGroupID.xy);",
         "   if (gl_LocalInvocationIndex == 0u) imageStore(occ, tile, vec4(counts[uint(tile.y * int(B.z) + tile.x)] > 0u ? 1.0 : 0.0));",
         "   uint ti = uint(tile.y * int(B.z) + tile.x);",
         "   uint n = min(counts[ti], " + PTILE_CAP + "u);",
         "   ivec2 c = ivec2(gl_GlobalInvocationID.xy);",
         "   vec2 q = B.xy + (vec2(c) + 0.5) * " + f(1F / PRES) + ";",
         "   vec2 g = vec2(0.0);",
         "   for (uint i = 0u; i < n; i++) {",
         "      Part p = parts[lists[ti * " + PTILE_CAP + "u + i]];",
         "      float age;",
         "      vec2 d = q - pzPartPos(p, P.x, age);",
         "      float r = length(d);",
         "      if (r >= " + f(P_RADIUS) + " || age >= " + f(P_LIFE) + ") continue;",
         "      // a wave packet moving along its direction: a raised-cosine window times a carrier across the packet",
         "      float wnd = 0.5 + 0.5 * cos(" + f((float)Math.PI / P_RADIUS) + " * r);",
         "      float dwr = -0.5 * " + f((float)Math.PI / P_RADIUS) + " * sin(" + f((float)Math.PI / P_RADIUS) + " * r);",
         "      float ph = " + f(P_K) + " * dot(d, p.a.zw);",
         "      float amp = p.b.y * 0.04 * exp(-1.3 * age) * min(1.0, age * 20.0);",
         "      g += amp * (dwr * (d / max(r, 1e-4)) * sin(ph) + wnd * " + f(P_K) + " * cos(ph) * p.a.zw);",
         "   }",
         "   imageStore(slope, c, vec4(g, 0.0, 0.0));",
         "}");
}
