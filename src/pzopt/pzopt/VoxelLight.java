package pzopt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import zombie.core.ShaderHelper;
import zombie.core.SpriteRenderer;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.IsoObject;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoLightSource;
import zombie.iso.IsoRoomLight;
import zombie.iso.IsoWorld;

/**
 * Voxel lighting (2026-10-18). The static lights of the world (lamp posts, placed lamps, room lights), the open sky and one
 * bounce of the traced light, computed through the god rays' voxel occupancy ({@link GodRays}: per square and level the
 * floor, the N / W edge as wall / window / doorway / open, roof tiles, room hashes, tree crown density) into a light volume
 * anchored in the world round the camera, and read per pixel by pixelLight's chunk composite in place of the native's
 * per-square light.
 * <ol>
 *   <li>Light volume A (RGBA16F, {@code voxelLightRes} texels a square across, {@code voxelLightLevelRes} a level up,
 *       toroidal in x / y like the occupancy): per texel the lamps that reach it, each through an exact 3D DDA of the
 *       occupancy toward the lamp's head (a wall stops it, a window's pane between sill and head lets {@code
 *       voxelLightGlassPct} through, a doorway up to its head, a floor or roof crossed stops it, crowns attenuate), with the
 *       native's falloff and colour; room lights fill their room's squares flat as the native does. Volume B (RGBA8): the
 *       light's dominant direction and how directional it is, for the facing term (a wall facing the lamp lights up).</li>
 *   <li>Bricks of 4 x 4 squares x 1 level: traced once and kept. A brick is traced again only when a light that reaches it
 *       changes (switched, colour, power) or the occupancy of a chunk in reach changes (a door opens, a wall is built),
 *       nearest the camera first within {@code voxelLightBricksPerFrame}; a brick the window scrolls onto is cleared
 *       (validity 0: the native's light) until traced. A still scene costs no compute at all.</li>
 *   <li>Flood volume C (RGBA16F, one cell a square and level): the open sky (a column with nothing above) and the traced
 *       light x albedo injected (S), then flooded cell to cell through the occupancy's openings, max of the neighbours x
 *       decay (sky {@code voxelLightSkyDecayPct}, bounce {@code voxelLightBounceDecayPct}): the ambient falls off into
 *       rooms from their windows and doorways, lit windows spill warm light out, lamp pools fill their surroundings.
 *       Iterated a few times a frame after a change, until settled.</li>
 *   <li>The chunk composite (PixelLight's programs, {@code PPL_VOXEL}): per pixel the point a quarter square along its
 *       normal, never filtered across a blocked edge of its square (the occupancy says where the walls are: no light
 *       leaks), A x facing + the ambient x the sky term + the bounce, blended with the native's light by A's validity.</li>
 * </ol>
 */
public final class VoxelLight {
   private VoxelLight() {
   }

   public static volatile boolean ACTIVE = Config.VOXEL_LIGHT && PixelLight.ACTIVE && Overrides.enabled() && !CoreGl.legacyMac();
   private static volatile boolean failed;
   private static volatile String why = "";

   static final int N = Config.VOXEL_LIGHT_SQUARES, R = Config.VOXEL_LIGHT_RES, RZ = Config.VOXEL_LIGHT_LEVEL_RES, LZ = 8;
   static final int BS = 4; // squares per brick side
   static final int NB = N / BS; // bricks per side
   static final int TX = N * R, TZ = LZ * RZ; // texels per side, up
   static final int A_UNIT = 44, B_UNIT = 45, C_UNIT = 46, LM_UNIT = 47, OCC_UNIT = 49, TS_UNIT = 50, O4_UNIT = 51, LMAP_UNIT = 52, PROBE_UNIT = 53;
   static final int MAX_LIGHTS = 4096;
   static final float ROOM_SHINE = 5.0F; // squares a room light reaches out through its windows and doorways (IsoRoomLight.shineOut)

   public static boolean wantsOccupancy() {
      return ACTIVE && !failed;
   }

   private static void fail(String w) {
      if (!failed) {
         failed = true;
         why = w;
         Log.warn("voxel light: " + w + "; the native's light for the rest of the session");
      }
   }

   // ------------------------------------------------------------------------------------------------ lights (game thread)

   /** This frame's static lights: x, y, head z (levels), reach; r, g, b, kind (0 point, 1 room); room rect x0, y0, x1, y1 + hash. */
   private static float[] lightData = new float[MAX_LIGHTS * 12];
   private static int lightCount;
   private static int lightSerial; // bumped whenever lightData changed (the render thread re-uploads)
   private static final java.util.HashMap<Integer, Long> sigById = new java.util.HashMap<>();
   private static final java.util.HashMap<Integer, float[]> boxById = new java.util.HashMap<>(); // the reach box each light last dirtied
   private static final java.util.HashSet<Integer> seenIds = new java.util.HashSet<>();
   /** pixelLight's per-pixel point lights skip these lamps (the volume has them): ids of the lights in the volume. */
   private static final java.util.HashSet<Integer> covered = new java.util.HashSet<>();

   /** Whether lamp l is in the light volume (pixelLight leaves it out of its per-pixel point lights). */
   public static boolean covers(IsoLightSource l) {
      return ACTIVE && !failed && Gl.ready && covered.contains(l.id);
   }

   // ------------------------------------------------------------------------------------------------ bricks (game thread)

   private static final long[] slotOwner = new long[NB * NB * LZ];
   private static final boolean[] slotDirty = new boolean[NB * NB * LZ];
   private static int dirtyCount;
   private static int winX0 = Integer.MIN_VALUE, winY0, zBase = Integer.MIN_VALUE;
   private static int[] spiral;
   private static final ArrayList<int[]> pendingChunks = new ArrayList<>(), nextChunks = new ArrayList<>();
   private static long frames, bricksTraced, bricksEmpty, bricksCleared, lightChanges, chunkChanges;
   /**
    * The flood's regions: where the traced light changed lately (traced or cleared bricks), each iterated (inject, flood,
    * resolve) over itself plus FLOOD_REACH squares until its iterations are spent; up to MAX_REGIONS, merged when they meet.
    */
   static final class Region {
      int x0, y0, x1, y1, life;
      boolean fresh;
   }

   static final int FLOOD_REACH = 16, MAX_REGIONS = 4, REGION_LIFE = 32;
   private static final ArrayList<Region> regions = new ArrayList<>();

   private static void touch(int x0, int y0, int x1, int y1) {
      for (Region r : regions) {
         if (x0 <= r.x1 + FLOOD_REACH && x1 >= r.x0 - FLOOD_REACH && y0 <= r.y1 + FLOOD_REACH && y1 >= r.y0 - FLOOD_REACH) {
            r.x0 = Math.min(r.x0, x0);
            r.y0 = Math.min(r.y0, y0);
            r.x1 = Math.max(r.x1, x1);
            r.y1 = Math.max(r.y1, y1);
            r.life = REGION_LIFE;
            r.fresh = true;
            return;
         }
      }
      Region r = new Region();
      r.x0 = x0;
      r.y0 = y0;
      r.x1 = x1;
      r.y1 = y1;
      r.life = REGION_LIFE;
      r.fresh = true;
      regions.add(r);
      while (regions.size() > MAX_REGIONS) { // the two whose union is smallest become one
         int bi = 0, bj = 1;
         long best = Long.MAX_VALUE;
         for (int i = 0; i < regions.size(); i++) {
            for (int j = i + 1; j < regions.size(); j++) {
               Region a = regions.get(i), b = regions.get(j);
               long area = (long)(Math.max(a.x1, b.x1) - Math.min(a.x0, b.x0)) * (Math.max(a.y1, b.y1) - Math.min(a.y0, b.y0));
               if (area < best) {
                  best = area;
                  bi = i;
                  bj = j;
               }
            }
         }
         Region a = regions.get(bi), b = regions.remove(bj);
         a.x0 = Math.min(a.x0, b.x0);
         a.y0 = Math.min(a.y0, b.y0);
         a.x1 = Math.max(a.x1, b.x1);
         a.y1 = Math.max(a.y1, b.y1);
         a.life = Math.max(a.life, b.life);
         a.fresh |= b.fresh;
      }
   }

   private static long brickKey(int bx, int by, int z) {
      return ((long)bx & 0xFFFFFL) << 40 | ((long)by & 0xFFFFFL) << 20 | (long)(z + 64) & 0xFFFFFL;
   }

   private static int slot(int bx, int by, int z) {
      return ((z - zBase) * NB + (by & NB - 1)) * NB + (bx & NB - 1);
   }

   private static final ArrayList<int[]> bakedChunks = new ArrayList<>();

   /** PixelLight.bakeBegin, game thread: a chunk's textures bake again (their surface cache layers are relit first). */
   public static void chunkBaked(zombie.iso.IsoChunk c) {
      if (ACTIVE && !failed && c != null && bakedChunks.size() < 4096) {
         bakedChunks.add(new int[] {c.wx, c.wy});
      }
   }

   /** GodRays, game thread: a chunk's occupancy was built again; different from what was there: its bricks next frame. */
   static void occChanged(int wx, int wy, int[] before, int[] after) {
      if (!ACTIVE || failed) {
         return;
      }
      if (before != null && java.util.Arrays.equals(before, after)) {
         return; // the 30 s refresh of the near chunks: nothing changed
      }
      nextChunks.add(new int[] {wx, wy});
   }

   private static void markBox(float x0, float y0, float x1, float y1, int z0, int z1) {
      int bx0 = (int)Math.floor(Math.max(x0, winX0) / BS), bx1 = (int)Math.floor(Math.min(x1, winX0 + N - 1) / BS);
      int by0 = (int)Math.floor(Math.max(y0, winY0) / BS), by1 = (int)Math.floor(Math.min(y1, winY0 + N - 1) / BS);
      z0 = Math.max(z0, zBase);
      z1 = Math.min(z1, zBase + LZ - 1);
      for (int z = z0; z <= z1; z++) {
         for (int by = by0; by <= by1; by++) {
            for (int bx = bx0; bx <= bx1; bx++) {
               int s = slot(bx, by, z);
               if (!slotDirty[s]) {
                  slotDirty[s] = true;
                  dirtyCount++;
               }
            }
         }
      }
   }

   private static int[] spiral() {
      if (spiral == null) {
         ArrayList<int[]> l = new ArrayList<>();
         for (int dy = -NB / 2 - 3; dy < NB / 2 + 3; dy++) { // the window is chunk-aligned: up to two bricks off the camera's centre
            for (int dx = -NB / 2 - 3; dx < NB / 2 + 3; dx++) {
               l.add(new int[] {dx, dy});
            }
         }
         l.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
         spiral = new int[l.size() * 2];
         for (int i = 0; i < l.size(); i++) {
            spiral[i * 2] = l.get(i)[0];
            spiral[i * 2 + 1] = l.get(i)[1];
         }
      }
      return spiral;
   }

   // per chunk-sized bucket of the window: the lights whose reach box touches it (rebuilt when the lights change)
   private static final int CB = N / 8;
   private static int[][] bucket = new int[CB * CB][];
   private static final int[] bucketN = new int[CB * CB];
   private static boolean bucketsDirty = true;

   private static void buildBuckets() {
      java.util.Arrays.fill(bucketN, 0);
      for (int i = 0; i < lightCount; i++) {
         int k = i * 12;
         float r = lightData[k + 3] + 1.0F;
         float x0 = lightData[k], y0 = lightData[k + 1], x1 = x0, y1 = y0;
         if (lightData[k + 7] == 1.0F) {
            x0 = lightData[k + 8];
            y0 = lightData[k + 9];
            x1 = lightData[k + 10];
            y1 = lightData[k + 11];
            r = ROOM_SHINE + 1.0F;
         }
         int cx0 = (int)Math.floor((Math.max(x0 - r, winX0) - winX0) / 8.0F), cx1 = (int)Math.floor((Math.min(x1 + r, winX0 + N - 1) - winX0) / 8.0F);
         int cy0 = (int)Math.floor((Math.max(y0 - r, winY0) - winY0) / 8.0F), cy1 = (int)Math.floor((Math.min(y1 + r, winY0 + N - 1) - winY0) / 8.0F);
         for (int cy = cy0; cy <= cy1; cy++) {
            for (int cx = cx0; cx <= cx1; cx++) {
               int b = cy * CB + cx;
               if (bucket[b] == null) {
                  bucket[b] = new int[16];
               } else if (bucketN[b] == bucket[b].length) {
                  bucket[b] = java.util.Arrays.copyOf(bucket[b], bucket[b].length * 2);
               }
               bucket[b][bucketN[b]++] = i;
            }
         }
      }
      bucketsDirty = false;
   }

   /** The game's static lights now; every light that changed (or came, or went) dirties the bricks in its reach. */
   private static void gatherLights(IsoCell cell) {
      seenIds.clear();
      int n = 0;
      boolean changed = false;
      java.util.Stack<IsoLightSource> lamps = cell.getLamppostPositions();
      covered.clear();
      float s = Config.VOXEL_LIGHT_STRENGTH_PCT / 100.0F;
      for (int i = 0; i < lamps.size() && n < MAX_LIGHTS; i++) {
         IsoLightSource l = lamps.get(i);
         if (l == null || l.id == 0 || l.life != -1 || l.radius <= 0) {
            continue;
         }
         boolean on = l.activeJni;
         float r = Math.min(l.radius, 20);
         if (l.x + r < winX0 || l.x - r > winX0 + N || l.y + r < winY0 || l.y - r > winY0 + N || l.z < zBase - 1 || l.z > zBase + LZ) {
            continue;
         }
         float cr = Math.min(1.0F, Math.max(0.0F, l.r * 2.0F)), cg = Math.min(1.0F, Math.max(0.0F, l.g * 2.0F)), cb = Math.min(1.0F, Math.max(0.0F, l.b * 2.0F));
         IsoGridSquare sq = cell.getGridSquare(l.x, l.y, l.z);
         float head = sq != null && sq.getRoom() == null ? 0.8F : 0.6F; // a lamp post's head; a lamp indoors on a table
         long sig = on ? (Float.floatToIntBits(cr) * 31L + Float.floatToIntBits(cg)) * 31L + Float.floatToIntBits(cb) + (long)l.radius * 1_000_003L
            + ((long)l.x << 40) + ((long)l.y << 20) + l.z + (sq != null && sq.getRoom() == null ? 7L : 0L) : 0L;
         boolean sigChanged = track(l.id, sig, l.x - r, l.y - r, l.x + 1 + r, l.y + 1 + r, l.z - 1, l.z + 1);
         changed |= sigChanged;
         if (!on) {
            continue;
         }
         covered.add(l.id);
         int k = n * 12;
         lightIds[n] = l.id;
         lightData[k] = l.x + 0.5F;
         lightData[k + 1] = l.y + 0.5F;
         lightData[k + 2] = l.z + head;
         lightData[k + 3] = r;
         lightData[k + 4] = cr * s;
         lightData[k + 5] = cg * s;
         lightData[k + 6] = cb * s;
         lightData[k + 7] = 0.0F;
         lightData[k + 8] = lightData[k + 9] = lightData[k + 10] = lightData[k + 11] = 0.0F;
         if (MAPS) {
            int[] m = mapFor(l.id);
            if (m != null) {
               if (sigChanged) {
                  m[1] = 0;
               }
               lightData[k + 8] = m[0];
               lightData[k + 9] = m[1];
            }
         }
         n++;
      }
      ArrayList<IsoRoomLight> rooms = cell.roomLights;
      for (int i = 0; i < rooms.size() && n < MAX_LIGHTS; i++) {
         IsoRoomLight l = rooms.get(i);
         if (l == null || l.id == 0 || l.room == null) {
            continue;
         }
         if (l.x + l.width < winX0 || l.x > winX0 + N || l.y + l.height < winY0 || l.y > winY0 + N || l.z < zBase || l.z >= zBase + LZ) {
            continue;
         }
         boolean on = l.activeJni;
         long sig = on ? ((long)l.x << 40) + ((long)l.y << 20) + l.z * 7L + l.width * 1_000_003L + l.height * 999_983L + 1L : 0L;
         changed |= track(-l.id, sig, l.x - 1 - ROOM_SHINE, l.y - 1 - ROOM_SHINE, l.x + l.width + 1 + ROOM_SHINE, l.y + l.height + 1 + ROOM_SHINE, l.z, l.z);
         if (!on) {
            continue;
         }
         int k = n * 12;
         lightData[k] = l.x + l.width * 0.5F;
         lightData[k + 1] = l.y + l.height * 0.5F;
         lightData[k + 2] = l.z + 0.9F;
         lightData[k + 3] = 0.0F;
         lightData[k + 4] = 0.9F * s; // IsoRoomLight.addInfluence's colour
         lightData[k + 5] = 0.8F * s;
         lightData[k + 6] = 0.7F * s;
         lightData[k + 7] = 1.0F;
         lightData[k + 8] = l.x;
         lightData[k + 9] = l.y;
         lightData[k + 10] = l.x + l.width;
         lightData[k + 11] = l.y + l.height;
         roomHashes[n] = GodRays.roomHash(l.room, l.z); // goes in the reach's place for the render thread (a room light has no DDA)
         n++;
      }
      // lights that went (unloaded, out of the window): their reach once more
      if (sigById.size() > seenIds.size()) {
         java.util.Iterator<java.util.Map.Entry<Integer, Long>> it = sigById.entrySet().iterator();
         while (it.hasNext()) {
            java.util.Map.Entry<Integer, Long> e = it.next();
            if (!seenIds.contains(e.getKey())) {
               float[] b = boxById.remove(e.getKey());
               int[] gone = mapOf.remove(e.getKey());
               if (gone != null) {
                  freeMapLayers.add(gone[0]);
               }
               if (b != null && e.getValue() != 0L) {
                  markBox(b[0], b[1], b[2], b[3], (int)b[4], (int)b[5]);
                  changed = true;
               }
               it.remove();
            }
         }
      }
      if (changed || n != lightCount) {
         lightCount = n;
         lightSerial++;
         bucketsDirty = true;
      }
   }

   private static final int[] roomHashes = new int[MAX_LIGHTS];

   // voxelLightDirect=maps: a visibility map layer per lamp (by id), traced again when the lamp or the occupancy in its reach changed
   static final boolean MAPS = "maps".equals(Config.VOXEL_LIGHT_DIRECT);
   static final int LM_SIZE = 64, LM_LAYERS = 1024;
   private static final java.util.HashMap<Integer, int[]> mapOf = new java.util.HashMap<>(); // id -> {layer, traced (1)}
   private static final java.util.ArrayDeque<Integer> freeMapLayers = new java.util.ArrayDeque<>();
   private static final java.util.HashSet<Integer> mapDirty = new java.util.HashSet<>();
   private static final int[] lightIds = new int[MAX_LIGHTS];
   static long mapsTraced;

   private static boolean mapsInvalidated;

   private static int[] mapFor(int id) {
      int[] m = mapOf.get(id);
      if (m == null) {
         if (freeMapLayers.isEmpty() && mapOf.size() < LM_LAYERS) {
            freeMapLayers.add(mapOf.size());
         }
         Integer layer = freeMapLayers.poll();
         if (layer == null) {
            return null; // more lamps than layers: the rest trace per texel
         }
         m = new int[] {layer, 0};
         mapOf.put(id, m);
      }
      return m;
   }

   /** Records light id's signature; a new one or a change dirties its reach (old and new). */
   private static boolean track(int id, long sig, float x0, float y0, float x1, float y1, int z0, int z1) {
      seenIds.add(id);
      Long was = sigById.put(id, sig);
      if (was != null && was == sig) {
         return false;
      }
      float[] old = boxById.get(id);
      if (old != null) {
         markBox(old[0], old[1], old[2], old[3], (int)old[4], (int)old[5]);
      }
      if (was == null && sig == 0L) {
         boxById.put(id, new float[] {x0, y0, x1, y1, z0, z1});
         return false; // a light seen off for the first time: nothing to undo
      }
      markBox(x0, y0, x1, y1, z0, z1);
      boxById.put(id, new float[] {x0, y0, x1, y1, z0, z1});
      lightChanges++;
      return true;
   }

   // ------------------------------------------------------------------------------------------------ the frame (game thread)

   static final int MAX_JOBS = 8192;

   static final class Frame extends TextureDraw.GenericDrawer {
      boolean on, free = true;
      int winX0, winY0, zBase, occZ0, maxTop;
      int lightSerial, lights;
      float[] lightData = new float[MAX_LIGHTS * 12];
      int[] jobs = new int[MAX_JOBS * 4];
      int njobs;
      int[] idx = new int[1 << 16];
      int nidx;
      int prop; // flood iterations this frame (on every region)
      boolean clearAll;
      final int[] maps = new int[256]; // the light visibility maps to trace: light index, layer
      int nmaps;
      final int[] reg = new int[MAX_REGIONS * 5]; // x0, y0, w, h (world squares), fresh (inject)
      int nreg;
      final int[] settled = new int[MAX_REGIONS * 4]; // regions settled this frame: x0, y0, x1, y1
      int nsettled;
      int[] baked = new int[256];
      int nbaked;
      float ambR, ambG, ambB;
      boolean devOff;

      @Override
      public void render() {
         try {
            Gl.render(this);
         } catch (Throwable t) {
            fail("render: " + t);
         }
      }

      @Override
      public void postRender() {
         this.free = true;
      }
   }

   private static final Frame[] FRAMES = {new Frame(), new Frame(), new Frame(), new Frame()};
   private static int frameIndex;
   private static long toggleT0;
   static volatile boolean devOff;

   private static Frame freeFrame() {
      for (int i = 0; i < FRAMES.length; i++) {
         Frame f = FRAMES[frameIndex++ & 3];
         if (f.free) {
            return f;
         }
      }
      return null;
   }

   /**
    * Game thread, PixelLight.beforeComposite (before pixelLight's own frame): the lights, the window, the bricks to trace,
    * queued for the render thread ahead of the chunk composite.
    */
   public static void gameFrame(int playerIndex) {
      if (!ACTIVE || failed || IsoWorld.instance == null || IsoWorld.instance.currentCell == null) {
         return;
      }
      long t0 = System.nanoTime();
      try {
         gameFrameInner(playerIndex);
      } catch (Throwable t) {
         fail("game frame: " + t);
      }
      gameNs += System.nanoTime() - t0;
   }

   private static long gameNs;
   private static boolean occSeen;

   private static void gameFrameInner(int playerIndex) {
      IsoCell cell = IsoWorld.instance.currentCell;
      Frame f = freeFrame();
      if (f == null) {
         return; // the render thread is behind: next frame
      }
      frames++;
      if (Config.DEV_VOXEL_LIGHT_ALTERNATE > 0) {
         long now = System.currentTimeMillis();
         if (toggleT0 == 0L) {
            toggleT0 = now;
            Log.info("voxel light: alternating every " + Config.DEV_VOXEL_LIGHT_ALTERNATE + " ms from epoch_ms " + now + " (on first)");
         }
         devOff = (now - toggleT0) / Config.DEV_VOXEL_LIGHT_ALTERNATE % 2L == 1L;
      }
      f.devOff = devOff;
      float cx = IsoCamera.frameState.camCharacterX, cy = IsoCamera.frameState.camCharacterY;
      int camLevel = (int)Math.floor(IsoCamera.frameState.camCharacterZ);
      int wantX0 = Math.floorDiv((int)Math.floor(cx) - N / 2, 8) * 8, wantY0 = Math.floorDiv((int)Math.floor(cy) - N / 2, 8) * 8;
      int occ0 = GodRays.occZ0();
      int wantZ = Math.max(occ0, Math.min(camLevel - 1, occ0 + 16 - LZ)); // the camera's level - 1 .. + 6 (basements to the roofs round it), within the occupancy
      boolean clearAll = false;
      if (wantZ != zBase) {
         zBase = wantZ;
         java.util.Arrays.fill(slotOwner, Long.MIN_VALUE);
         clearAll = true;
      }
      boolean moved = wantX0 != winX0 || wantY0 != winY0 || clearAll;
      winX0 = wantX0;
      winY0 = wantY0;
      f.njobs = 0;
      f.nidx = 0;
      if (moved) {
         // the slots the window now holds for other bricks: cleared this frame (no budget), traced later
         int bx0 = winX0 / BS, by0 = winY0 / BS;
         for (int z = zBase; z < zBase + LZ; z++) {
            for (int by = by0; by < by0 + NB; by++) {
               for (int bx = bx0; bx < bx0 + NB; bx++) {
                  int s = slot(bx, by, z);
                  long k = brickKey(bx, by, z);
                  if (slotOwner[s] != k) {
                     slotOwner[s] = k;
                     if (!slotDirty[s]) {
                        slotDirty[s] = true;
                        dirtyCount++;
                     }
                     if (!clearAll && f.njobs < MAX_JOBS) {
                        addJob(f, bx, by, z, -1, 0);
                        touch(bx * BS, by * BS, bx * BS + BS, by * BS + BS);
                        bricksCleared++;
                     }
                  }
               }
            }
         }
         bucketsDirty = true;
      }
      f.clearAll = clearAll;
      f.nbaked = 0;
      for (int[] c : bakedChunks) {
         if (f.nbaked * 2 + 1 >= f.baked.length) {
            f.baked = java.util.Arrays.copyOf(f.baked, f.baked.length * 2);
         }
         f.baked[f.nbaked * 2] = c[0];
         f.baked[f.nbaked * 2 + 1] = c[1];
         f.nbaked++;
      }
      bakedChunks.clear();
      if (moved || frames % Config.VOXEL_LIGHT_GATHER_FRAMES == 0) {
         gatherLights(cell);
      }
      // the chunks whose occupancy changed last frame (uploaded since): the bricks of their squares and of the lights in reach
      for (int[] c : pendingChunks) {
         float x0 = c[0] * 8, y0 = c[1] * 8, x1 = x0 + 8, y1 = y0 + 8;
         markBox(x0 - 1, y0 - 1, x1 + 1, y1 + 1, zBase, zBase + LZ - 1);
         for (int i = 0; i < lightCount; i++) {
            int k = i * 12;
            if (lightData[k + 7] != 0.0F) {
               continue;
            }
            float lx = lightData[k], ly = lightData[k + 1], r = lightData[k + 3] + 1.0F;
            if (lx + r >= x0 && lx - r <= x1 && ly + r >= y0 && ly - r <= y1) {
               int lz = (int)Math.floor(lightData[k + 2]);
               markBox(lx - r, ly - r, lx + r, ly + r, lz - 1, lz + 1);
               if (MAPS) {
                  int[] m = mapOf.get(lightIds[i]);
                  if (m != null && m[1] != 0) {
                     m[1] = 0;
                     lightData[k + 9] = 0F;
                     mapsInvalidated = true;
                  }
               }
            }
         }
         chunkChanges++;
      }
      pendingChunks.clear();
      pendingChunks.addAll(nextChunks);
      nextChunks.clear();
      if (bucketsDirty) {
         buildBuckets();
      }
      // the maps to trace this frame (before the bricks: the bricks traced this frame read them)
      f.nmaps = 0;
      if (MAPS && occSeen) {
         for (int i = 0; i < lightCount && f.nmaps < 128; i++) {
            int k = i * 12;
            if (lightData[k + 7] != 0F) {
               continue;
            }
            int[] m = mapOf.get(lightIds[i]);
            if (m != null && m[1] == 0) {
               f.maps[f.nmaps * 2] = i;
               f.maps[f.nmaps * 2 + 1] = m[0];
               f.nmaps++;
               m[1] = 1;
               lightData[k + 9] = 1F;
               mapsInvalidated = true;
               mapsTraced++;
            }
         }
      }
      if (mapsInvalidated) {
         mapsInvalidated = false;
         lightSerial++; // the lights' map flags to the render thread
      }
      if (Config.DEV_VOXEL_LIGHT_FULL) {
         for (int s = 0; s < slotDirty.length; s++) {
            if (!slotDirty[s] && slotOwner[s] != Long.MIN_VALUE) {
               slotDirty[s] = true;
               dirtyCount++;
            }
         }
      }
      // the dirty bricks, nearest first, within the budget (a brick no light reaches is cheap: 1/16 of the budget)
      int traced = 0;
      if (!occSeen && GodRays.occScanComplete()) {
         occSeen = true;
         Log.info("voxel light: occupancy complete after " + frames + " frames, tracing");
      }
      if (dirtyCount > 0 && !f.devOff && occSeen) { // nothing before the occupancy is there (lights through every wall, traced again)
         int[] sp = spiral();
         int cbx = Math.floorDiv((int)Math.floor(cx), BS), cby = Math.floorDiv((int)Math.floor(cy), BS);
         int budget = Config.DEV_VOXEL_LIGHT_FULL ? Integer.MAX_VALUE / 2 : Config.VOXEL_LIGHT_BRICKS * 16;
         int bx0 = winX0 / BS, by0 = winY0 / BS;
         outer:
         for (int dz = 0; dz < LZ * 2; dz++) {
            int z = camLevel + (dz % 2 == 0 ? dz / 2 : -(dz / 2 + 1));
            if (z < zBase || z >= zBase + LZ) {
               continue;
            }
            for (int i = 0; i < sp.length; i += 2) {
               int bx = cbx + sp[i], by = cby + sp[i + 1];
               if (bx < bx0 || bx >= bx0 + NB || by < by0 || by >= by0 + NB) {
                  continue;
               }
               int s = slot(bx, by, z);
               if (!slotDirty[s]) {
                  continue;
               }
               if (f.njobs >= MAX_JOBS || budget <= 0) {
                  break outer;
               }
               int first = f.nidx;
               int cnt = brickLights(f, bx, by, z);
               addJob(f, bx, by, z, first, cnt);
               touch(bx * BS, by * BS, bx * BS + BS, by * BS + BS);
               slotDirty[s] = false;
               dirtyCount--;
               budget -= cnt == 0 ? 1 : 16;
               traced++;
               if (cnt == 0) {
                  bricksEmpty++;
               }
            }
         }
      }
      if (traced > 0) {
         bricksTraced += traced;
      }
      if (clearAll) {
         regions.clear();
         touch(winX0, winY0, winX0 + N, winY0 + N);
      }
      // the regions: inject where fresh, a few flood iterations (pairs: the composite reads one texture), then the resolve;
      // a spent region is settled (the cache's layers over it are relit)
      int it = Config.VOXEL_LIGHT_SKY || Config.VOXEL_LIGHT_BOUNCE ? Config.VOXEL_LIGHT_PROP_ITERS + (Config.VOXEL_LIGHT_PROP_ITERS & 1) : 0;
      f.nreg = 0;
      f.nsettled = 0;
      if (!f.devOff) {
         for (int i = 0; i < regions.size(); i++) {
            Region r = regions.get(i);
            int x0 = Math.max(winX0, r.x0 - FLOOD_REACH), y0 = Math.max(winY0, r.y0 - FLOOD_REACH);
            int x1 = Math.min(winX0 + N, r.x1 + FLOOD_REACH), y1 = Math.min(winY0 + N, r.y1 + FLOOD_REACH);
            if (x1 <= x0 || y1 <= y0) {
               regions.remove(i--);
               continue;
            }
            int k = f.nreg++ * 5;
            f.reg[k] = x0;
            f.reg[k + 1] = y0;
            f.reg[k + 2] = x1 - x0;
            f.reg[k + 3] = y1 - y0;
            f.reg[k + 4] = r.fresh ? 1 : 0;
            r.fresh = false;
            r.life -= Math.max(it, 2);
            if (r.life <= 0) {
               int q = f.nsettled++ * 4;
               f.settled[q] = x0;
               f.settled[q + 1] = y0;
               f.settled[q + 2] = x1;
               f.settled[q + 3] = y1;
               regions.remove(i--);
            }
         }
      }
      f.prop = it;
      // the frame's state
      f.on = true;
      f.winX0 = winX0;
      f.winY0 = winY0;
      f.zBase = zBase;
      f.occZ0 = GodRays.occZ0();
      f.maxTop = GodRays.maxTopLevel + 1;
      if (f.lightSerial != lightSerial || f.lights != lightCount) {
         f.lightSerial = lightSerial;
         f.lights = lightCount;
         System.arraycopy(lightData, 0, f.lightData, 0, lightCount * 12);
         for (int i = 0; i < lightCount; i++) {
            if (f.lightData[i * 12 + 7] == 1.0F) {
               f.lightData[i * 12 + 3] = Float.intBitsToFloat(roomHashes[i]); // the room's hash in the reach's place (a room light has no DDA)
            }
         }
      }
      int amb = PixelLight.ambientOutdoor();
      f.ambR = amb < 0 ? -1.0F : (amb & 0xFF) / 255.0F;
      f.ambG = amb < 0 ? -1.0F : (amb >> 8 & 0xFF) / 255.0F;
      f.ambB = amb < 0 ? -1.0F : (amb >> 16 & 0xFF) / 255.0F;
      f.free = false;
      SpriteRenderer.instance.drawGeneric(f);
      if (Config.DEV_VOXEL_LIGHT_TIMING || frames % 3000 == 1 || !Config.DEV_VOXEL_LIGHT_PROBE.isEmpty() && frames % 600 == 1) {
         logStats();
      }
   }

   private static long lastStatsMs, statsNs, statsFrames;

   private static void logStats() {
      long now = System.currentTimeMillis();
      if (now - lastStatsMs < 5000L) {
         return;
      }
      lastStatsMs = now;
      Log.info(String.format(java.util.Locale.ROOT,
         "voxel light: frames=%d lights=%d bricks traced=%d (empty %d) cleared=%d dirty now=%d light changes=%d chunk changes=%d game %.1f us/frame (last %d frames) win=%d,%d z=%d",
         frames, lightCount, bricksTraced, bricksEmpty, bricksCleared, dirtyCount, lightChanges, chunkChanges, (gameNs - statsNs) / 1000.0 / Math.max(1L, frames - statsFrames), frames - statsFrames, winX0, winY0, zBase)
         + " maps traced=" + mapsTraced + " cache: layers=" + Gl.SLOTS.size() + " relights=" + Gl.relights + " (new " + Gl.relightsNew + ") evictions=" + Gl.slotEvictions + " remaps=" + Gl.remaps + " volVersion=" + Gl.volVersion);
      statsNs = gameNs;
      statsFrames = frames;
      if (!Config.DEV_VOXEL_LIGHT_PROBE.isEmpty()) {
         for (String box : Config.DEV_VOXEL_LIGHT_PROBE.split(";")) {
            probe(box);
         }
      }
   }

   /** devVoxelLightProbe: the lights reaching a box of squares and what the occupancy says of each square in it. */
   private static void probe(String box) {
      String[] a = box.split(",");
      int x0 = Integer.parseInt(a[0].trim()), y0 = Integer.parseInt(a[1].trim()), x1 = Integer.parseInt(a[2].trim()), y1 = Integer.parseInt(a[3].trim()), z = Integer.parseInt(a[4].trim());
      IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
      if (cell == null) {
         return;
      }
      StringBuilder b = new StringBuilder("voxel light probe ").append(box).append(':');
      for (IsoLightSource l : cell.getLamppostPositions()) {
         if (l != null && l.x + l.radius >= x0 && l.x - l.radius <= x1 && l.y + l.radius >= y0 && l.y - l.radius <= y1 && Math.abs(l.z - z) <= 1) {
            b.append(String.format(java.util.Locale.ROOT, "%n  lamp id=%d at %d,%d,%d r=%d rgb=%.2f,%.2f,%.2f on=%b life=%d sqRoom=%s", l.id, l.x, l.y, l.z, l.radius, l.r, l.g, l.b, l.activeJni, l.life,
               cell.getGridSquare(l.x, l.y, l.z) == null ? "?" : String.valueOf(cell.getGridSquare(l.x, l.y, l.z).getRoom() == null ? null : cell.getGridSquare(l.x, l.y, l.z).getRoom().getName())));
         }
      }
      for (IsoRoomLight l : cell.roomLights) {
         if (l != null && l.x + l.width >= x0 && l.x <= x1 && l.y + l.height >= y0 && l.y <= y1 && l.z == z) {
            b.append(String.format(java.util.Locale.ROOT, "%n  room light id=%d rect %d,%d %dx%d z=%d room=%s hash=%d on=%b", l.id, l.x, l.y, l.width, l.height, l.z,
               l.room == null ? null : l.room.getName(), l.room == null ? 0 : GodRays.roomHash(l.room, l.z), l.activeJni));
         }
      }
      for (int y = y0; y <= y1; y++) {
         b.append(String.format(java.util.Locale.ROOT, "%n  y=%d:", y));
         for (int x = x0; x <= x1; x++) {
            IsoGridSquare sq = cell.getGridSquare(x, y, z);
            int o = GodRays.occCpu(x, y, z);
            zombie.core.textures.ColorInfo li = sq == null ? null : sq.getLightInfo(0);
            if (sq != null && ((o >> 1 & 3) == 1 || (o >> 3 & 3) == 1)) {
               IsoObject wn = sq.getWall(true), ww = sq.getWall(false);
               b.append(" {").append(wn != null && wn.getSprite() != null ? wn.getSprite().getName() : "-").append('/').append(ww != null && ww.getSprite() != null ? ww.getSprite().getName() : "-");
               if (wn == null && ww == null) {
                  for (int k = 0; k < sq.getObjects().size(); k++) {
                     IsoObject ob = sq.getObjects().get(k);
                     b.append(k == 0 ? " objects:" : ",").append(ob != null && ob.getSprite() != null ? ob.getSprite().getName() : "?");
                  }
               }
               b.append('}');
            }
            b.append(sq != null && sq.isSeen(0) ? " s" : " u");
            b.append(String.format(java.util.Locale.ROOT, " [%d %s occ=%x h=%d out=%s nat=%.2f,%.2f,%.2f]", x, sq == null ? "null" : sq.getRoom() == null ? "-" : sq.getRoom().getName(), o & 0xFFFF, o >>> 16,
               sq == null ? "?" : sq.has(zombie.iso.SpriteDetails.IsoFlagType.exterior) ? "y" : "n", li == null ? -1F : li.r, li == null ? -1F : li.g, li == null ? -1F : li.b));
         }
      }
      Log.info(b.toString());
   }

   private static void addJob(Frame f, int bx, int by, int z, int first, int cnt) {
      int k = f.njobs * 4;
      f.jobs[k] = bx * BS * R;
      f.jobs[k + 1] = by * BS * R;
      f.jobs[k + 2] = z;
      f.jobs[k + 3] = first < 0 ? -1 : first | cnt << 20;
      f.njobs++;
   }

   /** The lights that reach brick (bx, by, z) into the frame's index list; their count. */
   private static int brickLights(Frame f, int bx, int by, int z) {
      float x0 = bx * BS, y0 = by * BS, x1 = x0 + BS, y1 = y0 + BS;
      int cbx = Math.floorDiv(bx * BS - winX0, 8), cby = Math.floorDiv(by * BS - winY0, 8);
      if (cbx < 0 || cby < 0 || cbx >= CB || cby >= CB) {
         return 0;
      }
      int b = cby * CB + cbx;
      int[] list = bucket[b];
      int cnt = 0;
      for (int j = 0; j < bucketN[b]; j++) {
         int i = list[j];
         int k = i * 12;
         if (lightData[k + 7] == 1.0F) {
            if ((int)Math.floor(lightData[k + 2]) != z || lightData[k + 10] + ROOM_SHINE <= x0 || lightData[k + 8] - ROOM_SHINE >= x1 || lightData[k + 11] + ROOM_SHINE <= y0
               || lightData[k + 9] - ROOM_SHINE >= y1) {
               continue;
            }
         } else {
            int lz = (int)Math.floor(lightData[k + 2]);
            if (Math.abs(lz - z) > 1) {
               continue;
            }
            float lx = lightData[k], ly = lightData[k + 1], r = lightData[k + 3];
            float dx = Math.max(0.0F, Math.max(x0 - lx, lx - x1)), dy = Math.max(0.0F, Math.max(y0 - ly, ly - y1));
            if (dx * dx + dy * dy >= r * r) {
               continue;
            }
         }
         if (f.nidx >= f.idx.length) {
            f.idx = java.util.Arrays.copyOf(f.idx, f.idx.length * 2);
         }
         f.idx[f.nidx++] = i;
         cnt++;
         if (cnt >= 1023) {
            break;
         }
      }
      return cnt;
   }

   // ------------------------------------------------------------------------------------------------ render thread

   static final class Gl {
      static volatile boolean ready;
      static int texA, texB, texS, texC0, texC1, texT1, texT2, texE, progDirect, progInject, progFlood, progResolve;
      static int bufJobs, bufIdx, bufLights;
      static int uploadedSerial = -1;
      static int winX0, winY0, zBase, occZ0;
      static boolean on;
      static float ambR = -1F, ambG, ambB;
      private static IntBuffer ib;
      private static FloatBuffer fb;
      private static final int[] U_D = new int[9], U_I = new int[9], U_F = new int[9], U_R = new int[9];
      static long dispatches, floodIters, relights, relightsNew, slotEvictions, remaps;

      static int texLM, progRelight, texTS, progTorch, texO4, progCoarse, texLmap, progLmap, bufMapJobs, progCascade, texProbe, progCube;
      static final int[] texD = new int[4]; // the directional bounce: D0, D1 (current), D0, D1 (the other half of the ping-pong)
      static final boolean CUBE = "cube".equals(Config.VOXEL_LIGHT_BOUNCE_METHOD);
      private static int coarseSerial = -1;

      /** The coarse occupancy again when the occupancy changed (render thread). */
      private static void coarse() {
         if (coarseSerial == occSerial || GodRays.Gl.occTex == 0) {
            return;
         }
         coarseSerial = occSerial;
         Timing.begin("coarse");
         GL20.glUseProgram(progCoarse);
         GL20.glUniform1i(GL20.glGetUniformLocation(progCoarse, "occ"), OCC_UNIT);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + OCC_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, GodRays.Gl.occTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL42.glBindImageTexture(0, texO4, 0, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_R8UI);
         GL43.glDispatchCompute(GodRays.OCC_N / 4 / 8, GodRays.OCC_N / 4 / 8, GodRays.OCC_L);
         GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
         GL42.glBindImageTexture(0, 0, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_R8UI);
         Timing.end("coarse");
      }

      /** The coarse occupancy on its unit and the skip switch for a tracing program (bound). */
      private static void skipUniforms(int prog) {
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + O4_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, texO4);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL20.glUniform1i(GL20.glGetUniformLocation(prog, "occ4"), O4_UNIT);
         GL20.glUniform1i(GL20.glGetUniformLocation(prog, "uSkip"), Config.VOXEL_LIGHT_SKIP ? 1 : 0);
      }
      private static int[] uTorch;
      static final int TS_SQ = 32, TS_TEX = TS_SQ * 4; // the torch map: squares across, texels across
      private static final float[] tsO = new float[8]; // per slot: map origin x, y (world squares), level, valid
      private static final int[] tsLight = {-1, -1};
      private static final float[] tsKey = new float[8]; // per slot: the torch traced last (x, y, head, reach)
      private static int tsOccSerial = -1;
      static long torchTraces;

      /**
       * PixelLight's render (render thread, before the composite, its lights known): the visibility map round each carried
       * torch (up to two), traced again when the torch moved or the occupancy changed.
       */
      static void torchShadows(float[] la, float[] lc, float[] lh, int n, int ox, int oy) {
         tsLight[0] = tsLight[1] = -1;
         if (!Config.VOXEL_LIGHT_TORCH_SHADOWS || !ready || !on || failed || progTorch == 0 || GodRays.Gl.occTex == 0) {
            return;
         }
         int slot = 0;
         boolean bound = false;
         for (int i = 0; i < n && slot < 2; i++) {
            if (lc[i * 4 + 3] != 1.0F) {
               continue;
            }
            float lx = la[i * 4] + ox, ly = la[i * 4 + 1] + oy, lzv = la[i * 4 + 2], head = lzv + lh[i], reach = la[i * 4 + 3];
            int level = (int)Math.floor(lzv + 0.05F);
            float x0 = (float)Math.floor(lx) - TS_SQ / 2, y0 = (float)Math.floor(ly) - TS_SQ / 2;
            tsLight[slot] = i;
            int k = slot * 4;
            boolean same = tsO[k + 3] > 0.5F && tsKey[k] == lx && tsKey[k + 1] == ly && tsKey[k + 2] == head && tsKey[k + 3] == reach && tsOccSerial == occSerial;
            tsO[k] = x0;
            tsO[k + 1] = y0;
            tsO[k + 2] = level;
            tsO[k + 3] = 1F;
            if (!same) {
               tsKey[k] = lx;
               tsKey[k + 1] = ly;
               tsKey[k + 2] = head;
               tsKey[k + 3] = reach;
               if (!bound) {
                  bound = true;
                  Timing.begin("torch");
                  GL20.glUseProgram(progTorch);
                  GL20.glUniform1i(uTorch[2], OCC_UNIT);
                  GL20.glUniform4i(uTorch[3], occZ0, (int)GodRays.Gl.occMaxTop, 0, 0);
                  GL20.glUniform4f(uTorch[4], Config.VOXEL_LIGHT_GLASS_PCT / 100F, 0.8F * Config.VOXEL_LIGHT_CROWN_PCT / 100F, 0F, GodRays.LEVEL);
                  GL20.glUniform1f(uTorch[5], Config.VOXEL_LIGHT_FENCE_PCT / 100F);
                  skipUniforms(progTorch);
                  GL13.glActiveTexture(GL13.GL_TEXTURE0 + OCC_UNIT);
                  GL11.glBindTexture(GL12.GL_TEXTURE_3D, GodRays.Gl.occTex);
                  GL13.glActiveTexture(GL13.GL_TEXTURE0);
                  GL42.glBindImageTexture(0, texTS, 0, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_R8);
               }
               GL20.glUniform4f(uTorch[0], lx, ly, head, reach);
               GL20.glUniform4f(uTorch[1], x0, y0, level, slot);
               GL43.glDispatchCompute(TS_TEX / 8, TS_TEX / 8, 2);
               torchTraces++;
               if (Config.DEV_VOXEL_LIGHT_TORCH_LOG) {
                  torchLog(slot, lx, ly, lzv, lh[i], level, reach, x0, y0);
               }
            }
            slot++;
         }
         for (int s = slot; s < 2; s++) {
            tsO[s * 4 + 3] = 0F;
         }
         tsOccSerial = occSerial;
         if (bound) {
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            GL42.glBindImageTexture(0, 0, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_R8);
            GL20.glUseProgram(0);
            ShaderHelper.forgetCurrentlyBound();
            Timing.end("torch");
         }
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + TS_UNIT);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texTS);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
      }

      static int occSerial; // bumped by every occupancy upload GodRays makes (render thread)
      private static ByteBuffer tsRead;

      /** devVoxelLightTorchLog: the retrace and how much of the ground within its reach the map puts in shadow (v < 0.5). */
      private static void torchLog(int slot, float lx, float ly, float lzv, float lh, int level, float reach, float x0, float y0) {
         GL42.glMemoryBarrier(GL42.GL_TEXTURE_UPDATE_BARRIER_BIT);
         if (tsRead == null) {
            tsRead = BufferUtils.createByteBuffer(TS_TEX * TS_TEX * 4);
         }
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texTS);
         tsRead.clear();
         GL11.glGetTexImage(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, tsRead);
         int in = 0, dark = 0, base = slot * 2 * TS_TEX * TS_TEX;
         for (int ty = 0; ty < TS_TEX; ty++) {
            for (int tx = 0; tx < TS_TEX; tx++) {
               float wx = x0 + (tx + 0.5F) * 0.25F, wy = y0 + (ty + 0.5F) * 0.25F;
               float dx = wx - lx, dy = wy - ly;
               if (dx * dx + dy * dy < reach * reach) {
                  in++;
                  if ((tsRead.get(base + ty * TS_TEX + tx) & 255) < 128) {
                     dark++;
                  }
               }
            }
         }
         Log.info(String.format(java.util.Locale.ROOT, "voxel light torch: t=%d slot=%d at %.3f,%.3f z=%.3f lh=%.3f level=%d reach=%.2f occZ0=%d serial=%d dark=%.4f",
            System.currentTimeMillis(), slot, lx, ly, lzv, lh, level, reach, occZ0, occSerial, in == 0 ? 0F : dark / (float)in));
      }
      private static final int[] U_L = new int[10];
      private static int glFrame, volVersion;

      /** A chunk texture's light layer in the surface cache. */
      static final class Slot {
         int layer, version = -1, cwx = Integer.MIN_VALUE, cwy, lvl0, lvl1, lastFrame, mapFrame;
         boolean ready, baked, mapped, stale;
         final double[] map = new double[7];
         zombie.core.textures.Texture depth;
      }

      private static final java.util.IdentityHashMap<zombie.core.textures.Texture, Slot> SLOTS = new java.util.IdentityHashMap<>();
      private static Slot[] byLayer;
      private static int freeLayers;
      private static final ArrayList<Slot> queue = new ArrayList<>();
      private static final java.util.HashMap<Integer, int[]> LAYER_LOC = new java.util.HashMap<>();
      static final boolean CACHE = "cache".equals(Config.VOXEL_LIGHT_APPLY);

      /**
       * PixelLight.chunkDraw (render thread, the composite program bound, per chunk texture draw): its light layer for this
       * draw, recorded with the texture's world mapping (A = x - y = aA u + bA, B = x + y - 6z = aB v + bB, C = x + y + 2z = bC -
       * depth / DEPTH_PER_XY); -1 while it has none lit (the volume per pixel).
       */
      static void draw(int program, zombie.core.textures.Texture depth, double[] map, int lvl0, int lvl1, int cwx, int cwy) {
         int[] loc = LAYER_LOC.get(program);
         if (loc == null) {
            loc = new int[] {GL20.glGetUniformLocation(program, "vxlLayer")};
            LAYER_LOC.put(program, loc);
         }
         if (loc[0] < 0) {
            return;
         }
         float layer = CACHE && "native".equals(Config.VOXEL_LIGHT_PENDING) ? -2F : -1F;
         if (CACHE && ready && on && depth != null && texLM != 0) {
            Slot sl = SLOTS.get(depth);
            if (sl == null) {
               sl = alloc(depth);
            }
            if (sl != null) {
               if (sl.cwx != cwx || sl.cwy != cwy || sl.lvl0 != lvl0 || sl.lvl1 != lvl1) {
                  sl.ready = false; // another chunk in a pooled texture
                  sl.mapped = false;
                  sl.cwx = cwx;
                  sl.cwy = cwy;
                  sl.lvl0 = lvl0;
                  sl.lvl1 = lvl1;
               }
               // the texture's world mapping: another one (a zoom renders it at another size, the camera's depth terms drift by
               // a rounding) is another layer
               if (map != null) {
                  double[] o = sl.map;
                  if (sl.mapped && (Math.abs(o[0] - map[0]) > 1e-6 || Math.abs(o[4] - map[4]) > 1e-6 || Math.abs(o[2] - map[2]) > 1e-3 || Math.abs(o[5] - map[5]) > 1e-3
                     || Math.abs(o[6] - map[6]) > 0.01)) {
                     sl.ready = false;
                     remaps++;
                  }
                  if (!sl.mapped || !sl.ready) {
                     System.arraycopy(map, 0, sl.map, 0, 7);
                     sl.mapFrame = glFrame;
                  }
                  sl.mapped = true;
               }
               sl.lastFrame = glFrame;
               if (sl.ready) {
                  layer = sl.layer;
               }
            }
         }
         GL20.glUniform1f(loc[0], layer);
      }

      /** The layers over this frame's fresh and settled regions are stale (relit within the budget). */
      private static void staleRegions(Frame f) {
         if (SLOTS.isEmpty()) {
            return;
         }
         for (Slot sl : SLOTS.values()) {
            if (sl.stale || !sl.ready) {
               continue;
            }
            int x0 = sl.cwx * 8 - 2, y0 = sl.cwy * 8 - 2, x1 = x0 + 12, y1 = y0 + 12; // the chunk and what its tall sprites reach
            for (int r = 0; r < f.nreg && !sl.stale; r++) {
               int k = r * 5;
               sl.stale = f.reg[k + 4] != 0 && x1 > f.reg[k] && x0 < f.reg[k] + f.reg[k + 2] && y1 > f.reg[k + 1] && y0 < f.reg[k + 1] + f.reg[k + 3];
            }
            for (int r = 0; r < f.nsettled && !sl.stale; r++) {
               int k = r * 4;
               sl.stale = x1 > f.settled[k] && x0 < f.settled[k + 2] && y1 > f.settled[k + 1] && y0 < f.settled[k + 3];
            }
         }
      }

      private static Slot alloc(zombie.core.textures.Texture depth) {
         if (byLayer == null) {
            byLayer = new Slot[Config.VOXEL_LIGHT_CACHE_LAYERS];
            freeLayers = byLayer.length;
         }
         int layer = -1;
         if (freeLayers > 0) {
            for (int i = 0; i < byLayer.length; i++) {
               if (byLayer[i] == null) {
                  layer = i;
                  freeLayers--;
                  break;
               }
            }
         } else {
            int oldest = Integer.MAX_VALUE; // the layer drawn longest ago, never one drawn this frame
            for (int i = 0; i < byLayer.length; i++) {
               if (byLayer[i].lastFrame < oldest && byLayer[i].lastFrame != glFrame) {
                  oldest = byLayer[i].lastFrame;
                  layer = i;
               }
            }
            if (layer < 0) {
               return null;
            }
            SLOTS.remove(byLayer[layer].depth);
            slotEvictions++;
         }
         Slot sl = new Slot();
         sl.layer = layer;
         sl.depth = depth;
         byLayer[layer] = sl;
         SLOTS.put(depth, sl);
         return sl;
      }

      /** Render thread, before the composite: the budget's worth of light layers relit (new ones, re-baked ones, then stale ones, newest drawn first). */
      private static void relight(Frame f) {
         if (byLayer == null || SLOTS.isEmpty()) {
            return;
         }
         for (int i = 0; i < f.nbaked; i++) {
            int wx = f.baked[i * 2], wy = f.baked[i * 2 + 1];
            for (Slot sl : SLOTS.values()) {
               if (sl.cwx == wx && sl.cwy == wy) {
                  sl.baked = true;
               }
            }
         }
         queue.clear();
         for (Slot sl : SLOTS.values()) {
            if (sl.mapped && sl.mapFrame != glFrame && (!sl.ready || sl.baked || sl.stale || sl.version != volVersion) && glFrame - sl.lastFrame < 4 && sl.depth.getID() > 0) {
               queue.add(sl);
            }
         }
         if (queue.isEmpty()) {
            return;
         }
         queue.sort((a, b) -> {
            int pa = !a.ready ? 0 : a.baked ? 1 : 2, pb = !b.ready ? 0 : b.baked ? 1 : 2;
            return pa != pb ? Integer.compare(pa, pb) : Integer.compare(b.lastFrame, a.lastFrame);
         });
         Timing.begin("relight");
         GL20.glUseProgram(progRelight);
         GL20.glUniform4i(U_L[5], f.winX0, f.winY0, f.zBase, 0);
         GL20.glUniform1i(U_L[0], OCC_UNIT);
         GL20.glUniform4i(U_L[6], occZ0, (int)GodRays.Gl.occMaxTop, 0, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + OCC_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, GodRays.Gl.occTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL20.glUniform1i(GL20.glGetUniformLocation(progRelight, "uDepth"), 48);
         GL20.glUniform1i(GL20.glGetUniformLocation(progRelight, "uT1"), A_UNIT);
         GL20.glUniform1i(GL20.glGetUniformLocation(progRelight, "uT2"), B_UNIT);
         GL20.glUniform1i(GL20.glGetUniformLocation(progRelight, "uE"), C_UNIT);
         GL20.glUniform1i(GL20.glGetUniformLocation(progRelight, "uCube"), CUBE ? 1 : 0);
         if (CUBE) {
            GL20.glUniform1f(GL20.glGetUniformLocation(progRelight, "uCubeGain"), Config.VOXEL_LIGHT_CUBE_GAIN_PCT / 100F);
            int[] us = {55, 56, 57};
            int[] ts = {texD[0], texD[1], texC0};
            String[] ns = {"uD0", "uD1", "uCf"};
            for (int i = 0; i < 3; i++) {
               GL13.glActiveTexture(GL13.GL_TEXTURE0 + us[i]);
               GL11.glBindTexture(GL12.GL_TEXTURE_3D, ts[i]);
               GL20.glUniform1i(GL20.glGetUniformLocation(progRelight, ns[i]), us[i]);
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
         }
         GL42.glBindImageTexture(0, texLM, 0, true, 0, GL15.GL_WRITE_ONLY, GL11.GL_RGBA16);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + 48);
         int n = Math.min(queue.size(), Config.VOXEL_LIGHT_RELIGHTS), bakedLeft = Config.VOXEL_LIGHT_RELIGHTS_BAKED;
         for (int i = 0; i < n; i++) {
            Slot sl = queue.get(i);
            if (sl.ready && sl.baked && !sl.stale && sl.version == volVersion && bakedLeft-- <= 0) {
               continue; // re-baked only (a turn re-bakes the cut walls' chunks): a few a frame
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sl.depth.getID());
            // the reference: integer parts of A, B, C at u = v = 0 (the floats stay small)
            double[] m = sl.map;
            int rA = (int)Math.floor(m[2]), rB = (int)Math.floor(m[5]), rC = (int)Math.floor(m[6]);
            GL20.glUniform4f(U_L[1], (float)m[0], (float)m[1], (float)m[3], (float)m[4]);
            GL20.glUniform4f(U_L[7], (float)(m[2] - rA), (float)(m[5] - rB), sl.cwx * 8, sl.cwy * 8);
            GL20.glUniform4f(U_L[2], (float)(m[6] - rC), (float)(1.0 / PixelLight.DEPTH_PER_XY), sl.layer, Config.VOXEL_LIGHT_WRAP_PCT / 100F);
            GL20.glUniform4i(U_L[3], rA, rB, rC, "none".equals(Config.VOXEL_LIGHT_NORMAL) ? 0 : 1);
            GL20.glUniform4f(U_L[4], sl.lvl0, sl.lvl1, Config.VOXEL_LIGHT_SKY_FLOOR_PCT / 100F, Config.DEV_VOXEL_LIGHT_VIEW_CYCLE > 0 || Config.DEV_VOXEL_LIGHT_VIEW != 0 ? 1F : 0F);
            GL43.glDispatchCompute((Config.VOXEL_LIGHT_CACHE_W + 7) / 8, (Config.VOXEL_LIGHT_CACHE_H + 7) / 8, 1);
            if (!sl.ready) {
               relightsNew++;
            }
            sl.ready = true;
            sl.baked = false;
            sl.stale = false;
            sl.version = volVersion;
            relights++;
         }
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
         GL42.glBindImageTexture(0, 0, 0, false, 0, GL15.GL_READ_ONLY, GL11.GL_RGBA16);
         GL20.glUseProgram(0);
         ShaderHelper.forgetCurrentlyBound();
         Timing.end("relight");
      }
      private static int uFence = -2;

      static boolean init() {
         if (progDirect != 0) {
            return true;
         }
         if (!GL.getCapabilities().OpenGL43) {
            fail("no OpenGL 4.3 (compute shaders)");
            return false;
         }
         String defs = "#version 430\n#define LM_SIZE " + LM_SIZE + "\n#define R " + R + "\n#define RZ " + RZ + "\n#define N " + N + "\n#define LZ " + LZ + "\n#define TX " + TX + "\n#define TZ " + TZ + "\n#define BT " + BS * R + "\n";
         progDirect = GodRays.Gl.computeProgram(defs + COMMON_CS + TRACE_GLSL + DIRECT_CS, "voxel light direct");
         progTorch = GodRays.Gl.computeProgram(defs + COMMON_CS + TRACE_GLSL + TORCH_CS, "voxel light torch");
         progCoarse = GodRays.Gl.computeProgram(defs + COMMON_CS + COARSE_CS, "voxel light coarse");
         progLmap = MAPS ? GodRays.Gl.computeProgram(defs + COMMON_CS + TRACE_GLSL + LMAP_CS, "voxel light maps") : -1;
         progInject = GodRays.Gl.computeProgram(defs + COMMON_CS + TRACE_GLSL + SKYDIR_GLSL + INJECT_CS, "voxel light inject");
         progCascade = GodRays.Gl.computeProgram(defs + COMMON_CS + TRACE_GLSL + SKYDIR_GLSL + CASCADE_CS, "voxel light cascade");
         progCube = GodRays.Gl.computeProgram(defs + COMMON_CS + CUBE_CS, "voxel light cube");
         progFlood = GodRays.Gl.computeProgram(defs + COMMON_CS + FLOOD_CS, "voxel light flood");
         progResolve = GodRays.Gl.computeProgram(defs + COMMON_CS + RESOLVE_CS, "voxel light resolve");
         progRelight = GodRays.Gl.computeProgram(defs + COMMON_CS + RELIGHT_CS, "voxel light relight");
         if (progDirect == 0 || progInject == 0 || progFlood == 0 || progResolve == 0 || progRelight == 0 || progTorch == 0 || progCoarse == 0 || progLmap == 0 || progCascade == 0 || progCube == 0) {
            fail("compute shaders did not compile (log above)");
            return false;
         }
         locations(progDirect, U_D);
         locations(progInject, U_I);
         locations(progFlood, U_F);
         locations(progResolve, U_R);
         String[] ln = {"occ", "uAff", "uAff2", "uRef", "uLev", "uWin", "uOcc", "uAff0", "uOpt2"};
         for (int i = 0; i < ln.length; i++) {
            U_L[i] = GL20.glGetUniformLocation(progRelight, ln[i]);
         }
         texA = tex3d(TX, TX, TZ, GL30.GL_RGBA16F, true);
         texB = tex3d(TX, TX, TZ, GL11.GL_RGBA8, true);
         texS = tex3d(N, N, LZ, GL30.GL_RGBA16F, false);
         texC0 = tex3d(N, N, LZ, GL30.GL_RGBA16F, true);
         texC1 = tex3d(N, N, LZ, GL30.GL_RGBA16F, true);
         texT1 = texArray(TX, TX, TZ, GL11.GL_RGBA8, true);
         texT2 = texArray(TX, TX, TZ, GL11.GL_RGBA8, true);
         texE = texArray(N, N, LZ, GL30.GL_R8UI, false);
         texO4 = tex3d(GodRays.OCC_N / 4, GodRays.OCC_N / 4, GodRays.OCC_L, GL30.GL_R8UI, false);
         texProbe = tex3d(N / 2, N / 2, LZ * 5, GL11.GL_RGBA8, false);
         for (int i = 0; i < 4; i++) {
            texD[i] = tex3d(N, N, LZ, GL30.GL_RGBA16F, false);
         }
         if (MAPS) {
            texLmap = texArray(LM_SIZE, LM_SIZE, LM_LAYERS, GL30.GL_RGBA16F, false);
            bufMapJobs = GL15.glGenBuffers();
         }
         texTS = texArray(TS_TEX, TS_TEX, 4, GL30.GL_R8, true);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texTS);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
         uTorch = new int[] {GL20.glGetUniformLocation(progTorch, "uT"), GL20.glGetUniformLocation(progTorch, "uTO"), GL20.glGetUniformLocation(progTorch, "occ"),
            GL20.glGetUniformLocation(progTorch, "uOcc"), GL20.glGetUniformLocation(progTorch, "uOpt"), GL20.glGetUniformLocation(progTorch, "uFence")};
         if (CACHE) {
            texLM = texArray(Config.VOXEL_LIGHT_CACHE_W, Config.VOXEL_LIGHT_CACHE_H, Config.VOXEL_LIGHT_CACHE_LAYERS, GL11.GL_RGBA16, true); // 16-bit: the validity mark (see RELIGHT_CS)
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texLM);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
            Log.info("voxel light: surface cache " + Config.VOXEL_LIGHT_CACHE_W + "x" + Config.VOXEL_LIGHT_CACHE_H + " x " + Config.VOXEL_LIGHT_CACHE_LAYERS + " layers ("
               + ((long)Config.VOXEL_LIGHT_CACHE_W * Config.VOXEL_LIGHT_CACHE_H * Config.VOXEL_LIGHT_CACHE_LAYERS * 8 >> 20) + " MB)");
         }
         bufJobs = GL15.glGenBuffers();
         bufIdx = GL15.glGenBuffers();
         bufLights = GL15.glGenBuffers();
         clearAll();
         long mb = ((long)TX * TX * TZ * 12 + (long)N * N * LZ * 24) >> 20;
         Log.info("voxel light: volume " + TX + "x" + TX + "x" + TZ + " (" + N + " squares, " + R + "/square, " + RZ + "/level), flood " + N + "x" + N + "x" + LZ + ", " + mb + " MB");
         return true;
      }

      private static void locations(int p, int[] u) {
         String[] names = {"occ", "uWin", "uOcc", "uOpt", "uOpt2", "uB", "uA", "uC", "uReg"};
         for (int i = 0; i < names.length; i++) {
            u[i] = GL20.glGetUniformLocation(p, names[i]);
         }
      }

      private static int tex3d(int x, int y, int z, int fmt, boolean linear) {
         int t = GL11.glGenTextures();
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, t);
         GL42.glTexStorage3D(GL12.GL_TEXTURE_3D, 1, fmt, x, y, z);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, linear ? GL11.GL_LINEAR : GL11.GL_NEAREST);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, linear ? GL11.GL_LINEAR : GL11.GL_NEAREST);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
         return t;
      }

      private static int texArray(int x, int y, int z, int fmt, boolean linear) {
         int t = GL11.glGenTextures();
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, t);
         GL42.glTexStorage3D(GL30.GL_TEXTURE_2D_ARRAY, 1, fmt, x, y, z);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, linear ? GL11.GL_LINEAR : GL11.GL_NEAREST);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, linear ? GL11.GL_LINEAR : GL11.GL_NEAREST);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
         return t;
      }

      private static void clearAll() {
         float[] zero = {0F, 0F, 0F, 0F};
         for (int t : new int[] {texA, texS, texC0, texC1, texD[0], texD[1], texD[2], texD[3]}) {
            org.lwjgl.opengl.GL44.glClearTexImage(t, 0, GL11.GL_RGBA, GL11.GL_FLOAT, zero);
         }
         org.lwjgl.opengl.GL44.glClearTexImage(texB, 0, GL11.GL_RGBA, GL11.GL_FLOAT, new float[] {0.5F, 0.5F, 1F, 0F});
         org.lwjgl.opengl.GL44.glClearTexImage(texT1, 0, GL11.GL_RGBA, GL11.GL_FLOAT, zero);
         org.lwjgl.opengl.GL44.glClearTexImage(texT2, 0, GL11.GL_RGBA, GL11.GL_FLOAT, zero);
         org.lwjgl.opengl.GL44.glClearTexImage(texE, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, new int[] {0});
      }

      static void render(Frame f) {
         if (failed) {
            ready = false;
            return;
         }
         on = f.on && !f.devOff;
         glFrame++;
         if (!f.on) {
            return;
         }
         if (!init()) {
            return;
         }
         if (GodRays.Gl.occTex == 0) {
            return; // the occupancy is not there yet
         }
         winX0 = f.winX0;
         winY0 = f.winY0;
         zBase = f.zBase;
         occZ0 = GodRays.Gl.occZ0Now;
         ambR = f.ambR;
         ambG = f.ambG;
         ambB = f.ambB;
         if (f.clearAll) {
            clearAll();
         }
         if (f.devOff) {
            return;
         }
         Timing.begin("frame");
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + OCC_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, GodRays.Gl.occTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         coarse();
         // 1. the lights (when they changed) and this frame's bricks
         if (f.lightSerial != uploadedSerial) {
            uploadedSerial = f.lightSerial;
            fb = grow(fb, Math.max(12, f.lights * 12));
            fb.clear();
            fb.put(f.lightData, 0, Math.max(12, f.lights * 12)).flip();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, bufLights);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, fb, GL15.GL_STREAM_DRAW);
         }
         if (f.nmaps > 0) {
            Timing.begin("maps");
            ib = growI(ib, f.nmaps * 2);
            ib.clear();
            ib.put(f.maps, 0, f.nmaps * 2).flip();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, bufMapJobs);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, ib, GL15.GL_STREAM_DRAW);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, bufLights);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, bufMapJobs);
            GL20.glUseProgram(progLmap);
            common(U_D, f);
            GL20.glUniform1i(GL20.glGetUniformLocation(progLmap, "occ"), OCC_UNIT);
            GL20.glUniform1f(GL20.glGetUniformLocation(progLmap, "uFence"), Config.VOXEL_LIGHT_FENCE_PCT / 100F);
            GL20.glUniform4f(GL20.glGetUniformLocation(progLmap, "uOpt"), Config.VOXEL_LIGHT_GLASS_PCT / 100F, 0.8F * Config.VOXEL_LIGHT_CROWN_PCT / 100F, 0F, GodRays.LEVEL);
            GL20.glUniform4i(GL20.glGetUniformLocation(progLmap, "uOcc"), occZ0, f.maxTop, 0, 0);
            GL42.glBindImageTexture(0, texLmap, 0, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA16F);
            GL43.glDispatchCompute(LM_SIZE / 8, LM_SIZE / 8, f.nmaps);
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            Timing.end("maps");
         }
         if (f.njobs > 0) {
            Timing.begin("direct");
            ib = growI(ib, f.njobs * 4);
            ib.clear();
            ib.put(f.jobs, 0, f.njobs * 4).flip();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, bufJobs);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, ib, GL15.GL_STREAM_DRAW);
            ib = growI(ib, Math.max(4, f.nidx));
            ib.clear();
            ib.put(f.idx, 0, Math.max(1, f.nidx)).flip();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, bufIdx);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, ib, GL15.GL_STREAM_DRAW);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, bufJobs);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, bufIdx);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, bufLights);
            GL20.glUseProgram(progDirect);
            common(U_D, f);
            if (uFence == -2) {
               uFence = GL20.glGetUniformLocation(progDirect, "uFence");
            }
            GL20.glUniform1f(uFence, Config.VOXEL_LIGHT_FENCE_PCT / 100F);
            skipUniforms(progDirect);
            GL20.glUniform1i(GL20.glGetUniformLocation(progDirect, "uMaps"), MAPS ? 1 : 0);
            if (MAPS) {
               GL13.glActiveTexture(GL13.GL_TEXTURE0 + LMAP_UNIT);
               GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texLmap);
               GL13.glActiveTexture(GL13.GL_TEXTURE0);
               GL20.glUniform1i(GL20.glGetUniformLocation(progDirect, "uLmap"), LMAP_UNIT);
            }
            GL42.glBindImageTexture(0, texA, 0, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA16F);
            GL42.glBindImageTexture(1, texB, 0, true, 0, GL15.GL_WRITE_ONLY, GL11.GL_RGBA8);
            GL43.glDispatchCompute(f.njobs, 1, 1);
            dispatches++;
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            Timing.end("direct");
         }
         // 2. per region: inject where fresh, the flood's ping-pong iterations (C0 -> C1 -> C0: the composite reads C0), the resolve
         if (f.clearAll) {
            volVersion++; // every layer stale
         }
         staleRegions(f);
         boolean anyFresh = false;
         for (int r = 0; r < f.nreg; r++) {
            anyFresh |= f.reg[r * 5 + 4] != 0;
         }
         boolean cascades = "cascades".equals(Config.VOXEL_LIGHT_SKY_METHOD);
         if (anyFresh && cascades) {
            Timing.begin("cascade");
            GL20.glUseProgram(progCascade);
            int pc = progCascade;
            GL20.glUniform1i(GL20.glGetUniformLocation(pc, "occ"), OCC_UNIT);
            GL20.glUniform4i(GL20.glGetUniformLocation(pc, "uWin"), f.winX0, f.winY0, f.zBase, 0);
            GL20.glUniform4i(GL20.glGetUniformLocation(pc, "uOcc"), occZ0, f.maxTop, 0, 0);
            GL20.glUniform4f(GL20.glGetUniformLocation(pc, "uOpt"), Config.VOXEL_LIGHT_GLASS_PCT / 100F, 0.8F * Config.VOXEL_LIGHT_CROWN_PCT / 100F, 0F, GodRays.LEVEL);
            GL20.glUniform1f(GL20.glGetUniformLocation(pc, "uFence"), Config.VOXEL_LIGHT_FENCE_PCT / 100F);
            skipUniforms(pc);
            GL42.glBindImageTexture(0, texProbe, 0, true, 0, GL15.GL_WRITE_ONLY, GL11.GL_RGBA8);
            int ur = GL20.glGetUniformLocation(pc, "uReg");
            for (int r = 0; r < f.nreg; r++) {
               if (f.reg[r * 5 + 4] != 0) {
                  GL20.glUniform4i(ur, f.reg[r * 5], f.reg[r * 5 + 1], f.reg[r * 5 + 2], f.reg[r * 5 + 3]);
                  GL43.glDispatchCompute((f.reg[r * 5 + 2] / 2 + 2 + 7) / 8, (f.reg[r * 5 + 3] / 2 + 2 + 7) / 8, LZ);
               }
            }
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            Timing.end("cascade");
         }
         if (anyFresh) {
            Timing.begin("inject");
            GL20.glUseProgram(progInject);
            common(U_I, f);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + A_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, texA);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL20.glUniform1i(U_I[6], A_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(progInject, "uSkyCones"), "cones".equals(Config.VOXEL_LIGHT_SKY_METHOD) ? 1 : "zenith".equals(Config.VOXEL_LIGHT_SKY_METHOD) ? 2 : cascades ? 3 : 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + PROBE_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, texProbe);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL20.glUniform1i(GL20.glGetUniformLocation(progInject, "uProbe"), PROBE_UNIT);
            GL20.glUniform1f(GL20.glGetUniformLocation(progInject, "uFence"), Config.VOXEL_LIGHT_FENCE_PCT / 100F);
            skipUniforms(progInject);
            GL42.glBindImageTexture(0, texS, 0, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA16F);
            GL42.glBindImageTexture(1, texE, 0, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_R8UI);
            for (int r = 0; r < f.nreg; r++) {
               if (f.reg[r * 5 + 4] != 0) {
                  region(U_I, f, r);
                  GL43.glDispatchCompute((f.reg[r * 5 + 2] + 7) / 8, (f.reg[r * 5 + 3] + 7) / 8, LZ);
               }
            }
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            Timing.end("inject");
         }
         if (f.prop > 0 && f.nreg > 0) {
            Timing.begin("flood");
            GL20.glUseProgram(progFlood);
            common(U_F, f);
            for (int i = 0; i < f.prop; i++) {
               int src = i % 2 == 0 ? texC0 : texC1, dst = i % 2 == 0 ? texC1 : texC0;
               GL42.glBindImageTexture(0, texS, 0, true, 0, GL15.GL_READ_ONLY, GL30.GL_RGBA16F);
               GL42.glBindImageTexture(1, src, 0, true, 0, GL15.GL_READ_ONLY, GL30.GL_RGBA16F);
               GL42.glBindImageTexture(2, dst, 0, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA16F);
               for (int r = 0; r < f.nreg; r++) {
                  region(U_F, f, r);
                  GL43.glDispatchCompute((f.reg[r * 5 + 2] + 7) / 8, (f.reg[r * 5 + 3] + 7) / 8, LZ);
               }
               GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
               floodIters++;
            }
            Timing.end("flood");
            if (CUBE) {
               Timing.begin("cube");
               GL20.glUseProgram(progCube);
               int pc = progCube;
               GL20.glUniform1i(GL20.glGetUniformLocation(pc, "occ"), OCC_UNIT);
               GL20.glUniform4i(GL20.glGetUniformLocation(pc, "uWin"), f.winX0, f.winY0, f.zBase, 0);
               GL20.glUniform4i(GL20.glGetUniformLocation(pc, "uOcc"), occZ0, f.maxTop, 0, 0);
               GL20.glUniform4f(GL20.glGetUniformLocation(pc, "uOpt"), Config.VOXEL_LIGHT_GLASS_PCT / 100F, 0F, 0F, GodRays.LEVEL);
               GL20.glUniform4f(GL20.glGetUniformLocation(pc, "uOpt2"), 0F, Config.VOXEL_LIGHT_BOUNCE_DECAY_PCT / 100F, 0F, 0F);
               int ur = GL20.glGetUniformLocation(pc, "uReg");
               for (int i = 0; i < f.prop; i++) {
                  int a = i % 2 == 0 ? 0 : 2, b = i % 2 == 0 ? 2 : 0;
                  GL42.glBindImageTexture(0, texS, 0, true, 0, GL15.GL_READ_ONLY, GL30.GL_RGBA16F);
                  GL42.glBindImageTexture(1, texD[a], 0, true, 0, GL15.GL_READ_ONLY, GL30.GL_RGBA16F);
                  GL42.glBindImageTexture(2, texD[a + 1], 0, true, 0, GL15.GL_READ_ONLY, GL30.GL_RGBA16F);
                  GL42.glBindImageTexture(3, texD[b], 0, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA16F);
                  GL42.glBindImageTexture(4, texD[b + 1], 0, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA16F);
                  for (int r = 0; r < f.nreg; r++) {
                     GL20.glUniform4i(ur, f.reg[r * 5], f.reg[r * 5 + 1], f.reg[r * 5 + 2], f.reg[r * 5 + 3]);
                     GL43.glDispatchCompute((f.reg[r * 5 + 2] + 7) / 8, (f.reg[r * 5 + 3] + 7) / 8, LZ);
                  }
                  GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
               }
               GL42.glBindImageTexture(4, 0, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_RGBA16F);
               Timing.end("cube");
            }
         }
         // 3. the resolve: the regions into the composite's layers
         if (f.nreg > 0) {
            Timing.begin("resolve");
            GL20.glUseProgram(progResolve);
            common(U_R, f);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + A_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, texA);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + B_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, texB);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + C_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, texC0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL20.glUniform1i(U_R[6], A_UNIT);
            GL20.glUniform1i(U_R[5], B_UNIT);
            GL20.glUniform1i(U_R[7], C_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(progResolve, "uCube"), CUBE ? 1 : 0);
            GL42.glBindImageTexture(1, texE, 0, true, 0, GL15.GL_READ_ONLY, GL30.GL_R8UI);
            GL42.glBindImageTexture(2, texT1, 0, true, 0, GL15.GL_WRITE_ONLY, GL11.GL_RGBA8);
            GL42.glBindImageTexture(3, texT2, 0, true, 0, GL15.GL_WRITE_ONLY, GL11.GL_RGBA8);
            for (int r = 0; r < f.nreg; r++) {
               region(U_R, f, r);
               GL43.glDispatchCompute((f.reg[r * 5 + 2] * R + 7) / 8, (f.reg[r * 5 + 3] * R + 7) / 8, TZ);
            }
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            Timing.end("resolve");
         }
         for (int i = 0; i < 4; i++) {
            GL42.glBindImageTexture(i, 0, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_RGBA16F);
         }
         GL20.glUseProgram(0);
         ShaderHelper.forgetCurrentlyBound();
         // 4. the layers for the composite
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + A_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texT1);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + B_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texT2);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + C_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texE);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         if (CACHE) {
            relight(f);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + LM_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texLM);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
         }
         ready = ambR >= 0F;
         Timing.end("frame");
         if (!Config.DEV_VOXEL_LIGHT_DUMP_AT.isEmpty()) {
            dumps(f);
         }
      }

      private static long dumpT0;
      private static int dumpNext;

      /** devVoxelLightDumpAt: A and C read back (float32, x fastest) with the mapping, to ~/Zomboid/pzopt-voxel/t<s>-*. */
      private static void dumps(Frame f) {
         long now = System.currentTimeMillis();
         if (dumpT0 == 0L) {
            dumpT0 = now;
         }
         String[] at = Config.DEV_VOXEL_LIGHT_DUMP_AT.split(",");
         if (dumpNext >= at.length || (now - dumpT0) / 1000.0 < Double.parseDouble(at[dumpNext].trim())) {
            return;
         }
         String tag = "t" + at[dumpNext++].trim();
         try {
            java.io.File dir = new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt-voxel");
            dir.mkdirs();
            write(new java.io.File(dir, tag + "-A.bin"), texA, TX, TX, TZ);
            write(new java.io.File(dir, tag + "-C.bin"), texC0, N, N, LZ);
            write(new java.io.File(dir, tag + "-S.bin"), texS, N, N, LZ);
            write2d(new java.io.File(dir, tag + "-TS.bin"), texTS, TS_TEX, TS_TEX, 4);
            writeU(new java.io.File(dir, tag + "-occ.bin"), GodRays.Gl.occTex, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, GodRays.OCC_N * GodRays.OCC_N * GodRays.OCC_L * 4);
            writeU(new java.io.File(dir, tag + "-occ4.bin"), texO4, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, GodRays.OCC_N / 4 * GodRays.OCC_N / 4 * GodRays.OCC_L);
            java.nio.file.Files.writeString(new java.io.File(dir, tag + "-TS.txt").toPath(), java.util.Arrays.toString(tsO) + " lights " + java.util.Arrays.toString(tsLight) + "\n");
            String meta = "occZ0Now " + GodRays.Gl.occZ0Now + "\nmaxTop " + f.maxTop + "\n" + "winX0 " + f.winX0 + "\nwinY0 " + f.winY0 + "\nzBase " + f.zBase + "\noccZ0 " + occZ0 + "\nN " + N + "\nR " + R + "\nRZ " + RZ + "\nLZ " + LZ
               + "\nlights " + f.lights + "\namb " + f.ambR + " " + f.ambG + " " + f.ambB + "\n";
            java.nio.file.Files.writeString(new java.io.File(dir, tag + "-meta.txt").toPath(), meta);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < f.lights; i++) {
               for (int k = 0; k < 12; k++) {
                  sb.append(k == 3 && f.lightData[i * 12 + 7] == 1F ? Float.floatToRawIntBits(f.lightData[i * 12 + 3]) : f.lightData[i * 12 + k]).append(k == 11 ? '\n' : ' ');
               }
            }
            java.nio.file.Files.writeString(new java.io.File(dir, tag + "-lights.txt").toPath(), sb.toString());
            Log.info("voxel light: dumped " + tag + " to " + dir);
         } catch (Throwable t) {
            Log.warn("voxel light: dump failed: " + t);
         }
      }

      private static void writeU(java.io.File file, int tex, int fmt, int type, int bytes) throws java.io.IOException {
         ByteBuffer b = BufferUtils.createByteBuffer(bytes);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + 48);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex);
         GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
         GL11.glGetTexImage(GL12.GL_TEXTURE_3D, 0, fmt, type, b);
         GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            ch.write(b);
         }
      }

      private static void write2d(java.io.File file, int tex, int x, int y, int z) throws java.io.IOException {
         ByteBuffer b = BufferUtils.createByteBuffer(x * y * z * 4);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + 48);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, tex);
         GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
         GL11.glGetTexImage(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RED, GL11.GL_FLOAT, b);
         GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            ch.write(b);
         }
      }

      private static void write(java.io.File file, int tex, int x, int y, int z) throws java.io.IOException {
         ByteBuffer b = BufferUtils.createByteBuffer(x * y * z * 16);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + 48); // a unit nobody's state cache tracks
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex);
         GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
         GL11.glGetTexImage(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, b);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            ch.write(b);
         }
      }

      private static void region(int[] u, Frame f, int r) {
         GL20.glUniform4i(u[8], f.reg[r * 5], f.reg[r * 5 + 1], f.reg[r * 5 + 2], f.reg[r * 5 + 3]);
      }

      private static void common(int[] u, Frame f) {
         GL20.glUniform1i(u[0], OCC_UNIT);
         GL20.glUniform4i(u[1], f.winX0, f.winY0, f.zBase, 0);
         GL20.glUniform4i(u[2], occZ0, f.maxTop, 0, 0);
         GL20.glUniform4f(u[3], Config.VOXEL_LIGHT_GLASS_PCT / 100F, 0.8F * Config.VOXEL_LIGHT_CROWN_PCT / 100F,
            "max".equals(Config.VOXEL_LIGHT_COMBINE) ? 1F : "add".equals(Config.VOXEL_LIGHT_COMBINE) ? 2F : "hue".equals(Config.VOXEL_LIGHT_COMBINE) ? 3F : 0F, GodRays.LEVEL);
         GL20.glUniform4f(u[4], Config.VOXEL_LIGHT_SKY_DECAY_PCT / 100F, Config.VOXEL_LIGHT_BOUNCE_DECAY_PCT / 100F, Config.VOXEL_LIGHT_BOUNCE ? Config.VOXEL_LIGHT_BOUNCE_PCT / 100F : 0F,
            Config.VOXEL_LIGHT_SKY ? 1F : 0F);
      }

      private static FloatBuffer grow(FloatBuffer b, int n) {
         return b != null && b.capacity() >= n ? b : BufferUtils.createFloatBuffer(Math.max(n, b == null ? 4096 : b.capacity() * 2));
      }

      private static IntBuffer growI(IntBuffer b, int n) {
         return b != null && b.capacity() >= n ? b : BufferUtils.createIntBuffer(Math.max(n, b == null ? 4096 : b.capacity() * 2));
      }

      // per composite program: the uniform locations
      private static final java.util.HashMap<Integer, int[]> LOC = new java.util.HashMap<>();

      /** PixelLight.setChunkUniforms (the program bound, once per program and frame): the volume's mapping, or off. */
      static void uniforms(int program, int ox, int oy) {
         int[] l = LOC.get(program);
         if (l == null) {
            l = new int[] {GL20.glGetUniformLocation(program, "vxlMap"), GL20.glGetUniformLocation(program, "vxlWin"), GL20.glGetUniformLocation(program, "vxlMapZ"),
               GL20.glGetUniformLocation(program, "vxlOccI"), GL20.glGetUniformLocation(program, "vxlAmb"), GL20.glGetUniformLocation(program, "vxlOpt"),
               GL20.glGetUniformLocation(program, "vxlTsO"), GL20.glGetUniformLocation(program, "vxlTsI")};
            LOC.put(program, l);
         }
         if (l[0] < 0) {
            return;
         }
         boolean live = ready && on && !failed;
         GL20.glUniform4f(l[0], Math.floorMod(ox, N), Math.floorMod(oy, N), 1F / N, live ? 1F : 0F);
         if (!live) {
            return;
         }
         GL20.glUniform4f(l[1], winX0 - ox, winY0 - oy, N, zBase);
         GL20.glUniform4f(l[2], RZ, 0.5F / RZ, 0.5F / R, devView());
         GL20.glUniform4i(l[3], ox & 255, oy & 255, occZ0, 0);
         GL20.glUniform4f(l[4], ambR, ambG, ambB, Config.VOXEL_LIGHT_SKY_FLOOR_PCT / 100F);
         if (l.length > 6 && l[6] >= 0) {
            GL20.glUniform4fv(l[6], new float[] {tsO[0] - ox, tsO[1] - oy, tsO[2], tsO[3], tsO[4] - ox, tsO[5] - oy, tsO[6], tsO[7]});
            GL20.glUniform2i(l[7], tsLight[0], tsLight[1]);
         }
         GL20.glUniform4f(l[5], Config.VOXEL_LIGHT_WRAP_PCT / 100F, Config.VOXEL_LIGHT_BOUNCE ? 1F : 0F, Config.VOXEL_LIGHT_SKY ? (Config.VOXEL_LIGHT_AMBIENT_ADD ? 2F : 1F) : 0F, "none".equals(Config.VOXEL_LIGHT_NORMAL) ? 0F : 1F);
      }
   }

   private static long viewT0;
   private static int lastView = -1;

   /** The dev view now: devVoxelLightView, or the cycle's (devVoxelLightViewCycle, each switch logged with its epoch). */
   static int devView() {
      if (Config.DEV_VOXEL_LIGHT_VIEW_CYCLE <= 0) {
         return Config.DEV_VOXEL_LIGHT_VIEW;
      }
      long now = System.currentTimeMillis();
      if (viewT0 == 0L) {
         viewT0 = now;
      }
      String[] list = Config.DEV_VOXEL_LIGHT_VIEW_LIST.split(",");
      int v = Integer.parseInt(list[(int)((now - viewT0) / Config.DEV_VOXEL_LIGHT_VIEW_CYCLE % list.length)].trim());
      if (v != lastView) {
         lastView = v;
         Log.info("voxel light: dev view " + v + " from epoch_ms " + now);
      }
      return v;
   }

   /** dev (devVoxelLightTiming): GPU time of the passes, a line every 5 s. */
   static final class Timing {
      private static final int SLOTS = 64;
      private static int[] q;
      private static final String[] name = new String[SLOTS];
      private static final boolean[] busy = new boolean[SLOTS];
      private static int next;
      private static final java.util.HashMap<String, Integer> open = new java.util.HashMap<>();
      private static final java.util.TreeMap<String, ArrayList<Long>> all = new java.util.TreeMap<>();
      private static long lastLog;

      static void begin(String n) {
         if (!Config.DEV_VOXEL_LIGHT_TIMING) {
            return;
         }
         if (q == null) {
            q = new int[SLOTS * 2];
            for (int i = 0; i < q.length; i++) {
               q[i] = GL15.glGenQueries();
            }
         }
         if ("frame".equals(n)) {
            collect();
         }
         int s = next;
         if (busy[s]) {
            return;
         }
         next = (next + 1) % SLOTS;
         GL33.glQueryCounter(q[s * 2], GL33.GL_TIMESTAMP);
         name[s] = n;
         open.put(n, s);
      }

      static void end(String n) {
         if (!Config.DEV_VOXEL_LIGHT_TIMING || q == null) {
            return;
         }
         Integer s = open.remove(n);
         if (s == null) {
            return;
         }
         GL33.glQueryCounter(q[s * 2 + 1], GL33.GL_TIMESTAMP);
         busy[s] = true;
      }

      private static void collect() {
         for (int s = 0; s < SLOTS; s++) {
            if (!busy[s] || GL15.glGetQueryObjecti(q[s * 2 + 1], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
               continue;
            }
            long t = GL33.glGetQueryObjecti64(q[s * 2 + 1], GL15.GL_QUERY_RESULT) - GL33.glGetQueryObjecti64(q[s * 2], GL15.GL_QUERY_RESULT);
            all.computeIfAbsent(name[s], k -> new ArrayList<>()).add(t);
            busy[s] = false;
         }
         long now = System.currentTimeMillis();
         if (now - lastLog > 5000L && !all.isEmpty()) {
            lastLog = now;
            StringBuilder sb = new StringBuilder("voxel light timing:");
            for (java.util.Map.Entry<String, ArrayList<Long>> e : all.entrySet()) {
               ArrayList<Long> v = e.getValue();
               if (v.isEmpty()) {
                  continue;
               }
               long sum = 0L;
               for (long x : v) {
                  sum += x;
               }
               java.util.Collections.sort(v);
               sb.append(String.format(java.util.Locale.ROOT, " %s median %.1f us mean %.1f p90 %.1f (%d)", e.getKey(), v.get(v.size() / 2) / 1000.0, sum / 1000.0 / v.size(),
                  v.get(Math.min(v.size() - 1, v.size() * 9 / 10)) / 1000.0, v.size()));
               v.clear();
            }
            sb.append(" epoch_ms=").append(now);
            Log.info(sb.toString());
         }
      }
   }

   // ------------------------------------------------------------------------------------------------ GLSL

   /** Occupancy access and the window (all compute programs). */
   static final String COMMON_CS = String.join("\n",
      "uniform usampler3D occ;", // GodRays' occupancy: R32UI, 256 x 256 squares (toroidal) x 16 levels from uOcc.x
      "uniform ivec4 uWin;", // x, y: the window's first square (world), z: the volume's first level
      "uniform ivec4 uOcc;", // x: the occupancy's first level, y: above the tallest level with anything in it
      "uniform vec4 uOpt;", // x: glass transmittance, y: crown extinction a square, z: combine (0 screen, 1 max, 2 add), w: squares a level
      "uniform vec4 uOpt2;", // x: sky decay, y: bounce decay, z: bounce albedo (0: none), w: sky on
      "uniform ivec4 uReg;", // the region dispatched: x, y its first square (world), z, w its squares across (inject, flood, resolve)
      "uint occAt(int x, int y, int L) {",
      "   int lz = L - uOcc.x;",
      "   if (lz < 0 || lz > 15) return 0u;",
      "   return texelFetch(occ, ivec3(x & 255, y & 255, lz), 0).r;",
      "}",
      "vec2 octEnc(vec3 v) {",
      "   v /= abs(v.x) + abs(v.y) + abs(v.z);",
      "   return v.z >= 0.0 ? v.xy : (1.0 - abs(v.yx)) * vec2(v.x >= 0.0 ? 1.0 : -1.0, v.y >= 0.0 ? 1.0 : -1.0);",
      "}",
      "vec3 octDec(vec2 o) {",
      "   vec3 v = vec3(o, 1.0 - abs(o.x) - abs(o.y));",
      "   if (v.z < 0.0) v.xy = (1.0 - abs(v.yx)) * vec2(v.x >= 0.0 ? 1.0 : -1.0, v.y >= 0.0 ? 1.0 : -1.0);",
      "   return normalize(v);",
      "}",
      // the storage cell of world square x (toroidal) and back
      "int worldX(int sx) { return uWin.x + ((sx - (uWin.x & (N - 1))) & (N - 1)); }",
      "int worldY(int sy) { return uWin.y + ((sy - (uWin.y & (N - 1))) & (N - 1)); }",
      "");

   /** The 3D DDA through the occupancy (direct lights, torch shadows): what reaches q from p. */
   static final String TRACE_GLSL = String.join("\n",
      "uniform float uFence;", // light through a see-through fence
      "uniform usampler3D occ4;", // the coarse occupancy: 4 x 4 squares a level, 1 where anything stops or thins light inside a level
      "uniform int uSkip;", // 1: empty blocks crossed in one step
      "bool coarseEmpty(ivec3 c) {",
      "   int lz = c.z - uOcc.x;",
      "   if (lz < 0 || lz > 15) return true;",
      "   return texelFetch(occ4, ivec3((c.x >> 2) & 63, (c.y >> 2) & 63, lz), 0).r == 0u;",
      "}",
      // what an edge lets through at height f of its level: a wall nothing, a window's pane between sill and head, a doorway
      // up to its head
      // o: the occupancy word holding the edge, n: the N edge (else W)
      "float edgeT(uint o, bool n, float f) {",
      "   uint e = (n ? o >> 1 : o >> 3) & 3u;",
      "   if (e == 1u && (o & (n ? 16384u : 32768u)) != 0u && (o & (n ? 128u : 8192u)) != 0u) return f < 0.65 ? 0.0 : 1.0;", // a solid fence panel (GodRays.solidFence): up to its top
      "   if ((o & (n ? 16384u : 32768u)) != 0u && e < 2u) return uFence;", // a see-through fence (bars, chain link)
      "   if (e == 0u && (o & (n ? 128u : 8192u)) != 0u) return f < 0.33 ? 0.0 : 1.0;", // a low fence on an open edge
      "   if (e == 1u) return 0.0;",
      "   if (e == 2u) return f < 0.30 || f > 0.84 ? 0.0 : uOpt.x;",
      "   if (e == 3u) return f > 0.80 ? 0.0 : 1.0;",
      "   return 1.0;",
      "}",
      // the transmittance from p to q (x, y squares, z levels): an exact 3D DDA through the squares and levels
      "float trace(vec3 p, vec3 q) {",
      "   vec3 d = q - p;",
      "   float lenXY = length(d.xy);",
      "   ivec3 c = ivec3(floor(p));",
      "   ivec3 st = ivec3(d.x >= 0.0 ? 1 : -1, d.y >= 0.0 ? 1 : -1, d.z >= 0.0 ? 1 : -1);",
      "   vec3 ad = abs(d);",
      "   vec3 tDelta = vec3(ad.x > 1e-6 ? 1.0 / ad.x : 1e30, ad.y > 1e-6 ? 1.0 / ad.y : 1e30, ad.z > 1e-6 ? 1.0 / ad.z : 1e30);",
      "   vec3 fr = p - vec3(c);",
      "   vec3 tMax = vec3(st.x > 0 ? 1.0 - fr.x : fr.x, st.y > 0 ? 1.0 - fr.y : fr.y, st.z > 0 ? 1.0 - fr.z : fr.z) * tDelta;",
      "   float T = 1.0, t = 0.0;",
      "   float ownCrown = ((occAt(c.x, c.y, c.z) >> 8) & 15u) != 0u ? 2.0 : 0.75;", // a point in a crown: the leaves of its own tree up to 2 squares away
      "   for (int i = 0; i < 160; i++) {",
      // empty-space skipping: a 4 x 4 block with nothing in it at this level is crossed in one step (its exit edge is the next
      // block's, tested; a level crossed inside it is not skipped)
      "      if (uSkip != 0 && coarseEmpty(c)) {",
      "         int bx0 = c.x & ~3, by0 = c.y & ~3;",
      "         float txe = d.x > 0.0 ? (float(bx0 + 4) - p.x) / d.x : d.x < 0.0 ? (float(bx0) - p.x) / d.x : 1e30;",
      "         float tye = d.y > 0.0 ? (float(by0 + 4) - p.y) / d.y : d.y < 0.0 ? (float(by0) - p.y) / d.y : 1e30;",
      "         float te = min(txe, tye);",
      "         if (te >= 1.0 && tMax.z >= 1.0) return T;", // the rest of the ray stays in it
      "         if (te < tMax.z) {",
      "            vec3 pe = p + d * te;",
      "            t = te;",
      "            if (txe <= tye) {",
      "               int nx = d.x > 0.0 ? bx0 + 4 : bx0 - 1, ny = clamp(int(floor(pe.y)), by0, by0 + 3);",
      "               if (d.x > 0.0) T *= edgeT(occAt(nx, ny, c.z), false, pe.z - float(c.z));",
      "               c.xy = ivec2(nx, ny);",
      "            } else {",
      "               int ny = d.y > 0.0 ? by0 + 4 : by0 - 1, nx = clamp(int(floor(pe.x)), bx0, bx0 + 3);",
      "               if (d.y > 0.0) T *= edgeT(occAt(nx, ny, c.z), true, pe.z - float(c.z));",
      "               c.xy = ivec2(nx, ny);",
      "            }",
      "            tMax.x = d.x > 0.0 ? (float(c.x + 1) - p.x) / d.x : d.x < 0.0 ? (float(c.x) - p.x) / d.x : 1e30;",
      "            tMax.y = d.y > 0.0 ? (float(c.y + 1) - p.y) / d.y : d.y < 0.0 ? (float(c.y) - p.y) / d.y : 1e30;",
      "            if (T < 0.004) return 0.0;",
      "            continue;",
      "         }",
      "      }",
      "      float tn = min(min(tMax.x, tMax.y), min(tMax.z, 1.0));",
      "      uint o = occAt(c.x, c.y, c.z);",
      "      uint cr = (o >> 8) & 15u;",
      // the crown a ray starts or ends in is the lit leaf itself or the lamp's own tree: only the leaves between count
      "      if (cr != 0u) {",
      "         float a0 = max(t * lenXY, ownCrown), a1 = min(tn * lenXY, lenXY - 0.75);",
      "         if (a1 > a0) T *= exp(-uOpt.y * float(cr) / 15.0 * (a1 - a0));",
      "      }",
      "      if (tn >= 1.0) break;",
      "      t = tn;",
      "      if (tMax.x <= tMax.y && tMax.x <= tMax.z) {",
      "         T *= edgeT(st.x > 0 ? occAt(c.x + 1, c.y, c.z) : o, false, p.z + d.z * t - float(c.z));",
      "         c.x += st.x;",
      "         tMax.x += tDelta.x;",
      "      } else if (tMax.y <= tMax.z) {",
      "         T *= edgeT(st.y > 0 ? occAt(c.x, c.y + 1, c.z) : o, true, p.z + d.z * t - float(c.z));",
      "         c.y += st.y;",
      "         tMax.y += tDelta.y;",
      "      } else {",
      "         if ((occAt(c.x, c.y, st.z > 0 ? c.z + 1 : c.z) & 65u) != 0u) return 0.0;", // a floor or a roof crossed
      "         c.z += st.z;",
      "         tMax.z += tDelta.z;",
      "      }",
      "      if (T < 0.004) return 0.0;",
      "   }",
      "   return T;",
      "}",
      // the per-light visibility maps' march: from the lamp outwards, the distance (in the segment's 3D squares) to the first
      // full stop, to the first partial one, and what the partial ones let through before the stop
      "vec3 traceHit(vec3 p, vec3 q) {",
      "   vec3 d = q - p;",
      "   float len = length(d * vec3(1.0, 1.0, uOpt.w)), lenXY = length(d.xy);",
      "   ivec3 c = ivec3(floor(p));",
      "   ivec3 st = ivec3(d.x >= 0.0 ? 1 : -1, d.y >= 0.0 ? 1 : -1, d.z >= 0.0 ? 1 : -1);",
      "   vec3 ad = abs(d);",
      "   vec3 tDelta = vec3(ad.x > 1e-6 ? 1.0 / ad.x : 1e30, ad.y > 1e-6 ? 1.0 / ad.y : 1e30, ad.z > 1e-6 ? 1.0 / ad.z : 1e30);",
      "   vec3 fr = p - vec3(c);",
      "   vec3 tMax = vec3(st.x > 0 ? 1.0 - fr.x : fr.x, st.y > 0 ? 1.0 - fr.y : fr.y, st.z > 0 ? 1.0 - fr.z : fr.z) * tDelta;",
      "   float T = 1.0, t = 0.0, dPart = 1e9;",
      "   for (int i = 0; i < 200; i++) {",
      "      float tn = min(min(tMax.x, tMax.y), min(tMax.z, 1.0));",
      "      uint o = occAt(c.x, c.y, c.z);",
      "      uint cr = (o >> 8) & 15u;",
      "      if (cr != 0u && tn * lenXY > 0.75) { T *= exp(-uOpt.y * float(cr) / 15.0 * (tn - max(t, 0.75 / max(lenXY, 1e-4))) * lenXY); dPart = min(dPart, t * len); }",
      "      if (tn >= 1.0) break;",
      "      t = tn;",
      "      float k = 1.0;",
      "      if (tMax.x <= tMax.y && tMax.x <= tMax.z) {",
      "         k = edgeT(st.x > 0 ? occAt(c.x + 1, c.y, c.z) : o, false, p.z + d.z * t - float(c.z));",
      "         c.x += st.x; tMax.x += tDelta.x;",
      "      } else if (tMax.y <= tMax.z) {",
      "         k = edgeT(st.y > 0 ? occAt(c.x, c.y + 1, c.z) : o, true, p.z + d.z * t - float(c.z));",
      "         c.y += st.y; tMax.y += tDelta.y;",
      "      } else {",
      "         if ((occAt(c.x, c.y, st.z > 0 ? c.z + 1 : c.z) & 65u) != 0u) k = 0.0;",
      "         c.z += st.z; tMax.z += tDelta.z;",
      "      }",
      "      if (k <= 0.0 || T * k < 0.004) return vec3(t * len, min(dPart, t * len), T);",
      "      if (k < 1.0) { T *= k; dPart = min(dPart, t * len); }",
      "   }",
      "   return vec3(1e4, dPart, T);",
      "}",
      "");

   /** Per light map texel (a direction from the lamp's head, octahedral): its march (traceHit). */
   static final String LMAP_CS = String.join("\n",
      "layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;",
      "layout(std430, binding = 2) readonly buffer Lights { vec4 lights[]; };",
      "layout(std430, binding = 3) readonly buffer MapJobs { ivec2 mjobs[]; };", // light index, layer
      "layout(rgba16f, binding = 0) uniform writeonly image2DArray outM;",
      "void main() {",
      "   ivec2 g = ivec2(gl_GlobalInvocationID.xy);",
      "   ivec2 job = mjobs[gl_GlobalInvocationID.z];",
      "   vec4 a = lights[job.x * 3];",
      "   vec3 dir = octDec((vec2(g) + 0.5) / float(LM_SIZE) * 2.0 - 1.0);", // in squares (z scaled)
      "   vec3 q = a.xyz + vec3(dir.xy, dir.z / uOpt.w) * (a.w + 1.0);",
      "   imageStore(outM, ivec3(g, job.y), vec4(traceHit(a.xyz, q), 1.0));",
      "}");

   /** Per brick (a workgroup): each texel's light from the brick's lamps, traced through the occupancy. */
   static final String DIRECT_CS = String.join("\n",
      "layout(local_size_x = BT, local_size_y = BT, local_size_z = RZ) in;",
      "layout(std430, binding = 0) readonly buffer Jobs { ivec4 jobs[]; };", // x, y: the brick's first texel (world texel coords), z: level, w: first light | count << 20 (-1: clear)
      "layout(std430, binding = 1) readonly buffer Idx { int lidx[]; };",
      "layout(std430, binding = 2) readonly buffer Lights { vec4 lights[]; };", // 3 a light: (x, y, head z, reach | room hash), (r, g, b, kind), (room rect)
      "layout(rgba16f, binding = 0) uniform writeonly image3D outA;",
      "layout(rgba8, binding = 1) uniform writeonly image3D outB;",
      "uniform sampler2DArray uLmap;", // the lights' visibility maps (voxelLightDirect=maps)
      "uniform int uMaps;",
      "void main() {",
      "   ivec4 j = jobs[gl_WorkGroupID.x];",
      "   ivec3 l = ivec3(gl_LocalInvocationID);",
      "   ivec2 g = j.xy + l.xy;",
      "   ivec3 si = ivec3(g & (TX - 1), (j.z - uWin.z) * RZ + l.z);",
      "   if (j.w == -1) {",
      "      imageStore(outA, si, vec4(0.0));",
      "      imageStore(outB, si, vec4(0.5, 0.5, 1.0, 0.0));",
      "      return;",
      "   }",
      "   vec3 p = vec3((vec2(g) + 0.5) / float(R), float(j.z) + (float(l.z) + 0.5) / float(RZ));",
      "   uint own = occAt(int(floor(p.x)), int(floor(p.y)), j.z);",
      "   int room = (own & 32u) != 0u ? int(own >> 16) : -1;",
      "   int first = j.w & 0xFFFFF, cnt = j.w >> 20;",
      "   vec3 acc = vec3(0.0), keep = vec3(1.0), dsum = vec3(0.0);",
      "   float lsum = 0.0;",
      "   for (int k = 0; k < cnt; k++) {",
      "      int li = lidx[first + k];",
      "      vec4 a = lights[li * 3], c = lights[li * 3 + 1], r = lights[li * 3 + 2];",
      "      vec3 I;",
      "      vec3 dir = vec3(0.0);",
      "      if (c.w > 0.5) {", // a room light: its room's squares in its rectangle, flat (the native's), no direction
      "         if (int(floor(a.z)) != j.z) continue;",
      "         if (room == floatBitsToInt(a.w)) {",
      "            if (p.x < r.x || p.x >= r.z || p.y < r.y || p.y >= r.w) continue;",
      "            I = c.rgb;",
      "         } else {",
      // out of the room through its windows and doorways (IsoRoomLight.shineOut: up to 5 squares, (1 - d / 5)^2 x 2): from the
      // nearest of its squares, at the height of a window's middle, through the occupancy (a wall stops it, glass thins it)
      "            vec2 qr = clamp(p.xy, r.xy + 0.5, r.zw - 0.5);",
      "            float dd = length(p.xy - qr);",
      "            if (dd >= 5.0) continue;",
      "            float del = 1.0 - dd / 5.0;",
      "            float hz = float(j.z) + 0.55;",
      // towards the nearest of its squares and up to two either way along its side: a window spreads its light (the native
      // lights the whole strip along a wall with windows in it, not a column under each)
      "            float v = trace(vec3(p.xy, hz), vec3(qr, hz));",
      "            for (int s9 = 0; s9 < 8 && v < 0.99; s9++) {",
      "               float k9 = float((s9 & 3) < 2 ? (s9 & 3) - 2 : (s9 & 3) - 1);", // -2 -1 1 2, on x (s9 0..3) then on y
      "               vec2 qk = clamp(qr + (s9 < 4 ? vec2(k9, 0.0) : vec2(0.0, k9)), r.xy + 0.5, r.zw - 0.5);",
      "               if (qk != qr) v = max(v, trace(vec3(p.xy, hz), vec3(qk, hz)) * (1.0 - 0.15 * abs(k9)));",
      "            }",
      "            if (v <= 0.0) continue;",
      // no direction, as the room's own light: the faces beside the window take it alike (a direction into the room
      // turned the summed direction of every light there into the wall: a lamp-lit facade went dark round lit windows)
      "            I = min(c.rgb * (2.0 * del * del), vec3(1.0)) * v;",
      "         }",
      "      } else {",
      "         vec3 q = a.xyz;",
      "         vec3 dv = (q - p) * vec3(1.0, 1.0, uOpt.w);",
      "         float dist = length(dv);",
      "         float fall = clamp(1.0 - dist / a.w, 0.0, 1.0);",
      "         if (fall <= 0.0) continue;",
      "         float v;",
      "         if (uMaps != 0 && r.y > 0.5) {", // the light's visibility map, 2 x 2 percentage-closer
      "            vec3 dv3 = (p - q) * vec3(1.0, 1.0, uOpt.w);",
      "            vec2 mu = (octEnc(dv3 / max(dist, 1e-4)) * 0.5 + 0.5) * float(LM_SIZE) - 0.5;",
      "            ivec2 mi = ivec2(floor(mu));",
      "            vec2 mf = mu - vec2(mi);",
      "            v = 0.0;",
      "            for (int s4 = 0; s4 < 4; s4++) {",
      "               ivec2 o2 = ivec2(s4 & 1, s4 >> 1);",
      "               vec4 m = texelFetch(uLmap, ivec3(clamp(mi + o2, ivec2(0), ivec2(LM_SIZE - 1)), int(r.x)), 0);",
      "               float vv = dist < m.x - 0.2 ? (dist > m.y ? m.z : 1.0) : 0.0;",
      "               v += vv * (o2.x == 1 ? mf.x : 1.0 - mf.x) * (o2.y == 1 ? mf.y : 1.0 - mf.y);",
      "            }",
      "         } else {",
      "            v = trace(p, q);",
      "         }",
      "         if (v <= 0.0) continue;",
      "         I = c.rgb * (fall * v);",
      "         dir = dv / max(dist, 1e-4);",
      "      }",
      "      if (uOpt.z < 0.5) keep *= 1.0 - clamp(I, 0.0, 1.0);",
      "      else if (uOpt.z < 1.5) acc = max(acc, I);",
      "      else acc += I;",
      "      float lum = dot(I, vec3(0.3, 0.5, 0.2));",
      "      dsum += dir * lum;", // a room light adds to the sum, not the direction: it lights every face alike
      "      lsum += lum;",
      "   }",
      "   if (uOpt.z < 0.5) acc = 1.0 - keep;",
      "   else if (uOpt.z > 2.5) acc /= max(1.0, max(acc.r, max(acc.g, acc.b)));", // hue: summed, back under 1 keeping its colour
      "   float w = lsum > 1e-5 ? length(dsum) / lsum : 0.0;",
      "   vec3 dn = length(dsum) > 1e-6 ? normalize(dsum) : vec3(0.0, 0.0, 1.0);",
      "   imageStore(outA, si, vec4(acc, 1.0));",
      "   imageStore(outB, si, vec4(dn * 0.5 + 0.5, clamp(w, 0.0, 1.0)));",
      "}");

   /** The coarse occupancy: per 4 x 4 squares and level, whether anything stops or thins light travelling inside the level. */
   static final String COARSE_CS = String.join("\n",
      "layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;",
      "layout(r8ui, binding = 0) uniform writeonly uimage3D outO4;",
      "void main() {",
      "   ivec3 g = ivec3(gl_GlobalInvocationID);",
      "   uint any = 0u;",
      "   for (int k = 0; k < 16; k++) {",
      "      uint o = texelFetch(occ, ivec3(g.x * 4 + (k & 3), g.y * 4 + (k >> 2), g.z), 0).r;",
      "      any |= o & (30u | 128u | 3840u | 8192u | 16384u | 32768u);", // edges, low fences, crowns, see-through fences
      "   }",
      "   imageStore(outO4, g, uvec4(any != 0u ? 1u : 0u));",
      "}");

   /** Per torch map texel (4 a square, two heights in the torch's level): what reaches it from the torch's lens. */
   static final String TORCH_CS = String.join("\n",
      "layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;",
      "uniform vec4 uT;", // the lens: x, y (world squares), height (levels), reach
      "uniform vec4 uTO;", // the map's origin x, y (world squares), its level, the slot
      "layout(r8, binding = 0) uniform writeonly image2DArray outT;",
      "void main() {",
      "   ivec3 g = ivec3(gl_GlobalInvocationID);",
      "   vec3 p = vec3(uTO.xy + (vec2(g.xy) + 0.5) * 0.25, uTO.z + (g.z == 0 ? 0.05 : 0.55));", // just over the floor, half a level up
      "   float v = 1.0;",
      "   if (length(p.xy - uT.xy) < uT.w + 1.0) v = trace(p, uT.xyz);",
      "   imageStore(outT, ivec3(g.xy, int(uTO.w) * 2 + g.z), vec4(v));",
      "}");

   /** The sky's 17 directions (the zenith, 8 azimuths at 20 and 50 degrees up) and their cosine weights (cones, cascades). */
   static final String SKYDIR_GLSL = String.join("\n",
      "vec3 skyDir(int k) {",
      "   float el = k == 0 ? 1.5707963 : k <= 8 ? 0.349 : 0.873;",
      "   float az = float(k) * 0.7853982;",
      "   return vec3(cos(el) * cos(az), cos(el) * sin(az), sin(el));",
      "}",
      "");

   /**
    * Radiance cascades for the sky (voxelLightSkyMethod=cascades), cascade 1: per probe (2 x 2 squares a level) the sky's 17
    * directions traced from 3 to 14 squares out (5 RGBA8 texels a probe); cascade 0 (the inject) traces each cell's first 3
    * squares and takes the far part from the probe.
    */
   static final String CASCADE_CS = String.join("\n",
      "layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;",
      "layout(rgba8, binding = 0) uniform writeonly image3D outP;", // (N / 2) x (N / 2) x (LZ * 5)
      "void main() {",
      "   ivec3 g = ivec3(gl_GlobalInvocationID);",
      "   if (g.x >= (uReg.z + 1) / 2 + 1 || g.y >= (uReg.w + 1) / 2 + 1) return;",
      "   int px = (uReg.x >> 1) + g.x, py = (uReg.y >> 1) + g.y, L = uWin.z + g.z;",
      "   vec3 c0 = vec3(float(px * 2) + 1.0, float(py * 2) + 1.0, float(L) + 0.45);",
      "   float v[20];",
      "   for (int k = 0; k < 20; k++) v[k] = 1.0;",
      "   for (int k = 0; k < 17; k++) {",
      "      vec3 dir = skyDir(k);",
      "      vec3 a = c0 + vec3(dir.xy * 3.0, dir.z * 3.0 / uOpt.w), q = c0 + vec3(dir.xy * 14.0, dir.z * 14.0 / uOpt.w);",
      "      v[k] = trace(a, q);",
      "   }",
      "   ivec3 s = ivec3(px & (N / 2 - 1), py & (N / 2 - 1), g.z * 5);",
      "   for (int t = 0; t < 5; t++) imageStore(outP, s + ivec3(0, 0, t), vec4(v[t * 4], v[t * 4 + 1], v[t * 4 + 2], v[t * 4 + 3]));",
      "}");

   /** Per flood cell: the sources, the open sky above it and the traced light x albedo (the bounce). */
   static final String INJECT_CS = String.join("\n",
      "layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;",
      "uniform int uSkyCones;",
      "uniform sampler3D uProbe;", // cascades: cascade 1's probes
      "float probeVis(int x, int y, int lz, int k) {", // the nearest probe's far transmittance for direction k
      "   ivec3 p = ivec3((x >> 1) & (N / 2 - 1), (y >> 1) & (N / 2 - 1), lz * 5 + k / 4);",
      "   vec4 t = texelFetch(uProbe, p, 0);",
      "   int c = k & 3;",
      "   return c == 0 ? t.x : c == 1 ? t.y : c == 2 ? t.z : t.w;",
      "}",
      "uniform sampler3D uA;",
      "layout(rgba16f, binding = 0) uniform writeonly image3D outS;",
      "layout(r8ui, binding = 1) uniform writeonly uimage2DArray outE;",
      // a wall or a window's frame: the composite never filters across it (a see-through fence is no room boundary)
      "bool blocks(uint o, bool n) { uint e = (n ? o >> 1 : o >> 3) & 3u; return (e == 1u || e == 2u) && (o & (n ? 16384u : 32768u)) == 0u; }",
      "void main() {",
      "   ivec3 g = ivec3(gl_GlobalInvocationID);",
      "   if (g.x >= uReg.z || g.y >= uReg.w) return;",
      "   int x = uReg.x + g.x, y = uReg.y + g.y, L = uWin.z + g.z;",
      "   ivec3 s = ivec3(x & (N - 1), y & (N - 1), g.z);",
      "   uint o = occAt(x, y, L);",
      "   float sky = 0.0;",
      "   if (uSkyCones == 3 && uOpt2.w > 0.5) {", // cascades: the first 3 squares here, the rest from the probe
      "      vec3 c0 = vec3(float(x) + 0.5, float(y) + 0.5, float(L) + 0.45);",
      "      float wsum = 0.0;",
      "      for (int k = 0; k < 17; k++) {",
      "         vec3 dir = skyDir(k);",
      "         float w = dir.z;",
      "         float t0 = trace(c0, c0 + vec3(dir.xy * 3.0, dir.z * 3.0 / uOpt.w));",
      "         if (t0 > 0.0) t0 *= probeVis(x, y, g.z, k);",
      "         sky += w * t0;",
      "         wsum += w;",
      "      }",
      "      sky /= wsum;",
      "   } else if (uSkyCones != 0 && uOpt2.w > 0.5) {",
      // cones (voxelLightSkyMethod=cones): 17 rays from the cell's middle (the zenith, 8 azimuths at 20 and 50 degrees up)
      // through the occupancy, cosine-weighted: the sky a room sees through its windows comes in from where they face
      "      vec3 c0 = vec3(float(x) + 0.5, float(y) + 0.5, float(L) + 0.45);",
      "      float wsum = 0.0;",
      "      for (int k = 0; k < (uSkyCones == 2 ? 1 : 17); k++) {", // dev: 2 = the zenith ray alone
      "         vec3 dir = skyDir(k);",
      "         float len = 14.0;",
      "         vec3 q = c0 + vec3(dir.xy * len, dir.z * len / uOpt.w);",
      "         float w = dir.z;",
      "         sky += w * trace(c0, q);",
      "         wsum += w;",
      "      }",
      "      sky /= wsum;",
      "   } else if ((o & 32u) == 0u && uOpt2.w > 0.5) {", // not a room: the sky through what is above
      "      sky = 1.0 - 0.7 * float((o >> 8) & 15u) / 15.0;",
      "      for (int k = L + 1; k < uOcc.y; k++) {",
      "         uint a = occAt(x, y, k);",
      "         if ((a & 65u) != 0u) { sky = 0.0; break; }",
      "         sky *= 1.0 - 0.7 * float((a >> 8) & 15u) / 15.0;",
      "      }",
      "   }",
      // the traced light at the square's floor (its bottom texels, the four across)
      "   vec4 A = textureLod(uA, vec3((vec2(x, y) + 0.5) / float(N), (float(s.z * RZ) + 0.5) / float(TZ)), 0.0);",
      "   imageStore(outS, s, vec4(A.rgb * A.a * uOpt2.z, sky));",
      "   uint e = (blocks(o, false) ? 1u : 0u) | (blocks(o, true) ? 2u : 0u) | (blocks(occAt(x + 1, y, L), false) ? 4u : 0u) | (blocks(occAt(x, y + 1, L), true) ? 8u : 0u);",
      "   imageStore(outE, s, uvec4(e));",
      "}");

   /**
    * The directional bounce (voxelLightBounceMethod=cube, a light propagation volume of six directional fluxes, discrete
    * ordinates): per cell the bounce's luminance flowing +x, -x, +y, -y (D0) and +z, -z (D1). Each iteration a flux comes on from
    * the cell behind (0.6 of the same direction, 0.1 of each side one: the spread) through the opening between, decayed, plus
    * the cell's own emission (its floor's traced light x albedo: half up, an eighth each way across).
    */
   static final String CUBE_CS = String.join("\n",
      "layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;",
      "layout(rgba16f, binding = 0) uniform readonly image3D inS;",
      "layout(rgba16f, binding = 1) uniform readonly image3D inD0;",
      "layout(rgba16f, binding = 2) uniform readonly image3D inD1;",
      "layout(rgba16f, binding = 3) uniform writeonly image3D outD0;",
      "layout(rgba16f, binding = 4) uniform writeonly image3D outD1;",
      "float edgeC(uint o, bool n) { uint e = (n ? o >> 1 : o >> 3) & 3u; if ((o & (n ? 16384u : 32768u)) != 0u && e < 2u) return 0.8; return e == 1u ? 0.0 : e == 2u ? uOpt.x : 1.0; }",
      "void flux(int x, int y, int L, int sz, out vec4 d0, out vec4 d1) {", // a cell's fluxes (zero outside the window / levels)
      "   d0 = vec4(0.0); d1 = vec4(0.0);",
      "   if (x < uWin.x || y < uWin.y || x >= uWin.x + N || y >= uWin.y + N || sz < 0 || sz >= LZ) return;",
      "   ivec3 c = ivec3(x & (N - 1), y & (N - 1), sz);",
      "   d0 = imageLoad(inD0, c); d1 = imageLoad(inD1, c);",
      "}",
      "float spread(vec4 d0, vec4 d1, float same, float sides) { return 0.6 * same + 0.1 * sides; }",
      "void main() {",
      "   ivec3 g = ivec3(gl_GlobalInvocationID);",
      "   if (g.x >= uReg.z || g.y >= uReg.w) return;",
      "   int x = uReg.x + g.x, y = uReg.y + g.y, L = uWin.z + g.z;",
      "   ivec3 s = ivec3(x & (N - 1), y & (N - 1), g.z);",
      "   uint o = occAt(x, y, L);",
      "   vec4 S = imageLoad(inS, s);",
      "   float em = dot(S.rgb, vec3(0.3, 0.5, 0.2));",
      "   vec4 a0, a1;",
      "   float k = uOpt2.y;", // decay a square
      "   vec4 o0 = vec4(em * 0.125), o1 = vec4(em * 0.5, 0.0, 0.0, 0.0);",
      // +x: from the cell to the west through this cell's W edge
      "   float t = edgeC(o, false);",
      "   if (t > 0.0) { flux(x - 1, y, L, g.z, a0, a1); o0.x += k * t * (0.6 * a0.x + 0.1 * (a0.z + a0.w + a1.x + a1.y)); }",
      // -x: from the cell to the east through its W edge
      "   t = edgeC(occAt(x + 1, y, L), false);",
      "   if (t > 0.0) { flux(x + 1, y, L, g.z, a0, a1); o0.y += k * t * (0.6 * a0.y + 0.1 * (a0.z + a0.w + a1.x + a1.y)); }",
      // +y: from the north through this cell's N edge; -y: from the south through its N edge
      "   t = edgeC(o, true);",
      "   if (t > 0.0) { flux(x, y - 1, L, g.z, a0, a1); o0.z += k * t * (0.6 * a0.z + 0.1 * (a0.x + a0.y + a1.x + a1.y)); }",
      "   t = edgeC(occAt(x, y + 1, L), true);",
      "   if (t > 0.0) { flux(x, y + 1, L, g.z, a0, a1); o0.w += k * t * (0.6 * a0.w + 0.1 * (a0.x + a0.y + a1.x + a1.y)); }",
      // +z: from below through this cell's floor; -z: from above through the floor of the cell above
      "   if ((o & 65u) == 0u) { flux(x, y, L - 1, g.z - 1, a0, a1); o1.x += k * (0.6 * a1.x + 0.1 * (a0.x + a0.y + a0.z + a0.w)); }",
      "   if ((occAt(x, y, L + 1) & 65u) == 0u) { flux(x, y, L + 1, g.z + 1, a0, a1); o1.y += k * (0.6 * a1.y + 0.1 * (a0.x + a0.y + a0.z + a0.w)); }",
      "   imageStore(outD0, s, o0);",
      "   imageStore(outD1, s, o1);",
      "}");

   /** One flood iteration: a cell keeps its source or the brightest neighbour through an opening, decayed a square. */
   static final String FLOOD_CS = String.join("\n",
      "layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;",
      "layout(rgba16f, binding = 0) uniform readonly image3D inS;",
      "layout(rgba16f, binding = 1) uniform readonly image3D inC;",
      "layout(rgba16f, binding = 2) uniform writeonly image3D outC;",
      // the flood goes through a window's pane, a doorway and a see-through fence
      "float edgeF(uint o, bool n) { uint e = (n ? o >> 1 : o >> 3) & 3u; if ((o & (n ? 16384u : 32768u)) != 0u && e < 2u) return 0.8; return e == 1u ? 0.0 : e == 2u ? uOpt.x : 1.0; }",
      "void main() {",
      "   ivec3 g = ivec3(gl_GlobalInvocationID);",
      "   if (g.x >= uReg.z || g.y >= uReg.w) return;",
      "   int x = uReg.x + g.x, y = uReg.y + g.y, L = uWin.z + g.z;",
      "   ivec3 s = ivec3(x & (N - 1), y & (N - 1), g.z);",
      "   uint o = occAt(x, y, L);",
      "   vec4 m = vec4(uOpt2.y, uOpt2.y, uOpt2.y, uOpt2.x);",
      "   vec4 best = vec4(0.0);",
      "   ivec3 msk = ivec3(N - 1, N - 1, 0);",
      "   if (x + 1 < uWin.x + N) { float t = edgeF(occAt(x + 1, y, L), false); if (t > 0.0) best = max(best, imageLoad(inC, ivec3((s.x + 1) & (N - 1), s.y, s.z)) * t); }",
      "   if (x > uWin.x) { float t = edgeF(o, false); if (t > 0.0) best = max(best, imageLoad(inC, ivec3((s.x - 1) & (N - 1), s.y, s.z)) * t); }",
      "   if (y + 1 < uWin.y + N) { float t = edgeF(occAt(x, y + 1, L), true); if (t > 0.0) best = max(best, imageLoad(inC, ivec3(s.x, (s.y + 1) & (N - 1), s.z)) * t); }",
      "   if (y > uWin.y) { float t = edgeF(o, true); if (t > 0.0) best = max(best, imageLoad(inC, ivec3(s.x, (s.y - 1) & (N - 1), s.z)) * t); }",
      "   if (s.z + 1 < LZ && (occAt(x, y, L + 1) & 65u) == 0u) best = max(best, imageLoad(inC, s + ivec3(0, 0, 1)));",
      "   if (s.z > 0 && (o & 1u) == 0u) best = max(best, imageLoad(inC, s - ivec3(0, 0, 1)));",
      "   imageStore(outC, s, max(imageLoad(inS, s), best * m));",
      "}");

   /**
    * Per texel: the lamps (A), their direction (B) and the flood (C: bounce, sky) upsampled from the square cells without
    * crossing a blocked edge, into the two RGBA8 layers the composite reads (one bilinear tap each on a floor).
    */
   static final String RESOLVE_CS = String.join("\n",
      "layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;",
      "uniform sampler3D uA;",
      "uniform sampler3D uC;",
      "uniform sampler3D uB;",
      "layout(r8ui, binding = 1) uniform readonly uimage2DArray inE;",
      "layout(rgba8, binding = 2) uniform writeonly image2DArray outT1;",
      "layout(rgba8, binding = 3) uniform writeonly image2DArray outT2;",
      "uniform int uCube;", // the directional bounce: the cache adds it from the fluxes
      "vec2 oct(vec3 v) {",
      "   v /= abs(v.x) + abs(v.y) + abs(v.z);",
      "   return v.z >= 0.0 ? v.xy : (1.0 - abs(v.yx)) * vec2(v.x >= 0.0 ? 1.0 : -1.0, v.y >= 0.0 ? 1.0 : -1.0);",
      "}",
      "void main() {",
      "   ivec3 g = ivec3(gl_GlobalInvocationID);",
      "   if (g.x >= uReg.z * R || g.y >= uReg.w * R) return;",
      "   int wtx = uReg.x * R + g.x, wty = uReg.y * R + g.y;", // the texel's world column
      "   ivec3 t = ivec3(wtx & (TX - 1), wty & (TX - 1), g.z);",
      "   vec4 A = texelFetch(uA, t, 0), B = texelFetch(uB, t, 0);",
      "   int sx = wtx / R, sy = wty / R, cz = t.z / RZ;",
      "   vec2 f = (vec2(wtx - sx * R, wty - sy * R) + 0.5) / float(R);", // where in its square
      "   ivec2 c = ivec2(sx, sy) & (N - 1);",
      "   uint e = imageLoad(inE, ivec3(c, cz)).r;",
      "   int dx = f.x < 0.5 ? -1 : 1, dy = f.y < 0.5 ? -1 : 1;",
      "   bool okx = (dx < 0 ? (e & 1u) == 0u && sx > uWin.x : (e & 4u) == 0u && sx + 1 < uWin.x + N);",
      "   bool oky = (dy < 0 ? (e & 2u) == 0u && sy > uWin.y : (e & 8u) == 0u && sy + 1 < uWin.y + N);",
      "   vec4 c00 = texelFetch(uC, ivec3(c, cz), 0);",
      "   vec4 c10 = okx ? texelFetch(uC, ivec3((c + ivec2(dx, 0)) & (N - 1), cz), 0) : c00;",
      "   vec4 c01 = oky ? texelFetch(uC, ivec3((c + ivec2(0, dy)) & (N - 1), cz), 0) : c00;",
      "   vec4 c11 = okx && oky ? texelFetch(uC, ivec3((c + ivec2(dx, dy)) & (N - 1), cz), 0) : okx ? c10 : c01;",
      "   vec2 w = abs(f - 0.5);",
      "   vec4 C = mix(mix(c00, c10, w.x), mix(c01, c11, w.x), w.y);",
      "   vec3 d = B.xyz * 2.0 - 1.0;",
      "   vec2 o = length(d) > 1e-3 ? oct(d) : vec2(0.0);",
      // a: validity and the sky in one (0: not traced, 0.5 + sky / 2 traced): a floor needs this layer only
      "   imageStore(outT1, t, vec4(clamp(A.rgb * A.a + (uCube != 0 ? vec3(0.0) : C.rgb), 0.0, 1.0), A.a > 0.5 ? 0.5 + 0.5 * clamp(C.a, 0.0, 1.0) : 0.0));",
      "   imageStore(outT2, t, vec4(o * 0.5 + 0.5, B.w, A.a));",
      "}");

   /**
    * The surface cache: one chunk texture's light layer (a texel per 8 x 8 of its pixels or so). Each texel's surface point
    * from the chunk texture's own depth (the texel's middle, or the nearest covered sample of its block) through the
    * texture's world mapping, its normal from the depth beside it, then the resolved volume there as the composite would read
    * it per pixel (never across a wall, both layers of the level, the facing): rgb the light, a the sky / validity.
    */
   static final String RELIGHT_CS = String.join("\n",
      "layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;",
      "uniform sampler2D uDepth;", // the chunk texture's depth
      "uniform sampler2DArray uT1;",
      "uniform sampler2DArray uT2;",
      "uniform usampler2DArray uE;",
      "uniform vec4 uAff;", // A = x - y = uAff.x u + uAff.y v + uAff0.x, B = x + y - 6z = uAff.z u + uAff.w v + uAff0.y (relative to uRef)
      "uniform vec4 uAff0;",
      "uniform vec4 uAff2;", // C = x + y + 2z = uAff2.x - depth * uAff2.y (relative to uRef); z: the layer; w: wrap
      "uniform ivec4 uRef;", // x, y, z: A, B, C at the reference (integers); w: facing on
      "uniform vec4 uLev;", // x, y: the texture's levels; z: the least sky a room keeps; w: -
      "uniform int uCube;", // the directional bounce (voxelLightBounceMethod=cube)
      "uniform float uCubeGain;",
      "uniform sampler3D uD0;",
      "uniform sampler3D uD1;",
      "uniform sampler3D uCf;", // the flood's bounce colour
      "layout(rgba16, binding = 0) uniform writeonly image2DArray outL;",
      "const float LEVEL = 2.4494897;",
      "vec3 world(vec2 uv, float d) {", // relative to the reference square
      "   float A = dot(uAff.xy, uv) + uAff0.x, B = dot(uAff.zw, uv) + uAff0.y, C = uAff2.x - d * uAff2.y;",
      "   float z = (C - B + float(uRef.z - uRef.y)) * 0.125;",
      "   float S = C + float(uRef.z) - 2.0 * z;",
      "   return vec3((S + A + float(uRef.x)) * 0.5, (S - A - float(uRef.x)) * 0.5, z);",
      "}",
      "vec3 snapN(vec3 P, vec3 nn) {",
      "   float l = length(nn);",
      "   nn = l > 1e-10 ? nn / l : vec3(0.0, 0.0, 1.0);",
      "   nn = dot(nn, vec3(3.0, 3.0, LEVEL)) < 0.0 ? -nn : nn;",
      "   if (abs(P.z - floor(P.z + 0.5)) < 0.008 || nn.z > 0.94) return vec3(0.0, 0.0, 1.0);",
      "   if (nn.x > 0.94) return vec3(1.0, 0.0, 0.0);",
      "   if (nn.y > 0.94) return vec3(0.0, 1.0, 0.0);",
      "   return nn;",
      "}",
      "vec4 tap(sampler2DArray t, vec2 uv, float layer, float w) {",
      "   vec4 a = textureLod(t, vec3(uv, layer), 0.0);",
      "   if (w > 0.004) a = mix(a, textureLod(t, vec3(uv, layer + 1.0), 0.0), w);",
      "   return a;",
      "}",
      "void main() {",
      "   ivec2 t = ivec2(gl_GlobalInvocationID.xy);",
      "   ivec2 ls = imageSize(outL).xy;",
      "   if (t.x >= ls.x || t.y >= ls.y) return;",
      "   ivec2 ds = textureSize(uDepth, 0);",
      "   vec2 blk = vec2(ds) / vec2(ls);", // chunk texels a layer texel
      // the block's surface: the median depth of nine samples across it (the surface most of the block shows: not the room
      // seen through a window's opening, not a sprite's edge)
      "   vec2 c0 = (vec2(t) + 0.5) * blk;",
      "   const vec2 OFF[9] = vec2[9](vec2(0.0), vec2(-0.25, -0.25), vec2(0.25, -0.25), vec2(-0.25, 0.25), vec2(0.25, 0.25), vec2(0.45, 0.0), vec2(-0.45, 0.0), vec2(0.0, 0.45), vec2(0.0, -0.45));",
      "   float dv[9];",
      "   ivec2 tv[9];",
      "   int nv = 0;",
      "   for (int k = 0; k < 9; k++) {",
      "      ivec2 q = clamp(ivec2(c0 + OFF[k] * blk), ivec2(0), ds - 1);",
      "      float dq = texelFetch(uDepth, q, 0).r;",
      "      if (dq < 1.0) {",
      "         int j = nv++;", // insertion sort by depth
      "         while (j > 0 && dv[j - 1] > dq) { dv[j] = dv[j - 1]; tv[j] = tv[j - 1]; j--; }",
      "         dv[j] = dq; tv[j] = q;",
      "      }",
      "   }",
      "   ivec2 ti = ivec2(c0);",
      "   float d = 1.0;",
      "   if (nv > 0) { d = dv[nv / 2]; ti = tv[nv / 2]; }",
      // a block with leaves in it (a crown's edge over the ground): the native's light, as the leaves get (the ground under a
      // crown is the crown's shadow: its light on the edge leaves made dark specks round every tree)
      "   int leaves = 0;",
      "   for (int k = 0; k < nv; k++) {",
      "      vec3 Pk = world((vec2(tv[k]) + 0.5) / vec2(ds), dv[k]);",
      "      if (Pk.z - floor(Pk.z + 0.006) > 0.12 && ((occAt(int(floor(Pk.x)), int(floor(Pk.y)), int(floor(Pk.z + 0.006))) >> 8) & 15u) != 0u) leaves++;",
      "   }",
      "   if (leaves >= 2) { imageStore(outL, ivec3(t, int(uAff2.z)), vec4(0.0)); return; }",
      // dilation: a block with no surface takes the nearest of the next blocks' (the composite's bilinear tap never mixes an
      // empty texel into its neighbours: dark dashes along every chunk texture's edge and dots round sprite holes)
      "   for (int k = 0; k < 8 && d >= 1.0; k++) {",
      "      vec2 o = vec2(k == 0 || k == 4 || k == 5 ? 1.0 : k == 1 || k == 6 || k == 7 ? -1.0 : 0.0, k == 2 || k == 4 || k == 6 ? 1.0 : k == 3 || k == 5 || k == 7 ? -1.0 : 0.0);",
      "      ti = clamp(ivec2(c0 + o * blk), ivec2(0), ds - 1);",
      "      d = texelFetch(uDepth, ti, 0).r;",
      "   }",
      "   if (d >= 1.0) { imageStore(outL, ivec3(t, int(uAff2.z)), vec4(0.0)); return; }",
      "   vec2 uv = (vec2(ti) + 0.5) / vec2(ds);",
      "   vec3 P = world(uv, d);",
      // dev: a surface point outside its chunk (+ 4 squares for tall sprites): the texture's mapping is wrong (magenta)
      "   if (uLev.w > 0.5 && (P.x < uAff0.z - 4.0 || P.x > uAff0.z + 12.0 || P.y < uAff0.w - 4.0 || P.y > uAff0.w + 12.0 || P.z < uLev.x - 1.0 || P.z > uLev.y + 8.0)) { imageStore(outL, ivec3(t, int(uAff2.z)), vec4(1.0 / 1.25, 0.0, 1.0 / 1.25, 1.0)); return; }",
      "   if (P.z < uWin.z || P.z >= float(uWin.z + LZ)) { imageStore(outL, ivec3(t, int(uAff2.z)), vec4(0.0)); return; }",
      // the normal from the depth K texels to each side (the side with the smaller change: never across an edge)
      "   const int K = 2;",
      "   float xp = texelFetch(uDepth, min(ti + ivec2(K, 0), ds - 1), 0).r, xm = texelFetch(uDepth, max(ti - ivec2(K, 0), ivec2(0)), 0).r;",
      "   float yp = texelFetch(uDepth, min(ti + ivec2(0, K), ds - 1), 0).r, ym = texelFetch(uDepth, max(ti - ivec2(0, K), ivec2(0)), 0).r;",
      "   vec3 n = vec3(0.0, 0.0, 1.0);",
      "   bool isFlat = false;",
      // planar: both neighbours on each axis on the texel's plane (a wall, a floor, a box's face); not planar (leaves, bars, a
      // sprite's edge): no facing, the light flat as the native gives objects
      "   bool planar = xp < 1.0 && xm < 1.0 && yp < 1.0 && ym < 1.0 && abs(xp + xm - 2.0 * d) < 4.0 / 65535.0 + 0.25 * abs(xp - xm)",
      "      && abs(yp + ym - 2.0 * d) < 4.0 / 65535.0 + 0.25 * abs(yp - ym);",
      "   if (!(xp >= 1.0 && xm >= 1.0 || yp >= 1.0 && ym >= 1.0)) {",
      "      float sx = xp < 1.0 && (xm >= 1.0 || abs(xp - d) <= abs(d - xm)) ? 1.0 : -1.0;",
      "      float sy = yp < 1.0 && (ym >= 1.0 || abs(yp - d) <= abs(d - ym)) ? 1.0 : -1.0;",
      "      vec3 px = world(uv + vec2(sx * float(K), 0.0) / vec2(ds), sx > 0.0 ? xp : xm);",
      "      vec3 py = world(uv + vec2(0.0, sy * float(K)) / vec2(ds), sy > 0.0 ? yp : ym);",
      "      vec3 L = vec3(1.0, 1.0, LEVEL);",
      "      vec3 raw = cross((px - P) * L, (py - P) * L);",
      "      isFlat = normalize(dot(raw, vec3(3.0, 3.0, LEVEL)) < 0.0 ? -raw : raw).z > 0.94;", // a horizontal surface by its own normal (the snap makes any texel at a level's height a floor)
      "      n = snapN(P, raw);",
      "   }",
      // the owner level and the volume there, as the composite's per-pixel path
      "   float lz = clamp(floor(P.z + 0.006), uLev.x, uLev.y);",
      // a leaf (a point above the floor inside a tree's crown: a baked tree's depth puts its crown inside the crown's own
      // volume, its normals are noise): the native's light, as the game lights its trees
      "   bool ground = planar && isFlat;",
      "   if (!ground && ((occAt(int(floor(P.x)), int(floor(P.y)), int(floor(P.z + 0.006))) >> 8) & 15u) != 0u) { imageStore(outL, ivec3(t, int(uAff2.z)), vec4(0.0)); return; }",
      // a planar texel (a floor, a wall, a box's face) a quarter square along its normal into the air; any other (plants, bars,
      // a sprite's edge: its normal is noise) the light at the point itself (the floor under it took the shade of the next
      // crown for the top of a small tree beside a big one; the blotches that sampling the floor was meant to stop were the
      // composite's bilinear tap mixing in the native's empty texels, now weighed out)
      "   vec3 Q = planar ? P + n * vec3(0.25, 0.25, 0.25 / LEVEL) : P;",
      "   float fz = clamp(Q.z - lz, 0.5 / float(RZ), 1.0 - 0.5 / float(RZ));",
      "   vec2 sq = floor(Q.xy);",
      "   vec2 qa = Q.xy;",
      "   uint e = texelFetch(uE, ivec3(ivec2(sq) & (N - 1), int(lz) - uWin.z), 0).r;",
      "   float h = 0.5 / float(R);",
      "   if ((e & 1u) != 0u) qa.x = max(qa.x, sq.x + h);",
      "   if ((e & 4u) != 0u) qa.x = min(qa.x, sq.x + 1.0 - h);",
      "   if ((e & 2u) != 0u) qa.y = max(qa.y, sq.y + h);",
      "   if ((e & 8u) != 0u) qa.y = min(qa.y, sq.y + 1.0 - h);",
      "   vec2 rel = qa - vec2(uWin.xy);",
      "   float edge = min(min(rel.x, rel.y), min(float(N) - rel.x, float(N) - rel.y));",
      "   if (edge < 1.0) { imageStore(outL, ivec3(t, int(uAff2.z)), vec4(0.0)); return; }",
      "   vec2 vuv = qa / float(N);",
      "   float tz = fz * float(RZ) - 0.5;",
      "   float k = floor(tz), wz = tz - k;",
      "   float layer = (lz - float(uWin.z)) * float(RZ) + k;",
      "   vec4 T1 = tap(uT1, vuv, layer, wz);",
      "   float valid = clamp(2.0 * T1.a, 0.0, 1.0) * clamp((edge - 1.0) / 4.0, 0.0, 1.0);",
      "   float sky = clamp(2.0 * T1.a - 1.0, 0.0, 1.0);",
      "   float fac = 1.0;",
      "   if (uRef.w != 0 && n.z < 0.95 && planar) {",
      "      vec4 T2 = tap(uT2, vuv, layer, wz);",
      "      if (T2.z > 0.01) {",
      "         vec2 o = T2.xy * 2.0 - 1.0;",
      "         vec3 ld = vec3(o, 1.0 - abs(o.x) - abs(o.y));",
      "         if (ld.z < 0.0) ld.xy = (1.0 - abs(ld.yx)) * vec2(ld.x >= 0.0 ? 1.0 : -1.0, ld.y >= 0.0 ? 1.0 : -1.0);",
      "         ld = normalize(ld);",
      "         float wr = uAff2.w;",
      "         fac = mix(1.0, clamp(max(dot(n, ld) + wr, 0.0) / max(ld.z + wr, 0.15), 0.0, 1.25), T2.z);",
      "      }",
      "   }",
      // rgb: the light (the composite takes it as is, x 1.25 headroom: a facing light may pass the floor's), a: validity and sky
      "   vec3 lit = T1.rgb * fac;",
      "   if (uCube != 0) {", // the bounce arriving against the normal (flux flowing -n), coloured like the flood's
      "      ivec2 cs = ivec2(sq);",
      "      ivec3 cc = ivec3(cs & (N - 1), int(lz) - uWin.z);",
      "      vec4 d0 = texelFetch(uD0, cc, 0), d1 = texelFetch(uD1, cc, 0);",
      "      vec3 col = texelFetch(uCf, cc, 0).rgb;",
      "      col /= max(dot(col, vec3(0.3, 0.5, 0.2)), 1e-4);",
      "      float E = d0.x * max(-n.x, 0.0) + d0.y * max(n.x, 0.0) + d0.z * max(-n.y, 0.0) + d0.w * max(n.y, 0.0) + d1.x * max(-n.z, 0.0) + d1.y * max(n.z, 0.0);",
      "      lit += col * E * uCubeGain;",
      "   }",
      // a: 0 the native's light here; under 0.5 partly the volume's (2a of it, no sky: the window's edge); 0.99 + 0.01 sky the
      // volume's. A filtered alpha under 0.99 tells the composite a native texel is in its footprint: it then weighs the four
      // texels by their validity (one bilinear fetch mixed the native texels' empty rgb in: dark rims round every crown and bush)
      "   imageStore(outL, ivec3(t, int(uAff2.z)), vec4(clamp(lit / 1.25, 0.0, 1.0), valid > 0.5 ? 0.99 + 0.01 * sky : 0.25 * valid));",
      "}");

   /**
    * The composite's side (pixelLight's LIGHT_GLSL, PPL_VOXEL): the voxel light at a pixel, or the native's (Ln) where the
    * volume does not hold it. vxlW: how much of the voxel light the pixel took (the wall gradient of the native fades by it).
    */
   static final String FRAG_GLSL = String.join("\n",
      "#ifdef PPL_VOXEL",
      // the resolved volume (sampler2DArray / usampler2DArray: the game's renumbering of sampler2Ds leaves them alone)
      "layout(binding = 44) uniform sampler2DArray vxlT1;", // rgb: lamps + bounce, a: the sky
      "layout(binding = 45) uniform sampler2DArray vxlT2;", // xy: the lamps' direction (octahedral), z: how directional, w: validity
      "layout(binding = 46) uniform usampler2DArray vxlE;", // per square and level: its blocked edges (1 W, 2 N, 4 E, 8 S)
      "layout(binding = 47) uniform sampler2DArray vxlLM;", // the surface cache: a light layer per chunk texture
      "uniform float vxlLayer = -1.0;", // this draw's layer (set per draw; -1: not lit yet, the volume per pixel; -2: not lit yet, the native's light)
      "vec2 vxlUv = vec2(0.0);", // the chunk texture coordinate (main)
      "uniform vec4 vxlMap;", // x, y: the origin square's world x, y mod N; z: 1 / N; w: on
      "uniform vec4 vxlWin;", // x, y: the window's first square relative to the origin square; z: N; w: the volume's first level
      "uniform vec4 vxlMapZ;", // x: texels a level (RZ); y: half a texel up (levels); z: half a texel across (squares); w: dev view
      "uniform vec4 vxlAmb;", // rgb: the outdoor ambient; w: the least sky a room keeps
      "uniform vec4 vxlOpt;", // x: wrap; y: -; z: ambient mode (1 max, 2 add, 0 native); w: facing on
      "layout(binding = 50) uniform sampler2DArray vxlTS;", // TS_UNIT: the carried torches' visibility maps
      "uniform vec4 vxlTsO[2];", // per torch slot: map origin x, y relative to the origin square, its level, traced (1)
      "uniform ivec2 vxlTsI = ivec2(-1);", // per slot: the light (pplLa index) it belongs to
      "float vxlTorchVis(vec3 P, vec3 n, int slot) {",
      "   vec4 o = vxlTsO[slot];",
      "   if (o.w < 0.5 || floor(P.z + 0.006) != o.z) return 1.0;", // another level: not traced (a light lights its own level)
      "   vec3 Q = P + n * vec3(0.15, 0.15, 0.15 / PPL_LEVEL);", // a hair off the surface, towards the light's side
      "   vec2 uv = (Q.xy - o.xy) / 32.0;",
      "   if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) return 1.0;",
      "   float f = clamp((Q.z - o.z - 0.05) / 0.5, 0.0, 1.0);",
      "   float a = textureLod(vxlTS, vec3(uv, float(slot * 2)), 0.0).r;",
      "   if (f > 0.004) a = mix(a, textureLod(vxlTS, vec3(uv, float(slot * 2 + 1)), 0.0).r, f);",
      "   return a;",
      "}",
      "float vxlW = 0.0;",
      "int vxlCost = 0;", // dev (devPplAlternate masks): 131072 no facing, 262144 no wall clamp, 524288 one layer always
      "vec4 vxlTap(sampler2DArray t, vec2 uv, float layer, float w) {", // one bilinear tap, or two layers mixed (walls)
      "   vec4 a = textureLod(t, vec3(uv, layer), 0.0);",
      "   if (w > 0.004) a = mix(a, textureLod(t, vec3(uv, layer + 1.0), 0.0), w);",
      "   return a;",
      "}",
      "vec3 vxlLight(vec3 P, vec3 n, float lz, vec2 sq, vec3 Ln) {",
      "   vxlW = 0.0;",
      "   if ((vxlCost & 2097152) != 0) { vxlW = 1.0; return vec3(0.5); }", // dev probe: no fetch at all
      "   if ((vxlCost & 1048576) != 0) { vxlW = 1.0; vec4 t = textureLod(vxlT1, vec3((P.xy + vxlMap.xy) * vxlMap.z, (lz - vxlWin.w) * vxlMapZ.x), 0.0); return max(vxlAmb.rgb * t.a, t.rgb); }", // dev probe: one fetch
      "   if (vxlMap.w < 0.5 || vxlLayer < -1.5) return Ln;",
      "   bool useCache = vxlLayer >= 0.0 && (vxlCost & 4194304) == 0;", // the surface cache: one fetch (dev cost bit 4194304: the per-pixel path instead)
      "#ifdef PPL_DEV",
      "   if (int(vxlMapZ.w + 0.5) == 10) useCache = false;", // dev view 10: the voxel lighting per pixel (to compare with view 0)
      "#endif",
      "   if (useCache) {",
      "      vec4 lm = textureLod(vxlLM, vec3(vxlUv, vxlLayer), 0.0);",
      "      float lsky;",
      "      if (lm.a > 0.9899) {", // all four texels the volume's: the one fetch
      "         vxlW = 1.0;",
      "         lsky = clamp((lm.a - 0.99) * 100.0, 0.0, 1.0);",
      "      } else {", // a texel of the native's in the footprint: the four weighed by their validity
      "         vec2 fx = vxlUv * vec2(textureSize(vxlLM, 0).xy) - 0.5;",
      "         ivec2 i0 = ivec2(floor(fx));",
      "         vec2 ff = fx - vec2(i0);",
      "         ivec2 lim = textureSize(vxlLM, 0).xy - 1;",
      "         vec3 rs = vec3(0.0);",
      "         float ws = 0.0, ss = 0.0;",
      "         for (int k = 0; k < 4; k++) {",
      "            ivec2 o = ivec2(k & 1, k >> 1);",
      "            vec4 tk = texelFetch(vxlLM, ivec3(clamp(i0 + o, ivec2(0), lim), int(vxlLayer)), 0);",
      "            float vk = (tk.a > 0.9899 ? 1.0 : 2.0 * tk.a) * (o.x == 1 ? ff.x : 1.0 - ff.x) * (o.y == 1 ? ff.y : 1.0 - ff.y);",
      "            rs += tk.rgb * vk;",
      "            ss += (tk.a > 0.9899 ? clamp((tk.a - 0.99) * 100.0, 0.0, 1.0) : 0.0) * vk;",
      "            ws += vk;",
      "         }",
      "         vxlW = clamp(ws, 0.0, 1.0);",
      "         if (vxlW <= 0.001) return Ln;",
      "         lm.rgb = rs / ws;",
      "         lsky = ss / ws;",
      "      }",
      "      vec3 ca = vxlAmb.rgb * max(lsky, vxlAmb.w), cl = lm.rgb * 1.25;",
      "      vec3 Lc = vxlOpt.z > 1.5 ? ca + cl : vxlOpt.z > 0.5 ? max(ca, cl) : max(Ln, cl);",
      "#ifdef PPL_DEV",
      "      int cv = int(vxlMapZ.w + 0.5);",
      "      if (cv == 1) Lc = cl;",
      "      if (cv == 2) Lc = vec3(lsky);",
      "      if (cv == 4) Lc = vec3(vxlW, 1.0 - vxlW, 0.0);",
      "      if (cv == 6) vxlW = 1.0;",
      "      if (cv == 7 && (P.x - P.y) < (vxlWin.x - vxlWin.y)) { vxlW = 0.0; return Ln; }",
      "      if (cv == 9) Lc = vec3(0.2, 0.2, 1.0);", // dev: blue where the cache lights the pixel
      "#endif",
      "      return mix(Ln, clamp(Lc, 0.0, 1.0), vxlW);",
      "   }",
      "#ifdef PPL_DEV",
      "   if (int(vxlMapZ.w + 0.5) == 9) { vxlW = 1.0; return vec3(1.0, 0.3, 0.2); }", // dev: red where the volume is read per pixel
      "#endif",
      "   if (lz < vxlWin.w || lz > vxlWin.w + 7.0) return Ln;",
      "   vec2 rel = P.xy - vxlWin.xy;",
      "   float edge = min(min(rel.x, rel.y), min(vxlWin.z - rel.x, vxlWin.z - rel.y));",
      "   if (edge < 1.0) return Ln;",
      // a quarter square along the normal into the air, inside the pixel's level
      "   vec3 Q = P + n * vec3(0.25, 0.25, 0.25 / PPL_LEVEL);",
      "   float fz = clamp(Q.z - lz, vxlMapZ.y, 1.0 - vxlMapZ.y);",
      // never filtered across a blocked edge of the square in front of the surface (a window's pane sits a hair inside the
      // wall: its own square is the room): the texels behind a wall are another room's
      "   sq = floor(Q.xy);",
      "   vec2 qa = Q.xy;",
      "   vec2 fq = Q.xy - sq;",
      "   if ((vxlCost & 262144) == 0 && (min(fq.x, fq.y) < vxlMapZ.z || max(fq.x, fq.y) > 1.0 - vxlMapZ.z)) {", // only near an edge does the filter reach the next square
      "      uint e = texelFetch(vxlE, ivec3((ivec2(sq) + ivec2(vxlMap.xy)) & (int(vxlWin.z) - 1), int(lz - vxlWin.w)), 0).r;",
      "      float h = vxlMapZ.z;",
      "      if ((e & 1u) != 0u) qa.x = max(qa.x, sq.x + h);",
      "      if ((e & 4u) != 0u) qa.x = min(qa.x, sq.x + 1.0 - h);",
      "      if ((e & 2u) != 0u) qa.y = max(qa.y, sq.y + h);",
      "      if ((e & 8u) != 0u) qa.y = min(qa.y, sq.y + 1.0 - h);",
      "   }",
      "   vec2 uv = (qa + vxlMap.xy) * vxlMap.z;",
      "   float tz = fz * vxlMapZ.x - 0.5;", // the level's texels: layers k and k + 1, a floor exactly on the first
      "   float k = floor(tz), wz = (vxlCost & 524288) != 0 ? 0.0 : tz - k;",
      "   float layer = (lz - vxlWin.w) * vxlMapZ.x + k;",
      "   vec4 T1 = vxlTap(vxlT1, uv, layer, wz);",
      "   vxlW = clamp(2.0 * T1.a, 0.0, 1.0) * clamp((edge - 1.0) / 4.0, 0.0, 1.0);",
      "   if (vxlW <= 0.001) return Ln;",
      "   float sky = clamp(2.0 * T1.a - 1.0, 0.0, 1.0);",
      "   float fac = 1.0;",
      // the facing: walls and objects (a floor's is 1: the light is the native's, measured on floors)
      "   vec4 T2 = vec4(0.5);",
      "   if (vxlOpt.w > 0.5 && n.z < 0.95 && (vxlCost & 131072) == 0) T2 = vxlTap(vxlT2, uv, layer, wz);",
      "   if (T2.z > 0.01) {",
      "      vec2 o = T2.xy * 2.0 - 1.0;", // octahedral
      "      vec3 ld = vec3(o, 1.0 - abs(o.x) - abs(o.y));",
      "      if (ld.z < 0.0) ld.xy = (1.0 - abs(ld.yx)) * vec2(ld.x >= 0.0 ? 1.0 : -1.0, ld.y >= 0.0 ? 1.0 : -1.0);",
      "      ld = normalize(ld);",
      "      float wr = vxlOpt.x;",
      "      fac = mix(1.0, clamp(max(dot(n, ld) + wr, 0.0) / max(ld.z + wr, 0.15), 0.0, 1.25), T2.z);",
      "   }",
      "   vec3 amb = vxlAmb.rgb * max(sky, vxlAmb.w);",
      "   vec3 Lv = vxlOpt.z > 1.5 ? amb + T1.rgb * fac : vxlOpt.z > 0.5 ? max(amb, T1.rgb * fac) : max(Ln, T1.rgb * fac);",
      "#ifdef PPL_DEV",
      "   int vv = int(vxlMapZ.w + 0.5);",
      "   if (vv == 1) Lv = T1.rgb * fac;",
      "   if (vv == 2) Lv = vec3(sky);",
      "   if (vv == 4) Lv = vec3(vxlW, 1.0 - vxlW, 0.0);",
      "   if (vv == 5) Lv = vec3(fac * 0.5);",
      "   if (vv == 6) vxlW = 1.0;",
      "   if (vv == 7 && (P.x - P.y) < (vxlWin.x - vxlWin.y)) { vxlW = 0.0; return Ln; }", // split: the native left of the window's centre line
      "   if (vv == 8) Lv = n * 0.5 + 0.5;",
      "#endif",
      "   return mix(Ln, clamp(Lv, 0.0, 1.0), vxlW);",
      "}",
      "#endif");
}
