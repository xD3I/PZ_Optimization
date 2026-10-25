package pzopt;

import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoLightSource;
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
 */
final class StaticLightShadow {
   /** The light kind in CapsuleShadow's lK: a point light of the static world (1 a torch, 2 a vehicle light). */
   static final float KIND = 3F;
   /** Heights above the light's level floor, squares (a level is 2.449): a street lamp's head, a lamp or wall light, a fire. */
   static final float STREET_H = 3.0F;
   static final float STREET_UP_H = 1.2F;
   static final float LAMP_H = 1.7F;
   static final float FIRE_H = 0.5F;
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
    * Is the light one the pass casts shadows from: on, with a reach, not a building's window / door ambient light (local to
    * a building with no switch: the daylight a room gets, not a fixture).
    */
   static boolean usable(IsoLightSource l) {
      return l != null && l.active && l.radius > 0 && l.life == -1 && !(l.localToBuilding != null && l.switches.isEmpty());
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

   /** reach(): the light does not reach the caster. */
   static final float NONE = -1F;
   /** reach(): no wall within the shadow's length. */
   static final float OPEN = 1e4F;

   /**
    * Does the light reach a caster on square (x, y, z) as the game's lamp model has it: its level, its building when the
    * light is local to one, a clear line from its square (walls and closed doors block, windows do not). NONE when it does
    * not; else how far from the light (squares) its shadows reach past the caster before the first wall, closed door or
    * room boundary on the way out (the shadow stops there: a street lamp's light never reaches the room behind that wall,
    * and a lit room's own light outshines it through a window), OPEN when none within the light's reach. A street lamp
    * shades casters outdoors only. A burning character's own light (no switch, its square) does not shade it. Game thread;
    * the answers are kept LOS_FRAMES frames, LOS_BUDGET new ones a frame (an unasked pair waits: no shadow for a frame
    * rather than one through a wall).
    */
   static float reach(IsoCell cell, IsoLightSource l, int x, int y, int z) {
      // its own level; a street lamp's head tile one level up (the tall z = 1 lamps) lights the ground below it
      if (l.z != z && !(l.z == z + 1 && street(l))) {
         return NONE;
      }
      if (l.switches.isEmpty() && l.x == x && l.y == y) {
         return NONE; // a fire on the caster's square, a burning character's own light
      }
      if (l.localToBuilding != null || street(l)) {
         IsoGridSquare sq = cell.getGridSquare(x, y, z);
         if (sq == null || l.localToBuilding != null && sq.getBuilding() != l.localToBuilding) {
            return NONE;
         }
         if (street(l) && sq.getRoom() != null) {
            return NONE; // a street lamp shades who stands outdoors (indoors the room's own light rules: max of the lights)
         }
      }
      int dx = x - l.x, dy = y - l.y;
      if (Math.abs(dx) > 120 || Math.abs(dy) > 120) {
         return NONE;
      }
      long key = ((long)(l.x & 0xFFFF)) | ((long)(l.y & 0xFFFF) << 16) | ((long)((dx + 128) & 0xFF) << 32) | ((long)((dy + 128) & 0xFF) << 40)
         | ((long)((z + 64) & 0x7F) << 48) | ((long)(l.z - z & 1) << 55) | (1L << 62);
      int h = (int)((key * 0x9E3779B97F4A7C15L) >>> 51) & (SLOTS - 1);
      int free = -1;
      for (int p = 0; p < 8; p++) {
         int s = (h + p) & (SLOTS - 1);
         if (KEYS[s] == key) {
            long v = VALS[s];
            if (frame - (v >>> 16) < LOS_FRAMES || asked >= LOS_BUDGET) {
               losHits++;
               return decode(v);
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
         return NONE;
      }
      asked++;
      losTests++;
      float r = NONE;
      LosUtil.TestResults t = l.x == x && l.y == y ? LosUtil.TestResults.Clear : LosUtil.lineClear(cell, l.x, l.y, z, x, y, z, false); // on the caster's level (a head tile's level has no floor to walk)
      if (t != LosUtil.TestResults.Blocked && t != LosUtil.TestResults.ClearThroughClosedDoor) {
         r = wallPast(cell, l.x + 0.5F, l.y + 0.5F, x, y, z, Math.min(l.radius, 20));
      }
      if (free < 0) {
         free = h; // the probe run is full: take its first slot
      }
      KEYS[free] = key;
      VALS[free] = frame << 16 | encode(r);
      return r;
   }

   /** The cached value's low 16 bits: 0 = NONE, 0x7FFF = OPEN, else 1 + the limit in 1/16 squares. */
   static long encode(float r) {
      if (r < 0F) {
         return 0L;
      }
      if (r >= OPEN * 0.5F) {
         return 0x7FFFL;
      }
      return 1L + Math.min(0x7FFDL, (long)(r * 16F));
   }

   static float decode(long v) {
      long c = v & 0xFFFFL;
      return c == 0L ? NONE : c == 0x7FFFL ? OPEN : (c - 1L) / 16F;
   }

   /**
    * From the caster's square (x, y) away from the light at (lx, ly) up to the light's reach: the distance from the light
    * of the first edge a wall or a closed door blocks (squares, at the edge; OPEN when there is none). Game thread.
    */
   static float wallPast(IsoCell cell, float lx, float ly, int x, int y, int z, float reach) {
      float cx = x + 0.5F, cy = y + 0.5F;
      float vx = cx - lx, vy = cy - ly;
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
            // a wall or a closed door; or into another room / out of it (a window, a doorway: the other side has its own
            // light, the native takes the brightest, so this lamp's share there is not what the shadow would take away)
            LosUtil.TestResults r = sq.testVisionAdjacent(px - nx, py - ny, 0, true, false);
            if (r == LosUtil.TestResults.Blocked || r == LosUtil.TestResults.ClearThroughClosedDoor || sq.getRoom() != room) {
               // the ray's crossing of the square's edge (the later one of a diagonal step), plus the fragment fade's half width:
               // the wall's face keeps its shadow, the floor behind it none
               float te = 0F;
               if (nx != px && Math.abs(vx) > 1e-5F) {
                  te = Math.max(te, (Math.max(px, nx) - cx) / vx);
               }
               if (ny != py && Math.abs(vy) > 1e-5F) {
                  te = Math.max(te, (Math.max(py, ny) - cy) / vy);
               }
               return d0 + Math.max(0F, Math.min(t, te)) + 0.08F;
            }
         }
         prev = sq;
         px = nx;
         py = ny;
      }
      return OPEN;
   }

   /** dev: what lights it (a street lamp's / lamp's sprite, else none: a fire or another source). */
   static String describe(IsoLightSource l) {
      if (l.switches.isEmpty()) {
         return l.localToBuilding != null ? "building" : "noswitch";
      }
      IsoLightSwitch s = l.switches.get(0);
      String name = s == null || s.getSprite() == null ? "?" : s.getSprite().getName();
      return (s != null && s.streetLight ? "street:" : "lamp:") + name;
   }

   static String stats() {
      return "static lights: line tests " + losTests + " cached " + losHits + " deferred " + losDeferred;
   }
}
