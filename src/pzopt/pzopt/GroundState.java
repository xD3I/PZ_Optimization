package pzopt;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.joml.Vector3f;
import zombie.core.SpriteRenderer;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.vehicles.BaseVehicle;

/**
 * Ground and grass, shared part (2026-10-24): what the ground remembers (ruts, footprints, compaction), as a world-anchored
 * toroidal texture the bake, the composite and the blades read, kept per save on disk.
 *
 * <h2>The map</h2>
 * {@link #SQUARES} x {@link #SQUARES} squares round the player at {@link #TPS} texels a square: a 1024 x 1024 RGBA8 texture
 * (4 MB of VRAM, 4 MB of CPU mirror), texel (floor(x * TPS) & 1023, floor(y * TPS) & 1023) for world square position (x, y),
 * GL_REPEAT, linear filtering. It holds 16 x 16 chunks: chunk (wx, wy) lives in slot (wx & 15, wy & 15) while it is inside
 * the 16 x 16 chunk window round the player's chunk; when the player moves, the slots that change chunk are saved (if
 * touched), cleared and loaded with their new chunk. Channels (the default {@link Rules}; a feature may give them its own
 * meaning through its rules):
 * <ul>
 *   <li>R: rut / print depth 0..1;</li>
 *   <li>G: compaction (how trodden: horde paths, the grass flattened) 0..1;</li>
 *   <li>B: berm (soil pushed up beside a rut) 0..1;</li>
 *   <li>A: when it was last touched: game time in eighths of a day, mod 256 ({@link #nowCode()}; 32 days round).</li>
 * </ul>
 *
 * <h2>Writing</h2>
 * Game thread only. {@link #stampSegment} (a capsule: a wheel's line from its last position, a drag) and {@link #stampDisc}
 * (a foot plant, a body) write through the {@link Rules} ({@link #setRules}); the softness of each square (from its ground
 * class: road and floors take nothing) scales the stamp. Built-in sources while the map is active: every moving vehicle's
 * wheels on level 0 (capsules from their last position) and every character on level 0 (a foot plant every 0.45 squares,
 * left and right); {@link #setSources} switches them. A feature turns the map on by calling {@link #use()} once a frame (the
 * tracks key does it through {@link Ground}); with nobody using it nothing runs, no texture exists.
 *
 * <h2>Reading</h2>
 * GLSL {@link #GLSL}: {@code vec4 pzgStateAt(vec2 world)} and {@code float pzgStateAge(float a)} (game days since the
 * texel was touched). The floor variant has it built in ({@link GroundBake}); another program declares {@link #GLSL},
 * calls {@link #bindTexture()} (unit {@link #UNIT}) and {@link #applyUniforms(int)} on the render thread. The CPU side reads
 * {@link #sample(float, float)}.
 *
 * <h2>Uploads</h2>
 * The game thread marks 32 x 32-texel tiles dirty; at most {@link #MAX_TILES_PER_FRAME} are copied into a pooled buffer at
 * the frame's start and uploaded on the render thread through a pixel buffer (on Mesa a client-memory sub-image waits for
 * the driver thread). No glGet, no read back.
 *
 * <h2>Change listeners</h2>
 * {@link #addListener(ChangeListener)}: called on the game thread with the chunks whose state changed (at most every
 * {@link #CHANGE_MS} per chunk), for a feature that bakes the tracks into the chunk textures (it then calls
 * GroundBake.rebakeChunk). {@link #rebakeOnChange(boolean)} does that for it.
 *
 * <h2>Decay</h2>
 * {@link Rules#decay}: run over the resident chunks a few tiles a frame and at load; the default clears texels older than
 * groundTracksDays (the shader fades by {@code pzgStateAge} meanwhile).
 *
 * <h2>Persistence</h2>
 * {@code groundTracksPersist}: touched chunks go to {@code ~/Zomboid/pzopt/ground/<mode>/<save>/r_<rx>_<ry>.gsr} (never
 * inside the save; one region file per 16 x 16 chunks: magic, version, 256 offsets / lengths, deflated chunk blobs with the
 * game hour they were written at), on one background thread: when a chunk leaves the window, every 30 s, at
 * {@link #flush()}. A chunk entering the window is read on that thread and applied on the game thread if its slot still
 * holds it.
 */
public final class GroundState {
   private GroundState() {
   }

   public static final int TPS = 8, SQUARES = 128, SIZE = TPS * SQUARES, CHUNK_TEX = 8 * TPS, SLOTS = SQUARES / 8;
   public static final int TILE = 32, TILES = SIZE / TILE, MAX_TILES_PER_FRAME = 48, UNIT = GroundBake.STATE_UNIT;
   public static final long CHANGE_MS = 250L;

   // ------------------------------------------------------------------------------------------------ the API

   /** How a stamp writes and how the state decays (the tracks feature fills its own in). */
   public interface Rules {
      /**
       * Writes one texel: {@code px[o .. o + 3]} (RGBA, unsigned bytes) under a stamp. {@code w} 0..1 is the stamp's weight
       * at the texel (1 inside, falling off at the edge), {@code rim} 0..1 the berm ring just outside it, {@code soft} the
       * square's softness, {@code now} {@link #nowCode()}.
       */
      void stamp(byte[] px, int o, float w, float rim, float soft, Stamp s, int now);

      /** A texel of a resident chunk, {@code days} game days after it was last touched: change it (0 = gone). */
      void decay(byte[] px, int o, float days);

      /** 0..1: how much a square of ground class {@code code} (GroundTiles.square) takes a print. */
      default float softness(int code, IsoGridSquare sq) {
         return defaultSoftness(code);
      }
   }

   /** One stamp: kind and strengths (a feature's rules read what they need). */
   public static final class Stamp {
      public static final int WHEEL = 1, FOOT = 2, BODY = 3, OTHER = 4;
      public int kind = OTHER;
      public float depth = 0.5F, compact = 0.2F, berm = 0.3F;
      /** The source (a BaseVehicle, an IsoGameCharacter, or null). */
      public Object source;
      /** The direction of travel (unit, world), when known. */
      public float dirX, dirY;
   }

   public interface ChangeListener {
      /** Game thread: chunk (wx, wy)'s ground state changed. */
      void changed(int wx, int wy);
   }

   private static volatile Rules rules = new DefaultRules();
   private static final CopyOnWriteArrayList<ChangeListener> LISTENERS = new CopyOnWriteArrayList<>();
   private static boolean rebakeOnChange;
   private static boolean srcVehicles = true, srcCharacters = true;
   private static int usedFrame = -100;

   public static void setRules(Rules r) {
      rules = r == null ? new DefaultRules() : r;
   }

   public static Rules rules() {
      return rules;
   }

   public static void addListener(ChangeListener l) {
      LISTENERS.addIfAbsent(l);
   }

   public static void removeListener(ChangeListener l) {
      LISTENERS.remove(l);
   }

   /** Changed chunks re-bake their level 0 (low priority) by themselves. */
   public static void rebakeOnChange(boolean on) {
      rebakeOnChange = on;
   }

   /** The built-in stamp sources (vehicle wheels, character feet). */
   public static void setSources(boolean vehicles, boolean characters) {
      srcVehicles = vehicles;
      srcCharacters = characters;
   }

   /** Game thread, once a frame: a feature reads or writes the map this frame (keeps it active). */
   public static void use() {
      usedFrame = frame;
   }

   /** The map is active this frame (someone uses it, or a dev rig runs). */
   public static boolean active() {
      return activeNow;
   }

   /** Game time in eighths of a game day, mod 256 (the A channel's clock). */
   public static int nowCode() {
      return nowCode;
   }

   static float defaultSoftness(int code) {
      if (!GroundTiles.natural(code)) {
         return 0F;
      }
      switch (GroundTiles.material(code)) {
         case GroundTiles.DIRT:
         case GroundTiles.DARK_DIRT:
         case GroundTiles.SAND:
            return 1F;
         case GroundTiles.CLAY:
            return 0.9F;
         case GroundTiles.GRASS:
            return 0.55F;
         case GroundTiles.GRAVEL:
            return 0.25F;
         default:
            return 0F;
      }
   }

   /** The defaults: depth and berm keep their maximum, compaction adds up, decay clears after groundTracksDays. */
   public static class DefaultRules implements Rules {
      @Override
      public void stamp(byte[] px, int o, float w, float rim, float soft, Stamp s, int now) {
         if (w > 0F) {
            int d = (int)(w * soft * s.depth * 255F + 0.5F);
            if (d > (px[o] & 255)) {
               px[o] = (byte)d;
            }
            int c = (px[o + 1] & 255) + (int)(w * soft * s.compact * 255F * 0.25F + 0.5F);
            px[o + 1] = (byte)Math.min(255, c);
         }
         if (rim > 0F) {
            int b = (int)(rim * soft * s.berm * 255F + 0.5F);
            if (b > (px[o + 2] & 255)) {
               px[o + 2] = (byte)b;
            }
         }
         if (w > 0.05F || rim > 0.05F) {
            px[o + 3] = (byte)now;
         }
      }

      @Override
      public void decay(byte[] px, int o, float days) {
         if (days > Config.GROUND_TRACKS_DAYS) {
            px[o] = px[o + 1] = px[o + 2] = px[o + 3] = 0;
         }
      }
   }

   // ------------------------------------------------------------------------------------------------ state (game thread)

   private static byte[] px; // SIZE x SIZE x 4, row-major
   private static final long[] dirty = new long[TILES * TILES / 64];
   private static final int[] resident = new int[SLOTS * SLOTS]; // packed chunk per slot, EMPTY when none
   private static final boolean[] touched = new boolean[SLOTS * SLOTS]; // has non-zero texels
   private static final boolean[] modified = new boolean[SLOTS * SLOTS]; // changed since its last save / load
   private static final long[] changedAt = new long[SLOTS * SLOTS];
   private static final boolean[] changedPending = new boolean[SLOTS * SLOTS];
   private static final int EMPTY = Integer.MIN_VALUE;
   private static int frame, nowCode;
   private static boolean activeNow, wasActive;
   private static int pcx = Integer.MIN_VALUE, pcy = Integer.MIN_VALUE;
   private static String saveId;
   private static long lastFlushMs;
   private static int decayCursor;
   public static long stamps, texelsWritten, tilesUploaded, chunksSaved, chunksLoaded, chunksEvicted, uploadsDeferred;

   static {
      java.util.Arrays.fill(resident, EMPTY);
   }

   private static int pack(int wx, int wy) {
      return (wx & 0xFFFF) << 16 | (wy & 0xFFFF);
   }

   private static int slotOf(int wx, int wy) {
      return (wy & (SLOTS - 1)) * SLOTS + (wx & (SLOTS - 1));
   }

   /** Ground.frameStart (game thread). */
   static void frameStart(int playerIndex) {
      frame++;
      boolean want = usedFrame >= frame - 2 || Ground.on(Ground.K_TRACKS) || Config.DEV_GROUND_STATE_TEST || Config.DEV_GROUND_WALKERS > 0;
      want &= GroundBake.supported();
      if (!want) {
         if (wasActive) {
            flush();
            wasActive = false;
            activeNow = false;
            BIND_ON = false;
         }
         return;
      }
      zombie.characters.IsoPlayer pl = zombie.characters.IsoPlayer.players[playerIndex];
      if (pl == null || playerIndex != 0) {
         return; // (split screen: the map follows player 1)
      }
      if (!wasActive) {
         wasActive = true;
         if (px == null) {
            px = new byte[SIZE * SIZE * 4];
         }
         Log.info("ground state: on (" + SIZE + " x " + SIZE + " RGBA8, " + TPS + " texels a square, persist " + Config.GROUND_TRACKS_PERSIST + ")");
      }
      activeNow = true;
      BIND_ON = true;
      try {
         nowCode = (int)Math.floor(zombie.GameTime.getInstance().getWorldAgeHours() / 3.0) & 255;
      } catch (Throwable t) {
         nowCode = 0;
      }
      String sid = zombie.core.Core.gameMode + "/" + zombie.core.Core.gameSaveWorld;
      if (!sid.equals(saveId)) {
         if (saveId != null) {
            flush();
         }
         clearAll();
         saveId = sid;
      }
      applyLoads();
      int cx = (int)Math.floor(pl.getX() / 8.0), cy = (int)Math.floor(pl.getY() / 8.0);
      if (cx != pcx || cy != pcy) {
         pcx = cx;
         pcy = cy;
         window();
      }
      long t0 = System.nanoTime();
      if (srcVehicles) {
         vehicles();
      }
      if (srcCharacters) {
         characters();
      }
      if (Config.DEV_GROUND_WALKERS > 0) {
         walkers(pl, Config.DEV_GROUND_WALKERS);
      }
      if (Config.DEV_GROUND_STATE_TEST && frame % 60 == 0) {
         testPattern(pl);
      }
      decay(4);
      notifyChanged();
      stampNs += System.nanoTime() - t0;
      upload();
      long now = System.currentTimeMillis();
      if (now - lastFlushMs > 30_000L) {
         lastFlushMs = now;
         saveModified(false);
      }
   }

   public static long stampNs;

   private static void clearAll() {
      if (px != null) {
         java.util.Arrays.fill(px, (byte)0);
      }
      java.util.Arrays.fill(resident, EMPTY);
      java.util.Arrays.fill(touched, false);
      java.util.Arrays.fill(modified, false);
      java.util.Arrays.fill(dirty, texMade ? -1L : 0L);
      LOADS.clear();
      pcx = pcy = Integer.MIN_VALUE;
   }

   /** The 16 x 16 chunk window round the player's chunk: slots that change chunk are saved, cleared and loaded. */
   private static void window() {
      int ox = pcx - SLOTS / 2, oy = pcy - SLOTS / 2;
      for (int sy = 0; sy < SLOTS; sy++) {
         for (int sx = 0; sx < SLOTS; sx++) {
            int wx = ox + Math.floorMod(sx - ox, SLOTS), wy = oy + Math.floorMod(sy - oy, SLOTS);
            int s = sy * SLOTS + sx;
            int want = pack(wx, wy);
            if (resident[s] == want) {
               continue;
            }
            if (resident[s] != EMPTY) {
               if (touched[s] && modified[s] && Config.GROUND_TRACKS_PERSIST) {
                  save(s, (short)(resident[s] >> 16), (short)resident[s]);
               }
               if (touched[s]) {
                  clearSlot(s);
               }
               chunksEvicted++;
            }
            resident[s] = want;
            touched[s] = false;
            modified[s] = false;
            if (Config.GROUND_TRACKS_PERSIST && saveId != null) {
               load(wx, wy);
            }
         }
      }
   }

   private static void clearSlot(int s) {
      int sx = s % SLOTS, sy = s / SLOTS;
      for (int ty = 0; ty < CHUNK_TEX; ty++) {
         int row = ((sy * CHUNK_TEX + ty) * SIZE + sx * CHUNK_TEX) * 4;
         java.util.Arrays.fill(px, row, row + CHUNK_TEX * 4, (byte)0);
      }
      markChunkDirty(sx, sy);
   }

   private static void markChunkDirty(int sx, int sy) {
      int per = CHUNK_TEX / TILE;
      for (int j = 0; j < per; j++) {
         for (int i = 0; i < per; i++) {
            markTile(sx * per + i, sy * per + j);
         }
      }
   }

   private static void markTile(int tx, int ty) {
      int t = ty * TILES + tx;
      dirty[t >> 6] |= 1L << (t & 63);
   }

   // ------------------------------------------------------------------------------------------------ stamping

   private static final Stamp TMP = new Stamp();
   private static final float[] SOFT = new float[64];

   /** Game thread: a capsule from (ax, ay) to (bx, by), radius {@code r} squares, at level 0 (world square units). */
   public static void stampSegment(float ax, float ay, float bx, float by, float r, Stamp s) {
      if (!activeNow || px == null) {
         return;
      }
      float rim = r * 1.7F;
      float x0 = Math.min(ax, bx) - rim, x1 = Math.max(ax, bx) + rim, y0 = Math.min(ay, by) - rim, y1 = Math.max(ay, by) + rim;
      // inside the window only
      float wx0 = (pcx - SLOTS / 2) * 8F, wy0 = (pcy - SLOTS / 2) * 8F;
      if (x1 < wx0 || y1 < wy0 || x0 >= wx0 + SQUARES || y0 >= wy0 + SQUARES) {
         return;
      }
      x0 = Math.max(x0, wx0);
      y0 = Math.max(y0, wy0);
      x1 = Math.min(x1, wx0 + SQUARES - 1e-3F);
      y1 = Math.min(y1, wy0 + SQUARES - 1e-3F);
      int sqx0 = (int)Math.floor(x0), sqy0 = (int)Math.floor(y0), sqx1 = (int)Math.floor(x1), sqy1 = (int)Math.floor(y1);
      int nsx = sqx1 - sqx0 + 1, nsy = sqy1 - sqy0 + 1;
      if (nsx * nsy > SOFT.length) {
         return; // (a stamp over more than 64 squares: not a track)
      }
      Rules ru = rules;
      zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
      boolean any = false;
      for (int j = 0; j < nsy; j++) {
         for (int i = 0; i < nsx; i++) {
            IsoGridSquare sq = cell == null ? null : cell.getGridSquare(sqx0 + i, sqy0 + j, 0);
            float sf = sq == null ? 0F : ru.softness(GroundTiles.square(sq), sq);
            SOFT[j * nsx + i] = sf;
            any |= sf > 0F;
         }
      }
      if (!any) {
         return;
      }
      stamps++;
      float dx = bx - ax, dy = by - ay;
      float ll = dx * dx + dy * dy;
      int tx0 = (int)Math.floor(x0 * TPS), tx1 = (int)Math.floor(x1 * TPS), ty0 = (int)Math.floor(y0 * TPS), ty1 = (int)Math.floor(y1 * TPS);
      float inner = r * 0.55F;
      for (int ty = ty0; ty <= ty1; ty++) {
         float py = (ty + 0.5F) / TPS;
         int sj = (int)Math.floor(py) - sqy0;
         for (int tx = tx0; tx <= tx1; tx++) {
            float pxw = (tx + 0.5F) / TPS;
            float sf = SOFT[sj * nsx + ((int)Math.floor(pxw) - sqx0)];
            if (sf <= 0F) {
               continue;
            }
            float t = ll > 1e-8F ? Math.max(0F, Math.min(1F, ((pxw - ax) * dx + (py - ay) * dy) / ll)) : 0F;
            float ex = pxw - (ax + t * dx), ey = py - (ay + t * dy);
            float d = (float)Math.sqrt(ex * ex + ey * ey);
            if (d >= rim) {
               continue;
            }
            float w = d <= inner ? 1F : d >= r ? 0F : 1F - smooth((d - inner) / (r - inner));
            float rm = d <= r * 0.8F ? 0F : 1F - smooth(Math.abs(d - r * 1.25F) / (r * 0.45F));
            int mx = tx & (SIZE - 1), my = ty & (SIZE - 1);
            int o = (my * SIZE + mx) * 4;
            ru.stamp(px, o, w, Math.max(0F, rm), sf, s, nowCode);
            texelsWritten++;
         }
      }
      // the tiles and chunks touched
      for (int ty = ty0 / TILE; ty <= ty1 / TILE; ty++) {
         for (int tx = tx0 / TILE; tx <= tx1 / TILE; tx++) {
            markTile(tx & (TILES - 1), ty & (TILES - 1));
         }
      }
      for (int cy = sqy0 >> 3; cy <= sqy1 >> 3; cy++) {
         for (int cx = sqx0 >> 3; cx <= sqx1 >> 3; cx++) {
            int sl = slotOf(cx, cy);
            touched[sl] = true;
            modified[sl] = true;
            changedPending[sl] = true;
         }
      }
   }

   /** Game thread: a disc of radius {@code r} squares at (x, y), level 0. */
   public static void stampDisc(float x, float y, float r, Stamp s) {
      stampSegment(x, y, x, y, r, s);
   }

   private static float smooth(float t) {
      t = Math.max(0F, Math.min(1F, t));
      return t * t * (3F - 2F * t);
   }

   /** Game thread: the state at world (x, y) as 0..1 RGBA into {@code out} (nearest texel); false outside the window. */
   public static boolean sample(float x, float y, float[] out) {
      if (px == null || !activeNow) {
         return false;
      }
      float wx0 = (pcx - SLOTS / 2) * 8F, wy0 = (pcy - SLOTS / 2) * 8F;
      if (x < wx0 || y < wy0 || x >= wx0 + SQUARES || y >= wy0 + SQUARES) {
         return false;
      }
      int o = ((((int)Math.floor(y * TPS)) & (SIZE - 1)) * SIZE + (((int)Math.floor(x * TPS)) & (SIZE - 1))) * 4;
      for (int i = 0; i < 4; i++) {
         out[i] = (px[o + i] & 255) / 255F;
      }
      return true;
   }

   // ------------------------------------------------------------------------------------------------ built-in sources

   private static final class Trace {
      float x, y, dist;
      float[] wx = new float[8], wy = new float[8];
      int wheels, seen;
      boolean left;
   }

   private static final IdentityHashMap<Object, Trace> TRACES = new IdentityHashMap<>();
   private static final Vector3f WP = new Vector3f();
   private static final Stamp WHEEL = new Stamp(), FOOT = new Stamp();

   static {
      WHEEL.kind = Stamp.WHEEL;
      WHEEL.depth = 0.7F;
      WHEEL.compact = 0.6F;
      WHEEL.berm = 0.5F;
      FOOT.kind = Stamp.FOOT;
      FOOT.depth = 0.35F;
      FOOT.compact = 0.3F;
      FOOT.berm = 0.15F;
   }

   private static void vehicles() {
      zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
      if (cell == null) {
         return;
      }
      for (BaseVehicle v : cell.getVehicles()) {
         if (v == null || v.getZ() >= 0.5F) {
            continue;
         }
         zombie.scripting.objects.VehicleScript script = v.getScript();
         if (script == null) {
            continue;
         }
         Trace tr = TRACES.get(v);
         if (tr == null) {
            tr = new Trace();
            TRACES.put(v, tr);
            tr.wheels = -1;
         }
         tr.seen = frame;
         int n = Math.min(8, script.getWheelCount());
         Vector3f mo = script.getModel() != null ? script.getModel().offset : null;
         float cx = v.getX(), cy = v.getY();
         float moved = (cx - tr.x) * (cx - tr.x) + (cy - tr.y) * (cy - tr.y);
         if (tr.wheels == n && moved < 0.0016F) {
            continue; // parked or creeping (0.04 squares): no new segment yet
         }
         boolean jump = moved > 9F || tr.wheels != n;
         tr.x = cx;
         tr.y = cy;
         float dirX = 0F, dirY = 0F;
         for (int w = 0; w < n; w++) {
            Vector3f off = script.getWheel(w).offset;
            v.getWorldPos(off.x + (mo != null ? mo.x : 0F), 0F, off.z + (mo != null ? mo.z : 0F), WP, script);
            if (!jump) {
               float r = Math.max(0.08F, script.getWheel(w).width * 0.5F);
               WHEEL.source = v;
               float ddx = WP.x - tr.wx[w], ddy = WP.y - tr.wy[w];
               float l = (float)Math.sqrt(ddx * ddx + ddy * ddy);
               WHEEL.dirX = l > 1e-4F ? ddx / l : 0F;
               WHEEL.dirY = l > 1e-4F ? ddy / l : 0F;
               stampSegment(tr.wx[w], tr.wy[w], WP.x, WP.y, r, WHEEL);
            }
            tr.wx[w] = WP.x;
            tr.wy[w] = WP.y;
         }
         tr.wheels = n;
      }
   }

   private static void characters() {
      zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
      if (cell == null) {
         return;
      }
      for (IsoMovingObject o : cell.getObjectList()) {
         if (!(o instanceof zombie.characters.IsoGameCharacter c) || o.getZ() >= 0.5F || c.getVehicle() != null) {
            continue;
         }
         foot(o, o.getX(), o.getY(), c.isOnFloor() ? 0.3F : 0F);
      }
      if (frame % 120 == 0) {
         TRACES.values().removeIf(t -> t.seen < frame - 120);
      }
   }

   /** One character: a foot plant every 0.45 squares travelled, alternating sides; {@code body} > 0 also presses a body disc. */
   private static void foot(Object key, float x, float y, float body) {
      Trace tr = TRACES.get(key);
      if (tr == null) {
         tr = new Trace();
         tr.x = x;
         tr.y = y;
         TRACES.put(key, tr);
      }
      tr.seen = frame;
      float dx = x - tr.x, dy = y - tr.y;
      float d = (float)Math.sqrt(dx * dx + dy * dy);
      if (d > 4F) {
         tr.x = x;
         tr.y = y;
         tr.dist = 0F;
         return;
      }
      if (body > 0F && d < 0.01F) {
         return;
      }
      tr.dist += d;
      tr.x = x;
      tr.y = y;
      if (tr.dist < 0.45F || d < 1e-4F) {
         return;
      }
      tr.dist = 0F;
      float nx = dx / d, ny = dy / d;
      float side = tr.left ? 0.11F : -0.11F;
      tr.left = !tr.left;
      FOOT.source = key;
      FOOT.dirX = nx;
      FOOT.dirY = ny;
      float fx = x - ny * side, fy = y + nx * side;
      stampSegment(fx - nx * 0.06F, fy - ny * 0.06F, fx + nx * 0.06F, fy + ny * 0.06F, 0.07F, FOOT);
   }

   /** devGroundWalkers: N synthetic walkers circling the player (the stamping cost rig). */
   private static final ArrayList<Object> WALKER_KEYS = new ArrayList<>();

   private static void walkers(zombie.characters.IsoPlayer pl, int n) {
      while (WALKER_KEYS.size() < n) {
         WALKER_KEYS.add(new Object());
      }
      double t = System.nanoTime() / 1e9;
      for (int i = 0; i < n; i++) {
         double r = 3.0 + (i % 20) * 0.9;
         double a = t * (1.2 / r) * 1.4 + i * 2.399963;
         foot(WALKER_KEYS.get(i), (float)(pl.getX() + Math.cos(a) * r), (float)(pl.getY() + Math.sin(a) * r), 0F);
      }
   }

   private static void testPattern(zombie.characters.IsoPlayer pl) {
      float x = (float)Math.floor(pl.getX()) + 0.5F, y = (float)Math.floor(pl.getY()) + 0.5F;
      Stamp s = TMP;
      s.kind = Stamp.OTHER;
      s.depth = 1F;
      s.compact = 1F;
      s.berm = 1F;
      for (int i = -6; i <= 6; i += 3) {
         stampSegment(x - 6, y + i, x + 6, y + i, 0.25F, s);
         stampDisc(x + i, y - 8, 0.2F + 0.05F * (i + 6), s);
      }
   }

   // ------------------------------------------------------------------------------------------------ decay, listeners

   private static void decay(int tilesPerFrame) {
      Rules ru = rules;
      int per = CHUNK_TEX / TILE;
      for (int k = 0; k < tilesPerFrame; k++) {
         int t = decayCursor;
         decayCursor = (decayCursor + 1) % (TILES * TILES);
         int tx = t % TILES, ty = t / TILES;
         int s = (ty / per) * SLOTS + tx / per;
         if (!touched[s]) {
            continue;
         }
         boolean changed = false;
         for (int y = 0; y < TILE; y++) {
            int o = ((ty * TILE + y) * SIZE + tx * TILE) * 4;
            for (int x = 0; x < TILE; x++, o += 4) {
               if (px[o] == 0 && px[o + 1] == 0 && px[o + 2] == 0) {
                  continue;
               }
               int age = (nowCode - (px[o + 3] & 255)) & 255;
               int b0 = px[o], b1 = px[o + 1], b2 = px[o + 2];
               ru.decay(px, o, age / 8F);
               changed |= b0 != px[o] || b1 != px[o + 1] || b2 != px[o + 2];
            }
         }
         if (changed) {
            markTile(tx, ty);
            modified[s] = true;
         }
      }
   }

   private static void notifyChanged() {
      if (LISTENERS.isEmpty() && !rebakeOnChange) {
         java.util.Arrays.fill(changedPending, false);
         return;
      }
      long now = System.currentTimeMillis();
      for (int s = 0; s < SLOTS * SLOTS; s++) {
         if (!changedPending[s] || now - changedAt[s] < CHANGE_MS || resident[s] == EMPTY) {
            continue;
         }
         changedPending[s] = false;
         changedAt[s] = now;
         int wx = (short)(resident[s] >> 16), wy = (short)resident[s];
         for (ChangeListener l : LISTENERS) {
            try {
               l.changed(wx, wy);
            } catch (Throwable t) {
               Log.warn("ground state: listener failed and is off: " + t);
               LISTENERS.remove(l);
            }
         }
         if (rebakeOnChange) {
            GroundBake.rebakeChunk(wx, wy, "tracks", true);
         }
      }
   }

   // ------------------------------------------------------------------------------------------------ upload

   private static final ArrayDeque<Upload> FREE = new ArrayDeque<>();

   private static final class Upload extends TextureDraw.GenericDrawer {
      final ByteBuffer buf = BufferUtils.createByteBuffer(MAX_TILES_PER_FRAME * TILE * TILE * 4);
      final int[] tiles = new int[MAX_TILES_PER_FRAME];
      int n;
      boolean full; // the first upload: the whole texture from zero

      @Override
      public void render() {
         uploadRender(this);
      }

      @Override
      public void postRender() {
         synchronized (FREE) {
            if (FREE.size() < 4) {
               FREE.add(this);
            }
         }
      }
   }

   private static volatile boolean texMade;

   private static void upload() {
      Upload u = null;
      for (int w = 0; w < dirty.length; w++) {
         long b = dirty[w];
         while (b != 0L) {
            int bit = Long.numberOfTrailingZeros(b);
            b &= b - 1;
            if (u == null) {
               synchronized (FREE) {
                  u = FREE.poll();
               }
               if (u == null) {
                  u = new Upload();
               }
               u.n = 0;
               u.buf.clear();
            }
            if (u.n == MAX_TILES_PER_FRAME) {
               uploadsDeferred++;
               w = dirty.length; // the rest next frame
               break;
            }
            int t = w * 64 + bit;
            dirty[w] &= ~(1L << bit);
            int tx = t % TILES, ty = t / TILES;
            for (int y = 0; y < TILE; y++) {
               u.buf.put(px, ((ty * TILE + y) * SIZE + tx * TILE) * 4, TILE * 4);
            }
            u.tiles[u.n++] = t;
         }
      }
      if (u != null && u.n > 0) {
         u.buf.flip();
         SpriteRenderer.instance.drawGeneric(u);
      } else if (u != null) {
         synchronized (FREE) {
            FREE.add(u);
         }
      }
   }

   private static int tex, pbo;
   static volatile boolean BIND_ON;

   private static void uploadRender(Upload u) {
      GpuSections.markNow(Ground.section("ground.state"), false);
      if (tex == 0) {
         tex = GL11.glGenTextures();
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL, 0);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, BufferUtils.createByteBuffer(SIZE * SIZE * 4));
         pbo = GL15.glGenBuffers();
         texMade = true;
      }
      GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo);
      GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, u.buf, GL15.GL_STREAM_DRAW);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
      GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
      for (int i = 0; i < u.n; i++) {
         int t = u.tiles[i];
         GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, (t % TILES) * TILE, (t / TILES) * TILE, TILE, TILE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (long)i * TILE * TILE * 4);
      }
      GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      tilesUploaded += u.n;
      GpuSections.markNow(Ground.section("ground.state"), true);
   }

   /** Render thread: the state texture on unit 57 (false before it exists; a program then reads pzgStateInfo.w = 0). */
   public static boolean bindTexture() {
      if (tex == 0) {
         return false;
      }
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      return true;
   }

   private static final java.util.HashMap<Integer, int[]> LOC = new java.util.HashMap<>();

   /** Render thread, {@code program} bound: its pzgStateInfo (texels a square, map texels, now code, on) and pzgStateTex unit. */
   public static void applyUniforms(int program) {
      int[] l = LOC.get(program);
      if (l == null) {
         l = new int[] {GL20.glGetUniformLocation(program, "pzgStateInfo"), GL20.glGetUniformLocation(program, "pzgStateTex")};
         LOC.put(program, l);
         if (l[1] >= 0) {
            GL20.glUniform1i(l[1], UNIT);
         }
      }
      if (l[0] >= 0) {
         GL20.glUniform4f(l[0], TPS, SIZE, nowCode, BIND_ON && texMade ? 1F : 0F);
      }
   }

   /** GLSL for programs outside the floor variant (bind with {@link #bindTexture}, {@link #applyUniforms}). */
   public static final String GLSL = String.join("\n",
      "#ifndef PZG_STATE",
      "#define PZG_STATE 1",
      "uniform sampler2D pzgStateTex;",
      "uniform vec4 pzgStateInfo;", // texels a square, map size in texels, now code (eighths of a day mod 256), on
      "vec4 pzgStateAt(vec2 world) {",
      "   if (pzgStateInfo.w < 0.5) return vec4(0.0);",
      "   return texture2D(pzgStateTex, world * (pzgStateInfo.x / pzgStateInfo.y));",
      "}",
      "#endif",
      "");

   /** GLSL shared by both (the floor variant declares its own uniforms): the age of a texel's A in game days. */
   public static final String GLSL_AGE = "float pzgStateAge(float a) { return mod(pzgStateInfo.z - floor(a * 255.0 + 0.5), 256.0) / 8.0; }\n";

   // ------------------------------------------------------------------------------------------------ persistence

   private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "pzopt-ground-io");
      t.setDaemon(true);
      t.setPriority(Thread.MIN_PRIORITY);
      return t;
   });
   private static final ConcurrentLinkedQueue<Object[]> LOADS = new ConcurrentLinkedQueue<>();
   private static final int MAGIC = 0x475A5352; // "GZSR"
   private static final int VERSION = 1;

   static File dir(String sid) {
      String safe = sid.replaceAll("[^A-Za-z0-9._/-]", "_").replace("..", "_");
      return new File(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt" + File.separator + "ground" + File.separator + safe.replace('/', File.separatorChar));
   }

   /** Game thread: every modified chunk to disk now (on the IO thread). */
   public static void flush() {
      if (px != null && Config.GROUND_TRACKS_PERSIST) {
         saveModified(true);
      }
   }

   private static void saveModified(boolean all) {
      if (px == null || saveId == null || !Config.GROUND_TRACKS_PERSIST) {
         return;
      }
      for (int s = 0; s < SLOTS * SLOTS; s++) {
         if (resident[s] != EMPTY && touched[s] && modified[s]) {
            save(s, (short)(resident[s] >> 16), (short)resident[s]);
         }
      }
   }

   private static void save(int s, int wx, int wy) {
      int sx = s % SLOTS, sy = s / SLOTS;
      byte[] b = new byte[CHUNK_TEX * CHUNK_TEX * 4];
      for (int ty = 0; ty < CHUNK_TEX; ty++) {
         System.arraycopy(px, ((sy * CHUNK_TEX + ty) * SIZE + sx * CHUNK_TEX) * 4, b, ty * CHUNK_TEX * 4, CHUNK_TEX * 4);
      }
      modified[s] = false;
      final File d = dir(saveId);
      final double hours = worldHours();
      IO.execute(() -> writeChunk(d, wx, wy, b, hours));
      chunksSaved++;
   }

   private static double worldHours() {
      try {
         return zombie.GameTime.getInstance().getWorldAgeHours();
      } catch (Throwable t) {
         return 0.0;
      }
   }

   private static void load(int wx, int wy) {
      final File d = dir(saveId);
      final String sid = saveId;
      IO.execute(() -> {
         byte[] b = readChunk(d, wx, wy);
         if (b != null) {
            LOADS.add(new Object[] {sid, wx, wy, b});
         }
      });
   }

   private static void applyLoads() {
      Object[] l;
      int n = 0;
      while (n < 8 && (l = LOADS.poll()) != null) {
         n++;
         if (!l[0].equals(saveId)) {
            continue;
         }
         int wx = (Integer)l[1], wy = (Integer)l[2];
         byte[] b = (byte[])l[3];
         int s = slotOf(wx, wy);
         if (resident[s] != pack(wx, wy)) {
            continue; // left the window meanwhile
         }
         int sx = s % SLOTS, sy = s / SLOTS;
         for (int ty = 0; ty < CHUNK_TEX; ty++) {
            int o = ((sy * CHUNK_TEX + ty) * SIZE + sx * CHUNK_TEX) * 4;
            for (int i = 0; i < CHUNK_TEX * 4; i += 4) {
               // merged with anything stamped since the chunk entered (max per channel, the newer time)
               for (int c = 0; c < 3; c++) {
                  if ((b[ty * CHUNK_TEX * 4 + i + c] & 255) > (px[o + i + c] & 255)) {
                     px[o + i + c] = b[ty * CHUNK_TEX * 4 + i + c];
                  }
               }
               if (px[o + i + 3] == 0) {
                  px[o + i + 3] = b[ty * CHUNK_TEX * 4 + i + 3];
               }
            }
         }
         touched[s] = true;
         changedPending[s] = true;
         markChunkDirty(sx, sy);
         chunksLoaded++;
      }
   }

   // region files: 16 x 16 chunks; header: magic, version, 256 x (offset int, length int, hours double); blobs deflated

   private static final int REGION = 16, HEADER = 8 + REGION * REGION * 16;

   private static File regionFile(File d, int wx, int wy) {
      return new File(d, "r_" + Math.floorDiv(wx, REGION) + "_" + Math.floorDiv(wy, REGION) + ".gsr");
   }

   /** IO thread. */
   static void writeChunk(File d, int wx, int wy, byte[] raw, double hours) {
      try {
         d.mkdirs();
         File f = regionFile(d, wx, wy);
         byte[][] blobs = new byte[REGION * REGION][];
         double[] at = new double[REGION * REGION];
         readRegion(f, blobs, at);
         int i = Math.floorMod(wy, REGION) * REGION + Math.floorMod(wx, REGION);
         boolean empty = true;
         for (int k = 0; k < raw.length && empty; k += 4) {
            empty = raw[k] == 0 && raw[k + 1] == 0 && raw[k + 2] == 0;
         }
         blobs[i] = empty ? null : deflate(raw);
         at[i] = hours;
         File tmp = new File(d, f.getName() + ".tmp");
         try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            int off = HEADER;
            for (int k = 0; k < REGION * REGION; k++) {
               int len = blobs[k] == null ? 0 : blobs[k].length;
               out.writeInt(len == 0 ? 0 : off);
               out.writeInt(len);
               out.writeDouble(at[k]);
               off += len;
            }
            for (int k = 0; k < REGION * REGION; k++) {
               if (blobs[k] != null) {
                  out.write(blobs[k]);
               }
            }
         }
         java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (Throwable t) {
         Log.warn("ground state: could not save chunk " + wx + "," + wy + ": " + t);
      }
   }

   /** IO thread: the chunk's texels, aged by the game time since they were written (older than 32 days: none). */
   static byte[] readChunk(File d, int wx, int wy) {
      File f = regionFile(d, wx, wy);
      if (!f.isFile()) {
         return null;
      }
      byte[][] blobs = new byte[REGION * REGION][];
      double[] at = new double[REGION * REGION];
      if (!readRegion(f, blobs, at)) {
         return null;
      }
      int i = Math.floorMod(wy, REGION) * REGION + Math.floorMod(wx, REGION);
      if (blobs[i] == null) {
         return null;
      }
      double days = (worldHours() - at[i]) / 24.0;
      if (days > 31.0 || days < -1.0) {
         return null; // (the A clock wraps at 32 days; a save older than that, or a world time that went back: gone)
      }
      try {
         byte[] raw = inflate(blobs[i], CHUNK_TEX * CHUNK_TEX * 4);
         return raw;
      } catch (Throwable t) {
         Log.warn("ground state: chunk " + wx + "," + wy + " unreadable: " + t);
         return null;
      }
   }

   /** The region files in {@code d} (tests, stats). */
   static int regionFiles(File d) {
      File[] fs = d.listFiles((x, n) -> n.endsWith(".gsr"));
      return fs == null ? 0 : fs.length;
   }

   private static boolean readRegion(File f, byte[][] blobs, double[] at) {
      if (!f.isFile()) {
         return false;
      }
      try {
         byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
         DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(all));
         if (in.readInt() != MAGIC || in.readInt() != VERSION) {
            return false;
         }
         for (int k = 0; k < REGION * REGION; k++) {
            int off = in.readInt(), len = in.readInt();
            at[k] = in.readDouble();
            if (len > 0 && off >= HEADER && off + len <= all.length) {
               blobs[k] = java.util.Arrays.copyOfRange(all, off, off + len);
            }
         }
         return true;
      } catch (Throwable t) {
         Log.warn("ground state: region " + f.getName() + " unreadable: " + t);
         return false;
      }
   }

   static byte[] deflate(byte[] raw) {
      java.util.zip.Deflater def = new java.util.zip.Deflater(6);
      def.setInput(raw);
      def.finish();
      ByteArrayOutputStream bo = new ByteArrayOutputStream(raw.length / 8 + 64);
      byte[] buf = new byte[8192];
      while (!def.finished()) {
         bo.write(buf, 0, def.deflate(buf));
      }
      def.end();
      return bo.toByteArray();
   }

   static byte[] inflate(byte[] z, int size) throws java.util.zip.DataFormatException {
      java.util.zip.Inflater inf = new java.util.zip.Inflater();
      inf.setInput(z);
      byte[] raw = new byte[size];
      int n = 0;
      while (n < size && !inf.finished()) {
         int k = inf.inflate(raw, n, size - n);
         if (k == 0 && (inf.needsInput() || inf.needsDictionary())) {
            break;
         }
         n += k;
      }
      inf.end();
      if (n != size) {
         throw new java.util.zip.DataFormatException("short chunk (" + n + " of " + size + ")");
      }
      return raw;
   }

   public static String stats() {
      return "ground state: " + (activeNow ? "on" : "off") + ", stamps " + stamps + ", texels " + texelsWritten + ", tiles up " + tilesUploaded + " (deferred "
         + uploadsDeferred + "), chunks saved " + chunksSaved + " loaded " + chunksLoaded + " evicted " + chunksEvicted + ", stamping "
         + String.format(java.util.Locale.ROOT, "%.1f", stampNs / 1e6) + " ms total";
   }
}
