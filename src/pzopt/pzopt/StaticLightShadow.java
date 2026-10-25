package pzopt;

import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoLightSource;
import zombie.iso.IsoRoomLight;
import zombie.iso.LosUtil;
import zombie.iso.objects.IsoLightSwitch;

/**
 * sunShadowStaticLights: the static lights of the world (street lamps, lamps and wall lights, fires: the cell's
 * {@code IsoCell.getLamppostPositions()}, the list LightingJNI hands the native) as point lights of the character shadow
 * pass ({@link CapsuleShadow}). The frame takes the lights that are on nearest the camera (a pool holding the camera
 * first) into the slots the torches leave; each caster then keeps the two of them that light it most, and only those
 * that reach it as the game's own lamp model has it: its level, the light's building when the light is local to one,
 * a clear line from the light's square (LosUtil.lineClear: walls and closed doors block, windows let it through).
 * The shadow's strength is the light's share at the receiver (share(): a quadratic fade of the native's linear lamp
 * field, saturating): dark and short under the lamp, long and faint towards the edge of its pool.
 * With sunShadowRoomLights the rooms' ceiling lights too (IsoCell.roomLights, kept apart from the lamp list: one IsoRoomLight a
 * room rectangle, lit evenly by the native): each rectangle an area light at one point under the ceiling (roomPoints: two or
 * three along a hallway), a flat share over the room (roomShare), a wide penumbra; it shades only casters in its own room.
 * Fires (no switch, no building: campfires, burning squares, burning characters) are kind 4, a wider source (softer); a
 * burning character's light sits at the character (the game moves it a square at a time) and never shades its owner.
 * The shadow stops at the plane of the first wall / room edge past the caster (planeW: the wall's face keeps it along the
 * whole wall, the floor behind gets none). A vehicle's emergency lightbar lights are left out (CapsuleShadow.gatherStatic).
 */
final class StaticLightShadow {
   /** The light kind in CapsuleShadow's lK: a point light of the static world (1 a torch, 2 a vehicle light). */
   static final float KIND = 3F;
   /** A fire's kind (a campfire, a burning square, a burning character): a point light like KIND with a wider source (the
    *  flames: FIRE_SIZE), so its shadows are softer than a lamp's (CapsuleShadow.LIGHT_FRAG). */
   static final float KIND_FIRE = 4F;
   /** The light's size (squares) in the shader's soft-shadow term: a lamp's globe, a fire's flames. */
   static final float LAMP_SIZE = 0.3F;
   static final float FIRE_SIZE = 0.6F;
   /** Heights above the light's level floor, squares (a level is 2.449): a street lamp's head, a lamp or wall light, a fire. */
   static final float STREET_H = 3.0F;
   static final float STREET_UP_H = 1.2F;
   static final float LAMP_H = 1.7F;
   static final float FIRE_H = 0.5F;
   /** A burning character's flames: the body burns, the light's middle is about at its chest. */
   static final float BURN_H = 1.1F;
   /** The shader's saturating gain on the falloff (CapsuleShadow.LIGHT_FRAG torch(): kind 3). */
   static final float GAIN = 2.5F;
   static final int PER_CASTER = 2;
   private static final int LOS_BUDGET = 48; // lineClear tests a frame (the rest wait: cached answers are kept ~2 s)
   private static final long LOS_FRAMES = 240L;

   private StaticLightShadow() {
   }

   /**
    * The light's share of a pixel {@code d} squares from it (reach, strength 0..1): the native's lamp field is linear,
    * (1 - d / r) x colour, over the night ambient; the shadow takes a quadratic of it (saturating under the lamp) so it
    * fades out well inside the pool's edge instead of every caster 20 squares round a street lamp casting a long dark one.
    */
   static float share(float d, float reach, float strength) {
      if (reach <= 0F || d >= reach) {
         return 0F;
      }
      float x = 1F - Math.max(0F, d) / reach;
      return Math.min(1F, GAIN * strength * x * x);
   }

   /** The light's strength as LightingJNI hands it to the native (its brightest channel x 2, clamped). */
   static float strength(float r, float g, float b) {
      return Math.max(0F, Math.min(1F, 2F * Math.max(r, Math.max(g, b))));
   }

   /**
    * Ordering key of a candidate light (smaller first): the distance from the camera to its pool's edge, negative inside
    * the pool (the light the camera stands in comes first).
    */
   static float order(float dx, float dy, float reach) {
      return (float)Math.sqrt(dx * dx + dy * dy) - reach;
   }

   /**
    * The caster's two lights from {@code shares} (one per static slot, 0 = it does not reach the caster), strongest first,
    * into out[0..1] (slot indices, -1 none). Pure.
    */
   static void pickTwo(float[] shares, int n, int[] out) {
      out[0] = -1;
      out[1] = -1;
      float s0 = 0F, s1 = 0F;
      for (int i = 0; i < n; i++) {
         float s = shares[i];
         if (s <= 0.02F) {
            continue;
         }
         if (s > s0) {
            out[1] = out[0];
            s1 = s0;
            out[0] = i;
            s0 = s;
         } else if (s > s1) {
            out[1] = i;
            s1 = s;
         }
      }
   }

   /** Is it a street lamp (its switch's sprite says streetlight)? */
   static boolean street(IsoLightSource l) {
      if (l.switches.isEmpty()) {
         return false;
      }
      IsoLightSwitch s = l.switches.get(0);
      return s != null && s.streetLight;
   }

   /**
    * The light's height (squares, metric: a level is 2.449) above its own level's floor: a street lamp's head (a head tile a
    * level up sits STREET_UP_H above that level), a lamp or wall light, a fire.
    */
   static float height(IsoLightSource l) {
      if (street(l)) {
         return l.z > 0 ? STREET_UP_H : STREET_H;
      }
      return l.switches.isEmpty() ? FIRE_H : LAMP_H;
   }

   /**
    * The colours of a vehicle's emergency lightbar world lights (BaseVehicle leftLight1/2 blue, rightLight1/2 red: no switch,
    * no building, moved with the car and swapped at the flash rate). With a vehicle on the light's square (CapsuleShadow
    * gatherStatic) it is one: not a static light (inside the car it would shade the car itself, and the flashes strobe). Pure.
    */
   static boolean lightbarColour(float r, float g, float b) {
      return g == 0F && (r == 0F && b == 1F || r == 1F && b == 0F);
   }

   /** Is it a fire (no switch, no building: a campfire, a burning square, a burning character's own light)? */
   static boolean fire(IsoLightSource l) {
      return l.switches.isEmpty() && l.localToBuilding == null;
   }

   /**
    * Is the light one the pass casts shadows from: on, with a reach, not a building's window / door ambient light (local to
    * a building with no switch: the daylight a room gets, not a fixture).
    */
   static boolean usable(IsoLightSource l) {
      return l != null && l.active && l.radius > 0 && l.life == -1 && !(l.localToBuilding != null && l.switches.isEmpty());
   }

   // ---- room ceiling lights (sunShadowRoomLights) ----

   /** The light kind in CapsuleShadow's lK: a room's ceiling light (an area light: no cone, a flat share, a big lamp size). */
   static final float ROOM_KIND = 5F;
   /** A ceiling light's height above its level's floor, squares: the fixture just under the ceiling (a level is 2.449). */
   static final float CEIL_H = 2.3F;
   /** The share of the pixel's light a ceiling light's shadow takes away inside its pool (the room is lit evenly: a fill light, softer than a lamp's 1; 0.7 left the shadows on the walls too dark, ceil-probe3). */
   static final float ROOM_SHARE = 0.55F;
   /** The share fades out over the last 1 / ROOM_FADE of the point's reach. */
   static final float ROOM_FADE = 3F;
   /** At most this many points along a room's long side (a hallway's fixtures). */
   static final int ROOM_POINTS = 3;

   /** roomPoints for a one-rectangle room. */
   static int roomPoints(int x, int y, int w, int h, float[] out) {
      return roomPoints(new int[] {x, y, w, h}, 1, out);
   }

   /**
    * A room's ceiling fixtures (the game lights each of its RoomDef rectangles evenly, one IsoRoomLight a rectangle, all
    * switched together; the rectangles are only a decomposition of the room's floor, so the fixtures follow the room, not
    * them): one at the room's area centroid, or, along a long thin room (a hallway, a long shop), k evenly spaced over the
    * long side of its bounding box, k = the long side over 1.5 x the short one (at least 4 squares a cell), at most
    * ROOM_POINTS; each point then moved into the room (half a square in from its walls: an L-shaped room's centroid can
    * lie outside it). {@code rects}: x, y, w, h per rectangle, n of them. Into out[3 i ..]: x, y (world, square units) and
    * the point's reach (its cell's half diagonal + 2 squares: full share over the cell, faded out past the walls). Returns
    * k. Pure.
    */
   static int roomPoints(int[] rects, int n, float[] out) {
      int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
      double ax = 0.0, ay = 0.0, area = 0.0;
      for (int i = 0; i < n; i++) {
         int x = rects[i * 4], y = rects[i * 4 + 1], w = Math.max(1, rects[i * 4 + 2]), h = Math.max(1, rects[i * 4 + 3]);
         x0 = Math.min(x0, x);
         y0 = Math.min(y0, y);
         x1 = Math.max(x1, x + w);
         y1 = Math.max(y1, y + h);
         ax += (double)w * h * (x + 0.5 * w);
         ay += (double)w * h * (y + 0.5 * h);
         area += (double)w * h;
      }
      if (n <= 0) {
         return 0;
      }
      float gx = (float)(ax / area), gy = (float)(ay / area);
      int w = x1 - x0, h = y1 - y0;
      int lng = Math.max(w, h), sht = Math.min(w, h);
      int k = Math.max(1, Math.min(ROOM_POINTS, Math.round(lng / Math.max(1.5F * sht, 4F))));
      float cw = w >= h ? (float)w / k : w, ch = w >= h ? h : (float)h / k;
      float reach = Math.min(14F, 0.5F * (float)Math.sqrt(cw * cw + ch * ch) + 2F);
      for (int i = 0; i < k; i++) {
         float px = k == 1 ? gx : w >= h ? x0 + cw * (i + 0.5F) : gx;
         float py = k == 1 ? gy : w >= h ? gy : y0 + ch * (i + 0.5F);
         // into the room: the nearest point of its rectangles, each half a square in from its edges
         float bx = px, by = py, bd = Float.MAX_VALUE;
         for (int j = 0; j < n; j++) {
            float rx = rects[j * 4], ry = rects[j * 4 + 1], rw = Math.max(1, rects[j * 4 + 2]), rh = Math.max(1, rects[j * 4 + 3]);
            float qx = Math.max(rx + Math.min(0.5F, rw * 0.5F), Math.min(rx + rw - Math.min(0.5F, rw * 0.5F), px));
            float qy = Math.max(ry + Math.min(0.5F, rh * 0.5F), Math.min(ry + rh - Math.min(0.5F, rh * 0.5F), py));
            float d = (qx - px) * (qx - px) + (qy - py) * (qy - py);
            if (d < bd) {
               bd = d;
               bx = qx;
               by = qy;
            }
         }
         out[i * 3] = bx;
         out[i * 3 + 1] = by;
         out[i * 3 + 2] = reach;
      }
      return k;
   }

   /** A ceiling light's share of a pixel d squares from its point (the shader's torch(), kind 5): flat, fading at the reach. */
   static float roomShare(float d, float reach) {
      if (reach <= 0F || d >= reach) {
         return 0F;
      }
      return ROOM_SHARE * Math.min(1F, (1F - Math.max(0F, d) / reach) * ROOM_FADE);
   }

   /** Is the room light on as the native has it (LightingJNI's room light pass: the switch, and power: grid or a powered switch)? */
   static boolean roomOn(IsoRoomLight l) {
      return l != null && l.id != 0 && l.activeJni && l.room != null && l.room.def != null;
   }

   /**
    * reach() for a room's ceiling light point (px, py: world) on square (x, y, z): only a caster inside its own room (same
    * building, same level) and with a clear line from the point's square; then how far its shadow goes before the first wall,
    * closed door or room boundary past the caster (the room's own walls: wallPast). Game thread, cached as reach().
    */
   static int roomReach(IsoCell cell, IsoRoomLight l, float px, float py, float radius, int x, int y, int z) {
      if (l.z != z) {
         return NONE;
      }
      IsoGridSquare sq = cell.getGridSquare(x, y, z);
      if (sq == null || sq.getRoom() != l.room) {
         return NONE;
      }
      return line(cell, (int)Math.floor(px), (int)Math.floor(py), px, py, x, y, z, radius, 2L);
   }

   /** dev: a room light (its room's name and rectangle). */
   static String describe(IsoRoomLight l) {
      if (l.room == null || l.room.def == null) {
         return "room:?";
      }
      zombie.iso.RoomDef d = l.room.def;
      return "room:" + d.name + " " + d.getX() + "," + d.getY() + "-" + d.getX2() + "," + d.getY2() + " (" + d.rects.size() + " rects)";
   }

   // the light -> caster square answers: open addressing, the key packs both squares, the value the frame it was asked and
   // the answer (bit 0)
   private static final int SLOTS = 8192;
   private static final long[] KEYS = new long[SLOTS];
   private static final long[] VALS = new long[SLOTS];
   private static long frame;
   private static int asked;
   static long losTests;
   static long losHits;
   static long losDeferred;

   /** Game thread, once a frame (CapsuleShadow.queue): the frame counter and the lineClear budget. */
   static void beginFrame() {
      frame++;
      asked = 0;
   }

   /** dev (devStaticLightLog): why the last reach() answered as it did: z another level, o its own fire, b another building,
    *  i a street lamp and a caster indoors, f too far, c a cached answer, d deferred (budget), l a line test this frame. */
   static char why;

   /** reach(): the light does not reach the caster. */
   static final int NONE = 0;
   /** reach(): no wall within the shadow's length. */
   static final int OPEN = 0x7FFF;
   /** The shader's value for OPEN (the pair's w: no wall plane; torches and headlights too). */
   static final float OPEN_W = 1e4F;

   /**
    * Does the light reach a caster on square (x, y, z) as the game's lamp model has it: its level, its building when the
    * light is local to one, a clear line from its square (walls and closed doors block, windows do not). NONE when it does
    * not; else the wall its shadows stop at past the caster (wallPast: the first wall, closed door or room boundary on the
    * way out from the light through the caster; a street lamp's light never reaches the room behind that wall, and a lit
    * room's own light outshines it through a window) as a plane code (planeCode), OPEN when there is none within the
    * light's reach. A street lamp shades casters outdoors only. A fire on the caster's own square (a burning character's own
    * light) does not shade it. Game thread; the answers are kept LOS_FRAMES frames, LOS_BUDGET new ones a frame (an unasked
    * pair waits: no shadow for a frame rather than one through a wall).
    */
   static int reach(IsoCell cell, IsoLightSource l, int x, int y, int z) {
      // its own level; a street lamp's head tile one level up (the tall z = 1 lamps) lights the ground below it
      why = 'z';
      if (l.z != z && !(l.z == z + 1 && street(l))) {
         return NONE;
      }
      why = 'o';
      if (l.switches.isEmpty() && l.x == x && l.y == y) {
         return NONE; // a fire on the caster's square, a burning character's own light
      }
      if (l.localToBuilding != null || street(l)) {
         IsoGridSquare sq = cell.getGridSquare(x, y, z);
         why = 'b';
         if (sq == null || l.localToBuilding != null && sq.getBuilding() != l.localToBuilding) {
            return NONE;
         }
         why = 'i';
         if (street(l) && sq.getRoom() != null) {
            return NONE; // a street lamp shades who stands outdoors (indoors the room's own light rules: max of the lights)
         }
      }
      return line(cell, l.x, l.y, l.x + 0.5F, l.y + 0.5F, x, y, z, Math.min(l.radius, 20), (long)(l.z - z & 1));
   }

   /**
    * The cached part of reach() / roomReach(): a clear line from the light's square (lx, ly) to the caster's (x, y) on the
    * caster's level, then the first wall past the caster within {@code radius} along the ray from the light's point (px, py)
    * (wallPast), as a plane code relative to (lx, ly). {@code flags} go into the key (bit 0: a street lamp's head a level up;
    * bit 1: a room light's point).
    */
   private static int line(IsoCell cell, int lx, int ly, float px, float py, int x, int y, int z, float radius, long flags) {
      int dx = x - lx, dy = y - ly;
      why = 'f';
      if (Math.abs(dx) > 120 || Math.abs(dy) > 120) {
         return NONE;
      }
      why = 'c';
      long key = ((long)(lx & 0xFFFF)) | ((long)(ly & 0xFFFF) << 16) | ((long)((dx + 128) & 0xFF) << 32) | ((long)((dy + 128) & 0xFF) << 40)
         | ((long)((z + 64) & 0x7F) << 48) | ((flags & 3L) << 55) | (1L << 62);
      int h = (int)((key * 0x9E3779B97F4A7C15L) >>> 51) & (SLOTS - 1);
      int free = -1;
      for (int p = 0; p < 8; p++) {
         int s = (h + p) & (SLOTS - 1);
         if (KEYS[s] == key) {
            long v = VALS[s];
            if (frame - (v >>> 16) < LOS_FRAMES || asked >= LOS_BUDGET) {
               losHits++;
               return (int)(v & 0xFFFFL);
            }
            free = s;
            break;
         }
         if (free < 0 && (KEYS[s] == 0L || frame - (VALS[s] >>> 16) >= LOS_FRAMES)) {
            free = s;
         }
      }
      if (asked >= LOS_BUDGET) {
         losDeferred++;
         why = 'd';
         return NONE;
      }
      why = 'l';
      asked++;
      losTests++;
      int r = NONE;
      LosUtil.TestResults t = lx == x && ly == y ? LosUtil.TestResults.Clear : LosUtil.lineClear(cell, lx, ly, z, x, y, z, false); // on the caster's level (a head tile's level has no floor to walk)
      if (t != LosUtil.TestResults.Blocked && t != LosUtil.TestResults.ClearThroughClosedDoor) {
         r = wallPast(cell, px, py, lx, ly, x, y, z, radius);
      }
      if (free < 0) {
         free = h; // the probe run is full: take its first slot
      }
      KEYS[free] = key;
      VALS[free] = frame << 16 | (r & 0xFFFFL);
      return r;
   }

   /**
    * A wall plane past the caster as a code (reach()'s answer, cached): 1 + (dc + 64) << 2 | axis << 1 | pos, where the
    * plane is x = c (axis 0) or y = c (axis 1) on a square edge, dc = c minus the light square's coordinate on that axis
    * (-64..63), and pos 1 when the shadow runs towards the larger coordinate (beyond the wall = past c that way). Pure.
    */
   static int planeCode(int axis, int dc, boolean pos) {
      return 1 + (((dc + 64) & 0x7F) << 2 | (axis & 1) << 1 | (pos ? 1 : 0));
   }

   /**
    * The pair's w for the shader (CapsuleShadow LIGHT_VERT / LIGHT_FRAG wallBeyond): OPEN_W, or +-(1000 (x = c) | 2000
    * (y = c) + c + 500) with c relative to the frame's origin square (ox, oy), the sign + when the shadow runs to the larger
    * coordinate. Pure.
    */
   static float planeW(int code, int lx, int ly, int ox, int oy) {
      if (code == NONE || code == OPEN) {
         return OPEN_W;
      }
      int v = code - 1;
      int axis = (v >> 1) & 1, dc = (v >> 2) - 64;
      int c = axis == 0 ? lx + dc - ox : ly + dc - oy;
      float w = (axis == 0 ? 1000F : 2000F) + c + 500F;
      return (v & 1) != 0 ? w : -w;
   }

   /**
    * How far past the wall plane w (planeW) the point (px, py) lies on the shadow's side (negative: before it; -1e4: no
    * plane). The shader's wallBeyond() is this. Pure.
    */
   static float beyond(float w, float px, float py) {
      float a = Math.abs(w);
      if (a > 5000F) {
         return -1e4F;
      }
      float ax = (float)Math.floor(a / 1000F);
      float c = a - ax * 1000F - 500F;
      return Math.signum(w) * ((ax < 1.5F ? px : py) - c);
   }

   /** dev log: the plane's distance from the light (lx, ly: its centre) along the ray to the caster (cx, cy), else OPEN_W. Pure. */
   static float planeDistance(float w, float lx, float ly, float cx, float cy) {
      float a = Math.abs(w);
      if (a > 5000F) {
         return OPEN_W;
      }
      float vx = cx - lx, vy = cy - ly;
      float d = (float)Math.sqrt(vx * vx + vy * vy);
      if (d < 1e-4F) {
         return OPEN_W;
      }
      float ax = (float)Math.floor(a / 1000F);
      float c = a - ax * 1000F - 500F;
      float comp = (ax < 1.5F ? vx : vy) / d;
      if (Math.abs(comp) < 1e-4F) {
         return OPEN_W;
      }
      float t = (c - (ax < 1.5F ? lx : ly)) / comp;
      return t > 0F ? t : OPEN_W;
   }

   /**
    * From the caster's square (x, y) away from the light's point (ox, oy) up to the light's reach: the first square edge a
    * wall or a closed door blocks, or where the ray leaves the caster's room (a window, a doorway: the other side has its own
    * light, the native takes the brightest, so this lamp's share there is not what the shadow would take away), as a plane
    * code (planeCode) relative to the light's square (lx, ly); OPEN when there is none. Game thread.
    */
   static int wallPast(IsoCell cell, float ox, float oy, int lx, int ly, int x, int y, int z, float reach) {
      float cx = x + 0.5F, cy = y + 0.5F;
      float vx = cx - ox, vy = cy - oy;
      float d0 = (float)Math.sqrt(vx * vx + vy * vy);
      if (d0 < 1e-3F) {
         return OPEN;
      }
      vx /= d0;
      vy /= d0;
      IsoGridSquare prev = cell.getGridSquare(x, y, z);
      Object room = prev == null ? null : prev.getRoom();
      int px = x, py = y;
      for (float t = 0.25F; d0 + t <= reach; t += 0.25F) {
         int nx = (int)Math.floor(cx + vx * t), ny = (int)Math.floor(cy + vy * t);
         if (nx == px && ny == py) {
            continue;
         }
         IsoGridSquare sq = cell.getGridSquare(nx, ny, z);
         if (sq != null && prev != null) {
            LosUtil.TestResults r = sq.testVisionAdjacent(px - nx, py - ny, 0, true, false);
            if (r == LosUtil.TestResults.Blocked || r == LosUtil.TestResults.ClearThroughClosedDoor || sq.getRoom() != room) {
               return edgePlane(px, py, nx, ny, cx, cy, vx, vy, lx, ly);
            }
         }
         prev = sq;
         px = nx;
         py = ny;
      }
      return OPEN;
   }

   /**
    * The plane of the edge the step (px, py) -> (nx, ny) crosses on the ray from (cx, cy) along (vx, vy): the x or y edge
    * (a diagonal step: the one the ray crosses last), as a plane code relative to the light's square (lx, ly). Pure.
    */
   static int edgePlane(int px, int py, int nx, int ny, float cx, float cy, float vx, float vy, int lx, int ly) {
      float tx = nx != px && Math.abs(vx) > 1e-5F ? (Math.max(px, nx) - cx) / vx : -1F;
      float ty = ny != py && Math.abs(vy) > 1e-5F ? (Math.max(py, ny) - cy) / vy : -1F;
      if (tx < 0F && ty < 0F) {
         return OPEN;
      }
      return tx >= ty ? planeCode(0, Math.max(px, nx) - lx, nx > px) : planeCode(1, Math.max(py, ny) - ly, ny > py);
   }

   /** dev: what lights it (a street lamp's / lamp's sprite, else none: a fire or another source). */
   static String describe(IsoLightSource l) {
      String room = "";
      IsoGridSquare sq = zombie.iso.IsoWorld.instance == null || zombie.iso.IsoWorld.instance.currentCell == null ? null : zombie.iso.IsoWorld.instance.currentCell.getGridSquare(l.x, l.y, l.z);
      if (sq != null && sq.getRoom() != null && sq.getRoom().getRoomDef() != null) {
         zombie.iso.RoomDef d = sq.getRoom().getRoomDef();
         room = " room " + d.getName() + " " + d.getX() + "," + d.getY() + ".." + (d.getX2() - 1) + "," + (d.getY2() - 1);
      }
      if (l.switches.isEmpty()) {
         return (l.localToBuilding != null ? "building" : "noswitch") + room;
      }
      IsoLightSwitch s = l.switches.get(0);
      String name = s == null || s.getSprite() == null ? "?" : s.getSprite().getName();
      return (s != null && s.streetLight ? "street:" : "lamp:") + name + room;
   }

   static String stats() {
      return "static lights: line tests " + losTests + " cached " + losHits + " deferred " + losDeferred;
   }
}
