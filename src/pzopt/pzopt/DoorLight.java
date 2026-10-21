package pzopt;

import zombie.core.properties.IsoPropertyType;
import zombie.core.properties.PropertyContainer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.GridSquareEdgeFacingDirection;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;

/**
 * God rays through open doors (2026-10-18, docs/findings-god-rays-doors-2026-10-18.md): the door's leaf in the light volume
 * of its doorway (game thread).
 *
 * <ul>
 *   <li>{@code godRaysDoorLeaf}: the open leaf stands in the door's square along the edge the game draws it on
 *       ({@link IsoDoor#getSpriteEdge}: the next edge round, or the one its sprite is attached to), hinged at the corner the
 *       two edges share; the light volume's shader casts the light toward it and drops what hits the leaf (an analytic
 *       rectangle, soft by the same penumbra as the frame's edges; a door with glass lets its glass band through).</li>
 *   <li>{@code godRaysDoorSwingMs}: a toggle swings the leaf about its hinge over that time (the stock door changes sprite
 *       in one frame): opening, the light sweeps in through the widening gap; closing, the doorway stays an aperture for
 *       the light until the leaf has shut it.</li>
 * </ul>
 */
final class DoorLight {
   private DoorLight() {
   }

   static final float FRAME = 0.15F; // the door frame's margin either side of the opening (squares; the light volumes' E_DOOR)
   static final float HEAD = 0.80F; // the opening's head (levels)

   /** The swings in progress: door -> {start ms, 1 opening / 0 closing}. */
   private static final java.util.IdentityHashMap<IsoObject, long[]> SWINGS = new java.util.IdentityHashMap<>();
   static long swings; // dev: swings started

   /** The watched door changed open state (GodRays.pollWatched, game thread). */
   static void toggled(IsoObject d, boolean open) {
      if (Config.GOD_RAYS_DOOR_SWING_MS <= 0 || !Config.GOD_RAYS_DOOR_LEAF) {
         return;
      }
      PropertyContainer p = d.getProperties();
      if (p != null && p.has(IsoPropertyType.GARAGE_DOOR)) {
         return; // rolls up: no leaf in the doorway
      }
      long now = System.currentTimeMillis();
      long[] s = SWINGS.get(d);
      long t0 = now;
      if (s != null) {
         // reversed mid-swing: carry on from the angle it has
         float f = Math.min(1F, (now - s[0]) / (float)Config.GOD_RAYS_DOOR_SWING_MS);
         t0 = now - (long)((1F - f) * Config.GOD_RAYS_DOOR_SWING_MS);
      }
      SWINGS.put(d, new long[] {t0, open ? 1L : 0L});
      swings++;
      if (open) {
         OPENED.put(d, now); // the dust the door stirs (godRaysDoorDustPct)
      } else {
         OPENED.remove(d);
      }
   }

   /** The doors opened in the last PUFF_S seconds: door -> when (ms). */
   private static final java.util.IdentityHashMap<IsoObject, Long> OPENED = new java.util.IdentityHashMap<>();
   static final float PUFF_S = 9F;

   static boolean anyPuff() {
      if (OPENED.isEmpty()) {
         return false;
      }
      long now = System.currentTimeMillis();
      OPENED.values().removeIf(t -> now - t > (long)(PUFF_S * 1000F));
      return !OPENED.isEmpty();
   }

   /** Seconds since the door in the doorway (x, y, z, north) was opened, -1 when not within PUFF_S. */
   static float openedAgo(int x, int y, int z, boolean north) {
      if (OPENED.isEmpty() || Config.GOD_RAYS_DOOR_DUST_PCT <= 0) {
         return -1F;
      }
      IsoCell cell = IsoWorld.instance.currentCell;
      IsoGridSquare sq = cell != null ? cell.getGridSquare(x, y, z) : null;
      IsoObject d = sq != null ? sq.getDoor(north ? GridSquareEdgeFacingDirection.NORTH_SOUTH : GridSquareEdgeFacingDirection.EAST_WEST) : null;
      Long t = d != null ? OPENED.get(d) : null;
      return t == null ? -1F : (System.currentTimeMillis() - t) / 1000F;
   }

   /** A swing is running (GodRays rebuilds the light volumes every frame meanwhile). */
   static boolean anySwing() {
      return !SWINGS.isEmpty();
   }

   /**
    * Game thread, once a frame: the finished swings out of the table; their chunks' occupancy again (a door that has shut
    * is a wall for the light again). Returns true when one ended.
    */
   static boolean expire(java.util.function.Consumer<IsoGridSquare> dirty) {
      if (SWINGS.isEmpty()) {
         return false;
      }
      long now = System.currentTimeMillis();
      boolean any = false;
      java.util.Iterator<java.util.Map.Entry<IsoObject, long[]>> it = SWINGS.entrySet().iterator();
      while (it.hasNext()) {
         java.util.Map.Entry<IsoObject, long[]> e = it.next();
         if (now - e.getValue()[0] >= Config.GOD_RAYS_DOOR_SWING_MS) {
            IsoObject d = e.getKey(); // (before the remove: an IdentityHashMap entry is gone with it)
            it.remove();
            if (d.getSquare() != null) {
               dirty.accept(d.getSquare());
            }
            any = true;
         }
      }
      return any;
   }

   /** A closed door still swinging shut: the light still comes through until it has. */
   static boolean closing(IsoObject d) {
      long[] s = SWINGS.isEmpty() ? null : SWINGS.get(d);
      return s != null && s[1] == 0L;
   }

   /** How open the leaf is, 0 (shut) .. 1 (open, 90 deg). */
   static float openness(IsoObject d, boolean open) {
      long[] s = SWINGS.isEmpty() ? null : SWINGS.get(d);
      if (s == null) {
         return open ? 1F : 0F;
      }
      float t = Math.min(1F, Math.max(0F, (System.currentTimeMillis() - s[0]) / (float)Math.max(1, Config.GOD_RAYS_DOOR_SWING_MS)));
      if (s[1] == 1L) {
         float u = 1F - t;
         return 1F - u * u * u; // opening: fast, then settles against the wall
      }
      return 1F - t * t * t; // closing: slow, then slams
   }

   /**
    * The leaf of the door in the doorway (x, y, z, north) for the light volume, relative to (refX, refY): out[0..3] the pivot
    * (x, y) and the leaf's bottom and top (squares of height), out[4..7] its direction (unit), length and glass band (packed
    * DoorGlass band, -1 none). False when there is no leaf (an empty frame, a garage door, the key off).
    */
   static boolean leaf(int x, int y, int z, boolean north, int refX, int refY, float[] out) {
      if (!Config.GOD_RAYS_DOOR_LEAF) {
         return false;
      }
      IsoCell cell = IsoWorld.instance.currentCell;
      IsoGridSquare sq = cell != null ? cell.getGridSquare(x, y, z) : null;
      if (sq == null) {
         return false;
      }
      IsoObject d = sq.getDoor(north ? GridSquareEdgeFacingDirection.NORTH_SOUTH : GridSquareEdgeFacingDirection.EAST_WEST);
      boolean open;
      if (d instanceof IsoDoor door) {
         open = door.IsOpen();
      } else if (d instanceof IsoThumpable th) {
         open = th.IsOpen();
      } else {
         return false;
      }
      PropertyContainer p = d.getProperties();
      if (p != null && p.has(IsoPropertyType.GARAGE_DOOR)) {
         return false;
      }
      float o = openness(d, open);
      // the open leaf's edge (IsoDoor.getSpriteEdge): attachedE / attachedS, else the next edge round (N door -> W, W -> N)
      PropertyContainer op = d instanceof IsoDoor door ? (door.getOpenSprite() != null ? door.getOpenSprite().getProperties() : p)
         : ((IsoThumpable)d).openSprite != null ? ((IsoThumpable)d).openSprite.getProperties() : p;
      boolean attE = op != null && op.has(IsoFlagType.attachedE), attS = op != null && op.has(IsoFlagType.attachedS);
      float hx, hy, cx, cy, ox, oy; // the hinge corner, the shut leaf's way along the doorway, the open leaf's way into the square
      if (north) {
         boolean east = attE;
         hx = east ? x + 1 : x;
         hy = y;
         cx = east ? -1F : 1F;
         cy = 0F;
         ox = 0F;
         oy = 1F;
      } else {
         boolean south = attS;
         hx = x;
         hy = south ? y + 1 : y;
         cx = 0F;
         cy = south ? -1F : 1F;
         ox = 1F;
         oy = 0F;
      }
      double a = o * Math.PI * 0.5;
      float ux = (float)(Math.cos(a) * cx + Math.sin(a) * ox), uy = (float)(Math.cos(a) * cy + Math.sin(a) * oy);
      out[0] = hx + FRAME * cx - refX;
      out[1] = hy + FRAME * cy - refY;
      out[2] = z * GodRays.LEVEL;
      out[3] = (z + HEAD) * GodRays.LEVEL;
      out[4] = ux;
      out[5] = uy;
      out[6] = 1F - 2F * FRAME;
      out[7] = glass(d, p);
      return true;
   }

   // ------------------------------------------------------------------------------------------------ spill (godRaysSpill)

   static long spillsMade; // dev: spills drawn (since the last stats line)
   private static final float[] spD = new float[256];
   private static final float[][] spC = new float[256][];
   private static final float[][] spPool = new float[256][40];
   private static final float[] spQ = new float[12];

   /**
    * A lit room's light out through its open doors and windows (game thread, after the local lights; fills the local
    * lights' slots from {@code count}): for every window or doorway in view between a room and the outside whose room has
    * a ceiling light on, the light (a point inset in the light's rectangle, under the ceiling, nearest the opening) and the
    * frustum the opening cuts from it, as a local light of kind 3 (its sides, the opening's plane in lp, its ends in lq).
    * The outside air's density (fog, rain) makes it show, as the other local lights.
    */
   static int spills(GodRays.Frame f, int count, float outside) {
      int room = GodRays.MAX_LIGHTS - count;
      if (room <= 0) {
         return count;
      }
      IsoCell cell = IsoWorld.instance.currentCell;
      if (cell == null) {
         return count;
      }
      float g = Config.GOD_RAYS_LOCAL_PCT / 100F;
      if (outside * (float)Math.PI / Math.max(0.05F, Config.GOD_RAYS_LOCAL_CORE_PCT / 100F) * g < 0.012F) {
         return count; // clear air: a spill would not show
      }
      int camLevel0 = (int)Math.floor(zombie.iso.IsoCamera.frameState.camCharacterZ);
      long key = ((((((GodRays.buildSerial * 31L + f.refX) * 131L + f.refY) * 17L + (f.i0 >> 3)) * 13L + (f.j0 >> 3)) * 7L + f.nx * 1000L + f.ny) * 11L + camLevel0) * 29L
         + Float.floatToIntBits(outside) + (long)Config.GOD_RAYS_LOCAL_PCT * 7919L + (long)Config.GOD_RAYS_SPILL_PCT * 15485863L
         + ((long)Math.floor(zombie.iso.IsoCamera.frameState.camCharacterX) >> 2) * 104729L + ((long)Math.floor(zombie.iso.IsoCamera.frameState.camCharacterY) >> 2) * 1299709L;
      int lh = 1;
      for (int i = 0; i < watchedLights.size(); i++) {
         lh = lh * 31 + (watchedLights.get(i).active ? 1 : 2);
      }
      if (key == cacheKey && lh == cacheLights && cacheN <= room) {
         // nothing that shapes the spills changed: last frame's (the pass reads only these slots)
         for (int i = 0; i < cacheN; i++) {
            int k = (count + i) * 4;
            System.arraycopy(cacheL, i * 32, f.la, k, 4);
            System.arraycopy(cacheL, i * 32 + 4, f.lb, k, 4);
            System.arraycopy(cacheL, i * 32 + 8, f.lc, k, 4);
            System.arraycopy(cacheL, i * 32 + 12, f.lq, k, 4);
            System.arraycopy(cacheP, i * 20, f.lp, (count + i) * 20, 20);
         }
         spillsMade += cacheN;
         cacheHits++;
         return count + cacheN;
      }
      watchedLights.clear();
      int first = count;
      // the screen's world rectangle (as the light volumes')
      double u0 = f.i0 * (double)f.cu, u1 = (f.i0 + f.nx) * (double)f.cu, v0 = f.j0 * (double)f.cv, v1 = (f.j0 + f.ny) * (double)f.cv;
      double zA = f.zLo, zB = f.zLo + f.nz * f.cz;
      int x0 = (int)Math.floor((v0 + 6 * zA + u0) / 2) - 6, x1 = (int)Math.floor((v1 + 6 * zB + u1) / 2) + 6;
      int y0 = (int)Math.floor((v0 + 6 * zA - u1) / 2) - 6, y1 = (int)Math.floor((v1 + 6 * zB - u0) / 2) + 6;
      int camLevel = (int)Math.floor(zombie.iso.IsoCamera.frameState.camCharacterZ);
      float ccx = zombie.iso.IsoCamera.frameState.camCharacterX, ccy = zombie.iso.IsoCamera.frameState.camCharacterY;
      int oz0 = GodRays.occZ0();
      int n = 0;
      for (int cy = y0 >> 3; cy <= y1 >> 3; cy++) {
         for (int cx = x0 >> 3; cx <= x1 >> 3; cx++) {
            int[] list = GodRays.aperturesOf(cx, cy);
            if (list == null) {
               continue;
            }
            for (int packed : list) {
               int x = cx * 8 + (packed & 7), y = cy * 8 + (packed >> 3 & 7), z = oz0 + (packed >> 6 & 63);
               boolean north = (packed >> 12 & 1) == 0;
               if (z > camLevel || z < f.zLo || n >= spC.length) {
                  continue;
               }
               int o = GodRays.occCpu(x, y, z);
               int type = north ? o >> 1 & 3 : o >> 3 & 3;
               if (type != GodRays.E_WINDOW && type != GodRays.E_DOOR) {
                  continue;
               }
               int oa = north ? GodRays.occCpu(x, y - 1, z) : GodRays.occCpu(x - 1, y, z);
               boolean roomA = (oa & GodRays.B_ROOM) != 0, roomB = (o & GodRays.B_ROOM) != 0;
               if (roomA == roomB) {
                  continue;
               }
               int gb = packed >>> 16;
               boolean glass = (o & (north ? GodRays.B_GLASS_N : GodRays.B_GLASS_W)) != 0 && gb != 0;
               int run = 1;
               if (type == GodRays.E_DOOR && !glass) {
                  if (GodRays.openDoorway(north ? x - 1 : x, north ? y : y - 1, z, north)) {
                     continue;
                  }
                  while (run < 8 && GodRays.openDoorway(north ? x + run : x, north ? y : y + run, z, north)) {
                     run++;
                  }
               }
               float m = type == GodRays.E_WINDOW ? 0.1F : FRAME;
               float aLo = glass ? (gb & 15) / 15F : m, aHi = glass ? (gb >> 4 & 15) / 15F : run - m;
               float zs = glass ? (gb >> 8 & 15) / 15F : type == GodRays.E_WINDOW ? 0.30F : 0F;
               float zh = glass ? (gb >> 12 & 15) / 15F : type == GodRays.E_WINDOW ? 0.84F : HEAD;
               // the room's side and its light
               IsoGridSquare rsq = roomB ? cell.getGridSquare(x, y, z) : cell.getGridSquare(north ? x : x - 1, north ? y - 1 : y, z);
               zombie.iso.areas.IsoRoom r = rsq != null ? rsq.getRoom() : null;
               if (r == null || r.roomLights.isEmpty()) {
                  continue;
               }
               float amx = north ? x + 0.5F * (aLo + aHi) : x, amy = north ? y : y + 0.5F * (aLo + aHi);
               float inX = north ? 0F : roomB ? 1F : -1F, inY = north ? roomB ? 1F : -1F : 0F; // into the room
               zombie.iso.IsoRoomLight best = null;
               float bd = Float.MAX_VALUE, bx = 0F, by = 0F;
               for (int k = 0; k < r.roomLights.size(); k++) {
                  zombie.iso.IsoRoomLight l = r.roomLights.get(k);
                  if (l != null && l.z == z && !watchedLights.contains(l)) {
                     watchedLights.add(l); // (a switch toggled: the cache is stale)
                  }
                  if (l == null || !l.active || l.z != z) {
                     continue;
                  }
                  float lx = Math.max(l.x + 0.5F, Math.min(l.x + l.width - 0.5F, amx)), ly = Math.max(l.y + 0.5F, Math.min(l.y + l.height - 0.5F, amy));
                  // at least 1.2 squares in from the opening (a light right at it would spill a half-space)
                  float depth = (lx - amx) * inX + (ly - amy) * inY;
                  if (depth < 1.2F) {
                     lx += inX * (1.2F - depth);
                     ly += inY * (1.2F - depth);
                  }
                  float d = (lx - amx) * (lx - amx) + (ly - amy) * (ly - amy);
                  if (d < bd) {
                     bd = d;
                     best = l;
                     bx = lx;
                     by = ly;
                  }
               }
               if (best == null) {
                  continue;
               }
               float cr = Math.min(1F, best.r * 1.4F), cg = Math.min(1F, best.g * 1.4F), cb = Math.min(1F, best.b * 1.4F);
               float tr = (type == GodRays.E_WINDOW || glass ? 0.85F : 1F) * Config.GOD_RAYS_SPILL_PCT / 100F;
               float peak = outside * (float)Math.PI / Math.max(0.05F, Config.GOD_RAYS_LOCAL_CORE_PCT / 100F) * Math.max(cr, Math.max(cg, cb)) * tr * g;
               if (peak < 0.012F) {
                  continue;
               }
               float[] c = spPool[n];
               float lzs = (z + 0.92F) * GodRays.LEVEL; // under the ceiling (squares)
               float rx = f.refX, ry = f.refY;
               // the opening's corners (squares, from the reference square): 0 low-start, 1 low-end, 2 high-end, 3 high-start
               float[] q = spQ;
               for (int i = 0; i < 4; i++) {
                  float a = i == 0 || i == 3 ? aLo : aHi;
                  q[i * 3] = (north ? x + a : x) - rx;
                  q[i * 3 + 1] = (north ? y : y + a) - ry;
                  q[i * 3 + 2] = (z + (i < 2 ? zs : zh)) * GodRays.LEVEL;
               }
               float Lx = bx - rx, Ly = by - ry;
               float qcx = 0.25F * (q[0] + q[3] + q[6] + q[9]), qcy = 0.25F * (q[1] + q[4] + q[7] + q[10]), qcz = 0.25F * (q[2] + q[5] + q[8] + q[11]);
               float mx = Lx + 2F * (qcx - Lx), my = Ly + 2F * (qcy - Ly), mz = lzs + 2F * (qcz - lzs); // a point inside the frustum, outside
               // 0: the opening's plane, its normal out of the room
               c[0] = -inX;
               c[1] = -inY;
               c[2] = 0F;
               c[3] = -(c[0] * q[0] + c[1] * q[1]);
               for (int e = 0; e < 4; e++) {
                  int i = e, j = (e + 1) & 3;
                  float ax = q[i * 3] - Lx, ay = q[i * 3 + 1] - Ly, az = q[i * 3 + 2] - lzs;
                  float bx2 = q[j * 3] - Lx, by2 = q[j * 3 + 1] - Ly, bz = q[j * 3 + 2] - lzs;
                  float nx = ay * bz - az * by2, ny = az * bx2 - ax * bz, nz = ax * by2 - ay * bx2;
                  float len = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
                  if (len < 1e-6F) {
                     nx = ny = 0F;
                     nz = 1F;
                     len = 1F;
                  }
                  nx /= len;
                  ny /= len;
                  nz /= len;
                  float d = -(nx * Lx + ny * Ly + nz * lzs);
                  if (nx * mx + ny * my + nz * mz + d < 0F) {
                     nx = -nx;
                     ny = -ny;
                     nz = -nz;
                     d = -d;
                  }
                  c[4 + e * 4] = nx;
                  c[5 + e * 4] = ny;
                  c[6 + e * 4] = nz;
                  c[7 + e * 4] = d;
               }
               c[20] = Lx;
               c[21] = Ly;
               c[22] = z + 0.92F; // (levels, as the local lights')
               c[23] = 6F;
               c[24] = cr * tr * g;
               c[25] = cg * tr * g;
               c[26] = cb * tr * g;
               c[27] = (north ? x + aLo : x) - rx;
               c[28] = (north ? y : y + aLo) - ry;
               c[29] = (north ? x + aHi : x) - rx;
               c[30] = (north ? y : y + aHi) - ry;
               c[31] = z + zs;
               c[32] = z + zh;
               spC[n] = c;
               spD[n] = (amx - ccx) * (amx - ccx) + (amy - ccy) * (amy - ccy);
               n++;
            }
         }
      }
      while (room > 0 && n > 0) {
         int best = 0;
         for (int i = 1; i < n; i++) {
            if (spD[i] < spD[best]) {
               best = i;
            }
         }
         float[] c = spC[best];
         spC[best] = spC[--n];
         spD[best] = spD[n];
         int k = count * 4;
         f.la[k] = c[20];
         f.la[k + 1] = c[21];
         f.la[k + 2] = c[22];
         f.la[k + 3] = c[23];
         f.lb[k] = c[31]; // (a spill: its opening's sill and head, levels; the shader's cone test skips a point light)
         f.lb[k + 1] = c[32];
         f.lb[k + 2] = -2F;
         f.lb[k + 3] = outside;
         f.lc[k] = c[24];
         f.lc[k + 1] = c[25];
         f.lc[k + 2] = c[26];
         f.lc[k + 3] = 3F; // kind: a spill
         System.arraycopy(c, 0, f.lp, count * 20, 20);
         System.arraycopy(c, 27, f.lq, k, 4);
         count++;
         room--;
         spillsMade++;
      }
      cacheN = count - first;
      for (int i = 0; i < cacheN; i++) {
         int k = (first + i) * 4;
         System.arraycopy(f.la, k, cacheL, i * 32, 4);
         System.arraycopy(f.lb, k, cacheL, i * 32 + 4, 4);
         System.arraycopy(f.lc, k, cacheL, i * 32 + 8, 4);
         System.arraycopy(f.lq, k, cacheL, i * 32 + 12, 4);
         System.arraycopy(f.lp, (first + i) * 20, cacheP, i * 20, 20);
      }
      cacheKey = key;
      int h2 = 1;
      for (int i = 0; i < watchedLights.size(); i++) {
         h2 = h2 * 31 + (watchedLights.get(i).active ? 1 : 2);
      }
      cacheLights = h2;
      cacheBuilds++;
      return count;
   }

   private static long cacheKey = Long.MIN_VALUE;
   private static int cacheLights, cacheN;
   static long cacheHits, cacheBuilds; // dev
   private static final float[] cacheL = new float[GodRays.MAX_LIGHTS * 32], cacheP = new float[GodRays.MAX_LIGHTS * 20];
   private static final java.util.ArrayList<zombie.iso.IsoRoomLight> watchedLights = new java.util.ArrayList<>();

   /** The leaf's glass (DoorGlass's band of the shut sprite, the open sprite's or a door-window guess), -1 none. */
   private static float glass(IsoObject d, PropertyContainer p) {
      if (p == null || !p.has(IsoPropertyType.DOOR_TRANS)) {
         return -1F;
      }
      if (d instanceof IsoDoor door && (door.HasCurtains() != null && !door.isCurtainOpen() || door.isBarricaded())) {
         return -1F;
      }
      String n = d.getSprite() != null ? d.getSprite().getName() : null;
      int b = DoorGlass.band(n);
      if (b < 0 && n != null) {
         int us = n.lastIndexOf('_');
         try {
            int k = Integer.parseInt(n.substring(us + 1));
            for (int dk = -2; dk <= 2 && b < 0; dk++) {
               b = DoorGlass.band(n.substring(0, us + 1) + (k + dk)); // the shut sprite of the same door (its neighbours on the sheet)
            }
         } catch (NumberFormatException e) {
            // not a sheet name
         }
      }
      return b >= 0 ? b : GodRays.DEFAULT_GLASS_BAND;
   }
}
